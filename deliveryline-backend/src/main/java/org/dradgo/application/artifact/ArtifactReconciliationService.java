package org.dradgo.application.artifact;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.domain.DomainException;
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
			Optional<ArtifactOperationSnapshot> result = reconcileWithIsolation(operation);
			result.ifPresent(orphaned::add);
		}
		log.info("reconcileStalePendingOperations done stalePendingThreshold={} candidateCount={} orphanedCount={}",
			stalePendingThreshold, stale.size(), orphaned.size());
		return new ArtifactReconciliationResult(orphaned);
	}

	private Optional<ArtifactOperationSnapshot> reconcileWithIsolation(ArtifactOperationSnapshot operation) {
		return perItemTransactionTemplate.execute(status -> reconcileSingleOperation(operation));
	}

	Optional<ArtifactOperationSnapshot> reconcileSingleOperation(ArtifactOperationSnapshot operation) {
		Optional<ArtifactRecordSnapshot> currentArtifact = artifactRecordPort.findByPublicId(operation.artifactId());
		if (currentArtifact.isEmpty() || currentArtifact.get().status() != ArtifactStatus.PENDING) {
			log.info("reconcileSingleOperation skip operationId={} artifactId={} reason=artifactNoLongerPending",
				operation.publicId(), operation.artifactId());
			return Optional.empty();
		}
		ArtifactOperationSnapshot orphaned;
		try {
			artifactRecordPort.markFailed(operation.artifactId(), FailureCategory.ORPHAN, "stale_pending");
			orphaned = artifactOperationPort.markFailedOrphan(
				operation.publicId(),
				"artifact payload never materialized");
		} catch (DomainException error) {
			if (error.errorCode() == DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION) {
				log.info("reconcileSingleOperation skip operationId={} artifactId={} reason=artifactStateRaceWonByPeer",
					operation.publicId(), operation.artifactId());
				return Optional.empty();
			}
			throw error;
		}
		// D2 (round-5 decision): append BOTH ARTIFACT_FAILED and RECOVERY_RECONCILED so existing
		// consumers subscribed to ARTIFACT_FAILED see the orphan flip without having to learn
		// about the new reconciliation event type. Both events sit on the same DB clock as the
		// preceding artifact/operation flips because they're emitted inside the per-item
		// REQUIRES_NEW transaction.
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
				"failureReason", "stale_pending")));
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
				"stalePendingThreshold", stalePendingThreshold.toString())));
		log.warn("reconcileSingleOperation flipped artifact to failed/orphan operationId={} artifactId={} workflowRunId={} stalePendingThreshold={}",
			orphaned.publicId(), orphaned.artifactId(), orphaned.workflowRunId(), stalePendingThreshold);
		return Optional.of(orphaned);
	}
}
