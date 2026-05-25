package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.artifact.spi.ArtifactRunnerExecutionPort;
import org.dradgo.application.artifact.spi.ArtifactWorkflowRunStatePort;
import org.dradgo.application.clarification.ClarificationLifecycleOrchestrator;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ArtifactOperationServiceUnitTest {

  private static final ActorContext OPERATOR_ACTOR =
      new ActorContext("alex", ActorType.HUMAN, "corr-1");
  private static final byte[] PAYLOAD_BYTES = "spec content".getBytes(StandardCharsets.UTF_8);
  private static final String PAYLOAD_DIGEST_HEX =
      ArtifactChecksum.digestHex("SHA-256", PAYLOAD_BYTES).orElseThrow();
  private static final ArtifactChecksum VALID_CHECKSUM =
      new ArtifactChecksum("SHA-256", PAYLOAD_DIGEST_HEX);

  @Test
  void markAvailableCompletesPendingOperationAndAppendsArtifactAvailableEvent() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ClarificationLifecycleOrchestrator orchestrator =
        mock(ClarificationLifecycleOrchestrator.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            orchestrator);
    ArtifactRecordSnapshot availableArtifact =
        new ArtifactRecordSnapshot(
            "art_ready1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "artifacts/run_ready1234/art_ready1234/v1/spec.md",
            "SHA-256",
            PAYLOAD_DIGEST_HEX,
            ArtifactStatus.AVAILABLE,
            null);
    ArtifactOperationSnapshot pendingOperation =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    ArtifactOperationSnapshot completedOperation =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.COMPLETE,
            "idem-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactPayloadStore.readBytes("artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(Optional.of(PAYLOAD_BYTES));
    when(artifactOperationPort.findPendingByArtifactId("art_ready1234"))
        .thenReturn(Optional.of(pendingOperation));
    when(artifactRecordPort.markAvailable(
            "art_ready1234", VALID_CHECKSUM, "artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(availableArtifact);
    when(artifactOperationPort.markComplete("op_ready1234")).thenReturn(completedOperation);
    when(orchestrator.sweepAfterSpecRebuild("run_ready1234", "art_ready1234", 1, OPERATOR_ACTOR))
        .thenReturn(
            new ClarificationLifecycleOrchestrator.LifecycleSweepResult(0, 0, 0, java.util.List.of()));

    ArtifactAvailabilityResult result =
        service.markAvailable(
            "art_ready1234",
            VALID_CHECKSUM,
            "artifacts/run_ready1234/art_ready1234/v1/spec.md",
            OPERATOR_ACTOR);

    assertEquals(availableArtifact, result.artifact());
    assertEquals(completedOperation, result.operation());
    verify(artifactEventPort)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    "run_ready1234".equals(event.workflowRunId())
                        && event.eventType() == WorkflowEventType.ARTIFACT_AVAILABLE
                        && "alex".equals(event.actorIdentity())
                        && event.actorType() == ActorType.HUMAN
                        && event.createdAt() != null
                        && "art_ready1234".equals(event.details().get("artifactId"))
                        && "op_ready1234".equals(event.details().get("operationId"))
                        && "SHA-256".equals(event.details().get("checksumAlgorithm"))
                        && "artifacts/run_ready1234/art_ready1234/v1/spec.md"
                            .equals(event.details().get("storageRef"))));
    verify(artifactPayloadStore).readBytes("artifacts/run_ready1234/art_ready1234/v1/spec.md");
  }

  @Test
  void markAvailableUsesTheAvailabilityMomentInsteadOfThePendingOperationCreationTime() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ClarificationLifecycleOrchestrator orchestrator =
        mock(ClarificationLifecycleOrchestrator.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            orchestrator);
    ArtifactRecordSnapshot availableArtifact =
        new ArtifactRecordSnapshot(
            "art_ready1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "artifacts/run_ready1234/art_ready1234/v1/spec.md",
            "SHA-256",
            PAYLOAD_DIGEST_HEX,
            ArtifactStatus.AVAILABLE,
            null);
    OffsetDateTime pendingCreatedAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    ArtifactOperationSnapshot pendingOperation =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-1234567890",
            null,
            null,
            pendingCreatedAt);
    ArtifactOperationSnapshot completedOperation =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.COMPLETE,
            "idem-1234567890",
            null,
            null,
            pendingCreatedAt);

    when(artifactPayloadStore.readBytes("artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(Optional.of(PAYLOAD_BYTES));
    when(artifactOperationPort.findPendingByArtifactId("art_ready1234"))
        .thenReturn(Optional.of(pendingOperation));
    when(artifactRecordPort.markAvailable(
            "art_ready1234", VALID_CHECKSUM, "artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(availableArtifact);
    when(artifactOperationPort.markComplete("op_ready1234")).thenReturn(completedOperation);
    when(orchestrator.sweepAfterSpecRebuild("run_ready1234", "art_ready1234", 1, OPERATOR_ACTOR))
        .thenReturn(
            new ClarificationLifecycleOrchestrator.LifecycleSweepResult(0, 0, 0, java.util.List.of()));

    service.markAvailable(
        "art_ready1234",
        VALID_CHECKSUM,
        "artifacts/run_ready1234/art_ready1234/v1/spec.md",
        OPERATOR_ACTOR);

    verify(artifactEventPort)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    event.createdAt() != null
                        && event.createdAt().isAfter(pendingCreatedAt)
                        && event.eventType() == WorkflowEventType.ARTIFACT_AVAILABLE));
  }

  @Test
  void createDraftCanonicalizesSpecPayloadRefToSpecDotMd() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_draft1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);

    when(artifactRecordPort.createDraft(any())).thenReturn(draft);

    assertEquals(
        draft,
        service.createDraft("run_ready1234", ArtifactType.SPEC, "spec-v1.md", OPERATOR_ACTOR));
    verify(artifactRecordPort)
        .createDraft(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    request.artifactType() == ArtifactType.SPEC
                        && "spec.md".equals(request.payloadRef())
                        && request.classification() == DataClassification.SHAREABLE_REDACTED
                        && request.actor().actorType() == ActorType.HUMAN
                        && "alex".equals(request.actor().actorIdentity())));
  }

  @Test
  void newVersionCanonicalizesSpecPayloadRefToSpecDotMd() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ClarificationLifecycleOrchestrator orchestrator =
        mock(ClarificationLifecycleOrchestrator.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            orchestrator);
    ArtifactRecordSnapshot nextVersion =
        new ArtifactRecordSnapshot(
            "art_version1234",
            "run_ready1234",
            ArtifactType.SPEC,
            2,
            "art_parent1234",
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);

    when(artifactRecordPort.createNextVersion(any())).thenReturn(nextVersion);

    assertEquals(nextVersion, service.newVersion("art_parent1234", "spec-v2.md", OPERATOR_ACTOR));
    verifyNoInteractions(orchestrator);
    verify(artifactRecordPort)
        .createNextVersion(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    "art_parent1234".equals(request.lineageMemberArtifactId())
                        && "spec.md".equals(request.payloadRef())
                        && request.actor().actorType() == ActorType.HUMAN
                        && "alex".equals(request.actor().actorIdentity())));
  }

  @Test
  void markAvailableOnSpecArtifactTriggersClarificationSweepAfterAvailability() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ClarificationLifecycleOrchestrator orchestrator =
        mock(ClarificationLifecycleOrchestrator.class);
    when(orchestrator.sweepAfterSpecRebuild(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(
            new ClarificationLifecycleOrchestrator.LifecycleSweepResult(
                2, 1, 1, java.util.List.of()));
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            orchestrator);
    ArtifactRecordSnapshot availableArtifact =
        new ArtifactRecordSnapshot(
            "art_spec_v2_123",
            "run_ready1234",
            ArtifactType.SPEC,
            2,
            "art_spec_v1_123",
            DataClassification.SHAREABLE_REDACTED,
            "artifacts/run_ready1234/art_spec_v2_123/v2/spec.md",
            "SHA-256",
            PAYLOAD_DIGEST_HEX,
            ArtifactStatus.AVAILABLE,
            null);
    ArtifactOperationSnapshot pendingOperation =
        new ArtifactOperationSnapshot(
            "op_spec_v2_123",
            "run_ready1234",
            "art_spec_v2_123",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-spec-v2-123",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    ArtifactOperationSnapshot completedOperation =
        new ArtifactOperationSnapshot(
            "op_spec_v2_123",
            "run_ready1234",
            "art_spec_v2_123",
            "create",
            ArtifactOperationStatus.COMPLETE,
            "idem-spec-v2-123",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactPayloadStore.readBytes("artifacts/run_ready1234/art_spec_v2_123/v2/spec.md"))
        .thenReturn(Optional.of(PAYLOAD_BYTES));
    when(artifactOperationPort.findPendingByArtifactId("art_spec_v2_123"))
        .thenReturn(Optional.of(pendingOperation));
    when(artifactRecordPort.markAvailable(
            "art_spec_v2_123",
            VALID_CHECKSUM,
            "artifacts/run_ready1234/art_spec_v2_123/v2/spec.md"))
        .thenReturn(availableArtifact);
    when(artifactOperationPort.markComplete("op_spec_v2_123")).thenReturn(completedOperation);

    service.markAvailable(
        "art_spec_v2_123",
        VALID_CHECKSUM,
        "artifacts/run_ready1234/art_spec_v2_123/v2/spec.md",
        OPERATOR_ACTOR);

    verify(orchestrator)
        .sweepAfterSpecRebuild("run_ready1234", "art_spec_v2_123", 2, OPERATOR_ACTOR);
  }

  @Test
  void markFailedKeepsArtifactAndOperationFailureStatesAligned() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot failedArtifact =
        new ArtifactRecordSnapshot(
            "art_failed1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.FAILED,
            null);
    ArtifactOperationSnapshot pendingOperation =
        new ArtifactOperationSnapshot(
            "op_failed1234",
            "run_ready1234",
            "art_failed1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-failed-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    ArtifactOperationSnapshot failedOperation =
        new ArtifactOperationSnapshot(
            "op_failed1234",
            "run_ready1234",
            "art_failed1234",
            "create",
            ArtifactOperationStatus.FAILED,
            "idem-failed-1234567890",
            org.dradgo.domain.registry.FailureCategory.RUNNER_CRASH,
            "runner exited before payload write",
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactOperationPort.findPendingByArtifactId("art_failed1234"))
        .thenReturn(java.util.Optional.of(pendingOperation));
    when(artifactRecordPort.markFailed(
            "art_failed1234", FailureCategory.RUNNER_CRASH, "runner exited before payload write"))
        .thenReturn(failedArtifact);
    when(artifactOperationPort.markFailed(
            "op_failed1234", FailureCategory.RUNNER_CRASH, "runner exited before payload write"))
        .thenReturn(failedOperation);

    ArtifactFailureResult result =
        service.markFailed(
            "art_failed1234",
            FailureCategory.RUNNER_CRASH,
            "runner exited before payload write",
            ActorContext.SYSTEM);

    assertEquals(failedArtifact, result.artifact());
    assertEquals(failedOperation, result.operation());
  }

  @Test
  void recordOperationReplaysTheExistingPendingOperationForTheSameArtifactAndIdempotencyKey() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot latestArtifact =
        new ArtifactRecordSnapshot(
            "art_replay1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot replay =
        new ArtifactOperationSnapshot(
            "op_replay1234",
            "run_ready1234",
            "art_replay1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-replay-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_ready1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.of(latestArtifact));
    when(artifactOperationPort.findReplay(
            "run_ready1234", ArtifactType.SPEC, "idem-replay-1234567890", "create"))
        .thenReturn(java.util.Optional.of(replay));
    when(artifactRecordPort.findByPublicId("art_replay1234"))
        .thenReturn(java.util.Optional.of(latestArtifact));

    RecordArtifactOperationResult result =
        service.recordOperation(
            new RecordArtifactOperationCommand(
                "run_ready1234",
                ArtifactType.SPEC,
                ArtifactOperationType.CREATE,
                "idem-replay-1234567890",
                "spec.md",
                "spec body".getBytes(),
                "alex",
                ActorType.HUMAN,
                "corr-1",
                null));

    assertEquals(latestArtifact, result.artifact());
    assertEquals(replay, result.operation());
    verify(artifactOperationPort)
        .findReplay("run_ready1234", ArtifactType.SPEC, "idem-replay-1234567890", "create");
    verify(artifactOperationPort, org.mockito.Mockito.never())
        .createPending(any(), any(), any(), any(), any(), any());
  }

  @Test
  void recordOperationCreatesDraftAndPendingOperationWhenNoArtifactLineageExists() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_draft1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_draft1234",
            "run_ready1234",
            "art_draft1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-create-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_ready1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.empty());
    when(artifactRecordPort.createDraft(any())).thenReturn(draft);
    when(artifactOperationPort.createPending(
            any(),
            eq("run_ready1234"),
            eq(ArtifactType.SPEC),
            eq("art_draft1234"),
            eq("create"),
            eq("idem-create-1234567890")))
        .thenReturn(pending);

    RecordArtifactOperationResult result =
        service.recordOperation(
            new RecordArtifactOperationCommand(
                "run_ready1234",
                ArtifactType.SPEC,
                ArtifactOperationType.CREATE,
                "idem-create-1234567890",
                "spec.md",
                "spec body".getBytes(),
                "alex",
                ActorType.HUMAN,
                "corr-2",
                null));

    assertEquals(draft, result.artifact());
    assertEquals(pending, result.operation());
  }

  @Test
  void recordOperationRejectsUnknownOperationTypesAtRegistryParseTime() {
    // With the operation type carried as a typed enum (R11/M1), unknown values are rejected
    // at command-construction time by the registry parser rather than by the service. This
    // test pins that contract and prevents accidental regressions to a string-typed field.
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> ArtifactOperationType.fromValue("destroy", "artifact_operations.operation_type"));

    assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, error.errorCode());
    assertEquals("destroy", error.details().get("value"));
    assertEquals("artifact_operations.operation_type", error.details().get("field"));
  }

  @Test
  void recordOperationCreatesNewVersionForReplaceOperations() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot latest =
        new ArtifactRecordSnapshot(
            "art_parent1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactRecordSnapshot next =
        new ArtifactRecordSnapshot(
            "art_child1234",
            "run_ready1234",
            ArtifactType.SPEC,
            2,
            "art_parent1234",
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_replace1234",
            "run_ready1234",
            "art_child1234",
            "replace",
            ArtifactOperationStatus.PENDING,
            "idem-replace-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_ready1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.of(latest));
    when(artifactOperationPort.findReplay(
            "run_ready1234", ArtifactType.SPEC, "idem-replace-1234567890", "replace"))
        .thenReturn(java.util.Optional.empty());
    when(artifactRecordPort.createNextVersion(any())).thenReturn(next);
    when(artifactOperationPort.createPending(
            any(),
            eq("run_ready1234"),
            eq(ArtifactType.SPEC),
            eq("art_child1234"),
            eq("replace"),
            eq("idem-replace-1234567890")))
        .thenReturn(pending);

    RecordArtifactOperationResult result =
        service.recordOperation(
            new RecordArtifactOperationCommand(
                "run_ready1234",
                ArtifactType.SPEC,
                ArtifactOperationType.REPLACE,
                "idem-replace-1234567890",
                "spec-v2.md",
                "spec body v2".getBytes(),
                "alex",
                ActorType.HUMAN,
                "corr-3",
                null));

    assertEquals(next, result.artifact());
    assertEquals(pending, result.operation());
    verify(artifactRecordPort)
        .createNextVersion(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    "art_parent1234".equals(request.lineageMemberArtifactId())
                        && "spec.md".equals(request.payloadRef())));
    verify(artifactPayloadStore)
        .write(
            eq("run_ready1234"),
            eq("art_child1234"),
            eq(2),
            eq("spec.md"),
            argThat(bytes -> Arrays.equals(bytes, "spec body v2".getBytes())));
  }

  @Test
  void lateRunnerCallbacksMarkTheArtifactAsLateOrStaleBeforeReturning() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_late1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactRecordSnapshot late =
        new ArtifactRecordSnapshot(
            "art_late1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.LATE_OR_STALE,
            null);
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_late1234",
            "run_ready1234",
            "art_late1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-late-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_ready1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.empty());
    when(artifactRecordPort.createDraft(any())).thenReturn(draft);
    when(artifactRunnerExecutionPort.isTimedOut("rex_late1234")).thenReturn(true);
    when(artifactRecordPort.markLateOrStale("art_late1234")).thenReturn(late);
    when(artifactOperationPort.createPending(
            any(),
            eq("run_ready1234"),
            eq(ArtifactType.SPEC),
            eq("art_late1234"),
            eq("create"),
            eq("idem-late-1234567890")))
        .thenReturn(pending);

    RecordArtifactOperationResult result =
        service.recordOperation(
            new RecordArtifactOperationCommand(
                "run_ready1234",
                ArtifactType.SPEC,
                ArtifactOperationType.CREATE,
                "idem-late-1234567890",
                "spec.md",
                "spec body".getBytes(),
                "alex",
                ActorType.HUMAN,
                "corr-4",
                "rex_late1234"));

    assertEquals(ArtifactStatus.LATE_OR_STALE, result.artifact().status());
    assertEquals(pending, result.operation());
  }

  @Test
  void markAvailableRejectsBlankStorageRefsBeforePersistingAvailability() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    new ArtifactChecksum("sha256", "abc123"),
                    "   ",
                    OPERATOR_ACTOR));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    assertEquals("storageRef", error.details().get("field"));
    verifyNoInteractions(artifactRecordPort, artifactOperationPort, artifactEventPort);
  }

  @Test
  void markAvailableRequiresAnExplicitActorContextInsteadOfFallingBackToSystem() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));

    Map<String, Object> ignored = Map.of();
    assertEquals(0, ignored.size());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    new ArtifactChecksum("sha256", "abc123"),
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    null));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    assertEquals("actor", error.details().get("field"));
    verifyNoInteractions(artifactRecordPort, artifactOperationPort, artifactEventPort);
  }

  @Test
  void recordOperationRejectsCreateAgainstExistingNonFailedLineage() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    ArtifactRecordSnapshot existingLeaf =
        new ArtifactRecordSnapshot(
            "art_existing1234",
            "run_dup1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.AVAILABLE,
            null);

    when(artifactOperationPort.findReplay(
            "run_dup1234", ArtifactType.SPEC, "idem-dup-1234567890", "create"))
        .thenReturn(java.util.Optional.empty());
    when(runStatePort.currentState("run_dup1234"))
        .thenReturn(java.util.Optional.of(WorkflowState.EXECUTING));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_dup1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.of(existingLeaf));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.recordOperation(
                    new RecordArtifactOperationCommand(
                        "run_dup1234",
                        ArtifactType.SPEC,
                        ArtifactOperationType.CREATE,
                        "idem-dup-1234567890",
                        "spec.md",
                        "spec body".getBytes(),
                        "alex",
                        ActorType.HUMAN,
                        "corr-dup",
                        null)));

    assertEquals(DomainErrorCode.ARTIFACT_LINEAGE_ALREADY_EXISTS, error.errorCode());
    assertEquals("art_existing1234", error.details().get("existingArtifactId"));
    assertEquals("available", error.details().get("existingArtifactStatus"));
    verify(artifactRecordPort, org.mockito.Mockito.never()).createDraft(any());
    verify(artifactOperationPort, org.mockito.Mockito.never())
        .createPending(any(), any(), any(), any(), any(), any());
  }

  @Test
  void recordOperationAllowsCreateAgainstFailedLineageLeafByStartingFreshDraft() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    ArtifactRecordSnapshot failedLeaf =
        new ArtifactRecordSnapshot(
            "art_failed1234",
            "run_recover1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            FailureCategory.ORPHAN,
            "stale_pending",
            ArtifactStatus.FAILED,
            null,
            false);
    ArtifactRecordSnapshot freshDraft =
        new ArtifactRecordSnapshot(
            "art_fresh1234",
            "run_recover1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_fresh1234",
            "run_recover1234",
            "art_fresh1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-recover-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactOperationPort.findReplay(
            "run_recover1234", ArtifactType.SPEC, "idem-recover-1234567890", "create"))
        .thenReturn(java.util.Optional.empty());
    when(runStatePort.currentState("run_recover1234"))
        .thenReturn(java.util.Optional.of(WorkflowState.EXECUTING));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_recover1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.of(failedLeaf));
    when(artifactRecordPort.createDraft(any())).thenReturn(freshDraft);
    when(artifactOperationPort.createPending(
            any(),
            eq("run_recover1234"),
            eq(ArtifactType.SPEC),
            eq("art_fresh1234"),
            eq("create"),
            eq("idem-recover-1234567890")))
        .thenReturn(pending);

    RecordArtifactOperationResult result =
        service.recordOperation(
            new RecordArtifactOperationCommand(
                "run_recover1234",
                ArtifactType.SPEC,
                ArtifactOperationType.CREATE,
                "idem-recover-1234567890",
                "spec.md",
                "spec body".getBytes(),
                "alex",
                ActorType.HUMAN,
                "corr-recover",
                null));

    assertEquals(freshDraft, result.artifact());
    assertEquals(pending, result.operation());
  }

  @Test
  void recordOperationRejectsOperationsAgainstTerminalWorkflowRuns() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    when(artifactOperationPort.findReplay(
            "run_done1234", ArtifactType.SPEC, "idem-terminal-1234567890", "create"))
        .thenReturn(java.util.Optional.empty());
    when(runStatePort.currentState("run_done1234"))
        .thenReturn(java.util.Optional.of(WorkflowState.COMPLETED));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.recordOperation(
                    new RecordArtifactOperationCommand(
                        "run_done1234",
                        ArtifactType.SPEC,
                        ArtifactOperationType.CREATE,
                        "idem-terminal-1234567890",
                        "spec.md",
                        "spec body".getBytes(),
                        "alex",
                        ActorType.HUMAN,
                        "corr-terminal",
                        null)));

    assertEquals(DomainErrorCode.WORKFLOW_RUN_TERMINAL, error.errorCode());
    assertEquals("Completed", error.details().get("workflowRunState"));
    verifyNoInteractions(artifactRecordPort);
  }

  @Test
  void recordOperationReplaysWinnerAfterIdempotencyConstraintCollisionUsingRequiresNewTemplate() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    TransactionTemplate raceReplayTemplate = callthroughTemplate();
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));
    service.setRaceReplayTemplate(raceReplayTemplate);

    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_race1234",
            "run_race1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactRecordSnapshot winningArtifact =
        new ArtifactRecordSnapshot(
            "art_winner1234",
            "run_race1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot winningOperation =
        new ArtifactOperationSnapshot(
            "op_winner1234",
            "run_race1234",
            "art_winner1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-race-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactOperationPort.findReplay(
            "run_race1234", ArtifactType.SPEC, "idem-race-1234567890", "create"))
        .thenReturn(java.util.Optional.empty(), java.util.Optional.of(winningOperation));
    when(runStatePort.currentState("run_race1234"))
        .thenReturn(java.util.Optional.of(WorkflowState.EXECUTING));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_race1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.empty());
    when(artifactRecordPort.createDraft(any())).thenReturn(draft);
    when(artifactRecordPort.findByPublicId("art_winner1234"))
        .thenReturn(java.util.Optional.of(winningArtifact));
    when(artifactOperationPort.createPending(
            any(),
            eq("run_race1234"),
            eq(ArtifactType.SPEC),
            eq("art_race1234"),
            eq("create"),
            eq("idem-race-1234567890")))
        .thenThrow(
            new DataIntegrityViolationException(
                "uq_artifact_operations_idem_key_op_type_workflow_run"));

    RecordArtifactOperationResult result =
        service.recordOperation(
            new RecordArtifactOperationCommand(
                "run_race1234",
                ArtifactType.SPEC,
                ArtifactOperationType.CREATE,
                "idem-race-1234567890",
                "spec.md",
                "spec body".getBytes(),
                "alex",
                ActorType.HUMAN,
                "corr-race",
                null));

    assertEquals(winningArtifact, result.artifact());
    assertEquals(winningOperation, result.operation());
    verify(raceReplayTemplate).execute(any(TransactionCallback.class));
  }

  @Test
  void recordOperationMapsIdempotencyConstraintCollisionWithoutReplayToTypedConflict() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_collide1234",
            "run_collide1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);

    when(artifactOperationPort.findReplay(
            "run_collide1234", ArtifactType.SPEC, "idem-collide-1234567890", "create"))
        .thenReturn(java.util.Optional.empty());
    when(runStatePort.currentState("run_collide1234"))
        .thenReturn(java.util.Optional.of(WorkflowState.EXECUTING));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_collide1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.empty());
    when(artifactRecordPort.createDraft(any())).thenReturn(draft);
    when(artifactOperationPort.createPending(
            any(),
            eq("run_collide1234"),
            eq(ArtifactType.SPEC),
            eq("art_collide1234"),
            eq("create"),
            eq("idem-collide-1234567890")))
        .thenThrow(
            new DataIntegrityViolationException(
                "uq_artifact_operations_idem_key_op_type_workflow_run"));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.recordOperation(
                    new RecordArtifactOperationCommand(
                        "run_collide1234",
                        ArtifactType.SPEC,
                        ArtifactOperationType.CREATE,
                        "idem-collide-1234567890",
                        "spec.md",
                        "spec body".getBytes(),
                        "alex",
                        ActorType.HUMAN,
                        "corr-collide",
                        null)));

    assertEquals(DomainErrorCode.ARTIFACT_OPERATION_CONFLICT, error.errorCode());
    assertEquals("idem-collide-1234567890", error.details().get("idempotencyKey"));
  }

  @Test
  void recordOperationFailedReplaySurfacesAsOperationConflict() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    ArtifactRecordSnapshot priorArtifact =
        new ArtifactRecordSnapshot(
            "art_failed-replay1234",
            "run_replay-fail1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            FailureCategory.RUNNER_CRASH,
            "runner crashed",
            ArtifactStatus.FAILED,
            null,
            false);
    ArtifactOperationSnapshot priorOperation =
        new ArtifactOperationSnapshot(
            "op_failed-replay1234",
            "run_replay-fail1234",
            "art_failed-replay1234",
            "create",
            ArtifactOperationStatus.FAILED,
            "idem-replayfail-1234567890",
            FailureCategory.RUNNER_CRASH,
            "runner crashed",
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactOperationPort.findReplay(
            "run_replay-fail1234", ArtifactType.SPEC, "idem-replayfail-1234567890", "create"))
        .thenReturn(java.util.Optional.of(priorOperation));
    when(artifactRecordPort.findByPublicId("art_failed-replay1234"))
        .thenReturn(java.util.Optional.of(priorArtifact));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.recordOperation(
                    new RecordArtifactOperationCommand(
                        "run_replay-fail1234",
                        ArtifactType.SPEC,
                        ArtifactOperationType.CREATE,
                        "idem-replayfail-1234567890",
                        "spec.md",
                        "spec body".getBytes(),
                        "alex",
                        ActorType.HUMAN,
                        "corr-replayfail",
                        null)));

    // The service surfaces a failed-prior-operation replay as ARTIFACT_OPERATION_INTENT_CONFLICT
    // (not the broader ARTIFACT_OPERATION_CONFLICT) so callers can distinguish "key was used
    // for a prior FAILED op" from generic operation conflicts. ArtifactOperationService line 457.
    assertEquals(DomainErrorCode.ARTIFACT_OPERATION_INTENT_CONFLICT, error.errorCode());
    assertEquals("idem-replayfail-1234567890", error.details().get("idempotencyKey"));
    assertEquals("op_failed-replay1234", error.details().get("priorOperationId"));
    verify(artifactOperationPort, org.mockito.Mockito.never())
        .createPending(any(), any(), any(), any(), any(), any());
  }

  @Test
  void recordOperationReplayMissingArtifactRecordRaisesArtifactRecordNotFound() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    ArtifactOperationSnapshot priorOperation =
        new ArtifactOperationSnapshot(
            "op_orphan1234",
            "run_replay-missing1234",
            "art_missing1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-orphan-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactOperationPort.findReplay(
            "run_replay-missing1234", ArtifactType.SPEC, "idem-orphan-1234567890", "create"))
        .thenReturn(java.util.Optional.of(priorOperation));
    when(artifactRecordPort.findByPublicId("art_missing1234"))
        .thenReturn(java.util.Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.recordOperation(
                    new RecordArtifactOperationCommand(
                        "run_replay-missing1234",
                        ArtifactType.SPEC,
                        ArtifactOperationType.CREATE,
                        "idem-orphan-1234567890",
                        "spec.md",
                        "spec body".getBytes(),
                        "alex",
                        ActorType.HUMAN,
                        "corr-orphan",
                        null)));

    assertEquals(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND, error.errorCode());
    assertEquals("art_missing1234", error.details().get("artifactId"));
    assertEquals("op_orphan1234", error.details().get("operationId"));
  }

  @Test
  void markAvailableRejectsBlankChecksumAlgorithmBeforePersistingAvailability() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));

    DomainException blankAlgorithm =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    new ArtifactChecksum("   ", "abc123"),
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    OPERATOR_ACTOR));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, blankAlgorithm.errorCode());
    assertEquals("checksum.algorithm", blankAlgorithm.details().get("field"));

    DomainException blankValue =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    new ArtifactChecksum("sha256", null),
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    OPERATOR_ACTOR));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, blankValue.errorCode());
    assertEquals("checksum.value", blankValue.details().get("field"));

    DomainException nullChecksum =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    null,
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    OPERATOR_ACTOR));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, nullChecksum.errorCode());
    assertEquals("checksum", nullChecksum.details().get("field"));

    verifyNoInteractions(artifactRecordPort, artifactOperationPort, artifactEventPort);
  }

  @Test
  void markFailedAppendsArtifactFailedEventCarryingFailureCategoryAndReason() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot failedArtifact =
        new ArtifactRecordSnapshot(
            "art_failed1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            null,
            null,
            null,
            FailureCategory.RUNNER_CRASH,
            "runner exited",
            ArtifactStatus.FAILED,
            null,
            false);
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_failed1234",
            "run_ready1234",
            "art_failed1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-failed-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    ArtifactOperationSnapshot failedOperation =
        new ArtifactOperationSnapshot(
            "op_failed1234",
            "run_ready1234",
            "art_failed1234",
            "create",
            ArtifactOperationStatus.FAILED,
            "idem-failed-1234567890",
            FailureCategory.RUNNER_CRASH,
            "runner exited",
            OffsetDateTime.now(ZoneOffset.UTC));

    when(artifactOperationPort.findPendingByArtifactId("art_failed1234"))
        .thenReturn(java.util.Optional.of(pending));
    when(artifactRecordPort.markFailed(
            "art_failed1234", FailureCategory.RUNNER_CRASH, "runner exited"))
        .thenReturn(failedArtifact);
    when(artifactOperationPort.markFailed(
            "op_failed1234", FailureCategory.RUNNER_CRASH, "runner exited"))
        .thenReturn(failedOperation);

    service.markFailed(
        "art_failed1234", FailureCategory.RUNNER_CRASH, "runner exited", OPERATOR_ACTOR);

    verify(artifactEventPort)
        .append(
            org.mockito.ArgumentMatchers.argThat(
                event ->
                    event.eventType() == WorkflowEventType.ARTIFACT_FAILED
                        && event.actorType() == ActorType.HUMAN
                        && "alex".equals(event.actorIdentity())
                        && FailureCategory.RUNNER_CRASH == event.failureCategory()
                        && "runner exited".equals(event.reason())
                        && "art_failed1234".equals(event.details().get("artifactId"))
                        && "op_failed1234".equals(event.details().get("operationId"))
                        && "runner_crash".equals(event.details().get("failureCategory"))
                        && "runner exited".equals(event.details().get("failureReason"))));
  }

  @Test
  void markFailedRejectsNullActorContext() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.markFailed(
                    "art_failed1234", FailureCategory.RUNNER_CRASH, "runner exited", null));

    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    assertEquals("actor", error.details().get("field"));
    verifyNoInteractions(artifactRecordPort, artifactOperationPort, artifactEventPort);
  }

  @Test
  void markAvailableRejectsUnreadablePayloadBeforeFlippingArtifactStatus() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    when(artifactOperationPort.findPendingByArtifactId("art_ready1234"))
        .thenReturn(Optional.of(pending));
    when(artifactPayloadStore.readBytes("artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(Optional.empty());

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    VALID_CHECKSUM,
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    OPERATOR_ACTOR));

    assertEquals(DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE, error.errorCode());
    assertEquals("art_ready1234", error.details().get("artifactId"));
    verify(artifactRecordPort, org.mockito.Mockito.never()).markAvailable(any(), any(), any());
    verify(artifactOperationPort, org.mockito.Mockito.never()).markComplete(any());
    verifyNoInteractions(artifactEventPort);
  }

  @Test
  void markAvailableRejectsChecksumMismatchBeforeFlippingArtifactStatus() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    when(artifactOperationPort.findPendingByArtifactId("art_ready1234"))
        .thenReturn(Optional.of(pending));
    // Caller-supplied checksum claims SHA-256 of "spec content" (PAYLOAD_DIGEST_HEX), but the
    // real bytes on disk are different — the recompute MUST detect the mismatch.
    when(artifactPayloadStore.readBytes("artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(Optional.of("different bytes".getBytes(StandardCharsets.UTF_8)));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    VALID_CHECKSUM,
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    OPERATOR_ACTOR));

    assertEquals(DomainErrorCode.ARTIFACT_CHECKSUM_MISMATCH, error.errorCode());
    verify(artifactRecordPort, org.mockito.Mockito.never()).markAvailable(any(), any(), any());
    verify(artifactOperationPort, org.mockito.Mockito.never()).markComplete(any());
    verifyNoInteractions(artifactEventPort);
  }

  @Test
  void markAvailableRejectsUnknownChecksumAlgorithm() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_ready1234",
            "run_ready1234",
            "art_ready1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    when(artifactOperationPort.findPendingByArtifactId("art_ready1234"))
        .thenReturn(Optional.of(pending));
    when(artifactPayloadStore.readBytes("artifacts/run_ready1234/art_ready1234/v1/spec.md"))
        .thenReturn(Optional.of(PAYLOAD_BYTES));

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.markAvailable(
                    "art_ready1234",
                    new ArtifactChecksum("MD5", PAYLOAD_DIGEST_HEX),
                    "artifacts/run_ready1234/art_ready1234/v1/spec.md",
                    OPERATOR_ACTOR));

    // P15: unsupported checksum algorithms → INVALID_COMMAND_PAYLOAD (not a checksum mismatch)
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, error.errorCode());
    assertEquals("MD5", error.details().get("checksumAlgorithm"));
    verifyNoInteractions(artifactEventPort);
  }

  @Test
  void markFailedRejectsBlankReasonBeforeWritingFailureColumns() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));

    DomainException nullReason =
        assertThrows(
            DomainException.class,
            () ->
                service.markFailed(
                    "art_failed1234", FailureCategory.RUNNER_CRASH, null, OPERATOR_ACTOR));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, nullReason.errorCode());
    assertEquals("reason", nullReason.details().get("field"));

    DomainException blankReason =
        assertThrows(
            DomainException.class,
            () ->
                service.markFailed(
                    "art_failed1234", FailureCategory.RUNNER_CRASH, "   ", OPERATOR_ACTOR));
    assertEquals(DomainErrorCode.INVALID_COMMAND_PAYLOAD, blankReason.errorCode());
    assertEquals("reason", blankReason.details().get("field"));

    verifyNoInteractions(artifactRecordPort, artifactOperationPort, artifactEventPort);
  }

  @Test
  void recordOperationWritesPayloadBytesViaTheArtifactPayloadStore() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactOperationService service =
        ArtifactOperationService.withoutWorkflowRunStateGuard(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            mock(ClarificationLifecycleOrchestrator.class));
    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_draft1234",
            "run_ready1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.SHAREABLE_REDACTED,
            "spec.md",
            null,
            null,
            ArtifactStatus.PENDING,
            null);
    ArtifactOperationSnapshot pending =
        new ArtifactOperationSnapshot(
            "op_draft1234",
            "run_ready1234",
            "art_draft1234",
            "create",
            ArtifactOperationStatus.PENDING,
            "idem-create-1234567890",
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            eq("run_ready1234"), eq(ArtifactType.SPEC.value())))
        .thenReturn(Optional.empty());
    when(artifactRecordPort.createDraft(any())).thenReturn(draft);
    when(artifactOperationPort.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(artifactOperationPort.createPending(any(), any(), any(), any(), any(), any()))
        .thenReturn(pending);

    byte[] payload = "spec body".getBytes(StandardCharsets.UTF_8);
    service.recordOperation(
        new RecordArtifactOperationCommand(
            "run_ready1234",
            ArtifactType.SPEC,
            ArtifactOperationType.CREATE,
            "idem-create-1234567890",
            "spec.md",
            payload,
            "alex",
            ActorType.HUMAN,
            "corr-write",
            null));

    // D1: service-driven outbox file write — store.write is called with the payload bytes.
    verify(artifactPayloadStore)
        .write(eq("run_ready1234"), eq("art_draft1234"), eq(1), eq("spec.md"), eq(payload));
  }

  @Test
  void recordOperationIdempotencyConstraintCollisionWithMatchingConstraintNameSurfacesAsConflict() {
    ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
    ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
    ArtifactPayloadStore artifactPayloadStore = mock(ArtifactPayloadStore.class);
    ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
    ArtifactRunnerExecutionPort artifactRunnerExecutionPort =
        mock(ArtifactRunnerExecutionPort.class);
    ArtifactWorkflowRunStatePort runStatePort = mock(ArtifactWorkflowRunStatePort.class);
    ArtifactOperationService service =
        new ArtifactOperationService(
            artifactRecordPort,
            artifactOperationPort,
            artifactPayloadStore,
            artifactEventPort,
            artifactRunnerExecutionPort,
            runStatePort,
            mock(ClarificationLifecycleOrchestrator.class));

    ArtifactRecordSnapshot draft =
        new ArtifactRecordSnapshot(
            "art_race2_1234",
            "run_race2_1234",
            ArtifactType.SPEC,
            1,
            null,
            DataClassification.LOCAL_ONLY,
            null,
            null,
            null,
            ArtifactStatus.PENDING,
            null);

    when(artifactOperationPort.findReplay(
            "run_race2_1234", ArtifactType.SPEC, "idem-race2-1234567890", "create"))
        .thenReturn(java.util.Optional.empty());
    when(runStatePort.currentState("run_race2_1234"))
        .thenReturn(java.util.Optional.of(WorkflowState.EXECUTING));
    when(artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
            "run_race2_1234", ArtifactType.SPEC.value()))
        .thenReturn(java.util.Optional.empty());
    when(artifactRecordPort.createDraft(any())).thenReturn(draft);
    when(artifactOperationPort.createPending(
            any(),
            eq("run_race2_1234"),
            eq(ArtifactType.SPEC),
            eq("art_race2_1234"),
            eq("create"),
            eq("idem-race2-1234567890")))
        .thenThrow(
            new DataIntegrityViolationException(
                "uq_artifact_operations_idem_key_op_type_workflow_run"));

    // P1: idempotency constraint collision → ARTIFACT_OPERATION_CONFLICT, no race-replay
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.recordOperation(
                    new RecordArtifactOperationCommand(
                        "run_race2_1234",
                        ArtifactType.SPEC,
                        ArtifactOperationType.CREATE,
                        "idem-race2-1234567890",
                        "spec.md",
                        "spec body".getBytes(),
                        "alex",
                        ActorType.HUMAN,
                        "corr-race2",
                        null)));

    assertEquals(DomainErrorCode.ARTIFACT_OPERATION_CONFLICT, error.errorCode());
    assertEquals("idem-race2-1234567890", error.details().get("idempotencyKey"));
  }

  @Test
  void recordArtifactOperationCommandRejectsBlankIdempotencyKey() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecordArtifactOperationCommand(
                "run_ready1234",
                ArtifactType.SPEC,
                ArtifactOperationType.CREATE,
                "   ",
                "spec.md",
                null,
                "alex",
                ActorType.HUMAN,
                null,
                null));
  }

  @Test
  void recordArtifactOperationCommandRejectsIdempotencyKeyExceedingMaxLength() {
    String oversized = "x".repeat(257);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RecordArtifactOperationCommand(
                    "run_ready1234",
                    ArtifactType.SPEC,
                    ArtifactOperationType.CREATE,
                    oversized,
                    "spec.md",
                    null,
                    "alex",
                    ActorType.HUMAN,
                    null,
                    null));
    assertEquals(true, error.getMessage().contains("idempotencyKey"));
  }

  @Test
  void recordArtifactOperationCommandRejectsCorrelationIdExceedingMaxLength() {
    String oversized = "c".repeat(257);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new RecordArtifactOperationCommand(
                    "run_ready1234",
                    ArtifactType.SPEC,
                    ArtifactOperationType.CREATE,
                    "idem-valid",
                    "spec.md",
                    null,
                    "alex",
                    ActorType.HUMAN,
                    oversized,
                    null));
    assertEquals(true, error.getMessage().contains("correlationId"));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static TransactionTemplate callthroughTemplate() {
    TransactionTemplate template = mock(TransactionTemplate.class);
    when(template.execute(any(TransactionCallback.class)))
        .thenAnswer(
            invocation -> ((TransactionCallback) invocation.getArgument(0)).doInTransaction(null));
    return template;
  }
}
