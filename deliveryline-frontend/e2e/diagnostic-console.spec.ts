/**
 * Story 3d-6 (AC4 / AC6) — Read-only Diagnostic Console E2E: opening an EXECUTING run whose backend
 * advertises the `open_diagnostic_console` action (owner-scoped) shows the console, the SSE stream's
 * chunks render, and the "Read-only" badge is present. Driven against the fixture-route mock
 * (`e2e/support/mockApi.ts`), which models the `/diagnostic-console/stream` endpoint as a complete
 * `text/event-stream` body. NEVER a live backend (story 1.23 / 2.27 discipline).
 */
import { test, expect } from '@playwright/test';

import { mockBackend, DIAGNOSTIC_CONSOLE_RUN_ID } from './support/mockApi';

test.beforeEach(async ({ page }) => {
  await mockBackend(page);
});

test.describe('Read-only diagnostic console (story 3d-6)', () => {
  test('shows the console, renders streamed chunks, and badges it Read-only when open_diagnostic_console is allowed', async ({
    page,
  }) => {
    await page.goto(`/workflows/${DIAGNOSTIC_CONSOLE_RUN_ID}`);

    const console = page.getByTestId('read-only-diagnostic-console');
    await expect(console).toBeVisible();
    // The fixture stream replays one live chunk; it must render in the console region.
    await expect(page.getByTestId('console-scroll')).toContainText('e2e console output');
    // AC6 — the Read-only badge is present (color-independent: icon + label).
    await expect(page.getByTestId('console-readonly-badge')).toBeVisible();
    // No input control posting to the backend — read-only streaming pty (DD-1 / Trap T6).
    await expect(console.locator('input')).toHaveCount(0);
    await expect(console.locator('textarea')).toHaveCount(0);
  });
});
