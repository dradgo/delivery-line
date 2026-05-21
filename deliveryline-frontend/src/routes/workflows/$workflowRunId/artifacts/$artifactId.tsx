import { Link, createFileRoute } from '@tanstack/react-router';

import { Container, Stack } from '@/components/layout';
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
 * not nested inside it — AC1 lists them as distinct routes. Minimal placeholder;
 * the Artifact Review Panel variants land in 2.17 / 3.26 / 3.27.
 */

/** Typed loader stub until 2.6. `artifactType` is loose (string) so the AC8b guard is necessary. */
interface ArtifactViewerStub {
  workflowRunId: string;
  artifactId: string;
  /** Backend-reported artifact type; a future type may be unknown to this build (AC8b). */
  artifactType: string;
}

/** Artifact types this build's review panel can render today (spec variant only in Epic 2). */
const RENDERABLE_ARTIFACT_TYPES = new Set(['spec']);

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

  // AC8b — an artifact type the current Artifact Review Panel can't render yet.
  if (!RENDERABLE_ARTIFACT_TYPES.has(data.artifactType)) {
    return <UnrenderableArtifactState artifactType={data.artifactType} />;
  }

  return (
    <Container className="py-8">
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
          <code>{artifactId}</code> &middot; type <code>{data.artifactType}</code> &middot; run{' '}
          <code>{workflowRunId}</code>
        </p>
        <p className="text-body text-text-secondary max-w-prose">
          Navigation skeleton (story 2.5). The Artifact Review Panel (2.17) renders the artifact
          body here once it and the data layer (2.6) land.
        </p>
      </Stack>
    </Container>
  );
}
