package org.dradgo.application.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.runner.spi.TicketSummaryProvider;
import org.dradgo.application.runner.workspace.RepoManifestRef;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.spi.RepoTreeEntry;
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
  // ValidationContext.DEFAULT_MAX_PAYLOAD_BYTES (2 KB) is far too small for a real context bundle:
  // the execution (pr-output) bundle carries the approved-spec ref + approved-plan ref + one
  // artifactReferences entry per available artifact + the repo tree summary (capped at
  // RepositoryWorkspaceService.TREE_ENTRY_CAP=200 entries with mount-relative paths). Such bundles
  // routinely exceed 2 KB and were rejected FILE_TOO_LARGE -> RUNNER_CONTRACT_VIOLATION -> run
  // Failed. Raise the produce-time cap to 256 KB — the bundle is reference-by-id and NEVER embeds
  // artifact bodies, so it stays well-bounded (the twin of
  // RunnerBroker.RUNNER_RESULT_MAX_PAYLOAD_BYTES,
  // which raised the runner-RESULT cap for the same reason).
  private static final int CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES = 256 * 1024;

  private final TicketSummaryProvider ticketSummaryProvider;
  private final ArtifactRecordPort artifactRecordPort;
  private final ApprovalReadPort approvalReadPort;
  private final ClarificationReadPort clarificationReadPort;
  private final RedactionPolicyService redactionPolicyService;
  private final RunnerContractValidator contractValidator;
  private final ObjectMapper objectMapper;

  /**
   * Canonical constructor (story 3.10 — gains {@link ClarificationReadPort}). Production wiring
   * (Spring DI) always uses this 6-arg form with the real port implementations. The {@link
   * ClarificationReadPort} sources the incorporated-clarification {@code priorFeedbackReferences}
   * entries on the execution-stage composition path (AC1).
   */
  @Autowired
  public ContextBundleService(
      TicketSummaryProvider ticketSummaryProvider,
      ArtifactRecordPort artifactRecordPort,
      ApprovalReadPort approvalReadPort,
      ClarificationReadPort clarificationReadPort,
      RedactionPolicyService redactionPolicyService,
      RunnerContractValidator contractValidator) {
    this.ticketSummaryProvider =
        Objects.requireNonNull(ticketSummaryProvider, "ticketSummaryProvider");
    this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
    this.approvalReadPort = Objects.requireNonNull(approvalReadPort, "approvalReadPort");
    this.clarificationReadPort =
        Objects.requireNonNull(clarificationReadPort, "clarificationReadPort");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Legacy 5-arg overload (story 2.8 shape) kept compilable for tests that exercise {@link #create
   * create(...)} / {@link #createForSpecInvestigation} but NOT the story-3.10 incorporated
   * -clarification source. Chains through {@link UnwiredClarificationReadPort} so such sites
   * compile but cannot silently read empty clarifications on the execution sub-stage path.
   */
  public ContextBundleService(
      TicketSummaryProvider ticketSummaryProvider,
      ArtifactRecordPort artifactRecordPort,
      ApprovalReadPort approvalReadPort,
      RedactionPolicyService redactionPolicyService,
      RunnerContractValidator contractValidator) {
    this(
        ticketSummaryProvider,
        artifactRecordPort,
        approvalReadPort,
        UnwiredClarificationReadPort.INSTANCE,
        redactionPolicyService,
        contractValidator);
  }

  /**
   * Legacy 4-arg overload kept compilable for unit tests that only exercise the original {@link
   * #create create(...)} path (introduced before story 2.8 added the approvals read source). The
   * {@link #createForSpecInvestigation} sibling method and the story-3.10 execution sub-stage path
   * are unusable when the service is constructed via this overload — they will throw {@link
   * IllegalStateException} on use.
   *
   * <p>Production wiring (Spring DI in {@code DeliverylineApplication}) always uses the canonical
   * 6-arg constructor above and passes the real {@link ApprovalReadPort} + {@link
   * ClarificationReadPort} implementations.
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
        UnwiredClarificationReadPort.INSTANCE,
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
              + "approvals-sourced paths (createForSpecInvestigation) require the 6-arg "
              + "constructor with a real ApprovalReadPort");
    }
  }

  /**
   * Marker {@link ClarificationReadPort} that fails fast when invoked. Used by the legacy 4-arg and
   * 5-arg constructors so test sites that don't exercise the story-3.10 execution sub-stage path
   * continue to compile but cannot silently produce empty incorporated-clarification reads.
   */
  private enum UnwiredClarificationReadPort implements ClarificationReadPort {
    INSTANCE;

    @Override
    public Optional<Clarification> findByPublicId(String clarificationPublicId) {
      throw unwired();
    }

    @Override
    public Optional<Clarification> findByPublicIdForUpdate(
        String workflowRunPublicId, String clarificationPublicId) {
      throw unwired();
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
      throw unwired();
    }

    @Override
    public List<Clarification> listByWorkflowRunId(String workflowRunPublicId) {
      throw unwired();
    }

    @Override
    public List<Clarification> listByArtifactId(String artifactPublicId) {
      throw unwired();
    }

    @Override
    public int countPendingByWorkflowRun(String workflowRunPublicId) {
      throw unwired();
    }

    @Override
    public Optional<ClarificationLifecycleSnapshot> findLifecycleSnapshotByPublicId(
        String clarificationPublicId) {
      throw unwired();
    }

    private static IllegalStateException unwired() {
      return new IllegalStateException(
          "ContextBundleService was constructed via the legacy 4-arg/5-arg overload; "
              + "the story-3.10 execution sub-stage path requires the 6-arg constructor with a "
              + "real ClarificationReadPort");
    }
  }

  /**
   * Legacy 7-arg overload — the generic execution-stage composition (latest-available union). Kept
   * byte-identical for the {@code RecoveryService} retry path and all pre-3.10 tests by delegating
   * to {@link #create(String, RunnerStage, String, int, ExecutionConstraints, DataClassification,
   * ActorContext, ExecutionSubStage, RepositoryContextSummary, String)} with a {@code null}
   * sub-stage + no repo context (Decision D1).
   */
  public ContextBundle create(
      String workflowRunPublicId,
      RunnerStage stage,
      String reservedRunnerExecutionId,
      int contextBundleVersion,
      ExecutionConstraints executionConstraints,
      DataClassification claimedClassification,
      ActorContext actor) {
    return create(
        workflowRunPublicId,
        stage,
        reservedRunnerExecutionId,
        contextBundleVersion,
        executionConstraints,
        claimedClassification,
        actor,
        null,
        null,
        null);
  }

  /**
   * Story 3.10 — the execution-stage composer, made <em>sub-stage-aware</em> and
   * <em>repo-aware</em> for the two implementation sub-stages (AC1/AC2/AC4/AC7).
   *
   * <ul>
   *   <li>{@code subStage == null} preserves today's exact generic composition (the
   *       latest-available union + parent-walk feedback) so the {@code RecoveryService} retry path
   *       and all pre-3.10 tests are byte-identical (Decision D1, OQ-4).
   *   <li>{@code subStage != null} sources {@code priorFeedbackReferences} from the approvals +
   *       incorporated-clarifications read ports (distinct {@code kind}s, Decision D3) instead of
   *       the parent-walk, and — for {@link ExecutionSubStage#PR_OUTPUT} — additionally carries the
   *       {@code approvedImplementationPlanReference} + the {@code implementationPlan.rejection}
   *       feedback.
   *   <li>{@code repositoryContextSummary != null} embeds the five 3a-2 repo fields via the reused
   *       {@code writeRepositoryContext} helper; {@code repositoryBranchRef} adds the expected
   *       deterministic branch name (story 3.9 AC2) — a ref, never a host path (Trap T5). Both are
   *       absent on no-repo dispatches, keeping those byte-identical to today (AC2).
   * </ul>
   *
   * <p>The single {@code redact(root, classification)} pass (AC4/Decision D6) covers every new text
   * leaf, including the embedded {@code repositoryTreeSummary} entries; no second redaction call is
   * added.
   */
  public ContextBundle create(
      String workflowRunPublicId,
      RunnerStage stage,
      String reservedRunnerExecutionId,
      int contextBundleVersion,
      ExecutionConstraints executionConstraints,
      DataClassification claimedClassification,
      ActorContext actor,
      ExecutionSubStage subStage,
      RepositoryContextSummary repositoryContextSummary,
      String repositoryBranchRef) {
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
    if (ticket == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.EXECUTION.value());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Ticket summary unavailable for execution-stage bundle composition",
          details);
    }

    Optional<ArtifactRecordSnapshot> approvedSpec =
        latestAvailable(workflowRunPublicId, ArtifactType.SPEC);
    List<ArtifactRecordSnapshot> artifactReferences =
        collectAvailableArtifacts(workflowRunPublicId);

    ObjectNode root;
    boolean approvedPlanPresent = false;
    int priorFeedbackCount;
    if (subStage == null) {
      // Legacy generic path — byte-identical to the pre-3.10 composition (Decision D1 / OQ-4).
      root =
          assemble(
              workflowRunPublicId,
              reservedRunnerExecutionId,
              ticket,
              approvedSpec,
              artifactReferences,
              executionConstraints,
              claimedClassification);
      priorFeedbackCount = root.get("priorFeedbackReferences").size();
    } else {
      // Source the approved-plan reference from the SAME approval lineage deriveExecutionSubStage
      // keys PR_OUTPUT on (resolve the exact approved artifact by its lineage id), NOT from
      // latestAvailable — an approved plan that has not (yet) reached `available` status must still
      // be referenced so a PR-output bundle never silently omits the plan it was selected on.
      // writeArtifactReference encodes a non-available artifact gracefully
      // (referenceAvailable=false
      // + unavailableReason), so the reference is always emitted when an approval exists.
      Optional<ArtifactRecordSnapshot> approvedPlan =
          subStage == ExecutionSubStage.PR_OUTPUT
              ? approvalReadPort
                  .findLatestApprovedForArtifactLineage(
                      workflowRunPublicId, ArtifactType.IMPLEMENTATION_PLAN.value())
                  .flatMap(approval -> artifactRecordPort.findByPublicId(approval.artifactId()))
              : Optional.empty();
      approvedPlanPresent = approvedPlan.isPresent();
      List<PriorFeedbackReference> feedback =
          collectExecutionFeedbackReferences(workflowRunPublicId, subStage);
      priorFeedbackCount = feedback.size();
      root =
          assembleExecutionStage(
              workflowRunPublicId,
              reservedRunnerExecutionId,
              ticket,
              subStage,
              approvedSpec,
              approvedPlan,
              feedback,
              artifactReferences,
              executionConstraints,
              claimedClassification,
              repositoryContextSummary,
              repositoryBranchRef);
    }

    RedactionResult redaction = redactionPolicyService.redact(root, claimedClassification.value());
    JsonNode redactedJson =
        synchronizeClassification(
            redaction.sanitizedJson() == null ? root : redaction.sanitizedJson(),
            redaction.effectiveClassification());
    byte[] redactedBytes = serialize(redactedJson);

    ValidationContext validationContext =
        ValidationContext.builder().maxPayloadBytes(CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES).build();
    ValidationResult result =
        contractValidator.validate(
            ValidationTarget.CONTEXT_BUNDLE, redactedBytes, validationContext);
    if (!result.valid()) {
      log.warn(
          "create context-bundle rejected workflowRunId={} runnerExecutionId={} subStage={} errorCount={} errors={}",
          workflowRunPublicId,
          reservedRunnerExecutionId,
          subStage,
          result.errors().size(),
          result.errors());
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
        "create context-bundle ok workflowRunId={} runnerExecutionId={} stage={} subStage={} version={} "
            + "classification={} repoContextPresent={} approvedPlanPresent={} priorFeedbackCount={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        stage.value(),
        subStage,
        contextBundleVersion,
        redaction.effectiveClassification().value(),
        repositoryContextSummary != null,
        approvedPlanPresent,
        priorFeedbackCount);
    return new ContextBundle(
        workflowRunPublicId,
        stage,
        reservedRunnerExecutionId,
        contextBundleVersion,
        redaction.effectiveClassification(),
        redactedBytes);
  }

  /**
   * Story 3.10 (Decision D4) — derive the {@link ExecutionSubStage} from run state for a dispatch
   * that does not pass one explicitly (no live caller does today — 3.11/3.12 deferred). Returns
   * {@link ExecutionSubStage#PR_OUTPUT} when an approved implementation-plan artifact already
   * exists for the run (the plan-approved → implementation flow), else {@link
   * ExecutionSubStage#IMPLEMENTATION_PLAN}. Reuses the already-injected {@link ApprovalReadPort}.
   */
  public ExecutionSubStage deriveExecutionSubStage(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    boolean planApproved =
        approvalReadPort
            .findLatestApprovedForArtifactLineage(
                workflowRunPublicId, ArtifactType.IMPLEMENTATION_PLAN.value())
            .isPresent();
    return planApproved ? ExecutionSubStage.PR_OUTPUT : ExecutionSubStage.IMPLEMENTATION_PLAN;
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
      ActorContext actor,
      RepositoryContextSummary repositoryContextSummary) {
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

    // Story 3e-2 (AC3) — feed the run's ACCEPTED clarifications into the spec rebuild. Only the
    // accepted rows (the explicit PM judgment, story 2.12) drive the rebuild; open/answered-but-not
    // -accepted clarifications are excluded.
    List<Clarification> acceptedClarifications =
        clarificationReadPort.listByWorkflowRunId(workflowRunPublicId).stream()
            .filter(clarification -> Clarification.STATUS_ACCEPTED.equals(clarification.status()))
            .toList();

    ObjectNode root =
        assembleForSpecInvestigation(
            workflowRunPublicId,
            reservedRunnerExecutionId,
            ticket,
            rejections,
            priorSpecVersions,
            acceptedClarifications,
            executionConstraints,
            claimedClassification,
            repositoryContextSummary);

    RedactionResult redaction;
    try {
      redaction =
          redactionPolicyService.redact(root, DataClassification.SHAREABLE_REDACTED.value());
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

    ValidationContext validationContext =
        ValidationContext.builder().maxPayloadBytes(CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES).build();
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
        "createForSpecInvestigation ok workflowRunId={} runnerExecutionId={} stage={} version={} classification={} priorRejectionCount={} priorSpecVersionCount={} acceptedClarificationCount={} repoContextPresent={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        RunnerStage.INVESTIGATION.value(),
        contextBundleVersion,
        DataClassification.SHAREABLE_REDACTED.value(),
        rejections.size(),
        priorSpecVersions.size(),
        acceptedClarifications.size(),
        repositoryContextSummary != null);
    return new ContextBundle(
        workflowRunPublicId,
        RunnerStage.INVESTIGATION,
        reservedRunnerExecutionId,
        contextBundleVersion,
        DataClassification.SHAREABLE_REDACTED,
        redactedBytes);
  }

  /**
   * Story 3d-2 (AC1/AC7, Task 4, DD-8) — composes a context bundle for the advisory <em>review</em>
   * use case ({@link RunnerStage#REVIEW}). The reviewer reviews the latest AVAILABLE
   * execution-stage output artifact (prOutput if present, else implementationPlan) of the run; that
   * artifact is carried as the SOLE {@code artifactReferences} entry (the runner reads its
   * already-redacted content from the mounted input dir, exactly as any runner reads a referenced
   * artifact), and the approved spec — when present — rides {@code approvedSpecificationReference}
   * as review context.
   *
   * <p>Review is READ-ONLY over an existing artifact: NO repository workspace is prepared (unlike
   * INVESTIGATION/EXECUTION), so no repo-context fields are emitted. The whole bundle passes the
   * same single {@code redact(root, SHAREABLE_REDACTED)} pass + {@code CONTEXT_BUNDLE} contract
   * validation as every other bundle (AC7 — the reviewed content the reviewer sees is redacted
   * before egress).
   *
   * <p>A run with no AVAILABLE execution-stage artifact is a degenerate dispatch (the reviewer
   * enqueue only fires on {@code WaitingForReview} entry, which an available plan/prOutput
   * triggered) — surfaced as a {@link DomainException} so the worker degrades the reviewer
   * execution gracefully (AC6) without failing the run.
   */
  public ContextBundle createForReview(
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
        "createForReview entry workflowRunId={} runnerExecutionId={} version={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        contextBundleVersion);

    ArtifactRecordSnapshot reviewedArtifact = resolveReviewedArtifact(workflowRunPublicId);

    TicketSummary ticket = ticketSummaryProvider.fetchByWorkflowRun(workflowRunPublicId);
    if (ticket == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.REVIEW.value());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Ticket summary unavailable for review bundle composition",
          details);
    }

    // Story 3e-3 (AC2) — the reviewed artifact's TYPE selects the bundle variant. A SPEC reviewed
    // artifact means the reviewer fired at the WaitingForSpecApproval gate (resolveReviewedArtifact
    // fell through to the spec): compose the spec-review bundle that ALSO inlines the run's OPEN
    // clarifications so the reviewer can weigh whether the spec leaves them unresolved. Every other
    // reviewed type (prOutput / implementationPlan) is the unchanged execution-phase review bundle.
    // bundleVariant (logged below) is the authoritative spec-vs-execution signal; the count is an
    // honest 0 for an execution review (no open-clarification block), never a -1 sentinel that
    // would
    // poison a log-derived metric summing openClarificationCount.
    boolean isSpecReview = reviewedArtifact.artifactType() == ArtifactType.SPEC;
    int openClarificationCount = 0;
    ObjectNode root;
    if (isSpecReview) {
      List<Clarification> openClarifications =
          clarificationReadPort.listByWorkflowRunId(workflowRunPublicId).stream()
              .filter(clarification -> Clarification.STATUS_OPEN.equals(clarification.status()))
              .toList();
      openClarificationCount = openClarifications.size();
      root =
          assembleForSpecReview(
              workflowRunPublicId,
              reservedRunnerExecutionId,
              ticket,
              reviewedArtifact,
              openClarifications,
              executionConstraints,
              DataClassification.SHAREABLE_REDACTED);
    } else {
      Optional<ArtifactRecordSnapshot> approvedSpec =
          latestAvailable(workflowRunPublicId, ArtifactType.SPEC);
      root =
          assembleForReview(
              workflowRunPublicId,
              reservedRunnerExecutionId,
              ticket,
              approvedSpec,
              reviewedArtifact,
              executionConstraints,
              DataClassification.SHAREABLE_REDACTED);
    }

    RedactionResult redaction;
    try {
      redaction =
          redactionPolicyService.redact(root, DataClassification.SHAREABLE_REDACTED.value());
    } catch (RuntimeException e) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.REVIEW.value());
      details.put("cause", e.getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Redaction failure during review bundle composition",
          details);
    }
    JsonNode redactedJson =
        synchronizeClassification(
            redaction.sanitizedJson() == null ? root : redaction.sanitizedJson(),
            DataClassification.SHAREABLE_REDACTED);
    byte[] redactedBytes = serialize(redactedJson);

    ValidationContext validationContext =
        ValidationContext.builder().maxPayloadBytes(CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES).build();
    ValidationResult result =
        contractValidator.validate(
            ValidationTarget.CONTEXT_BUNDLE, redactedBytes, validationContext);
    if (!result.valid()) {
      log.warn(
          "createForReview context-bundle rejected workflowRunId={} runnerExecutionId={} errorCount={}",
          workflowRunPublicId,
          reservedRunnerExecutionId,
          result.errors().size());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("workflowRunId", workflowRunPublicId);
      details.put("runnerExecutionId", reservedRunnerExecutionId);
      details.put("stage", RunnerStage.REVIEW.value());
      details.put("validationErrors", result.errors());
      throw new DomainException(
          DomainErrorCode.RUNNER_CONTRACT_VIOLATION,
          "Review context bundle failed contract validation",
          details);
    }
    log.info(
        "createForReview ok workflowRunId={} runnerExecutionId={} stage={} version={} classification={} reviewedArtifactId={} reviewedArtifactType={} bundleVariant={} openClarificationCount={}",
        workflowRunPublicId,
        reservedRunnerExecutionId,
        RunnerStage.REVIEW.value(),
        contextBundleVersion,
        DataClassification.SHAREABLE_REDACTED.value(),
        reviewedArtifact.publicId(),
        reviewedArtifact.artifactType().value(),
        isSpecReview ? "spec-review" : "execution-review",
        openClarificationCount);
    return new ContextBundle(
        workflowRunPublicId,
        RunnerStage.REVIEW,
        reservedRunnerExecutionId,
        contextBundleVersion,
        DataClassification.SHAREABLE_REDACTED,
        redactedBytes);
  }

  /**
   * Story 3d-2 / 3e-3 — the artifact the reviewer reviews, resolved by gate precedence so the SAME
   * derivation serves the execution gate and the spec gate (the reviewer fires at one gate at a
   * time):
   *
   * <ul>
   *   <li>{@code prOutput} ⊐ {@code implementationPlan} — the latest AVAILABLE execution-stage
   *       output (prOutput supersedes implementationPlan when both exist, mirroring how a re-entry
   *       into {@code WaitingForReview} after pr-output is the current head). This is the
   *       execution-phase reviewer (3d-2), unchanged.
   *   <li>else {@code spec} — story 3e-3: at the {@code WaitingForSpecApproval} gate only a spec
   *       artifact exists (investigation produced it; no plan/prOutput yet), so the precedence
   *       falls through to the spec and the spec-phase reviewer reviews it. At the execution gate a
   *       plan/prOutput is always present, so the spec is never selected there — the
   *       execution-phase behavior stays byte-identical.
   * </ul>
   *
   * <p>Used at compose (build the bundle) and enqueue (pin the FK) time, where the run is AT the
   * reviewer's gate so the precedence selects exactly what the reviewer sees. The harvest fallback
   * must NOT use this spec-inclusive form — see {@link #resolveExecutionReviewedArtifact}.
   */
  public ArtifactRecordSnapshot resolveReviewedArtifact(String workflowRunPublicId) {
    return resolveReviewedArtifact(workflowRunPublicId, true);
  }

  /**
   * Story 3e-3 (code-review D1) — the harvest pin-absent fallback re-derives WITHOUT the spec tier.
   *
   * <p>The enqueue-time pin is the authoritative record of which artifact the reviewer actually
   * saw. Once it is absent (a pre-V22 reviewer, or a rare enqueue-time pin-write failure) we can no
   * longer tell a spec-phase reviewer from an execution reviewer, and the run may have advanced
   * past {@code WaitingForSpecApproval} so that a plan/prOutput now exists. Including the spec tier
   * in that fallback would let an advanced-run re-derivation silently return the plan for what was
   * a spec review and mis-attribute the producer (EXECUTION instead of INVESTIGATION, see {@code
   * ReviewResultHarvester#resolveProducerIdentity}). Excluding it restores the pre-3e-3 behavior:
   * an execution reviewer re-derives its plan/prOutput unchanged, and a spec-phase reviewer whose
   * pin was lost degrades cleanly (the gate stays human-reviewable, AC7) instead of guessing.
   */
  public ArtifactRecordSnapshot resolveExecutionReviewedArtifact(String workflowRunPublicId) {
    return resolveReviewedArtifact(workflowRunPublicId, false);
  }

  private ArtifactRecordSnapshot resolveReviewedArtifact(
      String workflowRunPublicId, boolean includeSpecFallback) {
    return latestAvailable(workflowRunPublicId, ArtifactType.PR_OUTPUT)
        .or(() -> latestAvailable(workflowRunPublicId, ArtifactType.IMPLEMENTATION_PLAN))
        .or(
            () ->
                includeSpecFallback
                    ? latestAvailable(workflowRunPublicId, ArtifactType.SPEC)
                    : Optional.empty())
        .orElseThrow(
            () -> {
              Map<String, Object> details = new LinkedHashMap<>();
              details.put("workflowRunId", workflowRunPublicId);
              details.put("stage", RunnerStage.REVIEW.value());
              return new DomainException(
                  DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                  "No available artifact to review for run " + workflowRunPublicId,
                  details);
            });
  }

  private ObjectNode assembleForReview(
      String workflowRunPublicId,
      String runnerExecutionId,
      TicketSummary ticket,
      Optional<ArtifactRecordSnapshot> approvedSpec,
      ArtifactRecordSnapshot reviewedArtifact,
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

    // Review carries no prior-feedback chain — the verdict is a fresh second opinion.
    root.putArray("priorFeedbackReferences");

    // The reviewed artifact is the SOLE reference; the runner reads its (already-redacted) content
    // from the mounted input dir and emits its verdict over it.
    ArrayNode artifactRefsNode = root.putArray("artifactReferences");
    writeArtifactReference(artifactRefsNode.addObject(), reviewedArtifact);

    ObjectNode constraintsNode = root.putObject("executionConstraints");
    constraintsNode.put("timeoutSeconds", executionConstraints.timeoutSeconds());
    constraintsNode.put("allowRawOutput", executionConstraints.allowRawOutput());

    root.put("classification", classification.value());
    return root;
  }

  /**
   * Story 3e-3 (AC2/AC8) — the spec-phase review bundle. Mirrors {@link #assembleForReview} (the
   * reviewed SPEC artifact is the SOLE {@code artifactReferences} entry; the runner reads its
   * already-redacted content from the mounted input dir) but ALSO inlines the run's {@code open}
   * clarifications so the reviewer can assess whether the spec leaves the open questions
   * unresolved.
   *
   * <p>Open clarifications carry NO answer yet (status {@code open} ⇒ answerText null), so the
   * inline rows are {@code {clarificationId, questionId, questionText}} only — the exact OPEN twin
   * of 3e-2's {@code acceptedClarifications} block ({@link #assembleForSpecInvestigation}), policed
   * by the SAME single {@code redact(root, SHAREABLE_REDACTED)} pass downstream that covers every
   * text leaf. The {@code openClarifications} array is emitted ONLY when ≥1 open clarification
   * exists, so a no-open-clarification spec review is byte-identical to the execution-review
   * baseline shape (the field is optional + not in the schema {@code required}). The reviewed spec
   * is NOT yet approved (we are AT the approval gate), so {@code approvedSpecificationReference} is
   * null — the spec rides {@code artifactReferences} as the reviewed artifact.
   */
  private ObjectNode assembleForSpecReview(
      String workflowRunPublicId,
      String runnerExecutionId,
      TicketSummary ticket,
      ArtifactRecordSnapshot reviewedSpecArtifact,
      List<Clarification> openClarifications,
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

    // The spec under review is not yet approved (this IS the approval gate); it rides
    // artifactReferences below as the reviewed artifact, not as an approved-spec reference.
    root.putNull("approvedSpecificationReference");

    // Story 3e-3 — one {kind: 'clarification.open'} feedback ref per open clarification, the by-id
    // audit half (mirrors the 3e-2 clarification.answered ref). The question CONTENT rides the
    // inline openClarifications array below (the runner reads only this bundle).
    ArrayNode priorFeedbackRefsNode = root.putArray("priorFeedbackReferences");
    for (Clarification open : openClarifications) {
      ObjectNode feedbackNode = priorFeedbackRefsNode.addObject();
      feedbackNode.put("referenceId", open.publicId());
      feedbackNode.put("kind", "clarification.open");
    }

    // The reviewed spec is the SOLE artifact reference; the runner reads its (already-redacted)
    // content from the mounted input dir and emits its verdict over it.
    ArrayNode artifactRefsNode = root.putArray("artifactReferences");
    writeArtifactReference(artifactRefsNode.addObject(), reviewedSpecArtifact);

    ObjectNode constraintsNode = root.putObject("executionConstraints");
    constraintsNode.put("timeoutSeconds", executionConstraints.timeoutSeconds());
    constraintsNode.put("allowRawOutput", executionConstraints.allowRawOutput());

    // Story 3e-3 (AC2) — inline the OPEN clarifications' question text so the spec reviewer can
    // weigh
    // whether the spec leaves them unresolved. Open rows have no answerText (status open ⇒ answer
    // fields null), so only {clarificationId, questionId, questionText} is emitted — the OPEN twin
    // of
    // the 3e-2 acceptedClarifications block. Policed by the SAME single redact(root,
    // SHAREABLE_REDACTED) pass below as every other text leaf. Emitted ONLY when >=1 open
    // clarification exists so a no-clarification spec review stays additive-byte-identical (the
    // field
    // is optional + not schema-required; the 256KB cap stays comfortable — questionText is
    // bounded).
    if (!openClarifications.isEmpty()) {
      ArrayNode openNode = root.putArray("openClarifications");
      for (Clarification open : openClarifications) {
        ObjectNode clarificationNode = openNode.addObject();
        clarificationNode.put("clarificationId", open.publicId());
        clarificationNode.put("questionId", open.questionId());
        clarificationNode.put("questionText", open.questionText());
      }
    }

    root.put("classification", classification.value());
    return root;
  }

  private ObjectNode assembleForSpecInvestigation(
      String workflowRunPublicId,
      String runnerExecutionId,
      TicketSummary ticket,
      List<ApprovalSnapshot> priorRejections,
      List<ArtifactRecordSnapshot> priorSpecVersions,
      List<Clarification> acceptedClarifications,
      ExecutionConstraints executionConstraints,
      DataClassification classification,
      RepositoryContextSummary repositoryContextSummary) {
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
    // Story 3e-2 (AC3) — one clarification.answered feedback ref per ACCEPTED clarification, so the
    // spec rebuild's feedback inventory records that each answer drove this regeneration (alongside
    // the spec.rejection rows). The answer CONTENT rides the inline acceptedClarifications array
    // below (the runner reads only this bundle); this ref is the by-id audit half.
    for (Clarification accepted : acceptedClarifications) {
      ObjectNode feedbackNode = priorFeedbackRefsNode.addObject();
      feedbackNode.put("referenceId", accepted.publicId());
      feedbackNode.put("kind", "clarification.answered");
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

    // Story 3e-2 (AC3) — inline the ACCEPTED clarifications' question+answer so the spec rebuild
    // runner can actually incorporate them. The runner reads ONLY this bundle, so (unlike every
    // other feedback channel, which is reference-by-id) the answer content must be carried here.
    // This is policed by the SAME single redact(root, SHAREABLE_REDACTED) pass below as every other
    // text leaf, so the reviewer-authored answer text is redacted exactly like artifact content
    // (the bundle 256KB cap stays comfortable — answerText is @Size(max=8192) per clarification).
    // Emitted ONLY when >=1 accepted clarification exists, so a no-clarification rebuild stays
    // byte-identical to the story-2.8 baseline (the field is optional + not in the schema
    // required).
    if (!acceptedClarifications.isEmpty()) {
      ArrayNode acceptedNode = root.putArray("acceptedClarifications");
      for (Clarification accepted : acceptedClarifications) {
        // (review P5) Defensive guard: an accepted clarification with a blank answerText would emit
        // a JSON null/empty leaf and fail the whole bundle against context-bundle.v1.schema.json
        // (answerText: type string, minLength 1), aborting the spec rebuild. This is defended
        // upstream by the @NotBlank answerText at answer time, so a blank here is a contract
        // violation — skip the row's inline content (its by-id clarification.answered feedback ref
        // is still recorded above) and warn rather than break the entire dispatch.
        String answerText = accepted.answerText();
        if (answerText == null || answerText.isBlank()) {
          log.warn(
              "accepted clarification has blank answerText — omitting from spec-rebuild bundle "
                  + "clarificationId={} questionId={}",
              accepted.publicId(),
              accepted.questionId());
          continue;
        }
        ObjectNode clarificationNode = acceptedNode.addObject();
        clarificationNode.put("clarificationId", accepted.publicId());
        clarificationNode.put("questionId", accepted.questionId());
        clarificationNode.put("questionText", accepted.questionText());
        clarificationNode.put("answerText", answerText);
      }
    }

    // Story 3a-2 (AC1) — additive optional repo-context fields. Emitted ONLY when a workspace was
    // prepared for this run; when null, NOTHING is written so the bundle is byte-identical to the
    // story-2.8 baseline. The single redact(root, SHAREABLE_REDACTED) pass downstream (:292) covers
    // every text leaf written here, including each tree-summary entry (AC3/Decision D6).
    if (repositoryContextSummary != null) {
      writeRepositoryContext(root, repositoryContextSummary);
    }

    root.put("classification", classification.value());
    return root;
  }

  /**
   * Writes the five additive spec-stage repo-context fields (AC1). Only the small {@code
   * repositoryTreeSummary} is embedded; README + manifests are reference-by-mount-path (Decision
   * D4). Every value here is the container mount path or a mount-relative path — never a host
   * absolute path (Trap T7).
   */
  private void writeRepositoryContext(ObjectNode root, RepositoryContextSummary summary) {
    root.put("repositoryWorkspaceRef", summary.mountPath());

    ArrayNode treeNode = root.putArray("repositoryTreeSummary");
    for (RepoTreeEntry entry : summary.treeSummary()) {
      ObjectNode entryNode = treeNode.addObject();
      entryNode.put("path", entry.path());
      entryNode.put("type", entry.type().value());
    }

    if (summary.readmeRef() != null) {
      root.put("repositoryReadmeRef", summary.readmeRef());
    } else {
      root.putNull("repositoryReadmeRef");
    }

    ArrayNode manifestNode = root.putArray("packageManifestRefs");
    for (RepoManifestRef manifest : summary.manifestRefs()) {
      ObjectNode manifestEntry = manifestNode.addObject();
      manifestEntry.put("path", manifest.path());
      manifestEntry.put("kind", manifest.kind());
    }

    root.put("ticketRepositoryMappingVersion", summary.mappingVersion());
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

  /**
   * Story 3.10 (AC1/AC2/AC7) — assemble the execution-stage bundle for a known sub-stage. Shares
   * the envelope + {@code writeArtifactReference} + {@code writeRepositoryContext} helpers with
   * {@link #assemble} / {@link #assembleForSpecInvestigation}; only the feedback source (approvals
   * + incorporated clarifications, not the parent-walk) and the two pr-output-specific slots
   * diverge.
   */
  private ObjectNode assembleExecutionStage(
      String workflowRunPublicId,
      String runnerExecutionId,
      TicketSummary ticket,
      ExecutionSubStage subStage,
      Optional<ArtifactRecordSnapshot> approvedSpec,
      Optional<ArtifactRecordSnapshot> approvedPlan,
      List<PriorFeedbackReference> feedback,
      List<ArtifactRecordSnapshot> artifactReferences,
      ExecutionConstraints executionConstraints,
      DataClassification classification,
      RepositoryContextSummary repositoryContextSummary,
      String repositoryBranchRef) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("schemaVersion", CONTEXT_BUNDLE_SCHEMA_VERSION);
    root.put("workflowRunId", workflowRunPublicId);
    root.put("runnerExecutionId", runnerExecutionId);

    ObjectNode ticketNode = root.putObject("ticketSummary");
    ticketNode.put("ticketRef", ticket.ticketRef());
    ticketNode.put("title", ticket.title());
    ticketNode.put("summary", ticket.summary());

    // The approved spec is the input contract for BOTH implementation sub-stages (AC1/AC2).
    if (approvedSpec.isPresent()) {
      writeArtifactReference(root.putObject("approvedSpecificationReference"), approvedSpec.get());
    } else {
      root.putNull("approvedSpecificationReference");
    }

    ArrayNode priorFeedbackRefsNode = root.putArray("priorFeedbackReferences");
    for (PriorFeedbackReference reference : feedback) {
      ObjectNode feedbackNode = priorFeedbackRefsNode.addObject();
      feedbackNode.put("referenceId", reference.referenceId());
      feedbackNode.put("kind", reference.kind());
    }

    ArrayNode artifactRefsNode = root.putArray("artifactReferences");
    for (ArtifactRecordSnapshot ref : artifactReferences) {
      writeArtifactReference(artifactRefsNode.addObject(), ref);
    }

    ObjectNode constraintsNode = root.putObject("executionConstraints");
    constraintsNode.put("timeoutSeconds", executionConstraints.timeoutSeconds());
    constraintsNode.put("allowRawOutput", executionConstraints.allowRawOutput());

    root.put("classification", classification.value());

    // PR-output sub-stage carries the approved implementation-plan reference (AC2). The plan
    // sub-stage omits the field entirely (AC1 — no approved-plan ref).
    if (subStage == ExecutionSubStage.PR_OUTPUT) {
      if (approvedPlan.isPresent()) {
        writeArtifactReference(
            root.putObject("approvedImplementationPlanReference"), approvedPlan.get());
      } else {
        root.putNull("approvedImplementationPlanReference");
      }
    }

    // Repo context is workspace-gated, NOT sub-stage-gated (OQ-3): embedded for either sub-stage
    // whenever a workspace was prepared; entirely absent (with repositoryBranchRef) on no-repo
    // dispatches so those stay byte-identical to today (AC2). The single downstream redact pass
    // covers every text leaf written here, including each tree-summary entry (AC4/Decision D6).
    if (repositoryContextSummary != null) {
      writeRepositoryContext(root, repositoryContextSummary);
      if (repositoryBranchRef != null && !repositoryBranchRef.isBlank()) {
        // A git ref (story 3.9 AC2), never a host absolute path (Trap T5).
        root.put("repositoryBranchRef", repositoryBranchRef);
      } else {
        // A prepared workspace with no deterministic branch ref violates story 3.9 AC2; surface it
        // rather than silently omitting the field (a runner has no branch anchor for push).
        log.warn(
            "Execution context bundle has a prepared repository workspace but no deterministic "
                + "branch ref (story 3.9 AC2); repositoryBranchRef omitted. "
                + "workflowRunId={} subStage={}",
            workflowRunPublicId,
            subStage);
      }
    }

    return root;
  }

  /**
   * Story 3.10 (AC1/AC2, Decision D3) — the execution-stage {@code priorFeedbackReferences} source:
   * prior spec PM decisions (approvals + rejections), then — for {@link
   * ExecutionSubStage#PR_OUTPUT} — prior implementation-plan rejections (technical feedback), then
   * incorporated clarifications. Each contributes a reference-by-id entry with a distinct {@code
   * kind}; no answer/feedback body text is ever embedded (the runner reads detail from the
   * referenced rows / inspection path).
   */
  private List<PriorFeedbackReference> collectExecutionFeedbackReferences(
      String workflowRunPublicId, ExecutionSubStage subStage) {
    List<PriorFeedbackReference> references = new ArrayList<>();
    for (ApprovalSnapshot decision :
        approvalReadPort.listByWorkflowRunAndArtifactType(
            workflowRunPublicId, ArtifactType.SPEC.value())) {
      references.add(
          new PriorFeedbackReference(
              decision.publicId(), decision.isApproved() ? "spec.approval" : "spec.rejection"));
    }
    // Story 3.21 (OQ-2) — thread prior implementation-plan rejections into BOTH execution
    // sub-stages: the PR_OUTPUT bundle (so the pr-output runner sees why a prior plan was rejected,
    // story 3.10) AND the regenerated IMPLEMENTATION_PLAN bundle (so a re-dispatched plan after a
    // plan rejection sees the prior plan's technical feedback — "feedback flows back into the
    // rebuild loop", FR17). Both sub-stages reference the same implementation-plan rejection rows.
    if (subStage == ExecutionSubStage.PR_OUTPUT
        || subStage == ExecutionSubStage.IMPLEMENTATION_PLAN) {
      for (ApprovalSnapshot rejection :
          approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(
              workflowRunPublicId, ArtifactType.IMPLEMENTATION_PLAN.value())) {
        references.add(
            new PriorFeedbackReference(rejection.publicId(), "implementationPlan.rejection"));
      }
    }
    for (Clarification clarification :
        clarificationReadPort.listByWorkflowRunId(workflowRunPublicId)) {
      if (clarification.isIncorporated()) {
        references.add(
            new PriorFeedbackReference(clarification.publicId(), "clarification.incorporated"));
      }
    }
    return List.copyOf(references);
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
    List<ArtifactRecordSnapshot> collected = new ArrayList<>();
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
