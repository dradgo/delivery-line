package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Story 4.18 (AC4/AC10) — real-Postgres end-to-end coverage of the conflict-driven auto-pause: a
 * NEW high-severity conflict detected by the sweep pauses the run with {@code actor='system'} and,
 * via the pause-prep change, stamps {@code recovery_actions.reviewer_role='system'} — the branch a
 * mock-based unit test cannot prove reaches the DB. Opts INTO auto-pause via {@link
 * TestPropertySource} (the shared test profile opts out — see {@code
 * src/test/resources/application.yml}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@TestPropertySource(
    properties =
        "deliveryline.integration.conflict-detection.auto-pause-on-categories="
            + "external_state_advanced,external_state_reverted")
@Tag("integration")
class ConflictAutoPauseIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictDetectionService detectionService;
  @Autowired private GitHubMockScenarioRegistry gitHubScenarios;

  private String seededRun;
  private String seededLink;

  @AfterEach
  void cleanUp() {
    if (seededLink != null) {
      jdbcTemplate.update(
          "delete from integration_conflicts where integration_link_id = ?", seededLink);
      jdbcTemplate.update("delete from integration_links where public_id = ?", seededLink);
    }
    if (seededRun != null) {
      // recovery_actions FK workflow_runs (RESTRICT) — delete the auto-pause row before the run.
      jdbcTemplate.update(
          "delete from recovery_actions where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          seededRun);
      jdbcTemplate.update(
          "delete from workflow_events where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          seededRun);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", seededRun);
    }
    gitHubScenarios.clearTestScenarios();
    seededRun = null;
    seededLink = null;
  }

  @Test
  void highSeverityConflictAutoPausesRunWithSystemReviewerRole() {
    seedGitHubLink("octo/repo#4180", "{\"prState\":\"open\"}");
    // Fresh PR merged (closed + merged=true) while cached baseline open → external_state_advanced.
    gitHubScenarios.seedPullRequest(pr("octo/repo#4180", "octo/repo", "feature/x", "closed", true));

    SweepResult result = detectionService.sweep();

    assertThat(result.conflictsDetected()).isGreaterThanOrEqualTo(1);
    // The run was auto-paused (WaitingForReview → Paused).
    assertThat(
            jdbcTemplate.queryForObject(
                "select current_state from workflow_runs where public_id = ?",
                String.class,
                seededRun))
        .isEqualTo("Paused");
    // The auto-pause recovery_actions row is a system-actor pause with reviewer_role='system'.
    assertThat(
            jdbcTemplate.queryForObject(
                "select reviewer_role from recovery_actions"
                    + " where workflow_run_id = (select id from workflow_runs where public_id = ?)"
                    + "   and action_type = 'pause'",
                String.class,
                seededRun))
        .isEqualTo("system");
    assertThat(
            jdbcTemplate.queryForObject(
                "select actor_type from recovery_actions"
                    + " where workflow_run_id = (select id from workflow_runs where public_id = ?)"
                    + "   and action_type = 'pause'",
                String.class,
                seededRun))
        .isEqualTo("system");
  }

  @Test
  void lowSeverityConflictDoesNotAutoPause() {
    seedGitHubLink("octo/repo#4181", "{\"prState\":\"open\",\"branch\":\"feature/orig\"}");
    // Branch drift → metadata_drift (not a high-severity auto-pause category).
    gitHubScenarios.seedPullRequest(
        pr("octo/repo#4181", "octo/repo", "feature/renamed", "open", false));

    detectionService.sweep();

    assertThat(
            jdbcTemplate.queryForObject(
                "select current_state from workflow_runs where public_id = ?",
                String.class,
                seededRun))
        .isEqualTo("WaitingForReview");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from recovery_actions where workflow_run_id ="
                    + " (select id from workflow_runs where public_id = ?)",
                Integer.class,
                seededRun))
        .isZero();
  }

  private void seedGitHubLink(String externalRef, String metadataJson) {
    String suffix = Integer.toHexString(System.identityHashCode(externalRef));
    seededRun = "run_apit" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')",
        seededRun);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, seededRun);
    seededLink = "ilk_apit" + suffix;
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, 'github_pr', ?, cast(? as jsonb), 'synced')",
        seededLink,
        runId,
        externalRef,
        metadataJson);
  }

  private static PullRequest pr(
      String prRef, String repoRef, String branch, String state, boolean merged) {
    return new PullRequest(
        PullRequestRef.of(prRef),
        RepositoryRef.of(repoRef),
        1,
        branch,
        state,
        merged,
        "https://github.com/" + repoRef + "/pull/1",
        Instant.parse("2026-05-01T10:00:00Z"));
  }
}
