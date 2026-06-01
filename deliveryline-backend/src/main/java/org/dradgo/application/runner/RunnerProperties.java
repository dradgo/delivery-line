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
    boolean allowShareableLogs) {

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
        false);
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
   * Story 3.5 Trap T2 — default agent-provider env-var names per runner kind. The list is ordered =
   * the runner image's resolution preference (first present wins at dispatch time, and the value is
   * injected under the name it was found — see {@link RunnerSecretsService}). Codex also accepts
   * {@code OPENAI_API_KEY} as a fallback alias for the same OpenAI key. Story 3.4 made Claude
   * dual-mode: {@code CLAUDE_CODE_OAUTH_TOKEN} (Pro/Max subscription token, preferred) then {@code
   * ANTHROPIC_API_KEY} (per-token API billing) — two DISTINCT credential types, not aliases, so
   * doctor reports PASS when either is set. NAMES only — the values live in {@code .env} / process
   * env and are read per-dispatch.
   */
  public static Map<RunnerKind, List<String>> defaultSecretEnvNames() {
    EnumMap<RunnerKind, List<String>> names = new EnumMap<>(RunnerKind.class);
    names.put(RunnerKind.CODEX, List.of("CODEX_API_KEY", "OPENAI_API_KEY"));
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
      long danglingContainerMinAgeSeconds) {

    /** Upper bound (1 day) for the dangling-container grace window; guards against overflow. */
    private static final long MAX_DANGLING_CONTAINER_MIN_AGE_SECONDS = 86_400L;

    public Docker {
      Objects.requireNonNull(defaultKind, "defaultKind");
      imageTags = imageTags == null ? Map.of() : Map.copyOf(new EnumMap<>(imageTags));
      for (RunnerKind kind : RunnerKind.values()) {
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
          120L);
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
