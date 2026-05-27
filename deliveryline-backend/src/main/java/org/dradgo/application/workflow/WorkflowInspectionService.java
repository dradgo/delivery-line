package org.dradgo.application.workflow;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.SpecificationArtifact;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.FailureDescription;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.ContextBundle;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
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
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
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
  private final ApprovalReadPort approvalReadPort;
  private final IntegrationLinkService integrationLinkService;
  private final RedactionPolicyService redactionPolicyService;
  private final RecoveryService recoveryService;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final RunnerScratchStore runnerScratchStore;
  private final ClarificationReadPort clarificationReadPort;

  public WorkflowInspectionService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      ArtifactRecordPort artifactRecordPort,
      ApprovalReadPort approvalReadPort,
      IntegrationLinkService integrationLinkService,
      RedactionPolicyService redactionPolicyService,
      RecoveryService recoveryService,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      RunnerScratchStore runnerScratchStore,
      ClarificationReadPort clarificationReadPort) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
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
      WorkflowRunDetailedSummaryView view =
          new WorkflowRunDetailedSummaryView(
              run.publicId(),
              run.currentState().value(),
              ticketRef,
              latest.map(WorkflowEventRecord::createdAt).orElse(null),
              latest.map(record -> record.eventType().value()).orElse(null),
              run.specRejectionLoopCount(),
              run.escalationMarkerSet(),
              pending);
      log.info(
          "getRunSummary success workflowRunId={} pendingClarifications={} currentState={}",
          workflowRunPublicId,
          pending,
          view.currentState());
      return view;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
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

  static final Set<String> RECOGNIZED_ACTOR_ROLES =
      Set.of(ROLE_PRODUCT_REVIEWER, ROLE_WORKFLOW_OWNER);

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

      // TRAP 2: use the LATEST spec artifact (any status), NOT the last approved one. The UI's
      // version stamp must match what the reviewer currently sees in the artifact panel; deriving
      // from the approved spec would drift on reject-and-resubmit loops.
      Optional<ArtifactRecordSnapshot> latestSpecOpt =
          artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
              workflowRunPublicId, ArtifactType.SPEC.value());
      Integer specVersion = latestSpecOpt.map(ArtifactRecordSnapshot::version).orElse(null);
      String latestSpecPublicId = latestSpecOpt.map(ArtifactRecordSnapshot::publicId).orElse(null);

      Integer bundleVersion = null;
      if (latestSpecPublicId != null) {
        ContextBundleLookupResult lookup = getContextBundleLookupForArtifact(latestSpecPublicId);
        if (lookup.available()) {
          bundleVersion = lookup.bundle().contextBundleVersion();
        }
      }

      String latestEventId =
          workflowEventReadPort
              .findLatestByWorkflowRunPublicId(workflowRunPublicId)
              .map(WorkflowEventRecord::publicId)
              .orElse(null);

      WorkflowState state = WorkflowState.fromValue(summary.currentState(), "currentState");
      List<AllowedAction> actions =
          computeActionMatrix(
              state, resolvedRole, summary.pendingClarifications(), latestSpecPublicId);

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

  private List<AllowedAction> computeActionMatrix(
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
          if (ROLE_PRODUCT_REVIEWER.equals(actorRole)) {
            // AC4: drop approve_spec when pending clarifications block approval (story 2.12 gate).
            if (pendingClarifications > 0) {
              return List.of(AllowedAction.REJECT_SPEC, AllowedAction.ANSWER_CLARIFICATION);
            }
            return List.of(
                AllowedAction.APPROVE_SPEC,
                AllowedAction.REJECT_SPEC,
                AllowedAction.ANSWER_CLARIFICATION);
          }
          return List.of(AllowedAction.VIEW_ONLY, AllowedAction.ANSWER_CLARIFICATION);
        }
      case EXECUTING:
        return List.of(AllowedAction.VIEW_ONLY, AllowedAction.AWAIT_OUTCOME);
      case WAITING_FOR_REVIEW:
        // SEAM (Epic 3/4): Epic 3 adds approve_implementation / reject_implementation / takeover
        // for the developer-review actor here. MVP returns view_only for all roles.
        return List.of(AllowedAction.VIEW_ONLY);
      case COMPLETED:
        return List.of(AllowedAction.VIEW_ONLY);
      case FAILED:
        {
          if (ROLE_WORKFLOW_OWNER.equals(actorRole)) {
            return List.of(AllowedAction.RETRY, AllowedAction.VIEW_DIAGNOSTICS);
          }
          return List.of(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_DIAGNOSTICS);
        }
      case PAUSED:
        // SEAM (Epic 4): Epic 4 adds resume here for workflow_owner.
        return List.of(AllowedAction.VIEW_ONLY, AllowedAction.VIEW_DIAGNOSTICS);
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
                        snapshot.status().value())));
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
  public List<WorkflowRunSummaryView> listRuns(WorkflowState stateFilter, int limit) {
    int capped = Math.min(Math.max(limit, 1), MAX_LIST_PAGE_SIZE);
    log.info(
        "listing workflow_runs stateFilter={} limit={}",
        stateFilter == null ? "<all>" : stateFilter.value(),
        capped);
    List<WorkflowRunSnapshot> runs = workflowRunReadPort.listRuns(stateFilter, capped);
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
              pendingClarifications));
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

  public record LatestArtifactView(String artifactType, int version, String status) {}

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
      int pendingClarifications) {}

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

  public record WorkflowRunDetailedSummaryView(
      String workflowRunId,
      String currentState,
      String ticketRef,
      OffsetDateTime lastEventAt,
      String lastEventType,
      int specRejectionLoopCount,
      boolean escalationMarker,
      int pendingClarifications) {}

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
}
