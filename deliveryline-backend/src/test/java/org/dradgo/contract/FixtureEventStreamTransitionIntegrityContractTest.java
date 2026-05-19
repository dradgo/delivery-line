package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.dradgo.application.workflow.WorkflowTransitionTable;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.WorkflowEventType;
import org.dradgo.domain.registry.WorkflowState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 1.23 AC6 - fixture event-stream transition integrity.
 *
 * <p>Every event that mutates workflow state must use {@code workflow.stateChanged} and encode a
 * legal transition per {@link WorkflowTransitionTable}. The only allowed partial transition shape
 * is the initial Inbox bootstrap event ({@code priorState=null, resultingState=Inbox}).
 *
 * <p>Special-case handling matches {@link WorkflowTransitionTable#assertTransitionAllowed}:
 * {@code Executing -> Failed} requires a non-null {@link FailureCategory} value from the
 * runner-failure set; other transitions must not carry a failure category. {@code TakenOver} and
 * {@code Reconciled} targets must carry a non-blank reason.
 */
@Tag("contract")
@Tag("foundation-gate")
class FixtureEventStreamTransitionIntegrityContractTest {

  private static final Path FIXTURE_ROOT =
      Path.of("src", "test", "resources", "fixture-event-streams");

  @Test
  void everyFixtureEventEncodesALegalTransition() throws IOException {
    WorkflowTransitionTable table = WorkflowTransitionTable.defaultTable();
    ObjectMapper mapper = new ObjectMapper();
    List<String> violations = new ArrayList<>();

    if (!Files.isDirectory(FIXTURE_ROOT)) {
      fail(
          "[story 1.23] fixture-event-streams directory missing at "
              + FIXTURE_ROOT.toAbsolutePath());
      return;
    }

    List<Path> fixtures = listFixtureJson();
    if (fixtures.isEmpty()) {
      fail("[story 1.23] no fixture .json files found under " + FIXTURE_ROOT.toAbsolutePath());
      return;
    }

    for (Path fixture : fixtures) {
      String displayName = FIXTURE_ROOT.relativize(fixture).toString();
      JsonNode root = mapper.readTree(fixture.toFile());
      JsonNode events = root.path("events");
      if (!events.isArray()) {
        violations.add(displayName + " events is missing or not an array");
        continue;
      }
      int idx = 0;
      for (JsonNode event : events) {
        WorkflowEventType eventType =
            parseEventTypeNullable(displayName, idx, event.path("eventType"), violations);
        JsonNode priorNode = event.path("priorState");
        JsonNode resultingNode = event.path("resultingState");
        boolean hasPrior = priorNode.isTextual();
        boolean hasResulting = resultingNode.isTextual();

        if (!hasPrior && !hasResulting) {
          if (eventType == WorkflowEventType.WORKFLOW_STATE_CHANGED) {
            violations.add(
                displayName
                    + " events["
                    + idx
                    + "] uses workflow.stateChanged but omits both priorState and resultingState");
          }
          idx++;
          continue;
        }

        if (!hasPrior && hasResulting) {
          WorkflowState bootstrapState = parseState(resultingNode.asText());
          if (eventType != WorkflowEventType.WORKFLOW_STATE_CHANGED) {
            violations.add(
                displayName
                    + " events["
                    + idx
                    + "] bootstrap state change must use workflow.stateChanged");
          } else if (idx != 0 || bootstrapState != WorkflowState.INBOX) {
            violations.add(
                displayName
                    + " events["
                    + idx
                    + "] invalid bootstrap transition shape; only the first event may move into"
                    + " Inbox with null priorState");
          }
          idx++;
          continue;
        }

        if (hasPrior != hasResulting) {
          violations.add(
              displayName
                  + " events["
                  + idx
                  + "] must provide priorState and resultingState together");
          idx++;
          continue;
        }

        if (eventType != WorkflowEventType.WORKFLOW_STATE_CHANGED) {
          violations.add(
              displayName
                  + " events["
                  + idx
                  + "] encodes workflow state change "
                  + priorNode.asText()
                  + " -> "
                  + resultingNode.asText()
                  + " with non-state eventType="
                  + event.path("eventType").asText());
          idx++;
          continue;
        }

        WorkflowState prior = parseState(priorNode.asText());
        WorkflowState resulting = parseState(resultingNode.asText());
        if (prior == null) {
          violations.add(
              displayName + " events[" + idx + "].priorState is unknown: " + priorNode.asText());
          idx++;
          continue;
        }
        if (resulting == null) {
          violations.add(
              displayName
                  + " events["
                  + idx
                  + "].resultingState is unknown: "
                  + resultingNode.asText());
          idx++;
          continue;
        }

        FailureCategory failureCategory = parseFailureCategoryNullable(event.path("failureCategory"));
        String reason = event.path("reason").isTextual() ? event.path("reason").asText() : null;
        try {
          table.assertTransitionAllowed(
              "run_foundation_gate_fixture_probe", prior, resulting, failureCategory, reason);
        } catch (DomainException domainException) {
          violations.add(
              displayName
                  + " events["
                  + idx
                  + "] encodes illegal transition "
                  + prior.value()
                  + " -> "
                  + resulting.value()
                  + " (failureCategory="
                  + (failureCategory == null ? "null" : failureCategory.value())
                  + "): "
                  + domainException.errorCode().value()
                  + " - "
                  + domainException.getMessage());
        }
        idx++;
      }
    }

    if (!violations.isEmpty()) {
      fail(
          "[story 1.23] fixture event-stream transition integrity violations ("
              + violations.size()
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

  private static WorkflowEventType parseEventTypeNullable(
      String displayName, int idx, JsonNode node, List<String> violations) {
    if (!node.isTextual()) {
      violations.add(displayName + " events[" + idx + "].eventType is missing or non-textual");
      return null;
    }
    try {
      return WorkflowEventType.fromValue(node.asText(), "eventType");
    } catch (IllegalArgumentException ex) {
      violations.add(displayName + " events[" + idx + "].eventType is unknown: " + node.asText());
      return null;
    }
  }

  private static WorkflowState parseState(String wireValue) {
    for (WorkflowState state : WorkflowState.values()) {
      if (state.value().equals(wireValue)) {
        return state;
      }
    }
    return null;
  }

  private static FailureCategory parseFailureCategoryNullable(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalStateException(
          "[story 1.23] fixture event-stream failureCategory is non-text node ("
              + node.getNodeType()
              + ") - expected string enum value or null/missing");
    }
    String wireValue = node.asText();
    if (wireValue.isBlank()) {
      return null;
    }
    for (FailureCategory category : FailureCategory.values()) {
      if (category.value().equals(wireValue)) {
        return category;
      }
    }
    throw new IllegalStateException(
        "[story 1.23] fixture event-stream failureCategory=\""
            + wireValue
            + "\" is not a known FailureCategory enum value - fix the fixture typo or add the new"
            + " category to the registry");
  }
}
