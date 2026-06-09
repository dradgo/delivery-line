/**
 * Story 2.23 (AC7, AC8, AC9, AC10) — `<GovernedButton>`.
 *
 * Composes the generic shadcn `Button` (T-UI-PURE: COMPOSE, never edit
 * `ui/button.tsx`) and layers the governed-workflow vocabulary on top:
 *   · `priority` (primary | secondary | tertiary | destructive | blocked) →
 *     a documented `Button` variant (AC7).
 *   · `priority="blocked"` OR `workflowState="blocked"` → a NON-interactive
 *     blocked visual on the `--state-blocker` token with a REQUIRED adjacent
 *     `blockedExplanation` (TS-narrowed, AC8) — never a plain disabled button.
 *   · `workflowState` reflects workflow truth (AC9): `submitting` → spinner +
 *     `aria-busy` + non-interactive (the sanctioned `<LoadingState>` spinner
 *     path, T-SPINNER-BOUNDARY); `completed` → checkmark + `aria-live` outcome
 *     that PERSISTS until the parent resets (AC10); `stale` → stale treatment.
 *
 * T-LAYERING: generic infra — imports only `@/components/ui/*`,
 * `@/components/feedback/*`, `@/lib/*`, and `lucide-react`. NEVER
 * `@/features/workflows/**`. `workflowState` here is the LOCAL presentation union
 * (`buttonHierarchy.ts`), NOT the 2.19 domain view-model.
 */
import { useId, type ReactNode } from 'react';
import { Ban, Check, History } from 'lucide-react';

import { cn } from '@/lib/utils';
import { STATE_SIGNIFIERS } from '@/lib/state-signifiers';
import { Button, type ButtonProps } from '@/components/ui/button';
import { LoadingState } from '@/components/feedback';

import {
  BLOCKED_TONE_CLASS,
  PRIORITY_VARIANT,
  STALE_TONE_CLASS,
  workflowStatePresentation,
  type ButtonPriority,
  type ButtonWorkflowState,
} from './buttonHierarchy';

/** Props shared by every governed-button shape (the generic `Button` surface). */
type GovernedButtonBaseProps = Omit<ButtonProps, 'variant'> & {
  testId?: string | undefined;
};

/**
 * The blocked shape requires `blockedExplanation` (AC8) — reached either by an
 * explicit `priority="blocked"` or by `workflowState="blocked"`. TypeScript
 * therefore rejects a blocked button with no adjacent explanation.
 */
type BlockedByPriorityProps = {
  priority: 'blocked';
  workflowState?: ButtonWorkflowState | undefined;
  blockedExplanation: string;
};
type BlockedByStateProps = {
  priority: Exclude<ButtonPriority, 'blocked'>;
  workflowState: 'blocked';
  blockedExplanation: string;
};
type UnblockedProps = {
  priority: Exclude<ButtonPriority, 'blocked'>;
  workflowState?: Exclude<ButtonWorkflowState, 'blocked'> | undefined;
  blockedExplanation?: string | undefined;
};

export type GovernedButtonProps = GovernedButtonBaseProps &
  (BlockedByPriorityProps | BlockedByStateProps | UnblockedProps);

/**
 * Computed via a function (not an inline alias) ON PURPOSE: it keeps TypeScript
 * from narrowing the props union through the boolean, so the defense-in-depth
 * `blockedExplanation` runtime guard below stays meaningful rather than being
 * flagged as an "unnecessary condition".
 */
function isBlockedPresentation(
  priority: ButtonPriority,
  workflowState: ButtonWorkflowState | undefined,
): boolean {
  return priority === 'blocked' || workflowState === 'blocked';
}

export function GovernedButton(props: GovernedButtonProps) {
  const {
    priority,
    workflowState,
    blockedExplanation,
    testId,
    className,
    children,
    disabled,
    ...rest
  } = props;

  const explanationId = useId();
  const isBlocked = isBlockedPresentation(priority, workflowState);

  // AC8 — non-interactive blocked visual on the DOMINANT blocker token, with a
  // REQUIRED adjacent explanation (visible text + aria-describedby). This is NOT
  // a `<button>` — no handler is attached, so it cannot be activated.
  if (isBlocked) {
    const hasExplanation = blockedExplanation !== undefined && blockedExplanation.trim() !== '';
    if (!hasExplanation) {
      // Defense-in-depth behind the TS narrowing (field-only structured warn —
      // never the children/explanation text). The ESLint rules + types are the
      // real contract; this only fires if the TS guard is bypassed.
      console.warn({ event: 'overlay.blockedButtonMissingExplanation', priority, workflowState });
    }
    return (
      <span
        data-governed-button=""
        data-priority={priority}
        data-workflow-state={workflowState}
        data-testid={testId}
        className={cn('inline-flex flex-col gap-1', className)}
      >
        <span
          data-blocked="true"
          aria-disabled="true"
          aria-describedby={hasExplanation ? explanationId : undefined}
          className={cn(
            'inline-flex w-fit items-center gap-2 rounded-md border px-4 py-2 text-sm font-medium',
            BLOCKED_TONE_CLASS,
          )}
        >
          <Ban className="size-4 shrink-0" aria-hidden />
          <span>{children}</span>
          {/* Non-color signifier label for screen readers (never color alone). */}
          <span className="sr-only">{STATE_SIGNIFIERS.blocker.label}</span>
        </span>
        {hasExplanation ? (
          <span
            id={explanationId}
            data-blocked-explanation=""
            className="text-meta text-text-tertiary"
          >
            {blockedExplanation}
          </span>
        ) : null}
      </span>
    );
  }

  const presentation =
    workflowState !== undefined ? workflowStatePresentation(workflowState) : undefined;
  const variant = PRIORITY_VARIANT[priority];

  let content: ReactNode = children;
  if (workflowState === 'submitting') {
    // T-SPINNER-BOUNDARY — the sanctioned feedback spinner path (no raw
    // animate-spin in `src/components/actions/`).
    content = <LoadingState variant="generatingArtifact" message={children} />;
  } else if (workflowState === 'completed') {
    // AC9/AC10 — outcome checkmark in an aria-live region; persists until the
    // parent re-renders to another state (no auto-clear here).
    content = (
      <span aria-live="polite" className="inline-flex items-center gap-2">
        <Check className="size-4 shrink-0" aria-hidden />
        {children}
      </span>
    );
  } else if (workflowState === 'stale') {
    content = (
      <span className="inline-flex items-center gap-2">
        <History className="size-4 shrink-0" aria-hidden />
        {children}
      </span>
    );
  }

  return (
    <Button
      variant={variant}
      disabled={disabled === true || presentation?.nonInteractive === true}
      aria-busy={presentation?.ariaBusy === true ? true : undefined}
      data-governed-button=""
      data-priority={priority}
      data-workflow-state={workflowState}
      data-testid={testId}
      // Story 2.25 (AC10) — 44px touch-target floor on the interactive button.
      className={cn('min-h-touch', workflowState === 'stale' && STALE_TONE_CLASS, className)}
      {...rest}
    >
      {content}
    </Button>
  );
}
