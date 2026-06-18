package org.dradgo.infrastructure.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.integration.linear.LinearAutoIngestProperties;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.registry.ActorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><strong>Story 3a.5 — opt-in auto-ingest.</strong> When {@code
 * deliveryline.linear.auto-ingest.enabled=true}, the watcher additionally auto-creates a governed
 * run (via the same {@link WorkflowCommandService#submit} the CLI/REST use) for each polled ticket
 * whose opaque source workflow-state id ({@link Ticket#sourceStatusId()}) is in the configured
 * allow-list <em>and</em> that has no existing active link (AR18). The created run lands in {@code
 * Inbox} and feeds story 3a-1's spec auto-dispatch. Default OFF ⇒ the poll behaves byte-identically
 * to the Epic-1 watcher (no {@code submit} call, no new log lines). Auto-ingest is layered on top
 * of the best-effort per-ticket touch loop with per-ticket failure isolation; the watermark
 * advances exactly as before, independent of ingest outcomes.
 *
 * <p>Story 3.32 — the host depends on the vendor-neutral {@link TicketSourceAdapter} port (the
 * Linear kind is the only implementation today). The opaque {@code sourceStatusId} is a Linear-
 * specific concern that only this Linear-specific host interprets (OQ-1).
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
 *       window via {@link TicketSourceAdapter#pollNewTickets(Instant)} (the real adapter walks
 *       GraphQL cursor pagination internally). The cursor advances to {@code max(updatedAt)} of the
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

  /** Stable system actor identity for auto-ingested runs (Story 3a.5). */
  private static final String AUTO_INGEST_ACTOR_IDENTITY = "linear-auto-ingest";

  private final TicketSourceAdapter linearAdapter;
  private final IntegrationLinkRecordPort integrationLinkRecordPort;
  private final UuidV7Generator uuidV7Generator;
  private final WorkflowCommandService workflowCommandService;
  private final LinearAutoIngestProperties autoIngestProperties;
  private final Set<String> statusIdAllowList;
  private final Clock clock;
  private final AtomicReference<Instant> lastPollAt;

  @Autowired
  public LinearPollingHost(
      TicketSourceAdapter linearAdapter,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      UuidV7Generator uuidV7Generator,
      WorkflowCommandService workflowCommandService,
      LinearAutoIngestProperties autoIngestProperties) {
    this(
        linearAdapter,
        integrationLinkRecordPort,
        uuidV7Generator,
        workflowCommandService,
        autoIngestProperties,
        Clock.systemUTC());
  }

  LinearPollingHost(
      TicketSourceAdapter linearAdapter,
      IntegrationLinkRecordPort integrationLinkRecordPort,
      UuidV7Generator uuidV7Generator,
      WorkflowCommandService workflowCommandService,
      LinearAutoIngestProperties autoIngestProperties,
      Clock clock) {
    this.linearAdapter = Objects.requireNonNull(linearAdapter, "linearAdapter");
    this.integrationLinkRecordPort =
        Objects.requireNonNull(integrationLinkRecordPort, "integrationLinkRecordPort");
    this.uuidV7Generator = Objects.requireNonNull(uuidV7Generator, "uuidV7Generator");
    this.workflowCommandService =
        Objects.requireNonNull(workflowCommandService, "workflowCommandService");
    this.autoIngestProperties =
        Objects.requireNonNull(autoIngestProperties, "autoIngestProperties");
    // Precompute the allow-list once for O(1) membership; the properties record already stripped
    // blanks/normalized the entries.
    this.statusIdAllowList = Set.copyOf(autoIngestProperties.statusIds());
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
    List<Ticket> tickets;
    try {
      tickets = linearAdapter.pollNewTickets(since);
    } catch (TicketSourceAdapterException error) {
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
    Ticket newest = tickets.get(tickets.size() - 1);
    boolean autoIngestEnabled = autoIngestProperties.enabled();
    if (autoIngestEnabled && statusIdAllowList.isEmpty()) {
      // AC2 fail-safe — enabled but no eligibility ids configured ⇒ nothing is ingested (never the
      // whole workspace). Emit exactly one WARN per poll batch so the misconfiguration is visible.
      log.warn(
          "linear_real auto_ingest_no_status_ids since={} count={} hint=configure_deliveryline.linear.auto-ingest.status-ids",
          since,
          tickets.size());
    }
    int touched = 0;
    int skipped = 0;
    int touchFailures = 0;
    // Story 3a.5 auto-ingest counters (distinct from the touch counters above): `ingested` =
    // submitted; `autoIngestSkipped` = eligible but already linked (AR18, logged as skipped per
    // AC6); `ineligible` = state id absent/not in the allow-list.
    int ingested = 0;
    int autoIngestSkipped = 0;
    int ineligible = 0;
    for (Ticket ticket : tickets) {
      String ticketRef = ticket.ticketRef().value();
      try {
        boolean updated =
            integrationLinkRecordPort.touchLastSyncAtByTypeAndExternalRef(
                INTEGRATION_TYPE_LINEAR, ticketRef, ticket.updatedAt());
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
            ticketRef,
            error.getClass().getSimpleName());
        skipped++;
        touchFailures++;
      }

      if (autoIngestEnabled) {
        // AC4 — auto-ingest is layered on top of the touch attempt (above) so a touch failure still
        // preserves the cursor; per-ticket failures are isolated and never abort the batch.
        if (!isEligible(ticket)) {
          ineligible++;
          log.debug(
              "linear_real auto_ingest_ineligible ticketRef={} statusName={} statusId={}",
              ticketRef,
              ticket.sourceStatus(),
              ticket.sourceStatusId());
        } else {
          // The active-link pre-check and the submit are both DB-backed and BOTH must sit inside
          // the
          // per-ticket try (AC4): a transient failure of the pre-check read must isolate to this
          // ticket — never propagate out of the loop and abort the batch / skip watermark advance.
          try {
            if (integrationLinkRecordPort
                .findActiveByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, ticketRef)
                .isPresent()) {
              // AR18 — already linked (CLI/REST/prior auto-ingest); touch-only, do not re-submit.
              autoIngestSkipped++;
            } else {
              SubmitWorkflowResult result =
                  workflowCommandService.submit(buildAutoIngestCommand(ticket));
              ingested++;
              log.info(
                  "linear_real auto_ingest_created ticketRef={} statusName={} statusId={} runId={} state={} actorType={}",
                  ticketRef,
                  ticket.sourceStatus(),
                  ticket.sourceStatusId(),
                  result.workflowRunId(),
                  result.currentState().value(),
                  ActorType.SYSTEM.value());
            }
          } catch (DomainException de) {
            log.warn(
                "linear_real auto_ingest_failed ticketRef={} code={}",
                ticketRef,
                de.errorCode().value());
          } catch (RuntimeException re) {
            // Includes a failure of the active-link pre-check read above — per-ticket isolation
            // (AC4): the batch continues and watermark advancement is unaffected.
            log.warn(
                "linear_real auto_ingest_failed ticketRef={} cause={}",
                ticketRef,
                re.getClass().getSimpleName());
          }
        }
      }
    }
    if (autoIngestEnabled) {
      // AC6 — extend the batch summary with the auto-ingest counters (ingested / already-linked
      // skipped / ineligible) only when the flag is on.
      log.info(
          "linear_real polling_batch since={} count={} oldest={} newest={} touched={} skipped={} touchFailures={} ingested={} alreadyLinkedSkipped={} ineligible={}",
          since,
          tickets.size(),
          tickets.get(0).updatedAt(),
          newest.updatedAt(),
          touched,
          skipped,
          touchFailures,
          ingested,
          autoIngestSkipped,
          ineligible);
    } else {
      // AC1 — flag off ⇒ the batch line is byte-identical to the Epic-1 watcher (no new fields).
      log.info(
          "linear_real polling_batch since={} count={} oldest={} newest={} touched={} skipped={} touchFailures={}",
          since,
          tickets.size(),
          tickets.get(0).updatedAt(),
          newest.updatedAt(),
          touched,
          skipped,
          touchFailures);
    }
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

  /**
   * AC2 — a ticket is eligible iff auto-ingest is enabled, the allow-list is non-empty, and the
   * ticket's opaque source workflow-state id is in the allow-list. A {@code null} state id (state
   * absent from the response) is ineligible. Gating is on the id only — the name is for logs.
   */
  private boolean isEligible(Ticket ticket) {
    return autoIngestProperties.enabled()
        && !statusIdAllowList.isEmpty()
        && ticket.sourceStatusId() != null
        && statusIdAllowList.contains(ticket.sourceStatusId());
  }

  /**
   * AC3 — build the submit command with a {@code SYSTEM} actor, the deterministic ticket-derived
   * idempotency key, and {@code correlationId=null} (T-FINGERPRINT-DRIFT: the per-poll correlation
   * must NOT enter the fingerprint, else a re-poll mints a fingerprint mismatch).
   */
  private SubmitWorkflowCommand buildAutoIngestCommand(Ticket ticket) {
    String ticketRef = ticket.ticketRef().value();
    return new SubmitWorkflowCommand(
        AUTO_INGEST_ACTOR_IDENTITY, ActorType.SYSTEM, autoIngestKey(ticketRef), null, ticketRef);
  }

  /**
   * AC3 — deterministic, ticket-identity-only idempotency key: {@code "ai-" + sha256Hex(...)} (3 +
   * 64 hex = 67 chars, satisfying the opaque {@code [A-Za-z0-9-]{16,128}} key rule). The same
   * {@code ticketRef} ⇒ the same key, so a re-poll/JVM-restart maps to the same idempotency record
   * (replay, not double-create). Status is deliberately NOT in the key — a status change keeps one
   * run per ticket.
   */
  private static String autoIngestKey(String ticketRef) {
    return "ai-" + sha256Hex(AUTO_INGEST_ACTOR_IDENTITY + "|" + ticketRef);
  }

  private static String sha256Hex(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      // SHA-256 is mandatory in every Java distribution; defensive only.
      throw new IllegalStateException("SHA-256 not available", error);
    }
  }

  /** Test-only accessor — last successfully polled high-water-mark. */
  public Instant lastPollAt() {
    return lastPollAt.get();
  }
}
