package org.dradgo.adapters.runner;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.dradgo.adapters.runner.docker.ContainerState;
import org.dradgo.adapters.runner.docker.CreateContainerSpec;
import org.dradgo.adapters.runner.docker.DockerEngineGateway;
import org.dradgo.adapters.runner.docker.DockerLogSanitizer;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerPollStatus;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Story 3.1 — real Docker container lifecycle adapter implementing the existing {@link
 * RunnerAdapter} port.
 *
 * <p>Mirrors the structure of {@code MockRunnerAdapter}: profile-gated {@code @Component},
 * non-blocking {@code dispatch}, deterministic {@code poll} branches, idempotent {@code cancel},
 * project-owned {@link ConcurrentMap} for rex-id → container-id correlation.
 *
 * <p>Scope guardrail: this story's adapter ONLY drives container create → start → wait → harvest
 * result file. Timeout enforcement + heartbeat + workspace cleanup live in story 3.2 (trap T9);
 * secret env injection lives in 3.5; log capture lives in 3.6.
 */
@Component
@Profile("runners.docker")
public class DockerRunnerAdapter implements RunnerAdapter {

  private static final Logger log = LoggerFactory.getLogger(DockerRunnerAdapter.class);

  private static final String CONTAINER_INPUT_MOUNT = "/workspace/input";
  private static final String CONTAINER_OUTPUT_MOUNT = "/workspace/output";
  private static final String CONTAINER_LOGS_MOUNT = "/workspace/logs";
  private static final String NETWORK_MODE_NONE = "none";

  private final RunnerScratchStore scratchStore;
  private final RunnerWorkspaceStore workspaceStore;
  private final DockerEngineGateway docker;
  private final RunnerProperties runnerProperties;
  private final Clock clock;
  private final ConcurrentMap<String, String> rexIdToContainerId = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public DockerRunnerAdapter(
      RunnerScratchStore scratchStore,
      RunnerWorkspaceStore workspaceStore,
      DockerEngineGateway docker,
      RunnerProperties runnerProperties) {
    this(scratchStore, workspaceStore, docker, runnerProperties, Clock.systemUTC());
  }

  DockerRunnerAdapter(
      RunnerScratchStore scratchStore,
      RunnerWorkspaceStore workspaceStore,
      DockerEngineGateway docker,
      RunnerProperties runnerProperties,
      Clock clock) {
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.docker = Objects.requireNonNull(docker, "docker");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public RunnerDispatchAck dispatch(RunnerDispatchRequest request) {
    Objects.requireNonNull(request, "request");
    String rexId =
        PublicIdPrefixes.require(request.runnerExecutionId(), PublicIdPrefixes.RUNNER_EXECUTION);
    String existingContainerId = rexIdToContainerId.get(rexId);
    if (existingContainerId != null) {
      return new RunnerDispatchAck("docker:" + existingContainerId);
    }
    RunnerKind kind = request.runnerKind();

    String image = runnerProperties.docker().imageTagFor(kind);
    String safeImage = DockerLogSanitizer.redactImageTag(image);
    log.info(
        "docker dispatch start runnerExecutionId={} workflowRunId={} kind={} image={}",
        rexId,
        request.workflowRunId(),
        kind.value(),
        safeImage);

    WorkspaceLayout layout = workspaceStore.prepare(rexId);
    byte[] bundleBytes =
        scratchStore
            .tryReadContextBundle(rexId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "scratch bundle missing for "
                            + rexId
                            + " — broker contract violated (RunnerBroker.dispatch must write the"
                            + " bundle to scratch before delegating to the adapter)"));
    workspaceStore.writeInputBundle(rexId, bundleBytes);

    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("deliveryline.runnerExecutionId", rexId);
    labels.put("deliveryline.workflowRunId", request.workflowRunId());
    labels.put("deliveryline.runnerKind", kind.value());
    labels.put(
        "deliveryline.dispatchedAt",
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).toString());

    CreateContainerSpec spec =
        new CreateContainerSpec(
            image,
            List.of(
                new CreateContainerSpec.BindMount(layout.input(), CONTAINER_INPUT_MOUNT, true),
                new CreateContainerSpec.BindMount(layout.output(), CONTAINER_OUTPUT_MOUNT, false),
                new CreateContainerSpec.BindMount(layout.logs(), CONTAINER_LOGS_MOUNT, false)),
            NETWORK_MODE_NONE,
            labels);

