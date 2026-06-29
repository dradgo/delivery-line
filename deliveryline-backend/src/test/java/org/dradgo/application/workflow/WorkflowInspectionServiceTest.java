package org.dradgo.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.recovery.FailureDescription;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventStreamView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.application.workflow.spi.SplitProposalReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventReadPort;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Test;

class WorkflowInspectionServiceTest {

  private static final String RUN = "run_inspect12345";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-13T10:00:00Z");

  private final WorkflowRunReadPort runs = mock(WorkflowRunReadPort.class);
  private final WorkflowEventReadPort events = mock(WorkflowEventReadPort.class);
  private final ArtifactRecordPort artifacts = mock(ArtifactRecordPort.class);
  private final org.dradgo.application.artifact.spi.ArtifactPayloadStore payloadStore =
      mock(org.dradgo.application.artifact.spi.ArtifactPayloadStore.class);
  private final org.dradgo.application.approval.spi.ApprovalReadPort approvals =
      mock(org.dradgo.application.approval.spi.ApprovalReadPort.class);
  private final IntegrationLinkService links = mock(IntegrationLinkService.class);
  private final RedactionPolicyService redaction =
      new RedactionPolicyService(new DataClassificationService());
  private final RecoveryService recovery = mock(RecoveryService.class);
  private final org.dradgo.application.runner.spi.RunnerExecutionRecordPort runnerExecutions =
      mock(org.dradgo.application.runner.spi.RunnerExecutionRecordPort.class);
  private final org.dradgo.application.runner.spi.RunnerScratchStore scratchStore =
      mock(org.dradgo.application.runner.spi.RunnerScratchStore.class);
  private final org.dradgo.application.clarification.spi.ClarificationReadPort clarifications =
      mock(org.dradgo.application.clarification.spi.ClarificationReadPort.class);
  private final WorkflowInspectionService service =
      new WorkflowInspectionService(
          runs,
          events,
          artifacts,
          payloadStore,
          approvals,
          links,
          redaction,
          recovery,
          runnerExecutions,
          scratchStore,
          clarifications,
          mock(org.dradgo.application.recovery.spi.RecoveryActionRecordPort.class),
          org.dradgo.application.runner.RunnerProperties.defaults(),
          org.dradgo.application.runner.RunnerWorkerPoolProperties.defaults(),
          mock(SplitProposalReadPort.class));

  private void stubNonFailedDescribe(
      String runId, WorkflowState currentState, String nextSafeAction) {
    when(recovery.describeFailure(runId))
        .thenReturn(
            new FailureDescription(
                runId, currentState, null, null, null, null, null, nextSafeAction, null));
  }

