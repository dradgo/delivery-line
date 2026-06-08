/**
 * Story 2.21 — shared non-color-signifier presentation for the feedback
 * primitives (`<InlineFeedback>`, `<PersistentStateBadge>`).
 *
 * T-LAYERING: `src/components/feedback/` is GENERIC infrastructure
 * (architecture.md:1182) and MUST NOT import from `src/features/workflows/**`.
 * The feature layer already has an icon+label idiom (`StateSignifierChip` +
 * `workflowStateMapping`), but importing it here would invert the layering. So
 * this module MIRRORS that rendering using ONLY the generic
 * `@/lib/state-signifiers` contract — the same `STATE_SIGNIFIERS` lucide icon
 * names + the same `--state-{name}` token class strings.
 *
 * T-REFRESH: this is a pure `.ts` sibling (icon-component map + token-class map
 * are non-literal `const`s that may NOT live beside a component under
 * `react-refresh/only-export-components` — `frontend-react-refresh-no-fn-exports`).
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
 * `SemanticState` is an ALIAS of the canonical 12-state union, NOT a parallel
 * union (T-NO-PARALLEL-UNION). One source of truth for the signifier contract.
 */
export type SemanticState = StateName;

/**
 * The five `<InlineFeedback>` prominence variants. Each maps onto a canonical
 * {@link StateName} so the icon + label + token color come from the SAME
 * signifier contract every other composite consumes (story 2.3 AC5).
 */
export type InlineFeedbackVariant = 'info' | 'success' | 'warning' | 'blocker' | 'error';

/** `<InlineFeedback>` variant → canonical {@link StateName}. */
export const INLINE_VARIANT_TO_STATE: Record<InlineFeedbackVariant, StateName> = {
  info: 'informational',
  success: 'success',
  warning: 'warning',
  blocker: 'blocker',
  error: 'error',
};

/**
 * UX-DR15 prominence tier — `warning`/`blocker`/`error` are "visually stronger"
 * than informational feedback. Exposed as a `data-prominence` stamp so the
 * stronger treatment is assertable without pixel diffing (2.27 owns snapshots).
 */
export const DOMINANT_VARIANTS: ReadonlySet<InlineFeedbackVariant> = new Set([
  'warning',
  'blocker',
  'error',
]);

/** Resolve a `STATE_SIGNIFIERS` icon NAME to its lucide component (mirrors the
 * feature-layer map — same 12 entries, generic source). */
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

/** The lucide icon component for a semantic state (never color alone — AC6). */
export function stateIconComponent(state: StateName): LucideIcon {
  return STATE_ICON_COMPONENTS[STATE_SIGNIFIERS[state].icon];
}

/** The human label paired with the icon for a semantic state. */
export function stateLabel(state: StateName): string {
  return STATE_SIGNIFIERS[state].label;
}

/**
 * Literal `--state-{name}` token class strings per state (purge-safe — MUST NOT
 * be built via `bg-state-${name}`, which the Tailwind content scanner cannot
 * see). Mirrors the feature-layer `STATE_CHIP_CLASSES` so the generic primitives
 * carry the identical palette without importing the feature module (T-LAYERING).
 */
export const STATE_TONE_CLASSES: Record<StateName, string> = {
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

/** The `--state-{name}` token classes for a semantic state. */
export function stateToneClasses(state: StateName): string {
  return STATE_TONE_CLASSES[state];
}
