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
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';

import {
  compareLayout,
  type ChangeBlockView,
  type ChangeKind,
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
}: CompareModeProps) {
  // AC7 — the "Show only changes" filter defaults ON where the diff is non-trivial.
  const [showOnlyChanges, setShowOnlyChanges] = useState(true);
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
  useEffect(() => {
    sectionRef.current?.focus();
  }, []);

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
      tabIndex={-1}
      className="w-full outline-none"
    >
      {/* ONE polite live region for the whole surface (Reconciliation 12). */}
      <p className="sr-only" role="status" aria-live="polite" data-testid="compare-announcer">
        {announcement}
      </p>

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
          {view !== undefined ? <CompareSummaryHeader view={view} showControls={false} /> : null}
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
                <div className="max-h-[32rem] overflow-y-auto" data-testid="compare-synced-scroll">
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
