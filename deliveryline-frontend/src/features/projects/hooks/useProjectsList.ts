/**
 * Story 3c-9 (Task 3, AC2) — thin project-list read hook.
 *
 * Wraps the shared `listProjectsOptions` (Task 2) so the key always comes from the
 * `projectKeys.list` factory (satisfies `no-inline-query-keys`) and the result is
 * typed `Project[]` with no runtime cast. Mirrors `useWorkflowsList`.
 */
import { useQuery } from '@tanstack/react-query';

import { listProjectsOptions } from '@/lib/api/queryOptions';

export function useProjectsList() {
  return useQuery(listProjectsOptions());
}
