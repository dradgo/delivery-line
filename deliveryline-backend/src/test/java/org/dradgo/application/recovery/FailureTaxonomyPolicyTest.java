package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureTaxonomyValue;
import org.junit.jupiter.api.Test;

/**
 * Story 4.9 (AC6/AC12) — pins the write-path deprecation guard through the pure-static seam with
 * SYNTHETIC arguments: the registry ships with zero deprecated values (see {@code
 * FailureTaxonomyValueTest.noConstantIsDeprecatedToday}), so the guard's reject branch cannot be
 * exercised through a real constant, and faking a deprecated constant would mutate the shipped
 * registry. The seam keeps NFR33's write-rejection semantics real and non-dead from day one.
 */
class FailureTaxonomyPolicyTest {

  @Test
  void activeValuePassesTheGuard() {
    assertThatCode(() -> FailureTaxonomyPolicy.requireNotDeprecated("specification_gap", null))
        .doesNotThrowAnyException();
  }

  @Test
  void deprecatedValueIsRejectedWithReplacementHint() {
    assertThatThrownBy(
            () -> FailureTaxonomyPolicy.requireNotDeprecated("legacy_value", "context_gap"))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.DEPRECATED_TAXONOMY_VALUE);
              assertThat(ex.details()).containsEntry("provided", "legacy_value");
              assertThat(ex.details()).containsEntry("replacementValue", "context_gap");
            });
  }

  @Test
  void everyShippedConstantPassesTheGuardToday() {
    for (FailureTaxonomyValue value : FailureTaxonomyValue.values()) {
      assertThatCode(
              () ->
                  FailureTaxonomyPolicy.requireNotDeprecated(
                      value.value(), value.deprecatedReplacementValue()))
          .doesNotThrowAnyException();
    }
  }
}
