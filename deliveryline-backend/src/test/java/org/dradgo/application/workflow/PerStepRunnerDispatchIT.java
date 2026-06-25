package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectRunnerStep;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.RunnerKind;
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
 * Story 3e-4 (AC5, Task 7) — end-to-end proof over real-Postgres wiring that the per-step runner
 * mapping rides the unchanged 3d-3 dispatch chokepoint, exercised at the spec gate (the step
 * reachable from {@code submit}):
 *
 * <ul>
 *   <li><b>per-step {@code manual} parks</b>: a project with {@code stepRunnerKinds={spec:manual}}
 *       and NO project-wide override parks the spec dispatch in {@code WaitingForManualExecution}
 *       (the resolver returns {@code MANUAL} for the spec step; the existing chokepoint branches to
 *       the manual dispatcher — NO new dispatch logic).
 *   <li><b>per-step non-manual overrides a project-wide manual default and enqueues</b>: a project
 *       with {@code runnerKind=manual} (project-wide) but {@code stepRunnerKinds={spec:codex}}
 *       ENQUEUES the spec dispatch (Investigating + a queued row) — the per-step mapping wins over
 *       the single override, so the step that would otherwise park instead enqueues.
 * </ul>
 *
 * <p>The {@code prOutput=manual}-parks-while-spec-enqueues mixed behavior the story headlines uses
 * the identical chokepoint keyed off whatever {@code resolveRunnerKind} returns. That chokepoint
 * ({@code WorkflowOrchestrationService.enqueueDispatch}) is stage-agnostic: it derives the
 * EXECUTION sub-stage, feeds it to the resolver, and branches {@code if (kind == MANUAL) park(...)}
 * identically for INVESTIGATION and both EXECUTION sub-stages. So the EXECUTION-sub-stage manual
 * park is proven by COMPOSITION — this IT drives the spec-gate park end-to-end over real Postgres,
 * while {@code ProjectRuntimeConfigResolverTest.perStepManualResolvesManualForEveryMappableStep}
 * proves the resolver returns {@code MANUAL} for spec / implementationPlan / prOutput — rather than
 * via a brittle multi-stage drive to a reachable prOutput EXECUTION dispatch. The
 * per-(stage,sub-stage) derivation itself is exhaustively covered by {@code
 * ProjectRunnerStepsTest}. Mirrors {@code ManualExecutionParkIT}; named {@code *IT} so Failsafe
 * runs it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.spec-stage.auto-dispatch=true")
@Tag("integration")
class PerStepRunnerDispatchIT {

  private static final String STEP_MANUAL_SLUG = "perstep-spec-manual";
  private static final String STEP_CODEX_SLUG = "perstep-spec-codex";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private ProjectStore projectStore;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private RunnerAdapter runnerAdapter;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private RepositoryWorkspaceService repositoryWorkspaceService;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update(
        "delete from project_runner_kinds where project_id in"
            + " (select public_id from projects where slug in (?, ?))",
        STEP_MANUAL_SLUG,
        STEP_CODEX_SLUG);
    jdbcTemplate.update(
        "delete from projects where slug in (?, ?)", STEP_MANUAL_SLUG, STEP_CODEX_SLUG);
  }

  @Test
  void perStepManualOnSpecParksTheRun() {
    seedProject(STEP_MANUAL_SLUG, null, Map.of(ProjectRunnerStep.SPEC, RunnerKind.MANUAL));
    stubGitClone();

    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex",
                    ActorType.HUMAN,
                    "idem-3e4-stepmanual",
                    "corr-3e4-stepmanual",
                    "LIN-101",
                    STEP_MANUAL_SLUG))
            .workflowRunId();

    // The per-step manual on the spec step parks via the unchanged 3d-3 path.
    assertEquals(WorkflowState.WAITING_FOR_MANUAL_EXECUTION.value(), currentState(runId));
    assertEquals(
        "awaiting_manual",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'manual.executionRequested'"
                + " and details->>'runnerKind' = 'manual' and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId));
  }

  @Test
  void perStepCodexOnSpecOverridesProjectWideManualAndEnqueues() {
    // Project-wide default is MANUAL (would park), but the per-step spec=codex mapping wins.
    seedProject(
        STEP_CODEX_SLUG, RunnerKind.MANUAL, Map.of(ProjectRunnerStep.SPEC, RunnerKind.CODEX));
    stubGitClone();

    String runId =
        commandService
            .submit(
                new SubmitWorkflowCommand(
                    "alex",
                    ActorType.HUMAN,
                    "idem-3e4-stepcodex",
                    "corr-3e4-stepcodex",
                    "LIN-102",
                    STEP_CODEX_SLUG))
            .workflowRunId();

    // The per-step codex mapping beats the project-wide manual default → normal enqueue, no park.
    assertEquals(WorkflowState.INVESTIGATING.value(), currentState(runId));
    assertEquals(
        "queued",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where status = 'awaiting_manual'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId));
  }

  private void seedProject(
      String slug, RunnerKind override, Map<ProjectRunnerStep, RunnerKind> stepRunnerKinds) {
    projectStore.insert(
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "Per-step " + slug,
            slug,
            ProjectStatus.ACTIVE,
            "octo/perstep",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            override,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            stepRunnerKinds));
  }

  private void stubGitClone() {
    RepositoryWorkspaceService.RepositoryMount mount =
        new RepositoryWorkspaceService.RepositoryMount(
            Paths.get("/tmp/repo"), "/workspace/repo", "main", "deliveryline/spec/stage-1");
    RepositoryContextSummary summary =
        new RepositoryContextSummary(
            "/workspace/repo", java.util.List.of(), "README.md", java.util.List.of(), "config:v1");
    when(repositoryWorkspaceService.prepareWorkspace(any(), any(), any(), any(), any(), any()))
        .thenReturn(mount);
    when(repositoryWorkspaceService.summarize(any(), any())).thenReturn(summary);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }
}
