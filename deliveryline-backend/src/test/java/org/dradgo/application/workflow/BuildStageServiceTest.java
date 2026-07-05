package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.dradgo.application.runner.workspace.spi.GitCommandPort;
import org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 3h-1 (Task 9, AC1/AC3/AC4/AC5/AC6) — unit coverage for the build-validation gate
 * orchestration: applicability (disabled / no command / no workspace ⇒ skip = pre-3h parity), the
 * pass path (record completed + run the deferred delivery tail), the bounded fix loop (below-cap
 * re-dispatch; at-cap FAILED + escalation), and the no-token guarantee.
 */
class BuildStageServiceTest {

  private static final String RUN_ID = "run_buildstage000000000000000000000";
  private static final String PR_OUTPUT_REX = "rex_prooutput0000000000000000000000";
  private static final String CORRELATION = "corr-build";
  private static final String COMMAND = "mvn -q -DskipTests package";

  private ProjectRuntimeConfigResolver resolver;
  private RunnerProperties runnerProperties;
  private RunnerWorkspaceStore workspaceStore;
  private BuildCommandPort buildCommandPort;
  private RunnerExecutionRecordPort recordPort;
  private RunnerExecutionService executionService;
  private RunnerLogCaptureService logCaptureService;
  private AfterCommitSideEffectRunner afterCommit;
  private WorkflowRunRejectionLoopPort rejectionLoopPort;
  private BuildFixEscalationThresholdProvider thresholdProvider;
  private WorkflowTransitionService transitionService;
  private GitCommandPort gitCommandPort;
  private WorkflowOrchestrationService orchestration;
  private BuildStageService service;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    resolver = mock(ProjectRuntimeConfigResolver.class);
    runnerProperties = mock(RunnerProperties.class);
    workspaceStore = mock(RunnerWorkspaceStore.class);
    buildCommandPort = mock(BuildCommandPort.class);
    recordPort = mock(RunnerExecutionRecordPort.class);
    executionService = mock(RunnerExecutionService.class);
    logCaptureService = mock(RunnerLogCaptureService.class);
    afterCommit = mock(AfterCommitSideEffectRunner.class);
    rejectionLoopPort = mock(WorkflowRunRejectionLoopPort.class);
    thresholdProvider = mock(BuildFixEscalationThresholdProvider.class);
    transitionService = mock(WorkflowTransitionService.class);
    gitCommandPort = mock(GitCommandPort.class);
    orchestration = mock(WorkflowOrchestrationService.class);

    ObjectProvider<WorkflowOrchestrationService> orchestrationProvider = mock(ObjectProvider.class);
    when(orchestrationProvider.getIfAvailable()).thenReturn(orchestration);

    when(runnerProperties.buildTimeout()).thenReturn(Duration.ofMinutes(10));
    when(logCaptureService.captureLogs(any(), any(), any(), any()))
        .thenReturn(mock(CapturedLogs.class));

    // The afterCommit helper fires its side-effects synchronously in this unit test (no real tx).
    doAnswer(inv -> runRunnable(inv.getArgument(2)))
        .when(afterCommit)
        .runAfterCommit(any(), any(), any());
    doAnswer(inv -> runRunnable(inv.getArgument(2)))
        .when(afterCommit)
        .runInNewTransaction(any(), any(), any());

    service =
        new BuildStageService(
            resolver,
            runnerProperties,
            workspaceStore,
            buildCommandPort,
            recordPort,
            executionService,
            logCaptureService,
            afterCommit,
            rejectionLoopPort,
            thresholdProvider,
            transitionService,
            gitCommandPort,
            Runnable::run, // same-thread executor keeps the afterCommit-driven build synchronous
            orchestrationProvider);

