package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.NewIntegrationLink;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression IT for the worker-thread {@link org.hibernate.LazyInitializationException} that wedged
 * spec-investigation dispatch in {@code Investigating}: {@link RepositoryWorkspaceService} calls
 * {@code findActiveByWorkflowRun} from the {@code RunnerWorkerPool} thread — which has NO
 * open-session-in-view (not a web request) and NO ambient transaction — and the entity mapper
 * traverses the lazy {@code WorkflowRunEntity} association, throwing once the per-query session has
 * closed.
 *
 * <p>Deliberately NOT {@code @Transactional} (unlike {@link IntegrationLinkGitHubPrIT}): a
 * test-managed transaction would keep the session open across each port call and mask the very bug
 * under test. Each read therefore runs with no ambient session, exactly as the worker pool does.
 * Seeded rows are committed (no rollback), so the test cleans up after itself to avoid the known
 * cross-IT {@code integration_links} leak (CI flake).
 *
 * <p>Named {@code *IT} so Failsafe (Docker tier) runs it and the no-Docker Surefire tier excludes
 * it ([[springboot-testcontainers-test-must-be-IT]]).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class IntegrationLinkLazyAssociationIT {

  private static final String GITHUB_TYPE = "github_pr";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IntegrationLinkRecordPort port;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<String> seededRuns = new ArrayList<>();
  private final List<String> seededLinks = new ArrayList<>();

  @AfterEach
  void cleanupCommittedRows() {
    for (String linkPublicId : seededLinks) {
      jdbcTemplate.update("delete from integration_links where public_id = ?", linkPublicId);
    }
    for (String runPublicId : seededRuns) {
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", runPublicId);
    }
  }

  @Test
  void findActiveByWorkflowRunResolvesWorkflowRunRefWithNoAmbientSession() {
    String run = seedRun();
    String externalRef = uniqueRef();
    port.insert(githubLink(run, externalRef));

    // Worker-thread condition: no @Transactional, no OSIV. Pre-fix this threw
    // LazyInitializationException on the WorkflowRunEntity proxy inside the mapper.
    IntegrationLink found =
        assertDoesNotThrow(() -> port.findActiveByWorkflowRun(run).orElseThrow());
    assertEquals(run, found.workflowRunPublicId());
  }

  @Test
  void findByPublicIdResolvesWorkflowRunRefWithNoAmbientSession() {
    String run = seedRun();
    String externalRef = uniqueRef();
    IntegrationLink inserted = port.insert(githubLink(run, externalRef));

    IntegrationLink found =
        assertDoesNotThrow(() -> port.findByPublicId(inserted.publicId()).orElseThrow());
    assertEquals(run, found.workflowRunPublicId());
  }

  @Test
  void findActiveByTypeAndExternalRefResolvesWorkflowRunRefWithNoAmbientSession() {
    String run = seedRun();
    String externalRef = uniqueRef();
    port.insert(githubLink(run, externalRef));

    IntegrationLink found =
        assertDoesNotThrow(
            () -> port.findActiveByTypeAndExternalRef(GITHUB_TYPE, externalRef).orElseThrow());
    assertEquals(run, found.workflowRunPublicId());
  }

  private NewIntegrationLink githubLink(String runPublicId, String externalRef) {
    String linkPublicId = PublicIdPrefixes.INTEGRATION_LINK.next();
    seededLinks.add(linkPublicId);
    return new NewIntegrationLink(
        linkPublicId,
        runPublicId,
        GITHUB_TYPE,
        externalRef,
        metadataBytes(),
        Instant.parse("2026-06-10T10:00:00Z"),
        Instant.parse("2026-06-10T10:00:00Z"));
  }

  private byte[] metadataBytes() {
    try {
      return objectMapper.writeValueAsBytes(Map.of("prState", "open"));
    } catch (Exception error) {
      throw new AssertionError("failed to serialize test metadata", error);
    }
  }

  private String uniqueRef() {
    return "octo/hello#" + PublicIdPrefixes.INTEGRATION_LINK.next();
  }

  private String seedRun() {
    String publicId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", publicId, "Inbox");
    seededRuns.add(publicId);
    return publicId;
  }
}
