package org.dradgo.adapters.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.ArtifactOperationEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.ArtifactOperationEntityMapper;
import org.dradgo.adapters.persistence.repository.ArtifactOperationRepository;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ArtifactOperationPersistenceAdapter implements ArtifactOperationPort {

  private static final Logger log =
      LoggerFactory.getLogger(ArtifactOperationPersistenceAdapter.class);

  private final ArtifactOperationRepository artifactOperationRepository;
  private final ArtifactRepository artifactRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final ArtifactOperationEntityMapper artifactOperationEntityMapper;

  public ArtifactOperationPersistenceAdapter(
      ArtifactOperationRepository artifactOperationRepository,
      ArtifactRepository artifactRepository,
      WorkflowRunRepository workflowRunRepository,
      ArtifactOperationEntityMapper artifactOperationEntityMapper) {
    this.artifactOperationRepository = artifactOperationRepository;
    this.artifactRepository = artifactRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.artifactOperationEntityMapper = artifactOperationEntityMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ArtifactOperationSnapshot> findReplay(
      String workflowRunId,
      ArtifactType artifactType,
      String idempotencyKey,
      String operationType) {
    return artifactOperationRepository
        .findFirstByWorkflowRunPublicIdAndArtifactTypeAndIdempotencyKeyAndOperationType(
            workflowRunId, artifactType.value(), idempotencyKey, operationType)
        .map(artifactOperationEntityMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ArtifactOperationSnapshot> findRecordedOperation(
      String workflowRunId, ArtifactType artifactType, String idempotencyKey) {
    return artifactOperationRepository
        .findFirstByWorkflowRunPublicIdAndArtifactTypeAndIdempotencyKeyOrderByCreatedAtAsc(
            workflowRunId, artifactType.value(), idempotencyKey)
        .map(artifactOperationEntityMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ArtifactOperationSnapshot> findPendingByArtifactId(String artifactId) {
    List<ArtifactOperationEntity> pending =
        artifactOperationRepository.findByArtifactPublicIdAndStatusOrderByCreatedAtDesc(
            artifactId, ArtifactOperationStatus.PENDING.value());
    if (pending.isEmpty()) {
      return Optional.empty();
    }
    if (pending.size() > 1) {
      // Defense-in-depth: V4's partial unique index "uq_artifact_operations_pending_per_artifact"
      // prevents two pending ops on the same artifact, but if the index is dropped, missed in a
      // hot-fix replay, or this method runs against pre-V4 data, surface the violation as a
      // stable internal error rather than silently picking one row by createdAt tiebreak.
      List<String> publicIds = pending.stream().map(ArtifactOperationEntity::getPublicId).toList();
      log.error(
          "findPendingByArtifactId invariant violated: multiple pending operations exist artifactId={} pendingOperationIds={}",
          artifactId,
          publicIds);
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Multiple pending artifact operations exist for artifact: " + artifactId,
          Map.of(
              "artifactId", artifactId,
              "pendingOperationIds", publicIds));
    }
    return Optional.of(artifactOperationEntityMapper.toSnapshot(pending.get(0)));
  }

  @Override
  public ArtifactOperationSnapshot createPending(
      String operationPublicId,
      String workflowRunId,
      ArtifactType artifactType,
      String artifactId,
      String operationType,
      String idempotencyKey) {
    WorkflowRunEntity workflowRun = requireWorkflowRun(workflowRunId);
    ArtifactEntity artifact = requireArtifact(artifactId);
    ArtifactOperationEntity entity = new ArtifactOperationEntity();
    entity.setPublicId(operationPublicId);
    entity.setWorkflowRun(workflowRun);
    entity.setArtifact(artifact);
    entity.setLinkedEvent(artifact.getLinkedEvent());
    entity.setOperationType(operationType);
    entity.setArtifactType(artifactType);
    entity.setStatus(ArtifactOperationStatus.PENDING);
    entity.setIdempotencyKey(idempotencyKey);
    ArtifactOperationSnapshot persisted =
        artifactOperationEntityMapper.toSnapshot(artifactOperationRepository.saveAndFlush(entity));
    log.info(
        "artifact_operation pending persisted operationId={} workflowRunId={} artifactId={} artifactType={} operationType={} idempotencyKey={}",
        persisted.publicId(),
        workflowRunId,
        artifactId,
        artifactType.value(),
        operationType,
        idempotencyKey);
    return persisted;
  }

  @Override
  public ArtifactOperationSnapshot markComplete(String operationPublicId) {
    ArtifactOperationEntity operation = requireOperation(operationPublicId);
    requireOperationSourceStatus(operation, ArtifactOperationStatus.PENDING, "markComplete");
    operation.setStatus(ArtifactOperationStatus.COMPLETE);
    ArtifactOperationSnapshot persisted =
        artifactOperationEntityMapper.toSnapshot(
            artifactOperationRepository.saveAndFlush(operation));
    log.info(
        "artifact_operation marked complete operationId={} artifactId={}",
        persisted.publicId(),
        persisted.artifactId());
    return persisted;
  }

  @Override
  public ArtifactOperationSnapshot markFailed(
      String operationPublicId, FailureCategory failureCategory, String reason) {
    ArtifactOperationEntity operation = requireOperation(operationPublicId);
    requireOperationSourceStatus(operation, ArtifactOperationStatus.PENDING, "markFailed");
    operation.setStatus(ArtifactOperationStatus.FAILED);
    operation.setFailureCategory(failureCategory);
    operation.setReason(reason);
    ArtifactOperationSnapshot persisted =
        artifactOperationEntityMapper.toSnapshot(
            artifactOperationRepository.saveAndFlush(operation));
    log.warn(
        "artifact_operation marked failed operationId={} artifactId={} failureCategory={} reason={}",
        persisted.publicId(),
        persisted.artifactId(),
        failureCategory.value(),
        reason);
    return persisted;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArtifactOperationSnapshot> findPendingOlderThan(Duration threshold) {
    long seconds = threshold == null ? 0L : Math.max(threshold.getSeconds(), 0L);
    return artifactOperationRepository
        .findByStatusAndCreatedAtOlderThanSeconds(ArtifactOperationStatus.PENDING.value(), seconds)
        .stream()
        .map(artifactOperationEntityMapper::toSnapshot)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasFailedOrFailedOrphanForRun(String workflowRunPublicId) {
    return artifactOperationRepository.existsFailedOrFailedOrphanByWorkflowRunPublicId(
        workflowRunPublicId);
  }

  @Override
  public ArtifactOperationSnapshot markFailedOrphan(String operationPublicId, String reason) {
    ArtifactOperationEntity operation = requireOperation(operationPublicId);
    requireOperationSourceStatus(operation, ArtifactOperationStatus.PENDING, "markFailedOrphan");
    operation.setStatus(ArtifactOperationStatus.FAILED_ORPHAN);
    operation.setFailureCategory(FailureCategory.ORPHAN);
    operation.setReason(reason);
    ArtifactOperationSnapshot persisted =
        artifactOperationEntityMapper.toSnapshot(
            artifactOperationRepository.saveAndFlush(operation));
    log.warn(
        "artifact_operation marked failed_orphan operationId={} artifactId={} reason={}",
        persisted.publicId(),
        persisted.artifactId(),
        reason);
    return persisted;
  }

  private WorkflowRunEntity requireWorkflowRun(String workflowRunId) {
    return workflowRunRepository
        .findByPublicId(workflowRunId)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.RUN_NOT_FOUND,
                    "Workflow run not found: " + workflowRunId,
                    Map.of("runId", workflowRunId)));
  }

  private ArtifactEntity requireArtifact(String artifactId) {
    return artifactRepository
        .findByPublicId(artifactId)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                    "Artifact not found: " + artifactId,
                    Map.of("artifactId", artifactId)));
  }

  private ArtifactOperationEntity requireOperation(String operationPublicId) {
    return artifactOperationRepository
        .findByPublicId(operationPublicId)
        .orElseThrow(
            () ->
                new DomainException(
                    DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
                    "Artifact operation not found: " + operationPublicId,
                    Map.of("operationId", operationPublicId)));
  }

  private void requireOperationSourceStatus(
      ArtifactOperationEntity operation, ArtifactOperationStatus required, String transition) {
    if (operation.getStatus() != required) {
      log.warn(
          "artifact_operation state transition rejected operationId={} currentStatus={} requiredStatus={} transition={}",
          operation.getPublicId(),
          operation.getStatus().value(),
          required.value(),
          transition);
      throw new DomainException(
          DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
          "Artifact operation "
              + transition
              + " requires status "
              + required.value()
              + " but current status is "
              + operation.getStatus().value(),
          Map.of(
              "operationId", operation.getPublicId(),
              "currentStatus", operation.getStatus().value(),
              "requiredStatus", required.value(),
              "transition", transition));
    }
  }
}
