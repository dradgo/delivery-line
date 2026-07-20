/**
 * Story 4.24 (AC1, R6) — the registry query-key factory.
 *
 * Governed vocabularies served by `GET /api/v1/registries/**` are GLOBAL, not run-scoped, so their
 * keys live in a DEDICATED factory rooted at `['registries']` — NOT under `workflowKeys.detail(id)`
 * (a run's classify mutation must not invalidate the shared taxonomy registry; the registry changes
 * only under ADR-0035 governance, never per run). Every TanStack Query key comes from a factory call
 * (the `no-inline-query-keys` ESLint rule); inline arrays are a build-failing anti-pattern.
 */
export const registryKeys = {
  /** Root of every registry key. */
  all: ['registries'] as const,

  /**
   * The governed failure-taxonomy registry (`GET /api/v1/registries/failure-taxonomy`). Global +
   * long-lived (the six values + their curated prose change only under ADR 0035), so consumers key
   * it here with a long `staleTime` rather than under any run's detail subtree.
   */
  failureTaxonomy: () => [...registryKeys.all, 'failureTaxonomy'] as const,
} as const;
