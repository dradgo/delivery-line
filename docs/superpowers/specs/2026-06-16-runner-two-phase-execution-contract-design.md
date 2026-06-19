# Spec — Runner two-phase EXECUTION contract (sub-project #1)

Date: 2026-06-16
Status: design (story input)

## Context

Sub-project #1 of "Option X". The correctness fix behind the `WaitingForReview` issues observed
on live run `run_ae258aa42f524ba29db3c795732a21e6`.

### Verified root cause

- The backend has a sub-stage concept — `ExecutionSubStage` ∈ {`IMPLEMENTATION_PLAN`, `PR_OUTPUT`},
  derived by `ContextBundleService.deriveExecutionSubStage` from "does an approved implementation
  plan exist?".
- But the runner dispatch only carries the coarse `RunnerStage` ∈ {`INVESTIGATION`, `EXECUTION`}.
  `DockerRunnerAdapter` (`:243`) sets `DELIVERYLINE_RUNNER_STAGE = request.stage().value()` →
  `"execution"`.
- The runner's `entrypoint.sh` `map_stage` maps `execution → prOutput` unconditionally
  (`entrypoint.sh:246`). So EVERY execution dispatch tells the runner "prOutput" → it runs
  `danger-full-access`, implements, and pushes — regardless of the backend's plan/PR sub-stage.
- Consequence on `run_ae258…`: the orchestrator was in `IMPLEMENTATION_PLAN` (no approved plan),
  but the runner produced a `prOutput` (real `docs/` change, pushed, draft PR #2). The broker
  ingested it, treated it as the plan sub-stage (`onPlanStageSucceeded → WaitingForReview`), and
  **skipped `validateAndEnrichPrOutput`** (which only runs in the `PR_OUTPUT` sub-stage) — the
  sole place that persists the `github_pr` integration-link. Hence: no plan to review, a `prOutput`
  labeled as the plan phase, and no link despite a real PR.

### What already works (no change needed)

- The runner is fully stage-aware: `entrypoint.sh` branches sandbox + prompt by artifact type —
  `implementationPlan` → `read-only`, "produce a plan, do NOT modify files, Markdown to stdout";
  `prOutput` → `danger-full-access`, "implement the change". `runner.mjs build --stage` already
  emits all three artifact shapes. `map_stage` already accepts `implementation-plan` / `pr-output`.

## Scope

In:
1. Thread the `ExecutionSubStage` from the backend dispatch to the runner so the runner runs the
   correct phase: `IMPLEMENTATION_PLAN` → runner stage `implementation-plan` (read-only, emits
   `implementationPlan`, no push); `PR_OUTPUT` → runner stage `pr-output` (implements + pushes +
   `prOutput` + `github_pr` link).
2. The two-dispatch orchestration: approve spec → dispatch #1 (plan, read-only) → plan review →
   approve plan → dispatch #2 (PR, implement+push) → PR review → accept → Completed.

Out:
- Artifact availability wiring (#2b) and the review UI (#3).

## Design

### Thread the sub-stage to the runner

- The execution dispatch must carry the resolved `ExecutionSubStage`, not just `RunnerStage`.
  Options to evaluate during story design:
  - (preferred) add the sub-stage to the dispatch request the broker builds in
    `executeQueuedDispatch`, and have `DockerRunnerAdapter` set `DELIVERYLINE_RUNNER_STAGE` to
    `implementation-plan` / `pr-output` (the tokens `map_stage` already accepts) instead of the
    coarse `execution`. INVESTIGATION still maps to `spec-investigation` / `investigation`.
  - keep `request.stage()` as `RunnerStage` but add a `subStage` field; the adapter chooses the
    env token from the pair.
- The sub-stage is derived ONCE at dispatch (the broker already derives it for logging:
  `executeQueuedDispatch execution sub-stage derived ... subStage=IMPLEMENTATION_PLAN`). Reuse that.
- Net: `IMPLEMENTATION_PLAN` dispatch produces a read-only `implementationPlan` (no commit/push);
  `PR_OUTPUT` dispatch produces a `prOutput` (commit/push/PR), and the broker's
  `validateAndEnrichPrOutput` runs (persisting the `github_pr` link).

### Two-dispatch orchestration

- Spec approval already triggers `dispatchPlanGeneration` (observed). With #1 the first execution
  dispatch genuinely runs the plan phase.
- **Open question to resolve in story design:** does approving the implementation plan
  (`TechnicalApprovalService.acceptImplementation` on an `implementationPlan` → `Executing`)
  already re-dispatch a second execution run (the PR phase)? Today only ONE execution dispatch
  happens because the runner always does `prOutput`. The PR-phase re-dispatch on plan-approval
  must exist or be added so the flow reaches the PR sub-stage. Trace
  `WorkflowOrchestrationService.onPlanStageSucceeded` / the accept-plan transition and the
  dispatch trigger.

### Runner

- No runner-image change expected (stage-aware logic + `map_stage` tokens already present). Confirm
  the conformance ITs cover an `implementation-plan` dispatch producing an `implementationPlan`
  with `read-only` sandbox (no repo writes) and a `pr-output` dispatch producing a `prOutput`.

## Likely story breakdown

- Story A — backend: thread `ExecutionSubStage` → runner stage token (dispatch + adapter), with
  unit/IT coverage that each sub-stage dispatches the correct runner stage.
- Story B — backend: plan-approval re-dispatches the PR phase (if not already wired); the full
  spec→plan→PR→complete state walk green in an orchestration IT.
- Story C (if needed) — runner conformance ITs for the two execution sub-stages.

## Verification

- An execution IT walks: approve spec → plan dispatch emits `implementationPlan` (no push) →
  `WaitingForReview` → accept plan → PR dispatch emits `prOutput` + pushes + persists `github_pr`
  link → `WaitingForReview` → accept → `Completed`.
- Regression: investigation/spec path unchanged.

## Risks / notes

- Depends on #2b for the artifacts to be `available` (review gates). #1 + #2b together make a
  coherent two-phase flow; #3 makes it usable in the UI.
- The runner-image rebuild chain (codex/claude `:latest` are hand-built) applies if any runner
  change IS needed — see the stale-image / real-run enablement notes.
