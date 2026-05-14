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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dradgo.application.artifact.ArtifactRecordSnapshot;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkService;
import org.dradgo.application.security.DataClassificationService;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
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
	private final IntegrationLinkService links = mock(IntegrationLinkService.class);
	private final RedactionPolicyService redaction = new RedactionPolicyService(new DataClassificationService());
	private final WorkflowInspectionService service =
		new WorkflowInspectionService(runs, events, artifacts, links, redaction);

	@Test
	void getStatusReturnsHappyPathViewWithLatestEventArtifactsAndLink() {
		when(runs.findByPublicId(RUN)).thenReturn(Optional.of(
			new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L)));
		when(events.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.of(eventRecord(
			"evt_recent1234",
			WorkflowEventType.WORKFLOW_STATE_CHANGED,
			WorkflowState.INBOX,
			WorkflowState.EXECUTING,
			Map.of("linearTicketReference", "LIN-101", "idempotencyKey", "should-not-leak"))));
		when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.SPEC.value()))
			.thenReturn(Optional.of(artifactSnapshot(ArtifactType.SPEC, 2, ArtifactStatus.AVAILABLE)));
		when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.IMPLEMENTATION_PLAN.value()))
			.thenReturn(Optional.empty());
		when(artifacts.findLatestByWorkflowRunIdAndArtifactType(RUN, ArtifactType.PR_OUTPUT.value()))
			.thenReturn(Optional.empty());
		when(links.findActiveLinkByWorkflowRun(RUN)).thenReturn(Optional.of(new IntegrationLink(
			"ilk_active12345",
			RUN,
			"linear",
			"LIN-101",
			IntegrationSyncStatus.LINKED,
			Instant.now(),
			Instant.now(),
			null)));

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
		assertNotNull(view.linkedTicket());
		assertEquals("LIN-101", view.linkedTicket().externalRef());
		assertEquals(WorkflowInspectionService.NEXT_SAFE_ACTION_PLACEHOLDER, view.nextSafeAction());
	}

	@Test
	void getStatusOmitsArtifactsAndLinkWhenAbsent() {
		when(runs.findByPublicId(RUN)).thenReturn(Optional.of(
			new WorkflowRunSnapshot(RUN, WorkflowState.INBOX, null, 1L)));
		when(events.findLatestByWorkflowRunPublicId(RUN)).thenReturn(Optional.empty());
		when(artifacts.findLatestByWorkflowRunIdAndArtifactType(eq(RUN), any())).thenReturn(Optional.empty());
		when(links.findActiveLinkByWorkflowRun(RUN)).thenReturn(Optional.empty());

		WorkflowStatusView view = service.getStatus(RUN);

		assertEquals(WorkflowState.INBOX, view.currentState());
		assertNull(view.currentActorIdentity());
		assertNull(view.lastEventType());
		assertNull(view.lastEventAt());
		assertTrue(view.latestArtifacts().isEmpty());
		assertNull(view.linkedTicket());
	}

	@Test
	void getStatusRaisesRunNotFoundForMissingRun() {
		when(runs.findByPublicId("run_missing12345")).thenReturn(Optional.empty());
		DomainException error = assertThrows(DomainException.class,
			() -> service.getStatus("run_missing12345"));
		assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
		assertEquals("run_missing12345", error.details().get("runId"));
	}

	@Test
	void listHistoryDelegatesToPortAndProjectsRecords() {
		when(runs.findByPublicId(RUN)).thenReturn(Optional.of(
			new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L)));
		Map<String, Object> rawDetails = new LinkedHashMap<>();
		rawDetails.put("linearTicketReference", "LIN-101");
		rawDetails.put("idempotencyKey", "secret-key");
		rawDetails.put("correlationId", "corr-1");
		rawDetails.put("someUnknownKey", "drop-me");
		when(events.listByWorkflowRunPublicId(RUN, null)).thenReturn(List.of(
			eventRecord("evt_one1234", WorkflowEventType.WORKFLOW_STATE_CHANGED,
				null, WorkflowState.INBOX, rawDetails)));

		WorkflowHistoryView history = service.listHistory(RUN, null);

		assertEquals(1, history.events().size());
		Map<String, Object> renderedDetails = history.events().get(0).details();
		assertEquals("LIN-101", renderedDetails.get("linearTicketReference"));
		assertEquals("corr-1", renderedDetails.get("correlationId"));
		assertNull(renderedDetails.get("idempotencyKey"), "idempotencyKey must never reach a rendered details payload");
		assertNull(renderedDetails.get("someUnknownKey"), "unlisted keys must be dropped");
	}

	@Test
	void listHistoryRedactsSecretBytesEvenInsideAllowListedKeyValues() {
		when(runs.findByPublicId(RUN)).thenReturn(Optional.of(
			new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L)));
		Map<String, Object> rawDetails = new LinkedHashMap<>();
		// Adversarial: an operator pasted a github PAT into the allow-listed
		// linearTicketReference value. The allow-list lets the KEY through, so we rely on the
		// redaction policy to scrub the VALUE.
		rawDetails.put("linearTicketReference",
			"github_pat_1234567890abcdefghijklmnopqrstuvwxyzABCDEFG");
		when(events.listByWorkflowRunPublicId(RUN, null)).thenReturn(List.of(
			eventRecord("evt_secret1234", WorkflowEventType.WORKFLOW_STATE_CHANGED,
				null, WorkflowState.INBOX, rawDetails)));

		WorkflowHistoryView history = service.listHistory(RUN, null);
		String renderedTicket = String.valueOf(history.events().get(0).details().get("linearTicketReference"));
		assertTrue(
			!renderedTicket.contains("github_pat_1234567890abcdefghijklmnopqrstuvwxyzABCDEFG"),
			() -> "github PAT must not survive the redaction defense pass; got: " + renderedTicket);
	}

	@Test
	void listHistoryAppliesSinceFilterThroughPort() {
		when(runs.findByPublicId(RUN)).thenReturn(Optional.of(
			new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L)));
		OffsetDateTime since = OffsetDateTime.parse("2026-05-13T09:00:00Z");
		when(events.listByWorkflowRunPublicId(RUN, since)).thenReturn(List.of());

		WorkflowHistoryView history = service.listHistory(RUN, since);
		assertTrue(history.events().isEmpty());
	}

	@Test
	void listHistoryRaisesHistoryTooLargeWhenPortSignalsCeiling() {
		when(runs.findByPublicId(RUN)).thenReturn(Optional.of(
			new WorkflowRunSnapshot(RUN, WorkflowState.EXECUTING, null, 3L)));
		when(events.listByWorkflowRunPublicId(RUN, null)).thenThrow(new DomainException(
			DomainErrorCode.HISTORY_TOO_LARGE,
			"Run history exceeds 1000 events; pass --since to narrow.",
			Map.of("runId", RUN, "ceiling", 1000)));

		DomainException error = assertThrows(DomainException.class, () -> service.listHistory(RUN, null));
		assertEquals(DomainErrorCode.HISTORY_TOO_LARGE, error.errorCode());
	}

	@Test
	void listHistoryRaisesRunNotFoundForMissingRun() {
		when(runs.findByPublicId("run_nohist12345")).thenReturn(Optional.empty());
		DomainException error = assertThrows(DomainException.class,
			() -> service.listHistory("run_nohist12345", null));
		assertEquals(DomainErrorCode.RUN_NOT_FOUND, error.errorCode());
	}

	private static WorkflowEventRecord eventRecord(
		String publicId,
		WorkflowEventType eventType,
		WorkflowState priorState,
		WorkflowState resultingState,
		Map<String, Object> details
	) {
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

	private static ArtifactRecordSnapshot artifactSnapshot(ArtifactType type, int version, ArtifactStatus status) {
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
