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
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Assembles the {@code context-bundle.v1} document, runs it through {@link RedactionPolicyService},
 * validates it against the contract schema, and returns the redacted bytes plus the effective
 * classification.
 *
 * <p>Story 1.13 AC3: the assembled bundle is redacted <em>before</em> validation and never stored
 * or logged in pre-redaction form. The {@code runnerExecutionId} is pre-reserved by {@code
 * RunnerBroker.dispatch} and threaded through here to satisfy the schema's {@code required:
 * ["runnerExecutionId"]} clause.
 */
@Service
public class ContextBundleService {

  private static final Logger log = LoggerFactory.getLogger(ContextBundleService.class);
  private static final int CONTEXT_BUNDLE_SCHEMA_VERSION = 1;

  private final TicketSummaryProvider ticketSummaryProvider;
  private final ArtifactRecordPort artifactRecordPort;
  private final ApprovalReadPort approvalReadPort;
  private final RedactionPolicyService redactionPolicyService;
  private final RunnerContractValidator contractValidator;
  private final ObjectMapper objectMapper;

  @Autowired
  public ContextBundleService(
      TicketSummaryProvider ticketSummaryProvider,
      ArtifactRecordPort artifactRecordPort,
      ApprovalReadPort approvalReadPort,
      RedactionPolicyService redactionPolicyService,
      RunnerContractValidator contractValidator) {
    this.ticketSummaryProvider =
        Objects.requireNonNull(ticketSummaryProvider, "ticketSummaryProvider");
    this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
    this.approvalReadPort = Objects.requireNonNull(approvalReadPort, "approvalReadPort");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Legacy 4-arg overload kept compilable for unit tests that only exercise the original {@link
   * #create create(...)} path (introduced before story 2.8 added the approvals read source). The
   * {@link #createForSpecInvestigation} sibling method is unusable when the service is constructed
   * via this overload — it will throw {@link IllegalStateException} on use.
   *
   * <p>Production wiring (Spring DI in {@code DeliverylineApplication}) always uses the canonical
   * 5-arg constructor above and passes the real {@link ApprovalReadPort} implementation.
   *
   * @deprecated Tests added or modified for story 2.8+ should construct with an explicit {@link
   *     ApprovalReadPort} (mocked when not exercising approval-sourced paths).
   */
  @Deprecated(forRemoval = false)
  public ContextBundleService(
      TicketSummaryProvider ticketSummaryProvider,
      ArtifactRecordPort artifactRecordPort,
      RedactionPolicyService redactionPolicyService,
      RunnerContractValidator contractValidator) {
    this(
        ticketSummaryProvider,
        artifactRecordPort,
        UnwiredApprovalReadPort.INSTANCE,
        redactionPolicyService,
        contractValidator);
  }

  /**
   * Marker {@link ApprovalReadPort} that fails fast when invoked. Used by the legacy 4-arg
   * constructor so test sites that don't exercise spec-investigation continue to compile but cannot
   * silently produce empty approval reads.
   */
  private enum UnwiredApprovalReadPort implements ApprovalReadPort {
    INSTANCE;

    @Override
    public Optional<ApprovalSnapshot> findLatestApprovedForArtifactLineage(
        String workflowRunPublicId, String artifactType) {
      throw unwired();
    }

    @Override
    public List<ApprovalSnapshot> listByWorkflowRunAndArtifactType(
        String workflowRunPublicId, String artifactType) {
      throw unwired();
    }

    @Override
    public List<ApprovalSnapshot> listRejectionsByWorkflowRunAndArtifactType(
        String workflowRunPublicId, String artifactType) {
      throw unwired();
    }

    private static IllegalStateException unwired() {
      return new IllegalStateException(
          "ContextBundleService was constructed via the legacy 4-arg overload; "
              + "approvals-sourced paths (createForSpecInvestigation) require the 5-arg "
              + "constructor with a real ApprovalReadPort");
    }
  }

  public ContextBundle create(
      String workflowRunPublicId,
      RunnerStage stage,
      String reservedRunnerExecutionId,
      int contextBundleVersion,
      ExecutionConstraints executionConstraints,
      DataClassification claimedClassification,
      ActorContext actor) {
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

    Optional<ArtifactRecordSnapshot> approvedSpec =
        latestAvailable(workflowRunPublicId, ArtifactType.SPEC);
    List<ArtifactRecordSnapshot> artifactReferences =
        collectAvailableArtifacts(workflowRunPublicId);

    ObjectNode root =
        assemble(
            workflowRunPublicId,
            reservedRunnerExecutionId,
            ticket,
            approvedSpec,
            artifactReferences,
            executionConstraints,
            claimedClassification);

    RedactionResult redaction = redactionPolicyService.redact(root, claimedClassification.value());
    JsonNode redactedJson =
        synchronizeClassification(
            redaction.sanitizedJson() == null ? root : redaction.sanitizedJson(),
            redaction.effectiveClassification());
    byte[] redactedBytes = serialize(redactedJson);

    ValidationContext validationContext = ValidationContext.defaults();
    ValidationResult result =
        contractValidator.validate(
            ValidationTarget.CONTEXT_BUNDLE, redactedBytes, validationContext);
    if (!result.valid()) {
      log.warn(
          "create context-bundle rejected workflowRunId={} runnerExecutionId={} errorCount={}",
          workflowRunPublicId,
          reservedRunnerExecutionId,
          result.errors().size());
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
    log.info(
        "create context-bundle ok workflowRunId={} runnerExecutionId={} stage={} version={} classification={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        stage.value(),
        contextBundleVersion,
        redaction.effectiveClassification().value());
    return new ContextBundle(
        workflowRunPublicId,
        stage,
        reservedRunnerExecutionId,
        contextBundleVersion,
        redaction.effectiveClassification(),
        redactedBytes);
  }

  /**
   * Composes a context bundle for the <em>spec-investigation</em> use case (story 2.8 AC3-5). The
   * investigation stage is reused ({@link RunnerStage#INVESTIGATION}); the semantic distinction
   * lives in this method, not in the registry.
   *
   * <p>Differs from {@link #create create(...)} in <em>what</em> goes into the bundle:
   *
   * <ul>
   *   <li>{@code approvedSpecificationReference} is always {@code null} (spec doesn't exist yet at
   *       investigation entry — FR7);
   *   <li>{@code priorFeedbackReferences} is sourced from the {@code approvals} table's {@code
   *       decision='rejected'} rows for {@link ArtifactType#SPEC} in this run, chronological
   *       ascending. Each row contributes a {@code {referenceId: apr_…, kind: "spec.rejection"}}
   *       entry — this replaces the parent-walking source used by the execution-stage path;
   *   <li>{@code artifactReferences} carries the prior spec versions in this run (ascending version
   *       order). On true bootstrap (no prior spec) the array is empty — Task 5 relaxes the v1
   *       schema's {@code minItems: 1} to {@code 0} to accommodate this.
   * </ul>
   *
   * <p>The redaction + validation + serialization machinery is shared with {@link #create
   * create(...)} verbatim; only composition diverges.
   */
  public ContextBundle createForSpecInvestigation(
      String workflowRunPublicId,
      String reservedRunnerExecutionId,
      int contextBundleVersion,
      ExecutionConstraints executionConstraints,
      DataClassification claimedClassification,
      ActorContext actor) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(reservedRunnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(executionConstraints, "executionConstraints");
    Objects.requireNonNull(claimedClassification, "claimedClassification");
    Objects.requireNonNull(actor, "actor");
    if (contextBundleVersion <= 0) {
      throw new IllegalArgumentException("contextBundleVersion must be positive");
    }

    log.info(
        "createForSpecInvestigation entry workflowRunId={} runnerExecutionId={} version={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        contextBundleVersion);

    TicketSummary ticket = ticketSummaryProvider.fetchByWorkflowRun(workflowRunPublicId);
    if (ticket == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.INVESTIGATION.value());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Ticket summary unavailable for spec-investigation bundle composition",
          details);
    }

    List<ApprovalSnapshot> rejections =
        approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(
            workflowRunPublicId, ArtifactType.SPEC.value());

    List<ArtifactRecordSnapshot> priorSpecVersions =
        artifactRecordPort.listByWorkflowRunIdAndArtifactType(
            workflowRunPublicId, ArtifactType.SPEC.value());

    ObjectNode root =
        assembleForSpecInvestigation(
            workflowRunPublicId,
            reservedRunnerExecutionId,
            ticket,
            rejections,
            priorSpecVersions,
            executionConstraints,
            claimedClassification);

    RedactionResult redaction;
    try {
      redaction = redactionPolicyService.redact(root, DataClassification.SHAREABLE_REDACTED.value());
    } catch (RuntimeException e) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.INVESTIGATION.value());
      details.put("cause", e.getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Redaction failure during spec-investigation bundle composition",
          details);
    }
    JsonNode redactedJson =
        synchronizeClassification(
            redaction.sanitizedJson() == null ? root : redaction.sanitizedJson(),
            DataClassification.SHAREABLE_REDACTED);
    byte[] redactedBytes = serialize(redactedJson);

    ValidationContext validationContext = ValidationContext.defaults();
    ValidationResult result =
        contractValidator.validate(
            ValidationTarget.CONTEXT_BUNDLE, redactedBytes, validationContext);
    if (!result.valid()) {
      log.warn(
          "createForSpecInvestigation context-bundle rejected workflowRunId={} runnerExecutionId={} errorCount={}",
          workflowRunPublicId,
          reservedRunnerExecutionId,
          result.errors().size());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.INVESTIGATION.value());
      details.put("validationErrors", result.errors());
      throw new DomainException(
          DomainErrorCode.RUNNER_CONTRACT_VIOLATION,
          "Spec-investigation context bundle failed contract validation",
          details);
    }
    log.info(
        "createForSpecInvestigation ok workflowRunId={} runnerExecutionId={} stage={} version={} classification={} priorRejectionCount={} priorSpecVersionCount={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        RunnerStage.INVESTIGATION.value(),
        contextBundleVersion,
        DataClassification.SHAREABLE_REDACTED.value(),
        rejections.size(),
        priorSpecVersions.size());
    return new ContextBundle(
        workflowRunPublicId,
        RunnerStage.INVESTIGATION,
        reservedRunnerExecutionId,
        contextBundleVersion,
        DataClassification.SHAREABLE_REDACTED,
        redactedBytes);
  }

