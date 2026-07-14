package org.dradgo.application.artifact;

import static java.nio.charset.StandardCharsets.UTF_8;
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
import org.dradgo.application.artifact.reconciliation.spi.DriftRow;
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
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.DriftCategory;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.16 (AC10) — unit coverage for the {@code ArtifactReconciliationService} repair
 * coordinator: per-category dispatch, the category-legality matrix, missing-required-field
 * rejection, the event/detail + recovery-action + resolve write shape, replay-first precedence, and
 * the re-verify-checksum resolve-on-match / leave-open-on-mismatch branches. All ports are mocked
 * and the per-item {@code TransactionTemplate} is a call-through stub.
 */
class ArtifactReconciliationServiceRepairUnitTest {

  private static final String DRIFT_ID = "adr_repair0001";
  private static final String RUN_ID = "run_repair1234";
  private static final String ARTIFACT_ID = "art_repair0001";
  private static final String OPERATION_ID = "op_repair00001";
  private static final String IDEMPOTENCY_KEY = "idem-artifact-repair-0001";
  private static final ActorContext ACTOR =
      new ActorContext("operator-1", ActorType.HUMAN, "corr-repair-1");

  private ArtifactOperationPort artifactOperationPort;
  private ArtifactRecordPort artifactRecordPort;
  private ArtifactEventPort artifactEventPort;
  private ArtifactDriftReadPort driftReadPort;
  private ArtifactDriftWritePort driftWritePort;
  private RecoveryActionRecordPort recoveryActionRecordPort;
  private ApprovalService approvalService;
  private WorkflowEventWritePort workflowEventWritePort;
  private WorkflowEventReadPort workflowEventReadPort;
  private ArtifactPayloadStore payloadStore;
  private ArtifactReconciliationService service;

