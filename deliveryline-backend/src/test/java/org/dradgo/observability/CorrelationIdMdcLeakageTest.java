package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.dradgo.adapters.cli.WorkflowCommands;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.WorkflowState;
import org.dradgo.application.observability.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Pins AC2 of story 1.19: a CLI command MUST {@code MDC.remove(correlationId)} in a
 * {@code finally} block, so two sequential invocations cannot share or leak correlation state.
 * Independent of the {@code WorkflowCommandsTest} family — that suite verifies user-visible
 * stdout; this suite verifies the structured-log surface.
 */
class CorrelationIdMdcLeakageTest {

	private WorkflowCommandService service;
	private WorkflowCommands commands;

	@BeforeEach
	void setUp() {
		service = mock(WorkflowCommandService.class);
		when(service.submit(any(SubmitWorkflowCommand.class))).thenAnswer(invocation -> {
			SubmitWorkflowCommand command = invocation.getArgument(0);
			return new SubmitWorkflowResult("run_test-aaaaa", WorkflowState.INBOX, command.correlationId());
		});
		commands = new WorkflowCommands(
			service,
			() -> true, // interactive — auto-generate idempotency keys
			counter("idem"));
	}

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	@Test
	void mdcIsEmptyBetweenSequentialCliInvocations() {
		commands.submit("LIN-1", "actor@local", ActorType.HUMAN, null, null, false);
		assertThat(MDC.get(MdcKeys.CORRELATION_ID))
			.as("correlationId must not leak past the submit() entry-point's finally block")
			.isNull();

		commands.submit("LIN-2", "actor@local", ActorType.HUMAN, null, null, false);
		assertThat(MDC.get(MdcKeys.CORRELATION_ID))
			.as("a second submit() must also clear correlationId")
			.isNull();
	}

	@Test
	void allMdcKeysClearedBetweenSequentialCliInvocations() {
		// Renamed from `mdcWorkflowRunIdIsEmptyBetweenSequentialServiceCalls` (P15 of story 1.19
		// review). The service is mocked so application-layer MDC stamping (workflowRunId,
		// artifactId, …) does not run here — what this assertion actually pins is that no MDC
		// keys remain set after two sequential CLI invocations, which is the AC2 leakage
		// contract at the CLI seam. The deeper application-service MDC stamping is verified by
		// JsonSchemaStabilityTest + the per-service logging contract tests.
		commands.submit("LIN-A", "actor@local", ActorType.HUMAN, null, null, false);
		commands.submit("LIN-B", "actor@local", ActorType.HUMAN, null, null, false);

		assertThat(MDC.getCopyOfContextMap())
			.as("no residual MDC keys between two sequential CLI invocations")
			.satisfiesAnyOf(
				map -> assertThat(map).isNull(),
				map -> assertThat(map).isEmpty());
	}

	private static java.util.function.Supplier<String> counter(String prefix) {
		java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
		return () -> prefix + "-" + n.incrementAndGet();
	}

	@SuppressWarnings("unused")
	private IdempotencyKeyValidator unusedValidator() {
		return new IdempotencyKeyValidator();
	}
}
