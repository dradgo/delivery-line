package org.dradgo.adapters.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import org.dradgo.application.security.RedactionPolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ProblemDetailsMapperTest {

  @Test
  void missingRedactionPolicyServiceFailsClosedForRejectedValues() throws Exception {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("objectMapper", new ObjectMapper());
    ProblemDetailsMapper mapper =
        new ProblemDetailsMapper(
            beanFactory.getBeanProvider(
                org.dradgo.application.security.RedactionPolicyService.class),
            beanFactory.getBeanProvider(ObjectMapper.class),
            beanFactory.getBeanProvider(SpaFallbackController.class));
    Method method =
        ProblemDetailsMapper.class.getDeclaredMethod(
            "sanitizeRejectedValue", String.class, Object.class);
    method.setAccessible(true);

    Object sanitized = method.invoke(mapper, "apiKey", "secret-token-value");

    assertEquals("[REDACTED]", sanitized);
  }

  @Test
  void redactionFailuresFailClosedForRejectedValues() throws Exception {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("objectMapper", new ObjectMapper());
    RedactionPolicyService redactionPolicyService = Mockito.mock(RedactionPolicyService.class);
    Mockito.when(redactionPolicyService.redact(Mockito.anyMap(), Mockito.isNull()))
        .thenThrow(new IllegalStateException("boom"));
    beanFactory.addBean("redactionPolicyService", redactionPolicyService);
    ProblemDetailsMapper mapper =
        new ProblemDetailsMapper(
            beanFactory.getBeanProvider(RedactionPolicyService.class),
            beanFactory.getBeanProvider(ObjectMapper.class),
            beanFactory.getBeanProvider(SpaFallbackController.class));
    Method method =
        ProblemDetailsMapper.class.getDeclaredMethod(
            "sanitizeRejectedValue", String.class, Object.class);
    method.setAccessible(true);

    Object sanitized =
        assertDoesNotThrow(() -> method.invoke(mapper, "apiKey", "secret-token-value"));

    assertEquals("[REDACTED]", sanitized);
  }

  // Story 2.28 — the new SPA delegation in handleNoResourceFound. The Docker-tier
  // SpaServingContractTest covers the happy path (shell served); these unit tests pin the two
  // genuinely-new fall-through branches that have no integration coverage: (a) the SPA controller
  // bean is absent (API-only / non-web slice → ObjectProvider.getIfAvailable() == null), and
  // (b) the predicate matched a browser navigation but no bundle is embedded (shellExists()==false,
  // the inert dev/test path). Both MUST still produce the JSON 404, never serve a shell.

  @Test
  void noResourceFoundReturnsJson404WhenSpaControllerBeanAbsent() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("objectMapper", new ObjectMapper());
    // No SpaFallbackController bean registered → getIfAvailable() returns null.
    ProblemDetailsMapper mapper =
        new ProblemDetailsMapper(
            beanFactory.getBeanProvider(RedactionPolicyService.class),
            beanFactory.getBeanProvider(ObjectMapper.class),
            beanFactory.getBeanProvider(SpaFallbackController.class));

    ResponseEntity<?> response =
        mapper.handleNoResourceFound(notFound("workflows/run_123"), navigationRequest());

    assertEquals(404, response.getStatusCode().value());
  }

  @Test
  void noResourceFoundFallsThroughToJson404WhenBundleAbsent() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("objectMapper", new ObjectMapper());
    SpaFallbackController controller = Mockito.mock(SpaFallbackController.class);
    Mockito.when(controller.shellExists()).thenReturn(false); // predicate matches, but no bundle
    beanFactory.addBean("spaFallbackController", controller);
    ProblemDetailsMapper mapper =
        new ProblemDetailsMapper(
            beanFactory.getBeanProvider(RedactionPolicyService.class),
            beanFactory.getBeanProvider(ObjectMapper.class),
            beanFactory.getBeanProvider(SpaFallbackController.class));

    ResponseEntity<?> response =
        mapper.handleNoResourceFound(notFound("workflows/run_123"), navigationRequest());

    assertEquals(404, response.getStatusCode().value());
    Mockito.verify(controller, Mockito.never()).serveShell(Mockito.anyString());
  }

  /** A GET browser navigation (accepts HTML) so {@code shouldServeSpaShell} returns true. */
  private static HttpServletRequest navigationRequest() {
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    Mockito.when(request.getMethod()).thenReturn("GET");
    Mockito.when(request.getHeader("Accept")).thenReturn("text/html");
    Mockito.when(request.getRequestURI()).thenReturn("/workflows/run_123");
    return request;
  }

  /** A {@link NoResourceFoundException} carrying the given resource path (constructor-agnostic). */
  private static NoResourceFoundException notFound(String resourcePath) {
    NoResourceFoundException exception = Mockito.mock(NoResourceFoundException.class);
    Mockito.when(exception.getResourcePath()).thenReturn(resourcePath);
    return exception;
  }
}
