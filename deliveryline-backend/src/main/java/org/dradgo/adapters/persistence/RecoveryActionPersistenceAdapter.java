package org.dradgo.adapters.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.RecoveryActionEntity;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.RecoveryActionEntityMapper;
import org.dradgo.adapters.persistence.repository.RecoveryActionRepository;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-adapter implementation of {@link RecoveryActionRecordPort} (story 1.18 Task 2).
 *
 * <p>Translates unique-violations on {@code uq_recovery_actions_idempotency_key} into {@code
 * DomainException(IDEMPOTENCY_KEY_CONFLICT)} mirroring the pattern established by {@link
 * IntegrationLinkPersistenceAdapter#insert(IntegrationLinkPersistenceAdapter.NewIntegrationLink)}.
 *
 * <p>The {@code result_status} state machine ({@code pending → succeeded | failed} terminal) is
 * enforced in {@link #markSucceeded(String)} / {@link #markFailed(String)}; any attempt to flip a
 * terminal row throws {@code DomainException(INTERNAL_ERROR)}.
 */
@Component
public class RecoveryActionPersistenceAdapter implements RecoveryActionRecordPort {

  private static final Logger log = LoggerFactory.getLogger(RecoveryActionPersistenceAdapter.class);

  static final String STATUS_PENDING = "pending";
  static final String STATUS_SUCCEEDED = "succeeded";
  static final String STATUS_FAILED = "failed";
  static final String ACTION_TYPE_RETRY = "retry";
  static final String ACTION_TYPE_TAKEOVER = "takeover";

  private final RecoveryActionRepository recoveryActionRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowEventRepository workflowEventRepository;
  private final RecoveryActionEntityMapper mapper;

  public RecoveryActionPersistenceAdapter(
      RecoveryActionRepository recoveryActionRepository,
      WorkflowRunRepository workflowRunRepository,
      WorkflowEventRepository workflowEventRepository,
      RecoveryActionEntityMapper mapper) {
    this.recoveryActionRepository =
        Objects.requireNonNull(recoveryActionRepository, "recoveryActionRepository");
    this.workflowRunRepository =
        Objects.requireNonNull(workflowRunRepository, "workflowRunRepository");
    this.workflowEventRepository =
        Objects.requireNonNull(workflowEventRepository, "workflowEventRepository");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public RecoveryActionSnapshot insert(RecoveryActionWriteCommand command) {
    Objects.requireNonNull(command, "command");
    PublicIdPrefixes.require(command.workflowRunPublicId(), PublicIdPrefixes.WORKFLOW_RUN);
    requireNonBlank("actionType", command.actionType());
    requireNonBlank("actorIdentity", command.actorIdentity());
    Objects.requireNonNull(command.actorType(), "actorType");
    requireNonBlank("idempotencyKey", command.idempotencyKey());
    requireNonBlank("resultStatus", command.resultStatus());

    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicId(command.workflowRunPublicId())
            .orElseThrow(
                () -> referenceMissing("workflowRunPublicId", command.workflowRunPublicId()));

    WorkflowEventEntity triggeringEvent =
        resolveOptionalEvent(command.triggeringEventPublicId(), "triggeringEventPublicId");
    WorkflowEventEntity resultingEvent =
        resolveOptionalEvent(command.resultingEventPublicId(), "resultingEventPublicId");

    RecoveryActionEntity entity = new RecoveryActionEntity();
    entity.setPublicId(PublicIdPrefixes.RECOVERY_ACTION.next());
    entity.setWorkflowRun(workflowRun);
    entity.setActionType(command.actionType());
    entity.setTriggeringEvent(triggeringEvent);
    entity.setResultingEvent(resultingEvent);
    entity.setActorIdentity(command.actorIdentity());
    entity.setActorType(command.actorType());
    // Story 3.22 (AC3): persist reviewer_role when the caller supplies it (takeover='developer');
    // null for the retry path (story 1.18 convenience constructor).
    entity.setReviewerRole(command.reviewerRole());
    entity.setIdempotencyKey(command.idempotencyKey());
    entity.setResultStatus(command.resultStatus());

    RecoveryActionEntity persisted;
    try {
      persisted = recoveryActionRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException collision) {
      log.warn(
          "recovery_action insert conflict workflowRunId={} actionType={} idempotencyKey={} cause={}",
          command.workflowRunPublicId(),
          command.actionType(),
          command.idempotencyKey(),
          collision.getMostSpecificCause() == null
              ? null
              : collision.getMostSpecificCause().getClass().getSimpleName());
      throw translateUniqueViolation(command, collision);
    }
    log.info(
        "persisting recovery_action publicId={} workflowRunId={} actionType={} resultStatus={}",
        persisted.getPublicId(),
        persisted.getWorkflowRun().getPublicId(),
        persisted.getActionType(),
        persisted.getResultStatus());
    return mapper.toSnapshot(persisted);
  }

  // Read methods are wrapped in a readOnly transaction so the lazy `workflowRun` association on
  // RecoveryActionEntity stays attached to the JPA session through the `mapper::toSnapshot` call
  // (same latent-LazyInit pattern as WorkflowEventPersistenceAdapter +
  // RunnerExecutionPersistenceAdapter — was hidden by mock-based unit tests until the story 1.21
  // Testcontainers retry replay test exercised it).
  @Override
  @Transactional(readOnly = true)
  public Optional<RecoveryActionSnapshot> findByIdempotencyKey(String idempotencyKey) {
    requireNonBlank("idempotencyKey", idempotencyKey);
    return recoveryActionRepository.findByIdempotencyKey(idempotencyKey).map(mapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RecoveryActionSnapshot> findLatestTakeoverForRun(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    return recoveryActionRepository
        .findFirstByWorkflowRunPublicIdAndActionTypeOrderByCreatedAtDescIdDesc(
            workflowRunPublicId, ACTION_TYPE_TAKEOVER)
        .map(mapper::toSnapshot);
  }

  @Override
  public RecoveryActionSnapshot markSucceeded(String idempotencyKey) {
    return flipTerminal(idempotencyKey, STATUS_SUCCEEDED);
  }

  @Override
  public RecoveryActionSnapshot markFailed(String idempotencyKey) {
    return flipTerminal(idempotencyKey, STATUS_FAILED);
  }

  private RecoveryActionSnapshot flipTerminal(String idempotencyKey, String targetStatus) {
    requireNonBlank("idempotencyKey", idempotencyKey);
    RecoveryActionEntity entity =
        recoveryActionRepository
            .findByIdempotencyKey(idempotencyKey)
            .orElseThrow(
                () -> {
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("idempotencyKey", idempotencyKey);
                  details.put("targetStatus", targetStatus);
                  details.put("reason", "recovery_action_record_missing");
                  return new DomainException(
                      DomainErrorCode.INTERNAL_ERROR,
                      "Recovery action not found for idempotencyKey=" + idempotencyKey,
                      details);
                });
    String current = entity.getResultStatus();
    if (!STATUS_PENDING.equals(current)) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("idempotencyKey", idempotencyKey);
      details.put("currentResultStatus", current);
      details.put("targetResultStatus", targetStatus);
      details.put("reason", "result_status_terminal_transition_rejected");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "recovery_actions.result_status is already terminal ("
              + current
              + "); cannot transition to "
              + targetStatus,
          details);
    }
    entity.setResultStatus(targetStatus);
    RecoveryActionEntity persisted = recoveryActionRepository.saveAndFlush(entity);
    log.info(
        "recovery_action result_status flipped publicId={} from={} to={}",
        persisted.getPublicId(),
        current,
        targetStatus);
    return mapper.toSnapshot(persisted);
  }

  private WorkflowEventEntity resolveOptionalEvent(String publicId, String fieldName) {
    if (publicId == null) {
      return null;
    }
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.WORKFLOW_EVENT);
    return workflowEventRepository
        .findByPublicId(publicId)
        .orElseThrow(() -> referenceMissing(fieldName, publicId));
  }

  private static DomainException translateUniqueViolation(
      RecoveryActionWriteCommand command, DataIntegrityViolationException cause) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("workflowRunId", command.workflowRunPublicId());
    details.put("actionType", command.actionType());
    details.put("idempotencyKey", command.idempotencyKey());
    String message =
        cause.getMostSpecificCause() == null ? null : cause.getMostSpecificCause().getMessage();
    if (message != null) {
      if (message.contains("uq_recovery_actions_idempotency_key")) {
        details.put("constraint", "uq_recovery_actions_idempotency_key");
        details.put("reason", "idempotency_key_already_used");
        return new DomainException(
            DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "Idempotency key conflict for recovery action: " + command.idempotencyKey(),
            details);
      }
      if (message.contains("uq_recovery_actions_public_id")) {
        details.put("constraint", "uq_recovery_actions_public_id");
        details.put("reason", "public_id_collision");
      } else {
        details.put("reason", "recovery_action_constraint_violation");
      }
    } else {
      details.put("reason", "recovery_action_constraint_violation");
    }
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR, "recovery_actions constraint violation", details);
  }

  private static DomainException referenceMissing(String fieldName, String value) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(fieldName, value);
    details.put("reason", fieldName + "_not_found");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Referenced row not found for " + fieldName + "=" + value,
        details);
  }

  private static void requireNonBlank(String fieldName, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must be non-blank");
    }
  }
}
