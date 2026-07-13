package org.dradgo.adapters.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.application.audit.AuditQueryService.AuditEventRow;
import org.dradgo.application.audit.AuditQueryService.AuditQueryResult;
import org.dradgo.application.recovery.ClassifyFailureResult;
import org.dradgo.application.recovery.PauseRecoveryResult;
import org.dradgo.application.recovery.ReconcileRecoveryResult;
import org.dradgo.application.recovery.RerunFromStepRecoveryResult;
import org.dradgo.application.recovery.ResumeRecoveryResult;
import org.dradgo.application.workflow.WorkflowInspectionService.FailureDiagnostics;
import org.dradgo.application.workflow.WorkflowInspectionService.IntegrationSyncStatusView;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunRow;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.application.workflow.WorkflowInspectionService.RecommendedActionView;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceView;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkerStatus;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.integration.ticketsource.TicketQueryResult;
import org.dradgo.domain.integration.ticketsource.TicketSummary;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.WorkflowState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pure rendering helper for the {@code status} and {@code history} Spring Shell commands (story
 * 1-15 Task 2). No business logic — text and JSON renderers consume the application-shaped views
 * from {@link org.dradgo.application.workflow.WorkflowInspectionService} and emit the surface the
 * AC pins.
 *
 * <p>The {@code workflow-status.v1} / {@code workflow-history.v1} JSON schemas under {@code
 * src/main/resources/schemas/cli/} pin the JSON wire shape and the {@code schemaVersion}
 * backward-compatibility contract (story 1-15 Task 3).
 *
 * <p>Named {@code WorkflowCommandOutputs} (plural noun, not {@code *Commands}) so the ArchUnit
 * {@code CLASSES_NAMED_COMMANDS_MUST_BE_COMMAND_GROUP_UNDER_CLI} rule does not require a
 * {@code @CommandGroup} annotation on this helper.
 */
@Component
public class WorkflowCommandOutputs {

  static final int STATUS_SCHEMA_VERSION = 1;
  static final int HISTORY_SCHEMA_VERSION = 1;
  static final int QUEUE_STATUS_SCHEMA_VERSION = 1;
  static final int OPERATOR_STATUS_SCHEMA_VERSION = 1;
  static final int AUDIT_QUERY_SCHEMA_VERSION = 1;
  static final int OPERATOR_DIAGNOSE_SCHEMA_VERSION = 1;
  static final int OPERATOR_RESUME_SCHEMA_VERSION = 1;
  static final int OPERATOR_RECONCILE_SCHEMA_VERSION = 1;
  static final int OPERATOR_RERUN_FROM_STEP_SCHEMA_VERSION = 1;
  static final int OPERATOR_PAUSE_SCHEMA_VERSION = 1;
  static final int OPERATOR_CLASSIFY_FAILURE_SCHEMA_VERSION = 1;
  static final int TICKET_QUERY_SCHEMA_VERSION = 1;

  // Story 3.19 (AC3/AC7) — color thresholds for the queue-depth line. Defaults mirror the alert
  // rules in infra/observability/prometheus/alerts.yml (warn 50 / critical 200). The stale-count
  // fields render yellow whenever non-zero (a stale row is itself the anomaly — AC3).
  private static final long QUEUE_DEPTH_WARN = 50L;
  private static final long QUEUE_DEPTH_CRITICAL = 200L;
  // ESC built from its code point (never a literal control byte in source — keeps the file text,
  // see the literal-control-byte lesson).
  private static final String ESC = Character.toString((char) 27);
  private static final String ANSI_YELLOW = ESC + "[33m";
  private static final String ANSI_RED = ESC + "[31m";
  private static final String ANSI_GREEN = ESC + "[32m";
  private static final String ANSI_DIM = ESC + "[2m";
  private static final String ANSI_RESET = ESC + "[0m";

  private final ObjectMapper objectMapper;

