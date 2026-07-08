package org.dradgo.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;
import org.dradgo.adapters.integration.ticketsource.jira.JiraMockAdapter;
import org.dradgo.adapters.integration.ticketsource.jira.JiraRealAdapter;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.jira.JiraProperties;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
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
        .andRespond(withSuccess("{\"values\":[]}", MediaType.APPLICATION_JSON));
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
