package org.dradgo.application.project;

import java.util.List;
import java.util.Optional;
import org.dradgo.domain.project.Project;

/**
 * Story 3c-6 — the first {@code Project} read/write port. The application owns this port; the JPA
 * {@code ProjectPersistenceAdapter} in {@code adapters.persistence} implements it (the {@code
 * application-cannot-import-adapters} rule — the seeder + resolver reach the DB only through this
 * seam).
 *
 * <p>Originally minimal (seed + resolve only); story 3c-8 adds the CRUD/disable surface the project
 * REST API needs ({@link #findAll}, {@link #update}). {@code findAll} is a co-edit with story 3c-10
 * (doctor's project listing) — whichever story merges first introduces the identical signature.
 */
public interface ProjectStore {

  /** The active project carrying the given slug, or empty when none exists. */
  Optional<Project> findBySlug(String slug);

  /**
   * Story 3c-8 (AC1) — every project, ordered by {@code created_at} ascending for a stable list
   * endpoint. Includes disabled projects (the REST list shows them so an operator can re-enable).
   * Co-edit with story 3c-10 (its first listing consumer) — identical signature.
   */
  List<Project> findAll();

  /**
   * Story 3c-8 (AC1/AC4) — persist a mutated project (name / repository url / kinds / OpenSpec flag
   * / status), keyed on {@code publicId}; returns the persisted aggregate. The immutable {@code
   * createdAt} is preserved. A slug-uniqueness violation surfaces as a typed {@code
   * DomainException(PROJECT_SLUG_CONFLICT)}; an unknown {@code publicId} surfaces as {@code
   * DomainException(PROJECT_NOT_FOUND)}.
   */
  Project update(Project project);

  /** The project carrying the given {@code prj_} public id, or empty when none exists. */
  Optional<Project> findByPublicId(String publicId);

  /**
   * Insert a new project row. Implementations let the DB generate {@code id}/{@code created_at} and
   * return the persisted aggregate (with the DB-assigned {@code createdAt}). A slug/public-id
   * uniqueness violation surfaces as a {@code DataIntegrityViolationException} for the caller's
   * concurrent-startup backstop (AC5).
   */
  Project insert(Project project);

  /**
   * Backfill every {@code workflow_runs} row with a null {@code project_id} to the given default
   * project public id (single {@code @Modifying} UPDATE). Naturally idempotent ({@code where
   * project_id is null}); returns the number of rows updated.
   */
  int backfillNullProjectIds(String defaultProjectPublicId);

  /**
   * The {@code project_id} bound to the given run, or empty when the run does not exist OR carries
   * a null {@code project_id} (both collapse to "no binding" so the resolver falls back to the
   * default project). A focused read — deliberately not widening {@code WorkflowRunSnapshot}.
   */
  Optional<String> findProjectIdForRun(String runPublicId);
}
