package org.dradgo.application.clarification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.SpecClarificationAcknowledgementReadPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Story 2.12 AC4 + story 3e-2 AC6 — focused Mockito coverage for {@link
 * ClarificationLifecycleOrchestrator} after the substring-scan stub was replaced by the structured
 * acknowledgements oracle ({@link SpecClarificationAcknowledgementReadPort}).
 *
 * <p>Pins:
 *
 * <ul>
 *   <li>Zero accepted clarifications → no mark* calls, no artifact/acknowledgement load.
 *   <li>One accepted + acknowledgement {@code addressed:true} → one {@code markIncorporated}.
 *   <li>One accepted + acknowledgement {@code addressed:false} → one {@code markSuperseded} with
 *       {@code noEffectReason = clarification_not_addressed}.
 *   <li>One accepted + NO acknowledgement (absent) → superseded (not-addressed).
 *   <li>Mixed sweep: incorporates the addressed, supersedes the unaddressed.
 *   <li>Lineage scope: an accepted clarification pinned outside the rebuilt spec lineage is
 *       skipped.
 *   <li>Fail-loud: a missing artifact record raises {@code
 *       DomainException(ARTIFACT_PAYLOAD_UNAVAILABLE)} and NO mark* calls fire.
 *   <li>P23 input validation: blank/whitespace ids raise {@code
 *       DomainException(INVALID_ID_PREFIX)}.
 * </ul>
 */
class ClarificationLifecycleOrchestratorTest {

  private static final String RUN_ID = "run_orch_test_001";
  private static final String SPEC_V1_ID = "art_orch_spec_v1";
  private static final String SPEC_V2_ID = "art_orch_spec_v2";
  private static final String OTHER_SPEC_V1_ID = "art_orch_other_v1";
  private static final int SPEC_V2_VERSION = 2;

  private final ClarificationReadPort readPort = Mockito.mock(ClarificationReadPort.class);
  private final ClarificationLifecycleService lifecycleService =
      Mockito.mock(ClarificationLifecycleService.class);
  private final ArtifactRecordPort artifactRecordPort = Mockito.mock(ArtifactRecordPort.class);
  private final SpecClarificationAcknowledgementReadPort acknowledgementReadPort =
      Mockito.mock(SpecClarificationAcknowledgementReadPort.class);

