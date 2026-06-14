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
import org.dradgo.application.approval.TechnicalApprovalService;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationResult;
import org.dradgo.application.clarification.ClarificationService;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.WorkflowCommandFingerprintFactory;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.RetryWorkflowCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
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
  /**
   * P10 — replay-ref separator switched from {@code "|"} (which is a legal char in workflow run
   * public ids and state names per a future regex relaxation) to ASCII Unit Separator {@code
   * U+001F}. The character is disallowed in every component of the resultRef (run-public-id and
   * state-value regexes both ban control characters), so the indexOf split is guaranteed
   * unambiguous. The parser remains backward-compatible with legacy {@code "|"}-encoded refs
   * already persisted in {@code idempotency_records.result_ref}; new writes use {@code U+001F}.
   */
  private static final String CLARIFICATION_REPLAY_REF_SEPARATOR = "";

  private static final String LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR = "|";

  /**
   * Sentinel surfaced for clarification idempotent replays whose underlying row was hard-deleted
   * before the replay arrived (story-2.13 round-3 decision D-Round3-1). Preserves the
   * idempotent-replay-never-fails contract for previously-successful 200s — callers see {@code
   * clarificationStatus="unknown"} instead of a 500. Only ever surfaced for the legacy 2-segment
   * replay-ref population; fresh writes embed the real status in the ref.
   */
  static final String LEGACY_CLARIFICATION_REPLAY_STATUS_UNKNOWN = "unknown";

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
  private final TechnicalApprovalService technicalApprovalService;
  private final ClarificationService clarificationService;
  private final ClarificationReadPort clarificationReadPort;
  // Story 3a-1 (Task 5 / AC1) — auto-dispatch the spec runner once a submitted run is created.
  private final WorkflowOrchestrationService workflowOrchestrationService;
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
      ApprovalService approvalService,
      TechnicalApprovalService technicalApprovalService,
      ClarificationService clarificationService,
      ClarificationReadPort clarificationReadPort,
      WorkflowOrchestrationService workflowOrchestrationService) {
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
    this.technicalApprovalService = technicalApprovalService;
    this.clarificationService = clarificationService;
    this.clarificationReadPort = clarificationReadPort;
    this.workflowOrchestrationService = workflowOrchestrationService;
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
  public WorkflowStateChangeResult acceptImplementation(AcceptImplementationCommand command) {
    // Story 3.20: technical-approval twin of approveSpec. The approval row insert +
    // approval.approved event append + transition (+ implementation-plan dispatch) all happen
    // inside TechnicalApprovalService, participating in this method's @Transactional boundary. The
    // legacy contract returns WorkflowStateChangeResult; story 3.23 will expose the richer
    // ApprovalResult through the REST surface. Replay is special-cased
    // (replayAcceptImplementation):
    // the resulting state depends on the artifact type, so it cannot be hard-coded (Trap T2).
    return executeIdempotent(
        command, this::acceptImplementationInternal, this::replayAcceptImplementation);
  }

  @Transactional
  public WorkflowStateChangeResult answerClarification(SubmitClarificationCommand command) {
    // Story 2.11: the clarification row UPDATE + clarification.answered event append all happen
    // inside ClarificationService, participating in this method's @Transactional boundary. The
    // legacy contract still returns WorkflowStateChangeResult (story 2.13 will rebuild the surface
    // to expose ClarificationResult directly). OQ-1 resolved as recommended: idempotency wired
    // here in 2.11 so replays land correctly end-to-end.
    return executeIdempotent(
        command,
        this::answerClarificationInternal,
        this::replayStateChange,
        this::clarificationReplayRef);
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

      // Story 3a-1 (AC1 / Task 5) — auto-dispatch the spec runner. This shares the submit
      // transaction (Dev Notes §"Transaction & async boundary"): the run + Linear link + the
      // Inbox->Investigating transition + the runner_executions row commit or roll back together,
      // so a dispatch failure unwinds the submit. The async runner RESULT arrives later via the
      // poller (a separate transaction). No-op when spec-stage.auto-dispatch=false (test profile),
      // which leaves the run in Inbox.
      workflowOrchestrationService.dispatchSpecGeneration(
          workflowRun.publicId(), normalizeOptional(command.correlationId()));

      // Story 3a-1 review finding P4 — report the run's ACTUAL committed state, not the stale
      // just-created Inbox value: when auto-dispatch advanced the run to Investigating in THIS
      // transaction the caller must see Investigating. Re-read within the transaction
      // (auto-dispatch
      // disabled leaves it Inbox; enabled => Investigating).
      //
      // The re-read is purely cosmetic — it only populates the response's currentState. Guard it so
      // a transient read failure cannot escape and roll back this @Transactional submit (the
      // created
      // run + Linear link + Inbox->Investigating transition + dispatched runner_executions row all
      // committed together). Fall back to the just-created state on an empty result OR a read
      // error.
      WorkflowState resultingState;
      try {
        resultingState =
            workflowRunReadPort
                .findByPublicId(workflowRun.publicId())
                .map(WorkflowRunSnapshot::currentState)
                .orElse(workflowRun.currentState());
      } catch (RuntimeException readError) {
        resultingState = workflowRun.currentState();
      }
      return new SubmitWorkflowResult(
          workflowRun.publicId(), resultingState, normalizeOptional(command.correlationId()));
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

  private WorkflowStateChangeResult acceptImplementationInternal(
      AcceptImplementationCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      ApprovalResult approvalResult = technicalApprovalService.acceptImplementation(command);
      return new WorkflowStateChangeResult(
          approvalResult.workflowRunId(),
          approvalResult.resultingState(),
          approvalResult.correlationId());
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunId);
    }
  }

  private WorkflowStateChangeResult answerClarificationInternal(
      SubmitClarificationCommand command) {
    String priorRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunId());
    try {
      // Story 2.13 round-4 P-R4-4: read currentState FIRST so the response reflects the workflow
      // state at the START of the answer operation, not whatever a concurrent reject/takeover/retry
      // happened to commit during the answer write. Trap T6 says answering does NOT advance state —
      // if the read came after the clarification write, a concurrent state-change could surface as
      // the answer's "post-mutation" state and contradict AC9. Both calls sit inside the outer
      // @Transactional answerClarification, so READ COMMITTED still sees the latest committed value
      // at this point, but the resulting currentState now belongs to the snapshot the answer
      // started from — not to an unrelated concurrent transition.
      WorkflowState currentState =
          workflowRunReadPort
              .findByPublicId(command.workflowRunId())
              .map(WorkflowRunSnapshot::currentState)
              .orElseThrow(
                  () ->
                      new DomainException(
                          DomainErrorCode.RUN_NOT_FOUND,
                          "Workflow run not found: " + command.workflowRunId(),
                          Map.of("runId", command.workflowRunId())));
      ClarificationResult result = clarificationService.submitAnswer(command);
      return new WorkflowStateChangeResult(
          result.workflowRunId(), currentState, result.correlationId(), result.status());
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
    return executeIdempotent(command, action, replayLoader, DomainResult::workflowRunId);
  }

  private <T extends DomainResult, C extends WorkflowCommand> T executeIdempotent(
      C command,
      java.util.function.Function<C, T> action,
      java.util.function.BiFunction<String, C, T> replayLoader,
      java.util.function.Function<T, String> resultRefExtractor) {
    validateForExecution(command);
    String fingerprint = fingerprintFactory.fingerprintFor(command);
    IdempotencyService.ReservationOutcome outcome =
        checkAndReserveInIndependentTransaction(command, fingerprint);
    if (outcome.decision() == IdempotencyService.ReservationDecision.REPLAY) {
      return replayLoader.apply(outcome.resultRef(), command);
    }
    try {
      T result = action.apply(command);
      completeWhenTransactionFinishes(command.idempotencyKey(), resultRefExtractor.apply(result));
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

  /**
   * Story 3.20 (Trap T2): {@code acceptImplementation}'s resulting state depends on the accepted
   * artifact's type — {@code Completed} for a {@code prOutput}, {@code Executing} for an {@code
   * implementationPlan}. The generic {@link #replayStateChange} hard-codes the post-state per
   * command type, which would return the wrong state here, so this dedicated replay re-reads the
   * run and returns its <strong>current</strong> {@code currentState()}. The run never leaves
   * {@code Completed} (terminal) once reached, and an {@code implementationPlan} acceptance lands
   * {@code Executing}; either way the live current state is the correct replay answer.
   */
  private WorkflowStateChangeResult replayAcceptImplementation(
      String resultRef, AcceptImplementationCommand command) {
    var workflowRun = findWorkflowRunForReplay(resultRef);
    return new WorkflowStateChangeResult(
        workflowRun.publicId(),
        workflowRun.currentState(),
        normalizeOptional(command.correlationId()));
  }

  private WorkflowStateChangeResult replayStateChange(String resultRef, WorkflowCommand command) {
    String workflowRunId =
        switch (command) {
          case SubmitClarificationCommand ignored -> clarificationReplayRunId(resultRef);
          default -> resultRef;
        };
    var workflowRun = findWorkflowRunForReplay(workflowRunId);
    // Replay must return the original command result, not the run's later live state. Commands
    // with invariant post-states are therefore pinned here; callers arriving after the run has
    // advanced still receive the state produced by the original accepted command.
    WorkflowState resultingState =
        switch (command) {
          case ApproveSpecCommand ignored -> WorkflowState.EXECUTING;
          case RejectSpecCommand ignored -> WorkflowState.INVESTIGATING;
          case SubmitClarificationCommand ignored -> clarificationReplayState(resultRef);
          default -> workflowRun.currentState();
        };
    if (command instanceof SubmitClarificationCommand submitClarificationCommand) {
      return new WorkflowStateChangeResult(
          workflowRun.publicId(),
          resultingState,
          normalizeOptional(command.correlationId()),
          clarificationReplayStatus(resultRef, submitClarificationCommand));
    }
    return new WorkflowStateChangeResult(
        workflowRun.publicId(), resultingState, normalizeOptional(command.correlationId()));
  }

  private String clarificationReplayRef(WorkflowStateChangeResult result) {
    if (result.currentState() == null) {
      // Defensive: a null currentState would NPE here and surface as INTERNAL_ERROR via the
      // failed-tx pipeline for a command that actually succeeded. Today the answer flow always
      // populates currentState, but future result variants must not silently corrupt idempotency.
      throw new IllegalStateException(
          "WorkflowStateChangeResult.currentState() is null for clarification replay ref");
    }
    String clarificationStatus = normalizeOptional(result.clarificationStatus());
    if (clarificationStatus == null) {
      throw new IllegalStateException(
          "WorkflowStateChangeResult.clarificationStatus() is null for clarification replay ref");
    }
    return result.workflowRunId()
        + CLARIFICATION_REPLAY_REF_SEPARATOR
        + result.currentState().value()
        + CLARIFICATION_REPLAY_REF_SEPARATOR
        + clarificationStatus;
  }

  private String clarificationReplayRunId(String resultRef) {
    return parseClarificationReplayRef(resultRef).workflowRunId();
  }

  private WorkflowState clarificationReplayState(String resultRef) {
    return parseClarificationReplayRef(resultRef).workflowState();
  }

  /**
   * Story 2.13 review D1: legacy two-segment {@code resultRef} values written before this story
   * stored only {@code workflowRunId + SEP + state.value()} — they do not encode the {@code
   * clarificationStatus} that callers now expect on replay. For these legacy refs the resolver
   * falls back to a live read against {@link ClarificationReadPort}, which means:
   *
   * <ul>
   *   <li>If the row has advanced (answered → accepted / incorporated / superseded /
   *       rejected_invalid) between original-answer time and replay time, the replay observes the
   *       <em>current</em> row status, not the answer-time status. Idempotent replays across the
   *       legacy migration window may therefore not reproduce the original response byte-for-byte.
   *   <li>If the row has been hard-deleted (operator action, retention sweep), the replay returns
   *       the {@link #LEGACY_CLARIFICATION_REPLAY_STATUS_UNKNOWN} sentinel (story-2.13 round-3
   *       decision D-Round3-1) so the previously-200 response stays 200 and the idempotent-replay
   *       contract is preserved. Bounded to the legacy window; fresh writes always carry the
   *       3-segment ref with the embedded status.
   * </ul>
   *
   * <p>This drift is accepted (story-2.13 review decision D1 + D-Round3-1) rather than
   * reject-legacy or backfill, because the migration window is bounded and the operator-visible
   * blast radius is limited to the small population of pre-2.13 idempotency rows still in flight.
   */
  private String clarificationReplayStatus(String resultRef, SubmitClarificationCommand command) {
    ClarificationReplayRef replayRef = parseClarificationReplayRef(resultRef);
    if (replayRef.clarificationStatus() != null) {
      return replayRef.clarificationStatus();
    }
    // Story 2.13 round-4 P-R4-1: cross-run guard on the legacy 2-segment replay-ref fallback. The
    // idempotency record fingerprint normally blocks cross-run replays, but the legacy path reads
    // from a port keyed on clarificationId alone — a tampered URL or migration-era leaked ref
    // would otherwise surface another run's clarification status. Reject mismatches as
    // CLARIFICATION_NOT_FOUND (same code/HTTP-status the caller would see for an unknown id, so we
    // don't leak the existence of a foreign run's clarification through error code variance).
    return clarificationReadPort
        .findByPublicId(command.clarificationId())
        .map(
            clarification -> {
              if (!clarification.workflowRunId().equals(command.workflowRunId())) {
                throw new DomainException(
                    DomainErrorCode.CLARIFICATION_NOT_FOUND,
                    "Clarification not found on workflow run",
                    Map.of(
                        "clarificationId",
                        command.clarificationId(),
                        "workflowRunId",
                        command.workflowRunId()));
              }
              return clarification.status();
            })
        .orElse(LEGACY_CLARIFICATION_REPLAY_STATUS_UNKNOWN);
  }

  private ClarificationReplayRef parseClarificationReplayRef(String resultRef) {
    int firstSeparator = clarificationReplaySeparatorIndex(resultRef, 0);
    if (firstSeparator <= 0) {
      throw malformedClarificationReplayRef(resultRef);
    }
    int firstSeparatorLength = clarificationReplaySeparatorLength(resultRef, firstSeparator);
    int stateStart = firstSeparator + firstSeparatorLength;
    if (stateStart >= resultRef.length()) {
      throw malformedClarificationReplayRef(resultRef);
    }
    int secondSeparator = clarificationReplaySeparatorIndex(resultRef, stateStart);
    String stateValue =
        secondSeparator < 0
            ? resultRef.substring(stateStart)
            : resultRef.substring(stateStart, secondSeparator);
    if (stateValue.isBlank()) {
      throw malformedClarificationReplayRef(resultRef);
    }
    String clarificationStatus = null;
    if (secondSeparator >= 0) {
      int secondSeparatorLength = clarificationReplaySeparatorLength(resultRef, secondSeparator);
      int statusStart = secondSeparator + secondSeparatorLength;
      if (statusStart >= resultRef.length()) {
        throw malformedClarificationReplayRef(resultRef);
      }
      clarificationStatus = resultRef.substring(statusStart);
      if (!Clarification.ALL_STATUSES.contains(clarificationStatus)) {
        throw malformedClarificationReplayRef(resultRef);
      }
    }
    return new ClarificationReplayRef(
        resultRef.substring(0, firstSeparator),
        WorkflowState.fromValue(stateValue, "idempotency.resultRef"),
        clarificationStatus);
  }

  private int clarificationReplaySeparatorIndex(String resultRef, int fromIndex) {
    int separator = resultRef.indexOf(CLARIFICATION_REPLAY_REF_SEPARATOR, fromIndex);
    int legacySeparator = resultRef.indexOf(LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR, fromIndex);
    if (separator < 0) {
      return legacySeparator;
    }
    if (legacySeparator < 0) {
      return separator;
    }
    // Story 2.13 review P16: a single ref must encode with one separator scheme. A ref carrying
    // both the new U+001F and the legacy '|' is ambiguous — Math.min would silently pick one and
    // misparse. Reject as malformed so callers see a typed error rather than corrupted parsing.
    throw malformedClarificationReplayRef(resultRef);
  }

  private int clarificationReplaySeparatorLength(String resultRef, int separatorIndex) {
    if (resultRef.startsWith(CLARIFICATION_REPLAY_REF_SEPARATOR, separatorIndex)) {
      return CLARIFICATION_REPLAY_REF_SEPARATOR.length();
    }
    if (resultRef.startsWith(LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR, separatorIndex)) {
      return LEGACY_CLARIFICATION_REPLAY_REF_SEPARATOR.length();
    }
    throw malformedClarificationReplayRef(resultRef);
  }

  private DomainException malformedClarificationReplayRef(String resultRef) {
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Malformed clarification replay result reference",
        Map.of("resultRef", resultRef));
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

  private record ClarificationReplayRef(
      String workflowRunId, WorkflowState workflowState, String clarificationStatus) {}
}
