package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

/** Story 3.16 (AC5) — the {@code deliveryline sync-completion} CLI command. */
class WorkflowCommandsSyncCompletionTest {

  private static final String RUN_ID = "run_synccmd1234";

  private WorkflowCommands commandsWith(WorkflowOrchestrationService orchestration) {
    return new WorkflowCommands(
        mock(WorkflowCommandService.class),
        null,
        null,
        () -> false,
        () -> "01964c38-1c45-7000-8000-000000000000",
        () -> "01964c38-1c45-7000-8000-000000000001",
        new IdempotencyKeyValidator(),
        null,
        null,
        new LocalActorIdentityResolver("local-operator"),
        orchestration);
  }

  @Test
  void syncCompletionDelegatesToOrchestrationAndReportsPostedOutcome() {
    WorkflowOrchestrationService orchestration = mock(WorkflowOrchestrationService.class);
    when(orchestration.syncCompletionToLinear(RUN_ID))
        .thenReturn(WorkflowOrchestrationService.SyncCompletionOutcome.POSTED);
    WorkflowCommands commands = commandsWith(orchestration);

    String output = commands.syncCompletion(RUN_ID, "corr-sync-1", true);

    verify(orchestration).syncCompletionToLinear(RUN_ID);
    assertTrue(output.contains(RUN_ID));
    assertTrue(output.contains("completion summary posted to Linear"));
    assertTrue(output.contains("[correlation-id: corr-sync-1]"));
  }

  @Test
  void syncCompletionSurfacesSkipReasonWithoutFailing() {
    WorkflowOrchestrationService orchestration = mock(WorkflowOrchestrationService.class);
    when(orchestration.syncCompletionToLinear(RUN_ID))
        .thenReturn(WorkflowOrchestrationService.SyncCompletionOutcome.SKIPPED_NO_TICKET_LINK);
    WorkflowCommands commands = commandsWith(orchestration);

    String output = commands.syncCompletion(RUN_ID, null, false);

    assertTrue(output.contains("skipped (no linked Linear ticket)"));
  }

  @Test
  void syncCompletionExitsNonZeroWhenLinearPostFailed() {
    WorkflowOrchestrationService orchestration = mock(WorkflowOrchestrationService.class);
    when(orchestration.syncCompletionToLinear(RUN_ID))
        .thenReturn(WorkflowOrchestrationService.SyncCompletionOutcome.POST_FAILED);
    WorkflowCommands commands = commandsWith(orchestration);

    // The post failure is already recorded as an event; the manual CLI surfaces a non-zero exit.
    DomainException error =
        assertThrows(DomainException.class, () -> commands.syncCompletion(RUN_ID, null, false));
    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
  }

  @Test
  void syncCompletionWithoutOrchestrationWiredFailsClosed() {
    // Legacy submit-only constructor leaves orchestration unwired.
    WorkflowCommands commands =
        new WorkflowCommands(
            mock(WorkflowCommandService.class),
            () -> true,
            () -> "01964c38-1c45-7000-8000-000000000000");

    DomainException error =
        assertThrows(DomainException.class, () -> commands.syncCompletion(RUN_ID, null, false));
    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
  }
}
