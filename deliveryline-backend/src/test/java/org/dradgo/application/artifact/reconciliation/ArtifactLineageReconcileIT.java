package org.dradgo.application.artifact.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.artifact.ArtifactOperationService;
import org.dradgo.application.artifact.ArtifactReconciliationService;
import org.dradgo.application.artifact.LineageReconciliationResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.16a (AC10) — real-Postgres coverage of the operator-driven LINEAGE-recovery path (Model
 * A: acts directly on an artifact id, no persisted conflict): reattach re-parents + preserves
 * history; terminate flips terminal + closes the pending op; fork inserts a {@code
 * lineage_recovery=true} head at {@code version=max+1} / {@code parent=null}; each writes a {@code
 * recovery_actions} row (widened {@code artifact_lineage_reconcile} CHECK — V47 — accepted) + an
 * {@code artifact.lineageReconciled} event; idempotent replay (no second row/event); the two
 * malformed-decision rejections; and the AC7 no-silent-fork signal ({@code hasActiveLineage} — the
 * input the fail-closed CREATE/UPDATE decision keys on). Named {@code *IT} so it runs under
 * Failsafe (Testcontainers), never the no-Docker Surefire tier.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class ArtifactLineageReconcileIT {

  private static final ActorContext ACTOR =
      new ActorContext("operator-lineage", ActorType.HUMAN, "corr-lineage-it");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactReconciliationService reconciliationService;
  @Autowired private ArtifactOperationService artifactOperationService;

  private final List<String> seededRuns = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String run : seededRuns) {
      Long runId =
          jdbcTemplate.queryForObject(
              "select id from workflow_runs where public_id = ?", Long.class, run);
      jdbcTemplate.update("delete from recovery_actions where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifact_operations where workflow_run_id = ?", runId);
      // approvals FK the run + artifacts with on-delete-restrict — delete them before either.
      jdbcTemplate.update("delete from approvals where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    seededRuns.clear();
  }

  @Test
  void reattachReparentsOrphanPreservesHistoryAndKeepsLineageActive() {
    Seed seed = seedRun("reattach");
    long eventId = seedEvent(seed);
    String parent = "art_lgpar" + seed.suffix;
    String orphan = "art_lgorph" + seed.suffix;
    long parentId = seedArtifact(seed, eventId, "available", parent, 1, false);
    seedArtifact(seed, eventId, "pending", orphan, 2, false);

    LineageReconciliationResult result =
        reconciliationService.reattachToExistingLineage(
            orphan,
            parent,
            "re-parent onto the surviving leaf",
            ACTOR,
            "idem-reattach-" + seed.suffix);

    assertThat(result.replayed()).isFalse();
    assertThat(result.lineageAction()).isEqualTo("reattach_to_existing_lineage");
    assertThat(result.lineageReferenceArtifactId()).isEqualTo(parent);
    // The orphan is now a proper child of the chosen parent; both rows survive (history preserved).
    assertThat(parentInternalIdOf(orphan)).isEqualTo(parentId);
    assertThat(statusOf(parent)).isEqualTo("available");
    assertThat(statusOf(orphan)).isEqualTo("pending");
    // recovery_actions row with the widened action_type (V47 CHECK accepted it) + one event.
    assertThat(actionTypeOf(result.recoveryActionId())).isEqualTo("artifact_lineage_reconcile");
    assertThat(resultStatusOf(result.recoveryActionId())).isEqualTo("succeeded");
    assertThat(lineageReconciledEventCount(seed.runPublicId)).isEqualTo(1);
    // AC7 — the re-parented leaf is non-FAILED, so the fail-closed decision UPDATES (grafts), never
    // silently forks.
    assertThat(artifactOperationService.hasActiveLineage(seed.runPublicId, ArtifactType.SPEC))
        .isTrue();
  }

  @Test
  void terminateFlipsTerminalClosesPendingOperationAndDeactivatesLineage() {
    Seed seed = seedRun("terminate");
    long eventId = seedEvent(seed);
    String artifact = "art_lgterm" + seed.suffix;
    long artifactId = seedArtifact(seed, eventId, "available", artifact, 1, false);
    String op = seedPendingOperation(seed, eventId, artifactId, "op_lgterm" + seed.suffix);

    // Before: a non-FAILED leaf means the lineage is active.
    assertThat(artifactOperationService.hasActiveLineage(seed.runPublicId, ArtifactType.SPEC))
        .isTrue();

    LineageReconciliationResult result =
        reconciliationService.terminateAmbiguousLineage(
            artifact, "abandoning ambiguous lineage", ACTOR, "idem-terminate-" + seed.suffix);

    assertThat(result.lineageAction()).isEqualTo("terminate_ambiguous_lineage");
    assertThat(result.lineageReferenceArtifactId()).isNull();
    assertThat(statusOf(artifact)).isEqualTo("failed");
    assertThat(operationStatusOf(op)).isEqualTo("failed_orphan");
    assertThat(actionTypeOf(result.recoveryActionId())).isEqualTo("artifact_lineage_reconcile");
    assertThat(lineageReconciledEventCount(seed.runPublicId)).isEqualTo(1);
    // AC7 — after terminate the only leaf is FAILED, so hasActiveLineage is false and a fresh
    // CREATE
    // (not a silent revive of the abandoned lineage) is the legitimate next step.
    assertThat(artifactOperationService.hasActiveLineage(seed.runPublicId, ArtifactType.SPEC))
        .isFalse();
  }

  @Test
  void forkInsertsLineageRecoveryHeadAtMaxVersionPlusOneWithNullParent() {
    Seed seed = seedRun("fork");
    long eventId = seedEvent(seed);
    String source = "art_lgsrc" + seed.suffix;
    seedArtifact(seed, eventId, "failed", source, 1, false);

    LineageReconciliationResult result =
        reconciliationService.createExplicitFork(
            source,
            "start a fresh lineage after the failed predecessor",
            ACTOR,
            "idem-fork-" + seed.suffix);

    assertThat(result.lineageAction()).isEqualTo("create_explicit_fork");
    String forkId = result.lineageReferenceArtifactId();
    assertThat(forkId).isNotNull();
    // The fork head: version = max(1)+1 = 2, parent NULL, lineage_recovery TRUE, status pending.
    assertThat(versionOf(forkId)).isEqualTo(2);
    assertThat(parentInternalIdOf(forkId)).isNull();
    assertThat(lineageRecoveryOf(forkId)).isTrue();
    assertThat(statusOf(forkId)).isEqualTo("pending");
    assertThat(actionTypeOf(result.recoveryActionId())).isEqualTo("artifact_lineage_reconcile");
    assertThat(lineageReconciledEventCount(seed.runPublicId)).isEqualTo(1);
    // AC7 — the lineage_recovery head is the non-FAILED active leaf, so follow-up ops graft onto it
    // (UPDATE), never silently fork again.
    assertThat(artifactOperationService.hasActiveLineage(seed.runPublicId, ArtifactType.SPEC))
        .isTrue();
  }

  @Test
  void secondReconcileUnderSameKeyReplaysWithoutASecondRowOrEvent() {
    Seed seed = seedRun("replay");
    long eventId = seedEvent(seed);
    String artifact = "art_lgrep" + seed.suffix;
    seedArtifact(seed, eventId, "available", artifact, 1, false);
    String key = "idem-lgreplay-" + seed.suffix;

    LineageReconciliationResult first =
        reconciliationService.terminateAmbiguousLineage(artifact, "first", ACTOR, key);
    LineageReconciliationResult second =
        reconciliationService.terminateAmbiguousLineage(artifact, "second", ACTOR, key);

    assertThat(first.replayed()).isFalse();
    assertThat(second.replayed()).isTrue();
    assertThat(second.recoveryActionId()).isEqualTo(first.recoveryActionId());
    assertThat(recoveryActionCount(seed.runPublicId)).isEqualTo(1);
    assertThat(lineageReconciledEventCount(seed.runPublicId)).isEqualTo(1);
  }

  @Test
  void reattachWithoutChosenParentIsRejected() {
    Seed seed = seedRun("noparent");
    long eventId = seedEvent(seed);
    String artifact = "art_lgnp" + seed.suffix;
    seedArtifact(seed, eventId, "pending", artifact, 1, false);

    assertThatThrownBy(
            () ->
                reconciliationService.reconcileLineage(
                    new org.dradgo.application.artifact.ReconcileLineageCommand(
                        artifact,
                        "reattach_to_existing_lineage",
                        null,
                        "x",
                        ACTOR,
                        "idem-noparent-" + seed.suffix)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(DomainErrorCode.MISSING_LINEAGE_RECOVERY_FIELD));
  }

  @Test
  void unknownLineageActionIsRejected() {
    Seed seed = seedRun("badaction");
    long eventId = seedEvent(seed);
    String artifact = "art_lgba" + seed.suffix;
    seedArtifact(seed, eventId, "pending", artifact, 1, false);

    assertThatThrownBy(
            () ->
                reconciliationService.reconcileLineage(
                    new org.dradgo.application.artifact.ReconcileLineageCommand(
                        artifact,
                        "no_such_action",
                        null,
                        "x",
                        ACTOR,
                        "idem-badaction-" + seed.suffix)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(DomainErrorCode.INVALID_LINEAGE_RECOVERY_ACTION));
  }

  @Test
  void reattachOntoNonLeafParentIsRejected() {
    // Story 4.16a [Review D1] — a chosen parent that already has an active child is not a leaf;
    // reattaching a second orphan onto it would create two active leaves. Rejected.
    Seed seed = seedRun("nonleaf");
    long eventId = seedEvent(seed);
    String parent = "art_lgnlp" + seed.suffix;
    String child = "art_lgnlc" + seed.suffix;
    String orphan = "art_lgnlo" + seed.suffix;
    long parentId = seedArtifact(seed, eventId, "available", parent, 1, false);
    long childId = seedArtifact(seed, eventId, "available", child, 2, false);
    setParent(child, parentId);
    seedArtifact(seed, eventId, "pending", orphan, 3, false);

    assertThatThrownBy(
            () ->
                reconciliationService.reattachToExistingLineage(
                    orphan, parent, "attach onto a non-leaf", ACTOR, "idem-nonleaf-" + seed.suffix))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION));
    // The child link is untouched (no partial re-parent occurred).
    assertThat(parentInternalIdOf(child)).isEqualTo(parentId);
    assertThat(childId).isPositive();
    // No reconcile artifact was written.
    assertThat(recoveryActionCount(seed.runPublicId)).isZero();
  }

  @Test
  void reattachWhenTargetAlreadyHasAParentIsRejected() {
    // Story 4.16a [Review D1] — reattach is an ORPHAN-repair action; a target that already carries
    // a parent must not be silently re-parented (NFR19 no-silent-overwrite).
    Seed seed = seedRun("parented");
    long eventId = seedEvent(seed);
    String existingParent = "art_lgpp0" + seed.suffix;
    String target = "art_lgtgt" + seed.suffix;
    String chosen = "art_lgch" + seed.suffix;
    long existingParentId = seedArtifact(seed, eventId, "available", existingParent, 1, false);
    seedArtifact(seed, eventId, "pending", target, 2, false);
    setParent(target, existingParentId);
    seedArtifact(seed, eventId, "available", chosen, 3, false);

    assertThatThrownBy(
            () ->
                reconciliationService.reattachToExistingLineage(
                    target,
                    chosen,
                    "re-parent an already-parented target",
                    ACTOR,
                    "idem-parented-" + seed.suffix))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION));
    // The existing parent link is preserved (no overwrite).
    assertThat(parentInternalIdOf(target)).isEqualTo(existingParentId);
    assertThat(recoveryActionCount(seed.runPublicId)).isZero();
  }

  @Test
  void reattachOntoArchivedParentIsRejected() {
    // Story 4.16a [Review D2] — the chosen parent must not be archived (soft-deleted).
    Seed seed = seedRun("archpar");
    long eventId = seedEvent(seed);
    String parent = "art_lgap" + seed.suffix;
    String orphan = "art_lgao" + seed.suffix;
    seedArtifact(seed, eventId, "available", parent, 1, false);
    archive(parent);
    seedArtifact(seed, eventId, "pending", orphan, 2, false);

    assertThatThrownBy(
            () ->
                reconciliationService.reattachToExistingLineage(
                    orphan,
                    parent,
                    "attach onto an archived parent",
                    ACTOR,
                    "idem-archpar-" + seed.suffix))
        .isInstanceOfSatisfying(
            DomainException.class,
            e ->
                assertThat(e.errorCode())
                    .isEqualTo(DomainErrorCode.ARTIFACT_INVALID_STATE_TRANSITION));
    assertThat(parentInternalIdOf(orphan)).isNull();
    assertThat(recoveryActionCount(seed.runPublicId)).isZero();
  }

  @Test
  void forkSucceedsOnATerminalRun() {
    // Story 4.16a [Review D4] — a fresh lineage-recovery fork legitimately runs on a
    // failed/terminal run (mirroring reattach/terminate); it serializes on the advisory lineage
    // lock, NOT the terminal-refusing run-row lock, so recovery is not blocked on exactly the runs
    // that need it.
    Seed seed = seedRunWithState("forkterm", "Completed");
    long eventId = seedEvent(seed);
    String source = "art_lgfts" + seed.suffix;
    seedArtifact(seed, eventId, "failed", source, 1, false);

    LineageReconciliationResult result =
        reconciliationService.createExplicitFork(
            source, "fork after a terminal run", ACTOR, "idem-forkterm-" + seed.suffix);

    String forkId = result.lineageReferenceArtifactId();
    assertThat(forkId).isNotNull();
    assertThat(versionOf(forkId)).isEqualTo(2);
    assertThat(parentInternalIdOf(forkId)).isNull();
    assertThat(lineageRecoveryOf(forkId)).isTrue();
    assertThat(statusOf(forkId)).isEqualTo("pending");
    assertThat(actionTypeOf(result.recoveryActionId())).isEqualTo("artifact_lineage_reconcile");
    assertThat(lineageReconciledEventCount(seed.runPublicId)).isEqualTo(1);
  }

  @Test
  void terminateInvalidatesOnlyTheTerminatedVersionsApprovalNotAHealthySibling() {
    // Story 4.16a [Review D3] — version-specific approval invalidation. Two approved versions of
    // the
    // same (run, spec) lineage; terminating the ambiguous v1 must invalidate ONLY v1's approval,
    // not
    // the healthy v2's. The old run+type-keyed path resolved the HIGHEST approved version (v2), so
    // this asserts exactly the fix.
    Seed seed = seedRun("apprver");
    long eventId = seedEvent(seed);
    String ambiguous = "art_lgav1" + seed.suffix;
    String healthy = "art_lgav2" + seed.suffix;
    long ambiguousId = seedArtifact(seed, eventId, "available", ambiguous, 1, false);
    long healthyId = seedArtifact(seed, eventId, "available", healthy, 2, false);
    String ambiguousApproval = "apr_lgav1" + seed.suffix;
    String healthyApproval = "apr_lgav2" + seed.suffix;
    seedApproval(seed, ambiguousApproval, ambiguousId, 1);
    seedApproval(seed, healthyApproval, healthyId, 2);

    reconciliationService.terminateAmbiguousLineage(
        ambiguous, "abandon the ambiguous version", ACTOR, "idem-apprver-" + seed.suffix);

    assertThat(invalidatedAtOf(ambiguousApproval)).isNotNull();
    assertThat(invalidatedAtOf(healthyApproval)).isNull();
  }

  // ---- seeding + assertion helpers --------------------------------------------------------------

  private record Seed(String runPublicId, long runId, String suffix) {}

  private Seed seedRun(String label) {
    return seedRunWithState(label, "Executing");
  }

  private Seed seedRunWithState(String label, String state) {
    String suffix = label + Integer.toHexString(System.identityHashCode(label)) + seededRuns.size();
    String run = "run_lgrit" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", run, state);
    seededRuns.add(run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    return new Seed(run, runId, suffix);
  }

  private void setParent(String childPublicId, long parentInternalId) {
    jdbcTemplate.update(
        "update artifacts set parent_artifact_id = ? where public_id = ?",
        parentInternalId,
        childPublicId);
  }

  private void archive(String artifactPublicId) {
    jdbcTemplate.update(
        "update artifacts set archived_at = now() where public_id = ?", artifactPublicId);
  }

  private void seedApproval(
      Seed seed, String approvalPublicId, long artifactInternalId, int version) {
    jdbcTemplate.update(
        "insert into approvals (public_id, workflow_run_id, artifact_id, artifact_version,"
            + " context_bundle_version, actor_identity, actor_type, reviewer_role, decision,"
            + " idempotency_key) values (?, ?, ?, ?, 1, 'alex', 'human', 'workflow_owner',"
            + " 'approved', ?)",
        approvalPublicId,
        seed.runId,
        artifactInternalId,
        version,
        "idem_" + approvalPublicId);
  }

  private java.time.OffsetDateTime invalidatedAtOf(String approvalPublicId) {
    return jdbcTemplate.queryForObject(
        "select invalidated_at from approvals where public_id = ?",
        java.time.OffsetDateTime.class,
        approvalPublicId);
  }

  private long seedEvent(Seed seed) {
    String evt = "evt_lgrit" + seed.suffix;
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
            + " actor_type) values (?, ?, 'artifact.draftCreated', 'system', 'system')",
        evt,
        seed.runId);
    return jdbcTemplate.queryForObject(
        "select id from workflow_events where public_id = ?", Long.class, evt);
  }

  private long seedArtifact(
      Seed seed,
      long eventId,
      String status,
      String artifactPublicId,
      int version,
      boolean lineageRecovery) {
    // FAILED requires a paired failure_category/failure_reason
    // (ck_artifacts_failure_reason_paired).
    String failureCategory = "failed".equals(status) ? "orphan" : null;
    String failureReason = "failed".equals(status) ? "seed_failed" : null;
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, classification,"
            + " storage_ref, checksum_algorithm, checksum_value, failure_category, failure_reason,"
            + " status, linked_event_id, lineage_recovery, created_at) values (?, ?, 'spec', ?,"
            + " 'shareable-redacted', null, null, null, ?, ?, ?, ?, ?, now() - interval '1 hour')",
        artifactPublicId,
        seed.runId,
        version,
        failureCategory,
        failureReason,
        status,
        eventId,
        lineageRecovery);
    return artifactIdOf(artifactPublicId);
  }

  private String seedPendingOperation(
      Seed seed, long eventId, long artifactId, String operationPublicId) {
    jdbcTemplate.update(
        "insert into artifact_operations (public_id, workflow_run_id, artifact_id, artifact_type,"
            + " linked_event_id, operation_type, status, idempotency_key, created_at)"
            + " values (?, ?, ?, 'spec', ?, 'create', 'pending', ?, now() - interval '1 hour')",
        operationPublicId,
        seed.runId,
        artifactId,
        eventId,
        "idem_" + operationPublicId);
    return operationPublicId;
  }

  private long artifactIdOf(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
  }

  private Long parentInternalIdOf(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select parent_artifact_id from artifacts where public_id = ?",
        Long.class,
        artifactPublicId);
  }

  private String statusOf(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select status from artifacts where public_id = ?", String.class, artifactPublicId);
  }

  private int versionOf(String artifactPublicId) {
    return jdbcTemplate.queryForObject(
        "select version from artifacts where public_id = ?", Integer.class, artifactPublicId);
  }

  private boolean lineageRecoveryOf(String artifactPublicId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select lineage_recovery from artifacts where public_id = ?",
            Boolean.class,
            artifactPublicId));
  }

  private String operationStatusOf(String op) {
    return jdbcTemplate.queryForObject(
        "select status from artifact_operations where public_id = ?", String.class, op);
  }

  private String actionTypeOf(String recoveryActionId) {
    return jdbcTemplate.queryForObject(
        "select action_type from recovery_actions where public_id = ?",
        String.class,
        recoveryActionId);
  }

  private String resultStatusOf(String recoveryActionId) {
    return jdbcTemplate.queryForObject(
        "select result_status from recovery_actions where public_id = ?",
        String.class,
        recoveryActionId);
  }

  private int recoveryActionCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'artifact_lineage_reconcile'"
                + "   and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }

  private int lineageReconciledEventCount(String run) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'artifact.lineageReconciled'"
                + "   and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            run);
    return count == null ? 0 : count;
  }
}
