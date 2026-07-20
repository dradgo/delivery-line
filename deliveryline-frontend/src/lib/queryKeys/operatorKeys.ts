/**
 * Story 4.2 (AC3/AC5) — the operator-queue query-key factory + its multi-valued filter model.
 *
 * A DEDICATED sibling of `workflowKeys` — the operator queue's filters are multi-valued (arrays of
 * state/failure-category/runner-kind tokens + a single time-window enum), which the reviewer
 * queue's single-`state`-string `WorkflowListFilters` cannot carry (story 4.2 Reconciliation 7).
 * Every TanStack Query key comes from a factory call (the `no-inline-query-keys` rule); the arrays
 * are normalized (deduped + sorted) so two structurally-equal filter sets share ONE cache entry.
 */

import type { components } from '@/lib/api/schema';

/** The relative recent-activity window; `all` omits the `since` param. */
export type OperatorTimeWindow = '1h' | '24h' | '7d' | '30d' | 'all';

/** Failure-category / runner-kind wire tokens (from the generated OpenAPI enums). */
type FailureCategoryToken = NonNullable<components['schemas']['OperatorRunRow']['failureCategory']>;
type RunnerKindToken = NonNullable<components['schemas']['OperatorRunRow']['runnerKind']>;

/**
 * The authoritative filter-token vocabularies — ONE source of truth shared by the sidebar options and
 * the route's `validateSearch` (so an unknown URL token is dropped, not forwarded to a 400). The
 * failure-category / runner-kind records are completeness-checked against the generated enums: adding
 * a backend enum member is a COMPILE error until listed here (the options can never silently drift).
 */
export const OPERATOR_STATE_TOKENS = [
  'failed',
  'stalled',
  'orphaned',
  'takenover',
  'overridden',
] as const;

const FAILURE_CATEGORY_PRESENT: Record<FailureCategoryToken, true> = {
  runner_timeout: true,
  runner_crash: true,
  runner_contract_violation: true,
  runner_non_zero_exit: true,
  runner_late_result: true,
  runner_duplicate_result: true,
  runner_malformed_output: true,
  runner_secret_leak: true,
  runner_build_failed: true,
  orphan: true,
};
export const FAILURE_CATEGORY_TOKENS = Object.keys(
  FAILURE_CATEGORY_PRESENT,
) as FailureCategoryToken[];

const RUNNER_KIND_PRESENT: Record<RunnerKindToken, true> = {
  codex: true,
  claude: true,
  manual: true,
};
export const RUNNER_KIND_TOKENS = Object.keys(RUNNER_KIND_PRESENT) as RunnerKindToken[];

/** Retain only the tokens present in `known` (order-preserving) — drops unknown/stale URL tokens. */
export function retainKnownTokens(tokens: readonly string[], known: readonly string[]): string[] {
  const set = new Set<string>(known);
  return tokens.filter((token) => set.has(token));
}

/** The multi-valued, URL-owned operator-queue filter model (story 4.2 Reconciliation 7). */
export interface OperatorQueueFilters {
  /** Operator-state tokens (failed/stalled/orphaned/takenover/overridden), multi-select. */
  states: string[];
  /** Failure-category wire tokens (from the registry), multi-select. */
  failureCategories: string[];
  /** Runner-kind tokens (codex/claude/manual), multi-select. */
  runnerKinds: string[];
  /** Single time-window selector; `all` disables the `since` filter. */
  timeWindow: OperatorTimeWindow;
}

/** The default (no active filter) shape — an empty multi-select set with the `all` window. */
export const EMPTY_OPERATOR_FILTERS: OperatorQueueFilters = {
  states: [],
  failureCategories: [],
  runnerKinds: [],
  timeWindow: 'all',
};

/** Sort + de-duplicate a token array so equal filter sets produce an equal, stable cache key. */
function normalizeTokens(tokens: readonly string[]): string[] {
  return Array.from(new Set(tokens.map((t) => t.trim()).filter((t) => t !== ''))).sort();
}

/**
 * Normalize the filter model into a stable, serializable shape (mirrors
 * `workflowKeys.normalizeFilters`): arrays deduped + sorted, the window passed through. Two
 * structurally-equal filter objects therefore produce an equal key (shared cache entry).
 */
export function normalizeOperatorFilters(
  filters: OperatorQueueFilters = EMPTY_OPERATOR_FILTERS,
): OperatorQueueFilters {
  return {
    states: normalizeTokens(filters.states),
    failureCategories: normalizeTokens(filters.failureCategories),
    runnerKinds: normalizeTokens(filters.runnerKinds),
    timeWindow: filters.timeWindow,
  };
}

/** True when any filter carries a meaningful value (drives the filtered-empty state). */
export function operatorFiltersActive(filters: OperatorQueueFilters): boolean {
  const normalized = normalizeOperatorFilters(filters);
  return (
    normalized.states.length > 0 ||
    normalized.failureCategories.length > 0 ||
    normalized.runnerKinds.length > 0 ||
    normalized.timeWindow !== 'all'
  );
}

export const operatorKeys = {
  /** Root of every operator-queue key. */
  all: ['operator'] as const,
  /** All operator-runs list queries (any filter). */
  lists: () => [...operatorKeys.all, 'runs'] as const,
  /** A specific filtered operator-runs list (the infinite query's key). */
  list: (filters?: OperatorQueueFilters) =>
    [...operatorKeys.lists(), normalizeOperatorFilters(filters)] as const,
} as const;
