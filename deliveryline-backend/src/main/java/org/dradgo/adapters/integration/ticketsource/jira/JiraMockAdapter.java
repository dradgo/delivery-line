package org.dradgo.adapters.integration.ticketsource.jira;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.dradgo.application.integration.ConnectivityResult;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.integration.ticketsource.CommentResult;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.GovernedRunComment;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic, network-free {@link TicketSourceAdapter} for the JIRA kind (story 3i-1 Task 4).
 * Activated under Spring profile {@code jira-mock} (opt-in for {@code local}/{@code demo}). The
 * parity twin of {@code JiraRealAdapter}, mirroring {@code LinearMockAdapter}'s determinism
 * contract:
 *
 * <ul>
 *   <li>No randomness / no wall-clock — a fetched ticket is synthesized deterministically from its
 *       key with fixed timestamps.
 *   <li>No network I/O — no {@code RestClient}, no socket usage.
 *   <li>Sub-task creation is deterministic and JIRA-shaped with an in-memory replay map; comment
 *       posting dedups in memory on {@code (ref, runPublicId, fingerprint)} to surface {@link
 *       CommentResult#SKIPPED_DUPLICATE}.
 * </ul>
 *
 * <p>Unlike {@code LinearMockAdapter} (which loads fixture JSON via a scenario registry), the JIRA
 * mock synthesizes its happy ticket in-line — no fixture files — and exposes tiny test-only {@code
 * registerNotFound}/{@code registerFailure} hooks so the mock↔real parity contract can drive the
 * not-found and classified-failure branches. An unregistered well-formed key resolves to a
 * deterministic HAPPY ticket so the {@code jira-mock} profile is immediately usable.
 */
@Component
@Profile("jira-mock")
public class JiraMockAdapter implements TicketSourceAdapter {

  private static final Logger log = LoggerFactory.getLogger(JiraMockAdapter.class);

  private static final Instant FIXED_CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant FIXED_UPDATED = Instant.parse("2026-01-02T00:00:00Z");
  private static final String MOCK_STATUS_NAME = "To Do";
  private static final String MOCK_STATUS_ID = "10000";

  private final CopyOnWriteArrayList<PostedComment> postedComments = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<CreationKey, CreateSubticketResult> createdSubtickets =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Scenario> scenarios = new ConcurrentHashMap<>();

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.JIRA;
  }

  @Override
  public Optional<Ticket> fetchTicketByReference(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    String ticketRef = ref.value();
    Scenario scenario = scenarios.getOrDefault(ticketRef, Scenario.HAPPY);
    switch (scenario.behaviour()) {
      case HAPPY -> {
        log.info("jira_mock fetch ticketRef={} resolution=happy", ticketRef);
        return Optional.of(happyTicket(ticketRef));
      }
      case NOT_FOUND -> {
        log.info("jira_mock fetch ticketRef={} resolution=not_found", ticketRef);
        return Optional.empty();
      }
      case FAILURE -> throw failure(scenario, ticketRef, "fetch");
      default -> throw new IllegalStateException("Unhandled behaviour: " + scenario.behaviour());
    }
  }

  @Override
  public List<Ticket> pollNewTickets(Instant since) {
    Objects.requireNonNull(since, "since");
    List<Ticket> matched = new ArrayList<>();
    for (Map.Entry<String, Scenario> entry : scenarios.entrySet()) {
      if (entry.getValue().behaviour() == Behaviour.HAPPY) {
        Ticket ticket = happyTicket(entry.getKey());
        if (ticket.updatedAt().isAfter(since)) {
          matched.add(ticket);
        }
      }
    }
    matched.sort(Comparator.comparing(Ticket::updatedAt));
    log.info("jira_mock poll since={} returned={} tickets", since, matched.size());
    return List.copyOf(matched);
  }

  @Override
  public CommentResult postGovernedRunComment(TicketRef ref, GovernedRunComment summary) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(summary, "summary");
    String ticketRef = ref.value();
    boolean duplicate =
        postedComments.stream()
            .anyMatch(
                posted ->
                    posted.ticketRef().equals(ticketRef)
                        && posted.comment().runPublicId().equals(summary.runPublicId())
                        && posted.comment().fingerprint().equals(summary.fingerprint()));
    if (duplicate) {
      log.info(
          "jira_mock comment_skipped ticketRef={} runPublicId={} fingerprint={} reason=already_posted",
          MdcKeys.sanitizeForLog(ticketRef),
          MdcKeys.sanitizeForLog(summary.runPublicId()),
          MdcKeys.sanitizeForLog(summary.fingerprint()));
      return CommentResult.SKIPPED_DUPLICATE;
    }
    postedComments.add(new PostedComment(ticketRef, summary));
    log.info(
        "jira_mock comment_recorded ticketRef={} runPublicId={} fingerprint={}",
        MdcKeys.sanitizeForLog(ticketRef),
        MdcKeys.sanitizeForLog(summary.runPublicId()),
        MdcKeys.sanitizeForLog(summary.fingerprint()));
    return CommentResult.POSTED;
  }

  @Override
  public CreateSubticketResult createSubticket(TicketRef parentRef, SubticketDraft draft) {
    Objects.requireNonNull(parentRef, "parentRef");
    Objects.requireNonNull(draft, "draft");
    CreationKey key = new CreationKey(parentRef.value(), draft.idempotencyKey());
    CreateSubticketResult existing = createdSubtickets.get(key);
    if (existing != null) {
      log.info(
          "jira_mock subticket_replay parentTicketRef={} childTicketRef={} workflowRunId={} subtaskOrdinal={} idempotencyKey={}",
          MdcKeys.sanitizeForLog(parentRef.value()),
          MdcKeys.sanitizeForLog(existing.childRef().value()),
          MdcKeys.sanitizeForLog(draft.parentRunId()),
          draft.ordinal(),
          MdcKeys.sanitizeForLog(draft.idempotencyKey()));
      return new CreateSubticketResult(
          existing.childRef(),
          existing.idempotencyKey(),
          existing.parentLinkFingerprint(),
          true,
          existing.metadata());
    }
    TicketRef childRef = TicketRef.of(mockChildRef(parentRef, draft));
    String parentLinkFingerprint = "subticket:" + draft.idempotencyKey();
    CreateSubticketResult created =
        new CreateSubticketResult(
            childRef,
            draft.idempotencyKey(),
            parentLinkFingerprint,
            false,
            Map.of("source", "jira-mock", "subtaskOrdinal", Integer.toString(draft.ordinal())));
    CreateSubticketResult raced = createdSubtickets.putIfAbsent(key, created);
    if (raced != null) {
      return new CreateSubticketResult(
          raced.childRef(),
          raced.idempotencyKey(),
          raced.parentLinkFingerprint(),
          true,
          raced.metadata());
    }
    postGovernedRunComment(
        parentRef,
        new GovernedRunComment(
            draft.parentRunId(),
            parentLinkFingerprint,
            "Created split child ticket " + childRef.value(),
            DataClassification.SHAREABLE_REDACTED));
    log.info(
        "jira_mock subticket_created parentTicketRef={} childTicketRef={} workflowRunId={} subtaskOrdinal={} idempotencyKey={}",
        MdcKeys.sanitizeForLog(parentRef.value()),
        MdcKeys.sanitizeForLog(childRef.value()),
        MdcKeys.sanitizeForLog(draft.parentRunId()),
        draft.ordinal(),
        MdcKeys.sanitizeForLog(draft.idempotencyKey()));
    return created;
  }

  @Override
  public Optional<String> buildSourceTicketUrl(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    // Deterministic stub URL (no randomness/wall-clock) on a distinct mock host so a mock-sourced
    // origin URL is never mistaken for a live JIRA link.
    String url = "https://jira.mock/browse/" + ref.value();
    log.debug(
        "jira_mock build_source_ticket_url ticketRef={} present=true",
        MdcKeys.sanitizeForLog(ref.value()));
    return Optional.of(url);
  }

  @Override
  public TicketSourceCapabilities getCapabilities() {
    return TicketSourceCapabilities.jiraDefaults();
  }

  @Override
  public ConnectivityResult verifyConnectivity(String credentialOverride) {
    // Deterministic, network-free probe: the mock is always reachable + authenticated. The
    // credential override is irrelevant (no real auth) and is never logged.
    log.info("jira_mock verify_connectivity resolution=ok");
    return ConnectivityResult.ok("jira-mock: deterministic reachable + authenticated");
  }

  // -------- test-only scenario hooks + accessors ----------------------------------------------

  /** Test-only — mark a ticket ref as not-found ({@code fetchTicketByReference} → empty). */
  public void registerNotFound(String ticketRef) {
    scenarios.put(ticketRef, Scenario.NOT_FOUND);
  }

  /** Test-only — mark a ticket ref as failing with the given classified category. */
  public void registerFailure(String ticketRef, IntegrationFailureCategory category) {
    scenarios.put(ticketRef, new Scenario(Behaviour.FAILURE, category));
  }

  /** Test-only — mark a ticket ref as a happy, pollable ticket. */
  public void registerHappy(String ticketRef) {
    scenarios.put(ticketRef, Scenario.HAPPY);
  }

  /** Test-only accessor returning the recorded comment-post history (immutable snapshot). */
  public List<PostedComment> postedComments() {
    return List.copyOf(postedComments);
  }

  /** Test-only utility — clear recorded comments between scenarios. */
  public void clearPostedComments() {
    postedComments.clear();
  }

  /** Test-only accessor returning sub-ticket creation history. */
  public Map<String, CreateSubticketResult> createdSubtickets() {
    return createdSubtickets.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(entry -> entry.getKey().asString(), Map.Entry::getValue));
  }

  /** Test-only utility — clear recorded sub-ticket creation history. */
  public void clearCreatedSubtickets() {
    createdSubtickets.clear();
  }

  private static String mockChildRef(TicketRef parentRef, SubticketDraft draft) {
    String parent = parentRef.value();
    int dash = parent.lastIndexOf('-');
    if (dash <= 0 || dash == parent.length() - 1) {
      return parent + draft.ordinal();
    }
    String projectKey = parent.substring(0, dash);
    int parentNumber = Integer.parseInt(parent.substring(dash + 1));
    return projectKey + "-" + ((parentNumber * 1000) + draft.ordinal());
  }

  private static Ticket happyTicket(String ticketRef) {
    return new Ticket(
        TicketRef.of(ticketRef),
        "JIRA mock ticket " + ticketRef,
        "Deterministic mock summary for " + ticketRef,
        "mock-user@example.com",
        FIXED_CREATED,
        FIXED_UPDATED,
        Map.of("mock", ""),
        MOCK_STATUS_NAME,
        MOCK_STATUS_ID);
  }

  private static TicketSourceAdapterException failure(
      Scenario scenario, String ticketRef, String operation) {
    IntegrationFailureCategory category = scenario.category();
    return new TicketSourceAdapterException(
        category == null ? IntegrationFailureCategory.SYNC_FAILURE : category,
        "jira_mock " + operation + " simulated " + category + " for ticketRef=" + ticketRef);
  }

  /** In-memory record of a {@link #postGovernedRunComment} call. */
  public record PostedComment(String ticketRef, GovernedRunComment comment) {}

  private enum Behaviour {
    HAPPY,
    NOT_FOUND,
    FAILURE
  }

  private record Scenario(Behaviour behaviour, IntegrationFailureCategory category) {
    private static final Scenario HAPPY = new Scenario(Behaviour.HAPPY, null);
    private static final Scenario NOT_FOUND = new Scenario(Behaviour.NOT_FOUND, null);
  }

  private record CreationKey(String parentTicketRef, String idempotencyKey) {
    private String asString() {
      return parentTicketRef + "|" + idempotencyKey;
    }
  }
}