  private ObjectNode assembleForSpecInvestigation(
      String workflowRunPublicId,
      String runnerExecutionId,
      TicketSummary ticket,
      List<ApprovalSnapshot> priorRejections,
      List<ArtifactRecordSnapshot> priorSpecVersions,
      ExecutionConstraints executionConstraints,
      DataClassification classification) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("schemaVersion", CONTEXT_BUNDLE_SCHEMA_VERSION);
    root.put("workflowRunId", workflowRunPublicId);
    root.put("runnerExecutionId", runnerExecutionId);

    ObjectNode ticketNode = root.putObject("ticketSummary");
    ticketNode.put("ticketRef", ticket.ticketRef());
    ticketNode.put("title", ticket.title());
    ticketNode.put("summary", ticket.summary());

    // No approved spec exists during spec-investigation (story 2.8 AC3 — investigation produces
    // the spec).
    root.putNull("approvedSpecificationReference");

    ArrayNode priorFeedbackRefsNode = root.putArray("priorFeedbackReferences");
    for (ApprovalSnapshot rejection : priorRejections) {
      ObjectNode feedbackNode = priorFeedbackRefsNode.addObject();
      feedbackNode.put("referenceId", rejection.publicId());
      feedbackNode.put("kind", "spec.rejection");
    }

