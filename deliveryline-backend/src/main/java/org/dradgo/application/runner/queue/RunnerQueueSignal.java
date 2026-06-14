package org.dradgo.application.runner.queue;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Story 3.17b (AC2) — the in-JVM wake-up latch idle workers block on between dequeues. It is the
 * single rendezvous point between the {@code LISTEN runner_queue_updated} listener (which {@link
 * #signal signals} on every {@code NOTIFY} delivered by Postgres) and the worker pool (whose idle
 * workers {@link #awaitSignal await} it, bounded by the AC1 backoff so a missed/edge-lost
 * notification never stalls liveness — Decision D8).
 *
 * <p>Edge-triggered with a one-permit memory: a {@code signal()} that arrives while no worker is
 * waiting still releases the very next {@code awaitSignal(...)} (the {@code pending} flag), so the
 * enqueue→NOTIFY→listener→signal hop cannot race ahead of a worker that is mid-dispatch and about
 * to loop back to wait. Lives in {@code application.runner.queue}: it depends only on the JDK +
 * Spring stereotype, never on adapters/infrastructure (Trap T11).
 */
@Component
public class RunnerQueueSignal {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition workAvailable = lock.newCondition();
  private boolean pending;

  /**
   * Wake one waiting worker (or arm the next {@link #awaitSignal} if none is waiting). Called from
   * the queue listener's notification thread and is safe to call from any thread.
   */
  public void signal() {
    lock.lock();
    try {
      pending = true;
      workAvailable.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Wake EVERY waiting worker. Used by the worker pool's graceful-shutdown drain to release all
   * idle workers at once so they observe the cleared running flag and exit promptly.
   */
  public void signalAll() {
    lock.lock();
    try {
      pending = true;
      workAvailable.signalAll();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Block up to {@code maxWait} for a {@link #signal}, returning {@code true} if one arrived (or
   * was already pending) and {@code false} on timeout. Consumes the pending permit. Interruption
   * returns {@code true} so the worker re-checks the queue + its running flag promptly (shutdown
   * path).
   */
  public boolean awaitSignal(Duration maxWait) {
    lock.lock();
    try {
      if (pending) {
        pending = false;
        return true;
      }
      workAvailable.await(maxWait.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
      // Review P2: re-check `pending` rather than trusting await's boolean. A signal() that landed
      // in the timeout/re-acquire window sets pending=true but its Condition.signal() reaches no
      // waiter; consuming it here (instead of unconditionally clearing) prevents a lost wake-up.
      if (pending) {
        pending = false;
        return true;
      }
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return true;
    } finally {
      lock.unlock();
    }
  }
}
