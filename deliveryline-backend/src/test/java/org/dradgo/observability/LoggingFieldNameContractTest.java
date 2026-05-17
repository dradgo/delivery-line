package org.dradgo.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.dradgo.application.observability.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Story 1.19 AC4: the five MDC keys ({@link MdcKeys#ALL}) MUST use the exact camelCase casing
 * defined in {@code MdcKeys}. This test stamps each key, emits a log line, captures the event
 * via {@link ListAppender}, and asserts the {@link ILoggingEvent#getMDCPropertyMap()} contains
 * the exact key names and rejects every forbidden variant (snake_case, PascalCase, lowercase).
 *
 * <p>Because the JSON encoder (logstash-logback-encoder composite) reads
 * {@code getMDCPropertyMap()} directly, the same key set materialises in the {@code demo}
 * profile's JSON output without a separate pin.
 */
class LoggingFieldNameContractTest {

	private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LoggingFieldNameContractTest.class);

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		appender = new ListAppender<>();
		appender.start();
		((Logger) LoggerFactory.getLogger(LoggingFieldNameContractTest.class)).addAppender(appender);
		MDC.clear();
	}

	@AfterEach
	void tearDown() {
		((Logger) LoggerFactory.getLogger(LoggingFieldNameContractTest.class)).detachAppender(appender);
		MDC.clear();
	}

	@Test
	void mdcKeySetMatchesExactCamelCaseSurface() {
		MDC.put(MdcKeys.CORRELATION_ID, "c1");
		MDC.put(MdcKeys.WORKFLOW_RUN_ID, "run_x");
		MDC.put(MdcKeys.RUNNER_EXECUTION_ID, "rex_x");
		MDC.put(MdcKeys.ARTIFACT_ID, "art_x");
		MDC.put(MdcKeys.ARTIFACT_OPERATION_ID, "op_x");

		LOG.info("pin field names");

		ILoggingEvent event = appender.list.stream()
			.filter(e -> e.getFormattedMessage().equals("pin field names"))
			.findFirst()
			.orElseThrow();
		var mdc = event.getMDCPropertyMap();

		assertThat(mdc).containsKeys(
			"correlationId", "workflowRunId", "runnerExecutionId", "artifactId", "artifactOperationId");

		// Reject snake_case / lowercase / PascalCase drift.
		assertThat(mdc).doesNotContainKeys(
			"correlation_id", "correlationid", "CorrelationId",
			"workflow_run_id", "workflowrunid", "WorkflowRunId",
			"runner_execution_id", "runnerexecutionid", "RunnerExecutionId",
			"artifact_id", "artifactid", "ArtifactId",
			"artifact_operation_id", "artifactoperationid", "ArtifactOperationId");
	}

	@Test
	void mdcKeysSetMatchesPublishedSurface() {
		assertThat(MdcKeys.ALL)
			.containsExactlyInAnyOrder(
				"correlationId", "workflowRunId", "runnerExecutionId", "artifactId", "artifactOperationId");
	}
}
