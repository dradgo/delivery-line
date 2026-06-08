/**
 * Story 2.21 (AC1, AC9) — `feedbackToast`, the typed wrapper over `sonner`.
 *
 * ⚠️ ANCILLARY LIGHTWEIGHT CONFIRMATIONS ONLY. A toast is NEVER the sole record
 * of a workflow-significant outcome (UX-DR15 / T-TOAST-IS-ANCILLARY). Workflow
 * truth — "answer incorporated", "decision recorded", "run blocked/failed" —
 * lives in `<InlineFeedback>` / `<PersistentStateBadge>` placed in the region
 * where the user acted. The `no-workflow-toast-success` ESLint rule MECHANICALLY
 * enforces this: in any file importing a workflow-mutation hook, a
 * `feedbackToast.success/warning/error` (or a bare/`.success` sonner `toast`) is
 * an error; only `feedbackToast.info` (ancillary) is allowed there.
 *
 * T-REFRESH: pure `.ts` function map (not a component) — no JSX render needed
 * for ancillary confirmations (OQ-2). T-NO-SECOND-TOASTER: this calls the
 * already-mounted `<Toaster>` (AppShell.tsx); it does NOT mount another host.
 */
import { toast } from 'sonner';

export { FEEDBACK_TOAST_POSITION } from '@/components/ui/sonnerConfig';

/** Sonner's per-call options type (description, duration, action, …). */
type FeedbackToastOptions = Parameters<typeof toast.success>[1];

/**
 * AC9 / OQ-5 — the documented `<Toaster>` position. `top-right` keeps toasts away
 * from the bottom-anchored primary action controls (e.g. the sticky-footer
 * ApprovalDecisionBar, 2.19), so a confirmation never covers the buttons the user
 * is acting on. Set on the single `ui/sonner.tsx` host; re-exported here as the
 * documented value AC9's positioning assertion reads. Full mobile-breakpoint
 * validation is deferred to story 2.26.
 */
/** Design-system defaults applied to every ancillary toast. */
const FEEDBACK_TOAST_DEFAULTS: FeedbackToastOptions = {
  duration: 4000,
};

/** The ancillary toast levels this wrapper exposes. */
export type FeedbackToastVariant = 'info' | 'success' | 'warning' | 'error';

/**
 * Dispatch one ancillary toast at the given level with design-system defaults.
 *
 * Defensive default branch (logging instrumentation): if a caller ever dispatches
 * an unknown/empty variant, we log the single anomaly line
 * `feedback.toastSuppressedFallback` — KEY ONLY (`event` + `variant`), NEVER the
 * `message` text (it may carry composite- or runner-supplied content) — and fall
 * back to a base toast so the confirmation is never silently dropped.
 */
export function emitFeedbackToast(
  variant: string,
  message: string,
  opts?: FeedbackToastOptions,
): void {
  const merged: FeedbackToastOptions = { ...FEEDBACK_TOAST_DEFAULTS, ...opts };
  switch (variant) {
    case 'info':
      toast.info(message, merged);
      return;
    case 'success':
      toast.success(message, merged);
      return;
    case 'warning':
      toast.warning(message, merged);
      return;
    case 'error':
      toast.error(message, merged);
      return;
    default:
      console.warn({ event: 'feedback.toastSuppressedFallback', variant });
      toast(message, merged);
  }
}

/**
 * The typed ancillary-confirmation toast map. Use `feedbackToast.info(...)` for
 * lightweight confirmations; reach for `<InlineFeedback>`/`<PersistentStateBadge>`
 * for anything a reviewer must still see after the toast auto-dismisses.
 */
export const feedbackToast = {
  info: (message: string, opts?: FeedbackToastOptions): void =>
    emitFeedbackToast('info', message, opts),
  success: (message: string, opts?: FeedbackToastOptions): void =>
    emitFeedbackToast('success', message, opts),
  warning: (message: string, opts?: FeedbackToastOptions): void =>
    emitFeedbackToast('warning', message, opts),
  error: (message: string, opts?: FeedbackToastOptions): void =>
    emitFeedbackToast('error', message, opts),
};
