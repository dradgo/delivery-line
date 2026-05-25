package org.dradgo.application.clarification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ClarificationLifecycleServiceLoggingTest {

  private static final OffsetDateTime NOW =
      OffsetDateTime.ofInstant(Instant.parse("2026-05-25T13:00:00Z"), ZoneOffset.UTC);

  private final ClarificationReadPort readPort = mock(ClarificationReadPort.class);
  private final ClarificationWritePort writePort = mock(ClarificationWritePort.class);
  private final WorkflowEventWritePort eventWritePort = mock(WorkflowEventWritePort.class);

  private ClarificationLifecycleService service;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    service =
        new ClarificationLifecycleService(
            readPort, writePort, eventWritePort, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ClarificationLifecycleService.class)).addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(ClarificationLifecycleService.class))
        .detachAppender(appender);
  }

  @Test
  void markAcceptedEmitsEntryAndSuccessInfoLogs() {
    when(readPort.findByPublicId("clr_log_accept"))
        .thenReturn(Optional.of(answeredRow("clr_log_accept", "run_log_accept")));
    when(readPort.findByPublicIdForUpdate("run_log_accept", "clr_log_accept"))
        .thenReturn(Optional.of(answeredRow("clr_log_accept", "run_log_accept")));
    when(writePort.markAccepted(any()))
        .thenReturn(
            new Clarification(
                "clr_log_accept",
                "run_log_accept",
                "art_log_accept",
                1,
                "Q-LOG-1",
                "Question?",
                Clarification.STATUS_ACCEPTED,
                "answer",
                "alex",
                ActorType.HUMAN,
                NOW.minusMinutes(5),
                NOW.minusHours(1)));

    service.markAccepted("run_log_accept", "clr_log_accept", ActorContext.SYSTEM);

    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("markAccepted entry")
                    && event.getFormattedMessage().contains("clarificationId=clr_log_accept"))
        .anyMatch(
            event ->
                event.getLevel() == Level.INFO
                    && event.getFormattedMessage().contains("markAccepted success")
                    && event.getFormattedMessage().contains("status=accepted"));
  }

  @Test
  void crossRunGuardLogsClarificationNotFoundWarn() {
    when(readPort.findByPublicId("clr_log_cross"))
        .thenReturn(Optional.of(answeredRow("clr_log_cross", "run_other_123")));

    assertThatThrownBy(
            () -> service.markAccepted("run_log_cross", "clr_log_cross", ActorContext.SYSTEM))
        .hasMessageContaining("Clarification not found");

    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("CLARIFICATION_NOT_FOUND")
                    && event.getFormattedMessage().contains("reason=cross_run"));
  }

  @Test
  void terminalReentryLogsIllegalTransitionWarn() {
    when(readPort.findByPublicId("clr_log_terminal"))
        .thenReturn(
            Optional.of(
                new Clarification(
                    "clr_log_terminal",
                    "run_log_terminal",
                    "art_log_terminal",
                    1,
                    "Q-LOG-2",
                    "Question?",
                    Clarification.STATUS_INCORPORATED,
                    "answer",
                    "alex",
                    ActorType.HUMAN,
                    NOW.minusMinutes(5),
                    NOW.minusHours(1))));
    when(readPort.findByPublicIdForUpdate("run_log_terminal", "clr_log_terminal"))
        .thenReturn(
            Optional.of(
                new Clarification(
                    "clr_log_terminal",
                    "run_log_terminal",
                    "art_log_terminal",
                    1,
                    "Q-LOG-2",
                    "Question?",
                    Clarification.STATUS_INCORPORATED,
                    "answer",
                    "alex",
                    ActorType.HUMAN,
                    NOW.minusMinutes(5),
                    NOW.minusHours(1))));

    assertThatThrownBy(
            () ->
                service.markIncorporated(
                    "run_log_terminal", "clr_log_terminal", "art_log_new", 2, ActorContext.SYSTEM))
        .hasMessageContaining("Illegal clarification transition");

    assertThat(appender.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("ILLEGAL_CLARIFICATION_TRANSITION")
                    && event.getFormattedMessage().contains("currentStatus=incorporated"));
  }

  private static Clarification answeredRow(String clarificationId, String runId) {
    return new Clarification(
        clarificationId,
        runId,
        "art_log_accept",
        1,
        "Q-LOG-1",
        "Question?",
        Clarification.STATUS_ANSWERED,
        "answer",
        "alex",
        ActorType.HUMAN,
        NOW.minusMinutes(5),
        NOW.minusHours(1));
  }
}