    ArrayNode artifactRefsNode = root.putArray("artifactReferences");
    for (ArtifactRecordSnapshot priorSpec : priorSpecVersions) {
      // Defense-in-depth: the port query filters by artifact_type = 'spec', but a future
      // repository / query regression must NOT silently leak non-spec rows into the bundle.
      if (priorSpec.artifactType() != ArtifactType.SPEC) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowRunId", workflowRunPublicId);
        details.put("runnerExecutionId", runnerExecutionId);
        details.put("offendingArtifactId", priorSpec.publicId());
        details.put("actualArtifactType", priorSpec.artifactType().value());
        details.put("expectedArtifactType", ArtifactType.SPEC.value());
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "Non-spec artifact leaked into spec-investigation priorSpecVersions",
            details);
      }
      writeArtifactReference(artifactRefsNode.addObject(), priorSpec);
    }

    ObjectNode constraintsNode = root.putObject("executionConstraints");
    constraintsNode.put("timeoutSeconds", executionConstraints.timeoutSeconds());
    constraintsNode.put("allowRawOutput", executionConstraints.allowRawOutput());

    root.put("classification", classification.value());
    return root;
  }

  private ObjectNode assemble(
      String workflowRunPublicId,
      String runnerExecutionId,
      TicketSummary ticket,
      Optional<ArtifactRecordSnapshot> approvedSpec,
      List<ArtifactRecordSnapshot> artifactReferences,
      ExecutionConstraints executionConstraints,
      DataClassification classification) {
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
    for (PriorFeedbackReference feedbackReference :
        collectPriorFeedbackReferences(artifactReferences)) {
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
    boolean referenceAvailable = isReferencableArtifact(snapshot);
    target.put("artifactStatus", snapshot.status().value());
    target.put("referenceAvailable", referenceAvailable);
    if (referenceAvailable) {
      target.put("referencePath", snapshot.storageRef());
      target.putNull("unavailableReason");
    } else {
      target.putNull("referencePath");
      target.put("unavailableReason", unavailableArtifactReason(snapshot));
    }
  }

  private boolean isReferencableArtifact(ArtifactRecordSnapshot snapshot) {
    return snapshot.status() == ArtifactStatus.AVAILABLE
        && snapshot.storageRef() != null
        && !snapshot.storageRef().isBlank();
  }

  private String unavailableArtifactReason(ArtifactRecordSnapshot snapshot) {
    if (snapshot.status() != ArtifactStatus.AVAILABLE) {
      return "artifact_status_" + snapshot.status().value();
    }
    return "storage_ref_missing";
  }

  private Optional<ArtifactRecordSnapshot> latestAvailable(
      String workflowRunPublicId, ArtifactType type) {
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

  private List<PriorFeedbackReference> collectPriorFeedbackReferences(
      List<ArtifactRecordSnapshot> artifactReferences) {
    Map<String, PriorFeedbackReference> references = new java.util.LinkedHashMap<>();
    for (ArtifactRecordSnapshot snapshot : artifactReferences) {
      String parentArtifactId = snapshot.parentArtifactId();
      while (parentArtifactId != null
          && !parentArtifactId.isBlank()
          && !references.containsKey(parentArtifactId)) {
        Optional<ArtifactRecordSnapshot> prior =
            artifactRecordPort.findByPublicId(parentArtifactId);
        if (prior.isEmpty()) {
          break;
        }
        ArtifactRecordSnapshot priorSnapshot = prior.get();
        references.put(
            parentArtifactId,
            new PriorFeedbackReference(parentArtifactId, priorSnapshot.artifactType().value()));
        parentArtifactId = priorSnapshot.parentArtifactId();
      }
    }
    return List.copyOf(references.values());
  }

  private record PriorFeedbackReference(String referenceId, String kind) {}

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

  private JsonNode synchronizeClassification(
      JsonNode redactedJson, DataClassification effectiveClassification) {
    if (redactedJson instanceof ObjectNode objectNode) {
      objectNode.put("classification", effectiveClassification.value());
    }
    return redactedJson;
  }
}
