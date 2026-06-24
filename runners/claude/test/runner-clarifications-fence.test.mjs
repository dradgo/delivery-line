// Story 3e-1 — unit tier for `runner.mjs build` clarifications fence-split (spec stage).
//
// Runs the dependency-free helper as a subprocess (exactly as the entrypoint invokes it at
// step 6) and inspects the emitted runner-result.v1 specArtifact.questions + the materialized
// spec.md. Proves:
//   * a fenced ```clarifications block at the SPEC stage is lifted into specArtifact.questions
//     and STRIPPED from the persisted spec.md,
//   * an ABSENT fence omits the questions field entirely (never `questions: []`) and leaves
//     spec.md byte-identical to the raw agent output,
//   * MALFORMED JSON in the fence is NON-FATAL — the field is omitted, the run still succeeds,
//   * the questions channel is SPEC-stage only (an implementationPlan result never carries it).
//
// Twin of runners/codex/test/runner-clarifications-fence.test.mjs (only provider text differs).
// Run locally: `node --test runners/claude/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');

// Runs `build --stage <stage>` against a minimal bundle whose agent stdout is `summary`.
function build({ stage = 'spec', summary } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'clarifications-fence-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const summaryPath = join(dir, 'summary.txt');
  const outPath = join(dir, 'runner-result.v1.json');
  writeFileSync(
    bundlePath,
    JSON.stringify({
      schemaVersion: 1,
      workflowRunId: 'run_clarifications',
      runnerExecutionId: 'rex_clarifications',
      classification: 'shareable-redacted',
    }),
    'utf8',
  );
  writeFileSync(summaryPath, summary, 'utf8');
  const result = spawnSync(
    process.execPath,
    [RUNNER, 'build', '--bundle', bundlePath, '--stage', stage, '--summary-file', summaryPath, '--out', outPath],
    { encoding: 'utf8' },
  );
  const document = JSON.parse(readFileSync(outPath, 'utf8'));
  return { dir, outPath, result, document, artifact: document.artifactReferences[0] };
}

const SPEC_BODY = 'Design specification line one\nDesign specification line two\n';
const FENCE =
  '```clarifications\n[{"questionId":"Q-001","questionText":"Confirm whether archived records are included."}]\n```\n';

test('spec stage: fenced clarifications are lifted into specArtifact.questions and stripped from spec.md', () => {
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY + FENCE });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(artifact.questions, [
    { questionId: 'Q-001', questionText: 'Confirm whether archived records are included.' },
  ]);
  const specMd = readFileSync(join(dir, 'artifacts/run_clarifications/spec.md'), 'utf8');
  assert.ok(!specMd.includes('```clarifications'), 'fence must be stripped from spec.md');
  assert.ok(specMd.includes('Design specification line one'), 'spec body is preserved');
});

test('spec stage: no fence omits the questions field entirely and leaves spec.md byte-identical', () => {
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!('questions' in artifact), 'questions field must be omitted when no fence is present');
  const specMd = readFileSync(join(dir, 'artifacts/run_clarifications/spec.md'), 'utf8');
  assert.equal(specMd, SPEC_BODY, 'spec.md is byte-identical to the raw agent output');
});

test('spec stage: malformed JSON in the fence is non-fatal — questions omitted, run still succeeds', () => {
  const malformed = '```clarifications\n[{"questionId": "Q-001", oops not json}]\n```\n';
  const { result, artifact } = build({ stage: 'spec', summary: SPEC_BODY + malformed });
  assert.equal(result.status, 0, 'a clarification problem never fails spec delivery');
  assert.ok(!('questions' in artifact), 'malformed fence yields no questions field');
});

test('spec stage: a malformed fence is PRESERVED in spec.md (strip only on a successful parse)', () => {
  const malformed = '```clarifications\n[{"questionId": "Q-001", oops not json}]\n```\n';
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY + malformed });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!('questions' in artifact), 'malformed fence yields no questions field');
  const specMd = readFileSync(join(dir, 'artifacts/run_clarifications/spec.md'), 'utf8');
  assert.ok(
    specMd.includes('```clarifications'),
    'a malformed clarifications block is kept in the spec, not silently dropped',
  );
});

test('spec stage: a well-formed but question-less fence is PRESERVED (strip needs >=1 question)', () => {
  const emptyFence = '```clarifications\n[]\n```\n';
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY + emptyFence });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!('questions' in artifact), 'an empty array yields no questions field');
  const specMd = readFileSync(join(dir, 'artifacts/run_clarifications/spec.md'), 'utf8');
  assert.ok(
    specMd.includes('```clarifications'),
    'a zero-question fence stays in the spec (the fence is only stripped when it yields questions)',
  );
});

test('spec stage: stripping a valid fence preserves surrounding CRLF bytes and the trailing newline', () => {
  const crlfBody = 'Line one\r\nLine two\r\n';
  const crlfFence =
    '```clarifications\r\n[{"questionId":"Q-CRLF","questionText":"Keep CRLF?"}]\r\n```\r\n';
  const crlfTail = 'Tail line\r\n';
  const { result, artifact, dir } = build({
    stage: 'spec',
    summary: crlfBody + crlfFence + crlfTail,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(artifact.questions, [{ questionId: 'Q-CRLF', questionText: 'Keep CRLF?' }]);
  const specMd = readFileSync(join(dir, 'artifacts/run_clarifications/spec.md'), 'utf8');
  assert.ok(!specMd.includes('```clarifications'), 'fence is removed');
  assert.equal(
    specMd,
    crlfBody + crlfTail,
    'the slice preserves surrounding CRLF endings + the trailing newline verbatim (no \\n normalization)',
  );
});

test('implementationPlan stage: questions channel is spec-only (never attached to a plan)', () => {
  const { result, artifact } = build({
    stage: 'implementationPlan',
    summary: 'Step one\nStep two\n' + FENCE,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(artifact.artifactType, 'implementationPlan');
  assert.ok(!('questions' in artifact), 'implementationPlan never carries questions');
});
