package org.dradgo.application.workflow;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.commands.SubmitBatchCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.spi.BatchSubmissionReadPort;
import org.dradgo.application.workflow.spi.BatchSubmissionWritePort;
import org.dradgo.application.workflow.spi.BatchSubmissionWritePort.NewBatchSubmission;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.18 — batch submission orchestrator.
 *
 * <p>Composes the story-1.15 single-submit flow ({@link WorkflowCommandService#submit}) once per
 * ticket, with <strong>best-effort semantics</strong>: a single ticket's rejection does not abort
 * the rest of the batch. The queue this submits onto is already active (stories 3.17a/3.17b) — this
 * service never touches the queue, the worker pool, or the dispatch path; it fans a list of tickets
 * through the existing single-submit method and aggregates the outcomes.
 *
 * <p><strong>THE central trap (Reconciliation 4 / Decision D-TX):</strong> {@code submitBatch} is
 * deliberately NOT {@code @Transactional}. If it were, every per-ticket {@code submit()} (itself
 * {@code @Transactional(REQUIRED)}) would JOIN the one batch transaction, so a single ticket's
 * rollback-marked {@code DomainException} would poison the whole batch — best-effort destroyed.
 * With no ambient transaction, each per-ticket {@code submit()} opens its own physical transaction;
 * one ticket's failure rolls back only that ticket. The batch-level idempotency reserve/complete
 * and the {@code batch_submissions} write each run in their own independent transactions.
 */
@Service
public class WorkflowBatchSubmissionService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowBatchSubmissionService.class);

  /**
   * Field separator for the canonical batch fingerprint + per-ticket idempotency key derivation. A
   * NUL byte cannot appear in a ticket reference (validated to a printable {@code @Size(max=128)}
   * string) so concatenation is unambiguous.
   */
  private static final String FIELD_SEPARATOR = "\u0000";

  private final WorkflowCommandService workflowCommandService;
  private final IdempotencyService idempotencyService;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final Validator validator;
  private final BatchSubmissionWritePort batchSubmissionWritePort;
  private final BatchSubmissionReadPort batchSubmissionReadPort;
  private final UuidV7Generator uuidV7Generator;
  private final TransactionTemplate independentTransactionTemplate;
  private final int maxTickets;

  public WorkflowBatchSubmissionService(
      WorkflowCommandService workflowCommandService,
      IdempotencyService idempotencyService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      Validator validator,
      BatchSubmissionWritePort batchSubmissionWritePort,
      BatchSubmissionReadPort batchSubmissionReadPort,
      UuidV7Generator uuidV7Generator,
      PlatformTransactionManager transactionManager,
      @Value("${deliveryline.workflow.batch-max-tickets:100}") int maxTickets) {
    this.workflowCommandService = workflowCommandService;
    this.idempotencyService = idempotencyService;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
    this.validator = validator;
    this.batchSubmissionWritePort = batchSubmissionWritePort;
    this.batchSubmissionReadPort = batchSubmissionReadPort;
    this.uuidV7Generator = uuidV7Generator;
    this.independentTransactionTemplate = requiresNewTemplate(transactionManager);
    this.maxTickets = maxTickets < 1 ? 1 : maxTickets;
  }

  public BatchSubmissionResult submitBatch(SubmitBatchCommand command) {
    // Dedupe to an order-preserving distinct list at the entry point so the whole pipeline treats
    // a batch as a SET of tickets. The fingerprint already distincts (Decision D-FP); without this
    // the submit loop would iterate duplicates with colliding per-ticket idempotency keys (the 2nd
    // submit() REPLAYs the 1st, same runId, double-counted as queued), and two requests differing
    // only by duplicates would hash identically and silently REPLAY instead of conflicting.
    SubmitBatchCommand normalized = normalizeTickets(command);
    validateCommand(normalized);
    String idempotencyKey = idempotencyKeyValidator.requireValid(normalized.idempotencyKey());
    String fingerprint = fingerprintFor(normalized);

    IdempotencyService.ReservationOutcome reservation =
        independentTransactionTemplate.execute(
            ignored ->
                idempotencyService.checkAndReserve(
                    idempotencyKey,
                    SubmitBatchCommand.class.getSimpleName(),
                    normalized.actorIdentity(),
                    fingerprint));
    if (reservation != null
        && reservation.decision() == IdempotencyService.ReservationDecision.REPLAY) {
      return replay(reservation.resultRef(), idempotencyKey);
    }

    try {
      BatchSubmissionResult result = process(normalized, idempotencyKey);
      independentTransactionTemplate.executeWithoutResult(
          ignored ->
              idempotencyService.complete(
                  idempotencyKey, result.batchId(), IdempotencyRecordStatus.COMPLETED));
      return result;
    } catch (RuntimeException error) {
      completeFailed(idempotencyKey, error);
      throw error;
    }
  }

  private BatchSubmissionResult process(SubmitBatchCommand command, String idempotencyKey) {
    String batchPublicId = PublicIdPrefixes.BATCH_SUBMISSION.next();
    String batchCorrelationId =
        normalizeOptional(command.correlationId()) == null
            ? uuidV7Generator.generate()
            : command.correlationId().trim();
    String priorCorrelation = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, batchCorrelationId);
    try {
      List<String> tickets = command.linearTicketReferences();
      log.info(
          "batch submit start batchId={} total={} actorIdentity={} idempotencyKey={}",
          batchPublicId,
          tickets.size(),
          MdcKeys.sanitizeForLog(command.actorIdentity()),
          MdcKeys.sanitizeForLog(idempotencyKey));

      List<TicketBatchResult> outcomes = new ArrayList<>(tickets.size());
      boolean queueExhausted = false;
      for (int index = 0; index < tickets.size(); index++) {
        String ticketRef = tickets.get(index);
        if (queueExhausted) {
          // AC7: once the queue is full we stop attempting further submits and mark every
          // remaining ticket rejected with RUNNER_QUEUE_FULL.
          outcomes.add(
              TicketBatchResult.rejected(
                  ticketRef,
                  DomainErrorCode.RUNNER_QUEUE_FULL.value(),
                  "Runner queue full; batch truncated before this ticket"));
          continue;
        }
        try {
          SubmitWorkflowResult submitResult =
              workflowCommandService.submit(
                  new SubmitWorkflowCommand(
                      command.actorIdentity(),
                      command.actorType(),
                      deriveTicketKey(idempotencyKey, ticketRef),
                      childCorrelationId(batchCorrelationId, ticketRef),
                      ticketRef));
          outcomes.add(TicketBatchResult.queued(ticketRef, submitResult.workflowRunId()));
          stampBatchSubmissionId(submitResult.workflowRunId(), batchPublicId);
          log.info(
              "batch submit ticket queued batchId={} ticketRef={} workflowRunId={}",
              batchPublicId,
              MdcKeys.sanitizeForLog(ticketRef),
              submitResult.workflowRunId());
        } catch (DomainException rejection) {
          outcomes.add(
              TicketBatchResult.rejected(ticketRef, codeValue(rejection), rejection.getMessage()));
          if (rejection.errorCode() == DomainErrorCode.RUNNER_QUEUE_FULL) {
            queueExhausted = true;
            log.warn(
                "batch submit queue-full truncation batchId={} ticketRef={} remainingTickets={}",
                batchPublicId,
                MdcKeys.sanitizeForLog(ticketRef),
                tickets.size() - index - 1);
          } else {
            log.info(
                "batch submit ticket rejected batchId={} ticketRef={} rejectionCode={}",
                batchPublicId,
                MdcKeys.sanitizeForLog(ticketRef),
                codeValue(rejection));
          }
        }
      }

      int queuedCount = (int) outcomes.stream().filter(TicketBatchResult::isQueued).count();
      int rejectedCount = outcomes.size() - queuedCount;
      OffsetDateTime submittedAt =
          batchSubmissionWritePort.insert(
              new NewBatchSubmission(
                  batchPublicId,
                  idempotencyKey,
                  command.actorIdentity(),
                  command.actorType(),
                  outcomes.size(),
                  queuedCount,
                  rejectedCount,
                  List.copyOf(outcomes)));
      log.info(
          "batch submit finish batchId={} total={} queuedCount={} rejectedCount={}",
          batchPublicId,
          outcomes.size(),
          queuedCount,
          rejectedCount);
      return new BatchSubmissionResult(
          batchPublicId,
          submittedAt,
          command.actorIdentity(),
          outcomes.size(),
          queuedCount,
          rejectedCount,
          List.copyOf(outcomes));
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelation);
    }
  }

  private void stampBatchSubmissionId(String workflowRunId, String batchPublicId) {
    // Best-effort trace link (AC4 / story 3.19). A stamp failure must never flip a successfully
    // queued ticket into a rejection, so swallow + log rather than propagate.
    try {
      batchSubmissionWritePort.stampBatchSubmissionId(workflowRunId, batchPublicId);
    } catch (RuntimeException stampError) {
      log.warn(
          "batch submit stamp failed batchId={} workflowRunId={} cause={}",
          batchPublicId,
          workflowRunId,
          stampError.getClass().getSimpleName());
    }
  }

  private BatchSubmissionResult replay(String resultRef, String idempotencyKey) {
    log.warn(
        "batch submit idempotent replay idempotencyKey={} batchId={}",
        MdcKeys.sanitizeForLog(idempotencyKey),
        resultRef);
    return batchSubmissionReadPort
        .findByPublicId(resultRef)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.INTERNAL_ERROR,
                    "Idempotent batch replay could not locate the prior batch submission",
                    Map.of("batchId", resultRef)));
  }

  private void completeFailed(String idempotencyKey, RuntimeException original) {
    try {
      independentTransactionTemplate.executeWithoutResult(
          ignored ->
              idempotencyService.complete(idempotencyKey, null, IdempotencyRecordStatus.FAILED));
    } catch (RuntimeException completionError) {
      original.addSuppressed(completionError);
    }
    log.error(
        "batch submit failed idempotencyKey={} cause={}",
        MdcKeys.sanitizeForLog(idempotencyKey),
        original.getClass().getSimpleName());
  }

  /**
   * Per-ticket idempotency key derived deterministically from the batch key + ticket ref (Decision
   * D-IDEMPKEY). The literal {@code {batchKey}:{ticketRef}} form the story sketches would be
   * rejected by {@link IdempotencyKeyValidator} (the {@code :} is outside the opaque-key charset
   * and the combined length can exceed 128), so we hash to a 64-char lowercase hex digest that
   * satisfies the validator's {@code [A-Za-z0-9-]{16,128}} rule while staying deterministic — so a
   * re-run that was interrupted before the batch-level complete stays per-ticket idempotent.
   */
  private String deriveTicketKey(String batchKey, String ticketRef) {
    MessageDigest digest = newDigest();
    digest.update(batchKey.getBytes(StandardCharsets.UTF_8));
    digest.update(FIELD_SEPARATOR.getBytes(StandardCharsets.UTF_8));
    digest.update(ticketRef.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private String childCorrelationId(String batchCorrelationId, String ticketRef) {
    return batchCorrelationId + "/" + ticketRef;
  }

  /**
   * Canonical batch fingerprint (Decision D-FP): SHA-256 over the SORTED, DISTINCT ticket list plus
   * the actor identity + actor type. Order-insensitivity matches "same tickets, same batch".
   * Mirrors the {@code WorkflowCommandFingerprintFactory} digest/append idiom but is intentionally
   * NOT routed through the sealed factory (Reconciliation 3 — {@code SubmitBatchCommand} is not a
   * {@code WorkflowCommand}).
   */
  String fingerprintFor(SubmitBatchCommand command) {
    MessageDigest digest = newDigest();
    Set<String> sortedDistinct = new TreeSet<>(command.linearTicketReferences());
    for (String ticketRef : sortedDistinct) {
      append(digest, ticketRef);
    }
    append(digest, command.actorIdentity());
    append(digest, command.actorType().value());
    return HexFormat.of().formatHex(digest.digest());
  }

  private void validateCommand(SubmitBatchCommand command) {
    // Exclude idempotencyKey violations from the payload check: the key is the authority of
    // IdempotencyKeyValidator.requireValid (a missing key must surface as MISSING_IDEMPOTENCY_KEY,
    // not INVALID_COMMAND_PAYLOAD — matching the submit-workflow sibling's validateForExecution).
    List<ConstraintViolation<SubmitBatchCommand>> payloadViolations =
        validator.validate(command).stream()
            .filter(violation -> !"idempotencyKey".equals(violation.getPropertyPath().toString()))
            .toList();
    if (!payloadViolations.isEmpty()) {
      throw invalidPayload(payloadViolations);
    }
    if (command.linearTicketReferences().size() > maxTickets) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("submitted", command.linearTicketReferences().size());
      details.put("maxTickets", maxTickets);
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Batch exceeds the configured maximum of " + maxTickets + " tickets",
          details);
    }
  }

  private DomainException invalidPayload(List<ConstraintViolation<SubmitBatchCommand>> violations) {
    List<Map<String, Object>> fieldErrors = new ArrayList<>();
    violations.stream()
        .sorted(
            Comparator.comparing(
                (ConstraintViolation<SubmitBatchCommand> violation) ->
                    violation.getPropertyPath().toString()))
        .forEach(
            violation -> {
              Map<String, Object> fieldError = new LinkedHashMap<>();
              fieldError.put("field", violation.getPropertyPath().toString());
              fieldError.put("message", violation.getMessage());
              fieldErrors.add(fieldError);
            });
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("commandType", SubmitBatchCommand.class.getSimpleName());
    details.put("fieldErrors", fieldErrors);
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Invalid batch submission payload", details);
  }

  private static String codeValue(DomainException exception) {
    return exception.errorCode() == null ? null : exception.errorCode().value();
  }

  private MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available in JDK 21", exception);
    }
  }

  private void append(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  /**
   * Returns a command whose ticket list is reduced to its order-preserving distinct elements. A
   * null list is passed through unchanged so {@link #validateCommand} surfaces the
   * {@code @NotEmpty} violation rather than this method NPE-ing. When there are no duplicates the
   * original command is returned to avoid rebuilding the record.
   */
  private SubmitBatchCommand normalizeTickets(SubmitBatchCommand command) {
    List<String> refs = command.linearTicketReferences();
    if (refs == null) {
      return command;
    }
    List<String> distinct = refs.stream().distinct().toList();
    if (distinct.size() == refs.size()) {
      return command;
    }
    return new SubmitBatchCommand(
        distinct,
        command.actorIdentity(),
        command.actorType(),
        command.idempotencyKey(),
        command.correlationId());
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
