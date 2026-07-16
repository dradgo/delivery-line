/**
 * Story 2.6 (AC3) — the workflow query-key factory.
 *
 * EVERY TanStack Query key in the app comes from a factory call (architecture.md
 * 766–777, 830, 851) — inline arrays are a build-failing anti-pattern, enforced by
 * the `no-inline-query-keys` ESLint rule (story 2.31). The keys are HIERARCHICAL:
 * `detail(id)` is a structural PREFIX of `events(id)` / `artifacts(id)` /
 * `allowedActions(id)`, so a mutation can invalidate everything under one run with
 * a single prefix (`queryClient.invalidateQueries({ queryKey: workflowKeys.detail(id) })`).
 *
 * AC3 mandates these exact members: `all`, `list`, `detail`, `events`, `artifacts`,
 * `artifact`, `allowedActions`. Note: `artifacts`/`artifact`/`allowedActions` name
 * endpoints that DO NOT exist in 6.9's snapshot yet (artifact reads land in a later
 * Epic-2 story; allowed-actions is story 2.14). The KEYS are the stable contract
 * those future hooks bind to, so they are authored now even though their hooks are
 * typed stubs (see src/features/workflows/hooks).
 */

/** Filters accepted by `listWorkflows` (6.9 documents only `state`). */
export interface WorkflowListFilters {
  /** Current-state filter, e.g. `WaitingForSpecApproval`. */
  state?: string;
  /**
   * Story 3d-8 — include soft-hidden (archived) runs in the queue. Omitted/false hides them
   * (the backend defaults to `archived_at IS NULL`); `normalizeFilters` keys the cache per value
   * so the archived/un-archived views are distinct cache entries.
   */
  includeArchived?: boolean;
  /** Project filter, accepted as a project slug or prj_ public id. */
  projectId?: string;
}

/**
 * Normalize filters into a stable, serializable shape so two structurally-equal
 * filter objects produce an equal key (and therefore dedupe / share one cache
 * entry). Undefined fields are dropped; remaining keys are sorted.
 */
function normalizeFilters(filters: WorkflowListFilters = {}): WorkflowListFilters {
  const normalized: WorkflowListFilters = {};
  for (const key of Object.keys(filters).sort() as (keyof WorkflowListFilters)[]) {
    const value = filters[key];
    if (value === undefined) {
      continue;
    }
    if (key === 'projectId' && typeof value === 'string') {
      const trimmed = value.trim();
      if (trimmed === '') {
        continue;
      }
      (normalized as Record<string, unknown>)[key] = trimmed;
      continue;
    }
    // Writing through a union key (`keyof WorkflowListFilters`) would require the
    // value to be assignable to `never` (the intersection of the differently-typed
    // fields); a Record view keeps the generic loop while the runtime is unchanged.
    (normalized as Record<string, unknown>)[key] = value;
  }
  return normalized;
}

