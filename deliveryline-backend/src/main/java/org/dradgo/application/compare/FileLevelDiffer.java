package org.dradgo.application.compare;

import java.util.List;

/**
 * Story 4.19 (AC5, Reconciliation 8) — computes a prOutput file-level delta from the two artifacts'
 * resolved unified-diff payloads. Pure interface (no Spring / persistence deps) so it unit-tests
 * independently (AC9).
 *
 * <p>Semantics (OQ-2): each side's unified diff is parsed into a per-file {@code (+lines, -lines)}
 * map; the delta is the union — a file in revision B's changeset only is {@code added}, in A's only
 * is {@code removed}, in both is {@code modified}. Line counts are taken from the revision that
 * carries the file (B for {@code added}/{@code modified}, A for {@code removed}). Full diff content
 * is intentionally NOT returned (lazy-loaded via {@code linkedDiffReferences}).
 */
public interface FileLevelDiffer {

  /**
   * @param priorDiff revision A's resolved unified diff text; {@code null}/blank = absent (yields
   *     no A-side files — Reconciliation 8).
   * @param currentDiff revision B's resolved unified diff text; {@code null}/blank = absent.
   * @return per-file change blocks, sorted by file path; empty when both diffs are absent or the
   *     two changesets touch the same files with identical counts. Never {@code null}.
   */
  List<FileChangeBlock> diff(String priorDiff, String currentDiff);
}
