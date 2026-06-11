/**
 * Story 2.6 (Task 7) — shared MSW server for the data-layer test suite.
 *
 * Story 2.27 (Task 1, AC2/AC3) added the shared DEFAULT handlers (`./handlers`):
 * a realistic read-only backend seeded from the story-1.23 fixture event streams,
 * typed by the OpenAPI schema. The per-test `server.use(...)` override pattern is
 * UNCHANGED — runtime handlers prepend (so a test still overrides any endpoint), and
 * `setup.ts`'s `resetHandlers()` restores THESE defaults (not an empty set) after
 * each test. Mutations are intentionally left to per-test handlers (each asserts a
 * specific request body / error response).
 */
import { setupServer } from 'msw/node';

import { defaultHandlers } from './handlers';

export const server = setupServer(...defaultHandlers);
