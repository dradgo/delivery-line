# Plan — WaitingForReview review experience (prOutput)

## Goal
Make `WaitingForReview` a usable review surface: the operator (single user, developer role)
sees the prOutput (summary + PR link + unified diff) and can Accept / Reject / Takeover, wired
to the existing 3.20–3.25 endpoints.

## Root-cause recap (verified against live run run_ae258…)
- prOutput is ingested `pending`; only SPEC is marked `available` (`RunnerBroker.markSpecArtifactAvailable`, line 1342).
- `acceptImplementation` requires `isApprovalEligible` = AVAILABLE (line 205) → prOutput accept is **currently impossible** (would 503 ARTIFACT_PAYLOAD_UNAVAILABLE).
- For PR_OUTPUT, accept also gates on an active `github_pr` link (line 213). Live run has **only a `linear` link** → gate unsatisfiable.
- UI calls allowed-actions as `product_reviewer`; accept/reject/takeover exist only for `developer` → inert spec card ("No decision available").

## Decisions (locked)
1. Mark prOutput **available on ingest** (mirror spec).
2. Single operator carries the **developer** role → UI acts as developer at WaitingForReview.
3. **Full**: diff viewer + takeover.

## OPEN RISK — PR-link gate (needs decision before Accept can complete)
The codex stub makes no real git commit → no push → no `github_pr` link → `acceptImplementation`
PR_OUTPUT path fails `assertPrLinkPresentAndMatches` even after availability is fixed. Options:
- (A) Make the codex/claude runner produce a real change so captureAndPush pushes + the 3.15 enrich
  creates the github_pr link (most faithful; touches the runner image / real-run chain).
- (B) Relax the pilot PR-link gate (it's already a degraded "a link must exist" check per OQ-1) to
  also accept a prOutput whose payload carries a prReference, or skip when no repo push occurred.
- (C) Out of scope for this build: render + Reject + Takeover work without a PR link; only **Accept**
  is blocked. Ship review/reject/takeover now, track Accept-completion separately.

## Phases (TDD, failing test first each step)

### Phase 1 — Backend: prOutput available on ingest
- `RunnerBroker.onResult`: after the prOutput CREATE **and** the 3.12 enrich UPDATE, mark the
  prOutput `available` (checksum over payload bytes + storageRef), mirroring `markSpecArtifactAvailable`.
  Must run AFTER `enrichPrOutputArtifact` so the available version carries the actual refs (verify the
  enrich UPDATE doesn't reset status / creates the version that gets stamped).
- Tests: `RunnerBrokerUnitTest` (prOutput → available), `PrOutputOrchestrationIT` (artifact status=available + run WaitingForReview). Check `markavailable`-related contract tests.
- Watch: implementationPlan stays `pending` (no human gate) unless we later add plan review — keep scope to prOutput.

### Phase 2 — Backend: confirm/resolve PR-link gate (per OPEN RISK decision)
- Drive an IT through `acceptImplementation` on an available prOutput to see the gate fire, then
  apply the chosen option (A/B/C).

### Phase 3 — Frontend: developer-role allowed-actions at WaitingForReview
- Request allowed-actions (and send decisions) as `developer` when state = WaitingForReview
  (single-user-all-roles). Confirm accept/reject/takeover render.

### Phase 4 — Frontend: prOutput review panel
- Render prOutput: summary, PR link (reuse `githubRef.ts` + `PrStateBadge` from 3.31), unified diff
  (reuse `SafeUnifiedDiffRenderer` / `parseUnifiedDiff`). Stop falling back to spec when state is review.
- Wire Accept / Reject (taxonomy) / Takeover buttons to existing REST endpoints; echo the
  allowed-actions version stamp.
- Vitest coverage for the panel + the three actions; respect react-refresh export rule (helpers in .ts).

### Phase 5 — Verify end-to-end
- Rebuild, re-run the pilot (or seed a WaitingForReview run), confirm content renders + an action transitions state.

## Files (anticipated)
- BE: `application/runner/RunnerBroker.java` (+ helper), tests in `runner/RunnerBrokerUnitTest`, `workflow/PrOutputOrchestrationIT`, possibly `approval/TechnicalApprovalService*` if gate changes.
- FE: WaitingForReview review panel component(s), allowed-actions role wiring, queryOptions/api hooks, reuse 3.31 `githubRef`/`PrStateBadge`/`SafeUnifiedDiffRenderer`; vitest specs.

## Out of scope
- implementationPlan review stage (the stub skips it; revisit if real runners emit plans).
- Header-based role attribution (story 2.13) — single-user-all-roles for now.
