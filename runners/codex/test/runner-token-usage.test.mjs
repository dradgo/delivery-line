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

// Runs `build --stage <stage>` (default spec) against a minimal bundle and returns the parsed
// result document.
function build({ mockFile, mockContent, eventsContent, stage = 'spec' } = {}) {
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
  // Story 3g-3 follow-up — the real path passes the captured codex `--json` JSONL via --events-file;
  // the legacy/mock path passes raw text via --summary-file. Exactly one source is wired per build.
  let source;
  if (eventsContent !== undefined) {
    const eventsPath = join(dir, 'events.jsonl');
    writeFileSync(eventsPath, eventsContent, 'utf8');
    source = ['--events-file', eventsPath];
  } else {
    source = ['--summary-file', summaryPath];
  }
  const args = [
    RUNNER,
    'build',
    '--bundle',
    bundlePath,
    '--stage',
    stage,
    ...source,
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

// ===== Story 3g-3 follow-up (FR74) — REVIEW arm also carries reviewer token usage =====
// A REVIEW invocation emits review-result.v1 (not runner-result.v1), so its usage rides on the
// top-level `usage` key — the ONLY channel by which a reviewer's token counts reach the backend.
test('review stage -> usage lifted from --json events onto review-result.v1 top-level usage', () => {
  const eventsContent = [
    JSON.stringify({ type: 'turn.started' }),
    JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'VERDICT: pass' },
    }),
    JSON.stringify({
      type: 'turn.completed',
      usage: { input_tokens: 173092, cached_input_tokens: 127616, output_tokens: 1868 },
    }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent, stage: 'review' });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const doc = JSON.parse(readFileSync(outPath, 'utf8'));
    assert.equal(doc.outcome, 'pass');
    assert.deepEqual(doc.usage, {
      inputTokens: 173092,
      outputTokens: 1868,
      totalTokens: 174960,
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('review stage with NO usage -> usage key ABSENT (byte-identical to pre-follow-up)', () => {
  const eventsContent = [
    JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'VERDICT: concern' },
    }),
    JSON.stringify({ type: 'turn.completed' }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent, stage: 'review' });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const doc = JSON.parse(readFileSync(outPath, 'utf8'));
    assert.equal(
      Object.prototype.hasOwnProperty.call(doc, 'usage'),
      false,
      'usage must be omitted entirely when no counts are available',
    );
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

// ===== Story 3g-3 follow-up (FR74) — REAL usage from the codex `--json` JSONL event stream =====

test('codex --json events -> real input/output/total lifted from turn.completed.usage', () => {
  // The exact shape captured from codex v0.135.0 `codex exec --json` (turn.completed.usage).
  const eventsContent = [
    JSON.stringify({ type: 'thread.started', thread_id: 't1' }),
    JSON.stringify({ type: 'turn.started' }),
    JSON.stringify({
      type: 'item.completed',
      item: { id: 'item_1', type: 'agent_message', text: 'The reconstructed plan text.' },
    }),
    JSON.stringify({
      type: 'turn.completed',
      usage: {
        input_tokens: 34294,
        cached_input_tokens: 25856,
        output_tokens: 112,
        reasoning_output_tokens: 56,
      },
    }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const doc = JSON.parse(readFileSync(outPath, 'utf8'));
    // input_tokens + output_tokens = 34294 + 112 = 34406 (codex blended total; subsets dropped).
    assert.deepEqual(doc.normalizedOutput.usage, {
      inputTokens: 34294,
      outputTokens: 112,
      totalTokens: 34406,
    });
    // The artifact was reconstructed from the agent_message text, not the raw JSONL.
    assert.equal(doc.normalizedOutput.summary, 'The reconstructed plan text.');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('codex --json with MULTIPLE agent_messages -> artifact is the FINAL message, not the running narration', () => {
  // Codex streams progress commentary as SEPARATE agent_message items ("reading the spec now", "jq
  // not installed", ...) and its actual deliverable as the FINAL agent_message. Concatenating them
  // all pollutes the artifact/review-rationale with the running monologue — reconstruct from the
  // LAST agent_message only (matching codex's own --output-last-message notion of "the answer").
  const eventsContent = [
    JSON.stringify({ type: 'thread.started', thread_id: 't1' }),
    JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'Progress: reading the spec now.' },
    }),
    JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'jq is not installed; falling back to shell reads.' },
    }),
    JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'The final reviewed specification content.' },
    }),
    JSON.stringify({ type: 'turn.completed', usage: { input_tokens: 100, output_tokens: 20 } }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(normalizedOutput.summary, 'The final reviewed specification content.');
    assert.ok(
      !normalizedOutput.summary.includes('jq is not installed'),
      'progress narration must not leak into the artifact',
    );
    assert.ok(!normalizedOutput.summary.includes('Progress: reading'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('codex --json events with NO turn.completed usage -> usage omitted, message still reconstructed', () => {
  const eventsContent = [
    JSON.stringify({ type: 'turn.started' }),
    JSON.stringify({
      type: 'item.completed',
      item: { type: 'agent_message', text: 'Plan without usage.' },
    }),
    JSON.stringify({ type: 'turn.completed' }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'), false);
    assert.equal(normalizedOutput.summary, 'Plan without usage.');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('--events-file with PLAIN TEXT (non-JSON) -> falls back to raw text, usage absent (back-compat)', () => {
  const { dir, outPath, result } = build({ eventsContent: 'Just a plain text plan.\nSecond line.\n' });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'), false);
    assert.equal(normalizedOutput.summary, 'Just a plain text plan.');
  } finally {
    rmSync(dir, { recursive: true, force: true });
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
