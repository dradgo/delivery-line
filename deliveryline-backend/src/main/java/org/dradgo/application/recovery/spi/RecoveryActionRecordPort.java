package org.dradgo.application.recovery.spi;

import java.util.Optional;

/**
 * SPI for the {@code recovery_actions} table (story 1.18 Task 2).
 *
 * <p>Lives in {@code application.recovery.spi} so {@code application.recovery.RecoveryService}
 * (story 1.18 Task 3 — landing in a follow-up slice) can persist recovery rows without touching
 * the persistence adapter directly. The implementation lives in
 * {@code adapters.persistence.RecoveryActionPersistenceAdapter}.
 *
 * <p>Story 1.18 only exercises {@code action_type = 'retry'}; Epic 4 will add resume / rerun /
 * reconcile / pause / takeover. The state machine for {@code result_status} is:
 *
 * <pre>
 *        insert (pending)
 *               │
 *               ├── markSucceeded ──► succeeded   (terminal)
 *               │
 *               └── markFailed    ──► failed      (terminal)
 * </pre>
 *
 * <p>{@code succeeded ↔ failed} transitions are NOT permitted — re-flipping a terminal row throws
 * {@code DomainException(INTERNAL_ERROR)} per the state-machine contract.
 */
public interface RecoveryActionRecordPort {

	/**
	 * Insert a new {@code recovery_actions} row. The adapter generates the {@code rcv_} public id,
	 * resolves the workflow_run / workflow_events FK references by public-id lookup, and translates
	 * a unique-violation on {@code uq_recovery_actions_idempotency_key} into
	 * {@code DomainException(IDEMPOTENCY_KEY_CONFLICT)}.
	 *
	 * @throws org.dradgo.domain.DomainException with {@code IDEMPOTENCY_KEY_CONFLICT} if the
	 *         idempotency_key collides with an existing row, or {@code INTERNAL_ERROR} if a
	 *         referenced workflow_run / workflow_event public id is missing.
	 */
	RecoveryActionSnapshot insert(RecoveryActionWriteCommand command);

	/**
	 * Look up an existing recovery action by idempotency_key. Returns {@code Optional.empty()} if
	 * no row matches — the recovery service uses this to detect replays before reserving the
	 * downstream broker key.
	 */
	Optional<RecoveryActionSnapshot> findByIdempotencyKey(String idempotencyKey);

	/**
	 * Flip {@code result_status} from {@code pending} to {@code succeeded}. Throws
	 * {@code DomainException(INTERNAL_ERROR)} if no matching row exists or if the current
	 * {@code result_status} is not {@code pending}.
	 */
	RecoveryActionSnapshot markSucceeded(String idempotencyKey);

	/**
	 * Flip {@code result_status} from {@code pending} to {@code failed}. Throws
	 * {@code DomainException(INTERNAL_ERROR)} if no matching row exists or if the current
	 * {@code result_status} is not {@code pending}.
	 */
	RecoveryActionSnapshot markFailed(String idempotencyKey);
}
