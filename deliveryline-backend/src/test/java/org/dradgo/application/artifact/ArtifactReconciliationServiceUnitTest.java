package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.dradgo.application.artifact.spi.ArtifactEventPort;
import org.dradgo.application.artifact.spi.ArtifactOperationPort;
import org.dradgo.application.artifact.spi.ArtifactRecordPort;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationStatus;
import org.dradgo.domain.registry.ArtifactStatus;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DataClassification;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.junit.jupiter.api.Test;

class ArtifactReconciliationServiceUnitTest {

	@Test
	void stalePendingOperationsAreMarkedAsFailedOrphan() {
		ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
		ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
		ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
		ArtifactReconciliationService service = new ArtifactReconciliationService(
			artifactOperationPort,
			artifactRecordPort,
			artifactEventPort,
			Clock.fixed(Instant.parse("2026-05-07T14:00:00Z"), ZoneOffset.UTC),
			Duration.ofMinutes(15),
			callthroughTemplate());
		ArtifactOperationSnapshot stalePending = new ArtifactOperationSnapshot(
			"op_pending1234",
			"run_ready1234",
			"art_pending1234",
			"create",
			ArtifactOperationStatus.PENDING,
			"idem-1234567890",
			null,
			null,
			OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
		ArtifactOperationSnapshot orphaned = new ArtifactOperationSnapshot(
			"op_pending1234",
			"run_ready1234",
			"art_pending1234",
			"create",
			ArtifactOperationStatus.FAILED_ORPHAN,
			"idem-1234567890",
			FailureCategory.RUNNER_CRASH,
			"artifact payload never materialized",
			OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));

		when(artifactOperationPort.findPendingOlderThan(org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(stalePending));
		when(artifactRecordPort.findByPublicId("art_pending1234"))
			.thenReturn(Optional.of(new ArtifactRecordSnapshot(
				"art_pending1234",
				"run_ready1234",
				ArtifactType.SPEC,
				1,
				null,
				DataClassification.LOCAL_ONLY,
				null,
				null,
				null,
				ArtifactStatus.PENDING,
				null)));
		when(artifactOperationPort.markFailedOrphan(
			eq("op_pending1234"),
			eq("artifact payload never materialized")))
			.thenReturn(orphaned);

		ArtifactReconciliationResult result = service.reconcileStalePendingOperations();

		assertEquals(List.of(orphaned), result.orphanedOperations());
		verify(artifactRecordPort).markFailed("art_pending1234", FailureCategory.ORPHAN, "stale_pending");
		verify(artifactOperationPort).markFailedOrphan("op_pending1234", "artifact payload never materialized");
		// D2 (round-5 decision): reconciliation appends BOTH ARTIFACT_FAILED and RECOVERY_RECONCILED
		// so existing consumers subscribed to the failure event see the orphan flip without
		// having to learn about the new reconciliation event type.
		verify(artifactEventPort).append(org.mockito.ArgumentMatchers.argThat(event ->
			event.eventType() == WorkflowEventType.ARTIFACT_FAILED
				&& event.actorType() == ActorType.SYSTEM
				&& FailureCategory.ORPHAN == event.failureCategory()
				&& "op_pending1234".equals(event.details().get("operationId"))
				&& "stale_pending".equals(event.details().get("failureReason"))
				&& "orphan".equals(event.details().get("failureCategory"))));
		verify(artifactEventPort).append(org.mockito.ArgumentMatchers.argThat(event ->
			event.eventType() == WorkflowEventType.RECOVERY_RECONCILED
				&& event.actorType() == ActorType.SYSTEM
				&& FailureCategory.ORPHAN == event.failureCategory()
				&& "op_pending1234".equals(event.details().get("operationId"))));
	}

	@Test
	void reconcileSkipsArtifactsThatBecameAvailableBetweenScanAndReconciliation() {
		ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
		ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
		ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
		ArtifactReconciliationService service = new ArtifactReconciliationService(
			artifactOperationPort,
			artifactRecordPort,
			artifactEventPort,
			Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC),
			Duration.ofMinutes(15),
			callthroughTemplate());
		ArtifactOperationSnapshot stalePending = new ArtifactOperationSnapshot(
			"op_pending5678",
			"run_ready5678",
			"art_winner5678",
			"create",
			ArtifactOperationStatus.PENDING,
			"idem-cas-1234567890",
			null,
			null,
			OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(45));

		when(artifactOperationPort.findPendingOlderThan(org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of(stalePending));
		when(artifactRecordPort.findByPublicId("art_winner5678"))
			.thenReturn(Optional.of(new ArtifactRecordSnapshot(
				"art_winner5678",
				"run_ready5678",
				ArtifactType.SPEC,
				1,
				null,
				DataClassification.LOCAL_ONLY,
				"artifacts/run_ready5678/art_winner5678/v1/spec.md",
				"SHA-256",
				"deadbeef",
				ArtifactStatus.AVAILABLE,
				null)));

		ArtifactReconciliationResult result = service.reconcileStalePendingOperations();

		assertEquals(List.of(), result.orphanedOperations());
		verify(artifactRecordPort, never()).markFailed(
			eq("art_winner5678"), eq(FailureCategory.ORPHAN), eq("stale_pending"));
		verify(artifactOperationPort, never()).markFailedOrphan(
			eq("op_pending5678"), eq("artifact payload never materialized"));
		verify(artifactEventPort, never()).append(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void reconciliationUsesTheConfiguredThresholdInsteadOfAHardcodedWindow() {
		ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
		ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
		ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
		Clock clock = Clock.fixed(Instant.parse("2026-05-07T14:00:00Z"), ZoneOffset.UTC);
		ArtifactReconciliationService service = new ArtifactReconciliationService(
			artifactOperationPort,
			artifactRecordPort,
			artifactEventPort,
			clock,
			Duration.ofMinutes(42),
			callthroughTemplate());

		when(artifactOperationPort.findPendingOlderThan(Duration.ofMinutes(42)))
			.thenReturn(List.of());

		ArtifactReconciliationResult result = service.reconcileStalePendingOperations();

		assertEquals(List.of(), result.orphanedOperations());
		// Threshold is passed as a Duration so the DB-side query (now() - interval) keeps both
		// sides of the staleness comparison on a single clock; JVM-side OffsetDateTime arithmetic
		// would mix JVM and DB timestamps and re-introduce the clock-skew bug.
		verify(artifactOperationPort).findPendingOlderThan(Duration.ofMinutes(42));
		verify(artifactOperationPort, never()).markFailedOrphan(eq("unused"), eq("unused"));
		verify(artifactRecordPort, never()).markFailed(eq("unused"), eq(FailureCategory.ORPHAN), eq("stale_pending"));
	}

	@Test
	void constructorRejectsNullTransactionTemplateSoUnitTestsCannotSilentlyBypassPerItemIsolation() {
		ArtifactOperationPort artifactOperationPort = mock(ArtifactOperationPort.class);
		ArtifactRecordPort artifactRecordPort = mock(ArtifactRecordPort.class);
		ArtifactEventPort artifactEventPort = mock(ArtifactEventPort.class);
		Clock clock = Clock.fixed(Instant.parse("2026-05-08T11:00:00Z"), ZoneOffset.UTC);

		IllegalArgumentException error = assertThrows(
			IllegalArgumentException.class,
			() -> new ArtifactReconciliationService(
				artifactOperationPort,
				artifactRecordPort,
				artifactEventPort,
				clock,
				Duration.ofMinutes(15),
				null));

		assertEquals(true, error.getMessage().contains("perItemTransactionTemplate"));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionTemplate callthroughTemplate() {
		TransactionTemplate template = mock(TransactionTemplate.class);
		when(template.execute(any(TransactionCallback.class)))
			.thenAnswer(invocation -> ((TransactionCallback) invocation.getArgument(0)).doInTransaction(null));
		return template;
	}
}
