package org.dradgo.application.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Story 3h-2 (AC3/AC6, FR76) — classifies a backend-side lint command's output into a {@link
 * LintFindings} value object. The <b>criticality baseline is exit-code-based</b> (Decision: minimum
 * viable classifier): a lint command whose exit signals findings ({@code exitCode != 0}) is treated
 * as having a critical ({@code error}) finding and parks the run; a clean exit ({@code 0}) is
 * non-critical regardless of any content. Per-line parsing is a <b>tolerant enrichment</b> layered
 * on top purely to populate the FE lint panel (3h-6) — a garbled/unrecognized line is skipped,
 * never a crash (it degrades exactly like the reviewer harvester).
 *
 * <p>On a clean exit any {@code error}-keyword line is downgraded to {@code warning} so the panel's
 * severity stays consistent with the authoritative exit-code gate signal (a linter printing "0
 * errors" must not read as critical). On a non-zero exit with no parseable {@code error} line a
 * single synthetic {@code error} finding is emitted so the panel always explains the gate.
 *
 * <p>{@code @Component} (not {@code @Service}) so the {@code
 * APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES} ArchUnit rule does not bind the {@code
 * *Classifier} name (mirrors {@code ProjectRuntimeConfigResolver}).
 */
@Component
public class LintFindingsClassifier {

  /** Guard against an unbounded jsonb payload from a pathological linter dump. */
  private static final int MAX_FINDINGS = 500;

  /** Truncate a single finding message so one huge line cannot bloat the payload. */
  private static final int MAX_MESSAGE_CHARS = 500;

  // Best-effort "path.ext:line" location, e.g. src/Foo.java:42 or frontend/a.ts:7. Tolerant — a
  // line without a match simply carries a null location.
  private static final Pattern FILE_LINE =
      Pattern.compile("([A-Za-z0-9_.\\-/\\\\]+\\.[A-Za-z0-9]+):(\\d+)");

  /**
   * Classify one lint command's (already redacted) output. {@code command} labels the produced
   * findings' {@code rule}. Never throws on malformed content.
   */
  public LintFindings classify(String command, int exitCode, String stdout, String stderr) {
    boolean critical = exitCode != 0;
    List<LintFinding> findings = new ArrayList<>();
    parseInto(findings, command, stdout, critical);
    parseInto(findings, command, stderr, critical);
    if (critical && findings.stream().noneMatch(f -> "error".equals(f.severity()))) {
      String summary = firstNonBlank(stderr, stdout);
      findings.add(
          new LintFinding(
              "error",
              null,
              null,
              command,
              truncate(summary != null ? summary.strip() : "lint command exited " + exitCode)));
    }
    return new LintFindings(critical, findings);
  }

  private static void parseInto(
      List<LintFinding> findings, String command, String output, boolean critical) {
    if (output == null || output.isBlank()) {
      return;
    }
    for (String rawLine : output.split("\\R")) {
      if (findings.size() >= MAX_FINDINGS) {
        return;
      }
      String line = rawLine.strip();
      if (line.isEmpty()) {
        continue;
      }
      String severity = severityOf(line, critical);
      if (severity == null) {
        continue; // garbled / non-diagnostic line — skip, never crash
      }
      String file = null;
      Integer lineNo = null;
      Matcher matcher = FILE_LINE.matcher(line);
      if (matcher.find()) {
        file = matcher.group(1);
        try {
          lineNo = Integer.valueOf(matcher.group(2));
        } catch (NumberFormatException ignored) {
          lineNo = null;
        }
      }
      findings.add(new LintFinding(severity, file, lineNo, command, truncate(line)));
    }
  }

  /**
   * Derive a finding severity from a line by keyword. On a clean exit an {@code error} mention is
   * downgraded to {@code warning} so the panel severity never contradicts the exit-code gate
   * signal. Returns {@code null} for a non-diagnostic line (skipped).
   */
  private static String severityOf(String line, boolean critical) {
    String lower = line.toLowerCase(Locale.ROOT);
    if (lower.contains("error")) {
      return critical ? "error" : "warning";
    }
    if (lower.contains("warning") || lower.contains("warn")) {
      return "warning";
    }
    if (lower.contains("info") || lower.contains("note")) {
      return "info";
    }
    return null;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return (b != null && !b.isBlank()) ? b : null;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= MAX_MESSAGE_CHARS ? value : value.substring(0, MAX_MESSAGE_CHARS);
  }
}
