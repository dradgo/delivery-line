package org.dradgo.application.project;

import java.util.Optional;
import org.dradgo.domain.project.Project;

/**
 * Story 3c-6 — the first {@code Project} read/write port. The application owns this port; the JPA
 * {@code ProjectPersistenceAdapter} in {@code adapters.persistence} implements it (the {@code
 * application-cannot-import-adapters} rule — the seeder + resolver reach the DB only through this
 * seam).
 *
 * <p>Deliberately minimal: it serves the {@link DefaultProjectSeeder} (seed + backfill) and the
 * {@link ProjectRuntimeConfigResolver} (run&rarr;Project resolution) only. The full CRUD/disable
 * surface for the project REST API is story 3c-8 and is intentionally NOT modelled here.
 */
public interface ProjectStore {

  /** The active project carrying the given slug, or empty when none exists. */
  Optional<Project> findBySlug(String slug);

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
