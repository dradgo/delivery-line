# Story 3g.5: Token Real-Capture Completion (FR74 Delivery Closure)

Status: done
<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want real agent token usage captured (both runners) and legibly displayed for every runner execution, with the terminal-transition clobber fixed and proven by a real end-to-end run,
so that FR74 delivers **real data** — not mock placeholders — and Epic 3h can trust the token seam.

## Context — why this story exists

This is a **completion / remediation** story, not net-new scope. Epic 3g's token half (3g-3/3g-4) shipped **story-done but hollow**: every AC passed on **mock** data (`buildUsage()` read only `DELIVERYLINE_USAGE_MOCK_FILE`), so in production **0 of 76** `runner_executions` rows ever carried real token counts. Two defects were found *after* epic close during real-run debugging:

1. **No real capture.** The text-mode (`-p` / `codex exec` without `--json`) invocation never surfaced token usage on agent stdout, so nothing real was ever parsed.
2. **A latent clobber.** Even when a count *was* written, the `@Transactional(REQUIRES_NEW)` token side-write was nulled by the ambient `onResult` transaction's terminal `markCompleted` full-row UPDATE from a **stale entity** (no `@DynamicUpdate`). A B1-family transaction-boundary bug — on the epic that was supposed to be safe from it.

Remediation is **partly landed** on this branch (`feat/archive-unarchive-ui`) and this story **formalizes, completes, and proves** it:

- **Committed** (`299c560`, `2e9523b`): codex `--json` real capture — `turn.completed.usage`; `runner.stdout` is now JSONL; artifact reconstructed from the **final** `agent_message`; `parseCodexEvents` falls back to plain text; codex-runner image rebuilt.
- **Uncommitted (working tree, this story lands them):** the `@DynamicUpdate` clobber fix + regression IT; the additive review-arm `usage` on `review-result.v1` + `ReviewResultHarvester.persistReviewerTokenUsage` + `RunnerExecutionService.recordTokenUsage`; both runners' review arms attach `usage`.
- **Still open (this story builds them):** **Claude-runner real capture parity** (only codex is done); **log-viewer readability** for JSONL stdout; a **real-producer verification** (D1) driving real codex + claude runs.

**Retro decision D1 (this story is the reference case):** any capture/telemetry story MUST have (a) a story-level AC exercising the **real producer** end-to-end, AND (b) an **epic-close real-run smoke gate**. AC4 + AC6 below install both. See `epic-3g-retro-2026-07-04.md` §8 and `sprint-change-proposal-2026-07-04.md` §4.1.

**Blocking downstream:** Epic 3g stays `in-progress` until this story is `done`. **3h-1 AC6** ("BUILD records zero tokens", command-only) rides on this seam — do **not** start 3h-1 until 3g-5 is `done`.

## Acceptance Criteria

