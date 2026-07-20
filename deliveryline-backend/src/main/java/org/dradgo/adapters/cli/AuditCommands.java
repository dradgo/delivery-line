package org.dradgo.adapters.cli;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.dradgo.application.audit.AuditQueryService;
import org.dradgo.application.audit.AuditQueryService.AuditQueryFilter;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Story 4.3 (AC4/AC5/AC10) — {@code deliveryline audit query} CLI surface: a flat, filterable,
 * cursor-paginated audit-history stream over one run ({@code --run}) or a whole ticket ({@code
 * --ticket}). A thin adapter — it only parses flags → builds an {@link AuditQueryFilter} → calls
 * {@link AuditQueryService#queryByRun}/{@link AuditQueryService#queryByTicket} → renders via {@link
 * WorkflowCommandOutputs} → emits the AC10 completion log. ALL event-type/time/limit/cursor
 * resolution lives in the service (AC9).
 *
 * <p><b>Command name.</b> Spring Shell 4.0.2 composes a command's registered name as {@code
 * groupPrefix + " " + @Command.name} — the {@code @CommandGroup.name} is help-categorization only
 * (proven by {@code OperatorCliCommandRegistrationIT}). So to register exactly {@code deliveryline
 * audit query} (AC4) the group {@code prefix} carries {@code "deliveryline audit"} and the command
 * {@code name} is {@code "query"} (the story's naive {@code prefix="deliveryline"} would
 * mis-register as {@code deliveryline query}). Pinned by {@code AuditCliCommandRegistrationIT}.
 *
 * <p><b>{@code --ticket} / {@code --run} are mutually exclusive</b> — no Spring Shell XOR
 * construct; the guard mirrors {@code WorkflowCommands.parseBatchTickets} ({@code hasTicket ==
 * hasRun} → {@code INVALID_COMMAND_PAYLOAD}, story 4.3 Reconciliation 11).
 */
@Component
@CommandGroup(name = "audit", description = "Audit history query", prefix = "deliveryline audit")
public class AuditCommands {

  private static final Logger log = LoggerFactory.getLogger(AuditCommands.class);

  private static final String COMMAND_NAME = "audit query";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";

  private final AuditQueryService auditQueryService;
  private final WorkflowCommandOutputs outputs;
  private final CliInteractivityDetector interactivity;
  private final Supplier<String> correlationIdSupplier;

  @Autowired
  public AuditCommands(
      AuditQueryService auditQueryService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      UuidV7Generator uuidV7Generator) {
    this(auditQueryService, outputs, interactivity, uuidV7Generator::generate);
  }

  AuditCommands(
      AuditQueryService auditQueryService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      Supplier<String> correlationIdSupplier) {
    this.auditQueryService = Objects.requireNonNull(auditQueryService, "auditQueryService");
    this.outputs = Objects.requireNonNull(outputs, "outputs");
    this.interactivity = Objects.requireNonNull(interactivity, "interactivity");
    this.correlationIdSupplier =
        Objects.requireNonNull(correlationIdSupplier, "correlationIdSupplier");
  }

  @Command(
      name = "query",
      description =
          "Query audit history for exactly one of --ticket (all runs of a ticket) or --run (a"
              + " single run). Filter with --event-type (comma-separated), --actor, --since/--until"
              + " (ISO-8601), --limit, --cursor. --format=json emits a stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String query(
      @Option(longName = "ticket", description = "Ticket external ref (e.g. LIN-123). XOR --run.")
          String ticket,
      @Option(longName = "run", description = "Run public id (run_...). XOR --ticket.") String run,
      @Option(
              longName = "event-type",
              description = "Comma-separated event-type wire values (e.g. workflow.stateChanged)")
          String eventType,
      @Option(longName = "actor", description = "Filter to a specific actor identity") String actor,
      @Option(
              longName = "since",
              description = "Lower time bound, ISO-8601 (e.g. 2026-07-01T00:00:00Z)")
          String since,
      @Option(longName = "until", description = "Upper time bound, ISO-8601") String until,
      @Option(
              longName = "limit",
              description = "Maximum events per page (default 50, max 200)",
              defaultValue = "50")
          int limit,
      @Option(longName = "cursor", description = "Opaque keyset cursor from a prior nextCursor")
          String cursor,
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
    String scopeLabel = "unknown";
    String scopeRef = null;
    int resultCount = -1;
    try {
      // Parse INSIDE the try so a rejected filter still emits the AC10 completion log AND runs the
      // finally/endScope — otherwise the MDC correlation scope would leak into the next command.
      boolean json = isJson(format);
      boolean hasTicket = ticket != null && !ticket.isBlank();
      boolean hasRun = run != null && !run.isBlank();
      if (hasTicket == hasRun) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rule", "provide exactly one of --ticket / --run");
        throw new DomainException(
            DomainErrorCode.INVALID_COMMAND_PAYLOAD,
            "Provide exactly one of --ticket or --run",
            details);
      }
      OffsetDateTime sinceInstant = parseInstant(since, "since");
      OffsetDateTime untilInstant = parseInstant(until, "until");
      AuditQueryFilter filter =
          new AuditQueryFilter(
              splitEventTypes(eventType), actor, sinceInstant, untilInstant, limit, cursor);

      AuditQueryResult result;
      if (hasTicket) {
        scopeLabel = "ticket";
        scopeRef = ticket.trim();
        result = auditQueryService.queryByTicket(scopeRef, filter);
      } else {
        scopeLabel = "run";
        scopeRef = run.trim();
        result = auditQueryService.queryByRun(scopeRef, filter);
      }
      resultCount = result.events().size();

      boolean ansi = !json && interactivity.isInteractive();
      String rendered =
          json ? outputs.renderAuditQueryJson(result) : outputs.renderAuditQueryText(result, ansi);
      if (verbose) {
        rendered = rendered + System.lineSeparator() + "correlationId=" + resolvedCorrelation;
      }
      emit(resolvedCorrelation, scopeLabel, scopeRef, resultCount, start, OUTCOME_SUCCESS);
      return rendered;
    } catch (DomainException de) {
      emit(resolvedCorrelation, scopeLabel, scopeRef, resultCount, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emit(resolvedCorrelation, scopeLabel, scopeRef, resultCount, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

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

  // ISO-8601 absolute timestamp (mirrors the sibling `workflow history --since`, story 4.3
  // Reconciliation 12). A malformed value is a rejected filter → INVALID_AUDIT_FILTER.
  private OffsetDateTime parseInstant(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (DateTimeParseException badTimestamp) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put(field, raw);
      throw new DomainException(
          DomainErrorCode.INVALID_AUDIT_FILTER,
          "Invalid --" + field + " timestamp (expected ISO-8601): " + raw,
          details);
    }
  }

  private static List<String> splitEventTypes(String eventType) {
    List<String> tokens = new ArrayList<>();
    if (eventType == null || eventType.isBlank()) {
      return tokens;
    }
    for (String token : eventType.split(",")) {
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

  // Story 4.3 (AC10) — structured completion log. No single runId (by-ticket spans runs), so emit
  // the resolved scope + ref + resultCount. User-supplied values are CR/LF-sanitized.
  private static void emit(
      String correlationId, String scope, String ref, int resultCount, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "audit command completed correlationId={} commandName={} scope={} ref={} resultCount={}"
            + " outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME,
        scope,
        MdcKeys.sanitizeForLog(ref),
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
