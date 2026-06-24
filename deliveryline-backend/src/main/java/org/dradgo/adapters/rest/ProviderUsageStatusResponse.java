package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import org.dradgo.application.workflow.WorkflowInspectionService.ProviderUsageStatusView;

/**
 * Story 3d-7 (FR69, AC5) — REST view of the latest per-credential provider usage/limit snapshot.
 *
 * <p>Provider-reported + as-of-a-timestamp values (or the documented {@code not_exposed} state).
 * NON-SECRET by construction — only window numbers, timestamps, and the non-secret {@code
 * accountReference} label. {@code present == false} means no snapshot has been captured for the run
 * yet (legacy/default runner, or no run output), and the UI degrades to an empty state.
 */
@Schema(
    name = "ProviderUsageStatus",
    description =
        "Latest per-credential, NON-SECRET provider usage/limit snapshot for a run "
            + "(5-hour + weekly windows, or the documented 'not exposed by provider' state). "
            + "Provider-reported and as-of a timestamp; never a fabricated value.")
public record ProviderUsageStatusResponse(
    @Schema(description = "False when no snapshot has been captured for the run yet.")
        boolean present,
    @Schema(
            description = "available | not_exposed; null when no snapshot exists.",
            nullable = true,
            allowableValues = {"available", "not_exposed"})
        String signalState,
    @Schema(
            description = "Non-secret account label that produced the usage (e.g. claude:oauth).",
            nullable = true)
        String accountReference,
    @Schema(description = "5-hour rolling window; null when not exposed / absent.", nullable = true)
        UsageWindowResponse fiveHour,
    @Schema(description = "Weekly window; null when not exposed / absent.", nullable = true)
        UsageWindowResponse weekly,
    @Schema(
            description = "Provider-reported as-of timestamp; null when not exposed.",
            nullable = true)
        OffsetDateTime asOf,
    @Schema(description = "Server-stamped capture time; null when absent.", nullable = true)
        OffsetDateTime capturedAt) {

  /** One provider window (5h or weekly). All fields nullable. */
  @Schema(name = "ProviderUsageWindow")
  public record UsageWindowResponse(
      @Schema(description = "Used fraction 0..1.", nullable = true) Double usedFraction,
      @Schema(description = "Used units.", nullable = true) Integer used,
      @Schema(description = "Limit units.", nullable = true) Integer limit,
      @Schema(description = "Window reset timestamp.", nullable = true) OffsetDateTime resetsAt) {}

  /** Empty body for a run with no captured snapshot. */
  public static ProviderUsageStatusResponse absent() {
    return new ProviderUsageStatusResponse(false, null, null, null, null, null, null);
  }

  /** Map a captured snapshot; a not-exposed/empty window becomes {@code null}. */
  public static ProviderUsageStatusResponse from(ProviderUsageStatusView view) {
    return new ProviderUsageStatusResponse(
        true,
        view.signalState(),
        view.accountReference(),
        windowFrom(view.fiveHour()),
        windowFrom(view.weekly()),
        view.asOf(),
        view.capturedAt());
  }

  private static UsageWindowResponse windowFrom(ProviderUsageStatusView.UsageWindowView window) {
    if (window == null || window.isEmpty()) {
      return null;
    }
    return new UsageWindowResponse(
        window.usedFraction(), window.used(), window.limit(), window.resetsAt());
  }
}
