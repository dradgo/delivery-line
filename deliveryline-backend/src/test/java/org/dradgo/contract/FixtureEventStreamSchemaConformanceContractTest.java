package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 1.23 Task 7 — fixture event-stream schema conformance.
 *
 * <p>Every {@code .json} fixture under {@code src/test/resources/fixture-event-streams/} (excluding
 * the {@code schema/} sub-directory) MUST conform to the authoritative wire shape described by
 * {@code workflow-events-response.schema.json}. The check is programmatic to keep the test free of
 * additional schema-validator dependencies, but the assertions mirror the required-fields / enum /
 * pattern / forbidden-keys constraints declared in the schema file.
 *
 * <p>Tagged {@code @Tag("contract")} so it runs in the existing {@code backend-contract-tests} CI
 * tier (defense-in-depth: a fixture-only change fails contract tests even before foundation-gate
 * runs) AND {@code @Tag("foundation-gate")} so the dedicated {@code foundation-gate} job's {@code
 * <groups>} filter picks it up too.
 */
@Tag("contract")
@Tag("foundation-gate")
class FixtureEventStreamSchemaConformanceContractTest {

  private static final Path FIXTURE_ROOT =
      Path.of("src", "test", "resources", "fixture-event-streams");
  private static final Path SCHEMA_FILE =
      FIXTURE_ROOT.resolve("schema").resolve("workflow-events-response.schema.json");

  private static final Pattern RUN_ID = Pattern.compile("^run_[A-Za-z0-9_]{4,}$");
  private static final Pattern EVENT_ID = Pattern.compile("^evt_[A-Za-z0-9_]{4,}$");
  private static final Pattern RECOVERY_ACTION_ID = Pattern.compile("^rcv_[A-Za-z0-9_]{4,}$");

  private static final Set<String> WORKFLOW_STATE_VALUES =
      Arrays.stream(WorkflowState.values())
          .map(WorkflowState::value)
          .collect(Collectors.toUnmodifiableSet());
  private static final Set<String> EVENT_TYPE_VALUES =
      Arrays.stream(WorkflowEventType.values())
          .map(WorkflowEventType::value)
          .collect(Collectors.toUnmodifiableSet());
  private static final Set<String> ACTOR_TYPE_VALUES =
      Arrays.stream(ActorType.values())
          .map(ActorType::value)
          .collect(Collectors.toUnmodifiableSet());
  private static final Set<String> FAILURE_CATEGORY_VALUES =
      Arrays.stream(FailureCategory.values())
          .map(FailureCategory::value)
          .collect(Collectors.toUnmodifiableSet());
  private static final Set<String> ARTIFACT_VARIANTS =
      Set.of("spec", "implementationPlan", "prOutput");

  private static final Map<String, String> REQUIRED_EVENT_FIELDS =
      Map.ofEntries(
          Map.entry("publicId", "string"),
          Map.entry("workflowRunPublicId", "string"),
          Map.entry("eventType", "string"),
          Map.entry("actorIdentity", "string"),
          Map.entry("actorType", "string"),
          Map.entry("interventionMarker", "boolean"),
          Map.entry("createdAt", "string"),
          Map.entry("details", "object"));

  /**
   * Authoritative schema-validator pass (story 1.23 review patch P16). Wires {@code
   * com.networknt:json-schema-validator} draft-2020-12 against {@code
   * workflow-events-response.schema.json} and runs every fixture through it. The programmatic-check
   * method below stays as defense-in-depth: a hand-rolled subset of the schema rules that catches
   * things schema validation would also catch (so a schema-file regression does not silently weaken
   * the gate) plus a handful of operational invariants (server-only keys forbidden,
   * deterministic-id patterns, ISO-8601 parsability) the schema does not encode.
   */
  @Test
  void everyFixtureJsonValidatesAgainstTheNetworkntJsonSchemaValidator() throws IOException {
    if (!Files.isRegularFile(SCHEMA_FILE)) {
      fail(
          "[story 1.23] schema file missing at "
              + SCHEMA_FILE.toAbsolutePath()
              + " — fixture event-stream contract cannot be loaded");
      return;
    }
    if (!Files.isDirectory(FIXTURE_ROOT)) {
      fail(
          "[story 1.23] fixture-event-streams directory missing at "
              + FIXTURE_ROOT.toAbsolutePath());
      return;
    }

    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    String schemaBody = Files.readString(SCHEMA_FILE);
    Schema schema = registry.getSchema(schemaBody, InputFormat.JSON);

    List<String> violations = new ArrayList<>();
    List<Path> fixtures = listFixtureJson();
    if (fixtures.isEmpty()) {
      fail(
          "[story 1.23] no fixture .json files found under "
              + FIXTURE_ROOT.toAbsolutePath()
              + " (excluding schema/) — silent corpus violates AC4");
      return;
    }

    for (Path fixture : fixtures) {
      String displayName = FIXTURE_ROOT.relativize(fixture).toString();
      String body = Files.readString(fixture);
      Result result = schema.walk(body, InputFormat.JSON, true);
      List<Error> errors = result.getErrors();
      for (Error error : errors) {
        String where =
            error.getInstanceLocation() == null ? "$" : error.getInstanceLocation().toString();
        violations.add(displayName + " @ " + where + ": " + error.getMessage());
      }
    }

    if (!violations.isEmpty()) {
      fail(
          "[story 1.23] fixture event-stream schema-validator conformance failed ("
              + violations.size()
              + " issue"
              + (violations.size() == 1 ? "" : "s")
              + "): "
              + String.join("; ", violations));
    }
  }

