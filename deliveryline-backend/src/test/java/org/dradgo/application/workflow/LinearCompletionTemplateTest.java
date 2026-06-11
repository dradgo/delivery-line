package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

/** Story 3.16 (AC8 / D4) — placeholder grammar for the completion-sync summary template. */
class LinearCompletionTemplateTest {

  @Test
  void defaultTemplateIsValid() {
    LinearCompletionTemplate.validate(
        WorkflowProperties.LinearCompletionSync.DEFAULT_TEMPLATE); // must not throw
  }

  @Test
  void validateRejectsBlankTemplate() {
    assertThatThrownBy(() -> LinearCompletionTemplate.validate("  "))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMPLETION_TEMPLATE);
  }

  @Test
  void validateRejectsUnknownPlaceholder() {
    assertThatThrownBy(() -> LinearCompletionTemplate.validate("run {runId} bogus {nope}"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e -> {
              DomainException de = (DomainException) e;
              assertThat(de.errorCode()).isEqualTo(DomainErrorCode.INVALID_COMPLETION_TEMPLATE);
              assertThat(de.getMessage()).contains("nope");
            });
  }

  @Test
  void validateRejectsTemplateMissingRequiredRunId() {
    assertThatThrownBy(() -> LinearCompletionTemplate.validate("PR {prUrl} only"))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMPLETION_TEMPLATE);
  }

  @Test
  void validateAcceptsAMinimalRunIdOnlyTemplate() {
    LinearCompletionTemplate.validate("Run {runId} done."); // must not throw
  }

  @Test
  void renderSubstitutesKnownValues() {
    String rendered =
        LinearCompletionTemplate.render(
            "Run {runId} PR {prUrl}", Map.of("runId", "run_1", "prUrl", "http://x/1"));
    assertThat(rendered).isEqualTo("Run run_1 PR http://x/1");
  }

  @Test
  void renderLeavesNoLiteralTokenWhenAValueIsAbsent() {
    String rendered =
        LinearCompletionTemplate.render("Run {runId} PR {prUrl}", Map.of("runId", "run_1"));
    assertThat(rendered).isEqualTo("Run run_1 PR ").doesNotContain("{prUrl}");
  }
}
