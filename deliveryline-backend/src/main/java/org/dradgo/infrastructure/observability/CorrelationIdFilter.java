package org.dradgo.infrastructure.observability;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request-scoped {@link Filter} that stamps {@code correlationId} on MDC at the very front of the
 * servlet chain, echoing the resolved value on the response so clients can correlate logs to API
 * calls.
 *
 * <p>If the inbound {@code X-Correlation-Id} header is missing OR is not a structurally valid UUID
 * (any version, parsed via {@link UuidV7Generator#tryParse(String)}), the filter generates a fresh
 * UUIDv7 via the injected {@link UuidV7Generator}. The supplied value is sanitised via {@link
 * MdcKeys#sanitizeForLog(String)} before being placed on MDC so a malicious header value carrying
 * CR/LF/TAB control characters cannot forge a synthetic structured-log line.
 *
 * <p>Removes the MDC key in a {@code finally} block — see AC2 of story 1.19. Test pin: {@code
 * CorrelationIdMdcLeakageTest}.
 */
public class CorrelationIdFilter implements Filter {

  public static final String HEADER = "X-Correlation-Id";
  private static final int MAX_HEADER_LENGTH = 256;
  private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

  private final UuidV7Generator uuidV7Generator;

  public CorrelationIdFilter(UuidV7Generator uuidV7Generator) {
    this.uuidV7Generator = uuidV7Generator;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String supplied = null;
    if (request instanceof HttpServletRequest http) {
      supplied = http.getHeader(HEADER);
    }
    if (supplied != null && supplied.length() > MAX_HEADER_LENGTH) {
      log.warn(
          "rejecting oversized X-Correlation-Id header length={} maxLength={}",
          supplied.length(),
          MAX_HEADER_LENGTH);
      supplied = null;
    }
    Optional<String> parsed = UuidV7Generator.tryParse(supplied);
    String resolved = MdcKeys.sanitizeForLog(parsed.orElseGet(uuidV7Generator::generate));

    String prior = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, resolved);
    try {
      if (response instanceof HttpServletResponse http) {
        http.setHeader(HEADER, resolved);
      }
      chain.doFilter(request, response);
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, prior);
    }
  }
}
