// Story 3d-7 (FR69) — unit tier for `runner.mjs build` provider-usage capture.
//
// Runs the dependency-free helper as a subprocess (exactly as the entrypoint invokes it at
// step 6) and inspects the emitted runner-result.v1 normalizedOutput.providerUsage. Proves:
//   * with NO mock file, the real path emits the documented `not_exposed` marker (spike outcome)
//     — never a fabricated number — carrying only signalState + a non-secret accountLabel + asOf,
//   * the accountLabel is derived from the NON-SECRET --auth-var name (claude:oauth / claude:api /
//     claude:unknown), never a token value (Trap T1),
//   * with a DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE present, the `available` shape is emitted with
//     the window numbers + timestamps (offline happy path),
//   * a hostile mock file carrying secret-shaped extra keys leaks NOTHING — only the four allowed
//     numeric/timestamp window keys survive (AC4 no-secret), and capture never throws.
//
// Twin of runners/codex/test/runner-provider-usage.test.mjs (only provider/label values differ).
// Run locally: `node --test runners/claude/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const PROVIDER = 'claude';
const SECRET = 'sk-ant-supersecrettokenvalue';

// Runs `build --stage spec` against a minimal bundle and returns the parsed result document.
function build({ authVar, mockFile, mockContent } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'provider-usage-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const summaryPath = join(dir, 'summary.txt');
  const outPath = join(dir, 'runner-result.v1.json');
  writeFileSync(
    bundlePath,
    JSON.stringify({
      schemaVersion: 1,
      workflowRunId: 'run_provider_usage',
      runnerExecutionId: 'rex_provider_usage',
      classification: 'shareable-redacted',
    }),
    'utf8',
  );
  writeFileSync(summaryPath, 'Generated specification artifact\n', 'utf8');
  const env = { ...process.env };
  delete env.DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE;
  if (mockFile) {
    const mockPath = join(dir, 'provider-usage.mock.json');
    writeFileSync(mockPath, mockContent, 'utf8');
    env.DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE = mockPath;
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
  ];
  if (authVar !== undefined) args.push('--auth-var', authVar);
  const result = spawnSync(process.execPath, args, { encoding: 'utf8', env });
  return { dir, outPath, result };
}

test('no mock file -> documented not_exposed marker, non-secret oauth label, asOf present', () => {
  const { dir, outPath, result } = build({ authVar: 'CLAUDE_CODE_OAUTH_TOKEN' });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const usage = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput.providerUsage;
    assert.equal(usage.signalState, 'not_exposed');
    assert.equal(usage.accountLabel, `${PROVIDER}:oauth`);
    assert.ok(typeof usage.asOf === 'string' && usage.asOf.length > 0);
    assert.equal(usage.fiveHour, undefined);
    assert.equal(usage.weekly, undefined);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('accountLabel maps from the non-secret --auth-var name only', () => {
  for (const [authVar, label] of [
    ['CLAUDE_CODE_OAUTH_TOKEN', `${PROVIDER}:oauth`],
    ['ANTHROPIC_API_KEY', `${PROVIDER}:api`],
    ['SOMETHING_ELSE', `${PROVIDER}:unknown`],
    [undefined, `${PROVIDER}:unknown`],
  ]) {
    const { dir, outPath, result } = build({ authVar });
    try {
      assert.equal(result.status, 0, `stderr=${result.stderr}`);
      const usage = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput.providerUsage;
      assert.equal(usage.accountLabel, label, `authVar=${authVar}`);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test('mock file present -> available shape with window numbers + timestamps', () => {
  const mockContent = JSON.stringify({
    signalState: 'available',
    fiveHour: { usedFraction: 0.42, used: 42, limit: 100, resetsAt: '2030-01-01T05:00:00Z' },
    weekly: { usedFraction: 0.1, used: 70, limit: 700, resetsAt: '2030-01-06T00:00:00Z' },
  });
  const { dir, outPath, result } = build({ authVar: 'ANTHROPIC_API_KEY', mockFile: true, mockContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const usage = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput.providerUsage;
    assert.equal(usage.signalState, 'available');
    assert.equal(usage.accountLabel, `${PROVIDER}:api`);
    assert.equal(usage.fiveHour.used, 42);
    assert.equal(usage.fiveHour.limit, 100);
    assert.equal(usage.weekly.usedFraction, 0.1);
    assert.ok(usage.asOf.length > 0);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('hostile mock file leaks no secret-shaped material and never throws', () => {
  const mockContent = JSON.stringify({
    signalState: 'available',
    token: SECRET,
    accountLabel: SECRET,
    fiveHour: { used: 5, limit: 100, apiKey: SECRET, resetsAt: '2030-01-01T05:00:00Z' },
    weekly: { secret: SECRET },
  });
  const { dir, outPath, result } = build({ authVar: 'CLAUDE_CODE_OAUTH_TOKEN', mockFile: true, mockContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const raw = readFileSync(outPath, 'utf8');
    assert.ok(!raw.includes(SECRET), 'no secret-shaped string in the emitted result');
    const usage = JSON.parse(raw).normalizedOutput.providerUsage;
    // accountLabel is forced from the auth-var, never the mock file's value.
    assert.equal(usage.accountLabel, `${PROVIDER}:oauth`);
    // Only the four allowed window keys survive sanitisation.
    assert.deepEqual(Object.keys(usage.fiveHour).sort(), ['limit', 'resetsAt', 'used']);
    // weekly carried only a secret key -> dropped entirely.
    assert.equal(usage.weekly, undefined);
    assert.equal(usage.token, undefined);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
