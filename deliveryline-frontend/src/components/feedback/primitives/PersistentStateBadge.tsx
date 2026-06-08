/**
 * Story 2.21 (AC1, AC4, AC6) — `<PersistentStateBadge>`.
 *
 * A non-auto-dismissing badge for a PERSISTENT workflow outcome (a decision
 * result, an incorporated state). It mirrors the feature-layer `StateSignifierChip`
 * icon+label idiom (WorkflowStateBadge.tsx) but is built from the GENERIC
 * `@/lib/state-signifiers` contract only — it does NOT import the chip
 * (T-LAYERING). It never auto-dismisses; the consumer controls its lifetime.
 *
 * AC6 (non-color signifier + a11y): pairs the `--state-{name}` token color with
 * an icon AND a text label (never color alone, story 2.3 AC5), and exposes a
 * `role="status"` + `aria-live="polite"` live region for the outcome it carries.
 */
import { cn } from '@/lib/utils';
import type { StateName } from '@/lib/state-signifiers';

import { stateIconComponent, stateLabel, stateToneClasses } from './feedbackStatePresentation';

export type { SemanticState } from './feedbackStatePresentation';

export interface PersistentStateBadgeProps {
  /**
   * The semantic state driving the icon + label + token color. `SemanticState`
   * is an alias of this same `StateName` union (T-NO-PARALLEL-UNION).
   */
  state: StateName;
  /** Visible label override (defaults to the canonical `STATE_SIGNIFIERS` label). */
  label?: string | undefined;
  /** Optional tooltip (`title`) — e.g. the reason behind a stale/superseded outcome. */
  title?: string | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export function PersistentStateBadge({
  state,
  label,
  title,
  className,
  testId = 'persistent-state-badge',
}: PersistentStateBadgeProps) {
  const Icon = stateIconComponent(state);
  const isAssertive = state === 'warning' || state === 'blocker' || state === 'error';
  const visibleLabel = label !== undefined && label.trim().length > 0 ? label : stateLabel(state);
  return (
    <span
      data-testid={testId}
      data-persistent-state-badge
      data-state-name={state}
      role={isAssertive ? 'alert' : 'status'}
      aria-live={isAssertive ? 'assertive' : 'polite'}
      title={title}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium',
        stateToneClasses(state),
        className,
      )}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      <span>{visibleLabel}</span>
    </span>
  );
}
