package org.dradgo.application.observability;

import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Single-source-of-truth for the stable MDC key surface declared in story 1.19 / architecture Hard
 * Invariants. The six keys below are the ONLY MDC keys this codebase is permitted to stamp; the
 * {@code LoggingMdcContractTest} enforces zero drift via case-exact string match.
 *
 * <p>All MDC writes MUST route through the helpers exposed here so a copy-paste typo cannot
 * silently introduce a key like {@code correlation_id} or {@code CorrelationId}. The exact
 * camelCase strings are duplicated in {@code logback-spring.xml}'s pattern converters and in the
 * {@code RedactingJsonProvider}; the {@code LoggingFieldNameContractTest} pins both surfaces.
 *
 * <p>Story 2.9 added {@link #APPROVAL_ID} so {@code ApprovalService.approveSpec} log lines and the
 * persistence-adapter writer carry the {@code apr_…} public id alongside the existing {@code
 * workflowRunId} / {@code artifactId} keys.
 */
public final class MdcKeys {

  public static final String CORRELATION_ID = "correlationId";
  public static final String WORKFLOW_RUN_ID = "workflowRunId";
  public static final String RUNNER_EXECUTION_ID = "runnerExecutionId";
  public static final String ARTIFACT_ID = "artifactId";
  public static final String ARTIFACT_OPERATION_ID = "artifactOperationId";
  public static final String APPROVAL_ID = "approvalId";
  // Story 3.17b — the RunnerWorkerPool worker-thread lease holder, carried on every worker-loop +
  // dispatch log so a multi-worker pool's lines are attributable.
  public static final String WORKER_ID = "workerId";

  public static final Set<String> ALL =
      Set.of(
          CORRELATION_ID,
          WORKFLOW_RUN_ID,
          RUNNER_EXECUTION_ID,
          ARTIFACT_ID,
          ARTIFACT_OPERATION_ID,
          APPROVAL_ID,
          WORKER_ID);

  private MdcKeys() {
    throw new AssertionError("no instances");
  }

  public static String sanitizeForLog(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("[\\r\\n\\t]", "_");
  }

  /**
   * Stamp the key, run the action, restore the prior value (or remove if absent) in a finally
   * block. Sanitises the value for log injection before writing.
   */
  public static void withKey(String key, String value, Runnable action) {
    String prior = beginScope(key, value);
    try {
      action.run();
    } finally {
      endScope(key, prior);
    }
  }

  /** Returning variant of {@link #withKey(String, String, Runnable)}. */
  public static <T> T withKey(String key, String value, Supplier<T> action) {
    String prior = beginScope(key, value);
    try {
      return action.get();
    } finally {
      endScope(key, prior);
    }
  }

  /**
   * Push an MDC value and return the prior value so the caller can restore it in a finally block.
   * Lower-level than {@link #withKey} for cases where the surrounding try/finally is already
   * structured for other reasons (e.g., transactional bookkeeping).
   *
   * <p>If {@code value} is {@code null} or empty (after trim), the scope is a no-op write — the
   * prior value is returned without modification so {@link #endScope} can restore it exactly. This
   * avoids silently inheriting a parent scope's value while still allowing the caller's finally to
   * be uniform. See D4 of the story 1.19 review.
   *
   * <p>Returns the prior MDC value to pass back into {@link #endScope}.
   */
  public static String beginScope(String key, String value) {
    String prior = MDC.get(key);
    String sanitized = sanitizeForLog(value);
    if (sanitized != null && !sanitized.isEmpty()) {
      MDC.put(key, sanitized);
    }
    return prior;
  }

  /** End an MDC scope started by {@link #beginScope}, restoring the prior value if any. */
  public static void endScope(String key, String prior) {
    if (prior == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, prior);
    }
  }
}
