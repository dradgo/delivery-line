/**
 * Story 2.6 (Task 7) — Vitest setup: jest-dom matchers + MSW lifecycle.
 *
 * `onUnhandledRequest: 'error'` makes any request a test forgot to mock fail
 * loudly, so a hook silently hitting the network can't pass by accident.
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, expect } from 'vitest';
import * as axeMatchers from 'vitest-axe/matchers';

import { server } from './server';

// Story 2.25 (Task 1, AC2) — register the axe-core `toHaveNoViolations` matcher
// suite-wide so `expectNoA11yViolations` (src/test/a11y/axe.ts) can assert it.
expect.extend(axeMatchers);

// jsdom has no canvas; axe-core's `color-contrast` rule probes `getContext` and
// jsdom throws "Not implemented", flooding stderr on every scan. Returning null
// makes axe degrade `color-contrast` to *incomplete* quietly (the authoritative
// contrast gate is the token-pair `check:contrast` node-test — story 2.25 AC6).
// Nothing in this app renders to canvas, so this stub is behaviour-neutral.
HTMLCanvasElement.prototype.getContext = (() =>
  null) as typeof HTMLCanvasElement.prototype.getContext;

// Vitest 4 augments matchers via the `vitest` module's `Assertion` interface.
declare module 'vitest' {
  interface Assertion {
    toHaveNoViolations(): void;
  }
  interface AsymmetricMatchersContaining {
    toHaveNoViolations(): void;
  }
}

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  // RTL only auto-cleans when `test.globals: true`; this config is module-scoped,
  // so component tests must explicitly cleanup() between cases or the DOM bleeds
  // and queries like getByRole('main') start matching multiple results (story 2.7).
  cleanup();
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
