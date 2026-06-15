package org.dradgo.adapters.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.BatchSubmissionResult;
import org.dradgo.application.workflow.TicketBatchResult;
import org.dradgo.application.workflow.WorkflowBatchSubmissionService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitBatchCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class WorkflowBatchCommandsTest {

  private static final String KEY = "idem-batch-aaaaaaaaaaaa";
  private static final OffsetDateTime SUBMITTED_AT =
      OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);

  private final WorkflowBatchSubmissionService batchService =
      mock(WorkflowBatchSubmissionService.class);

  private WorkflowCommands commands(BooleanSupplier interactive) {
    return new WorkflowCommands(
        mock(WorkflowCommandService.class),
        null,
        null,
        interactive,
        () -> "01964c38-1c45-7000-8000-000000000000",
        () -> "01964c38-1c45-7000-8000-000000000000",
        new IdempotencyKeyValidator(),
        null,
        null,
        new LocalActorIdentityResolver("local-operator"),
        null,
        batchService);
  }

  private BatchSubmissionResult result(int queued, int rejected, List<TicketBatchResult> tickets) {
    return new BatchSubmissionResult(
        "bat_batch12345678",
        SUBMITTED_AT,
        "local-operator",
        queued + rejected,
        queued,
        rejected,
        tickets);
  }

  @Test
  void rendersTabularResultWithHeaderAndPerTicketRows() {
    when(batchService.submitBatch(any()))
        .thenReturn(
            result(
                1,
                1,
                List.of(
                    TicketBatchResult.queued("LIN-1", "run_aaa"),
                    TicketBatchResult.rejected("LIN-2", "LINEAR_TICKET_NOT_FOUND", "no ticket"))));

    String output =
        commands(() -> true)
            .submitBatch("LIN-1,LIN-2", null, "alex", ActorType.HUMAN, KEY, null, false);

    assertThat(output).contains("Ticket | Run ID | Outcome | Reason");
    assertThat(output).contains("LIN-1 | run_aaa | queued | -");
    assertThat(output).contains("LIN-2 | - | rejected | no ticket");
  }

  @Test
  void parsesFromFileDroppingBlankAndCommentLines(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("tickets.txt");
    Files.writeString(
        file,
        "# header comment\nLIN-1\n\n  LIN-2  \n# another comment\nLIN-3\n",
        StandardCharsets.UTF_8);
    when(batchService.submitBatch(any()))
        .thenReturn(result(3, 0, List.of(TicketBatchResult.queued("LIN-1", "run_a"))));

    commands(() -> true)
        .submitBatch(null, file.toString(), "alex", ActorType.HUMAN, KEY, null, false);

    ArgumentCaptor<SubmitBatchCommand> captor = ArgumentCaptor.forClass(SubmitBatchCommand.class);
    verify(batchService).submitBatch(captor.capture());
    assertThat(captor.getValue().linearTicketReferences())
        .containsExactly("LIN-1", "LIN-2", "LIN-3");
  }

  @Test
  void requiresExactlyOneOfTicketsOrFromFile() {
    WorkflowCommands neither = commands(() -> true);
    assertThatThrownBy(
            () -> neither.submitBatch(null, null, "alex", ActorType.HUMAN, KEY, null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    verifyNoInteractions(batchService);

    WorkflowCommands both = commands(() -> true);
    assertThatThrownBy(
            () -> both.submitBatch("LIN-1", "f.txt", "alex", ActorType.HUMAN, KEY, null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void defaultExitDoesNotThrowEvenWithRejections() {
    when(batchService.submitBatch(any()))
        .thenReturn(
            result(
                1,
                1,
                List.of(
                    TicketBatchResult.queued("LIN-1", "run_a"),
                    TicketBatchResult.rejected("LIN-2", "LINEAR_TICKET_NOT_FOUND", "no ticket"))));

    String output =
        commands(() -> true)
            .submitBatch("LIN-1,LIN-2", null, "alex", ActorType.HUMAN, KEY, null, false);

    assertThat(output).contains("rejected 1");
  }

  @Test
  void exitOnAnyRejectionThrowsWhenRejectionsPresent() {
    when(batchService.submitBatch(any()))
        .thenReturn(result(1, 1, List.of(TicketBatchResult.rejected("LIN-2", "X", "boom"))));

    assertThatThrownBy(
            () ->
                commands(() -> true)
                    .submitBatch("LIN-1,LIN-2", null, "alex", ActorType.HUMAN, KEY, null, true))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void exitOnAnyRejectionReturnsNormallyWhenAllQueued() {
    when(batchService.submitBatch(any()))
        .thenReturn(result(2, 0, List.of(TicketBatchResult.queued("LIN-1", "run_a"))));

    String output =
        commands(() -> true)
            .submitBatch("LIN-1,LIN-2", null, "alex", ActorType.HUMAN, KEY, null, true);

    assertThat(output).contains("rejected 0");
  }

  @Test
  void nonInteractiveSubmitBatchRequiresAnExplicitKey() {
    assertThatThrownBy(
            () ->
                commands(() -> false)
                    .submitBatch("LIN-1", null, "alex", ActorType.HUMAN, null, null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.MISSING_IDEMPOTENCY_KEY);
  }

  @Test
  void generatedKeyIsSurfacedWhenKeyOmittedInteractively() {
    when(batchService.submitBatch(any()))
        .thenReturn(result(1, 0, List.of(TicketBatchResult.queued("LIN-1", "run_a"))));

    String output =
        commands(() -> true).submitBatch("LIN-1", null, "alex", ActorType.HUMAN, null, null, false);

    assertThat(output)
        .contains("[generated-idempotency-key: 01964c38-1c45-7000-8000-000000000000]");
  }

  @Test
  void legacyConstructorWithoutBatchServiceFailsWithInternalError() {
    WorkflowCommands legacy =
        new WorkflowCommands(
            mock(WorkflowCommandService.class),
            () -> true,
            () -> "01964c38-1c45-7000-8000-000000000000");

    assertThatThrownBy(
            () -> legacy.submitBatch("LIN-1", null, "alex", ActorType.HUMAN, KEY, null, false))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INTERNAL_ERROR);
    verify(batchService, never()).submitBatch(any());
  }
}
