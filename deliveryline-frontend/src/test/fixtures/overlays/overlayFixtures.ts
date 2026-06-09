/**
 * Story 2.23 (AC12) — fixtures for the overlay + button-hierarchy variant
 * inventory. One fixture per overlay variant (confirmation danger/warning/info,
 * rationale-capture, bounded-detail-sheet right + bottom, non-dismissible-
 * critical) plus the governed-button variants (5 priorities, the workflowStates,
 * blocked-with-explanation, a single-primary DecisionArea example).
 *
 * Driven by BOTH the `PrimitivesPlayground` "Overlays + Button Hierarchy" section
 * and the fixture-driven RTL render tests (true pixel-diff snapshots are deferred
 * to story 2.27 — these assert roles/text/`data-*`, never `toMatchSnapshot`).
 *
 * Pure `.ts` (T-REFRESH) — plain data; bodies/labels are TRUSTED demo strings.
 */
import type { ButtonPriority, ButtonWorkflowState } from '@/components/actions';
import type { OverlayIntent, RationaleField } from '@/components/overlays';

/** A `<ConfirmationDialog>` variant fixture. */
export interface ConfirmationDialogFixture {
  readonly id: string;
  readonly label: string;
  readonly title: string;
  readonly intent: OverlayIntent;
  readonly consequence: string;
  readonly confirmLabel?: string;
  readonly cancelLabel?: string;
}

export const CONFIRMATION_DIALOG_FIXTURES: readonly ConfirmationDialogFixture[] = [
  {
    id: 'confirmation-danger',
    label: 'Confirmation · danger',
    title: 'Reject this specification?',
    intent: 'danger',
    consequence:
      'Rejecting sends the specification back for rework. The current version will no longer be the candidate for approval.',
    confirmLabel: 'Reject',
  },
  {
    id: 'confirmation-warning',
    label: 'Confirmation · warning',
    title: 'Approve a stale view?',
    intent: 'warning',
    consequence:
      'This view may be out of date. Approving anyway advances the run on the version you are viewing.',
    confirmLabel: 'Approve anyway',
  },
  {
    id: 'confirmation-info',
    label: 'Confirmation · info',
    title: 'Continue to compare?',
    intent: 'info',
    consequence: 'You can return to this review at any time without losing context.',
    confirmLabel: 'Continue',
  },
] as const;

/** A `<RationaleCaptureDialog>` fixture (the reject-with-reason shape). */
export interface RationaleCaptureFixture {
  readonly id: string;
  readonly label: string;
  readonly title: string;
  readonly intent: OverlayIntent;
  readonly consequence: string;
  readonly fields: readonly RationaleField[];
  readonly confirmLabel?: string;
}

export const RATIONALE_CAPTURE_FIXTURE: RationaleCaptureFixture = {
  id: 'rationale-capture-reject',
  label: 'Rationale capture · reject with reason',
  title: 'Reject with a reason',
  intent: 'danger',
  consequence:
    'Your reason is sent back with the rejection so the next attempt can address it. This cannot be undone.',
  confirmLabel: 'Reject',
  fields: [
    {
      name: 'category',
      label: 'Reason category',
      type: 'select',
      required: true,
      placeholder: 'Choose a category',
      options: [
        { value: 'incomplete', label: 'Incomplete' },
        { value: 'incorrect', label: 'Incorrect' },
        { value: 'out-of-scope', label: 'Out of scope' },
      ],
    },
    {
      name: 'detail',
      label: 'What needs to change',
      type: 'textarea',
      required: true,
      placeholder: 'Describe what the next attempt must address…',
      validate: (value) =>
        value.trim().length < 10 ? 'Add at least 10 characters of detail.' : undefined,
    },
  ],
};

/** A `<BoundedDetailSheet>` fixture. */
export interface BoundedDetailSheetFixture {
  readonly id: string;
  readonly label: string;
  readonly title: string;
  readonly description?: string;
  readonly side: 'right' | 'bottom';
  readonly fullHeightOnMobile?: boolean;
  readonly body: string;
}

