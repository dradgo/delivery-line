package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.integration.conflict.ConflictIntegrationTypes;
import org.dradgo.application.integration.conflict.ConflictResolutionView;
import org.dradgo.application.integration.conflict.ConflictSummary;
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.commands.ReconcileWorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RecoveryServiceReconcileTest {

  private static final String RUN = "run_reconcile1234";
  private static final String CONFLICT = "icf_reconcile123";
  private static final String IDEMPOTENCY_KEY = "idem-reconcile-1234567890";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-07-09T10:00:00Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowCommandService workflowCommandService;
  private WorkflowOrchestrationService workflowOrchestrationService;
  private WorkflowEventReadPort eventReadPort;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;
  private IntegrationConflictService conflictService;
  private IntegrationLinkService integrationLinkService;
  private RecoveryService service;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    workflowOrchestrationService = mock(WorkflowOrchestrationService.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    conflictService = mock(IntegrationConflictService.class);
    integrationLinkService = mock(IntegrationLinkService.class);
    when(conflictService.listUnresolvedConflicts(any())).thenReturn(List.of());
    service =
        new RecoveryService(
            runReadPort,
            eventReadPort,
            mock(RunnerExecutionRecordPort.class),
            mock(ArtifactOperationPort.class),
            workflowCommandService,
            mock(RunnerExecutionQueue.class),
            mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class),
            mock(org.dradgo.application.runner.ManualExecutionDispatcher.class),
            workflowOrchestrationService,
            integrationLinkService,
            eventWritePort,
            recoveryRecordPort,
            conflictService,
            new IdempotencyKeyValidator(),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate(),
            mock(org.dradgo.application.approval.ApprovalService.class),
            mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class),
            mock(org.dradgo.application.workflow.WorkflowTransitionService.class));
  }

  @Test
  void acceptInternalStateTransitionsToReconciledAppendsEventResolvesConflictAndSyncsLinear() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(linearConflict(null)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(reconcileActionSnapshot("rcv_rec-aaaaa", "pending", null));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(reconcileActionSnapshot("rcv_rec-aaaaa", "succeeded", "evt_reconciled-1"));

    ReconcileRecoveryResult result =
        service.reconcile(
            RUN,
            CONFLICT,
            "accept_internal_state",
            IDEMPOTENCY_KEY,
            actor(),
            "operator chose internal state");

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals("rcv_rec-aaaaa", result.recoveryActionPublicId());
    assertEquals(CONFLICT, result.resolvedConflictId());
    assertEquals(WorkflowState.RECONCILED, result.resultingState());

    ArgumentCaptor<ReconcileWorkflowCommand> commandCaptor =
        ArgumentCaptor.forClass(ReconcileWorkflowCommand.class);
    verify(workflowCommandService, times(1)).reconcileWorkflow(commandCaptor.capture());
    assertEquals(RUN, commandCaptor.getValue().workflowRunId());
    assertEquals(CONFLICT, commandCaptor.getValue().conflictId());
    assertEquals("accept_internal_state", commandCaptor.getValue().decision().value());
    assertEquals("operator chose internal state", commandCaptor.getValue().reasonText());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    WorkflowEventRecord event = eventCaptor.getValue();
    assertEquals(WorkflowEventType.RECOVERY_RECONCILED, event.eventType());
    assertEquals(WorkflowState.EXECUTING, event.priorState());
    assertEquals(WorkflowState.RECONCILED, event.resultingState());
    assertEquals(CONFLICT, event.details().get("conflictId"));
    assertEquals("accept_internal_state", event.details().get("reconciliationDecision"));
    assertEquals(IDEMPOTENCY_KEY, event.details().get("idempotencyKey"));

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort).insert(writeCaptor.capture());
    assertEquals("reconcile", writeCaptor.getValue().actionType());
    assertEquals("workflow_owner", writeCaptor.getValue().reviewerRole());
    assertEquals("pending", writeCaptor.getValue().resultStatus());
    // triggering_event_id must be an evt_ id (resolved best-effort from the run's
    // integration.conflictDetected event) or null when unresolvable вЂ” never the icf_ conflict id,
    // which throws invalidPrefix in the persistence adapter. Here the event read port is unstubbed
    // (no conflictDetected event), so the nullable FK is null.
    assertNull(writeCaptor.getValue().triggeringEventPublicId());

    verify(conflictService).resolveConflict(CONFLICT, RUN, "rcv_rec-aaaaa", FIXED_NOW.toInstant());
    // Story 4.6 code review (P3): the prep tx serializes concurrent same-run reconciles by taking
    // the per-run advisory lock before the last-conflict count->transition decision.
    verify(conflictService).lockRunForReconcile(RUN);
    verify(workflowOrchestrationService).syncCompletionToLinear(RUN);
    verify(integrationLinkService, org.mockito.Mockito.never()).syncGitHubPr(any());
    verify(recoveryRecordPort).markSucceeded(IDEMPOTENCY_KEY);
  }

  @Test
  void acceptInternalStateForGithubConflictCommentsOnLinkedPullRequest() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(githubConflict(null)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(reconcileActionSnapshot("rcv_rec-gh000", "pending", null));

    service.reconcile(
        RUN,
        CONFLICT,
        "accept_internal_state",
        IDEMPOTENCY_KEY,
        actor(),
        "operator chose internal state");

    verify(integrationLinkService).commentInternalReconcileOnGitHubPr(RUN, CONFLICT);
    verify(integrationLinkService, org.mockito.Mockito.never()).syncGitHubPr(any());
    verify(workflowOrchestrationService, org.mockito.Mockito.never()).syncCompletionToLinear(any());
  }

  @Test
  void acceptExternalStateForGithubConflictSyncsPullRequest() {
    stubHappyPath(githubConflict(null), "rcv_rec-ghsync");

    ReconcileRecoveryResult result =
        service.reconcile(
            RUN,
            CONFLICT,
            "accept_external_state",
            IDEMPOTENCY_KEY,
            actor(),
            "operator accepts external state");

    assertEquals(WorkflowState.RECONCILED, result.resultingState());
    verify(integrationLinkService).syncGitHubPr(RUN);
    verify(integrationLinkService, org.mockito.Mockito.never())
        .commentInternalReconcileOnGitHubPr(any(), any());
    verify(workflowOrchestrationService, org.mockito.Mockito.never()).syncCompletionToLinear(any());
  }

  @Test
  void markFailedExternallyRecordsAssertionWithoutExternalSideEffect() {
    stubHappyPath(linearConflict(null), "rcv_rec-markfail");

    ReconcileRecoveryResult result =
        service.reconcile(
            RUN,
            CONFLICT,
            "mark_failed_externally",
            IDEMPOTENCY_KEY,
            actor(),
            "operator confirmed external failure");

    assertEquals(WorkflowState.RECONCILED, result.resultingState());
    verify(integrationLinkService, org.mockito.Mockito.never()).syncGitHubPr(any());
    verify(integrationLinkService, org.mockito.Mockito.never())
        .commentInternalReconcileOnGitHubPr(any(), any());
    verify(workflowOrchestrationService, org.mockito.Mockito.never()).syncCompletionToLinear(any());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals(
        "mark_failed_externally", eventCaptor.getValue().details().get("reconciliationDecision"));
  }

  @Test
  void reconcileKeepsRunInCurrentStateWhenOtherUnresolvedConflictsRemain() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(linearConflict(null)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(reconcileActionSnapshot("rcv_rec-multi", "pending", null));
    when(conflictService.listUnresolvedConflicts(any()))
        .thenReturn(List.of(summary("icf_other12345")));

    ReconcileRecoveryResult result =
        service.reconcile(
            RUN,
            CONFLICT,
            "mark_completed_externally",
            IDEMPOTENCY_KEY,
            actor(),
            "operator resolved one conflict");

    assertEquals(WorkflowState.EXECUTING, result.resultingState());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals(WorkflowState.EXECUTING, eventCaptor.getValue().priorState());
    assertEquals(WorkflowState.EXECUTING, eventCaptor.getValue().resultingState());
  }

  @Test
  void replayWithDifferentConflictIsRejectedAsIdempotencyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                reconcileActionSnapshot("rcv_rec-replay", "succeeded", "evt_reconciled-1")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                reconciledEvent(
                    "evt_reconciled-1",
                    "icf_other12345",
                    "accept_internal_state",
                    WorkflowState.RECONCILED)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN, CONFLICT, "accept_internal_state", IDEMPOTENCY_KEY, actor(), " "));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
  }

  @Test
  void replayWithDifferentDecisionIsRejectedAsIdempotencyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                reconcileActionSnapshot("rcv_rec-replay", "succeeded", "evt_reconciled-1")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                reconciledEvent(
                    "evt_reconciled-1",
                    CONFLICT,
                    "accept_external_state",
                    WorkflowState.RECONCILED)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN, CONFLICT, "accept_internal_state", IDEMPOTENCY_KEY, actor(), " "));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
  }

  @Test
  void replayWithMatchingEventDetailsReturnsStoredResultingStateBeforeReasonValidation() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                reconcileActionSnapshot("rcv_rec-replay", "succeeded", "evt_reconciled-1")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                reconciledEvent(
                    "evt_reconciled-1",
                    CONFLICT,
                    "accept_internal_state",
                    WorkflowState.EXECUTING)));

    ReconcileRecoveryResult result =
        service.reconcile(RUN, CONFLICT, "accept_internal_state", IDEMPOTENCY_KEY, actor(), " ");

    assertEquals("evt_reconciled-1", result.reconciledEventPublicId());
    assertEquals(WorkflowState.EXECUTING, result.resultingState());
    assertEquals(CONFLICT, result.resolvedConflictId());
    verify(conflictService, org.mockito.Mockito.never()).findConflictForResolution(any());
  }

  @Test
  void absentConflictIsRejectedAsNotFound() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT)).thenReturn(Optional.empty());

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN,
                    CONFLICT,
                    "accept_internal_state",
                    IDEMPOTENCY_KEY,
                    actor(),
                    "operator chose internal state"));

    assertEquals(DomainErrorCode.CONFLICT_NOT_FOUND, exception.errorCode());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());
  }

  @Test
  void conflictForDifferentRunIsRejectedAsNotFound() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(linearConflictForRun("run_other12345", null)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN,
                    CONFLICT,
                    "accept_internal_state",
                    IDEMPOTENCY_KEY,
                    actor(),
                    "operator chose internal state"));

    assertEquals(DomainErrorCode.CONFLICT_NOT_FOUND, exception.errorCode());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());
  }

  @Test
  void alreadyResolvedConflictIsRejectedBeforeTransition() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(linearConflict(FIXED_NOW)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN,
                    CONFLICT,
                    "accept_internal_state",
                    IDEMPOTENCY_KEY,
                    actor(),
                    "operator chose internal state"));

    assertEquals(DomainErrorCode.CONFLICT_ALREADY_RESOLVED, exception.errorCode());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());
  }

  @Test
  void concurrentlyResolvedConflictIsRejectedFromPrepTransaction() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(linearConflict(null)));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(reconcileActionSnapshot("rcv_rec-race", "pending", null));
    doThrow(new DomainException(DomainErrorCode.CONFLICT_ALREADY_RESOLVED, "resolved"))
        .when(conflictService)
        .resolveConflict(CONFLICT, RUN, "rcv_rec-race", FIXED_NOW.toInstant());

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN,
                    CONFLICT,
                    "accept_internal_state",
                    IDEMPOTENCY_KEY,
                    actor(),
                    "operator chose internal state"));

    assertEquals(DomainErrorCode.CONFLICT_ALREADY_RESOLVED, exception.errorCode());
  }

  @Test
  void terminalRunIsRejectedAsNotApplicable() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.COMPLETED, null, 7L, 0, false)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN,
                    CONFLICT,
                    "accept_internal_state",
                    IDEMPOTENCY_KEY,
                    actor(),
                    "operator chose internal state"));

    assertEquals(DomainErrorCode.RECONCILE_NOT_APPLICABLE, exception.errorCode());
    verify(conflictService, org.mockito.Mockito.never()).findConflictForResolution(any());
  }

  @Test
  void crossActionIdempotencyKeyIsRejectedAsConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(actionSnapshot("rcv_retry-12345", "retry", "succeeded", null)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN,
                    CONFLICT,
                    "accept_internal_state",
                    IDEMPOTENCY_KEY,
                    actor(),
                    "operator chose internal state"));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
    verify(runReadPort, org.mockito.Mockito.never()).findByPublicId(any());
  }

  @Test
  void concurrentIdempotentRaceReplaysStoredResult() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.empty(),
            Optional.of(
                reconcileActionSnapshot("rcv_rec-racereplay", "succeeded", "evt_reconciled-1")));
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT))
        .thenReturn(Optional.of(linearConflict(null)));
    when(workflowCommandService.reconcileWorkflow(any()))
        .thenThrow(new DomainException(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "duplicate"));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                reconciledEvent(
                    "evt_reconciled-1",
                    CONFLICT,
                    "accept_internal_state",
                    WorkflowState.RECONCILED)));

    ReconcileRecoveryResult result =
        service.reconcile(
            RUN,
            CONFLICT,
            "accept_internal_state",
            IDEMPOTENCY_KEY,
            actor(),
            "operator chose internal state");

    assertEquals("rcv_rec-racereplay", result.recoveryActionPublicId());
    assertEquals("evt_reconciled-1", result.reconciledEventPublicId());
    assertEquals(CONFLICT, result.resolvedConflictId());
    assertEquals(WorkflowState.RECONCILED, result.resultingState());
    assertEquals(true, result.replayed());
  }

  @Test
  void missingDecisionIsRejectedBeforeConflictLookup() {
    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.reconcile(RUN, CONFLICT, " ", IDEMPOTENCY_KEY, actor(), "reason"));

    assertEquals(DomainErrorCode.MISSING_RECONCILIATION_DECISION, exception.errorCode());
    verify(conflictService, org.mockito.Mockito.never()).findConflictForResolution(any());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());
  }

  @Test
  void invalidDecisionIsRejectedBeforeConflictLookup() {
    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(RUN, CONFLICT, "overwrite", IDEMPOTENCY_KEY, actor(), "reason"));

    assertEquals(DomainErrorCode.INVALID_RECONCILIATION_DECISION, exception.errorCode());
    verify(conflictService, org.mockito.Mockito.never()).findConflictForResolution(any());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());
  }

  @Test
  void blankReasonIsRejectedBeforeConflictLookup() {
    DomainException exception =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcile(
                    RUN, CONFLICT, "accept_internal_state", IDEMPOTENCY_KEY, actor(), " "));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, exception.errorCode());
    verify(conflictService, org.mockito.Mockito.never()).findConflictForResolution(any());
    verify(workflowCommandService, org.mockito.Mockito.never()).reconcileWorkflow(any());
  }

  private static ConflictResolutionView githubConflict(OffsetDateTime resolvedAt) {
    return new ConflictResolutionView(
        CONFLICT,
        RUN,
        "ilk_github-12345",
        ConflictIntegrationTypes.GITHUB_PR,
        IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value(),
        "octo/hello#42",
        resolvedAt,
        "{}",
        "{}");
  }

  private static ConflictResolutionView linearConflict(OffsetDateTime resolvedAt) {
    return linearConflictForRun(RUN, resolvedAt);
  }

  private static ConflictResolutionView linearConflictForRun(
      String workflowRunId, OffsetDateTime resolvedAt) {
    return new ConflictResolutionView(
        CONFLICT,
        workflowRunId,
        "lnk_linear-12345",
        ConflictIntegrationTypes.LINEAR,
        IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value(),
        "DEL-123",
        resolvedAt,
        "{}",
        "{}");
  }

  private static ConflictSummary summary(String conflictId) {
    return new ConflictSummary(
        conflictId,
        "lnk_other-12345",
        RUN,
        IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value(),
        ConflictIntegrationTypes.LINEAR,
        "DEL-999",
        FIXED_NOW.toInstant());
  }

  private static WorkflowEventRecord reconciledEvent(
      String eventId, String conflictId, String decision, WorkflowState resultingState) {
    return new WorkflowEventRecord(
        eventId,
        RUN,
        WorkflowEventType.RECOVERY_RECONCILED,
        WorkflowState.EXECUTING,
        resultingState,
        "alex",
        ActorType.HUMAN,
        "reason",
        null,
        true,
        FIXED_NOW,
        Map.of("conflictId", conflictId, "reconciliationDecision", decision));
  }

  private RecoveryActionSnapshot reconcileActionSnapshot(
      String publicId, String resultStatus, String resultingEventId) {
    return actionSnapshot(publicId, "reconcile", resultStatus, resultingEventId);
  }

  private RecoveryActionSnapshot actionSnapshot(
      String publicId, String actionType, String resultStatus, String resultingEventId) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        RUN,
        actionType,
        CONFLICT,
        resultingEventId,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private void stubHappyPath(ConflictResolutionView conflict, String recoveryActionId) {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 7L, 0, false)));
    when(conflictService.findConflictForResolution(CONFLICT)).thenReturn(Optional.of(conflict));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(reconcileActionSnapshot(recoveryActionId, "pending", null));
    when(recoveryRecordPort.markSucceeded(IDEMPOTENCY_KEY))
        .thenReturn(reconcileActionSnapshot(recoveryActionId, "succeeded", "evt_reconciled-1"));
  }

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-reconcile-1");
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
