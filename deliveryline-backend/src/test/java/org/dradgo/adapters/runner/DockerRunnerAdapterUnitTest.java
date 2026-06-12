package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.dradgo.adapters.runner.docker.ContainerState;
import org.dradgo.adapters.runner.docker.CreateContainerSpec;
import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.runner.CapturedLogs;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerLogCaptureService.RunnerLogTruncation;
import org.dradgo.application.runner.RunnerPollStatus;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.LogGrowthObservation;
import org.dradgo.application.runner.spi.RawRunnerLog;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 3.1 Task 7 — unit-level behavior of {@link DockerRunnerAdapter}. The {@link
 * DockerEngineGateway} is mocked, so these assertions cover the adapter's per-branch logic without
 * needing a real Docker daemon. Heavy-weight container ITs live in {@code
 * DockerRunnerAdapterContainerLifecycleIT} (tagged {@code docker-runner-it}).
 */
class DockerRunnerAdapterUnitTest {

  private static final String REX_ID = "rex_dr1234567890";
  private static final String RUN_ID = "run_dr1234567890";
  private static final String CONTAINER_ID = "abcdef0123456789";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-28T12:00:00Z"), ZoneOffset.UTC);

  private RunnerScratchStore scratchStore;
  private RunnerWorkspaceStore workspaceStore;
  private DockerEngineGateway gateway;
  private RunnerProperties properties;
  private RunnerSecretsService secretsService;
  private org.dradgo.application.runner.RunnerLogCaptureService logCaptureService;
  private org.dradgo.application.runner.RunnerExecutionService executionService;
  private DockerRunnerAdapter adapter;

  @BeforeEach
  void setUp() {
    scratchStore = Mockito.mock(RunnerScratchStore.class);
    workspaceStore = Mockito.mock(RunnerWorkspaceStore.class);
    gateway = Mockito.mock(DockerEngineGateway.class);
    properties = RunnerProperties.defaults();
    // Story 3.5: provide the agent-provider key so dispatch resolution succeeds for the CODEX kind
    // these tests dispatch. A dedicated missing-secret case lives in the secrets/scan test suites.
    secretsService =
        new RunnerSecretsService(
            new MockEnvironment().withProperty("CODEX_API_KEY", "sk-codex-unit-test-value"),
            properties);
    // Story 3.6: the capture path is exercised end-to-end in RunnerLogCaptureServiceTest +
    // DockerRunnerAdapterLogCaptureUnitTest; here we stub it so the existing lifecycle assertions
    // stay focused on dispatch/poll behavior.
    logCaptureService = Mockito.mock(org.dradgo.application.runner.RunnerLogCaptureService.class);
    executionService = Mockito.mock(org.dradgo.application.runner.RunnerExecutionService.class);
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
  }

