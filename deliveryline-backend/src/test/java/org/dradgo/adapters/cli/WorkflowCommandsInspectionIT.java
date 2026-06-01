package org.dradgo.adapters.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {"spring.shell.interactive.enabled=false", "spring.shell.script.enabled=false"})
@ActiveProfiles({"test", "linear-mock"})
class WorkflowCommandsInspectionIT {

  private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-05-13T10:00:00Z");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private WorkflowCommands commands;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void statusAndHistoryIgnoreArchivedDatabaseRows() throws Exception {
    String runId = insertRun("run_archivedinspect12345", WorkflowState.EXECUTING);
    insertEvent(
        "evt_active12345",
        runId,
        WorkflowEventType.WORKFLOW_STATE_CHANGED,
        WorkflowState.INBOX,
        WorkflowState.EXECUTING,
        "alex",
        BASE_TIME,
        null);
    insertEvent(
        "evt_archived12345",
        runId,
        WorkflowEventType.WORKFLOW_STATE_CHANGED,
        WorkflowState.EXECUTING,
        WorkflowState.FAILED,
        "archived-bot",
        BASE_TIME.plusSeconds(30),
        BASE_TIME.plusMinutes(1));

    JsonNode status =
        objectMapper.readTree(
            commands.status(runId, "json", "corr-archived-status", false, false, false));
    JsonNode history =
        objectMapper.readTree(
            commands.history(runId, "json", null, "corr-archived-history", false));

    assertEquals("alex", status.get("currentActor").get("identity").asText());
    // Story 1.21 — compare as OffsetDateTime, not toString(); Jackson emits the explicit
    // ":00" seconds field while OffsetDateTime.toString() omits zero seconds.
    assertEquals(
        BASE_TIME, OffsetDateTime.parse(status.get("lastEvent").get("createdAt").asText()));
    assertEquals(1, history.get("events").size());
    assertEquals("evt_active12345", history.get("events").get(0).get("publicId").asText());
  }

  @Test
  void statusAndHistoryMeetPilotPerformanceTargetsWithOneHundredPersistedEvents() throws Exception {
    String runId = insertRun("run_perfinspect12345", WorkflowState.EXECUTING);
    for (int i = 0; i < 100; i++) {
      insertEvent(
          String.format("evt_perf%05d", i),
          runId,
          WorkflowEventType.WORKFLOW_STATE_CHANGED,
          WorkflowState.PLANNED,
          WorkflowState.EXECUTING,
          "alex",
          BASE_TIME.plusSeconds(i),
          null);
    }

    assertTrue(historyIndexExists(), "Expected workflow_events history index to exist");

    long statusStart = System.nanoTime();
    String statusJson = commands.status(runId, "json", "corr-perf-status", false, false, false);
    long statusElapsedMs = elapsedMs(statusStart);

    long historyStart = System.nanoTime();
    String historyJson = commands.history(runId, "json", null, "corr-perf-history", false);
    long historyElapsedMs = elapsedMs(historyStart);

    JsonNode status = objectMapper.readTree(statusJson);
    JsonNode history = objectMapper.readTree(historyJson);

    assertEquals(runId, status.get("workflowRunId").asText());
    assertEquals(100, history.get("events").size());
    assertTrue(
        statusElapsedMs < 2_000, () -> "status exceeded 2s target: " + statusElapsedMs + "ms");
    assertTrue(
        historyElapsedMs < 5_000, () -> "history exceeded 5s target: " + historyElapsedMs + "ms");
  }

  private String insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
    return publicId;
  }

  private void insertEvent(
      String publicId,
      String runPublicId,
      WorkflowEventType eventType,
      WorkflowState priorState,
      WorkflowState resultingState,
      String actorIdentity,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt)
      throws JsonProcessingException {
    jdbcTemplate.update(
        """
				insert into workflow_events (
					public_id,
					workflow_run_id,
					event_type,
					prior_state,
					resulting_state,
					actor_identity,
					actor_type,
					reason,
					intervention_marker,
					details,
					created_at,
					archived_at
				) values (
					?,
					(select id from workflow_runs where public_id = ?),
					?,
					?,
					?,
					?,
					?,
					?,
					false,
					cast(? as jsonb),
					?,
					?
				)
				""",
        publicId,
        runPublicId,
        eventType.value(),
        priorState == null ? null : priorState.value(),
        resultingState == null ? null : resultingState.value(),
        actorIdentity,
        ActorType.HUMAN.value(),
        "seeded",
        objectMapper.writeValueAsString(Map.of("correlationId", "corr-seeded")),
        createdAt,
        archivedAt);
  }

  private boolean historyIndexExists() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
				select count(*)
				from pg_indexes
				where schemaname = 'public'
				  and indexname = 'idx_workflow_events_workflow_run_id_created_at'
				""",
            Integer.class);
    return count != null && count == 1;
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
