package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.dradgo.adapters.persistence.entity.IdempotencyRecordEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into idempotency_records (
				public_id,
				key,
				command_type,
				actor_identity,
				command_fingerprint,
				status
			) values (
				:publicId,
				:key,
				:commandType,
				:actorIdentity,
				:commandFingerprint,
				:status
			)
			on conflict (key) do nothing
			""", nativeQuery = true)
	int tryReserve(
		@Param("publicId") String publicId,
		@Param("key") String key,
		@Param("commandType") String commandType,
		@Param("actorIdentity") String actorIdentity,
		@Param("commandFingerprint") String commandFingerprint,
		@Param("status") String status
	);

	@Lock(LockModeType.PESSIMISTIC_READ)
	Optional<IdempotencyRecordEntity> findWithLockByKey(String key);

	// Compute staleness in SQL so the database clock is the single source of
	// truth — comparing DB-set `created_at` against JVM `Clock.systemUTC()`
	// gives wrong answers under any cross-process clock skew.
	@Query(value = """
			select extract(epoch from (now() - ir.created_at)) >= ?2
			from idempotency_records ir where ir.key = ?1
			""", nativeQuery = true)
	Optional<Boolean> isReservationStale(String key, long thresholdSeconds);
}
