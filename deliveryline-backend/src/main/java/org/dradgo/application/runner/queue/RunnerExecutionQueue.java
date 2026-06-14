package org.dradgo.application.runner.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.queue.spi.RunnerQueueNotificationPort;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Story 3.17a (substrate) + 3.17b (activation) — the PostgreSQL-backed runner execution queue.
 * {@link #enqueue} writes a lightweight {@code queued} {@code runner_executions} row + a {@code
 * runner.queued} event under a backpressure cap and fires a post-commit {@code NOTIFY
 * runner_queue_updated} so an idle worker wakes within the AC2 latency budget; {@link #dequeue}
 * leases the next row with {@code FOR UPDATE SKIP LOCKED} so concurrent workers never collide.
 *
 * <p><b>Activated in 3.17b (Decision D1).</b> The 7 dispatch callers now {@code enqueue} instead of
 * calling {@code RunnerBroker.dispatch} synchronously; the {@link
 * org.dradgo.application.runner.queue.RunnerWorkerPool} dequeues these rows and runs the relocated
 * dispatch body on a bounded thread pool.
 *
 * <p><b>Dispatch carriage persisted at enqueue (V14).</b> The bundle is still composed at dispatch
 * on the worker (Decision D2), not here. But the worker needs the {@code idempotencyKey} + the
 * originating {@link ActorContext} (identity/type/correlationId) to reconstruct the idempotency
 * reservation + the dispatch events when it runs that body off a dequeued row — V12's {@code
 * correlation_id} alone cannot rebuild a non-system actor (e.g. {@code RecoveryService.retry}). So
 * enqueue persists all three onto the row; {@code dequeue} reads them back via the snapshot.
 *
 * <p>Lives in {@code application.runner.queue} (Application layer): it depends only on {@code
 * domain} + the {@code application.runner[.queue].spi} ports, never on {@code adapters}/{@code
 * infrastructure} (Trap T11). Annotated {@code @Component} (not {@code @Service}) — like {@code
 * RunnerBroker} — because its name lacks the {@code *Service} suffix the naming ArchUnit rule pins.
 */
@Component
public class RunnerExecutionQueue {

  private static final Logger log = LoggerFactory.getLogger(RunnerExecutionQueue.class);

  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionEventPort eventPort;
  private final RunnerProperties runnerProperties;
  private final RunnerQueueNotificationPort notificationPort;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RunnerExecutionQueue(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerProperties runnerProperties,
      RunnerQueueNotificationPort notificationPort) {
    this(recordPort, eventPort, runnerProperties, notificationPort, Clock.systemUTC());
  }

  // Package-private clock-injecting overload for deterministic tests (no Clock bean is published in
  // the context — the production path defaults to Clock.systemUTC(), mirroring the persistence
  // adapter). Only the @Autowired constructor above is public, so there is no binding ambiguity.
  RunnerExecutionQueue(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerProperties runnerProperties,
      RunnerQueueNotificationPort notificationPort,
      Clock clock) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.eventPort = Objects.requireNonNull(eventPort, "eventPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.notificationPort = Objects.requireNonNull(notificationPort, "notificationPort");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Story 3.17a (AC2/AC4/AC5) + 3.17b — place a new {@code queued} row in the queue. Counts current
   * queued rows under the write transaction and raises {@link DomainErrorCode#RUNNER_QUEUE_FULL}
   * (writing NO row) when already at {@code deliveryline.runner.queue-max-depth}; otherwise mints a
   * {@code rex_…} id, persists the row (with the dispatch carriage — {@code idempotencyKey} + the
   * {@code actor} identity/type + its {@code correlationId} + {@code queuePriority}), appends a
   * {@code runner.queued} event, and registers a post-commit {@code NOTIFY} so an idle worker
   * wakes.
   *
   * @param idempotencyKey the dispatch idempotency key; the worker reserves it at dispatch time
   *     (preserving today's replay semantics). Must be non-blank.
   * @param actor the originating actor; its identity/type/correlationId are persisted so the worker
   *     can rebuild the {@link ActorContext} the dispatch events + idempotency reservation use.
   * @param queuePriority dequeue ordering key (lower = sooner); must be {@code >= 0}
   */
  @Transactional
  public QueuedRunnerExecution enqueue(
      String workflowRunId,
      RunnerStage stage,
      String idempotencyKey,
      ActorContext actor,
      int queuePriority) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(actor, "actor");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    // queuePriority IS the live dequeue ORDER BY key — a negative value would silently front-run
    // every default-100 row.
    if (queuePriority < 0) {
      throw new IllegalArgumentException("queuePriority must be >= 0");
    }
    String correlationId = actor.correlationId();

    String priorWorkflowRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorCorrelation = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
    try {
      int maxDepth = runnerProperties.queueMaxDepth();
      long currentDepth = recordPort.countQueued();
      if (currentDepth >= maxDepth) {
        log.warn(
            "enqueue rejected runner queue full workflowRunId={} stage={} currentDepth={} maxDepth={}",
            workflowRunId,
            stage.value(),
            currentDepth,
            maxDepth);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentDepth", currentDepth);
        details.put("maxDepth", maxDepth);
        throw new DomainException(
            DomainErrorCode.RUNNER_QUEUE_FULL,
            "Runner queue is full: " + currentDepth + " >= " + maxDepth,
            details);
      }

      String runnerExecutionId = PublicIdPrefixes.RUNNER_EXECUTION.next();
      int contextBundleVersion = recordPort.nextContextBundleVersion(workflowRunId, stage);
      Duration timeout = runnerProperties.timeoutFor(stage);
      RunnerExecutionSnapshot snapshot =
          recordPort.insertQueued(
              runnerExecutionId,
              workflowRunId,
              stage,
              contextBundleVersion,
              timeout,
              queuePriority,
              correlationId,
              idempotencyKey,
              actor.actorIdentity(),
              actor.actorType().value());
      long resultingDepth = currentDepth + 1;
      String queuedEventId =
          appendQueuedEvent(workflowRunId, runnerExecutionId, stage, queuePriority, correlationId);
      registerQueueNotifyAfterCommit(runnerExecutionId);
      log.info(
          "enqueue runnerExecutionId={} workflowRunId={} stage={} queuePriority={} currentDepth={} maxDepth={}",
          runnerExecutionId,
          workflowRunId,
          stage.value(),
          queuePriority,
          resultingDepth,
          maxDepth);
      return new QueuedRunnerExecution(
          snapshot.publicId(),
          workflowRunId,
          stage,
          queuePriority,
          correlationId,
          resultingDepth,
          queuedEventId);
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelation);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorWorkflowRun);
    }
  }

