package org.dradgo.adapters.integration.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.linear.GovernedRunComment;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.application.integration.linear.LinearProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LinearRealAdapterUnitTest {

	private static final String BASE_URL = "https://api.linear.app/graphql";

	private RestClient restClient;
	private MockRestServiceServer mockServer;
	private LinearRealAdapter adapter;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		mockServer = MockRestServiceServer.bindTo(builder).build();
		restClient = builder.build();
		LinearProperties properties = new LinearProperties(
			"test-token",
			BASE_URL,
			60_000L,
			50,
			new LinearProperties.Timeout(5_000L, 30_000L),
			2.0d,
			new LinearProperties.Polling(true));
		adapter = new LinearRealAdapter(restClient, properties);
	}

	@Test
	void fetchHappyPathParsesIssueIntoLinearTicket() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
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
				""", MediaType.APPLICATION_JSON));

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
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("{\"data\":{\"issues\":{\"nodes\":[]}}}", MediaType.APPLICATION_JSON));

		Optional<LinearTicket> ticket = adapter.fetchTicketByReference("LIN-404");

		assertTrue(ticket.isEmpty());
		mockServer.verify();
	}

	@Test
	void fetchOnMalformedRefRaisesSyncFailureWithoutCallingLinear() {
		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("not-a-valid-ref"));
		assertEquals(IntegrationFailureCategory.SYNC_FAILURE, error.failureCategory());
		// mockServer received no expectations and no calls — implicit verification of "no network".
		mockServer.verify();
	}

	@Test
	void fetchOn401MapsToLinkFailure() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("LIN-401"));
		assertEquals(IntegrationFailureCategory.LINK_FAILURE, error.failureCategory());
		mockServer.verify();
	}

	@Test
	void fetchOn429MapsToNetworkApiFailure() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("LIN-429"));
		assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());
		mockServer.verify();
	}

	@Test
	void fetchOnNetworkIOMapsToNetworkApiFailure() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withException(new SocketTimeoutException("read timed out")));

		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("LIN-504"));
		assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());
		mockServer.verify();
	}

	@Test
	void fetchOnGraphqlErrorRatelimitedMapsToNetworkApiFailure() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{
				  "errors": [{"message":"rate limited","extensions":{"code":"RATELIMITED"}}]
				}
				""", MediaType.APPLICATION_JSON));

		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("LIN-430"));
		assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());
		mockServer.verify();
	}

	@Test
	void fetchOnGraphqlErrorInvalidInputMapsToSyncFailure() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{
				  "errors": [{"message":"bad","extensions":{"code":"INVALID_INPUT"}}]
				}
				""", MediaType.APPLICATION_JSON));

		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("LIN-400"));
		assertEquals(IntegrationFailureCategory.SYNC_FAILURE, error.failureCategory());
		mockServer.verify();
	}

	@Test
	void pollNewTicketsReturnsEmptyListWhenNoNodes() {
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{"data":{"issues":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":""}}}}
				""", MediaType.APPLICATION_JSON));

		List<LinearTicket> tickets = adapter.pollNewTickets(Instant.parse("2026-05-01T00:00:00Z"));
		assertTrue(tickets.isEmpty());
		mockServer.verify();
	}

	@Test
	void pollNewTicketsDrainsAllPagesAndSortsAscendingByUpdatedAt() {
		// Page 1 — newest first (Linear orderBy defaults to DESC), pageInfo.hasNextPage=true.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
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
				""", MediaType.APPLICATION_JSON));
		// Page 2 — older item, pageInfo.hasNextPage=false ends the drain.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
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
				""", MediaType.APPLICATION_JSON));

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
			String body = """
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
				""".formatted(page);
			mockServer.expect(requestTo(BASE_URL))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		}

		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.pollNewTickets(Instant.parse("2026-04-30T00:00:00Z")));

		assertEquals(IntegrationFailureCategory.SYNC_FAILURE, error.failureCategory());
		mockServer.verify();
	}

	@Test
	void postCommentSkipsWhenFingerprintMarkerAlreadyPresent() {
		// First call: listComments returns a body containing the fingerprint marker.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
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
				""", MediaType.APPLICATION_JSON));
		// No second request expected — idempotency check short-circuits the post.

		GovernedRunComment summary = new GovernedRunComment(
			"run_abc",
			"fp-7",
			"Body for the run.",
			DataClassification.SHAREABLE_REDACTED);

		adapter.postGovernedRunComment("LIN-IDEMP", summary);

		mockServer.verify();
	}

	@Test
	void postCommentPostsWhenFingerprintMarkerNotPresent() {
		// First call: listComments returns an empty list.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{"data":{"issue":{"comments":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":""}}}}}
				""", MediaType.APPLICATION_JSON));
		// Second call: commentCreate returns success.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{"data":{"commentCreate":{"success":true,"comment":{"id":"comment-id-1"}}}}
				""", MediaType.APPLICATION_JSON));

		GovernedRunComment summary = new GovernedRunComment(
			"run_xyz",
			"fp-new",
			"Body to post.",
			DataClassification.SHAREABLE_REDACTED);

		adapter.postGovernedRunComment("LIN-POST", summary);

		mockServer.verify();
	}

	@Test
	void postCommentPaginatesListCommentsAndFindsMarkerOnLaterPage() {
		// Page 1 of existing comments — does NOT contain the fingerprint marker; signals hasNextPage.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{"data":{"issue":{"comments":{
				  "nodes":[{"body":"unrelated chatter"}],
				  "pageInfo":{"hasNextPage":true,"endCursor":"page-1-end"}
				}}}}
				""", MediaType.APPLICATION_JSON));
		// Page 2 — contains the marker. Adapter must short-circuit without POSTing.
		mockServer.expect(requestTo(BASE_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{"data":{"issue":{"comments":{
				  "nodes":[{"body":"earlier note\\n<!-- deliveryline:run=run_p2 fp=fp-p2 -->\\nbody"}],
				  "pageInfo":{"hasNextPage":false,"endCursor":"page-2-end"}
				}}}}
				""", MediaType.APPLICATION_JSON));
		// No commentCreate expected — pagination scan locates the marker on page 2.

		GovernedRunComment summary = new GovernedRunComment(
			"run_p2",
			"fp-p2",
			"Body the adapter must skip.",
			DataClassification.SHAREABLE_REDACTED);

		adapter.postGovernedRunComment("LIN-PAGED", summary);

		mockServer.verify();
	}
}
