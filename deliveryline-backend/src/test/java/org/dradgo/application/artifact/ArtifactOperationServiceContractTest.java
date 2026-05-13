package org.dradgo.application.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.ArtifactOperationType;
import org.dradgo.domain.registry.ArtifactType;
import org.dradgo.domain.registry.DomainErrorCode;
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
class ArtifactOperationServiceContractTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ArtifactOperationService service;

	@Autowired
	private ArtifactReconciliationService reconciliationService;

	@AfterEach
	void cleanDatabase() {
		jdbcTemplate.update("delete from artifact_operations");
		jdbcTemplate.update("delete from artifacts");
		jdbcTemplate.update("delete from workflow_events");
		jdbcTemplate.update("delete from workflow_runs");
	}

	@Test
	void concurrentNewVersionCallsAllocateUniqueVersionsAndPreserveLineageOrder() throws Exception {
		insertRun("run_artifact1234", WorkflowState.EXECUTING);
		ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-test");
		ArtifactRecordSnapshot draft = service.createDraft("run_artifact1234", ArtifactType.SPEC, "spec-v1.md", actor);
		CyclicBarrier barrier = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Callable<ArtifactRecordSnapshot> taskOne = () -> {
				barrier.await();
				return service.newVersion(draft.publicId(), "spec-v2.md", actor);
			};
			Callable<ArtifactRecordSnapshot> taskTwo = () -> {
				barrier.await();
				return service.newVersion(draft.publicId(), "spec-v3.md", actor);
			};

			Future<ArtifactRecordSnapshot> firstFuture = executor.submit(taskOne);
			Future<ArtifactRecordSnapshot> secondFuture = executor.submit(taskTwo);
			ArtifactRecordSnapshot first = firstFuture.get();
			ArtifactRecordSnapshot second = secondFuture.get();

			assertEquals(Set.of(2, 3), Set.of(first.version(), second.version()));
		}

		List<ArtifactLineageRow> lineage = jdbcTemplate.query(
			"""
				select artifact.public_id,
				       artifact.version,
				       parent.public_id as parent_public_id
				from artifacts artifact
				left join artifacts parent on parent.id = artifact.parent_artifact_id
				where artifact.workflow_run_id = (select id from workflow_runs where public_id = ?)
				  and artifact.artifact_type = ?
				order by artifact.version
				""",
			(resultSet, rowNum) -> new ArtifactLineageRow(
				resultSet.getString("public_id"),
				resultSet.getInt("version"),
				resultSet.getString("parent_public_id")),
			"run_artifact1234",
			ArtifactType.SPEC.value());

		assertEquals(3, lineage.size());
		assertEquals(draft.publicId(), lineage.get(0).publicId());
		assertEquals(1, lineage.get(0).version());
		assertNull(lineage.get(0).parentPublicId());
		assertEquals(2, lineage.get(1).version());
		assertEquals(draft.publicId(), lineage.get(1).parentPublicId());
		assertEquals(3, lineage.get(2).version());
		assertEquals(lineage.get(1).publicId(), lineage.get(2).parentPublicId());
	}

	@Test
	void recordOperationCommitsTheArtifactEventAndOperationRowsTogetherAgainstARealDatabase() {
		// AC3 transactional outbox: a single recordOperation must produce one workflow_event row,
		// one artifact row, and one artifact_operations row, all cross-referenced. This is the
		// real-DB regression that the mock-based ArtifactPersistenceAdapterUnitTest cannot prove.
		insertRun("run_outbox1234", WorkflowState.EXECUTING);

		var result = service.recordOperation(new RecordArtifactOperationCommand(
			"run_outbox1234",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			"idem-outbox-" + System.nanoTime(),
			"spec.md",
			"hello".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-outbox",
			null));

		String artifactPublicId = result.artifact().publicId();
		String operationPublicId = result.operation().publicId();

		Integer artifactCount = jdbcTemplate.queryForObject(
			"select count(*) from artifacts where public_id = ?", Integer.class, artifactPublicId);
		Integer operationCount = jdbcTemplate.queryForObject(
			"select count(*) from artifact_operations where public_id = ?", Integer.class, operationPublicId);
		Integer eventCount = jdbcTemplate.queryForObject(
			"select count(*) from workflow_events e "
				+ "join artifacts a on a.linked_event_id = e.id where a.public_id = ?",
			Integer.class, artifactPublicId);
		String operationLinkedEventId = jdbcTemplate.queryForObject(
			"select e.public_id from artifact_operations op "
				+ "join workflow_events e on e.id = op.linked_event_id where op.public_id = ?",
			String.class, operationPublicId);
		String artifactLinkedEventId = jdbcTemplate.queryForObject(
			"select e.public_id from artifacts a "
				+ "join workflow_events e on e.id = a.linked_event_id where a.public_id = ?",
			String.class, artifactPublicId);

		assertEquals(Integer.valueOf(1), artifactCount, "AC3: artifact row must exist");
		assertEquals(Integer.valueOf(1), operationCount, "AC3: artifact_operations row must exist");
		assertEquals(Integer.valueOf(1), eventCount, "AC3: linked workflow_events row must exist");
		assertNotNull(operationLinkedEventId, "AC3: artifact_operations must reference its linked_event");
		assertEquals(operationLinkedEventId, artifactLinkedEventId,
			"AC3: artifact and operation must point at the same linked_event row");
	}

	@Test
	void markAvailableCommitsArtifactStateAndOperationStateAndAvailabilityEventTogetherAgainstARealDatabase() {
		// AC4 markAvailable single-tx commit: artifact.status -> available, op.status -> complete,
		// and ARTIFACT_AVAILABLE event are appended in the same transaction. Verifies all three
		// are observable post-call.
		insertRun("run_avail1234", WorkflowState.EXECUTING);
		byte[] payloadBytes = "payload-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String checksumHex = ArtifactChecksum.digestHex("SHA-256", payloadBytes).orElseThrow();

		var recorded = service.recordOperation(new RecordArtifactOperationCommand(
			"run_avail1234",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			"idem-avail-" + System.nanoTime(),
			"spec.md",
			payloadBytes,
			"alex",
			ActorType.HUMAN,
			"corr-avail",
			null));

		ActorContext actor = new ActorContext("alex", ActorType.HUMAN, "corr-avail");
		// D3 (round-5 decision): markAvailable re-verifies via readBytes + recompute. The bytes
		// were written by recordOperation's afterCommit hook (D1), so readBytes must return them
		// and the supplied SHA-256 hex must match.
		service.markAvailable(
			recorded.artifact().publicId(),
			new ArtifactChecksum("SHA-256", checksumHex),
			"artifacts/run_avail1234/" + recorded.artifact().publicId() + "/v1/spec.md",
			actor);

		String artifactStatus = jdbcTemplate.queryForObject(
			"select status from artifacts where public_id = ?",
			String.class, recorded.artifact().publicId());
		String operationStatus = jdbcTemplate.queryForObject(
			"select status from artifact_operations where public_id = ?",
			String.class, recorded.operation().publicId());
		Integer availableEventCount = jdbcTemplate.queryForObject(
			"select count(*) from workflow_events e "
				+ "join workflow_runs r on r.id = e.workflow_run_id "
				+ "where r.public_id = ? and e.event_type = 'artifact.available'",
			Integer.class, "run_avail1234");

		assertEquals("available", artifactStatus, "AC4: artifact.status must be 'available'");
		assertEquals("complete", operationStatus, "AC4: artifact_operations.status must be 'complete'");
		assertEquals(Integer.valueOf(1), availableEventCount, "AC4: exactly one ARTIFACT_AVAILABLE event must exist");
	}

	@Test
	void duplicateRecordOperationWithSameWorkflowRunArtifactTypeAndIdempotencyKeyReplaysAgainstARealDatabase() {
		// AC9 idempotency: V3 widened unique constraint on (workflow_run_id, artifact_type,
		// idempotency_key, operation_type). A duplicate recordOperation must replay the prior
		// outcome instead of creating a second row, and only one operation row is persisted.
		insertRun("run_idem1234", WorkflowState.EXECUTING);
		String idem = "idem-dup-" + System.nanoTime();

		var first = service.recordOperation(new RecordArtifactOperationCommand(
			"run_idem1234",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			idem,
			"spec.md",
			"first-payload".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-idem",
			null));
		var second = service.recordOperation(new RecordArtifactOperationCommand(
			"run_idem1234",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			idem,
			"spec.md",
			"second-payload".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-idem",
			null));

		Integer rowCount = jdbcTemplate.queryForObject(
			"select count(*) from artifact_operations op "
				+ "join workflow_runs r on r.id = op.workflow_run_id "
				+ "where r.public_id = ? and op.idempotency_key = ? and op.operation_type = ?",
			Integer.class, "run_idem1234", idem, "create");

		assertEquals(first.operation().publicId(), second.operation().publicId(),
			"AC9: duplicate submission must replay the prior operation public id");
		assertEquals(first.artifact().publicId(), second.artifact().publicId(),
			"AC9: duplicate submission must point at the same artifact row");
		assertEquals(Integer.valueOf(1), rowCount,
			"AC9: V3 unique constraint must keep duplicate submissions to a single artifact_operations row");
	}

	@Test
	void reconciliationFlipsStalePendingArtifactToFailedOrphanAgainstARealDatabase() {
		// AC11 interrupted artifact ops + reconciliation: a DB-committed pending operation whose
		// file write never landed must be observable as failed_orphan after reconciliation runs.
		// Real-DB regression that mocks cannot exercise: V4 partial unique index, the actual
		// REQUIRES_NEW per-item transaction, and the @PrePersist clock all participate.
		insertRun("run_orphan1234", WorkflowState.EXECUTING);
		var recorded = service.recordOperation(new RecordArtifactOperationCommand(
			"run_orphan1234",
			ArtifactType.SPEC,
			ArtifactOperationType.CREATE,
			"idem-orphan-" + System.nanoTime(),
			"spec.md",
			"never-flushed-to-fs".getBytes(),
			"alex",
			ActorType.HUMAN,
			"corr-orphan",
			null));

		// Push the operation's created_at well past the staleness threshold so the DB-side
		// `now() - interval` query in findPendingOlderThan returns it.
		jdbcTemplate.update(
			"update artifact_operations set created_at = ? where public_id = ?",
			OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
			recorded.operation().publicId());

		var result = reconciliationService.reconcileStalePendingOperations();

		assertFalse(result.orphanedOperations().isEmpty(),
			"AC11: reconciliation must surface the stale pending operation as orphaned");
		assertTrue(result.orphanedOperations().stream()
				.anyMatch(op -> op.publicId().equals(recorded.operation().publicId())),
			"AC11: the stale operation we seeded must be the one that was flipped");

		String operationStatus = jdbcTemplate.queryForObject(
			"select status from artifact_operations where public_id = ?",
			String.class, recorded.operation().publicId());
		String artifactStatus = jdbcTemplate.queryForObject(
			"select status from artifacts where public_id = ?",
			String.class, recorded.artifact().publicId());
		String artifactFailureCategory = jdbcTemplate.queryForObject(
			"select failure_category from artifacts where public_id = ?",
			String.class, recorded.artifact().publicId());

		assertEquals("failed_orphan", operationStatus, "AC11: operation row must be 'failed_orphan'");
		assertEquals("failed", artifactStatus, "AC11: artifact row must be 'failed'");
		assertEquals("orphan", artifactFailureCategory,
			"AC11: artifact.failure_category must be 'orphan' so downstream queries can filter");
	}

	@Test
	void recordOperationOnTerminalWorkflowRunRaisesTypedWorkflowRunTerminalErrorAgainstARealDatabase() {
		// Bonus AC3 negative: V3 idempotency replay fast-path runs first; for a fresh
		// idempotency key on a terminal run, the WORKFLOW_RUN_TERMINAL guard kicks in and the
		// service must raise the typed domain error. Real-DB regression because the
		// ArtifactWorkflowRunStatePersistenceAdapter actually queries workflow_runs.current_state.
		insertRun("run_done1234", WorkflowState.COMPLETED);

		DomainException error = assertThrows(
			DomainException.class,
			() -> service.recordOperation(new RecordArtifactOperationCommand(
				"run_done1234",
				ArtifactType.SPEC,
				ArtifactOperationType.CREATE,
				"idem-terminal-" + System.nanoTime(),
				"spec.md",
				"payload".getBytes(),
				"alex",
				ActorType.HUMAN,
				"corr-terminal",
				null)));

		assertEquals(DomainErrorCode.WORKFLOW_RUN_TERMINAL, error.errorCode());
		assertEquals("run_done1234", error.details().get("workflowRunId"));
	}

	private void insertRun(String publicId, WorkflowState state) {
		jdbcTemplate.update(
			"insert into workflow_runs (public_id, current_state) values (?, ?)",
			publicId,
			state.value());
	}

	private record ArtifactLineageRow(String publicId, int version, String parentPublicId) {
	}
}
