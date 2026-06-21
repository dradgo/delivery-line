/**
 * Story 3c-9 (Task 9, AC10) — the project-management journey against the fixture
 * mock backend: navigate to Projects, create a project, set a credential, and run a
 * connection test, plus a keyboard-only reachability pass for the nav landmark + the
 * primary "New project" action.
 */
import { test, expect, type Page } from '@playwright/test';

import { mockBackend } from './support/mockApi';

/** The mobile/tablet breakpoint floor (useResponsiveLayout.ts TABLET_MIN_PX). */
const TABLET_MIN_PX = 768;

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

test('AC10 — create → set-credential → test-connection journey', async ({ page }) => {
  await page.goto('/projects');

  // The seeded default project is listed.
  await expect(page.getByTestId('project-list-table')).toBeVisible();
  await expect(
    page.getByTestId('project-row').filter({ hasText: 'Default project' }),
  ).toBeVisible();

  // Create a new project.
  await page.getByTestId('project-new-button').click();
  await expect(page.getByTestId('project-form-dialog')).toBeVisible();
  await page.getByTestId('project-name-input').fill('Acme Widgets');
  await page.getByTestId('project-slug-input').fill('acme-widgets');
  await page.getByTestId('project-form-submit').click();

  // The new project appears in the list (the create invalidates + refetches).
  const acmeRow = page.getByTestId('project-row').filter({ hasText: 'Acme Widgets' });
  await expect(acmeRow).toBeVisible();
  await acmeRow.getByTestId('project-credential-set-button-repo_host').click();
  await expect(page.getByTestId('project-credential-dialog-repo_host')).toBeVisible();
  await page.getByTestId('project-credential-input-repo_host').fill('a-secret-token');
  await page.getByTestId('project-credential-submit-repo_host').click();
  // Presence flips to Configured (no secret ever shown).
  await expect(acmeRow.getByTestId('project-credential-status-repo_host')).toHaveAttribute(
    'data-credential-status',
    'configured',
  );

  // Run a connection test on the new project.
  await acmeRow.getByTestId('connection-test-button').click();
  await expect(acmeRow.getByTestId('connection-test-result')).toBeVisible();
  await expect(acmeRow.locator('[data-check="repository_reachable"]')).toHaveAttribute(
    'data-check-status',
    'pass',
  );
});

test('AC8 — the Projects nav link and "New project" action are keyboard-reachable', async ({
  page,
}) => {
  await page.goto('/projects');
  await expect(page.getByTestId('project-list-table')).toBeVisible();

  // The nav landmark link is reachable by keyboard. On a phone-class viewport the
  // nav rail collapses into a hamburger drawer (AppShell `MobileTopBar` → `Sheet`),
  // so the keyboard path to the `/projects` link runs through the "Open workflow
  // navigation" trigger; on desktop/tablet the inline rail link is tabbable directly.
  const viewport = page.viewportSize();
  const isMobile = viewport !== null && viewport.width < TABLET_MIN_PX;
  if (isMobile) {
    expect(await tabUntilFocused(page, '[aria-label="Open workflow navigation"]')).toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('nav-projects-link')).toBeVisible();
    expect(await tabUntilFocused(page, 'a[href="/projects"]')).toBe(true);
    // Reset to a clean, drawer-closed state before the in-page action check below so
    // the open drawer's focus trap can't intercept the tab walk to "New project".
    await page.goto('/projects');
    await expect(page.getByTestId('project-list-table')).toBeVisible();
  } else {
    expect(await tabUntilFocused(page, 'a[href="/projects"]')).toBe(true);
  }

  // The single primary "New project" action is reachable + activates by keyboard.
  expect(await tabUntilFocused(page, '[data-testid="project-new-button"]')).toBe(true);
  await page.keyboard.press('Enter');
  await expect(page.getByTestId('project-form-dialog')).toBeVisible();
});
