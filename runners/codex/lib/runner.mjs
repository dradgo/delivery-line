#!/usr/bin/env node
// Story 3.3 — Codex runner contract helper (runner-contracts v1).
//
// The slim base image ships `node` but NOT `jq`, so the POSIX `entrypoint.sh`
// delegates all JSON read/build work to this dependency-free ESM module.
//
// Subcommands:
//   prepare --bundle <path> [--prompt-out <file>]
//       Parse the input context bundle. Emits shell-safe KEY=VALUE assignments
//       (DL_SCHEMA_VERSION / DL_WORKFLOW_RUN_ID / DL_RUNNER_EXECUTION_ID /
//       DL_CLASSIFICATION / DL_BUNDLE_STAGE) on stdout for the entrypoint to
//       `eval`, and optionally writes the Codex prompt to --prompt-out.
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
//   materialize-auth --out <path>   (story 3a-3 — Codex subscription auth)
//       Read the subscription credential from process.env.CODEX_AUTH_JSON (NEVER
//       from argv — it must never appear on the process command line), validate it
//       parses to a NON-EMPTY JSON object, and write it atomically with file mode
//       0600 to --out (typically $CODEX_HOME/auth.json). Exit 21 (auth family) on
//       absent/blank/non-JSON/non-object/empty input; exit 2 on missing --out.
//       NEVER prints the value — only a name+presence diagnostic.
//
// NEVER prints secret values: the prompt is built only from the bundle's
// non-secret ticket/spec/feedback/constraint fields; secrets live in env and
// are consumed by the Codex CLI directly, never by this helper. The sole secret
// this helper itself handles is CODEX_AUTH_JSON (materialize-auth), read from env
// and held to the same no-print bar.

import { mkdirSync, readFileSync, writeFileSync, renameSync, chmodSync, rmSync } from 'node:fs';
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

// Atomic write via tmp + rename. When `mode` is supplied (e.g. 0o600 for the
// subscription auth.json secret) the TEMP file is created AND chmod'd to it before
// the rename, so the destination is never world-readable for any window — a post-hoc
// chmod on the live path would leave a brief default-umask (~0644) gap, and a chmod
// failure would silently leave the secret readable. writeFileSync's mode only applies
// on creation, so chmod the tmp too in case a stale tmp pre-existed.
function writeAtomically(path, contents, mode) {
  const tmp = `${path}.tmp`;
  try {
    mkdirSync(dirname(path), { recursive: true });
    if (mode !== undefined) {
      writeFileSync(tmp, contents, { encoding: 'utf8', mode });
      try {
        chmodSync(tmp, mode);
      } catch {
        // best-effort on a non-POSIX dev fs (Windows); the production runner is Linux.
      }
    } else {
      writeFileSync(tmp, contents, 'utf8');
    }
    renameSync(tmp, path);
  } catch (error) {
    // Never leave a partial temp file behind — for a secret write it could hold the value.
    try {
      rmSync(tmp, { force: true });
    } catch {
      // ignore cleanup failure
    }
    fail(40, `failed to write ${path}: ${error.message}`);
  }
}

function commandPrepare(args) {
  const doc = readBundle(args.bundle);
  const promptOut = args['prompt-out'];
  if (promptOut) {
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
    writeAtomically(promptOut, `${lines.join('\n')}\n`);
  }
  // Story 3a-8 — additionally surface the ticketRef (for the deterministic OpenSpec
  // change-id) and the two carry-forward referencePaths (read by the pr-output OpenSpec
  // assembly). These are emitted UNCONDITIONALLY but only consumed when the entrypoint's
  // DELIVERYLINE_RUNNER_OPENSPEC flag is on — they are internal eval'd shell vars (never
  // logged / never an artifact), so the flag-off path stays byte-identical.
  const ticketForId =
    doc.ticketSummary && typeof doc.ticketSummary === 'object' ? doc.ticketSummary : {};
  const specRefOut = doc.approvedSpecificationReference;
  const planRefOut = doc.approvedImplementationPlanReference;
  process.stdout.write(
    [
      `DL_SCHEMA_VERSION=${shellQuote(doc.schemaVersion ?? '')}`,
      `DL_WORKFLOW_RUN_ID=${shellQuote(doc.workflowRunId ?? '')}`,
      `DL_RUNNER_EXECUTION_ID=${shellQuote(doc.runnerExecutionId ?? '')}`,
      `DL_CLASSIFICATION=${shellQuote(doc.classification ?? '')}`,
      `DL_BUNDLE_STAGE=${shellQuote(sanitizeStageToken(doc.stage))}`,
      `DL_TICKET_REF=${shellQuote(ticketForId.ticketRef ?? '')}`,
      `DL_SPEC_REF_PATH=${shellQuote(referencePathOf(specRefOut))}`,
      `DL_PLAN_REF_PATH=${shellQuote(referencePathOf(planRefOut))}`,
      '',
    ].join('\n'),
  );
  process.exit(0);
}

