/**
 * Story 2.16 (AC2.b, AC6) — the shared `WorkflowStateBadge`.
 *
 * Maps the backend `currentState` enum → a `StateName` (story 2.3) → its
 * `--state-*` color tokens + the `STATE_SIGNIFIERS` lucide icon + a text label.
 * This is the SINGLE badge story 2.15 (`RunReviewQueueItem`) imports for
 * "consistent badge styling" — it ships after this story, so the badge is born here.
 * The pure mapping lives in the sibling {@link ./workflowStateMapping} module.
 *
 * TRAP T1 — this lives under `features/workflows/`, NOT `components/ui/`: the
 * `no-workflow-domain-in-ui-primitives` ESLint rule (story 2.31) forbids workflow
 * vocabulary in `ui/**`, and `eslint --max-warnings=0` would fail there.
 *
 * AC6 / story 2.3 AC5 — NEVER color alone: every chip pairs the color token with
 * an icon AND a text label.
 */
import { cn } from '@/lib/utils';
import type { StateName } from '@/lib/state-signifiers';

import {
  STATE_CHIP_CLASSES,
  backendStateToStateName,
  stateIconComponent,
} from './workflowStateMapping';

export interface StateSignifierChipProps {
  /** Semantic state driving the color token + icon. */
  stateName: StateName;
  /** Visible text label (the non-color signifier alongside the icon). */
  label: string;
  /** Optional tooltip (`title`) — used by the stale chip for its reason text. */
  title?: string | undefined;
  className?: string | undefined;
  /** Test hook. */
  testId?: string | undefined;
}

/**
 * A small color + icon + label chip. The reusable primitive behind both the
 * workflow-state badge and the strip's "Stale" / "Escalated" markers — each pairs
 * its color with an icon AND a text label so status is never color-alone (AC6).
 */
export function StateSignifierChip({
  stateName,
  label,
  title,
  className,
  testId,
}: StateSignifierChipProps) {
  const Icon = stateIconComponent(stateName);
  return (
    <span
      data-testid={testId}
      data-state-name={stateName}
      title={title}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium',
        STATE_CHIP_CLASSES[stateName],
        className,
      )}
    >
      <Icon className="size-3.5" aria-hidden />
      <span>{label}</span>
    </span>
  );
}

export interface WorkflowStateBadgeProps {
  /** Backend `currentState` enum value (e.g. `Completed`). */
  currentState?: string | undefined;
  className?: string | undefined;
}

/**
 * The current-state badge: the backend state label colored + iconed by its mapped
 * `StateName`. The label shows the raw backend state (parity with the CLI /
 * `RunIdentityRegion`); an absent state reads as "Unknown" on the neutral palette.
 */
export function WorkflowStateBadge({ currentState, className }: WorkflowStateBadgeProps) {
  const stateName = backendStateToStateName(currentState);
  const label = currentState !== undefined && currentState.trim() !== '' ? currentState : 'Unknown';
  return (
    <StateSignifierChip
      stateName={stateName}
      label={label}
      className={className}
      testId="workflow-state-badge"
    />
  );
}
