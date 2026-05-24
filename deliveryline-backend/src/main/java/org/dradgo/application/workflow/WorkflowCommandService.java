package org.dradgo.application.workflow;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.dradgo.application.approval.ApprovalResult;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.WorkflowCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowCommandService {

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowRunCreatePort workflowRunCreatePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final WorkflowTransitionService workflowTransitionService;
  private final Validator validator;
  private final IdempotencyService idempotencyService;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final WorkflowCommandFingerprintFactory fingerprintFactory;
  private final IntegrationLinkService integrationLinkService;
  private final ApprovalService approvalService;
  private final TransactionTemplate failureCompletionTemplate;
  private static final int REPLAY_LOOKUP_ATTEMPTS = 200;
  private static final long REPLAY_LOOKUP_DELAY_MS = 10L;

  public WorkflowCommandService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowRunCreatePort workflowRunCreatePort,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowTransitionService workflowTransitionService,
      Validator validator,
      PlatformTransactionManager transactionManager,
      IdempotencyService idempotencyService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      WorkflowCommandFingerprintFactory fingerprintFactory,
      IntegrationLinkService integrationLinkService,
      ApprovalService approvalService) {
    this.workflowRunReadPort = workflowRunReadPort;
    this.workflowRunCreatePort = workflowRunCreatePort;
    this.workflowEventWritePort = workflowEventWritePort;
    this.workflowTransitionService = workflowTransitionService;
    this.validator = validator;
    this.failureCompletionTemplate = requiresNewTemplate(transactionManager);
    this.idempotencyService = idempotencyService;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
    this.fingerprintFactory = fingerprintFactory;
    this.integrationLinkService = integrationLinkService;
    this.approvalService = approvalService;
  }

  @Transactional
  public SubmitWorkflowResult submit(SubmitWorkflowCommand command) {
    return executeIdempotent(command, this::submitInternal, this::replaySubmit);
  }

  @Transactional
  public WorkflowStateChangeResult approveSpec(ApproveSpecCommand command) {
    return executeIdempotent(command, this::approveSpecInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult rejectSpec(RejectSpecCommand command) {
    return executeIdempotent(command, this::rejectSpecInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult retryWorkflow(RetryWorkflowCommand command) {
    return executeIdempotent(command, this::retryWorkflowInternal, this::replayStateChange);
  }

  @Transactional
  public WorkflowStateChangeResult takeoverWorkflow(TakeoverWorkflowCommand command) {
    return executeIdempotent(command, this::takeoverWorkflowInternal, this::replayStateChange);
  }

  private SubmitWorkflowResult submitInternal(SubmitWorkflowCommand command) {
    // The create path, initial event append, and integration_link creation must stay inside the
    // surrounding @Transactional boundary so they commit or roll back together. If linking the
    // source ticket fails (LINEAR_TICKET_NOT_FOUND / INTEGRATION_LINK_CONFLICT / adapter
    // failure), the workflow_run row is rolled back too — the run never existed.
    var workflowRun =
        workflowRunCreatePort.create(PublicIdPrefixes.WORKFLOW_RUN.next(), WorkflowState.INBOX);
    if (workflowRun.currentState() != WorkflowState.INBOX) {
      throw new IllegalStateException(
          "Workflow run create port must return an INBOX run, but returned "
              + workflowRun.currentState());
    }
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRun.publicId());
    try {
      Map<String, Object> details = baseDetails(command);
      details.put("linearTicketReference", command.linearTicketReference());
      workflowEventWritePort.append(
          new WorkflowEventRecord(
              PublicIdPrefixes.WORKFLOW_EVENT.next(),
              workflowRun.publicId(),
              WorkflowEventType.WORKFLOW_STATE_CHANGED,
              null,
              WorkflowState.INBOX,
              command.actorIdentity(),
              command.actorType(),
              "workflow submitted",
              null,
              false,
              OffsetDateTime.now(ZoneOffset.UTC),
              details));

      integrationLinkService.linkTicketWithinTransaction(
          workflowRun.publicId(),
          command.linearTicketReference(),
          new ActorContext(
              command.actorIdentity(),
              command.actorType(),
              normalizeOptional(command.correlationId())));

      return new SubmitWorkflowResult(
          workflowRun.publicId(), WorkflowState.INBOX, normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult approveSpecInternal(ApproveSpecCommand command) {
    // Story 2.9: the approval row insert + approval.approved event append + transition all
    // happen inside ApprovalService, participating in this method's @Transactional boundary.
    // The legacy REST/CLI contract still returns WorkflowStateChangeResult (story 2.13 will
    // rebuild the surface to expose the richer ApprovalResult directly).
    ApprovalResult approvalResult = approvalService.approveSpec(command);
    return new WorkflowStateChangeResult(
        approvalResult.workflowRunId(),
        approvalResult.resultingState(),
        approvalResult.correlationId());
  }

  private WorkflowStateChangeResult rejectSpecInternal(RejectSpecCommand command) {
    // Story 2.10: the approval row insert (decision=rejected) + approval.rejected event append +
    // counter increment + optional escalation.required event + transition all happen inside
    // ApprovalService, participating in this method's @Transactional boundary. The legacy
    // REST/CLI contract still returns WorkflowStateChangeResult (story 2.13 will rebuild the
    // surface to expose the richer ApprovalResult directly). This replaces the prior minimal
    // stub that only emitted a workflow.stateChanged transition with no approvals row, no
    // approval.rejected event, no counter increment, and no taxonomy (story 2.10 trap T2).
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      ApprovalResult approvalResult = approvalService.rejectSpec(command);
      return new WorkflowStateChangeResult(
          approvalResult.workflowRunId(),
          approvalResult.resultingState(),
          approvalResult.correlationId());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult retryWorkflowInternal(RetryWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      transition(
          command.workflowRunId(),
          WorkflowState.EXECUTING,
          command,
          fallbackReason(command.reasonText(), "retry workflow"),
          Map.of());
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.EXECUTING,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult takeoverWorkflowInternal(TakeoverWorkflowCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      transition(
          command.workflowRunId(),
          WorkflowState.TAKEN_OVER,
          command,
          fallbackReason(command.reasonText(), "take over workflow"),
          Map.of());
      return new WorkflowStateChangeResult(
          command.workflowRunId(),
          WorkflowState.TAKEN_OVER,
          normalizeOptional(command.correlationId()));
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private <T extends DomainResult, C extends WorkflowCommand> T executeIdempotent(
      C command,
      java.util.function.Function<C, T> action,
      java.util.function.BiFunction<String, C, T> replayLoader) {
    validateForExecution(command);
    String fingerprint = fingerprintFactory.fingerprintFor(command);
    IdempotencyService.ReservationOutcome outcome =
        checkAndReserveInIndependentTransaction(command, fingerprint);
    if (outcome.decision() == IdempotencyService.ReservationDecision.REPLAY) {
      return replayLoader.apply(outcome.resultRef(), command);
    }
    try {
      T result = action.apply(command);
      completeWhenTransactionFinishes(command.idempotencyKey(), result.workflowRunId());
      return result;
    } catch (RuntimeException error) {
      completeFailedInIndependentTransaction(command.idempotencyKey(), error);
      throw error;
    }
  }

  private <C extends WorkflowCommand>
      IdempotencyService.ReservationOutcome checkAndReserveInIndependentTransaction(
          C command, String fingerprint) {
    return failureCompletionTemplate.execute(
        ignored ->
            idempotencyService.checkAndReserve(
                command.idempotencyKey(),
                command.commandType(),
                command.actorIdentity(),
                fingerprint));
  }

  private void completeWhenTransactionFinishes(String idempotencyKey, String workflowRunId) {
    completeInIndependentTransaction(
        idempotencyKey, workflowRunId, IdempotencyRecordStatus.COMPLETED);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) {
                completeInIndependentTransaction(
                    idempotencyKey, null, IdempotencyRecordStatus.FAILED);
              }
            }
          });
    }
  }

  private void completeFailedInIndependentTransaction(
      String idempotencyKey, RuntimeException original) {
    try {
      completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
    } catch (RuntimeException completionError) {
      original.addSuppressed(completionError);
    }
  }

  private void completeInIndependentTransaction(
      String idempotencyKey, String resultRef, IdempotencyRecordStatus status) {
    failureCompletionTemplate.execute(
        ignored -> {
          idempotencyService.complete(idempotencyKey, resultRef, status);
          return null;
        });
  }

  private void validateForExecution(WorkflowCommand command) {
    Set<ConstraintViolation<WorkflowCommand>> violations = validator.validate(command);
    boolean hasNonIdempotencyViolations =
        violations.stream()
            .anyMatch(
                violation -> !"idempotencyKey".equals(violation.getPropertyPath().toString()));
    if (hasNonIdempotencyViolations) {
      throw invalidCommandPayload(command, violations);
    }
    idempotencyKeyValidator.requireValid(command.idempotencyKey());
  }

  private void transition(
      String workflowRunId,
      WorkflowState targetState,
      WorkflowCommand command,
      String reason,
      Map<String, Object> extraDetails) {
    workflowTransitionService.transition(
        workflowRunId,
        targetState,
        new TransitionActor(command.actorIdentity(), command.actorType()),
        reason,
        command.idempotencyKey(),
        commandDetails(command, extraDetails));
  }

  private DomainException invalidCommandPayload(
      WorkflowCommand command, Set<ConstraintViolation<WorkflowCommand>> violations) {
    List<Map<String, Object>> fieldErrors = new ArrayList<>();
    violations.stream()
        .sorted(
            Comparator.comparing(
                    (ConstraintViolation<WorkflowCommand> violation) ->
                        violation.getPropertyPath().toString())
                .thenComparing(
                    violation ->
                        violation
                            .getConstraintDescriptor()
                            .getAnnotation()
                            .annotationType()
                            .getSimpleName()))
        .forEach(
            violation -> {
              Map<String, Object> fieldError = new LinkedHashMap<>();
              fieldError.put("field", violation.getPropertyPath().toString());
              fieldError.put(
                  "code",
                  violation
                      .getConstraintDescriptor()
                      .getAnnotation()
                      .annotationType()
                      .getSimpleName());
              fieldError.put("rejectedValue", violation.getInvalidValue());
              fieldError.put("message", violation.getMessage());
              fieldErrors.add(fieldError);
            });

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("commandType", command.commandType());
    details.put("fieldErrors", fieldErrors);
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Invalid command payload for " + command.commandType(),
        details);
  }

  private Map<String, Object> baseDetails(WorkflowCommand command) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("commandType", command.commandType());
    details.put("idempotencyKey", command.idempotencyKey());
    String correlationId = normalizeOptional(command.correlationId());
    if (correlationId != null) {
      details.put("correlationId", correlationId);
    }
    return details;
  }

  private Map<String, Object> commandDetails(
      WorkflowCommand command, Map<String, Object> extraDetails) {
    // Canonical envelope keys (commandType, idempotencyKey, correlationId) win over
    // caller-supplied extraDetails so a future caller cannot accidentally clobber them.
    Map<String, Object> details = new LinkedHashMap<>(extraDetails);
    details.putAll(baseDetails(command));
    return details;
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  // Retry/Takeover commands accept an optional reasonText; when omitted we substitute a
  // stable system-supplied fallback so the audit trail always carries a non-empty reason.
  // This is intentional: requiring reasonText on retry/takeover would be a UX regression
  // for the operator-recovery happy path. RejectSpecCommand keeps @NotBlank because spec
  // rejection is an explicit operator decision that should always carry justification.
  private String fallbackReason(String reason, String fallback) {
    String normalized = normalizeOptional(reason);
    return normalized == null ? fallback : normalized;
  }

  private SubmitWorkflowResult replaySubmit(String resultRef, SubmitWorkflowCommand command) {
    var workflowRun = findWorkflowRunForReplay(resultRef);
    return new SubmitWorkflowResult(
        workflowRun.publicId(),
        workflowRun.currentState(),
        normalizeOptional(command.correlationId()));
  }

  private WorkflowStateChangeResult replayStateChange(String resultRef, WorkflowCommand command) {
    var workflowRun = findWorkflowRunForReplay(resultRef);
    // Replay must return the original command result, not the run's later live state. Commands
    // with invariant post-states are therefore pinned here; callers arriving after the run has
    // advanced still receive the state produced by the original accepted command.
    WorkflowState resultingState =
        switch (command) {
          case ApproveSpecCommand ignored -> WorkflowState.EXECUTING;
          case RejectSpecCommand ignored -> WorkflowState.INVESTIGATING;
          default -> workflowRun.currentState();
        };
    return new WorkflowStateChangeResult(
        workflowRun.publicId(), resultingState, normalizeOptional(command.correlationId()));
  }

  private WorkflowRunSnapshot findWorkflowRunForReplay(String workflowRunId) {
    for (int attempt = 0; attempt < REPLAY_LOOKUP_ATTEMPTS; attempt++) {
      var workflowRun = workflowRunReadPort.findByPublicId(workflowRunId);
      if (workflowRun.isPresent()) {
        return workflowRun.get();
      }
      pauseBeforeReplayLookup();
    }
    throw new DomainException(
        DomainErrorCode.RUN_NOT_FOUND,
        "Workflow run not found: " + workflowRunId,
        Map.of("runId", workflowRunId));
  }

  private void pauseBeforeReplayLookup() {
    try {
      Thread.sleep(REPLAY_LOOKUP_DELAY_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Interrupted while waiting for workflow run replay visibility",
          Map.of("delayMs", REPLAY_LOOKUP_DELAY_MS));
    }
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
