/**
 * Story 2.27 (Task 6, AC9) — keyboard-only critical-journey coverage: the executable,
 * real-browser form of story 2.25 AC3's keyboard-operability contract (the jsdom
 * `expectTabReachesAll` helper is the unit-level analogue).
 *
 * HARD RULE: these specs use ONLY the keyboard — Tab / Shift+Tab / Enter / Space /
 * Escape — and make ZERO `.click()` / `.tap()` calls. If any decision surface becomes
 * unreachable without a pointer, the `tabUntilFocused` helper exhausts its budget and
 * the test fails — which is exactly the regression AC9 guards against.
 */
import { test, expect, type Page } from '@playwright/test';

import { mockBackend, HAPPY_RUN_ID } from './support/mockApi';

/**
 * Press Tab (or Shift+Tab) up to `max` times until the element matching `selector`
 * holds focus. Returns true once focused; false if the budget is exhausted (i.e. the
 * target is NOT keyboard-reachable — an AC9 failure).
 */
async function tabUntilFocused(page: Page, selector: string, max = 50): Promise<boolean> {
  for (let i = 0; i < max; i++) {
    const focused = await page.evaluate(
      (sel) => document.activeElement?.matches(sel) ?? false,
      selector,
    );
    if (focused) return true;
    await page.keyboard.press('Tab');
  }
  return page.evaluate((sel) => document.activeElement?.matches(sel) ?? false, selector);
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

test('AC9 — the queue → run → spec-read journey is fully keyboard-operable', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByTestId('run-review-queue-item').first()).toBeVisible();

  // Reach the happy-path run's open-link with Tab alone, then activate with Enter.
  const runLink = `a[href="/workflows/${HAPPY_RUN_ID}"]`;
  expect(await tabUntilFocused(page, runLink)).toBe(true);
  await page.keyboard.press('Enter');

  // On the run detail, the decision surface is present and the artifact link is
  // reachable by keyboard.
  await expect(page.getByRole('region', { name: 'Run context' })).toBeVisible();
  await expect(page.getByTestId('approval-decision-bar')).toBeVisible();

  const artifactLink = `a[href^="/workflows/${HAPPY_RUN_ID}/artifacts/"]`;
  expect(await tabUntilFocused(page, artifactLink)).toBe(true);
  await page.keyboard.press('Enter');

  // The spec artifact viewer is reached — no pointer was used anywhere above.
  await expect(page.getByText('art_sample0001')).toBeVisible();
  await expect(page.getByTestId('approval-decision-bar')).toBeVisible();
});
