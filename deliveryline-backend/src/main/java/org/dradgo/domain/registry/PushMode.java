package org.dradgo.domain.registry;

import java.util.Map;

/**
 * Story 3h-4 (AC1, FR78) — per-project delivery push mode. A closed, self-owned enum selecting
 * whether the backend push (and PR/MR creation) at the pr-output delivery tail happens
 * automatically or behind an explicit operator approval:
 *
 * <ul>
 *   <li>{@code AUTO} — the run never parks; {@code captureAndPush} fires inline exactly as pre-3h
 *       delivery (byte-identical), then the run advances to WaitingForReview. The delivery gate is
 *       a pass-through.
 *   <li>{@code MANUAL} — the run parks at WaitingForDelivery; {@code approve_delivery} records the
 *       operator's out-of-band delivery ({@code delivery.recordedManually}) and advances WITHOUT
 *       touching git.
 *   <li>{@code APPROVE} — the run parks at WaitingForDelivery; {@code approve_delivery} performs
 *       the push (and PR per {@code autoCreatePullRequest}) via the resumable delivery seam, then
 *       advances to WaitingForReview.
 * </ul>
 *
 * <p>Structurally identical to {@link RunnerKind}/{@code status}/connector kinds — a CHECK-
 * constrained text column ({@code ck_projects_push_mode}), NOT free-text like {@code
 * reviewer_model_kind} (whose valid set defers to which reviewer models are wired at execution
 * time). {@code pushMode} has no such indirection.
 */
public enum PushMode implements RegistryValue {
  AUTO("auto"),
  MANUAL("manual"),
  APPROVE("approve");

  private static final Map<String, PushMode> LOOKUP = RegistryParsers.index(values());

  private final String value;

  PushMode(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static PushMode fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static PushMode fromValue(String rawValue, String field) {
    return RegistryParsers.parse("PushMode", rawValue, field, LOOKUP);
  }
}
