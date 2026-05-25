package org.dradgo.application.clarification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.clarification.spi.ClarificationWritePort.RecordAnswer;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Story 2.11 AC11 (a)–(g): unit test of {@link ClarificationService#submitAnswer}. Mocks all SPIs;
 * pins:
 *
 * <ul>
 *   <li>happy-path {@code open → answered}: row mutated, {@code CLARIFICATION_ANSWERED} event
 *       appended with the canonical details map, no state transition;
 *   <li>version-mismatch: {@code CLARIFICATION_ARTIFACT_VERSION_MISMATCH}, no row mutation, no
 *       event;
 *   <li>cross-run leak guard: {@code CLARIFICATION_NOT_FOUND} (same shape as the missing-row case);
 *   <li>terminal-state rejection: {@code CLARIFICATION_TERMINAL_STATE};
 *   <li>re-answer in {@code answered} state: prior {@code answerText} captured in event details;
 *   <li>re-answer in {@code accepted} state: status preserved (AC8).
 * </ul>
 */
class ClarificationServiceSubmitAnswerTest {

  private static final String RUN = "run_abc12345";
  private static final String CLR = "clr_abc12345";
  private static final String ART = "art_abc12345";
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 5, 25, 10, 0, 0, 0, ZoneOffset.UTC);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-05-25T10:00:00Z"), ZoneOffset.UTC);

  private final ClarificationReadPort readPort = mock(ClarificationReadPort.class);
  private final ClarificationWritePort writePort = mock(ClarificationWritePort.class);
  private final ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
  private final WorkflowEventWritePort eventWritePort = mock(WorkflowEventWritePort.class);
  private final ClarificationService service =
      new ClarificationService(readPort, writePort, artifactRecordPort, eventWritePort, CLOCK);

  @Test
  void happyPathOpenToAnsweredMutatesRowAndAppendsEvent() {
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(openClarification(1)));
    when(artifactRecordPort.findByPublicId(ART)).thenReturn(Optional.of(specArtifact(1)));
    when(writePort.recordAnswer(any()))
        .thenAnswer(invocation -> answeredClarification(invocation.getArgument(0), 1, "answer-1"));

    ClarificationResult result = service.submitAnswer(command(1, "answer-1"));

    assertEquals(CLR, result.clarificationId());
    assertEquals(Clarification.STATUS_ANSWERED, result.status());
    assertEquals(NOW, result.answeredAt());

    ArgumentCaptor<RecordAnswer> answerCaptor = ArgumentCaptor.forClass(RecordAnswer.class);
    verify(writePort).recordAnswer(answerCaptor.capture());
    assertEquals("answer-1", answerCaptor.getValue().answerText());
    assertEquals("alex", answerCaptor.getValue().answeredByActor());
    assertEquals(ActorType.HUMAN, answerCaptor.getValue().answeredByActorType());
    assertEquals(NOW, answerCaptor.getValue().answeredAt());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    WorkflowEventRecord event = eventCaptor.getValue();
    assertEquals(WorkflowEventType.CLARIFICATION_ANSWERED, event.eventType());
    assertEquals(RUN, event.workflowRunPublicId());
    assertNull(event.priorState());
    assertNull(event.resultingState());
    assertEquals(CLR, event.details().get("clarificationId"));
    assertEquals(ART, event.details().get("artifactId"));
    assertEquals(1, event.details().get("artifactVersion"));
    assertEquals("Q1", event.details().get("questionId"));
    assertEquals("idem-clr-1", event.details().get("idempotencyKey"));
    assertEquals(false, event.details().containsKey("priorAnswerText"));
  }

  @Test
  void versionMismatchRaisesAndDoesNotMutateRowOrAppendEvent() {
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(openClarification(1)));
    when(artifactRecordPort.findByPublicId(ART)).thenReturn(Optional.of(specArtifact(2)));

    DomainException error =
        assertThrows(DomainException.class, () -> service.submitAnswer(command(1, "answer-1")));
    assertEquals(DomainErrorCode.CLARIFICATION_ARTIFACT_VERSION_MISMATCH, error.errorCode());
    assertEquals(1, error.details().get("expectedArtifactVersion"));
    assertEquals(2, error.details().get("currentArtifactVersion"));

    verify(writePort, never()).recordAnswer(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void missingClarificationRaisesNotFound() {
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.empty());

    DomainException error =
        assertThrows(DomainException.class, () -> service.submitAnswer(command(1, "answer-1")));
    assertEquals(DomainErrorCode.CLARIFICATION_NOT_FOUND, error.errorCode());
    verify(writePort, never()).recordAnswer(any());
  }

  @Test
  void clarificationInSiblingRunRaisesNotFoundWithoutLeakingExistence() {
    // Trap T6: existence in a sibling run must NOT leak through a distinct error shape.
    Clarification siblingRunRow =
        new Clarification(
            CLR,
            "run_otherrun",
            ART,
            1,
            "Q1",
            "What?",
            Clarification.STATUS_OPEN,
            null,
            null,
            null,
            null,
            NOW.minusHours(1));
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(siblingRunRow));

    DomainException error =
        assertThrows(DomainException.class, () -> service.submitAnswer(command(1, "answer-1")));
    assertEquals(DomainErrorCode.CLARIFICATION_NOT_FOUND, error.errorCode());
    verify(writePort, never()).recordAnswer(any());
  }

  @Test
  void terminalStateRejectsAnswer() {
    Clarification terminal =
        new Clarification(
            CLR,
            RUN,
            ART,
            1,
            "Q1",
            "What?",
            Clarification.STATUS_INCORPORATED,
            "prior-answer",
            "kim",
            ActorType.HUMAN,
            NOW.minusHours(1),
            NOW.minusHours(2));
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(terminal));
    when(artifactRecordPort.findByPublicId(ART)).thenReturn(Optional.of(specArtifact(1)));

    DomainException error =
        assertThrows(DomainException.class, () -> service.submitAnswer(command(1, "answer-1")));
    assertEquals(DomainErrorCode.CLARIFICATION_TERMINAL_STATE, error.errorCode());
    assertEquals(Clarification.STATUS_INCORPORATED, error.details().get("currentStatus"));
    verify(writePort, never()).recordAnswer(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void reAnswerInAnsweredStatePreservesPriorAnswerTextInEventDetails() {
    Clarification answered =
        new Clarification(
            CLR,
            RUN,
            ART,
            1,
            "Q1",
            "What?",
            Clarification.STATUS_ANSWERED,
            "first-answer",
            "kim",
            ActorType.HUMAN,
            NOW.minusHours(1),
            NOW.minusHours(2));
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(answered));
    when(artifactRecordPort.findByPublicId(ART)).thenReturn(Optional.of(specArtifact(1)));
    when(writePort.recordAnswer(any()))
        .thenAnswer(
            invocation -> answeredClarification(invocation.getArgument(0), 1, "revised-answer"));

    service.submitAnswer(command(1, "revised-answer"));

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals("first-answer", eventCaptor.getValue().details().get("priorAnswerText"));
  }

  @Test
  void reAnswerInAcceptedStateIsAllowedAndPreservesStatus() {
    Clarification accepted =
        new Clarification(
            CLR,
            RUN,
            ART,
            1,
            "Q1",
            "What?",
            Clarification.STATUS_ACCEPTED,
            "approved-answer",
            "kim",
            ActorType.HUMAN,
            NOW.minusHours(1),
            NOW.minusHours(2));
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(accepted));
    when(artifactRecordPort.findByPublicId(ART)).thenReturn(Optional.of(specArtifact(1)));
    when(writePort.recordAnswer(any()))
        .thenAnswer(
            invocation ->
                new Clarification(
                    CLR,
                    RUN,
                    ART,
                    1,
                    "Q1",
                    "What?",
                    // Adapter preserves accepted when status was already past 'open'.
                    Clarification.STATUS_ACCEPTED,
                    "revised-answer",
                    "alex",
                    ActorType.HUMAN,
                    NOW,
                    NOW.minusHours(2)));

    ClarificationResult result = service.submitAnswer(command(1, "revised-answer"));

    assertEquals(Clarification.STATUS_ACCEPTED, result.status());
    verify(writePort).recordAnswer(any());
    verify(eventWritePort).append(any());
  }

  @Test
  void correlationIdEchoesIntoResultAndEventDetailsWhenPresent() {
    when(readPort.findByPublicId(CLR)).thenReturn(Optional.of(openClarification(1)));
    when(artifactRecordPort.findByPublicId(ART)).thenReturn(Optional.of(specArtifact(1)));
    when(writePort.recordAnswer(any()))
        .thenAnswer(invocation -> answeredClarification(invocation.getArgument(0), 1, "answer-1"));

    SubmitClarificationCommand cmd =
        new SubmitClarificationCommand(
            RUN, CLR, ART, 1, "answer-1", "alex", ActorType.HUMAN, "idem-clr-1", "corr-1");
    ClarificationResult result = service.submitAnswer(cmd);

    assertEquals("corr-1", result.correlationId());

    ArgumentCaptor<WorkflowEventRecord> eventCaptor =
        ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(eventCaptor.capture());
    assertEquals("corr-1", eventCaptor.getValue().details().get("correlationId"));
  }

  @Test
  void unknownPublicIdPrefixesAreRejectedFastWithInvalidIdPrefix() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                service.submitAnswer(
                    new SubmitClarificationCommand(
                        "bogus_run",
                        CLR,
                        ART,
                        1,
                        "answer-1",
                        "alex",
                        ActorType.HUMAN,
                        "idem-clr-1",
                        null)));
    assertEquals(DomainErrorCode.INVALID_ID_PREFIX, error.errorCode());
    assertTrue(error.details().containsKey("registry"));
  }

  // ============ helpers ============

  private SubmitClarificationCommand command(int artifactVersion, String answerText) {
    return new SubmitClarificationCommand(
        RUN, CLR, ART, artifactVersion, answerText, "alex", ActorType.HUMAN, "idem-clr-1", null);
  }

  private Clarification openClarification(int artifactVersion) {
    return new Clarification(
        CLR,
        RUN,
        ART,
        artifactVersion,
        "Q1",
        "What is the boundary?",
        Clarification.STATUS_OPEN,
        null,
        null,
        null,
        null,
        NOW.minusHours(1));
  }

  private Clarification answeredClarification(
      RecordAnswer recordAnswer, int artifactVersion, String answerText) {
    assertNotNull(recordAnswer);
    return new Clarification(
        CLR,
        RUN,
        ART,
        artifactVersion,
        "Q1",
        "What is the boundary?",
        Clarification.STATUS_ANSWERED,
        answerText,
        recordAnswer.answeredByActor(),
        recordAnswer.answeredByActorType(),
        recordAnswer.answeredAt(),
        NOW.minusHours(1));
  }

  private ArtifactRecordSnapshot specArtifact(int version) {
    return new ArtifactRecordSnapshot(
        ART,
        RUN,
        ArtifactType.SPEC,
        version,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "spec.md",
        "SHA-256",
        "deadbeef",
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        NOW.minusHours(2));
  }
}