export const BOUNDED_DETAIL_SHEET_FIXTURES: readonly BoundedDetailSheetFixture[] = [
  {
    id: 'bounded-detail-right',
    label: 'Bounded detail · right',
    title: 'Run context',
    description: 'Secondary detail for the run you are reviewing.',
    side: 'right',
    body: 'Bounded supporting detail. Closing returns you to the same review context without reset.',
  },
  {
    id: 'bounded-detail-bottom',
    label: 'Bounded detail · bottom (full-height)',
    title: 'Run context',
    description: 'Full-height bottom sheet for narrow screens.',
    side: 'bottom',
    fullHeightOnMobile: true,
    body: 'The full-height slide-up sheet pattern (UX-DR18) for breakpoints where a dialog gets cramped.',
  },
] as const;

/** A `<NonDismissibleCriticalWarning>` fixture. */
export interface CriticalWarningFixture {
  readonly id: string;
  readonly label: string;
  readonly title: string;
  readonly body: string;
  readonly acknowledgmentLabel: string;
  readonly intent?: OverlayIntent;
}

export const CRITICAL_WARNING_FIXTURE: CriticalWarningFixture = {
  id: 'non-dismissible-critical',
  label: 'Non-dismissible · critical',
  title: 'Run irrecoverably stopped',
  body: 'This run was stopped and cannot be resumed. Acknowledge to continue — Escape and clicking outside are intentionally disabled.',
  acknowledgmentLabel: 'I understand',
  intent: 'danger',
};

/** A `<GovernedButton>` variant fixture. */
export interface GovernedButtonFixture {
  readonly id: string;
  readonly label: string;
  readonly priority: ButtonPriority;
  readonly workflowState?: ButtonWorkflowState;
  readonly blockedExplanation?: string;
  readonly children: string;
}

export const GOVERNED_BUTTON_FIXTURES: readonly GovernedButtonFixture[] = [
  { id: 'btn-primary', label: 'Priority · primary', priority: 'primary', children: 'Approve' },
  {
    id: 'btn-secondary',
    label: 'Priority · secondary',
    priority: 'secondary',
    children: 'Compare',
  },
  { id: 'btn-tertiary', label: 'Priority · tertiary', priority: 'tertiary', children: 'Inspect' },
  {
    id: 'btn-destructive',
    label: 'Priority · destructive',
    priority: 'destructive',
    children: 'Reject',
  },
  {
    id: 'btn-blocked',
    label: 'Priority · blocked (with explanation)',
    priority: 'blocked',
    blockedExplanation: 'Incorporate open clarifications before approving.',
    children: 'Approve',
  },
  {
    id: 'btn-state-ready',
    label: 'workflowState · ready',
    priority: 'primary',
    workflowState: 'ready',
    children: 'Approve',
  },
  {
    id: 'btn-state-stale',
    label: 'workflowState · stale',
    priority: 'primary',
    workflowState: 'stale',
    children: 'Approve (stale)',
  },
  {
    id: 'btn-state-submitting',
    label: 'workflowState · submitting',
    priority: 'primary',
    workflowState: 'submitting',
    children: 'Approving',
  },
  {
    id: 'btn-state-completed',
    label: 'workflowState · completed',
    priority: 'primary',
    workflowState: 'completed',
    children: 'Approved',
  },
  {
    id: 'btn-state-blocked',
    label: 'workflowState · blocked (with explanation)',
    priority: 'secondary',
    workflowState: 'blocked',
    blockedExplanation: 'This action is unavailable until the upstream review completes.',
    children: 'Continue',
  },
] as const;

/** A single-primary `<DecisionArea>` example (AC7/AC11). */
export interface DecisionAreaFixture {
  readonly id: string;
  readonly label: string;
  readonly primaryLabel: string;
  readonly secondaryLabel: string;
  readonly tertiaryLabel: string;
}

export const DECISION_AREA_FIXTURE: DecisionAreaFixture = {
  id: 'decision-area-single-primary',
  label: 'Decision area · single primary + overflow',
  primaryLabel: 'Approve',
  secondaryLabel: 'Compare',
  tertiaryLabel: 'Inspect',
};
