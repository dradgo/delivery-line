package org.dradgo.application.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Story 3.5 — resolves the agent-provider API key a runner <em>container</em> needs for a single
 * dispatch, with strict no-leak guarantees (NFR8/NFR9/NFR14).
 *
 * <p><b>Source of truth for env-var names (Trap T2).</b> The per-kind env-var <em>names</em> live
 * in {@link RunnerProperties#secretEnvNamesFor(RunnerKind)} (config-driven under {@code
 * deliveryline.runner.secret-env-names}); stories 3.3/3.4 read the same names. The <em>values</em>
 * are read at call time from the Spring {@link Environment} (system env + the optional {@code .env}
 * imported via {@code spring.config.import}) — never from {@code application.yml} defaults, never
 * snapshotted into a field or static (AC9 rotation: rotate {@code .env} + restart → next dispatch
 * uses the new value; in-flight containers keep their create-time env).
 *
 * <p><b>Container env only (Trap T1).</b> {@link #resolveSecretsForRunner} returns ONLY the
 * agent-provider key for the kind. {@code GITHUB_TOKEN} / {@code LINEAR_API_KEY} are host-side and
 * MUST NOT be injected into a runner container (story 3.14 AC10 / 3.9 AC10); the host-side {@link
 * #resolveHostSecret} resolver exists for those callers.
 *
 * <p><b>No detection here (Trap T7).</b> This service only <em>resolves values</em>; all credential
 * <em>detection</em> routes through {@code application.security.RedactionPolicyService}.
 *
 * <p><b>Logging.</b> Entry/exit at INFO carry {@code runnerKind} + a resolved-var <em>count</em>
 * only. The {@code WARN} on a missing var names the absent var (a name is not a value), never a
 * value. No log line ever carries a secret value.
 */
@Service
public class RunnerSecretsService {

  private static final Logger log = LoggerFactory.getLogger(RunnerSecretsService.class);

  private final Environment environment;
  private final RunnerProperties runnerProperties;

  public RunnerSecretsService(Environment environment, RunnerProperties runnerProperties) {
    this.environment = Objects.requireNonNull(environment, "environment");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
  }

  /**
   * Resolve the {@code env-var name → value} map a runner container of {@code runnerKind} needs for
   * this dispatch (the agent-provider key only — Trap T1). The configured names are tried in order
   * and the <em>first present</em> non-blank value wins; the value is injected under the name it
   * was <em>actually found under</em>, so the runner image reads it back under that exact name.
   *
   * <p>Story 3.4: this matched-name injection is required because a kind's names are not always
   * interchangeable aliases. Codex's {@code [CODEX_API_KEY, OPENAI_API_KEY]} are two names for the
   * same OpenAI key, but Claude's {@code [CLAUDE_CODE_OAUTH_TOKEN, ANTHROPIC_API_KEY]} are two
   * DISTINCT credential types (subscription OAuth token vs API key) the CLI reads from different
   * env vars — collapsing them to a single canonical name would inject an API key under the
   * OAuth-token name. The first configured name remains the <em>preferred</em> credential and the
   * name hinted on a missing-secret failure.
   *
   * @throws DomainException {@link DomainErrorCode#DOCTOR_RUNNER_SECRET_MISSING} when no configured
   *     name resolves to a non-blank value — dispatch fails fast (runners may be optional in early
   *     pilot phases, so this is a per-dispatch failure, not a startup failure). {@code details}
   *     carries {@code runnerKind} + {@code missingEnvVar} (the preferred NAME, never a value).
   */
  public Map<String, String> resolveSecretsForRunner(
      RunnerKind runnerKind, RunnerStage stage, String workflowRunId) {
    Objects.requireNonNull(runnerKind, "runnerKind");
    Objects.requireNonNull(stage, "stage");
    log.info(
        "runner secrets resolve start runnerKind={} stage={} workflowRunId={}",
        runnerKind.value(),
        stage.value(),
        workflowRunId);

    // Story 3.8 — Codex subscription auth.json sourced LIVE from a host file each dispatch
    // (deliveryline.codex.auth-json-file / CODEX_AUTH_JSON_FILE, typically ~/.codex/auth.json),
    // taking precedence over a frozen CODEX_AUTH_JSON env value. Subscription OAuth refresh tokens
    // are
    // single-use: a frozen env value goes stale once any run/host refreshes it
    // ("refresh_token_reused"
    // 401). Re-reading the host CLI's CURRENT auth.json every dispatch keeps the injected token
    // fresh.
    if (runnerKind == RunnerKind.CODEX) {
      Optional<String> fromFile = resolveCodexAuthJsonFromFile();
      if (fromFile.isPresent()) {
        log.info(
            "runner secrets resolve ok runnerKind={} stage={} workflowRunId={} source=auth-json-file"
                + " resolvedVarCount=1 valueLength={}",
            runnerKind.value(),
            stage.value(),
            workflowRunId,
            fromFile.get().length());
        return Map.of("CODEX_AUTH_JSON", fromFile.get());
      }
    }

    List<String> candidateNames = runnerProperties.secretEnvNamesFor(runnerKind);
    if (candidateNames.isEmpty()) {
      // Defaults guarantee a non-empty list for every RunnerKind; an empty list means an operator
      // explicitly blanked the mapping for this kind. Treat as missing-secret with no name to hint.
      throw secretMissing(runnerKind, runnerKind.value() + " (no env-var name configured)");
    }
    String preferredName = candidateNames.get(0);

    for (String name : candidateNames) {
      String value = environment.getProperty(name);
      if (value != null && !value.isBlank()) {
        Map<String, String> resolved = new LinkedHashMap<>();
        // Inject under the name the value was found under (story 3.4) so the runner image reads it
        // back under that exact name — required for non-alias credentials like Claude's
        // OAuth-token-or-API-key dual mode.
        resolved.put(name, value);
        log.info(
            "runner secrets resolve ok runnerKind={} stage={} workflowRunId={} resolvedVarCount={}",
            runnerKind.value(),
            stage.value(),
            workflowRunId,
            resolved.size());
        return Map.copyOf(resolved);
      }
    }

    log.warn(
        "runner secret missing runnerKind={} stage={} workflowRunId={} missingEnvVar={}",
        runnerKind.value(),
        stage.value(),
        workflowRunId,
        preferredName);
    throw secretMissing(runnerKind, preferredName);
  }

  /**
   * Story 3.8 — read the Codex subscription credential from the host file configured at {@code
   * deliveryline.codex.auth-json-file} (or the {@code CODEX_AUTH_JSON_FILE} env), e.g. {@code
   * ~/.codex/auth.json}. Returns the file's current non-blank content so each dispatch injects the
   * host CLI's latest token, or {@link Optional#empty()} when unconfigured, missing, unreadable, or
   * empty (callers then fall back to the {@code CODEX_AUTH_JSON} env var). The value is a live
   * credential and is NEVER logged.
   */
  private Optional<String> resolveCodexAuthJsonFromFile() {
    String path = environment.getProperty("deliveryline.codex.auth-json-file");
    if (path == null || path.isBlank()) {
      path = environment.getProperty("CODEX_AUTH_JSON_FILE");
    }
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    try {
      String content = Files.readString(Path.of(path.strip())).strip();
      if (content.isEmpty()) {
        log.warn("configured codex auth-json-file is empty path={}", path);
        return Optional.empty();
      }
      return Optional.of(content);
    } catch (IOException | RuntimeException error) {
      log.warn(
          "could not read codex auth-json-file path={} cause={}; falling back to CODEX_AUTH_JSON env",
          path,
          error.toString());
      return Optional.empty();
    }
  }

  /**
   * Host-side resolver for credentials that NEVER enter a runner container (git clone/push —
   * 3.9/3.14; Linear intake — Epic 1/2). Returns the value from the Spring {@link Environment} when
   * present and non-blank, else {@link Optional#empty()}. Trap T1: callers MUST NOT feed the result
   * into {@link CreateContainerSpec} environment.
   */
  public Optional<String> resolveHostSecret(String envName) {
    if (envName == null || envName.isBlank()) {
      return Optional.empty();
    }
    String value = environment.getProperty(envName);
    boolean present = value != null && !value.isBlank();
    log.info("host secret resolve envVar={} present={}", envName, present);
    return present ? Optional.of(value) : Optional.empty();
  }

  private static DomainException secretMissing(RunnerKind runnerKind, String missingEnvVar) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerKind", runnerKind.value());
    details.put("missingEnvVar", missingEnvVar);
    return new DomainException(
        DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING,
        "runner secret missing for kind " + runnerKind.value() + ": " + missingEnvVar,
        details);
  }
}
