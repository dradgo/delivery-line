/**
 * Story 4.20 (AC1) — the Compare-Mode data hook.
 *
 * A NET-NEW TanStack Query hook over the EXISTING story-4.19 endpoint
 * (`GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}` → `RevisionDelta`), which is
 * already live in the generated `schema.d.ts` (`operations.compareArtifacts`) — this story does
 * NOT regenerate or re-add any of that contract.
 *
 * A/B direction is FIXED by 4.19: `artifactIdA` = baseline/prior, `artifactIdB` = target/current;
 * `changeKind` is computed B-relative-to-A. The pair is part of the query key verbatim
 * (`workflowKeys.revisionDelta(a, b)`, a sibling of `artifact(id)` off `all` — a compare spans an
 * artifact lineage independent of any single run, so it is NOT a child of `detail(runId)`).
 *
 * Read-only + idempotent → NO Idempotency-Key (mirrors `useAllowedActions`). The query is
 * DISABLED until BOTH ids are non-empty, so the Compare overlay can mount (and render the
 * "no baseline available" state) before a prior-version id is resolvable (OQ-2).
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

/** The generated revision-delta wire shape (story 4.19 — DO NOT regenerate). */
export type RevisionDelta = components['schemas']['RevisionDelta'];

/** GET the typed revision delta for an ordered (prior, current) artifact-id pair. */
async function fetchRevisionDelta(
  artifactIdA: string,
  artifactIdB: string,
): Promise<RevisionDelta> {
  return unwrap(
    await apiClient.GET('/api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}', {
      params: { path: { artifactIdA, artifactIdB } },
    }),
  );
}

/**
 * Read the typed revision delta between two artifact versions of one lineage.
 *
 * `enabled` only when BOTH ids are non-empty (mirror `useArtifact`), so an unresolved baseline
 * (OQ-2 — no prior-version id source today) leaves the query idle rather than firing a request
 * that would 400/404. `staleTime: STALE_TIME.detail` — a lineage compare turns over as new
 * revisions land, so it should not be cached indefinitely.
 */
export function useRevisionDelta(artifactIdA: string, artifactIdB: string) {
  return useQuery({
    queryKey: workflowKeys.revisionDelta(artifactIdA, artifactIdB),
    queryFn: () => fetchRevisionDelta(artifactIdA, artifactIdB),
    enabled: artifactIdA.length > 0 && artifactIdB.length > 0,
    staleTime: STALE_TIME.detail,
  });
}
