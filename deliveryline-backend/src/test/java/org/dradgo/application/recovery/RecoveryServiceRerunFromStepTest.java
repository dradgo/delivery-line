package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.queue.QueuedRunnerExecution;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.commands.RerunFromStepWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.7 (AC11) — unit coverage for {@link RecoveryService#rerunFromStep}, mirroring the {@code
 * resume}/{@code reconcile} cases: rerun-to-{@code investigating} from {@code Failed} + supersedes
 * prior artifacts + invalidates the prior spec approval + re-enqueues the INVESTIGATION runner;
 * rerun-to-{@code executing} from {@code WaitingForReview} + invalidates the prior plan approval +
 * re-enqueues the EXECUTION runner; {@code INVALID_RERUN_TARGET_STEP}; {@code MISSING_REASON_TEXT};
 * {@code ILLEGAL_TRANSITION} (terminal run); idempotent replay; cross-action-key conflict;
 * concurrent-replay; dispatch-failure compensation.
 */
class RecoveryServiceRerunFromStepTest {

  private static final String RUN = "run_rerun12345";
  private static final String IDEMPOTENCY_KEY = "idem-rerun-1234567890";
  private static final String FAILURE_EVENT_ID = "evt_fail-aaaaa1";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-07-11T12:00:00Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowEventReadPort eventReadPort;
  private WorkflowCommandService workflowCommandService;
  private RunnerExecutionQueue runnerExecutionQueue;
  private org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver;
  private WorkflowOrchestrationService workflowOrchestrationService;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;
  private ApprovalService approvalService;
  private ArtifactRecordPort artifactRecordPort;
  private WorkflowTransitionService workflowTransitionService;
  private RecoveryService service;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    runnerExecutionQueue = mock(RunnerExecutionQueue.class);
    projectRuntimeConfigResolver =
        mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class);
    workflowOrchestrationService = mock(WorkflowOrchestrationService.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    approvalService = mock(ApprovalService.class);
    artifactRecordPort = mock(ArtifactRecordPort.class);
    workflowTransitionService = mock(WorkflowTransitionService.class);
    // Default: no prior recovery action for the key (fresh path).
    when(recoveryRecordPort.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    service =
        new RecoveryService(
            runReadPort,
            eventReadPort,
            mock(RunnerExecutionRecordPort.class),
            mock(ArtifactOperationPort.class),
            workflowCommandService,
            runnerExecutionQueue,
            projectRuntimeConfigResolver,
            mock(org.dradgo.application.runner.ManualExecutionDispatcher.class),
            workflowOrchestrationService,
            eventWritePort,
            recoveryRecordPort,
            new IdempotencyKeyValidator(),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate(),
            approvalService,
            artifactRecordPort,
            workflowTransitionService);
  }

  @Test
  void rerunToInvestigatingFromFailedTransitionsInvalidatesSpecAndReEnqueuesInvestigationRunner() {
    stubRun(WorkflowState.FAILED);
    stubFailureEvent();
    stubLeaf(ArtifactType.SPEC, "art_spec_1");
    stubLeaf(ArtifactType.IMPLEMENTATION_PLAN, "art_plan_1");
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN, "prOutput"))
        .thenReturn(Optional.empty());
    when(approvalService.invalidateCurrentApproval(RUN, "spec", "superseded_by_rerun_from_step"))
        .thenReturn(Optional.of("apr_spec_1"));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(rerunSnapshot("rcv_rr-1", "pending", null));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(rerunSnapshot("rcv_rr-1", "succeeded", "evt_rr-1"));
    stubEnqueue(RunnerStage.INVESTIGATION, "rex_rr-1");

    RerunFromStepRecoveryResult result =
        service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "re-spec needed");

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals("rcv_rr-1", result.recoveryActionPublicId());
    assertEquals("rex_rr-1", result.newRunnerExecutionPublicId());
    assertEquals(WorkflowState.INVESTIGATING, result.resultingState());
    assertEquals(List.of("art_spec_1", "art_plan_1"), result.supersededArtifactIds());
    assertEquals(List.of("apr_spec_1"), result.invalidatedApprovalIds());

    ArgumentCaptor<RerunFromStepWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(RerunFromStepWorkflowCommand.class);
    verify(workflowCommandService, times(1)).rerunFromStepWorkflow(commandCaptor.capture());
    assertEquals(RUN, commandCaptor.getValue().workflowRunId());
    assertEquals(IDEMPOTENCY_KEY, commandCaptor.getValue().idempotencyKey());
    assertEquals(WorkflowState.INVESTIGATING, commandCaptor.getValue().targetState());
    assertEquals("re-spec needed", commandCaptor.getValue().reasonText());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(1)).append(eventCaptor.capture());
    WorkflowEventRecord rerunEvent = eventCaptor.getValue();
    assertEquals(WorkflowEventType.RECOVERY_RERUN_FROM_STEP, rerunEvent.eventType());
    assertEquals(WorkflowState.FAILED, rerunEvent.priorState());
    assertEquals(WorkflowState.INVESTIGATING, rerunEvent.resultingState());
    assertTrue(rerunEvent.interventionMarker());
    assertEquals("investigating", rerunEvent.details().get("targetStep"));
    assertEquals(
        List.of("art_spec_1", "art_plan_1"), rerunEvent.details().get("supersededArtifactIds"));
    assertEquals(List.of("apr_spec_1"), rerunEvent.details().get("invalidatedApprovalIds"));
    assertEquals(FAILURE_EVENT_ID, rerunEvent.details().get("triggeringEventId"));
    assertEquals(IDEMPOTENCY_KEY, rerunEvent.details().get("idempotencyKey"));

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort, times(1)).insert(writeCaptor.capture());
    assertEquals("rerun", writeCaptor.getValue().actionType());
    assertEquals("workflow_owner", writeCaptor.getValue().reviewerRole());
    assertEquals("pending", writeCaptor.getValue().resultStatus());
    assertEquals(FAILURE_EVENT_ID, writeCaptor.getValue().triggeringEventPublicId());

    verify(approvalService, times(1))
        .invalidateCurrentApproval(RUN, "spec", "superseded_by_rerun_from_step");
    verify(runnerExecutionQueue, times(1))
        .enqueue(
            eq(RUN),
            eq(RunnerStage.INVESTIGATION),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyInt());
    verify(recoveryRecordPort, times(1)).markSucceeded(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markFailed(any());
    verify(workflowOrchestrationService, times(1))
        .notifyLinearRunReopened(eq(RUN), eq("investigating"), any());
  }

  @Test
  void rerunToExecutingFromWaitingForReviewInvalidatesPlanAndReEnqueuesExecutionRunner() {
    stubRun(WorkflowState.WAITING_FOR_REVIEW);
    stubFailureEvent();
    stubLeaf(ArtifactType.IMPLEMENTATION_PLAN, "art_plan_2");
    stubLeaf(ArtifactType.PR_OUTPUT, "art_pr_2");
    when(approvalService.invalidateCurrentApproval(
            RUN, "implementationPlan", "superseded_by_rerun_from_step"))
        .thenReturn(Optional.of("apr_plan_2"));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(rerunSnapshot("rcv_rr-2", "pending", null));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(rerunSnapshot("rcv_rr-2", "succeeded", "evt_rr-2"));
    stubEnqueue(RunnerStage.EXECUTION, "rex_rr-2");

    RerunFromStepRecoveryResult result =
        service.rerunFromStep(RUN, "executing", IDEMPOTENCY_KEY, actor(), "re-implement");

    assertFalse(result.replayed());
    assertEquals(WorkflowState.EXECUTING, result.resultingState());
    // EXECUTING supersedes {implementationPlan, prOutput} only (NOT spec).
    assertEquals(List.of("art_plan_2", "art_pr_2"), result.supersededArtifactIds());
    assertEquals(List.of("apr_plan_2"), result.invalidatedApprovalIds());

    verify(approvalService, times(1))
        .invalidateCurrentApproval(RUN, "implementationPlan", "superseded_by_rerun_from_step");
    verify(approvalService, never()).invalidateCurrentApproval(eq(RUN), eq("spec"), any());
    verify(runnerExecutionQueue, times(1))
        .enqueue(
            eq(RUN),
            eq(RunnerStage.EXECUTION),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void rerunWithInvalidTargetStepRaisesInvalidRerunTargetStepWithoutMutatingState() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "waiting_for_review", IDEMPOTENCY_KEY, actor(), "x"));
    assertEquals(DomainErrorCode.INVALID_RERUN_TARGET_STEP, error.errorCode());
    assertEquals("waiting_for_review", error.details().get("provided"));

    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void rerunWithBlankTargetStepRaisesInvalidRerunTargetStep() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "  ", IDEMPOTENCY_KEY, actor(), "x"));
    assertEquals(DomainErrorCode.INVALID_RERUN_TARGET_STEP, error.errorCode());
  }

  @Test
  void rerunWithBlankReasonRaisesMissingReasonText() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "  "));
    assertEquals(DomainErrorCode.MISSING_REASON_TEXT, error.errorCode());

    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void rerunOnTerminalRunRaisesIllegalTransition() {
    stubRun(WorkflowState.COMPLETED);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "x"));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals("Completed", error.details().get("currentState"));

    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
  }

  @Test
  void rerunFromNonSourceStateRaisesIllegalTransitionWithoutMutatingState() {
    // [Review 2026-07-12] Rerun-from-step may launch ONLY from {FAILED, WAITING_FOR_REVIEW} (the
    // AC10 gate). WAITING_FOR_SPEC_APPROVAL has a LEGAL transition-table edge to
    // EXECUTING/INVESTIGATING, so without the explicit source-state gate the rerun would succeed
    // and
    // destroy the PM's live spec approval — the ADR-0034 out-of-scope case. It must be rejected
    // here
    // before any state is consumed.
    stubRun(WorkflowState.WAITING_FOR_SPEC_APPROVAL);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "x"));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals("WaitingForSpecApproval", error.details().get("currentState"));

    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(approvalService, never()).invalidateCurrentApproval(any(), any(), any());
  }

  @Test
  void rerunReplaysSucceededPriorActionWithoutSideEffects() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(rerunSnapshot("rcv_prior-1", "succeeded", "evt_rr-prior")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null)).thenReturn(List.of(priorRerunEvent()));

    RerunFromStepRecoveryResult result =
        service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "re-spec");

    assertTrue(result.replayed());
    assertEquals("rcv_prior-1", result.recoveryActionPublicId());
    assertEquals("evt_rr-prior", result.rerunEventPublicId());
    assertEquals(WorkflowState.INVESTIGATING, result.resultingState());
    assertNull(result.newRunnerExecutionPublicId());

    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(runnerExecutionQueue, never())
        .enqueue(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void rerunReplayWithDifferentTargetStepRaisesIdempotencyKeyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(rerunSnapshot("rcv_prior-1", "succeeded", "evt_rr-prior")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(priorRerunEvent())); // stored targetStep = investigating

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "executing", IDEMPOTENCY_KEY, actor(), "x"));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void rerunReplayWithSameStepButDifferentReasonRaisesIdempotencyKeyConflict() {
    // [Review P2] Same key + same step but a DIFFERENT non-blank reason is a distinct action
    // (reasonText composes the fingerprint identity) → conflict, not a replay.
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(rerunSnapshot("rcv_prior-1", "succeeded", "evt_rr-prior")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(priorRerunEvent())); // stored reason = "re-spec"

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.rerunFromStep(
                    RUN, "investigating", IDEMPOTENCY_KEY, actor(), "a different reason"));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void rerunConcurrentLoserWithDifferentTargetStepRaisesConflictNotFalseReplay() {
    // [Review P1] A concurrent same-key loser that requested a DIFFERENT step than the committed
    // winner must raise IDEMPOTENCY_KEY_CONFLICT (matching the serial path), NOT falsely replay the
    // winner's result for a step it never asked for.
    stubRun(WorkflowState.FAILED);
    stubFailureEvent();
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN), any()))
        .thenReturn(Optional.empty());
    when(approvalService.invalidateCurrentApproval(any(), any(), any()))
        .thenReturn(Optional.empty());
    RecoveryActionSnapshot winner = rerunSnapshot("rcv_winner-1", "succeeded", "evt_rr-prior");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(priorRerunEvent())); // winner stored targetStep = investigating
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "executing", IDEMPOTENCY_KEY, actor(), "re-impl"));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void rerunWithPriorNonRerunActionUnderSameKeyRaisesConflict() {
    RecoveryActionSnapshot priorResume =
        new RecoveryActionSnapshot(
            "rcv_prior-resume",
            9L,
            RUN,
            "resume",
            FAILURE_EVENT_ID,
            "evt_resumed",
            "alex",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            FIXED_NOW,
            "workflow_owner");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(priorResume));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "x"));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
  }

  @Test
  void rerunConcurrentLoserReplaysWhenWinnerSucceededBetweenChecks() {
    stubRun(WorkflowState.FAILED);
    stubFailureEvent();
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN), any()))
        .thenReturn(Optional.empty());
    when(approvalService.invalidateCurrentApproval(any(), any(), any()))
        .thenReturn(Optional.empty());
    RecoveryActionSnapshot winner = rerunSnapshot("rcv_winner-1", "succeeded", "evt_rr-prior");
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null)).thenReturn(List.of(priorRerunEvent()));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "recovery_actions unique violation",
                Map.of("idempotencyKey", IDEMPOTENCY_KEY)));

    RerunFromStepRecoveryResult result =
        service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "re-spec");

    assertTrue(result.replayed());
    assertEquals("rcv_winner-1", result.recoveryActionPublicId());
    verify(runnerExecutionQueue, never())
        .enqueue(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    verify(recoveryRecordPort, never()).markSucceeded(any());
  }

  @Test
  void rerunReEnqueueFailureFlipsRecoveryActionToFailedAndAppendsDispatchFailedEvent() {
    stubRun(WorkflowState.FAILED);
    stubFailureEvent();
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN), any()))
        .thenReturn(Optional.empty());
    // A spec approval WAS invalidated in the prep tx, so the compensation must restore it.
    when(approvalService.invalidateCurrentApproval(any(), any(), any()))
        .thenReturn(Optional.of("apr_spec_x"));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(rerunSnapshot("rcv_rr-3", "pending", null));
    when(projectRuntimeConfigResolver.resolveRunnerKind(RUN, RunnerStage.INVESTIGATION))
        .thenReturn(RunnerKind.CODEX);
    when(runnerExecutionQueue.enqueue(
            eq(RUN),
            eq(RunnerStage.INVESTIGATION),
            any(),
            any(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new IllegalStateException("queue enqueue failed"));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> service.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor(), "re-spec"));
    assertTrue(error.getMessage().contains("queue enqueue failed"));

    verify(recoveryRecordPort, times(1)).markFailed(IDEMPOTENCY_KEY);
    verify(recoveryRecordPort, never()).markSucceeded(any());

    // First append is recovery.rerunFromStep (prep tx); second recovery.dispatchFailed
    // (compensation).
    ArgumentCaptor<WorkflowEventRecord> appended =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(2)).append(appended.capture());
    WorkflowEventRecord dispatchFailed = appended.getAllValues().get(1);
    assertEquals(WorkflowEventType.RECOVERY_DISPATCH_FAILED, dispatchFailed.eventType());
    assertEquals("rcv_rr-3", dispatchFailed.details().get("recoveryActionId"));
    assertEquals("RUNTIME_ERROR", dispatchFailed.details().get("errorCode"));

    // [Review D1] Compensation un-strands the run: the invalidated approval is restored AND the run
    // is driven to FAILED (a legal retry/rerun source) via the RECOVERY_DISPATCH_FAILED category.
    verify(approvalService, times(1)).restoreInvalidatedApproval("apr_spec_x");
    verify(workflowTransitionService, times(1))
        .transition(
            eq(RUN),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RECOVERY_DISPATCH_FAILED),
            any());
  }

  // ---------------------------------------------------------------------------
  // Story 4.22 — previewRerunFromStep (non-mutating)
  // ---------------------------------------------------------------------------

  @Test
  void previewToInvestigatingFromFailedReturnsSupersededAndInvalidatedWithoutAnyWrite() {
    stubRun(WorkflowState.FAILED);
    stubLeaf(ArtifactType.SPEC, "art_spec_1");
    stubLeaf(ArtifactType.IMPLEMENTATION_PLAN, "art_plan_1");
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN, "prOutput"))
        .thenReturn(Optional.empty());
    when(approvalService.findCurrentApprovalId(RUN, "spec")).thenReturn(Optional.of("apr_spec_1"));

    RerunFromStepPreviewResult preview = service.previewRerunFromStep(RUN, "investigating");

    assertEquals(WorkflowState.INVESTIGATING, preview.resultingState());
    assertEquals(List.of("art_spec_1", "art_plan_1"), preview.supersededArtifactIds());
    assertEquals(List.of("apr_spec_1"), preview.invalidatedApprovalIds());

    // ZERO writes — this is the defining preview contract (AC5, Task 1).
    verify(approvalService, never()).invalidateCurrentApproval(any(), any(), any());
    verify(workflowCommandService, never()).rerunFromStepWorkflow(any());
    verify(eventWritePort, never()).append(any());
    verify(recoveryRecordPort, never()).insert(any(RecoveryActionWriteCommand.class));
    verify(recoveryRecordPort, never()).markSucceeded(any());
    verify(recoveryRecordPort, never()).markFailed(any());
    verify(runnerExecutionQueue, never())
        .enqueue(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void previewToExecutingFromWaitingForReviewReadsPlanLeavesAndPlanApproval() {
    stubRun(WorkflowState.WAITING_FOR_REVIEW);
    stubLeaf(ArtifactType.IMPLEMENTATION_PLAN, "art_plan_2");
    stubLeaf(ArtifactType.PR_OUTPUT, "art_pr_2");
    when(approvalService.findCurrentApprovalId(RUN, "implementationPlan"))
        .thenReturn(Optional.of("apr_plan_2"));

    RerunFromStepPreviewResult preview = service.previewRerunFromStep(RUN, "executing");

    assertEquals(WorkflowState.EXECUTING, preview.resultingState());
    // EXECUTING supersedes {implementationPlan, prOutput} only (NOT spec) — mirrors the write path.
    assertEquals(List.of("art_plan_2", "art_pr_2"), preview.supersededArtifactIds());
    assertEquals(List.of("apr_plan_2"), preview.invalidatedApprovalIds());
    verify(approvalService, never()).findCurrentApprovalId(RUN, "spec");
    verify(approvalService, never()).invalidateCurrentApproval(any(), any(), any());
  }

  @Test
  void previewWithNoCurrentApprovalReturnsEmptyInvalidatedList() {
    stubRun(WorkflowState.FAILED);
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(eq(RUN), any()))
        .thenReturn(Optional.empty());
    when(approvalService.findCurrentApprovalId(RUN, "spec")).thenReturn(Optional.empty());

    RerunFromStepPreviewResult preview = service.previewRerunFromStep(RUN, "investigating");

    assertEquals(List.of(), preview.supersededArtifactIds());
    assertEquals(List.of(), preview.invalidatedApprovalIds());
  }

  @Test
  void previewWithInvalidTargetStepRaisesInvalidRerunTargetStepBeforeReadingRun() {
    DomainException error =
        assertThrows(
            DomainException.class, () -> service.previewRerunFromStep(RUN, "waiting_for_review"));
    assertEquals(DomainErrorCode.INVALID_RERUN_TARGET_STEP, error.errorCode());
    verify(runReadPort, never()).findByPublicId(any());
    verify(approvalService, never()).findCurrentApprovalId(any(), any());
  }

  @Test
  void previewWithBlankTargetStepRaisesInvalidRerunTargetStep() {
    DomainException error =
        assertThrows(DomainException.class, () -> service.previewRerunFromStep(RUN, "  "));
    assertEquals(DomainErrorCode.INVALID_RERUN_TARGET_STEP, error.errorCode());
  }

  @Test
  void previewOnNonSourceStateRaisesIllegalTransitionWithoutReads() {
    // OQ-1 default: 409 ILLEGAL_TRANSITION (mirror rerunFromStep) so preview + mutation agree on
    // eligibility; the FE only calls preview when rerun_from_step is in allowed-actions anyway.
    stubRun(WorkflowState.COMPLETED);

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.previewRerunFromStep(RUN, "investigating"));
    assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
    assertEquals("Completed", error.details().get("currentState"));
    verify(approvalService, never()).findCurrentApprovalId(any(), any());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private void stubRun(WorkflowState state) {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, state, null, 7L, 0, false)));
  }

  private void stubFailureEvent() {
    when(eventReadPort.findLatestFailureEvent(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    FAILURE_EVENT_ID,
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.EXECUTING,
                    WorkflowState.FAILED,
                    "alex",
                    ActorType.HUMAN,
                    "failed",
                    null,
                    false,
                    FIXED_NOW,
                    Map.of())));
  }

  private void stubLeaf(ArtifactType type, String publicId) {
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(RUN, type.value()))
        .thenReturn(
            Optional.of(
                ArtifactRecordSnapshot.withoutFailureMetadata(
                    publicId,
                    RUN,
                    type,
                    1,
                    null,
                    DataClassification.SHAREABLE_REDACTED,
                    "storage/" + publicId,
                    "sha-256",
                    "abc",
                    ArtifactStatus.AVAILABLE,
                    null)));
  }

  private void stubEnqueue(RunnerStage stage, String newRexPublicId) {
    when(projectRuntimeConfigResolver.resolveRunnerKind(RUN, stage)).thenReturn(RunnerKind.CODEX);
    when(runnerExecutionQueue.enqueue(
            eq(RUN), eq(stage), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(
            new QueuedRunnerExecution(
                newRexPublicId, RUN, stage, 100, "corr-rerun-1", 1L, "evt_q-rerun1"));
  }

  private WorkflowEventRecord priorRerunEvent() {
    Map<String, Object> details =
        Map.of(
            "targetStep",
            "investigating",
            "supersededArtifactIds",
            List.of("art_spec_prior"),
            "invalidatedApprovalIds",
            List.of("apr_spec_prior"));
    return new WorkflowEventRecord(
        "evt_rr-prior",
        RUN,
        WorkflowEventType.RECOVERY_RERUN_FROM_STEP,
        WorkflowState.FAILED,
        WorkflowState.INVESTIGATING,
        "alex",
        ActorType.HUMAN,
        "re-spec",
        null,
        true,
        FIXED_NOW,
        details);
  }

  private RecoveryActionSnapshot rerunSnapshot(
      String publicId, String resultStatus, String resultingEventPublicId) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        "rerun",
        FAILURE_EVENT_ID,
        resultingEventPublicId,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-rerun-1");
  }

  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any()))
        .thenAnswer(
            invocation -> {
              TransactionCallback<?> callback = invocation.getArgument(0);
              return callback.doInTransaction(null);
            });
    org.mockito.Mockito.doAnswer(
            invocation -> {
              java.util.function.Consumer<org.springframework.transaction.TransactionStatus>
                  action = invocation.getArgument(0);
              action.accept(null);
              return null;
            })
        .when(template)
        .executeWithoutResult(any());
    return template;
  }
}