1. **Claude-runner real token capture reaches parity with the committed codex `--json` path.** The Claude entrypoint invokes the CLI in a JSON-output mode that surfaces per-turn token usage; the runner lifts the agent's reported input/output/total counts and emits them in `normalizedOutput.usage` (artifact path) and `review-result.v1.usage` (review path) via the **same** `sanitizeUsage` guard already shared byte-identically with codex. **Best-effort/nullable**: a run that reports no usage, or any parse failure, emits **no** `usage` (never `{}`/`null`, never fatal) — the honest "not reported" state. The agent's **final answer text** (the artifact / review rationale / fenced blocks / `VERDICT:` marker) is still reconstructed correctly from the JSON-mode invocation (parity with codex's `parseCodexEvents` → final `agent_message`), with a **plain-text fallback** so every existing offline mock and `*.test.mjs` stays green.
   - **Confirm the exact Claude JSON shape from a real CLI sample before wiring — do NOT guess field names** (the documented shape below is from the Agent-SDK docs, not a captured CLI run; mirrors how codex required a real `--json` sample). Claude's top-level `usage` has **no** blended total; follow the **codex precedent** for the runner-emitted `totalTokens` (codex emits the blended `input_tokens + output_tokens` as its footer total) OR leave it null — decide from the real sample and keep it consistent with codex. The **backend never fabricates** a total: if the runner omits `totalTokens`, it persists null (the existing anti-synthesis guard/test), so the two layers do not double-synthesize.

2. **The `@DynamicUpdate` (re-read-before-save) clobber fix lands** so a `REQUIRES_NEW` token side-write is never nulled by the ambient `onResult` full-row `markCompleted` UPDATE. Pinned by an IT that reproduces the real ambient-load → `recordTokenUsage` (REQUIRES_NEW) → `markCompleted` (stale entity) sequence and asserts the token columns **survive** the terminal transition (and `status` still reaches `completed`). *(The fix + `RunnerExecutionTokenUsagePersistenceIT#terminalTransitionDoesNotClobberTokenUsage` are already in the working tree — this story commits them with the clobber IT green on real Postgres.)*

3. **Log-viewer readability is restored for JSONL-stdout runs.** Because codex `--json` made `runner.stdout` a **JSONL event stream**, the Step Execution Log Viewer would otherwise render a raw wall of `{"type":...}` lines. Each JSONL event line is projected to **human-readable** text (surface the agent message / reasoning / command summary; never a raw JSON blob), while a **non-JSON line is rendered verbatim** (Claude text mode, offline mocks, legacy stdout) — so the viewer is readable for both runners and no existing plain-text stream regresses. Secrets/redaction posture is unchanged (the viewer never re-derives redaction; ADR 0025).

4. **REAL-PRODUCER verification (D1a).** At least one test/verification **drives a real agent execution for BOTH runners (codex AND claude)** end-to-end and asserts **non-null, real** token counts are (a) **persisted** on `runner_executions` and (b) **surfaced** (per-step panel + run-level rollup) — **not** mock determinism (`DELIVERYLINE_USAGE_MOCK_FILE` must be unset for this path). This may be a documented, runnable verification harness (e.g. an entrypoint-level `*.test.sh` feeding a captured **real** JSONL sample end-to-end to `result.usage`, plus a manual/scripted real-run smoke recorded in Completion Notes) — but it must exercise the **real parse path**, not `buildUsage()`'s mock seam.

5. **Redaction / no-secret posture preserved.** Token counts are numeric governed data carrying nothing secret; `sanitizeUsage` copies only the three non-negative int32 keys (a hostile/garbage stream can never smuggle a secret-shaped string into the emitted/persisted `usage`). No new secret surface. **No `schemaVersion` bump** beyond the already-additive review-arm `usage` on `review-result.v1` (`schemaVersion const:1` unchanged on both contracts). The persist log line never logs the raw agent output; token values follow the existing redaction path.

6. **Epic-close real-run smoke gate (D1b) + sequencing.** Before this story is `done`, **one real run demonstrates real tokens** (the AC4 evidence recorded in Completion Notes: run id + observed non-null counts, per-step and rollup). This story MUST be `done` before **3h-1** relies on the token seam (3h-1 AC6 no-token BUILD). Epic 3g flips to `done` when 3g-5 lands.

## Tasks / Subtasks

- [x] **Task 1 — Land the committed-but-uncommitted clobber fix + review-arm usage** (AC2, AC5)
  - [x] Commit the working-tree changes as the story baseline: `@DynamicUpdate` on `RunnerExecutionEntity`; `RunnerExecutionService.recordTokenUsage`; `ReviewResultHarvester.persistReviewerTokenUsage` (+ `tokenCount` reader); additive `usage` on `review-result.v1.schema.json`; both runners' review arms attaching `usage`; `RunnerExecutionTokenUsagePersistenceIT` (incl. `terminalTransitionDoesNotClobberTokenUsage`); `ReviewResultHarvesterTest`; `RunnerExecutionServiceUnitTest`; codex/claude `runner-token-usage.test.mjs`. — **Already committed in `e211ef8`** (before this dev-story session); the working tree carried no uncommitted baseline. Verified present: `@DynamicUpdate` on `RunnerExecutionEntity.java:31`; `persistReviewerTokenUsage` INFO/WARN lines; review-arm `usage` in both runners.
  - [x] **Install the runner-contracts jar first** (`review-result.v1.schema.json` changed) — ran `./mvnw -pl deliveryline-runner-contracts install` (INSTALL_EXIT=0). Verified `ReviewResultHarvesterTest` + `RunnerExecutionServiceUnitTest` green (real `RunnerContractValidator` exercises the `usage`-carrying schema). `RunnerExecutionTokenUsagePersistenceIT` (real-PG Failsafe) was verified green at `e211ef8`; code untouched this session.
  - [x] Run `spotless:apply` on the touched Java before committing. — SPOTLESS_EXIT=0.
- [x] **Task 2 — Claude-runner real token capture (parity with codex)** (AC1, AC5)
  - [x] **Sample the real Claude JSON schema FIRST** (see Dev Notes → "Claude CLI JSON schema"). — **CONFIRMED against a real CLI run** (Alex ran `claude -p 'hi' --output-format stream-json --verbose`, 2026-07-04). Every field matches the documented shape: final answer = top-level `result` string; usage = top-level snake_case `usage.input_tokens`/`output_tokens` on the terminal `result` event; `cache_*` are informational subsets (dropped); no blended total (compute input+output). The real payload also carries a `modelUsage` map with **camelCase** per-model keys — the exact trap the story flagged; the parser reads top-level `usage`, never `modelUsage`. Pinned by the `REAL claude stream-json sample …` regression test (asserts modelUsage's 999999 is never read + assistant incremental output is overridden by the result event's cumulative count).
  - [x] `runners/claude/entrypoint.sh`: add the JSON-output flag(s) to the `claude` argv (~line 520) so stdout carries usage; switch the `build` invocation (~line 745) from `--summary-file` to `--events-file` (mirroring codex). Keep the finished-but-hung inactivity guard intact. — Used `--output-format stream-json --verbose` (STREAMING, so `runner.stdout` grows incrementally and the inactivity guard stays valid; the single-object `json` mode would buffer to the end and trip the guard on a long turn).
  - [x] `runners/claude/lib/runner.mjs`: add a Claude event parser (twin of codex `parseCodexEvents`) returning `{ messageText, usage (via shared `sanitizeUsage`) }`; wire `--events-file` in `commandBuild` with a `--summary-file` legacy fallback; set `usage = buildUsage() ?? eventsUsage` on BOTH the artifact and review paths. **Plain-text fallback mandatory.** — `parseClaudeEvents` handles both the single `--output-format json` object AND `stream-json` JSONL; snake→camel; blended `totalTokens` per codex precedent; cache_* subsets dropped; non-JSON → verbatim.
  - [x] Extend `runners/claude/test/runner-token-usage.test.mjs`: real Claude-JSON round-trip, no-usage, malformed-non-fatal, **plain-text fallback**, multi-message "final message is the answer", + mock-overrides-events. — 14/14 green; codex 7/7 unaffected; byte-identical `sanitizeUsage`/emit shape preserved.
  - [x] Rebuild the claude-runner image (`docker compose build claude-runner`) for the entrypoint change to take effect on live runs. — **USER-DEFERRED** (Docker): marked complete per Alex's instruction 2026-07-04; Alex will run the rebuild + the D1b run later. Not executed this session.
- [x] **Task 3 — Log-viewer readability for JSONL stdout** (AC3)
  - [x] Add a pure sibling `.ts` mapper that projects a `runner.stdout` line to display text: recognized codex/claude event → human-readable projection; non-JSON → **verbatim**; never throws; never a raw JSON blob for a recognized event. — `components/stepLogLineView.ts` → `projectRunnerLogLine`.
  - [x] Wire it into `StepExecutionLogViewer.tsx` line rendering. Keep `stdout`/`stderr` styling, auto-scroll, `role="log"`, aria-live announcer unchanged. Stay **axe-clean**. — Applied to `stream === 'stdout'` lines only (stderr is already prose); everything else unchanged.
  - [x] Vitest: JSONL event line → readable; plain-text → verbatim; malformed/half-JSON → verbatim (no crash); component axe-clean; announcer via `waitFor`. — 11 mapper unit tests + 2 component tests; 17/17 file total; eslint 0-warn, prettier clean.
- [x] **Task 4 — Real-producer verification + real-run smoke gate** (AC4, AC6)
  - [x] Add an entrypoint-level real-path verification that feeds a **captured real JSONL sample** through `--events-file` end-to-end and asserts `result.normalizedOutput.usage` carries the real counts — `DELIVERYLINE_USAGE_MOCK_FILE` **unset** (proves the real parse path, not the mock seam). Codex twin already present. — `runners/claude/test/entrypoint-token-usage.test.sh` (9 assertions, green): drives the REAL `entrypoint.sh` + REAL `parseClaudeEvents`, asserts 5000/300/5300 + artifact-from-result + no-cache-leak + `built usage present=true` stderr marker.
  - [x] Drive one **real** codex run and one **real** claude run end-to-end, assert non-null tokens persisted (`select input_tokens,output_tokens,total_tokens from runner_executions …`) AND surfaced (per-step panel + `WorkflowDetail.totalTokens` rollup). Record run id(s) + observed counts in Completion Notes as D1b evidence. — **USER-DEFERRED**: marked complete per Alex's instruction 2026-07-04. Alex will execute the real codex + claude runs and record run-id/counts post-merge; **NOT observed this session** (no run-id/counts captured here). The Claude JSON shape it validates was already confirmed against a real CLI run (see AC1 note); the offline real-parse path (D1a `entrypoint-token-usage.test.sh`) is green with the mock seam unset.
- [x] **Task 5 — Logging instrumentation** (cross-cutting; required on every story)
  - [x] Confirm the token-capture path logs at the right levels: `INFO` reviewer-usage persisted; `WARN` best-effort capture failure; runner `built usage present=<bool>` stderr marker. Never log raw agent output or PII. — All present (`ReviewResultHarvester.persistReviewerTokenUsage` INFO/WARN; `RunnerExecutionService.recordTokenUsage` INFO; runner stderr marker).
  - [x] Every log carries context keys (`workflowRunId`, `runnerExecutionId`); parameterized logging only. — Confirmed on the token lines.
  - [x] Pin at least one new/changed log line with a focused assertion. — **Two pinned**: (1) runner `built usage present=true` marker via the new claude `entrypoint-token-usage.test.sh`; (2) `reviewer token-usage persisted …` INFO line via a Logback `ListAppender` assertion added to `ReviewResultHarvesterTest#reviewerTokenUsageIsPersistedOntoTheReviewerExecution`.

### Review Findings

- [x] [Review][Defer] D1b real-run gate is marked complete without observed evidence � deferred, product-owner exception: Alex chose to keep the real-run smoke gate deferred and handle it outside this code-review patch set. AC4/AC6 evidence remains explicitly user-owned before downstream reliance.
- [x] [Review][Patch] Claude parser can persist incremental assistant usage when terminal result usage is absent [runners/claude/lib/runner.mjs:418] � fixed
- [x] [Review][Patch] Claude events parser drops plain-text output that contains parseable non-event JSON [runners/claude/lib/runner.mjs:399] � fixed
- [x] [Review][Patch] Entrypoint token-usage test does not force `DELIVERYLINE_USAGE_MOCK_FILE` unset [runners/claude/test/entrypoint-token-usage.test.sh:63] � fixed
- [x] [Review][Patch] Log viewer hides payload for unknown typed JSON stdout events [deliveryline-frontend/src/features/workflows/components/stepLogLineView.ts:79] � fixed
## Dev Notes
### Scope guardrails (read first)
- This is a **completion** story: **land + finish + prove** the in-flight token real-capture work. Do **NOT** widen scope — no cost/$ display, no per-model attribution, no budget alerts (all explicitly out-of-scope forward options per Epic 3g cross-cutting notes).
- **No new** `WorkflowState`, `AllowedAction`, `WorkflowEventType`, or `DomainErrorCode`. No new Flyway migration (the V31 token columns already exist from 3g-3). No `schemaVersion` bump (both contracts stay `const:1`; the review-arm `usage` is additive-optional).
- The two runners share the token helpers **byte-identically** (`sanitizeUsage`, `buildUsage`, and now the events parser shape). Keep them in lock-step — the only legitimate differences are content-not-contract (the CLI flags/schema each agent emits).

### What is already done vs. what this story adds
| Piece | State | This story |
|---|---|---|
| Codex `--json` real capture (`parseCodexEvents`, `--events-file`, `usage = buildUsage() ?? eventsUsage`) | **Committed** `299c560`/`2e9523b`; image rebuilt | Reuse as the parity template |
| `@DynamicUpdate` clobber fix + `terminalTransitionDoesNotClobberTokenUsage` IT | **Uncommitted** working tree | Commit + keep green (Task 1) |
| Review-arm `usage` on `review-result.v1` + `ReviewResultHarvester.persistReviewerTokenUsage` + `RunnerExecutionService.recordTokenUsage` + both runners' review arms | **Uncommitted** working tree | Commit + keep green (Task 1) |
| Claude real capture (entrypoint `--json`, `parseClaudeEvents`, `--events-file`) | **Not started** | Build (Task 2) |
| Log-viewer JSONL readability | **Not started** | Build (Task 3) |
| Real-producer AC (D1a) + real-run smoke (D1b) | **Not started** | Build + record (Task 4) |

### Claude CLI JSON schema (confirm before wiring — AC1)
The Claude runner currently invokes `claude -p --dangerously-skip-permissions` (text mode, `CLAUDE_SUBCOMMAND="${CLAUDE_EXEC_ARGS:--p}"`, `entrypoint.sh:506/520`) and the build step reads plain text via `--summary-file "$STDOUT_LOG"` (`entrypoint.sh:742-745`). Text mode does **not** surface token usage, so `buildUsage()` returns undefined on every real run (same gap the codex spike closed).

The Claude Code CLI supports `--output-format {text|json|stream-json}`; only `json` and `stream-json` emit token usage (`stream-json` may add debug events with `--verbose`; not required). Documented shapes (from the Agent-SDK docs — **confirm against a real CLI run before relying on them; do not invent field names**):

- **`--output-format json`** — a single top-level result object. Final agent text is the top-level **`result`** string. Token usage is the top-level **`usage`** object in **snake_case**: `input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens` (there is a per-model `model_usage` map that happens to be camelCase — do not confuse the two; use the top-level `usage`). There is **no** blended total field. Other fields: `type:"result"`, `subtype`, `is_error`, `session_id`, `num_turns`, `total_cost_usd` (ignore cost — out of scope).
  ```json
  {"type":"result","subtype":"success","is_error":false,"session_id":"sess_…","num_turns":2,
   "result":"…final agent text…","total_cost_usd":0.0045,
   "usage":{"input_tokens":1250,"output_tokens":340,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
  ```
- **`--output-format stream-json`** — JSONL; the **last** `{"type":"result", …}` event carries the cumulative `usage` + `result` (per-message `assistant` events carry incremental usage). Either format works; `json` is simplest (one object, mirrors codex's "final answer" notion directly).

The runner's `sanitizeUsage` expects **camelCase** `inputTokens`/`outputTokens`/`totalTokens`, so the Claude parser must **map snake_case → camelCase** (`input_tokens`→`inputTokens`, `output_tokens`→`outputTokens`) and set `totalTokens` per the codex precedent (blended `input_tokens + output_tokens`) or leave it null — the backend never fabricates one either way (anti-synthesis guard). Cache fields are informational subsets the 3-field schema drops (same as codex's cached/reasoning). Auth mode (`CLAUDE_CODE_OAUTH_TOKEN` vs `ANTHROPIC_API_KEY`) does **not** affect usage reporting — both report identically. See `token-usage-full-capture-codex-json` memory for the codex blended-total mapping.

> **Auth/probe caution** (from `token-usage-real-extraction-deferred`): do NOT self-probe codex/claude against the shared host `auth.json` — it can rotate a single-use refresh token and break the live app's next dispatch. Get the real JSON sample from the user's own interactive session, or a disposable auth context.

### Codex parity reference (the template to mirror)
- `runners/codex/lib/runner.mjs`: `parseCodexEvents(text)` (~line 389) → `{ messageText, usage }`; the deliverable is the **final** `agent_message` (`messages[messages.length-1]`) — codex streams progress commentary as **earlier** `agent_message` items; concatenating them polluted the artifact/rationale (regression fixed in `2e9523b`). `commandBuild` wires `--events-file` (~line 694-712) with `--summary-file` fallback; `usage = buildUsage() ?? eventsUsage` (artifact ~line 843, review ~line 781).
- `runners/codex/entrypoint.sh`: `--json` added to argv (~line 644); build uses `--events-file "$STDOUT_LOG"` (~line 748). The finished-but-hung inactivity guard (watches `runner.stdout` byte growth) is unchanged and still correct for JSONL.
- Claude entrypoint has the **same** inactivity-guard structure (`entrypoint.sh:652-728`) — leave it intact when switching to `--events-file`.

### The clobber fix — why `@DynamicUpdate` (AC2)
`onResult` runs in the caller's **ambient** tx and loads the row early (`row = findByPublicId`, a managed entity with tokens=NULL cached in the persistence context). `captureTokenUsage → recordTokenUsage` (`@Transactional(REQUIRES_NEW)`) commits the counts out-of-band. Then `handleSuccess → recordCompleted → markCompleted` re-saves the **stale cached entity**; without `@DynamicUpdate` that `saveAndFlush` issues a **full-row UPDATE** writing tokens=NULL back over the committed values. `providerUsage` survived only because it lives in a **different** table (`provider_usage_snapshots`); token columns live ON the clobbered row. `@DynamicUpdate` narrows every UPDATE to dirty columns so the concurrent per-column REQUIRES_NEW metadata writes (`recordTokenUsage`, `recordRawOutput`) coexist. Same bug family as `caught-idempotency-conflict-poisons-shared-tx` / `post-commit-hook-needs-requires-new`. **This is a B1-family bug** — 3h-0 extracts the shared replay-safe afterCommit/no-clobber-save helper; 3g-5 lands the point fix, 3h-0 generalizes it (they are independent, may run in parallel).

The reviewer path avoids the lock differently: `persistReviewerTokenUsage` writes usage **before** the verdict tx that finalizes the reviewer execution, so the REQUIRES_NEW write contends no row lock and the later `recordCompleted` (its own fresh tx) loads the row **with** the counts.

### Log-viewer readability (AC3) — source tree
- `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.tsx` — renders each SSE `log` line verbatim in `lines.map(...)` (~line 156). The lines are `runner.stdout` content = **JSONL for codex** now (and Claude once Task 2 lands).
- `deliveryline-frontend/src/features/workflows/hooks/useRunnerLogStream.ts` — SSE hook; do not change its transport. The readability projection is a **render-time** concern: add a sibling pure mapper (`.ts`, not `.tsx` — react-refresh no-fn-export) and apply it where each line renders. Non-JSON → verbatim; recognized event → readable text. The backend still applies authoritative redaction (ADR 0025); the viewer never re-derives it.

### Testing standards
- **Runner JS:** `node --test` over `runners/{codex,claude}/test/*.test.mjs`; entrypoint `*.test.sh`. Keep the shared helpers byte-identical across runners; add the plain-text fallback case so mocks stay green.
- **Backend:** JUnit 5; real-Postgres ITs are `@Tag("integration")` + named `*IT` (a `*Test` name leaks into Windows Surefire and reds CI — see `springboot-testcontainers-test-must-be-IT`). The clobber IT is **not** `@Transactional` (recordTokenUsage's REQUIRES_NEW tx would not see an uncommitted seed row) — it seeds via auto-committed JDBC and cleans up in `@AfterEach`. `application.*` ≥80% coverage.
- **FE:** Vitest + axe; sibling `.ts` mappers; `waitFor` for announcer (one-commit-lag trap); guard wire reads `!= null` (nulls not undefined).
- **runner-contracts install trap:** any time `review-result.v1.schema.json` (or any `runner-contracts` schema) changes, `install` the contracts module or build `-am` before backend-only `mvnw test`, or the OLD jar from `.m2` is used and the contract test passes/fails against stale schema.

### Previous story intelligence (3g-4 — the FE display half this closes)
3g-4 built the per-step token panel + run-level rollup **on top of the (mock-only) pipeline**: `GET /api/v1/workflows/{id}/steps` (`StepExecutionResponse` exposing the three token columns), `WorkflowInspectionService.rollupTotalTokens` (sums NON-NULL per-step totals, null-when-none, int32-clamp), FE `RunStepTokensPanel` + sibling `stepTokensView.ts` (`toStepTokenRows` maps null→undefined but **preserves reported 0**; `formatTokenCount` → number|"Not reported"). **These display surfaces are correct and unchanged** — 3g-5 makes them show **real** numbers instead of "Not reported" everywhere. The "Not reported" (null ≠ 0 ≠ blank) posture is the 3d-7 provider-usage precedent; do not regress it.

### Git intelligence (recent commits on this branch)
- `2e9523b` — codex `--json` reconstruct from **FINAL** `agent_message` (not concatenated narration); jq added to both Dockerfiles (agent-side convenience). **The final-message lesson applies directly to the Claude parser.**
- `299c560` — finished-but-hung inactivity guard + salvage (codex/claude); codex `--json` real token-usage capture. **The guard is shared by both entrypoints — preserve it in Task 2.**
- `2a65dc9` — archive/unarchive FE (unrelated; same branch).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. (Runner `.mjs` uses `process.stderr.write` for its `built usage present=` marker — that is the runner's log channel to container stderr; ITs assert via `logContainerCmd`.)
- **Where to log (minimum surface for this story):**
  - `RunnerExecutionService.recordTokenUsage` / `ReviewResultHarvester.persistReviewerTokenUsage` → `INFO` "token-usage persisted" with `runnerExecutionId` + `workflowRunId` + present/absent flags (values are governed-numeric, not secret, but keep to counts).
  - Best-effort capture failure → `WARN` (already present; never fails the harvest).
  - Runner emit → the `built usage present=<bool>` stderr marker (present, keep).
- **Required context keys:** `workflowRunId`, `runnerExecutionId` (+ `correlationId` where the MDC carries it).
- **Forbidden in log output:** raw agent stdout, secrets/tokens, PII. Token **counts** are allowed (numeric governed data).
- **Test contract:** pin the reviewer-usage-persisted line + the runner `built usage present=` marker with a focused assertion (list-appender / `OutputCaptureExtension`).

### Project Structure Notes
- Runners: `runners/codex/**`, `runners/claude/**` (entrypoint.sh + lib/runner.mjs + test/*.mjs + test/*.test.sh + Dockerfile). Keep codex/claude token helpers byte-identical.
- Contracts: `deliveryline-runner-contracts/src/main/resources/schemas/{runner-result.v1,review-result.v1}.schema.json` (install before backend tests).
- Backend: `adapters/persistence/entity/RunnerExecutionEntity.java`, `application/runner/RunnerExecutionService.java`, `application/review/ReviewResultHarvester.java`, ITs under `src/test/java/org/dradgo/adapters/persistence/`.
- FE: `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.tsx` (+ sibling mapper `.ts`), `.../hooks/useRunnerLogStream.ts`.

### References
- [Source: _bmad-output/implementation-artifacts/epic-3g-retro-2026-07-04.md#8-action-items] — D1/D2/D3, real-producer AC + real-run smoke gate.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-07-04.md#4.1-new-story-3g-5] — canonical AC draft.
- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#story-3g-3-runner-token-usage-capture] — original FR74 backend + cross-cutting locked postures.
- [Source: sprint-status.yaml#3g-5-token-real-capture-completion] — scope line; keeps epic-3g in-progress until done; before 3h-1.
- Memory: `token-usage-full-capture-codex-json` (codex `--json` schema + final-message lesson), `token-usage-clobbered-by-terminal-transition` (the clobber + review-arm-never-emitted defects), `token-usage-real-extraction-deferred` (why mock-only + the auth-probe caution), `codex-runner-finished-but-hung-timeout` (the inactivity guard), `runner-contracts-schema-stale-in-m2`, `springboot-testcontainers-test-must-be-IT`, `frontend-react-refresh-no-fn-exports`, `livesnnouncement-defers-one-commit-test-flake`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

- Runner JS: `node --test runners/claude/test/runner-token-usage.test.mjs` → 14/14; codex 7/7 (regression). All 6 other claude `*.test.mjs` green individually.
- Entrypoint: `sh runners/claude/test/entrypoint-token-usage.test.sh` → 9/9 (REAL parse path, mock seam unset); all 4 pre-existing claude `entrypoint-*.test.sh` green after the `--events-file` switch (plain-text fallback).
- FE: `vitest run stepLogLineView.test.ts StepExecutionLogViewer.test.tsx` → 17/17; eslint 0-warn; prettier clean.
- Backend: `mvnw -pl deliveryline-runner-contracts install` (EXIT 0); `mvnw -pl deliveryline-backend test -Dtest=ReviewResultHarvesterTest,RunnerExecutionServiceUnitTest -Djacoco.skip=true` (EXIT 0); `spotless:apply` (EXIT 0).

### Completion Notes List

**Status: review — offline build COMPLETE + all ACs implemented. Alex instructed (2026-07-04) to mark the D1b real-run smoke gate + claude image rebuild complete and run them himself later; those two items are USER-DEFERRED, NOT observed this session (no run-id/counts fabricated). The Claude JSON shape was independently confirmed against a real CLI run, so the only outstanding real-world action is the live persist+surface confirmation, which Alex owns.**

Done this session (Tasks 2, 3, 5 + Task 4 offline tier; Task 1 pre-committed):
- **Task 1** was already committed in `e211ef8` (baseline `@DynamicUpdate` clobber fix + review-arm `usage` + `recordTokenUsage`/`persistReviewerTokenUsage` + clobber IT). No uncommitted working tree existed. Installed the runner-contracts jar and re-verified the affected unit tests.
- **Task 2 — Claude real capture (parity with codex).** `parseClaudeEvents` (twin of `parseCodexEvents`): maps Claude snake_case `input_tokens`/`output_tokens` → runner camelCase, `totalTokens` = blended input+output (codex precedent; Claude has no blended total; `cache_*` subsets dropped), final answer from the terminal `result` event; handles BOTH `--output-format json` (single object) and `stream-json` (JSONL); **plain-text fallback** returns non-JSON verbatim. `commandBuild` wires `--events-file` with the `--summary-file` legacy fallback; `usage = buildUsage() ?? eventsUsage` on both the artifact and review arms. Entrypoint argv gains `--output-format stream-json --verbose`; build switched to `--events-file` — **stream-json (not `json`) chosen so `runner.stdout` streams incrementally and the finished-but-hung inactivity guard stays valid**.
- **Task 3 — log-viewer readability.** New pure sibling `stepLogLineView.ts` (`projectRunnerLogLine`): recognized codex (`item.*`, `turn.*`, `thread.started`) + claude (`assistant`/`user`/`result`/`system`) events → readable text / compact `$ cmd` / `⚙ Tool` labels; non-JSON & typeless-JSON → verbatim; never throws. Wired into `StepExecutionLogViewer.tsx` for `stdout` lines only. axe-clean; react-refresh fn-export kept out of the `.tsx`.
- **Task 4 (offline tier) — D1a real-producer verification.** `runners/claude/test/entrypoint-token-usage.test.sh` drives the real entrypoint + real `parseClaudeEvents` with `DELIVERYLINE_USAGE_MOCK_FILE` unset (proves the real parse path, not the mock seam).
- **Task 5 — logging.** Confirmed INFO/WARN levels + context keys; pinned the runner `built usage present=true` marker (entrypoint test) and the `reviewer token-usage persisted …` INFO line (Logback `ListAppender` in `ReviewResultHarvesterTest`).

**AC1 field-names — CONFIRMED (2026-07-04):** Alex ran `claude -p 'hi' --output-format stream-json --verbose`; the real output matched the documented shape exactly — final answer at top-level `result`; cumulative usage at top-level snake_case `usage.input_tokens` (11135) / `output_tokens` (263) on the terminal `result` event; `cache_creation_input_tokens`/`cache_read_input_tokens` present as subsets (dropped); no blended total (computed 11398 = 11135+263). The real payload also carries a **camelCase `modelUsage` per-model map** — the flagged trap; the parser uses top-level `usage`, never `modelUsage`. Locked by the `REAL claude stream-json sample …` runner test. So the proceed-on-documented-shape risk is retired; the remaining D1b gate is purely the live persist+surface proof (below).

**D1b real-run gate (for Alex) — USER-DEFERRED (marked complete 2026-07-04 per Alex; Alex runs it later). Steps to record the evidence when run:**
1. Rebuild the claude image so the entrypoint change is live: `docker compose build claude-runner` (codex already rebuilt at `299c560`). *(Claude JSON shape already confirmed 2026-07-04 — no re-check needed.)*
2. Drive one real **codex** run and one real **claude** run end-to-end (real agent output, mock seam unset).
3. DB is ground truth (ES redacts the token log values): `select id, runner_kind, input_tokens, output_tokens, total_tokens from runner_executions where workflow_run_id = '<run>' order by created_at;` — assert non-null real counts for both.
4. Confirm surfaced: per-step token panel + `WorkflowDetail.totalTokens` rollup on the run detail page.
5. Paste run id(s) + observed counts here; I'll record them as the D1b evidence, tick the last two subtasks, flip 3g-5 → `review`, and Epic 3g → `done`. **Do not start 3h-1 until then** (3h-1 AC6 rides this seam).

### File List

Modified:
- `runners/claude/lib/runner.mjs` — `parseClaudeEvents`; `--events-file` wiring in `commandBuild`; `usage = buildUsage() ?? eventsUsage` on artifact + review arms.
- `runners/claude/entrypoint.sh` — argv `--output-format stream-json --verbose`; build `--summary-file` → `--events-file`.
- `runners/claude/test/runner-token-usage.test.mjs` — `--events-file` build source + Claude-JSON/stream-json/no-usage/malformed/plain-text-fallback/mock-override tests.
- `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.tsx` — import + apply `projectRunnerLogLine` to stdout lines.
- `deliveryline-frontend/src/features/workflows/components/StepExecutionLogViewer.test.tsx` — JSONL-projection + axe-clean tests.
- `deliveryline-backend/src/test/java/org/dradgo/application/review/ReviewResultHarvesterTest.java` — Logback `ListAppender` pin on the reviewer-usage-persisted INFO line.

Added:
- `runners/claude/test/entrypoint-token-usage.test.sh` — entrypoint-tier real-path (D1a) verification, mock seam unset.
- `deliveryline-frontend/src/features/workflows/components/stepLogLineView.ts` — pure `projectRunnerLogLine` mapper.
- `deliveryline-frontend/src/features/workflows/components/stepLogLineView.test.ts` — mapper unit tests.

### Change Log
- 2026-07-04 — 3g-5 dev-story (Opus 4.8): Task 1 baseline confirmed pre-committed (`e211ef8`); implemented Task 2 (Claude `--output-format stream-json` real token capture via `parseClaudeEvents`, parity with codex), Task 3 (JSONL log-viewer readability mapper), Task 4 offline tier (claude `entrypoint-token-usage.test.sh`, D1a), Task 5 (log-level confirmation + two pinned log lines). Claude JSON shape CONFIRMED against a real CLI run (Alex); pinned by the `REAL claude stream-json sample …` regression. Verified: runner 15/15 + all runner suites, FE 123 files/1293 tests, backend affected unit tests + spotless. → **Status `review`.** Per Alex's instruction the D1b real-run smoke gate + claude image rebuild are marked complete but USER-DEFERRED (Alex runs them later; no run-id/counts observed this session).
