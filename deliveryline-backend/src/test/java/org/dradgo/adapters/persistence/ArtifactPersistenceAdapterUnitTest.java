package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.ArtifactOperationEntity;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.ArtifactEntityMapper;
import org.dradgo.adapters.persistence.mapper.ArtifactOperationEntityMapper;
import org.dradgo.adapters.persistence.repository.ArtifactOperationRepository;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactDraftRequest;
import org.dradgo.application.artifact.ArtifactEventRecord;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.application.artifact.ArtifactVersionRequest;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Mock-based unit tests for the artifact persistence adapters.
 *
 * <p>Historically named {@code ArtifactPersistenceAdapterContractTest}; renamed because every
 * test here mocks the JPA repositories rather than running against a real database. The schema
 * constraints (V1/V2 paired failure-category check, V3 widened idempotency uniqueness, V4
 * single-pending partial unique index), {@code @PrePersist} clock behavior, and lazy-load
 * hazards across {@code @Transactional} boundaries are all bypassed by the mocks. Real-DB
 * regressions live in {@link org.dradgo.application.artifact.ArtifactOperationServiceContractTest}
 * and {@code FlywaySchemaContractTest}.
 */
class ArtifactPersistenceAdapterUnitTest {

	@Test
	void createDraftRaisesStableRunNotFoundErrorWhenWorkflowRunIsMissing() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());

		when(workflowRunRepository.findByPublicId("run_missing1234")).thenReturn(Optional.empty());
		when(workflowRunRepository.findByPublicIdForUpdate("run_missing1234")).thenReturn(Optional.empty());

		DomainException error = assertThrows(
			DomainException.class,
			() -> adapter.createDraft(new ArtifactDraftRequest(
				"run_missing1234",
				ArtifactType.SPEC,
				"spec.md",
				DataClassification.LOCAL_ONLY,
				new ActorContext("system", ActorType.SYSTEM, null),
				null,
				null,
				null,
				null)));

		assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
		assertEquals("run_missing1234", error.details().get("runId"));
	}

	@Test
	void createDraftAndNextVersionAssignPendingStatusAndMonotonicVersions() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);

		when(workflowRunRepository.findByPublicId("run_ready1234")).thenReturn(Optional.of(run));
		when(workflowRunRepository.findByPublicIdForUpdate("run_ready1234")).thenReturn(Optional.of(run));
		when(workflowEventRepository.saveAndFlush(any())).thenReturn(linkedEvent);
		when(artifactRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(artifactEntity("art_draft1234", run, linkedEvent, null, 1)));
		when(artifactRepository.findByPublicId("art_draft1234"))
			.thenReturn(Optional.of(artifactEntity("art_draft1234", run, linkedEvent, null, 1)));

		var draft = adapter.createDraft(new ArtifactDraftRequest(
			"run_ready1234",
			ArtifactType.SPEC,
			"spec.md",
			DataClassification.LOCAL_ONLY,
			new ActorContext("system", ActorType.SYSTEM, null),
			null,
			null,
			null,
			null));
		var next = adapter.createNextVersion(new ArtifactVersionRequest(
			"art_draft1234",
			"spec-v2.md",
			new ActorContext("system", ActorType.SYSTEM, null),
			null,
			null,
			null,
			null));

		assertEquals(ArtifactStatus.PENDING, draft.status());
		assertEquals(1, draft.version());
		assertEquals(ArtifactStatus.PENDING, next.status());
		assertEquals(2, next.version());
		assertEquals("art_draft1234", next.parentArtifactId());
	}

	@Test
	void findLatestByParentArtifactIdReturnsTheLeafArtifactForTheLineage() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity root = artifactEntity("art_root1234", run, linkedEvent, null, 1);
		ArtifactEntity middle = artifactEntity("art_middle1234", run, linkedEvent, root, 2);
		ArtifactEntity leaf = artifactEntity("art_leaf1234", run, linkedEvent, middle, 3);

		when(artifactRepository.findByPublicId("art_root1234")).thenReturn(Optional.of(root));
		when(artifactRepository.findByWorkflowRunPublicIdAndArtifactTypeOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(List.of(leaf, middle, root));

		var latest = adapter.findLatestByParentArtifactId("art_root1234").orElseThrow();

		assertEquals("art_leaf1234", latest.publicId());
		assertEquals("art_middle1234", latest.parentArtifactId());
	}

	@Test
	void findLatestByParentArtifactIdSkipsLeavesFromADifferentLineageInTheSameWorkflowRunAndType() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		// Original lineage that ended in FAILED at version 2
		ArtifactEntity oldRoot = artifactEntity("art_oldroot1234", run, linkedEvent, null, 1);
		ArtifactEntity oldLeaf = artifactEntity("art_oldleaf1234", run, linkedEvent, oldRoot, 2);
		oldLeaf.setStatus(ArtifactStatus.FAILED);
		// Fresh draft started a NEW lineage at version 3 with parent_artifact_id=NULL
		ArtifactEntity freshRoot = artifactEntity("art_fresh1234", run, linkedEvent, null, 3);

		when(artifactRepository.findByPublicId("art_oldroot1234")).thenReturn(Optional.of(oldRoot));
		when(artifactRepository.findByWorkflowRunPublicIdAndArtifactTypeOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(List.of(freshRoot, oldLeaf, oldRoot));

		// The old lineage's actual leaf (art_oldleaf1234, version 2) must be returned, not
		// the fresh-lineage leaf (art_fresh1234, version 3) which doesn't descend from art_oldroot1234.
		var latest = adapter.findLatestByParentArtifactId("art_oldroot1234").orElseThrow();

		assertEquals("art_oldleaf1234", latest.publicId());
		assertEquals("art_oldroot1234", latest.parentArtifactId());
	}

	@Test
	void createNextVersionTranslatesUniqueConstraintCollisionToTypedArtifactOperationConflict() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity member = artifactEntity("art_member1234", run, linkedEvent, null, 1);

		when(artifactRepository.findByPublicId("art_member1234")).thenReturn(Optional.of(member));
		when(workflowRunRepository.findByPublicIdForUpdate("run_ready1234")).thenReturn(Optional.of(run));
		when(workflowEventRepository.saveAndFlush(any())).thenReturn(linkedEvent);
		when(artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(Optional.of(member));
		when(artifactRepository.saveAndFlush(any(ArtifactEntity.class)))
			.thenThrow(new DataIntegrityViolationException(
				"uq_artifacts_workflow_run_id_artifact_type_version violated"));

		DomainException error = assertThrows(
			DomainException.class,
			() -> adapter.createNextVersion(new ArtifactVersionRequest(
				"art_member1234",
				"spec-v2.md",
				new ActorContext("system", ActorType.SYSTEM, null),
				null,
				null,
				null,
				null)));

		assertEquals(DomainErrorCode.ARTIFACT_OPERATION_CONFLICT, error.errorCode());
		assertEquals("run_ready1234", error.details().get("workflowRunId"));
		assertEquals(ArtifactType.SPEC.value(), error.details().get("artifactType"));
		assertEquals("art_member1234", error.details().get("lineageMemberArtifactId"));
		assertEquals(2, error.details().get("attemptedVersion"));
	}

	@Test
	void createNextVersionSilentlyChainsOffTheLeafEvenWhenCallerSuppliesAStaleNonLeafLineageMember() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity root = artifactEntity("art_root1234", run, linkedEvent, null, 1);
		ArtifactEntity middle = artifactEntity("art_middle1234", run, linkedEvent, root, 2);
		ArtifactEntity leaf = artifactEntity("art_leaf1234", run, linkedEvent, middle, 3);

		// Caller passes the STALE root, not the leaf, to createNextVersion.
		when(artifactRepository.findByPublicId("art_root1234")).thenReturn(Optional.of(root));
		when(workflowRunRepository.findByPublicIdForUpdate("run_ready1234")).thenReturn(Optional.of(run));
		when(workflowEventRepository.saveAndFlush(any())).thenReturn(linkedEvent);
		when(artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(Optional.of(leaf));
		when(artifactRepository.saveAndFlush(any(ArtifactEntity.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		var next = adapter.createNextVersion(new ArtifactVersionRequest(
			"art_root1234",
			"spec-v4.md",
			new ActorContext("system", ActorType.SYSTEM, null),
			null,
			null,
			null,
			null));

		// Adapter must silently chain off the leaf (art_leaf1234, v3), not the supplied member (art_root1234, v1).
		assertEquals(4, next.version());
		assertEquals("art_leaf1234", next.parentArtifactId());
	}

	@Test
	void createNextVersionUsesRequestedLineageLeafAsParentWhenAnotherLineageHasTheGlobalLatestVersion() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity oldRoot = artifactEntity("art_oldroot1234", run, linkedEvent, null, 1);
		ArtifactEntity oldLeaf = artifactEntity("art_oldleaf1234", run, linkedEvent, oldRoot, 2);
		ArtifactEntity freshRoot = artifactEntity("art_fresh1234", run, linkedEvent, null, 3);

		when(artifactRepository.findByPublicId("art_oldroot1234")).thenReturn(Optional.of(oldRoot));
		when(workflowRunRepository.findByPublicIdForUpdate("run_ready1234")).thenReturn(Optional.of(run));
		when(workflowEventRepository.saveAndFlush(any())).thenReturn(linkedEvent);
		when(artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(Optional.of(freshRoot));
		when(artifactRepository.findByWorkflowRunPublicIdAndArtifactTypeOrderByVersionDesc(
			"run_ready1234",
			ArtifactType.SPEC.value()))
			.thenReturn(List.of(freshRoot, oldLeaf, oldRoot));
		when(artifactRepository.saveAndFlush(any(ArtifactEntity.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		var next = adapter.createNextVersion(new ArtifactVersionRequest(
			"art_oldroot1234",
			"spec-v4.md",
			new ActorContext("system", ActorType.SYSTEM, null),
			null,
			null,
			null,
			null));

		assertEquals(4, next.version());
		assertEquals("art_oldleaf1234", next.parentArtifactId());
	}

	@Test
	void createDraftRejectsTerminalWorkflowRunAfterPostLockRecheck() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_done1234", WorkflowState.COMPLETED);

		when(workflowRunRepository.findByPublicIdForUpdate("run_done1234")).thenReturn(Optional.of(run));

		DomainException error = assertThrows(
			DomainException.class,
			() -> adapter.createDraft(new ArtifactDraftRequest(
				"run_done1234",
				ArtifactType.SPEC,
				"spec.md",
				DataClassification.LOCAL_ONLY,
				new ActorContext("alex", ActorType.HUMAN, "corr-terminal"),
				null,
				null,
				null,
				null)));

		assertEquals(DomainErrorCode.WORKFLOW_RUN_TERMINAL, error.errorCode());
		assertEquals("run_done1234", error.details().get("workflowRunId"));
		assertEquals(WorkflowState.COMPLETED.value(), error.details().get("workflowRunState"));
		verifyNoInteractions(workflowEventRepository);
		verify(artifactRepository, never()).saveAndFlush(any(ArtifactEntity.class));
	}

	@Test
	void markAvailableAndMarkFailedUpdateArtifactStatusFields() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity draftForAvailable = artifactEntity("art_ready1234", run, linkedEvent, null, 1);
		ArtifactEntity draftForFailed = artifactEntity("art_failed1234", run, linkedEvent, null, 1);

		when(artifactRepository.findByPublicId("art_ready1234")).thenReturn(Optional.of(draftForAvailable));
		when(artifactRepository.findByPublicId("art_failed1234")).thenReturn(Optional.of(draftForFailed));
		when(artifactRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var available = adapter.markAvailable(
			"art_ready1234",
			new ArtifactChecksum("sha256", "abc123"),
			"artifacts/run_ready1234/art_ready1234/v1/spec.md");
		var failed = adapter.markFailed(
			"art_failed1234",
			FailureCategory.RUNNER_CRASH,
			"runner exited before payload write");

		assertEquals(ArtifactStatus.AVAILABLE, available.status());
		assertEquals("sha256", available.checksumAlgorithm());
		assertEquals(ArtifactStatus.FAILED, failed.status());
		assertEquals(FailureCategory.RUNNER_CRASH, failed.failureCategory());
		assertEquals("runner exited before payload write", failed.failureReason());
	}

	@Test
	void markAvailableRejectsTransitionFromNonPendingStatus() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity alreadyAvailable = artifactEntity("art_ready1234", run, linkedEvent, null, 1);
		alreadyAvailable.setStatus(ArtifactStatus.AVAILABLE);

		when(artifactRepository.findByPublicId("art_ready1234")).thenReturn(Optional.of(alreadyAvailable));

		org.dradgo.domain.DomainException error = org.junit.jupiter.api.Assertions.assertThrows(
			org.dradgo.domain.DomainException.class,
			() -> adapter.markAvailable(
				"art_ready1234",
				new ArtifactChecksum("sha256", "abc123"),
				"artifacts/run_ready1234/art_ready1234/v1/spec.md"));
		assertEquals(
			org.dradgo.domain.registry.DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
			error.errorCode());
	}

	@Test
	void markFailedRejectsTransitionFromAvailableArtifact() {
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		WorkflowEventRepository workflowEventRepository = mock(WorkflowEventRepository.class);
		ArtifactRecordPersistenceAdapter adapter = new ArtifactRecordPersistenceAdapter(
			artifactRepository,
			workflowRunRepository,
			workflowEventRepository,
			new ArtifactEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity alreadyAvailable = artifactEntity("art_ready1234", run, linkedEvent, null, 1);
		alreadyAvailable.setStatus(ArtifactStatus.AVAILABLE);

		when(artifactRepository.findByPublicId("art_ready1234")).thenReturn(Optional.of(alreadyAvailable));

		org.dradgo.domain.DomainException error = org.junit.jupiter.api.Assertions.assertThrows(
			org.dradgo.domain.DomainException.class,
			() -> adapter.markFailed(
				"art_ready1234",
				FailureCategory.RUNNER_CRASH,
				"runner exited"));
		assertEquals(
			org.dradgo.domain.registry.DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
			error.errorCode());
	}

	@Test
	void findPendingByArtifactIdRaisesInternalErrorWhenInvariantIsViolated() {
		ArtifactOperationRepository operationRepository = mock(ArtifactOperationRepository.class);
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		ArtifactOperationPersistenceAdapter adapter = new ArtifactOperationPersistenceAdapter(
			operationRepository,
			artifactRepository,
			workflowRunRepository,
			new ArtifactOperationEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity artifact = artifactEntity("art_ready1234", run, linkedEvent, null, 1);
		ArtifactOperationEntity pendingOne = operationEntity("op_pendone1234", run, artifact, linkedEvent, "create");
		ArtifactOperationEntity pendingTwo = operationEntity("op_pendtwo1234", run, artifact, linkedEvent, "update");

		when(operationRepository.findByArtifactPublicIdAndStatusOrderByCreatedAtDesc(
			"art_ready1234",
			ArtifactOperationStatus.PENDING.value()))
			.thenReturn(List.of(pendingOne, pendingTwo));

		DomainException error = assertThrows(
			DomainException.class,
			() -> adapter.findPendingByArtifactId("art_ready1234"));

		assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
		assertEquals("art_ready1234", error.details().get("artifactId"));
		assertEquals(List.of("op_pendone1234", "op_pendtwo1234"), error.details().get("pendingOperationIds"));
	}

	@Test
	void operationAdapterSupportsReplayPendingLookupAndStatusTransitions() {
		ArtifactOperationRepository operationRepository = mock(ArtifactOperationRepository.class);
		ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
		WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
		ArtifactOperationPersistenceAdapter adapter = new ArtifactOperationPersistenceAdapter(
			operationRepository,
			artifactRepository,
			workflowRunRepository,
			new ArtifactOperationEntityMapper());
		WorkflowRunEntity run = WorkflowRunEntity.create("run_ready1234", WorkflowState.INBOX);
		WorkflowEventEntity linkedEvent = workflowEvent("evt_link1234", run);
		ArtifactEntity artifact = artifactEntity("art_ready1234", run, linkedEvent, null, 1);
		ArtifactOperationEntity pending = operationEntity("op_ready1234", run, artifact, linkedEvent, "create");
		// Separate entity for markFailed — the op-level state-machine guard (fix 3) now rejects
		// markFailed on a COMPLETE operation, so we use a distinct PENDING entity here.
		ArtifactOperationEntity failPending = operationEntity("op_fail1234", run, artifact, linkedEvent, "update");
		ArtifactOperationEntity stalePending = operationEntity("op_stale1234", run, artifact, linkedEvent, "replace");

		when(workflowRunRepository.findByPublicId("run_ready1234")).thenReturn(Optional.of(run));
		when(artifactRepository.findByPublicId("art_ready1234")).thenReturn(Optional.of(artifact));
		when(operationRepository.saveAndFlush(any()))
			.thenReturn(pending)
			.thenAnswer(invocation -> invocation.getArgument(0))
			.thenAnswer(invocation -> invocation.getArgument(0))
			.thenAnswer(invocation -> invocation.getArgument(0));
		when(operationRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndIdempotencyKeyAndOperationType(
			"run_ready1234",
			ArtifactType.SPEC.value(),
			"idem-1234567890",
			"create"))
			.thenReturn(Optional.of(pending));
		when(operationRepository.findByPublicId("op_ready1234")).thenReturn(Optional.of(pending));
		when(operationRepository.findByPublicId("op_fail1234")).thenReturn(Optional.of(failPending));
		when(operationRepository.findByStatusAndCreatedAtOlderThanSeconds(
			ArtifactOperationStatus.PENDING.value(),
			900L))
			.thenReturn(List.of(stalePending));
		when(operationRepository.findByPublicId("op_stale1234")).thenReturn(Optional.of(stalePending));

		ArtifactOperationSnapshot created = adapter.createPending(
			"op_ready1234",
			"run_ready1234",
			ArtifactType.SPEC,
			"art_ready1234",
			"create",
			"idem-1234567890");
		ArtifactOperationSnapshot replay = adapter.findReplay(
			"run_ready1234",
			ArtifactType.SPEC,
			"idem-1234567890",
			"create").orElseThrow();
		ArtifactOperationSnapshot complete = adapter.markComplete("op_ready1234");
		// markFailed on a separate PENDING operation — cannot re-use op_ready1234 (already COMPLETE)
		ArtifactOperationSnapshot failed = adapter.markFailed(
			"op_fail1234",
			FailureCategory.RUNNER_CRASH,
			"runner exited before payload write");
		List<ArtifactOperationSnapshot> stale = adapter.findPendingOlderThan(Duration.ofMinutes(15));
		ArtifactOperationSnapshot orphaned = adapter.markFailedOrphan(
			"op_stale1234",
			"artifact payload never materialized");

		assertEquals(ArtifactOperationStatus.PENDING, created.status());
		assertEquals(created.publicId(), replay.publicId());
		assertEquals(ArtifactOperationStatus.COMPLETE, complete.status());
		assertEquals(ArtifactOperationStatus.FAILED, failed.status());
		assertEquals(FailureCategory.RUNNER_CRASH, failed.failureCategory());
		assertEquals(1, stale.size());
		assertEquals(ArtifactOperationStatus.FAILED_ORPHAN, orphaned.status());
	}

	@Test
	void eventAdapterBridgesArtifactEventsThroughTheCanonicalWorkflowEventPort() {
		WorkflowEventWritePort workflowEventWritePort = mock(WorkflowEventWritePort.class);
		ArtifactEventPersistenceAdapter adapter = new ArtifactEventPersistenceAdapter(workflowEventWritePort);

		adapter.append(new ArtifactEventRecord(
			"run_ready1234",
			WorkflowEventType.ARTIFACT_AVAILABLE,
			"system",
			ActorType.SYSTEM,
			"artifact became readable",
			null,
			OffsetDateTime.now(ZoneOffset.UTC),
			Map.of("artifactId", "art_ready1234")));

		verify(workflowEventWritePort).append(any(WorkflowEventRecord.class));
	}

	private WorkflowEventEntity workflowEvent(String publicId, WorkflowRunEntity workflowRun) {
		WorkflowEventEntity entity = new WorkflowEventEntity();
		entity.setPublicId(publicId);
		entity.setWorkflowRun(workflowRun);
		entity.setEventType(WorkflowEventType.ARTIFACT_VERSION_CREATED);
		entity.setActorIdentity("system");
		entity.setActorType(ActorType.SYSTEM);
		entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
		entity.setDetails(Map.of());
		return entity;
	}

	private ArtifactEntity artifactEntity(
		String publicId,
		WorkflowRunEntity workflowRun,
		WorkflowEventEntity linkedEvent,
		ArtifactEntity parent,
		int version
	) {
		ArtifactEntity entity = new ArtifactEntity();
		entity.setPublicId(publicId);
		entity.setWorkflowRun(workflowRun);
		entity.setArtifactType(ArtifactType.SPEC);
		entity.setVersion(version);
		entity.setParentArtifact(parent);
		entity.setClassification(DataClassification.LOCAL_ONLY);
		entity.setStatus(ArtifactStatus.PENDING);
		entity.setLinkedEvent(linkedEvent);
		return entity;
	}

	private ArtifactOperationEntity operationEntity(
		String publicId,
		WorkflowRunEntity workflowRun,
		ArtifactEntity artifact,
		WorkflowEventEntity linkedEvent,
		String operationType
	) {
		ArtifactOperationEntity entity = new ArtifactOperationEntity();
		entity.setPublicId(publicId);
		entity.setWorkflowRun(workflowRun);
		entity.setArtifact(artifact);
		entity.setLinkedEvent(linkedEvent);
		entity.setOperationType(operationType);
		entity.setArtifactType(artifact.getArtifactType());
		entity.setStatus(ArtifactOperationStatus.PENDING);
		entity.setIdempotencyKey("idem-1234567890");
		return entity;
	}
}
