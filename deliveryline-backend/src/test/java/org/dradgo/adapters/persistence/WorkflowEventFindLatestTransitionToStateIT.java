package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.domain.registry.WorkflowState;
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
 * Story 4.8 review — pins the {@code eventType} filter on {@link
 * WorkflowEventReadPort#findLatestTransitionToState(String, WorkflowState)}. The {@code
 * recovery.paused} audit anchor (4.8) also stamps typed {@code prior_state}/{@code
 * resulting_state='Paused'} and is appended AFTER the transition event in the same tx; without the
 * filter the latest-row read would return the recovery event, de-facto repointing resume's
 * priorState derivation off the {@code workflow.stateChanged} row (Reconciliation 6 binds resume to
 * the transition event and forbids repointing).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class WorkflowEventFindLatestTransitionToStateIT {

  private static final String RUN_PUBLIC_ID = "run_evtfilter123";

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private WorkflowEventReadPort workflowEventReadPort;

  private final ItLoggingHarness logHarness = new ItLoggingHarness(getClass());

  @BeforeEach
  void setup() {
    cleanDatabase();
    logHarness.attach("corr-evt-filter-it-1", RUN_PUBLIC_ID, "idem-evt-filter-it-1234567890");
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
  void returnsTheTransitionEventNotTheLaterRecoveryAuditAnchor() {
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    // The pause transition event (the row resume MUST read) …
    insertEvent(
        workflowRunId,
        "evt_trans-paused1",
        "workflow.stateChanged",
        "WaitingForReview",
        "Paused",
        now.minusSeconds(2));
    // … followed by the recovery.paused audit anchor, appended LATER in the same pause tx and —
    // in the TOCTOU race this pin exists for — carrying a DIFFERENT (stale) priorState.
    insertEvent(
        workflowRunId,
        "evt_rcv-paused1",
        "recovery.paused",
        "Executing",
        "Paused",
        now.minusSeconds(1));

    Optional<WorkflowEventRecord> latest =
        workflowEventReadPort.findLatestTransitionToState(RUN_PUBLIC_ID, WorkflowState.PAUSED);

    assertTrue(latest.isPresent());
    assertEquals("evt_trans-paused1", latest.get().publicId());
    assertEquals(WorkflowState.WAITING_FOR_REVIEW, latest.get().priorState());
  }

  @Test
  void amongTransitionEventsTheLatestStillWins() {
    long workflowRunId = insertWorkflowRun(RUN_PUBLIC_ID);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

    insertEvent(
        workflowRunId,
        "evt_trans-old1",
        "workflow.stateChanged",
        "Executing",
        "Paused",
        now.minusMinutes(10));
    insertEvent(
        workflowRunId,
        "evt_trans-new1",
        "workflow.stateChanged",
        "Failed",
        "Paused",
        now.minusMinutes(1));

    Optional<WorkflowEventRecord> latest =
        workflowEventReadPort.findLatestTransitionToState(RUN_PUBLIC_ID, WorkflowState.PAUSED);

    assertTrue(latest.isPresent());
    assertEquals("evt_trans-new1", latest.get().publicId());
    assertEquals(WorkflowState.FAILED, latest.get().priorState());
  }

  private long insertWorkflowRun(String publicId) {
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", publicId, "Paused");
    Long id =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, publicId);
    assertNotNull(id);
    return id;
  }

  private void insertEvent(
      long workflowRunId,
      String publicId,
      String eventType,
      String priorState,
      String resultingState,
      OffsetDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into workflow_events
          (public_id, workflow_run_id, event_type, prior_state, resulting_state, actor_identity,
           actor_type, intervention_marker, details, created_at)
        values (?, ?, ?, ?, ?, 'alex', 'human', false, '{}'::jsonb, ?)
        """,
        publicId,
        workflowRunId,
        eventType,
        priorState,
        resultingState,
        createdAt);
  }
}
