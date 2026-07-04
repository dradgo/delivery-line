package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3g-3 (FR74, AC2/AC3/AC4) — real-Postgres round-trip for {@link
 * RunnerExecutionRecordPort#recordTokenUsage}: the three V31 token columns persist and round-trip
 * onto {@link RunnerExecutionSnapshot}; a no-usage row stays NULL (parity); the metadata write
 * never mutates {@code status}. NOT {@code @Transactional} — recordTokenUsage commits in a
 * REQUIRES_NEW tx that would not see an uncommitted seed row, so the row is seeded via an
 * auto-committed JDBC insert and the seeded rows are deleted in {@code @AfterEach} (shared
 * Postgres, mirror RunnerExecutionQueueIT). Named {@code *IT} so Failsafe runs it (a {@code *Test}
 * name leaks into Windows Surefire).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RunnerExecutionTokenUsagePersistenceIT {

  @Autowired private RunnerExecutionRecordPort recordPort;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void recordTokenUsagePersistsTheThreeColumnsAndRoundTripsOntoTheSnapshot() {
    String rex = seedRunningExecution();

    RunnerExecutionSnapshot snapshot = recordPort.recordTokenUsage(rex, 1200, 800, 2000);

    assertThat(snapshot.inputTokens()).isEqualTo(1200);
    assertThat(snapshot.outputTokens()).isEqualTo(800);
    assertThat(snapshot.totalTokens()).isEqualTo(2000);
    // Columns actually landed on the row.
    assertThat(
            jdbcTemplate.queryForObject(
                "select input_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isEqualTo(1200);
    // Metadata-only: the write never mutated status.
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from runner_executions where public_id = ?", String.class, rex))
        .isEqualTo("running");
  }

  @Test
  void noUsageRowKeepsTheThreeColumnsNull() {
    String rex = seedRunningExecution();

    RunnerExecutionSnapshot snapshot = recordPort.findByPublicId(rex).orElseThrow();

    assertThat(snapshot.inputTokens()).isNull();
    assertThat(snapshot.outputTokens()).isNull();
    assertThat(snapshot.totalTokens()).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "select total_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isNull();
  }

  @Test
  void partiallyReportedCountsPersistIndependently() {
    String rex = seedRunningExecution();

    // Only totalTokens reported — input/output stay NULL (never synthesized from the total).
    RunnerExecutionSnapshot snapshot = recordPort.recordTokenUsage(rex, null, null, 2000);

    assertThat(snapshot.inputTokens()).isNull();
    assertThat(snapshot.outputTokens()).isNull();
    assertThat(snapshot.totalTokens()).isEqualTo(2000);
  }

  /**
   * Regression for the silent token-usage clobber (0/N rows ever populated in production). Faithful
   * to the broker's {@code onResult} flow: an AMBIENT transaction first loads the row (caching a
   * managed entity with NULL tokens), THEN {@code recordTokenUsage} commits the counts in its
   * REQUIRES_NEW tx, THEN the terminal {@code markCompleted} re-saves the SAME row from the ambient
   * (now-stale) entity. Before {@code @DynamicUpdate} on the entity, that terminal full-row UPDATE
   * wrote every column from the stale entity — overwriting the freshly-committed token columns back
   * to NULL. With per-column (dynamic) updates the token columns are left untouched and survive.
   */
  @Test
  void terminalTransitionDoesNotClobberTokenUsage() {
    String rex = seedRunningExecution();
    TransactionTemplate ambient = new TransactionTemplate(transactionManager);

    ambient.executeWithoutResult(
        status -> {
          // Ambient load — caches a managed entity with NULL tokens in this tx's persistence
          // context
          // (mirrors `row = findByPublicId(rex)` at the top of onResult).
          recordPort.findByPublicId(rex).orElseThrow();
          // REQUIRES_NEW: commits the token counts on the row out-of-band.
          recordPort.recordTokenUsage(rex, 1200, 800, 2000);
          // Terminal transition in the ambient tx — re-saves the SAME row from the stale entity.
          recordPort.markCompleted(rex, OffsetDateTime.now(ZoneOffset.UTC));
        });

    assertThat(
            jdbcTemplate.queryForObject(
                "select total_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isEqualTo(2000);
    assertThat(
            jdbcTemplate.queryForObject(
                "select input_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isEqualTo(1200);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from runner_executions where public_id = ?", String.class, rex))
        .isEqualTo("completed");
  }

  private String seedRunningExecution() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'investigation', 'running', 1,
                now(), now() + interval '10 minutes', 100, 0, now())
        """,
        rex,
        runId);
    return rex;
  }
}
