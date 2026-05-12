package org.dradgo.application.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@code context-bundle.v1} document, runs it through
 * {@link RedactionPolicyService}, validates it against the contract schema, and returns the
 * redacted bytes plus the effective classification.
 *
 * <p>Story 1.13 AC3: the assembled bundle is redacted <em>before</em> validation and never
 * stored or logged in pre-redaction form. The {@code runnerExecutionId} is pre-reserved by
 * {@code RunnerBroker.dispatch} and threaded through here to satisfy the schema's
 * {@code required: ["runnerExecutionId"]} clause.
 */
@Service
public class ContextBundleService {

	private static final Logger log = LoggerFactory.getLogger(ContextBundleService.class);
	private static final int CONTEXT_BUNDLE_SCHEMA_VERSION = 1;

	private final TicketSummaryProvider ticketSummaryProvider;
	private final ArtifactRecordPort artifactRecordPort;
	private final RedactionPolicyService redactionPolicyService;
	private final RunnerContractValidator contractValidator;
	private final ObjectMapper objectMapper;

	public ContextBundleService(
		TicketSummaryProvider ticketSummaryProvider,
		ArtifactRecordPort artifactRecordPort,
		RedactionPolicyService redactionPolicyService,
		RunnerContractValidator contractValidator
	) {
		this.ticketSummaryProvider = Objects.requireNonNull(ticketSummaryProvider, "ticketSummaryProvider");
		this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
		this.redactionPolicyService = Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
		this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
		this.objectMapper = new ObjectMapper();
	}

	public ContextBundle create(
		String workflowRunPublicId,
		RunnerStage stage,
		String reservedRunnerExecutionId,
		int contextBundleVersion,
		ExecutionConstraints executionConstraints,
		DataClassification claimedClassification,
		ActorContext actor
	) {
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		PublicIdPrefixes.require(reservedRunnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(executionConstraints, "executionConstraints");
		Objects.requireNonNull(claimedClassification, "claimedClassification");
		Objects.requireNonNull(actor, "actor");
		if (contextBundleVersion <= 0) {
			throw new IllegalArgumentException("contextBundleVersion must be positive");
		}

		TicketSummary ticket = ticketSummaryProvider.fetchByWorkflowRun(workflowRunPublicId);

		Optional<ArtifactRecordSnapshot> approvedSpec = latestAvailable(workflowRunPublicId, ArtifactType.SPEC);
		List<ArtifactRecordSnapshot> artifactReferences = collectAvailableArtifacts(workflowRunPublicId);

		ObjectNode root = assemble(
			workflowRunPublicId,
			reservedRunnerExecutionId,
			ticket,
			approvedSpec,
			artifactReferences,
			executionConstraints,
			claimedClassification);

		RedactionResult redaction = redactionPolicyService.redact(root, claimedClassification.value());
		JsonNode redactedJson = redaction.sanitizedJson() == null ? root : redaction.sanitizedJson();
		byte[] redactedBytes = serialize(redactedJson);

		ValidationContext validationContext = ValidationContext.defaults();
		ValidationResult result = contractValidator.validate(ValidationTarget.CONTEXT_BUNDLE, redactedBytes, validationContext);
		if (!result.valid()) {
			log.warn("create context-bundle rejected workflowRunId={} runnerExecutionId={} errorCount={}",
				workflowRunPublicId, reservedRunnerExecutionId, result.errors().size());
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("workflowRunId", workflowRunPublicId);
			details.put("runnerExecutionId", reservedRunnerExecutionId);
			details.put("stage", stage.value());
			details.put("validationErrors", result.errors());
			throw new DomainException(
				DomainErrorCode.RUNNER_CONTRACT_VIOLATION,
				"Context bundle failed contract validation",
				details);
		}
		log.info("create context-bundle ok workflowRunId={} runnerExecutionId={} stage={} version={} classification={}",
			workflowRunPublicId, reservedRunnerExecutionId, stage.value(), contextBundleVersion,
			redaction.effectiveClassification().value());
		return new ContextBundle(
			workflowRunPublicId,
			stage,
			reservedRunnerExecutionId,
			contextBundleVersion,
			redaction.effectiveClassification(),
			redactedBytes);
	}

	private ObjectNode assemble(
		String workflowRunPublicId,
		String runnerExecutionId,
		TicketSummary ticket,
		Optional<ArtifactRecordSnapshot> approvedSpec,
		List<ArtifactRecordSnapshot> artifactReferences,
		ExecutionConstraints executionConstraints,
		DataClassification classification
	) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("schemaVersion", CONTEXT_BUNDLE_SCHEMA_VERSION);
		root.put("workflowRunId", workflowRunPublicId);
		root.put("runnerExecutionId", runnerExecutionId);

		ObjectNode ticketNode = root.putObject("ticketSummary");
		ticketNode.put("ticketRef", ticket.ticketRef());
		ticketNode.put("title", ticket.title());
		ticketNode.put("summary", ticket.summary());

		if (approvedSpec.isPresent()) {
			writeArtifactReference(root.putObject("approvedSpecificationReference"), approvedSpec.get());
		} else {
			root.putNull("approvedSpecificationReference");
		}

		ArrayNode priorFeedbackRefsNode = root.putArray("priorFeedbackReferences");
		for (PriorFeedbackReference feedbackReference : collectPriorFeedbackReferences(artifactReferences)) {
			ObjectNode feedbackNode = priorFeedbackRefsNode.addObject();
			feedbackNode.put("referenceId", feedbackReference.referenceId());
			feedbackNode.put("kind", feedbackReference.kind());
		}

		ArrayNode artifactRefsNode = root.putArray("artifactReferences");
		for (ArtifactRecordSnapshot ref : artifactReferences) {
			writeArtifactReference(artifactRefsNode.addObject(), ref);
		}

		ObjectNode constraintsNode = root.putObject("executionConstraints");
		constraintsNode.put("timeoutSeconds", executionConstraints.timeoutSeconds());
		constraintsNode.put("allowRawOutput", executionConstraints.allowRawOutput());

		root.put("classification", classification.value());
		return root;
	}

