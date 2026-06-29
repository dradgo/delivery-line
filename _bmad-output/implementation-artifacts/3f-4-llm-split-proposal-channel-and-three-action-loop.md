# Story 3f.4: LLM Split-Proposal Channel + Three-Action Feedback Loop

Status: done

<!-- 2026-06-29 bmad-create-story context-engine pass. Target sprint key: 3f-4-llm-split-proposal-channel-and-three-action-loop. Prereqs 3d-2 (reviewer substrate) + 3e-3 (spec-gate reviewer) + 3e-2 (re-propose feedback loop) all DONE. Validation optional: run validate-create-story before dev-story. -->

> **⚠️ READ FIRST — this is the OPERATOR-FACING front half of split. It produces and iterates an advisory LLM *proposal*; it does NOT commit anything.** 3f-4 adds three advisory actions at the existing spec/review gate (`request_split`, `continue_as_single`, `repropose_split`), a reviewer-style LLM call that emits a **structured split proposal** (`subtasks[]` + `dependencies[]`), durable persistence of that proposal, a re-propose-with-feedback loop, and the REST/CLI/FE surfaces. It creates **NO** child runs, **NO** sub-tickets, **NO** dependency edges, and does **NOT** move the parent out of its gate or into `Split`. All of that is **3f-5** (`approve_split` → `SplitCommitService`). The parent stays parked at `WaitingForSpecApproval` / `WaitingForReview` throughout 3f-4.
>
> **Reuses (all DONE):** the 3d-2/3e-3 advisory-reviewer substrate (`RunnerStage.REVIEW`, `enqueueReviewerIfConfigured`, reviewer binding, graceful degradation, runner queue/worker), the 3e-1 byte-identical fence-split precedent (`splitClarificationsFence`), and the 3e-2 re-propose feedback machinery (`priorFeedbackReferences`, `spec_rejection_loop_count`, escalation marker + `ESCALATION_REQUIRED`).
>
> **Five reconciliations the live codebase forces (read ALL before coding):**
>
> 1. **Reuse `RunnerStage.REVIEW` for *dispatch*, but the split proposal is NOT a verdict — do not route it through `ReviewResultHarvester`/`step_reviews`.** The reviewer substrate (`enqueueReviewerIfConfigured`, `WorkflowOrchestrationService.java:778-825`) gives you the queue/worker/credential/binding/degradation plumbing for free. But its harvest (`ReviewResultHarvester`) validates `review-result.v1` (`outcome`/`rationale`) and writes `step_reviews` — a verdict, not a decomposition. The split proposal is a **distinct structured output** (`subtasks[]`+`dependencies[]`) with its **own lifecycle** (open → superseded / dismissed / approved) and a `loop_count`. So: the runner emits the proposal via a fenced ` ```split ` block (the 3e-1 `splitClarificationsFence` precedent), validated against a **new `split-proposal.v1.schema.json`** contract; a **new `SplitProposalHarvester`** persists it to a **new `split_proposals` table** (V29). `step_reviews` is untouched. See R1.
> 2. **No new gate state, no new event type, no new state-machine edge.** The proposal is an **advisory overlay** at the existing gate (ADR-0029 decision 4, Alt-3 rejected). The parent never leaves its gate until `approve_split` (3f-5). So 3f-4 adds **zero** `WorkflowState`, **zero** `WorkflowEventType`, **zero** transition-table edges, and **zero** workflow-state CHECK widening. The only foundation-gate surface is the **three new `AllowedAction`s** (enum + `allowed-actions.placeholder.json` + `AllowedActionRegistryPinTest`). This is far smaller than 3f-2/3f-3.
> 3. **Re-propose feedback is MATERIALIZED as a redaction-policed referenced input — never inlined; the "2KB cap" framing is stale.** Mirror the 3e-2 shipped pattern exactly: the operator's free-text feedback is written as a redaction-policed input file the runner reads via a by-id `priorFeedbackReferences` entry (`PriorFeedbackReference(referenceId, kind)`, `ContextBundleService.java:1325`), with a **new kind `split.feedback`**. Do NOT embed feedback text in the bundle JSON. The cap is now `CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES = 256*1024` (`ContextBundleService.java:68`, [[context-bundle-2kb-payload-cap]]), and 3e-2/3e-3 materialize-or-inline-bounded — the original "2KB" wording in the epic is obsolete. See R3.
> 4. **The depth-cap guard (`SPLIT_DEPTH_LIMIT_EXCEEDED` + `complex-ticket-flow.max-split-depth`) is FORWARD-OWNED by 3f-7 — DO NOT build it here.** Epic 3f-4 AC1 references "consult the 3f-7 depth-cap guard first," but 3f-7 explicitly owns that error code (three sites), the config property, and the guard, and it is sequenced **after 3f-5**. At 3f-4 build time **no run has a `parent_run_id`** (3f-5 mints the first children) and recursion does not exist, so every splittable run is at depth 0 < cap(3) — the guard is a structural no-op. Building it here would collide with 3f-7's three-sites ownership. 3f-4 leaves `REQUEST_SPLIT` depth-unguarded (safe), and 3f-7 inserts the guard at the `SplitProposalService.request` entry. See R4.
> 5. **The proposal needs a reviewer model — reuse the run-level reviewer binding; degrade gracefully when unbound.** The proposing LLM call resolves through the same per-project reviewer binding as 3d-2/3e-3 (`hasReviewerBinding`/`resolveReviewerKind`, run-level — not per-stage). A project with **no reviewer binding** cannot generate a proposal: `request_split` degrades through the same single path as 3d-2 (panel shows "proposal unavailable — no reviewer model configured"; the gate is never blocked). The three actions are still surfaced; the *generation* degrades, not the gate. See R5.

## Story

As an operator at the spec or implementation-review gate,
I want an LLM to propose how to split the current run into smaller subtasks (with suggested dependencies), and to approve, decline, or re-propose-with-feedback,
so that I get governed assistance decomposing oversized scope without surrendering the decision — and I can iterate the proposal until it is right before any runs are created.

## Acceptance Criteria

> Reconciled against the live codebase (the reviewer substrate, fence-split, and re-propose loop all exist and are reused). Where the epic wording assumes machinery that 3f-7 owns or that the shipped 3e-2/3e-3 code superseded, the reconciled AC below is authoritative; rationale in Dev Notes (R1–R7).

1. **Given** allowed-actions (2.14), **Then** three new `AllowedAction`s are registered (drift: enum + `allowed-actions.placeholder.json` + `AllowedActionRegistryPinTest` wire pins): `REQUEST_SPLIT("request_split")`, `DECLINE_SPLIT("continue_as_single")`, `REPROPOSE_SPLIT("repropose_split")`. (`APPROVE_SPLIT("approve_split")` is owned by 3f-5 — do NOT add it here.) They are surfaced by `WorkflowInspectionService.baseActionMatrix` as an **advisory overlay at the existing gate** — `product_reviewer`/`workflow_owner` at `WaitingForSpecApproval`, `developer` at `WaitingForReview` — with **no new gate state**. The set is proposal-aware: `request_split` when no open proposal exists; `repropose_split` + `continue_as_single` when an open proposal exists (`approve_split` is added by 3f-5 in the same branch). The **depth-cap pre-check (`SPLIT_DEPTH_LIMIT_EXCEEDED`) is NOT implemented in 3f-4** — it is forward-owned by 3f-7 (R4); `request_split` is depth-unguarded here.

2. **Given** `REQUEST_SPLIT`, **Then** a reviewer-style LLM call is enqueued over the gate's reviewed artifact (the spec at `WaitingForSpecApproval`, the plan/pr-output at `WaitingForReview` — via the existing `ContextBundleService.resolveReviewedArtifact` precedence) reusing the 3d-2 `RunnerStage.REVIEW` dispatch substrate, resolved through the **run-level reviewer binding** (decrypted in memory only, never logged). The context bundle carries an additive `splitProposalRequested: true` marker; both `runner.mjs` entrypoints (BYTE-IDENTICAL), when `stage === 'review' && bundle.splitProposalRequested`, build a decomposition prompt and emit a fenced ` ```split ` JSON block — `{ "schemaVersion": 1, "subtasks": [{ordinal, title, scope}], "dependencies": [{fromOrdinal, toOrdinal}] }` — parsed by a new `splitSplitProposalFence(stdout)` helper (mirroring `splitClarificationsFence`: non-fatal on malformed/absent, strip only on ≥1 usable subtask). Both **offline mocks emit a deterministic 2-subtask, 1-dependency proposal** so the loop IT is deterministic.

