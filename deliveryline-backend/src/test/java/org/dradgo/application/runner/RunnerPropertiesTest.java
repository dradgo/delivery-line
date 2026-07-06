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
    // Story 3.17a AC4 — the RunnerExecutionQueue backpressure cap defaults to 100.
    assertEquals(100, defaults.queueMaxDepth());
  }

  @Test
  void queueMaxDepthIsClampedToAtLeastOne() {
    // Story 3.17a AC4 — a cap below 1 (0, negative, or an unset primitive bound to 0) would reject
    // every enqueue; the compact ctor coerces it up to 1 rather than dead-queueing.
    assertEquals(1, queueMaxDepthProperties(0).queueMaxDepth());
    assertEquals(1, queueMaxDepthProperties(-5).queueMaxDepth());
    // A configured value at or above 1 binds verbatim.
    assertEquals(250, queueMaxDepthProperties(250).queueMaxDepth());
  }

  private static RunnerProperties queueMaxDepthProperties(int queueMaxDepth) {
    RunnerProperties d = RunnerProperties.defaults();
    return new RunnerProperties(
        d.staleThresholdMultiplier(),
        d.stageTimeouts(),
        d.timeoutScanIntervalMs(),
        d.timeoutScanBatchSize(),
        d.staleScanIntervalMs(),
        d.pollIntervalMs(),
        d.recovery(),
        d.mock(),
        d.scheduling(),
        d.docker(),
        d.secretEnvNames(),
        d.allowShareableLogs(),
        d.specStage(),
        d.planStage(),
        d.implementationStage(),
        d.openspec(),
        d.buildStage(),
        d.lintStage(),
        queueMaxDepth);
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
            RunnerProperties.PlanStage.defaults(),
            RunnerProperties.ImplementationStage.defaults(),
            RunnerProperties.OpenSpec.defaults(),
            RunnerProperties.BuildStage.defaults(),
            RunnerProperties.LintStage.defaults(),
            100);
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
            new RunnerProperties.PlanStage(RunnerKind.CODEX, true),
            RunnerProperties.ImplementationStage.defaults(),
            RunnerProperties.OpenSpec.defaults(),
            RunnerProperties.BuildStage.defaults(),
            RunnerProperties.LintStage.defaults(),
            100);
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
            new RunnerProperties.PlanStage(RunnerKind.CLAUDE, false),
            RunnerProperties.ImplementationStage.defaults(),
            RunnerProperties.OpenSpec.defaults(),
            RunnerProperties.BuildStage.defaults(),
            RunnerProperties.LintStage.defaults(),
            100);
    assertEquals(
        RunnerKind.CLAUDE,
        claudePlan.kindForStage(org.dradgo.domain.registry.RunnerStage.EXECUTION));
    assertEquals(false, claudePlan.planAutoDispatchEnabled());

    // Story 3h-1 (AC1 switch-arm) — BUILD is never runner-kind-dispatched (it runs backend-side via
    // BuildStageService/BuildCommandPort), so kindForStage(BUILD) must fail loud, exactly like the
    // REVIEW arm (reaching here for either stage is a routing bug, not a silent mis-resolve).
    assertThrows(
        IllegalStateException.class,
        () -> claudePlan.kindForStage(org.dradgo.domain.registry.RunnerStage.BUILD));
    assertThrows(
        IllegalStateException.class,
        () -> claudePlan.kindForStage(org.dradgo.domain.registry.RunnerStage.REVIEW));
  }

  @Test
  void implementationStageDefaultsToCodexWithAutoDispatchOn() {
    // Story 3.12 (AC1) — non-Spring construction defaults mirror plan-stage: codex + auto-dispatch
    // ON. implementationAutoDispatchEnabled() reads the same switch.
    RunnerProperties defaults = RunnerProperties.defaults();
    assertEquals(RunnerKind.CODEX, defaults.implementationStage().kind());
    assertEquals(true, defaults.implementationStage().autoDispatch());
    assertEquals(true, defaults.implementationAutoDispatchEnabled());
  }

  @Test
  void kindForExecutionSubStageHonorsPlanStageForPlanAndImplementationStageForPrOutput() {
    // Story 3.12 (AC1 / Task 5, closes 3.11 OQ-4) — IMPLEMENTATION_PLAN resolves to
    // plan-stage.kind,
    // PR_OUTPUT resolves to implementation-stage.kind; a null sub-stage falls back to
    // plan-stage.kind
    // (legacy/recovery composition byte-identical). Distinct kinds prove the resolver is
    // sub-stage-keyed.
    RunnerProperties props =
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
            new RunnerProperties.PlanStage(RunnerKind.CODEX, true),
            new RunnerProperties.ImplementationStage(RunnerKind.CLAUDE, true),
            RunnerProperties.OpenSpec.defaults(),
            RunnerProperties.BuildStage.defaults(),
            RunnerProperties.LintStage.defaults(),
            100);
    assertEquals(
        RunnerKind.CODEX, props.kindForExecutionSubStage(ExecutionSubStage.IMPLEMENTATION_PLAN));
    assertEquals(RunnerKind.CLAUDE, props.kindForExecutionSubStage(ExecutionSubStage.PR_OUTPUT));
    assertEquals(RunnerKind.CODEX, props.kindForExecutionSubStage(null));
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
                RunnerProperties.PlanStage.defaults(),
                RunnerProperties.ImplementationStage.defaults(),
                RunnerProperties.OpenSpec.defaults(),
                RunnerProperties.BuildStage.defaults(),
                RunnerProperties.LintStage.defaults(),
                100));
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
                RunnerProperties.PlanStage.defaults(),
                RunnerProperties.ImplementationStage.defaults(),
                RunnerProperties.OpenSpec.defaults(),
                RunnerProperties.BuildStage.defaults(),
                RunnerProperties.LintStage.defaults(),
                100));
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
        danglingContainerMinAgeSeconds,
        "none",
        java.util.List.of());
  }
}
