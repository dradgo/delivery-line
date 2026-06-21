/**
 * Story 3c-9 (Task 3, AC3) — single-project read hook (edit-form prefill).
 *
 * Wraps the shared `getProjectOptions` (Task 2) so the key comes from the
 * `projectKeys.detail` factory and the result is a typed `Project`. Mirrors
 * `useWorkflowDetail`. `enabled` is gated so the edit form only fetches when a
 * project is actually selected (an empty id never fires a request).
 */
import { useQuery } from '@tanstack/react-query';

import { getProjectOptions } from '@/lib/api/queryOptions';

export function useProjectDetail(projectId: string | undefined) {
  return useQuery({
    ...getProjectOptions(projectId ?? ''),
    enabled: projectId !== undefined && projectId !== '',
  });
}
