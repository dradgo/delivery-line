package org.dradgo.adapters.persistence.mapper;

import org.dradgo.adapters.persistence.entity.IntegrationLinkEntity;
import org.dradgo.application.integration.IntegrationLink;
import org.springframework.stereotype.Component;

/**
 * Entity ↔ domain translation for {@code integration_links}. Explicit (no MapStruct) to match the
 * 1-11 pattern used by sibling persistence adapters. The {@code external_metadata} jsonb column is
 * deliberately NOT projected onto the domain shape — the application reads/writes raw JSON bytes
 * through the SPI; mapper consumers that need the typed metadata go through the persistence
 * adapter, which owns the byte[]↔Map translation.
 */
@Component
public class IntegrationLinkEntityMapper {

  public IntegrationLink toDomain(IntegrationLinkEntity entity) {
    return new IntegrationLink(
        entity.getPublicId(),
        entity.getWorkflowRun().getPublicId(),
        entity.getIntegrationType(),
        entity.getExternalRef(),
        entity.getSyncStatus(),
        entity.getCreatedAt().toInstant(),
        entity.getLastSyncAt() == null ? null : entity.getLastSyncAt().toInstant(),
        entity.getArchivedAt() == null ? null : entity.getArchivedAt().toInstant());
  }
}
