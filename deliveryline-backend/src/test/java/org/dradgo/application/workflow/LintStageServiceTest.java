package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.dradgo.application.project.ProjectRuntimeConfigResolver;
import org.dradgo.application.runner.CapturedLogs;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerLogCaptureService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.workspace.spi.BuildCommandPort;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Story 3h-2 (Task 9, AC1/AC3/AC4/AC7) — unit coverage for the CPU lint gate orchestration:
 * applicability (disabled / no commands / no workspace ⇒ skip = parity), the critical-park path
 * (record failed + persist findings + finalize producing rex + park at WaitingForLintApproval, tail
 * NOT run), the advisory-advance path (record completed + run the deferred tail, no park), the
 * multi-command fail-fast, and the no-token guarantee.
 */
class LintStageServiceTest {

  private static final String RUN_ID = "run_lintstage0000000000000000000000";
  private static final String PR_OUTPUT_REX = "rex_prooutput0000000000000000000000";
  private static final String CORRELATION = "corr-lint";
  private static final String CMD_1 = "mvn -q -DskipTests checkstyle:check";
  private static final String CMD_2 = "npm run lint";

  private ProjectRuntimeConfigResolver resolver;
  private RunnerProperties runnerProperties;
  private RunnerWorkspaceStore workspaceStore;
  private BuildCommandPort buildCommandPort;
  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionService executionService;
  private RunnerLogCaptureService logCaptureService;
  private AfterCommitSideEffectRunner afterCommit;
  private WorkflowTransitionService transitionService;
  private RedactionPolicyService redactionPolicyService;
  private LintStageService service;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    resolver = mock(ProjectRuntimeConfigResolver.class);
    runnerProperties = mock(RunnerProperties.class);
    workspaceStore = mock(RunnerWorkspaceStore.class);
    buildCommandPort = mock(BuildCommandPort.class);
    recordPort = mock(RunnerExecutionRecordPort.class);
    executionService = mock(RunnerExecutionService.class);
    logCaptureService = mock(RunnerLogCaptureService.class);
    afterCommit = mock(AfterCommitSideEffectRunner.class);
    transitionService = mock(WorkflowTransitionService.class);
    redactionPolicyService = mock(RedactionPolicyService.class);

    when(runnerProperties.lintTimeout()).thenReturn(Duration.ofMinutes(5));
    when(logCaptureService.captureLogs(any(), any(), any(), any()))
        .thenReturn(mock(CapturedLogs.class));
    // Default redaction is identity (pass-through) so the existing assertions on raw content hold;
    // the dedicated redaction test re-stubs a scrubbing behavior.
    when(redactionPolicyService.redact(anyString(), any()))
        .thenAnswer(inv -> identityRedaction(inv.getArgument(0)));

    // The afterCommit helper fires its side-effects synchronously in this unit test (no real tx).
    doAnswer(inv -> runRunnable(inv.getArgument(2)))
        .when(afterCommit)
        .runAfterCommit(any(), any(), any());
    doAnswer(inv -> runRunnable(inv.getArgument(2)))
        .when(afterCommit)
        .runInNewTransaction(any(), any(), any());

    service =
        new LintStageService(
            resolver,
            runnerProperties,
            workspaceStore,
            buildCommandPort,
            recordPort,
            executionService,
            logCaptureService,
            afterCommit,
            transitionService,
            new LintFindingsClassifier(), // the classifier is a pure value-mapper — use the real
            // one
            redactionPolicyService,
            Runnable::run); // same-thread executor keeps the afterCommit-driven lint synchronous

