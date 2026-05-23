package org.dradgo.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.approval.ApprovalSnapshot;
import org.dradgo.application.approval.spi.ApprovalReadPort;
import org.dradgo.application.approval.spi.ApprovalWritePort;
import org.dradgo.application.approval.spi.ApprovalWritePort.NewApproval;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-Postgres regression for {@link ApprovalWritePersistenceAdapter} (story 2.9 Task 3). Mirrors
 * the {@code ApprovalReadPersistenceAdapterTest} setup. Three cases:
 *
 * <ul>
 *   <li>happy-path insert + read-back via {@link
 *       ApprovalReadPort#findLatestApprovedForArtifactLineage};
 *   <li>duplicate idempotency-key insert → {@code IDEMPOTENCY_KEY_CONFLICT} (defense-in-depth
 *       backstop on the {@code uq_approvals_idempotency_key} UNIQUE constraint, trap T5);
 *   <li>missing artifact FK target → typed {@code ARTIFACT_RECORD_NOT_FOUND}.
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
class ApprovalWritePersistenceAdapterTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApprovalWritePort approvalWritePort;
  @Autowired private ApprovalReadPort approvalReadPort;

  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachLogAppender() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(ApprovalWritePersistenceAdapter.class)).addAppender(appender);
  }

  @AfterEach
  void detachLogAppender() {
    ((Logger) LoggerFactory.getLogger(ApprovalWritePersistenceAdapter.class))
        .detachAppender(appender);
  }

  @AfterEach
  void cleanDatabase() {
    jdbcTemplate.update("delete from approvals");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from workflow_runs");
  }

  @Test
  void happyPathInsertIsReadableThroughApprovalReadPort() {
    insertRun("run_writeapp_1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    insertArtifact("run_writeapp_1234", "art_writeapp_v1", ArtifactType.SPEC, 1);

    ApprovalSnapshot persisted =
        approvalWritePort.insert(
            new NewApproval(
                "apr_writeapp_001",
                "run_writeapp_1234",
                "art_writeapp_v1",
                1,
                1,
                "alex",
                ActorType.HUMAN,
                "product_reviewer",
                ApprovalSnapshot.DECISION_APPROVED,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                "idem-write-1234567890"));

    assertThat(persisted.publicId()).isEqualTo("apr_writeapp_001");
    assertThat(persisted.workflowRunId()).isEqualTo("run_writeapp_1234");
    assertThat(persisted.artifactId()).isEqualTo("art_writeapp_v1");
    assertThat(persisted.artifactVersion()).isEqualTo(1);
    assertThat(persisted.reviewerRole()).isEqualTo("product_reviewer");
    assertThat(persisted.decision()).isEqualTo(ApprovalSnapshot.DECISION_APPROVED);
    assertThat(persisted.rejectionTaxonomy()).isNull();

    Optional<ApprovalSnapshot> readBack =
        approvalReadPort.findLatestApprovedForArtifactLineage(
            "run_writeapp_1234", ArtifactType.SPEC.value());
    assertThat(readBack).isPresent();
    assertThat(readBack.get().publicId()).isEqualTo("apr_writeapp_001");
    assertThat(readBack.get().reviewerRole()).isEqualTo("product_reviewer");
  }

  @Test
  void duplicateIdempotencyKeyMapsToTypedConflict() {
    insertRun("run_writedup_1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    insertArtifact("run_writedup_1234", "art_writedup_v1", ArtifactType.SPEC, 1);

    String sharedKey = "idem-writedup-1234567890";
    approvalWritePort.insert(
        new NewApproval(
            "apr_writedup_001",
            "run_writedup_1234",
            "art_writedup_v1",
            1,
            1,
            "alex",
            ActorType.HUMAN,
            "product_reviewer",
            ApprovalSnapshot.DECISION_APPROVED,
            null,
            null,
            OffsetDateTime.now(ZoneOffset.UTC),
            sharedKey));

    assertThatThrownBy(
            () ->
                approvalWritePort.insert(
                    new NewApproval(
                        "apr_writedup_002",
                        "run_writedup_1234",
                        "art_writedup_v1",
                        1,
                        1,
                        "alex",
                        ActorType.HUMAN,
                        "product_reviewer",
                        ApprovalSnapshot.DECISION_APPROVED,
                        null,
                        null,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        sharedKey)))
        .isInstanceOf(DomainException.class)
        .satisfies(
            error -> {
              DomainException domainError = (DomainException) error;
              assertThat(domainError.errorCode())
                  .isEqualTo(DomainErrorCode.IDEMPOTENCY_KEY_CONFLICT);
              // Review batch 1 P9: idempotency-key MUST NOT be echoed in the Problem Details
              // body — it's a caller-private session token. Server-side logs (asserted below)
              // carry it under structured MDC for forensic correlation.
              assertThat(domainError.details())
                  .containsEntry("source", "db_unique_constraint")
                  .containsEntry("conflictDetected", true)
                  .doesNotContainKey("idempotencyKey");
            });

    // Review batch 1 P10: pin the WARN log line. Spec line 239 requires
    // "WARN on the DB-level uq_approvals_idempotency_key backstop (source=db_unique_constraint)."
    boolean warnPresent =
        appender.list.stream()
            .anyMatch(
                event ->
                    event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("idempotency-key conflict")
                        && event.getFormattedMessage().contains("source=db_unique_constraint"));
    assertThat(warnPresent)
        .as("expected WARN log line pinning the DB-unique-constraint backstop")
        .isTrue();
  }

  @Test
  void missingArtifactFkRaisesTypedNotFound() {
    insertRun("run_writemissart_1234", WorkflowState.WAITING_FOR_SPEC_APPROVAL);
    // No artifact insert.

    assertThatThrownBy(
            () ->
                approvalWritePort.insert(
                    new NewApproval(
                        "apr_writemissart_001",
                        "run_writemissart_1234",
                        "art_doesnotexist",
                        1,
                        1,
                        "alex",
                        ActorType.HUMAN,
                        "product_reviewer",
                        ApprovalSnapshot.DECISION_APPROVED,
                        null,
                        null,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        "idem-writemissart-1234567890")))
        .isInstanceOf(DomainException.class)
        .satisfies(
            error ->
                assertThat(((DomainException) error).errorCode())
                    .isEqualTo(DomainErrorCode.ARTIFACT_RECORD_NOT_FOUND));
  }

  // ---------------------------------------------------------------------------
  // JDBC seed helpers — direct inserts (mirror ApprovalReadPersistenceAdapterTest).
  // ---------------------------------------------------------------------------

  private void insertRun(String publicId, WorkflowState state) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)",
        publicId,
        state.value());
  }

  private void insertArtifact(
      String workflowRunPublicId, String artifactPublicId, ArtifactType type, int version) {
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, workflowRunPublicId);
    String evtPublicId = "evt_seed_" + System.nanoTime();
    Long linkedEventId =
        jdbcTemplate.queryForObject(
            "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity, actor_type) "
                + "values (?, ?, 'artifact.draftCreated', 'seed', 'system') returning id",
            Long.class,
            evtPublicId,
            runId);
    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version, parent_artifact_id, "
            + "classification, status, linked_event_id) values (?, ?, ?, ?, null, ?, 'available', ?)",
        artifactPublicId,
        runId,
        type.value(),
        version,
        type.defaultClassification().value(),
        linkedEventId);
  }
}