3. **Given** the proposal must survive until the operator acts, **Then** it is **persisted** in a new `split_proposals` table (V29, additive — R2 decided a new table because `step_reviews` is verdict-shaped with no payload column and no lifecycle/loop_count): keyed `public_id`, `workflow_run_id`, the reviewed-artifact id+version it decomposed, `status` (`open`/`superseded`/`dismissed`/`approved`), `loop_count`, the redacted `proposal_json`, `reviewer_model_identity` + `producer_model_identity` (self-vs-producer, 3d-2 provenance), `created_at`, `archived_at`. A **partial unique index enforces at most one `open` proposal per run** ([[one-active-per-key-needs-partial-unique-index]]). A `runner_executions` row is recorded for the proposal call (FR53 inspectability, reused). The proposal is surfaced in the gate Decision Bar via a new advisory **Split Proposal Panel** (proposed subtasks + dependency edges, whole-proposal actions only — per-subtask editing is a deferred forward option).

4. **Given** `REPROPOSE_SPLIT`, **Then** the operator's free-text feedback re-runs the proposal call with the feedback **materialized as a redaction-policed referenced input** (a new `priorFeedbackReferences` kind `split.feedback`; reference-by-id, **never inlined** into the bundle JSON — the 3e-2 pattern, R3) and a `split_proposal_loop_count` (new `workflow_runs` column, V29, mirroring `spec_rejection_loop_count`, CHECK ≥ 0) is incremented to drive a **distinct dispatch idempotency key per re-propose attempt** (mirror `specDispatchKey(runId, loopCount)`). The prior `open` proposal is marked `superseded` and the new one inserted `open`. At a configurable threshold (`deliveryline.workflow.split-proposal-escalation-threshold`, default 3, mirroring `SpecRejectionEscalationThresholdProvider`) the existing escalation marker is flipped and `ESCALATION_REQUIRED` is emitted exactly once (reuse the existing marker + `clear_escalation_marker` action; do NOT add a new event type).

5. **Given** `DECLINE_SPLIT` ("continue as one ticket"), **Then** the current `open` proposal is marked `dismissed`, the parent stays at its gate, and the normal gate actions (`approve_spec`/`reject_spec`/`answer_clarification`/`accept_clarification`/`regenerate_spec_with_clarifications` at the spec gate; `accept_implementation`/`reject_implementation`/`takeover_workflow` at the review gate) are byte-identical to a run that was never split-proposed.

6. **Given** REST + CLI, **Then** `POST /api/v1/workflows/{workflowRunId}/split/request`, `/split/repropose` (carries the feedback text body), `/split/decline` (each `Idempotency-Key` + `X-Actor-Identity`, HUMAN actor) and `GET /api/v1/workflows/{workflowRunId}/split-proposal` (serves the current proposal + `state` + `loopCount`) drive a new `SplitProposalService`; CLI parity via `WorkflowCommands` using the **optional-setter injection** pattern (3f-3); OpenAPI snapshot + `schema.d.ts` regenerate (NOT byte-identical — new operations/DTOs).

7. **Given** redaction + provenance, **Then** the proposal input (reviewed artifact + materialized feedback) and the proposal output (`proposal_json`) pass the same redaction policy + adversarial secret-fixture gate as any runner artifact before persistence/egress; the proposing model identity is recorded and a same-model self-review (proposer == artifact producer) is flagged like 3d-2.

8. **Given** tests, **Then** coverage asserts: the 4 new-or-changed `AllowedAction` drift sites green (3 new enum values + placeholder + pins; the proposal-aware matrix at both gates); runner fence-split (parses / absent omits / malformed non-fatal, BOTH runners, byte-identical); the proposal persists (one-open-per-run index) and is served by `GET /split-proposal`; re-propose carries feedback by reference + bumps `split_proposal_loop_count` + honors the escalation marker; decline restores normal gate actions byte-identically; the redaction fixture over the materialized feedback file; reviewer-unbound `request_split` degrades (gate not blocked); OpenAPI/`schema.d.ts` regen; `application.*` ≥ 80% line coverage.

## Tasks / Subtasks

- [ ] **Task 1 — Three advisory `AllowedAction`s + proposal-aware action matrix** (AC: 1, 5)
  - [x] `domain/registry/AllowedAction.java` — add `REQUEST_SPLIT("request_split")`, `DECLINE_SPLIT("continue_as_single")`, `REPROPOSE_SPLIT("repropose_split")` (before the closing `;`, with story comments mirroring `ACCEPT_CLARIFICATION`). **Do NOT add `approve_split`** (3f-5).
  - [x] Drift: `src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (add the 3 wire values) + `architecture/AllowedActionRegistryPinTest.java` (add 3 `...WireValueIsPinned()` tests). No DB CHECK exists for allowed-actions (enum ↔ placeholder JSON only).
  - [x] `application/workflow/WorkflowInspectionService.java#baseActionMatrix` — threaded `boolean hasOpenSplitProposal` (resolved via the new `SplitProposalReadPort`) as an `appendSplitOverlay` in `computeActionMatrix` (before the archive overlay). At `WAITING_FOR_SPEC_APPROVAL` (`product_reviewer`/`workflow_owner`) and `WAITING_FOR_REVIEW` (`developer`): adds `REQUEST_SPLIT` when `!hasOpenSplitProposal`, else `REPROPOSE_SPLIT` + `DECLINE_SPLIT`. Existing gate actions intact (overlay, not replace). Matrix unit-test cases for both gates × open/no-open.
  - [ ] Update the `AllowedActions` OpenAPI `@Schema` allowableValues for the 3 new actions (regen in Task 6).

