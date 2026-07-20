package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenario;
import org.dradgo.adapters.integration.repohost.github.GitHubMockScenarioRegistry;
import org.dradgo.domain.integration.repohost.PullRequest;
import org.dradgo.domain.integration.repohost.PullRequestRef;
import org.dradgo.domain.integration.repohost.RepositoryRef;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.dradgo.domain.registry.IntegrationFailureCategory;
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
 * Story 4.25 (AC4) — recovery-integration CI-tier SCENARIO: integration-conflict fault injection
 * across ALL FIVE conflict categories, driven end-to-end through the GitHub mock on Testcontainers
 * Postgres. For each category a {@code github_pr} link is seeded on its own {@code
 * WaitingForReview} run with a cached baseline, the GitHub mock is driven to produce the drift
 * (nothing new is built for injection — {@link GitHubMockScenarioRegistry} IS the seam), and a
 * SINGLE synchronous {@link IntegrationConflictDetectionService#sweep()} records every conflict:
 *
 * <ul>
 *   <li>{@code external_state_advanced} — merged (closed+merged) over cached open;
 *   <li>{@code external_state_reverted} — reopened (open) over cached closed;
 *   <li>{@code external_resource_removed} — PR removed (no PR resolves) over cached open;
 *   <li>{@code metadata_drift} — drifted source branch over cached branch;
 *   <li>{@code link_broken} — a {@code PERMISSION_DENIED} scenario.
 * </ul>
 *
 * <p>Auto-pause (NFR21) is opted back IN via {@link TestPropertySource} for ONLY the two default
 * state-drift categories ({@code external_state_advanced}/{@code external_state_reverted}); the
 * scenario asserts those two runs auto-pause and the other three detect-only (stay {@code
 * WaitingForReview}) — proving the default auto-pause set is honored. Template: {@link
 * IntegrationConflictDetectionIT}.
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres + {@code clearTestScenarios()} in {@code @AfterEach}. Tagged
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
class IntegrationConflictCategoryScenarioIT {

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictDetectionService detectionService;
  @Autowired private GitHubMockScenarioRegistry gitHubScenarios;

  private final List<String> seededRuns = new ArrayList<>();
  private final List<String> seededLinks = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String link : seededLinks) {
      jdbcTemplate.update("delete from integration_conflicts where integration_link_id = ?", link);
      jdbcTemplate.update("delete from integration_links where public_id = ?", link);
    }
    for (String run : seededRuns) {
      jdbcTemplate.update(
          "delete from recovery_actions where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update(
          "delete from workflow_events where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    gitHubScenarios.clearTestScenarios();
    seededRuns.clear();
    seededLinks.clear();
  }

  @Test
  void allFiveConflictCategoriesAreDetectedAndOnlyTheTwoDefaultCategoriesAutoPause() {
    // --- inject each category via the GitHub mock ---
    String advancedLink = seedLink("TEST-octo/repo#4501", "{\"prState\":\"open\"}");
    gitHubScenarios.seedPullRequest(pr("TEST-octo/repo#4501", "feature/x", "closed", true));

    String revertedLink = seedLink("TEST-octo/repo#4502", "{\"prState\":\"closed\"}");
    gitHubScenarios.seedPullRequest(pr("TEST-octo/repo#4502", "feature/x", "open", false));

    String removedLink = seedLink("TEST-octo/repo#4503", "{\"prState\":\"open\"}");
    // No PR seeded → getPullRequestByRef resolves empty → external_resource_removed.

    String driftLink =
        seedLink("TEST-octo/repo#4504", "{\"prState\":\"open\",\"branch\":\"feature/orig\"}");
    gitHubScenarios.seedPullRequest(pr("TEST-octo/repo#4504", "feature/renamed", "open", false));

    String brokenLink = seedLink("TEST-octo/repo#4505", "{\"prState\":\"open\"}");
    gitHubScenarios.register(
        new GitHubMockScenario(
            "TEST-octo/repo#4505",
            GitHubMockScenario.Behaviour.PERMISSION_DENIED,
            null,
            IntegrationFailureCategory.GITHUB_PERMISSION_DENIED));

    // --- one synchronous sweep detects all five ---
    detectionService.sweep();

    assertThat(categoryFor(advancedLink))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value());
    assertThat(categoryFor(revertedLink))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_REVERTED.value());
    assertThat(categoryFor(removedLink))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED.value());
    assertThat(categoryFor(driftLink))
        .isEqualTo(IntegrationConflictCategory.METADATA_DRIFT.value());
    assertThat(categoryFor(brokenLink)).isEqualTo(IntegrationConflictCategory.LINK_BROKEN.value());

    // Auto-pause fires ONLY for the two default state-drift categories (NFR21).
    assertThat(runStateFor(advancedLink)).isEqualTo("Paused");
    assertThat(runStateFor(revertedLink)).isEqualTo("Paused");
    // The other three detect-only — no auto-pause, no recovery_actions row.
    assertThat(runStateFor(removedLink)).isEqualTo("WaitingForReview");
    assertThat(runStateFor(driftLink)).isEqualTo("WaitingForReview");
    assertThat(runStateFor(brokenLink)).isEqualTo("WaitingForReview");
    assertThat(recoveryActionCountFor(removedLink)).isZero();
    assertThat(recoveryActionCountFor(driftLink)).isZero();
    assertThat(recoveryActionCountFor(brokenLink)).isZero();
  }

  // ---- seed + assertion helpers -----------------------------------------------------------------

  private String seedLink(String externalRef, String metadataJson) {
    String suffix = Integer.toHexString(System.identityHashCode(externalRef)) + seededLinks.size();
    String run = "run_icfscn" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')", run);
    seededRuns.add(run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    String link = "ilk_icfscn" + suffix;
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, 'github_pr', ?, cast(? as jsonb), 'synced')",
        link,
        runId,
        externalRef,
        metadataJson);
    seededLinks.add(link);
    return link;
  }

  private static PullRequest pr(String prRef, String branch, String state, boolean merged) {
    return new PullRequest(
        PullRequestRef.of(prRef),
        RepositoryRef.of("octo/repo"),
        1,
        branch,
        state,
        merged,
        "https://github.com/octo/repo/pull/1",
        Instant.parse("2026-05-01T10:00:00Z"));
  }

  private String categoryFor(String link) {
    return jdbcTemplate.queryForObject(
        "select conflict_category from integration_conflicts where integration_link_id = ?"
            + " order by detected_at desc limit 1",
        String.class,
        link);
  }

  private String runStateFor(String link) {
    return jdbcTemplate.queryForObject(
        "select r.current_state from workflow_runs r"
            + " join integration_links l on l.workflow_run_id = r.id"
            + " where l.public_id = ?",
        String.class,
        link);
  }

  private int recoveryActionCountFor(String link) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from recovery_actions ra"
                + " join integration_links l on l.workflow_run_id = ra.workflow_run_id"
                + " where l.public_id = ?",
            Integer.class,
            link);
    return count == null ? 0 : count;
  }
}
