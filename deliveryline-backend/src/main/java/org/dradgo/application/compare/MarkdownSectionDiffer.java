package org.dradgo.application.compare;

import java.util.List;

/**
 * Story 4.19 (AC3) — computes section-by-section markdown deltas. A pure interface (no Spring /
 * persistence deps) so it unit-tests independently (AC9). The input strings are the two artifacts'
 * redacted spec bodies; the output blocks are further redacted defense-in-depth by {@link
 * RevisionDeltaService} before serialization (AC6).
 */
public interface MarkdownSectionDiffer {

  /**
   * Splits both bodies into {@code (sectionPath, body)} regions by ATX heading, aligns them by
   * {@code sectionPath}, and emits an {@code added}/{@code removed}/{@code modified} block per
   * region that changed. Whitespace-only body differences are NOT reported as {@code modified}.
   *
   * @param priorMarkdown revision A body; {@code null}/blank treated as no sections.
   * @param currentMarkdown revision B body; {@code null}/blank treated as no sections.
   * @return the ordered change blocks; empty when the two bodies have no semantic section
   *     difference. Never {@code null}.
   */
  List<MarkdownChangeBlock> diff(String priorMarkdown, String currentMarkdown);

  /**
   * True when the two bodies differ only in non-semantic whitespace — i.e. they are identical after
   * per-line trimming and blank-run collapse, order preserved. A section <em>reorder</em> changes
   * the line order and therefore returns {@code false} (it is a meaningful change even though
   * {@link #diff} — which aligns by heading — emits no block for it). Callers use this to decide
   * {@code noMeaningfulDiff} without treating a reorder as "no change".
   */
  boolean isWhitespaceOnlyDifference(String priorMarkdown, String currentMarkdown);
}
