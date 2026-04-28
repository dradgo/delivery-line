package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.workflow.WorkflowTransitionService;
import org.dradgo.application.workflow.WorkflowTransitionService.TransitionActor;
import org.dradgo.application.workflow.WorkflowTransitionService.WorkflowTransitionConcurrencyProbe;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import({
	TestcontainersConfiguration.class,
	WorkflowTransitionServiceContractTest.WorkflowTransitionServiceTestConfiguration.class
})
@SpringBootTest
@ActiveProfiles("test")
class WorkflowTransitionServiceContractTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WorkflowTransitionService service;

	@Autowired
	private CoordinatedProbe concurrencyProbe;

	@AfterEach
	void cleanDatabase() {
		concurrencyProbe.reset();
		jdbcTemplate.update("delete from workflow_events");
		jdbcTemplate.update("delete from workflow_runs");
	}

	@Test
	void validTransitionUpdatesTheRunAndAppendsAMatchingEventAtomically() {
		String runPublicId = insertRun("run_valid1234", WorkflowState.PLANNED);

		service.transition(
			runPublicId,
			WorkflowState.INVESTIGATING,
			new TransitionActor("alex", ActorType.HUMAN),
			"start investigation",
			"idem-valid-1234");

		assertEquals("Investigating", currentState(runPublicId));
		List<Map<String, Object>> events = jdbcTemplate.queryForList(
			"""
				select public_id, event_type, prior_state, resulting_state, actor_identity, actor_type,
				       reason, intervention_marker, failure_category
				from workflow_events
				where workflow_run_id = (select id from workflow_runs where public_id = ?)
				""",
			runPublicId);
		assertEquals(1, events.size());
		Map<String, Object> event = events.get(0);
		assertTrue(event.get("public_id").toString().startsWith("evt_"));
		assertEquals("workflow.stateChanged", event.get("event_type"));
		assertEquals("Planned", event.get("prior_state"));
		assertEquals("Investigating", event.get("resulting_state"));
		assertEquals("alex", event.get("actor_identity"));
		assertEquals("human", event.get("actor_type"));
		assertEquals("start investigation", event.get("reason"));
		assertEquals(Boolean.FALSE, event.get("intervention_marker"));
		assertEquals(null, event.get("failure_category"));
	}

	@Test
	void invalidTransitionRollsBackStateAndEventWrites() {
		String runPublicId = insertRun("run_invalid1234", WorkflowState.INBOX);
		int eventsBefore = eventCount(runPublicId);

		DomainException error = assertThrows(
			DomainException.class,
			() -> service.transition(
				runPublicId,
				WorkflowState.COMPLETED,
				new TransitionActor("alex", ActorType.HUMAN),
				"skip everything",
				"idem-invalid-1234"));

		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("Inbox", error.details().get("sourceState"));
		assertEquals("Completed", error.details().get("targetState"));
		assertEquals(runPublicId, error.details().get("runId"));
		assertEquals(eventsBefore, eventCount(runPublicId));
		assertEquals("Inbox", currentState(runPublicId));
	}

	@Test
	void staleSourceStateRejectsReplayWithIllegalTransition() {
		String runPublicId = insertRun("run_duplicate1234", WorkflowState.PLANNED);

		service.transition(
			runPublicId,
			WorkflowState.INVESTIGATING,
			new TransitionActor("alex", ActorType.HUMAN),
			"start investigation",
			"idem-duplicate-1");

		DomainException error = assertThrows(
			DomainException.class,
			() -> service.transition(
				runPublicId,
				WorkflowState.INVESTIGATING,
				new TransitionActor("alex", ActorType.HUMAN),
				"replay same logical request",
				"idem-duplicate-1"));

		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals(1, eventCount(runPublicId));
		assertEquals("Investigating", currentState(runPublicId));
	}

	@Test
	void executingToFailedPersistsAllowedRunnerFailureCategoriesAndRejectsOthers() {
		String failedRun = insertRun("run_failed1234", WorkflowState.EXECUTING);

		service.transition(
			failedRun,
			WorkflowState.FAILED,
			new TransitionActor("codex-runner", ActorType.AGENT),
			"runner crashed",
			"idem-failed-1",
			FailureCategory.RUNNER_CRASH);

		assertEquals("Failed", currentState(failedRun));
		assertEquals(
			"runner_crash",
			jdbcTemplate.queryForObject(
				"""
					select failure_category
					from workflow_events
					where workflow_run_id = (select id from workflow_runs where public_id = ?)
					""",
				String.class,
				failedRun));

		String rejectedRun = insertRun("run_failed5678", WorkflowState.EXECUTING);
		DomainException error = assertThrows(
			DomainException.class,
			() -> service.transition(
				rejectedRun,
				WorkflowState.FAILED,
				new TransitionActor("codex-runner", ActorType.AGENT),
				"runner produced a late result",
				"idem-failed-2",
				FailureCategory.RUNNER_LATE_RESULT));
		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals(0, eventCount(rejectedRun));
		assertEquals("Executing", currentState(rejectedRun));
	}

	@Test
	void concurrentTransitionsProduceOneSuccessAndOneConflict() throws Exception {
		String runPublicId = insertRun("run_concurrent1234", WorkflowState.WAITING_FOR_REVIEW);
		concurrencyProbe.coordinateNextRun(runPublicId, 2);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Callable<String>> calls = List.of(
				() -> {
					service.transition(
						runPublicId,
						WorkflowState.COMPLETED,
						new TransitionActor("alex", ActorType.HUMAN),
						"approved",
						"idem-concurrent-1");
					return "completed";
				},
				() -> {
					service.transition(
						runPublicId,
						WorkflowState.EXECUTING,
						new TransitionActor("alex", ActorType.HUMAN),
						"re-open for changes",
						"idem-concurrent-2");
					return "executing";
				});

			List<Future<String>> futures = calls.stream().map(executor::submit).toList();
			List<String> successes = new ArrayList<>();
			List<DomainException> failures = new ArrayList<>();
			for (Future<String> future : futures) {
				try {
					successes.add(future.get(10, TimeUnit.SECONDS));
				} catch (ExecutionException executionException) {
					Throwable cause = executionException.getCause();
					assertNotNull(cause);
					failures.add(assertInstanceOf(DomainException.class, cause,
						() -> "Unexpected non-domain failure: " + cause));
				}
			}

			assertEquals(1, successes.size());
			assertEquals(1, failures.size());
			assertEquals(DomainErrorCode.CONCURRENT_TRANSITION_CONFLICT, failures.get(0).errorCode());
			assertEquals(1, eventCount(runPublicId));
			String finalState = currentState(runPublicId);
			assertTrue(List.of("Completed", "Executing").contains(finalState));
			String survivingEventResultingState = jdbcTemplate.queryForObject(
				"""
					select resulting_state
					from workflow_events
					where workflow_run_id = (select id from workflow_runs where public_id = ?)
					""",
				String.class,
				runPublicId);
			assertEquals(finalState, survivingEventResultingState,
				"Surviving event resulting_state must match the run's current_state after rollback");
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void transitionsAgainstArchivedRunsAreRejectedAndLeaveStateUntouched() {
		String runPublicId = insertRun("run_archived1234", WorkflowState.PLANNED);
		jdbcTemplate.update(
			"update workflow_runs set archived_at = now() where public_id = ?",
			runPublicId);

		DomainException error = assertThrows(
			DomainException.class,
			() -> service.transition(
				runPublicId,
				WorkflowState.INVESTIGATING,
				new TransitionActor("alex", ActorType.HUMAN),
				"start investigation",
				"idem-archived-1"));

		assertEquals(DomainErrorCode.ILLEGAL_TRANSITION, error.errorCode());
		assertEquals("run_archived", error.details().get("reason"));
		assertEquals(0, eventCount(runPublicId));
		assertEquals("Planned", currentState(runPublicId));
	}

	private String insertRun(String publicId, WorkflowState state) {
		jdbcTemplate.update(
			"insert into workflow_runs (public_id, current_state) values (?, ?)",
			publicId,
			state.value());
		return publicId;
	}

	private String currentState(String runPublicId) {
		return jdbcTemplate.queryForObject(
			"select current_state from workflow_runs where public_id = ?",
			String.class,
			runPublicId);
	}

	private int eventCount(String runPublicId) {
		Integer count = jdbcTemplate.queryForObject(
			"""
				select count(*)
				from workflow_events
				where workflow_run_id = (select id from workflow_runs where public_id = ?)
				""",
			Integer.class,
			runPublicId);
		return count == null ? 0 : count;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class WorkflowTransitionServiceTestConfiguration {

		@Bean
		CoordinatedProbe coordinatedProbe() {
			return new CoordinatedProbe();
		}
	}

	static final class CoordinatedProbe implements WorkflowTransitionConcurrencyProbe {

		private volatile String targetRunId;
		private volatile CountDownLatch readyLatch = new CountDownLatch(0);
		private volatile CountDownLatch releaseLatch = new CountDownLatch(0);

		void coordinateNextRun(String runPublicId, int participants) {
			targetRunId = runPublicId;
			readyLatch = new CountDownLatch(participants);
			releaseLatch = new CountDownLatch(1);
		}

		void reset() {
			targetRunId = null;
			readyLatch = new CountDownLatch(0);
			releaseLatch = new CountDownLatch(0);
		}

		@Override
		public void afterRunLoaded(String runPublicId) {
			if (!runPublicId.equals(targetRunId)) {
				return;
			}
			readyLatch.countDown();
			try {
				if (!readyLatch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting for concurrent transition participants");
				}
				releaseLatch.countDown();
				if (!releaseLatch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out releasing concurrent transition participants");
				}
			} catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while coordinating concurrent transition test", interruptedException);
			}
		}
	}
}