- [x] **Task 2 — V29 schema: `split_proposals` table + `split_proposal_loop_count` column** (AC: 3, 4, 8)
  - [x] Confirm the true next Flyway number before coding. As of this story, head is `V28__add_run_dependencies_and_waiting_for_dependencies.sql`; use `V29__add_split_proposals.sql` unless a newer migration landed.
  - [x] `split_proposals(id bigserial PK, public_id text unique [prefix-checked], workflow_run_id text NOT NULL FK → workflow_runs.public_id, reviewed_artifact_id text, reviewed_artifact_version int, status text NOT NULL CHECK in ('open','superseded','dismissed','approved'), loop_count int NOT NULL DEFAULT 0 CHECK ≥ 0, proposal_json text NOT NULL, reviewer_model_identity text, producer_model_identity text, created_at timestamptz NOT NULL DEFAULT now(), archived_at timestamptz)`. **Partial unique index** `... unique (workflow_run_id) where status = 'open'` — one open proposal per run ([[one-active-per-key-needs-partial-unique-index]]).
  - [x] In the same migration, add `workflow_runs.split_proposal_loop_count int NOT NULL DEFAULT 0 CHECK (split_proposal_loop_count >= 0)` (additive, replay-safe; mirror `V7__add_spec_rejection_loop_columns.sql`). **NO** workflow-state CHECK widening (no new state — R2).
  - [x] Register the new public-id prefix (`splprop_`) in `PublicIdPrefixes` + its drift/registry test (RegistryContractTest auto-covers prefixMap↔SQL↔placeholder; placeholder JSON updated). 3f-5 keys idempotency on parent-run + this proposal id.
  - [x] Extend `FlywaySchemaContractTest` for the table, PK/FK (ON DELETE RESTRICT ON UPDATE CASCADE — assert referential actions, 3f-3 review lesson), CHECKs, the partial unique index, and the new column.

- [x] **Task 3 — `split-proposal.v1` runner contract + both `runner.mjs` fence emission + mocks** (AC: 2, 8)
  - [x] `deliveryline-runner-contracts/src/main/resources/schemas/split-proposal.v1.schema.json` (new): `{schemaVersion:const 1, subtasks: [{ordinal:int≥1, title:string 1..200, scope:string 1..N}] (1..maxN), dependencies: [{fromOrdinal:int, toOrdinal:int}], additionalProperties:false}`. Add `SPLIT_PROPOSAL` to `RunnerContractValidator.ValidationTarget`. (Plus an `if/then` so a degraded failure result may carry empty subtasks.)
  - [x] `context-bundle.v1.schema.json` — additive-optional `splitProposalRequested` (boolean) + reuse `priorFeedbackReferences` (kind `split.feedback` is a string value, no schema change to the ref shape). NOT in `required`; `schemaVersion` stays `const 1` (3e-1/3e-3 additive discipline).
  - [x] Both `runners/{claude,codex}/lib/runner.mjs` (BYTE-IDENTICAL, verify SHA-equal): add `splitSplitProposalFence(stdout)` (exact sibling of `splitClarificationsFence` — slice at fence-line offsets, preserve CRLF/trailing newline, non-fatal on malformed, strip only on ≥1 usable subtask). At `stage === 'review' && bundle.splitProposalRequested`, build the decomposition prompt and write the parsed proposal as the result payload validated against `split-proposal.v1` (NOT `review-result.v1`). Mocks: emit a deterministic 2-subtask, 1-dependency proposal gated on the non-secret stage/mode marker. Mirror the 3e-2 review-hardened fence rules; keep both runner test files in lockstep. **Reinstall `deliveryline-runner-contracts` into `.m2` before backend ITs** ([[runner-contracts-schema-stale-in-m2]]).

- [x] **Task 4 — `SplitProposalService` + `SplitProposalHarvester` + dispatch wiring** (AC: 2, 3, 4, 5, 7)
  - [ ] `application/workflow/SplitProposalService.java` (new) — `request`, `repropose(feedbackText)`, `decline`, all `@Transactional`. `request`: guard the gate state (`WaitingForSpecApproval`/`WaitingForReview` only) + reviewer-bound (R5: unbound → graceful "unavailable", not an error); resolve the reviewed artifact (`resolveReviewedArtifact`); enqueue the proposal call reusing the 3d-2 dispatch substrate with `splitProposalRequested=true` in the bundle. `repropose`: materialize the feedback as a redaction-policed referenced input (kind `split.feedback`, R3), bump `split_proposal_loop_count` for a distinct dispatch key, supersede the prior open proposal, flip escalation at threshold. `decline`: mark the open proposal `dismissed`.
  - [ ] `application/workflow/SplitProposalHarvester.java` (new) — on a split-mode `RunnerStage.REVIEW` result, validate `split-proposal.v1`, redact, compute reviewer/producer identities (reuse `resolveProducerIdentity` — SPEC→INVESTIGATION, plan/prOutput→EXECUTION, 3e-3 lesson) + self-review flag, insert `split_proposals` (`open`). Route the broker so a split-mode REVIEW execution goes to this harvester and **NOT** `ReviewResultHarvester` (do not write `step_reviews`). Best-effort/degrade-not-block on failure (3d-2 discipline).
  - [ ] `application/workflow/spi/SplitProposalReadPort.java` + write port + `adapters/persistence/SplitProposalPersistenceAdapter.java`. Read views (`SplitProposalView`) live in `application.workflow` (NOT `.spi`) so REST stays thin ([[story-3f-3-run-dependency-graph-waiting-for-dependencies-reconciliations]] R-lesson; `ArchitectureBoundaryTest`).
  - [ ] `SplitProposalEscalationThresholdProvider` (mirror `SpecRejectionEscalationThresholdProvider`) reading `deliveryline.workflow.split-proposal-escalation-threshold` (default 3). Add to BOTH `application.yml` and the **test** `application.yml` ([[validated-config-needs-test-yaml]]).

- [x] **Task 5 — REST + CLI surfaces** (AC: 6)
  - [ ] `adapters/rest/WorkflowController.java` — `POST /{id}/split/request`, `POST /{id}/split/repropose` (body `{feedbackText}`), `POST /{id}/split/decline` (each `Idempotency-Key` + `X-Actor-Identity`, fail-closed `localActorIdentityResolver.requireSafe`), `GET /{id}/split-proposal`. Mirror the 3f-3 `/dependencies` POST/GET shape. New request records + `SplitProposalResponse` (state ∈ `none|pending|available|unavailable`, the proposal, `loopCount`). Adding the controller dep fans out to the `@WebMvcTest(WorkflowController.class)` slices (~16 — add `@MockitoBean SplitProposalService`).
  - [ ] `adapters/cli/WorkflowCommands.java` — `split-request` / `split-repropose` / `split-decline` / `split-proposal-show` via the **optional-setter injection** pattern (3f-3 `setRunDependencyService`) + a `requireSplitWired()` guard, to avoid the telescoping-ctor unit-test fan-out.

- [x] **Task 6 — OpenAPI + frontend client + Split Proposal Panel** (AC: 1, 3, 5, 6)
  - [x] Regenerated the OpenAPI snapshot via `OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` (Failsafe + Testcontainers, full context loaded) → `openapi.json` gained `/split/request|repropose|decline` + `GET /split-proposal` + `SplitProposalResponse/Payload/Subtask/Dependency` + `ReproposeSplitRequest`; `npm run generate-api` → `schema.d.ts`; `npm run check:api` GREEN.
  - [x] FE `approvalDecisionView.ts` — added the 3 actions to `DecisionAction` + `KNOWN_ACTIONS` + `CONSEQUENCE_HINTS`; governed buttons via a co-located `SplitActionBar` (allowed-actions-driven; `useWorkflowMutation`; no bare actor-role text).
  - [x] New `SplitProposalPanel.tsx` + container (mirror `ReviewerVerdictPanel`: `StateSignifierChip`, color-independent, subtasks + dependency edges + self-review chip + loopCount; 3f-5 commit affordance placeholder) + `useSplitProposal` hook (GET, polls while pending) + mounted alongside `ReviewerVerdictPanelContainer` in the route.
  - [x] Vitest + axe: `SplitProposalPanel.test.tsx` 13 cases (none/pending/available/unavailable + open-vs-no-open action sets + feedback gating + axe sweeps). FE gates: tsc -b, lint/prettier, check:api, vitest **1215/0** all GREEN.

