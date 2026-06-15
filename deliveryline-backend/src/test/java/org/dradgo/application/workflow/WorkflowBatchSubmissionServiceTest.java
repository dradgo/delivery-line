package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.workflow.commands.SubmitBatchCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.spi.BatchSubmissionReadPort;
import org.dradgo.application.workflow.spi.BatchSubmissionWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class WorkflowBatchSubmissionServiceTest {

  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
  private static final String KEY = "idem-batch-aaaaaaaaaaaa";
  private static final OffsetDateTime SUBMITTED_AT =
      OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);

  private WorkflowCommandService workflowCommandService;
  private IdempotencyService idempotencyService;
  private BatchSubmissionWritePort writePort;
  private BatchSubmissionReadPort readPort;
  private UuidV7Generator uuidV7Generator;
  private WorkflowBatchSubmissionService service;

  @AfterAll
  static void closeFactory() {
    VALIDATOR_FACTORY.close();
  }

  @BeforeEach
  void setUp() {
    workflowCommandService = org.mockito.Mockito.mock(WorkflowCommandService.class);
    idempotencyService = org.mockito.Mockito.mock(IdempotencyService.class);
    writePort = org.mockito.Mockito.mock(BatchSubmissionWritePort.class);
    readPort = org.mockito.Mockito.mock(BatchSubmissionReadPort.class);
    uuidV7Generator = org.mockito.Mockito.mock(UuidV7Generator.class);
    PlatformTransactionManager transactionManager =
        org.mockito.Mockito.mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any()))
        .thenReturn(org.mockito.Mockito.mock(TransactionStatus.class));

    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.RESERVED, null));
    when(writePort.insert(any())).thenReturn(SUBMITTED_AT);
    when(writePort.stampBatchSubmissionId(anyString(), anyString())).thenReturn(1);
    when(uuidV7Generator.generate()).thenReturn("01964c38-1c45-7000-8000-000000000000");

    service =
        new WorkflowBatchSubmissionService(
            workflowCommandService,
            idempotencyService,
            new IdempotencyKeyValidator(),
            VALIDATOR,
            writePort,
            readPort,
            uuidV7Generator,
            transactionManager,
            100);
  }

  private SubmitBatchCommand command(List<String> tickets) {
    return new SubmitBatchCommand(tickets, "alex", ActorType.HUMAN, KEY, null);
  }

  private void stubSubmitEchoingTicket() {
    when(workflowCommandService.submit(any()))
        .thenAnswer(
            invocation -> {
              SubmitWorkflowCommand cmd = invocation.getArgument(0);
              return new SubmitWorkflowResult(
                  "run_" + cmd.linearTicketReference(), WorkflowState.INBOX, cmd.correlationId());
            });
  }

  @Test
  void allTicketsQueued() {
    stubSubmitEchoingTicket();

    BatchSubmissionResult result = service.submitBatch(command(List.of("LIN-1", "LIN-2", "LIN-3")));

    assertThat(result.batchId()).startsWith("bat_");
    assertThat(result.total()).isEqualTo(3);
    assertThat(result.queuedCount()).isEqualTo(3);
    assertThat(result.rejectedCount()).isZero();
    assertThat(result.submittedAt()).isEqualTo(SUBMITTED_AT);
    assertThat(result.tickets()).extracting(TicketBatchResult::queueResult).containsOnly("queued");
    verify(idempotencyService)
        .complete(eq(KEY), eq(result.batchId()), eq(IdempotencyRecordStatus.COMPLETED));
  }

  @Test
  void mixedBatchRecordsRejectionsAndContinues() {
    when(workflowCommandService.submit(any()))
        .thenAnswer(
            invocation -> {
              SubmitWorkflowCommand cmd = invocation.getArgument(0);
              if ("LIN-2".equals(cmd.linearTicketReference())) {
                throw new DomainException(
                    DomainErrorCode.LINEAR_TICKET_NOT_FOUND, "no such ticket");
              }
              return new SubmitWorkflowResult(
                  "run_" + cmd.linearTicketReference(), WorkflowState.INBOX, null);
            });

    BatchSubmissionResult result = service.submitBatch(command(List.of("LIN-1", "LIN-2", "LIN-3")));

    assertThat(result.queuedCount()).isEqualTo(2);
    assertThat(result.rejectedCount()).isEqualTo(1);
    // Best-effort: LIN-3 is still processed AFTER LIN-2's rejection.
    assertThat(result.tickets().get(2).ticketRef()).isEqualTo("LIN-3");
    assertThat(result.tickets().get(2).isQueued()).isTrue();
    TicketBatchResult rejected = result.tickets().get(1);
    assertThat(rejected.queueResult()).isEqualTo("rejected");
    assertThat(rejected.rejectionCode()).isEqualTo("LINEAR_TICKET_NOT_FOUND");
    assertThat(rejected.runId()).isNull();
  }

  @Test
  void queueFullMidBatchTruncatesRemainingTickets() {
    when(workflowCommandService.submit(any()))
        .thenAnswer(
            invocation -> {
              SubmitWorkflowCommand cmd = invocation.getArgument(0);
              if ("LIN-1".equals(cmd.linearTicketReference())) {
                return new SubmitWorkflowResult("run_LIN-1", WorkflowState.INBOX, null);
              }
              throw new DomainException(DomainErrorCode.RUNNER_QUEUE_FULL, "queue full");
            });

    BatchSubmissionResult result =
        service.submitBatch(command(List.of("LIN-1", "LIN-2", "LIN-3", "LIN-4")));

    assertThat(result.queuedCount()).isEqualTo(1);
    assertThat(result.rejectedCount()).isEqualTo(3);
    assertThat(result.tickets())
        .filteredOn(t -> !t.isQueued())
        .allMatch(t -> "RUNNER_QUEUE_FULL".equals(t.rejectionCode()));
    // Only LIN-1 (queued) + LIN-2 (queue-full hit) reach submit(); LIN-3/LIN-4 are truncated.
    verify(workflowCommandService, org.mockito.Mockito.times(2)).submit(any());
  }

  @Test
  void idempotentReplayReturnsPriorResultWithoutResubmitting() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new ReservationOutcome(ReservationDecision.REPLAY, "bat_prior12345678"));
    BatchSubmissionResult prior =
        new BatchSubmissionResult(
            "bat_prior12345678",
            SUBMITTED_AT,
            "alex",
            2,
            2,
            0,
            List.of(
                TicketBatchResult.queued("LIN-1", "run_LIN-1"),
                TicketBatchResult.queued("LIN-2", "run_LIN-2")));
    when(readPort.findByPublicId("bat_prior12345678")).thenReturn(java.util.Optional.of(prior));

    BatchSubmissionResult result = service.submitBatch(command(List.of("LIN-1", "LIN-2")));

    assertThat(result).isEqualTo(prior);
    verify(workflowCommandService, never()).submit(any());
    verify(writePort, never()).insert(any());
    verify(idempotencyService, never()).complete(anyString(), any(), any());
  }

  @Test
  void idempotencyConflictPropagates() {
    when(idempotencyService.checkAndReserve(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new DomainException(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT, "conflict"));

    assertThatThrownBy(() -> service.submitBatch(command(List.of("LIN-1"))))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    verify(workflowCommandService, never()).submit(any());
  }

  @Test
  void emptyTicketListRaisesInvalidCommandPayload() {
    assertThatThrownBy(() -> service.submitBatch(command(List.of())))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void exceedingConfiguredMaxRaisesInvalidCommandPayload() {
    WorkflowBatchSubmissionService smallService =
        new WorkflowBatchSubmissionService(
            workflowCommandService,
            idempotencyService,
            new IdempotencyKeyValidator(),
            VALIDATOR,
            writePort,
            readPort,
            uuidV7Generator,
            org.mockito.Mockito.mock(PlatformTransactionManager.class),
            2);

    assertThatThrownBy(() -> smallService.submitBatch(command(List.of("LIN-1", "LIN-2", "LIN-3"))))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
  }

  @Test
  void fingerprintIsOrderInsensitiveOverTheTicketSet() {
    String forward = service.fingerprintFor(command(List.of("LIN-1", "LIN-2", "LIN-3")));
    String shuffled = service.fingerprintFor(command(List.of("LIN-3", "LIN-1", "LIN-2")));
    String different = service.fingerprintFor(command(List.of("LIN-1", "LIN-2", "LIN-9")));

    assertThat(forward).isEqualTo(shuffled);
    assertThat(forward).isNotEqualTo(different);
  }

  @Test
  void derivesChildCorrelationAndDeterministicPerTicketIdempotencyKey() {
    stubSubmitEchoingTicket();

    service.submitBatch(command(List.of("LIN-1", "LIN-2")));

    ArgumentCaptor<SubmitWorkflowCommand> captor =
        ArgumentCaptor.forClass(SubmitWorkflowCommand.class);
    verify(workflowCommandService, org.mockito.Mockito.times(2)).submit(captor.capture());
    List<SubmitWorkflowCommand> commands = captor.getAllValues();
    // Child correlation = {batchCorrelationId}/{ticketRef}; batch correlation is the generated
    // UUID.
    assertThat(commands.get(0).correlationId())
        .isEqualTo("01964c38-1c45-7000-8000-000000000000/LIN-1");
    assertThat(commands.get(1).correlationId())
        .isEqualTo("01964c38-1c45-7000-8000-000000000000/LIN-2");
    // Per-ticket key is a deterministic 64-char hex digest that satisfies IdempotencyKeyValidator.
    assertThat(commands.get(0).idempotencyKey()).matches("[0-9a-f]{64}");
    assertThat(commands.get(0).idempotencyKey()).isNotEqualTo(commands.get(1).idempotencyKey());
  }

  @Test
  void stampsBatchSubmissionIdOnEachQueuedRun() {
    stubSubmitEchoingTicket();

    BatchSubmissionResult result = service.submitBatch(command(List.of("LIN-1", "LIN-2")));

    verify(writePort).stampBatchSubmissionId("run_LIN-1", result.batchId());
    verify(writePort).stampBatchSubmissionId("run_LIN-2", result.batchId());
  }
}
