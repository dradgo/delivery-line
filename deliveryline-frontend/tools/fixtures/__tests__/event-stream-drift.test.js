// @ts-check
/**
 * Story 2.27 (Task 1, AC3 / S6) — fixture event-stream drift gate (`check:fixtures`).
 *
 * The frontend MSW handlers (src/test/handlers.ts) are seeded from vendored copies of
 * the story-1.23 canonical event streams so the test layer imports them as plain
 * in-`src` JSON (no cross-module TS/eslint coupling). This gate is the SINGLE-SOURCE
 * enforcement (OQ-2 "vendor + drift gate" resolution): it fails the enforced Maven/CI
 * path if any vendored copy drifts from the backend original, mirroring the `check:api`
 * schema-drift gate. If 1.23 changes, re-copy the streams (the failure message lists
 * which file drifted) — never edit the vendored copy by hand.
 *
 * The per-file comparison is SEMANTIC (`JSON.parse` + `deepEqual`): key reordering and
 * whitespace are tolerated, but any content drift fails. A separate test asserts the
 * vendored SET matches the backend SET, so a newly added (or removed) backend stream
 * cannot silently go un-vendored.
 *
 * Framework-free ESM + `node --test`, run with ZERO build step (Node 20.19 cannot
 * strip TS types) — same discipline as `tools/routing/__tests__/public-id-params.test.js`.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const vendoredDir = join(here, '..', '..', '..', 'src', 'test', 'fixtures', 'event-streams');
const backendDir = join(
  here,
  '..',
  '..',
  '..',
  '..',
  'deliveryline-backend',
  'src',
  'test',
  'resources',
  'fixture-event-streams',
);

/** The streams the frontend test layer vendors + MUST keep in lockstep with 1.23. */
const STREAMS = [
  'happy-path-success.json',
  'spec-rejection-and-resubmit.json',
  'execution-failure-with-retry.json',
  'clarification-incorporated-happy-path.json',
  'clarification-superseded-and-rejected.json',
];

/** Parse a JSON file, surfacing a clear message if it is missing/unreadable. */
function readJson(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    assert.fail(`Could not read or parse fixture event stream at ${path}: ${reason}`);
  }
}

test('the vendored stream set matches the backend story-1.23 set', () => {
  const backendStreams = readdirSync(backendDir)
    .filter((file) => file.endsWith('.json'))
    .sort();
  assert.deepEqual(
    backendStreams,
    [...STREAMS].sort(),
    'The backend fixture-event-streams set drifted from the vendored set. A 1.23 stream ' +
      'was added or removed without updating deliveryline-frontend/src/test/fixtures/event-streams/ ' +
      '(and the STREAMS list in this gate). Re-vendor the full set so the MSW baseline stays complete.',
  );
});

for (const name of STREAMS) {
  test(`vendored ${name} matches the backend story-1.23 original`, () => {
    const vendored = readJson(join(vendoredDir, name));
    const backend = readJson(join(backendDir, name));
    assert.deepEqual(
      vendored,
      backend,
      `Vendored fixture drifted: deliveryline-frontend/src/test/fixtures/event-streams/${name} ` +
        `differs from the backend original. Re-copy it from ` +
        `deliveryline-backend/src/test/resources/fixture-event-streams/${name}.`,
    );
  });
}
