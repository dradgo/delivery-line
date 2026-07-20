// Regression — `runner.mjs build --stage implementationPlan` must NOT truncate the plan.
//
// A prior `nonEmptyLines.slice(0, 50)` cap silently dropped every plan line past the 50th.
// Real plans are far longer than 50 non-empty lines, so the persisted implementationPlan
// artifact ended mid-file (mid-code-block, mid-task). The advisory plan reviewer then read a
// physically truncated plan and (correctly) rejected it as "the plan is incomplete", driving a
// reject -> re-execute -> re-truncate -> reject loop that escalated the run. This test pins the
// full plan surviving so the cap can never silently return.
//
// Twin of runners/claude/test/runner-plan-steps.test.mjs (only provider text differs).
// Run locally: `node --test runners/codex/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');

function build({ stage = 'implementationPlan', summary } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'plan-steps-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const summaryPath = join(dir, 'summary.txt');
  const outPath = join(dir, 'runner-result.v1.json');
  writeFileSync(
    bundlePath,
    JSON.stringify({
      schemaVersion: 1,
      workflowRunId: 'run_plan',
      runnerExecutionId: 'rex_plan',
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

// 120 distinct non-empty lines interleaved with blank lines (blanks are dropped as before).
const LINE_COUNT = 120;
const planLines = Array.from({ length: LINE_COUNT }, (_, i) => `plan-line-${i + 1}`);
const PLAN_BODY = planLines.join('\n\n') + '\n';

test('implementationPlan stage: a plan with >50 non-empty lines is preserved in full (no 50-line cap)', () => {
  const { result, artifact } = build({ stage: 'implementationPlan', summary: PLAN_BODY });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(artifact.artifactType, 'implementationPlan');
  assert.equal(
    artifact.steps.length,
    LINE_COUNT,
    `every non-empty plan line must survive (got ${artifact.steps.length} of ${LINE_COUNT})`,
  );
  assert.equal(artifact.steps[0], 'plan-line-1', 'first line preserved');
  assert.equal(
    artifact.steps[LINE_COUNT - 1],
    `plan-line-${LINE_COUNT}`,
    'the LAST line survives — the plan is not cut off mid-content',
  );
});
