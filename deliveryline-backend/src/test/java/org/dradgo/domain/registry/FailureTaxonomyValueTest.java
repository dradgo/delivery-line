package org.dradgo.domain.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;

class FailureTaxonomyValueTest {

  /**
   * NFR33 stability gate: taxonomy values are NEVER hard-removed. This set is append-only — a new
   * value is added here alongside its ADR; removing a value from the registry reds this test.
   * Asserted as containment (not equality) so additions are free and removals are the only red.
   */
  private static final Set<String> FROZEN_WIRE_VALUES =
      Set.of(
          "specification_gap",
          "context_gap",
          "agent_execution_failure",
          "review_rejection",
          "integration_or_merge_failure",
          "tooling_or_infrastructure_failure");

  @Test
  void registryContainsEveryFrozenWireValueAndNeverHardRemovesOne() {
    assertThat(DomainRegistry.failureTaxonomyValues())
        .as(
            "NFR33: failure-taxonomy values must never be hard-removed — deprecate with a "
                + "replacement instead (ADR 0035)")
        .containsAll(FROZEN_WIRE_VALUES);
  }

  @Test
  void sixCanonicalValuesArePinnedToTheirWireStrings() {
    assertThat(FailureTaxonomyValue.SPECIFICATION_GAP.value()).isEqualTo("specification_gap");
    assertThat(FailureTaxonomyValue.CONTEXT_GAP.value()).isEqualTo("context_gap");
    assertThat(FailureTaxonomyValue.AGENT_EXECUTION_FAILURE.value())
        .isEqualTo("agent_execution_failure");
    assertThat(FailureTaxonomyValue.REVIEW_REJECTION.value()).isEqualTo("review_rejection");
    assertThat(FailureTaxonomyValue.INTEGRATION_OR_MERGE_FAILURE.value())
        .isEqualTo("integration_or_merge_failure");
    assertThat(FailureTaxonomyValue.TOOLING_OR_INFRASTRUCTURE_FAILURE.value())
        .isEqualTo("tooling_or_infrastructure_failure");
    assertThat(FailureTaxonomyValue.values()).hasSize(6);
  }

  @Test
  void noConstantIsDeprecatedToday() {
    assertThat(Arrays.stream(FailureTaxonomyValue.values()))
        .noneMatch(FailureTaxonomyValue::deprecated);
    assertThat(Arrays.stream(FailureTaxonomyValue.values()))
        .allMatch(value -> value.deprecatedReplacementValue() == null);
  }

  @Test
  void displayLabelOfEveryActiveConstantEqualsItsWireValue() {
    for (FailureTaxonomyValue value : FailureTaxonomyValue.values()) {
      assertThat(value.displayLabel()).isEqualTo(value.value());
    }
  }

  @Test
  void displayLabelSeamAffixesDeprecatedForSyntheticDeprecatedValue() {
    assertThat(FailureTaxonomyValue.displayLabel("legacy_value", "context_gap"))
        .isEqualTo("legacy_value (deprecated)");
    assertThat(FailureTaxonomyValue.displayLabel("specification_gap", null))
        .isEqualTo("specification_gap");
  }

  @Test
  void fromValueRoundTripsEveryConstant() {
    for (FailureTaxonomyValue value : FailureTaxonomyValue.values()) {
      assertThat(FailureTaxonomyValue.fromValue(value.value(), "taxonomyValue")).isEqualTo(value);
    }
  }

  @Test
  void fromValueRejectsUnknownValue() {
    assertThatThrownBy(() -> FailureTaxonomyValue.fromValue("not_a_value", "taxonomyValue"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            ex ->
                assertThat(((DomainException) ex).errorCode())
                    .isEqualTo(DomainErrorCode.UNKNOWN_REGISTRY_VALUE));
  }

  @Test
  void fromNullableValueReturnsNullOnNull() {
    assertThat(FailureTaxonomyValue.fromNullableValue(null, "taxonomyValue")).isNull();
  }
}
