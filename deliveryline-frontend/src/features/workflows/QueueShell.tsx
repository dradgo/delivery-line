/**
 * Story 2.20 (AC1–AC10) — the `/workflows` review-queue shell.
 *
 * Owns the FOUR non-row queue states (loading / empty / filtered-empty / error)
 * plus the populated list. State selection is delegated to the pure
 * {@link resolveQueueState} (AC1) so EXACTLY ONE branch renders — there are no
 * overlapping `&&` chains in the JSX below, and the root carries a stable
 * `data-queue-state` for the side-by-side distinctness test (AC7).
 *
 * COMPOSES story-2.22 primitives (`<EmptyState>` / `<ErrorState>`) — it never
 * rebuilds them. Loading uses the NEW `<Skeleton>` (AC2: skeleton rows, never a
 * spinner-on-blank); story 2.22's `<LoadingState>` is deliberately unused here.
 *
 * Row rendering is DECOUPLED (AC9): the shell never imports the not-yet-existent
 * `RunReviewQueueItem` (story 2.15) — it renders rows through the `renderItem`
 * render-prop seam, defaulting to a minimal `QueuePlaceholderRow`. `[SEAM story 2.15]`.
 *
 * Props-injectable (OQ-1) so unit tests drive every state without a router:
 * `filters` is passed in (the route reads `Route.useSearch()` and hands it down —
 * AC9), and `onClearFilters` performs the empty-search navigation (AC4).
 */
import { useEffect, type ReactNode } from 'react';
import { Link } from '@tanstack/react-router';

import { Button } from '@/components/ui/button';
import { ProjectSelector } from '@/features/projects/components/ProjectSelector';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState, ErrorState } from '@/components/feedback';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import type { WorkflowSummary } from '@/lib/api/queryOptions';
import type { WorkflowListFilters } from '@/lib/queryKeys/workflowKeys';
import { isValidRunId } from '@/lib/routing/publicId';
import { validateUrlScheme } from '@/lib/sanitization';

import {
  queueEmpty,
  queueFilteredEmpty,
  queueLoaded,
  queueLoading,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';

import { useWorkflowsList } from './hooks/useWorkflowsList';
import { queueErrorMessage } from './queueErrorMessage';
import {
  QUEUE_ROW_MIN_HEIGHT,
  QUEUE_SKELETON_ROW_COUNT,
  resolveQueueState,
  type QueueState,
} from './queueState';

export interface QueueShellProps {
  /** Active filters, read from the route search params by the caller (AC9). */
  filters?: WorkflowListFilters;
  /** Row renderer — story 2.15's `RunReviewQueueItem` fills this seam (AC9). */
  renderItem?: (item: WorkflowSummary) => ReactNode;
  /** Clears all filters (the route navigates to the empty-search URL) (AC4). */
  onClearFilters?: () => void;
  /**
   * Story 3.29 (AC4/R5) — toggle the single-state `?state` filter between
   * `TakenOver` (view ONLY taken-over runs) and cleared. The route performs the
   * navigation; `QueueShell` holds NO filter state (the URL is the single source of
   * truth — active state is read back from `filters.state`).
   */
  onToggleTakenOverFilter?: (next: 'TakenOver' | undefined) => void;
  /**
   * Story 3d-8 (AC5) — toggle the `?includeArchived` filter between including soft-hidden
   * (archived) runs and the default (hidden). The route performs the navigation; `QueueShell`
   * holds NO filter state (the URL is the single source of truth — active state is read back
   * from `filters.includeArchived`). `undefined` (not `false`) when turning off, so a cleared
   * toggle does not count as an active filter (mirrors the taken-over pattern).
   */
  onToggleIncludeArchived?: (next: true | undefined) => void;
  /** Story 3f-6 - URL-owned project filter change. */
  onProjectFilterChange?: (next: string | undefined) => void;
}

/** True when any filter carries a meaningful (non-empty) value. */
function hasActiveFilters(filters: WorkflowListFilters): boolean {
  return Object.values(filters).some((value) => {
    if (typeof value === 'string') {
      return value.trim() !== '';
    }
    return value !== undefined && value !== null && value !== '';
  });
}

/**
 * The documented per-state live-region announcement (AC8). Text is sourced from
 * the shared announcement vocabulary (story 2.25 AC7), never inlined.
 *
 * Story 2.25 (AC5) live-region duplication reconciliation: the `error` state
 * returns `''` — the composed `<ErrorState>` carries its own polite region, so
 * the shell announcer stays silent on error to avoid two regions speaking the
 * same failure (the 2.20 shell↔ErrorState double-announcement gap).
 */
function announcementFor(state: QueueState, count: number): string {
  switch (state) {
    case 'loading':
      return queueLoading;
    case 'empty':
      return queueEmpty;
    case 'filtered-empty':
      return queueFilteredEmpty;
    case 'error':
      return '';
    case 'populated':
      return queueLoaded(count);
    default:
      return '';
  }
}

/**
 * Minimal default row (AC9). Story 2.15's `RunReviewQueueItem` replaces this via
 * `renderItem`; both share `QUEUE_ROW_MIN_HEIGHT` so layout stays stable. Renders
 * a keyboard-operable deep link when the run id is well-formed, else inert text
 * (defensive — never build a route param from a malformed id).
 */
function QueuePlaceholderRow({ item }: { item: WorkflowSummary }) {
  const runId = item.workflowRunId;
  const meta = [item.currentState, item.lastEventAt].filter(Boolean).join(' · ');
  const body = (
    <span className="flex items-center gap-2">
      <span className="font-medium text-text-primary">{runId ?? 'Run'}</span>
      {meta ? <span className="text-meta text-text-secondary">{meta}</span> : null}
    </span>
  );
  const rowClass =
    'flex items-center rounded-md border border-border bg-surface px-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus';
  if (runId !== undefined && isValidRunId(runId)) {
    return (
      <Link
        to="/workflows/$workflowRunId"
        params={{ workflowRunId: runId }}
        className={`${rowClass} hover:bg-surface-elevated`}
        style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
        data-testid="queue-placeholder-row"
      >
        {body}
      </Link>
    );
  }
  return (
    <div
      className={rowClass}
      style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}
      data-testid="queue-placeholder-row"
    >
      {body}
    </div>
  );
}

