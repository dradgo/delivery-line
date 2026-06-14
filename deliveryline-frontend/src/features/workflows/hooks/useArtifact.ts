/**
 * Story 3a-9 (Gate 3 / AC6) — typed artifact-read hook, now LIVE.
 *
 * Replaces the story-2.6 disabled stub: the artifact-read endpoint
 * (`GET /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}`) ships with this
 * story, so the hook fetches through `artifactQueryOptions` (which adapts the raw
 * `ArtifactDetail` DTO into the frontend-owned `ArtifactView` — D1). The query key
 * stays `workflowKeys.artifact(artifactId)` (one arg — artifact public ids are
 * globally unique), the stable contract reserved by story 2.6 AC3.
 *
 * D2 — the endpoint path needs BOTH `workflowRunId` and `artifactId`, so the signature
 * gained `workflowRunId`. The query is disabled until both ids are present so the
 * detail route can render the panel before a spec artifact exists.
 */
import { useQuery } from '@tanstack/react-query';

import { artifactQueryOptions } from '@/lib/api/queryOptions';

export function useArtifact(workflowRunId: string, artifactId: string) {
  return useQuery({
    ...artifactQueryOptions(workflowRunId, artifactId),
    enabled: workflowRunId.length > 0 && artifactId.length > 0,
  });
}
