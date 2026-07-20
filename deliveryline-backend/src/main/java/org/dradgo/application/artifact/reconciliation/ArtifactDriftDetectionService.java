package org.dradgo.application.artifact.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.ArtifactEventRecord;
import org.dradgo.application.artifact.ArtifactOperationSnapshot;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.reconciliation.spi.ArtifactDriftWritePort;
import org.dradgo.application.artifact.reconciliation.spi.DriftRecordRequest;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.id.PublicIdPrefixes;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.15 (AC1/AC3/AC4, NFR2, FR47) — the DETECTION half of Epic-4's artifact-reconciliation
 * split. A framework-trigger-free application service (the {@code @Scheduled} trigger lives in
 * {@code infrastructure.config}) that, per tick, scans three drift categories and records +
 * announces each detected drift — <strong>never</strong> mutating an artifact/operation status,
 * never writing {@code resolved_*}, never invoking the existing auto-flip reconciliation (repair is
 * story 4.16).
 *
 * <p><strong>Three categories.</strong> (a) <em>orphan operation</em> — stale {@code pending}
 * {@code artifact_operations} past the {@code deliveryline.artifact.reconciliation.stale-pending-
 * minutes} threshold, reusing the existing DB-side {@link
 * ArtifactOperationPort#findPendingOlderThan} finder; (b) <em>missing payload</em> — an {@code
 * available} artifact whose {@code storage_ref} {@link ArtifactPayloadStore#isReadable} can no
 * longer resolve; (c) <em>checksum mismatch</em> — the payload reads but its recomputed {@link
 * ArtifactChecksum#digestHex} differs from the stored {@code checksum_value}. An unsupported stored
 * algorithm (empty digest) is a WARN, NOT a mismatch (OQ-2) — old rows are never
 * false-positive-flagged as corrupt.
 *
 * <p><strong>Isolation + idempotency.</strong> Each detected drift is written in its own {@code
 * REQUIRES_NEW} transaction (P23 per-item isolation — one bad row never aborts the tick), and the
 * write is insert-if-absent against the {@code uq_artifact_drift_detected_active} partial-unique
 * (NULLS NOT DISTINCT) index, so a standing drift produces exactly one unresolved row + one {@code
 * artifact.driftDetected} event across repeated scans. The per-tick {@code correlationId} is a
 * fresh {@code UUID} (P18 — never reuse {@code ActorContext.SYSTEM.correlationId}).
 */
@Service
public class ArtifactDriftDetectionService {

  private static final Logger log = LoggerFactory.getLogger(ArtifactDriftDetectionService.class);

  static final String DRIFT_DETECTED_COUNTER = "deliveryline.artifact.drift.detected";

  private final ArtifactOperationPort artifactOperationPort;
  private final ArtifactRecordPort artifactRecordPort;
  private final ArtifactPayloadStore payloadStore;
  private final ArtifactDriftWritePort driftWritePort;
  private final ArtifactEventPort artifactEventPort;
  private final ArtifactDriftDetectionProperties properties;
  private final MeterRegistry meterRegistry;
  private final Clock clock;
  private final Duration stalePendingThreshold;
  // Each detected drift is written in its own REQUIRES_NEW tx so one bad row never aborts the tick.
  private final TransactionTemplate perItemTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  // Keyset cursors (story 4.15 review D1/D2). Detection is status-neutral: a clean available
  // artifact / a still-pending orphan never leaves its scan set, so an uncursored oldest-N scan
  // would re-read the same oldest batch every tick and NEVER reach drift beyond it once more than
  // one batch is eligible. Each cursor advances to the last row of a FULL batch and resets to null
  // on a PARTIAL batch (tail reached), so successive ticks walk the whole eligible set then
  // restart.
  // volatile for cross-tick visibility; per-instance (multi-instance convergence is the DB
  // partial-unique + ON CONFLICT, not the cursor).
  private volatile OffsetDateTime availableCursorCreatedAt;
  private volatile String availableCursorPublicId;
  private volatile OffsetDateTime orphanCursorCreatedAt;
  private volatile String orphanCursorPublicId;

  @Autowired
  public ArtifactDriftDetectionService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactPayloadStore payloadStore,
      ArtifactDriftWritePort driftWritePort,
      ArtifactEventPort artifactEventPort,
      ArtifactDriftDetectionProperties properties,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager,
      @Value("${deliveryline.artifact.reconciliation.stale-pending-minutes:15}")
          long stalePendingMinutes) {
    this(
        artifactOperationPort,
        artifactRecordPort,
        payloadStore,
        driftWritePort,
        artifactEventPort,
        properties,
        meterRegistry,
        Clock.systemUTC(),
        Duration.ofMinutes(stalePendingMinutes),
        requiresNewTemplate(transactionManager));
  }

  /**
   * Test-only constructor. Callers MUST pass a {@code TransactionTemplate} that delegates to the
   * production REQUIRES_NEW propagation (or a Mockito stub that calls the callback inline). Passing
   * {@code null} is rejected so unit tests cannot silently skip the per-item isolation the
   * production sweep depends on.
   */
  ArtifactDriftDetectionService(
      ArtifactOperationPort artifactOperationPort,
      ArtifactRecordPort artifactRecordPort,
      ArtifactPayloadStore payloadStore,
      ArtifactDriftWritePort driftWritePort,
      ArtifactEventPort artifactEventPort,
      ArtifactDriftDetectionProperties properties,
      MeterRegistry meterRegistry,
      Clock clock,
      Duration stalePendingThreshold,
      TransactionTemplate perItemTemplate) {
    this.artifactOperationPort =
        Objects.requireNonNull(artifactOperationPort, "artifactOperationPort");
    this.artifactRecordPort = Objects.requireNonNull(artifactRecordPort, "artifactRecordPort");
    this.payloadStore = Objects.requireNonNull(payloadStore, "payloadStore");
    this.driftWritePort = Objects.requireNonNull(driftWritePort, "driftWritePort");
    this.artifactEventPort = Objects.requireNonNull(artifactEventPort, "artifactEventPort");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (stalePendingThreshold == null
        || stalePendingThreshold.compareTo(Duration.ofMinutes(1)) < 0) {
      throw new IllegalArgumentException(
          "stalePendingThreshold must be at least 1 minute but was: " + stalePendingThreshold);
    }
    this.stalePendingThreshold = stalePendingThreshold;
    if (perItemTemplate == null) {
      throw new IllegalArgumentException(
          "perItemTemplate must not be null; tests must stub a callthrough TransactionTemplate "
              + "so drift detection cannot silently bypass REQUIRES_NEW isolation");
    }
    this.perItemTemplate = perItemTemplate;
  }

  private static TransactionTemplate requiresNewTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  /**
   * Run one drift-detection tick: scan orphan operations, then the bounded {@code available}
   * artifact window, recording + announcing each genuinely-new drift. Never throws for per-item
   * failures — those are swallowed and retried next tick.
   */
  public DriftScanResult detectDrift() {
    String correlationId = "artifact-drift-sweep:" + UUID.randomUUID();
    return MdcKeys.withKey(
        MdcKeys.CORRELATION_ID,
        correlationId,
        () -> {
          long startNanos = System.nanoTime();
          int batchLimit = properties.batchLimit();
          Duration minAge = Duration.ofMinutes(properties.minAgeMinutes());
          log.info(
              "artifact-drift SWEEP start stalePendingThreshold={} minAge={} batchLimit={}",
              stalePendingThreshold,
              minAge,
              batchLimit);
          Tally tally = new Tally();
          scanOrphans(batchLimit, correlationId, tally);
          scanAvailable(minAge, batchLimit, correlationId, tally);
          DriftScanResult result = tally.toResult();
          log.info(
              "artifact-drift SWEEP complete orphanCount={} missingCount={} checksumCount={}"
                  + " batchLimitHit={} durationMs={}",
              result.orphanCount(),
              result.missingCount(),
              result.checksumCount(),
              result.batchLimitHit(),
              (System.nanoTime() - startNanos) / 1_000_000L);
          return result;
        });
  }

  // ------------------------------------------------------------------------------------------------
  // Category (a): orphan operations — stale pending artifact_operations past the threshold.
  // ------------------------------------------------------------------------------------------------

  private void scanOrphans(int batchLimit, String correlationId, Tally tally) {
    List<ArtifactOperationSnapshot> stale =
        artifactOperationPort.findPendingOlderThan(
            stalePendingThreshold, batchLimit, orphanCursorCreatedAt, orphanCursorPublicId);
    log.debug(
        "artifact-drift orphan scan candidateCount={} afterPublicId={}",
        stale.size(),
        orphanCursorPublicId);
    if (stale.size() >= batchLimit) {
      // No silent truncation: the batch was full, so advance the keyset cursor and keep the rest
      // for
      // the next tick (detection-only never resolves orphans, so an uncursored scan would starve
      // any
      // orphan past the oldest batch).
      tally.batchLimitHit = true;
      ArtifactOperationSnapshot last = stale.get(stale.size() - 1);
      orphanCursorCreatedAt = last.createdAt();
      orphanCursorPublicId = last.publicId();
      log.warn(
          "artifact-drift orphan scan hit batch limit batchLimit={} — advancing keyset cursor to"
              + " operationId={}, continues next tick",
          batchLimit,
          orphanCursorPublicId);
    } else {
      // Partial batch = tail reached; reset so the next tick rescans from the oldest orphan.
      orphanCursorCreatedAt = null;
      orphanCursorPublicId = null;
    }
    for (ArtifactOperationSnapshot operation : stale) {
      String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, operation.workflowRunId());
      String priorOp = MdcKeys.beginScope(MdcKeys.ARTIFACT_OPERATION_ID, operation.publicId());
      try {
        recordDrift(
            new DriftCandidate(
                DriftCategory.ORPHAN_OPERATION,
                operation.workflowRunId(),
                null,
                operation.publicId(),
                FailureCategory.ORPHAN,
                "stale_pending",
                orphanSnapshotJson(operation)),
            correlationId,
            tally);
      } catch (RuntimeException error) {
        // P23 — a per-item failure never aborts the tick; WARN + move on (mirrors
        // ArtifactReconciliationService.reconcileStalePendingOperations).
        log.warn(
            "artifact-drift SWEEP swallowed per-orphan error operationId={} error={}",
            operation.publicId(),
            error.getClass().getSimpleName(),
            error);
      } finally {
        MdcKeys.endScope(MdcKeys.ARTIFACT_OPERATION_ID, priorOp);
        MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
      }
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Categories (b)+(c): available artifacts — missing payload / checksum mismatch.
  // ------------------------------------------------------------------------------------------------

  private void scanAvailable(Duration minAge, int batchLimit, String correlationId, Tally tally) {
    List<ArtifactRecordSnapshot> available =
        artifactRecordPort.findAvailableCreatedBefore(
            minAge, batchLimit, availableCursorCreatedAt, availableCursorPublicId);
    if (available.size() >= batchLimit) {
      // No silent truncation: the batch was full, so advance the keyset cursor and continue from
      // there next tick (detection-only never flips status, so an uncursored scan would re-read the
      // same oldest batch forever and never reach drift on newer available artifacts).
      tally.batchLimitHit = true;
      ArtifactRecordSnapshot last = available.get(available.size() - 1);
      availableCursorCreatedAt = last.createdAt();
      availableCursorPublicId = last.publicId();
      log.warn(
          "artifact-drift SWEEP hit batch limit batchLimit={} — advancing keyset cursor to"
              + " artifactId={}, continues next tick",
          batchLimit,
          availableCursorPublicId);
    } else {
      // Partial batch = tail reached; reset so the next tick rescans from the oldest available.
      availableCursorCreatedAt = null;
      availableCursorPublicId = null;
    }
    for (ArtifactRecordSnapshot artifact : available) {
      String priorRun = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, artifact.workflowRunId());
      String priorArt = MdcKeys.beginScope(MdcKeys.ARTIFACT_ID, artifact.publicId());
      try {
        classifyAndRecord(artifact, correlationId, tally);
      } catch (RuntimeException error) {
        log.warn(
            "artifact-drift SWEEP swallowed per-artifact error artifactId={} error={}",
            artifact.publicId(),
            error.getClass().getSimpleName(),
            error);
      } finally {
        MdcKeys.endScope(MdcKeys.ARTIFACT_ID, priorArt);
        MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRun);
      }
    }
  }

  private void classifyAndRecord(
      ArtifactRecordSnapshot artifact, String correlationId, Tally tally) {
    String storageRef = artifact.storageRef();
    if (storageRef == null || storageRef.isBlank() || !payloadStore.isReadable(storageRef)) {
      recordDrift(
          new DriftCandidate(
              DriftCategory.MISSING_PAYLOAD,
              artifact.workflowRunId(),
              artifact.publicId(),
              null,
              null,
              "payload_missing",
              artifactSnapshotJson(artifact, "payload_missing")),
          correlationId,
          tally);
      return;
    }
    Optional<byte[]> bytes = payloadStore.readBytes(storageRef);
    if (bytes.isEmpty()) {
      // Readable a moment ago but not now (TOCTOU race) — do not mislabel; the next tick re-scans.
      log.debug(
          "artifact-drift payload became unreadable mid-scan artifactId={} — skipping this tick",
          artifact.publicId());
      return;
    }
    Optional<String> digest = ArtifactChecksum.digestHex(artifact.checksumAlgorithm(), bytes.get());
    if (digest.isEmpty()) {
      // OQ-2 — an unsupported/legacy stored algorithm is UNVERIFIABLE, NOT a mismatch. WARN so a
      // data-migration signal is visible without false-positive-flagging an old row as corrupt.
      log.warn(
          "artifact-drift unverifiable checksum algorithm artifactId={} checksumAlgorithm={}",
          artifact.publicId(),
          artifact.checksumAlgorithm());
      return;
    }
    String storedChecksum = artifact.checksumValue();
    if (storedChecksum == null || storedChecksum.isBlank()) {
      // Symmetric to the unsupported-algorithm guard: a supported algorithm paired with an
      // absent/blank stored checksum is UNVERIFIABLE, NOT a mismatch. The V1 paired-null CHECK
      // keeps the pure-null case away from here, but a non-null blank value satisfies that CHECK —
      // guard it so a readable, uncorrupted payload is never false-flagged as corrupt.
      log.warn(
          "artifact-drift unverifiable stored checksum (absent/blank) artifactId={} checksumAlgorithm={}",
          artifact.publicId(),
          artifact.checksumAlgorithm());
      return;
    }
    if (!digest.get().equalsIgnoreCase(storedChecksum)) {
      recordDrift(
          new DriftCandidate(
              DriftCategory.CHECKSUM_MISMATCH,
              artifact.workflowRunId(),
              artifact.publicId(),
              null,
              null,
              "checksum_mismatch",
              artifactSnapshotJson(artifact, "checksum_mismatch")),
          correlationId,
          tally);
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Persistence (each drift write in its own REQUIRES_NEW tx) + event emission + metrics.
  // ------------------------------------------------------------------------------------------------

  private void recordDrift(DriftCandidate candidate, String correlationId, Tally tally) {
    String driftId = PublicIdPrefixes.ARTIFACT_DRIFT_DETECTED.next();
    Instant detectedAt = Instant.now(clock);
    DriftRecordRequest request =
        new DriftRecordRequest(
            driftId,
            candidate.workflowRunId(),
            candidate.artifactId(),
            candidate.artifactOperationId(),
            candidate.category().value(),
            candidate.lastKnownStateJson(),
            detectedAt);

    Boolean inserted =
        perItemTemplate.execute(
            status -> {
              boolean wrote = driftWritePort.recordIfAbsent(request);
              if (wrote) {
                emitDriftEvent(candidate, driftId, correlationId, detectedAt);
              }
              return wrote;
            });

    if (Boolean.TRUE.equals(inserted)) {
      tally.increment(candidate.category());
      meterRegistry
          .counter(DRIFT_DETECTED_COUNTER, "category", candidate.category().value())
          .increment();
      log.warn(
          "artifact drift detected driftId={} driftCategory={} artifactId={} operationId={} reason={}",
          driftId,
          candidate.category().value(),
          candidate.artifactId(),
          candidate.artifactOperationId(),
          candidate.reason());
    } else {
      // Expected every tick for a standing drift — DEBUG, not WARN.
      log.debug(
          "artifact-drift SWEEP dedup-skip driftCategory={} artifactId={} operationId={}",
          candidate.category().value(),
          candidate.artifactId(),
          candidate.artifactOperationId());
    }
  }

  private void emitDriftEvent(
      DriftCandidate candidate, String driftId, String correlationId, Instant detectedAt) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.DRIFT_ID, driftId);
    details.put(WorkflowEventDetailKeys.DRIFT_CATEGORY, candidate.category().value());
    if (candidate.artifactId() != null) {
      details.put(WorkflowEventDetailKeys.ARTIFACT_ID, candidate.artifactId());
    }
    if (candidate.artifactOperationId() != null) {
      details.put(WorkflowEventDetailKeys.OPERATION_ID, candidate.artifactOperationId());
    }
    details.put(WorkflowEventDetailKeys.REASON, candidate.reason());
    if (candidate.failureCategory() != null) {
      details.put(WorkflowEventDetailKeys.FAILURE_CATEGORY, candidate.failureCategory().value());
    }
    if (correlationId != null && !correlationId.isBlank()) {
      details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    // State-neutral event (prior_state == resulting_state == null, intervention_marker=false — the
    // ArtifactEventPersistenceAdapter writes those): a drift is not a workflow-state change.
    artifactEventPort.append(
        new ArtifactEventRecord(
            candidate.workflowRunId(),
            WorkflowEventType.ARTIFACT_DRIFT_DETECTED,
            ActorContext.SYSTEM.actorIdentity(),
            ActorContext.SYSTEM.actorType(),
            candidate.reason(),
            candidate.failureCategory(),
            OffsetDateTime.ofInstant(detectedAt, ZoneOffset.UTC),
            details));
  }

  private String orphanSnapshotJson(ArtifactOperationSnapshot operation) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("operationId", operation.publicId());
    snapshot.put("artifactId", operation.artifactId());
    snapshot.put("workflowRunId", operation.workflowRunId());
    snapshot.put("status", operation.status() == null ? null : operation.status().value());
    snapshot.put(
        "createdAt", operation.createdAt() == null ? null : operation.createdAt().toString());
    snapshot.put("reason", "stale_pending");
    return toJson(snapshot);
  }

  private String artifactSnapshotJson(ArtifactRecordSnapshot artifact, String reason) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("artifactId", artifact.publicId());
    snapshot.put("workflowRunId", artifact.workflowRunId());
    snapshot.put("status", artifact.status() == null ? null : artifact.status().value());
    snapshot.put("storageRef", artifact.storageRef());
    snapshot.put("checksumAlgorithm", artifact.checksumAlgorithm());
    snapshot.put(
        "createdAt", artifact.createdAt() == null ? null : artifact.createdAt().toString());
    snapshot.put("reason", reason);
    return toJson(snapshot);
  }

  private String toJson(Map<String, Object> value) {
    // Map.copyOf rejects null values; a LinkedHashMap with nulls is fine for Jackson, which encodes
    // them as JSON null.
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      // Snapshots carry only ids/states/refs — serialization cannot realistically fail; fall back
      // to
      // an empty object so the drift is still recorded.
      return "{}";
    }
  }

  /** Mutable per-tick accumulator folded into a {@link DriftScanResult}. */
  private static final class Tally {
    private int orphanCount;
    private int missingCount;
    private int checksumCount;
    private boolean batchLimitHit;

    private void increment(DriftCategory category) {
      switch (category) {
        case ORPHAN_OPERATION -> orphanCount++;
        case MISSING_PAYLOAD -> missingCount++;
        case CHECKSUM_MISMATCH -> checksumCount++;
      }
    }

    private DriftScanResult toResult() {
      return new DriftScanResult(orphanCount, missingCount, checksumCount, batchLimitHit);
    }
  }

  /**
   * One classified drift ready to record; exactly one of {@code artifactId} / {@code operationId}.
   */
  private record DriftCandidate(
      DriftCategory category,
      String workflowRunId,
      String artifactId,
      String artifactOperationId,
      FailureCategory failureCategory,
      String reason,
      String lastKnownStateJson) {}
}
