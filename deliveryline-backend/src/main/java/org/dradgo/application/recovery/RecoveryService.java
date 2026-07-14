package org.dradgo.application.recovery;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.ConflictIntegrationTypes;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.RunnerDispatchResult;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionCancellation;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.commands.PauseWorkflowCommand;
import org.dradgo.application.workflow.commands.ReconcileWorkflowCommand;
import org.dradgo.application.workflow.commands.RerunFromStepWorkflowCommand;
import org.dradgo.application.workflow.commands.ResumeWorkflowCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.spi.FailureClassificationRecord;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunFailureClassificationPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.FailureTaxonomyValue;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.ReconciliationDecision;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.SafeRerunStep;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for CLI minimum-viable recovery (story 1.18 baseline).
 *
 * <p><strong>Scope governed by ADR 0033 (was ArchUnit scope-protected):</strong> today this service
 * exposes {@link #retry(String, String, ActorContext, String)}, {@link #resume(String, String,
 * ActorContext, String)} (story 4.5), {@link #reconcile(String, String, String, ActorContext,
 * String, String)} (story 4.6), {@link #rerunFromStep(String, String, String, ActorContext,
 * String)} (story 4.7), {@link #pause(String, String, ActorContext, String)} (story 4.8), {@link
 * #classifyFailure(String, String, String, ActorContext, String)} (story 4.9), and {@link
 * #describeFailure(String)}. The former ArchUnit tripwire {@code
 * RECOVERY_SERVICE_IS_SCOPE_PROTECTED} (installed by story 1.18 to keep the deeper recovery surface
 * out until Epic 4 justified it) was <strong>lifted by story 4.28</strong> — see {@code
 * docs/adr/0033-recovery-service-scope-lift.md}. The set of recovery methods allowed to land on
 * this service is now governed by that ADR's "what new scope is now allowed" list rather than by a
 * build-breaking rule; adding a method outside that list requires a new ADR (or updating 0033).
 *
 * <p>The ADR 0033 (c) allow-list is now fully landed on this service. {@code takeover(...)} lives
 * on the sibling {@code DeveloperTakeoverService}, which remains ArchUnit scope-protected.
 *
 * <p><strong>Idempotency-key namespacing:</strong> the user-supplied {@code idempotencyKey} flows
 * through three idempotency surfaces — {@link WorkflowCommandService#retryWorkflow} (workflow state
 * transition), {@link org.dradgo.application.runner.queue.RunnerExecutionQueue#enqueue} (runner
 * dispatch — story 3.17b), and {@link RecoveryActionRecordPort} (recovery_actions row). To prevent
 * collisions on the broker's fingerprint check ({@code workflowRunId|stage|contextBundleVersion}),
 * the broker key is derived by appending a stable {@code :runner} suffix. The recovery-action key
 * is the bare key.
 *
 * <p><strong>State-mutation invariant:</strong> all workflow state changes route through {@link
 * WorkflowCommandService#retryWorkflow} (which calls {@code WorkflowTransitionService}). The
 * ArchUnit rule {@code ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE} forbids any
 * other path; this service never calls {@code WorkflowRunStatePort.updateCurrentState} directly.
 *
 * <p><strong>Concurrent-retry contract (review F532 + batch-3 B16/A3 + B7/E16):</strong> two
 * retries with the same idempotency key racing past the pre-insert replay check both attempt the
 * prep transaction; the loser sees {@code IDEMPOTENCY_KEY_CONFLICT} from the {@code
 * recovery_actions} unique constraint (or from the inner {@code
 * WorkflowCommandService.retryWorkflow} idempotency record). On that conflict we re-read the prior
 * {@code recovery_actions} row: if it is now bound to the same run AND already {@code
 * result_status='succeeded'} AND carries a non-null {@code resulting_event_id}, we return the
 * documented replay {@link RetryRecoveryResult} instead of surfacing the conflict. Pending / failed
 * prior attempts on the same run propagate the conflict unchanged (matches the same-run,
 * non-succeeded contract from review F2). Different-run prior rows also propagate. Missing or
 * null-event-id rows propagate (defensive — the FK is {@code ON DELETE SET NULL}; a link-less row
 * cannot back a replay).
 *
 * <p><strong>Ordering invariant.</strong> Inside the prep transaction the call sequence is {@code
 * workflowCommandService.retryWorkflow} → {@code workflowEventWritePort.append} → {@code
 * recoveryActionRecordPort.insert}. The inner {@code retryWorkflow} writes its {@code
 * idempotency_records} row first, so if thread B's conflict comes from that surface (rather than
 * the {@code recovery_actions} unique constraint), thread A's {@code recovery_actions} insert has
 * not happened yet — thread B's re-read sees {@code Optional.empty()} and propagates the conflict
 * by design. Operators who want a guaranteed replay should retry the call AFTER the original
 * returns; the conflict surface is a best-effort fast-path for already-completed prior attempts,
 * not a synchronization primitive.
 *
 * <p><strong>Correlation-id echo on replay.</strong> The returned {@link RetryRecoveryResult}
 * carries the CURRENT call's {@code actor.correlationId()}, not the original winning attempt's
 * stored correlation id. Audit-trace joins by correlation id may therefore split across two ids:
 * the current call's id reaches the CLI stdout / structured log, while the persisted {@code
 * recovery.retried} event row carries the winning attempt's id. This is intentional — the current
 * call's id is what the operator's tooling already attached to the request and what structured logs
 * in the loser's call carry. To find the originating attempt's id, follow the returned {@code
 * recoveryRetriedEventPublicId} through the event store.
 *
 * <p><strong>Terminal-result-status contract (review F526):</strong> a successful {@code retry}
 * result implies the {@code recovery_actions} row has reached {@code result_status='succeeded'}
 * before the method returns. If the post-dispatch {@code markSucceeded} flip fails, the row stays
 * at {@code pending} and the runner is already running — we refuse to lie about audit terminality
 * and raise {@code INTERNAL_ERROR} carrying the new-runner id, recovery-action id, and root cause
 * so the operator can reconcile. On the dispatch-failure path the symmetric guarantee is provided
 * by the {@code recovery.dispatchFailed} audit event (it carries {@code compensationFailed=true}
 * when the {@code markFailed} compensation itself fails).
 *
 * <p><strong>Degraded-state contract (review F1 + dispatch-failure audit):</strong> the {@code
 * retry} flow runs the workflow-state transition, the {@code recovery.retried} event append, and
 * the {@code recovery_actions} insert inside a single {@code REQUIRES_NEW} transaction template
 * that commits BEFORE {@link org.dradgo.application.runner.queue.RunnerExecutionQueue#enqueue} is
 * invoked. If the enqueue then fails, two fresh {@code REQUIRES_NEW} transactions run: (1) the
 * {@code recovery_actions} row is flipped to {@code result_status = failed}; (2) a {@link
 * WorkflowEventType#RECOVERY_DISPATCH_FAILED recovery.dispatchFailed} event is appended carrying
 * {@code errorCode}, {@code errorClass}, {@code failedStage}, {@code recoveryActionId}, {@code
 * recoveryRetriedEventId}, {@code idempotencyKey}, and (when present) {@code correlationId} and
 * {@code reason}. The append-only invariant (NFR4) is preserved — the already-committed {@code
 * recovery.retried} event is never mutated; the typed broker error reaches the audit trail through
 * this second append. Failures appending the dispatch-failed event are suppressed onto the original
 * broker error so the caller still sees the real cause. The workflow stays in {@code Executing}
 * without a running runner; the next retry call (or the broker's startup-recovery scan) can correct
 * the dangling state.
 */
@Service
public class RecoveryService {

  private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

  // Story 3.17b — default dequeue priority for recovery-driven enqueues (matches the V12
  // queue_priority column default). Recovery does not jump the queue; that is story 3.18's concern.
  private static final int RECOVERY_QUEUE_PRIORITY = 100;

  static final String NEXT_SAFE_ACTION_RETRY = "retry";
  static final String NEXT_SAFE_ACTION_AWAIT_OUTCOME = "await_outcome";
  static final String NEXT_SAFE_ACTION_VIEW_ONLY = "view_only";
  static final String NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION = "await_manual_reconciliation";

  static final String ACTION_TYPE_RETRY = "retry";
  // Story 4.5 — resume recovery-action constants. reviewer_role='workflow_owner' is the resume
  // invariant applied when building the recovery_actions row (9-arg ctor), NOT a public param
  // (audit-only label; RBAC deferred).
  static final String ACTION_TYPE_RESUME = "resume";
  static final String ACTION_TYPE_RECONCILE = "reconcile";
  // Story 4.7 — the pre-reserved V1 recovery_actions CHECK slot for rerun-from-step. The literal
  // 'rerun_from_step' reconciles to 'rerun' (the finer semantic lives in the recovery.rerunFromStep
  // event + details.targetStep — no recovery_actions.details column, mirror 4.5/4.6).
  static final String ACTION_TYPE_RERUN = "rerun";
  // Story 4.8 — the pre-reserved V1 recovery_actions CHECK slot for manual pause (no migration).
  static final String ACTION_TYPE_PAUSE = "pause";
  // Story 4.9 — NOT a pre-reserved V1 slot (contrast rerun/pause): the V44 migration widened
  // ck_recovery_actions_action_type with this value.
  static final String ACTION_TYPE_CLASSIFY_FAILURE = "classify_failure";
  static final String INVALIDATED_REASON_RERUN = "superseded_by_rerun_from_step";
  static final String REVIEWER_ROLE_WORKFLOW_OWNER = "workflow_owner";
  // Story 4.18 (AC4 / Reconciliation 3) — the reviewer_role stamped on the recovery_actions row of
  // a SYSTEM-actor auto-pause (the story-4.17 conflict sweep's ConflictAutoPauseHandler passes
  // ActorContext.SYSTEM). recovery_actions.reviewer_role is free `text not null` (V1:176, no
  // CHECK),
  // so no migration/registry change is needed; deriving it from the actor lets the audit trail
  // distinguish an operator pause (workflow_owner) from an auto-pause (system).
  static final String REVIEWER_ROLE_SYSTEM = "system";
  static final String RESULT_STATUS_PENDING = "pending";
  static final String RESULT_STATUS_SUCCEEDED = "succeeded";

  private static final List<RunnerExecutionStatus> FAILED_RUNNER_STATUSES =
      List.of(
          RunnerExecutionStatus.FAILED,
          RunnerExecutionStatus.TIMED_OUT,
          RunnerExecutionStatus.ORPHANED);

  private static final List<RunnerExecutionStatus> ALL_RUNNER_STATUSES =
      List.of(
          RunnerExecutionStatus.PENDING,
          RunnerExecutionStatus.RUNNING,
          RunnerExecutionStatus.COMPLETED,
          RunnerExecutionStatus.FAILED,
          RunnerExecutionStatus.TIMED_OUT,
          RunnerExecutionStatus.ORPHANED);

  private static final List<WorkflowState> TERMINAL_STATES =
      List.of(WorkflowState.COMPLETED, WorkflowState.TAKEN_OVER, WorkflowState.RECONCILED);

  // Story 4.7 [Review 2026-07-12] — the ONLY states from which a rerun-from-step may be launched.
  // Mirrors the AC10 allowed-actions gate (RERUN_FROM_STEP is offered only at FAILED +
  // WAITING_FOR_REVIEW) so the service enforces the same source boundary the read-model advertises.
  // Without this, the transition table's broader legal edges (e.g. WAITING_FOR_SPEC_APPROVAL →
  // EXECUTING/INVESTIGATING) would let a non-UI caller rerun from a state that destroys a live PM
  // approval — the exact case ADR-0034 scopes out. Executor-as-gate precedent (retry: != FAILED;
  // resume: != PAUSED).
  private static final List<WorkflowState> RERUN_SOURCE_STATES =
      List.of(WorkflowState.FAILED, WorkflowState.WAITING_FOR_REVIEW);

  // Story 4.8 (AC3 / Reconciliation 1) — the EXPLICIT allow-list of pause source states, NOT the
  // epic's literal "any non-terminal state" and deliberately NOT derived by subtracting
  // TERMINAL_STATES (terminal-subtraction would silently re-admit the four excluded states below).
  // INBOX / PLANNED / SPLIT / WAITING_FOR_DEPENDENCIES are EXCLUDED because an autonomous driver
  // owns their out-edge and pausing them silently consumes a one-shot trigger
  // (RunDependencyReleaseService fires WaitingForDependencies → Investigating exactly once; the
  // 3f-7 rollup drives Split → Completed) — the run would wedge permanently. FAILED is MANDATORY:
  // 4.4's RecommendationService already advertises `pause` as a safe action on Failed runs.
  // Executor-as-gate current-state precondition (retry: != FAILED; resume: != PAUSED); every
  // member has a symmetric → PAUSED / PAUSED → return edge pair in WorkflowTransitionTable
  // (PauseTransitionSymmetryTest pins the invariant).
  static final Set<WorkflowState> PAUSABLE_SOURCE_STATES =
      Collections.unmodifiableSet(
          EnumSet.of(
              WorkflowState.INVESTIGATING,
              WorkflowState.WAITING_FOR_SPEC_APPROVAL,
              WorkflowState.EXECUTING,
              WorkflowState.WAITING_FOR_REVIEW,
              WorkflowState.WAITING_FOR_MANUAL_EXECUTION,
              WorkflowState.WAITING_FOR_LINT_APPROVAL,
              WorkflowState.WAITING_FOR_DELIVERY,
              WorkflowState.FAILED));

  // Story 4.8 (AC4 / Reconciliation 5) — the dispatch-visible statuses pause scans and flips to
  // cancelled_for_pause. Deliberately NARROWER than takeover's CANCEL_SCAN_STATUSES: an
  // AWAITING_MANUAL parked row is NOT cancelled (pause is reversible; a cancelled manual park has
  // no re-park path on resume, and the parked row is already invisible to the dequeuer and to
  // ACTIVE_STATUSES). Resume from priorState=WaitingForManualExecution finds its park intact.
  private static final List<RunnerExecutionStatus> PAUSE_CANCEL_SCAN_STATUSES =
      List.of(
          RunnerExecutionStatus.QUEUED,
          RunnerExecutionStatus.PENDING,
          RunnerExecutionStatus.RUNNING);

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final RunnerExecutionRecordPort runnerExecutionRecordPort;
  private final ArtifactOperationPort artifactOperationPort;
  private final WorkflowCommandService workflowCommandService;
  // Story 3.17b (AC3/D1) — recovery retry now enqueues onto the RunnerExecutionQueue (the worker
  // pool runs the real dispatch later) instead of calling RunnerBroker.dispatch synchronously.
  private final org.dradgo.application.runner.queue.RunnerExecutionQueue runnerExecutionQueue;
  // Story 3d-3 (AC1/AC3/AC6) — the recovery enqueue site is the second dispatch chokepoint. It
  // resolves the run's runner kind and, when `manual`, parks instead of enqueuing (no container, no
  // queue row). Both collaborators are always-present application @Components (no DI cycle).
  private final org.dradgo.application.project.ProjectRuntimeConfigResolver
      projectRuntimeConfigResolver;
  private final org.dradgo.application.runner.ManualExecutionDispatcher manualExecutionDispatcher;
  // Story 4.5 (AC6) — resume re-dispatches the EXECUTION-stage runner via the sub-stage-aware
  // redispatchAfterRetry (never transitions; no-op returning null when auto-dispatch is off). No DI
  // cycle: WorkflowOrchestrationService does not depend on RecoveryService.
  private final WorkflowOrchestrationService workflowOrchestrationService;
  private final IntegrationLinkService integrationLinkService;
  private final IntegrationConflictService integrationConflictService;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final RecoveryActionRecordPort recoveryActionRecordPort;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  // Story 4.7 — approval invalidation (routed through the approval package) + active-leaf artifact
  // reads for the rerun audit detail. Both nullable in the resume/retry-only test constructor.
  private final ApprovalService approvalService;
  private final ArtifactRecordPort artifactRecordPort;
  // Story 4.7 [Review D1] — dispatch-failure compensation drives the run to FAILED directly (mirror
  // RunnerBroker.driveWorkflowFailed) so a rerun whose post-commit re-enqueue fails stays
  // recoverable. Nullable in the resume/reconcile-only test constructors; rerunFromStep guards
  // non-null at call time (like approvalService/artifactRecordPort).
  private final WorkflowTransitionService workflowTransitionService;
  // Story 4.8 (AC4) — pause's post-commit best-effort `docker stop` for previously-RUNNING rows
  // (cancel never throws; DeveloperTakeoverService injects the same port). Nullable in the
  // pre-pause
  // test constructors; pause guards non-null at call time (like approvalService).
  private final RunnerAdapter runnerAdapter;
  // Story 4.9 — classify's targeted workflow_runs column writer (the columns are deliberately
  // unmapped on WorkflowRunEntity; see the port's javadoc). Nullable in the pre-classify test
  // constructors; classifyFailure guards non-null at call time (like runnerAdapter).
  private final WorkflowRunFailureClassificationPort workflowRunFailureClassificationPort;
  private final Clock clock;
  private final TransactionTemplate retryPrepTransactionTemplate;
  private final TransactionTemplate resultStatusTransactionTemplate;

  @Autowired
  public RecoveryService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      ArtifactOperationPort artifactOperationPort,
      WorkflowCommandService workflowCommandService,
      org.dradgo.application.runner.queue.RunnerExecutionQueue runnerExecutionQueue,
      org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      org.dradgo.application.runner.ManualExecutionDispatcher manualExecutionDispatcher,
      WorkflowOrchestrationService workflowOrchestrationService,
      IntegrationLinkService integrationLinkService,
      WorkflowEventWritePort workflowEventWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IntegrationConflictService integrationConflictService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      ApprovalService approvalService,
      ArtifactRecordPort artifactRecordPort,
      WorkflowTransitionService workflowTransitionService,
      RunnerAdapter runnerAdapter,
      WorkflowRunFailureClassificationPort workflowRunFailureClassificationPort,
      PlatformTransactionManager transactionManager) {
    this(
        workflowRunReadPort,
        workflowEventReadPort,
        runnerExecutionRecordPort,
        artifactOperationPort,
        workflowCommandService,
        runnerExecutionQueue,
        projectRuntimeConfigResolver,
        manualExecutionDispatcher,
        workflowOrchestrationService,
        integrationLinkService,
        workflowEventWritePort,
        recoveryActionRecordPort,
        integrationConflictService,
        idempotencyKeyValidator,
        Clock.systemUTC(),
        requiresNewTemplate(transactionManager),
        requiresNewTemplate(transactionManager),
        approvalService,
        artifactRecordPort,
        workflowTransitionService,
        runnerAdapter,
        workflowRunFailureClassificationPort);
  }

  RecoveryService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      ArtifactOperationPort artifactOperationPort,
      WorkflowCommandService workflowCommandService,
      org.dradgo.application.runner.queue.RunnerExecutionQueue runnerExecutionQueue,
      org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      org.dradgo.application.runner.ManualExecutionDispatcher manualExecutionDispatcher,
      WorkflowOrchestrationService workflowOrchestrationService,
      WorkflowEventWritePort workflowEventWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IdempotencyKeyValidator idempotencyKeyValidator,
      Clock clock,
      TransactionTemplate retryPrepTransactionTemplate,
      TransactionTemplate resultStatusTransactionTemplate,
      ApprovalService approvalService,
      ArtifactRecordPort artifactRecordPort,
      WorkflowTransitionService workflowTransitionService) {
    this(
        workflowRunReadPort,
        workflowEventReadPort,
        runnerExecutionRecordPort,
        artifactOperationPort,
        workflowCommandService,
        runnerExecutionQueue,
        projectRuntimeConfigResolver,
        manualExecutionDispatcher,
        workflowOrchestrationService,
        null,
        workflowEventWritePort,
        recoveryActionRecordPort,
        null,
        idempotencyKeyValidator,
        clock,
        retryPrepTransactionTemplate,
        resultStatusTransactionTemplate,
        approvalService,
        artifactRecordPort,
        workflowTransitionService,
        null,
        null);
  }

  RecoveryService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowEventReadPort workflowEventReadPort,
      RunnerExecutionRecordPort runnerExecutionRecordPort,
      ArtifactOperationPort artifactOperationPort,
      WorkflowCommandService workflowCommandService,
      org.dradgo.application.runner.queue.RunnerExecutionQueue runnerExecutionQueue,
      org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver,
      org.dradgo.application.runner.ManualExecutionDispatcher manualExecutionDispatcher,
      WorkflowOrchestrationService workflowOrchestrationService,
      IntegrationLinkService integrationLinkService,
      WorkflowEventWritePort workflowEventWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      IntegrationConflictService integrationConflictService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      Clock clock,
      TransactionTemplate retryPrepTransactionTemplate,
      TransactionTemplate resultStatusTransactionTemplate,
      ApprovalService approvalService,
      ArtifactRecordPort artifactRecordPort,
      WorkflowTransitionService workflowTransitionService,
      RunnerAdapter runnerAdapter,
      WorkflowRunFailureClassificationPort workflowRunFailureClassificationPort) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowEventReadPort =
        Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
    this.runnerExecutionRecordPort =
        Objects.requireNonNull(runnerExecutionRecordPort, "runnerExecutionRecordPort");
    this.artifactOperationPort =
        Objects.requireNonNull(artifactOperationPort, "artifactOperationPort");
    this.workflowCommandService =
        Objects.requireNonNull(workflowCommandService, "workflowCommandService");
    this.runnerExecutionQueue =
        Objects.requireNonNull(runnerExecutionQueue, "runnerExecutionQueue");
    this.projectRuntimeConfigResolver =
        Objects.requireNonNull(projectRuntimeConfigResolver, "projectRuntimeConfigResolver");
    this.manualExecutionDispatcher =
        Objects.requireNonNull(manualExecutionDispatcher, "manualExecutionDispatcher");
    this.workflowOrchestrationService =
        Objects.requireNonNull(workflowOrchestrationService, "workflowOrchestrationService");
    this.integrationLinkService = integrationLinkService;
    this.integrationConflictService = integrationConflictService;
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.recoveryActionRecordPort =
        Objects.requireNonNull(recoveryActionRecordPort, "recoveryActionRecordPort");
    this.idempotencyKeyValidator =
        Objects.requireNonNull(idempotencyKeyValidator, "idempotencyKeyValidator");
    // Nullable: the resume/retry-only test constructor does not exercise rerunFromStep, so it
    // passes null for both. rerunFromStep guards non-null at call time (like reconcile guards
    // integrationConflictService).
    this.approvalService = approvalService;
    this.artifactRecordPort = artifactRecordPort;
    this.workflowTransitionService = workflowTransitionService;
    // Nullable: the pre-pause test constructors pass null; pause guards non-null at call time.
    this.runnerAdapter = runnerAdapter;
    // Nullable: the pre-classify test constructors pass null; classifyFailure guards non-null at
    // call time.
    this.workflowRunFailureClassificationPort = workflowRunFailureClassificationPort;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.retryPrepTransactionTemplate =
        Objects.requireNonNull(retryPrepTransactionTemplate, "retryPrepTransactionTemplate");
    this.resultStatusTransactionTemplate =
        Objects.requireNonNull(resultStatusTransactionTemplate, "resultStatusTransactionTemplate");
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  public RetryRecoveryResult retry(
      String workflowRunId, String idempotencyKey, ActorContext actor, String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery retry start workflowRunId={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Step 1 — replay detection. Treat a prior recovery_actions row as a replay only when it
      // belongs to the SAME run AND already reached the `succeeded` terminal. A row for a
      // different run, or a prior `pending` / `failed` attempt on this run, surfaces as
      // IDEMPOTENCY_KEY_CONFLICT so the operator either picks a fresh key or rolls back through
      // the documented degraded-state path (review F2).
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!workflowRunId.equals(prior.workflowRunPublicId())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("requestedRunId", workflowRunId);
          details.put("priorRunId", prior.workflowRunPublicId());
          details.put("reason", "idempotency_key_bound_to_different_run");
          log.warn(
              "recovery retry rejected workflowRunId={} reason=idempotency_key_bound_to_different_run priorRunId={}",
              workflowRunId,
              prior.workflowRunPublicId());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key is already bound to a different workflow run (priorRunId="
                  + prior.workflowRunPublicId()
                  + ")",
              details);
        }
        if (!RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("runId", workflowRunId);
          details.put("priorResultStatus", prior.resultStatus());
          details.put("reason", "idempotency_key_used_by_non_succeeded_attempt");
          log.warn(
              "recovery retry rejected workflowRunId={} reason=idempotency_key_used_by_non_succeeded_attempt priorResultStatus={}",
              workflowRunId,
              prior.resultStatus());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key was used by a prior non-succeeded recovery attempt (resultStatus="
                  + prior.resultStatus()
                  + "); use a fresh key for a new attempt",
              details);
        }
        log.info(
            "recovery retry replay workflowRunId={} recoveryActionId={} idempotencyKey={}",
            workflowRunId,
            prior.publicId(),
            validatedIdempotencyKey);
        return new RetryRecoveryResult(
            prior.publicId(),
            prior.resultingEventPublicId(),
            null,
            null,
            actor.correlationId(),
            true);
      }

      // Step 2 — current-state guard. retry is only meaningful from Failed. Read run snapshot in
      // the prep tx below to keep the read coherent with the transition; this check happens here
      // to short-circuit before consuming any state.
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      if (run.currentState() != WorkflowState.FAILED) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", run.currentState().value());
        log.warn(
            "recovery retry rejected workflowRunId={} currentState={} reason=not_failed",
            workflowRunId,
            run.currentState().value());
        throw new DomainException(
            DomainErrorCode.RETRY_NOT_APPLICABLE,
            "Retry is only applicable to runs in state Failed; current state is "
                + run.currentState().value(),
            details);
      }

      // Step 3 — locate the failure event. AC2/AC9 require triggering_event_id to link the audit
      // trail to the workflow.stateChanged → Failed event; a Failed run without that event row
      // can only have arrived there through a malformed fixture, and we refuse to invent a
      // link-less audit entry (review F3).
      WorkflowEventRecord failureEvent =
          workflowEventReadPort
              .findLatestFailureEvent(workflowRunId)
              .orElseThrow(
                  () -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("runId", workflowRunId);
                    details.put("reason", "no_failure_event_to_link");
                    return new DomainException(
                        DomainErrorCode.RETRY_NOT_APPLICABLE,
                        "Retry requires a workflow.stateChanged → Failed event to link the audit trail; "
                            + "none found for run "
                            + workflowRunId,
                        details);
                  });

      // Step 4 — locate the most recent failed runner_execution to know which stage to redispatch.
      RunnerExecutionSnapshot lastFailedRunner =
          findLastFailedRunner(workflowRunId)
              .orElseThrow(
                  () -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("runId", workflowRunId);
                    details.put("reason", "no_failed_runner_execution_to_redispatch");
                    return new DomainException(
                        DomainErrorCode.RETRY_NOT_APPLICABLE,
                        "Retry requires a prior failed runner_execution to identify the stage to redispatch; "
                            + "none found for run "
                            + workflowRunId,
                        details);
                  });
      RunnerStage failedStage = lastFailedRunner.stage();

      // Step 5 — prep work runs inside a REQUIRES_NEW transaction so the audit trail (state
      // transition + recovery.retried event + recovery_actions row) is durable BEFORE the broker
      // dispatch is attempted. If the broker then fails, the recovery_actions row survives and
      // gets flipped to `failed` in a fresh tx so operators can see the attempt in audit history
      // (review F1).
      String correlationId = actor.correlationId();
      String effectiveReason = resolveReasonText(reasonText, failedStage);
      RetryPrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performRetryPrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        failedStage,
                        failureEvent,
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        // Concurrent-retry race (review F532): the pre-insert replay check at the top of this
        // method saw an empty result, but a sibling thread won the recovery_actions insert
        // (or the inner WorkflowCommandService.retryWorkflow's idempotency record) between
        // our check and our prep tx — surfacing IDEMPOTENCY_KEY_CONFLICT. Re-read the prior
        // row; if it is now bound to the same run AND already succeeded, return the documented
        // replay shape rather than punishing the loser with an idempotency conflict. Pending
        // or failed prior attempts on this run propagate the conflict (the same-run, non-
        // succeeded contract from review F2). Different-run prior rows propagate the conflict
        // unchanged (F2 again).
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<RetryRecoveryResult> raceReplay =
              resolveConcurrentReplay(workflowRunId, validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      // Step 6 — enqueue the runner OUTSIDE any transaction so the audit trail above is durable
      // before any side effects (story 3.17b OQ-4: enqueue opens its own REQUIRED tx + afterCommit
      // NOTIFY here). Namespace the broker key with ":runner" so the worker's idempotency_records
      // row cannot collide with the recovery action's idempotency_records row.
      String dispatchKey = validatedIdempotencyKey + ":runner";
      // Story 3d-3 (AC1/AC3/AC6) — resolve the run's runner kind at the recovery chokepoint. When
      // `manual`, park (compose+write bundle, awaiting_manual row, transition) instead of enqueuing
      // a container; the manual.executionRequested event id is the retry audit anchor (replacing
      // the
      // runner.queued anchor). A default project resolves the global per-stage kind ⇒ the unchanged
      // enqueue path (R7 parity). Unlike orchestration, recovery enqueues OUTSIDE a tx by design;
      // ManualExecutionDispatcher.park is @Transactional, so it opens its own tx here.
      String newRunnerExecutionId;
      String runnerDispatchedEventPublicId;
      try {
        org.dradgo.domain.registry.RunnerKind kind =
            projectRuntimeConfigResolver.resolveRunnerKind(workflowRunId, failedStage);
        if (kind == org.dradgo.domain.registry.RunnerKind.MANUAL) {
          org.dradgo.application.runner.RunnerDispatchResult.Parked parked =
              (org.dradgo.application.runner.RunnerDispatchResult.Parked)
                  manualExecutionDispatcher.park(workflowRunId, failedStage, dispatchKey, actor);
          newRunnerExecutionId = parked.handle().runnerExecutionId();
          runnerDispatchedEventPublicId = parked.manualEventPublicId();
        } else {
          org.dradgo.application.runner.queue.QueuedRunnerExecution dispatchResult =
              runnerExecutionQueue.enqueue(
                  workflowRunId, failedStage, dispatchKey, actor, RECOVERY_QUEUE_PRIORITY);
          newRunnerExecutionId = dispatchResult.runnerExecutionPublicId();
          runnerDispatchedEventPublicId = dispatchResult.queuedEventPublicId();
        }
      } catch (RuntimeException error) {
        boolean compensationFailed = false;
        try {
          resultStatusTransactionTemplate.executeWithoutResult(
              status -> recoveryActionRecordPort.markFailed(validatedIdempotencyKey));
        } catch (RuntimeException compensationError) {
          compensationFailed = true;
          error.addSuppressed(compensationError);
        }
        // Append-only audit: stamp the typed broker error into a second event so operators
        // reading `history` see the dispatch failure. Mutating the already-committed
        // `recovery.retried` event would violate NFR4; instead we add `recovery.dispatchFailed`
        // in its own REQUIRES_NEW tx so the audit row survives even when the outer caller
        // unwinds. Failures appending the event are suppressed onto the broker error so the
        // CLI surface still reports the real cause.
        try {
          appendDispatchFailedEvent(
              workflowRunId,
              failedStage,
              prep,
              validatedIdempotencyKey,
              correlationId,
              effectiveReason,
              actor,
              error,
              compensationFailed);
        } catch (RuntimeException auditError) {
          error.addSuppressed(auditError);
          // Audit-event store outage on the dispatch-failure path: the recovery_actions row
          // is flipped (or compensationFailed), the broker error is re-thrown, but the
          // `recovery.dispatchFailed` event never landed — the `history` CLI surface cannot
          // show this attempt. Escalate to ERROR with EVERY audit field stamped into the
          // structured log so operator log-scraping can reconstruct the missing event.
          // (review D2 / 2026-05-16)
          String dispatchErrorCode =
              (error instanceof DomainException d) ? d.errorCode().value() : "RUNTIME_ERROR";
          log.error(
              "recovery retry dispatch-failed event append failed workflowRunId={} "
                  + "recoveryActionId={} recoveryRetriedEventId={} idempotencyKey={} "
                  + "failedStage={} errorCode={} errorClass={} correlationId={} "
                  + "compensationFailed={} auditErrorClass={} — audit event missing; "
                  + "structured log is the only audit trail for this attempt",
              workflowRunId,
              prep.recoveryActionPublicId,
              prep.recoveryRetriedEventPublicId,
              validatedIdempotencyKey,
              failedStage.value(),
              dispatchErrorCode,
              error.getClass().getSimpleName(),
              correlationId,
              compensationFailed,
              auditError.getClass().getSimpleName());
        }
        log.warn(
            "recovery retry dispatch failed workflowRunId={} runnerStage={} errorClass={} — workflow state stays Executing without a runner; operator action required",
            workflowRunId,
            failedStage.value(),
            error.getClass().getSimpleName());
        throw error;
      }

      // Story 3.17b — the retry now ENQUEUES; the real adapter dispatch (and its runner.dispatched
      // event) happens later on a worker thread. The runner.queued event is the retry audit anchor
      // for the new enqueued execution (replaces the former runner.dispatched anchor, story 3.2a
      // AC10 / Trap T13, which is no longer produced synchronously here). Story 3d-3 — for a
      // `manual`
      // kind the anchor is the manual.executionRequested event id instead. Both are resolved into
      // newRunnerExecutionId / runnerDispatchedEventPublicId above.
      try {
        resultStatusTransactionTemplate.executeWithoutResult(
            status -> recoveryActionRecordPort.markSucceeded(validatedIdempotencyKey));
      } catch (RuntimeException completionError) {
        // Story 1.18 AC9 requires recovery_actions.result_status to reach a terminal value
        // (succeeded | failed) — leaving the row at `pending` while reporting CLI success
        // would lie about the audit state. Surface a typed INTERNAL_ERROR so operators see
        // the inconsistency and the CLI exit code reflects the degraded state; the runner is
        // already dispatched, so the suppressed completionError carries the root cause for
        // triage. (review F526)
        log.error(
            "recovery retry completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} newRunnerExecutionId={} errorClass={} — runner dispatched but recovery_actions row stays pending; operator reconciliation required",
            workflowRunId,
            prep.recoveryActionPublicId,
            validatedIdempotencyKey,
            newRunnerExecutionId,
            completionError.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("recoveryActionId", prep.recoveryActionPublicId);
        details.put("idempotencyKey", validatedIdempotencyKey);
        details.put("newRunnerExecutionId", newRunnerExecutionId);
        details.put("reason", "result_status_flip_failed_after_dispatch");
        DomainException terminal =
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR,
                "Runner dispatched but recovery_actions.result_status flip to succeeded failed; "
                    + "row remains pending. Operator reconciliation required.",
                details);
        terminal.addSuppressed(completionError);
        throw terminal;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery retry success workflowRunId={} recoveryActionId={} newRunnerExecutionId={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId,
          newRunnerExecutionId,
          elapsedMs);
      return new RetryRecoveryResult(
          prep.recoveryActionPublicId,
          prep.recoveryRetriedEventPublicId,
          runnerDispatchedEventPublicId,
          newRunnerExecutionId,
          correlationId,
          false);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 4.5 — resume a paused run back to its prior executing state. Structural twin of {@link
   * #retry}: MDC scope → idempotency validate → replay pre-check → current-state guard ({@code
   * PAUSED}) → locate the {@code → Paused} transition event → REQUIRES_NEW prep tx (transition via
   * {@link WorkflowCommandService#resumeWorkflow} + append {@code recovery.resumed} + insert {@code
   * recovery_actions} row) → post-commit re-dispatch (single-sourced here — see the double-dispatch
   * caution) with compensation → {@code markSucceeded} → return. Differs from retry in: the target
   * state comes from the recovered {@code priorState} (not hardcoded {@code Executing}); the
   * linking event is the {@code → Paused} transition (not the failure event); the event type is
   * {@code recovery.resumed}; the {@code recovery_actions} row carries {@code
   * reviewerRole='workflow_owner'} (9-arg ctor); and re-dispatch uses the sub-stage-aware {@code
   * redispatchAfterRetry}.
   */
  public ResumeRecoveryResult resume(
      String workflowRunId, String idempotencyKey, ActorContext actor, String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery resume start workflowRunId={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Step 1 — replay detection (mirror retry Step 1). A prior recovery_actions row is a replay
      // only when it belongs to the SAME run AND already reached `succeeded`. Different run, or a
      // prior pending/failed attempt on this run, surfaces as IDEMPOTENCY_KEY_CONFLICT.
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        // The idempotency key is not action-namespaced, so a prior row may belong to a different
        // recovery action (retry/takeover) on this run. Guard here exactly as
        // resolveConcurrentResumeReplay does — a cross-action key reuse is a conflict, never a
        // resume replay (otherwise we would echo the prior action's row/event id as if resume ran).
        if (!ACTION_TYPE_RESUME.equals(prior.actionType())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("runId", workflowRunId);
          details.put("priorActionType", prior.actionType());
          details.put("reason", "idempotency_key_bound_to_different_action");
          log.warn(
              "recovery resume rejected workflowRunId={} reason=idempotency_key_bound_to_different_action priorActionType={}",
              workflowRunId,
              prior.actionType());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key is already bound to a different recovery action (priorActionType="
                  + prior.actionType()
                  + ")",
              details);
        }
        if (!workflowRunId.equals(prior.workflowRunPublicId())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("requestedRunId", workflowRunId);
          details.put("priorRunId", prior.workflowRunPublicId());
          details.put("reason", "idempotency_key_bound_to_different_run");
          log.warn(
              "recovery resume rejected workflowRunId={} reason=idempotency_key_bound_to_different_run priorRunId={}",
              workflowRunId,
              prior.workflowRunPublicId());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key is already bound to a different workflow run (priorRunId="
                  + prior.workflowRunPublicId()
                  + ")",
              details);
        }
        if (!RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("idempotencyKey", validatedIdempotencyKey);
          details.put("runId", workflowRunId);
          details.put("priorResultStatus", prior.resultStatus());
          details.put("reason", "idempotency_key_used_by_non_succeeded_attempt");
          log.warn(
              "recovery resume rejected workflowRunId={} reason=idempotency_key_used_by_non_succeeded_attempt priorResultStatus={}",
              workflowRunId,
              prior.resultStatus());
          throw new DomainException(
              DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
              "Idempotency key was used by a prior non-succeeded recovery attempt (resultStatus="
                  + prior.resultStatus()
                  + "); use a fresh key for a new attempt",
              details);
        }
        log.info(
            "recovery resume replay workflowRunId={} recoveryActionId={} idempotencyKey={}",
            workflowRunId,
            prior.publicId(),
            validatedIdempotencyKey);
        return new ResumeRecoveryResult(
            prior.publicId(),
            prior.resultingEventPublicId(),
            null,
            resolvePriorExecutingStateForReplay(workflowRunId).orElse(null),
            actor.correlationId(),
            true);
      }

      // Step 2 — current-state guard. resume is only meaningful from Paused (AC3).
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      if (run.currentState() != WorkflowState.PAUSED) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", run.currentState().value());
        details.put("reason", "not_paused");
        log.warn(
            "recovery resume rejected workflowRunId={} currentState={} reason=not_paused",
            workflowRunId,
            run.currentState().value());
        throw new DomainException(
            DomainErrorCode.RESUME_NOT_APPLICABLE,
            "Resume is only applicable to runs in state Paused; current state is "
                + run.currentState().value(),
            details);
      }

      // Step 3 — locate the WORKFLOW_STATE_CHANGED → Paused event (Reconciliation 1). Its TYPED
      // priorState() is the recovered prior executing state to resume into, and its publicId() is
      // the triggering_event_id that links the audit trail. A Paused run without that event can
      // only
      // have arrived there through a malformed fixture; we refuse to invent a link-less audit entry
      // (mirror how retry raises RETRY_NOT_APPLICABLE when its failure event is missing).
      WorkflowEventRecord pausedEvent =
          workflowEventReadPort
              .findLatestTransitionToState(workflowRunId, WorkflowState.PAUSED)
              .orElseThrow(
                  () -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("runId", workflowRunId);
                    details.put("currentState", WorkflowState.PAUSED.value());
                    details.put("reason", "no_paused_event_to_link");
                    return new DomainException(
                        DomainErrorCode.RESUME_NOT_APPLICABLE,
                        "Resume requires a workflow.stateChanged → Paused event to derive the prior "
                            + "executing state and link the audit trail; none found for run "
                            + workflowRunId,
                        details);
                  });
      WorkflowState priorState = pausedEvent.priorState();
      if (priorState == null) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", WorkflowState.PAUSED.value());
        details.put("reason", "paused_event_missing_prior_state");
        log.warn(
            "recovery resume rejected workflowRunId={} reason=paused_event_missing_prior_state",
            workflowRunId);
        throw new DomainException(
            DomainErrorCode.RESUME_NOT_APPLICABLE,
            "Resume requires the → Paused event to carry a typed priorState to resume into; "
                + "none present for run "
                + workflowRunId,
            details);
      }

      // Step 4 — prep work runs inside a REQUIRES_NEW transaction so the audit trail (transition +
      // recovery.resumed event + recovery_actions row) is durable BEFORE the re-dispatch is
      // attempted. If the re-dispatch then fails, the recovery_actions row survives and gets
      // flipped
      // to `failed` in a fresh tx so operators can see the attempt in audit history (mirror retry
      // F1).
      String correlationId = actor.correlationId();
      String effectiveReason = resolveResumeReasonText(reasonText, priorState);
      ResumePrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performResumePrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        priorState,
                        pausedEvent,
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        // Concurrent-resume race (mirror retry F532): a sibling won the recovery_actions insert (or
        // the inner resumeWorkflow's idempotency record) between our pre-check and our prep tx. If
        // the prior row is now the same run AND already succeeded, return the documented replay;
        // otherwise propagate the conflict.
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<ResumeRecoveryResult> raceReplay =
              resolveConcurrentResumeReplay(workflowRunId, validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      // Step 5 — re-dispatch OUTSIDE any transaction so the audit trail above is durable before any
      // side effects. Single-sourced here (resumeWorkflowInternal did the transition ONLY — the
      // double-dispatch caution). Only priorStates that carry runner work re-dispatch; today
      // priorState is always Executing.
      String newRunnerExecutionId;
      try {
        RunnerDispatchResult dispatchResult =
            redispatchForResume(workflowRunId, priorState, correlationId);
        newRunnerExecutionId =
            dispatchResult == null ? null : dispatchResult.handle().runnerExecutionId();
      } catch (RuntimeException error) {
        boolean compensationFailed = false;
        try {
          resultStatusTransactionTemplate.executeWithoutResult(
              status -> recoveryActionRecordPort.markFailed(validatedIdempotencyKey));
        } catch (RuntimeException compensationError) {
          compensationFailed = true;
          error.addSuppressed(compensationError);
        }
        // Append-only audit: stamp the typed re-dispatch error into a second event so operators
        // reading `history` see the failure. Mutating the already-committed recovery.resumed event
        // would violate NFR4; instead append recovery.dispatchFailed in its own REQUIRES_NEW tx.
        try {
          appendResumeDispatchFailedEvent(
              workflowRunId,
              priorState,
              prep,
              validatedIdempotencyKey,
              correlationId,
              effectiveReason,
              actor,
              error,
              compensationFailed);
        } catch (RuntimeException auditError) {
          error.addSuppressed(auditError);
          String dispatchErrorCode =
              (error instanceof DomainException d) ? d.errorCode().value() : "RUNTIME_ERROR";
          log.error(
              "recovery resume dispatch-failed event append failed workflowRunId={} "
                  + "recoveryActionId={} recoveryResumedEventId={} idempotencyKey={} "
                  + "targetState={} errorCode={} errorClass={} correlationId={} "
                  + "compensationFailed={} auditErrorClass={} — audit event missing; "
                  + "structured log is the only audit trail for this attempt",
              workflowRunId,
              prep.recoveryActionPublicId,
              prep.resumedEventPublicId,
              validatedIdempotencyKey,
              priorState.value(),
              dispatchErrorCode,
              error.getClass().getSimpleName(),
              correlationId,
              compensationFailed,
              auditError.getClass().getSimpleName());
        }
        log.warn(
            "recovery resume dispatch failed workflowRunId={} targetState={} errorClass={} — workflow state stays {} without a runner; operator action required",
            workflowRunId,
            priorState.value(),
            error.getClass().getSimpleName(),
            priorState.value());
        throw error;
      }

      // Step 6 — flip the recovery_actions row to succeeded in a fresh REQUIRES_NEW tx. Leaving it
      // `pending` while reporting success would lie about audit terminality (mirror retry F526).
      try {
        resultStatusTransactionTemplate.executeWithoutResult(
            status -> recoveryActionRecordPort.markSucceeded(validatedIdempotencyKey));
      } catch (RuntimeException completionError) {
        log.error(
            "recovery resume completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} newRunnerExecutionId={} errorClass={} — run resumed but recovery_actions row stays pending; operator reconciliation required",
            workflowRunId,
            prep.recoveryActionPublicId,
            validatedIdempotencyKey,
            newRunnerExecutionId,
            completionError.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("recoveryActionId", prep.recoveryActionPublicId);
        details.put("idempotencyKey", validatedIdempotencyKey);
        details.put("newRunnerExecutionId", newRunnerExecutionId);
        details.put("reason", "result_status_flip_failed_after_resume");
        DomainException terminal =
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR,
                "Run resumed but recovery_actions.result_status flip to succeeded failed; "
                    + "row remains pending. Operator reconciliation required.",
                details);
        terminal.addSuppressed(completionError);
        throw terminal;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery resume success workflowRunId={} recoveryActionId={} newRunnerExecutionId={} resultingState={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId,
          newRunnerExecutionId,
          priorState.value(),
          elapsedMs);
      return new ResumeRecoveryResult(
          prep.recoveryActionPublicId,
          prep.resumedEventPublicId,
          newRunnerExecutionId,
          priorState,
          correlationId,
          false);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 4.8 — manually pause a run for operator intervention (NFR5: pause when uncertain).
   * Structural twin of {@link #resume} for the action shape and of {@code DeveloperTakeoverService}
   * for the cancellation shape: MDC scope → idempotency validate → require reason (trimmed; BEFORE
   * the replay pre-check — review R1) → replay pre-check (actionType-guarded {@code pause}) →
   * explicit {@code PAUSABLE_SOURCE_STATES} current-state fast-path gate → resolve the triggering
   * event <strong>BEFORE</strong> the prep tx (inside it the pause's own {@code
   * WORKFLOW_STATE_CHANGED → Paused} would win the latest-event read) → REQUIRES_NEW prep tx (in-tx
   * re-gate → transition via {@link WorkflowCommandService#pauseWorkflow} → read the authoritative
   * priorState back off the transition event + flip every {@code {queued, pending, running}} runner
   * row to {@code cancelled_for_pause} + append {@code recovery.paused} + insert {@code
   * recovery_actions}) → post-commit best-effort {@code docker stop} for previously-RUNNING rows →
   * {@code markSucceeded} → return. Differs from resume in: the target is the constant {@code
   * Paused}; the prep tx cancels runner work instead of re-dispatching (so there is NO
   * dispatch-failure compensation branch — pause enqueues nothing); and the linking event is the
   * run's latest {@code WORKFLOW_STATE_CHANGED} (nullable, best-effort like reconcile). The DB
   * status flip — not {@code docker stop} — is what makes pause hold: no poller filters on workflow
   * state, so without the flip a paused run's {@code queued} row would still be leased by {@code
   * DEQUEUE_SQL} and its in-flight row reaped by {@code scanForTimeouts}. Prior-state preservation
   * for resume (4.5) is BY TRANSITION: the {@code WORKFLOW_STATE_CHANGED → Paused} event stamps the
   * typed {@code priorState} resume reads; no detail key is added and resume is not repointed.
   */
  public PauseRecoveryResult pause(
      String workflowRunId, String idempotencyKey, ActorContext actor, String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    if (runnerAdapter == null) {
      throw new IllegalStateException("RunnerAdapter is required for pause");
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery pause start workflowRunId={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Step 1 — pause requires an explicit reason (AC2) BEFORE the replay pre-check (review R1:
      // reasonText composes the fingerprint identity — Reconciliation 9 — so a same-key retry
      // that omits it is an invalid request, not a replay; letting a blank reason skip the replay
      // discriminator gave the same key two different equality semantics). The transition table
      // does NOT mandate a reason for → Paused, so this is a story-level guard (shared
      // MISSING_REASON_TEXT, minted by 4.7). Trimmed so the stored audit rows and the replay
      // comparison share the fingerprint's normalizeOptional semantics.
      String effectiveReason = requirePauseReasonText(reasonText);

      // Step 2 — replay pre-check. Guard on ACTION_TYPE_PAUSE so a prior
      // retry/resume/reconcile/takeover row under the same key is a conflict, never a false pause
      // replay (the 4.5 review catch — retry's Step 1 still lacks the guard; do not copy retry).
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!ACTION_TYPE_PAUSE.equals(prior.actionType())
            || !workflowRunId.equals(prior.workflowRunPublicId())
            || !RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          throw idempotencyConflict(validatedIdempotencyKey, workflowRunId, prior);
        }
        return replayPauseResultOrConflict(
            workflowRunId, effectiveReason, validatedIdempotencyKey, actor, prior);
      }

      // Step 3 — explicit allow-list current-state gate (Reconciliation 1). NOT terminal-
      // subtraction: Inbox/Planned/Split/WaitingForDependencies are non-terminal but NOT pausable
      // (an autonomous driver owns their out-edge; pausing consumes a one-shot trigger).
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      WorkflowState sourceState = run.currentState();
      if (!PAUSABLE_SOURCE_STATES.contains(sourceState)) {
        // The outer catch (DomainException rejected) emits the single WARN with reason=<code>.
        throw pauseNotApplicable(workflowRunId, sourceState);
      }

      // Step 4 — resolve the triggering event BEFORE the prep tx opens (ordering trap): inside the
      // tx the pause's own transition appends its WORKFLOW_STATE_CHANGED → Paused FIRST, so a
      // post-transition read would select the pause's own event as its own trigger. Nullable /
      // best-effort like reconcile.
      String triggeringEventPublicId =
          workflowEventReadPort
              .findLatestByWorkflowRunPublicIdAndEventTypeIn(
                  workflowRunId, List.of(WorkflowEventType.WORKFLOW_STATE_CHANGED.value()))
              .map(WorkflowEventRecord::publicId)
              .orElse(null);

      String correlationId = actor.correlationId();
      PausePrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performPausePrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        triggeringEventPublicId,
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        // Concurrent-pause race (mirror retry F532): a sibling won the recovery_actions insert (or
        // the inner pauseWorkflow's idempotency record) between our pre-check and our prep tx.
        // Forward the resolved reason so a same-key/different-reason concurrent loser conflicts
        // exactly like the serial Step-1 path.
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<PauseRecoveryResult> raceReplay =
              resolveConcurrentPauseReplay(
                  workflowRunId, effectiveReason, validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      // Step 5 — best-effort container stop for previously-RUNNING rows, POST-commit and OUTSIDE
      // any tx (mirror DeveloperTakeoverService Step 3). cancel() never throws; the defensive
      // try/catch + WARN is belt-and-braces. The DB flip already stopped dispatch, so a failure
      // here never fails the pause. There is NO dispatch-failure compensation branch — pause
      // enqueues nothing, so there is no dispatch to fail.
      for (String runningRexId : prep.runningRunnerExecutionIds()) {
        try {
          runnerAdapter.cancel(runningRexId);
        } catch (RuntimeException cancelError) {
          log.warn(
              "recovery pause best-effort container-cancel failed workflowRunId={} runnerExecutionId={} cause={} — DB flip already stopped dispatch",
              workflowRunId,
              runningRexId,
              cancelError.getClass().getSimpleName());
        }
      }

      // Step 6 — flip the recovery_actions row to succeeded in a fresh REQUIRES_NEW tx. Leaving it
      // `pending` while reporting success would lie about audit terminality (mirror resume Step
      // 6).
      try {
        resultStatusTransactionTemplate.executeWithoutResult(
            status -> recoveryActionRecordPort.markSucceeded(validatedIdempotencyKey));
      } catch (RuntimeException completionError) {
        log.error(
            "recovery pause completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} errorClass={} — run paused and runners cancelled but recovery_actions row stays pending; operator reconciliation required",
            workflowRunId,
            prep.recoveryActionPublicId(),
            validatedIdempotencyKey,
            completionError.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("recoveryActionId", prep.recoveryActionPublicId());
        details.put("idempotencyKey", validatedIdempotencyKey);
        details.put("reason", "result_status_flip_failed_after_pause");
        DomainException terminal =
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR,
                "Run paused and runners cancelled but recovery_actions.result_status flip to "
                    + "succeeded failed; row remains pending. Operator reconciliation required.",
                details);
        terminal.addSuppressed(completionError);
        throw terminal;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery pause success workflowRunId={} recoveryActionId={} priorState={} cancelledInFlightCount={} cancelledQueuedCount={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId(),
          prep.sourceState().value(),
          prep.cancelledInFlightCount(),
          prep.cancelledQueuedCount(),
          elapsedMs);
      return new PauseRecoveryResult(
          prep.recoveryActionPublicId(),
          prep.pausedEventPublicId(),
          prep.sourceState(),
          prep.cancelledInFlightCount(),
          prep.cancelledQueuedCount(),
          WorkflowState.PAUSED,
          correlationId,
          false);
    } catch (DomainException rejected) {
      // Rejection observability (mirror rerunFromStep): every guarded rejection surfaces ONE WARN
      // carrying the reason before propagating — no competing per-site WARNs. currentState rides
      // the exception details when the rejecting guard captured it (review R1: the story's logging
      // spec requires it on the WARN, not only in the problem payload).
      Object rejectedCurrentState =
          rejected.details() == null ? null : rejected.details().get("currentState");
      log.warn(
          "recovery pause rejected workflowRunId={} currentState={} reason={}",
          workflowRunId,
          rejectedCurrentState == null ? "unknown" : rejectedCurrentState,
          rejected.errorCode());
      throw rejected;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private PausePrep performPausePrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      String triggeringEventPublicId,
      String effectiveReason,
      String correlationId) {
    // Review R1 (TOCTOU) — re-gate INSIDE the prep tx: the outer Step-3 gate read an unlocked
    // snapshot, so a run that advanced to a NON-pausable state in the gap must reject with the
    // typed 409 here, not with the transition's raw ILLEGAL_TRANSITION.
    WorkflowState gatedState =
        workflowRunReadPort
            .findByPublicId(workflowRunId)
            .orElseThrow(() -> runNotFound(workflowRunId))
            .currentState();
    if (!PAUSABLE_SOURCE_STATES.contains(gatedState)) {
      throw pauseNotApplicable(workflowRunId, gatedState);
    }

    PauseWorkflowCommand command =
        new PauseWorkflowCommand(
            workflowRunId,
            actor.actorIdentity(),
            actor.actorType(),
            idempotencyKey,
            correlationId,
            effectiveReason);
    workflowCommandService.pauseWorkflow(command);

    // Review R1 (TOCTOU) — the authoritative priorState is whatever the transition just stamped
    // on its workflow.stateChanged → Paused event, read back inside this same tx: if the run
    // advanced to ANOTHER pausable state between the outer gate and this tx, the recovery.paused
    // anchor, the success log, and the returned result must agree with the transition event that
    // resume derives its priorState from. Falls back to the in-tx gated read only if the command
    // replayed without a visible transition event (crash-recovery window).
    WorkflowState sourceState =
        workflowEventReadPort
            .findLatestTransitionToState(workflowRunId, WorkflowState.PAUSED)
            .map(WorkflowEventRecord::priorState)
            .orElse(gatedState);

    // AC4 — cancel dispatch: flip every {queued, pending, running} row to cancelled_for_pause
    // INSIDE this prep tx so the flip cannot commit-skew from the transition (the flip is the
    // authoritative stop-dispatch signal). Optional.empty() = already-terminal idempotent skip.
    // Prep-tx race note: markCancelledForPause locks via findByPublicIdForUpdate while DEQUEUE_SQL
    // uses FOR UPDATE SKIP LOCKED — if a worker leases a queued row this instant, our flip blocks,
    // then observes previousStatus == RUNNING and routes the rex id into the post-commit docker
    // stop
    // list. Both orderings are safe; no extra locking.
    List<RunnerExecutionSnapshot> dispatchable =
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, PAUSE_CANCEL_SCAN_STATUSES);
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    List<String> runningRunnerExecutionIds = new ArrayList<>();
    int cancelledQueuedCount = 0;
    int cancelledInFlightCount = 0;
    for (RunnerExecutionSnapshot row : dispatchable) {
      Optional<RunnerExecutionCancellation> cancellation =
          runnerExecutionRecordPort.markCancelledForPause(row.publicId(), now);
      if (cancellation.isEmpty()) {
        log.info(
            "recovery pause runner already terminal workflowRunId={} runnerExecutionId={}",
            workflowRunId,
            row.publicId());
        continue;
      }
      RunnerExecutionStatus from = cancellation.get().previousStatus();
      log.info(
          "runner execution cancelled for pause workflowRunId={} runnerExecutionId={} previousStatus={}",
          workflowRunId,
          row.publicId(),
          from.value());
      if (from == RunnerExecutionStatus.QUEUED) {
        cancelledQueuedCount++;
      } else {
        cancelledInFlightCount++;
      }
      if (from == RunnerExecutionStatus.RUNNING) {
        runningRunnerExecutionIds.add(row.publicId());
      }
    }

    // AC5/AC6 — the recovery.paused audit anchor. Prior-state preservation for resume is NOT here:
    // the transition above already stamped the typed priorState on its WORKFLOW_STATE_CHANGED →
    // Paused event (Reconciliation 6 — no priorState detail key, no workflow.paused type). This
    // event exists as the non-null recovery_actions.resulting_event_id FK anchor and carries the
    // same typed prior/resulting states for the audit trail.
    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    if (triggeringEventPublicId != null) {
      eventDetails.put(WorkflowEventDetailKeys.TRIGGERING_EVENT_ID, triggeringEventPublicId);
    }
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_PAUSED,
            sourceState,
            WorkflowState.PAUSED,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    // Story 4.18 (AC4 / Reconciliation 3) — derive reviewer_role from the actor: a SYSTEM-actor
    // auto-pause (the conflict sweep) stamps 'system' so the audit trail is distinguishable from an
    // operator (workflow_owner) pause; a same-key retry preserves the original by replaying, not
    // re-inserting.
    String reviewerRole =
        actor.actorType() == ActorType.SYSTEM ? REVIEWER_ROLE_SYSTEM : REVIEWER_ROLE_WORKFLOW_OWNER;
    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_PAUSE,
                triggeringEventPublicId,
                recoveryEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_PENDING,
                reviewerRole));

    return new PausePrep(
        recoveryAction.publicId(),
        recoveryEventPublicId,
        sourceState,
        List.copyOf(runningRunnerExecutionIds),
        cancelledInFlightCount,
        cancelledQueuedCount);
  }

  private PauseRecoveryResult replayPauseResultOrConflict(
      String workflowRunId,
      String effectiveReason,
      String idempotencyKey,
      ActorContext actor,
      RecoveryActionSnapshot prior) {
    if (prior.resultingEventPublicId() == null) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    WorkflowEventRecord event =
        workflowEventReadPort.listByWorkflowRunPublicId(workflowRunId, null).stream()
            .filter(candidate -> prior.resultingEventPublicId().equals(candidate.publicId()))
            .findFirst()
            .orElseThrow(() -> idempotencyConflict(idempotencyKey, workflowRunId, prior));
    // A same-key pause with a DIFFERENT reason is a conflict, never a replay (reasonText composes
    // the fingerprint identity — Reconciliation 9). Compare against the event row's typed reason
    // column (the 4.7 review lesson: event.reason(), not a detail key). Both sides are trimmed —
    // the caller validated+trimmed effectiveReason (review R1: the reason guard runs BEFORE the
    // replay pre-check) and the stored reason was trimmed at write time — matching the
    // fingerprint's normalizeOptional semantics, so a whitespace-differing identical retry
    // replays instead of conflicting.
    String storedReason = event.reason();
    if (!effectiveReason.equals(storedReason)) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    // priorState re-derivation: the recovery.paused event linked to THIS action carries the typed
    // priorState of THIS pause — per-action precise even if the run was later resumed and re-paused
    // under a different key (findLatestTransitionToState would return the LATEST pause's prior
    // state). Fall back to the latest → Paused transition for defensive robustness.
    WorkflowState priorState =
        event.priorState() != null
            ? event.priorState()
            : resolvePriorExecutingStateForReplay(workflowRunId).orElse(null);
    log.info(
        "recovery pause replay workflowRunId={} recoveryActionId={} idempotencyKey={}",
        workflowRunId,
        prior.publicId(),
        idempotencyKey);
    // Cancellation counts are 0 on replay BY CONTRACT: this call cancelled nothing and the
    // original call's counts are not recomputed. (Takeover signals "not recomputed" with null
    // counts; this record uses primitive ints, so 0 + replayed=true is the equivalent signal —
    // callers must branch on replayed(), not on the counts.)
    return new PauseRecoveryResult(
        prior.publicId(),
        prior.resultingEventPublicId(),
        priorState,
        0,
        0,
        WorkflowState.PAUSED,
        actor.correlationId(),
        true);
  }

  private Optional<PauseRecoveryResult> resolveConcurrentPauseReplay(
      String workflowRunId, String rawReason, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    // Guard ACTION_TYPE_PAUSE + non-null resulting_event_id (the FK is ON DELETE SET NULL; a
    // link-less row cannot back a replay) — mirror resolveConcurrentResumeReplay.
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())
        || !ACTION_TYPE_PAUSE.equals(snapshot.actionType())
        || !RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
        || snapshot.resultingEventPublicId() == null) {
      return Optional.empty();
    }
    return Optional.of(
        replayPauseResultOrConflict(workflowRunId, rawReason, idempotencyKey, actor, snapshot));
  }

  private static String requirePauseReasonText(String reasonText) {
    if (reasonText != null && !reasonText.isBlank()) {
      // Trimmed at the boundary (review R1) so every downstream site — the fingerprint
      // (normalizeOptional trims), the transition's fallbackReason, the recovery.paused event's
      // typed reason, and the replay discriminator — stores and compares the SAME value.
      return reasonText.trim();
    }
    throw new DomainException(
        DomainErrorCode.MISSING_REASON_TEXT,
        "Pause requires a non-blank reason",
        Map.of("field", "reasonText"));
  }

  private record PausePrep(
      String recoveryActionPublicId,
      String pausedEventPublicId,
      WorkflowState sourceState,
      List<String> runningRunnerExecutionIds,
      int cancelledInFlightCount,
      int cancelledQueuedCount) {}

  // Review R1 — single construction site for the typed 409 (raised by the outer fast-path gate
  // AND the in-tx re-gate); the pausable-source list in the message derives from
  // PAUSABLE_SOURCE_STATES so it cannot drift from the constant it describes.
  private static DomainException pauseNotApplicable(
      String workflowRunId, WorkflowState currentState) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("currentState", currentState.value());
    return new DomainException(
        DomainErrorCode.PAUSE_NOT_APPLICABLE,
        "Pause is not applicable from state "
            + currentState.value()
            + " (pausable sources: "
            + PAUSABLE_SOURCE_STATES.stream()
                .map(WorkflowState::value)
                .collect(java.util.stream.Collectors.joining(", "))
            + ")",
        details);
  }

  public ReconcileRecoveryResult reconcile(
      String workflowRunId,
      String conflictId,
      String resolutionDecision,
      String idempotencyKey,
      ActorContext actor,
      String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    if (integrationConflictService == null) {
      throw new IllegalStateException("IntegrationConflictService is required for reconcile");
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery reconcile start workflowRunId={} conflictId={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          conflictId,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Idempotency replay pre-check BEFORE input validation (mirror resume's replay-first order):
      // a genuine retry of an already-succeeded reconcile must replay even if the client now omits
      // reasonText or sends a blank/invalid decision.
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!ACTION_TYPE_RECONCILE.equals(prior.actionType())
            || !workflowRunId.equals(prior.workflowRunPublicId())
            || !RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          throw idempotencyConflict(validatedIdempotencyKey, workflowRunId, prior);
        }
        return replayReconcileResultOrConflict(
            workflowRunId, conflictId, resolutionDecision, validatedIdempotencyKey, actor, prior);
      }

      ReconciliationDecision decision = parseDecision(resolutionDecision);
      String effectiveReason = resolveReconcileReasonText(reasonText);

      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      if (TERMINAL_STATES.contains(run.currentState())) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", run.currentState().value());
        throw new DomainException(
            DomainErrorCode.RECONCILE_NOT_APPLICABLE,
            "Reconcile is not applicable from terminal state " + run.currentState().value(),
            details);
      }
      ConflictResolutionView view = validateConflictForReconcile(workflowRunId, conflictId);
      String correlationId = actor.correlationId();
      ReconcilePrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performReconcilePrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        decision,
                        view,
                        run.currentState(),
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<ReconcileRecoveryResult> raceReplay =
              resolveConcurrentReconcileReplay(
                  workflowRunId, conflictId, validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      applyReconcileSideEffect(workflowRunId, decision, view);
      // Mirror resume Step 6: a failed status flip must not surface as a raw error nor silently
      // strand the row as pending (which would poison every later same-key replay with
      // IDEMPOTENCY_KEY_CONFLICT). Escalate to INTERNAL_ERROR so the operator knows the reconcile
      // landed but needs reconciliation of the audit row.
      try {
        resultStatusTransactionTemplate.executeWithoutResult(
            status -> recoveryActionRecordPort.markSucceeded(validatedIdempotencyKey));
      } catch (RuntimeException completionError) {
        log.error(
            "recovery reconcile completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} errorClass={} — run reconciled but recovery_actions row stays pending; operator reconciliation required",
            workflowRunId,
            prep.recoveryActionPublicId,
            validatedIdempotencyKey,
            completionError.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("recoveryActionId", prep.recoveryActionPublicId);
        details.put("idempotencyKey", validatedIdempotencyKey);
        details.put("reason", "result_status_flip_failed_after_reconcile");
        DomainException terminal =
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR,
                "Run reconciled but recovery_actions.result_status flip to succeeded failed; "
                    + "row remains pending. Operator reconciliation required.",
                details);
        terminal.addSuppressed(completionError);
        throw terminal;
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery reconcile success workflowRunId={} recoveryActionId={} resolvedConflictId={} decision={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId,
          conflictId,
          decision.value(),
          elapsedMs);
      return new ReconcileRecoveryResult(
          prep.recoveryActionPublicId,
          prep.reconciledEventPublicId,
          conflictId,
          prep.resultingState,
          correlationId,
          false);
    } catch (DomainException rejected) {
      // Rejection observability: every guarded rejection (missing/invalid decision, blank reason,
      // RUN_NOT_FOUND, RECONCILE_NOT_APPLICABLE, CONFLICT_NOT_FOUND/ALREADY_RESOLVED, idempotency
      // conflict) surfaces one WARN carrying the reason before propagating.
      log.warn(
          "recovery reconcile rejected workflowRunId={} conflictId={} reason={}",
          workflowRunId,
          conflictId,
          rejected.errorCode());
      throw rejected;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 4.9 — apply a governed failure-taxonomy classification to a failed run (FR37/FR38). The
   * STRUCTURAL OUTLIER of the 4.5–4.9 slice: a PURE METADATA operation (epic AC10 — no state
   * transition, no runner re-dispatch, no integration side-effect), so it carries none of the
   * siblings' command/transition/post-commit apparatus. Shape: MDC scope → idempotency validate →
   * replay pre-check (actionType-guarded {@code classify_failure}, BEFORE input validation — the
   * reconcile ordering; same key + same run + succeeded replays iff the prior event's {@code
   * details.taxonomyValue} matches the request) → parse taxonomyValue ({@code
   * MISSING_/INVALID_TAXONOMY_VALUE}) → deprecation write-guard ({@link
   * FailureTaxonomyPolicy#requireNotDeprecated}) → current-state gate ({@code FAILED} only, else
   * {@code CLASSIFY_NOT_APPLICABLE}) → resolve the triggering failure event (best-effort, nullable
   * FK) → ONE {@code REQUIRES_NEW} prep tx ({@code applyClassification} column overwrite capturing
   * the prior value → append {@code recovery.failureClassified} with {@code priorState ==
   * resultingState == Failed}, {@code failureCategory = null}, {@code interventionMarker = true} →
   * insert the {@code recovery_actions} row with {@code result_status='succeeded'} DIRECTLY) →
   * return. There is NO {@code markSucceeded} flip and NO post-commit block: classify's entire
   * effect commits atomically, so a {@code pending} row would have nothing to flip it and the
   * replay pre-check keys on {@code succeeded} (story 4.9 R16). Re-classification overwrites the
   * column but appends a NEW event carrying {@code priorTaxonomyValue} — history lives in the event
   * chain (AC9, FR47 append-only).
   */
  public ClassifyFailureResult classifyFailure(
      String workflowRunId,
      String taxonomyValue,
      String idempotencyKey,
      ActorContext actor,
      String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    if (workflowRunFailureClassificationPort == null) {
      throw new IllegalStateException(
          "WorkflowRunFailureClassificationPort is required for classifyFailure");
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery classifyFailure start workflowRunId={} taxonomyValue={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          taxonomyValue,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Step 1 — replay pre-check BEFORE input validation (mirror reconcile's replay-first order):
      // a genuine retry of an already-succeeded classify must replay even if the client now sends
      // a blank value. Guard on ACTION_TYPE_CLASSIFY_FAILURE so a prior
      // retry/resume/reconcile/rerun/pause/takeover under the same key is a conflict, never a
      // false classify replay (the 4.5 review catch).
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!ACTION_TYPE_CLASSIFY_FAILURE.equals(prior.actionType())
            || !workflowRunId.equals(prior.workflowRunPublicId())
            || !RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          throw idempotencyConflict(validatedIdempotencyKey, workflowRunId, prior);
        }
        return replayClassifyFailureResultOrConflict(
            workflowRunId, taxonomyValue, validatedIdempotencyKey, actor, prior);
      }

      // Step 2 — parse + deprecation write-guard. Reads over the registry stay total (NFR33);
      // only this write path rejects a deprecated value.
      FailureTaxonomyValue taxonomy = parseTaxonomyValue(taxonomyValue);
      FailureTaxonomyPolicy.requireNotDeprecated(
          taxonomy.value(), taxonomy.deprecatedReplacementValue());
      String effectiveReason =
          (reasonText == null || reasonText.isBlank()) ? null : reasonText.trim();

      // Step 3 — current-state gate: FAILED only (AC3 provisional bind, OQ-1). A pure service
      // precondition — there is no transition and therefore no transition-table guard; keeps this
      // gate, baseActionMatrix case FAILED, and RecommendationService's FAILED-only early-return
      // mutually consistent.
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      if (run.currentState() != WorkflowState.FAILED) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", run.currentState().value());
        throw new DomainException(
            DomainErrorCode.CLASSIFY_NOT_APPLICABLE,
            "Classify is not applicable from state "
                + run.currentState().value()
                + " (classifiable states: Failed)",
            details);
      }

      // Step 4 — resolve the triggering event best-effort (nullable FK): the run's terminal
      // failure event.
      String triggeringEventPublicId =
          workflowEventReadPort
              .findLatestFailureEvent(workflowRunId)
              .map(WorkflowEventRecord::publicId)
              .orElse(null);

      String correlationId = actor.correlationId();
      ClassifyFailurePrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performClassifyFailurePrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        taxonomy,
                        run.currentState(),
                        triggeringEventPublicId,
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<ClassifyFailureResult> raceReplay =
              resolveConcurrentClassifyReplay(
                  workflowRunId, taxonomy.value(), validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery classifyFailure success workflowRunId={} recoveryActionId={} classifiedEventId={} taxonomyValue={} priorTaxonomyValue={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId,
          prep.classifiedEventPublicId,
          taxonomy.value(),
          prep.priorTaxonomyValue,
          elapsedMs);
      return new ClassifyFailureResult(
          prep.recoveryActionPublicId,
          prep.classifiedEventPublicId,
          taxonomy.value(),
          prep.priorTaxonomyValue,
          correlationId,
          false);
    } catch (DomainException rejected) {
      // Rejection observability: every guarded rejection (missing/invalid/deprecated value,
      // RUN_NOT_FOUND, CLASSIFY_NOT_APPLICABLE, idempotency conflict) surfaces one WARN carrying
      // the reason before propagating (mirror reconcile).
      log.warn(
          "recovery classifyFailure rejected workflowRunId={} taxonomyValue={} reason={}",
          workflowRunId,
          taxonomyValue,
          rejected.errorCode());
      throw rejected;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * Story 4.7 — rerun a run from a safe step boundary (Investigating/Executing). Structural twin of
   * {@link #resume} + {@link #reconcile}: MDC scope → idempotency validate → replay pre-check
   * (actionType-guarded {@code rerun}) → parse targetStep ({@link SafeRerunStep}) + require reason
   * → current-state read (terminal / unwired pairs surface {@code ILLEGAL_TRANSITION} from the
   * transition table) → resolve triggering event + read active-leaf artifact ids at/beyond the
   * target step → REQUIRES_NEW prep tx (transition via {@link
   * WorkflowCommandService#rerunFromStepWorkflow} + invalidate the prior stage approval via {@link
   * ApprovalService#invalidateCurrentApproval} + append {@code recovery.rerunFromStep} + insert
   * {@code recovery_actions}) → post-commit runner re-enqueue (single-sourced here) with
   * compensation → best-effort Linear reopen notification → {@code markSucceeded} → return. Differs
   * from resume in: the target is operator-chosen (String → {@code SafeRerunStep} → {@link
   * WorkflowState}); a prior approval is invalidated in the prep tx; there are TWO post-commit
   * side-effects (runner re-enqueue + best-effort Linear reopen); and it reads leaf artifact ids
   * for the audit detail.
   */
  public RerunFromStepRecoveryResult rerunFromStep(
      String workflowRunId,
      String targetStep,
      String idempotencyKey,
      ActorContext actor,
      String reasonText) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(actor, "actor");
    if (approvalService == null
        || artifactRecordPort == null
        || workflowTransitionService == null) {
      throw new IllegalStateException(
          "ApprovalService, ArtifactRecordPort and WorkflowTransitionService are required for"
              + " rerunFromStep");
    }
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(idempotencyKey);
      long start = System.nanoTime();
      log.info(
          "recovery rerunFromStep start workflowRunId={} targetStep={} idempotencyKey={} actorIdentity={}",
          workflowRunId,
          targetStep,
          validatedIdempotencyKey,
          actor.actorIdentity());

      // Step 1 — replay pre-check BEFORE input validation (mirror reconcile's replay-first order):
      // a
      // genuine retry of an already-succeeded rerun must replay even if the client now omits
      // reason.
      // Guard on ACTION_TYPE_RERUN so a prior retry/resume/reconcile/takeover under the same key is
      // a conflict, never a false rerun replay.
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!ACTION_TYPE_RERUN.equals(prior.actionType())
            || !workflowRunId.equals(prior.workflowRunPublicId())
            || !RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())) {
          throw idempotencyConflict(validatedIdempotencyKey, workflowRunId, prior);
        }
        return replayRerunResultOrConflict(
            workflowRunId, targetStep, reasonText, validatedIdempotencyKey, actor, prior);
      }

      // Step 2 — parse the operator-chosen target step (null/blank or unknown → INVALID_RERUN_...).
      WorkflowState targetState = resolveTargetState(targetStep);
      // Step 3 — rerun requires an explicit reason (AC2); the transition table does NOT mandate one
      // for Investigating/Executing targets, so this is a story-level guard (mirror reconcile).
      String effectiveReason = requireReasonText(reasonText);

      // Step 4 — current-state read + explicit source-state gate. Rerun-from-step may launch ONLY
      // from RERUN_SOURCE_STATES ({FAILED, WAITING_FOR_REVIEW}), matching the AC10 allowed-actions
      // gate. The transition table alone is too permissive (it legally allows, e.g.,
      // WAITING_FOR_SPEC_APPROVAL → EXECUTING/INVESTIGATING); rerunning from such a state would
      // invalidate a live PM approval + re-dispatch — the ADR-0034 out-of-scope case. This
      // executor-as-gate check (retry: != FAILED; resume: != PAUSED) also subsumes the terminal
      // states, which are not in the allow-list. Short-circuit here for a clean ILLEGAL_TRANSITION
      // before consuming any state.
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));
      if (!RERUN_SOURCE_STATES.contains(run.currentState())) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("currentState", run.currentState().value());
        details.put("targetStep", targetState.value());
        // The outer catch (DomainException rejected) emits the single WARN with reason=<code>.
        throw new DomainException(
            DomainErrorCode.ILLEGAL_TRANSITION,
            "Rerun-from-step is not applicable from state "
                + run.currentState().value()
                + " (allowed sources: Failed, WaitingForReview)",
            details);
      }

      // Step 5 — resolve the triggering event (best-effort, nullable FK): the run's latest failure
      // event, else the latest event overall.
      String triggeringEventPublicId =
          workflowEventReadPort
              .findLatestFailureEvent(workflowRunId)
              .or(() -> workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunId))
              .map(WorkflowEventRecord::publicId)
              .orElse(null);

      // Step 6 — read the active-leaf artifact ids at/beyond the target step for the audit detail
      // (READ ONLY — there is NO artifact-supersession write; the leaf advances via lineage graft
      // when the re-run runner produces its next version). INVESTIGATING⇒{spec,plan,prOutput};
      // EXECUTING⇒{plan,prOutput}.
      List<String> supersededArtifactIds = resolveSupersededArtifactIds(workflowRunId, targetState);
      // Which prior approval this rerun invalidates: INVESTIGATING⇒spec, EXECUTING⇒plan.
      ArtifactType approvalArtifactType = invalidatedApprovalArtifactType(targetState);

      String correlationId = actor.correlationId();
      RerunPrep prep;
      try {
        prep =
            retryPrepTransactionTemplate.execute(
                status ->
                    performRerunPrep(
                        workflowRunId,
                        validatedIdempotencyKey,
                        actor,
                        targetState,
                        run.currentState(),
                        triggeringEventPublicId,
                        supersededArtifactIds,
                        approvalArtifactType,
                        effectiveReason,
                        correlationId));
      } catch (DomainException prepError) {
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<RerunFromStepRecoveryResult> raceReplay =
              resolveConcurrentRerunReplay(
                  workflowRunId,
                  safeStepValueOf(targetState),
                  effectiveReason,
                  validatedIdempotencyKey,
                  actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      // Step 7 — re-enqueue the runner OUTSIDE any transaction so the audit trail above is durable
      // before any side effect (single-sourced here — rerunFromStepWorkflowInternal did the
      // transition ONLY; the double-dispatch caution). manual-kind parks. Retry-style compensation.
      RunnerStage targetStage = runnerStageFor(targetState);
      String dispatchKey = validatedIdempotencyKey + ":runner";
      String newRunnerExecutionId;
      try {
        org.dradgo.domain.registry.RunnerKind kind =
            projectRuntimeConfigResolver.resolveRunnerKind(workflowRunId, targetStage);
        if (kind == org.dradgo.domain.registry.RunnerKind.MANUAL) {
          org.dradgo.application.runner.RunnerDispatchResult.Parked parked =
              (org.dradgo.application.runner.RunnerDispatchResult.Parked)
                  manualExecutionDispatcher.park(workflowRunId, targetStage, dispatchKey, actor);
          newRunnerExecutionId = parked.handle().runnerExecutionId();
        } else {
          org.dradgo.application.runner.queue.QueuedRunnerExecution dispatchResult =
              runnerExecutionQueue.enqueue(
                  workflowRunId, targetStage, dispatchKey, actor, RECOVERY_QUEUE_PRIORITY);
          newRunnerExecutionId = dispatchResult.runnerExecutionPublicId();
        }
      } catch (RuntimeException error) {
        boolean compensationFailed = false;
        try {
          resultStatusTransactionTemplate.executeWithoutResult(
              status -> recoveryActionRecordPort.markFailed(validatedIdempotencyKey));
        } catch (RuntimeException compensationError) {
          compensationFailed = true;
          error.addSuppressed(compensationError);
        }
        try {
          appendRerunDispatchFailedEvent(
              workflowRunId,
              targetState,
              prep,
              validatedIdempotencyKey,
              correlationId,
              effectiveReason,
              actor,
              error,
              compensationFailed);
        } catch (RuntimeException auditError) {
          error.addSuppressed(auditError);
          String dispatchErrorCode =
              (error instanceof DomainException d) ? d.errorCode().value() : "RUNTIME_ERROR";
          log.error(
              "recovery rerunFromStep dispatch-failed event append failed workflowRunId={} "
                  + "recoveryActionId={} rerunEventId={} idempotencyKey={} targetStep={} "
                  + "errorCode={} errorClass={} correlationId={} compensationFailed={} "
                  + "auditErrorClass={} — audit event missing; structured log is the only audit "
                  + "trail for this attempt",
              workflowRunId,
              prep.recoveryActionPublicId(),
              prep.rerunEventPublicId(),
              validatedIdempotencyKey,
              targetState.value(),
              dispatchErrorCode,
              error.getClass().getSimpleName(),
              correlationId,
              compensationFailed,
              auditError.getClass().getSimpleName());
        }
        // [Review D1] Compensate the committed prep so the dispatch failure does not strand the
        // run:
        // restore the invalidated approval(s) and drive the run to FAILED (a legal retry/rerun
        // source). Best-effort — a compensation failure is suppressed onto the original error.
        compensateRerunDispatchFailure(
            workflowRunId, targetState, prep, actor, effectiveReason, error);
        log.warn(
            "recovery rerunFromStep dispatch failed workflowRunId={} targetStep={} errorClass={} — compensated (approval restored + run driven to Failed); operator retry/rerun available",
            workflowRunId,
            targetState.value(),
            error.getClass().getSimpleName());
        throw error;
      }

      // Step 8 — flip the recovery_actions row to succeeded (mirror resume Step 6). A failed flip
      // must not silently strand the row as pending (poisons later same-key replays) — escalate.
      try {
        resultStatusTransactionTemplate.executeWithoutResult(
            status -> recoveryActionRecordPort.markSucceeded(validatedIdempotencyKey));
      } catch (RuntimeException completionError) {
        log.error(
            "recovery rerunFromStep completion status update failed workflowRunId={} recoveryActionId={} idempotencyKey={} newRunnerExecutionId={} errorClass={} — run rerun but recovery_actions row stays pending; operator reconciliation required",
            workflowRunId,
            prep.recoveryActionPublicId(),
            validatedIdempotencyKey,
            newRunnerExecutionId,
            completionError.getClass().getSimpleName());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", workflowRunId);
        details.put("recoveryActionId", prep.recoveryActionPublicId());
        details.put("idempotencyKey", validatedIdempotencyKey);
        details.put("newRunnerExecutionId", newRunnerExecutionId);
        details.put("reason", "result_status_flip_failed_after_rerun");
        DomainException terminal =
            new DomainException(
                DomainErrorCode.INTERNAL_ERROR,
                "Run rerun but recovery_actions.result_status flip to succeeded failed; "
                    + "row remains pending. Operator reconciliation required.",
                details);
        terminal.addSuppressed(completionError);
        throw terminal;
      }

      // Step 9 — best-effort Linear reopen notification (LAST side-effect, never fails the rerun).
      bestEffortLinearReopenNotification(workflowRunId, targetState, correlationId);

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "recovery rerunFromStep success workflowRunId={} recoveryActionId={} newRunnerExecutionId={} resultingState={} invalidatedApprovalCount={} durationMs={}",
          workflowRunId,
          prep.recoveryActionPublicId(),
          newRunnerExecutionId,
          targetState.value(),
          prep.invalidatedApprovalIds().size(),
          elapsedMs);
      return new RerunFromStepRecoveryResult(
          prep.recoveryActionPublicId(),
          prep.rerunEventPublicId(),
          supersededArtifactIds,
          prep.invalidatedApprovalIds(),
          newRunnerExecutionId,
          targetState,
          correlationId,
          false);
    } catch (DomainException rejected) {
      // Rejection observability: every guarded rejection surfaces one WARN carrying the reason.
      log.warn(
          "recovery rerunFromStep rejected workflowRunId={} targetStep={} reason={}",
          workflowRunId,
          targetStep,
          rejected.errorCode());
      throw rejected;
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private WorkflowState resolveTargetState(String targetStep) {
    SafeRerunStep step;
    try {
      step = SafeRerunStep.fromNullableValue(targetStep, "targetStep");
    } catch (DomainException invalid) {
      throw invalidRerunTargetStep(targetStep);
    }
    if (step == null) {
      throw invalidRerunTargetStep(targetStep);
    }
    return switch (step) {
      case INVESTIGATING -> WorkflowState.INVESTIGATING;
      case EXECUTING -> WorkflowState.EXECUTING;
    };
  }

  private static DomainException invalidRerunTargetStep(String provided) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("provided", provided);
    return new DomainException(
        DomainErrorCode.INVALID_RERUN_TARGET_STEP,
        "Invalid rerun target step: " + provided,
        details);
  }

  private static String requireReasonText(String reasonText) {
    if (reasonText != null && !reasonText.isBlank()) {
      return reasonText;
    }
    throw new DomainException(
        DomainErrorCode.MISSING_REASON_TEXT,
        "Rerun-from-step requires a non-blank reason",
        Map.of("field", "reasonText"));
  }

  private static ArtifactType invalidatedApprovalArtifactType(WorkflowState targetState) {
    // INVESTIGATING (re-spec) invalidates the current spec approval; EXECUTING (re-implement) the
    // current implementationPlan approval.
    return targetState == WorkflowState.INVESTIGATING
        ? ArtifactType.SPEC
        : ArtifactType.IMPLEMENTATION_PLAN;
  }

  private static RunnerStage runnerStageFor(WorkflowState targetState) {
    return targetState == WorkflowState.INVESTIGATING
        ? RunnerStage.INVESTIGATION
        : RunnerStage.EXECUTION;
  }

  /**
   * The {@link SafeRerunStep} wire token for a resolved target state ({@code "investigating"} /
   * {@code "executing"}). This — NOT the capitalized {@link WorkflowState#value()} — is what {@code
   * details.targetStep} carries, so it matches the operator-supplied token on the idempotent-replay
   * comparison and stays consistent with the {@code SafeRerunStep} vocabulary.
   */
  private static String safeStepValueOf(WorkflowState targetState) {
    return targetState == WorkflowState.INVESTIGATING
        ? SafeRerunStep.INVESTIGATING.value()
        : SafeRerunStep.EXECUTING.value();
  }

  private List<String> resolveSupersededArtifactIds(
      String workflowRunId, WorkflowState targetState) {
    List<ArtifactType> types =
        targetState == WorkflowState.INVESTIGATING
            ? List.of(ArtifactType.SPEC, ArtifactType.IMPLEMENTATION_PLAN, ArtifactType.PR_OUTPUT)
            : List.of(ArtifactType.IMPLEMENTATION_PLAN, ArtifactType.PR_OUTPUT);
    List<String> leafIds = new ArrayList<>();
    for (ArtifactType type : types) {
      artifactRecordPort
          .findLatestByWorkflowRunIdAndArtifactType(workflowRunId, type.value())
          .map(ArtifactRecordSnapshot::publicId)
          .ifPresent(leafIds::add);
    }
    return List.copyOf(leafIds);
  }

  private RerunPrep performRerunPrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      WorkflowState targetState,
      WorkflowState priorState,
      String triggeringEventPublicId,
      List<String> supersededArtifactIds,
      ArtifactType approvalArtifactType,
      String effectiveReason,
      String correlationId) {
    RerunFromStepWorkflowCommand command =
        new RerunFromStepWorkflowCommand(
            workflowRunId,
            actor.actorIdentity(),
            actor.actorType(),
            idempotencyKey,
            correlationId,
            effectiveReason,
            targetState);
    workflowCommandService.rerunFromStepWorkflow(command);

    // Approval invalidation is the enforced supersession (Reconciliation 2), routed through the
    // approval package (write-boundary spirit, mirror 4.6). MANDATORY joins this prep tx.
    List<String> invalidatedApprovalIds = new ArrayList<>();
    approvalService
        .invalidateCurrentApproval(
            workflowRunId, approvalArtifactType.value(), INVALIDATED_REASON_RERUN)
        .ifPresent(invalidatedApprovalIds::add);

    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.TARGET_STEP, safeStepValueOf(targetState));
    eventDetails.put(WorkflowEventDetailKeys.SUPERSEDED_ARTIFACT_IDS, supersededArtifactIds);
    if (!invalidatedApprovalIds.isEmpty()) {
      eventDetails.put(WorkflowEventDetailKeys.INVALIDATED_APPROVAL_IDS, invalidatedApprovalIds);
    }
    if (triggeringEventPublicId != null) {
      eventDetails.put(WorkflowEventDetailKeys.TRIGGERING_EVENT_ID, triggeringEventPublicId);
    }
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_RERUN_FROM_STEP,
            priorState,
            targetState,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_RERUN,
                triggeringEventPublicId,
                recoveryEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_PENDING,
                REVIEWER_ROLE_WORKFLOW_OWNER));

    return new RerunPrep(
        recoveryAction.publicId(), recoveryEventPublicId, List.copyOf(invalidatedApprovalIds));
  }

  private void appendRerunDispatchFailedEvent(
      String workflowRunId,
      WorkflowState targetState,
      RerunPrep prep,
      String idempotencyKey,
      String correlationId,
      String effectiveReason,
      ActorContext actor,
      RuntimeException error,
      boolean compensationFailed) {
    String errorCode =
        (error instanceof DomainException domainError)
            ? domainError.errorCode().value()
            : "RUNTIME_ERROR";
    String errorClass = error.getClass().getSimpleName();
    String dispatchFailedReason = "rerun re-enqueue failed: " + errorCode;
    String dispatchFailedEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.RECOVERY_ACTION_ID, prep.recoveryActionPublicId());
    // The recovery-event-id detail key (retry-flavored name) carries the recovery.rerunFromStep
    // event id — the only recovery-event-id allow-listed key (matches resume's deferred note).
    eventDetails.put(WorkflowEventDetailKeys.RECOVERY_RETRIED_EVENT_ID, prep.rerunEventPublicId());
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CODE, errorCode);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CLASS, errorClass);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    if (compensationFailed) {
      eventDetails.put(WorkflowEventDetailKeys.COMPENSATION_FAILED, true);
    }
    resultStatusTransactionTemplate.executeWithoutResult(
        status -> {
          workflowEventWritePort.append(
              new WorkflowEventRecord(
                  dispatchFailedEventPublicId,
                  workflowRunId,
                  WorkflowEventType.RECOVERY_DISPATCH_FAILED,
                  targetState,
                  targetState,
                  actor.actorIdentity(),
                  actor.actorType(),
                  dispatchFailedReason,
                  null,
                  true,
                  OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                  eventDetails));
          log.info(
              "recovery rerunFromStep dispatch-failed event appended workflowRunId={} dispatchFailedEventId={} errorCode={} errorClass={}",
              workflowRunId,
              dispatchFailedEventPublicId,
              errorCode,
              errorClass);
        });
  }

  /**
   * [Review D1] Compensate a post-commit runner-dispatch failure. The prep tx already committed the
   * transition to {@code targetState} + the prior-approval invalidation, but no runner was enqueued
   * — leaving the run stranded (target state is not a legal retry/rerun source) with a destroyed
   * approval. This restores the invalidated approval(s) and drives the run to {@code FAILED} (a
   * legal {@code retry}/{@code rerunFromStep} source) via the {@code RECOVERY_DISPATCH_FAILED}
   * category — the only {@code INVESTIGATING/EXECUTING→FAILED} edges are failure-category-guarded.
   * Every step is best-effort: any compensation failure is suppressed onto the original dispatch
   * {@code error} so it never masks the real cause, and an {@code ILLEGAL_TRANSITION} (e.g. a
   * concurrent move) is logged-and-skipped like {@code RunnerBroker.driveWorkflowFailed}.
   */
  private void compensateRerunDispatchFailure(
      String workflowRunId,
      WorkflowState targetState,
      RerunPrep prep,
      ActorContext actor,
      String effectiveReason,
      RuntimeException error) {
    String fromStep = safeStepValueOf(targetState);
    for (String approvalId : prep.invalidatedApprovalIds()) {
      try {
        approvalService.restoreInvalidatedApproval(approvalId);
      } catch (RuntimeException restoreError) {
        error.addSuppressed(restoreError);
        log.warn(
            "recovery rerunFromStep compensation approval-restore failed workflowRunId={} approvalId={} errorClass={} — approval stays invalidated; operator action required",
            workflowRunId,
            approvalId,
            restoreError.getClass().getSimpleName());
      }
    }
    try {
      workflowTransitionService.transition(
          workflowRunId,
          WorkflowState.FAILED,
          new WorkflowTransitionService.TransitionActor(actor.actorIdentity(), actor.actorType()),
          "rerun-from-step re-enqueue failed; compensated to Failed",
          "rerun-compensation:"
              + workflowRunId
              + ":"
              + FailureCategory.RECOVERY_DISPATCH_FAILED.value(),
          FailureCategory.RECOVERY_DISPATCH_FAILED,
          Map.of(WorkflowEventDetailKeys.TARGET_STEP, fromStep));
      log.warn(
          "recovery rerunFromStep compensation drove run to Failed workflowRunId={} fromStep={} category={}",
          workflowRunId,
          fromStep,
          FailureCategory.RECOVERY_DISPATCH_FAILED.value());
    } catch (DomainException transitionError) {
      if (transitionError.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
        log.warn(
            "recovery rerunFromStep compensation transition-to-Failed skipped (illegal transition) workflowRunId={} fromStep={} — run left in target state; operator action required",
            workflowRunId,
            fromStep);
      } else {
        error.addSuppressed(transitionError);
        log.warn(
            "recovery rerunFromStep compensation transition-to-Failed failed workflowRunId={} errorClass={} — run left in target state; operator action required",
            workflowRunId,
            transitionError.getClass().getSimpleName());
      }
    } catch (RuntimeException transitionError) {
      error.addSuppressed(transitionError);
      log.warn(
          "recovery rerunFromStep compensation transition-to-Failed failed workflowRunId={} errorClass={} — run left in target state; operator action required",
          workflowRunId,
          transitionError.getClass().getSimpleName());
    }
  }

  private RerunFromStepRecoveryResult replayRerunResultOrConflict(
      String workflowRunId,
      String rawTargetStep,
      String rawReason,
      String idempotencyKey,
      ActorContext actor,
      RecoveryActionSnapshot prior) {
    if (prior.resultingEventPublicId() == null) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    WorkflowEventRecord event =
        workflowEventReadPort.listByWorkflowRunPublicId(workflowRunId, null).stream()
            .filter(candidate -> prior.resultingEventPublicId().equals(candidate.publicId()))
            .findFirst()
            .orElseThrow(() -> idempotencyConflict(idempotencyKey, workflowRunId, prior));
    // A same-key rerun requesting a DIFFERENT step OR a DIFFERENT non-blank reason than the
    // persisted one is a conflict, never a replay (targetStep AND reasonText both compose the
    // fingerprint identity — Reconciliation 9 / WorkflowCommandFingerprintFactory). A null/blank
    // raw
    // value skips its own comparison so a genuine retry that omits it still replays; the
    // concurrent-
    // replay path now forwards the resolved step + reason (not null) so the race enforces the same
    // discriminators as the serial Step-1 path.
    Object storedTargetStep = event.details().get(WorkflowEventDetailKeys.TARGET_STEP);
    if (rawTargetStep != null
        && !rawTargetStep.isBlank()
        && !rawTargetStep.equals(storedTargetStep)) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    String storedReason = event.reason();
    if (rawReason != null && !rawReason.isBlank() && !rawReason.equals(storedReason)) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    WorkflowState resultingState = event.resultingState();
    log.info(
        "recovery rerunFromStep replay workflowRunId={} recoveryActionId={} idempotencyKey={}",
        workflowRunId,
        prior.publicId(),
        idempotencyKey);
    return new RerunFromStepRecoveryResult(
        prior.publicId(),
        prior.resultingEventPublicId(),
        stringListDetail(event, WorkflowEventDetailKeys.SUPERSEDED_ARTIFACT_IDS),
        stringListDetail(event, WorkflowEventDetailKeys.INVALIDATED_APPROVAL_IDS),
        null,
        resultingState,
        actor.correlationId(),
        true);
  }

  private Optional<RerunFromStepRecoveryResult> resolveConcurrentRerunReplay(
      String workflowRunId,
      String rawTargetStep,
      String rawReason,
      String idempotencyKey,
      ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())
        || !ACTION_TYPE_RERUN.equals(snapshot.actionType())
        || !RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
        || snapshot.resultingEventPublicId() == null) {
      return Optional.empty();
    }
    // Forward the resolved step + reason (NOT null) so a same-key/different-step (or /different-
    // reason) concurrent loser raises IDEMPOTENCY_KEY_CONFLICT — matching the serial Step-1 path —
    // instead of falsely replaying the winner's result.
    return Optional.of(
        replayRerunResultOrConflict(
            workflowRunId, rawTargetStep, rawReason, idempotencyKey, actor, snapshot));
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringListDetail(WorkflowEventRecord event, String key) {
    Object value = event.details().get(key);
    if (value instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object element : list) {
        if (element != null) {
          result.add(element.toString());
        }
      }
      return List.copyOf(result);
    }
    return List.of();
  }

  private void bestEffortLinearReopenNotification(
      String workflowRunId, WorkflowState targetState, String correlationId) {
    try {
      workflowOrchestrationService.notifyLinearRunReopened(
          workflowRunId, safeStepValueOf(targetState), correlationId);
    } catch (RuntimeException error) {
      // The reopen notification is itself best-effort and swallows internally; this outer guard is
      // defense-in-depth so a proxy/tx-boundary surprise can NEVER fail the already-committed
      // rerun.
      log.warn(
          "recovery rerunFromStep linear reopen notification failed workflowRunId={} errorClass={} — rerun already committed; skipping",
          workflowRunId,
          error.getClass().getSimpleName());
    }
  }

  private record RerunPrep(
      String recoveryActionPublicId,
      String rerunEventPublicId,
      List<String> invalidatedApprovalIds) {}

  private ResumePrep performResumePrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      WorkflowState targetState,
      WorkflowEventRecord pausedEvent,
      String effectiveReason,
      String correlationId) {
    ResumeWorkflowCommand command =
        new ResumeWorkflowCommand(
            workflowRunId,
            actor.actorIdentity(),
            actor.actorType(),
            idempotencyKey,
            correlationId,
            effectiveReason,
            targetState);
    workflowCommandService.resumeWorkflow(command);

    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.TRIGGERING_EVENT_ID, pausedEvent.publicId());
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    // Surface the operator-supplied reason in the audit details (mirror retry F6). Truncation
    // matches ResumeWorkflowCommand's @Size(max = 512).
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_RESUMED,
            WorkflowState.PAUSED,
            targetState,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_RESUME,
                pausedEvent.publicId(),
                recoveryEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_PENDING,
                REVIEWER_ROLE_WORKFLOW_OWNER));

    return new ResumePrep(recoveryAction.publicId(), recoveryEventPublicId);
  }

  /**
   * AC6 re-dispatch: resuming into {@code Executing} re-dispatches the EXECUTION-stage runner via
   * the sub-stage-aware {@code redispatchAfterRetry} (plan vs pr-output; NEVER transitions; returns
   * {@code null} when auto-dispatch is off — tolerate the null). The {@code Investigating} branch —
   * coded defensively by 4.5 when no {@code Investigating → Paused} edge existed — is LIVE since
   * story 4.8 wired {@code Investigating} into {@code PAUSABLE_SOURCE_STATES} (pause cancels the
   * spec runner, so resume must re-dispatch it); {@code PauseResumeRoundTripIT} exercises it.
   * Human-gate priorStates (spec/review/lint/delivery/manual-park) and {@code Failed} carry no
   * runner work to re-dispatch — pause left the gate approval / manual park / failure diagnostics
   * intact, so resume just transitions back.
   */
  private RunnerDispatchResult redispatchForResume(
      String workflowRunId, WorkflowState priorState, String correlationId) {
    if (priorState == WorkflowState.EXECUTING) {
      return workflowOrchestrationService.redispatchAfterRetry(workflowRunId, correlationId);
    }
    if (priorState == WorkflowState.INVESTIGATING) {
      return workflowOrchestrationService.retrySpecGeneration(workflowRunId, correlationId);
    }
    return null;
  }

  private void appendResumeDispatchFailedEvent(
      String workflowRunId,
      WorkflowState targetState,
      ResumePrep prep,
      String idempotencyKey,
      String correlationId,
      String effectiveReason,
      ActorContext actor,
      RuntimeException error,
      boolean compensationFailed) {
    String errorCode =
        (error instanceof DomainException domainError)
            ? domainError.errorCode().value()
            : "RUNTIME_ERROR";
    String errorClass = error.getClass().getSimpleName();
    String dispatchFailedReason = "resume re-dispatch failed: " + errorCode;
    String dispatchFailedEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.RECOVERY_ACTION_ID, prep.recoveryActionPublicId);
    // The recovery-event-id detail key (retry-flavored name) carries the recovery.resumed event id
    // — the only recovery-event-id allow-listed key; the resumed event is this action's anchor.
    eventDetails.put(WorkflowEventDetailKeys.RECOVERY_RETRIED_EVENT_ID, prep.resumedEventPublicId);
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CODE, errorCode);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CLASS, errorClass);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    if (compensationFailed) {
      eventDetails.put(WorkflowEventDetailKeys.COMPENSATION_FAILED, true);
    }
    resultStatusTransactionTemplate.executeWithoutResult(
        status -> {
          workflowEventWritePort.append(
              new WorkflowEventRecord(
                  dispatchFailedEventPublicId,
                  workflowRunId,
                  WorkflowEventType.RECOVERY_DISPATCH_FAILED,
                  targetState,
                  targetState,
                  actor.actorIdentity(),
                  actor.actorType(),
                  dispatchFailedReason,
                  null,
                  true,
                  OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                  eventDetails));
          log.info(
              "recovery resume dispatch-failed event appended workflowRunId={} dispatchFailedEventId={} errorCode={} errorClass={}",
              workflowRunId,
              dispatchFailedEventPublicId,
              errorCode,
              errorClass);
        });
  }

  private Optional<ResumeRecoveryResult> resolveConcurrentResumeReplay(
      String workflowRunId, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())) {
      return Optional.empty();
    }
    if (!ACTION_TYPE_RESUME.equals(snapshot.actionType())) {
      return Optional.empty();
    }
    if (!RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())) {
      return Optional.empty();
    }
    // Defensive: recovery_actions.resulting_event_id FK is `ON DELETE SET NULL` — a succeeded prior
    // row could carry a null event id (the linked event was archived). Emitting a replay with a
    // null event id would mislead callers that assume non-null on replay; propagate the conflict.
    if (snapshot.resultingEventPublicId() == null) {
      log.warn(
          "recovery resume replay-on-conflict refused workflowRunId={} recoveryActionId={} reason=missing_resulting_event_id",
          workflowRunId,
          snapshot.publicId());
      return Optional.empty();
    }
    log.info(
        "recovery resume replay-on-conflict workflowRunId={} recoveryActionId={} idempotencyKey={} priorResultStatus={}",
        workflowRunId,
        snapshot.publicId(),
        idempotencyKey,
        snapshot.resultStatus());
    return Optional.of(
        new ResumeRecoveryResult(
            snapshot.publicId(),
            snapshot.resultingEventPublicId(),
            null,
            resolvePriorExecutingStateForReplay(workflowRunId).orElse(null),
            actor.correlationId(),
            true));
  }

  private Optional<WorkflowState> resolvePriorExecutingStateForReplay(String workflowRunId) {
    return workflowEventReadPort
        .findLatestTransitionToState(workflowRunId, WorkflowState.PAUSED)
        .map(WorkflowEventRecord::priorState);
  }

  private static String resolveResumeReasonText(String reasonText, WorkflowState targetState) {
    if (reasonText != null && !reasonText.isBlank()) {
      return reasonText;
    }
    return "resume to " + targetState.value();
  }

  private record ResumePrep(String recoveryActionPublicId, String resumedEventPublicId) {}

  private ReconcilePrep performReconcilePrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      ReconciliationDecision decision,
      ConflictResolutionView conflict,
      WorkflowState priorState,
      String effectiveReason,
      String correlationId) {
    // Story 4.6 code review (P3): serialize concurrent reconciles on this run FIRST, before the
    // last-conflict read below. Without it, two reconciles on two different conflicts of the same
    // run can each read hasOtherUnresolvedConflicts before the other's markResolved commits, both
    // skip the terminal transition, and leave the run stuck non-terminal with zero conflicts.
    // Tx-scoped advisory lock (released on this prep tx's commit/rollback).
    integrationConflictService.lockRunForReconcile(workflowRunId);
    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();

    // Last-conflict semantics: decide the resulting workflow state before appending the recovery
    // event, while the current conflict is still unresolved. Any unresolved conflict other than the
    // commanded one means this reconcile only closes one row and leaves the run in its current
    // state.
    boolean hasOtherUnresolvedConflicts =
        integrationConflictService
            .listUnresolvedConflicts(ConflictFilter.forRun(workflowRunId))
            .stream()
            .anyMatch(summary -> !conflict.conflictId().equals(summary.conflictId()));
    WorkflowState resultingState =
        hasOtherUnresolvedConflicts ? priorState : WorkflowState.RECONCILED;
    if (!hasOtherUnresolvedConflicts) {
      ReconcileWorkflowCommand command =
          new ReconcileWorkflowCommand(
              workflowRunId,
              actor.actorIdentity(),
              actor.actorType(),
              idempotencyKey,
              correlationId,
              conflict.conflictId(),
              decision,
              effectiveReason);
      workflowCommandService.reconcileWorkflow(command);
    }

    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.CONFLICT_ID, conflict.conflictId());
    eventDetails.put(WorkflowEventDetailKeys.CONFLICT_CATEGORY, conflict.conflictCategory());
    eventDetails.put(WorkflowEventDetailKeys.RECONCILIATION_DECISION, decision.value());
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_RECONCILED,
            priorState,
            resultingState,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    // triggering_event_id is a nullable FK to workflow_events(evt_...); resolve the run's
    // integration.conflictDetected event best-effort (null when unresolvable - AC4/Reconciliation
    // 3). Passing the conflict id (icf_...) here throws invalidPrefix in the adapter's prefix guard
    // and rolls back the entire reconcile prep tx; the conflict id already lives in the event
    // details under CONFLICT_ID.
    String triggeringEventPublicId =
        workflowEventReadPort
            .findLatestByWorkflowRunPublicIdAndEventTypeIn(
                workflowRunId, List.of(WorkflowEventType.INTEGRATION_CONFLICT_DETECTED.value()))
            .map(WorkflowEventRecord::publicId)
            .orElse(null);

    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_RECONCILE,
                triggeringEventPublicId,
                recoveryEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_PENDING,
                REVIEWER_ROLE_WORKFLOW_OWNER));
    integrationConflictService.resolveConflict(
        conflict.conflictId(), workflowRunId, recoveryAction.publicId(), clock.instant());

    return new ReconcilePrep(recoveryAction.publicId(), recoveryEventPublicId, resultingState);
  }

  private ConflictResolutionView validateConflictForReconcile(
      String workflowRunId, String conflictId) {
    ConflictResolutionView view =
        integrationConflictService
            .findConflictForResolution(conflictId)
            .orElseThrow(() -> conflictNotFound(workflowRunId, conflictId));
    if (!workflowRunId.equals(view.workflowRunId())) {
      throw conflictNotFound(workflowRunId, conflictId);
    }
    if (view.resolvedAt() != null) {
      throw new DomainException(
          DomainErrorCode.CONFLICT_ALREADY_RESOLVED,
          "Integration conflict is already resolved: " + conflictId,
          Map.of("conflictId", conflictId, "runId", workflowRunId));
    }
    return view;
  }

  private DomainException conflictNotFound(String workflowRunId, String conflictId) {
    return new DomainException(
        DomainErrorCode.CONFLICT_NOT_FOUND,
        "Integration conflict not found: " + conflictId,
        Map.of("conflictId", conflictId, "runId", workflowRunId));
  }

  private ReconciliationDecision parseDecision(String rawDecision) {
    if (rawDecision == null || rawDecision.isBlank()) {
      throw new DomainException(
          DomainErrorCode.MISSING_RECONCILIATION_DECISION,
          "Missing reconciliation decision",
          Map.of("field", "resolutionDecision"));
    }
    try {
      return ReconciliationDecision.fromValue(rawDecision, "resolutionDecision");
    } catch (DomainException ignored) {
      throw new DomainException(
          DomainErrorCode.INVALID_RECONCILIATION_DECISION,
          "Invalid reconciliation decision: " + rawDecision,
          Map.of("provided", rawDecision));
    }
  }

  private void applyReconcileSideEffect(
      String workflowRunId, ReconciliationDecision decision, ConflictResolutionView view) {
    try {
      if (decision == ReconciliationDecision.ACCEPT_EXTERNAL_STATE) {
        if (ConflictIntegrationTypes.GITHUB_PR.equals(view.integrationType())
            && integrationLinkService != null) {
          integrationLinkService.syncGitHubPr(workflowRunId);
        } else {
          // Documented gap (Task 5 / Reconciliation 6): no external metadata-refresh path exists
          // for
          // Linear (or unknown/null integration types) on accept_external_state — log + skip. The
          // conflict is still resolved and the run still reconciled.
          log.warn(
              "recovery reconcile accept_external_state skipped external refresh (documented gap) workflowRunId={} conflictId={} integrationType={}",
              workflowRunId,
              view.conflictId(),
              view.integrationType());
        }
        return;
      }
      if (decision == ReconciliationDecision.ACCEPT_INTERNAL_STATE) {
        if (ConflictIntegrationTypes.LINEAR.equals(view.integrationType())) {
          workflowOrchestrationService.syncCompletionToLinear(workflowRunId);
        }
        if (ConflictIntegrationTypes.GITHUB_PR.equals(view.integrationType())
            && integrationLinkService != null) {
          integrationLinkService.commentInternalReconcileOnGitHubPr(
              workflowRunId, view.conflictId());
        }
      }
    } catch (RuntimeException error) {
      log.warn(
          "recovery reconcile side-effect failed workflowRunId={} conflictId={} decision={} integrationType={} errorClass={}",
          workflowRunId,
          view.conflictId(),
          decision.value(),
          view.integrationType(),
          error.getClass().getSimpleName());
    }
  }

  private ReconcileRecoveryResult replayReconcileResultOrConflict(
      String workflowRunId,
      String conflictId,
      String rawDecision,
      String idempotencyKey,
      ActorContext actor,
      RecoveryActionSnapshot prior) {
    if (prior.resultingEventPublicId() == null) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    WorkflowEventRecord event =
        workflowEventReadPort.listByWorkflowRunPublicId(workflowRunId, null).stream()
            .filter(candidate -> prior.resultingEventPublicId().equals(candidate.publicId()))
            .findFirst()
            .orElseThrow(() -> idempotencyConflict(idempotencyKey, workflowRunId, prior));
    Object storedConflict = event.details().get(WorkflowEventDetailKeys.CONFLICT_ID);
    if (!conflictId.equals(storedConflict)) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    if (rawDecision != null && !rawDecision.isBlank()) {
      try {
        ReconciliationDecision requested = parseDecision(rawDecision);
        Object storedDecision =
            event.details().get(WorkflowEventDetailKeys.RECONCILIATION_DECISION);
        if (!requested.value().equals(storedDecision)) {
          throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
        }
      } catch (DomainException invalidDecision) {
        if (invalidDecision.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          throw invalidDecision;
        }
        // Preserve replay-first semantics: a malformed decision on a retry does not block replay
        // once the conflict id matches the persisted recovery.reconciled event.
      }
    }
    WorkflowState resultingState =
        event.resultingState() == null ? WorkflowState.RECONCILED : event.resultingState();
    log.info(
        "recovery reconcile replay workflowRunId={} recoveryActionId={} conflictId={} idempotencyKey={}",
        workflowRunId,
        prior.publicId(),
        conflictId,
        idempotencyKey);
    return new ReconcileRecoveryResult(
        prior.publicId(),
        prior.resultingEventPublicId(),
        conflictId,
        resultingState,
        actor.correlationId(),
        true);
  }

  private Optional<ReconcileRecoveryResult> resolveConcurrentReconcileReplay(
      String workflowRunId, String conflictId, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())
        || !ACTION_TYPE_RECONCILE.equals(snapshot.actionType())
        || !RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
        || snapshot.resultingEventPublicId() == null) {
      return Optional.empty();
    }
    return Optional.of(
        replayReconcileResultOrConflict(
            workflowRunId, conflictId, null, idempotencyKey, actor, snapshot));
  }

  private DomainException idempotencyConflict(
      String idempotencyKey, String workflowRunId, RecoveryActionSnapshot prior) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", idempotencyKey);
    details.put("runId", workflowRunId);
    details.put("priorActionType", prior.actionType());
    details.put("priorResultStatus", prior.resultStatus());
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        "Idempotency key is already bound to another recovery attempt",
        details);
  }

  private static String resolveReconcileReasonText(String reasonText) {
    if (reasonText != null && !reasonText.isBlank()) {
      return reasonText;
    }
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Reconcile requires a non-blank reason",
        Map.of("field", "reasonText"));
  }

  private record ReconcilePrep(
      String recoveryActionPublicId,
      String reconciledEventPublicId,
      WorkflowState resultingState) {}

  // Story 4.9 — the entire classify effect: column overwrite (capturing the prior value under FOR
  // UPDATE) → recovery.failureClassified append → recovery_actions insert, all in ONE prep tx.
  // The column write comes FIRST because its returned prior value feeds the event's
  // priorTaxonomyValue detail. result_status='succeeded' on INSERT — nothing runs after this tx
  // commits, so there is no pending→succeeded flip (R16).
  private ClassifyFailurePrep performClassifyFailurePrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      FailureTaxonomyValue taxonomy,
      WorkflowState currentState,
      String triggeringEventPublicId,
      String effectiveReason,
      String correlationId) {
    OffsetDateTime classifiedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    Optional<FailureClassificationRecord> prior =
        workflowRunFailureClassificationPort.applyClassification(
            workflowRunId, taxonomy.value(), classifiedAt, actor.actorIdentity());
    String priorTaxonomyValue =
        prior
            .map(FailureClassificationRecord::taxonomyValue)
            .map(FailureTaxonomyValue::value)
            .orElse(null);

    String classifiedEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.TAXONOMY_VALUE, taxonomy.value());
    if (priorTaxonomyValue != null) {
      eventDetails.put(WorkflowEventDetailKeys.PRIOR_TAXONOMY_VALUE, priorTaxonomyValue);
    }
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    // Non-transition audit event (the audit.logDownloaded shape): priorState == resultingState ==
    // the run's current state (Failed); failureCategory stays null — the taxonomy is the
    // orthogonal HUMAN axis, never the runner-scoped FailureCategory; interventionMarker = true.
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            classifiedEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_FAILURE_CLASSIFIED,
            currentState,
            currentState,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            classifiedAt,
            eventDetails));

    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_CLASSIFY_FAILURE,
                triggeringEventPublicId,
                classifiedEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_SUCCEEDED,
                REVIEWER_ROLE_WORKFLOW_OWNER));

    return new ClassifyFailurePrep(
        recoveryAction.publicId(), classifiedEventPublicId, priorTaxonomyValue);
  }

  private FailureTaxonomyValue parseTaxonomyValue(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      throw new DomainException(
          DomainErrorCode.MISSING_TAXONOMY_VALUE,
          "Missing taxonomy value",
          Map.of("field", "taxonomyValue"));
    }
    try {
      return FailureTaxonomyValue.fromValue(rawValue, "taxonomyValue");
    } catch (DomainException ignored) {
      throw new DomainException(
          DomainErrorCode.INVALID_TAXONOMY_VALUE,
          "Invalid taxonomy value: " + rawValue,
          Map.of("provided", rawValue));
    }
  }

  // Story 4.9 — replay contract: same key + same run + succeeded replays iff the prior
  // recovery.failureClassified event's details.taxonomyValue equals the requested value ("same
  // key + different fingerprint" realized without idempotency_records — mirror
  // replayReconcileResultOrConflict). A blank/omitted requested value does not block replay
  // (replay-first semantics, the reconcile decision-comparison precedent).
  private ClassifyFailureResult replayClassifyFailureResultOrConflict(
      String workflowRunId,
      String rawTaxonomyValue,
      String idempotencyKey,
      ActorContext actor,
      RecoveryActionSnapshot prior) {
    if (prior.resultingEventPublicId() == null) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    WorkflowEventRecord event =
        workflowEventReadPort.listByWorkflowRunPublicId(workflowRunId, null).stream()
            .filter(candidate -> prior.resultingEventPublicId().equals(candidate.publicId()))
            .findFirst()
            .orElseThrow(() -> idempotencyConflict(idempotencyKey, workflowRunId, prior));
    Object storedValue = event.details().get(WorkflowEventDetailKeys.TAXONOMY_VALUE);
    if (rawTaxonomyValue != null
        && !rawTaxonomyValue.isBlank()
        && !rawTaxonomyValue.trim().equals(storedValue)) {
      throw idempotencyConflict(idempotencyKey, workflowRunId, prior);
    }
    Object storedPriorValue = event.details().get(WorkflowEventDetailKeys.PRIOR_TAXONOMY_VALUE);
    log.info(
        "recovery classifyFailure replay workflowRunId={} recoveryActionId={} taxonomyValue={} idempotencyKey={}",
        workflowRunId,
        prior.publicId(),
        storedValue,
        idempotencyKey);
    return new ClassifyFailureResult(
        prior.publicId(),
        prior.resultingEventPublicId(),
        storedValue == null ? null : storedValue.toString(),
        storedPriorValue == null ? null : storedPriorValue.toString(),
        actor.correlationId(),
        true);
  }

  private Optional<ClassifyFailureResult> resolveConcurrentClassifyReplay(
      String workflowRunId, String taxonomyValue, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())
        || !ACTION_TYPE_CLASSIFY_FAILURE.equals(snapshot.actionType())
        || !RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
        || snapshot.resultingEventPublicId() == null) {
      return Optional.empty();
    }
    return Optional.of(
        replayClassifyFailureResultOrConflict(
            workflowRunId, taxonomyValue, idempotencyKey, actor, snapshot));
  }

  private record ClassifyFailurePrep(
      String recoveryActionPublicId, String classifiedEventPublicId, String priorTaxonomyValue) {}

  private RetryPrep performRetryPrep(
      String workflowRunId,
      String idempotencyKey,
      ActorContext actor,
      RunnerStage failedStage,
      WorkflowEventRecord failureEvent,
      String effectiveReason,
      String correlationId) {
    RetryWorkflowCommand command =
        new RetryWorkflowCommand(
            workflowRunId,
            actor.actorIdentity(),
            actor.actorType(),
            idempotencyKey,
            correlationId,
            effectiveReason);
    workflowCommandService.retryWorkflow(command);

    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.FAILED_STAGE, failedStage.value());
    eventDetails.put(WorkflowEventDetailKeys.TRIGGERING_EVENT_ID, failureEvent.publicId());
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    // Surface the operator-supplied reason in the audit details so it survives in
    // workflow_events.details even though `reason` is also stamped on the event row itself
    // (review F6). Truncation matches RetryWorkflowCommand's @Size(max = 512) so a downstream
    // schema gate would catch any drift.
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            workflowRunId,
            WorkflowEventType.RECOVERY_RETRIED,
            WorkflowState.FAILED,
            WorkflowState.EXECUTING,
            actor.actorIdentity(),
            actor.actorType(),
            effectiveReason,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                workflowRunId,
                ACTION_TYPE_RETRY,
                failureEvent.publicId(),
                recoveryEventPublicId,
                actor.actorIdentity(),
                actor.actorType(),
                idempotencyKey,
                RESULT_STATUS_PENDING));

    return new RetryPrep(recoveryAction.publicId(), recoveryEventPublicId);
  }

  private void appendDispatchFailedEvent(
      String workflowRunId,
      RunnerStage failedStage,
      RetryPrep prep,
      String idempotencyKey,
      String correlationId,
      String effectiveReason,
      ActorContext actor,
      RuntimeException error,
      boolean compensationFailed) {
    String errorCode =
        (error instanceof DomainException domainError)
            ? domainError.errorCode().value()
            : "RUNTIME_ERROR";
    String errorClass = error.getClass().getSimpleName();
    String dispatchFailedReason = "broker dispatch failed: " + errorCode;
    String dispatchFailedEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.FAILED_STAGE, failedStage.value());
    eventDetails.put(WorkflowEventDetailKeys.RECOVERY_ACTION_ID, prep.recoveryActionPublicId);
    eventDetails.put(
        WorkflowEventDetailKeys.RECOVERY_RETRIED_EVENT_ID, prep.recoveryRetriedEventPublicId);
    eventDetails.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CODE, errorCode);
    eventDetails.put(WorkflowEventDetailKeys.ERROR_CLASS, errorClass);
    if (correlationId != null) {
      eventDetails.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    if (effectiveReason != null && !effectiveReason.isBlank()) {
      eventDetails.put(WorkflowEventDetailKeys.REASON, effectiveReason);
    }
    if (compensationFailed) {
      eventDetails.put(WorkflowEventDetailKeys.COMPENSATION_FAILED, true);
    }
    resultStatusTransactionTemplate.executeWithoutResult(
        status -> {
          workflowEventWritePort.append(
              new WorkflowEventRecord(
                  dispatchFailedEventPublicId,
                  workflowRunId,
                  WorkflowEventType.RECOVERY_DISPATCH_FAILED,
                  WorkflowState.EXECUTING,
                  WorkflowState.EXECUTING,
                  actor.actorIdentity(),
                  actor.actorType(),
                  dispatchFailedReason,
                  null,
                  true,
                  OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                  eventDetails));
          log.info(
              "recovery dispatch-failed event appended workflowRunId={} dispatchFailedEventId={} errorCode={} errorClass={}",
              workflowRunId,
              dispatchFailedEventPublicId,
              errorCode,
              errorClass);
        });
  }

  private Optional<RetryRecoveryResult> resolveConcurrentReplay(
      String workflowRunId, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      // The conflict came from somewhere other than recovery_actions (e.g. the inner
      // retryWorkflow's idempotency_records row), and the sibling thread either has not
      // committed yet or rolled back. Surface the original conflict.
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!workflowRunId.equals(snapshot.workflowRunPublicId())) {
      return Optional.empty();
    }
    if (!RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())) {
      return Optional.empty();
    }
    // Defensive: recovery_actions.resulting_event_id FK is `ON DELETE SET NULL`, so a
    // succeeded prior row could carry a null event public id (the linked event was archived /
    // deleted). Treat that as not-a-valid-replay and propagate the conflict — emitting a
    // replay result with a null event id would NPE callers that assume non-null on replay.
    // (review E13 / 2026-05-16)
    if (snapshot.resultingEventPublicId() == null) {
      log.warn(
          "recovery retry replay-on-conflict refused workflowRunId={} recoveryActionId={} reason=missing_resulting_event_id",
          workflowRunId,
          snapshot.publicId());
      return Optional.empty();
    }
    log.info(
        "recovery retry replay-on-conflict workflowRunId={} recoveryActionId={} idempotencyKey={} priorResultStatus={}",
        workflowRunId,
        snapshot.publicId(),
        idempotencyKey,
        snapshot.resultStatus());
    return Optional.of(
        new RetryRecoveryResult(
            snapshot.publicId(),
            snapshot.resultingEventPublicId(),
            null,
            null,
            actor.correlationId(),
            true));
  }

  private static String resolveReasonText(String reasonText, RunnerStage failedStage) {
    if (reasonText != null && !reasonText.isBlank()) {
      return reasonText;
    }
    return "retry from failed " + failedStage.value();
  }

  @Transactional(readOnly = true)
  public FailureDescription describeFailure(String workflowRunId) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      log.info("describe failure workflowRunId={}", workflowRunId);
      WorkflowRunSnapshot run =
          workflowRunReadPort
              .findByPublicId(workflowRunId)
              .orElseThrow(() -> runNotFound(workflowRunId));

      String diagnosticReferenceCorrelationId =
          workflowEventReadPort.findLatestCorrelationId(workflowRunId).orElse(null);

      if (run.currentState() != WorkflowState.FAILED) {
        String safeAction =
            TERMINAL_STATES.contains(run.currentState())
                ? NEXT_SAFE_ACTION_VIEW_ONLY
                : NEXT_SAFE_ACTION_AWAIT_OUTCOME;
        log.info(
            "describe failure resolved workflowRunId={} currentState={} failedStage=null nextSafeAction={}",
            workflowRunId,
            run.currentState().value(),
            safeAction);
        return new FailureDescription(
            workflowRunId,
            run.currentState(),
            null,
            null,
            null,
            null,
            null,
            safeAction,
            diagnosticReferenceCorrelationId);
      }

      Optional<WorkflowEventRecord> failureEvent =
          workflowEventReadPort.findLatestFailureEvent(workflowRunId);
      Optional<RunnerExecutionSnapshot> lastFailedRunner = findLastFailedRunner(workflowRunId);
      Optional<RunnerExecutionSnapshot> latestRunner = findLatestRunner(workflowRunId);

      String failedStage = lastFailedRunner.map(snapshot -> snapshot.stage().value()).orElse(null);
      String lastSuccessfulStage =
          failureEvent
              .map(event -> event.priorState() == null ? null : event.priorState().value())
              .orElse(null);
      // Normalize timestamps to UTC for byte-equal text/JSON output across the CLI surfaces.
      // The recovery.retried event already normalizes via withOffsetSameInstant(ZoneOffset.UTC)
      // at append time; describing a Failed run after the fact should produce the same offset
      // regardless of which session timezone the JPA mapper hands us. (review E8 / 2026-05-16)
      OffsetDateTime failureTimestamp =
          failureEvent
              .map(WorkflowEventRecord::createdAt)
              .map(t -> t.withOffsetSameInstant(ZoneOffset.UTC))
              .orElse(null);
      String failureCategory =
          failureEvent
              .map(
                  event -> event.failureCategory() == null ? null : event.failureCategory().value())
              .orElse(null);
      // AC5: lastActivityTimestamp is the last_activity_at of the most recent runner_executions
      // row for the run REGARDLESS of status, falling back to the failure timestamp when no
      // runner_execution exists (review F5). Also normalized to UTC (review E8).
      OffsetDateTime lastActivityTimestamp =
          latestRunner
              .map(RunnerExecutionSnapshot::lastActivityAt)
              .map(t -> t.withOffsetSameInstant(ZoneOffset.UTC))
              .orElse(failureTimestamp);

      // Match the retry path's preconditions so the CLI never recommends `retry` on a Failed
      // run that retry would itself reject. `retry` throws RETRY_NOT_APPLICABLE when either the
      // failure event OR the last failed runner_execution is missing (Steps 3 and 4); recommend
      // `await_manual_reconciliation` in those branches to surface the inconsistency to the
      // operator instead of telling them to run a command that will error.
      // (review E5 + E6 / 2026-05-16)
      boolean retryWouldBeRejected = failureEvent.isEmpty() || lastFailedRunner.isEmpty();
      // A git push rejected for a missing `workflow` token scope is a permission wall — retrying
      // with the same token hits it identically. The broker records the git category in the failure
      // event details (RunnerBroker#appendGitPushFailedEvent); when it is the non-retryable
      // workflow-scope category, recommend manual remediation instead of `retry` even though the
      // coarse FailureCategory is the generic RUNNER_CONTRACT_VIOLATION.
      boolean nonRetryableGitFailure =
          failureEvent
              .map(
                  event ->
                      IntegrationFailureCategory.GIT_WORKFLOW_SCOPE_MISSING
                          .value()
                          .equals(event.details().get("gitFailureCategory")))
              .orElse(false);
      String safeAction;
      if (retryWouldBeRejected
          || nonRetryableGitFailure
          || artifactOperationPort.hasFailedOrFailedOrphanForRun(workflowRunId)) {
        safeAction = NEXT_SAFE_ACTION_AWAIT_MANUAL_RECONCILIATION;
      } else {
        safeAction = NEXT_SAFE_ACTION_RETRY;
      }

      log.info(
          "describe failure resolved workflowRunId={} currentState={} failedStage={} nextSafeAction={}",
          workflowRunId,
          run.currentState().value(),
          failedStage,
          safeAction);
      return new FailureDescription(
          workflowRunId,
          run.currentState(),
          failedStage,
          lastSuccessfulStage,
          failureTimestamp,
          failureCategory,
          lastActivityTimestamp,
          safeAction,
          diagnosticReferenceCorrelationId);
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private Optional<RunnerExecutionSnapshot> findLastFailedRunner(String workflowRunId) {
    return pickLatest(
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, FAILED_RUNNER_STATUSES));
  }

  private Optional<RunnerExecutionSnapshot> findLatestRunner(String workflowRunId) {
    return pickLatest(
        runnerExecutionRecordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, ALL_RUNNER_STATUSES));
  }

  private static Optional<RunnerExecutionSnapshot> pickLatest(
      List<RunnerExecutionSnapshot> snapshots) {
    return snapshots.stream()
        .max(
            Comparator.comparing(
                    RunnerExecutionSnapshot::createdAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                    RunnerExecutionSnapshot::publicId,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
  }

  private static DomainException runNotFound(String workflowRunPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunPublicId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunPublicId, details);
  }

  private record RetryPrep(String recoveryActionPublicId, String recoveryRetriedEventPublicId) {}
}
