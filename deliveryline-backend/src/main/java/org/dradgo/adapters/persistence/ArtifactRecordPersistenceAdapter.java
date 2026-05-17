package org.dradgo.adapters.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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

	private static final Set<WorkflowState> TERMINAL_RUN_STATES = EnumSet.of(
		WorkflowState.COMPLETED,
		WorkflowState.FAILED,
		WorkflowState.RECONCILED);

	@PersistenceContext
	private EntityManager entityManager;

	private final ArtifactRepository artifactRepository;
	private final WorkflowRunRepository workflowRunRepository;
	private final WorkflowEventRepository workflowEventRepository;
	private final ArtifactEntityMapper artifactEntityMapper;

	public ArtifactRecordPersistenceAdapter(
		ArtifactRepository artifactRepository,
		WorkflowRunRepository workflowRunRepository,
		WorkflowEventRepository workflowEventRepository,
		ArtifactEntityMapper artifactEntityMapper
	) {
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
		entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey)::bigint)")
			.setParameter("lockKey", workflowRunId + ":" + artifactType)
			.getSingleResult();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ArtifactRecordSnapshot> findByPublicId(String artifactId) {
		return artifactRepository.findByPublicId(artifactId)
			.map(artifactEntityMapper::toSnapshot);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ArtifactRecordSnapshot> findLatestByWorkflowRunIdAndArtifactType(String workflowRunId, String artifactType) {
		return artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(workflowRunId, artifactType)
			.map(artifactEntityMapper::toSnapshot);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ArtifactRecordSnapshot> findLatestByParentArtifactId(String lineageMemberArtifactId) {
		Optional<ArtifactEntity> member = artifactRepository.findByPublicId(lineageMemberArtifactId);
		if (member.isEmpty()) {
			return Optional.empty();
		}
		return findLatestActiveLineageMemberEntity(member.get())
			.map(artifactEntityMapper::toSnapshot);
	}

	@Override
	@Transactional
	public ArtifactRecordSnapshot createDraft(ArtifactDraftRequest request) {
		WorkflowRunEntity workflowRun = requireWorkflowRunForUpdate(request.workflowRunId());
		WorkflowEventEntity linkedEvent = workflowEventRepository.saveAndFlush(newArtifactEvent(
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
		int nextVersion = artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
			request.workflowRunId(),
			request.artifactType().value())
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
			log.warn("createDraft conflict on artifact version unique constraint workflowRunId={} artifactType={} attemptedVersion={} cause={}",
				request.workflowRunId(), request.artifactType().value(), nextVersion,
				collision.getMostSpecificCause().getClass().getSimpleName());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
				"Concurrent artifact draft creation conflict for workflowRunId=" + request.workflowRunId()
					+ ", artifactType=" + request.artifactType().value(),
				Map.of(
					"workflowRunId", request.workflowRunId(),
					"artifactType", request.artifactType().value(),
					"attemptedVersion", nextVersion),
				collision);
		}
		log.info("artifact draft persisted artifactId={} workflowRunId={} artifactType={} version={} status=pending",
			persisted.publicId(), request.workflowRunId(), request.artifactType().value(), persisted.version());
		return persisted;
	}

	@Override
	@Transactional
	public ArtifactRecordSnapshot createNextVersion(ArtifactVersionRequest request) {
		ArtifactEntity requestedMember = requireArtifact(request.lineageMemberArtifactId());
		requireWorkflowRunForUpdate(requestedMember.getWorkflowRun().getPublicId());
		ArtifactEntity latestActiveArtifact = artifactRepository.findFirstByWorkflowRunPublicIdAndArtifactTypeAndArchivedAtIsNullOrderByVersionDesc(
			requestedMember.getWorkflowRun().getPublicId(),
			requestedMember.getArtifactType().value())
			.orElse(requestedMember);
		ArtifactEntity lineageHead = findLatestActiveLineageMemberEntity(requestedMember)
			.orElse(latestActiveArtifact);
		int nextVersion = latestActiveArtifact.getVersion() + 1;
		WorkflowEventEntity linkedEvent = workflowEventRepository.saveAndFlush(newArtifactEvent(
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
					"lineageMemberArtifactId", request.lineageMemberArtifactId(),
					"parentArtifactId", lineageHead.getPublicId()))));
		ArtifactEntity entity = new ArtifactEntity();
		entity.setPublicId(PublicIdPrefixes.ARTIFACT.next());
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
			log.warn("createNextVersion conflict on artifact version unique constraint workflowRunId={} artifactType={} attemptedVersion={} cause={}",
				requestedMember.getWorkflowRun().getPublicId(),
				requestedMember.getArtifactType().value(),
				nextVersion,
				collision.getMostSpecificCause().getClass().getSimpleName());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
				"Concurrent artifact version conflict for workflowRunId="
					+ requestedMember.getWorkflowRun().getPublicId() + ", artifactType="
					+ requestedMember.getArtifactType().value(),
				Map.of(
					"workflowRunId", requestedMember.getWorkflowRun().getPublicId(),
					"artifactType", requestedMember.getArtifactType().value(),
					"lineageMemberArtifactId", request.lineageMemberArtifactId(),
					"attemptedVersion", nextVersion),
				collision);
		}
		log.info("artifact next-version persisted artifactId={} parentArtifactId={} workflowRunId={} artifactType={} version={}",
			persisted.publicId(), lineageHead.getPublicId(), persisted.workflowRunId(), persisted.artifactType().value(), persisted.version());
		return persisted;
	}

	@Override
	public ArtifactRecordSnapshot markAvailable(String artifactId, ArtifactChecksum checksum, String storageRef) {
		ArtifactEntity artifact = requireArtifact(artifactId);
		requireNotArchived(artifact, ArtifactStatus.AVAILABLE);
		if (artifact.getStatus() != ArtifactStatus.PENDING) {
			log.warn("markAvailable rejected: invalid state transition artifactId={} currentStatus={} targetStatus=available",
				artifactId, artifact.getStatus().value());
			throw invalidArtifactTransition(artifactId, artifact.getStatus(), ArtifactStatus.AVAILABLE);
		}
		artifact.setChecksumAlgorithm(checksum.algorithm());
		artifact.setChecksumValue(checksum.value());
		artifact.setStorageRef(storageRef);
		artifact.setStatus(ArtifactStatus.AVAILABLE);
		ArtifactRecordSnapshot persisted = artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
		log.info("artifact transitioned to available artifactId={} version={} checksumAlgorithm={}",
			persisted.publicId(), persisted.version(), checksum.algorithm());
		return persisted;
	}

	@Override
	public ArtifactRecordSnapshot markFailed(String artifactId, FailureCategory failureCategory, String reason) {
		ArtifactEntity artifact = requireArtifact(artifactId);
		requireNotArchived(artifact, ArtifactStatus.FAILED);
		ArtifactStatus current = artifact.getStatus();
		if (current != ArtifactStatus.PENDING && current != ArtifactStatus.LATE_OR_STALE) {
			log.warn("markFailed rejected: invalid state transition artifactId={} currentStatus={} targetStatus=failed",
				artifactId, current.value());
			throw invalidArtifactTransition(artifactId, current, ArtifactStatus.FAILED);
		}
		artifact.setStatus(ArtifactStatus.FAILED);
		artifact.setFailureCategory(failureCategory);
		artifact.setFailureReason(reason);
		ArtifactRecordSnapshot persisted = artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
		log.warn("artifact transitioned to failed artifactId={} version={} failureCategory={} failureReason={}",
			persisted.publicId(), persisted.version(), failureCategory.value(), reason);
		return persisted;
	}

	@Override
	public ArtifactRecordSnapshot markLateOrStale(String artifactId) {
		ArtifactEntity artifact = requireArtifact(artifactId);
		requireNotArchived(artifact, ArtifactStatus.LATE_OR_STALE);
		if (artifact.getStatus() != ArtifactStatus.PENDING) {
			log.warn("markLateOrStale rejected: invalid state transition artifactId={} currentStatus={} targetStatus=late_or_stale",
				artifactId, artifact.getStatus().value());
			throw invalidArtifactTransition(artifactId, artifact.getStatus(), ArtifactStatus.LATE_OR_STALE);
		}
		artifact.setStatus(ArtifactStatus.LATE_OR_STALE);
		ArtifactRecordSnapshot persisted = artifactEntityMapper.toSnapshot(artifactRepository.saveAndFlush(artifact));
		log.warn("artifact transitioned to late_or_stale artifactId={} version={}",
			persisted.publicId(), persisted.version());
		return persisted;
	}

	private WorkflowRunEntity requireWorkflowRunForUpdate(String workflowRunId) {
		WorkflowRunEntity run = workflowRunRepository.findByPublicIdForUpdate(workflowRunId)
			.orElseThrow(() -> new DomainException(
				DomainErrorCode.RUN_NOT_FOUND,
				"Workflow run not found: " + workflowRunId,
				Map.of("runId", workflowRunId)));
		// Re-validate terminal state INSIDE the pessimistic lock. The pre-lock check in
		// ArtifactOperationService is a TOCTOU window: a run that became terminal between the
		// pre-lock read and the lock acquisition would otherwise slip through. Reading
		// currentState from the already-fetched entity avoids a second query.
		WorkflowState currentState = run.getCurrentState();
		if (TERMINAL_RUN_STATES.contains(currentState)) {
			log.warn("createDraft/createNextVersion rejected: workflow run is terminal (post-lock recheck) workflowRunId={} workflowRunState={}",
				workflowRunId, currentState.value());
			throw new DomainException(
				DomainErrorCode.WORKFLOW_RUN_TERMINAL,
				"Workflow run is in a terminal state and cannot accept artifact operations: " + currentState.value(),
				Map.of(
					"workflowRunId", workflowRunId,
					"workflowRunState", currentState.value()));
		}
		return run;
	}

	private ArtifactEntity requireArtifact(String artifactId) {
		return artifactRepository.findByPublicId(artifactId)
			.orElseThrow(() -> new DomainException(
				DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
				"Artifact not found: " + artifactId,
				Map.of("artifactId", artifactId)));
	}

	private Optional<ArtifactEntity> findLatestActiveLineageMemberEntity(ArtifactEntity memberEntity) {
		// Story 1.12c (AC1+AC2): leaf resolution + parent-chain check are pushed down into a single
		// PostgreSQL recursive CTE — no unbounded sibling load, no N+1 lazy parent walk. Cycle
		// defense survives via the CTE depth bound (10000), replacing the previous JVM-side
		// visited-set guard. See ArtifactRepository#findActiveLineageLeaf javadoc.
		String workflowRunPublicId = memberEntity.getWorkflowRun().getPublicId();
		String artifactTypeValue = memberEntity.getArtifactType().value();
		String lineageMemberPublicId = memberEntity.getPublicId();
		log.debug("findActiveLineageLeaf entry workflowRunId={} artifactType={} lineageMemberArtifactId={}",
			workflowRunPublicId, artifactTypeValue, lineageMemberPublicId);
		Optional<ArtifactEntity> leaf = artifactRepository.findActiveLineageLeaf(
			workflowRunPublicId, artifactTypeValue, lineageMemberPublicId);
		log.debug("findActiveLineageLeaf exit workflowRunId={} artifactType={} lineageMemberArtifactId={} leafArtifactId={}",
			workflowRunPublicId, artifactTypeValue, lineageMemberPublicId,
			leaf.map(ArtifactEntity::getPublicId).orElse(null));
		return leaf;
	}

	private WorkflowEventEntity newArtifactEvent(
		WorkflowRunEntity workflowRun,
		WorkflowEventType eventType,
		String actorIdentity,
		ActorType actorType,
		String reason,
		Map<String, Object> details
	) {
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
		Map<String, Object> extraDetails
	) {
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

	private DomainException invalidArtifactTransition(String artifactId, ArtifactStatus currentStatus, ArtifactStatus targetStatus) {
		return new DomainException(
			DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION,
			"Artifact state transition not allowed: " + currentStatus.value() + " -> " + targetStatus.value(),
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
