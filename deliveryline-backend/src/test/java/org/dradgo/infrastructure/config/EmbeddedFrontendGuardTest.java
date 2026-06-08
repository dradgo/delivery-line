package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Fail-fast startup guard for the embedded Vite SPA bundle. The decision is extracted to a static
 * predicate so both branches are unit-testable without a Spring context or a built bundle.
 */
class EmbeddedFrontendGuardTest {

  @Test
  void presentBundlePasses() {
    assertThatCode(() -> EmbeddedFrontendGuard.assertBundlePresent(true))
        .doesNotThrowAnyException();
  }

  @Test
  void missingBundleFailsWithActionableMessage() {
    assertThatThrownBy(() -> EmbeddedFrontendGuard.assertBundlePresent(false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("static/index.html")
        .hasMessageContaining("fail-on-missing-bundle");
  }
}
