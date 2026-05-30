# ADR 0004 — Spec-Stage Orchestration & Repo-Context Bundle

**Status:** Accepted (2026-05-26)
**Driver:** `sprint-change-proposal-2026-05-26.md` — Project Lead pivot to ship a usable end-to-end ticket→real-spec→view scenario as the active slice (Epic 2a + 3a).

## Context

Two gaps surfaced when planning the active slice for "submit a real Linear ticket → real agent generates a spec against the git project → view the spec":

1. **Implicit spec-stage orchestration.** Story 3.11 wires `dispatchPlanGeneration` on `WaitingForSpecApproval → Executing`, but no story today wires `dispatchSpecGeneration` on `Inbox → Investigating`. Today only test code dispatches the mock spec runner directly; there is no production path that auto-fires the spec runner on ticket submission.
2. **Repo content excluded from the spec-stage context bundle.** Story 2.8 AC3 explicitly omits any repository reference from the `spec-investigation` bundle (correctly — at that stage no prior spec exists, and 2.8 deliberately scoped narrowly). Story 3.10 extends the bundle for implementation-plan and pr-output stages. Nothing today carries codebase context into the spec stage.

Without addressing both, the spec agent has no way to read the git project — it would see only ticket text. That contradicts the active-slice goal.

## Decision

**1. Spec-stage orchestration is wired via `WorkflowOrchestrationService.dispatchSpecGeneration(workflowRunId)`** — analogous in shape and contract to `dispatchPlanGeneration` from story 3.11. Both run through the same `WorkflowOrchestrationService` so the architecture invariant "only one path auto-advances workflow state on runner outcome" (story 3.11 AC9) remains intact.

Implementation lives in net-new story **3a-1** (drafted by `bmad-create-story` when entered into the cycle). The AC set follows story 3.11's shape with these differences:

- Trigger transition: `Inbox → Investigating` (not `WaitingForSpecApproval → Executing`).
- Target artifact type: `spec` (not `implementationPlan`).
- Success transition: `WaitingForSpecApproval` (not `WaitingForReview`).
- Runner image selection: configurable per `application.yml` `deliveryline.runner.spec-stage.kind` (codex or claude).
- Retry path: `retrySpecGeneration(workflowRunId)`, idempotent branch reuse + fresh context bundle version.

**2. The spec-stage context bundle includes a repository workspace reference**, extending story 2.8's bundle definition. This unifies the "agent reads the repo" capability across spec, plan, and PR stages — instead of treating spec as a degenerate "ticket-text-only" case.

Implementation lives in net-new story **3a-2** (drafted by `bmad-create-story` when entered into the cycle). The bundle gains:

- `repositoryWorkspaceRef` — path to the runner-mounted working tree (from story 3.9 `RepositoryWorkspaceService`).
- `repositoryTreeSummary` — depth-limited top-level tree listing with file types.
- `repositoryReadmeRef` — artifact ref to redacted README content.
- `packageManifestRefs` — refs to redacted `package.json` / `pom.xml` / `Cargo.toml` / equivalents.
- `ticketRepositoryMappingVersion` — which Linear↔GitHub mapping resolved to this repo.

Existing story 2.8 AC3 fields remain in place; this is additive composition, not replacement.

## Alternatives Considered

### Alt 1 — Pass repo context only via filesystem mount, no bundle reference

The runner could read the mounted working tree directly without the bundle carrying a reference to it.

**Rejected.** Violates the architecture rule that artifact-relevant references must be inspectable via `WorkflowInspectionService.getContextBundleForArtifact(artifactId)` (FR55). Without the bundle reference, a reviewer inspecting a spec artifact would have no record of *which repo state* the agent was reading from — defeating audit traceability for the most important context input.

### Alt 2 — In-process Claude API runner adapter (no Docker)

A non-Docker runner adapter calling the Anthropic SDK directly from the JVM, bypassing story 3.1's Docker container lifecycle entirely. Would have replaced stories 3.1, 3.3, 3.4, 3.5, 3.6 with a single thin adapter — fastest to a walking demo.

**Rejected by Project Lead.** Considered for speed of pilot but the active slice is intended to align with the MVP target architecture, not introduce a temporary parallel path that 3.1's full Docker implementation would have to displace later. Pulling 3.1 + 3.3 + 3.4 + 3.5 + 3.6 forward preserves the architecture and produces a real Docker-runner demo at slightly higher up-front cost.

### Alt 3 — Skip GitHub adapter entirely, hardcode a local repo path

Pulling story 3.9 forward without 3.13 + 3.14 would have required either (a) a temporary "direct repo URL" shape on 3.9 or (b) deferring the Linear→repo lookup indirection.

**Rejected by Project Lead.** Same rationale as Alt 2 — pulling 3.13 + 3.14 alongside keeps story 3.9 in its planned shape rather than introducing throwaway code that 3.13/3.14 would refactor away.

## Consequences

### Positive

- The active slice (Epic 2a + 3a) walks the full real scenario end-to-end with no temporary scaffolding.
- The spec-stage path lands with the same architecture rigor as the plan-stage path (3.11) — single orchestration service, single bundle composition pattern, full FR55 inspectability.
- Story 3.9's scope generalizes naturally to serve all three stages (spec, plan, pr-output); the per-stage logic differs only in which artifacts the bundle references, not in lifecycle or contract.
- The cross-runner contract is validated against two implementations on day one (Codex + Claude pulled forward together).

### Negative

- Pilot installers need three secrets in `.env` (`ANTHROPIC_API_KEY` or `CODEX_API_KEY`, plus `GITHUB_TOKEN`) before the active slice is exercisable end-to-end. Story 3.5 documents this; story 1.16 `doctor` reports missing secrets.
- 3-9 + 3-13 + 3-14 pulled forward means the Linear→GitHub repo mapping must work before the user sees a real spec. The mock GitHub adapter (3-13) absorbs the test-side risk; the real adapter (3-14) carries the pilot risk.
- Two net-new stories (3a-1, 3a-2) add to the active-slice scope (10 backend pulls + 2 new = 12 backend stories total).

### Neutral

- Story 3.10 (full implementation-stage context bundle) remains in Epic 3b unchanged — it will reuse the bundle-composition pattern established here for the plan + PR stages.
- Story 3.11 (plan-generation orchestration) similarly remains in Epic 3b unchanged — 3a-1 sets the precedent it follows.

## Upgrade Triggers

This ADR's decisions should be revisited when any of the following changes:

- The MVP scope expands beyond a single repository per workflow run (the `ticketRepositoryMappingVersion` field assumes 1:1).
- The redaction-policy gap closure in story 2.24 reveals further patterns that affect bundle composition (the bundle composition feeds into the same hardened redactor).
- A future runner kind (other than Codex / Claude) requires bundle-composition fields not in the current shape — current schema is additive-friendly per story 1.6 unknown-field handling.

## References

- `sprint-change-proposal-2026-05-26.md` — driving change proposal.
- Story 2.8 — spec artifact model + spec-stage bundle baseline (existing, done).
- Story 3.9 — RepositoryWorkspaceService (active slice).
- Story 3.10 — implementation-stage bundle (deferred to Epic 3b — same composition pattern as 3a-2).
- Story 3.11 — plan-generation orchestration (deferred to Epic 3b — same orchestration pattern as 3a-1).
- `architecture.md` §"Integration Points → Data Flow" step 3 — companion data-flow update.
