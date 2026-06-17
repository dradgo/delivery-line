package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3.11 (AC11) — end-to-end plan-stage orchestration over the real wiring (Testcontainers
 * Postgres + mock runner {@code happy-implementation-plan}). The EXECUTION analog of {@link
 * SpecStageOrchestrationIT}: it seeds an approval-eligible spec artifact directly (to skip the spec
 * stage and start at the plan stage), approves the spec to fire the {@code WaitingForSpecApproval
 * -> Executing} transition + the {@code dispatchPlanGeneration} trigger, then drives the async plan
 * result deterministically via {@link RunnerBroker#pollActiveExecutions()} (Trap T6 — never sleep
 * on the 5s scheduler) and asserts the run auto-advanced {@code Executing -> WaitingForReview}.
 *
 * <p>Opts into the plan-stage auto-dispatch master switch via {@link TestPropertySource} (the
 * shared test profile keeps it OFF, Trap T11). Like the spec IT, the deterministic failure-mode →
 * Failed (AC4), artifact-type mismatch (AC8), correlationId threading (AC7), and the orchestration
 * key/in-flight/idempotent-replay shapes are covered without Testcontainers by {@code
 * WorkflowOrchestrationServiceTest}, {@code RunnerBrokerUnitTest}, and {@code
 * WorkflowTransitionTableTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(properties = "deliveryline.runner.plan-stage.auto-dispatch=true")
class ImplementationPlanOrchestrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private WorkflowOrchestrationService orchestrationService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;

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
  }

  @Test
  void approveSpecAutoDispatchesPlanThenWaitingForReviewOnPoll() {
    // Precondition (Task 6): a run at WaitingForSpecApproval with an approval-eligible spec
    // artifact.
    String runId = insertRun("run_planit0000happy", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact(runId, "art_planit_happy_v1");

    // AC1: approveSpec transitions WaitingForSpecApproval -> Executing AND, inside the same
    // transaction, dispatches the implementation-plan runner (plan-stage.auto-dispatch=true).
    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            "art_planit_happy_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-planit-happy-12345",
            "corr-planit-happy",
            "product_reviewer",
            null));

    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
    assertEquals(1, executionCount(runId));
    // Story 3.17b: the plan auto-dispatch enqueues, so the fresh row sits at `queued`.
    assertEquals("queued", executionStatus(runId));

    // Drive the async EXECUTION result deterministically (Trap T6) — lease+dispatch the queued row
    // as a worker would, then harvest the mock happy-implementation-plan result.
    drainQueue();
    runnerBroker.pollActiveExecutions();

    // AC2/AC3: the implementation-plan artifact is ingested and the run auto-advanced to
    // WaitingForReview.
    assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));
    assertEquals("completed", executionStatus(runId));
    // Story 3b-3 (AC6, supersedes 3a-9 Decision-D1): the implementation-plan artifact is now marked
    // `available` on ingest (broker ingest-loop markArtifactAvailable) so the acceptImplementation
    // eligibility gate (isApprovalEligible = AVAILABLE) accepts it. The auto-advance still fires on
    // successful INGEST regardless. (queryForObject also asserts exactly one such artifact row.)
    assertEquals(
        "available",
        jdbcTemplate.queryForObject(
            "select status from artifacts where workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)"
                + " and artifact_type = 'implementationPlan'",
            String.class,
            runId));
  }

  @Test
  void reDispatchPlanWhileInFlightIsAnIdempotentNoOp() {
    // AC6: a duplicate plan dispatch for the same run while the EXECUTION execution is still
    // pending
    // replays — no second runner execution.
    String runId = insertRun("run_planit0000idem", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact(runId, "art_planit_idem_v1");
    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            "art_planit_idem_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-planit-idem-12345",
            "corr-planit-idem",
            "product_reviewer",
            null));
    assertEquals(1, executionCount(runId));

    orchestrationService.dispatchPlanGeneration(runId, "corr-planit-idem");

    assertEquals(1, executionCount(runId));
    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
  }

  @Test
  void retryPlanGenerationDispatchesAFreshRunnerExecution() {
    // AC5: a retry (no in-flight EXECUTION execution) re-dispatches with a fresh runnerExecutionId
    // +
    // advanced context-bundle version, preserving the prior execution for audit.
    String runId = insertRun("run_planit0000retry", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact(runId, "art_planit_retry_v1");
    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            "art_planit_retry_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-planit-retry-12345",
            "corr-planit-retry",
            "product_reviewer",
            null));
    drainQueue();
    runnerBroker.pollActiveExecutions();
    assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));
    assertEquals(1, executionCount(runId));

    orchestrationService.retryPlanGeneration(runId, "corr-planit-retry");

    // A SECOND EXECUTION execution was dispatched (the prior completed one is preserved).
    assertEquals(2, executionCount(runId));
    assertTrue(
        jdbcTemplate.queryForObject(
                "select count(*) from runner_executions where status = 'completed'"
                    + " and workflow_run_id ="
                    + " (select id from workflow_runs where public_id = ?)",
                Integer.class,
                runId)
            >= 1);
  }

  // ---------------------------------------------------------------------------------------------

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
   * Story 3.17b — plan auto-dispatch now ENQUEUES; the worker pool (off in the test profile, Trap
   * T11) leases + dispatches queued rows. Drive that leg deterministically here exactly as a worker
   * would ({@code dequeue} → {@code executeQueuedDispatch}) so the subsequent {@code
   * pollActiveExecutions()} can harvest the mock result (Trap T6: no async pool, no sleeping).
   */
  private void drainQueue() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-plan-worker")).isPresent()) {
      runnerBroker.executeQueuedDispatch(leased.get());
    }
  }

  private int executionCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from runner_executions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }

  private String executionStatus(String runId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?) order by id desc limit 1",
        String.class,
        runId);
  }

  /**
   * Mirrors {@code ApprovalServiceContractTest.seedAvailableSpecArtifact} — seeds a SPEC artifact
   * whose payload bytes round-trip through {@code ArtifactPayloadStore} so {@code
   * ArtifactService.isApprovalEligible} passes (the mock spec path leaves the artifact {@code
   * pending}). Bootstrap context-bundle path (no runnerExecutionId in event details → bundle
   * version 1).
   */
  private void seedAvailableSpecArtifact(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    byte[] payload = ("approval-eligible content for " + artifactPublicId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "spec.md", payload);
    String checksum = sha256Hex(payload);

    String evtPublicId = "evt_planseed" + System.nanoTime();
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
