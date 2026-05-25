package org.dradgo.contract;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Story 2.12 AC5 — make-or-break: <strong>silent disappearance of a clarification answer is a
 * contract violation</strong>. For every {@code clarification.answered} event in any committed
 * fixture stream, there MUST subsequently appear (within the same run's event sequence) either:
 *
 * <ul>
 *   <li>a {@code clarification.accepted} event for the same {@code clarificationId} followed by one
 *       of {@code clarification.incorporated}, {@code clarification.superseded}, {@code
 *       clarification.rejectedInvalid}, OR
 *   <li>a {@code clarification.rejectedInvalid} event for the same {@code clarificationId} (the
 *       direct {@code answered → rejected_invalid} transition), OR
 *   <li>a {@code clarification.noEffectReason} event for the same {@code clarificationId} (the
 *       explicit no-op terminal).
 * </ul>
 *
 * <p>Trap T9: the negative case lives <strong>inline</strong> as a synthetic event list inside this
 * test class — committing a malformed fixture file would be rejected by {@link
 * FixtureEventStreamSchemaConformanceContractTest} at a lower layer and mask this contract test's
 * own assertion.
 *
 * <p>Tagged {@code @Tag("foundation-gate")} so the dedicated CI job picks it up.
 */
@Tag("contract")
@Tag("foundation-gate")
class ClarificationVisibleIncorporationContractTest {

  private static final Path FIXTURE_ROOT =
      Path.of("src", "test", "resources", "fixture-event-streams");

  private static final String ANSWERED = "clarification.answered";
  private static final String ACCEPTED = "clarification.accepted";
  private static final String INCORPORATED = "clarification.incorporated";
  private static final String SUPERSEDED = "clarification.superseded";
  private static final String REJECTED_INVALID = "clarification.rejectedInvalid";
  private static final String NO_EFFECT_REASON = "clarification.noEffectReason";

  @Test
  void everyAnsweredClarificationInEveryFixtureHasADownstreamLifecycleChain() throws IOException {
    if (!Files.isDirectory(FIXTURE_ROOT)) {
      fail("[story 2.12] fixture-event-streams directory missing at " + FIXTURE_ROOT.toAbsolutePath());
      return;
    }
    ObjectMapper mapper = new ObjectMapper();
    List<String> violations = new ArrayList<>();
    List<Path> fixtures = listFixtureJson();
    for (Path fixture : fixtures) {
      String displayName = FIXTURE_ROOT.relativize(fixture).toString();
      JsonNode root = mapper.readTree(fixture.toFile());
      JsonNode events = root.path("events");
      if (!events.isArray()) continue;
      String fixtureWorkflowRunId = root.path("workflowRunId").asText("");
      List<EventRef> refs = new ArrayList<>();
      for (JsonNode event : events) {
        String type = event.path("eventType").asText("");
        String runId =
            event.path("workflowRunId").isMissingNode()
                ? fixtureWorkflowRunId
                : event.path("workflowRunId").asText(fixtureWorkflowRunId);
        String clrId = event.path("details").path("clarificationId").asText(null);
        // Patch #4 — require non-null clarificationId on clarification.* events. Other events
        // (workflow.*, artifact.*) legitimately have no clarificationId; the guard only fires
        // for events whose type starts with "clarification.".
        if (type.startsWith("clarification.") && (clrId == null || clrId.isEmpty())) {
          violations.add(
              displayName
                  + ": '"
                  + type
                  + "' event missing details.clarificationId (required for clarification.* events)");
          continue;
        }
        if (clrId == null || clrId.isEmpty()) continue;
        refs.add(new EventRef(type, runId, clrId));
      }
      List<String> fixtureViolations = assertVisibleIncorporation(refs);
      for (String v : fixtureViolations) {
        violations.add(displayName + ": " + v);
      }
    }
    if (!violations.isEmpty()) {
      fail(
          "[story 2.12 AC5] visible-incorporation contract violated ("
              + violations.size()
              + " issue"
              + (violations.size() == 1 ? "" : "s")
              + "): "
              + String.join("; ", violations));
    }
  }

  @Test
  void inlineNegativeStreamWithDanglingAnsweredEventIsRejected() {
    // Trap T9: negative case lives inline so we exercise the same assertion logic on a synthetic
    // stream that violates the contract. A clarification.answered followed by no follow-up is
    // the canonical regression we must catch.
    List<EventRef> dangling =
        List.of(
            new EventRef("workflow.stateChanged", "run_neg_001", null),
            new EventRef(ANSWERED, "run_neg_001", "clr_neg_dangling_001"));
    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () -> {
              List<String> violations = assertVisibleIncorporation(dangling);
              if (!violations.isEmpty()) {
                throw new AssertionError(String.join("; ", violations));
              }
            });
    if (!thrown.getMessage().contains("clr_neg_dangling_001")) {
      fail(
          "[story 2.12 AC5] negative inline case should call out the dangling clarification id "
              + "but message was: "
              + thrown.getMessage());
    }
  }

  @Test
  void inlineNegativeStreamWithImpossibleTransitionShapeIsRejected() {
    // Patch #2 — transition-shape validation: [answered, rejectedInvalid, accepted, incorporated]
    // is impossible per AC2 (rejected_invalid is terminal; accepted CANNOT follow it). The chain
    // check used to pass this because both `answered → ... → accepted → incorporated` AND
    // `answered → rejectedInvalid` were "present". Now we walk the shape and require ordering.
    List<EventRef> impossible =
        List.of(
            new EventRef(ANSWERED, "run_neg_002", "clr_neg_shape_001"),
            new EventRef(REJECTED_INVALID, "run_neg_002", "clr_neg_shape_001"),
            new EventRef(ACCEPTED, "run_neg_002", "clr_neg_shape_001"),
            new EventRef(INCORPORATED, "run_neg_002", "clr_neg_shape_001"));
    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () -> {
              List<String> violations = assertVisibleIncorporation(impossible);
              if (!violations.isEmpty()) {
                throw new AssertionError(String.join("; ", violations));
              }
            });
    if (!thrown.getMessage().contains("clr_neg_shape_001")) {
      fail(
          "[story 2.12 AC5] impossible-transition negative case should call out the clarificationId "
              + "but message was: "
              + thrown.getMessage());
    }
  }

  @Test
  void inlineNegativeStreamWithAcceptedThenRejectedInvalidIsRejected() {
    List<EventRef> impossible =
        List.of(
            new EventRef(ANSWERED, "run_neg_003", "clr_neg_shape_accepted_rejected"),
            new EventRef(ACCEPTED, "run_neg_003", "clr_neg_shape_accepted_rejected"),
            new EventRef(REJECTED_INVALID, "run_neg_003", "clr_neg_shape_accepted_rejected"));
    AssertionError thrown =
        assertThrows(
            AssertionError.class,
            () -> {
              List<String> violations = assertVisibleIncorporation(impossible);
              if (!violations.isEmpty()) {
                throw new AssertionError(String.join("; ", violations));
              }
            });
    if (!thrown.getMessage().contains("clr_neg_shape_accepted_rejected")) {
      fail(
          "[story 2.12 AC5] accepted-then-rejectedInvalid negative case should call out the clarificationId "
              + "but message was: "
              + thrown.getMessage());
    }
  }

  @Test
  void duplicateClarificationIdsAcrossRunsAreScopedIndependently() {
    // Patch #1 — group by (workflowRunId, clarificationId) so a malformed event stream in run A
    // can't satisfy the contract for the same clarificationId in run B. The two runs must each
    // close the lifecycle independently. Here run A is well-formed; run B has a dangling answer.
    List<EventRef> twoRuns =
        List.of(
            new EventRef(ANSWERED, "run_a", "clr_shared_001"),
            new EventRef(ACCEPTED, "run_a", "clr_shared_001"),
            new EventRef(INCORPORATED, "run_a", "clr_shared_001"),
            new EventRef(ANSWERED, "run_b", "clr_shared_001")); // dangling in run B
    List<String> violations = assertVisibleIncorporation(twoRuns);
    if (violations.size() != 1
        || !violations.get(0).contains("run_b")
        || !violations.get(0).contains("clr_shared_001")) {
      fail(
          "[story 2.12 AC5] expected exactly one violation calling out (run_b, clr_shared_001); got: "
              + violations);
    }
  }

  /**
   * Returns a list of violations (empty list = contract satisfied). Walks the event refs in order;
   * for every {@code clarification.answered} record we require subsequent events for the same
   * (workflowRunId, clarificationId) pair that close the lifecycle per AC5 AND match a legal
   * state-machine transition shape per AC2.
   *
   * <p>Patches addressed:
   * <ul>
   *   <li>#1 group by composite key (workflowRunId, clarificationId), not clarificationId alone.
   *   <li>#2 validate transition shape — rejectedInvalid must directly follow answered with NO
   *       preceding accepted; accepted must precede any incorporated/superseded.
   *   <li>#4 events whose type starts with "clarification." MUST carry details.clarificationId
   *       (enforced at the parse boundary in the caller).
   * </ul>
   */
  private static List<String> assertVisibleIncorporation(List<EventRef> events) {
    Map<String, List<String>> byScope = new LinkedHashMap<>();
    for (EventRef ref : events) {
      if (ref.clarificationId() == null) continue;
      byScope
          .computeIfAbsent(scopeKey(ref.workflowRunId(), ref.clarificationId()), k -> new ArrayList<>())
          .add(ref.eventType());
    }
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : byScope.entrySet()) {
      String scope = entry.getKey();
      List<String> types = entry.getValue();
      if (!types.contains(ANSWERED)) continue;
      int answeredIdx = types.indexOf(ANSWERED);
      List<String> trailing = types.subList(answeredIdx + 1, types.size());
      // Walk the trailing sequence linearly so we can enforce transition ordering — not just
      // chain existence (patch #2).
      boolean sawAccepted = false;
      boolean sawClosingTerminal = false; // incorporated | superseded | rejectedInvalid
      boolean shapeViolation = false;
      String shapeViolationDetail = null;
      for (String t : trailing) {
        if (ANSWERED.equals(t)) {
          // Re-answer is not part of AC2 — story 2.11 invariant prevents resubmission. Ignore
          // (the deferred contract-test gap acknowledges this).
          continue;
        }
        if (ACCEPTED.equals(t)) {
          if (sawClosingTerminal) {
            shapeViolation = true;
            shapeViolationDetail = "ACCEPTED after a terminal event";
            break;
          }
          sawAccepted = true;
          continue;
        }
        if (INCORPORATED.equals(t) || SUPERSEDED.equals(t)) {
          if (!sawAccepted) {
            shapeViolation = true;
            shapeViolationDetail = t + " without preceding ACCEPTED";
            break;
          }
          sawClosingTerminal = true;
          continue;
        }
        if (REJECTED_INVALID.equals(t)) {
          if (sawAccepted) {
            shapeViolation = true;
            shapeViolationDetail = "REJECTED_INVALID after ACCEPTED";
            break;
          }
          sawClosingTerminal = true;
          continue;
        }
        if (NO_EFFECT_REASON.equals(t)) {
          sawClosingTerminal = true;
          continue;
        }
      }
      boolean ok = sawClosingTerminal && !shapeViolation;
      if (!ok) {
        StringBuilder msg = new StringBuilder(scope).append(" has 'clarification.answered' ");
        if (shapeViolation) {
          msg.append("with illegal transition shape (").append(shapeViolationDetail).append(")");
        } else {
          msg.append("with no downstream chain (expected accepted+terminal or rejectedInvalid or noEffectReason)");
        }
        msg.append("; seen=[").append(String.join(",", trailing)).append("]");
        violations.add(msg.toString());
      }
    }
    return violations;
  }

  private static String scopeKey(String workflowRunId, String clarificationId) {
    return (workflowRunId == null ? "" : workflowRunId) + "/" + clarificationId;
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

  private record EventRef(String eventType, String workflowRunId, String clarificationId) {}
}
