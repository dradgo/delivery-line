/**
 * Story 4.2 (AC1–AC8) — the operator workflow-owner queue.
 *
 * COMPOSES the story-2.20 queue-state machinery (`resolveQueueState` + `EmptyState`/`ErrorState`/
 * `Skeleton`) rather than the reviewer `QueueShell` (which is bound to `useWorkflowsList`/
 * `WorkflowSummary`). Data comes from the operator `useInfiniteQuery` (`useOperatorRunsList`); the
 * populated list is virtualized (`@tanstack/react-virtual`) and loads more as the operator scrolls
 * near the end (AC5). Filters are URL-owned (the route passes them down + an `onFiltersChange` that
 * navigates — Reconciliation 7). Access is gated by the `useCanViewOperatorQueue` seam
 * (Reconciliation 6/OQ-GATE): E4 returns `true`; the not-allowed branch is a forward stub.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState, ErrorState } from '@/components/feedback';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { operatorFilteredToRuns } from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import { operatorFiltersActive, type OperatorQueueFilters } from '@/lib/queryKeys/operatorKeys';

import { OperatorFilterSidebar } from './components/OperatorFilterSidebar';
import { RunReviewQueueItem } from './components/RunReviewQueueItem';
import { toOperatorQueueRow } from './operatorQueueRow';
import { queueErrorMessage } from './queueErrorMessage';
import { QUEUE_ROW_MIN_HEIGHT, QUEUE_SKELETON_ROW_COUNT, resolveQueueState } from './queueState';
import { useCanViewOperatorQueue } from './hooks/useCanViewOperatorQueue';
import { useOperatorRunsList } from './hooks/useOperatorRunsList';

/** Fixed row size (px) for the fixed-size virtualizer — matches QUEUE_ROW_MIN_HEIGHT (4.5rem) + gap. */
const OPERATOR_ROW_SIZE_PX = 80;

export interface OperatorQueueProps {
  filters: OperatorQueueFilters;
  onFiltersChange: (next: OperatorQueueFilters) => void;
}

/** Compose a short, secret-free summary of the active filters for the ARIA announcement (AC8). */
function filterSummary(filters: OperatorQueueFilters): string {
  const parts: string[] = [];
  if (filters.states.length > 0) {
    parts.push(`state ${filters.states.join(', ')}`);
  }
  if (filters.failureCategories.length > 0) {
    parts.push(`failure ${filters.failureCategories.join(', ')}`);
  }
  if (filters.runnerKinds.length > 0) {
    parts.push(`runner ${filters.runnerKinds.join(', ')}`);
  }
  if (filters.timeWindow !== 'all') {
    parts.push(`window ${filters.timeWindow}`);
  }
  return parts.join('; ');
}

