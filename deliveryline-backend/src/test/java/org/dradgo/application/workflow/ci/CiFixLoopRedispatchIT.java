package org.dradgo.application.workflow.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.application.project.ProjectStore;
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
 * Story 3h-5 (AC2/AC7) — the locking IT for the CI fix loop. Mirrors {@code
 * BuildFixLoopRedispatchIT}: a red CI on a {@code WaitingForReview} run must re-dispatch a FRESH
 * EXECUTION runner (not a Replayed no-op), bump {@code ci_fix_loop_count}, materialize a FAILED CI
 * {@code runner_executions} row, and leave the run in {@code Executing} with the escalation marker
 * unraised. The deterministic {@code GitHubMockAdapter} returns a red verdict for the {@code
 * ci-red} head-SHA sentinel, so no adapter mocking is needed.
 *
 * <p>Non-{@code @Transactional} (the sweep uses {@code REQUIRES_NEW} per-run writes), named {@code
 * *IT} (Failsafe), with {@code implementation-stage.auto-dispatch=true} — the ONLY configuration
 * under which {@code retryImplementation} actually enqueues. By CI-poll time the delivery tail has
 * already completed the producing PR_OUTPUT rex, so this IT seeds it {@code completed} (the story's
 * "finalizeProducingExecution is NOT needed here" trap) and asserts no {@code running} EXECUTION
 * rex strands the re-dispatch.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
// github-mock explicit: @ActiveProfiles does NOT expand the `test` profile GROUP, so the
// GitHubMockAdapter must be named here or resolveRepositoryHost has no github connector.
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.workflow.ci-fix-max-loops=3",
      "deliveryline.workflow.ci-investigation.enabled=true",
      "deliveryline.runner.implementation-stage.auto-dispatch=true"
    })
@Tag("integration")
class CiFixLoopRedispatchIT {

  @Autowired private CiStatusPollingService ciStatusPollingService;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update("delete from projects where slug like 'cifix-it-%'");
  }

  @Test
  void redCiOnReviewableRunRedispatchesFreshExecutionAndBumpsCounter() {
    String projectId = seedGithubProject("cifix-it-redispatch");
    // WaitingForReview run pending a CI poll; ci-red head-SHA drives the mock's FAILURE verdict.
    String runId = seedWaitingForReviewRunPendingCi(projectId, 0);
    // Approved plan ⇒ deriveExecutionSubStage -> PR_OUTPUT; producing rex already COMPLETED (the
    // delivery tail finalized it before CI polled), so the in-flight guard does not strand it.
    seedApprovedImplementationPlan(runId, "art_cifix_plan");
    String producingRex = seedCompletedPrOutputExecution(runId);

    // The FAILURE grace window (story 3h-5 3rd review) holds a red verdict PENDING until
    // MIN_POLL_ATTEMPTS_BEFORE_ACCEPT (3) polls, so sweep three times: the first two bump the poll
    // attempt and keep the run pending; the third acts on the red verdict and re-dispatches.
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();
    ciStatusPollingService.sweep();

    // AC2 — the loop counter bumped once; the run transitioned to Executing (re-dispatch, not
    // fail).
    assertThat(ciFixLoopCount(runId)).isEqualTo(1);
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    assertThat(escalationMarker(runId)).isFalse();
    assertThat(ciStatus(runId)).isEqualTo("failure");

    // A FAILED CI runner_executions row was materialized (Decision 3).
    assertThat(ciRexStatus(runId)).isEqualTo("failed");

    // The producing rex stays completed (never stranded), and a FRESH queued EXECUTION rex exists —
    // not a Replayed no-op.
    assertThat(statusOf(producingRex)).isEqualTo("completed");
    assertThat(executionStageCount(runId)).isEqualTo(2);
    assertThat(queuedExecutionCount(runId)).isEqualTo(1);
  }

  // ---- helpers -------------------------------------------------------------

  private String seedGithubProject(String slug) {
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "CI fix " + slug,
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

  private String seedWaitingForReviewRunPendingCi(String projectId, int ciFixLoopCount) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id, ci_fix_loop_count,"
            + " ci_status, ci_head_sha) values (?, ?, ?, ?, 'pending', ?)",
        runId,
        WorkflowState.WAITING_FOR_REVIEW.value(),
        projectId,
        ciFixLoopCount,
        GitHubMockScenarioRegistry.CI_RED_HEAD_SHA);
    return runId;
  }

  private String seedCompletedPrOutputExecution(String runId) {
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at,
           completed_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', 'completed', 1,
                now(), now() + interval '10 minutes', 100, 0, now(), now())
        """,
        rex,
        runId);
    return rex;
  }

  private void seedApprovedImplementationPlan(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    long uniq = System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
                + " actor_type) values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning"
                + " id",
            Long.class,
            "evt_cifix" + uniq,
            runId);
    Long artifactId =
        jdbcTemplate.queryForObject(
            "insert into artifacts (public_id, workflow_run_id, artifact_type, version,"
                + " parent_artifact_id, classification, status, linked_event_id) values (?, ?,"
                + " 'implementationPlan', 1, null, 'shareable-redacted', 'pending', ?) returning id",
            Long.class,
            artifactPublicId,
            runId,
            linkedEventId);
    jdbcTemplate.update(
        "insert into approvals (public_id, workflow_run_id, artifact_id, artifact_version,"
            + " context_bundle_version, actor_identity, actor_type, reviewer_role, decision,"
            + " idempotency_key) values (?, ?, ?, 1, 1, 'alex', 'human', 'tech_reviewer',"
            + " 'approved', ?)",
        "apr_cifix" + uniq,
        runId,
        artifactId,
        "idem-cifix-approval-" + uniq);
  }

  private int ciFixLoopCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select ci_fix_loop_count from workflow_runs where public_id = ?", Integer.class, runId);
  }

  private String ciStatus(String runId) {
    return jdbcTemplate.queryForObject(
        "select ci_status from workflow_runs where public_id = ?", String.class, runId);
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

  private String ciRexStatus(String runId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where stage = 'ci' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        String.class,
        runId);
  }

  private String statusOf(String rexPublicId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, rexPublicId);
  }

  private int executionStageCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where stage = 'execution' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }

  private int queuedExecutionCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where stage = 'execution' and status = 'queued'"
            + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }
}
