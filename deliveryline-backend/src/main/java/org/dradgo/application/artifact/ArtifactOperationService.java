package org.dradgo.application.artifact;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.artifact.spi.ArtifactRunnerExecutionPort;
import org.dradgo.application.artifact.spi.ArtifactWorkflowRunStatePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ArtifactOperationService {

	private static final Logger log = LoggerFactory.getLogger(ArtifactOperationService.class);

	private static final EnumSet<WorkflowState> TERMINAL_RUN_STATES = EnumSet.of(
		WorkflowState.COMPLETED,
		WorkflowState.FAILED,
		WorkflowState.RECONCILED);

	private static final int MAX_FAILURE_REASON_LENGTH = 4096;

	// Constraint name from V3 migration: uq_artifact_operations_idem_key_op_type_workflow_run
	private static final String IDEMPOTENCY_CONSTRAINT = "uq_artifact_operations_idem_key_op_type_workflow_run";

	private final ArtifactRecordPort artifactRecordPort;
	private final ArtifactOperationPort artifactOperationPort;
	private final ArtifactPayloadStore artifactPayloadStore;
	private final ArtifactEventPort artifactEventPort;
	private final ArtifactRunnerExecutionPort artifactRunnerExecutionPort;
	private final ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort;
	private final Clock clock;
	private final Counter idempotencyReplayCounter;
	private TransactionTemplate operationTemplate;
	private TransactionTemplate raceReplayTemplate;

	@Autowired
	public ArtifactOperationService(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort,
		ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort,
		MeterRegistry meterRegistry
	) {
		this(
			artifactRecordPort,
			artifactOperationPort,
			artifactPayloadStore,
			artifactEventPort,
			artifactRunnerExecutionPort,
			artifactWorkflowRunStatePort,
			Clock.systemUTC(),
			meterRegistry);
	}

	// For tests that inject a runStatePort but do not need clock/meter control.
	ArtifactOperationService(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort,
		ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort
	) {
		this(
			artifactRecordPort,
			artifactOperationPort,
			artifactPayloadStore,
			artifactEventPort,
			artifactRunnerExecutionPort,
			artifactWorkflowRunStatePort,
			Clock.systemUTC(),
			new SimpleMeterRegistry());
	}

	// For tests that need clock control.
	ArtifactOperationService(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort,
		ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort,
		Clock clock
	) {
		this(
			artifactRecordPort,
			artifactOperationPort,
			artifactPayloadStore,
			artifactEventPort,
			artifactRunnerExecutionPort,
			artifactWorkflowRunStatePort,
			clock,
			new SimpleMeterRegistry());
	}

	private ArtifactOperationService(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort,
		ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort,
		Clock clock,
		MeterRegistry meterRegistry
	) {
		this.artifactRecordPort = artifactRecordPort;
		this.artifactOperationPort = artifactOperationPort;
		this.artifactPayloadStore = artifactPayloadStore;
		this.artifactEventPort = artifactEventPort;
		this.artifactRunnerExecutionPort = artifactRunnerExecutionPort;
		this.artifactWorkflowRunStatePort = artifactWorkflowRunStatePort;
		this.clock = clock;
		this.idempotencyReplayCounter = Counter
			.builder("deliveryline.artifact.operation.idempotency_replay")
			.description("Number of artifact operation idempotency replays")
			.register(meterRegistry);
	}

	@Autowired
	void setTransactionManager(PlatformTransactionManager transactionManager) {
		TransactionTemplate required = new TransactionTemplate(transactionManager);
		required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		this.operationTemplate = required;

		TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
		requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		requiresNew.setReadOnly(true);
		this.raceReplayTemplate = requiresNew;
	}

	void setRaceReplayTemplate(TransactionTemplate raceReplayTemplate) {
		this.raceReplayTemplate = raceReplayTemplate;
	}

	/**
	 * Builds a service whose workflow-run-state guard is intentionally absent.
	 *
	 * <p>Use only in unit tests that do not exercise the {@code WORKFLOW_RUN_TERMINAL}
	 * precondition or the {@code ARTIFACT_WORKFLOW_RUN_NOT_FOUND} guard. Tests that touch
	 * run-state behavior must wire a real or stubbed {@link ArtifactWorkflowRunStatePort}
	 * via the 6-arg constructor instead.
	 */
	public static ArtifactOperationService withoutWorkflowRunStateGuard(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort
	) {
		// Return EXECUTING (non-terminal) so the run-state guard passes without hitting
		// ARTIFACT_WORKFLOW_RUN_NOT_FOUND on empty state (D2).
		return new ArtifactOperationService(
			artifactRecordPort,
			artifactOperationPort,
			artifactPayloadStore,
			artifactEventPort,
			artifactRunnerExecutionPort,
			runId -> Optional.of(WorkflowState.EXECUTING),
			Clock.systemUTC());
	}

	@Transactional
	public ArtifactRecordSnapshot createDraft(
		String workflowRunId,
		ArtifactType artifactType,
		String payloadRef,
		ActorContext actor
	) {
		requireActor(actor);
		// P31: construct and validate the request object BEFORE logging "start" so that
		// validation failures from the compact ctor (e.g. blank payloadRef) don't produce
		// a dangling "start" log with no corresponding "success" or "error" entry.
		ArtifactDraftRequest request = new ArtifactDraftRequest(
			workflowRunId,
			artifactType,
			payloadRef,
			artifactType.defaultClassification(),
			actor,
			null,
			null,
			null,
			null);
		log.info("createDraft start workflowRunId={} artifactType={} actorIdentity={} actorType={} correlationId={}",
			workflowRunId, artifactType.value(), actor.actorIdentity(), actor.actorType(), actor.correlationId());
		ArtifactRecordSnapshot artifact = artifactRecordPort.createDraft(request);
		log.info("createDraft success workflowRunId={} artifactType={} artifactId={} version={}",
			workflowRunId, artifactType.value(), artifact.publicId(), artifact.version());
		return artifact;
	}

	public RecordArtifactOperationResult recordOperation(RecordArtifactOperationCommand operation) {
		String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, operation.workflowRunId());
		try {
			ActorContext actor = new ActorContext(operation.actorIdentity(), operation.actorType(), operation.correlationId());
			String operationTypeValue = operation.operationType().value();
			log.info("recordOperation start workflowRunId={} artifactType={} operationType={} idempotencyKey={} actorIdentity={} correlationId={} runnerExecutionId={}",
				operation.workflowRunId(), operation.artifactType().value(), operationTypeValue,
				operation.idempotencyKey(), operation.actorIdentity(), operation.correlationId(), operation.runnerExecutionId());

			Optional<RecordArtifactOperationResult> replayed = replayIfPresent(operation, operationTypeValue);
			if (replayed.isPresent()) {
				return finalizeReplay(operation, operationTypeValue, replayed.get());
			}

			try {
				return executeRecordOperation(operation, operationTypeValue, actor);
			} catch (DataIntegrityViolationException duplicate) {
				return handleRecordOperationConstraintCollision(operation, operationTypeValue, duplicate);
			}
		} finally {
			MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
		}
	}

	@Transactional
	public ArtifactAvailabilityResult markAvailable(
		String artifactId,
		ArtifactChecksum checksum,
		String storageRef,
		ActorContext actor
	) {
		requireActor(actor);
		validateStorageRef(storageRef);
		validateChecksum(checksum);
		String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifactId);
		try {
		log.info("markAvailable start artifactId={} storageRef={} checksumAlgorithm={} actorIdentity={} correlationId={}",
			artifactId, storageRef, checksum.algorithm(), actor.actorIdentity(), actor.correlationId());

		Optional<ArtifactOperationSnapshot> pendingOp = artifactOperationPort.findPendingByArtifactId(artifactId);
		if (pendingOp.isEmpty()) {
			// D9: hybrid retry — look up current artifact state instead of throwing immediately.
			ArtifactRecordSnapshot currentArtifact = artifactRecordPort.findByPublicId(artifactId)
				.orElseThrow(() -> new DomainException(
					DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
					"Artifact not found: " + artifactId,
					Map.of("artifactId", artifactId)));
			if (currentArtifact.status() == ArtifactStatus.AVAILABLE) {
				log.info("markAvailable idempotent: artifact already AVAILABLE artifactId={}", artifactId);
				idempotencyReplayCounter.increment();
				return new ArtifactAvailabilityResult(currentArtifact, null);
			}
			log.warn("markAvailable rejected: no pending operation and artifact not AVAILABLE artifactId={} artifactStatus={}",
				artifactId, currentArtifact.status().value());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_OPERATION_INTENT_CONFLICT,
				"No pending operation found for artifact and artifact is not in an available state: "
					+ currentArtifact.status().value(),
				Map.of(
					"artifactId", artifactId,
					"artifactStatus", currentArtifact.status().value()));
		}

		// P3: when the artifact record already has a storageRef pre-stored (future: set during
		// recordOperation's in-tx write), prefer that over the caller-supplied value to prevent a
		// caller from pointing the checksum verification at a file they control. For freshly
		// PENDING artifacts the persisted storageRef is null, so the caller-supplied value (already
		// path-validated by validateStorageRef / P21) is used. The path-validation guard (P21)
		// provides defense-in-depth while the pre-storage path is deferred.
		verifyPayloadMatchesChecksum(artifactId, storageRef, checksum);

		ArtifactRecordSnapshot artifact;
		try {
			artifact = artifactRecordPort.markAvailable(artifactId, checksum, storageRef);
		} catch (DomainException stateError) {
			if (stateError.errorCode() == DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION) {
				// P11: close the pending operation row so it does not become a zombie.
				// Do NOT suppress the original state-transition error — the caller must know
				// the transition was rejected.
				log.warn("markAvailable rejected: invalid artifact state transition, closing pending operation artifactId={} operationId={}",
					artifactId, pendingOp.get().publicId());
				try {
					artifactOperationPort.markFailed(
						pendingOp.get().publicId(),
						FailureCategory.ORPHAN,
						"markAvailable state transition rejected: " + stateError.getMessage());
				} catch (Exception closeError) {
					log.error("markAvailable: failed to close orphaned operation row after state transition error artifactId={} operationId={} closeError={}",
						artifactId, pendingOp.get().publicId(), closeError.toString());
				}
			}
			throw stateError;
		}

		// P8: defense-in-depth — assert the artifact reached AVAILABLE before emitting the event.
		if (artifact.status() != ArtifactStatus.AVAILABLE) {
			throw new DomainException(
				DomainErrorCode.INTERNAL_ERROR,
				"markAvailable did not transition artifact to AVAILABLE state: " + artifact.status().value(),
				Map.of(
					"artifactId", artifactId,
					"unexpectedStatus", artifact.status().value()));
		}

		ArtifactOperationSnapshot operation = artifactOperationPort.markComplete(pendingOp.get().publicId());
		artifactEventPort.append(new ArtifactEventRecord(
			artifact.workflowRunId(),
			WorkflowEventType.ARTIFACT_AVAILABLE,
			actor.actorIdentity(),
			actor.actorType(),
			null,
			null,
			OffsetDateTime.now(clock),
			Map.of(
				"artifactId", artifact.publicId(),
				"operationId", operation.publicId(),
				"checksumAlgorithm", checksum.algorithm(),
				"storageRef", storageRef)));
		log.info("markAvailable success artifactId={} operationId={} workflowRunId={} version={}",
			artifact.publicId(), operation.publicId(), artifact.workflowRunId(), artifact.version());
		return new ArtifactAvailabilityResult(artifact, operation);
		} finally {
			MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
		}
	}

	@Transactional
	public ArtifactFailureResult markFailed(
		String artifactId,
		FailureCategory failureCategory,
		String reason,
		ActorContext actor
	) {
		requireActor(actor);
		validateFailureReason(reason);
		String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifactId);
		try {
		log.info("markFailed start artifactId={} failureCategory={} actorIdentity={} correlationId={}",
			artifactId, failureCategory.value(), actor.actorIdentity(), actor.correlationId());

		Optional<ArtifactOperationSnapshot> pendingOp = artifactOperationPort.findPendingByArtifactId(artifactId);
		if (pendingOp.isEmpty()) {
			// D9: hybrid retry — look up current artifact state instead of throwing immediately.
			ArtifactRecordSnapshot currentArtifact = artifactRecordPort.findByPublicId(artifactId)
				.orElseThrow(() -> new DomainException(
					DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
					"Artifact not found: " + artifactId,
					Map.of("artifactId", artifactId)));
			if (currentArtifact.status() == ArtifactStatus.FAILED) {
				log.info("markFailed idempotent: artifact already FAILED artifactId={}", artifactId);
				idempotencyReplayCounter.increment();
				return new ArtifactFailureResult(currentArtifact, null);
			}
			log.warn("markFailed rejected: no pending operation and artifact not FAILED artifactId={} artifactStatus={}",
				artifactId, currentArtifact.status().value());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_OPERATION_INTENT_CONFLICT,
				"No pending operation found for artifact and artifact is not in a failed state: "
					+ currentArtifact.status().value(),
				Map.of(
					"artifactId", artifactId,
					"artifactStatus", currentArtifact.status().value()));
		}

		ArtifactRecordSnapshot artifact = artifactRecordPort.markFailed(artifactId, failureCategory, reason);
		ArtifactOperationSnapshot operation = artifactOperationPort.markFailed(pendingOp.get().publicId(), failureCategory, reason);
		artifactEventPort.append(new ArtifactEventRecord(
			artifact.workflowRunId(),
			WorkflowEventType.ARTIFACT_FAILED,
			actor.actorIdentity(),
			actor.actorType(),
			reason,
			failureCategory,
			OffsetDateTime.now(clock),
			Map.of(
				"artifactId", artifact.publicId(),
				"operationId", operation.publicId(),
				"failureCategory", failureCategory.value(),
				"failureReason", reason)));
		log.warn("markFailed applied artifactId={} operationId={} workflowRunId={} failureCategory={} failureReason={}",
			artifact.publicId(), operation.publicId(), artifact.workflowRunId(), failureCategory.value(), reason);
		return new ArtifactFailureResult(artifact, operation);
		} finally {
			MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
		}
	}

	@Transactional
	public ArtifactRecordSnapshot newVersion(String parentArtifactId, String payloadRef, ActorContext actor) {
		requireActor(actor);
		log.info("newVersion start parentArtifactId={} actorIdentity={} correlationId={}",
			parentArtifactId, actor.actorIdentity(), actor.correlationId());
		ArtifactRecordSnapshot artifact = artifactRecordPort.createNextVersion(new ArtifactVersionRequest(
			parentArtifactId,
			payloadRef,
			actor,
			null,
			null,
			null,
			null));
		log.info("newVersion success parentArtifactId={} artifactId={} version={}",
			parentArtifactId, artifact.publicId(), artifact.version());
		return artifact;
	}

	private Optional<RecordArtifactOperationResult> replayIfPresent(
		RecordArtifactOperationCommand operation,
		String operationTypeValue
	) {
		Optional<ArtifactOperationSnapshot> replay = artifactOperationPort.findReplay(
			operation.workflowRunId(),
			operation.artifactType(),
			operation.idempotencyKey(),
			operationTypeValue);
		if (replay.isEmpty()) {
			return Optional.empty();
		}
		ArtifactOperationSnapshot priorOperation = replay.get();
		ArtifactRecordSnapshot priorArtifact = artifactRecordPort.findByPublicId(priorOperation.artifactId())
			.orElseThrow(() -> {
				log.warn("recordOperation replay rejected: artifact record missing for replayed operation operationId={} artifactId={}",
					priorOperation.publicId(), priorOperation.artifactId());
				return new DomainException(
					DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
					"Artifact record missing for replayed operation: " + priorOperation.artifactId(),
					Map.of(
						"artifactId", priorOperation.artifactId(),
						"operationId", priorOperation.publicId()));
			});
		if (priorOperation.status() == ArtifactOperationStatus.FAILED) {
			ArtifactFailureResult failure = new ArtifactFailureResult(priorArtifact, priorOperation);
			return Optional.of(new RecordArtifactOperationResult(priorArtifact, priorOperation, failure));
		}
		return Optional.of(new RecordArtifactOperationResult(priorArtifact, priorOperation));
	}

	private RecordArtifactOperationResult finalizeReplay(
		RecordArtifactOperationCommand operation,
		String operationTypeValue,
		RecordArtifactOperationResult result
	) {
		idempotencyReplayCounter.increment();
		if (result.isFailure()) {
			ArtifactFailureResult failure = result.failure();
			log.warn("recordOperation replay returned failed prior operation workflowRunId={} idempotencyKey={} priorOperationId={} failureCategory={}",
				operation.workflowRunId(), operation.idempotencyKey(),
				failure.operation().publicId(),
				failure.operation().failureCategory() != null ? failure.operation().failureCategory().value() : "none");
			throw new DomainException(
				DomainErrorCode.ARTIFACT_OPERATION_INTENT_CONFLICT,
				"Idempotency key was already used for a failed artifact operation",
				Map.of(
					"idempotencyKey", operation.idempotencyKey(),
					"priorOperationId", failure.operation().publicId(),
					"failureCategory", failure.operation().failureCategory() != null
						? failure.operation().failureCategory().value() : "none",
					"artifactId", failure.artifact().publicId()));
		}
		log.warn("recordOperation replay hit workflowRunId={} idempotencyKey={} operationType={} priorOperationId={} priorOperationStatus={} priorArtifactId={}",
			operation.workflowRunId(), operation.idempotencyKey(), operationTypeValue,
			result.operation().publicId(), result.operation().status().value(), result.artifact().publicId());
		return result;
	}

	private RecordArtifactOperationResult executeRecordOperation(
		RecordArtifactOperationCommand operation,
		String operationTypeValue,
		ActorContext actor
	) {
		if (operationTemplate == null) {
			return doRecordOperationInTransaction(operation, operationTypeValue, actor);
		}
		return operationTemplate.execute(status -> doRecordOperationInTransaction(operation, operationTypeValue, actor));
	}

	private RecordArtifactOperationResult doRecordOperationInTransaction(
		RecordArtifactOperationCommand operation,
		String operationTypeValue,
		ActorContext actor
	) {
		requireWorkflowRunNonTerminal(operation.workflowRunId());

		// D10: acquire advisory transaction lock scoped to (workflowRunId, artifactType) BEFORE
		// reading the lineage head. This serializes concurrent recordOperation calls for the same
		// artifact family within a run, preventing the TOCTOU window between the lineage read and
		// the artifact/operation insert.
		artifactRecordPort.lockLineageForUpdate(operation.workflowRunId(), operation.artifactType().value());

		Optional<ArtifactRecordSnapshot> latestArtifact = artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
			operation.workflowRunId(),
			operation.artifactType().value());
		String operationPublicId = PublicIdPrefixes.ARTIFACT_OPERATION.next();
		String priorOpMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_OPERATION_ID, operationPublicId);
		try {
			ArtifactRecordSnapshot artifact = createOrAdvanceArtifact(operation, operationTypeValue, operationPublicId, actor, latestArtifact);
			String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifact.publicId());
			try {
				if (operation.runnerExecutionId() != null && artifactRunnerExecutionPort.isTimedOut(operation.runnerExecutionId())) {
					log.warn("recordOperation flagging artifact as late_or_stale workflowRunId={} artifactId={} runnerExecutionId={}",
						operation.workflowRunId(), artifact.publicId(), operation.runnerExecutionId());
					artifact = artifactRecordPort.markLateOrStale(artifact.publicId());
				}

				ArtifactOperationSnapshot pending = artifactOperationPort.createPending(
					operationPublicId,
					operation.workflowRunId(),
					operation.artifactType(),
					artifact.publicId(),
					operationTypeValue,
					operation.idempotencyKey());
				if (operation.payloadContent() != null) {
					artifactPayloadStore.write(
						operation.workflowRunId(),
						artifact.publicId(),
						artifact.version(),
						operation.payloadRef(),
						operation.payloadContent());
				}
				log.info("recordOperation success workflowRunId={} artifactId={} operationId={} operationType={} idempotencyKey={}",
					operation.workflowRunId(), artifact.publicId(), pending.publicId(), operationTypeValue, operation.idempotencyKey());
				return new RecordArtifactOperationResult(artifact, pending);
			} finally {
				MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
			}
		} finally {
			MdcKeys.endScope(MdcKeys.ARTIFACT_OPERATION_ID, priorOpMdc);
		}
	}

	private RecordArtifactOperationResult handleRecordOperationConstraintCollision(
		RecordArtifactOperationCommand operation,
		String operationTypeValue,
		DataIntegrityViolationException duplicate
	) {
		String causeMessage = duplicate.getMostSpecificCause().getMessage();
		if (causeMessage == null || !causeMessage.contains(IDEMPOTENCY_CONSTRAINT)) {
			log.warn("recordOperation unrelated constraint violation workflowRunId={} artifactType={} operationType={} idempotencyKey={} cause={}",
				operation.workflowRunId(), operation.artifactType().value(), operationTypeValue,
				operation.idempotencyKey(), duplicate.getMostSpecificCause().getClass().getSimpleName());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
				"Artifact operation constraint violation",
				Map.of(
					"workflowRunId", operation.workflowRunId(),
					"artifactType", operation.artifactType().value(),
					"operationType", operationTypeValue,
					"idempotencyKey", operation.idempotencyKey()),
				duplicate);
		}

		Optional<RecordArtifactOperationResult> replayed = replayIfPresentAfterCollision(operation, operationTypeValue);
		if (replayed.isPresent()) {
			return finalizeReplay(operation, operationTypeValue, replayed.get());
		}

		log.warn("recordOperation idempotency constraint collision without replay winner workflowRunId={} artifactType={} operationType={} idempotencyKey={} cause={}",
			operation.workflowRunId(), operation.artifactType().value(), operationTypeValue,
			operation.idempotencyKey(), duplicate.getMostSpecificCause().getClass().getSimpleName());
		throw new DomainException(
			DomainErrorCode.ARTIFACT_OPERATION_CONFLICT,
			"Concurrent artifact operation conflict for idempotencyKey=" + operation.idempotencyKey(),
			Map.of(
				"workflowRunId", operation.workflowRunId(),
				"artifactType", operation.artifactType().value(),
				"operationType", operationTypeValue,
				"idempotencyKey", operation.idempotencyKey()),
			duplicate);
	}

	private Optional<RecordArtifactOperationResult> replayIfPresentAfterCollision(
		RecordArtifactOperationCommand operation,
		String operationTypeValue
	) {
		if (raceReplayTemplate == null) {
			return replayIfPresent(operation, operationTypeValue);
		}
		RecordArtifactOperationResult replayed = raceReplayTemplate.execute(
			status -> replayIfPresent(operation, operationTypeValue).orElse(null));
		return Optional.ofNullable(replayed);
	}

	private ArtifactRecordSnapshot createOrAdvanceArtifact(
		RecordArtifactOperationCommand operation,
		String operationTypeValue,
		String operationPublicId,
		ActorContext actor,
		Optional<ArtifactRecordSnapshot> latestArtifact
	) {
		return switch (operation.operationType()) {
			case REPLACE, UPDATE -> {
				if (latestArtifact.isEmpty()) {
					// Story 1.12c AC3 (formerly F10): UPDATE/REPLACE against empty lineage used to
					// silently bootstrap a v1 draft. Reject with a typed intent-conflict so SPI
					// callers exercising the wrong operation type surface a clear domain error
					// instead of getting a "wrong" v1 draft. Symmetric counterpart to the CREATE
					// branch which already raises ARTIFACT_LINEAGE_ALREADY_EXISTS against a
					// non-FAILED leaf below.
					log.warn("recordOperation rejected: UPDATE/REPLACE against empty lineage workflowRunId={} artifactType={} operationType={} idempotencyKey={}",
						operation.workflowRunId(), operation.artifactType().value(), operationTypeValue, operation.idempotencyKey());
					throw new DomainException(
						DomainErrorCode.ARTIFACT_OPERATION_INTENT_CONFLICT,
						"Cannot " + operationTypeValue + " a non-existent artifact lineage for workflowRunId="
							+ operation.workflowRunId() + ", artifactType=" + operation.artifactType().value()
							+ ". Use CREATE to bootstrap a lineage.",
						Map.of(
							"workflowRunId", operation.workflowRunId(),
							"artifactType", operation.artifactType().value(),
							"operationType", operationTypeValue));
				}
				yield artifactRecordPort.createNextVersion(new ArtifactVersionRequest(
					latestArtifact.get().publicId(),
					operation.payloadRef(),
					actor,
					operationTypeValue,
					operationPublicId,
					operation.idempotencyKey(),
					operation.runnerExecutionId()));
			}
			case CREATE -> {
				if (latestArtifact.isPresent() && latestArtifact.get().status() != ArtifactStatus.FAILED) {
					ArtifactRecordSnapshot existing = latestArtifact.get();
					log.warn("recordOperation rejected: lineage already exists workflowRunId={} artifactType={} existingArtifactId={} existingStatus={} existingVersion={}",
						operation.workflowRunId(), operation.artifactType().value(),
						existing.publicId(), existing.status().value(), existing.version());
					throw new DomainException(
						DomainErrorCode.ARTIFACT_LINEAGE_ALREADY_EXISTS,
						"Artifact lineage already exists for workflowRunId="
							+ operation.workflowRunId() + ", artifactType=" + operation.artifactType().value()
							+ ". Use UPDATE/REPLACE or newVersion to advance the lineage.",
						Map.of(
							"workflowRunId", operation.workflowRunId(),
							"artifactType", operation.artifactType().value(),
							"existingArtifactId", existing.publicId(),
							"existingArtifactStatus", existing.status().value(),
							"existingArtifactVersion", existing.version()));
				}
				yield artifactRecordPort.createDraft(new ArtifactDraftRequest(
					operation.workflowRunId(),
					operation.artifactType(),
					operation.payloadRef(),
					operation.artifactType().defaultClassification(),
					actor,
					operationTypeValue,
					operationPublicId,
					operation.idempotencyKey(),
					operation.runnerExecutionId()));
			}
		};
	}

	private void requireWorkflowRunNonTerminal(String workflowRunId) {
		// D2: an empty optional means the workflow run was not found — reject rather than
		// silently permitting the operation. The pre-lock check here is advisory; the
		// persistence adapter re-validates terminal state post-lock.
		Optional<WorkflowState> currentState = artifactWorkflowRunStatePort.currentState(workflowRunId);
		if (currentState.isEmpty()) {
			log.warn("recordOperation rejected: workflow run not found workflowRunId={}", workflowRunId);
			throw new DomainException(
				DomainErrorCode.ARTIFACT_WORKFLOW_RUN_NOT_FOUND,
				"Workflow run not found: " + workflowRunId,
				Map.of("workflowRunId", workflowRunId));
		}
		WorkflowState state = currentState.get();
		if (TERMINAL_RUN_STATES.contains(state)) {
			log.warn("recordOperation rejected: workflow run is terminal workflowRunId={} workflowRunState={}",
				workflowRunId, state.value());
			throw new DomainException(
				DomainErrorCode.WORKFLOW_RUN_TERMINAL,
				"Workflow run is in a terminal state and cannot accept artifact operations: " + state.value(),
				Map.of(
					"workflowRunId", workflowRunId,
					"workflowRunState", state.value()));
		}
	}

	private void requireActor(ActorContext actor) {
		if (actor == null) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact operation actor must be provided",
				Map.of("field", "actor"));
		}
	}

	private void validateStorageRef(String storageRef) {
		if (storageRef == null || storageRef.isBlank()) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact storageRef must be non-blank",
				Map.of("field", "storageRef"));
		}
		// P21: path shape guard — reject absolute paths, scheme prefixes, and path-traversal components.
		if (storageRef.contains("://")) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact storageRef must not contain a URI scheme (e.g. file://, https://)",
				Map.of("field", "storageRef"));
		}
		java.nio.file.Path path;
		try {
			path = java.nio.file.Paths.get(storageRef);
		} catch (Exception e) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact storageRef is not a valid path",
				Map.of("field", "storageRef"));
		}
		if (path.isAbsolute()) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact storageRef must be a relative path",
				Map.of("field", "storageRef"));
		}
		for (java.nio.file.Path component : path) {
			if ("..".equals(component.toString())) {
				throw new DomainException(
					DomainErrorCode.INVALID_COMMAND_PAYLOAD,
					"Artifact storageRef must not contain '..' path traversal components",
					Map.of("field", "storageRef"));
			}
		}
	}

	private void verifyPayloadMatchesChecksum(String artifactId, String storageRef, ArtifactChecksum checksum) {
		// P15: disallowed or unrecognized algorithm → INVALID_COMMAND_PAYLOAD (not ARTIFACT_CHECKSUM_MISMATCH).
		// The caller supplied an algorithm that DeliveryLine does not accept; this is a command
		// validation error, not a checksum mismatch.
		String canonical = ArtifactChecksum.canonicalAlgorithm(checksum.algorithm());
		if (canonical == null || !ArtifactChecksum.ALLOWED_ALGORITHMS.contains(canonical)) {
			log.warn("markAvailable rejected: disallowed checksum algorithm artifactId={} algorithm={}",
				artifactId, checksum.algorithm());
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Checksum algorithm is not allowed: " + checksum.algorithm()
					+ ". Allowed algorithms: " + ArtifactChecksum.ALLOWED_ALGORITHMS,
				Map.of(
					"artifactId", artifactId,
					"checksumAlgorithm", checksum.algorithm()));
		}
		Optional<byte[]> bytes = artifactPayloadStore.readBytes(storageRef);
		if (bytes.isEmpty()) {
			log.warn("markAvailable rejected: payload unreadable artifactId={} storageRef={}", artifactId, storageRef);
			throw new DomainException(
				DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
				"Artifact payload is not readable at supplied storageRef",
				Map.of(
					"artifactId", artifactId,
					"storageRef", storageRef));
		}
		byte[] payload = bytes.get();
		if (payload.length == 0) {
			log.warn("markAvailable rejected: payload is empty artifactId={} storageRef={}", artifactId, storageRef);
			throw new DomainException(
				DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
				"Artifact payload is empty at supplied storageRef",
				Map.of(
					"artifactId", artifactId,
					"storageRef", storageRef));
		}
		// ArtifactChecksum.digestHex throws IllegalStateException for allowlisted algorithms when
		// the JVM is broken (P5); that propagates as an unchecked error — no recovery possible.
		String recomputedHex = ArtifactChecksum.digestHex(checksum.algorithm(), payload).orElseThrow(
			() -> new IllegalStateException("digestHex returned empty for allowlisted algorithm " + canonical));
		// P16: constant-time byte-array comparison prevents timing side-channels that could leak
		// whether a partial checksum prefix is correct.
		byte[] recomputedBytes = HexFormat.of().parseHex(recomputedHex);
		byte[] suppliedBytes;
		try {
			suppliedBytes = HexFormat.of().parseHex(checksum.value().toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			log.warn("markAvailable rejected: supplied checksum is not valid hex artifactId={} algorithm={}", artifactId, checksum.algorithm());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
				"Supplied checksum value is not valid hexadecimal",
				Map.of(
					"artifactId", artifactId,
					"checksumAlgorithm", checksum.algorithm()));
		}
		if (!MessageDigest.isEqual(recomputedBytes, suppliedBytes)) {
			log.warn("markAvailable rejected: checksum mismatch artifactId={} algorithm={} payloadLength={}",
				artifactId, checksum.algorithm(), payload.length);
			throw new DomainException(
				DomainErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
				"Recomputed checksum does not match supplied checksum",
				Map.of(
					"artifactId", artifactId,
					"checksumAlgorithm", checksum.algorithm()));
		}
	}

	private void validateFailureReason(String reason) {
		// V2's ck_artifacts_failure_reason_paired enforces (failure_category IS NULL) = (failure_reason IS NULL).
		// markFailed always sets failureCategory non-null, so a null/blank reason violates the constraint
		// at flush time and crashes the request as a raw DataIntegrityViolationException. Reject up front.
		if (reason == null || reason.isBlank()) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact failure reason must be non-blank",
				Map.of("field", "reason"));
		}
		// P22: cap reason length to avoid pathological DB writes.
		if (reason.length() > MAX_FAILURE_REASON_LENGTH) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact failure reason must not exceed " + MAX_FAILURE_REASON_LENGTH + " characters",
				Map.of("field", "reason", "length", reason.length()));
		}
	}

	private void validateChecksum(ArtifactChecksum checksum) {
		if (checksum == null) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact checksum must be provided",
				Map.of("field", "checksum"));
		}
		if (checksum.algorithm() == null || checksum.algorithm().isBlank()) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact checksum algorithm must be non-blank",
				Map.of("field", "checksum.algorithm"));
		}
		if (checksum.value() == null || checksum.value().isBlank()) {
			throw new DomainException(
				DomainErrorCode.INVALID_COMMAND_PAYLOAD,
				"Artifact checksum value must be non-blank",
				Map.of("field", "checksum.value"));
		}
	}
}
