package org.dradgo.application.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Story 4.19 (Reconciliation 5/6/8) — pure payload-shape helpers that extract the diff inputs from
 * the raw artifact payload bytes, mirroring {@code WorkflowInspectionService.getArtifactDetail} /
 * {@code parseSteps} but re-implemented here so {@link RevisionDeltaService} depends only on {@code
 * ArtifactService} + {@code RedactionPolicyService} (AC9 forbids depending on {@code
 * WorkflowInspectionService}).
 *
 * <p>Holds the only Jackson dependency in the {@code application.compare} package so the differ
 * classes stay java-only and the {@link RevisionDeltaService} collaborator surface stays clean — a
 * malformed payload never propagates a parse exception: it degrades to an empty body / no steps /
 * absent diff.
 */
final class ComparePayloads {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ComparePayloads() {}

  /** Spec payload → the markdown body (the payload bytes are the markdown, not JSON). */
  static String markdownBody(byte[] payload) {
    return payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
  }

  /**
   * implementationPlan payload → the ordered {@code steps} array as {@code List<String>} (non-blank
   * textual elements only), mirroring {@code WorkflowInspectionService.parseSteps}. Empty list when
   * the payload is absent, malformed, or carries no usable {@code steps} array.
   */
  static List<String> parseSteps(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return List.of();
    }
    try {
      JsonNode stepsNode = MAPPER.readTree(payload).path("steps");
      if (!stepsNode.isArray()) {
        return List.of();
      }
      List<String> steps = new ArrayList<>();
      for (JsonNode element : stepsNode) {
        if (element.isTextual() && !element.asText().isBlank()) {
          steps.add(element.asText());
        }
      }
      return List.copyOf(steps);
    } catch (IOException malformed) {
      return List.of();
    }
  }

  /**
   * True when {@code payload} is well-formed JSON carrying a {@code steps} array (even an empty
   * one) — i.e. {@link #parseSteps} returned an empty list because the plan genuinely has no steps,
   * NOT because the payload was absent or malformed. Lets the caller tell a real "no steps" apart
   * from a silently-swallowed parse failure (so two different-but-unparseable plans are not
   * reported as a no-meaningful-diff).
   */
  static boolean stepsPayloadParseable(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return false;
    }
    try {
      return MAPPER.readTree(payload).path("steps").isArray();
    } catch (IOException malformed) {
      return false;
    }
  }

  /**
   * prOutput payload → the resolved unified-diff text (the {@code diff} field), or {@code null}
   * when the payload is absent, malformed, or the {@code diff} field is missing/blank (frequently
   * absent — runners emit only the ephemeral {@code diffReference} pointer, Reconciliation 8).
   */
  static String prOutputDiff(byte[] payload) {
    if (payload == null || payload.length == 0) {
      return null;
    }
    try {
      String diff = MAPPER.readTree(payload).path("diff").asText(null);
      return (diff == null || diff.isBlank()) ? null : diff;
    } catch (IOException malformed) {
      return null;
    }
  }
}
