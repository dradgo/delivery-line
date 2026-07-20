package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.integration.conflict.ConflictFilter;
import org.dradgo.application.integration.conflict.ConflictSummary;
import org.dradgo.application.integration.conflict.IntegrationConflictService;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictWritePort;
import org.dradgo.application.integration.conflict.spi.NewIntegrationConflict;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Story 4.6 AC10 - real-Postgres reconcile coverage for the 4.17 conflict row resolution path. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("integration")
class IntegrationConflictReconcileIT {

  private static final String RUN = "run_reconcileit1";
  private static final String LINK = "ilk_reconcileit1";
  private static final String CONFLICT = "icf_reconcileit1";
  private static final String IDEMPOTENCY_KEY = "idem-reconcile-it-1234";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictWritePort conflictWritePort;
  @Autowired private IntegrationConflictService conflictService;
  @Autowired private RecoveryService recoveryService;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from integration_conflicts where public_id = ?", CONFLICT);
    jdbcTemplate.update("delete from integration_links where public_id = ?", LINK);
    jdbcTemplate.update("delete from recovery_actions where idempotency_key = ?", IDEMPOTENCY_KEY);
    jdbcTemplate.update("delete from idempotency_records where key = ?", IDEMPOTENCY_KEY);
    jdbcTemplate.update(
        "delete from workflow_events where workflow_run_id = "
            + "(select id from workflow_runs where public_id = ?)",
        RUN);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
  }

  @Test
  void reconcileResolvesSeededConflictAndPersistsRecoveryAction() {
    seedRunAndLink();
    boolean inserted =
        conflictWritePort.insertIfAbsent(
            new NewIntegrationConflict(
                CONFLICT,
                LINK,
                RUN,
                IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value(),
                "{\"currentState\":\"Executing\"}",
                "{\"externalState\":\"merged\"}",
                Instant.parse("2026-07-09T10:00:00Z")));
    assertThat(inserted).isTrue();
    assertThat(unresolvedForRun()).extracting(ConflictSummary::conflictId).contains(CONFLICT);

    ReconcileRecoveryResult result =
        recoveryService.reconcile(
            RUN,
            CONFLICT,
            "mark_completed_externally",
            IDEMPOTENCY_KEY,
            new ActorContext("alex", ActorType.HUMAN, "corr-reconcile-it"),
            "operator verified external completion");

    assertThat(result.resolvedConflictId()).isEqualTo(CONFLICT);
    assertThat(result.resultingState()).isEqualTo(WorkflowState.RECONCILED);
    assertThat(result.replayed()).isFalse();
    assertThat(unresolvedForRun()).extracting(ConflictSummary::conflictId).doesNotContain(CONFLICT);

    String recoveryActionId =
        jdbcTemplate.queryForObject(
            "select public_id from recovery_actions where idempotency_key = ?",
            String.class,
            IDEMPOTENCY_KEY);
    assertThat(recoveryActionId).isEqualTo(result.recoveryActionPublicId());
    assertThat(recoveryActionId).startsWith("rcv_");

    String resolvedBy =
        jdbcTemplate.queryForObject(
            "select resolved_by_action_id from integration_conflicts where public_id = ?",
            String.class,
            CONFLICT);
    assertThat(resolvedBy).isEqualTo(recoveryActionId);

    String runState =
        jdbcTemplate.queryForObject(
            "select current_state from workflow_runs where public_id = ?", String.class, RUN);
    assertThat(runState).isEqualTo(WorkflowState.RECONCILED.value());
  }

  private List<ConflictSummary> unresolvedForRun() {
    return conflictService.listUnresolvedConflicts(ConflictFilter.forRun(RUN));
  }

  private void seedRunAndLink() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        RUN,
        WorkflowState.EXECUTING.value());
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, 'github_pr', 'octo/repo#46', cast(? as jsonb), 'synced')",
        LINK,
        runId,
        "{\"prState\":\"open\"}");
  }
}
