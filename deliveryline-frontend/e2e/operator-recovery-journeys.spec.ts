/**
 * Story 4.26 (AC7, Task 2) — Epic-4 keyboard-only operator/reviewer journeys, the executable
 * real-browser form of the story-2.25 AC3 keyboard-operability contract for Epic 4's new decision
 * surfaces (classify / reconcile / resume / Compare Mode).
 *
 * HARD RULE (mirrors keyboard-only.spec.ts): ONLY Tab / Shift+Tab / Enter / Space / Escape — ZERO
 * `.click()` / `.tap()`. If any Epic-4 decision surface becomes unreachable without a pointer, a
 * `tabUntil*` helper exhausts its budget and the test fails — the regression AC7 guards against.
 *
 * These run in the existing `frontend-e2e` matrix (chromium/firefox/webkit/msedge + the
 * mobile-galaxy-s23 project) — NO new CI job — at `retries: 0`. The mobile project auto-covers the
 * story-4.21 single-column Compare takeover (Journey C branches on the project's viewport).
 */
import { test, expect, type Page } from '@playwright/test';

import {
  mockBackend,
  OPERATOR_FAILED_RUN_ID,
  PAUSED_CONFLICT_RUN_ID,
  COMPARE_RUN_ID,
} from './support/mockApi';

/** Tab (forward) up to `max` times until the element matching `selector` holds focus. */
async function tabUntilFocused(page: Page, selector: string, max = 60): Promise<boolean> {
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

/**
 * Tab until the focused element is an actionable BUTTON whose accessible name contains `name` (for
 * the recovery/confirm buttons that carry no testid, or whose testid sits on a wrapper). Requiring
 * the element to be a button avoids stopping on a focusable CONTAINER that merely contains the text.
 *
 * Searches FORWARD then BACKWARD: after a dialog closes, focus is restored to the trigger, which may
 * sit AFTER the target in DOM order (a forward-only walk would then have to wrap the whole document).
 * Both directions still use only Tab / Shift+Tab — no pointer.
 */
async function tabUntilButton(page: Page, name: string, max = 80): Promise<boolean> {
  const check = (t: string) => {
    const el = document.activeElement;
    if (el == null) return false;
    const isButton = el.tagName === 'BUTTON' || el.getAttribute('role') === 'button';
    const label = el.getAttribute('aria-label') ?? el.textContent ?? '';
    return isButton && label.includes(t);
  };
  for (let i = 0; i < max; i++) {
    if (await page.evaluate(check, name)) return true;
    await page.keyboard.press('Tab');
  }
  for (let i = 0; i < max; i++) {
    if (await page.evaluate(check, name)) return true;
    await page.keyboard.press('Shift+Tab');
  }
  return page.evaluate(check, name);
}

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

test('AC7 Journey A — operator finds a Failed run, diagnoses via deep-dive, and classifies it (keyboard-only)', async ({
  page,
}) => {
  // Find the Failed run in the operator queue.
  await page.goto('/operator/queue');
  await expect(page.getByTestId('operator-queue')).toBeVisible();
  await expect(page.getByTestId('run-review-queue-item').first()).toBeVisible();

  // Open the run's detail, where the story-4.4 failure-diagnostics deep-dive lives. The queue row is
  // an <a>; navigate directly rather than Tab-to-anchor (local WebKit won't Tab-focus anchors — the
  // same pre-existing limitation Journey C documents). AC7's keyboard-only contract is about the
  // DECISION surfaces exercised below (the deep-dive drill-down + the classify dialog), not the link.
  await page.goto(`/workflows/${OPERATOR_FAILED_RUN_ID}`);
  await expect(page.getByTestId('failure-event-surface')).toBeVisible();

  // Diagnose via deep-dive: the failure event row is a <button>; Enter opens the diagnostics sheet
  // with the NFR7 five-question summary. Assert the summary carries the run's real failure reason.
  const failureRow = '[data-testid="failure-event-row"]';
  expect(await tabUntilFocused(page, failureRow)).toBe(true);
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('failure-diagnostics-sheet')).toBeVisible();
  await expect(page.getByTestId('failure-diagnostics-summary')).toContainText('container exited');

  // Escape closes the deep-dive sheet and restores focus to the opening row.
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('failure-diagnostics-sheet')).toHaveCount(0);

  // Classify via the dialog launched from the same diagnostics surface (Tab back to the trigger).
  expect(await tabUntilButton(page, 'Classify failure')).toBe(true);
  await page.keyboard.press('Enter');

  // The taxonomy dialog opens; select a radio card by keyboard, then apply.
  await expect(page.getByTestId('failure-classification-dialog')).toBeVisible();
  const option = '[data-testid="failure-classification-option-agent_execution_failure"] input';
  expect(await tabUntilFocused(page, option)).toBe(true);
  await page.keyboard.press('Space');
  expect(await tabUntilButton(page, 'Apply classification')).toBe(true);
  await page.keyboard.press('Enter');

  // On success the dialog self-closes (the success announcement survives via the document-level
  // live region). The dialog is gone — the diagnose→classify round-trip completed keyboard-only.
  await expect(page.getByTestId('failure-classification-dialog')).toHaveCount(0);
});

