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
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.workspace.RepositoryContextSummary;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ManualBundleLookupResult;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
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
@TestPropertySource(
    properties = {
      "deliveryline.runner.spec-stage.auto-dispatch=true",
      // Story 3d-4 re-review (chunk 2) — opt into the plan-stage auto-dispatch master switch so an
      // approveSpec on a MANUAL-kind project re-parks at the EXECUTION (plan) stage, exercising the
      // EXECUTION arm of ingestManualResult through to WaitingForReview (AC9 #5).
      "deliveryline.runner.plan-stage.auto-dispatch=true"
    })
@Tag("integration")
class ManualArtifactSubmissionIT {

  private static final String MANUAL_PROJECT_SLUG = "manual-submit-proj";
  private static final String SPEC_CONTENT_REF = "spec/v1.md";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private ProjectStore projectStore;
  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private ManualArtifactSubmissionService submissionService;
  @Autowired private RunnerScratchStore scratchStore;

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

  @Test
  void sameKeyConflictsWhenADifferentArtifactIsSubmittedUnderOneKey() {
    // AC9 #3 (conflict half) — driven through the REAL idempotency store, not a stubbed throw.
    String runId = parkRun("idem-3d4-park-0000006", "LIN-102");
    String rexId = parkedRex(runId);

    // First valid submission under key K succeeds (run advances out of WaitingForManualExecution).
    submissionService.submit(
        submitCommand(runId, validSpecPayload(runId, rexId), "idem-3d4-conflict-key01"));

    // A DIFFERENT artifact (changed inline content ⇒ different fingerprint) under the SAME key K
    // must
    // raise IDEMPOTENCY_KEY_CONFLICT — the conflict is detected at the idempotency step before the
    // applicability gate, so the already-advanced run does not mask it.
    ManualArtifactSubmissionCommand conflicting =
        new ManualArtifactSubmissionCommand(
            runId,
            validSpecPayload(runId, rexId),
            Map.of(
                SPEC_CONTENT_REF,
                "# Operator Spec\n\nDIFFERENT body.".getBytes(StandardCharsets.UTF_8)),
            "idem-3d4-conflict-key01",
            "operator-jane",
            ActorType.HUMAN,
            "corr-submit-1");
    DomainException error =
        assertThrows(DomainException.class, () -> submissionService.submit(conflicting));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void invalidSubmissionReleasesTheKeySoSameKeyResubmitSucceeds() {
    // AC5 (exact promise) — an invalid submission rolls the reservation back, so the SAME key is
    // free for an honest valid resubmission (the prior IT only proved a FRESH key).
    String runId = parkRun("idem-3d4-park-0000007", "LIN-102");
    String rexId = parkedRex(runId);
    String key = "idem-3d4-samekey-retry1";

    byte[] garbage = "{ not a runner result }".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        DomainException.class, () -> submissionService.submit(submitCommand(runId, garbage, key)));
    // Run untouched.
    assertEquals(WorkflowState.WAITING_FOR_MANUAL_EXECUTION.value(), currentState(runId));

    // Resubmit a VALID payload under the SAME key — succeeds (reservation was released on
    // rollback).
    WorkflowStateChangeResult retry =
        submissionService.submit(submitCommand(runId, validSpecPayload(runId, rexId), key));
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL, retry.currentState());
  }

  @Test
  void manualBundleDegradesToBundleNotPersistedWhenScratchEvicted() {
    // AC1 / R3 — the real getManualBundle read serves a typed unavailable reason (NOT a 500) when
    // the
    // scratch bundle has been evicted. Driven against the real scratch store, not a mocked service.
    String runId = parkRun("idem-3d4-park-0000008", "LIN-102");
    String rexId = parkedRex(runId);

    // Evict the persisted bundle the park wrote (scratch is not durable).
    scratchStore.deleteContextBundle(rexId);

    ManualBundleLookupResult bundle = inspectionService.getManualBundle(runId);
    assertEquals(false, bundle.available());
    assertEquals(rexId, bundle.runnerExecutionId());
    assertEquals("bundleNotPersisted", bundle.reason());
  }

  @Test
  void executionStageManualPlanReachesWaitingForReviewAndIsAcceptedLikeAnAutomatedArtifact() {
    // AC9 #5 / R7 — the load-bearing e2e: an operator-submitted EXECUTION-stage artifact re-enters
    // the SAME review pipeline as an automated runner's output and the downstream review loop
    // accepts
    // it identically. This is the only test that exercises the EXECUTION arm of ingestManualResult
    // (markArtifactAvailable for implementationPlan) end-to-end through WaitingForReview.
    String runId = parkRun("idem-3d4-park-0000009", "LIN-102");
    String specRex = parkedRex(runId);

    // 1. INVESTIGATION manual spec submission → WaitingForSpecApproval.
    submissionService.submit(
        submitCommand(runId, validSpecPayload(runId, specRex), "idem-3d4-execflow-spec1"));
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), currentState(runId));

    // 2. approveSpec → Executing → dispatchPlanGeneration → MANUAL kind re-parks the plan
    // (EXECUTION)
    // stage → WaitingForManualExecution with a SECOND awaiting_manual row.
    String specArtifactId = availableArtifactId(runId, "spec");
    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            specArtifactId,
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-3d4-execflow-approve1",
            "corr-execflow-approve",
            "product_reviewer",
            null));
    assertEquals(WorkflowState.WAITING_FOR_MANUAL_EXECUTION.value(), currentState(runId));

    String planRex = parkedRex(runId);
    assertEquals(
        "execution",
        jdbcTemplate.queryForObject(
            "select stage from runner_executions where public_id = ?", String.class, planRex));

    // 3. Submit the operator-produced implementation plan → ingest EXECUTION arm →
    // WaitingForReview.
    WorkflowStateChangeResult planResult =
        submissionService.submit(
            new ManualArtifactSubmissionCommand(
                runId,
                validPlanPayload(runId, planRex),
                Map.of(),
                "idem-3d4-execflow-plan1",
                "operator-jane",
                ActorType.HUMAN,
                "corr-execflow-plan"));
    assertEquals(WorkflowState.WAITING_FOR_REVIEW, planResult.currentState());

    // The plan artifact is ingested + marked available — the SAME approval-eligibility an automated
    // plan produces, so the downstream review queue sees it identically.
    String planArtifactId = availableArtifactId(runId, "implementationPlan");

    // 4. The downstream review loop ACCEPTS the manual artifact exactly as an automated one —
    // proving
    // the manual path re-enters the same review pipeline (the run leaves WaitingForReview).
    WorkflowStateChangeResult accepted =
        commandService.acceptImplementation(
            new AcceptImplementationCommand(
                runId,
                planArtifactId,
                1,
                1,
                "dev-dan",
                ActorType.HUMAN,
                "idem-3d4-execflow-accept1",
                "corr-execflow-accept",
                "developer",
                null));
    org.junit.jupiter.api.Assertions.assertNotEquals(
        WorkflowState.WAITING_FOR_REVIEW, accepted.currentState());
  }

  private byte[] validPlanPayload(String runId, String rexId) {
    return ("""
        {
          "schemaVersion": 1,
          "workflowRunId": "%s",
          "runnerExecutionId": "%s",
          "artifactReferences": [
            {
              "artifactId": "art_manualplan1",
              "artifactType": "implementationPlan",
              "steps": ["Operator step one", "Operator step two"],
              "contextReferences": ["ctx/ref/1"]
            }
          ],
          "normalizedOutput": { "summary": "operator plan", "outcome": "success" },
          "checksum": {
            "algorithm": "SHA-256",
            "hexDigest": "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
          },
          "classification": "shareable-redacted",
          "failureCategory": null
        }
        """)
        .formatted(runId, rexId)
        .getBytes(StandardCharsets.UTF_8);
  }

  private String availableArtifactId(String runId, String artifactType) {
    return jdbcTemplate.queryForObject(
        "select public_id from artifacts where artifact_type = ? and status = 'available'"
            + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
        String.class,
        artifactType,
        runId);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }
}
