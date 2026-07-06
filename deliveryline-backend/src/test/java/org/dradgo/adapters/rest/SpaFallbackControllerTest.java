package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.infrastructure.observability.RedactionLayoutHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Story 2.28 — SPA deep-link fallback decision logic + shell serving (embedded-frontend mode). When
 * the Vite bundle is embedded under {@code classpath:/static/}, a hard-load / refresh of a
 * client-side route (e.g. {@code /workflows/run_123}) reaches the backend as a {@code
 * NoResourceFoundException}; {@link SpaFallbackController} serves the app shell ({@code
 * index.html}) so TanStack Router can resolve it client-side — but ONLY for genuine browser
 * navigations to non-API routes. API/management paths and missing assets keep their JSON
 * ProblemDetails.
 *
 * <p>The 10 predicate cases were moved verbatim from {@code ProblemDetailsMapperSpaFallbackTest}
 * when the fallback was extracted into {@link SpaFallbackController} (story 2.28 AC1/S3) — they pin
 * the routing predicate in isolation (no Spring context, no built bundle required). The serving
 * cases rely on the test bundle stub at {@code src/test/resources/static/index.html}.
 */
@ExtendWith(OutputCaptureExtension.class)
class SpaFallbackControllerTest {

  // Wire an identity RedactionPolicyService into RedactionLayoutHolder so the %redactedMsg
  // converter
  // (active via logback-spring.xml's local,test,!demo springProfile block once any @SpringBootTest
  // has installed it on the shared LoggerContext) passes log messages through verbatim. Without
  // this
  // bridge the holder's `service` field is null on this plain JUnit test and the converter emits
  // the
  // fail-closed sentinel "[redaction-pending]", making the serveShell CapturedOutput.contains(...)
  // assertion order-dependent (green only when another live context happens to have the holder
  // wired). Same capture-and-restore precedent as WorkflowCommandsStatusHistoryTest and the
  // observability contract tests (story 1.19 review, P16).
  private static RedactionPolicyService priorService;

  @BeforeAll
  static void wireRedactionHolder() {
    priorService = RedactionLayoutHolder.currentForTesting();
    RedactionLayoutHolder.setRedactionService(
        new RedactionPolicyService(new DataClassificationService()));
  }

  @AfterAll
  static void unwireRedactionHolder() {
    if (priorService == null) {
      RedactionLayoutHolder.clearForTesting();
    } else {
      RedactionLayoutHolder.setRedactionService(priorService);
    }
  }

  private static MockHttpServletRequest navigation(String method, String accept) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/");
    if (accept != null) {
      request.addHeader("Accept", accept);
    }
    return request;
  }

  @Test
  void extensionlessBrowserRouteIsServedTheShell() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "workflows/run_123", navigation("GET", "text/html,application/xhtml+xml")))
        .isTrue();
  }

  @Test
  void rootIsServedTheShell() {
    assertThat(SpaFallbackController.shouldServeSpaShell("", navigation("GET", "text/html")))
        .isTrue();
  }

  @Test
  void apiPathKeepsJsonProblem() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "api/v1/workflows/missing", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void actuatorPathKeepsJsonProblem() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "actuator/health", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void apiDocsPathKeepsJsonProblem() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "v3/api-docs", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void missingAssetKeepsJsonProblem() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "assets/app-abc123.js", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void jsonClientNeverGetsShell() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "workflows/run_123", navigation("GET", "application/json")))
        .isFalse();
  }

  @Test
  void nonGetNeverGetsShell() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell(
                "workflows/x", navigation("POST", "text/html")))
        .isFalse();
  }

  @Test
  void absentAcceptIsTreatedAsNavigation() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell("workflows/run_123", navigation("GET", null)))
        .isTrue();
  }

  @Test
  void leadingSlashIsTolerated() {
    assertThat(
            SpaFallbackController.shouldServeSpaShell("/api/v1/x", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void serveShellReturnsTheNoStoreHtmlShellAndLogsAtInfo(CapturedOutput output) {
    SpaFallbackController controller = new SpaFallbackController();
    assertThat(controller.shellExists())
        .as("test bundle stub at src/test/resources/static/index.html must be on the classpath")
        .isTrue();

    ResponseEntity<org.springframework.core.io.Resource> response =
        controller.serveShell("workflows/run_123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(response.getBody()).isSameAs(SpaFallbackController.SPA_SHELL);
    assertThat(output).contains("spa_fallback serving shell for path=workflows/run_123");
  }
}
