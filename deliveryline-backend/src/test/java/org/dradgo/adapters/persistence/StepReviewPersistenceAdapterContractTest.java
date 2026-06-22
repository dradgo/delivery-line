package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.review.StepReviewSnapshot;
import org.dradgo.application.review.spi.StepReviewReadPort;
import org.dradgo.application.review.spi.StepReviewWritePort;
import org.dradgo.application.review.spi.StepReviewWritePort.NewStepReview;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ReviewOutcome;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3d-2 (AC2/AC4, Task 10) — real-Postgres regression for the {@code step_reviews} write/read
 * seam. Mirrors {@code ApprovalWritePersistenceAdapterContractTest}. Cases:
 *
 * <ul>
 *   <li>happy insert → read back through {@link StepReviewReadPort#findLatestForRun} (composite
 *       artifact FK pinned, {@code outcome} registry parse on read, identities + self-review
 *       surfaced) — and the read runs on a non-{@code @Transactional} path WITHOUT a {@code
 *       LazyInitializationException} (the {@code join fetch} covers the LAZY FKs);
 *   <li>a second verdict for the SAME reviewer execution → the V21 partial-unique index fires,
 *       surfaced as the benign {@code DuplicateStepReviewException} idempotent signal (no raw
 *       DataIntegrityViolation leaks, no false INTERNAL_ERROR);
 *   <li>missing run / runner-execution / artifact FK targets → typed not-found codes.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class StepReviewPersistenceAdapterContractTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StepReviewWritePort stepReviewWritePort;
  @Autowired private StepReviewReadPort stepReviewReadPort;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from step_reviews");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void happyInsertIsReadableAndPinsProvenance() {
    seedRun("run_rev_happy01");
    seedArtifact("run_rev_happy01", "art_rev_happy01", ArtifactType.PR_OUTPUT, 2);
    seedRunnerExecution("run_rev_happy01", "rex_rev_happy01");

    StepReviewSnapshot persisted =
        stepReviewWritePort.insert(
            new NewStepReview(
                "rev_happy0001",
                "run_rev_happy01",
                "rex_rev_happy01",
                "art_rev_happy01",
                2,
                ReviewOutcome.CONCERN,
                "[redacted rationale]",
                "claude:it-3d2",
                "claude:it-3d2"));

    assertThat(persisted.outcome()).isEqualTo(ReviewOutcome.CONCERN);
    assertThat(persisted.selfReview()).isTrue(); // equal identities

    // Read back on a NON-@Transactional path — the join-fetch read port must not LazyInit.
    Optional<StepReviewSnapshot> readBack = stepReviewReadPort.findLatestForRun("run_rev_happy01");
    assertThat(readBack).isPresent();
    StepReviewSnapshot v = readBack.get();
    assertThat(v.publicId()).isEqualTo("rev_happy0001");
    assertThat(v.runnerExecutionId()).isEqualTo("rex_rev_happy01");
    assertThat(v.reviewedArtifactId()).isEqualTo("art_rev_happy01");
    assertThat(v.reviewedArtifactVersion()).isEqualTo(2);
    assertThat(v.outcome()).isEqualTo(ReviewOutcome.CONCERN); // registry parse on read
    assertThat(v.rationale()).isEqualTo("[redacted rationale]");
    assertThat(v.selfReview()).isTrue();
  }

  @Test
  void duplicateVerdictPerReviewerExecutionIsBenignIdempotentSignal() {
    // Code-review hardening: a duplicate verdict for the SAME reviewer execution (V21
    // partial-unique
    // index fires) is an anticipated idempotent re-delivery, NOT an INTERNAL_ERROR 500. The adapter
    // surfaces the benign DuplicateStepReviewException so the harvest treats it as a no-op rather
    // than falsely degrading the run as a contract violation.
    seedRun("run_rev_dup01");
    seedArtifact("run_rev_dup01", "art_rev_dup01", ArtifactType.IMPLEMENTATION_PLAN, 1);
    seedRunnerExecution("run_rev_dup01", "rex_rev_dup01");

    stepReviewWritePort.insert(
        new NewStepReview(
            "rev_dup00001",
            "run_rev_dup01",
            "rex_rev_dup01",
            "art_rev_dup01",
            1,
            ReviewOutcome.PASS,
            null,
            "codex:it",
            "codex:it"));

    assertThatThrownBy(
            () ->
                stepReviewWritePort.insert(
                    new NewStepReview(
                        "rev_dup00002",
                        "run_rev_dup01",
                        "rex_rev_dup01",
                        "art_rev_dup01",
                        1,
                        ReviewOutcome.FAIL,
                        null,
                        "codex:it",
                        "codex:it")))
        .isInstanceOf(org.dradgo.application.review.spi.DuplicateStepReviewException.class)
        .satisfies(
            e ->
                assertThat(
                        ((org.dradgo.application.review.spi.DuplicateStepReviewException) e)
                            .runnerExecutionPublicId())
                    .isEqualTo("rex_rev_dup01"));
  }

  @Test
  void missingRunnerExecutionFkRaisesTypedNotFound() {
    seedRun("run_rev_missrex");
    seedArtifact("run_rev_missrex", "art_rev_missrex", ArtifactType.PR_OUTPUT, 1);
    // No runner_execution insert.

    assertThatThrownBy(
            () ->
                stepReviewWritePort.insert(
                    new NewStepReview(
                        "rev_miss00001",
                        "run_rev_missrex",
                        "rex_doesnotexist",
                        "art_rev_missrex",
                        1,
                        ReviewOutcome.PASS,
                        null,
                        "claude:it",
                        "codex:it")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.RUNNER_EXECUTION_NOT_FOUND));
  }

  // ---------------------------------------------------------------------------
  // JDBC seed helpers.
  // ---------------------------------------------------------------------------

  private void seedRun(String publicId) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        WorkflowState.WAITING_FOR_REVIEW.value());
  }

  private void seedArtifact(
      String workflowRunPublicId, String artifactPublicId, ArtifactType type, int version) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    String evtPublicId = "evt_revseed_" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, linked_event_id) values (?, ?, ?, ?, null, ?, 'available', ?)",
        artifactPublicId,
        runId,
        type.value(),
        version,
        type.defaultClassification().value(),
        linkedEventId);
  }

  private void seedRunnerExecution(String workflowRunPublicId, String publicId) {
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, queue_priority, queue_attempt_count, created_at)
        values (?, (select id from workflow_runs where public_id = ?),
                'review', 'running', 1, now(), now() + interval '10 minutes', 100, 0, now())
        """,
        publicId,
        workflowRunPublicId);
  }
}
