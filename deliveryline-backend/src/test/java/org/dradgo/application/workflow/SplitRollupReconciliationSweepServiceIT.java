package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3f-8 (AC6) — end-to-end coverage of the split-rollup reconciliation sweep over the real
 * wiring (Testcontainers Postgres). NOT {@code @Transactional}: the sweep drives {@code Split ->
 * Completed} through {@code WorkflowTransitionService.transition()}, whose post-commit hook chain
 * (recursion + 3f-3 release) fires only after a genuine commit (a {@code @Transactional} test rolls
 * that back), so each test commits real rows and {@code @AfterEach} cleans them up (mirrors {@code
 * RunSplitCompletionRollupIT}).
 *
 * <p>The strand is simulated by creating the children directly in {@code Completed} (so the 3f-7
 * {@code afterCommit} rollup hook never fired for them) while leaving the parent in {@code Split} —
 * exactly the state a transient hook failure leaves behind. The sweep is invoked directly (no
 * scheduler wait); the {@code @Scheduled} trigger stays disabled in the shared test profile.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class SplitRollupReconciliationSweepServiceIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private SplitRollupReconciliationSweepService sweep;
  @Autowired private RunSplitCompletionRollupService rollupService;

  @AfterEach
  void cleanDatabase() {
    // Null the self-referencing parent_run_id (ON DELETE RESTRICT) before the bulk delete so the
    // split-child rows do not block the parent rows (mirrors RunSplitCompletionRollupIT).
    jdbcTemplate.update("update workflow_runs set parent_run_id = null");
    jdbcTemplate.update("delete from run_dependencies");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private long viaSplitRollupEventCount(String runId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e "
                + "join workflow_runs r on r.id = e.workflow_run_id "
                + "where r.public_id = ? and e.details::text like '%viaSplitRollup%'",
            Long.class, runId);
    return count == null ? 0 : count;
  }

  private String newRun(WorkflowState state, String parentRunId) {
    String id = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(id, state, null, parentRunId);
    return id;
  }

  @Test
  void sweepRollsUpAStrandedSplitParent() {
    // Parent stuck in Split with BOTH children already Completed — the transient-hook-failure
    // strand.
    String parent = newRun(WorkflowState.SPLIT, null);
    newRun(WorkflowState.COMPLETED, parent);
    newRun(WorkflowState.COMPLETED, parent);

    SplitRollupReconciliationSweepService.SweepResult result = sweep.sweep();

    assertThat(result.found()).isEqualTo(1);
    assertThat(result.recovered()).isEqualTo(1);
    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(viaSplitRollupEventCount(parent)).isEqualTo(1);
  }

  @Test
  void sweepIsANoOpWhenNotAllChildrenCompleted() {
    String parent = newRun(WorkflowState.SPLIT, null);
    newRun(WorkflowState.COMPLETED, parent);
    newRun(WorkflowState.WAITING_FOR_REVIEW, parent);

    SplitRollupReconciliationSweepService.SweepResult result = sweep.sweep();

    assertThat(result.found()).isZero();
    assertThat(currentState(parent)).isEqualTo("Split");
    assertThat(viaSplitRollupEventCount(parent)).isZero();
  }

  @Test
  void sweepIgnoresNonSplitRuns() {
    // A non-Split run (even one with completed children) is never a sweep target.
    String notSplit = newRun(WorkflowState.WAITING_FOR_REVIEW, null);
    newRun(WorkflowState.COMPLETED, notSplit);

    SplitRollupReconciliationSweepService.SweepResult result = sweep.sweep();

    assertThat(result.found()).isZero();
    assertThat(currentState(notSplit)).isEqualTo("WaitingForReview");
  }

  @Test
  void sweepIgnoresAnArchivedSplitParent() {
    // Code-review 2026-06-30: an archived Split parent (all children Completed) must NOT be a sweep
    // target — WorkflowTransitionService rejects a transition on an archived run, so discovering it
    // would throw inside the swallowed rollup every tick, never flip, and WARN-spam forever while
    // head-of-line blocking recoverable strands. The discovery query excludes archived_at != null.
    String parent = newRun(WorkflowState.SPLIT, null);
    newRun(WorkflowState.COMPLETED, parent);
    newRun(WorkflowState.COMPLETED, parent);
    jdbcTemplate.update("update workflow_runs set archived_at = now() where public_id = ?", parent);

    SplitRollupReconciliationSweepService.SweepResult result = sweep.sweep();

    assertThat(result.found()).isZero();
    assertThat(currentState(parent)).isEqualTo("Split");
    assertThat(viaSplitRollupEventCount(parent)).isZero();
  }

  @Test
  void sweepRecoversAStrandedGrandparentViaTheInheritedHookChain() {
    // Grandparent -> parent (both Split) -> leaf (Completed). Only the parent is "all children
    // Completed", so the sweep pokes the parent; its own Split -> Completed transition re-fires the
    // 3f-7 rollup hook for the grandparent (AC3 — no special-casing in the sweep).
    String grandparent = newRun(WorkflowState.SPLIT, null);
    String parent = newRun(WorkflowState.SPLIT, grandparent);
    newRun(WorkflowState.COMPLETED, parent);

    SplitRollupReconciliationSweepService.SweepResult result = sweep.sweep();

    assertThat(result.found()).isEqualTo(1);
    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(currentState(grandparent)).isEqualTo("Completed");
  }

  @Test
  void repeatedSweepAndLateHookNeverDoubleTransitionOrDoubleEmit() {
    // Idempotency / concurrency proxy (AC2): the advisory lock + deterministic split-rollup:<id>
    // key
    // + the state machine rejecting a second -> Completed mean repeated sweeps and a late
    // child-driven
    // hook for the same parent produce exactly ONE Split -> Completed (one viaSplitRollup event).
    String parent = newRun(WorkflowState.SPLIT, null);
    String childA = newRun(WorkflowState.COMPLETED, parent);
    newRun(WorkflowState.COMPLETED, parent);

    sweep.sweep();
    assertThat(currentState(parent)).isEqualTo("Completed");

    // A second tick finds nothing (parent no longer Split); a late hook for an already-rolled-up
    // parent is a no-op.
    SplitRollupReconciliationSweepService.SweepResult second = sweep.sweep();
    assertThat(second.found()).isZero();
    rollupService.rollupParentOf(childA, "corr-late-hook");

    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(viaSplitRollupEventCount(parent)).isEqualTo(1);
  }
}
