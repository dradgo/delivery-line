/**
 * Story 3.27 (Task 7 / AC2, AC5) — pure unified-diff parser unit tests.
 *
 * Parse correctness (file/hunk/line counts, add/del tallies, new-file path resolution)
 * + the malformed-diff defensive fallback (never throws). No JSX — the parser is pure.
 */
import { describe, expect, it } from 'vitest';

import {
  PR_DIFF_MAX_FILES,
  PR_DIFF_MAX_LINES,
  countChangedLines,
  parseUnifiedDiff,
} from '../unifiedDiff';

const MULTI_FILE_DIFF = [
  'diff --git a/src/handler.ts b/src/handler.ts',
  'index 1a2b3c4..5d6e7f8 100644',
  '--- a/src/handler.ts',
  '+++ b/src/handler.ts',
  '@@ -1,4 +1,5 @@',
  ' import { process } from "./process";',
  '-export function handle(req) {',
  '+export function handle(req: Request) {',
  '+  validate(req.body);',
  '   return process(req);',
  'diff --git a/src/new.ts b/src/new.ts',
  'new file mode 100644',
  '--- /dev/null',
  '+++ b/src/new.ts',
  '@@ -0,0 +1,2 @@',
  '+export const a = 1;',
  '+export const b = 2;',
].join('\n');

describe('parseUnifiedDiff', () => {
  it('splits a multi-file diff into files → hunks → lines with correct paths', () => {
    const files = parseUnifiedDiff(MULTI_FILE_DIFF);
    expect(files).toHaveLength(2);
    expect(files[0]?.path).toBe('src/handler.ts');
    expect(files[1]?.path).toBe('src/new.ts');
    expect(files[0]?.hunks).toHaveLength(1);
    expect(files[1]?.hunks).toHaveLength(1);
  });

  it('tallies additions/deletions per file', () => {
    const files = parseUnifiedDiff(MULTI_FILE_DIFF);
    // handler.ts: 1 removed, 2 added; new.ts: 2 added.
    expect(files[0]?.additions).toBe(2);
    expect(files[0]?.deletions).toBe(1);
    expect(files[1]?.additions).toBe(2);
    expect(files[1]?.deletions).toBe(0);
  });

  it('classifies each line by its +/-/space prefix and strips the prefix', () => {
    const files = parseUnifiedDiff(MULTI_FILE_DIFF);
    const lines = files[0]?.hunks[0]?.lines ?? [];
    expect(lines[0]).toEqual({ kind: 'context', text: 'import { process } from "./process";' });
    expect(lines[1]).toEqual({ kind: 'removed', text: 'export function handle(req) {' });
    expect(lines[2]).toEqual({ kind: 'added', text: 'export function handle(req: Request) {' });
  });

  it('resolves a new file (--- /dev/null) to its new path', () => {
    const files = parseUnifiedDiff(MULTI_FILE_DIFF);
    expect(files[1]?.path).toBe('src/new.ts');
    // oldPath differs (/dev/null) so it is retained.
    expect(files[1]?.oldPath).toBe('/dev/null');
  });

  it('counts changed lines across all files', () => {
    expect(countChangedLines(parseUnifiedDiff(MULTI_FILE_DIFF))).toBe(5);
  });

  it('does not throw on a malformed/headerless diff — falls back to a single synthetic file', () => {
    const messy = '+just an added line\n-a removed line\nplain context line';
    const files = parseUnifiedDiff(messy);
    expect(files).toHaveLength(1);
    expect(files[0]?.additions).toBe(1);
    expect(files[0]?.deletions).toBe(1);
    const kinds = files[0]?.hunks[0]?.lines.map((l) => l.kind);
    expect(kinds).toEqual(['added', 'removed', 'context']);
  });

  it('returns an empty array for an empty/whitespace diff', () => {
    expect(parseUnifiedDiff('')).toEqual([]);
    expect(parseUnifiedDiff('   \n  ')).toEqual([]);
  });

  it('treats an in-hunk +++ / --- line as content, not a file header', () => {
    const diff = [
      'diff --git a/x b/x',
      '--- a/x',
      '+++ b/x',
      '@@ -1,1 +1,2 @@',
      ' context',
      '+++ this is an added line that starts with plus-plus-plus',
    ].join('\n');
    const files = parseUnifiedDiff(diff);
    expect(files).toHaveLength(1);
    expect(files[0]?.additions).toBe(1);
    expect(files[0]?.hunks[0]?.lines[1]?.text).toBe(
      '++ this is an added line that starts with plus-plus-plus',
    );
  });

  it('exports the AC5 pagination thresholds', () => {
    expect(PR_DIFF_MAX_FILES).toBe(50);
    expect(PR_DIFF_MAX_LINES).toBe(5000);
  });
});
