package org.dradgo.application.integration.conflict;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Story 4.18 (AC4/AC5 / Reconciliation 4/5) — the conflict-driven auto-pause seam. Invoked from the
 * story-4.17 {@code IntegrationConflictDetectionService.recordConflict} post-insert {@code
 * wrote==true} branch (exactly once per new {@code (link, category)} via the insert-or-skip dedup —
 * no per-tick spam), it pauses the run (via {@link RecoveryService#pause}) when the newly-detected
 * conflict's category is in the configured auto-pause set ({@code
 * deliveryline.integration.conflict-detection.auto-pause-on-categories}, default {@code
 * [external_state_advanced, external_state_reverted]} per NFR21).
 *
 * <p><strong>Best-effort by contract.</strong> The pause runs on the sweep's lock-free phase-2 path
 * AFTER the conflict-write transaction commits, and every failure is swallowed with a log so a
 * non-pausable run (terminal / already-{@code Paused} / {@code TakenOver}) or any transient pause
 * fault never aborts the sweep. The non-terminal / non-{@code Paused} / non-{@code TakenOver}
 * precondition is enforced BY {@code pause}'s own {@code PAUSABLE_SOURCE_STATES} gate ({@code
 * PAUSE_NOT_APPLICABLE}), so this handler does NOT re-check the run's state before calling.
 *
 * <p><strong>Actor + idempotency.</strong> The pause is stamped with {@link ActorContext#SYSTEM}
 * (identity {@code system}, type {@code SYSTEM}) — which, via the story-4.18 pause-prep change,
 * records {@code reviewer_role='system'} so the audit trail distinguishes an auto-pause from an
 * operator pause. The idempotency key is deterministic from the conflict ({@code
 * "autopause-conflict-" + conflictId}) so a re-invocation replays idempotently instead of
 * double-pausing.
 *
 * <p>{@code RecoveryService} is injected lazily via {@link ObjectProvider} — it is a large (21-arg)
 * bean and the detection sweep is not on any startup-critical path, so lazy resolution avoids
 * init-order surprises. There is NO {@code application.integration.conflict → application.recovery}
 * ArchUnit dependency rule (story 4.28 lifted RecoveryService scope-protection, ADR 0033).
 */
@Component
public class ConflictAutoPauseHandler {

  private static final Logger log = LoggerFactory.getLogger(ConflictAutoPauseHandler.class);

  /** AC4 — the fixed reason text stamped on the auto-pause recovery action + event. */
  static final String AUTO_PAUSE_REASON = "auto_paused_on_state_conflict";

  /** AC4 — the deterministic idempotency-key prefix (replay-safe per conflict). */
  static final String IDEMPOTENCY_KEY_PREFIX = "autopause-conflict-";

  private final ObjectProvider<RecoveryService> recoveryServiceProvider;
  // Resolved once from the (immutable) properties so an unknown-token WARN fires at most once at
  // construction, not per detected conflict. Empty = auto-pause disabled (AC5 opt-out).
  private final Set<IntegrationConflictCategory> autoPauseCategories;

  public ConflictAutoPauseHandler(
      ObjectProvider<RecoveryService> recoveryServiceProvider,
      IntegrationConflictDetectionProperties properties) {
    this.recoveryServiceProvider =
        Objects.requireNonNull(recoveryServiceProvider, "recoveryServiceProvider");
    Objects.requireNonNull(properties, "properties");
    this.autoPauseCategories = resolveCategories(properties.autoPauseOnCategories());
    log.info(
        "conflict auto-pause configured categories={}",
        autoPauseCategories.stream().map(IntegrationConflictCategory::value).toList());
  }

  private static Set<IntegrationConflictCategory> resolveCategories(List<String> rawTokens) {
    EnumSet<IntegrationConflictCategory> resolved =
        EnumSet.noneOf(IntegrationConflictCategory.class);
    if (rawTokens == null) {
      return resolved;
    }
    for (String token : rawTokens) {
      try {
        resolved.add(IntegrationConflictCategory.fromValue(token, "autoPauseOnCategories"));
      } catch (DomainException unknown) {
        // Never throw on config (memory: validated-config-needs-test-yaml) — skip the bad token so
        // a
        // typo in one category does not disable the whole detection sweep at boot.
        log.warn(
            "conflict auto-pause skipping unknown category token token={} — check"
                + " deliveryline.integration.conflict-detection.auto-pause-on-categories",
            token);
      }
    }
    return resolved;
  }

  /**
   * Pause {@code workflowRunId} if {@code category} is in the configured auto-pause set. A no-op
   * for an unconfigured category or when auto-pause is disabled (empty set). Best-effort: swallows
   * every pause failure with a log so the caller's detection sweep is never aborted.
   *
   * @param workflowRunId the run whose conflict was just detected
   * @param conflictId the new conflict's public id (drives the deterministic idempotency key)
   * @param category the detected conflict's category
   * @param correlationId the sweep correlation id (threaded onto the pause actor), or {@code null}
   */
  public void maybeAutoPause(
      String workflowRunId,
      String conflictId,
      IntegrationConflictCategory category,
      String correlationId) {
    if (category == null || !autoPauseCategories.contains(category)) {
      log.debug(
          "conflict auto-pause skipped (category not configured) workflowRunId={} conflictId={}"
              + " conflictCategory={}",
          workflowRunId,
          conflictId,
          category == null ? null : category.value());
      return;
    }
    RecoveryService recoveryService = recoveryServiceProvider.getIfAvailable();
    if (recoveryService == null) {
      log.warn(
          "conflict auto-pause skipped (RecoveryService unavailable) workflowRunId={} conflictId={}",
          workflowRunId,
          conflictId);
      return;
    }
    ActorContext actor =
        correlationId == null || correlationId.isBlank()
            ? ActorContext.SYSTEM
            : new ActorContext("system", ActorType.SYSTEM, correlationId);
    // The idempotency key must match the opaque-key pattern [A-Za-z0-9-]{16,128}; conflict public
    // ids carry a '_' separator (icf_...), so sanitize '_' → '-'. Deterministic + collision-free
    // (the random id body is alphanumeric), so a re-invocation still replays instead of re-pausing.
    String idempotencyKey = (IDEMPOTENCY_KEY_PREFIX + conflictId).replace('_', '-');
    try {
      log.info(
          "auto-pausing run on state conflict workflowRunId={} conflictId={} conflictCategory={}"
              + " idempotencyKey={} actorIdentity={}",
          workflowRunId,
          conflictId,
          category.value(),
          idempotencyKey,
          actor.actorIdentity());
      recoveryService.pause(workflowRunId, idempotencyKey, actor, AUTO_PAUSE_REASON);
    } catch (DomainException error) {
      if (error.errorCode() == DomainErrorCode.PAUSE_NOT_APPLICABLE) {
        // The run is terminal / already Paused / TakenOver — pause's own gate rejected it.
        // Expected;
        // WARN + swallow so the sweep continues (AC4 non-pausable precondition).
        log.warn(
            "auto-pause not applicable (run not in a pausable state) workflowRunId={} conflictId={}"
                + " conflictCategory={}",
            workflowRunId,
            conflictId,
            category.value());
        return;
      }
      // Any other domain error is unexpected for this best-effort side effect — WARN + swallow so a
      // single bad run never aborts the detection sweep.
      log.warn(
          "auto-pause failed workflowRunId={} conflictId={} errorCode={} — sweep continues",
          workflowRunId,
          conflictId,
          error.errorCode().value());
    } catch (RuntimeException unexpected) {
      log.warn(
          "auto-pause failed (unexpected) workflowRunId={} conflictId={} errorClass={} — sweep"
              + " continues",
          workflowRunId,
          conflictId,
          unexpected.getClass().getSimpleName());
    }
  }
}
