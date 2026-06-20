package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.ProjectCredentialEntity;
import org.dradgo.domain.project.ProjectCredential;
import org.springframework.stereotype.Component;

/**
 * Story 3c-5 — entity &rarr; domain translation for {@code project_credentials}. Explicit (no
 * MapStruct), matching the sibling {@code IntegrationLinkEntityMapper}. The {@code connector_role}
 * raw text is converted to {@code ConnectorRole} by the entity getter (fail-fast registry parsing);
 * the {@code ProjectCredential} record defensively clones the {@code ciphertext} on construction.
 */
@Component
public class ProjectCredentialEntityMapper {

  public ProjectCredential toDomain(ProjectCredentialEntity entity) {
    return new ProjectCredential(
        entity.getPublicId(),
        entity.getProjectId(),
        entity.getConnectorRole(),
        entity.getCiphertext(),
        entity.getKeyId(),
        entity.getAlgo(),
        entity.getCreatedAt(),
        entity.getArchivedAt());
  }
}
