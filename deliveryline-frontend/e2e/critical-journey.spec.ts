/**
 * Story 2.27 (Task 6, AC8) — the two critical-journey E2E specs, run across the
 * Chrome / Firefox / Safari(WebKit) / Edge matrix + the Galaxy S23+ mobile project.
 *
 *  J1: queue → open run → read spec artifact → (answer clarification → approve)
 *  J2: queue → open run → read spec → (reject with feedback)
 *
 * The reachable navigation skeleton (queue → run detail → spec artifact viewer, with
 * the run-context strip and the approval decision surface present) is asserted on a
 * REAL browser engine against the fixture-backed mock. The terminal commit steps
 * (answer-clarification, approve, reject-commit) are DORMANT in this build — the
 * clarification-read endpoint and the artifactId read-seam have not shipped, so the
 * approval bar renders `blocked` and the clarification region renders empty (stories
 * 2.18 / 2.19). Those steps are QUARANTINED with `test.fixme` + justification (AC11 /
 * story 1.21 AC5) so the gap is surfaced, not faked; they flip to live assertions
 * when those seams land.
 */
import { test, expect } from '@playwright/test';

import { mockBackend, HAPPY_RUN_ID } from './support/mockApi';

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

/** Drive queue → run detail → spec artifact viewer; return having read the spec. */
async function navigateToSpecArtifact(page: import('@playwright/test').Page) {
  // `/` redirects to the queue (the canonical entry surface).
  await page.goto('/');
  await expect(page.getByTestId('run-review-queue-item').first()).toBeVisible();

  // Open the happy-path run (match by the typed href, not fragile link text).
  await page.locator(`a[href="/workflows/${HAPPY_RUN_ID}"]`).first().click();
  await expect(page.getByRole('region', { name: 'Run context' })).toBeVisible();
  // The sticky-footer approval decision surface is present on the run detail.
  await expect(page.getByTestId('approval-decision-bar')).toBeVisible();

  // Read the spec artifact (the Artifact Review Panel route). The artifact id is the
  // unique on-page marker (the heading "Artifact" collides with the panel title).
  await page.getByRole('link', { name: /Open a sample artifact/ }).click();
  await expect(page.getByText('art_sample0001')).toBeVisible();
  // The inline approval decision surface is present beside the artifact too.
  await expect(page.getByTestId('approval-decision-bar')).toBeVisible();
}

test.describe('J1 — queue → run → spec read → answer clarification → approve', () => {
  test('reaches the spec-review decision surface from the queue', async ({ page }) => {
    await navigateToSpecArtifact(page);
  });

  test.fixme('answers a clarification then approves the spec', () => {
    // DORMANT: the clarification-read endpoint is unshipped (region renders the
    // calm empty state) and the approval bar is `blocked` until the artifactId
    // read-seam lands. Enable when those seams ship (stories 2.18/2.19 follow-ons).
  });
});

test.describe('J2 — queue → run → spec read → reject with feedback', () => {
  test('reaches the spec-review decision surface from the queue', async ({ page }) => {
    await navigateToSpecArtifact(page);
  });

  test.fixme('rejects the spec with a tagged-feedback reason', () => {
    // DORMANT: the reject-with-feedback dialog opens from the approval bar's
    // action area, which is `blocked` until the artifactId read-seam lands.
    // Enable when the approval bar drives a real artifactId.
  });
});
