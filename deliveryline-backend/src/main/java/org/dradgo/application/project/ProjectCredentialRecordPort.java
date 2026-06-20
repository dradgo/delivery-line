package org.dradgo.application.project;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.domain.project.ProjectCredential;
import org.dradgo.domain.registry.ConnectorRole;

/**
 * Story 3c-5 (AC1/AC6/AC9) — application-owned SPI port for {@code project_credentials}
 * persistence. It exists so {@code ProjectCredentialService} (in {@code application.project}) can
 * write/read credential rows without importing {@code adapters.persistence} (the {@code
 * application-cannot-import-adapters} ArchUnit boundary). The implementation lives in {@code
 * adapters.persistence}.
 *
 * <p>Implementations MUST be secret-hostile: never log the {@code ciphertext}, {@code keyId}, or
 * any decrypted material. A foreign-key violation against {@code fk_project_credentials_projects}
 * (an unknown {@code project_id}) maps to {@code DomainException(PROJECT_NOT_FOUND)}; a
 * partial-unique collision on {@code uq_project_credentials_project_role} (a concurrent active
 * write) maps to a conflict.
 */
public interface ProjectCredentialRecordPort {

  /**
   * The single active (non-archived) credential for {@code (projectPublicId, role)}, or {@link
   * Optional#empty()} when none is configured.
   */
  Optional<ProjectCredential> findActive(String projectPublicId, ConnectorRole role);

  /**
   * Archive (set {@code archived_at}) whichever credential is currently active for {@code
   * (projectPublicId, role)}, freeing the partial-unique slot for a rotated replacement. Returns
   * the number of rows archived (0 when none was active — a first-time set).
   */
  int archiveActive(String projectPublicId, ConnectorRole role, OffsetDateTime archivedAt);

  /** Insert a fresh credential row, returning the persisted aggregate. */
  ProjectCredential insert(ProjectCredential newRow);
}
