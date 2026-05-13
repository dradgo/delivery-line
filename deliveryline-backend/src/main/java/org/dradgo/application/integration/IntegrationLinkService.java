package org.dradgo.application.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.integration.linear.LinearAdapter;
import org.dradgo.application.integration.linear.LinearAdapterException;
import org.dradgo.application.integration.linear.LinearTicket;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service that owns the lifecycle of {@code integration_links} rows for Linear-sourced
 * workflow runs (story 1.14 Task 5).
 *
 * <p>{@link #linkTicket(String, String, ActorContext, String)} is the single entry point for
 * creating a link. It reserves an {@code ilk_} id under an idempotency key, fetches the source
 * ticket through {@link LinearAdapter}, takes a {@code SELECT … FOR UPDATE} on any existing
 * active link for the same {@code (linear, externalRef)}, and inserts a redacted row.
 *
 * <p>Only this service and the polling host bean may call
 * {@link LinearAdapter} directly — CLI, REST, and persistence layers must call this service
 * (mirrors the "only {@code RunnerBroker} may call {@code RunnerAdapter.dispatch}" rule from
 * story 1.13).
 */
@Service
public class IntegrationLinkService {

	private static final Logger log = LoggerFactory.getLogger(IntegrationLinkService.class);

	static final String LINEAR_INTEGRATION_TYPE = "linear";
	static final String COMMAND_TYPE = "IntegrationLinkService.linkTicket";

	private final IntegrationLinkRecordPort integrationLinkRecordPort;
	private final LinearAdapter linearAdapter;
	private final IdempotencyService idempotencyService;
	private final RedactionPolicyService redactionPolicyService;
	private final TransactionTemplate failureCompletionTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	public IntegrationLinkService(
		IntegrationLinkRecordPort integrationLinkRecordPort,
		LinearAdapter linearAdapter,
		IdempotencyService idempotencyService,
		RedactionPolicyService redactionPolicyService,
		PlatformTransactionManager transactionManager
	) {
		this(
			integrationLinkRecordPort,
			linearAdapter,
			idempotencyService,
			redactionPolicyService,
			requiresNewTemplate(transactionManager));
	}

	public IntegrationLinkService(
		IntegrationLinkRecordPort integrationLinkRecordPort,
		LinearAdapter linearAdapter,
		IdempotencyService idempotencyService,
		RedactionPolicyService redactionPolicyService,
		TransactionTemplate failureCompletionTemplate
	) {
		this.integrationLinkRecordPort = Objects.requireNonNull(integrationLinkRecordPort, "integrationLinkRecordPort");
		this.linearAdapter = Objects.requireNonNull(linearAdapter, "linearAdapter");
		this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
		this.redactionPolicyService = Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
		this.failureCompletionTemplate = Objects.requireNonNull(failureCompletionTemplate, "failureCompletionTemplate");
	}

	/**
	 * Reserve + insert (or replay) an {@code integration_links} row for a Linear ticket on a
	 * workflow run. See class doc for invariants.
	 */
	@Transactional
	public IntegrationLink linkTicket(
		String workflowRunPublicId,
		String linearTicketRef,
		ActorContext actor,
		String idempotencyKey
	) {
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		Objects.requireNonNull(linearTicketRef, "linearTicketRef");
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");

		String fingerprint = computeFingerprint(LINEAR_INTEGRATION_TYPE, linearTicketRef, workflowRunPublicId);
		log.info(
			"linkTicket entry workflowRunId={} externalRef={} actorIdentity={} idempotencyKey={}",
			workflowRunPublicId, linearTicketRef, actor.actorIdentity(), idempotencyKey);

		ReservationOutcome outcome = idempotencyService.checkAndReserve(
			idempotencyKey,
			COMMAND_TYPE,
			actor.actorIdentity(),
			fingerprint);

		if (outcome.decision() == IdempotencyService.ReservationDecision.REPLAY) {
			String priorRef = outcome.resultRef();
			if (priorRef == null) {
				// Prior attempt failed terminally (e.g., LINEAR_TICKET_NOT_FOUND with null resultRef
				// was completed). Replay the same failure rather than re-running the side effects.
				throw replayedTerminalFailure(idempotencyKey, linearTicketRef);
			}
			IntegrationLink existing = integrationLinkRecordPort.findByPublicId(priorRef)
				.orElseThrow(() -> replayedRecordMissing(idempotencyKey, priorRef));
			log.info("linkTicket replay idempotencyKey={} resultRef={}", idempotencyKey, priorRef);
			return existing;
		}

		// RESERVED — proceed with the side-effecting body.
		Optional<IntegrationLink> activeLink = integrationLinkRecordPort
			.findActiveByTypeAndExternalRefForUpdate(LINEAR_INTEGRATION_TYPE, linearTicketRef);
		if (activeLink.isPresent()) {
			IntegrationLink existing = activeLink.get();
			if (existing.workflowRunPublicId().equals(workflowRunPublicId)) {
				completeInIndependentTransaction(
					idempotencyKey,
					existing.publicId(),
					IdempotencyRecordStatus.COMPLETED);
				log.info(
					"linkTicket idempotent_same_run workflowRunId={} externalRef={} existingPublicId={}",
					workflowRunPublicId, linearTicketRef, existing.publicId());
				return existing;
			}
			completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
			log.warn(
				"linkTicket cross_run_conflict workflowRunId={} externalRef={} existingRunId={}",
				workflowRunPublicId, linearTicketRef, existing.workflowRunPublicId());
			throw crossRunConflict(linearTicketRef, existing);
		}

		String publicId = PublicIdPrefixes.INTEGRATION_LINK.next();
		LinearTicket ticket;
		try {
			Optional<LinearTicket> fetched = linearAdapter.fetchTicketByReference(linearTicketRef);
			if (fetched.isEmpty()) {
				completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
				log.warn("linkTicket ticket_not_found workflowRunId={} externalRef={}",
					workflowRunPublicId, linearTicketRef);
				throw linearTicketNotFound(linearTicketRef);
			}
			ticket = fetched.get();
		} catch (LinearAdapterException error) {
			completeInIndependentTransaction(idempotencyKey, null, IdempotencyRecordStatus.FAILED);
			log.warn(
				"linkTicket adapter_failure workflowRunId={} externalRef={} category={}",
				workflowRunPublicId, linearTicketRef, error.failureCategory().value());
			throw adapterFailure(linearTicketRef, error);
		}

		Map<String, Object> rawMetadata = buildExternalMetadata(ticket);
		RedactionResult redacted = redactionPolicyService.redact(
			rawMetadata, DataClassification.SHAREABLE_REDACTED.value());
		byte[] metadataBytes = serializeRedactedMetadata(redacted.sanitizedJson());

		Instant now = Instant.now();
		IntegrationLink inserted = integrationLinkRecordPort.insert(new NewIntegrationLink(
			publicId,
			workflowRunPublicId,
			LINEAR_INTEGRATION_TYPE,
			linearTicketRef,
			metadataBytes,
			now,
			now));
		idempotencyService.complete(idempotencyKey, inserted.publicId(), IdempotencyRecordStatus.COMPLETED);
		log.info(
			"linkTicket success workflowRunId={} externalRef={} integrationLinkPublicId={} effectiveClassification={}",
			workflowRunPublicId, linearTicketRef, inserted.publicId(),
			redacted.effectiveClassification().value());
		return inserted;
	}

	private void completeInIndependentTransaction(
		String idempotencyKey,
		String resultRef,
		IdempotencyRecordStatus status
	) {
		failureCompletionTemplate.execute(ignored -> {
			idempotencyService.complete(idempotencyKey, resultRef, status);
			return null;
		});
	}

	private static TransactionTemplate requiresNewTemplate(PlatformTransactionManager transactionManager) {
		Objects.requireNonNull(transactionManager, "transactionManager");
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	/**
	 * Look up the currently-active integration link for an external reference. Active =
	 * {@code archived_at IS NULL AND sync_status != 'superseded'}.
	 */
	public Optional<IntegrationLink> findActiveLink(String integrationType, String externalRef) {
		return integrationLinkRecordPort.findActiveByTypeAndExternalRef(integrationType, externalRef);
	}

	/**
	 * Look up the currently-active integration link for a workflow run.
	 */
	public Optional<IntegrationLink> findActiveLinkByWorkflowRun(String workflowRunPublicId) {
		return integrationLinkRecordPort.findActiveByWorkflowRun(workflowRunPublicId);
	}

	/** Transition {@code sync_status} {@code linked → synced} and refresh {@code last_sync_at}. */
	@Transactional
	public IntegrationLink markSynced(String integrationLinkPublicId, Instant syncedAt) {
		Objects.requireNonNull(syncedAt, "syncedAt");
		return integrationLinkRecordPort.updateSyncStatus(
			integrationLinkPublicId, IntegrationSyncStatus.SYNCED, syncedAt);
	}

	/** Transition the link to {@code stale}. Used by the polling loop when freshness thresholds are exceeded (AC9). */
	@Transactional
	public IntegrationLink markStale(String integrationLinkPublicId) {
		return integrationLinkRecordPort.updateSyncStatus(
			integrationLinkPublicId, IntegrationSyncStatus.STALE, null);
	}

	/**
	 * Transition the link to {@code failed} and emit a structured log line carrying the
	 * {@link IntegrationFailureCategory}.
	 */
	@Transactional
	public IntegrationLink markFailed(String integrationLinkPublicId, IntegrationFailureCategory category) {
		Objects.requireNonNull(category, "category");
		log.warn("integration_link mark_failed publicId={} category={}",
			integrationLinkPublicId, category.value());
		return integrationLinkRecordPort.updateSyncStatus(
			integrationLinkPublicId, IntegrationSyncStatus.FAILED, null);
	}

	static String computeFingerprint(String integrationType, String externalRef, String workflowRunPublicId) {
		String canonical = integrationType + "|" + externalRef + "|" + workflowRunPublicId;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException error) {
			// SHA-256 is mandatory in every Java distribution; defensive only.
			throw new IllegalStateException("SHA-256 not available", error);
		}
	}

	private static Map<String, Object> buildExternalMetadata(LinearTicket ticket) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("title", ticket.title());
		metadata.put("summary", ticket.summary());
		metadata.put("authorIdentity", ticket.authorIdentity());
		metadata.put("labels", ticket.labels());
		metadata.put("ticketCreatedAt", ticket.createdAt().toString());
		metadata.put("ticketUpdatedAt", ticket.updatedAt().toString());
		return metadata;
	}

	private byte[] serializeRedactedMetadata(JsonNode sanitizedJson) {
		if (sanitizedJson == null) {
			return "{}".getBytes(StandardCharsets.UTF_8);
		}
		try {
			return objectMapper.writeValueAsBytes(sanitizedJson);
		} catch (IOException error) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("reason", "redacted_metadata_serialization_failed");
			throw new DomainException(
				DomainErrorCode.INTERNAL_ERROR,
				"Failed to serialize redacted external_metadata for integration_links",
				details);
		}
	}

	private static DomainException linearTicketNotFound(String externalRef) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("integrationType", LINEAR_INTEGRATION_TYPE);
		details.put("externalRef", externalRef);
		return new DomainException(
			DomainErrorCode.LINEAR_TICKET_NOT_FOUND,
			"Linear ticket not found: " + externalRef,
			details);
	}

	private static DomainException crossRunConflict(String externalRef, IntegrationLink existing) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("externalRef", externalRef);
		details.put("existingIntegrationLinkPublicId", existing.publicId());
		details.put("existingRunPublicId", existing.workflowRunPublicId());
		details.put("reason", "cross_run_active_linear_link_exists");
		return new DomainException(
			DomainErrorCode.INTEGRATION_LINK_CONFLICT,
			"Linear ticket " + externalRef + " is already linked to run " + existing.workflowRunPublicId(),
			details);
	}

	private static DomainException adapterFailure(String externalRef, LinearAdapterException cause) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("externalRef", externalRef);
		details.put("failureCategory", cause.failureCategory().value());
		return new DomainException(
			DomainErrorCode.INTEGRATION_LINK_CONFLICT,
			"Linear adapter failure during linkTicket: " + cause.getMessage(),
			details);
	}

	private static DomainException replayedTerminalFailure(String idempotencyKey, String externalRef) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("idempotencyKey", idempotencyKey);
		details.put("externalRef", externalRef);
		details.put("reason", "prior_attempt_failed_terminally");
		return new DomainException(
			DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
			"Prior linkTicket attempt for this key failed terminally; submit with a fresh idempotency key",
			details);
	}

	private static DomainException replayedRecordMissing(String idempotencyKey, String publicId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("idempotencyKey", idempotencyKey);
		details.put("integrationLinkPublicId", publicId);
		details.put("reason", "integration_link_record_missing");
		return new DomainException(
			DomainErrorCode.INTERNAL_ERROR,
			"Idempotency replay references a missing integration_link row: " + publicId,
			details);
	}
}
