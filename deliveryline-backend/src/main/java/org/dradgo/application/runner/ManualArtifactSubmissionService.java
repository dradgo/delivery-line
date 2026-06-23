package org.dradgo.application.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerExecutionSnapshot;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationError;
import org.dradgo.runnercontracts.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3d-4 (AC2/AC4/AC5 / R2, R6) — the operator-submission path for a parked {@code
 * WaitingForManualExecution} run. It owns the <strong>applicability + validation +
 * idempotency</strong> gate (no state change on failure), then delegates the <strong>ingest +
 * complete + advance</strong> to {@link RunnerBroker#ingestManualResult} (which reuses the broker's
 * battle-tested artifact-ingest core minus the workspace-coupled secret-scan / captureAndPush steps
 * a manual park never created).
 *
 * <p>Ordering is deliberate so that (a) an honest retry of an already-COMPLETED submission replays
 * even after the run has left {@code WaitingForManualExecution}, and (b) any mid-sequence failure
 * is a pure no-op on persisted state (AC5 — an invalid artifact leaves the run parked +
 * resubmittable):
 *
 * <ol>
 *   <li>verify the run exists (else {@link DomainErrorCode#RUN_NOT_FOUND} — wins over a
 *       fabricated-run replay);
 *   <li>reserve idempotency <strong>FIRST</strong> (fingerprint includes the artifact bytes — a
 *       different artifact under one key conflicts; an honest retry replays). This must precede the
 *       applicability gate so a same-key retry of an already-advanced run REPLAYs the prior result
 *       instead of being rejected by the gate below;
 *   <li>applicability gate — resolve the run's single active {@code awaiting_manual}
 *       runner-execution row (else {@link DomainErrorCode#MANUAL_EXECUTION_NOT_APPLICABLE});
 *   <li>validate the payload with the SAME {@code ContractValidator}/{@code ValidationContext} the
 *       broker uses in {@code onResult} ({@link DomainErrorCode#RUNNER_OUTPUT_VALIDATION_FAILED}),
 *       then the stage→artifact-type rule ({@link DomainErrorCode#RUNNER_ARTIFACT_TYPE_MISMATCH});
 *   <li>materialize any operator-supplied artifact content into scratch (so a spec's {@code
 *       contentReference} resolves), then delegate ingest+complete+advance to the broker;
 *   <li>complete the idempotency record with the resulting state.
 * </ol>
 *
 * <p>The whole method is {@code @Transactional}: the finalize + ingest + event + transition AND the
 * idempotency reservation all commit or roll back together (AC4). The no-op-on-failure guarantee
 * (AC5) rests on this single transaction — the reservation is taken BEFORE validation, so a later
 * validation/ingest failure rolls the reservation back too, leaving the run resubmittable under the
 * same key. (Do NOT split the reservation into its own committed transaction: that would burn the
 * key on an invalid submission.)
 */
@Service
public class ManualArtifactSubmissionService {

  private static final Logger log = LoggerFactory.getLogger(ManualArtifactSubmissionService.class);
  private static final String COMMAND_TYPE = "manual-artifact-submit";

  private final WorkflowRunReadPort workflowRunReadPort;
  private final RunnerExecutionRecordPort recordPort;
  private final RunnerScratchStore scratchStore;
  private final RunnerContractValidator contractValidator;
  private final RunnerBroker runnerBroker;
  private final IdempotencyService idempotencyService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public ManualArtifactSubmissionService(
      WorkflowRunReadPort workflowRunReadPort,
      RunnerExecutionRecordPort recordPort,
      RunnerScratchStore scratchStore,
      RunnerContractValidator contractValidator,
      RunnerBroker runnerBroker,
      IdempotencyService idempotencyService) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.contractValidator = Objects.requireNonNull(contractValidator, "contractValidator");
    this.runnerBroker = Objects.requireNonNull(runnerBroker, "runnerBroker");
    this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
  }

  @Transactional
  public WorkflowStateChangeResult submit(ManualArtifactSubmissionCommand command) {
    Objects.requireNonNull(command, "command");
    String workflowRunId = command.workflowRunId();
    PublicIdPrefixes.require(workflowRunId, PublicIdPrefixes.WORKFLOW_RUN);
    ActorContext operatorActor =
        new ActorContext(command.actorIdentity(), command.actorType(), command.correlationId());
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    try {
      log.info(
          "manual artifact submission received workflowRunId={} actorIdentity={}",
          workflowRunId,
          MdcKeys.sanitizeForLog(command.actorIdentity()));

      // 1. Run existence (RUN_NOT_FOUND wins over a fabricated-run replay).
      workflowRunReadPort
          .findByPublicId(workflowRunId)
          .orElseThrow(() -> runNotFound(workflowRunId));

      // 2. Idempotency FIRST (fingerprint includes the artifact bytes — AC3). An honest retry of an
      // already-COMPLETED submission must replay the prior result EVEN THOUGH the run has since
      // left
      // WaitingForManualExecution (the applicability gate below would otherwise reject it). The
      // reservation joins this @Transactional boundary, so a later validation/ingest failure rolls
      // the reservation back too, leaving the run parked + resubmittable under the same key (AC5).
      String fingerprint =
          fingerprint(workflowRunId, command.payloadBytes(), command.artifactContents());
      IdempotencyService.ReservationOutcome outcome =
          idempotencyService.checkAndReserve(
              command.idempotencyKey(), COMMAND_TYPE, command.actorIdentity(), fingerprint);
      if (outcome.decision() == IdempotencyService.ReservationDecision.REPLAY) {
        WorkflowState replayedState =
            WorkflowState.fromValue(outcome.resultRef(), "idempotencyReplayState");
        log.info(
            "manual artifact submission replayed workflowRunId={} state={}",
            workflowRunId,
            replayedState.value());
        return new WorkflowStateChangeResult(workflowRunId, replayedState, command.correlationId());
      }

      // 3. Applicability gate — exactly one parked awaiting_manual row (else
      // MANUAL_EXECUTION_NOT_APPLICABLE; the throw rolls back the reservation).
      RunnerExecutionSnapshot parkedRow = resolveParkedRow(workflowRunId);
      String runnerExecutionId = parkedRow.publicId();
      String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
      try {
        // 4. Validate with the SAME validator + context the broker uses (AC2). On failure the throw
        // rolls the whole tx (incl. the reservation) back — a pure no-op on persisted state (AC5).
        validateOrReject(workflowRunId, runnerExecutionId, parkedRow, command.payloadBytes());

        // 5. Materialize any operator-supplied artifact content into scratch so the broker's
        // contentReference-keyed payload read (e.g. a spec) resolves, then delegate ingest.
        materializeArtifactContents(runnerExecutionId, command.artifactContents());
        runnerBroker.ingestManualResult(runnerExecutionId, command.payloadBytes(), operatorActor);

        // 6. Read the resulting state + complete the idempotency record with it.
        WorkflowState currentState = currentState(workflowRunId);
        idempotencyService.complete(
            command.idempotencyKey(), currentState.value(), IdempotencyRecordStatus.COMPLETED);
        log.info(
            "manual artifact accepted workflowRunId={} runnerExecutionId={} stage={} newState={}",
            workflowRunId,
            runnerExecutionId,
            parkedRow.stage().value(),
            currentState.value());
        return new WorkflowStateChangeResult(workflowRunId, currentState, command.correlationId());
      } finally {
        MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      }
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private RunnerExecutionSnapshot resolveParkedRow(String workflowRunId) {
    List<RunnerExecutionSnapshot> parked =
        recordPort.findByWorkflowRunPublicIdAndStatusIn(
            workflowRunId, List.of(RunnerExecutionStatus.AWAITING_MANUAL));
    if (parked.isEmpty()) {
      log.warn(
          "manual artifact rejected workflowRunId={} reason={}",
          workflowRunId,
          DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE.value());
      throw manualExecutionNotApplicable(workflowRunId);
    }
    if (parked.size() > 1) {
      // Invariant breach: 3d-3 parks exactly one awaiting_manual row per run. More than one means
      // the dispatch-side park double-fired; silently picking the first could finalize/ingest
      // against the wrong execution, so fail loud rather than guess (ingestManualResult's
      // recordCompleted is deliberately unguarded on this single-row guarantee).
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("runId", workflowRunId);
      details.put("parkedRowCount", parked.size());
      log.error(
          "manual artifact ambiguous parked rows workflowRunId={} count={}",
          workflowRunId,
          parked.size());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Multiple awaiting_manual runner executions for the run; cannot resolve a single parked"
              + " row",
          details);
    }
    return parked.get(0);
  }

  private void validateOrReject(
      String workflowRunId,
      String runnerExecutionId,
      RunnerExecutionSnapshot parkedRow,
      byte[] payloadBytes) {
    // Same validator + context the broker builds for an automated runner result (AC2 — no parallel
    // weaker schema).
    ValidationContext validationContext =
        runnerBroker.buildResultValidationContext(workflowRunId, runnerExecutionId);
    ValidationResult result =
        contractValidator.validate(ValidationTarget.RUNNER_RESULT, payloadBytes, validationContext);
    if (!result.valid()) {
      log.warn(
          "manual artifact rejected workflowRunId={} runnerExecutionId={} reason={}",
          workflowRunId,
          runnerExecutionId,
          DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED.value());
      throw outputValidationFailed(workflowRunId, runnerExecutionId, result.errors());
    }

    JsonNode parsed;
    try {
      parsed = objectMapper.readTree(payloadBytes);
    } catch (IOException error) {
      // Unreachable: the schema validation above already parses the payload as JSON.
      throw outputValidationFailed(workflowRunId, runnerExecutionId, List.of());
    }
    // The submitted result must target the parked execution — a mismatched (but otherwise known)
    // runnerExecutionId would ingest against the wrong row.
    String submittedRex = parsed.path("runnerExecutionId").asText(null);
    if (!runnerExecutionId.equals(submittedRex)) {
      log.warn(
          "manual artifact rejected workflowRunId={} runnerExecutionId={} reason={} submittedRex={}",
          workflowRunId,
          runnerExecutionId,
          DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED.value(),
          MdcKeys.sanitizeForLog(submittedRex));
      throw outputValidationFailed(workflowRunId, runnerExecutionId, List.of());
    }
    // Stage→artifact-type rule, using the SAME mapping the broker uses (AC2).
    for (JsonNode ref : parsed.path("artifactReferences")) {
      ArtifactType refType =
          ArtifactType.fromValue(
              ref.path("artifactType").asText(), "runner_result.artifactReferences.artifactType");
      if (!RunnerBroker.allowedArtifactTypesForStage(parkedRow.stage()).contains(refType)) {
        log.warn(
            "manual artifact rejected workflowRunId={} runnerExecutionId={} reason={} stage={} actualType={}",
            workflowRunId,
            runnerExecutionId,
            DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH.value(),
            parkedRow.stage().value(),
            refType.value());
        throw artifactTypeMismatch(workflowRunId, runnerExecutionId, parkedRow, refType);
      }
    }
  }

  private void materializeArtifactContents(
      String runnerExecutionId, Map<String, byte[]> artifactContents) {
    if (artifactContents == null || artifactContents.isEmpty()) {
      return;
    }
    for (Map.Entry<String, byte[]> entry : artifactContents.entrySet()) {
      scratchStore.writeArtifactContent(runnerExecutionId, entry.getKey(), entry.getValue());
    }
  }

  private WorkflowState currentState(String workflowRunId) {
    return workflowRunReadPort
        .findByPublicId(workflowRunId)
        .map(WorkflowRunSnapshot::currentState)
        .orElseThrow(() -> runNotFound(workflowRunId));
  }

  // Dedicated mapper used ONLY to canonicalize the fingerprint input (config-independent tree
  // round-trip). Kept separate from the Spring-managed boundary mappers so a future change to the
  // app's serialization config can never silently shift fingerprints.
  private static final ObjectMapper FINGERPRINT_MAPPER = new ObjectMapper();

  /**
   * Canonicalizes a JSON payload for fingerprinting: parse, recursively sort object keys (arrays
   * keep their order — order is significant in JSON), re-serialize compactly. Falls back to the raw
   * bytes when the payload is not parseable JSON (deterministic either way).
   */
  private static byte[] canonicalJsonBytes(byte[] payloadBytes) {
    try {
      JsonNode tree = FINGERPRINT_MAPPER.readTree(payloadBytes);
      if (tree == null || tree.isMissingNode()) {
        return payloadBytes;
      }
      return FINGERPRINT_MAPPER.writeValueAsBytes(canonicalize(tree));
    } catch (IOException error) {
      return payloadBytes;
    }
  }

  private static JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = FINGERPRINT_MAPPER.createObjectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) {
        sorted.set(name, canonicalize(node.get(name)));
      }
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = FINGERPRINT_MAPPER.createArrayNode();
      for (JsonNode element : node) {
        array.add(canonicalize(element));
      }
      return array;
    }
    return node;
  }

  /**
   * Idempotency fingerprint for a manual submission. The {@code payloadBytes} are
   * <strong>canonicalized</strong> (parsed and re-serialized with recursively sorted object keys
   * and no insignificant whitespace) BEFORE hashing so that the SAME logical artifact yields the
   * SAME fingerprint regardless of how each channel delivered it — the REST controller
   * re-serializes the parsed {@code result} JSON node (compact) while the CLI forwards the
   * operator's raw file bytes (arbitrary whitespace / key order). Without canonicalization those
   * byte streams differ and an honest cross-channel retry under one key would surface a false
   * {@link DomainErrorCode#IDEMPOTENCY_KEY_CONFLICT} (review finding 2026-06-23). A payload that is
   * not parseable JSON (only reachable before validation rejects it) falls back to the raw bytes so
   * the fingerprint stays deterministic. Package-private so {@code
   * ManualArtifactSubmissionServiceTest} can pin the canonicalization invariant directly.
   */
  static String fingerprint(
      String workflowRunId, byte[] payloadBytes, Map<String, byte[]> artifactContents) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(COMMAND_TYPE.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(workflowRunId.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(canonicalJsonBytes(payloadBytes));
      if (artifactContents != null) {
        // Sorted so the fingerprint is order-independent.
        for (String key : new java.util.TreeSet<>(artifactContents.keySet())) {
          digest.update((byte) 0);
          digest.update(key.getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
          digest.update(artifactContents.get(key));
        }
      }
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest.digest()) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("JVM does not provide SHA-256", error);
    }
  }

  private static DomainException runNotFound(String workflowRunId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunId, details);
  }

  private static DomainException manualExecutionNotApplicable(String workflowRunId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("reason", "no_parked_manual_execution");
    return new DomainException(
        DomainErrorCode.MANUAL_EXECUTION_NOT_APPLICABLE,
        "Manual execution is not applicable: run is not parked in WaitingForManualExecution: "
            + workflowRunId,
        details);
  }

  private static DomainException outputValidationFailed(
      String workflowRunId, String runnerExecutionId, List<ValidationError> errors) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("errorCount", errors.size());
    if (!errors.isEmpty()) {
      details.put("firstError", errors.get(0).code().name());
    }
    return new DomainException(
        DomainErrorCode.RUNNER_OUTPUT_VALIDATION_FAILED,
        "Manual artifact failed runner-output validation",
        details);
  }

  private static DomainException artifactTypeMismatch(
      String workflowRunId,
      String runnerExecutionId,
      RunnerExecutionSnapshot parkedRow,
      ArtifactType actualType) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunId);
    details.put("runnerExecutionId", runnerExecutionId);
    details.put("stage", parkedRow.stage().value());
    details.put("actualArtifactType", actualType.value());
    details.put(
        "expectedArtifactTypes",
        RunnerBroker.allowedArtifactTypesForStage(parkedRow.stage()).stream()
            .map(ArtifactType::value)
            .toList());
    return new DomainException(
        DomainErrorCode.RUNNER_ARTIFACT_TYPE_MISMATCH,
        "Manual artifact type is not allowed for the parked stage",
        details);
  }

  /**
   * Story 3d-4 — the operator submission inputs. The {@code payloadBytes} is the
   * runner-result-shaped JSON the {@code ContractValidator} validates (true parity, AC2's "no
   * validation bypass"); {@code artifactContents} optionally carries the bytes for any {@code
   * contentReference}-keyed artifact (e.g. a spec) the operator produced, materialized into scratch
   * before ingest.
   */
  public record ManualArtifactSubmissionCommand(
      String workflowRunId,
      byte[] payloadBytes,
      Map<String, byte[]> artifactContents,
      String idempotencyKey,
      String actorIdentity,
      org.dradgo.domain.registry.ActorType actorType,
      String correlationId) {

    public ManualArtifactSubmissionCommand {
      Objects.requireNonNull(workflowRunId, "workflowRunId");
      Objects.requireNonNull(payloadBytes, "payloadBytes");
      Objects.requireNonNull(idempotencyKey, "idempotencyKey");
      Objects.requireNonNull(actorIdentity, "actorIdentity");
      Objects.requireNonNull(actorType, "actorType");
    }
  }
}
