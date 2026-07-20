package org.dradgo.application.integration.conflict;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.concurrent.ConcurrentHashMap;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictScanPort;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
import org.dradgo.application.integration.conflict.spi.IntegrationLinkScanRow;
import org.dradgo.application.integration.conflict.spi.NewIntegrationConflict;
import org.dradgo.application.integration.repohost.RepositoryHostAdapter;
import org.dradgo.application.integration.repohost.RepositoryHostAdapterException;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapter;
import org.dradgo.application.integration.ticketsource.TicketSourceAdapterException;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.ticketsource.Ticket;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.IntegrationFailureCategory;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.17 (AC1/AC2/AC5/AC8/AC9, FR41/FR43) — the DETECTION half of Epic-4's integration-conflict
 * pair. A framework-trigger-free application service (the {@code @Scheduled} trigger lives in
 * {@code infrastructure.config}) that, per tick, compares internal workflow state against the
 * cached-vs- fresh EXTERNAL Linear/GitHub state and persists detected conflicts — never a silent
 * overwrite (NFR19).
 *
 * <p><strong>Two-phase, no-I/O-under-lock discipline.</strong> Phase 1 takes the PG advisory
 * transaction lock (serializing concurrent sweeps / app instances) and reads both scan windows
 * inside ONE short-lived transaction, then RELEASES the lock (tx commit) <em>before</em> any
 * external HTTP. Phase 2 classifies (fresh Linear/GitHub fetch) and records each conflict in its
 * own {@code REQUIRES_NEW} transaction, lock-free, so a slow/throttled external host never pins the
 * pooled connection or the advisory lock (the review flagged the earlier single-outer-transaction
 * shape as an idle-in-transaction + cross-instance-contention hazard). Each first-insert emits
 * exactly one {@code integration.conflictDetected} event via the insert-or-skip dedup ({@code
 * uq_integration_conflicts_unresolved}) — no per-tick spam.
 *
 * <p><strong>Keyset scan cursor.</strong> The scan is keyset-paginated by {@code integration_links.
 * id}: each tick reads up to {@code batchLimit} rows after a per-type in-memory cursor, advancing
 * the cursor so links beyond a single batch are covered on subsequent ticks (the review flagged the
 * earlier bare {@code LIMIT} as starving the tail past {@code batchLimit}). When the tail is
 * reached the cursor wraps to the oldest link so newly-appearing drift is re-detected each full
 * rotation; a rate/network back-off resumes at the last good link. The cursor is process-local — on
 * restart or across instances it simply re-scans from the oldest (the insert-or-skip dedup makes
 * the re-scan a no-op for standing conflicts).
 *
 * <p><strong>GitHub</strong> carries all five categories (cached {@code prState} vs fresh {@code
 * getPullRequestByRef}, using the {@code PullRequest.merged} widening): merged-while-open / closed-
 * without-merge → {@code external_state_advanced}; reopened-after-close → {@code
 * external_state_reverted}; absent → {@code external_resource_removed}; branch/repo drift → {@code
 * metadata_drift}; permanent access failure → {@code link_broken}. A link with no cached {@code
 * prState} baseline snapshots the fresh state first-seen (like Linear) rather than silently missing
 * a later merge. <strong>Linear</strong> reliably carries {@code external_resource_removed} +
 * {@code link_broken} now; a first-seen {@code sourceStatusId} baseline is snapshotted to enable
 * FUTURE state-drift detection, but 4.17 emits no Linear state-drift conflict (PROVISIONAL — OQ-1).
 * Transient sync/network/rate failures WARN + back off that integration's remaining links for the
 * tick (AC8).
 */
@Service
public class IntegrationConflictDetectionService {

  private static final Logger log =
      LoggerFactory.getLogger(IntegrationConflictDetectionService.class);

  static final String LINEAR_INTEGRATION_TYPE = ConflictIntegrationTypes.LINEAR;
  static final String GITHUB_PR_INTEGRATION_TYPE = ConflictIntegrationTypes.GITHUB_PR;
  // Metric/tag integration labels ({category, integration}); integration is the coarse host name.
  static final String LINEAR_INTEGRATION_TAG = "linear";
  static final String GITHUB_INTEGRATION_TAG = "github";

