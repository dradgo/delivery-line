package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.application.artifact.spi.ArtifactRunnerExecutionPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

class ArtifactLoggingContractTest {

	private final ListAppender<ILoggingEvent> operationServiceAppender = attachListAppender(ArtifactOperationService.class);
	private final ListAppender<ILoggingEvent> reconciliationAppender = attachListAppender(ArtifactReconciliationService.class);
	private final ListAppender<ILoggingEvent> serviceAppender = attachListAppender(ArtifactService.class);

	@AfterEach
	void detachAppenders() {
		detach(ArtifactOperationService.class, operationServiceAppender);
		detach(ArtifactReconciliationService.class, reconciliationAppender);
		detach(ArtifactService.class, serviceAppender);
	}

	@Test
	void recordOperationSuccessEmitsEntryAndSuccessLogsCarryingContextKeys() {
		ArtifactRecordPort recordPort = mock(ArtifactRecordPort.class);
		ArtifactOperationPort operationPort = mock(ArtifactOperationPort.class);
		ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
		ArtifactEventPort eventPort = mock(ArtifactEventPort.class);
		ArtifactRunnerExecutionPort runnerPort = mock(ArtifactRunnerExecutionPort.class);
		ArtifactOperationService service = new ArtifactOperationService(
			recordPort, operationPort, payloadStore, eventPort, runnerPort);

		ArtifactRecordSnapshot draft = pendingArtifact("art_log1234", "run_log1234");
		ArtifactOperationSnapshot pending = pendingOperation("op_log1234", "run_log1234", "art_log1234");
		when(recordPort.findLatestByWorkflowRunIdAndArtifactType("run_log1234", ArtifactType.SPEC.value()))
			.thenReturn(Optional.empty());
		when(recordPort.createDraft(any())).thenReturn(draft);
		when(operationPort.createPending(any(), eq("run_log1234"), eq(ArtifactType.SPEC), eq("art_log1234"),
			eq(ArtifactOperationType.CREATE.value()), eq("idem-log-1234567890"))).thenReturn(pending);

		service.recordOperation(new RecordArtifactOperationCommand(
			"run_log1234",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			"idem-log-1234567890",
			"spec.md",
			"spec body".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-log",
			null));

		List<ILoggingEvent> events = operationServiceAppender.list;
		assertTrue(events.stream().anyMatch(e ->
			e.getLevel() == Level.INFO
				&& e.getFormattedMessage().contains("recordOperation start")
				&& e.getFormattedMessage().contains("workflowRunId=run_log1234")
				&& e.getFormattedMessage().contains("idempotencyKey=idem-log-1234567890")
				&& e.getFormattedMessage().contains("correlationId=corr-log")),
			"expected INFO entry log carrying workflowRunId/idempotencyKey/correlationId; events=" + events);
		assertTrue(events.stream().anyMatch(e ->
			e.getLevel() == Level.INFO
				&& e.getFormattedMessage().contains("recordOperation success")
				&& e.getFormattedMessage().contains("artifactId=art_log1234")
				&& e.getFormattedMessage().contains("operationId=op_log1234")),
			"expected INFO success log carrying artifactId/operationId; events=" + events);
	}

