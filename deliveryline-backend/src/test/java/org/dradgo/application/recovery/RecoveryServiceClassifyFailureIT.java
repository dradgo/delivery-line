package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureClassificationView;
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

/**
 * Story 4.9 (AC12) — real-Postgres coverage for {@link RecoveryService#classifyFailure}: seed a
 * Failed run, classify, re-classify, and assert the governed outcomes land durably — the
 * workflow_runs classification triple (behind the V44 CHECKs), the {@code
 * recovery.failureClassified} event chain (prior value preserved per FR47), the {@code
 * recovery_actions} row proving the widened {@code ck_recovery_actions_action_type} CHECK accepts
 * {@code 'classify_failure'}, the NO-transition invariant (the run stays Failed), and the AC9
 * inspection read. Probe rows are deleted children-first in {@code @AfterEach}
 * ([[flywayschema-restrict-fk-probe-rows-leak]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("integration")
class RecoveryServiceClassifyFailureIT {

  private static final String RUN = "run_classifyit01";
  private static final String FAIL_EVENT = "evt_classifyit_f1";
  private static final String FIRST_KEY = "idem-classifyit-first-1234";
  private static final String SECOND_KEY = "idem-classifyit-second-1234";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecoveryService recoveryService;
  @Autowired private WorkflowInspectionService workflowInspectionService;

  @AfterEach
  void cleanUp() {
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);
    if (!ids.isEmpty()) {
      Long runId = ids.get(0);
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
    }
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
  }

  @Test
  void classifyThenReclassifySetsColumnsAppendsEventChainAndInsertsSucceededRows() {
    Long runId = seedFailedRun();
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-classify-it");

    // --- first classification ---
    ClassifyFailureResult first =
        recoveryService.classifyFailure(
            RUN, "specification_gap", FIRST_KEY, actor, "spec missed the edge case");

    assertThat(first.replayed()).isFalse();
    assertThat(first.taxonomyValue()).isEqualTo("specification_gap");
    assertThat(first.priorTaxonomyValue()).isNull();

    // Column triple set (satisfying both V44 CHECKs — value in the registry set, all-or-nothing).
    assertThat(
            jdbcTemplate.queryForObject(
                "select failure_classification from workflow_runs where public_id = ?",
                String.class,
                RUN))
        .isEqualTo("specification_gap");
    assertThat(
            jdbcTemplate.queryForObject(
                "select failure_classified_by from workflow_runs where public_id = ?",
                String.class,
                RUN))
        .isEqualTo("alex");
    assertThat(
            jdbcTemplate.queryForObject(
                "select failure_classified_at from workflow_runs where public_id = ?",
                java.sql.Timestamp.class,
                RUN))
        .isNotNull();

    // NO transition — classify is a pure metadata operation (AC10).
    assertThat(
            jdbcTemplate.queryForObject(
                "select current_state from workflow_runs where public_id = ?", String.class, RUN))
        .isEqualTo(WorkflowState.FAILED.value());

    // The widened action_type CHECK accepted the row; succeeded on INSERT (R16), no pending flip.
    assertThat(
            jdbcTemplate.queryForObject(
                "select action_type from recovery_actions where idempotency_key = ?",
                String.class,
                FIRST_KEY))
        .isEqualTo("classify_failure");
    assertThat(
            jdbcTemplate.queryForObject(
                "select result_status from recovery_actions where idempotency_key = ?",
                String.class,
                FIRST_KEY))
        .isEqualTo("succeeded");
    assertThat(
            jdbcTemplate.queryForObject(
                "select reviewer_role from recovery_actions where idempotency_key = ?",
                String.class,
                FIRST_KEY))
        .isEqualTo("workflow_owner");
    // The row's FKs: triggering = the seeded failure event; resulting = the classified event.
    assertThat(
            jdbcTemplate.queryForObject(
                "select e.public_id from recovery_actions ra "
                    + "join workflow_events e on e.id = ra.triggering_event_id "
                    + "where ra.idempotency_key = ?",
                String.class,
                FIRST_KEY))
        .isEqualTo(FAIL_EVENT);
    assertThat(
            jdbcTemplate.queryForObject(
                "select e.details->>'taxonomyValue' from recovery_actions ra "
                    + "join workflow_events e on e.id = ra.resulting_event_id "
                    + "where ra.idempotency_key = ?",
                String.class,
                FIRST_KEY))
        .isEqualTo("specification_gap");

    // --- re-classification (AC9): column overwritten, NEW event appended, prior preserved ---
    ClassifyFailureResult second =
        recoveryService.classifyFailure(RUN, "context_gap", SECOND_KEY, actor, null);

    assertThat(second.replayed()).isFalse();
    assertThat(second.taxonomyValue()).isEqualTo("context_gap");
    assertThat(second.priorTaxonomyValue()).isEqualTo("specification_gap");

    assertThat(
            jdbcTemplate.queryForObject(
                "select failure_classification from workflow_runs where public_id = ?",
                String.class,
                RUN))
        .isEqualTo("context_gap");

    List<String> chain =
        jdbcTemplate.queryForList(
            "select details->>'taxonomyValue' from workflow_events "
                + "where workflow_run_id = ? and event_type = 'recovery.failureClassified' "
                + "order by created_at, id",
            String.class,
            runId);
    assertThat(chain).containsExactly("specification_gap", "context_gap");
    assertThat(
            jdbcTemplate.queryForObject(
                "select details->>'priorTaxonomyValue' from workflow_events "
                    + "where public_id = ?",
                String.class,
                second.classifiedEventPublicId()))
        .isEqualTo("specification_gap");

    // Story 4.9 review: the re-classify's triggering_event_id must resolve to the ORIGINAL
    // Executing->Failed transition, not the first classify event. The recovery.failureClassified
    // event is stamped Failed->Failed, so findLatestFailureEvent must exclude it — otherwise this
    // FK would point at the prior classify event and corrupt audit lineage.
    assertThat(
            jdbcTemplate.queryForObject(
                "select e.public_id from recovery_actions ra "
                    + "join workflow_events e on e.id = ra.triggering_event_id "
                    + "where ra.idempotency_key = ?",
                String.class,
                SECOND_KEY))
        .isEqualTo(FAIL_EVENT);

    // AC9 inspection read: "classified as X (previously Y at Z)".
    FailureClassificationView view = workflowInspectionService.getFailureClassification(RUN);
    assertThat(view.currentTaxonomyValue()).isEqualTo("context_gap");
    assertThat(view.currentDisplayLabel()).isEqualTo("context_gap");
    assertThat(view.classifiedBy()).isEqualTo("alex");
    assertThat(view.priorClassifications()).hasSize(1);
    assertThat(view.priorClassifications().get(0).taxonomyValue()).isEqualTo("specification_gap");
  }

  @Test
  void classifyReplaysOnSameKeyWithoutSecondRowOrSecondEvent() {
    Long runId = seedFailedRun();
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-classify-it");

    ClassifyFailureResult firstCall =
        recoveryService.classifyFailure(RUN, "review_rejection", FIRST_KEY, actor, null);
    ClassifyFailureResult replay =
        recoveryService.classifyFailure(RUN, "review_rejection", FIRST_KEY, actor, null);

    assertThat(firstCall.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.recoveryActionPublicId()).isEqualTo(firstCall.recoveryActionPublicId());
    assertThat(replay.classifiedEventPublicId()).isEqualTo(firstCall.classifiedEventPublicId());

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from recovery_actions where idempotency_key = ?",
                Integer.class,
                FIRST_KEY))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from workflow_events where workflow_run_id = ? "
                    + "and event_type = 'recovery.failureClassified'",
                Integer.class,
                runId))
        .isEqualTo(1);
  }

  private Long seedFailedRun() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        RUN,
        WorkflowState.FAILED.value());
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);

    // Failure transition event (Executing → Failed) — resolved as the classify's triggering
    // event.
    jdbcTemplate.update(
        "insert into workflow_events "
            + "(public_id, workflow_run_id, event_type, prior_state, resulting_state, "
            + " actor_identity, actor_type, failure_category, intervention_marker) "
            + "values (?, ?, 'workflow.stateChanged', 'Executing', 'Failed', 'system', 'system', "
            + " 'runner_crash', false)",
        FAIL_EVENT,
        runId);
    return runId;
  }
}