// Story 3a-8 — pull the non-secret referencePath out of an artifactReference-shaped
// object (or '' when absent / not carried). The referenced payload is read from the
// mounted input dir at pr-output; the bundle never embeds it inline.
function referencePathOf(ref) {
  return ref && typeof ref === 'object' && typeof ref.referencePath === 'string'
    ? ref.referencePath
    : '';
}

// Story 3d-2 — advisory review outcome parser (lock-step with runners/claude/lib/runner.mjs). The
// review prompt asks the model to end with a `VERDICT: pass|concern|fail` marker; absent a marker
// the verdict defaults to `concern` (advisory + conservative — never silently `pass`).
function parseReviewOutcome(text) {
  const source = typeof text === 'string' ? text : '';
  // Take the LAST line-anchored VERDICT marker so a quoted/echoed instruction earlier in the
  // output cannot win over the model's actual closing verdict. Default to 'concern' when absent.
  const matches = source.match(/^[ \t>*-]*VERDICT:\s*(pass|concern|fail)/gim);
  if (!matches || matches.length === 0) return 'concern';
  const verdict = /VERDICT:\s*(pass|concern|fail)/i.exec(matches[matches.length - 1]);
  return verdict ? verdict[1].toLowerCase() : 'concern';
}

// Story 3d-2 (code-review 2026-06-22, lock-step with runners/claude/lib/runner.mjs) — cap the
// rationale length WITHOUT slicing through the middle of a token. The backend redacts the rationale
// before persistence, but it only ever sees the truncated text the runner emits; a hard mid-token
// cut could split a secret so the backend redaction regex no longer matches the partial prefix that
// remains. When the cut lands mid-token, retreat to the last whitespace boundary inside the budget
// (dropping the trailing partial token), keeping at least half the budget so a whitespace-free blob
// still truncates.
function truncateRationale(text, maxChars) {
  if (text.length <= maxChars) return text;
  const cut = text.slice(0, maxChars);
  if (/\S/.test(text.charAt(maxChars))) {
    const lastWhitespace = cut.search(/\s\S*$/);
    if (lastWhitespace > maxChars / 2) {
      return cut.slice(0, lastWhitespace);
    }
  }
  return cut;
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
  const summaryBase = (nonEmptyLines[0] ?? `Codex ${stage} runner produced output`).slice(0, 2000);
  // Story 3a-8 (AC5/D4): surface the OpenSpec validate outcome in the result summary. Passed
  // only on flag-on pr-output by the entrypoint; absent (flag-off) => summary byte-identical.
  const openspecNote = args['openspec-note'];
  const summary = openspecNote
    ? `${summaryBase} [openspec: ${String(openspecNote).slice(0, 200)}]`
    : summaryBase;

  const workflowRunId = doc.workflowRunId;
  const runnerExecutionId = doc.runnerExecutionId;
  const classification = doc.classification;
  const artifactId = deriveArtifactId(runnerExecutionId, stage);
  // SHA-256 over the raw Codex output bytes — 64 lowercase hex chars (schema checksum).
  const hexDigest = createHash('sha256').update(rawOutput, 'utf8').digest('hex');

  // Story 3d-2 — advisory REVIEW stage emits a review-result.v1 verdict (NOT a runner-result.v1
  // artifact). Lock-step with runners/claude/lib/runner.mjs. Parse the verdict from the model's
  // output (a `VERDICT: ...` marker when present; default `concern` when absent — advisory +
  // conservative). Backend redacts the rationale + computes provenance.
  if (stage === 'review') {
    const reviewResult = {
      schemaVersion: 1,
      workflowRunId,
      runnerExecutionId,
      outcome: parseReviewOutcome(rawOutput),
      rationale: rawOutput.trim().length > 0 ? truncateRationale(rawOutput.trim(), 8000) : summary,
      summary,
      reviewerModelIdentity: 'codex',
      classification,
      failureCategory: null,
    };
    writeAtomically(out, `${JSON.stringify(reviewResult, null, 2)}\n`);
    process.stdout.write(`${summary}\n`);
    process.exit(0);
  }

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
      branch: `codex/${runnerExecutionId}`,
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
  const summary = args.summary || 'Codex runner failed';
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
      branch: `codex/${runnerExecutionId}`,
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

// Story 3a-3 — exit code for a present-but-unusable CODEX_AUTH_JSON (malformed /
// empty / not a non-empty object). The credential IS present, so this is distinct
// from the entrypoint's exit 20 "no credential present"; the entrypoint mirrors
// this code so the failure-result summary is unambiguous ("Codex auth.json is
// malformed"). See entrypoint.sh, README "Exit codes", and runners/RUNNER_CONTRACT.md.
const MATERIALIZE_AUTH_EXIT = 21;

// Story 3a-3 — materialize the subscription credential from CODEX_AUTH_JSON into an
// auth.json file (0600, atomic). The Codex CLI reads subscription credentials from a
// FILE ($CODEX_HOME/auth.json), not an env var, so the entrypoint calls this helper
// to bridge the env-var secret pipeline (RunnerSecretsService) to the file the CLI
// expects. The raw value travels in env (NOT argv) and is NEVER printed — the only
// diagnostic is a name+presence line; the entrypoint owns the human-readable log.
function commandMaterializeAuth(args) {
  const out = args.out;
  if (!out || out === 'true') {
    fail(2, 'materialize-auth: missing --out <path>');
  }
  const raw = process.env.CODEX_AUTH_JSON;
  if (raw === undefined || raw === null || raw.trim() === '') {
    fail(MATERIALIZE_AUTH_EXIT, 'materialize-auth: CODEX_AUTH_JSON is absent or blank (value never logged)');
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    // Do NOT include the raw value or the parse error detail (which can echo the value).
    fail(MATERIALIZE_AUTH_EXIT, 'materialize-auth: CODEX_AUTH_JSON is not valid JSON (value never logged)');
  }
  if (
    typeof parsed !== 'object' ||
    parsed === null ||
    Array.isArray(parsed) ||
    Object.keys(parsed).length === 0
  ) {
    fail(
      MATERIALIZE_AUTH_EXIT,
      'materialize-auth: CODEX_AUTH_JSON must be a non-empty JSON object (value never logged)',
    );
  }
  // Write the raw credential verbatim (single-line JSON as supplied) — never re-serialized,
  // so an operator's exact auth.json bytes reach the CLI. Pass mode 0600 so writeAtomically
  // creates the temp file restricted and renames it into place atomically — the secret file
  // is never world-readable for any window (no post-hoc chmod gap).
  writeAtomically(out, raw, 0o600);
  // Name + presence only — never the value.
  process.stderr.write('runner.mjs: materialize-auth wrote auth.json from name=CODEX_AUTH_JSON\n');
  process.exit(0);
}

// ===== Story 3a-8 — OpenSpec fence split (BYTE-IDENTICAL in both runner.mjs files) =====
// The read-only stages emit OpenSpec change files to STDOUT (their normal artifact
// channel) using a documented fence convention:
//   === FILE: <relpath> ===
//   <file content...>
// At pr-output the entrypoint reconstructs the OpenSpec change folder from the two
// carried artifacts by running this helper once per artifact. It is deliberately
// dependency-free + atomic (reuses writeAtomically). Path traversal (absolute paths,
// drive letters, `..` segments) is rejected so a malformed/hostile fence can never
// escape the change dir. Empty / fence-less input exits non-zero with a clear message;
// the entrypoint treats that as a best-effort WARN and STILL ships the code (Trap
// T-ADDITIVE-NEVER-BLOCKS — an OpenSpec problem never fails code delivery).
function isSafeRelPath(rel) {
  if (typeof rel !== 'string' || rel.trim() === '') return false;
  if (rel.startsWith('/') || rel.startsWith('\\')) return false; // absolute (posix / win)
  if (/^[A-Za-z]:[\\/]/.test(rel)) return false; // windows drive (C:\ , C:/)
  const parts = rel.split(/[\\/]/);
  return !parts.some((p) => p === '..' || p === '.' || p === '');
}

function commandSplitFenced(args) {
  const inPath = args.in;
  const changeDir = args['change-dir'];
  if (!inPath || inPath === 'true') fail(2, 'split-fenced: missing --in <fenced-file>');
  if (!changeDir || changeDir === 'true') fail(2, 'split-fenced: missing --change-dir <dir>');

  let raw;
  try {
    raw = readFileSync(inPath, 'utf8');
  } catch {
    fail(41, `split-fenced: cannot read ${inPath}`);
  }

  const FENCE = /^=== FILE: (.+?) ===\s*$/;
  const sections = [];
  let current = null;
  for (const line of raw.split(/\r?\n/)) {
    const match = line.match(FENCE);
    if (match) {
      current = { relpath: match[1].trim(), body: [] };
      sections.push(current);
    } else if (current) {
      current.body.push(line);
    }
  }
  if (sections.length === 0) {
    fail(41, 'split-fenced: no "=== FILE: <relpath> ===" fence found (malformed/empty input)');
  }

  let written = 0;
  for (const section of sections) {
    if (!isSafeRelPath(section.relpath)) {
      fail(42, `split-fenced: unsafe relpath rejected: ${section.relpath}`);
    }
    // Normalize the trailing fence-boundary blank line, then end with exactly one newline.
    const body = section.body.join('\n').replace(/\n+$/, '');
    try {
      writeAtomically(join(changeDir, section.relpath), body.length > 0 ? `${body}\n` : '');
    } catch {
      fail(41, `split-fenced: cannot write ${section.relpath}`);
    }
    written++;
  }
  process.stdout.write(`split-fenced: wrote ${written} file(s) to ${changeDir}\n`);
  process.exit(0);
}
// ===== end OpenSpec fence split =====

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
  case 'materialize-auth':
    commandMaterializeAuth(parsed);
    break;
  case 'split-fenced':
    commandSplitFenced(parsed);
    break;
  default:
    fail(2, `unknown command: ${command ?? '(none)'}`);
}
