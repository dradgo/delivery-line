package org.dradgo.application.runner;

import java.nio.charset.StandardCharsets;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.runner.spi.RunnerLogStore;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.application.security.RedactionResult;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DataClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Story 3.6 AC1/AC2/AC4 — host-side capture path for raw runner stdout/stderr.
 *
 * <p>{@link #captureLogs} <b>redacts each stream first</b> via {@link RedactionPolicyService}, then
 * writes the redacted bytes to the durable {@link RunnerLogStore} ({@code runner-logs/{rex}/}) and
 * returns a content-free {@link CapturedLogs} carrying the reference path + metrics. The raw {@code
 * stdout}/{@code stderr} strings are method-local ONLY — never stored in a field, never written
 * un-redacted, never returned (Trap T1). This is the single redaction entry point story 3.5 AC5
 * handed to this story for the host-side log-capture path.
 *
 * <p>Credential detection is delegated entirely to {@code application.security} ({@link
 * RedactionPolicyService}) — this service only orchestrates capture/write and counts redaction
 * placeholders (Trap T2 / OQ-1). It declares no credential regex (ArchUnit AC8).
 */
@Service
public class RunnerLogCaptureService {

  private static final Logger log = LoggerFactory.getLogger(RunnerLogCaptureService.class);

  /**
   * Redaction placeholder prefix emitted by {@code SensitivePayloadAnalyzer} ({@code
   * [REDACTED_<CATEGORY>]}). OQ-1/Trap T2: {@link RedactionResult} exposes no occurrence count, so
   * {@code redaction_count} is derived by counting this literal token across both redacted streams.
   * This is an exact-substring count (no credential regex), so it does not violate the
   * credential-detection-in-application.security ArchUnit rule.
   */
  static final String REDACTION_PLACEHOLDER_PREFIX = "[REDACTED_";

  public enum RunnerLogTruncation {
    NONE,
    STDOUT,
    STDERR,
    BOTH;

    boolean any() {
      return this != NONE;
    }
  }

  private final RedactionPolicyService redactionPolicyService;
  private final RunnerLogStore runnerLogStore;
  private final RunnerProperties runnerProperties;

  public RunnerLogCaptureService(
      RedactionPolicyService redactionPolicyService,
      RunnerLogStore runnerLogStore,
      RunnerProperties runnerProperties) {
    this.redactionPolicyService = redactionPolicyService;
    this.runnerLogStore = runnerLogStore;
    this.runnerProperties = runnerProperties;
  }

  /**
   * Redact {@code stdout}/{@code stderr}, persist the redacted streams to {@code
   * runner-logs/{rex}/}, and return the typed capture result. Null streams are treated as empty.
   */
  public CapturedLogs captureLogs(
      String runnerExecutionId, String workflowRunId, String stdout, String stderr) {
    return captureLogs(runnerExecutionId, workflowRunId, stdout, stderr, RunnerLogTruncation.NONE);
  }

  public CapturedLogs captureLogs(
      String runnerExecutionId,
      String workflowRunId,
      String stdout,
      String stderr,
      RunnerLogTruncation truncation) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    RunnerLogTruncation effectiveTruncation =
        truncation == null ? RunnerLogTruncation.NONE : truncation;
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, workflowRunId);
    String priorRexMdc = MdcKeys.beginScope(MdcKeys.RUNNER_EXECUTION_ID, runnerExecutionId);
    try {
      String rawStdout = stdout == null ? "" : stdout;
      String rawStderr = stderr == null ? "" : stderr;
      if (rawStdout.isEmpty() && rawStderr.isEmpty()) {
        log.warn(
            "runner log capture found empty source streams runnerExecutionId={}",
            runnerExecutionId);
      }
      log.info("runner log capture start runnerExecutionId={}", runnerExecutionId);

      // Trap T1: redaction is the FIRST action on each stream; only sanitized text is ever written.
      RedactionResult stdoutResult =
          redactionPolicyService.redact(rawStdout, DataClassification.LOCAL_ONLY.value());
      RedactionResult stderrResult =
          redactionPolicyService.redact(rawStderr, DataClassification.LOCAL_ONLY.value());

      byte[] redactedStdout = stdoutResult.sanitizedText().getBytes(StandardCharsets.UTF_8);
      byte[] redactedStderr = stderrResult.sanitizedText().getBytes(StandardCharsets.UTF_8);

      int redactionCount =
          countPlaceholders(stdoutResult.sanitizedText())
              + countPlaceholders(stderrResult.sanitizedText());
      DataClassification classification = classify(stdoutResult, stderrResult, effectiveTruncation);

      RunnerLogReference written =
          runnerLogStore.write(runnerExecutionId, redactedStdout, redactedStderr);

      CapturedLogs captured =
          new CapturedLogs(
              written.referencePath(), written.byteSize(), classification, redactionCount);
      log.info(
          "runner log capture finished runnerExecutionId={} redactionCount={} byteSize={} classification={}",
          runnerExecutionId,
          redactionCount,
          captured.byteSize(),
          classification.value());
      return captured;
    } finally {
      MdcKeys.endScope(MdcKeys.RUNNER_EXECUTION_ID, priorRexMdc);
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  /**
   * AC4 / Trap T3: classification defaults to {@code local-only}. It is elevated to {@code
   * shareable-redacted} ONLY when the redaction pass detected zero secrets across BOTH streams AND
   * the operator opted in via {@code deliveryline.runner.allow-shareable-logs=true}. The {@link
   * RedactionResult#effectiveClassification()} is intentionally NOT reused here — AC4 is a
   * narrower, separate decision.
   */
  private DataClassification classify(
      RedactionResult stdoutResult, RedactionResult stderrResult, RunnerLogTruncation truncation) {
    if (truncation.any()) {
      return DataClassification.LOCAL_ONLY;
    }
    boolean noSecrets =
        stdoutResult.detectedCategories().isEmpty() && stderrResult.detectedCategories().isEmpty();
    return (noSecrets && runnerProperties.allowShareableLogs())
        ? DataClassification.SHAREABLE_REDACTED
        : DataClassification.LOCAL_ONLY;
  }

  private static int countPlaceholders(String sanitizedText) {
    if (sanitizedText == null || sanitizedText.isEmpty()) {
      return 0;
    }
    int count = 0;
    int from = 0;
    while (true) {
      int index = sanitizedText.indexOf(REDACTION_PLACEHOLDER_PREFIX, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + REDACTION_PLACEHOLDER_PREFIX.length();
    }
  }
}
