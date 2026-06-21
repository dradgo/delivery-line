/**
 * Story 3c-9 (Task 4, AC3/AC7) — the live `useCreateProject` mutation.
 *
 * CREATE over `POST /api/v1/projects`. Idempotency-Key is OPTIONAL on this op (the
 * spec marks it optional, mirroring `submit-workflow`); we still mint + attach one
 * per attempt so a transient retry is idempotent. `X-Actor-Identity` is sent only
 * when the caller supplies it (no live actor context today — forward-compat, the
 * `useApproveSpec` reviewerRole precedent). On success the list is invalidated.
 *
 * Typed failures surface via `ProblemDetailsError`: `PROJECT_SLUG_CONFLICT` (409),
 * `IDEMPOTENCY_KEY_CONFLICT` (409), `INVALID_COMMAND_PAYLOAD` / `UNKNOWN_REGISTRY_VALUE`
 * (400) — branch on `error.code`, never status text.
 */
import { apiClient, unwrap } from '@/lib/api/client';
import { IDEMPOTENCY_KEY_HEADER } from '@/lib/api/idempotency';
import type { Project } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';

import { useProjectMutation, type ProjectMutationResult } from './useProjectMutation';

type CreateProjectRequest = components['schemas']['CreateProjectRequest'];

/** The variables a caller passes to create a project. Shape mirrors `CreateProjectRequest`. */
export interface CreateProjectVariables {
  name: string;
  slug: string;
  ticketSourceKind: string;
  repoHostKind: string;
  /** Nullable repository URL — omitted from the body when blank. */
  repositoryUrl?: string | undefined;
  openspecEnabled: boolean;
  /** Forward-compat — omitted today (no live actor context). */
  actorIdentity?: string | undefined;
}

export type CreateProjectResult = ProjectMutationResult<Project, CreateProjectVariables>;

/** Build the live create-project mutation. No `projectId` — create CREATES the project. */
export function useCreateProject(): CreateProjectResult {
  return useProjectMutation<CreateProjectVariables, Project>({
    mutationFn: async ({ variables, idempotencyKey }) => {
      const body: CreateProjectRequest = {
        name: variables.name,
        slug: variables.slug,
        ticketSourceKind: variables.ticketSourceKind,
        repoHostKind: variables.repoHostKind,
        openspecEnabled: variables.openspecEnabled,
        // Optional-field spread: omit when blank (mirror `useApproveSpec`).
        ...(variables.repositoryUrl !== undefined && variables.repositoryUrl !== ''
          ? { repositoryUrl: variables.repositoryUrl }
          : {}),
      };
      return unwrap(
        await apiClient.POST('/api/v1/projects', {
          params: {
            header: {
              [IDEMPOTENCY_KEY_HEADER]: idempotencyKey,
              ...(variables.actorIdentity !== undefined && variables.actorIdentity !== ''
                ? { 'X-Actor-Identity': variables.actorIdentity }
                : {}),
            },
          },
          body,
        }),
      );
    },
  });
}
