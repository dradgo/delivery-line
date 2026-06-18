package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.MockRunnerAdapter;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.runner.ContextBundleService;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3b.2 (the headline deliverable) — end-to-end verification of the <strong>two-dispatch
 * execution walk</strong> over the real wiring (Testcontainers Postgres + mock runner): {@code
 * approve spec → dispatch #1 (plan, read-only) → plan review → accept plan → dispatch #2 (PR,
 * implement+push) → PR review → accept PR → Completed}.
 *
 * <p>This story is <strong>test-only</strong>. The plan-accept → PR-phase re-dispatch ALREADY
 * exists (story 3.20: {@link
 * org.dradgo.application.approval.TechnicalApprovalService#acceptImplementation} on an {@code
 * implementationPlan} transitions {@code WaitingForReview → Executing} then calls {@link
 * WorkflowOrchestrationService#dispatchImplementation} inside the same {@code MANDATORY}
 * transaction). Combined with 3b-1's per-dispatch {@code ExecutionSubStage} threading and {@code
 * ContextBundleService.deriveExecutionSubStage} (which flips to {@code PR_OUTPUT} once an approved
 * implementation-plan approval row exists), the orchestration is complete — nothing had ever
 * exercised it through a SINGLE full walk because the sibling ITs ({@link
 * ImplementationPlanOrchestrationIT}, {@link PrOutputOrchestrationIT}) each seed mid-stream. This
 * IT closes that gap and is the pilot-readiness verification for the {@code run_ae258…} incident.
 *
 * <p><strong>Scope boundaries (mirroring the sibling ITs' javadoc):</strong>
 *
 * <ul>
 *   <li><b>No repo workspace is wired here</b> (no {@code github-mock} profile), so {@code
 *       captureAndPush} returns empty and the mock runner does not dirty a real clone — the natural
 *       enrich-persists-{@code github_pr}-link path does not fire. Per Design Decision DD2 this IT
 *       <em>seeds</em> an active {@code github_pr} link before the {@code prOutput} accept (what
 *       enrichment persists in production) and asserts only the orchestration state walk + the link
 *       gating/binding the accept. The "enrich actually persists the link from a real push outcome"
 *       claim is covered deterministically by {@code RunnerBrokerUnitTest}, {@code
 *       RepositoryWorkspaceServiceIT}, and {@code IntegrationLinkGitHubPrFoundationContract} — not
 *       duplicated here (AC5).
 *   <li><b>Artifact availability is marked in-test</b> (Design Decision DD1): the mock execution
 *       path leaves {@code implementationPlan}/{@code prOutput} {@code pending} ({@code
 *       markAvailable} has no production caller — that is the parallel story 3b-3). This IT drives
 *       the real {@link ArtifactOperationService#markAvailable} against the ingested artifact so
 *       {@code isApprovalEligible} passes; it does NOT wire markAvailable into the production
 *       ingest loop.
 * </ul>
 *
 * <p>Drives the async runner result deterministically via {@code drainQueue()} (lease + {@code
 * executeQueuedDispatch}, standing in for the off-in-test worker pool) followed by {@link
 * RunnerBroker#pollActiveExecutions()} — never sleeps on the scheduler (Trap T6). Enables BOTH
 * auto-dispatch master switches via {@link TestPropertySource} (the shared test profile keeps them
 * OFF, Trap T7/T11) and leaves {@code default-scenario.execution} at the shared {@code
 * happy-implementation-plan} so dispatch #1 emits a plan; dispatch #2's pr-output is selected via a
 * single-shot {@link MockRunnerAdapter#pinScenarioForWorkflowRun} pin.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.plan-stage.auto-dispatch=true",
      "deliveryline.runner.implementation-stage.auto-dispatch=true"
    })
class WaitingForReviewTwoDispatchOrchestrationIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private WorkflowOrchestrationService orchestrationService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;
  @Autowired private ArtifactOperationService artifactOperationService;
  @Autowired private MockRunnerAdapter mockRunnerAdapter;
  @Autowired private ContextBundleService contextBundleService;

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

  /**
   * AC1/AC2/AC3/AC5 + the logging task: the full two-dispatch walk reaches {@code Completed}. Pins
   * (AC3) that dispatch #1 ran the read-only plan phase (an {@code implementationPlan} artifact, NO
   * {@code prOutput}, NO active {@code github_pr} link) and dispatch #2 ran the pr-output phase
   * (driven only by the real {@code acceptImplementation(implementationPlan)} re-dispatch — AC2, no
   * new trigger). AC3 is pinned directly at the gate predicate: {@code
   * ContextBundleService.deriveExecutionSubStage} (the exact condition {@code RunnerBroker:1515}
   * keys on) is {@code IMPLEMENTATION_PLAN} after dispatch #1 and {@code PR_OUTPUT} after the plan
   * accept, proving {@code validateAndEnrichPrOutput} runs only on dispatch #2. AC5 is exercised
   * both ways: the {@code prOutput} accept fails closed with {@code ARTIFACT_PR_LINK_MISMATCH} when
   * no active {@code github_pr} link is present (gate-negative), then succeeds once the link is
   * seeded (gate-positive). The logging task is satisfied by the {@link ListAppender} assertions
   * over the EXISTING two-phase dispatch logs (the exact signal that was opaque on the live
   * incident).
   */
  @Test
  void fullWalkSpecToPrOutputReachesCompletedViaTwoDispatches() {
    Logger orchestrationLogger =
        (Logger) LoggerFactory.getLogger(WorkflowOrchestrationService.class);
    Logger acceptLogger =
        (Logger)
            LoggerFactory.getLogger(org.dradgo.application.approval.TechnicalApprovalService.class);
    ListAppender<ILoggingEvent> dispatchLogs = attach(orchestrationLogger);
    ListAppender<ILoggingEvent> acceptLogs = attach(acceptLogger);

    try {
      String runId = insertRun("run_2dispatch00walk", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
      seedAvailableSpecArtifact(runId, "art_2disp_spec_v1");

      // --- Spec approval auto-dispatches dispatch #1 (the read-only plan phase). -------------
      commandService.approveSpec(
          new ApproveSpecCommand(
              runId,
              "art_2disp_spec_v1",
              1,
              1,
              "alex",
              ActorType.HUMAN,
              "idem-2disp-approve-spec",
              "corr-2disp",
              "product_reviewer",
              null));
      assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
      assertEquals(1, executionCount(runId));
      assertEquals("queued", executionStatus(runId));

      drainQueue();
      runnerBroker.pollActiveExecutions();

      // AC1/AC3: plan ingested, run auto-advanced to WaitingForReview, and the plan phase pushed
      // NOTHING — no prOutput artifact and no active github_pr link exist yet.
      assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));
      assertEquals("completed", executionStatus(runId));
      assertEquals(1, artifactCount(runId, ArtifactType.IMPLEMENTATION_PLAN));
      assertEquals(0, artifactCount(runId, ArtifactType.PR_OUTPUT));
      assertTrue(
          activeGitHubPrLinkCount(runId) == 0, "plan phase must not persist a github_pr link");
      // AC3: the enrich gate (RunnerBroker:1515 `executionSubStage == PR_OUTPUT`) keys on this
      // exact
      // derivation. With no approved plan yet it is IMPLEMENTATION_PLAN, so dispatch #1's gate was
      // skipped — validateAndEnrichPrOutput did NOT run on the read-only plan phase.
      assertEquals(
          ExecutionSubStage.IMPLEMENTATION_PLAN,
          contextBundleService.deriveExecutionSubStage(runId));

      // --- Accept the plan → second dispatch (the pr-output phase). --------------------------
      String planArtifactId = latestArtifactId(runId, ArtifactType.IMPLEMENTATION_PLAN);
      markIngestedArtifactAvailable(planArtifactId);
      // Single-shot pin: consumed by dispatch #2 only (dispatch #1 already ran on the default
      // happy-implementation-plan scenario).
      mockRunnerAdapter.pinScenarioForWorkflowRun(runId, "happy-pr-output");

      int[] planVersions = currentVersions(planArtifactId);
      commandService.acceptImplementation(
          new AcceptImplementationCommand(
              runId,
              planArtifactId,
              planVersions[0],
              planVersions[1],
              "alex",
              ActorType.HUMAN,
              "idem-2disp-accept-plan",
              "corr-2disp",
              "developer",
              null));

      // AC1/AC3: plan-accept transitioned WaitingForReview -> Executing AND enqueued dispatch #2
      // (deriveExecutionSubStage now yields PR_OUTPUT). Exactly 2 executions; the new one queued.
      assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
      assertEquals(2, executionCount(runId));
      assertEquals("queued", executionStatus(runId));
      // AC3: the approved implementation-plan row now exists → the gate predicate flips to
      // PR_OUTPUT, so dispatch #2 (and ONLY dispatch #2) runs validateAndEnrichPrOutput.
      assertEquals(
          ExecutionSubStage.PR_OUTPUT, contextBundleService.deriveExecutionSubStage(runId));

      drainQueue();
      runnerBroker.pollActiveExecutions();

      // AC1: pr-output ingested, run auto-advanced back to WaitingForReview; dispatch #2 completed.
      assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));
      assertEquals("completed", executionStatus(runId));
      assertEquals(1, artifactCount(runId, ArtifactType.PR_OUTPUT));

      // --- Accept the pr-output → Completed (AC5: gated/bound by the active github_pr link). ---
      String prArtifactId = latestArtifactId(runId, ArtifactType.PR_OUTPUT);
      markIngestedArtifactAvailable(prArtifactId);
      int[] prVersions = currentVersions(prArtifactId);

      // AC5 gate-NEGATIVE: with NO active github_pr link, the prOutput accept must fail closed. The
      // PR-link gate (TechnicalApprovalService.assertPrLinkPresentAndMatches) throws
      // ARTIFACT_PR_LINK_MISMATCH BEFORE any approval row / transition is persisted (the gate runs
      // before the insert), so the run is left untouched at WaitingForReview and the versions stay
      // valid for the real accept below. (A distinct idempotencyKey keeps the two attempts
      // isolated.)
      assertEquals(0, activeGitHubPrLinkCount(runId));
      assertThatThrownBy(
              () ->
                  commandService.acceptImplementation(
                      new AcceptImplementationCommand(
                          runId,
                          prArtifactId,
                          prVersions[0],
                          prVersions[1],
                          "alex",
                          ActorType.HUMAN,
                          "idem-2disp-accept-pr-nolink",
                          "corr-2disp",
                          "developer",
                          null)))
          .isInstanceOf(DomainException.class)
          .extracting(thrown -> ((DomainException) thrown).errorCode())
          .isEqualTo(DomainErrorCode.ARTIFACT_PR_LINK_MISMATCH);
      assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));

      // AC5 gate-POSITIVE: seed the active github_pr link (DD2) → the same accept now passes the
      // gate and binds the approval.
      seedActiveGitHubPrLink(runId, "https://github.com/acme/widget/pull/42");
      commandService.acceptImplementation(
          new AcceptImplementationCommand(
              runId,
              prArtifactId,
              prVersions[0],
              prVersions[1],
              "alex",
              ActorType.HUMAN,
              "idem-2disp-accept-pr",
              "corr-2disp",
              "developer",
              null));

      // AC1: final state Completed AND still exactly 2 executions (the prOutput accept transitions
      // to COMPLETED with NO further dispatch — TechnicalApprovalService:573-584).
      assertEquals(WorkflowState.COMPLETED.value(), currentState(runId));
      assertEquals(2, executionCount(runId));
    } finally {
      orchestrationLogger.detachAppender(dispatchLogs);
      acceptLogger.detachAppender(acceptLogs);
    }

    // Logging task: the two dispatches are distinguishable by sub-stage (AC3 observability), and
    // the
    // plan-accept re-dispatch is logged by the real acceptImplementation path (AC2).
    assertThat(dispatchLogs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage())
                  .contains("dispatchPlanGeneration entry")
                  .contains("stage=EXECUTION")
                  .contains("subStage=implementationPlan");
            })
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.INFO);
              assertThat(event.getFormattedMessage())
                  .contains("dispatchImplementation entry")
                  .contains("stage=EXECUTION")
                  .contains("subStage=prOutput");
            });
    assertThat(acceptLogs.list)
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("acceptImplementation dispatchImplementation workflowRunId="));
  }

  /**
   * AC4 — idempotency: while the pr-output execution is in-flight (after the plan accept enqueued
   * dispatch #2, before draining), neither a direct {@code dispatchImplementation} nor a duplicate
   * {@code acceptImplementation(implementationPlan)} with the same {@code idempotencyKey} creates a
   * 3rd runner execution or illegally re-transitions. The sub-stage-aware in-flight guard ({@code
   * deriveExecutionSubStage == PR_OUTPUT} + an active EXECUTION execution) and the approval
   * idempotency key enforce this. Mirror of {@code
   * PrOutputOrchestrationIT.reDispatchImplementationWhileInFlightIsAnIdempotentNoOp}, lifted to the
   * real two-dispatch path.
   */
  @Test
  void duplicatePlanAcceptOrPrDispatchWhileInFlightIsIdempotentNoOp() {
    String runId = insertRun("run_2dispatch00idem", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact(runId, "art_2disp_idem_spec_v1");

    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            "art_2disp_idem_spec_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-2disp-idem-approve-spec",
            "corr-2disp-idem",
            "product_reviewer",
            null));
    drainQueue();
    runnerBroker.pollActiveExecutions();
    assertEquals(WorkflowState.WAITING_FOR_REVIEW.value(), currentState(runId));

    String planArtifactId = latestArtifactId(runId, ArtifactType.IMPLEMENTATION_PLAN);
    markIngestedArtifactAvailable(planArtifactId);
    mockRunnerAdapter.pinScenarioForWorkflowRun(runId, "happy-pr-output");

    int[] planVersions = currentVersions(planArtifactId);
    AcceptImplementationCommand acceptPlan =
        new AcceptImplementationCommand(
            runId,
            planArtifactId,
            planVersions[0],
            planVersions[1],
            "alex",
            ActorType.HUMAN,
            "idem-2disp-idem-accept-plan",
            "corr-2disp-idem",
            "developer",
            null);
    commandService.acceptImplementation(acceptPlan);

    // Dispatch #2 is now enqueued (queued) and in-flight for the PR_OUTPUT sub-stage.
    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
    assertEquals(2, executionCount(runId));

    // (a) A direct pr-output dispatch while in-flight is a no-op (sub-stage-aware guard).
    orchestrationService.dispatchImplementation(runId, "corr-2disp-idem");
    assertEquals(2, executionCount(runId));
    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));

    // (b) A duplicate plan accept (same idempotencyKey) replays — no new execution, no illegal
    // re-transition.
    commandService.acceptImplementation(acceptPlan);
    assertEquals(2, executionCount(runId));
    assertEquals(WorkflowState.EXECUTING.value(), currentState(runId));
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
   * Story 3.17b — auto-dispatch ENQUEUES; the worker pool (off in the test profile, Trap T7) leases
   * + dispatches queued rows. Drive that leg deterministically here exactly as a worker would
   * ({@code dequeue} → {@code executeQueuedDispatch}) so the subsequent {@code
   * pollActiveExecutions()} can harvest the mock result (Trap T6: no async pool, no sleeping).
   */
  private void drainQueue() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-2dispatch-worker")).isPresent()) {
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

  private int artifactCount(String runId, ArtifactType type) {
    return jdbcTemplate.queryForObject(
        "select count(*) from artifacts where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?) and artifact_type = ?",
        Integer.class,
        runId,
        type.value());
  }

  private String latestArtifactId(String runId, ArtifactType type) {
    return jdbcTemplate.queryForObject(
        "select public_id from artifacts where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?) and artifact_type = ?"
            + " order by id desc limit 1",
        String.class,
        runId,
        type.value());
  }

  private int activeGitHubPrLinkCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from integration_links where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)"
            + " and integration_type = 'github_pr' and archived_at is null"
            + " and sync_status != 'superseded'",
        Integer.class,
        runId);
  }

  /**
   * Design Decision DD1 — make the ingested artifact approval-eligible by driving the REAL {@link
   * ArtifactOperationService#markAvailable} (the parallel story 3b-3 wires this on ingest; here it
   * is in-test only, [[markavailable-has-no-production-caller]]). The ingested pending artifact
   * carries no {@code storage_ref}/checksum until markAvailable, so we write our own payload bytes
   * to the real {@code ArtifactPayloadStore}, compute their checksum, and let markAvailable verify
   * + stamp them. Defensive: if 3b-3 has already promoted the artifact to {@code available}, skip.
   */
  private void markIngestedArtifactAvailable(String artifactId) {
    String status =
        jdbcTemplate.queryForObject(
            "select status from artifacts where public_id = ?", String.class, artifactId);
    if ("available".equals(status)) {
      return;
    }
    int version =
        jdbcTemplate.queryForObject(
            "select version from artifacts where public_id = ?", Integer.class, artifactId);
    String runPublicId =
        jdbcTemplate.queryForObject(
            "select r.public_id from artifacts a"
                + " join workflow_runs r on r.id = a.workflow_run_id"
                + " where a.public_id = ?",
            String.class,
            artifactId);
    byte[] payload = ("approval-eligible content for " + artifactId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactId, version, "out.md", payload);
    artifactOperationService.markAvailable(
        artifactId,
        new ArtifactChecksum("SHA-256", sha256Hex(payload)),
        storageRef,
        new ActorContext("system", ActorType.SYSTEM, null));
  }

  /**
   * Design Decision DD2 — seed an active {@code github_pr} link (a direct insert; {@code
   * IntegrationLinkService.linkPullRequest} resolves the PR via the {@code github-mock}/{@code
   * github-real} RepositoryHostAdapter, which is not on the classpath under this IT's profiles).
   * Represents what {@code validateAndEnrichPrOutput} persists in production. The {@code prOutput}
   * accept's PR-link gate ({@code assertPrLinkPresentAndMatches}) requires exactly one active link.
   */
  private void seedActiveGitHubPrLink(String runId, String prReference) {
    Long runDbId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runId);
    jdbcTemplate.update(
        "insert into integration_links (public_id, workflow_run_id, integration_type, external_ref,"
            + " sync_status) values (?, ?, 'github_pr', ?, 'linked')",
        "ilk_2disp" + System.nanoTime(),
        runDbId,
        prReference);
  }

  /**
   * Resolve the current artifact version + context-bundle version for an artifact exactly as {@code
   * ApprovalVersionBinder} does (artifact.version + the linked runner execution's
   * context_bundle_version, bootstrapping to 1 when unlinked), so the accept command's version
   * binding passes against the live persisted state without hard-coding.
   */
  private int[] currentVersions(String artifactId) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select a.version as av, coalesce(re.context_bundle_version, 1) as cbv"
                + " from artifacts a"
                + " join workflow_events we on we.id = a.linked_event_id"
                + " left join runner_executions re"
                + "   on re.public_id = (we.details ->> 'runnerExecutionId')"
                + " where a.public_id = ?",
            artifactId);
    return new int[] {((Number) row.get("av")).intValue(), ((Number) row.get("cbv")).intValue()};
  }

  /**
   * Mirrors {@code ImplementationPlanOrchestrationIT.seedAvailableSpecArtifact} — seeds a SPEC
   * artifact whose payload bytes round-trip through {@code ArtifactPayloadStore} so {@code
   * ArtifactService.isApprovalEligible} passes (the mock spec path leaves the artifact {@code
   * pending}).
   */
  private void seedAvailableSpecArtifact(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    byte[] payload = ("approval-eligible content for " + artifactPublicId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "spec.md", payload);
    String checksum = sha256Hex(payload);

    String evtPublicId = "evt_2dispseed" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
                + " actor_type) values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning"
                + " id",
            Long.class,
            evtPublicId,
            runId);

    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version,"
            + " parent_artifact_id, classification, status, storage_ref, checksum_algorithm,"
            + " checksum_value, linked_event_id) values (?, ?, 'spec', 1, null,"
            + " 'shareable-redacted', 'available', ?, 'SHA-256', ?, ?)",
        artifactPublicId,
        runId,
        storageRef,
        checksum,
        linkedEventId);
  }

  private static ListAppender<ILoggingEvent> attach(Logger logger) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
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
