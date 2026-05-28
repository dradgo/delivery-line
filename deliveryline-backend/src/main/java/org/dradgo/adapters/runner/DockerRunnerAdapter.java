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
import org.dradgo.application.runner.spi.LogGrowthObservation;
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
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
public class DockerRunnerAdapter implements RecoverableRunnerAdapter {

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
  private final ConcurrentMap<String, LogGrowthObservation> rexIdToLastLogObservation =
      new ConcurrentHashMap<>();

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
    labels.put("deliveryline.stage", request.stage().value());
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

    String status = state.status();
    boolean runningLike =
        "created".equals(status)
            || "running".equals(status)
            || "paused".equals(status)
            || "restarting".equals(status);
    // Trap T2: heartbeat detection only modifies the Running branch — Completed / Failed / Unknown
    // classification stays unchanged. Short-circuit when the container has already exited.
    if (runningLike) {
      RunnerPollStatus heartbeat = detectHeartbeat(runnerExecutionId, containerId, state);
      if (heartbeat != null) {
        return heartbeat;
      }
    }

    switch (status) {
      case "created":
      case "running":
      case "paused":
      case "restarting":
        log.debug(
            "docker poll running runnerExecutionId={} containerId={} status={}",
            runnerExecutionId,
            containerId,
            status);
        return new RunnerPollStatus.Running();
      case "exited":
        return classifyExited(runnerExecutionId, containerId, state);
      case "dead":
      case "removing":
        log.warn(
            "docker poll terminal-failure runnerExecutionId={} containerId={} status={}",
            runnerExecutionId,
            containerId,
            status);
        return new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH);
      default:
        log.warn(
            "docker poll unknown-status runnerExecutionId={} containerId={} status={}",
            runnerExecutionId,
            containerId,
            status);
        return new RunnerPollStatus.Unknown();
    }
  }

  /**
   * Story 3.2 AC2: priority-ordered heartbeat-source check. Returns a {@link
   * RunnerPollStatus.HeartbeatTouched} when any source advances; otherwise returns {@code null} so
   * the caller falls through to the existing {@link RunnerPollStatus.Running} classification.
   */
  private RunnerPollStatus detectHeartbeat(
      String runnerExecutionId, String containerId, ContainerState state) {
    LogGrowthObservation previous = rexIdToLastLogObservation.get(runnerExecutionId);
    OffsetDateTime previousActivity =
        previous == null ? OffsetDateTime.MIN : previous.lastModifiedAt();

    // (a) container State.StartedAt — a brand-new start counts as activity.
    OffsetDateTime startedAt = state.startedAt();
    if (startedAt != null && startedAt.isAfter(previousActivity)) {
      // Persist the started-at marker as the floor for future log-growth comparisons.
      rexIdToLastLogObservation.put(runnerExecutionId, new LogGrowthObservation(0L, startedAt));
      log.debug(
          "docker heartbeat startedAt runnerExecutionId={} containerId={} startedAt={}",
          runnerExecutionId,
          containerId,
          startedAt);
      return new RunnerPollStatus.HeartbeatTouched(startedAt);
    }

    // (b) heartbeat.touch file — optional marker the runner image MAY touch periodically.
    Optional<OffsetDateTime> heartbeatTouch =
        workspaceStore.tryReadHeartbeatTouch(runnerExecutionId);
    if (heartbeatTouch.isPresent() && heartbeatTouch.get().isAfter(previousActivity)) {
      rexIdToLastLogObservation.put(
          runnerExecutionId,
          previous == null
              ? new LogGrowthObservation(0L, heartbeatTouch.get())
              : new LogGrowthObservation(previous.byteCount(), heartbeatTouch.get()));
      log.debug(
          "docker heartbeat heartbeatTouch runnerExecutionId={} containerId={} modifiedAt={}",
          runnerExecutionId,
          containerId,
          heartbeatTouch.get());
      return new RunnerPollStatus.HeartbeatTouched(heartbeatTouch.get());
    }

    // (c) logs/runner.stdout byte-count growth.
    Optional<LogGrowthObservation> observation = workspaceStore.observeLogGrowth(runnerExecutionId);
    if (observation.isPresent()) {
      LogGrowthObservation fresh = observation.get();
      // Review fix: baseline 0 (not -1) so a freshly-created EMPTY runner.stdout (byteCount == 0)
      // does NOT register as growth on the first poll — only real bytes count as activity.
      long previousBytes = previous == null ? 0L : previous.byteCount();
      if (fresh.byteCount() > previousBytes && fresh.lastModifiedAt().isAfter(previousActivity)) {
        rexIdToLastLogObservation.put(runnerExecutionId, fresh);
        log.debug(
            "docker heartbeat logGrowth runnerExecutionId={} containerId={} bytes={} modifiedAt={}",
            runnerExecutionId,
            containerId,
            fresh.byteCount(),
            fresh.lastModifiedAt());
        return new RunnerPollStatus.HeartbeatTouched(fresh.lastModifiedAt());
      }
    }

    return null;
  }

  @Override
  public Optional<RunnerPollStatus> recoverHandle(String runnerExecutionId) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    if (rexIdToContainerId.containsKey(runnerExecutionId)) {
      // Already have a live in-process handle; the broker should keep using normal polling.
      log.info(
          "docker recoverHandle skip runnerExecutionId={} reason=handle_already_present",
          runnerExecutionId);
      return safeRecoverPoll(runnerExecutionId);
    }
    Optional<String> probed;
    try {
      probed = docker.findContainerIdByRunnerExecutionId(runnerExecutionId);
    } catch (RuntimeException error) {
      log.warn(
          "docker recoverHandle probe failure runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
      return Optional.empty();
    }
    if (probed.isEmpty()) {
      log.info("docker recoverHandle no-container-found runnerExecutionId={}", runnerExecutionId);
      return Optional.empty();
    }
    String containerId = probed.get();
    rexIdToContainerId.putIfAbsent(runnerExecutionId, containerId);
    log.info(
        "docker recoverHandle container-id recovered runnerExecutionId={} containerId={}",
        runnerExecutionId,
        containerId);
    return safeRecoverPoll(runnerExecutionId);
  }

  /**
   * Wraps {@link #poll(String)} for the recovery probe so the {@link
   * RecoverableRunnerAdapter#recoverHandle(String)} contract ("must NOT throw — best-effort") holds
   * even if the engine inspect inside {@code poll} throws. A failed probe degrades to {@code
   * Optional.empty()} so the broker falls through to its orphan path rather than poisoning the
   * per-row recovery transaction.
   */
  private Optional<RunnerPollStatus> safeRecoverPoll(String runnerExecutionId) {
    try {
      return Optional.of(poll(runnerExecutionId));
    } catch (RuntimeException error) {
      log.warn(
          "docker recoverHandle poll failure runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
      return Optional.empty();
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
    rexIdToLastLogObservation.remove(runnerExecutionId);
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

  /**
   * Story 3.2 AC1: exposes the in-memory container-id handle so {@code
   * RunnerBroker.processSingleTimeout} can issue {@code docker stop}/{@code docker kill} against
   * the live container during the per-row timeout transition. Package-private — only the broker
   * (and the docker-adapter test surface) is allowed to call this.
   */
  public Optional<String> findContainerIdForTesting(String runnerExecutionId) {
    return Optional.ofNullable(rexIdToContainerId.get(runnerExecutionId));
  }

  @Override
  public Optional<String> findContainerIdFor(String runnerExecutionId) {
    return findContainerIdForTesting(runnerExecutionId);
  }

  @Override
  public boolean emitsDispatchedAfterAck() {
    return true;
  }

  @Override
  public TerminationOutcome terminate(String runnerExecutionId, Duration graceful) {
    Objects.requireNonNull(runnerExecutionId, "runnerExecutionId");
    Objects.requireNonNull(graceful, "graceful");
    String containerId = rexIdToContainerId.get(runnerExecutionId);
    if (containerId == null) {
      log.info("docker terminate no-op runnerExecutionId={} reason=unknown_id", runnerExecutionId);
      return TerminationOutcome.UNKNOWN;
    }
    try {
      docker.stopContainer(containerId, graceful);
      log.info(
          "docker terminate stop issued runnerExecutionId={} containerId={} graceful={}",
          runnerExecutionId,
          containerId,
          graceful);
    } catch (RuntimeException stopError) {
      log.warn(
          "docker terminate stop best-effort failure runnerExecutionId={} containerId={} cause={}",
          runnerExecutionId,
          containerId,
          stopError.toString());
      // fall through to the kill attempt
    }
    ContainerState postStop;
    try {
      postStop = docker.inspectContainer(containerId);
    } catch (RuntimeException inspectError) {
      log.warn(
          "docker terminate post-stop inspect failure runnerExecutionId={} containerId={} cause={}",
          runnerExecutionId,
          containerId,
          inspectError.toString());
      // Review fix: the post-stop state is unconfirmed — do NOT claim STOPPED_GRACEFULLY (which
      // would also suppress the kill fallback and be written as authoritative into the
      // runner.timeout event). Report UNKNOWN so the caller treats termination as unconfirmed.
      return TerminationOutcome.UNKNOWN;
    }
    boolean stillRunning =
        "running".equals(postStop.status())
            || "paused".equals(postStop.status())
            || "restarting".equals(postStop.status());
    if (!stillRunning) {
      return TerminationOutcome.STOPPED_GRACEFULLY;
    }
    try {
      docker.killContainer(containerId);
      log.warn(
          "docker terminate kill fallback runnerExecutionId={} containerId={}",
          runnerExecutionId,
          containerId);
      return TerminationOutcome.KILLED_AFTER_GRACE;
    } catch (RuntimeException killError) {
      log.error(
          "docker terminate kill best-effort failure runnerExecutionId={} containerId={} cause={}",
          runnerExecutionId,
          containerId,
          killError.toString());
      return TerminationOutcome.BEST_EFFORT_FAILURE;
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
