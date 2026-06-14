package org.dradgo.application.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3.21 (AC10) — Spring-slice integration that drives the full {@code
 * WorkflowCommandService.rejectImplementation → TechnicalApprovalService → adapters} chain
 * end-to-end against a real database (the technical-rejection twin of {@code
 * TechnicalApprovalServiceContractTest}). Pins the implementationPlan + prOutput happy paths (both
 * → Executing), the persisted {@code decision=rejected} row with a non-null developer {@code
 * rejection_taxonomy} (AR34a), the {@code approval.rejected} event, the implementation-rejection
 * counter increment, idempotent replay, idempotency-key conflict, and the escalation-marker
 * threshold (a single escalation.required event once the counter crosses the threshold).
 *
 * <p>The unit layer ({@code TechnicalApprovalServiceRejectImplementationTest}) uses mocks; this
 * test pins the whole stack agrees against a real Postgres. Runner re-dispatch is a no-op under the
 * test profile (stage auto-dispatch off), so the transition + DB effects are asserted directly.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class TechnicalApprovalServiceRejectImplementationContractTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService workflowCommandService;

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
  void rejectImplementationPlanEndToEndTransitionsToExecuting() {
    String runId = insertRun("run_rejimplit_a01", WorkflowState.WAITING_FOR_REVIEW);
    seedArtifact(runId, "art_implplan_v1", ArtifactType.IMPLEMENTATION_PLAN);

    workflowCommandService.rejectImplementation(
        rejectCommand(
            runId,
            "art_implplan_v1",
            1,
            1,
            "idem-rejimpl-plan-1234567890",
            RejectionTaxonomy.INCORRECT_APPROACH));

    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
    assertEquals("rejected", latestDecision(runId));
    assertEquals("incorrect_approach", latestRejectionTaxonomy(runId));
    assertNotNull(latestRejectionTaxonomy(runId), "AR34a: rejection_taxonomy must be non-null");
    assertEquals(1, implementationLoopCount(runId));
    assertEquals(1, eventCount(runId, "approval.rejected"));
    assertEquals(0, eventCount(runId, "escalation.required"));
  }

  @Test
  void rejectPrOutputEndToEndTransitionsToExecuting() {
    String runId = insertRun("run_rejimplit_b01", WorkflowState.WAITING_FOR_REVIEW);
    seedArtifact(runId, "art_proutput_v1", ArtifactType.PR_OUTPUT);

    workflowCommandService.rejectImplementation(
        rejectCommand(
            runId,
            "art_proutput_v1",
            1,
            1,
            "idem-rejimpl-pr-1234567890",
            RejectionTaxonomy.BREAKS_EXISTING_FUNCTIONALITY));

    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
    assertEquals("rejected", latestDecision(runId));
    assertEquals("breaks_existing_functionality", latestRejectionTaxonomy(runId));
    assertEquals(1, implementationLoopCount(runId));
  }

  @Test
  void idempotentReplayInsertsRejectionOnlyOnce() {
    String runId = insertRun("run_rejimplit_c01", WorkflowState.WAITING_FOR_REVIEW);
    seedArtifact(runId, "art_implplan_c1", ArtifactType.IMPLEMENTATION_PLAN);
    RejectImplementationCommand command =
        rejectCommand(
            runId,
            "art_implplan_c1",
            1,
            1,
            "idem-rejimpl-replay-123456",
            RejectionTaxonomy.QUALITY_ISSUE);

    var first = workflowCommandService.rejectImplementation(command);
    var second = workflowCommandService.rejectImplementation(command);

    assertEquals(WorkflowState.EXECUTING, first.currentState());
    assertEquals(WorkflowState.EXECUTING, second.currentState());
    Integer approvalRows =
        jdbcTemplate.queryForObject(
            "select count(*) from approvals a join workflow_runs r on a.workflow_run_id = r.id "
                + "where r.public_id = ?",
            Integer.class,
            runId);
    assertEquals(1, approvalRows, "idempotent replay must NOT insert a second rejection row");
    assertEquals(1, implementationLoopCount(runId), "replay must NOT double-increment the counter");
  }

  @Test
  void idempotencyKeyConflictOnDifferentFingerprint() {
    String runId = insertRun("run_rejimplit_d01", WorkflowState.WAITING_FOR_REVIEW);
    seedArtifact(runId, "art_implplan_d1", ArtifactType.IMPLEMENTATION_PLAN);
    String sharedKey = "idem-rejimpl-conflict-1234";

    workflowCommandService.rejectImplementation(
        rejectCommand(
            runId, "art_implplan_d1", 1, 1, sharedKey, RejectionTaxonomy.INCORRECT_APPROACH));

    // Same key, different taggedFeedback -> different fingerprint -> conflict at the reservation
    // gate.
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                workflowCommandService.rejectImplementation(
                    rejectCommand(
                        runId,
                        "art_implplan_d1",
                        1,
                        1,
                        sharedKey,
                        RejectionTaxonomy.QUALITY_ISSUE)));
    assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
  }

  @Test
  void escalationRequiredEmittedOnceWhenThresholdCrossed() {
    String runId = insertRun("run_rejimplit_e01", WorkflowState.WAITING_FOR_REVIEW);
    seedArtifact(runId, "art_implplan_e1", ArtifactType.IMPLEMENTATION_PLAN);

    // Three rejections cross the default threshold (3). The run lands in Executing each time; reset
    // it back to WaitingForReview between rejections so each one re-enters the legal review edge.
    for (int i = 1; i <= 3; i++) {
      workflowCommandService.rejectImplementation(
          rejectCommand(
              runId,
              "art_implplan_e1",
              1,
              1,
              "idem-rejimpl-escalate-" + i,
              RejectionTaxonomy.QUALITY_ISSUE));
      if (i < 3) {
        resetToWaitingForReview(runId);
      }
    }

    assertEquals(3, implementationLoopCount(runId));
    assertEquals(3, eventCount(runId, "approval.rejected"));
    assertEquals(1, eventCount(runId, "escalation.required"), "escalation must fire exactly once");
    assertEquals(Boolean.TRUE, escalationMarkerSet(runId));
  }

  // ---------------------------------------------------------------------------
  // Seed + query helpers
  // ---------------------------------------------------------------------------

  private RejectImplementationCommand rejectCommand(
      String runId,
      String artifactId,
      int artifactVersion,
      int contextVersion,
      String idemKey,
      RejectionTaxonomy taggedFeedback) {
    return new RejectImplementationCommand(
        runId,
        artifactId,
        artifactVersion,
        contextVersion,
        "dev-alex",
        ActorType.HUMAN,
        idemKey,
        "corr-rejimpl-1",
        "developer",
        taggedFeedback,
        "Implementation does not meet the bar");
  }

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  private void resetToWaitingForReview(String runId) {
    jdbcTemplate.update(
        "update workflow_runs set current_state = ? where public_id = ?",
        WorkflowState.WAITING_FOR_REVIEW.value(),
        runId);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private int implementationLoopCount(String runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select implementation_rejection_loop_count from workflow_runs where public_id = ?",
            Integer.class,
            runId);
    return count == null ? -1 : count;
  }

  private Boolean escalationMarkerSet(String runId) {
    return jdbcTemplate.queryForObject(
        "select escalation_marker_set from workflow_runs where public_id = ?",
        Boolean.class,
        runId);
  }

  private String latestDecision(String runId) {
    return jdbcTemplate.queryForObject(
        "select a.decision from approvals a join workflow_runs r on a.workflow_run_id = r.id "
            + "where r.public_id = ? order by a.decided_at desc, a.id desc limit 1",
        String.class,
        runId);
  }

  private String latestRejectionTaxonomy(String runId) {
    return jdbcTemplate.queryForObject(
        "select a.rejection_taxonomy from approvals a join workflow_runs r on a.workflow_run_id = r.id "
            + "where r.public_id = ? order by a.decided_at desc, a.id desc limit 1",
        String.class,
        runId);
  }

  private int eventCount(String runId, String eventType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e join workflow_runs r on e.workflow_run_id = r.id "
                + "where r.public_id = ? and e.event_type = ?",
            Integer.class,
            runId,
            eventType);
    return count == null ? -1 : count;
  }

  private void seedArtifact(String runPublicId, String artifactPublicId, ArtifactType type) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    String evtPublicId = "evt_seed" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);

    // Rejection does NOT check approval-eligibility (Decision D3/OQ-1), so the artifact need only
    // exist at the bound version — payload bytes / availability are irrelevant here.
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, storage_ref, checksum_algorithm, checksum_value, linked_event_id) "
            + "values (?, ?, ?, 1, null, 'shareable-redacted', 'available', ?, 'SHA-256', ?, ?)",
        artifactPublicId,
        runId,
        type.value(),
        "scratch://impl/" + artifactPublicId,
        "abc123def456",
        linkedEventId);
  }
}