export const workflowKeys = {
  /** Root of every workflow key — invalidates the whole feature. */
  all: ['workflows'] as const,

  /** All list queries (any filter). */
  lists: () => [...workflowKeys.all, 'list'] as const,
  /** A specific filtered run-queue list. */
  list: (filters?: WorkflowListFilters) =>
    [...workflowKeys.lists(), normalizeFilters(filters)] as const,

  /** All detail queries. */
  details: () => [...workflowKeys.all, 'detail'] as const,
  /** A single run's detail — the PREFIX shared by `events`/`artifacts`/`allowedActions`. */
  detail: (workflowRunId: string) => [...workflowKeys.details(), workflowRunId] as const,

  /** A run's append-only event history. */
  events: (workflowRunId: string) => [...workflowKeys.detail(workflowRunId), 'events'] as const,
  /** A run's artifact list (endpoint ships in a later story; key authored now). */
  artifacts: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'artifacts'] as const,
  /**
   * A run's allowed operator actions (endpoint ships in story 2.14; key authored now).
   *
   * Story 3b-4: an optional `actorRole` scopes the cache entry per role — appended to the
   * key ONLY when provided (any falsy value collapses to the base key, so an empty string
   * cannot fork a phantom entry distinct from the byte-identical no-arg request), so the
   * developer-role entry (story 3b-4) is distinct from the default (`product_reviewer`) one
   * and the no-arg key stays byte-identical to today (spec/recovery consumers unaffected).
   * Either way the key remains a structural PREFIX child of `detail(id)`, so the
   * `detail(id)` invalidation cascade still refreshes it.
   */
  allowedActions: (workflowRunId: string, actorRole?: string) =>
    actorRole == null || actorRole === ''
      ? ([...workflowKeys.detail(workflowRunId), 'allowedActions'] as const)
      : ([...workflowKeys.detail(workflowRunId), 'allowedActions', actorRole] as const),
  /**
   * A run's clarifications (read endpoint ships with the clarification-read story;
   * key authored now by story 2.18). A PREFIX child of `detail(id)`, so a spec
   * mutation's `detail(id)` invalidation cascades to it for free.
   */
  clarifications: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'clarifications'] as const,
  /**
   * A run's advisory reviewer verdict (story 3d-2). A PREFIX child of `detail(id)`, so a spec/
   * approval mutation's `detail(id)` invalidation cascade refreshes it for free.
   */
  reviewerVerdict: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'reviewerVerdict'] as const,
  /**
   * A run's advisory split proposal (story 3f-4). A PREFIX child of `detail(id)`, so a
   * split request/repropose/decline mutation's `detail(id)` invalidation cascade refreshes
   * the proposal channel (+ the gate's allowed-actions) for free.
   */
  splitProposal: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'splitProposal'] as const,

  /**
   * A run's parked manual-execution input bundle (story 3d-4). A PREFIX child of `detail(id)`, so a
   * manual-artifact submission's `detail(id)` invalidation cascade refreshes it for free (after a
   * successful submission the run leaves WaitingForManualExecution and the bundle 409s — the surface
   * self-hides on the new state).
   */
  manualBundle: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'manualBundle'] as const,

  /**
   * A run's latest provider usage/limit status (story 3d-7). A PREFIX child of `detail(id)`, so a
   * detail invalidation cascade refreshes the indicator for free as the run advances.
   */
  providerUsageStatus: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'providerUsageStatus'] as const,

  /**
   * A run's per-step token usage (story 3g-4). A PREFIX child of `detail(id)`, so a detail
   * invalidation cascade refreshes the per-step token panel for free as the run advances.
   */
  stepExecutions: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'stepExecutions'] as const,

  /**
   * A run's failure-diagnostics deep-dive (story 4.4). A PREFIX child of `detail(id)`, so a
   * recovery mutation's `detail(id)` invalidation cascade refreshes the operator panel
   * (recommended actions + integration sync + runner-log reference) for free as the run advances.
   */
  failureDiagnostics: (workflowRunId: string) =>
    [...workflowKeys.detail(workflowRunId), 'failureDiagnostics'] as const,

  /**
   * A run's NON-MUTATING rerun-from-step preview (story 4.22). Keyed per `targetStep` so the
   * investigating/executing previews are distinct cache entries. A structural PREFIX child of
   * `detail(id)`, so a recovery mutation's `detail(id)` invalidation cascade refreshes it for free.
   */
  rerunPreview: (workflowRunId: string, targetStep: string) =>
    [...workflowKeys.detail(workflowRunId), 'rerunPreview', targetStep] as const,

  /** A single artifact by its own public id (endpoint ships in the artifact-read story). */
  artifact: (artifactId: string) => [...workflowKeys.all, 'artifact', artifactId] as const,

  /**
   * Story 4.20 (AC1) — a typed revision delta between two artifact versions of ONE lineage
   * (story 4.19 `GET /api/v1/artifacts/{a}/compare/{b}`). Keyed OFF `all` (a sibling of
   * `artifact(id)`), NOT `detail(runId)`: a compare spans an artifact lineage independent of any
   * single run (it may cross runs), so it must not live under one run's detail-invalidation
   * subtree. Read-only + idempotent (no Idempotency-Key). The A/B order is significant —
   * A = baseline/prior, B = target/current — so the pair is part of the key verbatim.
   */
  revisionDelta: (artifactIdA: string, artifactIdB: string) =>
    [...workflowKeys.all, 'revisionDelta', artifactIdA, artifactIdB] as const,
} as const;
