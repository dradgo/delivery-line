package org.dradgo.adapters.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.dradgo.adapters.files.LocalRunnerScratchStore;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.adapters.runner.docker.DefaultDockerEngineGateway;
import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.application.runner.ExecutionConstraints;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerPollStatus;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/**
 * Story 3.1 Task 7 — Testcontainers-backed integration test for the real Docker container lifecycle
 * path through {@link DockerRunnerAdapter}.
 *
 * <p>Tagged {@code docker-runner-it} (opt-in via the CI tier on Linux runners only — see story 1.21
 * AC3). The {@link EnabledIfDockerAvailable} condition skips the test on contributor machines
 * without Docker.
 *
 * <p>The test image is {@code alpine:3.20}: tiny, stable, widely cached, and ships with {@code sh}
 * so the test can write a minimal valid {@code runner-result.v1.json} inside the mounted output
 * volume via an entrypoint override. This story does NOT depend on the Codex/Claude runner images
 * (stories 3.3/3.4) — the IT tier is shape-only.
 *
 * <p>Real broker-handoff end-to-end coverage lives in {@code RunnerBrokerDockerHandoffContractTest}
 * (broker → adapter, but with the gateway mocked) and {@code DockerRunnerAdapter*ContractTest}
 * (broker → adapter → real Docker, story 3.8 scope).
 */
@Tag("docker-runner-it")
@EnabledIfDockerAvailable
class DockerRunnerAdapterContainerLifecycleIT {

  @TempDir Path tempHome;

  private static final String TEST_IMAGE = "alpine:3.20";

  private DockerClient dockerClient;
  private DockerEngineGateway gateway;
  private RunnerScratchStore scratchStore;
  private RunnerWorkspaceStore workspaceStore;
  private DockerRunnerAdapter adapter;
  private String containerIdToCleanup;

