package org.dradgo.application.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowRunDetailedSummaryView;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3.20 (AC13) — Spring-slice integration that drives the full {@code
 * WorkflowCommandService.acceptImplementation → TechnicalApprovalService → adapters →
 * ApprovalReadPort} chain end-to-end. The twin of {@code ApprovalServiceContractTest}. Pins the
 * implementation-plan (→ Executing) and pr-output (→ Completed) happy paths, idempotent replay,
 * idempotency-key conflict, and the FR21 separated {@code productApprovalState}/{@code
 * technicalApprovalState} surfaced by {@code getRunSummary}.
 *
 * <p>The unit layer ({@code TechnicalApprovalServiceAcceptImplementationTest}) uses mocks; this
 * test pins the entire stack agrees against a real database.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class TechnicalApprovalServiceContractTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService workflowCommandService;
  @Autowired private WorkflowInspectionService workflowInspectionService;
  @Autowired private ApprovalReadPort approvalReadPort;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void acceptImplementationPlanEndToEndTransitionsToExecuting() {
    String runId = insertRun("run_acceptimplit_a01", WorkflowState.WAITING_FOR_REVIEW);
    seedAvailableArtifact(runId, "art_implplan_v1", ArtifactType.IMPLEMENTATION_PLAN);

    workflowCommandService.acceptImplementation(
        acceptCommand(runId, "art_implplan_v1", 1, 1, "idem-acceptimpl-plan-1234567890"));

    Optional<ApprovalSnapshot> latest =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            runId, ArtifactType.IMPLEMENTATION_PLAN.value());
    assertTrue(latest.isPresent(), "ApprovalReadPort must surface the just-written plan approval");
    assertEquals("developer", latest.get().reviewerRole());
    assertEquals(ApprovalSnapshot.DECISION_APPROVED, latest.get().decision());

    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));

    // FR21 separation — technical APPROVED, product NONE (no spec approval on this run).
    WorkflowRunDetailedSummaryView summary = workflowInspectionService.getRunSummary(runId);
    assertEquals("APPROVED", summary.technicalApprovalState());
    assertEquals("NONE", summary.productApprovalState());
  }

  @Test
  void acceptPrOutputEndToEndTransitionsToCompleted() {
    String runId = insertRun("run_acceptimplit_b01", WorkflowState.WAITING_FOR_REVIEW);
    seedAvailableArtifact(runId, "art_proutput_v1", ArtifactType.PR_OUTPUT);
    seedActiveGitHubPrLink(runId, "ilk_proutput_b01", "octo/widgets#42");

    workflowCommandService.acceptImplementation(
        acceptCommand(runId, "art_proutput_v1", 1, 1, "idem-acceptimpl-pr-1234567890"));

    Optional<ApprovalSnapshot> latest =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            runId, ArtifactType.PR_OUTPUT.value());
    assertTrue(
        latest.isPresent(), "ApprovalReadPort must surface the just-written prOutput approval");
    assertEquals("developer", latest.get().reviewerRole());

    assertEquals(WorkflowState.COMPLETED.value(), currentState(runId));

    WorkflowRunDetailedSummaryView summary = workflowInspectionService.getRunSummary(runId);
    assertEquals("APPROVED", summary.technicalApprovalState());
  }

  @Test
  void idempotentReplayInsertsApprovalOnlyOnce() {
    String runId = insertRun("run_acceptimplit_c01", WorkflowState.WAITING_FOR_REVIEW);
    seedAvailableArtifact(runId, "art_implplan_c1", ArtifactType.IMPLEMENTATION_PLAN);
    AcceptImplementationCommand command =
        acceptCommand(runId, "art_implplan_c1", 1, 1, "idem-acceptimpl-replay-123456");

    var first = workflowCommandService.acceptImplementation(command);
    var second = workflowCommandService.acceptImplementation(command);

    assertEquals(WorkflowState.EXECUTING, first.currentState());
    assertEquals(WorkflowState.EXECUTING, second.currentState());
    Integer approvalRows =
        jdbcTemplate.queryForObject(
            "select count(*) from approvals a join workflow_runs r on a.workflow_run_id = r.id "
                + "where r.public_id = ?",
            Integer.class,
            runId);
    assertEquals(1, approvalRows, "idempotent replay must NOT insert a second approval row");
  }

  @Test
  void idempotencyKeyConflictOnDifferentFingerprint() {
    String runId = insertRun("run_acceptimplit_d01", WorkflowState.WAITING_FOR_REVIEW);
    seedAvailableArtifact(runId, "art_implplan_d1", ArtifactType.IMPLEMENTATION_PLAN);
    String sharedKey = "idem-acceptimpl-conflict-1234";

    workflowCommandService.acceptImplementation(
        acceptCommand(runId, "art_implplan_d1", 1, 1, sharedKey));

    // Same idempotency key, different artifactVersion -> different fingerprint -> conflict at the
    // reservation gate (before TechnicalApprovalService even runs).
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                workflowCommandService.acceptImplementation(
                    acceptCommand(runId, "art_implplan_d1", 2, 1, sharedKey)));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  // ---------------------------------------------------------------------------
  // Seed helpers
  // ---------------------------------------------------------------------------

  private AcceptImplementationCommand acceptCommand(
      String runId, String artifactId, int artifactVersion, int contextVersion, String idemKey) {
    return new AcceptImplementationCommand(
        runId,
        artifactId,
        artifactVersion,
        contextVersion,
        "dev-alex",
        ActorType.HUMAN,
        idemKey,
        "corr-acceptimpl-1",
        "developer",
        null);
  }

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  /**
   * Seeds an {@code available} implementation artifact whose payload round-trips through {@code
   * ArtifactPayloadStore} so {@code ArtifactService.isApprovalEligible} passes. Bootstrap
   * context-bundle path (no runnerExecutionId in the linked event → current bundle version = 1).
   */
  private void seedAvailableArtifact(
      String runPublicId, String artifactPublicId, ArtifactType type) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    byte[] payload = ("approval-eligible content for " + artifactPublicId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "artifact.md", payload);
    String checksum = sha256Hex(payload);

    String evtPublicId = "evt_seed" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);

    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, storage_ref, checksum_algorithm, checksum_value, linked_event_id) "
            + "values (?, ?, ?, 1, null, 'shareable-redacted', 'available', ?, 'SHA-256', ?, ?)",
        artifactPublicId,
        runId,
        type.value(),
        storageRef,
        checksum,
        linkedEventId);
  }

  private void seedActiveGitHubPrLink(String runPublicId, String linkPublicId, String externalRef) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    jdbcTemplate.update(
        "insert into integration_links "
            + "(public_id, workflow_run_id, integration_type, external_ref, external_metadata, "
            + "sync_status, created_at) "
            + "values (?, ?, 'github_pr', ?, '{}'::jsonb, 'linked', now())",
        linkPublicId,
        runId,
        externalRef);
  }

  private static String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }
}
