/**
 * Story 4.20 (AC2–AC8, UX-DR13) — the `CompareMode` presentational composite.
 *
 * Mirrors the `ArtifactReviewPanel` discipline: PRESENTATIONAL + prop-driven (a resolved
 * `state` + normalized `view`), router/query-free, tested directly with fixtures. The thin
 * `CompareModeContainer` owns the `useRevisionDelta` read + `resolveCompareState` + structured
 * logging and maps to these props.
 *
 * Anatomy (AC2): A/B revision identifiers, a summary header (changed-region + add/remove/modify
 * counts), a filter toggle ("Show only changes" / "Show all"), jump controls (J/K + arrow keys),
 * an exit-back-to-review control, a non-color changed-region gutter, and a comparison surface
 * whose LAYOUT is driven by `view.artifactType` (spec/plan → side-by-side; prOutput → stacked
 * file accordions — AC4). The per-block RENDERER is driven by `block.kind` (AC4). All untrusted
 * delta text routes through the `@/lib/sanitization` barrel (AC5).
 *
 * Exactly one `data-compare-state` is reachable per render (mirror `data-artifact-panel-state`).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowDownWideNarrow, ArrowUpWideNarrow, X } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { EmptyState, ErrorState } from '@/components/feedback';
import {
  PR_DIFF_MAX_LINES,
  SafeMarkdownRenderer,
  SafeUnifiedDiffRenderer,
  parseUnifiedDiff,
  renderTextWithRedactions,
} from '@/lib/sanitization';
import {
  compareExited,
  compareJumpedToRegion,
  compareLoadFailed,
  compareLoaded,
  compareNoMeaningfulDiff,
  compareShowingAfter,
  compareShowingBefore,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';

import {
  compareLayout,
  type ChangeBlockView,
  type ChangeKind,
  type CompareLayout,
  type CompareRevisionSummary,
  type CompareState,
  type CompareView,
} from '../compareView';
import { useArtifact } from '../hooks/useArtifact';
import { formatRelativeTime, formatUtcTimestamp } from '../runContextFormat';

export interface CompareModeProps {
  /** The resolved compare state (see `resolveCompareState`). */
  state: CompareState;
  /** The normalized view — present for `default`/`no-meaningful-diff`. */
  view?: CompareView | undefined;
  /** Exit back to the originating review context (`useReturnToRunContext` + overlay close). */
  onExit: () => void;
  /** Retry handler for the retryable `partial` (503) sub-state. */
  onRetry?: (() => void) | undefined;
  /**
   * prOutput lazy full-diff (Reconciliation 8): the run + current-artifact ids used by the
   * expandable file diff's `useArtifact` read. Both must be non-empty to enable the lazy read;
   * fixture-driven presentational tests omit them so no query fires (router/query-free).
   */
  readonly workflowRunId?: string | undefined;
  readonly artifactIdB?: string | undefined;
  /**
   * Story 4.21 (UX-DR23) — the resolved viewport, threaded from the container's `useResponsiveLayout()`
   * read (Reconciliation 2). `'mobile'` (<768px) selects the dedicated full-screen single-column
   * bounded state for the `default` comparison; `'desktop'` (tablet + desktop) keeps the 4.20 body.
   * Defaults to `'desktop'` so fixture-driven presentational tests stay matchMedia-free.
   */
  readonly viewport?: 'mobile' | 'desktop';
}

/** A non-color change signifier (symbol + text label) — never color/icon alone (2.3 AC5). */
function changeKindSignifier(kind: ChangeKind): { symbol: string; label: string } {
  switch (kind) {
    case 'added':
      return { symbol: '+', label: 'Added' };
    case 'removed':
      return { symbol: '−', label: 'Removed' };
    case 'reordered':
      return { symbol: '⇅', label: 'Reordered' };
    case 'modified':
    default:
      return { symbol: '~', label: 'Modified' };
  }
}

/** A short structural label for a change block (section path / step id / file path). */
function blockLabel(block: ChangeBlockView): string {
  switch (block.kind) {
    case 'markdown':
      return block.sectionPath ?? 'Section';
    case 'planStep':
      return block.stepId ?? 'Step';
    case 'file':
      return block.filePath ?? 'File';
    default:
      return 'Region';
  }
}

/** A/B identifier chip — `version`, `producedByActor` ("unknown" when null), relative `createdAt`. */
function RevisionIdentifier({
  side,
  label,
  revision,
  testId,
}: {
  side: 'A' | 'B';
  label: string;
  revision: CompareRevisionSummary;
  testId: string;
}) {
  const createdRelative =
    revision.createdAt != null ? formatRelativeTime(revision.createdAt) : null;
  const createdUtc = revision.createdAt != null ? formatUtcTimestamp(revision.createdAt) : null;
  return (
    <div className="flex flex-col gap-0.5" data-testid={testId} data-compare-side={side}>
      <span className="text-annotation uppercase tracking-wide text-text-tertiary">{label}</span>
      <span className="text-sm font-medium text-text-primary">
        {revision.version != null ? `v${revision.version}` : 'unknown version'}
      </span>
      <span className="text-meta text-text-tertiary">
        by {revision.producedByActor ?? 'unknown'}
        {createdRelative != null ? (
          <>
            {' · '}
            <time dateTime={revision.createdAt ?? undefined} title={createdUtc ?? undefined}>
              {createdRelative}
            </time>
          </>
        ) : null}
      </span>
    </div>
  );
}

