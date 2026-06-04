/**
 * Story 2.18 (AC1) — typed clarification-read hook STUB.
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): there is NO `GET clarifications`
 * list/status endpoint in `schema.d.ts` — the ONLY clarification path is
 * `POST .../clarifications/{id}/answer`. The backend inspection methods
 * (`getClarifications`/`getClarificationStatus`/`countPendingByWorkflowRun`, stories
 * 2.11/2.12) exist in the Java service but are NOT REST-exposed. So this hook is a
 * DISABLED STUB mirroring `useAllowedActions.ts`/`useArtifact.ts`: the factory key
 * `workflowKeys.clarifications(workflowRunId)` is the stable contract the real hook
 * will bind to. `enabled: false` keeps it inert — it never fires the placeholder
 * `queryFn`, which exists only to make the not-yet-available contract loud if a
 * consumer force-enables it before the backend ships.
 *
 * Do NOT fabricate the endpoint here (story 2.6/2.17 anti-pattern). When the
 * clarification-read endpoint ships: regenerate the client (`npm run generate-api`),
 * flip `enabled`, and replace the body with a real `apiClient.GET` typed by the new
 * `ClarificationsView` response shape — ZERO component changes (the container already
 * maps the disabled stub to the calm empty state).
 */
import { useQuery } from '@tanstack/react-query';

import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export function useClarifications(workflowRunId: string) {
  return useQuery({
    queryKey: workflowKeys.clarifications(workflowRunId),
    queryFn: (): Promise<never> => {
      throw new Error(
        'useClarifications: no clarification-read endpoint exists yet (ships with the clarification-read story; key reserved by story 2.18).',
      );
    },
    enabled: false,
  });
}
