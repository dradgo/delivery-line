package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.spi.BuildCommandPort;
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3h-1 (code-review regression, CRITICAL) — locks the build auto-fix loop's re-dispatch
 * against the sub-stage-aware in-flight dispatch guard, with {@code
 * implementation-stage.auto-dispatch=true} (the ONLY configuration under which the bug manifests —
 * the shared test profile and {@link BuildStageIT} run it OFF, so its below-cap case short-circuits
 * in {@code dispatchExecutionInternal} before the guard and never exercises this path).
 *
 * <p><b>The bug this guards:</b> on a below-cap BUILD failure the producing PR_OUTPUT {@code
 * runner_executions} row was left {@code running} (its completion lives only in the success-path
 * delivery tail). With an approved implementation-plan present, {@code deriveExecutionSubStage}
 * yields {@code PR_OUTPUT}, so {@code retryImplementation}'s in-flight guard found that
 * still-active EXECUTION row and returned a {@code Replayed} no-op — the loop never actually
 * re-dispatched, the {@code build.failure} feedback was never delivered, and the run wedged in
 * {@code Executing} until timeout-reaped as {@code RUNNER_TIMEOUT}. The fix ({@code
 * BuildStageService.finalizeProducingExecution}) completes the producing rex BEFORE the re-dispatch
 * so the guard passes and a fresh EXECUTION dispatch is enqueued.
 *
 * <p>This IT reproduces the EXACT precondition (approved plan ⇒ sub-stage {@code PR_OUTPUT}, plus a
 * still-{@code running} producing rex) and asserts both halves of the fix: the producing rex is
 * finalized AND a second (queued) EXECUTION execution is created. It would FAIL before the fix
 * (producing rex stays {@code running}; execution count stays 1). Non-{@code @Transactional}, named
 * {@code *IT} (Failsafe), gate driven inside a {@link TransactionTemplate} to mirror the poller tx.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.workflow.build-fix-max-loops=3",
      "deliveryline.runner.implementation-stage.auto-dispatch=true"
    })
@Tag("integration")
class BuildFixLoopRedispatchIT {

  private static final String BUILD_COMMAND = "build.sh";
  private static final Path REPO_DIR = Paths.get("/tmp/build-fix-redispatch-it-repo");

  @Autowired private BuildStageService buildStageService;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private BuildCommandPort buildCommandPort;
  @MockitoBean private RunnerWorkspaceStore workspaceStore;
  // Mocked so the build-artifact discard does not shell out to git against the fake REPO_DIR
  // (listUntrackedFiles() returns null ⇒ discard cleanly skipped).
  @MockitoBean private GitCommandPort gitCommandPort;

  private TransactionTemplate tx;

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
    jdbcTemplate.update("delete from projects where slug like 'buildfix-it-%'");
  }

  @BeforeEach
  void setUpTemplate() {
    tx = new TransactionTemplate(transactionManager);
    when(workspaceStore.resolveRepositoryDir(any())).thenReturn(Optional.of(REPO_DIR));
  }

  @Test
  void belowCapBuildFailureFinalizesProducingRexAndReDispatchesFreshExecution() {
    String projectId = seedProject("buildfix-it-redispatch", true, BUILD_COMMAND);
    String runId = seedExecutingRun(projectId, 0);
    // Approved implementation-plan ⇒ deriveExecutionSubStage -> PR_OUTPUT (the bug precondition).
    seedApprovedImplementationPlan(runId, "art_buildfix_plan");
    // The producing PR_OUTPUT execution is still RUNNING at build time — exactly the row that would
    // strand the in-flight guard if it were not finalized.
    String producingRex = seedRunningPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(BUILD_COMMAND), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "compile error"));

    boolean gated = gate(runId, producingRex, () -> {});

    assertThat(gated).isTrue();
    // AC4 — the loop counter bumped once; the run stays Executing (re-dispatch, not fail).
    assertThat(loopCount(runId)).isEqualTo(1);
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    assertThat(escalationMarker(runId)).isFalse();
    // The BUILD execution row is recorded failed.
    assertThat(statusOf(producingBuildRex(runId))).isEqualTo("failed");

    // CRITICAL fix, half 1 — the producing PR_OUTPUT execution is finalized (completed), so it no
    // longer strands the sub-stage in-flight guard. Pre-fix this would still be 'running'.
    assertThat(statusOf(producingRex)).isEqualTo("completed");

    // CRITICAL fix, half 2 — the re-dispatch was NOT a Replayed no-op: a SECOND EXECUTION-stage
    // execution was enqueued. Pre-fix the guard would replay the still-running producing rex and
    // this count would stay 1.
    assertThat(executionStageCount(runId)).isEqualTo(2);
    assertThat(queuedExecutionCount(runId)).isEqualTo(1);
  }

  // ---- helpers -------------------------------------------------------------

  private boolean gate(String runId, String producingRex, Runnable tail) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                buildStageService.tryGateBehindBuild(
                    runId, producingRex, "corr-buildfix-it", tail)));
  }

  private String seedProject(String slug, boolean buildEnabled, String buildCommand) {
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "Build fix " + slug,
            slug,
            ProjectStatus.ACTIVE,
            "octo/buildfix",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            Map.of(),
            buildEnabled ? buildCommand : null,
            buildEnabled);
    return projectStore.insert(project).publicId();
  }

  private String seedExecutingRun(String projectId, int buildFixLoopCount) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id, build_fix_loop_count)"
            + " values (?, ?, ?, ?)",
        runId,
        WorkflowState.EXECUTING.value(),
        projectId,
        buildFixLoopCount);
    return runId;
  }

  private String seedRunningPrOutputExecution(String runId) {
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', 'running', 1,
                now(), now() + interval '10 minutes', 100, 0, now())
        """,
        rex,
        runId);
    return rex;
  }

  /**
   * Seeds an APPROVED implementation-plan artifact lineage so {@code deriveExecutionSubStage}
   * yields {@code PR_OUTPUT} (mirrors {@code PrOutputOrchestrationIT}). The plan artifact stays
   * {@code pending}; the {@code decision='approved'} approval row is the sole condition {@code
   * findLatestApprovedForArtifactLineage} checks.
   */
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
            "evt_buildfix" + uniq,
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
        "apr_buildfix" + uniq,
        runId,
        artifactId,
        "idem-buildfix-approval-" + uniq);
  }

  private int loopCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select build_fix_loop_count from workflow_runs where public_id = ?", Integer.class, runId);
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

  private String statusOf(String rexPublicId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, rexPublicId);
  }

  private String producingBuildRex(String runId) {
    return jdbcTemplate.queryForObject(
        "select public_id from runner_executions where stage = 'build' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        String.class,
        runId);
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
