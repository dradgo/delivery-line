# Sprint Change Proposal — WaitingForReview Execution-Review Loop ("Option X")

**Date:** 2026-06-16
**Author:** Alex (via `bmad-correct-course`)
**Scope classification:** Moderate (backlog reorganization — net-new stories added to Epic 3b; no rework/rollback of completed work)
**Path forward:** Option 1 — Direct Adjustment (net-new stories within Epic 3b)

---

## Section 1 — Issue Summary

The first real full-cycle **execution** run — `run_ae258aa42f524ba29db3c795732a21e6` (FIN-21) — reached
`WaitingForReview` and exposed that the execution review loop is **not usable end-to-end**. This is the
execution-stage twin of the gate that story **3a-9** closed for the spec stage on 2026-06-14: the
Epic-2-retro **"first real full-cycle run"** pilot-readiness gate (`[[epic-2-retro-real-run-gate]]`)
surfacing dormant seams that were never exercised against a real run.

Three approved design specs (2026-06-16, "Option X") decompose the issue:

- **#1 — Runner two-phase EXECUTION contract** (`docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md`)
  The runner dispatch carries only the coarse `RunnerStage` (`INVESTIGATION`/`EXECUTION`).
  `DockerRunnerAdapter` (`:243`) sets `DELIVERYLINE_RUNNER_STAGE = "execution"`, and the runner's
  `entrypoint.sh map_stage` maps `execution → prOutput` **unconditionally** (`entrypoint.sh:246`). So
  **every** execution dispatch runs `danger-full-access`, implements, and pushes — regardless of the
  backend's `ExecutionSubStage` (`IMPLEMENTATION_PLAN` vs `PR_OUTPUT`). On `run_ae258…` the orchestrator
  was in `IMPLEMENTATION_PLAN` (no approved plan), but the runner produced a `prOutput` (real `docs/`
  change, pushed, draft PR #2); the broker ingested it as the plan sub-stage and **skipped**
  `validateAndEnrichPrOutput` — the sole place that persists the `github_pr` integration-link. Net: no
  plan to review, a `prOutput` mislabeled as the plan phase, and no PR link despite a real PR.

- **#2b — WaitingForReview availability** (`docs/superpowers/specs/2026-06-16-waiting-for-review-availability-design.md`)
  `implementationPlan`/`prOutput` artifacts ingest as `status=pending` (only `spec` is marked
  `available` — `RunnerBroker.markSpecArtifactAvailable`, ingest loop `RunnerBroker.java:1342`).
  `acceptImplementation` requires `isApprovalEligible` = AVAILABLE, so accept is currently impossible for
  a `prOutput`. The detail route links only the spec (`resolveSpecArtifactId`) — there is no link to the
  implementation/PR artifact.

- **#3 — WaitingForReview review experience UI** (`docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md`)
  `getAllowedActions` defaults to `product_reviewer` (the `ApprovalReviewerRoleResolver` `@Value`
  fallback); accept/reject/takeover are only returned for the `developer` role, so the decision bar is
  `blocked`/inert. The generic artifact viewer renders a `prOutput` (JSON: branch/commitSha/prReference/
  diffReference) as markdown → likely fails the `isArtifactView` guard → renders `error`. The embedded
  SPA bundle predates the `implementation_review` bar (`mvn package` re-embed required).

**Evidence:** live run `run_ae258aa42f524ba29db3c795732a21e6`; branch
`deliveryline/FIN-21/stage-732a21e6`; draft PR #2 on `dradgo/financemonitor.2019`; the cited source
locations (`RunnerBroker.java:1342`, `DockerRunnerAdapter:243`, `entrypoint.sh:246`,
`WorkflowDecisionBar.tsx`, `approvalDecisionView.ts`). All three specs are status **approved (design)**.

---

## Section 2 — Impact Analysis

**Epic impact.** Epic 3b's planned implementation + dev-review stories (3.7–3.36) are **done or in
flight** (3.28 in `review`; 3.29/3.35/3.36 `backlog`; 3.32–3.34 `ready-for-dev`). **None** of them
threads the execution sub-stage to the runner, marks execution artifacts `available` on ingest, or wires
the `developer` role at `WaitingForReview`. These are genuine **net-new** gaps — precisely the pattern by
which story 3a-9 was added net-new for the spec stage. Epic 3 cannot satisfy its pilot-readiness gate
(`[[epic-2-retro-real-run-gate]]` C1–C3) without them: the execution review loop strands every run at
`WaitingForReview`. **No existing story requires rework; no rollback; MVP scope is unchanged** — this work
*completes* the already-committed execution review MVP loop.

**Story impact.** Add 6 net-new stories (`3b-1`…`3b-6`). No edits to the AC text of completed stories.
Two reconciliations against done work to verify during story execution (not rework):
- 3.26 (impl-plan ARP variant) and 3.27 (PR/output ARP variant) are `done`, yet spec #3 reports a live
  `prOutput` renders as `error`. The variant renderers exist but are likely **not reachable for a live
  `prOutput`** because the raw JSON body fails `isArtifactView` (`[[artifact-read-dto-must-satisfy-isartifactview]]`).
  3b-5/3b-6 reconcile the done variants against the live artifact path rather than re-building them.
- 3a-9's note states `implementationPlan`/`prOutput` "deliberately remain `pending`-on-ingest". 3b-3
  **intentionally reverses** that posture now that the developer review gate is being activated — an
  explicit, scoped supersede, called out in the story.

**Artifact conflicts.** PRD, architecture, and UX specs — **no conflict** (this realizes existing intent;
the execution review surface and developer-takeover were always in the Epic 3 PRD/UX scope). Only two
artifacts change: `_bmad-output/planning-artifacts/epic-03-agent-execution.md` (append an "Epic 3b
Net-New Stories" section) and `_bmad-output/implementation-artifacts/sprint-status.yaml` (6 new
`backlog` entries). The three design specs are already the authoritative design source.

**Technical impact.** Backend: dispatch request carries `ExecutionSubStage`; `DockerRunnerAdapter` env
token; broker availability generalization + plan-approval re-dispatch. Runner: **no image change
expected** (stage-aware logic + `map_stage` tokens already present — confirm via conformance ITs).
Frontend: dev-role allowed-actions request + decision role; `prOutput`/`implementationPlan` review
rendering reusing 3.31 blocks; `mvn package` SPA re-embed.

---

## Section 3 — Recommended Approach

**Option 1 — Direct Adjustment.** Add 6 net-new stories within Epic 3b, mirroring the 3a-9 net-new
precedent. **Effort: Medium. Risk: Low.**

- *Option 2 (Rollback): Not viable* — nothing completed is wrong; the gaps are un-built seams, not
  defective work.
- *Option 3 (MVP review): Not needed* — MVP scope is unchanged; this completes the committed loop rather
  than reducing or redefining it.

**Sequencing.** `(3b-1 → 3b-2)` [#1 correctness] and `3b-3` [#2b availability] can proceed in parallel;
then `3b-4` [#3 dev-role] gates the decision actions; then `3b-5` (full `prOutput` accept needs the
`github_pr` link from 3b-1/3b-2) and `3b-6` (plan-phase decisions need no link). #1 + #2b together make a
coherent two-phase flow; #3 makes it usable in the UI.

---

## Section 4 — Detailed Change Proposals

### Artifact: `epic-03-agent-execution.md` — APPEND new section "Epic 3b Net-New Stories (added 2026-06-16)"

The 6 story stubs below are appended after story 3a-9 (current end of file, line 876). Full ACs are
drafted by `bmad-create-story` when each enters the cycle — the stubs capture working title, goal,
current-behavior, dependencies, AC-shape reference, and design source (the 3a net-new convention).

---

#### Story 3b-1: Thread `ExecutionSubStage` → Runner Stage Token (Dispatch + Adapter)

As a backend developer + workflow orchestrator,
I want the execution dispatch to carry the resolved `ExecutionSubStage` so `DockerRunnerAdapter` sets
`DELIVERYLINE_RUNNER_STAGE` to `implementation-plan` / `pr-output` (the tokens `entrypoint.sh map_stage`
already accepts) instead of the coarse `execution`,
So that an `IMPLEMENTATION_PLAN` dispatch runs the read-only plan phase (emits `implementationPlan`, no
push) and a `PR_OUTPUT` dispatch implements + pushes + emits `prOutput` — fixing the root cause that every
execution dispatch ran `prOutput` regardless of sub-stage.

**Current behavior to change:** `DockerRunnerAdapter` (`:243`) sets `DELIVERYLINE_RUNNER_STAGE =
request.stage().value()` → `"execution"`; `entrypoint.sh map_stage` (`:246`) maps `execution → prOutput`
unconditionally. The broker already derives the sub-stage for logging
(`executeQueuedDispatch … subStage=IMPLEMENTATION_PLAN`) — reuse that single derivation.

**Dependencies:** 3.1 (DockerRunnerAdapter), 3.11 (`dispatchPlanGeneration` + execution dispatch path),
3.12 (PR/output orchestration + `validateAndEnrichPrOutput`), 1.13 (RunnerBroker + ContextBundleService —
`deriveExecutionSubStage`), 1.6 (runner-contracts schema), 3.3/3.4 (runner images + `entrypoint.sh
map_stage` — confirmed stage-aware, no image change expected).

**AC-shape reference:**
- Dispatch carries the resolved `ExecutionSubStage` (preferred: add the sub-stage to the dispatch request
  built in `executeQueuedDispatch`; alternative: keep `request.stage()` as `RunnerStage` and add a
  `subStage` field, adapter chooses the env token from the pair — decide in story design). `INVESTIGATION`
  still maps to `spec-investigation`/`investigation`.
- `DockerRunnerAdapter` sets `DELIVERYLINE_RUNNER_STAGE ∈ {implementation-plan, pr-output}` from the
  resolved sub-stage. **Caution:** do not add a second Docker record constructor — it breaks Spring
  binding (`[[runner-image-stale-causes-exit-20]]`).
- Conformance ITs (folds in spec #1 "Story C"): an `implementation-plan` dispatch produces an
  `implementationPlan` with `read-only` sandbox (no repo writes); a `pr-output` dispatch produces a
  `prOutput`. Confirm `runner.mjs build --stage` already emits both shapes (it does) — assert, don't add.
- Unit/IT: each sub-stage dispatches the correct runner stage token; investigation/spec path unchanged
  (regression).
- ArchUnit boundary unchanged; `RUNNER_ARTIFACT_TYPE_MISMATCH` still guards a runner emitting the wrong
  artifact type for the dispatched sub-stage.

**Design source:** `docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md`
(sub-project #1, Story A + Story C).

---

#### Story 3b-2: Two-Dispatch Execution Orchestration — Plan-Approval Re-Dispatches the PR Phase

As a workflow orchestrator,
I want approving the implementation plan to re-dispatch a second execution run (the PR phase), so the
full walk is approve spec → dispatch #1 (plan, read-only) → plan review → approve plan → dispatch #2 (PR,
implement+push) → PR review → accept → `Completed`,
So that the two-phase contract from 3b-1 actually reaches the `PR_OUTPUT` sub-stage and persists the
`github_pr` link via `validateAndEnrichPrOutput`.

**Current behavior to change:** today only ONE execution dispatch happens (because the runner always did
`prOutput`). **Open question to resolve in story design:** does
`TechnicalApprovalService.acceptImplementation` on an `implementationPlan` (→ `Executing`) already
re-dispatch the PR phase, or must it be added? Trace
`WorkflowOrchestrationService.onPlanStageSucceeded` / the accept-plan transition and the dispatch trigger.

**Dependencies:** 3b-1 (sub-stage threading — required), 3.11 (`dispatchPlanGeneration`), 3.12
(`validateAndEnrichPrOutput` + PR/output orchestration), 3.20 (`acceptImplementation` — the plan-approval
entry point), 3.15 (GitHub PR integration-link persistence), 1.5 (state-transition table).

**AC-shape reference:**
- Spec approval triggers `dispatchPlanGeneration` (already observed); with 3b-1 the first execution
  dispatch genuinely runs the plan phase (read-only, no push).
- Accepting the plan re-dispatches the PR phase (add the trigger if absent); the PR dispatch produces a
  `prOutput`, pushes, and `validateAndEnrichPrOutput` runs (persisting the `github_pr` link).
- Idempotency: duplicate accept / re-dispatch for an in-flight PR-phase execution is a no-op
  (mirror 3.11 AC6).
- **End-to-end orchestration IT (the headline AC):** approve spec → plan dispatch emits
  `implementationPlan` (no push) → `WaitingForReview` → accept plan → PR dispatch emits `prOutput` +
  pushes + persists `github_pr` link → `WaitingForReview` → accept → `Completed`. Investigation/spec path
  unchanged (regression).
- `[[post-commit-hook-needs-requires-new]]` applies if the re-dispatch fires from a transaction-synchronization
  afterCommit hook.

**Design source:** `docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md`
(sub-project #1, Story B).

---

#### Story 3b-3: WaitingForReview Artifact Availability — Mark `implementationPlan`/`prOutput` `available` on Ingest + Surface the Implementation-Artifact Link

As a Workflow Owner / developer reviewer,
I want execution-produced artifacts (`implementationPlan`, `prOutput`) marked `available` on ingest and a
state-aware "Open the implementation output →" link surfaced at `WaitingForReview`,
So that `acceptImplementation` (which requires `isApprovalEligible` = AVAILABLE) can fire and the reviewer
can actually reach the implementation artifact — the execution-stage twin of 3a-9's spec-stage Gate 1.

**Current behavior to change:** only `spec` is marked available
(`RunnerBroker.markSpecArtifactAvailable`, ingest loop `RunnerBroker.java:1342`);
`implementationPlan`/`prOutput` stay `pending`. The detail route
(`routes/workflows/$workflowRunId/index.tsx`) links only the spec via `resolveSpecArtifactId`. The
embedded SPA bundle is stale (predates the `implementation_review` bar).

**Dependencies:** 3a-9 (spec-stage availability pattern — generalize `markSpecArtifactAvailable` →
`markArtifactAvailable`; this story **supersedes** 3a-9's deliberate `pending`-on-ingest posture for
`IMPLEMENTATION_PLAN`/`PR_OUTPUT`), 3.12 (3.12 enrich `UPDATE` interaction), 1.12 (artifact operations +
`markAvailable`, `[[markavailable-has-no-production-caller]]`), 2.1 (SPA embed at `mvn package`),
3.28 (`implementation_review` bar — re-embed activates it), `resolveImplementationArtifact` (live as of
3a-9).

**AC-shape reference:**
- Backend: in `RunnerBroker.onResult`'s ingest loop (~`:1342`), after the artifact `CREATE` succeeds,
  mark `SPEC`, `IMPLEMENTATION_PLAN`, and `PR_OUTPUT` `available` (today only `SPEC`). Generalize
  `markSpecArtifactAvailable` → `markArtifactAvailable` (checksum over the ingested payload bytes + the
  payload-store-reported `storageRef`; no spec-specific assumptions). Idempotent-replay safe.
- `prOutput` × 3.12 enrich: the enrich `UPDATE` runs only in the `PR_OUTPUT` sub-stage and after the
  in-loop marking. **Verify the enrich does not revert status to `pending`; if it does, re-mark
  `available` after `enrichPrOutputArtifact`** so the enriched version is the available one. Auto-advance
  (`onPlanStageSucceeded`/`onPrOutputStageSucceeded`) unchanged.
- Frontend: render an "Open the implementation output →" link when `resolveImplementationArtifact(data)`
  resolves an artifact id (prefers `prOutput`/`implementationPlan`, highest version), targeting the
  existing `/workflows/$workflowRunId/artifacts/$artifactId` route. No decision-bar change here.
- Rebuild + re-embed: `mvn package` rebuilds the SPA into backend `static/`
  (`[[embedded-frontend-at-package-phase]]`); verify on the live `WaitingForReview` run.
- Tests (failing-first): `RunnerBrokerUnitTest` — ingested `prOutput`/`implementationPlan` end
  `available`; `PrOutputOrchestrationIT`/`ImplementationPlanOrchestrationIT` — artifact row
  `status=available` + run `WaitingForReview`; no spec regression (`SpecStageOrchestrationIT`); a
  route/page test asserting the impl-artifact link renders with a `prOutput` `artifactId` and not when
  only a spec exists.

**Note:** marking `prOutput` available is a prerequisite for #3's accept flow (`isApprovalEligible`).
This story does NOT change runner behavior or fix the sub-stage mismatch (that is 3b-1/3b-2), and does NOT
ship the real `prOutput` renderer (that is 3b-5) — the generic viewer may render a raw `prOutput` JSON as
`error`, which is acceptable for this story.

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-availability-design.md`
(sub-project #2b).

---

#### Story 3b-4: Developer-Role Wiring at WaitingForReview

As the single operator (one user, multiple roles for now),
I want the UI to request allowed-actions as `developer` when `currentState === 'WaitingForReview'` and
send the `developer` reviewer role on accept/reject/takeover calls,
So that the already-built `implementation_review` decision bar's actions appear and fire instead of being
`blocked`/inert because `getAllowedActions` defaults to `product_reviewer`.

**Current behavior to change:** `getAllowedActions` defaults to `product_reviewer` (the
`ApprovalReviewerRoleResolver` `@Value` fallback); accept/reject/takeover are only returned for the
`developer` role → the decision bar is `blocked`.

**Dependencies:** 3.28 (`implementation_review` bar + `ImplementationReviewDecisionBarContainer` +
`useAcceptImplementation`/`useRejectImplementation`/`useTakeoverWorkflow` hooks), 3.23/3.24/3.25 (accept/
reject/takeover REST endpoints + reviewer-role boundary), 2.14 (allowed-actions version stamp the bar
reads live), 2.13 (header-based role attribution — explicitly OUT of scope; keep the wiring isolated so a
future 2.13 swap-in is clean).

**AC-shape reference:**
- UI requests allowed-actions as `developer` at `WaitingForReview` (single-user-all-roles) and sends the
  developer reviewer role on the decision calls. Mechanism decided in story design; keep isolated for a
  future 2.13 header-attribution swap.
- With the developer role, the bar renders accept/reject/takeover and a decision transitions the run
  (vitest + a manual run after `mvn package`).
- A thin contract test that allowed-actions for `WaitingForReview` returns the developer action set.
- **Note:** a `prOutput` accept still needs the `github_pr` link (backend `assertNoConflictingRepoLink`
  / accept PR-link gate) from 3b-1/3b-2 — sequence the full `prOutput` accept after #1, or test against a
  plan-phase accept (plan-phase accept/reject/takeover do not need the link).

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md`
(sub-project #3, Story A; takeover folds in).

---

#### Story 3b-5: `prOutput` Review Renderer — PR Link + State Badge + Unified Diff

As a developer reviewer,
I want a dedicated `prOutput` review panel that renders the PR link (via `githubRef` + `PrStateBadge`) and
the unified diff (via `SafeUnifiedDiffRenderer` / `parseUnifiedDiff`),
So that a `prOutput` (JSON: branch/commitSha/prReference/diffReference) renders as a reviewable PR + diff
instead of `error`, and accept/reject fire end-to-end against the real PR.

**Current behavior to change:** the generic artifact viewer renders the artifact body as markdown; a
`prOutput` JSON likely fails the `isArtifactView` guard → renders `error`. **Reconcile against done story
3.27** (PR/output ARP variant renderer): the variant exists but is likely unreachable for a live
`prOutput` because the raw JSON body fails `isArtifactView` (`[[artifact-read-dto-must-satisfy-isartifactview]]`,
`[[artifactview-variant-field-fanout]]`) — wire the done variant to the live artifact path; do not
re-build it.

**Dependencies:** 3b-3 (`prOutput` available + linkable), 3b-4 (developer-role actions), 3.27 (PR/output
ARP variant — reconcile), 3.31 (`githubRef.ts`, `PrStateBadge`, `SafeUnifiedDiffRenderer`/
`parseUnifiedDiff` — reused unforked; `[[githubref-branchurl-dot-traversal]]` guard retained), 3.23/3.24
(accept/reject endpoints).

**AC-shape reference:**
- A `prOutput` review panel: summary + PR link (`PrStateBadge` + `githubRef` URL hardening) + unified diff
  (`SafeUnifiedDiffRenderer`/`parseUnifiedDiff`), diff sourced from the artifact payload / `diffReference`.
  Decide render location (artifact-viewer route vs inline review panel on the detail page) in story
  design.
- Accept / reject fire and transition the run; the post-decision success summary + kept-alive
  announcement behavior in `WorkflowDecisionBar` is preserved (bar stays mounted through the state flip).
- Respect `[[frontend-react-refresh-no-fn-exports]]` (helpers in `.ts`, not `.tsx`) and
  `exactOptionalPropertyTypes` when extending ArtifactView variants.
- Tests: a live `prOutput` renders as PR link + diff (not `error`); accept/reject vitest + a manual run
  after `mvn package`.

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md`
(sub-project #3, Story B).

---

#### Story 3b-6: `implementationPlan` Review Rendering + Plan-Phase Accept/Reject/Takeover

As a developer reviewer,
I want the `implementationPlan` artifact's ordered steps rendered for review, with accept / reject
(developer taxonomy) / takeover wired end-to-end for the plan phase,
So that the plan phase of the two-dispatch flow is reviewable and actionable — the path that needs no
`github_pr` link (unlike a `prOutput` accept).

**Current behavior to change:** the `implementationPlan` review rendering is not surfaced on the live
review path. **Reconcile against done story 3.26** (impl-plan ARP variant renderer): wire the existing
variant to the live artifact path (same `isArtifactView` reachability reconciliation as 3b-5/3.27); do not
re-build it.

**Dependencies:** 3b-4 (developer-role actions), 3b-3 (`implementationPlan` available + linkable), 3.26
(impl-plan ARP variant — reconcile), 3.21 (`rejectImplementation` + developer rejection taxonomy
`incorrect_approach`/`incomplete_implementation`/`quality_issue`/`breaks_existing_functionality`/
`out_of_scope`), 3.22/3.25 (takeover service + endpoint + preserved-PR affordance).

**AC-shape reference:**
- Render the `implementationPlan` ordered steps for review (reuse the 3.26 variant on the live path).
- Plan-phase accept / reject (developer taxonomy) / takeover wired end-to-end to the existing endpoints,
  including the post-decision success summary and the takeover preserved-PR affordance. Plan-phase
  decisions need no `github_pr` link.
- Tests: the `implementationPlan` renders its steps (not `error`); plan-phase accept/reject/takeover
  vitest + a manual run after `mvn package`.

**Design source:** `docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md`
(sub-project #3, Story C; takeover folds in).

---

### Artifact: `sprint-status.yaml` — ADD 6 entries to the Epic 3b block (status `backlog`)

```
  3b-1-thread-execution-substage-to-runner-stage-token-dispatch-and-adapter: backlog
  3b-2-two-dispatch-execution-orchestration-plan-approval-redispatches-pr-phase: backlog
  3b-3-waiting-for-review-artifact-availability-mark-available-on-ingest-and-surface-link: backlog
  3b-4-developer-role-wiring-at-waiting-for-review: backlog
  3b-5-proutput-review-renderer-pr-link-state-badge-and-unified-diff: backlog
  3b-6-implementation-plan-review-rendering-and-plan-phase-accept-reject-takeover: backlog
```

---

## Section 5 — Implementation Handoff

**Scope classification: Moderate** (backlog reorganization; no strategic replan, no rollback).

| Story | Discipline | Sequencing |
|---|---|---|
| 3b-1 | Backend + runner conformance ITs | First (correctness root cause) |
| 3b-2 | Backend orchestration + IT | After 3b-1 |
| 3b-3 | Backend availability + FE link + SPA re-embed | Parallel to 3b-1/3b-2 (prereq for #3 accept) |
| 3b-4 | Frontend (+ thin contract test) | After 3.28; gates 3b-5/3b-6 actions |
| 3b-5 | Frontend renderer (reconcile 3.27) | After 3b-4; full prOutput accept after 3b-1/3b-2 |
| 3b-6 | Frontend renderer (reconcile 3.26) | After 3b-4 |

**Routing:** Product Owner / Developer (Moderate) — update Epic 3b backlog, then each story enters the
normal `bmad-create-story` → `bmad-dev-story` cycle. The three design specs are the authoritative inputs;
each story's full ACs are drafted by `bmad-create-story` at cycle entry.

**Success criteria:** the end-to-end execution review loop runs on a real run — approve spec → review a
real plan → accept → review a real PR (PR link + diff, `github_pr` persisted) → accept → `Completed` —
satisfying the Epic-3 pilot-readiness gate for the execution stage (the execution-stage completion of the
`[[epic-2-retro-real-run-gate]]`).

---

## Approval

- [x] Approved for implementation (Alex, 2026-06-16). Applied: 6 net-new story stubs (`3b-1`…`3b-6`) appended to `epic-03-agent-execution.md` (§ "Epic 3b Net-New Stories (added 2026-06-16)") + 6 `backlog` entries added to `sprint-status.yaml` Epic 3b block.