  /**
   * Story 3.17a (AC2) — lease the next queued row to {@code workerId} via {@code FOR UPDATE SKIP
   * LOCKED}. Returns the leased (now {@code running}) row, or empty when the queue is drained.
   */
  @Transactional
  public Optional<RunnerExecutionSnapshot> dequeue(String workerId) {
    requireNonBlank(workerId, "workerId");
    String safeWorkerId = MdcKeys.sanitizeForLog(workerId);
    Optional<RunnerExecutionSnapshot> leased = recordPort.dequeueNext(workerId);
    leased.ifPresentOrElse(
        snapshot ->
            log.info(
                "dequeue leased runnerExecutionId={} workflowRunId={} workerId={} queuePriority={}",
                snapshot.publicId(),
                snapshot.workflowRunPublicId(),
                safeWorkerId,
                snapshot.queuePriority()),
        () -> log.info("dequeue found no queued row workerId={}", safeWorkerId));
    return leased;
  }

  /**
   * Story 3.17b (AC2) — fire {@code NOTIFY runner_queue_updated} AFTER the enqueue commits (the
   * precedent is {@code WorkflowTransitionService}'s completion-sync afterCommit hook), so the
   * {@code queued} row is durable before any worker wakes. Best-effort (Decision D8): a notify
   * failure is swallowed with a WARN — workers still drain via the AC1 backoff poll. Degrades
   * silently when no synchronization is active (e.g. a programmatic call outside a managed tx).
   */
  private void registerQueueNotifyAfterCommit(String runnerExecutionId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      notifyQueuedBestEffort(runnerExecutionId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            notifyQueuedBestEffort(runnerExecutionId);
          }
        });
  }

  private void notifyQueuedBestEffort(String runnerExecutionId) {
    try {
      notificationPort.notifyQueued();
      log.debug("enqueue NOTIFY runner_queue_updated runnerExecutionId={}", runnerExecutionId);
    } catch (RuntimeException notifyFailure) {
      log.warn(
          "enqueue NOTIFY failed (workers fall back to backoff poll) runnerExecutionId={} cause={}",
          runnerExecutionId,
          notifyFailure.getClass().getSimpleName());
    }
  }

  private String appendQueuedEvent(
      String workflowRunId,
      String runnerExecutionId,
      RunnerStage stage,
      int queuePriority,
      String correlationId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("stage", stage.value());
    details.put("queuePriority", queuePriority);
    // The originating correlationId is persisted on the row (AC5); never log/emit secrets or bundle
    // bytes. ActorContext carries it for MDC continuity only.
    ActorContext actor =
        new ActorContext("system", ActorType.SYSTEM, MdcKeys.sanitizeForLog(correlationId));
    return eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_QUEUED,
        actor,
        "runner_queued",
        null,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank");
    }
  }
}
