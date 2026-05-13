package org.dradgo.infrastructure.config;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poll loop for the real Linear adapter (story 1.14 Task 4 / AC9). Active when:
 * <ul>
 *   <li>profile {@code linear-real} is active, AND</li>
 *   <li>{@code deliveryline.linear.polling.enabled} is true (default true; missing = true).</li>
 * </ul>
 *
 * <p>This is a watcher in Epic 1 — it calls {@link LinearAdapter#pollNewTickets(Instant)} on the
 * configured cadence and logs results, but the "feeds new tickets into {@code
 * IntegrationLinkService}" wiring lands in story 1.14 Task 5 (linkTicket body) and story 1.15
 * (CLI submit). Keeping the host bean present in Task 4 satisfies AC9's "polling interval is
 * configurable" pin and gives ops a single switch ({@code polling.enabled=false}) to mute the
 * loop without code changes.
 */
@Component
@Profile("linear-real")
@ConditionalOnProperty(name = "deliveryline.linear.polling.enabled", matchIfMissing = true)
@EnableScheduling
public class LinearPollingHost {

	private static final Logger log = LoggerFactory.getLogger(LinearPollingHost.class);

	private final LinearAdapter linearAdapter;
	private final Clock clock;
	private final AtomicReference<Instant> lastPollAt;

	public LinearPollingHost(LinearAdapter linearAdapter) {
		this(linearAdapter, Clock.systemUTC());
	}

	LinearPollingHost(LinearAdapter linearAdapter, Clock clock) {
		this.linearAdapter = Objects.requireNonNull(linearAdapter, "linearAdapter");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.lastPollAt = new AtomicReference<>(Instant.now(clock));
	}

	@Scheduled(fixedDelayString = "${deliveryline.linear.poll-interval-ms:60000}")
	public void pollLinear() {
		Instant since = lastPollAt.get();
		Instant nextSince = Instant.now(clock);
		try {
			List<LinearTicket> tickets = linearAdapter.pollNewTickets(since);
			if (tickets.isEmpty()) {
				log.info("linear_real polling_batch since={} count=0", since);
			} else {
				LinearTicket newest = tickets.get(tickets.size() - 1);
				log.info("linear_real polling_batch since={} count={} oldest={} newest={}",
					since, tickets.size(), tickets.get(0).updatedAt(), newest.updatedAt());
				// Advance the cursor to the newest seen updatedAt — subsequent polls return only
				// tickets strictly after this point. Story 1.15 wires the actual link-or-skip
				// decision; Epic 1 polling is observation-only.
				nextSince = newest.updatedAt();
			}
		} catch (LinearAdapterException error) {
			log.warn("linear_real polling_failed since={} category={} message={}",
				since, error.failureCategory().value(), error.getMessage());
			// Do not advance the cursor on failure — the next poll retries the same window.
			return;
		}
		lastPollAt.set(nextSince);
	}

	/** Test-only accessor — last successfully polled high-water-mark. */
	public Instant lastPollAt() {
		return lastPollAt.get();
	}
}
