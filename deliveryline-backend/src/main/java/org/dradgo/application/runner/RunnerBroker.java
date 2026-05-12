package org.dradgo.application.runner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.RecordArtifactOperationCommand;
import org.dradgo.application.artifact.RecordArtifactOperationResult;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.runner.spi.RunnerAdapter;
import org.dradgo.application.runner.spi.RunnerExecutionEventPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationError;
import org.dradgo.runnercontracts.ValidationErrorCode;
import org.dradgo.runnercontracts.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns runner lifecycle: idempotent {@link #dispatch dispatch} with replay; result handling
 * with the AC5 workflow-state split; scheduled {@link #scanForTimeouts timeout scan}; startup
 * {@link #recoverOnStartup orphan recovery}.
 *
 * <p>Registered as {@code @Component} (not {@code @Service}) so the
 * "application services must end in Service" architecture rule stays clean while the
 * story-fixed bean name remains {@code RunnerBroker}.
 */
@Component
public class RunnerBroker {

	private static final Logger log = LoggerFactory.getLogger(RunnerBroker.class);

	private static final List<RunnerExecutionStatus> ACTIVE_STATUSES =
		List.of(RunnerExecutionStatus.PENDING, RunnerExecutionStatus.RUNNING);

	private static final List<RunnerExecutionStatus> ALL_STATUSES =
		List.of(RunnerExecutionStatus.values());

	private final RunnerExecutionRecordPort recordPort;
	private final RunnerExecutionEventPort eventPort;
	private final RunnerExecutionService executionService;
	private final ContextBundleService contextBundleService;
	private final IdempotencyService idempotencyService;
	private final WorkflowTransitionService workflowTransitionService;
	private final ArtifactOperationService artifactOperationService;
	private final RunnerAdapter runnerAdapter;
	private final RunnerScratchStore scratchStore;
	private final RunnerContractValidator contractValidator;
	private final RunnerProperties runnerProperties;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate dispatchTransactionTemplate;
	private final TransactionTemplate perItemTransactionTemplate;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public RunnerBroker(
		RunnerExecutionRecordPort recordPort,
		RunnerExecutionEventPort eventPort,
		RunnerExecutionService executionService,
		ContextBundleService contextBundleService,
		IdempotencyService idempotencyService,
		WorkflowTransitionService workflowTransitionService,
		ArtifactOperationService artifactOperationService,
		RunnerAdapter runnerAdapter,
		RunnerScratchStore scratchStore,
		RunnerContractValidator contractValidator,
		RunnerProperties runnerProperties,
		PlatformTransactionManager transactionManager
	) {
		this(recordPort, eventPort, executionService, contextBundleService, idempotencyService,
			workflowTransitionService, artifactOperationService, runnerAdapter, scratchStore,
			contractValidator, runnerProperties,
			requiredTemplate(transactionManager),
			requiresNewTemplate(transactionManager),
			Clock.systemUTC());
	}

	RunnerBroker(
		RunnerExecutionRecordPort recordPort,
		RunnerExecutionEventPort eventPort,
		RunnerExecutionService executionService,
		ContextBundleService contextBundleService,
		IdempotencyService idempotencyService,
		WorkflowTransitionService workflowTransitionService,
		ArtifactOperationService artifactOperationService,
		RunnerAdapter runnerAdapter,
		RunnerScratchStore scratchStore,
		RunnerContractValidator contractValidator,
		RunnerProperties runnerProperties,
		TransactionTemplate dispatchTransactionTemplate,
		TransactionTemplate perItemTransactionTemplate,
		Clock clock
	) {
		this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
		this.eventPort = Objects.requireNonNull(eventPort, "eventPort");
		this.executionService = Objects.requireNonNull(executionService, "executionService");
		this.contextBundleService = Objects.requireNonNull(contextBundleService, "contextBundleService");
		this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
		this.workflowTransitionService = Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
		this.artifactOperationService = Objects.requireNonNull(artifactOperationService, "artifactOperationService");
		this.runnerAdapter = Objects.requireNonNull(runnerAdapter, "runnerAdapter");
		this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
		this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
		this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
		this.dispatchTransactionTemplate = Objects.requireNonNull(dispatchTransactionTemplate, "dispatchTransactionTemplate");
		this.perItemTransactionTemplate = Objects.requireNonNull(perItemTransactionTemplate, "perItemTransactionTemplate");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.objectMapper = new ObjectMapper();
	}

	private static TransactionTemplate requiredTemplate(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
		return template;
	}

	private static TransactionTemplate requiresNewTemplate(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	// =====================================================================
	// dispatch
	// =====================================================================

	public RunnerDispatchResult dispatch(
		String workflowRunId,
		RunnerStage stage,
		String idempotencyKey,
		ActorContext actor
	) {
		PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(actor, "actor");
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new IllegalArgumentException("idempotencyKey must not be blank");
		}

		String reservedRexId = PublicIdPrefixes.RUNNER_EXECUTION.next();
		int nextContextBundleVersion = recordPort.nextContextBundleVersion(workflowRunId, stage);
		String fingerprint = dispatchFingerprint(workflowRunId, stage, nextContextBundleVersion);

		ReservationOutcome reservation = idempotencyService.checkAndReserve(
			idempotencyKey, "RunnerBroker.dispatch", actor.actorIdentity(), fingerprint);

		if (reservation.decision() == ReservationDecision.REPLAY) {
			String priorRexId = reservation.resultRef();
			RunnerExecutionSnapshot prior = recordPort.findByPublicId(priorRexId)
				.orElseThrow(() -> idempotencyRecordLost(idempotencyKey, priorRexId));
			log.info("dispatch replay workflowRunId={} stage={} runnerExecutionId={} idempotencyKey={}",
				workflowRunId, stage.value(), priorRexId, idempotencyKey);
			return new RunnerDispatchResult.Replayed(toHandle(prior));
		}

		ExecutionConstraints constraints = new ExecutionConstraints(runnerProperties.timeoutFor(stage), false);

		ContextBundle bundle;
		try {
			bundle = contextBundleService.create(
				workflowRunId, stage, reservedRexId, nextContextBundleVersion,
				constraints, DataClassification.SHAREABLE_REDACTED, actor);
		} catch (DomainException error) {
			idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.FAILED);
			log.warn("dispatch context-bundle rejected workflowRunId={} stage={} runnerExecutionId={} errorCode={}",
				workflowRunId, stage.value(), reservedRexId, error.errorCode().value());
			throw error;
		}

		RunnerExecutionSnapshot inserted = dispatchTransactionTemplate.execute(status -> {
			RunnerExecutionSnapshot row = recordPort.insertPending(
				reservedRexId, workflowRunId, stage, nextContextBundleVersion, constraints);
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("runnerExecutionId", row.publicId());
			details.put("stage", stage.value());
			details.put("contextBundleVersion", row.contextBundleVersion());
			details.put("timeoutAt", row.timeoutAt().toString());
			details.put("idempotencyKey", idempotencyKey);
			eventPort.append(workflowRunId, WorkflowEventType.RUNNER_STARTED, actor, "runner_dispatched",
				null, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC), details);
			return row;
		});

		idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.COMPLETED);
		java.nio.file.Path bundlePath = scratchStore.writeContextBundle(reservedRexId, bundle.redactedPayload());

		RunnerDispatchRequest request = new RunnerDispatchRequest(
			reservedRexId, workflowRunId, stage, bundlePath, constraints, bundle.effectiveClassification());
		RunnerDispatchAck ack = runnerAdapter.dispatch(request);

		log.info("dispatch ok workflowRunId={} stage={} runnerExecutionId={} contextBundleVersion={} adapterRef={}",
			workflowRunId, stage.value(), reservedRexId, nextContextBundleVersion, ack.adapterRef());
		return new RunnerDispatchResult.Dispatched(toHandle(inserted), ack);
	}

	// =====================================================================
	// onResult
	// =====================================================================

	public void onResult(String runnerExecutionId, byte[] payloadBytes) {
		PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
		Objects.requireNonNull(payloadBytes, "payloadBytes");
		if (payloadBytes.length == 0) {
			throw new IllegalArgumentException("payloadBytes must not be empty");
		}

		RunnerExecutionSnapshot row = recordPort.findByPublicId(runnerExecutionId)
			.orElseThrow(() -> runnerExecutionNotFound(runnerExecutionId));
		String workflowRunId = row.workflowRunPublicId();

		// AC5 split (late-result branch): a row that has already been marked TIMED_OUT or ORPHANED
		// cannot legally transition further. A result arriving now is, by definition, late — emit
		// RUNNER_FAILED with runner_late_result and forward artifact references via
		// ArtifactOperationService (which already marks them late_or_stale per 1.12 AC10), but
		// do NOT change the workflow-run state. Operator-driven recovery owns that path.
		if (row.status() == RunnerExecutionStatus.TIMED_OUT || row.status() == RunnerExecutionStatus.ORPHANED) {
			handleLateResult(runnerExecutionId, workflowRunId, row, payloadBytes);
			return;
		}

		ValidationContext validationContext = buildResultValidationContext(workflowRunId, runnerExecutionId);
		ValidationResult result = contractValidator.validate(ValidationTarget.RUNNER_RESULT, payloadBytes, validationContext);

		if (!result.valid()) {
			FailureCategory category = classifyValidationFailure(result.errors());
			handleFailedValidation(runnerExecutionId, workflowRunId, row, category, result, payloadBytes);
			return;
		}

		JsonNode parsed;
		try {
			parsed = objectMapper.readTree(payloadBytes);
		} catch (java.io.IOException error) {
			handleFailedValidation(runnerExecutionId, workflowRunId, row, FailureCategory.RUNNER_MALFORMED_OUTPUT,
				new ValidationResult(false, List.of(new ValidationError(
					ValidationErrorCode.JSON_PARSE_FAILED, "$", error.getMessage()))),
				payloadBytes);
			return;
		}

		JsonNode failureCategoryNode = parsed.get("failureCategory");
		if (failureCategoryNode != null && failureCategoryNode.isTextual() && !failureCategoryNode.asText().isBlank()) {
			handleNonZeroExit(runnerExecutionId, workflowRunId, failureCategoryNode.asText());
			return;
		}

		handleSuccess(runnerExecutionId, workflowRunId, row, parsed);
	}

	private void handleSuccess(String runnerExecutionId, String workflowRunId, RunnerExecutionSnapshot row, JsonNode parsed) {
		JsonNode artifactRefs = parsed.path("artifactReferences");
		if (!artifactRefs.isArray() || artifactRefs.isEmpty()) {
			handleFailedValidation(runnerExecutionId, workflowRunId, row, FailureCategory.RUNNER_CONTRACT_VIOLATION,
				new ValidationResult(false, List.of(new ValidationError(
					ValidationErrorCode.SCHEMA_VALIDATION_FAILED, "/artifactReferences", "missing or empty"))),
				new byte[] {0});
			return;
		}

		String correlationId = UUID.randomUUID().toString();
		boolean artifactIngestionFailed = false;
		for (JsonNode ref : artifactRefs) {
			String typeValue = ref.path("artifactType").asText();
			ArtifactType artifactType = ArtifactType.fromValue(typeValue, "runner_result.artifactReferences.artifactType");
			String contentReference = ref.path("contentReference").asText(null);
			if (contentReference == null || contentReference.isBlank()) {
				handleFailedValidation(runnerExecutionId, workflowRunId, row, FailureCategory.RUNNER_CONTRACT_VIOLATION,
					new ValidationResult(false, List.of(new ValidationError(
						ValidationErrorCode.SCHEMA_VALIDATION_FAILED,
						"/artifactReferences/contentReference",
						"missing or blank"))),
					new byte[] {0});
				return;
			}
			Optional<byte[]> maybeBytes = scratchStore.tryReadArtifactContent(runnerExecutionId, contentReference);
			if (maybeBytes.isEmpty()) {
				handleFailedValidation(runnerExecutionId, workflowRunId, row, FailureCategory.RUNNER_CONTRACT_VIOLATION,
					new ValidationResult(false, List.of(new ValidationError(
						ValidationErrorCode.PATH_TRAVERSAL_DETECTED,
						"/artifactReferences/contentReference",
						"unreadable or escaping reference: " + contentReference))),
					new byte[] {0});
				return;
			}
			byte[] artifactBytes = maybeBytes.get();
			String payloadRef = leafFilename(contentReference);
			String idempotencyKey = "runner-result:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
			RecordArtifactOperationCommand command = new RecordArtifactOperationCommand(
				workflowRunId,
				artifactType,
				ArtifactOperationType.CREATE,
				idempotencyKey,
				payloadRef,
				artifactBytes,
				"system",
				org.dradgo.domain.registry.ActorType.SYSTEM,
				correlationId,
				runnerExecutionId);
			RecordArtifactOperationResult opResult = artifactOperationService.recordOperation(command);
			if (opResult.isFailure()) {
				log.warn("onResult artifact-record failed runnerExecutionId={} artifactType={} reason={}",
					runnerExecutionId, typeValue, opResult.failure());
				artifactIngestionFailed = true;
				break;
			}
		}

		if (artifactIngestionFailed) {
			// The runner produced a schema-valid result but at least one referenced artifact
			// could not be ingested. AC5 classifies this as a runner-side contract violation:
			// runner_contract_violation drives workflow run to FAILED.
			executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
			appendRunnerFailedEvent(workflowRunId, runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION,
				"artifact_ingestion_failed", ActorContext.SYSTEM);
			driveWorkflowFailed(workflowRunId, runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION,
				"artifact ingestion failed");
			return;
		}

		executionService.recordCompleted(runnerExecutionId);
		log.info("onResult success runnerExecutionId={} workflowRunId={} artifactCount={}",
			runnerExecutionId, workflowRunId, artifactRefs.size());
	}

	/** Last path segment of a relative reference (e.g. {@code "spec/v1.json"} → {@code "v1.json"}). */
	private static String leafFilename(String reference) {
		String normalized = reference.replace('\\', '/');
		int slash = normalized.lastIndexOf('/');
		return slash < 0 ? normalized : normalized.substring(slash + 1);
	}

	/**
	 * A result arriving for a row that is already in a non-result-bearing terminal state
	 * ({@code TIMED_OUT} or {@code ORPHANED}) is, by AC5, classified as
	 * {@code runner_late_result}: emit a precise {@code RUNNER_FAILED} event, route any
	 * artifact references via {@link ArtifactOperationService} for {@code late_or_stale}
	 * marking (story 1.12 AC10), and intentionally leave the workflow-run state alone.
	 */
	private void handleLateResult(
		String runnerExecutionId,
		String workflowRunId,
		RunnerExecutionSnapshot row,
		byte[] payloadBytes
	) {
		ValidationResult validation = contractValidator.validate(
			ValidationTarget.RUNNER_RESULT,
			payloadBytes,
			buildResultValidationContext(workflowRunId, runnerExecutionId));
		log.warn("onResult late result runnerExecutionId={} workflowRunId={} status={} payloadBytes={}",
			runnerExecutionId, workflowRunId, row.status().value(), payloadBytes.length);

		// Best-effort artifact harvest: parse the payload, route each artifactReference through
		// ArtifactOperationService. ArtifactOperationService's isTimedOut check tags artifacts as
		// late_or_stale automatically. We tolerate parse / schema failure here — the row is
		// already terminal, the event has been emitted, and the workflow-state path stays clean.
		if (validation.valid()) {
			try {
				JsonNode parsed = objectMapper.readTree(payloadBytes);
				JsonNode artifactRefs = parsed.path("artifactReferences");
				if (artifactRefs.isArray() && !artifactRefs.isEmpty()) {
					String correlationId = UUID.randomUUID().toString();
					for (JsonNode ref : artifactRefs) {
						try {
							String contentReference = ref.path("contentReference").asText(null);
							if (contentReference == null || contentReference.isBlank()) {
								continue;
							}
							Optional<byte[]> maybeBytes = scratchStore.tryReadArtifactContent(runnerExecutionId, contentReference);
							if (maybeBytes.isEmpty()) {
								continue;
							}
							String typeValue = ref.path("artifactType").asText();
							ArtifactType artifactType = ArtifactType.fromValue(typeValue, "runner_result.artifactReferences.artifactType");
							String idempotencyKey = "runner-result-late:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
							RecordArtifactOperationCommand command = new RecordArtifactOperationCommand(
								workflowRunId,
								artifactType,
								ArtifactOperationType.CREATE,
								idempotencyKey,
								leafFilename(contentReference),
								maybeBytes.get(),
								"system",
								org.dradgo.domain.registry.ActorType.SYSTEM,
								correlationId,
								runnerExecutionId);
							artifactOperationService.recordOperation(command);
						} catch (DomainException error) {
							log.warn("late-result artifact harvest skipped runnerExecutionId={} cause={}",
								runnerExecutionId, error.getMessage());
						}
					}
				}
			} catch (java.io.IOException error) {
				log.warn("late-result artifact harvest skipped runnerExecutionId={} reason=malformed_payload cause={}",
					runnerExecutionId, error.toString());
			}
		} else {
			log.warn("late-result payload rejected before artifact harvest runnerExecutionId={} errorCount={}",
				runnerExecutionId, validation.errors().size());
		}

		executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_LATE_RESULT);
		appendRunnerFailedEvent(workflowRunId, runnerExecutionId, FailureCategory.RUNNER_LATE_RESULT,
			"runner_late_result", ActorContext.SYSTEM);
	}

	private void handleNonZeroExit(String runnerExecutionId, String workflowRunId, String failureCategoryValue) {
		FailureCategory category = FailureCategory.fromValue(failureCategoryValue, "runner_result.failureCategory");
		FailureCategory effectiveCategory = category == FailureCategory.RUNNER_NON_ZERO_EXIT
			? category
			: FailureCategory.RUNNER_NON_ZERO_EXIT;
		executionService.recordFailed(runnerExecutionId, effectiveCategory);
		appendRunnerFailedEvent(workflowRunId, runnerExecutionId, effectiveCategory, "runner_non_zero_exit",
			ActorContext.SYSTEM);
		driveWorkflowFailed(workflowRunId, runnerExecutionId, effectiveCategory, "runner non-zero exit");
		log.warn("onResult non-zero exit runnerExecutionId={} workflowRunId={} reportedCategory={} effectiveCategory={}",
			runnerExecutionId, workflowRunId, category.value(), effectiveCategory.value());
	}

	private void handleFailedValidation(
		String runnerExecutionId,
		String workflowRunId,
		RunnerExecutionSnapshot row,
		FailureCategory category,
		ValidationResult result,
		byte[] payloadBytes
	) {
		boolean alreadyTerminal = isTerminal(row.status());
		if (!alreadyTerminal) {
			executionService.recordFailed(runnerExecutionId, category);
		}
		appendRunnerFailedEvent(workflowRunId, runnerExecutionId, category,
			alreadyTerminal ? "runner_late_or_duplicate" : "runner_result_rejected",
			ActorContext.SYSTEM);

		// AC5 split: only contract-violation / non-zero-exit / crash / timeout drive workflow state.
		// runner_malformed_output, runner_duplicate_result, runner_late_result do NOT change workflow state.
		if (category == FailureCategory.RUNNER_CONTRACT_VIOLATION
			|| category == FailureCategory.RUNNER_NON_ZERO_EXIT
			|| category == FailureCategory.RUNNER_CRASH
			|| category == FailureCategory.RUNNER_TIMEOUT) {
			driveWorkflowFailed(workflowRunId, runnerExecutionId, category, "runner result rejected");
		}
		log.warn("onResult validation failed runnerExecutionId={} workflowRunId={} category={} errorCount={} payloadBytes={}",
			runnerExecutionId, workflowRunId, category.value(), result.errors().size(), payloadBytes.length);
	}

	/**
	 * Build the ValidationContext for a runner result: every existing runner_execution row in the
	 * workflow run contributes its public id to {@code knownRunnerExecutionIds}; rows whose state
	 * indicates a prior result was already accepted by the broker contribute to
	 * {@code observedRunnerExecutionIds} so the validator can flag a 2nd arrival as
	 * {@code DUPLICATE_RUNNER_EXECUTION_ID}.
	 */
	private ValidationContext buildResultValidationContext(String workflowRunId, String runnerExecutionId) {
		ValidationContext.Builder builder = ValidationContext.builder();
		builder.addKnownRunnerExecutionId(runnerExecutionId);
		List<RunnerExecutionSnapshot> peers = recordPort.findByWorkflowRunPublicIdAndStatusIn(workflowRunId, ALL_STATUSES);
		for (RunnerExecutionSnapshot peer : peers) {
			builder.addKnownRunnerExecutionId(peer.publicId());
			if (priorResultReceived(peer)) {
				builder.addObservedRunnerExecutionId(peer.publicId());
			}
		}
		return builder.build();
	}

	/**
	 * True iff the row's terminal state implies a result payload was previously processed by the
	 * broker — i.e., either a successful result ({@code COMPLETED}) or a failed result whose
	 * failure category indicates the result file did arrive (validation rejection, non-zero-exit,
	 * malformed JSON, duplicate, or late). Categories that mean "no result file ever arrived"
	 * ({@code RUNNER_CRASH}, {@code RUNNER_TIMEOUT}, {@code ORPHAN}) intentionally return {@code false}
	 * so a subsequent late arrival can be classified by the late-result path rather than as a duplicate.
	 */
	private static boolean priorResultReceived(RunnerExecutionSnapshot snapshot) {
		if (snapshot.status() == RunnerExecutionStatus.COMPLETED) {
			return true;
		}
		if (snapshot.status() != RunnerExecutionStatus.FAILED) {
			return false;
		}
		FailureCategory category = snapshot.failureCategory();
		if (category == null) {
			return false;
		}
		return switch (category) {
			case RUNNER_CONTRACT_VIOLATION, RUNNER_NON_ZERO_EXIT, RUNNER_MALFORMED_OUTPUT,
				RUNNER_DUPLICATE_RESULT, RUNNER_LATE_RESULT -> true;
			case RUNNER_CRASH, RUNNER_TIMEOUT, ORPHAN -> false;
		};
	}

	private FailureCategory classifyValidationFailure(List<ValidationError> errors) {
		for (ValidationError error : errors) {
			if (error.code() == ValidationErrorCode.JSON_PARSE_FAILED) {
				return FailureCategory.RUNNER_MALFORMED_OUTPUT;
			}
			if (error.code() == ValidationErrorCode.DUPLICATE_RUNNER_EXECUTION_ID) {
				return FailureCategory.RUNNER_DUPLICATE_RESULT;
			}
		}
		return FailureCategory.RUNNER_CONTRACT_VIOLATION;
	}

	private void appendRunnerFailedEvent(
		String workflowRunId,
		String runnerExecutionId,
		FailureCategory category,
		String reason,
		ActorContext actor
	) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runnerExecutionId", runnerExecutionId);
		details.put("failureCategory", category.value());
		details.put("reason", reason);
		eventPort.append(workflowRunId, WorkflowEventType.RUNNER_FAILED, actor, reason, category,
			OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC), details);
	}

	private void driveWorkflowFailed(
		String workflowRunId,
		String runnerExecutionId,
		FailureCategory category,
		String reason
	) {
		TransitionActor transitionActor = RunnerActorTranslator.toTransitionActor(ActorContext.SYSTEM);
		try {
			workflowTransitionService.transition(
				workflowRunId,
				WorkflowState.FAILED,
				transitionActor,
				reason,
				"runner-broker:" + runnerExecutionId + ":" + category.value(),
				category,
				Map.of("runnerExecutionId", runnerExecutionId));
		} catch (DomainException error) {
			if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
				log.warn("driveWorkflowFailed swallowed ILLEGAL_TRANSITION workflowRunId={} runnerExecutionId={} reason={}",
					workflowRunId, runnerExecutionId, error.getMessage());
				return;
			}
			throw error;
		}
	}

	// =====================================================================
	// scanForTimeouts
	// =====================================================================

	public int scanForTimeouts() {
		List<RunnerExecutionSnapshot> stale = recordPort.findStaleByStatusInAndTimeoutAtBefore(
			ACTIVE_STATUSES, Duration.ZERO, runnerProperties.timeoutScanBatchSize());
		log.info("scanForTimeouts start candidates={}", stale.size());
		int flipped = 0;
		for (RunnerExecutionSnapshot snapshot : stale) {
			try {
				Boolean updated = perItemTransactionTemplate.execute(status -> processSingleTimeout(snapshot));
				if (Boolean.TRUE.equals(updated)) {
					flipped++;
				}
			} catch (Exception error) {
				log.error("scanForTimeouts item failed runnerExecutionId={} cause={}",
					snapshot.publicId(), error.toString());
			}
		}
		log.info("scanForTimeouts done candidates={} flipped={}", stale.size(), flipped);
		return flipped;
	}

	private boolean processSingleTimeout(RunnerExecutionSnapshot snapshot) {
		String runnerExecutionId = snapshot.publicId();
		Optional<RunnerExecutionSnapshot> fresh = recordPort.findByPublicId(runnerExecutionId);
		if (fresh.isEmpty() || isTerminal(fresh.get().status())) {
			return false;
		}
		// Heartbeat-race guard: re-validate timeout_at against now() inside the per-item
		// transaction. A heartbeat that bumped timeout_at after the initial scan must not be
		// clobbered to TIMED_OUT.
		OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
		OffsetDateTime freshTimeoutAt = fresh.get().timeoutAt();
		if (freshTimeoutAt == null || !freshTimeoutAt.isBefore(now)) {
			log.info("scanForTimeouts skipping runnerExecutionId={} reason=heartbeat_extended_deadline timeoutAt={} now={}",
				runnerExecutionId, freshTimeoutAt, now);
			return false;
		}
		executionService.recordTimedOut(runnerExecutionId);
		appendRunnerFailedEvent(snapshot.workflowRunPublicId(), runnerExecutionId,
			FailureCategory.RUNNER_TIMEOUT, "runner_timeout", ActorContext.SYSTEM);
		driveWorkflowFailed(snapshot.workflowRunPublicId(), runnerExecutionId,
			FailureCategory.RUNNER_TIMEOUT, "runner timeout");
		return true;
	}

	// =====================================================================
	// pollActiveExecutions — heartbeat + crash + result-harvest path
	// =====================================================================

	/**
	 * Walk active ({@code pending}/{@code running}) runner executions and consult
	 * {@link RunnerAdapter#poll(String)} for each. Drives {@code last_activity_at} forward on
	 * heartbeat touches, surfaces {@code runner_crash} (and other adapter-reported failure
	 * categories) per AC5, and harvests a present result file via {@link #onResult} so the
	 * happy-path can complete without external triggers.
	 */
	public int pollActiveExecutions() {
		int batchSize = runnerProperties.timeoutScanBatchSize();
		List<RunnerExecutionSnapshot> active = recordPort.findActiveStatuses(ACTIVE_STATUSES, batchSize);
		log.info("pollActiveExecutions start candidates={}", active.size());
		int processed = 0;
		for (RunnerExecutionSnapshot snapshot : active) {
			try {
				Boolean updated = perItemTransactionTemplate.execute(status -> processSinglePoll(snapshot));
				if (Boolean.TRUE.equals(updated)) {
					processed++;
				}
			} catch (Exception error) {
				log.error("pollActiveExecutions item failed runnerExecutionId={} cause={}",
					snapshot.publicId(), error.toString());
			}
		}
		log.info("pollActiveExecutions done candidates={} processed={}", active.size(), processed);
		return processed;
	}

	private boolean processSinglePoll(RunnerExecutionSnapshot snapshot) {
		String runnerExecutionId = snapshot.publicId();
		RunnerPollStatus pollStatus = runnerAdapter.poll(runnerExecutionId);
		return switch (pollStatus) {
			case RunnerPollStatus.Running ignored -> {
				executionService.touchActivity(
					runnerExecutionId,
					runnerProperties.staleThresholdFor(snapshot.stage()));
				yield true;
			}
			case RunnerPollStatus.Unknown ignored -> false;
			case RunnerPollStatus.HeartbeatTouched heartbeat -> {
				try {
					executionService.touchActivity(
						runnerExecutionId,
						heartbeat.activityTimestamp(),
						runnerProperties.staleThresholdFor(snapshot.stage()));
					log.info("poll heartbeat advanced runnerExecutionId={} activityTimestamp={}",
						runnerExecutionId, heartbeat.activityTimestamp());
					yield true;
				} catch (DomainException error) {
					if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
						log.warn("poll heartbeat ignored on terminal runnerExecutionId={} cause={}",
							runnerExecutionId, error.getMessage());
						yield false;
					}
					throw error;
				}
			}
			case RunnerPollStatus.Failed failed -> {
				handlePollFailure(snapshot, failed.failureCategory());
				yield true;
			}
			case RunnerPollStatus.Completed ignored -> harvestResultFromScratch(snapshot);
		};
	}

	private void handlePollFailure(RunnerExecutionSnapshot snapshot, FailureCategory category) {
		String runnerExecutionId = snapshot.publicId();
		String workflowRunId = snapshot.workflowRunPublicId();
		executionService.recordFailed(runnerExecutionId, category);
		appendRunnerFailedEvent(workflowRunId, runnerExecutionId, category,
			"runner_poll_failure", ActorContext.SYSTEM);
		// AC5 split: only the four "result-bearing or process-level" categories drive workflow state.
		if (category == FailureCategory.RUNNER_CRASH
			|| category == FailureCategory.RUNNER_TIMEOUT
			|| category == FailureCategory.RUNNER_CONTRACT_VIOLATION
			|| category == FailureCategory.RUNNER_NON_ZERO_EXIT) {
			driveWorkflowFailed(workflowRunId, runnerExecutionId, category,
				"runner poll reported " + category.value());
		}
		log.warn("poll failure runnerExecutionId={} workflowRunId={} category={}",
			runnerExecutionId, workflowRunId, category.value());
	}

	private boolean harvestResultFromScratch(RunnerExecutionSnapshot snapshot) {
		Optional<byte[]> result = scratchStore.tryReadRunnerResult(snapshot.publicId());
		if (result.isEmpty()) {
			return false;
		}
		onResult(snapshot.publicId(), result.get());
		return true;
	}

	// =====================================================================
	// recoverOnStartup
	// =====================================================================

	public int recoverOnStartup() {
		int batchSize = runnerProperties.recovery().batchSize();
		List<RunnerExecutionSnapshot> active = recordPort.findActiveStatuses(ACTIVE_STATUSES, batchSize);
		log.info("recoverOnStartup start candidates={}", active.size());
		int handled = 0;
		for (RunnerExecutionSnapshot snapshot : active) {
			try {
				Boolean processed = perItemTransactionTemplate.execute(status -> processOrphan(snapshot));
				if (Boolean.TRUE.equals(processed)) {
					handled++;
				}
			} catch (Exception error) {
				log.error("recoverOnStartup item failed runnerExecutionId={} cause={}",
					snapshot.publicId(), error.toString());
			}
		}
		log.info("recoverOnStartup done candidates={} handled={}", active.size(), handled);
		return handled;
	}

	private boolean processOrphan(RunnerExecutionSnapshot snapshot) {
		String runnerExecutionId = snapshot.publicId();
		Optional<byte[]> maybeResult = scratchStore.tryReadRunnerResult(runnerExecutionId);
		if (maybeResult.isPresent()) {
			ValidationContext context = ValidationContext.builder()
				.addKnownRunnerExecutionId(runnerExecutionId)
				.build();
			ValidationResult validation = contractValidator.validate(
				ValidationTarget.RUNNER_RESULT, maybeResult.get(), context);
			if (validation.valid()) {
				onResult(runnerExecutionId, maybeResult.get());
				log.info("recoverOnStartup resumed runnerExecutionId={}", runnerExecutionId);
				return true;
			}
		}
		executionService.recordOrphaned(runnerExecutionId);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runnerExecutionId", runnerExecutionId);
		details.put("failureCategory", FailureCategory.ORPHAN.value());
		details.put("reason", "broker_restart_orphan");
		eventPort.append(snapshot.workflowRunPublicId(), WorkflowEventType.RECOVERY_RECONCILED,
			ActorContext.SYSTEM, "broker_restart_orphan", FailureCategory.ORPHAN,
			OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC), details);
		log.warn("recoverOnStartup orphaned runnerExecutionId={} workflowRunId={}",
			runnerExecutionId, snapshot.workflowRunPublicId());
		return true;
	}

	// =====================================================================
	// helpers
	// =====================================================================

	private RunnerExecutionHandle toHandle(RunnerExecutionSnapshot snapshot) {
		return new RunnerExecutionHandle(
			snapshot.publicId(),
			snapshot.workflowRunPublicId(),
			snapshot.stage(),
			snapshot.status(),
			snapshot.timeoutAt());
	}

	private boolean isTerminal(RunnerExecutionStatus status) {
		return RunnerExecutionStateMachine.isTerminal(status);
	}

	private static String dispatchFingerprint(String workflowRunId, RunnerStage stage, int contextBundleVersion) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String composed = workflowRunId + "|" + stage.value() + "|" + contextBundleVersion;
			digest.update(composed.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 must be available in JDK 21", error);
		}
	}

	private static DomainException runnerExecutionNotFound(String runnerExecutionId) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runnerExecutionId", runnerExecutionId);
		return new DomainException(
			DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND,
			"Runner execution not found: " + runnerExecutionId,
			details);
	}

	private static DomainException idempotencyRecordLost(String idempotencyKey, String resultRef) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("idempotencyKey", idempotencyKey);
		details.put("resultRef", resultRef);
		return new DomainException(
			DomainErrorCode.IDEMPOTENCY_RECORD_LOST,
			"Idempotency replay pointed at missing runner execution: " + resultRef,
			details);
	}
}
