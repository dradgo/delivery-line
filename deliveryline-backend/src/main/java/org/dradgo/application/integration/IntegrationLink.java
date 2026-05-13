package org.dradgo.application.integration;

import java.time.Instant;
import java.util.Objects;
import org.dradgo.domain.registry.IntegrationSyncStatus;

/**
 * Domain shape of an {@code integration_links} row. The wire form of {@code integrationType} is
 * the V1 CHECK-constrained value ({@code linear} or {@code github_pr}); {@code externalRef} is the
 * provider-side ticket reference (e.g., {@code LIN-123}).
 *
 * <p>The persistence adapter translates entity ↔ this record via an explicit mapper (see story
 * Task 2). {@code archivedAt} is exposed so the service layer can distinguish active vs
 * superseded links without an extra query.
 */
public record IntegrationLink(
	String publicId,
	String workflowRunPublicId,
	String integrationType,
	String externalRef,
	IntegrationSyncStatus syncStatus,
	Instant createdAt,
	Instant lastSyncAt,
	Instant archivedAt) {

	public IntegrationLink {
		if (publicId == null || publicId.isBlank()) {
			throw new IllegalArgumentException("publicId must be non-blank");
		}
		if (workflowRunPublicId == null || workflowRunPublicId.isBlank()) {
			throw new IllegalArgumentException("workflowRunPublicId must be non-blank");
		}
		if (integrationType == null || integrationType.isBlank()) {
			throw new IllegalArgumentException("integrationType must be non-blank");
		}
		if (externalRef == null || externalRef.isBlank()) {
			throw new IllegalArgumentException("externalRef must be non-blank");
		}
		Objects.requireNonNull(syncStatus, "syncStatus");
		Objects.requireNonNull(createdAt, "createdAt");
	}

	/**
	 * An "active" integration link is one that has not been archived and is not in a superseded
	 * state — used by the conflict-detection path in {@code IntegrationLinkService.linkTicket}.
	 */
	public boolean isActive() {
		return archivedAt == null && syncStatus != IntegrationSyncStatus.SUPERSEDED;
	}
}
