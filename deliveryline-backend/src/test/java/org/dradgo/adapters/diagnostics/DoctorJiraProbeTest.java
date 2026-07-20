package org.dradgo.adapters.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.spi.ProbeResult;
import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Story 3i-1 (AC6) — doctor {@code jira-real} auth probe: inactive ⇒ PASS-not-applicable with no
 * network call; credentials-missing ⇒ {@code DOCTOR_JIRA_TOKEN_MISSING}; 200 ⇒ PASS; 401 ⇒ {@code
 * DOCTOR_JIRA_AUTH_FAILED}. Mirrors {@code DoctorGitHubProbeTest}.
 */
class DoctorJiraProbeTest {

  private static final String BASE_URL = "https://acme.atlassian.net";

  @Test
  void inactiveProfileReturnsPassNotApplicableWithNoNetworkCall() {
    // A null RestClient means any network attempt would NPE — PASS-not-applicable proves no call.
    MockEnvironment env = new MockEnvironment();
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(env, (JiraProperties) null, null);

    ProbeResult result = adapter.probeJiraAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.summary()).contains("not applicable");
    assertThat(result.details()).containsEntry("jiraRealProfile", "inactive");
  }

  @Test
  void activeProfileWithMissingCredentialsFailsWithTokenMissingCode() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("jira-real");
    DoctorProbeAdapter adapter = new DoctorProbeAdapter(env, JiraProperties.defaults(), null);

    ProbeResult result = adapter.probeJiraAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_JIRA_TOKEN_MISSING.value());
    assertThat(result.details()).containsEntry("tokenPresent", "missing");
  }

  @Test
  void activeProfileWith200OnMyselfProbePasses() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    server
        .expect(requestTo(BASE_URL + "/rest/api/3/myself"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"accountId\":\"5b10\"}", MediaType.APPLICATION_JSON));

    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(jiraRealEnv(), credentialProperties(), client);

    ProbeResult result = adapter.probeJiraAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.PASS);
    assertThat(result.summary()).contains("/rest/api/3/myself");
    server.verify();
  }

  @Test
  void activeProfileWith401OnMyselfProbeFailsWithAuthFailedCode() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    server
        .expect(requestTo(BASE_URL + "/rest/api/3/myself"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    DoctorProbeAdapter adapter =
        new DoctorProbeAdapter(jiraRealEnv(), credentialProperties(), client);

    ProbeResult result = adapter.probeJiraAuth();

    assertThat(result.status()).isEqualTo(DiagnosticsStatus.FAIL);
    assertThat(result.errorCode()).isEqualTo(DomainErrorCode.DOCTOR_JIRA_AUTH_FAILED.value());
    server.verify();
  }

  private static MockEnvironment jiraRealEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("jira-real");
    return env;
  }

  private static JiraProperties credentialProperties() {
    return new JiraProperties(
        "ATATT-doctor-probe-token",
        BASE_URL,
        "pilot@acme.example",
        50,
        new JiraProperties.Timeout(5_000L, 30_000L));
  }
}
