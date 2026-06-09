/**
 * Story 2.23 (AC1, AC5) — overlay `intent` → non-color-signifier presentation.
 *
 * The three overlay intents (`danger` / `warning` / `info`) map onto the
 * canonical 12-state {@link StateName} union so the icon + label + token color
 * come from the SAME signifier contract every other composite consumes (story
 * 2.3 AC5 — never color alone):
 *   danger → `blocker` (red, DOMINANT)   · warning → `warning` (amber, DOMINANT)
 *   info   → `informational` (recessive)
 *
 * T-LAYERING: `src/components/overlays/` is GENERIC infrastructure
 * (architecture.md:1182) and MUST NOT import from `src/features/workflows/**`.
 * The feature layer already has an icon+label idiom (`StateSignifierChip`), but
 * importing it here would invert the layering — so this module MIRRORS that
 * rendering using ONLY the generic `@/lib/state-signifiers` contract (the same
 * `STATE_SIGNIFIERS` lucide icon names + the same `--state-{name}` token classes).
 *
 * T-NO-PARALLEL-UNION: `intent` resolves onto `StateName`; it does NOT mint a
 * parallel state union.
 *
 * T-REFRESH: pure `.ts` sibling (non-literal `const` maps + helpers may NOT sit
 * beside a component under `react-refresh/only-export-components`).
 */
import { Ban, Info, TriangleAlert, type LucideIcon } from 'lucide-react';

import { STATE_SIGNIFIERS, type StateName } from '@/lib/state-signifiers';

/** The three overlay intents (UX-DR18). Each maps onto a canonical {@link StateName}. */
export type OverlayIntent = 'danger' | 'warning' | 'info';

/** Ordered list form of {@link OverlayIntent} for iteration (galleries, tests). */
export const OVERLAY_INTENTS: readonly OverlayIntent[] = ['danger', 'warning', 'info'];

/** `intent` → canonical {@link StateName}. */
export const OVERLAY_INTENT_TO_STATE: Record<OverlayIntent, StateName> = {
  danger: 'blocker',
  warning: 'warning',
  info: 'informational',
};

/**
 * Literal `--state-{name}` text-tone classes per intent (purge-safe — MUST NOT be
 * built via `text-state-${name}`, which the Tailwind content scanner cannot see).
 * Only the icon/label tone is coloured; the dialog surface stays neutral so the
 * consequence text reads at full contrast.
 */
const INTENT_TONE_CLASSES: Record<OverlayIntent, string> = {
  danger: 'text-state-blocker-foreground',
  warning: 'text-state-warning-foreground',
  info: 'text-state-informational-foreground',
};

/** Resolve an intent to its lucide icon component (mirrors the generic signifier map). */
const INTENT_ICON: Record<OverlayIntent, LucideIcon> = {
  danger: Ban,
  warning: TriangleAlert,
  info: Info,
};

/** The resolved presentation for an overlay intent. */
export interface OverlayIntentPresentation {
  /** The canonical semantic state the intent maps onto. */
  readonly stateName: StateName;
  /** The `STATE_SIGNIFIERS` lucide icon NAME (e.g. `'Ban'`). */
  readonly iconName: string;
  /** The human label paired with the icon (never color alone). */
  readonly label: string;
  /** The literal `--state-{name}` text-tone class for the icon + label. */
  readonly toneClass: string;
}

/** The lucide icon component for an overlay intent (never color alone — AC5). */
export function overlayIntentIcon(intent: OverlayIntent): LucideIcon {
  return INTENT_ICON[intent];
}

/** Resolve an overlay intent to its full non-color-signifier presentation. */
export function overlayIntentPresentation(intent: OverlayIntent): OverlayIntentPresentation {
  const stateName = OVERLAY_INTENT_TO_STATE[intent];
  return {
    stateName,
    iconName: STATE_SIGNIFIERS[stateName].icon,
    label: STATE_SIGNIFIERS[stateName].label,
    toneClass: INTENT_TONE_CLASSES[intent],
  };
}
