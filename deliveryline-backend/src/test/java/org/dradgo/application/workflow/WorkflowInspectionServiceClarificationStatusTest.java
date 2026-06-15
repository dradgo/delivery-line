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
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
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
  private final org.dradgo.application.recovery.spi.RecoveryActionRecordPort recoveryActions =
      mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class);

  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class),
          approvals,
          links,
          new RedactionPolicyService(new DataClassificationService()),
          recovery,
          runnerExecutions,
          scratchStore,
          clarifications,
          recoveryActions,
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults());

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
    // Story 3.20 FR21: no approvals seeded -> both states NONE (never collapsed).
    assertEquals("NONE", view.productApprovalState());
    assertEquals("NONE", view.technicalApprovalState());
    verify(clarifications).countPendingByWorkflowRun(RUN);
  }

  @Test
  void getRunSummarySpecApprovedOnlyYieldsProductApprovedTechnicalNone() {
    stubMinimalRunForSummary();
    when(approvals.findLatestApprovedForArtifactLineage(RUN, "spec"))
        .thenReturn(Optional.of(approved("apr_spec001", "product_reviewer")));

    WorkflowRunDetailedSummaryView view = service.getRunSummary(RUN);

    // FR21: product and technical acceptance are DISTINCT — a spec approval must not light
    // technical.
    assertEquals("APPROVED", view.productApprovalState());
    assertEquals("NONE", view.technicalApprovalState());
  }

  @Test
  void getRunSummaryPlanApprovedYieldsTechnicalApprovedProductNone() {
    stubMinimalRunForSummary();
    when(approvals.findLatestApprovedForArtifactLineage(RUN, "implementationPlan"))
        .thenReturn(Optional.of(approved("apr_plan001", "developer")));

    WorkflowRunDetailedSummaryView view = service.getRunSummary(RUN);

    assertEquals("NONE", view.productApprovalState());
    assertEquals("APPROVED", view.technicalApprovalState());
  }

  @Test
  void getRunSummaryPrOutputApprovedYieldsTechnicalApproved() {
    stubMinimalRunForSummary();
    when(approvals.findLatestApprovedForArtifactLineage(RUN, "prOutput"))
        .thenReturn(Optional.of(approved("apr_pr0001", "developer")));

    WorkflowRunDetailedSummaryView view = service.getRunSummary(RUN);

    assertEquals("APPROVED", view.technicalApprovalState());
  }

  @Test
  void getRunSummaryForTakenOverRunSourcesAttributionFromRecoveryActionNotEvent() {
    OffsetDateTime takeoverAt = NOW.minusMinutes(10);
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(RUN, WorkflowState.TAKEN_OVER, null, 7L, 0, false)));
    // A later audit event is the "latest" — it must NOT supply the takeover actor.
    when(events.findLatestByWorkflowRunPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_later9999",
                    RUN,
                    WorkflowEventType.RUNNER_COMPLETED,
                    null,
                    null,
                    "runner-system",
                    ActorType.SYSTEM,
                    "later audit event",
                    null,
                    false,
                    NOW,
                    Map.of())));
    // The → TakenOver transition event carries a DIFFERENT actor than the recovery action; only its
    // reason (the reviewer free-text, OQ-4 source) must flow through, never its actor/role.
    when(events.findLatestTransitionToState(RUN, WorkflowState.TAKEN_OVER))
        .thenReturn(
            Optional.of(
                new WorkflowEventRecord(
                    "evt_takeover123",
                    RUN,
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.WAITING_FOR_REVIEW,
                    WorkflowState.TAKEN_OVER,
                    "transition-actor",
                    ActorType.SYSTEM,
                    "continuing in IDE",
                    null,
                    true,
                    takeoverAt.plusSeconds(1),
                    Map.of())));
    // The authoritative takeover recovery_actions row supplies who/when/role.
    when(recoveryActions.findLatestTakeoverForRun(RUN))
        .thenReturn(
            Optional.of(
                new RecoveryActionSnapshot(
                    "rcv_takeover01",
                    1L,
                    RUN,
                    "takeover",
                    "evt_trigger001",
                    "evt_takeover123",
                    "alex",
                    ActorType.HUMAN,
                    "idem-takeover-attr-0001",
                    "succeeded",
                    takeoverAt,
                    "developer")));
    when(clarifications.countPendingByWorkflowRun(RUN)).thenReturn(0);

    WorkflowRunDetailedSummaryView view = service.getRunSummary(RUN);

    assertEquals("TakenOver", view.currentState());
    // who/when/role come from the recovery action, not the transition event or the latest event.
    assertEquals("alex", view.takenOverBy().actorIdentity());
    assertEquals("human", view.takenOverBy().actorType());
    assertEquals("developer", view.takenOverBy().reviewerRole());
    assertEquals(takeoverAt, view.takenOverAt());
    // reason is the only field sourced from the transition event (OQ-4: no recovery_actions
    // column).
    assertEquals("continuing in IDE", view.takenOverReason());
    verify(recoveryActions).findLatestTakeoverForRun(RUN);
  }

  @Test
  void getRunSummaryForNonTakenOverRunSkipsTakeoverLookupAndLeavesAttributionNull() {
    stubMinimalRunForSummary();

    WorkflowRunDetailedSummaryView view = service.getRunSummary(RUN);

    assertNull(view.takenOverBy());
    assertNull(view.takenOverAt());
    assertNull(view.takenOverReason());
    verify(recoveryActions, org.mockito.Mockito.never()).findLatestTakeoverForRun(RUN);
  }

  private void stubMinimalRunForSummary() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN, WorkflowState.WAITING_FOR_REVIEW, null, 7L, 0, false)));
    when(events.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());
    when(clarifications.countPendingByWorkflowRun(RUN)).thenReturn(0);
  }

  private static org.dradgo.application.approval.ApprovalSnapshot approved(
      String approvalId, String reviewerRole) {
    return new org.dradgo.application.approval.ApprovalSnapshot(
        approvalId,
        RUN,
        "art_" + approvalId,
        1,
        1,
        "actor@example.com",
        ActorType.HUMAN,
        reviewerRole,
        org.dradgo.application.approval.ApprovalSnapshot.DECISION_APPROVED,
        null,
        null,
        NOW.minusMinutes(5));
  }

  @Test
  void listRunsProjectsPendingClarificationsPerRow() {
    when(runs.listRuns(null, 2))
        .thenReturn(
            List.of(
                new WorkflowRunSnapshot("run_list_1", WorkflowState.EXECUTING, null, 1L, 0, false),
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
