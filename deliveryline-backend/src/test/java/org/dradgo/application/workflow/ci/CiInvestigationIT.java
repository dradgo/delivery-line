package org.dradgo.application.workflow.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.RedactedRunnerLog;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3h-5 (AC2/AC6/AC7) — real-PG behaviours of the CI-investigation sweep that do not need a
 * runner re-dispatch: the cap → escalation (never fail), green CI proceeds, and a red CI on a
 * non-reviewable run records the status but never re-dispatches. Uses the deterministic {@code
 * GitHubMockAdapter} (green by default; red for the {@code ci-red} head-SHA sentinel).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
// github-mock explicit: @ActiveProfiles does NOT expand the `test` profile GROUP (which lists
// github-mock at runtime), so the GitHubMockAdapter must be named here or resolveRepositoryHost has
// no github connector. [second-primary-vendor-adapter-in-profile-group-breaks-runtime-boot]
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.workflow.ci-fix-max-loops=3",
      "deliveryline.workflow.ci-investigation.enabled=true"
    })
@Tag("integration")
class CiInvestigationIT {

  @Autowired private CiStatusPollingService ciStatusPollingService;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RunnerLogStore runnerLogStore;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update("delete from projects where slug like 'ciinv-it-%'");
  }

  @Test
  void capExhaustedFlipsEscalationMarkerLeavesRunParkedAndEmitsOneEscalationEvent() {
    String projectId = seedGithubProject("ciinv-it-cap");
    // ci_fix_loop_count seeded at cap (3) → increment yields 4 > cap → escalation branch.
    String runId =
        seedRunPendingCi(
            projectId,
            WorkflowState.WAITING_FOR_REVIEW,
            3,
            GitHubMockScenarioRegistry.CI_RED_HEAD_SHA);

    // FAILURE grace (story 3h-5 3rd review): the red verdict is acted on only after
    // MIN_POLL_ATTEMPTS_BEFORE_ACCEPT (3) polls — the first two keep the run pending.
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();

    assertThat(escalationMarker(runId)).isTrue();
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    assertThat(ciStatus(runId)).isEqualTo("failure");
    // No EXECUTION re-dispatch on cap exhaustion.
    assertThat(executionStageCount(runId)).isZero();
    // Exactly one ESCALATION_REQUIRED event.
    assertThat(escalationEventCount(runId)).isEqualTo(1);
  }

  @Test
  void greenCiRecordsSuccessAfterGraceAndDoesNotMaterializeACiRex() {
    String projectId = seedGithubProject("ciinv-it-green");
    // A non-ci-red head-SHA → the mock returns SUCCESS.
    String runId =
        seedRunPendingCi(projectId, WorkflowState.WAITING_FOR_REVIEW, 0, "green-sha-123");

    // SUCCESS is graced (story 3h-5 review round 2 — the "green" half of the push→register race):
    // the run keeps polling until MIN_POLL_ATTEMPTS_BEFORE_ACCEPT (3) before green is accepted and
    // it
    // leaves the sweep. Earlier ticks leave it pending (no ci rex, no verdict recorded).
    ciStatusPollingService.sweep();
    assertThat(ciStatus(runId)).isEqualTo("pending");
    ciStatusPollingService.sweep();
    assertThat(ciStatus(runId)).isEqualTo("pending");
    ciStatusPollingService.sweep();

    assertThat(ciStatus(runId)).isEqualTo("success");
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    assertThat(ciRexCount(runId)).isZero();
    assertThat(executionStageCount(runId)).isZero();
  }

  @Test
  void redCiOnNonReviewableRunRecordsFailureWithoutTransitionOrRedispatch() {
    String projectId = seedGithubProject("ciinv-it-nonreviewable");
    // The run has already left WaitingForReview (Completed is terminal).
    String runId =
        seedRunPendingCi(
            projectId, WorkflowState.COMPLETED, 0, GitHubMockScenarioRegistry.CI_RED_HEAD_SHA);

    // FAILURE grace (story 3h-5 3rd review): three polls to get past the grace window; the CAS on
    // the acting sweep then reads Completed (non-reviewable) and records failure without acting.
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();

    assertThat(ciStatus(runId)).isEqualTo("failure");
    assertThat(currentState(runId)).isEqualTo(WorkflowState.COMPLETED.value());
    assertThat(ciFixLoopCount(runId)).isZero();
    assertThat(ciRexCount(runId)).isZero();
    assertThat(executionStageCount(runId)).isZero();
  }

  @Test
  void redCiFailureBodyIsRedactedInThePersistedCiRawOutput() {
    // AC5 — a secret planted in the check-run body must be redaction-policed on the way into the
    // persisted CI runner_executions raw output. Seed a reviewable run at cap so the CI rex is
    // materialized + captured (Phase 2/3) then escalates (no re-dispatch), and assert the stored
    // redacted log carries the placeholder, never the token. [redaction-fixture-two-gates]
    String projectId = seedGithubProject("ciinv-it-redaction");
    String runId =
        seedRunPendingCi(
            projectId,
            WorkflowState.WAITING_FOR_REVIEW,
            3,
            GitHubMockScenarioRegistry.CI_RED_WITH_SECRET_HEAD_SHA);

    // FAILURE grace (story 3h-5 3rd review): three polls to get past the grace window; only the
    // acting sweep materializes + captures the CI rex.
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();

    // The CI rex was materialized (Decision 3) and its stored output is the redacted body.
    assertThat(ciRexCount(runId)).isEqualTo(1);
    Optional<RedactedRunnerLog> stored = runnerLogStore.readRedacted(ciRexPublicId(runId));
    assertThat(stored).isPresent();
    assertThat(stored.get().stdout())
        .contains("[REDACTED_")
        .doesNotContain("ghp_1234567890abcdef1234567890abcdef1234");
  }

  // ---- helpers -------------------------------------------------------------

  private String ciRexPublicId(String runId) {
    return jdbcTemplate.queryForObject(
        "select public_id from runner_executions where stage = 'ci' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        String.class,
        runId);
  }

  private String seedGithubProject(String slug) {
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "CI inv " + slug,
            slug,
            ProjectStatus.ACTIVE,
            "octo/hello",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            Map.of(),
            null,
            false);
    return projectStore.insert(project).publicId();
  }

  private String seedRunPendingCi(
      String projectId, WorkflowState state, int ciFixLoopCount, String ciHeadSha) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id, ci_fix_loop_count,"
            + " ci_status, ci_head_sha) values (?, ?, ?, ?, 'pending', ?)",
        runId,
        state.value(),
        projectId,
        ciFixLoopCount,
        ciHeadSha);
    return runId;
  }

  private String ciStatus(String runId) {
    return jdbcTemplate.queryForObject(
        "select ci_status from workflow_runs where public_id = ?", String.class, runId);
  }

  private int ciFixLoopCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select ci_fix_loop_count from workflow_runs where public_id = ?", Integer.class, runId);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private boolean escalationMarker(String runId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select escalation_marker_set from workflow_runs where public_id = ?",
            Boolean.class,
            runId));
  }

  private int escalationEventCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from workflow_events where event_type = 'escalation.required' and"
            + " workflow_run_id = (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }

  private int ciRexCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where stage = 'ci' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }

  private int executionStageCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where stage = 'execution' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }
}
