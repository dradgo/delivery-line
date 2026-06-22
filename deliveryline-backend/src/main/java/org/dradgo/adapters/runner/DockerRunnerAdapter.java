package org.dradgo.adapters.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.dradgo.application.runner.CapturedLogs;
import org.dradgo.application.runner.ExecutionSubStage;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerExecutionService;
import org.dradgo.application.runner.RunnerLogCaptureService;
import org.dradgo.application.runner.RunnerLogCaptureService.RunnerLogTruncation;
import org.dradgo.application.runner.RunnerPollStatus;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerSecretsService;
import org.dradgo.application.runner.spi.LogGrowthObservation;
import org.dradgo.application.runner.spi.RawRunnerLog;
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.application.runner.workspace.RepositoryWorkspaceService;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
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
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final RunnerScratchStore scratchStore;
  private final RunnerWorkspaceStore workspaceStore;
  private final DockerEngineGateway docker;
  private final RunnerProperties runnerProperties;
  private final RunnerSecretsService runnerSecretsService;
  // Story 3.6 AC2/AC8: the adapter is the ONLY class allowed to touch raw runner output. It reads
  // the raw workspace logs at container exit and hands them straight to the capture service (which
  // redacts before any persist). The raw bytes are method-local — never field-stored here (Trap
  // T1).
  private final RunnerLogCaptureService runnerLogCaptureService;
  private final RunnerExecutionService runnerExecutionService;
  // Story 3.9 (Decision D0/D3, Trap T4) — nullable repository-workspace seam. Injected via
  // ObjectProvider so the public test ctor signature stays stable and the ctor-fan-out trap is
  // dodged; null in unit tests and absent in lean contexts. When present + the dispatch carries a
  // repositoryRef, prepareWorkspace clones the repo and a /workspace/repo mount is added.
  @Nullable private final RepositoryWorkspaceService repositoryWorkspaceService;
  private final Clock clock;
  private final ConcurrentMap<String, String> rexIdToContainerId = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LogGrowthObservation> rexIdToLastLogObservation =
      new ConcurrentHashMap<>();

  // Story 3d-2 (AC1) — per-project reviewer-credential resolution for a REVIEW dispatch. Optional
  // SETTER injection (mirroring RunnerBroker's resolver) so none of the four telescoping ctors —
  // nor their test sites — change. Present in production (the application.project @Components);
  // null in lean adapter unit slices, where a REVIEW dispatch never occurs. adapters→application is
  // an allowed dependency direction (only application→adapters is forbidden by ArchUnit).
  @Nullable
  private org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver;

  @Nullable
  private org.dradgo.application.project.ProjectConnectorResolver projectConnectorResolver;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProjectRuntimeConfigResolver(
      org.dradgo.application.project.ProjectRuntimeConfigResolver projectRuntimeConfigResolver) {
    this.projectRuntimeConfigResolver = projectRuntimeConfigResolver;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setProjectConnectorResolver(
      org.dradgo.application.project.ProjectConnectorResolver projectConnectorResolver) {
    this.projectConnectorResolver = projectConnectorResolver;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DockerRunnerAdapter(
      RunnerScratchStore scratchStore,
      RunnerWorkspaceStore workspaceStore,
      DockerEngineGateway docker,
      RunnerProperties runnerProperties,
      RunnerSecretsService runnerSecretsService,
      RunnerLogCaptureService runnerLogCaptureService,
      RunnerExecutionService runnerExecutionService,
      // Story 3.9 Trap T4 — ObjectProvider keeps the public test ctor stable and resolves to null
      // when no RepositoryWorkspaceService bean exists (lean adapter slice contexts).
      ObjectProvider<RepositoryWorkspaceService> repositoryWorkspaceServiceProvider) {
    this(
        scratchStore,
        workspaceStore,
        docker,
        runnerProperties,
        runnerSecretsService,
        runnerLogCaptureService,
        runnerExecutionService,
        Clock.systemUTC(),
        repositoryWorkspaceServiceProvider.getIfAvailable());
  }

  /**
   * Back-compat public constructor (no Clock, no repo seam) for explicit construction sites such as
   * the docker-runner lifecycle IT support — keeps the story-3.1 signature usable. NOT
   * {@code @Autowired}: Spring uses the ObjectProvider constructor above.
   */
  public DockerRunnerAdapter(
      RunnerScratchStore scratchStore,
      RunnerWorkspaceStore workspaceStore,
      DockerEngineGateway docker,
      RunnerProperties runnerProperties,
      RunnerSecretsService runnerSecretsService,
      RunnerLogCaptureService runnerLogCaptureService,
      RunnerExecutionService runnerExecutionService) {
    this(
        scratchStore,
        workspaceStore,
        docker,
        runnerProperties,
        runnerSecretsService,
        runnerLogCaptureService,
        runnerExecutionService,
        Clock.systemUTC(),
        null);
  }

  DockerRunnerAdapter(
      RunnerScratchStore scratchStore,
      RunnerWorkspaceStore workspaceStore,
      DockerEngineGateway docker,
      RunnerProperties runnerProperties,
      RunnerSecretsService runnerSecretsService,
      RunnerLogCaptureService runnerLogCaptureService,
      RunnerExecutionService runnerExecutionService,
      Clock clock) {
    this(
        scratchStore,
        workspaceStore,
        docker,
        runnerProperties,
        runnerSecretsService,
        runnerLogCaptureService,
        runnerExecutionService,
        clock,
        null);
  }

  DockerRunnerAdapter(
      RunnerScratchStore scratchStore,
      RunnerWorkspaceStore workspaceStore,
      DockerEngineGateway docker,
      RunnerProperties runnerProperties,
      RunnerSecretsService runnerSecretsService,
      RunnerLogCaptureService runnerLogCaptureService,
      RunnerExecutionService runnerExecutionService,
      Clock clock,
      @Nullable RepositoryWorkspaceService repositoryWorkspaceService) {
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
    this.docker = Objects.requireNonNull(docker, "docker");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.runnerSecretsService =
        Objects.requireNonNull(runnerSecretsService, "runnerSecretsService");
    this.runnerLogCaptureService =
        Objects.requireNonNull(runnerLogCaptureService, "runnerLogCaptureService");
    this.runnerExecutionService =
        Objects.requireNonNull(runnerExecutionService, "runnerExecutionService");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.repositoryWorkspaceService = repositoryWorkspaceService;
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

    // Story 3.5 AC1/AC3 + Trap T1/T3: resolve the agent-provider key for this dispatch immediately
    // before building the spec, and pass it straight into CreateContainerSpec.environment — never
    // through RunnerDispatchRequest or any persisted/serialized record. Resolution may throw
    // DOCTOR_RUNNER_SECRET_MISSING (fail fast at dispatch time). The mock adapter never calls this
    // (Trap T4). Log the COUNT only — never names/values (Trap T5 / Logging task).
    Map<String, String> secretEnv = resolveSecretEnv(request, kind);
    log.info(
        "docker dispatch secrets resolved runnerExecutionId={} kind={} stage={} secretVarCount={}",
        rexId,
        kind.value(),
        request.stage().value(),
        secretEnv.size());

    Map<String, String> containerEnv = new LinkedHashMap<>(secretEnv);
    // Story 3b.1 (AC2) — resolve the precise DELIVERYLINE_RUNNER_STAGE token from the (stage,
    // subStage) pair so an EXECUTION+IMPLEMENTATION_PLAN dispatch runs the read-only plan phase
    // (implementation-plan → implementationPlan) and EXECUTION+PR_OUTPUT implements + pushes
    // (pr-output → prOutput). INVESTIGATION and the null/legacy generic path stay byte-identical
    // (investigation / execution). Only this env var drives entrypoint.sh map_stage — the coarse
    // deliveryline.stage label above stays request.stage().value() (observability-only).
    String runnerStageToken = resolveRunnerStageToken(request);
    containerEnv.put("DELIVERYLINE_RUNNER_STAGE", runnerStageToken);
    // The wire token is the exact field that was wrong on run_ae258… (execution → always prOutput);
    // log it at INFO keyed by runnerExecutionId + workflowRunId so a prod incident is diagnosable
    // without re-deploying. Never logs payload bytes, secrets, or the provider key (Trap T5).
    log.info(
        "docker dispatch stage token resolved runnerExecutionId={} workflowRunId={} stage={} subStage={} runnerStageToken={}",
        rexId,
        request.workflowRunId(),
        request.stage().value(),
        request.subStage(),
        runnerStageToken);
    // Story 3a-8 (AC1) — surface the opt-in OpenSpec authoring flag to the entrypoint the same way
    // as the stage. Emitted ONLY when enabled, so a flag-off dispatch is byte-identical at the
    // container interior (no new env var at all). The mock adapter never reaches here.
    //
    // Story 3c-7 (AC2 / R2) — the flag is now PER-RUN: the broker resolves it from the run's
    // Project
    // via ProjectRuntimeConfigResolver.resolveOpenSpecEnabled(workflowRunId) (default-project
    // fallback) and threads it on the dispatch request. The adapter stays a dumb executor reading
    // request.openspecEnabled() — no ProjectRuntimeConfigResolver injection (avoids an
    // adapter->application-service dep + the [[docker-adapter-ctor-dep-fans-out]] ctor fan-out),
    // and
    // the global runnerProperties.openSpecEnabled() is no longer read on the dispatch hot path.
    if (request.openspecEnabled()) {
      containerEnv.put("DELIVERYLINE_RUNNER_OPENSPEC", "true");
    }

    List<CreateContainerSpec.BindMount> mounts = new ArrayList<>();
    mounts.add(new CreateContainerSpec.BindMount(layout.input(), CONTAINER_INPUT_MOUNT, true));
    mounts.add(new CreateContainerSpec.BindMount(layout.output(), CONTAINER_OUTPUT_MOUNT, false));
    mounts.add(new CreateContainerSpec.BindMount(layout.logs(), CONTAINER_LOGS_MOUNT, false));

    // Story 3.9 AC2/AC4 (Decision D0/D3) — when the dispatch carries a repositoryRef AND the
    // repository-workspace service is wired, clone the linked repo and add a /workspace/repo (rw)
    // mount. Every mock + no-repo dispatch (repositoryRef == null) is byte-for-byte unchanged.
    // Read the nullable field once into a local so the null-guard and the dereference observe the
    // same value (the field is injected via ObjectProvider and absent in mock/no-repo profiles).
    if (request.repositoryRef() != null) {
      RepositoryWorkspaceService workspaceService = this.repositoryWorkspaceService;
      if (workspaceService == null) {
        throw new IllegalStateException(
            "repository workspace service is unavailable for repositoryRef-bearing dispatch");
      }
      RepositoryWorkspaceService.RepositoryMount repoMount =
          workspaceService.prepareWorkspace(
              request.workflowRunId(),
              request.stage(),
              rexId,
              request.linearTicketRef(),
              request.linearTicketSummary(),
              request.repositoryRef());
      mounts.add(
          new CreateContainerSpec.BindMount(
              repoMount.repoHostPath(), repoMount.containerMountPath(), false));
      log.info(
          "docker dispatch repo workspace mounted runnerExecutionId={} repoRef={} branch={}",
          rexId,
          request.repositoryRef(),
          repoMount.branch());
    }

    // Story 3.8 — network mode is config-driven (deliveryline.runner.docker.network-mode), default
    // "none". Mock/contract runs stay isolated; real codex/claude runs need egress to their
    // provider
    // API and set "bridge" (or an egress-allowlisted network) in application.yml.
    String networkMode = runnerProperties.docker().networkMode();
    CreateContainerSpec spec =
        new CreateContainerSpec(
            image,
            List.copyOf(mounts),
            networkMode,
            labels,
            containerEnv,
            runnerProperties.docker().securityOpts());

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
        captureRunnerLogs(runnerExecutionId);
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
      mergeObservationKeepingHighWaterMark(
          runnerExecutionId, new LogGrowthObservation(0L, startedAt));
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
      mergeObservationKeepingHighWaterMark(
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
        mergeObservationKeepingHighWaterMark(runnerExecutionId, fresh);
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

  /**
   * Story 3.2a AC8 (a): atomically fold {@code candidate} into {@link #rexIdToLastLogObservation}
   * keeping the high-water mark on BOTH byte count and last-modified timestamp. Using {@link
   * java.util.concurrent.ConcurrentMap#merge} (rather than a {@code get} then {@code put}) means
   * two concurrent polls of the same runner-execution can never regress the stored observation —
   * the activity floor only ever advances.
   */
  private LogGrowthObservation mergeObservationKeepingHighWaterMark(
      String runnerExecutionId, LogGrowthObservation candidate) {
    return rexIdToLastLogObservation.merge(
        runnerExecutionId,
        candidate,
        (existing, incoming) -> {
          long bytes = Math.max(existing.byteCount(), incoming.byteCount());
          OffsetDateTime timestamp =
              incoming.lastModifiedAt().isAfter(existing.lastModifiedAt())
                  ? incoming.lastModifiedAt()
                  : existing.lastModifiedAt();
          return new LogGrowthObservation(bytes, timestamp);
        });
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
    // Story 3.2a AC8 (b): re-seed the log-growth observation floor on recovery so the first poll
    // after a broker restart does NOT re-emit HeartbeatTouched off the container's (stale)
    // StartedAt
    // or its already-written log bytes. The floor is the current log size at recovery time; only
    // genuine NEW growth after recovery counts as activity. The Running-branch lease re-arm in
    // RunnerBroker.processOrphan handles the deadline refresh for a recovered-running container.
    long recoveredLogBytes =
        workspaceStore
            .observeLogGrowth(runnerExecutionId)
            .map(LogGrowthObservation::byteCount)
            .orElse(0L);
    mergeObservationKeepingHighWaterMark(
        runnerExecutionId,
        new LogGrowthObservation(
            recoveredLogBytes, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)));
    log.info(
        "docker recoverHandle container-id recovered runnerExecutionId={} containerId={} reseededLogBytes={}",
        runnerExecutionId,
        containerId,
        recoveredLogBytes);
    return safeRecoverPoll(runnerExecutionId);
  }

  /**
   * Story 3b.1 (AC2) — resolve the {@code DELIVERYLINE_RUNNER_STAGE} env token from the {@code
   * (stage, subStage)} pair. These are literal {@code entrypoint.sh map_stage} contract tokens, not
   * registry {@code value()}s:
   *
   * <ul>
   *   <li>{@code INVESTIGATION} → {@code "investigation"} (= {@code request.stage().value()}).
   *   <li>{@code EXECUTION} + {@code IMPLEMENTATION_PLAN} → {@code "implementation-plan"}
   *       (read-only plan phase, emits {@code implementationPlan}, no push).
   *   <li>{@code EXECUTION} + {@code PR_OUTPUT} → {@code "pr-output"} (implements + pushes, emits
   *       {@code prOutput}).
   *   <li>{@code EXECUTION} + {@code subStage == null} → {@code "execution"} (legacy/recovery
   *       fallback → {@code map_stage} maps to {@code prOutput}; byte-identical to pre-3b
   *       behavior).
   * </ul>
   */
  private static String resolveRunnerStageToken(RunnerDispatchRequest request) {
    if (request.stage() == RunnerStage.EXECUTION && request.subStage() != null) {
      ExecutionSubStage subStage = request.subStage();
      return switch (subStage) {
        case IMPLEMENTATION_PLAN -> "implementation-plan";
        case PR_OUTPUT -> "pr-output";
      };
    }
    // INVESTIGATION → "investigation"; EXECUTION + null → "execution" (legacy generic path).
    // REVIEW → "review" (the entrypoint map_stage routes it to the review prompt).
    return request.stage().value();
  }

  /**
   * Story 3d-2 (AC1) — resolve the {@code env-var → value} map for the dispatch. For a REVIEW
   * dispatch the reviewer uses the PER-PROJECT reviewer credential (resolved through {@link
   * org.dradgo.application.project.ProjectConnectorResolver}, decrypted in-memory only, injected
   * under the kind's preferred env-var name, NEVER logged) — NOT the kind's host key. A configured
   * reviewer with no active reviewer credential fails fast with {@code
   * DOCTOR_RUNNER_SECRET_MISSING} so the worker degrades the reviewer execution (the run stays
   * human-reviewable, AC6); there is no host-key fallback (the reviewer is strictly opt-in per
   * project, AC5). Every non-REVIEW dispatch is byte-identical to story 3.5 (the kind's host key
   * via {@link RunnerSecretsService}).
   */
  // Package-private for focused AC1 coverage (DockerRunnerAdapterReviewerCredentialTest).
  Map<String, String> resolveSecretEnv(RunnerDispatchRequest request, RunnerKind kind) {
    if (request.stage() == RunnerStage.REVIEW) {
      if (projectRuntimeConfigResolver == null || projectConnectorResolver == null) {
        // AC1/AC5 (code-review re-review 2026-06-22) — the advisory reviewer is strictly
        // per-project; there is NO host-key fallback. If the project resolvers are not wired (a
        // misconfigured context), fail loud rather than silently dispatching the reviewer on the
        // operator's host credential. The REVIEW dispatch fault degrades gracefully in the broker
        // (AC6): the run stays human-reviewable, the panel shows "unavailable".
        throw new IllegalStateException(
            "reviewer credential resolvers are not wired; cannot resolve a per-project reviewer"
                + " secret for a REVIEW dispatch (refusing the host-key fallback)");
      }
      org.dradgo.domain.project.Project project =
          projectRuntimeConfigResolver.resolveForRun(request.workflowRunId());
      Optional<String> reviewerSecret =
          projectConnectorResolver.resolveConnectorSecret(
              project, org.dradgo.domain.registry.ConnectorRole.REVIEWER.value());
      if (reviewerSecret.isEmpty() || reviewerSecret.get().isBlank()) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runnerKind", kind.value());
        details.put("role", org.dradgo.domain.registry.ConnectorRole.REVIEWER.value());
        details.put("projectId", project.publicId());
        throw new org.dradgo.domain.DomainException(
            org.dradgo.domain.registry.DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING,
            "reviewer credential not configured for project " + project.publicId(),
            details);
      }
      java.util.List<String> names = runnerProperties.secretEnvNamesFor(kind);
      if (names.isEmpty()) {
        throw new org.dradgo.domain.DomainException(
            org.dradgo.domain.registry.DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING,
            "no secret env-var name configured for reviewer kind " + kind.value(),
            Map.of("runnerKind", kind.value()));
      }
      // Inject under the kind's PREFERRED name (mirrors RunnerSecretsService) so the runner image
      // reads it back exactly as it reads a normal dispatch's key. Value is method-local — never
      // logged, never stored in a field (Trap T1/T5).
      return Map.of(names.get(0), reviewerSecret.get());
    }
    return runnerSecretsService.resolveSecretsForRunner(
        kind, request.stage(), request.workflowRunId());
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
    bytes.ifPresent(payload -> mirrorResultToScratch(runnerExecutionId, payload));
    return bytes;
  }

  private void mirrorResultToScratch(String runnerExecutionId, byte[] payload) {
    try {
      scratchStore.writeRunnerResult(runnerExecutionId, payload);
      JsonNode parsed = OBJECT_MAPPER.readTree(payload);
      JsonNode artifactRefs = parsed.path("artifactReferences");
      if (!artifactRefs.isArray()) {
        return;
      }
      for (JsonNode ref : artifactRefs) {
        String contentReference = ref.path("contentReference").asText(null);
        if (contentReference == null || contentReference.isBlank()) {
          continue;
        }
        workspaceStore
            .tryReadArtifactContent(runnerExecutionId, contentReference)
            .ifPresent(
                bytes ->
                    scratchStore.writeArtifactContent(runnerExecutionId, contentReference, bytes));
      }
    } catch (IOException | RuntimeException error) {
      log.warn(
          "docker mirror result to scratch skipped runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
    }
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
    // Story 3.6 AC2: the container has exited — capture BOTH raw streams now (regardless of clean
    // vs crash exit), redacting before any persist. Best-effort: a capture failure must never
    // change how the result harvest classifies the exit.
    captureRunnerLogs(runnerExecutionId);
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

  /**
   * Story 3.6 AC2/AC8 — read the raw workspace stdout/stderr (method-local, never field-stored),
   * hand them to {@link RunnerLogCaptureService} for redaction + durable write, then persist the
   * resulting reference + metrics onto the row via a metadata-only update. Best-effort: capture is
   * observability — any failure is logged and swallowed so it never derails result classification
   * or the container exit path. The capture call is idempotent enough to tolerate the broker
   * polling {@code classifyExited} more than once (it re-redacts + re-writes the same redacted
   * files and re-records the same reference).
   */
  private void captureRunnerLogs(String runnerExecutionId) {
    try {
      RawRunnerLog stdout =
          workspaceStore.readRawStdoutForCapture(runnerExecutionId).orElse(RawRunnerLog.empty());
      RawRunnerLog stderr =
          workspaceStore.readRawStderrForCapture(runnerExecutionId).orElse(RawRunnerLog.empty());
      String workflowRunId =
          runnerExecutionService
              .findByPublicId(runnerExecutionId)
              .map(org.dradgo.application.runner.spi.RunnerExecutionSnapshot::workflowRunPublicId)
              .orElse(null);
      CapturedLogs captured =
          runnerLogCaptureService.captureLogs(
              runnerExecutionId,
              workflowRunId,
              stdout.text(),
              stderr.text(),
              truncation(stdout, stderr));
      runnerExecutionService.recordRawOutput(runnerExecutionId, captured);
      log.info(
          "docker runner logs captured runnerExecutionId={} redactionCount={} logsCaptured=true",
          runnerExecutionId,
          captured.redactionCount());
    } catch (RuntimeException error) {
      log.warn(
          "docker runner log capture failed runnerExecutionId={} cause={}",
          runnerExecutionId,
          error.toString());
    }
  }

  private static RunnerLogTruncation truncation(RawRunnerLog stdout, RawRunnerLog stderr) {
    if (stdout.truncated() && stderr.truncated()) {
      return RunnerLogTruncation.BOTH;
    }
    if (stdout.truncated()) {
      return RunnerLogTruncation.STDOUT;
    }
    if (stderr.truncated()) {
      return RunnerLogTruncation.STDERR;
    }
    return RunnerLogTruncation.NONE;
  }
}
