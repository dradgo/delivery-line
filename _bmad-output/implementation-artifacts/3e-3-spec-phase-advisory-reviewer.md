# Story 3e.3: Spec-Phase Advisory Reviewer (WaitingForSpecApproval)

Status: done

<!-- Added to Epic 3e via sprint-change-proposal-2026-06-23.md (correct-course addendum). Depends on 3d-2 (reviewer substrate) + 3e-1 (open clarifications as review context). Validation optional: run validate-create-story before dev-story. -->

> **⚠️ READ FIRST — this is an EXTENSION of the 3d-2 advisory reviewer, not a new reviewer subsystem.** Story 3d-2 built the entire advisory-LLM-reviewer substrate — `RunnerStage.REVIEW`, the `step_reviews` verdict table, `resolveReviewerKind`/`hasReviewerBinding`, `enqueueReviewerIfConfigured`, the `review-result.v1` schema, the `GET /reviewer-verdict` endpoint, and the Reviewer Verdict Panel — but **only fires it at the execution-output phase (`WaitingForReview`).** `enqueueReviewerIfConfigured(...)` is called from `onPlanStageSucceeded` (`WorkflowOrchestrationService.java:751`) and `onPrOutputStageSucceeded` (`:953`) — **NOT** from `onSpecStageSucceeded` (`:349`), the `WaitingForSpecApproval` entry. 3e-3 fires the SAME advisory reviewer at the spec phase, over the spec artifact, with **ticket + spec + open clarifications** as its review context, and surfaces the verdict in the spec Decision Bar. It is **advisory-only** — it never auto-approves/rejects the spec; the human PM decision remains governing (mirror 3d-2 DD-2).
>
> **Three reconciliations the live codebase forces (read all three before coding):**
>
> 1. **The reviewer trigger already exists — you call it from one more place.** `enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId)` enqueues a `RunnerStage.REVIEW` over the producing execution's artifact, async + non-blocking, inside the transition tx, and degrades every failure mode through the worker (panel "unavailable"). Add a call to it from `onSpecStageSucceeded` (the spec-ready transition), guarded so the spec-phase reviewer fires only when the project has a reviewer binding. The reviewer-kind binding (`reviewer_model_kind`) is **run-level, not per-stage** (`resolveReviewerKind`), so the same binding governs the spec reviewer and the execution reviewer — no new project field. See R1.
> 2. **The spec-review context bundle must include the OPEN CLARIFICATIONS — this is why 3e-3 depends on 3e-1 (now DONE).** The existing reviewer bundle (`ContextBundleService.assembleForReview`, `:745`) sets `priorFeedbackReferences` EMPTY ("the verdict is a fresh second opinion") and references only the reviewed artifact. For the spec phase the user explicitly wants the reviewer to weigh the **ticket + spec + open questions**. Add a spec-review bundle variant (or extend `assembleForReview`) that includes the run's `open` clarifications. **Mirror the EXACT pattern 3e-2 actually shipped (`assembleForSpecInvestigation`, `:785`/`:857`): an INLINE `acceptedClarifications` array of `{clarificationId, questionId, questionText[, answerText]}`, policed by the SAME single `redact(root, SHAREABLE_REDACTED)` pass — NOT a reference-by-id materialized input file.** The earlier "materialize as an input file / 2KB cap" framing is stale: the cap was raised to 256KB ([[context-bundle-2kb-payload-cap]], `CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES = 256*1024`) and 3e-2 inlines the bounded clarification text directly. Open clarifications carry NO answer yet, so inline `{clarificationId, questionId, questionText}` only (questionText is bounded). Without 3e-1 there are no clarifications to include. See R2.
> 3. **`step_reviews` + the verdict endpoint already persist/serve any reviewed artifact — the gap is "which stage triggers" + the FE surfacing at the spec phase.** `step_reviews` stores `reviewed_artifact_id` + version + `outcome` + `rationale` generically; `GET /reviewer-verdict` already serves it. So persistence is reused unchanged. The net-new is: (a) the spec-phase trigger (R1), (b) the spec-review bundle (R2), (c) surfacing the verdict panel in the **spec** Decision Bar (the FE panel exists for `WaitingForReview`; render it at `WaitingForSpecApproval` too). See R3.

## Story

As a product reviewer,
I want a project-configured second LLM to review the spec — with the ticket, the specification, and the open clarification questions as its context — before I approve it,
So that I get a governed second opinion at the spec-approval gate (not only at the execution-output gate), without surrendering my human approval authority.

