/**
 * Story 2.7 (Task 6, AC5) — responsive layout-mode hook for the tri-pane shell.
 *
 * Returns the coarse layout mode the `AppShell` switches on: `desktop` keeps the
 * full inline tri-pane; `tablet` moves the right context panel into a drawer;
 * `mobile` collapses to a single artifact-first column with both side panels in
 * drawers.
 *
 * SCOPE (story 2.7 ↔ 2.26 split — see `src/features/workflows/LAYOUT.md`): this is
 * the MINIMAL hook the shell needs to function across breakpoints. Story 2.26
 * ("Responsive Design") OWNS the full breakpoint matrix, the `RESPONSIVE.md` ADR,
 * the named Tailwind breakpoint aliases, and the exhaustive test matrix — it
 * HARDENS this hook in place. Do not pre-empt that here. (Same posture story 2.6
 * took standing up the minimal Vitest runner that 2.27 extends.)
 *
 * Breakpoints — UX "Breakpoint Strategy" (ux-design-specification.md:2092-2106):
 *   mobile 320-767px · tablet 768-1023px · desktop ≥1024px.
 * These deliberately align with Tailwind's default `md` (768px) / `lg` (1024px)
 * so story 2.26 can formalize the aliases without moving the boundaries.
 */
import { useEffect, useState } from 'react';

export type ResponsiveLayout = 'mobile' | 'tablet' | 'desktop';

/** Tablet floor / desktop floor in CSS pixels. */
const TABLET_MIN_PX = 768;
const DESKTOP_MIN_PX = 1024;

const TABLET_QUERY = `(min-width: ${TABLET_MIN_PX}px) and (max-width: ${DESKTOP_MIN_PX - 1}px)`;
const DESKTOP_QUERY = `(min-width: ${DESKTOP_MIN_PX}px)`;

/** True only in a DOM environment that actually implements `matchMedia`. */
function canMatchMedia(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function';
}

/**
 * Resolve the current layout mode from `matchMedia`. SSR-safe: with no DOM (or no
 * `matchMedia` — e.g. the data-layer Vitest config) it defaults to `desktop`, so
 * the shell renders the full tri-pane rather than a degraded collapsed view.
 */
function readLayout(): ResponsiveLayout {
  if (!canMatchMedia()) {
    return 'desktop';
  }
  if (window.matchMedia(DESKTOP_QUERY).matches) {
    return 'desktop';
  }
  if (window.matchMedia(TABLET_QUERY).matches) {
    return 'tablet';
  }
  return 'mobile';
}

/**
 * Subscribe to viewport changes and report the current {@link ResponsiveLayout}.
 * Re-renders the consumer when the mode crosses a breakpoint boundary.
 */
export function useResponsiveLayout(): ResponsiveLayout {
  const [layout, setLayout] = useState<ResponsiveLayout>(readLayout);

  useEffect(() => {
    if (!canMatchMedia()) {
      return;
    }
    const queries = [window.matchMedia(TABLET_QUERY), window.matchMedia(DESKTOP_QUERY)];
    const onChange = (): void => {
      setLayout(readLayout());
    };
    // Re-sync once after mount: the initial `useState` ran during the first
    // render, but the viewport may have changed before effects flushed.
    onChange();
    for (const query of queries) {
      query.addEventListener('change', onChange);
    }
    return () => {
      for (const query of queries) {
        query.removeEventListener('change', onChange);
      }
    };
  }, []);

  return layout;
}