	private void writeArtifactReference(ObjectNode target, ArtifactRecordSnapshot snapshot) {
		target.put("artifactId", snapshot.publicId());
		target.put("artifactType", snapshot.artifactType().value());
		target.put("referencePath", snapshot.storageRef() == null ? "" : snapshot.storageRef());
	}

	private Optional<ArtifactRecordSnapshot> latestAvailable(String workflowRunPublicId, ArtifactType type) {
		return artifactRecordPort
			.findLatestByWorkflowRunIdAndArtifactType(workflowRunPublicId, type.value())
			.filter(snapshot -> snapshot.status() == ArtifactStatus.AVAILABLE);
	}

	private List<ArtifactRecordSnapshot> collectAvailableArtifacts(String workflowRunPublicId) {
		List<ArtifactRecordSnapshot> collected = new java.util.ArrayList<>();
		for (ArtifactType type : ArtifactType.values()) {
			latestAvailable(workflowRunPublicId, type).ifPresent(collected::add);
		}
		return collected;
	}

	private List<PriorFeedbackReference> collectPriorFeedbackReferences(List<ArtifactRecordSnapshot> artifactReferences) {
		Map<String, PriorFeedbackReference> references = new java.util.LinkedHashMap<>();
		for (ArtifactRecordSnapshot snapshot : artifactReferences) {
			String parentArtifactId = snapshot.parentArtifactId();
			while (parentArtifactId != null && !parentArtifactId.isBlank() && !references.containsKey(parentArtifactId)) {
				Optional<ArtifactRecordSnapshot> prior = artifactRecordPort.findByPublicId(parentArtifactId);
				if (prior.isEmpty()) {
					break;
				}
				ArtifactRecordSnapshot priorSnapshot = prior.get();
				references.put(parentArtifactId, new PriorFeedbackReference(
					parentArtifactId,
					priorSnapshot.artifactType().value()));
				parentArtifactId = priorSnapshot.parentArtifactId();
			}
		}
		return List.copyOf(references.values());
	}

	private record PriorFeedbackReference(String referenceId, String kind) {
	}

	private byte[] serialize(JsonNode node) {
		try {
			return objectMapper.writeValueAsBytes(node);
		} catch (JsonProcessingException error) {
			throw new DomainException(
				DomainErrorCode.INTERNAL_ERROR,
				"Failed to serialize context-bundle JSON",
				Map.of("cause", error.getMessage()),
				error);
		}
	}
}
