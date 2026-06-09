/**
 * Story 2.25 (Task 2 — AC1) — shared keyboard-operability test helpers.
 *
 * AC1 requires every interactive composite/primitive to be fully keyboard
 * operable: Tab order matches visual (DOM) order and every action is reachable
 * without a mouse. These helpers drive `@testing-library/user-event` and assert
 * reachability so component tests stay terse and consistent.
 */
import { expect } from 'vitest';
import type { UserEvent } from '@testing-library/user-event';

/**
 * Natively-tabbable elements: links with hrefs, enabled form controls, and any
 * element with a non-negative explicit `tabindex`. Disabled controls and
 * `tabindex="-1"` (programmatic-only focus) are excluded, matching browser Tab
 * traversal.
 */
// `:not([aria-hidden="true"])` excludes the hidden native `<select aria-hidden>`
// fallback that Radix Select renders alongside its real (button-based) trigger —
// it is not part of the visible Tab order.
const TABBABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
]
  .map((sel) => `${sel}:not([aria-hidden="true"])`)
  .join(', ');

/** All natively-tabbable elements within `container`, in DOM (≈ visual) order. */
export function tabbableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(TABBABLE_SELECTOR));
}

/**
 * Tab forward through `container` and assert every tabbable element receives
 * focus in DOM order (AC1 — "Tab order matches visual order, every action
 * reachable without a mouse"). Fails if any element is skipped or out of order.
 */
export async function expectTabReachesAll(user: UserEvent, container: HTMLElement): Promise<void> {
  const expected = tabbableElements(container);
  expect(expected.length).toBeGreaterThan(0);
  for (const el of expected) {
    await user.tab();
    expect(document.activeElement).toBe(el);
  }
}
