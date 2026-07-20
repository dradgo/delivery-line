package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.17 (AC10) — real-Postgres coverage of the conflict-detection sweep: per-category GitHub
 * detection via mock conflict scenarios, Linear removed, insert-or-skip dedup, rate-limit backoff,
 * archived-link exclusion, and that {@code listUnresolvedConflicts} surfaces the row. Named {@code
 * *IT} so it runs under Failsafe (Testcontainers), never the no-Docker Surefire tier.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("integration")
class IntegrationConflictDetectionIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictDetectionService detectionService;
  @Autowired private IntegrationConflictService conflictService;
  @Autowired private GitHubMockScenarioRegistry gitHubScenarios;

  private final List<String> seededRuns = new java.util.ArrayList<>();
  private final List<String> seededLinks = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String link : seededLinks) {
      jdbcTemplate.update("delete from integration_conflicts where integration_link_id = ?", link);
    }
    for (String link : seededLinks) {
      jdbcTemplate.update("delete from integration_links where public_id = ?", link);
    }
    for (String run : seededRuns) {
      jdbcTemplate.update(
          "delete from workflow_events where workflow_run_id = "
              + "(select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    gitHubScenarios.clearTestScenarios();
    seededRuns.clear();
    seededLinks.clear();
  }

  @Test
  void mergedPullRequestWhileOpenPersistsExternalStateAdvancedAndIsListable() {
    String link = seedGitHubLink("octo/repo#4171", "{\"prState\":\"open\"}");
    // Fresh PR is merged (closed + merged=true) while the cached baseline was open.
    gitHubScenarios.seedPullRequest(pr("octo/repo#4171", "octo/repo", "feature/x", "closed", true));

    SweepResult result = detectionService.sweep();

    assertThat(result.conflictsDetected()).isGreaterThanOrEqualTo(1);
    assertThat(categoryFor(link))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value());
    assertThat(conflictEventCount(link)).isEqualTo(1);

    List<ConflictSummary> listed =
        conflictService.listUnresolvedConflicts(ConflictFilter.unfiltered());
    assertThat(listed).anySatisfy(c -> assertThat(c.integrationLinkId()).isEqualTo(link));
  }

  @Test
  void deletedPullRequestPersistsExternalResourceRemoved() {
    String link = seedGitHubLink("octo/repo#4172", "{\"prState\":\"open\"}");
    // No PR seeded — getPullRequestByRef resolves empty (deleted externally).

    detectionService.sweep();

    assertThat(categoryFor(link))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED.value());
  }

  @Test
  void branchDriftPersistsMetadataDrift() {
    String link =
        seedGitHubLink("octo/repo#4173", "{\"prState\":\"open\",\"branch\":\"feature/orig\"}");
    gitHubScenarios.seedPullRequest(
        pr("octo/repo#4173", "octo/repo", "feature/renamed", "open", false));

    detectionService.sweep();

    assertThat(categoryFor(link)).isEqualTo(IntegrationConflictCategory.METADATA_DRIFT.value());
  }

  @Test
  void reopenedPullRequestPersistsExternalStateReverted() {
    String link = seedGitHubLink("octo/repo#4178", "{\"prState\":\"closed\"}");
    // Fresh PR is open again (reopened) while the cached baseline was closed.
    gitHubScenarios.seedPullRequest(pr("octo/repo#4178", "octo/repo", "feature/x", "open", false));

    detectionService.sweep();

    assertThat(categoryFor(link))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_REVERTED.value());
  }

  @Test
  void permissionDeniedPullRequestPersistsLinkBroken() {
    String link = seedGitHubLink("octo/repo#4179", "{\"prState\":\"open\"}");
    gitHubScenarios.register(
        new GitHubMockScenario(
            "octo/repo#4179",
            GitHubMockScenario.Behaviour.PERMISSION_DENIED,
            null,
            IntegrationFailureCategory.GITHUB_PERMISSION_DENIED));

    detectionService.sweep();

    assertThat(categoryFor(link)).isEqualTo(IntegrationConflictCategory.LINK_BROKEN.value());
  }

  @Test
  void secondSweepIsDedupedNoNewRowOrEvent() {
    String link = seedGitHubLink("octo/repo#4174", "{\"prState\":\"open\"}");
    // deleted PR → external_resource_removed on the first tick.
    detectionService.sweep();
    detectionService.sweep();

    Integer rows =
        jdbcTemplate.queryForObject(
            "select count(*) from integration_conflicts where integration_link_id = ?",
            Integer.class,
            link);
    assertThat(rows).isEqualTo(1);
    assertThat(conflictEventCount(link)).isEqualTo(1);
  }

  @Test
  void rateLimitedGitHubLinkYieldsNoConflictAndBacksOff() {
    String link = seedGitHubLink("octo/repo#4175", "{\"prState\":\"open\"}");
    gitHubScenarios.register(
        new GitHubMockScenario(
            "octo/repo#4175",
            GitHubMockScenario.Behaviour.RATE_LIMITED,
            null,
            IntegrationFailureCategory.GITHUB_RATE_LIMITED));

    SweepResult result = detectionService.sweep();

    assertThat(result.rateLimited()).isTrue();
    assertThat(conflictCount(link)).isZero();
  }

  @Test
  void archivedLinkIsExcludedFromDetection() {
    String link = seedGitHubLink("octo/repo#4176", "{\"prState\":\"open\"}");
    jdbcTemplate.update(
        "update integration_links set archived_at = now() where public_id = ?", link);
    // Even with a deleted PR, an archived link must NOT be scanned.

    detectionService.sweep();

    assertThat(conflictCount(link)).isZero();
  }

  @Test
  void pausedRunWithDriftedActiveLinkIsStillSurfacedBySweep() {
    // Story 4.8 (AC8 / Reconciliation 10) — integration awareness continues while paused: the
    // sweep's scan SQL has NO predicate on workflow_runs.current_state, so a Paused run's active
    // link is scanned exactly like any other, and the sweep only RECORDS conflicts (it performs no
    // transition, so it can never un-pause the run). Regression pin — no production change.
    String link = seedGitHubLink("octo/repo#4808", "{\"prState\":\"open\"}");
    String run = seededRuns.get(seededRuns.size() - 1);
    // Guard against seed-helper drift: the drifted link must provably belong to the SAME run we
    // pause below — otherwise the "still surfaced while Paused" assertion passes vacuously against
    // some other (non-paused) run's link.
    assertThat(runOwningLink(link)).isEqualTo(run);
    jdbcTemplate.update(
        "update workflow_runs set current_state = 'Paused' where public_id = ?", run);
    gitHubScenarios.seedPullRequest(pr("octo/repo#4808", "octo/repo", "feature/x", "closed", true));

    detectionService.sweep();

    assertThat(categoryFor(link))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED.value());
    List<ConflictSummary> listed =
        conflictService.listUnresolvedConflicts(ConflictFilter.unfiltered());
    // The surfaced conflict is anchored to the PAUSED run (not merely to any link).
    assertThat(listed)
        .anySatisfy(
            c -> {
              assertThat(c.integrationLinkId()).isEqualTo(link);
              assertThat(c.workflowRunId()).isEqualTo(run);
            });
    // The sweep never un-pauses the run.
    assertThat(
            jdbcTemplate.queryForObject(
                "select current_state from workflow_runs where public_id = ?", String.class, run))
        .isEqualTo("Paused");
  }

  @Test
  void deletedLinearTicketPersistsExternalResourceRemoved() {
    // An external_ref the Linear mock does not know → fetchTicketByReference empty → removed.
    String link = seedLinearLink("TEST-LIN-GONE-4177");

    detectionService.sweep();

    assertThat(categoryFor(link))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED.value());
  }

  // ---- seeding + assertion helpers --------------------------------------------------------------

  private String seedGitHubLink(String externalRef, String metadataJson) {
    return seedLink("github_pr", externalRef, metadataJson);
  }

  private String seedLinearLink(String externalRef) {
    return seedLink("linear", externalRef, "{}");
  }

  private String seedLink(String integrationType, String externalRef, String metadataJson) {
    String suffix = Integer.toHexString(System.identityHashCode(externalRef)) + seededLinks.size();
    String run = "run_icfit" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')", run);
    seededRuns.add(run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    String link = "ilk_test" + suffix;
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, ?, ?, cast(? as jsonb), 'synced')",
        link,
        runId,
        integrationType,
        externalRef,
        metadataJson);
    seededLinks.add(link);
    return link;
  }

  private String runOwningLink(String link) {
    return jdbcTemplate.queryForObject(
        "select r.public_id from workflow_runs r"
            + " join integration_links l on l.workflow_run_id = r.id"
            + " where l.public_id = ?",
        String.class,
        link);
  }

  private String categoryFor(String link) {
    return jdbcTemplate.queryForObject(
        "select conflict_category from integration_conflicts where integration_link_id = ?"
            + " order by detected_at desc limit 1",
        String.class,
        link);
  }

  private int conflictCount(String link) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from integration_conflicts where integration_link_id = ?",
            Integer.class,
            link);
    return count == null ? 0 : count;
  }

  private int conflictEventCount(String link) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events e"
                + " where e.event_type = 'integration.conflictDetected'"
                + "   and e.workflow_run_id = ("
                + "     select workflow_run_id from integration_links where public_id = ?)",
            Integer.class,
            link);
    return count == null ? 0 : count;
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
