package org.dradgo.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.SpecificationArtifact;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.FailureDescription;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.runner.ProviderUsageSnapshotView;
import org.dradgo.application.runner.ProviderUsageStatusService;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerQueueCounts;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.AllowedAction;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that materializes the CLI/REST inspection views for a workflow run (story
 * 1-15 Task 1, extended in story 1.18 Task 4). Read-only: never mutates state, never logs raw event
 * details.
 *
 * <p>Story 1.18 wires {@link RecoveryService#describeFailure(String)} into the {@code status}
 * surface so the previously-stubbed {@code nextSafeAction} now carries the real {@code retry /
 * await_manual_reconciliation / await_outcome / view_only} value, plus five failure-diagnostic
 * fields populated only when {@code currentState == Failed}.
 */
@Service
public class WorkflowInspectionService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowInspectionService.class);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final ArtifactRecordPort artifactRecordPort;
  private final ArtifactPayloadStore artifactPayloadStore;
  private final ApprovalReadPort approvalReadPort;
  private final IntegrationLinkService integrationLinkService;
  private final RedactionPolicyService redactionPolicyService;
  private final RecoveryService recoveryService;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RunnerScratchStore runnerScratchStore;
  private final ClarificationReadPort clarificationReadPort;
  // Story 3.22 (AC8) — the authoritative source of takeover attribution (actor/role/timestamp) for
  // a TakenOver run's getRunSummary, read from the takeover recovery_actions row (not an event).
  private final RecoveryActionRecordPort recoveryActionRecordPort;
  // Story 3.19 — config-only inputs for the runner-queue inspection view (Reconciliation 2/6/7):
  // poolSize from RunnerWorkerPoolProperties.size() (always present even when the pool is disabled
  // in the test profile); the stale/lease window from RunnerProperties.staleThresholdFor(stage).
  private final RunnerProperties runnerProperties;
  private final RunnerWorkerPoolProperties runnerWorkerPoolProperties;
  // Story 3b-5 — parses the structured prOutput payload (branch/commitSha/diff) for the
  // artifact-read projection. A plain instance field, not a constructor dependency, so the ~7 `new
  // WorkflowInspectionService(...)` test sites are untouched ([[runnerproperties-record-fanout]]).
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  public WorkflowInspectionService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactPayloadStore artifactPayloadStore,
      ApprovalReadPort approvalReadPort,
      IntegrationLinkService integrationLinkService,
      RedactionPolicyService redactionPolicyService,
      RecoveryService recoveryService,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RunnerScratchStore runnerScratchStore,
      ClarificationReadPort clarificationReadPort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      RunnerProperties runnerProperties,
      RunnerWorkerPoolProperties runnerWorkerPoolProperties) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
    this.artifactPayloadStore =
        Objects.requireNonNull(artifactPayloadStore, "artifactPayloadStore");
    this.approvalReadPort = Objects.requireNonNull(approvalReadPort, "approvalReadPort");
    this.integrationLinkService =
        Objects.requireNonNull(integrationLinkService, "integrationLinkService");
    this.redactionPolicyService =
        Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
    this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.runnerScratchStore = Objects.requireNonNull(runnerScratchStore, "runnerScratchStore");
    this.clarificationReadPort =
        Objects.requireNonNull(clarificationReadPort, "clarificationReadPort");
    this.recoveryActionRecordPort =
        Objects.requireNonNull(recoveryActionRecordPort, "recoveryActionRecordPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.runnerWorkerPoolProperties =
        Objects.requireNonNull(runnerWorkerPoolProperties, "runnerWorkerPoolProperties");
  }

  // Story 3d-2 (AC3, Task 7) — the advisory-verdict read port, backing getReviewerVerdict. Optional
  // SETTER injection (mirroring RunnerBroker's resolver) so none of the ~10 `new
  // WorkflowInspectionService(...)` test sites change; production Spring wires the
  // application.review @Component. Absent in lean unit ctors that never call getReviewerVerdict.
  private org.dradgo.application.review.spi.StepReviewReadPort stepReviewReadPort;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setStepReviewReadPort(
      org.dradgo.application.review.spi.StepReviewReadPort stepReviewReadPort) {
    this.stepReviewReadPort = stepReviewReadPort;
  }

  // Story 3d-7 (FR69, AC5) — the provider-usage read service, backing getProviderUsageStatus.
  // Optional SETTER injection (mirroring stepReviewReadPort) so the lean `new
  // WorkflowInspectionService(...)` test ctors stay untouched; production Spring wires the
  // application.runner @Service. Routing the read through this application-service surface keeps
  // the
  // adapters.rest controller off the forbidden application.runner package
  // (REST_CONTROLLERS_STAY_THIN
  // [[story-3d-4-manual-artifact-submission-implementation]]).
  private ProviderUsageStatusService providerUsageStatusService;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProviderUsageStatusService(ProviderUsageStatusService providerUsageStatusService) {
    this.providerUsageStatusService = providerUsageStatusService;
  }

  /**
   * Story 3d-7 (FR69, AC5) — latest per-credential provider usage/limit status for a run, projected
   * into an {@code application.workflow} view so the REST/CLI transports never reach into {@code
   * application.runner}. Tolerant of absence: returns {@link Optional#empty()} when no snapshot has
   * been captured (legacy/default runner) or when the read service is not wired (lean profiles).
   */
  @Transactional(readOnly = true)
  public Optional<ProviderUsageStatusView> getProviderUsageStatus(String workflowRunId) {
    if (providerUsageStatusService == null) {
      return Optional.empty();
    }
    return providerUsageStatusService
        .getLatestForRun(workflowRunId)
        .map(ProviderUsageStatusView::from);
  }

  /**
   * Story 3d-7 (FR69, AC5 / Trap T5) — true when {@code actionWireValue} is in the run×role
   * allowed-action set. Lets transports gate on the wire string WITHOUT referencing the {@link
   * AllowedAction} enum directly (story 2.14 AC9 boundary — only this service +
   * AllowedActionsResponse may touch the enum). Throws the standard RUN_NOT_FOUND /
   * INVALID_ID_PREFIX / UNKNOWN_ACTOR_ROLE Problem Details first, exactly as {@link
   * #getAllowedActions}.
   */
  @Transactional(readOnly = true)
  public boolean isActionAllowed(String workflowRunId, String actorRole, String actionWireValue) {
    return getAllowedActions(workflowRunId, actorRole).actions().stream()
        .map(AllowedAction::value)
        .anyMatch(value -> value.equals(actionWireValue));
  }

  /**
   * Story 3d-2 (AC3/AC4/AC6, Task 7) — the advisory reviewer verdict for a run, derived server-side
   * so the frontend panel stays presentational. State machine (no governed action — the panel is
   * advisory-only, AC8):
   *
   * <ul>
   *   <li>a non-archived {@code step_reviews} row exists ⇒ {@code available} (+ outcome, redacted
   *       rationale, model-identity pair, self-review flag);
   *   <li>else the latest {@link RunnerStage#REVIEW} execution is queued/pending/running ⇒ {@code
   *       pending} (the verdict is on its way);
   *   <li>else that execution is terminal-failed ⇒ {@code unavailable} (+ the failure reason) — a
   *       failed second opinion that never stranded the run (AC6);
   *   <li>else there is NO reviewer execution at all ⇒ {@code unavailable} with reason {@code
   *       no_reviewer_configured} so the frontend renders NOTHING (AC5 — panel absent for
   *       no-binding projects).
   * </ul>
   */
  @Transactional(readOnly = true)
  public ReviewerVerdictView getReviewerVerdict(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    workflowRunReadPort
        .findByPublicId(workflowRunPublicId)
        .orElseThrow(() -> runNotFound(workflowRunPublicId));

    Optional<org.dradgo.application.review.StepReviewSnapshot> verdict =
        stepReviewReadPort == null
            ? Optional.empty()
            : stepReviewReadPort.findLatestForRun(workflowRunPublicId);

    // The latest verdict and the latest reviewer execution are read independently. Re-review
    // reconciliation (code-review 2026-06-22): a prior verdict is stale whenever the LATEST
    // reviewer
    // execution is not the one that produced it — surface the latest execution's state instead so
    // the panel never reports a settled verdict beside a superseded re-review. Two cases:
    //   (1) the latest execution is still in flight (QUEUED/PENDING/RUNNING) — `pending`;
    //   (2) the latest execution terminally FAILED over a newer artifact (3rd-round code-review) —
    //       the `available` branch below requires the verdict to belong to the latest execution, so
    //       a verdict from an OLDER execution falls through to the switch and surfaces the latest
    //       execution's `unavailable` reason rather than the stale verdict.
    // A verdict is written atomically with its reviewer execution's COMPLETED transition, so a
    // verdict can only exist for an already-terminal execution; an in-flight or newer LATEST
    // execution therefore always post-dates the verdict.
    Optional<org.dradgo.application.runner.spi.RunnerExecutionSnapshot> reviewerExec =
        runnerExecutionRecordPort.findLatestByWorkflowRunPublicIdAndStage(
            workflowRunPublicId, org.dradgo.domain.registry.RunnerStage.REVIEW);
    boolean latestReviewerInFlight =
        reviewerExec
            .map(
                r ->
                    switch (r.status()) {
                      case QUEUED, PENDING, RUNNING -> true;
                      default -> false;
                    })
            .orElse(false);
    // The verdict is current only when it was produced by the latest reviewer execution. When no
    // reviewer execution is resolvable (anomalous — a verdict implies an execution) we keep showing
    // the verdict rather than hiding it.
    boolean verdictIsForLatestExec =
        verdict.isPresent()
            && (reviewerExec.isEmpty()
                || verdict.get().runnerExecutionId().equals(reviewerExec.get().publicId()));

    if (verdict.isPresent() && verdictIsForLatestExec && !latestReviewerInFlight) {
      org.dradgo.application.review.StepReviewSnapshot v = verdict.get();
      log.info(
          "getReviewerVerdict available workflowRunId={} reviewId={} outcome={} selfReview={}",
          workflowRunPublicId,
          v.publicId(),
          v.outcome().value(),
          v.selfReview());
      return new ReviewerVerdictView(
          "available",
          v.outcome().value(),
          v.rationale(),
          v.reviewerModelIdentity(),
          v.producerModelIdentity(),
          v.selfReview(),
          null,
          v.createdAt());
    }

    if (reviewerExec.isEmpty()) {
      log.debug(
          "getReviewerVerdict no reviewer activity workflowRunId={} (panel absent)",
          workflowRunPublicId);
      return new ReviewerVerdictView(
          "unavailable", null, null, null, null, false, "no_reviewer_configured", null);
    }

    org.dradgo.application.runner.spi.RunnerExecutionSnapshot rex = reviewerExec.get();
    switch (rex.status()) {
      case QUEUED, PENDING, RUNNING:
        log.info(
            "getReviewerVerdict pending workflowRunId={} reviewerExec={} status={}",
            workflowRunPublicId,
            rex.publicId(),
            rex.status().value());
        return new ReviewerVerdictView("pending", null, null, null, null, false, null, null);
      default:
        // FAILED / TIMED_OUT / ORPHANED / CANCELLED_FOR_TAKEOVER — a finished reviewer execution
        // with no verdict row = the "review unavailable" state (AC6). A COMPLETED execution with no
        // verdict row is NOT a normal degrade: the success path always persists the verdict BEFORE
        // marking the execution completed, so this is an internal inconsistency — surface it loudly
        // (distinct reason + WARN) rather than masking it as a generic "unavailable".
        boolean completedWithoutVerdict =
            rex.status() == org.dradgo.domain.registry.RunnerExecutionStatus.COMPLETED;
        String reason =
            completedWithoutVerdict
                ? "reviewer_completed_without_verdict"
                : (rex.failureCategory() != null
                    ? rex.failureCategory().value()
                    : "reviewer_unavailable");
        if (completedWithoutVerdict) {
          log.warn(
              "getReviewerVerdict COMPLETED reviewer execution has no verdict row (internal"
                  + " inconsistency) workflowRunId={} reviewerExec={}",
              workflowRunPublicId,
              rex.publicId());
        } else {
          log.info(
              "getReviewerVerdict unavailable workflowRunId={} reviewerExec={} status={} reason={}",
              workflowRunPublicId,
              rex.publicId(),
              rex.status().value(),
              reason);
        }
        return new ReviewerVerdictView("unavailable", null, null, null, null, false, reason, null);
    }
  }

  /**
   * Story 3d-2 (Task 7) — server-derived advisory-verdict projection for the {@code GET
   * …/reviewer-verdict} read leg. {@code state} ∈ {@code pending|available|unavailable}; the
   * verdict fields are non-null only when {@code state == available}; {@code unavailableReason} is
   * non-null only when {@code state == unavailable}.
   */
  public record ReviewerVerdictView(
      String state,
      String outcome,
      String rationale,
      String reviewerModelIdentity,
      String producerModelIdentity,
      boolean selfReview,
      String unavailableReason,
      OffsetDateTime createdAt) {}

  /**
   * Story 3d-7 (FR69, AC5) — {@code application.workflow} projection of the latest provider
   * usage/limit snapshot, so the REST/CLI transports consume an application-service view rather
   * than the {@code application.runner} read type. NON-SECRET by construction: window numbers,
   * timestamps, and the non-secret account label only. {@code signalState == not_exposed} ⇒ both
   * windows are empty and the surface degrades to the documented "not exposed by provider"
   * indicator.
   */
  public record ProviderUsageStatusView(
      String signalState,
      String accountReference,
      UsageWindowView fiveHour,
      UsageWindowView weekly,
      OffsetDateTime asOf,
      OffsetDateTime capturedAt) {

    static ProviderUsageStatusView from(ProviderUsageSnapshotView view) {
      return new ProviderUsageStatusView(
          view.signalState(),
          view.accountReference(),
          UsageWindowView.from(view.fiveHour()),
          UsageWindowView.from(view.weekly()),
          view.asOf(),
          view.capturedAt());
    }

    /** A single provider window (5h or weekly). All fields nullable. */
    public record UsageWindowView(
        Double usedFraction, Integer used, Integer limit, OffsetDateTime resetsAt) {

      static UsageWindowView from(ProviderUsageSnapshotView.UsageWindow window) {
        return window == null
            ? null
            : new UsageWindowView(
                window.usedFraction(), window.used(), window.limit(), window.resetsAt());
      }

      /** True when no field carries a value (the not-exposed / empty window). */
      public boolean isEmpty() {
        return usedFraction == null && used == null && limit == null && resetsAt == null;
      }
    }
  }

  /**
   * Story 2.11 AC9: status-grouped clarifications for the supplied workflow run. Backs the UI
   * Clarification Region (story 2.18) via TanStack Query (story 2.6). Archived rows are filtered
   * out at the read port (V8 schema parity with story 1.3 retention).
   */
  @Transactional(readOnly = true)
  public List<ClarificationView> getClarifications(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info("getClarifications entry workflowRunId={}", workflowRunPublicId);
      List<Clarification> rows = clarificationReadPort.listByWorkflowRunId(workflowRunPublicId);
      List<ClarificationView> views = new ArrayList<>(rows.size());
      for (Clarification row : rows) {
        views.add(toClarificationView(row));
      }
      log.info(
          "getClarifications success workflowRunId={} count={}", workflowRunPublicId, views.size());
      return List.copyOf(views);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /**
   * Story 2.11 AC9: status-grouped clarifications scoped to a single artifact. Same ordering
   * contract as {@link #getClarifications(String)}.
   *
   * <p>P16 — caller MUST supply the {@code workflowRunPublicId} they expect the artifact's
   * clarifications to belong to. Rows from sibling runs are filtered out (Trap T11 cross-run /
   * cross-tenant leak guard). Returning an empty list is the safe default when the artifact is
   * unrelated to the caller's run.
   */
  @Transactional(readOnly = true)
  public List<ClarificationView> getClarificationsForArtifact(
      String workflowRunPublicId, String artifactPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(artifactPublicId, PublicIdPrefixes.ARTIFACT);
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    String priorArtifactId = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifactPublicId);
    try {
      log.info(
          "getClarificationsForArtifact entry workflowRunId={} artifactId={}",
          workflowRunPublicId,
          artifactPublicId);
      List<Clarification> rows = clarificationReadPort.listByArtifactId(artifactPublicId);
      List<ClarificationView> views = new ArrayList<>(rows.size());
      int filteredOut = 0;
      for (Clarification row : rows) {
        if (workflowRunPublicId.equals(row.workflowRunId())) {
          views.add(toClarificationView(row));
        } else {
          filteredOut++;
        }
      }
      if (filteredOut > 0) {
        log.warn(
            "getClarificationsForArtifact cross-run-filtered workflowRunId={} artifactId={} filteredCount={}",
            workflowRunPublicId,
            artifactPublicId,
            filteredOut);
      }
      log.info(
          "getClarificationsForArtifact success workflowRunId={} artifactId={} count={}",
          workflowRunPublicId,
          artifactPublicId,
          views.size());
      return List.copyOf(views);
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactId);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /**
   * Story 2.12 AC6 / AC7: V9-rich lifecycle status for a single clarification. Cross-run guard
   * (Trap T11) raises {@code CLARIFICATION_NOT_FOUND} when the row belongs to a sibling run. UI
   * Clarification Region (story 2.18) consumes this for the per-question lifecycle indicator.
   */
  @Transactional(readOnly = true)
  public ClarificationStatusView getClarificationStatus(
      String workflowRunPublicId, String clarificationPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(clarificationPublicId, PublicIdPrefixes.CLARIFICATION);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "getClarificationStatus entry workflowRunId={} clarificationId={}",
          workflowRunPublicId,
          clarificationPublicId);
      ClarificationLifecycleSnapshot snapshot =
          clarificationReadPort
              .findLifecycleSnapshotByPublicId(clarificationPublicId)
              .orElseThrow(
                  () ->
                      clarificationNotFound(workflowRunPublicId, clarificationPublicId, "missing"));
      if (!workflowRunPublicId.equals(snapshot.workflowRunId())) {
        // Trap T11 cross-run leak guard — same shape as missing-row.
        throw clarificationNotFound(workflowRunPublicId, clarificationPublicId, "cross_run");
      }
      ClarificationStatusView view = toClarificationStatusView(snapshot);
      log.info(
          "getClarificationStatus success workflowRunId={} clarificationId={} status={}",
          workflowRunPublicId,
          clarificationPublicId,
          view.status());
      return view;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 2.12 AC9: detailed per-run summary including {@code pendingClarifications}. Story 2.14
   * (allowed-actions endpoint) reads this to gate {@code approve_spec}.
   */
  @Transactional(readOnly = true)
  public WorkflowRunDetailedSummaryView getRunSummary(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info("getRunSummary entry workflowRunId={}", workflowRunPublicId);
      // P28 — read ordering: under READ COMMITTED, putting countPendingByWorkflowRun LAST means
      // any concurrent transaction that flipped a clarification to incorporated/rejected_invalid
      // commits BEFORE we read the count, so we never surface a stale-high "pending" alongside
      // a fresh latest-event-type of `clarification.incorporated`. Bumping the @Transactional to
      // REPEATABLE_READ would also work but adds lock-conflict risk; reorder is cheaper.
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunPublicId)
              .orElseThrow(() -> runNotFound(workflowRunPublicId));
      Optional<WorkflowEventRecord> latest =
          workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunPublicId);
      String ticketRef =
          integrationLinkService
              .findActiveLinkByWorkflowRun(workflowRunPublicId)
              .map(IntegrationLink::externalRef)
              .orElse(null);
      int pending = clarificationReadPort.countPendingByWorkflowRun(workflowRunPublicId);
      // FR21 (story 3.20 AC4) — derive product vs technical acceptance as DISTINCT typed states,
      // never collapsed. Product derives from the latest approved `spec` lineage; technical from
      // the
      // latest approved `implementationPlan` OR `prOutput` lineage (reviewer_role + artifact type
      // already distinguish them at the data layer). REST/CLI/UI surfacing is deferred to
      // 3.23/3.28/3.31 (Trap T8) — these two fields stay application-internal.
      RunApprovalState productApprovalState =
          approvalReadPort
                  .findLatestApprovedForArtifactLineage(
                      workflowRunPublicId, ArtifactType.SPEC.value())
                  .isPresent()
              ? RunApprovalState.APPROVED
              : RunApprovalState.NONE;
      boolean technicalApproved =
          approvalReadPort
                  .findLatestApprovedForArtifactLineage(
                      workflowRunPublicId, ArtifactType.IMPLEMENTATION_PLAN.value())
                  .isPresent()
              || approvalReadPort
                  .findLatestApprovedForArtifactLineage(
                      workflowRunPublicId, ArtifactType.PR_OUTPUT.value())
                  .isPresent();
      RunApprovalState technicalApprovalState =
          technicalApproved ? RunApprovalState.APPROVED : RunApprovalState.NONE;
      // Story 3.22 (AC8 / OQ-4): takeover attribution is populated ONLY for a TakenOver run.
      // who/when/role are reconstructed from the AUTHORITATIVE takeover recovery_actions row
      // (actor_identity, actor_type, reviewer_role='developer', created_at) — never inferred from
      // an arbitrary later audit event, and reviewer_role is real persisted data rather than a
      // hard-coded constant. The reviewer reason has no recovery_actions column (OQ-4), so it is
      // read from the → TakenOver transition event's details (the only place
      // WorkflowTransitionService
      // persists it). A defensive fallback attributes from the transition event when no takeover
      // recovery_actions row exists (legacy/edge runs).
      TakeoverAttribution takenOverBy = null;
      OffsetDateTime takenOverAt = null;
      String takenOverReason = null;
      if (run.currentState() == WorkflowState.TAKEN_OVER) {
        Optional<RecoveryActionSnapshot> takeoverAction =
            recoveryActionRecordPort.findLatestTakeoverForRun(workflowRunPublicId);
        Optional<WorkflowEventRecord> takeoverTransition =
            workflowEventReadPort.findLatestTransitionToState(
                workflowRunPublicId, WorkflowState.TAKEN_OVER);
        if (takeoverAction.isPresent()) {
          RecoveryActionSnapshot action = takeoverAction.get();
          takenOverBy =
              new TakeoverAttribution(
                  action.actorIdentity(),
                  action.actorType() == null ? null : action.actorType().value(),
                  action.reviewerRole());
          takenOverAt = action.createdAt();
        } else if (takeoverTransition.isPresent()) {
          WorkflowEventRecord takeoverEvent = takeoverTransition.get();
          takenOverBy =
              new TakeoverAttribution(
                  takeoverEvent.actorIdentity(),
                  takeoverEvent.actorType() == null ? null : takeoverEvent.actorType().value(),
                  "developer");
          takenOverAt = takeoverEvent.createdAt();
        }
        takenOverReason = takeoverTransition.map(WorkflowEventRecord::reason).orElse(null);
      }
      WorkflowRunDetailedSummaryView view =
          new WorkflowRunDetailedSummaryView(
              run.publicId(),
              run.currentState().value(),
              ticketRef,
              latest.map(WorkflowEventRecord::createdAt).orElse(null),
              latest.map(record -> record.eventType().value()).orElse(null),
              run.specRejectionLoopCount(),
              run.escalationMarkerSet(),
              pending,
              productApprovalState.name(),
              technicalApprovalState.name(),
              takenOverBy,
              takenOverAt,
              takenOverReason,
              run.archivedAt());
      log.info(
          "getRunSummary success workflowRunId={} pendingClarifications={} currentState={} productApprovalState={} technicalApprovalState={}",
          workflowRunPublicId,
          pending,
          view.currentState(),
          view.productApprovalState(),
          view.technicalApprovalState());
      return view;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /** Story 3.19 — recent-completion window backing {@code recentThroughputPerMinute} (AC1). */
  private static final Duration THROUGHPUT_WINDOW = Duration.ofSeconds(60);

  /**
   * Upper bound on the leased-running rows fetched for the per-worker view. Leased running rows are
   * physically bounded by the worker-pool size (max 32 — {@code RunnerWorkerPoolProperties} clamps
   * it), so this cap is far above any real value and never truncates; it is a runaway backstop
   * only.
   */
  private static final int LEASED_FETCH_CAP = 1024;

  /**
   * Story 3.19 (AC1/AC3/AC9/AC10) — the single read seam for runner-queue + worker-pool inspection.
   * Returns a {@link RunnerQueueStatus} typed view consumed by the CLI ({@code deliveryline workers
   * status}), the REST endpoint ({@code GET /api/v1/runner-queue/status}), and the Prometheus
   * exporter. READ-ONLY: touches no queue / worker / dispatch / write path.
   *
   * <p>{@code poolSize} comes from config ({@link RunnerWorkerPoolProperties#size()}) — always
   * present even though the pool is disabled in the test profile (Reconciliation 7). All live
   * counts come from the read port; {@code idleWorkers = max(0, poolSize − activeWorkers)} and the
   * per-worker list is reconstructed from the leased running DB rows (the pool keeps no in-memory
   * roster — Reconciliation 6). When {@code batchIdOrNull} is non-null the queue counts + worker
   * list scope to that batch's executions (AC9); {@code poolSize} stays global. A malformed {@code
   * batchId} raises the existing {@code INVALID_ID_PREFIX} via the read port's prefix guard.
   */
  @Transactional(readOnly = true)
  public RunnerQueueStatus getRunnerQueueStatus(String batchIdRaw) {
    // Normalize blank/whitespace to null (= global view) in this single seam so every transport
    // agrees: the CLI already collapses an empty --batch-id to null, and doing it here makes the
    // REST `?batchId=` empty/whitespace param behave the same (global) instead of failing the
    // prefix guard with a 400. A genuinely malformed non-blank id still raises INVALID_ID_PREFIX.
    String batchIdOrNull = (batchIdRaw == null || batchIdRaw.isBlank()) ? null : batchIdRaw.trim();
    // batchId carried as a structured log parameter (not MDC): the MdcKeys permitted-key set is
    // closed and adding a new key fans out to its contract — the Logging Requirements explicitly
    // allow "pass as parameters" where MDC is not already wired.
    log.info("getRunnerQueueStatus entry batchId={}", batchIdOrNull);
    Duration staleWindow = maxStaleWindow();
    RunnerQueueCounts counts =
        runnerExecutionRecordPort.loadQueueCounts(staleWindow, THROUGHPUT_WINDOW, batchIdOrNull);
    List<RunnerExecutionSnapshot> leased =
        runnerExecutionRecordPort.findLeasedRunning(batchIdOrNull, LEASED_FETCH_CAP);

    int poolSize = runnerWorkerPoolProperties.size();
    long activeWorkers = counts.activeWorkers();
    long idleWorkers = Math.max(0L, poolSize - activeWorkers);
    List<WorkerStatus> workers = new ArrayList<>(leased.size());
    for (RunnerExecutionSnapshot row : leased) {
      workers.add(
          new WorkerStatus(
              row.workerId(),
              "busy",
              row.publicId(),
              row.workflowRunPublicId(),
              row.dispatchedAt(),
              row.stage() == null ? null : row.stage().value()));
    }

    RunnerQueueStatus view =
        new RunnerQueueStatus(
            poolSize,
            activeWorkers,
            idleWorkers,
            counts.queueDepth(),
            counts.oldestQueuedAt(),
            counts.oldestQueuedAgeSeconds(),
            activeWorkers,
            // Normalize the windowed completion count to a per-minute rate so the field name stays
            // honest if THROUGHPUT_WINDOW ever moves off 60s (today the window is 60s → identity).
            Math.round(
                counts.recentThroughput() * 60.0 / Math.max(1L, THROUGHPUT_WINDOW.toSeconds())),
            counts.staleQueuedCount(),
            counts.staleDispatchedCount(),
            List.copyOf(workers));

    if (view.staleQueuedCount() > 0 || view.staleDispatchedCount() > 0) {
      // A genuine operational anomaly worth a log line, not just a metric (Logging Requirements).
      log.warn(
          "getRunnerQueueStatus stale-detected batchId={} staleQueued={} staleDispatched={} queueDepth={}",
          batchIdOrNull,
          view.staleQueuedCount(),
          view.staleDispatchedCount(),
          view.queueDepth());
    }
    log.info(
        "getRunnerQueueStatus success batchId={} queueDepth={} activeWorkers={} idleWorkers={} oldestQueuedAgeSeconds={}",
        batchIdOrNull,
        view.queueDepth(),
        view.activeWorkers(),
        view.idleWorkers(),
        view.oldestQueuedAgeSeconds());
    return view;
  }

  /**
   * The lease/stale window = {@code staleThresholdMultiplier × stageTimeout}, identical to the
   * broker's orphan threshold. The two stage timeouts can differ, so take the longest so a queued
   * or dispatched row of the slower stage is never flagged stale prematurely by the shorter window.
   */
  private Duration maxStaleWindow() {
    Duration max = Duration.ZERO;
    for (RunnerStage stage : RunnerStage.values()) {
      Duration window = runnerProperties.staleThresholdFor(stage);
      if (window.compareTo(max) > 0) {
        max = window;
      }
    }
    return max.isZero() ? Duration.ofSeconds(1) : max;
  }

  /**
   * Story 2.14 — recognized {@code actorRole} query-param values for the allowed-actions endpoint.
   * Distinct from {@link ApprovalReviewerRoleResolver}'s set: that resolver always falls back to a
   * default and must never reject (used in the spec-approval mutation path where a missing role
   * must still let the mutation proceed). This set is the "fail closed" identity used by AC7 — an
   * unrecognized value surfaces a typed {@link DomainErrorCode#UNKNOWN_ACTOR_ROLE} so the UI can
   * render a typed error rather than silently fall back.
   */
  /**
   * Story 2.14 review P2 — canonical wire strings for the two recognized {@code actorRole} values.
   * Used by both {@link #RECOGNIZED_ACTOR_ROLES} membership and the matrix switch below so a rename
   * cannot drift the two sources of truth apart.
   */
  static final String ROLE_PRODUCT_REVIEWER = "product_reviewer";

  static final String ROLE_WORKFLOW_OWNER = "workflow_owner";

  /**
   * Story 3.20 (AC12 / OQ-3) — the developer-review actor role recognized for {@code
   * accept_implementation} in the {@code WAITING_FOR_REVIEW} state. Added to {@link
   * #RECOGNIZED_ACTOR_ROLES} so {@code getAllowedActions(runId, "developer")} does not throw {@code
   * UNKNOWN_ACTOR_ROLE}. {@code reject_implementation} (3.21) / {@code takeover} (3.22) reuse the
   * same role + matrix branch.
   */
  static final String ROLE_DEVELOPER = "developer";

  static final Set<String> RECOGNIZED_ACTOR_ROLES =
      Set.of(ROLE_PRODUCT_REVIEWER, ROLE_WORKFLOW_OWNER, ROLE_DEVELOPER);

  /** Default {@code actorRole} when the query param is null/blank (story 2.14 AC6 MVP). */
  static final String DEFAULT_ACTOR_ROLE = ROLE_PRODUCT_REVIEWER;

  /**
   * Story 2.14 — backend-authoritative allowed-actions derivation for the {@code GET
   * /api/v1/workflows/{workflowRunId}/allowed-actions} endpoint. The sole source of truth for the
   * state×role → action-set matrix (UX-DR12) — controllers and adapters MUST NOT compose this set
   * themselves, pinned by {@code
   * ALLOWED_ACTION_DERIVATION_LIVES_ONLY_IN_WORKFLOW_INSPECTION_SERVICE} ArchUnit rule.
   *
   * <p>The returned {@link AllowedActionsView} carries the typed action list plus a version stamp
   * the UI Approval/Decision Bar (story 2.19) echoes back on mutations so a stale UI surfaces
   * {@code APPROVAL_VERSION_MISMATCH} on the next approve attempt.
   *
   * <p>SEAM (Epic 3 / Epic 4): the matrix below currently emits the 7 in-scope actions. Future
   * stories will additively wire {@code approve_implementation}, {@code reject_implementation},
   * {@code takeover} (Epic 3) and {@code resume}, {@code reconcile}, {@code rerun_from_step},
   * {@code pause}, {@code clear_escalation_marker} (Epic 4). The current switch's affected cases
   * are marked with {@code // SEAM (Epic 3/4)} comments so the next consumer doesn't have to
   * rediscover the seam.
   *
   * @param workflowRunPublicId the {@code run_}-prefixed run id (prefix-validated first)
   * @param actorRole the role string from the query param; null/blank defaults to {@code
   *     product_reviewer}; any other value not in {@link #RECOGNIZED_ACTOR_ROLES} throws {@link
   *     DomainErrorCode#UNKNOWN_ACTOR_ROLE}
   * @return view carrying the typed action list and version stamp
   */
  @Transactional(readOnly = true)
  public AllowedActionsView getAllowedActions(String workflowRunPublicId, String actorRole) {
    // Review P1: open MDC scope BEFORE validation calls so INVALID_ID_PREFIX +
    // UNKNOWN_ACTOR_ROLE rejection log lines carry the workflowRunId MDC key (otherwise
    // operators tracing 400-shaped rejections see entry without the structured correlation key
    // even though the message string carries it).
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
      String resolvedRole = resolveActorRole(actorRole, workflowRunPublicId);
      log.info(
          "getAllowedActions entry workflowRunId={} actorRole={}",
          MdcKeys.sanitizeForLog(workflowRunPublicId),
          MdcKeys.sanitizeForLog(resolvedRole));

      WorkflowRunDetailedSummaryView summary = getRunSummary(workflowRunPublicId);
      WorkflowState state = WorkflowState.fromValue(summary.currentState(), "currentState");

      // TRAP 2: use the LATEST spec artifact (any status), NOT the last approved one. The UI's
      // version stamp must match what the reviewer currently sees in the artifact panel; deriving
      // from the approved spec would drift on reject-and-resubmit loops.
      Optional<ArtifactRecordSnapshot> latestSpecOpt =
          artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
              workflowRunPublicId, ArtifactType.SPEC.value());
      Integer specVersion = latestSpecOpt.map(ArtifactRecordSnapshot::version).orElse(null);
      String latestSpecPublicId = latestSpecOpt.map(ArtifactRecordSnapshot::publicId).orElse(null);

      // currentContextBundleVersion MUST reflect the artifact the reviewer is currently deciding
      // on,
      // so the value the UI echoes back matches what that decision's optimistic-concurrency check
      // (ApprovalVersionBinder) compares against. At WaitingForReview the developer decides on the
      // IMPLEMENTATION artifact (prOutput, else implementationPlan), whose producing
      // runner-execution
      // carries the EXECUTION context-bundle version — which diverges from the spec bundle (a retry
      // mints a fresh execution version, e.g. 3, while the spec bundle stays 1). Every other state
      // keeps the spec bundle (the spec-approval flow). Without this the implementation-review
      // accept/reject PERMANENTLY 409s APPROVAL_VERSION_MISMATCH (stamp says spec=1, binder demands
      // the execution bundle), unfixable by refresh.
      Integer bundleVersion =
          state == WorkflowState.WAITING_FOR_REVIEW
              ? resolveImplementationContextBundleVersion(workflowRunPublicId)
              : resolveSpecContextBundleVersion(latestSpecPublicId);

      String latestEventId =
          workflowEventReadPort
              .findLatestByWorkflowRunPublicId(workflowRunPublicId)
              .map(WorkflowEventRecord::publicId)
              .orElse(null);

      List<AllowedAction> actions =
          computeActionMatrix(
              state,
              resolvedRole,
              summary.pendingClarifications(),
              latestSpecPublicId,
              summary.archivedAt() != null);

      AllowedActionsView view =
          new AllowedActionsView(
              List.copyOf(actions),
              new AllowedActionsVersionStamp(
                  summary.currentState(), specVersion, bundleVersion, latestEventId));
      log.info(
          "getAllowedActions success workflowRunId={} actorRole={} workflowState={} actionCount={} versionStampLastEventId={}",
          MdcKeys.sanitizeForLog(workflowRunPublicId),
          MdcKeys.sanitizeForLog(resolvedRole),
          view.versionStamp().workflowState(),
          actions.size(),
          MdcKeys.sanitizeForLog(latestEventId));
      return view;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private String resolveActorRole(String rawActorRole, String workflowRunPublicId) {
    if (rawActorRole == null || rawActorRole.isBlank()) {
      return DEFAULT_ACTOR_ROLE;
    }
    String trimmed = rawActorRole.strip();
    if (!RECOGNIZED_ACTOR_ROLES.contains(trimmed)) {
      log.warn(
          "getAllowedActions rejected UNKNOWN_ACTOR_ROLE workflowRunId={} actorRole={}",
          MdcKeys.sanitizeForLog(workflowRunPublicId),
          MdcKeys.sanitizeForLog(trimmed));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("actorRole", trimmed);
      throw new DomainException(
          DomainErrorCode.UNKNOWN_ACTOR_ROLE, "Unknown actor role: " + trimmed, details);
    }
    return trimmed;
  }

  // Story 3d-8 (FR67, AC3/AC4): soft-hide is orthogonal to the per-state lifecycle — a run can be
  // hidden from ANY state. The base matrix encodes state×role; this wrapper appends exactly one of
  // archive_run (a live run) / unarchive_run (an already-hidden run) so the two affordances are
  // mutually exclusive per run. Kept inside WorkflowInspectionService so the ArchUnit pin (the
  // matrix has a single home) still holds.
  private List<AllowedAction> computeActionMatrix(
      WorkflowState state,
      String actorRole,
      int pendingClarifications,
      String latestSpecPublicId,
      boolean archived) {
    List<AllowedAction> base =
        baseActionMatrix(state, actorRole, pendingClarifications, latestSpecPublicId);
    // Soft-hide is a run-owner triage affordance only (review decision 3d-8/D1): gate
    // archive_run/unarchive_run to workflow_owner, mirroring RETRY / OPEN_DIAGNOSTIC_CONSOLE.
    // It stays additive + orthogonal to the per-state lifecycle actions, just role-scoped.
    if (!ROLE_WORKFLOW_OWNER.equals(actorRole)) {
      return base;
    }
    List<AllowedAction> withArchive = new ArrayList<>(base);
    withArchive.add(archived ? AllowedAction.UNARCHIVE_RUN : AllowedAction.ARCHIVE_RUN);
    return List.copyOf(withArchive);
  }

  private List<AllowedAction> baseActionMatrix(
      WorkflowState state, String actorRole, int pendingClarifications, String latestSpecPublicId) {
    // Single switch — the sole place in the codebase where state×role → action-set is encoded
    // (UX-DR12 + ArchUnit pin). Any duplication outside this method is a future-bug seed.
    switch (state) {
      case INBOX:
      case PLANNED:
        return List.of(AllowedAction.VIEW_ONLY);
      case INVESTIGATING:
        {
          // AC3 semantic: "open" = status literally 'open' (unanswered). This is DIFFERENT from
          // story 2.12's pendingClarifications gate which counts NOT-in-{incorporated,
          // rejected_invalid}. The AC4 gate (below) uses the 2.12 definition; this AC3 row asks
          // "are there unanswered questions on the latest in-flight spec?".
          if (latestSpecPublicId != null && hasOpenClarificationOnArtifact(latestSpecPublicId)) {
            return List.of(AllowedAction.VIEW_ONLY, AllowedAction.ANSWER_CLARIFICATION);
          }
          return List.of(AllowedAction.VIEW_ONLY);
        }
      case WAITING_FOR_SPEC_APPROVAL:
        {
          // Story 3e-2 (AC1/AC2): the accept_clarification + regenerate_spec_with_clarifications
          // actions are surfaced alongside answer_clarification for the reviewer roles
          // (product_reviewer + workflow_owner). Both are surfaced unconditionally (not gated on a
          // pending/accepted count) — the canonical executors no-op/guard when there is nothing to
          // accept or no accepted clarification to rebuild from, mirroring how answer_clarification
          // is offered regardless of the open-question count (Completion Notes: "surface always").
          if (ROLE_PRODUCT_REVIEWER.equals(actorRole)) {
            // AC4: drop approve_spec when pending clarifications block approval (story 2.12 gate).
            if (pendingClarifications > 0) {
              return List.of(
                  AllowedAction.REJECT_SPEC,
                  AllowedAction.ANSWER_CLARIFICATION,
                  AllowedAction.ACCEPT_CLARIFICATION,
                  AllowedAction.REGENERATE_SPEC);
            }
            return List.of(
                AllowedAction.APPROVE_SPEC,
                AllowedAction.REJECT_SPEC,
                AllowedAction.ANSWER_CLARIFICATION,
                AllowedAction.ACCEPT_CLARIFICATION,
                AllowedAction.REGENERATE_SPEC);
          }
          // Story 3e-2 (AC1/AC2) — accept + regenerate are REVIEWER actions (product_reviewer above
          // + workflow_owner here). The run owner can also drive them; other recognized roles (e.g.
          // developer) keep the view + answer set only.
          if (ROLE_WORKFLOW_OWNER.equals(actorRole)) {
            return List.of(
                AllowedAction.VIEW_ONLY,
                AllowedAction.ANSWER_CLARIFICATION,
                AllowedAction.ACCEPT_CLARIFICATION,
                AllowedAction.REGENERATE_SPEC);
          }
          return List.of(AllowedAction.VIEW_ONLY, AllowedAction.ANSWER_CLARIFICATION);
        }
      case EXECUTING:
        // Story 3d-5 (AC6): a runner execution exists in EXECUTING, so the log viewer is offered
        // (role-agnostic) alongside the passive views. Story 3d-6 (AC4) additively offers the
        // read-only diagnostic console — but ONLY to the run owner (workflow_owner, the single
        // local
        // operator), since EXECUTING is the only state where a container is live to attach. The
        // endpoint re-checks liveness at attach time (LIVE-ONLY, DD-3).
        if (ROLE_WORKFLOW_OWNER.equals(actorRole)) {
          return List.of(
              AllowedAction.VIEW_ONLY,
              AllowedAction.AWAIT_OUTCOME,
              AllowedAction.VIEW_RUNNER_LOGS,
              AllowedAction.OPEN_DIAGNOSTIC_CONSOLE,
              AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
        }
        return List.of(
            AllowedAction.VIEW_ONLY,
            AllowedAction.AWAIT_OUTCOME,
            AllowedAction.VIEW_RUNNER_LOGS,
            AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
      case WAITING_FOR_REVIEW:
        // Story 3.20 (AC12) + Story 3.21 (AC9): the developer-review actor may accept OR reject the
        // implementation here. Story 3.22 (AC9) additively adds takeover_workflow for the developer
        // role; all other roles keep view_only. Story 3d-5 (AC6) additively adds view_runner_logs
        // for EVERY role (the producing runner execution's logs are reviewable here).
        if (ROLE_DEVELOPER.equals(actorRole)) {
          return List.of(
              AllowedAction.ACCEPT_IMPLEMENTATION,
              AllowedAction.REJECT_IMPLEMENTATION,
              AllowedAction.TAKEOVER_WORKFLOW,
              AllowedAction.VIEW_ONLY,
              AllowedAction.VIEW_RUNNER_LOGS,
              AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
        }
        return List.of(
            AllowedAction.VIEW_ONLY,
            AllowedAction.VIEW_RUNNER_LOGS,
            AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
      case WAITING_FOR_MANUAL_EXECUTION:
        // Story 3d-3 (AC7 / R6): a run parked for manual execution advertises the bundle-obtain +
        // artifact-submit actions to the local operator (workflow_owner — the run owner); every
        // other role gets view_only. The endpoints that honor these land in 3d-4; 3d-3 only
        // registers + surfaces them so the run already advertises them when 3d-4 wires the routes.
        if (ROLE_WORKFLOW_OWNER.equals(actorRole)) {
          return List.of(
              AllowedAction.OBTAIN_MANUAL_BUNDLE,
              AllowedAction.SUBMIT_MANUAL_ARTIFACT,
              AllowedAction.VIEW_ONLY);
        }
        return List.of(AllowedAction.VIEW_ONLY);
      case COMPLETED:
        return List.of(AllowedAction.VIEW_ONLY);
      case FAILED:
        {
          // Story 3d-5 (AC6): the failed runner execution's logs are a primary diagnostic surface,
          // so view_runner_logs is offered to every role here.
          if (ROLE_WORKFLOW_OWNER.equals(actorRole)) {
            return List.of(
                AllowedAction.RETRY,
                AllowedAction.VIEW_DIAGNOSTICS,
                AllowedAction.VIEW_RUNNER_LOGS,
                AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
          }
          return List.of(
              AllowedAction.VIEW_ONLY,
              AllowedAction.VIEW_DIAGNOSTICS,
              AllowedAction.VIEW_RUNNER_LOGS,
              AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
        }
      case PAUSED:
        // SEAM (Epic 4): Epic 4 adds resume here for workflow_owner.
        // Story 3d-5 (AC6): the paused run's runner execution logs remain viewable.
        return List.of(
            AllowedAction.VIEW_ONLY,
            AllowedAction.VIEW_DIAGNOSTICS,
            AllowedAction.VIEW_RUNNER_LOGS,
            AllowedAction.VIEW_PROVIDER_USAGE_STATUS);
      case TAKEN_OVER:
        // SEAM (Epic 4): Epic 4 adds reconcile / clear_escalation_marker here for workflow_owner.
        return List.of(AllowedAction.VIEW_ONLY);
      case RECONCILED:
        return List.of(AllowedAction.VIEW_ONLY);
      default:
        // Future-state guard (AC8) — any new WorkflowState that forgets to update this switch
        // surfaces immediately rather than silently returning an empty action list.
        throw new IllegalStateException(
            "Allowed-actions matrix missing case for state " + state.value());
    }
  }

  private boolean hasOpenClarificationOnArtifact(String artifactPublicId) {
    for (Clarification clarification : clarificationReadPort.listByArtifactId(artifactPublicId)) {
      if (clarification.isOpen()) {
        return true;
      }
    }
    return false;
  }

  private static ClarificationStatusView toClarificationStatusView(
      ClarificationLifecycleSnapshot s) {
    return new ClarificationStatusView(
        s.publicId(),
        s.workflowRunId(),
        s.artifactId(),
        s.artifactVersion(),
        s.questionId(),
        s.questionText(),
        s.status(),
        s.answerText(),
        s.answeredByActor(),
        s.answeredByActorType() == null ? null : s.answeredByActorType().value(),
        s.answeredAt(),
        s.acceptedAt(),
        s.incorporatedAt(),
        s.incorporatedIntoArtifactPublicId(),
        s.supersededByArtifactPublicId(),
        s.noEffectReason(),
        s.createdAt());
  }

  private DomainException clarificationNotFound(
      String workflowRunPublicId, String clarificationPublicId, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("clarificationId", clarificationPublicId);
    details.put("workflowRunId", workflowRunPublicId);
    log.warn(
        "getClarificationStatus rejected CLARIFICATION_NOT_FOUND workflowRunId={} clarificationId={} reason={}",
        workflowRunPublicId,
        clarificationPublicId,
        reason);
    return new DomainException(
        DomainErrorCode.CLARIFICATION_NOT_FOUND,
        "Clarification not found: " + clarificationPublicId,
        details);
  }

  private static ClarificationView toClarificationView(Clarification row) {
    return new ClarificationView(
        row.publicId(),
        row.workflowRunId(),
        row.artifactId(),
        row.artifactVersion(),
        row.questionId(),
        row.questionText(),
        row.status(),
        row.answerText(),
        row.answeredByActor(),
        row.answeredByActorType() == null ? null : row.answeredByActorType().value(),
        row.answeredAt(),
        row.createdAt());
  }

  @Transactional(readOnly = true)
  public WorkflowStatusView getStatus(String workflowRunPublicId) {
    // Validate the public-id prefix BEFORE any logging or DB lookup so that:
    // (a) null/blank/wrong-prefix input surfaces a governed DomainException(INVALID_ID_PREFIX)
    //     instead of a raw NullPointerException or a misleading RUN_NOT_FOUND, and
    // (b) the input cannot carry control characters (CR/LF/TAB) into MDC or log interpolation
    //     because the registered SUFFIX_PATTERN restricts allowed characters to [A-Za-z0-9_-].
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info("inspecting workflow_run snapshot workflowRunId={}", workflowRunPublicId);
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunPublicId)
              .orElseThrow(() -> runNotFound(workflowRunPublicId));

      Optional<WorkflowEventRecord> latest =
          workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunPublicId);

      List<LatestArtifactView> latestArtifacts = new ArrayList<>();
      for (ArtifactType artifactType : ArtifactType.values()) {
        Optional<ArtifactRecordSnapshot> latestForType =
            artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
                workflowRunPublicId, artifactType.value());
        latestForType.ifPresent(
            snapshot ->
                latestArtifacts.add(
                    new LatestArtifactView(
                        snapshot.artifactType().value(),
                        snapshot.version(),
                        snapshot.status().value(),
                        snapshot.publicId())));
      }

      LinkedTicketView linkedTicket =
          integrationLinkService
              .findActiveLinkByWorkflowRun(workflowRunPublicId)
              .map(WorkflowInspectionService::toLinkedTicket)
              .orElse(null);

      FailureDescription failure = recoveryService.describeFailure(workflowRunPublicId);

      WorkflowStatusView view =
          new WorkflowStatusView(
              run.publicId(),
              run.currentState(),
              latest.map(WorkflowEventRecord::actorIdentity).orElse(null),
              latest.map(record -> record.actorType().value()).orElse(null),
              latest.map(record -> record.eventType().value()).orElse(null),
              latest.map(WorkflowEventRecord::createdAt).orElse(null),
              List.copyOf(latestArtifacts),
              linkedTicket,
              failure.failedStage(),
              failure.lastSuccessfulStage(),
              failure.failureTimestamp(),
              failure.failureCategory(),
              failure.lastActivityTimestamp(),
              failure.nextSafeAction(),
              run.specRejectionLoopCount(),
              run.escalationMarkerSet());
      log.info(
          "inspecting workflow_run snapshot success workflowRunId={} currentState={}",
          workflowRunPublicId,
          run.currentState().value());
      return view;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  @Transactional(readOnly = true)
  public WorkflowHistoryView listHistory(
      String workflowRunPublicId, OffsetDateTime sinceInclusive) {
    // See getStatus() for the prefix-validation rationale (covers governed error surface +
    // log-injection defense in one check).
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info(
          "inspecting workflow_run history workflowRunId={} sinceInclusive={}",
          workflowRunPublicId,
          sinceInclusive);
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunPublicId)
              .orElseThrow(() -> runNotFound(workflowRunPublicId));

      List<WorkflowEventRecord> events =
          workflowEventReadPort.listByWorkflowRunPublicId(run.publicId(), sinceInclusive);
      List<WorkflowEventView> rendered = new ArrayList<>(events.size());
      for (WorkflowEventRecord event : events) {
        rendered.add(
            new WorkflowEventView(
                event.publicId(),
                event.eventType().value(),
                event.priorState() == null ? null : event.priorState().value(),
                event.resultingState() == null ? null : event.resultingState().value(),
                event.actorIdentity(),
                event.actorType().value(),
                event.reason(),
                event.failureCategory() == null ? null : event.failureCategory().value(),
                event.interventionMarker(),
                event.createdAt(),
                redactDetails(filterAllowedDetails(event.details()))));
      }
      log.info(
          "inspecting workflow_run history success workflowRunId={} eventCount={}",
          workflowRunPublicId,
          rendered.size());
      return new WorkflowHistoryView(run.publicId(), List.copyOf(rendered));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Hard ceiling on the {@code GET /api/v1/workflows} list page size (story 6.9 Task 1). Mirrors
   * the page-size discipline of the events read port ({@link
   * WorkflowEventReadPort#HISTORY_CEILING}) — bounds the per-run enrichment fan-out (latest event +
   * active ticket lookup per row) for the MVP localhost queue.
   */
  public static final int MAX_LIST_PAGE_SIZE = 200;

  /** Default list page size when a caller does not request one (story 6.9 Task 1). */
  public static final int DEFAULT_LIST_PAGE_SIZE = 50;

  /**
   * List recent workflow runs newest-first for the queue/list UI (story 6.9 Task 1, AC1/AC2).
   * Read-only. Backs {@code GET /api/v1/workflows}.
   *
   * @param stateFilter optional {@link WorkflowState} filter; {@code null} returns all states
   * @param limit requested page size; clamped to {@code [1, }{@link #MAX_LIST_PAGE_SIZE}{@code ]}
   * @return lean per-run summaries (run id, current state, ticket ref, last event time + type)
   *     ordered by run creation descending
   */
  @Transactional(readOnly = true)
  public List<WorkflowRunSummaryView> listRuns(
      WorkflowState stateFilter, boolean includeArchived, int limit) {
    int capped = Math.min(Math.max(limit, 1), MAX_LIST_PAGE_SIZE);
    log.info(
        "listing workflow_runs stateFilter={} includeArchived={} limit={}",
        stateFilter == null ? "<all>" : stateFilter.value(),
        includeArchived,
        capped);
    List<WorkflowRunSnapshot> runs =
        workflowRunReadPort.listRuns(stateFilter, includeArchived, capped);
    List<WorkflowRunSummaryView> summaries = new ArrayList<>(runs.size());
    for (WorkflowRunSnapshot run : runs) {
      Optional<WorkflowEventRecord> latest =
          workflowEventReadPort.findLatestByWorkflowRunPublicId(run.publicId());
      String ticketRef =
          integrationLinkService
              .findActiveLinkByWorkflowRun(run.publicId())
              .map(IntegrationLink::externalRef)
              .orElse(null);
      int pendingClarifications = clarificationReadPort.countPendingByWorkflowRun(run.publicId());
      summaries.add(
          new WorkflowRunSummaryView(
              run.publicId(),
              run.currentState().value(),
              ticketRef,
              latest.map(WorkflowEventRecord::createdAt).orElse(null),
              latest.map(record -> record.eventType().value()).orElse(null),
              run.specRejectionLoopCount(),
              run.escalationMarkerSet(),
              pendingClarifications,
              run.archivedAt()));
    }
    log.info("listing workflow_runs success count={}", summaries.size());
    return summaries;
  }

  /**
   * Story 2.8 AC8 / FR10: latest approved spec for the workflow run, projected as a {@link
   * SpecificationArtifact}. Returns {@link Optional#empty()} (never {@code null}) when no spec has
   * been approved yet in this run.
   */
  @Transactional(readOnly = true)
  public Optional<SpecificationArtifact> getCurrentApprovedSpec(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info("getCurrentApprovedSpec entry workflowRunId={}", workflowRunPublicId);
      Optional<ApprovalSnapshot> approval =
          approvalReadPort.findLatestApprovedForArtifactLineage(
              workflowRunPublicId, ArtifactType.SPEC.value());
      if (approval.isEmpty()) {
        log.info(
            "getCurrentApprovedSpec success workflowRunId={} noApprovedSpec=true",
            workflowRunPublicId);
        return Optional.empty();
      }
      Optional<ArtifactRecordSnapshot> artifact =
          artifactRecordPort.findByPublicId(approval.get().artifactId());
      if (artifact.isEmpty()) {
        log.warn(
            "getCurrentApprovedSpec inconsistency workflowRunId={} approvalId={} artifactId={} reason=approvalReferencesUnknownArtifact",
            workflowRunPublicId,
            approval.get().publicId(),
            approval.get().artifactId());
        return Optional.empty();
      }
      SpecificationArtifact spec = SpecificationArtifact.fromSnapshot(artifact.get());
      log.info(
          "getCurrentApprovedSpec success workflowRunId={} artifactId={} version={}",
          workflowRunPublicId,
          spec.id(),
          spec.version());
      return Optional.of(spec);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 2.8 AC9 / FR11: full chronological spec history (ascending {@code version}) with each
   * version joined to its decision row from {@code approvals}. Spec versions with no approval row
   * are emitted with {@code decision="pending"}.
   */
  @Transactional(readOnly = true)
  public List<SpecHistoryEntry> getSpecHistory(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info("getSpecHistory entry workflowRunId={}", workflowRunPublicId);
      List<ArtifactRecordSnapshot> specVersions =
          artifactRecordPort.listByWorkflowRunIdAndArtifactType(
              workflowRunPublicId, ArtifactType.SPEC.value());
      List<ApprovalSnapshot> decisions =
          approvalReadPort.listByWorkflowRunAndArtifactType(
              workflowRunPublicId, ArtifactType.SPEC.value());

      Map<String, ApprovalSnapshot> decisionByVersionKey = new LinkedHashMap<>();
      for (ApprovalSnapshot decision : decisions) {
        String versionKey = specVersionKey(decision.artifactId(), decision.artifactVersion());
        ApprovalSnapshot prior = decisionByVersionKey.putIfAbsent(versionKey, decision);
        if (prior != null) {
          throw duplicateSpecDecision(workflowRunPublicId, prior, decision);
        }
      }

      List<SpecHistoryEntry> entries = new ArrayList<>(specVersions.size());
      for (ArtifactRecordSnapshot spec : specVersions) {
        SpecificationArtifact projected = SpecificationArtifact.fromSnapshot(spec);
        ApprovalSnapshot decision =
            decisionByVersionKey.get(specVersionKey(spec.publicId(), spec.version()));
        if (decision == null) {
          log.debug(
              "getSpecHistory pending version workflowRunId={} artifactId={} version={}",
              workflowRunPublicId,
              spec.publicId(),
              spec.version());
          entries.add(new SpecHistoryEntry(projected, "pending", null, null, null));
        } else {
          entries.add(
              new SpecHistoryEntry(
                  projected,
                  decision.decision(),
                  decision.reviewerRole(),
                  decision.rejectionTaxonomy(),
                  decision.decidedAt()));
        }
      }
      log.info(
          "getSpecHistory success workflowRunId={} historyLength={}",
          workflowRunPublicId,
          entries.size());
      return List.copyOf(entries);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 3a-9 (Gate 3): artifact-content read for the run-detail review surface. Backs {@code GET
   * /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}} — the live source the Artifact Review
   * Panel (story 2.17) renders so a reviewer can read the real spec body before approving it.
   *
   * <p>Resolution + guard order (security-first; never leak another run's artifact existence):
   *
   * <ol>
   *   <li>Validate both public-id prefixes ({@code run_} / {@code art_}) — malformed input surfaces
   *       a governed {@code INVALID_ID_PREFIX} (400) at the boundary, mirroring {@link #getStatus};
   *   <li>Resolve the run → {@code RUN_NOT_FOUND} (404) when absent;
   *   <li>Resolve the artifact by public id → {@code ARTIFACT_RECORD_NOT_FOUND} (404) when absent;
   *   <li>Cross-run ownership guard: an artifact owned by a different run is reported as {@code
   *       ARTIFACT_RECORD_NOT_FOUND} (404) — identical to "absent" so existence never leaks;
   *   <li>Classification guard: a {@code LOCAL_ONLY} artifact is never served and is likewise
   *       reported as {@code ARTIFACT_RECORD_NOT_FOUND} (404);
   *   <li>Read the persisted payload bytes via {@link ArtifactPayloadStore#readBytes(String)} →
   *       {@code ARTIFACT_PAYLOAD_UNAVAILABLE} (503) when unreadable.
   * </ol>
   *
   * <p>The returned {@code body} is the <em>already-redacted</em> payload (redaction happens at
   * write time per stories 1.10 / 2.24) decoded as a UTF-8 markdown string — there is no new
   * redaction logic here, and the raw bytes / {@code body} are never logged.
   *
   * @param workflowRunPublicId the {@code run_}-prefixed owning run id
   * @param artifactPublicId the {@code art_}-prefixed artifact id
   * @return a fully-populated read view: identity, type/version/status/classification, createdAt,
   *     short-form checksum, and the redacted UTF-8 body
   */
  @Transactional(readOnly = true)
  public ArtifactDetailView getArtifactDetail(String workflowRunPublicId, String artifactPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    PublicIdPrefixes.require(artifactPublicId, PublicIdPrefixes.ARTIFACT);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifactPublicId);
    try {
      log.info(
          "getArtifactDetail entry workflowRunId={} artifactId={}",
          workflowRunPublicId,
          artifactPublicId);

      // Resolve the run first so a non-existent run never reaches the artifact lookup
      // (RUN_NOT_FOUND
      // must win over ARTIFACT_RECORD_NOT_FOUND for a fabricated run id).
      workflowRunReadPort
          .findByPublicId(workflowRunPublicId)
          .orElseThrow(() -> runNotFound(workflowRunPublicId));

      ArtifactRecordSnapshot artifact =
          artifactRecordPort
              .findByPublicId(artifactPublicId)
              .orElseThrow(() -> artifactRecordNotFound(artifactPublicId));

      // Cross-run ownership guard — report as "not found", never leak that the id belongs to
      // another run.
      if (!workflowRunPublicId.equals(artifact.workflowRunId())) {
        log.warn(
            "getArtifactDetail cross-run reject workflowRunId={} artifactId={} ownerRunId={}",
            workflowRunPublicId,
            artifactPublicId,
            artifact.workflowRunId());
        throw artifactRecordNotFound(artifactPublicId);
      }

      // Classification guard — a local-only artifact must never be served as shareable content.
      if (artifact.classification() == DataClassification.LOCAL_ONLY) {
        log.warn(
            "getArtifactDetail classification reject workflowRunId={} artifactId={}"
                + " classification={}",
            workflowRunPublicId,
            artifactPublicId,
            artifact.classification().value());
        throw artifactRecordNotFound(artifactPublicId);
      }

      // Status guard — only AVAILABLE artifacts carry finalized, approvable content (mirrors
      // ArtifactService.isApprovalEligible). A non-available artifact — notably the deliberately
      // pending implementationPlan/prOutput, whose developer-review gate arrives with
      // 3.20/3.23/3.26
      // — is reported as "not found", never served prematurely.
      if (artifact.status() != ArtifactStatus.AVAILABLE) {
        log.warn(
            "getArtifactDetail status reject workflowRunId={} artifactId={} status={}",
            workflowRunPublicId,
            artifactPublicId,
            artifact.status().value());
        throw artifactRecordNotFound(artifactPublicId);
      }

      Optional<byte[]> payloadBytes = artifactPayloadStore.readBytes(artifact.storageRef());
      if (payloadBytes.isEmpty() || payloadBytes.get().length == 0) {
        log.warn(
            "getArtifactDetail payload unreadable workflowRunId={} artifactId={}",
            workflowRunPublicId,
            artifactPublicId);
        throw artifactPayloadUnavailable(artifactPublicId);
      }

      String body = new String(payloadBytes.get(), java.nio.charset.StandardCharsets.UTF_8);

      // Story 3b-5 — for a prOutput the structured fields are the source of truth: parse
      // branch/commitSha/diff from the stored payload JSON, co-presently source prReference+prState
      // from the active github_pr link, and blank the markdown body (the diff travels in the typed
      // `diff` field, never duplicated into the body that a markdown renderer would receive). A
      // malformed payload JSON must NOT 500 the read — it falls back to null structured fields and
      // an empty body. spec / implementationPlan reads are byte-identical to pre-3b-5 (all five
      // null,
      // body intact).
      String responseBody = body;
      String branch = null;
      String commitSha = null;
      String prReference = null;
      String prState = null;
      String diff = null;
      List<String> steps = null;
      if (artifact.artifactType() == ArtifactType.PR_OUTPUT) {
        responseBody = "";
        try {
          com.fasterxml.jackson.databind.JsonNode payload =
              objectMapper.readTree(payloadBytes.get());
          branch = textOrNull(payload, "branch");
          commitSha = textOrNull(payload, "commitSha");
          diff = textOrNull(payload, "diff");
        } catch (java.io.IOException malformed) {
          log.warn(
              "getArtifactDetail malformed prOutput payload (null structured fields)"
                  + " workflowRunId={} artifactId={}",
              workflowRunPublicId,
              artifactPublicId);
        }
        var prLink = integrationLinkService.findActiveGitHubPrLinkView(workflowRunPublicId);
        if (prLink.isPresent() && prLink.get().prState() != null) {
          // DD3 — prReference and prState are co-present (both from the same github_pr row); when
          // the link lacks a state we treat it as no-linkage so the frontend's isValidPrLinkage
          // (a present prLinkage must carry a valid prState) never sees a half-populated linkage.
          prReference = prLink.get().prReference();
          prState = prLink.get().prState();
        }
      } else if (artifact.artifactType() == ArtifactType.IMPLEMENTATION_PLAN) {
        // Story 3b-6 — the ordered steps are the source of truth for an implementationPlan: parse
        // them from the stored payload JSON and blank the markdown body (the raw JSON must never
        // reach SafeMarkdownRenderer). A malformed payload must NOT 500 — fall back to null steps
        // and keep the original body (degraded but safe) with a WARN. Never log the step text
        // (reviewer-authored plan content) — log the parsed count only.
        responseBody = "";
        try {
          com.fasterxml.jackson.databind.JsonNode payload =
              objectMapper.readTree(payloadBytes.get());
          steps = parseSteps(payload);
        } catch (java.io.IOException malformed) {
          responseBody = body;
          steps = null;
          log.warn(
              "getArtifactDetail malformed implementationPlan payload (null steps)"
                  + " workflowRunId={} artifactId={}",
              workflowRunPublicId,
              artifactPublicId);
        }
      }

      ArtifactDetailView view =
          new ArtifactDetailView(
              artifact.publicId(),
              artifact.artifactType().value(),
              artifact.version(),
              artifact.status().value(),
              artifact.classification().value(),
              artifact.createdAt(),
              shortChecksum(artifact.checksumAlgorithm(), artifact.checksumValue()),
              responseBody,
              branch,
              commitSha,
              prReference,
              prState,
              diff,
              steps);
      log.info(
          "getArtifactDetail success workflowRunId={} artifactId={} artifactType={} version={}"
              + " status={} bodyLength={}",
          workflowRunPublicId,
          artifactPublicId,
          view.artifactType(),
          view.version(),
          view.status(),
          body.length());
      return view;
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Short-form checksum for display: {@code <algorithm>:<first 12 hex chars>}. Returns {@code null}
   * when the artifact carries no checksum yet (e.g. a still-{@code pending} lineage row). Never
   * exposes the full digest — the short form is enough to correlate without inviting offline
   * brute-force of the underlying bytes.
   */
  private static String shortChecksum(String algorithm, String value) {
    if (algorithm == null || value == null || value.isBlank()) {
      return null;
    }
    // Normalize the algorithm to its canonical (upper-case) form so the served prefix is stable
    // regardless of how the algorithm string happened to be persisted (e.g. "sha-256" vs
    // "SHA-256").
    String canonical = ArtifactChecksum.canonicalAlgorithm(algorithm);
    String head = value.length() <= 12 ? value : value.substring(0, 12);
    return canonical + ":" + head;
  }

  /** Story 3b-5 — non-blank textual value of {@code field} on {@code node}, else {@code null}. */
  private static String textOrNull(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    return (value == null || value.isBlank()) ? null : value;
  }

  /**
   * Story 3b-6 — the implementationPlan ordered steps as a {@code List<String>} parsed from the
   * stored payload's {@code steps} array (each element's non-blank text; non-textual / blank
   * elements skipped). Returns {@code null} when the payload carries no usable {@code steps} array
   * so the read DTO stays null-clean for a step-less plan (degrading to the renderer's
   * empty-state).
   */
  private static List<String> parseSteps(JsonNode payload) {
    JsonNode stepsNode = payload.path("steps");
    if (!stepsNode.isArray() || stepsNode.isEmpty()) {
      return null;
    }
    List<String> steps = new ArrayList<>();
    for (JsonNode element : stepsNode) {
      if (!element.isTextual()) {
        continue;
      }
      String value = element.asText();
      if (!value.isBlank()) {
        steps.add(value);
      }
    }
    return steps.isEmpty() ? null : steps;
  }

  private static DomainException artifactRecordNotFound(String artifactPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", artifactPublicId);
    return new DomainException(
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        "Artifact record not found: " + artifactPublicId,
        details);
  }

  private static DomainException artifactPayloadUnavailable(String artifactPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("artifactId", artifactPublicId);
    return new DomainException(
        DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
        "Artifact payload unavailable for " + artifactPublicId,
        details);
  }

  /**
   * Story 2.8 AC7 / FR55: bundle inspection for a specific artifact.
   *
   * <p>Returns the redacted runner-contracts {@code context-bundle.v1} bytes that the runner saw
   * when it produced the artifact — read verbatim from the runner scratch store ({@link
   * RunnerScratchStore#tryReadContextBundle}). No recomposition. AC7 wording is strict: the bundle
   * returned MUST be the bundle that produced the artifact, not a current-state synthesis that may
   * have drifted from the original inputs.
   *
   * <p>Returns {@link Optional#empty()} (with a structured WARN) for any miss reason:
   *
   * <ul>
   *   <li>{@code artifactNotFound} — no row for {@code artifactId};
   *   <li>{@code runnerExecutionLinkMissing} — the artifact has no creation event tying it to a
   *       runner execution (e.g. CLI-only/seed artifact);
   *   <li>{@code runnerExecutionNotFound} — the link points at a runner_execution row that no
   *       longer exists;
   *   <li>{@code runnerExecutionRunMismatch} — the linked runner execution belongs to a different
   *       workflow run than the artifact;
   *   <li>{@code bundleNotPersisted} — scratch has been evicted; the historical bytes are gone and
   *       FR55 fidelity cannot be honored. A persisted-bundle column/file is deferred to a future
   *       story (see deferred-work.md).
   * </ul>
   */
  @Transactional(readOnly = true)
  public Optional<ContextBundle> getContextBundleForArtifact(String artifactId) {
    return Optional.ofNullable(getContextBundleLookupForArtifact(artifactId).bundle());
  }

  @Transactional(readOnly = true)
  /**
   * The spec-stage version stamp's bundle version: the latest spec artifact's context-bundle
   * version (or {@code null} when no spec / no resolvable bundle yet). Unchanged spec-approval-flow
   * behaviour, extracted so {@link #getAllowedActions} can branch on the review state.
   */
  private Integer resolveSpecContextBundleVersion(String latestSpecPublicId) {
    if (latestSpecPublicId == null) {
      return null;
    }
    ContextBundleLookupResult lookup = getContextBundleLookupForArtifact(latestSpecPublicId);
    return lookup.available() ? lookup.bundle().contextBundleVersion() : null;
  }

  /**
   * The implementation-review version stamp's bundle version: the EXECUTION context-bundle version
   * of the artifact under technical review — the highest-version {@code prOutput} (else {@code
   * implementationPlan}, mirroring the frontend's {@code resolveImplementationArtifact}) — resolved
   * via its producing runner-execution EXACTLY as {@code
   * ApprovalVersionBinder.resolveCurrentContextBundleVersion} does, so the value the UI sends back
   * matches what the accept/reject binder compares against. {@code null} when no implementation
   * artifact exists yet (or its runner-execution link is missing) → the bar renders blocked rather
   * than firing a request the binder would reject.
   */
  private Integer resolveImplementationContextBundleVersion(String workflowRunPublicId) {
    Optional<ArtifactRecordSnapshot> implArtifact =
        artifactRecordPort
            .findLatestByWorkflowRunIdAndArtifactType(
                workflowRunPublicId, ArtifactType.PR_OUTPUT.value())
            .or(
                () ->
                    artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
                        workflowRunPublicId, ArtifactType.IMPLEMENTATION_PLAN.value()));
    if (implArtifact.isEmpty()) {
      return null;
    }
    return artifactRecordPort
        .findRunnerExecutionIdForArtifact(implArtifact.get().publicId())
        .flatMap(runnerExecutionRecordPort::findByPublicId)
        .map(RunnerExecutionSnapshot::contextBundleVersion)
        .orElse(null);
  }

  public ContextBundleLookupResult getContextBundleLookupForArtifact(String artifactId) {
    PublicIdPrefixes.require(artifactId, PublicIdPrefixes.ARTIFACT);
    String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifactId);
    try {
      log.info("getContextBundleForArtifact entry artifactId={}", artifactId);
      Optional<ArtifactRecordSnapshot> artifact = artifactRecordPort.findByPublicId(artifactId);
      if (artifact.isEmpty()) {
        log.warn(
            "getContextBundleForArtifact miss artifactId={} reason=artifactNotFound", artifactId);
        return ContextBundleLookupResult.unavailable(artifactId, "artifactNotFound");
      }
      Optional<String> rexIdOpt = artifactRecordPort.findRunnerExecutionIdForArtifact(artifactId);
      if (rexIdOpt.isEmpty()) {
        log.warn(
            "getContextBundleForArtifact miss artifactId={} reason=runnerExecutionLinkMissing",
            artifactId);
        return ContextBundleLookupResult.unavailable(artifactId, "runnerExecutionLinkMissing");
      }
      String runnerExecutionId = rexIdOpt.get();
      Optional<RunnerExecutionSnapshot> rex =
          runnerExecutionRecordPort.findByPublicId(runnerExecutionId);
      if (rex.isEmpty()) {
        log.warn(
            "getContextBundleForArtifact miss artifactId={} runnerExecutionId={} reason=runnerExecutionNotFound",
            artifactId,
            runnerExecutionId);
        return ContextBundleLookupResult.unavailable(artifactId, "runnerExecutionNotFound");
      }
      RunnerExecutionSnapshot rexSnapshot = rex.get();
      ArtifactRecordSnapshot artifactSnapshot = artifact.get();
      if (!artifactSnapshot.workflowRunId().equals(rexSnapshot.workflowRunPublicId())) {
        log.warn(
            "getContextBundleForArtifact miss artifactId={} runnerExecutionId={} reason=runnerExecutionRunMismatch requestedWorkflowRunId={} resolvedWorkflowRunId={}",
            artifactId,
            runnerExecutionId,
            artifactSnapshot.workflowRunId(),
            rexSnapshot.workflowRunPublicId());
        return ContextBundleLookupResult.unavailable(artifactId, "runnerExecutionRunMismatch");
      }

      Optional<byte[]> scratchBytes = runnerScratchStore.tryReadContextBundle(runnerExecutionId);
      if (scratchBytes.isPresent() && scratchBytes.get().length > 0) {
        ContextBundle bundle =
            new ContextBundle(
                rexSnapshot.workflowRunPublicId(),
                rexSnapshot.stage(),
                runnerExecutionId,
                rexSnapshot.contextBundleVersion(),
                DataClassification.SHAREABLE_REDACTED,
                scratchBytes.get());
        log.info(
            "getContextBundleForArtifact success artifactId={} runnerExecutionId={} bundleByteLength={} source=scratch",
            artifactId,
            runnerExecutionId,
            bundle.redactedPayload().length);
        return ContextBundleLookupResult.available(artifactId, bundle);
      }

      log.warn(
          "getContextBundleForArtifact miss artifactId={} runnerExecutionId={} reason=bundleNotPersisted",
          artifactId,
          runnerExecutionId);
      return ContextBundleLookupResult.unavailable(artifactId, "bundleNotPersisted");
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
    }
  }

  /**
   * Story 3d-4 (AC1 / R3) — run-scoped retrieval of the parked manual step's input bundle. Unlike
   * {@link #getContextBundleForArtifact(String)} (which walks artifact → producing runner-execution
   * → bundle), a parked run has produced NO artifact yet, so this resolves the run's single active
   * {@code awaiting_manual} {@code runner_executions} row and reads its persisted, ALREADY-REDACTED
   * bundle bytes from the scratch store. The bytes are {@code SHAREABLE_REDACTED} — never
   * recomposed, never re-egressed unredacted (ADR 0025 posture, same as 3d-5's finished-log read).
   *
   * <p>Throws {@link DomainErrorCode#RUN_NOT_FOUND} for an unknown run and {@link
   * DomainErrorCode#MANUAL_EXECUTION_NOT_APPLICABLE} when the run has no parked {@code
   * awaiting_manual} execution (the wrong-state gate — it is not in {@code
   * WaitingForManualExecution}). Returns an {@code unavailable("bundleNotPersisted")} result (NOT a
   * 500) when the row exists but the scratch file has been evicted (scratch is not durable).
   */
  @Transactional(readOnly = true)
  public ManualBundleLookupResult getManualBundle(String workflowRunId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      workflowRunReadPort
          .findByPublicId(workflowRunId)
          .orElseThrow(() -> runNotFound(workflowRunId));
      List<RunnerExecutionSnapshot> parked =
          runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
              workflowRunId, List.of(RunnerExecutionStatus.AWAITING_MANUAL));
      if (parked.isEmpty()) {
        log.warn("getManualBundle reject workflowRunId={} reason=noParkedExecution", workflowRunId);
        throw manualExecutionNotApplicable(workflowRunId);
      }
      if (parked.size() > 1) {
        // Invariant breach: 3d-3 parks exactly one awaiting_manual row per run; serving an
        // arbitrary row's bundle would be non-deterministic. Fail loud (mirrors the submission
        // path's resolveParkedRow guard).
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("parkedRowCount", parked.size());
        log.error(
            "getManualBundle ambiguous parked rows workflowRunId={} count={}",
            workflowRunId,
            parked.size());
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "Multiple awaiting_manual runner executions for the run; cannot resolve a single parked"
                + " row",
            details);
      }
      RunnerExecutionSnapshot row = parked.get(0);
      String runnerExecutionId = row.publicId();
      Optional<byte[]> scratchBytes = runnerScratchStore.tryReadContextBundle(runnerExecutionId);
      if (scratchBytes.isPresent() && scratchBytes.get().length > 0) {
        ContextBundle bundle =
            new ContextBundle(
                workflowRunId,
                row.stage(),
                runnerExecutionId,
                row.contextBundleVersion(),
                DataClassification.SHAREABLE_REDACTED,
                scratchBytes.get());
        log.info(
            "manual bundle retrieved workflowRunId={} runnerExecutionId={} bundleByteLength={}",
            workflowRunId,
            runnerExecutionId,
            bundle.redactedPayload().length);
        return ManualBundleLookupResult.available(workflowRunId, bundle);
      }
      log.warn(
          "getManualBundle unavailable workflowRunId={} runnerExecutionId={} reason=bundleNotPersisted",
          workflowRunId,
          runnerExecutionId);
      return ManualBundleLookupResult.unavailable(
          workflowRunId, runnerExecutionId, "bundleNotPersisted");
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private static DomainException manualExecutionNotApplicable(String workflowRunId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("reason", "no_parked_manual_execution");
    return new DomainException(
        DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE,
        "Manual execution is not applicable: run is not parked in WaitingForManualExecution: "
            + workflowRunId,
        details);
  }

  /**
   * Story 3.6 AC7 — typed inspection of a runner execution's durable redacted log store. Mirrors
   * {@link #getContextBundleForArtifact(String)}: validates the {@code rex_} prefix, opens an MDC
   * scope, reads the persisted {@code raw_output_*} columns off the {@code runner_executions} row
   * (via the already-injected {@link RunnerExecutionRecordPort#findByPublicId(String)}), and
   * returns an available/unavailable result. NEVER renders log <em>content</em> — only the
   * content-free {@link RunnerLogReference} (path + byteSize + classification + redactionCount).
   *
   * <p>Unavailable reasons: {@code runnerExecutionNotFound} (no row) and {@code logsNotCaptured}
   * (row exists but the capture columns are null — honest "not captured", e.g. mock runner or a
   * pre-3.6 execution).
   */
  /**
   * Story 3.6 AC7 / OQ-4 — resolve the most-recently-created runner execution for a workflow run so
   * {@code status --include-runner-logs} can pick which {@code rex} to render. Returns {@link
   * Optional#empty()} when the run has no runner executions yet. {@link #getRunnerLogReference}
   * stays {@code rex}-scoped; this is only the run→rex lookup.
   */
  @Transactional(readOnly = true)
  public Optional<String> findLatestRunnerExecutionId(String workflowRunId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    return runnerExecutionRecordPort
        .findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, List.of(org.dradgo.domain.registry.RunnerExecutionStatus.values()))
        .stream()
        .max(
            java.util.Comparator.comparing(
                RunnerExecutionSnapshot::createdAt,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
        .map(RunnerExecutionSnapshot::publicId);
  }

  @Transactional(readOnly = true)
  public RunnerLogReferenceResult getRunnerLogReference(String runnerExecutionId) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      log.info("getRunnerLogReference entry runnerExecutionId={}", runnerExecutionId);
      Optional<RunnerExecutionSnapshot> rex =
          runnerExecutionRecordPort.findByPublicId(runnerExecutionId);
      if (rex.isEmpty()) {
        log.warn(
            "getRunnerLogReference miss runnerExecutionId={} reason=runnerExecutionNotFound",
            runnerExecutionId);
        return RunnerLogReferenceResult.unavailable(runnerExecutionId, "runnerExecutionNotFound");
      }
      RunnerExecutionSnapshot snapshot = rex.get();
      boolean noneCaptured =
          snapshot.rawOutputReference() == null
              && snapshot.rawOutputClassification() == null
              && snapshot.rawOutputByteSize() == null
              && snapshot.redactionCount() == null;
      if (noneCaptured) {
        log.warn(
            "getRunnerLogReference miss runnerExecutionId={} reason=logsNotCaptured",
            runnerExecutionId);
        return RunnerLogReferenceResult.unavailable(runnerExecutionId, "logsNotCaptured");
      }
      if (snapshot.rawOutputReference() == null
          || snapshot.rawOutputClassification() == null
          || snapshot.rawOutputByteSize() == null
          || snapshot.redactionCount() == null) {
        log.warn(
            "getRunnerLogReference miss runnerExecutionId={} reason=incompleteLogMetadata",
            runnerExecutionId);
        return RunnerLogReferenceResult.unavailable(runnerExecutionId, "incompleteLogMetadata");
      }
      RunnerLogReference reference =
          new RunnerLogReference(
              snapshot.rawOutputReference(),
              snapshot.rawOutputByteSize(),
              snapshot.rawOutputClassification(),
              snapshot.redactionCount());
      log.info(
          "getRunnerLogReference success runnerExecutionId={} classification={} redactionCount={} byteSize={}",
          runnerExecutionId,
          reference.classification().value(),
          reference.redactionCount(),
          reference.byteSize());
      return RunnerLogReferenceResult.available(runnerExecutionId, reference);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
    }
  }

  private static String specVersionKey(String artifactId, int version) {
    return artifactId + ":" + version;
  }

  private static DomainException duplicateSpecDecision(
      String workflowRunPublicId, ApprovalSnapshot prior, ApprovalSnapshot duplicate) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", workflowRunPublicId);
    details.put("artifactId", duplicate.artifactId());
    details.put("artifactVersion", duplicate.artifactVersion());
    details.put("priorApprovalId", prior.publicId());
    details.put("duplicateApprovalId", duplicate.publicId());
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Duplicate approvals found for spec version "
            + duplicate.artifactVersion()
            + " of artifact "
            + duplicate.artifactId(),
        details);
  }

  /**
   * Materialize the full event stream for a run in the committed wire shape (story 6.9 Task 2,
   * AC1/AC2). Backs {@code GET /api/v1/workflows/{workflowRunId}/events} and is pinned against
   * {@code fixture-event-streams/schema/workflow-events-response.schema.json}.
   *
   * <p>Sourced from {@link WorkflowEventRecord} (which carries {@code workflowRunPublicId}), NOT
   * from {@link WorkflowEventView} (which drops it and uses a different top-level shape). Event
   * {@code details} are wire-sanitized: server-only keys (e.g. {@code idempotencyKey}) are stripped
   * and values run through the redaction policy — but unlike CLI {@code history} the open-map keys
   * the schema/UI need (e.g. {@code artifactVariant}) are preserved.
   *
   * @param workflowRunPublicId the {@code run_}-prefixed run id (prefix-validated first)
   * @return the schema-shaped run header + ordered event list
   */
  @Transactional(readOnly = true)
  public WorkflowEventStreamView getEventStream(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
    try {
      log.info("inspecting workflow_run event stream workflowRunId={}", workflowRunPublicId);
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunPublicId)
              .orElseThrow(() -> runNotFound(workflowRunPublicId));

      List<WorkflowEventRecord> events =
          workflowEventReadPort.listByWorkflowRunPublicId(run.publicId(), null);
      if (events.isEmpty()) {
        throw invalidEventStream(workflowRunPublicId, "missing event history");
      }

      List<WorkflowEventStreamItem> items = new ArrayList<>(events.size());
      for (WorkflowEventRecord event : events) {
        items.add(
            new WorkflowEventStreamItem(
                event.publicId(),
                event.workflowRunPublicId(),
                event.eventType().value(),
                event.priorState() == null ? null : event.priorState().value(),
                event.resultingState() == null ? null : event.resultingState().value(),
                event.actorIdentity(),
                event.actorType().value(),
                event.reason(),
                event.failureCategory() == null ? null : event.failureCategory().value(),
                event.interventionMarker(),
                event.createdAt(),
                sanitizeWireDetails(event.details())));
      }

      // workflowRun.createdAt = the moment the run's event stream began (the submit event), which
      // the committed fixtures equate to the run's createdAt. Every governed run has a submit
      // event, so events is non-empty in practice; guard defensively for the empty edge.
      OffsetDateTime runCreatedAt = events.get(0).createdAt();
      String ticketRef = resolveTicketRef(workflowRunPublicId, events);
      if (ticketRef == null || ticketRef.isBlank()) {
        throw invalidEventStream(workflowRunPublicId, "missing ticket reference");
      }

      WorkflowRunHeaderView header =
          new WorkflowRunHeaderView(
              run.publicId(), ticketRef, runCreatedAt, run.currentState().value());
      log.info(
          "inspecting workflow_run event stream success workflowRunId={} eventCount={}",
          workflowRunPublicId,
          items.size());
      return new WorkflowEventStreamView(header, List.copyOf(items));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Resolve the run's ticket reference for the events wire header: the active integration link's
   * external ref is authoritative; fall back to the earliest event detail carrying {@code
   * linearTicketReference} (every run is submitted with a {@code @NotBlank} ticket reference, so
   * one of these is always present).
   */
  private String resolveTicketRef(String workflowRunPublicId, List<WorkflowEventRecord> events) {
    Optional<String> linkRef =
        integrationLinkService
            .findActiveLinkByWorkflowRun(workflowRunPublicId)
            .map(IntegrationLink::externalRef);
    if (linkRef.isPresent()) {
      return linkRef.get();
    }
    for (WorkflowEventRecord event : events) {
      Object ref =
          event.details() == null
              ? null
              : event.details().get(WorkflowEventDetailKeys.LINEAR_TICKET_REFERENCE);
      if (ref instanceof String text && !text.isBlank()) {
        return text;
      }
    }
    return null;
  }

  /**
   * Wire sanitization for {@code GET /events} event {@code details} (story 6.9 Task 2): strip
   * server-only keys ({@link WorkflowEventDetailKeys#SERVER_ONLY_KEYS} — notably {@code
   * idempotencyKey}, which the schema forbids) then run a value-level redaction pass. Unlike the
   * CLI {@code history} allow-list this preserves open-map keys the schema/UI consume (e.g. {@code
   * artifactVariant}).
   */
  private Map<String, Object> sanitizeWireDetails(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> stripped = new LinkedHashMap<>(raw);
    for (String serverOnlyKey : WorkflowEventDetailKeys.SERVER_ONLY_KEYS) {
      stripped.remove(serverOnlyKey);
    }
    return redactDetails(stripped);
  }

  /**
   * Allow-listed keys that may flow from {@code workflow_events.details} into a rendered CLI
   * payload (story 1-15 Task 4). Everything else is dropped before render. Sourced from {@link
   * WorkflowEventDetailKeys#ALLOW_LISTED_KEYS} — the single source of truth shared with {@code
   * RecoveryService} (producer), {@code workflow-history.v1.schema.json} (transport contract), and
   * {@code WorkflowEventRepository} (PostgreSQL-native filter). Story 1.18 review batch 3 (D1)
   * centralized the keys.
   *
   * <p>{@code idempotencyKey} is intentionally omitted — it is operator-only and surfaced on stdout
   * by {@code submit}/{@code retry}, never echoed through {@code status} or {@code history}. {@link
   * WorkflowEventDetailKeys#SERVER_ONLY_KEYS} lists the stripped set.
   */
  static final List<String> ALLOWED_DETAIL_KEYS = WorkflowEventDetailKeys.ALLOW_LISTED_KEYS;

  private Map<String, Object> redactDetails(Map<String, Object> filtered) {
    // Defense-in-depth: the allow-list above strips dangerous KEYS, but a caller could still
    // have written a secret VALUE into a permitted key (e.g. an operator pasting a token into
    // the ticket reference text). Run the filtered payload through the redaction policy as a
    // second gate. Per the architecture rule "redaction runs both when data is captured and
    // when data is exported", this is the export-side pass.
    if (filtered.isEmpty()) {
      return filtered;
    }
    RedactionResult result =
        redactionPolicyService.redact(filtered, DataClassification.SHAREABLE_REDACTED.value());
    JsonNode sanitized = result.sanitizedJson();
    if (sanitized == null || !sanitized.isObject()) {
      return filtered;
    }
    Map<String, Object> rebuilt = new LinkedHashMap<>();
    sanitized
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode value = entry.getValue();
              if (value.isNull()) {
                rebuilt.put(entry.getKey(), null);
              } else if (value.isBoolean()) {
                rebuilt.put(entry.getKey(), value.booleanValue());
              } else if (value.isNumber()) {
                // Preserve numeric type instead of coercing to a String — schemas pin
                // `artifactVersion` / `contextVersion` as integers, and a future writer that
                // hands us a Double, BigInteger, or BigDecimal would otherwise produce a JSON
                // string that fails `workflow-history.v1` validation.
                rebuilt.put(entry.getKey(), value.numberValue());
              } else {
                rebuilt.put(entry.getKey(), value.asText());
              }
            });
    return rebuilt;
  }

  private static Map<String, Object> filterAllowedDetails(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> filtered = new LinkedHashMap<>();
    for (String key : ALLOWED_DETAIL_KEYS) {
      Object value = raw.get(key);
      if (value != null) {
        filtered.put(key, value);
      }
    }
    return filtered;
  }

  private static LinkedTicketView toLinkedTicket(IntegrationLink link) {
    return new LinkedTicketView(
        link.integrationType(), link.externalRef(), link.syncStatus().value());
  }

  private static DomainException runNotFound(String workflowRunPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunPublicId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunPublicId, details);
  }

  private static DomainException invalidEventStream(String workflowRunPublicId, String reason) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunPublicId);
    details.put("reason", reason);
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Workflow event stream is invalid for " + workflowRunPublicId + ": " + reason,
        details);
  }

  /**
   * Application-facing status snapshot. Transport adapters render this view.
   *
   * <p>The five {@code failed*} / {@code last*Activity*} fields (story 1.18) are non-null only when
   * {@code currentState == Failed}; on non-Failed runs they are all null and {@code nextSafeAction}
   * reflects the canonical non-Failed value.
   */
  public record WorkflowStatusView(
      String workflowRunId,
      WorkflowState currentState,
      String currentActorIdentity,
      String currentActorType,
      String lastEventType,
      OffsetDateTime lastEventAt,
      List<LatestArtifactView> latestArtifacts,
      LinkedTicketView linkedTicket,
      String failedStage,
      String lastSuccessfulStage,
      OffsetDateTime failureTimestamp,
      String failureCategory,
      OffsetDateTime lastActivityTimestamp,
      String nextSafeAction,
      // Story 2.10 — spec rejection loop tracking surfaced to the inspection consumer.
      int specRejectionLoopCount,
      boolean escalationMarker) {}

  public record LatestArtifactView(
      String artifactType, int version, String status, String artifactId) {

    /**
     * Convenience constructor for callers that do not surface the artifact public id (the CLI
     * status/history renderer projects only {@code artifactType}/{@code version}/{@code status} —
     * see {@code WorkflowCommandOutputs}). Production REST construction always passes the real
     * {@code snapshot.publicId()} so {@code WorkflowDetail.latestArtifacts[].artifactId} resolves
     * the spec approval bar (story 2.19) and the artifact-read endpoint (story 3a-9).
     */
    public LatestArtifactView(String artifactType, int version, String status) {
      this(artifactType, version, status, null);
    }
  }

  /**
   * Story 3a-9 (Gate 3) read view for a single artifact's content. {@code body} is the persisted,
   * already-redacted payload decoded as UTF-8 markdown; {@code checksumShortForm} is {@code
   * <algorithm>:<first 12 hex>} or {@code null} when the artifact has no checksum yet.
   */
  public record ArtifactDetailView(
      String artifactId,
      String artifactType,
      int version,
      String status,
      String classification,
      OffsetDateTime createdAt,
      String checksumShortForm,
      String body,
      // Story 3b-5 — structured prOutput fields (all null for spec/implementationPlan). branch /
      // commitSha / diff are parsed from the stored prOutput payload JSON; prReference / prState
      // are
      // sourced co-presently from the active github_pr integration link (both null ⇒ no linked PR).
      String branch,
      String commitSha,
      String prReference,
      String prState,
      String diff,
      // Story 3b-6 — the implementationPlan ordered steps, parsed from the stored payload JSON at
      // read time; null for spec/prOutput (never co-populated with the prOutput fields above).
      List<String> steps) {}

  public record LinkedTicketView(String integrationType, String externalRef, String syncStatus) {}

  public record WorkflowHistoryView(String workflowRunId, List<WorkflowEventView> events) {}

  public record WorkflowEventView(
      String publicId,
      String eventType,
      String priorState,
      String resultingState,
      String actorIdentity,
      String actorType,
      String reason,
      String failureCategory,
      boolean interventionMarker,
      OffsetDateTime createdAt,
      Map<String, Object> details) {}

  /**
   * Lean per-run summary for the queue/list surface ({@code GET /api/v1/workflows}, story 6.9).
   * Just enough for the queue UI (story 2.15) to render a row and link to detail.
   */
  public record WorkflowRunSummaryView(
      String workflowRunId,
      String currentState,
      String ticketRef,
      OffsetDateTime lastEventAt,
      String lastEventType,
      // Story 2.10 — spec rejection loop tracking surfaced on the queue surface so the UI can
      // render a "loop depth N" badge / escalation badge without a second per-row lookup.
      int specRejectionLoopCount,
      boolean escalationMarker,
      // Story 2.12 — non-terminal clarification count surfaced on the queue surface; story 2.14
      // gates approve_spec on this count == 0. N+1 in listRuns accepted for MVP queue scale
      // (typical < 50 rows) — see OQ-4 + Trap T12.
      int pendingClarifications,
      // Story 3d-8 (FR67, AC5) — the soft-hide marker (null = live), surfaced on the queue summary
      // so WorkflowSummaryResponse can render an archived/hidden badge without a second lookup.
      OffsetDateTime archivedAt) {}

  /**
   * Story 2.12 AC9: richer per-run summary returned by {@link #getRunSummary(String)}. Carries
   * everything {@link WorkflowRunSummaryView} carries plus is dedicated to single-run inspection
   * (story 2.14 reads this directly).
   */
  /**
   * Story 2.14 — typed allowed-actions view returned by {@link #getAllowedActions(String, String)}.
   * Carries the action list plus a version stamp the UI Approval/Decision Bar (story 2.19) echoes
   * back on subsequent mutations so the existing {@code expectedArtifactVersion} + {@code
   * expectedContextBundleVersion} checks on approve/reject surface {@code
   * APPROVAL_VERSION_MISMATCH} if the UI is stale.
   */
  public record AllowedActionsView(
      List<AllowedAction> actions, AllowedActionsVersionStamp versionStamp) {}

  /**
   * Story 2.14 — version stamp composed from the run state, the LATEST spec artifact (any approval
   * status — see TRAP 2 in the story spec), the linked context-bundle version, and the latest event
   * id. The three integer/string fields are nullable: a run with no spec yet returns {@code
   * currentSpecArtifactVersion = null}, {@code currentContextBundleVersion = null}; a spec produced
   * via the CLI/seed path with no linked runner execution returns {@code
   * currentContextBundleVersion = null}; an unreachable event-less run returns {@code lastEventId =
   * null}.
   */
  public record AllowedActionsVersionStamp(
      String workflowState,
      Integer currentSpecArtifactVersion,
      Integer currentContextBundleVersion,
      String lastEventId) {}

  /**
   * Story 3.20 (FR21 / OQ-2) — minimal product/technical acceptance state. {@code APPROVED} iff a
   * latest-approved row exists for the relevant artifact type(s); {@code NONE} otherwise. Kept
   * localized so a future Decision Bar (story 3.28) widening to {@code PENDING}/{@code REJECTED} is
   * a one-file change.
   */
  public enum RunApprovalState {
    NONE,
    APPROVED
  }

  /**
   * Story 3.20 (AC4 / Trap T8): {@code productApprovalState} + {@code technicalApprovalState} are
   * the FR21 separate acceptance states, appended at the END of the record. They are read
   * internally (allowed-actions + tests today); REST/CLI/UI surfacing is deferred to stories
   * 3.23/3.28/3.31 per the 2.12 {@code pendingClarifications} precedent, so {@code
   * WorkflowSummaryResponse} /{@code WorkflowStatusView} are intentionally NOT widened.
   */
  public record WorkflowRunDetailedSummaryView(
      String workflowRunId,
      String currentState,
      String ticketRef,
      OffsetDateTime lastEventAt,
      String lastEventType,
      int specRejectionLoopCount,
      boolean escalationMarker,
      int pendingClarifications,
      String productApprovalState,
      String technicalApprovalState,
      // Story 3.22 (AC8): takeover attribution (FR19 reconstruction — who/when/why). Populated ONLY
      // when currentState == TAKEN_OVER, else null. Application-internal home; REST/CLI/UI
      // surfacing
      // is deferred to 3.25/3.28/3.29 (Trap T8, mirrors productApprovalState above). NOT widened
      // onto WorkflowSummaryResponse/WorkflowStatusView — OpenApiSnapshotContractTest stays green.
      TakeoverAttribution takenOverBy,
      OffsetDateTime takenOverAt,
      String takenOverReason,
      // Story 3d-8 (FR67, AC3/AC4) — the soft-hide marker (null = live). Drives the archive_run vs
      // unarchive_run affordance in computeActionMatrix; never archive-filtered on the by-id read.
      OffsetDateTime archivedAt) {}

  /**
   * Story 3.22 (AC8): the developer who took over a run — {@code actor_identity}, {@code
   * actor_type}, and the takeover-invariant {@code reviewer_role='developer'}. Sourced from the
   * {@code workflow.stateChanged → TakenOver} event (the takeover transition's actor); {@code
   * reviewerRole} is the constant invariant (Trap T2: it is not carried on {@code
   * TakeoverWorkflowCommand}).
   */
  public record TakeoverAttribution(String actorIdentity, String actorType, String reviewerRole) {}

  /**
   * Story 2.12 AC6 / AC7: V9-rich clarification status used by {@link
   * #getClarificationStatus(String, String)}. UI Clarification Region (story 2.18) consumes this
   * for the per-question lifecycle indicator. Trap T1 — built from {@code
   * ClarificationLifecycleSnapshot} read projection; {@link Clarification} stays lean.
   */
  public record ClarificationStatusView(
      String clarificationId,
      String workflowRunId,
      String artifactId,
      int artifactVersion,
      String questionId,
      String questionText,
      String status,
      String answerText,
      String answeredByActor,
      String answeredByActorType,
      OffsetDateTime answeredAt,
      OffsetDateTime acceptedAt,
      OffsetDateTime incorporatedAt,
      String incorporatedIntoArtifactId,
      String supersededByArtifactId,
      String noEffectReason,
      OffsetDateTime createdAt) {}

  /**
   * Schema-shaped event-stream view ({@code GET /api/v1/workflows/{id}/events}, story 6.9). Mirrors
   * {@code workflow-events-response.schema.json}: a run header object plus the ordered event list.
   */
  public record WorkflowEventStreamView(
      WorkflowRunHeaderView workflowRun, List<WorkflowEventStreamItem> events) {}

  /**
   * Run header of the event-stream wire shape: {@code {publicId, ticketRef, createdAt,
   * terminalState}} (story 6.9). {@code terminalState} is the run's current state — the state at
   * the end of the returned stream.
   */
  public record WorkflowRunHeaderView(
      String publicId, String ticketRef, OffsetDateTime createdAt, String terminalState) {}

  /**
   * One event in the wire shape (story 6.9). Distinct from {@link WorkflowEventView}: it carries
   * {@code workflowRunPublicId} (required by the committed schema) and its {@code details} preserve
   * open-map keys (e.g. {@code artifactVariant}) after server-only-key stripping + value redaction.
   */
  public record WorkflowEventStreamItem(
      String publicId,
      String workflowRunPublicId,
      String eventType,
      String priorState,
      String resultingState,
      String actorIdentity,
      String actorType,
      String reason,
      String failureCategory,
      boolean interventionMarker,
      OffsetDateTime createdAt,
      Map<String, Object> details) {}

  /**
   * Story 2.8 AC9: one row of the spec history projection. {@code decision} is one of {@code
   * "approved"}, {@code "rejected"}, or {@code "pending"} (no approval row yet). {@code
   * reviewerRole}, {@code rejectionTaxonomy}, and {@code decidedAt} are non-null only when the
   * version carries a decision row; {@code rejectionTaxonomy} is non-null only when {@code decision
   * == "rejected"} (DB CHECK pairing).
   */
  public record SpecHistoryEntry(
      SpecificationArtifact spec,
      String decision,
      String reviewerRole,
      String rejectionTaxonomy,
      OffsetDateTime decidedAt) {}

  /**
   * Story 2.11 AC9: clarification view used by both {@link #getClarifications(String)} and {@link
   * #getClarificationsForArtifact(String)}. {@code answerText} / {@code answeredByActor} / {@code
   * answeredByActorType} / {@code answeredAt} are non-null only when {@code status != "open"}
   * (paired with the DB CHECK invariant).
   */
  public record ClarificationView(
      String clarificationId,
      String workflowRunId,
      String artifactId,
      int artifactVersion,
      String questionId,
      String questionText,
      String status,
      String answerText,
      String answeredByActor,
      String answeredByActorType,
      OffsetDateTime answeredAt,
      OffsetDateTime createdAt) {}

  public record ContextBundleLookupResult(String artifactId, ContextBundle bundle, String reason) {

    public ContextBundleLookupResult {
      Objects.requireNonNull(artifactId, "artifactId");
      if ((bundle == null) == (reason == null)) {
        throw new IllegalArgumentException(
            "Exactly one of bundle or reason must be set on ContextBundleLookupResult");
      }
    }

    public static ContextBundleLookupResult available(String artifactId, ContextBundle bundle) {
      return new ContextBundleLookupResult(
          artifactId, Objects.requireNonNull(bundle, "bundle"), null);
    }

    public static ContextBundleLookupResult unavailable(String artifactId, String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      return new ContextBundleLookupResult(artifactId, null, reason);
    }

    public boolean available() {
      return bundle != null;
    }
  }

  /**
   * Story 3d-4 (AC1 / R3) — run-scoped available/unavailable result for {@link
   * #getManualBundle(String)}. Mirrors {@link ContextBundleLookupResult} but is keyed by the
   * workflow run (a parked run has produced no artifact yet). Exactly one of {@code bundle} /
   * {@code reason} is set; {@code runnerExecutionId} is the parked execution's id (always present —
   * the row exists in both the available and the {@code bundleNotPersisted} states).
   */
  public record ManualBundleLookupResult(
      String workflowRunId, String runnerExecutionId, ContextBundle bundle, String reason) {

    public ManualBundleLookupResult {
      Objects.requireNonNull(workflowRunId, "workflowRunId");
      Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
      if ((bundle == null) == (reason == null)) {
        throw new IllegalArgumentException(
            "Exactly one of bundle or reason must be set on ManualBundleLookupResult");
      }
    }

    public static ManualBundleLookupResult available(String workflowRunId, ContextBundle bundle) {
      Objects.requireNonNull(bundle, "bundle");
      return new ManualBundleLookupResult(workflowRunId, bundle.runnerExecutionId(), bundle, null);
    }

    public static ManualBundleLookupResult unavailable(
        String workflowRunId, String runnerExecutionId, String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      return new ManualBundleLookupResult(workflowRunId, runnerExecutionId, null, reason);
    }

    public boolean available() {
      return bundle != null;
    }

    /**
     * Story 3d-4 review — flattened accessor so the REST DTO ({@code ManualBundleResponse}) never
     * reaches through this result into {@code application.runner.ContextBundle} (the
     * thin-controller ArchUnit boundary forbids REST adapters from depending on {@code
     * application.runner..}). Null when the bundle is unavailable.
     */
    public Integer contextBundleVersion() {
      return bundle == null ? null : bundle.contextBundleVersion();
    }

    /**
     * Story 3d-4 review — flattened accessor for the {@code SHAREABLE_REDACTED} bundle bytes, same
     * boundary rationale as {@link #contextBundleVersion()}. Null when the bundle is unavailable.
     */
    public byte[] redactedPayload() {
      return bundle == null ? null : bundle.redactedPayload();
    }
  }

  /**
   * Story 3.6 AC7 — available/unavailable result for {@link #getRunnerLogReference(String)},
   * mirroring {@link ContextBundleLookupResult}. Exactly one of {@code reference} / {@code reason}
   * is set. The reference carries metrics ONLY (never log content, never a secret value).
   */
  public record RunnerLogReferenceResult(
      String runnerExecutionId, RunnerLogReference reference, String reason) {

    public RunnerLogReferenceResult {
      if (reference != null) {
        Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
      }
      if ((reference == null) == (reason == null)) {
        throw new IllegalArgumentException(
            "Exactly one of reference or reason must be set on RunnerLogReferenceResult");
      }
    }

    public static RunnerLogReferenceResult available(
        String runnerExecutionId, RunnerLogReference reference) {
      return new RunnerLogReferenceResult(
          runnerExecutionId, Objects.requireNonNull(reference, "reference"), null);
    }

    public static RunnerLogReferenceResult unavailable(String runnerExecutionId, String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      return new RunnerLogReferenceResult(runnerExecutionId, null, reason);
    }

    public boolean available() {
      return reference != null;
    }
  }

  /**
   * Story 3.19 (AC1/AC3) — typed runner-queue + worker-pool inspection view. Defined HERE (nested
   * in {@code application.workflow}), NOT under {@code application.runner}, because the ArchUnit
   * rule {@code REST_CONTROLLERS_STAY_THIN_AND_AVOID_SPI_OR_PERSISTENCE_OR_RUNNER} forbids {@code
   * adapters.rest}/{@code adapters.cli} from depending on {@code org.dradgo.application.runner..} —
   * so the transport adapters can consume this view only if it lives in {@code
   * application.workflow} (Reconciliation 1). AC10 pins reference to this type to {@link
   * WorkflowInspectionService}, the REST {@code RunnerQueueStatusResponse}, and the CLI {@code
   * workers status} surface.
   *
   * <ul>
   *   <li>{@code poolSize} — configured worker count ({@link RunnerWorkerPoolProperties#size()});
   *       global even under a {@code batchId} filter.
   *   <li>{@code activeWorkers} / {@code inFlightExecutions} — leased running rows (the two are
   *       equal by construction).
   *   <li>{@code idleWorkers} — {@code max(0, poolSize − activeWorkers)}.
   *   <li>{@code queueDepth} — rows in {@code status='queued'}.
   *   <li>{@code oldestQueuedAt} / {@code oldestQueuedAgeSeconds} — oldest queued row + its
   *       server-computed age in seconds (null/0 when the queue is empty).
   *   <li>{@code recentThroughputPerMinute} — completions in the last 60s.
   *   <li>{@code staleQueuedCount} / {@code staleDispatchedCount} — queued/leased rows past the
   *       lease window (AC3).
   *   <li>{@code workers} — one {@link WorkerStatus} per leased running row (the BUSY workers).
   * </ul>
   */
  public record RunnerQueueStatus(
      int poolSize,
      long activeWorkers,
      long idleWorkers,
      long queueDepth,
      OffsetDateTime oldestQueuedAt,
      long oldestQueuedAgeSeconds,
      long inFlightExecutions,
      long recentThroughputPerMinute,
      long staleQueuedCount,
      long staleDispatchedCount,
      List<WorkerStatus> workers) {

    public RunnerQueueStatus {
      workers = workers == null ? List.of() : List.copyOf(workers);
    }
  }

  /**
   * Story 3.19 (AC1) — per-worker state, reconstructed from a leased running {@code
   * runner_executions} row (the worker pool keeps no in-memory roster — Reconciliation 6). {@code
   * state} is {@code "busy"} for every entry the view emits (only leased workers are enumerable).
   * The optional fields carry the current work; they are non-null for a busy worker.
   */
  public record WorkerStatus(
      String workerId,
      String state,
      String currentRunnerExecutionId,
      String currentWorkflowRunId,
      OffsetDateTime dispatchedAt,
      String currentStage) {}
}
