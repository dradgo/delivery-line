package org.dradgo.application.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.RecordArtifactOperationCommand;
import org.dradgo.application.artifact.RecordArtifactOperationResult;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
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
 * Owns runner lifecycle: idempotent {@link #dispatch dispatch} with replay; result handling with
 * the AC5 workflow-state split; scheduled {@link #scanForTimeouts timeout scan}; startup {@link
 * #recoverOnStartup orphan recovery}.
 *
 * <p>Registered as {@code @Component} (not {@code @Service}) so the "application services must end
 * in Service" architecture rule stays clean while the story-fixed bean name remains {@code
 * RunnerBroker}.
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
      PlatformTransactionManager transactionManager) {
    this(
        recordPort,
        eventPort,
        executionService,
        contextBundleService,
        idempotencyService,
        workflowTransitionService,
        artifactOperationService,
        runnerAdapter,
        scratchStore,
        contractValidator,
        runnerProperties,
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
      Clock clock) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.eventPort = Objects.requireNonNull(eventPort, "eventPort");
    this.executionService = Objects.requireNonNull(executionService, "executionService");
    this.contextBundleService =
        Objects.requireNonNull(contextBundleService, "contextBundleService");
    this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
    this.workflowTransitionService =
        Objects.requireNonNull(workflowTransitionService, "workflowTransitionService");
    this.artifactOperationService =
        Objects.requireNonNull(artifactOperationService, "artifactOperationService");
    this.runnerAdapter = Objects.requireNonNull(runnerAdapter, "runnerAdapter");
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.dispatchTransactionTemplate =
        Objects.requireNonNull(dispatchTransactionTemplate, "dispatchTransactionTemplate");
    this.perItemTransactionTemplate =
        Objects.requireNonNull(perItemTransactionTemplate, "perItemTransactionTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.objectMapper = new ObjectMapper();
  }

  private static TransactionTemplate requiredTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    return template;
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  // =====================================================================
  // dispatch
  // =====================================================================

  public RunnerDispatchResult dispatch(
      String workflowRunId, RunnerStage stage, String idempotencyKey, ActorContext actor) {
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(actor, "actor");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }

    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String reservedRexId = PublicIdPrefixes.RUNNER_EXECUTION.next();
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, reservedRexId);
    try {
      int nextContextBundleVersion = recordPort.nextContextBundleVersion(workflowRunId, stage);
      String fingerprint = dispatchFingerprint(workflowRunId, stage, nextContextBundleVersion);

      ReservationOutcome reservation =
          idempotencyService.checkAndReserve(
              idempotencyKey, "RunnerBroker.dispatch", actor.actorIdentity(), fingerprint);

      if (reservation.decision() == ReservationDecision.REPLAY) {
        String priorRexId = reservation.resultRef();
        RunnerExecutionSnapshot prior =
            recordPort
                .findByPublicId(priorRexId)
                .orElseThrow(() -> idempotencyRecordLost(idempotencyKey, priorRexId));
        log.info(
            "dispatch replay workflowRunId={} stage={} runnerExecutionId={} idempotencyKey={}",
            workflowRunId,
            stage.value(),
            priorRexId,
            idempotencyKey);
        return new RunnerDispatchResult.Replayed(toHandle(prior));
      }

      ExecutionConstraints constraints =
          new ExecutionConstraints(runnerProperties.timeoutFor(stage), false);

      ContextBundle bundle;
      try {
        bundle =
            contextBundleService.create(
                workflowRunId,
                stage,
                reservedRexId,
                nextContextBundleVersion,
                constraints,
                DataClassification.SHAREABLE_REDACTED,
                actor);
      } catch (DomainException error) {
        idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.FAILED);
        log.warn(
            "dispatch context-bundle rejected workflowRunId={} stage={} runnerExecutionId={} errorCode={}",
            workflowRunId,
            stage.value(),
            reservedRexId,
            error.errorCode().value());
        throw error;
      }

      // Story 3.2 AC8 (OQ-4): emit RUNNER_STARTED only when the active adapter does NOT take
      // over the dispatch-event family. Docker adapter takes over via RUNNER_DISPATCHED emitted
      // post-ack (below) so the audit trail stays single-source.
      boolean adapterTakesOverDispatchEvent =
          runnerAdapter instanceof RecoverableRunnerAdapter recoverable
              && recoverable.emitsDispatchedAfterAck();
      RunnerExecutionSnapshot inserted =
          dispatchTransactionTemplate.execute(
              status -> {
                RunnerExecutionSnapshot row =
                    recordPort.insertPending(
                        reservedRexId, workflowRunId, stage, nextContextBundleVersion, constraints);
                if (!adapterTakesOverDispatchEvent) {
                  Map<String, Object> details = new LinkedHashMap<>();
                  details.put("runnerExecutionId", row.publicId());
                  details.put("stage", stage.value());
                  details.put("contextBundleVersion", row.contextBundleVersion());
                  details.put("timeoutAt", row.timeoutAt().toString());
                  details.put("idempotencyKey", idempotencyKey);
                  eventPort.append(
                      workflowRunId,
                      WorkflowEventType.RUNNER_STARTED,
                      actor,
                      "runner_dispatched",
                      null,
                      OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                      details);
                }
                return row;
              });

      idempotencyService.complete(idempotencyKey, reservedRexId, IdempotencyRecordStatus.COMPLETED);
      java.nio.file.Path bundlePath =
          scratchStore.writeContextBundle(reservedRexId, bundle.redactedPayload());

      RunnerDispatchRequest request =
          new RunnerDispatchRequest(
              reservedRexId,
              workflowRunId,
              stage,
              runnerProperties.docker().defaultKind(),
              bundlePath,
              constraints,
              bundle.effectiveClassification());
      RunnerDispatchAck ack = runnerAdapter.dispatch(request);

      // Story 3.2 AC8: emit RUNNER_DISPATCHED on the docker path (replaces the legacy
      // RUNNER_STARTED). Mock path keeps the existing RUNNER_STARTED event (emitted above inside
      // the dispatchTransactionTemplate) — see OQ-4. Path discriminated by adapter ref prefix so
      // the broker stays adapter-type-agnostic.
      String runnerDispatchedEventPublicId =
          appendRunnerDispatchedEventIfDocker(
              workflowRunId, reservedRexId, request.runnerKind(), ack, actor);

      log.info(
          "dispatch ok workflowRunId={} stage={} runnerExecutionId={} contextBundleVersion={} adapterRef={}",
          workflowRunId,
          stage.value(),
          reservedRexId,
          nextContextBundleVersion,
          ack.adapterRef());
      return new RunnerDispatchResult.Dispatched(
          toHandle(inserted), ack, runnerDispatchedEventPublicId);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
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

    RunnerExecutionSnapshot row =
        recordPort
            .findByPublicId(runnerExecutionId)
            .orElseThrow(() -> runnerExecutionNotFound(runnerExecutionId));
    String workflowRunId = row.workflowRunPublicId();
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {

      // AC5 split (late-result branch): a row that has already been marked TIMED_OUT or ORPHANED
      // cannot legally transition further. A result arriving now is, by definition, late — emit
      // RUNNER_FAILED with runner_late_result and forward artifact references via
      // ArtifactOperationService (which already marks them late_or_stale per 1.12 AC10), but
      // do NOT change the workflow-run state. Operator-driven recovery owns that path.
      if (row.status() == RunnerExecutionStatus.TIMED_OUT
          || row.status() == RunnerExecutionStatus.ORPHANED) {
        handleLateResult(runnerExecutionId, workflowRunId, row, payloadBytes);
        return;
      }

      ValidationContext validationContext =
          buildResultValidationContext(workflowRunId, runnerExecutionId);
      ValidationResult result =
          contractValidator.validate(
              ValidationTarget.RUNNER_RESULT, payloadBytes, validationContext);

      if (!result.valid()) {
        FailureCategory category = classifyValidationFailure(result.errors());
        handleFailedValidation(
            runnerExecutionId, workflowRunId, row, category, result, payloadBytes);
        return;
      }

      JsonNode parsed;
      try {
        parsed = objectMapper.readTree(payloadBytes);
      } catch (java.io.IOException error) {
        handleFailedValidation(
            runnerExecutionId,
            workflowRunId,
            row,
            FailureCategory.RUNNER_MALFORMED_OUTPUT,
            new ValidationResult(
                false,
                List.of(
                    new ValidationError(
                        ValidationErrorCode.JSON_PARSE_FAILED, "$", error.getMessage()))),
            payloadBytes);
        return;
      }

      JsonNode failureCategoryNode = parsed.get("failureCategory");
      if (failureCategoryNode != null
          && failureCategoryNode.isTextual()
          && !failureCategoryNode.asText().isBlank()) {
        handleNonZeroExit(runnerExecutionId, workflowRunId, failureCategoryNode.asText());
        return;
      }

      handleSuccess(runnerExecutionId, workflowRunId, row, parsed);
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private void handleSuccess(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      JsonNode parsed) {
    JsonNode artifactRefs = parsed.path("artifactReferences");
    if (!artifactRefs.isArray() || artifactRefs.isEmpty()) {
      handleFailedValidation(
          runnerExecutionId,
          workflowRunId,
          row,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          new ValidationResult(
              false,
              List.of(
                  new ValidationError(
                      ValidationErrorCode.SCHEMA_VALIDATION_FAILED,
                      "/artifactReferences",
                      "missing or empty"))),
          new byte[] {0});
      return;
    }

    String correlationId = UUID.randomUUID().toString();
    boolean artifactIngestionFailed = false;
    for (JsonNode ref : artifactRefs) {
      String typeValue = ref.path("artifactType").asText();
      ArtifactType artifactType =
          ArtifactType.fromValue(typeValue, "runner_result.artifactReferences.artifactType");
      String contentReference = ref.path("contentReference").asText(null);
      if (contentReference == null || contentReference.isBlank()) {
        handleFailedValidation(
            runnerExecutionId,
            workflowRunId,
            row,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            new ValidationResult(
                false,
                List.of(
                    new ValidationError(
                        ValidationErrorCode.SCHEMA_VALIDATION_FAILED,
                        "/artifactReferences/contentReference",
                        "missing or blank"))),
            new byte[] {0});
        return;
      }
      Optional<byte[]> maybeBytes =
          scratchStore.tryReadArtifactContent(runnerExecutionId, contentReference);
      if (maybeBytes.isEmpty()) {
        handleFailedValidation(
            runnerExecutionId,
            workflowRunId,
            row,
            FailureCategory.RUNNER_CONTRACT_VIOLATION,
            new ValidationResult(
                false,
                List.of(
                    new ValidationError(
                        ValidationErrorCode.PATH_TRAVERSAL_DETECTED,
                        "/artifactReferences/contentReference",
                        "unreadable or escaping reference: " + contentReference))),
            new byte[] {0});
        return;
      }
      byte[] artifactBytes = maybeBytes.get();
      String payloadRef = leafFilename(contentReference);
      String idempotencyKey =
          "runner-result:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
      RecordArtifactOperationCommand command =
          new RecordArtifactOperationCommand(
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
        log.warn(
            "onResult artifact-record failed runnerExecutionId={} artifactType={} reason={}",
            runnerExecutionId,
            typeValue,
            opResult.failure());
        artifactIngestionFailed = true;
        break;
      }
    }

    if (artifactIngestionFailed) {
      // The runner produced a schema-valid result but at least one referenced artifact
      // could not be ingested. AC5 classifies this as a runner-side contract violation:
      // runner_contract_violation drives workflow run to FAILED.
      executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_CONTRACT_VIOLATION);
      appendRunnerFailedEvent(
          workflowRunId,
          runnerExecutionId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "artifact_ingestion_failed",
          ActorContext.SYSTEM);
      driveWorkflowFailed(
          workflowRunId,
          runnerExecutionId,
          FailureCategory.RUNNER_CONTRACT_VIOLATION,
          "artifact ingestion failed");
      return;
    }

    // Story 3.2a AC9: gate the RUNNER_COMPLETED append on the row ACTUALLY transitioning. A
    // duplicate onResult (recovery scratch-replay re-entering, or a concurrent harvest) on an
    // already-completed row throws ILLEGAL_TRANSITION here — skip the append so the lifecycle event
    // is emitted at most once per completion.
    try {
      executionService.recordCompleted(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "onResult complete skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return;
    }
    // Story 3.2 AC8: emit RUNNER_COMPLETED so the happy-path success has a dedicated audit-trail
    // entry instead of being inferred from the workflow-state transition only. Exit code is not
    // surfaced on the success path (the runner contract considers any payload with valid
    // artifactReferences a successful completion); operators trace failures via RUNNER_FAILED.
    appendRunnerCompletedEvent(workflowRunId, runnerExecutionId, null);
    log.info(
        "onResult success runnerExecutionId={} workflowRunId={} artifactCount={}",
        runnerExecutionId,
        workflowRunId,
        artifactRefs.size());
  }

  /**
   * Last path segment of a relative reference (e.g. {@code "spec/v1.json"} → {@code "v1.json"}).
   */
  private static String leafFilename(String reference) {
    String normalized = reference.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    return slash < 0 ? normalized : normalized.substring(slash + 1);
  }

  /**
   * A result arriving for a row that is already in a non-result-bearing terminal state ({@code
   * TIMED_OUT} or {@code ORPHANED}) is, by AC5, classified as {@code runner_late_result}: emit a
   * precise {@code RUNNER_FAILED} event, route any artifact references via {@link
   * ArtifactOperationService} for {@code late_or_stale} marking (story 1.12 AC10), and
   * intentionally leave the workflow-run state alone.
   */
  private void handleLateResult(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      byte[] payloadBytes) {
    ValidationResult validation =
        contractValidator.validate(
            ValidationTarget.RUNNER_RESULT,
            payloadBytes,
            buildResultValidationContext(workflowRunId, runnerExecutionId));
    log.warn(
        "onResult late result runnerExecutionId={} workflowRunId={} status={} payloadBytes={}",
        runnerExecutionId,
        workflowRunId,
        row.status().value(),
        payloadBytes.length);

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
              Optional<byte[]> maybeBytes =
                  scratchStore.tryReadArtifactContent(runnerExecutionId, contentReference);
              if (maybeBytes.isEmpty()) {
                continue;
              }
              String typeValue = ref.path("artifactType").asText();
              ArtifactType artifactType =
                  ArtifactType.fromValue(
                      typeValue, "runner_result.artifactReferences.artifactType");
              String idempotencyKey =
                  "runner-result-late:" + runnerExecutionId + ":" + ref.path("artifactId").asText();
              RecordArtifactOperationCommand command =
                  new RecordArtifactOperationCommand(
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
              log.warn(
                  "late-result artifact harvest skipped runnerExecutionId={} cause={}",
                  runnerExecutionId,
                  error.getMessage());
            }
          }
        }
      } catch (java.io.IOException error) {
        log.warn(
            "late-result artifact harvest skipped runnerExecutionId={} reason=malformed_payload cause={}",
            runnerExecutionId,
            error.toString());
      }
    } else {
      log.warn(
          "late-result payload rejected before artifact harvest runnerExecutionId={} errorCount={}",
          runnerExecutionId,
          validation.errors().size());
    }

    executionService.recordFailed(runnerExecutionId, FailureCategory.RUNNER_LATE_RESULT);
    appendRunnerFailedEvent(
        workflowRunId,
        runnerExecutionId,
        FailureCategory.RUNNER_LATE_RESULT,
        "runner_late_result",
        ActorContext.SYSTEM);
  }

  private void handleNonZeroExit(
      String runnerExecutionId, String workflowRunId, String failureCategoryValue) {
    FailureCategory category =
        FailureCategory.fromValue(failureCategoryValue, "runner_result.failureCategory");
    FailureCategory effectiveCategory =
        category == FailureCategory.RUNNER_NON_ZERO_EXIT
            ? category
            : FailureCategory.RUNNER_NON_ZERO_EXIT;
    executionService.recordFailed(runnerExecutionId, effectiveCategory);
    appendRunnerFailedEvent(
        workflowRunId,
        runnerExecutionId,
        effectiveCategory,
        "runner_non_zero_exit",
        ActorContext.SYSTEM);
    driveWorkflowFailed(
        workflowRunId, runnerExecutionId, effectiveCategory, "runner non-zero exit");
    log.warn(
        "onResult non-zero exit runnerExecutionId={} workflowRunId={} reportedCategory={} effectiveCategory={}",
        runnerExecutionId,
        workflowRunId,
        category.value(),
        effectiveCategory.value());
  }

  private void handleFailedValidation(
      String runnerExecutionId,
      String workflowRunId,
      RunnerExecutionSnapshot row,
      FailureCategory category,
      ValidationResult result,
      byte[] payloadBytes) {
    boolean alreadyTerminal = isTerminal(row.status());
    if (!alreadyTerminal) {
      executionService.recordFailed(runnerExecutionId, category);
    }
    appendRunnerFailedEvent(
        workflowRunId,
        runnerExecutionId,
        category,
        alreadyTerminal ? "runner_late_or_duplicate" : "runner_result_rejected",
        ActorContext.SYSTEM);

    // AC5 split: only contract-violation / non-zero-exit / crash / timeout drive workflow state.
    // runner_malformed_output, runner_duplicate_result, runner_late_result do NOT change workflow
    // state.
    if (category == FailureCategory.RUNNER_CONTRACT_VIOLATION
        || category == FailureCategory.RUNNER_NON_ZERO_EXIT
        || category == FailureCategory.RUNNER_CRASH
        || category == FailureCategory.RUNNER_TIMEOUT) {
      driveWorkflowFailed(workflowRunId, runnerExecutionId, category, "runner result rejected");
    }
    log.warn(
        "onResult validation failed runnerExecutionId={} workflowRunId={} category={} errorCount={} payloadBytes={}",
        runnerExecutionId,
        workflowRunId,
        category.value(),
        result.errors().size(),
        payloadBytes.length);
  }

  /**
   * Build the ValidationContext for a runner result: every existing runner_execution row in the
   * workflow run contributes its public id to {@code knownRunnerExecutionIds}; rows whose state
   * indicates a prior result was already accepted by the broker contribute to {@code
   * observedRunnerExecutionIds} so the validator can flag a 2nd arrival as {@code
   * DUPLICATE_RUNNER_EXECUTION_ID}.
   */
  private ValidationContext buildResultValidationContext(
      String workflowRunId, String runnerExecutionId) {
    ValidationContext.Builder builder = ValidationContext.builder();
    builder.addKnownRunnerExecutionId(runnerExecutionId);
    List<RunnerExecutionSnapshot> peers =
        recordPort.findByWorkflowRunPublicIdAndStatusIn(workflowRunId, ALL_STATUSES);
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
   * broker — i.e., either a successful result ({@code COMPLETED}) or a failed result whose failure
   * category indicates the result file did arrive (validation rejection, non-zero-exit, malformed
   * JSON, duplicate, or late). Categories that mean "no result file ever arrived" ({@code
   * RUNNER_CRASH}, {@code RUNNER_TIMEOUT}, {@code ORPHAN}) intentionally return {@code false} so a
   * subsequent late arrival can be classified by the late-result path rather than as a duplicate.
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
      case RUNNER_CONTRACT_VIOLATION,
          RUNNER_NON_ZERO_EXIT,
          RUNNER_MALFORMED_OUTPUT,
          RUNNER_DUPLICATE_RESULT,
          RUNNER_LATE_RESULT ->
          true;
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

  /**
   * Story 3.2 AC8: emit {@code runner.dispatched} on the docker path after the adapter's dispatch
   * ack returns. The mock path's {@code emitsDispatchedAfterAck()} returns {@code false}, so this
   * is a no-op under {@code runners.mock}.
   */
  private String appendRunnerDispatchedEventIfDocker(
      String workflowRunId,
      String runnerExecutionId,
      org.dradgo.domain.registry.RunnerKind runnerKind,
      RunnerDispatchAck ack,
      ActorContext actor) {
    if (!(runnerAdapter instanceof RecoverableRunnerAdapter recoverable)
        || !recoverable.emitsDispatchedAfterAck()) {
      return null;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("runnerKind", runnerKind.value());
    recoverable
        .findContainerIdFor(runnerExecutionId)
        .ifPresent(id -> details.put("containerId", id));
    String image = runnerProperties.docker().imageTagFor(runnerKind);
    if (image != null && !image.isBlank()) {
      details.put("image", RunnerProperties.Docker.redactImageTag(image));
    }
    details.put(
        "dispatchedAt", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).toString());
    // Story 3.2a AC10: capture the appended event's public id so the dispatch result can surface it
    // back to RecoveryService as the retry audit anchor.
    String dispatchedEventPublicId =
        eventPort.append(
            workflowRunId,
            WorkflowEventType.RUNNER_DISPATCHED,
            actor,
            "runner_dispatched",
            null,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            details);
    log.info(
        "RUNNER_DISPATCHED appended workflowRunId={} runnerExecutionId={} runnerKind={} adapterRef={} eventId={}",
        workflowRunId,
        runnerExecutionId,
        runnerKind.value(),
        ack.adapterRef(),
        dispatchedEventPublicId);
    return dispatchedEventPublicId;
  }

  /** Story 3.2 AC8: emit {@code runner.completed} after a successful happy-path result ingest. */
  private void appendRunnerCompletedEvent(
      String workflowRunId, String runnerExecutionId, Integer exitCode) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      recoverable
          .findContainerIdFor(runnerExecutionId)
          .ifPresent(id -> details.put("containerId", id));
    }
    if (exitCode != null) {
      details.put("exitCode", exitCode);
    }
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_COMPLETED,
        ActorContext.SYSTEM,
        "runner_completed",
        null,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    log.info(
        "RUNNER_COMPLETED appended workflowRunId={} runnerExecutionId={}",
        workflowRunId,
        runnerExecutionId);
  }

  private void appendRunnerFailedEvent(
      String workflowRunId,
      String runnerExecutionId,
      FailureCategory category,
      String reason,
      ActorContext actor) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", category.value());
    details.put("reason", reason);
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_FAILED,
        actor,
        reason,
        category,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  private void driveWorkflowFailed(
      String workflowRunId, String runnerExecutionId, FailureCategory category, String reason) {
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
        log.warn(
            "driveWorkflowFailed swallowed ILLEGAL_TRANSITION workflowRunId={} runnerExecutionId={} reason={}",
            workflowRunId,
            runnerExecutionId,
            error.getMessage());
        return;
      }
      throw error;
    }
  }

  // =====================================================================
  // scanForTimeouts
  // =====================================================================

  public int scanForTimeouts() {
    List<RunnerExecutionSnapshot> stale =
        recordPort.findStaleByStatusInAndTimeoutAtBefore(
            ACTIVE_STATUSES, Duration.ZERO, runnerProperties.timeoutScanBatchSize());
    log.info("scanForTimeouts start candidates={}", stale.size());
    int flipped = 0;
    for (RunnerExecutionSnapshot snapshot : stale) {
      try {
        Boolean updated =
            perItemTransactionTemplate.execute(status -> processSingleTimeout(snapshot));
        if (Boolean.TRUE.equals(updated)) {
          flipped++;
        }
      } catch (Exception error) {
        log.error(
            "scanForTimeouts item failed runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
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
      log.info(
          "scanForTimeouts skipping runnerExecutionId={} reason=heartbeat_extended_deadline timeoutAt={} now={}",
          runnerExecutionId,
          freshTimeoutAt,
          now);
      return false;
    }
    // Story 3.2 AC1: when the active adapter exposes the recovery sub-interface (the docker
    // path), issue a graceful stop + kill-after-grace BEFORE flipping the row to TIMED_OUT. The
    // mock adapter implements terminate as a no-op-y cancel(), so this stays safe under
    // runners.mock. Trap T1 — the heartbeat-race guard above runs FIRST.
    RecoverableRunnerAdapter.TerminationOutcome terminationOutcome =
        RecoverableRunnerAdapter.TerminationOutcome.UNKNOWN;
    Optional<String> containerId = Optional.empty();
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      containerId = recoverable.findContainerIdFor(runnerExecutionId);
      try {
        terminationOutcome = recoverable.terminate(runnerExecutionId, Duration.ofSeconds(10L));
        log.info(
            "scanForTimeouts terminate runnerExecutionId={} outcome={}",
            runnerExecutionId,
            terminationOutcome);
      } catch (RuntimeException terminateError) {
        log.error(
            "scanForTimeouts terminate threw unexpectedly runnerExecutionId={} cause={}",
            runnerExecutionId,
            terminateError.toString());
      }
    }
    // Story 3.2a code-review (2026-05-29): gate the TIMED_OUT flip on the row ACTUALLY
    // transitioning, mirroring recordCompleted/recordOrphaned. The isTerminal pre-check above is a
    // non-locking read; a concurrent onResult/recovery can move the row terminal between that read
    // and recordTimedOut. recordTimedOut re-reads under a write lock and throws ILLEGAL_TRANSITION
    // if the row is already terminal — catch it and skip the event + workflow-fail so a
    // scan-vs-completion race cannot emit a duplicate runner.timeout or double-fail the run. The
    // terminate() above already ran (best-effort stop of a past-deadline container); only the
    // bookkeeping is gated.
    try {
      executionService.recordTimedOut(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "scanForTimeouts timeout skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return false;
    }
    appendRunnerTimeoutEvent(
        snapshot.workflowRunPublicId(),
        runnerExecutionId,
        containerId.orElse(null),
        terminationOutcome,
        freshTimeoutAt);
    driveWorkflowFailed(
        snapshot.workflowRunPublicId(),
        runnerExecutionId,
        FailureCategory.RUNNER_TIMEOUT,
        "runner timeout");
    return true;
  }

  private void appendRunnerTimeoutEvent(
      String workflowRunId,
      String runnerExecutionId,
      String containerId,
      RecoverableRunnerAdapter.TerminationOutcome terminationOutcome,
      OffsetDateTime timeoutAt) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    if (containerId != null) {
      details.put("containerId", containerId);
    }
    details.put("failureCategory", FailureCategory.RUNNER_TIMEOUT.value());
    if (timeoutAt != null) {
      details.put("timeoutAt", timeoutAt.toString());
    }
    details.put("terminationOutcome", terminationOutcome.name());
    eventPort.append(
        workflowRunId,
        WorkflowEventType.RUNNER_TIMEOUT,
        ActorContext.SYSTEM,
        "runner_timeout",
        FailureCategory.RUNNER_TIMEOUT,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
  }

  // =====================================================================
  // scanForStaleExecutions — heartbeat-stale warn + orphan flip (story 3.2 AC3)
  // =====================================================================

  /**
   * Story 3.2 AC3: scheduled scan that emits {@code runner.heartbeatStale} once per stale-window
   * for rows past {@code 1 × stage_timeout} but inside {@code 2 × stage_timeout}, and flips rows
   * past {@code 2 × stage_timeout} to {@code orphaned} with {@code runner.orphaned}.
   *
   * <p>The {@code heartbeat_stale_emitted_at} column gates re-emission (Trap T4 / OQ-1). The orphan
   * flip does NOT drive workflow state — Epic 4 recovery decides reconcile vs. fail-forward (AC3
   * sub-bullet (c)).
   */
  public int scanForStaleExecutions() {
    log.info("scanForStaleExecutions start");
    int heartbeatWarnings = 0;
    int orphanFlips = 0;
    int batchSize = runnerProperties.timeoutScanBatchSize();
    Duration baselineWindow = runnerProperties.timeoutFor(RunnerStage.INVESTIGATION);
    for (RunnerStage stage : RunnerStage.values()) {
      Duration stageTimeout = runnerProperties.timeoutFor(stage);
      Duration orphanThreshold = runnerProperties.staleThresholdFor(stage);
      // Phase 1 — heartbeat-stale WARN per row (idempotent on heartbeat_stale_emitted_at).
      // Story 3.2a AC2: the query is stage-scoped so the per-stage LIMIT cannot be exhausted by a
      // backlog of another stage (no cross-stage starvation).
      List<RunnerExecutionSnapshot> heartbeatStaleCandidates =
          recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
              ACTIVE_STATUSES, stage, stageTimeout, batchSize);
      for (RunnerExecutionSnapshot snapshot : heartbeatStaleCandidates) {
        try {
          Boolean emitted =
              perItemTransactionTemplate.execute(
                  status -> processHeartbeatStale(snapshot, orphanThreshold));
          if (Boolean.TRUE.equals(emitted)) {
            heartbeatWarnings++;
          }
        } catch (Exception error) {
          log.error(
              "scanForStaleExecutions heartbeat-stale item failed runnerExecutionId={} cause={}",
              snapshot.publicId(),
              error.toString());
        }
      }
      // Phase 2 — orphan flip per row past the orphan threshold (also stage-scoped, AC2).
      List<RunnerExecutionSnapshot> orphanCandidates =
          recordPort.findStaleByStatusInAndStageAndLastActivityAtBefore(
              ACTIVE_STATUSES, stage, orphanThreshold, batchSize);
      for (RunnerExecutionSnapshot snapshot : orphanCandidates) {
        try {
          Boolean flipped =
              perItemTransactionTemplate.execute(status -> processStaleOrphan(snapshot));
          if (Boolean.TRUE.equals(flipped)) {
            orphanFlips++;
          }
        } catch (Exception error) {
          log.error(
              "scanForStaleExecutions orphan item failed runnerExecutionId={} cause={}",
              snapshot.publicId(),
              error.toString());
        }
      }
      // Best-effort progress signal across stages.
      log.debug(
          "scanForStaleExecutions stage progress stage={} stageTimeout={} orphanThreshold={} baseline={}",
          stage.value(),
          stageTimeout,
          orphanThreshold,
          baselineWindow);
    }
    log.info(
        "scanForStaleExecutions done heartbeatWarnings={} orphanFlips={}",
        heartbeatWarnings,
        orphanFlips);
    return heartbeatWarnings + orphanFlips;
  }

  private boolean processHeartbeatStale(
      RunnerExecutionSnapshot snapshot, Duration orphanThreshold) {
    String runnerExecutionId = snapshot.publicId();
    Optional<RunnerExecutionSnapshot> fresh = recordPort.findByPublicId(runnerExecutionId);
    if (fresh.isEmpty() || isTerminal(fresh.get().status())) {
      return false;
    }
    RunnerExecutionSnapshot current = fresh.get();
    if (current.heartbeatStaleEmittedAt() != null) {
      return false;
    }
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime orphanCutoff = now.minus(orphanThreshold).withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime lastActivity = current.lastActivityAt();
    // Story 3.2a AC9: handle a null last_activity_at explicitly rather than silently conflating it
    // with "past the orphan threshold". A live row should always carry last_activity_at (set on
    // insert); a null here is anomalous, so we skip the heartbeat-stale emission and surface the
    // anomaly distinctly instead of guessing the row is orphan-eligible.
    if (lastActivity == null) {
      log.warn(
          "scanForStaleExecutions heartbeat-stale skip runnerExecutionId={} reason=null_last_activity_at",
          runnerExecutionId);
      return false;
    }
    if (lastActivity.isBefore(orphanCutoff)) {
      // Past the orphan threshold — orphan phase will handle it, do not double-emit.
      return false;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("lastActivityAt", lastActivity.toString());
    details.put("reason", "heartbeat_stale");
    eventPort.append(
        current.workflowRunPublicId(),
        WorkflowEventType.RUNNER_HEARTBEAT_STALE,
        ActorContext.SYSTEM,
        "heartbeat_stale",
        null,
        now,
        details);
    recordPort.markHeartbeatStaleEmitted(runnerExecutionId, now);
    log.warn(
        "RUNNER_HEARTBEAT_STALE appended runnerExecutionId={} workflowRunId={} lastActivityAt={}",
        runnerExecutionId,
        current.workflowRunPublicId(),
        lastActivity);
    return true;
  }

  private boolean processStaleOrphan(RunnerExecutionSnapshot snapshot) {
    String runnerExecutionId = snapshot.publicId();
    Optional<RunnerExecutionSnapshot> fresh = recordPort.findByPublicId(runnerExecutionId);
    if (fresh.isEmpty() || isTerminal(fresh.get().status())) {
      return false;
    }
    RunnerExecutionSnapshot current = fresh.get();
    // Story 3.2a AC9: gate the RUNNER_ORPHANED append on the row ACTUALLY transitioning. The
    // isTerminal pre-check above is a non-locking read; a concurrent poll/recovery can move the row
    // terminal between that read and this guarded transition. recordOrphaned re-reads under a write
    // lock and throws ILLEGAL_TRANSITION if the row is already terminal — catch it and skip the
    // append so a scan-vs-recovery race cannot emit a duplicate lifecycle event.
    try {
      executionService.recordOrphaned(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "scanForStaleExecutions orphan skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return false;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("failureCategory", FailureCategory.ORPHAN.value());
    details.put("reason", "lease_expired");
    if (current.lastActivityAt() != null) {
      details.put("lastActivityAt", current.lastActivityAt().toString());
    }
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      recoverable
          .findContainerIdFor(runnerExecutionId)
          .ifPresent(id -> details.put("containerId", id));
    }
    eventPort.append(
        snapshot.workflowRunPublicId(),
        WorkflowEventType.RUNNER_ORPHANED,
        ActorContext.SYSTEM,
        "lease_expired",
        FailureCategory.ORPHAN,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        details);
    log.warn(
        "RUNNER_ORPHANED appended runnerExecutionId={} workflowRunId={} lastActivityAt={}",
        runnerExecutionId,
        snapshot.workflowRunPublicId(),
        current.lastActivityAt());
    return true;
  }

  // =====================================================================
  // pollActiveExecutions — heartbeat + crash + result-harvest path
  // =====================================================================

  /**
   * Walk active ({@code pending}/{@code running}) runner executions and consult {@link
   * RunnerAdapter#poll(String)} for each. Drives {@code last_activity_at} forward on heartbeat
   * touches, surfaces {@code runner_crash} (and other adapter-reported failure categories) per AC5,
   * and harvests a present result file via {@link #onResult} so the happy-path can complete without
   * external triggers.
   */
  public int pollActiveExecutions() {
    int batchSize = runnerProperties.timeoutScanBatchSize();
    List<RunnerExecutionSnapshot> active =
        recordPort.findActiveStatuses(ACTIVE_STATUSES, batchSize);
    log.info("pollActiveExecutions start candidates={}", active.size());
    int processed = 0;
    for (RunnerExecutionSnapshot snapshot : active) {
      try {
        Boolean updated = perItemTransactionTemplate.execute(status -> processSinglePoll(snapshot));
        if (Boolean.TRUE.equals(updated)) {
          processed++;
        }
      } catch (Exception error) {
        log.error(
            "pollActiveExecutions item failed runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
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
            runnerExecutionId, runnerProperties.staleThresholdFor(snapshot.stage()));
        yield true;
      }
      case RunnerPollStatus.Unknown ignored -> false;
      case RunnerPollStatus.HeartbeatTouched heartbeat -> {
        try {
          executionService.touchActivity(
              runnerExecutionId,
              heartbeat.activityTimestamp(),
              runnerProperties.staleThresholdFor(snapshot.stage()));
          log.info(
              "poll heartbeat advanced runnerExecutionId={} activityTimestamp={}",
              runnerExecutionId,
              heartbeat.activityTimestamp());
          yield true;
        } catch (DomainException error) {
          if (error.errorCode() == DomainErrorCode.ILLEGAL_TRANSITION) {
            log.warn(
                "poll heartbeat ignored on terminal runnerExecutionId={} cause={}",
                runnerExecutionId,
                error.getMessage());
            yield false;
          }
          throw error;
        }
      }
      case RunnerPollStatus.Failed failed -> {
        handlePollFailure(snapshot, failed.failureCategory());
        yield true;
      }
      case RunnerPollStatus.Completed ignored -> harvestResultFromAdapter(snapshot);
    };
  }

  private void handlePollFailure(RunnerExecutionSnapshot snapshot, FailureCategory category) {
    String runnerExecutionId = snapshot.publicId();
    String workflowRunId = snapshot.workflowRunPublicId();
    executionService.recordFailed(runnerExecutionId, category);
    appendRunnerFailedEvent(
        workflowRunId, runnerExecutionId, category, "runner_poll_failure", ActorContext.SYSTEM);
    // AC5 split: only the four "result-bearing or process-level" categories drive workflow state.
    if (category == FailureCategory.RUNNER_CRASH
        || category == FailureCategory.RUNNER_TIMEOUT
        || category == FailureCategory.RUNNER_CONTRACT_VIOLATION
        || category == FailureCategory.RUNNER_NON_ZERO_EXIT) {
      driveWorkflowFailed(
          workflowRunId, runnerExecutionId, category, "runner poll reported " + category.value());
    }
    log.warn(
        "poll failure runnerExecutionId={} workflowRunId={} category={}",
        runnerExecutionId,
        workflowRunId,
        category.value());
  }

  private boolean harvestResultFromAdapter(RunnerExecutionSnapshot snapshot) {
    Optional<byte[]> result = runnerAdapter.tryReadResult(snapshot.publicId());
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
    List<RunnerExecutionSnapshot> active =
        recordPort.findActiveStatuses(ACTIVE_STATUSES, batchSize);
    log.info("recoverOnStartup start candidates={}", active.size());
    int handled = 0;
    for (RunnerExecutionSnapshot snapshot : active) {
      try {
        Boolean processed = perItemTransactionTemplate.execute(status -> processOrphan(snapshot));
        if (Boolean.TRUE.equals(processed)) {
          handled++;
        }
      } catch (Exception error) {
        log.error(
            "recoverOnStartup item failed runnerExecutionId={} cause={}",
            snapshot.publicId(),
            error.toString());
      }
    }
    log.info("recoverOnStartup done candidates={} handled={}", active.size(), handled);
    return handled;
  }

  private boolean processOrphan(RunnerExecutionSnapshot snapshot) {
    String runnerExecutionId = snapshot.publicId();
    // Trap T6: scratch-replay branch FIRST so a mock-runner happy-path that completed during JVM
    // downtime resumes via the scratch leaf file, exactly as story 1.13 / 3.1 defined.
    Optional<byte[]> maybeResult = scratchStore.tryReadRunnerResult(runnerExecutionId);
    if (maybeResult.isPresent()) {
      ValidationContext context =
          ValidationContext.builder().addKnownRunnerExecutionId(runnerExecutionId).build();
      ValidationResult validation =
          contractValidator.validate(ValidationTarget.RUNNER_RESULT, maybeResult.get(), context);
      if (validation.valid()) {
        onResult(runnerExecutionId, maybeResult.get());
        log.info("recoverOnStartup resumed runnerExecutionId={} via=scratch", runnerExecutionId);
        return true;
      }
    }

    // Story 3.2 AC4 / Trap T5: docker recovery probe SECOND — only when the active adapter
    // exposes the RecoverableRunnerAdapter sub-interface AND returns a non-empty handle. Mock
    // returns empty so this branch is a no-op under runners.mock.
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverable) {
      Optional<RunnerPollStatus> recovered = recoverable.recoverHandle(runnerExecutionId);
      if (recovered.isPresent()) {
        RunnerPollStatus status = recovered.get();
        switch (status) {
          case RunnerPollStatus.Running ignored -> {
            // Review decision D2: re-arm the lease on recovery. AC4 literally says "no row change"
            // for a recovered-running container, but its pre-crash timeout_at may already be
            // elapsed after a long broker outage, so the next scanForTimeouts would immediately
            // kill a genuinely-alive container. Refresh last_activity_at to now so the recovered
            // container gets a fresh stage window. (Documented deviation from AC4.)
            OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            try {
              executionService.touchActivity(
                  runnerExecutionId, nowUtc, runnerProperties.staleThresholdFor(snapshot.stage()));
            } catch (DomainException terminalRow) {
              // touchActivity on terminal row — next pollActiveExecutions tick handles it.
            }
            log.info(
                "recoverOnStartup resumed runnerExecutionId={} via=docker_probe status=running rearmed=true",
                runnerExecutionId);
            return false;
          }
          case RunnerPollStatus.HeartbeatTouched heartbeat -> {
            OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            // P12: clamp a future-dated engine StartedAt / filesystem mtime to the broker clock so
            // the deadline is not over-extended past now (engine/FS clocks may run ahead).
            OffsetDateTime activity = heartbeat.activityTimestamp();
            OffsetDateTime clamped = activity.isAfter(nowUtc) ? nowUtc : activity;
            try {
              executionService.touchActivity(
                  runnerExecutionId, clamped, runnerProperties.staleThresholdFor(snapshot.stage()));
            } catch (DomainException ignored) {
              // touchActivity on terminal row — ignore, the next pollActiveExecutions tick handles
              // it.
            }
            log.info(
                "recoverOnStartup resumed runnerExecutionId={} via=docker_probe status=heartbeat",
                runnerExecutionId);
            return false;
          }
          case RunnerPollStatus.Completed ignored -> {
            boolean harvested = harvestResultFromAdapter(snapshot);
            log.info(
                "recoverOnStartup harvested runnerExecutionId={} via=docker_probe harvested={}",
                runnerExecutionId,
                harvested);
            return harvested;
          }
          case RunnerPollStatus.Failed failed -> {
            handlePollFailure(snapshot, failed.failureCategory());
            log.info(
                "recoverOnStartup failed runnerExecutionId={} via=docker_probe category={}",
                runnerExecutionId,
                failed.failureCategory().value());
            return true;
          }
          case RunnerPollStatus.Unknown ignored -> {
            // Fall through to the orphan flip.
          }
        }
      } else {
        log.warn(
            "recoverOnStartup docker probe no-container-found runnerExecutionId={} workflowRunId={}",
            runnerExecutionId,
            snapshot.workflowRunPublicId());
      }
    }

    // Final fallback: flip to orphaned + emit RUNNER_ORPHANED (and keep RECOVERY_RECONCILED for
    // the recovery-axis audit per AC8 Trap T10 — they are complementary).
    // Story 3.2a AC9: gate the dual append on the row actually transitioning so a concurrent
    // scan-vs-recovery race (or a scratch result landing between the read above and here) cannot
    // append duplicate RUNNER_ORPHANED / RECOVERY_RECONCILED events.
    try {
      executionService.recordOrphaned(runnerExecutionId);
    } catch (DomainException raceTerminal) {
      if (raceTerminal.errorCode() != DomainErrorCode.ILLEGAL_TRANSITION) {
        throw raceTerminal;
      }
      log.info(
          "recoverOnStartup orphan skip runnerExecutionId={} reason=already_terminal errorCode={}",
          runnerExecutionId,
          raceTerminal.errorCode().value());
      return false;
    }
    Map<String, Object> orphanDetails = new LinkedHashMap<>();
    orphanDetails.put("runnerExecutionId", runnerExecutionId);
    orphanDetails.put("failureCategory", FailureCategory.ORPHAN.value());
    orphanDetails.put("reason", "broker_restart_orphan");
    if (runnerAdapter instanceof RecoverableRunnerAdapter recoverableForId) {
      recoverableForId
          .findContainerIdFor(runnerExecutionId)
          .ifPresent(id -> orphanDetails.put("containerId", id));
    }
    eventPort.append(
        snapshot.workflowRunPublicId(),
        WorkflowEventType.RUNNER_ORPHANED,
        ActorContext.SYSTEM,
        "broker_restart_orphan",
        FailureCategory.ORPHAN,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        orphanDetails);
    Map<String, Object> recoveryDetails = new LinkedHashMap<>();
    recoveryDetails.put("runnerExecutionId", runnerExecutionId);
    recoveryDetails.put("failureCategory", FailureCategory.ORPHAN.value());
    recoveryDetails.put("reason", "broker_restart_orphan");
    eventPort.append(
        snapshot.workflowRunPublicId(),
        WorkflowEventType.RECOVERY_RECONCILED,
        ActorContext.SYSTEM,
        "broker_restart_orphan",
        FailureCategory.ORPHAN,
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        recoveryDetails);
    log.warn(
        "recoverOnStartup orphaned runnerExecutionId={} workflowRunId={}",
        runnerExecutionId,
        snapshot.workflowRunPublicId());
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

  private static String dispatchFingerprint(
      String workflowRunId, RunnerStage stage, int contextBundleVersion) {
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
