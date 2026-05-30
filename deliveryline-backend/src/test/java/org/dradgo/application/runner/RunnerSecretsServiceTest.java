package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Story 3.5 AC1/AC2/AC9/AC11(a)(b) — secret resolution from env vars + missing-secret failure. */
class RunnerSecretsServiceTest {

  private static final String CODEX_VALUE = "sk-codex-abc123-not-a-real-key";
  private static final String OPENAI_VALUE = "sk-openai-def456-not-a-real-key";
  private static final String ANTHROPIC_VALUE = "sk-ant-ghi789-not-a-real-key";

  private RunnerSecretsService serviceWith(MockEnvironment env) {
    return new RunnerSecretsService(env, RunnerProperties.defaults());
  }

  @Test
  void resolvesCodexKeyFromCanonicalEnvVar() {
    MockEnvironment env = new MockEnvironment().withProperty("CODEX_API_KEY", CODEX_VALUE);

    Map<String, String> resolved =
        serviceWith(env)
            .resolveSecretsForRunner(RunnerKind.CODEX, RunnerStage.EXECUTION, "run_workflow1");

    assertThat(resolved).containsExactly(Map.entry("CODEX_API_KEY", CODEX_VALUE));
  }

  @Test
  void resolvesClaudeKeyFromAnthropicEnvVar() {
    MockEnvironment env = new MockEnvironment().withProperty("ANTHROPIC_API_KEY", ANTHROPIC_VALUE);

    Map<String, String> resolved =
        serviceWith(env)
            .resolveSecretsForRunner(RunnerKind.CLAUDE, RunnerStage.INVESTIGATION, "run_workflow1");

    assertThat(resolved).containsExactly(Map.entry("ANTHROPIC_API_KEY", ANTHROPIC_VALUE));
  }

  @Test
  void fallsBackToAlternateSourceButInjectsUnderCanonicalName() {
    // Only the fallback OPENAI_API_KEY is set; the value is injected under the canonical
    // CODEX_API_KEY name the Codex runner image consumes (Trap T2).
    MockEnvironment env = new MockEnvironment().withProperty("OPENAI_API_KEY", OPENAI_VALUE);

    Map<String, String> resolved =
        serviceWith(env)
            .resolveSecretsForRunner(RunnerKind.CODEX, RunnerStage.EXECUTION, "run_workflow1");

    assertThat(resolved).containsExactly(Map.entry("CODEX_API_KEY", OPENAI_VALUE));
  }

  @Test
  void canonicalNameWinsWhenBothSourcesPresent() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("CODEX_API_KEY", CODEX_VALUE)
            .withProperty("OPENAI_API_KEY", OPENAI_VALUE);

    Map<String, String> resolved =
        serviceWith(env)
            .resolveSecretsForRunner(RunnerKind.CODEX, RunnerStage.EXECUTION, "run_workflow1");

    assertThat(resolved).containsExactly(Map.entry("CODEX_API_KEY", CODEX_VALUE));
  }

  @Test
  void missingSecretThrowsDoctorRunnerSecretMissingWithNameOnly() {
    MockEnvironment env = new MockEnvironment(); // nothing set

    assertThatThrownBy(
            () ->
                serviceWith(env)
                    .resolveSecretsForRunner(
                        RunnerKind.CLAUDE, RunnerStage.EXECUTION, "run_workflow1"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING);
              assertThat(ex.details()).containsEntry("runnerKind", "claude");
              assertThat(ex.details()).containsEntry("missingEnvVar", "ANTHROPIC_API_KEY");
              // No secret value leaks into details (none exists, but assert the shape is
              // name-only).
              assertThat(ex.details().keySet())
                  .containsExactlyInAnyOrder("runnerKind", "missingEnvVar");
            });
  }

  @Test
  void blankValueIsTreatedAsMissing() {
    MockEnvironment env = new MockEnvironment().withProperty("CODEX_API_KEY", "   ");

    assertThatThrownBy(
            () ->
                serviceWith(env)
                    .resolveSecretsForRunner(
                        RunnerKind.CODEX, RunnerStage.EXECUTION, "run_workflow1"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            ex ->
                assertThat(((DomainException) ex).details())
                    .containsEntry("missingEnvVar", "CODEX_API_KEY"));
  }

  @Test
  void resolveHostSecretReturnsValueWhenPresentAndEmptyOtherwise() {
    MockEnvironment env = new MockEnvironment().withProperty("GITHUB_TOKEN", "ghp_hostsideonly");
    RunnerSecretsService service = serviceWith(env);

    assertThat(service.resolveHostSecret("GITHUB_TOKEN")).contains("ghp_hostsideonly");
    assertThat(service.resolveHostSecret("LINEAR_API_KEY")).isEmpty();
    assertThat(service.resolveHostSecret(null)).isEmpty();
    assertThat(service.resolveHostSecret("  ")).isEmpty();
  }

  @Test
  void readsEnvironmentPerCallSoRotationTakesEffectWithoutRestart() {
    // AC9: no @Value snapshot / static cache — a value change between calls is observed
    // immediately.
    MockEnvironment env = new MockEnvironment().withProperty("ANTHROPIC_API_KEY", ANTHROPIC_VALUE);
    RunnerSecretsService service = serviceWith(env);

    assertThat(
            service
                .resolveSecretsForRunner(RunnerKind.CLAUDE, RunnerStage.EXECUTION, "run_workflow1")
                .get("ANTHROPIC_API_KEY"))
        .isEqualTo(ANTHROPIC_VALUE);

    env.withProperty("ANTHROPIC_API_KEY", "sk-ant-rotated-value");

    assertThat(
            service
                .resolveSecretsForRunner(RunnerKind.CLAUDE, RunnerStage.EXECUTION, "run_workflow1")
                .get("ANTHROPIC_API_KEY"))
        .isEqualTo("sk-ant-rotated-value");
  }
}
