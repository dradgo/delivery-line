package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.ClarificationStatusView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowRunDetailedSummaryView;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowInspectionServiceClarificationStatusTest {

  private static final String RUN = "run_clrstatus123";
  private static final String OTHER_RUN = "run_clrstatus999";
  private static final String ART = "art_clrstatus123";
  private static final String NEW_ART = "art_clrstatus456";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-25T12:00:00Z");

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final ArtifactRecordPort artifacts = mock(ArtifactRecordPort.class);
  private final org.dradgo.application.approval.spi.ApprovalReadPort approvals =
      mock(org.dradgo.application.approval.spi.ApprovalReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final org.dradgo.application.runner.spi.RunnerExecutionRecordPort runnerExecutions =
      mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class);
  private final org.dradgo.application.runner.spi.RunnerScratchStore scratchStore =
      mock(org.dradgo.application.runner.spi.RunnerScratchStore.class);
  private final org.dradgo.application.clarification.spi.ClarificationReadPort clarifications =
      mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class);

  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          approvals,
          links,
          new RedactionPolicyService(new DataClassificationService()),
          recovery,
          runnerExecutions,
          scratchStore,
          clarifications);

  @Test
  void getClarificationStatusProjectsEachLifecycleShape() {
    List<ClarificationLifecycleSnapshot> snapshots =
        List.of(
            snapshot("clr_open12345", Clarification.STATUS_OPEN, RUN, null, null, null, null, null),
            snapshot(
                "clr_answered1",
                Clarification.STATUS_ANSWERED,
                RUN,
                null,
                null,
                null,
                null,
                "answer-1"),
            snapshot(
                "clr_accept123",
                Clarification.STATUS_ACCEPTED,
                RUN,
                NOW.minusMinutes(5),
                null,
                null,
                null,
                "answer-2"),
            snapshot(
                "clr_incorp123",
                Clarification.STATUS_INCORPORATED,
                RUN,
                NOW.minusMinutes(10),
                NOW.minusMinutes(1),
                NEW_ART,
                null,
                "answer-3"),
            snapshot(
                "clr_super1234",
                Clarification.STATUS_SUPERSEDED,
                RUN,
                NOW.minusMinutes(15),
                null,
                null,
                NEW_ART,
                "clarification_not_addressed"),
            snapshot(
                "clr_rejected1",
                Clarification.STATUS_REJECTED_INVALID,
                RUN,
                null,
                null,
                null,
                null,
                "answer-4",
                "pm_marked_invalid"));

    for (ClarificationLifecycleSnapshot snapshot : snapshots) {
      when(clarifications.findLifecycleSnapshotByPublicId(snapshot.publicId()))
          .thenReturn(Optional.of(snapshot));
    }

    for (ClarificationLifecycleSnapshot snapshot : snapshots) {
      ClarificationStatusView view = service.getClarificationStatus(RUN, snapshot.publicId());

      assertEquals(snapshot.publicId(), view.clarificationId());
      assertEquals(snapshot.workflowRunId(), view.workflowRunId());
      assertEquals(snapshot.artifactId(), view.artifactId());
      assertEquals(snapshot.artifactVersion(), view.artifactVersion());
      assertEquals(snapshot.questionId(), view.questionId());
      assertEquals(snapshot.questionText(), view.questionText());
      assertEquals(snapshot.status(), view.status());
      assertEquals(snapshot.answerText(), view.answerText());
      assertEquals(snapshot.answeredByActor(), view.answeredByActor());
      assertEquals(
          snapshot.answeredByActorType() == null ? null : snapshot.answeredByActorType().value(),
          view.answeredByActorType());
      assertEquals(snapshot.answeredAt(), view.answeredAt());
      assertEquals(snapshot.acceptedAt(), view.acceptedAt());
      assertEquals(snapshot.incorporatedAt(), view.incorporatedAt());
      assertEquals(snapshot.incorporatedIntoArtifactPublicId(), view.incorporatedIntoArtifactId());
      assertEquals(snapshot.supersededByArtifactPublicId(), view.supersededByArtifactId());
      assertEquals(snapshot.noEffectReason(), view.noEffectReason());
      assertEquals(snapshot.createdAt(), view.createdAt());
    }
  }

  @Test
  void getClarificationStatusCrossRunRowRaisesClarificationNotFound() {
    when(clarifications.findLifecycleSnapshotByPublicId("clr_cross1234"))
        .thenReturn(
            Optional.of(
                snapshot(
                    "clr_cross1234",
                    Clarification.STATUS_ANSWERED,
                    OTHER_RUN,
                    null,
                    null,
                    null,
                    null,
                    "answer-cross")));

    DomainException error =
        assertThrows(
            DomainException.class, () -> service.getClarificationStatus(RUN, "clr_cross1234"));

    assertEquals(DomainErrorCode.CLARIFICATION_NOT_FOUND, error.errorCode());
    assertEquals("clr_cross1234", error.details().get("clarificationId"));
    assertEquals(RUN, error.details().get("workflowRunId"));
  }

  @Test
  void getRunSummaryIncludesPendingClarificationsAndLatestEvent() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, 7L, 2, true)));
    when(events.findLatestByWorkflowRunPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_summary123",
                    RUN,
                    WorkflowEventType.CLARIFICATION_SUPERSEDED,
                    null,
                    null,
                    "system",
                    ActorType.SYSTEM,
                    "clarification superseded",
                    null,
                    false,
                    NOW,
                    Map.of("clarificationId", "clr_super1234"))));
    when(clarifications.countPendingByWorkflowRun(RUN)).thenReturn(3);

    WorkflowRunDetailedSummaryView view = service.getRunSummary(RUN);

    assertEquals(RUN, view.workflowRunId());
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), view.currentState());
    assertEquals(NOW, view.lastEventAt());
    assertEquals(WorkflowEventType.CLARIFICATION_SUPERSEDED.value(), view.lastEventType());
    assertEquals(2, view.specRejectionLoopCount());
    assertEquals(true, view.escalationMarker());
    assertEquals(3, view.pendingClarifications());
    verify(clarifications).countPendingByWorkflowRun(RUN);
  }

  @Test
  void listRunsProjectsPendingClarificationsPerRow() {
    when(runs.listRuns(null, 2))
        .thenReturn(
            List.of(
                new WorkflowRunSnapshot(
                    "run_list_1", WorkflowState.EXECUTING, null, 1L, 0, false),
                new WorkflowRunSnapshot(
                    "run_list_2", WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, 2L, 1, true)));
    when(events.findLatestByWorkflowRunPublicId("run_list_1"))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_list_1",
                    "run_list_1",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.INBOX,
                    WorkflowState.EXECUTING,
                    "alex",
                    ActorType.HUMAN,
                    "state changed",
                    null,
                    false,
                    NOW.minusMinutes(2),
                    Map.of())));
    when(events.findLatestByWorkflowRunPublicId("run_list_2")).thenReturn(Optional.empty());
    when(links.findActiveLinkByWorkflowRun("run_list_1")).thenReturn(Optional.empty());
    when(links.findActiveLinkByWorkflowRun("run_list_2")).thenReturn(Optional.empty());
    when(clarifications.countPendingByWorkflowRun("run_list_1")).thenReturn(1);
    when(clarifications.countPendingByWorkflowRun("run_list_2")).thenReturn(4);

    List<WorkflowInspectionService.WorkflowRunSummaryView> result = service.listRuns(null, 2);

    assertEquals(2, result.size());
    assertEquals("run_list_1", result.get(0).workflowRunId());
    assertEquals(1, result.get(0).pendingClarifications());
    assertEquals(WorkflowEventType.WORKFLOW_STATE_CHANGED.value(), result.get(0).lastEventType());
    assertEquals("run_list_2", result.get(1).workflowRunId());
    assertEquals(4, result.get(1).pendingClarifications());
    assertNull(result.get(1).lastEventType());
  }

  private static ClarificationLifecycleSnapshot snapshot(
      String clarificationId,
      String status,
      String runId,
      OffsetDateTime acceptedAt,
      OffsetDateTime incorporatedAt,
      String incorporatedIntoArtifactId,
      String supersededByArtifactId,
      String answerText) {
    return snapshot(
        clarificationId,
        status,
        runId,
        acceptedAt,
        incorporatedAt,
        incorporatedIntoArtifactId,
        supersededByArtifactId,
        answerText,
        supersededByArtifactId == null ? null : "clarification_not_addressed");
  }

  private static ClarificationLifecycleSnapshot snapshot(
      String clarificationId,
      String status,
      String runId,
      OffsetDateTime acceptedAt,
      OffsetDateTime incorporatedAt,
      String incorporatedIntoArtifactId,
      String supersededByArtifactId,
      String answerText,
      String noEffectReason) {
    boolean answered = !Clarification.STATUS_OPEN.equals(status);
    return new ClarificationLifecycleSnapshot(
        clarificationId,
        runId,
        ART,
        1,
        "Q-123",
        "What is the boundary?",
        status,
        answerText,
        answered ? "alex@example.com" : null,
        answered ? ActorType.HUMAN : null,
        answered ? NOW.minusHours(1) : null,
        acceptedAt,
        incorporatedAt,
        incorporatedAt == null ? null : "evt_" + clarificationId,
        incorporatedIntoArtifactId,
        supersededByArtifactId,
        supersededByArtifactId == null ? null : 2,
        noEffectReason,
        NOW.minusHours(2));
  }
}