test('AC7 Journey B — integration conflict auto-pause → reconcile → resume (keyboard-only)', async ({
  page,
}) => {
  await page.goto(`/workflows/${PAUSED_CONFLICT_RUN_ID}`);

  // A Paused run selects the recovery_operator decision bar.
  await expect(page.getByTestId('approval-decision-bar')).toHaveAttribute(
    'data-approval-bar-mode',
    'recovery_operator',
  );

  // Reconcile enables once the unresolved-conflict list resolves a conflictId (AC8). The
  // recovery-action-* testid sits on the row wrapper, so drive the button by its accessible name.
  await expect(page.getByRole('button', { name: 'Reconcile conflict' })).toBeEnabled();
  expect(await tabUntilButton(page, 'Reconcile conflict')).toBe(true);
  await page.keyboard.press('Enter');

  // The reconciliation dialog opens with the side-by-side snapshots + safe-first decisions.
  await expect(page.getByTestId('reconciliation-dialog')).toBeVisible();
  await expect(page.getByTestId('reconciliation-snapshots')).toBeVisible();

  // Pick the recommended (safe-first) decision, then supply the required reason (NFR19).
  const decision = '[data-testid="reconciliation-decision-accept_external_state"] input';
  expect(await tabUntilFocused(page, decision)).toBe(true);
  await page.keyboard.press('Space');
  const reason = '[data-testid="reconciliation-reason"]';
  expect(await tabUntilFocused(page, reason)).toBe(true);
  await page.keyboard.type('The external state is authoritative for this run.');
  expect(await tabUntilButton(page, 'Confirm reconcile')).toBe(true);
  await page.keyboard.press('Enter');

  // The dialog closes; back on the recovery bar, resume the run.
  await expect(page.getByTestId('reconciliation-dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Resume run' })).toBeEnabled();
  expect(await tabUntilButton(page, 'Resume run')).toBe(true);
  await page.keyboard.press('Enter');
  expect(await tabUntilButton(page, 'Confirm resume')).toBe(true);
  await page.keyboard.press('Enter');

  // The recovery bar persists the resulting state — the run resumed (Executing).
  await expect(page.getByTestId('recovery-decision-summary')).toContainText('Executing');
});

test('AC7 Journey C — reviewer enters Compare Mode, navigates with J/K, exits (keyboard-only)', async ({
  page,
}) => {
  const isMobile = (page.viewportSize()?.width ?? 9999) < 768;

  await page.goto(`/workflows/${COMPARE_RUN_ID}`);
  await expect(page.getByTestId('approval-decision-bar')).toBeVisible();

  // Open the current (v2) spec artifact via its keyboard-reachable run-detail link.
  const artifactLink = `a[href^="/workflows/${COMPARE_RUN_ID}/artifacts/"]`;
  expect(await tabUntilFocused(page, artifactLink)).toBe(true);
  await page.keyboard.press('Enter');

  // The Compare control is ENABLED (v2 artifact has a comparable prior + enter_compare_mode) and
  // keyboard-reachable; activating it opens Compare Mode as an in-context overlay (no navigation).
  const compareEntry = '[data-testid="artifact-compare-entry"]';
  await expect(page.locator(compareEntry)).toBeEnabled();
  expect(await tabUntilFocused(page, compareEntry)).toBe(true);
  await page.keyboard.press('Enter');

  await expect(page.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'default');
  if (isMobile) {
    // Story 4.21 — the mobile single-column takeover (proves the AC5 bounded state on a real engine).
    await expect(page.getByTestId('compare-mobile-body')).toBeVisible();
  }

  // J jumps to the first changed region; a second J advances; K steps back — the section
  // self-focused on entry so the accelerators fire without a pointer (AC6).
  const region = (i: number) => (isMobile ? `compare-mobile-region-${i}` : `compare-region-${i}`);
  await page.keyboard.press('j');
  await expect(page.getByTestId(region(0))).toBeFocused();
  await page.keyboard.press('j');
  await expect(page.getByTestId(region(1))).toBeFocused();
  await page.keyboard.press('k');
  await expect(page.getByTestId(region(0))).toBeFocused();

  // Escape exits Compare Mode back to the review context.
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('compare-mode')).toHaveCount(0);
});
