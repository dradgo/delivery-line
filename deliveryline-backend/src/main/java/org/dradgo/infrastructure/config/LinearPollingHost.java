package org.dradgo.infrastructure.config;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poll loop for the real Linear adapter (story 1.14 Task 4 / AC9). Active when:
 *
 * <ul>
 *   <li>profile {@code linear-real} is active, AND
 *   <li>{@code deliveryline.linear.polling.enabled} is true (default true; missing = true).
 * </ul>
 *
 * <p>This is a <em>watcher</em> in Epic 1 — it observes ticket updates and refreshes {@code
 * integration_links.last_sync_at} on matching active rows (AC9 "last_sync_at is updated on each
 * successful poll"). The polling loop does <strong>not</strong> create new integration links —
 * ingestion happens via CLI {@code submit} (story 1.15).
 *
 * <p>Watermark management (story 1.14 review finding 2):
 *
 * <ul>
 *   <li><strong>Seeding:</strong> {@link #seedWatermark()} runs once at bean construction (via
 *       {@code @PostConstruct}) and seeds {@code lastPollAt} from {@link
 *       IntegrationLinkRecordPort#findMaxLastSyncAtForType(String)}. This prevents a JVM restart
 *       from forgetting prior progress (the cursor was previously held in JVM heap only). When no
 *       active linear links exist, the seed falls back to {@code Instant.now(clock)}.
 *   <li><strong>Advancement:</strong> {@link #pollLinear()} drains all pages in the current poll
 *       window via {@link LinearAdapter#pollNewTickets(Instant)} (the real adapter walks GraphQL
 *       cursor pagination internally). The cursor advances to {@code max(updatedAt)} of the
 *       returned set, which is monotonic because the adapter sorts ascending. No advancement on
 *       error.
 *   <li><strong>Per-ticket touch:</strong> Every observed ticket triggers {@link
 *       IntegrationLinkRecordPort#touchLastSyncAtByTypeAndExternalRef(String, String, Instant)} so
 *       the {@code last_sync_at} column on matching active rows reflects the latest observation —
 *       without forcing a {@code sync_status} transition.
 * </ul>
 */
@Component
@Profile("linear-real")
@ConditionalOnProperty(name = "deliveryline.linear.polling.enabled", matchIfMissing = true)
@EnableScheduling
public class LinearPollingHost {

  private static final Logger log = LoggerFactory.getLogger(LinearPollingHost.class);
  private static final Instant SAFE_WATERMARK_FLOOR = Instant.EPOCH;

  /** Integration-type literal — mirrors the V1 {@code integration_links.integration_type} CHECK. */
  private static final String INTEGRATION_TYPE_LINEAR = "linear";

  private final LinearAdapter linearAdapter;
  private final IntegrationLinkRecordPort integrationLinkRecordPort;
  private final UuidV7Generator uuidV7Generator;
  private final Clock clock;
  private final AtomicReference<Instant> lastPollAt;

  public LinearPollingHost(
      LinearAdapter linearAdapter,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      UuidV7Generator uuidV7Generator) {
    this(linearAdapter, integrationLinkRecordPort, uuidV7Generator, Clock.systemUTC());
  }

  LinearPollingHost(
      LinearAdapter linearAdapter,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      UuidV7Generator uuidV7Generator,
      Clock clock) {
    this.linearAdapter = Objects.requireNonNull(linearAdapter, "linearAdapter");
    this.integrationLinkRecordPort =
        Objects.requireNonNull(integrationLinkRecordPort, "integrationLinkRecordPort");
    this.uuidV7Generator = Objects.requireNonNull(uuidV7Generator, "uuidV7Generator");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lastPollAt = new AtomicReference<>(Instant.now(clock));
  }

  @PostConstruct
  void seedWatermark() {
    String priorCorrelationMdc =
        MdcKeys.beginScope(MdcKeys.CORRELATION_ID, uuidV7Generator.generate());
    try {
      Instant noActiveLinksFallback = Instant.now(clock);
      Instant seed;
      try {
        seed =
            integrationLinkRecordPort
                .findMaxLastSyncAtForType(INTEGRATION_TYPE_LINEAR)
                .orElse(noActiveLinksFallback);
      } catch (RuntimeException error) {
        log.warn(
            "linear_real polling_watermark seed_failed cause={} fallback=safe_floor",
            error.getClass().getSimpleName());
        seed = SAFE_WATERMARK_FLOOR;
      }
      lastPollAt.set(seed);
      log.info(
          "linear_real polling_watermark seeded={} source={}",
          seed,
          seed.equals(SAFE_WATERMARK_FLOOR)
              ? "safe_floor_after_seed_failure"
              : seed.equals(noActiveLinksFallback)
                  ? "clock_fallback_no_active_links"
                  : "integration_links_max_last_sync_at");
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
    }
  }

  @Scheduled(fixedDelayString = "${deliveryline.linear.poll-interval-ms:60000}")
  public void pollLinear() {
    String priorCorrelationMdc =
        MdcKeys.beginScope(MdcKeys.CORRELATION_ID, uuidV7Generator.generate());
    try {
      pollLinearInternal();
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationMdc);
    }
  }

  private void pollLinearInternal() {
    Instant since = lastPollAt.get();
    List<LinearTicket> tickets;
    try {
      tickets = linearAdapter.pollNewTickets(since);
    } catch (LinearAdapterException error) {
      log.warn(
          "linear_real polling_failed since={} category={} message={}",
          since,
          error.failureCategory().value(),
          error.getMessage());
      // Cursor stays put — the next interval retries the same window.
      return;
    }
    if (tickets.isEmpty()) {
      log.info("linear_real polling_batch since={} count=0", since);
      return;
    }
    LinearTicket newest = tickets.get(tickets.size() - 1);
    int touched = 0;
    int skipped = 0;
    int touchFailures = 0;
    for (LinearTicket ticket : tickets) {
      try {
        boolean updated =
            integrationLinkRecordPort.touchLastSyncAtByTypeAndExternalRef(
                INTEGRATION_TYPE_LINEAR, ticket.ticketRef(), ticket.updatedAt());
        if (updated) {
          touched++;
        } else {
          skipped++;
        }
      } catch (RuntimeException error) {
        // Best-effort per-ticket touch — a single failure does not block the rest of the
        // batch or stop watermark advancement. The poll loop will see the same ticket on a
        // subsequent cycle if its updatedAt > the new watermark.
        log.warn(
            "linear_real polling_touch_failed ticketRef={} cause={}",
            ticket.ticketRef(),
            error.getClass().getSimpleName());
        skipped++;
        touchFailures++;
      }
    }
    log.info(
        "linear_real polling_batch since={} count={} oldest={} newest={} touched={} skipped={} touchFailures={}",
        since,
        tickets.size(),
        tickets.get(0).updatedAt(),
        newest.updatedAt(),
        touched,
        skipped,
        touchFailures);
    if (touchFailures > 0) {
      log.warn(
          "linear_real polling_cursor_preserved since={} newest={} touchFailures={}",
          since,
          newest.updatedAt(),
          touchFailures);
      return;
    }
    lastPollAt.set(newest.updatedAt());
  }

  /** Test-only accessor — last successfully polled high-water-mark. */
  public Instant lastPollAt() {
    return lastPollAt.get();
  }
}
