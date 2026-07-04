package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Story 3h-0 (AC1/AC7) — unit coverage of the two composable layers of {@link
 * AfterCommitSideEffectRunner} over a stub {@link PlatformTransactionManager}: Layer A
 * registration/guard/swallow semantics and Layer B {@code REQUIRES_NEW} run/swallow semantics, with
 * each log branch pinned. Real propagation + advisory-lock + replay behavior is proven by {@link
 * AfterCommitSideEffectRunnerIT} (real Postgres, Failsafe).
 */
class AfterCommitSideEffectRunnerTest {

  private static final String CONTEXT = "run_ctx_00000001";

  private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
  private final AfterCommitSideEffectRunner runner = new AfterCommitSideEffectRunner(txManager);

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  {
    // The REQUIRES_NEW TransactionTemplate needs a non-null status to commit; the body still runs
    // synchronously so the assertions below hold without a real transaction manager.
    when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
  }

  private Level priorLevel;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(AfterCommitSideEffectRunner.class);
    // Lower to DEBUG so the success ("side-effect completed") line reaches the appender — a
    // ListAppender only receives events at/above the logger's effective level.
    priorLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    logger.setLevel(priorLevel);
    appender.stop();
    // Defensive: never leak a manually-initialized synchronization into another test.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  // ---- Layer A: runAfterCommit ------------------------------------------------------------------

  @Test
  void runAfterCommitSkipsAndWarnsWhenNoSynchronizationActive() {
    AtomicInteger runs = new AtomicInteger();

    // No active synchronization (isSynchronizationActive() == false by default off any tx).
    runner.runAfterCommit("probe", CONTEXT, runs::incrementAndGet);

    assertThat(runs.get()).isZero();
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage()
                        .contains("probe not registered (no active transaction synchronization)")
                    && e.getFormattedMessage().contains("contextId=" + CONTEXT));
  }

  @Test
  void runAfterCommitRegistersAndFiresTheSideEffectOnceWithFiredLog() {
    AtomicInteger runs = new AtomicInteger();
    TransactionSynchronizationManager.initSynchronization();
    try {
      runner.runAfterCommit("probe", CONTEXT, runs::incrementAndGet);

      // Nothing runs until the (simulated) commit fires the registered afterCommit callbacks.
      assertThat(runs.get()).isZero();
      invokeRegisteredAfterCommits();

      assertThat(runs.get()).isEqualTo(1);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.INFO
                    && e.getFormattedMessage()
                        .contains("probe registered contextId=" + CONTEXT + " (afterCommit)"));
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.INFO
                    && e.getFormattedMessage()
                        .contains("probe fired contextId=" + CONTEXT + " (post-commit)"));
  }

  @Test
  void runAfterCommitSwallowsAndWarnsWhenSideEffectThrows() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      runner.runAfterCommit(
          "probe",
          CONTEXT,
          () -> {
            throw new IllegalStateException("boom");
          });

      // The already-committed transition must not be affected: firing afterCommit must NOT throw.
      assertThatCode(this::invokeRegisteredAfterCommits).doesNotThrowAnyException();
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage()
                        .contains("probe swallowed an error (completion intact)")
                    && e.getFormattedMessage().contains("cause=IllegalStateException"));
  }

  // ---- Layer B: runInNewTransaction -------------------------------------------------------------

  @Test
  void runInNewTransactionRunsTheWorkBodyOnce() {
    AtomicInteger runs = new AtomicInteger();

    runner.runInNewTransaction("probe", CONTEXT, runs::incrementAndGet);

    assertThat(runs.get()).isEqualTo(1);
    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.DEBUG
                    && e.getFormattedMessage().contains("probe side-effect completed"));
  }

  @Test
  void runInNewTransactionSwallowsAndWarnsWhenWorkThrows() {
    assertThatCode(
            () ->
                runner.runInNewTransaction(
                    "probe",
                    CONTEXT,
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .doesNotThrowAnyException();

    assertThat(appender.list)
        .anyMatch(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage()
                        .contains("probe swallowed an error (completion intact)")
                    && e.getFormattedMessage().contains("cause=IllegalStateException"));
  }

  private void invokeRegisteredAfterCommits() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCommit();
    }
  }
}
