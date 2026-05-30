import { Link } from '@tanstack/react-router';

import { EmptyState, ErrorState } from '@/components/feedback';

/**
 * Story 2.5 — shared "this link goes nowhere good" UI states for the route tree.
 *
 * Lives under `src/routes/-states/`: the `-` ignore prefix (router default
 * `routeFileIgnorePrefix`) means the TanStack Router generator does NOT treat
 * these as routes — they are plain components the route files compose.
 *
 * Story 2.7 (TRAP 1): these states render INSIDE the tri-pane shell's central
 * main pane (Q4 — dead ends keep the shell frame, AC8 structural stability).
 *
 * CLOSED 2026-05-28 by story 2.22 — these states now compose the shared
 * `feedback/states` primitives (`<EmptyState>` / `<ErrorState>`) instead of a
 * local shell. Every original copy string is preserved verbatim (Trap T15); the
 * primitives supply the icon, semantic treatment, a11y baseline (role / aria-live
 * per AC9), and the typed next-safe-action.
 */

/** Brand link affordance reused across the empty-state actions. */
const QUEUE_LINK_CLASS =
  'text-meta text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2';

/** AC2 — the route param did not match the `run_`/`art_` public-id shape. */
export function InvalidLinkState() {
  return (
    <ErrorState
      variant="failedRetrieval"
      title="Invalid link"
      nextAction={{ kind: 'NavigateBack' }}
      message={
        <p>
          This link contains an identifier that isn&rsquo;t in the expected format, so it can&rsquo;t
          point to a real run or artifact. Check the URL — it may have been copied incompletely or
          corrupted in transit.
        </p>
      }
    />
  );
}

/** AC4 — backend reported no run for this (well-formed) id. Wired by 2.6/2.28. */
export function RunNotFoundState({ workflowRunId }: { workflowRunId?: string }) {
  return (
    <ErrorState
      variant="failedRetrieval"
      title="Run not found"
      nextAction={{ kind: 'NavigateBack' }}
      message={
        <p>
          No workflow run matches{' '}
          {workflowRunId !== undefined && workflowRunId !== '' ? (
            <code className="text-meta">{workflowRunId}</code>
          ) : (
            'this identifier'
          )}
          . It may have been removed under a retention policy, or the link may be out of date.
        </p>
      }
    />
  );
}

/** AC4 — backend reported no artifact for this (well-formed) id. Wired by 2.6/2.28. */
export function ArtifactNotFoundState({ artifactId }: { artifactId?: string }) {
  return (
    <ErrorState
      variant="failedRetrieval"
      title="Artifact not found"
      nextAction={{ kind: 'NavigateBack' }}
      message={
        <p>
          No artifact matches{' '}
          {artifactId !== undefined && artifactId !== '' ? (
            <code className="text-meta">{artifactId}</code>
          ) : (
            'this identifier'
          )}{' '}
          on this run. It may belong to a different run, or the link may be out of date.
        </p>
      }
    />
  );
}

/** AC1 — any URL that matched no route at all. */
export function PageNotFoundState() {
  return (
    <EmptyState
      variant="filtered"
      title="Page not found"
      message={
        <p>
          This address doesn&rsquo;t match any page in the review workspace. Use the run queue to find
          the run you&rsquo;re looking for.
        </p>
      }
      action={
        <Link to="/workflows" className={QUEUE_LINK_CLASS}>
          Go to run queue
        </Link>
      }
    />
  );
}

/** Generic error boundary — distinct from any not-found state. */
export function GenericErrorState() {
  return (
    <ErrorState
      variant="failedRetrieval"
      title="Something went wrong"
      urgency="active"
      nextAction={{ kind: 'Refresh', onRefresh: () => window.location.reload() }}
      message={
        <p>
          An unexpected error interrupted this view. Reloading usually clears it; if it keeps
          happening, the run may need operator attention.
        </p>
      }
    />
  );
}

/**
 * AC8 (UX-DR6) case (a) — a run reported in a workflow state this build does not
 * recognize. Render an explicit "newer state" panel rather than crashing.
 */
export function UnrecognizedRunStateState({ currentStage }: { currentStage?: string }) {
  return (
    <EmptyState
      variant="filtered"
      title="This run is in a newer state"
      message={
        <p>
          This run reports{' '}
          {currentStage !== undefined && currentStage !== '' ? (
            <code className="text-meta">{currentStage}</code>
          ) : (
            'a workflow state'
          )}{' '}
          that this version of the workspace doesn&rsquo;t know how to display yet. Updating to a
          newer build should resolve it. Nothing about the run has changed.
        </p>
      }
      action={
        <Link to="/workflows" className={QUEUE_LINK_CLASS}>
          Back to queue
        </Link>
      }
    />
  );
}

/**
 * AC8 (UX-DR6) case (b) — an artifact whose type the current Artifact Review Panel
 * cannot render yet (ARP variants land across 2.17 / 3.26 / 3.27).
 */
export function UnrenderableArtifactState({ artifactType }: { artifactType?: string }) {
  return (
    <EmptyState
      variant="filtered"
      title="This artifact type isn’t viewable here yet"
      message={
        <p>
          {artifactType !== undefined && artifactType !== '' ? (
            <>
              Artifacts of type <code className="text-meta">{artifactType}</code> aren&rsquo;t
            </>
          ) : (
            <>This artifact type isn&rsquo;t</>
          )}{' '}
          renderable in this build of the review panel. The viewer for it arrives in a later release.
        </p>
      }
    />
  );
}

/**
 * AC8 (UX-DR6) case (c) — a navigation the recorded actor role is not associated
 * with. RECORDED-role-aware DISPLAY only — the UI never enforces permissions
 * (architecture.md:519); the backend remains the authority (Trap T7).
 */
export function PermissionRestrictedState() {
  return (
    <ErrorState
      variant="permissionRestricted"
      title="Not available for your recorded role"
      urgency="passive"
      nextAction={{ kind: 'ContactSupport' }}
      message={
        <p>
          Your recorded role isn&rsquo;t associated with this view. This is an informational signal
          based on recorded role context — it isn&rsquo;t a security control, and access decisions are
          made by the backend, not here.
        </p>
      }
    />
  );
}
