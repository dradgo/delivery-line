package org.dradgo.application.integration.conflict;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.application.integration.conflict.spi.TerminalRunConflict;
import org.dradgo.application.recovery.spi.RecoveryActionRecordPort;
import org.dradgo.application.recovery.spi.RecoveryActionSnapshot;
import org.dradgo.application.recovery.spi.RecoveryActionWriteCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 4.30 (AC2/AC3/AC5, Reconciliation 2/3/6) — the BACKSTOP half of the terminal-run conflict
 * guard pair: a self-healing sweep that clears {@code integration_conflicts} rows stranded on a run
 * that has already terminalized ({@code Completed}/{@code TakenOver}/{@code Reconciled}).
 *
 * <p><strong>The gap this closes.</strong> Story 4.6's D1 patch made reconcile
 * <em>per-conflict</em> (a run terminalizes to {@code Reconciled} only when its last unresolved
 * conflict is cleared); its P3a per-run advisory lock closed manifestation (a). This sweep closes
 * manifestation (b): the 4.17 detection sweep does not hold the reconcile lock, so it can {@code
 * insertIfAbsent} a fresh conflict for a run that terminalized concurrently. That conflict is
 * unresolvable — {@code RecoveryService.reconcile} rejects terminal runs ({@code
 * RECONCILE_NOT_APPLICABLE}) and the 4.6 P1 overlay no longer advertises reconcile there — so it
 * sits {@code resolved_at IS NULL} forever and {@code countUnresolvedByCategoryAndIntegration}
 * over-reports. The detector's terminal-run guard (Reconciliation 1) prevents the common case at
 * the source; this sweep covers (a) rows stranded BEFORE this story shipped and (b) the
 * sub-millisecond TOCTOU window the guard cannot see.
 *
 * <p><strong>What "clear" means (OQ-1 = SYSTEM-resolve).</strong> A terminal run is done;
 * re-opening it is out of scope (it would reverse a governed terminal transition). For each
 * stranded conflict the sweep records a SYSTEM-actor {@code recovery_actions} row ({@code
 * action_type='reconcile'}, {@code actor_type='system'}, {@code reviewer_role='system'}, {@code
 * result_status='succeeded'}) and a {@code RECOVERY_RECONCILED} audit event, then marks the
 * conflict resolved — so unresolved counts stop over-reporting, the partial-unique dedup index
 * frees for a genuine future re-detect, and the audit trail records WHY it was auto-cleared (never
 * an anonymous DB mutation — AC3). It adds NO new {@code ReconciliationDecision} registry value:
 * {@code SYSTEM_TERMINAL_RUN} would be an operator-facing decision (OpenAPI/FE fan-out) for a
 * purely system-internal auto-clear, so the event's SYSTEM actor + reason carry the semantics
 * instead.
 *
 * <p><strong>Safety / boundary (AC3, Reconciliation 6).</strong> Each conflict is cleared in its
 * own bounded transaction that FIRST takes the P3a per-run reconcile advisory lock ({@link
 * IntegrationConflictService#lockRunForReconcile}) so the sweep serializes against a live operator
 * reconcile of the same run, then routes the mark-resolved through {@link
 * IntegrationConflictService#resolveConflict} — keeping the write inside {@code
 * application.integration.conflict} (the {@code
 * ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS} ArchUnit boundary). {@code
 * resolveConflict}'s idempotent {@code WHERE resolved_at IS NULL} makes a concurrent clear a no-op:
 * it throws {@code CONFLICT_ALREADY_RESOLVED}, this rolls the per-conflict tx back cleanly and
 * skips. There is NO external I/O under the lock (unlike operator reconcile's post-commit
 * side-effect) — the sweep is pure internal bookkeeping.
 *
 * <p>Framework-trigger-free by design (mirrors {@code SplitRollupReconciliationSweepService}):
 * plain application logic invoked by the {@code @ConditionalOnProperty}-gated {@code @Scheduled}
 * trigger in {@code infrastructure.config}, keeping the application layer free of Spring scheduling
 * annotations.
 */
@Service
public class IntegrationConflictTerminalRunReconciliationSweepService {

  private static final Logger log =
      LoggerFactory.getLogger(IntegrationConflictTerminalRunReconciliationSweepService.class);

  private static final String ACTION_TYPE_RECONCILE = "reconcile";
  private static final String REVIEWER_ROLE_SYSTEM = "system";
  private static final String RESULT_STATUS_SUCCEEDED = "succeeded";
  private static final String SYSTEM_ACTOR_IDENTITY = "system";
  private static final String AUTO_CLEAR_REASON =
      "integration conflict auto-cleared: detected on a run already in a terminal state";
  // Deterministic per-conflict idempotency key for the recovery_actions row. A cleared conflict
  // never reappears in the terminal-run scan (resolved_at is set), and a failed clear rolls the
  // whole per-conflict tx (row + event) back, so this key can never collide with a committed row.
  private static final String IDEMPOTENCY_KEY_PREFIX = "terminal-run-sweep:";

  private final IntegrationConflictReadPort readPort;
  private final IntegrationConflictService conflictService;
  private final RecoveryActionRecordPort recoveryActionRecordPort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final IntegrationConflictTerminalSweepProperties properties;
  private final Clock clock;
  // One short bounded tx per stranded conflict (lock + event + recovery_action + markResolved). The
  // scheduler drives sweep() with no ambient tx, so each execute() opens a fresh tx; a throw rolls
  // that single conflict back and the next conflict gets a clean tx (no shared-tx poisoning).
  private final TransactionTemplate perConflictTemplate;

  /**
   * Spring constructor. There is no {@code Clock} bean in the app (mirrors {@code
   * RecoveryService}), so this defaults {@link Clock#systemUTC()} and delegates to the {@code
   * Clock}-accepting constructor the tests use for a fixed clock.
   */
  @Autowired
  public IntegrationConflictTerminalRunReconciliationSweepService(
      IntegrationConflictReadPort readPort,
      IntegrationConflictService conflictService,
      RecoveryActionRecordPort recoveryActionRecordPort,
      WorkflowEventWritePort workflowEventWritePort,
      IntegrationConflictTerminalSweepProperties properties,
      PlatformTransactionManager transactionManager) {
    this(
        readPort,
        conflictService,
        recoveryActionRecordPort,
        workflowEventWritePort,
        properties,
        Clock.systemUTC(),
        transactionManager);
  }

  IntegrationConflictTerminalRunReconciliationSweepService(
      IntegrationConflictReadPort readPort,
      IntegrationConflictService conflictService,
      RecoveryActionRecordPort recoveryActionRecordPort,
      WorkflowEventWritePort workflowEventWritePort,
      IntegrationConflictTerminalSweepProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.readPort = Objects.requireNonNull(readPort, "readPort");
    this.conflictService = Objects.requireNonNull(conflictService, "conflictService");
    this.recoveryActionRecordPort =
        Objects.requireNonNull(recoveryActionRecordPort, "recoveryActionRecordPort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.perConflictTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Run one terminal-run reconciliation tick: discover unresolved conflicts on terminal runs
   * (bounded by the configured batch limit) and SYSTEM-resolve each. Never throws — a per-conflict
   * failure is swallowed (WARN) and retried next tick; a concurrent live reconcile that already
   * cleared the row is a benign skip.
   *
   * @return a summary of how many stranded conflicts were found and how many were actually cleared
   */
  public SweepResult sweep() {
    int batchLimit = properties.batchLimit();
    List<TerminalRunConflict> stranded = readPort.findUnresolvedConflictsOnTerminalRuns(batchLimit);
    int found = stranded.size();
    int cleared = 0;

    for (TerminalRunConflict conflict : stranded) {
      if (clearOne(conflict)) {
        cleared++;
        // AC2 — per-item WARN AFTER the clear committed: a stranded terminal-run conflict was
        // auto-cleared. ids/states only, no snapshots/payloads.
        log.warn(
            "integration-conflict TERMINAL-RUN SWEEP auto-cleared stranded conflict conflictId={}"
                + " workflowRunId={} currentState={}",
            conflict.conflictId(),
            conflict.workflowRunId(),
            conflict.currentState());
      }
    }

    boolean batchLimitHit = found == batchLimit;
    if (batchLimitHit) {
      // No silent truncation (AC5): the batch was full, so more stranded conflicts may remain.
      log.warn(
          "integration-conflict TERMINAL-RUN SWEEP hit batch limit — more stranded conflicts may"
              + " remain, healing next tick batchLimit={} found={} cleared={}",
          batchLimit,
          found,
          cleared);
    }
    log.info(
        "integration-conflict TERMINAL-RUN SWEEP tick complete found={} cleared={} batchLimit={}",
        found,
        cleared,
        batchLimit);
    return new SweepResult(found, cleared, batchLimitHit);
  }

  /**
   * SYSTEM-resolve one stranded conflict in its own bounded transaction. Returns {@code true} when
   * the row was cleared, {@code false} on a benign already-resolved race or a swallowed
   * per-conflict error (retried next tick). Never propagates.
   */
  private boolean clearOne(TerminalRunConflict conflict) {
    try {
      Boolean resolved =
          perConflictTemplate.execute(
              status -> {
                resolveTerminalRunConflict(conflict);
                return Boolean.TRUE;
              });
      return Boolean.TRUE.equals(resolved);
    } catch (DomainException domainError) {
      if (domainError.errorCode() == DomainErrorCode.CONFLICT_ALREADY_RESOLVED) {
        // Lost the race to a concurrent live reconcile (or an overlapping tick) — the row is
        // already resolved and the per-conflict tx rolled back cleanly. Benign, not a failure.
        log.debug(
            "integration-conflict TERMINAL-RUN SWEEP conflictId={} already resolved concurrently —"
                + " skipping",
            conflict.conflictId());
        return false;
      }
      log.warn(
          "integration-conflict TERMINAL-RUN SWEEP swallowed clear error conflictId={}"
              + " workflowRunId={} errorCode={}",
          conflict.conflictId(),
          conflict.workflowRunId(),
          domainError.errorCode());
      return false;
    } catch (RuntimeException unexpected) {
      // A per-conflict failure never aborts the sweep (mirrors the 4.17 per-link swallow) — WARN
      // with ids + exception type and heal on the next tick.
      log.warn(
          "integration-conflict TERMINAL-RUN SWEEP swallowed clear error conflictId={}"
              + " workflowRunId={} error={}",
          conflict.conflictId(),
          conflict.workflowRunId(),
          unexpected.getClass().getSimpleName());
      return false;
    }
  }

  private void resolveTerminalRunConflict(TerminalRunConflict conflict) {
    String runId = conflict.workflowRunId();
    String conflictId = conflict.conflictId();
    // The scan already filtered to terminal wire values; parse for the event's prior/resulting
    // state.
    WorkflowState terminalState = WorkflowState.fromValue(conflict.currentState(), "currentState");

    // 1. Serialize against a live operator reconcile of the SAME run (P3a per-run advisory lock),
    // released on this tx's commit/rollback. Must be first, before the resolve read/write below.
    conflictService.lockRunForReconcile(runId);

    // 2. RECOVERY_RECONCILED audit event — SYSTEM actor; the run itself does NOT change state
    // (prior == resulting == the terminal state). No RECONCILIATION_DECISION detail: this is a
    // system-internal auto-clear, not an operator decision. The AUTO_CLEARED=true flag is the
    // structured discriminator so a timeline consumer can tell this SYSTEM strand-clear from an
    // operator reconcile — the RECOVERY_RECONCILED type is reused for every terminal state, so on a
    // Completed/TakenOver run (where "reconciled" alone would read oddly) this flag disambiguates.
    String recoveryEventPublicId = PublicIdPrefixes.WORKFLOW_EVENT.next();
    Map<String, Object> eventDetails = new LinkedHashMap<>();
    eventDetails.put(WorkflowEventDetailKeys.CONFLICT_ID, conflictId);
    eventDetails.put(WorkflowEventDetailKeys.WORKFLOW_RUN_ID, runId);
    eventDetails.put(WorkflowEventDetailKeys.AUTO_CLEARED, true);
    eventDetails.put(WorkflowEventDetailKeys.REASON, AUTO_CLEAR_REASON);
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            recoveryEventPublicId,
            runId,
            WorkflowEventType.RECOVERY_RECONCILED,
            terminalState,
            terminalState,
            SYSTEM_ACTOR_IDENTITY,
            ActorType.SYSTEM,
            AUTO_CLEAR_REASON,
            null,
            true,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
            eventDetails));

    // 3. SYSTEM-actor recovery_actions row so resolved_by_action_id FK is satisfied AND the audit
    // trail records WHY it was auto-cleared. Inserted 'succeeded' directly (like classify_failure,
    // 4.9 R16): the whole effect commits in this one tx — there is no post-commit side-effect a
    // 'pending' row could wait on.
    RecoveryActionSnapshot recoveryAction =
        recoveryActionRecordPort.insert(
            new RecoveryActionWriteCommand(
                runId,
                ACTION_TYPE_RECONCILE,
                null,
                recoveryEventPublicId,
                SYSTEM_ACTOR_IDENTITY,
                ActorType.SYSTEM,
                IDEMPOTENCY_KEY_PREFIX + conflictId,
                RESULT_STATUS_SUCCEEDED,
                REVIEWER_ROLE_SYSTEM));

    // 4. Mark resolved via the in-package service (write-boundary + MANDATORY tx). Its idempotent
    // WHERE resolved_at IS NULL throws CONFLICT_ALREADY_RESOLVED on a concurrent clear → this tx
    // rolls back and clearOne treats it as a benign skip.
    conflictService.resolveConflict(conflictId, runId, recoveryAction.publicId(), clock.instant());
  }

  /**
   * Per-tick summary. {@code found} = unresolved conflicts on terminal runs discovered this tick;
   * {@code cleared} = those confirmed SYSTEM-resolved; {@code batchLimitHit} = the discovery filled
   * the batch (more may remain for the next tick).
   */
  public record SweepResult(int found, int cleared, boolean batchLimitHit) {}
}
