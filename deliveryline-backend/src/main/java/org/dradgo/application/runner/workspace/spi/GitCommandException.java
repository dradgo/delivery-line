package org.dradgo.application.runner.workspace.spi;

import java.util.Map;
import java.util.Objects;
import org.dradgo.domain.registry.IntegrationFailureCategory;

/**
 * Typed failure raised by {@link GitCommandPort} implementations (story 3.9 Decision D1/D4).
 * Carries a domain-shaped {@link IntegrationFailureCategory} so the application layer can classify
 * a {@code git} failure (push rejected, branch protection, network, auth) without ever seeing
 * {@code Process}/exit-code/JGit types (Trap T1).
 *
 * <p>The message + details are pre-redacted by the adapter (a git child-process's stdout/stderr is
 * routed through {@code RedactionPolicyService} before it reaches here) — never carry a raw token,
 * auth URL, or credential-helper value (AC10/Trap T5).
 */
public final class GitCommandException extends RuntimeException {

  private final IntegrationFailureCategory failureCategory;
  private final transient Map<String, String> details;

  public GitCommandException(IntegrationFailureCategory failureCategory, String message) {
    this(failureCategory, message, null, Map.of());
  }

  public GitCommandException(
      IntegrationFailureCategory failureCategory, String message, Throwable cause) {
    this(failureCategory, message, cause, Map.of());
  }

  public GitCommandException(
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
