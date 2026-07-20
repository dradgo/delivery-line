package org.dradgo.application.compare;

/**
 * Story 4.19 (AC2) — a single typed change region inside a {@link RevisionDelta}. Sealed over the
 * three per-artifact-type variants so the switch that consumes a delta is exhaustive:
 *
 * <ul>
 *   <li>{@link MarkdownChangeBlock} — spec (markdown section diff, AC3).
 *   <li>{@link PlanStepChangeBlock} — implementationPlan (structured-step diff, AC4).
 *   <li>{@link FileChangeBlock} — prOutput (file-level diff summary, AC5).
 * </ul>
 *
 * <p>Every block carries a {@link #changeKind()} from the {@link ChangeKind} constant set.
 */
public sealed interface ChangeBlock
    permits MarkdownChangeBlock, PlanStepChangeBlock, FileChangeBlock {

  /** One of the {@link ChangeKind} constants. Never {@code null}. */
  String changeKind();
}
