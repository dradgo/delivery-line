/**
 * Story 3c-9 (Task 4, AC7) — the live `useDisableProject` mutation.
 *
 * `POST /api/v1/projects/{projectId}/disable` (no body, no headers beyond the
 * middleware fallbacks). The `default` project never advertises `disable` (its
 * `allowedActions` omits it), so this is only reachable when the backend permits it;
 * a forbidden attempt still surfaces `INVALID_COMMAND_PAYLOAD` (400) defensively.
 * On success the list + this project's detail are invalidated.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import type { Project } from '@/lib/api/queryOptions';

import { useProjectMutation, type ProjectMutationResult } from './useProjectMutation';

/** Disable takes no payload — the project id is carried by the hook. */
export type DisableProjectVariables = Record<string, never>;

export type DisableProjectResult = ProjectMutationResult<Project, DisableProjectVariables>;

/** Build the live disable-project mutation for an existing project. */
export function useDisableProject(projectId: string): DisableProjectResult {
  return useProjectMutation<DisableProjectVariables, Project>({
    projectId,
    mutationFn: async () =>
      unwrap(
        await apiClient.POST('/api/v1/projects/{projectId}/disable', {
          params: { path: { projectId } },
        }),
      ),
  });
}
