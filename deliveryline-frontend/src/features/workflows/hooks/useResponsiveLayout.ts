/**
 * Responsive layout-mode hook for the tri-pane shell (story 2.7, AC5; hardened by
 * story 2.26, AC8).
 *
 * Returns the coarse layout mode the `AppShell` switches on: `desktop` keeps the
 * full inline tri-pane; `tablet` moves the right context panel into a drawer;
 * `mobile` collapses to a single artifact-first column with both side panels in
 * drawers.
 *
 * THIS IS THE CANONICAL breakpoint hook — the single source of breakpoint logic
 * for the whole frontend (AC8). Breakpoint-conditional rendering routes through it
 * (or Tailwind responsive utilities backed by the same `md`/`lg` boundaries); no
 * composite scatters its own ad-hoc `window.matchMedia` / CSS media query. The
 * collapse contract it drives is documented in
 * `src/features/workflows/RESPONSIVE.md` (the responsive ADR) — its structural-shell
 * sibling is `LAYOUT.md`.
 *
 * Breakpoints — UX "Breakpoint Strategy" (ux-design-specification.md:2092-2106):
 *   mobile 320-767px · tablet 768-1023px · desktop ≥1024px.
 * These align EXACTLY with Tailwind's default `md` (768px) / `lg` (1024px) and the
 * named `tablet`/`desktop` aliases in `tailwind.config.ts`; the boundaries are fixed
 * (story 2.26 D3 — moving them silently re-flows every responsive utility).
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
