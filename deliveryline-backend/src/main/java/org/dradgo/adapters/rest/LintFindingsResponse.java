package org.dradgo.adapters.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.dradgo.application.workflow.LintFinding;
import org.dradgo.application.workflow.WorkflowInspectionService.LintFindingsView;

/**
 * Story 3h-2 (AC6, FR76) — the severity-classified CPU-lint findings for {@code GET
 * /api/v1/workflows/{workflowRunId}/lint-findings}, backing the FE lint panel (3h-6).
 * Presentational + advisory-only: it carries NO governed action (the operator approve_lint /
 * request_lint_fix actions ride the allowed-actions matrix, not this read) and never 5xx-es on
 * missing findings (returns {@code state:"none"}).
 *
 * <p>Server-derived state (mirrors {@link ReviewerVerdictResponse}): the frontend stays dumb.
 * {@code state == "gated"} ⇒ the run is parked at {@code WaitingForLintApproval} on a critical
 * finding; {@code advisory} ⇒ non-critical (or already-approved) findings; {@code none} ⇒ nothing
 * to show.
 */
@Schema(
    name = "LintFindings",
    description = "Severity-classified CPU-lint findings surfaced beside the lint gate.")
public record LintFindingsResponse(
    @Schema(
            description = "Lint findings state.",
            allowableValues = {"none", "advisory", "gated"},
            example = "gated")
        String state,
    @Schema(
            description = "True when at least one critical (error) finding is present.",
            example = "true")
        boolean hasCritical,
    @Schema(description = "The severity-classified findings; empty when state=none.")
        List<LintFindingResponse> findings) {

  public static LintFindingsResponse from(LintFindingsView view) {
    return new LintFindingsResponse(
        view.state(),
        view.hasCritical(),
        view.findings().stream().map(LintFindingResponse::from).toList());
  }

  /**
   * One severity-classified lint finding (redacted — ids/counts/summary only, never secret bytes).
   */
  @Schema(name = "LintFinding", description = "A single severity-classified lint finding.")
  public record LintFindingResponse(
      @Schema(
              description = "Finding severity.",
              allowableValues = {"error", "warning", "info"},
              example = "error")
          String severity,
      @Schema(description = "Source file (best-effort; null when not parseable).", nullable = true)
          String file,
      @Schema(description = "Source line (best-effort; null when not parseable).", nullable = true)
          Integer line,
      @Schema(description = "Producing linter/command (null when unknown).", nullable = true)
          String rule,
      @Schema(description = "Redacted, truncated finding summary.", nullable = true)
          String message) {

    public static LintFindingResponse from(LintFinding finding) {
      return new LintFindingResponse(
          finding.severity(), finding.file(), finding.line(), finding.rule(), finding.message());
    }
  }
}
