package org.dradgo.application.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
    Docker docker) {

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
        Docker.defaults());
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
      if (danglingContainerMinAgeSeconds < 0L) {
        throw new IllegalArgumentException(
            "deliveryline.runner.docker.dangling-container-min-age-seconds must be >= 0: "
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
  }
}
