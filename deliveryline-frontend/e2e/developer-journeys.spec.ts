/**
 * Story 3.35 (Task 4, AC7) — Epic-3 developer-journey E2E specs: the executable,
 * real-browser form of the implementation-review decision loop (story 3.28/3b-4)
 * and the recovery-retry loop (story 3.30), run across the same
 * chromium/firefox/webkit/msedge + Galaxy-S23 matrix as the story-2.27 journeys.
 *
 * HARD RULE (story 2.25 AC3): these journeys use ONLY the keyboard — Tab /
 * Shift+Tab / Enter / Space / Escape — and make ZERO `.click()` / `.tap()` calls.
 * If a decision surface becomes unreachable without a pointer, `tabUntilFocused`
 * exhausts its budget and the test fails — the regression AC7 guards against.
 *
 * Backend: driven entirely against the fixture-route mock (`e2e/support/mockApi.ts`),
 * which models two synthetic execution-stage runs (a `WaitingForReview` developer
 * review + a `Failed` recovery) and the accept/reject/takeover/retry mutations.
 * NEVER a live backend (story 1.23 / 2.27 discipline). `retries: 0`
 * (playwright.config.ts) — any flaky leg is quarantined with `test.fixme` +
 * justification (AC12), never silently retried.
 */
import { test, expect, type Locator, type Page } from '@playwright/test';

import { mockBackend, DEV_REVIEW_RUN_ID, RECOVERY_RUN_ID } from './support/mockApi';

/**
 * Tab (forward) up to `max` times until `target` holds focus. Compares the live
 * `document.activeElement` to the located element so it works for controls named by
 * accessible-name/text (not just CSS-matchable attributes). Returns false if the budget
 * is exhausted — i.e. the control is NOT keyboard-reachable (the AC7 failure).
 */
async function tabUntilFocused(page: Page, target: Locator, max = 60): Promise<boolean> {
  const isFocused = () => target.evaluate((el) => el === document.activeElement).catch(() => false);
  for (let i = 0; i < max; i++) {
    if (await isFocused()) return true;
    await page.keyboard.press('Tab');
  }
  return isFocused();
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

/** Open a synthetic run's detail page from the queue using the keyboard alone. */
async function openRunFromQueue(page: Page, runId: string) {
  await page.goto('/');
  await expect(page.getByTestId('run-review-queue-item').first()).toBeVisible();
  const runLink = page.locator(`a[href="/workflows/${runId}"]`);
  expect(
    await tabUntilFocused(page, runLink),
    `queue link for ${runId} must be keyboard-reachable`,
  ).toBe(true);
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('approval-decision-bar')).toBeVisible();
}

test.describe('Developer accept-implementation journey', () => {
  test('AC7 — queue → run → implementation-review bar → accept (keyboard-only)', async ({
    page,
  }) => {
    await openRunFromQueue(page, DEV_REVIEW_RUN_ID);

    // The Decision Bar renders the implementation_review mode for the WaitingForReview run.
    await expect(page.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'implementation_review',
    );

    // The Accept control is reachable by keyboard alone, then activated with Enter.
    const accept = page.getByRole('button', { name: 'Accept implementation' });
    expect(await tabUntilFocused(page, accept)).toBe(true);
    await page.keyboard.press('Enter');

    // The decision settles into the kept-alive success summary (state advanced to Executing).
    await expect(page.getByTestId('approval-decision-summary')).toBeVisible();
  });
});

test.describe('Developer reject-implementation journey', () => {
  test('AC7 — reject with rationale + taxonomy dialog (keyboard-only)', async ({ page }) => {
    await openRunFromQueue(page, DEV_REVIEW_RUN_ID);
    await expect(page.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'implementation_review',
    );

    // Open the rejection dialog with the keyboard.
    const reject = page.getByRole('button', { name: 'Reject with feedback' });
    expect(await tabUntilFocused(page, reject)).toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('approval-rejection-dialog')).toBeVisible();

    // Fill the required free-text rationale.
    const reason = page.getByTestId('approval-rejection-reason');
    expect(await tabUntilFocused(page, reason)).toBe(true);
    await page.keyboard.type('Approach diverges from the approved plan.');

    // Select a developer-taxonomy option (first radio) by keyboard.
    const firstRadio = page.getByRole('radio').first();
    expect(await tabUntilFocused(page, firstRadio)).toBe(true);
    await page.keyboard.press('Space');

    // Confirm the rejection.
    const confirm = page.getByRole('button', { name: 'Confirm rejection' });
    expect(await tabUntilFocused(page, confirm)).toBe(true);
    await page.keyboard.press('Enter');

    await expect(page.getByTestId('approval-decision-summary')).toBeVisible();
  });
});

test.describe('Developer takeover journey', () => {
  test('AC7 — takeover with confirmation dialog + post-takeover PR affordance (keyboard-only)', async ({
    page,
  }) => {
    await openRunFromQueue(page, DEV_REVIEW_RUN_ID);
    await expect(page.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'implementation_review',
    );

    // Open the takeover confirmation dialog.
    const takeover = page.getByRole('button', { name: 'Take over' });
    expect(await tabUntilFocused(page, takeover)).toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('confirmation-dialog')).toBeVisible();

    // Provide the required reason and confirm.
    const reason = page.getByTestId('takeover-reason');
    expect(await tabUntilFocused(page, reason)).toBe(true);
    await page.keyboard.type('Continuing this run manually in the IDE.');
    const confirm = page.getByRole('button', { name: 'Confirm takeover' });
    expect(await tabUntilFocused(page, confirm)).toBe(true);
    await page.keyboard.press('Enter');

    // Post-takeover: the "Continue work in PR {ref}" affordance (AC7).
    await expect(page.getByTestId('takeover-continue-pr')).toBeVisible();
  });
});

test.describe('Recovery retry journey', () => {
  test('AC7 — Failed run → recovery bar → retry (keyboard-only)', async ({ page }) => {
    await openRunFromQueue(page, RECOVERY_RUN_ID);

    await expect(page.getByTestId('approval-decision-bar')).toHaveAttribute(
      'data-approval-bar-mode',
      'recovery_operator',
    );

    // Reach + activate the retry control by keyboard.
    const retry = page.getByTestId('recovery-retry');
    expect(await tabUntilFocused(page, retry)).toBe(true);
    await page.keyboard.press('Enter');

    // Retry fires through a confirmation dialog when one is presented; otherwise it commits
    // directly. Confirm if the dialog appears, then assert the kept-alive success note.
    const confirm = page.getByRole('button', { name: 'Confirm retry' });
    if (await confirm.isVisible().catch(() => false)) {
      expect(await tabUntilFocused(page, confirm)).toBe(true);
      await page.keyboard.press('Enter');
    }
    await expect(page.getByTestId('recovery-retry-success')).toBeVisible();
  });
});
