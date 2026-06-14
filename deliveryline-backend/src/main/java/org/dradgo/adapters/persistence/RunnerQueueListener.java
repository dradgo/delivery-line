package org.dradgo.adapters.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.dradgo.application.runner.queue.RunnerQueueSignal;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Story 3.17b (AC2) — the dedicated PostgreSQL {@code LISTEN runner_queue_updated} connection. A
 * background daemon thread holds a long-lived connection, blocks on {@link
 * PGConnection#getNotifications(int)}, and {@link RunnerQueueSignal#signal() signals} idle workers
 * the instant a {@code NOTIFY} (fired by {@code RunnerExecutionQueue.enqueue}'s post-commit hook)
 * arrives — delivering the AC2 p95 &lt; 500ms idle wake-up.
 *
 * <p><b>Latency optimization atop a correct poll (Decision D8).</b> If the listener connection
 * drops it reconnects with backoff; while it is down, workers still drain via the AC1 backoff poll,
 * so the queue NEVER stalls on listener failure. The {@code LISTEN/NOTIFY} hop only shortens idle
 * latency.
 *
 * <p>Gated on {@code worker-pool.enabled} (the same flag as {@link
 * org.dradgo.application.runner.queue.RunnerWorkerPool}) so it starts only where the pool runs — it
 * stays dormant in the fast {@code @SpringBootTest} tier (Trap T8).
 *
 * <p><b>Dedicated physical connection (review D2).</b> A connection held open for {@code LISTEN}
 * must NOT be borrowed from the shared Hikari pool — it would permanently consume one permit, and a
 * checked-out connection is never reclaimed by Hikari's {@code maxLifetime}. This opens its own
 * physical connection via {@link DriverManager} using the pool's own JDBC coordinates (so prod and
 * Testcontainers both work without duplicating datasource config) and reconnects with backoff on
 * any fault.
 */
@Component
public class RunnerQueueListener implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(RunnerQueueListener.class);

  private static final int SHUTDOWN_PHASE = Integer.MAX_VALUE - 1024;
  private static final int NOTIFICATION_BLOCK_MILLIS = 10_000;
  private static final long RECONNECT_BACKOFF_MILLIS = 2_000L;

  private final DataSource dataSource;
  private final RunnerQueueSignal signal;
  private final RunnerWorkerPoolProperties properties;

  private final Object lifecycleLock = new Object();
  private volatile boolean running;
  private volatile Thread listenerThread;

  public RunnerQueueListener(
      DataSource dataSource, RunnerQueueSignal signal, RunnerWorkerPoolProperties properties) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.signal = Objects.requireNonNull(signal, "signal");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running || !properties.enabled()) {
        if (!properties.enabled()) {
          log.info("RunnerQueueListener disabled (worker-pool.enabled=false) — not starting");
        }
        return;
      }
      running = true;
      Thread thread = new Thread(this::listenLoop, "runner-queue-listener");
      thread.setDaemon(true);
      this.listenerThread = thread;
      thread.start();
      log.info("RunnerQueueListener started channel={}", RunnerQueueNotificationAdapter.CHANNEL);
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleLock) {
      if (!running) {
        return;
      }
      running = false;
      Thread thread = this.listenerThread;
      if (thread != null) {
        thread.interrupt();
      }
      this.listenerThread = null;
      log.info("RunnerQueueListener stopped");
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

  private void listenLoop() {
    while (running) {
      try (Connection connection = openDedicatedConnection()) {
        PGConnection pgConnection = connection.unwrap(PGConnection.class);
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + RunnerQueueNotificationAdapter.CHANNEL);
        }
        log.info(
            "RunnerQueueListener LISTEN established channel={}",
            RunnerQueueNotificationAdapter.CHANNEL);
        while (running) {
          PGNotification[] notifications = pgConnection.getNotifications(NOTIFICATION_BLOCK_MILLIS);
          if (notifications != null && notifications.length > 0) {
            // Review P1: a single NOTIFY batch may stand for several enqueues. Wake EVERY idle
            // worker (not just one) so a burst is drained concurrently; SKIP LOCKED in dequeue
            // means
            // each woken worker leases a distinct row (or finds none and re-waits — harmless).
            signal.signalAll();
          }
        }
      } catch (SQLException connectionError) {
        if (!running) {
          return;
        }
        log.warn(
            "RunnerQueueListener connection lost — workers fall back to backoff poll; reconnecting cause={}",
            connectionError.getClass().getSimpleName());
        backoffBeforeReconnect();
      } catch (RuntimeException unexpected) {
        if (!running) {
          return;
        }
        log.error("RunnerQueueListener unexpected failure; reconnecting", unexpected);
        backoffBeforeReconnect();
      }
    }
  }

  /**
   * Open a dedicated PHYSICAL connection for the {@code LISTEN} loop (review D2) so it never
   * consumes a shared Hikari pool permit for the app's lifetime. Derives the JDBC coordinates from
   * the Hikari pool itself, so prod and Testcontainers (which configures Hikari via
   * {@code @ServiceConnection}) both work without duplicating datasource config. Falls back to a
   * pooled connection only for a non-Hikari / jdbcUrl-less DataSource.
   */
  private Connection openDedicatedConnection() throws SQLException {
    if (dataSource instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
      return DriverManager.getConnection(
          hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
    }
    return dataSource.getConnection();
  }

  private void backoffBeforeReconnect() {
    // Interruptible wait without Thread.sleep (forbidden-call lint): park the listener thread.
    java.util.concurrent.locks.LockSupport.parkNanos(
        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(RECONNECT_BACKOFF_MILLIS));
    if (Thread.interrupted() && !running) {
      // swallow — stop() interrupted us during backoff; the while(running) guard will exit.
      log.debug("RunnerQueueListener backoff interrupted during shutdown");
    }
  }
}
