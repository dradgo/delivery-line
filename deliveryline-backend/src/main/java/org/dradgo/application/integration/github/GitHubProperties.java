package org.dradgo.application.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the real GitHub integration subsystem (story 3.14 Task 1).
 *
 * <p>{@code token} is sourced from the {@code GITHUB_TOKEN} environment variable via standard
 * Spring property placeholder resolution — never hardcoded. The accessor is marked {@link
 * JsonIgnore} so accidental serialization (Actuator/management endpoints, logging frameworks that
 * walk POJOs) cannot leak the PAT; {@link #toString()} is overridden to redact. Mirrors {@code
 * LinearProperties} (story 1.14 Task 4).
 *
 * <p>Lives in {@code application.integration.github} (not {@code infrastructure.config}) so the
 * "application must not depend on infrastructure" ArchUnit rule stays clean — {@code
 * GitHubRealAdapter} reads its config from this record.
 *
 * <p>Decision D6 (memory {@code validated-config-needs-test-yaml}): the compact constructor
 * <strong>normalizes-with-defaults and never throws</strong>. The bean binds profile-neutrally
 * (registered via {@code @EnableConfigurationProperties} on {@code GitHubConfiguration}), so it
 * instantiates in every {@code @SpringBootTest} tier even when no {@code deliveryline.github.*}
 * keys are present. A blank token is allowed at bind time and remains startup-safe under {@code
 * github-real}; the doctor probe reports {@code DOCTOR_GITHUB_TOKEN_MISSING} so diagnostics can
 * surface the missing secret. This avoids the validated-config test-yaml trap without requiring
 * matching keys.
 */
@ConfigurationProperties("deliveryline.github")
public record GitHubProperties(
    String token, String baseUrl, String apiVersion, int rateLimitWarnThreshold, Timeout timeout) {

  /** GitHub REST API v3 base URL. */
  public static final String DEFAULT_BASE_URL = "https://api.github.com";

  /** Pinned GitHub REST API version (AC1: {@code X-GitHub-Api-Version}). */
  public static final String DEFAULT_API_VERSION = "2022-11-28";

  /** AC5: WARN when {@code X-RateLimit-Remaining} drops below this threshold. */
  public static final int DEFAULT_RATE_LIMIT_WARN_THRESHOLD = 100;

  public GitHubProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    apiVersion = apiVersion == null || apiVersion.isBlank() ? DEFAULT_API_VERSION : apiVersion;
    // Normalize (never throw) so the profile-neutral binding is safe under every test tier (D6).
    rateLimitWarnThreshold =
        rateLimitWarnThreshold <= 0 ? DEFAULT_RATE_LIMIT_WARN_THRESHOLD : rateLimitWarnThreshold;
    timeout = timeout == null ? Timeout.defaults() : timeout;
  }

  public static GitHubProperties defaults() {
    return new GitHubProperties(
        null,
        DEFAULT_BASE_URL,
        DEFAULT_API_VERSION,
        DEFAULT_RATE_LIMIT_WARN_THRESHOLD,
        Timeout.defaults());
  }

  @JsonIgnore
  @Override
  public String token() {
    return token;
  }

  /** True when a non-blank PAT is configured. */
  @JsonIgnore
  public boolean hasToken() {
    return token != null && !token.isBlank();
  }

  @Override
  public String toString() {
    return "GitHubProperties{baseUrl="
        + baseUrl
        + ", apiVersion="
        + apiVersion
        + ", rateLimitWarnThreshold="
        + rateLimitWarnThreshold
        + ", timeout="
        + timeout
        + ", token=<redacted>}";
  }

  public record Timeout(long connectMs, long readMs) {

    public Timeout {
      // Normalize (never throw) — see the class-level D6 note.
      connectMs = connectMs <= 0L ? 5_000L : connectMs;
      readMs = readMs <= 0L ? 30_000L : readMs;
    }

    public Duration connectDuration() {
      return Duration.ofMillis(Math.min(connectMs, Integer.MAX_VALUE));
    }

    public Duration readDuration() {
      return Duration.ofMillis(Math.min(readMs, Integer.MAX_VALUE));
    }

    public static Timeout defaults() {
      return new Timeout(5_000L, 30_000L);
    }
  }
}