  private ClarificationLifecycleOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator =
        new ClarificationLifecycleOrchestrator(
            readPort, lifecycleService, artifactRecordPort, acknowledgementReadPort);
  }

  @Test
  void sweepWithZeroAcceptedClarificationsReturnsEmptyResultAndCallsNoMarkMethods() {
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of());

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.consideredCount()).isZero();
    assertThat(result.incorporatedCount()).isZero();
    assertThat(result.supersededCount()).isZero();
    assertThat(result.decisions()).isEmpty();
    verify(lifecycleService, never())
        .markIncorporated(anyString(), anyString(), anyString(), anyInt(), any());
    verify(lifecycleService, never())
        .markSuperseded(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
    // No artifact/acknowledgement load even attempted when zero accepted (short-circuit).
    verify(artifactRecordPort, never()).findByPublicId(anyString());
    verify(acknowledgementReadPort, never()).findBySpecArtifactPublicId(anyString());
  }

  @Test
  void sweepIncorporatesAcceptedClarificationsWhenAcknowledgementIsAddressedTrue() {
    Clarification accepted = accepted("clr_orch_001", "Q-AUTH-001");
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(accepted));
    when(artifactRecordPort.findByPublicId(SPEC_V2_ID)).thenReturn(Optional.of(specV2()));
    when(acknowledgementReadPort.findBySpecArtifactPublicId(SPEC_V2_ID))
        .thenReturn(List.of(new SpecClarificationAcknowledgement(SPEC_V2_ID, "Q-AUTH-001", true)));

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.consideredCount()).isEqualTo(1);
    assertThat(result.incorporatedCount()).isEqualTo(1);
    assertThat(result.supersededCount()).isZero();
    verify(lifecycleService, times(1))
        .markIncorporated(
            eq(RUN_ID),
            eq("clr_orch_001"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            eq(ActorContext.SYSTEM));
    verify(lifecycleService, never())
        .markSuperseded(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
  }

  @Test
  void sweepSupersedesAcceptedClarificationsWhenAcknowledgementIsAddressedFalse() {
    Clarification accepted = accepted("clr_orch_002", "Q-AUTH-001");
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(accepted));
    when(artifactRecordPort.findByPublicId(SPEC_V2_ID)).thenReturn(Optional.of(specV2()));
    when(acknowledgementReadPort.findBySpecArtifactPublicId(SPEC_V2_ID))
        .thenReturn(List.of(new SpecClarificationAcknowledgement(SPEC_V2_ID, "Q-AUTH-001", false)));

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.incorporatedCount()).isZero();
    assertThat(result.supersededCount()).isEqualTo(1);
    verify(lifecycleService, times(1))
        .markSuperseded(
            eq(RUN_ID),
            eq("clr_orch_002"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            eq("clarification_not_addressed"),
            eq(ActorContext.SYSTEM));
    verify(lifecycleService, never())
        .markIncorporated(anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void sweepSupersedesAcceptedClarificationsWhenAcknowledgementIsAbsent() {
    // Story 3e-2 — an ABSENT acknowledgement (the runner never mentioned the question) is
    // not-addressed => superseded (identical to addressed:false).
    Clarification accepted = accepted("clr_orch_absent", "Q-AUTH-001");
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(accepted));
    when(artifactRecordPort.findByPublicId(SPEC_V2_ID)).thenReturn(Optional.of(specV2()));
    when(acknowledgementReadPort.findBySpecArtifactPublicId(SPEC_V2_ID)).thenReturn(List.of());

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.incorporatedCount()).isZero();
    assertThat(result.supersededCount()).isEqualTo(1);
    verify(lifecycleService, times(1))
        .markSuperseded(
            eq(RUN_ID),
            eq("clr_orch_absent"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            eq("clarification_not_addressed"),
            eq(ActorContext.SYSTEM));
  }

  @Test
  void sweepMixedAcceptedClarificationsApplyPerQuestionDecision() {
    Clarification c1 = accepted("clr_orch_mixed_a", "Q-AUTH-001");
    Clarification c2 = accepted("clr_orch_mixed_b", "Q-AUTH-002");
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(c1, c2));
    when(artifactRecordPort.findByPublicId(SPEC_V2_ID)).thenReturn(Optional.of(specV2()));
    when(acknowledgementReadPort.findBySpecArtifactPublicId(SPEC_V2_ID))
        .thenReturn(
            List.of(
                new SpecClarificationAcknowledgement(SPEC_V2_ID, "Q-AUTH-001", true),
                new SpecClarificationAcknowledgement(SPEC_V2_ID, "Q-AUTH-002", false)));

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.consideredCount()).isEqualTo(2);
    assertThat(result.incorporatedCount()).isEqualTo(1);
    assertThat(result.supersededCount()).isEqualTo(1);
    verify(lifecycleService)
        .markIncorporated(
            eq(RUN_ID),
            eq("clr_orch_mixed_a"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            eq(ActorContext.SYSTEM));
    verify(lifecycleService)
        .markSuperseded(
            eq(RUN_ID),
            eq("clr_orch_mixed_b"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            eq("clarification_not_addressed"),
            eq(ActorContext.SYSTEM));
  }

  @Test
  void sweepIgnoresAcceptedClarificationsOutsideTheRebuiltSpecLineage() {
    Clarification sameLineage = accepted("clr_orch_lineage_ok", "Q-AUTH-001");
    Clarification unrelated =
        new Clarification(
            "clr_orch_lineage_skip",
            RUN_ID,
            OTHER_SPEC_V1_ID,
            1,
            "Q-OTHER-001",
            "Question text for Q-OTHER-001",
            Clarification.STATUS_ACCEPTED,
            "Answer text",
            "alex@example.com",
            ActorType.HUMAN,
            OffsetDateTime.parse("2026-05-25T09:00:00Z"),
            OffsetDateTime.parse("2026-05-25T08:30:00Z"));
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(sameLineage, unrelated));
    when(artifactRecordPort.findByPublicId(SPEC_V2_ID)).thenReturn(Optional.of(specV2()));
    when(artifactRecordPort.findByPublicId(SPEC_V1_ID)).thenReturn(Optional.of(specV1()));
    // Even though the runner addressed BOTH questions, the unrelated clarification is pinned to a
    // FOREIGN lineage (OTHER_SPEC_V1_ID not in lineageArtifactIds(v2)) so the sweep skips it.
    when(acknowledgementReadPort.findBySpecArtifactPublicId(SPEC_V2_ID))
        .thenReturn(
            List.of(
                new SpecClarificationAcknowledgement(SPEC_V2_ID, "Q-AUTH-001", true),
                new SpecClarificationAcknowledgement(SPEC_V2_ID, "Q-OTHER-001", true)));

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.consideredCount()).isEqualTo(2);
    assertThat(result.incorporatedCount()).isEqualTo(1);
    assertThat(result.supersededCount()).isZero();
    verify(lifecycleService)
        .markIncorporated(
            eq(RUN_ID),
            eq("clr_orch_lineage_ok"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            eq(ActorContext.SYSTEM));
    verify(lifecycleService, never())
        .markSuperseded(
            eq(RUN_ID),
            eq("clr_orch_lineage_skip"),
            eq(SPEC_V2_ID),
            eq(SPEC_V2_VERSION),
            anyString(),
            eq(ActorContext.SYSTEM));
  }

  @Test
  void sweepSkipsClarificationsThatAreNotInAcceptedStatus() {
    Clarification openRow = clarification("clr_orch_open", "Q-X", Clarification.STATUS_OPEN);
    Clarification answeredRow = clarification("clr_orch_ans", "Q-Y", Clarification.STATUS_ANSWERED);
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(openRow, answeredRow));

    ClarificationLifecycleOrchestrator.LifecycleSweepResult result =
        orchestrator.sweepAfterSpecRebuild(
            RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM);

    assertThat(result.consideredCount()).isZero();
    assertThat(result.decisions()).isEmpty();
    verify(lifecycleService, never())
        .markIncorporated(anyString(), anyString(), anyString(), anyInt(), any());
    verify(lifecycleService, never())
        .markSuperseded(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
  }

  @Test
  void sweepRaisesArtifactPayloadUnavailableWhenArtifactRecordIsMissing() {
    Clarification accepted = accepted("clr_orch_missing", "Q-AUTH-001");
    when(readPort.listByWorkflowRunId(RUN_ID)).thenReturn(List.of(accepted));
    when(artifactRecordPort.findByPublicId(SPEC_V2_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                orchestrator.sweepAfterSpecRebuild(
                    RUN_ID, SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE);
    verify(lifecycleService, never())
        .markIncorporated(anyString(), anyString(), anyString(), anyInt(), any());
    verify(lifecycleService, never())
        .markSuperseded(anyString(), anyString(), anyString(), anyInt(), anyString(), any());
  }

  @Test
  void sweepRejectsBlankWorkflowRunIdP23() {
    assertThatThrownBy(
            () ->
                orchestrator.sweepAfterSpecRebuild(
                    "   ", SPEC_V2_ID, SPEC_V2_VERSION, ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.INVALID_ID_PREFIX);
  }

  @Test
  void sweepRejectsBlankArtifactIdP23() {
    assertThatThrownBy(
            () ->
                orchestrator.sweepAfterSpecRebuild(
                    RUN_ID, "", SPEC_V2_VERSION, ActorContext.SYSTEM))
        .isInstanceOf(DomainException.class)
        .extracting("errorCode")
        .isEqualTo(DomainErrorCode.INVALID_ID_PREFIX);
  }

  private static Clarification accepted(String clarificationId, String questionId) {
    return clarification(clarificationId, questionId, Clarification.STATUS_ACCEPTED);
  }

  private static Clarification clarification(
      String clarificationId, String questionId, String status) {
    boolean answered = !Clarification.STATUS_OPEN.equals(status);
    return new Clarification(
        clarificationId,
        RUN_ID,
        SPEC_V1_ID,
        1,
        questionId,
        "Question text for " + questionId,
        status,
        answered ? "Answer text" : null,
        answered ? "alex@example.com" : null,
        answered ? ActorType.HUMAN : null,
        answered ? OffsetDateTime.parse("2026-05-25T09:00:00Z") : null,
        OffsetDateTime.parse("2026-05-25T08:30:00Z"));
  }

  private static ArtifactRecordSnapshot specV2() {
    return new ArtifactRecordSnapshot(
        SPEC_V2_ID,
        RUN_ID,
        ArtifactType.SPEC,
        SPEC_V2_VERSION,
        SPEC_V1_ID,
        DataClassification.LOCAL_ONLY,
        "artifacts/run_orch_test_001/art_orch_spec_v2/v2/spec.md",
        "SHA-256",
        "abc123",
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        OffsetDateTime.parse("2026-05-25T10:00:00Z"));
  }

  private static ArtifactRecordSnapshot specV1() {
    return new ArtifactRecordSnapshot(
        SPEC_V1_ID,
        RUN_ID,
        ArtifactType.SPEC,
        1,
        null,
        DataClassification.LOCAL_ONLY,
        "artifacts/run_orch_test_001/art_orch_spec_v1/v1/spec.md",
        "SHA-256",
        "abc122",
        null,
        null,
        ArtifactStatus.AVAILABLE,
        null,
        false,
        OffsetDateTime.parse("2026-05-25T09:00:00Z"));
  }
}