    String containerId = null;
    try {
      containerId = docker.createContainer(spec);
      String racedContainerId = rexIdToContainerId.putIfAbsent(rexId, containerId);
      if (racedContainerId != null) {
        docker.removeContainer(containerId, true);
        return new RunnerDispatchAck("docker:" + racedContainerId);
      }
      docker.startContainer(containerId);
    } catch (RuntimeException error) {
      if (containerId != null) {
        rexIdToContainerId.remove(rexId, containerId);
        try {
          docker.removeContainer(containerId, true);
        } catch (RuntimeException cleanupError) {
          log.warn(
              "docker dispatch cleanup failed runnerExecutionId={} containerId={} cause={}",
              rexId,
              containerId,
              cleanupError.toString());
        }
      }
      log.error(
          "docker dispatch failed runnerExecutionId={} workflowRunId={} kind={} image={} cause={}",
          rexId,
          request.workflowRunId(),
          kind.value(),
          safeImage,
          error.toString());
      throw new IllegalStateException(
          "Docker dispatch failed for " + rexId + ": " + error.getMessage(), error);
    }

    log.info(
        "docker dispatch ok runnerExecutionId={} containerId={} workspaceRoot={}",
        rexId,
        containerId,
        layout.root());
    return new RunnerDispatchAck("docker:" + containerId);
  }

  @Override
  public RunnerPollStatus poll(String runnerExecutionId) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    String containerId = rexIdToContainerId.get(runnerExecutionId);
    if (containerId == null) {
      // No handle — either dispatch never ran in this process, or we restarted before story 3.2
      // wires recovery back into the adapter. The broker's existing recoverOnStartup path treats
      // Unknown the same way it always has.
      return new RunnerPollStatus.Unknown();
    }
    ContainerState state;
    try {
      state = docker.inspectContainer(containerId);
    } catch (RuntimeException error) {
      log.warn(
          "docker poll inspect failed runnerExecutionId={} containerId={} cause={}",
          runnerExecutionId,
          containerId,
          error.toString());
      return new RunnerPollStatus.Unknown();
    }

    switch (state.status()) {
      case "created":
      case "running":
      case "paused":
      case "restarting":
        log.debug(
            "docker poll running runnerExecutionId={} containerId={} status={}",
            runnerExecutionId,
            containerId,
            state.status());
        return new RunnerPollStatus.Running();
      case "exited":
        return classifyExited(runnerExecutionId, containerId, state);
      case "dead":
      case "removing":
        log.warn(
            "docker poll terminal-failure runnerExecutionId={} containerId={} status={}",
            runnerExecutionId,
            containerId,
            state.status());
        return new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH);
      default:
        log.warn(
            "docker poll unknown-status runnerExecutionId={} containerId={} status={}",
            runnerExecutionId,
            containerId,
            state.status());
        return new RunnerPollStatus.Unknown();
    }
  }

  @Override
  public Optional<byte[]> tryReadResult(String runnerExecutionId) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    Optional<byte[]> bytes = workspaceStore.tryReadResult(runnerExecutionId);
    bytes.ifPresent(
        payload ->
            log.info(
                "docker tryReadResult hit runnerExecutionId={} bytes={}",
                runnerExecutionId,
                payload.length));
    return bytes;
  }

  @Override
  public void cancel(String runnerExecutionId) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    String containerId = rexIdToContainerId.remove(runnerExecutionId);
    if (containerId == null) {
      log.info("docker cancel no-op runnerExecutionId={} reason=unknown_id", runnerExecutionId);
      return;
    }
    try {
      ContainerState state = docker.inspectContainer(containerId);
      if (state.isExited()) {
        log.info(
            "docker cancel no-op runnerExecutionId={} containerId={} reason=already_exited",
            runnerExecutionId,
            containerId);
        return;
      }
      docker.stopContainer(containerId, Duration.ofSeconds(10L));
      log.info(
          "docker cancel issued runnerExecutionId={} containerId={}",
          runnerExecutionId,
          containerId);
    } catch (RuntimeException error) {
      // cancel must NEVER throw — best-effort, idempotent per RunnerAdapter contract.
      log.warn(
          "docker cancel best-effort failure runnerExecutionId={} containerId={} cause={}",
          runnerExecutionId,
          containerId,
          error.toString());
    }
  }

  private RunnerPollStatus classifyExited(
      String runnerExecutionId, String containerId, ContainerState state) {
    Optional<byte[]> result = workspaceStore.tryReadResult(runnerExecutionId);
    Integer exitCode = state.exitCode();
    if (result.isEmpty()) {
      // AC3.b + AC3.c: zero OR non-zero exit without a result file is a contract failure
      // (the runner promised a result file regardless of clean exit).
      log.warn(
          "docker poll exited-no-result runnerExecutionId={} containerId={} exitCode={}",
          runnerExecutionId,
          containerId,
          exitCode);
      return new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH);
    }
    log.info(
        "docker poll completed runnerExecutionId={} containerId={} exitCode={} bytes={}",
        runnerExecutionId,
        containerId,
        exitCode,
        result.get().length);
    return new RunnerPollStatus.Completed();
  }
}
