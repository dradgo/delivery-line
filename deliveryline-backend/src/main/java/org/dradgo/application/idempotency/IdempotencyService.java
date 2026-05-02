package org.dradgo.application.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.IdempotencyRecordEntity;
import org.dradgo.adapters.persistence.repository.IdempotencyRecordRepository;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

	private static final Duration STALE_RESERVATION_THRESHOLD = Duration.ofMinutes(10);
	private static final int MAX_RESERVATION_ATTEMPTS = 3;

	private final IdempotencyRecordRepository repository;
	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public IdempotencyService(IdempotencyRecordRepository repository, JdbcTemplate jdbcTemplate) {
		this.repository = repository;
		this.jdbcTemplate = jdbcTemplate;
		this.clock = Clock.systemUTC();
	}

	@Transactional
	public ReservationOutcome checkAndReserve(
		String key,
		String commandType,
		String actorIdentity,
		String fingerprint
	) {
		// Retry the upsert/resolve cycle when a racing winner rolls back between
		// our 0-affected insert and our pessimistic-read reread (the row vanishes
		// from under us). 3 attempts is enough for typical rollback timing
		// without risking pathological loops; the IllegalStateException at the
		// end is a safety belt that should never trigger under normal contention.
		for (int attempt = 0; attempt < MAX_RESERVATION_ATTEMPTS; attempt++) {
			int inserted = jdbcTemplate.update(
				"""
					insert into idempotency_records (
						public_id,
						key,
						command_type,
						actor_identity,
						command_fingerprint,
						status
					) values (?, ?, ?, ?, ?, ?)
					on conflict (key) do nothing
					""",
				PublicIdPrefixes.IDEMPOTENCY_RECORD.next(),
				key,
				commandType,
				actorIdentity,
				fingerprint,
				IdempotencyRecordStatus.RESERVED.value());
			if (inserted == 1) {
				return new ReservationOutcome(ReservationDecision.RESERVED, null);
			}
			Optional<ReservationOutcome> resolved = tryResolveExistingRecord(key, fingerprint);
			if (resolved.isPresent()) {
				return resolved.get();
			}
			// Winner rolled back during our resolve — retry the insert.
		}
		throw new IllegalStateException(
			"Failed to reserve idempotency key after " + MAX_RESERVATION_ATTEMPTS + " attempts: " + key);
	}

	@Transactional
	public void complete(String key, String resultRef, IdempotencyRecordStatus status) {
		IdempotencyRecordEntity record = repository.findWithLockByKey(key)
			.orElseThrow(() -> missingRecord(key));
		record.setResultRef(resultRef);
		record.setStatus(status);
		record.setCompletedAt(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
		repository.save(record);
	}

	private Optional<ReservationOutcome> tryResolveExistingRecord(String key, String fingerprint) {
		Optional<IdempotencyRecordEntity> maybe = repository.findWithLockByKey(key);
		if (maybe.isEmpty()) {
			// Winner rolled back during our resolve; signal the caller to retry the insert.
			return Optional.empty();
		}
		IdempotencyRecordEntity existing = maybe.get();
		if (!existing.getCommandFingerprint().equals(fingerprint)) {
			throw fingerprintConflict(key, existing.getCommandFingerprint(), fingerprint);
		}
		if (existing.getStatus() == IdempotencyRecordStatus.COMPLETED) {
			return Optional.of(new ReservationOutcome(ReservationDecision.REPLAY, existing.getResultRef()));
		}
		if (existing.getStatus() == IdempotencyRecordStatus.RESERVED && isStale(existing)) {
			throw staleReservation(key, existing.getCreatedAt());
		}
		throw activeReservationConflict(key, fingerprint);
	}

	private boolean isStale(IdempotencyRecordEntity existing) {
		return repository.isReservationStale(existing.getKey(), STALE_RESERVATION_THRESHOLD.toMinutes())
			.orElse(false);
	}

	private DomainException fingerprintConflict(String key, String existingFingerprint, String submittedFingerprint) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("idempotencyKey", key);
		details.put("existingFingerprint", existingFingerprint);
		details.put("submittedFingerprint", submittedFingerprint);
		return new DomainException(
			DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
			"Idempotency key conflict for key " + key
				+ " (existing=" + abbreviate(existingFingerprint)
				+ ", submitted=" + abbreviate(submittedFingerprint) + ")",
			details);
	}

	private DomainException staleReservation(String key, OffsetDateTime createdAt) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("idempotencyKey", key);
		details.put("createdAt", createdAt);
		details.put("thresholdMinutes", STALE_RESERVATION_THRESHOLD.toMinutes());
		return new DomainException(
			DomainErrorCode.STALE_IDEMPOTENCY_RESERVATION,
			"Stale idempotency reservation detected for key " + key,
			details);
	}

	private DomainException activeReservationConflict(String key, String fingerprint) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("idempotencyKey", key);
		details.put("submittedFingerprint", fingerprint);
		return new DomainException(
			DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
			"Idempotency key is already reserved for an in-flight command: " + key,
			details);
	}

	private IllegalStateException missingRecord(String key) {
		return new IllegalStateException("Idempotency record disappeared for key " + key);
	}

	private String abbreviate(String fingerprint) {
		return fingerprint.length() <= 16 ? fingerprint : fingerprint.substring(0, 16);
	}

	public enum ReservationDecision {
		RESERVED,
		REPLAY
	}

	public record ReservationOutcome(ReservationDecision decision, String resultRef) {
	}
}
