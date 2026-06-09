package org.dradgo.application.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import org.dradgo.domain.registry.RunnerKind;
import org.junit.jupiter.api.Test;

/**
 * Story 3.2a AC12 — directed coverage of {@link RunnerProperties} binding fields and positive-value
 * guards, including the new {@code Docker.danglingContainerMinAgeSeconds} field added by this
 * story.
 */
class RunnerPropertiesTest {

  @Test
  void defaultsExposeTheStory32SchedulingAndDockerCleanupFields() {
    RunnerProperties defaults = RunnerProperties.defaults();
    assertEquals(60_000L, defaults.staleScanIntervalMs());
    assertEquals(5_000L, defaults.pollIntervalMs());
    assertEquals(10_000L, defaults.timeoutScanIntervalMs());
    assertEquals(3_600_000L, defaults.docker().workspaceCleanupIntervalMs());
    // Story 3.2a AC4 — the new dangling-container min-age grace window.
    assertEquals(120L, defaults.docker().danglingContainerMinAgeSeconds());
    // Story 3.6 AC4 — log capture stays local-only by default (no shareable elevation).
    assertEquals(false, defaults.allowShareableLogs());
  }

  @Test
  void allowShareableLogsBindsTheConfiguredValue() {
    // Story 3.6 AC4 — the flag is the operator opt-in gate for shareable-redacted log elevation.
    RunnerProperties enabled =
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
            RunnerProperties.Docker.defaults(),
            RunnerProperties.defaultSecretEnvNames(),
            true,
            RunnerProperties.SpecStage.defaults(),
            RunnerProperties.PlanStage.defaults());
    assertEquals(true, enabled.allowShareableLogs());
  }

  @Test
  void codexDefaultSecretEnvNamesAreSubscriptionFirst() {
    // Story 3a-3 (AC1) — CODEX_AUTH_JSON (subscription auth.json content) is prepended to the Codex
    // resolution list so RunnerSecretsService (first-present-wins) picks subscription over the API
    // key. Order IS the resolution preference; the API key remains the fallback.
    assertEquals(
        java.util.List.of("CODEX_AUTH_JSON", "CODEX_API_KEY", "OPENAI_API_KEY"),
        RunnerProperties.defaultSecretEnvNames().get(RunnerKind.CODEX));
    // Story 3.4 — Claude stays subscription-first dual-mode (unchanged by 3a-3).
    assertEquals(
        java.util.List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
        RunnerProperties.defaultSecretEnvNames().get(RunnerKind.CLAUDE));
    // secretEnvNamesFor delegates to the same default when the kind is unconfigured.
    assertEquals(
        java.util.List.of("CODEX_AUTH_JSON", "CODEX_API_KEY", "OPENAI_API_KEY"),
        RunnerProperties.defaults().secretEnvNamesFor(RunnerKind.CODEX));
  }

  @Test
  void specStageDefaultsToCodexWithAutoDispatchOn() {
    // Story 3a-1 (AC10) — non-Spring construction defaults: codex image + auto-dispatch ON.
    RunnerProperties.SpecStage specStage = RunnerProperties.defaults().specStage();
    assertEquals(RunnerKind.CODEX, specStage.kind());
    assertEquals(true, specStage.autoDispatch());
  }

  @Test
  void planStageDefaultsToCodexWithAutoDispatchOn() {
    // Story 3.11 (AC10) — non-Spring construction defaults mirror spec-stage: codex + auto-dispatch
    // ON. planAutoDispatchEnabled() reads the same switch.
    RunnerProperties defaults = RunnerProperties.defaults();
    assertEquals(RunnerKind.CODEX, defaults.planStage().kind());
    assertEquals(true, defaults.planStage().autoDispatch());
    assertEquals(true, defaults.planAutoDispatchEnabled());
  }

  @Test
  void kindForStageHonorsSpecStageForInvestigationAndPlanStageForExecution() {
    // Story 3a-1 (AC10) — INVESTIGATION resolves to spec-stage.kind. Story 3.11 (AC10 / D3) —
    // EXECUTION resolves to plan-stage.kind (covers both the implementation-plan and pr-output
    // sub-stages until story 3.12). Distinct kinds per stage prove the resolver is stage-keyed.
    RunnerProperties claudeSpecCodexPlan =
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
            RunnerProperties.Docker.defaults(),
            RunnerProperties.defaultSecretEnvNames(),
            false,
            new RunnerProperties.SpecStage(RunnerKind.CLAUDE, true),
            new RunnerProperties.PlanStage(RunnerKind.CODEX, true));
    assertEquals(
        RunnerKind.CLAUDE,
        claudeSpecCodexPlan.kindForStage(org.dradgo.domain.registry.RunnerStage.INVESTIGATION));
    assertEquals(
        RunnerKind.CODEX,
        claudeSpecCodexPlan.kindForStage(org.dradgo.domain.registry.RunnerStage.EXECUTION));

    // Flip the plan kind to claude and confirm EXECUTION tracks plan-stage.kind independently.
    RunnerProperties claudePlan =
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
            RunnerProperties.Docker.defaults(),
            RunnerProperties.defaultSecretEnvNames(),
            false,
            RunnerProperties.SpecStage.defaults(),
            new RunnerProperties.PlanStage(RunnerKind.CLAUDE, false));
    assertEquals(
        RunnerKind.CLAUDE,
        claudePlan.kindForStage(org.dradgo.domain.registry.RunnerStage.EXECUTION));
    assertEquals(false, claudePlan.planAutoDispatchEnabled());
  }

  @Test
  void staleScanIntervalMustBePositive() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunnerProperties(
                2.0d,
                java.util.Map.of(),
                10_000L,
                50,
                0L, // staleScanIntervalMs
                5_000L,
                RunnerProperties.Recovery.defaults(),
                RunnerProperties.Mock.defaults(),
                RunnerProperties.Scheduling.defaults(),
                RunnerProperties.Docker.defaults(),
                RunnerProperties.defaultSecretEnvNames(),
                false,
                RunnerProperties.SpecStage.defaults(),
                RunnerProperties.PlanStage.defaults()));
  }

  @Test
  void pollIntervalMustBePositive() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunnerProperties(
                2.0d,
                java.util.Map.of(),
                10_000L,
                50,
                60_000L,
                0L, // pollIntervalMs
                RunnerProperties.Recovery.defaults(),
                RunnerProperties.Mock.defaults(),
                RunnerProperties.Scheduling.defaults(),
                RunnerProperties.Docker.defaults(),
                RunnerProperties.defaultSecretEnvNames(),
                false,
                RunnerProperties.SpecStage.defaults(),
                RunnerProperties.PlanStage.defaults()));
  }

  @Test
  void workspaceCleanupIntervalMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> docker(0L, 120L));
  }

  @Test
  void danglingContainerMinAgeMayBeZeroButNotNegative() {
    // Zero disables the guard (allowed); negative is rejected.
    docker(3_600_000L, 0L); // no throw
    assertThrows(IllegalArgumentException.class, () -> docker(3_600_000L, -1L));
  }

  @Test
  void danglingContainerMinAgeRejectsValueAboveOneDay() {
    // Story 3.2a code-review (2026-05-29): the upper bound guards now.minusSeconds(...) overflow.
    docker(3_600_000L, 86_400L); // exactly one day — boundary allowed, no throw
    assertThrows(IllegalArgumentException.class, () -> docker(3_600_000L, 86_401L));
  }

  @Test
  void redactImageTagStripsEmbeddedCredentialsAndLeavesPlainRefsUntouched() {
    // Story 3.2a code-review (2026-05-29): directly pin the credential-redaction contract so a
    // credential-bearing image tag can never leak into the runner.dispatched audit event / logs.
    assertEquals(
        "***@host/image:tag", RunnerProperties.Docker.redactImageTag("user:secret@host/image:tag"));
    // Un-credentialed reference is untouched.
    assertEquals(
        "deliveryline/codex-runner:latest",
        RunnerProperties.Docker.redactImageTag("deliveryline/codex-runner:latest"));
    // Registry with a port + digest (no credentials) must NOT be over-redacted.
    assertEquals(
        "registry.example:5000/image@sha256:abc",
        RunnerProperties.Docker.redactImageTag("registry.example:5000/image@sha256:abc"));
    // Null / blank pass through unchanged.
    assertEquals(null, RunnerProperties.Docker.redactImageTag(null));
    assertEquals("  ", RunnerProperties.Docker.redactImageTag("  "));
  }

  private static RunnerProperties.Docker docker(
      long workspaceCleanupIntervalMs, long danglingContainerMinAgeSeconds) {
    EnumMap<RunnerKind, String> tags = new EnumMap<>(RunnerKind.class);
    tags.put(RunnerKind.CODEX, "deliveryline/codex-runner:latest");
    tags.put(RunnerKind.CLAUDE, "deliveryline/claude-runner:latest");
    return new RunnerProperties.Docker(
        RunnerKind.CODEX,
        tags,
        Path.of("runner-work"),
        24L,
        workspaceCleanupIntervalMs,
        Duration.ofSeconds(30L),
        Duration.ofSeconds(30L),
        danglingContainerMinAgeSeconds);
  }
}
