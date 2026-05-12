package org.dradgo.adapters.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.dradgo.adapters.persistence.entity.RunnerExecutionEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.RunnerExecutionEntityMapper;
import org.dradgo.adapters.persistence.repository.RunnerExecutionRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerExecutionStateMachine;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
public class RunnerExecutionPersistenceAdapter implements RunnerExecutionRecordPort {

	private static final Logger log = LoggerFactory.getLogger(RunnerExecutionPersistenceAdapter.class);

	private final RunnerExecutionRepository runnerExecutionRepository;
	private final WorkflowRunRepository workflowRunRepository;
	private final RunnerExecutionEntityMapper mapper;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public RunnerExecutionPersistenceAdapter(
		RunnerExecutionRepository runnerExecutionRepository,
		WorkflowRunRepository workflowRunRepository,
		RunnerExecutionEntityMapper mapper
	) {
		this(runnerExecutionRepository, workflowRunRepository, mapper, Clock.systemUTC());
	}

	RunnerExecutionPersistenceAdapter(
		RunnerExecutionRepository runnerExecutionRepository,
		WorkflowRunRepository workflowRunRepository,
		RunnerExecutionEntityMapper mapper,
		Clock clock
	) {
		this.runnerExecutionRepository = Objects.requireNonNull(runnerExecutionRepository, "runnerExecutionRepository");
		this.workflowRunRepository = Objects.requireNonNull(workflowRunRepository, "workflowRunRepository");
		this.mapper = Objects.requireNonNull(mapper, "mapper");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public Optional<RunnerExecutionSnapshot> findByPublicId(String publicId) {
		PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
		return runnerExecutionRepository.findByPublicId(publicId).map(mapper::toSnapshot);
	}

	@Override
	public List<RunnerExecutionSnapshot> findByWorkflowRunPublicIdAndStatusIn(
		String workflowRunPublicId,
		List<RunnerExecutionStatus> statuses
	) {
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		if (statuses == null || statuses.isEmpty()) {
			return List.of();
		}
		List<String> rawStatuses = statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
		return runnerExecutionRepository
			.findByWorkflowRunPublicIdAndStatusIn(workflowRunPublicId, rawStatuses)
			.stream()
			.map(mapper::toSnapshot)
			.collect(Collectors.toList());
	}

	@Override
	public List<RunnerExecutionSnapshot> findStaleByStatusInAndTimeoutAtBefore(
		List<RunnerExecutionStatus> statuses,
		Duration scanWindow,
		int limit
	) {
		Objects.requireNonNull(scanWindow, "scanWindow");
		if (statuses == null || statuses.isEmpty()) {
			return List.of();
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		// DB-side comparison: cutoff = now() - scanWindow per 1.12 H10 pattern (no JVM clock drift).
		OffsetDateTime cutoff = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(scanWindow);
		List<String> rawStatuses = statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
		return runnerExecutionRepository
			.findStaleByStatusInAndTimeoutAtBefore(rawStatuses, cutoff, Limit.of(limit))
			.stream()
			.map(mapper::toSnapshot)
			.collect(Collectors.toList());
	}

	@Override
	public List<RunnerExecutionSnapshot> findActiveStatuses(List<RunnerExecutionStatus> statuses, int limit) {
		if (statuses == null || statuses.isEmpty()) {
			return List.of();
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		List<String> rawStatuses = statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
		return runnerExecutionRepository
			.findByStatusInOrderByCreatedAtAsc(rawStatuses, Limit.of(limit))
			.stream()
			.map(mapper::toSnapshot)
			.collect(Collectors.toList());
	}

	@Override
	public int nextContextBundleVersion(String workflowRunPublicId, RunnerStage stage) {
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		Objects.requireNonNull(stage, "stage");
		return runnerExecutionRepository
			.findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(workflowRunPublicId, stage.value())
			.map(entity -> entity.getContextBundleVersion() + 1)
			.orElse(1);
	}

	@Override
	public RunnerExecutionSnapshot insertPending(
		String publicId,
		String workflowRunPublicId,
		RunnerStage stage,
		int contextBundleVersion,
		ExecutionConstraints executionConstraints
	) {
		PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(executionConstraints, "executionConstraints");
		if (contextBundleVersion <= 0) {
			throw new IllegalArgumentException("contextBundleVersion must be positive");
		}
		WorkflowRunEntity workflowRun = workflowRunRepository.findByPublicId(workflowRunPublicId)
			.orElseThrow(() -> runNotFound(workflowRunPublicId));

		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		RunnerExecutionEntity entity = new RunnerExecutionEntity();
		entity.setPublicId(publicId);
		entity.setWorkflowRun(workflowRun);
		entity.setStage(stage);
		entity.setStatus(RunnerExecutionStatus.PENDING);
		entity.setContextBundleVersion(contextBundleVersion);
		entity.setLastActivityAt(now);
		entity.setTimeoutAt(now.plus(executionConstraints.timeout()));
		entity.setFailureCategory(null);
		entity.setCompletedAt(null);
		RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
		log.info("insertPending publicId={} workflowRunId={} stage={} contextBundleVersion={} timeoutAt={}",
			publicId, workflowRunPublicId, stage.value(), contextBundleVersion, saved.getTimeoutAt());
		return mapper.toSnapshot(saved);
	}

	@Override
	public RunnerExecutionSnapshot transitionToRunning(String publicId, OffsetDateTime lastActivityAt) {
		Objects.requireNonNull(lastActivityAt, "lastActivityAt");
		return mutateWithGuard(publicId, RunnerExecutionStatus.RUNNING, entity -> {
			OffsetDateTime activity = lastActivityAt.withOffsetSameInstant(ZoneOffset.UTC);
			entity.setLastActivityAt(activity);
			ensureTimeoutAfterActivity(entity, activity, Duration.ZERO);
			entity.setStatus(RunnerExecutionStatus.RUNNING);
		});
	}

	@Override
	public RunnerExecutionSnapshot touchActivity(
		String publicId,
		OffsetDateTime lastActivityAt,
		Duration staleTimeoutWindow
	) {
		Objects.requireNonNull(lastActivityAt, "lastActivityAt");
		Objects.requireNonNull(staleTimeoutWindow, "staleTimeoutWindow");
		PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
		RunnerExecutionEntity entity = runnerExecutionRepository.findByPublicIdForUpdate(publicId)
			.orElseThrow(() -> runnerExecutionNotFound(publicId));
		RunnerExecutionStatus current = entity.getStatus();
		if (current != RunnerExecutionStatus.PENDING && current != RunnerExecutionStatus.RUNNING) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("runnerExecutionId", publicId);
			details.put("currentStatus", current.value());
			details.put("reason", "touch_activity_requires_non_terminal_status");
			throw new DomainException(
				DomainErrorCode.ILLEGAL_TRANSITION,
				"Cannot touch activity on terminal runner execution: " + publicId,
				details);
		}
		OffsetDateTime activity = lastActivityAt.withOffsetSameInstant(ZoneOffset.UTC);
		entity.setLastActivityAt(activity);
		ensureTimeoutAfterActivity(entity, activity, staleTimeoutWindow);
		return mapper.toSnapshot(runnerExecutionRepository.saveAndFlush(entity));
	}

	@Override
	public RunnerExecutionSnapshot markCompleted(String publicId, OffsetDateTime completedAt) {
		Objects.requireNonNull(completedAt, "completedAt");
		return mutateWithGuard(publicId, RunnerExecutionStatus.COMPLETED, entity -> {
			entity.setStatus(RunnerExecutionStatus.COMPLETED);
			entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
			entity.setFailureCategory(null);
		});
	}

	@Override
	public RunnerExecutionSnapshot markFailed(
		String publicId,
		FailureCategory failureCategory,
		OffsetDateTime completedAt
	) {
		Objects.requireNonNull(failureCategory, "failureCategory");
		Objects.requireNonNull(completedAt, "completedAt");
		return mutateWithGuard(publicId, RunnerExecutionStatus.FAILED, entity -> {
			entity.setStatus(RunnerExecutionStatus.FAILED);
			entity.setFailureCategory(failureCategory);
			entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
		});
	}

	@Override
	public RunnerExecutionSnapshot markTimedOut(String publicId, OffsetDateTime completedAt) {
		Objects.requireNonNull(completedAt, "completedAt");
		return mutateWithGuard(publicId, RunnerExecutionStatus.TIMED_OUT, entity -> {
			entity.setStatus(RunnerExecutionStatus.TIMED_OUT);
			entity.setFailureCategory(FailureCategory.RUNNER_TIMEOUT);
			entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
		});
	}

	@Override
	public RunnerExecutionSnapshot markOrphaned(String publicId, OffsetDateTime completedAt) {
		Objects.requireNonNull(completedAt, "completedAt");
		return mutateWithGuard(publicId, RunnerExecutionStatus.ORPHANED, entity -> {
			entity.setStatus(RunnerExecutionStatus.ORPHANED);
			entity.setFailureCategory(FailureCategory.ORPHAN);
			entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
		});
	}

	private RunnerExecutionSnapshot mutateWithGuard(
		String publicId,
		RunnerExecutionStatus targetStatus,
		java.util.function.Consumer<RunnerExecutionEntity> mutator
	) {
		PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
		RunnerExecutionEntity entity = runnerExecutionRepository.findByPublicIdForUpdate(publicId)
			.orElseThrow(() -> runnerExecutionNotFound(publicId));
		RunnerExecutionStatus current = entity.getStatus();
		RunnerExecutionStateMachine.assertCanTransition(publicId, current, targetStatus);
		mutator.accept(entity);
		RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
		log.info("transition runnerExecutionId={} from={} to={}", publicId, current.value(), targetStatus.value());
		return mapper.toSnapshot(saved);
	}

	private static void ensureTimeoutAfterActivity(
		RunnerExecutionEntity entity,
		OffsetDateTime activity,
		Duration staleTimeoutWindow
	) {
		// AC7 invariant: timeout_at >= last_activity_at. Never reduce timeout_at. Heartbeats and
		// poll-driven activity extend the stale deadline to at least activity + stale window.
		OffsetDateTime candidate = activity.plus(staleTimeoutWindow);
		if (entity.getTimeoutAt() == null || candidate.isAfter(entity.getTimeoutAt())) {
			entity.setTimeoutAt(candidate);
		} else if (activity.isAfter(entity.getTimeoutAt())) {
			entity.setTimeoutAt(activity);
		}
	}

	private static DomainException runnerExecutionNotFound(String publicId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runnerExecutionId", publicId);
		return new DomainException(
			DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
			"Runner execution not found: " + publicId,
			details);
	}

	private static DomainException runNotFound(String workflowRunPublicId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", workflowRunPublicId);
		return new DomainException(
			DomainErrorCode.RUN_NOT_FOUND,
			"Workflow run not found: " + workflowRunPublicId,
			details);
	}
}
