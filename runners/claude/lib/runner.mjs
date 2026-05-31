#!/usr/bin/env node
// Story 3.4 — Claude runner contract helper (runner-contracts v1). The Claude
// twin of story 3.3's Codex helper: the JSON read/build SCHEMA SHAPE is
// byte-for-byte identical (this IS the shared contract — runners/RUNNER_CONTRACT.md);
// only content-not-contract fields differ (the prOutput branch prefix, the
// default summary text, and these header comments).
//
// The slim base image ships `node` but NOT `jq`, so the POSIX `entrypoint.sh`
// delegates all JSON read/build work to this dependency-free ESM module.
//
// Subcommands:
//   prepare --bundle <path> [--prompt-out <file>]
//       Parse the input context bundle. Emits shell-safe KEY=VALUE assignments
//       (DL_SCHEMA_VERSION / DL_WORKFLOW_RUN_ID / DL_RUNNER_EXECUTION_ID /
//       DL_CLASSIFICATION / DL_BUNDLE_STAGE) on stdout for the entrypoint to
//       `eval`, and optionally writes the Claude prompt to --prompt-out.
//       Exit 11 if the bundle is missing / not JSON. Does NOT enforce
//       schemaVersion (the shell owns that decision + exit code 12) so the
//       diagnostic value is still surfaced.
//
//   build --bundle <path> --stage <artifactType> --summary-file <f> --out <path>
//       Build a schema-conformant runner-result.v1 document for the resolved
//       artifactType (spec | implementationPlan | prOutput) and write it
//       atomically to --out. Exit 13 on unknown stage, 40 on write failure.
//
//   build-invalid --out <path>
//       Write a deliberately schema-INVALID result file (drives the
//       --simulate-failure=contract_violation path / story 3.6 / 3.8 tests).
//
// NEVER prints secret values: the prompt is built only from the bundle's
// non-secret ticket/spec/feedback/constraint fields; secrets live in env and
// are consumed by the Claude CLI directly, never by this helper.

import { mkdirSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';

function fail(code, message) {
  process.stderr.write(`runner.mjs: ${message}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token.startsWith('--')) {
      const key = token.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith('--')) {
        out[key] = next;
        i++;
      } else {
        out[key] = 'true';
      }
    }
  }
  return out;
}

function readBundle(path) {
  if (!path) {
    fail(2, 'missing --bundle');
  }
  let raw;
  try {
    raw = readFileSync(path, 'utf8');
  } catch {
    fail(11, `cannot read bundle at ${path}`);
  }
  let doc;
  try {
    doc = JSON.parse(raw);
  } catch {
    fail(11, 'bundle is not valid JSON');
  }
  if (typeof doc !== 'object' || doc === null || Array.isArray(doc)) {
    fail(11, 'bundle is not a JSON object');
  }
  return doc;
}

// Single-quote a value for safe `eval` in POSIX sh.
function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

// Forward-compat: the frozen context-bundle.v1 schema is additionalProperties:false
// so a `stage` field can never appear today. If a future schema revision adds one
// we accept it, but only as a constrained token (defence-in-depth before `eval`).
function sanitizeStageToken(value) {
  if (typeof value !== 'string') return '';
  return /^[A-Za-z0-9_-]{1,64}$/.test(value) ? value : '';
}

function deriveArtifactId(runnerExecutionId, stage) {
  const digest = createHash('sha256')
    .update(`${runnerExecutionId}:${stage}`)
    .digest('hex')
    .slice(0, 24);
  return `art_${digest}`;
}

function writeAtomically(path, contents) {
  const tmp = `${path}.tmp`;
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(tmp, contents, 'utf8');
    renameSync(tmp, path);
  } catch (error) {
    fail(40, `failed to write ${path}: ${error.message}`);
  }
}

function commandPrepare(args) {
  const doc = readBundle(args.bundle);
  const promptOut = args['prompt-out'];
  if (promptOut) {
    const promptTemplate = args.template ? readTemplate(args.template) : '';
    const ticket = (doc.ticketSummary && typeof doc.ticketSummary === 'object') ? doc.ticketSummary : {};
    const lines = [];
    lines.push(`# Ticket: ${ticket.ticketRef ?? ''} — ${ticket.title ?? ''}`.trim());
    lines.push('');
    lines.push(String(ticket.summary ?? ''));
    const specRef = doc.approvedSpecificationReference;
    if (specRef && typeof specRef === 'object' && specRef.referencePath) {
      lines.push('');
      lines.push(`Approved specification reference: ${specRef.referencePath}`);
    }
    const feedback = Array.isArray(doc.priorFeedbackReferences) ? doc.priorFeedbackReferences : [];
    if (feedback.length > 0) {
      lines.push('');
      lines.push(`Prior feedback references: ${feedback.map((f) => f.referenceId).join(', ')}`);
    }
    const constraints = (doc.executionConstraints && typeof doc.executionConstraints === 'object')
      ? doc.executionConstraints
      : {};
    lines.push('');
    lines.push(
      `Execution constraints: timeoutSeconds=${constraints.timeoutSeconds ?? ''} allowRawOutput=${constraints.allowRawOutput ?? ''}`,
    );
    const bundlePrompt = `${lines.join('\n')}\n`;
    const prompt =
      promptTemplate.length > 0
        ? applyTemplate(promptTemplate, bundlePrompt)
        : bundlePrompt;
    writeAtomically(promptOut, prompt);
  }
  process.stdout.write(
    [
      `DL_SCHEMA_VERSION=${shellQuote(doc.schemaVersion ?? '')}`,
      `DL_WORKFLOW_RUN_ID=${shellQuote(doc.workflowRunId ?? '')}`,
      `DL_RUNNER_EXECUTION_ID=${shellQuote(doc.runnerExecutionId ?? '')}`,
      `DL_CLASSIFICATION=${shellQuote(doc.classification ?? '')}`,
      `DL_BUNDLE_STAGE=${shellQuote(sanitizeStageToken(doc.stage))}`,
      '',
    ].join('\n'),
  );
  process.exit(0);
}

function readTemplate(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch (error) {
    fail(2, `cannot read prompt template at ${path}: ${error.message}`);
  }
}

function applyTemplate(template, bundlePrompt) {
  return template.includes('{{prompt}}')
    ? template.replaceAll('{{prompt}}', bundlePrompt)
    : `${template}\n${bundlePrompt}`;
}

function commandBuild(args) {
  const doc = readBundle(args.bundle);
  const stage = args.stage;
  const out = args.out;
  if (!out) fail(2, 'missing --out');
  const summaryFile = args['summary-file'];

  let rawOutput = '';
  if (summaryFile) {
    try {
      rawOutput = readFileSync(summaryFile, 'utf8');
    } catch {
      rawOutput = '';
    }
  }
  const nonEmptyLines = rawOutput.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0);
  const summary = (nonEmptyLines[0] ?? `Claude ${stage} runner produced output`).slice(0, 2000);

  const workflowRunId = doc.workflowRunId;
  const runnerExecutionId = doc.runnerExecutionId;
  const classification = doc.classification;
  const artifactId = deriveArtifactId(runnerExecutionId, stage);
  // SHA-256 over the raw Claude output bytes — 64 lowercase hex chars (schema checksum).
  const hexDigest = createHash('sha256').update(rawOutput, 'utf8').digest('hex');

  let artifact;
  if (stage === 'spec') {
    const contentReference = `artifacts/${workflowRunId}/spec.md`;
    const artifactPath = join(dirname(out), contentReference);
    writeAtomically(artifactPath, rawOutput || summary);
    artifact = {
      artifactId,
      artifactType: 'spec',
      contentReference,
    };
  } else if (stage === 'implementationPlan') {
    const steps = nonEmptyLines.slice(0, 50);
    artifact = {
      artifactId,
      artifactType: 'implementationPlan',
      steps: steps.length > 0 ? steps : [summary],
      contextReferences: [],
    };
  } else if (stage === 'prOutput') {
    artifact = {
      artifactId,
      artifactType: 'prOutput',
      branch: `claude/${runnerExecutionId}`,
      commitSha: hexDigest.slice(0, 40),
      prReference: `artifacts/${workflowRunId}/pr.json`,
      diffReference: `artifacts/${workflowRunId}/pr.diff`,
    };
  } else {
    fail(13, `unknown stage: ${stage}`);
  }

  const result = {
    schemaVersion: 1,
    workflowRunId,
    runnerExecutionId,
    artifactReferences: [artifact],
    normalizedOutput: { summary, outcome: 'success' },
    checksum: { algorithm: 'SHA-256', hexDigest },
    classification,
    failureCategory: null,
  };

  writeAtomically(out, `${JSON.stringify(result, null, 2)}\n`);
  process.stdout.write(`${summary}\n`);
  process.exit(0);
}