  static final String CONFLICT_DETECTED_COUNTER = "deliveryline.integration.conflict.detected";

  // Cached GitHub baseline keys (written by IntegrationLinkService.buildGitHubExternalMetadata).
  private static final String META_PR_STATE = "prState";
  private static final String META_BRANCH = "branch";
  private static final String META_REPOSITORY_FULL_NAME = "repositoryFullName";
  // Cached Linear baseline key (first-seen snapshot, OQ-1 provisional).
  private static final String META_SOURCE_STATUS_ID = "sourceStatusId";

  private static final String STATE_OPEN = "open";
  private static final String STATE_CLOSED = "closed";
  private static final String STATE_MERGED = "merged";

  private static final String SYSTEM_ACTOR_IDENTITY = "system";

  private final IntegrationConflictScanPort scanPort;
  private final IntegrationConflictWritePort writePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final TicketSourceAdapter linearAdapter;
  private final ObjectProvider<RepositoryHostAdapter> gitHubAdapterProvider;
  private final IntegrationConflictDetectionProperties properties;
  private final MeterRegistry meterRegistry;
  // Story 4.18 (AC4) — the conflict-driven auto-pause seam, invoked once per NEW conflict in the
  // post-insert branch below. Best-effort (swallows all pause failures internally) so it never
  // aborts the sweep.
  private final ConflictAutoPauseHandler conflictAutoPauseHandler;
  // Phase-1 lock+scan runs in a short-lived REQUIRED tx (released before any external I/O); each
  // phase-2 conflict write runs in its own REQUIRES_NEW tx so one bad link never aborts the sweep.
  private final TransactionTemplate scanTemplate;
  private final TransactionTemplate perLinkTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();
  // Per-integration-type keyset cursor (last scanned integration_links.id). Process-local; the
  // @Scheduled trigger drives sweep() single-threaded, and the advisory lock serializes instances.
  private final Map<String, Long> scanCursors = new ConcurrentHashMap<>();

