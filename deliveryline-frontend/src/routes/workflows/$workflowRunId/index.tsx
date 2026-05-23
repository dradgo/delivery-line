import { Link, createFileRoute, notFound } from '@tanstack/react-router';

import { Stack } from '@/components/layout';
import { detailQueryOptions } from '@/lib/api/queryOptions';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import {
  InvalidRouteParamError,
  assertValidRunRouteParams,
} from '@/lib/routing/routeParamValidation';
import { useWorkflowDetail } from '@/features/workflows/hooks/useWorkflowDetail';
import {
  GenericErrorState,
  InvalidLinkState,
  RunNotFoundState,
  UnrecognizedRunStateState,
} from '../../-states/DeadEndState';

/**
 * WorkflowDetailRoute (`/workflows/$workflowRunId`).
 *
 * AC10 — DO NOT split this route per stage. The backend-reported state selects the
 * Artifact Review Panel variant INSIDE this route; Epic 3 adds stages by widening
 * the recognized set, never by forking the route tree.
 *
 * Story 2.6 replaced the story-2.5 typed stub with a real prefetch:
 *   • the loader warms the TanStack Query cache via
 *     `context.queryClient.ensureQueryData(detailQueryOptions(id))`, so a deep link
 *     renders without a loading flash (AC3) and the component's `useWorkflowDetail`
 *     reads the SAME cache entry (dedup / structural sharing, AC10);
 *   • a backend `RUN_NOT_FOUND` (typed `ProblemDetailsError`) becomes
 *     `throw notFound()` → `RunNotFoundState` (AC4 / the story-2.6/2.28 SEAM);
 *   • the X-Correlation-Id header rides every request automatically via the client
 *     middleware (story 2.6/1.19 SEAM) — no per-loader header code.
 */

/**
 * Workflow states this build recognizes (the backend `currentState` enum, from
 * 6.9's OpenAPI schema). AC8a: a run reported in a state OUTSIDE this set (e.g. a
 * future Epic-3+ state seen by an older build) renders the explicit "newer state"
 * panel rather than crashing.
 *
 * NOTE (story 2.6 deliberate deviation): story 2.5's stub guarded a `currentStage`
 * ∈ {spec, implementation-plan, pr-output} and a `viewerAuthorized` flag — neither
 * field exists on the real backend `WorkflowDetail` (it exposes `currentState`, and
 * reports no per-viewer authorization). Keeping those guards verbatim would be dead
 * code failing `no-unnecessary-condition` (max-warnings=0), so the AC8 unrecognized-
 * state guard is re-pointed at the real `currentState` enum — strictly more correct
 * against the live contract. The recorded-role `PermissionRestrictedState` defers to
 * the story that ships role context; it stays exported and reachable via that story.
 */
const RECOGNIZED_STATES = new Set([
  'Inbox',
  'Planned',
  'Investigating',
  'WaitingForSpecApproval',
  'Executing',
  'WaitingForReview',
  'Completed',
  'Failed',
  'Paused',
  'TakenOver',
  'Reconciled',
]);

export const Route = createFileRoute('/workflows/$workflowRunId/')({
  beforeLoad: ({ params }) => {
    // AC2 — reject malformed IDs at the route boundary so loaders never run for
    // impossible deep links.
    assertValidRunRouteParams(params.workflowRunId);
  },
  loader: async ({ context, params }) => {
    try {
      // AC3 — flash-free deep link: warm the cache the component reads.
      return await context.queryClient.ensureQueryData(detailQueryOptions(params.workflowRunId));
    } catch (error) {
      // AC4 — a well-formed id the backend has no run for → dedicated not-found state.
      if (isProblemDetailsError(error) && error.code === 'RUN_NOT_FOUND') {
        // `notFound()` returns a TanStack Router control-flow signal, not an Error;
        // throwing it is the documented router pattern for triggering notFoundComponent.
        // eslint-disable-next-line @typescript-eslint/only-throw-error
        throw notFound();
      }
      throw error;
    }
  },
  notFoundComponent: () => <RunNotFoundState />,
  errorComponent: ({ error }) =>
    error instanceof InvalidRouteParamError ? <InvalidLinkState /> : <GenericErrorState />,
  component: WorkflowDetailRoute,
});

function WorkflowDetailRoute() {
  const { workflowRunId } = Route.useParams();
  // Reads the cache the loader already warmed (AC10 — one shared entry).
  const { data } = useWorkflowDetail(workflowRunId);

  // AC8a — a run reported in a state this build doesn't recognize.
  if (data?.currentState !== undefined && !RECOGNIZED_STATES.has(data.currentState)) {
    return <UnrecognizedRunStateState currentStage={data.currentState} />;
  }

  return (
    <Stack gap="4" className="items-start">
      <Link
        to="/workflows"
        className="text-meta text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
      >
        &larr; Back to queue
      </Link>
      <h1 className="text-page-title">Workflow run</h1>
      <p className="text-meta text-text-tertiary">
        <code>{workflowRunId}</code>
        {data?.currentState !== undefined ? (
          <>
            {' '}
            &middot; state <code>{data.currentState}</code>
          </>
        ) : null}
      </p>
      <p className="text-body text-text-secondary max-w-prose">
        Navigation skeleton (story 2.5) now backed by the live data layer (story 2.6) and hosted in
        the tri-pane shell (story 2.7). The Run Context Strip (2.16) and the Artifact Review Panel
        (2.17) render here once they land.
      </p>
      <Link
        to="/workflows/$workflowRunId/artifacts/$artifactId"
        params={{ workflowRunId, artifactId: 'art_sample0001' }}
        className="text-body text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
      >
        Open a sample artifact &rarr;
      </Link>
    </Stack>
  );
}