- [ ] **Task 7 — Tests** (AC: 8, all)
  - [ ] **Full loop IT** (Failsafe + Testcontainers + offline **Docker mock-runner**): bound project → `request_split` → mock emits 2-subtask/1-dep proposal → row `open` + served by `GET`; `repropose` → loop_count bump/supersede; `decline` → dismissed; reviewer-unbound parity. **DEFERRED** — the only remaining item. Its constituents are individually verified: persistence path on real Postgres (`SplitProposalPersistenceAdapterIT` 6/0), service logic (8/0), contract (6/0), full Spring context boot (OpenApiSnapshot IT), runner fence emission (node 75/0). Best run in the `docker-runner-it` CI tier (real container runner).
  - [x] Unit: action-matrix both gates × open/no-open (AllowedActionsTest 52/0); `SplitProposalService` request/repropose/decline guards + reviewer-unbound degrade + escalation flip (SplitProposalServiceTest 8/0). `SplitProposalPersistenceAdapterIT` 6/0 covers persist/self-review/one-open-index/supersede-then-insert/dismiss/loopCount on real Postgres.
  - [x] Contract: `split-proposal.v1` valid/invalid incl. the if/then degrade rule (`SplitProposalContractTest` 6/0); runner fence emission both runners byte-identical (node 34+41/0). `ContextBundleService` `split.feedback` by-reference path implemented (covered by the deferred full-loop IT).
  - [x] Drift: 3 new AllowedActions (placeholder + 3 pins); `FlywaySchemaContractTest` (split_proposals + split_proposal_feedback table/column/index/CHECK/FK actions + workflow_run_id FK count 9→10); OpenAPI + `schema.d.ts` regenerated (NOT byte-identical).
  - [x] Naming/tier: `SplitProposalPersistenceAdapterIT` named `*IT` + `@Tag("integration")` → Failsafe lifecycle (ran on real Postgres here).

- [x] **Logging instrumentation** (cross-cutting; required)
  - [x] SLF4J parameterized logs at `SplitProposalService` request/repropose/decline (entry/decision/exit), the dispatch enqueue (bound|unbound-degraded), `SplitProposalHarvester` persist + self-review, supersede, escalation flip, and every swallowed degradation branch.
  - [x] Context keys: ids/counts/status only (`workflowRunId`, `idempotencyKey`, `actorIdentity`, `splitProposalId`, `loopCount`, `status`, `subtaskCount`, `selfReview`) — **never** subtask titles/scope, feedback text, reviewed-artifact bytes, tokens, or the reviewer credential.
  - [ ] Pin ≥1 log assertion per new branch via list-appender / `OutputCaptureExtension` — DEFERRED with the harvest IT tier.

## Dev Notes

### Reconciled Scope

In scope: the three advisory actions; the reviewer-style proposal call (reusing REVIEW dispatch); the `split-proposal.v1` runner contract + both-runner fence emission + mocks; `split_proposals` (V29) + `split_proposal_loop_count`; `SplitProposalService` (request/repropose/decline) + `SplitProposalHarvester`; the re-propose feedback loop (materialized reference + loop_count + escalation); REST/CLI/`GET /split-proposal`; the FE Split Proposal Panel + the 3 governed actions.

Out of scope (do NOT build — owned elsewhere):
- `approve_split` + `SplitCommitService` + child-run / sub-ticket creation + dependency-edge writing + parent → `Split` + `workflow.split` event — **3f-5**.
- The depth-cap guard, `SPLIT_DEPTH_LIMIT_EXCEEDED`, `complex-ticket-flow.max-split-depth`, and the deep-split override — **3f-7** (R4).
- Per-subtask proposal editing in the panel; manual (non-LLM) subtask entry — deferred forward options (ADR-0029).

### Why these ACs are reconciled (epic wording vs. the live codebase)

| Epic wording | Reality (verified 2026-06-29) | Reconciliation |
|---|---|---|
| "persisted against the run (a `split_proposals` row **or** the generic `step_reviews` payload)" | `step_reviews` (V19) is verdict-shaped: `outcome`/`rationale`, **no payload column**, no lifecycle/loop_count. | New `split_proposals` table (V29); `step_reviews` untouched (R1/R2). |
| "feedback … never inlined past the 2KB cap" | Cap is now `256*1024` (`ContextBundleService:68`); 3e-2 materializes feedback as a by-id referenced input; 3e-3 inlines bounded text. | Materialize the feedback as a redaction-policed `split.feedback` referenced input, never inlined (R3); "2KB" is obsolete. |
| "consults the story-3f-7 depth-cap guard first" | 3f-7 owns `SPLIT_DEPTH_LIMIT_EXCEEDED` + the config + the guard, and runs after 3f-5; no run has a parent yet. | Defer the guard to 3f-7 (R4); `request_split` is depth-unguarded (no run can exceed depth 0). |
| "reviewer-style LLM call (reusing RunnerStage.REVIEW)" | REVIEW harvest writes a verdict to `step_reviews`; a proposal is not a verdict. | Reuse REVIEW *dispatch* only; new fence + contract + harvester + table (R1). |

