package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3h-0 (B1 extraction) — the ONE shared, tested helper for the replay-safe {@code
 * afterCommit} side-effect pattern that three hook sites ({@code RunDependencyReleaseService} 3f-3,
 * {@code RunSplitCompletionRollupService} 3f-7, {@code SplitRollupReconciliationSweepService} 3f-8)
 * independently re-derived — and that has been mis-derived twice (the 3f post-commit {@code
 * REQUIRES_NEW} trap and the 3g stale-entity token clobber). New async hooks MUST consume this
 * helper instead of hand-rolling the machinery again. [ADR 0032]
 *
 * <p>Two composable layers:
 *
 * <ul>
 *   <li><strong>Layer A — {@link #runAfterCommit(String, String, Runnable)}:</strong> register a
 *       best-effort side effect that runs once the CURRENT transaction commits. Guards on an active
 *       synchronization (WARN + skip when there is none), fires the effect from {@code
 *       afterCommit}, and swallows any {@link RuntimeException} so the already-committed transition
 *       can NEVER be rolled back by a failing side effect.
 *   <li><strong>Layer B — {@link #runInNewTransaction(String, String, Runnable)}:</strong> run a
 *       side effect in its OWN {@code PROPAGATION_REQUIRES_NEW} transaction (the fix for the 3f
 *       trap: an {@code afterCommit} hook doing JPA work must suspend the dying original
 *       transaction's resources and bind a fresh persistence context — a plain {@code REQUIRED}
 *       would join the dying tx and throw). Any tx-scoped work the caller wants (e.g. an advisory
 *       lock) goes as the FIRST statement of the supplied {@code work} — the helper stays
 *       domain-agnostic and never references a lock or a port.
 * </ul>
 *
 * <p>The hooks compose A over B; the sweep uses B alone. The helper is a leaf bean (its only
 * collaborator is {@link PlatformTransactionManager}), so it injects directly with no DI cycle. It
 * deliberately does NOT resolve any lazy collaborator and does NOT call {@code
 * WorkflowTransitionService.transition(...)} — the caller's {@code Runnable} owns the {@code
 * ObjectProvider.getIfAvailable()} null-guard and the state mutation, keeping the {@code
 * only_workflow_transition_service_may_mutate_workflow_state} ArchUnit boundary intact.
 *
 * <p><strong>No-clobber save rule (documented, not enforced here — [ADR 0032]):</strong> any
 * out-of-band {@code REQUIRES_NEW} metadata write onto a row that a full-row {@code UPDATE} (from a
 * possibly-stale managed entity) also touches requires {@code @DynamicUpdate} (narrow the UPDATE to
 * dirty columns) or an explicit re-read-before-save on that entity — otherwise the stale full-row
 * save clobbers the freshly-committed columns back (the 3g token clobber, {@code
 * RunnerExecutionTokenUsagePersistenceIT#terminalTransitionDoesNotClobberTokenUsage}).
 *
 * <p>Annotated {@link Component} (NOT {@code @Service}) on purpose: it is infrastructure plumbing,
 * not a domain service, so the {@code APPLICATION_SERVICES_MUST_BE_NAMED_AS_SERVICES} rule — which
 * binds only {@code @Service} beans — does not force a {@code *Service}/{@code *Orchestrator}
 * suffix onto a {@code *Runner} plumbing name.
 */
@Component
public class AfterCommitSideEffectRunner {

  private static final Logger log = LoggerFactory.getLogger(AfterCommitSideEffectRunner.class);

  private final TransactionTemplate requiresNewTx;

  public AfterCommitSideEffectRunner(PlatformTransactionManager transactionManager) {
    this.requiresNewTx = new TransactionTemplate(transactionManager);
    this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Layer A — register a best-effort {@code sideEffect} to run once the CURRENT transaction
   * commits. When there is no active transaction synchronization (e.g. a direct non-transactional
   * call path) nothing is hooked (WARN + return). The {@code afterCommit} callback logs an INFO
   * "fired" line and runs {@code sideEffect} under the same swallow-and-WARN guard as Layer B, so a
   * failing side effect can never roll the already-committed transition back.
   *
   * @param label short human-readable label for this hook, used as the log-line prefix (e.g. {@code
   *     "split-rollup hook"})
   * @param contextId the correlating id carried on every log line (the run / parent public id)
   * @param sideEffect the effect to run post-commit; typically resolves a lazy collaborator (with
   *     its own null-guard) and delegates into {@link #runInNewTransaction}
   */
  public void runAfterCommit(String label, String contextId, Runnable sideEffect) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn(
          "{} not registered (no active transaction synchronization) contextId={}",
          label,
          contextId);
      return;
    }
    log.info("{} registered contextId={} (afterCommit)", label, contextId);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            log.info("{} fired contextId={} (post-commit)", label, contextId);
            swallow(label, contextId, sideEffect);
          }
        });
  }

  /**
   * Layer B — run {@code work} in its OWN {@code PROPAGATION_REQUIRES_NEW} transaction, swallowing
   * and WARN-logging any {@link RuntimeException} (best-effort). Put any tx-scoped step (advisory
   * lock, {@code FOR UPDATE} read) as the FIRST statement of {@code work}; it runs inside the fresh
   * transaction. Safe to call from an {@code afterCommit} hook (Layer A) or directly (the sweep).
   *
   * @param label short human-readable label, used as the log-line prefix (e.g. {@code
   *     "split-rollup"})
   * @param contextId the correlating id carried on every log line
   * @param work the side-effect body to run in the new transaction
   */
  public void runInNewTransaction(String label, String contextId, Runnable work) {
    swallow(label, contextId, () -> requiresNewTx.executeWithoutResult(status -> work.run()));
  }

  private void swallow(String label, String contextId, Runnable body) {
    try {
      body.run();
      log.debug("{} side-effect completed contextId={}", label, contextId);
    } catch (RuntimeException error) {
      // Best-effort: the committed transition that scheduled this side effect must stay intact, so
      // any escape is swallowed with a WARN (never an ERROR — nothing upstream failed). The
      // caller's
      // own idempotency key + in-tx state re-check make a swallowed-then-retried effect safe.
      log.warn(
          "{} swallowed an error (completion intact) contextId={} cause={}",
          label,
          contextId,
          error.getClass().getSimpleName());
    }
  }
}
