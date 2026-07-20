package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationService;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.workflow.commands.ApproveLintCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

class WorkflowCommandServiceReplayRefTest {

  private WorkflowCommandService service;
  private ClarificationReadPort clarificationReadPort;
  private WorkflowRunReadPort workflowRunReadPort;

  @BeforeEach
  void setUp() {
    clarificationReadPort = mock(ClarificationReadPort.class);
    workflowRunReadPort = mock(WorkflowRunReadPort.class);
    service =
        new WorkflowCommandService(
            workflowRunReadPort,
            mock(WorkflowRunCreatePort.class),
            mock(WorkflowEventWritePort.class),
            mock(WorkflowTransitionService.class),
            mock(Validator.class),
            mock(PlatformTransactionManager.class),
            mock(IdempotencyService.class),
            mock(IdempotencyKeyValidator.class),
            mock(WorkflowCommandFingerprintFactory.class),
            mock(IntegrationLinkService.class),
            mock(ApprovalService.class),
            mock(org.dradgo.application.approval.TechnicalApprovalService.class),
            mock(org.dradgo.application.workflow.LintApprovalService.class),
            mock(org.dradgo.application.workflow.DeliveryApprovalService.class),
            mock(ClarificationService.class),
            mock(org.dradgo.application.clarification.ClarificationLifecycleService.class),
            clarificationReadPort,
            mock(WorkflowOrchestrationService.class),
            mock(org.dradgo.application.workflow.spi.WorkflowRunRejectionLoopPort.class),
            mock(org.dradgo.application.project.ProjectStore.class),
            mock(org.dradgo.application.project.ProjectRuntimeConfigResolver.class));
  }

  @Test
  void legacyPipeSeparatedReplayRefsStillParse() {
    String resultRef = "run_legacy123|WaitingForSpecApproval";

    String runId = ReflectionTestUtils.invokeMethod(service, "clarificationReplayRunId", resultRef);
    WorkflowState state =
        ReflectionTestUtils.invokeMethod(service, "clarificationReplayState", resultRef);

    assertEquals("run_legacy123", runId);
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL, state);
  }

  @Test
  void newUnitSeparatorReplayRefsStillParse() {
    String resultRef = "run_new123\u001FWaitingForSpecApproval\u001Fanswered";

    String runId = ReflectionTestUtils.invokeMethod(service, "clarificationReplayRunId", resultRef);
    WorkflowState state =
        ReflectionTestUtils.invokeMethod(service, "clarificationReplayState", resultRef);
    String clarificationStatus =
        ReflectionTestUtils.invokeMethod(
            service,
            "clarificationReplayStatus",
            resultRef,
            new SubmitClarificationCommand(
                "run_new123",
                "clr_new123",
                "art_new123",
                1,
                "answer",
                "alex",
                ActorType.HUMAN,
                "idem-new-123",
                "corr-new-123"));

    assertEquals("run_new123", runId);
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL, state);
    assertEquals("answered", clarificationStatus);
  }

  @Test
  void approveLintReplayStateComesFromStoredResultRef() {
    String resultRef = "run_lintreplay123\u001FWaitingForDelivery";
    when(workflowRunReadPort.findByPublicId("run_lintreplay123"))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    "run_lintreplay123", WorkflowState.WAITING_FOR_REVIEW, null, 1L, 0, false)));

    WorkflowStateChangeResult result =
        ReflectionTestUtils.invokeMethod(
            service,
            "replayStateChange",
            resultRef,
            new ApproveLintCommand(
                "run_lintreplay123", "alex", ActorType.HUMAN, "idem-lint", "corr", null));

    assertEquals("run_lintreplay123", result.workflowRunId());
    assertEquals(WorkflowState.WAITING_FOR_DELIVERY, result.currentState());
  }

  @Test
  void legacyReplayRefsRecoverClarificationStatusFromStoredRow() {
    when(clarificationReadPort.findByPublicId("clr_legacy123"))
        .thenReturn(
            Optional.of(
                new Clarification(
                    "clr_legacy123",
                    "run_legacy123",
                    "art_legacy123",
                    1,
                    "Q1",
                    "What changed?",
                    Clarification.STATUS_ACCEPTED,
                    "answer",
                    "alex",
                    ActorType.HUMAN,
                    OffsetDateTime.parse("2026-05-26T10:00:00Z"),
                    OffsetDateTime.parse("2026-05-26T09:00:00Z"))));

    String clarificationStatus =
        ReflectionTestUtils.invokeMethod(
            service,
            "clarificationReplayStatus",
            "run_legacy123|WaitingForSpecApproval",
            new SubmitClarificationCommand(
                "run_legacy123",
                "clr_legacy123",
                "art_legacy123",
                1,
                "answer",
                "alex",
                ActorType.HUMAN,
                "idem-legacy-123",
                "corr-legacy-123"));

    assertEquals("accepted", clarificationStatus);
  }
}