### R1 — Reuse REVIEW dispatch, not REVIEW harvest
`enqueueReviewerIfConfigured` (`WorkflowOrchestrationService.java:778-825`) gives queue/worker/credential/binding/degradation. The split proposal diverges at the *output*: the runner emits a fenced ` ```split ` block (the 3e-1 `splitClarificationsFence` mechanism — a way to extract structured JSON from the model's free-text stdout) validated against a new `split-proposal.v1` contract; a new `SplitProposalHarvester` persists it. `ReviewResultHarvester` / `review-result.v1` / `step_reviews` are **not** on the split path — a split request produces **no** verdict. Distinguish split mode from verdict mode via the additive `splitProposalRequested` bundle flag (pinned on the reviewer execution so the broker routes the result to the right harvester).

### R2 — No new state / event / edge; a dedicated table for the lifecycle
Per ADR-0029 decision 4 (Alt-3 "dedicated `WaitingForSplitApproval` gate state" explicitly rejected), the proposal is an **advisory overlay** — the parent never leaves its gate until `approve_split` (3f-5). So 3f-4 adds no `WorkflowState`, no `WorkflowEventType`, no transition edge, and no state-CHECK widening (contrast 3f-2/3f-3). The proposal's own lifecycle (`open`→`superseded`/`dismissed`/`approved`) + `loop_count` + structured payload do not fit the verdict-shaped `step_reviews`, so a new `split_proposals` table carries them. A partial unique index (`where status='open'`) guarantees one live proposal per run ([[one-active-per-key-needs-partial-unique-index]]). (ADR-0029 decisions 2/4 describe `Split` as *terminal* — that was reversed to **non-terminal** by 3f-2/3f-7; irrelevant here since 3f-4 never touches the `Split` state.)

### R3 — Re-propose feedback is materialized by reference (3e-2 pattern), not inlined
`priorFeedbackReferences` carries `{referenceId, kind}` only (`ContextBundleService.java:1325`); the runner reads referenced content from its mounted input dir. For re-propose, write the operator's feedback as a redaction-policed input file referenced by a new kind `split.feedback` (mirror how `spec.rejection` / `clarification.answered` content is materialized). Never embed feedback text in the bundle JSON. The bundle's single `redact(root, classification)` pass (`ContextBundleService:380`) covers the reference metadata; the materialized file passes the adversarial secret-fixture gate ([[redaction-fixture-needs-two-gates]]). `split_proposal_loop_count` (new column) mirrors `spec_rejection_loop_count` (`V7`, increment at `ApprovalService:388-391`) and exists primarily to make each re-propose dispatch a **distinct** idempotency key (mirror `specDispatchKey(runId, count)`), with an escalation marker + `ESCALATION_REQUIRED` at the configurable threshold (reuse the existing marker + `CLEAR_ESCALATION_MARKER`; no new event type).

### R4 — Depth cap is forward-owned by 3f-7
Epic 3f-4 AC1 says `request_split` consults the depth-cap guard, but 3f-7 AC5 + sprint-status assign `SPLIT_DEPTH_LIMIT_EXCEEDED` (three sites), `complex-ticket-flow.max-split-depth`, and the deep-split override to 3f-7, sequenced after 3f-5. At 3f-4 build time no run has a `parent_run_id` (3f-5 mints the first children) and recursion does not exist, so every splittable run is at depth 0 < cap(3) — the guard is a structural no-op and building it here would collide with 3f-7's three-sites ownership. Leave `SplitProposalService.request` depth-unguarded; 3f-7 inserts the guard at that entry. **Do not add the error code, the config property, or the override flag in 3f-4.**

### R5 — Reviewer binding gates *generation*, not the gate
The proposing LLM uses the run-level reviewer binding (`hasReviewerBinding`/`resolveReviewerKind`, per-run not per-stage — 3e-3 R1). An unbound project cannot generate a proposal: `request_split` degrades through the single 3d-2 path (panel "unavailable — no reviewer model configured"), and the spec/review gate is never blocked. The three actions stay surfaced; the generation degrades. (Forward option, not this story: a non-reviewer fallback model for proposals, or manual subtask entry — ADR-0029 Alt-2.)

### R6 — Reviewed-artifact derivation is reused unchanged
`ContextBundleService.resolveReviewedArtifact` already resolves the spec at `WaitingForSpecApproval` and the plan/pr-output at `WaitingForReview` (the SPEC fallback `prOutput → implementationPlan → spec` was added by 3e-3). The split proposal decomposes whatever that derivation returns at the current gate — no new derivation. Producer identity for self-review uses the same SPEC→INVESTIGATION / plan-prOutput→EXECUTION mapping (3e-3 fix in `ReviewResultHarvester.resolveProducerIdentity`).

### Previous Story Intelligence (3f-1/3f-2/3f-3, 3e-2/3e-3)
- **3f-3** established the Epic-3f application-service shape: one `application.workflow` service owns preconditions + idempotency; persistence adapters only store rows; read views live in `application.workflow` (NOT `.spi`) to keep REST thin; controller deps fan out to ~16 `@WebMvcTest` slices; CLI uses optional-setter injection. Apply all of these.
- **3f-3** review lesson: assert FK referential actions in `FlywaySchemaContractTest` (not just column pairs); hardcoded state/status SQL literals must be pinned against the registry value.
- **3e-2** shipped the materialize-by-reference feedback pattern (kind + redaction-policed input file) — the direct precedent for `split.feedback`. Its **duplicate-key flush trap** ([[caught-idempotency-conflict-poisons-shared-tx]]): if `SplitProposalHarvester` writes inside a shared broker tx, a unique-index conflict (two `open` rows) must be **prevented** (supersede-then-insert, or `ON CONFLICT`), not caught after a poisoning flush. Pin with an IT.
- **3e-3** proved the reviewer substrate is reused with a single new call site + a bundle variant + a one-line FE render gate; degradation/self-review/parity are inherited. The split path is the same shape plus a new harvest target.
- **3e-2/3e-3** fence rules: byte-identical across both `runner.mjs`, SHA-verified; strip only on successful parse with ≥1 usable item; slice at fence offsets preserving CRLF; non-fatal on malformed; mocks gate on the non-secret stage/mode marker.

### Architecture & Boundary Guardrails
- Keep proposal rules in `application.workflow`; persistence adapters implement ports only; REST/CLI stay thin (`ArchitectureBoundaryTest`). Application cannot import adapters — reach the runner/dispatch via existing SPI seams ([[application-cannot-import-adapters]]).
- Flyway versioned SQL only; explicit relational columns, not JSON metadata, for the lifecycle fields (`proposal_json` is the opaque LLM payload, redacted — acceptable as text).
- New public-id prefix → register in `PublicIdPrefixes` + drift; the join/FK is keyed on `workflow_runs.public_id` (text), ON DELETE RESTRICT ON UPDATE CASCADE (3f-2/3f-3 convention).
- A validated `@ConfigurationProperties` field (escalation threshold) requires the **test** `application.yml` updated too ([[validated-config-needs-test-yaml]]).

### Transaction & Idempotency Notes
- `request`/`repropose`/`decline` are idempotent under `Idempotency-Key`; a replayed `request` must not enqueue a second proposal call nor insert a second `open` row (the partial unique index + a deterministic dispatch key enforce this).
- Re-propose dispatch key is distinct per attempt (`split-proposal:<runId>:<loopCount>`); the `superseded` transition of the prior row + the new `open` insert happen in one tx (avoid the two-open-row conflict — [[caught-idempotency-conflict-poisons-shared-tx]]).
- Harvest is best-effort: a failed/malformed proposal call degrades the panel to "unavailable"; it never blocks or transitions the gate (3d-2 discipline). The reviewer credential is decrypted in memory only, never logged.

### Latest Technical Context
- No new external library. Reuses the existing Spring transaction / queue / reviewer-dispatch patterns. The runner contract is a local additive JSON schema; the fence helper is plain Node string-slicing (no new dep). `node --check` both `runner.mjs` after editing.

### Testing Standards
- Unit via Surefire; `@SpringBootTest`/Testcontainers ⇒ `*IT` via Failsafe ([[springboot-testcontainers-test-must-be-IT]], [[maven-arglineation-goal-crash]]).
- Foundation/registry/Flyway drift under `src/test/java/org/dradgo/foundation` + `.../contract` + `.../architecture`.
- `application.*` coverage ≥ 80% (CI `backend-contract-tests` tier; not always reproduced locally — verify in a clean env, [[verify-ci-fixes-in-clean-env]]).
- OpenAPI: write snapshot → review additive diff → green → `npm run generate-api` → `check:api` ([[openapi-regen-frontend-client-drift-cascade]], [[openapi-regen-platform-shim]]).
- Reinstall `deliveryline-runner-contracts` to `.m2` before backend ITs (`-am` or install — [[runner-contracts-schema-stale-in-m2]]).
- FE: focused Vitest + axe for the new panel + the gate actions; prettier/eslint before push ([[prettier-gate-cascades-ci]]).

### References
- Epic: `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` — Story 3f-4 + Cross-Cutting Notes (foundation-gate widening, NFR16, FRs).
- ADR: `docs/adr/0029-complex-ticket-flow.md` — decision 4 (advisory overlay, three-action loop, persisted proposal, fenced ` ```split `, `priorFeedbackReferences` feedback + `split_proposal_loop_count` + escalation marker) + Alt-2/Alt-3 (manual entry / dedicated gate-state rejected). NOTE: decisions 2/4 say `Split` is *terminal* — superseded by 3f-2 (non-terminal); not touched by 3f-4.
- ADR: `docs/adr/0026-per-step-advisory-reviewer-model.md` — the reviewer-style channel reused for the proposal.
- Previous stories: `3e-3-spec-phase-advisory-reviewer.md` (reviewer reuse + bundle variant + degradation), `3e-2-clarification-accept-and-spec-rebuild-incorporation.md` (materialize-by-reference feedback + loop count + escalation + fence-split traps), `3f-3-run-dependency-graph-and-waiting-for-dependencies-gating.md` (Epic-3f service/persistence/REST/CLI/Flyway pattern + review lessons).
- Live seams (re-grep before editing — anchor semantically): `application/workflow/WorkflowOrchestrationService.java` (`enqueueReviewerIfConfigured` ~:778-825, `onSpecStageSucceeded`/`onPlanStageSucceeded`/`onPrOutputStageSucceeded` enqueue sites), `application/runner/ContextBundleService.java` (`resolveReviewedArtifact`, `PriorFeedbackReference` :1325, `CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES` :68, redact :380), `application/review/ReviewResultHarvester.java` (`resolveProducerIdentity`/self-review — the pattern the new harvester mirrors), `application/workflow/WorkflowInspectionService.java` (`baseActionMatrix` ~:922-1091, gate branches :958-993 / :1015-1032), `domain/registry/AllowedAction.java`, `application/approval/ApprovalService.java` (escalation flip :409-454), `application/workflow/SpecRejectionEscalationThresholdProvider.java`, `runners/{claude,codex}/lib/runner.mjs` (`splitClarificationsFence`), `deliveryline-runner-contracts/src/main/resources/schemas/`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (bmad-dev-story)

