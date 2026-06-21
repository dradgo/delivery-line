/**
 * Story 3c-9 (Task 4, AC5) — the live `useTestProjectConnection` mutation.
 *
 * `POST /api/v1/projects/{projectId}/test-connection` runs a LIVE probe and returns
 * transient per-check results (`TestConnection`). It is NOT idempotent (re-running it
 * is the point), so it does NOT ride the `useProjectMutation` factory — it mints no
 * key and invalidates nothing. The result is held by the caller in component state
 * (R4 — the backend persists no test history).
 *
 * A per-check `fail`/`skipped` is IN-BAND data (HTTP 200), not an error. Only
 * `PROJECT_NOT_FOUND` (404) / `UNSUPPORTED_CONNECTOR_KIND` (400) surface as typed
 * `ProblemDetailsError`s.
 */
import { useMutation } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import type { components } from '@/lib/api/schema';
import { projectKeys } from '@/lib/queryKeys/projectKeys';

export type TestConnection = components['schemas']['TestConnection'];

/** Build the live connection-test probe for a project. */
export function useTestProjectConnection(projectId: string) {
  return useMutation<TestConnection, unknown, void>({
    // Factory-backed key (no-inline-query-keys) — namespaced under this project.
    mutationKey: projectKeys.detail(projectId),
    mutationFn: async () =>
      unwrap(
        await apiClient.POST('/api/v1/projects/{projectId}/test-connection', {
          params: { path: { projectId } },
        }),
      ),
  });
}
