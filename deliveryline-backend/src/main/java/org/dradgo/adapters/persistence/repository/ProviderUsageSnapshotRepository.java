package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ProviderUsageSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the V24 {@code provider_usage_snapshots} table (story 3d-7). The read
 * lookup enforces {@code archived_at IS NULL} and returns the most recent row per run so the
 * surface always shows the latest captured snapshot.
 */
public interface ProviderUsageSnapshotRepository
    extends JpaRepository<ProviderUsageSnapshotEntity, Long> {

  Optional<ProviderUsageSnapshotEntity>
      findFirstByWorkflowRunIdAndArchivedAtIsNullOrderByCreatedAtDescIdDesc(String workflowRunId);

  long countByArchivedAtIsNullAndSignalState(String signalState);
}
