# Spec — WaitingForReview availability (sub-project #2b)

Date: 2026-06-16
Status: approved (design); pending spec review

## Context

Sub-project #2 of the larger "Option X" effort (align the runner two-phase EXECUTION
contract + a full WaitingForReview review experience). This spec covers **only #2b**: make
the execution-stage artifact `available` on ingest and make it visibly surface on the
`WaitingForReview` screen. Decision buttons firing and a proper PR/diff renderer are
**deferred to #3**.

### How this emerged (verified facts)

- Live run `run_ae258aa42f524ba29db3c795732a21e6` reached `WaitingForReview`. Its execution
  runner emitted a `prOutput` artifact (real change: `docs/` files; branch
  `deliveryline/FIN-21/stage-732a21e6` pushed; **draft PR #2** opened on
  `dradgo/financemonitor.2019`).
- The `prOutput` artifact is `status=pending`. Only `spec` is marked `available` today
  (`RunnerBroker.markSpecArtifactAvailable`, the ingest loop at `RunnerBroker.java:1342`).
  `acceptImplementation` requires `isApprovalEligible` = AVAILABLE, so accept is currently
  impossible for a `prOutput`.
- The detail route (`routes/workflows/$workflowRunId/index.tsx`) renders a **static
  placeholder paragraph** + an **"Open the specification →"** link built ONLY from
  `resolveSpecArtifactId` (spec only). Artifact bodies live on the separate artifact-viewer
  route. There is **no link to the implementation/PR artifact**.
- Current frontend source ALREADY routes `WaitingForReview → ImplementationReviewDecisionBarContainer`
  (`WorkflowDecisionBar.tsx`) and `resolveImplementationArtifact` already prefers `prOutput`.
  The screenshot showed the `spec_approval` card instead — proving the **SPA bundle embedded
  in the running app is stale** (the embedded SPA is copied in only at `mvn package`).

## Scope

In:
1. Backend: mark `implementationPlan` and `prOutput` artifacts `available` on ingest.
2. Frontend: surface a state-aware "Open the implementation output →" link at
   `WaitingForReview` using the existing `resolveImplementationArtifact`.
3. Rebuild + re-embed the SPA so the running app picks up (2) and the already-built
   implementation_review bar.

Out (→ #3):
- Decision buttons actually firing (needs developer-role wiring — "one user, multiple roles").
- A proper `prOutput` renderer (PR link + unified diff). The generic artifact viewer may render
  a raw `prOutput` JSON as `error` (it may not satisfy the `isArtifactView` guard) — acceptable
  for #2b; #3 owns the real renderer.

Out (→ #1):
- Threading `ExecutionSubStage` to the runner so the plan phase runs read-only and the
  github_pr link persists. #2b does NOT change runner behavior or the sub-stage mismatch.

## Design

### Backend — availability on ingest

Generalize the spec-only availability wiring to all runner-produced artifact types.

- In `RunnerBroker.onResult`'s ingest loop (around `:1342`), after the artifact `CREATE`
  succeeds, mark the artifact `available` for `SPEC`, `IMPLEMENTATION_PLAN`, and `PR_OUTPUT`
  (today only `SPEC`). Reuse the `markSpecArtifactAvailable` mechanism (checksum over the same
  payload bytes handed to `recordOperation` + the storageRef the payload store reported);
  rename/generalize it to `markArtifactAvailable` (no spec-specific assumptions).
- `prOutput` interaction with the 3.12 enrich `UPDATE`: the enrich runs only in the
  `PR_OUTPUT` sub-stage. Marking available in the ingest loop happens before that. Verify the
  enrich `UPDATE` does not revert status to `pending`; if it does, re-mark available after
  `enrichPrOutputArtifact` so the enriched version is the available one. In today's
  plan-sub-stage path the enrich does not run, so in-loop marking already suffices.
- Keep the auto-advance (`onPlanStageSucceeded` / `onPrOutputStageSucceeded`) unchanged — it
  fires on ingest regardless of availability, exactly as spec does.

Tests (failing-first):
- `RunnerBrokerUnitTest`: an ingested `prOutput` (and `implementationPlan`) ends `available`.
- `PrOutputOrchestrationIT` / `ImplementationPlanOrchestrationIT`: after a successful execution
  result, the artifact row is `status=available` and the run is `WaitingForReview`.
- Confirm no regression to the spec path (`SpecStageOrchestrationIT`).

### Frontend — surface the implementation artifact

- In `routes/workflows/$workflowRunId/index.tsx`, in addition to the spec link, render an
  "Open the implementation output →" link when `resolveImplementationArtifact(data)` resolves
  an artifact id (it prefers `prOutput`/`implementationPlan`, highest version). Gate the new
  link to the review context (e.g. show when an implementation artifact exists; it naturally
  appears once the execution stage has produced one). The link targets the existing
  `/workflows/$workflowRunId/artifacts/$artifactId` route.
- No change to the decision bar in #2b (it already routes to implementation_review in source;
  making its buttons fire is #3).

Tests (failing-first):
- A route/page test (or the existing detail-page test) asserting the implementation-artifact
  link renders when `latestArtifacts` carries a `prOutput` with an `artifactId`, and does not
  render when only a spec exists.

### Rebuild + re-embed

- `mvn package` rebuilds the SPA into the backend `static/` (story 2.1). After implementing,
  rebuild so the running app serves the current frontend (activating both the new link and the
  already-built implementation_review bar). Verify on the live `WaitingForReview` run.

## Verification

1. Backend tests green (artifact `available`; no spec regression).
2. Frontend test green (impl-artifact link renders at review).
3. Manual: re-run / re-open `run_ae258…` (or a fresh WaitingForReview run) after `mvn package`;
   confirm the screen offers a link to the implementation output and the artifact is `available`
   in the read model.

## Risks / notes

- The generic artifact viewer may not render a raw `prOutput` JSON cleanly (`isArtifactView`
  guard) — expected; #3 ships the real renderer. #2b's visible win is that the run surfaces the
  implementation artifact (available + linkable) instead of only the spec.
- Marking `prOutput` available is also a prerequisite for #3's accept flow (`isApprovalEligible`).
- This does not fix the runner sub-stage mismatch (#1) — the run still produced a `prOutput`
  during the plan sub-stage; the github_pr link is still absent (that's #1).
