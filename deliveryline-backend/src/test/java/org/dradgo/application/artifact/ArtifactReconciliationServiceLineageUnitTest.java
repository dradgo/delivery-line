package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftWritePort;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.16a (AC10) — unit coverage for the {@code ArtifactReconciliationService} lineage-recovery
 * coordinator: the three typed actions (reattach / terminate / fork), the event/detail +
 * recovery-action write shape, action-parse + required-field rejection, replay-first precedence,
 * and the idempotency-key action_type conflict. All ports are mocked and the per-item {@code
 * TransactionTemplate} is a call-through stub.
 */
class ArtifactReconciliationServiceLineageUnitTest {

  private static final String RUN_ID = "run_lineage1234";
  private static final String TARGET_ARTIFACT_ID = "art_lineage0001";
  private static final String PARENT_ARTIFACT_ID = "art_lineage0002";
  private static final String FORK_ARTIFACT_ID = "art_forkhead001";
  private static final String OPERATION_ID = "op_lineage00001";
  private static final String IDEMPOTENCY_KEY = "idem-lineage-reconcile-0001";
  private static final ActorContext ACTOR =
      new ActorContext("operator-1", ActorType.HUMAN, "corr-lineage-1");

  private ArtifactOperationPort artifactOperationPort;
  private ArtifactRecordPort artifactRecordPort;
  private RecoveryActionRecordPort recoveryActionRecordPort;
  private ApprovalService approvalService;
  private WorkflowEventWritePort workflowEventWritePort;
  private WorkflowEventReadPort workflowEventReadPort;
  private ArtifactReconciliationService service;

  @BeforeEach
  void setUp() {
    artifactOperationPort = mock(ArtifactOperationPort.class);
    artifactRecordPort = mock(ArtifactRecordPort.class);
    recoveryActionRecordPort = mock(RecoveryActionRecordPort.class);
    approvalService = mock(ApprovalService.class);
    workflowEventWritePort = mock(WorkflowEventWritePort.class);
    workflowEventReadPort = mock(WorkflowEventReadPort.class);
    service =
        new ArtifactReconciliationService(
            artifactOperationPort,
            artifactRecordPort,
            mock(ArtifactEventPort.class),
            Clock.fixed(Instant.parse("2026-05-07T14:00:00Z"), ZoneOffset.UTC),
            Duration.ofMinutes(15),
            callthroughTemplate(),
            mock(ArtifactDriftReadPort.class),
            mock(ArtifactDriftWritePort.class),
            recoveryActionRecordPort,
            approvalService,
            workflowEventWritePort,
            workflowEventReadPort,
            mock(ArtifactPayloadStore.class),
            new IdempotencyKeyValidator());
    when(recoveryActionRecordPort.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(recoveryActionRecordPort.insert(any())).thenReturn(recoveryAction("rcv_lineage0001"));
    when(artifactRecordPort.findByPublicId(TARGET_ARTIFACT_ID))
        .thenReturn(Optional.of(target(ArtifactStatus.AVAILABLE)));
  }

  @Test
  void reattachRecordsChosenParentReferenceAppendsEventAndInsertsRecoveryAction() {
    LineageReconciliationResult result =
        service.reattachToExistingLineage(
            TARGET_ARTIFACT_ID, PARENT_ARTIFACT_ID, "operator picked leaf", ACTOR, IDEMPOTENCY_KEY);

    assertFalse(result.replayed());
    assertEquals("reattach_to_existing_lineage", result.lineageAction());
    assertEquals("rcv_lineage0001", result.recoveryActionId());
    assertEquals(PARENT_ARTIFACT_ID, result.lineageReferenceArtifactId());

    verify(artifactRecordPort).reattachToLineage(TARGET_ARTIFACT_ID, PARENT_ARTIFACT_ID);

    // The single artifact.lineageReconciled event carries the lineageAction discriminator + the
    // chosen-parent reference; state-neutral + intervention-marked.
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertEquals(WorkflowEventType.ARTIFACT_LINEAGE_RECONCILED, event.getValue().eventType());
    assertTrue(event.getValue().interventionMarker());
    assertNull(event.getValue().priorState());
    assertNull(event.getValue().resultingState());
    assertEquals(
        "reattach_to_existing_lineage",
        event.getValue().details().get(WorkflowEventDetailKeys.LINEAGE_ACTION));
    assertEquals(
        TARGET_ARTIFACT_ID, event.getValue().details().get(WorkflowEventDetailKeys.ARTIFACT_ID));
    assertEquals(
        PARENT_ARTIFACT_ID,
        event.getValue().details().get(WorkflowEventDetailKeys.LINEAGE_REFERENCE_ARTIFACT_ID));

    // recovery_actions row: artifact_lineage_reconcile + succeeded-on-INSERT + null triggering +
    // the
    // event as resulting_event_id.
    ArgumentCaptor<RecoveryActionWriteCommand> ra =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryActionRecordPort).insert(ra.capture());
    assertEquals("artifact_lineage_reconcile", ra.getValue().actionType());
    assertEquals("succeeded", ra.getValue().resultStatus());
    assertEquals("workflow_owner", ra.getValue().reviewerRole());
    assertNull(ra.getValue().triggeringEventPublicId());
    assertEquals(event.getValue().publicId(), ra.getValue().resultingEventPublicId());
  }

