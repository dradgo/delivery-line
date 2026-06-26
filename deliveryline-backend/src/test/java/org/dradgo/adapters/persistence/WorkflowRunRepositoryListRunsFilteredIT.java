package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 3f-6 — verifies the optional-filter JPQL in {@link
 * WorkflowRunRepository#listRunsFiltered(String, boolean, String, Pageable)} against a real
 * datasource. The adapter was rewritten from four derived queries to this single optional-filter
 * query, so this IT pins (a) project-id narrowing, (b) state composition, (c) include-archived
 * composition, (d) newest-first ordering with the id tiebreak, and (e) no-filter parity: the
 * default no-project paths must return the same rows/order as the legacy derived-query methods the
 * rewrite replaced.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class WorkflowRunRepositoryListRunsFilteredIT {

  private static final String PROJECT_ALPHA = "prj_alpha_filter_it";
  private static final String PROJECT_BETA = "prj_beta_filter_it";
  private static final Pageable PAGE = PageRequest.of(0, 50);

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private WorkflowRunRepository repository;

  @BeforeEach
  void setup() {
    cleanDatabase();
    insertProject(PROJECT_ALPHA, "alpha-filter-it");
    insertProject(PROJECT_BETA, "beta-filter-it");
  }

  @AfterEach
  void teardown() {
    cleanDatabase();
  }

  private void cleanDatabase() {
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
    jdbcTemplate.update(
        "delete from projects where public_id in (?, ?)", PROJECT_ALPHA, PROJECT_BETA);
  }

  @Test
  void narrowsToTheRequestedProjectByPublicId() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    insertRun("run_alpha_old001", "Inbox", PROJECT_ALPHA, now.minusMinutes(30), null);
    insertRun("run_alpha_new002", "Inbox", PROJECT_ALPHA, now.minusMinutes(10), null);
    insertRun("run_beta_mid0003", "Inbox", PROJECT_BETA, now.minusMinutes(20), null);

    List<String> alpha = publicIds(repository.listRunsFiltered(null, false, PROJECT_ALPHA, PAGE));

    // Only ALPHA runs, newest-first.
    assertEquals(List.of("run_alpha_new002", "run_alpha_old001"), alpha);
  }

  @Test
  void composesStateWithProjectFilter() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    insertRun("run_alpha_inbox01", "Inbox", PROJECT_ALPHA, now.minusMinutes(30), null);
    insertRun("run_alpha_exec002", "Executing", PROJECT_ALPHA, now.minusMinutes(20), null);
    insertRun("run_beta_exec0003", "Executing", PROJECT_BETA, now.minusMinutes(10), null);

    List<String> result =
        publicIds(repository.listRunsFiltered("Executing", false, PROJECT_ALPHA, PAGE));

    // Executing AND ALPHA only — the beta Executing run and the alpha Inbox run are excluded.
    assertEquals(List.of("run_alpha_exec002"), result);
  }

  @Test
  void includeArchivedComposesWithinAProject() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    insertRun("run_alpha_live001", "Inbox", PROJECT_ALPHA, now.minusMinutes(20), null);
    insertRun(
        "run_alpha_arch002", "Inbox", PROJECT_ALPHA, now.minusMinutes(10), now.minusMinutes(5));

    List<String> defaultView =
        publicIds(repository.listRunsFiltered(null, false, PROJECT_ALPHA, PAGE));
    List<String> archivedView =
        publicIds(repository.listRunsFiltered(null, true, PROJECT_ALPHA, PAGE));

    assertEquals(List.of("run_alpha_live001"), defaultView);
    // include_archived=true surfaces the archived run too, still newest-first.
    assertEquals(List.of("run_alpha_arch002", "run_alpha_live001"), archivedView);
  }

  @Test
  void ordersByCreatedAtDescThenIdDescOnTies() {
    OffsetDateTime shared =
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusMinutes(5);
    insertRun("run_tie_first0001", "Inbox", PROJECT_ALPHA, shared, null);
    insertRun("run_tie_second002", "Inbox", PROJECT_ALPHA, shared, null);

    List<String> result = publicIds(repository.listRunsFiltered(null, false, PROJECT_ALPHA, PAGE));

    // Identical created_at → the later-inserted (higher BIGSERIAL id) row wins the tiebreak.
    assertEquals(List.of("run_tie_second002", "run_tie_first0001"), result);
  }

  @Test
  void honorsThePageableLimit() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    insertRun("run_limit_old0001", "Inbox", PROJECT_ALPHA, now.minusMinutes(30), null);
    insertRun("run_limit_mid0002", "Inbox", PROJECT_ALPHA, now.minusMinutes(20), null);
    insertRun("run_limit_new0003", "Inbox", PROJECT_ALPHA, now.minusMinutes(10), null);

    List<String> result =
        publicIds(repository.listRunsFiltered(null, false, PROJECT_ALPHA, PageRequest.of(0, 2)));

    // Capped at 2, newest-first.
    assertEquals(List.of("run_limit_new0003", "run_limit_mid0002"), result);
  }

  @Test
  void noFilterDefaultPathMatchesLegacyNonArchivedDerivedQuery() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    insertRun("run_parity_a00001", "Inbox", PROJECT_ALPHA, now.minusMinutes(30), null);
    insertRun("run_parity_b00002", "Executing", PROJECT_BETA, now.minusMinutes(20), null);
    insertRun(
        "run_parity_arch003", "Inbox", PROJECT_ALPHA, now.minusMinutes(10), now.minusMinutes(5));

    List<String> rewritten = publicIds(repository.listRunsFiltered(null, false, null, PAGE));
    List<String> legacy =
        publicIds(repository.findByArchivedAtIsNullOrderByCreatedAtDescIdDesc(PAGE));

    // The default (no-project, hide-archived) queue path must be row/order-identical to the
    // derived query the rewrite replaced.
    assertEquals(legacy, rewritten);
  }

  @Test
  void noFilterIncludeArchivedPathMatchesLegacyUnfilteredDerivedQuery() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    insertRun("run_parity_c00001", "Inbox", PROJECT_ALPHA, now.minusMinutes(30), null);
    insertRun("run_parity_d00002", "Executing", PROJECT_BETA, now.minusMinutes(20), null);
    insertRun(
        "run_parity_arch004", "Inbox", PROJECT_ALPHA, now.minusMinutes(10), now.minusMinutes(5));

    List<String> rewritten = publicIds(repository.listRunsFiltered(null, true, null, PAGE));
    List<String> legacy = publicIds(repository.findAllByOrderByCreatedAtDescIdDesc(PAGE));

    // The include-archived no-project path must be row/order-identical to the legacy unfiltered
    // derived query (archived rows included).
    assertEquals(legacy, rewritten);
  }

  private void insertProject(String publicId, String slug) {
    jdbcTemplate.update(
        """
        insert into projects
          (public_id, name, slug, status, ticket_source_kind, repo_host_kind)
        values (?, ?, ?, 'active', 'linear', 'github')
        """,
        publicId,
        "Project " + slug,
        slug);
  }

  private void insertRun(
      String publicId,
      String currentState,
      String projectId,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt) {
    jdbcTemplate.update(
        """
        insert into workflow_runs (public_id, current_state, project_id, created_at, archived_at)
        values (?, ?, ?, ?, ?)
        """,
        publicId,
        currentState,
        projectId,
        createdAt,
        archivedAt);
    Long id =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, publicId);
    assertNotNull(id);
  }

  private List<String> publicIds(List<WorkflowRunEntity> entities) {
    return entities.stream().map(WorkflowRunEntity::getPublicId).toList();
  }
}
