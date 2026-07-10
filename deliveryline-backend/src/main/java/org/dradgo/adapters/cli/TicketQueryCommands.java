package org.dradgo.adapters.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.integration.ticketsource.TicketQueryService;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.TicketQuery;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Story 3i-2 (AC3) — {@code deliveryline tickets query} CLI surface: the filtered candidate-ticket
 * browse at CLI parity with the REST intake endpoint. A thin adapter — it only parses flags →
 * builds a {@link TicketQuery} → calls {@link TicketQueryService#queryCandidateTickets}
 * <b>in-process</b> (never over REST) → renders via {@link WorkflowCommandOutputs} → emits the
 * completion log.
 *
 * <p><b>Command name.</b> Spring Shell 4.0.2 composes a command's registered name as {@code
 * groupPrefix + " " + @Command.name} — {@code @CommandGroup.name} is help-categorization only. So
 * to register exactly {@code deliveryline tickets query} the group {@code prefix} carries {@code
 * "deliveryline tickets"} and the command {@code name} is {@code "query"}. Pinned by {@code
 * TicketQueryCliCommandRegistrationIT}.
 *
 * <p><b>Redaction.</b> The completion log carries counts/flags only — never the assignee, component
 * or state filter values, and never a returned ticket's title/summary (story 3i-2 AC7).
 */
@Component
@CommandGroup(
    name = "tickets",
    description = "Ticket intake browse",
    prefix = "deliveryline tickets")
public class TicketQueryCommands {

  private static final Logger log = LoggerFactory.getLogger(TicketQueryCommands.class);

  private static final String COMMAND_NAME = "tickets query";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";

  private final TicketQueryService ticketQueryService;
  private final WorkflowCommandOutputs outputs;
  private final CliInteractivityDetector interactivity;
  private final Supplier<String> correlationIdSupplier;

  @Autowired
  public TicketQueryCommands(
      TicketQueryService ticketQueryService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      UuidV7Generator uuidV7Generator) {
    this(ticketQueryService, outputs, interactivity, uuidV7Generator::generate);
  }

  TicketQueryCommands(
      TicketQueryService ticketQueryService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      Supplier<String> correlationIdSupplier) {
    this.ticketQueryService = Objects.requireNonNull(ticketQueryService, "ticketQueryService");
    this.outputs = Objects.requireNonNull(outputs, "outputs");
    this.interactivity = Objects.requireNonNull(interactivity, "interactivity");
    this.correlationIdSupplier =
        Objects.requireNonNull(correlationIdSupplier, "correlationIdSupplier");
  }

  @Command(
      name = "query",
      description =
          "Browse candidate tickets from a project's ticket source. Filter with --assignee,"
              + " --components (comma-separated), --state; bound with --limit. Only connectors that"
              + " advertise the ticket-query capability (today: JIRA) can be browsed."
              + " --format=json emits a stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String query(
      @Option(longName = "project", description = "Project public id (prj_...)", required = true)
          String project,
      @Option(
              longName = "assignee",
              description =
                  "Source assignee identity (JIRA Cloud: an accountId, or an email the instance"
                      + " resolves). Opaque — passed to the source verbatim.")
          String assignee,
      @Option(
              longName = "components",
              description = "Comma-separated component names; a ticket matching any is returned")
          String components,
      @Option(longName = "state", description = "Source workflow-state name, e.g. \"To Do\"")
          String state,
      @Option(
              longName = "limit",
              description = "Maximum tickets to return (default 50, max 200)",
              defaultValue = "" + TicketQuery.DEFAULT_LIMIT)
          int limit,
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
    int resultCount = -1;
    try {
      // Parse INSIDE the try so a rejected filter still emits the completion log AND runs the
      // finally/endScope — otherwise the MDC correlation scope would leak into the next command.
      boolean json = isJson(format);
      requireProject(project);
      requireLimitInRange(limit);
      List<String> componentTokens = splitComponents(components);
      requireComponentCountInRange(componentTokens);

      TicketQueryResult result =
          ticketQueryService.queryCandidateTickets(
              project.trim(), new TicketQuery(assignee, componentTokens, state, limit));
      resultCount = result.tickets().size();

      boolean ansi = !json && interactivity.isInteractive();
      String rendered =
          json
              ? outputs.renderTicketQueryJson(result)
              : outputs.renderTicketQueryText(result, ansi);
      if (verbose) {
        rendered = rendered + System.lineSeparator() + "correlationId=" + resolvedCorrelation;
      }
      emit(resolvedCorrelation, project, resultCount, start, OUTCOME_SUCCESS);
      return rendered;
    } catch (DomainException de) {
      emit(resolvedCorrelation, project, resultCount, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emit(resolvedCorrelation, project, resultCount, start, OUTCOME_UNKNOWN);
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

  private static void requireProject(String project) {
    if (project != null && !project.isBlank()) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("option", "project");
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "--project is required", details);
  }

  /**
   * Reject an out-of-range limit as a typed INVALID_COMMAND_PAYLOAD before it reaches the {@link
   * TicketQuery} compact constructor, whose IllegalArgumentException would exit through the
   * mapper's generic 401 internal-error bucket rather than the 101 invalid-input one.
   */
  private static void requireLimitInRange(int limit) {
    if (limit >= 1 && limit <= TicketQuery.MAX_LIMIT) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("option", "limit");
    details.put("rejectedValue", limit);
    details.put("min", 1);
    details.put("max", TicketQuery.MAX_LIMIT);
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "--limit must be between 1 and " + TicketQuery.MAX_LIMIT,
        details);
  }

  /**
   * Reject an over-large component filter as a typed INVALID_COMMAND_PAYLOAD. Every token is
   * rendered into the source's query string, so an unbounded set is an unbounded request. The
   * {@link TicketQuery} compact constructor throws for the same condition; this guard keeps it out
   * of the mapper's generic internal-error bucket.
   */
  private static void requireComponentCountInRange(List<String> components) {
    if (components.size() <= TicketQuery.MAX_COMPONENTS) {
      return;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("option", "components");
    details.put("rejectedCount", components.size());
    details.put("max", TicketQuery.MAX_COMPONENTS);
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "--components must not exceed " + TicketQuery.MAX_COMPONENTS + " values",
        details);
  }

  private static List<String> splitComponents(String components) {
    List<String> tokens = new ArrayList<>();
    if (components == null || components.isBlank()) {
      return tokens;
    }
    for (String token : components.split(",")) {
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

  // Structured completion log. Counts only — the filter values and the returned ticket free-text
  // are never logged (AC7). The project id is CR/LF-sanitized.
  private static void emit(
      String correlationId, String project, int resultCount, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "tickets command completed correlationId={} commandName={} projectId={} resultCount={}"
            + " outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME,
        MdcKeys.sanitizeForLog(project),
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