	@Test
	void recordOperationLateOrStaleEmitsWarnLogIdentifyingTheRunnerAndArtifact() {
		ArtifactRecordPort recordPort = mock(ArtifactRecordPort.class);
		ArtifactOperationPort operationPort = mock(ArtifactOperationPort.class);
		ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
		ArtifactEventPort eventPort = mock(ArtifactEventPort.class);
		ArtifactRunnerExecutionPort runnerPort = mock(ArtifactRunnerExecutionPort.class);
		ArtifactOperationService service = new ArtifactOperationService(
			recordPort, operationPort, payloadStore, eventPort, runnerPort);

		ArtifactRecordSnapshot draft = pendingArtifact("art_late9999", "run_late9999");
		ArtifactRecordSnapshot late = lateOrStaleArtifact("art_late9999", "run_late9999");
		ArtifactOperationSnapshot pending = pendingOperation("op_late9999", "run_late9999", "art_late9999");
		when(recordPort.findLatestByWorkflowRunIdAndArtifactType("run_late9999", ArtifactType.SPEC.value()))
			.thenReturn(Optional.empty());
		when(recordPort.createDraft(any())).thenReturn(draft);
		when(runnerPort.isTimedOut("rex_late9999")).thenReturn(true);
		when(recordPort.markLateOrStale("art_late9999")).thenReturn(late);
		when(operationPort.createPending(any(), eq("run_late9999"), eq(ArtifactType.SPEC), eq("art_late9999"),
			eq(ArtifactOperationType.CREATE.value()), eq("idem-late-9999999999"))).thenReturn(pending);

		service.recordOperation(new RecordArtifactOperationCommand(
			"run_late9999",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			"idem-late-9999999999",
			"spec.md",
			"spec body".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-late",
			"rex_late9999"));

		assertTrue(operationServiceAppender.list.stream().anyMatch(e ->
			e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("flagging artifact as late_or_stale")
				&& e.getFormattedMessage().contains("artifactId=art_late9999")
				&& e.getFormattedMessage().contains("runnerExecutionId=rex_late9999")),
			"expected WARN late_or_stale log; events=" + operationServiceAppender.list);
	}

	@Test
	void recordOperationConflictNoReplayEmitsWarnIdentifyingIdempotencyKey() {
		ArtifactRecordPort recordPort = mock(ArtifactRecordPort.class);
		ArtifactOperationPort operationPort = mock(ArtifactOperationPort.class);
		ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
		ArtifactEventPort eventPort = mock(ArtifactEventPort.class);
		ArtifactRunnerExecutionPort runnerPort = mock(ArtifactRunnerExecutionPort.class);
		ArtifactOperationService service = new ArtifactOperationService(
			recordPort, operationPort, payloadStore, eventPort, runnerPort);

		ArtifactRecordSnapshot draft = pendingArtifact("art_conflict7777", "run_conflict7777");
		when(operationPort.findReplay(eq("run_conflict7777"), eq(ArtifactType.SPEC), eq("idem-conflict-7777777777"), any()))
			.thenReturn(Optional.empty());
		when(recordPort.findLatestByWorkflowRunIdAndArtifactType("run_conflict7777", ArtifactType.SPEC.value()))
			.thenReturn(Optional.empty());
		when(recordPort.createDraft(any())).thenReturn(draft);
		when(operationPort.createPending(any(), eq("run_conflict7777"), eq(ArtifactType.SPEC), eq("art_conflict7777"),
			eq(ArtifactOperationType.CREATE.value()), eq("idem-conflict-7777777777")))
			.thenThrow(new DataIntegrityViolationException("uq violation"));

		assertThrows(DomainException.class, () -> service.recordOperation(new RecordArtifactOperationCommand(
			"run_conflict7777",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			"idem-conflict-7777777777",
			"spec.md",
			"spec body".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-conflict",
			null)));

		assertTrue(operationServiceAppender.list.stream().anyMatch(e ->
			e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("recordOperation conflict no-replay")
				&& e.getFormattedMessage().contains("idempotencyKey=idem-conflict-7777777777")),
			"expected WARN conflict log; events=" + operationServiceAppender.list);
	}

