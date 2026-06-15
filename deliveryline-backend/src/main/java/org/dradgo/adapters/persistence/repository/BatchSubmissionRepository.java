package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.BatchSubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the V15 {@code batch_submissions} table (story 3.18). The lookup
 * enforces {@code archived_at IS NULL} so tombstoned batches never reach the application layer.
 */
public interface BatchSubmissionRepository extends JpaRepository<BatchSubmissionEntity, Long> {

  Optional<BatchSubmissionEntity> findByPublicIdAndArchivedAtIsNull(String publicId);
}
