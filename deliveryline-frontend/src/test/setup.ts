/**
 * Story 2.6 (Task 7) — Vitest setup: jest-dom matchers + MSW lifecycle.
 *
 * `onUnhandledRequest: 'error'` makes any request a test forgot to mock fail
 * loudly, so a hook silently hitting the network can't pass by accident.
 */
import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { server } from './server';

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
