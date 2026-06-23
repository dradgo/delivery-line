package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.ManualArtifactSubmissionService.ManualArtifactSubmissionCommand;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ManualBundleLookupResult;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
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
 * Story 3d-4 (AC9) — end-to-end proof over real-Postgres wiring that an operator-submitted manual
 * artifact for a parked run re-enters the SAME validation/review pipeline as an automated runner's
 * output: it finalizes the parked {@code awaiting_manual} row to {@code completed}, ingests + marks
 * the artifact available, appends {@code manual.artifactSubmitted} with the OPERATOR identity, and
 * transitions the run OUT of {@code WaitingForManualExecution} into the stage-appropriate post-step
 * state ({@code WaitingForSpecApproval} for an INVESTIGATION-stage park) — all in one transaction.
 * Also covers AC5 (invalid resubmittable), idempotency, the wrong-state gate, and the run-scoped
 * bundle read.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.spec-stage.auto-dispatch=true")
@Tag("integration")
class ManualArtifactSubmissionIT {

  private static final String MANUAL_PROJECT_SLUG = "manual-submit-proj";
  private static final String SPEC_CONTENT_REF = "spec/v1.md";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private ProjectStore projectStore;
  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private ManualArtifactSubmissionService submissionService;

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
    jdbcTemplate.update("delete from projects where slug = ?", MANUAL_PROJECT_SLUG);
  }

  private String parkRun(String idempotencyKey, String ticket) {
    projectStore.insert(
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "Manual Submit Project",
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
    RepositoryWorkspaceService.RepositoryMount mount =
        new RepositoryWorkspaceService.RepositoryMount(
            Paths.get("/tmp/repo"), "/workspace/repo", "main", "deliveryline/spec/stage-1");
    RepositoryContextSummary summary =
        new RepositoryContextSummary(
            "/workspace/repo", java.util.List.of(), "README.md", java.util.List.of(), "config:v1");
    when(repositoryWorkspaceService.prepareWorkspace(any(), any(), any(), any(), any(), any()))
        .thenReturn(mount);
    when(repositoryWorkspaceService.summarize(any(), any())).thenReturn(summary);

    SubmitWorkflowResult submit =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex",
                ActorType.HUMAN,
                idempotencyKey,
                "corr-" + idempotencyKey,
                ticket,
                MANUAL_PROJECT_SLUG));
    String runId = submit.workflowRunId();
    assertEquals(WorkflowState.WAITING_FOR_MANUAL_EXECUTION.value(), currentState(runId));
    return runId;
  }

  private String parkedRex(String runId) {
    return jdbcTemplate.queryForObject(
        "select public_id from runner_executions where status = 'awaiting_manual'"
            + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
        String.class,
        runId);
  }

  private byte[] validSpecPayload(String runId, String rexId) {
    return ("""
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            { "artifactId": "art_manualspec1", "artifactType": "spec", "contentReference": "%s" }
          ],
          "normalizedOutput": { "summary": "operator spec", "outcome": "success" },
          "checksum": {
            "algorithm": "SHA-256",
            "hexDigest": "abababababababababababababababababababababababababababababababab"
          },
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """)
        .formatted(runId, rexId, SPEC_CONTENT_REF)
        .getBytes(StandardCharsets.UTF_8);
  }

  private ManualArtifactSubmissionCommand submitCommand(
      String runId, byte[] payload, String idempotencyKey) {
    return new ManualArtifactSubmissionCommand(
        runId,
        payload,
        Map.of(
            SPEC_CONTENT_REF,
            "# Operator Spec\n\nProduced by hand.".getBytes(StandardCharsets.UTF_8)),
        idempotencyKey,
        "operator-jane",
        ActorType.HUMAN,
        "corr-submit-1");
  }

  @Test
  void validSubmissionFinalizesParkIngestsArtifactAndTransitionsOut() {
    String runId = parkRun("idem-3d4-park-0000001", "LIN-102");
    String rexId = parkedRex(runId);

    WorkflowStateChangeResult result =
        submissionService.submit(
            submitCommand(runId, validSpecPayload(runId, rexId), "idem-3d4-submit-000001"));

    // INVESTIGATION park ⇒ WaitingForSpecApproval.
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL, result.currentState());
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));

    // The parked row finalized to completed with completed_at stamped.
    assertEquals(
        "completed",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where public_id = ?", String.class, rexId));
    assertNotNull(
        jdbcTemplate.queryForObject(
            "select completed_at from runner_executions where public_id = ?",
            OffsetDateTime.class,
            rexId));

    // The artifact was ingested + marked available.
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)"
                + " and artifact_type = 'spec' and status = 'available'",
            Integer.class,
            runId));

    // manual.artifactSubmitted appended with the OPERATOR identity (not system).
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'manual.artifactSubmitted'"
                + " and actor_identity = 'operator-jane'"
                + " and details->>'runnerExecutionId' = ? and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            Integer.class,
            rexId,
            runId));
  }

  @Test
  void invalidSubmissionLeavesRunParkedAndResubmittable() {
    String runId = parkRun("idem-3d4-park-0000002", "LIN-102");
    String rexId = parkedRex(runId);

    byte[] garbage = "{ not a runner result }".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        DomainException.class,
        () -> submissionService.submit(submitCommand(runId, garbage, "idem-3d4-submit-bad0001")));

    // AC5 — pure no-op: run still parked, row still awaiting_manual, no event.
    assertEquals(WorkflowState.WAITING_FOR_MANUAL_EXECUTION.value(), currentState(runId));
    assertEquals(
        "awaiting_manual",
        jdbcTemplate.queryForObject(
            "select status from runner_executions where public_id = ?", String.class, rexId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'manual.artifactSubmitted'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId));

    // A subsequent VALID resubmission (fresh key) succeeds.
    WorkflowStateChangeResult retry =
        submissionService.submit(
            submitCommand(runId, validSpecPayload(runId, rexId), "idem-3d4-submit-ok00001"));
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL, retry.currentState());
  }

  @Test
  void idempotentReplaySameKeyDoesNotDoubleApply() {
    String runId = parkRun("idem-3d4-park-0000003", "LIN-102");
    String rexId = parkedRex(runId);
    byte[] payload = validSpecPayload(runId, rexId);

    WorkflowStateChangeResult first =
        submissionService.submit(submitCommand(runId, payload, "idem-3d4-replay-000001"));
    WorkflowStateChangeResult replay =
        submissionService.submit(submitCommand(runId, payload, "idem-3d4-replay-000001"));

    assertEquals(first.currentState(), replay.currentState());
    // Exactly one manual.artifactSubmitted event despite two calls.
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'manual.artifactSubmitted'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            runId));
  }

  @Test
  void nonParkedRunIsManualExecutionNotApplicable() {
    // A default-project run never parks; submit must reject with the wrong-state code.
    SubmitWorkflowResult submit =
        commandService.submit(
            new SubmitWorkflowCommand(
                "alex", ActorType.HUMAN, "idem-3d4-default-na0001", "corr-na", "LIN-103"));
    String runId = submit.workflowRunId();

    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                submissionService.submit(
                    submitCommand(
                        runId,
                        validSpecPayload(runId, "rex_dummy0001"),
                        "idem-3d4-submit-na00001")));
    assertEquals(DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE, error.errorCode());
  }

  @Test
  void manualBundleReadServesPersistedRedactedBytes() {
    String runId = parkRun("idem-3d4-park-0000004", "LIN-102");
    String rexId = parkedRex(runId);

    ManualBundleLookupResult bundle = inspectionService.getManualBundle(runId);
    assertTrue(bundle.available());
    assertEquals(rexId, bundle.runnerExecutionId());
    assertTrue(bundle.bundle().redactedPayload().length > 0);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }
}