export function OperatorQueue({ filters, onFiltersChange }: OperatorQueueProps) {
  const canView = useCanViewOperatorQueue();
  const { query, rows, aggregate } = useOperatorRunsList(filters);
  const filtersActive = operatorFiltersActive(filters);
  // The cursor-independent match count (page-1 aggregate, stable across pages) — NOT rows.length,
  // which is only the rows loaded so far and grows on every fetched page.
  const matchCount = aggregate?.total ?? rows.length;
  const state = resolveQueueState({
    isError: query.isError,
    isPending: query.isPending,
    count: rows.length,
    filtersActive,
  });

  // Bulk-actions placeholder (AC6) — local-only selection state that wires to NOTHING.
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());

  const parentRef = useRef<HTMLDivElement>(null);
  const rowVirtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => OPERATOR_ROW_SIZE_PX,
    overscan: 8,
  });
  const virtualItems = rowVirtualizer.getVirtualItems();

  // AC5 — fetch the next page when the last virtual row scrolls into view. Depend on the specific
  // (stable) query fields rather than the whole `query` result object, whose reference changes every
  // render (which would run this effect on every render, not only when the tail scrolls into view).
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = query;
  useEffect(() => {
    const last = virtualItems[virtualItems.length - 1];
    if (last !== undefined && last.index >= rows.length - 1 && hasNextPage && !isFetchingNextPage) {
      void fetchNextPage();
    }
  }, [virtualItems, rows.length, hasNextPage, isFetchingNextPage, fetchNextPage]);

  // AC8 — announce the resolved (full-match) run count + active-filter summary after the query
  // settles. Uses the stable aggregate total, so it does NOT re-announce as each page loads.
  const announcement = useLiveAnnouncement(
    state === 'populated' || state === 'filtered-empty'
      ? operatorFilteredToRuns(matchCount, filterSummary(filters))
      : '',
  );

  // AC8/observability — field-only structured log of the error state (never row content).
  useEffect(() => {
    if (state !== 'error') {
      return;
    }
    console.warn({
      event: 'operatorQueue.loadError',
      state,
      code: isProblemDetailsError(query.error) ? query.error.code : 'transport',
      filtersActive,
    });
  }, [state, query.error, filtersActive]);

  if (!canView) {
    // Reconciliation 6 — forward-looking, currently-unreachable stub for E5 RBAC.
    return (
      <ErrorState
        variant="permissionRestricted"
        urgency="passive"
        message="You do not have access to the operator queue."
        nextAction={{ kind: 'NavigateBack' }}
      />
    );
  }

  const toggleRowSelection = (runId: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(runId)) {
        next.delete(runId);
      } else {
        next.add(runId);
      }
      return next;
    });
  };

  return (
    <div className="flex flex-col gap-4" data-queue-state={state} data-testid="operator-queue">
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-page-title">Operator queue</h1>
        {/* AC6 — bulk-actions placeholder: a "Select multiple" toggle + a permanently-disabled
            "Bulk action" control. Placeholder ONLY — wires to nothing in E4. */}
        <div className="flex items-center gap-3" data-testid="operator-bulk-actions">
          <div className="flex items-center gap-2">
            <Checkbox
              id="operator-select-multiple"
              checked={selectMode}
              onCheckedChange={(next) => setSelectMode(next === true)}
            />
            <label htmlFor="operator-select-multiple" className="text-sm text-text-primary">
              Select multiple
            </label>
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled
            data-testid="operator-bulk-action-dropdown"
          >
            Bulk operator actions arrive in a future release
          </Button>
        </div>
      </div>

      {/* AC8 — single visually-hidden polite announcer. */}
      <div
        role="status"
        aria-live="polite"
        className="sr-only"
        data-testid="operator-queue-announcer"
      >
        {announcement}
      </div>

      <div className="flex flex-col gap-6 md:flex-row">
        <OperatorFilterSidebar filters={filters} onChange={onFiltersChange} />

        <div className="min-w-0 flex-1">
          {state === 'loading' ? (
            <div
              className="flex flex-col gap-2"
              aria-busy="true"
              aria-hidden
              data-testid="operator-queue-loading"
            >
              {Array.from({ length: QUEUE_SKELETON_ROW_COUNT }, (_, index) => (
                <Skeleton
                  key={index}
                  className="w-full"
                  style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
                />
              ))}
            </div>
          ) : null}

          {state === 'error' ? (
            <ErrorState
              variant="failedRetrieval"
              urgency="passive"
              message={`${queueErrorMessage(query.error)} If this keeps happening, report it to your administrator.`}
              nextAction={{ kind: 'Retry', onRetry: () => void query.refetch() }}
            />
          ) : null}

          {state === 'empty' ? (
            <EmptyState
              variant="queue"
              message="No runs need operator attention right now. Failed, stalled, orphaned, taken-over, and overridden runs appear here."
            />
          ) : null}

          {state === 'filtered-empty' ? (
            <EmptyState
              variant="filtered"
              action={
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    onFiltersChange({
                      states: [],
                      failureCategories: [],
                      runnerKinds: [],
                      timeWindow: 'all',
                    })
                  }
                >
                  Clear filters
                </Button>
              }
            />
          ) : null}

          {state === 'populated' ? (
            <>
              <div
                ref={parentRef}
                className="max-h-[70vh] overflow-auto"
                data-testid="operator-queue-scroll"
              >
                {/* eslint-disable-next-line jsx-a11y/no-redundant-roles */}
                <ul
                  role="list"
                  className="relative w-full"
                  style={{ height: rowVirtualizer.getTotalSize() }}
                  data-testid="operator-queue-list"
                >
                  {virtualItems.map((virtualRow): ReactNode => {
                    const row = rows[virtualRow.index];
                    if (row === undefined) {
                      return null;
                    }
                    const runId = row.runId;
                    return (
                      <li
                        key={runId ?? virtualRow.index}
                        // Dynamic measurement: operator rows wrap (long ticket refs + several chips)
                        // and can exceed the fixed estimate on narrow viewports. Without measuring,
                        // the fixed-height absolute rows overlap and getTotalSize() is wrong. Let the
                        // virtualizer read each row's true height via measureElement (data-index).
                        ref={rowVirtualizer.measureElement}
                        data-index={virtualRow.index}
                        className="absolute left-0 top-0 flex w-full items-center gap-2"
                        style={{
                          transform: `translateY(${virtualRow.start}px)`,
                        }}
                      >
                        {selectMode ? (
                          <span className="relative z-[2] shrink-0">
                            <Checkbox
                              aria-label={`Select run ${runId ?? ''}`}
                              checked={runId !== undefined && selected.has(runId)}
                              onCheckedChange={() =>
                                runId !== undefined ? toggleRowSelection(runId) : undefined
                              }
                            />
                          </span>
                        ) : null}
                        <span className="min-w-0 flex-1">
                          <RunReviewQueueItem run={toOperatorQueueRow(row)} variant="operator" />
                        </span>
                      </li>
                    );
                  })}
                </ul>
              </div>
              {/* AC7 — a keyboard-triggerable load-more affordance (virtualized rows off-screen are
                  not tabbable; this keeps the next page reachable without a scroll gesture). */}
              {query.hasNextPage ? (
                <div className="mt-3 flex justify-center">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={query.isFetchingNextPage}
                    onClick={() => void query.fetchNextPage()}
                    data-testid="operator-queue-load-more"
                  >
                    {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
                  </Button>
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}
