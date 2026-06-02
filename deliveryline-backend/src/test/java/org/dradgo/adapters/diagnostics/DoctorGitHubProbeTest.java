package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Story 3.14 AC9 / AC11(d) — doctor {@code github-real} auth probe: inactive ⇒ PASS-not-applicable
 * with no network call; token-missing ⇒ {@code DOCTOR_GITHUB_TOKEN_MISSING}; 200 ⇒ PASS; 401 ⇒
 * {@code DOCTOR_GITHUB_AUTH_FAILED}.
 */
class DoctorGitHubProbeTest {

  private static final String BASE_URL = "https://api.github.com";

  @Test
  void inactiveProfileReturnsPassNotApplicableWithNoNetworkCall() {
    // A null RestClient means any network attempt would NPE — PASS-not-applicable proves no call.
    MockEnvironment env = new MockEnvironment();
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(env, (RestClient) null, null);

    ProbeResult result = adapter.probeGitHubAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.summary()).contains("not applicable");
    assertThat(result.details()).containsEntry("githubRealProfile", "inactive");
  }

  @Test
  void activeProfileWithMissingTokenFailsWithTokenMissingCode() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("github-real");
    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(env, (RestClient) null, GitHubProperties.defaults());

    ProbeResult result = adapter.probeGitHubAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_GITHUB_TOKEN_MISSING.value());
    assertThat(result.details()).containsEntry("tokenPresent", "missing");
  }

  @Test
  void activeProfileWith200OnUserProbePasses() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    server
        .expect(requestTo(BASE_URL + "/user"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"login\":\"octo\"}", MediaType.APPLICATION_JSON));

    DoctorProbeAdapter adapter = new DoctorProbeAdapter(githubRealEnv(), client, tokenProperties());

    ProbeResult result = adapter.probeGitHubAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.summary()).contains("GET /user");
    server.verify();
  }

  @Test
  void activeProfileWith401OnUserProbeFailsWithAuthFailedCode() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    server.expect(requestTo(BASE_URL + "/user")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    DoctorProbeAdapter adapter = new DoctorProbeAdapter(githubRealEnv(), client, tokenProperties());

    ProbeResult result = adapter.probeGitHubAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_GITHUB_AUTH_FAILED.value());
    server.verify();
  }

  private static MockEnvironment githubRealEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("github-real");
    return env;
  }

  private static GitHubProperties tokenProperties() {
    return new GitHubProperties(
        "ghp_doctor_probe_token",
        BASE_URL,
        "2022-11-28",
        100,
        new GitHubProperties.Timeout(5_000L, 30_000L));
  }
}
