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
    // Story 3e-2 (AC3) — accepted clarifications arrive INLINE in the spec-rebuild bundle (the
    // runner reads only this bundle). Surface each question+answer into the prompt so the
    // regenerated spec incorporates the reviewer's answers. Absent on every non-rebuild bundle.
    const acceptedClarifications = Array.isArray(doc.acceptedClarifications)
      ? doc.acceptedClarifications
      : [];
    if (acceptedClarifications.length > 0) {
      lines.push('');
      lines.push('Accepted clarifications to incorporate into the regenerated specification:');
      for (const clarification of acceptedClarifications) {
        if (clarification && typeof clarification === 'object') {
          lines.push(
            `- [${clarification.questionId ?? ''}] Q: ${clarification.questionText ?? ''} A: ${clarification.answerText ?? ''}`,
          );
        }
      }
    }
    // Story 3e-3 (AC2) — open clarifications arrive INLINE in the spec-phase REVIEW bundle (the
    // runner reads only this bundle). Surface each open question into the prompt so the advisory
    // reviewer can weigh whether the spec leaves them unresolved. Open rows carry no answer yet.
    // Absent on every non-spec-review bundle (byte-identical there).
    const openClarifications = Array.isArray(doc.openClarifications) ? doc.openClarifications : [];
    if (openClarifications.length > 0) {
      lines.push('');
      lines.push('Open clarification questions the specification should adequately address:');
      for (const clarification of openClarifications) {
        if (clarification && typeof clarification === 'object') {
          lines.push(`- [${clarification.questionId ?? ''}] Q: ${clarification.questionText ?? ''}`);
        }
      }
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
  // Story 3a-8 — additionally surface the ticketRef (for the deterministic OpenSpec
  // change-id) and the two carry-forward referencePaths (read by the pr-output OpenSpec
  // assembly). These are emitted UNCONDITIONALLY but only consumed when the entrypoint's
  // DELIVERYLINE_RUNNER_OPENSPEC flag is on — they are internal eval'd shell vars (never
  // logged / never an artifact), so the flag-off path stays byte-identical.
  const ticketForId =
    doc.ticketSummary && typeof doc.ticketSummary === 'object' ? doc.ticketSummary : {};
  const specRefOut = doc.approvedSpecificationReference;
  const planRefOut = doc.approvedImplementationPlanReference;
  // Spec-phase advisory review (story 3e-3 follow-up) — the reviewed artifact is the SOLE
  // artifactReferences entry in a REVIEW bundle. Surface its type so the entrypoint's review arm can
  // pick a SPEC-appropriate prompt (review the specification itself) instead of the
  // implementation-review prompt that hunts for a non-existent patch/repo at the spec gate.
  const reviewedRefOut =
    Array.isArray(doc.artifactReferences) &&
    doc.artifactReferences[0] &&
    typeof doc.artifactReferences[0] === 'object'
      ? doc.artifactReferences[0].artifactType ?? ''
      : '';
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
      // Story 3f-4 — surface the additive split-proposal marker so the entrypoint can append the
      // decomposition directive (instead of the verdict directive) at a split-mode REVIEW dispatch
      // and the offline mock can emit the deterministic ```split fence. 'true' only when the bundle
      // sets splitProposalRequested=true; '' otherwise (byte-identical legacy path).
      `DL_SPLIT_PROPOSAL_REQUESTED=${shellQuote(doc.splitProposalRequested === true ? 'true' : '')}`,
      // Story 3e-3 follow-up — reviewed artifact type ('spec' at the spec gate, 'prOutput' /
      // 'implementationPlan' at the execution gate; '' on non-review bundles). Selects the review
      // prompt so a spec review reviews the SPEC, not a non-existent implementation.
      `DL_REVIEWED_ARTIFACT_TYPE=${shellQuote(reviewedRefOut)}`,
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

// Story 3d-2 — derive the advisory review outcome from the reviewer model's output. The review
// prompt asks the model to end with a `VERDICT: pass|concern|fail` marker; when present that is
// authoritative. Absent a marker the verdict defaults to `concern` (advisory + conservative — a
// human still decides; we never silently `pass` an unparseable review).
function parseReviewOutcome(text) {
  const source = typeof text === 'string' ? text : '';
  // Take the LAST line-anchored VERDICT marker so a quoted/echoed instruction earlier in the
  // output cannot win over the model's actual closing verdict. Default to 'concern' when absent.
  const matches = source.match(/^[ \t>*-]*VERDICT:\s*(pass|concern|fail)/gim);
  if (!matches || matches.length === 0) return 'concern';
  const verdict = /VERDICT:\s*(pass|concern|fail)/i.exec(matches[matches.length - 1]);
  return verdict ? verdict[1].toLowerCase() : 'concern';
}

// Story 3d-2 (code-review 2026-06-22, lock-step with runners/codex/lib/runner.mjs) — cap the
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

// Story 3d-7 (FR69) — map the NON-SECRET name of the env var the entrypoint resolved the
// provider auth from to a non-secret account label. NEVER the token value (Trap T1) — the
// label is which credential MODE was used, attributable but secret-free. Claude has two modes.
const CLAUDE_AUTH_LABELS = {
  CLAUDE_CODE_OAUTH_TOKEN: 'claude:oauth',
  ANTHROPIC_API_KEY: 'claude:api',
};

function resolveAccountLabel(authVar) {
  return CLAUDE_AUTH_LABELS[authVar] ?? 'claude:unknown';
}

// Defensively rebuild a usage window from arbitrary input, copying ONLY the four allowed
// numeric/timestamp keys — so even a hostile/garbage mock file can never smuggle a secret-shaped
// string into the emitted/persisted payload (AC4). Returns undefined when nothing usable remains.
function sanitizeUsageWindow(raw) {
  if (!raw || typeof raw !== 'object') return undefined;
  const out = {};
  if (typeof raw.usedFraction === 'number' && raw.usedFraction >= 0 && raw.usedFraction <= 1) {
    out.usedFraction = raw.usedFraction;
  }
  if (Number.isInteger(raw.used) && raw.used >= 0) out.used = raw.used;
  if (Number.isInteger(raw.limit) && raw.limit >= 0) out.limit = raw.limit;
  if (typeof raw.resetsAt === 'string' && raw.resetsAt.length > 0) out.resetsAt = raw.resetsAt;
  return Object.keys(out).length > 0 ? out : undefined;
}

// Story 3d-7 SPIKE outcome: the 5-hour/weekly SUBSCRIPTION window status is NOT exposed in the
// headless invocation the runner uses (see the story Completion Notes), so the real path emits an
// explicit `not_exposed` marker — never a fabricated number. The offline mock injects a
// deterministic `available` payload via DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE so the happy path is
// exercised offline + in CI. This is ADDITIVE optional metadata; capture must NEVER throw, never
// fail the run, and never log/emit the token value.
function buildProviderUsage(authVar) {
  const accountLabel = resolveAccountLabel(authVar);
  const asOf = new Date().toISOString();
  const mockFile = process.env.DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE;
  if (mockFile) {
    try {
      const parsed = JSON.parse(readFileSync(mockFile, 'utf8'));
      if (parsed && typeof parsed === 'object' && parsed.signalState === 'available') {
        const usage = { signalState: 'available', accountLabel, asOf };
        const fiveHour = sanitizeUsageWindow(parsed.fiveHour);
        const weekly = sanitizeUsageWindow(parsed.weekly);
        if (fiveHour) usage.fiveHour = fiveHour;
        if (weekly) usage.weekly = weekly;
        return usage;
      }
    } catch {
      // fall through to not_exposed — capture is best-effort and must never throw.
    }
  }
  return { signalState: 'not_exposed', accountLabel, asOf };
}

// ===== Story 3g-3 (FR74) — per-execution token accounting (BYTE-IDENTICAL in both runner.mjs) =====
// Defensively rebuild a per-execution token-usage block from arbitrary input, copying ONLY the three
// allowed non-negative integer keys — so even a hostile/garbage mock file can never smuggle a
// secret-shaped string into the emitted/persisted `usage` (AC5). Each count is INDEPENDENT: a
// missing/bad sub-field is dropped to absent (never fabricated, never synthesised from the others).
// A count must be a non-negative int32 (0..2147483647) to match the `integer` runner_executions
// columns it lands on — an out-of-range value is dropped here rather than silently coerced to null
// downstream (contract mirrors this with `maximum:2147483647`).
// Returns undefined when nothing usable remains, so `usage` is omitted entirely (AC1 byte-identical).
function sanitizeUsage(raw) {
  if (!raw || typeof raw !== 'object') return undefined;
  const out = {};
  const inRange = (v) => Number.isInteger(v) && v >= 0 && v <= 2147483647;
  if (inRange(raw.inputTokens)) out.inputTokens = raw.inputTokens;
  if (inRange(raw.outputTokens)) out.outputTokens = raw.outputTokens;
  if (inRange(raw.totalTokens)) out.totalTokens = raw.totalTokens;
  return Object.keys(out).length > 0 ? out : undefined;
}

// Story 3g-3 SCOPE (mirror the 3d-7 providerUsage spike posture): the text-mode (`-p`) headless
// invocation does NOT surface token usage on agent stdout (verified — no parsing exists), so real
// token extraction is a documented forward option, out of scope here. The deterministic path reads
// an env-injected mock file (the exact DELIVERYLINE_PROVIDER_USAGE_MOCK_FILE seam) so the happy path
// is exercised offline + in CI. Best-effort: NEVER throws, NEVER fails the run; returns a sanitized
// {inputTokens?,outputTokens?,totalTokens?} or undefined (unset/unreadable/malformed/no-counts) —
// the honest "not reported" state 3g-4 renders. NEVER emits usage:{} or usage:null.
function buildUsage() {
  const mockFile = process.env.DELIVERYLINE_USAGE_MOCK_FILE;
  if (!mockFile) return undefined;
  try {
    const parsed = JSON.parse(readFileSync(mockFile, 'utf8'));
    return sanitizeUsage(parsed);
  } catch {
    // fall through to undefined — capture is best-effort and must never throw.
    return undefined;
  }
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

// ===== Story 3e-1 — clarifications fence split (BYTE-IDENTICAL in both runner.mjs files) =====
// The spec (investigation) stage agent may raise OPEN CLARIFYING QUESTIONS alongside the
// specification it writes to stdout, using a fence convention mirroring the OpenSpec fence (3a-8):
//   ```clarifications
//   [{ "questionId": "Q-001", "questionText": "..." }]
//   ```
// This helper lifts that block out of the agent stdout: it returns the parsed `questions` array
// (each entry coerced to {questionId, questionText}; malformed entries dropped) and the
// `strippedStdout` with the fence removed so the persisted spec.md never carries the raw block.
// A missing/empty/garbage block is NON-FATAL (logged to stderr, treated as no questions) — a
// clarification problem NEVER fails spec delivery (Trap T-ADDITIVE-NEVER-BLOCKS).
//
// The fence is stripped ONLY when it parses to >=1 usable question: a no-fence, malformed, or
// zero-question block returns the original stdout UNCHANGED, so (a) a no-question result stays
// byte-identical to pre-3e and (b) a malformed/empty block is preserved in the spec rather than
// silently vanishing (code-review 2026-06-24). The strip itself slices the ORIGINAL string at the
// fence-line boundaries (never split-then-rejoin) so the surrounding spec bytes — CRLF line endings
// and the trailing newline — survive verbatim (AC2 byte-identical guarantee).
function splitClarificationsFence(stdout) {
  if (typeof stdout !== 'string' || stdout.length === 0) {
    return { questions: [], strippedStdout: typeof stdout === 'string' ? stdout : '' };
  }
  const OPEN = /^```clarifications\s*$/;
  const CLOSE = /^```\s*$/;
  // Scan on \n boundaries (mirroring split(/\r?\n/): a single \r immediately before a \n is part of
  // the terminator, not the line content) while tracking byte offsets, so a successful strip can
  // slice the original string instead of re-joining normalized lines.
  let blockStart = -1; // offset of the opening fence line
  let bodyStart = -1; // offset just past the opening fence line terminator
  let bodyEnd = -1; // offset of the closing fence line
  let blockEnd = -1; // offset just past the closing fence line terminator
  let pos = 0;
  while (pos < stdout.length) {
    const nl = stdout.indexOf('\n', pos);
    const eol = nl === -1 ? stdout.length : nl;
    const nextPos = nl === -1 ? stdout.length : nl + 1;
    const contentEnd = eol > pos && stdout[eol - 1] === '\r' ? eol - 1 : eol;
    const line = stdout.slice(pos, contentEnd);
    if (blockStart === -1) {
      if (OPEN.test(line)) {
        blockStart = pos;
        bodyStart = nextPos;
      }
    } else if (CLOSE.test(line)) {
      bodyEnd = pos;
      blockEnd = nextPos;
      break;
    }
    pos = nextPos;
  }
  if (blockStart === -1 || blockEnd === -1) {
    return { questions: [], strippedStdout: stdout };
  }
  let questions = [];
  try {
    const parsed = JSON.parse(stdout.slice(bodyStart, bodyEnd));
    if (Array.isArray(parsed)) {
      questions = parsed
        .filter(
          (q) =>
            q &&
            typeof q === 'object' &&
            typeof q.questionId === 'string' &&
            typeof q.questionText === 'string',
        )
        .map((q) => ({ questionId: q.questionId, questionText: q.questionText }));
    }
  } catch {
    process.stderr.write(
      'runner.mjs: clarifications fence is not valid JSON — ignoring (no questions)\n',
    );
    questions = [];
  }
  if (questions.length === 0) {
    return { questions: [], strippedStdout: stdout };
  }
  const strippedStdout = stdout.slice(0, blockStart) + stdout.slice(blockEnd);
  return { questions, strippedStdout };
}
// ===== end clarifications fence split =====

// ===== Story 3e-2 — clarificationAcknowledgements fence split (SHA-EQUAL in both runner.mjs files) =====
// Sibling of splitClarificationsFence: the spec-REBUILD stage agent reports which ACCEPTED
// clarifications it actually addressed, using a fence convention mirroring the clarifications fence:
//   ```clarificationAcknowledgements
//   [{ "questionId": "Q-001", "addressed": true }]
//   ```
// This helper lifts that block out of the agent stdout: it returns the parsed `acknowledgements`
// array (each entry coerced to {questionId, addressed}; malformed entries dropped) and the
// `strippedStdout` with the fence removed so the persisted spec.md never carries the raw block.
// Same NEVER-BLOCKS + byte-identical-strip discipline as splitClarificationsFence: a missing/
// malformed/empty/zero-item block is NON-FATAL and returns the original stdout UNCHANGED; a
// successful strip slices the ORIGINAL string at the fence-line boundaries (preserving CRLF and the
// trailing newline verbatim).
function splitClarificationAcknowledgementsFence(stdout) {
  if (typeof stdout !== 'string' || stdout.length === 0) {
    return { acknowledgements: [], strippedStdout: typeof stdout === 'string' ? stdout : '' };
  }
  const OPEN = /^```clarificationAcknowledgements\s*$/;
  const CLOSE = /^```\s*$/;
  let blockStart = -1;
  let bodyStart = -1;
  let bodyEnd = -1;
  let blockEnd = -1;
  let pos = 0;
  while (pos < stdout.length) {
    const nl = stdout.indexOf('\n', pos);
    const eol = nl === -1 ? stdout.length : nl;
    const nextPos = nl === -1 ? stdout.length : nl + 1;
    const contentEnd = eol > pos && stdout[eol - 1] === '\r' ? eol - 1 : eol;
    const line = stdout.slice(pos, contentEnd);
    if (blockStart === -1) {
      if (OPEN.test(line)) {
        blockStart = pos;
        bodyStart = nextPos;
      }
    } else if (CLOSE.test(line)) {
      bodyEnd = pos;
      blockEnd = nextPos;
      break;
    }
    pos = nextPos;
  }
  if (blockStart === -1 || blockEnd === -1) {
    return { acknowledgements: [], strippedStdout: stdout };
  }
  let acknowledgements = [];
  try {
    const parsed = JSON.parse(stdout.slice(bodyStart, bodyEnd));
    if (Array.isArray(parsed)) {
      acknowledgements = parsed
        .filter(
          (a) =>
            a &&
            typeof a === 'object' &&
            typeof a.questionId === 'string' &&
            typeof a.addressed === 'boolean',
        )
        .map((a) => ({ questionId: a.questionId, addressed: a.addressed }));
    }
  } catch {
    process.stderr.write(
      'runner.mjs: clarificationAcknowledgements fence is not valid JSON — ignoring (no acknowledgements)\n',
    );
    acknowledgements = [];
  }
  if (acknowledgements.length === 0) {
    return { acknowledgements: [], strippedStdout: stdout };
  }
  const strippedStdout = stdout.slice(0, blockStart) + stdout.slice(blockEnd);
  return { acknowledgements, strippedStdout };
}
// ===== end clarificationAcknowledgements fence split =====

// ===== Story 3f-4 — split-proposal fence split (BYTE-IDENTICAL in both runner.mjs files) =====
// Sibling of splitClarificationsFence. In SPLIT mode (a REVIEW dispatch whose bundle carries
// splitProposalRequested=true) the model proposes how to decompose the gate's reviewed artifact,
// emitting a fenced ```split block holding a single JSON OBJECT (not an array):
//   ```split
//   {"schemaVersion":1,"subtasks":[{"ordinal":1,"title":"...","scope":"..."}],"dependencies":[{"fromOrdinal":2,"toOrdinal":1}]}
//   ```
// This helper lifts that block out of the agent stdout: it returns the parsed `subtasks` array
// (each coerced to {ordinal,title,scope}; malformed entries dropped), the parsed `dependencies`
// array (each coerced to {fromOrdinal,toOrdinal}; malformed entries dropped), and the
// `strippedStdout` with the fence removed. Same NEVER-BLOCKS + byte-identical-strip discipline as
// splitClarificationsFence: a missing/malformed/empty/zero-subtask block is NON-FATAL and returns
// the original stdout UNCHANGED (the strip happens ONLY on >=1 usable subtask); a successful strip
// slices the ORIGINAL string at the fence-line boundaries (preserving CRLF + the trailing newline).
function splitSplitProposalFence(stdout) {
  if (typeof stdout !== 'string' || stdout.length === 0) {
    return {
      subtasks: [],
      dependencies: [],
      strippedStdout: typeof stdout === 'string' ? stdout : '',
    };
  }
  const OPEN = /^```split\s*$/;
  const CLOSE = /^```\s*$/;
  let blockStart = -1;
  let bodyStart = -1;
  let bodyEnd = -1;
  let blockEnd = -1;
  let pos = 0;
  while (pos < stdout.length) {
    const nl = stdout.indexOf('\n', pos);
    const eol = nl === -1 ? stdout.length : nl;
    const nextPos = nl === -1 ? stdout.length : nl + 1;
    const contentEnd = eol > pos && stdout[eol - 1] === '\r' ? eol - 1 : eol;
    const line = stdout.slice(pos, contentEnd);
    if (blockStart === -1) {
      if (OPEN.test(line)) {
        blockStart = pos;
        bodyStart = nextPos;
      }
    } else if (CLOSE.test(line)) {
      bodyEnd = pos;
      blockEnd = nextPos;
      break;
    }
    pos = nextPos;
  }
  if (blockStart === -1 || blockEnd === -1) {
    return { subtasks: [], dependencies: [], strippedStdout: stdout };
  }
  let subtasks = [];
  let dependencies = [];
  try {
    const parsed = JSON.parse(stdout.slice(bodyStart, bodyEnd));
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      if (Array.isArray(parsed.subtasks)) {
        subtasks = parsed.subtasks
          .filter(
            (s) =>
              s &&
              typeof s === 'object' &&
              Number.isInteger(s.ordinal) &&
              s.ordinal >= 1 &&
              typeof s.title === 'string' &&
              s.title.length > 0 &&
              typeof s.scope === 'string' &&
              s.scope.length > 0,
          )
          .map((s) => ({ ordinal: s.ordinal, title: s.title, scope: s.scope }));
      }
      if (Array.isArray(parsed.dependencies)) {
        dependencies = parsed.dependencies
          .filter(
            (d) =>
              d &&
              typeof d === 'object' &&
              Number.isInteger(d.fromOrdinal) &&
              d.fromOrdinal >= 1 &&
              Number.isInteger(d.toOrdinal) &&
              d.toOrdinal >= 1,
          )
          .map((d) => ({ fromOrdinal: d.fromOrdinal, toOrdinal: d.toOrdinal }));
      }
    }
  } catch {
    process.stderr.write('runner.mjs: split fence is not valid JSON — ignoring (no proposal)\n');
    subtasks = [];
    dependencies = [];
  }
  if (subtasks.length === 0) {
    return { subtasks: [], dependencies: [], strippedStdout: stdout };
  }
  const strippedStdout = stdout.slice(0, blockStart) + stdout.slice(blockEnd);
  return { subtasks, dependencies, strippedStdout };
}
// ===== end split-proposal fence split =====

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
  const summaryBase = (nonEmptyLines[0] ?? `Claude ${stage} runner produced output`).slice(0, 2000);
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
  // SHA-256 over the raw Claude output bytes — 64 lowercase hex chars (schema checksum).
  const hexDigest = createHash('sha256').update(rawOutput, 'utf8').digest('hex');

  // Story 3f-4 (AC2/R1) — advisory SPLIT-PROPOSAL mode: a REVIEW dispatch whose bundle carries
  // splitProposalRequested=true emits a split-proposal.v1 DECOMPOSITION (subtasks[]+dependencies[]),
  // NOT a review-result.v1 verdict (R1: reuse REVIEW *dispatch*, not its verdict harvest). The model
  // writes a fenced ```split block; splitSplitProposalFence lifts it. >=1 usable subtask => a valid
  // proposal; none => a failure-shaped result the backend SplitProposalHarvester degrades to
  // "proposal unavailable" (R5: the gate is NEVER blocked). Checked BEFORE the plain review arm.
  if (stage === 'review' && doc.splitProposalRequested === true) {
    const { subtasks, dependencies } = splitSplitProposalFence(rawOutput);
    const splitProposal = {
      schemaVersion: 1,
      workflowRunId,
      runnerExecutionId,
      subtasks,
      dependencies,
      summary,
      reviewerModelIdentity: 'claude',
      classification,
      failureCategory: subtasks.length > 0 ? null : 'runner_malformed_output',
    };
    writeAtomically(out, `${JSON.stringify(splitProposal, null, 2)}\n`);
    process.stdout.write(`${summary}\n`);
    process.exit(0);
  }

  // Story 3d-2 — advisory REVIEW stage emits a review-result.v1 verdict (NOT a runner-result.v1
  // artifact): the reviewer reads the embedded artifactReferences[0] content and produces a
  // pass/concern/fail verdict. Parse the verdict from the model's output (a `VERDICT: ...` marker
  // when present; default to `concern` — advisory + conservative — when the model gave no marker).
  // Backend redacts the rationale + computes provenance; this carries only what the runner knows.
  if (stage === 'review') {
    const reviewResult = {
      schemaVersion: 1,
      workflowRunId,
      runnerExecutionId,
      outcome: parseReviewOutcome(rawOutput),
      rationale: rawOutput.trim().length > 0 ? truncateRationale(rawOutput.trim(), 8000) : summary,
      summary,
      reviewerModelIdentity: 'claude',
      classification,
      failureCategory: null,
    };
    // Story 3g-3 follow-up (FR74) — attach the OPTIONAL reviewer token usage (lock-step with the
    // codex runner). A REVIEW invocation emits review-result.v1 (not runner-result.v1), so this is
    // the ONLY path by which the reviewer's token counts reach the runner_executions V31 columns.
    // Mirrors this runner's artifact path (buildUsage() only; real --json capture is a tracked
    // follow-up), so absence stays byte-identical.
    const reviewUsage = buildUsage();
    if (reviewUsage) reviewResult.usage = reviewUsage;
    writeAtomically(out, `${JSON.stringify(reviewResult, null, 2)}\n`);
    process.stdout.write(`${summary}\n`);
    process.exit(0);
  }

  let artifact;
  if (stage === 'spec') {
    const { questions, strippedStdout: afterQuestions } = splitClarificationsFence(rawOutput);
    // Story 3e-2 — also lift the acknowledgements fence (sibling of the questions fence), chaining
    // on the questions-stripped output so a spec carrying BOTH fences strips both from spec.md.
    const { acknowledgements, strippedStdout } =
      splitClarificationAcknowledgementsFence(afterQuestions);
    const contentReference = `artifacts/${workflowRunId}/spec.md`;
    const artifactPath = join(dirname(out), contentReference);
    writeAtomically(artifactPath, strippedStdout || summary);
    artifact = {
      artifactId,
      artifactType: 'spec',
      contentReference,
    };
    // Story 3e-1 — attach OPTIONAL open clarifications only when the agent fenced >=1
    // (never `questions: []`, so a no-question result stays byte-identical to pre-3e).
    if (questions.length > 0) {
      artifact.questions = questions;
    }
    // Story 3e-2 — attach OPTIONAL clarification acknowledgements only when the agent fenced >=1
    // (never `clarificationAcknowledgements: []`, mirroring the questions discipline).
    if (acknowledgements.length > 0) {
      artifact.clarificationAcknowledgements = acknowledgements;
    }
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

  // Story 3d-7 (FR69) — attach the OPTIONAL provider usage/limit metadata (5h/weekly window
  // status or the documented `not_exposed` marker). Additive: legacy runs / runners that omit
  // this stay byte-identical and the backend treats absence as a no-op.
  const providerUsage = buildProviderUsage(args['auth-var']);

  // Story 3g-3 (FR74) — attach the OPTIONAL per-execution token counts when available. Omitted
  // entirely when absent (best-effort, never throws) so a no-usage result stays byte-identical.
  const usage = buildUsage();
  process.stderr.write(`runner.mjs: built usage present=${usage !== undefined}\n`);

  const normalizedOutput = { summary, outcome: 'success', providerUsage };
  if (usage) normalizedOutput.usage = usage;

  const result = {
    schemaVersion: 1,
    workflowRunId,
    runnerExecutionId,
    artifactReferences: [artifact],
    normalizedOutput,
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
  case 'split-fenced':
    commandSplitFenced(parsed);
    break;
  default:
    fail(2, `unknown command: ${command ?? '(none)'}`);
}
