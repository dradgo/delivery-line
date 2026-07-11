package org.dradgo.application.workflow.spi;

import java.util.List;
import java.util.Optional;

/**
 * Story 3h-5 (AC2/AC6, Decision 6) — persistence port for the CI-investigation columns on {@code
 * workflow_runs}. Every write here is a <strong>targeted raw-SQL {@code UPDATE ... WHERE public_id
 * = :publicId}</strong>, never a {@code WorkflowRunEntity} save: the entity has no
 * {@code @DynamicUpdate} and carries an optimistic-lock {@code version}, so a stale-entity full-row
 * UPDATE would clobber concurrent writes (the 3g token-usage bug —
 * token-usage-clobbered-by-terminal-transition). The {@code transition()} call the fix loop makes
 * still goes through {@code WorkflowTransitionService} normally; only the {@code ci_*} columns are
 * written here.
 *
 * <p>The sweep-lock + per-run-lock methods are <strong>not</strong> {@code @Transactional} in the
 * adapter — they join the caller's transaction so the transaction-scoped {@code
 * pg_advisory_xact_lock} is held for the intended scope and auto-released on that transaction's
 * commit.
 */
public interface CiStatusPort {

  /**
   * Stamp a run pending a CI poll after a successful backend push (auto or approve mode): {@code
   * ci_status='pending', ci_head_sha=:sha, ci_poll_attempts=0, ci_last_polled_at=null}. Called only
   * when the resolved repo host reports {@code supportsCiStatusReads=true}.
   */
  void markCiPollPending(String workflowRunPublicId, String ciHeadSha);

  /**
   * Bump {@code ci_poll_attempts} and stamp {@code ci_last_polled_at=now()}, returning the new
   * attempt count (single {@code UPDATE ... RETURNING}). Used by the sweep to bound retries.
   */
  int recordCiPollAttempt(String workflowRunPublicId);

  /**
   * Record a resolved CI verdict: {@code ci_status=:status, ci_last_polled_at=now()}. {@code
   * status} is one of the CHECKed lowercase values ({@code success} / {@code failure} / {@code
   * neutral} / {@code unavailable}).
   */
  void recordCiStatus(String workflowRunPublicId, String status);

  /**
   * Keyset-paginated scan of runs still awaiting a CI verdict: {@code where ci_status='pending' and
   * archived_at is null and id > :afterSeq order by id asc limit :batchLimit}. Not a bare {@code
   * LIMIT} — keyset pagination on the raw monotonic {@code id} so the sweep advances past a batch
   * across ticks instead of starving the tail.
   */
  List<CiPollRow> findRunsAwaitingCiStatus(long afterSeq, int batchLimit);

  /**
   * Read a run's CI-investigation columns ({@code ci_status}, {@code ci_head_sha}, {@code
   * ci_fix_loop_count}) for the detail read model (AC3). Empty when the run does not exist. Read
   * via targeted SQL — the {@code ci_*} columns are not mapped on {@code WorkflowRunEntity}
   * (Decision 6).
   */
  Optional<CiRunView> readCiView(String workflowRunPublicId);

  /**
   * Read the current {@code current_state} for a run as a fresh DB read (never a cached entity),
   * for the red-CI CAS: the fix loop re-reads this under the per-run advisory lock and skips the
   * re-dispatch unless it is still {@code WAITING_FOR_REVIEW} (Decision 1). Empty when the run does
   * not exist.
   */
  Optional<String> readCurrentState(String workflowRunPublicId);

  /**
   * Acquire the sweep-wide transaction-scoped advisory lock so only one scheduler instance scans at
   * a time. Joins the caller's transaction (NOT {@code REQUIRES_NEW}); auto-released on commit.
   */
  void acquireCiSweepLock();

  /**
   * Acquire the per-run transaction-scoped advisory lock ({@code pg_advisory_xact_lock(classifier,
   * hashtext(runId))}) so the red-CI CAS + re-dispatch for a single run is serialized against a
   * concurrent REVIEW harvest / operator accept, while different runs never contend. Joins the
   * caller's ({@code REQUIRES_NEW}) transaction.
   */
  void lockRunForCiInvestigation(String workflowRunPublicId);
}
