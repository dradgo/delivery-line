package org.dradgo.application.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.dradgo.application.approval.ApprovalService;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftWritePort;
import org.dradgo.application.artifact.reconciliation.spi.DriftQuery;
import org.dradgo.application.artifact.reconciliation.spi.DriftRow;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.DriftCategory;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
  // Story 4.15 (AC5) — the read seam for listUnresolvedDrift. Nullable in the test-only constructor
  // (unit tests of the orphan-reconciliation path do not exercise the drift read surface); the read
  // method fails fast with IllegalStateException if it is absent, mirroring
  // IntegrationConflictService.
  private final ArtifactDriftReadPort driftReadPort;
  // Story 4.16 (repair path) — the write/resolve + audit + approval seams. All nullable in the
  // legacy 7-arg test constructor (the orphan-reconciliation unit tests do not exercise repair);
  // repairArtifactDrift fails fast with IllegalStateException if a required one is absent.
  private final ArtifactDriftWritePort driftWritePort;
  private final RecoveryActionRecordPort recoveryActionRecordPort;
  private final ApprovalService approvalService;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final WorkflowEventReadPort workflowEventReadPort;
  private final ArtifactPayloadStore payloadStore;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final ObjectMapper objectMapper = new ObjectMapper();

  // Story 4.15 review D2 — listUnresolvedDrift page bounds. Detection-only accumulates unresolved
  // rows (nothing resolves them until story 4.16), so the read is ALWAYS capped: a null operator
  // limit defaults to DEFAULT, and any explicit limit must be within (0, MAX].
  static final int DEFAULT_DRIFT_PAGE_LIMIT = 500;
  static final int MAX_DRIFT_PAGE_LIMIT = 1000;

  // Story 4.16 (Reconciliation 2/13) — the recovery_actions constants for the repair path. The
  // action_type value is NOT a domain-registry enum (it is only an SQL CHECK + this constant); the
  // V46 migration widened ck_recovery_actions_action_type with 'artifact_repair'.
  static final String ACTION_TYPE_ARTIFACT_REPAIR = "artifact_repair";
  // Story 4.16a (Reconciliation 2) — the recovery_actions action_type for the lineage-recovery
  // path.
  // NOT a domain-registry enum (SQL CHECK + this constant only); V47 widened
  // ck_recovery_actions_action_type with 'artifact_lineage_reconcile'.
  static final String ACTION_TYPE_ARTIFACT_LINEAGE_RECONCILE = "artifact_lineage_reconcile";
  static final String RESULT_STATUS_SUCCEEDED = "succeeded";
  static final String REVIEWER_ROLE_WORKFLOW_OWNER = "workflow_owner";
  // Story 4.16a (OQ-8) — controlled reason token stamped on approvals invalidated when an ambiguous
  // lineage is terminated (mirror INVALIDATED_REASON_MARK_CORRUPTED) so the cause is
  // machine-readable.
  static final String INVALIDATED_REASON_LINEAGE_TERMINATED = "superseded_by_lineage_termination";
  // Controlled reason tokens stamped on invalidated approvals (mirror 4.7's
  // superseded_by_rerun_from_step) so the invalidation cause is machine-readable.
  static final String INVALIDATED_REASON_MARK_CORRUPTED = "superseded_by_mark_corrupted";
  static final String INVALIDATED_REASON_MARK_PAYLOAD_UNAVAILABLE =
      "superseded_by_mark_payload_unavailable";

  @Autowired
  public ArtifactReconciliationService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactEventPort artifactEventPort,
      ArtifactDriftReadPort driftReadPort,
      ArtifactDriftWritePort driftWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      ApprovalService approvalService,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowEventReadPort workflowEventReadPort,
      ArtifactPayloadStore payloadStore,
      IdempotencyKeyValidator idempotencyKeyValidator,
      PlatformTransactionManager transactionManager,
      @Value("${deliveryline.artifact.reconciliation.stale-pending-minutes:15}")
          long stalePendingMinutes) {
    this(
        artifactOperationPort,
        artifactRecordPort,
        artifactEventPort,
        Clock.systemUTC(),
        Duration.ofMinutes(stalePendingMinutes),
        requiresNewTemplate(transactionManager),
        driftReadPort,
        driftWritePort,
        recoveryActionRecordPort,
        approvalService,
        workflowEventWritePort,
        workflowEventReadPort,
        payloadStore,
        idempotencyKeyValidator);
  }

  /**
   * Legacy 7-arg test constructor (story 4.15) — the orphan-reconciliation + drift-read unit tests
   * that do not exercise the repair path. Delegates to the canonical constructor with every story
   * 4.16 repair seam {@code null}; {@code repairArtifactDrift} fails fast if invoked without them.
   * Callers MUST pass a {@code TransactionTemplate} that delegates to the production REQUIRES_NEW
   * propagation (or a Mockito stub that calls the callback inline); passing {@code null} is
   * rejected so unit tests cannot silently skip the per-item isolation reconciliation depends on.
   */
  ArtifactReconciliationService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactEventPort artifactEventPort,
      Clock clock,
      Duration stalePendingThreshold,
      TransactionTemplate perItemTransactionTemplate,
      ArtifactDriftReadPort driftReadPort) {
    this(
        artifactOperationPort,
        artifactRecordPort,
        artifactEventPort,
        clock,
        stalePendingThreshold,
        perItemTransactionTemplate,
        driftReadPort,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Canonical constructor (package-private — the story 4.16 repair unit test wires the full seam
   * set through it). The six original dependencies are validated at construction; the seven repair
   * seams are nullable by design (the legacy constructors pass {@code null}) and are asserted
   * present at repair time.
   */
  ArtifactReconciliationService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactEventPort artifactEventPort,
      Clock clock,
      Duration stalePendingThreshold,
      TransactionTemplate perItemTransactionTemplate,
      ArtifactDriftReadPort driftReadPort,
      ArtifactDriftWritePort driftWritePort,
      RecoveryActionRecordPort recoveryActionRecordPort,
      ApprovalService approvalService,
      WorkflowEventWritePort workflowEventWritePort,
      WorkflowEventReadPort workflowEventReadPort,
      ArtifactPayloadStore payloadStore,
      IdempotencyKeyValidator idempotencyKeyValidator) {
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
    if (stalePendingThreshold == null
        || stalePendingThreshold.compareTo(Duration.ofMinutes(1)) < 0) {
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
    // Nullable by design — the orphan-reconciliation unit tests construct without a drift read
    // port.
    this.driftReadPort = driftReadPort;
    this.driftWritePort = driftWritePort;
    this.recoveryActionRecordPort = recoveryActionRecordPort;
    this.approvalService = approvalService;
    this.workflowEventWritePort = workflowEventWritePort;
    this.workflowEventReadPort = workflowEventReadPort;
    this.payloadStore = payloadStore;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  public ArtifactReconciliationResult reconcileStalePendingOperations() {
    // Stale-window comparison runs DB-side via findPendingOlderThan: created_at < (now() -
    // threshold).
    // JVM-derived thresholds drift away from DB-side timestamps under clock skew, so the staleness
    // decision must stay on a single clock to avoid false-positive or false-negative orphan flips.
    List<ArtifactOperationSnapshot> stale =
        artifactOperationPort.findPendingOlderThan(stalePendingThreshold);
    log.info(
        "reconcileStalePendingOperations start stalePendingThreshold={} candidateCount={}",
        stalePendingThreshold,
        stale.size());
    List<ArtifactOperationSnapshot> orphaned = new ArrayList<>();
    for (ArtifactOperationSnapshot operation : stale) {
      // P23: per-item exception isolation. A failure on one item must not abort the entire
      // batch — log and continue so the remaining candidates are still processed.
      try {
        Optional<ArtifactOperationSnapshot> result = reconcileWithIsolation(operation);
        result.ifPresent(orphaned::add);
      } catch (Exception error) {
        log.error(
            "reconcileStalePendingOperations item failed operationId={} artifactId={} cause={}",
            operation.publicId(),
            operation.artifactId(),
            error.toString());
      }
    }
    log.info(
        "reconcileStalePendingOperations done stalePendingThreshold={} candidateCount={} orphanedCount={}",
        stalePendingThreshold,
        stale.size(),
        orphaned.size());
    return new ArtifactReconciliationResult(orphaned);
  }

  private Optional<ArtifactOperationSnapshot> reconcileWithIsolation(
      ArtifactOperationSnapshot operation) {
    return perItemTransactionTemplate.execute(status -> reconcileSingleOperation(operation));
  }

  Optional<ArtifactOperationSnapshot> reconcileSingleOperation(
      ArtifactOperationSnapshot operation) {
    String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, operation.artifactId());
    String priorOperationMdc =
        MdcKeys.beginScope(MdcKeys.ARTIFACT_OPERATION_ID, operation.publicId());
    try {
      Optional<ArtifactRecordSnapshot> currentArtifact =
          artifactRecordPort.findByPublicId(operation.artifactId());
      if (currentArtifact.isEmpty()) {
        log.info(
            "reconcileSingleOperation skip operationId={} artifactId={} reason=artifactAbsent",
            operation.publicId(),
            operation.artifactId());
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
          artifactRecordPort.markFailed(
              operation.artifactId(), FailureCategory.ORPHAN, "stale_pending");
        } catch (DomainException error) {
          if (error.errorCode() == DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION) {
            log.info(
                "reconcileSingleOperation skip operationId={} artifactId={} reason=artifactStateRaceWonByPeer",
                operation.publicId(),
                operation.artifactId());
            return Optional.empty();
          }
          throw error;
        }
        orphaned =
            artifactOperationPort.markFailedOrphan(
                operation.publicId(), "artifact payload never materialized");
        // P20: for PENDING artifacts, emit BOTH ARTIFACT_FAILED and RECOVERY_RECONCILED so
        // existing consumers subscribed to ARTIFACT_FAILED see the flip without having to
        // learn about the new reconciliation event type.
        // P18: use a fresh per-tick UUID correlationId; do NOT reuse
        // ActorContext.SYSTEM.correlationId.
        String correlationId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(clock);
        artifactEventPort.append(
            new ArtifactEventRecord(
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
        artifactEventPort.append(
            new ArtifactEventRecord(
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
          orphaned =
              artifactOperationPort.markFailedOrphan(
                  operation.publicId(), "artifact payload never materialized");
        } catch (DomainException closeError) {
          log.info(
              "reconcileSingleOperation skip late-or-stale operationId={} artifactId={} reason={}",
              operation.publicId(),
              operation.artifactId(),
              closeError.getMessage());
          return Optional.empty();
        }
        // P20: for LATE_OR_STALE, emit only RECOVERY_RECONCILED — do NOT emit ARTIFACT_FAILED
        // since the artifact status already reflects the failure; a second ARTIFACT_FAILED event
        // would mislead consumers into thinking the artifact just transitioned to failed.
        String correlationId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(clock);
        artifactEventPort.append(
            new ArtifactEventRecord(
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
        // P14: artifact is FAILED, AVAILABLE, or ARCHIVED — it is no longer PENDING or
        // LATE_OR_STALE,
        // but the operation row may still be dangling (zombie). Close it as FAILED_ORPHAN so it
        // does
        // not interfere with the single-pending invariant or show up in future reconciliation
        // passes.
        try {
          orphaned =
              artifactOperationPort.markFailedOrphan(
                  operation.publicId(),
                  "zombie operation: artifact reached terminal/available state without completing the operation");
        } catch (DomainException closeError) {
          log.info(
              "reconcileSingleOperation skip zombie operationId={} artifactId={} artifactStatus={} reason={}",
              operation.publicId(),
              operation.artifactId(),
              artifactStatus.value(),
              closeError.getMessage());
          return Optional.empty();
        }
        log.info(
            "reconcileSingleOperation closed zombie operationId={} artifactId={} artifactStatus={}",
            orphaned.publicId(),
            orphaned.artifactId(),
            artifactStatus.value());
        return Optional.of(orphaned);
      }
      log.warn(
          "reconcileSingleOperation flipped artifact to failed/orphan operationId={} artifactId={} workflowRunId={} stalePendingThreshold={}",
          orphaned.publicId(),
          orphaned.artifactId(),
          orphaned.workflowRunId(),
          stalePendingThreshold);
      return Optional.of(orphaned);
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_OPERATION_ID, priorOperationMdc);
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Story 4.15 (AC5/AC6) — the operator drift READ surface. Reads are unrestricted (AC9 only guards
  // the WRITE path, which lives in application.artifact.reconciliation); this delegates to the
  // drift
  // read port and enriches each row with the COMPUTED RepairActionHint (AC6 — derived purely from
  // driftCategory, drives no behavior in 4.15; story 4.16 consumes it to pre-select a repair).
  // ------------------------------------------------------------------------------------------------

  /**
   * Returns the currently UNRESOLVED artifact-drift rows ({@code resolved_at IS NULL}) matching the
   * optional {@link DriftFilter}, newest-first. Each {@link DriftSummary} carries the parsed {@code
   * lastKnownState} snapshot and a computed {@link RepairActionHint}. Bad filter values raise
   * {@code INVALID_COMMAND_PAYLOAD}.
   */
  @Transactional(readOnly = true)
  public List<DriftSummary> listUnresolvedDrift(DriftFilter filter) {
    Objects.requireNonNull(filter, "filter");
    validateDriftFilter(filter);
    if (driftReadPort == null) {
      throw new IllegalStateException(
          "ArtifactDriftReadPort is required to list unresolved artifact drift");
    }
    int effectiveLimit = filter.limit() == null ? DEFAULT_DRIFT_PAGE_LIMIT : filter.limit();
    log.info(
        "listUnresolvedDrift start driftCategoryFilter={} workflowRunFilter={} ticketRefFilter={}"
            + " limit={}",
        filter.driftCategory(),
        filter.workflowRunId(),
        filter.ticketReference(),
        effectiveLimit);
    List<DriftRow> rows =
        driftReadPort.listUnresolved(
            new DriftQuery(
                filter.driftCategory(),
                filter.timeSince(),
                filter.workflowRunId(),
                filter.ticketReference(),
                effectiveLimit));
    List<DriftSummary> summaries = new ArrayList<>(rows.size());
    for (DriftRow row : rows) {
      summaries.add(
          new DriftSummary(
              row.driftId(),
              row.artifactId(),
              row.artifactOperationId(),
              row.driftCategory(),
              row.detectedAt(),
              parseLastKnownState(row.lastKnownStateJson()),
              RepairActionHint.forCategory(row.driftCategory())));
    }
    log.info("listUnresolvedDrift done returned={}", summaries.size());
    return summaries;
  }

  private void validateDriftFilter(DriftFilter filter) {
    if (filter.driftCategory() != null && !filter.driftCategory().isBlank()) {
      boolean known = false;
      for (DriftCategory value : DriftCategory.values()) {
        if (value.value().equals(filter.driftCategory())) {
          known = true;
          break;
        }
      }
      if (!known) {
        throw invalidDriftFilter("driftCategory", filter.driftCategory());
      }
    }
    if (filter.timeSince() != null && filter.timeSince().isNegative()) {
      throw invalidDriftFilter("timeSince", filter.timeSince().toString());
    }
    if (filter.limit() != null && (filter.limit() <= 0 || filter.limit() > MAX_DRIFT_PAGE_LIMIT)) {
      throw invalidDriftFilter("limit", String.valueOf(filter.limit()));
    }
  }

  private static DomainException invalidDriftFilter(String field, String value) {
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Invalid artifact-drift filter value for " + field,
        Map.of("field", field, "value", value, "reason", "invalid_drift_filter"));
  }

  private Map<String, Object> parseLastKnownState(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      // A malformed snapshot must not break the read — surface an empty state rather than throw.
      log.warn("listUnresolvedDrift unparseable last_known_state — surfacing empty state");
      return Map.of();
    }
  }

  /**
   * Story 4.15 (AC5) — the filter surface for {@link #listUnresolvedDrift}. Every filter field is
   * nullable = "no filter on this axis"; all rows returned are always {@code resolved_at IS NULL}.
   * {@code driftCategory} is a {@code DriftCategory} wire value; {@code timeSince} bounds {@code
   * detected_at >= now() - timeSince}; {@code workflowRunId} narrows to a run; {@code
   * ticketReference} narrows to a run whose typed {@code linear} integration link carries that
   * external ref. {@code limit} is a nullable page cap (review D2) — {@code null} defaults to
   * {@link #DEFAULT_DRIFT_PAGE_LIMIT}; a value outside {@code (0, }{@link
   * #MAX_DRIFT_PAGE_LIMIT}{@code ]} raises {@code INVALID_COMMAND_PAYLOAD}.
   */
  public record DriftFilter(
      String driftCategory,
      Duration timeSince,
      String workflowRunId,
      String ticketReference,
      Integer limit) {

    /** No-filter query — returns unresolved drift up to the default page cap. */
    public static DriftFilter unfiltered() {
      return new DriftFilter(null, null, null, null, null);
    }

    public static DriftFilter forRun(String workflowRunId) {
      return new DriftFilter(null, null, workflowRunId, null, null);
    }
  }

  /**
   * Story 4.15 (AC5) — one unresolved drift row for the operator surface. Exactly one of {@code
   * artifactId} / {@code artifactOperationId} is non-null (the other axis is a plain nullable ref,
   * NOT {@code Optional}, per repo convention). {@code lastKnownState} is the parsed JSON snapshot
   * captured at detection; {@code suggestedRepairAction} is the COMPUTED {@link RepairActionHint}
   * (AC6) — a non-persisted hint story 4.16 consumes.
   */
  public record DriftSummary(
      String driftId,
      String artifactId,
      String artifactOperationId,
      DriftCategory driftCategory,
      Instant detectedAt,
      Map<String, Object> lastKnownState,
      RepairActionHint suggestedRepairAction) {}

  // ================================================================================================
  // Story 4.16 (AC1-AC4, AC6 / Reconciliation 12/13/14) — the operator-driven repair WRITE path.
  // A public coordinator (repairArtifactDrift) validates + dispatches inside ONE REQUIRES_NEW prep
  // tx; the six typed methods are thin public wrappers over it (AC1) so validation + idempotency
  // are
  // single-sourced. Mirrors RecoveryService.classifyFailure: replay-first with an action_type
  // guard,
  // succeeded-on-INSERT recovery_actions, the repair-method payload on the RESULTING event (there
  // is
  // NO recovery_actions.details column — Reconciliation 1).
  // ================================================================================================

  /** AC1 — mark the orphan operation failed (orphan_operation drift). */
  public ArtifactRepairResult markOperationFailed(
      String driftId, String reasonText, ActorContext actor, String idempotencyKey) {
    return repairArtifactDrift(
        new RepairArtifactDriftCommand(
            driftId,
            RepairAction.MARK_OPERATION_FAILED.value(),
            reasonText,
            null,
            null,
            actor,
            idempotencyKey));
  }

  /**
   * AC1 — mark the orphan operation complete (orphan_operation drift); completionEvidence required.
   */
  public ArtifactRepairResult markOperationComplete(
      String driftId, String completionEvidence, ActorContext actor, String idempotencyKey) {
    return repairArtifactDrift(
        new RepairArtifactDriftCommand(
            driftId,
            RepairAction.MARK_OPERATION_COMPLETE.value(),
            null,
            completionEvidence,
            null,
            actor,
            idempotencyKey));
  }

  /** AC1 — mark the artifact's payload unavailable (missing_payload drift): AVAILABLE -> FAILED. */
  public ArtifactRepairResult markPayloadUnavailable(
      String driftId, String reasonText, ActorContext actor, String idempotencyKey) {
    return repairArtifactDrift(
        new RepairArtifactDriftCommand(
            driftId,
            RepairAction.MARK_PAYLOAD_UNAVAILABLE.value(),
            reasonText,
            null,
            null,
            actor,
            idempotencyKey));
  }

  /** AC1 — restore the artifact from a backup source (missing_payload drift). E4 STUB (OQ-2). */
  public ArtifactRepairResult restoreFromBackup(
      String driftId, String backupSource, ActorContext actor, String idempotencyKey) {
    return repairArtifactDrift(
        new RepairArtifactDriftCommand(
            driftId,
            RepairAction.RESTORE_FROM_BACKUP.value(),
            null,
            null,
            backupSource,
            actor,
            idempotencyKey));
  }

  /** AC1 — mark the artifact corrupted (checksum_mismatch drift): AVAILABLE -> CORRUPTED. */
  public ArtifactRepairResult markCorrupted(
      String driftId, String reasonText, ActorContext actor, String idempotencyKey) {
    return repairArtifactDrift(
        new RepairArtifactDriftCommand(
            driftId,
            RepairAction.MARK_CORRUPTED.value(),
            reasonText,
            null,
            null,
            actor,
            idempotencyKey));
  }

  /**
   * AC1 — re-verify the checksum (checksum_mismatch drift): resolve-on-match /
   * leave-open-on-mismatch.
   */
  public ArtifactRepairResult reVerifyChecksum(
      String driftId, ActorContext actor, String idempotencyKey) {
    return repairArtifactDrift(
        new RepairArtifactDriftCommand(
            driftId,
            RepairAction.RE_VERIFY_CHECKSUM.value(),
            null,
            null,
            null,
            actor,
            idempotencyKey));
  }

  /**
   * Story 4.16 (AC1-AC4, AC6) — the single repair coordinator the REST controller + CLI call. Runs
   * the idempotency replay pre-check, resolves + validates the drift, checks repair-action legality
   * + required fields, then dispatches to the matching typed repair inside ONE {@code REQUIRES_NEW}
   * prep transaction (Reconciliation 12/13). All repair effects (status mutation, approval
   * invalidation, {@code artifact.driftRepaired} event, {@code recovery_actions} insert, drift
   * resolve) commit atomically.
   */
  public ArtifactRepairResult repairArtifactDrift(RepairArtifactDriftCommand command) {
    Objects.requireNonNull(command, "command");
    ActorContext actor = Objects.requireNonNull(command.actor(), "actor");
    requireRepairSeams();
    validateCommandShape(command);
    String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(command.idempotencyKey());
    long start = System.nanoTime();
    log.info(
        "artifact repairArtifactDrift start driftId={} repairAction={} actorIdentity={}"
            + " idempotencyKey={}",
        command.driftId(),
        command.repairAction(),
        actor.actorIdentity(),
        validatedIdempotencyKey);
    try {
      // Step 1 — replay pre-check BEFORE input validation (mirror classifyFailure). Guard on
      // ACTION_TYPE_ARTIFACT_REPAIR so a prior retry/resume/reconcile/rerun/pause/classify under
      // the
      // same key is a conflict, never a false repair replay
      // ([[transition-idempotencykey-is-not-a-dedupe-key]]).
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!ACTION_TYPE_ARTIFACT_REPAIR.equals(prior.actionType())
            || !RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())
            || prior.resultingEventPublicId() == null) {
          throw idempotencyConflict(validatedIdempotencyKey, prior);
        }
        return replayRepair(command.driftId(), validatedIdempotencyKey, actor, prior);
      }

      // Step 2 — drift lookup (returns EVEN resolved rows so DRIFT_ALREADY_RESOLVED is detectable).
      DriftRow drift =
          driftReadPort
              .findByPublicId(command.driftId())
              .orElseThrow(() -> driftNotFound(command.driftId()));
      if (drift.resolvedAt() != null) {
        throw driftAlreadyResolved(command.driftId());
      }

      // Step 3 — repair-action legality for the drift's category.
      RepairAction action =
          RepairAction.fromWire(command.repairAction())
              .filter(candidate -> RepairAction.isLegalFor(drift.driftCategory(), candidate))
              .orElseThrow(
                  () ->
                      invalidRepairActionForCategory(
                          command.repairAction(), drift.driftCategory()));

      // Step 4 — action-specific required fields.
      validateRequiredFields(action, command);

      // Step 5 — dispatch, all inside ONE REQUIRES_NEW prep tx.
      String correlationId = actor.correlationId();
      RepairPrep prep;
      try {
        prep =
            perItemTransactionTemplate.execute(
                status ->
                    performRepair(
                        drift, action, command, validatedIdempotencyKey, actor, correlationId));
      } catch (DomainException prepError) {
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<ArtifactRepairResult> raceReplay =
              resolveConcurrentRepairReplay(command.driftId(), validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "artifact repairArtifactDrift success driftId={} repairAction={} recoveryActionId={}"
              + " repairedEventId={} resolved={} durationMs={}",
          command.driftId(),
          action.value(),
          prep.recoveryActionId(),
          prep.repairedEventId(),
          prep.resolved(),
          elapsedMs);
      return new ArtifactRepairResult(
          command.driftId(),
          action.value(),
          prep.recoveryActionId(),
          prep.repairedEventId(),
          prep.resolved(),
          correlationId,
          false);
    } catch (DomainException rejected) {
      log.warn(
          "artifact repairArtifactDrift rejected driftId={} repairAction={} reason={}",
          command.driftId(),
          command.repairAction(),
          rejected.errorCode());
      throw rejected;
    }
  }

  private RepairPrep performRepair(
      DriftRow drift,
      RepairAction action,
      RepairArtifactDriftCommand command,
      String idempotencyKey,
      ActorContext actor,
      String correlationId) {
    String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, drift.artifactId());
    String priorOperationMdc =
        MdcKeys.beginScope(MdcKeys.ARTIFACT_OPERATION_ID, drift.artifactOperationId());
    try {
      String effectiveReason = effectiveReason(command);

      // 1. Apply the typed repair (status mutation / approval invalidation / re-verify recompute).
      //    Returns whether the drift should be resolved (reVerifyChecksum-still-mismatched ->
      // false).
      boolean resolveDrift = applyRepair(drift, action, command, effectiveReason);

      // 2. Best-effort triggering event: the latest artifact.driftDetected for the run (nullable FK
      // —
      //    Reconciliation 14/OQ-6; imprecise when a run has multiple drifts).
      String triggeringEventPublicId =
          workflowEventReadPort
              .findLatestByWorkflowRunPublicIdAndEventTypeIn(
                  drift.workflowRunId(), List.of(WorkflowEventType.ARTIFACT_DRIFT_DETECTED.value()))
              .map(WorkflowEventRecord::publicId)
              .orElse(null);

      // 3. Append the single state-neutral artifact.driftRepaired event (pre-generated evt_ id ->
      //    recovery_actions.resulting_event_id). details.repairAction discriminates the repair.
      String repairedEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put(WorkflowEventDetailKeys.REPAIR_ACTION, action.value());
      details.put(WorkflowEventDetailKeys.DRIFT_CATEGORY, drift.driftCategory().value());
      details.put(WorkflowEventDetailKeys.DRIFT_ID, drift.driftId());
      if (drift.artifactId() != null) {
        details.put(WorkflowEventDetailKeys.ARTIFACT_ID, drift.artifactId());
      }
      if (drift.artifactOperationId() != null) {
        details.put(WorkflowEventDetailKeys.OPERATION_ID, drift.artifactOperationId());
      }
      if (effectiveReason != null) {
        details.put(WorkflowEventDetailKeys.REASON, effectiveReason);
      }
      if (correlationId != null) {
        details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
      }
      // Server-only (stripped from CLI history render) — mirrors classify.
      details.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
      OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      workflowEventWritePort.append(
          new WorkflowEventRecord(
              repairedEventPublicId,
              drift.workflowRunId(),
              WorkflowEventType.ARTIFACT_DRIFT_REPAIRED,
              null,
              null,
              actor.actorIdentity(),
              actor.actorType(),
              effectiveReason,
              null,
              true,
              now,
              details));

      // 4. Insert the recovery_actions row (succeeded-on-INSERT — single tx, no post-commit hook).
      RecoveryActionSnapshot recoveryAction =
          recoveryActionRecordPort.insert(
              new RecoveryActionWriteCommand(
                  drift.workflowRunId(),
                  ACTION_TYPE_ARTIFACT_REPAIR,
                  triggeringEventPublicId,
                  repairedEventPublicId,
                  actor.actorIdentity(),
                  actor.actorType(),
                  idempotencyKey,
                  RESULT_STATUS_SUCCEEDED,
                  REVIEWER_ROLE_WORKFLOW_OWNER));

      // 5. Resolve the drift (FK RESTRICT -> the recovery_actions row must already exist). A
      //    reVerifyChecksum that still mismatched leaves the drift UNRESOLVED (Reconciliation 8).
      boolean resolved = false;
      if (resolveDrift) {
        resolved =
            driftWritePort.resolveDrift(
                drift.driftId(), recoveryAction.publicId(), now.toInstant());
        // code-review F2 — resolveDrift's UPDATE guards `WHERE resolved_at IS NULL`, so a zero-row
        // result means another repair (a distinct idempotency key that also passed the Step-2
        // unresolved check) won the race and resolved this drift first. reVerifyChecksum mutates no
        // status, so without this guard the loser would still commit a duplicate
        // artifact.driftRepaired
        // event + recovery_actions row and falsely report success. Throwing rolls back this whole
        // REQUIRES_NEW tx (undoing the event + recovery_actions insert) and surfaces the intuitive
        // DRIFT_ALREADY_RESOLVED. Status-mutating repairs are already rolled back earlier by their
        // AVAILABLE/PENDING transition guard, so they never reach here on the losing side.
        if (!resolved) {
          throw driftAlreadyResolved(drift.driftId());
        }
      }
      log.info(
          "artifact repair applied driftId={} artifactId={} operationId={} repairAction={}"
              + " driftCategory={} correlationId={} resolved={}",
          drift.driftId(),
          drift.artifactId(),
          drift.artifactOperationId(),
          action.value(),
          drift.driftCategory().value(),
          correlationId,
          resolved);
      return new RepairPrep(recoveryAction.publicId(), repairedEventPublicId, resolved);
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_OPERATION_ID, priorOperationMdc);
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
    }
  }

  private boolean applyRepair(
      DriftRow drift,
      RepairAction action,
      RepairArtifactDriftCommand command,
      String effectiveReason) {
    requireDriftAxis(drift, action);
    return switch (action) {
      case MARK_OPERATION_FAILED -> {
        artifactOperationPort.markFailed(
            drift.artifactOperationId(),
            FailureCategory.ORPHAN,
            effectiveReason == null ? "operator_marked_failed" : effectiveReason);
        yield true;
      }
      case MARK_OPERATION_COMPLETE -> {
        artifactOperationPort.markComplete(drift.artifactOperationId());
        yield true;
      }
      case MARK_PAYLOAD_UNAVAILABLE -> {
        ArtifactRecordSnapshot updated =
            artifactRecordPort.markPayloadUnavailable(
                drift.artifactId(),
                effectiveReason == null ? "payload_unavailable" : effectiveReason);
        invalidateApprovalsForRepair(
            drift.workflowRunId(),
            updated.artifactType().value(),
            INVALIDATED_REASON_MARK_PAYLOAD_UNAVAILABLE);
        yield true;
      }
      case MARK_CORRUPTED -> {
        ArtifactRecordSnapshot updated =
            artifactRecordPort.markCorrupted(
                drift.artifactId(),
                effectiveReason == null ? "operator_marked_corrupted" : effectiveReason);
        invalidateApprovalsForRepair(
            drift.workflowRunId(),
            updated.artifactType().value(),
            INVALIDATED_REASON_MARK_CORRUPTED);
        yield true;
      }
      case RE_VERIFY_CHECKSUM -> reVerifyChecksumInternal(drift);
      case RESTORE_FROM_BACKUP -> {
        // E4 stub (AC1 explicit, OQ-2 resolved -> option 2) — the wire value + method exist for
        // forward compatibility, but the body rejects until a future backup-integration epic wires
        // a
        // backup source. restore_from_backup IS a legal action for the missing_payload category, so
        // it surfaces the dedicated REPAIR_ACTION_NOT_IMPLEMENTED (501) rather than the
        // semantically-wrong INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY. Throws inside the prep tx so
        // nothing is persisted for an unsupported repair.
        log.warn(
            "restore-from-backup not yet supported driftId={} — E4 stub (rejecting until backup"
                + " integration lands)",
            drift.driftId());
        Map<String, Object> stubDetails = new LinkedHashMap<>();
        stubDetails.put("driftId", drift.driftId());
        stubDetails.put("repairAction", RepairAction.RESTORE_FROM_BACKUP.value());
        stubDetails.put("reason", "restore_from_backup_not_yet_supported");
        throw new DomainException(
            DomainErrorCode.REPAIR_ACTION_NOT_IMPLEMENTED,
            "restore_from_backup is not yet supported (deferred to the backup-integration epic)",
            stubDetails);
      }
    };
  }

  private void invalidateApprovalsForRepair(
      String workflowRunId, String artifactType, String reasonToken) {
    // Propagation.MANDATORY — joins the enclosing prep tx (AC4). Flips the run's current approved
    // leaf of the artifact type (run+type keyed, NOT version-specific — OQ-3).
    approvalService
        .invalidateCurrentApproval(workflowRunId, artifactType, reasonToken)
        .ifPresent(
            approvalId ->
                log.warn(
                    "approval invalidated by repair workflowRunId={} artifactType={} approvalId={}"
                        + " reason={}",
                    workflowRunId,
                    artifactType,
                    approvalId,
                    reasonToken));
  }

  private boolean reVerifyChecksumInternal(DriftRow drift) {
    Optional<ArtifactRecordSnapshot> maybe = artifactRecordPort.findByPublicId(drift.artifactId());
    if (maybe.isEmpty()) {
      log.warn(
          "reVerifyChecksum artifact absent driftId={} artifactId={} — drift left unresolved",
          drift.driftId(),
          drift.artifactId());
      return false;
    }
    ArtifactRecordSnapshot artifact = maybe.get();
    Optional<byte[]> bytes =
        artifact.storageRef() == null
            ? Optional.empty()
            : payloadStore.readBytes(artifact.storageRef());
    if (bytes.isEmpty()) {
      log.warn(
          "reVerifyChecksum payload unreadable driftId={} artifactId={} — drift left unresolved",
          drift.driftId(),
          drift.artifactId());
      driftWritePort.updateLastKnownState(
          drift.driftId(),
          reVerifyStateJson(artifact.checksumAlgorithm(), artifact.checksumValue(), null));
      return false;
    }
    Optional<String> recomputed =
        ArtifactChecksum.digestHex(artifact.checksumAlgorithm(), bytes.get());
    // digestHex emits lowercase hex; compare case-insensitively (matching ArtifactService.verify).
    if (recomputed.isPresent() && recomputed.get().equalsIgnoreCase(artifact.checksumValue())) {
      log.info(
          "reVerifyChecksum now matches driftId={} artifactId={} — transient drift resolved",
          drift.driftId(),
          drift.artifactId());
      return true;
    }
    log.warn(
        "re-verify checksum still mismatched — drift left unresolved driftId={} artifactId={}",
        drift.driftId(),
        drift.artifactId());
    driftWritePort.updateLastKnownState(
        drift.driftId(),
        reVerifyStateJson(
            artifact.checksumAlgorithm(), artifact.checksumValue(), recomputed.orElse(null)));
    return false;
  }

  private String reVerifyStateJson(
      String algorithm, String storedChecksum, String recomputedChecksum) {
    Map<String, Object> state = new LinkedHashMap<>();
    if (algorithm != null) {
      state.put("checksumAlgorithm", algorithm);
    }
    if (storedChecksum != null) {
      state.put("storedChecksum", storedChecksum);
    }
    if (recomputedChecksum != null) {
      state.put("recomputedChecksum", recomputedChecksum);
    }
    state.put(
        "reVerifiedAt", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).toString());
    try {
      return objectMapper.writeValueAsString(state);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      log.warn("reVerifyChecksum could not serialize last-known-state snapshot — using empty");
      return "{}";
    }
  }

  private void validateRequiredFields(RepairAction action, RepairArtifactDriftCommand command) {
    if (action == RepairAction.MARK_OPERATION_COMPLETE && isBlank(command.completionEvidence())) {
      throw missingRepairRequiredField("completionEvidence", action);
    }
  }

  // Max lengths mirror the REST ArtifactRepairRequest @Size caps so the CLI surface (which has no
  // bean validation) enforces the same bounds — CLI/REST equivalence (AC8, code-review F3).
  private static final int MAX_REPAIR_ACTION_LENGTH = 64;
  private static final int MAX_REPAIR_TEXT_LENGTH = 512;

  /**
   * Validate the request shape before the replay pre-check: a present driftId and field lengths
   * within the REST DTO's bounds. Runs on both surfaces so a blank driftId (reachable via the CLI's
   * optional {@code --drift}, code-review F1) surfaces a typed {@code INVALID_COMMAND_PAYLOAD}
   * instead of a NullPointerException 500 from {@code findByPublicId}, and an oversized CLI field
   * (code-review F3) is rejected exactly as the REST {@code @Size} would reject it. A malformed
   * request is never a legitimate replay, so validating structural shape ahead of Step 1 is safe.
   */
  private void validateCommandShape(RepairArtifactDriftCommand command) {
    if (isBlank(command.driftId())) {
      throw invalidCommandPayload("driftId", "driftId is required");
    }
    requireWithinLength("repairAction", command.repairAction(), MAX_REPAIR_ACTION_LENGTH);
    requireWithinLength("reasonText", command.reasonText(), MAX_REPAIR_TEXT_LENGTH);
    requireWithinLength("completionEvidence", command.completionEvidence(), MAX_REPAIR_TEXT_LENGTH);
    requireWithinLength("backupSource", command.backupSource(), MAX_REPAIR_TEXT_LENGTH);
  }

  private static void requireWithinLength(String field, String value, int max) {
    if (value != null && value.length() > max) {
      throw invalidCommandPayload(field, field + " exceeds max length " + max);
    }
  }

  private static DomainException invalidCommandPayload(String field, String message) {
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, message, Map.of("field", field));
  }

  private ArtifactRepairResult replayRepair(
      String driftId, String idempotencyKey, ActorContext actor, RecoveryActionSnapshot prior) {
    // Reconstruct the prior result from its resulting event's details (mirror classify replay). A
    // stored driftId that differs from the request is a conflict (same key, different drift).
    WorkflowEventRecord event =
        workflowEventReadPort.listByWorkflowRunPublicId(prior.workflowRunPublicId(), null).stream()
            .filter(candidate -> prior.resultingEventPublicId().equals(candidate.publicId()))
            .findFirst()
            .orElseThrow(() -> idempotencyConflict(idempotencyKey, prior));
    Object storedDriftId = event.details().get(WorkflowEventDetailKeys.DRIFT_ID);
    if (driftId != null && !driftId.equals(storedDriftId)) {
      throw idempotencyConflict(idempotencyKey, prior);
    }
    Object storedAction = event.details().get(WorkflowEventDetailKeys.REPAIR_ACTION);
    boolean resolved =
        driftReadPort.findByPublicId(driftId).map(row -> row.resolvedAt() != null).orElse(false);
    log.info(
        "artifact repairArtifactDrift replay driftId={} recoveryActionId={} repairAction={}"
            + " resolved={} idempotencyKey={}",
        driftId,
        prior.publicId(),
        storedAction,
        resolved,
        idempotencyKey);
    return new ArtifactRepairResult(
        driftId,
        storedAction == null ? null : storedAction.toString(),
        prior.publicId(),
        prior.resultingEventPublicId(),
        resolved,
        actor.correlationId(),
        true);
  }

  private Optional<ArtifactRepairResult> resolveConcurrentRepairReplay(
      String driftId, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!ACTION_TYPE_ARTIFACT_REPAIR.equals(snapshot.actionType())
        || !RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
        || snapshot.resultingEventPublicId() == null) {
      return Optional.empty();
    }
    return Optional.of(replayRepair(driftId, idempotencyKey, actor, snapshot));
  }

  private void requireRepairSeams() {
    if (driftReadPort == null
        || driftWritePort == null
        || recoveryActionRecordPort == null
        || approvalService == null
        || workflowEventWritePort == null
        || workflowEventReadPort == null
        || payloadStore == null
        || idempotencyKeyValidator == null) {
      throw new IllegalStateException(
          "ArtifactReconciliationService repair seams are required for repairArtifactDrift");
    }
  }

  private static String effectiveReason(RepairArtifactDriftCommand command) {
    String reason =
        firstNonBlank(command.reasonText(), command.completionEvidence(), command.backupSource());
    return reason == null ? null : reason.trim();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  // code-review F9 — the detection invariant guarantees each drift populates exactly one axis for
  // its category (orphan_operation -> artifactOperationId; missing_payload/checksum_mismatch ->
  // artifactId). A corrupt or hand-inserted row could leave the required axis null; without this
  // guard the downstream port call NPEs into an opaque 500. A null axis is a server-side
  // data-integrity break (not a client mistake), so it surfaces INTERNAL_ERROR (500) with a
  // diagnostic payload + ERROR log rather than a typed 4xx that would mask the fault.
  private void requireDriftAxis(DriftRow drift, RepairAction action) {
    boolean needsOperation =
        action == RepairAction.MARK_OPERATION_FAILED
            || action == RepairAction.MARK_OPERATION_COMPLETE;
    if (needsOperation && drift.artifactOperationId() == null) {
      throw driftAxisMissing(drift, "artifactOperationId");
    }
    boolean needsArtifact =
        action == RepairAction.MARK_PAYLOAD_UNAVAILABLE
            || action == RepairAction.MARK_CORRUPTED
            || action == RepairAction.RE_VERIFY_CHECKSUM;
    if (needsArtifact && drift.artifactId() == null) {
      throw driftAxisMissing(drift, "artifactId");
    }
  }

  private DomainException driftAxisMissing(DriftRow drift, String axis) {
    log.error(
        "artifact repair drift row invariant violated driftId={} driftCategory={} missingAxis={}",
        drift.driftId(),
        drift.driftCategory().value(),
        axis);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("driftId", drift.driftId());
    details.put("driftCategory", drift.driftCategory().value());
    details.put("missingAxis", axis);
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR,
        "Drift row invariant violated: null "
            + axis
            + " for category "
            + drift.driftCategory().value(),
        details);
  }

  private static DomainException driftNotFound(String driftId) {
    return new DomainException(
        DomainErrorCode.DRIFT_NOT_FOUND,
        "No artifact drift for id " + driftId,
        Map.of("driftId", driftId == null ? "" : driftId));
  }

  private static DomainException driftAlreadyResolved(String driftId) {
    return new DomainException(
        DomainErrorCode.DRIFT_ALREADY_RESOLVED,
        "Artifact drift already resolved: " + driftId,
        Map.of("driftId", driftId));
  }

  private static DomainException invalidRepairActionForCategory(
      String provided, DriftCategory category) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("provided", provided == null ? "" : provided);
    details.put("driftCategory", category.value());
    details.put("allowableValues", RepairAction.legalWireValuesFor(category));
    return new DomainException(
        DomainErrorCode.INVALID_REPAIR_ACTION_FOR_DRIFT_CATEGORY,
        "Repair action is not valid for drift category " + category.value(),
        details);
  }

  private static DomainException missingRepairRequiredField(String field, RepairAction action) {
    return new DomainException(
        DomainErrorCode.MISSING_REPAIR_REQUIRED_FIELD,
        "Repair action " + action.value() + " requires field: " + field,
        Map.of("field", field, "repairAction", action.value()));
  }

  private DomainException idempotencyConflict(String idempotencyKey, RecoveryActionSnapshot prior) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("idempotencyKey", idempotencyKey);
    details.put("priorActionType", prior.actionType());
    details.put("priorResultStatus", prior.resultStatus());
    return new DomainException(
        DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT,
        "Idempotency key is already bound to another recovery attempt",
        details);
  }

  private record RepairPrep(String recoveryActionId, String repairedEventId, boolean resolved) {}

  /**
   * Story 4.15 (AC6) — a plain COMPUTED application hint (NOT persisted, NOT a {@code
   * domain.registry} value → no drift test), derived purely from the {@link DriftCategory}. It
   * drives NO behavior in 4.15; story 4.16 consumes it to pre-select a repair for the operator.
   */
  public enum RepairActionHint {
    MARK_FAILED_OR_COMPLETE,
    RESTORE_FROM_BACKUP_OR_MARK_UNAVAILABLE,
    RE_VERIFY_OR_MARK_CORRUPTED;

    public static RepairActionHint forCategory(DriftCategory category) {
      return switch (category) {
        case ORPHAN_OPERATION -> MARK_FAILED_OR_COMPLETE;
        case MISSING_PAYLOAD -> RESTORE_FROM_BACKUP_OR_MARK_UNAVAILABLE;
        case CHECKSUM_MISMATCH -> RE_VERIFY_OR_MARK_CORRUPTED;
      };
    }
  }

  // ================================================================================================
  // Story 4.16a (AC1-AC7, AC9 / Reconciliation 1/10) — the operator-driven LINEAGE-recovery WRITE
  // path. Model A: the operator acts DIRECTLY on an ambiguous/orphan artifact id (lineage conflicts
  // are transient / never persisted — there is NO conflictId). A public coordinator
  // (reconcileLineage)
  // validates + dispatches inside ONE REQUIRES_NEW prep tx; the three typed methods are thin public
  // wrappers over it (AC2) so validation + idempotency are single-sourced. Mirrors
  // repairArtifactDrift
  // exactly: replay-first with an action_type guard, succeeded-on-INSERT recovery_actions, the
  // lineageAction payload on the RESULTING event (there is NO recovery_actions.details column). The
  // three lineage-write methods live on the ALREADY-injected artifactRecordPort — ZERO new ctor
  // deps.
  // ================================================================================================

  /** AC3 — re-parent an orphan/ambiguous artifact onto an operator-chosen lineage leaf. */
  public LineageReconciliationResult reattachToExistingLineage(
      String targetArtifactId,
      String chosenParentArtifactId,
      String reasonText,
      ActorContext actor,
      String idempotencyKey) {
    return reconcileLineage(
        new ReconcileLineageCommand(
            targetArtifactId,
            LineageAction.REATTACH_TO_EXISTING_LINEAGE.value(),
            chosenParentArtifactId,
            reasonText,
            actor,
            idempotencyKey));
  }

  /** AC4 — mark an ambiguous lineage terminal so replay cannot silently revive it. */
  public LineageReconciliationResult terminateAmbiguousLineage(
      String targetArtifactId, String reasonText, ActorContext actor, String idempotencyKey) {
    return reconcileLineage(
        new ReconcileLineageCommand(
            targetArtifactId,
            LineageAction.TERMINATE_AMBIGUOUS_LINEAGE.value(),
            null,
            reasonText,
            actor,
            idempotencyKey));
  }

  /**
   * AC5 — create a fresh, disjoint lineage branch with the {@code lineage_recovery} discriminator.
   */
  public LineageReconciliationResult createExplicitFork(
      String targetArtifactId, String reasonText, ActorContext actor, String idempotencyKey) {
    return reconcileLineage(
        new ReconcileLineageCommand(
            targetArtifactId,
            LineageAction.CREATE_EXPLICIT_FORK.value(),
            null,
            reasonText,
            actor,
            idempotencyKey));
  }

  /**
   * Story 4.16a (AC1-AC6) — the single lineage-recovery coordinator the REST controller + CLI call.
   * Runs the idempotency replay pre-check, resolves the target artifact, parses + validates the
   * lineage action + required fields, then dispatches to the matching typed action inside ONE
   * {@code REQUIRES_NEW} prep transaction (Reconciliation 7/10). All effects (lineage mutation,
   * optional approval invalidation, {@code artifact.lineageReconciled} event, {@code
   * recovery_actions} insert) commit atomically with no post-commit side-effect.
   */
  public LineageReconciliationResult reconcileLineage(ReconcileLineageCommand command) {
    Objects.requireNonNull(command, "command");
    ActorContext actor = Objects.requireNonNull(command.actor(), "actor");
    requireLineageSeams();
    validateLineageCommandShape(command);
    String validatedIdempotencyKey = idempotencyKeyValidator.requireValid(command.idempotencyKey());
    long start = System.nanoTime();
    log.info(
        "artifact reconcileLineage start targetArtifactId={} lineageAction={} actorIdentity={}"
            + " idempotencyKey={}",
        command.targetArtifactId(),
        command.lineageAction(),
        actor.actorIdentity(),
        validatedIdempotencyKey);
    try {
      // Step 1 — replay pre-check BEFORE input validation (mirror repairArtifactDrift). Guard on
      // ACTION_TYPE_ARTIFACT_LINEAGE_RECONCILE so a prior repair/retry/resume/… under the same key
      // is
      // a conflict, never a false lineage replay
      // ([[transition-idempotencykey-is-not-a-dedupe-key]]).
      Optional<RecoveryActionSnapshot> priorAction =
          recoveryActionRecordPort.findByIdempotencyKey(validatedIdempotencyKey);
      if (priorAction.isPresent()) {
        RecoveryActionSnapshot prior = priorAction.get();
        if (!ACTION_TYPE_ARTIFACT_LINEAGE_RECONCILE.equals(prior.actionType())
            || !RESULT_STATUS_SUCCEEDED.equals(prior.resultStatus())
            || prior.resultingEventPublicId() == null) {
          throw idempotencyConflict(validatedIdempotencyKey, prior);
        }
        return replayLineageReconcile(
            command.targetArtifactId(), validatedIdempotencyKey, actor, prior);
      }

      // Step 2 — target artifact lookup.
      ArtifactRecordSnapshot target =
          artifactRecordPort
              .findByPublicId(command.targetArtifactId())
              .orElseThrow(() -> artifactRecordNotFound(command.targetArtifactId()));

      // Step 3 — lineage-action parse (no category legality — every action applies to any
      // artifact).
      LineageAction action =
          LineageAction.fromWire(command.lineageAction())
              .orElseThrow(() -> invalidLineageAction(command.lineageAction()));

      // Step 4 — action-specific required fields.
      validateLineageRequiredFields(action, command);

      // Step 5 — dispatch, all inside ONE REQUIRES_NEW prep tx.
      String correlationId = actor.correlationId();
      LineagePrep prep;
      try {
        prep =
            perItemTransactionTemplate.execute(
                status ->
                    performLineageReconcile(
                        target, action, command, validatedIdempotencyKey, actor, correlationId));
      } catch (DomainException prepError) {
        if (prepError.errorCode() == DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT) {
          Optional<LineageReconciliationResult> raceReplay =
              resolveConcurrentLineageReplay(
                  command.targetArtifactId(), validatedIdempotencyKey, actor);
          if (raceReplay.isPresent()) {
            return raceReplay.get();
          }
        }
        throw prepError;
      }

      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      log.info(
          "artifact reconcileLineage success targetArtifactId={} lineageAction={} recoveryActionId={}"
              + " reconciledEventId={} lineageReferenceArtifactId={} durationMs={}",
          command.targetArtifactId(),
          action.value(),
          prep.recoveryActionId(),
          prep.reconciledEventId(),
          prep.lineageReferenceArtifactId(),
          elapsedMs);
      return new LineageReconciliationResult(
          command.targetArtifactId(),
          action.value(),
          prep.recoveryActionId(),
          prep.reconciledEventId(),
          prep.lineageReferenceArtifactId(),
          correlationId,
          false);
    } catch (DomainException rejected) {
      log.warn(
          "artifact reconcileLineage rejected targetArtifactId={} lineageAction={} reason={}",
          command.targetArtifactId(),
          command.lineageAction(),
          rejected.errorCode());
      throw rejected;
    }
  }

  private LineagePrep performLineageReconcile(
      ArtifactRecordSnapshot target,
      LineageAction action,
      ReconcileLineageCommand command,
      String idempotencyKey,
      ActorContext actor,
      String correlationId) {
    String priorArtifactMdc = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, target.publicId());
    try {
      String effectiveReason =
          command.reasonText() == null || command.reasonText().isBlank()
              ? null
              : command.reasonText().trim();
      String workflowRunId = target.workflowRunId();

      // 1. Apply the typed lineage action. lineageReferenceArtifactId = the secondary artifact
      //    involved (chosen parent for reattach, new fork id for create_explicit_fork).
      String lineageReferenceArtifactId = null;
      switch (action) {
        case REATTACH_TO_EXISTING_LINEAGE -> {
          artifactRecordPort.reattachToLineage(target.publicId(), command.chosenParentArtifactId());
          lineageReferenceArtifactId = command.chosenParentArtifactId();
        }
        case TERMINATE_AMBIGUOUS_LINEAGE -> {
          artifactRecordPort.markLineageTerminated(
              target.publicId(), effectiveReason == null ? "lineage_terminated" : effectiveReason);
          // Close any dangling pending operation so replay cannot re-drive the abandoned lineage.
          artifactOperationPort
              .findPendingByArtifactId(target.publicId())
              .ifPresent(
                  op ->
                      artifactOperationPort.markFailedOrphan(
                          op.publicId(), "lineage terminated by operator"));
          // OQ-8 / Review D3 — invalidate any current approval on the abandoned artifact VERSION
          // (artifact-keyed, not run+type keyed — never touches a healthy sibling version's
          // approval).
          invalidateApprovalsForLineageTermination(target.publicId());
        }
        case CREATE_EXPLICIT_FORK -> {
          ArtifactRecordSnapshot fork =
              artifactRecordPort.createLineageRecoveryFork(
                  target.publicId(), actor, effectiveReason);
          lineageReferenceArtifactId = fork.publicId();
        }
      }

      // 2. Append the single state-neutral artifact.lineageReconciled event (pre-generated evt_ id
      // ->
      //    recovery_actions.resulting_event_id). details.lineageAction discriminates the action.
      String reconciledEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put(WorkflowEventDetailKeys.LINEAGE_ACTION, action.value());
      details.put(WorkflowEventDetailKeys.ARTIFACT_ID, target.publicId());
      if (lineageReferenceArtifactId != null) {
        details.put(
            WorkflowEventDetailKeys.LINEAGE_REFERENCE_ARTIFACT_ID, lineageReferenceArtifactId);
      }
      if (effectiveReason != null) {
        details.put(WorkflowEventDetailKeys.REASON, effectiveReason);
      }
      if (correlationId != null) {
        details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
      }
      // Server-only (stripped from CLI history render) — mirrors repair/classify.
      details.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
      OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
      workflowEventWritePort.append(
          new WorkflowEventRecord(
              reconciledEventPublicId,
              workflowRunId,
              WorkflowEventType.ARTIFACT_LINEAGE_RECONCILED,
              null,
              null,
              actor.actorIdentity(),
              actor.actorType(),
              effectiveReason,
              null,
              true,
              now,
              details));

      // 3. Insert the recovery_actions row (succeeded-on-INSERT — single tx, no post-commit hook).
      //    triggering_event_id = null (no persisted conflict/detection event to point at — OQ-5).
      RecoveryActionSnapshot recoveryAction =
          recoveryActionRecordPort.insert(
              new RecoveryActionWriteCommand(
                  workflowRunId,
                  ACTION_TYPE_ARTIFACT_LINEAGE_RECONCILE,
                  null,
                  reconciledEventPublicId,
                  actor.actorIdentity(),
                  actor.actorType(),
                  idempotencyKey,
                  RESULT_STATUS_SUCCEEDED,
                  REVIEWER_ROLE_WORKFLOW_OWNER));

      log.info(
          "artifact lineage reconciled targetArtifactId={} lineageAction={}"
              + " lineageReferenceArtifactId={} correlationId={}",
          target.publicId(),
          action.value(),
          lineageReferenceArtifactId,
          correlationId);
      return new LineagePrep(
          recoveryAction.publicId(), reconciledEventPublicId, lineageReferenceArtifactId);
    } finally {
      MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArtifactMdc);
    }
  }

  private void invalidateApprovalsForLineageTermination(String targetArtifactId) {
    // Propagation.MANDATORY — joins the enclosing prep tx (OQ-8 / Review D3). Version-specific:
    // invalidates only the abandoned artifact version's approval (keyed on the artifact public id),
    // never a healthy sibling version's approval of the same (run, type).
    approvalService
        .invalidateApprovalForArtifact(targetArtifactId, INVALIDATED_REASON_LINEAGE_TERMINATED)
        .ifPresent(
            approvalId ->
                log.warn(
                    "approval invalidated by lineage termination targetArtifactId={} approvalId={}"
                        + " reason={}",
                    targetArtifactId,
                    approvalId,
                    INVALIDATED_REASON_LINEAGE_TERMINATED));
  }

  // Max lengths mirror the REST ArtifactLineageReconcileRequest @Size caps so the CLI surface
  // (which
  // has no bean validation) enforces the same bounds — CLI/REST equivalence (AC9, the 4.16 F3
  // lesson).
  private static final int MAX_LINEAGE_ACTION_LENGTH = 64;
  private static final int MAX_LINEAGE_ID_LENGTH = 64;
  private static final int MAX_LINEAGE_TEXT_LENGTH = 512;

  /**
   * Validate the request shape before the replay pre-check: a present {@code targetArtifactId} and
   * field lengths within the REST DTO's bounds. Runs on both surfaces so a blank {@code
   * targetArtifactId} (reachable via the CLI's optional flag) surfaces a typed {@code
   * INVALID_COMMAND_PAYLOAD} instead of an NPE 500, and an oversized CLI field is rejected exactly
   * as the REST {@code @Size} would. A malformed request is never a legitimate replay, so
   * validating shape ahead of Step 1 is safe.
   */
  private void validateLineageCommandShape(ReconcileLineageCommand command) {
    if (isBlank(command.targetArtifactId())) {
      throw invalidCommandPayload("targetArtifactId", "targetArtifactId is required");
    }
    requireWithinLength("lineageAction", command.lineageAction(), MAX_LINEAGE_ACTION_LENGTH);
    requireWithinLength(
        "chosenParentArtifactId", command.chosenParentArtifactId(), MAX_LINEAGE_ID_LENGTH);
    requireWithinLength("reasonText", command.reasonText(), MAX_LINEAGE_TEXT_LENGTH);
  }

  private void validateLineageRequiredFields(
      LineageAction action, ReconcileLineageCommand command) {
    if (action == LineageAction.REATTACH_TO_EXISTING_LINEAGE
        && isBlank(command.chosenParentArtifactId())) {
      throw missingLineageField("chosenParentArtifactId", action);
    }
  }

  private LineageReconciliationResult replayLineageReconcile(
      String targetArtifactId,
      String idempotencyKey,
      ActorContext actor,
      RecoveryActionSnapshot prior) {
    // Reconstruct the prior result from its resulting event's details (mirror repair replay). A
    // stored targetArtifactId that differs from the request is a conflict (same key, different
    // artifact).
    WorkflowEventRecord event =
        workflowEventReadPort.listByWorkflowRunPublicId(prior.workflowRunPublicId(), null).stream()
            .filter(candidate -> prior.resultingEventPublicId().equals(candidate.publicId()))
            .findFirst()
            .orElseThrow(() -> idempotencyConflict(idempotencyKey, prior));
    Object storedTarget = event.details().get(WorkflowEventDetailKeys.ARTIFACT_ID);
    if (targetArtifactId != null && !targetArtifactId.equals(storedTarget)) {
      throw idempotencyConflict(idempotencyKey, prior);
    }
    Object storedAction = event.details().get(WorkflowEventDetailKeys.LINEAGE_ACTION);
    Object storedReference =
        event.details().get(WorkflowEventDetailKeys.LINEAGE_REFERENCE_ARTIFACT_ID);
    log.info(
        "artifact reconcileLineage replay targetArtifactId={} recoveryActionId={} lineageAction={}"
            + " idempotencyKey={}",
        targetArtifactId,
        prior.publicId(),
        storedAction,
        idempotencyKey);
    return new LineageReconciliationResult(
        targetArtifactId,
        storedAction == null ? null : storedAction.toString(),
        prior.publicId(),
        prior.resultingEventPublicId(),
        storedReference == null ? null : storedReference.toString(),
        actor.correlationId(),
        true);
  }

  private Optional<LineageReconciliationResult> resolveConcurrentLineageReplay(
      String targetArtifactId, String idempotencyKey, ActorContext actor) {
    Optional<RecoveryActionSnapshot> prior =
        recoveryActionRecordPort.findByIdempotencyKey(idempotencyKey);
    if (prior.isEmpty()) {
      return Optional.empty();
    }
    RecoveryActionSnapshot snapshot = prior.get();
    if (!ACTION_TYPE_ARTIFACT_LINEAGE_RECONCILE.equals(snapshot.actionType())
        || !RESULT_STATUS_SUCCEEDED.equals(snapshot.resultStatus())
        || snapshot.resultingEventPublicId() == null) {
      return Optional.empty();
    }
    return Optional.of(replayLineageReconcile(targetArtifactId, idempotencyKey, actor, snapshot));
  }

  private void requireLineageSeams() {
    if (recoveryActionRecordPort == null
        || approvalService == null
        || workflowEventWritePort == null
        || workflowEventReadPort == null
        || idempotencyKeyValidator == null) {
      throw new IllegalStateException(
          "ArtifactReconciliationService lineage seams are required for reconcileLineage");
    }
  }

  private static DomainException artifactRecordNotFound(String artifactId) {
    return new DomainException(
        DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND,
        "Artifact not found: " + artifactId,
        Map.of("artifactId", artifactId == null ? "" : artifactId));
  }

  private static DomainException invalidLineageAction(String provided) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("provided", provided == null ? "" : provided);
    details.put("allowableValues", LineageAction.allWireValues());
    return new DomainException(
        DomainErrorCode.INVALID_LINEAGE_RECOVERY_ACTION,
        "Unknown lineage recovery action",
        details);
  }

  private static DomainException missingLineageField(String field, LineageAction action) {
    return new DomainException(
        DomainErrorCode.MISSING_LINEAGE_RECOVERY_FIELD,
        "Lineage action " + action.value() + " requires field: " + field,
        Map.of("field", field, "lineageAction", action.value()));
  }

  private record LineagePrep(
      String recoveryActionId, String reconciledEventId, String lineageReferenceArtifactId) {}
}
