package org.dradgo.application.workflow;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.approval.ApprovalResult;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.approval.TechnicalApprovalService;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleResult;
import org.dradgo.application.clarification.ClarificationLifecycleService;
import org.dradgo.application.clarification.ClarificationResult;
import org.dradgo.application.clarification.ClarificationService;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.project.DefaultProjectSeeder;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.AcceptClarificationCommand;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveDeliveryCommand;
import org.dradgo.application.workflow.commands.ApproveLintCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.PauseWorkflowCommand;
import org.dradgo.application.workflow.commands.ReconcileWorkflowCommand;
import org.dradgo.application.workflow.commands.RegenerateSpecCommand;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RequestLintFixCommand;
import org.dradgo.application.workflow.commands.RerunFromStepWorkflowCommand;
import org.dradgo.application.workflow.commands.ResumeWorkflowCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.PushMode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowCommandService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowCommandService.class);

  /**
   * P10 — replay-ref separator switched from {@code "|"} (which is a legal char in workflow run
   * public ids and state names per a future regex relaxation) to ASCII Unit Separator {@code
   * U+001F}. The character is disallowed in every component of the resultRef (run-public-id and
   * state-value regexes both ban control characters), so the indexOf split is guaranteed
   * unambiguous. The parser remains backward-compatible with legacy {@code "|"}-encoded refs
   * already persisted in {@code idempotency_records.result_ref}; new writes use {@code U+001F}.
   */
  private static final String CLARIFICATION_REPLAY_REF_SEPARATOR = "";

  private static final String LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR = "|";

  /**
   * Sentinel surfaced for clarification idempotent replays whose underlying row was hard-deleted
   * before the replay arrived (story-2.13 round-3 decision D-Round3-1). Preserves the
   * idempotent-replay-never-fails contract for previously-successful 200s — callers see {@code
   * clarificationStatus="unknown"} instead of a 500. Only ever surfaced for the legacy 2-segment
   * replay-ref population; fresh writes embed the real status in the ref.
   */
  static final String LEGACY_CLARIFICATION_REPLAY_STATUS_UNKNOWN = "unknown";

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowRunCreatePort workflowRunCreatePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final WorkflowTransitionService workflowTransitionService;
  private final Validator validator;
  private final IdempotencyService idempotencyService;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final WorkflowCommandFingerprintFactory fingerprintFactory;
  private final IntegrationLinkService integrationLinkService;
  private final ApprovalService approvalService;
  private final TechnicalApprovalService technicalApprovalService;
  // Story 3h-2 (AC5) — the pre-review lint-gate operator-action executors.
  private final LintApprovalService lintApprovalService;
  private final DeliveryApprovalService deliveryApprovalService;
  private final ClarificationService clarificationService;
  // Story 3e-2 (AC1) — canonical executor for accept_clarification (answered -> accepted).
  private final ClarificationLifecycleService clarificationLifecycleService;
  private final ClarificationReadPort clarificationReadPort;
  // Story 3a-1 (Task 5 / AC1) — auto-dispatch the spec runner once a submitted run is created.
  private final WorkflowOrchestrationService workflowOrchestrationService;
  // Story 3e-2 (AC2) — bump spec_rejection_loop_count before retrySpecGeneration so each
  // regenerate mints a fresh deterministic dispatch key (mirrors ApprovalService.rejectSpec).
  private final WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort;
  // Story 3c-6 (AC2) — resolve the default project to bind every new run to at create time.
  private final ProjectStore projectStore;
  // Story 3h-4 (AC7) — resolve the run's push mode so approve_lint's replay pin reflects the
  // actual
  // mode-dependent post-state (WaitingForReview when auto, WaitingForDelivery otherwise).
  private final org.dradgo.application.project.ProjectRuntimeConfigResolver
      projectRuntimeConfigResolver;
  private final TransactionTemplate failureCompletionTemplate;
  private static final int REPLAY_LOOKUP_ATTEMPTS = 200;
  private static final long REPLAY_LOOKUP_DELAY_MS = 10L;

  public WorkflowCommandService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowRunCreatePort workflowRunCreatePort,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowTransitionService workflowTransitionService,
      Validator validator,
      PlatformTransactionManager transactionManager,
      IdempotencyService idempotencyService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      WorkflowCommandFingerprintFactory fingerprintFactory,
      IntegrationLinkService integrationLinkService,
      ApprovalService approvalService,
      TechnicalApprovalService technicalApprovalService,
      LintApprovalService lintApprovalService,
      DeliveryApprovalService deliveryApprovalService,
      ClarificationService clarificationService,
      ClarificationLifecycleService clarificationLifecycleService,
      ClarificationReadPort clarificationReadPort,
      WorkflowOrchestrationService workflowOrchestrationService,
      WorkflowRunRejectionLoopPort workflowRunRejectionLoopPort,
      ProjectStore projectStore,
      org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.workflowRunCreatePort = workflowRunCreatePort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.workflowTransitionService = workflowTransitionService;
    this.validator = validator;
    this.failureCompletionTemplate = requiresNewTemplate(transactionManager);
    this.idempotencyService = idempotencyService;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
    this.fingerprintFactory = fingerprintFactory;
    this.integrationLinkService = integrationLinkService;
    this.approvalService = approvalService;
    this.technicalApprovalService = technicalApprovalService;
    this.lintApprovalService = lintApprovalService;
    this.deliveryApprovalService = deliveryApprovalService;
    this.clarificationService = clarificationService;
    this.clarificationLifecycleService = clarificationLifecycleService;
    this.clarificationReadPort = clarificationReadPort;
    this.workflowOrchestrationService = workflowOrchestrationService;
    this.workflowRunRejectionLoopPort = workflowRunRejectionLoopPort;
    this.projectStore = projectStore;
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
  }

  @Transactional
  public SubmitWorkflowResult submit(SubmitWorkflowCommand command) {
    return executeIdempotent(command, this::submitInternal, this::replaySubmit);
  }

  @Transactional
  public WorkflowStateChangeResult approveSpec(ApproveSpecCommand command) {
    return executeIdempotent(command, this::approveSpecInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult rejectSpec(RejectSpecCommand command) {
    return executeIdempotent(command, this::rejectSpecInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult acceptImplementation(AcceptImplementationCommand command) {
    // Story 3.20: technical-approval twin of approveSpec. The approval row insert +
    // approval.approved event append + transition (+ implementation-plan dispatch) all happen
    // inside TechnicalApprovalService, participating in this method's @Transactional boundary. The
    // legacy contract returns WorkflowStateChangeResult; story 3.23 will expose the richer
    // ApprovalResult through the REST surface. Replay is special-cased
    // (replayAcceptImplementation):
    // the resulting state depends on the artifact type, so it cannot be hard-coded (Trap T2).
    return executeIdempotent(
        command, this::acceptImplementationInternal, this::replayAcceptImplementation);
  }

  @Transactional
  public WorkflowStateChangeResult rejectImplementation(RejectImplementationCommand command) {
    // Story 3.21: technical-rejection twin of rejectSpec. The rejection row insert +
    // approval.rejected
    // event append + counter increment + optional escalation.required event + transition + runner
    // re-dispatch all happen inside TechnicalApprovalService, participating in this method's
    // @Transactional boundary. The legacy contract returns WorkflowStateChangeResult; story 3.24
    // will expose the richer ApprovalResult through the REST surface. Replay resolves to EXECUTING
    // for BOTH artifact kinds (Decision D3), so the generic replayStateChange hard-codes it (unlike
    // acceptImplementation, whose target is artifact-type-dependent).
    return executeIdempotent(command, this::rejectImplementationInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult answerClarification(SubmitClarificationCommand command) {
    // Story 2.11: the clarification row UPDATE + clarification.answered event append all happen
    // inside ClarificationService, participating in this method's @Transactional boundary. The
    // legacy contract still returns WorkflowStateChangeResult (story 2.13 will rebuild the surface
    // to expose ClarificationResult directly). OQ-1 resolved as recommended: idempotency wired
    // here in 2.11 so replays land correctly end-to-end.
    return executeIdempotent(
        command,
        this::answerClarificationInternal,
        this::replayStateChange,
        this::clarificationReplayRef);
  }

  @Transactional
  public WorkflowStateChangeResult acceptClarification(AcceptClarificationCommand command) {
    // Story 3e-2 (AC1): the answered -> accepted row UPDATE + clarification.accepted event append
    // happen inside ClarificationLifecycleService.markAccepted, participating in this method's
    // @Transactional boundary. Twin of answerClarification: accepting does NOT advance workflow
    // state (the run stays WaitingForSpecApproval), so the replay ref carries the same
    // (runId, state, clarificationStatus) triple as the answer path.
    return executeIdempotent(
        command,
        this::acceptClarificationInternal,
        this::replayStateChange,
        this::clarificationReplayRef);
  }

  @Transactional
  public WorkflowStateChangeResult regenerateSpecWithClarifications(RegenerateSpecCommand command) {
    // Story 3e-2 (AC2): structural twin of rejectSpec — transition WaitingForSpecApproval ->
    // Investigating then re-dispatch the spec runner, all inside this @Transactional boundary so a
    // dispatch failure rolls back the transition + loop-count bump. Replay pins INVESTIGATING (the
    // original command's post-state), mirroring rejectSpec.
    return executeIdempotent(
        command, this::regenerateSpecWithClarificationsInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult retryWorkflow(RetryWorkflowCommand command) {
    return executeIdempotent(command, this::retryWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult takeoverWorkflow(TakeoverWorkflowCommand command) {
    return executeIdempotent(command, this::takeoverWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult resumeWorkflow(ResumeWorkflowCommand command) {
    // Story 4.5 (AC5 / Reconciliation 8): transition Paused → command.targetState() (the
    // recovered
    // prior executing state) inside the shared idempotency envelope. Unlike retryWorkflow, this
    // does
    // NOT re-dispatch here — re-dispatch is single-sourced by RecoveryService.resume after the
    // prep
    // tx commits (the double-dispatch caution). Replay pins targetState (an invariant post-state
    // per accepted command) in replayStateChange.
    return executeIdempotent(command, this::resumeWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult pauseWorkflow(PauseWorkflowCommand command) {
    // Story 4.8 (AC7 / Reconciliation 9): transition <pausable source> → Paused inside the shared
    // idempotency envelope. The target is the CONSTANT Paused (no targetState on the command,
    // unlike
    // resume's derived one); the transition table validates the source edge and raises
    // ILLEGAL_TRANSITION for unwired sources. Transition-ONLY: the runner cancel-flips + the
    // recovery.paused event + the recovery_actions row live in RecoveryService.pause's prep tx
    // (which this @Transactional REQUIRED joins), and the post-commit docker stop is single-sourced
    // there. Replay pins PAUSED (the invariant post-state) in replayStateChange.
    return executeIdempotent(command, this::pauseWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult reconcileWorkflow(ReconcileWorkflowCommand command) {
    return executeIdempotent(command, this::reconcileWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult rerunFromStepWorkflow(RerunFromStepWorkflowCommand command) {
    // Story 4.7 (AC4/AC5 / Reconciliation 9): transition to the caller-chosen targetState (the
    // safe step boundary — Investigating/Executing) inside the shared idempotency envelope. Unlike
    // retryWorkflow this does NOT re-enqueue here — the runner re-enqueue is single-sourced by
    // RecoveryService.rerunFromStep after the prep tx commits (the double-dispatch caution). Replay
    // pins targetState (an invariant post-state per accepted command) in replayStateChange.
    return executeIdempotent(command, this::rerunFromStepWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult approveLint(ApproveLintCommand command) {
    // Story 3h-2 (AC5): the resume-tail deferral is registered inside LintApprovalService,
    // participating in this method's @Transactional idempotency boundary. Replay pins
    // WaitingForReview (the invariant post-state).
    return executeIdempotent(
        command,
        this::approveLintInternal,
        this::replayStateChange,
        result -> stateChangeReplayRef(result.workflowRunId(), result.currentState()));
  }

  @Transactional
  public WorkflowStateChangeResult requestLintFix(RequestLintFixCommand command) {
    // Story 3h-2 (AC5): the counter bump + escalation + transition + re-dispatch happen inside
    // LintApprovalService, participating in this method's @Transactional boundary (a re-dispatch
    // failure rolls back the counter bump + transition). Replay pins Executing.
    return executeIdempotent(command, this::requestLintFixInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult approveDelivery(ApproveDeliveryCommand command) {
    // Story 3h-4 (AC4/AC7): the WaitingForDelivery -> WaitingForReview transition (+ the manual
    // audit event) happen synchronously inside DeliveryApprovalService, participating in this
    // method's @Transactional idempotency boundary; the workspace-coupled push resume / reviewer
    // enqueue are deferred post-commit there. Replay pins WaitingForReview (the invariant
    // post-state) so a replayed approve short-circuits without re-pushing / double-creating the PR.
    return executeIdempotent(command, this::approveDeliveryInternal, this::replayStateChange);
  }

  private SubmitWorkflowResult submitInternal(SubmitWorkflowCommand command) {
    // Story 3c-7 (AC1) — resolve the project to bind this run to BEFORE create so the run row is
    // never null at insert. An explicit projectReference (slug or `prj_` id) resolves the named
    // project (else PROJECT_NOT_FOUND); absent, the 3c-6 reserved `default` project stands. A
    // create
    // that cannot resolve a project is rejected with the registered PROJECT_NOT_FOUND (R8).
    ProjectBinding projectBinding = resolveProjectBinding(command.projectReference());
    String projectId = projectBinding.projectId();

    // The create path, initial event append, and integration_link creation must stay inside the
    // surrounding @Transactional boundary so they commit or roll back together. If linking the
    // source ticket fails (LINEAR_TICKET_NOT_FOUND / INTEGRATION_LINK_CONFLICT / adapter
    // failure), the workflow_run row is rolled back too — the run never existed.
    var workflowRun =
        workflowRunCreatePort.create(
            PublicIdPrefixes.WORKFLOW_RUN.next(), WorkflowState.INBOX, projectId);
    if (workflowRun.currentState() != WorkflowState.INBOX) {
      throw new IllegalStateException(
          "Workflow run create port must return an INBOX run, but returned "
              + workflowRun.currentState());
    }
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRun.publicId());
    try {
      log.info(
          "binding new run {} to project {} source={}",
          workflowRun.publicId(),
          projectId,
          projectBinding.source());
      Map<String, Object> details = baseDetails(command);
      details.put("linearTicketReference", command.linearTicketReference());
      workflowEventWritePort.append(
          new WorkflowEventRecord(
              PublicIdPrefixes.WORKFLOW_EVENT.next(),
              workflowRun.publicId(),
              WorkflowEventType.WORKFLOW_STATE_CHANGED,
              null,
              WorkflowState.INBOX,
              command.actorIdentity(),
              command.actorType(),
              "workflow submitted",
              null,
              false,
              OffsetDateTime.now(ZoneOffset.UTC),
              details));

      integrationLinkService.linkTicketWithinTransaction(
          workflowRun.publicId(),
          command.linearTicketReference(),
          new ActorContext(
              command.actorIdentity(),
              command.actorType(),
              normalizeOptional(command.correlationId())));

      // Story 3a-1 (AC1 / Task 5) — auto-dispatch the spec runner. This shares the submit
      // transaction (Dev Notes §"Transaction & async boundary"): the run + Linear link + the
      // Inbox->Investigating transition + the runner_executions row commit or roll back together,
      // so a dispatch failure unwinds the submit. The async runner RESULT arrives later via the
      // poller (a separate transaction). No-op when spec-stage.auto-dispatch=false (test profile),
      // which leaves the run in Inbox.
      workflowOrchestrationService.dispatchSpecGeneration(
          workflowRun.publicId(), normalizeOptional(command.correlationId()));

      // Story 3a-1 review finding P4 — report the run's ACTUAL committed state, not the stale
      // just-created Inbox value: when auto-dispatch advanced the run to Investigating in THIS
      // transaction the caller must see Investigating. Re-read within the transaction
      // (auto-dispatch
      // disabled leaves it Inbox; enabled => Investigating).
      //
      // The re-read is purely cosmetic — it only populates the response's currentState. Guard it
      // so
      // a transient read failure cannot escape and roll back this @Transactional submit (the
      // created
      // run + Linear link + Inbox->Investigating transition + dispatched runner_executions row all
      // committed together). Fall back to the just-created state on an empty result OR a read
      // error.
      WorkflowState resultingState;
      try {
        resultingState =
            workflowRunReadPort
                .findByPublicId(workflowRun.publicId())
                .map(WorkflowRunSnapshot::currentState)
                .orElse(workflowRun.currentState());
      } catch (RuntimeException readError) {
        resultingState = workflowRun.currentState();
      }
      return new SubmitWorkflowResult(
          workflowRun.publicId(), resultingState, normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /** The resolved project id bound at create + the resolution source (for the INFO bind log). */
  private record ProjectBinding(String projectId, String source) {}

  /**
   * Story 3c-7 (AC1) — generalize the 3c-6 hardcoded {@code findBySlug(default)} bind. An explicit
   * {@code projectReference} resolves the named project: a {@code prj_}-prefixed reference via
   * {@link ProjectStore#findByPublicId}, otherwise via {@link ProjectStore#findBySlug}; an
   * unresolvable explicit reference is rejected with the consume-only {@code PROJECT_NOT_FOUND}. An
   * absent reference keeps the reserved {@code default}-project fallback (byte-identical to 3c-6).
   */
  private ProjectBinding resolveProjectBinding(String projectReference) {
    if (projectReference != null && !projectReference.isBlank()) {
      String reference = projectReference.trim();
      java.util.Optional<Project> resolved =
          reference.startsWith(PublicIdPrefixes.PROJECT.prefix())
              ? projectStore.findByPublicId(reference)
              : projectStore.findBySlug(reference);
      String projectId =
          resolved
              .map(Project::publicId)
              .orElseThrow(
                  () -> {
                    log.warn(
                        "submit rejected: project reference did not resolve projectReference={}",
                        MdcKeys.sanitizeForLog(reference));
                    return new DomainException(
                        DomainErrorCode.PROJECT_NOT_FOUND,
                        "no project resolved for reference " + reference);
                  });
      return new ProjectBinding(projectId, "explicit");
    }
    String defaultProjectId =
        projectStore
            .findBySlug(DefaultProjectSeeder.DEFAULT_PROJECT_SLUG)
            .map(Project::publicId)
            .orElseThrow(
                () -> {
                  log.warn("submit rejected: no default project resolved for run creation");
                  return new DomainException(
                      DomainErrorCode.PROJECT_NOT_FOUND,
                      "no default project resolved for run creation");
                });
    return new ProjectBinding(defaultProjectId, "default");
  }

  private WorkflowStateChangeResult approveSpecInternal(ApproveSpecCommand command) {
    // Story 2.9: the approval row insert + approval.approved event append + transition all
    // happen inside ApprovalService, participating in this method's @Transactional boundary.
    // The legacy REST/CLI contract still returns WorkflowStateChangeResult (story 2.13 will
    // rebuild the surface to expose the richer ApprovalResult directly).
    ApprovalResult approvalResult = approvalService.approveSpec(command);
    return new WorkflowStateChangeResult(
        approvalResult.workflowRunId(),
        approvalResult.resultingState(),
        approvalResult.correlationId());
  }

  private WorkflowStateChangeResult rejectSpecInternal(RejectSpecCommand command) {
    // Story 2.10: the approval row insert (decision=rejected) + approval.rejected event append +
    // counter increment + optional escalation.required event + transition all happen inside
    // ApprovalService, participating in this method's @Transactional boundary. The legacy
    // REST/CLI contract still returns WorkflowStateChangeResult (story 2.13 will rebuild the
    // surface to expose the richer ApprovalResult directly). This replaces the prior minimal
    // stub that only emitted a workflow.stateChanged transition with no approvals row, no
    // approval.rejected event, no counter increment, and no taxonomy (story 2.10 trap T2).
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      ApprovalResult approvalResult = approvalService.rejectSpec(command);
      return new WorkflowStateChangeResult(
          approvalResult.workflowRunId(),
          approvalResult.resultingState(),
          approvalResult.correlationId());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult acceptImplementationInternal(
      AcceptImplementationCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      ApprovalResult approvalResult = technicalApprovalService.acceptImplementation(command);
      return new WorkflowStateChangeResult(
          approvalResult.workflowRunId(),
          approvalResult.resultingState(),
          approvalResult.correlationId());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult rejectImplementationInternal(
      RejectImplementationCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      ApprovalResult approvalResult = technicalApprovalService.rejectImplementation(command);
      return new WorkflowStateChangeResult(
          approvalResult.workflowRunId(),
          approvalResult.resultingState(),
          approvalResult.correlationId());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult approveLintInternal(ApproveLintCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      requireParkedAtLintGate(command.workflowRunId(), "approve_lint");
      WorkflowState resultingState = lintApprovalService.approveLint(command);
      return new WorkflowStateChangeResult(
          command.workflowRunId(), resultingState, normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult requestLintFixInternal(RequestLintFixCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      requireParkedAtLintGate(command.workflowRunId(), "request_lint_fix");
      WorkflowState resultingState = lintApprovalService.requestLintFix(command);
      return new WorkflowStateChangeResult(
          command.workflowRunId(), resultingState, normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult approveDeliveryInternal(ApproveDeliveryCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      requireParkedAtDeliveryGate(command.workflowRunId(), "approve_delivery");
      WorkflowState resultingState = deliveryApprovalService.approveDelivery(command);
      return new WorkflowStateChangeResult(
          command.workflowRunId(), resultingState, normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  /**
   * Story 3h-2 (code-review 2026-07-06 re-review) — the executor gate for the pre-review lint-gate
   * actions ({@code approve_lint} / {@code request_lint_fix}). These are surfaced ONLY at {@code
   * WaitingForLintApproval}, so — mirroring the {@code RETRY_NOT_APPLICABLE} / {@code
   * REGENERATE_NOT_APPLICABLE} precedent (there is NO generic ACTION_NOT_ALLOWED guard) — the
   * executor is the gate. This precondition is LOAD-BEARING, not defensive: both action targets are
   * ALSO reachable from other live sources ({@code EXECUTING -> WaitingForReview} is the normal
   * delivery tail; {@code WaitingForReview -> Executing} is reject-implementation), so
   * transition-table legality ALONE does NOT reject a wrong-state call. Without this guard a
   * wrong-state {@code approve_lint} (e.g. on a still-{@code EXECUTING} run — stale UI, double
   * submit, direct API) would transition + push a mid-execution workspace, and a wrong-state {@code
   * request_lint_fix} on a {@code WaitingForReview} run would hijack it back into {@code
   * Executing}. Reject (ILLEGAL_TRANSITION → 409, non-retryable) unless the run is actually parked
   * at the gate.
   */
  private void requireParkedAtLintGate(String workflowRunId, String action) {
    WorkflowState currentState =
        workflowRunReadPort
            .findByPublicId(workflowRunId)
            .map(WorkflowRunSnapshot::currentState)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUN_NOT_FOUND,
                        "Workflow run not found: " + workflowRunId,
                        Map.of("runId", workflowRunId)));
    if (currentState != WorkflowState.WAITING_FOR_LINT_APPROVAL) {
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Lint-gate action '"
              + action
              + "' requires the run to be parked at WaitingForLintApproval",
          Map.of(
              "runId",
              workflowRunId,
              "action",
              action,
              "currentState",
              currentState.value(),
              "requiredState",
              WorkflowState.WAITING_FOR_LINT_APPROVAL.value()));
    }
  }

  /**
   * Story 3h-4 (AC7) — the executor gate for {@code approve_delivery}, surfaced ONLY at {@code
   * WaitingForDelivery}. Mirrors {@link #requireParkedAtLintGate}: LOAD-BEARING, not defensive,
   * because the action's target ({@code WaitingForReview}) is reachable from other live sources
   * (the normal delivery tail {@code EXECUTING -> WaitingForReview}, the lint gate {@code
   * WaitingForLintApproval -> WaitingForReview}), so transition-table legality ALONE does NOT
   * reject a wrong-state call. Without this guard a wrong-state {@code approve_delivery} (stale UI,
   * double submit, direct API on a still-{@code EXECUTING} run) would transition + push. Reject
   * (ILLEGAL_TRANSITION → 409, non-retryable) unless the run is actually parked at the delivery
   * gate.
   */
  private void requireParkedAtDeliveryGate(String workflowRunId, String action) {
    WorkflowState currentState =
        workflowRunReadPort
            .findByPublicId(workflowRunId)
            .map(WorkflowRunSnapshot::currentState)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUN_NOT_FOUND,
                        "Workflow run not found: " + workflowRunId,
                        Map.of("runId", workflowRunId)));
    if (currentState != WorkflowState.WAITING_FOR_DELIVERY) {
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Delivery-gate action '"
              + action
              + "' requires the run to be parked at WaitingForDelivery",
          Map.of(
              "runId",
              workflowRunId,
              "action",
              action,
              "currentState",
              currentState.value(),
              "requiredState",
              WorkflowState.WAITING_FOR_DELIVERY.value()));
    }
  }

  private WorkflowStateChangeResult answerClarificationInternal(
      SubmitClarificationCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // Story 2.13 round-4 P-R4-4: read currentState FIRST so the response reflects the workflow
      // state at the START of the answer operation, not whatever a concurrent reject/takeover/retry
      // happened to commit during the answer write. Trap T6 says answering does NOT advance state
      // —
      // if the read came after the clarification write, a concurrent state-change could surface as
      // the answer's "post-mutation" state and contradict AC9. Both calls sit inside the outer
      // @Transactional answerClarification, so READ COMMITTED still sees the latest committed value
      // at this point, but the resulting currentState now belongs to the snapshot the answer
      // started from — not to an unrelated concurrent transition.
      WorkflowState currentState =
          workflowRunReadPort
              .findByPublicId(command.workflowRunId())
              .map(WorkflowRunSnapshot::currentState)
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.RUN_NOT_FOUND,
                          "Workflow run not found: " + command.workflowRunId(),
                          Map.of("runId", command.workflowRunId())));
      ClarificationResult result = clarificationService.submitAnswer(command);
      return new WorkflowStateChangeResult(
          result.workflowRunId(), currentState, result.correlationId(), result.status());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult acceptClarificationInternal(
      AcceptClarificationCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // Story 3e-2 (AC1): read currentState FIRST (mirror answerClarificationInternal trap T6) so
      // the response reflects the run state at the START of the accept — accepting does NOT
      // advance
      // workflow state, so a concurrent reject/takeover must not surface as the accept's
      // post-mutation state.
      WorkflowState currentState =
          workflowRunReadPort
              .findByPublicId(command.workflowRunId())
              .map(WorkflowRunSnapshot::currentState)
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.RUN_NOT_FOUND,
                          "Workflow run not found: " + command.workflowRunId(),
                          Map.of("runId", command.workflowRunId())));
      ClarificationLifecycleResult result =
          clarificationLifecycleService.markAccepted(
              command.workflowRunId(),
              command.clarificationId(),
              new ActorContext(
                  command.actorIdentity(),
                  command.actorType(),
                  normalizeOptional(command.correlationId())));
      return new WorkflowStateChangeResult(
          result.workflowRunId(),
          currentState,
          normalizeOptional(command.correlationId()),
          result.status());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult regenerateSpecWithClarificationsInternal(
      RegenerateSpecCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // (review D1) Gate FIRST: a spec rebuild only makes sense when there is >=1 `accepted`
      // clarification to incorporate. The REGENERATE_SPEC action is surfaced unconditionally at
      // WaitingForSpecApproval (WorkflowInspectionService.computeActionMatrix), so the executor is
      // the gate (mirrors the RETRY_NOT_APPLICABLE / ARCHIVE_NOT_APPLICABLE precedent — no
      // generic
      // ACTION_NOT_ALLOWED). Reject BEFORE the loop-count bump / transition / re-dispatch so a
      // no-incorporation rebuild never spuriously re-runs the spec stage.
      boolean hasAccepted =
          clarificationReadPort.listByWorkflowRunId(command.workflowRunId()).stream()
              .anyMatch(c -> Clarification.STATUS_ACCEPTED.equals(c.status()));
      if (!hasAccepted) {
        throw new DomainException(
            DomainErrorCode.REGENERATE_NOT_APPLICABLE,
            "Spec regeneration requires at least one accepted clarification to incorporate",
            Map.of("runId", command.workflowRunId()));
      }
      // (a) Bump spec_rejection_loop_count FIRST (mirror ApprovalService.rejectSpec's order:
      // increment -> transition -> retry) so retrySpecGeneration's snapshot read sees the new count
      // and mints a dispatch key distinct from the prior spec attempt. Without this, two successive
      // spec dispatches compute the SAME specDispatchKey and the broker rejects the second as an
      // idempotency conflict (the initial submit already dispatched at loopCount=0).
      int loopCount =
          workflowRunRejectionLoopPort.incrementAndReadLoopCount(command.workflowRunId());
      log.info(
          "regenerateSpecWithClarifications workflowRunId={} bumped specRejectionLoopCount={}",
          command.workflowRunId(),
          loopCount);
      // (b) WaitingForSpecApproval -> Investigating. The transition service appends
      // workflow.stateChanged itself (do NOT append a second one). The edge is already legal —
      // the
      // reject->retry loop uses it — so no transition-table change.
      transition(
          command.workflowRunId(),
          WorkflowState.INVESTIGATING,
          command,
          "regenerate specification with clarifications",
          Map.of());
      // (c) Re-dispatch ONLY (Trap T8) — retrySpecGeneration MUST NOT re-transition. Shares this
      // @Transactional boundary, so a dispatch failure rolls back the transition + the bump
      // (all-or-nothing). No-op when spec-stage.auto-dispatch=false.
      workflowOrchestrationService.retrySpecGeneration(
          command.workflowRunId(), normalizeOptional(command.correlationId()));
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.INVESTIGATING,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult retryWorkflowInternal(RetryWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      transition(
          command.workflowRunId(),
          WorkflowState.EXECUTING,
          command,
          fallbackReason(command.reasonText(), "retry workflow"),
          Map.of());
      // RC2 (rerun re-dispatch) — the transition above only flips Failed -> Executing; it
      // enqueues
      // NO runner, so without this the run wedges in Executing with nothing for the worker pool to
      // dequeue. Re-dispatch the EXECUTION-stage runner so the retried run actually resumes. Shares
      // this @Transactional boundary exactly like submitInternal's dispatchSpecGeneration (the
      // enqueue opens a REQUIRED tx + afterCommit NOTIFY). No-op when implementation/plan
      // auto-dispatch is off (the shared test profile), so the bare transition stays observable.
      workflowOrchestrationService.redispatchAfterRetry(
          command.workflowRunId(), normalizeOptional(command.correlationId()));
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.EXECUTING,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult takeoverWorkflowInternal(TakeoverWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      transition(
          command.workflowRunId(),
          WorkflowState.TAKEN_OVER,
          command,
          fallbackReason(command.reasonText(), "take over workflow"),
          Map.of());
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.TAKEN_OVER,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult resumeWorkflowInternal(ResumeWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // Story 4.5 (Reconciliation 8): transition to the caller-supplied targetState (the recovered
      // Paused → prior executing state) via the generic transition helper — the transition
      // table
      // validates Paused → targetState is legal (Paused → Executing today). NO re-dispatch here
      // (RecoveryService.resume owns the single redispatchAfterRetry after this prep tx commits).
      transition(
          command.workflowRunId(),
          command.targetState(),
          command,
          fallbackReason(command.reasonText(), "resume workflow"),
          Map.of());
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          command.targetState(),
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult rerunFromStepWorkflowInternal(
      RerunFromStepWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // Story 4.7 (Reconciliation 9): transition to the caller-chosen targetState via the generic
      // transition helper — the transition table validates the (currentState → targetState) edge is
      // legal (FAILED→{INVESTIGATING,EXECUTING}, WAITING_FOR_REVIEW→EXECUTING today) and raises
      // ILLEGAL_TRANSITION otherwise (a terminal run or an unwired pair). NO re-enqueue here
      // (RecoveryService.rerunFromStep owns the single runner re-enqueue after this prep tx
      // commits).
      transition(
          command.workflowRunId(),
          command.targetState(),
          command,
          fallbackReason(command.reasonText(), "rerun from step"),
          // targetStep carries the SafeRerunStep wire token (lowercase —
          // "investigating"/"executing")
          // to stay consistent with the recovery.rerunFromStep event's detail key, NOT the
          // capitalized WorkflowState value.
          Map.of(
              "targetStep",
              command.targetState() == WorkflowState.INVESTIGATING
                  ? "investigating"
                  : "executing"));
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          command.targetState(),
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult pauseWorkflowInternal(PauseWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // Story 4.8 (Reconciliation 9): transition to the constant PAUSED via the generic transition
      // helper — the transition table validates <source> → Paused is legal (the 8
      // PAUSABLE_SOURCE_STATES rows). failureCategory is null by construction (the category guard
      // admits it only on {Executing, Investigating} → Failed). NO runner cancellation here
      // (RecoveryService.pause owns the cancel-flips inside its prep tx and the post-commit docker
      // stop).
      transition(
          command.workflowRunId(),
          WorkflowState.PAUSED,
          command,
          fallbackReason(command.reasonText(), "pause workflow"),
          Map.of());
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.PAUSED,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult reconcileWorkflowInternal(ReconcileWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      transition(
          command.workflowRunId(),
          WorkflowState.RECONCILED,
          command,
          fallbackReason(command.reasonText(), "reconcile workflow"),
          Map.of(
              "conflictId",
              command.conflictId(),
              "reconciliationDecision",
              command.decision().value()));
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.RECONCILED,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private <T extends DomainResult, C extends WorkflowCommand> T executeIdempotent(
      C command,
      java.util.function.Function<C, T> action,
      java.util.function.BiFunction<String, C, T> replayLoader) {
    return executeIdempotent(command, action, replayLoader, DomainResult::workflowRunId);
  }

  private <T extends DomainResult, C extends WorkflowCommand> T executeIdempotent(
      C command,
      java.util.function.Function<C, T> action,
      java.util.function.BiFunction<String, C, T> replayLoader,
      java.util.function.Function<T, String> resultRefExtractor) {
    validateForExecution(command);
    String fingerprint = fingerprintFactory.fingerprintFor(command);
    IdempotencyService.ReservationOutcome outcome =
        checkAndReserveInIndependentTransaction(command, fingerprint);
    if (outcome.decision() == IdempotencyService.ReservationDecision.REPLAY) {
      return replayLoader.apply(outcome.resultRef(), command);
    }
    try {
      T result = action.apply(command);
      completeWhenTransactionFinishes(command.idempotencyKey(), resultRefExtractor.apply(result));
      return result;
    } catch (RuntimeException error) {
      completeFailedInIndependentTransaction(command.idempotencyKey(), error);
      throw error;
    }
  }

  private <C extends WorkflowCommand>
      IdempotencyService.ReservationOutcome checkAndReserveInIndependentTransaction(
          C command, String fingerprint) {
    return failureCompletionTemplate.execute(
        ignored ->
            idempotencyService.checkAndReserve(
                command.idempotencyKey(),
                command.commandType(),
                command.actorIdentity(),
                fingerprint));
  }

  private void completeWhenTransactionFinishes(String idempotencyKey, String workflowRunId) {
    completeInIndependentTransaction(
        idempotencyKey, workflowRunId, IdempotencyRecordStatus.COMPLETED);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) {
                completeInIndependentTransaction(
                    idempotencyKey, null, IdempotencyRecordStatus.FAILED);
              }
            }
          });
    }
  }

  private void completeFailedInIndependentTransaction(
      String idempotencyKey, RuntimeException original) {
    try {
      completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
    } catch (RuntimeException completionError) {
      original.addSuppressed(completionError);
    }
  }

  private void completeInIndependentTransaction(
      String idempotencyKey, String resultRef, IdempotencyRecordStatus status) {
    failureCompletionTemplate.execute(
        ignored -> {
          idempotencyService.complete(idempotencyKey, resultRef, status);
          return null;
        });
  }

  private void validateForExecution(WorkflowCommand command) {
    Set<ConstraintViolation<WorkflowCommand>> violations = validator.validate(command);
    boolean hasNonIdempotencyViolations =
        violations.stream()
            .anyMatch(
                violation -> !"idempotencyKey".equals(violation.getPropertyPath().toString()));
    if (hasNonIdempotencyViolations) {
      throw invalidCommandPayload(command, violations);
    }
    idempotencyKeyValidator.requireValid(command.idempotencyKey());
  }

  private void transition(
      String workflowRunId,
      WorkflowState targetState,
      WorkflowCommand command,
      String reason,
      Map<String, Object> extraDetails) {
    workflowTransitionService.transition(
        workflowRunId,
        targetState,
        new TransitionActor(command.actorIdentity(), command.actorType()),
        reason,
        command.idempotencyKey(),
        commandDetails(command, extraDetails));
  }

  private DomainException invalidCommandPayload(
      WorkflowCommand command, Set<ConstraintViolation<WorkflowCommand>> violations) {
    List<Map<String, Object>> fieldErrors = new ArrayList<>();
    violations.stream()
        .sorted(
            Comparator.comparing(
                    (ConstraintViolation<WorkflowCommand> violation) ->
                        violation.getPropertyPath().toString())
                .thenComparing(
                    violation ->
                        violation
                            .getConstraintDescriptor()
                            .getAnnotation()
                            .annotationType()
                            .getSimpleName()))
        .forEach(
            violation -> {
              Map<String, Object> fieldError = new LinkedHashMap<>();
              fieldError.put("field", violation.getPropertyPath().toString());
              fieldError.put(
                  "code",
                  violation
                      .getConstraintDescriptor()
                      .getAnnotation()
                      .annotationType()
                      .getSimpleName());
              fieldError.put("rejectedValue", violation.getInvalidValue());
              fieldError.put("message", violation.getMessage());
              fieldErrors.add(fieldError);
            });

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("commandType", command.commandType());
    details.put("fieldErrors", fieldErrors);
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Invalid command payload for " + command.commandType(),
        details);
  }

  private Map<String, Object> baseDetails(WorkflowCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("commandType", command.commandType());
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private Map<String, Object> commandDetails(
      WorkflowCommand command, Map<String, Object> extraDetails) {
    // Canonical envelope keys (commandType, idempotencyKey, correlationId) win over
    // caller-supplied extraDetails so a future caller cannot accidentally clobber them.
    Map<String, Object> details = new LinkedHashMap<>(extraDetails);
    details.putAll(baseDetails(command));
    return details;
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  // Retry/Takeover commands accept an optional reasonText; when omitted we substitute a
  // stable system-supplied fallback so the audit trail always carries a non-empty reason.
  // This is intentional: requiring reasonText on retry/takeover would be a UX regression
  // for the operator-recovery happy path. RejectSpecCommand keeps @NotBlank because spec
  // rejection is an explicit operator decision that should always carry justification.
  private String fallbackReason(String reason, String fallback) {
    String normalized = normalizeOptional(reason);
    return normalized == null ? fallback : normalized;
  }

  private SubmitWorkflowResult replaySubmit(String resultRef, SubmitWorkflowCommand command) {
    var workflowRun = findWorkflowRunForReplay(resultRef);
    return new SubmitWorkflowResult(
        workflowRun.publicId(),
        workflowRun.currentState(),
        normalizeOptional(command.correlationId()));
  }

  /**
   * Story 3.20 (Trap T2): {@code acceptImplementation}'s resulting state depends on the accepted
   * artifact's type — {@code Completed} for a {@code prOutput}, {@code Executing} for an {@code
   * implementationPlan}. The generic {@link #replayStateChange} hard-codes the post-state per
   * command type, which would return the wrong state here, so this dedicated replay re-reads the
   * run and returns its <strong>current</strong> {@code currentState()}. The run never leaves
   * {@code Completed} (terminal) once reached, and an {@code implementationPlan} acceptance lands
   * {@code Executing}; either way the live current state is the correct replay answer.
   */
  private WorkflowStateChangeResult replayAcceptImplementation(
      String resultRef, AcceptImplementationCommand command) {
    var workflowRun = findWorkflowRunForReplay(resultRef);
    return new WorkflowStateChangeResult(
        workflowRun.publicId(),
        workflowRun.currentState(),
        normalizeOptional(command.correlationId()));
  }

  private WorkflowStateChangeResult replayStateChange(String resultRef, WorkflowCommand command) {
    String workflowRunId =
        switch (command) {
          case SubmitClarificationCommand ignored -> clarificationReplayRunId(resultRef);
          case AcceptClarificationCommand ignored -> clarificationReplayRunId(resultRef);
          case ApproveLintCommand ignored -> stateChangeReplayRunId(resultRef);
          default -> resultRef;
        };
    var workflowRun = findWorkflowRunForReplay(workflowRunId);
    // Replay must return the original command result, not the run's later live state. Commands
    // with invariant post-states are therefore pinned here; callers arriving after the run has
    // advanced still receive the state produced by the original accepted command.
    WorkflowState resultingState =
        switch (command) {
          case ApproveSpecCommand ignored -> WorkflowState.EXECUTING;
          case RejectSpecCommand ignored -> WorkflowState.INVESTIGATING;
          // Story 3e-2 (AC2): regenerate's post-state is Investigating (the re-dispatch
          // transition),
          // pinned like RejectSpecCommand so a replay after the rebuild returns Investigating.
          case RegenerateSpecCommand ignored -> WorkflowState.INVESTIGATING;
          // Story 3.21: both implementationPlan + prOutput rejection land in Executing (Decision
          // D3),
          // so a hard-coded replay state is correct (unlike acceptImplementation's type-dependent
          // target, which uses the dedicated replayAcceptImplementation re-read).
          case RejectImplementationCommand ignored -> WorkflowState.EXECUTING;
          // Story 3h-2 (AC5) / 3h-4 (Decision 3): request_lint_fix re-dispatches to Executing
          // (invariant). approve_lint's post-state is now MODE-DEPENDENT — WaitingForReview when
          // the
          // run's project is auto push mode (the lint approval delivers directly),
          // WaitingForDelivery
          // when non-auto (the lint approval routes into the delivery gate). Resolve pushMode at
          // replay time (a stable per-project value, so the same answer as at command time) rather
          // than re-reading the run's later live state, which may have advanced past either.
          case ApproveLintCommand ignored -> stateChangeReplayState(resultRef, workflowRunId);
          case RequestLintFixCommand ignored -> WorkflowState.EXECUTING;
          // Story 3h-4 (AC7): approve_delivery always advances to WaitingForReview (both approve
          // and
          // manual push modes), so a static pin is correct — a replayed approve returns
          // WaitingForReview without re-pushing / double-creating the PR.
          case ApproveDeliveryCommand ignored -> WorkflowState.WAITING_FOR_REVIEW;
          // Story 4.5 (Reconciliation 8): resume's post-state is the accepted command's
          // targetState (the recovered prior executing state — Executing today). It is the
          // invariant answer for a replay of this exact command, mirroring how retry pins
          // Executing; pin it from the command payload rather than re-reading the run's later
          // live state.
          case ResumeWorkflowCommand resume -> resume.targetState();
          // Story 4.7 (Reconciliation 9): rerun-from-step's post-state is the accepted command's
          // targetState (the operator-chosen safe step boundary). It is the invariant answer for a
          // replay of this exact command; pin it from the command payload rather than re-reading
          // the
          // run's later live state, mirroring resume.
          case RerunFromStepWorkflowCommand rerun -> rerun.targetState();
          // Story 4.8 (Reconciliation 9): pause's post-state is the constant Paused — the
          // invariant
          // answer for a replay of this exact command, mirroring how reconcile pins Reconciled.
          case PauseWorkflowCommand ignored -> WorkflowState.PAUSED;
          case ReconcileWorkflowCommand ignored -> WorkflowState.RECONCILED;
          case SubmitClarificationCommand ignored -> clarificationReplayState(resultRef);
          case AcceptClarificationCommand ignored -> clarificationReplayState(resultRef);
          default -> workflowRun.currentState();
        };
    if (command instanceof SubmitClarificationCommand submitClarificationCommand) {
      return new WorkflowStateChangeResult(
          workflowRun.publicId(),
          resultingState,
          normalizeOptional(command.correlationId()),
          clarificationReplayStatus(resultRef, submitClarificationCommand));
    }
    if (command instanceof AcceptClarificationCommand) {
      // Story 3e-2: accept_clarification is a NET-NEW command — every replay ref is the 3-segment
      // (run, state, status) shape (no legacy 2-segment refs exist), so the embedded status is
      // always present. No legacy live-read fallback needed (contrast SubmitClarificationCommand).
      return new WorkflowStateChangeResult(
          workflowRun.publicId(),
          resultingState,
          normalizeOptional(command.correlationId()),
          parseClarificationReplayRef(resultRef).clarificationStatus());
    }
    return new WorkflowStateChangeResult(
        workflowRun.publicId(), resultingState, normalizeOptional(command.correlationId()));
  }

  private String clarificationReplayRef(WorkflowStateChangeResult result) {
    if (result.currentState() == null) {
      // Defensive: a null currentState would NPE here and surface as INTERNAL_ERROR via the
      // failed-tx pipeline for a command that actually succeeded. Today the answer flow always
      // populates currentState, but future result variants must not silently corrupt idempotency.
      throw new IllegalStateException(
          "WorkflowStateChangeResult.currentState() is null for clarification replay ref");
    }
    String clarificationStatus = normalizeOptional(result.clarificationStatus());
    if (clarificationStatus == null) {
      throw new IllegalStateException(
          "WorkflowStateChangeResult.clarificationStatus() is null for clarification replay ref");
    }
    return result.workflowRunId()
        + CLARIFICATION_REPLAY_REF_SEPARATOR
        + result.currentState().value()
        + CLARIFICATION_REPLAY_REF_SEPARATOR
        + clarificationStatus;
  }

  private String stateChangeReplayRef(String workflowRunId, WorkflowState state) {
    return workflowRunId + CLARIFICATION_REPLAY_REF_SEPARATOR + state.value();
  }

  private String stateChangeReplayRunId(String resultRef) {
    int separator = resultRef.indexOf(CLARIFICATION_REPLAY_REF_SEPARATOR);
    return separator < 0 ? resultRef : resultRef.substring(0, separator);
  }

  private WorkflowState stateChangeReplayState(String resultRef, String workflowRunId) {
    int separator = resultRef.indexOf(CLARIFICATION_REPLAY_REF_SEPARATOR);
    if (separator < 0) {
      return projectRuntimeConfigResolver.resolvePushMode(workflowRunId) == PushMode.AUTO
          ? WorkflowState.WAITING_FOR_REVIEW
          : WorkflowState.WAITING_FOR_DELIVERY;
    }
    String stateValue =
        resultRef.substring(separator + CLARIFICATION_REPLAY_REF_SEPARATOR.length());
    return WorkflowState.fromValue(stateValue, "idempotency.resultRef");
  }

  private String clarificationReplayRunId(String resultRef) {
    return parseClarificationReplayRef(resultRef).workflowRunId();
  }

  private WorkflowState clarificationReplayState(String resultRef) {
    return parseClarificationReplayRef(resultRef).workflowState();
  }

  /**
   * Story 2.13 review D1: legacy two-segment {@code resultRef} values written before this story
   * stored only {@code workflowRunId + SEP + state.value()} — they do not encode the {@code
   * clarificationStatus} that callers now expect on replay. For these legacy refs the resolver
   * falls back to a live read against {@link ClarificationReadPort}, which means:
   *
   * <ul>
   *   <li>If the row has advanced (answered → accepted / incorporated / superseded /
   *       rejected_invalid) between original-answer time and replay time, the replay observes the
   *       <em>current</em> row status, not the answer-time status. Idempotent replays across the
   *       legacy migration window may therefore not reproduce the original response byte-for-byte.
   *   <li>If the row has been hard-deleted (operator action, retention sweep), the replay returns
   *       the {@link #LEGACY_CLARIFICATION_REPLAY_STATUS_UNKNOWN} sentinel (story-2.13 round-3
   *       decision D-Round3-1) so the previously-200 response stays 200 and the idempotent-replay
   *       contract is preserved. Bounded to the legacy window; fresh writes always carry the
   *       3-segment ref with the embedded status.
   * </ul>
   *
   * <p>This drift is accepted (story-2.13 review decision D1 + D-Round3-1) rather than
   * reject-legacy or backfill, because the migration window is bounded and the operator-visible
   * blast radius is limited to the small population of pre-2.13 idempotency rows still in flight.
   */
  private String clarificationReplayStatus(String resultRef, SubmitClarificationCommand command) {
    ClarificationReplayRef replayRef = parseClarificationReplayRef(resultRef);
    if (replayRef.clarificationStatus() != null) {
      return replayRef.clarificationStatus();
    }
    // Story 2.13 round-4 P-R4-1: cross-run guard on the legacy 2-segment replay-ref fallback. The
    // idempotency record fingerprint normally blocks cross-run replays, but the legacy path reads
    // from a port keyed on clarificationId alone — a tampered URL or migration-era leaked ref
    // would otherwise surface another run's clarification status. Reject mismatches as
    // CLARIFICATION_NOT_FOUND (same code/HTTP-status the caller would see for an unknown id, so we
    // don't leak the existence of a foreign run's clarification through error code variance).
    return clarificationReadPort
        .findByPublicId(command.clarificationId())
        .map(
            clarification -> {
              if (!clarification.workflowRunId().equals(command.workflowRunId())) {
                throw new DomainException(
                    DomainErrorCode.CLARIFICATION_NOT_FOUND,
                    "Clarification not found on workflow run",
                    Map.of(
                        "clarificationId",
                        command.clarificationId(),
                        "workflowRunId",
                        command.workflowRunId()));
              }
              return clarification.status();
            })
        .orElse(LEGACY_CLARIFICATION_REPLAY_STATUS_UNKNOWN);
  }

  private ClarificationReplayRef parseClarificationReplayRef(String resultRef) {
    int firstSeparator = clarificationReplaySeparatorIndex(resultRef, 0);
    if (firstSeparator <= 0) {
      throw malformedClarificationReplayRef(resultRef);
    }
    int firstSeparatorLength = clarificationReplaySeparatorLength(resultRef, firstSeparator);
    int stateStart = firstSeparator + firstSeparatorLength;
    if (stateStart >= resultRef.length()) {
      throw malformedClarificationReplayRef(resultRef);
    }
    int secondSeparator = clarificationReplaySeparatorIndex(resultRef, stateStart);
    String stateValue =
        secondSeparator < 0
            ? resultRef.substring(stateStart)
            : resultRef.substring(stateStart, secondSeparator);
    if (stateValue.isBlank()) {
      throw malformedClarificationReplayRef(resultRef);
    }
    String clarificationStatus = null;
    if (secondSeparator >= 0) {
      int secondSeparatorLength = clarificationReplaySeparatorLength(resultRef, secondSeparator);
      int statusStart = secondSeparator + secondSeparatorLength;
      if (statusStart >= resultRef.length()) {
        throw malformedClarificationReplayRef(resultRef);
      }
      clarificationStatus = resultRef.substring(statusStart);
      if (!Clarification.ALL_STATUSES.contains(clarificationStatus)) {
        throw malformedClarificationReplayRef(resultRef);
      }
    }
    return new ClarificationReplayRef(
        resultRef.substring(0, firstSeparator),
        WorkflowState.fromValue(stateValue, "idempotency.resultRef"),
        clarificationStatus);
  }

  private int clarificationReplaySeparatorIndex(String resultRef, int fromIndex) {
    int separator = resultRef.indexOf(CLARIFICATION_REPLAY_REF_SEPARATOR, fromIndex);
    int legacySeparator = resultRef.indexOf(LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR, fromIndex);
    if (separator < 0) {
      return legacySeparator;
    }
    if (legacySeparator < 0) {
      return separator;
    }
    // Story 2.13 review P16: a single ref must encode with one separator scheme. A ref carrying
    // both the new U+001F and the legacy '|' is ambiguous — Math.min would silently pick one and
    // misparse. Reject as malformed so callers see a typed error rather than corrupted parsing.
    throw malformedClarificationReplayRef(resultRef);
  }

  private int clarificationReplaySeparatorLength(String resultRef, int separatorIndex) {
    if (resultRef.startsWith(CLARIFICATION_REPLAY_REF_SEPARATOR, separatorIndex)) {
      return CLARIFICATION_REPLAY_REF_SEPARATOR.length();
    }
    if (resultRef.startsWith(LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR, separatorIndex)) {
      return LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR.length();
    }
    throw malformedClarificationReplayRef(resultRef);
  }

  private DomainException malformedClarificationReplayRef(String resultRef) {
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Malformed clarification replay result reference",
        Map.of("resultRef", resultRef));
  }

  private WorkflowRunSnapshot findWorkflowRunForReplay(String workflowRunId) {
    for (int attempt = 0; attempt < REPLAY_LOOKUP_ATTEMPTS; attempt++) {
      var workflowRun = workflowRunReadPort.findByPublicId(workflowRunId);
      if (workflowRun.isPresent()) {
        return workflowRun.get();
      }
      pauseBeforeReplayLookup();
    }
    throw new DomainException(
        DomainErrorCode.RUN_NOT_FOUND,
        "Workflow run not found: " + workflowRunId,
        Map.of("runId", workflowRunId));
  }

  private void pauseBeforeReplayLookup() {
    try {
      Thread.sleep(REPLAY_LOOKUP_DELAY_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Interrupted while waiting for workflow run replay visibility",
          Map.of("delayMs", REPLAY_LOOKUP_DELAY_MS));
    }
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  private record ClarificationReplayRef(
      String workflowRunId, WorkflowState workflowState, String clarificationStatus) {}
}
