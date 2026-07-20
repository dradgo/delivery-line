package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.RecordArtifactOperationCommand;
import org.dradgo.application.project.ProjectStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.application.workflow.commands.ApproveDeliveryCommand;
import org.dradgo.application.workflow.commands.ApproveLintCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.domain.registry.PushMode;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3h-4 (Task 8, AC2/AC4/AC7) — real-Postgres proof of the unified delivery gate over the
 * actual persistence + afterCommit/REQUIRES_NEW + V38 state-CHECK machinery. {@link
 * RunnerWorkspaceStore} + {@link RepositoryWorkspaceService} are mocked for a resolvable workspace
 * + a deterministic push outcome; everything else (execution recording, the producing-rex finalize,
 * the EXECUTING → WaitingForDelivery transition against the real V38 CHECK, the approve_delivery
 * idempotency boundary, the delivery.recordedManually event append) is the real wiring.
 *
 * <p>The gate is driven inside a {@link TransactionTemplate} to mirror the poller transaction.
 * Non-{@code @Transactional}; named {@code *IT} so Failsafe runs it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class DeliveryGateIT {

  private static final Path REPO_DIR = Paths.get("/tmp/delivery-gate-it-repo");
  private static final String LINT_1 = "checkstyle.sh";

  @Autowired private DeliveryGateService deliveryGateService;
  @Autowired private WorkflowCommandService workflowCommandService;
  @Autowired private LintStageService lintStageService;
  @Autowired private ArtifactOperationService artifactOperationService;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private RunnerWorkspaceStore workspaceStore;
  @MockitoBean private RepositoryWorkspaceService repositoryWorkspaceService;

  @MockitoBean
  private org.dradgo.application.runner.workspace.spi.BuildCommandPort buildCommandPort;

  private TransactionTemplate tx;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update("delete from projects where slug like 'delivgate-it-%'");
  }

  @BeforeEach
  void setUpTemplate() {
    tx = new TransactionTemplate(transactionManager);
    when(workspaceStore.resolveRepositoryDir(any())).thenReturn(Optional.of(REPO_DIR));
  }

  @Test
  void autoModeIsPassThroughAndNeverParksAtWaitingForDelivery() {
    String projectId = seedProject("delivgate-it-auto", PushMode.AUTO, true, false, List.of());
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    // AC6 parity — auto never parks; the gate is a pure pass-through (returns false). The gate
    // itself does NOT run the inline delivery — the CALLER (RunnerBroker.tryDeliveryGateOrDeliver)
    // runs it when gated=false — so tailRan stays false here (we call the gate directly). The run
    // never enters WaitingForDelivery and the producing rex is left untouched by the gate.
    assertThat(gated).isFalse();
    assertThat(tailRan.get()).isFalse();
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    // Pass-through: the gate did NOT finalize the producing rex (that only happens on a park).
    assertThat(prOutputStatus(prOutputRex)).isEqualTo("running");
  }

  @Test
  void approveModeParksAtWaitingForDeliveryAndFinalizesProducingRex() {
    String projectId =
        seedProject("delivgate-it-approve", PushMode.APPROVE, true, false, List.of());
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    assertThat(gated).isTrue();
    // AC4 — the run parks at the new non-terminal state (proves the V38 CHECK + transition table).
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_DELIVERY.value());
    // The delivery tail did NOT run (no inline push); the producing rex was finalized.
    assertThat(tailRan.get()).isFalse();
    assertThat(prOutputStatus(prOutputRex)).isEqualTo("completed");
  }

  @Test
  void approveDeliveryOnApproveModePushesEnrichesAndAdvancesToWaitingForReview() {
    Parked parked = parkAtDeliveryGate("delivgate-it-approve-run", PushMode.APPROVE);
    seedPrOutputArtifact(parked.runId(), parked.prOutputRex());
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(1);
    // captureAndPush yields authoritative refs + a diff so the resume enriches. Null PR ref keeps
    // linkGitHubPrBestEffort a no-op (mirrors LintStageIT — a fabricated PR would poison the tx).
    when(repositoryWorkspaceService.captureAndPush(parked.prOutputRex()))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "commit123abc", "feat/delivery-it", null, true, "diff --git a/F b/F\n+line")));

    WorkflowStateChangeResult result =
        workflowCommandService.approveDelivery(
            new ApproveDeliveryCommand(
                parked.runId(),
                "op-1",
                ActorType.HUMAN,
                "idem-approve-delivery-0001",
                "corr-deliv-it",
                null));

    assertThat(result.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    assertThat(currentState(parked.runId())).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    // The deferred resume pushed + enriched the pr-output artifact (v2 chained off v1).
    verify(repositoryWorkspaceService).captureAndPush(parked.prOutputRex());
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(2);
    // No manual-delivery event on the approve path.
    assertThat(deliveryRecordedManuallyCount(parked.runId())).isZero();
  }

  @Test
  void approveDeliveryOnManualModeRecordsEventAndAdvancesWithoutTouchingGit() {
    Parked parked = parkAtDeliveryGate("delivgate-it-manual-run", PushMode.MANUAL);
    seedPrOutputArtifact(parked.runId(), parked.prOutputRex());

    WorkflowStateChangeResult result =
        workflowCommandService.approveDelivery(
            new ApproveDeliveryCommand(
                parked.runId(),
                "op-1",
                ActorType.HUMAN,
                "idem-approve-delivery-manual-01",
                "corr-deliv-it",
                null));

    assertThat(result.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    assertThat(currentState(parked.runId())).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    // AC4 (manual) — a delivery.recordedManually event is appended and the backend NEVER pushes.
    assertThat(deliveryRecordedManuallyCount(parked.runId())).isEqualTo(1);
    verify(repositoryWorkspaceService, never()).captureAndPush(any());
    // The artifact is NOT enriched (no push) — it stays at v1.
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(1);
  }

  @Test
  void wrongStateApproveDeliveryOnExecutingRunIs409AndPushesNothing() {
    String projectId =
        seedProject("delivgate-it-wrongstate", PushMode.APPROVE, true, false, List.of());
    String runId = seedExecutingRun(projectId);

    assertThatThrownBy(
            () ->
                workflowCommandService.approveDelivery(
                    new ApproveDeliveryCommand(
                        runId,
                        "op-1",
                        ActorType.HUMAN,
                        "idem-approve-delivery-wrongstate",
                        "corr-deliv-it",
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);

    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    verify(repositoryWorkspaceService, never()).captureAndPush(any());
  }

  @Test
  void lintApprovalOnNonAutoProjectLandsAtWaitingForDelivery() {
    // Story 3h-4 (Decision 3) — a project with BOTH lint gating and a non-auto push mode:
    // approve_lint
    // must route into the delivery gate (WaitingForDelivery), NOT WaitingForReview.
    String projectId =
        seedProject("delivgate-it-lintcompose", PushMode.APPROVE, true, true, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    jdbcTemplate.update(
        "update workflow_runs set current_state = ? where public_id = ?",
        WorkflowState.WAITING_FOR_LINT_APPROVAL.value(),
        runId);

    WorkflowStateChangeResult result =
        workflowCommandService.approveLint(
            new ApproveLintCommand(
                runId,
                "op-1",
                ActorType.HUMAN,
                "idem-approve-lint-compose",
                "corr-deliv-it",
                null));

    assertThat(result.currentState()).isEqualTo(WorkflowState.WAITING_FOR_DELIVERY);
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_DELIVERY.value());
    // The lint approval did NOT push (approve_delivery owns delivery).
    verify(repositoryWorkspaceService, never()).captureAndPush(any());
  }

  @Test
  void replayedApproveDeliveryShortCircuitsWithoutDoublePushing() {
    Parked parked = parkAtDeliveryGate("delivgate-it-idem", PushMode.APPROVE);
    seedPrOutputArtifact(parked.runId(), parked.prOutputRex());
    when(repositoryWorkspaceService.captureAndPush(parked.prOutputRex()))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "commit123abc", "feat/delivery-it", null, true, "diff --git a/F b/F\n+line")));
    ApproveDeliveryCommand command =
        new ApproveDeliveryCommand(
            parked.runId(),
            "op-1",
            ActorType.HUMAN,
            "idem-approve-delivery-replay-01",
            "corr-deliv-it",
            null);

    WorkflowStateChangeResult first = workflowCommandService.approveDelivery(command);
    // Same idempotency key → replay short-circuits to the pinned post-state, never re-running.
    WorkflowStateChangeResult replay = workflowCommandService.approveDelivery(command);

    assertThat(first.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    assertThat(replay.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    // The push seam fired exactly ONCE across the original + replay (no double-push).
    verify(repositoryWorkspaceService).captureAndPush(parked.prOutputRex());
  }

  // ---- helpers -------------------------------------------------------------

  private boolean gate(String runId, String prOutputRex, Runnable tail) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                deliveryGateService.tryGateBehindDelivery(
                    runId, prOutputRex, "corr-deliv-it", tail)));
  }

  private Parked parkAtDeliveryGate(String slug, PushMode pushMode) {
    String projectId = seedProject(slug, pushMode, true, false, List.of());
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    gate(runId, prOutputRex, () -> {});
    return new Parked(runId, prOutputRex);
  }

  private record Parked(String runId, String prOutputRex) {}

  private void seedPrOutputArtifact(String runId, String prOutputRex) {
    artifactOperationService.recordOperation(
        new RecordArtifactOperationCommand(
            runId,
            ArtifactType.PR_OUTPUT,
            ArtifactOperationType.CREATE,
            "seed-prout:" + runId,
            "prOutput.json",
            "{\"artifactType\":\"pr_output\",\"artifactId\":\"a1\"}"
                .getBytes(StandardCharsets.UTF_8),
            "system",
            ActorType.SYSTEM,
            "corr-deliv-it",
            prOutputRex));
  }

  private int latestPrOutputVersion(String runId) {
    return artifactOperationService
        .findLatestArtifact(runId, ArtifactType.PR_OUTPUT)
        .map(art -> art.version())
        .orElse(-1);
  }

  private String seedProject(
      String slug,
      PushMode pushMode,
      boolean autoCreatePullRequest,
      boolean lintEnabled,
      List<String> lintCommands) {
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "Delivery gate " + slug,
            slug,
            ProjectStatus.ACTIVE,
            "octo/delivgate",
            ConnectorKind.LINEAR,
            ConnectorKind.GITHUB,
            false,
            null,
            false,
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            null,
            Map.of(),
            null,
            false,
            lintEnabled ? lintCommands : List.of(),
            lintEnabled,
            pushMode,
            autoCreatePullRequest);
    return projectStore.insert(project).publicId();
  }

  private String seedExecutingRun(String projectId) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state, project_id) values (?, ?, ?)",
        runId,
        WorkflowState.EXECUTING.value(),
        projectId);
    return runId;
  }

  private String seedPrOutputExecution(String runId) {
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'execution', 'running', 1,
                now(), now() + interval '10 minutes', 100, 0, now())
        """,
        rex,
        runId);
    return rex;
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private String prOutputStatus(String prOutputRex) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, prOutputRex);
  }

  private int deliveryRecordedManuallyCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from workflow_events where event_type = 'delivery.recordedManually'"
            + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
        Integer.class,
        runId);
  }
}
