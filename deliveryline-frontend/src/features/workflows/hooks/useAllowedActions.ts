/**
 * Story 2.6 (AC8) — typed allowed-actions hook STUB.
 *
 * SEAM (story 2.14): the allowed-actions inspection endpoint does NOT exist in
 * 6.9's OpenAPI snapshot yet. This is a typed stub; the factory key
 * `workflowKeys.allowedActions(workflowRunId)` is the stable contract story 2.14's
 * real hook binds to (AC3). `enabled: false` keeps it inert. Do NOT fabricate the
 * endpoint (story 2.6 anti-pattern) — replace with a real `apiClient.GET` typed by
 * the generated response once 2.14 ships and the client is regenerated.
 */
import { useQuery } from '@tanstack/react-query';

import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export function useAllowedActions(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.allowedActions(workflowRunId),
    queryFn: (): Promise<never> => {
      throw new Error(
        'useAllowedActions: the allowed-actions endpoint is not available yet (ships with story 2.14; key reserved by story 2.6 AC3).',
      );
    },
    enabled: false,
  });
}
