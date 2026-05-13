package org.dradgo.adapters.integration.linear;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.dradgo.application.integration.linear.GovernedRunComment;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic, fixture-backed {@link LinearAdapter} implementation. Activated under Spring
 * profile {@code linear-mock} — the default profile in {@code test}; opt-in for
 * {@code local}/{@code demo}.
 *
 * <p>Determinism contract (AC2, AC8, AC10):
 * <ul>
 *   <li>No randomness — every behaviour is keyed to a {@code ticketRef} via
 *       {@link LinearMockScenarioRegistry}.</li>
 *   <li>No wall-clock dependence — all timestamps come from fixture JSON files.</li>
 *   <li>No network I/O — no {@code RestClient}, no {@code HttpClient}, no socket usage.
 *       Pinned by {@code LinearMockNetworkIsolationTest} (Task 7).</li>
 * </ul>
 *
 * <p>Comment-posting: {@link #postGovernedRunComment(String, GovernedRunComment)} records the
 * call into an in-memory list keyed by {@code ticketRef}. Tests inspect via {@link #postedComments()}
 * — this accessor is NOT on the {@link LinearAdapter} port (Task 3 invariant).
 */
@Component
@Profile("linear-mock")
public class LinearMockAdapter implements LinearAdapter {

	private static final Logger log = LoggerFactory.getLogger(LinearMockAdapter.class);

	private final LinearMockScenarioRegistry registry;
	private final CopyOnWriteArrayList<PostedComment> postedComments = new CopyOnWriteArrayList<>();

	public LinearMockAdapter(LinearMockScenarioRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
	}

	@Override
	public Optional<LinearTicket> fetchTicketByReference(String ticketRef) {
		Objects.requireNonNull(ticketRef, "ticketRef");
		Optional<LinearMockScenario> scenario = registry.find(ticketRef);
		if (scenario.isEmpty()) {
			log.info("linear_mock fetch ticketRef={} resolution=empty (no scenario registered)", ticketRef);
			return Optional.empty();
		}
		LinearMockScenario configured = scenario.get();
		switch (configured.behaviour()) {
			case HAPPY:
				LinearTicket ticket = registry.loadHappyFixture(configured);
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
	public List<LinearTicket> pollNewTickets(Instant since) {
		Objects.requireNonNull(since, "since");
		List<LinearTicket> matched = registry.all().values().stream()
			.filter(scenario -> scenario.behaviour() == LinearMockScenario.Behaviour.HAPPY)
			.map(registry::loadHappyFixture)
			.filter(ticket -> ticket.updatedAt().isAfter(since))
			.sorted(Comparator.comparing(LinearTicket::updatedAt))
			.collect(Collectors.toList());
		log.info("linear_mock poll since={} returned={} tickets", since, matched.size());
		return Collections.unmodifiableList(matched);
	}

	@Override
	public void postGovernedRunComment(String ticketRef, GovernedRunComment summary) {
		Objects.requireNonNull(ticketRef, "ticketRef");
		Objects.requireNonNull(summary, "summary");
		postedComments.add(new PostedComment(ticketRef, summary));
		log.info(
			"linear_mock comment_recorded ticketRef={} runPublicId={} fingerprint={}",
			ticketRef, summary.runPublicId(), summary.fingerprint());
	}

	/**
	 * Test-only accessor returning the recorded comment-post call history. Returns an immutable
	 * snapshot. Not part of the {@link LinearAdapter} port — only adapter-scope tests should
	 * depend on it.
	 */
	public List<PostedComment> postedComments() {
		return List.copyOf(postedComments);
	}

	/** Test-only utility — clear the recorded comments between scenarios. */
	public void clearPostedComments() {
		postedComments.clear();
	}

	private static LinearAdapterException failure(LinearMockScenario scenario, String operation) {
		IntegrationFailureCategory category = scenario.expectedFailureCategory();
		if (category == null) {
			category = switch (scenario.behaviour()) {
				case RATE_LIMITED, NETWORK_FAILURE -> IntegrationFailureCategory.NETWORK_API_FAILURE;
				case AUTH_FAILURE -> IntegrationFailureCategory.LINK_FAILURE;
				case MALFORMED_RESPONSE -> IntegrationFailureCategory.SYNC_FAILURE;
				default -> IntegrationFailureCategory.SYNC_FAILURE;
			};
		}
		return new LinearAdapterException(
			category,
			"linear_mock " + operation + " simulated " + scenario.behaviour().name()
				+ " for ticketRef=" + scenario.ticketRef());
	}

	/** In-memory record of a {@link #postGovernedRunComment} call. */
	public record PostedComment(String ticketRef, GovernedRunComment comment) {
	}
}
