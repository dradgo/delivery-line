/**
 * Story 2.6 (Task 7) — Vitest setup: jest-dom matchers + MSW lifecycle.
 *
 * `onUnhandledRequest: 'error'` makes any request a test forgot to mock fail
 * loudly, so a hook silently hitting the network can't pass by accident.
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { server } from './server';

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