  public IntegrationConflictDetectionService(
      IntegrationConflictScanPort scanPort,
      IntegrationConflictWritePort writePort,
      WorkflowEventWritePort workflowEventWritePort,
      TicketSourceAdapter linearAdapter,
      ObjectProvider<RepositoryHostAdapter> gitHubAdapterProvider,
      IntegrationConflictDetectionProperties properties,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager,
      ConflictAutoPauseHandler conflictAutoPauseHandler) {
    this.scanPort = Objects.requireNonNull(scanPort, "scanPort");
    this.writePort = Objects.requireNonNull(writePort, "writePort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.linearAdapter = Objects.requireNonNull(linearAdapter, "linearAdapter");
    this.gitHubAdapterProvider =
        Objects.requireNonNull(gitHubAdapterProvider, "gitHubAdapterProvider");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    this.conflictAutoPauseHandler =
        Objects.requireNonNull(conflictAutoPauseHandler, "conflictAutoPauseHandler");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.scanTemplate = new TransactionTemplate(transactionManager);
    this.perLinkTemplate = new TransactionTemplate(transactionManager);
    this.perLinkTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Run one conflict-detection tick across all integration types. Phase 1 takes the sweep advisory
   * lock + reads both scan windows in a short tx (lock released on commit); phase 2 classifies +
   * records conflicts lock-free. Never throws for per-link failures — those are swallowed inside
   * their {@code REQUIRES_NEW} write transaction and retried next tick.
   */
  public SweepResult sweep() {
    String correlationId = "conflict-sweep:" + UUID.randomUUID();
    return MdcKeys.withKey(
        MdcKeys.CORRELATION_ID,
        correlationId,
        () -> {
          long startNanos = System.nanoTime();
          int batchLimit = properties.batchLimit();
          RepositoryHostAdapter gitHubAdapter = gitHubAdapterProvider.getIfAvailable();
          if (gitHubAdapter == null) {
            // No github profile active (mock/real) — the sweep still runs Linear.
            log.debug(
                "integration-conflict SWEEP github adapter unavailable — skipping github links");
          }
          log.info("integration-conflict SWEEP start batchLimit={}", batchLimit);
          Tally tally = new Tally();

          // Phase 1 — lock + scan both windows inside one short-lived tx; NO external I/O here.
          RepositoryHostAdapter resolvedGitHub = gitHubAdapter;
          ScanWindow[] windows =
              scanTemplate.execute(
                  status -> {
                    scanPort.acquireSweepLock();
                    ScanWindow github =
                        resolvedGitHub == null
                            ? ScanWindow.empty(GITHUB_PR_INTEGRATION_TYPE)
                            : scanWindow(GITHUB_PR_INTEGRATION_TYPE, batchLimit, tally);
                    ScanWindow linear = scanWindow(LINEAR_INTEGRATION_TYPE, batchLimit, tally);
                    return new ScanWindow[] {github, linear};
                  });

          // Phase 2 — classify (fresh fetch) + record each conflict, lock-free.
          processType(
              GITHUB_INTEGRATION_TAG, true, windows[0], gitHubAdapter, correlationId, tally);
          processType(LINEAR_INTEGRATION_TAG, false, windows[1], null, correlationId, tally);

          SweepResult result = tally.toResult();
          log.info(
              "integration-conflict SWEEP complete scanned={} conflictsDetected={}"
                  + " skippedDuplicate={} batchLimitHit={} rateLimited={} durationMs={}",
              result.scanned(),
              result.conflictsDetected(),
              result.skippedDuplicate(),
              result.batchLimitHit(),
              result.rateLimited(),
              (System.nanoTime() - startNanos) / 1_000_000L);
          return result;
        });
  }

  /**
   * Read one keyset window of active links of {@code integrationType} after the per-type cursor.
   * Fetches {@code batchLimit + 1} to distinguish an exactly-full batch (nothing beyond) from a
   * truncated one (more remain) without a spurious WARN.
   */
  private ScanWindow scanWindow(String integrationType, int batchLimit, Tally tally) {
    long cursor = scanCursors.getOrDefault(integrationType, 0L);
    List<IntegrationLinkScanRow> fetched =
        scanPort.scanActiveLinksByType(integrationType, batchLimit + 1, cursor);
    boolean more = fetched.size() > batchLimit;
    List<IntegrationLinkScanRow> rows =
        more ? new ArrayList<>(fetched.subList(0, batchLimit)) : fetched;
    if (more) {
      tally.batchLimitHit = true;
      log.warn(
          "integration-conflict SWEEP hit batch limit for integrationType={} batchLimit={} — cursor"
              + " advances so the remainder is scanned next tick",
          integrationType,
          batchLimit);
    }
    return new ScanWindow(integrationType, rows, more, cursor);
  }

  private void processType(
      String integrationTag,
      boolean github,
      ScanWindow window,
      RepositoryHostAdapter gitHubAdapter,
      String correlationId,
      Tally tally) {
    long lastSeq = window.startCursor();
    boolean backedOff = false;
    for (IntegrationLinkScanRow row : window.rows()) {
      tally.scanned++;
      String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, row.workflowRunPublicId());
      try {
        Decision decision = github ? classifyGitHub(row, gitHubAdapter) : classifyLinear(row);
        if (decision.backoff()) {
          tally.rateLimited = true;
          log.warn(
              "integration-conflict SWEEP backing off {} conflict scan (rate/network) after"
                  + " integrationLinkId={} failureCategory={} — skipping remaining {} links",
              integrationTag,
              row.integrationLinkPublicId(),
              decision.failureCategory() == null ? null : decision.failureCategory().value(),
              integrationTag);
          backedOff = true;
          break;
        }
        if (!(decision.skip() || decision.category() == null)) {
          if (isTerminalRun(row.currentState())) {
            // Story 4.30 (AC1, Reconciliation 1) — SKIP creating a conflict for a run that has
            // already terminalized (Completed/TakenOver/Reconciled). Such a conflict is
            // unresolvable
            // (RecoveryService.reconcile rejects terminal runs with RECONCILE_NOT_APPLICABLE and
            // the
            // 4.6 P1 overlay no longer advertises reconcile there), so it would strand
            // resolved_at IS NULL forever and over-report unresolved counts. The run's currentState
            // is already in hand on the scan row — this is a free guard, not a new query. The
            // sub-millisecond TOCTOU window (state read non-terminal, terminalizes before insert
            // commits) is healed by the terminal-run reconciliation sweep.
            log.debug(
                "integration-conflict SWEEP skipping terminal-run conflict workflowRunId={}"
                    + " integrationLinkId={} currentState={} conflictCategory={}",
                row.workflowRunPublicId(),
                row.integrationLinkPublicId(),
                row.currentState(),
                decision.category().value());
          } else {
            recordConflict(row, decision, integrationTag, correlationId, tally);
          }
        }
        lastSeq = row.linkSeq();
      } catch (RuntimeException unexpected) {
        // A per-link failure never aborts the sweep — WARN with the link id + exception type and
        // move on (mirrors RunSplitCompletionRollupService.rollupParent swallow+WARN). The cursor
        // still advances past this link so a permanently-failing row never wedges the rotation.
        log.warn(
            "integration-conflict SWEEP swallowed per-link error integrationLinkId={} error={}",
            row.integrationLinkPublicId(),
            unexpected.getClass().getSimpleName());
        lastSeq = row.linkSeq();
      } finally {
        MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
      }
    }
    // Advance the keyset cursor: on back-off resume at the last good link (retry the backed-off
    // one); when more links remain advance past this batch; otherwise wrap to the oldest so newly-
    // appearing drift on early links is re-detected each full rotation.
    long nextCursor = (backedOff || window.more()) ? lastSeq : 0L;
    scanCursors.put(window.integrationType(), nextCursor);
  }

  // ------------------------------------------------------------------------------------------------
  // Classification (cached external_metadata baseline vs a fresh adapter query).
  // ------------------------------------------------------------------------------------------------

  private Decision classifyGitHub(IntegrationLinkScanRow row, RepositoryHostAdapter gitHubAdapter) {
    Map<String, Object> cached = decodeMetadata(row.externalMetadata());
    Optional<PullRequest> fresh;
    try {
      fresh = gitHubAdapter.getPullRequestByRef(PullRequestRef.of(row.externalRef()));
    } catch (RepositoryHostAdapterException failure) {
      return classifyGitHubFailure(failure);
    }
    if (fresh.isEmpty()) {
      return Decision.conflict(
          IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED, null, "github_pr_absent", null);
    }
    PullRequest pr = fresh.get();
    String cachedPrState = asString(cached.get(META_PR_STATE));
    if (cachedPrState == null) {
      // First-seen link with no cached prState baseline: snapshot the fresh state so the NEXT tick
      // can diff against it (mirrors the Linear first-seen baseline) rather than silently missing a
      // later merge/close. No conflict this tick — there is no internal expectation to contradict.
      snapshotGitHubBaseline(row, cached, pr);
      return Decision.none();
    }
    if (pr.merged()
        && !STATE_CLOSED.equalsIgnoreCase(cachedPrState)
        && !STATE_MERGED.equalsIgnoreCase(cachedPrState)) {
      return Decision.conflict(
          IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED,
          IntegrationFailureCategory.STATE_CONFLICT,
          "github_pr_merged_externally",
          pr.state());
    }
    if (STATE_CLOSED.equalsIgnoreCase(pr.state())
        && !pr.merged()
        && STATE_OPEN.equalsIgnoreCase(cachedPrState)) {
      // Closed WITHOUT merge externally while the baseline was still open — the PR reached a
      // terminal (rejected) state ahead of the internal run; classify as external-state-advanced.
      return Decision.conflict(
          IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED,
          IntegrationFailureCategory.STATE_CONFLICT,
          "github_pr_closed_externally",
          pr.state());
    }
    if (STATE_OPEN.equalsIgnoreCase(pr.state()) && STATE_CLOSED.equalsIgnoreCase(cachedPrState)) {
      return Decision.conflict(
          IntegrationConflictCategory.EXTERNAL_STATE_REVERTED,
          IntegrationFailureCategory.STATE_CONFLICT,
          "github_pr_reopened",
          pr.state());
    }
    if (metadataDrifted(pr, cached)) {
      return Decision.conflict(
          IntegrationConflictCategory.METADATA_DRIFT, null, "github_pr_metadata_drift", pr.state());
    }
    return Decision.none();
  }

  private static boolean metadataDrifted(PullRequest pr, Map<String, Object> cached) {
    String cachedBranch = asString(cached.get(META_BRANCH));
    if (cachedBranch != null && !cachedBranch.equals(pr.sourceBranch())) {
      return true;
    }
    String cachedRepo = asString(cached.get(META_REPOSITORY_FULL_NAME));
    return cachedRepo != null && !cachedRepo.equals(pr.repoRef().value());
  }

  private static Decision classifyGitHubFailure(RepositoryHostAdapterException failure) {
    IntegrationFailureCategory category = failure.failureCategory();
    return switch (category) {
      // Rate/network class — short-circuit this integration's remaining links for the tick (AC8),
      // symmetric with the Linear branch (Reconciliation 8: transient network → back off).
      case GITHUB_RATE_LIMITED, GITHUB_NETWORK_FAILURE, NETWORK_API_FAILURE ->
          Decision.backoff(category);
      case GITHUB_PR_NOT_FOUND, GITHUB_REPO_NOT_FOUND, LINK_FAILURE ->
          Decision.conflict(
              IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED,
              category,
              "github_permanent_removal",
              null);
      case GITHUB_PERMISSION_DENIED, GITHUB_AUTH_FAILED, GITHUB_BRANCH_PROTECTED ->
          Decision.conflict(
              IntegrationConflictCategory.LINK_BROKEN, category, "github_permanent_access", null);
      default -> Decision.skip(category);
    };
  }

  private Decision classifyLinear(IntegrationLinkScanRow row) {
    Map<String, Object> cached = decodeMetadata(row.externalMetadata());
    Optional<Ticket> fresh;
    try {
      fresh = linearAdapter.fetchTicketByReference(TicketRef.of(row.externalRef()));
    } catch (TicketSourceAdapterException failure) {
      return classifyLinearFailure(failure);
    }
    if (fresh.isEmpty()) {
      return Decision.conflict(
          IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED,
          null,
          "linear_ticket_absent",
          null);
    }
    // First-seen baseline (OQ-1 provisional): snapshot sourceStatusId so a FUTURE story can detect
    // Linear state-drift. 4.17 emits NO Linear state-drift conflict — best-effort, never fails the
    // sweep.
    Ticket ticket = fresh.get();
    if (asString(cached.get(META_SOURCE_STATUS_ID)) == null && ticket.sourceStatusId() != null) {
      snapshotLinearBaseline(row, cached, ticket);
    }
    return Decision.none();
  }

  private static Decision classifyLinearFailure(TicketSourceAdapterException failure) {
    IntegrationFailureCategory category = failure.failureCategory();
    return switch (category) {
      // Linear rate/network class — short-circuit remaining Linear links this tick (AC8).
      case SYNC_FAILURE, NETWORK_API_FAILURE, GITHUB_RATE_LIMITED -> Decision.backoff(category);
      case LINK_FAILURE, GITHUB_AUTH_FAILED, GITHUB_PERMISSION_DENIED ->
          Decision.conflict(
              IntegrationConflictCategory.LINK_BROKEN, category, "linear_permanent_access", null);
      default -> Decision.skip(category);
    };
  }

  private void snapshotLinearBaseline(
      IntegrationLinkScanRow row, Map<String, Object> cached, Ticket ticket) {
    Map<String, Object> updated = new LinkedHashMap<>(cached);
    updated.put(META_SOURCE_STATUS_ID, ticket.sourceStatusId());
    snapshotBaseline(row, updated, "linear");
  }

  private void snapshotGitHubBaseline(
      IntegrationLinkScanRow row, Map<String, Object> cached, PullRequest pr) {
    if (pr.state() == null) {
      return;
    }
    Map<String, Object> updated = new LinkedHashMap<>(cached);
    updated.put(META_PR_STATE, pr.state());
    snapshotBaseline(row, updated, "github");
  }

  private void snapshotBaseline(
      IntegrationLinkScanRow row, Map<String, Object> updatedMetadata, String integrationTag) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(updatedMetadata);
      scanPort.snapshotExternalMetadataBaseline(row.integrationLinkPublicId(), bytes);
    } catch (RuntimeException | JsonProcessingException baselineError) {
      // Provisional, best-effort — a baseline write failure must never break the sweep. The write
      // runs in its own REQUIRES_NEW tx (adapter), so this never poisons the phase-2 flow.
      log.debug(
          "integration-conflict {} baseline snapshot skipped integrationLinkId={} error={}",
          integrationTag,
          row.integrationLinkPublicId(),
          baselineError.getClass().getSimpleName());
    }
  }

  // ------------------------------------------------------------------------------------------------
  // Persistence (each conflict write in its own REQUIRES_NEW tx) + event emission + metrics.
  // ------------------------------------------------------------------------------------------------

  private void recordConflict(
      IntegrationLinkScanRow row,
      Decision decision,
      String integrationTag,
      String correlationId,
      Tally tally) {
    String conflictId = PublicIdPrefixes.INTEGRATION_CONFLICT.next();
    Instant detectedAt = Instant.now();
    NewIntegrationConflict request =
        new NewIntegrationConflict(
            conflictId,
            row.integrationLinkPublicId(),
            row.workflowRunPublicId(),
            decision.category().value(),
            internalSnapshotJson(row),
            externalSnapshotJson(row, decision, integrationTag),
            detectedAt);

    Boolean inserted =
        perLinkTemplate.execute(
            status -> {
              boolean wrote = writePort.insertIfAbsent(request);
              if (wrote) {
                emitConflictEvent(
                    row, decision, conflictId, integrationTag, correlationId, detectedAt);
              }
              return wrote;
            });

    if (Boolean.TRUE.equals(inserted)) {
      tally.conflictsDetected++;
      meterRegistry
          .counter(
              CONFLICT_DETECTED_COUNTER,
              "category",
              decision.category().value(),
              "integration",
              integrationTag)
          .increment();
      log.info(
          "integration conflict detected conflictId={} conflictCategory={} integrationLinkId={}"
              + " integration={} reason={}",
          conflictId,
          decision.category().value(),
          row.integrationLinkPublicId(),
          integrationTag,
          decision.reason());
      // Story 4.18 (AC4) — auto-pause the run on a NEW high-severity state-drift conflict. Runs
      // here, AFTER the conflict-write REQUIRES_NEW tx has committed (not inside it), on the
      // sweep's
      // lock-free phase-2 path; the handler is best-effort and swallows every pause failure so a
      // non-pausable run never aborts the sweep. Exactly-once per (link, category) via the dedup.
      conflictAutoPauseHandler.maybeAutoPause(
          row.workflowRunPublicId(), conflictId, decision.category(), correlationId);
    } else {
      tally.skippedDuplicate++;
      log.debug(
          "integration-conflict SWEEP dedup-skip conflictCategory={} integrationLinkId={}",
          decision.category().value(),
          row.integrationLinkPublicId());
    }
  }

  private void emitConflictEvent(
      IntegrationLinkScanRow row,
      Decision decision,
      String conflictId,
      String integrationTag,
      String correlationId,
      Instant detectedAt) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.CONFLICT_ID, conflictId);
    details.put(WorkflowEventDetailKeys.CONFLICT_CATEGORY, decision.category().value());
    details.put(WorkflowEventDetailKeys.WORKFLOW_RUN_ID, row.workflowRunPublicId());
    details.put(WorkflowEventDetailKeys.REASON, decision.reason());
    if (decision.failureCategory() != null) {
      details.put(WorkflowEventDetailKeys.FAILURE_CATEGORY, decision.failureCategory().value());
    }
    if (GITHUB_INTEGRATION_TAG.equals(integrationTag)) {
      details.put(WorkflowEventDetailKeys.GITHUB_PR_REFERENCE, row.externalRef());
      if (decision.prState() != null) {
        details.put(WorkflowEventDetailKeys.PR_STATE, decision.prState());
      }
    } else {
      details.put(WorkflowEventDetailKeys.LINEAR_TICKET_REFERENCE, row.externalRef());
    }
    if (correlationId != null && !correlationId.isBlank()) {
      details.put(WorkflowEventDetailKeys.CORRELATION_ID, correlationId);
    }
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            row.workflowRunPublicId(),
            WorkflowEventType.INTEGRATION_CONFLICT_DETECTED,
            null,
            null,
            SYSTEM_ACTOR_IDENTITY,
            ActorType.SYSTEM,
            "integration_conflict_detected",
            null,
            false,
            OffsetDateTime.ofInstant(detectedAt, ZoneOffset.UTC),
            details));
  }

  private String internalSnapshotJson(IntegrationLinkScanRow row) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("currentState", row.currentState());
    return toJson(snapshot);
  }

  private String externalSnapshotJson(
      IntegrationLinkScanRow row, Decision decision, String integrationTag) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("integration", integrationTag);
    snapshot.put("externalRef", row.externalRef());
    snapshot.put("reason", decision.reason());
    if (decision.prState() != null) {
      snapshot.put("freshPrState", decision.prState());
    }
    if (decision.failureCategory() != null) {
      // AC5 — store the classifying IntegrationFailureCategory value in the external snapshot.
      snapshot.put("failureCategory", decision.failureCategory().value());
    }
    return toJson(snapshot);
  }

  private String toJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException error) {
      // Snapshots carry only ids/states/reasons — serialization cannot realistically fail; fall
      // back
      // to an empty object so a conflict is still recorded.
      return "{}";
    }
  }

  private Map<String, Object> decodeMetadata(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(
          bytes, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (java.io.IOException error) {
      log.debug("integration-conflict SWEEP unparseable external_metadata — treating as empty");
      return Map.of();
    }
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * Story 4.30 (AC1) — {@code true} when the scan row's run is in a terminal {@link WorkflowState}
   * ({@code Completed}/{@code TakenOver}/{@code Reconciled}). Defensive: a null or unparseable
   * state string is treated as NON-terminal so a bad state value never silently drops a genuine
   * conflict — detection proceeds and the row is written as before this story.
   */
  private static boolean isTerminalRun(String currentState) {
    if (currentState == null || currentState.isBlank()) {
      return false;
    }
    try {
      return WorkflowState.fromValue(currentState, null).isTerminal();
    } catch (RuntimeException unparseable) {
      // A bad/unknown state string must never silently drop a genuine conflict — proceed as
      // non-terminal (detection writes the row exactly as before this story).
      log.debug(
          "integration-conflict SWEEP unparseable currentState={} — treating run as non-terminal",
          currentState);
      return false;
    }
  }

  /** Mutable per-sweep accumulator folded into a {@link SweepResult}. */
  private static final class Tally {
    private int scanned;
    private int conflictsDetected;
    private int skippedDuplicate;
    private boolean batchLimitHit;
    private boolean rateLimited;

    private SweepResult toResult() {
      return new SweepResult(
          scanned, conflictsDetected, skippedDuplicate, batchLimitHit, rateLimited);
    }
  }

  /**
   * One keyset scan window for an integration type: the (trimmed-to-{@code batchLimit}) rows, the
   * {@code more} flag (a {@code batchLimit + 1} row was available), and the {@code startCursor} the
   * window was read from (so a mid-batch back-off can resume there).
   */
  private record ScanWindow(
      String integrationType, List<IntegrationLinkScanRow> rows, boolean more, long startCursor) {

    private static ScanWindow empty(String integrationType) {
      return new ScanWindow(integrationType, List.of(), false, 0L);
    }
  }

  /**
   * Classification outcome for one link. Exactly one of: a conflict ({@code category != null}), a
   * back-off ({@code backoff}), a transient skip ({@code skip}), or none.
   */
  private record Decision(
      IntegrationConflictCategory category,
      IntegrationFailureCategory failureCategory,
      String reason,
      boolean backoff,
      boolean skip,
      String prState) {

    private static Decision conflict(
        IntegrationConflictCategory category,
        IntegrationFailureCategory failureCategory,
        String reason,
        String prState) {
      return new Decision(category, failureCategory, reason, false, false, prState);
    }

    private static Decision backoff(IntegrationFailureCategory failureCategory) {
      return new Decision(null, failureCategory, "rate_or_network_backoff", true, false, null);
    }

    private static Decision skip(IntegrationFailureCategory failureCategory) {
      return new Decision(null, failureCategory, "transient_skip", false, true, null);
    }

    private static Decision none() {
      return new Decision(null, null, "no_conflict", false, false, null);
    }
  }
}
