package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.commands.SubmitBatchCommand;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3.18 (AC4/AC9/AC11) — batch submission persistence + replay against a real Postgres.
 *
 * <p>Opts into {@code spec-stage.auto-dispatch=true} (worker pool stays OFF in the shared test
 * profile) so each per-ticket submit enqueues a {@code queued} runner_executions row that is NOT
 * drained — letting the test assert {@code batch_submission_id} stamping. V15 applies implicitly
 * (Flyway boots the context); the {@code result_json} snapshot reconstructs the full per-ticket
 * list on an idempotent replay.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.spec-stage.auto-dispatch=true")
@Tag("integration")
class WorkflowBatchSubmissionIT {

  @Autowired private WorkflowBatchSubmissionService service;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void persistsBatchStampsQueuedRunsAndReplaysIdentically() {
    String key = "idembatch" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    SubmitBatchCommand command =
        new SubmitBatchCommand(List.of("LIN-101", "LIN-102"), "alex", ActorType.HUMAN, key, null);

    BatchSubmissionResult result = service.submitBatch(command);

    assertThat(result.batchId()).startsWith("bat_");
    assertThat(result.total()).isEqualTo(2);
    assertThat(result.queuedCount()).isEqualTo(2);
    assertThat(result.rejectedCount()).isZero();

    // batch_submissions row persisted with the per-ticket result_json.
    Integer rowCount =
        jdbcTemplate.queryForObject(
            "select count(*) from batch_submissions where public_id = ?",
            Integer.class,
            result.batchId());
    assertThat(rowCount).isEqualTo(1);
    String resultJson =
        jdbcTemplate.queryForObject(
            "select result_json from batch_submissions where public_id = ?",
            String.class,
            result.batchId());
    assertThat(resultJson).contains("LIN-101").contains("LIN-102").contains("queued");

    // Each queued run's runner_executions row is stamped with the batch id (AC4 / story 3.19).
    for (TicketBatchResult ticket : result.tickets()) {
      assertThat(ticket.isQueued()).isTrue();
      List<String> stamped =
          jdbcTemplate.queryForList(
              "select batch_submission_id from runner_executions where workflow_run_id ="
                  + " (select id from workflow_runs where public_id = ?)",
              String.class,
              ticket.runId());
      assertThat(stamped).isNotEmpty().allMatch(result.batchId()::equals);
    }

    long runsBefore =
        jdbcTemplate.queryForObject("select count(*) from runner_executions", Long.class);

    // Idempotent replay: same key + same fingerprint returns the identical result and writes
    // nothing.
    BatchSubmissionResult replay = service.submitBatch(command);

    assertThat(replay.batchId()).isEqualTo(result.batchId());
    assertThat(replay.submittedAt()).isEqualTo(result.submittedAt());
    assertThat(replay.tickets()).isEqualTo(result.tickets());
    // No duplicate batch row, and no fresh per-ticket runs (REPLAY short-circuits before submit).
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from batch_submissions where public_id = ?",
                Long.class,
                result.batchId()))
        .isEqualTo(1L);
    assertThat(jdbcTemplate.queryForObject("select count(*) from runner_executions", Long.class))
        .isEqualTo(runsBefore);
  }
}
