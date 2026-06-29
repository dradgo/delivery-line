/**
 * Story 3f-7 (AC6) — the Split Lineage panel.
 *
 * Renders the decomposition progress for a run parked in the non-terminal `Split` state, read
 * directly from the embedded `WorkflowDetail.decompositionStatus` read model (no extra fetch). The
 * backend computes "decomposed — N of M descendants complete" over the parent's direct children
 * (which, because a split child only reaches `Completed` via its own rollup, equals whole-subtree
 * doneness).
 *
 * Renders nothing when `decompositionStatus` is absent — i.e. for every non-`Split` run, including a
 * parent that has rolled up to `Completed` (at which point the run's state badge in the context
 * strip flips to `Completed`). This keeps the detail view calm for ordinary runs and lets the panel
 * naturally disappear on rollup.
 */
export interface SplitLineagePanelProps {
  decompositionStatus: string | null | undefined;
}

export function SplitLineagePanel({ decompositionStatus }: SplitLineagePanelProps) {
  // Calm by default: only a Split parent carries a decomposition status.
  if (decompositionStatus == null || decompositionStatus === '') {
    return null;
  }

  return (
    <section
      aria-labelledby="split-lineage-heading"
      data-testid="split-lineage-panel"
      className="flex flex-col gap-3 rounded-md border border-border p-4"
    >
      <h2 id="split-lineage-heading" className="text-section-title">
        Decomposition
      </h2>
      <p data-testid="split-lineage-status" className="text-body text-text-secondary">
        {decompositionStatus}
      </p>
    </section>
  );
}
