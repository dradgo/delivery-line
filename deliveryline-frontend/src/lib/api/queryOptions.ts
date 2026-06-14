/**
 * Story 2.6 (AC5, AC9, AC10) — shared query functions, options, and the
 * QueryClient factory.
 *
 * One place owns the cache-policy defaults so hooks and route loaders never each
 * re-declare them:
 *   • detail  — short `staleTime` (5s): workflow state changes and the UI must
 *     reflect freshness (AC9).
 *   • events  — longer `staleTime` (60s): the event log is append-only, so a
 *     fetched page stays valid far longer (AC9).
 *   • retry   — respects `ProblemDetailsError.retryable`: a non-retryable domain
 *     error (e.g. RUN_NOT_FOUND) is never re-attempted (AC5).
 *
 * Query keys come from `workflowKeys` (AC3) and the SAME key from two consumers
 * shares ONE cache entry — TanStack Query's request dedup + structural sharing
 * (AC10 / NFR25-27) means the Context Strip and Artifact Review Panel reading the
 * same run detail trigger ONE fetch, not two.
 */
import { QueryCache, QueryClient, queryOptions } from '@tanstack/react-query';

import { apiClient, unwrap } from './client';
import { isProblemDetailsError, type ProblemDetailsError } from './problemDetails';
import type { components } from './schema';
import { artifactTypeLabel, type ArtifactView } from '@/features/workflows/artifactView';
import { workflowKeys, type WorkflowListFilters } from '../queryKeys/workflowKeys';

export type WorkflowDetail = components['schemas']['WorkflowDetail'];
export type WorkflowSummary = components['schemas']['WorkflowSummary'];
export type WorkflowEventsResponse = components['schemas']['WorkflowEventsResponse'];
/** The raw artifact-read DTO (story 3a-9 Gate 3) — adapted into `ArtifactView` below. */
export type ArtifactDetail = components['schemas']['ArtifactDetail'];

/** Cache-freshness defaults (ms). Centralized so every hook reads the same policy. */
export const STALE_TIME = {
  /** Workflow detail — short, reflects live state (AC9). */
  detail: 5_000,
  /** Event history — longer, the stream is append-only (AC9). */
  events: 60_000,
  /** Run-queue list — short, the queue turns over as runs advance. */
  list: 5_000,
  /** Artifact content — long; the persisted redacted body is immutable for a given version. */
  artifact: 60_000,
} as const;

const MAX_RETRIES = 2;

/**
 * Retry policy honored by every query: never re-attempt a domain error the
 * backend flagged non-retryable; otherwise allow a small bounded number of
 * retries for transient transport failures (AC5).
 */
export function retryUnlessNonRetryable(failureCount: number, error: unknown): boolean {
  if (isProblemDetailsError(error) && !error.retryable) {
    return false;
  }
  return failureCount < MAX_RETRIES;
}

/** GET a list of run summaries (newest-first), throwing typed problem details on failure. */
async function fetchWorkflowList(filters: WorkflowListFilters): Promise<WorkflowSummary[]> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows', {
      params: { query: filters.state !== undefined ? { state: filters.state } : {} },
    }),
  );
}

/** GET a single run's detail, throwing typed problem details (e.g. RUN_NOT_FOUND) on failure. */
async function fetchWorkflowDetail(workflowRunId: string): Promise<WorkflowDetail> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}', {
      params: { path: { workflowRunId } },
    }),
  );
}

/** GET a run's event history, throwing typed problem details on failure. */
async function fetchWorkflowEvents(workflowRunId: string): Promise<WorkflowEventsResponse> {
  return unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/events', {
      params: { path: { workflowRunId } },
    }),
  );
}

/** Options for the run-queue list query (used by hooks AND the `/workflows` loader). */
export function listQueryOptions(filters: WorkflowListFilters = {}) {
  return queryOptions({
    queryKey: workflowKeys.list(filters),
    queryFn: () => fetchWorkflowList(filters),
    staleTime: STALE_TIME.list,
  });
}

/** Options for a single run's detail (used by hooks AND the detail-route loader). */
export function detailQueryOptions(workflowRunId: string) {
  return queryOptions({
    queryKey: workflowKeys.detail(workflowRunId),
    queryFn: () => fetchWorkflowDetail(workflowRunId),
    staleTime: STALE_TIME.detail,
  });
}

/** Options for a run's event history. */
export function eventsQueryOptions(workflowRunId: string) {
  return queryOptions({
    queryKey: workflowKeys.events(workflowRunId),
    queryFn: () => fetchWorkflowEvents(workflowRunId),
    staleTime: STALE_TIME.events,
  });
}

/**
 * Story 3a-9 (Gate 3 / D1) — compose the display `title` the frontend `ArtifactView`
 * requires. The backend DTO deliberately omits `title` (it is a display concern with no
 * clean backend source — Dev Notes D1); we derive it from the artifact type + version.
 */
