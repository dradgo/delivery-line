package org.dradgo.application.workflow;

import java.util.List;

/**
 * Story 3h-2 (AC3/AC6, FR76) — the severity-classified result of a backend-side CPU-lint run.
 * {@code hasCritical} is the gate signal: {@code true} ⇒ at least one critical (linter {@code
 * error}) finding, which parks the run at {@code WaitingForLintApproval} BEFORE any LLM review or
 * push; {@code false} ⇒ clean or only non-critical ({@code warning}/{@code info}) findings, which
 * are attached as advisory and the delivery tail proceeds unchanged.
 *
 * <p>Persisted as the {@code lint_findings} jsonb payload on the LINT {@code runner_executions} row
 * (Decision 5 — no new table). {@code findings} is defensively copied to an immutable list (null →
 * empty).
 */
public record LintFindings(boolean hasCritical, List<LintFinding> findings) {

  public LintFindings {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  /** A clean result (no critical, no findings) — the parity path when lint passes cleanly. */
  public static LintFindings clean() {
    return new LintFindings(false, List.of());
  }
}
