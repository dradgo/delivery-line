package org.dradgo.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.dradgo.application.integration.bitbucket.BitbucketProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Bitbucket integration subsystem wiring (story 3i-3 / FR82). The Bitbucket-repository-host twin of
 * {@code GitHubConfiguration}.
 *
 * <ul>
 *   <li>binds {@link BitbucketProperties} from the {@code deliveryline.bitbucket.*} namespace
 *       (profile-neutral with safe defaults — see {@link BitbucketProperties} Decision D6),
 *   <li>defines the {@code bitbucketRestClient} bean under the {@code bitbucket-real} profile only,
 *   <li>asserts that {@code bitbucket-mock} and {@code bitbucket-real} are mutually exclusive.
 * </ul>
 *
 * <p>The exclusivity guard reads only the active profile names (mirrors {@code
 * assertExclusiveGitHubProfile}). The {@code bitbucketRestClient} bean is present only under {@code
 * bitbucket-real}; {@code BitbucketProfileWiringContractTest} asserts no such bean exists under
 * {@code bitbucket-mock} (no Bitbucket network call under the mock). The repository-host {@code
 * kind} classpath fail-fast lives in {@code GitHubConfiguration.assertSupportedRepositoryHostKind}
 * (which now accepts {@code kind=bitbucket}); {@link BitbucketProperties} is <em>not</em>
 * re-registered here to avoid a duplicate {@code RepositoryHostProperties} binding.
 */
@Configuration
@EnableConfigurationProperties(BitbucketProperties.class)
public class BitbucketConfiguration {

  static final String MOCK_PROFILE = "bitbucket-mock";
  static final String REAL_PROFILE = "bitbucket-real";

  public BitbucketConfiguration(Environment environment) {
    assertExclusiveBitbucketProfile(environment);
  }

  /**
   * Provisions the dedicated {@code bitbucketRestClient} bean used by {@code BitbucketRealAdapter}
   * and the doctor {@code bitbucket-real} auth probe. Only present under {@code bitbucket-real}.
   * Mirrors {@code GitHubConfiguration.gitHubRestClient}: a {@code SimpleClientHttpRequestFactory}
   * carries the connect/read timeouts and a request interceptor reads the secret at request time.
   *
   * <p>The interceptor resolves auth exactly once per request: a per-request credential-override
   * attribute (the project-scoped stored secret, set by the connectivity probe) takes precedence
   * over the host-env token. The resolved secret is self-describing — a {@code
   * workspace:app_password} pair (containing a {@code ':'}) is sent as HTTP Basic; a bare access
   * token is sent as Bearer. The secret is never logged, never embedded in a URL. A blank token is
   * not startup-fatal; the doctor probe reports {@code DOCTOR_BITBUCKET_TOKEN_MISSING} under {@code
   * bitbucket-real} before attempting a network call.
   */
  @Bean(name = "bitbucketRestClient")
  @Profile(REAL_PROFILE)
  public RestClient bitbucketRestClient(BitbucketProperties properties) {
    Objects.requireNonNull(properties, "properties");
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.timeout().connectDuration());
    requestFactory.setReadTimeout(properties.timeout().readDuration());
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .defaultHeader("Accept", "application/json")
        .defaultHeader("User-Agent", "DeliveryLine/1.0 (bitbucket-real)")
        .requestInterceptor(
            (request, body, execution) -> {
              Object override =
                  request.getAttributes().get(BitbucketProperties.CREDENTIAL_OVERRIDE_ATTRIBUTE);
              String secret =
                  (override instanceof String s && !s.isBlank()) ? s : properties.token();
              String header = authorizationHeader(secret);
              if (header != null) {
                request.getHeaders().set("Authorization", header);
              }
              return execution.execute(request, body);
            })
        .build();
  }

  /**
   * Builds the {@code Authorization} header value for a self-describing Bitbucket secret: a {@code
   * workspace:app_password} pair (contains {@code ':'}) becomes HTTP {@code Basic base64(secret)};
   * a bare access token becomes {@code Bearer <token>}. Returns {@code null} for a blank/absent
   * secret (no header set — the doctor probe surfaces the missing token). Package-private for the
   * profile-wiring contract test.
   */
  static String authorizationHeader(String secret) {
    if (secret == null || secret.isBlank()) {
      return null;
    }
    if (secret.indexOf(':') >= 0) {
      String encoded = Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
      return "Basic " + encoded;
    }
    return "Bearer " + secret;
  }

  private static void assertExclusiveBitbucketProfile(Environment environment) {
    boolean mockActive = isProfileActive(environment, MOCK_PROFILE);
    boolean realActive = isProfileActive(environment, REAL_PROFILE);
    if (mockActive && realActive) {
      throw new IllegalStateException(
          "Bitbucket profile conflict: "
              + MOCK_PROFILE
              + " and "
              + REAL_PROFILE
              + " are mutually exclusive but both are active. Activate exactly one.");
    }
  }

  private static boolean isProfileActive(Environment environment, String profile) {
    for (String active : environment.getActiveProfiles()) {
      if (active.equals(profile)) {
        return true;
      }
    }
    return false;
  }
}
