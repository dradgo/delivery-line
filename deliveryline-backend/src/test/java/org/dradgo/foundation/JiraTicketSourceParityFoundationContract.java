package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;
import org.dradgo.adapters.integration.ticketsource.gitlab.GitLabTicketSourceStubAdapter;
import org.dradgo.adapters.integration.ticketsource.jira.JiraMockAdapter;
import org.dradgo.adapters.integration.ticketsource.jira.JiraRealAdapter;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketQuery;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSummary;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Foundation contract (story 3i-1 AC8) — the JIRA mock + real adapters implement the same
 * vendor-neutral {@link TicketSourceAdapter} port and behave alike at the port boundary: (a) both
 * implement the port, (b) a happy read returns a neutral {@link Ticket} in both (the real one
 * against {@link MockRestServiceServer}-stubbed HTTP), (c) a classified failure surfaces the same
 * {@link IntegrationFailureCategory} in both, and (d) the connectivity probe is
 * reachable+authenticated in both. Mirrors {@code TicketSourceAbstractionFoundationContract} for
 * the JIRA kind.
 */
@Tag("foundation-gate")
class JiraTicketSourceParityFoundationContract {

  private static final String BASE_URL = "https://acme.atlassian.net";

  private final JiraMockAdapter mock = new JiraMockAdapter();

  @Test
  void bothAdaptersImplementTheSamePort() {
    assertTrue(
        TicketSourceAdapter.class.isAssignableFrom(JiraMockAdapter.class),
        tag("JiraMockAdapter must implement TicketSourceAdapter"));
    assertTrue(
        TicketSourceAdapter.class.isAssignableFrom(JiraRealAdapter.class),
        tag("JiraRealAdapter must implement TicketSourceAdapter"));
  }

  @Test
  void happyReadReturnsNeutralTicketInBoth() {
    Optional<Ticket> mockTicket = mock.fetchTicketByReference(TicketRef.of("PROJ-1"));
    assertTrue(mockTicket.isPresent(), tag("mock happy read present"));
    assertInstanceOf(Ticket.class, mockTicket.get());

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-1")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(ISSUE_RESPONSE, MediaType.APPLICATION_JSON));
    Optional<Ticket> realTicket = harness.adapter.fetchTicketByReference(TicketRef.of("PROJ-1"));
    assertTrue(realTicket.isPresent(), tag("real happy read present"));
    assertEquals("PROJ-1", realTicket.get().ticketRef().value(), tag("real ticketRef mapped"));
    harness.server.verify();
  }

  @Test
  void classifiedFailureSurfacesSameCategoryInBoth() {
    mock.registerFailure("PROJ-RATE", IntegrationFailureCategory.NETWORK_API_FAILURE);
    IntegrationFailureCategory mockCategory =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> mock.fetchTicketByReference(TicketRef.of("PROJ-RATE")))
            .failureCategory();

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/issue/PROJ-429")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    IntegrationFailureCategory realCategory =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> harness.adapter.fetchTicketByReference(TicketRef.of("PROJ-429")))
            .failureCategory();