  @Autowired
  public WorkflowCommandOutputs(ObjectProvider<ObjectMapper> objectMapperProvider) {
    this(objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules()));
  }

  public WorkflowCommandOutputs(ObjectMapper objectMapper) {
    // Disable WRITE_DATES_AS_TIMESTAMPS so OffsetDateTime renders as ISO-8601, matching the
    // JSON schema's format=date-time constraint.
    this.objectMapper = objectMapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public String renderStatusText(WorkflowStatusView view) {
    StringBuilder out = new StringBuilder();
    out.append("current state: ").append(view.currentState().value()).append('\n');
    out.append("current actor: ")
        .append(formatActor(view.currentActorIdentity(), view.currentActorType()))
        .append('\n');
    out.append("last event type: ").append(nullableString(view.lastEventType())).append('\n');
    out.append("last event timestamp: ").append(formatTimestamp(view.lastEventAt())).append('\n');
    for (LatestArtifactView artifact : view.latestArtifacts()) {
      out.append("latest artifact ")
          .append(artifact.artifactType())
          .append(" v")
          .append(artifact.version())
          .append('\n');
    }
    if (view.linkedTicket() != null) {
      out.append("linked ticket: ")
          .append(view.linkedTicket().integrationType())
          .append(':')
          .append(view.linkedTicket().externalRef())
          .append('\n');
    }
    // Story 1.18 failure-diagnostic block — emitted only when at least one of the five
    // failed*/last*Activity* fields is non-null (which is true precisely when currentState ==
    // Failed and the failure event / last failed runner_execution were locatable).
    if (hasFailureDiagnostics(view)) {
      out.append("failed stage: ").append(nullableString(view.failedStage())).append('\n');
      out.append("last successful stage: ")
          .append(nullableString(view.lastSuccessfulStage()))
          .append('\n');
      out.append("failure timestamp: ")
          .append(formatTimestamp(view.failureTimestamp()))
          .append('\n');
      out.append("failure category: ").append(nullableString(view.failureCategory())).append('\n');
      out.append("last activity timestamp: ")
          .append(formatTimestamp(view.lastActivityTimestamp()))
          .append('\n');
    }
    out.append("next safe action: ").append(view.nextSafeAction());
    return out.toString();
  }

  private static boolean hasFailureDiagnostics(WorkflowStatusView view) {
    return view.failedStage() != null
        || view.lastSuccessfulStage() != null
        || view.failureTimestamp() != null
        || view.failureCategory() != null
        || view.lastActivityTimestamp() != null;
  }

  /**
   * Renders the JSON wire shape pinned by {@code workflow-status.v1.schema.json}.
   *
   * <p><b>Caller contract:</b> the {@link WorkflowStatusView} passed in must already be the
   * application-layer view emitted by {@link
   * org.dradgo.application.workflow.WorkflowInspectionService#getStatus(String)} — which is
   * responsible for applying the {@code WorkflowInspectionService.ALLOWED_DETAIL_KEYS} allow-list
   * and the {@code RedactionPolicyService} export-side pass. This renderer performs no redaction of
   * its own; calling it with a raw entity-shaped view will leak.
   */
  public String renderStatusJson(WorkflowStatusView view) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", STATUS_SCHEMA_VERSION);
    payload.put("workflowRunId", view.workflowRunId());
    payload.put("currentState", view.currentState().value());
    if (view.currentActorIdentity() != null) {
      Map<String, Object> actor = new LinkedHashMap<>();
      actor.put("identity", view.currentActorIdentity());
      actor.put("type", view.currentActorType());
      payload.put("currentActor", actor);
    } else {
      payload.put("currentActor", null);
    }
    if (view.lastEventType() != null && view.lastEventAt() != null) {
      Map<String, Object> lastEvent = new LinkedHashMap<>();
      lastEvent.put("eventType", view.lastEventType());
      lastEvent.put("createdAt", canonicalUtcIso(view.lastEventAt()));
      payload.put("lastEvent", lastEvent);
    } else {
      payload.put("lastEvent", null);
    }
    java.util.List<Map<String, Object>> artifacts = new java.util.ArrayList<>();
    for (LatestArtifactView artifact : view.latestArtifacts()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("artifactType", artifact.artifactType());
      entry.put("version", artifact.version());
      entry.put("status", artifact.status());
      artifacts.add(entry);
    }
    payload.put("latestArtifacts", artifacts);
    if (view.linkedTicket() != null) {
      Map<String, Object> link = new LinkedHashMap<>();
      link.put("integrationType", view.linkedTicket().integrationType());
      link.put("externalRef", view.linkedTicket().externalRef());
      link.put("syncStatus", view.linkedTicket().syncStatus());
      payload.put("linkedTicket", link);
    } else {
      payload.put("linkedTicket", null);
    }
    // Story 1.18 failure-diagnostic fields — always emitted (JSON null on non-Failed runs) so
    // the schema's `additionalProperties: false` + `required` contract pins the field set.
    payload.put("failedStage", view.failedStage());
    payload.put("lastSuccessfulStage", view.lastSuccessfulStage());
    payload.put(
        "failureTimestamp",
        view.failureTimestamp() == null ? null : canonicalUtcIso(view.failureTimestamp()));
    payload.put("failureCategory", view.failureCategory());
    payload.put(
        "lastActivityTimestamp",
        view.lastActivityTimestamp() == null
            ? null
            : canonicalUtcIso(view.lastActivityTimestamp()));
    payload.put("nextSafeAction", view.nextSafeAction());
    return writeJson(payload);
  }

  public String renderHistoryText(WorkflowHistoryView view) {
    StringBuilder out = new StringBuilder();
    for (WorkflowEventView event : view.events()) {
      out.append(formatTimestamp(event.createdAt()))
          .append(' ')
          .append(event.eventType())
          .append(' ')
          .append(escapeForText(event.actorIdentity()))
          .append('/')
          .append(escapeForText(event.actorType()))
          .append(' ')
          .append(nullableString(event.priorState()))
          .append("->")
          .append(nullableString(event.resultingState()));
      if (event.reason() != null && !event.reason().isBlank()) {
        out.append(" reason=\"").append(escapeQuotedValue(event.reason())).append('"');
      }
      if (event.failureCategory() != null && !event.failureCategory().isBlank()) {
        out.append(" failureCategory=").append(escapeForText(event.failureCategory()));
      }
      if (event.interventionMarker()) {
        out.append(" [intervention]");
      }
      if (event.details() != null && !event.details().isEmpty()) {
        out.append(" details=").append(renderDetailsForText(event.details()));
      }
      out.append('\n');
    }
    if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
      out.setLength(out.length() - 1);
    }
    return out.toString();
  }

  /**
   * Renders the JSON wire shape pinned by {@code workflow-history.v1.schema.json}.
   *
   * <p><b>Caller contract:</b> the {@link WorkflowHistoryView} passed in must already be the
   * application-layer view emitted by {@link
   * org.dradgo.application.workflow.WorkflowInspectionService#listHistory(String,
   * java.time.OffsetDateTime)} — which is responsible for filtering {@code event.details()} through
   * the {@code WorkflowInspectionService.ALLOWED_DETAIL_KEYS} allow-list and the {@code
   * RedactionPolicyService} export-side pass. This renderer performs no redaction of its own;
   * calling it with raw entity-shaped events will leak {@code idempotencyKey} and any other
   * non-allow-listed details into the rendered JSON.
   */
  public String renderHistoryJson(WorkflowHistoryView view) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", HISTORY_SCHEMA_VERSION);
    payload.put("workflowRunId", view.workflowRunId());
    java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
    for (WorkflowEventView event : view.events()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("publicId", event.publicId());
      entry.put("eventType", event.eventType());
      entry.put("priorState", event.priorState());
      entry.put("resultingState", event.resultingState());
      entry.put("actorIdentity", event.actorIdentity());
      entry.put("actorType", event.actorType());
      entry.put("reason", event.reason());
      entry.put("failureCategory", event.failureCategory());
      entry.put("interventionMarker", event.interventionMarker());
      entry.put("createdAt", canonicalUtcIso(event.createdAt()));
      entry.put("details", event.details() == null ? Map.of() : event.details());
      events.add(entry);
    }
    payload.put("events", events);
    return writeJson(payload);
  }

  /**
   * Story 3.19 (AC2/AC3) — text rendering of the runner-queue + worker-pool inspection view. {@code
   * ansi} gates the yellow/red color codes: the caller passes {@code true} only for an interactive
   * TTY in text mode, {@code false} for a non-TTY pipe (so {@code grep}/{@code awk} see clean
   * text). Stale counts render yellow when non-zero; the queue-depth line is yellow at/above the
   * warn threshold and red at/above the critical threshold (mirrors the alert rules).
   */
  public String renderQueueStatusText(RunnerQueueStatus view, boolean ansi) {
    StringBuilder out = new StringBuilder();
    out.append("pool size: ").append(view.poolSize()).append('\n');
    out.append("active workers: ").append(view.activeWorkers()).append('\n');
    out.append("idle workers: ").append(view.idleWorkers()).append('\n');
    out.append("queue depth: ").append(colorQueueDepth(view.queueDepth(), ansi)).append('\n');
    out.append("oldest queued at: ").append(formatTimestamp(view.oldestQueuedAt())).append('\n');
    out.append("oldest queued age (s): ").append(view.oldestQueuedAgeSeconds()).append('\n');
    out.append("in-flight executions: ").append(view.inFlightExecutions()).append('\n');
    out.append("throughput (per min): ").append(view.recentThroughputPerMinute()).append('\n');
    out.append("stale queued: ").append(colorStale(view.staleQueuedCount(), ansi)).append('\n');
    out.append("stale dispatched: ")
        .append(colorStale(view.staleDispatchedCount(), ansi))
        .append('\n');
    out.append("workers:");
    if (view.workers().isEmpty()) {
      out.append(" (none busy)");
    } else {
      for (WorkerStatus worker : view.workers()) {
        // Escape every free-form field consistently (escapeForText is null-safe → "(none)"), not
        // just workerId — the per-line format must stay un-splittable for grep/awk regardless of
        // which column carries the value.
        out.append("\n  ")
            .append(escapeForText(worker.workerId()))
            .append(' ')
            .append(escapeForText(worker.state()))
            .append(" rex=")
            .append(escapeForText(worker.currentRunnerExecutionId()))
            .append(" run=")
            .append(escapeForText(worker.currentWorkflowRunId()))
            .append(" stage=")
            .append(escapeForText(worker.currentStage()))
            .append(" dispatchedAt=")
            .append(formatTimestamp(worker.dispatchedAt()));
      }
    }
    return out.toString();
  }

  /**
   * Story 3.19 (AC2) — stable-schema JSON for the runner-queue view. No ANSI ever (AC3); consumed
   * by scripts + Grafana-adjacent tooling. {@code schemaVersion} pins backward compatibility.
   */
  public String renderQueueStatusJson(RunnerQueueStatus view) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", QUEUE_STATUS_SCHEMA_VERSION);
    payload.put("poolSize", view.poolSize());
    payload.put("activeWorkers", view.activeWorkers());
    payload.put("idleWorkers", view.idleWorkers());
    payload.put("queueDepth", view.queueDepth());
    payload.put(
        "oldestQueuedAt",
        view.oldestQueuedAt() == null ? null : canonicalUtcIso(view.oldestQueuedAt()));
    payload.put("oldestQueuedAgeSeconds", view.oldestQueuedAgeSeconds());
    payload.put("inFlightExecutions", view.inFlightExecutions());
    payload.put("recentThroughputPerMinute", view.recentThroughputPerMinute());
    payload.put("staleQueuedCount", view.staleQueuedCount());
    payload.put("staleDispatchedCount", view.staleDispatchedCount());
    java.util.List<Map<String, Object>> workers = new java.util.ArrayList<>();
    for (WorkerStatus worker : view.workers()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("workerId", worker.workerId());
      entry.put("state", worker.state());
      entry.put("currentRunnerExecutionId", worker.currentRunnerExecutionId());
      entry.put("currentWorkflowRunId", worker.currentWorkflowRunId());
      entry.put(
          "dispatchedAt",
          worker.dispatchedAt() == null ? null : canonicalUtcIso(worker.dispatchedAt()));
      entry.put("currentStage", worker.currentStage());
      workers.add(entry);
    }
    payload.put("workers", workers);
    return writeJson(payload);
  }

  /**
   * Story 4.1 (AC4) — text rendering of the operator fleet summary. A machine-friendly header
   * (histograms + total + oldest-entry age) then one grep-safe line per run. {@code ansi} gates the
   * color codes (red for failed/orphaned rows, yellow for stalled, dim for takenover) — the caller
   * passes {@code true} only for an interactive TTY in text mode. The color-independent signifier
   * is a leading UPPERCASE bracketed state label ({@code [FAILED]}/{@code [STALLED]}/{@code
   * [ORPHANED]}/{@code [TAKENOVER]}) that survives ANSI stripping (story 2.3 AC5 convention —
   * Reconciliation 9); every free-form field routes through {@link #escapeForText} for grep/awk
   * safety.
   */
  public String renderOperatorSummaryText(OperatorRunSummary view, boolean ansi) {
    StringBuilder out = new StringBuilder();
    out.append("total: ").append(view.total()).append('\n');
    out.append("oldest entry: ").append(formatTimestamp(view.oldestEntryAt())).append('\n');
    out.append("by state:");
    if (view.byState().isEmpty()) {
      out.append(" (none)");
    } else {
      for (Map.Entry<WorkflowState, Integer> entry : view.byState().entrySet()) {
        out.append("\n  ").append(entry.getKey().value()).append(": ").append(entry.getValue());
      }
    }
    out.append('\n').append("by failure category:");
    if (view.byFailureCategory().isEmpty()) {
      out.append(" (none)");
    } else {
      view.byFailureCategory()
          .forEach(
              (category, count) ->
                  out.append("\n  ").append(category.value()).append(": ").append(count));
    }
    out.append('\n').append("runs:");
    if (view.runs().isEmpty()) {
      out.append(" (none)");
    } else {
      for (OperatorRunRow row : view.runs()) {
        out.append("\n  ")
            .append(operatorLabel(row, ansi))
            .append(' ')
            .append(escapeForText(row.runId()))
            .append(" state=")
            .append(row.currentState().value())
            .append(" failureCategory=")
            .append(escapeForText(row.failureCategory()))
            .append(" lastTransitionAt=")
            .append(formatTimestamp(row.lastTransitionAt()))
            .append(" actor=")
            .append(escapeForText(row.actorIdentity()))
            .append(" ticket=")
            .append(escapeForText(row.linkedTicketRef()))
            .append(" pr=")
            .append(escapeForText(row.linkedPrRef()))
            .append(" escalation=")
            .append(row.escalationMarker())
            .append(" oldestEventAt=")
            .append(formatTimestamp(row.oldestEventAt()));
      }
    }
    return out.toString();
  }

  /**
   * Story 4.1 (AC5) — stable-schema JSON for the operator fleet summary. No ANSI ever; {@code
   * schemaVersion} pins backward compatibility (additive within v1; any removal/rename bumps v2).
   * The two histograms render as objects keyed by the state / failure-category wire strings.
   */
  public String renderOperatorSummaryJson(OperatorRunSummary view) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_STATUS_SCHEMA_VERSION);
    payload.put("total", view.total());
    payload.put(
        "oldestEntryAt",
        view.oldestEntryAt() == null ? null : canonicalUtcIso(view.oldestEntryAt()));
    Map<String, Object> byState = new LinkedHashMap<>();
    view.byState().forEach((state, count) -> byState.put(state.value(), count));
    payload.put("byState", byState);
    Map<String, Object> byFailureCategory = new LinkedHashMap<>();
    view.byFailureCategory()
        .forEach((category, count) -> byFailureCategory.put(category.value(), count));
    payload.put("byFailureCategory", byFailureCategory);
    java.util.List<Map<String, Object>> runs = new java.util.ArrayList<>();
    for (OperatorRunRow row : view.runs()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("runId", row.runId());
      entry.put("currentState", row.currentState().value());
      entry.put("failureCategory", row.failureCategory());
      entry.put(
          "lastTransitionAt",
          row.lastTransitionAt() == null ? null : canonicalUtcIso(row.lastTransitionAt()));
      entry.put("actorIdentity", row.actorIdentity());
      entry.put("linkedTicketRef", row.linkedTicketRef());
      entry.put("linkedPrRef", row.linkedPrRef());
      entry.put("escalationMarker", row.escalationMarker());
      entry.put(
          "oldestEventAt",
          row.oldestEventAt() == null ? null : canonicalUtcIso(row.oldestEventAt()));
      runs.add(entry);
    }
    payload.put("runs", runs);
    return writeJson(payload);
  }

  /**
   * Story 4.4 (AC3) — text rendering of the failure-diagnostics deep-dive. The NFR7 five questions
   * are printed FIRST (above the fold — what happened / what changed / who acted / what failed /
   * what is next), then the detail fields, the runner-log reference, per-integration sync status,
   * and the safety-ranked recommended actions. {@code ansi} gates the color coding (red = failure,
   * yellow = caution, green = safe) — the caller passes {@code true} only for an interactive TTY in
   * text mode; the color-independent signifier is the UPPERCASE bracketed safety label ({@code
   * [SAFE]}/{@code [CAUTION]}/{@code [RISKY]}) that survives ANSI stripping (story 2.3 AC5). Every
   * free-form field routes through {@link #escapeForText} (the {@code failureReason} is already
   * redacted + control-char-guarded by the service).
   */
  public String renderDiagnoseText(FailureDiagnostics view, boolean ansi) {
    StringBuilder out = new StringBuilder();
    out.append("=== Failure Diagnostics ===").append('\n');
    out.append("run state: ").append(runStateLabel(view.currentState(), ansi)).append('\n');
    // NFR7 five questions (AC7) — above the fold.
    out.append("what happened: ")
        .append(escapeForText(view.failureCategory()))
        .append(" — ")
        .append(escapeForText(view.failureReason()))
        .append('\n');
    out.append("what changed: ")
        .append(escapeForText(view.lastSuccessfulStage()))
        .append(" -> ")
        .append(escapeForText(view.failedStage()))
        .append('\n');
    out.append("who acted: ").append(escapeForText(view.lastActorIdentity())).append('\n');
    out.append("what failed: stage=")
        .append(escapeForText(view.failedStage()))
        .append(" category=")
        .append(escapeForText(view.failureCategory()))
        .append('\n');
    out.append("what is next: ").append(escapeForText(whatIsNext(view))).append('\n');
    // Detail fields.
    out.append("correlationId: ").append(escapeForText(view.correlationId())).append('\n');
    out.append("lastGoodState: ").append(escapeForText(view.lastGoodState())).append('\n');
    out.append("failureTimestamp: ").append(formatTimestamp(view.failureTimestamp())).append('\n');
    out.append("lastActivityTimestamp: ")
        .append(formatTimestamp(view.lastActivityTimestamp()))
        .append('\n');
    out.append("currentBlockingReason: ")
        .append(escapeForText(view.currentBlockingReason()))
        .append('\n');
    out.append("runner log: ").append(runnerLogLine(view.runnerLogReference())).append('\n');
    out.append("integration sync:");
    out.append("\n  linear: ").append(syncLine(view.linearSyncStatus()));
    out.append("\n  github: ").append(syncLine(view.githubSyncStatus()));
    out.append('\n').append("recommended actions:");
    if (view.recommendedRecoveryActions().isEmpty()) {
      out.append(" (none)");
    } else {
      for (RecommendedActionView action : view.recommendedRecoveryActions()) {
        out.append("\n  ")
            .append(safetyLabel(action.safetyLevel(), ansi))
            .append(' ')
            .append(escapeForText(action.actionType()))
            .append(" — ")
            .append(escapeForText(action.reason()))
            .append(" (precondition: ")
            .append(escapeForText(action.precondition()))
            .append(')');
      }
    }
    return out.toString();
  }

  /**
   * Story 4.4 (AC3) — stable-schema JSON for the failure-diagnostics deep-dive. No ANSI ever;
   * {@code schemaVersion} pins backward compatibility (additive within v1; any removal/rename bumps
   * v2). Pinned by {@code operator-diagnose.v1.schema.json}.
   */
  public String renderDiagnoseJson(FailureDiagnostics view) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_DIAGNOSE_SCHEMA_VERSION);
    payload.put("currentState", view.currentState() == null ? null : view.currentState().value());
    payload.put("failedStage", view.failedStage());
    payload.put("lastSuccessfulStage", view.lastSuccessfulStage());
    payload.put("failureCategory", view.failureCategory());
    payload.put("failureReason", view.failureReason());
    payload.put(
        "failureTimestamp",
        view.failureTimestamp() == null ? null : canonicalUtcIso(view.failureTimestamp()));
    payload.put(
        "lastActivityTimestamp",
        view.lastActivityTimestamp() == null
            ? null
            : canonicalUtcIso(view.lastActivityTimestamp()));
    payload.put("correlationId", view.correlationId());
    payload.put("lastGoodState", view.lastGoodState());
    payload.put("currentBlockingReason", view.currentBlockingReason());
    payload.put("nextSafeAction", view.nextSafeAction());
    payload.put("lastActorIdentity", view.lastActorIdentity());
    payload.put("runnerLogReference", runnerLogJson(view.runnerLogReference()));
    Map<String, Object> integrationSyncStatus = new LinkedHashMap<>();
    integrationSyncStatus.put("linear", syncJson(view.linearSyncStatus()));
    integrationSyncStatus.put("github", syncJson(view.githubSyncStatus()));
    payload.put("integrationSyncStatus", integrationSyncStatus);
    List<Map<String, Object>> actions = new java.util.ArrayList<>();
    for (RecommendedActionView action : view.recommendedRecoveryActions()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("actionType", action.actionType());
      entry.put("safetyLevel", action.safetyLevel());
      entry.put("reason", action.reason());
      entry.put("precondition", action.precondition());
      actions.add(entry);
    }
    payload.put("recommendedRecoveryActions", actions);
    return writeJson(payload);
  }

  /**
   * Story 4.10 — stable {@code operator-resume.v1} JSON document for {@code deliveryline operator
   * resume --format json}. {@code workflowRunId} is passed explicitly because {@link
   * ResumeRecoveryResult} carries none. {@code currentState} and {@code runnerExecutionId} are
   * nullable, mirroring the result contract (replay / auto-dispatch-off leave them null).
   */
  public String renderOperatorResumeJson(String workflowRunId, ResumeRecoveryResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_RESUME_SCHEMA_VERSION);
    payload.put("workflowRunId", workflowRunId);
    payload.put(
        "currentState", result.resultingState() == null ? null : result.resultingState().value());
    payload.put("recoveryActionId", result.recoveryActionPublicId());
    payload.put("resumedEventId", result.resumedEventPublicId());
    payload.put("runnerExecutionId", result.newRunnerExecutionPublicId());
    payload.put("correlationId", result.correlationId());
    payload.put("replayed", result.replayed());
    return writeJson(payload);
  }

  /**
   * Story 4.11 — stable {@code operator-reconcile.v1} JSON document for {@code deliveryline
   * operator reconcile --format json}. {@code workflowRunId} is passed explicitly because {@link
   * ReconcileRecoveryResult} carries none. {@code currentState} is non-null (both reconcile paths
   * resolve a concrete state — Reconciliation 7); {@code resolvedConflictId} is the {@code icf_...}
   * id that was closed.
   */
  public String renderOperatorReconcileJson(String workflowRunId, ReconcileRecoveryResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_RECONCILE_SCHEMA_VERSION);
    payload.put("workflowRunId", workflowRunId);
    payload.put(
        "currentState", result.resultingState() == null ? null : result.resultingState().value());
    payload.put("recoveryActionId", result.recoveryActionPublicId());
    payload.put("reconciledEventId", result.reconciledEventPublicId());
    payload.put("resolvedConflictId", result.resolvedConflictId());
    payload.put("correlationId", result.correlationId());
    payload.put("replayed", result.replayed());
    return writeJson(payload);
  }

  /**
   * Story 4.12 — stable {@code operator-rerun-from-step.v1} JSON document for {@code deliveryline
   * operator rerun-from-step --format json}. {@code workflowRunId} is passed explicitly because
   * {@link RerunFromStepRecoveryResult} carries none. {@code currentState} is non-null (both rerun
   * paths resolve a concrete safe-boundary state — Reconciliation 8); {@code supersededArtifactIds}
   * and {@code invalidatedApprovalIds} are never-null arrays (Reconciliation 7); {@code
   * runnerExecutionId} is null on replay / auto-dispatch-off.
   */
  public String renderOperatorRerunFromStepJson(
      String workflowRunId, RerunFromStepRecoveryResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_RERUN_FROM_STEP_SCHEMA_VERSION);
    payload.put("workflowRunId", workflowRunId);
    payload.put(
        "currentState", result.resultingState() == null ? null : result.resultingState().value());
    payload.put("recoveryActionId", result.recoveryActionPublicId());
    payload.put("rerunEventId", result.rerunEventPublicId());
    payload.put("supersededArtifactIds", result.supersededArtifactIds());
    payload.put("invalidatedApprovalIds", result.invalidatedApprovalIds());
    payload.put("runnerExecutionId", result.newRunnerExecutionPublicId());
    payload.put("correlationId", result.correlationId());
    payload.put("replayed", result.replayed());
    return writeJson(payload);
  }

  /**
   * Story 4.13 — stable {@code operator-pause.v1} JSON document for {@code deliveryline operator
   * pause --format json}. {@code workflowRunId} is passed explicitly because {@link
   * PauseRecoveryResult} carries none. {@code currentState} is non-null (always {@code PAUSED} —
   * Reconciliation 6); {@code priorState} IS null-tolerant (may be null on a degenerate replay per
   * the result javadoc); {@code cancelledInFlightCount}/{@code cancelledQueuedCount} are {@code
   * int} (0 on replay).
   */
  public String renderOperatorPauseJson(String workflowRunId, PauseRecoveryResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_PAUSE_SCHEMA_VERSION);
    payload.put("workflowRunId", workflowRunId);
    payload.put(
        "currentState", result.resultingState() == null ? null : result.resultingState().value());
    payload.put("priorState", result.priorState() == null ? null : result.priorState().value());
    payload.put("recoveryActionId", result.recoveryActionPublicId());
    payload.put("pausedEventId", result.pausedEventPublicId());
    payload.put("cancelledInFlightCount", result.cancelledInFlightCount());
    payload.put("cancelledQueuedCount", result.cancelledQueuedCount());
    payload.put("correlationId", result.correlationId());
    payload.put("replayed", result.replayed());
    return writeJson(payload);
  }

  /**
   * Story 4.14 — stable {@code operator-classify-failure.v1} JSON document for {@code deliveryline
   * operator classify-failure --format json}. {@code workflowRunId} is passed explicitly because
   * {@link ClassifyFailureResult} carries none. There is NO {@code currentState} field — classify
   * is pure metadata (no transition — Reconciliation 6); {@code taxonomyValue} is always present,
   * {@code priorTaxonomyValue} IS null-tolerant (null on a first classify per the result javadoc).
   */
  public String renderOperatorClassifyFailureJson(
      String workflowRunId, ClassifyFailureResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", OPERATOR_CLASSIFY_FAILURE_SCHEMA_VERSION);
    payload.put("workflowRunId", workflowRunId);
    payload.put("taxonomyValue", result.taxonomyValue());
    payload.put("priorTaxonomyValue", result.priorTaxonomyValue());
    payload.put("recoveryActionId", result.recoveryActionPublicId());
    payload.put("classifiedEventId", result.classifiedEventPublicId());
    payload.put("correlationId", result.correlationId());
    payload.put("replayed", result.replayed());
    return writeJson(payload);
  }

  // NFR7 "what is next" (story 4.4 review) — the single actionable answer shown to a human. Leads
  // with the top safety-ranked recommended action, falling back to nextSafeAction when there are no
  // recommendations, so the CLI text answer matches the FE panel (which renders the same). The JSON
  // render intentionally exposes nextSafeAction + the full recommendedRecoveryActions list as raw
  // data instead, letting programmatic consumers derive this themselves.
  private static String whatIsNext(FailureDiagnostics view) {
    if (!view.recommendedRecoveryActions().isEmpty()) {
      return view.recommendedRecoveryActions().get(0).actionType();
    }
    return view.nextSafeAction();
  }

  private static String runStateLabel(WorkflowState state, boolean ansi) {
    if (state == null) {
      return "(none)";
    }
    String value = state.value();
    return ansi && state == WorkflowState.FAILED ? ANSI_RED + value + ANSI_RESET : value;
  }

  private static String safetyLabel(String safetyLevel, boolean ansi) {
    String upper = safetyLevel == null ? "UNKNOWN" : safetyLevel.toUpperCase(java.util.Locale.ROOT);
    String bracketed = "[" + upper + "]";
    String color =
        safetyLevel == null
            ? null
            : switch (safetyLevel) {
              case "safe" -> ANSI_GREEN;
              case "caution" -> ANSI_YELLOW;
              case "risky" -> ANSI_RED;
              default -> null;
            };
    return ansi && color != null ? color + bracketed + ANSI_RESET : bracketed;
  }

  private static String runnerLogLine(RunnerLogReferenceView reference) {
    if (reference == null) {
      return "(none)";
    }
    return "rex="
        + escapeForText(reference.runnerExecutionId())
        + " classification="
        + escapeForText(reference.classification())
        + " bytes="
        + reference.byteSize()
        + " redactions="
        + reference.redactionCount();
  }

  private static String syncLine(IntegrationSyncStatusView sync) {
    if (sync == null) {
      return "(none)";
    }
    return "ref="
        + escapeForText(sync.externalRef())
        + " status="
        + escapeForText(sync.syncStatus())
        + " lastSyncAt="
        + formatTimestamp(sync.lastSyncAt());
  }

  private static Map<String, Object> runnerLogJson(RunnerLogReferenceView reference) {
    if (reference == null) {
      return null;
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("runnerExecutionId", reference.runnerExecutionId());
    entry.put("referencePath", reference.referencePath());
    entry.put("byteSize", reference.byteSize());
    entry.put("classification", reference.classification());
    entry.put("redactionCount", reference.redactionCount());
    return entry;
  }

  private static Map<String, Object> syncJson(IntegrationSyncStatusView sync) {
    if (sync == null) {
      return null;
    }
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("integrationType", sync.integrationType());
    entry.put("externalRef", sync.externalRef());
    entry.put("syncStatus", sync.syncStatus());
    entry.put("lastSyncAt", sync.lastSyncAt() == null ? null : canonicalUtcIso(sync.lastSyncAt()));
    return entry;
  }

  /**
   * Story 4.3 (AC4) — text rendering of the audit query result. A machine-friendly header ({@code
   * total} + {@code nextCursor}) then one grep-safe line per event. {@code ansi} gates the color
   * codes (red for a failure event, i.e. a non-null failureCategory) — the caller passes {@code
   * true} only for an interactive TTY in text mode. The color-independent signifier is a leading
   * UPPERCASE bracketed event-type label ({@code [WORKFLOW.STATECHANGED]}) that survives ANSI
   * stripping; every free-form field routes through {@link #escapeForText} for grep/awk safety (the
   * {@code reason} is already redacted + control-char-guarded by the service).
   */
  public String renderAuditQueryText(AuditQueryResult result, boolean ansi) {
    StringBuilder out = new StringBuilder();
    out.append("total: ").append(result.totalCount()).append('\n');
    out.append("nextCursor: ").append(nullableString(result.nextCursor())).append('\n');
    out.append("events:");
    if (result.events().isEmpty()) {
      out.append(" (none)");
    } else {
      for (AuditEventRow row : result.events()) {
        out.append("\n  ")
            .append(eventTypeLabel(row, ansi))
            .append(' ')
            .append(escapeForText(row.eventId()))
            .append(' ')
            .append(escapeForText(row.workflowRunId()))
            .append(" actor=")
            .append(
                formatActor(
                    row.actorIdentity(), row.actorType() == null ? null : row.actorType().value()))
            .append(" state=")
            .append(formatTransition(row))
            .append(" failureCategory=")
            .append(
                row.failureCategory() == null
                    ? "(none)"
                    : escapeForText(row.failureCategory().value()))
            .append(" at=")
            .append(formatTimestamp(row.timestamp()))
            .append(" correlationId=")
            .append(escapeForText(row.correlationId()))
            .append(" artifactId=")
            .append(escapeForText(row.linkedArtifactId()))
            .append(" reason=\"")
            .append(escapeQuotedValue(row.reason()))
            .append('"');
      }
    }
    return out.toString();
  }

  /**
   * Story 4.3 (AC4) — stable-schema JSON for the audit query result. No ANSI ever; {@code
   * schemaVersion} pins backward compatibility (additive within v1; any removal/rename bumps v2).
   * Nullable fields serialize as JSON {@code null}; the {@code audit-query.v1} schema constrains
   * the shape. The {@code reason} is already redacted + control-char-guarded by the service.
   */
  public String renderAuditQueryJson(AuditQueryResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", AUDIT_QUERY_SCHEMA_VERSION);
    payload.put("totalCount", result.totalCount());
    payload.put("nextCursor", result.nextCursor());
    java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
    for (AuditEventRow row : result.events()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("eventId", row.eventId());
      entry.put("eventType", row.eventType());
      entry.put("workflowRunId", row.workflowRunId());
      entry.put("actorIdentity", row.actorIdentity());
      entry.put("actorType", row.actorType() == null ? null : row.actorType().value());
      entry.put("timestamp", row.timestamp() == null ? null : canonicalUtcIso(row.timestamp()));
      entry.put("priorState", row.priorState() == null ? null : row.priorState().value());
      entry.put(
          "resultingState", row.resultingState() == null ? null : row.resultingState().value());
      entry.put(
          "failureCategory", row.failureCategory() == null ? null : row.failureCategory().value());
      entry.put("reason", row.reason());
      entry.put("correlationId", row.correlationId());
      entry.put("linkedArtifactId", row.linkedArtifactId());
      events.add(entry);
    }
    payload.put("events", events);
    return writeJson(payload);
  }

  /**
   * Story 3i-2 (AC3) — human-readable candidate-ticket browse. One line per ticket: the ticket ref,
   * the title, and a {@code summary=} field. Every free-form field routes through {@link
   * #escapeForText} / {@link #escapeQuotedValue} for grep/awk safety, mirroring {@link
   * #renderAuditQueryText}. No ANSI beyond the dim ref (a browse has no anomaly to color).
   *
   * <p>Emits {@code shown} and {@code total} separately, plus an explicit {@code truncated} line,
   * so a capped page never reads as a complete backlog.
   */
  public String renderTicketQueryText(TicketQueryResult result, boolean ansi) {
    List<TicketSummary> tickets = result.tickets();
    StringBuilder out = new StringBuilder();
    // `shown` is this page; `total` is what matched at the source. When they differ the operator is
    // looking at a partial backlog and needs to know it — a bare count would hide that entirely.
    out.append("shown: ").append(tickets.size()).append('\n');
    out.append("total: ").append(result.total()).append('\n');
    if (result.truncated()) {
      out.append("truncated: yes (narrow the filter or raise --limit)").append('\n');
    }
    out.append("tickets:");
    if (tickets.isEmpty()) {
      out.append(" (none)");
      return out.toString();
    }
    for (TicketSummary ticket : tickets) {
      String ref = escapeForText(ticket.ticketRef().value());
      out.append("\n  ")
          .append(ansi ? ANSI_DIM + ref + ANSI_RESET : ref)
          .append(" title=\"")
          .append(escapeQuotedValue(ticket.title()))
          .append("\" summary=\"")
          .append(escapeQuotedValue(ticket.summary()))
          .append('"');
    }
    return out.toString();
  }

  /**
   * Story 3i-2 (AC3) — stable-schema JSON for the candidate-ticket browse. No ANSI ever; {@code
   * schemaVersion} pins backward compatibility (additive within v1; any removal/rename bumps v2). A
   * ticket with no description serializes {@code summary} as JSON {@code null}.
   *
   * <p>{@code shownCount} is the rows in {@code tickets}; {@code totalCount} is the source's match
   * count and may exceed it; {@code truncated} is the derived flag a script should branch on.
   */
  public String renderTicketQueryJson(TicketQueryResult result) {
    List<TicketSummary> tickets = result.tickets();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", TICKET_QUERY_SCHEMA_VERSION);
    payload.put("shownCount", tickets.size());
    payload.put("totalCount", result.total());
    payload.put("truncated", result.truncated());
    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
    for (TicketSummary ticket : tickets) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("ticketRef", ticket.ticketRef().value());
      entry.put("title", ticket.title());
      entry.put("summary", ticket.summary());
      rows.add(entry);
    }
    payload.put("tickets", rows);
    return writeJson(payload);
  }

  private static String formatTransition(AuditEventRow row) {
    String prior = row.priorState() == null ? "(none)" : row.priorState().value();
    String resulting = row.resultingState() == null ? "(none)" : row.resultingState().value();
    return prior + "->" + resulting;
  }

  private static String eventTypeLabel(AuditEventRow row, boolean ansi) {
    String bracketed = "[" + row.eventType().toUpperCase(java.util.Locale.ROOT) + "]";
    // A failure event is the anomaly worth coloring; every other event type is the plain bracketed
    // label. The bracketed UPPERCASE text is the color-independent signifier (survives ANSI strip).
    return ansi && row.failureCategory() != null ? ANSI_RED + bracketed + ANSI_RESET : bracketed;
  }

  /**
   * The UPPERCASE bracketed signifier + its (optional) ANSI color. The label is the server-derived
   * {@link OperatorRunRow#operatorSignifier()} (which knows the matched predicate — story 4.1 AC4 /
   * Reconciliation 9), NOT re-derived from {@code currentState} here: that re-derivation could not
   * emit {@code [OVERRIDDEN]} and mislabeled an active overridden run as {@code [STALLED]}. Color
   * follows AC4 (red for failed/orphaned, yellow for stalled, dim for takenover); every other
   * signifier — {@code OVERRIDDEN} and any raw state — is the bracketed label with no color. The
   * bracketed UPPERCASE text is the color-independent signifier — it survives ANSI stripping.
   */
  private static String operatorLabel(OperatorRunRow row, boolean ansi) {
    String label = row.operatorSignifier();
    if (label == null || label.isBlank()) {
      // Defensive fallback — the service always sets the signifier; never render an empty bracket.
      label = row.currentState().value().toUpperCase(java.util.Locale.ROOT);
    }
    String color =
        switch (label) {
          case "FAILED", "ORPHANED" -> ANSI_RED;
          case "STALLED" -> ANSI_YELLOW;
          case "TAKENOVER" -> ANSI_DIM;
          default -> null;
        };
    String bracketed = "[" + label + "]";
    return ansi && color != null ? color + bracketed + ANSI_RESET : bracketed;
  }

  private static String colorStale(long value, boolean ansi) {
    if (value == 0L || !ansi) {
      return Long.toString(value);
    }
    // A stale row is itself the anomaly (AC3): yellow whenever non-zero. AC7 defines no stale-count
    // critical threshold, so we do NOT borrow the (unrelated) queue-depth red threshold here.
    return ANSI_YELLOW + value + ANSI_RESET;
  }

  private static String colorQueueDepth(long depth, boolean ansi) {
    if (!ansi || depth < QUEUE_DEPTH_WARN) {
      return Long.toString(depth);
    }
    String color = depth >= QUEUE_DEPTH_CRITICAL ? ANSI_RED : ANSI_YELLOW;
    return color + depth + ANSI_RESET;
  }

  private String writeJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException error) {
      // Wrap as DomainException(INTERNAL_ERROR) so the CLI exit-code mapper applies
      // (exit 401) and the structured completion log line emits a stable failure code,
      // instead of leaking a raw stack trace and an undocumented exit code.
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "Failed to serialize CLI payload", error);
    }
  }

  private static String formatActor(String identity, String type) {
    if (identity == null) {
      return "(none)";
    }
    return escapeForText(identity) + "/" + escapeForText(type);
  }

  private static String formatTimestamp(OffsetDateTime timestamp) {
    if (timestamp == null) {
      return "(none)";
    }
    return canonicalUtcIso(timestamp);
  }

  /**
   * Canonical ISO-8601 representation used by both text and JSON renderers — both surfaces emit the
   * same instant with the same {@code Z} offset, so a consumer comparing the two can assume
   * byte-for-byte equality on {@code createdAt} fields.
   */
  private static String canonicalUtcIso(OffsetDateTime timestamp) {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        timestamp.withOffsetSameInstant(ZoneOffset.UTC));
  }

  private static String nullableString(String value) {
    return value == null ? "(none)" : value;
  }

  /**
   * Sanitize a free-form text token for the per-line history format: replace control characters
   * (CR/LF/TAB) with the literal escape sequences {@code \r}, {@code \n}, {@code \t} so the
   * line-oriented parser used by operators ({@code grep}, {@code awk}) cannot be tricked into
   * splitting one event across multiple lines, and escape backslashes so the escape sequence is
   * unambiguous.
   */
  private static String escapeForText(String value) {
    if (value == null) {
      return "(none)";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  /**
   * Same as {@link #escapeForText(String)} but additionally escapes {@code "} so the {@code
   * reason="..."} token shape stays parseable when the reason contains quote characters.
   */
  private static String escapeQuotedValue(String value) {
    if (value == null) {
      return "";
    }
    return escapeForText(value).replace("\"", "\\\"");
  }

  /**
   * Render a per-event {@code details} map for the one-line history surface using the same {@code
   * {key=value, key=value}} shape that {@link java.util.LinkedHashMap#toString()} produces, but
   * routing every value through {@link #escapeForText(String)} so CR / LF / TAB characters inside
   * allow-listed details (e.g. an operator-supplied {@code --correlation-id}) cannot split one
   * event across multiple lines (story 1.18 review F7). Non-string values use {@link
   * Object#toString()} directly because allow-listed numeric / boolean values cannot carry control
   * characters.
   */
  private static String renderDetailsForText(Map<String, Object> details) {
    StringBuilder out = new StringBuilder().append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : details.entrySet()) {
      if (!first) {
        out.append(", ");
      }
      first = false;
      out.append(entry.getKey()).append('=');
      Object value = entry.getValue();
      if (value == null) {
        out.append("null");
      } else if (value instanceof String text) {
        out.append(escapeForText(text));
      } else {
        out.append(value);
      }
    }
    out.append('}');
    return out.toString();
  }
}
