/**
 * Story 2.4 — adaptive density convention (AC7, UX-DR density rules ux:896-904).
 *
 * Composites (Queue Item 2.15, ARP 2.17, Run Context Strip 2.16) expose a
 * `density: 'compact' | 'standard'` prop. `compact` uses the 4px spacing step
 * (dense scanning rows / metadata blocks); `standard` uses the 8px step
 * (medium-density default for sustained reading). This shared helper maps the
 * density to a LITERAL Tailwind gap class so composites don't re-derive it and
 * Tailwind's content-purge keeps the utilities (no dynamic class strings).
 *
 * Generic / domain-free by construction — no `src/features/**` imports.
 */

/** Adaptive density mode applied by composites. */
export type Density = 'compact' | 'standard';

/** compact → 4px step (`gap-1`); standard → 8px step (`gap-2`). Literal classes. */
const DENSITY_GAP: Record<Density, 'gap-1' | 'gap-2'> = {
  compact: 'gap-1',
  standard: 'gap-2',
};

/**
 * Resolve a density to its canonical internal-gap Tailwind class.
 * `Record<Density, …>` indexed by `Density` is total, so this is safe under
 * `noUncheckedIndexedAccess` (no `| undefined`).
 */
export function densityGap(density: Density): 'gap-1' | 'gap-2' {
  return DENSITY_GAP[density];
}
