package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ConnectorKind;
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
 * Story 3d-3 (AC8) — end-to-end proof over real-Postgres wiring that a run bound to a project whose
 * {@code runnerKind = manual} PARKS at the dispatch chokepoint instead of enqueuing a container:
 * state → {@code WaitingForManualExecution}, an {@code awaiting_manual} {@code runner_executions}
 * row (no worker/lease columns), the input bundle composed + written (readable via {@link
 * RunnerScratchStore}), a {@code manual.executionRequested} event with {@code runnerKind=manual},
 * and NO {@code queued} row / the runner adapter NEVER invoked (AC3/AC6). The parity test proves a
 * default-project run still enqueues (R7 byte-identical).
 *
 * <p>Opts into the auto-dispatch master switch so {@code submit} drives the real spec-stage
 * dispatch chokepoint ({@code WorkflowOrchestrationService.enqueueDispatch}); only the two leaf
 * adapters that cannot run in-process are mocked (the runner container + the git clone). Named
 * {@code *IT} so Failsafe runs it ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.spec-stage.auto-dispatch=true")
@Tag("integration")
class ManualExecutionParkIT {

  private static final String MANUAL_PROJECT_SLUG = "manual-park-proj";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private ProjectStore projectStore;
  @Autowired private RunnerScratchStore scratchStore;

  // The git clone is the only leaf that cannot run in-process; the runner adapter is mocked so we
  // can PROVE it is never invoked on the manual path.
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
    // Remove the manual test project AFTER its runs (FK fk_workflow_runs_projects); leave
    // `default`.
    jdbcTemplate.update("delete from projects where slug = ?", MANUAL_PROJECT_SLUG);
  }

  @Test
  void manualKindProjectParksTheRunInsteadOfEnqueuing() {
    Project manual =
        projectStore.insert(
            new Project(
                PublicIdPrefixes.PROJECT.next(),
                "Manual Project",
                MANUAL_PROJECT_SLUG,
                ProjectStatus.ACTIVE,
                "octo/manual",
                ConnectorKind.LINEAR,
                ConnectorKind.GITHUB,
                false,
                null,
                false,
                RunnerKind.MANUAL,
                OffsetDateTime.now(ZoneOffset.UTC),
                null));

    stubGitClone();

    SubmitWorkflowResult submit =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex",
                ActorType.HUMAN,
                "idem-3d3-manual-001",
                "corr-3d3-manual",
                "LIN-102",
                MANUAL_PROJECT_SLUG));
    String runId = submit.workflowRunId();

    assertEquals(manual.publicId(), projectStore.findProjectIdForRun(runId).orElse(null));

    // AC3 — the run is parked in WaitingForManualExecution (not Investigating-with-queued-row).
    assertEquals(WorkflowState.WAITING_FOR_MANUAL_EXECUTION.value(), currentState(runId));

    // AC5 — exactly one awaiting_manual runner_executions row, with NO worker/lease/queue columns
    // and no completed_at (non-terminal).
    assertEquals(1, runnerExecutionCount(runId));
    String rexId =
        jdbcTemplate.queryForObject(
            "select public_id from runner_executions where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            String.class,
            runId);
    assertEquals(
        "awaiting_manual",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where public_id = ?", String.class, rexId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where public_id = ?"
                + " and (worker_id is not null or dispatched_at is not null"
                + " or completed_at is not null or correlation_id is not null"
                + " or queue_attempt_count <> 0)",
            Integer.class,
            rexId));

    // AC3 — the input bundle was composed + written and is retrievable for 3d-4.
    assertTrue(scratchStore.tryReadContextBundle(rexId).isPresent());

    // AC4 — a manual.executionRequested event with runnerKind=manual was appended.
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'manual.executionRequested'"
                + " and details->>'runnerKind' = 'manual'"
                + " and details->>'runnerExecutionId' = ?"
                + " and details->>'workflowRunId' = ? and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            Integer.class,
            rexId,
            runId,
            runId));

    // AC6 — NO queued row, and the runner adapter was NEVER invoked (no container, no worker slot).
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from runner_executions where status = 'queued' and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId));
    verify(runnerAdapter, never()).dispatch(any());
  }

  @Test
  void defaultProjectRunStillEnqueuesAndNeverParks() {
    // R7 parity — no explicit project reference ⇒ the seeded `default` project (runnerKind null ⇒
    // global per-stage kind). The chokepoint takes the unchanged enqueue path: a queued row, no
    // park, no manual.executionRequested event.
    stubGitClone();

    SubmitWorkflowResult submit =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex", ActorType.HUMAN, "idem-3d3-default-001", "corr-3d3-default", "LIN-103"));
    String runId = submit.workflowRunId();

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
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'manual.executionRequested'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId));
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

  private int runnerExecutionCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }
}
