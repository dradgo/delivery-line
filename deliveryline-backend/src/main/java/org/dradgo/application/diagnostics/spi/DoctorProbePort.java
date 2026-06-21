package org.dradgo.application.diagnostics.spi;

public interface DoctorProbePort {

  ProbeResult probeJavaVersion();

  ProbeResult probeSpringProfiles();

  ProbeResult probePostgresConnectivity();

  ProbeResult probeFlywayState();

  ProbeResult probeArtifactDirectory();

  ProbeResult probeConfigFilePermissions();

  ProbeResult probeDockerAvailability();

  ProbeResult probeRunnerSecrets();

  ProbeResult probeRestBindAddress();

  ProbeResult probeSupportedEnvironment();

  /**
   * Story 3.14 AC9 — GitHub PAT auth check. When the {@code github-real} profile is inactive (the
   * default — mock profile), returns a PASS "not-applicable" result and makes <strong>no network
   * call</strong>. When active: token blank ⇒ FAIL {@code DOCTOR_GITHUB_TOKEN_MISSING}; otherwise
   * probes {@code GET /user} and reports PASS on 200 / FAIL {@code DOCTOR_GITHUB_AUTH_FAILED} on
   * 401/403. Reports presence only — never logs or returns the token (NFR8/NFR9).
   */
  ProbeResult probeGitHubAuth();

  /**
   * Story 3.9 AC15 — system {@code git} availability. Like {@link #probeGitHubAuth()}, returns a
   * PASS "not-applicable" result with NO process call when the {@code github-real} profile is
   * inactive (the default mock profile). Under {@code github-real}: probes {@code git --version}
   * and reports PASS on exit 0, FAIL {@code DOCTOR_GIT_MISSING} when the binary is absent / errors
   * / times out (the system git CLI is the runtime prerequisite for {@code
   * RepositoryWorkspaceService}, ADR 0022).
   */
  ProbeResult probeGitAvailability();

  /**
   * Story 3.9 AC15 — validates the configured {@code deliveryline-bot} git identity (Decision D8).
   * PASS "not-applicable" with no work when {@code github-real} is inactive. Under {@code
   * github-real}: PASS when the operator explicitly configured a bot name + email, otherwise WARN
   * {@code DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED} (a bot identity is desirable but a real run
   * proceeds with the built-in default). Reports presence only — never logs a token.
   */
  ProbeResult probeGitBotIdentity();

  /**
   * Story 3.7 AC10 / Decision D7 — host-memory advisory for the profile-gated ELK observability
   * stack. SKIP (not-applicable) when the {@code observability} profile is inactive — symmetric
   * with the github-real-gated probes. When active: WARN {@code DOCTOR_OBSERVABILITY_LOW_MEMORY}
   * (never FAIL — AR25 keeps observability optional) if total host physical memory is under the
   * recommended 8 GB; otherwise PASS. Reports memory metrics only — never logs payloads or secrets.
   */
  ProbeResult probeObservabilityMemory();

  /**
   * Story 3.16 AC7 — reports the Linear completion-sync setting. PASS when disabled (the feature is
   * opt-out, never a failure); when enabled, PASS with the validated template's placeholders, or
   * FAIL {@code INVALID_COMPLETION_TEMPLATE} if the template is malformed (defensive — the startup
   * validator would already have failed boot in that case). Reports the enabled flag + template
   * placeholders only — never a token or payload.
   */
  ProbeResult probeLinearCompletionSync();

  /**
   * Story 3c-10 (AC1/AC2/AC3) — per-project configuration health read-out. SKIP (not-applicable)
   * when the {@code ProjectStore} read side is absent (lean / doctor-smoke contexts with no
   * DataSource — symmetric with {@link #probePostgresConnectivity()}). Otherwise lists every
   * non-archived project, reporting per project its status / connector kinds / repository binding /
   * OpenSpec flag and, per active project per connector role, a PRESENCE-only credential verdict
   * ({@code present-via-store} / {@code present-via-host-env} / {@code missing}). PASS when every
   * active project is fully configured; WARN {@code DOCTOR_PROJECT_CONFIG_INCOMPLETE} when at least
   * one active project is misconfigured (blank repository URL, a {@code missing} credential, or a
   * connector kind the resolver cannot resolve — caught and rendered per-project, never
   * propagated). Disabled projects are listed but excluded from the WARN roll-up. This is a fast,
   * local, config-only read — NO live connectivity test (that is the project {@code testConnection}
   * endpoint, story 3c-8). Reports PRESENCE only — never logs or returns a secret value or
   * ciphertext.
   */
  ProbeResult probeProjects();
}
