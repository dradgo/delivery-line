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
import org.dradgo.application.runner.spi.RunnerExecutionCancellation;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort.ReviewedArtifactPin;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerTakeoverCancellation;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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

  // Story 3.19 (AC1/AC3/AC9) — one-pass scalar aggregate of the queue state. Postgres FILTER
  // clauses keep every count on a single consistent snapshot of runner_executions; stale cutoffs +
  // the oldest-queued age are computed server-side (now()), no JVM clock. The :batchId param is
  // cast to text so a NULL bind keeps its type for the `is null` short-circuit (3.18's
  // batch_submission_id is a native-only text column with no entity field, so this MUST be native
  // SQL — JPQL cannot see it).
  private static final String QUEUE_COUNTS_SQL =
      """
      select
        count(*) filter (where status = 'queued') as queue_depth,
        count(*) filter (where status = 'running' and worker_id is not null) as active_workers,
        count(*) filter (
          where status = 'queued'
            and created_at < now() - make_interval(secs => :staleSecs)) as stale_queued,
        count(*) filter (
          where status = 'running' and worker_id is not null
            and dispatched_at < now() - make_interval(secs => :staleSecs)) as stale_dispatched,
        count(*) filter (
          where status = 'completed'
            and completed_at >= now() - make_interval(secs => :throughputSecs)) as recent_throughput,
        min(created_at) filter (where status = 'queued') as oldest_queued_at,
        coalesce(
          floor(extract(epoch from (now() - min(created_at) filter (where status = 'queued')))),
          0)::bigint as oldest_queued_age_seconds
      from runner_executions
      where (:batchId::text is null or batch_submission_id = :batchId::text)
      """;

  // Story 3.19 (AC1) — leased running rows backing the per-worker WorkerStatus list. Returns the
  // public ids ordered oldest-lease-first; the adapter re-reads each via JPA (mirroring the dequeue
  // RETURNING-then-reread pattern) so the full snapshot incl. the workflow_run public id is mapped
  // through the lazy association inside the readOnly transaction.
  private static final String LEASED_RUNNING_IDS_SQL =
      """
      select public_id
        from runner_executions
       where status = 'running' and worker_id is not null
         and (:batchId::text is null or batch_submission_id = :batchId::text)
       order by dispatched_at asc, id asc
       limit :limit
      """;

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
  @Transactional(readOnly = true)
  public Optional<RunnerExecutionSnapshot> findLatestByWorkflowRunPublicIdAndStage(
      String workflowRunPublicId, RunnerStage stage) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    return runnerExecutionRepository
        .findFirstByWorkflowRunPublicIdAndStageOrderByContextBundleVersionDesc(
            workflowRunPublicId, stage.value())
        .map(mapper::toSnapshot);
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
            .findByPublicIdForUpdate(workflowRunPublicId)
            .orElseThrow(() -> runNotFound(workflowRunPublicId));
    WorkflowState runState = workflowRun.getCurrentState();
    if (runState == WorkflowState.COMPLETED
        || runState == WorkflowState.TAKEN_OVER
        || runState == WorkflowState.RECONCILED) {
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Cannot enqueue runner execution for terminal workflow run",
          Map.of("runId", workflowRunPublicId, "currentState", runState.value()));
    }

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

  // Story 3d-3 (AC5) — insert an awaiting_manual row for a step parked under the `manual` runner
  // kind. Mirrors insertPending's terminal-state guard + run lock, but sets the NON-TERMINAL
  // awaiting_manual status with NO container/lease/worker/queue columns and completed_at null. The
  // timeout_at sentinel == last_activity_at (a parked run has no real timeout — ADR 0024); it only
  // satisfies ck_runner_executions_timeout_after_activity and keeps the row invisible to the
  // lease-reclamation scans (which key on worker_id/dispatched_at, both null here). Relies on the
  // caller's transaction (ManualExecutionDispatcher.park is @Transactional / runs in the
  // submit/approve tx).
  @Override
  public RunnerExecutionSnapshot insertAwaitingManual(
      String publicId, String workflowRunPublicId, RunnerStage stage, int contextBundleVersion) {
    return insertAwaitingManual(publicId, workflowRunPublicId, stage, contextBundleVersion, null);
  }

  @Override
  public RunnerExecutionSnapshot insertAwaitingManual(
      String publicId,
      String workflowRunPublicId,
      RunnerStage stage,
      int contextBundleVersion,
      String idempotencyKey) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    if (contextBundleVersion <= 0) {
      throw new IllegalArgumentException("contextBundleVersion must be positive");
    }
    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicIdForUpdate(workflowRunPublicId)
            .orElseThrow(() -> runNotFound(workflowRunPublicId));
    requireManualParkable(workflowRunPublicId, workflowRun);

    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    RunnerExecutionEntity entity = new RunnerExecutionEntity();
    entity.setPublicId(publicId);
    entity.setWorkflowRun(workflowRun);
    entity.setStage(stage);
    entity.setStatus(RunnerExecutionStatus.AWAITING_MANUAL);
    entity.setContextBundleVersion(contextBundleVersion);
    entity.setLastActivityAt(now);
    // Parked rows have no real timeout (ADR 0024). Sentinel == last_activity_at satisfies the
    // ck_runner_executions_timeout_after_activity (>=) CHECK without implying a timeout.
    entity.setTimeoutAt(now);
    entity.setFailureCategory(null);
    entity.setCompletedAt(null);
    entity.setIdempotencyKey(idempotencyKey);
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "insertAwaitingManual publicId={} workflowRunId={} stage={} contextBundleVersion={}",
        publicId,
        workflowRunPublicId,
        stage.value(),
        contextBundleVersion);
    return mapper.toSnapshot(saved);
  }

  @Override
  public RunnerExecutionSnapshot insertAwaitingManualAllocated(
      String publicId, String workflowRunPublicId, RunnerStage stage, String idempotencyKey) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicIdForUpdate(workflowRunPublicId)
            .orElseThrow(() -> runNotFound(workflowRunPublicId));
    requireManualParkable(workflowRunPublicId, workflowRun);
    int contextBundleVersion = nextContextBundleVersion(workflowRunPublicId, stage);
    return insertAwaitingManual(
        publicId, workflowRunPublicId, stage, contextBundleVersion, idempotencyKey);
  }

  private static void requireManualParkable(
      String workflowRunPublicId, WorkflowRunEntity workflowRun) {
    WorkflowState runState = workflowRun.getCurrentState();
    if (runState != WorkflowState.INVESTIGATING && runState != WorkflowState.EXECUTING) {
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Cannot record manual runner execution from current workflow state",
          Map.of("runId", workflowRunPublicId, "currentState", runState.value()));
    }
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
      String correlationId,
      String idempotencyKey,
      String actorIdentity,
      String actorType) {
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
            .findByPublicIdForUpdate(workflowRunPublicId)
            .orElseThrow(() -> runNotFound(workflowRunPublicId));
    WorkflowState runState = workflowRun.getCurrentState();
    if (runState == WorkflowState.COMPLETED
        || runState == WorkflowState.TAKEN_OVER
        || runState == WorkflowState.RECONCILED) {
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Cannot enqueue runner execution for terminal workflow run",
          Map.of("runId", workflowRunPublicId, "currentState", runState.value()));
    }

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
    entity.setIdempotencyKey(idempotencyKey);
    entity.setActorIdentity(actorIdentity);
    entity.setActorType(actorType);
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

  // Story 3.19 (AC1/AC3/AC9) — one-pass scalar aggregate. readOnly tx for consistency with the
  // sibling read methods; stale cutoffs computed server-side (now()) so the result is clock-drift
  // free (verify on real Postgres, not mocks — markescalationonce-boolean-returning-bug lesson).
  @Override
  @Transactional(readOnly = true)
  public org.dradgo.application.runner.spi.RunnerQueueCounts loadQueueCounts(
      Duration staleWindow, Duration throughputWindow, String batchIdOrNull) {
    Objects.requireNonNull(staleWindow, "staleWindow");
    Objects.requireNonNull(throughputWindow, "throughputWindow");
    if (batchIdOrNull != null) {
      PublicIdPrefixes.require(batchIdOrNull, PublicIdPrefixes.BATCH_SUBMISSION);
    }
    var params =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("staleSecs", staleWindow.toMillis() / 1000.0d)
            .addValue("throughputSecs", throughputWindow.toMillis() / 1000.0d)
            .addValue("batchId", batchIdOrNull);
    org.dradgo.application.runner.spi.RunnerQueueCounts counts =
        jdbcTemplate.queryForObject(
            QUEUE_COUNTS_SQL,
            params,
            (rs, rowNum) ->
                new org.dradgo.application.runner.spi.RunnerQueueCounts(
                    rs.getLong("queue_depth"),
                    rs.getLong("active_workers"),
                    rs.getLong("stale_queued"),
                    rs.getLong("stale_dispatched"),
                    rs.getLong("recent_throughput"),
                    rs.getObject("oldest_queued_at", OffsetDateTime.class),
                    rs.getLong("oldest_queued_age_seconds")));
    log.debug(
        "loadQueueCounts batchId={} queueDepth={} activeWorkers={} staleQueued={} staleDispatched={}",
        batchIdOrNull,
        counts == null ? null : counts.queueDepth(),
        counts == null ? null : counts.activeWorkers(),
        counts == null ? null : counts.staleQueuedCount(),
        counts == null ? null : counts.staleDispatchedCount());
    return counts == null ? org.dradgo.application.runner.spi.RunnerQueueCounts.empty() : counts;
  }

  // Story 3.19 (AC1) — leased running rows for the per-worker view. Native id-select (with the
  // optional batch filter) then JPA re-read preserving order, mirroring the dequeue
  // RETURNING-then-reread pattern so the lazy workflow_run association is mapped inside this
  // readOnly transaction.
  @Override
  @Transactional(readOnly = true)
  public List<RunnerExecutionSnapshot> findLeasedRunning(String batchIdOrNull, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    if (batchIdOrNull != null) {
      PublicIdPrefixes.require(batchIdOrNull, PublicIdPrefixes.BATCH_SUBMISSION);
    }
    var params =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("batchId", batchIdOrNull)
            .addValue("limit", limit);
    List<String> publicIds =
        jdbcTemplate.queryForList(LEASED_RUNNING_IDS_SQL, params, String.class);
    List<RunnerExecutionSnapshot> rows = new java.util.ArrayList<>(publicIds.size());
    for (String publicId : publicIds) {
      runnerExecutionRepository
          .findByPublicId(publicId)
          .map(mapper::toSnapshot)
          .ifPresent(rows::add);
    }
    log.debug("findLeasedRunning batchId={} leasedCount={}", batchIdOrNull, rows.size());
    return List.copyOf(rows);
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

  // Story 3.22 (AC5): guarded flip {queued|pending|running} → cancelled_for_takeover. No
  // failureCategory (this is an operator takeover, not a failure); stamps completed_at like the
  // other terminal mutators. Called inside the takeover service's REQUIRES_NEW transaction.
  @Override
  public Optional<RunnerTakeoverCancellation> markCancelledForTakeover(
      String publicId, OffsetDateTime cancelledAt) {
    return markCancelled(publicId, RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER, cancelledAt)
        .map(previousStatus -> new RunnerTakeoverCancellation(publicId, previousStatus));
  }

  // Story 4.8 (AC4): guarded flip {queued|pending|running} → cancelled_for_pause — the reversible
  // pause sibling of markCancelledForTakeover (shared body below). Called inside the pause
  // service's REQUIRES_NEW transaction.
  @Override
  public Optional<RunnerExecutionCancellation> markCancelledForPause(
      String publicId, OffsetDateTime cancelledAt) {
    return markCancelled(publicId, RunnerExecutionStatus.CANCELLED_FOR_PAUSE, cancelledAt)
        .map(previousStatus -> new RunnerExecutionCancellation(publicId, previousStatus));
  }

  // Shared cancellation body (stories 3.22 + 4.8): lock the row, idempotently skip an
  // already-terminal one (Optional.empty()), assert the state-machine edge, stamp the terminal
  // status + completed_at, clear failureCategory (an operator cancellation is not a failure) and
  // the heartbeat-stale marker. Returns the previous status for the caller's counts / post-commit
  // docker-stop routing.
  private Optional<RunnerExecutionStatus> markCancelled(
      String publicId, RunnerExecutionStatus targetStatus, OffsetDateTime cancelledAt) {
    Objects.requireNonNull(cancelledAt, "cancelledAt");
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    RunnerExecutionStatus previousStatus = entity.getStatus();
    if (RunnerExecutionStateMachine.isTerminal(previousStatus)) {
      return Optional.empty();
    }
    RunnerExecutionStateMachine.assertCanTransition(publicId, previousStatus, targetStatus);
    entity.setStatus(targetStatus);
    entity.setCompletedAt(cancelledAt.withOffsetSameInstant(ZoneOffset.UTC));
    entity.setFailureCategory(null);
    entity.setHeartbeatStaleEmittedAt(null);
    runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "transition runnerExecutionId={} from={} to={}",
        publicId,
        previousStatus.value(),
        targetStatus.value());
    return Optional.of(previousStatus);
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
  public List<RunnerExecutionSnapshot> findLeasedStaleByStageAndDispatchedAtBefore(
      RunnerStage stage, Duration leaseWindow, int limit) {
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(leaseWindow, "leaseWindow");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    OffsetDateTime cutoff =
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(leaseWindow);
    return runnerExecutionRepository
        .findLeasedStaleByStageAndDispatchedAtBefore(
            RunnerExecutionStatus.RUNNING.value(), stage.value(), cutoff, Limit.of(limit))
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
    // Story 4.8 (Reconciliation 4 / OQ-4): cancelled_for_pause is DELIBERATELY EXCLUDED from this
    // cleanup horizon even though it is terminal for the row. Pause is designed to be resumed —
    // enrolling the status here would let RunnerWorkspaceCleanupJob.sweepWorkspaces delete the
    // paused run's workspace out from under a later resume. cancelled_for_takeover is included
    // only because takeover is irreversible. Do NOT "helpfully" add cancelled_for_pause.
    List<String> rawStatuses =
        List.of(
            RunnerExecutionStatus.COMPLETED.value(),
            RunnerExecutionStatus.FAILED.value(),
            RunnerExecutionStatus.TIMED_OUT.value(),
            RunnerExecutionStatus.ORPHANED.value(),
            RunnerExecutionStatus.CANCELLED_FOR_TAKEOVER.value());
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
  // and must be tolerated.
  //
  // PROPAGATION_REQUIRES_NEW (2026-07-01 deadlock fix): this pessimistic FOR UPDATE lock MUST NOT
  // be
  // held in the caller's ambient transaction across the subsequent result harvest. DockerRunner
  // Adapter.poll() calls this (via captureRunnerLogs) INSIDE RunnerBroker.pollActiveExecutions's
  // per-item tx, then the poll harvests the result — and the REVIEW harvesters (SplitProposal
  // Harvester / ReviewResultHarvester) finalize the SAME runner_executions row in their own
  // REQUIRES_NEW tx (a second pooled connection). If capture joined the ambient tx (REQUIRED), that
  // ambient lock + the harvester's second-connection re-lock SELF-DEADLOCK on one scheduler thread
  // (no lock_timeout -> hangs forever, wedging every @Scheduled runner task). Committing this
  // metadata write in its own tx releases the row lock before the harvest. No caller holds this
  // row's lock BEFORE calling capture (poll/timeout/recovery all capture first, then lock), so the
  // new tx never self-conflicts.
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
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

  // Story 3g-3 (FR74) — persist per-execution token counts (V31 columns). METADATA-ONLY update
  // mirroring recordRawOutput: REQUIRES_NEW so the write commits in its own tx (releasing the row
  // lock) without joining the ambient/harvest tx, no state-machine guard (the row is terminal by
  // result-ingest time), tolerant of an already-terminal row. Each count is nullable and stored
  // exactly as passed (the broker already dropped absent/malformed values to null). Throws only
  // when the row is missing.
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RunnerExecutionSnapshot recordTokenUsage(
      String publicId, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    entity.setInputTokens(inputTokens);
    entity.setOutputTokens(outputTokens);
    entity.setTotalTokens(totalTokens);
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "persisting token usage runnerExecutionId={} input={} output={} total={}",
        publicId,
        inputTokens,
        outputTokens,
        totalTokens);
    return mapper.toSnapshot(saved);
  }

  // Story 3h-2 (AC6) — persist the lint findings jsonb (V34 column). METADATA-ONLY update mirroring
  // recordTokenUsage: REQUIRES_NEW so the write commits in its own tx (releasing the row lock)
  // without joining the ambient LINT-capture tx, no state-machine guard, tolerant of a
  // still-running
  // row (findings are written during capture before finalization). The payload is
  // already-serialized,
  // already-redacted JSON (ids/counts only — never raw secret bytes). Throws only when the row is
  // missing.
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RunnerExecutionSnapshot recordLintFindings(String publicId, String lintFindingsJson) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    entity.setLintFindings(lintFindingsJson);
    RunnerExecutionEntity saved = runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "persisting lint findings runnerExecutionId={} payloadPresent={}",
        publicId,
        lintFindingsJson != null);
    return mapper.toSnapshot(saved);
  }

  // Story 3d-2 (code-review D1) — pin the reviewed artifact onto a REVIEW execution at enqueue.
  // METADATA-ONLY (no state-machine guard, no status mutation): the pin is written on a `queued`
  // reviewer row right after enqueue. enqueue and pin run as SEPARATE transactions (the
  // orchestration calls them sequentially after the WaitingForReview transition has committed), so
  // this opens its OWN (REQUIRED) transaction — findByPublicIdForUpdate needs the active one. A
  // worker that leases the queued row before the pin commits simply finds no pin and the harvest
  // re-derives; the pin is best-effort (AC6).
  @Override
  @Transactional
  public void pinReviewedArtifact(
      String publicId,
      String reviewedArtifactPublicId,
      int reviewedArtifactVersion,
      String reviewedArtifactType) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    Objects.requireNonNull(reviewedArtifactPublicId, "reviewedArtifactPublicId");
    Objects.requireNonNull(reviewedArtifactType, "reviewedArtifactType");
    if (reviewedArtifactVersion <= 0) {
      throw new IllegalArgumentException("reviewedArtifactVersion must be positive");
    }
    RunnerExecutionEntity entity =
        runnerExecutionRepository
            .findByPublicIdForUpdate(publicId)
            .orElseThrow(() -> runnerExecutionNotFound(publicId));
    entity.setReviewedArtifactId(reviewedArtifactPublicId);
    entity.setReviewedArtifactVersion(reviewedArtifactVersion);
    entity.setReviewedArtifactType(reviewedArtifactType);
    runnerExecutionRepository.saveAndFlush(entity);
    log.info(
        "pinning reviewed artifact runnerExecutionId={} reviewedArtifactId={} version={} type={}",
        publicId,
        reviewedArtifactPublicId,
        reviewedArtifactVersion,
        reviewedArtifactType);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ReviewedArtifactPin> findReviewedArtifactPin(String publicId) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.RUNNER_EXECUTION);
    return runnerExecutionRepository
        .findByPublicId(publicId)
        .filter(
            entity ->
                entity.getReviewedArtifactId() != null
                    && entity.getReviewedArtifactVersion() != null
                    && entity.getReviewedArtifactType() != null)
        .map(
            entity ->
                new ReviewedArtifactPin(
                    entity.getReviewedArtifactId(),
                    entity.getReviewedArtifactVersion(),
                    entity.getReviewedArtifactType()));
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
