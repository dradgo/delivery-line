import { defineConfig, devices } from '@playwright/test';

/**
 * Story 2.27 (Task 6, AC8/AC9, S3) — Playwright cross-browser + mobile-viewport +
 * keyboard-only E2E config.
 *
 * Scope & isolation (S3): these specs live under `e2e/` — OUTSIDE the Vitest
 * `include` glob (`src/**\/*.test.{ts,tsx}`) — so they never run under jsdom, and
 * Vitest never collects them. Playwright runs in its OWN CI job (`frontend-e2e`)
 * gated on `frontend-build-tests` (Vitest) success, and installs its own browser
 * binaries (`npx playwright install --with-deps`) — kept OUT of the Maven
 * clean-package path (that path is Vite build + Vitest only).
 *
 * Backend: the specs DO NOT hit a live backend. They intercept `/api/v1/**` via
 * Playwright route fulfillment seeded from the SAME story-1.23 fixture streams the
 * Vitest MSW handlers use (e2e/support/mockApi.ts → src/test/fixtures/event-streams),
 * so there is a single source of truth and no mock/prod drift.
 *
 * Flake control (AC11): `retries: 0` — NO blanket retry, on CI or locally. A flaky
 * spec is quarantined with `test.fixme(...)` + a one-line justification, surfacing
 * the flake rather than masking it (story 1.21 AC5). See frontend/README.md.
 */

/** Galaxy S23+-class viewport (story 2.26 AC9): ~360×780 CSS px, dpr 3, touch. */
const galaxyS23 = {
  ...devices['Pixel 7'],
  viewport: { width: 360, height: 780 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
};

export default defineConfig({
  testDir: './e2e',
  // The whole suite is the production bundle served by `vite preview`.
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // AC11 — no blanket retry; flaky specs are explicitly quarantined, never retried.
  retries: 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    // OQ-3 default — real Edge via the `msedge` channel. The `frontend-e2e` CI job
    // runs on ubuntu-latest and installs the channel per-leg via
    // `npx playwright install --with-deps msedge` (the channel is also present on a
    // windows-latest runner if the matrix ever moves there).
    { name: 'msedge', use: { ...devices['Desktop Edge'], channel: 'msedge' } },
    // Mobile cross-cut (story 2.26 AC9) — the executable form of the jsdom-pinned
    // structural-collapse contract, now on a real engine.
    { name: 'mobile-galaxy-s23', use: galaxyS23 },
  ],
  // Serve the PRODUCTION bundle (AC8 — E2E runs against the built SPA, not dev).
  // CI builds first (the job's `npm run build` step), so previewing is enough there;
  // locally we build-then-preview so `npm run test:e2e` works from a clean tree.
  webServer: {
    command: process.env.CI
      ? 'npm run preview -- --port 4173 --strictPort'
      : 'npm run build && npm run preview -- --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
  },
});
