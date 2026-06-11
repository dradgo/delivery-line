/**
 * Story 2.27 (Task 6, AC8/AC9) — mobile-viewport critical-journey coverage: the
 * executable cross-browser/mobile E2E that story 2.26 explicitly DEFERRED here (its
 * D1 decision). At the Galaxy S23+-class viewport (project `mobile-galaxy-s23`,
 * ~360×780) the contract 2.26 pinned in jsdom is now proven on a real engine:
 *   • the sticky-footer primary decision surface stays reachable in-viewport;
 *   • the run identity + context strip stay visible;
 *   • the tri-pane collapses to a single column (panes stack, never side-by-side).
 *
 * The spec is project-agnostic (it reads the live viewport), so it also runs as a
 * harmless smoke on the desktop projects; the real mobile proof is the mobile project.
 */
import { test, expect } from '@playwright/test';

import { mockBackend, HAPPY_RUN_ID } from './support/mockApi';

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

/** The mobile/tablet breakpoint floor (useResponsiveLayout.ts TABLET_MIN_PX). */
const TABLET_MIN_PX = 768;

test('AC9 — run detail keeps the decision surface + run identity reachable on a phone', async ({
  page,
}) => {
  await page.goto(`/workflows/${HAPPY_RUN_ID}`);

  // Run identity + context strip are visible.
  await expect(page.getByRole('region', { name: 'Run context' })).toBeVisible();

  // The sticky-footer approval decision bar is visible AND inside the viewport
  // (reachable without horizontal scroll — the mobile structural contract).
  const decisionBar = page.getByTestId('approval-decision-bar');
  await expect(decisionBar).toBeVisible();

  // Review P3 — assert the viewport is present rather than silently skipping the
  // whole assertion block when it is null (the old `if (box && viewport)` guard hid
  // a misconfigured project as a green test).
  const viewport = page.viewportSize();
  expect(viewport, 'a fixed viewport is required for the layout assertions').not.toBeNull();
  const box = await decisionBar.boundingBox();
  expect(box).not.toBeNull();

  // The bar starts within the viewport's horizontal bounds (no off-canvas drift).
  // The +1 absorbs sub-pixel rounding in boundingBox() vs the integer viewport
  // width; it is NOT slack for real overflow (a bar off-canvas drifts by ≫1px).
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width + 1);

  // Review P3 — the tri-pane-stacks-to-one-column proof the file header promises.
  // Only on a mobile-range viewport does AppShell collapse to a single column (nav
  // rail + right context panel move into drawers, leaving `<main>` as the sole
  // inline pane — see AppShell.tsx). On desktop projects this spec still runs as a
  // reachability smoke, so the collapse assertion is gated to the mobile range.
  if (viewport!.width < TABLET_MIN_PX) {
    const main = page.getByRole('main');
    const mainBox = await main.boundingBox();
    expect(mainBox).not.toBeNull();
    // Single column: `<main>` is flush to the left edge (no nav rail beside it) and
    // spans the full viewport width (no right context panel taking horizontal room).
    // Same ±1 sub-pixel tolerance as above.
    expect(mainBox!.x).toBeLessThanOrEqual(1);
    expect(mainBox!.width).toBeGreaterThanOrEqual(viewport!.width - 1);
  }
});
