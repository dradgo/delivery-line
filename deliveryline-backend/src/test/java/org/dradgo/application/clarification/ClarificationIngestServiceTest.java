package org.dradgo.application.clarification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.clarification.ClarificationIngestService.RaisedQuestion;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.clarification.spi.ClarificationWritePort.NewClarification;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * Story 3e-1 (AC4/AC6/AC8) — unit test of {@link ClarificationIngestService#createOpenFromSpec}.
 * Mocks all SPIs; pins:
 *
 * <ul>
 *   <li>N questions → N {@code insertOpen} calls (each with the deterministic idempotency key) + N
 *       {@code CLARIFICATION_RAISED} events carrying clarificationId/artifactId/questionId;
 *   <li>a pre-flight idempotency-key hit (review D1) skips the insert entirely so no conflicting
 *       {@code saveAndFlush} is issued — the remaining questions still proceed;
 *   <li>an {@code IDEMPOTENCY_KEY_CONFLICT} on one question is swallowed as a benign duplicate and
 *       the remaining questions still proceed;
 *   <li>an empty/null question list is a no-op (no SPI calls);
 *   <li>logging discipline (trap T12): the summary line carries counts only — NEVER questionText.
 * </ul>
 */
class ClarificationIngestServiceTest {

  private static final String RUN = "run_abc12345";
  private static final String ART = "art_spec12345";
  private static final String REX = "rex_abc12345";
  private static final int VERSION = 2;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-06-24T10:00:00Z"), ZoneOffset.UTC);

  private final ClarificationWritePort writePort = mock(ClarificationWritePort.class);
  private final ClarificationReadPort readPort = mock(ClarificationReadPort.class);
  private final WorkflowEventWritePort eventWritePort = mock(WorkflowEventWritePort.class);
  private final ClarificationIngestService service =
      new ClarificationIngestService(writePort, readPort, eventWritePort, CLOCK);

  @Test
  void createsOneOpenClarificationAndEventPerQuestionWithDeterministicIdempotencyKey() {
    when(writePort.insertOpen(any())).thenAnswer(inv -> openFrom(inv.getArgument(0)));

    int created =
        service.createOpenFromSpec(
            RUN,
            ART,
            VERSION,
            List.of(
                new RaisedQuestion("Q-001", "Confirm export scope?"),
                new RaisedQuestion("Q-002", "Decide retry policy?")),
            REX,
            "corr-1");

    assertEquals(2, created);

    ArgumentCaptor<NewClarification> insertCaptor = ArgumentCaptor.forClass(NewClarification.class);
    verify(writePort, times(2)).insertOpen(insertCaptor.capture());
    List<NewClarification> inserts = insertCaptor.getAllValues();
    assertEquals(RUN, inserts.get(0).workflowRunPublicId());
    assertEquals(ART, inserts.get(0).artifactPublicId());
    assertEquals(VERSION, inserts.get(0).artifactVersion());
    assertEquals("Q-001", inserts.get(0).questionId());
    assertTrue(inserts.get(0).publicId().startsWith("clr_"));
    // Deterministic idempotency key: runner-result-clarification:<rex>:<questionId>
    assertEquals("runner-result-clarification:" + REX + ":Q-001", inserts.get(0).idempotencyKey());
    assertEquals("runner-result-clarification:" + REX + ":Q-002", inserts.get(1).idempotencyKey());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(2)).append(eventCaptor.capture());
    WorkflowEventRecord first = eventCaptor.getAllValues().get(0);
    assertEquals(WorkflowEventType.CLARIFICATION_RAISED, first.eventType());
    assertEquals(RUN, first.workflowRunPublicId());
    assertEquals(ActorType.SYSTEM, first.actorType());
    assertEquals("Q-001", first.details().get("questionId"));
    assertEquals(ART, first.details().get("artifactId"));
    assertTrue(((String) first.details().get("clarificationId")).startsWith("clr_"));
    assertEquals("corr-1", first.details().get("correlationId"));
  }

  @Test
  void idempotencyKeyConflictOnOneQuestionIsSwallowedAndTheRestProceed() {
    when(writePort.insertOpen(any()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "duplicate clarification"))
        .thenAnswer(inv -> openFrom(inv.getArgument(0)));

    int created =
        service.createOpenFromSpec(
            RUN,
            ART,
            VERSION,
            List.of(
                new RaisedQuestion("Q-001", "Already raised?"),
                new RaisedQuestion("Q-002", "Brand new?")),
            REX,
            null);

    // One duplicate skipped, one created; only the created one appends an event.
    assertEquals(1, created);
    verify(writePort, times(2)).insertOpen(any());
    verify(eventWritePort, times(1)).append(any());
  }

  @Test
  void preflightIdempotencyKeyHitSkipsInsertWithoutFlushAndTheRestProceed() {
    // review D1: the FIRST question's key already exists (cross-call replay/re-harvest) — it must
    // be
    // skipped BEFORE insertOpen so no conflicting saveAndFlush is ever issued; the second proceeds.
    when(readPort.existsByIdempotencyKey("runner-result-clarification:" + REX + ":Q-001"))
        .thenReturn(true);
    when(readPort.existsByIdempotencyKey("runner-result-clarification:" + REX + ":Q-002"))
        .thenReturn(false);
    when(writePort.insertOpen(any())).thenAnswer(inv -> openFrom(inv.getArgument(0)));

    int created =
        service.createOpenFromSpec(
            RUN,
            ART,
            VERSION,
            List.of(
                new RaisedQuestion("Q-001", "Already raised on a prior harvest?"),
                new RaisedQuestion("Q-002", "Brand new?")),
            REX,
            null);

    assertEquals(1, created);
    // Only the non-duplicate question reaches insertOpen — the pre-flight prevented the flush.
    ArgumentCaptor<NewClarification> insertCaptor = ArgumentCaptor.forClass(NewClarification.class);
    verify(writePort, times(1)).insertOpen(insertCaptor.capture());
    assertEquals("Q-002", insertCaptor.getValue().questionId());
    verify(eventWritePort, times(1)).append(any());
  }

  @Test
  void emptyOrNullQuestionListIsANoOp() {
    assertEquals(0, service.createOpenFromSpec(RUN, ART, VERSION, List.of(), REX, null));
    assertEquals(0, service.createOpenFromSpec(RUN, ART, VERSION, null, REX, null));
    verify(writePort, never()).insertOpen(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void summaryLogCarriesCountsButNeverQuestionText() {
    when(writePort.insertOpen(any())).thenAnswer(inv -> openFrom(inv.getArgument(0)));
    Logger logger = (Logger) LoggerFactory.getLogger(ClarificationIngestService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.createOpenFromSpec(
          RUN,
          ART,
          VERSION,
          List.of(new RaisedQuestion("Q-001", "Top secret question text")),
          REX,
          null);
    } finally {
      logger.detachAppender(appender);
    }

    boolean summaryLogged =
        appender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("clarification ingest")
                        && e.getFormattedMessage().contains("createdCount=1"));
    assertTrue(summaryLogged, "expected an INFO ingest summary line carrying createdCount");
    boolean leaked =
        appender.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("Top secret question text"));
    assertTrue(!leaked, "questionText must never appear in any log line (trap T12)");
  }

  private static Clarification openFrom(NewClarification newClarification) {
    return new Clarification(
        newClarification.publicId(),
        newClarification.workflowRunPublicId(),
        newClarification.artifactPublicId(),
        newClarification.artifactVersion(),
        newClarification.questionId(),
        newClarification.questionText(),
        Clarification.STATUS_OPEN,
        null,
        null,
        null,
        null,
        OffsetDateTime.now(CLOCK));
  }
}
