/**
 * Story 3d-5 (AC4 / AC6) — Step Execution Log Viewer E2E: opening a run whose backend advertises
 * the `view_runner_logs` action shows the viewer, and the SSE stream's lines render. Driven against
 * the fixture-route mock (`e2e/support/mockApi.ts`), which models the `/runner-logs/stream`
 * endpoint as a complete `text/event-stream` body. NEVER a live backend (story 1.23 / 2.27
 * discipline).
 */
import { test, expect } from '@playwright/test';

import { mockBackend, RECOVERY_RUN_ID } from './support/mockApi';

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

test.describe('Step execution log viewer (story 3d-5)', () => {
  test('shows the viewer and renders streamed lines when view_runner_logs is allowed', async ({
    page,
  }) => {
    await page.goto(`/workflows/${RECOVERY_RUN_ID}`);

    const viewer = page.getByTestId('step-execution-log-viewer');
    await expect(viewer).toBeVisible();
    // The fixture stream replays one finished line; it must render in the log region.
    await expect(page.getByTestId('step-log-scroll')).toContainText('e2e runner log line');
    // Color-independent mode signifier present (icon + label).
    await expect(page.getByTestId('step-log-mode')).toBeVisible();
  });
});
