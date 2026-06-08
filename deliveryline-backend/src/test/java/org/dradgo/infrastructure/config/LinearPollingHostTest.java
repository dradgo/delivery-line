package org.dradgo.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAutoIngestProperties;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Story 3a.5 — unit coverage for the opt-in auto-ingest branch on {@link LinearPollingHost}. Builds
 * the host via the package-private {@code Clock} constructor with mocked collaborators and a fixed
 * {@code Clock}; asserts the AC8 (a)–(h) matrix and pins the new log surfaces with a {@link
 * ListAppender}.
 */
class LinearPollingHostTest {

  private static final String INTEGRATION_TYPE_LINEAR = "linear";
  private static final String ELIGIBLE_STATUS_ID = "state-ready-uuid";
  private static final String INELIGIBLE_STATUS_ID = "state-backlog-uuid";
  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final LinearAdapter linearAdapter = mock(LinearAdapter.class);
  private final IntegrationLinkRecordPort recordPort = mock(IntegrationLinkRecordPort.class);
  private final WorkflowCommandService commandService = mock(WorkflowCommandService.class);
  private final ListAppender<ILoggingEvent> appender = attachAppender();

  @AfterEach
  void detachAppender() {
    ((Logger) LoggerFactory.getLogger(LinearPollingHost.class)).detachAppender(appender);
  }

  // ---- (a) eligible + no active link → submits once -------------------------------------------

  @Test
  void eligibleTicketWithNoActiveLinkSubmitsOnce() {
    LinearTicket ticket = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(ticket));
    stubNoActiveLink("LIN-1");
    stubSubmitReturns("run_1");

