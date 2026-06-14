// Story 3a-8 — unit tier for `runner.mjs split-fenced` (OpenSpec change-folder reconstruction).
//
// Runs the dependency-free helper as a subprocess (it reads argv and calls process.exit,
// exactly as the pr-output entrypoint invokes it). Proves:
//   * a multi-file fenced payload is split into the change dir, creating subdirs (specs/…),
//   * body trailing-fence blank lines are normalized to exactly one trailing newline,
//   * missing --in / --change-dir is a usage error (exit 2),
//   * an unreadable --in or a fence-less/empty payload exits 41 (read/parse family),
//   * a path-traversal relpath (absolute, drive-letter, `..`, empty segment) is rejected
//     with exit 42 and NOTHING is written outside the change dir,
//   * every non-zero branch is best-effort from the entrypoint's view (it never blocks the
//     run — see Trap T-ADDITIVE-NEVER-BLOCKS; this tier just pins the helper's own codes).
//
// BYTE-IDENTICAL to runners/claude/test/runner-split-fenced.test.mjs except this header path.
// Run locally: `node --test runners/codex/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const READ_PARSE_EXIT = 41; // cannot read --in, or no fence found (malformed/empty)
const TRAVERSAL_EXIT = 42; // unsafe relpath rejected
const USAGE_EXIT = 2; // missing required flag

// Lays the fenced payload in a temp file and runs `split-fenced --in <file> --change-dir <dir>`.
function run(fenced, { withIn = true, withChangeDir = true } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'split-fenced-'));
  const inPath = join(dir, 'carried.txt');
  const changeDir = join(dir, 'changes', 'DL-101-run');
  if (fenced !== undefined) writeFileSync(inPath, fenced, 'utf8');
  const args = ['split-fenced'];
  if (withIn) args.push('--in', inPath);
  if (withChangeDir) args.push('--change-dir', changeDir);
  const result = spawnSync(process.execPath, [RUNNER, ...args], { encoding: 'utf8' });
  return { dir, inPath, changeDir, result };
}

test('multi-file fenced payload is split into the change dir, creating subdirs', () => {
  const fenced = [
    '=== FILE: proposal.md ===',
    '# Proposal',
    'why this change',
    '=== FILE: specs/auth/spec.md ===',
    '## Requirement',
    '- it works',
  ].join('\n');
  const { dir, changeDir, result } = run(fenced);
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    assert.equal(readFileSync(join(changeDir, 'proposal.md'), 'utf8'), '# Proposal\nwhy this change\n');
    assert.equal(
      readFileSync(join(changeDir, 'specs', 'auth', 'spec.md'), 'utf8'),
      '## Requirement\n- it works\n',
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('trailing blank lines before the next fence are normalized to one newline', () => {
  const fenced = ['=== FILE: tasks.md ===', '- [ ] do the thing', '', '', ''].join('\n');
  const { dir, changeDir, result } = run(fenced);
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    assert.equal(readFileSync(join(changeDir, 'tasks.md'), 'utf8'), '- [ ] do the thing\n');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('an empty-bodied fence writes an empty file', () => {
  const fenced = '=== FILE: design.md ===\n';
  const { dir, changeDir, result } = run(fenced);
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    assert.equal(readFileSync(join(changeDir, 'design.md'), 'utf8'), '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('missing --in is a usage error (exit 2)', () => {
  const { dir, result } = run('=== FILE: a.md ===\nx', { withIn: false });
  try {
    assert.equal(result.status, USAGE_EXIT);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('missing --change-dir is a usage error (exit 2)', () => {
  const { dir, result } = run('=== FILE: a.md ===\nx', { withChangeDir: false });
  try {
    assert.equal(result.status, USAGE_EXIT);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('an unreadable --in exits 41', () => {
  // withIn=true but we never write the file, so readFileSync throws.
  const { dir, result } = run(undefined);
  try {
    assert.equal(result.status, READ_PARSE_EXIT);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a fence-less / empty payload exits 41 and writes nothing', () => {
  for (const payload of ['', 'just some prose\nno fences here\n']) {
    const { dir, changeDir, result } = run(payload);
    try {
      assert.equal(result.status, READ_PARSE_EXIT, `payload=${JSON.stringify(payload)}`);
      assert.ok(!existsSync(changeDir), 'no change dir created on parse failure');
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('path-traversal relpaths are rejected (exit 42) and escape nothing', () => {
  const hostile = [
    '../escape.md',
    'a/../../escape.md',
    '/abs/escape.md',
    'C:\\win\\escape.md',
    'sub//escape.md', // empty segment
  ];
  for (const relpath of hostile) {
    const { dir, changeDir, result } = run(`=== FILE: ${relpath} ===\npwned`);
    try {
      assert.equal(result.status, TRAVERSAL_EXIT, `relpath=${relpath}`);
      // The traversal target (one level above the change dir) must not exist.
      assert.ok(!existsSync(join(dirname(changeDir), 'escape.md')), `escaped for relpath=${relpath}`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('a safe file written before a hostile fence still leaves no escape', () => {
  // The first (safe) file is written, then the second (hostile) fence aborts with 42.
  const fenced = ['=== FILE: proposal.md ===', 'ok', '=== FILE: ../escape.md ===', 'pwned'].join('\n');
  const { dir, changeDir, result } = run(fenced);
  try {
    assert.equal(result.status, TRAVERSAL_EXIT);
    assert.ok(!existsSync(join(dirname(changeDir), 'escape.md')), 'no escape outside change dir');
    // proposal.md may or may not have been written before the abort; either way the dir is
    // confined to the change dir.
    if (existsSync(changeDir)) {
      assert.deepEqual(readdirSync(changeDir).sort(), ['proposal.md']);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
