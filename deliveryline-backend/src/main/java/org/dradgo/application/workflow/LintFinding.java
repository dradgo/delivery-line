package org.dradgo.application.workflow;

/**
 * Story 3h-2 (AC3/AC6, FR76) — one severity-classified CPU-lint finding. A pure application value
 * object persisted (as part of {@link LintFindings}) into the {@code lint_findings} jsonb column on
 * the LINT {@code runner_executions} row and served through the {@code getLintFindings} read leg.
 *
 * <ul>
 *   <li>{@code severity} — one of {@code error} (critical), {@code warning}, {@code info}
 *       (non-critical). Only a critical finding parks the run at {@code WaitingForLintApproval}.
 *   <li>{@code file} / {@code line} — best-effort source location (nullable; the tolerant
 *       classifier leaves them null when it cannot parse a location).
 *   <li>{@code rule} — the linter/command that produced the finding (nullable).
 *   <li>{@code message} — a redacted, truncated summary line (never raw secret bytes — the findings
 *       pass the same redaction path as the captured output).
 * </ul>
 */
public record LintFinding(
    String severity, String file, Integer line, String rule, String message) {}
