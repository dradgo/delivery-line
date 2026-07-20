package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
import org.dradgo.application.runner.workspace.spi.BuildCommandPort;
import org.dradgo.application.workflow.commands.ApproveLintCommand;
import org.dradgo.application.workflow.commands.RequestLintFixCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
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
 * Story 3h-2 (Task 9, AC1/AC3/AC4/AC6/AC7) — real-Postgres proof of the CPU lint gate over the
 * actual persistence + afterCommit/REQUIRES_NEW + V34 state-CHECK/jsonb machinery. {@link
 * BuildCommandPort} + {@link RunnerWorkspaceStore} are mocked for deterministic critical/clean
 * output + a resolvable workspace; everything else (execution recording, the {@code lint_findings}
 * jsonb write, the producing-rex finalize, the EXECUTING → WaitingForLintApproval transition
 * against the real V34 CHECK, redacted log capture) is the real wiring.
 *
 * <p>The gate is driven inside a {@link TransactionTemplate} to mirror the poller transaction, so
 * {@code AfterCommitSideEffectRunner.runAfterCommit} fires the lint on commit and the test-profile
 * {@code lintStageExecutor} runs it synchronously. Non-{@code @Transactional}; named {@code *IT} so
 * Failsafe runs it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class LintStageIT {

  private static final String LINT_1 = "checkstyle.sh";
  private static final String LINT_2 = "eslint.sh";
  private static final Path REPO_DIR = Paths.get("/tmp/lint-stage-it-repo");

  @Autowired private LintStageService lintStageService;
  @Autowired private WorkflowCommandService workflowCommandService;
  @Autowired private ArtifactOperationService artifactOperationService;
  @Autowired private ProjectStore projectStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private BuildCommandPort buildCommandPort;
  @MockitoBean private RunnerWorkspaceStore workspaceStore;
  // Story 3h-2 (code-review 2026-07-06) — the approve_lint resume enriches the pr-output artifact
  // from the AUTHORITATIVE captureAndPush outcome; mock it so the resume gets real refs + a diff
  // without touching git/GitHub.
  @MockitoBean private RepositoryWorkspaceService repositoryWorkspaceService;

  private TransactionTemplate tx;

  @BeforeEach
  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update("delete from projects where slug like 'lintstage-it-%'");
  }

  @BeforeEach
  void setUpTemplate() {
    tx = new TransactionTemplate(transactionManager);
    when(workspaceStore.resolveRepositoryDir(any())).thenReturn(Optional.of(REPO_DIR));
  }

  @Test
  void criticalFindingParksAtWaitingForLintApprovalPersistsFindingsAndFinalizesProducingRex() {
    String projectId = seedProject("lintstage-it-critical", true, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(LINT_1), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "[ERROR] src/Foo.java:42: bad"));
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    assertThat(gated).isTrue();
    // AC4 — the run parks at the new non-terminal state (proves the V34 CHECK + transition table).
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_LINT_APPROVAL.value());
    // The LINT row is recorded failed (existing RUNNER_NON_ZERO_EXIT); the delivery tail did NOT
    // run.
    assertThat(lintExecutionStatus(runId)).isEqualTo("failed");
    assertThat(tailRan.get()).isFalse();
    // AC6 — the severity-classified findings persisted as jsonb on the LINT row.
    String findings = lintFindingsJson(runId);
    assertThat(findings).isNotNull();
    // Postgres normalizes jsonb text with a space after the colon — strip whitespace before
    // matching.
    assertThat(findings.replaceAll("\\s", "")).contains("\"hasCritical\":true");
    assertThat(findings.replaceAll("\\s", "")).contains("\"severity\":\"error\"");
    // The producing PR_OUTPUT execution was finalized (else it would be timeout-reaped while
    // parked).
    assertThat(prOutputStatus(prOutputRex)).isEqualTo("completed");
  }

  @Test
  void cleanLintCompletesLintRexRunsTailStaysExecutingAndRecordsNoTokens() {
    String projectId = seedProject("lintstage-it-clean", true, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(LINT_1), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(0, "0 problems", ""));
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    assertThat(gated).isTrue();
    // Clean lint → the LINT row completes, the tail runs, the run stays Executing (no park).
    assertThat(lintExecutionStatus(runId)).isEqualTo("completed");
    assertThat(tailRan.get()).isTrue();
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    // AC7 — a backend-side LINT execution records ZERO token usage (columns stay NULL).
    assertThat(lintTokenColumnsAllNull(runId)).isTrue();
  }

  @Test
  void multiCommandLintFailsFastOnTheFirstCriticalCommand() {
    String projectId = seedProject("lintstage-it-failfast", true, List.of(LINT_1, LINT_2));
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(LINT_1), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "checkstyle error"));

    gate(runId, prOutputRex, () -> {});

    // Fail-fast: the first critical command parks the run; the second command is never invoked.
    verify(buildCommandPort).run(eq(REPO_DIR), eq(LINT_1), any(Duration.class));
    verify(buildCommandPort, never()).run(eq(REPO_DIR), eq(LINT_2), any(Duration.class));
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_LINT_APPROVAL.value());
  }

  @Test
  void disabledProjectSkipsLintEntirely() {
    String projectId = seedProject("lintstage-it-disabled", false, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    AtomicBoolean tailRan = new AtomicBoolean(false);

    boolean gated = gate(runId, prOutputRex, () -> tailRan.set(true));

    // AC2/AC7 parity — the gate is skipped: no LINT row, no lint invoked, tail untouched here.
    assertThat(gated).isFalse();
    assertThat(lintExecutionStatus(runId)).isNull();
    assertThat(tailRan.get()).isFalse();
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
  }

  // ---- AC5/AC8 operator actions (code-review 2026-07-06 follow-up) ---------

  @Test
  void approveLintTransitionsToWaitingForReviewEnrichesPrOutputArtifactAndFinalizesProducingRex() {
    ParkedRun parked = parkAtLintGate("lintstage-it-approve");
    assertThat(currentState(parked.runId()))
        .isEqualTo(WorkflowState.WAITING_FOR_LINT_APPROVAL.value());
    seedPrOutputArtifact(parked.runId(), parked.prOutputRex());
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(1);
    // captureAndPush yields authoritative refs + a diff so the resume enriches (P1). The PR ref is
    // null on purpose: linkGitHubPrBestEffort is a separate best-effort concern (not AC8/P1) and
    // the
    // mock GitHub adapter would reject a fabricated PR, whose caught INTEGRATION_LINK_CONFLICT
    // would
    // still mark the resume tx rollback-only (the "caught exception poisons the shared tx" trap)
    // and
    // undo the enrichment. A null PR ref makes the linkage step a no-op.
    when(repositoryWorkspaceService.captureAndPush(parked.prOutputRex()))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "commit123abc",
                    "feat/lint-it",
                    null,
                    true,
                    "diff --git a/F b/F\n+added line")));

    WorkflowStateChangeResult result =
        workflowCommandService.approveLint(
            new ApproveLintCommand(
                parked.runId(),
                "op-1",
                ActorType.HUMAN,
                "idem-approve-main-0001",
                "corr-lint-it",
                null));

    // AC5/P2 — approve transitions the run to WaitingForReview SYNCHRONOUSLY (a wrong-state approve
    // would 409 here instead).
    assertThat(result.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    assertThat(currentState(parked.runId())).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    // P1 — the deferred resume ENRICHED the pr-output artifact (a v2 chained off v1 carrying the
    // authoritative push refs + diff), so the advisory reviewer reads a reviewable artifact instead
    // of the diff-less v1.
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(2);
    // The producing PR_OUTPUT execution is finalized (idempotent — already completed at park).
    assertThat(prOutputStatus(parked.prOutputRex())).isEqualTo("completed");
  }

  @Test
  void approveLintReplayIsIdempotentAndDoesNotDoublePushOrEnrich() {
    ParkedRun parked = parkAtLintGate("lintstage-it-approve-replay");
    seedPrOutputArtifact(parked.runId(), parked.prOutputRex());
    when(repositoryWorkspaceService.captureAndPush(parked.prOutputRex()))
        .thenReturn(
            Optional.of(
                new RepositoryWorkspaceService.RepositoryPushOutcome(
                    "commit123abc", "feat/lint-it", null, true, "diff --git a/F b/F\n+x")));
    ApproveLintCommand cmd =
        new ApproveLintCommand(
            parked.runId(),
            "op-1",
            ActorType.HUMAN,
            "idem-approve-replay-01",
            "corr-lint-it",
            null);

    workflowCommandService.approveLint(cmd);
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(2);

    // AC8 — replay the SAME idempotency key: the command boundary returns the recorded result
    // without re-running the resume, so NO second push and NO second enrichment version.
    WorkflowStateChangeResult replay = workflowCommandService.approveLint(cmd);

    assertThat(replay.currentState()).isEqualTo(WorkflowState.WAITING_FOR_REVIEW);
    assertThat(latestPrOutputVersion(parked.runId())).isEqualTo(2);
    verify(repositoryWorkspaceService).captureAndPush(parked.prOutputRex()); // exactly once
  }

  @Test
  void requestLintFixBumpsLoopCountAndTransitionsToExecuting() {
    ParkedRun parked = parkAtLintGate("lintstage-it-fix");

    WorkflowStateChangeResult result =
        workflowCommandService.requestLintFix(
            new RequestLintFixCommand(
                parked.runId(),
                "op-1",
                ActorType.HUMAN,
                "idem-request-fix-below-01",
                "corr-lint-it",
                "please fix"));

    // AC5/AC8 — the loop counter is bumped and the run transitions back to Executing for
    // re-dispatch.
    assertThat(result.currentState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(currentState(parked.runId())).isEqualTo(WorkflowState.EXECUTING.value());
    assertThat(lintFixLoopCount(parked.runId())).isEqualTo(1);
    // Below the cap (default 3) → the shared escalation marker is NOT flipped.
    assertThat(escalationMarkerSet(parked.runId())).isFalse();
  }

  @Test
  void requestLintFixAtCapFlipsEscalationMarkerButNeverFails() {
    ParkedRun parked = parkAtLintGate("lintstage-it-fix-cap");
    // Pre-seed the counter to cap-1 (default cap 3) so this request reaches the cap.
    jdbcTemplate.update(
        "update workflow_runs set lint_fix_loop_count = 2 where public_id = ?", parked.runId());

    WorkflowStateChangeResult result =
        workflowCommandService.requestLintFix(
            new RequestLintFixCommand(
                parked.runId(),
                "op-1",
                ActorType.HUMAN,
                "idem-request-fix-atcap-01",
                "corr-lint-it",
                null));

    // Decision 3 — at the cap the shared escalation marker flips for VISIBILITY only; the run still
    // transitions to Executing and is NEVER driven to Failed.
    assertThat(result.currentState()).isEqualTo(WorkflowState.EXECUTING);
    assertThat(currentState(parked.runId())).isEqualTo(WorkflowState.EXECUTING.value());
    assertThat(lintFixLoopCount(parked.runId())).isEqualTo(3);
    assertThat(escalationMarkerSet(parked.runId())).isTrue();
  }

  @Test
  void approveLintOnANonGateStateIsRejectedAndDoesNotPushOrTransition() {
    // Code-review 2026-07-06 re-review: a run that is EXECUTING (not parked at the lint gate). The
    // edge EXECUTING -> WaitingForReview is a legal delivery-tail edge, so transition-table
    // legality
    // ALONE would let a wrong-state approve succeed and push a mid-execution workspace. The
    // upstream
    // requireParkedAtLintGate precondition must reject it (ILLEGAL_TRANSITION -> 409) instead.
    String projectId = seedProject("lintstage-it-approve-wrongstate", true, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    seedPrOutputExecution(runId);

    assertThatThrownBy(
            () ->
                workflowCommandService.approveLint(
                    new ApproveLintCommand(
                        runId,
                        "op-1",
                        ActorType.HUMAN,
                        "idem-approve-wrongstate-01",
                        "corr-lint-it",
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);

    // The run stays EXECUTING and NOTHING was pushed (the whole point — no mid-execution push).
    assertThat(currentState(runId)).isEqualTo(WorkflowState.EXECUTING.value());
    verify(repositoryWorkspaceService, never()).captureAndPush(any());
  }

  @Test
  void requestLintFixOnANonGateStateIsRejectedAndDoesNotBumpTheLoopCount() {
    // Code-review 2026-07-06 re-review: a run parked at WaitingForReview (awaiting review).
    // WaitingForReview -> Executing is a legal reject-implementation edge, so a wrong-state
    // request_lint_fix would otherwise hijack the run back into Executing + bump the fix counter.
    String projectId = seedProject("lintstage-it-fix-wrongstate", true, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    jdbcTemplate.update(
        "update workflow_runs set current_state = ? where public_id = ?",
        WorkflowState.WAITING_FOR_REVIEW.value(),
        runId);

    assertThatThrownBy(
            () ->
                workflowCommandService.requestLintFix(
                    new RequestLintFixCommand(
                        runId,
                        "op-1",
                        ActorType.HUMAN,
                        "idem-request-fix-wrongstate-01",
                        "corr-lint-it",
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.ILLEGAL_TRANSITION);

    // The run stays WaitingForReview and the fix-loop counter was NOT bumped (guard runs first).
    assertThat(currentState(runId)).isEqualTo(WorkflowState.WAITING_FOR_REVIEW.value());
    assertThat(lintFixLoopCount(runId)).isEqualTo(0);
  }

  // ---- helpers -------------------------------------------------------------

  private boolean gate(String runId, String prOutputRex, Runnable tail) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                lintStageService.tryGateBehindLint(runId, prOutputRex, "corr-lint-it", tail)));
  }

  /**
   * Seed a lint-enabled run + producing rex and park it at WaitingForLintApproval via a critical
   * lint.
   */
  private ParkedRun parkAtLintGate(String slug) {
    String projectId = seedProject(slug, true, List.of(LINT_1));
    String runId = seedExecutingRun(projectId);
    String prOutputRex = seedPrOutputExecution(runId);
    when(buildCommandPort.run(eq(REPO_DIR), eq(LINT_1), any(Duration.class)))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "[ERROR] src/Foo.java:42: bad"));
    gate(runId, prOutputRex, () -> {});
    return new ParkedRun(runId, prOutputRex);
  }

  private record ParkedRun(String runId, String prOutputRex) {}

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
            "corr-lint-it",
            prOutputRex));
  }

  private int latestPrOutputVersion(String runId) {
    return artifactOperationService
        .findLatestArtifact(runId, ArtifactType.PR_OUTPUT)
        .map(art -> art.version())
        .orElse(-1);
  }

  private int lintFixLoopCount(String runId) {
    return jdbcTemplate.queryForObject(
        "select lint_fix_loop_count from workflow_runs where public_id = ?", Integer.class, runId);
  }

  private boolean escalationMarkerSet(String runId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select escalation_marker_set from workflow_runs where public_id = ?",
            Boolean.class,
            runId));
  }

  private String seedProject(String slug, boolean lintEnabled, List<String> lintCommands) {
    Project project =
        new Project(
            PublicIdPrefixes.PROJECT.next(),
            "Lint stage " + slug,
            slug,
            ProjectStatus.ACTIVE,
            "octo/lintstage",
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
            lintEnabled);
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

  private String lintExecutionStatus(String runId) {
    return jdbcTemplate.query(
        "select status from runner_executions where stage = 'lint' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        rs -> rs.next() ? rs.getString(1) : null,
        runId);
  }

  private String lintFindingsJson(String runId) {
    return jdbcTemplate.query(
        "select lint_findings::text from runner_executions where stage = 'lint' and workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        rs -> rs.next() ? rs.getString(1) : null,
        runId);
  }

  private String prOutputStatus(String prOutputRex) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, prOutputRex);
  }

  private boolean lintTokenColumnsAllNull(String runId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.query(
            "select input_tokens, output_tokens, total_tokens from runner_executions"
                + " where stage = 'lint' and workflow_run_id ="
                + " (select id from workflow_runs where public_id = ?)",
            rs -> {
              if (!rs.next()) {
                return false;
              }
              rs.getInt(1);
              boolean inNull = rs.wasNull();
              rs.getInt(2);
              boolean outNull = rs.wasNull();
              rs.getInt(3);
              boolean totNull = rs.wasNull();
              return inNull && outNull && totNull;
            },
            runId));
  }
}
