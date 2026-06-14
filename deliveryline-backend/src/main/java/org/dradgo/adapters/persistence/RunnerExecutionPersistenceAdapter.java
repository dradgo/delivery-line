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
import org.dradgo.application.observability.MdcKeys;
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
import org.springframework.transaction.annotation.Transactional;

@Component
public class RunnerExecutionPersistenceAdapter implements RunnerExecutionRecordPort {

  private static final Logger log =
      LoggerFactory.getLogger(RunnerExecutionPersistenceAdapter.class);

  // Story 3.17a (AC2 / Trap T12) — JPA @Lock(PESSIMISTIC_WRITE) emits FOR UPDATE WITHOUT SKIP
  // LOCKED, so the dequeue lease (which MUST skip rows another worker already locked) is a native
  // NamedParameterJdbcTemplate UPDATE ... RETURNING, mirroring WorkflowRunPersistenceAdapter.
  private static final String DEQUEUE_SQL =
      """
      update runner_executions
         set status = 'running',
             worker_id = :workerId,
             dispatched_at = now(),
             queue_attempt_count = queue_attempt_count + 1
       where id = (
         select id
           from runner_executions
          where status = 'queued'
          order by queue_priority asc, created_at asc, id asc
          for update skip locked
          limit 1
       )
      returning public_id
      """;
  private static final String COUNT_QUEUED_SQL =
      "select count(*) from runner_executions where status = 'queued'";

  private final RunnerExecutionRepository runnerExecutionRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final RunnerExecutionEntityMapper mapper;
  private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RunnerExecutionPersistenceAdapter(
      RunnerExecutionRepository runnerExecutionRepository,
      WorkflowRunRepository workflowRunRepository,
      RunnerExecutionEntityMapper mapper,
      org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate) {
    this(runnerExecutionRepository, workflowRunRepository, mapper, jdbcTemplate, Clock.systemUTC());
  }

  RunnerExecutionPersistenceAdapter(
      RunnerExecutionRepository runnerExecutionRepository,
      WorkflowRunRepository workflowRunRepository,
      RunnerExecutionEntityMapper mapper,
      org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate,
      Clock clock) {
    this.runnerExecutionRepository =
        Objects.requireNonNull(runnerExecutionRepository, "runnerExecutionRepository");
    this.workflowRunRepository =
        Objects.requireNonNull(workflowRunRepository, "workflowRunRepository");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // Read methods are wrapped in a readOnly transaction so the lazy `workflowRun` association on
  // RunnerExecutionEntity stays attached to the JPA session through the `mapper::toSnapshot` call
  // (same latent-LazyInit pattern as WorkflowEventPersistenceAdapter — was hidden by mock-based
  // unit tests until the story 1.21 Testcontainers happy-path retry test exercised it).
  @Override
  @Transactional(readOnly = true)
  public Optional<RunnerExecutionSnapshot> findByPublicId(String publicId) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    return runnerExecutionRepository.findByPublicId(publicId).map(mapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findByWorkflowRunPublicIdAndStatusIn(
      String workflowRunPublicId, List<RunnerExecutionStatus> statuses) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    List<String> rawStatuses =
        statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
    return runnerExecutionRepository
        .findByWorkflowRunPublicIdAndStatusIn(workflowRunPublicId, rawStatuses)
        .stream()
        .map(mapper::toSnapshot)
        .collect(Collectors.toList());
  }

  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findStaleByStatusInAndTimeoutAtBefore(
      List<RunnerExecutionStatus> statuses, Duration scanWindow, int limit) {
    Objects.requireNonNull(scanWindow, "scanWindow");
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    // DB-side comparison: cutoff = now() - scanWindow per 1.12 H10 pattern (no JVM clock drift).
    OffsetDateTime cutoff =
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(scanWindow);
    List<String> rawStatuses =
        statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
    return runnerExecutionRepository
        .findStaleByStatusInAndTimeoutAtBefore(rawStatuses, cutoff, Limit.of(limit))
        .stream()
        .map(mapper::toSnapshot)
        .collect(Collectors.toList());
  }

  // Story 3.2a: must be readOnly-transactional like the sibling finders above so the lazy
  // `workflowRun` association survives the `mapper::toSnapshot` call. This backs the broker's
  // pollActiveExecutions() + recoverOnStartup() paths; the gap was latent because the broker unit
  // tests mock this port, and only the story-3.2a broker-driven docker lifecycle ITs exercise it
  // against a real session — they surfaced the LazyInitializationException it caused.
  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findActiveStatuses(
      List<RunnerExecutionStatus> statuses, int limit) {
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    List<String> rawStatuses =
        statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
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
        .findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(
            workflowRunPublicId, stage.value())
        .map(entity -> entity.getContextBundleVersion() + 1)
        .orElse(1);
  }

  @Override
  public RunnerExecutionSnapshot insertPending(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      int contextBundleVersion,
      ExecutionConstraints executionConstraints) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(executionConstraints, "executionConstraints");
    if (contextBundleVersion <= 0) {
      throw new IllegalArgumentException("contextBundleVersion must be positive");
    }
    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicId(workflowRunPublicId)
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
    log.info(
        "insertPending publicId={} workflowRunId={} stage={} contextBundleVersion={} timeoutAt={}",
        publicId,
        workflowRunPublicId,
        stage.value(),
        contextBundleVersion,
        saved.getTimeoutAt());
    return mapper.toSnapshot(saved);
  }

