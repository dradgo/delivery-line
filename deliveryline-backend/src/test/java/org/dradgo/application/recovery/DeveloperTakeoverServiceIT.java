package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.queue.RunnerExecutionQueue;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowRunDetailedSummaryView;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 3.22 (AC12) — Testcontainers Postgres coverage of {@link
 * DeveloperTakeoverService#takeoverWorkflow} end-to-end over real schema/state-machine/transition
 * wiring. Seeds a {@code WaitingForReview} run with a {@code queued} + a {@code pending} runner row
 * and an active {@code github_pr} link; drives the takeover; asserts the run is {@code TakenOver},
 * both runner rows flip to {@code cancelled_for_takeover}, the PR link is preserved, the
 * developer-attributed {@code recovery_actions} row reaches {@code succeeded}, {@link
 * WorkflowInspectionService#getRunSummary} returns the takeover fields, and the now-{@code
 * TakenOver} run offers only {@code view_only}. Then asserts idempotent replay (single recovery
 * row).
 *
 * <p>NOT {@code @Tag("docker-runner-it")} — only a real Postgres is needed (no runner images). The
 * live-container {@code docker stop} is covered by {@link
 * org.dradgo.adapters.runner.lifecycle.DockerRunnerTakeoverCancellationIT}.
 *
 * <p>Story 3.35 (AC4, Task 3) — adds {@link
 * #fullWalkSpecApprovedToPlanGeneratedThenTakeoverCancelsInFlightAndPreservesPr()}, which drives
 * the REAL orchestration ({@code spec approved → dispatch #1 plan → accept plan → dispatch #2 PR
 * enqueued}) before the takeover (reusing the {@link
 * org.dradgo.application.workflow.WaitingForReviewTwoDispatchOrchestrationIT} seam) so the takeover
 * cancels a genuinely-orchestrated in-flight execution and preserves a real generated plan artifact
 * — closing the epic's "spec approved → plan generated → developer takes over" walk that the
 * focused methods above (which seed mid-stream at {@code WaitingForReview}) never exercised. The
 * auto-dispatch master switches are enabled via {@link TestPropertySource} for that walk; the
 * focused methods never call {@code approveSpec}/{@code acceptImplementation}, so the switches do
 * not perturb them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@TestPropertySource(
    properties = {
      "deliveryline.runner.plan-stage.auto-dispatch=true",
      "deliveryline.runner.implementation-stage.auto-dispatch=true"
    })
@Tag("integration")
class DeveloperTakeoverServiceIT {

  private static final String IDEMPOTENCY_KEY = "idem-takeover-it-00000001";
  private static final String PR_REF = "octo/widgets#7";
  private static final String REASON = "developer continuing in IDE";

  @Autowired private DeveloperTakeoverService takeoverService;
  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private RecoveryActionRecordPort recoveryActionRecordPort;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService commandService;
  @Autowired private RunnerBroker runnerBroker;
  @Autowired private RunnerExecutionQueue runnerExecutionQueue;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;
  @Autowired private ArtifactOperationService artifactOperationService;
  @Autowired private MockRunnerAdapter mockRunnerAdapter;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void takeoverTransitionsCancelsRunnersPreservesPrAndRecordsAttribution() {
    String runId = seedWaitingForReviewRun();
    String queuedRex = insertRunner(runId, "queued");
    String pendingRex = insertRunner(runId, "pending");
    seedActiveGitHubPrLink(runId);

    TakeoverResult result = takeoverService.takeoverWorkflow(command(runId));

    assertThat(result.replayed()).isFalse();
    assertThat(result.resultingState().value()).isEqualTo("TakenOver");
    assertThat(result.recoveryActionPublicId()).startsWith("rcv_");
    assertThat(result.cancelledQueuedCount()).isEqualTo(1);
    assertThat(result.cancelledInFlightCount()).isEqualTo(1); // the pending row
    assertThat(result.preservedPrReference()).isEqualTo(PR_REF);

    // Run is TakenOver.
    assertThat(currentState(runId)).isEqualTo("TakenOver");

    // Both runner rows flipped to the terminal takeover status.
    assertThat(runnerStatus(queuedRex)).isEqualTo("cancelled_for_takeover");
    assertThat(runnerStatus(pendingRex)).isEqualTo("cancelled_for_takeover");

    // PR linkage preserved (left untouched, still active) — query directly (findActiveGitHubPrLink
    // takes a FOR UPDATE lock that would need an ambient tx the test method does not hold).
    assertThat(activeGitHubPrRef(runId)).isEqualTo(PR_REF);

    // Developer-attributed recovery_actions row reached succeeded.
    RecoveryActionSnapshot action =
        recoveryActionRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY).orElseThrow();
    assertThat(action.actionType()).isEqualTo("takeover");
    assertThat(action.reviewerRole()).isEqualTo("developer");
    assertThat(action.resultStatus()).isEqualTo("succeeded");
    assertThat(action.actorType()).isEqualTo(ActorType.HUMAN);

    // A later audit event must not replace the takeover transition as the attribution source.
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type, reason, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'runner.completed', 'runner-system', 'system', 'later audit event', now() + interval '1 second')
        """,
        PublicIdPrefixes.WORKFLOW_EVENT.next(),
        runId);

    // getRunSummary surfaces the takeover attribution (FR19 reconstruction).
    WorkflowRunDetailedSummaryView summary = inspectionService.getRunSummary(runId);
    assertThat(summary.currentState()).isEqualTo("TakenOver");
    assertThat(summary.takenOverBy()).isNotNull();
    assertThat(summary.takenOverBy().actorIdentity()).isEqualTo("alex");
    assertThat(summary.takenOverBy().actorType()).isEqualTo("human");
    assertThat(summary.takenOverBy().reviewerRole()).isEqualTo("developer");
    assertThat(summary.takenOverAt()).isNotNull();
    assertThat(summary.takenOverReason()).isEqualTo(REASON);

    // Post-takeover the only allowed action is view_only (for the developer role).
    AllowedActionsView actions = inspectionService.getAllowedActions(runId, "developer");
    assertThat(actions.actions()).extracting(Enum::name).containsExactly("VIEW_ONLY");
  }

  @Test
  void replayWithSameKeyReturnsReplayWithoutASecondRecoveryRow() {
    String runId = seedWaitingForReviewRun();
    insertRunner(runId, "queued");

    TakeoverResult first = takeoverService.takeoverWorkflow(command(runId));
    assertThat(first.replayed()).isFalse();

    TakeoverResult replay = takeoverService.takeoverWorkflow(command(runId));
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.recoveryActionPublicId()).isEqualTo(first.recoveryActionPublicId());
    assertThat(replay.resultingState().value()).isEqualTo("TakenOver");

    Integer rowCount =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'takeover'", Integer.class);
    assertThat(rowCount).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = WorkflowState.class,
      names = {
        "INBOX",
        "PLANNED",
        "INVESTIGATING",
        "WAITING_FOR_SPEC_APPROVAL",
        "EXECUTING",
        "WAITING_FOR_REVIEW",
        "FAILED",
        "PAUSED"
      })
  void takeoverFromEveryNonTerminalStateReachesTakenOverAndRecordsAttribution(
      WorkflowState source) {
    String runId = seedRunInState(source);
    // Key suffix uses the PascalCase value (no underscores) — the idempotency-key pattern is
    // [A-Za-z0-9-]{16,128}, so source.name() (SCREAMING_SNAKE) would be rejected.
    String key = "idem-takeover-ok-" + source.value();

    TakeoverResult result = takeoverService.takeoverWorkflow(commandFor(runId, key));

    assertThat(result.replayed()).isFalse();
    assertThat(result.resultingState().value()).isEqualTo("TakenOver");
    assertThat(currentState(runId)).isEqualTo("TakenOver");
    RecoveryActionSnapshot action =
        recoveryActionRecordPort.findByIdempotencyKey(key).orElseThrow();
    assertThat(action.actionType()).isEqualTo("takeover");
    assertThat(action.reviewerRole()).isEqualTo("developer");
    assertThat(action.resultStatus()).isEqualTo("succeeded");
  }

  @ParameterizedTest
  @EnumSource(
      value = WorkflowState.class,
      names = {"COMPLETED", "TAKEN_OVER", "RECONCILED"})
  void takeoverFromEveryTerminalStateIsRejectedWithoutSideEffects(WorkflowState source) {
    String runId = seedRunInState(source);
    String key = "idem-takeover-no-" + source.value();

    DomainException error =
        assertThrows(
            DomainException.class, () -> takeoverService.takeoverWorkflow(commandFor(runId, key)));

    assertThat(error.errorCode()).isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);
    // The whole prep tx rolled back: no takeover recovery_actions row, run left in its source
    // state.
    assertThat(recoveryActionRecordPort.findByIdempotencyKey(key)).isEmpty();
    assertThat(currentState(runId)).isEqualTo(source.value());
  }

  /**
   * Story 3.35 (AC4 / FR18 + FR19 + FR33) — the full developer-takeover walk over the REAL
   * orchestration. Drives {@code spec approved → dispatch #1 (plan) → accept plan → dispatch #2
   * (PR) enqueued} exactly as {@code WaitingForReviewTwoDispatchOrchestrationIT} does, THEN has the
   * developer take over while dispatch #2 is queued. Asserts the run reaches {@code TakenOver}, the
   * genuinely-orchestrated queued PR execution flips to {@code cancelled_for_takeover} while the
   * already-{@code completed} plan execution is left untouched (takeover cancels only non-terminal
   * work), the generated implementation-plan artifact + the active {@code github_pr} link are
   * preserved, the developer-attributed recovery row succeeds, and the post-takeover allowed
   * actions reduce to {@code VIEW_ONLY}.
   *
   * <p>Does NOT re-assert the dual-runner cancellation matrix, idempotent replay, or the all-states
   * sweep — those stay in the focused methods above; this method's unique contribution is the real
   * spec→plan pre-amble.
   */
  @Test
  void fullWalkSpecApprovedToPlanGeneratedThenTakeoverCancelsInFlightAndPreservesPr() {
    // --- Real spec approval → dispatch #1 (the read-only plan phase). --------------------------
    String runId = seedRunInState(WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact(runId, "art_takeover_spec_v1");

    commandService.approveSpec(
        new ApproveSpecCommand(
            runId,
            "art_takeover_spec_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "idem-takeoverwalk-approve-spec",
            "corr-takeoverwalk",
            "product_reviewer",
            null));
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());

    drainQueue();
    runnerBroker.pollActiveExecutions();

    // Plan generated, run auto-advanced to WaitingForReview; dispatch #1 completed.
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    assertThat(artifactCount(runId, ArtifactType.IMPLEMENTATION_PLAN)).isEqualTo(1);
    String planRex = latestRunnerPublicId(runId);
    assertThat(runnerStatus(planRex)).isEqualTo("completed");

    // --- Accept the plan → dispatch #2 (PR phase) is enqueued (queued). ------------------------
    String planArtifactId = latestArtifactId(runId, ArtifactType.IMPLEMENTATION_PLAN);
    markIngestedArtifactAvailable(planArtifactId);
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
            "idem-takeoverwalk-accept-plan",
            "corr-takeoverwalk",
            "developer",
            null));
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    assertThat(executionCount(runId)).isEqualTo(2);
    String queuedPrRex = latestRunnerPublicId(runId);
    assertThat(runnerStatus(queuedPrRex)).isEqualTo("queued");

    // The active github_pr link that validateAndEnrichPrOutput persists in production (DD2) — seed
    // it so we can prove takeover preserves it. (No worker drains dispatch #2; we take over while
    // it
    // is still queued, deliberately leaving the in-flight work for takeover to cancel.)
    seedActiveGitHubPrLink(runId);

    // --- Developer takes over (FR18 cancel in-flight, FR19 attribution, FR33 PR preserved). ----
    TakeoverResult result = takeoverService.takeoverWorkflow(command(runId));

    assertThat(result.replayed()).isFalse();
    assertThat(result.resultingState().value()).isEqualTo("TakenOver");
    assertThat(result.cancelledQueuedCount()).isEqualTo(1); // the queued PR dispatch
    assertThat(result.cancelledInFlightCount()).isEqualTo(0);
    assertThat(result.preservedPrReference()).isEqualTo(PR_REF);

    assertThat(currentState(runId)).isEqualTo("TakenOver");
    // The genuinely-orchestrated queued PR execution flipped to the terminal takeover status...
    assertThat(runnerStatus(queuedPrRex)).isEqualTo("cancelled_for_takeover");
    // ...while the already-completed plan execution is left untouched (only non-terminal work is
    // cancelled).
    assertThat(runnerStatus(planRex)).isEqualTo("completed");

    // The generated plan artifact + the PR linkage are preserved.
    assertThat(artifactCount(runId, ArtifactType.IMPLEMENTATION_PLAN)).isEqualTo(1);
    assertThat(activeGitHubPrRef(runId)).isEqualTo(PR_REF);

    // Developer-attributed recovery_actions row reached succeeded (FR19).
    RecoveryActionSnapshot action =
        recoveryActionRecordPort.findByIdempotencyKey(IDEMPOTENCY_KEY).orElseThrow();
    assertThat(action.actionType()).isEqualTo("takeover");
    assertThat(action.reviewerRole()).isEqualTo("developer");
    assertThat(action.resultStatus()).isEqualTo("succeeded");
    assertThat(action.actorType()).isEqualTo(ActorType.HUMAN);

    // getRunSummary reconstructs the takeover attribution.
    WorkflowRunDetailedSummaryView summary = inspectionService.getRunSummary(runId);
    assertThat(summary.currentState()).isEqualTo("TakenOver");
    assertThat(summary.takenOverReason()).isEqualTo(REASON);

    // Post-takeover the only allowed action is view_only.
    AllowedActionsView actions = inspectionService.getAllowedActions(runId, "developer");
    assertThat(actions.actions()).extracting(Enum::name).containsExactly("VIEW_ONLY");
  }

  // ----- seed helpers -----

  private static TakeoverWorkflowCommand command(String runId) {
    return new TakeoverWorkflowCommand(
        runId, "alex", ActorType.HUMAN, IDEMPOTENCY_KEY, "corr-takeover-it", REASON);
  }

  private static TakeoverWorkflowCommand commandFor(String runId, String idempotencyKey) {
    return new TakeoverWorkflowCommand(
        runId, "alex", ActorType.HUMAN, idempotencyKey, "corr-takeover-it", REASON);
  }

  private String seedRunInState(WorkflowState state) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, state.value());
    return runId;
  }

  private String seedWaitingForReviewRun() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')",
        runId);
    return runId;
  }

  private String insertRunner(String runId, String status) {
    String publicId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', ?, 1, now(), now() + interval '10 minutes', 100, 0, now())
        """,
        publicId,
        runId,
        status);
    return publicId;
  }

  private void seedActiveGitHubPrLink(String runId) {
    String linkId = PublicIdPrefixes.INTEGRATION_LINK.next();
    jdbcTemplate.update(
        """
        insert into integration_links
          (public_id, workflow_run_id, integration_type, external_ref, sync_status, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'github_pr', ?, 'synced', now())
        """,
        linkId,
        runId,
        PR_REF);
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private String runnerStatus(String publicId) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, publicId);
  }

  private String activeGitHubPrRef(String runId) {
    return jdbcTemplate.queryForObject(
        """
        select external_ref from integration_links
         where integration_type = 'github_pr'
           and archived_at is null
           and sync_status <> 'superseded'
           and workflow_run_id = (select id from workflow_runs where public_id = ?)
        """,
        String.class,
        runId);
  }

  // ----- orchestration-walk helpers (story 3.35, AC4) — mirror
  // WaitingForReviewTwoDispatchOrchestrationIT so the takeover acts on a genuinely orchestrated
  // run.

  /**
   * Story 3.17b — auto-dispatch ENQUEUES; the worker pool (off in the test profile) leases +
   * dispatches queued rows. Drive that leg deterministically ({@code dequeue} → {@code
   * executeQueuedDispatch}) so the subsequent {@code pollActiveExecutions()} can harvest the mock
   * result.
   */
  private void drainQueue() {
    Optional<RunnerExecutionSnapshot> leased;
    while ((leased = runnerExecutionQueue.dequeue("it-takeoverwalk-worker")).isPresent()) {
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

  /** The public id of the most recently inserted runner execution for the run. */
  private String latestRunnerPublicId(String runId) {
    return jdbcTemplate.queryForObject(
        "select public_id from runner_executions where workflow_run_id ="
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

  /**
   * Resolve the current artifact version + context-bundle version exactly as {@code
   * ApprovalVersionBinder} does, so the accept command's version binding passes against the live
   * persisted state without hard-coding.
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
   * Make the ingested (pending) artifact approval-eligible by driving the REAL {@code
   * ArtifactOperationService#markAvailable} (the mock execution path leaves artifacts {@code
   * pending}). Defensive: skip if already promoted.
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
   * Seed a SPEC artifact whose payload bytes round-trip through {@code ArtifactPayloadStore} so
   * {@code ArtifactService.isApprovalEligible} passes (mirrors {@code
   * WaitingForReviewTwoDispatchOrchestrationIT.seedAvailableSpecArtifact}).
   */
  private void seedAvailableSpecArtifact(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    byte[] payload = ("approval-eligible content for " + artifactPublicId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "spec.md", payload);
    String checksum = sha256Hex(payload);
    String evtPublicId = "evt_takeoverseed" + System.nanoTime();
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

  private static String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(payload));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }
}
