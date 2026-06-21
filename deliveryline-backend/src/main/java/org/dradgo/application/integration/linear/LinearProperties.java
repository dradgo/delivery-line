package org.dradgo.application.integration.linear;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the Linear integration subsystem (story 1.14 Task 4).
 *
 * <p>{@code apiToken} is sourced from the {@code LINEAR_API_TOKEN} environment variable via
 * standard Spring property placeholder resolution — never hardcoded. The accessor is marked {@link
 * JsonIgnore} so accidental serialization (Actuator/management endpoints, logging frameworks that
 * walk POJOs) cannot leak the token; {@link #toString()} is overridden to redact.
 *
 * <p>Lives in {@code application.integration.linear} (not {@code infrastructure.config}) so the
 * "application must not depend on infrastructure" ArchUnit rule stays clean — the {@code
 * LinearRealAdapter} reads its config from this record. Mirrors the {@code
 * application.runner.RunnerProperties} pattern from story 1.13.
 *
 * <p>Polling defaults to a 60-second interval per AC9 ("default 60s"). The {@code polling.enabled}
 * flag (default true) lets ops keep the real adapter wired but mute the scheduler when no upstream
 * consumer requires intake — the scheduler bean is `@ConditionalOnProperty` against this flag.
 *
 * <p>Story 3a.4 adds two <strong>optional</strong> poll-scoping fields, {@code teamKey} and {@code
 * projectId}, appended at the END of the component list to keep the constructor fan-out minimal
 * (only {@link #defaults()} and the adapter unit test construct this record directly). Both default
 * to {@code null}; when both are absent the poll behaves byte-identically to before (the whole
 * token-scoped workspace is polled). They are deliberately <strong>unvalidated</strong> — absence
 * is the valid default — so no {@code @SpringBootTest} context fails on a missing property, and the
 * test {@code application.yml} needs no mirroring entry. A Linear "workspace" is determined by the
 * API token and is NOT a filterable GraphQL field; the configurable lever is team/project scoping
 * <em>within</em> the token's workspace (these are non-secret identifiers, safe to log).
 */
@ConfigurationProperties("deliveryline.linear")
public record LinearProperties(
    String apiToken,
    String baseUrl,
    long pollIntervalMs,
    int pollBatchSize,
    Timeout timeout,
    double staleThresholdMultiplier,
    Polling polling,
    String teamKey,
    String projectId) {

  /**
   * Story 3c-8 (P1) — per-request RestClient attribute key carrying a one-off credential override
   * (the project-scoped stored API token) for the connectivity probe. When set on a request, the
   * {@code linearRestClient} interceptor prefers it over the host-env {@link #apiToken()};
   * otherwise the host-env token is used (the AC3 fallback). Never logged.
   */
  public static final String CREDENTIAL_OVERRIDE_ATTRIBUTE =
      "deliveryline.linear.credentialOverride";

  public LinearProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.linear.app/graphql" : baseUrl;
    if (pollIntervalMs <= 0L) {
      throw new IllegalArgumentException(
          "deliveryline.linear.poll-interval-ms must be positive: " + pollIntervalMs);
    }
    if (pollBatchSize <= 0) {
      throw new IllegalArgumentException(
          "deliveryline.linear.poll-batch-size must be positive: " + pollBatchSize);
    }
    if (staleThresholdMultiplier <= 0.0d) {
      throw new IllegalArgumentException(
          "deliveryline.linear.stale-threshold-multiplier must be positive: "
              + staleThresholdMultiplier);
    }
    timeout = timeout == null ? Timeout.defaults() : timeout;
    polling = polling == null ? Polling.defaults() : polling;
    // Story 3a.4 — normalize the optional scope identifiers: blank ⇒ null (absent scope), and strip
    // surrounding whitespace so a stray `" FIN "` (env var / quoted YAML) is not emitted as an
    // `eq:" FIN "` filter that Linear never matches → silent empty poll.
    teamKey = teamKey == null || teamKey.isBlank() ? null : teamKey.strip();
    projectId = projectId == null || projectId.isBlank() ? null : projectId.strip();
  }

  public static LinearProperties defaults() {
    return new LinearProperties(
        null,
        "https://api.linear.app/graphql",
        60_000L,
        50,
        Timeout.defaults(),
        2.0d,
        Polling.defaults(),
        null,
        null);
  }

  @JsonIgnore
  @Override
  public String apiToken() {
    return apiToken;
  }

  @Override
  public String toString() {
    return "LinearProperties{baseUrl="
        + baseUrl
        + ", pollIntervalMs="
        + pollIntervalMs
        + ", pollBatchSize="
        + pollBatchSize
        + ", timeout="
        + timeout
        + ", staleThresholdMultiplier="
        + staleThresholdMultiplier
        + ", polling="
        + polling
        + ", teamKey="
        + teamKey
        + ", projectId="
        + projectId
        + ", apiToken=<redacted>}";
  }

  public record Timeout(long connectMs, long readMs) {

    public Timeout {
      if (connectMs <= 0L) {
        throw new IllegalArgumentException(
            "deliveryline.linear.timeout.connect-ms must be positive: " + connectMs);
      }
      if (readMs <= 0L) {
        throw new IllegalArgumentException(
            "deliveryline.linear.timeout.read-ms must be positive: " + readMs);
      }
    }

    public static Timeout defaults() {
      return new Timeout(5_000L, 30_000L);
    }
  }

  public record Polling(boolean enabled) {

    public static Polling defaults() {
      return new Polling(true);
    }
  }
}