  // Story 3.17a (AC2) — insert a queued row for the RunnerExecutionQueue substrate. Mirrors
  // insertPending but seeds the queue columns (priority + correlationId, no lease yet) and the
  // queued status. Relies on the caller's transaction (RunnerExecutionQueue.enqueue is
  // @Transactional) so the backpressure count + this insert decide atomically.
  @Override
  public RunnerExecutionSnapshot insertQueued(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      int contextBundleVersion,
      Duration timeout,
      int queuePriority,
      String correlationId) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(timeout, "timeout");
    if (contextBundleVersion <= 0) {
      throw new IllegalArgumentException("contextBundleVersion must be positive");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicId(workflowRunPublicId)
            .orElseThrow(() -> runNotFound(workflowRunPublicId));

    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionEntity entity = new RunnerExecutionEntity();
    entity.setPublicId(publicId);
    entity.setWorkflowRun(workflowRun);
    entity.setStage(stage);
    entity.setStatus(RunnerExecutionStatus.QUEUED);
    entity.setContextBundleVersion(contextBundleVersion);
    entity.setLastActivityAt(now);
    entity.setTimeoutAt(now.plus(timeout));
    entity.setFailureCategory(null);
    entity.setCompletedAt(null);
    entity.setQueuePriority(queuePriority);
    entity.setQueueAttemptCount(0);
    entity.setCorrelationId(correlationId);
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "insertQueued publicId={} workflowRunId={} stage={} queuePriority={} contextBundleVersion={}",
        publicId,
        workflowRunPublicId,
        stage.value(),
        queuePriority,
        contextBundleVersion);
    return mapper.toSnapshot(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public long countQueued() {
    Long count =
        jdbcTemplate.queryForObject(COUNT_QUEUED_SQL, new java.util.HashMap<>(), Long.class);
    return count == null ? 0L : count;
  }

  // Story 3.17a (AC2 / Trap T12) — native FOR UPDATE SKIP LOCKED lease. The UPDATE ... RETURNING
  // public_id flips exactly one queued row to running under a row lock that skips rows another
  // worker already holds; the leased row's full snapshot is then re-read via JPA (the RETURNING
  // public_id avoids hand-mapping all columns + the workflow_run join here).
  @Override
  public Optional<RunnerExecutionSnapshot> dequeueNext(String workerId) {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must be non-blank");
    }
    String leasedPublicId =
        jdbcTemplate.query(
            DEQUEUE_SQL,
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                "workerId", workerId),
            rs -> rs.next() ? rs.getString(1) : null);
    if (leasedPublicId == null) {
      return Optional.empty();
    }
    log.info(
        "dequeue leased runnerExecutionId={} workerId={}",
        leasedPublicId,
        MdcKeys.sanitizeForLog(workerId));
    return runnerExecutionRepository.findByPublicId(leasedPublicId).map(mapper::toSnapshot);
  }

  @Override
  public RunnerExecutionSnapshot transitionToRunning(
      String publicId, OffsetDateTime lastActivityAt) {
    Objects.requireNonNull(lastActivityAt, "lastActivityAt");
    return mutateWithGuard(
        publicId,
        RunnerExecutionStatus.RUNNING,
        entity -> {
          OffsetDateTime activity = lastActivityAt.withOffsetSameInstant(ZoneOffset.UTC);
          entity.setLastActivityAt(activity);
          ensureTimeoutAfterActivity(entity, activity, Duration.ZERO);
          entity.setStatus(RunnerExecutionStatus.RUNNING);
          entity.setHeartbeatStaleEmittedAt(null);
        });
  }

  @Override
  public RunnerExecutionSnapshot touchActivity(
      String publicId, OffsetDateTime lastActivityAt, Duration staleTimeoutWindow) {
    Objects.requireNonNull(lastActivityAt, "lastActivityAt");
    Objects.requireNonNull(staleTimeoutWindow, "staleTimeoutWindow");
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
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
    entity.setHeartbeatStaleEmittedAt(null);
    return mapper.toSnapshot(runnerExecutionRepository.saveAndFlush(entity));
  }

  @Override
  public RunnerExecutionSnapshot markCompleted(String publicId, OffsetDateTime completedAt) {
    Objects.requireNonNull(completedAt, "completedAt");
    return mutateWithGuard(
        publicId,
        RunnerExecutionStatus.COMPLETED,
        entity -> {
          entity.setStatus(RunnerExecutionStatus.COMPLETED);
          entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
          entity.setFailureCategory(null);
          entity.setHeartbeatStaleEmittedAt(null);
        });
  }

  @Override
  public RunnerExecutionSnapshot markFailed(
      String publicId, FailureCategory failureCategory, OffsetDateTime completedAt) {
    Objects.requireNonNull(failureCategory, "failureCategory");
    Objects.requireNonNull(completedAt, "completedAt");
    return mutateWithGuard(
        publicId,
        RunnerExecutionStatus.FAILED,
        entity -> {
          entity.setStatus(RunnerExecutionStatus.FAILED);
          entity.setFailureCategory(failureCategory);
          entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
          entity.setHeartbeatStaleEmittedAt(null);
        });
  }

  @Override
  public RunnerExecutionSnapshot markTimedOut(String publicId, OffsetDateTime completedAt) {
    Objects.requireNonNull(completedAt, "completedAt");
    return mutateWithGuard(
        publicId,
        RunnerExecutionStatus.TIMED_OUT,
        entity -> {
          entity.setStatus(RunnerExecutionStatus.TIMED_OUT);
          entity.setFailureCategory(FailureCategory.RUNNER_TIMEOUT);
          entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
          entity.setHeartbeatStaleEmittedAt(null);
        });
  }

  @Override
  public RunnerExecutionSnapshot markOrphaned(String publicId, OffsetDateTime completedAt) {
    Objects.requireNonNull(completedAt, "completedAt");
    return mutateWithGuard(
        publicId,
        RunnerExecutionStatus.ORPHANED,
        entity -> {
          entity.setStatus(RunnerExecutionStatus.ORPHANED);
          entity.setFailureCategory(FailureCategory.ORPHAN);
          entity.setCompletedAt(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
          entity.setHeartbeatStaleEmittedAt(null);
        });
  }

  // Story 3.2a: self-transactional (REQUIRED) — unlike the other mutators (always called inside the
  // broker's TransactionTemplate), markArchived is invoked by
  // RunnerWorkspaceCleanupJob.sweepWorkspaces,
  // which runs outside any transaction. findByPublicIdForUpdate takes a pessimistic lock and needs
  // an
  // active transaction, so without this annotation every cleanup tick threw "No active transaction"
  // (caught best-effort), leaving workspaces deleted-but-never-archived and re-processed forever.
  // REQUIRED joins the broker's transaction when one is already active.
  @Override
  @Transactional
  public RunnerExecutionSnapshot markArchived(String publicId, OffsetDateTime archivedAt) {
    Objects.requireNonNull(archivedAt, "archivedAt");
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    entity.setArchivedAt(archivedAt.withOffsetSameInstant(ZoneOffset.UTC));
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info("markArchived runnerExecutionId={} archivedAt={}", publicId, entity.getArchivedAt());
    return mapper.toSnapshot(saved);
  }

  @Override
  public RunnerExecutionSnapshot markHeartbeatStaleEmitted(
      String publicId, OffsetDateTime emittedAt) {
    Objects.requireNonNull(emittedAt, "emittedAt");
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    entity.setHeartbeatStaleEmittedAt(emittedAt.withOffsetSameInstant(ZoneOffset.UTC));
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "markHeartbeatStaleEmitted runnerExecutionId={} emittedAt={}",
        publicId,
        entity.getHeartbeatStaleEmittedAt());
    return mapper.toSnapshot(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findStaleByStatusInAndLastActivityAtBefore(
      List<RunnerExecutionStatus> statuses, Duration staleWindow, int limit) {
    Objects.requireNonNull(staleWindow, "staleWindow");
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    OffsetDateTime cutoff =
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(staleWindow);
    List<String> rawStatuses =
        statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
    return runnerExecutionRepository
        .findStaleByStatusInAndLastActivityAtBefore(rawStatuses, cutoff, Limit.of(limit))
        .stream()
        .map(mapper::toSnapshot)
        .collect(Collectors.toList());
  }

  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findStaleByStatusInAndStageAndLastActivityAtBefore(
      List<RunnerExecutionStatus> statuses, RunnerStage stage, Duration staleWindow, int limit) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(staleWindow, "staleWindow");
    if (statuses == null || statuses.isEmpty()) {
      return List.of();
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    OffsetDateTime cutoff =
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(staleWindow);
    List<String> rawStatuses =
        statuses.stream().map(RunnerExecutionStatus::value).collect(Collectors.toList());
    return runnerExecutionRepository
        .findStaleByStatusInAndStageAndLastActivityAtBefore(
            rawStatuses, stage.value(), cutoff, Limit.of(limit))
        .stream()
        .map(mapper::toSnapshot)
        .collect(Collectors.toList());
  }

  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findCompletedBeforeAndNotArchived(
      OffsetDateTime cutoff, int limit) {
    Objects.requireNonNull(cutoff, "cutoff");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    // Trap T16: the SQL guard restricts status to terminal values so a live row whose completed_at
    // drifted is never returned to the cleanup sweep.
    List<String> rawStatuses =
        List.of(
            RunnerExecutionStatus.COMPLETED.value(),
            RunnerExecutionStatus.FAILED.value(),
            RunnerExecutionStatus.TIMED_OUT.value(),
            RunnerExecutionStatus.ORPHANED.value());
    return runnerExecutionRepository
        .findCompletedBeforeAndNotArchived(
            rawStatuses, cutoff.withOffsetSameInstant(ZoneOffset.UTC), Limit.of(limit))
        .stream()
        .map(mapper::toSnapshot)
        .collect(Collectors.toList());
  }

  // Story 3.6 AC3/AC6 / Trap T10: self-transactional like markArchived — the capture call site (the
  // Docker adapter at container exit) runs outside any transaction, and findByPublicIdForUpdate
  // needs an active one for its pessimistic lock. METADATA-ONLY: no state-machine guard, no status
  // mutation; an already-terminal row is the normal case (logs are captured after the row
  // completed)
  // and must be tolerated. REQUIRED joins an ambient transaction when one is already active.
  @Override
  @Transactional
  public RunnerExecutionSnapshot recordRawOutput(
      String publicId,
      String reference,
      org.dradgo.domain.registry.DataClassification classification,
      long byteSize,
      int redactionCount) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(classification, "classification");
    if (byteSize < 0L) {
      throw new IllegalArgumentException("byteSize must not be negative");
    }
    if (redactionCount < 0) {
      throw new IllegalArgumentException("redactionCount must not be negative");
    }
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    entity.setRawOutputReference(reference);
    entity.setRawOutputClassification(classification.value());
    entity.setRawOutputByteSize(byteSize);
    entity.setRedactionCount(redactionCount);
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "persisting runner raw-output reference runnerExecutionId={} classification={} byteSize={} redactionCount={}",
        publicId,
        classification.value(),
        byteSize,
        redactionCount);
    return mapper.toSnapshot(saved);
  }

  private RunnerExecutionSnapshot mutateWithGuard(
      String publicId,
      RunnerExecutionStatus targetStatus,
      java.util.function.Consumer<RunnerExecutionEntity> mutator) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    RunnerExecutionStatus current = entity.getStatus();
    RunnerExecutionStateMachine.assertCanTransition(publicId, current, targetStatus);
    mutator.accept(entity);
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "transition runnerExecutionId={} from={} to={}",
        publicId,
        current.value(),
        targetStatus.value());
    return mapper.toSnapshot(saved);
  }

  private static void ensureTimeoutAfterActivity(
      RunnerExecutionEntity entity, OffsetDateTime activity, Duration staleTimeoutWindow) {
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
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunPublicId, details);
  }
}
