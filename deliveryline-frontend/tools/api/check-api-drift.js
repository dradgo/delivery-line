// @ts-check
/**
 * Story 2.6 (AC2, Task 2) — generated-API-client drift gate.
 *
 * Mirrors the backend's OpenAPI drift semantics (story 1.21 / 6.9): the committed
 * typed client (`src/lib/api/schema.d.ts`) must be byte-identical to a FRESH
 * regeneration from the committed snapshot. A stale committed client (someone
 * edited the backend `openapi.json` but forgot to run `npm run generate-api`)
 * fails CI here, exactly like `check:routes` / `check:tokens` / `check:contrast`.
 *
 * Runs the SAME generator (`openapi-typescript`) the `generate-api` script uses, to
 * a temp file, then compares against the committed output with line endings
 * normalized to `\n` (so a CRLF checkout on Windows can't spuriously fail — the
 * `frontend-lockfile-cross-platform` / `verify-ci-fixes-in-clean-env` discipline).
 *
 * Plain framework-free Node ESM, run by `node tools/api/check-api-drift.js` — the
 * established `tools/**` check pattern (no Vitest, no build step).
 */
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const frontendRoot = resolve(here, '../..');
const SNAPSHOT = resolve(
  frontendRoot,
  '../deliveryline-backend/src/main/resources/openapi/openapi.json',
);
const COMMITTED = resolve(frontendRoot, 'src/lib/api/schema.d.ts');
const GENERATOR_CLI = resolve(frontendRoot, 'node_modules/openapi-typescript/bin/cli.js');

/** Normalize CRLF→LF so a Windows checkout diffs identically to a Linux one. */
function normalize(text) {
  return text.replace(/\r\n/g, '\n');
}

function fail(message) {
  console.error(`❌ check:api — ${message}`);
  process.exit(1);
}

const tempDir = mkdtempSync(join(tmpdir(), 'dl-api-drift-'));
const tempOut = join(tempDir, 'schema.d.ts');

try {
  // Invoke the generator via the current Node binary + its CLI entry — robust
  // across Windows/Linux (no reliance on a `.bin` shim or PATH).
  const result = spawnSync(process.execPath, [GENERATOR_CLI, SNAPSHOT, '--output', tempOut], {
    cwd: frontendRoot,
    encoding: 'utf8',
  });
  if (result.status !== 0) {
    fail(
      `openapi-typescript failed to regenerate from ${SNAPSHOT}\n${result.stderr ?? ''}${result.stdout ?? ''}`,
    );
  }

  const fresh = normalize(readFileSync(tempOut, 'utf8'));
  let committed;
  try {
    committed = normalize(readFileSync(COMMITTED, 'utf8'));
  } catch {
    fail(
      `committed client missing at ${COMMITTED}. Run \`npm run generate-api\` and commit src/lib/api/schema.d.ts.`,
    );
  }

  if (fresh !== committed) {
    fail(
      `committed src/lib/api/schema.d.ts is STALE relative to the backend OpenAPI snapshot.\n` +
        `The backend openapi.json changed but the generated client was not regenerated.\n` +
        `Fix: run \`npm run generate-api\` and commit the updated src/lib/api/schema.d.ts.`,
    );
  }

  console.log('✅ check:api — generated client is in sync with the committed OpenAPI snapshot.');
} finally {
  rmSync(tempDir, { recursive: true, force: true });
}
