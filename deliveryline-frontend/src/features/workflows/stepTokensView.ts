/**
 * Story 3g-4 (FR74) — pure view-model for the per-step token panel + run-total.
 *
 * A sibling `.ts` (NOT `.tsx`) so the pure mapper/formatter exports do not trip the
 * `react-refresh/only-export-components` rule (max-warnings=0) — the same convention as
 * `runContextView.ts` / `prLinkageView.ts`.
 *
 * The token posture is locked (mirror 3d-7 `ProviderLimitStatus`): a `null`/absent count is "not
 * reported" — NEVER `0`, never blank, never color-only. `0` means "the agent reported zero". So the
 * mapper coalesces each nullable wire count to `undefined` (present-value-or-undefined), and
 * `formatTokenCount` renders `undefined` as the literal textual "Not reported".
 */
import type { StepExecution } from './hooks/useStepExecutions';

/** One runner execution's token row, ready to render. Absent counts are `undefined` (not `0`). */
export interface StepTokenRow {
  readonly runnerExecutionId: string;
  /** Raw `stage` wire value (e.g. `execution` / `review`); humanized verbatim on render. */
  readonly stage: string | undefined;
  /** Raw `status` wire value (e.g. `completed` / `running`); humanized verbatim on render. */
  readonly status: string | undefined;
  readonly createdAt: string | undefined;
  readonly inputTokens: number | undefined;
  readonly outputTokens: number | undefined;
  readonly totalTokens: number | undefined;
}

/**
 * Coalesce a nullable wire count to a present number or `undefined`. Nullable wire fields arrive as
 * JSON `null` (not absent) — the generated type says `number | null` — so guard `!= null` (covers
 * both null and undefined) and PRESERVE a real `0` (a reported zero is data, distinct from absent).
 */
function presentOrUndefined(value: number | null | undefined): number | undefined {
  return value != null ? value : undefined;
}

/** Coalesce a nullable wire string to a present, non-blank value or `undefined`. */
function presentStringOrUndefined(value: string | null | undefined): string | undefined {
  return value != null && value.trim() !== '' ? value : undefined;
}

/** Map the wire `StepExecution[]` (already oldest-first from the backend) to render-ready rows. */
export function toStepTokenRows(steps: StepExecution[] | undefined): StepTokenRow[] {
  if (steps === undefined) {
    return [];
  }
  return steps.map((step) => ({
    runnerExecutionId: step.runnerExecutionId ?? '',
    stage: presentStringOrUndefined(step.stage),
    status: presentStringOrUndefined(step.status),
    createdAt: presentStringOrUndefined(step.createdAt),
    inputTokens: presentOrUndefined(step.inputTokens),
    outputTokens: presentOrUndefined(step.outputTokens),
    totalTokens: presentOrUndefined(step.totalTokens),
  }));
}

/**
 * Render a token count: the number for a present value (INCLUDING a reported `0`), or the literal
 * "Not reported" for `undefined` — the exact 3d-7 not-exposed posture. Never renders a bare `0` for
 * an absent count, and never a blank.
 */
export function formatTokenCount(n: number | undefined): string {
  return n === undefined ? 'Not reported' : String(n);
}
