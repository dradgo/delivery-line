package org.dradgo.adapters.integration.ticketsource.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketQuery;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSummary;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Story 3i-1 Task 3 — JiraRealAdapter against MockRestServiceServer-stubbed JIRA REST v3. */
class JiraRealAdapterUnitTest {

  private static final String BASE_URL = "https://acme.atlassian.net";

  private record Harness(MockRestServiceServer server, JiraRealAdapter adapter) {}

  private static Harness harness() {
    return harness(BASE_URL);
  }

  private static Harness harness(String baseUrl) {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    JiraProperties properties =
        new JiraProperties(
            "ATATT-token",
            baseUrl,
            "pilot@acme.example",
            50,
            new JiraProperties.Timeout(5_000L, 30_000L));
    return new Harness(server, new JiraRealAdapter(builder.build(), properties));
  }

  private static Harness authHarness(String hostToken) {
    RestClient.Builder builder =
        RestClient.builder()
            .baseUrl(BASE_URL)
            .requestInterceptor(
                (request, body, execution) -> {
                  Object override =
                      request.getAttributes().get(JiraProperties.CREDENTIAL_OVERRIDE_ATTRIBUTE);
                  String token = (override instanceof String s && !s.isBlank()) ? s : hostToken;
                  if (token != null && !token.isBlank()) {
                    request.getHeaders().setBearerAuth(token);
                  }
                  return execution.execute(request, body);
                });
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    JiraProperties properties =
        new JiraProperties(
            hostToken,
            BASE_URL,
            "pilot@acme.example",
            50,
            new JiraProperties.Timeout(5_000L, 30_000L));
    return new Harness(server, new JiraRealAdapter(builder.build(), properties));
  }

  private static final String ISSUE_JSON =
      """
      {
        "key": "PROJ-1",
        "fields": {
          "summary": "Add caching layer",
          "description": {"type":"doc","version":1,"content":[
            {"type":"paragraph","content":[{"type":"text","text":"Bounded feature"}]}]},
          "status": {"id":"10001","name":"To Do"},
          "labels": ["feature"],
          "reporter": {"accountId":"5b10","emailAddress":"dev@example.com","displayName":"Dev"},
          "created": "2026-05-01T10:00:00.000+0000",
          "updated": "2026-05-02T12:30:00.000+0000"
        }
      }
      """;

  private static final String EMPTY_COMMENTS =
      "{\"comments\":[],\"startAt\":0,\"maxResults\":100,\"total\":0}";

  @Test
  void fetchMapsIssueToNeutralTicket() {
    Harness h = harness();
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(ISSUE_JSON, MediaType.APPLICATION_JSON));

    Ticket ticket = h.adapter.fetchTicketByReference(TicketRef.of("PROJ-1")).orElseThrow();

