package org.dradgo.application.workflow;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowTransitionService {

	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 256;

	private final WorkflowRunRepository workflowRunRepository;
	private final WorkflowEventRepository workflowEventRepository;
	private final WorkflowTransitionTable transitionTable;
	private final TransactionTemplate transactionTemplate;
	private final WorkflowTransitionConcurrencyProbe concurrencyProbe;

	public WorkflowTransitionService(
		WorkflowRunRepository workflowRunRepository,
		WorkflowEventRepository workflowEventRepository,
		PlatformTransactionManager transactionManager,
		ObjectProvider<WorkflowTransitionConcurrencyProbe> concurrencyProbeProvider
	) {
		this.workflowRunRepository = workflowRunRepository;
		this.workflowEventRepository = workflowEventRepository;
		this.transitionTable = WorkflowTransitionTable.defaultTable();
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.concurrencyProbe = concurrencyProbeProvider.getIfAvailable(WorkflowTransitionConcurrencyProbe::noop);
	}

	public void transition(
		String runId,
		WorkflowState targetState,
		TransitionActor actor,
		String reason,
		String idempotencyKey
	) {
		transition(runId, targetState, actor, reason, idempotencyKey, null);
	}

	public void transition(
		String runId,
		WorkflowState targetState,
		TransitionActor actor,
		String reason,
		String idempotencyKey,
		FailureCategory failureCategory
	) {
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(targetState, "targetState");
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		if (idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey must not be blank");
		}
		if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new IllegalArgumentException(
				"idempotencyKey exceeds max length " + MAX_IDEMPOTENCY_KEY_LENGTH);
		}

		try {
			transactionTemplate.executeWithoutResult(status ->
				doTransition(runId, targetState, actor, reason, idempotencyKey, failureCategory));
		} catch (OptimisticLockingFailureException exception) {
			throw concurrentConflict(runId, targetState, idempotencyKey, exception);
		}
	}

	private void doTransition(
		String runId,
		WorkflowState targetState,
		TransitionActor actor,
		String reason,
		String idempotencyKey,
		FailureCategory failureCategory
	) {
		WorkflowRunEntity workflowRun = workflowRunRepository.findByPublicId(runId)
			.orElseThrow(() -> runNotFound(runId));
		if (workflowRun.getArchivedAt() != null) {
			throw archivedRunRejected(runId, targetState);
		}
		concurrencyProbe.afterRunLoaded(runId);

		WorkflowState priorState = workflowRun.getCurrentState();
		transitionTable.assertTransitionAllowed(runId, priorState, targetState, failureCategory, reason);

		workflowRun.setCurrentState(targetState);
		workflowRunRepository.saveAndFlush(workflowRun);

		WorkflowEventEntity event = new WorkflowEventEntity();
		event.setPublicId(nextEventPublicId());
		event.setWorkflowRun(workflowRun);
		event.setEventType(WorkflowEventType.WORKFLOW_STATE_CHANGED);
		event.setPriorState(priorState);
		event.setResultingState(targetState);
		event.setActorIdentity(actor.identity());
		event.setActorType(actor.type());
		event.setReason(reason);
		event.setFailureCategory(failureCategory);
		event.setInterventionMarker(targetState == WorkflowState.TAKEN_OVER || targetState == WorkflowState.RECONCILED);
		event.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
		event.getDetails().put("idempotencyKey", idempotencyKey);
		workflowEventRepository.saveAndFlush(event);
	}

	private String nextEventPublicId() {
		return PublicIdPrefixes.WORKFLOW_EVENT.format(UUID.randomUUID().toString().replace("-", ""));
	}

	private DomainException runNotFound(String runId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", runId);
		return new DomainException(
			DomainErrorCode.RUN_NOT_FOUND,
			"Workflow run not found: " + runId,
			details);
	}

	private DomainException archivedRunRejected(String runId, WorkflowState targetState) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", runId);
		details.put("targetState", targetState.value());
		details.put("reason", "run_archived");
		return new DomainException(
			DomainErrorCode.ILLEGAL_TRANSITION,
			"Workflow run is archived and cannot be transitioned: " + runId,
			details);
	}

	private DomainException concurrentConflict(
		String runId,
		WorkflowState targetState,
		String idempotencyKey,
		Exception cause
	) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", runId);
		details.put("targetState", targetState.value());
		details.put("idempotencyKey", idempotencyKey);
		return new DomainException(
			DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT,
			"Concurrent workflow transition conflict for run " + runId,
			details,
			cause);
	}

	public record TransitionActor(String identity, ActorType type) {

		public TransitionActor {
			Objects.requireNonNull(identity, "identity");
			Objects.requireNonNull(type, "type");
			if (identity.isBlank()) {
				throw new IllegalArgumentException("identity must not be blank");
			}
		}
	}

	public interface WorkflowTransitionConcurrencyProbe {

		void afterRunLoaded(String runPublicId);

		static WorkflowTransitionConcurrencyProbe noop() {
			return runPublicId -> {
			};
		}
	}
}
