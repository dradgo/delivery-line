package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class WorkflowCommandServiceClarificationRollbackContractTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WorkflowCommandService service;
  @Autowired private ArtifactPayloadStore artifactPayloadStore;

  @MockitoBean private WorkflowEventWritePort workflowEventWritePort;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from integration_links");
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void answerClarificationRollsBackRowMutationWhenEventAppendFails() {
    insertRun("run_clrrollback1", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    seedAvailableSpecArtifact("run_clrrollback1", "art_clrrollback1");
    seedOpenClarification("run_clrrollback1", "art_clrrollback1", "clr_clrrollback1");
    doThrow(new IllegalStateException("append failed"))
        .when(workflowEventWritePort)
        .append(any(WorkflowEventRecord.class));

    assertThrows(
        IllegalStateException.class,
        () ->
            service.answerClarification(
                new SubmitClarificationCommand(
                    "run_clrrollback1",
                    "clr_clrrollback1",
                    "art_clrrollback1",
                    1,
                    "rollback me",
                    "alex",
                    ActorType.HUMAN,
                    "idem-clarification-rollback-1234567890",
                    "corr-clarification-rollback-1")));

    assertEquals(
        "open",
        jdbcTemplate.queryForObject(
            "select status from clarifications where public_id = ?",
            String.class,
            "clr_clrrollback1"));
    assertNull(
        jdbcTemplate.queryForObject(
            "select answer_text from clarifications where public_id = ?",
            String.class,
            "clr_clrrollback1"));
    assertEquals("failed", idempotencyStatus("idem-clarification-rollback-1234567890"));
  }

  private void insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
  }

  private void seedAvailableSpecArtifact(String runPublicId, String artifactPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);

    byte[] payload = ("clarification rollback content for " + artifactPublicId).getBytes();
    String storageRef =
        artifactPayloadStore.write(runPublicId, artifactPublicId, 1, "spec.md", payload);
    String evtPublicId = "evt_seed" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);

    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, storage_ref, checksum_algorithm, checksum_value, linked_event_id) "
            + "values (?, ?, 'spec', 1, null, 'shareable-redacted', 'available', ?, 'SHA-256', 'deadbeef', ?)",
        artifactPublicId,
        runId,
        storageRef,
        linkedEventId);
  }

  private void seedOpenClarification(
      String runPublicId, String artifactPublicId, String clarificationPublicId) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, runPublicId);
    Long artifactId =
        jdbcTemplate.queryForObject(
            "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, "
            + "question_id, question_text, status, idempotency_key) "
            + "values (?, ?, ?, 1, 'Q1', 'What is the boundary?', 'open', ?)",
        clarificationPublicId,
        runId,
        artifactId,
        "seed-" + clarificationPublicId);
  }

  private String idempotencyStatus(String key) {
    return jdbcTemplate.queryForObject(
        "select status from idempotency_records where key = ?", String.class, key);
  }
}
