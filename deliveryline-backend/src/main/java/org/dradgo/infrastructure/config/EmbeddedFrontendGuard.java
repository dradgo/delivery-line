package org.dradgo.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Fail-fast startup guard for embedded-frontend mode. The Vite SPA bundle is built by the {@code
 * deliveryline-frontend} module ({@code target/dist}) and copied into the backend's {@code
 * classpath:/static/} at {@code mvn package} time (story 2.1). Running the backend WITHOUT that
 * copy (e.g. straight from the IDE, or with {@code -Dfrontend-maven-plugin.skip=true}) yields a
 * jar/run that serves the API but 404s the UI — a silent, confusing failure mode.
 *
 * <p>This guard aborts startup with an actionable message when {@code classpath:/static/index.html}
 * is absent, so the missing bundle is caught immediately instead of at first browser load. It is
 * gated by {@code deliveryline.frontend.fail-on-missing-bundle} (default {@code true}); the test
 * tier sets it {@code false} (the SPA bundle is never built into the test classpath), and an
 * API-only dev run that uses the Vite dev server on {@code :5173} can disable it too.
 */
@Component
@ConditionalOnProperty(
    name = "deliveryline.frontend.fail-on-missing-bundle",
    havingValue = "true",
    matchIfMissing = true)
public class EmbeddedFrontendGuard {

  private static final Logger LOG = LoggerFactory.getLogger(EmbeddedFrontendGuard.class);

  /** Classpath location of the embedded SPA shell (Spring Boot serves it at {@code /}). */
  static final String EMBEDDED_SHELL = "static/index.html";

  @PostConstruct
  void verifyEmbeddedFrontendPresent() {
    assertBundlePresent(new ClassPathResource(EMBEDDED_SHELL).exists());
    LOG.info(
        "embedded_frontend bundle present at classpath:/{} — serving UI + API on a single port",
        EMBEDDED_SHELL);
  }

  /**
   * Throws an actionable {@link IllegalStateException} when the embedded SPA bundle is absent.
   * Package-private + static so both branches are unit-testable without a Spring context.
   */
  static void assertBundlePresent(boolean present) {
    if (present) {
      return;
    }
    throw new IllegalStateException(
        "Embedded frontend bundle missing: classpath:/"
            + EMBEDDED_SHELL
            + " was not found. The Vite SPA must be built and embedded before startup. Fix with one"
            + " of:\n"
            + "  • mvn -pl deliveryline-frontend -am package   "
            + "(build the bundle, then run the backend jar)\n"
            + "  • mvn -pl deliveryline-backend package         "
            + "(copies an already-built deliveryline-frontend/target/dist onto the classpath)\n"
            + "  • for an API-only run that uses the Vite dev server on :5173 instead, set "
            + "deliveryline.frontend.fail-on-missing-bundle=false");
  }
}
