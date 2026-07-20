package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Story 4.25 (AC2e) — recovery-integration CI-tier SCENARIO: the classify-failure recovery path
 * end-to-end on Testcontainers Postgres. Composes {@link RecoveryService#classifyFailure} against a
 * seeded {@code Failed} run and asserts the governed AC2(e) outcomes: the {@code
 * workflow_runs.failure_classification} triple is set (behind the V44 CHECKs, via the
 * classification port — the columns are not entity-mapped), a {@code recovery.failureClassified}
 * event is appended, and the operation performs NO transition (the run stays {@code Failed} —
 * classify is pure post-hoc metadata). Template: {@link RecoveryServiceClassifyFailureIT}.
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres. Tagged {@code @Tag("recovery-integration")}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("recovery-integration")
class ClassifyFailureRecoveryScenarioIT {

  private static final String RUN = "run_classifyscn01";
  private static final String FAIL_EVENT = "evt_classifyscn_f1";
  private static final String KEY = "idem-scenario-classify-0001";
  private static final String TAXONOMY = "agent_execution_failure";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecoveryService recoveryService;

  @AfterEach
  void cleanUp() {
    Long runId =
        jdbcTemplate.query(
            "select id from workflow_runs where public_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            RUN);
    if (runId != null) {
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
    }
    jdbcTemplate.update("delete from idempotency_records where key = ?", KEY);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
  }

  @Test
  void classifyFailureSetsClassificationTripleAppendsEventAndPerformsNoTransition() {
    Long runId = seedFailedRun();

    ClassifyFailureResult result =
        recoveryService.classifyFailure(
            RUN,
            TAXONOMY,
            KEY,
            new ActorContext("alex", ActorType.HUMAN, "corr-classify-scenario"),
            "runner crashed mid-execution");

    assertThat(result.replayed()).isFalse();
    assertThat(result.taxonomyValue()).isEqualTo(TAXONOMY);
    assertThat(result.priorTaxonomyValue()).isNull();

    // The V44 classification triple is set on workflow_runs (not entity-mapped columns).
    assertThat(classificationColumn("failure_classification")).isEqualTo(TAXONOMY);
    assertThat(classificationColumn("failure_classified_by")).isEqualTo("alex");
    assertThat(
            jdbcTemplate.queryForObject(
                "select failure_classified_at from workflow_runs where public_id = ?",
                java.sql.Timestamp.class,
                RUN))
        .isNotNull();

    // NO transition — classify is pure post-hoc metadata (AC2e / NFR).
    assertThat(classificationColumn("current_state")).isEqualTo(WorkflowState.FAILED.value());

    // The recovery.failureClassified event is appended, and the recovery_actions row succeeded.
    Integer classifiedEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where workflow_run_id = ?"
                + " and event_type = 'recovery.failureClassified'"
                + " and details->>'taxonomyValue' = ?",
            Integer.class,
            runId,
            TAXONOMY);
    assertThat(classifiedEvents).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select action_type from recovery_actions where idempotency_key = ?",
                String.class,
                KEY))
        .isEqualTo("classify_failure");
    assertThat(
            jdbcTemplate.queryForObject(
                "select result_status from recovery_actions where idempotency_key = ?",
                String.class,
                KEY))
        .isEqualTo("succeeded");
  }

  private Long seedFailedRun() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        RUN,
        WorkflowState.FAILED.value());
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);
    jdbcTemplate.update(
        "insert into workflow_events "
            + "(public_id, workflow_run_id, event_type, prior_state, resulting_state, "
            + " actor_identity, actor_type, failure_category, intervention_marker) "
            + "values (?, ?, 'workflow.stateChanged', 'Executing', 'Failed', 'system', 'system', "
            + " 'runner_crash', false)",
        FAIL_EVENT,
        runId);
    return runId;
  }

  private String classificationColumn(String column) {
    return jdbcTemplate.queryForObject(
        "select " + column + " from workflow_runs where public_id = ?", String.class, RUN);
  }
}
