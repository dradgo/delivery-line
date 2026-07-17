package org.dradgo.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.integration.conflict.IntegrationConflictDetectionService;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 4.25 (AC2b) — recovery-integration CI-tier SCENARIO: the reconcile recovery path
 * end-to-end, driven by a DELIBERATELY INJECTED integration conflict. Composes three existing
 * primitives into the epic AC2(b) fault-recovery flow, all on Testcontainers Postgres:
 *
 * <ol>
 *   <li><b>inject</b> — seed a {@code github_pr} integration link on a {@code WaitingForReview} run
 *       with cached {@code prState=open}, then have the GitHub mock report the PR as merged
 *       (closed+merged) via {@link GitHubMockScenarioRegistry#seedPullRequest} (nothing new is
 *       built — the mock IS the injection primitive);
 *   <li><b>detect + auto-pause</b> — call {@link IntegrationConflictDetectionService#sweep()}
 *       synchronously (the {@code @Scheduled} wrapper is disabled in the test profile) and assert
 *       an {@code external_state_advanced} conflict row + {@code integration.conflictDetected}
 *       event land, and — because this class opts auto-pause back IN via {@link TestPropertySource}
 *       (the shared profile opts out with {@code []}) — the run auto-pauses with a {@code
 *       system}-actor {@code recovery_actions} pause row;
 *   <li><b>reconcile</b> — resolve the conflict via {@link RecoveryService#reconcile} and assert
 *       the run reaches {@code Reconciled} and the conflict row is resolved (linked to the recovery
 *       action).
 * </ol>
 *
 * <p>Templates: {@link org.dradgo.application.integration.conflict.ConflictAutoPauseIT} + {@link
 * IntegrationConflictReconcileIT}. Per-scenario isolation (AC9): fresh {@link TempDir} {@code
 * deliveryline.home} + per-class Testcontainers Postgres. Tagged
 * {@code @Tag("recovery-integration")}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@TestPropertySource(
    properties =
        "deliveryline.integration.conflict-detection.auto-pause-on-categories="
            + "external_state_advanced,external_state_reverted")
@Tag("recovery-integration")
class ReconcileRecoveryScenarioIT {

  private static final String RUN = "run_reconscn0001";
  private static final String LINK = "ilk_reconscn0001";
  // TEST- prefix so GitHubMockScenarioRegistry.clearTestScenarios() actually purges the seeded PR
  // in @AfterEach (it only removes refs beginning with TEST-); prevents cross-scenario leak in the
  // shared github-mock singleton. The ref is an opaque map key — the mock never parses its shape.
  private static final String EXTERNAL_REF = "TEST-octo/repo#4250";
  private static final String KEY = "idem-scenario-reconcile-0001";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictDetectionService detectionService;
  @Autowired private RecoveryService recoveryService;
  @Autowired private GitHubMockScenarioRegistry gitHubScenarios;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from integration_conflicts where integration_link_id = ?", LINK);
    jdbcTemplate.update("delete from integration_links where public_id = ?", LINK);
    jdbcTemplate.update(
        "delete from recovery_actions where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        RUN);
    jdbcTemplate.update("delete from idempotency_records where key = ?", KEY);
    jdbcTemplate.update(
        "delete from workflow_events where workflow_run_id ="
            + " (select id from workflow_runs where public_id = ?)",
        RUN);
    jdbcTemplate.update("delete from workflow_runs where public_id = ?", RUN);
    gitHubScenarios.clearTestScenarios();
  }

  @Test
  void injectedConflictDetectsAutoPausesAndReconcilesToReconciled() {
    seedGitHubLink();
    // Inject: fresh PR merged (closed + merged=true) over cached open baseline → advanced.
    gitHubScenarios.seedPullRequest(pr("closed", true));

    detectionService.sweep();

    // Detected + persisted with the right category and event.
    String conflictId =
        jdbcTemplate.queryForObject(
            "select public_id from integration_conflicts where integration_link_id = ?",
            String.class,
            LINK);
    assertThat(conflictId).startsWith("icf_");
    assertThat(conflictCategory())
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value());
    assertThat(conflictDetectedEventCount()).isEqualTo(1);

    // Auto-paused (opted in via @TestPropertySource) with a system-actor pause action.
    assertThat(currentState()).isEqualTo("Paused");
    assertThat(systemPauseActionCount()).isEqualTo(1);

    // Reconcile: accept the external state → run reaches Reconciled + conflict resolved.
    ReconcileRecoveryResult result =
        recoveryService.reconcile(
            RUN,
            conflictId,
            "accept_external_state",
            KEY,
            new ActorContext("alex", ActorType.HUMAN, "corr-reconcile-scenario"),
            "operator accepted the merged external state");

    assertThat(result.resolvedConflictId()).isEqualTo(conflictId);
    assertThat(result.resultingState()).isEqualTo(WorkflowState.RECONCILED);
    assertThat(result.replayed()).isFalse();
    assertThat(currentState()).isEqualTo(WorkflowState.RECONCILED.value());

    String resolvedBy =
        jdbcTemplate.queryForObject(
            "select resolved_by_action_id from integration_conflicts where public_id = ?",
            String.class,
            conflictId);
    assertThat(resolvedBy).isEqualTo(result.recoveryActionPublicId());
  }

  private void seedGitHubLink() {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')", RUN);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, RUN);
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, 'github_pr', ?, cast(? as jsonb), 'synced')",
        LINK,
        runId,
        EXTERNAL_REF,
        "{\"prState\":\"open\"}");
  }

  private static PullRequest pr(String state, boolean merged) {
    return new PullRequest(
        PullRequestRef.of(EXTERNAL_REF),
        RepositoryRef.of("octo/repo"),
        1,
        "feature/x",
        state,
        merged,
        "https://github.com/octo/repo/pull/1",
        Instant.parse("2026-05-01T10:00:00Z"));
  }

  private String currentState() {
    return jdbcTemplate.queryForObject(
        "select current_state from workflow_runs where public_id = ?", String.class, RUN);
  }

  private String conflictCategory() {
    return jdbcTemplate.queryForObject(
        "select conflict_category from integration_conflicts where integration_link_id = ?"
            + " order by detected_at desc limit 1",
        String.class,
        LINK);
  }

  private int conflictDetectedEventCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events where event_type = 'integration.conflictDetected'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            RUN);
    return count == null ? 0 : count;
  }

  private int systemPauseActionCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions where action_type = 'pause'"
                + " and actor_type = 'system' and reviewer_role = 'system'"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            RUN);
    return count == null ? 0 : count;
  }
}
