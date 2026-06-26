package org.dradgo.adapters.integration.ticketsource.linear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
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
    Optional<Ticket> ticket =
        adapter.fetchTicketByReference(
            TicketRef.of(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK));
    assertTrue(ticket.isPresent(), "LIN-101 must resolve from the production fixture set");
    assertEquals("LIN-101", ticket.get().ticketRef().value());
    assertEquals("low", ticket.get().labels().get("risk"));
    assertEquals("feature", ticket.get().labels().get("type"));
  }

  @Test
  void fetchReturnsEmptyForUnregisteredRef() {
    Optional<Ticket> ticket = adapter.fetchTicketByReference(TicketRef.of("LIN-UNKNOWN-999"));
    assertTrue(
        ticket.isEmpty(),
        "Unknown refs route to LINEAR_TICKET_NOT_FOUND at the command layer (AC7)");
  }

  @Test
  void fetchReturnsEmptyForExplicitNotFoundScenario() {
    registry.register(
        new LinearMockScenario(
            "TEST-NOT-FOUND", LinearMockScenario.Behaviour.NOT_FOUND, null, null));
    Optional<Ticket> ticket = adapter.fetchTicketByReference(TicketRef.of("TEST-NOT-FOUND"));
    assertTrue(ticket.isEmpty());
  }

  @Test
  void fetchThrowsAdapterFailureWithCategoryForAdversarialScenarios() {
    registry.register(
        new LinearMockScenario(
            "TEST-RATE",
            LinearMockScenario.Behaviour.RATE_LIMITED,
            null,
            IntegrationFailureCategory.NETWORK_API_FAILURE));
    TicketSourceAdapterException error =
        assertThrows(
            TicketSourceAdapterException.class,
            () -> adapter.fetchTicketByReference(TicketRef.of("TEST-RATE")));
    assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, error.failureCategory());

    registry.register(
        new LinearMockScenario(
            "TEST-AUTH",
            LinearMockScenario.Behaviour.AUTH_FAILURE,
            null,
            IntegrationFailureCategory.LINK_FAILURE));
    TicketSourceAdapterException auth =
        assertThrows(
            TicketSourceAdapterException.class,
            () -> adapter.fetchTicketByReference(TicketRef.of("TEST-AUTH")));
    assertEquals(IntegrationFailureCategory.LINK_FAILURE, auth.failureCategory());

    registry.register(
        new LinearMockScenario(
            "TEST-NET",
            LinearMockScenario.Behaviour.NETWORK_FAILURE,
            null,
            IntegrationFailureCategory.NETWORK_API_FAILURE));
    TicketSourceAdapterException net =
        assertThrows(
            TicketSourceAdapterException.class,
            () -> adapter.fetchTicketByReference(TicketRef.of("TEST-NET")));
    assertEquals(IntegrationFailureCategory.NETWORK_API_FAILURE, net.failureCategory());

    registry.register(
        new LinearMockScenario(
            "TEST-MAL",
            LinearMockScenario.Behaviour.MALFORMED_RESPONSE,
            null,
            IntegrationFailureCategory.SYNC_FAILURE));
    TicketSourceAdapterException mal =
        assertThrows(
            TicketSourceAdapterException.class,
            () -> adapter.fetchTicketByReference(TicketRef.of("TEST-MAL")));
    assertEquals(IntegrationFailureCategory.SYNC_FAILURE, mal.failureCategory());
  }

  @Test
  void pollReturnsAllThreeProductionFixturesSortedByUpdatedAtWhenSinceIsEpoch() {
    List<Ticket> tickets = adapter.pollNewTickets(Instant.EPOCH);
    assertEquals(3, tickets.size());
    // Production fixtures use 2026-04-21, 2026-04-22, 2026-04-24 updatedAt — ascending order
    // must place LIN-101 first, LIN-102 second, LIN-103 last.
    assertEquals("LIN-101", tickets.get(0).ticketRef().value());
    assertEquals("LIN-102", tickets.get(1).ticketRef().value());
    assertEquals("LIN-103", tickets.get(2).ticketRef().value());
    assertTrue(tickets.get(0).updatedAt().isBefore(tickets.get(1).updatedAt()));
    assertTrue(tickets.get(1).updatedAt().isBefore(tickets.get(2).updatedAt()));
  }

  @Test
  void pollWindowsOutTicketsAtOrBeforeSince() {
    // LIN-102 updatedAt is 2026-04-22T11:45:00Z. since = 2026-04-23T00:00:00Z must exclude LIN-101
    // + LIN-102.
    List<Ticket> tickets = adapter.pollNewTickets(Instant.parse("2026-04-23T00:00:00Z"));
    assertEquals(1, tickets.size());
    assertEquals("LIN-103", tickets.get(0).ticketRef().value());
  }

  @Test
  void pollExcludesAdversarialNonHappyScenarios() {
    // Adversarial scenarios contribute zero tickets to pollNewTickets.
    registry.register(
        new LinearMockScenario(
            "TEST-RATE",
            LinearMockScenario.Behaviour.RATE_LIMITED,
            null,
            IntegrationFailureCategory.NETWORK_API_FAILURE));
    registry.register(
        new LinearMockScenario("TEST-NF", LinearMockScenario.Behaviour.NOT_FOUND, null, null));
    List<Ticket> tickets = adapter.pollNewTickets(Instant.EPOCH);
    assertEquals(3, tickets.size(), "Only HAPPY scenarios appear in pollNewTickets");
    assertTrue(
        tickets.stream().noneMatch(ticket -> ticket.ticketRef().value().startsWith("TEST-")));
  }

  @Test
  void postGovernedRunCommentRecordsButDoesNotPersist() {
    GovernedRunComment summary =
        new GovernedRunComment(
            "run_abcdef12",
            "fp-test-1",
            "Body redacted by RedactionPolicyService before reaching the port.",
            DataClassification.SHAREABLE_REDACTED);
    assertTrue(adapter.postedComments().isEmpty());
    adapter.postGovernedRunComment(TicketRef.of("LIN-101"), summary);
    List<LinearMockAdapter.PostedComment> recorded = adapter.postedComments();
    assertEquals(1, recorded.size());
    assertEquals("LIN-101", recorded.get(0).ticketRef());
    assertEquals(summary, recorded.get(0).comment());

    adapter.clearPostedComments();
    assertTrue(adapter.postedComments().isEmpty());
  }

  @Test
  void createSubticketIsDeterministicAndReplayDoesNotDuplicateParentLinkComment() {
    SubticketDraft draft =
        new SubticketDraft(
            "run_parent01",
            "proposal_01",
            "subtask_01",
            2,
            "Redacted child title",
            "Redacted child scope",
            "split:run_parent01:proposal_01:2");

    CreateSubticketResult first = adapter.createSubticket(TicketRef.of("LIN-101"), draft);
    CreateSubticketResult replay = adapter.createSubticket(TicketRef.of("LIN-101"), draft);

    assertEquals(TicketRef.of("LIN-101-2"), first.childRef());
    assertEquals(first.childRef(), replay.childRef());
    assertFalse(first.replay());
    assertTrue(replay.replay());
    assertEquals(1, adapter.createdSubtickets().size());
    assertEquals(1, adapter.postedComments().size());
    assertEquals("LIN-101", adapter.postedComments().get(0).ticketRef());
    assertEquals(
        first.parentLinkFingerprint(), adapter.postedComments().get(0).comment().fingerprint());
  }

  @Test
  void capabilitiesAdvertiseTicketCreation() {
    assertTrue(adapter.getCapabilities().supportsTicketCreation());
  }

  @Test
  void clearTestScenariosRemovesOnlyTestPrefixedEntries() {
    registry.register(
        new LinearMockScenario("TEST-EXAMPLE", LinearMockScenario.Behaviour.NOT_FOUND, null, null));
    assertTrue(registry.find("TEST-EXAMPLE").isPresent());
    assertTrue(registry.find(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK).isPresent());

    registry.clearTestScenarios();
    assertFalse(
        registry.find("TEST-EXAMPLE").isPresent(),
        "clearTestScenarios must drop TEST-prefixed entries");
    assertTrue(
        registry.find(LinearMockScenarioRegistry.TICKET_FEATURE_LOW_RISK).isPresent(),
        "clearTestScenarios must preserve the built-in production fixtures");
  }
}
