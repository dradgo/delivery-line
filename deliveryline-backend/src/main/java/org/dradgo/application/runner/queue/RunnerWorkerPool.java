package org.dradgo.application.runner.queue;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Story 3.17b (AC1/AC6/AC10) — the bounded worker pool that drains the {@link
 * RunnerExecutionQueue}. A Spring {@link ThreadPoolTaskExecutor} of {@code worker-pool.size}
 * threads (core = max) each runs the loop {@code dequeue → if work then
 * RunnerBroker.executeQueuedDispatch → repeat}; an idle worker sleeps on the {@link
 * RunnerQueueSignal} (woken by the LISTEN/NOTIFY listener) bounded by an exponential backoff
 * (initial 1s → max 10s, reset on work) so liveness never depends on the notification (Decision
 * D8).
 *
 * <p>Realizes the 3.17a ADR 0006 decision: ONE shared pool, no per-stage pools (AC6).
 *
 * <p><b>Graceful shutdown (AC10).</b> Implements {@link SmartLifecycle}; on {@code stop} it clears
 * the running flag, wakes every idle worker, and waits up to the drain timeout (aligned with {@code
 * spring.lifecycle.timeout-per-shutdown-phase}) for any in-flight dispatch to finish before the
 * executor is shut down. Queued rows are untouched (they stay {@code queued} for the next restart);
 * in-flight {@code running} rows continue under the existing recover-on-startup / stale-scan paths
 * — containers are NOT cancelled (that is recovery's job, story 3.2/E4).
 *
 * <p>Off by default in tests ({@code worker-pool.enabled=false}, Trap T8) so an always-on pool
 * never dequeues during unrelated {@code @SpringBootTest}s. Lives in {@code
 * application.runner.queue}; it reaches the runner only through {@code RunnerBroker} + the queue,
 * never an adapter (Trap T11).
 */
@Component
public class RunnerWorkerPool implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(RunnerWorkerPool.class);

  // A high phase so the pool stops EARLY in shutdown — before the DataSource/persistence beans the
  // workers depend on are torn down.
  private static final int SHUTDOWN_PHASE = Integer.MAX_VALUE - 1024;
  private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

  private final RunnerExecutionQueue queue;
  private final RunnerBroker broker;
  private final RunnerQueueSignal signal;
  private final RunnerWorkerPoolProperties properties;

  // Review P3: the total shutdown-drain budget is bound from spring.lifecycle's
  // timeout-per-shutdown-phase (the same window Spring grants this lifecycle phase) instead of a
  // hard-coded constant that could silently drift from it. The in-flight drain AND the executor
  // termination share this ONE deadline, so they are not additive.
  private final Duration drainTimeout;

  private final ReentrantLock drainLock = new ReentrantLock();
  private final Condition allDrained = drainLock.newCondition();
  private int inFlight; // guarded by drainLock

  private final Object lifecycleLock = new Object();
  private volatile boolean running;
  private volatile ThreadPoolTaskExecutor executor;
  private String poolInstanceId = "";

  public RunnerWorkerPool(
      RunnerExecutionQueue queue,
      RunnerBroker broker,
      RunnerQueueSignal signal,
      RunnerWorkerPoolProperties properties,
      @Value("${spring.lifecycle.timeout-per-shutdown-phase:60s}") Duration drainTimeout) {
    this.queue = queue;
    this.broker = broker;
    this.signal = signal;
    this.properties = properties;
    this.drainTimeout =
        drainTimeout == null || drainTimeout.isNegative() || drainTimeout.isZero()
            ? DEFAULT_DRAIN_TIMEOUT
            : drainTimeout;
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running) {
        return;
      }
      if (!properties.enabled()) {
        log.info("RunnerWorkerPool disabled (worker-pool.enabled=false) — not starting");
        return;
      }
      int size = properties.size();
      poolInstanceId = UUID.randomUUID().toString().substring(0, 8);
      ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
      pool.setCorePoolSize(size);
      pool.setMaxPoolSize(size);
      pool.setQueueCapacity(0);
      pool.setThreadNamePrefix("runner-worker-");
      pool.setAllowCoreThreadTimeOut(false);
      pool.initialize();
      this.executor = pool;
      this.running = true;
      for (int i = 0; i < size; i++) {
        String workerId = "runner-worker-" + poolInstanceId + "-" + i;
        pool.execute(() -> runWorker(workerId));
      }
      log.info(
          "RunnerWorkerPool started size={} backoffInitial={} backoffMax={} poolInstanceId={}",
          size,
          properties.backoff().initial(),
          properties.backoff().max(),
          poolInstanceId);
    }
  }

  @Override
  public void stop() {
    stop(() -> {});
  }

  @Override
  public void stop(Runnable callback) {
    synchronized (lifecycleLock) {
      if (!running) {
        callback.run();
        return;
      }
      log.info(
          "RunnerWorkerPool drain-begin inFlight={} drainTimeout={}",
          currentInFlight(),
          drainTimeout);
      // Review P3: one shared deadline for the in-flight drain AND the executor termination, so the
      // two waits are bounded by a single budget (not additive) aligned with Spring's lifecycle
      // phase timeout.
      long deadlineNanos = System.nanoTime() + drainTimeout.toNanos();
      running = false;
      // Wake every idle worker so it observes the cleared running flag and exits its await loop.
      signal.signalAll();
      drainInFlight(deadlineNanos);
      ThreadPoolTaskExecutor pool = this.executor;
      if (pool != null) {
        // Workers have exited their loops (running=false + drained); shut the executor threads down
        // within whatever remains of the shared deadline.
        long remainingMillis = Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        pool.setAwaitTerminationMillis(remainingMillis);
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.shutdown();
        this.executor = null;
      }
      log.info("RunnerWorkerPool drain-complete");
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isAutoStartup() {
    return properties.enabled();
  }

  @Override
  public int getPhase() {
    return SHUTDOWN_PHASE;
  }

  private void runWorker(String workerId) {
    String priorWorkerMdc = MdcKeys.beginScope(MdcKeys.WORKER_ID, workerId);
    Duration backoff = properties.backoff().initial();
    Duration maxBackoff = properties.backoff().max();
    String safeWorkerId = MdcKeys.sanitizeForLog(workerId);
    log.info("worker loop start workerId={}", safeWorkerId);
    try {
      while (running) {
        boolean didWork = false;
        try {
          Optional<RunnerExecutionSnapshot> leased = queue.dequeue(workerId);
          if (leased.isPresent()) {
            didWork = true;
            beginDispatch();
            try {
              broker.executeQueuedDispatch(leased.get());
            } finally {
              endDispatch();
            }
          }
        } catch (RuntimeException loopError) {
          // A dispatch / dequeue failure must not kill the worker (that would shrink pool
          // capacity).
          // The leased row, if any, is reclaimed by the story-3.2 stale scan (AC5). Back off (via
          // the
          // interruptible signal wait, never Thread.sleep) so a persistent fault does not hot-loop.
          log.error(
              "worker loop iteration failed workerId={} cause={}",
              safeWorkerId,
              loopError.toString());
          if (running) {
            signal.awaitSignal(backoff);
            backoff = nextBackoff(backoff, maxBackoff);
          }
          continue;
        }
        if (didWork) {
          backoff = properties.backoff().initial();
        } else if (running) {
          // Idle: wait for a NOTIFY-driven signal up to the current backoff, then grow it.
          signal.awaitSignal(backoff);
          backoff = nextBackoff(backoff, maxBackoff);
        }
      }
    } finally {
      log.info("worker loop exit workerId={}", safeWorkerId);
      MdcKeys.endScope(MdcKeys.WORKER_ID, priorWorkerMdc);
    }
  }

  private void beginDispatch() {
    drainLock.lock();
    try {
      inFlight++;
    } finally {
      drainLock.unlock();
    }
  }

  private void endDispatch() {
    drainLock.lock();
    try {
      inFlight--;
      if (inFlight <= 0) {
        allDrained.signalAll();
      }
    } finally {
      drainLock.unlock();
    }
  }

  private int currentInFlight() {
    drainLock.lock();
    try {
      return inFlight;
    } finally {
      drainLock.unlock();
    }
  }

  /**
   * Block until {@code deadlineNanos} (a {@link System#nanoTime} instant) for every in-flight
   * dispatch to finish. In-flight dispatches are SHORT (compose bundle + adapter ack), so this
   * normally returns near-instantly; the bound guards against a wedged dispatch and shares the
   * shutdown budget with the subsequent executor termination (review P3).
   */
  private void drainInFlight(long deadlineNanos) {
    drainLock.lock();
    try {
      long remainingNanos = deadlineNanos - System.nanoTime();
      while (inFlight > 0 && remainingNanos > 0L) {
        remainingNanos = allDrained.awaitNanos(remainingNanos);
      }
      if (inFlight > 0) {
        log.warn(
            "RunnerWorkerPool drain timed out with inFlight={} (in-flight rows recover via stale scan)",
            inFlight);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      drainLock.unlock();
    }
  }

  private static Duration nextBackoff(Duration current, Duration max) {
    Duration doubled = current.multipliedBy(2);
    return doubled.compareTo(max) > 0 ? max : doubled;
  }
}
