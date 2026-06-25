// Story 3e-2 — unit tier for `runner.mjs build` clarificationAcknowledgements fence-split (spec).
//
// Sibling of runner-clarifications-fence.test.mjs. Runs the dependency-free helper as a subprocess
// and inspects the emitted runner-result.v1 specArtifact.clarificationAcknowledgements + the
// materialized spec.md. Proves:
//   * a fenced ```clarificationAcknowledgements block at the SPEC stage is lifted into
//     specArtifact.clarificationAcknowledgements and STRIPPED from the persisted spec.md,
//   * an ABSENT fence omits the field entirely (never `[]`) and leaves spec.md byte-identical,
//   * MALFORMED JSON in the fence is NON-FATAL — the field is omitted, the run still succeeds,
//   * a malformed / zero-item fence is PRESERVED in spec.md (strip only on a successful parse),
//   * stripping preserves surrounding CRLF + trailing newline verbatim,
//   * the channel is SPEC-stage only (an implementationPlan result never carries it),
//   * BOTH fences (questions + acknowledgements) in one spec output are each lifted and stripped.
//
// Twin of runners/claude/test/runner-clarification-acknowledgements-fence.test.mjs (only provider text differs).
// Run locally: `node --test runners/codex/test/` (zero deps; node:test built-in).
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
  const dir = mkdtempSync(join(tmpdir(), 'clarification-acks-fence-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const summaryPath = join(dir, 'summary.txt');
  const outPath = join(dir, 'runner-result.v1.json');
  writeFileSync(
    bundlePath,
    JSON.stringify({
      schemaVersion: 1,
      workflowRunId: 'run_clarificationacks',
      runnerExecutionId: 'rex_clarificationacks',
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
  '```clarificationAcknowledgements\n[{"questionId":"Q-001","addressed":true},{"questionId":"Q-002","addressed":false}]\n```\n';

test('spec stage: fenced acknowledgements are lifted into specArtifact.clarificationAcknowledgements and stripped from spec.md', () => {
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY + FENCE });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(artifact.clarificationAcknowledgements, [
    { questionId: 'Q-001', addressed: true },
    { questionId: 'Q-002', addressed: false },
  ]);
  const specMd = readFileSync(join(dir, 'artifacts/run_clarificationacks/spec.md'), 'utf8');
  assert.ok(!specMd.includes('```clarificationAcknowledgements'), 'fence must be stripped from spec.md');
  assert.ok(specMd.includes('Design specification line one'), 'spec body is preserved');
});

test('spec stage: no fence omits the field entirely and leaves spec.md byte-identical', () => {
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(
    !('clarificationAcknowledgements' in artifact),
    'field must be omitted when no fence is present',
  );
  const specMd = readFileSync(join(dir, 'artifacts/run_clarificationacks/spec.md'), 'utf8');
  assert.equal(specMd, SPEC_BODY, 'spec.md is byte-identical to the raw agent output');
});

test('spec stage: malformed JSON in the fence is non-fatal — field omitted, run still succeeds', () => {
  const malformed = '```clarificationAcknowledgements\n[{"questionId": "Q-001", oops}]\n```\n';
  const { result, artifact } = build({ stage: 'spec', summary: SPEC_BODY + malformed });
  assert.equal(result.status, 0, 'an acknowledgement problem never fails spec delivery');
  assert.ok(!('clarificationAcknowledgements' in artifact), 'malformed fence yields no field');
});

test('spec stage: a malformed fence is PRESERVED in spec.md (strip only on a successful parse)', () => {
  const malformed = '```clarificationAcknowledgements\n[{"questionId": "Q-001", oops}]\n```\n';
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY + malformed });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!('clarificationAcknowledgements' in artifact), 'malformed fence yields no field');
  const specMd = readFileSync(join(dir, 'artifacts/run_clarificationacks/spec.md'), 'utf8');
  assert.ok(
    specMd.includes('```clarificationAcknowledgements'),
    'a malformed acknowledgements block is kept in the spec, not silently dropped',
  );
});

test('spec stage: a well-formed but empty fence is PRESERVED (strip needs >=1 acknowledgement)', () => {
  const emptyFence = '```clarificationAcknowledgements\n[]\n```\n';
  const { result, artifact, dir } = build({ stage: 'spec', summary: SPEC_BODY + emptyFence });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!('clarificationAcknowledgements' in artifact), 'an empty array yields no field');
  const specMd = readFileSync(join(dir, 'artifacts/run_clarificationacks/spec.md'), 'utf8');
  assert.ok(
    specMd.includes('```clarificationAcknowledgements'),
    'a zero-item fence stays in the spec (stripped only when it yields acknowledgements)',
  );
});

test('spec stage: stripping a valid fence preserves surrounding CRLF bytes and the trailing newline', () => {
  const crlfBody = 'Line one\r\nLine two\r\n';
  const crlfFence =
    '```clarificationAcknowledgements\r\n[{"questionId":"Q-CRLF","addressed":true}]\r\n```\r\n';
  const crlfTail = 'Tail line\r\n';
  const { result, artifact, dir } = build({
    stage: 'spec',
    summary: crlfBody + crlfFence + crlfTail,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(artifact.clarificationAcknowledgements, [{ questionId: 'Q-CRLF', addressed: true }]);
  const specMd = readFileSync(join(dir, 'artifacts/run_clarificationacks/spec.md'), 'utf8');
  assert.ok(!specMd.includes('```clarificationAcknowledgements'), 'fence is removed');
  assert.equal(
    specMd,
    crlfBody + crlfTail,
    'the slice preserves surrounding CRLF endings + the trailing newline verbatim (no \\n normalization)',
  );
});

test('implementationPlan stage: acknowledgements channel is spec-only (never attached to a plan)', () => {
  const { result, artifact } = build({
    stage: 'implementationPlan',
    summary: 'Step one\nStep two\n' + FENCE,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(artifact.artifactType, 'implementationPlan');
  assert.ok(
    !('clarificationAcknowledgements' in artifact),
    'implementationPlan never carries acknowledgements',
  );
});

test('spec stage: BOTH questions and acknowledgements fences are each lifted and stripped', () => {
  const questionsFence =
    '```clarifications\n[{"questionId":"Q-001","questionText":"Confirm scope?"}]\n```\n';
  const acksFence = '```clarificationAcknowledgements\n[{"questionId":"Q-001","addressed":true}]\n```\n';
  const { result, artifact, dir } = build({
    stage: 'spec',
    summary: SPEC_BODY + questionsFence + acksFence,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(artifact.questions, [{ questionId: 'Q-001', questionText: 'Confirm scope?' }]);
  assert.deepEqual(artifact.clarificationAcknowledgements, [{ questionId: 'Q-001', addressed: true }]);
  const specMd = readFileSync(join(dir, 'artifacts/run_clarificationacks/spec.md'), 'utf8');
  assert.ok(!specMd.includes('```clarifications'), 'questions fence stripped');
  assert.ok(!specMd.includes('```clarificationAcknowledgements'), 'acknowledgements fence stripped');
  assert.ok(specMd.includes('Design specification line one'), 'spec body preserved');
});
