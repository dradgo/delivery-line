package org.dradgo.application.artifact;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ArtifactOperationService {

	private static final Logger log = LoggerFactory.getLogger(ArtifactOperationService.class);

	private static final EnumSet<WorkflowState> TERMINAL_RUN_STATES = EnumSet.of(
		WorkflowState.COMPLETED,
		WorkflowState.FAILED,
		WorkflowState.RECONCILED);

	private final ArtifactRecordPort artifactRecordPort;
	private final ArtifactOperationPort artifactOperationPort;
	private final ArtifactPayloadStore artifactPayloadStore;
	private final ArtifactEventPort artifactEventPort;
	private final ArtifactRunnerExecutionPort artifactRunnerExecutionPort;
	private final ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort;
	private final Clock clock;

	@Autowired
	public ArtifactOperationService(
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
			Clock.systemUTC());
	}

	ArtifactOperationService(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort,
		ArtifactWorkflowRunStatePort artifactWorkflowRunStatePort,
		Clock clock
	) {
		this.artifactRecordPort = artifactRecordPort;
		this.artifactOperationPort = artifactOperationPort;
		this.artifactPayloadStore = artifactPayloadStore;
		this.artifactEventPort = artifactEventPort;
		this.artifactRunnerExecutionPort = artifactRunnerExecutionPort;
		this.artifactWorkflowRunStatePort = artifactWorkflowRunStatePort;
		this.clock = clock;
	}

	/**
	 * Convenience constructor that defaults {@code ArtifactWorkflowRunStatePort} to a stub
	 * which always returns {@link Optional#empty()} — i.e. every workflow run is treated as
	 * non-terminal.
	 *
	 * <p><strong>Footgun:</strong> this silently bypasses the {@code WORKFLOW_RUN_TERMINAL}
	 * precondition guard added in chunk-1/Bundle-3 R12. Production wiring goes through the
	 * 6-arg {@link Autowired} constructor that injects the real port.
	 *
	 * <p>Prefer {@link #withoutWorkflowRunStateGuard} so the bypass is named and intentional;
	 * tests that rely on the guard MUST wire a real or stubbed
	 * {@link ArtifactWorkflowRunStatePort}.
	 *
	 * @deprecated New callers must not use this constructor; use
	 *             {@link #withoutWorkflowRunStateGuard} or wire the 6-arg ctor explicitly.
	 */
	@Deprecated(forRemoval = true)
	public ArtifactOperationService(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort
	) {
		this(
			artifactRecordPort,
			artifactOperationPort,
			artifactPayloadStore,
			artifactEventPort,
			artifactRunnerExecutionPort,
			runId -> Optional.empty(),
			Clock.systemUTC());
	}

	/**
	 * Builds a service whose workflow-run-state guard is intentionally absent.
	 *
	 * <p>Use only in unit tests that do not exercise the {@code WORKFLOW_RUN_TERMINAL}
	 * precondition. Tests that touch run-state behavior must wire a real or stubbed
	 * {@link ArtifactWorkflowRunStatePort} via the 6-arg constructor instead.
	 */
	public static ArtifactOperationService withoutWorkflowRunStateGuard(
		ArtifactRecordPort artifactRecordPort,
		ArtifactOperationPort artifactOperationPort,
		ArtifactPayloadStore artifactPayloadStore,
		ArtifactEventPort artifactEventPort,
		ArtifactRunnerExecutionPort artifactRunnerExecutionPort
	) {
		return new ArtifactOperationService(
			artifactRecordPort,
			artifactOperationPort,
			artifactPayloadStore,
			artifactEventPort,
			artifactRunnerExecutionPort,
			runId -> Optional.empty(),
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
		log.info("createDraft start workflowRunId={} artifactType={} actorIdentity={} actorType={} correlationId={}",
			workflowRunId, artifactType.value(), actor.actorIdentity(), actor.actorType(), actor.correlationId());
		ArtifactRecordSnapshot artifact = artifactRecordPort.createDraft(new ArtifactDraftRequest(
			workflowRunId,
			artifactType,
			payloadRef,
			artifactType.defaultClassification(),
			actor,
			null,
			null,
			null,
			null));
		log.info("createDraft success workflowRunId={} artifactType={} artifactId={} version={}",
			workflowRunId, artifactType.value(), artifact.publicId(), artifact.version());
		return artifact;
	}

	@Transactional
	public RecordArtifactOperationResult recordOperation(RecordArtifactOperationCommand operation) {
		ActorContext actor = new ActorContext(operation.actorIdentity(), operation.actorType(), operation.correlationId());
		String operationTypeValue = operation.operationType().value();
		log.info("recordOperation start workflowRunId={} artifactType={} operationType={} idempotencyKey={} actorIdentity={} correlationId={} runnerExecutionId={}",
			operation.workflowRunId(), operation.artifactType().value(), operationTypeValue,
			operation.idempotencyKey(), operation.actorIdentity(), operation.correlationId(), operation.runnerExecutionId());

		Optional<RecordArtifactOperationResult> replayed = replayIfPresent(operation, operationTypeValue);
		if (replayed.isPresent()) {
			RecordArtifactOperationResult result = replayed.get();
			log.warn("recordOperation replay hit workflowRunId={} idempotencyKey={} operationType={} priorOperationId={} priorOperationStatus={} priorArtifactId={}",
				operation.workflowRunId(), operation.idempotencyKey(), operationTypeValue,
				result.operation().publicId(), result.operation().status().value(), result.artifact().publicId());
			return result;
		}

		requireWorkflowRunNonTerminal(operation.workflowRunId());

		Optional<ArtifactRecordSnapshot> latestArtifact = artifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(
			operation.workflowRunId(),
			operation.artifactType().value());
		String operationPublicId = PublicIdPrefixes.ARTIFACT_OPERATION.next();
		ArtifactRecordSnapshot artifact = createOrAdvanceArtifact(operation, operationTypeValue, operationPublicId, actor, latestArtifact);

		if (operation.runnerExecutionId() != null && artifactRunnerExecutionPort.isTimedOut(operation.runnerExecutionId())) {
			log.warn("recordOperation flagging artifact as late_or_stale workflowRunId={} artifactId={} runnerExecutionId={}",
				operation.workflowRunId(), artifact.publicId(), operation.runnerExecutionId());
			artifact = artifactRecordPort.markLateOrStale(artifact.publicId());
		}

		try {
			ArtifactOperationSnapshot pending = artifactOperationPort.createPending(
				operationPublicId,
				operation.workflowRunId(),
				operation.artifactType(),
				artifact.publicId(),
				operationTypeValue,
				operation.idempotencyKey());
			log.info("recordOperation success workflowRunId={} artifactId={} operationId={} operationType={} idempotencyKey={}",
				operation.workflowRunId(), artifact.publicId(), pending.publicId(), operationTypeValue, operation.idempotencyKey());
			// D1 (round-5 decision): the service owns the payload write. The DB transaction holds
			// the workflow_event/artifact/operation rows; the file write must happen AFTER commit
			// so a rolled-back metadata commit cannot leave an orphan payload on disk. When a
			// Spring transaction is active, register an afterCommit hook; otherwise (unit test
			// without @Transactional proxy) write inline so behavior remains observable.
			schedulePayloadWriteAfterCommit(operation, artifact);
			return new RecordArtifactOperationResult(artifact, pending);
		} catch (DataIntegrityViolationException duplicate) {
			Optional<RecordArtifactOperationResult> raceReplay = replayIfPresent(operation, operationTypeValue);
			if (raceReplay.isPresent()) {
				RecordArtifactOperationResult result = raceReplay.get();
				log.warn("recordOperation race-replay resolved after idempotency-constraint collision workflowRunId={} idempotencyKey={} winnerOperationId={}",
					operation.workflowRunId(), operation.idempotencyKey(), result.operation().publicId());
				return result;
			}
			log.warn("recordOperation conflict no-replay workflowRunId={} artifactType={} operationType={} idempotencyKey={} cause={}",
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
		log.info("markAvailable start artifactId={} storageRef={} checksumAlgorithm={} actorIdentity={} correlationId={}",
			artifactId, storageRef, checksum.algorithm(), actor.actorIdentity(), actor.correlationId());
		ArtifactOperationSnapshot pending = artifactOperationPort.findPendingByArtifactId(artifactId)
			.orElseThrow(() -> {
				log.warn("markAvailable rejected: no pending operation for artifactId={}", artifactId);
				return new DomainException(
					DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
					"Pending artifact operation not found for artifact: " + artifactId,
					Map.of("artifactId", artifactId));
			});
		// Re-verify before flipping: read payload bytes through the contained payload store and
		// recompute the digest. A poisoned markAvailable that supplies a checksum for a payload
		// that does not exist (or whose digest does not match the supplied value) must not flip
		// the artifact to AVAILABLE — otherwise downstream approval-eligibility gates trust the
		// stored checksum at face value. Defense-in-depth on top of ArtifactService.isApprovalEligible.
		verifyPayloadMatchesChecksum(artifactId, storageRef, checksum);
		ArtifactRecordSnapshot artifact = artifactRecordPort.markAvailable(artifactId, checksum, storageRef);
		ArtifactOperationSnapshot operation = artifactOperationPort.markComplete(pending.publicId());
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
		log.info("markFailed start artifactId={} failureCategory={} actorIdentity={} correlationId={}",
			artifactId, failureCategory.value(), actor.actorIdentity(), actor.correlationId());
		ArtifactOperationSnapshot pending = artifactOperationPort.findPendingByArtifactId(artifactId)
			.orElseThrow(() -> {
				log.warn("markFailed rejected: no pending operation for artifactId={}", artifactId);
				return new DomainException(
					DomainErrorCode.ARTIFACT_PAYLOAD_UNAVAILABLE,
					"Pending artifact operation not found for artifact: " + artifactId,
					Map.of("artifactId", artifactId));
			});
		ArtifactRecordSnapshot artifact = artifactRecordPort.markFailed(artifactId, failureCategory, reason);
		ArtifactOperationSnapshot operation = artifactOperationPort.markFailed(pending.publicId(), failureCategory, reason);
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

	private ArtifactRecordSnapshot createOrAdvanceArtifact(
		RecordArtifactOperationCommand operation,
		String operationTypeValue,
		String operationPublicId,
		ActorContext actor,
		Optional<ArtifactRecordSnapshot> latestArtifact
	) {
		return switch (operation.operationType()) {
			case REPLACE, UPDATE -> latestArtifact
				.map(existing -> artifactRecordPort.createNextVersion(new ArtifactVersionRequest(
					existing.publicId(),
					operation.payloadRef(),
					actor,
					operationTypeValue,
					operationPublicId,
					operation.idempotencyKey(),
					operation.runnerExecutionId())))
				.orElseGet(() -> artifactRecordPort.createDraft(new ArtifactDraftRequest(
					operation.workflowRunId(),
					operation.artifactType(),
					operation.payloadRef(),
					operation.artifactType().defaultClassification(),
					actor,
					operationTypeValue,
					operationPublicId,
					operation.idempotencyKey(),
					operation.runnerExecutionId())));
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

	private void schedulePayloadWriteAfterCommit(
		RecordArtifactOperationCommand operation,
		ArtifactRecordSnapshot artifact
	) {
		if (operation.payloadContent() == null) {
			return;
		}
		final String workflowRunId = operation.workflowRunId();
		final String artifactId = artifact.publicId();
		final int version = artifact.version();
		final String payloadRef = operation.payloadRef();
		final byte[] payloadContent = operation.payloadContent();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					try {
						artifactPayloadStore.write(workflowRunId, artifactId, version, payloadRef, payloadContent);
					} catch (RuntimeException error) {
						// Metadata is already committed; the operation row is PENDING. Reconciliation
						// will mark it stale-pending once the threshold elapses. Do not rethrow —
						// afterCommit failures cannot roll back a committed transaction anyway.
						log.error("recordOperation payload write failed after metadata commit; reconciliation will mark stale-pending workflowRunId={} artifactId={} version={} payloadBytes={} cause={}",
							workflowRunId, artifactId, version, payloadContent.length, error.toString());
					}
				}
			});
		} else {
			// No active Spring transaction (unit-test path without @Transactional proxy). Write
			// inline so the test surface is observable and write failures propagate.
			artifactPayloadStore.write(workflowRunId, artifactId, version, payloadRef, payloadContent);
		}
	}

	private void requireWorkflowRunNonTerminal(String workflowRunId) {
		Optional<WorkflowState> currentState = artifactWorkflowRunStatePort.currentState(workflowRunId);
		if (currentState.isEmpty()) {
			return;
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
	}

	private void verifyPayloadMatchesChecksum(String artifactId, String storageRef, ArtifactChecksum checksum) {
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
		Optional<String> recomputed = ArtifactChecksum.digestHex(checksum.algorithm(), payload);
		if (recomputed.isEmpty()) {
			log.warn("markAvailable rejected: unknown or disallowed checksum algorithm artifactId={} algorithm={}",
				artifactId, checksum.algorithm());
			throw new DomainException(
				DomainErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
				"Unknown or disallowed checksum algorithm: " + checksum.algorithm(),
				Map.of(
					"artifactId", artifactId,
					"checksumAlgorithm", checksum.algorithm()));
		}
		if (!recomputed.get().equalsIgnoreCase(checksum.value())) {
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
