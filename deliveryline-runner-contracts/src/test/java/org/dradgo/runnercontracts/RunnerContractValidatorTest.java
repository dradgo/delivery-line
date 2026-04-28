package org.dradgo.runnercontracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class RunnerContractValidatorTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Path VALID_FIXTURE_DIRECTORY = Path.of("src/test/resources/fixtures/valid");
	private static final Path INVALID_FIXTURE_DIRECTORY = Path.of("src/test/resources/fixtures/invalid");
	private static final Path EXPECTATIONS_MANIFEST = Path.of("src/test/resources/fixtures/fixture-expectations.json");
	private static final Path CONTEXT_SCHEMA = Path.of("src/main/resources/schemas/context-bundle.v1.schema.json");
	private static final Path RUNNER_RESULT_SCHEMA = Path.of("src/main/resources/schemas/runner-result.v1.schema.json");
	private static final List<String> EXPECTED_CLASSIFICATIONS = List.of(
		"local-only",
		"shareable-redacted",
		"shareable-full",
		"derived-public-safe");
	private static final List<String> EXPECTED_ARTIFACT_TYPES = List.of(
		"spec",
		"implementationPlan",
		"prOutput");
	private static final List<String> EXPECTED_FAILURE_CATEGORIES = List.of(
		"runner_timeout",
		"runner_crash",
		"runner_contract_violation",
		"runner_non_zero_exit",
		"runner_late_result",
		"runner_duplicate_result",
		"runner_malformed_output");

	@Test
	void schemaResourcesAndValidatorExist() {
		assertNotNull(RunnerContractValidator.class.getResource("/schemas/context-bundle.v1.schema.json"));
		assertNotNull(RunnerContractValidator.class.getResource("/schemas/runner-result.v1.schema.json"));
		assertDoesNotThrow(RunnerContractValidator::new);
	}

	@Test
	void validFixtureCorpusPassesValidation() throws IOException {
		RunnerContractValidator validator = new RunnerContractValidator();
		List<Path> validFixtures = listJsonFiles(VALID_FIXTURE_DIRECTORY);

		assertEquals(4, validFixtures.size());

		for (Path fixture : validFixtures) {
			ValidationResult result = validator.validateFixture(targetFor(fixture), fixture);
			assertTrue(result.valid(), () -> fixture + " should be valid but had errors: " + result.errors());
		}
	}

	@Test
	void invalidFixtureCorpusFailsWithExpectedTypedError() throws IOException {
		RunnerContractValidator validator = new RunnerContractValidator();
		FixtureExpectations expectations = loadExpectations();
		List<Path> invalidFixtures = listJsonFiles(INVALID_FIXTURE_DIRECTORY);

		assertEquals(invalidFixtures.size(), expectations.invalidFixtures().size());

		for (InvalidFixtureExpectation expectation : expectations.invalidFixtures()) {
			Path fixture = expectation.fixturePath();
			assertTrue(Files.exists(fixture), () -> "Missing invalid fixture: " + fixture);

			ValidationResult result = validator.validateFixture(
				expectation.validationTarget(),
				fixture,
				contextForExpectation(expectation));

			assertFalse(result.valid(), () -> fixture + " should be invalid");
			assertContainsErrorCode(result, expectation.expectedValidationErrorCode());
		}
	}

	@Test
	void schemasExposeCriticalInvariants() throws IOException {
		JsonNode contextSchema = OBJECT_MAPPER.readTree(CONTEXT_SCHEMA.toFile());
		JsonNode runnerResultSchema = OBJECT_MAPPER.readTree(RUNNER_RESULT_SCHEMA.toFile());

		assertEquals("https://json-schema.org/draft/2020-12/schema", contextSchema.path("$schema").asText());
		assertEquals("https://json-schema.org/draft/2020-12/schema", runnerResultSchema.path("$schema").asText());
		assertEquals(1, contextSchema.at("/properties/schemaVersion/const").asInt());
		assertEquals(1, runnerResultSchema.at("/properties/schemaVersion/const").asInt());
		assertEquals(EXPECTED_CLASSIFICATIONS, stringValues(contextSchema.at("/properties/classification/enum")));
		assertEquals(EXPECTED_CLASSIFICATIONS, stringValues(runnerResultSchema.at("/properties/classification/enum")));
		assertEquals(EXPECTED_ARTIFACT_TYPES, stringValues(contextSchema.at("/$defs/artifactReference/properties/artifactType/enum")));
		assertEquals(EXPECTED_ARTIFACT_TYPES, stringValues(runnerResultSchema.at("/$defs/baseArtifact/properties/artifactType/enum")));
		assertEquals(EXPECTED_FAILURE_CATEGORIES, stringValues(runnerResultSchema.at("/properties/failureCategory/anyOf/1/enum")));
		assertTrue(stringValues(contextSchema.path("required")).contains("approvedSpecificationReference"));
		assertTrue(stringValues(runnerResultSchema.path("required")).contains("failureCategory"));
		assertFalse(stringValues(runnerResultSchema.path("required")).contains("rawOutputReference"));
		assertTrue(hasNullOption(contextSchema.at("/properties/approvedSpecificationReference/anyOf")));
		assertEquals(3, runnerResultSchema.at("/properties/artifactReferences/items/oneOf").size());
	}

	@Test
	void artifactVariantsRejectWrongPayloadShapeWhenDiscriminatorChanges() throws IOException {
		RunnerContractValidator validator = new RunnerContractValidator();

		for (Path fixture : List.of(
			VALID_FIXTURE_DIRECTORY.resolve("runner-result.v1.spec.valid.json"),
			VALID_FIXTURE_DIRECTORY.resolve("runner-result.v1.implementation-plan.valid.json"),
			VALID_FIXTURE_DIRECTORY.resolve("runner-result.v1.pr-output.valid.json"))) {
			ObjectNode document = (ObjectNode) OBJECT_MAPPER.readTree(fixture.toFile());
			ObjectNode artifact = (ObjectNode) document.withArray("artifactReferences").get(0);
			String wrongArtifactType = switch (artifact.path("artifactType").asText()) {
				case "spec" -> "implementationPlan";
				case "implementationPlan" -> "prOutput";
				case "prOutput" -> "spec";
				default -> throw new IllegalStateException("Unexpected artifact type in fixture " + fixture);
			};
			artifact.put("artifactType", wrongArtifactType);

			ValidationResult result = validator.validate(
				RunnerContractValidator.ValidationTarget.RUNNER_RESULT,
				OBJECT_MAPPER.writeValueAsBytes(document),
				ValidationContext.defaults());

			assertFalse(result.valid(), () -> fixture + " should fail when artifactType changes");
			assertContainsErrorCode(result, ValidationErrorCode.SCHEMA_VALIDATION_FAILED);
		}
	}

	@Test
	void duplicateRunnerExecutionFixturesUseCrossFixtureContext() throws IOException {
		RunnerContractValidator validator = new RunnerContractValidator();
		Path fixtureA = INVALID_FIXTURE_DIRECTORY.resolve("runner-result.v1.invalid-duplicate-runner-execution-a.json");
		Path fixtureB = INVALID_FIXTURE_DIRECTORY.resolve("runner-result.v1.invalid-duplicate-runner-execution-b.json");
		String fixtureAId = extractRunnerExecutionId(fixtureA);
		String fixtureBId = extractRunnerExecutionId(fixtureB);

		assertEquals(fixtureAId, fixtureBId);

		ValidationResult resultA = validator.validateFixture(
			RunnerContractValidator.ValidationTarget.RUNNER_RESULT,
			fixtureA,
			ValidationContext.builder().addObservedRunnerExecutionId(fixtureBId).build());
		ValidationResult resultB = validator.validateFixture(
			RunnerContractValidator.ValidationTarget.RUNNER_RESULT,
			fixtureB,
			ValidationContext.builder().addObservedRunnerExecutionId(fixtureAId).build());

		assertContainsErrorCode(resultA, ValidationErrorCode.DUPLICATE_RUNNER_EXECUTION_ID);
		assertContainsErrorCode(resultB, ValidationErrorCode.DUPLICATE_RUNNER_EXECUTION_ID);
	}

	@Test
	void oversizedFixtureExceedsDocumentedDefaultLimit() throws IOException {
		RunnerContractValidator validator = new RunnerContractValidator();
		Path fixture = INVALID_FIXTURE_DIRECTORY.resolve("runner-result.v1.invalid-oversized-file.json");

		assertTrue(Files.size(fixture) > ValidationContext.DEFAULT_MAX_PAYLOAD_BYTES);

		ValidationResult result = validator.validateFixture(
			RunnerContractValidator.ValidationTarget.RUNNER_RESULT,
			fixture);

		assertFalse(result.valid());
		assertContainsErrorCode(result, ValidationErrorCode.FILE_TOO_LARGE);
	}

	private static FixtureExpectations loadExpectations() throws IOException {
		return OBJECT_MAPPER.readValue(EXPECTATIONS_MANIFEST.toFile(), FixtureExpectations.class);
	}

	private static ValidationContext contextForExpectation(InvalidFixtureExpectation expectation) throws IOException {
		return switch (expectation.expectedValidationErrorCode()) {
			case DUPLICATE_RUNNER_EXECUTION_ID -> ValidationContext.builder()
				.addObservedRunnerExecutionId(extractRunnerExecutionId(expectation.fixturePath()))
				.build();
			case STALE_METADATA -> ValidationContext.builder()
				.addKnownRunnerExecutionId("rex_known_from_context")
				.build();
			default -> ValidationContext.defaults();
		};
	}

	private static void assertContainsErrorCode(ValidationResult result, ValidationErrorCode expectedCode) {
		assertTrue(
			result.errors().stream().anyMatch(error -> error.code() == expectedCode),
			() -> "Expected error code " + expectedCode + " but got " + result.errors());
	}

	private static String extractRunnerExecutionId(Path fixture) throws IOException {
		return OBJECT_MAPPER.readTree(fixture.toFile()).path("runnerExecutionId").asText();
	}

	private static RunnerContractValidator.ValidationTarget targetFor(Path fixture) {
		return fixture.getFileName().toString().startsWith("context-bundle")
			? RunnerContractValidator.ValidationTarget.CONTEXT_BUNDLE
			: RunnerContractValidator.ValidationTarget.RUNNER_RESULT;
	}

	private static List<Path> listJsonFiles(Path directory) throws IOException {
		try (Stream<Path> paths = Files.list(directory)) {
			return paths
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.toList();
		}
	}

	private static List<String> stringValues(JsonNode node) {
		return StreamSupportFacade.stream(node).map(JsonNode::asText).toList();
	}

	private static boolean hasNullOption(JsonNode node) {
		return StreamSupportFacade.stream(node)
			.anyMatch(option -> "null".equals(option.path("type").asText()));
	}

	private record FixtureExpectations(List<InvalidFixtureExpectation> invalidFixtures) {
	}

	private record InvalidFixtureExpectation(String path, String target, String expectedCode) {
		private Path fixturePath() {
			return Path.of(path);
		}

		private RunnerContractValidator.ValidationTarget validationTarget() {
			return RunnerContractValidator.ValidationTarget.valueOf(target);
		}

		private ValidationErrorCode expectedValidationErrorCode() {
			return ValidationErrorCode.valueOf(expectedCode);
		}
	}

	private static final class StreamSupportFacade {

		private StreamSupportFacade() {
		}

		private static Stream<JsonNode> stream(JsonNode node) {
			return StreamSupport.stream(node.spliterator(), false);
		}
	}
}
