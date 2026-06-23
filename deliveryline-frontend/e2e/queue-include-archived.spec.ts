/**
 * Story 3d-8 (AC5 / AC10) — the run-queue "Show archived runs" include-archived toggle E2E.
 * Driven against the fixture-route mock (`e2e/support/mockApi.ts`), whose `/api/v1/workflows`
 * handler surfaces a soft-hidden run ONLY when `?includeArchived=true` (mirroring the backend's
 * default-hide). NEVER a live backend (story 1.23 / 2.27 discipline).
 */
import { test, expect } from '@playwright/test';

import { mockBackend, ARCHIVED_RUN_ID } from './support/mockApi';

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

test.describe('Queue include-archived toggle (story 3d-8)', () => {
  test('hides archived runs by default and reveals them when the toggle is on', async ({
    page,
  }) => {
    await page.goto('/workflows');

    const toggle = page.getByTestId('queue-include-archived-toggle');
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');

    // Default queue excludes the archived run.
    await expect(page.getByText('DEL-ARCHIVED')).toHaveCount(0);

    // Toggle ON → the archived run appears with its color-independent "Hidden" chip.
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('DEL-ARCHIVED')).toBeVisible();
    const archivedRow = page
      .getByTestId('run-review-queue-item')
      .filter({ hasText: 'DEL-ARCHIVED' });
    await expect(archivedRow.getByTestId('queue-item-archived')).toHaveText('Hidden');

    // The URL carries the filter so a reload/deep-link keeps the archived view.
    await expect(page).toHaveURL(new RegExp('includeArchived=true'));

    // Toggle OFF → the archived run is hidden again.
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByText('DEL-ARCHIVED')).toHaveCount(0);
    expect(ARCHIVED_RUN_ID).toBe('run_archived00001');
  });
});
