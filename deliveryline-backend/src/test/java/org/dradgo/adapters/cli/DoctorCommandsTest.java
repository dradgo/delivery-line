package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.dradgo.application.diagnostics.DiagnosticsCheck;
import org.dradgo.application.diagnostics.DiagnosticsReport;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.DoctorRunRequest;
import org.dradgo.application.diagnostics.DoctorService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class DoctorCommandsTest {

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final RedactionPolicyService redaction = new RedactionPolicyService(new DataClassificationService());
	private final DoctorReportRenderer renderer = new DoctorReportRenderer(mapper, redaction);
	private final DoctorService doctorService = mock(DoctorService.class);
	private final DoctorCommands commands = new DoctorCommands(
		doctorService,
		renderer,
		() -> "01964c38-1c45-7000-8000-000000000099");

	private PrintStream originalStdout;
	private ByteArrayOutputStream capturedStdout;

	@BeforeEach
	void redirectStdout() {
		originalStdout = System.out;
		capturedStdout = new ByteArrayOutputStream();
		System.setOut(new PrintStream(capturedStdout));
	}

	@AfterEach
	void restoreStdout() {
		System.setOut(originalStdout);
		MDC.clear();
	}

	@Test
	void textFormatReturnsRenderedReport() {
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());

		String output = commands.doctor(null, null, null, "corr-1");

		assertThat(output).contains("java-version: PASS Java 21");
		assertThat(output).contains("overall: PASS");
	}

	@Test
	void jsonFormatReturnsParseableJson() throws Exception {
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());

		String output = commands.doctor("json", null, null, null);

		assertThat(mapper.readTree(output).get("schemaVersion").asInt()).isEqualTo(1);
	}

	@Test
	void failingReportPrintsReportToStdoutBeforeThrowing() {
		DiagnosticsReport report = failingReport();
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(report);

		assertThatThrownBy(() -> commands.doctor("text", null, null, "corr-fail"))
			.isInstanceOf(DomainException.class)
			.satisfies(t -> {
				DomainException de = (DomainException) t;
				assertThat(de.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE);
				assertThat(de.details()).containsKey("failedChecks");
			});

		String stdout = capturedStdout.toString();
		assertThat(stdout).contains("postgres-connectivity: FAIL");
		assertThat(stdout).contains("overall: FAIL");
	}

	@Test
	void onlyAndExcludeAreParsedAsCsv() {
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());

		commands.doctor(null, "java-version,postgres-connectivity", null, null);

		org.mockito.ArgumentCaptor<DoctorRunRequest> captor =
			org.mockito.ArgumentCaptor.forClass(DoctorRunRequest.class);
		org.mockito.Mockito.verify(doctorService).runDiagnostics(captor.capture());
		assertThat(captor.getValue().only()).containsExactlyInAnyOrder("java-version", "postgres-connectivity");
		assertThat(captor.getValue().exclude()).isEmpty();
	}

	@Test
	void unsupportedFormatRaisesInvalidCommandPayload() {
		assertThatThrownBy(() -> commands.doctor("yaml", null, null, null))
			.isInstanceOf(DomainException.class)
			.satisfies(t -> {
				DomainException de = (DomainException) t;
				assertThat(de.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
			});
	}

	@Test
	void correlationIdGeneratedWhenOptionOmitted() {
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());

		commands.doctor(null, null, null, null);

		org.mockito.ArgumentCaptor<DoctorRunRequest> captor =
			org.mockito.ArgumentCaptor.forClass(DoctorRunRequest.class);
		org.mockito.Mockito.verify(doctorService).runDiagnostics(captor.capture());
		assertThat(captor.getValue().correlationId()).isEqualTo("01964c38-1c45-7000-8000-000000000099");
	}

	@Test
	void mdcCleanedAfterRun() {
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());

		commands.doctor(null, null, null, "corr-mdc");
		assertThat(MDC.get("correlationId")).isNull();
	}

	@Test
	void correlationIdSanitizedFromInjectionAttempts() {
		when(doctorService.runDiagnostics(any(DoctorRunRequest.class))).thenReturn(passingReport());

		commands.doctor(null, null, null, "corr\ninjected");

		org.mockito.ArgumentCaptor<DoctorRunRequest> captor =
			org.mockito.ArgumentCaptor.forClass(DoctorRunRequest.class);
		org.mockito.Mockito.verify(doctorService).runDiagnostics(captor.capture());
		assertThat(captor.getValue().correlationId()).isEqualTo("corr_injected");
	}

	private DiagnosticsReport passingReport() {
		return new DiagnosticsReport(1, OffsetDateTime.parse("2026-05-14T10:00:00Z"),
			DiagnosticsStatus.PASS,
			List.of(new DiagnosticsCheck("java-version", DiagnosticsStatus.PASS, "Java 21", null, null, Map.of())));
	}

	private DiagnosticsReport failingReport() {
		return new DiagnosticsReport(1, OffsetDateTime.parse("2026-05-14T10:00:00Z"),
			DiagnosticsStatus.FAIL,
			List.of(
				new DiagnosticsCheck("java-version", DiagnosticsStatus.PASS, "Java 21", null, null, Map.of()),
				new DiagnosticsCheck("postgres-connectivity", DiagnosticsStatus.FAIL,
					"Postgres unreachable",
					"Run docker compose up.", "DOCTOR_POSTGRES_UNREACHABLE", Map.of())));
	}
}
