package org.dradgo.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Story 2.14 — service-side logging contract pin for {@link
 * WorkflowInspectionService#getAllowedActions(String, String)}. Locks the entry INFO + success INFO
 * shapes and the WARN-on-UNKNOWN_ACTOR_ROLE rejection shape so that an operator log scrape can (a)
 * correlate a 400-shaped rejection back to the actorRole that triggered it and (b) trace a
 * 200-shaped success back to the chosen action count + last-event id for cache-staleness
 * diagnostics.
 */
class WorkflowInspectionServiceAllowedActionsLoggingTest {

  private static final String RUN = "run_logging_aa";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-27T13:00:00Z");

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final ArtifactRecordPort artifacts = mock(ArtifactRecordPort.class);
  private final org.dradgo.application.approval.spi.ApprovalReadPort approvals =
      mock(org.dradgo.application.approval.spi.ApprovalReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final RunnerExecutionRecordPort runnerExecutions = mock(RunnerExecutionRecordPort.class);
  private final RunnerScratchStore scratchStore = mock(RunnerScratchStore.class);
  private final org.dradgo.application.clarification.spi.ClarificationReadPort clarifications =
      mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class);

  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          approvals,
          links,
          new RedactionPolicyService(new DataClassificationService()),
          recovery,
          runnerExecutions,
          scratchStore,
          clarifications);

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void attachAppender() {
    appender = new ListAppender<>();
    appender.start();
    logger = (Logger) LoggerFactory.getLogger(WorkflowInspectionService.class);
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void successfulReadEmitsInfoEntryAndSuccessLogs() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.INBOX, null, 1L, 0, false)));
    when(clarifications.countPendingByWorkflowRun(RUN)).thenReturn(0);
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.SPEC.value()))
        .thenReturn(Optional.empty());
    when(events.findLatestByWorkflowRunPublicId(RUN))
        .thenReturn(
            Optional.of(
                new org.dradgo.application.workflow.spi.WorkflowEventRecord(
                    "evt_log_a",
                    RUN,
                    org.dradgo.domain.registry.WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    null,
                    null,
                    "system",
                    org.dradgo.domain.registry.ActorType.SYSTEM,
                    "state",
                    null,
                    false,
                    NOW,
                    java.util.Map.of())));

    service.getAllowedActions(RUN, "workflow_owner");

    assertThat(appender.list)
        .filteredOn(e -> e.getLevel() == Level.INFO)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(
            line ->
                line.contains("getAllowedActions entry")
                    && line.contains("workflowRunId=" + RUN)
                    && line.contains("actorRole=workflow_owner"))
        .anyMatch(
            line ->
                line.contains("getAllowedActions success")
                    && line.contains("workflowRunId=" + RUN)
                    && line.contains("actorRole=workflow_owner")
                    && line.contains("workflowState=Inbox")
                    && line.contains("actionCount=1")
                    && line.contains("versionStampLastEventId=evt_log_a"));
  }

  @Test
  void unknownActorRoleEmitsWarnWithRejectionContext() {
    // Mirror of the clarificationNotFound WARN shape: log code + workflowRunId + actorRole so
    // an operator can correlate the rejection back to the call site without spelunking the
    // application logs for the corresponding 400 ProblemDetails body.
    assertThatThrownBy(() -> service.getAllowedActions(RUN, "auditor"))
        .isInstanceOf(DomainException.class)
        .extracting(ex -> ((DomainException) ex).errorCode())
        .isEqualTo(DomainErrorCode.UNKNOWN_ACTOR_ROLE);

    assertThat(appender.list)
        .filteredOn(e -> e.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(
            line ->
                line.contains("getAllowedActions rejected UNKNOWN_ACTOR_ROLE")
                    && line.contains("workflowRunId=" + RUN)
                    && line.contains("actorRole=auditor"));
  }
}
