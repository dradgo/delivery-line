package org.dradgo.application.integration.linear;

import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Runtime exception raised by {@link LinearAdapter} implementations when a Linear call fails for
 * any reason that maps cleanly onto an {@link IntegrationFailureCategory}. The application service
 * is responsible for converting this to the appropriate {@code DomainException} or {@code
 * sync_status} transition (story 1.14 Task 5 / Task 8 logging contract).
 *
 * <p>This type is intentionally domain-shaped — it carries an {@link IntegrationFailureCategory}
 * rather than HTTP status codes or GraphQL error envelopes. The {@code
 * LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (Task 7) forbids GraphQL-specific types in
 * the {@code application.integration.linear} package; this exception remains compliant.
 */
public final class LinearAdapterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final IntegrationFailureCategory failureCategory;

  public LinearAdapterException(IntegrationFailureCategory failureCategory, String message) {
    this(failureCategory, message, null);
  }

  public LinearAdapterException(
      IntegrationFailureCategory failureCategory, String message, Throwable cause) {
    super(message, cause);
    this.failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
  }

  public IntegrationFailureCategory failureCategory() {
    return failureCategory;
  }
}
