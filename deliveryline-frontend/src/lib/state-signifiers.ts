/**
 * Story 2.3 AC5 — non-color signifier map for the 12 semantic states.
 *
 * UX-DR2 "never state by color alone" (ux:815, 894): a state's color token must
 * ALWAYS be accompanied by a non-color signifier — an icon and a text label.
 * This is the single contract every workflow composite (2.15+) imports so the
 * pairing is guaranteed at the source. The colors themselves live in
 * `src/styles/globals.css` as `--state-{name}` tokens; this file maps each
 * `StateName` to its icon + label.
 *
 * `icon` is a `lucide-react` export name (string identifier) rather than a
 * component, so this module stays presentation-framework-agnostic and importable
 * by tooling/tests. Composites resolve the name to a component (see
 * PrimitivesPlayground for the canonical lookup).
 */

/**
 * The canonical 12-state union — the SINGLE source other stories (2.4, 2.15+)
 * import instead of re-typing the list. Must stay in sync with the
 * `--state-{name}` token groups in globals.css (enforced by the
 * state-signifiers.test.js parity gate).
 */
export type StateName =
  | 'informational'
  | 'success'
  | 'warning'
  | 'blocker'
  | 'draft'
  | 'selected'
  | 'loading'
  | 'error'
  | 'permission-restricted'
  | 'empty'
  | 'stale'
  | 'recovery';

/** Ordered list form of {@link StateName} for iteration (galleries, tests). */
export const STATE_NAMES: readonly StateName[] = [
  'informational',
  'success',
  'warning',
  'blocker',
  'draft',
  'selected',
  'loading',
  'error',
  'permission-restricted',
  'empty',
  'stale',
  'recovery',
];

/** A non-color signifier: a lucide-react icon name + a human label (and an
 * optional textual pattern hint for future use, e.g. striped/hatched fills). */
export interface StateSignifier {
  /** A `lucide-react` icon export name (e.g. `'TriangleAlert'`). */
  readonly icon: string;
  /** Short human-readable label shown alongside the color. */
  readonly label: string;
  /** Optional non-color/non-icon pattern hint (reserved for later composites). */
  readonly pattern?: string;
}

/**
 * Exhaustive map from every {@link StateName} to its signifier. Typed as
 * `Record<StateName, …>` so TypeScript fails the build if a state is missing —
 * adding a new state to the union forces a signifier entry here.
 */
export const STATE_SIGNIFIERS: Record<StateName, StateSignifier> = {
  informational: { icon: 'Info', label: 'Informational' },
  success: { icon: 'CircleCheck', label: 'Success' },
  warning: { icon: 'TriangleAlert', label: 'Warning' },
  blocker: { icon: 'Ban', label: 'Blocker' },
  draft: { icon: 'FilePen', label: 'Draft' },
  selected: { icon: 'CircleDot', label: 'Selected' },
  loading: { icon: 'LoaderCircle', label: 'Loading' },
  error: { icon: 'OctagonX', label: 'Error' },
  'permission-restricted': { icon: 'Lock', label: 'Restricted' },
  empty: { icon: 'Inbox', label: 'Empty' },
  stale: { icon: 'History', label: 'Stale' },
  recovery: { icon: 'RotateCcw', label: 'Recovery' },
};
