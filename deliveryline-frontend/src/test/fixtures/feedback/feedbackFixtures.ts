/**
 * Story 2.21 (AC7) — fixtures for the UX-DR15 feedback-primitive variant
 * inventory. One fixture per documented variant (inline informational / inline
 * success-incorporated / warning-stale / blocker / error-failed-action /
 * persistent decision outcome) plus lifecycle-chain fixtures (incl. an off-chain
 * terminal). Driven by both the `PrimitivesPlayground` section and the
 * fixture-driven RTL render tests (true pixel-diff snapshots are deferred to
 * story 2.27 — these assert roles/text/`data-*`, never `toMatchSnapshot`).
 *
 * Pure `.ts` (T-REFRESH) — plain data; bodies are TRUSTED demo strings.
 */
import type { StateName } from '@/lib/state-signifiers';

import type {
  InlineFeedbackPersistence,
  InlineFeedbackVariant,
} from '@/components/feedback/primitives/InlineFeedback';

/** A single `<InlineFeedback>` variant fixture. */
export interface InlineFeedbackFixture {
  readonly id: string;
  readonly label: string;
  readonly variant: InlineFeedbackVariant;
  readonly persistsUntil: InlineFeedbackPersistence;
  readonly title?: string;
  readonly body: string;
  readonly staleStateExplanation?: {
    readonly whatChanged: string;
    readonly whatToDoNext: string;
  };
}

export const INLINE_FEEDBACK_FIXTURES: readonly InlineFeedbackFixture[] = [
  {
    id: 'inline-informational',
    label: 'Inline · informational',
    variant: 'info',
    persistsUntil: 'dismiss',
    title: 'Comparison available',
    body: 'An earlier version is available to compare against.',
  },
  {
    id: 'inline-success-incorporated',
    label: 'Inline · success (incorporated)',
    variant: 'success',
    persistsUntil: 'workflowChange',
    title: 'Answer incorporated',
    body: 'Your answer was applied to the latest specification version.',
  },
  {
    id: 'inline-warning-stale',
    label: 'Inline · warning (stale)',
    variant: 'warning',
    persistsUntil: 'workflowChange',
    title: 'This view is out of date',
    body: 'The run changed while you were deciding.',
    staleStateExplanation: {
      whatChanged: 'A newer specification version was published since you opened this.',
      whatToDoNext: 'Refresh to load the current version, then re-submit your decision.',
    },
  },
  {
    id: 'inline-blocker',
    label: 'Inline · blocker',
    variant: 'blocker',
    persistsUntil: 'infinite',
    title: 'Cannot approve yet',
    body: 'Open clarifications must be incorporated before this run can be approved.',
  },
  {
    id: 'inline-error-failed-action',
    label: 'Inline · error (failed action)',
    variant: 'error',
    persistsUntil: 'dismiss',
    title: 'Submission failed',
    body: 'Your decision could not be recorded. Try again.',
  },
] as const;

/** A single `<PersistentStateBadge>` fixture (persistent decision outcome). */
export interface PersistentBadgeFixture {
  readonly id: string;
  readonly label: string;
  readonly state: StateName;
  readonly badgeLabel?: string;
  readonly title?: string;
}

export const PERSISTENT_BADGE_FIXTURES: readonly PersistentBadgeFixture[] = [
  {
    id: 'persistent-approved',
    label: 'Persistent · approved',
    state: 'success',
    badgeLabel: 'Approved',
  },
  {
    id: 'persistent-rejected',
    label: 'Persistent · rejected',
    state: 'error',
    badgeLabel: 'Rejected',
  },
  {
    id: 'persistent-superseded',
    label: 'Persistent · superseded',
    state: 'stale',
    badgeLabel: 'Superseded',
    title: 'A newer answer set this one aside.',
  },
] as const;

/** A single `<ActionLifecycleIndicator>` fixture. */
export interface LifecycleFixture {
  readonly id: string;
  readonly label: string;
  readonly stages?: readonly string[];
  readonly currentStage: string;
}

export const LIFECYCLE_FIXTURES: readonly LifecycleFixture[] = [
  { id: 'lifecycle-submitted', label: 'Lifecycle · submitted', currentStage: 'submitted' },
  { id: 'lifecycle-accepted', label: 'Lifecycle · accepted', currentStage: 'accepted' },
  { id: 'lifecycle-incorporated', label: 'Lifecycle · incorporated', currentStage: 'incorporated' },
  // Off-chain terminal — left the happy chain (UX-DR15 distinct treatment).
  { id: 'lifecycle-failed', label: 'Lifecycle · failed (off-chain)', currentStage: 'failed' },
] as const;
