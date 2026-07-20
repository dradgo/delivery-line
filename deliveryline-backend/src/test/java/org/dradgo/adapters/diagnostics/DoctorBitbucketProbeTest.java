package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.integration.bitbucket.BitbucketProperties;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Story 3i-3 (AC6) — doctor {@code bitbucket-real} auth probe: inactive ⇒ PASS-not-applicable with
 * no network call; credential-missing ⇒ {@code DOCTOR_BITBUCKET_TOKEN_MISSING}; 200 ⇒ PASS; 401 ⇒
 * {@code DOCTOR_BITBUCKET_AUTH_FAILED}. Mirrors {@code DoctorJiraProbeTest}.
 */
class DoctorBitbucketProbeTest {

  private static final String BASE_URL = "https://api.bitbucket.org";

  @Test
  void inactiveProfileReturnsPassNotApplicableWithNoNetworkCall() {
    MockEnvironment env = new MockEnvironment();
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(env, (BitbucketProperties) null, null);

    ProbeResult result = adapter.probeBitbucket();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.summary()).contains("not applicable");
    assertThat(result.details()).containsEntry("bitbucketRealProfile", "inactive");
  }

  @Test
  void activeProfileWithMissingCredentialFailsWithTokenMissingCode() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("bitbucket-real");
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(env, BitbucketProperties.defaults(), null);

    ProbeResult result = adapter.probeBitbucket();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode())
        .isEqualTo(DomainErrorCode.DOCTOR_BITBUCKET_TOKEN_MISSING.value());
    assertThat(result.details()).containsEntry("tokenPresent", "missing");
  }

  @Test
  void activeProfileWith200OnUserProbePasses() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    server
        .expect(requestTo(BASE_URL + "/2.0/user"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"account_id\":\"5b10\"}", MediaType.APPLICATION_JSON));

    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(bitbucketRealEnv(), credentialProperties(), client);

    ProbeResult result = adapter.probeBitbucket();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.summary()).contains("/user");
    server.verify();
  }

  @Test
  void activeProfileWith401OnUserProbeFailsWithAuthFailedCode() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    server
        .expect(requestTo(BASE_URL + "/2.0/user"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(bitbucketRealEnv(), credentialProperties(), client);

    ProbeResult result = adapter.probeBitbucket();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_BITBUCKET_AUTH_FAILED.value());
    server.verify();
  }

  private static MockEnvironment bitbucketRealEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("bitbucket-real");
    return env;
  }

  private static BitbucketProperties credentialProperties() {
    return new BitbucketProperties(
        "workspace:doctor-probe-app-password",
        BASE_URL,
        new BitbucketProperties.Timeout(5_000L, 30_000L));
  }
}