/** UNTRUSTED spec section text (prior/current) → block-level `SafeMarkdownRenderer` (AC5). */
function MarkdownCell({ text, empty }: { text: string | null; empty: string }) {
  if (text == null) {
    return <p className="text-meta italic text-text-tertiary">{empty}</p>;
  }
  return <SafeMarkdownRenderer source={text} className="prose" />;
}

/** UNTRUSTED plan-step text → React-escaped PLAIN text (never markdown in interactive elements). */
function PlanStepCell({
  text,
  order,
  empty,
}: {
  text: string | null;
  order: number | null;
  empty: string;
}) {
  if (text == null) {
    return <p className="text-meta italic text-text-tertiary">{empty}</p>;
  }
  return (
    <p className="text-sm text-text-secondary">
      {order != null ? (
        <span className="mr-2 text-annotation uppercase tracking-wide text-text-tertiary">
          #{order}
        </span>
      ) : null}
      {text}
    </p>
  );
}

/**
 * prOutput lazy full-diff (Reconciliation 8). On file-accordion expand, read the CURRENT artifact
 * via the EXISTING `useArtifact` (from `linkedDiffReferences`), parse its resolved unified `diff`,
 * find the file by path, and render it via the sanctioned `SafeUnifiedDiffRenderer` (line-capped).
 * Mounted ONLY when both ids are present, so presentational fixture tests never fire a query.
 */
function CompareFileDiff({
  workflowRunId,
  artifactIdB,
  filePath,
}: {
  workflowRunId: string;
  artifactIdB: string;
  filePath: string | null;
}) {
  const { data, isFetching, isError } = useArtifact(workflowRunId, artifactIdB);
  const files = useMemo(
    () => (data?.artifactType === 'prOutput' ? parseUnifiedDiff(data.diff) : []),
    [data],
  );
  if (isFetching) {
    return <Skeleton className="h-16 w-full" data-testid="compare-file-diff-loading" />;
  }
  if (isError) {
    return (
      <p className="text-meta text-text-tertiary" data-testid="compare-file-diff-error">
        The full diff for this file could not be loaded.
      </p>
    );
  }
  const match = filePath != null ? files.find((file) => file.path === filePath) : undefined;
  if (match === undefined) {
    return (
      <p className="text-meta text-text-tertiary" data-testid="compare-file-diff-empty">
        No unified diff is available for this file.
      </p>
    );
  }
  if (match.binary === true) {
    return (
      <p className="text-meta text-text-tertiary" data-testid="compare-file-diff-binary">
        Binary file — no textual diff shown.
      </p>
    );
  }
  return (
    <SafeUnifiedDiffRenderer
      hunks={match.hunks}
      maxLines={PR_DIFF_MAX_LINES}
      aria-label={`Diff for ${match.path}`}
    />
  );
}

