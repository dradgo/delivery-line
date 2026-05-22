/**
 * Story 2.6 (AC8) — typed artifact-read hook STUB.
 *
 * SEAM (story 2.14 / artifact-read story): the artifact-read endpoint does NOT
 * exist in 6.9's OpenAPI snapshot yet, so this hook is a typed stub. The factory
 * key `workflowKeys.artifact(artifactId)` is the stable contract the real hook
 * will bind to (AC3). `enabled: false` keeps the query inert — it never fires the
 * placeholder `queryFn`, which exists only to make the not-yet-available contract
 * explicit and loud if a consumer force-enables it before the backend ships.
 *
 * Do NOT fabricate the endpoint here (story 2.6 anti-pattern). When the backend
 * adds it, regenerate the client (`npm run generate-api`) and replace the body
 * with a real `apiClient.GET` typed by the new response shape, dropping `enabled: false`.
 */
import { useQuery } from '@tanstack/react-query';

import { workflowKeys } from '@/lib/queryKeys/workflowKeys';

export function useArtifact(artifactId: string) {
  return useQuery({
    queryKey: workflowKeys.artifact(artifactId),
    queryFn: (): Promise<never> => {
      throw new Error(
        'useArtifact: the artifact-read endpoint is not available yet (ships with the artifact-read story; key reserved by story 2.6 AC3).',
      );
    },
    enabled: false,
  });
}
