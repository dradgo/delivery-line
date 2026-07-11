package org.dradgo.adapters.integration.repohost.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.dradgo.application.integration.github.GitHubProperties;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.integration.repohost.CiConclusion;
import org.dradgo.domain.integration.repohost.CiStatus;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Story 3h-5 (AC1/AC5, Task 3) — {@link GitHubRealAdapter#readCheckRuns} conclusion mapping over a
 * mocked HTTP layer: the check-runs GET, the conclusion truth table, the {@code total_count=0}
 * neutral, annotations fetched only for failed runs, and 404 classification.
 */
class GitHubRealAdapterCheckRunsTest {

  private static final String BASE_URL = "https://api.github.com";
  private static final String SHA = "a1b2c3d4";
  private static final String CHECK_RUNS_URL =
      BASE_URL + "/repos/octo/hello/commits/" + SHA + "/check-runs?filter=latest&per_page=100";

  private MockRestServiceServer mockServer;
  private GitHubRealAdapter adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    GitHubProperties properties =
        new GitHubProperties(
            "t", BASE_URL, "2022-11-28", 100, new GitHubProperties.Timeout(5_000L, 30_000L));
    adapter =
        new GitHubRealAdapter(
            builder.build(),
            properties,
            new RedactionPolicyService(new DataClassificationService()));
  }

  private CiStatus read() {
    return adapter.readCheckRuns(RepositoryRef.of("octo/hello"), SHA);
  }

  @Test
  void totalCountZeroMapsToNeutral() {
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("{\"total_count\":0,\"check_runs\":[]}", MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.NEUTRAL);
    mockServer.verify();
  }

  @Test
  void allCompletedSuccessMapsToSuccess() {
    String body =
        """
        {"total_count":2,"check_runs":[
          {"id":1,"name":"build","status":"completed","conclusion":"success"},
          {"id":2,"name":"test","status":"completed","conclusion":"skipped"}]}
        """;
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.SUCCESS);
    mockServer.verify();
  }

  @Test
  void anyInProgressCheckMapsToPendingAndFetchesNoAnnotations() {
    String body =
        """
        {"total_count":2,"check_runs":[
          {"id":1,"name":"build","status":"completed","conclusion":"failure"},
          {"id":2,"name":"test","status":"in_progress","conclusion":null}]}
        """;
    // Only the check-runs GET is expected — a PENDING verdict fetches no annotations.
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.PENDING);
    mockServer.verify();
  }

  @Test
  void completedFailureMapsToFailureAndComposesAnnotations() {
    String checkRuns =
        """
        {"total_count":1,"check_runs":[
          {"id":42,"name":"build","status":"completed","conclusion":"failure",
           "details_url":"https://github.com/octo/hello/runs/42",
           "output":{"title":"Build failed","summary":"1 error","text":"stack"}}]}
        """;
    String annotations =
        """
        [{"path":"src/Main.java","start_line":12,"annotation_level":"failure","message":"cannot find symbol"},
         {"path":"src/Other.java","start_line":3,"annotation_level":"warning","message":"unused"}]
        """;
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(checkRuns, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/check-runs/42/annotations?per_page=100"))
        .andRespond(withSuccess(annotations, MediaType.APPLICATION_JSON));

    CiStatus status = read();
    assertThat(status.conclusion()).isEqualTo(CiConclusion.FAILURE);
    assertThat(status.checks()).hasSize(1);
    String failureText = status.checks().get(0).failureText();
    assertThat(failureText).contains("Build failed").contains("cannot find symbol");
    // Only the failure-level annotation is kept.
    assertThat(failureText).doesNotContain("unused");
    mockServer.verify();
  }

  @Test
  void cancelledWithNoFailureMapsToNeutral() {
    String body =
        """
        {"total_count":1,"check_runs":[
          {"id":1,"name":"build","status":"completed","conclusion":"cancelled"}]}
        """;
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.NEUTRAL);
    mockServer.verify();
  }

  @Test
  void timedOutMapsToFailure() {
    String checkRuns =
        """
        {"total_count":1,"check_runs":[
          {"id":9,"name":"build","status":"completed","conclusion":"timed_out","output":{}}]}
        """;
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(checkRuns, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/check-runs/9/annotations?per_page=100"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.FAILURE);
    mockServer.verify();
  }

  @Test
  void notFoundClassifiesAsRepositoryHostAdapterException() {
    mockServer.expect(requestTo(CHECK_RUNS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(this::read)
        .isInstanceOf(RepositoryHostAdapterException.class)
        .extracting(e -> ((RepositoryHostAdapterException) e).failureCategory())
        .isEqualTo(IntegrationFailureCategory.GITHUB_REPO_NOT_FOUND);
    mockServer.verify();
  }

  @Test
  void unknownConclusionMapsToNeutralNotSuccess() {
    // Story 3h-5 review (P6): a completed check with an unrecognized/future conclusion must be
    // treated as inconclusive (NEUTRAL) — never silently counted green.
    String body =
        """
        {"total_count":1,"check_runs":[
          {"id":1,"name":"build","status":"completed","conclusion":"some_future_value"}]}
        """;
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.NEUTRAL);
    mockServer.verify();
  }

  @Test
  void actionRequiredMapsToNeutralNotFailure() {
    // Story 3h-5 3rd review (Decision 2): action_required awaits a MANUAL operator action, not a
    // code defect — it must NOT drive the auto-fix loop, so it maps to NEUTRAL, not FAILURE.
    String body =
        """
        {"total_count":1,"check_runs":[
          {"id":1,"name":"deploy-approval","status":"completed","conclusion":"action_required"}]}
        """;
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.NEUTRAL);
    mockServer.verify();
  }

  @Test
  void paginationTruncatedWithAllCollectedGreenMapsToPendingNotSuccess() {
    // Story 3h-5 3rd review (P): when the page budget is exhausted before every reported check is
    // collected (checkRuns.size() < total_count) and everything collected is green, an unread check
    // beyond the budget could still be failing — report PENDING (keep polling), never a false
    // terminal SUCCESS. total_count=3 but only 2 collected (page 2 comes back empty → truncated).
    String page1 =
        "{\"total_count\":3,\"check_runs\":["
            + "{\"id\":1,\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"success\"},"
            + "{\"id\":2,\"name\":\"test\",\"status\":\"completed\",\"conclusion\":\"success\"}]}";
    String page2Empty = "{\"total_count\":3,\"check_runs\":[]}";
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
    mockServer
        .expect(
            requestTo(
                BASE_URL
                    + "/repos/octo/hello/commits/"
                    + SHA
                    + "/check-runs?filter=latest&per_page=100&page=2"))
        .andRespond(withSuccess(page2Empty, MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.PENDING);
    mockServer.verify();
  }

  @Test
  void paginatesBeyondFirstPageToSurfaceALaterFailure() {
    // Story 3h-5 review (P2): total_count>100 must walk further pages — a failure on page 2 was
    // previously missed (the verdict was computed over only the first 100 checks → false SUCCESS).
    StringBuilder page1Runs = new StringBuilder();
    for (int i = 1; i <= 100; i++) {
      if (i > 1) {
        page1Runs.append(',');
      }
      page1Runs
          .append("{\"id\":")
          .append(i)
          .append(",\"name\":\"c")
          .append(i)
          .append("\",\"status\":\"completed\",\"conclusion\":\"success\"}");
    }
    String page1 = "{\"total_count\":101,\"check_runs\":[" + page1Runs + "]}";
    String page2 =
        "{\"total_count\":101,\"check_runs\":[{\"id\":999,\"name\":\"slow-build\","
            + "\"status\":\"completed\",\"conclusion\":\"failure\",\"output\":{}}]}";
    mockServer
        .expect(requestTo(CHECK_RUNS_URL))
        .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
    mockServer
        .expect(
            requestTo(
                BASE_URL
                    + "/repos/octo/hello/commits/"
                    + SHA
                    + "/check-runs?filter=latest&per_page=100&page=2"))
        .andRespond(withSuccess(page2, MediaType.APPLICATION_JSON));
    mockServer
        .expect(requestTo(BASE_URL + "/repos/octo/hello/check-runs/999/annotations?per_page=100"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    assertThat(read().conclusion()).isEqualTo(CiConclusion.FAILURE);
    mockServer.verify();
  }
}