function composeArtifactTitle(artifactType: string, version: number): string {
  switch (artifactType) {
    case 'spec':
      return `Specification — v${version}`;
    case 'implementationPlan':
      return `Implementation Plan — v${version}`;
    case 'prOutput':
      return `PR Output — v${version}`;
    default:
      return `${artifactTypeLabel(artifactType)} — v${version}`;
  }
}

/**
 * Story 3a-9 (Gate 3 / D1) — map the raw `ArtifactDetail` wire DTO into the frontend-owned
 * `ArtifactView` shape the Artifact Review Panel renders. CRITICAL: the result MUST satisfy
 * `isArtifactView` (artifactId + title + finite version + classification + body + createdAt),
 * or the panel renders `error` instead of `default`. We INJECT `artifactId` from the query
 * arg (robust against a wire-null `artifactId` — `[[workflowdetail-wire-sends-null-not-undefined]]`)
 * and COMPOSE `title` here; everything else passes through from the DTO.
 */
export function toArtifactView(dto: ArtifactDetail, artifactId: string): ArtifactView {
  const version = typeof dto.version === 'number' ? dto.version : 0;
  const artifactType = dto.artifactType ?? '';
  const base = {
    artifactId,
    title: composeArtifactTitle(artifactType, version),
    version,
    classification: dto.classification ?? '',
    body: dto.body ?? '',
    createdAt: dto.createdAt ?? '',
  };
  if (artifactType === 'implementationPlan') {
    return { ...base, artifactType: 'implementationPlan' };
  }
  if (artifactType === 'prOutput') {
    // Story 3.27 — the prOutput view requires the runner-emitted branch/commit/diff + the
    // backend-truth prLinkage. The live 3a-9 `ArtifactDetail` wire carries NONE of these
    // yet (no live prOutput read model — the renderer is fixture-driven), so map empty
    // defaults; the renderer degrades gracefully (empty diff → "No diff content",
    // null prLinkage → "No linked pull request"). A future read-model story populates them.
    return {
      ...base,
      artifactType: 'prOutput',
      branch: '',
      commitSha: '',
      diff: '',
      prLinkage: null,
    };
  }
  // Default to the spec variant (the only fully-rendered type and this story's scope); an
  // unknown wire type still carries through so `isArtifactView` can reject it loudly.
  return {
    ...base,
    artifactType: (artifactType === 'spec' ? 'spec' : artifactType) as 'spec',
    ...(typeof dto.checksum === 'string' ? { checksum: dto.checksum } : {}),
  };
}

/**
 * GET a single artifact's redacted content, throwing typed problem details (RUN_NOT_FOUND /
 * ARTIFACT_RECORD_NOT_FOUND / ARTIFACT_PAYLOAD_UNAVAILABLE) on failure, then adapt the raw DTO
 * into the `ArtifactView` the panel consumes.
 */
async function fetchArtifact(workflowRunId: string, artifactId: string): Promise<ArtifactView> {
  const dto = unwrap(
    await apiClient.GET('/api/v1/workflows/{workflowRunId}/artifacts/{artifactId}', {
      params: { path: { workflowRunId, artifactId } },
    }),
  );
  return toArtifactView(dto, artifactId);
}

/** Options for a single artifact's content (used by `useArtifact` AND any loader warm). */
export function artifactQueryOptions(workflowRunId: string, artifactId: string) {
  return queryOptions({
    // Key by the globally-unique artifact public id (story 2.6 reserved this one-arg factory).
    queryKey: workflowKeys.artifact(artifactId),
    queryFn: () => fetchArtifact(workflowRunId, artifactId),
    staleTime: STALE_TIME.artifact,
  });
}

/**
 * Build the single app-wide QueryClient. The `QueryCache.onError` is the typed
 * error seam (AC5): it receives the `ProblemDetailsError` thrown by the query
 * functions so future cross-cutting handling (e.g. a global toast for
 * INTERNAL_ERROR) branches on `error.code` — never on raw status or string match.
 * It is intentionally side-effect-free today (no console spam — Task 8); call
 * sites consume the typed error via each query's own `error`.
 *
 * @param onProblemDetails optional hook invoked for typed domain errors (tests +
 *   future global handlers pass one); non-problem errors are ignored here and
 *   surface through the individual query's `error`.
 */
export function createQueryClient(
  onProblemDetails?: (error: ProblemDetailsError) => void,
): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error) => {
        if (isProblemDetailsError(error)) {
          onProblemDetails?.(error);
        }
      },
    }),
    defaultOptions: {
      queries: {
        retry: retryUnlessNonRetryable,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: retryUnlessNonRetryable,
      },
    },
  });
}
