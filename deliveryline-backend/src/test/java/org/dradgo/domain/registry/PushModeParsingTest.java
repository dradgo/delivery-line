package org.dradgo.domain.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.dradgo.domain.DomainException;
import org.junit.jupiter.api.Test;

/** Story 3h-4 (AC1) — PushMode round-trip + fail-fast on an unknown wire value. */
class PushModeParsingTest {

  @Test
  void everyValueRoundTrips() {
    for (PushMode mode : PushMode.values()) {
      assertThat(PushMode.fromValue(mode.value(), "pushMode")).isEqualTo(mode);
    }
  }

  @Test
  void wireValuesAreTheExpectedClosedSet() {
    assertThat(PushMode.AUTO.value()).isEqualTo("auto");
    assertThat(PushMode.MANUAL.value()).isEqualTo("manual");
    assertThat(PushMode.APPROVE.value()).isEqualTo("approve");
  }

  @Test
  void unknownValueThrowsTypedUnknownRegistryValue() {
    assertThatThrownBy(() -> PushMode.fromValue("bogus", "projects.push_mode"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            ex ->
                assertThat(((DomainException) ex).errorCode())
                    .isEqualTo(DomainErrorCode.UNKNOWN_REGISTRY_VALUE));
  }
}
