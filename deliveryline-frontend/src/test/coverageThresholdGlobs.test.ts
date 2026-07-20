/**
 * Story 4.26 (AC11, Task 5) — coverage-threshold glob meta-test (the `deferred-work.md` frontend
 * hardening item).
 *
 * `vitest.config.ts` enforces per-path line-coverage floors keyed by glob (`src/lib/sanitization/**`
 * etc.). A `thresholds` glob that matches ZERO instrumented files is silently satisfied — v8 reports
 * 100% for an empty set — so a file rename/move can evaporate a floor without redding the build. This
 * meta-test reads the ACTUAL config text, extracts every threshold glob, and asserts each still
 * matches ≥1 instrumented source file (a `.ts`/`.tsx` under the glob's base dir that the coverage
 * `exclude` list does not drop). A rename that empties a floor now fails HERE instead of passing
 * unnoticed.
 *
 * It reads the config as TEXT (not by importing it) so it never pulls the Vite/React plugin graph
 * into the jsdom worker, and stays coupled to the real keys — add a threshold glob and this test
 * checks it automatically; rename a floored directory and this test reds.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/** Files the coverage config excludes from instrumentation (mirror of `coverage.exclude`). */
function isInstrumentableSource(relPath: string): boolean {
  if (!/\.(ts|tsx)$/.test(relPath)) return false;
  if (/\.test\.(ts|tsx)$/.test(relPath)) return false;
  if (/\.d\.ts$/.test(relPath)) return false;
  if (relPath.startsWith('src/test/')) return false;
  if (relPath.startsWith('src/dev/')) return false;
  if (relPath === 'src/main.tsx') return false;
  if (relPath === 'src/routeTree.gen.ts') return false;
  if (relPath === 'src/lib/api/schema.d.ts') return false;
  return true;
}

/** Recursively collect repo-relative file paths under `dir` (POSIX separators). */
function walk(root: string, dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(join(root, dir), { withFileTypes: true })) {
    const rel = `${dir}/${entry.name}`;
    if (entry.isDirectory()) {
      out.push(...walk(root, rel));
    } else {
      out.push(rel);
    }
  }
  return out;
}

/**
 * Parse the `thresholds: { 'glob': { lines: N }, ... }` keys out of the config text.
 *
 * Scans from the `thresholds:` label to end-of-file rather than capturing a block that
 * terminates at the first line-leading `},`: that earlier terminator silently truncated
 * the captured region the moment any entry was reformatted multi-line (e.g. adding a
 * second metric like `{ lines: N, branches: M }`), dropping every subsequent glob from
 * the guard while the "at least one" check still passed. `thresholds` is the last block
 * in the coverage config, so scanning to EOF captures every glob key regardless of
 * single- vs multi-line formatting.
 */
function parseThresholdGlobs(configText: string): string[] {
  const start = configText.indexOf('thresholds:');
  expect(
    start,
    'could not locate the coverage thresholds block in vitest.config.ts',
  ).toBeGreaterThanOrEqual(0);
  const region = configText.slice(start);
  const keys: string[] = [];
  const keyRe = /'([^']+)':\s*\{\s*lines:/g;
  let match: RegExpExecArray | null;
  while ((match = keyRe.exec(region)) !== null) {
    keys.push(match[1]!);
  }
  return keys;
}

describe('coverage threshold globs (story 4.26 AC11)', () => {
  const root = process.cwd();
  const configText = readFileSync(join(root, 'vitest.config.ts'), 'utf8');
  const globs = parseThresholdGlobs(configText);
  const allSources = walk(root, 'src').filter(isInstrumentableSource);

  it('vitest.config.ts declares at least one per-path coverage floor', () => {
    expect(globs.length, 'expected per-path coverage floors in vitest.config.ts').toBeGreaterThan(
      0,
    );
  });

  for (const glob of globs) {
    it(`threshold glob "${glob}" matches at least one instrumented source file`, () => {
      // Every floor in this repo is of the form `src/<path>/**`; the base is the glob without `/**`.
      const base = glob.replace(/\/\*\*$/, '').replace(/\/$/, '');
      const matches = allSources.filter((p) => p === base || p.startsWith(`${base}/`));
      expect(
        matches.length,
        `coverage floor "${glob}" matches ZERO instrumented files — a rename/move likely evaporated it, so the floor is silently satisfied (v8 reports 100% for an empty set). Update vitest.config.ts.`,
      ).toBeGreaterThan(0);
    });
  }
});
