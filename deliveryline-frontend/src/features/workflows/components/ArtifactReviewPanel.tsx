/**
 * Story 2.17 — the generalized `ArtifactReviewPanel` composite.
 *
 * Designed with artifact-type polymorphism from day one (party-mode finding #3):
 * it dispatches on a resolved `ArtifactView` discriminated union (`artifactType`,
 * the runner-contracts schema-v1 values per story 1.6 AC4) to a per-variant
 * renderer — `spec` fully rendered (Epic 2), `implementationPlan` + `prOutput`
 * stub-rendered (Epic 3), unknown → a safe "unsupported artifact type" fallback
 * (never crashes — mirrors the route's `UnrenderableArtifactState` defensiveness).
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): there is NO backend `ArtifactView` and NO
 * artifact-read endpoint; `useArtifact` is a disabled stub. So:
 *   • {@link ArtifactReviewPanel} is PRESENTATIONAL — it takes a resolved state +
 *     artifact and renders; tests drive it directly with fixtures (router/query-free).
 *   • {@link ArtifactReviewPanelContainer} is the thin data seam — it reads the
 *     disabled `useArtifact` / `useAllowedActions` hooks and maps them to the panel
 *     props + owns the structured logging. Live today: `loading → empty` (the disabled
 *     stub never resolves) and `error`; enabling the hook later flips `empty → default`
 *     with ZERO panel changes.
 *
 * AC6 primacy: the panel renders into the AppShell `<main>` (which carries the real
 * `min-w-[36rem]` floor and never auto-collapses). The panel itself sets NO min-width
 * below that floor and NO auto-collapse class — section nav scrolls WITHIN the panel.
 */
import { useEffect, type ReactNode } from 'react';
import { TriangleAlert } from 'lucide-react';

import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState, ErrorState } from '@/components/feedback';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { cn } from '@/lib/utils';

import {
  canEnableCompare,
  hasComparableRevision,
  isArtifactView,
  resolveArtifactPanelState,
  type ArtifactPanelState,
  type ArtifactView,
} from '../artifactView';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useArtifact } from '../hooks/useArtifact';
import { ImplementationPlanArtifactRenderer } from './ImplementationPlanArtifactRenderer';
import { PrOutputArtifactRenderer } from './PrOutputArtifactRenderer';
import { SpecArtifactRenderer } from './SpecArtifactRenderer';

export interface ArtifactReviewPanelProps {
  /** The resolved panel state (see {@link resolveArtifactPanelState}). */
  state: ArtifactPanelState;
  /** The resolved artifact — present for `default`/`stale`/`superseded`/`incomplete`. */
  artifact?: ArtifactView | undefined;
  /** AC9 — whether the Compare-Mode entry is enabled (always the safe default `false` live). */
  compareEnabled?: boolean;
  /** AC4 — the error-state Retry handler. */
  onRetry?: (() => void) | undefined;
}

/** The unknown-discriminant safe fallback (AC3) — never crashes on a future type. */
function UnsupportedArtifact({ artifactType }: { artifactType: string }) {
  return (
    <div data-testid="unsupported-artifact" className="text-body text-text-secondary">
      <h2 className="text-section-heading">Unsupported artifact type</h2>
      <p className="text-meta text-text-tertiary">
        This build can’t render artifacts of type <code>{artifactType}</code> yet.
      </p>
    </div>
  );
}

/** Dispatch a resolved artifact to its per-variant renderer (AC1, AC3). */
function renderVariant(artifact: ArtifactView, compareEnabled: boolean): ReactNode {
  switch (artifact.artifactType) {
    case 'spec':
      return <SpecArtifactRenderer artifact={artifact} compareEnabled={compareEnabled} />;
    case 'implementationPlan':
      return <ImplementationPlanArtifactRenderer artifact={artifact} />;
    case 'prOutput':
      return <PrOutputArtifactRenderer artifact={artifact} />;
    default:
      // A discriminant value this build doesn't know (a future type). The union is
      // closed in TS, but the runtime guard is the artifact-level twin of the route's
      // `RENDERABLE_ARTIFACT_TYPES` guard (T9) — defend, never crash.
      return <UnsupportedArtifact artifactType={(artifact as ArtifactView).artifactType} />;
  }
}

/** A non-default-status banner (stale / superseded / incomplete) with an optional action. */
function StatusBanner({
  testId,
  tone,
  title,
  message,
  action,
}: {
  testId: string;
  tone: 'warning' | 'error';
  title: string;
  message: string;
  action?: ReactNode;
}) {
  return (
    <div
      role="status"
      data-testid={testId}
      className={cn(
        'mb-3 rounded-md border px-3 py-2 text-sm',
        tone === 'error'
          ? 'border-state-error-border bg-state-error text-state-error-foreground'
          : 'border-state-warning-border bg-state-warning text-state-warning-foreground',
      )}
    >
      <div className="flex items-start gap-2">
        <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
        <div>
          <p className="font-medium">{title}</p>
          <p className="text-text-secondary">{message}</p>
        </div>
      </div>
      {action !== undefined ? <div className="mt-2">{action}</div> : null}
    </div>
  );
}

/** "View latest" affordance for stale/superseded states (AC4). No target wired in E2 (dormant). */
function ViewLatestAction() {
  return (
    <button
      type="button"
      disabled
      title="Latest-artifact navigation is available in a later release"
      className="rounded-md border border-border px-2.5 py-1 text-sm font-medium text-text-primary hover:bg-surface-elevated focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
      data-testid="artifact-view-latest"
    >
      View latest
    </button>
  );
}

