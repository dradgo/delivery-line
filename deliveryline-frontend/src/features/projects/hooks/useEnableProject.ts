/**
 * Story 3c-9 (Task 4, AC7) — the live `useEnableProject` mutation.
 *
 * `POST /api/v1/projects/{projectId}/enable` (no body). Advertised only by a
 * `disabled` project's `allowedActions`. On success the list + this project's detail
 * are invalidated. Typed failure: `PROJECT_NOT_FOUND` (404).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import type { Project } from '@/lib/api/queryOptions';

import { useProjectMutation, type ProjectMutationResult } from './useProjectMutation';

/** Enable takes no payload — the project id is carried by the hook. */
export type EnableProjectVariables = Record<string, never>;

export type EnableProjectResult = ProjectMutationResult<Project, EnableProjectVariables>;

/** Build the live enable-project mutation for an existing project. */
export function useEnableProject(projectId: string): EnableProjectResult {
  return useProjectMutation<EnableProjectVariables, Project>({
    projectId,
    mutationFn: async () =>
      unwrap(
        await apiClient.POST('/api/v1/projects/{projectId}/enable', {
          params: { path: { projectId } },
        }),
      ),
  });
}