  @Test
  void everyFixtureJsonConformsToTheWireSchema() throws IOException {
    List<String> violations = new ArrayList<>();

    if (!Files.isRegularFile(SCHEMA_FILE)) {
      fail(
          "[story 1.23] schema file missing at "
              + SCHEMA_FILE.toAbsolutePath()
              + " — fixture event-stream contract cannot be loaded");
      return;
    }
    if (!Files.isDirectory(FIXTURE_ROOT)) {
      fail(
          "[story 1.23] fixture-event-streams directory missing at "
              + FIXTURE_ROOT.toAbsolutePath());
      return;
    }

    ObjectMapper mapper = new ObjectMapper();
    List<Path> fixtures = listFixtureJson();
    if (fixtures.isEmpty()) {
      fail(
          "[story 1.23] no fixture .json files found under "
              + FIXTURE_ROOT.toAbsolutePath()
              + " (excluding schema/) — silent corpus violates AC4");
      return;
    }

    for (Path fixture : fixtures) {
      String displayName = FIXTURE_ROOT.relativize(fixture).toString();
      try {
        JsonNode root = mapper.readTree(fixture.toFile());
        validateTopLevel(root, displayName, violations);
        validateWorkflowRun(root.path("workflowRun"), displayName, violations);
        validateEvents(root.path("events"), displayName, violations);
      } catch (IOException exception) {
        violations.add(
            "fixture " + displayName + " failed to parse as JSON: " + exception.getMessage());
      }
    }

    if (!violations.isEmpty()) {
      fail(
          "[story 1.23] fixture event-stream schema conformance failed ("
              + violations.size()
              + " issue"
              + (violations.size() == 1 ? "" : "s")
              + "): "
              + String.join("; ", violations));
    }
  }

