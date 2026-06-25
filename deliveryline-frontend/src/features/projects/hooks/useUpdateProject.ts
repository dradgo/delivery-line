/**
 * Story 3c-9 (Task 4, AC3/AC7) — the live `useUpdateProject` mutation.
 *
 * EDIT over `PUT /api/v1/projects/{projectId}`. The op carries NO `Idempotency-Key`
 * header (a full-replace PUT is naturally idempotent); the minted key the factory
 * provides is simply unused here (the client middleware still adds a fallback for the
 * mutation invariant). `slug` is immutable post-create and is NOT in
 * `UpdateProjectRequest`, so the edit form never sends it. `X-Actor-Identity` is sent
 * only when supplied. On success the list + this project's detail are invalidated.
 *
 * Typed failures: `PROJECT_SLUG_CONFLICT` (409 — defensive), `PROJECT_NOT_FOUND` (404),
 * `INVALID_COMMAND_PAYLOAD` / `UNKNOWN_REGISTRY_VALUE` (400).
 */
import { apiClient, unwrap } from '@/lib/api/client';
import type { Project } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';

import { useProjectMutation, type ProjectMutationResult } from './useProjectMutation';

type UpdateProjectRequest = components['schemas']['UpdateProjectRequest'];

/** The variables a caller passes to update a project. Shape mirrors `UpdateProjectRequest` (NO slug). */
export interface UpdateProjectVariables {
  name: string;
  ticketSourceKind: string;
  repoHostKind: string;
  /** Nullable repository URL — omitted from the body when blank. */
  repositoryUrl?: string | undefined;
  openspecEnabled: boolean;
  /**
   * Project-wide runner default (3d-3 override). Full-replace on update: send `null` to clear
   * it (use the global per-stage kind), or a kind to set it. Always sent by the edit form so an
   * edit never silently drops a previously-set override.
   */
  runnerKind?: string | null | undefined;
  /**
   * Per-step runner mapping (step → kind), full-replace on update: the submitted map is
   * authoritative. Send `{}` to clear all per-step mappings. Always sent by the edit form.
   */
  stepRunnerKinds?: Record<string, string> | undefined;
  /** Forward-compat — omitted today (no live actor context). */
  actorIdentity?: string | undefined;
}

export type UpdateProjectResult = ProjectMutationResult<Project, UpdateProjectVariables>;

/** Build the live update-project mutation for an existing project. */
export function useUpdateProject(projectId: string): UpdateProjectResult {
  return useProjectMutation<UpdateProjectVariables, Project>({
    projectId,
    mutationFn: async ({ variables }) => {
      const body: UpdateProjectRequest = {
        name: variables.name,
        ticketSourceKind: variables.ticketSourceKind,
        repoHostKind: variables.repoHostKind,
        openspecEnabled: variables.openspecEnabled,
        ...(variables.repositoryUrl !== undefined && variables.repositoryUrl !== ''
          ? { repositoryUrl: variables.repositoryUrl }
          : {}),
        // Story 3e-4 — full-replace runner config. Always send both so an edit preserves or clears
        // them explicitly (omitting runnerKind would clear the override; omitting the map clears it).
        runnerKind: (variables.runnerKind ?? null) as Exclude<
          UpdateProjectRequest['runnerKind'],
          undefined
        >,
        stepRunnerKinds: variables.stepRunnerKinds ?? {},
      };
      return unwrap(
        await apiClient.PUT('/api/v1/projects/{projectId}', {
          params: {
            path: { projectId },
            ...(variables.actorIdentity !== undefined && variables.actorIdentity !== ''
              ? { header: { 'X-Actor-Identity': variables.actorIdentity } }
              : {}),
          },
          body,
        }),
      );
    },
  });
}
