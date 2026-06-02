package org.dradgo.application.integration.github;

import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Runtime exception raised by {@link GitHubAdapter} implementations when a GitHub call fails for
 * any reason that maps cleanly onto an {@link IntegrationFailureCategory}. The application service
 * is responsible for converting this to the appropriate {@code DomainException} or {@code
 * sync_status} transition (story 3.13 AC5; mirrors {@code LinearAdapterException}).
 *
 * <p>This type is intentionally domain-shaped — it carries an {@link IntegrationFailureCategory}
 * rather than HTTP status codes or GitHub REST error envelopes. The {@code
 * GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT} ArchUnit rule (story 3.13 Task 6) forbids
 * GitHub-specific types in the {@code application.integration.github} package; this exception
 * remains compliant.
 */
public final class GitHubAdapterException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final IntegrationFailureCategory failureCategory;
  private final Map<String, String> details;

  public GitHubAdapterException(IntegrationFailureCategory failureCategory, String message) {
    this(failureCategory, message, (Throwable) null);
  }

  public GitHubAdapterException(
      IntegrationFailureCategory failureCategory, String message, Map<String, String> details) {
    this(failureCategory, message, null, details);
  }

  public GitHubAdapterException(
      IntegrationFailureCategory failureCategory, String message, Throwable cause) {
    this(failureCategory, message, cause, Map.of());
  }

  public GitHubAdapterException(
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
