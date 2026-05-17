package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Story 1.19 AC8 (Open Clarification 4 default): {@link ProblemDetailsMapper} MUST stamp a
 * top-level {@code correlationId} extension field on every emitted Problem Details payload,
 * populated from MDC at the catch site. {@code instance} stays as the request path (the RFC 9457
 * convention).
 */
class ProblemDetailsCorrelationIdContractTest {

  private ProblemDetailsMapper mapper;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ObjectProvider<RedactionPolicyService> redactionProvider = mock(ObjectProvider.class);
    when(redactionProvider.getIfAvailable()).thenReturn(null);
    ObjectProvider<ObjectMapper> objectMapperProvider = mock(ObjectProvider.class);
    when(objectMapperProvider.getIfAvailable(
            org.mockito.ArgumentMatchers.<java.util.function.Supplier<ObjectMapper>>any()))
        .thenReturn(new ObjectMapper());
    mapper = new ProblemDetailsMapper(redactionProvider, objectMapperProvider);
    MDC.clear();
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void problemDetailsIncludesCorrelationIdFromMdc() {
    MDC.put(MdcKeys.CORRELATION_ID, "corr-problem-details-1");
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/workflow/run/run_abc");

    DomainException error =
        new DomainException(
            DomainErrorCode.RUN_NOT_FOUND,
            "Workflow run not found: run_abc",
            Map.of("runId", "run_abc"));

    ResponseEntity<ProblemDetail> response = mapper.handleDomainException(error, request);

    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    Map<String, Object> props = body.getProperties();
    assertThat(props).containsEntry(MdcKeys.CORRELATION_ID, "corr-problem-details-1");
    assertThat(body.getInstance()).hasToString("/api/workflow/run/run_abc");
  }

  @Test
  void problemDetailsOmitsCorrelationIdWhenMdcIsEmpty() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/workflow/run/run_abc");

    DomainException error =
        new DomainException(
            DomainErrorCode.RUN_NOT_FOUND,
            "Workflow run not found: run_abc",
            Map.of("runId", "run_abc"));

    ResponseEntity<ProblemDetail> response = mapper.handleDomainException(error, request);

    ProblemDetail body = response.getBody();
    assertThat(body).isNotNull();
    Map<String, Object> props = body.getProperties();
    assertThat(props).doesNotContainKey(MdcKeys.CORRELATION_ID);
  }
}
