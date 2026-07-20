package org.dradgo.adapters.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.dradgo.adapters.persistence.entity.ArtifactEntity;
import org.dradgo.adapters.persistence.entity.WorkflowEventEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.ArtifactEntityMapper;
import org.dradgo.adapters.persistence.repository.ArtifactRepository;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactDraftRequest;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.ArtifactVersionRequest;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ArtifactRecordPersistenceAdapter implements ArtifactRecordPort {

  private static final Logger log = LoggerFactory.getLogger(ArtifactRecordPersistenceAdapter.class);

  private static final Set<WorkflowState> TERMINAL_RUN_STATES =
      EnumSet.of(WorkflowState.COMPLETED, WorkflowState.FAILED, WorkflowState.RECONCILED);

  @PersistenceContext private EntityManager entityManager;

  private final ArtifactRepository artifactRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final WorkflowEventRepository workflowEventRepository;
  private final ArtifactEntityMapper artifactEntityMapper;

  public ArtifactRecordPersistenceAdapter(
      ArtifactRepository artifactRepository,
      WorkflowRunRepository workflowRunRepository,
      WorkflowEventRepository workflowEventRepository,
      ArtifactEntityMapper artifactEntityMapper) {
    this.artifactRepository = artifactRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.workflowEventRepository = workflowEventRepository;
    this.artifactEntityMapper = artifactEntityMapper;
  }

  @Override
  @Transactional
  public void lockLineageForUpdate(String workflowRunId, String artifactType) {
    // D10: use hashtext() (PostgreSQL's own stable string hash → int4, cast to bigint) so the
    // lock key is consistent across JVMs, JVM versions, and process restarts.
    entityManager
        .createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey)::bigint)")
        .setParameter("lockKey", workflowRunId + ":" + artifactType)
        .getSingleResult();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ArtifactRecordSnapshot> findByPublicId(String artifactId) {
    return artifactRepository.findByPublicId(artifactId).map(artifactEntityMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ArtifactRecordSnapshot> findLatestByWorkflowRunIdAndArtifactType(
      String workflowRunId, String artifactType) {
    return artifactRepository
        .findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
            workflowRunId, artifactType)
        .map(artifactEntityMapper::toSnapshot);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArtifactRecordSnapshot> listByWorkflowRunIdAndArtifactType(
      String workflowRunId, String artifactType) {
    List<ArtifactEntity> rows =
        artifactRepository
            .findByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionAsc(
                workflowRunId, artifactType);
    List<ArtifactRecordSnapshot> snapshots = new ArrayList<>(rows.size());
    for (ArtifactEntity row : rows) {
      snapshots.add(artifactEntityMapper.toSnapshot(row));
    }
    return List.copyOf(snapshots);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> findRunnerExecutionIdForArtifact(String artifactId) {
    String value = artifactRepository.findRunnerExecutionIdForArtifact(artifactId);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> findProducingActorForArtifact(String artifactId) {
    String value = artifactRepository.findProducingActorForArtifact(artifactId);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ArtifactRecordSnapshot> findLatestByParentArtifactId(
      String lineageMemberArtifactId) {
    Optional<ArtifactEntity> member = artifactRepository.findByPublicId(lineageMemberArtifactId);
    if (member.isEmpty()) {
      return Optional.empty();
    }
    return findLatestActiveLineageMemberEntity(member.get()).map(artifactEntityMapper::toSnapshot);
  }

  @Override
  @Transactional
  public ArtifactRecordSnapshot createDraft(ArtifactDraftRequest request) {
    WorkflowRunEntity workflowRun = requireWorkflowRunForUpdate(request.workflowRunId());
    WorkflowEventEntity linkedEvent =
        workflowEventRepository.saveAndFlush(
            newArtifactEvent(
                workflowRun,
                WorkflowEventType.ARTIFACT_DRAFT_CREATED,
                request.actor().actorIdentity(),
                request.actor().actorType(),
                "artifact draft created",
                artifactEventDetails(
                    request.payloadRef(),
                    request.actor().correlationId(),
                    request.operationType(),
                    request.operationPublicId(),
                    request.idempotencyKey(),
                    request.runnerExecutionId(),
                    Map.of("artifactType", request.artifactType().value()))));
    int nextVersion =
        artifactRepository
            .findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
                request.workflowRunId(), request.artifactType().value())
            .map(existing -> existing.getVersion() + 1)
            .orElse(1);
    ArtifactEntity entity = new ArtifactEntity();
    entity.setPublicId(PublicIdPrefixes.ARTIFACT.next());
    entity.setWorkflowRun(workflowRun);
    entity.setArtifactType(request.artifactType());
    entity.setVersion(nextVersion);
    entity.setClassification(request.classification());
    entity.setStatus(ArtifactStatus.PENDING);
    entity.setLinkedEvent(linkedEvent);
    ArtifactRecordSnapshot persisted;
    try {
      persisted = artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException collision) {
      // Concurrent createDraft calls for the same (workflow_run_id, artifact_type, version)
      // collide on uq_artifacts_workflow_run_id_artifact_type_version. The pessimistic lock
      // acquired via requireWorkflowRunForUpdate above serializes within a run, so a collision
      // here indicates an unexpected bypass (e.g., dropped constraint, manual insert). Surface
      // as a retryable ARTIFACT_OPERATION_CONFLICT so callers see a stable typed error.
      log.warn(
          "createDraft conflict on artifact version unique constraint workflowRunId={} artifactType={} attemptedVersion={} cause={}",
          request.workflowRunId(),
          request.artifactType().value(),
          nextVersion,
          collision.getMostSpecificCause().getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
          "Concurrent artifact draft creation conflict for workflowRunId="
              + request.workflowRunId()
              + ", artifactType="
              + request.artifactType().value(),
          Map.of(
              "workflowRunId", request.workflowRunId(),
              "artifactType", request.artifactType().value(),
              "attemptedVersion", nextVersion),
          collision);
    }
    log.info(
        "artifact draft persisted artifactId={} workflowRunId={} artifactType={} version={} status=pending",
        persisted.publicId(),
        request.workflowRunId(),
        request.artifactType().value(),
        persisted.version());
    return persisted;
  }

  @Override
  @Transactional
  public ArtifactRecordSnapshot createNextVersion(ArtifactVersionRequest request) {
    ArtifactEntity requestedMember = requireArtifact(request.lineageMemberArtifactId());
    requireWorkflowRunForUpdate(requestedMember.getWorkflowRun().getPublicId());
    ArtifactEntity latestActiveArtifact =
        artifactRepository
            .findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
                requestedMember.getWorkflowRun().getPublicId(),
                requestedMember.getArtifactType().value())
            .orElse(requestedMember);
    ArtifactEntity lineageHead =
        findLatestActiveLineageMemberEntity(requestedMember).orElse(latestActiveArtifact);
    int nextVersion = latestActiveArtifact.getVersion() + 1;
    // Allocate the new artifact's publicId up front so the event emitted below carries the
    // artifactId / artifactVersion that downstream readers (story 2.8 AC10) need to correlate.
    String nextArtifactPublicId = PublicIdPrefixes.ARTIFACT.next();
    WorkflowEventEntity linkedEvent =
        workflowEventRepository.saveAndFlush(
            newArtifactEvent(
                requestedMember.getWorkflowRun(),
                WorkflowEventType.ARTIFACT_VERSION_CREATED,
                request.actor().actorIdentity(),
                request.actor().actorType(),
                "artifact version created",
                artifactEventDetails(
                    request.payloadRef(),
                    request.actor().correlationId(),
                    request.operationType(),
                    request.operationPublicId(),
                    request.idempotencyKey(),
                    request.runnerExecutionId(),
                    Map.of(
                        "artifactId",
                        nextArtifactPublicId,
                        "artifactVersion",
                        nextVersion,
                        "lineageMemberArtifactId",
                        request.lineageMemberArtifactId(),
                        "parentArtifactId",
                        lineageHead.getPublicId()))));
    ArtifactEntity entity = new ArtifactEntity();
    entity.setPublicId(nextArtifactPublicId);
    entity.setWorkflowRun(requestedMember.getWorkflowRun());
    entity.setArtifactType(requestedMember.getArtifactType());
    entity.setVersion(nextVersion);
    entity.setParentArtifact(lineageHead);
    entity.setClassification(lineageHead.getClassification());
    entity.setStatus(ArtifactStatus.PENDING);
    entity.setLinkedEvent(linkedEvent);
    ArtifactRecordSnapshot persisted;
    try {
      persisted = artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException collision) {
      // Defense-in-depth around uq_artifacts_workflow_run_id_artifact_type_version. The
      // pessimistic lock acquired via requireWorkflowRunForUpdate(...) above serializes
      // concurrent createNextVersion callers within the same workflow run, but a typed
      // conflict surfaces if the invariant is ever bypassed (different code path, dropped
      // constraint, etc.) so callers can retry instead of seeing a raw JDBC exception.
      log.warn(
          "createNextVersion conflict on artifact version unique constraint workflowRunId={} artifactType={} attemptedVersion={} cause={}",
          requestedMember.getWorkflowRun().getPublicId(),
          requestedMember.getArtifactType().value(),
          nextVersion,
          collision.getMostSpecificCause().getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
          "Concurrent artifact version conflict for workflowRunId="
              + requestedMember.getWorkflowRun().getPublicId()
              + ", artifactType="
              + requestedMember.getArtifactType().value(),
          Map.of(
              "workflowRunId", requestedMember.getWorkflowRun().getPublicId(),
              "artifactType", requestedMember.getArtifactType().value(),
              "lineageMemberArtifactId", request.lineageMemberArtifactId(),
              "attemptedVersion", nextVersion),
          collision);
    }
    log.info(
        "artifact next-version persisted artifactId={} parentArtifactId={} workflowRunId={} artifactType={} version={}",
        persisted.publicId(),
        lineageHead.getPublicId(),
        persisted.workflowRunId(),
        persisted.artifactType().value(),
        persisted.version());
    return persisted;
  }

  @Override
  public ArtifactRecordSnapshot markAvailable(
      String artifactId, ArtifactChecksum checksum, String storageRef) {
    ArtifactEntity artifact = requireArtifact(artifactId);
    requireNotArchived(artifact, ArtifactStatus.AVAILABLE);
    if (artifact.getStatus() != ArtifactStatus.PENDING) {
      log.warn(
          "markAvailable rejected: invalid state transition artifactId={} currentStatus={} targetStatus=available",
          artifactId,
          artifact.getStatus().value());
      throw invalidArtifactTransition(artifactId, artifact.getStatus(), ArtifactStatus.AVAILABLE);
    }
    artifact.setChecksumAlgorithm(checksum.algorithm());
    artifact.setChecksumValue(checksum.value());
    artifact.setStorageRef(storageRef);
    artifact.setStatus(ArtifactStatus.AVAILABLE);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
    log.info(
        "artifact transitioned to available artifactId={} version={} checksumAlgorithm={}",
        persisted.publicId(),
        persisted.version(),
        checksum.algorithm());
    return persisted;
  }

  @Override
  public ArtifactRecordSnapshot markFailed(
      String artifactId, FailureCategory failureCategory, String reason) {
    ArtifactEntity artifact = requireArtifact(artifactId);
    requireNotArchived(artifact, ArtifactStatus.FAILED);
    ArtifactStatus current = artifact.getStatus();
    if (current != ArtifactStatus.PENDING && current != ArtifactStatus.LATE_OR_STALE) {
      log.warn(
          "markFailed rejected: invalid state transition artifactId={} currentStatus={} targetStatus=failed",
          artifactId,
          current.value());
      throw invalidArtifactTransition(artifactId, current, ArtifactStatus.FAILED);
    }
    artifact.setStatus(ArtifactStatus.FAILED);
    artifact.setFailureCategory(failureCategory);
    artifact.setFailureReason(reason);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
    log.warn(
        "artifact transitioned to failed artifactId={} version={} failureCategory={} failureReason={}",
        persisted.publicId(),
        persisted.version(),
        failureCategory.value(),
        reason);
    return persisted;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ArtifactRecordSnapshot> findAvailableCreatedBefore(
      Duration minAge,
      int batchLimit,
      java.time.OffsetDateTime afterCreatedAt,
      String afterPublicId) {
    if (batchLimit <= 0) {
      throw new IllegalArgumentException("batchLimit must be positive but was: " + batchLimit);
    }
    long minAgeMinutes = minAge == null ? 0L : Math.max(0L, minAge.toMinutes());
    // Both cursor components must be present to page; a half-set cursor starts from the oldest.
    java.time.OffsetDateTime cursorCreatedAt = afterPublicId == null ? null : afterCreatedAt;
    String cursorPublicId = afterCreatedAt == null ? null : afterPublicId;
    List<ArtifactEntity> rows =
        artifactRepository.findAvailableCreatedBefore(
            minAgeMinutes, batchLimit, cursorCreatedAt, cursorPublicId);
    List<ArtifactRecordSnapshot> snapshots = new ArrayList<>(rows.size());
    for (ArtifactEntity row : rows) {
      snapshots.add(artifactEntityMapper.toSnapshot(row));
    }
    log.debug(
        "findAvailableCreatedBefore minAgeMinutes={} batchLimit={} afterPublicId={} returned={}",
        minAgeMinutes,
        batchLimit,
        cursorPublicId,
        snapshots.size());
    return List.copyOf(snapshots);
  }

  @Override
  public ArtifactRecordSnapshot markLateOrStale(String artifactId) {
    ArtifactEntity artifact = requireArtifact(artifactId);
    requireNotArchived(artifact, ArtifactStatus.LATE_OR_STALE);
    if (artifact.getStatus() != ArtifactStatus.PENDING) {
      log.warn(
          "markLateOrStale rejected: invalid state transition artifactId={} currentStatus={} targetStatus=late_or_stale",
          artifactId,
          artifact.getStatus().value());
      throw invalidArtifactTransition(
          artifactId, artifact.getStatus(), ArtifactStatus.LATE_OR_STALE);
    }
    artifact.setStatus(ArtifactStatus.LATE_OR_STALE);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
    log.warn(
        "artifact transitioned to late_or_stale artifactId={} version={}",
        persisted.publicId(),
        persisted.version());
    return persisted;
  }

  @Override
  public ArtifactRecordSnapshot markCorrupted(String artifactId, String reason) {
    ArtifactEntity artifact = requireArtifact(artifactId);
    requireNotArchived(artifact, ArtifactStatus.CORRUPTED);
    ArtifactStatus current = artifact.getStatus();
    // Story 4.16 — checksum-mismatch drift is only ever detected on an 'available' artifact, so the
    // only legal source is AVAILABLE. Any other status (pending / failed / late_or_stale / already
    // corrupted) is a race or a misdirected repair and must be rejected.
    if (current != ArtifactStatus.AVAILABLE) {
      log.warn(
          "markCorrupted rejected: invalid state transition artifactId={} currentStatus={} targetStatus=corrupted",
          artifactId,
          current.value());
      throw invalidArtifactTransition(artifactId, current, ArtifactStatus.CORRUPTED);
    }
    // Leave failure_category / failure_reason BOTH null (like markLateOrStale): the artifacts
    // table's
    // ck_artifacts_failure_reason_paired CHECK requires the pair to be both-null or both-set, and
    // there is no FailureCategory that fits "corrupted". The corruption cause is fully captured on
    // the artifact.driftRepaired audit event (details.reason), so nothing is lost.
    artifact.setStatus(ArtifactStatus.CORRUPTED);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
    log.warn(
        "artifact transitioned to corrupted artifactId={} version={} reason={}",
        persisted.publicId(),
        persisted.version(),
        reason);
    return persisted;
  }

  @Override
  public ArtifactRecordSnapshot markPayloadUnavailable(String artifactId, String reason) {
    ArtifactEntity artifact = requireArtifact(artifactId);
    requireNotArchived(artifact, ArtifactStatus.FAILED);
    ArtifactStatus current = artifact.getStatus();
    // Story 4.16 (OQ-1) — missing-payload drift is detected on an 'available' artifact; the repair
    // flips it to FAILED (no dedicated 'unavailable' status). markFailed's guard rejects AVAILABLE,
    // so this dedicated path permits ONLY AVAILABLE as the source.
    if (current != ArtifactStatus.AVAILABLE) {
      log.warn(
          "markPayloadUnavailable rejected: invalid state transition artifactId={} currentStatus={} targetStatus=failed",
          artifactId,
          current.value());
      throw invalidArtifactTransition(artifactId, current, ArtifactStatus.FAILED);
    }
    // FAILED requires a paired failure_category/failure_reason
    // (ck_artifacts_failure_reason_paired).
    // ORPHAN is the closest existing category for a payload that is no longer present ("payload
    // never
    // materialized" — the same category reconcileSingleOperation uses for missing payloads).
    artifact.setStatus(ArtifactStatus.FAILED);
    artifact.setFailureCategory(FailureCategory.ORPHAN);
    artifact.setFailureReason(reason);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
    log.warn(
        "artifact payload unavailable — transitioned to failed artifactId={} version={} failureReason={}",
        persisted.publicId(),
        persisted.version(),
        reason);
    return persisted;
  }

  @Override
  @Transactional
  public ArtifactRecordSnapshot reattachToLineage(
      String orphanArtifactId, String chosenParentArtifactId) {
    ArtifactEntity orphan = requireArtifact(orphanArtifactId);
    ArtifactEntity chosenParent = requireArtifact(chosenParentArtifactId);
    requireNotArchived(orphan, orphan.getStatus());
    // Story 4.16a [Review D2] — the chosen parent must not be archived (soft-deleted); re-parenting
    // onto a tombstoned node would leave the orphan an active leaf hanging off a dead parent.
    requireNotArchived(chosenParent, chosenParent.getStatus());
    // Story 4.16a [Review D1] — reattach is an ORPHAN-repair action. Refuse a target that already
    // carries a parent lineage: re-parenting it would SILENTLY overwrite a healthy parent link
    // (violates NFR19 "no silent overwrite"; AC3 preserves history unchanged).
    if (orphan.getParentArtifact() != null) {
      log.warn(
          "reattachToLineage rejected: target already has a parent orphanArtifactId={}"
              + " existingParentArtifactId={}",
          orphanArtifactId,
          orphan.getParentArtifact().getPublicId());
      throw new DomainException(
          DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
          "Reattach applies only to orphaned (parentless) artifacts; target already has a parent"
              + " lineage",
          Map.of(
              "artifactId",
              orphanArtifactId,
              "existingParentArtifactId",
              orphan.getParentArtifact().getPublicId(),
              "reason",
              "target_already_parented"));
    }
    // Serialize against concurrent lineage writers (createDraft/createNextVersion hold the same
    // advisory key via ArtifactOperationService). No terminal-run opinion here — lineage recovery
    // legitimately runs on a failed run, so this uses the advisory lock rather than the run-row
    // lock
    // (whose terminal check would over-block recovery).
    lockLineageForUpdate(orphan.getWorkflowRun().getPublicId(), orphan.getArtifactType().value());
    // Same (run, type) — a reattach across lineages is a mismatch (story 4.19's code).
    if (!orphan.getWorkflowRun().getPublicId().equals(chosenParent.getWorkflowRun().getPublicId())
        || orphan.getArtifactType() != chosenParent.getArtifactType()) {
      log.warn(
          "reattachToLineage rejected: cross-lineage orphanArtifactId={} chosenParentArtifactId={}"
              + " orphanRun={} orphanType={} parentRun={} parentType={}",
          orphanArtifactId,
          chosenParentArtifactId,
          orphan.getWorkflowRun().getPublicId(),
          orphan.getArtifactType().value(),
          chosenParent.getWorkflowRun().getPublicId(),
          chosenParent.getArtifactType().value());
      throw lineageMismatch(orphanArtifactId, chosenParentArtifactId);
    }
    // Cycle guard: the chosen parent must not be the orphan itself nor a descendant of it (walking
    // parent_artifact_id up from the chosen parent must never reach the orphan).
    requireNoLineageCycle(orphan, chosenParent);
    // Story 4.16a [Review D1] — the chosen parent must be a LEAF (no non-archived child). Attaching
    // onto a parent that already has an active child would produce two active leaves for the same
    // (run, artifact_type), recreating the exact ambiguity reattach exists to resolve.
    if (artifactRepository.hasActiveChild(chosenParent.getPublicId())) {
      log.warn(
          "reattachToLineage rejected: chosen parent is not a leaf (already has an active child)"
              + " orphanArtifactId={} chosenParentArtifactId={}",
          orphanArtifactId,
          chosenParentArtifactId);
      throw new DomainException(
          DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
          "Chosen parent already has an active child; reattach requires a leaf parent",
          Map.of(
              "artifactId", orphanArtifactId,
              "chosenParentArtifactId", chosenParentArtifactId,
              "reason", "chosen_parent_not_leaf"));
    }
    orphan.setParentArtifact(chosenParent);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(orphan));
    log.info(
        "artifact reattached to lineage orphanArtifactId={} chosenParentArtifactId={}"
            + " workflowRunId={} artifactType={} version={}",
        persisted.publicId(),
        chosenParentArtifactId,
        persisted.workflowRunId(),
        persisted.artifactType().value(),
        persisted.version());
    return persisted;
  }

  @Override
  @Transactional
  public ArtifactRecordSnapshot markLineageTerminated(String artifactId, String reason) {
    ArtifactEntity artifact = requireArtifact(artifactId);
    requireNotArchived(artifact, ArtifactStatus.FAILED);
    lockLineageForUpdate(
        artifact.getWorkflowRun().getPublicId(), artifact.getArtifactType().value());
    ArtifactStatus current = artifact.getStatus();
    // Story 4.16a (AC4 / OQ-2) — flip an ambiguous lineage head to the terminal FAILED status so
    // hasActiveLineage excludes it and replay cannot revive it. The ambiguous head may be
    // available/pending/late_or_stale/corrupted; the ONLY rejected source is already-FAILED (a
    // repeat terminate on a settled lineage). This is a strictly broader source set than markFailed
    // (pending/late_or_stale only).
    if (current == ArtifactStatus.FAILED) {
      log.warn(
          "markLineageTerminated rejected: already terminal artifactId={} currentStatus={}",
          artifactId,
          current.value());
      throw invalidArtifactTransition(artifactId, current, ArtifactStatus.FAILED);
    }
    // FAILED requires a paired failure_category/failure_reason
    // (ck_artifacts_failure_reason_paired).
    // ORPHAN is the closest category for an abandoned/ambiguous lineage (same choice as
    // markPayloadUnavailable); the abandonment cause is fully captured on the
    // artifact.lineageReconciled audit event the service appends.
    String terminationReason = reason == null || reason.isBlank() ? "lineage_terminated" : reason;
    artifact.setStatus(ArtifactStatus.FAILED);
    artifact.setFailureCategory(FailureCategory.ORPHAN);
    artifact.setFailureReason(terminationReason);
    ArtifactRecordSnapshot persisted =
        artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
    log.warn(
        "artifact lineage terminated — transitioned to failed artifactId={} version={}"
            + " priorStatus={} failureReason={}",
        persisted.publicId(),
        persisted.version(),
        current.value(),
        terminationReason);
    return persisted;
  }

  @Override
  @Transactional
  public ArtifactRecordSnapshot createLineageRecoveryFork(
      String sourceLineageMemberArtifactId, ActorContext actor, String reason) {
    ArtifactEntity source = requireArtifact(sourceLineageMemberArtifactId);
    // Story 4.16a [Review D2] — do not root a fresh lineage off an archived (soft-deleted) source.
    requireNotArchived(source, source.getStatus());
    String artifactTypeValue = source.getArtifactType().value();
    // Story 4.16a [Review D4] — serialize the version-counter read+insert on the ADVISORY lineage
    // lock (keyed on run+type — the same key createDraft/createNextVersion hold via
    // ArtifactOperationService) rather than the run-row lock, whose terminal-run check would
    // over-block fork recovery. A fork legitimately runs on a failed/terminal run — mirroring
    // reattach/terminate — so lineage recovery is not refused on exactly the runs that need it.
    lockLineageForUpdate(source.getWorkflowRun().getPublicId(), artifactTypeValue);
    WorkflowRunEntity workflowRun = source.getWorkflowRun();
    int nextVersion =
        artifactRepository
            .findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
                workflowRun.getPublicId(), artifactTypeValue)
            .map(existing -> existing.getVersion() + 1)
            .orElse(1);
    String forkPublicId = PublicIdPrefixes.ARTIFACT.next();
    // Every artifact row requires a linked_event_id — emit an artifact.draftCreated event for the
    // new head (the recovery rationale + source ride the separate artifact.lineageReconciled audit
    // event the service appends). Marked with lineageRecovery=true + the source reference.
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put("artifactType", artifactTypeValue);
    eventDetails.put("artifactId", forkPublicId);
    eventDetails.put("artifactVersion", nextVersion);
    eventDetails.put("lineageRecovery", true);
    eventDetails.put("lineageSourceArtifactId", sourceLineageMemberArtifactId);
    if (actor.correlationId() != null && !actor.correlationId().isBlank()) {
      eventDetails.put("correlationId", actor.correlationId());
    }
    WorkflowEventEntity linkedEvent =
        workflowEventRepository.saveAndFlush(
            newArtifactEvent(
                workflowRun,
                WorkflowEventType.ARTIFACT_DRAFT_CREATED,
                actor.actorIdentity(),
                actor.actorType(),
                reason == null || reason.isBlank() ? "lineage recovery fork created" : reason,
                eventDetails));
    ArtifactEntity entity = new ArtifactEntity();
    entity.setPublicId(forkPublicId);
    entity.setWorkflowRun(workflowRun);
    entity.setArtifactType(source.getArtifactType());
    entity.setVersion(nextVersion);
    // parent_artifact_id stays NULL — this is a fresh, disjoint lineage root.
    entity.setClassification(source.getClassification());
    entity.setStatus(ArtifactStatus.PENDING);
    entity.setLineageRecovery(true);
    entity.setLinkedEvent(linkedEvent);
    ArtifactRecordSnapshot persisted;
    try {
      persisted = artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(entity));
    } catch (DataIntegrityViolationException collision) {
      // Defense-in-depth around uq_artifacts_workflow_run_id_artifact_type_version. The run-row
      // lock
      // acquired above serializes concurrent lineage writers, but a typed conflict surfaces if the
      // invariant is ever bypassed so callers see a stable error instead of a raw JDBC exception.
      log.warn(
          "createLineageRecoveryFork conflict on artifact version unique constraint workflowRunId={}"
              + " artifactType={} attemptedVersion={} cause={}",
          workflowRun.getPublicId(),
          artifactTypeValue,
          nextVersion,
          collision.getMostSpecificCause().getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
          "Concurrent artifact lineage-recovery fork conflict for workflowRunId="
              + workflowRun.getPublicId()
              + ", artifactType="
              + artifactTypeValue,
          Map.of(
              "workflowRunId", workflowRun.getPublicId(),
              "artifactType", artifactTypeValue,
              "sourceLineageMemberArtifactId", sourceLineageMemberArtifactId,
              "attemptedVersion", nextVersion),
          collision);
    }
    log.info(
        "artifact lineage-recovery fork persisted artifactId={} sourceLineageMemberArtifactId={}"
            + " workflowRunId={} artifactType={} version={} lineageRecovery=true status=pending",
        persisted.publicId(),
        sourceLineageMemberArtifactId,
        persisted.workflowRunId(),
        persisted.artifactType().value(),
        persisted.version());
    return persisted;
  }

  // Story 4.16a (AC3) — reject a reattach whose chosen parent is the orphan itself or a descendant
  // of it (walking parent_artifact_id up from the chosen parent must never reach the orphan). The
  // visited-set bounds the walk defensively even though ck_artifacts_no_self_parent + this guard
  // prevent a cycle from ever being introduced.
  private void requireNoLineageCycle(ArtifactEntity orphan, ArtifactEntity chosenParent) {
    String orphanPublicId = orphan.getPublicId();
    Set<String> visited = new HashSet<>();
    ArtifactEntity cursor = chosenParent;
    while (cursor != null) {
      String cursorId = cursor.getPublicId();
      if (cursorId.equals(orphanPublicId)) {
        log.warn(
            "reattachToLineage rejected: lineage cycle — chosen parent is the orphan or a"
                + " descendant orphanArtifactId={} chosenParentArtifactId={}",
            orphanPublicId,
            chosenParent.getPublicId());
        throw lineageCycle(orphanPublicId, chosenParent.getPublicId());
      }
      if (!visited.add(cursorId)) {
        break;
      }
      cursor = cursor.getParentArtifact();
    }
  }

  private DomainException lineageMismatch(String orphanArtifactId, String chosenParentArtifactId) {
    return new DomainException(
        DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH,
        "Reattach target and chosen parent are not in the same lineage (workflow run + artifact"
            + " type)",
        Map.of(
            "artifactId", orphanArtifactId,
            "chosenParentArtifactId", chosenParentArtifactId,
            "reason", "cross_lineage_reattach"));
  }

  private DomainException lineageCycle(String orphanArtifactId, String chosenParentArtifactId) {
    return new DomainException(
        DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
        "Reattach would introduce a lineage cycle: chosen parent "
            + chosenParentArtifactId
            + " is the orphan or a descendant of it",
        Map.of(
            "artifactId", orphanArtifactId,
            "chosenParentArtifactId", chosenParentArtifactId,
            "reason", "lineage_cycle"));
  }

  private WorkflowRunEntity requireWorkflowRunForUpdate(String workflowRunId) {
    WorkflowRunEntity run =
        workflowRunRepository
            .findByPublicIdForUpdate(workflowRunId)
            .orElseThrow(
                () ->
                    new DomainException(
                        DomainErrorCode.RUN_NOT_FOUND,
                        "Workflow run not found: " + workflowRunId,
                        Map.of("runId", workflowRunId)));
    // Re-validate terminal state INSIDE the pessimistic lock. The pre-lock check in
    // ArtifactOperationService is a TOCTOU window: a run that became terminal between the
    // pre-lock read and the lock acquisition would otherwise slip through. Reading
    // currentState from the already-fetched entity avoids a second query.
    WorkflowState currentState = run.getCurrentState();
    if (TERMINAL_RUN_STATES.contains(currentState)) {
      log.warn(
          "createDraft/createNextVersion rejected: workflow run is terminal (post-lock recheck) workflowRunId={} workflowRunState={}",
          workflowRunId,
          currentState.value());
      throw new DomainException(
          DomainErrorCode.WORKFLOW_RUN_TERMINAL,
          "Workflow run is in a terminal state and cannot accept artifact operations: "
              + currentState.value(),
          Map.of("workflowRunId", workflowRunId, "workflowRunState", currentState.value()));
    }
    return run;
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

  private Optional<ArtifactEntity> findLatestActiveLineageMemberEntity(
      ArtifactEntity memberEntity) {
    // Story 1.12c (AC1+AC2): leaf resolution + parent-chain check are pushed down into a single
    // PostgreSQL recursive CTE — no unbounded sibling load, no N+1 lazy parent walk. Cycle
    // defense survives via the CTE depth bound (10000), replacing the previous JVM-side
    // visited-set guard. See ArtifactRepository#findActiveLineageLeaf javadoc.
    String workflowRunPublicId = memberEntity.getWorkflowRun().getPublicId();
    String artifactTypeValue = memberEntity.getArtifactType().value();
    String lineageMemberPublicId = memberEntity.getPublicId();
    log.debug(
        "findActiveLineageLeaf entry workflowRunId={} artifactType={} lineageMemberArtifactId={}",
        workflowRunPublicId,
        artifactTypeValue,
        lineageMemberPublicId);
    Optional<ArtifactEntity> leaf =
        artifactRepository.findActiveLineageLeaf(
            workflowRunPublicId, artifactTypeValue, lineageMemberPublicId);
    log.debug(
        "findActiveLineageLeaf exit workflowRunId={} artifactType={} lineageMemberArtifactId={} leafArtifactId={}",
        workflowRunPublicId,
        artifactTypeValue,
        lineageMemberPublicId,
        leaf.map(ArtifactEntity::getPublicId).orElse(null));
    return leaf;
  }

  private WorkflowEventEntity newArtifactEvent(
      WorkflowRunEntity workflowRun,
      WorkflowEventType eventType,
      String actorIdentity,
      ActorType actorType,
      String reason,
      Map<String, Object> details) {
    WorkflowEventEntity entity = new WorkflowEventEntity();
    entity.setPublicId(PublicIdPrefixes.WORKFLOW_EVENT.next());
    entity.setWorkflowRun(workflowRun);
    entity.setEventType(eventType);
    entity.setActorIdentity(actorIdentity);
    entity.setActorType(actorType);
    entity.setReason(reason);
    entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    entity.setDetails(details);
    return entity;
  }

  private Map<String, Object> artifactEventDetails(
      String payloadRef,
      String correlationId,
      String operationType,
      String operationPublicId,
      String idempotencyKey,
      String runnerExecutionId,
      Map<String, Object> extraDetails) {
    Map<String, Object> details = new LinkedHashMap<>(extraDetails);
    details.put("payloadRef", payloadRef);
    if (correlationId != null && !correlationId.isBlank()) {
      details.put("correlationId", correlationId);
    }
    if (operationType != null) {
      details.put("operationType", operationType);
    }
    if (operationPublicId != null) {
      details.put("operationPublicId", operationPublicId);
    }
    if (idempotencyKey != null) {
      details.put("idempotencyKey", idempotencyKey);
    }
    if (runnerExecutionId != null) {
      details.put("runnerExecutionId", runnerExecutionId);
    }
    return details;
  }

  private DomainException invalidArtifactTransition(
      String artifactId, ArtifactStatus currentStatus, ArtifactStatus targetStatus) {
    return new DomainException(
        DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
        "Artifact state transition not allowed: "
            + currentStatus.value()
            + " -> "
            + targetStatus.value(),
        Map.of(
            "artifactId", artifactId,
            "currentStatus", currentStatus.value(),
            "targetStatus", targetStatus.value()));
  }

  private void requireNotArchived(ArtifactEntity artifact, ArtifactStatus targetStatus) {
    if (artifact.getArchivedAt() != null) {
      throw new DomainException(
          DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
          "Artifact is archived and cannot be transitioned to " + targetStatus.value(),
          Map.of(
              "artifactId", artifact.getPublicId(),
              "currentStatus", artifact.getStatus().value(),
              "targetStatus", targetStatus.value(),
              "archivedAt", artifact.getArchivedAt().toString()));
    }
  }
}
