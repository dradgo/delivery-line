/**
 * Story 3d-4 (AC7/AC9) — Manual Execution Surface E2E: a run parked in WaitingForManualExecution
 * whose owner-scoped actions advertise `obtain_manual_bundle` / `submit_manual_artifact` shows the
 * surface, offers the bundle download/copy affordances, and submits a pasted runner-result artifact.
 * Driven against the fixture-route mock (`e2e/support/mockApi.ts`) — NEVER a live backend (story
 * 1.23 / 2.27 discipline).
 */
import { test, expect } from '@playwright/test';

import { mockBackend, MANUAL_EXEC_RUN_ID } from './support/mockApi';

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

test.describe('Manual execution surface (story 3d-4)', () => {
  test('shows the surface, offers the bundle, and submits a pasted artifact', async ({ page }) => {
    await page.goto(`/workflows/${MANUAL_EXEC_RUN_ID}`);

    const surface = page.getByTestId('manual-execution-surface');
    await expect(surface).toBeVisible();

    // AC1 — the bundle download/copy affordances render for a parked run.
    await expect(page.getByTestId('manual-bundle-download')).toBeVisible();
    await expect(page.getByTestId('manual-bundle-copy')).toBeVisible();

    // AC2 — paste a runner-result-shaped artifact and submit.
    await page.getByTestId('manual-artifact-payload').fill('{"schemaVersion":1}');
    await page.getByTestId('manual-artifact-submit').click();

    // The mock advances the run to WaitingForSpecApproval; the success alert reflects it.
    await expect(page.getByTestId('manual-artifact-success')).toContainText(
      'WaitingForSpecApproval',
    );
  });
});
