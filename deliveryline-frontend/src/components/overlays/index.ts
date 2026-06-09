/**
 * Story 2.23 — overlay-primitives barrel.
 *
 * The four shared overlay variants (UX-DR18): a confirm/cancel dialog, a
 * rationale-capture dialog, a bounded secondary-detail sheet, and a
 * non-dismissible critical-warning overlay — each wrapping the existing shadcn
 * `Dialog`/`Sheet` (radix) primitive, never a new portal/host.
 *
 * T-LAYERING: generic infrastructure (architecture.md:1182) — consumes only
 * generic libs, never `@/features/workflows/**`.
 */
export { ConfirmationDialog, type ConfirmationDialogProps } from './ConfirmationDialog';
export {
  RationaleCaptureDialog,
  type RationaleCaptureDialogProps,
  type RationaleField,
  type RationaleFieldOption,
  type RationaleValues,
} from './RationaleCaptureDialog';
export { BoundedDetailSheet, type BoundedDetailSheetProps } from './BoundedDetailSheet';
export {
  NonDismissibleCriticalWarning,
  type NonDismissibleCriticalWarningProps,
} from './NonDismissibleCriticalWarning';
export {
  overlayIntentIcon,
  overlayIntentPresentation,
  OVERLAY_INTENTS,
  OVERLAY_INTENT_TO_STATE,
  type OverlayIntent,
  type OverlayIntentPresentation,
} from './overlayPresentation';
