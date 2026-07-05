package org.dradgo.application.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the runner subsystem.
 *
 * <p>Defaults match the story's specified values. Nested {@code recovery} and {@code mock} groups
 * keep stage-keyed maps typed against {@link RunnerStage} so the {@code domain/registry} enum is
 * the only legal stage form.
 *
 * <p>The class lives in {@code application.runner} (not {@code infrastructure}) so the application
 * services that consume it satisfy the architecture rule "application code must not depend directly
 * on infrastructure classes". The Spring wiring that activates binding still lives in {@code
 * infrastructure.config.RunnerConfiguration} via {@code @EnableConfigurationProperties}.
 */
@ConfigurationProperties("deliveryline.runner")
public record RunnerProperties(
    double staleThresholdMultiplier,
    Map<RunnerStage, Duration> stageTimeouts,
    long timeoutScanIntervalMs,
    int timeoutScanBatchSize,
    long staleScanIntervalMs,
    long pollIntervalMs,
    Recovery recovery,
    Mock mock,
    Scheduling scheduling,
    Docker docker,
    Map<RunnerKind, List<String>> secretEnvNames,
    boolean allowShareableLogs,
    // Story 3a-1 (AC10) — per-stage runner-image kind selection. Binds
    // deliveryline.runner.spec-stage.kind (codex | claude), defaulting to codex. Resolved into the
    // dispatch path via kindForStage(stage); a null group falls back to the codex default so the
    // @SpringBootTest tiers that omit the key still bind (test yaml carries it too — Trap T4).
    SpecStage specStage,
    // Story 3.11 (AC10) — plan-stage (EXECUTION) twin of specStage. Binds
    // deliveryline.runner.plan-stage.kind (codex | claude) + .auto-dispatch.
    // kindForStage(EXECUTION)
    // resolves to planStage.kind() (OQ-4: pr-output falls back here until story 3.12);
    // auto-dispatch
    // gates the approveSpec -> dispatchPlanGeneration trigger (ON in prod, OFF in the shared test
    // yaml — Trap T4/T11). A null group falls back to the codex default so contexts that omit the
    // key still bind.
    PlanStage planStage,
    // Story 3.12 (AC1 / Task 5) — implementation-stage (pr-output sub-stage) twin of planStage.
    // Binds deliveryline.runner.implementation-stage.kind (codex | claude) + .auto-dispatch.
    // kindForExecutionSubStage(PR_OUTPUT) resolves to implementationStage.kind(); auto-dispatch
    // gates the dispatchImplementation / retryImplementation trigger (ON in prod, OFF in the shared
    // test yaml — Trap T7). A null group falls back to the codex default so contexts that omit the
    // key still bind.
    ImplementationStage implementationStage,
    // Story 3a-8 (AC1) — opt-in OpenSpec authoring flag, surfaced to the container as
    // DELIVERYLINE_RUNNER_OPENSPEC. OPTIONAL + UNVALIDATED nested record (mirrors the docker()
    // nesting precedent); a null/absent group falls back to disabled (default OFF) so the shared
    // test application.yml needs no mirror entry (Trap: [[validated-config-needs-test-yaml]]).
    OpenSpec openspec,
    // Story 3h-1 (AC2, FR75) — global default build-validation config seeded into the default
    // project (deliveryline.runner.build-stage.{enabled,command,timeout}). OPTIONAL + UNVALIDATED
    // nested record (mirrors the openspec() precedent); a null/absent group falls back to disabled
    // (default OFF, no command) so the shared test application.yml needs no mirror entry (Trap:
    // [[validated-config-needs-test-yaml]]). Per-project overrides live on the Project aggregate;
    // this is only the seed default read by DefaultProjectSeeder.
    BuildStage buildStage,
    // Story 3.17a (AC4 / AC7) — RunnerExecutionQueue backpressure cap. The maximum number of rows
    // that may sit in status='queued' at once; enqueue beyond it raises RUNNER_QUEUE_FULL and
    // writes
    // no row. Default 100. Clamped to >=1 in the compact ctor (a cap below 1 would reject every
    // enqueue). Validated => the shared test application.yml MUST mirror it (Trap T5,
    // [[validated-config-needs-test-yaml]]). Read by the dormant queue (exercised by tests only in
    // 3.17a; the worker pool that drains the queue lands in 3.17b).
    int queueMaxDepth) {

  public RunnerProperties {
    if (staleThresholdMultiplier <= 0.0d) {
      throw new IllegalArgumentException(
          "deliveryline.runner.stale-threshold-multiplier must be positive: "
              + staleThresholdMultiplier);
    }
    if (timeoutScanIntervalMs <= 0L) {
      throw new IllegalArgumentException(
          "deliveryline.runner.timeout-scan-interval-ms must be positive: "
              + timeoutScanIntervalMs);
    }
    if (timeoutScanBatchSize <= 0) {
      throw new IllegalArgumentException(
          "deliveryline.runner.timeout-scan-batch-size must be positive: " + timeoutScanBatchSize);
    }
    if (staleScanIntervalMs <= 0L) {
      throw new IllegalArgumentException(
          "deliveryline.runner.stale-scan-interval-ms must be positive: " + staleScanIntervalMs);
    }
    if (pollIntervalMs <= 0L) {
      throw new IllegalArgumentException(
          "deliveryline.runner.poll-interval-ms must be positive: " + pollIntervalMs);
    }
    stageTimeouts = stageTimeouts == null ? Map.of() : Map.copyOf(stageTimeouts);
    recovery = recovery == null ? Recovery.defaults() : recovery;
    mock = mock == null ? Mock.defaults() : mock;
    scheduling = scheduling == null ? Scheduling.defaults() : scheduling;
    docker = docker == null ? Docker.defaults() : docker;
    // Story 3.5 AC1/AC2/Trap T2: per-kind agent-provider env-var NAMES (never values). This is the
    // source of truth stories 3.3/3.4 read when their runner images consume the injected key. A
    // null/empty config falls back to the documented defaults so dispatch never fails on a missing
    // mapping; the values themselves are resolved at dispatch time from the Spring Environment.
    secretEnvNames =
        (secretEnvNames == null || secretEnvNames.isEmpty())
            ? defaultSecretEnvNames()
            : deepCopySecretEnvNames(secretEnvNames);
    specStage = specStage == null ? SpecStage.defaults() : specStage;
    planStage = planStage == null ? PlanStage.defaults() : planStage;
    implementationStage =
        implementationStage == null ? ImplementationStage.defaults() : implementationStage;
    openspec = openspec == null ? OpenSpec.defaults() : openspec;
    buildStage = buildStage == null ? BuildStage.defaults() : buildStage;
    // Story 3.17a AC4 — clamp the backpressure cap to >=1. A cap below 1 (0, negative, or an unset
    // primitive that bound to 0) would reject every enqueue; coerce to the minimum useful depth so
    // a
    // misconfiguration degrades to "depth 1" rather than a dead queue.
    queueMaxDepth = queueMaxDepth < 1 ? 1 : queueMaxDepth;
  }

  public static RunnerProperties defaults() {
    Map<RunnerStage, Duration> timeouts = new LinkedHashMap<>();
    timeouts.put(RunnerStage.INVESTIGATION, Duration.ofSeconds(600));
    timeouts.put(RunnerStage.EXECUTION, Duration.ofSeconds(600));
    return new RunnerProperties(
        2.0d,
        timeouts,
        10_000L,
        50,
        60_000L,
        5_000L,
        Recovery.defaults(),
        Mock.defaults(),
        Scheduling.defaults(),
        Docker.defaults(),
        defaultSecretEnvNames(),
        false,
        SpecStage.defaults(),
        PlanStage.defaults(),
        ImplementationStage.defaults(),
        OpenSpec.defaults(),
        BuildStage.defaults(),
        100);
  }

  /**
   * Story 3a-1 (AC10) + story 3.11 (AC10 / Decision D3 / OQ-4) — resolve the runner-image {@link
   * RunnerKind} for a dispatch stage. The spec-investigation stage ({@link
   * RunnerStage#INVESTIGATION}) honors {@code deliveryline.runner.spec-stage.kind}; the EXECUTION
   * stage honors {@code deliveryline.runner.plan-stage.kind}. Because {@link RunnerStage#EXECUTION}
   * is shared by the implementation-plan AND pr-output sub-stages and the wire stage cannot
   * distinguish them, the plan-stage kind covers both for now — pr-output deliberately falls back
   * to {@code plan-stage.kind} until story 3.12 adds a dedicated implementation-stage kind (OQ-4).
   * A stage-keyed (not sub-stage-keyed) resolver is intentional: the broker's secret-scan path
   * (post-execution, story 3.5) only has {@code row.stage()} and MUST compare against the SAME key
   * the dispatch path injected — keying on the sub-stage would desync the two.
   */
  public RunnerKind kindForStage(RunnerStage stage) {
    Objects.requireNonNull(stage, "stage");
    return switch (stage) {
      case INVESTIGATION -> specStage.kind();
      case EXECUTION -> planStage.kind();
      // Story 3d-2 (DD-1/DD-7) — the reviewer kind is PER-PROJECT (Project.reviewerModelKind),
      // resolved by ProjectRuntimeConfigResolver.resolveReviewerKind, never from these global
      // per-stage props. The reviewer dispatch path special-cases REVIEW before reaching this
      // resolver, so reaching here for REVIEW is a routing bug — fail loud rather than silently
      // mis-resolve to a producer kind.
      case REVIEW ->
          throw new IllegalStateException(
              "kindForStage must not be called for RunnerStage.REVIEW; the reviewer kind is "
                  + "per-project — use ProjectRuntimeConfigResolver.resolveReviewerKind");
      // Story 3h-1 (AC1) — BUILD runs BACKEND-SIDE via BuildCommandPort (ProcessBuilder in the
      // materialized workspace), never through the Docker runner, so it is NEVER runner-kind
      // dispatched. Reaching here for BUILD is a routing bug — fail loud (same posture as REVIEW).
      case BUILD ->
          throw new IllegalStateException(
              "kindForStage must not be called for RunnerStage.BUILD; the build stage runs "
                  + "backend-side via BuildCommandPort and is never runner-kind dispatched");
    };
  }

  /**
   * Story 3.12 (AC1 / Task 5, closes 3.11 OQ-4) — resolve the runner-image {@link RunnerKind} for
   * an EXECUTION dispatch by its derived {@link ExecutionSubStage}: {@link
   * ExecutionSubStage#IMPLEMENTATION_PLAN} honors {@code deliveryline.runner.plan-stage.kind};
   * {@link ExecutionSubStage#PR_OUTPUT} honors {@code
   * deliveryline.runner.implementation-stage.kind}. A {@code null} sub-stage (legacy/recovery
   * composition) falls back to the plan-stage kind so the pre-3.12 dispatch behavior is
   * byte-identical.
   *
   * <p><b>OQ-4 coupling (load-bearing).</b> The broker's post-execution secret-scan path (story
   * 3.5, {@code RunnerBroker.onResult}) only has {@code row.stage()} (EXECUTION) and resolves the
   * scanned key via {@link #kindForStage(RunnerStage)} = {@code plan-stage.kind}. So the
   * sub-stage-aware dispatch kind here MUST equal {@code plan-stage.kind} for BOTH sub-stages (i.e.
   * {@code implementation-stage.kind} == {@code plan-stage.kind}, both {@code codex} by default) or
   * the scan would compare the runner workspace against the wrong provider key. Genuinely splitting
   * the kinds requires persisting the resolved kind on the {@code runner_executions} row (a {@code
   * runner_kind} column deferred per 3.1 OQ-7) so the scan can re-read it rather than re-derive it.
   */
  public RunnerKind kindForExecutionSubStage(ExecutionSubStage subStage) {
    return subStage == ExecutionSubStage.PR_OUTPUT ? implementationStage.kind() : planStage.kind();
  }

  /**
   * Story 3.12 (AC1 / Task 5) — implementation-stage (pr-output) auto-dispatch master switch
   * ({@code deliveryline.runner.implementation-stage.auto-dispatch}). The pr-output twin of {@link
   * #planAutoDispatchEnabled()}: when {@code false} (the shared test profile) the {@code
   * dispatchImplementation} / {@code retryImplementation} entry points are no-ops so the fast tier
   * stays deterministic; production enables it.
   */
  public boolean implementationAutoDispatchEnabled() {
    return implementationStage.autoDispatch();
  }

  /**
   * Story 3.11 (AC10 / Task 4) — plan-stage auto-dispatch master switch ({@code
   * deliveryline.runner.plan-stage.auto-dispatch}). The EXECUTION twin of {@link
   * SpecStage#autoDispatch()}: when {@code false} (the shared test profile) the {@code approveSpec
   * -> dispatchPlanGeneration} trigger is a no-op so the fast tier stays deterministic; production
   * enables it.
   */
  public boolean planAutoDispatchEnabled() {
    return planStage.autoDispatch();
  }

  /**
   * Story 3.6 AC4 — when {@code true}, captured runner logs whose redaction pass detected zero
   * secrets across both streams may be elevated from {@code local-only} to {@code
   * shareable-redacted}. Defaults to {@code false} (Trap T3): logs stay {@code local-only} (never
   * shipped by story 3.7's ELK filter) unless the operator explicitly opts in via {@code
   * deliveryline.runner.allow-shareable-logs=true}. The elevation is gated on BOTH the zero-secret
   * detection AND this flag — it is never sufficient on its own.
   */
  public boolean allowShareableLogs() {
    return allowShareableLogs;
  }

  /**
   * Story 3a-8 (AC1) — whether the runner entrypoints' opt-in OpenSpec authoring layer is enabled
   * ({@code deliveryline.runner.openspec.enabled}). When {@code true} it is surfaced to the
   * container as env {@code DELIVERYLINE_RUNNER_OPENSPEC=true} (threaded by {@code
   * DockerRunnerAdapter} exactly like {@code DELIVERYLINE_RUNNER_STAGE}); the entrypoints gate the
   * whole authoring layer on it. Default {@code false} ⇒ byte-identical legacy path.
   */
  public boolean openSpecEnabled() {
    return openspec.enabled();
  }

  /**
   * Story 3h-1 (AC2) — the GLOBAL default build-stage opt-in ({@code
   * deliveryline.runner.build-stage.enabled}) that {@code DefaultProjectSeeder} seeds into the
   * default project's per-project {@code buildStageEnabled}. Default {@code false} ⇒ pre-3h parity.
   */
  public boolean buildStageEnabled() {
    return buildStage.enabled();
  }

  /**
   * Story 3h-1 (AC2) — the GLOBAL default build command ({@code
   * deliveryline.runner.build-stage.command}) seeded into the default project's per-project {@code
   * buildCommand}. {@code null}/absent ⇒ no build command (BUILD skipped even if enabled).
   */
  public String buildCommand() {
    return buildStage.command();
  }

  /**
   * Story 3h-1 (AC2 / Task 3) — the bound on the backend-side build process ({@code
   * deliveryline.runner.build-stage.timeout}); overrun kills the process → non-zero exit. Defaults
   * to 10 minutes.
   */
  public Duration buildTimeout() {
    return buildStage.timeout();
  }

  /**
   * Story 3.5 Trap T2 — default agent-provider env-var names per runner kind. The list is ordered =
   * the runner image's resolution preference (first present wins at dispatch time, and the value is
   * injected under the name it was found — see {@link RunnerSecretsService}).
   *
   * <p>Story 3a-3 made Codex subscription-first/file-based: {@code CODEX_AUTH_JSON} (the raw,
   * single-line content of {@code $CODEX_HOME/auth.json} from a ChatGPT/Pro subscription, the
   * cost-saving path) is the PREFERRED credential; the Codex entrypoint materializes it into {@code
   * $CODEX_HOME/auth.json} before invoking Codex. {@code CODEX_API_KEY} then {@code OPENAI_API_KEY}
   * (aliases for the same OpenAI API key) remain the fallback. This mirrors story 3.4's Claude
   * subscription-first dual-mode, but Codex's subscription credential travels as a file's content
   * rather than a token the CLI reads directly — see {@code runners/codex/entrypoint.sh} + {@code
   * runners/codex/lib/runner.mjs materialize-auth}.
   *
   * <p>Story 3.4 made Claude dual-mode: {@code CLAUDE_CODE_OAUTH_TOKEN} (Pro/Max subscription
   * token, preferred) then {@code ANTHROPIC_API_KEY} (per-token API billing) — two DISTINCT
   * credential types, not aliases, so doctor reports PASS when either is set. NAMES only — the
   * values live in {@code .env} / process env and are read per-dispatch.
   */
  public static Map<RunnerKind, List<String>> defaultSecretEnvNames() {
    EnumMap<RunnerKind, List<String>> names = new EnumMap<>(RunnerKind.class);
    names.put(RunnerKind.CODEX, List.of("CODEX_AUTH_JSON", "CODEX_API_KEY", "OPENAI_API_KEY"));
    names.put(RunnerKind.CLAUDE, List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"));
    return names;
  }

  private static Map<RunnerKind, List<String>> deepCopySecretEnvNames(
      Map<RunnerKind, List<String>> source) {
    EnumMap<RunnerKind, List<String>> copy = new EnumMap<>(RunnerKind.class);
    for (Map.Entry<RunnerKind, List<String>> entry : source.entrySet()) {
      List<String> values = entry.getValue();
      copy.put(entry.getKey(), values == null ? List.of() : List.copyOf(values));
    }
    return Map.copyOf(copy);
  }

  /**
   * Resolve the ordered list of env-var names a runner of {@code kind} authenticates with. Falls
   * back to the documented defaults when the kind is absent from config so a new {@link RunnerKind}
   * never silently resolves to "no secret required".
   */
  public List<String> secretEnvNamesFor(RunnerKind kind) {
    Objects.requireNonNull(kind, "kind");
    List<String> configured = secretEnvNames.get(kind);
    if (configured != null && !configured.isEmpty()) {
      return configured;
    }
    return defaultSecretEnvNames().getOrDefault(kind, List.of());
  }

  public Duration timeoutFor(RunnerStage stage) {
    Objects.requireNonNull(stage, "stage");
    Duration explicit = stageTimeouts.get(stage);
    return explicit != null ? explicit : Duration.ofSeconds(600);
  }

  public Duration staleThresholdFor(RunnerStage stage) {
    Duration base = timeoutFor(stage);
    long millis = base.toMillis();
    long scaledMillis = (long) Math.ceil(millis * staleThresholdMultiplier);
    return Duration.ofMillis(Math.max(1L, scaledMillis));
  }

  public record Recovery(int batchSize) {

    public Recovery {
      if (batchSize <= 0) {
        throw new IllegalArgumentException(
            "deliveryline.runner.recovery.batch-size must be positive: " + batchSize);
      }
    }

    public static Recovery defaults() {
      return new Recovery(100);
    }
  }

  public record Mock(Map<RunnerStage, String> defaultScenario) {

    public Mock {
      defaultScenario = defaultScenario == null ? Map.of() : Map.copyOf(defaultScenario);
    }

    public static Mock defaults() {
      Map<RunnerStage, String> scenarios = new LinkedHashMap<>();
      scenarios.put(RunnerStage.INVESTIGATION, "happy-spec");
      scenarios.put(RunnerStage.EXECUTION, "happy-implementation-plan");
      // Story 3d-2 (Task 6) — the offline reviewer scenario for the !runners.docker profile.
      scenarios.put(RunnerStage.REVIEW, "happy-review");
      return new Mock(scenarios);
    }

    public String scenarioFor(RunnerStage stage) {
      Objects.requireNonNull(stage, "stage");
      String scenario = defaultScenario.get(stage);
      return scenario != null ? scenario : "happy-spec";
    }
  }

  public record Scheduling(boolean enabled) {

    public static Scheduling defaults() {
      return new Scheduling(true);
    }
  }

  /**
   * Story 3a-1 — spec-stage orchestration config.
   *
   * <ul>
   *   <li>{@code kind} (AC10) — runner-image flavor for the spec-investigation stage ({@code codex}
   *       | {@code claude}); a null/absent value defaults to {@link RunnerKind#CODEX} so binding
   *       never fails and a new {@link RunnerKind} never silently resolves to an undefined image.
   *   <li>{@code autoDispatch} — master switch for the auto-dispatch triggers (dispatch on submit +
   *       re-dispatch on spec rejection). Production ({@code application.yml}) sets it {@code
   *       true}; the shared test profile sets it {@code false} — mirroring {@code
   *       scheduling.enabled: false} — so the existing suite's submit/reject tests stay
   *       deterministic and the full submit→spec loop is exercised by the dedicated integration
   *       test that opts in. Spring binds a missing key to {@code false} (primitive default);
   *       {@link #defaults()} (non-Spring construction) is {@code true} to match the production
   *       default.
   * </ul>
   */
  public record SpecStage(RunnerKind kind, boolean autoDispatch) {

    public SpecStage {
      kind = kind == null ? RunnerKind.CODEX : kind;
    }

    public static SpecStage defaults() {
      return new SpecStage(RunnerKind.CODEX, true);
    }
  }

  /**
   * Story 3.11 (AC10) — plan-stage orchestration config, the EXECUTION twin of {@link SpecStage}.
   *
   * <ul>
   *   <li>{@code kind} — runner-image flavor for the implementation-plan (EXECUTION) stage ({@code
   *       codex} | {@code claude}); a null/absent value defaults to {@link RunnerKind#CODEX}.
   *       Resolved into the dispatch path via {@link #kindForStage(RunnerStage)} (it also serves
   *       the pr-output sub-stage until story 3.12 — OQ-4).
   *   <li>{@code autoDispatch} — master switch for the {@code approveSpec ->
   *       dispatchPlanGeneration} trigger. Production ({@code application.yml}) sets it {@code
   *       true}; the shared test profile sets it {@code false} (mirroring {@code
   *       spec-stage.auto-dispatch}) so the existing suite's approveSpec tests stay deterministic
   *       and the full approve→plan→{@code WaitingForReview} loop runs only in the dedicated
   *       integration test that opts in. Spring binds a missing key to {@code false} (primitive
   *       default); {@link #defaults()} (non-Spring construction) is {@code true} to match the
   *       production default.
   * </ul>
   */
  public record PlanStage(RunnerKind kind, boolean autoDispatch) {

    public PlanStage {
      kind = kind == null ? RunnerKind.CODEX : kind;
    }

    public static PlanStage defaults() {
      return new PlanStage(RunnerKind.CODEX, true);
    }
  }

  /**
   * Story 3.12 (AC1) — implementation-stage orchestration config, the pr-output (EXECUTION) twin of
   * {@link PlanStage}.
   *
   * <ul>
   *   <li>{@code kind} — runner-image flavor for the pr-output sub-stage ({@code codex} | {@code
   *       claude}); a null/absent value defaults to {@link RunnerKind#CODEX}. Resolved into the
   *       dispatch path via {@link #kindForExecutionSubStage(ExecutionSubStage)}. <b>OQ-4:</b> it
   *       MUST stay equal to {@code plan-stage.kind} until a {@code runner_kind} column lands, so
   *       the story-3.5 secret-scan (keyed on {@code kindForStage(EXECUTION)} = {@code
   *       plan-stage.kind}) stays consistent.
   *   <li>{@code autoDispatch} — master switch for the {@code dispatchImplementation} /{@code
   *       retryImplementation} entry points. Production ({@code application.yml}) sets it {@code
   *       true}; the shared test profile sets it {@code false} (mirroring {@code
   *       plan-stage.auto-dispatch}) so the existing suite stays deterministic and the full
   *       approved-plan→pr-output→{@code WaitingForReview} loop runs only in {@code
   *       PrOutputOrchestrationIT} (which opts in via {@code @TestPropertySource}). Spring binds a
   *       missing key to {@code false} (primitive default); {@link #defaults()} (non-Spring
   *       construction) is {@code true} to match the production default.
   * </ul>
   */
  public record ImplementationStage(RunnerKind kind, boolean autoDispatch) {

    public ImplementationStage {
      kind = kind == null ? RunnerKind.CODEX : kind;
    }

    public static ImplementationStage defaults() {
      return new ImplementationStage(RunnerKind.CODEX, true);
    }
  }

  /**
   * Story 3a-8 (AC1) — opt-in OpenSpec authoring switch ({@code
   * deliveryline.runner.openspec.enabled}). When {@code true}, the runner entrypoints author
   * OpenSpec change artifacts (proposal/specs/design/tasks) and assemble {@code
   * openspec/changes/<id>/} into the delivered PR at pr-output; when {@code false} (the default)
   * the runners behave byte-identically to today.
   *
   * <p><b>OPTIONAL + UNVALIDATED</b> (no bean validation, no compact-ctor guard): the shared test
   * {@code application.yml} therefore needs no mirror entry ({@code
   * [[validated-config-needs-test-yaml]]}). Spring binds a missing key to {@code false} (primitive
   * default); {@link #defaults()} (non-Spring construction) matches the production default OFF.
   */
  public record OpenSpec(boolean enabled) {

    public static OpenSpec defaults() {
      return new OpenSpec(false);
    }
  }

  /**
   * Story 3h-1 (AC2, FR75) — global default build-validation config ({@code
   * deliveryline.runner.build-stage.{enabled,command,timeout}}). Seeds the default project's
   * per-project build config via {@code DefaultProjectSeeder}; per-project overrides live on the
   * {@code Project} aggregate. {@code enabled=false} (the default) ⇒ BUILD skipped entirely (pre-3h
   * parity); {@code command} is the shell/build command run backend-side via {@code
   * BuildCommandPort} in the materialized workspace; {@code timeout} bounds that process (kill →
   * non-zero exit on overrun), defaulting to 10 minutes.
   *
   * <p><b>OPTIONAL + UNVALIDATED</b> (no bean validation, no compact-ctor guard): the shared test
   * {@code application.yml} therefore needs no mirror entry ({@code
   * [[validated-config-needs-test-yaml]]}). Spring binds a missing group to {@link #defaults()}; a
   * null/zero {@code timeout} falls back to the 10-minute default.
   */
  public record BuildStage(boolean enabled, String command, Duration timeout) {

    public BuildStage {
      timeout =
          (timeout == null || timeout.isZero() || timeout.isNegative()) ? DEFAULT_TIMEOUT : timeout;
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    public static BuildStage defaults() {
      return new BuildStage(false, null, DEFAULT_TIMEOUT);
    }
  }

  /**
   * Story 3.1 — Docker runner adapter configuration. The {@code @Profile("runners.docker")}-gated
   * {@code DockerRunnerAdapter} reads {@link #imageTags()} (kind → tag) and {@link
   * #workspaceRoot()}. {@code workspaceRetentionHours} is declared here so story 3.2's cleanup job
   * has a property to read (this story forbids immediate deletion).
   *
   * <p>Trap T3: {@link #defaultKind()} is the only sanctioned source of the runner kind for the
   * broker → adapter dispatch path. Reading the kind from the (untrusted) context bundle is
   * forbidden.
   */
  public record Docker(
      RunnerKind defaultKind,
      Map<RunnerKind, String> imageTags,
      Path workspaceRoot,
      long workspaceRetentionHours,
      long workspaceCleanupIntervalMs,
      Duration containerCreateTimeout,
      Duration containerStartTimeout,
      long danglingContainerMinAgeSeconds,
      // Story 3.8 — Docker network mode applied at container create (DockerRunnerAdapter →
      // CreateContainerSpec.networkMode). Defaults to "none" (the locked-down mock/contract
      // posture:
      // a runner needs no egress to read a bundle and write a result). Real agent runs
      // (codex/claude
      // CLIs calling their provider API) require egress — set
      // deliveryline.runner.docker.network-mode
      // to "bridge" (default NAT egress) or a pre-created egress-allowlisted network name. A
      // null/blank value falls back to "none" so every existing context binds unchanged.
      String networkMode,
      // Docker security options applied at container create (HostConfig.withSecurityOpts). Codex's
      // read-only sandbox (spec/implementationPlan stages) wraps every command in bubblewrap, which
      // must create an unprivileged USER NAMESPACE; Docker's DEFAULT seccomp profile blocks that
      // syscall, so real read-only-stage runs die with "bwrap: No permissions to create a new
      // namespace" and the agent never reads the repo. Setting ["seccomp=unconfined"] permits it
      // (the container is still non-root with backend-controlled network + read-only input mount).
      // OPTIONAL + UNVALIDATED: a null/absent value normalizes to an empty list, keeping the
      // locked-down mock/contract posture (and needing no shared-test-yaml mirror).
      List<String> securityOpts) {

    /** Upper bound (1 day) for the dangling-container grace window; guards against overflow. */
    private static final long MAX_DANGLING_CONTAINER_MIN_AGE_SECONDS = 86_400L;

    public Docker {
      Objects.requireNonNull(defaultKind, "defaultKind");
      imageTags = imageTags == null ? Map.of() : Map.copyOf(new EnumMap<>(imageTags));
      for (RunnerKind kind : RunnerKind.values()) {
        // Story 3d-3 — the `manual` kind launches NO container (the dispatch chokepoint parks the
        // run and never reaches imageTagFor), so it requires no Docker image tag. Skip it here; an
        // accidental imageTagFor(MANUAL) still fails loudly below.
        if (kind == RunnerKind.MANUAL) {
          continue;
        }
        String tag = imageTags.get(kind);
        if (tag == null || tag.isBlank()) {
          throw new IllegalArgumentException(
              "deliveryline.runner.docker.image-tags." + kind.value() + " must be configured");
        }
      }
      Objects.requireNonNull(workspaceRoot, "workspaceRoot");
      if (workspaceRetentionHours <= 0L) {
        throw new IllegalArgumentException(
            "deliveryline.runner.docker.workspace-retention-hours must be positive: "
                + workspaceRetentionHours);
      }
      if (workspaceCleanupIntervalMs <= 0L) {
        throw new IllegalArgumentException(
            "deliveryline.runner.docker.workspace-cleanup-interval-ms must be positive: "
                + workspaceCleanupIntervalMs);
      }
      Objects.requireNonNull(containerCreateTimeout, "containerCreateTimeout");
      Objects.requireNonNull(containerStartTimeout, "containerStartTimeout");
      if (containerCreateTimeout.isZero() || containerCreateTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "deliveryline.runner.docker.container-create-timeout must be positive");
      }
      if (containerStartTimeout.isZero() || containerStartTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "deliveryline.runner.docker.container-start-timeout must be positive");
      }
      // Story 3.2a AC4: a non-negative grace window during which a labelled-but-rowless container
      // is
      // preserved by the dangling-container sweep (covers the dispatch→row-insert window). Zero
      // disables the guard (every rowless container is eligible for removal immediately).
      // Story 3.2a code-review (2026-05-29): bound the upper end too. The sweep computes
      // now.minusSeconds(minAgeSeconds); an unbounded value overflows OffsetDateTime arithmetic
      // (DateTimeException), which the per-container handler swallows so every dangling container
      // leaks. A day is far beyond any sane dispatch→row-insert grace window.
      if (danglingContainerMinAgeSeconds < 0L
          || danglingContainerMinAgeSeconds > MAX_DANGLING_CONTAINER_MIN_AGE_SECONDS) {
        throw new IllegalArgumentException(
            "deliveryline.runner.docker.dangling-container-min-age-seconds must be between 0 and "
                + MAX_DANGLING_CONTAINER_MIN_AGE_SECONDS
                + ": "
                + danglingContainerMinAgeSeconds);
      }
      networkMode = (networkMode == null || networkMode.isBlank()) ? "none" : networkMode;
      securityOpts = securityOpts == null ? List.of() : List.copyOf(securityOpts);
    }

    public static Docker defaults() {
      EnumMap<RunnerKind, String> tags = new EnumMap<>(RunnerKind.class);
      tags.put(RunnerKind.CODEX, "deliveryline/codex-runner:latest");
      tags.put(RunnerKind.CLAUDE, "deliveryline/claude-runner:latest");
      return new Docker(
          RunnerKind.CODEX,
          tags,
          Path.of("runner-work"),
          24L,
          3_600_000L,
          Duration.ofSeconds(30L),
          Duration.ofSeconds(30L),
          120L,
          "none",
          List.of());
    }

    public String imageTagFor(RunnerKind kind) {
      Objects.requireNonNull(kind, "kind");
      String tag = imageTags.get(kind);
      if (tag == null || tag.isBlank()) {
        throw new IllegalStateException(
            "No image tag configured for runner kind "
                + kind.value()
                + " (deliveryline.runner.docker.image-tags."
                + kind.value()
                + ")");
      }
      return tag;
    }

    /**
     * Redacts credentials embedded in a registry image reference of the form {@code
     * user:pass@host/image:tag} → {@code ***@host/image:tag}, leaving an un-credentialed reference
     * untouched. Lives in the application layer (the image tags are application-owned config) so
     * {@code RunnerBroker} can redact before writing the {@code runner.dispatched} audit event
     * without reaching into {@code adapters.runner.docker} (which the layered-boundary ArchUnit
     * rule forbids). The adapter's {@code DockerLogSanitizer} delegates here so there is a single
     * implementation.
     */
    public static String redactImageTag(String image) {
      if (image == null || image.isBlank()) {
        return image;
      }
      int at = image.indexOf('@');
      int slash = image.indexOf('/');
      int colon = image.indexOf(':');
      if (at > 0 && (slash < 0 || at < slash) && colon > 0 && colon < at) {
        return "***" + image.substring(at);
      }
      return image;
    }
  }
}
