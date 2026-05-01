package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.shell.core.command.ExitStatus;

class WorkflowCliExitStatusExceptionMapperTest {

	private final WorkflowCliExitStatusExceptionMapper mapper = new WorkflowCliExitStatusExceptionMapper();

	@Test
	void clientLikeDomainErrorsMapToThe100SeriesBand() {
		ExitStatus status = mapper.apply(new DomainException(
			DomainErrorCode.INVALID_COMMAND_PAYLOAD,
			"Invalid command payload"));

		assertEquals(101, status.code());
		assertEquals("[INVALID_COMMAND_PAYLOAD] Invalid command payload", status.description());
	}

	@Test
	void concurrencyAndIdempotencyErrorsMapToThe200SeriesBand() {
		ExitStatus status = mapper.apply(new DomainException(
			DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT,
			"Workflow changed concurrently"));

		assertEquals(201, status.code());
		assertEquals("[CONCURRENT_TRANSITION_CONFLICT] Workflow changed concurrently", status.description());
	}

	@Test
	void approvalVersionMismatchMapsToThe200SeriesBand() {
		ExitStatus status = mapper.apply(new DomainException(
			DomainErrorCode.APPROVAL_VERSION_MISMATCH,
			"Artifact version is stale",
			Map.of("expectedArtifactVersion", 6, "currentArtifactVersion", 7)));

		assertEquals(201, status.code());
		assertEquals("[APPROVAL_VERSION_MISMATCH] Artifact version is stale", status.description());
	}

	@Test
	void runnerAndIntegrationErrorsMapToThe300SeriesBand() {
		ExitStatus status = mapper.apply(new DomainException(
			DomainErrorCode.RUNNER_TIMEOUT,
			"Runner timed out"));

		assertEquals(301, status.code());
		assertEquals("[RUNNER_TIMEOUT] Runner timed out", status.description());
	}

	@Test
	void infrastructureAndUnknownFailuresMapToThe400SeriesBand() {
		ExitStatus status = mapper.apply(new DomainException(
			DomainErrorCode.DOCTOR_POSTGRES_UNREACHABLE,
			"Postgres unreachable",
			Map.of("host", "localhost")));

		assertEquals(401, status.code());
		assertEquals("[DOCTOR_POSTGRES_UNREACHABLE] Postgres unreachable", status.description());
	}
}