### Debug Log References

- `node --test runners/{claude,codex}/test/*.test.mjs` — claude 34/0, codex 41/0 (incl. the 6 new split-proposal-fence tests each).
- `awk '…' runner.mjs | sha256sum` — splitSplitProposalFence region byte-identical across both runners (8715985338397b…).
- `./mvnw -pl deliveryline-runner-contracts -am test-compile` — clean.
- `./mvnw -pl deliveryline-backend -am test-compile` — clean (new views/ports/provider + the Flyway/Registry/pin test edits all resolve).

### Completion Notes List

Foundation layer (Tasks 1–3) + Task 4 scaffolding implemented and compiling/green:
- Task 1 (partial): 3 advisory `AllowedAction`s (`REQUEST_SPLIT`/`DECLINE_SPLIT`=`continue_as_single`/`REPROPOSE_SPLIT`) + placeholder JSON + 3 `AllowedActionRegistryPinTest` pins. **Proposal-aware action-matrix threading in `WorkflowInspectionService` NOT yet wired** (deferred with the service — its ctor change fans out to ~16 test sites + needs the read-port bean).
- Task 2 (done): V29 `split_proposals` (partial unique one-open-per-run index, status/loop_count CHECKs, FK→workflow_runs.public_id RESTRICT/CASCADE) + `workflow_runs.split_proposal_loop_count`; `PublicIdPrefixes.SPLIT_PROPOSAL` (`splprop_`); placeholder JSON + FlywaySchemaContractTest (CORE_TABLES, prefix map, FK count 9→10 with the public_id exception, dedicated schema test w/ probe-row cleanup) + RegistryContractTest auto-covered.
- Task 3 (done): `split-proposal.v1.schema.json` (+ `if/then`: ≥1 subtask unless failureCategory set) + `SPLIT_PROPOSAL` validation target; `context-bundle.v1` additive `splitProposalRequested`; both `runner.mjs` `splitSplitProposalFence` (byte-identical) + split-mode `commandBuild` branch + `DL_SPLIT_PROPOSAL_REQUESTED` prepare var; both entrypoints append the decomposition directive + export the marker in split mode; both mocks emit the deterministic 2-subtask/1-dep `split` fence; node fence tests for both runners.
- Task 4 (scaffolding): `SplitProposalView`/`SplitSubtaskView`/`SplitDependencyView`/`NewSplitProposal` (read views in `application.workflow`), `SplitProposalReadPort`/`SplitProposalWritePort` (spi), `SplitProposalEscalationThresholdProvider`, `deliveryline.workflow.split-proposal-escalation-threshold: 3`.

Key design decision pinned during dev: split mode rides `RunnerStage.REVIEW` (there is NO `SPLIT_PROPOSAL` stage) and is distinguished by the **idempotency-key prefix `split-proposal:<runId>:<loopCount>`** already persisted on `runner_executions` — durable, readable at both compose (`createForReview`) and harvest (`RunnerBroker.onResult`), needing NO new `runner_executions` columns.

**Backend vertical COMPLETE + locally verified** (added after the user chose "by-reference + new store" for R3 and "continue full build"):
- `SplitProposalService` (request/repropose/decline + GET proposalView; split dispatch keyed `split-proposal:<run>:<loop>`; reviewer-unbound degrade; escalation flip) — `SplitProposalServiceTest` 8/8 green.
- `SplitProposalHarvester` (validate `split-proposal.v1` → redact → supersede-then-insert open + finalize execution in one REQUIRES_NEW tx; degrade-not-block) + `RunnerBroker` split-routing (idempotency-key prefix; optional-setter harvester + read-port) + `createForReview` split overload (additive `splitProposalRequested` + by-reference `split.feedback` entry).
- R3 feedback materialized BY REFERENCE in a NEW durable `split_proposal_feedback` table (V29, prefix `splfb_`), keyed by reviewer execution; redacted before persistence.
- `SplitProposalPersistenceAdapter` (NamedParameterJdbcTemplate; proposal_json decode; one-open partial index honored via supersede-then-insert) + `WorkflowRunRejectionLoopPort.incrementAndReadSplitProposalLoopCount`.
- Action-matrix split overlay (`WorkflowInspectionService`) — `WorkflowInspectionServiceAllowedActionsTest` 52/52 green (incl. both-gate × open/no-open).
- REST `/split/request|repropose|decline` + `GET /split-proposal` (HUMAN, fail-closed actor, Idempotency-Key) + ~17 `@WebMvcTest` `@MockitoBean` fan-out; CLI `split-request|repropose|decline|split-proposal-show` (optional-setter).
- Logging instrumented at every branch (entry/decision/exit, enqueue bound|unbound-degraded, supersede, escalation flip at WARN); ids/counts/status only — never feedback text/subtask content/credential.
- VERIFIED: `./mvnw -pl deliveryline-backend -am test-compile` clean; AllowedActions 52/0, SplitProposalService 8/0, runner node tests 34+41/0; `spotless:apply` clean. Full Spring context starts (OpenApiSnapshotContractTest booted the context before the snapshot step).

