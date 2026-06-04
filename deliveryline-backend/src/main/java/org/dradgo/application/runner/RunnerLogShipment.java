package org.dradgo.application.runner;

import java.nio.charset.StandardCharsets;
import org.dradgo.domain.registry.DataClassification;

/**
 * Story 3.7 (Decision D5) — the unit of work handed to {@link RunnerLogShippingService}: a single
 * runner execution's already-redacted stdout/stderr plus the authoritative {@link
 * DataClassification} (read from the {@code runner_executions} row / {@link RunnerLogReference},
 * never inferred from the file). The shipping service decides — via {@link
 * RunnerLogShippingPolicy#isShippable} — whether this reaches the observability ingest at all.
 *
 * <p>Content is ALREADY redacted by {@code RunnerLogCaptureService}; this record never carries raw
 * streams and is never logged (Trap T1 — only metadata leaves the shipping service).
 */
public record RunnerLogShipment(
    String runnerExecutionId,
    String workflowRunId,
    DataClassification classification,
    String redactedStdout,
    String redactedStderr) {

  public RunnerLogShipment {
    redactedStdout = redactedStdout == null ? "" : redactedStdout;
    redactedStderr = redactedStderr == null ? "" : redactedStderr;
  }

  /** Combined UTF-8 byte size of both redacted streams — logged as shipping metadata. */
  public long byteSize() {
    return (long) redactedStdout.getBytes(StandardCharsets.UTF_8).length
        + (long) redactedStderr.getBytes(StandardCharsets.UTF_8).length;
  }
}
