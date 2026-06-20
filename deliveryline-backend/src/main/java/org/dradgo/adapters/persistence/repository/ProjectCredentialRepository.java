package org.dradgo.adapters.persistence.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ProjectCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Story 3c-5 — Spring Data repository for {@code project_credentials}. The {@code connector_role}
 * is matched as its raw underscored {@code String} form ({@code ConnectorRole#value()}); the
 * adapter converts to/from {@code ConnectorRole} at the boundary, mirroring the registry-getter
 * pattern on {@code IntegrationLinkEntity}.
 */
public interface ProjectCredentialRepository extends JpaRepository<ProjectCredentialEntity, Long> {

  Optional<ProjectCredentialEntity> findByProjectIdAndConnectorRoleAndArchivedAtIsNull(
      String projectId, String connectorRole);

  /**
   * Archive (set {@code archived_at}) whichever credential is currently active for {@code
   * (project_id, connector_role)} — freeing the V17 partial-unique slot for a rotated replacement.
   * Returns the number of rows archived.
   */
  @Modifying
  @Query(
      """
      update ProjectCredentialEntity credential
         set credential.archivedAt = :archivedAt
       where credential.projectId = :projectId
         and credential.connectorRole = :connectorRole
         and credential.archivedAt is null
      """)
  int archiveActive(
      @Param("projectId") String projectId,
      @Param("connectorRole") String connectorRole,
      @Param("archivedAt") OffsetDateTime archivedAt);
}
