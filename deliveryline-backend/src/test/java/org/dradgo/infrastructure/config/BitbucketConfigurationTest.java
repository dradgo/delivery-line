package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.dradgo.application.integration.bitbucket.BitbucketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

/**
 * Story 3i-3 (FR82) — {@link BitbucketConfiguration} wiring: the mock/real exclusivity guard and
 * the self-describing single-secret {@code Authorization} header decision (a {@code
 * workspace:app_password} pair → HTTP Basic; a bare access token → Bearer).
 */
class BitbucketConfigurationTest {

  private static Environment activeProfiles(String... profiles) {
    Environment environment = mock(Environment.class);
    when(environment.getActiveProfiles()).thenReturn(profiles);
    return environment;
  }

  @Test
  void bothProfilesActiveFailsFast() {
    assertThatThrownBy(
            () -> new BitbucketConfiguration(activeProfiles("bitbucket-mock", "bitbucket-real")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Bitbucket profile conflict");
  }

  @Test
  void singleProfileActivatesWithoutFailFast() {
    assertThatCode(() -> new BitbucketConfiguration(activeProfiles("bitbucket-real")))
        .doesNotThrowAnyException();
    assertThatCode(() -> new BitbucketConfiguration(activeProfiles())).doesNotThrowAnyException();
  }

  @Test
  void workspaceAppPasswordPairBecomesBasicHeader() {
    String secret = "acme-workspace:app-password-value";
    String header = BitbucketConfiguration.authorizationHeader(secret);
    String expected =
        "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    assertThat(header).isEqualTo(expected);
  }

  @Test
  void bareAccessTokenBecomesBearerHeader() {
    assertThat(BitbucketConfiguration.authorizationHeader("bare-access-token"))
        .isEqualTo("Bearer bare-access-token");
  }

  @Test
  void blankOrNullSecretYieldsNoHeader() {
    assertThat(BitbucketConfiguration.authorizationHeader(null)).isNull();
    assertThat(BitbucketConfiguration.authorizationHeader("   ")).isNull();
  }

  @Test
  void propertiesDefaultsAreHostOnlyAndNormalizeNeverThrow() {
    BitbucketProperties defaults = BitbucketProperties.defaults();
    assertThat(defaults.baseUrl()).isEqualTo("https://api.bitbucket.org");
    assertThat(defaults.hasToken()).isFalse();
    // Compact ctor normalizes blank base-url / null timeout without throwing.
    BitbucketProperties normalized =
        new BitbucketProperties("SUPER_SECRET_TOKEN_VALUE", "  ", null);
    assertThat(normalized.baseUrl()).isEqualTo("https://api.bitbucket.org");
    assertThat(normalized.timeout()).isNotNull();
    assertThat(normalized.toString())
        .doesNotContain("SUPER_SECRET_TOKEN_VALUE")
        .contains("redacted");
  }
}
