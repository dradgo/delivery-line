package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.AllowedActionsView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowRunDetailedSummaryView;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
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
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class DeveloperTakeoverServiceIT {

  private static final String IDEMPOTENCY_KEY = "idem-takeover-it-00000001";
  private static final String PR_REF = "octo/widgets#7";
  private static final String REASON = "developer continuing in IDE";

  @Autowired private DeveloperTakeoverService takeoverService;
  @Autowired private WorkflowInspectionService inspectionService;
  @Autowired private RecoveryActionRecordPort recoveryActionRecordPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from idempotency_records");
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
}