## Acceptance Criteria

> Reconciled against the live codebase: 3d-2 built the reviewer substrate but wired it only at `WaitingForReview`. The reconciled ACs below extend it to `WaitingForSpecApproval`; rationale in Dev Notes (R1–R6).

1. **Given** a project with a reviewer binding (`reviewer_model_kind` set — the same 3d-1/3d-2 binding, NOT a new field), **When** a spec artifact becomes available and the run advances `Investigating → WaitingForSpecApproval` (`onSpecStageSucceeded`), **Then** an advisory reviewer invocation (`RunnerStage.REVIEW`) is enqueued over the spec artifact via the existing `enqueueReviewerIfConfigured` — async, non-blocking, inside the spec-ready transition tx, resolved through `ProjectConnectorResolver` using the per-project reviewer credential (decrypted in memory only, never logged).

2. **Given** the spec-review context, **Then** the reviewer's input bundle includes the **ticket summary + the spec artifact + the run's `open` clarifications** (an INLINE `{clarificationId, questionId, questionText}` array, mirroring 3e-2's shipped `acceptedClarifications` inline block at `ContextBundleService:857`, policed by the same single `redact(...)` pass — open rows carry no answerText) — so the reviewer can assess the spec *and* flag whether the open questions are adequately handled. (Execution-phase reviewer bundles are unchanged — this is a spec-review-only bundle variant.)

3. **Given** the reviewer run, **Then** a `runner_executions` row is recorded for it (FR53 inspectability) and a `step_reviews` row persists the structured `outcome` + `rationale` + reviewer/producer model identities, with `reviewed_artifact_id` = the spec artifact + version (the existing generic `step_reviews` write — no schema change).

4. **Given** the advisory contract (ADR 0026 / 3d-2 DD-2), **Then** the spec-phase verdict is **surfaced** to the human in the `WaitingForSpecApproval` Decision Bar via the existing Reviewer Verdict Panel; it does **not** auto-approve or auto-reject the spec, `reviewer_gating_enabled` is not consulted, and the human approve/reject/answer/accept actions are unchanged.

5. **Given** provenance + self-review detection (3d-2 AC4), **Then** the verdict records which model produced the spec and which produced the review; a same-model self-review is flagged as a warning in the panel (reused unchanged).

6. **Given** no reviewer binding on a project, **Then** behavior is byte-identical to pre-3e-3 (no spec reviewer run, no panel) — strictly opt-in per project, the parity hot path (`hasReviewerBinding == false ⇒ no enqueue`).

7. **Given** a reviewer-run failure (misconfig, missing credential, provider error, timeout), **Then** it degrades gracefully through the SAME single path as 3d-2: the spec gate is **not** blocked, the panel shows "review unavailable" with the reason, the failed reviewer execution is recorded — a failed second opinion never strands the spec approval.

8. **Given** redaction (story 1.10) + the spec-review bundle (AC2), **Then** the reviewer's input (incl. the materialized clarification content) and output pass the same redaction guarantees + adversarial secret-fixture gate as any runner artifact before persistence/egress.

9. **Given** the `GET /reviewer-verdict` endpoint (3d-2), **Then** it serves the spec-phase verdict for a `WaitingForSpecApproval` run with no contract change (the endpoint is artifact/run-scoped, not stage-bound) — confirm + test; OpenAPI snapshot byte-identical.

10. **Given** tests, **Then** coverage asserts: the spec reviewer enqueues on `WaitingForSpecApproval` entry when bound and NOT when unbound (parity); the spec-review bundle includes ticket + spec + open clarifications; the verdict persists to `step_reviews` against the spec artifact and surfaces advisory-only in the spec Decision Bar; human spec decision unaffected; graceful degradation on reviewer failure; self-review flagged; redaction on the spec-review egress incl. clarification content; `application.*` ≥80% line coverage.

## Tasks / Subtasks

- [x] **Task 1 — Fire the advisory reviewer at the spec phase** (AC: 1, 6)
  - [x] `application/workflow/WorkflowOrchestrationService.java#onSpecStageSucceeded` — after the `Investigating → WaitingForSpecApproval` transition commits (mirroring `onPlanStageSucceeded`), call `enqueueReviewerIfConfigured(workflowRunId, runnerExecutionId, correlationId)`. Reused the existing helper verbatim (no logic duplicated).
  - [x] Reviewed-artifact derivation: extended `ContextBundleService.resolveReviewedArtifact` with a SPEC fallback (`prOutput → implementationPlan → spec`) so the SAME derivation pins/composes the spec at the spec gate (only the spec exists there) and is byte-identical for execution review (plan/prOutput always present). Decision noted in Completion Notes.

- [x] **Task 2 — Spec-review context bundle (ticket + spec + open clarifications)** (AC: 2, 8)
  - [x] `application/runner/ContextBundleService.java` — added `assembleForSpecReview`; `createForReview` branches on the reviewed artifact type (SPEC ⇒ spec-review bundle). Emits an INLINE `openClarifications` array `{clarificationId, questionId, questionText}` (no answerText) only when ≥1 open clarification exists; policed by the same single `redact(root, SHAREABLE_REDACTED)` pass. Added `openClarifications` to `context-bundle.v1.schema.json` (additive-optional, mirroring 3e-2 `acceptedClarifications`). Plus a by-id `priorFeedbackReferences {kind:'clarification.open'}` row per open clarification (audit half).
  - [x] The composer selects the spec-review variant when `reviewedArtifact.artifactType() == SPEC`; execution-stage review bundles (plan/pr-output) are unchanged.
  - [x] Filter `Clarification.STATUS_OPEN` (mirror of the 3e-2 `STATUS_ACCEPTED` filter); `ClarificationReadPort` already injected. Both runner.mjs prompt builders surface `openClarifications` (byte-identical mirror of the 3e-2 acceptedClarifications block).

- [x] **Task 3 — Surface the verdict in the spec Decision Bar** (AC: 4, 5, 9)
  - [x] Frontend — render the existing `ReviewerVerdictPanelContainer` (3d-2) at `WaitingForSpecApproval` too (route `index.tsx`); the panel/hook/`GET /reviewer-verdict` are run-scoped and unchanged.
  - [x] Confirmed `useReviewerVerdict` keys off the run (not a hardcoded state); only the route-level render gate needed the `|| 'WaitingForSpecApproval'`.
  - [x] Vitest: route renders the panel at WaitingForSpecApproval and NOT at Investigating; the panel's own axe coverage (`ReviewerVerdictPanel.test.tsx`) is unchanged (the component is reused as-is).

- [x] **Task 4 — Backend verification of reused surfaces** (AC: 3, 9)
  - [x] `ReviewResultHarvester` records a spec-phase verdict with `reviewed_artifact_id` = spec + version, no schema change (verified by IT harvest + unit test). Also fixed producer-identity provenance: a SPEC reviewed artifact resolves the INVESTIGATION producer kind (not EXECUTION) so self-review detection is correct at the spec gate.
  - [x] `GET /reviewer-verdict` confirmed stage-agnostic (no code change) — added a unit test serving a verdict for a `WaitingForSpecApproval` run; OpenAPI snapshot byte-identical (no endpoint/DTO change). Supersession semantic noted in Completion Notes.

- [x] **Task 5 — Tests** (AC: 10, all)
  - [x] **Spec-reviewer enqueue IT** (`SpecPhaseAdvisoryReviewerIT`, Failsafe + Testcontainers): bound project → `onSpecStageSucceeded` → a `RunnerStage.REVIEW` over the spec; unbound → NO reviewer enqueue (parity). Plus unit parity tests in `WorkflowOrchestrationServiceTest`.
  - [x] Spec-review bundle test (`ContextBundleServiceSpecReviewTest`): inline `openClarifications` `{clarificationId, questionId, questionText}` (no answerText), redaction-clean, accepted rows excluded; no-open-clarification run omits the field.
  - [x] Harvest IT: the same `SpecPhaseAdvisoryReviewerIT` drives the mock `happy-review` → `step_reviews` against the spec → `GET /reviewer-verdict` serves it. Plus `ReviewResultHarvesterTest` spec-path unit test.
  - [x] Graceful degradation: reused 3d-2 degradation assertions (`ReviewResultHarvesterTest`); the spec gate is never blocked (the harvest never transitions the run — proven by the IT staying `WaitingForSpecApproval`).
  - [x] Redaction: `ContextBundleServiceSpecReviewTest` plants a GitHub PAT in an open-clarification questionText and asserts it never egresses.
  - [x] Naming/tier: `SpecPhaseAdvisoryReviewerIT` (`*IT`) run via the Failsafe lifecycle phase ([[maven-arglineation-goal-crash]]).

- [x] **Logging instrumentation** (cross-cutting)
  - [x] `createForReview` logs `bundleVariant` (spec-review|execution-review) + `openClarificationCount`; the enqueue logs `bound|parity` (existing); the harvest logs verdict persistence (existing). No spec/clarification/answer text or reviewer credential is ever logged.

### Review Findings

_Code review 2026-06-25 (bmad-code-review, 3 adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). All 10 ACs assessed Met. 1 decision-needed · 1 patch · 5 deferred · 8 dismissed as noise/verified-handled._

- [x] [Review][Decision→Patched 2026-06-25] SPEC fallback in `resolveReviewedArtifact` weakens the wrong-artifact guard — the new `prOutput → implementationPlan → SPEC` precedence means the old `orElseThrow` no longer fires when only a spec exists. The synchronous compose path is safe (the transition table has no inbound edge to `WaitingForSpecApproval` from `Executing`/`WaitingForReview`, so no plan/prOutput exists at the spec gate). The residual risk is the **harvest pin-absent fallback**: if `pinReviewedArtifactBestEffort` failed at enqueue and the async harvest lands *after* the run advanced past the spec gate and an `AVAILABLE` plan now exists, re-derivation returns the plan (not the spec the reviewer reviewed) → verdict FK + `resolveProducerIdentity` mis-attribute as EXECUTION. Pre-3e-3 this fallback threw `ARTIFACT_RECORD_NOT_FOUND` and degraded cleanly. Narrow (pin must be absent) + advisory-only, but a silent mis-attribution where there used to be a clean failure. [blind+edge+auditor] `ContextBundleService.java:769` / `ReviewResultHarvester.java:207`
  - **Resolution:** Split the resolver — `resolveReviewedArtifact` (spec-inclusive) still serves compose + enqueue-pin (where the run is AT the gate); added `resolveExecutionReviewedArtifact` (prOutput → plan, NO spec tier) for the harvest pin-absent fallback. An execution reviewer re-derives unchanged; a spec reviewer with a lost pin now degrades cleanly (AC7) instead of mis-attributing. New regression test `ReviewResultHarvesterTest.specReviewerWithLostPinDegradesInsteadOfMisAttributing`. Verified: 10 harvester + 47 bundle/orchestration tests green.

- [x] [Review][Patch→Applied 2026-06-25] `openClarificationCount = -1` sentinel logged for every execution-review — `bundleVariant` is already a separate explicit log field, so the `-1` count is redundant and poisons any log-derived metric summing `openClarificationCount`; emit 0 (or omit) on the execution-review branch. [blind] `ContextBundleService.java#createForReview`
  - **Resolution:** Replaced the `-1` sentinel with an explicit `boolean isSpecReview` + an `openClarificationCount` that is an honest `0` on the execution-review branch. `bundleVariant` remains the authoritative spec-vs-execution signal. Verified: 13 bundle/review tests green.

- [x] [Review][Defer] Unbounded `questionText` / open-clarification count vs the 256KB bundle cap — no `maxLength` on `questionText` and no count cap on the open-clarification loop; "bounded" is asserted in comments, not enforced. Shared with the 3e-2 `acceptedClarifications` pattern — deferred, pre-existing (cross-cutting decision). [blind+edge] `context-bundle.v1.schema.json` / `ContextBundleService.assembleForSpecReview`

- [x] [Review][Defer] Unescaped `questionText` interpolated into both runner.mjs markdown prompts — free-form clarification text concatenated into a prompt bullet with no escaping; newlines/backticks/markdown break the layout and present a prompt-injection surface to the LLM reviewer (redaction policing covers secrets, not control chars). Shared with the 3e-2 `acceptedClarifications` block — deferred, pre-existing pattern. [blind] `runners/claude/lib/runner.mjs` + `runners/codex/lib/runner.mjs`

- [x] [Review][Defer] Redaction-to-empty `questionText` fails schema `minLength:1` — an open clarification whose text is entirely secret-shaped is blanked by the single `redact(...)` pass, failing bundle validation → reviewer degrades to "no verdict" (gate not blocked, advisory). Same failure mode as 3e-2 `acceptedClarifications` — deferred, pre-existing shared. [edge] `context-bundle.v1.schema.json:221`

- [x] [Review][Defer] FE Verdict Panel freshness keys on latest REVIEW execution, not reviewed-artifact version — on a spec→reject→Investigating→spec round-2 where the round-2 reviewer enqueue degrades, the round-1 verdict over a now-superseded spec still renders as `available`. Pre-existing freshness-check shape (`WorkflowInspectionService`), now reachable at the spec gate — deferred. [blind+edge] `WorkflowInspectionService.java:241` / `index.tsx:294`

- [x] [Review][Defer] Spec-phase self-review unit test is mock-tautological — `ReviewResultHarvesterTest.specPhaseHarvest...` asserts the producer identity equals the runner kind it stubbed the resolver to return (verifies the mock, not the real INVESTIGATION-stage wiring); the self-review boolean (AC5 output) isn't asserted there. Mitigated by `SpecPhaseAdvisoryReviewerIT` — deferred, test-strengthening only. [blind] `ReviewResultHarvesterTest.java`

## Dev Notes

### Why these ACs are reconciled (request vs. the live codebase)

| Request | Reality (verified 2026-06-23) | Reconciliation |
|---|---|---|
| "perform review on the WaitingForSpecApproval phase" | The advisory reviewer (3d-2) fires only at `WaitingForReview` — `enqueueReviewerIfConfigured` is called from `onPlanStageSucceeded`/`onPrOutputStageSucceeded`, not `onSpecStageSucceeded`. | Call the SAME trigger from `onSpecStageSucceeded` (R1, Task 1). |
| "Reviewer can get ticket, specification and opened questions" | The reviewer bundle (`assembleForReview`) carries only the reviewed artifact + empty prior-feedback. | Add a spec-review bundle variant that includes the ticket + spec + run's `open` clarifications (R2, Task 2) — hence the 3e-1 dependency. |
| "and review it" (advisory) | `step_reviews` + `GET /reviewer-verdict` + the verdict panel already exist (3d-2), generic over any reviewed artifact. | Reuse persistence + endpoint; surface the panel in the spec Decision Bar (R3, Task 3). Advisory-only — no auto-approve (3d-2 DD-2). |

### R1 — One more call site, run-level reviewer binding
The cheapest correct change: `onSpecStageSucceeded` calls `enqueueReviewerIfConfigured` exactly as the two execution callbacks do. The reviewer binding is **run-level** (`resolveReviewerKind`/`hasReviewerBinding` take only `workflowRunId`), so the spec reviewer and the execution reviewer share one project binding — no per-stage reviewer field, no new resolver. All the hard parts (async non-blocking, in-tx enqueue, graceful degradation, self-review flag, parity hot path) are already solved by 3d-2 and inherited for free. The only spec-specific care is that the reviewed artifact derivation resolves the SPEC for an INVESTIGATION-stage producing execution (Task 1 second bullet).

### R2 — The spec reviewer needs the open clarifications → depends on 3e-1 (now DONE)
The user's intent ("ticket, specification and opened questions") makes the spec-review bundle richer than the execution-review bundle. Execution review is "a fresh second opinion over one artifact" (empty prior-feedback by design). Spec review wants the open questions so the reviewer can judge whether the spec leaves them unresolved. **Reconciliation (verified against the SHIPPED 3e-2 code, not the 3e-2 story prose):** 3e-2 did NOT use a reference-by-id materialized input file — it INLINES an `acceptedClarifications` array (`{clarificationId, questionId, questionText, answerText}`) directly in the bundle JSON at `ContextBundleService:857`, justified because the cap was raised to 256KB ([[context-bundle-2kb-payload-cap]]) and answerText is `@Size(max=8192)`, and policed by the single `redact(root, SHAREABLE_REDACTED)` pass that covers every text leaf. 3e-3 mirrors that exactly for OPEN clarifications (questionText only — no answer yet). The "materialize a separate input file / 2KB cap" instruction from the original draft is obsolete; do not follow it. This is why 3e-3 sequences AFTER 3e-1 (no open clarifications exist before it) — both 3e-1 and 3e-2 are now DONE.

### R3 — Persistence + endpoint are stage-agnostic; only trigger + FE surfacing are net-new
`step_reviews.reviewed_artifact_id` + version is generic; the harvester writes whatever artifact the REVIEW execution reviewed; `GET /reviewer-verdict` is run-scoped. So no schema/endpoint change — confirm + test the spec-artifact path and (if any) widen a stage filter. The FE panel exists; render it in the spec Decision Bar and ensure its fetch isn't hardcoded to `WaitingForReview`.

### R4 — Advisory-only is structural, inherited from 3d-2
3d-2 DD-2: the verdict never auto-approves/rejects; `reviewer_gating_enabled` (3d-1, default false) is not consulted this epic. 3e-3 keeps that posture at the spec gate — the human approve/reject/answer/accept (2.13/3e-2) actions are untouched; the panel is presentational. Gating-capable spec review is a later epic if ever.

### R5 — Out of scope
Per-stage reviewer model selection (a different reviewer LLM for spec vs execution) — out of scope; one run-level reviewer binding governs both. Reviewer auto-gating the spec — out of scope (advisory only). The reviewer proposing answers to the open clarifications — out of scope (it flags adequacy; answering remains the human's via 3e-1/`/answer`).

### Verified seams (file:line, re-verified 2026-06-25 against the 3e-1/3e-2-landed codebase)
- Reviewer trigger (execution-only today) — `WorkflowOrchestrationService.java:751` (onPlanStageSucceeded enqueue call), `:953` (onPrOutputStageSucceeded enqueue call), helper `enqueueReviewerIfConfigured` `:769`, `pinReviewedArtifactBestEffort` `:825`. Spec entry that LACKS the call — `onSpecStageSucceeded` `:349`.
- Reviewer bundle (empty prior-feedback) — `ContextBundleService.assembleForReview:745` (drifted from `:730`; 3e-2 inserted `assembleForSpecInvestigation` above it). **Pattern to mirror for the inline clarifications** — `assembleForSpecInvestigation:785`, the inline `acceptedClarifications` block `:857`, the `STATUS_ACCEPTED` filter precedent `:513-519`. Clarification read port already injected (3.10), `listByWorkflowRunId` `:517`/`:1095`.
- Clarification domain — `application/clarification/Clarification.java`: `STATUS_OPEN = "open"` `:49`, fields `questionId` `:40` / `questionText` `:41`.
- Reviewer kind/binding (run-level) — `application/project/ProjectRuntimeConfigResolver.java`: `resolveReviewerKind:190` / `hasReviewerBinding:185` (both take only `workflowRunId`).
- Verdict persistence/serve — `application/review/ReviewResultHarvester.java`; serve via `WorkflowInspectionService.getReviewerVerdict:217` (run-scoped, **stage-agnostic** — `findLatestForRun` + `findLatestByWorkflowRunPublicIdAndStage(REVIEW)`, NO state filter) → `GET /reviewer-verdict` `WorkflowController:258` (3d-2). step_reviews generic over reviewed artifact; no schema/endpoint change.

## Dev Agent Record

### Context Reference
- Story 3e-3 implemented 2026-06-25 (Opus 4.8 [1m]) following bmad-dev-story. Extends the 3d-2 advisory reviewer to the `WaitingForSpecApproval` gate.

### Implementation Plan / Key Decisions
- **Reviewed-artifact derivation = gate precedence, not a new stage parameter (Task 1).** Rather than thread the producing execution's stage through `resolveReviewedArtifact`/`createForReview`/`ReviewResultHarvester` (which only have the `workflowRunId`), I extended `resolveReviewedArtifact` with a SPEC fallback: `prOutput → implementationPlan → spec`. At `WaitingForSpecApproval` only a spec artifact exists, so the precedence resolves the spec; at `WaitingForReview` a plan/prOutput is always present, so the spec is never selected and execution review stays byte-identical. This is the cheapest correct realization of the story's "map INVESTIGATION → the spec artifact" and needs **no new constructor dependency** (avoiding the `ContextBundleService` legacy-ctor fan-out). The pin (`pinReviewedArtifactBestEffort`) then records `reviewedArtifactType=spec`, and the harvest reuses that pin.
- **Bundle variant selected by reviewed-artifact TYPE (Task 2).** `createForReview` branches: `reviewedArtifact.artifactType() == SPEC` ⇒ `assembleForSpecReview` (inline `openClarifications` + `approvedSpecificationReference: null`, since the spec under review is not yet approved); else the unchanged `assembleForReview`. Inline `openClarifications` exactly mirrors 3e-2's shipped `acceptedClarifications` block (question text only — open rows have no answer), additive-optional, redaction-policed by the single root pass.
- **Producer-identity provenance for the spec (Task 4, AC5).** `ReviewResultHarvester.resolveProducerIdentity` previously mapped every reviewed type to an EXECUTION sub-stage. A SPEC reviewed artifact is produced by the INVESTIGATION stage, so I added a SPEC branch resolving `resolveRunnerKind(runId, INVESTIGATION)` — otherwise a same-model self-review at the spec gate would be mis-detected.
- **Supersession semantic (AC9, noted per Task 4).** `GET /reviewer-verdict` surfaces the LATEST verdict per run. A spec-phase verdict is naturally replaced once an execution-phase reviewer later runs — correct, because the run is at exactly one gate at a time.
- **Advisory-only is structural and inherited.** The harvest never transitions the run; the `SpecPhaseAdvisoryReviewerIT` asserts the run stays `WaitingForSpecApproval` after the verdict persists. The human spec approve/reject/answer/accept actions are untouched.

### Completion Notes
- Net-new is a SINGLE backend call site (`onSpecStageSucceeded` → `enqueueReviewerIfConfigured`) + a spec-review bundle variant + a one-line FE render gate; everything else (queue, worker, `step_reviews`, harvest, verdict endpoint, panel, hook) is reused unchanged from 3d-2.
- `openClarifications` is an additive-optional `context-bundle.v1` property (NOT in `required`; `schemaVersion` stays `const:1`) — same backward-compatible pattern as 3e-2's `acceptedClarifications`. **Reinstall `deliveryline-runner-contracts` into `.m2` before backend ITs** ([[runner-contracts-schema-stale-in-m2]]) — done.
- **IT trap discovered:** overriding `deliveryline.runner.mock.default-scenario.investigation` via `@TestPropertySource` rebuilds the whole `Mock.defaultScenario` map, so the `review` default (`happy-review`) must be re-stated or `scenarioFor(REVIEW)` falls back to `happy-spec` and the reviewer emits a runner-result the harvest rejects (no verdict). The IT now sets both keys.
- No OpenAPI/`schema.d.ts` regen (no endpoint/DTO change) — OpenAPI snapshot byte-identical (AC9).

### Verification (all green, 2026-06-25)
- Backend full unit suite: **1332 run, 0 fail, 0 error** (12 skipped) via the `test` lifecycle phase + `-Djacoco.skip=true`.
- Backend IT `SpecPhaseAdvisoryReviewerIT`: **2/2** (Testcontainers Postgres + offline mock runner) — enqueue+verdict-vs-spec, and no-binding parity.
- `deliveryline-runner-contracts`: 11/11. Runner node tiers: claude 28/28, codex 35/35. Both `runner.mjs` `node --check` clean.
- Frontend: `tsc --noEmit` clean; `ReviewerVerdictPanel.test.tsx` 9/9; route `index.test.tsx` 4/4 (2 new spec-gate render tests); prettier + eslint clean.

### File List
**Backend (main)**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java` — `onSpecStageSucceeded` calls `enqueueReviewerIfConfigured`.
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/ContextBundleService.java` — `resolveReviewedArtifact` SPEC fallback; `createForReview` type-branch; new `assembleForSpecReview`; bundle-variant log.
- `deliveryline-backend/src/main/java/org/dradgo/application/review/ReviewResultHarvester.java` — SPEC producer-identity branch (INVESTIGATION).

**Runner contracts + runners**
- `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json` — additive `openClarifications` property.
- `runners/claude/lib/runner.mjs`, `runners/codex/lib/runner.mjs` — surface `openClarifications` into the prompt (byte-identical mirror).

**Frontend**
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` — render the verdict panel at `WaitingForSpecApproval`.

**Tests (new)**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/ContextBundleServiceSpecReviewTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/SpecPhaseAdvisoryReviewerIT.java`

**Tests (extended)**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java` — spec-gate enqueue + parity.
- `deliveryline-backend/src/test/java/org/dradgo/application/review/ReviewResultHarvesterTest.java` — spec-phase harvest + INVESTIGATION producer.
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceReviewerVerdictTest.java` — stage-agnostic spec-gate serve.
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.test.tsx` — spec-gate panel render / non-render.

## Change Log
| Date | Change |
|---|---|
| 2026-06-25 | Implemented 3e-3: fire the 3d-2 advisory reviewer at the `WaitingForSpecApproval` gate over the spec artifact with the run's open clarifications inlined into the review bundle; surface the existing verdict panel in the spec Decision Bar. Status `ready-for-dev → in-progress → review`. |