  @Test
  void terminateMarksLineageTerminatedClosesPendingOpAndInvalidatesApprovals() {
    when(artifactOperationPort.findPendingByArtifactId(TARGET_ARTIFACT_ID))
        .thenReturn(Optional.of(pendingOperation()));
    when(approvalService.invalidateApprovalForArtifact(eq(TARGET_ARTIFACT_ID), any()))
        .thenReturn(Optional.of("apr_lineage0001"));

    LineageReconciliationResult result =
        service.terminateAmbiguousLineage(
            TARGET_ARTIFACT_ID, "abandoning ambiguous lineage", ACTOR, IDEMPOTENCY_KEY);

    assertEquals("terminate_ambiguous_lineage", result.lineageAction());
    assertNull(result.lineageReferenceArtifactId());

    verify(artifactRecordPort).markLineageTerminated(eq(TARGET_ARTIFACT_ID), any());
    verify(artifactOperationPort).markFailedOrphan(eq(OPERATION_ID), any());
    verify(approvalService)
        .invalidateApprovalForArtifact(TARGET_ARTIFACT_ID, "superseded_by_lineage_termination");

    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertEquals(
        "terminate_ambiguous_lineage",
        event.getValue().details().get(WorkflowEventDetailKeys.LINEAGE_ACTION));
    assertNull(
        event.getValue().details().get(WorkflowEventDetailKeys.LINEAGE_REFERENCE_ARTIFACT_ID));
    verify(recoveryActionRecordPort).insert(any());
  }

  @Test
  void terminateWithoutPendingOperationStillReconciles() {
    when(artifactOperationPort.findPendingByArtifactId(TARGET_ARTIFACT_ID))
        .thenReturn(Optional.empty());

    LineageReconciliationResult result =
        service.terminateAmbiguousLineage(TARGET_ARTIFACT_ID, "abandon", ACTOR, IDEMPOTENCY_KEY);

    assertEquals("terminate_ambiguous_lineage", result.lineageAction());
    verify(artifactRecordPort).markLineageTerminated(eq(TARGET_ARTIFACT_ID), any());
    verify(artifactOperationPort, never()).markFailedOrphan(any(), any());
    verify(recoveryActionRecordPort).insert(any());
  }

  @Test
  void createExplicitForkRecordsNewForkHeadAsReference() {
    when(artifactRecordPort.createLineageRecoveryFork(eq(TARGET_ARTIFACT_ID), any(), any()))
        .thenReturn(forkHead());

    LineageReconciliationResult result =
        service.createExplicitFork(
            TARGET_ARTIFACT_ID, "start a fresh lineage", ACTOR, IDEMPOTENCY_KEY);

    assertEquals("create_explicit_fork", result.lineageAction());
    assertEquals(FORK_ARTIFACT_ID, result.lineageReferenceArtifactId());

    verify(artifactRecordPort).createLineageRecoveryFork(eq(TARGET_ARTIFACT_ID), any(), any());
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertEquals(
        "create_explicit_fork",
        event.getValue().details().get(WorkflowEventDetailKeys.LINEAGE_ACTION));
    assertEquals(
        FORK_ARTIFACT_ID,
        event.getValue().details().get(WorkflowEventDetailKeys.LINEAGE_REFERENCE_ARTIFACT_ID));
    // Fork never invalidates approvals (only terminate does).
    verify(approvalService, never()).invalidateApprovalForArtifact(any(), any());
  }

