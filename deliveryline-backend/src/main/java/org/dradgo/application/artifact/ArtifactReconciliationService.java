package org.dradgo.application.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftReadPort;
import org.dradgo.application.artifact.reconciliation.spi.DriftQuery;
import org.dradgo.application.artifact.reconciliation.spi.DriftRow;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.DriftCategory;
import org.dradgo.domain.registry.FailureCategory;
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
  private final ObjectMapper objectMapper = new ObjectMapper();

  // Story 4.15 review D2 — listUnresolvedDrift page bounds. Detection-only accumulates unresolved
  // rows (nothing resolves them until story 4.16), so the read is ALWAYS capped: a null operator
  // limit defaults to DEFAULT, and any explicit limit must be within (0, MAX].
  static final int DEFAULT_DRIFT_PAGE_LIMIT = 500;
  static final int MAX_DRIFT_PAGE_LIMIT = 1000;

  @Autowired
  public ArtifactReconciliationService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactEventPort artifactEventPort,
      ArtifactDriftReadPort driftReadPort,
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
        driftReadPort);
  }

  /**
   * Test-only constructor. Callers MUST pass a {@code TransactionTemplate} that delegates to the
   * production REQUIRES_NEW propagation (or a Mockito stub that calls the callback inline). Passing
   * {@code null} is rejected so unit tests cannot silently skip the per-item isolation that
   * production reconciliation depends on.
   */
  ArtifactReconciliationService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactEventPort artifactEventPort,
      Clock clock,
      Duration stalePendingThreshold,
      TransactionTemplate perItemTransactionTemplate,
      ArtifactDriftReadPort driftReadPort) {
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
}
