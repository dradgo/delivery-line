/**
 * Story 3g-4 (FR74, AC1/AC3/AC5) — the per-step token usage panel + run-level total.
 *
 * Renders each runner execution (step) of the run with its input / output / total token counts
 * (a one-shot React-Query read via `useStepExecutions`), plus the backend-computed run-level total
 * in the header. Absent counts show the textual "Not reported" (mirror 3d-7 `ProviderLimitStatus`
 * — never `0`, never blank, never color-only, UX-DR2). Tokens-only — no estimated $ cost (AC3,
 * locked).
 *
 * Self-hides (`return null`) when the run has no runner executions yet (parity with
 * `RunDependencyPanel`). The run total is the backend's `WorkflowDetail.totalTokens` (single source
 * of the rollup, AC2) — the panel NEVER re-sums the per-step rows on the FE.
 */
import { formatTokenCount, toStepTokenRows } from '../stepTokensView';
import { useStepExecutions } from '../hooks/useStepExecutions';

export interface RunStepTokensPanelProps {
  workflowRunId: string;
  /**
   * The backend-computed run-level total (`WorkflowDetail.totalTokens`). Nullable on the wire — a
   * JSON `null` means "no step reported tokens" → rendered "Not reported" (never `0`). Guard `!=
   * null` ([[workflowdetail-wire-sends-null-not-undefined]]).
   */
  totalTokens: number | null | undefined;
}

export function RunStepTokensPanel({ workflowRunId, totalTokens }: RunStepTokensPanelProps) {
  const { data, isError, isPending } = useStepExecutions(workflowRunId);
  const rows = toStepTokenRows(data);
  // Coalesce the nullable wire total to a present value or undefined at the view-model seam.
  const runTotal = totalTokens != null ? totalTokens : undefined;

  if (isError) {
    // The per-step list failed to load, but the run-level total comes from the independent detail
    // query (the `totalTokens` prop) — surface it when present so the run's overall consumption
    // (AC3) is not lost with the step list.
    return (
      <section aria-label="Step token usage" data-testid="run-step-tokens" className="w-full">
        {runTotal !== undefined ? (
          <p data-testid="run-step-tokens-total" className="mb-2 text-meta text-text-tertiary">
            Run total:{' '}
            <span className="font-mono text-text-primary">{formatTokenCount(runTotal)}</span>
          </p>
        ) : null}
        <p
          data-testid="run-step-tokens-error"
          className="rounded-md border border-state-error-border bg-state-error px-3 py-2 text-sm text-state-error-foreground"
        >
          The step token usage could not be loaded.
        </p>
      </section>
    );
  }

  // Self-hide while pending and when the run has no runner executions yet (parity with
  // RunDependencyPanel) — the run total is derived from the same rows, so nothing to show.
  if (isPending || rows.length === 0) {
    return null;
  }

  return (
    <section aria-label="Step token usage" data-testid="run-step-tokens" className="w-full">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 className="text-meta uppercase tracking-wide text-text-tertiary">Step token usage</h2>
        <span data-testid="run-step-tokens-total" className="text-meta text-text-tertiary">
          Run total:{' '}
          <span className="font-mono text-text-primary">{formatTokenCount(runTotal)}</span>
        </span>
      </div>

      <ul className="flex flex-col gap-1">
        {rows.map((row, index) => (
          <li
            key={row.runnerExecutionId || `idx-${index}`}
            data-testid="run-step-tokens-row"
            className="flex flex-col gap-1 rounded-md border border-border bg-surface-sunken p-3"
          >
            <div className="flex items-center gap-2">
              <span className="text-sm text-text-primary">{row.stage ?? 'Unknown stage'}</span>
              {row.status !== undefined ? (
                <span
                  data-testid={`run-step-tokens-status-${row.runnerExecutionId}`}
                  className="rounded-full border border-border bg-surface px-2 py-0.5 text-meta text-text-tertiary"
                >
                  {row.status}
                </span>
              ) : null}
            </div>
            <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
              <span className="flex items-center gap-1">
                <span className="text-text-tertiary">Input</span>
                <span className="font-mono text-text-primary">
                  {formatTokenCount(row.inputTokens)}
                </span>
              </span>
              <span className="flex items-center gap-1">
                <span className="text-text-tertiary">Output</span>
                <span className="font-mono text-text-primary">
                  {formatTokenCount(row.outputTokens)}
                </span>
              </span>
              <span className="flex items-center gap-1">
                <span className="text-text-tertiary">Total</span>
                <span className="font-mono text-text-primary">
                  {formatTokenCount(row.totalTokens)}
                </span>
              </span>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