    host.pollLinear();

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(commandService, times(1)).submit(captor.capture());
    SubmitWorkflowCommand command = captor.getValue();
    assertEquals(ActorType.SYSTEM, command.actorType());
    assertEquals("linear-auto-ingest", command.actorIdentity());
    assertNull(command.correlationId(), "correlationId MUST be null (T-FINGERPRINT-DRIFT)");
    assertEquals("LIN-1", command.linearTicketReference());
    assertEquals(expectedKey("LIN-1"), command.idempotencyKey());
    assertContains(Level.INFO, "auto_ingest_created ticketRef=LIN-1");
    assertContains(Level.INFO, "actorType=system");
  }

  // ---- (b) re-poll with active link → no second submit; key deterministic ---------------------

  @Test
  void rePollWithActiveLinkDoesNotResubmit() {
    LinearTicket ticket = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(any())).thenReturn(List.of(ticket));
    stubSubmitReturns("run_1");

    // Cycle 1 — no active link yet ⇒ submit.
    when(recordPort.findActiveByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, "LIN-1"))
        .thenReturn(Optional.empty());
    host.pollLinear();
    // Cycle 2 — link now present ⇒ touch-only, no resubmit.
    when(recordPort.findActiveByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, "LIN-1"))
        .thenReturn(Optional.of(mock(IntegrationLink.class)));
    host.pollLinear();

    verify(commandService, times(1)).submit(any());
  }

  @Test
  void deterministicKeyIsStableAcrossPollCycles() {
    LinearTicket ticket = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(any())).thenReturn(List.of(ticket));
    stubNoActiveLink("LIN-1");
    stubSubmitReturns("run_1");

    host.pollLinear();
    host.pollLinear();

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(commandService, times(2)).submit(captor.capture());
    List<SubmitWorkflowCommand> commands = captor.getAllValues();
    assertEquals(
        commands.get(0).idempotencyKey(),
        commands.get(1).idempotencyKey(),
        "deterministic key must be stable across poll cycles");
  }

  // ---- (c) ineligible state id → touch-only, no submit ----------------------------------------

  @Test
  void ineligibleStatusIdIsTouchOnly() {
    LinearTicket ticket = ticket("LIN-9", NOW.plusSeconds(60), "Backlog", INELIGIBLE_STATUS_ID);
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(ticket));

    host.pollLinear();

    verify(commandService, never()).submit(any());
    verify(recordPort)
        .touchLastSyncAtByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, "LIN-9", ticket.updatedAt());
    assertContains(Level.DEBUG, "auto_ingest_ineligible ticketRef=LIN-9");
  }

  // ---- (d) flag disabled → byte-identical legacy path -----------------------------------------

  @Test
  void disabledFlagKeepsLegacyWatcherPath() {
    LinearTicket ticket = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearPollingHost host = host(LinearAutoIngestProperties.defaults());
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(ticket));

    host.pollLinear();

    verifyNoInteractions(commandService);
    // No auto-ingest log lines, and the batch line stays the legacy shape (no ingested= fields).
    assertNoneContains("auto_ingest");
    assertNoneContains("ingested=");
    verify(recordPort)
        .touchLastSyncAtByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, "LIN-1", ticket.updatedAt());
  }

  // ---- (e) one submit failure does not abort the batch ----------------------------------------

  @Test
  void oneSubmitFailureDoesNotAbortTheBatch() {
    LinearTicket t1 = eligibleTicket("LIN-1", NOW.plusSeconds(10));
    LinearTicket t2 = eligibleTicket("LIN-2", NOW.plusSeconds(20));
    LinearTicket t3 = eligibleTicket("LIN-3", NOW.plusSeconds(30));
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(t1, t2, t3));
    stubNoActiveLink("LIN-1");
    stubNoActiveLink("LIN-2");
    stubNoActiveLink("LIN-3");
    when(commandService.submit(any()))
        .thenReturn(new SubmitWorkflowResult("run_ok", WorkflowState.INBOX, null));
    when(commandService.submit(
            argThat(c -> c != null && "LIN-2".equals(c.linearTicketReference()))))
        .thenThrow(new DomainException(DomainErrorCode.INTEGRATION_LINK_CONFLICT, "conflict"));

    host.pollLinear();

    // All three were attempted (failure isolated, batch not aborted).
    verify(commandService, times(3)).submit(any());
    assertContains(Level.INFO, "auto_ingest_created ticketRef=LIN-1");
    assertContains(Level.INFO, "auto_ingest_created ticketRef=LIN-3");
    assertContains(Level.WARN, "auto_ingest_failed ticketRef=LIN-2 code=INTEGRATION_LINK_CONFLICT");
  }

  // ---- (f) watermark preserved/advanced exactly as today --------------------------------------

  @Test
  void watermarkAdvancesToNewestRegardlessOfIngestOutcome() {
    Instant newest = NOW.plusSeconds(120);
    LinearTicket t1 = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearTicket t2 = eligibleTicket("LIN-2", newest);
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(t1, t2));
    stubNoActiveLink("LIN-1");
    stubNoActiveLink("LIN-2");
    stubSubmitReturns("run_x");

    host.pollLinear();

    assertEquals(newest, host.lastPollAt(), "watermark advances to max(updatedAt) as today");
  }

  @Test
  void watermarkPreservedOnTouchFailureEvenWhenIngestSucceeds() {
    LinearTicket ticket = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(ticket));
    when(recordPort.touchLastSyncAtByTypeAndExternalRef(
            INTEGRATION_TYPE_LINEAR, "LIN-1", ticket.updatedAt()))
        .thenThrow(new IllegalStateException("db down"));
    stubNoActiveLink("LIN-1");
    stubSubmitReturns("run_x");

    host.pollLinear();

    // Touch failure preserves the cursor today; auto-ingest outcome does not influence it.
    assertEquals(NOW, host.lastPollAt(), "touch failure must preserve the existing cursor");
    assertContains(Level.WARN, "polling_cursor_preserved");
  }

  // ---- (g) enabled + empty status-ids → ingests nothing + single WARN -------------------------

  @Test
  void enabledWithEmptyStatusIdsIngestsNothingAndWarnsOnce() {
    LinearTicket ticket = eligibleTicket("LIN-1", NOW.plusSeconds(60));
    LinearPollingHost host = host(new LinearAutoIngestProperties(true, List.of()));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(ticket));

    host.pollLinear();

    verify(commandService, never()).submit(any());
    long warns =
        appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("auto_ingest_no_status_ids"))
            .count();
    assertEquals(1L, warns, "exactly one WARN per batch for the empty allow-list");
  }

  // ---- (h) null/unknown state id → ineligible -------------------------------------------------

  @Test
  void nullStatusIdIsIneligible() {
    LinearTicket ticket = ticket("LIN-0", NOW.plusSeconds(60), null, null);
    LinearPollingHost host = host(enabled(ELIGIBLE_STATUS_ID));
    when(linearAdapter.pollNewTickets(NOW)).thenReturn(List.of(ticket));

    host.pollLinear();

    verify(commandService, never()).submit(any());
    assertContains(Level.DEBUG, "auto_ingest_ineligible ticketRef=LIN-0");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private LinearPollingHost host(LinearAutoIngestProperties props) {
    return new LinearPollingHost(
        linearAdapter, recordPort, new UuidV7Generator(), commandService, props, CLOCK);
  }

  private static LinearAutoIngestProperties enabled(String... statusIds) {
    return new LinearAutoIngestProperties(true, List.of(statusIds));
  }

  private void stubNoActiveLink(String ticketRef) {
    when(recordPort.findActiveByTypeAndExternalRef(INTEGRATION_TYPE_LINEAR, ticketRef))
        .thenReturn(Optional.empty());
  }

  private void stubSubmitReturns(String runId) {
    when(commandService.submit(any()))
        .thenReturn(new SubmitWorkflowResult(runId, WorkflowState.INBOX, null));
  }

  private static LinearTicket eligibleTicket(String ref, Instant updatedAt) {
    return ticket(ref, updatedAt, "Ready for Planning", ELIGIBLE_STATUS_ID);
  }

  private static LinearTicket ticket(
      String ref, Instant updatedAt, String statusName, String statusId) {
    return new LinearTicket(
        ref,
        "title-" + ref,
        "summary",
        "dev@example.com",
        NOW,
        updatedAt,
        Map.of(),
        statusName,
        statusId);
  }

  /** Recompute the production idempotency-key formula independently. */
  private static String expectedKey(String ticketRef) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(("linear-auto-ingest|" + ticketRef).getBytes(StandardCharsets.UTF_8));
      return "ai-" + HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(LinearPollingHost.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    return appender;
  }

  private void assertContains(Level level, String fragment) {
    assertTrue(
        appender.list.stream()
            .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment)),
        () ->
            "expected "
                + level
                + " log containing '"
                + fragment
                + "'; saw: "
                + appender.list.stream()
                    .map(e -> e.getLevel() + " " + e.getFormattedMessage())
                    .toList());
  }

  private void assertNoneContains(String fragment) {
    assertTrue(
        appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(fragment)),
        () -> "expected NO log containing '" + fragment + "' but found one");
  }
}
