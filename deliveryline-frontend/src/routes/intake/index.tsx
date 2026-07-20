import { createFileRoute } from '@tanstack/react-router';

import { IntakeBrowse } from '@/features/intake/IntakeBrowse';
import { dedupeTokens } from '@/features/intake/intakeView';
import { candidateTicketsQueryOptions } from '@/lib/api/queryOptions';
import type { IntakeFilters } from '@/lib/queryKeys/intakeKeys';

/**
 * IntakeRoute — the `/intake` filtered ticket-intake browse (story 3i-2).
 *
 * A NEW top-level `intake` route segment (mirrors the CLI's `tickets` command group), NOT nested
 * under `/projects/$projectId` — the project is a URL-owned filter like every other, selected via
 * the shared `ProjectSelector`.
 *
 * Filters are URL-owned: `validateSearch` explicitly parses AND re-emits every key, and every
 * filter change `navigate`s with the FULL next filter set. A key that `validateSearch` does not
 * return is STRIPPED by TanStack, which silently disables that filter —
 * [[tanstack-validatesearch-strips-unparsed-param]]. The loader warms the query so a deep link
 * renders flash-free. `routeTree.gen.ts` regenerates automatically (do NOT hand-edit).
 *
 * The submitting actor's identity is deliberately NOT a search param: it is operator PII, and a URL
 * carries it into browser history, referrer headers, and any proxy access log. It lives in component
 * state instead.
 */
interface IntakeSearch {
  projectId?: string;
  assignee?: string;
  components?: string;
  state?: string;
}

/** Parse a CSV search value into a trimmed, non-empty, de-duplicated token array. */
function parseCsv(value: unknown): string[] {
  if (typeof value !== 'string' || value.length === 0) {
    return [];
  }
  return dedupeTokens(value.split(','));
}

function parseScalar(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : undefined;
}

/** Map the URL search (CSV strings) into the `IntakeFilters` the query consumes. */
function searchToFilters(search: IntakeSearch): IntakeFilters {
  return {
    projectId: parseScalar(search.projectId),
    assignee: parseScalar(search.assignee),
    components: parseCsv(search.components),
    state: parseScalar(search.state),
  };
}

/** Serialize the filter model back into the search object, omitting every absent key. */
function filtersToSearch(filters: IntakeFilters): IntakeSearch {
  return {
    ...(filters.projectId !== undefined ? { projectId: filters.projectId } : {}),
    ...(filters.assignee !== undefined ? { assignee: filters.assignee } : {}),
    ...(filters.components.length > 0 ? { components: filters.components.join(',') } : {}),
    ...(filters.state !== undefined ? { state: filters.state } : {}),
  };
}

export const Route = createFileRoute('/intake/')({
  validateSearch: (search: Record<string, unknown>): IntakeSearch => {
    const out: IntakeSearch = {};
    const projectId = parseScalar(search.projectId);
    if (projectId !== undefined) {
      out.projectId = projectId;
    }
    const assignee = parseScalar(search.assignee);
    if (assignee !== undefined) {
      out.assignee = assignee;
    }
    const components = parseCsv(search.components);
    if (components.length > 0) {
      out.components = components.join(',');
    }
    const state = parseScalar(search.state);
    if (state !== undefined) {
      out.state = state;
    }
    return out;
  },
  loaderDeps: ({ search }): IntakeSearch => search,
  loader: ({ context, deps }) => {
    const filters = searchToFilters(deps);
    if (filters.projectId === undefined) {
      // The query is disabled without a project — nothing to warm.
      return;
    }
    return context.queryClient.ensureQueryData(candidateTicketsQueryOptions(filters));
  },
  component: IntakeRoute,
});

function IntakeRoute() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  const filters = searchToFilters(search);

  // Spread ALL active filters into the URL on every change (TanStack strips unparsed keys, and a
  // dropped filter silently disables it — [[tanstack-validatesearch-strips-unparsed-param]]).
  const handleFiltersChange = (next: IntakeFilters) => {
    void navigate({ search: filtersToSearch(next) });
  };

  return <IntakeBrowse filters={filters} onFiltersChange={handleFiltersChange} />;
}
