package org.dradgo.adapters.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunFilter;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Story 4.1 (AC1/AC4/AC7/AC9) — {@code deliveryline operator status} CLI surface: a fleet view of
 * all runs in non-happy operator states across all workflows (the CLI analogue of {@code
 * deliveryline workers status}, extending {@code deliveryline status}'s per-run view). A thin
 * adapter — it only parses flags → builds an {@link OperatorRunFilter} of raw values → calls {@link
 * WorkflowInspectionService#getOperatorRunSummary} → renders via {@link WorkflowCommandOutputs} →
 * emits the AC7 completion log. ALL token/duration/limit resolution + histogram assembly lives in
 * the service (AC9).
 *
 * <p><b>Command name (Reconciliation 10, corrected).</b> Spring Shell 4.0.2 composes a command's
 * registered name as {@code groupPrefix + " " + @Command.name} — the {@code @CommandGroup.name} is
 * used ONLY for help categorization, NOT the path (see {@code CommandFactoryBean#getObject}). So
 * mirroring {@code WorkerCommands}' {@code prefix="deliveryline"} + {@code name="status"} would
 * register as {@code deliveryline status}, colliding with {@code WorkflowCommands.status}. To
 * register exactly {@code deliveryline operator status} (AC1) the group {@code prefix} carries
 * {@code "deliveryline operator"}. Pinned by {@code OperatorCliCommandRegistrationIT}.
 */
@Component
@CommandGroup(
    name = "operator",
    description = "Operator fleet inspection",
    prefix = "deliveryline operator")
public class OperatorCommands {

  private static final Logger log = LoggerFactory.getLogger(OperatorCommands.class);

  private static final String COMMAND_NAME = "operator status";
  private static final String COMMAND_NAME_DIAGNOSE = "operator diagnose";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";
  private static final String DEFAULT_STATE = "failed,stalled,orphaned";

  private final WorkflowInspectionService inspection;
  private final WorkflowCommandOutputs outputs;
  private final CliInteractivityDetector interactivity;
  private final Supplier<String> correlationIdSupplier;

  @Autowired
  public OperatorCommands(
      WorkflowInspectionService inspection,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      UuidV7Generator uuidV7Generator) {
    this(inspection, outputs, interactivity, uuidV7Generator::generate);
  }

  OperatorCommands(
      WorkflowInspectionService inspection,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      Supplier<String> correlationIdSupplier) {
    this.inspection = Objects.requireNonNull(inspection, "inspection");
    this.outputs = Objects.requireNonNull(outputs, "outputs");
    this.interactivity = Objects.requireNonNull(interactivity, "interactivity");
    this.correlationIdSupplier =
        Objects.requireNonNull(correlationIdSupplier, "correlationIdSupplier");
  }

  @Command(
      name = "status",
      description =
          "Show all runs in non-happy operator states across all workflows with diagnostic"
              + " summaries. --state selects failed|stalled|orphaned|takenover|overridden;"
              + " --since 1h|24h|7d windows recent activity; --format=json emits a stable-schema"
              + " document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String status(
      @Option(
              longName = "state",
              description =
                  "Comma-separated operator states: failed,stalled,orphaned,takenover,overridden",
              defaultValue = DEFAULT_STATE)
          String state,
      @Option(
              longName = "since",
              description = "Relative activity window: 1h, 24h, 7d, 2w (minutes/hours/days/weeks)")
          String since,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "limit",
              description = "Maximum runs listed (default 100, max 500)",
              defaultValue = "100")
          int limit,
      @Option(longName = "correlation-id", description = "Correlation ID") String correlationId,
      @Option(
              longName = "verbose",
              description = "Append the resolved correlation id",
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    int total = -1;
    try {
      // Parse INSIDE the try so an unsupported --format (isJson throws INVALID_COMMAND_PAYLOAD)
      // still emits the AC7 completion log AND runs the finally/endScope — otherwise the MDC
      // correlation-id scope pushed above would leak into the next shell command.
      boolean json = isJson(format);
      List<String> stateTokens = splitStateTokens(state);
      OperatorRunFilter filter = new OperatorRunFilter(stateTokens, since, limit);
      OperatorRunSummary view = inspection.getOperatorRunSummary(filter);
      total = view.total();
      // ANSI only for an interactive TTY in text mode; JSON + non-TTY pipes stay plain (AC4).
      boolean ansi = !json && interactivity.isInteractive();
      String rendered =
          json
              ? outputs.renderOperatorSummaryJson(view)
              : outputs.renderOperatorSummaryText(view, ansi);
      if (verbose) {
        rendered = rendered + System.lineSeparator() + "correlationId=" + resolvedCorrelation;
      }
      emitSuccess(resolvedCorrelation, state, since, limit, total, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure(resolvedCorrelation, state, since, limit, total, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure(resolvedCorrelation, state, since, limit, total, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "diagnose",
      description =
          "Deep-dive failure diagnostics for a single run: the NFR7 five questions (what happened /"
              + " changed / who acted / what failed / what is next), correlation id, runner-log"
              + " reference, per-integration sync status, and recommended recovery actions ranked by"
              + " safety (green=safe, yellow=caution, red=risky). --format=json emits a"
              + " stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String diagnose(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(longName = "correlation-id", description = "Correlation ID") String correlationId,
      @Option(
              longName = "verbose",
              description = "Append the resolved correlation id",
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse INSIDE the try so an unsupported --format (INVALID_COMMAND_PAYLOAD) still emits the
      // completion log AND runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      WorkflowInspectionService.FailureDiagnostics view = inspection.getFailureDiagnostics(runId);
      boolean ansi = !json && interactivity.isInteractive();
      String rendered =
          json ? outputs.renderDiagnoseJson(view) : outputs.renderDiagnoseText(view, ansi);
      // Only append the verbose correlation-id footer to TEXT output — appending it to JSON would
      // glue a trailing "correlationId=..." line onto the document and break the stable
      // operator-diagnose.v1 schema for machine consumers.
      if (verbose && !json) {
        rendered = rendered + System.lineSeparator() + "correlationId=" + resolvedCorrelation;
      }
      emitDiagnose(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      return rendered;
    } catch (DomainException de) {
      emitDiagnose(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitDiagnose(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  private static void emitDiagnose(String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_DIAGNOSE,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Format is a RENDER choice made by this adapter (mirrors WorkflowCommands/DoctorCommands); an
  // unsupported value raises INVALID_COMMAND_PAYLOAD so the exit-status mapper applies. Token /
  // since / limit resolution stays in the service (AC9).
  private boolean isJson(String format) {
    if (format == null || format.isBlank()) {
      return false;
    }
    String normalized = format.trim().toLowerCase(Locale.ROOT);
    if (FORMAT_TEXT.equals(normalized)) {
      return false;
    }
    if (FORMAT_JSON.equals(normalized)) {
      return true;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("format", format);
    details.put("supportedFormats", List.of(FORMAT_TEXT, FORMAT_JSON));
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Unsupported --format value: " + format, details);
  }

  private static List<String> splitStateTokens(String state) {
    List<String> tokens = new ArrayList<>();
    if (state == null || state.isBlank()) {
      return tokens;
    }
    for (String token : state.split(",")) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        tokens.add(trimmed);
      }
    }
    return tokens;
  }

  private CorrelationScope pushCorrelation(String supplied) {
    String resolved = supplied;
    if (resolved == null || resolved.isBlank()) {
      resolved = correlationIdSupplier.get();
    }
    resolved = MdcKeys.sanitizeForLog(resolved);
    String prior = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, resolved);
    return new CorrelationScope(resolved, prior);
  }

  record CorrelationScope(String resolved, String prior) {}

  // Story 4.1 (AC7) — structured completion log. No runId here (multi-run read), so emit the
  // resolved filter (states/since/limit) + resultCount in place of workflowRunId. User-supplied
  // filter values are sanitized against CR/LF log injection.
  private static void emitSuccess(
      String correlationId, String states, String since, int limit, int resultCount, long start) {
    emit(correlationId, states, since, limit, resultCount, start, OUTCOME_SUCCESS);
  }

  private static void emitFailure(
      String correlationId,
      String states,
      String since,
      int limit,
      int resultCount,
      long start,
      String outcome) {
    emit(correlationId, states, since, limit, resultCount, start, outcome);
  }

  private static void emit(
      String correlationId,
      String states,
      String since,
      int limit,
      int resultCount,
      long start,
      String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} states={} since={} limit={} resultCount={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME,
        MdcKeys.sanitizeForLog(states),
        MdcKeys.sanitizeForLog(since),
        limit,
        resultCount,
        outcome,
        elapsedMs);
  }

  private static String codeFor(DomainException de) {
    return de.errorCode() == null
        ? OUTCOME_UNKNOWN
        : OUTCOME_FAILURE_PREFIX + de.errorCode().value();
  }
}
