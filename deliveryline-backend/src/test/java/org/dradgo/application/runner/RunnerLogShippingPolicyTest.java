package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.domain.registry.DataClassification;
import org.junit.jupiter.api.Test;

/**
 * Story 3.6 forward seam, exercised by story 3.7 (AC4): {@link RunnerLogShippingPolicy#isShippable}
 * is the single stable predicate the ELK shipping path keys on — {@code local-only} (and null) are
 * never shippable; everything {@code shareable-redacted} and above is. This pins the contract the
 * Logstash classification filter and {@link RunnerLogShippingService} both rely on.
 */
class RunnerLogShippingPolicyTest {

  @Test
  void localOnlyIsNeverShippable() {
    assertThat(RunnerLogShippingPolicy.isShippable(DataClassification.LOCAL_ONLY)).isFalse();
  }

  @Test
  void nullClassificationIsNeverShippable() {
    assertThat(RunnerLogShippingPolicy.isShippable(null)).isFalse();
  }

  @Test
  void shareableRedactedAndAboveAreShippable() {
    assertThat(RunnerLogShippingPolicy.isShippable(DataClassification.SHAREABLE_REDACTED)).isTrue();
    assertThat(RunnerLogShippingPolicy.isShippable(DataClassification.SHAREABLE_FULL)).isTrue();
    assertThat(RunnerLogShippingPolicy.isShippable(DataClassification.DERIVED_PUBLIC_SAFE))
        .isTrue();
  }
}
