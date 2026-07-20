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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Story 3h-1 (Task 9, AC1/AC3/AC4/AC5/AC6) — real-Postgres proof of the build-validation gate over
 * the actual persistence + afterCommit/REQUIRES_NEW machinery. {@link BuildCommandPort} and {@link
 * RunnerWorkspaceStore} are mocked for deterministic pass/fail + a resolvable workspace; everything
 * else (execution recording, the loop-counter JDBC {@code UPDATE ... RETURNING}, the FAILED
 * transition + shared escalation marker, redacted log capture) is the real wiring.
 *
 * <p>The gate is driven inside a {@link TransactionTemplate} to mirror the poller transaction the
 * broker calls it from — so {@code AfterCommitSideEffectRunner.runAfterCommit} fires the build on
 * commit. Non-{@code @Transactional} (afterCommit/REQUIRES_NEW is exercised); named {@code *IT} so
 * Failsafe runs it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.workflow.build-fix-max-loops=3")
@Tag("integration")
class BuildStageIT {

  private static final String BUILD_COMMAND = "build.sh";
  private static final Path REPO_DIR = Paths.get("/tmp/build-stage-it-repo");

  @Autowired private BuildStageService buildStageService;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private BuildCommandPort buildCommandPort;
  @MockitoBean private RunnerWorkspaceStore workspaceStore;
  // Mocked so the build-artifact-discard snapshot/clean does not shell out to real git against the
  // fake REPO_DIR; listUntrackedFiles() returns null ⇒ discard cleanly skipped.
  @MockitoBean private GitCommandPort gitCommandPort;

  private TransactionTemplate tx;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update("delete from projects where slug like 'buildstage-it-%'");
  }

  @BeforeEach
  void setUpTemplate() {
    tx = new TransactionTemplate(transactionManager);
    when(workspaceStore.resolveRepositoryDir(any())).thenReturn(Optional.of(REPO_DIR));
  }

  @Test
  void buildFailBelowCapBumpsLoopCountAndKeepsRunExecuting() {
    String projectId = seedProject("buildstage-it-belowcap", true, BUILD_COMMAND);
    String runId = seedExecutingRun(projectId, 0);
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(BUILD_COMMAND), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "compile error"));

    boolean gated = gate(runId, prOutputRex, () -> {});

    assertThat(gated).isTrue();
    // AC4 — the loop counter was atomically bumped in the DB.
    assertThat(loopCount(runId)).isEqualTo(1);
    // The run never left Executing (re-dispatch, not fail); no escalation yet.
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    assertThat(escalationMarker(runId)).isFalse();
    // A BUILD execution row was recorded (failed) — reusing the runner-execution substrate.
    assertThat(buildExecutionStatus(runId)).isEqualTo("failed");
  }

  @Test
  void buildFailAtCapFailsRunWithBuildCategoryAndFlipsEscalationOnce() {
    String projectId = seedProject("buildstage-it-cap", true, BUILD_COMMAND);
    String runId = seedExecutingRun(projectId, 3); // increment -> 4 > cap(3)
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(BUILD_COMMAND), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "still broken"));

    gate(runId, prOutputRex, () -> {});

    // AC5 — cap exceeded: run FAILED with the build category, escalation marker flipped once.
    assertThat(loopCount(runId)).isEqualTo(4);
    assertThat(currentState(runId)).isEqualTo(WorkflowState.FAILED.value());
    assertThat(escalationMarker(runId)).isTrue();
    // The FAILED transition recorded the runner_build_failed category on a workflow_events row.
    Integer failedEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where failure_category = 'runner_build_failed'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId);
    assertThat(failedEvents).isGreaterThanOrEqualTo(1);
  }

  @Test
  void buildPassCompletesBuildRexRunsTailAndRecordsNoTokens() {
    String projectId = seedProject("buildstage-it-pass", true, BUILD_COMMAND);
    String runId = seedExecutingRun(projectId, 0);
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(BUILD_COMMAND), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(0, "BUILD SUCCESS", ""));
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    assertThat(gated).isTrue();
    // On success the BUILD row is completed and the deferred delivery tail runs.
    assertThat(buildExecutionStatus(runId)).isEqualTo("completed");
    assertThat(tailRan.get()).isTrue();
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    // AC6 — a backend-side BUILD execution records ZERO token usage (columns stay NULL).
    assertThat(buildTokenColumnsAllNull(runId)).isTrue();
  }

  @Test
  void disabledProjectSkipsBuildEntirely() {
    String projectId = seedProject("buildstage-it-disabled", false, BUILD_COMMAND);
    String runId = seedExecutingRun(projectId, 0);
    String prOutputRex = seedPrOutputExecution(runId);
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    // AC2/AC3 parity — the gate is skipped: no BUILD row, no build invoked, tail untouched here.
    assertThat(gated).isFalse();
    assertThat(buildExecutionStatus(runId)).isNull();
    assertThat(tailRan.get()).isFalse();
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
  }

  // ---- helpers -------------------------------------------------------------

  private boolean gate(String runId, String prOutputRex, Runnable tail) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                buildStageService.tryGateBehindBuild(runId, prOutputRex, "corr-build-it", tail)));
  }

  private String seedProject(String slug, boolean buildEnabled, String buildCommand) {
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "Build stage " + slug,
            slug,
            ProjectStatus.ACTIVE,
            "octo/buildstage",
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

  private String seedPrOutputExecution(String runId) {
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

  private String buildExecutionStatus(String runId) {
    return jdbcTemplate.query(
        "select status from runner_executions where stage = 'build' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        rs -> rs.next() ? rs.getString(1) : null,
        runId);
  }

  private boolean buildTokenColumnsAllNull(String runId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.query(
            "select input_tokens, output_tokens, total_tokens from runner_executions"
                + " where stage = 'build' and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            rs -> {
              if (!rs.next()) {
                return false;
              }
              rs.getInt(1);
              boolean inNull = rs.wasNull();
              rs.getInt(2);
              boolean outNull = rs.wasNull();
              rs.getInt(3);
              boolean totNull = rs.wasNull();
              return inNull && outNull && totNull;
            },
            runId));
  }
}