    assertThat(ticket.ticketRef().value()).isEqualTo("PROJ-1");
    assertThat(ticket.title()).isEqualTo("Add caching layer");
    assertThat(ticket.summary()).contains("Bounded feature");
    assertThat(ticket.authorIdentity()).isEqualTo("dev@example.com");
    assertThat(ticket.sourceStatus()).isEqualTo("To Do");
    assertThat(ticket.sourceStatusId()).isEqualTo("10001");
    assertThat(ticket.labels()).containsKey("feature");
    h.server.verify();
  }

  @Test
  void oauthGrantRefreshesTokenRoutesThroughCloudIdAndPersistsRotatedRefreshToken() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.atlassian.com");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    AtomicReference<String> rotatedGrant = new AtomicReference<>();
    JiraOAuthTokenProvider tokenProvider =
        new JiraOAuthTokenProvider(
            builder.build(), (projectId, plaintext) -> rotatedGrant.set(plaintext));
    JiraRealAdapter adapter =
        new JiraRealAdapter(
            builder.build(),
            new JiraProperties(
                null, "https://api.atlassian.com", null, 50, JiraProperties.Timeout.defaults()),
            tokenProvider);
    JiraRealAdapter bound =
        (JiraRealAdapter)
            adapter.withProjectCredential(
                "prj_demo01",
                "{\"cloudId\":\"cloud-123\",\"refreshToken\":\"refresh-old\","
                    + "\"clientId\":\"client-id\",\"clientSecret\":\"client-secret\"}");

    server
        .expect(requestTo("https://api.atlassian.com/oauth/token"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"grant_type\":\"refresh_token\"}"))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"access-new\",\"refresh_token\":\"refresh-new\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                Matchers.startsWith(
                    "https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/issue/PROJ-1")))
        .andExpect(header("Authorization", "Bearer access-new"))
        .andRespond(withSuccess(ISSUE_JSON, MediaType.APPLICATION_JSON));

    assertThat(bound.fetchTicketByReference(TicketRef.of("PROJ-1"))).isPresent();
    assertThat(rotatedGrant.get()).contains("refresh-new").contains("cloud-123");
    server.verify();
  }

  @Test
  void hostFallbackBearerTokenIsAppliedToRequests() {
    Harness h = authHarness("host-token");
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
        .andExpect(header("Authorization", "Bearer host-token"))
        .andRespond(withSuccess(ISSUE_JSON, MediaType.APPLICATION_JSON));

    assertThat(h.adapter.fetchTicketByReference(TicketRef.of("PROJ-1"))).isPresent();
    h.server.verify();
  }

  @Test
  void credentialOverrideBearerTokenTakesPrecedenceOverHostFallback() {
    Harness h = authHarness("host-token");
    JiraRealAdapter bound = (JiraRealAdapter) h.adapter.withCredentialOverride("project-token");
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
        .andExpect(header("Authorization", "Bearer project-token"))
        .andRespond(withSuccess(ISSUE_JSON, MediaType.APPLICATION_JSON));

    assertThat(bound.fetchTicketByReference(TicketRef.of("PROJ-1"))).isPresent();
    h.server.verify();
  }

  @Test
  void pollSearchesByJqlAndMapsResults() {
    Harness h = harness();
    String searchResponse =
        "{\"issues\":[" + ISSUE_JSON + "],\"startAt\":0,\"maxResults\":50,\"total\":1}";
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/search"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(searchResponse, MediaType.APPLICATION_JSON));

    java.util.List<Ticket> tickets =
        h.adapter.pollNewTickets(java.time.Instant.parse("2026-05-01T00:00:00Z"));

    assertThat(tickets).hasSize(1);
    assertThat(tickets.get(0).ticketRef().value()).isEqualTo("PROJ-1");
    h.server.verify();
  }

  // -------------------------------------------------------------------------------------------
  // Story 3i-2 (AC2) — queryTickets: JQL by omission + escaping + maxResults
  // -------------------------------------------------------------------------------------------

  private static final String SEARCH_ONE_ISSUE =
      "{\"issues\":[" + ISSUE_JSON + "],\"startAt\":0,\"maxResults\":50,\"total\":1}";

  /**
   * A JIRA issue whose {@code summary} is hidden by a field-level permission scheme. JIRA still
   * returns the issue in search results; {@code requireText(fields,"summary")} throws on it.
   * Realistic — not a corrupt payload.
   */
  private static final String ISSUE_HIDDEN_SUMMARY_JSON =
      """
      {
        "key": "PROJ-77",
        "fields": {
          "status": {"id":"10001","name":"To Do"},
          "labels": [],
          "reporter": {"accountId":"5b10","displayName":"Dev"},
          "created": "2026-05-01T10:00:00.000+0000",
          "updated": "2026-05-02T12:30:00.000+0000"
        }
      }
      """;

  /** A JIRA issue whose {@code updated} timestamp cannot be parsed. */
  private static final String ISSUE_BAD_TIMESTAMP_JSON =
      """
      {
        "key": "PROJ-78",
        "fields": {
          "summary": "Unparseable timestamp",
          "status": {"id":"10001","name":"To Do"},
          "labels": [],
          "reporter": {"accountId":"5b10","displayName":"Dev"},
          "created": "2026-05-01T10:00:00.000+0000",
          "updated": "not-a-timestamp"
        }
      }
      """;

  /** A JIRA issue whose description is absent entirely (a legal, body-less ticket). */
  private static final String ISSUE_NO_DESCRIPTION_JSON =
      """
      {
        "key": "PROJ-9",
        "fields": {
          "summary": "Body-less ticket",
          "status": {"id":"10001","name":"To Do"},
          "labels": [],
          "reporter": {"accountId":"5b10","displayName":"Dev"},
          "created": "2026-05-01T10:00:00.000+0000",
          "updated": "2026-05-02T12:30:00.000+0000"
        }
      }
      """;

  private static Harness expectSearchJql(String expectedJql, int expectedMaxResults, String body) {
    Harness h = harness();
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/search"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.jql").value(expectedJql))
        .andExpect(jsonPath("$.maxResults").value(expectedMaxResults))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    return h;
  }

  @Test
  void querySearchesByJqlAndMapsResults() {
    Harness h =
        expectSearchJql(
            "assignee = \"acct-1\" AND component in (\"billing\", \"api\") "
                + "AND status = \"To Do\" ORDER BY updated DESC",
            25,
            SEARCH_ONE_ISSUE);

    List<TicketSummary> tickets =
        h.adapter
            .queryTickets(new TicketQuery("acct-1", List.of("billing", "api"), "To Do", 25))
            .tickets();

    assertThat(tickets).hasSize(1);
    assertThat(tickets.get(0).ticketRef().value()).isEqualTo("PROJ-1");
    assertThat(tickets.get(0).title()).isEqualTo("Add caching layer");
    assertThat(tickets.get(0).summary()).contains("Bounded feature");
    h.server.verify();
  }

  /** An absent filter field contributes NO clause — never a match-all predicate. */
  @Test
  void queryOmitsAbsentFilterFieldsInsteadOfRenderingMatchAllClauses() {
    Harness h =
        expectSearchJql("ORDER BY updated DESC", TicketQuery.DEFAULT_LIMIT, SEARCH_ONE_ISSUE);

    assertThat(h.adapter.queryTickets(TicketQuery.unfiltered()).tickets()).hasSize(1);
    h.server.verify();
  }

  @Test
  void queryOmitsOnlyTheBlankFieldsAndKeepsThePresentOnes() {
    Harness h =
        expectSearchJql("status = \"In Progress\" ORDER BY updated DESC", 10, SEARCH_ONE_ISSUE);

    assertThat(
            h.adapter.queryTickets(new TicketQuery("  ", List.of(), "In Progress", 10)).tickets())
        .hasSize(1);
    h.server.verify();
  }

  /** Every operator-supplied value is escaped — the filters are a JQL-injection boundary. */
  @Test
  void queryEscapesQuotesAndBackslashesInEveryUserSuppliedValue() {
    Harness h =
        expectSearchJql(
            "assignee = \"a\\\"b\" AND component in (\"c\\\\d\") "
                + "AND status = \"e\\\"f\" ORDER BY updated DESC",
            50,
            SEARCH_ONE_ISSUE);

    h.adapter.queryTickets(new TicketQuery("a\"b", List.of("c\\d"), "e\"f", 50));
    h.server.verify();
  }

  /** A ticket with no description must map to a null summary, not crash the browse. */
  @Test
  void queryMapsAnAbsentDescriptionToANullSummary() {
    Harness h =
        expectSearchJql(
            "ORDER BY updated DESC",
            TicketQuery.DEFAULT_LIMIT,
            "{\"issues\":[" + ISSUE_NO_DESCRIPTION_JSON + "],\"startAt\":0,\"total\":1}");

    List<TicketSummary> tickets = h.adapter.queryTickets(TicketQuery.unfiltered()).tickets();

    assertThat(tickets).hasSize(1);
    assertThat(tickets.get(0).title()).isEqualTo("Body-less ticket");
    assertThat(tickets.get(0).summary()).isNull();
    h.server.verify();
  }

  /**
   * Code-review D2 — one issue the browsing account cannot fully see must not cost the operator the
   * rest of the page. Before this, {@code requireText} threw out of the un-guarded map loop and the
   * whole browse failed.
   */
  @Test
  void queryHiddenSummaryIssueIsSkippedInsteadOfFailingTheWholePage() {
    Harness h =
        expectSearchJql(
            "ORDER BY updated DESC",
            TicketQuery.DEFAULT_LIMIT,
            "{\"issues\":["
                + ISSUE_HIDDEN_SUMMARY_JSON
                + ","
                + ISSUE_JSON
                + "],\"startAt\":0,\"total\":2}");

    TicketQueryResult result = h.adapter.queryTickets(TicketQuery.unfiltered());

    assertThat(result.tickets()).hasSize(1);
    assertThat(result.tickets().get(0).ticketRef().value()).isEqualTo("PROJ-1");
    // The skipped issue still counts toward the source total, so the operator learns they are not
    // seeing everything.
    assertThat(result.total()).isEqualTo(2);
    assertThat(result.truncated()).isTrue();
    h.server.verify();
  }

  /** An unparseable timestamp is skipped on the same terms as a hidden field. */
  @Test
  void queryUnparseableTimestampIssueIsSkipped() {
    Harness h =
        expectSearchJql(
            "ORDER BY updated DESC",
            TicketQuery.DEFAULT_LIMIT,
            "{\"issues\":["
                + ISSUE_BAD_TIMESTAMP_JSON
                + ","
                + ISSUE_JSON
                + "],\"startAt\":0,\"total\":2}");

    TicketQueryResult result = h.adapter.queryTickets(TicketQuery.unfiltered());

    assertThat(result.tickets()).extracting(t -> t.ticketRef().value()).containsExactly("PROJ-1");
    h.server.verify();
  }

  /** Every issue unmappable => an empty page, still not an exception. */
  @Test
  void queryWithEveryIssueUnmappableReturnsAnEmptyPageRatherThanThrowing() {
    Harness h =
        expectSearchJql(
            "ORDER BY updated DESC",
            TicketQuery.DEFAULT_LIMIT,
            "{\"issues\":[" + ISSUE_HIDDEN_SUMMARY_JSON + "],\"startAt\":0,\"total\":1}");

    TicketQueryResult result = h.adapter.queryTickets(TicketQuery.unfiltered());

    assertThat(result.tickets()).isEmpty();
    assertThat(result.truncated()).isTrue();
    h.server.verify();
  }

  /** The skip is loud: a WARN carries the count, and never the issue payload or ticket text. */
  @Test
  void querySkippedIssuesAreLoggedAtWarnAsACountWithoutTicketText() {
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(JiraRealAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Harness h =
          expectSearchJql(
              "ORDER BY updated DESC",
              TicketQuery.DEFAULT_LIMIT,
              "{\"issues\":["
                  + ISSUE_HIDDEN_SUMMARY_JSON
                  + ","
                  + ISSUE_JSON
                  + "],\"startAt\":0,\"total\":2}");
      h.adapter.queryTickets(TicketQuery.unfiltered());

      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("skippedUnmappableIssues=1")
                      && event.getFormattedMessage().contains("of=2"));
      assertThat(appender.list)
          .noneMatch(
              event ->
                  event.getFormattedMessage().contains("PROJ-77")
                      || event.getFormattedMessage().contains("Add caching layer"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  /** Code-review D3 — the source's total is surfaced, not the page size. */
  @Test
  void queryReportsTheSourceTotalAndFlagsATruncatedPage() {
    Harness h =
        expectSearchJql(
            "ORDER BY updated DESC",
            1,
            "{\"issues\":[" + ISSUE_JSON + "],\"startAt\":0,\"total\":412}");

    TicketQueryResult result = h.adapter.queryTickets(new TicketQuery(null, List.of(), null, 1));

    assertThat(result.tickets()).hasSize(1);
    assertThat(result.total()).isEqualTo(412);
    assertThat(result.truncated()).isTrue();
    h.server.verify();
  }

  /** A response with no `total` field falls back to the page size — never a fabricated number. */
  @Test
  void queryWithoutATotalFieldFallsBackToThePageSizeAndReportsNoTruncation() {
    Harness h =
        expectSearchJql(
            "ORDER BY updated DESC",
            TicketQuery.DEFAULT_LIMIT,
            "{\"issues\":[" + ISSUE_JSON + "],\"startAt\":0}");

    TicketQueryResult result = h.adapter.queryTickets(TicketQuery.unfiltered());

    assertThat(result.total()).isEqualTo(1);
    assertThat(result.truncated()).isFalse();
    h.server.verify();
  }

  /** AC7 — the JQL string, the filter values, and the ticket free-text never reach the logs. */
  @Test
  void queryLogsCountsAndFlagsButNeverTheJqlOrFilterValuesOrTicketText() {
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(JiraRealAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Harness h =
          expectSearchJql(
              "assignee = \"secret-account\" ORDER BY updated DESC", 50, SEARCH_ONE_ISSUE);
      h.adapter.queryTickets(new TicketQuery("secret-account", List.of(), null, 50));

      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.INFO
                      && event.getFormattedMessage().contains("jira_real query")
                      && event.getFormattedMessage().contains("resultCount=1")
                      && event.getFormattedMessage().contains("assigneeFiltered=true"));
      assertThat(appender.list)
          .noneMatch(
              event ->
                  event.getFormattedMessage().contains("secret-account")
                      || event.getFormattedMessage().contains("ORDER BY")
                      || event.getFormattedMessage().contains("Add caching layer"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void fetch404IsEmptyNotAThrow() {
    Harness h = harness();
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-404")))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThat(h.adapter.fetchTicketByReference(TicketRef.of("PROJ-404"))).isEmpty();
    h.server.verify();
  }

  @Test
  void authErrorClassifiesAsLinkFailure() {
    Harness h = harness();
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    IntegrationFailureCategory category =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> h.adapter.fetchTicketByReference(TicketRef.of("PROJ-1")))
            .failureCategory();
    assertThat(category).isEqualTo(IntegrationFailureCategory.LINK_FAILURE);
  }

  @Test
  void rateLimitClassifiesAsNetworkFailure() {
    Harness h = harness();
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    IntegrationFailureCategory category =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> h.adapter.fetchTicketByReference(TicketRef.of("PROJ-1")))
            .failureCategory();
    assertThat(category).isEqualTo(IntegrationFailureCategory.NETWORK_API_FAILURE);
  }

  @Test
  void badRequestClassifiesAsSyncFailure() {
    Harness h = harness();
    // Comment scan first (empty), then the POST comment returns 400 → SYNC_FAILURE.
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EMPTY_COMMENTS, MediaType.APPLICATION_JSON));
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    GovernedRunComment comment =
        new GovernedRunComment("run_1", "fp_1", "body", DataClassification.SHAREABLE_REDACTED);
    IntegrationFailureCategory category =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> h.adapter.postGovernedRunComment(TicketRef.of("PROJ-1"), comment))
            .failureCategory();
    assertThat(category).isEqualTo(IntegrationFailureCategory.SYNC_FAILURE);
  }

  @Test
  void postCommentScansThenPosts() {
    Harness h = harness();
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EMPTY_COMMENTS, MediaType.APPLICATION_JSON));
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

    GovernedRunComment comment =
        new GovernedRunComment("run_1", "fp_1", "body", DataClassification.SHAREABLE_REDACTED);
    assertThat(h.adapter.postGovernedRunComment(TicketRef.of("PROJ-1"), comment))
        .isEqualTo(CommentResult.POSTED);
    h.server.verify();
  }

  @Test
  void postCommentSkipsWhenMarkerAlreadyPresent() {
    Harness h = harness();
    String markerComment =
        "{\"comments\":[{\"body\":{\"type\":\"doc\",\"version\":1,\"content\":["
            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":"
            + "\"<!-- deliveryline:run=run_1 fp=fp_1 -->\"}]}]}}],"
            + "\"startAt\":0,\"maxResults\":100,\"total\":1}";
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(markerComment, MediaType.APPLICATION_JSON));

    GovernedRunComment comment =
        new GovernedRunComment("run_1", "fp_1", "body", DataClassification.SHAREABLE_REDACTED);
    assertThat(h.adapter.postGovernedRunComment(TicketRef.of("PROJ-1"), comment))
        .isEqualTo(CommentResult.SKIPPED_DUPLICATE);
    h.server.verify();
  }

  @Test
  void createSubticketCreatesThenPostsParentLink() {
    Harness h = harness();
    // 1) scan parent for an existing subticket marker (none)
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EMPTY_COMMENTS, MediaType.APPLICATION_JSON));
    // 2) search for a child issue that has the create-time idempotency property (none)
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/search"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"issues\":[],\"total\":0}", MediaType.APPLICATION_JSON));
    // 3) create the sub-task
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess("{\"id\":\"10050\",\"key\":\"PROJ-50\"}", MediaType.APPLICATION_JSON));
    // 4) nested parent-link comment: scan (none) + post
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EMPTY_COMMENTS, MediaType.APPLICATION_JSON));
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"2\"}", MediaType.APPLICATION_JSON));

    SubticketDraft draft =
        new SubticketDraft(
            "run_parent", "proposal_1", "subtask_1", 1, "Child", "Body", "split:run_parent:1");
    CreateSubticketResult result = h.adapter.createSubticket(TicketRef.of("PROJ-1"), draft);

    assertThat(result.childRef().value()).isEqualTo("PROJ-50");
    assertThat(result.replay()).isFalse();
    h.server.verify();
  }

  @Test
  void createSubticketReplaysFromChildPropertyWhenParentMarkerIsMissing() {
    Harness h = harness();
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EMPTY_COMMENTS, MediaType.APPLICATION_JSON));
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/search"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"issues\":[{\"key\":\"PROJ-88\"}],\"total\":1}", MediaType.APPLICATION_JSON));

    SubticketDraft draft =
        new SubticketDraft(
            "run_parent", "proposal_1", "subtask_1", 1, "Child", "Body", "split:run_parent:1");
    CreateSubticketResult result = h.adapter.createSubticket(TicketRef.of("PROJ-1"), draft);

    assertThat(result.replay()).isTrue();
    assertThat(result.childRef().value()).isEqualTo("PROJ-88");
    h.server.verify();
  }

  @Test
  void createSubticketReplaysOnExistingMarker() {
    Harness h = harness();
    String markerComment =
        "{\"comments\":[{\"body\":{\"type\":\"doc\",\"version\":1,\"content\":["
            + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":"
            + "\"<!-- deliveryline:subticket key=split:run_parent:1 child=PROJ-77 -->\"}]}]}}],"
            + "\"startAt\":0,\"maxResults\":100,\"total\":1}";
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/issue/PROJ-1/comment?startAt=0&maxResults=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(markerComment, MediaType.APPLICATION_JSON));

    SubticketDraft draft =
        new SubticketDraft(
            "run_parent", "proposal_1", "subtask_1", 1, "Child", "Body", "split:run_parent:1");
    CreateSubticketResult result = h.adapter.createSubticket(TicketRef.of("PROJ-1"), draft);

    assertThat(result.replay()).isTrue();
    assertThat(result.childRef().value()).isEqualTo("PROJ-77");
    h.server.verify();
  }

  @Test
  void buildSourceTicketUrlDerivesBrowseLink() {
    Harness h = harness();
    assertThat(h.adapter.buildSourceTicketUrl(TicketRef.of("PROJ-9")))
        .contains(BASE_URL + "/browse/PROJ-9");
    // Malformed ref → empty.
    assertThat(h.adapter.buildSourceTicketUrl(TicketRef.of("not a key"))).isEmpty();
  }

  @Test
  void buildSourceTicketUrlEmptyWhenBaseUrlUnset() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    JiraRealAdapter adapter =
        new JiraRealAdapter(
            builder.build(),
            new JiraProperties(
                "ATATT-token", null, "pilot@acme.example", 50, JiraProperties.Timeout.defaults()));
    assertThat(adapter.buildSourceTicketUrl(TicketRef.of("PROJ-9"))).isEmpty();
  }

  @Test
  void verifyConnectivityOkOnMyselfAndProject() {
    Harness h = harness();
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/myself"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"accountId\":\"5b10\"}", MediaType.APPLICATION_JSON));
    h.server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/project/search")))
        .andRespond(
            withSuccess(
                "{\"values\":[{\"id\":\"10000\",\"key\":\"PROJ\"}]}", MediaType.APPLICATION_JSON));

    ConnectivityResult result = h.adapter.verifyConnectivity(null);
    assertThat(result.reachable()).isTrue();
    assertThat(result.authenticated()).isTrue();
    h.server.verify();
  }

  @Test
  void verifyConnectivityUnauthenticatedOn401() {
    Harness h = harness();
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/myself"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    ConnectivityResult result = h.adapter.verifyConnectivity(null);
    assertThat(result.reachable()).isTrue();
    assertThat(result.authenticated()).isFalse();
  }

  @Test
  void verifyConnectivityUnreachableOnServerError() {
    Harness h = harness();
    h.server
        .expect(requestTo(BASE_URL + "/rest/api/3/myself"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

    ConnectivityResult result = h.adapter.verifyConnectivity(null);
    assertThat(result.reachable()).isFalse();
  }

  @Test
  void classificationEmitsSecretFreeWarnLogLine() {
    // Pin the new WARN log line on a classified failure (logging-instrumentation task): the
    // category
    // is logged, never the token / Basic header.
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(JiraRealAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Harness h = harness();
      h.server
          .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
          .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
      assertThrows(
          TicketSourceAdapterException.class,
          () -> h.adapter.fetchTicketByReference(TicketRef.of("PROJ-1")));
      assertThat(appender.list)
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("category=network_api_failure"));
      assertThat(appender.list)
          .noneMatch(event -> event.getFormattedMessage().contains("ATATT-token"));
    } finally {
      logger.detachAppender(appender);
    }
  }
}
