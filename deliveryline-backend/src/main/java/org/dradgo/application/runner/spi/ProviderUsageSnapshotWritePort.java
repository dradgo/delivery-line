package org.dradgo.application.runner.spi;

import java.time.OffsetDateTime;

/**
 * Story 3d-7 (FR69, AC3) — write SPI over the V24 {@code provider_usage_snapshots} table.
 * Implemented under {@code adapters.persistence}.
 *
 * <p>Mirrors the {@code BatchSubmissionWritePort} posture: the adapter participates in its OWN
 * {@code REQUIRED} transaction and maps the {@code uq_provider_usage_snapshots_public_id} UNIQUE
 * constraint to a typed error. The producing {@code RunnerBroker.handleSuccess} call is best-effort
 * (a snapshot-write failure NEVER unwinds a successfully-completed run — Trap T2 byte-identical
 * legacy posture), so the broker swallows any thrown {@code DomainException}.
 *
 * <p><b>NON-SECRET by construction (Trap T1).</b> {@code accountReference} is the runner-emitted
 * non-secret account label, never a {@code project_credentials} FK and never a token-derived value.
 * No token/secret field is accepted or stored.
 */
public interface ProviderUsageSnapshotWritePort {

  /**
   * Insert one {@code provider_usage_snapshots} row and return the server-stamped {@code
   * created_at} so the caller can log/surface the capture time.
   *
   * @throws org.dradgo.domain.DomainException with {@code INTERNAL_ERROR} on a public-id collision.
   */
  OffsetDateTime insert(NewProviderUsageSnapshot snapshot);

  /**
   * Caller-built insert payload. {@code publicId} is supplied externally (caller-generated via
   * {@link org.dradgo.domain.id.PublicIdPrefixes#PROVIDER_USAGE_SNAPSHOT}) so log lines stay
   * deterministic. Window fields are nullable — {@code signalState == not_exposed} carries them all
   * {@code null}. NO secret/token field by construction (AC4).
   */
  record NewProviderUsageSnapshot(
      String publicId,
      String workflowRunId,
      String runnerExecutionId,
      String accountReference,
      String signalState,
      Double fiveHourUsedFraction,
      Integer fiveHourUsed,
      Integer fiveHourLimit,
      OffsetDateTime fiveHourResetsAt,
      Double weeklyUsedFraction,
      Integer weeklyUsed,
      Integer weeklyLimit,
      OffsetDateTime weeklyResetsAt,
      OffsetDateTime asOf) {}
}
