package org.dradgo.domain.definition;

import java.util.Objects;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ArtifactKind;

/**
 * Story 3m-2 (AC5/AC9) — domain aggregate for one ordered step of a {@link WorkflowDefinition},
 * mirroring the V48 {@code workflow_definition_steps} columns. A pure domain value object (dormant
 * in 3m-2).
 *
 * <p>DD-1: {@code stepKey} and {@code runnerKind} are free/opaque {@code String}s with NO registry
 * type here (custom definitions author arbitrary keys; {@code runnerKind} is validated at 3m-4 bind
 * time via {@code RunnerKind.fromValue}). {@code bmadRole} is the optional BMAD role-prompt nudge.
 * {@code producesArtifactKind} is the OPTIONAL typed artifact kind ({@code null} ⇒ the step
 * produces no typed artifact). Invariants: non-blank {@code publicId} (typed prefix guard) /
 * non-blank {@code stepKey}; {@code stepIndex} ≥ 0.
 */
public record StepDefinition(
    String publicId,
    Long definitionId,
    int stepIndex,
    String stepKey,
    String runnerKind, // nullable, free text (DD-1)
    String bmadRole, // nullable
    boolean humanGated,
    ArtifactKind producesArtifactKind // nullable
    ) {

  public StepDefinition {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.WORKFLOW_DEFINITION_STEP);
    // definition_id is `not null` in V48 — guard it here so a missing parent fails synchronously
    // and
    // attributably, rather than as an opaque DataIntegrityViolationException at flush time (mirrors
    // WorkflowDefinition's requireNonNull on `kind`).
    Objects.requireNonNull(definitionId, "StepDefinition definitionId must not be null");
    if (stepKey == null || stepKey.isBlank()) {
      throw new IllegalArgumentException("StepDefinition stepKey must be non-blank");
    }
    if (stepIndex < 0) {
      throw new IllegalArgumentException(
          "StepDefinition stepIndex must be >= 0 but was " + stepIndex);
    }
    // DD-1 free-text fields are OPTIONAL, but a blank is not a valid "unset": a persisted "" would
    // make RunnerKind.fromValue("") throw UNKNOWN_REGISTRY_VALUE at 3m-4 bind time instead of
    // reading as "no binding". Mirrors Project.reviewerModelKind's "non-blank when set" guard.
    if (runnerKind != null && runnerKind.isBlank()) {
      throw new IllegalArgumentException("StepDefinition runnerKind must be non-blank when set");
    }
    if (bmadRole != null && bmadRole.isBlank()) {
      throw new IllegalArgumentException("StepDefinition bmadRole must be non-blank when set");
    }
  }
}
