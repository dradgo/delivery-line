import { Link, createFileRoute } from '@tanstack/react-router';

import { Stack } from '@/components/layout';
import { ArtifactReviewPanelContainer } from '@/features/workflows/components/ArtifactReviewPanel';
import { WorkflowDecisionBar } from '@/features/workflows/components/WorkflowDecisionBar';
import { useArtifact } from '@/features/workflows/hooks/useArtifact';
import {
  InvalidRouteParamError,
  assertValidArtifactRouteParams,
} from '@/lib/routing/routeParamValidation';
import {
  ArtifactNotFoundState,
  GenericErrorState,
  InvalidLinkState,
  UnrenderableArtifactState,
} from '../../../-states/DeadEndState';

/**
 * Story 2.5 — ArtifactViewerRoute
 * (`/workflows/$workflowRunId/artifacts/$artifactId`).
 *
 * A full route alongside the detail route (sharing the `$workflowRunId` param),
 * not nested inside it — AC1 lists them as distinct routes.
 *
 * Story 2.17 — the `ArtifactReviewPanel` now mounts here (its thin container reads
 * the disabled `useArtifact` stub → renders `empty/not-yet-generated` today; flips to
 * the rendered artifact when the artifact-read story enables the hook). The route is
 * NOT forked per stage (epic AC10) — the panel's internal `artifactType` dispatch
 * selects the variant. The route-level `RENDERABLE_ARTIFACT_TYPES`/
 * `UnrenderableArtifactState` AC8b guard is KEPT below: it guards backend-reported
 * types this build can't render at all; the panel's dispatch is the artifact-level twin.
 */

/** Typed loader stub until 2.6. `artifactType` is loose (string) so the AC8b guard is necessary. */
interface ArtifactViewerStub {
  workflowRunId: string;
  artifactId: string;
  /** Backend-reported artifact type; a future type may be unknown to this build (AC8b). */
  artifactType: string;
}

/** Artifact types this build's review panel can render today (spec full, Epic-3 variants stubbed). */
const RENDERABLE_ARTIFACT_TYPES = new Set(['spec', 'implementationPlan', 'prOutput']);

export const Route = createFileRoute('/workflows/$workflowRunId/artifacts/$artifactId')({
  beforeLoad: ({ params }) => {
    // AC2 — reject malformed IDs at the route boundary so loaders never run for
    // impossible deep links.
    assertValidArtifactRouteParams(params.workflowRunId, params.artifactId);
  },
  loader: ({ params }): ArtifactViewerStub => {
    // SEAM (story 2.6): replace with queryClient.ensureQueryData(
    //   workflowKeys.artifact(params.workflowRunId, params.artifactId)) (AC3),
    //   attaching the X-Correlation-Id header (AC9, src/lib/api/correlation.ts).
    // SEAM (story 2.6/2.28): backend 404 → `throw notFound()` → ArtifactNotFoundState (AC4).
    return {
      workflowRunId: params.workflowRunId,
      artifactId: params.artifactId,
      artifactType: 'spec',
    };
  },
  // AC4 — backend 404 (via the 2.6 loader's `throw notFound()`) renders this
  // dedicated state, distinct from the malformed-link and generic-error states.
  // Wrapped so the component's optional prop doesn't clash with NotFoundRouteProps.
  notFoundComponent: () => <ArtifactNotFoundState />,
  errorComponent: ({ error }) =>
    error instanceof InvalidRouteParamError ? <InvalidLinkState /> : <GenericErrorState />,
  component: ArtifactViewerRoute,
});

function ArtifactViewerRoute() {
  const { workflowRunId, artifactId } = Route.useParams();
  const data = Route.useLoaderData();

  // The LIVE artifact type (story 3a-9 enabled `useArtifact`). This shares the cache key
  // with the panel's own read (deduped — no second request), so the route can drive the
  // subtitle + the AC8b guard from real data instead of the story-2.6 `spec` loader stub.
  // Falls back to the loader stub until the read resolves.
  const { data: artifact } = useArtifact(workflowRunId, artifactId);
  const artifactType = artifact?.artifactType ?? data.artifactType;

  // AC8b — an artifact type the current Artifact Review Panel can't render yet.
  if (!RENDERABLE_ARTIFACT_TYPES.has(artifactType)) {
    return <UnrenderableArtifactState artifactType={artifactType} />;
  }

  return (
    <Stack gap="4" className="items-start">
      <Link
        to="/workflows/$workflowRunId"
        params={{ workflowRunId }}
        className="text-meta text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
      >
        &larr; Back to run
      </Link>
      <h1 className="text-page-title">Artifact</h1>
      <p className="text-meta text-text-tertiary">
        <code>{artifactId}</code> &middot; type <code>{artifactType}</code> &middot; run{' '}
        <code>{workflowRunId}</code>
      </p>
      {/* Story 2.17 — the generalized Artifact Review Panel. The container reads the
          disabled `useArtifact` stub, so it renders the `empty/not-yet-generated`
          state today; it flips to the rendered artifact with no route change when the
          artifact-read story enables the hook + endpoint. */}
      <ArtifactReviewPanelContainer workflowRunId={workflowRunId} artifactId={artifactId} />
      {/* The in-flow decision bar, rendered beneath the Artifact Review Panel. The
          state-driven `WorkflowDecisionBar` selector (the same one the run-detail pane
          uses) picks the right mode for the run's current state — `implementation_review`
          (accept / reject / take over) at `WaitingForReview`, `recovery_operator` at
          `Failed`, `spec_approval` otherwise — rather than always mounting the spec bar
          (which read "No decision available" for an implementation-plan artifact). */}
      <WorkflowDecisionBar workflowRunId={workflowRunId} layout="inline_section" />
    </Stack>
  );
}
