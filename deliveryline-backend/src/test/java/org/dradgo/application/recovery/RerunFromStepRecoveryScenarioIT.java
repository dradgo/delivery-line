package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.approval.spi.ApprovalReadPort;
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
 * Story 4.25 (AC2c) — recovery-integration CI-tier SCENARIO: the rerun-from-step recovery path
 * end-to-end on Testcontainers Postgres. Composes {@link RecoveryService#rerunFromStep} against a
 * seeded {@code Failed} run carrying an approved spec, and asserts the governed AC2(c) outcomes:
 * the {@code Failed → Investigating} transition, the superseded active-leaf artifact ids, the
 * invalidated spec approval ids, and — the real enqueue this path performs (unlike resume's
 * auto-dispatch-gated one) — a fresh QUEUED {@code runner_executions} row for the re-run
 * investigation runner. Template: {@link RecoveryServiceRerunFromStepIT}.
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres. Tagged {@code @Tag("recovery-integration")}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("recovery-integration")
class RerunFromStepRecoveryScenarioIT {

  private static final String RUN = "run_rerunscn0001";
  private static final String FAIL_EVENT = "evt_rerunscn_fail1";
  private static final String SPEC_ART = "art_rerunscn_spec1";
  private static final String APPROVAL = "apr_rerunscn_spec1";
  private static final String APPROVAL_SEED_KEY = "idem-rerunscn-seed-approval";
  private static final String KEY = "idem-scenario-rerun-0001";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecoveryService recoveryService;
  @Autowired private ApprovalReadPort approvalReadPort;

  @AfterEach
  void cleanUp() {
    Long runId =
        jdbcTemplate.query(
            "select id from workflow_runs where public_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            RUN);
    if (runId != null) {
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from runner_executions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from approvals where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
    }
    jdbcTemplate.update("delete from idempotency_records where key like 'idem-rerunscn-%'");
    jdbcTemplate.update("delete from idempotency_records where key = ?", KEY);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
  }

  @Test
  void rerunFromStepInvalidatesApprovalSupersedesArtifactsAndEnqueuesInvestigationRunner() {
    Long runId = seedFailedRunWithApprovedSpec();

    RerunFromStepRecoveryResult result =
        recoveryService.rerunFromStep(
            RUN,
            "investigating",
            KEY,
            new ActorContext("alex", ActorType.HUMAN, "corr-rerun-scenario"),
            "spec missed scope; re-spec required");

    assertThat(result.replayed()).isFalse();
    assertThat(result.resultingState()).isEqualTo(WorkflowState.INVESTIGATING);
    assertThat(result.supersededArtifactIds()).contains(SPEC_ART);
    assertThat(result.invalidatedApprovalIds()).contains(APPROVAL);

    // Transition landed.
    assertThat(currentState()).isEqualTo(WorkflowState.INVESTIGATING.value());

    // The prior spec approval is invalidated with the governed reason.
    String invalidatedReason =
        jdbcTemplate.queryForObject(
            "select invalidated_reason from approvals where public_id = ?", String.class, APPROVAL);
    assertThat(invalidatedReason).isEqualTo("superseded_by_rerun_from_step");
    assertThat(approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "spec")).isEmpty();

    // Real enqueue: rerun-from-step queues a fresh investigation runner (RunnerExecutionQueue),
    // unlike resume whose re-dispatch is auto-dispatch-gated off in the test profile.
    Integer queuedRunners =
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where status = 'queued'"
                + " and workflow_run_id = ?",
            Integer.class,
            runId);
    assertThat(queuedRunners).isGreaterThanOrEqualTo(1);
  }

  private Long seedFailedRunWithApprovedSpec() {
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
            + " actor_identity, actor_type, intervention_marker) "
            + "values (?, ?, 'workflow.stateChanged', 'Executing', 'Failed', 'system', 'system',"
            + " false)",
        FAIL_EVENT,
        runId);
    Long eventId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id = ?", Long.class, FAIL_EVENT);

    jdbcTemplate.update(
        "insert into artifacts "
            + "(public_id, workflow_run_id, artifact_type, version, classification, status, "
            + " linked_event_id) "
            + "values (?, ?, 'spec', 1, 'shareable-redacted', 'available', ?)",
        SPEC_ART,
        runId,
        eventId);

    jdbcTemplate.update(
        "insert into approvals "
            + "(public_id, workflow_run_id, artifact_id, artifact_version, context_bundle_version, "
            + " actor_identity, actor_type, reviewer_role, decision, idempotency_key) "
            + "values (?, ?, (select id from artifacts where public_id = ?), 1, 1, "
            + " 'alex', 'human', 'product_reviewer', 'approved', ?)",
        APPROVAL,
        runId,
        SPEC_ART,
        APPROVAL_SEED_KEY);

    assertThat(approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "spec")).isPresent();
    return runId;
  }

  private String currentState() {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, RUN);
  }
}