    assertEquals(
        IntegrationFailureCategory.NETWORK_API_FAILURE, mockCategory, tag("mock category"));
    assertEquals(mockCategory, realCategory, tag("mock and real classify the same scenario alike"));
    harness.server.verify();
  }

  /**
   * Story 3i-2 (AC1/AC8) — both JIRA adapters advertise {@code supportsTicketQuery} and return the
   * same neutral {@link TicketSummary} shape from {@code queryTickets}. Parity is on the port
   * shape, not the payload: the mock synthesizes its own deterministic title/summary text.
   */
  @Test
  void queryTicketsIsAdvertisedAndReturnsTheSameNeutralShapeInBoth() {
    assertTrue(mock.getCapabilities().supportsTicketQuery(), tag("mock advertises ticket query"));

    mock.registerHappy("PROJ-1");
    TicketQueryResult mockResult = mock.queryTickets(TicketQuery.unfiltered());
    List<TicketSummary> mockTickets = mockResult.tickets();

    RealHarness harness = realHarness();
    assertTrue(
        harness.adapter.getCapabilities().supportsTicketQuery(),
        tag("real advertises ticket query"));
    harness
        .server
        .expect(requestTo(BASE_URL + "/rest/api/3/search"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"issues\":[" + ISSUE_RESPONSE + "],\"startAt\":0,\"total\":1}",
                MediaType.APPLICATION_JSON));
    TicketQueryResult realResult = harness.adapter.queryTickets(TicketQuery.unfiltered());
    List<TicketSummary> realTickets = realResult.tickets();

    assertEquals(1, mockTickets.size(), tag("mock browse returns one candidate"));
    assertEquals(1, realTickets.size(), tag("real browse returns one candidate"));
    assertInstanceOf(TicketSummary.class, mockTickets.get(0));
    assertEquals(
        TicketRef.of("PROJ-1"), mockTickets.get(0).ticketRef(), tag("mock ticketRef is neutral"));
    assertEquals(
        TicketRef.of("PROJ-1"), realTickets.get(0).ticketRef(), tag("real ticketRef is neutral"));
    assertNotNull(mockTickets.get(0).title(), tag("mock title present"));
    assertNotNull(realTickets.get(0).title(), tag("real title present"));
    // A complete page reports total == page size and truncated == false in BOTH adapters.
    assertEquals(1, mockResult.total(), tag("mock reports the source total"));
    assertEquals(1, realResult.total(), tag("real reports the source total"));
    assertFalse(mockResult.truncated(), tag("mock complete page is not truncated"));
    assertFalse(realResult.truncated(), tag("real complete page is not truncated"));
    harness.server.verify();
  }

  /**
   * Story 3i-2 code-review — a page capped by {@code limit} MUST report {@code truncated}, in both
   * adapters. Without this, a source matching hundreds of tickets renders identically to one
   * matching exactly {@code limit}, and the operator silently loses the rest of their backlog.
   *
   * <p>The mock must derive {@code total} from all matching scenarios rather than from the capped
   * page, or a real truncation regression would sail through parity.
   */
  @Test
  void aCappedPageReportsTruncationInBoth() {
    mock.registerHappy("PROJ-1");
    mock.registerHappy("PROJ-2");
    mock.registerHappy("PROJ-3");
    TicketQueryResult mockResult = mock.queryTickets(new TicketQuery(null, List.of(), null, 2));

    assertEquals(2, mockResult.tickets().size(), tag("mock honors the limit"));
    assertEquals(3, mockResult.total(), tag("mock total counts matches beyond the page"));
    assertTrue(mockResult.truncated(), tag("mock reports truncation"));

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL + "/rest/api/3/search"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"issues\":[" + ISSUE_RESPONSE + "],\"startAt\":0,\"total\":3}",
                MediaType.APPLICATION_JSON));
    TicketQueryResult realResult =
        harness.adapter.queryTickets(new TicketQuery(null, List.of(), null, 1));

    assertEquals(1, realResult.tickets().size(), tag("real returns the capped page"));
    assertEquals(3, realResult.total(), tag("real surfaces JIRA's total, not the page size"));
    assertTrue(realResult.truncated(), tag("real reports truncation"));
    harness.server.verify();
  }

  /** Story 3i-2 — a connector that does not advertise the browse throws rather than degrading. */
  @Test
  void nonQueryConnectorsThrowFromQueryTickets() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> new GitLabTicketSourceStubAdapter().queryTickets(TicketQuery.unfiltered()),
        tag("gitlab stub refuses an un-gated browse"));
  }

  @Test
  void verifyConnectivityProbeIsReachableAndAuthenticatedInBoth() {
    ConnectivityResult mockResult = mock.verifyConnectivity(null);
    assertTrue(mockResult.reachable() && mockResult.authenticated(), tag("mock probe ok"));

    RealHarness harness = realHarness();
    harness
        .server
        .expect(requestTo(BASE_URL + "/rest/api/3/myself"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"accountId\":\"5b10\"}", MediaType.APPLICATION_JSON));
    harness
        .server
        .expect(requestTo(Matchers.startsWith(BASE_URL + "/rest/api/3/project/search")))
        // At least one VISIBLE project. An empty `values` array is the adapter's documented
        // reachable-but-not-authenticated degrade ("jira: no reachable projects visible"), so
        // stubbing it empty asserted the opposite of what this test claims. The contract was never
        // registered in FoundationGateVerificationTest, so the broken fixture never ran (fixed
        // 3i-2).
        .andRespond(
            withSuccess(
                "{\"values\":[{\"id\":\"10000\",\"key\":\"PROJ\"}]}", MediaType.APPLICATION_JSON));
    ConnectivityResult realResult = harness.adapter.verifyConnectivity(null);
    assertTrue(realResult.reachable() && realResult.authenticated(), tag("real probe ok"));
    harness.server.verify();
  }

  private RealHarness realHarness() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    JiraProperties properties =
        new JiraProperties(
            "ATATT-parity-token",
            BASE_URL,
            "pilot@acme.example",
            50,
            new JiraProperties.Timeout(5_000L, 30_000L));
    return new RealHarness(server, new JiraRealAdapter(builder.build(), properties));
  }

  private static String tag(String detail) {
    return FoundationGateAssertions.tagged("3i-1", "JIRA TicketSource parity: " + detail);
  }

  private static final String ISSUE_RESPONSE =
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

  private record RealHarness(MockRestServiceServer server, JiraRealAdapter adapter) {}
}
