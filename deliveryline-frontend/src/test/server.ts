/**
 * Story 2.6 (Task 7) — shared MSW server for the data-layer test suite.
 *
 * No default handlers — each test installs its own via `server.use(...)` so the
 * intercepted contract is explicit per test. Story 2.27 may add shared default
 * handlers when it builds out the full suite.
 */
import { setupServer } from 'msw/node';

export const server = setupServer();
