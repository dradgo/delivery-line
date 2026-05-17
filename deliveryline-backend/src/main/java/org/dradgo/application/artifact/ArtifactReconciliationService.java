package org.dradgo.application.artifact;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ArtifactReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ArtifactReconciliationService.class);

	private final ArtifactOperationPort artifactOperationPort;
	private final ArtifactRecordPort artifactRecordPort;
	private final ArtifactEventPort artifactEventPort;
	private final Clock clock;
	private final Duration stalePendingThreshold;
	private final TransactionTemplate perItemTransactionTemplate;

	@Autowired
	public ArtifactReconciliationService(
		ArtifactOperationPort artifactOperationPort,
		ArtifactRecordPort artifactRecordPort,
		ArtifactEventPort artifactEventPort,
		PlatformTransactionManager transactionManager,
		@Value("${deliveryline.artifact.reconciliation.stale-pending-minutes:15}") long stalePendingMinutes
	) {
		this(
			artifactOperationPort,
			artifactRecordPort,
			artifactEventPort,
			Clock.systemUTC(),
			Duration.ofMinutes(stalePendingMinutes),
			requiresNewTemplate(transactionManager));
	}

	/**
	 * Test-only constructor. Callers MUST pass a {@code TransactionTemplate} that delegates to
	 * the production REQUIRES_NEW propagation (or a Mockito stub that calls the callback
	 * inline). Passing {@code null} is rejected so unit tests cannot silently skip the per-item
	 * isolation that production reconciliation depends on.
	 */
	ArtifactReconciliationService(
		ArtifactOperationPort artifactOperationPort,
		ArtifactRecordPort artifactRecordPort,
		ArtifactEventPort artifactEventPort,
		Clock clock,
		Duration stalePendingThreshold,
		TransactionTemplate perItemTransactionTemplate
	) {
		// P24: validate constructor parameters at construction time.
		if (artifactOperationPort == null) {
			throw new IllegalArgumentException("artifactOperationPort must not be null");
		}
		if (artifactRecordPort == null) {
			throw new IllegalArgumentException("artifactRecordPort must not be null");
		}
		if (artifactEventPort == null) {
			throw new IllegalArgumentException("artifactEventPort must not be null");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock must not be null");
		}
		if (stalePendingThreshold == null || stalePendingThreshold.compareTo(Duration.ofMinutes(1)) < 0) {
			throw new IllegalArgumentException(
				"stalePendingThreshold must be at least 1 minute but was: " + stalePendingThreshold);
		}
		if (perItemTransactionTemplate == null) {
			throw new IllegalArgumentException(
				"perItemTransactionTemplate must not be null; tests must stub a callthrough TransactionTemplate "
					+ "so reconciliation cannot silently bypass REQUIRES_NEW isolation");
		}
		this.artifactOperationPort = artifactOperationPort;
		this.artifactRecordPort = artifactRecordPort;
		this.artifactEventPort = artifactEventPort;
		this.clock = clock;
		this.stalePendingThreshold = stalePendingThreshold;
		this.perItemTransactionTemplate = perItemTransactionTemplate;
	}

	private static TransactionTemplate requiresNewTemplate(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	public ArtifactReconciliationResult reconcileStalePendingOperations() {
		// Stale-window comparison runs DB-side via findPendingOlderThan: created_at < (now() - threshold).
		// JVM-derived thresholds drift away from DB-side timestamps under clock skew, so the staleness
		// decision must stay on a single clock to avoid false-positive or false-negative orphan flips.
		List<ArtifactOperationSnapshot> stale = artifactOperationPort.findPendingOlderThan(stalePendingThreshold);
		log.info("reconcileStalePendingOperations start stalePendingThreshold={} candidateCount={}",
			stalePendingThreshold, stale.size());
		List<ArtifactOperationSnapshot> orphaned = new ArrayList<>();
		for (ArtifactOperationSnapshot operation : stale) {
			// P23: per-item exception isolation. A failure on one item must not abort the entire
			// batch — log and continue so the remaining candidates are still processed.
			try {
				Optional<ArtifactOperationSnapshot> result = reconcileWithIsolation(operation);
				result.ifPresent(orphaned::add);
			} catch (Exception error) {
				log.error("reconcileStalePendingOperations item failed operationId={} artifactId={} cause={}",
					operation.publicId(), operation.artifactId(), error.toString());
			}
		}
		log.info("reconcileStalePendingOperations done stalePendingThreshold={} candidateCount={} orphanedCount={}",
			stalePendingThreshold, stale.size(), orphaned.size());
		return new ArtifactReconciliationResult(orphaned);
	}

	private Optional<ArtifactOperationSnapshot> reconcileWithIsolation(ArtifactOperationSnapshot operation) {
		return perItemTransactionTemplate.execute(status -> reconcileSingleOperation(operation));
	}

	Optional<ArtifactOperationSnapshot> reconcileSingleOperation(ArtifactOperationSnapshot operation) {
		String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, operation.artifactId());
		String priorOperationMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_OPERATION_ID, operation.publicId());
		try {
		Optional<ArtifactRecordSnapshot> currentArtifact = artifactRecordPort.findByPublicId(operation.artifactId());
		if (currentArtifact.isEmpty()) {
			log.info("reconcileSingleOperation skip operationId={} artifactId={} reason=artifactAbsent",
				operation.publicId(), operation.artifactId());
			return Optional.empty();
		}
		ArtifactStatus artifactStatus = currentArtifact.get().status();
		ArtifactOperationSnapshot orphaned;
		if (artifactStatus == ArtifactStatus.PENDING) {
			// Full flip: artifact → FAILED, then operation → FAILED_ORPHAN.
			// Scope catch to markFailed only: a state-race on the artifact side means a peer already
			// took ownership and this run should skip. A failure in markFailedOrphan after a
			// successful markFailed would leave torn state (artifact failed, operation still pending)
			// and must propagate so the caller can alert/retry rather than silently swallowing it.
			try {
				artifactRecordPort.markFailed(operation.artifactId(), FailureCategory.ORPHAN, "stale_pending");
			} catch (DomainException error) {
				if (error.errorCode() == DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION) {
					log.info("reconcileSingleOperation skip operationId={} artifactId={} reason=artifactStateRaceWonByPeer",
						operation.publicId(), operation.artifactId());
					return Optional.empty();
				}
				throw error;
			}
			orphaned = artifactOperationPort.markFailedOrphan(
				operation.publicId(),
				"artifact payload never materialized");
			// P20: for PENDING artifacts, emit BOTH ARTIFACT_FAILED and RECOVERY_RECONCILED so
			// existing consumers subscribed to ARTIFACT_FAILED see the flip without having to
			// learn about the new reconciliation event type.
			// P18: use a fresh per-tick UUID correlationId; do NOT reuse ActorContext.SYSTEM.correlationId.
			String correlationId = UUID.randomUUID().toString();
			OffsetDateTime now = OffsetDateTime.now(clock);
			artifactEventPort.append(new ArtifactEventRecord(
				orphaned.workflowRunId(),
				WorkflowEventType.ARTIFACT_FAILED,
				ActorContext.SYSTEM.actorIdentity(),
				ActorContext.SYSTEM.actorType(),
				"stale_pending",
				FailureCategory.ORPHAN,
				now,
				Map.of(
					"artifactId", orphaned.artifactId(),
					"operationId", orphaned.publicId(),
					"failureCategory", FailureCategory.ORPHAN.value(),
					"failureReason", "stale_pending",
					"correlationId", correlationId)));
			artifactEventPort.append(new ArtifactEventRecord(
				orphaned.workflowRunId(),
				WorkflowEventType.RECOVERY_RECONCILED,
				ActorContext.SYSTEM.actorIdentity(),
				ActorContext.SYSTEM.actorType(),
				"artifact reconciliation detected stale pending operation",
				FailureCategory.ORPHAN,
				now,
				Map.of(
					"artifactId", orphaned.artifactId(),
					"operationId", orphaned.publicId(),
					"stalePendingThreshold", stalePendingThreshold.toString(),
					"correlationId", correlationId)));
		} else if (artifactStatus == ArtifactStatus.LATE_OR_STALE) {
			// Artifact already flagged late-or-stale by the runner-timeout guard. The artifact record
			// is not in PENDING and must not be touched here — only close the dangling operation row.
			try {
				orphaned = artifactOperationPort.markFailedOrphan(
					operation.publicId(),
					"artifact payload never materialized");
			} catch (DomainException closeError) {
				log.info("reconcileSingleOperation skip late-or-stale operationId={} artifactId={} reason={}",
					operation.publicId(), operation.artifactId(), closeError.getMessage());
				return Optional.empty();
			}
			// P20: for LATE_OR_STALE, emit only RECOVERY_RECONCILED — do NOT emit ARTIFACT_FAILED
			// since the artifact status already reflects the failure; a second ARTIFACT_FAILED event
			// would mislead consumers into thinking the artifact just transitioned to failed.
			String correlationId = UUID.randomUUID().toString();
			OffsetDateTime now = OffsetDateTime.now(clock);
			artifactEventPort.append(new ArtifactEventRecord(
				orphaned.workflowRunId(),
				WorkflowEventType.RECOVERY_RECONCILED,
				ActorContext.SYSTEM.actorIdentity(),
				ActorContext.SYSTEM.actorType(),
				"artifact reconciliation closed stale late-or-stale operation",
				FailureCategory.ORPHAN,
				now,
				Map.of(
					"artifactId", orphaned.artifactId(),
					"operationId", orphaned.publicId(),
					"stalePendingThreshold", stalePendingThreshold.toString(),
					"correlationId", correlationId)));
		} else {
			// P14: artifact is FAILED, AVAILABLE, or ARCHIVED — it is no longer PENDING or LATE_OR_STALE,
			// but the operation row may still be dangling (zombie). Close it as FAILED_ORPHAN so it does
			// not interfere with the single-pending invariant or show up in future reconciliation passes.
			try {
				orphaned = artifactOperationPort.markFailedOrphan(
					operation.publicId(),
					"zombie operation: artifact reached terminal/available state without completing the operation");
			} catch (DomainException closeError) {
				log.info("reconcileSingleOperation skip zombie operationId={} artifactId={} artifactStatus={} reason={}",
					operation.publicId(), operation.artifactId(), artifactStatus.value(), closeError.getMessage());
				return Optional.empty();
			}
			log.info("reconcileSingleOperation closed zombie operationId={} artifactId={} artifactStatus={}",
				orphaned.publicId(), orphaned.artifactId(), artifactStatus.value());
			return Optional.of(orphaned);
		}
		log.warn("reconcileSingleOperation flipped artifact to failed/orphan operationId={} artifactId={} workflowRunId={} stalePendingThreshold={}",
			orphaned.publicId(), orphaned.artifactId(), orphaned.workflowRunId(), stalePendingThreshold);
		return Optional.of(orphaned);
		} finally {
			MdcKeys.endScope(MdcKeys.ARTIFACT_OPERATION_ID, priorOperationMdc);
			MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
		}
	}
}
