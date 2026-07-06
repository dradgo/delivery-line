package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Story 3h-2 (AC3, FR76) — the severity classifier's exit-code baseline + tolerant per-line
 * enrichment. Criticality is exit-code-driven (a non-zero exit is critical regardless of content);
 * per-line parsing only enriches the panel and never crashes on garbled input.
 */
class LintFindingsClassifierTest {

  private final LintFindingsClassifier classifier = new LintFindingsClassifier();

  @Test
  void nonZeroExitIsCriticalWithASyntheticErrorFindingWhenNoErrorLineParsed() {
    LintFindings result = classifier.classify("mvn checkstyle:check", 1, "some noise output", "");
    assertThat(result.hasCritical()).isTrue();
    assertThat(result.findings()).hasSize(1);
    assertThat(result.findings().get(0).severity()).isEqualTo("error");
    assertThat(result.findings().get(0).rule()).isEqualTo("mvn checkstyle:check");
  }

  @Test
  void cleanExitWithNoFindingsIsNotCritical() {
    LintFindings result = classifier.classify("npm run lint", 0, "0 problems", "");
    assertThat(result.hasCritical()).isFalse();
    // "0 problems" carries no severity keyword → skipped; a clean exit yields no findings.
    assertThat(result.findings()).isEmpty();
  }

  @Test
  void cleanExitWithWarningLinesIsNonCriticalAdvisory() {
    LintFindings result =
        classifier.classify(
            "eslint", 0, "src/app.ts:12:5  warning  Unexpected console statement", "");
    assertThat(result.hasCritical()).isFalse();
    assertThat(result.findings()).hasSize(1);
    LintFinding finding = result.findings().get(0);
    assertThat(finding.severity()).isEqualTo("warning");
    assertThat(finding.file()).isEqualTo("src/app.ts");
    assertThat(finding.line()).isEqualTo(12);
  }

  @Test
  void errorKeywordOnACleanExitIsDowngradedToWarningSoSeverityMatchesTheExitCodeGate() {
    // A linter printing "0 errors" on a clean exit must NOT read as critical.
    LintFindings result = classifier.classify("checkstyle", 0, "Found 0 errors", "");
    assertThat(result.hasCritical()).isFalse();
    assertThat(result.findings()).hasSize(1);
    assertThat(result.findings().get(0).severity()).isEqualTo("warning");
  }

  @Test
  void nonZeroExitParsesErrorLinesWithLocations() {
    LintFindings result =
        classifier.classify(
            "checkstyle", 1, "[ERROR] src/Foo.java:42: Missing a Javadoc comment.", "");
    assertThat(result.hasCritical()).isTrue();
    assertThat(result.findings())
        .anySatisfy(
            f -> {
              assertThat(f.severity()).isEqualTo("error");
              assertThat(f.file()).isEqualTo("src/Foo.java");
              assertThat(f.line()).isEqualTo(42);
            });
  }

  @Test
  void garbledOrNonDiagnosticLinesAreSkippedNotCrashed() {
    LintFindings result =
        classifier.classify("linter", 0, "\n   \nBUILD banner ###\n:::garbage:::\n", "  ");
    assertThat(result.hasCritical()).isFalse();
    // None of the lines carry a severity keyword → all skipped, no crash.
    assertThat(result.findings()).isEmpty();
  }

  @Test
  void toleratesNullOutputStreams() {
    LintFindings result = classifier.classify("linter", 0, null, null);
    assertThat(result.hasCritical()).isFalse();
    assertThat(result.findings()).isEmpty();
  }
}
