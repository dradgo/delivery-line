package org.dradgo.adapters.integration.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.linear.GovernedRunComment;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinearMockAdapterUnitTest {

	private LinearMockScenarioRegistry registry;
	private LinearMockAdapter adapter;

	@BeforeEach
	void setUp() {
		registry = new LinearMockScenarioRegistry();
		adapter = new LinearMockAdapter(registry);
	}

	@Test
	void fetchReturnsHappyFixtureForLowRiskFeature() {
		Optional<LinearTicket> ticket = adapter.fetchTicketByReference(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK);
		assertTrue(ticket.isPresent(), "LIN-101 must resolve from the production fixture set");
		assertEquals("LIN-101", ticket.get().ticketRef());
		assertEquals("low", ticket.get().labels().get("risk"));
		assertEquals("feature", ticket.get().labels().get("type"));
	}

	@Test
	void fetchReturnsEmptyForUnregisteredRef() {
		Optional<LinearTicket> ticket = adapter.fetchTicketByReference("LIN-UNKNOWN-999");
		assertTrue(ticket.isEmpty(), "Unknown refs route to LINEAR_TICKET_NOT_FOUND at the command layer (AC7)");
	}

	@Test
	void fetchReturnsEmptyForExplicitNotFoundScenario() {
		registry.register(new LinearMockScenario(
			"TEST-NOT-FOUND",
			LinearMockScenario.Behaviour.NOT_FOUND,
			null,
			null));
		Optional<LinearTicket> ticket = adapter.fetchTicketByReference("TEST-NOT-FOUND");
		assertTrue(ticket.isEmpty());
	}

	@Test
	void fetchThrowsAdapterFailureWithCategoryForAdversarialScenarios() {
		registry.register(new LinearMockScenario(
			"TEST-RATE",
			LinearMockScenario.Behaviour.RATE_LIMITED,
			null,
			IntegrationFailureCategory.NETWORK_API_FAILURE));
		LinearAdapterException error = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("TEST-RATE"));
		assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());

		registry.register(new LinearMockScenario(
			"TEST-AUTH",
			LinearMockScenario.Behaviour.AUTH_FAILURE,
			null,
			IntegrationFailureCategory.LINK_FAILURE));
		LinearAdapterException auth = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("TEST-AUTH"));
		assertEquals(IntegrationFailureCategory.LINK_FAILURE, auth.failureCategory());

		registry.register(new LinearMockScenario(
			"TEST-NET",
			LinearMockScenario.Behaviour.NETWORK_FAILURE,
			null,
			IntegrationFailureCategory.NETWORK_API_FAILURE));
		LinearAdapterException net = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("TEST-NET"));
		assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, net.failureCategory());

		registry.register(new LinearMockScenario(
			"TEST-MAL",
			LinearMockScenario.Behaviour.MALFORMED_RESPONSE,
			null,
			IntegrationFailureCategory.SYNC_FAILURE));
		LinearAdapterException mal = assertThrows(LinearAdapterException.class,
			() -> adapter.fetchTicketByReference("TEST-MAL"));
		assertEquals(IntegrationFailureCategory.SYNC_FAILURE, mal.failureCategory());
	}

	@Test
	void pollReturnsAllThreeProductionFixturesSortedByUpdatedAtWhenSinceIsEpoch() {
		List<LinearTicket> tickets = adapter.pollNewTickets(Instant.EPOCH);
		assertEquals(3, tickets.size());
		// Production fixtures use 2026-04-21, 2026-04-22, 2026-04-24 updatedAt — ascending order
		// must place LIN-101 first, LIN-102 second, LIN-103 last.
		assertEquals("LIN-101", tickets.get(0).ticketRef());
		assertEquals("LIN-102", tickets.get(1).ticketRef());
		assertEquals("LIN-103", tickets.get(2).ticketRef());
		assertTrue(tickets.get(0).updatedAt().isBefore(tickets.get(1).updatedAt()));
		assertTrue(tickets.get(1).updatedAt().isBefore(tickets.get(2).updatedAt()));
	}

	@Test
	void pollWindowsOutTicketsAtOrBeforeSince() {
		// LIN-102 updatedAt is 2026-04-22T11:45:00Z. since = 2026-04-23T00:00:00Z must exclude LIN-101 + LIN-102.
		List<LinearTicket> tickets = adapter.pollNewTickets(Instant.parse("2026-04-23T00:00:00Z"));
		assertEquals(1, tickets.size());
		assertEquals("LIN-103", tickets.get(0).ticketRef());
	}

	@Test
	void pollExcludesAdversarialNonHappyScenarios() {
		// Adversarial scenarios contribute zero tickets to pollNewTickets.
		registry.register(new LinearMockScenario(
			"TEST-RATE",
			LinearMockScenario.Behaviour.RATE_LIMITED,
			null,
			IntegrationFailureCategory.NETWORK_API_FAILURE));
		registry.register(new LinearMockScenario(
			"TEST-NF",
			LinearMockScenario.Behaviour.NOT_FOUND,
			null,
			null));
		List<LinearTicket> tickets = adapter.pollNewTickets(Instant.EPOCH);
		assertEquals(3, tickets.size(), "Only HAPPY scenarios appear in pollNewTickets");
		assertTrue(tickets.stream().noneMatch(ticket -> ticket.ticketRef().startsWith("TEST-")));
	}

	@Test
	void postGovernedRunCommentRecordsButDoesNotPersist() {
		GovernedRunComment summary = new GovernedRunComment(
			"run_abcdef12",
			"fp-test-1",
			"Body redacted by RedactionPolicyService before reaching the port.",
			DataClassification.SHAREABLE_REDACTED);
		assertTrue(adapter.postedComments().isEmpty());
		adapter.postGovernedRunComment("LIN-101", summary);
		List<LinearMockAdapter.PostedComment> recorded = adapter.postedComments();
		assertEquals(1, recorded.size());
		assertEquals("LIN-101", recorded.get(0).ticketRef());
		assertEquals(summary, recorded.get(0).comment());

		adapter.clearPostedComments();
		assertTrue(adapter.postedComments().isEmpty());
	}

	@Test
	void clearTestScenariosRemovesOnlyTestPrefixedEntries() {
		registry.register(new LinearMockScenario(
			"TEST-EXAMPLE",
			LinearMockScenario.Behaviour.NOT_FOUND,
			null,
			null));
		assertTrue(registry.find("TEST-EXAMPLE").isPresent());
		assertTrue(registry.find(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK).isPresent());

		registry.clearTestScenarios();
		assertFalse(registry.find("TEST-EXAMPLE").isPresent(),
			"clearTestScenarios must drop TEST-prefixed entries");
		assertTrue(registry.find(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK).isPresent(),
			"clearTestScenarios must preserve the built-in production fixtures");
	}
}
