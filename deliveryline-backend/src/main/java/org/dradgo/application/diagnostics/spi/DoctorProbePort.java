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
}
