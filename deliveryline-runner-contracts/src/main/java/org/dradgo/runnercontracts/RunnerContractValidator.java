package org.dradgo.runnercontracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RunnerContractValidator {

	private static final String CONTEXT_BUNDLE_ID = "https://deliveryline.local/runner-contracts/context-bundle.v1.schema.json";
	private static final String RUNNER_RESULT_ID = "https://deliveryline.local/runner-contracts/runner-result.v1.schema.json";
	private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("(^[A-Za-z]:\\\\)|(^/)|\\.\\./|\\.\\.\\\\");
	private static final Pattern SECRET_PATTERN = Pattern.compile(
		"(ghp_[A-Za-z0-9]+)|(github_pat_[A-Za-z0-9_]+)|(-----BEGIN [A-Z ]*PRIVATE KEY-----)|(Authorization: Bearer )|(token=)",
		Pattern.CASE_INSENSITIVE);

	private final ObjectMapper objectMapper;
	private final SchemaRegistry schemaRegistry;

	public RunnerContractValidator() {
		this.objectMapper = new ObjectMapper();
		this.schemaRegistry = SchemaRegistry.withDefaultDialect(
			SpecificationVersion.DRAFT_2020_12,
			builder -> builder.schemaIdResolvers(resolvers ->
				resolvers.mapPrefix("https://deliveryline.local/runner-contracts", "classpath:schemas")));
	}

	public ValidationResult validateFixture(ValidationTarget target, Path fixturePath) {
		return validateFixture(target, fixturePath, ValidationContext.defaults());
	}

	public ValidationResult validateFixture(ValidationTarget target, Path fixturePath, ValidationContext context) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(fixturePath, "fixturePath");
		Objects.requireNonNull(context, "context");
		try {
			byte[] payload = Files.readAllBytes(fixturePath);
			return validate(target, payload, context);
		} catch (IOException exception) {
			return invalid(new ValidationError(
				ValidationErrorCode.JSON_PARSE_FAILED,
				fixturePath.toString(),
				"Unable to read fixture: " + exception.getMessage()));
		}
	}

	public ValidationResult validate(ValidationTarget target, byte[] payload, ValidationContext context) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(payload, "payload");
		Objects.requireNonNull(context, "context");
		if (payload.length > context.maxPayloadBytes()) {
			return invalid(new ValidationError(
				ValidationErrorCode.FILE_TOO_LARGE,
				"$",
				"Payload exceeds maxPayloadBytes=" + context.maxPayloadBytes()));
		}

		JsonNode document;
		try {
			document = objectMapper.readTree(payload);
		} catch (IOException exception) {
			return invalid(new ValidationError(
				ValidationErrorCode.JSON_PARSE_FAILED,
				"$",
				"Payload is not valid JSON: " + exception.getMessage()));
		}

		List<ValidationError> errors = new ArrayList<>(schemaErrors(target, payload));
		errors.addAll(semanticErrors(document, context));
		if (errors.isEmpty()) {
			return new ValidationResult(true, List.of());
		}
		return new ValidationResult(false, List.copyOf(errors));
	}

	private List<ValidationError> schemaErrors(ValidationTarget target, byte[] payload) {
		Schema schema = schemaRegistry.getSchema(SchemaLocation.of(target.schemaId()));
		Result validationResult = schema.walk(new String(payload, StandardCharsets.UTF_8), InputFormat.JSON, true);
		List<Error> schemaErrors = validationResult.getErrors();
		List<ValidationError> errors = new ArrayList<>();
		for (Error error : schemaErrors) {
			errors.add(new ValidationError(
				ValidationErrorCode.SCHEMA_VALIDATION_FAILED,
				error.getInstanceLocation() == null ? "$" : error.getInstanceLocation().toString(),
				error.getMessage()));
		}
		return errors;
	}

	private List<ValidationError> semanticErrors(JsonNode document, ValidationContext context) {
		List<ValidationError> errors = new ArrayList<>();
		String runnerExecutionId = textValue(document, "runnerExecutionId");
		if (runnerExecutionId != null && context.observedRunnerExecutionIds().contains(runnerExecutionId)) {
			errors.add(new ValidationError(
				ValidationErrorCode.DUPLICATE_RUNNER_EXECUTION_ID,
				"/runnerExecutionId",
				"Duplicate runnerExecutionId: " + runnerExecutionId));
		}
		if (runnerExecutionId != null
			&& !context.knownRunnerExecutionIds().isEmpty()
			&& !context.knownRunnerExecutionIds().contains(runnerExecutionId)) {
			errors.add(new ValidationError(
				ValidationErrorCode.STALE_METADATA,
				"/runnerExecutionId",
				"Unknown runnerExecutionId: " + runnerExecutionId));
		}
		inspectArtifactReferencePaths(document.path("approvedSpecificationReference"), "$/approvedSpecificationReference", errors);
		inspectArtifactReferencePaths(document.path("artifactReferences"), "$/artifactReferences", errors);
		String classification = textValue(document, "classification");
		String documentText = document.toString();
		if ("shareable-full".equals(classification) && SECRET_PATTERN.matcher(documentText).find()) {
			errors.add(new ValidationError(
				ValidationErrorCode.METADATA_SPOOFING_DETECTED,
				"/classification",
				"shareable-full payload contains secret-looking content"));
		}
		return errors;
	}

	private void inspectArtifactReferencePaths(JsonNode node, String path, List<ValidationError> errors) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return;
		}
		if (node.isTextual() && PATH_TRAVERSAL_PATTERN.matcher(node.textValue()).find()) {
			errors.add(new ValidationError(
				ValidationErrorCode.PATH_TRAVERSAL_DETECTED,
				path,
				"Path traversal or absolute path detected"));
		}
		if (node.isObject()) {
			node.fields().forEachRemaining(entry -> inspectArtifactReferencePaths(entry.getValue(), path + "/" + entry.getKey(), errors));
			return;
		}
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				inspectArtifactReferencePaths(node.get(index), path + "/" + index, errors);
			}
		}
	}

	private static String textValue(JsonNode node, String fieldName) {
		JsonNode child = node.get(fieldName);
		return child != null && child.isTextual() ? child.textValue() : null;
	}

	private static ValidationResult invalid(ValidationError error) {
		return new ValidationResult(false, List.of(error));
	}

	public enum ValidationTarget {
		CONTEXT_BUNDLE(CONTEXT_BUNDLE_ID),
		RUNNER_RESULT(RUNNER_RESULT_ID);

		private final String schemaId;

		ValidationTarget(String schemaId) {
			this.schemaId = schemaId;
		}

		public String schemaId() {
			return schemaId;
		}
	}
}
