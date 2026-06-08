/**
 * Story 2.21 (AC1, AC4, AC5, AC6) — `<InlineFeedback>`.
 *
 * An in-region status block (icon + label + message body) for feedback that
 * lives WHERE the user acted (UX-DR15: "the new state is visible in the same
 * region where the user acted"). Region-agnostic, presentational, query-free —
 * it renders wherever a composite mounts it, with NO portal/global host, so it
 * is "placed-inline by construction" (AC4).
 *
 * Accessibility (AC6): `aria-live="polite"` for `info`/`success`,
 * `aria-live="assertive"` for `warning`/`blocker`/`error` (the dominant tier,
 * UX-DR15 "visually stronger"); `role="status"`/`role="alert"` to match. Every
 * variant carries a non-color signifier (icon + label from `STATE_SIGNIFIERS`).
 *
 * T-UNTRUSTED: `children` (and the stale-explanation nodes) are TRUSTED,
 * composite-authored content by default. If a caller must render runner/agent
 * text, it MUST sanitize via `@/lib/sanitization` BEFORE passing it in — this
 * primitive renders its children verbatim and writes no raw HTML.
 */
import type { ReactNode } from 'react';
import { X } from 'lucide-react';

import { cn } from '@/lib/utils';

import {
  DOMINANT_VARIANTS,
  INLINE_VARIANT_TO_STATE,
  stateIconComponent,
  stateLabel,
  stateToneClasses,
  type InlineFeedbackVariant,
} from './feedbackStatePresentation';

export type { InlineFeedbackVariant } from './feedbackStatePresentation';

/**
 * Dismissal semantics (OQ-3) — this presentational primitive cannot observe
 * workflow state, so `persistsUntil` documents the CONTRACT, it does not wire a
 * query:
 *  - `'dismiss'`       → renders a dismiss control wired to `onDismiss`.
 *  - `'workflowChange'`→ renders no control; the consumer unmounts/replaces this
 *                        when the workflow advances (the 2.19 summary contract).
 *  - `'infinite'`      → renders no control; persists until the consumer removes it.
 */
export type InlineFeedbackPersistence = 'dismiss' | 'workflowChange' | 'infinite';

/**
 * The stale-state explanation (AC5) — a structured "what changed → what to do
 * next" pair. Rendered as two DISTINCT labeled segments, never concatenated
 * into one line. Both nodes are TRUSTED (T-UNTRUSTED).
 */
export interface StaleStateExplanation {
  readonly whatChanged: ReactNode;
  readonly whatToDoNext: ReactNode;
}

interface InlineFeedbackBaseProps {
  variant: InlineFeedbackVariant;
  /** Optional heading line above the body. */
  title?: ReactNode | undefined;
  /** Body content — TRUSTED, composite-authored (T-UNTRUSTED). */
  children?: ReactNode | undefined;
  /**
   * Structured "what changed → what to do next" (AC5). Only meaningful for
   * `variant="warning"` (stale state); ignored for other variants.
   */
  staleStateExplanation?: StaleStateExplanation | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export type InlineFeedbackProps = InlineFeedbackBaseProps &
  (
    | {
        persistsUntil: 'dismiss';
        /** Required because the dismiss control must always perform an action. */
        onDismiss: () => void;
      }
    | {
        persistsUntil: Exclude<InlineFeedbackPersistence, 'dismiss'>;
        onDismiss?: never;
      }
  );

/** Polite for benign info/success; assertive for the dominant warning/blocker/error tier. */
function ariaLiveFor(variant: InlineFeedbackVariant): 'polite' | 'assertive' {
  return DOMINANT_VARIANTS.has(variant) ? 'assertive' : 'polite';
}

export function InlineFeedback({
  variant,
  persistsUntil,
  title,
  children,
  staleStateExplanation,
  onDismiss,
  className,
  testId = 'inline-feedback',
}: InlineFeedbackProps) {
  const state = INLINE_VARIANT_TO_STATE[variant];
  const Icon = stateIconComponent(state);
  const isDominant = DOMINANT_VARIANTS.has(variant);
  const ariaLive = ariaLiveFor(variant);
  // AC5 — the stale explanation is only rendered for the warning (stale) variant.
  const stale = variant === 'warning' ? staleStateExplanation : undefined;

  return (
    <div
      data-testid={testId}
      data-inline-feedback-variant={variant}
      data-prominence={isDominant ? 'dominant' : 'informational'}
      role={ariaLive === 'assertive' ? 'alert' : 'status'}
      aria-live={ariaLive}
      className={cn(
        'flex items-start gap-2 rounded-md border p-3 text-sm',
        stateToneClasses(state),
        // UX-DR15 "visually stronger" — the dominant tier reads heavier.
        isDominant ? 'border-2 font-medium' : 'border',
        className,
      )}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-1.5">
          {/* The non-color text label (AC6) — never color alone. */}
          <span className="font-medium">{stateLabel(state)}</span>
          {title !== undefined && title !== null ? (
            <span className="text-text-secondary">{title}</span>
          ) : null}
        </div>
        {children !== undefined && children !== null ? (
          <div className="text-text-secondary">{children}</div>
        ) : null}
        {stale !== undefined ? (
          <dl data-stale-explanation className="mt-1 space-y-1">
            <div data-stale-segment="what-changed">
              <dt className="text-xs font-semibold uppercase tracking-wide text-text-tertiary">
                What changed
              </dt>
              <dd className="text-text-secondary">{stale.whatChanged}</dd>
            </div>
            <div data-stale-segment="what-to-do-next">
              <dt className="text-xs font-semibold uppercase tracking-wide text-text-tertiary">
                What to do next
              </dt>
              <dd className="text-text-secondary">{stale.whatToDoNext}</dd>
            </div>
          </dl>
        ) : null}
      </div>
      {persistsUntil === 'dismiss' ? (
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          data-inline-feedback-dismiss
          className="-mr-1 -mt-1 shrink-0 rounded p-1 text-text-tertiary hover:text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
        >
          <X className="size-4" aria-hidden />
        </button>
      ) : null}
    </div>
  );
}
