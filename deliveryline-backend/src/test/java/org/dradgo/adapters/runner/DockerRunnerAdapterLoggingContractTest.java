package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.dradgo.adapters.runner.docker.ContainerState;
import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.runner.CapturedLogs;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerLogCaptureService;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RawRunnerLog;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 3.1 review follow-up (open finding "Required Docker adapter logging contract is missing") —
 * a dedicated Logback list-appender contract pinning ≥1 assertion per {@link DockerRunnerAdapter}
 * log branch (dispatch entry/success/failure, poll running/completed/failed, tryReadResult hit,
 * cancel issued/no-op/best-effort-failure, image-tag credential redaction), plus a no-leak
 * assertion that neither the context-bundle bytes nor the agent-provider key ever reach the log
 * output. Mirrors the {@code RunnerLoggingContractTest} (story 1.19) / {@code
 * DockerRunnerLifecycleLoggingContractTest} (story 3.2a) template.
 *
 * <p>The {@link DockerEngineGateway} is mocked, so this contract runs in the fast tier with no real
 * Docker daemon (the heavyweight container ITs live in {@code
 * DockerRunnerAdapterContainerLifecycleIT}).
 */
class DockerRunnerAdapterLoggingContractTest {

  private static final String REX_ID = "rex_log1234567890";
  private static final String RUN_ID = "run_log1234567890";
  private static final String CONTAINER_ID = "container_log_0001";
  private static final String BUNDLE_SENTINEL = "BUNDLE_BYTES_MUST_NEVER_LEAK";
  private static final String SECRET_VALUE = "sk-codex-logcontract-value";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC);

  private RunnerScratchStore scratchStore;
  private RunnerWorkspaceStore workspaceStore;
  private DockerEngineGateway gateway;
  private RunnerProperties properties;
  private RunnerSecretsService secretsService;
  private RunnerLogCaptureService logCaptureService;
  private RunnerExecutionService executionService;
  private DockerRunnerAdapter adapter;

  private Logger adapterLogger;
  private ListAppender<ILoggingEvent> appender;
  private Level priorLevel;

  @BeforeEach
  void setUp() {
    scratchStore = Mockito.mock(RunnerScratchStore.class);
    workspaceStore = Mockito.mock(RunnerWorkspaceStore.class);
    gateway = Mockito.mock(DockerEngineGateway.class);
    properties = RunnerProperties.defaults();
    secretsService =
        new RunnerSecretsService(
            new MockEnvironment().withProperty("CODEX_API_KEY", SECRET_VALUE), properties);
    logCaptureService = Mockito.mock(RunnerLogCaptureService.class);
    executionService = Mockito.mock(RunnerExecutionService.class);
    adapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            properties,
            secretsService,
            logCaptureService,
            executionService,
            CLOCK);

    adapterLogger = (Logger) LoggerFactory.getLogger(DockerRunnerAdapter.class);
    priorLevel = adapterLogger.getLevel();
    // Capture the DEBUG running branch too.
    adapterLogger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    adapterLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    adapterLogger.detachAppender(appender);
    adapterLogger.setLevel(priorLevel);
  }

  @Test
  void dispatchLogsStartAndSuccessInfoLines() {
    dispatchSuccessfully();

    assertThat(logText())
        .contains("docker dispatch start")
        .contains("runnerExecutionId=" + REX_ID)
        .contains("image=deliveryline/codex-runner:latest")
        .contains("docker dispatch ok")
        .contains("containerId=" + CONTAINER_ID);
  }

  @Test
  void dispatchLogsErrorWhenGatewayStartFails() {
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID))
        .thenReturn(Optional.of(BUNDLE_SENTINEL.getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);
    Mockito.doThrow(new RuntimeException("engine start blew up"))
        .when(gateway)
        .startContainer(CONTAINER_ID);

    assertThatThrownBy(() -> adapter.dispatch(dispatchRequest()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(errorLines()).anyMatch(line -> line.contains("docker dispatch failed"));
  }

  @Test
  void dispatchLogsRedactedImageTagWhenCredentialsEmbedded() {
    // Image-tag credential redaction branch: a user:secret@host/image:tag reference must surface
    // as ***@host/image:tag — never the raw credential.
    RunnerProperties credentialed =
        propertiesWithCodexImage("user:secret@registry.example.test/deliveryline/codex:latest");
    DockerRunnerAdapter credAdapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            credentialed,
            new RunnerSecretsService(
                new MockEnvironment().withProperty("CODEX_API_KEY", SECRET_VALUE), credentialed),
            logCaptureService,
            executionService,
            CLOCK);
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("{}".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    credAdapter.dispatch(dispatchRequest());

    assertThat(logText())
        .doesNotContain("user:secret")
        .contains("***@registry.example.test/deliveryline/codex:latest");
  }

  @Test
  void pollLogsDebugRunningLine() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    when(workspaceStore.observeLogGrowth(REX_ID)).thenReturn(Optional.empty());

    adapter.poll(REX_ID);

    assertThat(debugLines()).anyMatch(line -> line.contains("docker poll running"));
  }

  @Test
  void pollLogsCompletedInfoLine() {
    dispatchSuccessfully();
    stubExitCapture();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.of("{}".getBytes()));

    adapter.poll(REX_ID);

    assertThat(logText()).contains("docker poll completed").contains("exitCode=0");
  }

  @Test
  void pollLogsWarnWhenExitedWithoutResult() {
    dispatchSuccessfully();
    stubExitCapture();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.empty());

    adapter.poll(REX_ID);

    assertThat(warnLines()).anyMatch(line -> line.contains("docker poll exited-no-result"));
  }

  @Test
  void pollLogsWarnForTerminalContainerState() {
    dispatchSuccessfully();
    stubExitCapture();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("dead", null, "none", List.of(), Map.of()));

    adapter.poll(REX_ID);

    assertThat(warnLines()).anyMatch(line -> line.contains("docker poll terminal-failure"));
  }

  @Test
  void tryReadResultLogsInfoHitWithByteCount() {
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.of("{}".getBytes()));

    adapter.tryReadResult(REX_ID);

    assertThat(logText()).contains("docker tryReadResult hit").contains("bytes=2");
  }

  @Test
  void cancelLogsInfoWhenStopIssued() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of()));

    adapter.cancel(REX_ID);

    assertThat(logText()).contains("docker cancel issued").contains("containerId=" + CONTAINER_ID);
  }

  @Test
  void cancelLogsNoOpForUnknownRexId() {
    adapter.cancel(REX_ID);

    assertThat(logText()).contains("docker cancel no-op").contains("reason=unknown_id");
  }

  @Test
  void cancelLogsWarnOnBestEffortFailure() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenThrow(new RuntimeException("docker inspect blew up"));

    adapter.cancel(REX_ID);

    assertThat(warnLines()).anyMatch(line -> line.contains("docker cancel best-effort failure"));
  }

  @Test
  void logsNeverLeakBundleBytesOrProviderKey() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    when(workspaceStore.observeLogGrowth(REX_ID)).thenReturn(Optional.empty());
    adapter.poll(REX_ID);

    assertThat(logText()).doesNotContain(BUNDLE_SENTINEL).doesNotContain(SECRET_VALUE);
  }

  // ----- helpers -----

  private String logText() {
    return appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.joining("\n"));
  }

  private List<String> warnLines() {
    return linesAtLevel(Level.WARN);
  }

  private List<String> errorLines() {
    return linesAtLevel(Level.ERROR);
  }

  private List<String> debugLines() {
    return linesAtLevel(Level.DEBUG);
  }

  private List<String> linesAtLevel(Level level) {
    return appender.list.stream()
        .filter(event -> event.getLevel() == level)
        .map(ILoggingEvent::getFormattedMessage)
        .collect(Collectors.toList());
  }

  private WorkspaceLayout stubWorkspace() {
    WorkspaceLayout layout =
        new WorkspaceLayout(
            Path.of("/workspaces/rex"),
            Path.of("/workspaces/rex/input"),
            Path.of("/workspaces/rex/output"),
            Path.of("/workspaces/rex/logs"));
    when(workspaceStore.prepare(REX_ID)).thenReturn(layout);
    return layout;
  }

  private void dispatchSuccessfully() {
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID))
        .thenReturn(Optional.of(BUNDLE_SENTINEL.getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);
    adapter.dispatch(dispatchRequest());
  }

  /** Stub the container-exit log-capture collaborators so {@code classifyExited} stays quiet. */
  private void stubExitCapture() {
    when(workspaceStore.readRawStdoutForCapture(REX_ID))
        .thenReturn(Optional.of(RawRunnerLog.empty()));
    when(workspaceStore.readRawStderrForCapture(REX_ID))
        .thenReturn(Optional.of(RawRunnerLog.empty()));
    when(executionService.findByPublicId(REX_ID)).thenReturn(Optional.empty());
    when(logCaptureService.captureLogs(any(), any(), any(), any(), any()))
        .thenReturn(
            new CapturedLogs("/runner-logs/" + REX_ID, 0L, DataClassification.LOCAL_ONLY, 0));
  }

  private RunnerDispatchRequest dispatchRequest() {
    return new RunnerDispatchRequest(
        REX_ID,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        RunnerKind.CODEX,
        Path.of("/scratch/context-bundle.v1.json"),
        new ExecutionConstraints(Duration.ofSeconds(600L), false),
        DataClassification.SHAREABLE_REDACTED);
  }

  /** Rebuild the default properties swapping ONLY the CODEX image tag. */
  private static RunnerProperties propertiesWithCodexImage(String codexImage) {
    RunnerProperties b = RunnerProperties.defaults();
    RunnerProperties.Docker d = b.docker();
    RunnerProperties.Docker docker =
        new RunnerProperties.Docker(
            d.defaultKind(),
            Map.of(
                RunnerKind.CODEX,
                codexImage,
                RunnerKind.CLAUDE,
                "deliveryline/claude-runner:latest"),
            d.workspaceRoot(),
            d.workspaceRetentionHours(),
            d.workspaceCleanupIntervalMs(),
            d.containerCreateTimeout(),
            d.containerStartTimeout(),
            d.danglingContainerMinAgeSeconds(),
            d.networkMode(),
            d.securityOpts());
    return new RunnerProperties(
        b.staleThresholdMultiplier(),
        b.stageTimeouts(),
        b.timeoutScanIntervalMs(),
        b.timeoutScanBatchSize(),
        b.staleScanIntervalMs(),
        b.pollIntervalMs(),
        b.recovery(),
        b.mock(),
        b.scheduling(),
        docker,
        b.secretEnvNames(),
        b.allowShareableLogs(),
        b.specStage(),
        b.planStage(),
        b.implementationStage(),
        b.openspec(),
        b.buildStage(),
        b.lintStage(),
        b.deliveryMode(),
        b.queueMaxDepth());
  }
}
