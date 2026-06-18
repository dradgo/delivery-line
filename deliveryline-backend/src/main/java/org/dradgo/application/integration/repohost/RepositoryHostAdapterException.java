package org.dradgo.application.integration.repohost;

import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Runtime exception raised by {@link RepositoryHostAdapter} implementations when a host call fails
 * for any reason that maps cleanly onto an {@link IntegrationFailureCategory}. The application
 * service is responsible for converting this to the appropriate {@code DomainException} or {@code
 * sync_status} transition (renamed from story 3.13's {@code GitHubAdapterException} by story 3.33;
 * mirrors {@code TicketSourceAdapterException}).
 *
 * <p>This type is intentionally domain-shaped — it carries an {@link IntegrationFailureCategory}
 * rather than HTTP status codes or host REST error envelopes. The {@code
 * REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule forbids host-specific transport
 * types in the {@code application.integration.repohost} package; this exception remains compliant.
 */
public final class RepositoryHostAdapterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final IntegrationFailureCategory failureCategory;
  private final Map<String, String> details;

  public RepositoryHostAdapterException(
      IntegrationFailureCategory failureCategory, String message) {
    this(failureCategory, message, (Throwable) null);
  }

  public RepositoryHostAdapterException(
      IntegrationFailureCategory failureCategory, String message, Map<String, String> details) {
    this(failureCategory, message, null, details);
  }

  public RepositoryHostAdapterException(
      IntegrationFailureCategory failureCategory, String message, Throwable cause) {
    this(failureCategory, message, cause, Map.of());
  }

  public RepositoryHostAdapterException(
      IntegrationFailureCategory failureCategory,
      String message,
      Throwable cause,
      Map<String, String> details) {
    super(message, cause);
    this.failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
    this.details = Map.copyOf(Objects.requireNonNull(details, "details"));
  }

  public IntegrationFailureCategory failureCategory() {
    return failureCategory;
  }

  public Map<String, String> details() {
    return details;
  }
}
