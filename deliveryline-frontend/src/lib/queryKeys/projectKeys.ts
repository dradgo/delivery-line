/**
 * Story 3c-9 (Task 2, AC2/AC3/AC7) — the project query-key factory.
 *
 * Mirrors `workflowKeys` (story 2.6): EVERY TanStack Query key comes from a factory
 * call — inline arrays are a build-failing anti-pattern enforced by the
 * `no-inline-query-keys` ESLint rule. The keys are HIERARCHICAL: `detail(id)` is a
 * structural PREFIX of any future per-project sub-resource key, so a mutation can
 * invalidate everything under one project with a single prefix
 * (`queryClient.invalidateQueries({ queryKey: projectKeys.detail(id) })`).
 *
 * The project list has no filters today (`GET /api/v1/projects` takes no query
 * params — see story Dev Notes R3), so `list()` collapses to the same key as
 * `lists()`; both members are kept for parity with `workflowKeys` so a future
 * filter only widens `list(...)` without churning call sites.
 */
export const projectKeys = {
  /** Root of every project key — invalidates the whole feature. */
  all: ['projects'] as const,

  /** All list queries (the project list is unfiltered today). */
  lists: () => [...projectKeys.all, 'list'] as const,
  /** The project list query (no filters — see R3). */
  list: () => [...projectKeys.lists()] as const,

  /** All detail queries. */
  details: () => [...projectKeys.all, 'detail'] as const,
  /** A single project's detail — the PREFIX any future per-project sub-resource shares. */
  detail: (projectId: string) => [...projectKeys.details(), projectId] as const,
} as const;
