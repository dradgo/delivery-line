package org.dradgo.application.clarification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Story 2.12 — focused Mockito coverage for {@link ClarificationLifecycleService}. Pins:
 *
 * <ul>
 *   <li>Each happy path ({@code markAccepted}, {@code markIncorporated}, {@code markSuperseded},
 *       {@code markRejectedInvalid}) writes the matching status + appends the matching event with
 *       the required detail keys.
 *   <li>Illegal transitions raise {@link DomainErrorCode#ILLEGAL_CLARIFICATION_TRANSITION} with
 *       structured details and no row/event side effects.
 *   <li>Cross-run leak guard (Trap T11) raises {@link DomainErrorCode#CLARIFICATION_NOT_FOUND}.
 *   <li>Orchestrator-supplied missing artifact raises {@link DomainErrorCode#INTERNAL_ERROR} (Trap
 *       T4).
 *   <li>{@code noEffectReason} controlled-vocabulary enforcement (Trap T2).
 * </ul>
 */
class ClarificationLifecycleServiceTest {

  private static final String RUN_ID = "run_test_lifecycle_001";
  private static final String CLR_ID = "clr_test_lifecycle_001";
  private static final String ART_ID = "art_test_spec_v1";
  private static final String NEW_SPEC_ID = "art_test_spec_v2";

  private static final OffsetDateTime FIXED_NOW =
      OffsetDateTime.ofInstant(Instant.parse("2026-05-25T10:15:30Z"), ZoneOffset.UTC);

  private final ClarificationReadPort readPort = Mockito.mock(ClarificationReadPort.class);
  private final ClarificationWritePort writePort = Mockito.mock(ClarificationWritePort.class);
  private final ArtifactRecordPort artifactRecordPort = Mockito.mock(ArtifactRecordPort.class);
  private final WorkflowEventWritePort eventWritePort = Mockito.mock(WorkflowEventWritePort.class);
  private ClarificationLifecycleService service;

  @BeforeEach
  void setUp() {
    Clock fixed = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
    service =
        new ClarificationLifecycleService(
            readPort, writePort, artifactRecordPort, eventWritePort, fixed);
  }

  @Test
  void markAcceptedHappyPath() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRow(Clarification.STATUS_ANSWERED)));
    when(writePort.markAccepted(any()))
        .thenReturn(answeredRow(Clarification.STATUS_ACCEPTED));

    ClarificationLifecycleResult result =
        service.markAccepted(RUN_ID, CLR_ID, ActorContext.SYSTEM);

    assertThat(result.status()).isEqualTo(Clarification.STATUS_ACCEPTED);
    assertThat(result.transitionedAt()).isEqualTo(FIXED_NOW);

    ArgumentCaptor<WorkflowEventRecord> events = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(events.capture());
    WorkflowEventRecord event = events.getValue();
    assertThat(event.eventType()).isEqualTo(WorkflowEventType.CLARIFICATION_ACCEPTED);
    assertThat(event.workflowRunPublicId()).isEqualTo(RUN_ID);
    assertThat(event.details())
        .containsEntry("clarificationId", CLR_ID)
        .containsEntry("artifactId", ART_ID);
  }

  @Test
  void markAcceptedFromOpenIsIllegalTransition() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(openRow()));

    assertThatThrownBy(() -> service.markAccepted(RUN_ID, CLR_ID, ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION);
    verify(writePort, never()).markAccepted(any());
    verify(eventWritePort, never()).append(any());
  }

  @Test
  void markAcceptedFromSiblingRunRaisesClarificationNotFound() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRowWithRun("run_test_other_999")));

    assertThatThrownBy(() -> service.markAccepted(RUN_ID, CLR_ID, ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.CLARIFICATION_NOT_FOUND);
  }

  @Test
  void markIncorporatedHappyPath() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRow(Clarification.STATUS_ACCEPTED)));
    when(artifactRecordPort.findByPublicId(NEW_SPEC_ID))
        .thenReturn(Optional.of(specV2()));
    when(writePort.markIncorporated(any()))
        .thenReturn(answeredRow(Clarification.STATUS_INCORPORATED));

    ClarificationLifecycleResult result =
        service.markIncorporated(RUN_ID, CLR_ID, NEW_SPEC_ID, ActorContext.SYSTEM);

    assertThat(result.status()).isEqualTo(Clarification.STATUS_INCORPORATED);
    ArgumentCaptor<WorkflowEventRecord> events = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort, times(1)).append(events.capture());
    WorkflowEventRecord event = events.getValue();
    assertThat(event.eventType()).isEqualTo(WorkflowEventType.CLARIFICATION_INCORPORATED);
    assertThat(event.details())
        .containsKeys("clarificationId", "questionId", "incorporatedIntoArtifactId", "incorporationEventId")
        .containsEntry("incorporatedIntoArtifactId", NEW_SPEC_ID);
  }

  @Test
  void markIncorporatedRaisesInternalErrorWhenArtifactMissing() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRow(Clarification.STATUS_ACCEPTED)));
    when(artifactRecordPort.findByPublicId(NEW_SPEC_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.markIncorporated(RUN_ID, CLR_ID, NEW_SPEC_ID, ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.INTERNAL_ERROR);
    verify(eventWritePort, never()).append(any());
    verify(writePort, never()).markIncorporated(any());
  }

  @Test
  void markSupersededHappyPath() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRow(Clarification.STATUS_ACCEPTED)));
    when(artifactRecordPort.findByPublicId(NEW_SPEC_ID)).thenReturn(Optional.of(specV2()));
    when(writePort.markSuperseded(any()))
        .thenReturn(answeredRow(Clarification.STATUS_SUPERSEDED));

    service.markSuperseded(
        RUN_ID, CLR_ID, NEW_SPEC_ID, "clarification_not_addressed", ActorContext.SYSTEM);
    ArgumentCaptor<WorkflowEventRecord> events = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(events.capture());
    assertThat(events.getValue().eventType()).isEqualTo(WorkflowEventType.CLARIFICATION_SUPERSEDED);
    assertThat(events.getValue().details())
        .containsEntry("supersededByArtifactId", NEW_SPEC_ID)
        .containsEntry("noEffectReason", "clarification_not_addressed");
  }

  @Test
  void markSupersededRejectsBlankNoEffectReason() {
    assertThatThrownBy(
            () -> service.markSuperseded(RUN_ID, CLR_ID, NEW_SPEC_ID, "", ActorContext.SYSTEM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void markSupersededRejectsUnknownNoEffectReason() {
    assertThatThrownBy(
            () ->
                service.markSuperseded(
                    RUN_ID, CLR_ID, NEW_SPEC_ID, "totally_made_up_token", ActorContext.SYSTEM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void markRejectedInvalidHappyPath() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRow(Clarification.STATUS_ANSWERED)));
    when(writePort.markRejectedInvalid(any()))
        .thenReturn(answeredRow(Clarification.STATUS_REJECTED_INVALID));

    service.markRejectedInvalid(RUN_ID, CLR_ID, "pm_marked_invalid", ActorContext.SYSTEM);
    ArgumentCaptor<WorkflowEventRecord> events = ArgumentCaptor.forClass(WorkflowEventRecord.class);
    verify(eventWritePort).append(events.capture());
    assertThat(events.getValue().eventType())
        .isEqualTo(WorkflowEventType.CLARIFICATION_REJECTED_INVALID);
    assertThat(events.getValue().details()).containsEntry("noEffectReason", "pm_marked_invalid");
  }

  @Test
  void markRejectedInvalidFromAcceptedIsIllegal() {
    when(readPort.findByPublicId(CLR_ID))
        .thenReturn(Optional.of(answeredRow(Clarification.STATUS_ACCEPTED)));

    assertThatThrownBy(
            () ->
                service.markRejectedInvalid(
                    RUN_ID, CLR_ID, "pm_marked_invalid", ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.ILLEGAL_CLARIFICATION_TRANSITION);
  }

  private static Clarification openRow() {
    return new Clarification(
        CLR_ID, RUN_ID, ART_ID, 1, "Q-AUTH-001", "Question text", Clarification.STATUS_OPEN, null,
        null, null, null, FIXED_NOW);
  }

  private static Clarification answeredRow(String status) {
    return answeredRowWithRun(status, RUN_ID);
  }

  private static Clarification answeredRowWithRun(String runId) {
    return answeredRowWithRun(Clarification.STATUS_ANSWERED, runId);
  }

  private static Clarification answeredRowWithRun(String status, String runId) {
    return new Clarification(
        CLR_ID,
        runId,
        ART_ID,
        1,
        "Q-AUTH-001",
        "Question text",
        status,
        "Answer text",
        "alex@example.com",
        ActorType.HUMAN,
        FIXED_NOW.minusMinutes(5),
        FIXED_NOW.minusHours(1));
  }

  private static ArtifactRecordSnapshot specV2() {
    return new ArtifactRecordSnapshot(
        NEW_SPEC_ID,
        RUN_ID,
        ArtifactType.SPEC,
        2,
        ART_ID,
        DataClassification.LOCAL_ONLY,
        "spec/v2/spec.md",
        "SHA-256",
        "abc123",
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        FIXED_NOW);
  }
}
