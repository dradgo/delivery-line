package org.dradgo.application.idempotency.spi;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.domain.registry.IdempotencyRecordStatus;

public interface IdempotencyRecordPort {

  /**
   * Attempt to insert a new RESERVED idempotency record. Race-safe via a single insert with
   * conflict-ignore semantics.
   *
   * <p>Caller must be in an active transaction; implementations participate via REQUIRED
   * propagation.
   *
   * @return {@code true} when a row was inserted by this call; {@code false} when an existing row
   *     already held the key
   */
  boolean tryReserve(
      String publicId,
      String key,
      String commandType,
      String actorIdentity,
      String commandFingerprint,
      IdempotencyRecordStatus status);

  /**
   * Pessimistic read of a record by its idempotency key.
   *
   * @return present snapshot when a row exists; empty Optional otherwise
   */
  Optional<IdempotencyRecordSnapshot> findWithLockByKey(String key);

  /**
   * Whether the existing reservation for {@code key} is older than {@code threshold}.
   *
   * @return {@code false} when no reservation exists or it is fresher than the threshold
   */
  boolean isReservationStale(String key, Duration threshold);

  /**
   * Transition a RESERVED record to its terminal status (COMPLETED or FAILED) with the resolved
   * result reference.
   */
  void markCompleted(
      String key, String resultRef, IdempotencyRecordStatus status, OffsetDateTime completedAt);
}
