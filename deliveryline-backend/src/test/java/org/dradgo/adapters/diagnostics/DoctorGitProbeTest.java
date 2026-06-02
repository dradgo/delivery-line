package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Story 3.9 AC15 — doctor git probes. Uses the {@code (Environment, ProcessLauncher,
 * WorkflowProperties)} test seam so the {@code git --version} call can be simulated present/missing
 * without depending on the host git, and the bot identity can be configured/unconfigured.
 */
class DoctorGitProbeTest {

  private static final WorkflowProperties CONFIGURED_BOT =
      new WorkflowProperties(
          new WorkflowProperties.Bot("DeliveryLine Bot", "bot@dradgo.org"), Map.of());
  private static final WorkflowProperties UNCONFIGURED_BOT = WorkflowProperties.defaults();

  private static Environment githubRealActive() {
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"github-real"});
    return env;
  }

  private static Environment githubRealInactive() {
    Environment env = mock(Environment.class);
    when(env.getActiveProfiles()).thenReturn(new String[] {"local"});
    return env;
  }

  // ---- probeGitAvailability ----

  @Test
  void gitAvailabilityIsNotApplicableWhenGithubRealInactive() {
    ProcessLauncher exploding =
        builder -> {
          throw new AssertionError("must not launch a process when github-real is inactive");
        };
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(githubRealInactive(), exploding, UNCONFIGURED_BOT);

    ProbeResult result = adapter.probeGitAvailability();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.errorCode()).isNull();
    assertThat(result.details()).containsEntry("githubRealProfile", "inactive");
  }

  @Test
  void gitAvailabilityPassesWhenGitVersionExitsZero() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(
            githubRealActive(), builder -> fakeProcess(0, "git version 2.45.0"), CONFIGURED_BOT);

    ProbeResult result = adapter.probeGitAvailability();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void gitAvailabilityFailsWhenBinaryMissing() {
    ProcessLauncher missing =
        builder -> {
          throw new IOException("CreateProcess error=2, The system cannot find the file specified");
        };
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(githubRealActive(), missing, CONFIGURED_BOT);

    ProbeResult result = adapter.probeGitAvailability();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_GIT_MISSING.value());
    assertThat(result.details()).containsEntry("gitProbe", "binary-missing");
  }

  @Test
  void gitAvailabilityFailsOnNonZeroExit() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(githubRealActive(), builder -> fakeProcess(127, ""), CONFIGURED_BOT);

    ProbeResult result = adapter.probeGitAvailability();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_GIT_MISSING.value());
  }

  // ---- probeGitBotIdentity ----

  @Test
  void gitBotIdentityIsNotApplicableWhenGithubRealInactive() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(githubRealInactive(), ProcessBuilder::start, UNCONFIGURED_BOT);

    ProbeResult result = adapter.probeGitBotIdentity();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void gitBotIdentityPassesWhenExplicitlyConfigured() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(githubRealActive(), ProcessBuilder::start, CONFIGURED_BOT);

    ProbeResult result = adapter.probeGitBotIdentity();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.errorCode()).isNull();
    assertThat(result.details())
        .containsEntry("botName", "present")
        .containsEntry("botEmail", "present");
  }

  @Test
  void gitBotIdentityWarnsWhenUnconfigured() {
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(githubRealActive(), ProcessBuilder::start, UNCONFIGURED_BOT);

    ProbeResult result = adapter.probeGitBotIdentity();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.WARN);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED.value());
    assertThat(result.details()).containsEntry("botName", "unset");
  }

  /** A minimal already-exited {@link Process} stand-in for the doctor probe's waitFor/exitValue. */
  private static Process fakeProcess(int exitCode, String stdout) {
    return new Process() {
      @Override
      public java.io.OutputStream getOutputStream() {
        return java.io.OutputStream.nullOutputStream();
      }

      @Override
      public java.io.InputStream getInputStream() {
        return new java.io.ByteArrayInputStream(
            stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }

      @Override
      public java.io.InputStream getErrorStream() {
        return java.io.InputStream.nullInputStream();
      }

      @Override
      public int waitFor() {
        return exitCode;
      }

      @Override
      public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
        return true;
      }

      @Override
      public int exitValue() {
        return exitCode;
      }

      @Override
      public void destroy() {}

      @Override
      public Process destroyForcibly() {
        return this;
      }

      @Override
      public boolean isAlive() {
        return false;
      }
    };
  }
}
