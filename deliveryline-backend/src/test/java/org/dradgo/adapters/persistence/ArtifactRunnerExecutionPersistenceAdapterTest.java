package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class ArtifactRunnerExecutionPersistenceAdapterTest {

	private static final String STATUS_QUERY = "select status from runner_executions where public_id = ?";

	@Test
	void timedOutRunnerExecutionsAreReportedAsTimedOut() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		ArtifactRunnerExecutionPersistenceAdapter adapter = new ArtifactRunnerExecutionPersistenceAdapter(jdbcTemplate);

		when(jdbcTemplate.queryForObject(STATUS_QUERY, String.class, "rex_timeout1234"))
			.thenReturn(RunnerExecutionStatus.TIMED_OUT.value());
		when(jdbcTemplate.queryForObject(STATUS_QUERY, String.class, "rex_running1234"))
			.thenReturn(RunnerExecutionStatus.RUNNING.value());

		assertTrue(adapter.isTimedOut("rex_timeout1234"));
		assertFalse(adapter.isTimedOut("rex_running1234"));
	}

	@Test
	void missingRunnerExecutionRowRaisesTypedNotFoundInsteadOfMaskingAsNotTimedOut() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		ArtifactRunnerExecutionPersistenceAdapter adapter = new ArtifactRunnerExecutionPersistenceAdapter(jdbcTemplate);

		when(jdbcTemplate.queryForObject(STATUS_QUERY, String.class, "rex_missing1234"))
			.thenThrow(new EmptyResultDataAccessException(1));

		DomainException error = assertThrows(DomainException.class, () -> adapter.isTimedOut("rex_missing1234"));

		assertEquals(DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND, error.errorCode());
		assertEquals("rex_missing1234", error.details().get("runnerExecutionId"));
	}

	@Test
	void unrecognizedRunnerExecutionStatusIsRoutedThroughTheRegistryParserInsteadOfASilentEqualsCheck() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		ArtifactRunnerExecutionPersistenceAdapter adapter = new ArtifactRunnerExecutionPersistenceAdapter(jdbcTemplate);

		when(jdbcTemplate.queryForObject(STATUS_QUERY, String.class, "rex_garbage1234"))
			.thenReturn("not_a_real_status");

		DomainException error = assertThrows(DomainException.class, () -> adapter.isTimedOut("rex_garbage1234"));

		assertEquals(DomainErrorCode.UNKNOWN_REGISTRY_VALUE, error.errorCode());
	}
}
