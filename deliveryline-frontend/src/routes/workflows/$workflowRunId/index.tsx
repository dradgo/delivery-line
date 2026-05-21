import { Link, createFileRoute } from '@tanstack/react-router';

import { Container, Stack } from '@/components/layout';
import {
  InvalidRouteParamError,
  assertValidRunRouteParams,
} from '@/lib/routing/routeParamValidation';
import {
  GenericErrorState,
  InvalidLinkState,
  PermissionRestrictedState,
  RunNotFoundState,
  UnrecognizedRunStateState,
} from '../../-states/DeadEndState';

/**
 * Story 2.5 — WorkflowDetailRoute (`/workflows/$workflowRunId`).
 *
 * AC10 — DO NOT split this route per stage. The backend-reported `currentStage`
 * (spec / implementation-plan / pr-output) selects the Artifact Review Panel
 * variant INSIDE this route; Epic 3 adds stages by widening the recognized set,
 * never by forking the route tree. Compare/clarification are bounded states
 * inside the run (ux:1836), not separate routes.
 */

/**
 * Typed stub the loader returns until 2.6. `currentStage` / `viewerAuthorized` are
 * typed as the loose backend-reported shapes (string / boolean) ON PURPOSE so the
 * AC8 guards below are genuinely necessary (a narrower union would make them
 * statically dead and trip `no-unnecessary-condition`).
 */
interface WorkflowDetailStub {
  workflowRunId: string;
  /** Backend-reported workflow stage; an Epic-3+ value may be unknown here (AC8a). */
  currentStage: string;
  /** Recorded-role visibility (AC8c) — DISPLAY only; the UI never enforces (architecture.md:519). */
  viewerAuthorized: boolean;
}

/** Stages this build's Artifact Review Panel can drive a variant for (AC10). */
const RECOGNIZED_STAGES = new Set(['spec', 'implementation-plan', 'pr-output']);

export const Route = createFileRoute('/workflows/$workflowRunId/')({
  beforeLoad: ({ params }) => {
    // AC2 — reject malformed IDs at the route boundary so loaders never run for
    // impossible deep links.
    assertValidRunRouteParams(params.workflowRunId);
  },
  loader: ({ params }): WorkflowDetailStub => {
    // SEAM (story 2.6): replace this typed stub with the real prefetch —
    //   return queryClient.ensureQueryData(workflowKeys.detail(params.workflowRunId))
    // so a deep-linked detail renders without a loading flash (AC3).
    // SEAM (story 2.6/1.19): attach the X-Correlation-Id header on that request
    //   (see src/lib/api/correlation.ts) so server logs trace back to this nav (AC9).
    // SEAM (story 2.6/2.28): a backend 404 here becomes `throw notFound()` →
    //   RunNotFoundState (AC4); the not-found wiring lands with the data layer.
    return {
      workflowRunId: params.workflowRunId,
      currentStage: 'spec',
      viewerAuthorized: true,
    };
  },
  // AC4 — when the 2.6 loader does `throw notFound()` on a backend 404, this
  // dedicated state renders (distinct from the malformed-link and generic-error
  // states). Wired now; the throw lands with the data layer. Wrapped so the
  // component's optional `workflowRunId` prop doesn't clash with NotFoundRouteProps.
  notFoundComponent: () => <RunNotFoundState />,
  errorComponent: ({ error }) =>
    error instanceof InvalidRouteParamError ? <InvalidLinkState /> : <GenericErrorState />,
  component: WorkflowDetailRoute,
});

function WorkflowDetailRoute() {
  const { workflowRunId } = Route.useParams();
  const data = Route.useLoaderData();

  // AC8c — recorded-role-aware display (never an enforcement gate).
  if (!data.viewerAuthorized) {
    return <PermissionRestrictedState />;
  }

  // AC8a — a run reported in a state this build doesn't recognize.
  if (!RECOGNIZED_STAGES.has(data.currentStage)) {
    return <UnrecognizedRunStateState currentStage={data.currentStage} />;
  }

  return (
    <Container className="py-8">
      <Stack gap="4" className="items-start">
        <Link
          to="/workflows"
          className="text-meta text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
        >
          &larr; Back to queue
        </Link>
        <h1 className="text-page-title">Workflow run</h1>
        <p className="text-meta text-text-tertiary">
          <code>{workflowRunId}</code> &middot; stage <code>{data.currentStage}</code>
        </p>
        <p className="text-body text-text-secondary max-w-prose">
          Navigation skeleton (story 2.5). The Run Context Strip (2.16) and the Artifact Review
          Panel (2.17) — whose variant the <code>currentStage</code> above selects (AC10) — render
          here once they and the data layer (2.6) land.
        </p>
        <Link
          to="/workflows/$workflowRunId/artifacts/$artifactId"
          params={{ workflowRunId, artifactId: 'art_sample0001' }}
          className="text-body text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
        >
          Open a sample artifact &rarr;
        </Link>
      </Stack>
    </Container>
  );
}
