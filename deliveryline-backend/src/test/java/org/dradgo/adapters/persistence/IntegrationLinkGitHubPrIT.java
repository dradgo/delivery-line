package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3.15 (Task 9 / AC10) — persistence integration test for the {@code github_pr} integration
 * link surface against a real Postgres: the V6 cross-run conflict index, the {@code LINKED →
 * SUPERSEDED} supersede-across-rows path (story 3.15 state-machine addition), and the new {@code
 * updateExternalMetadataAndSync} round-trip. Named {@code *IT} so Failsafe (Docker tier) runs it
 * and Surefire's no-Docker fast tier excludes it (Trap T6,
 * [[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
// The persistence adapter relies on an ambient transaction (its mapper traverses the lazy
// WorkflowRun association); production callers wrap it in @Transactional. The test-managed tx keeps
// the session open across each method (and rolls back for isolation).
@Transactional
class IntegrationLinkGitHubPrIT {

  private static final String GITHUB_TYPE = "github_pr";
  private static final String PR_REF = "octo/hello#42";
  private static final String OTHER_PR_REF = "octo/hello#43";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationLinkRecordPort port;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void crossRunDoubleLinkOnSameExternalRefHitsV6IndexAndRaisesConflict() {
    String runA = seedRun();
    String runB = seedRun();
    port.insert(githubLink(runA, PR_REF));

    DomainException error =
        assertThrows(DomainException.class, () -> port.insert(githubLink(runB, PR_REF)));
    assertEquals(DomainErrorCode.INTEGRATION_LINK_CONFLICT, error.errorCode());
  }

  @Test
  void supersededRowFreesTheExternalRefForAnotherRun() {
    String runA = seedRun();
    String runB = seedRun();
    IntegrationLink first = port.insert(githubLink(runA, PR_REF));

    // LINKED → SUPERSEDED is legal as of story 3.15; the V6 partial index excludes superseded rows.
    port.updateSyncStatus(first.publicId(), IntegrationSyncStatus.SUPERSEDED, null);

    // With runA's link superseded, runB may now claim the same canonical PR ref (the V6 cross-run
    // conflict would have rejected this while runA's link was active).
    IntegrationLink relinked = port.insert(githubLink(runB, PR_REF));
    assertEquals(IntegrationSyncStatus.LINKED, relinked.syncStatus());
    assertEquals(runB, relinked.workflowRunPublicId());

    // The superseded row remains for audit.
    IntegrationLink reloadedFirst = port.findByPublicId(first.publicId()).orElseThrow();
    assertEquals(IntegrationSyncStatus.SUPERSEDED, reloadedFirst.syncStatus());
  }

  @Test
  void supersedePriorThenLinkNewPrLeavesBothRowsVisibleInAudit() {
    String run = seedRun();
    IntegrationLink prior = port.insert(githubLink(run, OTHER_PR_REF));
    port.updateSyncStatus(prior.publicId(), IntegrationSyncStatus.SUPERSEDED, null);
    IntegrationLink fresh = port.insert(githubLink(run, PR_REF));

    Optional<IntegrationLink> active =
        port.findActiveByTypeAndWorkflowRunForUpdate(GITHUB_TYPE, run);
    assertTrue(active.isPresent());
    assertEquals(fresh.publicId(), active.get().publicId());
    assertEquals(PR_REF, active.get().externalRef());

    IntegrationLink reloadedPrior = port.findByPublicId(prior.publicId()).orElseThrow();
    assertEquals(IntegrationSyncStatus.SUPERSEDED, reloadedPrior.syncStatus());
  }

  @Test
  void updateExternalMetadataAndSyncRefreshesMetadataAndTransitionsToSynced() {
    String run = seedRun();
    IntegrationLink linked = port.insert(githubLink(run, PR_REF));

    byte[] refreshed = metadataBytes("merged");
    Instant syncedAt = Instant.parse("2026-06-10T12:00:00Z");
    IntegrationLink updated =
        port.updateExternalMetadataAndSync(
            linked.publicId(), refreshed, IntegrationSyncStatus.SYNCED, syncedAt);

    assertEquals(IntegrationSyncStatus.SYNCED, updated.syncStatus());
    assertNotNull(updated.lastSyncAt());

    String storedMetadata =
        jdbcTemplate.queryForObject(
            "select external_metadata::text from integration_links where public_id = ?",
            String.class,
            linked.publicId());
    assertNotNull(storedMetadata);
    assertTrue(
        storedMetadata.contains("merged"),
        "refreshed prState must be persisted; got: " + storedMetadata);
  }

  @Test
  void findActiveTicketSummaryByTypeAndWorkflowRunReturnsGitHubMetadataNonLocking() {
    // Story 3b-5 — the NON-locking typed projection the artifact-read path uses to surface prState.
    // Resolves the github_pr row's external ref + metadata (NOT the linear-convention first row)
    // and
    // excludes superseded rows.
    String run = seedRun();
    port.insert(githubLink(run, PR_REF));

    Optional<IntegrationLinkRecordPort.TicketSummaryProjection> projection =
        port.findActiveTicketSummaryByTypeAndWorkflowRun(GITHUB_TYPE, run);

    assertTrue(projection.isPresent());
    assertEquals(PR_REF, projection.get().externalRef());
    String metadata =
        new String(projection.get().externalMetadata(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(metadata.contains("\"prState\""), "metadata must carry prState: " + metadata);
    assertTrue(metadata.contains("open"), "metadata must carry the prState value: " + metadata);
  }

  @Test
  void findActiveTicketSummaryByTypeAndWorkflowRunEmptyWhenOnlySupersededRow() {
    String run = seedRun();
    IntegrationLink linked = port.insert(githubLink(run, PR_REF));
    port.updateSyncStatus(linked.publicId(), IntegrationSyncStatus.SUPERSEDED, null);

    assertTrue(port.findActiveTicketSummaryByTypeAndWorkflowRun(GITHUB_TYPE, run).isEmpty());
  }

  private NewIntegrationLink githubLink(String runPublicId, String externalRef) {
    return new NewIntegrationLink(
        PublicIdPrefixes.INTEGRATION_LINK.next(),
        runPublicId,
        GITHUB_TYPE,
        externalRef,
        metadataBytes("open"),
        Instant.parse("2026-06-10T10:00:00Z"),
        Instant.parse("2026-06-10T10:00:00Z"));
  }

  private byte[] metadataBytes(String prState) {
    try {
      return objectMapper.writeValueAsBytes(
          Map.of(
              "repositoryFullName", "octo/hello",
              "branch", "feature/x",
              "commitSha", "a1b2c3d4e5f6a7b8c9d0",
              "prNumber", 42,
              "prState", prState,
              "prUrl", "https://github.com/octo/hello/pull/42"));
    } catch (Exception error) {
      throw new AssertionError("failed to serialize test metadata", error);
    }
  }

  private String seedRun() {
    String publicId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", publicId, "Inbox");
    Long id =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, publicId);
    assertNotNull(id);
    return publicId;
  }
}
