package org.dradgo.adapters.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Story 2.13 logging-instrumentation pin: every typed {@link DomainException} surfaced by the
 * mapper MUST emit a WARN log line carrying {@code code}, {@code status}, {@code method}, and
 * {@code path} so an operator can correlate request id → outcome from log scrapes alone. The line
 * MUST NOT include human-text {@code title} / {@code detail} or caller-private payload bytes —
 * mirrors the architecture-line-712 rule the Problem Details body itself follows.
 */
class ProblemDetailsMapperLoggingContractTest {

  private ProblemDetailsMapper mapper;
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

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

    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(ProblemDetailsMapper.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void typedDomainExceptionEmitsWarnLineWithCodeStatusMethodPath() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/workflows/run_abc/approve-spec");
    when(request.getMethod()).thenReturn("POST");

    DomainException error =
        new DomainException(
            DomainErrorCode.APPROVAL_VERSION_MISMATCH,
            "Artifact version is stale",
            Map.of("expectedArtifactVersion", 1, "currentArtifactVersion", 3));

    mapper.handleDomainException(error, request);

    ILoggingEvent warn =
        appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected WARN log for DomainException"));
    String formatted = warn.getFormattedMessage();
    assertThat(formatted)
        .contains("code=" + DomainErrorCode.APPROVAL_VERSION_MISMATCH.value())
        .contains("status=409")
        .contains("method=POST")
        .contains("path=/api/v1/workflows/run_abc/approve-spec");
    // Human-readable title/detail must not leak into the log line.
    assertThat(formatted).doesNotContain("Artifact version is stale");
  }
}
