package org.dradgo.application.integration.ticketsource;

import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Runtime exception raised by {@link TicketSourceAdapter} implementations when a source call fails
 * for any reason that maps cleanly onto an {@link IntegrationFailureCategory}. The application
 * service is responsible for converting this to the appropriate {@code DomainException} or {@code
 * sync_status} transition.
 *
 * <p>This type is intentionally domain-shaped — it carries an {@link IntegrationFailureCategory}
 * rather than HTTP status codes or vendor error envelopes. The {@code
 * TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule forbids vendor-specific transport
 * types in the {@code application.integration.ticketsource} package; this exception remains
 * compliant (renamed from story 1.14's {@code LinearAdapterException} by story 3.32).
 */
public final class TicketSourceAdapterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final IntegrationFailureCategory failureCategory;

  public TicketSourceAdapterException(IntegrationFailureCategory failureCategory, String message) {
    this(failureCategory, message, null);
  }

  public TicketSourceAdapterException(
      IntegrationFailureCategory failureCategory, String message, Throwable cause) {
    super(message, cause);
    this.failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
  }

  public IntegrationFailureCategory failureCategory() {
    return failureCategory;
  }
}