  private static List<Path> listFixtureJson() throws IOException {
    try (Stream<Path> stream = Files.walk(FIXTURE_ROOT)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.startsWith(FIXTURE_ROOT.resolve("schema")))
          .sorted()
          .toList();
    }
  }

  private static void validateTopLevel(JsonNode root, String fixture, List<String> violations) {
    if (!root.isObject()) {
      violations.add(fixture + " root is not a JSON object");
      return;
    }
    if (!root.has("workflowRun")) {
      violations.add(fixture + " missing top-level field 'workflowRun'");
    }
    if (!root.has("events")) {
      violations.add(fixture + " missing top-level field 'events'");
    }
  }

  private static void validateWorkflowRun(
      JsonNode workflowRun, String fixture, List<String> violations) {
    if (!workflowRun.isObject()) {
      violations.add(fixture + " workflowRun is not a JSON object");
      return;
    }
    requireString(workflowRun, "publicId", fixture, "workflowRun", violations);
    requireString(workflowRun, "ticketRef", fixture, "workflowRun", violations);
    requireString(workflowRun, "createdAt", fixture, "workflowRun", violations);
    JsonNode runId = workflowRun.path("publicId");
    if (runId.isTextual() && !RUN_ID.matcher(runId.asText()).matches()) {
      violations.add(
          fixture + " workflowRun.publicId does not match run_<alnum>: " + runId.asText());
    }
    JsonNode createdAt = workflowRun.path("createdAt");
    if (createdAt.isTextual()) {
      try {
        OffsetDateTime.parse(createdAt.asText());
      } catch (DateTimeParseException ex) {
        violations.add(
            fixture
                + " workflowRun.createdAt is not a valid ISO-8601 offset date-time: "
                + createdAt.asText());
      }
    }
    JsonNode terminal = workflowRun.path("terminalState");
    if (!terminal.isMissingNode() && !terminal.isNull()) {
      if (!terminal.isTextual() || !WORKFLOW_STATE_VALUES.contains(terminal.asText())) {
        violations.add(
            fixture + " workflowRun.terminalState is not a known WorkflowState value: " + terminal);
      }
    }
  }

  private static void validateEvents(JsonNode events, String fixture, List<String> violations) {
    if (!events.isArray()) {
      violations.add(fixture + " events is not an array");
      return;
    }
    if (events.isEmpty()) {
      violations.add(fixture + " events array is empty");
      return;
    }
    int idx = 0;
    for (JsonNode event : events) {
      validateEvent(event, fixture, idx, violations);
      idx++;
    }
  }

  private static void validateEvent(
      JsonNode event, String fixture, int idx, List<String> violations) {
    String ctx = fixture + " events[" + idx + "]";
    if (!event.isObject()) {
      violations.add(ctx + " is not an object");
      return;
    }

    REQUIRED_EVENT_FIELDS.forEach(
        (field, kind) -> {
          JsonNode node = event.path(field);
          if (node.isMissingNode()) {
            violations.add(ctx + " missing required field '" + field + "'");
            return;
          }
          switch (kind) {
            case "string":
              if (!node.isTextual() || node.asText().isBlank()) {
                violations.add(ctx + "." + field + " must be a non-blank string");
              }
              break;
            case "boolean":
              if (!node.isBoolean()) {
                violations.add(ctx + "." + field + " must be boolean");
              }
              break;
            case "object":
              if (!node.isObject()) {
                violations.add(ctx + "." + field + " must be an object");
              }
              break;
            default:
              break;
          }
        });

    JsonNode publicId = event.path("publicId");
    if (publicId.isTextual() && !EVENT_ID.matcher(publicId.asText()).matches()) {
      violations.add(ctx + ".publicId does not match evt_<alnum>: " + publicId.asText());
    }
    JsonNode runRef = event.path("workflowRunPublicId");
    if (runRef.isTextual() && !RUN_ID.matcher(runRef.asText()).matches()) {
      violations.add(ctx + ".workflowRunPublicId does not match run_<alnum>: " + runRef.asText());
    }
    JsonNode eventType = event.path("eventType");
    if (eventType.isTextual() && !EVENT_TYPE_VALUES.contains(eventType.asText())) {
      violations.add(ctx + ".eventType is not a known WorkflowEventType: " + eventType.asText());
    }
    JsonNode actorType = event.path("actorType");
    if (actorType.isTextual() && !ACTOR_TYPE_VALUES.contains(actorType.asText())) {
      violations.add(ctx + ".actorType is not a known ActorType: " + actorType.asText());
    }
    JsonNode prior = event.path("priorState");
    if (prior.isTextual() && !WORKFLOW_STATE_VALUES.contains(prior.asText())) {
      violations.add(ctx + ".priorState is not a known WorkflowState: " + prior.asText());
    }
    JsonNode resulting = event.path("resultingState");
    if (resulting.isTextual() && !WORKFLOW_STATE_VALUES.contains(resulting.asText())) {
      violations.add(ctx + ".resultingState is not a known WorkflowState: " + resulting.asText());
    }
    JsonNode failureCategory = event.path("failureCategory");
    if (failureCategory.isTextual()
        && !FAILURE_CATEGORY_VALUES.contains(failureCategory.asText())) {
      violations.add(
          ctx + ".failureCategory is not a known FailureCategory: " + failureCategory.asText());
    }
    JsonNode createdAt = event.path("createdAt");
    if (createdAt.isTextual()) {
      try {
        OffsetDateTime.parse(createdAt.asText());
      } catch (DateTimeParseException ex) {
        violations.add(
            ctx + ".createdAt is not a valid ISO-8601 offset date-time: " + createdAt.asText());
      }
    }

    JsonNode details = event.path("details");
    if (details.isObject()) {
      if (details.has("idempotencyKey")) {
        violations.add(
            ctx
                + ".details contains 'idempotencyKey' — this is a server-only key and MUST NOT"
                + " appear in fixture wire output");
      }
      JsonNode variant = details.path("artifactVariant");
      if (variant.isTextual() && !ARTIFACT_VARIANTS.contains(variant.asText())) {
        violations.add(
            ctx + ".details.artifactVariant is not a known variant: " + variant.asText());
      }
      JsonNode triggeringEventId = details.path("triggeringEventId");
      if (triggeringEventId.isTextual()
          && !EVENT_ID.matcher(triggeringEventId.asText()).matches()) {
        violations.add(
            ctx
                + ".details.triggeringEventId does not match evt_<alnum>: "
                + triggeringEventId.asText());
      }
      JsonNode recoveryActionId = details.path("recoveryActionId");
      if (recoveryActionId.isTextual()
          && !RECOVERY_ACTION_ID.matcher(recoveryActionId.asText()).matches()) {
        violations.add(
            ctx
                + ".details.recoveryActionId does not match rcv_<alnum>: "
                + recoveryActionId.asText());
      }
    }
  }

  private static void requireString(
      JsonNode node, String field, String fixture, String parent, List<String> violations) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      violations.add(fixture + " " + parent + "." + field + " must be a non-blank string");
    }
  }
}
