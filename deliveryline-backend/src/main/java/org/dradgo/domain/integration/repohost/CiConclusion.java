package org.dradgo.domain.integration.repohost;

/**
 * Story 3h-5 (AC1, FR79) — the vendor-neutral, domain-shaped verdict of a repository host's CI
 * build for a pushed commit. Adapters translate a host's native check/pipeline vocabulary (GitHub
 * check-run {@code status}/{@code conclusion}, Bitbucket Pipelines state, …) into these five
 * projection values so the scheduled {@code CiStatusPollingService} sweep never sees host-specific
 * strings ({@code REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT}).
 *
 * <p>This is a <em>projection</em> type, not a foundation registry: it does NOT implement {@code
 * RegistryValue} and has NO {@code DomainRegistry} / {@code RegistryContractTest} leg. The
 * persisted {@code workflow_runs.ci_status} text column stores the lowercase name ({@code pending}
 * / {@code success} / {@code failure} / {@code neutral} / {@code unavailable}); a CHECK constraint
 * owns that closed set (V41), so the enum and the column are kept in lockstep by the migration, not
 * a registry test.
 *
 * <ul>
 *   <li>{@link #PENDING} — at least one check is still running (or the poll has not resolved yet).
 *       Keep polling until it resolves or the poll-attempt cap is hit.
 *   <li>{@link #SUCCESS} — every completed check succeeded (or was neutral/skipped). Green CI
 *       proceeds; the run is already at/past review.
 *   <li>{@link #FAILURE} — at least one check failed (failure / timed_out). Drives the bounded
 *       investigation/fix loop. NB {@code action_required} is deliberately NOT a failure — it needs
 *       a manual operator action the fix loop cannot satisfy, so it maps to NEUTRAL (Decision 2).
 *   <li>{@link #NEUTRAL} — no CI is configured on this ref, every check was cancelled/stale, or a
 *       check awaits a manual action ({@code action_required}). Never loop; record and stop.
 *   <li>{@link #UNAVAILABLE} — the host could not be read within the poll-attempt budget (transient
 *       errors exhausted). Terminal for the sweep; recorded, no re-dispatch.
 * </ul>
 */
public enum CiConclusion {
  PENDING,
  SUCCESS,
  FAILURE,
  NEUTRAL,
  UNAVAILABLE
}
