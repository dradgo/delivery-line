package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.diagnostics.DiagnosticsCheck;
import org.dradgo.application.diagnostics.DiagnosticsReport;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.DoctorRunRequest;
import org.dradgo.application.diagnostics.DoctorService;
import org.dradgo.application.diagnostics.spi.DoctorProbePort;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DoctorLoggingContractTest {

  private ListAppender<ILoggingEvent> commandAppender;
  private ListAppender<ILoggingEvent> serviceAppender;

  @BeforeEach
  void attachAppenders() {
    commandAppender = attachListAppender(DoctorCommands.class);
    serviceAppender = attachListAppender(DoctorService.class);
  }

  @AfterEach
  void detachAppenders() {
    detach(DoctorCommands.class, commandAppender);
    detach(DoctorService.class, serviceAppender);
  }

  @Test
  void doctorServiceEmitsSingleStartAndFinishInfoLogs() {
    DoctorProbePort probes = mock(DoctorProbePort.class);
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
    when(probes.probeGitHubAuth()).thenReturn(ProbeResult.pass("github-real inactive"));
    when(probes.probeGitAvailability()).thenReturn(ProbeResult.pass("git check not applicable"));
    when(probes.probeGitBotIdentity()).thenReturn(ProbeResult.pass("git identity not applicable"));
    when(probes.probeObservabilityMemory()).thenReturn(ProbeResult.skip("observability inactive"));
    when(probes.probeLinearCompletionSync())
        .thenReturn(ProbeResult.pass("completion-sync enabled"));
    when(probes.probeProjects()).thenReturn(ProbeResult.pass("projects configured"));
    DoctorService service =
        new DoctorService(
            probes,
            new RedactionPolicyService(new DataClassificationService()),
            Clock.fixed(Instant.parse("2026-05-14T10:00:00Z"), ZoneOffset.UTC));

    service.runDiagnostics(
        new DoctorRunRequest(java.util.Set.of(), java.util.Set.of(), "corr-service"));

    List<ILoggingEvent> infoEvents =
        serviceAppender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    assertThat(infoEvents).hasSize(2);
    assertThat(infoEvents.get(0).getFormattedMessage())
        .contains("doctor diagnostics started")
        .contains("correlationId=corr-service");
    assertThat(infoEvents.get(1).getFormattedMessage())
        .contains("doctor diagnostics finished")
        .contains("overallStatus=PASS")
        .contains("checksRun=18");
  }

  @Test
  void doctorCommandsEmitSingleCompletionLogOnSuccess() {
    DoctorService doctorService = mock(DoctorService.class);
    when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());
    DoctorCommands commands =
        new DoctorCommands(
            doctorService,
            new DoctorReportRenderer(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                new RedactionPolicyService(new DataClassificationService())),
            () -> "corr-success");

    commands.doctor("text", null, null, "corr-success");

    List<ILoggingEvent> infoEvents =
        commandAppender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    assertThat(infoEvents).hasSize(1);
    assertThat(infoEvents.get(0).getFormattedMessage())
        .contains("doctor command completed")
        .contains("correlationId=corr-success")
        .contains("outcome=success")
        .contains("checksRun=1");

    // Story 1.19 backfill: DoctorCommands.doctor stamps correlationId on MDC at entry. The
    // completion line is emitted before the finally-block cleanup, so the captured event
    // MUST carry the correlationId MDC key.
    assertThat(infoEvents.get(0).getMDCPropertyMap())
        .containsEntry("correlationId", "corr-success");
  }

  @Test
  void doctorCommandsEmitSingleCompletionLogOnFail() {
    DoctorService doctorService = mock(DoctorService.class);
    when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(failingReport());
    DoctorCommands commands =
        new DoctorCommands(
            doctorService,
            new DoctorReportRenderer(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                new RedactionPolicyService(new DataClassificationService())),
            () -> "corr-fail");

    assertThatThrownBy(() -> commands.doctor("text", null, null, "corr-fail"))
        .isInstanceOf(DomainException.class);

    List<ILoggingEvent> infoEvents =
        commandAppender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    assertThat(infoEvents).hasSize(1);
    assertThat(infoEvents.get(0).getFormattedMessage())
        .contains("doctor command completed")
        .contains("correlationId=corr-fail")
        .contains("outcome=failure:DOCTOR_POSTGRES_UNREACHABLE")
        .contains("checksRun=2");
  }

  private DiagnosticsReport passingReport() {
    return new DiagnosticsReport(
        1,
        OffsetDateTime.parse("2026-05-14T10:00:00Z"),
        DiagnosticsStatus.PASS,
        List.of(
            new DiagnosticsCheck(
                "java-version", DiagnosticsStatus.PASS, "Java 21", null, null, Map.of())));
  }

  private DiagnosticsReport failingReport() {
    return new DiagnosticsReport(
        1,
        OffsetDateTime.parse("2026-05-14T10:00:00Z"),
        DiagnosticsStatus.FAIL,
        List.of(
            new DiagnosticsCheck(
                "java-version", DiagnosticsStatus.PASS, "Java 21", null, null, Map.of()),
            new DiagnosticsCheck(
                "postgres-connectivity",
                DiagnosticsStatus.FAIL,
                "Postgres unreachable",
                "Run docker compose up.",
                "DOCTOR_POSTGRES_UNREACHABLE",
                Map.of())));
  }

  private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
    logger.detachAppender(appender);
  }
}
