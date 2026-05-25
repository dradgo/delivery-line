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
      List<EventRef> refs = new ArrayList<>();
      for (JsonNode event : events) {
        String type = event.path("eventType").asText("");
        String clrId = event.path("details").path("clarificationId").asText(null);
        if (clrId == null || clrId.isEmpty()) continue;
        refs.add(new EventRef(type, clrId));
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
            new EventRef("workflow.stateChanged", null),
            new EventRef(ANSWERED, "clr_neg_dangling_001"));
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

  /**
   * Returns a list of violations (empty list = contract satisfied). Walks the event refs in order;
   * for every {@code clarification.answered} record we require subsequent events for the same
   * clarificationId that close the lifecycle per AC5.
   */
  private static List<String> assertVisibleIncorporation(List<EventRef> events) {
    Map<String, List<String>> byClarification = new LinkedHashMap<>();
    for (EventRef ref : events) {
      if (ref.clarificationId() == null) continue;
      byClarification.computeIfAbsent(ref.clarificationId(), k -> new ArrayList<>()).add(ref.eventType());
    }
    List<String> violations = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : byClarification.entrySet()) {
      String clr = entry.getKey();
      List<String> types = entry.getValue();
      if (!types.contains(ANSWERED)) continue;
      // Find the index of the first answered and require a closing event after it.
      int answeredIdx = types.indexOf(ANSWERED);
      List<String> trailing = types.subList(answeredIdx + 1, types.size());
      boolean acceptedFollowed = false;
      boolean closingTerminalAfterAccepted = false;
      boolean directRejectedInvalid = false;
      boolean noEffectReasonClose = false;
      for (int i = 0; i < trailing.size(); i++) {
        String t = trailing.get(i);
        if (ACCEPTED.equals(t)) {
          acceptedFollowed = true;
          // Look ahead for INCORPORATED / SUPERSEDED / REJECTED_INVALID after this accepted.
          for (int j = i + 1; j < trailing.size(); j++) {
            String later = trailing.get(j);
            if (INCORPORATED.equals(later)
                || SUPERSEDED.equals(later)
                || REJECTED_INVALID.equals(later)) {
              closingTerminalAfterAccepted = true;
              break;
            }
          }
        }
        if (REJECTED_INVALID.equals(t) && !acceptedFollowed) {
          directRejectedInvalid = true;
        }
        if (NO_EFFECT_REASON.equals(t)) {
          noEffectReasonClose = true;
        }
      }
      boolean ok =
          (acceptedFollowed && closingTerminalAfterAccepted)
              || directRejectedInvalid
              || noEffectReasonClose;
      if (!ok) {
        violations.add(
            "clarificationId="
                + clr
                + " has 'clarification.answered' with no downstream chain (expected accepted+terminal or rejectedInvalid or noEffectReason); seen=["
                + String.join(",", trailing)
                + "]");
      }
    }
    return violations;
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

  private record EventRef(String eventType, String clarificationId) {}
}