    // Story 3h-1 (logging instrumentation) — pin the BuildStageService observability lines.
    logAppender = new ListAppender<>();
    logAppender.start();
    Logger serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(BuildStageService.class);
    serviceLogger.setLevel(Level.DEBUG);
    serviceLogger.addAppender(logAppender);
  }

  private static Object runRunnable(Runnable runnable) {
    runnable.run();
    return null;
  }

  /** All captured BuildStageService log messages (formatted, with substituted args). */
  private List<String> logMessages() {
    return logAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }

  private void assertLogged(String fragment) {
    assertThat(logMessages()).anyMatch(m -> m.contains(fragment));
  }

  private void enableBuild() {
    when(resolver.resolveBuildStageEnabled(RUN_ID)).thenReturn(true);
    when(resolver.resolveBuildCommand(RUN_ID)).thenReturn(Optional.of(COMMAND));
    when(workspaceStore.resolveRepositoryDir(PR_OUTPUT_REX))
        .thenReturn(Optional.of(Path.of("/tmp/repo")));
    when(recordPort.nextContextBundleVersion(RUN_ID, RunnerStage.BUILD)).thenReturn(1);
  }

  @Test
  void disabledProjectSkipsBuildAndReservesNoRow() {
    when(resolver.resolveBuildStageEnabled(RUN_ID)).thenReturn(false);
    Runnable tail = mock(Runnable.class);

    boolean gated = service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    assertThat(gated).isFalse();
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
    verify(afterCommit, never()).runAfterCommit(any(), any(), any());
    verify(tail, never()).run();
    assertLogged("build stage skipped");
    assertLogged("reason=disabled");
  }

  @Test
  void noBuildCommandSkips() {
    when(resolver.resolveBuildStageEnabled(RUN_ID)).thenReturn(true);
    when(resolver.resolveBuildCommand(RUN_ID)).thenReturn(Optional.empty());

    assertThat(service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class)))
        .isFalse();
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
  }

  @Test
  void noWorkspaceSkips() {
    when(resolver.resolveBuildStageEnabled(RUN_ID)).thenReturn(true);
    when(resolver.resolveBuildCommand(RUN_ID)).thenReturn(Optional.of(COMMAND));
    when(workspaceStore.resolveRepositoryDir(PR_OUTPUT_REX)).thenReturn(Optional.empty());

    assertThat(service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class)))
        .isFalse();
    verify(recordPort, never()).insertPending(any(), any(), any(), anyInt(), any());
  }

  @Test
  void buildPassReservesRowRunsTailAndRecordsCompleted() {
    enableBuild();
    when(buildCommandPort.run(any(), eq(COMMAND), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(0, "BUILD SUCCESS", ""));
    Runnable tail = mock(Runnable.class);

    boolean gated = service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    assertThat(gated).isTrue();
    // A BUILD runner_executions row is reserved with stage=build.
    ArgumentCaptor<RunnerStage> stage = ArgumentCaptor.forClass(RunnerStage.class);
    verify(recordPort).insertPending(any(), eq(RUN_ID), stage.capture(), anyInt(), any());
    assertThat(stage.getValue()).isEqualTo(RunnerStage.BUILD);
    // On success the BUILD row is completed and the deferred delivery tail runs.
    verify(executionService).recordCompleted(any());
    verify(tail).run();
    verify(orchestration, never()).retryImplementation(any(), any());
    assertLogged("build stage enabled");
    assertLogged("build stage passed");
  }

  @Test
  void buildFailBelowCapRedispatchesImplementation() {
    enableBuild();
    when(buildCommandPort.run(any(), eq(COMMAND), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "compile error"));
    when(rejectionLoopPort.incrementAndReadBuildFixLoopCount(RUN_ID)).thenReturn(1);
    when(thresholdProvider.get()).thenReturn(3);
    Runnable tail = mock(Runnable.class);

    service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    // The BUILD row is recorded failed; the producing PR_OUTPUT execution is finalized FIRST (else
    // the in-flight dispatch guard would make the re-dispatch a no-op); the implementation runner
    // is
    // re-dispatched (run stays Executing, so no transition); the delivery tail does NOT run.
    verify(executionService).recordFailed(any(), eq(FailureCategory.RUNNER_NON_ZERO_EXIT));
    verify(executionService).recordCompleted(PR_OUTPUT_REX);
    verify(orchestration).retryImplementation(RUN_ID, CORRELATION);
    verify(tail, never()).run();
    verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any(), any());
    assertLogged("build stage failed");
    assertLogged("build fix loop attempt 1/3");
  }

  @Test
  void buildFailAtExactCapStillRedispatches() {
    // Boundary (loopCount == cap): cap=N allows up to N re-dispatches, so the N-th failure still
    // loops; only the (N+1)-th (loopCount > cap) fails the run.
    enableBuild();
    when(buildCommandPort.run(any(), eq(COMMAND), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "compile error"));
    when(rejectionLoopPort.incrementAndReadBuildFixLoopCount(RUN_ID)).thenReturn(3);
    when(thresholdProvider.get()).thenReturn(3);

    service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class));

    verify(orchestration).retryImplementation(RUN_ID, CORRELATION);
    verify(transitionService, never()).transition(any(), any(), any(), any(), any(), any(), any());
    assertLogged("build fix loop attempt 3/3");
  }

  @Test
  void executorFailureFailsRunFastWithoutBurningTheFixLoop() {
    // An executor failure (the build process could not start) must NOT re-dispatch the LLM (it
    // cannot fix a broken executor) nor consume a fix-loop iteration — it fails fast + escalates.
    enableBuild();
    when(buildCommandPort.run(any(), eq(COMMAND), any()))
        .thenReturn(BuildCommandPort.BuildResult.executorFailure("sh: mvn: not found"));
    when(rejectionLoopPort.markEscalationOnce(RUN_ID)).thenReturn(1);
    Runnable tail = mock(Runnable.class);

    service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    verify(executionService).recordCompleted(PR_OUTPUT_REX); // producing rex finalized
    verify(rejectionLoopPort, never()).incrementAndReadBuildFixLoopCount(any());
    verify(orchestration, never()).retryImplementation(any(), any());
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_BUILD_FAILED),
            any());
    verify(rejectionLoopPort).markEscalationOnce(RUN_ID);
    verify(tail, never()).run();
    assertLogged("build stage executor failure");
  }

  @Test
  void buildFailAtCapFailsRunWithBuildCategoryAndEscalatesOnce() {
    enableBuild();
    when(buildCommandPort.run(any(), eq(COMMAND), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(1, "", "still broken"));
    when(rejectionLoopPort.incrementAndReadBuildFixLoopCount(RUN_ID)).thenReturn(4);
    when(thresholdProvider.get()).thenReturn(3);
    when(rejectionLoopPort.markEscalationOnce(RUN_ID)).thenReturn(1);
    Runnable tail = mock(Runnable.class);

    service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, tail);

    verify(executionService)
        .recordCompleted(PR_OUTPUT_REX); // producing rex finalized before FAILED
    verify(transitionService)
        .transition(
            eq(RUN_ID),
            eq(WorkflowState.FAILED),
            any(),
            any(),
            any(),
            eq(FailureCategory.RUNNER_BUILD_FAILED),
            any());
    verify(rejectionLoopPort).markEscalationOnce(RUN_ID);
    verify(orchestration, never()).retryImplementation(any(), any());
    verify(tail, never()).run();
    assertLogged("build fix loop cap exceeded");
    assertLogged("build fix loop escalation");
  }

  @Test
  void buildNeverRecordsTokenOrProviderUsage() {
    enableBuild();
    when(buildCommandPort.run(any(), eq(COMMAND), any()))
        .thenReturn(BuildCommandPort.BuildResult.of(0, "ok", ""));

    service.tryGateBehindBuild(RUN_ID, PR_OUTPUT_REX, CORRELATION, mock(Runnable.class));

    // AC6 — a backend-side BUILD execution never captures token/provider usage.
    verify(recordPort, never()).recordTokenUsage(any(), any(), any(), any());
  }
}
