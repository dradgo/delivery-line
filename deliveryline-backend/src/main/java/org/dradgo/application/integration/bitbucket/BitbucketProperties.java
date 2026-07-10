package org.dradgo.application.integration.bitbucket;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the real Bitbucket integration subsystem (story 3i-3 / FR82).
 * The Bitbucket-repository-host twin of {@code GitHubProperties}.
 *
 * <p>{@code token} is the single Bitbucket secret, sourced from the {@code BITBUCKET_TOKEN}
 * environment variable via standard Spring property placeholder resolution — never hardcoded. Its
 * encoding is <strong>self-describing</strong> (story 3i-3 Task 5 single-secret decision):
 *
 * <ul>
 *   <li>a {@code workspace:app_password} pair (contains a {@code ':'}) → sent as HTTP {@code
 *       Authorization: Basic base64(token)},
 *   <li>a bare access token (no {@code ':'}) → sent as {@code Authorization: Bearer <token>}.
 * </ul>
 *
 * The accessor is marked {@link JsonIgnore} so accidental serialization (Actuator/management
 * endpoints, POJO-walking log frameworks) cannot leak the secret; {@link #toString()} is overridden
 * to redact. Mirrors {@code GitHubProperties}.
 *
 * <p>Lives in {@code application.integration.bitbucket} (not {@code infrastructure.config}) so the
 * "application must not depend on infrastructure" ArchUnit rule stays clean — {@code
 * BitbucketRealAdapter} reads its config from this record.
 *
 * <p>Decision D6 (memory {@code validated-config-needs-test-yaml}): the compact constructor
 * <strong>normalizes-with-defaults and never throws</strong>. The bean binds profile-neutrally
 * (registered via {@code @EnableConfigurationProperties} on {@code BitbucketConfiguration}), so it
 * instantiates in every {@code @SpringBootTest} tier even when no {@code deliveryline.bitbucket.*}
 * keys are present. A blank token is allowed at bind time and remains startup-safe under {@code
 * bitbucket-real}; the doctor probe reports {@code DOCTOR_BITBUCKET_TOKEN_MISSING} so diagnostics
 * can surface the missing secret.
 */
@ConfigurationProperties("deliveryline.bitbucket")
public record BitbucketProperties(String token, String baseUrl, Timeout timeout) {

  /**
   * Bitbucket Cloud API host (base URL). The host only — mirroring the GitHub adapter, the {@code
   * /2.0} REST API version segment is carried explicitly in each request path so an absolute-path
   * request never clobbers a versioned base path.
   */
  public static final String DEFAULT_BASE_URL = "https://api.bitbucket.org";

  /**
   * Story 3c-8 (P1) — per-request RestClient attribute key carrying a one-off credential override
   * (the project-scoped stored Bitbucket secret) for the connectivity probe. When set on a request,
   * the {@code bitbucketRestClient} interceptor prefers it over the host-env {@link #token()};
   * otherwise the host-env token is used (the AC3 fallback). Never logged.
   */
  public static final String CREDENTIAL_OVERRIDE_ATTRIBUTE =
      "deliveryline.bitbucket.credentialOverride";

  public BitbucketProperties {
    // Normalize (never throw) so the profile-neutral binding is safe under every test tier (D6).
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
    timeout = timeout == null ? Timeout.defaults() : timeout;
  }

  public static BitbucketProperties defaults() {
    return new BitbucketProperties(null, DEFAULT_BASE_URL, Timeout.defaults());
  }

  @JsonIgnore
  @Override
  public String token() {
    return token;
  }

  /** True when a non-blank Bitbucket secret is configured. */
  @JsonIgnore
  public boolean hasToken() {
    return token != null && !token.isBlank();
  }

  @Override
  public String toString() {
    return "BitbucketProperties{baseUrl="
        + baseUrl
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
