/**
 * Story 2.22 (AC4.d) — consumer-facing feedback barrel.
 *
 * Canonical import path: `import { EmptyState, LoadingState, ErrorState } from
 * '@/components/feedback'`. Matches architecture.md:1182 ("Frontend empty,
 * stale, conflict, no-actions, and failed-load states live under
 * `components/feedback`"). Never write a generic spinner — use `<LoadingState>`.
 */
export * from './states';
