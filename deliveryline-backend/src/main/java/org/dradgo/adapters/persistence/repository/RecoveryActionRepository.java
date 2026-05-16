package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.RecoveryActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryActionRepository extends JpaRepository<RecoveryActionEntity, Long> {

	Optional<RecoveryActionEntity> findByPublicId(String publicId);

	Optional<RecoveryActionEntity> findByIdempotencyKey(String idempotencyKey);
}
