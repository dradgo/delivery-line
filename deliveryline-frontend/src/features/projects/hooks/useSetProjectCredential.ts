/**
 * Story 3c-9 (Task 4, AC4/AC7) — the live `useSetProjectCredential` mutation.
 *
 * `PUT /api/v1/projects/{projectId}/credentials/{role}` — the ONLY plaintext-secret
 * ingress. `Idempotency-Key` is REQUIRED (the OpenAPI marks the header required; a
 * missing key would 400 `MISSING_IDEMPOTENCY_KEY`), so the factory's minted key is
 * always attached. The plaintext `secret` rides the body `SetCredentialRequest`; the
 * response (`SetCredentialResponse`) is id-only and NEVER carries a secret. On success
 * the list + this project's detail (credential-presence badges) are invalidated.
 *
 * SECRET HOSTILITY: the secret lives only in the request body for the duration of one
 * call. It is never returned, never logged (no breadcrumb carries it), and the caller
 * clears its input on success (AC4).
 *
 * Typed failures: `CREDENTIAL_MASTER_KEY_UNCONFIGURED` (503), `IDEMPOTENCY_KEY_CONFLICT`
 * (409), `MISSING_IDEMPOTENCY_KEY` / `UNKNOWN_REGISTRY_VALUE` (400), `PROJECT_NOT_FOUND` (404).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { components } from '@/lib/api/schema';

import { useProjectMutation, type ProjectMutationResult } from './useProjectMutation';

type SetCredentialResponse = components['schemas']['SetCredentialResponse'];

/** The variables a caller passes to set a credential. The secret is write-only. */
export interface SetCredentialVariables {
  /** Underscored connector role wire form (`ticket_source` / `repo_host` / `reviewer`). */
  role: string;
  /** The plaintext secret — write-only, never returned/logged. */
  secret: string;
  /** Forward-compat — omitted today (no live actor context). */
  actorIdentity?: string | undefined;
}

export type SetProjectCredentialResult = ProjectMutationResult<
  SetCredentialResponse,
  SetCredentialVariables
>;

/** Build the live set-credential mutation for an existing project. */
export function useSetProjectCredential(projectId: string): SetProjectCredentialResult {
  return useProjectMutation<SetCredentialVariables, SetCredentialResponse>({
    projectId,
    mutationFn: async ({ variables, idempotencyKey }) =>
      unwrap(
        await apiClient.PUT('/api/v1/projects/{projectId}/credentials/{role}', {
          params: {
            path: { projectId, role: variables.role },
            header: {
              [IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
              ...(variables.actorIdentity !== undefined && variables.actorIdentity !== ''
                ? { 'X-Actor-Identity': variables.actorIdentity }
                : {}),
            },
          },
          body: { secret: variables.secret },
        }),
      ),
  });
}
