package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.artifact.ActorContext;
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
 * Story 4.7 (AC11) — real-Postgres coverage for {@link RecoveryService#rerunFromStep}: seed a
 * Failed run with an approved spec, rerun-to-investigating, and assert the governed outcomes land
 * durably — the Failed → Investigating transition, the {@code recovery.rerunFromStep} event, the
 * {@code recovery_actions} row (action_type=rerun, reviewer_role=workflow_owner), and — the
 * enforced supersession — the prior spec approval invalidated so {@code
 * findLatestApprovedForArtifactLineage} returns empty until re-approval.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("integration")
class RecoveryServiceRerunFromStepIT {

  private static final String RUN = "run_rerunit0001";
  private static final String FAIL_EVENT = "evt_rerunit_fail1";
  private static final String SPEC_ART = "art_rerunit_spec1";
  private static final String APPROVAL = "apr_rerunit_spec1";
  private static final String APPROVAL_SEED_KEY = "idem-rerunit-seed-approval";
  private static final String IDEMPOTENCY_KEY = "idem-rerunit-rerun-1234";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecoveryService recoveryService;
  @Autowired private ApprovalReadPort approvalReadPort;

  @AfterEach
  void cleanUp() {
    Long runId = runIdOrNull();
    if (runId != null) {
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from runner_executions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from approvals where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
    }
    jdbcTemplate.update("delete from idempotency_records where key like 'idem-rerunit-%'");
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
  }

  @Test
  void rerunToInvestigatingTransitionsAppendsEventInsertsActionAndInvalidatesSpecApproval() {
    Long runId = seedFailedRunWithApprovedSpec();

    RerunFromStepRecoveryResult result =
        recoveryService.rerunFromStep(
            RUN,
            "investigating",
            IDEMPOTENCY_KEY,
            new ActorContext("alex", ActorType.HUMAN, "corr-rerun-it"),
            "spec missed scope; re-spec required");

    assertThat(result.replayed()).isFalse();
    assertThat(result.resultingState()).isEqualTo(WorkflowState.INVESTIGATING);
    assertThat(result.supersededArtifactIds()).contains(SPEC_ART);
    assertThat(result.invalidatedApprovalIds()).contains(APPROVAL);

    // Transition landed.
    String runState =
        jdbcTemplate.queryForObject(
            "select current_state from workflow_runs where public_id = ?", String.class, RUN);
    assertThat(runState).isEqualTo(WorkflowState.INVESTIGATING.value());

    // recovery.rerunFromStep event appended with the target step detail.
    Integer rerunEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where workflow_run_id = ? "
                + "and event_type = 'recovery.rerunFromStep' "
                + "and details->>'targetStep' = 'investigating'",
            Integer.class,
            runId);
    assertThat(rerunEvents).isEqualTo(1);

    // recovery_actions row: action_type='rerun', reviewer_role='workflow_owner', succeeded.
    String actionType =
        jdbcTemplate.queryForObject(
            "select action_type from recovery_actions where idempotency_key = ?",
            String.class,
            IDEMPOTENCY_KEY);
    assertThat(actionType).isEqualTo("rerun");
    String reviewerRole =
        jdbcTemplate.queryForObject(
            "select reviewer_role from recovery_actions where idempotency_key = ?",
            String.class,
            IDEMPOTENCY_KEY);
    assertThat(reviewerRole).isEqualTo("workflow_owner");
    String resultStatus =
        jdbcTemplate.queryForObject(
            "select result_status from recovery_actions where idempotency_key = ?",
            String.class,
            IDEMPOTENCY_KEY);
    assertThat(resultStatus).isEqualTo("succeeded");

    // Enforced supersession: the prior spec approval is invalidated, so the current-approved read
    // returns empty until a new spec version is re-approved (getCurrentApprovedSpec → null).
    java.sql.Timestamp invalidatedAt =
        jdbcTemplate.queryForObject(
            "select invalidated_at from approvals where public_id = ?",
            java.sql.Timestamp.class,
            APPROVAL);
    assertThat(invalidatedAt).isNotNull();
    String invalidatedReason =
        jdbcTemplate.queryForObject(
            "select invalidated_reason from approvals where public_id = ?", String.class, APPROVAL);
    assertThat(invalidatedReason).isEqualTo("superseded_by_rerun_from_step");

    Optional<ApprovalSnapshot> currentApproved =
        approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "spec");
    assertThat(currentApproved).isEmpty();
  }

  @Test
  void rerunReplaysOnSameKeyWithoutDoubleInvalidatingOrDoubleTransitioning() {
    seedFailedRunWithApprovedSpec();
    ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-rerun-it");

    RerunFromStepRecoveryResult first =
        recoveryService.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor, "re-spec");
    RerunFromStepRecoveryResult replay =
        recoveryService.rerunFromStep(RUN, "investigating", IDEMPOTENCY_KEY, actor, "re-spec");

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.recoveryActionPublicId()).isEqualTo(first.recoveryActionPublicId());

    Integer actionRows =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where idempotency_key = ?",
            Integer.class,
            IDEMPOTENCY_KEY);
    assertThat(actionRows).isEqualTo(1);
    Integer rerunEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where workflow_run_id = "
                + "(select id from workflow_runs where public_id = ?) "
                + "and event_type = 'recovery.rerunFromStep'",
            Integer.class,
            RUN);
    assertThat(rerunEvents).isEqualTo(1);
  }

  private Long runIdOrNull() {
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private Long seedFailedRunWithApprovedSpec() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        RUN,
        WorkflowState.FAILED.value());
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);

    // Failure transition event (Executing → Failed) — the rerun's triggering event AND the spec
    // artifact's linked_event_id.
    jdbcTemplate.update(
        "insert into workflow_events "
            + "(public_id, workflow_run_id, event_type, prior_state, resulting_state, "
            + " actor_identity, actor_type, intervention_marker) "
            + "values (?, ?, 'workflow.stateChanged', 'Executing', 'Failed', 'system', 'system', false)",
        FAIL_EVENT,
        runId);
    Long eventId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id = ?", Long.class, FAIL_EVENT);

    // Approved spec artifact (version 1, available).
    jdbcTemplate.update(
        "insert into artifacts "
            + "(public_id, workflow_run_id, artifact_type, version, classification, status, "
            + " linked_event_id) "
            + "values (?, ?, 'spec', 1, 'shareable-redacted', 'available', ?)",
        SPEC_ART,
        runId,
        eventId);

    // Current spec approval (approved) — the row the rerun must invalidate.
    jdbcTemplate.update(
        "insert into approvals "
            + "(public_id, workflow_run_id, artifact_id, artifact_version, context_bundle_version, "
            + " actor_identity, actor_type, reviewer_role, decision, idempotency_key) "
            + "values (?, ?, (select id from artifacts where public_id = ?), 1, 1, "
            + " 'alex', 'human', 'product_reviewer', 'approved', ?)",
        APPROVAL,
        runId,
        SPEC_ART,
        APPROVAL_SEED_KEY);

    // Sanity: the seeded approval is the current approved spec before the rerun.
    assertThat(approvalReadPort.findLatestApprovedForArtifactLineage(RUN, "spec")).isPresent();
    return runId;
  }
}
