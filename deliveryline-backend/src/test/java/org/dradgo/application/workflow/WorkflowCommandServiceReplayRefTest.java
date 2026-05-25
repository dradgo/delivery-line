package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import jakarta.validation.Validator;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.clarification.ClarificationService;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

class WorkflowCommandServiceReplayRefTest {

  private WorkflowCommandService service;

  @BeforeEach
  void setUp() {
    service =
        new WorkflowCommandService(
            mock(WorkflowRunReadPort.class),
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
            mock(ClarificationService.class));
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
    String resultRef = "run_new123WaitingForSpecApproval";

    String runId = ReflectionTestUtils.invokeMethod(service, "clarificationReplayRunId", resultRef);
    WorkflowState state =
        ReflectionTestUtils.invokeMethod(service, "clarificationReplayState", resultRef);

    assertEquals("run_new123", runId);
    assertEquals(WorkflowState.WAITING_FOR_SPEC_APPROVAL, state);
  }
}