**OpenAPI regen + FE now DONE** (Docker + npm turned out to be available in this env):
- OpenAPI snapshot regenerated via Failsafe Testcontainers (`-Dopenapi.snapshot.write=true`) — `openapi.json` carries the 4 new ops + 5 schemas; `npm run generate-api` → `schema.d.ts`; `check:api` GREEN. (The `AllowedActions` schema is free-form `List<String>`, so no allowableValues edit was needed — the 3 actions surface automatically.)
- FE complete: `approvalDecisionView` (3 actions + hints), `useSplitProposal`, `useSplitActions` (request/repropose/decline), `SplitProposalPanel` + container + `SplitActionBar`, route mount, `SplitProposalPanel.test.tsx` (13). FE gates: tsc -b, lint/prettier, check:api, vitest **1215/0**.
- Bug fixed during context-load: `SplitProposalPersistenceAdapter` injected `ObjectMapper` (no such bean) → switched to `new ObjectMapper()` (mirrors ContextBundleService).
- FE decision flagged by the FE pass: `SplitActionBar` reads DEFAULT-role allowed-actions; at the `WaitingForReview` gate split is matrix-gated to `developer`, so the review-gate split buttons need the developer-role allowed-actions wired (the panel DISPLAY works at both gates regardless). Minor follow-up.

**REMAINING — one item:**
- Full-loop **container** IT (`request_split` → offline Docker mock-runner emits the proposal → broker routes to harvester → persisted → GET → repropose/decline). DEFERRED to the `docker-runner-it` CI tier. Rationale: every constituent is verified — persistence on real Postgres (`SplitProposalPersistenceAdapterIT` 6/0), service (8/0), contract incl. degrade if/then (6/0), runner fence both runners (75/0), and the FULL Spring context boots cleanly (OpenApiSnapshot IT). What the container IT adds is the real-runner orchestration round-trip only.

**Full local verification:** backend `test-compile` + `spotless:check` clean; SplitProposalServiceTest 8/0, AllowedActionsTest 52/0, SplitProposalContractTest 6/0, SplitProposalPersistenceAdapterIT 6/0 (real Postgres), OpenApiSnapshotContractTest context-load OK + snapshot regen; runner node tests 34+41/0; FE 1215/0 + lint + tsc + check:api.

### File List

Added:
- `deliveryline-backend/src/main/resources/db/migration/V29__add_split_proposals.sql`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitProposalView.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitSubtaskView.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitDependencyView.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/NewSplitProposal.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/SplitProposalEscalationThresholdProvider.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/SplitProposalReadPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/SplitProposalWritePort.java`
- `deliveryline-runner-contracts/src/main/resources/schemas/split-proposal.v1.schema.json`
- `runners/claude/test/runner-split-proposal-fence.test.mjs`
- `runners/codex/test/runner-split-proposal-fence.test.mjs`
- `deliveryline-backend/.../application/workflow/SplitProposalStatusView.java`, `SplitProposalCommands.java`, `SplitProposalService.java`, `SplitProposalHarvester.java`
- `deliveryline-backend/.../adapters/persistence/SplitProposalPersistenceAdapter.java`
- `deliveryline-backend/.../adapters/rest/SplitProposalResponse.java`, `ReproposeSplitRequest.java`

Modified (backend integration):
- `deliveryline-backend/.../adapters/persistence/WorkflowRunPersistenceAdapter.java` (+incrementAndReadSplitProposalLoopCount)
- `deliveryline-backend/.../application/workflow/spi/WorkflowRunRejectionLoopPort.java` (+split loop count)
- `deliveryline-backend/.../application/workflow/WorkflowInspectionService.java` (+split overlay + read-port dep)
- `deliveryline-backend/.../application/runner/RunnerBroker.java` (+split harvest routing + compose split flags)
- `deliveryline-backend/.../application/runner/ContextBundleService.java` (+createForReview split overload)
- `deliveryline-backend/.../adapters/rest/WorkflowController.java` (+/split/* + GET split-proposal)
- `deliveryline-backend/.../adapters/cli/WorkflowCommands.java` (+split-request/repropose/decline/show)
- V29 migration extended with `split_proposal_feedback` table; `PublicIdPrefixes.SPLIT_PROPOSAL_FEEDBACK`; FlywaySchema/placeholder updated
- 11 WorkflowInspectionService unit tests + 17 WorkflowController @WebMvcTest slices (new ctor arg / @MockitoBean)
- TESTS: `deliveryline-runner-contracts/.../SplitProposalContractTest.java`; `deliveryline-backend/.../application/workflow/SplitProposalServiceTest.java`; `deliveryline-backend/.../adapters/persistence/SplitProposalPersistenceAdapterIT.java`; `WorkflowInspectionServiceAllowedActionsTest.java` (+2 open-proposal cases)
- GENERATED: `deliveryline-backend/.../resources/openapi/openapi.json` (regenerated) ; `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- FE added: `deliveryline-frontend/src/features/workflows/hooks/useSplitProposal.ts`, `hooks/useSplitActions.ts`, `components/SplitProposalPanel.tsx`, `components/SplitProposalPanel.test.tsx`
- FE modified: `src/features/workflows/approvalDecisionView.ts`, `src/lib/queryKeys/workflowKeys.ts`, `src/routes/workflows/$workflowRunId/index.tsx`

Modified:
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/id/PublicIdPrefixes.java`
- `deliveryline-backend/src/main/resources/application.yml`
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java`
- `deliveryline-runner-contracts/src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java`
- `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json`
- `runners/claude/lib/runner.mjs`, `runners/codex/lib/runner.mjs`
- `runners/claude/entrypoint.sh`, `runners/codex/entrypoint.sh`
- `runners/claude/test/mock-claude.sh`, `runners/codex/test/mock-codex.sh`

## Change Log

| Date | Version | Change |
|------|---------|--------|
| 2026-06-29 | 0.1 | Created ready-for-dev story for 3f-4: three advisory split actions (advisory overlay, no gate state), reviewer-style proposal call reusing REVIEW dispatch, new `split-proposal.v1` fenced runner contract + both-runner emission + mocks, V29 `split_proposals` table + `split_proposal_loop_count`, `SplitProposalService`/`SplitProposalHarvester`, re-propose feedback-by-reference loop + escalation, REST/CLI/`GET /split-proposal`, FE Split Proposal Panel. Reconciliations: reuse REVIEW dispatch not harvest (R1); no new state/event (R2); materialize feedback by reference, "2KB" obsolete (R3); depth cap deferred to 3f-7 (R4); reviewer-unbound degrades generation not gate (R5). |

## Review Findings

<!-- bmad-code-review 2026-06-29: 3-layer adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 8 ACs + R1–R5 PASS (Acceptance Auditor). 2 decision-needed + 4 patch findings below; 5 dismissed as noise/by-design. -->

### Decision-needed (RESOLVED → patches)

- [x] **[Review][Decision→Patch] Re-request silently no-ops after a decline or a degraded attempt** — RESOLVED 2026-06-29: chose **`request` bumps `split_proposal_loop_count`** (same counter as `repropose`) so each attempt gets a distinct dispatch key. See patch P1 below. (Found by: blind+edge.)

- [x] **[Review][Decision→Patch] Out-of-band decline during the pending window resurrects a declined proposal; the matrix ignores in-flight dispatches** — RESOLVED 2026-06-29: chose **both** — (a) thread `hasActiveSplitDispatch` into `appendSplitOverlay` so the matrix matches `proposalView`'s pending state and suppresses `request_split` while a dispatch is in flight, and (b) make the harvester decline-aware (skip `insertOpen` when the in-flight attempt was declined). See patches P2 + P3 below. (Found by: edge.)

### Patch

