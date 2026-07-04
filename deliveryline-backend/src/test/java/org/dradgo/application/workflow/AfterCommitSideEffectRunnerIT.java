package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3h-0 (AC2) — real-Postgres proof-points for {@link AfterCommitSideEffectRunner} exercising
 * genuine transaction propagation / advisory-lock / replay behavior (not mocks). NOT
 * {@code @Transactional}: {@code afterCommit} + {@code REQUIRES_NEW} cannot be exercised inside a
 * rolled-back test transaction (a {@code @Transactional} test rolls the afterCommit hook back and
 * makes {@code REQUIRES_NEW} isolation a no-op), so each test commits real rows and the
 * {@code @AfterEach} cleans them up (shared Postgres, mirrors {@code RunSplitCompletionRollupIT} /
 * {@code RunnerExecutionTokenUsagePersistenceIT}). Named {@code *IT} so Failsafe runs it.
 *
 * <p>Three proof-points:
 *
 * <ol>
 *   <li><strong>Replay-idempotence</strong> — via the retrofitted 3f-7 consumer: a double rollup
 *       fire produces the {@code Split -> Completed} effect exactly ONCE (the caller's
 *       deterministic {@code split-rollup:<parentId>} key + the non-Split in-tx re-check; the
 *       helper does not weaken this).
 *   <li><strong>Clobber-avoidance foundation</strong> — an out-of-band {@code REQUIRES_NEW} column
 *       write run through {@link AfterCommitSideEffectRunner#runInNewTransaction} COMMITS
 *       independently and SURVIVES the ambient/outer transaction rolling back. This is the
 *       machinery property the no-clobber save rule builds on; the full stale-entity full-row-save
 *       shape is the production exemplar {@code
 *       RunnerExecutionTokenUsagePersistenceIT#terminalTransitionDoesNotClobberTokenUsage} (cited
 *       in ADR 0032) — kept there so 3h-0 does not re-touch 3g-5's {@code @DynamicUpdate} on {@code
 *       RunnerExecutionEntity}.
 *   <li><strong>Best-effort swallow</strong> — a {@link RuntimeException} thrown inside a real
 *       post-commit side effect is swallowed (WARN) and the already-committed outer transition
 *       survives; nothing propagates to the committer.
 * </ol>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class AfterCommitSideEffectRunnerIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private WorkflowTransitionService transitionService;
  @Autowired private RunSplitCompletionRollupService rollupService;
  @Autowired private AfterCommitSideEffectRunner sideEffectRunner;
  @Autowired private RunnerExecutionRecordPort runnerExecutionRecordPort;
  @Autowired private PlatformTransactionManager transactionManager;

  private static final TransitionActor SYSTEM = new TransitionActor("system", ActorType.SYSTEM);

  @AfterEach
  void cleanDatabase() {
    // Null the self-referencing parent_run_id (ON DELETE RESTRICT) before the bulk delete so the
    // split-child rows do not block the parent rows.
    jdbcTemplate.update("update workflow_runs set parent_run_id = null");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from run_dependencies");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  // ---- Proof-point 1: replay-idempotence (via the retrofitted 3f-7 consumer) --------------------

  @Test
  void doubleRollupFireRollsTheSplitParentUpExactlyOnce() {
    String parent = newRun(WorkflowState.SPLIT, null);
    String childA = newRun(WorkflowState.WAITING_FOR_REVIEW, parent);
    String childB = newRun(WorkflowState.WAITING_FOR_REVIEW, parent);

    // Completing both children rolls the parent up once through the retrofitted afterCommit hook.
    complete(childA);
    complete(childB);
    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(viaSplitRollupEventCount(parent)).isEqualTo(1);

    // Replay: fire the child-driven rollup again (double afterCommit fire) for BOTH children. The
    // deterministic split-rollup:<parentId> key + the non-Split in-tx re-check make it a no-op — no
    // second Split -> Completed transition, no duplicate viaSplitRollup event.
    rollupService.rollupParentOf(childA, "replay-a");
    rollupService.rollupParentOf(childB, "replay-b");

    assertThat(currentState(parent)).isEqualTo("Completed");
    assertThat(viaSplitRollupEventCount(parent)).isEqualTo(1);
  }

  // ---- Proof-point 2: clobber-avoidance (stale ambient entity + terminal save)
  // ----------

  @Test
  void outOfBandRequiresNewWriteSurvivesTheSubsequentStaleEntityTerminalSave() {
    String rex = seedRunningExecution();
    TransactionTemplate ambient = new TransactionTemplate(transactionManager);

    ambient.executeWithoutResult(
        status -> {
          // Ambient load: caches a managed entity with NULL tokens in this transaction's
          // persistence
          // context, matching the stale-row shape that caused the 3g token clobber.
          runnerExecutionRecordPort.findByPublicId(rex).orElseThrow();
          // Layer B commits the metadata write out-of-band while the ambient entity is stale.
          sideEffectRunner.runInNewTransaction(
              "token-usage",
              rex,
              () ->
                  jdbcTemplate.update(
                      """
                      update runner_executions
                      set input_tokens = 1200, output_tokens = 800, total_tokens = 2000
                      where public_id = ?
                      """,
                      rex));
          // Terminal save from the ambient transaction must not clobber those committed columns.
          runnerExecutionRecordPort.markCompleted(rex, OffsetDateTime.now(ZoneOffset.UTC));
        });

    assertThat(
            jdbcTemplate.queryForObject(
                "select input_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isEqualTo(1200);
    assertThat(
            jdbcTemplate.queryForObject(
                "select output_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isEqualTo(800);
    assertThat(
            jdbcTemplate.queryForObject(
                "select total_tokens from runner_executions where public_id = ?",
                Integer.class,
                rex))
        .isEqualTo(2000);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from runner_executions where public_id = ?", String.class, rex))
        .isEqualTo("completed");
  }

  // ---- Proof-point 3: best-effort swallow (real post-commit side effect throws)
  // ------------------

  @Test
  void sideEffectThrowingPostCommitIsSwallowedAndTheCommittedTransitionSurvives() {
    // A real run in a non-terminal state; the committed transition below must survive a throwing
    // post-commit side effect.
    String run = newRun(WorkflowState.WAITING_FOR_REVIEW, null);
    TransactionTemplate ambient = new TransactionTemplate(transactionManager);

    // Commit a real state change and register a post-commit side effect that throws in the same
    // transaction. Firing the hook must NOT propagate out of commit, and the state change must
    // remain durable.
    assertThatCode(
            () ->
                ambient.executeWithoutResult(
                    status -> {
                      jdbcTemplate.update(
                          "update workflow_runs set current_state = 'Investigating' where public_id = ?",
                          run);
                      sideEffectRunner.runAfterCommit(
                          "probe",
                          run,
                          () -> {
                            throw new IllegalStateException("boom");
                          });
                    }))
        .doesNotThrowAnyException();

    assertThat(currentState(run)).isEqualTo("Investigating");
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private String newRun(WorkflowState state, String parentRunId) {
    String id = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(id, state, null, parentRunId);
    return id;
  }

  private void complete(String runId) {
    transitionService.transition(
        runId, WorkflowState.COMPLETED, SYSTEM, "review_approved", "done:" + runId);
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

  private String seedRunningExecution() {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Inbox')", runId);
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'investigation', 'running', 1,
                now(), now() + interval '10 minutes', 100, 0, now())
        """,
        rex,
        runId);
    return rex;
  }
}
