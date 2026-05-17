package org.dradgo.application.workflow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import com.fasterxml.jackson.databind.JsonNode;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.FailureDescription;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that materializes the CLI/REST inspection views for a workflow run (story
 * 1-15 Task 1, extended in story 1.18 Task 4). Read-only: never mutates state, never logs raw
 * event details.
 *
 * <p>Story 1.18 wires {@link RecoveryService#describeFailure(String)} into the {@code status}
 * surface so the previously-stubbed {@code nextSafeAction} now carries the real
 * {@code retry / await_manual_reconciliation / await_outcome / view_only} value, plus five
 * failure-diagnostic fields populated only when {@code currentState == Failed}.
 */
@Service
public class WorkflowInspectionService {

	private static final Logger log = LoggerFactory.getLogger(WorkflowInspectionService.class);

	private final WorkflowRunReadPort workflowRunReadPort;
	private final WorkflowEventReadPort workflowEventReadPort;
	private final ArtifactRecordPort artifactRecordPort;
	private final IntegrationLinkService integrationLinkService;
	private final RedactionPolicyService redactionPolicyService;
	private final RecoveryService recoveryService;

	public WorkflowInspectionService(
		WorkflowRunReadPort workflowRunReadPort,
		WorkflowEventReadPort workflowEventReadPort,
		ArtifactRecordPort artifactRecordPort,
		IntegrationLinkService integrationLinkService,
		RedactionPolicyService redactionPolicyService,
		RecoveryService recoveryService
	) {
		this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
		this.workflowEventReadPort = Objects.requireNonNull(workflowEventReadPort, "workflowEventReadPort");
		this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
		this.integrationLinkService = Objects.requireNonNull(integrationLinkService, "integrationLinkService");
		this.redactionPolicyService = Objects.requireNonNull(redactionPolicyService, "redactionPolicyService");
		this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
	}

	@Transactional(readOnly = true)
	public WorkflowStatusView getStatus(String workflowRunPublicId) {
		// Validate the public-id prefix BEFORE any logging or DB lookup so that:
		// (a) null/blank/wrong-prefix input surfaces a governed DomainException(INVALID_ID_PREFIX)
		//     instead of a raw NullPointerException or a misleading RUN_NOT_FOUND, and
		// (b) the input cannot carry control characters (CR/LF/TAB) into MDC or log interpolation
		//     because the registered SUFFIX_PATTERN restricts allowed characters to [A-Za-z0-9_-].
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
		try {
		log.info("inspecting workflow_run snapshot workflowRunId={}", workflowRunPublicId);
		WorkflowRunSnapshot run = workflowRunReadPort.findByPublicId(workflowRunPublicId)
			.orElseThrow(() -> runNotFound(workflowRunPublicId));

		Optional<WorkflowEventRecord> latest = workflowEventReadPort.findLatestByWorkflowRunPublicId(workflowRunPublicId);

		List<LatestArtifactView> latestArtifacts = new ArrayList<>();
		for (ArtifactType artifactType : ArtifactType.values()) {
			Optional<ArtifactRecordSnapshot> latestForType = artifactRecordPort
				.findLatestByWorkflowRunIdAndArtifactType(workflowRunPublicId, artifactType.value());
			latestForType.ifPresent(snapshot -> latestArtifacts.add(new LatestArtifactView(
				snapshot.artifactType().value(),
				snapshot.version(),
				snapshot.status().value())));
		}

		LinkedTicketView linkedTicket = integrationLinkService
			.findActiveLinkByWorkflowRun(workflowRunPublicId)
			.map(WorkflowInspectionService::toLinkedTicket)
			.orElse(null);

		FailureDescription failure = recoveryService.describeFailure(workflowRunPublicId);

		WorkflowStatusView view = new WorkflowStatusView(
			run.publicId(),
			run.currentState(),
			latest.map(WorkflowEventRecord::actorIdentity).orElse(null),
			latest.map(record -> record.actorType().value()).orElse(null),
			latest.map(record -> record.eventType().value()).orElse(null),
			latest.map(WorkflowEventRecord::createdAt).orElse(null),
			List.copyOf(latestArtifacts),
			linkedTicket,
			failure.failedStage(),
			failure.lastSuccessfulStage(),
			failure.failureTimestamp(),
			failure.failureCategory(),
			failure.lastActivityTimestamp(),
			failure.nextSafeAction());
		log.info("inspecting workflow_run snapshot success workflowRunId={} currentState={}",
			workflowRunPublicId, run.currentState().value());
		return view;
		} finally {
			MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
		}
	}

	@Transactional(readOnly = true)
	public WorkflowHistoryView listHistory(String workflowRunPublicId, OffsetDateTime sinceInclusive) {
		// See getStatus() for the prefix-validation rationale (covers governed error surface +
		// log-injection defense in one check).
		PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
		String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunPublicId);
		try {
		log.info("inspecting workflow_run history workflowRunId={} sinceInclusive={}",
			workflowRunPublicId, sinceInclusive);
		WorkflowRunSnapshot run = workflowRunReadPort.findByPublicId(workflowRunPublicId)
			.orElseThrow(() -> runNotFound(workflowRunPublicId));

		List<WorkflowEventRecord> events = workflowEventReadPort
			.listByWorkflowRunPublicId(run.publicId(), sinceInclusive);
		List<WorkflowEventView> rendered = new ArrayList<>(events.size());
		for (WorkflowEventRecord event : events) {
			rendered.add(new WorkflowEventView(
				event.publicId(),
				event.eventType().value(),
				event.priorState() == null ? null : event.priorState().value(),
				event.resultingState() == null ? null : event.resultingState().value(),
				event.actorIdentity(),
				event.actorType().value(),
				event.reason(),
				event.failureCategory() == null ? null : event.failureCategory().value(),
				event.interventionMarker(),
				event.createdAt(),
				redactDetails(filterAllowedDetails(event.details()))));
		}
		log.info("inspecting workflow_run history success workflowRunId={} eventCount={}",
			workflowRunPublicId, rendered.size());
		return new WorkflowHistoryView(run.publicId(), List.copyOf(rendered));
		} finally {
			MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
		}
	}

	/**
	 * Allow-listed keys that may flow from {@code workflow_events.details} into a rendered CLI
	 * payload (story 1-15 Task 4). Everything else is dropped before render. Sourced from
	 * {@link WorkflowEventDetailKeys#ALLOW_LISTED_KEYS} — the single source of truth shared with
	 * {@code RecoveryService} (producer), {@code workflow-history.v1.schema.json} (transport
	 * contract), and {@code WorkflowEventRepository} (PostgreSQL-native filter). Story 1.18
	 * review batch 3 (D1) centralized the keys.
	 *
	 * <p>{@code idempotencyKey} is intentionally omitted — it is operator-only and surfaced on
	 * stdout by {@code submit}/{@code retry}, never echoed through {@code status} or
	 * {@code history}. {@link WorkflowEventDetailKeys#SERVER_ONLY_KEYS} lists the stripped set.
	 */
	static final List<String> ALLOWED_DETAIL_KEYS = WorkflowEventDetailKeys.ALLOW_LISTED_KEYS;

	private Map<String, Object> redactDetails(Map<String, Object> filtered) {
		// Defense-in-depth: the allow-list above strips dangerous KEYS, but a caller could still
		// have written a secret VALUE into a permitted key (e.g. an operator pasting a token into
		// the ticket reference text). Run the filtered payload through the redaction policy as a
		// second gate. Per the architecture rule "redaction runs both when data is captured and
		// when data is exported", this is the export-side pass.
		if (filtered.isEmpty()) {
			return filtered;
		}
		RedactionResult result = redactionPolicyService.redact(
			filtered, DataClassification.SHAREABLE_REDACTED.value());
		JsonNode sanitized = result.sanitizedJson();
		if (sanitized == null || !sanitized.isObject()) {
			return filtered;
		}
		Map<String, Object> rebuilt = new LinkedHashMap<>();
		sanitized.fields().forEachRemaining(entry -> {
			JsonNode value = entry.getValue();
			if (value.isNull()) {
				rebuilt.put(entry.getKey(), null);
			} else if (value.isBoolean()) {
				rebuilt.put(entry.getKey(), value.booleanValue());
			} else if (value.isNumber()) {
				// Preserve numeric type instead of coercing to a String — schemas pin
				// `artifactVersion` / `contextVersion` as integers, and a future writer that
				// hands us a Double, BigInteger, or BigDecimal would otherwise produce a JSON
				// string that fails `workflow-history.v1` validation.
				rebuilt.put(entry.getKey(), value.numberValue());
			} else {
				rebuilt.put(entry.getKey(), value.asText());
			}
		});
		return rebuilt;
	}

	private static Map<String, Object> filterAllowedDetails(Map<String, Object> raw) {
		if (raw == null || raw.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> filtered = new LinkedHashMap<>();
		for (String key : ALLOWED_DETAIL_KEYS) {
			Object value = raw.get(key);
			if (value != null) {
				filtered.put(key, value);
			}
		}
		return filtered;
	}

	private static LinkedTicketView toLinkedTicket(IntegrationLink link) {
		return new LinkedTicketView(
			link.integrationType(),
			link.externalRef(),
			link.syncStatus().value());
	}

	private static DomainException runNotFound(String workflowRunPublicId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", workflowRunPublicId);
		return new DomainException(
			DomainErrorCode.RUN_NOT_FOUND,
			"Workflow run not found: " + workflowRunPublicId,
			details);
	}

	/**
	 * Application-facing status snapshot. Transport adapters render this view.
	 *
	 * <p>The five {@code failed*} / {@code last*Activity*} fields (story 1.18) are non-null only
	 * when {@code currentState == Failed}; on non-Failed runs they are all null and
	 * {@code nextSafeAction} reflects the canonical non-Failed value.
	 */
	public record WorkflowStatusView(
		String workflowRunId,
		WorkflowState currentState,
		String currentActorIdentity,
		String currentActorType,
		String lastEventType,
		OffsetDateTime lastEventAt,
		List<LatestArtifactView> latestArtifacts,
		LinkedTicketView linkedTicket,
		String failedStage,
		String lastSuccessfulStage,
		OffsetDateTime failureTimestamp,
		String failureCategory,
		OffsetDateTime lastActivityTimestamp,
		String nextSafeAction
	) {
	}

	public record LatestArtifactView(String artifactType, int version, String status) {
	}

	public record LinkedTicketView(String integrationType, String externalRef, String syncStatus) {
	}

	public record WorkflowHistoryView(String workflowRunId, List<WorkflowEventView> events) {
	}

	public record WorkflowEventView(
		String publicId,
		String eventType,
		String priorState,
		String resultingState,
		String actorIdentity,
		String actorType,
		String reason,
		String failureCategory,
		boolean interventionMarker,
		OffsetDateTime createdAt,
		Map<String, Object> details
	) {
	}
}
