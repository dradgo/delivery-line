package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
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

  private final ObjectMapper objectMapper = new ObjectMapper();

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
    insertAcceptedClarification(
        "clr_accept123", "run_clrread1234", artifactId, "2026-05-13T10:02:00Z");
    insertRejectedInvalidClarification(
        "clr_rejected1", "run_clrread1234", artifactId, "2026-05-13T10:03:00Z");
    insertIncorporatedClarification(
        "clr_incorp123", "run_clrread1234", artifactId, "2026-05-13T10:04:00Z");
    insertSupersededClarification(
        "clr_super1234", "run_clrread1234", artifactId, artifactId, "2026-05-13T10:05:00Z");

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

  @Test
  void findLifecycleSnapshotByPublicIdProjectsV9LifecycleFields() {
    insertRun("run_clrlife1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactV1Id = insertArtifact("run_clrlife1234", "art_clrlife_v1", 1);
    Long artifactV2Id = insertArtifact("run_clrlife1234", "art_clrlife_v2", 2);
    Long incorporatedEventId =
        insertWorkflowEvent(
            "run_clrlife1234",
            "evt_clrlife_inc",
            "clarification.incorporated",
            "{\"clarificationId\":\"clr_life_inc\",\"incorporatedIntoArtifactId\":\"art_clrlife_v2\"}");
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, accepted_at, incorporated_at, incorporation_event_id, idempotency_key, created_at) "
            + "values (?, (select id from workflow_runs where public_id = ?), ?, 1, 'Q-LIFE-1', 'How was it incorporated?', "
            + "'incorporated', 'answer-inc', 'alex', 'human', '2026-05-25T09:00:00Z'::timestamptz, "
            + "'2026-05-25T09:05:00Z'::timestamptz, '2026-05-25T09:10:00Z'::timestamptz, ?, ?, '2026-05-25T08:55:00Z'::timestamptz)",
        "clr_life_inc",
        "run_clrlife1234",
        artifactV1Id,
        incorporatedEventId,
        "idem-life-inc");
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, accepted_at, superseded_by_artifact_id, superseded_by_artifact_version, no_effect_reason, idempotency_key, created_at) "
            + "values (?, (select id from workflow_runs where public_id = ?), ?, 1, 'Q-LIFE-2', 'Why was it superseded?', "
            + "'superseded', 'answer-super', 'alex', 'human', '2026-05-25T10:00:00Z'::timestamptz, "
            + "'2026-05-25T10:05:00Z'::timestamptz, ?, 2, 'clarification_not_addressed', ?, '2026-05-25T09:55:00Z'::timestamptz)",
        "clr_life_super",
        "run_clrlife1234",
        artifactV1Id,
        artifactV2Id,
        "idem-life-super");

    Optional<ClarificationLifecycleSnapshot> incorporated =
        clarificationReadPort.findLifecycleSnapshotByPublicId("clr_life_inc");
    Optional<ClarificationLifecycleSnapshot> superseded =
        clarificationReadPort.findLifecycleSnapshotByPublicId("clr_life_super");

    assertTrue(incorporated.isPresent());
    assertEquals("incorporated", incorporated.get().status());
    assertEquals("evt_clrlife_inc", incorporated.get().incorporationEventPublicId());
    assertEquals("art_clrlife_v2", incorporated.get().incorporatedIntoArtifactPublicId());
    assertTrue(superseded.isPresent());
    assertEquals("superseded", superseded.get().status());
    assertEquals("art_clrlife_v2", superseded.get().supersededByArtifactPublicId());
    assertEquals(Integer.valueOf(2), superseded.get().supersededByArtifactVersion());
    assertEquals("clarification_not_addressed", superseded.get().noEffectReason());
  }

  @Test
  void countPendingByWorkflowRunExcludesArchivedAndTerminalRows() {
    insertRun("run_clrcount123", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactId = insertArtifact("run_clrcount123", "art_clrcount123", 1);
    insertClarification(
        "clr_pending_open",
        "run_clrcount123",
        artifactId,
        1,
        Clarification.STATUS_OPEN,
        "2026-05-25T10:00:00Z");
    insertClarification(
        "clr_pending_ans",
        "run_clrcount123",
        artifactId,
        1,
        Clarification.STATUS_ANSWERED,
        "2026-05-25T10:01:00Z");
    insertAcceptedClarification(
        "clr_pending_acc", "run_clrcount123", artifactId, "2026-05-25T10:02:00Z");
    insertSupersededClarification(
        "clr_pending_sup", "run_clrcount123", artifactId, artifactId, "2026-05-25T10:03:00Z");
    insertRejectedInvalidClarification(
        "clr_terminal_rej", "run_clrcount123", artifactId, "2026-05-25T10:04:00Z");
    insertIncorporatedClarification(
        "clr_terminal_inc", "run_clrcount123", artifactId, "2026-05-25T10:05:00Z");
    insertClarification(
        "clr_archived_open",
        "run_clrcount123",
        artifactId,
        1,
        Clarification.STATUS_OPEN,
        "2026-05-25T10:06:00Z");
    jdbcTemplate.update(
        "update clarifications set archived_at = now() where public_id = ?", "clr_archived_open");

    int pending = clarificationReadPort.countPendingByWorkflowRun("run_clrcount123");

    assertEquals(4, pending);
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

  private void insertAcceptedClarification(
      String publicId, String workflowRunPublicId, Long artifactId, String createdAtIso) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, accepted_at, idempotency_key, created_at) "
            + "values (?, ?, ?, 1, 'Q-ACC', 'Accepted question?', 'accepted', 'answer', 'alex', 'human', "
            + "?::timestamptz, ?::timestamptz, ?, ?::timestamptz)",
        publicId,
        runId,
        artifactId,
        createdAtIso,
        createdAtIso,
        "idem-" + publicId,
        createdAtIso);
  }

  private void insertSupersededClarification(
      String publicId,
      String workflowRunPublicId,
      Long artifactId,
      Long supersededByArtifactId,
      String createdAtIso) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, accepted_at, "
            + "superseded_by_artifact_id, superseded_by_artifact_version, no_effect_reason, idempotency_key, created_at) "
            + "values (?, ?, ?, 1, 'Q-SUP', 'Superseded question?', 'superseded', 'answer', 'alex', 'human', "
            + "?::timestamptz, ?::timestamptz, ?, 1, 'clarification_not_addressed', ?, ?::timestamptz)",
        publicId,
        runId,
        artifactId,
        createdAtIso,
        createdAtIso,
        supersededByArtifactId,
        "idem-" + publicId,
        createdAtIso);
  }

  private void insertRejectedInvalidClarification(
      String publicId, String workflowRunPublicId, Long artifactId, String createdAtIso) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, no_effect_reason, idempotency_key, created_at) "
            + "values (?, ?, ?, 1, 'Q-REJ', 'Rejected question?', 'rejected_invalid', 'answer', 'alex', 'human', "
            + "?::timestamptz, 'pm_marked_invalid', ?, ?::timestamptz)",
        publicId,
        runId,
        artifactId,
        createdAtIso,
        "idem-" + publicId,
        createdAtIso);
  }

  private void insertIncorporatedClarification(
      String publicId, String workflowRunPublicId, Long artifactId, String createdAtIso) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    Long eventId =
        insertWorkflowEvent(
            workflowRunPublicId,
            "evt_" + publicId,
            "clarification.incorporated",
            "{\"clarificationId\":\""
                + publicId
                + "\",\"incorporatedIntoArtifactId\":\"art_clrcount123\"}");
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, accepted_at, incorporated_at, incorporation_event_id, idempotency_key, created_at) "
            + "values (?, ?, ?, 1, 'Q-INC', 'Incorporated question?', 'incorporated', 'answer', 'alex', 'human', "
            + "?::timestamptz, ?::timestamptz, ?::timestamptz, ?, ?, ?::timestamptz)",
        publicId,
        runId,
        artifactId,
        createdAtIso,
        createdAtIso,
        createdAtIso,
        eventId,
        "idem-" + publicId,
        createdAtIso);
  }

  private Long insertWorkflowEvent(
      String workflowRunPublicId, String publicId, String eventType, String detailsJson) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    return jdbcTemplate.queryForObject(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type, details) "
            + "values (?, ?, ?, 'seed', 'system', ?::jsonb) returning id",
        Long.class,
        publicId,
        runId,
        eventType,
        json(detailsJson));
  }

  private String json(String raw) {
    try {
      return objectMapper.readTree(raw).toString();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Invalid JSON test fixture", error);
    }
  }
}
