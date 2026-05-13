package org.dradgo.application.integration.spi;

import java.time.Instant;
import java.util.Optional;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.domain.registry.IntegrationSyncStatus;

/**
 * Application-owned persistence SPI for {@code integration_links} rows. Mirrors the 1-13
 * {@code RunnerExecutionRecordPort} shape — application services depend on this port, not on JPA
 * entities or Spring Data repositories directly. The persistence adapter implementation lives in
 * {@code adapters.persistence.IntegrationLinkPersistenceAdapter} (story 1.14 Task 2).
 *
 * <p>{@link #findActiveByTypeAndExternalRefForUpdate(String, String)} takes a
 * {@code PESSIMISTIC_WRITE} lock on the matching row so concurrent {@code linkTicket} calls for
 * the same ticket race-free at the conflict-detection step (story 1.14 Task 5 step 5). The V6
 * partial-unique index is the belt-and-braces backstop; the persistence adapter translates the
 * resulting {@code DataIntegrityViolationException} into a typed
 * {@code DomainException(INTEGRATION_LINK_CONFLICT)} so both paths produce identical surface.
 */
public interface IntegrationLinkRecordPort {

	/** Insert a freshly-reserved integration link in {@code sync_status='linked'} state. */
	IntegrationLink insert(NewIntegrationLink newLink);

	/** Look up by public id ({@code ilk_…}). Returns empty if the row does not exist. */
	Optional<IntegrationLink> findByPublicId(String publicId);

	/**
	 * Find the currently-active integration link (not archived, not superseded) for a given
	 * external reference. Used by the conflict-detection path. Plain read — no lock.
	 */
	Optional<IntegrationLink> findActiveByTypeAndExternalRef(String integrationType, String externalRef);

	/**
	 * Find the currently-active integration link for a given external reference, taking a
	 * {@code PESSIMISTIC_WRITE} lock for the duration of the transaction. Used by
	 * {@code IntegrationLinkService.linkTicket} to race-free against concurrent link attempts for
	 * the same ticket.
	 */
	Optional<IntegrationLink> findActiveByTypeAndExternalRefForUpdate(String integrationType, String externalRef);

	/**
	 * Find the currently-active integration link for a given workflow run (assumes at most one
	 * active link per run for Epic 1; in later epics a run could carry both a {@code linear} link
	 * and a {@code github_pr} link, in which case this should return the {@code linear} variant by
	 * convention — pin this in the persistence adapter test).
	 */
	Optional<IntegrationLink> findActiveByWorkflowRun(String workflowRunPublicId);

	/**
	 * Transition the sync status of an integration link, updating {@code last_sync_at} when the
	 * caller passes a non-null timestamp. Returns the updated row. Throws
	 * {@code DomainException(ILLEGAL_TRANSITION)} when the requested transition is not allowed by
	 * the state machine (see {@code IntegrationLinkStateMachine}).
	 */
	IntegrationLink updateSyncStatus(
		String publicId,
		IntegrationSyncStatus newStatus,
		Instant lastSyncAt);

	/** Mark the row as archived. Sets {@code archived_at = now}. */
	IntegrationLink markArchived(String publicId, Instant archivedAt);

	/**
	 * Project the active integration link for a workflow run as a {@link TicketSummaryProjection}
	 * (raw redacted {@code external_metadata} bytes + the external reference). Used by
	 * {@code IntegrationLinkTicketSummaryProvider} to keep the application layer free of any
	 * dependency on adapter-side JPA entities or repositories — the projection is the
	 * application-owned narrow shape over the row.
	 */
	Optional<TicketSummaryProjection> findActiveTicketSummaryByWorkflowRun(String workflowRunPublicId);

	/**
	 * Narrow projection over the active {@code integration_links} row carrying the bytes the
	 * application layer needs to render a {@code TicketSummary}. {@code externalMetadata} is the
	 * raw redacted JSON; the application reads it via Jackson without ever touching JPA types.
	 */
	record TicketSummaryProjection(String externalRef, byte[] externalMetadata) {

		public TicketSummaryProjection {
			if (externalRef == null || externalRef.isBlank()) {
				throw new IllegalArgumentException("externalRef must be non-blank");
			}
			externalMetadata = externalMetadata == null ? new byte[0] : externalMetadata;
		}
	}

	/**
	 * Input payload for {@link #insert(NewIntegrationLink)}. Kept as a small record so the port
	 * does not leak entity types. {@code externalMetadata} is opaque JSON bytes; the persistence
	 * adapter stores it in the {@code external_metadata} jsonb column (V1 CHECK: {@code < 64KB}).
	 * The redaction policy is applied by the caller BEFORE building this record — the adapter
	 * does not redact.
	 */
	record NewIntegrationLink(
		String publicId,
		String workflowRunPublicId,
		String integrationType,
		String externalRef,
		byte[] externalMetadata,
		Instant createdAt,
		Instant lastSyncAt) {
	}
}
