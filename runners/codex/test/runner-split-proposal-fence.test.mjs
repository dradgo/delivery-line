// Story 3f-4 — unit tier for `runner.mjs build` split-proposal fence-split (review + split mode).
//
// Runs the dependency-free helper as a subprocess (exactly as the entrypoint invokes it at step 6)
// and inspects the emitted split-proposal.v1 payload. Proves:
//   * a REVIEW dispatch whose bundle sets splitProposalRequested=true emits a split-proposal.v1
//     payload (subtasks[]+dependencies[]) lifted from a fenced ```split block, NOT a verdict,
//   * an ABSENT/MALFORMED/subtask-less fence is NON-FATAL — the runner emits an empty proposal with
//     failureCategory set so the backend harvester degrades the panel to "unavailable" (R5),
//   * malformed dependency edges are dropped while usable subtasks survive,
//   * the split channel is gated on the flag: a plain REVIEW dispatch still emits review-result.v1.
//
// Twin of runners/claude/test/runner-split-proposal-fence.test.mjs (only provider identity differs).
// Run locally: `node --test runners/codex/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const REVIEWER_IDENTITY = 'codex';

// Runs `build --stage review` against a minimal bundle whose agent stdout is `summary`.
// splitProposalRequested toggles the bundle flag that selects the split arm in commandBuild.
function build({ summary, splitProposalRequested = true } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'split-fence-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const summaryPath = join(dir, 'summary.txt');
  const outPath = join(dir, 'result.v1.json');
  const bundle = {
    schemaVersion: 1,
    workflowRunId: 'run_splitfence',
    runnerExecutionId: 'rex_splitfence',
    classification: 'shareable-redacted',
  };
  if (splitProposalRequested) bundle.splitProposalRequested = true;
  writeFileSync(bundlePath, JSON.stringify(bundle), 'utf8');
  writeFileSync(summaryPath, summary, 'utf8');
  const result = spawnSync(
    process.execPath,
    [RUNNER, 'build', '--bundle', bundlePath, '--stage', 'review', '--summary-file', summaryPath, '--out', outPath],
    { encoding: 'utf8' },
  );
  const document = JSON.parse(readFileSync(outPath, 'utf8'));
  return { dir, outPath, result, document };
}

const REVIEW_BODY = 'This artifact is large and should be decomposed.\n';
const FENCE =
  '```split\n{"schemaVersion":1,"subtasks":[{"ordinal":1,"title":"Part one","scope":"Do the first thing"},{"ordinal":2,"title":"Part two","scope":"Do the second thing"}],"dependencies":[{"fromOrdinal":2,"toOrdinal":1}]}\n```\n';

test('split mode: a fenced ```split block is lifted into subtasks + dependencies (no failure)', () => {
  const { result, document } = build({ summary: REVIEW_BODY + FENCE });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(document.schemaVersion, 1);
  assert.equal(document.workflowRunId, 'run_splitfence');
  assert.equal(document.runnerExecutionId, 'rex_splitfence');
  assert.equal(document.reviewerModelIdentity, REVIEWER_IDENTITY);
  assert.equal(document.failureCategory, null);
  assert.deepEqual(document.subtasks, [
    { ordinal: 1, title: 'Part one', scope: 'Do the first thing' },
    { ordinal: 2, title: 'Part two', scope: 'Do the second thing' },
  ]);
  assert.deepEqual(document.dependencies, [{ fromOrdinal: 2, toOrdinal: 1 }]);
  // It is a proposal, not a verdict.
  assert.ok(!('outcome' in document), 'split proposal must NOT carry a review outcome');
});

test('split mode: an ABSENT fence yields an empty proposal with failureCategory set (degrade)', () => {
  const { result, document } = build({ summary: REVIEW_BODY });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(document.subtasks, []);
  assert.deepEqual(document.dependencies, []);
  assert.equal(document.failureCategory, 'runner_malformed_output');
});

test('split mode: malformed JSON in the fence is non-fatal — empty proposal, degrade flagged', () => {
  const malformed = '```split\n{"subtasks": [oops not json]}\n```\n';
  const { result, document } = build({ summary: REVIEW_BODY + malformed });
  assert.equal(result.status, 0, 'a malformed proposal never fails the dispatch');
  assert.deepEqual(document.subtasks, []);
  assert.equal(document.failureCategory, 'runner_malformed_output');
});

test('split mode: a well-formed but subtask-less fence degrades (a proposal needs >=1 subtask)', () => {
  const emptyFence = '```split\n{"schemaVersion":1,"subtasks":[],"dependencies":[]}\n```\n';
  const { result, document } = build({ summary: REVIEW_BODY + emptyFence });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(document.subtasks, []);
  assert.equal(document.failureCategory, 'runner_malformed_output');
});

test('split mode: malformed dependency edges are dropped while usable subtasks survive', () => {
  const fence =
    '```split\n{"schemaVersion":1,"subtasks":[{"ordinal":1,"title":"Only","scope":"only scope"}],"dependencies":[{"fromOrdinal":1},{"fromOrdinal":1,"toOrdinal":2}]}\n```\n';
  const { result, document } = build({ summary: REVIEW_BODY + fence });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(document.subtasks.length, 1);
  // The first edge is missing toOrdinal (dropped); the second is well-formed (kept).
  assert.deepEqual(document.dependencies, [{ fromOrdinal: 1, toOrdinal: 2 }]);
  assert.equal(document.failureCategory, null);
});

test('split flag gates the channel: a plain REVIEW dispatch still emits a review-result verdict', () => {
  const { result, document } = build({
    summary: 'Looks good.\nVERDICT: pass\n',
    splitProposalRequested: false,
  });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(document.outcome, 'pass', 'without the flag, review stage emits a verdict');
  assert.ok(!('subtasks' in document), 'a plain review result carries no subtasks');
});
