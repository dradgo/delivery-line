/**
 * Story 2.4 — closed gap-token union → literal Tailwind classes (Q3, AC5 layout
 * prop API). Tailwind's content scanner cannot see dynamically-constructed class
 * strings (`` `gap-${n}` `` is purged), so layout primitives index this static
 * `Record` instead. The union also ENFORCES the 4px/8px hybrid scale: only
 * on-grid steps are expressible (no arbitrary numeric / inline-style spacing).
 *
 * 4px step → '0.5' | '1' | '1.5' | '2.5' (control internals, compact/dense rows).
 * 8px step → '2' | '4' | '6' | '8'        (panel spacing, section separation).
 */
export type GapToken = '0' | '0.5' | '1' | '1.5' | '2' | '2.5' | '3' | '4' | '6' | '8';

/** Static lookup so Tailwind emits every utility (purge-safe). */
export const GAP_CLASS: Record<GapToken, string> = {
  '0': 'gap-0',
  '0.5': 'gap-0.5',
  '1': 'gap-1',
  '1.5': 'gap-1.5',
  '2': 'gap-2',
  '2.5': 'gap-2.5',
  '3': 'gap-3',
  '4': 'gap-4',
  '6': 'gap-6',
  '8': 'gap-8',
};
