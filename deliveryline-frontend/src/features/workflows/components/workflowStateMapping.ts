/**
 * Story 2.16 (AC2.b) — the shared backend-state → presentation mapping behind
 * {@link WorkflowStateBadge}. Kept in a sibling NON-component module (per the AC's
 * "or a sibling map") so story 2.15's `RunReviewQueueItem` imports the SAME source,
 * and so the badge component file exports only components (react-refresh hygiene).
 */
import {
  Ban,
  CircleCheck,
  CircleDot,
  FilePen,
  History,
  Inbox,
  Info,
  LoaderCircle,
  Lock,
  OctagonX,
  RotateCcw,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';

import { STATE_SIGNIFIERS, type StateIconName, type StateName } from '@/lib/state-signifiers';

/**
 * Backend `currentState` enum → semantic `StateName` (Dev Notes mapping table).
 * The enum is the route's `RECOGNIZED_STATES`; an unrecognized/`undefined` state
 * falls back to neutral `informational` (mirrors the route's AC8a defensiveness).
 */
const BACKEND_STATE_TO_STATE_NAME: Record<string, StateName> = {
  Inbox: 'informational',
  Planned: 'informational',
  Investigating: 'draft',
  Executing: 'informational',
  WaitingForSpecApproval: 'warning',
  WaitingForReview: 'warning',
  // Story 3f-3 (AC8 / Task 2) — a run parked on unmet run-dependency prerequisites renders as an
  // explicit blocked state, never the generic fallback. Filling the two pre-existing omissions
  // (Split / WaitingForManualExecution) at the same time so every real backend state maps
  // deliberately instead of silently degrading to `informational`.
  WaitingForManualExecution: 'warning',
  WaitingForDependencies: 'blocker',
  Split: 'informational',
  Completed: 'success',
  Failed: 'error',
  Paused: 'warning',
  TakenOver: 'recovery',
  Reconciled: 'recovery',
};

export function backendStateToStateName(state: string | undefined): StateName {
  if (state === undefined) {
    return 'informational';
  }
  return BACKEND_STATE_TO_STATE_NAME[state] ?? 'informational';
}

/**
 * Literal Tailwind class strings per state (purge-safe — MUST NOT be built via
 * `bg-state-${name}`, which the content scanner cannot see). Mirrors the badge
 * palette in the dev `PrimitivesPlayground`.
 */
export const STATE_CHIP_CLASSES: Record<StateName, string> = {
  informational:
    'bg-state-informational text-state-informational-foreground border-state-informational-border',
  success: 'bg-state-success text-state-success-foreground border-state-success-border',
  warning: 'bg-state-warning text-state-warning-foreground border-state-warning-border',
  blocker: 'bg-state-blocker text-state-blocker-foreground border-state-blocker-border',
  draft: 'bg-state-draft text-state-draft-foreground border-state-draft-border',
  selected: 'bg-state-selected text-state-selected-foreground border-state-selected-border',
  loading: 'bg-state-loading text-state-loading-foreground border-state-loading-border',
  error: 'bg-state-error text-state-error-foreground border-state-error-border',
  'permission-restricted':
    'bg-state-permission-restricted text-state-permission-restricted-foreground border-state-permission-restricted-border',
  empty: 'bg-state-empty text-state-empty-foreground border-state-empty-border',
  stale: 'bg-state-stale text-state-stale-foreground border-state-stale-border',
  recovery: 'bg-state-recovery text-state-recovery-foreground border-state-recovery-border',
};

/** Resolve a `STATE_SIGNIFIERS` icon NAME to its lucide component. */
const STATE_ICON_COMPONENTS: Record<StateIconName, LucideIcon> = {
  Info,
  CircleCheck,
  TriangleAlert,
  Ban,
  FilePen,
  CircleDot,
  LoaderCircle,
  OctagonX,
  Lock,
  Inbox,
  History,
  RotateCcw,
};

export function stateIconComponent(name: StateName): LucideIcon {
  return STATE_ICON_COMPONENTS[STATE_SIGNIFIERS[name].icon];
}
