package org.dradgo.adapters.cli;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RetryRecoveryResult;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(name = "workflow", description = "Workflow commands", prefix = "deliveryline")
public class WorkflowCommands {

  private static final Logger log = LoggerFactory.getLogger(WorkflowCommands.class);

  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";

  private final WorkflowCommandService workflowCommandService;
  private final WorkflowInspectionService workflowInspectionService;
  private final WorkflowCommandOutputs outputs;
  private final BooleanSupplier interactivityDetector;
  private final Supplier<String> generatedKeySupplier;
  private final Supplier<String> correlationIdSupplier;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final RecoveryService recoveryService;

  @Autowired
  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector cliInteractivityDetector,
      UuidV7Generator uuidV7Generator,
      IdempotencyKeyValidator idempotencyKeyValidator,
      RecoveryService recoveryService) {
    this(
        workflowCommandService,
        workflowInspectionService,
        outputs,
        cliInteractivityDetector::isInteractive,
        uuidV7Generator::generate,
        uuidV7Generator::generate,
        idempotencyKeyValidator,
        recoveryService);
  }

  /**
   * Three-arg constructor kept for backward compatibility with the existing {@link
   * org.dradgo.adapters.cli.WorkflowCommandsTest} unit tests. The {@code submit}-only path does not
   * need the inspection or recovery services, so callers can pass nulls as long as they only invoke
   * {@link #submit}.
   */
  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      BooleanSupplier interactivityDetector,
      Supplier<String> generatedKeySupplier) {
    this(
        workflowCommandService,
        null,
        null,
        interactivityDetector,
        generatedKeySupplier,
        generatedKeySupplier,
        new IdempotencyKeyValidator(),
        null);
  }

  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      BooleanSupplier interactivityDetector,
      Supplier<String> generatedKeySupplier,
      Supplier<String> correlationIdSupplier,
      IdempotencyKeyValidator idempotencyKeyValidator,
      RecoveryService recoveryService) {
    this.workflowCommandService = workflowCommandService;
    this.workflowInspectionService = workflowInspectionService;
    this.outputs = outputs;
    this.interactivityDetector = interactivityDetector;
    this.generatedKeySupplier = generatedKeySupplier;
    this.correlationIdSupplier = correlationIdSupplier;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
    this.recoveryService = recoveryService;
  }

  @Command(
      name = "submit",
      description = "Submit a workflow ticket for governed execution",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String submit(
      @Option(longName = "ticket", description = "Linear ticket reference", required = true)
          String linearTicketReference,
      @Option(longName = "actor-identity", description = "Actor identity", required = true)
          String actorIdentity,
      @Option(longName = "actor-type", description = "Actor type", required = true)
          ActorType actorType,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    String runId = null;
    try {
      String resolvedIdempotencyKey = resolveIdempotencyKey(idempotencyKey);
      SubmitWorkflowResult result =
          workflowCommandService.submit(
              new SubmitWorkflowCommand(
                  actorIdentity,
                  actorType,
                  resolvedIdempotencyKey,
                  correlationId,
                  linearTicketReference));
      runId = result.workflowRunId();
      String output =
          result.workflowRunId() + " submitted (state: " + result.currentState().value() + ")";
      if (idempotencyKey == null) {
        output += " [generated-idempotency-key: " + resolvedIdempotencyKey + "]";
      }
      if (verbose) {
        output += " [correlation-id: " + resolvedCorrelation + "]";
      }
      emitSuccess("workflow submit", runId, resolvedCorrelation, start);
      return output;
    } catch (DomainException de) {
      emitFailure("workflow submit", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow submit", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "status",
      description = "Print the current state of a governed workflow run",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String status(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              required = false,
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireInspectionWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      WorkflowStatusView view = workflowInspectionService.getStatus(runId);
      String rendered = renderStatus(view, format);
      if (verbose) {
        rendered = appendCorrelationSuffix(rendered, resolvedCorrelation);
      }
      emitSuccess("workflow status", runId, resolvedCorrelation, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure("workflow status", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow status", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "retry",
      description =
          "Retry the last failed step of a Failed governed workflow run. Deeper recovery (reconcile, take over, rerun-from-arbitrary-step, failure-taxonomy classification, operator console) arrives in a later epic.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String retry(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(longName = "actor-identity", description = "Actor identity", required = true)
          String actorIdentity,
      @Option(longName = "actor-type", description = "Actor type", required = true)
          ActorType actorType,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "reason",
              description = "Operator-supplied reason text (optional)",
              required = false)
          String reason,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireRecoveryWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      ActorContext actor = new ActorContext(actorIdentity, actorType, resolvedCorrelation);
      RetryRecoveryResult result =
          recoveryService.retry(runId, resolvedIdempotencyKey, actor, reason);
      StringBuilder output = new StringBuilder();
      output.append(result.recoveryActionPublicId()).append(" retry submitted (state: Executing)");
      if (result.newRunnerExecutionPublicId() != null) {
        output
            .append(" [runner-execution: ")
            .append(result.newRunnerExecutionPublicId())
            .append(']');
      } else if (result.replayed()) {
        output.append(" [replayed]");
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
        if (result.recoveryRetriedEventPublicId() != null) {
          output
              .append(" [recovery-event: ")
              .append(result.recoveryRetriedEventPublicId())
              .append(']');
        }
        if (idempotencyKey == null) {
          output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
        }
      }
      emitSuccess("workflow retry", runId, resolvedCorrelation, start);
      if (reason != null && !reason.isBlank()) {
        log.info(
            "workflow retry operator reason supplied correlationId={} workflowRunId={} reasonLength={}",
            resolvedCorrelation,
            runId,
            reason.length());
      }
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow retry", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow retry", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "history",
      description = "Print the chronological event history of a governed workflow run",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String history(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              required = false,
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "since",
              description = "ISO-8601 timestamp; only show events with created_at >= since",
              required = false)
          String since,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireInspectionWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      OffsetDateTime sinceInclusive = parseSince(since);
      WorkflowHistoryView view = workflowInspectionService.listHistory(runId, sinceInclusive);
      String rendered = renderHistory(view, format);
      if (verbose) {
        rendered = appendCorrelationSuffix(rendered, resolvedCorrelation);
      }
      emitSuccess("workflow history", runId, resolvedCorrelation, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure("workflow history", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow history", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  private OffsetDateTime parseSince(String since) {
    if (since == null || since.isBlank()) {
      return null;
    }
    OffsetDateTime parsed;
    try {
      parsed = OffsetDateTime.parse(since);
    } catch (DateTimeParseException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("since", since);
      details.put("rule", "ISO-8601 with explicit zone (e.g. 2026-05-13T09:00:00Z)");
      throw new DomainException(
          DomainErrorCode.INVALID_TIME_RANGE, "Invalid --since timestamp: " + since, details);
    }
    if (parsed.isAfter(OffsetDateTime.now())) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("since", since);
      details.put("rule", "must be at or before the current time");
      throw new DomainException(
          DomainErrorCode.INVALID_TIME_RANGE,
          "Invalid --since timestamp (in the future): " + since,
          details);
    }
    return parsed;
  }

  private String resolveIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey != null) {
      return idempotencyKey;
    }
    if (interactivityDetector.getAsBoolean()) {
      return generatedKeySupplier.get();
    }
    throw idempotencyKeyValidator.missingKeyException();
  }

  private static String appendCorrelationSuffix(String rendered, String correlationId) {
    String suffix = "[correlation-id: " + correlationId + "]";
    if (rendered == null || rendered.isEmpty()) {
      return suffix;
    }
    if (rendered.endsWith("\n")) {
      return rendered + suffix;
    }
    return rendered + " " + suffix;
  }

  /**
   * Resolve the caller-supplied correlation id (or mint a fresh UUIDv7), sanitise for log
   * injection, and stamp it on MDC via {@link MdcKeys#beginScope} so the prior value can be
   * restored in a finally block. Callers must invoke {@code
   * MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior())} in their finally — this preserves any
   * outer scope's correlation id when the CLI is used as a library, instead of the previous blanket
   * {@code MDC.remove} which destroyed nested scopes. See P5 of the story 1.19 review.
   */
  private CorrelationScope pushCorrelation(String supplied) {
    String resolved = supplied;
    if (resolved == null || resolved.isBlank()) {
      resolved = correlationIdSupplier.get();
    }
    // Strip CR/LF/TAB to prevent log injection through MDC and SLF4J interpolation —
    // a value like `abc\nworkflow command completed correlationId=fake outcome=success`
    // would otherwise forge a synthetic completion line in the structured log stream.
    resolved = MdcKeys.sanitizeForLog(resolved);
    String prior = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, resolved);
    return new CorrelationScope(resolved, prior);
  }

  record CorrelationScope(String resolved, String prior) {}

  private void requireInspectionWired() {
    if (workflowInspectionService == null || outputs == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_inspection_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed with the legacy submit-only constructor; "
              + "inject WorkflowInspectionService and WorkflowCommandOutputs to use status/history",
          details);
    }
  }

  private void requireRecoveryWired() {
    if (recoveryService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_retry_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without RecoveryService; inject RecoveryService to use retry",
          details);
    }
  }

  private String renderStatus(WorkflowStatusView view, String format) {
    return switch (normalizeFormat(format)) {
      case FORMAT_JSON -> outputs.renderStatusJson(view);
      case FORMAT_TEXT -> outputs.renderStatusText(view);
      default -> throw unsupportedFormat(format);
    };
  }

  private String renderHistory(WorkflowHistoryView view, String format) {
    return switch (normalizeFormat(format)) {
      case FORMAT_JSON -> outputs.renderHistoryJson(view);
      case FORMAT_TEXT -> outputs.renderHistoryText(view);
      default -> throw unsupportedFormat(format);
    };
  }

  private String normalizeFormat(String format) {
    if (format == null || format.isBlank()) {
      return FORMAT_TEXT;
    }
    String normalized = format.trim().toLowerCase(Locale.ROOT);
    if (FORMAT_TEXT.equals(normalized) || FORMAT_JSON.equals(normalized)) {
      return normalized;
    }
    throw unsupportedFormat(format);
  }

  private DomainException unsupportedFormat(String format) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("format", format);
    details.put("supportedFormats", java.util.List.of(FORMAT_TEXT, FORMAT_JSON));
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Unsupported --format value: " + format, details);
  }

  private static void emitSuccess(
      String commandName, String runId, String correlationId, long startNanos) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "workflow command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        commandName,
        runId,
        OUTCOME_SUCCESS,
        elapsedMs);
  }

  private static void emitFailure(
      String commandName, String runId, String correlationId, long startNanos, String code) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "workflow command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        commandName,
        runId,
        code,
        elapsedMs);
  }

  private static String codeFor(DomainException de) {
    return de.errorCode() == null
        ? OUTCOME_UNKNOWN
        : OUTCOME_FAILURE_PREFIX + de.errorCode().value();
  }
}
