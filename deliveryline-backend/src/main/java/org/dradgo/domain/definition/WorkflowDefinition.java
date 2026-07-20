package org.dradgo.domain.definition;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DefinitionKind;

/**
 * Story 3m-2 (AC5/AC9) — domain aggregate for a configurable workflow definition, mirroring the V48
 * {@code workflow_definitions} columns. A pure domain value object (dormant in 3m-2 — the run
 * engine that walks these is 3m-3, the preset seed is 3m-5); no adapter imports leak into the
 * domain (ArchUnit {@code ArchitectureBoundaryTest}).
 *
 * <p>Invariants follow the {@link org.dradgo.domain.project.Project} precedent: the {@code
 * publicId} guard routes through {@link PublicIdPrefixes#require} (typed {@code
 * DomainException(INVALID_ID_PREFIX)}); blank/null guards throw {@link IllegalArgumentException} /
 * {@link NullPointerException} synchronously.
 */
public record WorkflowDefinition(
    String publicId,
    String key,
    String name,
    DefinitionKind kind,
    OffsetDateTime archivedAt // nullable
    ) {

  public WorkflowDefinition {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.WORKFLOW_DEFINITION);
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("WorkflowDefinition key must be non-blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("WorkflowDefinition name must be non-blank");
    }
    Objects.requireNonNull(kind, "WorkflowDefinition kind must not be null");
  }
}