function commandBuildFailure(args) {
  const doc = readBundle(args.bundle);
  const stage = args.stage;
  const out = args.out;
  const category = args.category || 'runner_non_zero_exit';
  const summary = args.summary || 'Claude runner failed';
  if (!out) fail(2, 'missing --out');

  const workflowRunId = doc.workflowRunId;
  const runnerExecutionId = doc.runnerExecutionId;
  const artifactId = deriveArtifactId(runnerExecutionId, `${stage}:failure`);
  const hexDigest = createHash('sha256').update(summary, 'utf8').digest('hex');

  let artifact;
  if (stage === 'spec') {
    artifact = {
      artifactId,
      artifactType: 'spec',
      contentReference: `artifacts/${workflowRunId}/failure.md`,
    };
  } else if (stage === 'implementationPlan') {
    artifact = {
      artifactId,
      artifactType: 'implementationPlan',
      steps: [summary],
      contextReferences: [],
    };
  } else if (stage === 'prOutput') {
    artifact = {
      artifactId,
      artifactType: 'prOutput',
      branch: `claude/${runnerExecutionId}`,
      commitSha: hexDigest.slice(0, 40),
      prReference: `artifacts/${workflowRunId}/failure-pr.json`,
      diffReference: `artifacts/${workflowRunId}/failure.diff`,
    };
  } else {
    fail(13, `unknown stage: ${stage}`);
  }

  const result = {
    schemaVersion: 1,
    workflowRunId,
    runnerExecutionId,
    artifactReferences: [artifact],
    normalizedOutput: { summary, outcome: 'failure' },
    checksum: { algorithm: 'SHA-256', hexDigest },
    classification: doc.classification,
    failureCategory: category,
  };

  writeAtomically(out, `${JSON.stringify(result, null, 2)}\n`);
  process.exit(0);
}

function commandBuildInvalid(args) {
  const out = args.out;
  if (!out) fail(2, 'missing --out');
  // Deliberately violates the schema (schemaVersion must be the const 1, and the
  // required fields are absent) so the backend's RunnerContractValidator classifies
  // this as runner_contract_violation.
  const invalid = {
    schemaVersion: 2,
    note: 'intentionally schema-invalid result for contract-violation simulation',
  };
  writeAtomically(out, `${JSON.stringify(invalid)}\n`);
  process.exit(0);
}

const [command, ...rest] = process.argv.slice(2);
const parsed = parseArgs(rest);

switch (command) {
  case 'prepare':
    commandPrepare(parsed);
    break;
  case 'build':
    commandBuild(parsed);
    break;
  case 'build-invalid':
    commandBuildInvalid(parsed);
    break;
  case 'build-failure':
    commandBuildFailure(parsed);
    break;
  default:
    fail(2, `unknown command: ${command ?? '(none)'}`);
}
