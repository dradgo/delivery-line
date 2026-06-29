package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
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

/**
 * Story 3f-5 — end-to-end coverage of {@link SplitCommitService#commit} over the real wiring
 * (Testcontainers Postgres). NOT {@code @Transactional}: the service is best-effort + non-
 * transactional (R1, each subtask owns its physical transaction), so each test commits real rows
 * and {@code @AfterEach} cleans them up.
 *
 * <p>The parent carries no source ticket link, so every subtask takes the internal-only path —
 * isolating the orchestration assertions (children + lineage + dependency direction + gated
 * dispatch + parent decomposition + the zero-child guard + idempotent replay) from any connector
 * creation capability. The {@code internal_subtask} linkage itself + the V30 uniqueness re-typing
 * are pinned by {@code IntegrationLinkSplitChildIT}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class SplitCommitServiceIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private SplitCommitService splitCommitService;
  @Autowired private SplitProposalWritePort splitProposalWritePort;
  @Autowired private SplitProposalReadPort splitProposalReadPort;

  // Two-subtask proposal with a single edge: subtask 2 depends on subtask 1 (R4 direction).
  private static final String TWO_SUBTASK_JSON =
      "{\"subtasks\":[{\"ordinal\":1,\"title\":\"Part one\",\"scope\":\"Do first\"},"
          + "{\"ordinal\":2,\"title\":\"Part two\",\"scope\":\"Do second\"}],"
          + "\"dependencies\":[{\"fromOrdinal\":2,\"toOrdinal\":1}]}";

  private static final String ZERO_SUBTASK_JSON = "{\"subtasks\":[],\"dependencies\":[]}";

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from run_dependencies");
    jdbcTemplate.update("delete from split_proposals");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  private String currentState(String runId) {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, runId);
  }

  private List<String> childRunIdsOf(String parentRunId) {
    return jdbcTemplate.queryForList(
        "select public_id from workflow_runs where parent_run_id = ? order by created_at, id",
        String.class,
        parentRunId);
  }

  private String seedGateRunWithProposal(String proposalJson) {
    String run = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(run, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null);
    splitProposalWritePort.insertOpen(
        new NewSplitProposal(
            PublicIdPrefixes.SPLIT_PROPOSAL.next(),
            run,
            "art_reviewed1234",
            1,
            0,
            proposalJson,
            "claude:latest",
            "codex:latest"));
    return run;
  }

  private ApproveSplitCommand command(String run, String key) {
    return new ApproveSplitCommand(run, "alex", ActorType.HUMAN, key, "corr-3f5");
  }

  @Test
  void commitFansOutChildrenWiresDependencyDirectionAndDecomposesParent() {
    String parent = seedGateRunWithProposal(TWO_SUBTASK_JSON);

    SplitCommitResult result = splitCommitService.commit(command(parent, "idem-3f5-commit-001"));

    // Two children minted with lineage to the parent.
    assertThat(result.parentDecomposed()).isTrue();
    assertThat(result.outcome()).isEqualTo(SplitCommitResult.OUTCOME_DECOMPOSED);
    List<String> children = childRunIdsOf(parent);
    assertThat(children).hasSize(2);
    String child1 = children.get(0);
    String child2 = children.get(1);

    // R4 direction — the run_dependencies row is (child2 depends on child1), NOT the reverse.
    Integer edge =
        jdbcTemplate.queryForObject(
            "select count(*) from run_dependencies where run_id = ? and depends_on_run_id = ?",
            Integer.class,
            child2,
            child1);
    assertThat(edge).isEqualTo(1);

    // Gated dispatch: the dependent (child2) PARKS in WaitingForDependencies; the independent
    // (child1) is NOT parked. (Its spec dispatch is a no-op under the test profile's
    // spec-stage.auto-dispatch=false, so it stays Inbox rather than advancing to Investigating —
    // the dispatch DECISION itself is unit-tested via the DISPATCHED gated-dispatch outcome.)
    assertThat(currentState(child2)).isEqualTo("WaitingForDependencies");
    assertThat(currentState(child1)).isEqualTo("Inbox");

    // Parent decomposed to the non-terminal Split state + a workflow.split event carrying children.
    assertThat(currentState(parent)).isEqualTo("Split");
    Integer splitEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e join workflow_runs r on e.workflow_run_id = r.id"
                + " where r.public_id = ? and e.event_type = 'workflow.split'",
            Integer.class,
            parent);
    assertThat(splitEvents).isEqualTo(1);
  }

  @Test
  void zeroChildCommitAbortsLeavingParentAndProposalUntouched() {
    String parent = seedGateRunWithProposal(ZERO_SUBTASK_JSON);

    SplitCommitResult result = splitCommitService.commit(command(parent, "idem-3f5-abort-001"));

    assertThat(result.parentDecomposed()).isFalse();
    assertThat(result.outcome()).isEqualTo(SplitCommitResult.OUTCOME_ABORTED_NO_CHILDREN);
    assertThat(childRunIdsOf(parent)).isEmpty();
    // Parent stays at its gate; the proposal stays open; no workflow.split event.
    assertThat(currentState(parent)).isEqualTo("WaitingForSpecApproval");
    assertThat(splitProposalReadPort.hasOpenForRun(parent)).isTrue();
    Integer splitEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e join workflow_runs r on e.workflow_run_id = r.id"
                + " where r.public_id = ? and e.event_type = 'workflow.split'",
            Integer.class,
            parent);
    assertThat(splitEvents).isZero();
  }

  @Test
  void replayWithSameIdempotencyKeyDoesNotDoubleCreateChildrenOrDecompose() {
    String parent = seedGateRunWithProposal(TWO_SUBTASK_JSON);
    String key = "idem-3f5-replay-001";

    splitCommitService.commit(command(parent, key));
    List<String> afterFirst = childRunIdsOf(parent);
    assertThat(afterFirst).hasSize(2);

    // Replay under the SAME idempotency key — no second children, no second decomposition.
    SplitCommitResult replay = splitCommitService.commit(command(parent, key));
    assertThat(childRunIdsOf(parent)).containsExactlyElementsOf(afterFirst);
    assertThat(replay.childRunIds()).hasSize(2);
    Integer splitEvents =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e join workflow_runs r on e.workflow_run_id = r.id"
                + " where r.public_id = ? and e.event_type = 'workflow.split'",
            Integer.class,
            parent);
    assertThat(splitEvents).isEqualTo(1);
  }
}
