import { Link, createFileRoute, notFound } from '@tanstack/react-router';

import { Stack } from '@/components/layout';
import { detailQueryOptions } from '@/lib/api/queryOptions';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { isValidClarificationId } from '@/lib/routing/publicId';
import {
  InvalidRouteParamError,
  assertValidRunRouteParams,
} from '@/lib/routing/routeParamValidation';
import { useWorkflowDetail } from '@/features/workflows/hooks/useWorkflowDetail';
import { resolveSpecArtifactId } from '@/features/workflows/approvalDecisionView';
import { RunContextStrip } from '@/features/workflows/components/RunContextStrip';
import { ContextPanelSlot } from '@/features/workflows/ContextPanelSlot';
import { ClarificationRegionContainer } from '@/features/workflows/components/ClarificationRegionContainer';
import { WorkflowDecisionBar } from '@/features/workflows/components/WorkflowDecisionBar';
import { FailureEventSurface } from '@/features/workflows/components/FailureEventSurface';
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

/**
 * Story 2.22 (AC1) — typed `?clarificationId=cla_xxx` deep-link search param.
 * `useNavigateToClarification` writes it; the Clarification Region (story 2.18)
 * reads it. Validated at the route boundary via `isValidClarificationId`, mirroring
 * the `beforeLoad` param defense — a malformed value is dropped, not surfaced.
 */
interface WorkflowDetailSearch {
  clarificationId?: string;
}

export const Route = createFileRoute('/workflows/$workflowRunId/')({
  validateSearch: (search: Record<string, unknown>): WorkflowDetailSearch =>
    typeof search.clarificationId === 'string' && isValidClarificationId(search.clarificationId)
      ? { clarificationId: search.clarificationId }
      : {},
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
  // Story 2.22 — the validated `?clarificationId` deep-link the Clarification Region reads.
  const { clarificationId } = Route.useSearch();
  // Reads the cache the loader already warmed (AC10 — one shared entry).
  const { data } = useWorkflowDetail(workflowRunId);
  // Story 3a-9 (AC6) — the real spec artifact id from the read model (story 2.19 resolver);
  // `undefined` until the run produces a spec artifact, which hides the artifact link.
  const specArtifactId = resolveSpecArtifactId(data);

  // AC8a — a run reported in a state this build doesn't recognize.
  if (data?.currentState !== undefined && !RECOGNIZED_STATES.has(data.currentState)) {
    return <UnrecognizedRunStateState currentStage={data.currentState} />;
  }

  return (
    <Stack gap="4" className="items-start">
      {/* Story 2.16 — persistent run-context orientation strip, above the (future
          2.17) artifact body. Reads the same warmed `useWorkflowDetail` cache. */}
      <RunContextStrip workflowRunId={workflowRunId} />
      {/* Story 3.30 (AC1/AC6) — the minimal failure-event surface + diagnostics panel.
          Renders nothing unless the run's event stream carries failure / recovery
          events (scope discipline — Epic 4 owns the full timeline). */}
      <FailureEventSurface workflowRunId={workflowRunId} />
      {/* Story 2.18 — the Clarification Region, projected into the right context
          panel via the AppShell slot (sidebar subregion, AC4). The main pane stays
          artifact-primary; today the region renders the calm `no open questions`
          empty state (the disabled `useClarifications` stub maps to an empty view). */}
      <ContextPanelSlot>
        <ClarificationRegionContainer
          workflowRunId={workflowRunId}
          clarificationId={clarificationId}
        />
      </ContextPanelSlot>
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
        the tri-pane shell (story 2.7). The Artifact Review Panel (story 2.17) renders in the
        artifact-viewer route — open it via the link below.
      </p>
      {specArtifactId !== undefined ? (
        <Link
          to="/workflows/$workflowRunId/artifacts/$artifactId"
          params={{ workflowRunId, artifactId: specArtifactId }}
          className="text-body text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
        >
          Open the specification &rarr;
        </Link>
      ) : null}
      {/* Decision Bar (AC4 sticky footer). Story 3.30 makes the mode state-driven: a
          `Failed` run gets the `recovery_operator` bar (the "Retry failed step" action);
          every other state gets the story-2.19 `spec_approval` bar. The selector owns the
          retry mutation so the recovery bar survives the post-retry Failed→Executing flip
          (success panel + AC7 announcement) — see `WorkflowDecisionBar`. */}
      <WorkflowDecisionBar workflowRunId={workflowRunId} />
    </Stack>
  );
}
