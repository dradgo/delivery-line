package org.dradgo.observability.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.dradgo.application.observability.MdcKeys;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Test harness for the story 1.21 Testcontainers ITs that satisfies the spec Logging requirement:
 * each IT logs on entry + outcome with the {@code correlationId} + {@code workflowRunId} MDC keys
 * (the two keys that exist in {@link MdcKeys}, per the contract pinned by {@code
 * LoggingMdcContractTest}) plus {@code idempotencyKey} as a structured message argument (it is
 * deliberately NOT promoted to MDC — adding it would break the {@code MdcKeys.ALL} closed set). The
 * harness attaches a Logback {@link ListAppender} so the entry/outcome lines are pinned with a
 * focused assertion: each emitted line carries the required keys.
 *
 * <p>Usage: instantiate per test class, call {@link #attach} in {@code @BeforeEach} with the test
 * method's correlationId / workflowRunId / idempotencyKey, and {@link #detach} in
 * {@code @AfterEach}. The detach call asserts the entry/outcome lines were emitted with the
 * expected MDC surface — a missing scope clear or a renamed MDC key fails the test loudly rather
 * than silently.
 */
public final class ItLoggingHarness {

  private final Logger testLogger;
  private final Logger rootLogger;
  private ListAppender<ILoggingEvent> appender;
  private String activeCorrelationId;
  private String activeWorkflowRunId;
  private String activeIdempotencyKey;
  private String priorCorrelationId;
  private String priorWorkflowRunId;

  public ItLoggingHarness(Class<?> testClass) {
    this.testLogger = (Logger) LoggerFactory.getLogger(testClass);
    this.rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  }

  /**
   * Stamp MDC scope, attach the list appender to the root logger, and emit a single {@code
   * it-entry} log line carrying the three identifiers. Returns {@code this} for fluent setup.
   */
  public ItLoggingHarness attach(
      String correlationId, String workflowRunId, String idempotencyKey) {
    this.activeCorrelationId = correlationId;
    this.activeWorkflowRunId = workflowRunId;
    this.activeIdempotencyKey = idempotencyKey;
    this.priorCorrelationId = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, correlationId);
    this.priorWorkflowRunId = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    this.appender = new ListAppender<>();
    this.appender.start();
    rootLogger.addAppender(this.appender);
    testLogger.info(
        "it-entry correlationId={} workflowRunId={} idempotencyKey={}",
        correlationId,
        workflowRunId,
        idempotencyKey);
    return this;
  }

  /**
   * Emit a single {@code it-outcome} log line, detach the appender, restore MDC, then assert that
   * the entry+outcome lines are both present and both carry the required MDC keys.
   */
  public void detach() {
    if (appender == null) {
      throw new IllegalStateException("detach() called without a prior attach()");
    }
    testLogger.info(
        "it-outcome correlationId={} workflowRunId={} idempotencyKey={}",
        activeCorrelationId,
        activeWorkflowRunId,
        activeIdempotencyKey);
    rootLogger.detachAppender(appender);
    appender.stop();
    MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorWorkflowRunId);
    MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationId);

    List<ILoggingEvent> harnessEvents =
        appender.list.stream()
            .filter(
                e ->
                    e.getLevel() == Level.INFO
                        && e.getFormattedMessage() != null
                        && (e.getFormattedMessage().startsWith("it-entry ")
                            || e.getFormattedMessage().startsWith("it-outcome ")))
            .toList();

    assertEquals(
        2,
        harnessEvents.size(),
        "expected exactly one it-entry + one it-outcome harness log line, got: " + harnessEvents);
    for (ILoggingEvent event : harnessEvents) {
      assertEquals(
          activeCorrelationId,
          event.getMDCPropertyMap().get(MdcKeys.CORRELATION_ID),
          "harness log line missing MDC correlationId: " + event.getFormattedMessage());
      assertEquals(
          activeWorkflowRunId,
          event.getMDCPropertyMap().get(MdcKeys.WORKFLOW_RUN_ID),
          "harness log line missing MDC workflowRunId: " + event.getFormattedMessage());
      String message = event.getFormattedMessage();
      assertNotNull(message);
      assertTrue(
          message.contains("idempotencyKey=" + activeIdempotencyKey),
          "harness log line must include the idempotencyKey structured argument: " + message);
    }

    // Reset state so a stale attach() left over after a failure does not leak into subsequent
    // tests in the same JVM.
    this.appender = null;
    this.activeCorrelationId = null;
    this.activeWorkflowRunId = null;
    this.activeIdempotencyKey = null;
  }

  /**
   * Defensive cleanup after a test method that aborts between attach() and detach() — restore the
   * MDC scope and detach the appender without performing the entry/outcome assertion (which would
   * cascade-mask the original failure).
   */
  public void detachOnFailure() {
    if (appender != null) {
      rootLogger.detachAppender(appender);
      appender.stop();
      appender = null;
    }
    if (priorWorkflowRunId != null || activeWorkflowRunId != null) {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorWorkflowRunId);
    }
    if (priorCorrelationId != null || activeCorrelationId != null) {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, priorCorrelationId);
    }
    // Reset any residual MDC the production code may have stamped during the failed test method
    // so MDC pollution does not leak across @BeforeEach boundaries (defensive — production code
    // already restores MDC on its own scopes).
    MDC.remove(MdcKeys.CORRELATION_ID);
    MDC.remove(MdcKeys.WORKFLOW_RUN_ID);
  }
}
