package org.dradgo.application.compare;

/**
 * Story 4.19 (AC3–AC5) — the small, stable {@code changeKind} constant set carried on every {@link
 * ChangeBlock}. Kept as string constants (not an enum) so the wire value is decoupled from any Java
 * name and the variant blocks stay plain view records with no cross-package enum dependency.
 *
 * <ul>
 *   <li>{@link #ADDED} — the region/step/file is present only in revision B (the target).
 *   <li>{@link #REMOVED} — present only in revision A (the baseline).
 *   <li>{@link #MODIFIED} — present in both, with a differing (whitespace-normalized) body.
 *   <li>{@link #REORDERED} — implementation-plan only: the same step text moved to a different
 *       index (AC4). Never emitted by the markdown or file differ.
 * </ul>
 */
public final class ChangeKind {

  public static final String ADDED = "added";
  public static final String REMOVED = "removed";
  public static final String MODIFIED = "modified";
  public static final String REORDERED = "reordered";

  private ChangeKind() {}
}