	@Test
	void reconciliationOrphanFlipEmitsWarnLogCarryingOperationAndThreshold() {
		ArtifactOperationPort operationPort = mock(ArtifactOperationPort.class);
		ArtifactRecordPort recordPort = mock(ArtifactRecordPort.class);
		ArtifactEventPort eventPort = mock(ArtifactEventPort.class);
		ArtifactReconciliationService service = new ArtifactReconciliationService(
			operationPort,
			recordPort,
			eventPort,
			Clock.fixed(Instant.parse("2026-05-08T14:00:00Z"), ZoneOffset.UTC),
			Duration.ofMinutes(15),
			callthroughTemplate());

		ArtifactOperationSnapshot stalePending = new ArtifactOperationSnapshot(
			"op_orphan1234",
			"run_orphan1234",
			"art_orphan1234",
			"create",
			ArtifactOperationStatus.PENDING,
			"idem-orphan-1234567890",
			null,
			null,
			OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
		ArtifactOperationSnapshot orphaned = new ArtifactOperationSnapshot(
			"op_orphan1234",
			"run_orphan1234",
			"art_orphan1234",
			"create",
			ArtifactOperationStatus.FAILED_ORPHAN,
			"idem-orphan-1234567890",
			FailureCategory.ORPHAN,
			"artifact payload never materialized",
			OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
		when(operationPort.findPendingOlderThan(any())).thenReturn(List.of(stalePending));
		when(recordPort.findByPublicId("art_orphan1234")).thenReturn(Optional.of(new ArtifactRecordSnapshot(
			"art_orphan1234",
			"run_orphan1234",
			ArtifactType.SPEC,
			1,
			null,
			DataClassification.LOCAL_ONLY,
			null,
			null,
			null,
			ArtifactStatus.PENDING,
			null)));
		when(operationPort.markFailedOrphan(eq("op_orphan1234"), eq("artifact payload never materialized")))
			.thenReturn(orphaned);

		service.reconcileStalePendingOperations();

		assertTrue(reconciliationAppender.list.stream().anyMatch(e ->
			e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("flipped artifact to failed/orphan")
				&& e.getFormattedMessage().contains("operationId=op_orphan1234")
				&& e.getFormattedMessage().contains("artifactId=art_orphan1234")),
			"expected WARN orphan-flip log; events=" + reconciliationAppender.list);
	}

	@Test
	void isApprovalEligibleChecksumMismatchEmitsWarnWithoutLeakingPayloadBytes() {
		ArtifactRecordPort recordPort = mock(ArtifactRecordPort.class);
		ArtifactPayloadStore payloadStore = mock(ArtifactPayloadStore.class);
		ArtifactService service = new ArtifactService(recordPort, payloadStore);

		when(recordPort.findByPublicId("art_drift9999")).thenReturn(Optional.of(new ArtifactRecordSnapshot(
			"art_drift9999",
			"run_drift9999",
			ArtifactType.SPEC,
			1,
			null,
			DataClassification.LOCAL_ONLY,
			"artifacts/run_drift9999/art_drift9999/v1/spec.md",
			"SHA-256",
			"00000000000000000000000000000000",
			ArtifactStatus.AVAILABLE,
			null)));
		when(payloadStore.readBytes("artifacts/run_drift9999/art_drift9999/v1/spec.md"))
			.thenReturn(Optional.of("supersecret".getBytes()));

		assertEquals(false, service.isApprovalEligible("art_drift9999"));

		assertTrue(serviceAppender.list.stream().anyMatch(e ->
			e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("checksumMismatch")
				&& e.getFormattedMessage().contains("artifactId=art_drift9999")
				&& !e.getFormattedMessage().contains("supersecret")),
			"expected WARN checksum-mismatch log without payload bytes; events=" + serviceAppender.list);
	}

	private static ArtifactRecordSnapshot pendingArtifact(String id, String runId) {
		return new ArtifactRecordSnapshot(
			id,
			runId,
			ArtifactType.SPEC,
			1,
			null,
			DataClassification.LOCAL_ONLY,
			null,
			null,
			null,
			ArtifactStatus.PENDING,
			null);
	}

	private static ArtifactRecordSnapshot lateOrStaleArtifact(String id, String runId) {
		return new ArtifactRecordSnapshot(
			id,
			runId,
			ArtifactType.SPEC,
			1,
			null,
			DataClassification.LOCAL_ONLY,
			null,
			null,
			null,
			ArtifactStatus.LATE_OR_STALE,
			null);
	}

	private static ArtifactOperationSnapshot pendingOperation(String id, String runId, String artifactId) {
		return new ArtifactOperationSnapshot(
			id,
			runId,
			artifactId,
			"create",
			ArtifactOperationStatus.PENDING,
			"idem-pending-" + id,
			null,
			null,
			OffsetDateTime.now(ZoneOffset.UTC));
	}

	private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
		Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
		logger.detachAppender(appender);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static org.springframework.transaction.support.TransactionTemplate callthroughTemplate() {
		org.springframework.transaction.support.TransactionTemplate template = mock(org.springframework.transaction.support.TransactionTemplate.class);
		when(template.execute(any(org.springframework.transaction.support.TransactionCallback.class)))
			.thenAnswer(invocation -> ((org.springframework.transaction.support.TransactionCallback) invocation.getArgument(0)).doInTransaction(null));
		return template;
	}
}
