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
    if (value !== undefined) {
      normalized[key] = value;
    }
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

  /** A single artifact by its own public id (endpoint ships in the artifact-read story). */
  artifact: (artifactId: string) => [...workflowKeys.all, 'artifact', artifactId] as const,
} as const;
