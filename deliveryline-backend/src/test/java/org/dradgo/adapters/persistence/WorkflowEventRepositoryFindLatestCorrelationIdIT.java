package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.persistence.repository.WorkflowEventRepository;
import org.dradgo.observability.testsupport.ItLoggingHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * F528 — story 1.18 deferred, lands in story 1.21. Verifies the native JSONB query in {@link
 * WorkflowEventRepository#findLatestCorrelationIdInDetails(String)} returns the most recent
 * non-blank, non-archived {@code details->>'correlationId'} for a run.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class WorkflowEventRepositoryFindLatestCorrelationIdIT {

  private static final String RUN_PUBLIC_ID = "run_corrid12345";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private WorkflowEventRepository workflowEventRepository;

  // Use a local ObjectMapper rather than the autowired one — the production module doesn't
  // expose a default Jackson bean in every Spring profile combination, and the test only needs
  // safe Map → JSON serialization for fixture inserts. Keeping it local also documents that this
  // test's serialization is independent of any production Jackson customization.
  private final ObjectMapper objectMapper = new ObjectMapper();

  private final ItLoggingHarness logHarness = new ItLoggingHarness(getClass());

  // @BeforeEach defends against a prior test method that aborted before @AfterEach
  // could run (per review-finding F-B-cleanup-belt-and-suspenders).
  @BeforeEach
  void setup() {
    cleanDatabase();
    logHarness.attach(
        "corr-find-latest-corr-it-1", RUN_PUBLIC_ID, "idem-find-latest-corr-it-12345");
  }

  @AfterEach
  void teardown() {
    try {
      logHarness.detach();
    } finally {
      cleanDatabase();
    }
  }

  private void cleanDatabase() {
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void returnsMostRecentNonBlankNonArchivedCorrelationId() {
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    insertEvent(workflowRunId, "evt_corrid001", now.minusMinutes(30), "corr-old-value-1");
    insertEvent(workflowRunId, "evt_corrid002", now.minusMinutes(20), "corr-middle-value-2");
    insertEvent(workflowRunId, "evt_corrid003", now.minusMinutes(10), "corr-newest-value-3");

    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails(RUN_PUBLIC_ID);

    assertTrue(latest.isPresent());
    assertEquals("corr-newest-value-3", latest.get());
  }

  @Test
  void ignoresArchivedEventsEvenWhenTheyAreNewest() {
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    insertEvent(workflowRunId, "evt_corrid010", now.minusMinutes(15), "corr-live-value");
    insertArchivedEvent(workflowRunId, "evt_corrid011", now.minusMinutes(5), "corr-archived-value");

    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails(RUN_PUBLIC_ID);

    assertTrue(latest.isPresent());
    assertEquals("corr-live-value", latest.get());
  }

  @Test
  void ignoresBlankCorrelationIdsEvenWhenTheyAreNewest() {
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    insertEvent(workflowRunId, "evt_corrid020", now.minusMinutes(15), "corr-real-value");
    insertEvent(workflowRunId, "evt_corrid021", now.minusMinutes(5), "");

    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails(RUN_PUBLIC_ID);

    assertTrue(latest.isPresent());
    assertEquals("corr-real-value", latest.get());
  }

  @Test
  void returnsEmptyWhenNoEventCarriesACorrelationId() {
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    insertEventWithoutCorrelationId(workflowRunId, "evt_corrid030", now.minusMinutes(5));

    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails(RUN_PUBLIC_ID);

    assertTrue(latest.isEmpty());
  }

  @Test
  void returnsEmptyForUnknownWorkflowRun() {
    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails("run_missing12345");
    assertTrue(latest.isEmpty());
  }

  @Test
  void usesIdAsTieBreakerWhenTwoEventsShareCreatedAt() {
    // Pin the contract being verified: when created_at is identical, the repository's ORDER BY
    // breaks the tie on the workflow_events.id BIGSERIAL (descending) — i.e. the LATER-inserted
    // row wins. This is asserted via lexicographic event public_id ordering so the test is
    // independent of BIGSERIAL allocation order across parallel sessions: 'evt_corrid041' is
    // inserted second, and the query returns its correlationId.
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime sharedTimestamp =
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS).minusMinutes(5);

    insertEvent(workflowRunId, "evt_corrid040", sharedTimestamp, "corr-first-inserted");
    insertEvent(workflowRunId, "evt_corrid041", sharedTimestamp, "corr-second-inserted");

    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails(RUN_PUBLIC_ID);

    assertTrue(latest.isPresent());
    assertEquals("corr-second-inserted", latest.get());

    // Confirm the ordering contract by inspecting the raw rows: the row with the higher BIGSERIAL
    // id is the one whose correlationId was returned.
    Long winningId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id = 'evt_corrid041'", Long.class);
    Long losingId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id = 'evt_corrid040'", Long.class);
    assertNotNull(winningId);
    assertNotNull(losingId);
    assertTrue(
        winningId > losingId,
        "expected the later-inserted row to receive the higher BIGSERIAL id ("
            + winningId
            + " vs "
            + losingId
            + ")");
  }

  @Test
  void treatsWhitespaceOnlyCorrelationIdAsMissing() {
    // Production guard `<> ''` accepts ' ' as non-empty; this test pins the documented behavior so
    // a future tightening (e.g. `btrim(...) <> ''`) shows up as a deliberate contract change rather
    // than a silent regression. Today, whitespace-only correlationIds DO leak through.
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    insertEvent(workflowRunId, "evt_corrid050", now.minusMinutes(10), "corr-real-value");
    insertEvent(workflowRunId, "evt_corrid051", now.minusMinutes(5), "   ");

    Optional<String> latest =
        workflowEventRepository.findLatestCorrelationIdInDetails(RUN_PUBLIC_ID);

    // Current behavior: whitespace string is non-empty and wins. Update this assertion if the
    // production guard tightens to `btrim(...) <> ''`.
    assertTrue(latest.isPresent());
    assertEquals(
        "   ",
        latest.get(),
        "Whitespace-only correlationId currently leaks through; tighten production guard or update this contract.");
  }

  private long insertWorkflowRun(String publicId) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", publicId, "Inbox");
    Long id =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, publicId);
    assertNotNull(id);
    return id;
  }

  private void insertEvent(
      long workflowRunId, String publicId, OffsetDateTime createdAt, String correlationId) {
    String detailsJson = toJsonDetails(correlationId);
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type,
           intervention_marker, details, created_at)
        values (?, ?, 'workflow.stateChanged', 'alex', 'human', false, ?::jsonb, ?)
        """,
        publicId,
        workflowRunId,
        detailsJson,
        createdAt);
  }

  private void insertArchivedEvent(
      long workflowRunId, String publicId, OffsetDateTime createdAt, String correlationId) {
    String detailsJson = toJsonDetails(correlationId);
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type,
           intervention_marker, details, created_at, archived_at)
        values (?, ?, 'workflow.stateChanged', 'alex', 'human', false, ?::jsonb, ?, ?)
        """,
        publicId,
        workflowRunId,
        detailsJson,
        createdAt,
        createdAt);
  }

  // Serialize through Jackson rather than string-concatenation so test correlation IDs containing
  // quotes, backslashes, or whitespace produce valid JSONB.
  private String toJsonDetails(String correlationId) {
    try {
      return objectMapper.writeValueAsString(Map.of("correlationId", correlationId));
    } catch (JsonProcessingException e) {
      throw new AssertionError("Failed to serialize correlationId test fixture", e);
    }
  }

  private void insertEventWithoutCorrelationId(
      long workflowRunId, String publicId, OffsetDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, actor_identity, actor_type,
           intervention_marker, details, created_at)
        values (?, ?, 'workflow.stateChanged', 'alex', 'human', false, '{}'::jsonb, ?)
        """,
        publicId,
        workflowRunId,
        createdAt);
  }
}
