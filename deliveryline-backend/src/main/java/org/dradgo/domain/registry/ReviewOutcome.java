package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3d-1 (AC3/AC5) — the advisory verdict of a per-step reviewer model, mirroring the {@link
 * ArtifactStatus}/{@link ProjectStatus} registry shape. Persisted to {@code step_reviews.outcome}
 * (the {@code ck_step_reviews_outcome} CHECK) and surfaced through the API (3d-2 Decision Bar
 * verdict panel), so it is drift-tested against both the DB CHECK and the {@code reviewOutcomes}
 * API placeholder (DD-2).
 *
 * <p>Wire values are lowercase {@code pass}/{@code concern}/{@code fail}. The verdict is
 * <em>advisory now, gating-capable later</em> (ADR 0026): no progression logic consults it in Epic
 * 3d.
 */
public enum ReviewOutcome implements RegistryValue {
  PASS("pass"),
  CONCERN("concern"),
  FAIL("fail");

  private static final Map<String, ReviewOutcome> LOOKUP = RegistryParsers.index(values());

  private final String value;

  ReviewOutcome(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static ReviewOutcome fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static ReviewOutcome fromValue(String rawValue, String field) {
    return RegistryParsers.parse("ReviewOutcome", rawValue, field, LOOKUP);
  }
}
