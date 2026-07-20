package org.dradgo.adapters.integration.ticketsource.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.integration.ConnectivityResult;
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
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.junit.jupiter.api.Test;

/** Story 3i-1 Task 4 — deterministic, network-free JIRA mock adapter behaviour. */
class JiraMockAdapterUnitTest {

  private final JiraMockAdapter adapter = new JiraMockAdapter();

  @Test
  void declaresJiraKind() {
    assertThat(adapter.connectorKind()).isEqualTo(ConnectorKind.JIRA);
  }

  @Test
  void fetchReturnsDeterministicHappyTicketForAnyWellFormedKey() {
    Optional<Ticket> ticket = adapter.fetchTicketByReference(TicketRef.of("PROJ-42"));

    assertThat(ticket).isPresent();
    assertThat(ticket.get().ticketRef().value()).isEqualTo("PROJ-42");
    assertThat(ticket.get().sourceStatus()).isNotBlank();
    assertThat(ticket.get().sourceStatusId()).isNotBlank();
  }

  @Test
  void registeredNotFoundRefResolvesEmpty() {
    adapter.registerNotFound("PROJ-404");
    assertThat(adapter.fetchTicketByReference(TicketRef.of("PROJ-404"))).isEmpty();
  }

  @Test
  void registeredFailureSurfacesTheSameCategory() {
    adapter.registerFailure("PROJ-429", IntegrationFailureCategory.NETWORK_API_FAILURE);

    IntegrationFailureCategory category =
        assertThrows(
                TicketSourceAdapterException.class,
                () -> adapter.fetchTicketByReference(TicketRef.of("PROJ-429")))
            .failureCategory();

    assertThat(category).isEqualTo(IntegrationFailureCategory.NETWORK_API_FAILURE);
  }

  @Test
  void commentPostThenReplayDedupes() {
    GovernedRunComment comment =
        new GovernedRunComment("run_1", "fp_1", "body", DataClassification.SHAREABLE_REDACTED);

    assertThat(adapter.postGovernedRunComment(TicketRef.of("PROJ-1"), comment))
        .isEqualTo(CommentResult.POSTED);
    assertThat(adapter.postGovernedRunComment(TicketRef.of("PROJ-1"), comment))
        .isEqualTo(CommentResult.SKIPPED_DUPLICATE);
  }

  @Test
  void createSubticketIsDeterministicAndReplaySafe() {
    SubticketDraft draft =
        new SubticketDraft(
            "run_parent", "proposal_1", "subtask_1", 3, "Title", "Body", "split:run_parent:1");

    CreateSubticketResult first = adapter.createSubticket(TicketRef.of("PROJ-9"), draft);
    assertThat(first.childRef().value()).isEqualTo("PROJ-9003");
    assertThat(first.replay()).isFalse();
    // Parent-link comment recorded through the adapter's own postGovernedRunComment.
    assertThat(adapter.postedComments()).hasSize(1);

    CreateSubticketResult replay = adapter.createSubticket(TicketRef.of("PROJ-9"), draft);
    assertThat(replay.replay()).isTrue();
    assertThat(replay.childRef()).isEqualTo(first.childRef());
  }

  @Test
  void buildSourceTicketUrlUsesDeterministicMockHost() {
    assertThat(adapter.buildSourceTicketUrl(TicketRef.of("PROJ-7")))
        .contains("https://jira.mock/browse/PROJ-7");
  }

  @Test
  void capabilitiesAndConnectivityAreTheJiraFullSet() {
    assertThat(adapter.getCapabilities().supportsTicketCreation()).isTrue();
    ConnectivityResult result = adapter.verifyConnectivity(null);
    assertThat(result.reachable()).isTrue();
    assertThat(result.authenticated()).isTrue();
  }

