package org.dradgo.application.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.diagnostics.spi.DoctorProbePort;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class DoctorServiceTest {

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-14T10:00:00Z"), ZoneOffset.UTC);
  private final DoctorProbePort probes = mock(DoctorProbePort.class);
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final DoctorService service = new DoctorService(probes, redaction, fixedClock);

  @Test
  void runDiagnosticsReturnsAllChecksInCanonicalOrder() {
    stubAllProbesPass();

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    assertThat(report.schemaVersion()).isEqualTo(1);
    assertThat(report.overallStatus()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(report.checks()).hasSize(DoctorService.STATIC_ORDER.size());
    assertThat(report.checks().stream().map(DiagnosticsCheck::name).toList())
        .isEqualTo(DoctorService.STATIC_ORDER);
  }

  @Test
  void onlyFilterPreservesCanonicalOrder() {
    stubAllProbesPass();

    DoctorRunRequest request =
        new DoctorRunRequest(
            Set.of(DoctorService.CHECK_FLYWAY_STATE, DoctorService.CHECK_POSTGRES_CONNECTIVITY),
            Set.of(),
            "corr-1");
    DiagnosticsReport report = service.runDiagnostics(request);

    assertThat(report.checks().stream().map(DiagnosticsCheck::name).toList())
        .containsExactly(
            DoctorService.CHECK_POSTGRES_CONNECTIVITY, DoctorService.CHECK_FLYWAY_STATE);
  }

  @Test
  void excludeFilterRendersSkippedChecksWithExcludeSummary() {
    stubAllProbesPass();

    DoctorRunRequest request =
        new DoctorRunRequest(Set.of(), Set.of(DoctorService.CHECK_DOCKER_AVAILABILITY), null);
    DiagnosticsReport report = service.runDiagnostics(request);

    DiagnosticsCheck dockerCheck = findCheck(report, DoctorService.CHECK_DOCKER_AVAILABILITY);
    assertThat(dockerCheck.status()).isEqualTo(DiagnosticsStatus.SKIP);
    assertThat(dockerCheck.summary()).isEqualTo("Excluded via --exclude");
  }

  @Test
  void unknownOnlyCheckNameRaisesInvalidCommandPayload() {
    assertThatThrownBy(
            () ->
                service.runDiagnostics(new DoctorRunRequest(Set.of("not-a-check"), Set.of(), null)))
        .isInstanceOf(DomainException.class)
        .satisfies(
            t -> {
              DomainException de = (DomainException) t;
              assertThat(de.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
              assertThat(de.details()).containsEntry("unknownCheck", "not-a-check");
            });
  }

  @Test
  void mutuallyExclusiveOnlyAndExcludeRaisesInvalidCommandPayload() {
    assertThatThrownBy(
            () ->
                service.runDiagnostics(
                    new DoctorRunRequest(
                        Set.of(DoctorService.CHECK_POSTGRES_CONNECTIVITY),
                        Set.of(DoctorService.CHECK_FLYWAY_STATE),
                        null)))
        .isInstanceOf(DomainException.class)
        .satisfies(
            t -> {
              DomainException de = (DomainException) t;
              assertThat(de.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
              assertThat(de.details())
                  .containsEntry("rule", "--only and --exclude are mutually exclusive");
            });
  }

  @Test
  void overallStatusIsFailWhenAnyCheckFails() {
    when(probes.probeJavaVersion()).thenReturn(ProbeResult.pass("Java 21"));
    when(probes.probeSpringProfiles()).thenReturn(ProbeResult.pass("local"));
    when(probes.probePostgresConnectivity())
        .thenReturn(
            ProbeResult.fail(
                "Postgres unreachable",
                DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE.value(),
                Map.of()));
    when(probes.probeFlywayState()).thenReturn(ProbeResult.pass("Flyway up"));
    when(probes.probeArtifactDirectory()).thenReturn(ProbeResult.pass("Writable"));
    when(probes.probeConfigFilePermissions()).thenReturn(ProbeResult.pass("Permissions ok"));
    when(probes.probeDockerAvailability())
        .thenReturn(
            ProbeResult.warn(
                "Docker unreachable", DomainErrorCode.DOCTOR_DOCKER_MISSING.value(), Map.of()));
    when(probes.probeRunnerSecrets()).thenReturn(ProbeResult.pass("Runner secrets present"));
    when(probes.probeRestBindAddress()).thenReturn(ProbeResult.pass("Loopback"));
    when(probes.probeSupportedEnvironment())
        .thenReturn(ProbeResult.pass("Supported environment in matrix"));
    when(probes.probeGitHubAuth()).thenReturn(ProbeResult.pass("github-real inactive"));
    when(probes.probeGitAvailability()).thenReturn(ProbeResult.pass("git n/a"));
    when(probes.probeGitBotIdentity()).thenReturn(ProbeResult.pass("git identity n/a"));
    when(probes.probeObservabilityMemory()).thenReturn(ProbeResult.skip("observability inactive"));
    when(probes.probeLinearCompletionSync())
        .thenReturn(ProbeResult.pass("completion-sync enabled"));
    when(probes.probeProjects()).thenReturn(ProbeResult.pass("projects configured"));
    when(probes.probeJiraAuth()).thenReturn(ProbeResult.pass("jira-real inactive"));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    assertThat(report.overallStatus()).isEqualTo(DiagnosticsStatus.FAIL);
    DiagnosticsCheck postgres = findCheck(report, DoctorService.CHECK_POSTGRES_CONNECTIVITY);
    assertThat(postgres.remediation()).contains("docker compose up -d postgres");
    assertThat(postgres.errorCode()).isEqualTo("DOCTOR_POSTGRES_UNREACHABLE");
  }

  @Test
  void runnerSecretsRemediationNamesClaudeSubscriptionToken() {
    stubAllProbesPass();
    when(probes.probeRunnerSecrets())
        .thenReturn(
            ProbeResult.fail(
                "Runner provider secrets missing",
                DomainErrorCode.DOCTOR_RUNNER_SECRET_MISSING.value(),
                Map.of("claude", "missing")));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    DiagnosticsCheck runnerSecrets = findCheck(report, DoctorService.CHECK_RUNNER_SECRETS);
    assertThat(runnerSecrets.remediation())
        .contains("CLAUDE_CODE_OAUTH_TOKEN")
        .contains("ANTHROPIC_API_KEY");
  }

  @Test
  void artifactDirectoryRemediationIncludesResolvedPathContext() {
    when(probes.probeJavaVersion()).thenReturn(ProbeResult.pass("Java 21"));
    when(probes.probeSpringProfiles()).thenReturn(ProbeResult.pass("local"));
    when(probes.probePostgresConnectivity()).thenReturn(ProbeResult.pass("Postgres up"));
    when(probes.probeFlywayState()).thenReturn(ProbeResult.pass("Flyway up"));
    when(probes.probeArtifactDirectory())
        .thenReturn(
            ProbeResult.fail(
                "Artifact directory unwritable: AccessDeniedException",
                DomainErrorCode.DOCTOR_ARTIFACT_DIR_UNWRITABLE.value(),
                Map.of("artifactRoot", "/var/tmp/deliveryline/artifacts")));
    when(probes.probeConfigFilePermissions()).thenReturn(ProbeResult.pass("Permissions ok"));
    when(probes.probeDockerAvailability()).thenReturn(ProbeResult.pass("Docker up"));
    when(probes.probeRunnerSecrets()).thenReturn(ProbeResult.pass("Runner secrets present"));
    when(probes.probeRestBindAddress()).thenReturn(ProbeResult.pass("Loopback"));
    when(probes.probeSupportedEnvironment())
        .thenReturn(ProbeResult.pass("Supported environment in matrix"));
    when(probes.probeGitHubAuth()).thenReturn(ProbeResult.pass("github-real inactive"));
    when(probes.probeGitAvailability()).thenReturn(ProbeResult.pass("git n/a"));
    when(probes.probeGitBotIdentity()).thenReturn(ProbeResult.pass("git identity n/a"));
    when(probes.probeObservabilityMemory()).thenReturn(ProbeResult.skip("observability inactive"));
    when(probes.probeLinearCompletionSync())
        .thenReturn(ProbeResult.pass("completion-sync enabled"));
    when(probes.probeProjects()).thenReturn(ProbeResult.pass("projects configured"));
    when(probes.probeJiraAuth()).thenReturn(ProbeResult.pass("jira-real inactive"));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    DiagnosticsCheck artifactDirectory = findCheck(report, DoctorService.CHECK_ARTIFACT_DIRECTORY);
    assertThat(artifactDirectory.remediation())
        .contains("Ensure the artifact root directory exists and is writable:")
        .isNotEqualTo("Ensure the artifact root directory exists and is writable.");
  }

  @Test
  void overallStatusIsWarnWhenAnyWarnAndNoFail() {
    stubAllProbesPass();
    when(probes.probeDockerAvailability())
        .thenReturn(
            ProbeResult.warn(
                "Docker unreachable", DomainErrorCode.DOCTOR_DOCKER_MISSING.value(), Map.of()));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    assertThat(report.overallStatus()).isEqualTo(DiagnosticsStatus.WARN);
  }

  @Test
  void overallStatusIsPassWhenAllChecksPassOrSkip() {
    stubAllProbesPass();

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    assertThat(report.overallStatus()).isEqualTo(DiagnosticsStatus.PASS);
  }

  @Test
  void runnerImageAndFrontendChecksAreReservedAsSkip() {
    stubAllProbesPass();

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    assertThat(findCheck(report, DoctorService.CHECK_RUNNER_IMAGE_AVAILABILITY).status())
        .isEqualTo(DiagnosticsStatus.SKIP);
    assertThat(findCheck(report, DoctorService.CHECK_FRONTEND_ASSET_PRESENCE).status())
        .isEqualTo(DiagnosticsStatus.SKIP);
  }

  @Test
  void supportedEnvironmentSlotInvokesProbe() {
    stubAllProbesPass();
    when(probes.probeSupportedEnvironment())
        .thenReturn(
            new ProbeResult(
                DiagnosticsStatus.PASS,
                "Windows 11 + PowerShell 7.4 + Docker Desktop",
                null,
                Map.of("os", "windows", "shell", "powershell", "matrixRow", "win11")));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    DiagnosticsCheck supportedEnv = findCheck(report, DoctorService.CHECK_SUPPORTED_ENVIRONMENT);
    assertThat(supportedEnv.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(supportedEnv.summary()).contains("Windows 11");
    assertThat(supportedEnv.details()).containsEntry("matrixRow", "win11");
  }

  @Test
  void supportedEnvironmentFailRendersDetectedOsShellInRemediation() {
    stubAllProbesPass();
    when(probes.probeSupportedEnvironment())
        .thenReturn(
            new ProbeResult(
                DiagnosticsStatus.FAIL,
                "Unsupported OS detected; combination is outside the supported matrix",
                DomainErrorCode.DOCTOR_UNSUPPORTED_ENVIRONMENT.value(),
                Map.of("os", "freebsd", "shell", "tcsh", "matrixRow", "none")));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    DiagnosticsCheck supportedEnv = findCheck(report, DoctorService.CHECK_SUPPORTED_ENVIRONMENT);
    assertThat(supportedEnv.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(supportedEnv.errorCode()).isEqualTo("DOCTOR_UNSUPPORTED_ENVIRONMENT");
    assertThat(supportedEnv.remediation())
        .contains("freebsd+tcsh")
        .contains("docs/supported-environments.md");
    assertThat(report.overallStatus()).isEqualTo(DiagnosticsStatus.FAIL);
  }

  @Test
  void summaryRemediationAndDetailsArePassedThroughRedaction() {
    when(probes.probeJavaVersion()).thenReturn(ProbeResult.pass("Java 21"));
    when(probes.probeSpringProfiles()).thenReturn(ProbeResult.pass("local"));
    Map<String, String> details = new LinkedHashMap<>();
    details.put("authHeader", "Authorization: Bearer ghp_aaaaaaaaaaaaaaaaaaaa");
    when(probes.probePostgresConnectivity())
        .thenReturn(
            ProbeResult.fail(
                "Postgres unreachable; saw github_pat_abcdefghij1234567890 in env",
                DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE.value(),
                details));
    when(probes.probeFlywayState()).thenReturn(ProbeResult.pass("Flyway up"));
    when(probes.probeArtifactDirectory()).thenReturn(ProbeResult.pass("Writable"));
    when(probes.probeConfigFilePermissions()).thenReturn(ProbeResult.pass("Permissions ok"));
    when(probes.probeDockerAvailability()).thenReturn(ProbeResult.pass("Docker up"));
    when(probes.probeRunnerSecrets()).thenReturn(ProbeResult.pass("Runner secrets present"));
    when(probes.probeRestBindAddress()).thenReturn(ProbeResult.pass("Loopback"));
    when(probes.probeSupportedEnvironment())
        .thenReturn(ProbeResult.pass("Supported environment in matrix"));
    when(probes.probeGitHubAuth()).thenReturn(ProbeResult.pass("github-real inactive"));
    when(probes.probeGitAvailability()).thenReturn(ProbeResult.pass("git n/a"));
    when(probes.probeGitBotIdentity()).thenReturn(ProbeResult.pass("git identity n/a"));
    when(probes.probeObservabilityMemory()).thenReturn(ProbeResult.skip("observability inactive"));
    when(probes.probeLinearCompletionSync())
        .thenReturn(ProbeResult.pass("completion-sync enabled"));
    when(probes.probeProjects()).thenReturn(ProbeResult.pass("projects configured"));
    when(probes.probeJiraAuth()).thenReturn(ProbeResult.pass("jira-real inactive"));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    DiagnosticsCheck postgres = findCheck(report, DoctorService.CHECK_POSTGRES_CONNECTIVITY);
    assertThat(postgres.summary()).doesNotContain("github_pat_");
    assertThat(postgres.details().get("authHeader")).doesNotContain("ghp_");
  }

  @Test
  void probeExceptionsAreTrappedAndSurfacedAsFail() {
    when(probes.probeJavaVersion()).thenReturn(ProbeResult.pass("Java 21"));
    when(probes.probeSpringProfiles()).thenReturn(ProbeResult.pass("local"));
    when(probes.probePostgresConnectivity()).thenThrow(new RuntimeException("boom"));
    when(probes.probeFlywayState()).thenReturn(ProbeResult.pass("Flyway up"));
    when(probes.probeArtifactDirectory()).thenReturn(ProbeResult.pass("Writable"));
    when(probes.probeConfigFilePermissions()).thenReturn(ProbeResult.pass("Permissions ok"));
    when(probes.probeDockerAvailability()).thenReturn(ProbeResult.pass("Docker up"));
    when(probes.probeRunnerSecrets()).thenReturn(ProbeResult.pass("Runner secrets present"));
    when(probes.probeRestBindAddress()).thenReturn(ProbeResult.pass("Loopback"));
    when(probes.probeSupportedEnvironment())
        .thenReturn(ProbeResult.pass("Supported environment in matrix"));
    when(probes.probeGitHubAuth()).thenReturn(ProbeResult.pass("github-real inactive"));
    when(probes.probeGitAvailability()).thenReturn(ProbeResult.pass("git n/a"));
    when(probes.probeGitBotIdentity()).thenReturn(ProbeResult.pass("git identity n/a"));
    when(probes.probeObservabilityMemory()).thenReturn(ProbeResult.skip("observability inactive"));
    when(probes.probeLinearCompletionSync())
        .thenReturn(ProbeResult.pass("completion-sync enabled"));
    when(probes.probeProjects()).thenReturn(ProbeResult.pass("projects configured"));
    when(probes.probeJiraAuth()).thenReturn(ProbeResult.pass("jira-real inactive"));

    DiagnosticsReport report = service.runDiagnostics(DoctorRunRequest.all());

    DiagnosticsCheck postgres = findCheck(report, DoctorService.CHECK_POSTGRES_CONNECTIVITY);
    assertThat(postgres.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(postgres.errorCode()).isEqualTo(DomainErrorCode.INTERNAL_ERROR.value());
    assertThat(report.overallStatus()).isEqualTo(DiagnosticsStatus.FAIL);
  }

  @Test
  void doctorServiceCarriesReadOnlyTransactionalAnnotation() {
    Transactional annotation = DoctorService.class.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.readOnly()).isTrue();
  }

  @Test
  void runDiagnosticsMethodSignatureMatchesContract() throws NoSuchMethodException {
    Method method = DoctorService.class.getMethod("runDiagnostics", DoctorRunRequest.class);
    assertThat(method.getReturnType()).isEqualTo(DiagnosticsReport.class);
  }

  private void stubAllProbesPass() {
    when(probes.probeJavaVersion()).thenReturn(ProbeResult.pass("Java 21"));
    when(probes.probeSpringProfiles()).thenReturn(ProbeResult.pass("local"));
    when(probes.probePostgresConnectivity()).thenReturn(ProbeResult.pass("Postgres up"));
    when(probes.probeFlywayState()).thenReturn(ProbeResult.pass("Flyway up"));
    when(probes.probeArtifactDirectory()).thenReturn(ProbeResult.pass("Writable"));
    when(probes.probeConfigFilePermissions()).thenReturn(ProbeResult.pass("Permissions ok"));
    when(probes.probeDockerAvailability()).thenReturn(ProbeResult.pass("Docker up"));
    when(probes.probeRunnerSecrets()).thenReturn(ProbeResult.pass("Runner secrets present"));
    when(probes.probeRestBindAddress()).thenReturn(ProbeResult.pass("Loopback"));
    when(probes.probeSupportedEnvironment())
        .thenReturn(ProbeResult.pass("Supported environment in matrix"));
    when(probes.probeGitHubAuth())
        .thenReturn(
            ProbeResult.pass("github-real profile inactive; GitHub auth check not applicable"));
    when(probes.probeGitAvailability())
        .thenReturn(
            ProbeResult.pass(
                "github-real profile inactive; git availability check not applicable"));
    when(probes.probeGitBotIdentity())
        .thenReturn(
            ProbeResult.pass(
                "github-real profile inactive; git bot identity check not applicable"));
    when(probes.probeObservabilityMemory())
        .thenReturn(ProbeResult.skip("observability profile inactive"));
    when(probes.probeLinearCompletionSync())
        .thenReturn(ProbeResult.pass("completion-sync enabled"));
    when(probes.probeProjects())
        .thenReturn(ProbeResult.pass("All 1 active project(s) configured (1 total)"));
    when(probes.probeJiraAuth())
        .thenReturn(ProbeResult.pass("jira-real profile inactive; JIRA auth check not applicable"));
  }

  private DiagnosticsCheck findCheck(DiagnosticsReport report, String name) {
    return report.checks().stream()
        .filter(c -> name.equals(c.name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Check not found: " + name));
  }
}
