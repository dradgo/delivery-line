package org.dradgo.adapters.runner.lifecycle;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.adapters.files.LocalRunnerScratchStore;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.adapters.runner.DockerRunnerAdapter;
import org.dradgo.adapters.runner.docker.DefaultDockerEngineGateway;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.domain.registry.RunnerKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;

/**
 * Shared setup + container-launch helpers for the story-3.2a docker-runner lifecycle IT scaffolds
 * (timeout / heartbeat / recovery / dangling-cleanup). Mirrors {@code
 * DockerRunnerAdapterContainerLifecycleIT}'s lightweight construction: a real {@link DockerClient}
 * from Testcontainers' {@code DockerClientFactory}, a real {@link DefaultDockerEngineGateway}, and
 * file stores rooted at a {@link TempDir}. The runner image-tags point at {@code alpine:3.20}.
 *
 * <p><b>WSL2 gate (Trap T15):</b> these ITs exercise real {@code docker stop}/{@code kill} signal
 * behavior, which differs between Windows Docker Desktop and Linux. They MUST be run on WSL2 Ubuntu
 * before pushing. The tier also needs {@code alpine:3.20} pre-pulled ({@code docker pull
 * alpine:3.20}) — the adapter uses a raw DockerClient that does not auto-pull.
 */
abstract class DockerLifecycleITSupport {

  protected static final String TEST_IMAGE = "alpine:3.20";
  protected static final String RUN_ID = "run_lifecycleit01";

  @TempDir Path tempHome;

  protected DockerClient dockerClient;
  protected DefaultDockerEngineGateway gateway;
  protected LocalRunnerScratchStore scratchStore;
  protected LocalRunnerWorkspaceStore workspaceStore;
  protected DockerRunnerAdapter adapter;
  protected RunnerProperties properties;

  private final List<String> containersToCleanup = new ArrayList<>();

  @BeforeEach
  void baseSetUp() {
    dockerClient = DockerClientFactory.instance().client();
    gateway = new DefaultDockerEngineGateway(dockerClient);
    scratchStore = new LocalRunnerScratchStore(tempHome.toAbsolutePath().toString());
    workspaceStore = new LocalRunnerWorkspaceStore(tempHome.toAbsolutePath().toString());

    RunnerProperties.Docker dockerConfig =
        new RunnerProperties.Docker(
            RunnerKind.CODEX,
            Map.of(RunnerKind.CODEX, TEST_IMAGE, RunnerKind.CLAUDE, TEST_IMAGE),
            tempHome.resolve("runner-work"),
            1L,
            3_600_000L,
            Duration.ofSeconds(30L),
            Duration.ofSeconds(30L),
            /* danglingContainerMinAgeSeconds= */ 0L,
            "none",
            java.util.List.of());
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
            RunnerProperties.ImplementationStage.defaults(),
            RunnerProperties.OpenSpec.defaults(),
            RunnerProperties.BuildStage.defaults(),
            100);
    // Story 3.5: real RunnerSecretsService backed by a MockEnvironment carrying both provider keys
    // so dispatch resolution succeeds for either kind under the lifecycle ITs.
    RunnerSecretsService secretsService =
        new RunnerSecretsService(
            new MockEnvironment()
                .withProperty("CODEX_API_KEY", "sk-codex-it-value")
                .withProperty("ANTHROPIC_API_KEY", "sk-ant-it-value"),
            properties);
    // Story 3.6: these lifecycle ITs assert container stop/kill/recovery behavior, not log capture
    // (which has dedicated unit + contract coverage). Stub the capture collaborators so the exit
    // path's capture call is a no-op here and the ITs stay focused + DB-free.
    org.dradgo.application.runner.RunnerLogCaptureService logCaptureService =
        org.mockito.Mockito.mock(org.dradgo.application.runner.RunnerLogCaptureService.class);
    org.dradgo.application.runner.RunnerExecutionService executionService =
        org.mockito.Mockito.mock(org.dradgo.application.runner.RunnerExecutionService.class);
    // Public constructor (uses Clock.systemUTC internally) — accessible from this sub-package; the
    // lifecycle ITs operate on real wall-clock time anyway.
    adapter =
        new DockerRunnerAdapter(
            scratchStore,
            workspaceStore,
            gateway,
            properties,
            secretsService,
            logCaptureService,
            executionService);
  }

  @AfterEach
  void baseTearDown() {
    for (String id : containersToCleanup) {
      try {
        dockerClient.removeContainerCmd(id).withForce(true).exec();
      } catch (RuntimeException ignored) {
        // best-effort cleanup
      }
    }
  }

  /** Launch a started container running {@code cmd}, tagged with the deliveryline 5-label set. */
  protected String launchLabeledContainer(String rex, String... cmd) {
    return launch(deliverylineLabels(rex), cmd);
  }

  /** Launch a started container with NO deliveryline labels (the "unrelated" control case). */
  protected String launchUnlabeledContainer(String... cmd) {
    return launch(Map.of(), cmd);
  }

  private String launch(Map<String, String> labels, String... cmd) {
    var create = dockerClient.createContainerCmd(TEST_IMAGE).withCmd(cmd);
    if (!labels.isEmpty()) {
      create = create.withLabels(labels);
    }
    String id = create.exec().getId();
    containersToCleanup.add(id);
    dockerClient.startContainerCmd(id).exec();
    return id;
  }

  protected Map<String, String> deliverylineLabels(String rex) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("deliveryline.runnerExecutionId", rex);
    labels.put("deliveryline.workflowRunId", RUN_ID);
    labels.put("deliveryline.runnerKind", "codex");
    labels.put("deliveryline.stage", "investigation");
    labels.put("deliveryline.dispatchedAt", OffsetDateTime.now().toString());
    return labels;
  }

  protected Path logsDir(String rex) {
    return workspaceStore.workspaceRoot().resolve(rex).resolve("logs");
  }

  protected Path outputDir(String rex) {
    return workspaceStore.workspaceRoot().resolve(rex).resolve("output");
  }

  protected boolean isRunning(String containerId) {
    InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
    Boolean running = inspect.getState().getRunning();
    return Boolean.TRUE.equals(running);
  }

  /** Poll the engine until the container is not running; fail if it is still running at timeout. */
  protected void awaitNotRunning(String containerId, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (!isRunning(containerId)) {
        return;
      }
      sleepQuietly(Duration.ofMillis(200));
    }
    // Story 3.2a code-review (2026-05-29): fail loudly instead of returning silently. A silent
    // return let a test proceed against a still-running container (recovery exercising the wrong
    // branch) and still pass for the wrong reason.
    throw new AssertionError(
        "container "
            + containerId
            + " was still running after "
            + timeout
            + " — awaitNotRunning timed out");
  }

  protected static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