  @Test
  void dispatchBuildsContainerSpecWithReadOnlyInputAndNoNetwork() {
    // AC2: input read-only, output/logs read-write, --network=none, four labels.
    WorkspaceLayout layout = stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID))
        .thenReturn(Optional.of("bundle-bytes".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    RunnerDispatchAck ack = adapter.dispatch(dispatchRequest());

    assertThat(ack.adapterRef()).isEqualTo("docker:" + CONTAINER_ID);
    ArgumentCaptor<CreateContainerSpec> specCaptor =
        ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(specCaptor.capture());
    verify(gateway).startContainer(CONTAINER_ID);
    verify(workspaceStore).writeInputBundle(eq(REX_ID), any(byte[].class));

    CreateContainerSpec spec = specCaptor.getValue();
    assertThat(spec.image()).isEqualTo("deliveryline/codex-runner:latest");
    assertThat(spec.networkMode()).isEqualTo("none");
    assertThat(spec.binds()).hasSize(3);
    assertThat(spec.binds().get(0).containerPath()).isEqualTo("/workspace/input");
    assertThat(spec.binds().get(0).readOnly()).isTrue();
    assertThat(spec.binds().get(1).containerPath()).isEqualTo("/workspace/output");
    assertThat(spec.binds().get(1).readOnly()).isFalse();
    assertThat(spec.binds().get(2).containerPath()).isEqualTo("/workspace/logs");
    assertThat(spec.binds().get(2).readOnly()).isFalse();
    assertThat(spec.labels())
        .containsEntry("deliveryline.runnerExecutionId", REX_ID)
        .containsEntry("deliveryline.workflowRunId", RUN_ID)
        .containsEntry("deliveryline.runnerKind", "codex")
        .containsKey("deliveryline.dispatchedAt");
  }

  @Test
  void dispatchAddsRepoMountAndCallsPrepareWorkspaceWhenRepositoryRefPresent() {
    // Story 3.9 AC2/AC4 — a repositoryRef-bearing dispatch adds a 4th /workspace/repo (rw) mount
    // and
    // delegates to RepositoryWorkspaceService.prepareWorkspace; no-repo dispatches are unaffected
    // (covered by dispatchBuildsContainerSpecWithReadOnlyInputAndNoNetwork above).
    RepositoryWorkspaceService repoService = Mockito.mock(RepositoryWorkspaceService.class);
    DockerRunnerAdapter repoAdapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            properties,
            secretsService,
            logCaptureService,
            executionService,
            CLOCK,
            repoService);
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);
    when(repoService.prepareWorkspace(
            eq(RUN_ID),
            eq(RunnerStage.INVESTIGATION),
            eq(REX_ID),
            eq("DEL-9"),
            eq("Fix dispatch"),
            eq("GH-101")))
        .thenReturn(
            new RepositoryWorkspaceService.RepositoryMount(
                Path.of("/workspaces/rex/repo"),
                "/workspace/repo",
                "main",
                "deliveryline/DEL-9/stage-12345678"));

    RunnerDispatchRequest request =
        new RunnerDispatchRequest(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerKind.CODEX,
            Path.of("/scratch/context-bundle.v1.json"),
            new ExecutionConstraints(Duration.ofSeconds(600L), false),
            DataClassification.SHAREABLE_REDACTED,
            "GH-101",
            "DEL-9",
            "Fix dispatch");

    repoAdapter.dispatch(request);

    verify(repoService)
        .prepareWorkspace(
            RUN_ID, RunnerStage.INVESTIGATION, REX_ID, "DEL-9", "Fix dispatch", "GH-101");
    ArgumentCaptor<CreateContainerSpec> specCaptor =
        ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(specCaptor.capture());
    CreateContainerSpec spec = specCaptor.getValue();
    assertThat(spec.binds()).hasSize(4);
    assertThat(spec.binds().get(3).containerPath()).isEqualTo("/workspace/repo");
    assertThat(spec.binds().get(3).readOnly()).isFalse();
  }

  @Test
  void dispatchFailsFastWhenRepositoryRefPresentButRepositoryServiceMissing() {
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    RunnerDispatchRequest request =
        new RunnerDispatchRequest(
            REX_ID,
            RUN_ID,
            RunnerStage.INVESTIGATION,
            RunnerKind.CODEX,
            Path.of("/scratch/context-bundle.v1.json"),
            new ExecutionConstraints(Duration.ofSeconds(600L), false),
            DataClassification.SHAREABLE_REDACTED,
            "GH-101",
            "DEL-9",
            "Fix dispatch");

    assertThatThrownBy(() -> adapter.dispatch(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("repository workspace service");
    verify(gateway, never()).createContainer(any());
  }

  @Test
  void dispatchInjectsProviderKeyIntoEnvButNeverIntoLabels() {
    // Story 3.5 AC3/AC11(e): the agent-provider key reaches the container ENV (Config.Env) but
    // NEVER the label set (docker inspect labels carry no secret — Trap T9).
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    adapter.dispatch(dispatchRequest());

    ArgumentCaptor<CreateContainerSpec> specCaptor =
        ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(specCaptor.capture());
    CreateContainerSpec spec = specCaptor.getValue();
    assertThat(spec.environment())
        .containsEntry("CODEX_API_KEY", "sk-codex-unit-test-value")
        .containsEntry("DELIVERYLINE_RUNNER_STAGE", "investigation");
    assertThat(spec.labels().values())
        .as("no container label value may carry the secret")
        .noneMatch(v -> v.contains("sk-codex-unit-test-value"));
  }

  @Test
  void dispatchLogsNeverContainTheProviderKeyValue() {
    // Story 3.5 AC2/AC11(g): the dispatch path logs only a secretVarCount — never the value.
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(DockerRunnerAdapter.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      adapter.dispatch(dispatchRequest());
    } finally {
      logger.detachAppender(appender);
    }

    String logs =
        appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .collect(java.util.stream.Collectors.joining("\n"));
    assertThat(logs).doesNotContain("sk-codex-unit-test-value");
    assertThat(logs).contains("secretVarCount=1");
  }

  @Test
  void dispatchThrowsDoctorRunnerSecretMissingWhenProviderKeyAbsent() {
    // Story 3.5 AC1/AC11(b): an absent provider key fails fast at DISPATCH time (not startup).
    DockerRunnerAdapter noSecretAdapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            properties,
            new RunnerSecretsService(new MockEnvironment(), properties),
            logCaptureService,
            executionService,
            CLOCK);
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));

    assertThatThrownBy(() -> noSecretAdapter.dispatch(dispatchRequest()))
        .isInstanceOf(org.dradgo.domain.DomainException.class)
        .satisfies(
            ex ->
                assertThat(((org.dradgo.domain.DomainException) ex).errorCode())
                    .isEqualTo(
                        org.dradgo.domain.registry.DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING));
    // Fail fast BEFORE touching the engine — no container is created.
    verify(gateway, org.mockito.Mockito.never()).createContainer(any());
  }

  @Test
  void dispatchIsIdempotentForSameRunnerExecutionId() {
    dispatchSuccessfully();

    RunnerDispatchAck replay = adapter.dispatch(dispatchRequest());

    assertThat(replay.adapterRef()).isEqualTo("docker:" + CONTAINER_ID);
    verify(gateway, times(1)).createContainer(any());
    verify(gateway, times(1)).startContainer(CONTAINER_ID);
  }

  @Test
  void dispatchRemovesCreatedContainerWhenStartFails() {
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);
    org.mockito.Mockito.doThrow(new RuntimeException("start failed"))
        .when(gateway)
        .startContainer(CONTAINER_ID);

    assertThatThrownBy(() -> adapter.dispatch(dispatchRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Docker dispatch failed");

    verify(gateway).removeContainer(CONTAINER_ID, true);
    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Unknown.class);
  }

  @Test
  void dispatchLogsRedactedImageTagWhenCredentialsAppearInRegistryUrl() {
    RunnerProperties.Docker dockerConfig =
        new RunnerProperties.Docker(
            RunnerKind.CODEX,
            Map.of(
                RunnerKind.CODEX,
                "user:secret@registry.example.test/deliveryline/codex:latest",
                RunnerKind.CLAUDE,
                "deliveryline/claude-runner:latest"),
            Path.of("runner-work"),
            24L,
            3_600_000L,
            Duration.ofSeconds(30L),
            Duration.ofSeconds(30L),
            120L,
            "none");
    properties =
        new RunnerProperties(
            2.0d,
            Map.of(),
            10_000L,
            50,
            60_000L,
            5_000L,
            RunnerProperties.Recovery.defaults(),
            RunnerProperties.Mock.defaults(),
            RunnerProperties.Scheduling.defaults(),
            dockerConfig,
            RunnerProperties.defaultSecretEnvNames(),
            false,
            RunnerProperties.SpecStage.defaults(),
            RunnerProperties.PlanStage.defaults(),
            RunnerProperties.ImplementationStage.defaults());
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
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(DockerRunnerAdapter.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      adapter.dispatch(dispatchRequest());
    } finally {
      logger.detachAppender(appender);
    }

    String logs =
        appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
            .collect(java.util.stream.Collectors.joining("\n"));
    // Story 3.5: the dispatch path now logs a benign "secretVarCount" field, so assert the actual
    // embedded CREDENTIAL ("user:secret@...") is redacted rather than the bare word "secret".
    assertThat(logs).doesNotContain("user:secret");
    assertThat(logs).contains("***@registry.example.test/deliveryline/codex:latest");
  }

  @Test
  void dispatchRaisesWhenScratchBundleMissing() {
    stubWorkspace();
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> adapter.dispatch(dispatchRequest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("broker contract violated");
  }

  @Test
  void pollReturnsUnknownWhenRexIdNotDispatched() {
    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Unknown.class);
  }

  @Test
  void pollReturnsRunningForCreatedRunningPausedRestarting() {
    dispatchSuccessfully();
    for (String status : List.of("created", "running", "paused", "restarting")) {
      when(gateway.inspectContainer(CONTAINER_ID))
          .thenReturn(new ContainerState(status, null, "none", List.of(), Map.of()));
      assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Running.class);
    }
  }

  @Test
  void pollReturnsCompletedWhenExitedWithResultFile() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.of("result".getBytes()));

    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Completed.class);
  }

  @Test
  void pollExitedCapturesStdoutAndStderrAndPersistsRawOutputReference() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));
    when(workspaceStore.readRawStdoutForCapture(REX_ID))
        .thenReturn(Optional.of(new RawRunnerLog("stdout secret", false)));
    when(workspaceStore.readRawStderrForCapture(REX_ID))
        .thenReturn(Optional.of(new RawRunnerLog("stderr secret", false)));
    when(executionService.findByPublicId(REX_ID))
        .thenReturn(Optional.of(runnerExecutionSnapshot()));
    CapturedLogs captured =
        new CapturedLogs("/runner-logs/" + REX_ID, 42L, DataClassification.LOCAL_ONLY, 2);
    when(logCaptureService.captureLogs(
            REX_ID, RUN_ID, "stdout secret", "stderr secret", RunnerLogTruncation.NONE))
        .thenReturn(captured);
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.of("result".getBytes()));

    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Completed.class);

    verify(logCaptureService)
        .captureLogs(REX_ID, RUN_ID, "stdout secret", "stderr secret", RunnerLogTruncation.NONE);
    verify(executionService).recordRawOutput(REX_ID, captured);
  }

  @Test
  void pollExitedSwallowsCaptureFailureAndStillClassifiesResult() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));
    when(workspaceStore.readRawStdoutForCapture(REX_ID))
        .thenReturn(Optional.of(new RawRunnerLog("stdout", false)));
    when(workspaceStore.readRawStderrForCapture(REX_ID))
        .thenReturn(Optional.of(RawRunnerLog.empty()));
    when(executionService.findByPublicId(REX_ID))
        .thenReturn(Optional.of(runnerExecutionSnapshot()));
    when(logCaptureService.captureLogs(anyString(), anyString(), anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("disk full"));
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.of("result".getBytes()));

    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Completed.class);

    verify(executionService, never()).recordRawOutput(eq(REX_ID), any());
  }

  @Test
  void pollReturnsFailedRunnerCrashWhenExitedZeroWithoutResult() {
    // AC3.c: clean exit with no result file → RUNNER_CRASH.
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.empty());

    RunnerPollStatus status = adapter.poll(REX_ID);
    assertThat(status).isInstanceOf(RunnerPollStatus.Failed.class);
    assertThat(((RunnerPollStatus.Failed) status).failureCategory())
        .isEqualTo(FailureCategory.RUNNER_CRASH);
  }

  @Test
  void pollReturnsFailedRunnerCrashWhenExitedNonZeroWithoutResult() {
    // AC3.b: non-zero exit without result → RUNNER_CRASH.
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 137, "none", List.of(), Map.of()));
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.empty());

    RunnerPollStatus status = adapter.poll(REX_ID);
    assertThat(status).isInstanceOf(RunnerPollStatus.Failed.class);
    assertThat(((RunnerPollStatus.Failed) status).failureCategory())
        .isEqualTo(FailureCategory.RUNNER_CRASH);
  }

  @Test
  void pollReturnsFailedRunnerCrashForDeadOrRemovingState() {
    dispatchSuccessfully();
    for (String status : List.of("dead", "removing")) {
      when(gateway.inspectContainer(CONTAINER_ID))
          .thenReturn(new ContainerState(status, null, "none", List.of(), Map.of()));
      RunnerPollStatus poll = adapter.poll(REX_ID);
      assertThat(poll).isInstanceOf(RunnerPollStatus.Failed.class);
      assertThat(((RunnerPollStatus.Failed) poll).failureCategory())
          .isEqualTo(FailureCategory.RUNNER_CRASH);
    }
  }

  @Test
  void tryReadResultDelegatesToWorkspaceStore() {
    when(workspaceStore.tryReadResult(REX_ID)).thenReturn(Optional.of("payload".getBytes()));

    assertThat(adapter.tryReadResult(REX_ID)).isPresent();
    verify(workspaceStore).tryReadResult(REX_ID);
  }

  @Test
  void cancelUnknownRexIdIsNoOpAndDoesNotThrow() {
    assertThatNoException().isThrownBy(() -> adapter.cancel(REX_ID));
    verify(gateway, never()).stopContainer(anyString(), any());
  }

  @Test
  void cancelOnAlreadyExitedDoesNotIssueStop() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("exited", 0, "none", List.of(), Map.of()));

    adapter.cancel(REX_ID);

    verify(gateway, never()).stopContainer(anyString(), any());
    verify(gateway, never()).removeContainer(anyString(), anyBoolean());
  }

  @Test
  void cancelOnRunningIssuesDockerStopWithTenSecondGrace() {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of()));

    adapter.cancel(REX_ID);

    verify(gateway, times(1)).stopContainer(CONTAINER_ID, Duration.ofSeconds(10L));
    // Trap T11: cancel MUST NOT call remove — that's story 3.2's cleanup job.
    verify(gateway, never()).removeContainer(anyString(), anyBoolean());
  }

  @Test
  void cancelTolerablyAbsorbsInspectFailure() {
    // cancel contract: best-effort, idempotent, MUST NOT throw.
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenThrow(new RuntimeException("docker inspect blew up"));

    assertThatNoException().isThrownBy(() -> adapter.cancel(REX_ID));
  }

  // ===== Story 3.2a AC8 — detectHeartbeat residual hardening ====================

  @Test
  void emptyRunnerStdoutDoesNotRegisterAsGrowthOnFirstPoll() {
    // Story 3.2a AC8: a freshly-created EMPTY runner.stdout (byteCount == 0) must NOT count as
    // heartbeat activity on the first poll — only real bytes do. startedAt=null so branch (a) is
    // skipped; heartbeat.touch absent; log growth is 0 bytes → no HeartbeatTouched.
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of(), null));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(0L, OffsetDateTime.now(CLOCK))));

    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Running.class);
  }

  @Test
  void logGrowthAdvancesMonotonicallyAcrossMillisecondPrecisionTimestamps() {
    // Story 3.2a AC8: ms-precision timestamps compare monotonically — genuine growth on a later
    // (by ms) timestamp fires; a stale observation (fewer bytes / earlier ts) never regresses the
    // stored high-water mark, so it returns Running rather than a spurious HeartbeatTouched.
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of(), null));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    OffsetDateTime t1 = OffsetDateTime.now(CLOCK);
    OffsetDateTime t2 = t1.plusNanos(5_000_000L); // +5ms

    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(100L, t1)));
    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.HeartbeatTouched.class);

    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(200L, t2)));
    RunnerPollStatus second = adapter.poll(REX_ID);
    assertThat(second).isInstanceOf(RunnerPollStatus.HeartbeatTouched.class);
    assertThat(((RunnerPollStatus.HeartbeatTouched) second).activityTimestamp()).isEqualTo(t2);

    // A stale observation (fewer bytes, earlier ts) must NOT regress the mark → Running, not a
    // spurious heartbeat.
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(50L, t1)));
    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Running.class);
  }

  @Test
  void concurrentPollsNeverRegressTheStoredObservation() throws InterruptedException {
    // Story 3.2a AC8 (a): two threads polling the same rex concurrently must not corrupt or regress
    // the stored high-water mark (atomic merge). After a storm of polls at a HIGH observation, a
    // subsequent poll at a LOW observation returns Running (the mark held).
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of(), null));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    OffsetDateTime high = OffsetDateTime.now(CLOCK).plusSeconds(60);
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(1_000_000L, high)));

    CountDownLatch start = new CountDownLatch(1);
    Runnable hammer =
        () -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
          }
          for (int i = 0; i < 200; i++) {
            adapter.poll(REX_ID);
          }
        };
    Thread a = new Thread(hammer);
    Thread b = new Thread(hammer);
    a.start();
    b.start();
    start.countDown();
    a.join(TimeUnit.SECONDS.toMillis(10));
    b.join(TimeUnit.SECONDS.toMillis(10));

    // The stored mark is now (1_000_000, high). A stale, lower observation must not fire.
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(
            Optional.of(new LogGrowthObservation(10L, OffsetDateTime.now(CLOCK).minusSeconds(60))));
    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.Running.class);
  }

  @Test
  void recoverHandleReSeedsObservationFloorSoStaleStartedAtDoesNotReEmitHeartbeat() {
    // Story 3.2a AC8 (b): on a broker restart the in-process observation map is empty.
    // recoverHandle
    // must re-seed the floor so the container's (stale, past) StartedAt and already-written log
    // bytes do NOT re-emit HeartbeatTouched — the first recovery poll classifies Running instead.
    when(gateway.findContainerIdByRunnerExecutionId(REX_ID)).thenReturn(Optional.of(CONTAINER_ID));
    OffsetDateTime staleStartedAt = OffsetDateTime.now(CLOCK).minusHours(1);
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(
            new ContainerState("running", null, "none", List.of(), Map.of(), staleStartedAt));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(500L, staleStartedAt)));

    Optional<RunnerPollStatus> recovered = adapter.recoverHandle(REX_ID);

    assertThat(recovered).isPresent();
    assertThat(recovered.get()).isInstanceOf(RunnerPollStatus.Running.class);
  }

  @Test
  void recoverHandleReSeedDoesNotRegressExistingHighWaterObservation() throws Exception {
    dispatchSuccessfully();
    when(gateway.inspectContainer(CONTAINER_ID))
        .thenReturn(new ContainerState("running", null, "none", List.of(), Map.of(), null));
    when(workspaceStore.tryReadHeartbeatTouch(REX_ID)).thenReturn(Optional.empty());
    OffsetDateTime high = OffsetDateTime.now(CLOCK).plusSeconds(60);
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(1_000L, high)));
    assertThat(adapter.poll(REX_ID)).isInstanceOf(RunnerPollStatus.HeartbeatTouched.class);

    java.lang.reflect.Field handles =
        DockerRunnerAdapter.class.getDeclaredField("rexIdToContainerId");
    handles.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.concurrent.ConcurrentMap<String, String> handleMap =
        (java.util.concurrent.ConcurrentMap<String, String>) handles.get(adapter);
    handleMap.remove(REX_ID);

    when(gateway.findContainerIdByRunnerExecutionId(REX_ID)).thenReturn(Optional.of(CONTAINER_ID));
    OffsetDateTime lower = OffsetDateTime.now(CLOCK).plusSeconds(30);
    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(500L, lower)));
    assertThat(adapter.recoverHandle(REX_ID)).isPresent();

    when(workspaceStore.observeLogGrowth(REX_ID))
        .thenReturn(Optional.of(new LogGrowthObservation(800L, lower)));
    assertThat(adapter.poll(REX_ID))
        .as("recoverHandle must not lower the previous high-water mark")
        .isInstanceOf(RunnerPollStatus.Running.class);
  }

  // ===== test helpers ============================================================

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
    when(scratchStore.tryReadContextBundle(REX_ID)).thenReturn(Optional.of("bundle".getBytes()));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);
    adapter.dispatch(dispatchRequest());
  }

  private RunnerDispatchRequest dispatchRequest() {
    return dispatchRequest(RunnerKind.CODEX);
  }

  private RunnerDispatchRequest dispatchRequest(RunnerKind kind) {
    return new RunnerDispatchRequest(
        REX_ID,
        RUN_ID,
        RunnerStage.INVESTIGATION,
        kind,
        Path.of("/scratch/context-bundle.v1.json"),
        new ExecutionConstraints(Duration.ofSeconds(600L), false),
        DataClassification.SHAREABLE_REDACTED);
  }

  private static RunnerExecutionSnapshot runnerExecutionSnapshot() {
    OffsetDateTime now = OffsetDateTime.now(CLOCK);
    return new RunnerExecutionSnapshot(
        REX_ID,
        RUN_ID,
        RunnerStage.EXECUTION,
        org.dradgo.domain.registry.RunnerExecutionStatus.RUNNING,
        1,
        now,
        now.plusSeconds(600),
        null,
        null,
        now,
        null);
  }
}