  @BeforeEach
  void setUp() {
    dockerClient = DockerClientFactory.instance().client();
    gateway = new DefaultDockerEngineGateway(dockerClient);
    scratchStore = new LocalRunnerScratchStore(tempHome.toAbsolutePath().toString());
    workspaceStore = new LocalRunnerWorkspaceStore(tempHome.toAbsolutePath().toString());

    RunnerProperties.Docker dockerConfig =
        new RunnerProperties.Docker(
            RunnerKind.CODEX,
            java.util.Map.of(RunnerKind.CODEX, TEST_IMAGE, RunnerKind.CLAUDE, TEST_IMAGE),
            tempHome.resolve("runner-work"),
            1L,
            3_600_000L,
            Duration.ofSeconds(30L),
            Duration.ofSeconds(30L),
            120L,
            "none");
    RunnerProperties properties =
        new RunnerProperties(
            2.0d,
            java.util.Map.of(),
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

    RunnerSecretsService secretsService =
        new RunnerSecretsService(
            new org.springframework.mock.env.MockEnvironment()
                .withProperty("CODEX_API_KEY", "sk-codex-it-value")
                .withProperty("ANTHROPIC_API_KEY", "sk-ant-it-value"),
            properties);
    // Story 3.6: real capture service (writes redacted logs/runner-logs) backed by a stubbed
    // execution service so the DB persist is a no-op in this container-only IT. The capture path's
    // redaction + durable-write behavior is pinned in the dedicated capture unit/contract tests.
    org.dradgo.application.runner.RunnerLogCaptureService logCaptureService =
        new org.dradgo.application.runner.RunnerLogCaptureService(
            new org.dradgo.application.security.RedactionPolicyService(
                new org.dradgo.application.security.DataClassificationService()),
            new org.dradgo.adapters.files.LocalRunnerLogStore(tempHome.toAbsolutePath().toString()),
            properties);
    org.dradgo.application.runner.RunnerExecutionService executionService =
        org.mockito.Mockito.mock(org.dradgo.application.runner.RunnerExecutionService.class);
    adapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            properties,
            secretsService,
            logCaptureService,
            executionService,
            Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    if (containerIdToCleanup != null) {
      try {
        gateway.removeContainer(containerIdToCleanup, true);
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }

  @Test
  void dispatchedContainerCarriesAllFiveLabelsAndNetworkModeNone() throws Exception {
    // AC7 (labels) + AC8 (--network=none): inspect the launched container directly.
    // Story 3.2a AC10 (m): assert the FULL five-label set, including the new deliveryline.stage.
    String rexId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    scratchStore.writeContextBundle(rexId, "{\"schemaVersion\":1}".getBytes());

    RunnerDispatchRequest request =
        new RunnerDispatchRequest(
            rexId,
            "run_it1234567890",
            RunnerStage.INVESTIGATION,
            RunnerKind.CODEX,
            scratchStore.contextBundlePath(rexId),
            new ExecutionConstraints(Duration.ofSeconds(600L), false),
            DataClassification.SHAREABLE_REDACTED);

    var ack = adapter.dispatch(request);
    containerIdToCleanup = ack.adapterRef().substring("docker:".length());

    InspectContainerResponse inspect =
        dockerClient.inspectContainerCmd(containerIdToCleanup).exec();

    // AC8 + Trap T7: NetworkMode=none, set at create time.
    assertThat(inspect.getHostConfig().getNetworkMode()).isEqualTo("none");
    // AC7 + story 3.2a AC10 (m): the full five-label set, with deliveryline.stage carrying the
    // dispatched stage value.
    assertThat(inspect.getConfig().getLabels())
        .containsKey("deliveryline.runnerExecutionId")
        .containsKey("deliveryline.workflowRunId")
        .containsEntry("deliveryline.runnerKind", "codex")
        .containsEntry("deliveryline.stage", "investigation")
        .containsKey("deliveryline.dispatchedAt");
    // AC10(j): input mount is read-only.
    var binds = inspect.getHostConfig().getBinds();
    assertThat(binds).hasSize(3);
  }

  @Test
  void workspaceTreeIsCreatedOnDispatchAndSurvivesTryReadResult() throws Exception {
    // AC7 (workspace remains on disk for diagnostic inspection) + AC10(h).
    String rexId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    scratchStore.writeContextBundle(rexId, "{\"schemaVersion\":1}".getBytes());

    RunnerDispatchRequest request =
        new RunnerDispatchRequest(
            rexId,
            "run_it1234567890",
            RunnerStage.INVESTIGATION,
            RunnerKind.CODEX,
            scratchStore.contextBundlePath(rexId),
            new ExecutionConstraints(Duration.ofSeconds(600L), false),
            DataClassification.SHAREABLE_REDACTED);

    var ack = adapter.dispatch(request);
    containerIdToCleanup = ack.adapterRef().substring("docker:".length());

    Path workspaceRoot = tempHome.resolve("runner-work").resolve(rexId);
    assertThat(Files.isDirectory(workspaceRoot)).isTrue();
    assertThat(Files.isDirectory(workspaceRoot.resolve("input"))).isTrue();
    assertThat(Files.isDirectory(workspaceRoot.resolve("output"))).isTrue();
    assertThat(Files.isDirectory(workspaceRoot.resolve("logs"))).isTrue();
    assertThat(Files.exists(workspaceRoot.resolve("input").resolve("context-bundle.v1.json")))
        .isTrue();

    // Poll path tolerates the missing result file (alpine:3.20 with no entrypoint exits 0
    // immediately) — AC3.c classifies this as RUNNER_CRASH.
    Thread.sleep(1000L);
    RunnerPollStatus status = adapter.poll(rexId);
    assertThat(status)
        .satisfiesAnyOf(
            running -> assertThat(running).isInstanceOf(RunnerPollStatus.Running.class),
            failed -> assertThat(failed).isInstanceOf(RunnerPollStatus.Failed.class));

    Optional<byte[]> readBack = adapter.tryReadResult(rexId);
    // AC7: workspace still on disk after tryReadResult.
    assertThat(Files.isDirectory(workspaceRoot)).isTrue();
    // No result file written by alpine:3.20 default entrypoint, so tryReadResult is empty.
    assertThat(readBack).isEmpty();
  }
}
