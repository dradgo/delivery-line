// Regression — the execution-stage prompt must point the agent at the MATERIALIZED spec location
// under the read-only input mount, NOT a bare referencePath the model resolves against the repo
// checkout. run_08880e2c / FIN-40: the backend now materializes the approved spec into
// input/<referencePath>, but the prompt surfaced only "Approved specification reference:
// artifacts/…/spec.md", so the agent opened that path relative to /workspace/repo, failed, reported
// "the referenced spec file is not present in this checkout", and produced a plan that DIVERGED
// from the approved design (repeated plan rejections + escalation). The prompt must name
// /workspace/input/<referencePath> so the agent actually reads the materialized spec.
//
// Twin of runners/codex/test/runner-spec-reference-path.test.mjs (only provider text differs).
// Run locally: `node --test runners/claude/test/` (zero deps; node:test built-in).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), '..', 'lib', 'runner.mjs');
const SPEC_REF = 'artifacts/run_spec/art_spec/v1/spec.md';

function prepare() {
  const dir = mkdtempSync(join(tmpdir(), 'spec-ref-'));
  const bundlePath = join(dir, 'context-bundle.v1.json');
  const promptPath = join(dir, 'claude.prompt');
  writeFileSync(
    bundlePath,
    JSON.stringify({
      schemaVersion: 1,
      workflowRunId: 'run_spec',
      runnerExecutionId: 'rex_spec',
      classification: 'shareable-redacted',
      ticketSummary: { ticketRef: 'FIN-1', title: 'T', summary: 'S' },
      approvedSpecificationReference: { referencePath: SPEC_REF, referenceAvailable: true },
    }),
    'utf8',
  );
  const result = spawnSync(
    process.execPath,
    [RUNNER, 'prepare', '--bundle', bundlePath, '--prompt-out', promptPath],
    { encoding: 'utf8' },
  );
  const prompt = readFileSync(promptPath, 'utf8');
  return { result, prompt };
}

test('prepare: the approved-spec prompt line names the materialized /workspace/input path', () => {
  const { result, prompt } = prepare();
  assert.equal(result.status, 0, result.stderr);
  assert.match(
    prompt,
    new RegExp(`/workspace/input/${SPEC_REF.replace(/[.]/g, '\\.')}`),
    'prompt must point the agent at the materialized spec under /workspace/input, not a bare repo-relative referencePath',
  );
});