/**
 * The PRESENTATIONAL panel (OQ-1). Renders exactly one state via
 * `data-artifact-panel-state`; for the content states it dispatches the artifact to
 * its variant renderer (optionally behind a stale/superseded/incomplete banner).
 */
export function ArtifactReviewPanel({
  state,
  artifact,
  compareEnabled = false,
  onRetry,
}: ArtifactReviewPanelProps) {
  return (
    <section
      aria-label="Artifact review"
      data-artifact-panel-state={state}
      // AC6 — full width within `<main>`; NO min-width below the 36rem floor and NO
      // auto-collapse class (the main pane owns primacy; the panel never yields width).
      className="w-full"
    >
      {state === 'loading' ? (
        <div className="space-y-3" data-testid="artifact-panel-loading">
          <Skeleton className="h-6 w-48" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-11/12" />
          <Skeleton className="h-4 w-10/12" />
          <Skeleton className="h-32 w-full" />
        </div>
      ) : null}

      {state === 'error' ? (
        <ErrorState
          variant="failedRetrieval"
          urgency="passive"
          message="We couldn’t load this artifact."
          nextAction={{ kind: 'Retry', onRetry: onRetry ?? (() => undefined) }}
        />
      ) : null}

      {state === 'empty' ? (
        <EmptyState
          variant="artifactNotGenerated"
          message="Specification not yet available. It appears here once the run produces it."
        />
      ) : null}

      {artifact !== undefined &&
      (state === 'default' ||
        state === 'stale' ||
        state === 'superseded' ||
        state === 'incomplete') ? (
        <>
          {state === 'stale' ? (
            <StatusBanner
              testId="artifact-stale-banner"
              tone="warning"
              title="A newer version exists"
              message="This artifact has been superseded by a newer revision."
              action={<ViewLatestAction />}
            />
          ) : null}
          {state === 'superseded' ? (
            <StatusBanner
              testId="artifact-superseded-banner"
              tone="error"
              title="This artifact is conflicting / superseded"
              message="The backend marked this artifact conflicting. Review the latest revision before acting."
              action={<ViewLatestAction />}
            />
          ) : null}
          {state === 'incomplete' ? (
            <StatusBanner
              testId="artifact-incomplete-banner"
              tone="warning"
              title="Incomplete artifact"
              message="This artifact’s content is partial (truncated). Some sections may be missing."
            />
          ) : null}
          {renderVariant(artifact, compareEnabled)}
        </>
      ) : null}
    </section>
  );
}

export interface ArtifactReviewPanelContainerProps {
  workflowRunId: string;
  artifactId: string;
}

/**
 * The thin data CONTAINER (OQ-1 / Task 4). Reads the disabled `useArtifact` /
 * `useAllowedActions` stubs, maps them to the presentational panel's props, and owns
 * the structured logging. Keeping it separate from the presentational panel lets the
 * panel be tested router/query-free with fixtures (mirrors `RunContextStrip`).
 *
 * T3 — the hooks stay DISABLED. The disabled `useArtifact` is idle (not fetching) with
 * no data → `isLoading=false`, `artifact=undefined` → `empty`. When the artifact-read
 * story enables the hook + endpoint, the loader-warmed cache flips this to `default`
 * with no change here.
 */
export function ArtifactReviewPanelContainer({
  workflowRunId,
  artifactId,
}: ArtifactReviewPanelContainerProps) {
  const artifactQuery = useArtifact(workflowRunId, artifactId);
  const allowedActionsQuery = useAllowedActions(workflowRunId);

  // The disabled stub returns `data: never | undefined` — always `undefined` at
  // runtime today. When the hook becomes live, runtime-guard the future read-model
  // shape instead of trusting a cast from query data.
  const rawArtifact: unknown = artifactQuery.data;
  const hasInvalidArtifact = rawArtifact !== undefined && !isArtifactView(rawArtifact);
  const artifact = isArtifactView(rawArtifact) ? rawArtifact : undefined;
  const state = resolveArtifactPanelState({
    isError: artifactQuery.isError || hasInvalidArtifact,
    // Initial fetch only. If a future live query refetches with data already present,
    // preserve the readable artifact instead of replacing it with skeleton rows.
    isLoading: artifact === undefined && artifactQuery.isFetching,
    artifact,
  });

  const actions = (allowedActionsQuery.data as { actions?: readonly string[] } | undefined)
    ?.actions;
  const compareEnabled = canEnableCompare(actions, hasComparableRevision(artifact));

  // Task 6 — field-only structured logs (mirror QueueShell / RunContextStrip). NEVER
  // the raw error message / artifact body (T8): only the stable ProblemDetails `code`
  // + a transport flag.
  useEffect(() => {
    if (state !== 'error') {
      return;
    }
    const error = hasInvalidArtifact ? undefined : artifactQuery.error;
    console.warn({
      event: 'artifactPanel.loadError',
      code: isProblemDetailsError(error) ? error.code : 'transport',
      transport: !isProblemDetailsError(error),
    });
  }, [state, artifactQuery.error, hasInvalidArtifact]);

  useEffect(() => {
    if (state !== 'stale') {
      return;
    }
    console.warn({ event: 'artifactPanel.stale' });
  }, [state]);

  const handleRetry = () => {
    console.info({ event: 'artifactPanel.retry' });
    void artifactQuery.refetch();
  };

  return (
    <ArtifactReviewPanel
      state={state}
      artifact={artifact}
      compareEnabled={compareEnabled}
      onRetry={handleRetry}
    />
  );
}