export function CompareMode({
  state,
  view,
  onExit,
  onRetry,
  workflowRunId,
  artifactIdB,
  viewport = 'desktop',
}: CompareModeProps) {
  const isMobile = viewport === 'mobile';
  // AC7 — the "Show only changes" filter defaults ON where the diff is non-trivial.
  const [showOnlyChanges, setShowOnlyChanges] = useState(true);
  // Story 4.21 (AC2) — the mobile before/after toggle: which single revision the column shows.
  // Default `after` (the current/B artifact under review — OQ-2). Desktop ignores it.
  const [side, setSide] = useState<'before' | 'after'>('after');
  // AC4 summary-first — OQ-4 provisional default: collapsed for prOutput (dense file diffs),
  // expanded for spec/plan (fewer, section/step-level blocks).
  const layout = view !== undefined ? compareLayout(view.artifactType) : 'side-by-side';
  const [summaryFirst, setSummaryFirst] = useState(layout === 'stacked');

  // OQ-4 — seed the summary-first default from `artifactType` the first time a view of that type
  // resolves (collapsed for prOutput, expanded for spec/plan). Runs once per artifactType, so it
  // never clobbers a user toggle within the same compare; a different compare re-seeds.
  const initialisedType = useRef<string | null>(null);
  useEffect(() => {
    if (view !== undefined && initialisedType.current !== view.artifactType) {
      initialisedType.current = view.artifactType;
      setSummaryFirst(compareLayout(view.artifactType) === 'stacked');
      setShowOnlyChanges(true);
    }
  }, [view]);

  // Review patch (4.21, OQ-2) — the mobile before/after toggle defaults to `after` per COMPARE, not
  // per artifactType. Re-seed to `after` whenever the compared revision pair changes, so a NEW
  // same-type compare on the same mounted instance (e.g. navigating between two spec compares) still
  // opens on the current/B revision instead of leaking the operator's prior Before/After choice. The
  // type-keyed seed above governs `summaryFirst`/`showOnlyChanges` only.
  const initialisedPair = useRef<string | null>(null);
  useEffect(() => {
    if (view === undefined) {
      return;
    }
    const pairKey = `${artifactIdB ?? ''}|${view.revisionA.version ?? ''}|${view.revisionB.version ?? ''}`;
    if (initialisedPair.current !== pairKey) {
      initialisedPair.current = pairKey;
      setSide('after');
    }
  }, [view, artifactIdB]);

  // Per-region expand overrides (side-by-side); effective expanded = override ?? !summaryFirst.
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});
  useEffect(() => {
    // Reset per-region overrides when the summary-first default flips.
    setExpanded({});
  }, [summaryFirst]);

  const blocks = view?.blocks ?? [];
  const total = blocks.length;

  // Jump-to-changed-region (AC6) — focus/scroll between region anchors; announce the landing.
  const regionRefs = useRef<(HTMLElement | null)[]>([]);
  const currentRegion = useRef<number>(-1);
  const [jumpAnnouncement, setJumpAnnouncement] = useState<string | null>(null);
  useEffect(() => {
    // A state change resets the jump announcement so the base state announcement is re-spoken.
    setJumpAnnouncement(null);
    currentRegion.current = -1;
  }, [state]);

  const goToRegion = useCallback(
    (index: number) => {
      if (index < 0 || index >= total) {
        return;
      }
      const node = regionRefs.current[index];
      if (node != null) {
        // jsdom implements scrollIntoView as a no-op; guarded for any environment lacking it.
        if (typeof node.scrollIntoView === 'function') {
          node.scrollIntoView({ block: 'center' });
        }
        node.focus();
      }
      currentRegion.current = index;
      setJumpAnnouncement(compareJumpedToRegion(index + 1, total));
    },
    [total],
  );

  const jump = useCallback(
    (direction: 1 | -1) => {
      if (total === 0) {
        return;
      }
      const base = currentRegion.current;
      const next =
        base === -1 ? (direction === 1 ? 0 : total - 1) : (base + direction + total) % total;
      goToRegion(next);
    },
    [total, goToRegion],
  );

  // ONE aria-live region + centralized vocabulary (Reconciliation 12). A jump announcement wins
  // over the base state announcement while it is set.
  const baseAnnouncement = useMemo(() => {
    if (state === 'default' && view !== undefined) {
      return compareLoaded(view.summary.changedRegionCount);
    }
    if (state === 'no-meaningful-diff') {
      return compareNoMeaningfulDiff;
    }
    if (state === 'no-baseline' || state === 'partial' || state === 'unavailable') {
      return compareLoadFailed;
    }
    return '';
  }, [state, view]);
  const announcement = useLiveAnnouncement(jumpAnnouncement ?? baseAnnouncement);

  const handleExit = useCallback(() => {
    // Announce before delegating; the overlay unmounts on exit, but the vocabulary is pinned.
    setJumpAnnouncement(compareExited);
    onExit();
  }, [onExit]);

  // Story 4.21 (AC2) — flip the mobile before/after toggle and announce the shown revision through
  // the SAME single live region (reuses the transient-over-base announcement slot).
  const showSide = useCallback((next: 'before' | 'after') => {
    setSide(next);
    setJumpAnnouncement(next === 'before' ? compareShowingBefore : compareShowingAfter);
  }, []);

  // AC6 — J/K/arrow jump + Esc exit as region-level keyboard-shortcut ACCELERATORS. Bound as a
  // native listener on the section (not a JSX handler on the non-interactive landmark) so the
  // shortcuts layer over the natively-operable children (jump buttons, region toggles, exit).
  const sectionRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const node = sectionRef.current;
    if (node === null) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        handleExit();
        return;
      }
      if (state !== 'default') {
        return;
      }
      if (event.key === 'j' || event.key === 'ArrowDown') {
        event.preventDefault();
        jump(1);
      } else if (event.key === 'k' || event.key === 'ArrowUp') {
        event.preventDefault();
        jump(-1);
      }
    };
    node.addEventListener('keydown', onKey);
    return () => node.removeEventListener('keydown', onKey);
  }, [state, jump, handleExit]);

  // AC6 — move focus into the section on entry so the native keydown accelerators (J/K/Esc) fire
  // immediately. Opening the overlay unmounts the Artifact Review Panel (which held focus on the
  // Compare button), so focus otherwise falls back to `document.body` — NOT a descendant of this
  // section — and the region-level `keydown` listener never receives events until the user
  // manually Tabs/clicks in. The section is `tabIndex={-1}`, so it is programmatically focusable.
  // Review patch D1: on the mobile takeover, `CompareModeMobileBody` owns initial focus + the focus
  // trap (its dialog is a descendant of this section, so the keydown accelerators still fire); do
  // NOT steal that focus back to the section here.
  useEffect(() => {
    if (isMobile && state === 'default') {
      return;
    }
    sectionRef.current?.focus();
  }, [isMobile, state]);

  const isRegionExpanded = (index: number): boolean => expanded[index] ?? !summaryFirst;
  const toggleRegion = (index: number) =>
    setExpanded((prev) => ({ ...prev, [index]: !(prev[index] ?? !summaryFirst) }));

  const lazyDiffEnabled =
    workflowRunId != null &&
    workflowRunId.length > 0 &&
    artifactIdB != null &&
    artifactIdB.length > 0;

  return (
    <section
      ref={sectionRef}
      aria-label="Compare revisions"
      data-testid="compare-mode"
      data-compare-state={state}
      data-compare-viewport={viewport}
      tabIndex={-1}
      className="w-full outline-none"
    >
      {/* ONE polite live region for the whole surface (Reconciliation 12). */}
      <p className="sr-only" role="status" aria-live="polite" data-testid="compare-announcer">
        {announcement}
      </p>

      {isMobile && state === 'default' && view !== undefined ? (
        // Story 4.21 (AC1/AC5/AC6) — the dedicated full-screen single-column bounded state. Only the
        // `default` comparison gets the bespoke takeover; non-default mobile states fall through to
        // the shared exit header + existing responsive renders (OQ-4).
        <CompareModeMobileBody
          view={view}
          layout={layout}
          side={side}
          onShowSide={showSide}
          onJumpPrev={() => jump(-1)}
          onJumpNext={() => jump(1)}
          onExit={handleExit}
          regionRefs={regionRefs}
          lazyDiffEnabled={lazyDiffEnabled}
          workflowRunId={workflowRunId ?? ''}
          artifactIdB={artifactIdB ?? ''}
        />
      ) : (
        <>
          {/* A minimal exit affordance is present in EVERY state so the operator can always leave. */}
          <div className="mb-3 flex items-center justify-between gap-2">
            <h2 className="text-section-heading">Compare revisions</h2>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleExit}
              data-testid="compare-exit"
            >
              <X className="mr-1 size-4" aria-hidden />
              Exit compare
            </Button>
          </div>

          {state === 'loading' ? (
            <div className="space-y-3" data-testid="compare-loading">
              <Skeleton className="h-6 w-64" />
              <div className="grid grid-cols-2 gap-4">
                <Skeleton className="h-40 w-full" />
                <Skeleton className="h-40 w-full" />
              </div>
            </div>
          ) : null}

          {state === 'no-baseline' ? (
            <ErrorState
              variant="unavailableDiffBaseline"
              urgency="passive"
              message="No earlier revision is available to compare against."
              nextAction={{ kind: 'NavigateBack', label: 'Back to review' }}
            />
          ) : null}

          {state === 'partial' ? (
            <ErrorState
              variant="failedRetrieval"
              urgency="active"
              title="Partial comparison"
              message="One revision’s payload could not be read. This may be temporary."
              nextAction={{ kind: 'Retry', onRetry: onRetry ?? (() => undefined) }}
            />
          ) : null}

          {state === 'unavailable' ? (
            <ErrorState
              variant="failedRetrieval"
              urgency="passive"
              title="Comparison unavailable"
              message="These revisions can’t be compared."
              nextAction={{ kind: 'NavigateBack', label: 'Back to review' }}
            />
          ) : null}

          {state === 'no-meaningful-diff' ? (
            <div data-testid="compare-no-meaningful-diff">
              {view !== undefined ? (
                <CompareSummaryHeader view={view} showControls={false} />
              ) : null}
              <EmptyState
                variant="noMeaningfulDiff"
                title="These revisions are identical"
                message="There are no meaningful differences between these two versions."
              />
            </div>
          ) : null}

          {state === 'default' && view !== undefined ? (
            <>
              <CompareSummaryHeader
                view={view}
                showControls
                showOnlyChanges={showOnlyChanges}
                onToggleShowOnlyChanges={() => setShowOnlyChanges((prev) => !prev)}
                summaryFirst={summaryFirst}
                onToggleSummaryFirst={() => setSummaryFirst((prev) => !prev)}
                onJumpPrev={() => jump(-1)}
                onJumpNext={() => jump(1)}
              />

              {!showOnlyChanges ? (
                <p className="mb-2 text-meta text-text-tertiary" data-testid="compare-all-note">
                  A revision delta lists only the changed regions; there is no unchanged content to
                  show.
                </p>
              ) : null}

              <div className="flex gap-3">
                {/* Non-color changed-region gutter (AC2 / 2.3 AC5) — one shape+label marker per region. */}
                <nav
                  aria-label="Changed regions"
                  className="flex shrink-0 flex-col gap-1"
                  data-testid="compare-gutter"
                >
                  {blocks.map((block, index) => {
                    const sig = changeKindSignifier(block.changeKind);
                    return (
                      <button
                        key={`marker-${index}`}
                        type="button"
                        onClick={() => goToRegion(index)}
                        className="flex items-center gap-1 rounded-sm border border-border px-1.5 py-0.5 text-annotation text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                        data-compare-region-marker={index}
                        data-change-kind={block.changeKind}
                        aria-label={`${sig.label}: ${blockLabel(block)}`}
                        title={`${sig.label}: ${blockLabel(block)}`}
                      >
                        <span aria-hidden className="font-mono">
                          {sig.symbol}
                        </span>
                        <span className="max-w-[8rem] truncate">{blockLabel(block)}</span>
                      </button>
                    );
                  })}
                </nav>

                {/* Comparison surface — layout driven by artifactType (AC4). */}
                <div
                  className="min-w-0 flex-1"
                  data-testid="compare-surface"
                  data-compare-layout={layout}
                >
                  {total === 0 ? (
                    <p className="text-meta text-text-tertiary" data-testid="compare-surface-empty">
                      No changed regions.
                    </p>
                  ) : layout === 'stacked' ? (
                    <StackedFileSurface
                      blocks={blocks}
                      regionRefs={regionRefs}
                      summaryFirst={summaryFirst}
                      lazyDiffEnabled={lazyDiffEnabled}
                      workflowRunId={workflowRunId ?? ''}
                      artifactIdB={artifactIdB ?? ''}
                    />
                  ) : (
                    // Single scrollport (inherently synced scroll — OQ-3) holding a 2-column
                    // prior|current row per changed region.
                    <div
                      className="max-h-[32rem] overflow-y-auto"
                      data-testid="compare-synced-scroll"
                    >
                      {blocks.map((block, index) => (
                        <SideBySideRegion
                          key={`region-${index}`}
                          block={block}
                          index={index}
                          expanded={isRegionExpanded(index)}
                          onToggle={() => toggleRegion(index)}
                          registerRef={(node) => {
                            regionRefs.current[index] = node;
                          }}
                        />
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </>
          ) : null}
        </>
      )}
    </section>
  );
}

/** The always-first summary header (AC2/AC7) — A/B identifiers + counts + optional controls. */
function CompareSummaryHeader({
  view,
  showControls,
  showOnlyChanges,
  onToggleShowOnlyChanges,
  summaryFirst,
  onToggleSummaryFirst,
  onJumpPrev,
  onJumpNext,
}: {
  view: CompareView;
  showControls: boolean;
  showOnlyChanges?: boolean;
  onToggleShowOnlyChanges?: () => void;
  summaryFirst?: boolean;
  onToggleSummaryFirst?: () => void;
  onJumpPrev?: () => void;
  onJumpNext?: () => void;
}) {
  const { summary } = view;
  return (
    <header className="mb-3" data-testid="compare-summary">
      <div className="mb-2 flex flex-wrap items-start justify-between gap-3">
        <RevisionIdentifier
          side="A"
          label="Before (prior)"
          revision={view.revisionA}
          testId="compare-revision-a"
        />
        <RevisionIdentifier
          side="B"
          label="After (current)"
          revision={view.revisionB}
          testId="compare-revision-b"
        />
      </div>
      <div className="flex flex-wrap items-center gap-2" data-testid="compare-summary-counts">
        <Badge variant="secondary" data-testid="compare-count-regions">
          {summary.changedRegionCount} changed{' '}
          {summary.changedRegionCount === 1 ? 'region' : 'regions'}
        </Badge>
        <Badge variant="outline" data-testid="compare-count-added">
          +{summary.addedCount} added
        </Badge>
        <Badge variant="outline" data-testid="compare-count-removed">
          {'−'}
          {summary.removedCount} removed
        </Badge>
        <Badge variant="outline" data-testid="compare-count-modified">
          ~{summary.modifiedCount} modified
        </Badge>
      </div>
      {showControls ? (
        <div className="mt-2 flex flex-wrap items-center gap-2" data-testid="compare-controls">
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-pressed={showOnlyChanges}
            onClick={onToggleShowOnlyChanges}
            data-testid="compare-filter-toggle"
          >
            {showOnlyChanges === true ? 'Show all' : 'Show only changes'}
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-pressed={summaryFirst}
            onClick={onToggleSummaryFirst}
            data-testid="compare-summary-first-toggle"
          >
            {summaryFirst === true ? 'Expand all' : 'Summary first'}
          </Button>
          <div className="ml-auto flex items-center gap-1">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onJumpPrev}
              data-testid="compare-jump-prev"
              aria-label="Previous changed region"
            >
              <ArrowUpWideNarrow className="size-4" aria-hidden />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={onJumpNext}
              data-testid="compare-jump-next"
              aria-label="Next changed region"
            >
              <ArrowDownWideNarrow className="size-4" aria-hidden />
            </Button>
          </div>
        </div>
      ) : null}
    </header>
  );
}

/** One changed region in the side-by-side (spec/plan) layout — prior (left) | current (right). */
function SideBySideRegion({
  block,
  index,
  expanded,
  onToggle,
  registerRef,
}: {
  block: ChangeBlockView;
  index: number;
  expanded: boolean;
  onToggle: () => void;
  registerRef: (node: HTMLElement | null) => void;
}) {
  const sig = changeKindSignifier(block.changeKind);
  return (
    <div
      ref={registerRef}
      tabIndex={-1}
      data-testid={`compare-region-${index}`}
      data-compare-region-index={index}
      data-change-kind={block.changeKind}
      data-block-kind={block.kind}
      className="mb-3 rounded-md border border-border p-2 outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
    >
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        className="mb-1 flex w-full items-center gap-2 text-left text-sm font-medium text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
        data-testid={`compare-region-toggle-${index}`}
      >
        <span
          className="shrink-0 rounded-sm border border-border px-1 font-mono text-annotation"
          aria-hidden
        >
          {sig.symbol}
        </span>
        <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
          {sig.label}
        </span>
        <span className="min-w-0 truncate">{blockLabel(block)}</span>
      </button>
      {expanded ? (
        <div className="grid grid-cols-2 gap-4" data-testid={`compare-region-detail-${index}`}>
          <div className="min-w-0" aria-label="Prior revision" data-compare-pane="prior">
            {block.kind === 'markdown' ? (
              <MarkdownCell text={block.priorText} empty="Not present in the prior revision." />
            ) : block.kind === 'planStep' ? (
              <PlanStepCell
                text={block.priorStepText}
                order={block.priorStepOrder}
                empty="Not present in the prior revision."
              />
            ) : null}
          </div>
          <div className="min-w-0" aria-label="Current revision" data-compare-pane="current">
            {block.kind === 'markdown' ? (
              <MarkdownCell text={block.currentText} empty="Removed in the current revision." />
            ) : block.kind === 'planStep' ? (
              <PlanStepCell
                text={block.currentStepText}
                order={block.currentStepOrder}
                empty="Removed in the current revision."
              />
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** The stacked (prOutput) layout — one collapsible file accordion per changed file (AC4). */
function StackedFileSurface({
  blocks,
  regionRefs,
  summaryFirst,
  lazyDiffEnabled,
  workflowRunId,
  artifactIdB,
}: {
  blocks: readonly ChangeBlockView[];
  regionRefs: React.MutableRefObject<(HTMLElement | null)[]>;
  summaryFirst: boolean;
  lazyDiffEnabled: boolean;
  workflowRunId: string;
  artifactIdB: string;
}) {
  const allValues = blocks.map((_, index) => `file-${index}`);
  const [open, setOpen] = useState<string[]>(summaryFirst ? [] : allValues);
  useEffect(() => {
    setOpen(summaryFirst ? [] : allValues);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset when the summary-first default flips.
  }, [summaryFirst]);

  return (
    <Accordion type="multiple" value={open} onValueChange={setOpen}>
      {blocks.map((block, index) => {
        const sig = changeKindSignifier(block.changeKind);
        const value = `file-${index}`;
        const isOpen = open.includes(value);
        const filePath = block.kind === 'file' ? block.filePath : null;
        return (
          <AccordionItem
            key={value}
            value={value}
            data-testid={`compare-region-${index}`}
            data-compare-region-index={index}
            data-change-kind={block.changeKind}
            data-block-kind={block.kind}
          >
            <AccordionTrigger
              ref={(node) => {
                regionRefs.current[index] = node;
              }}
              data-testid={`compare-region-toggle-${index}`}
            >
              <span className="flex min-w-0 flex-1 items-center gap-2">
                <span className="shrink-0 font-mono text-annotation" aria-hidden>
                  {sig.symbol}
                </span>
                <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
                  {sig.label}
                </span>
                {/* File path is UNTRUSTED → the sanctioned redaction path (AC5). */}
                <code className="min-w-0 truncate text-text-primary">
                  {filePath != null ? renderTextWithRedactions(filePath) : 'Unknown file'}
                </code>
                {block.kind === 'file' ? (
                  <span className="shrink-0 text-meta tabular-nums">
                    <span className="text-state-success-foreground">+{block.addedLines ?? 0}</span>{' '}
                    <span className="text-state-error-foreground">
                      {'−'}
                      {block.removedLines ?? 0}
                    </span>
                  </span>
                ) : null}
              </span>
            </AccordionTrigger>
            <AccordionContent data-testid={`compare-region-detail-${index}`}>
              {isOpen && lazyDiffEnabled ? (
                <CompareFileDiff
                  workflowRunId={workflowRunId}
                  artifactIdB={artifactIdB}
                  filePath={filePath}
                />
              ) : (
                <p className="text-meta text-text-tertiary" data-testid="compare-file-diff-summary">
                  Expand loads the full unified diff for this file.
                </p>
              )}
            </AccordionContent>
          </AccordionItem>
        );
      })}
    </Accordion>
  );
}

/** Compact revision label ("v2" / "unknown") for the mobile top bar. */
function revisionVersionLabel(revision: CompareRevisionSummary): string {
  return revision.version != null ? `v${revision.version}` : 'unknown';
}

/**
 * Story 4.21 (AC1–AC6, UX-DR23) — the dedicated mobile bounded state: a `fixed inset-0` full-screen
 * takeover (covers the AppShell nav rail + context panel — Reconciliation 4) with a persistent top
 * bar (revision A/B labels + before/after toggle + prev/next change + exit) over a single-column
 * body that scrolls beneath it. NEVER a shrunk side-by-side (AC6): one revision at a time for
 * spec/plan (driven by the toggle); prOutput reuses the existing single-column file accordions
 * (its per-file diff is inherently before/after, so the toggle is hidden — OQ-2). Reuses the
 * parent's `regionRefs`/jump machinery + the SAME single aria-live region (via `onShowSide`).
 */
function CompareModeMobileBody({
  view,
  layout,
  side,
  onShowSide,
  onJumpPrev,
  onJumpNext,
  onExit,
  regionRefs,
  lazyDiffEnabled,
  workflowRunId,
  artifactIdB,
}: {
  view: CompareView;
  layout: CompareLayout;
  side: 'before' | 'after';
  onShowSide: (next: 'before' | 'after') => void;
  onJumpPrev: () => void;
  onJumpNext: () => void;
  onExit: () => void;
  regionRefs: React.MutableRefObject<(HTMLElement | null)[]>;
  lazyDiffEnabled: boolean;
  workflowRunId: string;
  artifactIdB: string;
}) {
  // OQ-1 — a full-screen takeover locks background scroll while mounted (restored on exit).
  useEffect(() => {
    if (typeof document === 'undefined') {
      return;
    }
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  // Review patch D1 (a11y) — the takeover is a modal dialog: the covered AppShell nav rail + context
  // panel stay in the DOM, so contain Tab focus within the layer while it is open and restore focus
  // to the trigger on close. Pairs with `role="dialog"`/`aria-modal` on the container below. (Esc
  // exit is already handled by the parent section's keydown listener.)
  const containerRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (typeof document === 'undefined') {
      return;
    }
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const container = containerRef.current;
    // Move focus into the takeover on open (the container is programmatically focusable).
    container?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Tab' || container === null) {
        return;
      }
      const focusables = Array.from(
        container.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])',
        ),
      );
      if (focusables.length === 0) {
        event.preventDefault();
        container.focus();
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (first === undefined || last === undefined) {
        return;
      }
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === container)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    container?.addEventListener('keydown', onKeyDown);
    return () => {
      container?.removeEventListener('keydown', onKeyDown);
      if (previouslyFocused != null && typeof previouslyFocused.focus === 'function') {
        previouslyFocused.focus();
      }
    };
  }, []);

  const { blocks } = view;
  // prOutput file diffs are inherently before/after within one view → hide the toggle (OQ-2).
  const showToggle = layout !== 'stacked';

  return (
    <div
      ref={containerRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby="compare-mobile-title"
      tabIndex={-1}
      className="fixed inset-0 z-50 flex flex-col bg-surface outline-none"
      data-testid="compare-mobile-body"
    >
      {/* Persistent (sticky-by-flex) top bar — never scrolls with the body (AC5). */}
      <div className="shrink-0 border-b border-border">
        <div className="flex items-start justify-between gap-2 px-4 py-3">
          <div className="flex min-w-0 flex-col gap-0.5">
            <h2 id="compare-mobile-title" className="text-section-heading">
              Compare revisions
            </h2>
            <p className="text-meta text-text-tertiary" data-testid="compare-mobile-revisions">
              Before {revisionVersionLabel(view.revisionA)} · After{' '}
              {revisionVersionLabel(view.revisionB)}
            </p>
          </div>
          <button
            type="button"
            onClick={onExit}
            aria-label="Exit compare"
            data-testid="compare-mobile-exit"
            className="inline-flex min-h-touch min-w-touch shrink-0 items-center justify-center rounded-md border border-border text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          >
            <X className="size-5" aria-hidden />
          </button>
        </div>

        <div className="flex flex-wrap items-center gap-2 px-4 pb-3">
          {showToggle ? (
            <div
              role="group"
              aria-label="Show revision"
              data-testid="compare-mobile-toggle"
              data-compare-side={side}
              className="inline-flex overflow-hidden rounded-md border border-border"
            >
              <button
                type="button"
                onClick={() => onShowSide('before')}
                aria-pressed={side === 'before'}
                data-testid="compare-mobile-before"
                className={`inline-flex min-h-touch min-w-touch items-center justify-center px-3 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus ${
                  side === 'before'
                    ? 'bg-surface-elevated text-text-primary'
                    : 'text-text-secondary'
                }`}
              >
                Before
              </button>
              <button
                type="button"
                onClick={() => onShowSide('after')}
                aria-pressed={side === 'after'}
                data-testid="compare-mobile-after"
                className={`inline-flex min-h-touch min-w-touch items-center justify-center border-l border-border px-3 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus ${
                  side === 'after' ? 'bg-surface-elevated text-text-primary' : 'text-text-secondary'
                }`}
              >
                After
              </button>
            </div>
          ) : (
            <p className="text-meta text-text-tertiary" data-testid="compare-mobile-proutput-note">
              Each file shows its before/after diff together.
            </p>
          )}

          <div className="ml-auto flex items-center gap-1">
            <button
              type="button"
              onClick={onJumpPrev}
              aria-label="Previous change"
              data-testid="compare-mobile-jump-prev"
              className="inline-flex min-h-touch min-w-touch items-center justify-center rounded-md border border-border text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
            >
              <ArrowUpWideNarrow className="size-5" aria-hidden />
            </button>
            <button
              type="button"
              onClick={onJumpNext}
              aria-label="Next change"
              data-testid="compare-mobile-jump-next"
              className="inline-flex min-h-touch min-w-touch items-center justify-center rounded-md border border-border text-text-secondary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
            >
              <ArrowDownWideNarrow className="size-5" aria-hidden />
            </button>
          </div>
        </div>
      </div>

      {/* Single-column body — the ONLY scroll region (artifact-primacy, AC5). */}
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3" data-testid="compare-mobile-scroll">
        {blocks.length === 0 ? (
          <p className="text-meta text-text-tertiary" data-testid="compare-mobile-empty">
            No changed regions.
          </p>
        ) : layout === 'stacked' ? (
          // prOutput — the existing single-column file accordions (already one-file-at-a-time),
          // registering their own region refs so prev/next still lands (AC3/AC4).
          <StackedFileSurface
            blocks={blocks}
            regionRefs={regionRefs}
            summaryFirst
            lazyDiffEnabled={lazyDiffEnabled}
            workflowRunId={workflowRunId}
            artifactIdB={artifactIdB}
          />
        ) : (
          <div className="space-y-3" data-testid="compare-mobile-column">
            {blocks.map((block, index) => (
              <MobileColumnBlock
                key={`mobile-region-${index}`}
                block={block}
                index={index}
                side={side}
                registerRef={(node) => {
                  regionRefs.current[index] = node;
                }}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** One changed region in the mobile single-column layout — shows ONLY the toggled side (AC4/AC6). */
function MobileColumnBlock({
  block,
  index,
  side,
  registerRef,
}: {
  block: ChangeBlockView;
  index: number;
  side: 'before' | 'after';
  registerRef: (node: HTMLElement | null) => void;
}) {
  const sig = changeKindSignifier(block.changeKind);
  return (
    <div
      ref={registerRef}
      tabIndex={-1}
      data-testid={`compare-mobile-region-${index}`}
      data-compare-region-index={index}
      data-change-kind={block.changeKind}
      data-block-kind={block.kind}
      className="rounded-md border border-border p-2 outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
    >
      <div className="mb-1 flex items-center gap-2 text-sm font-medium text-text-primary">
        <span
          className="shrink-0 rounded-sm border border-border px-1 font-mono text-annotation"
          aria-hidden
        >
          {sig.symbol}
        </span>
        <span className="shrink-0 text-annotation uppercase tracking-wide text-text-tertiary">
          {sig.label}
        </span>
        <span className="min-w-0 truncate">{blockLabel(block)}</span>
      </div>
      <div data-testid={`compare-mobile-region-content-${index}`} data-compare-side={side}>
        {block.kind === 'markdown' ? (
          <MarkdownCell
            text={side === 'before' ? block.priorText : block.currentText}
            empty={
              side === 'before'
                ? 'Not present in the prior revision.'
                : 'Removed in the current revision.'
            }
          />
        ) : block.kind === 'planStep' ? (
          <PlanStepCell
            text={side === 'before' ? block.priorStepText : block.currentStepText}
            order={side === 'before' ? block.priorStepOrder : block.currentStepOrder}
            empty={
              side === 'before'
                ? 'Not present in the prior revision.'
                : 'Removed in the current revision.'
            }
          />
        ) : (
          // A file block is routed to StackedFileSurface, not here; degrade safely if one appears.
          <p className="text-meta text-text-tertiary">{blockLabel(block)}</p>
        )}
      </div>
    </div>
  );
}
