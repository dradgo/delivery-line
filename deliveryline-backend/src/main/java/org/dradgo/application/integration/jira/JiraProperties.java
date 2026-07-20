package org.dradgo.application.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the JIRA integration subsystem (story 3i-1 Task 2). Mirrors
 * {@code LinearProperties} / {@code GitHubProperties}.
 *
 * <p>{@code apiToken} is an optional host-env fallback bearer token. In normal project-scoped
 * operation the resolver binds a decrypted OAuth access token to each request via {@link
 * #CREDENTIAL_OVERRIDE_ATTRIBUTE}, so application startup does not require a JIRA token. The {@code
 * apiToken} accessor is marked {@link JsonIgnore} so accidental serialization (Actuator/management
 * endpoints, logging frameworks that walk POJOs) cannot leak the token; {@link #toString()} is
 * overridden to redact. {@code baseUrl} (e.g. {@code https://acme.atlassian.net}) is
 * <strong>non-secret deployment config</strong> - {@code buildSourceTicketUrl} derives the browse
 * link from it without decrypting any secret.
 *
 * <p>Lives in {@code application.integration.jira} (not {@code infrastructure.config}) so the
 * "application must not depend on infrastructure" ArchUnit rule stays clean — {@code
 * JiraRealAdapter} reads its config from this record.
 *
 * <p>Decision (memory {@code validated-config-needs-test-yaml}): the compact constructor
 * <strong>normalizes-with-defaults and never throws</strong> (the GitHubProperties D6 pattern), so
 * it binds profile-neutrally in every {@code @SpringBootTest} tier even when no {@code
 * deliveryline.jira.*} keys are present. A blank token/email is allowed at bind time and is fatal
 * only when the {@code jira-real} profile activates the {@code jiraRestClient} bean; the doctor
 * {@code jira-auth} probe surfaces a missing secret for diagnostics.
 */
@ConfigurationProperties("deliveryline.jira")
public record JiraProperties(
    String apiToken, String baseUrl, String email, int pollBatchSize, Timeout timeout) {

  /**
   * Story 3c-8 (P1) — per-request RestClient attribute key carrying a one-off credential override
   * (the project-scoped stored API token) for the connectivity probe. When set on a request, the
   * {@code jiraRestClient} interceptor prefers it over the host-env {@link #apiToken()} (the
   * account email is deployment-level per OQ-2); otherwise the host-env token is used (the AC3
   * fallback). Never logged.
   */
  public static final String CREDENTIAL_OVERRIDE_ATTRIBUTE = "deliveryline.jira.credentialOverride";

  /** Default JQL/paging batch size for {@link #pollBatchSize()}. */
  public static final int DEFAULT_POLL_BATCH_SIZE = 50;

  public JiraProperties {
    // Blank ⇒ null so an unset/quoted-empty base-url/email is treated as absent (never spliced into
    // an auth header or a browse URL). Normalize (never throw) — see the class-level note.
    baseUrl = baseUrl == null || baseUrl.isBlank() ? null : stripTrailingSlash(baseUrl.strip());
    email = email == null || email.isBlank() ? null : email.strip();
    pollBatchSize = pollBatchSize <= 0 ? DEFAULT_POLL_BATCH_SIZE : pollBatchSize;
    timeout = timeout == null ? Timeout.defaults() : timeout;
  }

  public static JiraProperties defaults() {
    return new JiraProperties(null, null, null, DEFAULT_POLL_BATCH_SIZE, Timeout.defaults());
  }

  @JsonIgnore
  @Override
  public String apiToken() {
    return apiToken;
  }

  /** True when a non-blank API token is configured (host-env fallback presence). */
  @JsonIgnore
  public boolean hasToken() {
    return apiToken != null && !apiToken.isBlank();
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  @Override
  public String toString() {
    return "JiraProperties{baseUrl="
        + baseUrl
        + ", email="
        + (email == null ? "unset" : "present")
        + ", pollBatchSize="
        + pollBatchSize
        + ", timeout="
        + timeout
        + ", apiToken=<redacted>}";
  }

  public record Timeout(long connectMs, long readMs) {

    public Timeout {
      // Normalize (never throw) — see the class-level D6 note.
      connectMs = connectMs <= 0L ? 5_000L : connectMs;
      readMs = readMs <= 0L ? 30_000L : readMs;
    }

    public static Timeout defaults() {
      return new Timeout(5_000L, 30_000L);
    }
  }
}
