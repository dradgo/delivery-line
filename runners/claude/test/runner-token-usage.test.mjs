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
// Twin of runners/codex/test/runner-token-usage.test.mjs (identical — only the RUNNER path differs).
// Run locally: `node --test runners/claude/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const SECRET = 'sk-ant-supersecrettokenvalue';

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
  // Story 3g-5 follow-up — the real path passes the captured Claude `--output-format stream-json`
  // (or single-object `json`) via --events-file; the legacy/mock path passes raw text via
  // --summary-file. Exactly one source is wired per build.
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
    'CLAUDE_CODE_OAUTH_TOKEN',
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

// Story 3g-3 follow-up (FR74) — the REVIEW arm also carries reviewer token usage. A REVIEW
// invocation emits review-result.v1 (not runner-result.v1), so its usage rides on the top-level
// `usage` key — the ONLY channel by which a reviewer's counts reach the runner_executions columns.
test('review stage with mock usage -> counts emitted onto review-result.v1 top-level usage', () => {
  const mockContent = JSON.stringify({ inputTokens: 1200, outputTokens: 800, totalTokens: 2000 });
  const { dir, outPath, result } = build({ mockFile: true, mockContent, stage: 'review' });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const doc = JSON.parse(readFileSync(outPath, 'utf8'));
    assert.equal(doc.schemaVersion, 1);
    assert.deepEqual(doc.usage, { inputTokens: 1200, outputTokens: 800, totalTokens: 2000 });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('review stage with NO usage -> usage key ABSENT (byte-identical to pre-follow-up)', () => {
  const { dir, outPath, result } = build({ stage: 'review' });
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

// ===== Story 3g-5 (FR74) — REAL usage from the Claude `--output-format {json|stream-json}` output.
// Claude reports usage in snake_case with NO blended total; the parser maps input_tokens/output_tokens
// -> camelCase and sets totalTokens to their blended sum (codex precedent); cache_* subsets dropped. =====

test('claude --output-format json (single object) -> real input/output/total lifted from top-level usage', () => {
  // The documented `--output-format json` shape: ONE object, final text at top-level `result`,
  // usage in snake_case (cache_* fields are informational subsets the 3-field schema drops).
  const eventsContent = JSON.stringify({
    type: 'result',
    subtype: 'success',
    is_error: false,
    session_id: 'sess_abc',
    num_turns: 2,
    result: 'The reconstructed Claude plan text.',
    total_cost_usd: 0.0045,
    usage: {
      input_tokens: 1250,
      output_tokens: 340,
      cache_creation_input_tokens: 0,
      cache_read_input_tokens: 512,
    },
  });
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const doc = JSON.parse(readFileSync(outPath, 'utf8'));
    // input_tokens + output_tokens = 1250 + 340 = 1590 (blended total; cache_* subsets dropped).
    assert.deepEqual(doc.normalizedOutput.usage, {
      inputTokens: 1250,
      outputTokens: 340,
      totalTokens: 1590,
    });
    // The artifact was reconstructed from the top-level `result`, not the raw JSON.
    assert.equal(doc.normalizedOutput.summary, 'The reconstructed Claude plan text.');
    // cache_* keys must NOT leak into the emitted usage.
    const raw = readFileSync(outPath, 'utf8');
    assert.ok(!raw.includes('cache_'), 'cache_* subset keys must not leak into the emitted usage');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('claude stream-json (JSONL) with MULTIPLE assistant events -> artifact is the FINAL result, not the running narration', () => {
  // Streaming `stream-json` emits incremental `assistant` progress events then a terminal
  // `type:"result"` carrying the FINAL answer + cumulative usage. The `result` string IS the
  // deliverable — the progress narration must never pollute the artifact/review rationale.
  const eventsContent = [
    JSON.stringify({ type: 'system', subtype: 'init', session_id: 's1' }),
    JSON.stringify({
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Progress: reading the spec now.' }] },
    }),
    JSON.stringify({
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Still working through the edge cases.' }] },
    }),
    JSON.stringify({
      type: 'result',
      subtype: 'success',
      is_error: false,
      result: 'The final reviewed specification content.',
      usage: { input_tokens: 900, output_tokens: 120, cache_read_input_tokens: 64 },
    }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(normalizedOutput.summary, 'The final reviewed specification content.');
    assert.ok(
      !normalizedOutput.summary.includes('Progress: reading'),
      'progress narration must not leak into the artifact',
    );
    // input 900 + output 120 = 1020 blended.
    assert.deepEqual(normalizedOutput.usage, {
      inputTokens: 900,
      outputTokens: 120,
      totalTokens: 1020,
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('claude result event with NO usage -> usage omitted, message still reconstructed', () => {
  const eventsContent = JSON.stringify({
    type: 'result',
    subtype: 'success',
    result: 'Plan without usage.',
  });
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

test('claude malformed usage (negative / non-integer) via events -> non-fatal, usage sanitized away', () => {
  const eventsContent = JSON.stringify({
    type: 'result',
    result: 'Plan with garbage usage.',
    usage: { input_tokens: -5, output_tokens: 3.5 },
  });
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `run must still succeed; stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'), false);
    assert.equal(normalizedOutput.summary, 'Plan with garbage usage.');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('--events-file with PLAIN TEXT (non-JSON) -> falls back to raw text, usage absent (back-compat)', () => {
  // The mandatory plain-text fallback: text-mode mocks + legacy stdout routed through --events-file
  // must keep working verbatim so every offline mock and entrypoint test stays green.
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
test('--events-file with unrelated JSON object -> falls back to raw text, usage absent', () => {
  // Plain-text artifacts can contain JSON snippets. Only recognized Claude events should switch the
  // parser out of raw-text fallback mode.
  const eventsContent = '{"example":"plain artifact json"}\nSecond line.\n';
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'), false);
    assert.equal(normalizedOutput.summary, '{"example":"plain artifact json"}');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('assistant incremental usage without result usage -> usage omitted, message fallback kept', () => {
  // Assistant usage is incremental; only the terminal result event is cumulative enough to persist.
  const eventsContent = JSON.stringify({
    type: 'assistant',
    message: {
      content: [{ type: 'text', text: 'Fallback assistant text.' }],
      usage: { input_tokens: 10, output_tokens: 2 },
    },
  });
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.equal(normalizedOutput.summary, 'Fallback assistant text.');
    assert.equal(Object.prototype.hasOwnProperty.call(normalizedOutput, 'usage'), false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('review stage with claude events usage -> counts emitted onto review-result.v1 top-level usage', () => {
  const eventsContent = JSON.stringify({
    type: 'result',
    subtype: 'success',
    result: 'VERDICT: pass\nThe change is sound.',
    usage: { input_tokens: 2000, output_tokens: 150 },
  });
  const { dir, outPath, result } = build({ eventsContent, stage: 'review' });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const doc = JSON.parse(readFileSync(outPath, 'utf8'));
    assert.equal(doc.schemaVersion, 1);
    assert.deepEqual(doc.usage, { inputTokens: 2000, outputTokens: 150, totalTokens: 2150 });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('REAL claude stream-json sample: result-event top-level usage used, modelUsage (camelCase per-model) IGNORED, assistant incremental overridden', () => {
  // Captured from a real `claude -p 'hi' --output-format stream-json --verbose` run (2026-07-04):
  // incremental `assistant` events report output_tokens:2 mid-stream; the terminal `result` event
  // carries the cumulative usage (input 11135 / output 263) PLUS a `modelUsage` map whose keys are
  // camelCase (inputTokens/outputTokens) — the runner must read the top-level snake_case `usage`,
  // NOT modelUsage (the story's explicit trap), and the final result event must override the
  // assistant's incremental counts.
  const eventsContent = [
    JSON.stringify({ type: 'system', subtype: 'init', session_id: 's1', model: 'claude-opus-4-8' }),
    JSON.stringify({
      type: 'assistant',
      message: {
        role: 'assistant',
        content: [{ type: 'thinking', thinking: '', signature: 'x' }],
        usage: { input_tokens: 11135, cache_read_input_tokens: 20319, output_tokens: 2 },
      },
    }),
    JSON.stringify({
      type: 'assistant',
      message: {
        role: 'assistant',
        content: [{ type: 'text', text: 'Hi! Ready when you are.' }],
        usage: { input_tokens: 11135, cache_read_input_tokens: 20319, output_tokens: 2 },
      },
    }),
    JSON.stringify({
      type: 'result',
      subtype: 'success',
      is_error: false,
      num_turns: 1,
      result: 'Hi! Ready when you are.',
      total_cost_usd: 0.1947,
      usage: {
        input_tokens: 11135,
        cache_creation_input_tokens: 12235,
        cache_read_input_tokens: 20319,
        output_tokens: 263,
      },
      modelUsage: {
        'claude-opus-4-8[1m]': { inputTokens: 999999, outputTokens: 999999 },
      },
    }),
  ].join('\n');
  const { dir, outPath, result } = build({ eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    // Final cumulative usage from the result event (11135 + 263 = 11398); NOT the assistant's
    // incremental output_tokens:2, and NOT modelUsage's 999999.
    assert.deepEqual(normalizedOutput.usage, {
      inputTokens: 11135,
      outputTokens: 263,
      totalTokens: 11398,
    });
    assert.equal(normalizedOutput.summary, 'Hi! Ready when you are.');
    const raw = readFileSync(outPath, 'utf8');
    assert.ok(!raw.includes('999999'), 'modelUsage (per-model camelCase) must never be read');
    assert.ok(!raw.includes('cache_'), 'cache_* subset keys must not leak');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('mock file OVERRIDES real events usage (deterministic offline seam wins)', () => {
  // `usage = buildUsage() ?? eventsUsage` — the DELIVERYLINE_USAGE_MOCK_FILE seam takes precedence so
  // offline/CI stays deterministic even when a real events stream is also present.
  const mockContent = JSON.stringify({ inputTokens: 11, outputTokens: 22, totalTokens: 33 });
  const eventsContent = JSON.stringify({
    type: 'result',
    result: 'Real events text.',
    usage: { input_tokens: 999, output_tokens: 888 },
  });
  const { dir, outPath, result } = build({ mockFile: true, mockContent, eventsContent });
  try {
    assert.equal(result.status, 0, `stderr=${result.stderr}`);
    const normalizedOutput = JSON.parse(readFileSync(outPath, 'utf8')).normalizedOutput;
    assert.deepEqual(normalizedOutput.usage, { inputTokens: 11, outputTokens: 22, totalTokens: 33 });
    // Artifact text still comes from the events stream (only usage is mock-overridden).
    assert.equal(normalizedOutput.summary, 'Real events text.');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
