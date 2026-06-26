import { createFileRoute } from '@tanstack/react-router';

import { listQueryOptions } from '@/lib/api/queryOptions';
import type { WorkflowListFilters } from '@/lib/queryKeys/workflowKeys';
import { QueueShell } from '@/features/workflows/QueueShell';
import { RunReviewQueueItem } from '@/features/workflows/components/RunReviewQueueItem';
import { toRunQueueRow } from '@/features/workflows/runQueueRow';

/**
 * WorkflowsRoute: the `/workflows` run review queue.
 *
 * Story 2.20 replaced the story-2.5 placeholder body with the real
 * `<QueueShell>`, which owns the four non-row queue states (loading / empty /
 * filtered-empty / error) and the ready list. The row UI itself arrives in story
 * 2.15 via the shell's `renderItem` seam.
 *
 * Filters live in the URL (AC6): `validateSearch` types + sanitizes `?state=...`;
 * the component reads it via `Route.useSearch()` and Clear-filters navigates to the
 * empty-search URL. The loader warms the SAME `listQueryOptions(search)` the shell
 * reads, so a deep link with filters renders flash-free off one shared cache entry
 * (story 2.6 data seam). The X-Correlation-Id header rides each request via the
 * client middleware (2.6/1.19). Building the filter CONTROLS is out of scope (AC9).
 */
export const Route = createFileRoute('/workflows/')({
  // Story 3d-8 — the URL carries both the `state` filter and the `includeArchived` flag, each
  // sanitized independently so a deep link with either (or both) renders off the shared cache.
  // Story 3f-6 — the URL also carries the `projectId` filter (slug or prj_ public id); without
  // parsing it here TanStack would strip it on every navigation, leaving the filter inert.
  validateSearch: (search: Record<string, unknown>): WorkflowListFilters => {
    const filters: WorkflowListFilters = {};
    if (typeof search.state === 'string' && search.state.length > 0) {
      filters.state = search.state;
    }
    if (search.includeArchived === true || search.includeArchived === 'true') {
      filters.includeArchived = true;
    }
    if (typeof search.projectId === 'string' && search.projectId.length > 0) {
      filters.projectId = search.projectId;
    }
    return filters;
  },
  loaderDeps: ({ search }): WorkflowListFilters => search,
  loader: ({ context, deps }) => context.queryClient.ensureQueryData(listQueryOptions(deps)),
  component: WorkflowsRoute,
});

function WorkflowsRoute() {
  const search = Route.useSearch();
  const navigate = Route.useNavigate();
  // Each toggle preserves the OTHER active filter (build conditionally for exactOptionalPropertyTypes).
  return (
    <QueueShell
      filters={search}
      onClearFilters={() => void navigate({ search: {} })}
      onToggleTakenOverFilter={(next) =>
        void navigate({
          search: {
            ...(next !== undefined ? { state: next } : {}),
            ...(search.includeArchived === true ? { includeArchived: true } : {}),
            ...(search.projectId !== undefined ? { projectId: search.projectId } : {}),
          },
        })
      }
      onToggleIncludeArchived={(next) =>
        void navigate({
          search: {
            ...(search.state !== undefined ? { state: search.state } : {}),
            ...(next === true ? { includeArchived: true } : {}),
            ...(search.projectId !== undefined ? { projectId: search.projectId } : {}),
          },
        })
      }
      onProjectFilterChange={(next) =>
        void navigate({
          search: {
            ...(search.state !== undefined ? { state: search.state } : {}),
            ...(search.includeArchived === true ? { includeArchived: true } : {}),
            ...(next !== undefined ? { projectId: next } : {}),
          },
        })
      }
      renderItem={(summary) => <RunReviewQueueItem run={toRunQueueRow(summary)} />}
    />
  );
}
