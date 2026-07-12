package org.dradgo.application.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.spi.FailureClassificationRecord;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunFailureClassificationPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureTaxonomyValue;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.9 (AC12) — the classifyFailure unit matrix, mirroring {@code
 * RecoveryServiceReconcileTest}'s shape. The load-bearing negative assertions are the AC10
 * metadata-only invariants: NO WorkflowCommandService transition, NO runner enqueue, NO
 * markSucceeded flip (the row inserts {@code succeeded} directly — R16).
 */
class RecoveryServiceClassifyFailureTest {

  private static final String RUN = "run_classify12345";
  private static final String IDEMPOTENCY_KEY = "idem-classify-1234567890";
  private static final String TAXONOMY = "specification_gap";
  private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-07-12T10:00:00Z");

  private WorkflowRunReadPort runReadPort;
  private WorkflowCommandService workflowCommandService;
  private WorkflowEventReadPort eventReadPort;
  private WorkflowEventWritePort eventWritePort;
  private RecoveryActionRecordPort recoveryRecordPort;
  private WorkflowRunFailureClassificationPort classificationPort;
  private RunnerExecutionQueue runnerExecutionQueue;
  private RecoveryService service;

  @BeforeEach
  void setUp() {
    runReadPort = mock(WorkflowRunReadPort.class);
    workflowCommandService = mock(WorkflowCommandService.class);
    eventReadPort = mock(WorkflowEventReadPort.class);
    eventWritePort = mock(WorkflowEventWritePort.class);
    recoveryRecordPort = mock(RecoveryActionRecordPort.class);
    classificationPort = mock(WorkflowRunFailureClassificationPort.class);
    runnerExecutionQueue = mock(RunnerExecutionQueue.class);
    service =
        new RecoveryService(
            runReadPort,
            eventReadPort,
            mock(RunnerExecutionRecordPort.class),
            mock(ArtifactOperationPort.class),
            workflowCommandService,
            runnerExecutionQueue,
            mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class),
            mock(org.dradgo.application.runner.ManualExecutionDispatcher.class),
            mock(WorkflowOrchestrationService.class),
            mock(IntegrationLinkService.class),
            eventWritePort,
            recoveryRecordPort,
            mock(IntegrationConflictService.class),
            new IdempotencyKeyValidator(),
            Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC),
            callthroughTemplate(),
            callthroughTemplate(),
            mock(org.dradgo.application.approval.ApprovalService.class),
            mock(org.dradgo.application.artifact.spi.ArtifactRecordPort.class),
            mock(org.dradgo.application.workflow.WorkflowTransitionService.class),
            mock(org.dradgo.application.runner.spi.RunnerAdapter.class),
            classificationPort);
  }

  @Test
  void classifyOnFailedRunUpdatesColumnAppendsEventAndInsertsSucceededRowWithoutTransition() {
    stubFreshKey();
    stubFailedRun();
    stubNoTriggeringEvent();
    when(classificationPort.applyClassification(any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(classifyActionSnapshot("rcv_cls-aaaaa", "succeeded", "evt_classified-1"));

    ClassifyFailureResult result =
        service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), "spec missed the edge");

    assertNotNull(result);
    assertFalse(result.replayed());
    assertEquals("rcv_cls-aaaaa", result.recoveryActionPublicId());
    assertEquals(TAXONOMY, result.taxonomyValue());
    assertNull(result.priorTaxonomyValue());

    verify(classificationPort)
        .applyClassification(
            RUN, TAXONOMY, FIXED_NOW.withOffsetSameInstant(ZoneOffset.UTC), "alex");

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    WorkflowEventRecord event = eventCaptor.getValue();
    assertEquals(WorkflowEventType.RECOVERY_FAILURE_CLASSIFIED, event.eventType());
    // Non-transition audit shape (the audit.logDownloaded precedent): prior == resulting ==
    // current state; failureCategory stays null (the taxonomy is the orthogonal human axis).
    assertEquals(WorkflowState.FAILED, event.priorState());
    assertEquals(WorkflowState.FAILED, event.resultingState());
    assertNull(event.failureCategory());
    assertTrue(event.interventionMarker());
    assertEquals(TAXONOMY, event.details().get("taxonomyValue"));
    assertEquals(IDEMPOTENCY_KEY, event.details().get("idempotencyKey"));
    assertEquals("spec missed the edge", event.details().get("reason"));
    assertFalse(event.details().containsKey("priorTaxonomyValue"));

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort).insert(writeCaptor.capture());
    assertEquals("classify_failure", writeCaptor.getValue().actionType());
    assertEquals("workflow_owner", writeCaptor.getValue().reviewerRole());
    // R16 — succeeded on INSERT; nothing runs post-commit that a pending row could wait on.
    assertEquals("succeeded", writeCaptor.getValue().resultStatus());
    assertEquals(event.publicId(), writeCaptor.getValue().resultingEventPublicId());
    assertNull(writeCaptor.getValue().triggeringEventPublicId());

    // AC10 metadata-only invariants: no transition, no dispatch, no pending→succeeded flip.
    verifyNoInteractions(workflowCommandService);
    verifyNoInteractions(runnerExecutionQueue);
    verify(recoveryRecordPort, never()).markSucceeded(any());
    verify(recoveryRecordPort, never()).markFailed(any());
  }

  @Test
  void reclassifyOverwritesColumnAndCarriesPriorTaxonomyValueOnEventAndResult() {
    stubFreshKey();
    stubFailedRun();
    stubNoTriggeringEvent();
    when(classificationPort.applyClassification(any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new FailureClassificationRecord(
                    FailureTaxonomyValue.CONTEXT_GAP, FIXED_NOW.minusHours(2), "amelia")));
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(classifyActionSnapshot("rcv_cls-bbbbb", "succeeded", "evt_classified-2"));

    ClassifyFailureResult result =
        service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null);

    assertEquals(TAXONOMY, result.taxonomyValue());
    assertEquals("context_gap", result.priorTaxonomyValue());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals("context_gap", eventCaptor.getValue().details().get("priorTaxonomyValue"));
    assertEquals(TAXONOMY, eventCaptor.getValue().details().get("taxonomyValue"));
  }

  @Test
  void classifyResolvesTriggeringFailureEventBestEffort() {
    stubFreshKey();
    stubFailedRun();
    when(eventReadPort.findLatestFailureEvent(RUN))
        .thenReturn(Optional.of(failureEvent("evt_failure-1")));
    when(classificationPort.applyClassification(any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenReturn(classifyActionSnapshot("rcv_cls-ccccc", "succeeded", "evt_classified-3"));

    service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null);

    ArgumentCaptor<RecoveryActionWriteCommand> writeCaptor =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryRecordPort).insert(writeCaptor.capture());
    assertEquals("evt_failure-1", writeCaptor.getValue().triggeringEventPublicId());
  }

  @Test
  void classifyOnNonFailedStatesIsRejectedAsNotApplicable() {
    for (WorkflowState state :
        List.of(WorkflowState.EXECUTING, WorkflowState.COMPLETED, WorkflowState.PAUSED)) {
      stubFreshKey();
      when(runReadPort.findByPublicId(RUN))
          .thenReturn(Optional.of(new WorkflowRunSnapshot(RUN, state, null, 7L, 0, false)));

      DomainException exception =
          assertThrows(
              DomainException.class,
              () -> service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null));

      assertEquals(DomainErrorCode.CLASSIFY_NOT_APPLICABLE, exception.errorCode());
      assertEquals(state.value(), exception.details().get("currentState"));
    }
    verify(classificationPort, never()).applyClassification(any(), any(), any(), any());
    verifyNoInteractions(eventWritePort);
  }

  @Test
  void missingTaxonomyValueIsRejectedBeforeRunLookup() {
    stubFreshKey();

    for (String raw : new String[] {null, " "}) {
      DomainException exception =
          assertThrows(
              DomainException.class,
              () -> service.classifyFailure(RUN, raw, IDEMPOTENCY_KEY, actor(), null));
      assertEquals(DomainErrorCode.MISSING_TAXONOMY_VALUE, exception.errorCode());
    }
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void invalidTaxonomyValueIsRejectedBeforeRunLookup() {
    stubFreshKey();

    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.classifyFailure(RUN, "not_a_value", IDEMPOTENCY_KEY, actor(), null));

    assertEquals(DomainErrorCode.INVALID_TAXONOMY_VALUE, exception.errorCode());
    assertEquals("not_a_value", exception.details().get("provided"));
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void missingRunIsRejectedAsRunNotFound() {
    stubFreshKey();
    when(runReadPort.findByPublicId(RUN)).thenReturn(Optional.empty());

    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null));

    assertEquals(DomainErrorCode.RUN_NOT_FOUND, exception.errorCode());
  }

  @Test
  void replayWithSameTaxonomyValueReturnsStoredResultBeforeInputValidation() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(classifyActionSnapshot("rcv_cls-replay", "succeeded", "evt_classified-1")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(classifiedEvent("evt_classified-1", TAXONOMY, "context_gap")));

    ClassifyFailureResult result =
        service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null);

    assertTrue(result.replayed());
    assertEquals("rcv_cls-replay", result.recoveryActionPublicId());
    assertEquals("evt_classified-1", result.classifiedEventPublicId());
    assertEquals(TAXONOMY, result.taxonomyValue());
    assertEquals("context_gap", result.priorTaxonomyValue());
    verify(runReadPort, never()).findByPublicId(any());
    verify(classificationPort, never()).applyClassification(any(), any(), any(), any());
  }

  @Test
  void replayWithDifferentTaxonomyValueIsRejectedAsIdempotencyConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(classifyActionSnapshot("rcv_cls-replay", "succeeded", "evt_classified-1")));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(classifiedEvent("evt_classified-1", "context_gap", null)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
  }

  @Test
  void crossActionIdempotencyKeyIsRejectedAsConflict() {
    // The 4.5 review catch: a prior RESUME row under the same key must never echo back as a
    // false classify replay — the actionType guard is mandatory from day one.
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(actionSnapshot("rcv_resume-1234", RUN, "resume", "succeeded", null)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
    assertEquals("resume", exception.details().get("priorActionType"));
    verify(runReadPort, never()).findByPublicId(any());
  }

  @Test
  void differentRunUnderSameKeyIsRejectedAsConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                actionSnapshot(
                    "rcv_cls-other", "run_other1234567", "classify_failure", "succeeded", null)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
  }

  @Test
  void nonSucceededPriorAttemptIsRejectedAsConflict() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.of(
                actionSnapshot("rcv_cls-pending", RUN, "classify_failure", "pending", null)));

    DomainException exception =
        assertThrows(
            DomainException.class,
            () -> service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null));

    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.errorCode());
  }

  @Test
  void concurrentIdempotentRaceReplaysStoredResult() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(
            Optional.empty(),
            Optional.of(
                classifyActionSnapshot("rcv_cls-racereplay", "succeeded", "evt_classified-1")));
    stubFailedRun();
    stubNoTriggeringEvent();
    when(classificationPort.applyClassification(any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(recoveryRecordPort.insert(any(RecoveryActionWriteCommand.class)))
        .thenThrow(new DomainException(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "duplicate"));
    when(eventReadPort.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(List.of(classifiedEvent("evt_classified-1", TAXONOMY, null)));

    ClassifyFailureResult result =
        service.classifyFailure(RUN, TAXONOMY, IDEMPOTENCY_KEY, actor(), null);

    assertTrue(result.replayed());
    assertEquals("rcv_cls-racereplay", result.recoveryActionPublicId());
    assertEquals("evt_classified-1", result.classifiedEventPublicId());
    assertEquals(TAXONOMY, result.taxonomyValue());
  }

  private void stubFreshKey() {
    when(recoveryRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
  }

  private void stubFailedRun() {
    when(runReadPort.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.FAILED, null, 7L, 0, false)));
  }

  private void stubNoTriggeringEvent() {
    when(eventReadPort.findLatestFailureEvent(RUN)).thenReturn(Optional.empty());
  }

  private static WorkflowEventRecord failureEvent(String eventId) {
    return new WorkflowEventRecord(
        eventId,
        RUN,
        WorkflowEventType.RUNNER_FAILED,
        WorkflowState.EXECUTING,
        WorkflowState.FAILED,
        "system",
        ActorType.SYSTEM,
        "runner failed",
        null,
        false,
        FIXED_NOW.minusHours(1),
        Map.of());
  }

  private static WorkflowEventRecord classifiedEvent(
      String eventId, String taxonomyValue, String priorTaxonomyValue) {
    Map<String, Object> details =
        priorTaxonomyValue == null
            ? Map.of("taxonomyValue", taxonomyValue)
            : Map.of("taxonomyValue", taxonomyValue, "priorTaxonomyValue", priorTaxonomyValue);
    return new WorkflowEventRecord(
        eventId,
        RUN,
        WorkflowEventType.RECOVERY_FAILURE_CLASSIFIED,
        WorkflowState.FAILED,
        WorkflowState.FAILED,
        "alex",
        ActorType.HUMAN,
        null,
        null,
        true,
        FIXED_NOW,
        details);
  }

  private static RecoveryActionSnapshot classifyActionSnapshot(
      String publicId, String resultStatus, String resultingEventId) {
    return actionSnapshot(publicId, RUN, "classify_failure", resultStatus, resultingEventId);
  }

  private static RecoveryActionSnapshot actionSnapshot(
      String publicId,
      String runId,
      String actionType,
      String resultStatus,
      String resultingEventId) {
    return new RecoveryActionSnapshot(
        publicId,
        1L,
        runId,
        actionType,
        null,
        resultingEventId,
        "alex",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        resultStatus,
        FIXED_NOW,
        "workflow_owner");
  }

  private static ActorContext actor() {
    return new ActorContext("alex", ActorType.HUMAN, "corr-classify-1");
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
