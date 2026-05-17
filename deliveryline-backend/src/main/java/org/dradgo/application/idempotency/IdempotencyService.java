package org.dradgo.application.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.idempotency.spi.IdempotencyRecordPort;
import org.dradgo.application.idempotency.spi.IdempotencyRecordSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

  private static final Duration STALE_RESERVATION_THRESHOLD = Duration.ofMinutes(10);
  private static final int MAX_RESERVATION_ATTEMPTS = 200;
  private static final Duration RESERVATION_RETRY_DELAY = Duration.ofMillis(10);

  private final IdempotencyRecordPort idempotencyRecordPort;
  private final Clock clock;

  public IdempotencyService(IdempotencyRecordPort idempotencyRecordPort) {
    this.idempotencyRecordPort = idempotencyRecordPort;
    this.clock = Clock.systemUTC();
  }

  @Transactional
  public ReservationOutcome checkAndReserve(
      String key, String commandType, String actorIdentity, String fingerprint) {
    // Retry when a racing winner has already committed the reservation but has not yet
    // completed the command. Once the winner flips the row to COMPLETED or FAILED, we can
    // deterministically replay or reject instead of surfacing a transient in-flight conflict.
    for (int attempt = 0; attempt < MAX_RESERVATION_ATTEMPTS; attempt++) {
      boolean inserted =
          idempotencyRecordPort.tryReserve(
              PublicIdPrefixes.IDEMPOTENCY_RECORD.next(),
              key,
              commandType,
              actorIdentity,
              fingerprint,
              IdempotencyRecordStatus.RESERVED);
      if (inserted) {
        return new ReservationOutcome(ReservationDecision.RESERVED, null);
      }
      Optional<ReservationOutcome> resolved = tryResolveExistingRecord(key, fingerprint);
      if (resolved.isPresent()) {
        return resolved.get();
      }
      pauseBeforeRetry();
    }
    throw reservationExhausted(key);
  }

  @Transactional
  public void complete(String key, String resultRef, IdempotencyRecordStatus status) {
    idempotencyRecordPort.markCompleted(
        key, resultRef, status, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
  }

  private Optional<ReservationOutcome> tryResolveExistingRecord(String key, String fingerprint) {
    Optional<IdempotencyRecordSnapshot> maybe = idempotencyRecordPort.findWithLockByKey(key);
    if (maybe.isEmpty()) {
      // Winner rolled back after we lost the reserve race; retry the insert path.
      return Optional.empty();
    }
    IdempotencyRecordSnapshot existing = maybe.get();
    if (!existing.commandFingerprint().equals(fingerprint)) {
      throw fingerprintConflict(key, existing.commandFingerprint(), fingerprint);
    }
    if (existing.status() == IdempotencyRecordStatus.COMPLETED) {
      return Optional.of(new ReservationOutcome(ReservationDecision.REPLAY, existing.resultRef()));
    }
    if (existing.status() == IdempotencyRecordStatus.FAILED) {
      throw priorAttemptFailed(key);
    }
    if (existing.status() == IdempotencyRecordStatus.RESERVED && isStale(existing)) {
      throw staleReservation(key, existing.createdAt());
    }
    if (existing.status() == IdempotencyRecordStatus.RESERVED) {
      return Optional.empty();
    }
    throw activeReservationConflict(key, fingerprint);
  }

  private boolean isStale(IdempotencyRecordSnapshot existing) {
    return idempotencyRecordPort.isReservationStale(existing.key(), STALE_RESERVATION_THRESHOLD);
  }

  private DomainException reservationExhausted(String key) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", key);
    details.put("maxAttempts", MAX_RESERVATION_ATTEMPTS);
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_RESERVATION_EXHAUSTED,
        "Failed to reserve idempotency key after " + MAX_RESERVATION_ATTEMPTS + " attempts: " + key,
        details);
  }

  private void pauseBeforeRetry() {
    try {
      Thread.sleep(RESERVATION_RETRY_DELAY);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Interrupted while waiting for idempotency reservation resolution",
          Map.of("retryDelayMs", RESERVATION_RETRY_DELAY.toMillis()));
    }
  }

  private DomainException fingerprintConflict(
      String key, String existingFingerprint, String submittedFingerprint) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", key);
    details.put("existingFingerprint", existingFingerprint);
    details.put("submittedFingerprint", submittedFingerprint);
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        "Idempotency key conflict for key "
            + key
            + " (existing="
            + abbreviate(existingFingerprint)
            + ", submitted="
            + abbreviate(submittedFingerprint)
            + ")",
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

  private DomainException priorAttemptFailed(String key) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", key);
    details.put("reason", "prior_attempt_failed_terminally");
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        "Prior command attempt for this idempotency key failed terminally; submit with a fresh key",
        details);
  }

  private String abbreviate(String fingerprint) {
    return fingerprint.length() <= 16 ? fingerprint : fingerprint.substring(0, 16);
  }

  public enum ReservationDecision {
    RESERVED,
    REPLAY
  }

  public record ReservationOutcome(ReservationDecision decision, String resultRef) {}
}