    logAppender = new ListAppender<>();
    logAppender.start();
    Logger serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(LintStageService.class);
    serviceLogger.setLevel(Level.DEBUG);
    serviceLogger.addAppender(logAppender);
  }

  private static Object runRunnable(Runnable runnable) {
    runnable.run();
    return null;
  }

  private static RedactionResult identityRedaction(String text) {
    RedactionResult result = mock(RedactionResult.class);
    when(result.sanitizedText()).thenReturn(text);
    return result;
  }

  private List<String> logMessages() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }

  private void assertLogged(String fragment) {
    assertThat(logMessages()).anyMatch(m -> m.contains(fragment));
  }

  private void enableLint(List<String> commands) {
    when(resolver.resolveLintStageEnabled(RUN_ID)).thenReturn(true);
    when(resolver.resolveLintCommands(RUN_ID)).thenReturn(commands);
    when(workspaceStore.resolveRepositoryDir(PR_OUTPUT_REX))
        .thenReturn(Optional.of(Path.of("/tmp/repo")));
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.LINT)).thenReturn(1);
  }

  @Test
  void disabledProjectSkipsLintAndReservesNoRow() {
    when(resolver.resolveLintStageEnabled(RUN_ID)).thenReturn(false);
    Runnable tail = mock(Runnable.class);

    boolean gated = service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    assertThat(gated).isFalse();
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
    verify(afterCommit, never()).runAfterCommit(any(), any(), any());
    verify(tail, never()).run();
    assertLogged("lint stage skipped");
    assertLogged("reason=disabled");
  }

  @Test
  void emptyLintCommandsSkips() {
    when(resolver.resolveLintStageEnabled(RUN_ID)).thenReturn(true);
    when(resolver.resolveLintCommands(RUN_ID)).thenReturn(List.of());

    assertThat(service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class)))
        .isFalse();
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
    assertLogged("reason=no_lint_commands");
  }

  @Test
  void noWorkspaceSkips() {
    when(resolver.resolveLintStageEnabled(RUN_ID)).thenReturn(true);
    when(resolver.resolveLintCommands(RUN_ID)).thenReturn(List.of(CMD_1));
    when(workspaceStore.resolveRepositoryDir(PR_OUTPUT_REX)).thenReturn(Optional.empty());

    assertThat(service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class)))
        .isFalse();
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
  }

  @Test
  void cleanLintReservesRowRunsTailAndRecordsCompletedWithoutParking() {
    enableLint(List.of(CMD_1));
    when(buildCommandPort.run(any(), eq(CMD_1), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(0, "0 problems", ""));
    Runnable tail = mock(Runnable.class);

    boolean gated = service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    assertThat(gated).isTrue();
    ArgumentCaptor<RunnerStage> stage = ArgumentCaptor.forClass(RunnerStage.class);
    verify(recordPort).insertPending(any(), eq(RUN_ID), stage.capture(), anyInt(), any());
    assertThat(stage.getValue()).isEqualTo(RunnerStage.LINT);
    // Clean lint → the LINT row is completed, findings are persisted, and the tail runs. No park.
    verify(executionService).recordCompleted(any());
    verify(executionService).recordLintFindings(any(), any());
    verify(tail).run();
    verify(transitionService, never()).transition(any(), any(), any(), any(), any(), anyMap());
    assertLogged("lint stage passed");
  }

  @Test
  void criticalFindingParksAtWaitingForLintApprovalAndDoesNotRunTail() {
    enableLint(List.of(CMD_1));
    when(buildCommandPort.run(any(), eq(CMD_1), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "[ERROR] src/Foo.java:42: bad"));
    Runnable tail = mock(Runnable.class);

    service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    // Critical → LINT row recorded FAILED with the existing RUNNER_NON_ZERO_EXIT category; findings
    // persisted; producing PR_OUTPUT execution finalized FIRST (avoid timeout-reap while parked);
    // transition EXECUTING -> WaitingForLintApproval; the delivery tail is NOT run.
    verify(executionService).recordFailed(any(), eq(FailureCategory.RUNNER_NON_ZERO_EXIT));
    verify(executionService).recordLintFindings(any(), any());
    verify(executionService).recordCompleted(PR_OUTPUT_REX);
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.WAITING_FOR_LINT_APPROVAL),
            any(),
            any(),
            eq("lint-gate:" + RUN_ID),
            anyMap());
    verify(tail, never()).run();
    assertLogged("lint stage critical findings");
  }

  @Test
  void multiCommandLintFailsFastOnTheFirstCriticalCommand() {
    enableLint(List.of(CMD_1, CMD_2));
    when(buildCommandPort.run(any(), eq(CMD_1), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "checkstyle error"));

    service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class));

    // Fail-fast: the first critical command parks the run; the second command never runs.
    verify(buildCommandPort).run(any(), eq(CMD_1), any());
    verify(buildCommandPort, never()).run(any(), eq(CMD_2), any());
    verify(transitionService)
        .transition(
            eq(RUN_ID), eq(WorkflowState.WAITING_FOR_LINT_APPROVAL), any(), any(), any(), anyMap());
  }

  @Test
  void lintFindingsAreRedactedBeforePersistence() {
    enableLint(List.of(CMD_1));
    when(buildCommandPort.run(any(), eq(CMD_1), any()))
        .thenReturn(
            BuildCommandPort.BuildResult.of(
                1, "", "[ERROR] leaked token=SECRET123 in src/Foo.java:9"));
    // P3 (code-review 2026-07-06) — a scrubbing redaction replaces the secret with the placeholder
    // BEFORE classification, so the persisted findings must never carry the raw secret bytes.
    when(redactionPolicyService.redact(anyString(), any()))
        .thenAnswer(
            inv ->
                identityRedaction(
                    ((String) inv.getArgument(0)).replace("SECRET123", "[REDACTED_TOKEN]")));

    service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class));

    ArgumentCaptor<String> findingsJson = ArgumentCaptor.forClass(String.class);
    verify(executionService).recordLintFindings(any(), findingsJson.capture());
    assertThat(findingsJson.getValue()).contains("[REDACTED_TOKEN]").doesNotContain("SECRET123");
  }

  @Test
  void lintNeverRecordsTokenUsage() {
    enableLint(List.of(CMD_1));
    when(buildCommandPort.run(any(), eq(CMD_1), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(0, "ok", ""));

    service.tryGateBehindLint(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class));

    // AC7 — a backend-side LINT execution never captures token/provider usage.
    verify(recordPort, never()).recordTokenUsage(any(), any(), any(), any());
  }
}
