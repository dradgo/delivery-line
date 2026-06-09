/**
 * Story 2.22 (AC4.d) — consumer-facing feedback barrel.
 *
 * Canonical import path: `import { EmptyState, LoadingState, ErrorState } from
 * '@/components/feedback'`. Matches architecture.md:1182 ("Frontend empty,
 * stale, conflict, no-actions, and failed-load states live under
 * `components/feedback`"). Never write a generic spinner — use `<LoadingState>`.
 *
 * Story 2.21 — the shared UX-DR15 feedback primitives (`<InlineFeedback>`,
 * `<PersistentStateBadge>`, `<ActionLifecycleIndicator>`, `feedbackToast`).
 */
export * from './states';
export * from './primitives';
export * from './AuditRoleLabel';
