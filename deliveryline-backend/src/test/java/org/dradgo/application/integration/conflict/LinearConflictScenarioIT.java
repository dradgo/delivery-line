package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.integration.ticketsource.linear.LinearMockScenario;
import org.dradgo.adapters.integration.ticketsource.linear.LinearMockScenarioRegistry;
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

/**
 * Story 4.25 (AC4, Reconciliation 3 / OQ-3) — recovery-integration CI-tier SCENARIO: integration
 * conflict fault injection through the LINEAR mock. The Linear mock is fixture-JSON driven with NO
 * state-seeding API, so it can reliably produce only the two NON-state-drift categories — {@code
 * external_resource_removed} (an unknown ticket ref → {@code NOT_FOUND}) and {@code link_broken} (a
 * permanent access failure → {@code AUTH_FAILURE} → {@code LINK_FAILURE} → {@code link_broken}).
 * Both are driven off the pre-registered stable adversarial refs ({@link
 * LinearMockScenarioRegistry#TICKET_NOT_FOUND}, {@link
 * LinearMockScenarioRegistry#TICKET_AUTH_FAILURE}) with a single synchronous {@link
 * IntegrationConflictDetectionService#sweep()}.
 *
 * <p><b>Documented gap (OQ-3):</b> the state-drift categories ({@code external_state_advanced} /
 * {@code _reverted} / {@code metadata_drift}) have NO Linear coverage here because the mock has no
 * persisted state baseline to drift from ({@code sourceStatusId} is provisional). Those categories
 * are exhaustively covered on the GitHub-PR vehicle in {@link
 * IntegrationConflictCategoryScenarioIT}; building a Linear {@code sourceStatusId} baseline is a
 * 4.17 follow-up, deliberately out of scope for 4.25.
 *
 * <p>Per-scenario isolation (AC9): fresh {@link TempDir} {@code deliveryline.home} + per-class
 * Testcontainers Postgres. Tagged {@code @Tag("recovery-integration")}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "github-mock"})
@Tag("recovery-integration")
class LinearConflictScenarioIT {

  private static final String REMOVED_RUN = "run_linscnrem01";
  private static final String REMOVED_LINK = "ilk_linscnrem01";
  private static final String BROKEN_RUN = "run_linscnbrk01";
  private static final String BROKEN_LINK = "ilk_linscnbrk01";

  // TEST- prefixed refs so LinearMockScenarioRegistry.clearTestScenarios() (which only removes
  // ticketRefs beginning with TEST-) actually purges these registrations in @AfterEach — restoring
  // the "TICKET_AUTH_FAILURE is NOT registered by default" invariant other tests in the shared
  // linear-mock context rely on. The registry's public LIN-NOT-FOUND / LIN-AUTH-FAILURE constants
  // are production-owned (not TEST- prefixed) so we register under local TEST- aliases instead; the
  // ref is an opaque scenario key, and only the registered Behaviour drives the conflict category.
  private static final String TEST_TICKET_NOT_FOUND = "TEST-LIN-NOT-FOUND";
  private static final String TEST_TICKET_AUTH_FAILURE = "TEST-LIN-AUTH-FAILURE";

  @TempDir static Path deliverylineHome;

  @DynamicPropertySource
  static void deliverylineHome(DynamicPropertyRegistry registry) {
    registry.add("deliveryline.home", () -> deliverylineHome.toString());
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationConflictDetectionService detectionService;
  @Autowired private LinearMockScenarioRegistry linearScenarios;

  @AfterEach
  void cleanUp() {
    for (String link : new String[] {REMOVED_LINK, BROKEN_LINK}) {
      jdbcTemplate.update("delete from integration_conflicts where integration_link_id = ?", link);
      jdbcTemplate.update("delete from integration_links where public_id = ?", link);
    }
    for (String run : new String[] {REMOVED_RUN, BROKEN_RUN}) {
      jdbcTemplate.update(
          "delete from workflow_events where workflow_run_id ="
              + " (select id from workflow_runs where public_id = ?)",
          run);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    linearScenarios.clearTestScenarios();
  }

  @Test
  void linearRemovedAndLinkBrokenConflictsAreDetected() {
    // Inject: NOT_FOUND → empty ticket → external_resource_removed; AUTH_FAILURE → LINK_FAILURE →
    // link_broken (the Linear mock has no seedTicket, so these adversarial behaviours are the only
    // two conflict categories it can drive — Reconciliation 3 / OQ-3). The refs are registered
    // explicitly here (they are NOT default-registered; the registry only reserves the constants).
    linearScenarios.register(
        new LinearMockScenario(
            TEST_TICKET_NOT_FOUND, LinearMockScenario.Behaviour.NOT_FOUND, null, null));
    linearScenarios.register(
        new LinearMockScenario(
            TEST_TICKET_AUTH_FAILURE,
            LinearMockScenario.Behaviour.AUTH_FAILURE,
            null,
            IntegrationFailureCategory.LINK_FAILURE));
    seedLinearLink(REMOVED_RUN, REMOVED_LINK, TEST_TICKET_NOT_FOUND);
    seedLinearLink(BROKEN_RUN, BROKEN_LINK, TEST_TICKET_AUTH_FAILURE);

    detectionService.sweep();

    assertThat(categoryFor(REMOVED_LINK))
        .isEqualTo(IntegrationConflictCategory.EXTERNAL_RESOURCE_REMOVED.value());
    assertThat(categoryFor(BROKEN_LINK)).isEqualTo(IntegrationConflictCategory.LINK_BROKEN.value());
  }

  private void seedLinearLink(String run, String link, String externalRef) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'WaitingForReview')", run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    jdbcTemplate.update(
        "insert into integration_links"
            + " (public_id, workflow_run_id, integration_type, external_ref, external_metadata,"
            + " sync_status) values (?, ?, 'linear', ?, cast('{}' as jsonb), 'synced')",
        link,
        runId,
        externalRef);
  }

  private String categoryFor(String link) {
    return jdbcTemplate.queryForObject(
        "select conflict_category from integration_conflicts where integration_link_id = ?"
            + " order by detected_at desc limit 1",
        String.class,
        link);
  }
}
