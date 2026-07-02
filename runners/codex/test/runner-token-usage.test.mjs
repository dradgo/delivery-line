// Story 3g-3 (FR74) — unit tier for `runner.mjs build` per-execution token-usage capture.
//
// Runs the dependency-free helper as a subprocess (exactly as the entrypoint invokes it at step 6)
// and inspects the emitted runner-result.v1 normalizedOutput.usage. Proves:
//   * with NO mock file, `usage` is ABSENT — a no-usage result stays byte-identical to pre-3g
//     output (never usage:{}, never usage:null),
//   * with a DELIVERYLINE_USAGE_MOCK_FILE present, the deterministic {input,output,total} counts are
//     emitted under normalizedOutput.usage (offline happy path),
//   * a malformed mock file (garbage / negative / non-integer / non-object) is NON-FATAL: the run
//     still succeeds and `usage` is omitted or partially sanitized, never a thrown/failed run,
//   * a hostile mock file carrying secret-shaped extra keys leaks NOTHING — only the three allowed
//     non-negative integer keys survive (AC5 no-secret), and capture never throws.
//
// Twin of runners/claude/test/runner-token-usage.test.mjs (identical — only the RUNNER path differs).
// Run locally: `node --test runners/codex/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const SECRET = 'sk-openai-supersecrettokenvalue';

// Runs `build --stage spec` against a minimal bundle and returns the parsed result document.
function build({ mockFile, mockContent } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'token-usage-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const summaryPath = join(dir, 'summary.txt');
  const outPath = join(dir, 'runner-result.v1.json');
  writeFileSync(
    bundlePath,
    JSON.stringify({
      schemaVersion: 1,
      workflowRunId: 'run_token_usage',
      runnerExecutionId: 'rex_token_usage',
      classification: 'shareable-redacted',
    }),
    'utf8',
  );
  writeFileSync(summaryPath, 'Generated specification artifact\n', 'utf8');
  const env = { ...process.env };
  delete env.DELIVERYLINE_USAGE_MOCK_FILE;
  if (mockFile) {
    const mockPath = join(dir, 'usage.mock.json');
    writeFileSync(mockPath, mockContent, 'utf8');
    env.DELIVERYLINE_USAGE_MOCK_FILE = mockPath;
  }
  const args = [
    RUNNER,
    'build',
    '--bundle',
    bundlePath,
    '--stage',
    'spec',
    '--summary-file',
    summaryPath,
    '--out',
    outPath,
    '--auth-var',
    'CODEX_AUTH_JSON',
  ];
  const result = spawnSync(process.execPath, args, { encoding: 'utf8', env });
  return { dir, outPath, result };
}

test('no mock file -> usage key ABSENT (byte-identical to pre-3g output)', () => {
  const { dir, outPath, result } = build();
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(
      Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'),
      false,
      'usage must be omitted entirely when no counts are available',
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('mock file present -> deterministic input/output/total counts emitted and schema-shaped', () => {
  const mockContent = JSON.stringify({ inputTokens: 1200, outputTokens: 800, totalTokens: 2000 });
  const { dir, outPath, result } = build({ mockFile: true, mockContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const usage = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput.usage;
    assert.deepEqual(usage, { inputTokens: 1200, outputTokens: 800, totalTokens: 2000 });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('partial mock (only totalTokens) -> only that field emitted, others absent (not synthesized)', () => {
  const mockContent = JSON.stringify({ totalTokens: 2000 });
  const { dir, outPath, result } = build({ mockFile: true, mockContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const usage = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput.usage;
    assert.deepEqual(usage, { totalTokens: 2000 });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('malformed mock (negative / non-integer / non-object) -> non-fatal, usage sanitized away', () => {
  for (const mockContent of [
    'not json at all',
    JSON.stringify({ inputTokens: -1, outputTokens: 1.5, totalTokens: 'lots' }),
    JSON.stringify([1, 2, 3]),
    JSON.stringify('just a string'),
    JSON.stringify(null),
  ]) {
    const { dir, outPath, result } = build({ mockFile: true, mockContent });
    try {
      assert.equal(result.status, 0, `run must still succeed; stderr=${result.stderr}`);
      const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
      assert.equal(
        Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'),
        false,
        `malformed mock ${mockContent} must drop usage entirely`,
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('hostile mock file leaks no secret-shaped material and never throws', () => {
  const mockContent = JSON.stringify({
    inputTokens: 10,
    outputTokens: 20,
    totalTokens: 30,
    apiKey: SECRET,
    note: SECRET,
  });
  const { dir, outPath, result } = build({ mockFile: true, mockContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const raw = readFileSync(outPath, 'utf8');
    assert.ok(!raw.includes(SECRET), 'no secret-shaped string in the emitted result');
    const usage = JSON.parse(raw).normalizedOutput.usage;
    // Only the three allowed integer keys survive sanitisation.
    assert.deepEqual(Object.keys(usage).sort(), ['inputTokens', 'outputTokens', 'totalTokens']);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
