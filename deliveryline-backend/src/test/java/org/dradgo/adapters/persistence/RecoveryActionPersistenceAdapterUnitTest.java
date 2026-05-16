package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.RecoveryActionEntity;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.RecoveryActionEntityMapper;
import org.dradgo.adapters.persistence.repository.RecoveryActionRepository;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class RecoveryActionPersistenceAdapterUnitTest {

	private RecoveryActionRepository recoveryActionRepository;
	private WorkflowRunRepository workflowRunRepository;
	private WorkflowEventRepository workflowEventRepository;
	private RecoveryActionEntityMapper mapper;
	private RecoveryActionPersistenceAdapter adapter;

	@BeforeEach
	void setUp() {
		recoveryActionRepository = mock(RecoveryActionRepository.class);
		workflowRunRepository = mock(WorkflowRunRepository.class);
		workflowEventRepository = mock(WorkflowEventRepository.class);
		mapper = new RecoveryActionEntityMapper();
		adapter = new RecoveryActionPersistenceAdapter(
			recoveryActionRepository, workflowRunRepository, workflowEventRepository, mapper);
	}

	@Test
	void insertReturnsSnapshotWithRcvPrefixAndPersistsAllFields() {
		WorkflowRunEntity run = workflowRun(101L, "run_unit-test-aaa1");
		WorkflowEventEntity triggering = workflowEvent(7001L, "evt_trig-aaaa1", run, WorkflowEventType.WORKFLOW_STATE_CHANGED);
		WorkflowEventEntity resulting = workflowEvent(7002L, "evt_rsl1-bbbb1", run, WorkflowEventType.RECOVERY_RETRIED);

		when(workflowRunRepository.findByPublicId("run_unit-test-aaa1")).thenReturn(Optional.of(run));
		when(workflowEventRepository.findByPublicId("evt_trig-aaaa1")).thenReturn(Optional.of(triggering));
		when(workflowEventRepository.findByPublicId("evt_rsl1-bbbb1")).thenReturn(Optional.of(resulting));
		when(recoveryActionRepository.saveAndFlush(any(RecoveryActionEntity.class))).thenAnswer(invocation -> {
			RecoveryActionEntity entity = invocation.getArgument(0);
			setEntityId(entity, 9001L);
			setEntityCreatedAt(entity, OffsetDateTime.now(ZoneOffset.UTC));
			return entity;
		});

		RecoveryActionWriteCommand command = new RecoveryActionWriteCommand(
			"run_unit-test-aaa1",
			"retry",
			"evt_trig-aaaa1",
			"evt_rsl1-bbbb1",
			"alex",
			ActorType.HUMAN,
			"idem-retry-1234567890",
			"pending");

		RecoveryActionSnapshot snapshot = adapter.insert(command);

		assertNotNull(snapshot);
		assertTrue(snapshot.publicId().startsWith("rcv_"), () -> "expected rcv_ prefix but got " + snapshot.publicId());
		assertEquals(9001L, snapshot.internalId());
		assertEquals("run_unit-test-aaa1", snapshot.workflowRunPublicId());
		assertEquals("retry", snapshot.actionType());
		assertEquals("evt_trig-aaaa1", snapshot.triggeringEventPublicId());
		assertEquals("evt_rsl1-bbbb1", snapshot.resultingEventPublicId());
		assertEquals("alex", snapshot.actorIdentity());
		assertEquals(ActorType.HUMAN, snapshot.actorType());
		assertEquals("idem-retry-1234567890", snapshot.idempotencyKey());
		assertEquals("pending", snapshot.resultStatus());

		ArgumentCaptor<RecoveryActionEntity> captor = ArgumentCaptor.forClass(RecoveryActionEntity.class);
		verify(recoveryActionRepository, times(1)).saveAndFlush(captor.capture());
		RecoveryActionEntity persisted = captor.getValue();
		assertSame(triggering, persisted.getTriggeringEvent());
		assertSame(resulting, persisted.getResultingEvent());
		assertNull(persisted.getReviewerRole(), "story 1.18 leaves reviewer_role NULL — Epic 4 owns it");
	}

	@Test
	void insertWithNullEventPublicIdsLeavesFkColumnsNull() {
		WorkflowRunEntity run = workflowRun(102L, "run_unit-test-bbb1");
		when(workflowRunRepository.findByPublicId("run_unit-test-bbb1")).thenReturn(Optional.of(run));
		when(recoveryActionRepository.saveAndFlush(any(RecoveryActionEntity.class))).thenAnswer(invocation -> {
			RecoveryActionEntity entity = invocation.getArgument(0);
			setEntityId(entity, 9002L);
			setEntityCreatedAt(entity, OffsetDateTime.now(ZoneOffset.UTC));
			return entity;
		});

		RecoveryActionWriteCommand command = new RecoveryActionWriteCommand(
			"run_unit-test-bbb1",
			"retry",
			null,
			null,
			"alex",
			ActorType.HUMAN,
			"idem-retry-no-events",
			"pending");

		RecoveryActionSnapshot snapshot = adapter.insert(command);

		assertNull(snapshot.triggeringEventPublicId());
		assertNull(snapshot.resultingEventPublicId());
		verify(workflowEventRepository, never()).findByPublicId(any());
	}

	@Test
	void insertTranslatesUniqueIdempotencyKeyViolationToIdempotencyKeyConflict() {
		WorkflowRunEntity run = workflowRun(103L, "run_unit-test-ccc1");
		when(workflowRunRepository.findByPublicId("run_unit-test-ccc1")).thenReturn(Optional.of(run));
		DataIntegrityViolationException violation = new DataIntegrityViolationException(
			"could not execute statement",
			new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_recovery_actions_idempotency_key\""));
		when(recoveryActionRepository.saveAndFlush(any(RecoveryActionEntity.class))).thenThrow(violation);

		RecoveryActionWriteCommand command = new RecoveryActionWriteCommand(
			"run_unit-test-ccc1",
			"retry",
			null,
			null,
			"alex",
			ActorType.HUMAN,
			"idem-retry-conflict",
			"pending");

		DomainException error = assertThrows(DomainException.class, () -> adapter.insert(command));
		assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
		assertEquals("idem-retry-conflict", error.details().get("idempotencyKey"));
		assertEquals("uq_recovery_actions_idempotency_key", error.details().get("constraint"));
	}

	@Test
	void insertTranslatesUnknownConstraintViolationToInternalError() {
		WorkflowRunEntity run = workflowRun(104L, "run_unit-test-ddd1");
		when(workflowRunRepository.findByPublicId("run_unit-test-ddd1")).thenReturn(Optional.of(run));
		DataIntegrityViolationException violation = new DataIntegrityViolationException(
			"could not execute statement",
			new RuntimeException("ERROR: violates check constraint \"ck_recovery_actions_action_type\""));
		when(recoveryActionRepository.saveAndFlush(any(RecoveryActionEntity.class))).thenThrow(violation);

		RecoveryActionWriteCommand command = new RecoveryActionWriteCommand(
			"run_unit-test-ddd1",
			"retry",
			null,
			null,
			"alex",
			ActorType.HUMAN,
			"idem-retry-bad-action",
			"pending");

		DomainException error = assertThrows(DomainException.class, () -> adapter.insert(command));
		assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
		assertEquals("recovery_action_constraint_violation", error.details().get("reason"));
	}

	@Test
	void insertWhenWorkflowRunMissingThrowsInternalError() {
		when(workflowRunRepository.findByPublicId("run_unit-test-missing")).thenReturn(Optional.empty());
		RecoveryActionWriteCommand command = new RecoveryActionWriteCommand(
			"run_unit-test-missing",
			"retry",
			null,
			null,
			"alex",
			ActorType.HUMAN,
			"idem-retry-missing-run",
			"pending");

		DomainException error = assertThrows(DomainException.class, () -> adapter.insert(command));
		assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
		assertEquals("workflowRunPublicId_not_found", error.details().get("reason"));
		verify(recoveryActionRepository, never()).saveAndFlush(any(RecoveryActionEntity.class));
	}

	@Test
	void findByIdempotencyKeyReturnsEmptyWhenNoRow() {
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-not-there")).thenReturn(Optional.empty());
		assertTrue(adapter.findByIdempotencyKey("idem-retry-not-there").isEmpty());
	}

	@Test
	void findByIdempotencyKeyReturnsSnapshotWhenRowExists() {
		WorkflowRunEntity run = workflowRun(105L, "run_unit-test-eee1");
		RecoveryActionEntity entity = recoveryActionEntity(9100L, "rcv_lookup-eeee1", run, "idem-retry-found", "pending");
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-found")).thenReturn(Optional.of(entity));

		Optional<RecoveryActionSnapshot> result = adapter.findByIdempotencyKey("idem-retry-found");
		assertTrue(result.isPresent());
		assertEquals("rcv_lookup-eeee1", result.get().publicId());
		assertEquals("pending", result.get().resultStatus());
	}

	@Test
	void markSucceededFlipsPendingToSucceededAndPersists() {
		WorkflowRunEntity run = workflowRun(106L, "run_unit-test-fff1");
		RecoveryActionEntity entity = recoveryActionEntity(9200L, "rcv_succ1-eeee1", run, "idem-retry-succ", "pending");
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-succ")).thenReturn(Optional.of(entity));
		when(recoveryActionRepository.saveAndFlush(entity)).thenReturn(entity);

		RecoveryActionSnapshot snapshot = adapter.markSucceeded("idem-retry-succ");
		assertEquals("succeeded", snapshot.resultStatus());
		verify(recoveryActionRepository, times(1)).saveAndFlush(entity);
	}

	@Test
	void markFailedFlipsPendingToFailedAndPersists() {
		WorkflowRunEntity run = workflowRun(107L, "run_unit-test-ggg1");
		RecoveryActionEntity entity = recoveryActionEntity(9300L, "rcv_fail1-eeee1", run, "idem-retry-fail", "pending");
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-fail")).thenReturn(Optional.of(entity));
		when(recoveryActionRepository.saveAndFlush(entity)).thenReturn(entity);

		RecoveryActionSnapshot snapshot = adapter.markFailed("idem-retry-fail");
		assertEquals("failed", snapshot.resultStatus());
	}

	@Test
	void markSucceededOnAlreadyTerminalRowThrowsInternalError() {
		WorkflowRunEntity run = workflowRun(108L, "run_unit-test-hhh1");
		RecoveryActionEntity entity = recoveryActionEntity(9400L, "rcv_term1-eeee1", run, "idem-retry-term", "succeeded");
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-term")).thenReturn(Optional.of(entity));

		DomainException error = assertThrows(DomainException.class, () -> adapter.markSucceeded("idem-retry-term"));
		assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
		assertEquals("succeeded", error.details().get("currentResultStatus"));
		assertEquals("succeeded", error.details().get("targetResultStatus"));
		assertEquals("result_status_terminal_transition_rejected", error.details().get("reason"));
		verify(recoveryActionRepository, never()).saveAndFlush(any(RecoveryActionEntity.class));
	}

	@Test
	void markFailedOnFailedRowAlsoRejectedAsTerminalTransition() {
		WorkflowRunEntity run = workflowRun(109L, "run_unit-test-iii1");
		RecoveryActionEntity entity = recoveryActionEntity(9500L, "rcv_term2-eeee1", run, "idem-retry-term2", "failed");
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-term2")).thenReturn(Optional.of(entity));

		DomainException error = assertThrows(DomainException.class, () -> adapter.markFailed("idem-retry-term2"));
		assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
		assertEquals("result_status_terminal_transition_rejected", error.details().get("reason"));
	}

	@Test
	void markSucceededOnMissingRowThrowsInternalError() {
		when(recoveryActionRepository.findByIdempotencyKey("idem-retry-missing")).thenReturn(Optional.empty());
		DomainException error = assertThrows(DomainException.class, () -> adapter.markSucceeded("idem-retry-missing"));
		assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
		assertEquals("recovery_action_record_missing", error.details().get("reason"));
	}

	private static WorkflowRunEntity workflowRun(long id, String publicId) {
		WorkflowRunEntity run = new WorkflowRunEntity();
		setEntityId(run, id);
		run.setPublicId(publicId);
		// currentState setter is package-private (story 1.5 invariant) — not needed by these tests.
		return run;
	}

	private static WorkflowEventEntity workflowEvent(long id, String publicId, WorkflowRunEntity run, WorkflowEventType type) {
		WorkflowEventEntity event = new WorkflowEventEntity();
		setEntityId(event, id);
		event.setPublicId(publicId);
		event.setWorkflowRun(run);
		event.setEventType(type);
		event.setActorIdentity("alex");
		event.setActorType(ActorType.HUMAN);
		return event;
	}

	private static RecoveryActionEntity recoveryActionEntity(
		long id,
		String publicId,
		WorkflowRunEntity run,
		String idempotencyKey,
		String resultStatus
	) {
		RecoveryActionEntity entity = new RecoveryActionEntity();
		setEntityId(entity, id);
		entity.setPublicId(publicId);
		entity.setWorkflowRun(run);
		entity.setActionType("retry");
		entity.setActorIdentity("alex");
		entity.setActorType(ActorType.HUMAN);
		entity.setIdempotencyKey(idempotencyKey);
		entity.setResultStatus(resultStatus);
		setEntityCreatedAt(entity, OffsetDateTime.now(ZoneOffset.UTC));
		return entity;
	}

	private static void setEntityId(Object entity, long id) {
		try {
			Field field = entity.getClass().getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		} catch (ReflectiveOperationException error) {
			throw new RuntimeException(error);
		}
	}

	private static void setEntityCreatedAt(RecoveryActionEntity entity, OffsetDateTime createdAt) {
		try {
			Field field = RecoveryActionEntity.class.getDeclaredField("createdAt");
			field.setAccessible(true);
			field.set(entity, createdAt);
		} catch (ReflectiveOperationException error) {
			throw new RuntimeException(error);
		}
	}
}
