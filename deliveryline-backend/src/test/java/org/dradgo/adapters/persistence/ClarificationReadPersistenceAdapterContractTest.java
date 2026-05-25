package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class ClarificationReadPersistenceAdapterContractTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ClarificationReadPort clarificationReadPort;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void listByWorkflowRunIdCollapsesTerminalStatesIntoOneChronologicalBucket() {
    insertRun("run_clrread1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactId = insertArtifact("run_clrread1234", "art_clrread1234", 1);
    insertClarification(
        "clr_open12345",
        "run_clrread1234",
        artifactId,
        1,
        Clarification.STATUS_OPEN,
        "2026-05-13T10:00:00Z");
    insertClarification(
        "clr_answered1",
        "run_clrread1234",
        artifactId,
        1,
        Clarification.STATUS_ANSWERED,
        "2026-05-13T10:01:00Z");
    insertClarification(
        "clr_accept123",
        "run_clrread1234",
        artifactId,
        1,
        Clarification.STATUS_ACCEPTED,
        "2026-05-13T10:02:00Z");
    insertClarification(
        "clr_rejected1",
        "run_clrread1234",
        artifactId,
        1,
        Clarification.STATUS_REJECTED_INVALID,
        "2026-05-13T10:03:00Z");
    insertClarification(
        "clr_incorp123",
        "run_clrread1234",
        artifactId,
        1,
        Clarification.STATUS_INCORPORATED,
        "2026-05-13T10:04:00Z");
    insertClarification(
        "clr_super1234",
        "run_clrread1234",
        artifactId,
        1,
        Clarification.STATUS_SUPERSEDED,
        "2026-05-13T10:05:00Z");

    List<Clarification> result = clarificationReadPort.listByWorkflowRunId("run_clrread1234");

    assertEquals(
        List.of(
            "clr_open12345",
            "clr_answered1",
            "clr_accept123",
            "clr_rejected1",
            "clr_incorp123",
            "clr_super1234"),
        result.stream().map(Clarification::publicId).toList());
  }

  @Test
  void artifactScopedReadsAndFindByPublicIdHideArchivedRows() {
    insertRun("run_clrarch1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactId = insertArtifact("run_clrarch1234", "art_clrarch1234", 1);
    insertClarification(
        "clr_visible123",
        "run_clrarch1234",
        artifactId,
        1,
        Clarification.STATUS_OPEN,
        "2026-05-13T10:00:00Z");
    insertClarification(
        "clr_hidden1234",
        "run_clrarch1234",
        artifactId,
        1,
        Clarification.STATUS_ANSWERED,
        "2026-05-13T10:01:00Z");
    jdbcTemplate.update(
        "update clarifications set archived_at = now() where public_id = ?", "clr_hidden1234");

    List<Clarification> artifactRows = clarificationReadPort.listByArtifactId("art_clrarch1234");
    Optional<Clarification> hidden = clarificationReadPort.findByPublicId("clr_hidden1234");

    assertEquals(1, artifactRows.size());
    assertEquals("clr_visible123", artifactRows.get(0).publicId());
    assertTrue(hidden.isEmpty());
  }

  private void insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
  }

  private Long insertArtifact(String workflowRunPublicId, String publicId, int version) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    String evtPublicId = "evt_clrread" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);
    return jdbcTemplate.queryForObject(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, linked_event_id) values (?, ?, 'spec', ?, null, 'shareable-redacted', 'available', ?) "
            + "returning id",
        Long.class,
        publicId,
        runId,
        version,
        linkedEventId);
  }

  private void insertClarification(
      String publicId,
      String workflowRunPublicId,
      Long artifactId,
      int artifactVersion,
      String status,
      String createdAtIso) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    boolean open = Clarification.STATUS_OPEN.equals(status);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, idempotency_key, created_at) "
            + "values (?, ?, ?, ?, 'Q1', 'What is the boundary?', ?, ?, ?, ?, ?::timestamptz, ?, ?::timestamptz)",
        publicId,
        runId,
        artifactId,
        artifactVersion,
        status,
        open ? null : "answer-" + publicId,
        open ? null : "alex",
        open ? null : "human",
        open ? null : createdAtIso,
        "idem-" + publicId,
        createdAtIso);
  }
}
