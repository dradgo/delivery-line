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

/**
 * Story 3.17a — the PostgreSQL-backed runner execution queue SUBSTRATE (part A of the epic-3.17
 * split). {@link #enqueue} writes a lightweight {@code queued} {@code runner_executions} row + a
 * {@code runner.queued} event under a backpressure cap; {@link #dequeue} leases the next row with
 * {@code FOR UPDATE SKIP LOCKED} so concurrent workers never collide.
 *
 * <p><b>Dormant by design (Decision D0).</b> 3.17a builds and fully tests the queue in isolation —
 * <em>nothing in production enqueues</em>. The 7 dispatch callers stay on the synchronous {@code
 * RunnerBroker.dispatch} path; story 3.17b refactors them onto this queue and adds the worker pool
 * ({@code ThreadPoolTaskExecutor} + {@code LISTEN/NOTIFY}). This is the house
 * build-the-seam-before-the-call-site pattern (3.15/3.16).
 *
 * <p><b>Bundle composed at dispatch, not enqueue (Decision D2).</b> {@code enqueue} writes only a
 * cheap queued row; the {@code contextBundleRef} is accepted for the 3.17b worker but NOT composed
 * here (repo clone/summarize belongs on the bounded worker thread). The {@code idempotencyKey} is
 * accepted but NOT reserved in 3.17a (Open Question OQ-3 — reservation stays at the dispatch body
 * the 3.17b worker runs, preserving today's replay semantics).
 *
 * <p>Lives in {@code application.runner.queue} (Application layer): it depends only on {@code
 * domain} + the {@code application.runner.spi} ports, never on {@code adapters}/{@code
 * infrastructure} (Trap T11).
 *
 * <p>Annotated {@code @Component} (not {@code @Service}) — like {@code RunnerBroker} — because its
 * name lacks the {@code *Service}/{@code *Orchestrator} suffix the application-service naming
 * ArchUnit rule pins on {@code @Service} beans.
 */
@Component
public class RunnerExecutionQueue {

  private static final Logger log = LoggerFactory.getLogger(RunnerExecutionQueue.class);

  private final RunnerExecutionRecordPort recordPort;
  private final RunnerExecutionEventPort eventPort;
  private final RunnerProperties runnerProperties;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public RunnerExecutionQueue(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerProperties runnerProperties) {
    this(recordPort, eventPort, runnerProperties, Clock.systemUTC());
  }

  // Package-private clock-injecting overload for deterministic tests (no Clock bean is published in
  // the context — the production path defaults to Clock.systemUTC(), mirroring the persistence
  // adapter). Only the @Autowired constructor above is public, so there is no binding ambiguity.
  RunnerExecutionQueue(
      RunnerExecutionRecordPort recordPort,
      RunnerExecutionEventPort eventPort,
      RunnerProperties runnerProperties,
      Clock clock) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.eventPort = Objects.requireNonNull(eventPort, "eventPort");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Story 3.17a (AC2/AC4/AC5) — place a new {@code queued} row in the queue. Counts current queued
   * rows under the write transaction and raises {@link DomainErrorCode#RUNNER_QUEUE_FULL} (writing
   * NO row) when already at {@code deliveryline.runner.queue-max-depth}; otherwise mints a {@code
   * rex_…} id, persists the row (with {@code correlationId} + {@code queuePriority}), and appends a
   * {@code runner.queued} event.
   *
   * @param contextBundleRef forward-compat for the 3.17b worker; NOT composed/persisted here (D2) —
   *     nullable (3.17b composes the bundle at dispatch and may enqueue before a ref exists)
   * @param idempotencyKey forward-compat; NOT reserved here (OQ-3) — nullable
   * @param correlationId originating story-1.19 correlationId, persisted on the row (AC5); nullable
   * @param queuePriority dequeue ordering key (lower = sooner); must be {@code >= 0}
   */
  @Transactional
  public QueuedRunnerExecution enqueue(
      String workflowRunId,
      RunnerStage stage,
      String contextBundleRef,
      String idempotencyKey,
      String correlationId,
      int queuePriority) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    // contextBundleRef + idempotencyKey are forward-compat inputs for the 3.17b worker (D2/OQ-3):
    // accepted but unused in 3.17a, so they are nullable here. queuePriority IS the live dequeue
    // ORDER BY key, so it is the validated input — a negative value would silently front-run every
    // default-100 row.
    if (queuePriority < 0) {
      throw new IllegalArgumentException("queuePriority must be >= 0");
    }

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
              correlationId);
      long resultingDepth = currentDepth + 1;
      String queuedEventId =
          appendQueuedEvent(workflowRunId, runnerExecutionId, stage, queuePriority, correlationId);
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
