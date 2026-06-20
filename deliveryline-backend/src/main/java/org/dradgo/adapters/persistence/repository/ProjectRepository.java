package org.dradgo.adapters.persistence.repository;

import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Story 3c-6 — Spring Data repository for the V17 {@code projects} table. Reads keyed on the two
 * unique columns ({@code slug} for the seeder's idempotency short-circuit, {@code public_id} for
 * the resolver). Writes go through {@link JpaRepository#saveAndFlush} in the adapter. The {@code
 * workflow_runs} backfill lives on {@code WorkflowRunRepository} (it mutates that entity).
 */
public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

  Optional<ProjectEntity> findBySlug(String slug);

  Optional<ProjectEntity> findByPublicId(String publicId);
}