  @Test
  void pollReturnsRegisteredHappyTicketsUpdatedAfterSince() {
    adapter.registerHappy("PROJ-100");
    // Fixed mock updatedAt is 2026-01-02; a since before it returns the ticket, after it excludes.
    assertThat(adapter.pollNewTickets(Instant.parse("2026-01-01T00:00:00Z"))).hasSize(1);
    assertThat(adapter.pollNewTickets(Instant.parse("2026-02-01T00:00:00Z"))).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Story 3i-2 (AC1/AC8) — deterministic candidate browse
  // -------------------------------------------------------------------------------------------

  @Test
  void queryAdvertisesTheCapabilityAndReturnsRegisteredHappyTickets() {
    assertThat(adapter.getCapabilities().supportsTicketQuery()).isTrue();
    adapter.registerHappy("PROJ-100");
    adapter.registerHappy("PROJ-101");

    TicketQueryResult result = adapter.queryTickets(TicketQuery.unfiltered());
    List<TicketSummary> tickets = result.tickets();

    // Ordered by ref (every mock ticket shares a fixed updatedAt), so this is deterministic.
    assertThat(tickets)
        .extracting(t -> t.ticketRef().value())
        .containsExactly("PROJ-100", "PROJ-101");
    assertThat(tickets.get(0).title()).isEqualTo("JIRA mock ticket PROJ-100");
  }

  @Test
  void queryHonoursTheLimit() {
    adapter.registerHappy("PROJ-100");
    adapter.registerHappy("PROJ-101");
    adapter.registerHappy("PROJ-102");

    assertThat(adapter.queryTickets(new TicketQuery(null, List.of(), null, 2)).tickets())
        .hasSize(2);
  }

  /**
   * `total` counts matches at the source, BEFORE the limit cap. Deriving it from the capped page
   * would make `truncated` permanently false and let a real truncation regression pass parity.
   */
  @Test
  void queryReportsTheUncappedTotalAndFlagsTruncation() {
    adapter.registerHappy("PROJ-100");
    adapter.registerHappy("PROJ-101");
    adapter.registerHappy("PROJ-102");

    TicketQueryResult capped = adapter.queryTickets(new TicketQuery(null, List.of(), null, 2));
    assertThat(capped.tickets()).hasSize(2);
    assertThat(capped.total()).isEqualTo(3);
    assertThat(capped.truncated()).isTrue();

    TicketQueryResult whole = adapter.queryTickets(new TicketQuery(null, List.of(), null, 50));
    assertThat(whole.tickets()).hasSize(3);
    assertThat(whole.total()).isEqualTo(3);
    assertThat(whole.truncated()).isFalse();
  }

  /** A filter that matches nothing reports a zero total, not a truncated empty page. */
  @Test
  void queryWithNoMatchesIsEmptyAndNotTruncated() {
    adapter.registerHappy("PROJ-100");

    TicketQueryResult result =
        adapter.queryTickets(new TicketQuery("someone-else", List.of(), null, 50));

    assertThat(result.tickets()).isEmpty();
    assertThat(result.total()).isZero();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void queryExcludesNonHappyScenarios() {
    adapter.registerHappy("PROJ-100");
    adapter.registerNotFound("PROJ-404");
    adapter.registerFailure("PROJ-500", IntegrationFailureCategory.NETWORK_API_FAILURE);

    assertThat(adapter.queryTickets(TicketQuery.unfiltered()).tickets())
        .extracting(t -> t.ticketRef().value())
        .containsExactly("PROJ-100");
  }

  /** A present filter must match the synthesized mock fields; an absent one constrains nothing. */
  @Test
  void queryFiltersOnAssigneeComponentAndState() {
    adapter.registerHappy("PROJ-100");

    assertThat(
            adapter
                .queryTickets(new TicketQuery("mock-user@example.com", List.of(), null, 50))
                .tickets())
        .hasSize(1);
    assertThat(adapter.queryTickets(new TicketQuery("someone-else", List.of(), null, 50)).tickets())
        .isEmpty();

    assertThat(adapter.queryTickets(new TicketQuery(null, List.of("mock"), null, 50)).tickets())
        .hasSize(1);
    assertThat(adapter.queryTickets(new TicketQuery(null, List.of("other"), null, 50)).tickets())
        .isEmpty();
    // Any-of semantics: one matching component in the list is enough.
    assertThat(
            adapter
                .queryTickets(new TicketQuery(null, List.of("other", "mock"), null, 50))
                .tickets())
        .hasSize(1);

    assertThat(adapter.queryTickets(new TicketQuery(null, List.of(), "To Do", 50)).tickets())
        .hasSize(1);
    assertThat(adapter.queryTickets(new TicketQuery(null, List.of(), "Done", 50)).tickets())
        .isEmpty();
  }

  @Test
  void queryMatchesFiltersCaseInsensitively() {
    adapter.registerHappy("PROJ-100");

    assertThat(
            adapter
                .queryTickets(
                    new TicketQuery("MOCK-USER@EXAMPLE.COM", List.of("MOCK"), "to do", 50))
                .tickets())
        .hasSize(1);
  }
}
