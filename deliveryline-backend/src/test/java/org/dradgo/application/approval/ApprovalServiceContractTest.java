package org.dradgo.application.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.SpecificationArtifact;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 2.9 Task 6 / AC10(h): Spring-slice integration that drives the full {@code
 * WorkflowCommandService.approveSpec → ApprovalService → adapters → ApprovalReadPort} chain and
 * confirms the {@link ApprovalReadPort#findLatestApprovedForArtifactLineage} read port plus {@link
 * WorkflowInspectionService#getCurrentApprovedSpec} both surface the just-written row. The
 * unit-layer tests ({@code ApprovalServiceApproveSpecTest}) use mocks and the persistence-slice
 * tests ({@code ApprovalWritePersistenceAdapterContractTest}) bypass the service; this test pins
 * that the entire stack agrees end-to-end.
 *
 * <p>Added in story 2.9 review batch 1 (2026-05-24).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class ApprovalServiceContractTest {

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
  void approveSpecEndToEndIsVisibleToReadPortAndInspectionService() {
    String runId = insertRun("run_apsvccontract1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact(runId, "art_apsvccontract_v1");

    workflowCommandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            "art_apsvccontract_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-apsvccontract-1234567890",
            "corr-apsvccontract-1",
            "product_reviewer",
            null));

    // (h) ApprovalReadPort returns the just-written row.
    Optional<ApprovalSnapshot> latestApproved =
        approvalReadPort.findLatestApprovedForArtifactLineage(runId, ArtifactType.SPEC.value());
    assertTrue(latestApproved.isPresent(), "ApprovalReadPort must surface the just-written row");
    assertEquals("art_apsvccontract_v1", latestApproved.get().artifactId());
    assertEquals(1, latestApproved.get().artifactVersion());
    assertEquals("product_reviewer", latestApproved.get().reviewerRole());
    assertEquals(ApprovalSnapshot.DECISION_APPROVED, latestApproved.get().decision());

    // (h cont'd) WorkflowInspectionService.getCurrentApprovedSpec (story 2.8 read surface) also
    // sees the just-written row through the same ApprovalReadPort.
    Optional<SpecificationArtifact> currentApproved =
        workflowInspectionService.getCurrentApprovedSpec(runId);
    assertTrue(
        currentApproved.isPresent(),
        "WorkflowInspectionService.getCurrentApprovedSpec must surface the just-written row");
    assertEquals("art_apsvccontract_v1", currentApproved.get().id());
    assertEquals(1, currentApproved.get().version());

    // Run advanced WAITING_FOR_SPEC_APPROVAL → EXECUTING per the transition table.
    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
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
   * Mirrors {@code WorkflowCommandServiceContractTest.seedAvailableSpecArtifact} — seeds a SPEC
   * artifact whose payload bytes round-trip through {@code ArtifactPayloadStore} so {@code
   * ArtifactService.isApprovalEligible} passes. Bootstrap context-bundle path (no runnerExecutionId
   * in event details → current bundle version = 1).
   */
  private void seedAvailableSpecArtifact(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    byte[] payload = ("approval-eligible content for " + artifactPublicId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "spec.md", payload);
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
            + "values (?, ?, 'spec', 1, null, 'shareable-redacted', 'available', ?, 'SHA-256', ?, ?)",
        artifactPublicId,
        runId,
        storageRef,
        checksum,
        linkedEventId);
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