  @Test
  void getStatusReturnsHappyPathViewWithLatestEventArtifactsAndLink() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN,
                    WorkflowState.EXECUTING,
                    null,
                    3L,
                    0,
                    false,
                    "prj_default",
                    "run_parent1234")));
    when(runs.findByParentRunId(RUN))
        .thenReturn(
            List.of(
                new WorkflowRunSnapshot(
                    "run_childA1234", WorkflowState.INBOX, null, 1L, 0, false, null, RUN)));
    when(events.findLatestByWorkflowRunPublicId(RUN))
        .thenReturn(
            Optional.of(
                eventRecord(
                    "evt_recent1234",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    WorkflowState.INBOX,
                    WorkflowState.EXECUTING,
                    Map.of(
                        "linearTicketReference", "LIN-101", "idempotencyKey", "should-not-leak"))));
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.SPEC.value()))
        .thenReturn(Optional.of(artifactSnapshot(ArtifactType.SPEC, 2, ArtifactStatus.AVAILABLE)));
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(
            RUN, ArtifactType.IMPLEMENTATION_PLAN.value()))
        .thenReturn(Optional.empty());
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.PR_OUTPUT.value()))
        .thenReturn(Optional.empty());
    when(links.findActiveLinkByWorkflowRun(RUN))
        .thenReturn(
            Optional.of(
                new IntegrationLink(
                    "ilk_active12345",
                    RUN,
                    "linear",
                    "LIN-101",
                    IntegrationSyncStatus.LINKED,
                    Instant.now(),
                    Instant.now(),
                    null)));
    stubNonFailedDescribe(RUN, WorkflowState.EXECUTING, "await_outcome");

    WorkflowStatusView view = service.getStatus(RUN);

    assertEquals(RUN, view.workflowRunId());
    assertEquals(WorkflowState.EXECUTING, view.currentState());
    assertEquals("alex", view.currentActorIdentity());
    assertEquals(ActorType.HUMAN.value(), view.currentActorType());
    assertEquals(WorkflowEventType.WORKFLOW_STATE_CHANGED.value(), view.lastEventType());
    assertEquals(NOW, view.lastEventAt());
    assertEquals(1, view.latestArtifacts().size());
    LatestArtifactView spec = view.latestArtifacts().get(0);
    assertEquals(ArtifactType.SPEC.value(), spec.artifactType());
    assertEquals(2, spec.version());
    assertEquals(ArtifactStatus.AVAILABLE.value(), spec.status());
    // Story 3a-9 Gate 2 — the latest-artifact view now carries the artifact public id so the
    // approval bar's resolveSpecArtifactId (story 2.19) and the artifact-read endpoint can resolve.
    assertEquals("art_spec2", spec.artifactId());
    assertNotNull(view.linkedTicket());
    assertEquals("LIN-101", view.linkedTicket().externalRef());
    assertEquals("await_outcome", view.nextSafeAction());
    assertEquals("run_parent1234", view.parentRunId());
    assertEquals(List.of("run_childA1234"), view.childRunIds());
    assertNull(view.failedStage());
    assertNull(view.lastSuccessfulStage());
    assertNull(view.failureTimestamp());
    assertNull(view.failureCategory());
    assertNull(view.lastActivityTimestamp());
  }

  @Test
  void getStatusOmitsArtifactsAndLinkWhenAbsent() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.INBOX, null, 1L, 0, false)));
    when(runs.findByParentRunId(RUN))
        .thenReturn(
            List.of(
                new WorkflowRunSnapshot(
                    "run_childA1234", WorkflowState.INBOX, null, 1L, 0, false, null, RUN)));
    when(events.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());
    when(artifacts.findLatestByWorkflowRunIdAndArtifactType(eq(RUN), any()))
        .thenReturn(Optional.empty());
    when(links.findActiveLinkByWorkflowRun(RUN)).thenReturn(Optional.empty());
    stubNonFailedDescribe(RUN, WorkflowState.INBOX, "await_outcome");

    WorkflowStatusView view = service.getStatus(RUN);

    assertEquals(WorkflowState.INBOX, view.currentState());
    assertNull(view.currentActorIdentity());
    assertNull(view.lastEventType());
    assertNull(view.lastEventAt());
    assertTrue(view.latestArtifacts().isEmpty());
    assertNull(view.linkedTicket());
    assertNull(view.failedStage());
    assertEquals("await_outcome", view.nextSafeAction());
  }

  @Test
  void getStatusRaisesRunNotFoundForMissingRun() {
    when(runs.findByPublicId("run_missing12345")).thenReturn(Optional.empty());
    DomainException error =
        assertThrows(DomainException.class, () -> service.getStatus("run_missing12345"));
    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
    assertEquals("run_missing12345", error.details().get("runId"));
  }

  @Test
  void listHistoryDelegatesToPortAndProjectsRecords() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L, 0, false)));
    Map<String, Object> rawDetails = new LinkedHashMap<>();
    rawDetails.put("linearTicketReference", "LIN-101");
    rawDetails.put("idempotencyKey", "secret-key");
    rawDetails.put("correlationId", "corr-1");
    rawDetails.put("reviewerRole", "product_reviewer");
    rawDetails.put("taggedFeedback", "missing_scope");
    rawDetails.put("specRejectionLoopCount", 2);
    rawDetails.put("escalationMarker", true);
    rawDetails.put("someUnknownKey", "drop-me");
    rawDetails.put("childRunIds", List.of("run_child1", "run_child2"));
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                eventRecord(
                    "evt_one1234",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    null,
                    WorkflowState.INBOX,
                    rawDetails)));

    WorkflowHistoryView history = service.listHistory(RUN, null);

    assertEquals(1, history.events().size());
    Map<String, Object> renderedDetails = history.events().get(0).details();
    assertEquals("LIN-101", renderedDetails.get("linearTicketReference"));
    assertEquals("corr-1", renderedDetails.get("correlationId"));
    assertEquals("product_reviewer", renderedDetails.get("reviewerRole"));
    assertEquals("missing_scope", renderedDetails.get("taggedFeedback"));
    assertEquals(2, renderedDetails.get("specRejectionLoopCount"));
    assertEquals(true, renderedDetails.get("escalationMarker"));
    assertEquals(List.of("run_child1", "run_child2"), renderedDetails.get("childRunIds"));
    assertNull(
        renderedDetails.get("idempotencyKey"),
        "idempotencyKey must never reach a rendered details payload");
    assertNull(renderedDetails.get("someUnknownKey"), "unlisted keys must be dropped");
  }

  @Test
  void listHistorySurfacesRecoveryAuditKeysFromDispatchFailedAndRetriedEvents() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 5L, 0, false)));
    // recovery.retried writes failedStage/triggeringEventId/idempotencyKey/reason +
    // (when MDC is set) correlationId. idempotencyKey must still be stripped; the rest
    // belong in `history` output for operator triage.
    Map<String, Object> retriedDetails = new LinkedHashMap<>();
    retriedDetails.put("failedStage", "execution");
    retriedDetails.put("triggeringEventId", "evt_failure-aaaa1");
    retriedDetails.put("idempotencyKey", "idem-secret-12345");
    retriedDetails.put("correlationId", "corr-retry-99");
    retriedDetails.put("reason", "transient broker outage cleared at 12:00");
    // recovery.dispatchFailed adds errorCode/errorClass/recoveryActionId/recoveryRetriedEventId
    // (+ optional compensationFailed flag).
    Map<String, Object> dispatchFailedDetails = new LinkedHashMap<>();
    dispatchFailedDetails.put("failedStage", "execution");
    dispatchFailedDetails.put("recoveryActionId", "rcv_recov-aaaaa");
    dispatchFailedDetails.put("recoveryRetriedEventId", "evt_recret-bbbbb");
    dispatchFailedDetails.put("idempotencyKey", "idem-secret-12345");
    dispatchFailedDetails.put("errorCode", "RUNNER_CONTRACT_VIOLATION");
    dispatchFailedDetails.put("errorClass", "DomainException");
    dispatchFailedDetails.put("compensationFailed", true);
    dispatchFailedDetails.put("correlationId", "corr-retry-99");
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                eventRecord(
                    "evt_retried-aaaaa",
                    WorkflowEventType.RECOVERY_RETRIED,
                    WorkflowState.FAILED,
                    WorkflowState.EXECUTING,
                    retriedDetails),
                eventRecord(
                    "evt_dispfail-bbbbb",
                    WorkflowEventType.RECOVERY_DISPATCH_FAILED,
                    WorkflowState.EXECUTING,
                    WorkflowState.EXECUTING,
                    dispatchFailedDetails)));

    WorkflowHistoryView history = service.listHistory(RUN, null);

    assertEquals(2, history.events().size());
    Map<String, Object> retried = history.events().get(0).details();
    assertEquals("execution", retried.get("failedStage"));
    assertEquals("evt_failure-aaaa1", retried.get("triggeringEventId"));
    assertEquals("transient broker outage cleared at 12:00", retried.get("reason"));
    assertEquals("corr-retry-99", retried.get("correlationId"));
    assertNull(
        retried.get("idempotencyKey"),
        "idempotencyKey must never reach the rendered history payload, even on recovery events");

    Map<String, Object> dispatchFailed = history.events().get(1).details();
    assertEquals("execution", dispatchFailed.get("failedStage"));
    assertEquals("rcv_recov-aaaaa", dispatchFailed.get("recoveryActionId"));
    assertEquals("evt_recret-bbbbb", dispatchFailed.get("recoveryRetriedEventId"));
    assertEquals("RUNNER_CONTRACT_VIOLATION", dispatchFailed.get("errorCode"));
    assertEquals("DomainException", dispatchFailed.get("errorClass"));
    assertEquals(true, dispatchFailed.get("compensationFailed"));
    assertEquals("corr-retry-99", dispatchFailed.get("correlationId"));
    assertNull(
        dispatchFailed.get("idempotencyKey"),
        "idempotencyKey must never reach the rendered history payload, even on recovery events");
  }

  @Test
  void listHistoryRedactsSecretBytesEvenInsideAllowListedKeyValues() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L, 0, false)));
    Map<String, Object> rawDetails = new LinkedHashMap<>();
    // Adversarial: an operator pasted a github PAT into the allow-listed
    // linearTicketReference value. The allow-list lets the KEY through, so we rely on the
    // redaction policy to scrub the VALUE.
    rawDetails.put(
        "linearTicketReference", "github_pat_1234567890abcdefghijklmnopqrstuvwxyzABCDEFG");
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                eventRecord(
                    "evt_secret1234",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    null,
                    WorkflowState.INBOX,
                    rawDetails)));

    WorkflowHistoryView history = service.listHistory(RUN, null);
    String renderedTicket =
        String.valueOf(history.events().get(0).details().get("linearTicketReference"));
    assertTrue(
        !renderedTicket.contains("github_pat_1234567890abcdefghijklmnopqrstuvwxyzABCDEFG"),
        () -> "github PAT must not survive the redaction defense pass; got: " + renderedTicket);
  }

  @Test
  void listHistoryAppliesSinceFilterThroughPort() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L, 0, false)));
    OffsetDateTime since = OffsetDateTime.parse("2026-05-13T09:00:00Z");
    when(events.listByWorkflowRunPublicId(RUN, since)).thenReturn(List.of());

    WorkflowHistoryView history = service.listHistory(RUN, since);
    assertTrue(history.events().isEmpty());
  }

  @Test
  void listHistoryRaisesHistoryTooLargeWhenPortSignalsCeiling() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L, 0, false)));
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenThrow(
            new DomainException(
                DomainErrorCode.HISTORY_TOO_LARGE,
                "Run history exceeds 1000 events; pass --since to narrow.",
                Map.of("runId", RUN, "ceiling", 1000)));

    DomainException error =
        assertThrows(DomainException.class, () -> service.listHistory(RUN, null));
    assertEquals(DomainErrorCode.HISTORY_TOO_LARGE, error.errorCode());
  }

  @Test
  void listHistoryRaisesRunNotFoundForMissingRun() {
    when(runs.findByPublicId("run_nohist12345")).thenReturn(Optional.empty());
    DomainException error =
        assertThrows(DomainException.class, () -> service.listHistory("run_nohist12345", null));
    assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
  }

  @Test
  void getEventStreamReturnsSchemaShapedHeaderAndItems() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(
                new WorkflowRunSnapshot(
                    RUN, WorkflowState.WAITING_FOR_SPEC_APPROVAL, null, 3L, 0, false)));
    when(links.findActiveLinkByWorkflowRun(RUN)).thenReturn(Optional.empty());
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                eventRecord(
                    "evt_submit1234",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    null,
                    WorkflowState.INBOX,
                    Map.of("linearTicketReference", "LIN-101", "correlationId", "corr-1")),
                eventRecord(
                    "evt_artifact1234",
                    WorkflowEventType.ARTIFACT_DRAFT_CREATED,
                    null,
                    null,
                    Map.of(
                        "artifactVariant",
                        "spec",
                        "artifactVersion",
                        1,
                        "idempotencyKey",
                        "drop-me"))));

    WorkflowEventStreamView view = service.getEventStream(RUN);

    assertEquals(RUN, view.workflowRun().publicId());
    assertEquals("LIN-101", view.workflowRun().ticketRef());
    assertEquals(NOW, view.workflowRun().createdAt());
    assertEquals(
        WorkflowState.WAITING_FOR_SPEC_APPROVAL.value(), view.workflowRun().terminalState());
    assertEquals(2, view.events().size());
    assertEquals(RUN, view.events().get(0).workflowRunPublicId());
    assertEquals("spec", view.events().get(1).details().get("artifactVariant"));
    assertNull(view.events().get(1).details().get("idempotencyKey"));
  }

  @Test
  void getEventStreamRaisesInternalErrorWhenHistoryIsEmpty() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L, 0, false)));
    when(events.listByWorkflowRunPublicId(RUN, null)).thenReturn(List.of());

    DomainException error = assertThrows(DomainException.class, () -> service.getEventStream(RUN));
    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    assertEquals("missing event history", error.details().get("reason"));
  }

  @Test
  void getEventStreamRaisesInternalErrorWhenTicketReferenceCannotBeResolved() {
    when(runs.findByPublicId(RUN))
        .thenReturn(
            Optional.of(new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L, 0, false)));
    when(links.findActiveLinkByWorkflowRun(RUN)).thenReturn(Optional.empty());
    when(events.listByWorkflowRunPublicId(RUN, null))
        .thenReturn(
            List.of(
                eventRecord(
                    "evt_submit1234",
                    WorkflowEventType.WORKFLOW_STATE_CHANGED,
                    null,
                    WorkflowState.INBOX,
                    Map.of("correlationId", "corr-1"))));

    DomainException error = assertThrows(DomainException.class, () -> service.getEventStream(RUN));
    assertEquals(DomainErrorCode.INTERNAL_ERROR, error.errorCode());
    assertEquals("missing ticket reference", error.details().get("reason"));
  }

  private static WorkflowEventRecord eventRecord(
      String publicId,
      WorkflowEventType eventType,
      WorkflowState priorState,
      WorkflowState resultingState,
      Map<String, Object> details) {
    Map<String, Object> mutable = new LinkedHashMap<>(details);
    return new WorkflowEventRecord(
        publicId,
        RUN,
        eventType,
        priorState,
        resultingState,
        "alex",
        ActorType.HUMAN,
        "workflow submitted",
        null,
        false,
        NOW,
        mutable);
  }

  private static ArtifactRecordSnapshot artifactSnapshot(
      ArtifactType type, int version, ArtifactStatus status) {
    return new ArtifactRecordSnapshot(
        "art_" + type.value() + version,
        RUN,
        type,
        version,
        null,
        DataClassification.SHAREABLE_REDACTED,
        "storage-ref",
        "sha256",
        "deadbeef",
        null,
        null,
        status,
        null,
        false);
  }
}