  @Test
  void reattachWithoutChosenParentIsRejected() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.reattachToExistingLineage(
                    TARGET_ARTIFACT_ID, "  ", "reason", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.MISSING_LINEAGE_RECOVERY_FIELD, error.errorCode());
    verify(artifactRecordPort, never()).reattachToLineage(any(), any());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void unknownLineageActionIsRejected() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.reconcileLineage(
                    new ReconcileLineageCommand(
                        TARGET_ARTIFACT_ID, "no_such_action", null, "x", ACTOR, IDEMPOTENCY_KEY)));
    assertEquals(DomainErrorCode.INVALID_LINEAGE_RECOVERY_ACTION, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void unknownTargetArtifactIsRejected() {
    when(artifactRecordPort.findByPublicId(TARGET_ARTIFACT_ID)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.terminateAmbiguousLineage(TARGET_ARTIFACT_ID, "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void blankTargetArtifactIdIsRejectedBeforeLookup() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.terminateAmbiguousLineage("  ", "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    verify(artifactRecordPort, never()).findByPublicId(any());
  }

  @Test
  void oversizedReasonIsRejectedAsInvalidCommandPayload() {
    String tooLong = "x".repeat(513);
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.terminateAmbiguousLineage(
                    TARGET_ARTIFACT_ID, tooLong, ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void priorSucceededReconcileUnderSameKeyReplaysWithoutASecondWrite() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior0001",
            1L,
            RUN_ID,
            "artifact_lineage_reconcile",
            null,
            "evt_prior0001",
            "operator-1",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            OffsetDateTime.parse("2026-05-07T13:00:00Z"));
    when(recoveryActionRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(prior));
    when(workflowEventReadPort.listByWorkflowRunPublicId(RUN_ID, null))
        .thenReturn(
            List.of(
                new WorkflowEventRecord(
                    "evt_prior0001",
                    RUN_ID,
                    WorkflowEventType.ARTIFACT_LINEAGE_RECONCILED,
                    null,
                    null,
                    "operator-1",
                    ActorType.HUMAN,
                    null,
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-07T13:00:00Z"),
                    java.util.Map.of(
                        WorkflowEventDetailKeys.ARTIFACT_ID,
                        TARGET_ARTIFACT_ID,
                        WorkflowEventDetailKeys.LINEAGE_ACTION,
                        "reattach_to_existing_lineage",
                        WorkflowEventDetailKeys.LINEAGE_REFERENCE_ARTIFACT_ID,
                        PARENT_ARTIFACT_ID))));

    LineageReconciliationResult result =
        service.reattachToExistingLineage(
            TARGET_ARTIFACT_ID, PARENT_ARTIFACT_ID, "x", ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.replayed());
    assertEquals("rcv_prior0001", result.recoveryActionId());
    assertEquals("reattach_to_existing_lineage", result.lineageAction());
    assertEquals(PARENT_ARTIFACT_ID, result.lineageReferenceArtifactId());
    verify(recoveryActionRecordPort, never()).insert(any());
    verify(workflowEventWritePort, never()).append(any());
    verify(artifactRecordPort, never()).reattachToLineage(any(), any());
  }

  @Test
  void priorDifferentActionTypeUnderSameKeyConflicts() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior0002",
            2L,
            RUN_ID,
            "artifact_repair",
            null,
            "evt_prior0002",
            "operator-1",
            ActorType.HUMAN,
            IDEMPOTENCY_KEY,
            "succeeded",
            OffsetDateTime.parse("2026-05-07T13:00:00Z"));
    when(recoveryActionRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.of(prior));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.terminateAmbiguousLineage(TARGET_ARTIFACT_ID, "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  private static ArtifactRecordSnapshot target(ArtifactStatus status) {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        TARGET_ARTIFACT_ID,
        RUN_ID,
        ArtifactType.SPEC,
        2,
        null,
        DataClassification.LOCAL_ONLY,
        "ref-1",
        "SHA-256",
        "abc123",
        status,
        null);
  }

  private static ArtifactRecordSnapshot forkHead() {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        FORK_ARTIFACT_ID,
        RUN_ID,
        ArtifactType.SPEC,
        3,
        null,
        DataClassification.LOCAL_ONLY,
        null,
        null,
        null,
        ArtifactStatus.PENDING,
        null);
  }

  private static ArtifactOperationSnapshot pendingOperation() {
    return new ArtifactOperationSnapshot(
        OPERATION_ID,
        RUN_ID,
        TARGET_ARTIFACT_ID,
        "create",
        ArtifactOperationStatus.PENDING,
        "idem-op-1",
        null,
        null,
        OffsetDateTime.parse("2026-05-07T12:00:00Z"));
  }

  private static RecoveryActionSnapshot recoveryAction(String publicId) {
    return new RecoveryActionSnapshot(
        publicId,
        99L,
        RUN_ID,
        "artifact_lineage_reconcile",
        null,
        "evt_lineage0001",
        "operator-1",
        ActorType.HUMAN,
        IDEMPOTENCY_KEY,
        "succeeded",
        OffsetDateTime.parse("2026-05-07T14:00:00Z"));
  }

  @SuppressWarnings("unchecked")
  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any(TransactionCallback.class)))
        .thenAnswer(
            invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
    return template;
  }
}
