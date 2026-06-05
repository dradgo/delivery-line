package org.dradgo.adapters.integration.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.linear.GovernedRunComment;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearProperties;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LinearRealAdapterUnitTest {

  private static final String BASE_URL = "https://api.linear.app/graphql";

  /**
   * Single-page empty poll response — exercises the request-body assertions without ticket data.
   */
  private static final String EMPTY_POLL_RESPONSE =
      "{\"data\":{\"issues\":{\"nodes\":[],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":\"\"}}}}";

  private RestClient restClient;
  private MockRestServiceServer mockServer;
  private LinearRealAdapter adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    mockServer = MockRestServiceServer.bindTo(builder).build();
    restClient = builder.build();
    // Default adapter has NO poll scope (teamKey/projectId null) — the backward-compat path the
    // three existing poll tests exercise (story 3a.4 AC3).
    adapter = new LinearRealAdapter(restClient, scopedProperties(null, null));
  }

  private static LinearProperties scopedProperties(String teamKey, String projectId) {
    return new LinearProperties(
        "test-token",
        BASE_URL,
        60_000L,
        50,
        new LinearProperties.Timeout(5_000L, 30_000L),
        2.0d,
        new LinearProperties.Polling(true),
        teamKey,
        projectId);
  }

  @Test
  void fetchHappyPathParsesIssueIntoLinearTicket() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{
				  "data": {
				    "issues": {
				      "nodes": [{
				        "identifier": "LIN-501",
				        "title": "Add caching layer",
				        "description": "Bounded feature with low risk",
				        "createdAt": "2026-05-01T10:00:00Z",
				        "updatedAt": "2026-05-02T12:30:00Z",
				        "creator": { "email": "dev@example.com", "displayName": "Dev" },
				        "labels": { "nodes": [{ "name": "feature" }, { "name": "caching" }] }
				      }]
				    }
				  }
				}
				""",
                MediaType.APPLICATION_JSON));

    Optional<LinearTicket> ticket = adapter.fetchTicketByReference("LIN-501");

    assertTrue(ticket.isPresent());
    assertEquals("LIN-501", ticket.get().ticketRef());
    assertEquals("Add caching layer", ticket.get().title());
    assertEquals("dev@example.com", ticket.get().authorIdentity());
    assertEquals(Instant.parse("2026-05-02T12:30:00Z"), ticket.get().updatedAt());
    assertTrue(ticket.get().labels().containsKey("feature"));
    mockServer.verify();
  }

  @Test
  void fetchReturnsEmptyWhenIssuesNodesIsEmpty() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess("{\"data\":{\"issues\":{\"nodes\":[]}}}", MediaType.APPLICATION_JSON));

    Optional<LinearTicket> ticket = adapter.fetchTicketByReference("LIN-404");

    assertTrue(ticket.isEmpty());
    mockServer.verify();
  }

  @Test
  void fetchOnMalformedRefRaisesSyncFailureWithoutCallingLinear() {
    LinearAdapterException error =
        assertThrows(
            LinearAdapterException.class, () -> adapter.fetchTicketByReference("not-a-valid-ref"));
    assertEquals(IntegrationFailureCategory.SYNC_FAILURE, error.failureCategory());
    // mockServer received no expectations and no calls — implicit verification of "no network".
    mockServer.verify();
  }

  @Test
  void fetchOn401MapsToLinkFailure() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    LinearAdapterException error =
        assertThrows(LinearAdapterException.class, () -> adapter.fetchTicketByReference("LIN-401"));
    assertEquals(IntegrationFailureCategory.LINK_FAILURE, error.failureCategory());
    mockServer.verify();
  }

  @Test
  void fetchOn429MapsToNetworkApiFailure() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    LinearAdapterException error =
        assertThrows(LinearAdapterException.class, () -> adapter.fetchTicketByReference("LIN-429"));
    assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());
    mockServer.verify();
  }

  @Test
  void fetchOnNetworkIOMapsToNetworkApiFailure() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withException(new SocketTimeoutException("read timed out")));

    LinearAdapterException error =
        assertThrows(LinearAdapterException.class, () -> adapter.fetchTicketByReference("LIN-504"));
    assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());
    mockServer.verify();
  }

  @Test
  void fetchOnGraphqlErrorRatelimitedMapsToNetworkApiFailure() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{
				  "errors": [{"message":"rate limited","extensions":{"code":"RATELIMITED"}}]
				}
				""",
                MediaType.APPLICATION_JSON));

    LinearAdapterException error =
        assertThrows(LinearAdapterException.class, () -> adapter.fetchTicketByReference("LIN-430"));
    assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());
    mockServer.verify();
  }

  @Test
  void fetchOnGraphqlErrorInvalidInputMapsToSyncFailure() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{
				  "errors": [{"message":"bad","extensions":{"code":"INVALID_INPUT"}}]
				}
				""",
                MediaType.APPLICATION_JSON));

    LinearAdapterException error =
        assertThrows(LinearAdapterException.class, () -> adapter.fetchTicketByReference("LIN-400"));
    assertEquals(IntegrationFailureCategory.SYNC_FAILURE, error.failureCategory());
    mockServer.verify();
  }

  @Test
  void pollNewTicketsReturnsEmptyListWhenNoNodes() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{"data":{"issues":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":""}}}}
				""",
                MediaType.APPLICATION_JSON));

    List<LinearTicket> tickets = adapter.pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
    assertTrue(tickets.isEmpty());
    mockServer.verify();
  }

  @Test
  void pollNewTicketsDrainsAllPagesAndSortsAscendingByUpdatedAt() {
    // Page 1 — newest first (Linear orderBy defaults to DESC), pageInfo.hasNextPage=true.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{
				  "data": {
				    "issues": {
				      "nodes": [
				        { "identifier":"LIN-301","title":"newer","description":"",
				          "createdAt":"2026-05-02T10:00:00Z","updatedAt":"2026-05-02T10:00:00Z",
				          "creator":{"email":"a@example.com","displayName":"A"},
				          "labels":{"nodes":[]}}
				      ],
				      "pageInfo": { "hasNextPage": true, "endCursor": "cursor-1" }
				    }
				  }
				}
				""",
                MediaType.APPLICATION_JSON));
    // Page 2 — older item, pageInfo.hasNextPage=false ends the drain.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{
				  "data": {
				    "issues": {
				      "nodes": [
				        { "identifier":"LIN-300","title":"older","description":"",
				          "createdAt":"2026-05-01T10:00:00Z","updatedAt":"2026-05-01T10:00:00Z",
				          "creator":{"email":"b@example.com","displayName":"B"},
				          "labels":{"nodes":[]}}
				      ],
				      "pageInfo": { "hasNextPage": false, "endCursor": "cursor-2" }
				    }
				  }
				}
				""",
                MediaType.APPLICATION_JSON));

    List<LinearTicket> tickets = adapter.pollNewTickets(Instant.parse("2026-04-30T00:00:00Z"));

    assertEquals(2, tickets.size());
    // Adapter sorts ASC by updatedAt — older item first, newer item last (the watermark).
    assertEquals("LIN-300", tickets.get(0).ticketRef());
    assertEquals("LIN-301", tickets.get(1).ticketRef());
    mockServer.verify();
  }

  @Test
  void pollNewTicketsFailsClosedWhenPageCapWouldTruncateWindow() {
    for (int page = 1; page <= 20; page++) {
      String body =
          """
				{
				  "data": {
				    "issues": {
				      "nodes": [
				        { "identifier":"LIN-%1$d","title":"ticket-%1$d","description":"",
				          "createdAt":"2026-05-02T10:00:00Z","updatedAt":"2026-05-02T10:00:00Z",
				          "creator":{"email":"cap@example.com","displayName":"Cap"},
				          "labels":{"nodes":[]}}
				      ],
				      "pageInfo": { "hasNextPage": true, "endCursor": "cursor-%1$d" }
				    }
				  }
				}
				"""
              .formatted(page);
      mockServer
          .expect(requestTo(BASE_URL))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    LinearAdapterException error =
        assertThrows(
            LinearAdapterException.class,
            () -> adapter.pollNewTickets(Instant.parse("2026-04-30T00:00:00Z")));

    assertEquals(IntegrationFailureCategory.SYNC_FAILURE, error.failureCategory());
    mockServer.verify();
  }

  // ---- Story 3a.4 — poll-scope IssueFilter (team/project) -------------------------------------

  @Test
  void pollAppliesConfiguredTeamAndProjectFilter() {
    PollHarness harness = scopedHarness("FIN", "proj-uuid-123");
    harness
        .server()
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.variables.filter.updatedAt.gt").value("2026-05-01T00:00:00Z"))
        .andExpect(jsonPath("$.variables.filter.team.key.eq").value("FIN"))
        .andExpect(jsonPath("$.variables.filter.project.id.eq").value("proj-uuid-123"))
        .andRespond(withSuccess(EMPTY_POLL_RESPONSE, MediaType.APPLICATION_JSON));

    harness.adapter().pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
    harness.server().verify();
  }

  @Test
  void pollAppliesTeamOnlyFilterWhenProjectAbsent() {
    // Per-field conditional: teamKey present, projectId blank → only the team key is added.
    PollHarness harness = scopedHarness("FIN", "  ");
    harness
        .server()
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.variables.filter.updatedAt.gt").value("2026-05-01T00:00:00Z"))
        .andExpect(jsonPath("$.variables.filter.team.key.eq").value("FIN"))
        .andExpect(jsonPath("$.variables.filter.project").doesNotExist())
        .andRespond(withSuccess(EMPTY_POLL_RESPONSE, MediaType.APPLICATION_JSON));

    harness.adapter().pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
    harness.server().verify();
  }

  @Test
  void pollOmitsScopeFilterWhenUnconfigured() {
    // Default adapter from setUp has null scope → the filter carries ONLY updatedAt (AC3); no
    // `team`/`project` keys, and never an `eq: null` (T-NULL-FILTER).
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.variables.filter.updatedAt.gt").value("2026-05-01T00:00:00Z"))
        .andExpect(jsonPath("$.variables.filter.team").doesNotExist())
        .andExpect(jsonPath("$.variables.filter.project").doesNotExist())
        .andRespond(withSuccess(EMPTY_POLL_RESPONSE, MediaType.APPLICATION_JSON));

    adapter.pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
    mockServer.verify();
  }

  @Test
  void pollLogsActiveScopeAtInfoWithoutLeakingToken() {
    PollHarness harness = scopedHarness("FIN", "proj-uuid-123");
    harness
        .server()
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(EMPTY_POLL_RESPONSE, MediaType.APPLICATION_JSON));

    ListAppender<ILoggingEvent> appender = attachListAppender();
    try {
      harness.adapter().pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
    } finally {
      detach(appender);
    }

    harness.server().verify();
    assertContainsInfo(appender, "teamKey=FIN");
    assertContainsInfo(appender, "projectId=proj-uuid-123");
    boolean tokenLeaked =
        appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("test-token"));
    assertFalse(tokenLeaked, "Linear API token must never be logged");
  }

  @Test
  void pollLogsNoneSentinelWhenScopeAbsent() {
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(EMPTY_POLL_RESPONSE, MediaType.APPLICATION_JSON));

    ListAppender<ILoggingEvent> appender = attachListAppender();
    try {
      adapter.pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
    } finally {
      detach(appender);
    }

    mockServer.verify();
    assertContainsInfo(appender, "teamKey=none");
    assertContainsInfo(appender, "projectId=none");
  }

  @Test
  void postCommentSkipsWhenFingerprintMarkerAlreadyPresent() {
    // First call: listComments returns a body containing the fingerprint marker.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{
				  "data": {
				    "issue": {
				      "comments": {
				        "nodes": [
				          { "body": "Earlier note\\n<!-- deliveryline:run=run_abc fp=fp-7 -->\\nBody" }
				        ]
				      }
				    }
				  }
				}
				""",
                MediaType.APPLICATION_JSON));
    // No second request expected — idempotency check short-circuits the post.

    GovernedRunComment summary =
        new GovernedRunComment(
            "run_abc", "fp-7", "Body for the run.", DataClassification.SHAREABLE_REDACTED);

    adapter.postGovernedRunComment("LIN-IDEMP", summary);

    mockServer.verify();
  }

  @Test
  void postCommentPostsWhenFingerprintMarkerNotPresent() {
    // First call: listComments returns an empty list.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{"data":{"issue":{"comments":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":""}}}}}
				""",
                MediaType.APPLICATION_JSON));
    // Second call: commentCreate returns success.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{"data":{"commentCreate":{"success":true,"comment":{"id":"comment-id-1"}}}}
				""",
                MediaType.APPLICATION_JSON));

    GovernedRunComment summary =
        new GovernedRunComment(
            "run_xyz", "fp-new", "Body to post.", DataClassification.SHAREABLE_REDACTED);

    adapter.postGovernedRunComment("LIN-POST", summary);

    mockServer.verify();
  }

  @Test
  void postCommentPaginatesListCommentsAndFindsMarkerOnLaterPage() {
    // Page 1 of existing comments — does NOT contain the fingerprint marker; signals hasNextPage.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{"data":{"issue":{"comments":{
				  "nodes":[{"body":"unrelated chatter"}],
				  "pageInfo":{"hasNextPage":true,"endCursor":"page-1-end"}
				}}}}
				""",
                MediaType.APPLICATION_JSON));
    // Page 2 — contains the marker. Adapter must short-circuit without POSTing.
    mockServer
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
				{"data":{"issue":{"comments":{
				  "nodes":[{"body":"earlier note\\n<!-- deliveryline:run=run_p2 fp=fp-p2 -->\\nbody"}],
				  "pageInfo":{"hasNextPage":false,"endCursor":"page-2-end"}
				}}}}
				""",
                MediaType.APPLICATION_JSON));
    // No commentCreate expected — pagination scan locates the marker on page 2.

    GovernedRunComment summary =
        new GovernedRunComment(
            "run_p2",
            "fp-p2",
            "Body the adapter must skip.",
            DataClassification.SHAREABLE_REDACTED);

    adapter.postGovernedRunComment("LIN-PAGED", summary);

    mockServer.verify();
  }

  // ---- Story 3a.4 helpers ---------------------------------------------------------------------

  /** A real adapter bound to its own {@link MockRestServiceServer} with the given poll scope. */
  private static PollHarness scopedHarness(String teamKey, String projectId) {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    return new PollHarness(
        new LinearRealAdapter(client, scopedProperties(teamKey, projectId)), server);
  }

  private record PollHarness(LinearRealAdapter adapter, MockRestServiceServer server) {}

  private static ListAppender<ILoggingEvent> attachListAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(LinearRealAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    return appender;
  }

  private static void detach(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(LinearRealAdapter.class);
    logger.detachAppender(appender);
  }

  private static void assertContainsInfo(ListAppender<ILoggingEvent> appender, String fragment) {
    boolean found =
        appender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .anyMatch(e -> e.getFormattedMessage().contains(fragment));
    assertTrue(
        found,
        () ->
            "Expected INFO log containing \""
                + fragment
                + "\" but saw: "
                + appender.list.stream()
                    .map(e -> e.getLevel() + " " + e.getFormattedMessage())
                    .toList());
  }
}
