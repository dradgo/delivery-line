package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * SPA deep-link fallback decision logic (embedded-frontend mode). When the Vite bundle is embedded
 * under {@code classpath:/static/}, a hard-load / refresh of a client-side route (e.g. {@code
 * /workflows/run_123}) reaches the backend as a {@code NoResourceFoundException}; the mapper serves
 * the app shell ({@code index.html}) so TanStack Router can resolve it client-side — but ONLY for
 * genuine browser navigations to non-API routes. API/management paths and missing assets keep their
 * JSON ProblemDetails. These tests pin the routing predicate in isolation (no Spring context, no
 * built bundle required).
 */
class ProblemDetailsMapperSpaFallbackTest {

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
            ProblemDetailsMapper.shouldServeSpaShell(
                "workflows/run_123", navigation("GET", "text/html,application/xhtml+xml")))
        .isTrue();
  }

  @Test
  void rootIsServedTheShell() {
    assertThat(ProblemDetailsMapper.shouldServeSpaShell("", navigation("GET", "text/html")))
        .isTrue();
  }

  @Test
  void apiPathKeepsJsonProblem() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell(
                "api/v1/workflows/missing", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void actuatorPathKeepsJsonProblem() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell(
                "actuator/health", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void apiDocsPathKeepsJsonProblem() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell("v3/api-docs", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void missingAssetKeepsJsonProblem() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell(
                "assets/app-abc123.js", navigation("GET", "text/html")))
        .isFalse();
  }

  @Test
  void jsonClientNeverGetsShell() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell(
                "workflows/run_123", navigation("GET", "application/json")))
        .isFalse();
  }

  @Test
  void nonGetNeverGetsShell() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell(
                "workflows/x", navigation("POST", "text/html")))
        .isFalse();
  }

  @Test
  void absentAcceptIsTreatedAsNavigation() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell("workflows/run_123", navigation("GET", null)))
        .isTrue();
  }

  @Test
  void leadingSlashIsTolerated() {
    assertThat(
            ProblemDetailsMapper.shouldServeSpaShell("/api/v1/x", navigation("GET", "text/html")))
        .isFalse();
  }
}
