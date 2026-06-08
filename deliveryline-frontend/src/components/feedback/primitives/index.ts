/**
 * Story 2.21 — feedback-primitives barrel.
 *
 * The shared UX-DR15 feedback toolkit: in-region status (`<InlineFeedback>`),
 * persistent outcome badge (`<PersistentStateBadge>`), lifecycle chain
 * (`<ActionLifecycleIndicator>`), and the ancillary-only `feedbackToast` wrapper.
 * Re-exported from `@/components/feedback` (`export * from './primitives'`).
 */
export {
  ActionLifecycleIndicator,
  type ActionLifecycleIndicatorProps,
} from './ActionLifecycleIndicator';
export {
  DEFAULT_LIFECYCLE_STAGES,
  resolveLifecyclePosition,
  lifecycleStageState,
  type LifecyclePosition,
  type LifecycleStageState,
} from './actionLifecycle';

export {
  InlineFeedback,
  type InlineFeedbackProps,
  type InlineFeedbackVariant,
  type InlineFeedbackPersistence,
  type StaleStateExplanation,
} from './InlineFeedback';

export {
  PersistentStateBadge,
  type PersistentStateBadgeProps,
  type SemanticState,
} from './PersistentStateBadge';

export {
  feedbackToast,
  emitFeedbackToast,
  FEEDBACK_TOAST_POSITION,
  type FeedbackToastVariant,
} from './feedbackToast';