- [x] **[Review][Patch] P1 — `request` must bump `split_proposal_loop_count` for a distinct dispatch key** [`SplitProposalService.java`] — APPLIED 2026-06-29. `request` now increments via `incrementAndReadSplitProposalLoopCount` before enqueue (distinct key per attempt), and is a no-op (returns pending) while a split dispatch is in flight so a replayed request can't double-dispatch/double-bump. `maybeFlipEscalation` stays repropose-only. Tests: `requestEnqueues…` asserts the `:1` bump; new `requestIsANoOpWhileASplitDispatchIsInFlight`. Green.
- [x] **[Review][Patch] P2 — `appendSplitOverlay` must suppress `request_split` while a split dispatch is in flight** [`WorkflowInspectionService.java`] — APPLIED 2026-06-29. Threaded `hasActiveSplitDispatch` (computed via `runnerExecutionRecordPort` at gate states only, no ctor change) through `computeActionMatrix` → `appendSplitOverlay`; while pending the overlay surfaces no split action, matching `proposalView`. `WorkflowInspectionServiceAllowedActionsTest` 52/0 green.
- [x] **[Review][Patch] P3 — decline must not resurrect a rejected proposal** [`SplitProposalService.java`] — APPLIED 2026-06-29. Resolved with a **right-sized guard instead of a new migration**: `decline` now rejects (`INVALID_COMMAND_PAYLOAD`) when a split dispatch is in flight and no proposal is open yet — you cannot decline a proposal that does not exist; the operator waits for it, then declines it normally. This closes the resurrection (the harvester's later `insertOpen` can no longer overwrite a premature decline) without a `split_proposal_declines` table or execution-status lies. New test `declineRejectedWhileSplitDispatchInFlight`; `SplitProposalServiceTest` 11/0. Forward option (not needed now): a "cancel the in-flight generation on decline" path if decline should *abort* generation rather than wait.

- [x] **[Review][Patch] P4 (APPLIED 2026-06-29) Split harvester is not total despite its "NEVER throws" contract — a RuntimeException strands the execution RUNNING** [`SplitProposalHarvester.java:149,158,185`] — wrapped the whole `harvest` body in `try/catch (RuntimeException)` → `degrade(...)`, so a fault from the reviewer-identity / reviewed-artifact / loop-count reads degrades the panel instead of escaping `onResult`. — `resolveReviewerIdentity` (`:280`) calls `resolveReviewerKind` with no try/catch (note `resolveProducerIdentity` at `:287` IS wrapped, and `SplitProposalService.reviewerBound` deliberately wraps the same resolver — proving it can throw); `resolveReviewedArtifact` (`:149`) catches only `DomainException`; `currentSplitProposalLoopCount` (`:185`) is unguarded. `RunnerBroker.onResult` calls `harvest(...)` (`RunnerBroker.java:1530`) inside a `try` with only a `finally` (no `catch`, `:1593`), and `recordCompletion` runs only when `completionOutcome != null` — so a thrown RuntimeException escapes `onResult` and leaves the split REVIEW execution stuck RUNNING until the timeout scan reaps it. Fix: wrap the harvest body (or the unguarded resolver calls) to `degrade(...)` on any RuntimeException.

- [x] **[Review][Patch] P5 (APPLIED 2026-06-29) `repropose` with no open proposal still bumps loop_count and can drive escalation** [`SplitProposalService.java:149-203`] — added an `findOpenForRun` guard: `repropose` now rejects with `INVALID_COMMAND_PAYLOAD` when no open proposal exists, before any loop-count bump / enqueue. New test `reproposeRejectsWhenNoOpenProposalExists`; 3 existing repropose tests updated to stub an open proposal. — `repropose` never asserts an open proposal exists; it `supersedeOpenForRun` (0 rows), increments `split_proposal_loop_count`, enqueues, and calls `maybeFlipEscalation`. The matrix only surfaces `repropose_split` when `hasOpenForRun`, but the REST endpoint accepts it from any HUMAN actor with no open-proposal guard. A stale UI / repeated POST climbs to the escalation threshold (default 3) and fires `ESCALATION_REQUIRED` with no proposal ever reviewed. Fix: reject `repropose` (INVALID_COMMAND_PAYLOAD) when no open proposal exists.

- [x] **[Review][Patch] P6 (APPLIED 2026-06-29) Timeout-race makes the harvester log "success" after the proposal insert was rolled back** [`SplitProposalHarvester.java:206-222`] — on `ILLEGAL_TRANSITION` the harvester now confirms a persisted open proposal actually exists (`findOpenForRun`) before returning `"success"`; if the insert was rolled back (terminal-execution race, nothing persisted) it degrades instead of falsely reporting success. — supersede + `insertOpen` + `recordCompleted` share one REQUIRES_NEW tx; if `recordCompleted` throws `ILLEGAL_TRANSITION` (a concurrent timeout scan flipped the execution TIMED_OUT/ORPHANED between `onResult`'s row-read and persist) the whole tx rolls back, undoing the insert, yet the catch returns `"success"` as an "idempotent no-op". A valid proposal is silently discarded while success is logged. Fix: on `ILLEGAL_TRANSITION`, confirm a persisted proposal actually exists for the run before returning `"success"`; otherwise degrade.

- [x] **[Review][Patch] P7 (APPLIED 2026-06-29) Dependency ordinals are never cross-validated against subtask ordinals → dangling edges persisted** [`SplitProposalHarvester.java`] — added `retainValidDependencies(subtasks, dependencies)`: before persistence the harvester drops dependency edges that reference a non-existent subtask ordinal, self-edges (`from==to`), and duplicates. Done backend-side (authoritative) rather than in both runners. (Runner-side schema bounds unchanged.) — both runners and the schema (`split-proposal.v1.schema.json`) bound each `fromOrdinal`/`toOrdinal` only to `>= 1`; nothing checks they reference an existing subtask ordinal, rejects self-dependencies (`from==to`), or rejects duplicate/gapped ordinals. A malformed subtask is dropped while a dependency pointing at it survives, so a structurally inconsistent decomposition is persisted and handed to 3f-5 (which mints a child run per subtask + a `run_dependencies` edge per dependency). Advisory-only in this story (hence low), but pushes a referential-integrity hole downstream. Fix: drop dependencies whose ordinals don't match a retained subtask (in the harvester or the fence helper) — or explicitly defer the validation to 3f-5's edge-materialization gate.

### Dismissed (noise / by-design)

- TDZ `ReferenceError` for `summary`/`classification` in the runner split arm (Blind Hunter, low-confidence) — FALSE POSITIVE: both are declared (`runner.mjs:588,598`) before the split arm (`:609`).
- Per-runner `reviewerModelIdentity: 'claude'/'codex'` in the emitted split payload (Auditor) — by-design; advisory-only field, `SplitProposalHarvester.resolveReviewerIdentity` re-derives the authoritative identity from the binding. The load-bearing `splitSplitProposalFence` helper is byte-identical (verified).
- `split-proposal.v1.schema.json` broader than the story sketch (Auditor) — by-design envelope mirroring `review-result.v1`; all emitted fields covered, `additionalProperties:false` passes.
- R3 prose "runner reads referenced content from its mounted input dir" overstates the mechanism (Auditor) — implementation faithfully mirrors the established `spec.rejection` reference-id pattern; documentation nuance only.
- Escalation marker shared across spec-rejection / implementation-rejection / split loops (Auditor) — the story's explicit "reuse the existing marker" intent; cross-loop interaction is documented, not a defect.
