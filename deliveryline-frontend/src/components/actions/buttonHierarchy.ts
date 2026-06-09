/**
 * Story 2.23 (AC7, AC8, AC9) — governed button-hierarchy presentation contract.
 *
 * UX-DR19 "one primary action per decision area"; buttons reflect workflow truth
 * (`ready · blocked · stale · submitting · completed`). This module holds the
 * LOCAL presentation unions + the maps that `<GovernedButton>` consumes.
 *
 * T-NO-PARALLEL-UNION-DOMAIN: `ButtonWorkflowState` is a LOCAL 5-value
 * presentation union — it is NOT the 2.19 domain `workflowState` view-model from
 * `src/features/workflows/**` (do NOT import or fork it), and it is NOT
 * `StateName` either. `priority`, by contrast, maps onto the generic shadcn
 * `Button` `variant`.
 *
 * T-REFRESH: pure `.ts` sibling (non-literal `const` maps + helpers may NOT sit
 * beside a component under `react-refresh/only-export-components`).
 */
import type { ButtonProps } from '@/components/ui/button';

/** The five governed action priorities (UX-DR19). */
export type ButtonPriority = 'primary' | 'secondary' | 'tertiary' | 'destructive' | 'blocked';

/** The five workflow-truth states a governed button can reflect (UX-DR19). */
export type ButtonWorkflowState = 'ready' | 'blocked' | 'stale' | 'submitting' | 'completed';

/** Ordered list forms for iteration (fixtures, galleries, tests). */
export const BUTTON_PRIORITIES: readonly ButtonPriority[] = [
  'primary',
  'secondary',
  'tertiary',
  'destructive',
  'blocked',
];

export const BUTTON_WORKFLOW_STATES: readonly ButtonWorkflowState[] = [
  'ready',
  'blocked',
  'stale',
  'submitting',
  'completed',
];

/** The generic shadcn `Button` variant union (the `priority` map target). */
type ButtonVariant = NonNullable<ButtonProps['variant']>;

/**
 * `priority` → shadcn `Button` `variant`. `blocked` has no interactive variant —
 * it renders a custom non-interactive blocked visual (AC8) — but the map stays
 * total so a new priority forces an entry here.
 */
export const PRIORITY_VARIANT: Record<ButtonPriority, ButtonVariant> = {
  primary: 'default',
  secondary: 'secondary',
  tertiary: 'ghost',
  destructive: 'destructive',
  blocked: 'outline',
};

/**
 * Literal `--state-{name}` token classes (purge-safe) for the non-`ready`
 * workflow treatments. `blocked` uses the DOMINANT blocker token (story 2.3);
 * `stale` uses the muted-amber stale token.
 */
export const BLOCKED_TONE_CLASS =
  'border-state-blocker-border bg-state-blocker text-state-blocker-foreground';
export const STALE_TONE_CLASS =
  'border-state-stale-border bg-state-stale text-state-stale-foreground';

/** A workflow state's documented visual + ARIA treatment. */
export interface WorkflowStatePresentation {
  /** The lucide icon NAME paired with the state (never color alone — AC9). */
  readonly iconName?: 'Ban' | 'History' | 'Check' | 'Loader2';
  /** `true` when the button announces `aria-busy` (submitting). */
  readonly ariaBusy: boolean;
  /** The `aria-live` politeness for an announced outcome (completed). */
  readonly ariaLive?: 'polite';
  /** `true` when the state must not respond to interaction (blocked / submitting). */
  readonly nonInteractive: boolean;
}

const WORKFLOW_STATE_PRESENTATION: Record<ButtonWorkflowState, WorkflowStatePresentation> = {
  ready: { ariaBusy: false, nonInteractive: false },
  blocked: { iconName: 'Ban', ariaBusy: false, nonInteractive: true },
  stale: { iconName: 'History', ariaBusy: false, nonInteractive: false },
  submitting: { iconName: 'Loader2', ariaBusy: true, nonInteractive: true },
  completed: { iconName: 'Check', ariaBusy: false, ariaLive: 'polite', nonInteractive: false },
};

/** Resolve a workflow state to its documented visual + ARIA treatment. */
export function workflowStatePresentation(state: ButtonWorkflowState): WorkflowStatePresentation {
  return WORKFLOW_STATE_PRESENTATION[state];
}
