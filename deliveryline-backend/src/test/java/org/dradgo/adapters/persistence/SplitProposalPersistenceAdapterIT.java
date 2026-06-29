package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.NewSplitProposal;
import org.dradgo.application.workflow.SplitProposalView;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.SplitProposalWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunCreatePort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3f-4 — persistence behavior of the {@code split_proposals} adapter against real Postgres:
 * the proposal_json round-trip decode, the one-open-per-run partial unique index (supersede-then-
 * insert), dismiss, latest-vs-open reads, self-review derivation, and the loop counter.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
@Transactional
class SplitProposalPersistenceAdapterIT {

  @Autowired private SplitProposalReadPort readPort;
  @Autowired private SplitProposalWritePort writePort;
  @Autowired private WorkflowRunCreatePort createPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final String PROPOSAL_JSON =
      "{\"subtasks\":[{\"ordinal\":1,\"title\":\"Part one\",\"scope\":\"Do first\"},"
          + "{\"ordinal\":2,\"title\":\"Part two\",\"scope\":\"Do second\"}],"
          + "\"dependencies\":[{\"fromOrdinal\":2,\"toOrdinal\":1}]}";

  private String newRun() {
    String id = PublicIdPrefixes.WORKFLOW_RUN.next();
    createPort.create(id, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null);
    return id;
  }

  private NewSplitProposal proposal(String run, String reviewer, String producer) {
    return new NewSplitProposal(
        PublicIdPrefixes.SPLIT_PROPOSAL.next(),
        run,
        "art_reviewed1234",
        1,
        0,
        PROPOSAL_JSON,
        reviewer,
        producer);
  }

  @Test
  void insertOpenRoundTripsAndDecodesProposalJson() {
    String run = newRun();
    writePort.insertOpen(proposal(run, "claude:latest", "codex:latest"));

    assertThat(readPort.hasOpenForRun(run)).isTrue();
    Optional<SplitProposalView> open = readPort.findOpenForRun(run);
    assertThat(open).isPresent();
    SplitProposalView v = open.get();
    assertThat(v.status()).isEqualTo(SplitProposalView.STATUS_OPEN);
    assertThat(v.subtasks()).hasSize(2);
    assertThat(v.subtasks().get(0).ordinal()).isEqualTo(1);
    assertThat(v.subtasks().get(0).title()).isEqualTo("Part one");
    assertThat(v.dependencies()).hasSize(1);
    assertThat(v.dependencies().get(0).fromOrdinal()).isEqualTo(2);
    assertThat(v.dependencies().get(0).toOrdinal()).isEqualTo(1);
    // Different reviewer vs producer → not a self-review.
    assertThat(v.selfReview()).isFalse();
  }

  @Test
  void selfReviewIsDerivedFromMatchingReviewerAndProducerIdentity() {
    String run = newRun();
    writePort.insertOpen(proposal(run, "claude:latest", "claude:latest"));
    assertThat(readPort.findOpenForRun(run))
        .get()
        .extracting(SplitProposalView::selfReview)
        .isEqualTo(true);
  }

  @Test
  void oneOpenPerRunPartialIndexRejectsASecondOpenWithoutSupersede() {
    String run = newRun();
    writePort.insertOpen(proposal(run, "claude:latest", "claude:latest"));
    assertThatThrownBy(() -> writePort.insertOpen(proposal(run, "claude:latest", "claude:latest")))
        .hasMessageContaining("uq_split_proposals_open_per_run");
  }

  @Test
  void supersedeThenInsertKeepsExactlyOneOpenAndLatestReturnsTheOpenOne() {
    String run = newRun();
    writePort.insertOpen(proposal(run, "claude:latest", "claude:latest"));
    // supersede-then-insert (the harvester/repropose flow): the prior open frees the slot.
    int superseded = writePort.supersedeOpenForRun(run);
    assertThat(superseded).isEqualTo(1);
    assertThat(readPort.hasOpenForRun(run)).isFalse();
    NewSplitProposal second = proposal(run, "claude:latest", "claude:latest");
    writePort.insertOpen(second);

    assertThat(readPort.hasOpenForRun(run)).isTrue();
    assertThat(readPort.findOpenForRun(run))
        .get()
        .extracting(SplitProposalView::publicId)
        .isEqualTo(second.publicId());
    // findLatest also returns the open one (open ranks before superseded).
    assertThat(readPort.findLatestForRun(run))
        .get()
        .extracting(SplitProposalView::publicId)
        .isEqualTo(second.publicId());
  }

  @Test
  void dismissOpenForRunFreesTheOpenSlotAndLatestStillReadsTheDismissedRow() {
    String run = newRun();
    NewSplitProposal only = proposal(run, "claude:latest", "claude:latest");
    writePort.insertOpen(only);
    int dismissed = writePort.dismissOpenForRun(run);
    assertThat(dismissed).isEqualTo(1);
    assertThat(readPort.hasOpenForRun(run)).isFalse();
    assertThat(readPort.findOpenForRun(run)).isEmpty();
    assertThat(readPort.findLatestForRun(run))
        .get()
        .extracting(SplitProposalView::status)
        .isEqualTo(SplitProposalView.STATUS_DISMISSED);
  }

  @Test
  void approveOpenForRunFlipsOpenToApprovedAndIsReplaySafe() {
    // Story 3f-5 (R6) — the CAS guard. First call flips the one open row (returns 1); a replay
    // finds no open row (returns 0) so the parent decomposition short-circuits.
    String run = newRun();
    NewSplitProposal only = proposal(run, "claude:latest", "claude:latest");
    writePort.insertOpen(only);

    int flipped = writePort.approveOpenForRun(run);
    assertThat(flipped).isEqualTo(1);
    assertThat(readPort.hasOpenForRun(run)).isFalse();
    assertThat(readPort.findLatestForRun(run))
        .get()
        .extracting(SplitProposalView::status)
        .isEqualTo(SplitProposalView.STATUS_APPROVED);

    // Replay: the row is already approved, so no open row matches → 0 rows updated.
    assertThat(writePort.approveOpenForRun(run)).isZero();
  }

  @Test
  void currentSplitProposalLoopCountReadsTheRunColumn() {
    String run = newRun();
    assertThat(readPort.currentSplitProposalLoopCount(run)).isZero();
    jdbcTemplate.update(
        "update workflow_runs set split_proposal_loop_count = 4 where public_id = ?", run);
    assertThat(readPort.currentSplitProposalLoopCount(run)).isEqualTo(4);
  }
}
