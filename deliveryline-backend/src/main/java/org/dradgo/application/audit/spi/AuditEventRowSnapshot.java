package org.dradgo.application.audit.spi;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Story 4.3 (AC1/AC7) — one intentionally-lossy audit event row returned by {@link
 * AuditEventReadPort}. The persistence adapter selects it from {@code workflow_events} joined to
 * {@code workflow_runs} (for the run {@code public_id}); the service maps this SPI snapshot onto
 * the adapter-facing {@code AuditQueryService.AuditEventRow}, redacting {@code reason} and deriving
 * {@code correlationId}/{@code linkedArtifactId} from the (redacted) {@code details} map. Never
 * imported by the CLI/REST (story 4.3 Reconciliation 13).
 *
 * <p><b>Unredacted on purpose.</b> {@code reason} and {@code details} are carried RAW — redaction
 * is the service's job (story 4.3 Reconciliation 10), so the read seam stays a pure projection.
 * {@code id} is the internal bigserial PK, surfaced ONLY as the keyset cursor tiebreaker (never
 * rendered). {@code correlationId}/{@code linkedArtifactId} are NOT columns — they live in {@code
 * details} under {@code correlationId}/{@code artifactId} and the service extracts them
 * post-redaction.
 *
 * <p>Nullable reference fields ({@code priorState}, {@code resultingState}, {@code
 * failureCategory}, {@code reason}) are plain nullable references (repo convention). {@code
 * actorIdentity}/{@code actorType} are NOT NULL columns.
 *
 * @param id internal bigserial PK — keyset cursor tiebreaker; never surfaced to callers
 * @param eventId the event public id ({@code evt_...})
 * @param eventType the event-type wire string
 * @param workflowRunId the owning run's public id ({@code run_...})
 * @param actorIdentity the actor identity
 * @param actorType the actor-type wire string
 * @param priorState the prior-state wire string, or {@code null} on non-transition events
 * @param resultingState the resulting-state wire string, or {@code null} on non-transition events
 * @param failureCategory the failure-category wire string, or {@code null}
 * @param reason the RAW (unredacted) reason text, or {@code null}
 * @param createdAt the event timestamp ({@code created_at})
 * @param details the RAW {@code details} JSONB as a map (never {@code null}; may be empty)
 */
public record AuditEventRowSnapshot(
    long id,
    String eventId,
    String eventType,
    String workflowRunId,
    String actorIdentity,
    String actorType,
    String priorState,
    String resultingState,
    String failureCategory,
    String reason,
    OffsetDateTime createdAt,
    Map<String, Object> details) {

  public AuditEventRowSnapshot {
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
