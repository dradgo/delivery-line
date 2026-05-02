package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.dradgo.adapters.persistence.entity.IdempotencyRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

	@Lock(LockModeType.PESSIMISTIC_READ)
	Optional<IdempotencyRecordEntity> findWithLockByKey(String key);

	// Compute staleness in SQL so the database clock is the single source of
	// truth — comparing DB-set `created_at` against JVM `Clock.systemUTC()`
	// gives wrong answers under any cross-process clock skew.
	@Query(value = """
			select extract(epoch from (now() - ir.created_at)) >= ?2 * 60
			from idempotency_records ir where ir.key = ?1
			""", nativeQuery = true)
	Optional<Boolean> isReservationStale(String key, long thresholdMinutes);
}
