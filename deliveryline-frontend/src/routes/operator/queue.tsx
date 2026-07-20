import { createFileRoute } from '@tanstack/react-router';

import { operatorRunsInfiniteQueryOptions } from '@/lib/api/queryOptions';
import {
  FAILURE_CATEGORY_TOKENS,
  OPERATOR_STATE_TOKENS,
  RUNNER_KIND_TOKENS,
  retainKnownTokens,
  type OperatorQueueFilters,
  type OperatorTimeWindow,
} from '@/lib/queryKeys/operatorKeys';
import { OperatorQueue } from '@/features/workflows/OperatorQueue';

/**
 * OperatorQueueRoute — the `/operator/queue` operator workflow-owner queue (story 4.2).
 *
 * A NEW top-level `operator` route segment (mirrors the CLI's `operator` command group), NOT nested
 * under `/workflows/`. Filters are URL-owned (Reconciliation 7): `validateSearch` parses the
 * multi-valued filter model from CSV search keys, the component reads it via `Route.useSearch()`,
 * and every filter change `navigate`s with the FULL next filter set (each nav spreads all active
 * filters, so none silently drop — [[tanstack-validatesearch-strips-unparsed-param]]). The loader
 * warms the infinite query's first page so a deep link renders flash-free. `routeTree.gen.ts`
 * regenerates automatically (do NOT hand-edit).
 */
interface OperatorQueueSearch {
  state?: string;
  failureCategory?: string;
  runnerKind?: string;
  timeWindow?: OperatorTimeWindow;
}

const TIME_WINDOWS: readonly OperatorTimeWindow[] = ['1h', '24h', '7d', '30d', 'all'];

function parseTimeWindow(value: unknown): OperatorTimeWindow | undefined {
  return typeof value === 'string' && (TIME_WINDOWS as readonly string[]).includes(value)
    ? (value as OperatorTimeWindow)
    : undefined;
}

/**
 * Parse a CSV search value into a trimmed, non-empty token array, retaining ONLY tokens in the known
 * vocabulary. An unknown token (stale bookmark, hand-edited URL, retired enum value) is dropped rather
 * than forwarded — otherwise the backend rejects it with `INVALID_COMMAND_PAYLOAD` and the whole queue
 * flips to an unrecoverable error whose Retry re-sends the same bad token. This mirrors how the
 * `timeWindow` param already degrades gracefully via `parseTimeWindow`.
 */
function parseKnownCsv(value: unknown, known: readonly string[]): string[] {
  if (typeof value !== 'string' || value.length === 0) {
    return [];
  }
  const tokens = value
    .split(',')
    .map((token) => token.trim())
    .filter((token) => token !== '');
  return retainKnownTokens(tokens, known);
}

/** Map the URL search (CSV strings) into the multi-valued `OperatorQueueFilters` the hook consumes. */
function searchToFilters(search: OperatorQueueSearch): OperatorQueueFilters {
  return {
    states: parseKnownCsv(search.state, OPERATOR_STATE_TOKENS),
    failureCategories: parseKnownCsv(search.failureCategory, FAILURE_CATEGORY_TOKENS),
    runnerKinds: parseKnownCsv(search.runnerKind, RUNNER_KIND_TOKENS),
    timeWindow: search.timeWindow ?? 'all',
  };
}

export const Route = createFileRoute('/operator/queue')({
  validateSearch: (search: Record<string, unknown>): OperatorQueueSearch => {
    const out: OperatorQueueSearch = {};
    if (typeof search.state === 'string' && search.state.length > 0) {
      out.state = search.state;
    }
    if (typeof search.failureCategory === 'string' && search.failureCategory.length > 0) {
      out.failureCategory = search.failureCategory;
    }
    if (typeof search.runnerKind === 'string' && search.runnerKind.length > 0) {
      out.runnerKind = search.runnerKind;
    }
    const timeWindow = parseTimeWindow(search.timeWindow);
    // 'all' is the default — omit it from the URL so the query stays clean.
    if (timeWindow !== undefined && timeWindow !== 'all') {
      out.timeWindow = timeWindow;
    }
    return out;
  },
  loaderDeps: ({ search }): OperatorQueueSearch => search,
  loader: ({ context, deps }) =>
    context.queryClient.ensureInfiniteQueryData(
      operatorRunsInfiniteQueryOptions(searchToFilters(deps)),
    ),
  component: OperatorQueueRoute,
});

function OperatorQueueRoute() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  const filters = searchToFilters(search);

  const handleFiltersChange = (next: OperatorQueueFilters) => {
    // Spread ALL active filters into the URL on every change (TanStack strips unparsed keys, and a
    // dropped filter silently disables it — [[tanstack-validatesearch-strips-unparsed-param]]).
    void navigate({
      search: {
        ...(next.states.length > 0 ? { state: next.states.join(',') } : {}),
        ...(next.failureCategories.length > 0
          ? { failureCategory: next.failureCategories.join(',') }
          : {}),
        ...(next.runnerKinds.length > 0 ? { runnerKind: next.runnerKinds.join(',') } : {}),
        ...(next.timeWindow !== 'all' ? { timeWindow: next.timeWindow } : {}),
      },
    });
  };

  return <OperatorQueue filters={filters} onFiltersChange={handleFiltersChange} />;
}
