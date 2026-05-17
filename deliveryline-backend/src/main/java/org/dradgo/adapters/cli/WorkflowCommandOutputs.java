package org.dradgo.adapters.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.workflow.WorkflowInspectionService.LatestArtifactView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowEventView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
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
