package org.dradgo.application.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyServiceContractTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private IdempotencyService service;

	@AfterEach
	void cleanDatabase() {
		jdbcTemplate.update("delete from idempotency_records");
	}

	@Test
	void firstReservationCanBeCompletedAndThenReplayed() {
		IdempotencyService.ReservationOutcome first = service.checkAndReserve(
			"idem-service-1234567890",
			"SubmitWorkflowCommand",
			"alex",
			"fingerprint-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

		assertEquals(IdempotencyService.ReservationDecision.RESERVED, first.decision());
		assertNull(first.resultRef());

		service.complete(
			"idem-service-1234567890",
			"run_submit1234",
			IdempotencyRecordStatus.COMPLETED);

		IdempotencyService.ReservationOutcome replay = service.checkAndReserve(
			"idem-service-1234567890",
			"SubmitWorkflowCommand",
			"alex",
			"fingerprint-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

		assertEquals(IdempotencyService.ReservationDecision.REPLAY, replay.decision());
		assertEquals("run_submit1234", replay.resultRef());

		assertEquals(
			"completed",
			jdbcTemplate.queryForObject(
				"select status from idempotency_records where key = ?",
				String.class,
				"idem-service-1234567890"));
		assertNotNull(
			jdbcTemplate.queryForObject(
				"select completed_at from idempotency_records where key = ?",
				OffsetDateTime.class,
				"idem-service-1234567890"));
	}

	@Test
	void differentFingerprintForTheSameKeyRaisesGovernedConflict() {
		service.checkAndReserve(
			"idem-service-abcdef123456",
			"SubmitWorkflowCommand",
			"alex",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		service.complete(
			"idem-service-abcdef123456",
			"run_submit1234",
			IdempotencyRecordStatus.COMPLETED);

		DomainException error = assertThrows(
			DomainException.class,
			() -> service.checkAndReserve(
				"idem-service-abcdef123456",
				"SubmitWorkflowCommand",
				"alex",
				"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));

		assertEquals(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, error.errorCode());
		assertEquals(
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			error.details().get("existingFingerprint"));
		assertEquals(
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			error.details().get("submittedFingerprint"));
	}

	@Test
	void staleReservationsFailClosedBeforeReexecution() {
		String key = "idem-stale-1234567890";
		String fingerprint = "c".repeat(64);

		// Reserve via the production write path — DB default sets created_at = now()
		IdempotencyService.ReservationOutcome reservation = service.checkAndReserve(
			key, "SubmitWorkflowCommand", "alex", fingerprint);
		assertEquals(IdempotencyService.ReservationDecision.RESERVED, reservation.decision());

		// Backdate the row to 11 minutes ago to simulate a crashed-mid-mutation
		// reservation that exceeded STALE_RESERVATION_THRESHOLD. Raw SQL is the
		// only path here because created_at is mapped @Column(updatable = false).
		jdbcTemplate.update(
			"update idempotency_records set created_at = ? where key = ?",
			OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11),
			key);

		DomainException error = assertThrows(
			DomainException.class,
			() -> service.checkAndReserve(
				key, "SubmitWorkflowCommand", "alex", fingerprint));

		assertEquals(DomainErrorCode.STALE_IDEMPOTENCY_RESERVATION, error.errorCode());
		assertEquals(key, error.details().get("idempotencyKey"));
	}
}
