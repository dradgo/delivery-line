package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.clarification.Clarification;
import org.dradgo.application.clarification.ClarificationLifecycleSnapshot;
import org.dradgo.application.clarification.spi.ClarificationReadPort;
import org.dradgo.application.clarification.spi.ClarificationWritePort;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkAccepted;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkIncorporated;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkRejectedInvalid;
import org.dradgo.application.clarification.spi.ClarificationWritePort.MarkSuperseded;
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
class ClarificationWritePersistenceAdapterLifecycleContractTest {

  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ClarificationWritePort clarificationWritePort;
  @Autowired private ClarificationReadPort clarificationReadPort;

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void markAcceptedPersistsAcceptedAtAndStatus() {
    insertRun("run_write_life_1", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactId = insertArtifact("run_write_life_1", "art_write_life_v1", 1);
    insertAnsweredClarification("clr_write_accept", "run_write_life_1", artifactId, NOW.minusMinutes(5));

    Clarification updated =
        clarificationWritePort.markAccepted(new MarkAccepted("clr_write_accept", NOW));
    Optional<ClarificationLifecycleSnapshot> readBack =
        clarificationReadPort.findLifecycleSnapshotByPublicId("clr_write_accept");

    assertThat(updated.status()).isEqualTo(Clarification.STATUS_ACCEPTED);
    assertThat(readBack).isPresent();
    assertThat(readBack.get().status()).isEqualTo(Clarification.STATUS_ACCEPTED);
    assertRoundTripsWithinOneMicrosecond(readBack.get().acceptedAt(), NOW);
    assertThat(readBack.get().noEffectReason()).isNull();
  }

  @Test
  void markIncorporatedResolvesWorkflowEventPublicIdToFkAndReadView() {
    insertRun("run_write_life_2", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactV1Id = insertArtifact("run_write_life_2", "art_write_life_v1", 1);
    insertArtifact("run_write_life_2", "art_write_life_v2", 2);
    insertAcceptedClarification("clr_write_inc", "run_write_life_2", artifactV1Id, NOW.minusMinutes(10));
    Long eventId =
        insertWorkflowEvent(
            "run_write_life_2",
            "evt_write_inc",
            "clarification.incorporated",
            "{\"clarificationId\":\"clr_write_inc\",\"incorporatedIntoArtifactId\":\"art_write_life_v2\"}");

    Clarification updated =
        clarificationWritePort.markIncorporated(
            new MarkIncorporated(
                "clr_write_inc", "art_write_life_v2", 2, "evt_write_inc", NOW));
    Optional<ClarificationLifecycleSnapshot> readBack =
        clarificationReadPort.findLifecycleSnapshotByPublicId("clr_write_inc");
    Long persistedEventId =
        jdbcTemplate.queryForObject(
            "select incorporation_event_id from clarifications where public_id = ?",
            Long.class,
            "clr_write_inc");

    assertThat(updated.status()).isEqualTo(Clarification.STATUS_INCORPORATED);
    assertThat(persistedEventId).isEqualTo(eventId);
    assertThat(readBack).isPresent();
    assertThat(readBack.get().incorporationEventPublicId()).isEqualTo("evt_write_inc");
    assertThat(readBack.get().incorporatedIntoArtifactPublicId()).isEqualTo("art_write_life_v2");
    assertRoundTripsWithinOneMicrosecond(readBack.get().incorporatedAt(), NOW);
  }

  @Test
  void markSupersededPersistsArtifactReferenceAndNoEffectReason() {
    insertRun("run_write_life_3", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactV1Id = insertArtifact("run_write_life_3", "art_write_life_v1", 1);
    insertArtifact("run_write_life_3", "art_write_life_v2", 2);
    insertAcceptedClarification("clr_write_super", "run_write_life_3", artifactV1Id, NOW.minusMinutes(20));

    Clarification updated =
        clarificationWritePort.markSuperseded(
            new MarkSuperseded(
                "clr_write_super",
                "art_write_life_v2",
                2,
                "clarification_not_addressed",
                NOW));
    Optional<ClarificationLifecycleSnapshot> readBack =
        clarificationReadPort.findLifecycleSnapshotByPublicId("clr_write_super");

    assertThat(updated.status()).isEqualTo(Clarification.STATUS_SUPERSEDED);
    assertThat(readBack).isPresent();
    assertThat(readBack.get().supersededByArtifactPublicId()).isEqualTo("art_write_life_v2");
    assertThat(readBack.get().supersededByArtifactVersion()).isEqualTo(2);
    assertThat(readBack.get().noEffectReason()).isEqualTo("clarification_not_addressed");
  }

  @Test
  void markRejectedInvalidPersistsNoEffectReason() {
    insertRun("run_write_life_4", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    Long artifactId = insertArtifact("run_write_life_4", "art_write_life_v1", 1);
    insertAnsweredClarification("clr_write_reject", "run_write_life_4", artifactId, NOW.minusMinutes(3));

    Clarification updated =
        clarificationWritePort.markRejectedInvalid(
            new MarkRejectedInvalid("clr_write_reject", "pm_marked_invalid", NOW));
    Optional<ClarificationLifecycleSnapshot> readBack =
        clarificationReadPort.findLifecycleSnapshotByPublicId("clr_write_reject");

    assertThat(updated.status()).isEqualTo(Clarification.STATUS_REJECTED_INVALID);
    assertThat(readBack).isPresent();
    assertThat(readBack.get().acceptedAt()).isNull();
    assertThat(readBack.get().noEffectReason()).isEqualTo("pm_marked_invalid");
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
    Long linkedEventId =
        insertWorkflowEvent(
            workflowRunPublicId,
            "evt_art_" + publicId,
            "artifact.versionCreated",
            "{\"artifactId\":\"" + publicId + "\",\"artifactVersion\":" + version + "}");
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

  private void insertAnsweredClarification(
      String publicId, String workflowRunPublicId, Long artifactId, OffsetDateTime answeredAt) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, idempotency_key, created_at) "
            + "values (?, ?, ?, 1, 'Q-WRITE', 'Question?', 'answered', 'answer', 'alex', 'human', ?, ?, ?)",
        publicId,
        runId,
        artifactId,
        answeredAt,
        "idem-" + publicId,
        answeredAt.minusMinutes(1));
  }

  private void insertAcceptedClarification(
      String publicId, String workflowRunPublicId, Long artifactId, OffsetDateTime acceptedAt) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    OffsetDateTime answeredAt = acceptedAt.minusMinutes(1);
    jdbcTemplate.update(
        "insert into clarifications (public_id, workflow_run_id, artifact_id, artifact_version, question_id, question_text, "
            + "status, answer_text, answered_by_actor, answered_by_actor_type, answered_at, accepted_at, idempotency_key, created_at) "
            + "values (?, ?, ?, 1, 'Q-WRITE', 'Question?', 'accepted', 'answer', 'alex', 'human', ?, ?, ?, ?)",
        publicId,
        runId,
        artifactId,
        answeredAt,
        acceptedAt,
        "idem-" + publicId,
        answeredAt.minusMinutes(1));
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
        canonicalJson(detailsJson));
  }

  private void assertRoundTripsWithinOneMicrosecond(
      OffsetDateTime actual, OffsetDateTime expected) {
    assertThat(Duration.between(expected, actual).abs())
        .isLessThanOrEqualTo(Duration.ofNanos(1_000));
  }

  private String canonicalJson(String raw) {
    try {
      return objectMapper.readTree(raw).toString();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Invalid JSON fixture", error);
    }
  }
}