/** Empty-state next step (AC3 / OQ-2): a docs CTA, gated behind a valid `VITE_DOCS_URL`. */
function GettingStartedAction() {
  const envRaw: unknown = import.meta.env.VITE_DOCS_URL;
  const href = typeof envRaw === 'string' ? envRaw : undefined;
  if (href !== undefined && validateUrlScheme(href).ok) {
    return (
      <Button asChild variant="outline" size="sm">
        <a href={href} target="_blank" rel="noreferrer">
          Read the getting-started guide
        </a>
      </Button>
    );
  }
  // Trap (lineage 2.22 T10) — no resolvable docs URL → disabled placeholder, never a dead link.
  return (
    <Button type="button" variant="outline" size="sm" disabled>
      Read the getting-started guide
    </Button>
  );
}

export function QueueShell({
  filters = {},
  renderItem,
  onClearFilters,
  onToggleTakenOverFilter,
  onToggleIncludeArchived,
  onProjectFilterChange,
}: QueueShellProps) {
  const query = useWorkflowsList(filters);
  const items = query.data ?? [];
  const filtersActive = hasActiveFilters(filters);
  const state = resolveQueueState({
    isError: query.isError,
    isPending: query.isPending,
    count: items.length,
    filtersActive,
  });

  // AC8/Task 8 — structured, field-only log of the resolved error state. Fires once
  // per error entry (deps), never logs row CONTENT or the raw error message.
  useEffect(() => {
    if (state !== 'error') {
      return;
    }
    console.warn({
      event: 'queue.loadError',
      state,
      code: isProblemDetailsError(query.error) ? query.error.code : 'transport',
      filtersActive,
    });
  }, [state, query.error, filtersActive]);

  const handleRetry = () => {
    console.info({ event: 'queue.retry', filtersActive });
    void query.refetch();
  };

  const handleClearFilters = () => {
    console.info({ event: 'queue.clearFilters', filtersActive });
    onClearFilters?.();
  };

  // Story 3.29 (AC4/R5) — the taken-over filter is the live single-state `?state`
  // filter pinned to `TakenOver`. Active state is read from the URL-backed `filters`
  // (no local state); toggling navigates to `?state=TakenOver` ⇄ cleared.
  const takenOverActive = filters.state === 'TakenOver';
  const handleToggleTakenOver = () => {
    const next = takenOverActive ? undefined : 'TakenOver';
    // Field-only log (Task 7) — the new filter state ONLY, never row content.
    console.info({ event: 'queue.toggleTakenOverFilter', state: next });
    onToggleTakenOverFilter?.(next);
  };

  // Story 3d-8 (AC5) — the include-archived toggle. Active state is read from the URL-backed
  // `filters` (no local state); toggling navigates to `?includeArchived=true` ⇄ cleared.
  const includeArchivedActive = filters.includeArchived === true;
  const handleToggleIncludeArchived = () => {
    const next = includeArchivedActive ? undefined : true;
    // Field-only log - the new filter flag ONLY, never row content.
    console.info({ event: 'queue.toggleIncludeArchivedFilter', includeArchived: next });
    onToggleIncludeArchived?.(next);
  };

  const handleProjectFilterChange = (next: string | undefined) => {
    console.info({ event: 'queue.projectFilterChange', projectId: next ?? null });
    onProjectFilterChange?.(next);
  };

  // AC5 — defer the announcement by one commit so even the cold-load "Loading…"
  // message (present at mount) is announced as a change, not swallowed as the
  // region's initial content (the 2.20 cold-load skeleton silence gap).
  const announcement = useLiveAnnouncement(announcementFor(state, items.length));

  return (
    <div className="flex flex-col gap-4" data-queue-state={state}>
      {/* AC1 (story 2a.1) — a persistent entry point to the submit form, reachable
          regardless of queue state (not only from the empty state). */}
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-page-title">Run review queue</h1>
        <div className="flex flex-wrap items-center gap-2">
          <ProjectSelector
            includeAllOption
            value={filters.projectId}
            onChange={handleProjectFilterChange}
          />
          {/* AC4/R5 - taken-over filter toggle (view-only-taken-over <-> all). */}
          <Button
            type="button"
            variant={takenOverActive ? 'default' : 'outline'}
            size="sm"
            aria-pressed={takenOverActive}
            onClick={handleToggleTakenOver}
            data-testid="queue-takenover-filter-toggle"
          >
            {takenOverActive ? 'Showing taken-over runs' : 'Show taken-over runs'}
          </Button>
          {/* Story 3d-8 (AC5) — include-archived filter toggle (default hidden ⇄ shown). */}
          <Button
            type="button"
            variant={includeArchivedActive ? 'default' : 'outline'}
            size="sm"
            aria-pressed={includeArchivedActive}
            onClick={handleToggleIncludeArchived}
            data-testid="queue-include-archived-toggle"
          >
            {includeArchivedActive ? 'Showing archived runs' : 'Show archived runs'}
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link to="/submit" data-testid="queue-submit-run-link">
              Submit a run
            </Link>
          </Button>
        </div>
      </div>

      {/* AC8 — single visually-hidden polite announcer for the active state. */}
      <div role="status" aria-live="polite" className="sr-only" data-testid="queue-announcer">
        {announcement}
      </div>

      {state === 'loading' ? (
        <div
          className="flex flex-col gap-2"
          aria-busy="true"
          aria-hidden
          data-testid="queue-loading"
        >
          {Array.from({ length: QUEUE_SKELETON_ROW_COUNT }, (_, index) => (
            <Skeleton key={index} className="w-full" style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }} />
          ))}
        </div>
      ) : null}

      {state === 'error' ? (
        <ErrorState
          variant="failedRetrieval"
          urgency="passive"
          message={`${queueErrorMessage(query.error)} If this keeps happening, report it to your administrator.`}
          nextAction={{ kind: 'Retry', onRetry: handleRetry }}
        />
      ) : null}

      {state === 'empty' ? (
        <EmptyState
          variant="queue"
          message="No specifications awaiting review. New runs from Linear appear here once submitted — or submit a run from a Linear ticket."
          action={
            <div className="flex flex-wrap items-center gap-2">
              <Button asChild variant="default" size="sm">
                <Link to="/submit" data-testid="empty-state-submit-link">
                  Submit a run
                </Link>
              </Button>
              <GettingStartedAction />
            </div>
          }
        />
      ) : null}

      {state === 'filtered-empty' ? (
        <EmptyState
          variant="filtered"
          action={
            <Button type="button" variant="outline" size="sm" onClick={handleClearFilters}>
              Clear filters
            </Button>
          }
        />
      ) : null}

      {state === 'populated' ? (
        // Tailwind v4 preflight resets `ul{list-style:none}`, and WebKit (Safari + iOS
        // VoiceOver) drops the implicit `list` role when list-style is none; the explicit
        // role keeps SR users hearing "list, N items" (AC8). See story 2.20 review M1.
        // eslint-disable-next-line jsx-a11y/no-redundant-roles
        <ul role="list" className="flex flex-col gap-2" data-testid="queue-list">
          {items.map((item, index) => (
            <li key={item.workflowRunId ?? index}>
              {renderItem ? renderItem(item) : <QueuePlaceholderRow item={item} />}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
