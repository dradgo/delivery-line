package org.dradgo.domain.project;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;

/**
 * Domain aggregate for a configured project, mirroring the V17 {@code projects} columns. This is a
 * pure domain value object (config aggregate), not a JPA entity — the persistence adapter/entity +
 * {@code PersistedRegistryValues} field-context wrappers land when rows are first read/written
 * through the application (3c-6 default-project seed; 3c-8 CRUD).
 *
 * <p>Invariants follow the {@code TicketRef}/{@code RepositoryRef} value-object precedent: blank /
 * null guards throw {@link IllegalArgumentException} / {@link NullPointerException} synchronously
 * to the caller. The {@code publicId} guard routes through {@link PublicIdPrefixes#require} (the
 * established prefix-validation path, which throws a typed {@code
 * DomainException(INVALID_ID_PREFIX)}). User-facing slug/name validation → typed errors happens at
 * the REST boundary in 3c-8, not here.
 */
public record Project(
    String publicId,
    String name,
    String slug,
    ProjectStatus status,
    String repositoryUrl, // nullable
    ConnectorKind ticketSourceKind,
    ConnectorKind repoHostKind,
    boolean openspecEnabled,
    OffsetDateTime createdAt,
    OffsetDateTime archivedAt) { // nullable

  public Project {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.PROJECT);
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Project name must be non-blank");
    }
    if (slug == null || slug.isBlank()) {
      throw new IllegalArgumentException("Project slug must be non-blank");
    }
    Objects.requireNonNull(status, "Project status must not be null");
    Objects.requireNonNull(ticketSourceKind, "Project ticketSourceKind must not be null");
    Objects.requireNonNull(repoHostKind, "Project repoHostKind must not be null");
    Objects.requireNonNull(createdAt, "Project createdAt must not be null");
  }
}
