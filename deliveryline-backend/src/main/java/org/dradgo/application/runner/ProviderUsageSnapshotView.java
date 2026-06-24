package org.dradgo.application.runner;

import java.time.OffsetDateTime;

/**
 * Story 3d-7 (FR69) — the read view of a per-credential provider usage/limit snapshot surfaced by
 * the REST endpoint + CLI.
 *
 * <p><b>NON-SECRET by construction (Trap T1 / AC3/AC4).</b> Carries only the non-secret {@code
 * accountReference} label (e.g. {@code claude:oauth}), the {@code signalState}, window numbers, and
 * timestamps — NEVER a token, key, or account secret. {@code signalState == not_exposed} is a
 * first-class state (the provider does not surface the 5h/weekly window headless — spike outcome),
 * in which case every {@link UsageWindow} field is {@code null} and the surface degrades to the
 * documented "not exposed by provider" indicator rather than a fabricated number.
 *
 * @param publicId the {@code pul_} snapshot id
 * @param workflowRunId the run the snapshot is attributed to
 * @param runnerExecutionId the producing runner execution ({@code null} when not captured)
 * @param accountReference the non-secret account label that produced the usage
 * @param signalState {@code available} | {@code not_exposed}
 * @param fiveHour the 5-hour rolling window (all fields {@code null} when not exposed)
 * @param weekly the weekly window (all fields {@code null} when not exposed)
 * @param asOf provider-reported as-of timestamp ({@code null} when not exposed)
 * @param capturedAt server-stamped capture time (present on any persisted snapshot; {@code null}
 *     only in the absent/no-snapshot placeholder surfaced by the REST/CLI layer)
 */
public record ProviderUsageSnapshotView(
    String publicId,
    String workflowRunId,
    String runnerExecutionId,
    String accountReference,
    String signalState,
    UsageWindow fiveHour,
    UsageWindow weekly,
    OffsetDateTime asOf,
    OffsetDateTime capturedAt) {

  /** A single provider window (5h or weekly). All fields are nullable / optional. */
  public record UsageWindow(
      Double usedFraction, Integer used, Integer limit, OffsetDateTime resetsAt) {

    /** True when no field carries a value (the not-exposed / empty window). */
    public boolean isEmpty() {
      return usedFraction == null && used == null && limit == null && resetsAt == null;
    }
  }
}
