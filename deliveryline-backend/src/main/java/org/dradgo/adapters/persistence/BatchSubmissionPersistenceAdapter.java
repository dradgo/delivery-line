package org.dradgo.adapters.persistence;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.adapters.persistence.entity.BatchSubmissionEntity;
import org.dradgo.adapters.persistence.mapper.BatchSubmissionEntityMapper;
import org.dradgo.adapters.persistence.repository.BatchSubmissionRepository;
import org.dradgo.adapters.persistence.repository.RunnerExecutionRepository;
import org.dradgo.application.workflow.BatchSubmissionResult;
import org.dradgo.application.workflow.spi.BatchSubmissionReadPort;
import org.dradgo.application.workflow.spi.BatchSubmissionWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link BatchSubmissionWritePort} + {@link BatchSubmissionReadPort} (story 3.18).
 *
 * <p>Each method runs in its own transaction ({@code REQUIRED}); because {@code
 * WorkflowBatchSubmissionService.submitBatch} carries no ambient transaction, a {@code REQUIRED}
 * boundary opens a fresh physical transaction per call — keeping the batch-row write independent of
 * the per-ticket submit transactions (best-effort, Decision D-TX). Defense-in-depth maps the {@code
 * uq_batch_submissions_idempotency_key} UNIQUE constraint to {@code IDEMPOTENCY_KEY_CONFLICT} so a
 * reservation-path bug surfaces as a Problem Details response rather than HTTP 500 (mirrors {@code
 * ApprovalWritePersistenceAdapter}).
 */
@Component
public class BatchSubmissionPersistenceAdapter
    implements BatchSubmissionWritePort, BatchSubmissionReadPort {

  private static final Logger log =
      LoggerFactory.getLogger(BatchSubmissionPersistenceAdapter.class);

  private static final String UQ_IDEMPOTENCY_CONSTRAINT = "uq_batch_submissions_idempotency_key";
  private static final String UQ_PUBLIC_ID_CONSTRAINT = "uq_batch_submissions_public_id";
  private static final String POSTGRES_UNIQUE_VIOLATION_SQLSTATE = "23505";

  private final BatchSubmissionRepository batchSubmissionRepository;
  private final BatchSubmissionEntityMapper batchSubmissionEntityMapper;
  private final RunnerExecutionRepository runnerExecutionRepository;

  public BatchSubmissionPersistenceAdapter(
      BatchSubmissionRepository batchSubmissionRepository,
      BatchSubmissionEntityMapper batchSubmissionEntityMapper,
      RunnerExecutionRepository runnerExecutionRepository) {
    this.batchSubmissionRepository = batchSubmissionRepository;
    this.batchSubmissionEntityMapper = batchSubmissionEntityMapper;
    this.runnerExecutionRepository = runnerExecutionRepository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public OffsetDateTime insert(NewBatchSubmission newBatchSubmission) {
    log.info(
        "batch submission write entry batchId={} total={} queuedCount={} rejectedCount={}",
        newBatchSubmission.publicId(),
        newBatchSubmission.total(),
        newBatchSubmission.queuedCount(),
        newBatchSubmission.rejectedCount());

    BatchSubmissionEntity entity = new BatchSubmissionEntity();
    entity.setPublicId(newBatchSubmission.publicId());
    entity.setActorIdentity(newBatchSubmission.actorIdentity());
    entity.setActorType(newBatchSubmission.actorType().value());
    entity.setIdempotencyKey(newBatchSubmission.idempotencyKey());
    entity.setTotal(newBatchSubmission.total());
    entity.setQueuedCount(newBatchSubmission.queuedCount());
    entity.setRejectedCount(newBatchSubmission.rejectedCount());
    entity.setResultJson(
        batchSubmissionEntityMapper.serializeTickets(newBatchSubmission.tickets()));

    BatchSubmissionEntity saved;
    try {
      saved = batchSubmissionRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException violation) {
      throw mapIntegrityViolation(newBatchSubmission.publicId(), violation);
    }
    log.info(
        "batch submission write success batchId={} createdAt={}",
        saved.getPublicId(),
        saved.getCreatedAt());
    return saved.getCreatedAt();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public int stampBatchSubmissionId(String workflowRunPublicId, String batchPublicId) {
    int stamped =
        runnerExecutionRepository.stampBatchSubmissionId(workflowRunPublicId, batchPublicId);
    if (stamped > 0) {
      log.info(
          "batch submission stamp batchId={} workflowRunId={} rowsStamped={}",
          batchPublicId,
          workflowRunPublicId,
          stamped);
    }
    return stamped;
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.Optional<BatchSubmissionResult> findByPublicId(String publicId) {
    return batchSubmissionRepository
        .findByPublicIdAndArchivedAtIsNull(publicId)
        .map(batchSubmissionEntityMapper::toResult);
  }

  private DomainException mapIntegrityViolation(
      String batchPublicId, DataIntegrityViolationException violation) {
    String constraintName = resolveConstraintName(violation);
    if (matchesIdempotencyConstraint(violation, constraintName)) {
      log.warn(
          "batch submission write idempotency-key conflict batchId={} source=db_unique_constraint",
          batchPublicId);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("source", "db_unique_constraint");
      details.put("conflictDetected", true);
      return new DomainException(
          DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
          "Batch submission idempotency key already used",
          details);
    }
    if (constraintName != null && constraintName.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
      log.error(
          "batch submission write public-id collision batchId={} constraint={}",
          batchPublicId,
          constraintName);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("source", "db_unique_constraint");
      details.put("constraintName", constraintName);
      return new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "Batch submission public-id collision", details);
    }
    log.error(
        "batch submission write integrity-violation batchId={} constraint={}",
        batchPublicId,
        constraintName);
    throw violation;
  }

  private static String resolveConstraintName(DataIntegrityViolationException violation) {
    Throwable cursor = violation;
    while (cursor != null) {
      if (cursor instanceof ConstraintViolationException cve) {
        String constraint = cve.getConstraintName();
        if (constraint != null && !constraint.isBlank()) {
          return constraint;
        }
      }
      cursor = cursor.getCause();
    }
    cursor = violation;
    while (cursor != null) {
      String message = cursor.getMessage();
      if (message != null) {
        if (message.contains(UQ_IDEMPOTENCY_CONSTRAINT)) {
          return UQ_IDEMPOTENCY_CONSTRAINT;
        }
        if (message.contains(UQ_PUBLIC_ID_CONSTRAINT)) {
          return UQ_PUBLIC_ID_CONSTRAINT;
        }
      }
      cursor = cursor.getCause();
    }
    return null;
  }

  private static boolean matchesIdempotencyConstraint(
      DataIntegrityViolationException violation, String resolvedConstraintName) {
    if (resolvedConstraintName != null
        && resolvedConstraintName.contains(UQ_IDEMPOTENCY_CONSTRAINT)) {
      return true;
    }
    if (resolvedConstraintName != null) {
      return false;
    }
    Throwable cursor = violation;
    while (cursor != null) {
      if (cursor instanceof SQLException sqlException
          && POSTGRES_UNIQUE_VIOLATION_SQLSTATE.equals(sqlException.getSQLState())) {
        return true;
      }
      cursor = cursor.getCause();
    }
    return false;
  }
}
