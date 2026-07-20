package org.dradgo.adapters.integration.ticketsource.linear;

import java.time.Instant;
import java.util.Collections;
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
import org.dradgo.domain.integration.ticketsource.TicketQuery;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic, fixture-backed {@link TicketSourceAdapter} implementation (the Linear kind).
 * Activated under Spring profile {@code linear-mock} — the default profile in {@code test}; opt-in
 * for {@code local}/{@code demo}.
 *
 * <p>Determinism contract (AC2, AC8, AC10):
 *
 * <ul>
 *   <li>No randomness — every behaviour is keyed to a {@code ticketRef} via {@link
 *       LinearMockScenarioRegistry}.
 *   <li>No wall-clock dependence — all timestamps come from fixture JSON files.
 *   <li>No network I/O — no {@code RestClient}, no {@code HttpClient}, no socket usage. Pinned by
 *       {@code LinearMockNetworkIsolationTest} (Task 7).
 * </ul>
 *
 * <p>Comment-posting: {@link #postGovernedRunComment(TicketRef, GovernedRunComment)} records the
 * call into an in-memory list keyed by {@code ticketRef}, deduping on {@code (ticketRef,
 * runPublicId, fingerprint)} so a replay surfaces {@link CommentResult#SKIPPED_DUPLICATE}
 * (mirroring the real adapter's idempotency no-op, whose marker is {@code run=<runPublicId>
 * fp=<fingerprint>}) rather than recording a second entry. Tests inspect via {@link
 * #postedComments()} — this accessor is NOT on the {@link TicketSourceAdapter} port (Task 3
 * invariant).
 *
 * <p>Story 3a.4 — the Linear poll team/project scope ({@code deliveryline.linear.team-key} / {@code
 * .project-id}) is a {@link LinearRealAdapter}-only concern: the mock intentionally does NOT read
 * {@code LinearProperties} scope fields and returns its deterministic fixtures regardless of any
 * configured scope.
 */
@Component
@Primary
@Profile("linear-mock")
public class LinearMockAdapter implements TicketSourceAdapter {

  private static final Logger log = LoggerFactory.getLogger(LinearMockAdapter.class);

  private final LinearMockScenarioRegistry registry;
  private final CopyOnWriteArrayList<PostedComment> postedComments = new CopyOnWriteArrayList<>();
  private final ConcurrentHashMap<CreationKey, CreateSubticketResult> createdSubtickets =
      new ConcurrentHashMap<>();

  public LinearMockAdapter(LinearMockScenarioRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public Optional<Ticket> fetchTicketByReference(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    String ticketRef = ref.value();
    Optional<LinearMockScenario> scenario = registry.find(ticketRef);
    if (scenario.isEmpty()) {
      log.info(
          "linear_mock fetch ticketRef={} resolution=empty (no scenario registered)", ticketRef);
      return Optional.empty();
    }
    LinearMockScenario configured = scenario.get();
    switch (configured.behaviour()) {
      case HAPPY:
        Ticket ticket = registry.loadHappyFixture(configured);
        log.info("linear_mock fetch ticketRef={} resolution=happy", ticketRef);
        return Optional.of(ticket);
      case NOT_FOUND:
        log.info("linear_mock fetch ticketRef={} resolution=not_found", ticketRef);
        return Optional.empty();
      case RATE_LIMITED:
      case NETWORK_FAILURE:
      case AUTH_FAILURE:
      case MALFORMED_RESPONSE:
        throw failure(configured, "fetch");
      default:
        throw new IllegalStateException("Unhandled behaviour: " + configured.behaviour());
    }
  }

  @Override
  public List<Ticket> pollNewTickets(Instant since) {
    Objects.requireNonNull(since, "since");
    List<Ticket> matched =
        registry.all().values().stream()
            .filter(scenario -> scenario.behaviour() == LinearMockScenario.Behaviour.HAPPY)
            .map(registry::loadHappyFixture)
            .filter(ticket -> ticket.updatedAt().isAfter(since))
            .sorted(Comparator.comparing(Ticket::updatedAt))
            .collect(Collectors.toList());
    log.info("linear_mock poll since={} returned={} tickets", since, matched.size());
    return Collections.unmodifiableList(matched);
  }

  /**
   * Story 3i-2 — parity with {@code LinearRealAdapter}: the Linear kind does not advertise {@code
   * supportsTicketQuery}, so the capability-gated intake surface never reaches this method.
   */
  @Override
  public TicketQueryResult queryTickets(TicketQuery query) {
    throw new UnsupportedOperationException("queryTickets not supported for linear");
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
          "linear_mock comment_skipped ticketRef={} runPublicId={} fingerprint={} reason=already_posted",
          ticketRef,
          summary.runPublicId(),
          summary.fingerprint());
      return CommentResult.SKIPPED_DUPLICATE;
    }
    postedComments.add(new PostedComment(ticketRef, summary));
    log.info(
        "linear_mock comment_recorded ticketRef={} runPublicId={} fingerprint={}",
        ticketRef,
        summary.runPublicId(),
        summary.fingerprint());
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
          "linear_mock subticket_replay parentTicketRef={} childTicketRef={} workflowRunId={} subtaskOrdinal={} idempotencyKey={}",
          parentRef.value(),
          existing.childRef().value(),
          draft.parentRunId(),
          draft.ordinal(),
          draft.idempotencyKey());
      return new CreateSubticketResult(
          existing.childRef(),
          existing.idempotencyKey(),
          existing.parentLinkFingerprint(),
          true,
          existing.metadata());
    }
    TicketRef childRef = TicketRef.of(parentRef.value() + "-" + draft.ordinal());
    String parentLinkFingerprint = "subticket:" + draft.idempotencyKey();
    CreateSubticketResult created =
        new CreateSubticketResult(
            childRef,
            draft.idempotencyKey(),
            parentLinkFingerprint,
            false,
            Map.of("source", "linear-mock", "subtaskOrdinal", Integer.toString(draft.ordinal())));
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
            org.dradgo.domain.registry.DataClassification.SHAREABLE_REDACTED));
    log.info(
        "linear_mock subticket_created parentTicketRef={} childTicketRef={} workflowRunId={} subtaskOrdinal={} idempotencyKey={}",
        parentRef.value(),
        childRef.value(),
        draft.parentRunId(),
        draft.ordinal(),
        draft.idempotencyKey());
    return created;
  }

  @Override
  public ConnectorKind connectorKind() {
    return ConnectorKind.LINEAR;
  }

  @Override
  public Optional<String> buildSourceTicketUrl(TicketRef ref) {
    Objects.requireNonNull(ref, "ref");
    // Deterministic stub URL (no randomness, no wall-clock): a fixed mock base + the ref. Distinct
    // host from the real adapter so a mock-sourced origin URL is never mistaken for a live link.
    String url = "https://linear.mock/issue/" + ref.value();
    log.debug(
        "linear_mock build_source_ticket_url ticketRef={} present=true",
        MdcKeys.sanitizeForLog(ref.value()));
    return Optional.of(url);
  }

  @Override
  public TicketSourceCapabilities getCapabilities() {
    return TicketSourceCapabilities.linearDefaults();
  }

  @Override
  public ConnectivityResult verifyConnectivity(String credentialOverride) {
    // Deterministic, network-free probe (story 3c-8): the mock is always reachable + authenticated.
    // The credential override is irrelevant to the mock (no real auth) and is never logged.
    log.info("linear_mock verify_connectivity resolution=ok");
    return ConnectivityResult.ok("linear-mock: deterministic reachable + authenticated");
  }

  /**
   * Test-only accessor returning the recorded comment-post call history. Returns an immutable
   * snapshot. Not part of the {@link TicketSourceAdapter} port — only adapter-scope tests should
   * depend on it.
   */
  public List<PostedComment> postedComments() {
    return List.copyOf(postedComments);
  }

  /** Test-only utility — clear the recorded comments between scenarios. */
  public void clearPostedComments() {
    postedComments.clear();
  }

  /** Test-only accessor returning source sub-ticket creation history. */
  public Map<String, CreateSubticketResult> createdSubtickets() {
    return createdSubtickets.entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(entry -> entry.getKey().asString(), Map.Entry::getValue));
  }

  /** Test-only utility - clear recorded sub-ticket creation history. */
  public void clearCreatedSubtickets() {
    createdSubtickets.clear();
  }

  private static TicketSourceAdapterException failure(
      LinearMockScenario scenario, String operation) {
    IntegrationFailureCategory category = scenario.expectedFailureCategory();
    if (category == null) {
      category =
          switch (scenario.behaviour()) {
            case RATE_LIMITED, NETWORK_FAILURE -> IntegrationFailureCategory.NETWORK_API_FAILURE;
            case AUTH_FAILURE -> IntegrationFailureCategory.LINK_FAILURE;
            case MALFORMED_RESPONSE -> IntegrationFailureCategory.SYNC_FAILURE;
            default -> IntegrationFailureCategory.SYNC_FAILURE;
          };
    }
    return new TicketSourceAdapterException(
        category,
        "linear_mock "
            + operation
            + " simulated "
            + scenario.behaviour().name()
            + " for ticketRef="
            + scenario.ticketRef());
  }

  /** In-memory record of a {@link #postGovernedRunComment} call. */
  public record PostedComment(String ticketRef, GovernedRunComment comment) {}

  private record CreationKey(String parentTicketRef, String idempotencyKey) {
    private String asString() {
      return parentTicketRef + "|" + idempotencyKey;
    }
  }
}
