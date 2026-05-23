package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
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
 * Real-Postgres regression for {@link ApprovalReadPersistenceAdapter} (story 2.8 AC10). Seeds
 * approvals + supporting artifacts/runs via direct JDBC inserts since the {@code ApprovalService}
 * writer is intentionally out of scope for this story (ships in 2.9 / 2.10).
 *
 * <p>Asserts the three port contract methods return the expected shape, decision filtering, and
 * chronological ordering, AND that the {@code archived_at IS NULL} filter (trap T7) is honored —
 * tombstoned rows must never appear in any of the three reads.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class ApprovalReadPersistenceAdapterTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApprovalReadPort approvalReadPort;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void findLatestApprovedReturnsMostRecentApprovedOnlyForType() {
    insertRun("run_approval1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long specV1 = insertArtifact("run_approval1234", "art_specv1abcd", ArtifactType.SPEC, 1, null);
    Long specV2 =
        insertArtifact("run_approval1234", "art_specv2abcd", ArtifactType.SPEC, 2, specV1);
    insertApproval(
        "apr_v1reject001",
        "run_approval1234",
        specV1,
        1,
        ApprovalSnapshot.DECISION_REJECTED,
        "missing_scope",
        "2026-05-10T10:00:00Z");
    insertApproval(
        "apr_v2approve01",
        "run_approval1234",
        specV2,
        2,
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        "2026-05-11T10:00:00Z");

    Optional<ApprovalSnapshot> result =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            "run_approval1234", ArtifactType.SPEC.value());

    assertTrue(result.isPresent());
    assertEquals("apr_v2approve01", result.get().publicId());
    assertEquals(2, result.get().artifactVersion());
    assertTrue(result.get().isApproved());
  }

  @Test
  void findLatestApprovedReturnsEmptyWhenNoApprovedRows() {
    insertRun("run_norows1234", WorkflowState.INVESTIGATING);
    Long specV1 = insertArtifact("run_norows1234", "art_speconly1234", ArtifactType.SPEC, 1, null);
    insertApproval(
        "apr_onlyreject01",
        "run_norows1234",
        specV1,
        1,
        ApprovalSnapshot.DECISION_REJECTED,
        "unclear_specification",
        "2026-05-12T10:00:00Z");

    Optional<ApprovalSnapshot> result =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            "run_norows1234", ArtifactType.SPEC.value());

    assertTrue(result.isEmpty());
  }

  @Test
  void findLatestApprovedPrefersHighestApprovedArtifactVersionOverLaterApprovalTimestamp() {
    insertRun("run_latestpref1", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long specV1 = insertArtifact("run_latestpref1", "art_prefv1abcd", ArtifactType.SPEC, 1, null);
    Long specV2 = insertArtifact("run_latestpref1", "art_prefv2abcd", ArtifactType.SPEC, 2, specV1);
    insertApproval(
        "apr_pref_v2_app",
        "run_latestpref1",
        specV2,
        2,
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        "2026-05-10T10:00:00Z");
    insertApproval(
        "apr_pref_v1_app",
        "run_latestpref1",
        specV1,
        1,
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        "2026-05-11T10:00:00Z");

    Optional<ApprovalSnapshot> result =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            "run_latestpref1", ArtifactType.SPEC.value());

    assertTrue(result.isPresent());
    assertEquals("apr_pref_v2_app", result.get().publicId());
    assertEquals(2, result.get().artifactVersion());
  }

  @Test
  void listByTypeReturnsChronologicalOrderRegardlessOfDecision() {
    insertRun("run_hist1234567", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long v1 = insertArtifact("run_hist1234567", "art_histv1abcd0", ArtifactType.SPEC, 1, null);
    Long v2 = insertArtifact("run_hist1234567", "art_histv2abcd0", ArtifactType.SPEC, 2, v1);
    insertApproval(
        "apr_hist_v1_rej",
        "run_hist1234567",
        v1,
        1,
        ApprovalSnapshot.DECISION_REJECTED,
        "missing_scope",
        "2026-05-01T10:00:00Z");
    insertApproval(
        "apr_hist_v2_app",
        "run_hist1234567",
        v2,
        2,
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        "2026-05-02T10:00:00Z");

    List<ApprovalSnapshot> result =
        approvalReadPort.listByWorkflowRunAndArtifactType(
            "run_hist1234567", ArtifactType.SPEC.value());

    assertEquals(2, result.size());
    assertEquals("apr_hist_v1_rej", result.get(0).publicId());
    assertTrue(result.get(0).isRejected());
    assertEquals("apr_hist_v2_app", result.get(1).publicId());
    assertTrue(result.get(1).isApproved());
  }

  @Test
  void listRejectionsFiltersToRejectedOnly() {
    insertRun("run_rej12345678", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long v1 = insertArtifact("run_rej12345678", "art_rejv1abcdef", ArtifactType.SPEC, 1, null);
    Long v2 = insertArtifact("run_rej12345678", "art_rejv2abcdef", ArtifactType.SPEC, 2, v1);
    insertApproval(
        "apr_rej_v1_rej",
        "run_rej12345678",
        v1,
        1,
        ApprovalSnapshot.DECISION_REJECTED,
        "missing_scope",
        "2026-05-03T10:00:00Z");
    insertApproval(
        "apr_rej_v2_app",
        "run_rej12345678",
        v2,
        2,
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        "2026-05-04T10:00:00Z");

    List<ApprovalSnapshot> result =
        approvalReadPort.listRejectionsByWorkflowRunAndArtifactType(
            "run_rej12345678", ArtifactType.SPEC.value());

    assertEquals(1, result.size());
    assertEquals("apr_rej_v1_rej", result.get(0).publicId());
    assertTrue(result.get(0).isRejected());
  }

  @Test
  void archivedRowsAreInvisibleToAllReadMethods() {
    insertRun("run_tomb12345678", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long v1 = insertArtifact("run_tomb12345678", "art_tombv1abcde", ArtifactType.SPEC, 1, null);
    insertApproval(
        "apr_tomb_v1_app",
        "run_tomb12345678",
        v1,
        1,
        ApprovalSnapshot.DECISION_APPROVED,
        null,
        "2026-05-05T10:00:00Z");
    // Tombstone the row.
    jdbcTemplate.update(
        "update approvals set archived_at = now() where public_id = ?", "apr_tomb_v1_app");

    assertTrue(
        approvalReadPort
            .findLatestApprovedForArtifactLineage("run_tomb12345678", ArtifactType.SPEC.value())
            .isEmpty());
    assertTrue(
        approvalReadPort
            .listByWorkflowRunAndArtifactType("run_tomb12345678", ArtifactType.SPEC.value())
            .isEmpty());
    assertTrue(
        approvalReadPort
            .listRejectionsByWorkflowRunAndArtifactType(
                "run_tomb12345678", ArtifactType.SPEC.value())
            .isEmpty());
  }

  // =============================================================================================
  // JDBC seed helpers — direct inserts since story 2.8 ships no writers.
  // =============================================================================================

  private void insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
  }

  private Long insertArtifact(
      String workflowRunPublicId,
      String publicId,
      ArtifactType type,
      int version,
      Long parentArtifactId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    String evtPublicId = "evt_apprsd" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);
    return jdbcTemplate.queryForObject(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, linked_event_id) values (?, ?, ?, ?, ?, ?, 'available', ?) "
            + "returning id",
        Long.class,
        publicId,
        runId,
        type.value(),
        version,
        parentArtifactId,
        type.defaultClassification().value(),
        linkedEventId);
  }

  private void insertApproval(
      String publicId,
      String workflowRunPublicId,
      Long artifactId,
      int artifactVersion,
      String decision,
      String rejectionTaxonomy,
      String decidedAtIso) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    jdbcTemplate.update(
        "insert into approvals (public_id, workflow_run_id, artifact_id, artifact_version, "
            + "context_bundle_version, actor_identity, actor_type, reviewer_role, decision, reason, "
            + "rejection_taxonomy, decided_at, idempotency_key) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?)",
        publicId,
        runId,
        artifactId,
        artifactVersion,
        artifactVersion,
        "human-pm",
        "human",
        "product_owner",
        decision,
        ApprovalSnapshot.DECISION_REJECTED.equals(decision) ? "needs more detail" : null,
        rejectionTaxonomy,
        decidedAtIso,
        publicId + "-idem");
  }
}