  @BeforeEach
  void setUp() {
    artifactOperationPort = mock(ArtifactOperationPort.class);
    artifactRecordPort = mock(ArtifactRecordPort.class);
    artifactEventPort = mock(ArtifactEventPort.class);
    driftReadPort = mock(ArtifactDriftReadPort.class);
    driftWritePort = mock(ArtifactDriftWritePort.class);
    recoveryActionRecordPort = mock(RecoveryActionRecordPort.class);
    approvalService = mock(ApprovalService.class);
    workflowEventWritePort = mock(WorkflowEventWritePort.class);
    workflowEventReadPort = mock(WorkflowEventReadPort.class);
    payloadStore = mock(ArtifactPayloadStore.class);
    service =
        new ArtifactReconciliationService(
            artifactOperationPort,
            artifactRecordPort,
            artifactEventPort,
            Clock.fixed(Instant.parse("2026-05-07T14:00:00Z"), ZoneOffset.UTC),
            Duration.ofMinutes(15),
            callthroughTemplate(),
            driftReadPort,
            driftWritePort,
            recoveryActionRecordPort,
            approvalService,
            workflowEventWritePort,
            workflowEventReadPort,
            payloadStore,
            new IdempotencyKeyValidator());
    // No prior recovery action for the idempotency key unless a test overrides it.
    when(recoveryActionRecordPort.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(recoveryActionRecordPort.insert(any())).thenReturn(recoveryAction("rcv_repair0001"));
    when(driftWritePort.resolveDrift(any(), any(), any())).thenReturn(true);
    when(workflowEventReadPort.findLatestByWorkflowRunPublicIdAndEventTypeIn(any(), any()))
        .thenReturn(Optional.empty());
  }

  @Test
  void markCorruptedResolvesDriftAppendsEventInvalidatesApprovalsAndInsertsRecoveryAction() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.CHECKSUM_MISMATCH, ARTIFACT_ID, null, null)));
    when(artifactRecordPort.markCorrupted(eq(ARTIFACT_ID), any()))
        .thenReturn(artifact(ArtifactStatus.CORRUPTED, "abc123"));
    when(approvalService.invalidateCurrentApproval(eq(RUN_ID), eq("spec"), any()))
        .thenReturn(Optional.of("apr_repair0001"));

    ArtifactRepairResult result =
        service.markCorrupted(DRIFT_ID, "confirmed corrupt on disk", ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.resolved());
    assertFalse(result.replayed());
    assertEquals("mark_corrupted", result.repairAction());
    assertEquals("rcv_repair0001", result.recoveryActionId());

    verify(artifactRecordPort).markCorrupted(eq(ARTIFACT_ID), any());
    verify(approvalService)
        .invalidateCurrentApproval(RUN_ID, "spec", "superseded_by_mark_corrupted");
    verify(driftWritePort).resolveDrift(eq(DRIFT_ID), eq("rcv_repair0001"), any());

    // The single artifact.driftRepaired event carries the repairAction discriminator + is
    // intervention-marked and state-neutral.
    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertEquals(WorkflowEventType.ARTIFACT_DRIFT_REPAIRED, event.getValue().eventType());
    assertTrue(event.getValue().interventionMarker());
    assertNull(event.getValue().priorState());
    assertNull(event.getValue().resultingState());
    assertEquals(
        "mark_corrupted", event.getValue().details().get(WorkflowEventDetailKeys.REPAIR_ACTION));
    assertEquals(DRIFT_ID, event.getValue().details().get(WorkflowEventDetailKeys.DRIFT_ID));

    // recovery_actions row: artifact_repair + succeeded-on-INSERT + resulting_event_id set.
    ArgumentCaptor<RecoveryActionWriteCommand> ra =
        ArgumentCaptor.forClass(RecoveryActionWriteCommand.class);
    verify(recoveryActionRecordPort).insert(ra.capture());
    assertEquals("artifact_repair", ra.getValue().actionType());
    assertEquals("succeeded", ra.getValue().resultStatus());
    assertEquals("workflow_owner", ra.getValue().reviewerRole());
    assertEquals(event.getValue().publicId(), ra.getValue().resultingEventPublicId());
  }

  @Test
  void markOperationFailedReusesOperationPortAndResolvesDrift() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.ORPHAN_OPERATION, null, OPERATION_ID, null)));

    ArtifactRepairResult result =
        service.markOperationFailed(DRIFT_ID, "stale orphan", ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.resolved());
    verify(artifactOperationPort).markFailed(eq(OPERATION_ID), any(), any());
    verify(driftWritePort).resolveDrift(eq(DRIFT_ID), any(), any());
  }

  @Test
  void markOperationCompleteWithoutCompletionEvidenceIsRejected() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.ORPHAN_OPERATION, null, OPERATION_ID, null)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.markOperationComplete(DRIFT_ID, "  ", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.MISSING_REPAIR_REQUIRED_FIELD, error.errorCode());
    verify(artifactOperationPort, never()).markComplete(any());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void repairActionIllegalForCategoryIsRejected() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.CHECKSUM_MISMATCH, ARTIFACT_ID, null, null)));

    // mark_operation_failed is legal only for orphan_operation, not checksum_mismatch.
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.markOperationFailed(DRIFT_ID, "nope", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void unknownDriftIsRejected() {
    when(driftReadPort.findByPublicId(DRIFT_ID)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.markCorrupted(DRIFT_ID, "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.DRIFT_NOT_FOUND, error.errorCode());
  }

  @Test
  void alreadyResolvedDriftIsRejected() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(
            Optional.of(
                drift(
                    DriftCategory.CHECKSUM_MISMATCH,
                    ARTIFACT_ID,
                    null,
                    Instant.parse("2026-05-07T13:00:00Z"))));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.markCorrupted(DRIFT_ID, "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.DRIFT_ALREADY_RESOLVED, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  @Test
  void priorSucceededRepairUnderSameKeyReplaysWithoutASecondInsert() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior0001",
            1L,
            RUN_ID,
            "artifact_repair",
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
                    WorkflowEventType.ARTIFACT_DRIFT_REPAIRED,
                    null,
                    null,
                    "operator-1",
                    ActorType.HUMAN,
                    null,
                    null,
                    true,
                    OffsetDateTime.parse("2026-05-07T13:00:00Z"),
                    java.util.Map.of(
                        WorkflowEventDetailKeys.DRIFT_ID,
                        DRIFT_ID,
                        WorkflowEventDetailKeys.REPAIR_ACTION,
                        "mark_corrupted"))));
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(
            Optional.of(
                drift(
                    DriftCategory.CHECKSUM_MISMATCH,
                    ARTIFACT_ID,
                    null,
                    Instant.parse("2026-05-07T13:00:00Z"))));

    ArtifactRepairResult result = service.markCorrupted(DRIFT_ID, "x", ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.replayed());
    assertEquals("rcv_prior0001", result.recoveryActionId());
    assertEquals("mark_corrupted", result.repairAction());
    verify(recoveryActionRecordPort, never()).insert(any());
    verify(workflowEventWritePort, never()).append(any());
  }

  @Test
  void priorDifferentActionTypeUnderSameKeyConflicts() {
    RecoveryActionSnapshot prior =
        new RecoveryActionSnapshot(
            "rcv_prior0002",
            2L,
            RUN_ID,
            "classify_failure",
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
            () -> service.markCorrupted(DRIFT_ID, "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void reVerifyChecksumResolvesWhenChecksumNowMatches() {
    byte[] payload = "recovered-payload".getBytes(UTF_8);
    String digest = ArtifactChecksum.digestHex("SHA-256", payload).orElseThrow();
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.CHECKSUM_MISMATCH, ARTIFACT_ID, null, null)));
    when(artifactRecordPort.findByPublicId(ARTIFACT_ID))
        .thenReturn(Optional.of(artifactWithRef("ref-1", "SHA-256", digest)));
    when(payloadStore.readBytes("ref-1")).thenReturn(Optional.of(payload));

    ArtifactRepairResult result = service.reVerifyChecksum(DRIFT_ID, ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.resolved());
    verify(driftWritePort).resolveDrift(eq(DRIFT_ID), any(), any());
    verify(driftWritePort, never()).updateLastKnownState(any(), any());
    // No status mutation on a matching re-verify.
    verify(artifactRecordPort, never()).markCorrupted(any(), any());
  }

  @Test
  void reVerifyChecksumLeavesDriftOpenWhenStillMismatched() {
    byte[] payload = "corrupt-payload".getBytes(UTF_8);
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.CHECKSUM_MISMATCH, ARTIFACT_ID, null, null)));
    when(artifactRecordPort.findByPublicId(ARTIFACT_ID))
        .thenReturn(Optional.of(artifactWithRef("ref-1", "SHA-256", "0000000000000000deadbeef")));
    when(payloadStore.readBytes("ref-1")).thenReturn(Optional.of(payload));

    ArtifactRepairResult result = service.reVerifyChecksum(DRIFT_ID, ACTOR, IDEMPOTENCY_KEY);

    assertFalse(result.resolved());
    verify(driftWritePort).updateLastKnownState(eq(DRIFT_ID), any());
    verify(driftWritePort, never()).resolveDrift(any(), any(), any());
    // A recovery_actions row + event are still recorded for the attempt (AC3).
    verify(recoveryActionRecordPort).insert(any());
    verify(workflowEventWritePort).append(any());
  }

  @Test
  void restoreFromBackupIsRejectedAsForwardCompatStub() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.MISSING_PAYLOAD, ARTIFACT_ID, null, null)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.restoreFromBackup(DRIFT_ID, "s3://bucket/key", ACTOR, IDEMPOTENCY_KEY));
    // OQ-2 resolved -> option 2: a legal-but-unimplemented action surfaces the dedicated
    // NOT_IMPLEMENTED code, not the semantically-wrong category-legality error.
    assertEquals(DomainErrorCode.REPAIR_ACTION_NOT_IMPLEMENTED, error.errorCode());
    // The stub rolls back inside the tx — nothing is persisted.
    verify(driftWritePort, never()).resolveDrift(any(), any(), any());
  }

  @Test
  void markPayloadUnavailableFailsArtifactInvalidatesApprovalsAndResolvesDrift() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.MISSING_PAYLOAD, ARTIFACT_ID, null, null)));
    when(artifactRecordPort.markPayloadUnavailable(eq(ARTIFACT_ID), any()))
        .thenReturn(artifact(ArtifactStatus.FAILED, "abc123"));
    when(approvalService.invalidateCurrentApproval(eq(RUN_ID), eq("spec"), any()))
        .thenReturn(Optional.of("apr_repair0002"));

    ArtifactRepairResult result =
        service.markPayloadUnavailable(
            DRIFT_ID, "payload deleted upstream", ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.resolved());
    assertFalse(result.replayed());
    assertEquals("mark_payload_unavailable", result.repairAction());

    verify(artifactRecordPort).markPayloadUnavailable(eq(ARTIFACT_ID), any());
    // AVAILABLE -> FAILED renders the artifact unusable, so prior approvals are invalidated (AC4)
    // with the payload-unavailable reason token.
    verify(approvalService)
        .invalidateCurrentApproval(RUN_ID, "spec", "superseded_by_mark_payload_unavailable");
    verify(driftWritePort).resolveDrift(eq(DRIFT_ID), eq("rcv_repair0001"), any());

    ArgumentCaptor<WorkflowEventRecord> event = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(workflowEventWritePort).append(event.capture());
    assertEquals(
        "mark_payload_unavailable",
        event.getValue().details().get(WorkflowEventDetailKeys.REPAIR_ACTION));
    verify(recoveryActionRecordPort).insert(any());
  }

  @Test
  void markOperationCompleteReusesOperationPortAndResolvesDrift() {
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.ORPHAN_OPERATION, null, OPERATION_ID, null)));

    ArtifactRepairResult result =
        service.markOperationComplete(
            DRIFT_ID, "verified complete in registry", ACTOR, IDEMPOTENCY_KEY);

    assertTrue(result.resolved());
    assertEquals("mark_operation_complete", result.repairAction());
    verify(artifactOperationPort).markComplete(OPERATION_ID);
    verify(driftWritePort).resolveDrift(eq(DRIFT_ID), any(), any());
  }

  @Test
  void blankDriftIdIsRejectedAsInvalidCommandPayload() {
    // code-review F1 — the CLI's --drift is optional, so a blank driftId must surface a typed error
    // rather than a NullPointerException 500 from findByPublicId's requireNonNull.
    DomainException error =
        assertThrows(
            DomainException.class, () -> service.markCorrupted("  ", "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    verify(driftReadPort, never()).findByPublicId(any());
  }

  @Test
  void oversizedReasonIsRejectedAsInvalidCommandPayload() {
    // code-review F3 — the CLI has no bean validation, so the service enforces the REST @Size bound
    // to keep the two surfaces equivalent (AC8).
    String tooLong = "x".repeat(513);
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.markCorrupted(DRIFT_ID, tooLong, ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  // ------------------------------------------------------------------------------------------------

  @Test
  void reVerifyChecksumThatLosesTheResolveRaceIsRejectedAsAlreadyResolved() {
    // code-review F2 — two distinct-key re-verifies both pass the Step-2 unresolved check and both
    // recompute a matching checksum; the loser's resolveDrift updates 0 rows (WHERE resolved_at IS
    // NULL) -> the whole tx rolls back with DRIFT_ALREADY_RESOLVED instead of committing a
    // duplicate
    // artifact.driftRepaired event + recovery_actions row and falsely reporting success.
    byte[] payload = "recovered-payload".getBytes(UTF_8);
    String digest = ArtifactChecksum.digestHex("SHA-256", payload).orElseThrow();
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.CHECKSUM_MISMATCH, ARTIFACT_ID, null, null)));
    when(artifactRecordPort.findByPublicId(ARTIFACT_ID))
        .thenReturn(Optional.of(artifactWithRef("ref-1", "SHA-256", digest)));
    when(payloadStore.readBytes("ref-1")).thenReturn(Optional.of(payload));
    when(driftWritePort.resolveDrift(any(), any(), any())).thenReturn(false); // lost the race

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.reVerifyChecksum(DRIFT_ID, ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.DRIFT_ALREADY_RESOLVED, error.errorCode());
  }

  @Test
  void nullTargetAxisOnDriftRowSurfacesInternalErrorNotNpe() {
    // code-review F9 — a checksum_mismatch drift whose artifactId is null (corrupt/hand-inserted
    // row) must surface a diagnostic INTERNAL_ERROR before any port call, not an opaque NPE 500.
    when(driftReadPort.findByPublicId(DRIFT_ID))
        .thenReturn(Optional.of(drift(DriftCategory.CHECKSUM_MISMATCH, null, null, null)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.markCorrupted(DRIFT_ID, "x", ACTOR, IDEMPOTENCY_KEY));
    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    verify(recoveryActionRecordPort, never()).insert(any());
  }

  private static DriftRow drift(
      DriftCategory category, String artifactId, String operationId, Instant resolvedAt) {
    return new DriftRow(
        DRIFT_ID,
        RUN_ID,
        artifactId,
        operationId,
        category,
        Instant.parse("2026-05-07T12:00:00Z"),
        "{}",
        resolvedAt);
  }

  private static ArtifactRecordSnapshot artifact(ArtifactStatus status, String checksumValue) {
    return artifactWithRef("ref-1", "SHA-256", checksumValue, status);
  }

  private static ArtifactRecordSnapshot artifactWithRef(
      String storageRef, String algorithm, String checksumValue) {
    return artifactWithRef(storageRef, algorithm, checksumValue, ArtifactStatus.AVAILABLE);
  }

  private static ArtifactRecordSnapshot artifactWithRef(
      String storageRef, String algorithm, String checksumValue, ArtifactStatus status) {
    return ArtifactRecordSnapshot.withoutFailureMetadata(
        ARTIFACT_ID,
        RUN_ID,
        ArtifactType.SPEC,
        1,
        null,
        DataClassification.LOCAL_ONLY,
        storageRef,
        algorithm,
        checksumValue,
        status,
        null);
  }

  private static RecoveryActionSnapshot recoveryAction(String publicId) {
    return new RecoveryActionSnapshot(
        publicId,
        99L,
        RUN_ID,
        "artifact_repair",
        null,
        "evt_repair0001",
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
