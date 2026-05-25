package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.registry.ActorType;
import org.junit.jupiter.api.Test;

class WorkflowInspectionServiceClarificationTest {

  private static final String RUN = "run_inspect12345";
  private static final String ART = "art_inspect12345";
  private static final java.time.OffsetDateTime NOW =
      java.time.OffsetDateTime.parse("2026-05-13T10:00:00Z");

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
  void getClarificationsProjectsOrderedRowsFromTheReadPort() {
    when(clarifications.listByWorkflowRunId(RUN))
        .thenReturn(
            List.of(
                clarification("clr_open12345", ART, Clarification.STATUS_OPEN, null),
                clarification("clr_ans123456", ART, Clarification.STATUS_ANSWERED, "answer-1")));

    List<WorkflowInspectionService.ClarificationView> result = service.getClarifications(RUN);

    assertEquals(2, result.size());
    assertEquals("clr_open12345", result.get(0).clarificationId());
    assertEquals(Clarification.STATUS_OPEN, result.get(0).status());
    assertEquals("clr_ans123456", result.get(1).clarificationId());
    assertEquals("answer-1", result.get(1).answerText());
    assertEquals(ActorType.HUMAN.value(), result.get(1).answeredByActorType());
    verify(clarifications).listByWorkflowRunId(RUN);
  }

  @Test
  void getClarificationsForArtifactProjectsRowsFromTheArtifactScopedReadPort() {
    when(clarifications.listByArtifactId(ART))
        .thenReturn(List.of(clarification("clr_artifact1", ART, Clarification.STATUS_ACCEPTED, "ok")));

    List<WorkflowInspectionService.ClarificationView> result =
        service.getClarificationsForArtifact(RUN, ART);

    assertEquals(1, result.size());
    assertEquals("clr_artifact1", result.get(0).clarificationId());
    assertEquals(RUN, result.get(0).workflowRunId());
    assertEquals(ART, result.get(0).artifactId());
    assertEquals(Clarification.STATUS_ACCEPTED, result.get(0).status());
    assertEquals("ok", result.get(0).answerText());
    verify(clarifications).listByArtifactId(ART);
  }

  private static Clarification clarification(
      String clarificationId, String artifactId, String status, String answerText) {
    return new Clarification(
        clarificationId,
        RUN,
        artifactId,
        1,
        "Q1",
        "What is the boundary?",
        status,
        answerText,
        answerText == null ? null : "alex",
        answerText == null ? null : ActorType.HUMAN,
        answerText == null ? null : NOW.plusMinutes(1),
        NOW);
  }
}
