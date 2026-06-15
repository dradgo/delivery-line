# Sprint Change Proposal — 2026-05-26

**Project:** DeliveryLine
**Triggering Epic:** Epic 2 (active) — sequencing pivot affecting Epic 3 pull-forward
**Trigger Source:** Project Lead strategic pivot (post story 2-13 close) — "start using the app sooner with real agent execution against the git project"
**Author:** Alex (Project Lead) + Claude (facilitating via `bmad-correct-course`)
**Status:** Approved (all four edits accepted by Project Lead 2026-05-26)
**Mode:** Incremental review

---

## 1. Issue Summary

After closing story 2-13 (REST mutation endpoints + OpenAPI), the Project Lead
called a sequencing pivot. The current plan defers real agent execution to all of
Epic 3 (36 stories) and defers the spec-viewing UI to mid-Epic-2. The pivot's
goal: walk the full real-world scenario **"submit a real Linear ticket → real
agent generates a spec against the git project → open the UI and read the
spec"** as early as possible, even at the cost of formal epic ordering.

**Issue category:** Strategic pivot — no defect, no missed requirement, no
rollback needed. Pure execution-order change.

**Concrete problem statement:**

- Story 1-13 ships only a `MockRunnerAdapter`. Real agent execution is currently
  zero. To exercise the real scenario the user wants, several Epic 3 stories
  must move earlier.
- Story 2-8 (spec-stage context bundle, done) deliberately omits repository
  content. Story 3-10 (full implementation-stage bundle) only addresses the
  plan + PR stages — there is **no story today** that gives the spec runner
  access to the git working tree.
- Story 3-11 wires `dispatchPlanGeneration` on `WaitingForSpecApproval → Executing`.
  There is **no story today** that wires `dispatchSpecGeneration` on `Inbox →
  Investigating`. Today only test code dispatches the mock spec runner directly.
- The viewing UI (queue item, context strip, artifact review panel) is on the
  Epic 2 critical path but currently backlog. Story 2-17 (the spec viewer) has
  a hard `dependency-edges` block on story 2-24 (sanitization + F19/F20 redaction
  closure).
- The full Epic 2 PM-loop UX (clarification region, decision bar, full a11y
  audit, responsive design, etc.) is not strictly needed to **view** a spec —
  approve/reject decisions can stay on the CLI (story 2-13 endpoints are done)
  while the UI ships in a view-only state.

**Evidence:**

- `sprint-status.yaml` lines 50–225 — Epic 1 done; Epic 2 backend done; Epic 2
  frontend post-scaffolding (2-15…2-29) all `backlog`; Epic 3 entirely `backlog`.
- `epic-03-agent-execution.md` story 3-1 AC1, AC4: Docker adapter and mock
  adapter share the `RunnerAdapter` port; profile-switched. No code change to
  the broker layer required to swap.
- `epics.md` story 2-8 AC3: `spec-investigation` bundle explicitly excludes
  approved-spec ref and contains no repo-context field.
- `epics.md` story 3-11 AC1: plan-generation orchestration is the *only*
  documented orchestration trigger that auto-dispatches a runner. Spec-stage
  trigger is implicit (in mock test code).
- `epics.md` story 2-15 "Dependency" note (line 1209) and story 2-17 "Dependency"
  note (line 1252): both block on story 2-24 via CI `dependency-edges` check.

---

## 2. Impact Analysis

### 2.1 Epic Impact

This proposal **splits two active/upcoming epics** into early-slice + deferred
halves. Story IDs remain stable (cross-references in dozens of story specs
continue to resolve); the split is structural and shows up in
`sprint-status.yaml`, the epic-headers, and the new sub-epic labels.

**Epic 2 splits into 2a + 2b:**

- **Epic 2a — Early-Usable PM Loop (active slice)**
  - Already done (no change): 2-1 … 2-13, 2-30, 2-31, 2-32
  - Pulled into active slice: **2-14, 2-15, 2-16, 2-17, 2-20, 2-22, 2-24**
  - Goal: backend mutation endpoints (done) + view-only spec UI shipped
- **Epic 2b — Full PM-Loop UX (deferred until 2a + 3a close)**
  - Stories: 2-18, 2-19, 2-21, 2-23, 2-25, 2-26, 2-27, 2-28, 2-29
  - Goal: move approve/reject/clarify decisions from CLI into the UI; complete
    a11y audit, responsive design, full frontend test suite, bundled-jar smoke
    and walkthrough docs

**Epic 3 splits into 3a + 3b:**

- **Epic 3a — Real Agent + Repo Stack (active slice)**
  - Pulled forward from Epic 3: **3-1, 3-2, 3-3, 3-4, 3-5, 3-6, 3-9, 3-13, 3-14**
  - Net-new stories: **3a-1 (Spec-stage orchestration)**, **3a-2 (Spec-stage
    repo-context bundle extension)**
  - Goal: real Codex + Claude runners producing real spec artifacts against the
    cloned git repo
- **Epic 3b — Implementation Output + Dev Review (deferred until 3a + 2a close)**
  - Stories: 3-7, 3-8, 3-10, 3-11, 3-12, 3-15 … 3-36
  - Goal: extend the spec-stage path to implementation-plan + PR/output stages,
    add ELK, dev-review services and UI, queue/worker pool, GitHub PR linkage
    display, retro doc increment

**Epics 4, 5, 6 — no impact.** Story 6-9 was already pulled forward and is done.

### 2.2 Story Impact

| Bucket | Story | Current status | Proposed status | Notes |
|---|---|---|---|---|
| Epic 2a pulls | 2-14 Allowed-actions endpoint | backlog | active-slice next | consumed by 2-17 |
| Epic 2a pulls | 2-15 RunReviewQueueItem | backlog | active-slice | depends on 2-24 |
| Epic 2a pulls | 2-16 RunContextStrip | backlog | active-slice | — |
| Epic 2a pulls | 2-17 ArtifactReviewPanel (spec variant) | backlog | active-slice | depends on 2-24, reads `useAllowedActions` (2-14) |
| Epic 2a pulls | 2-20 Queue shell states | backlog | active-slice | parent of 2-15 |
| Epic 2a pulls | 2-22 Navigation + state primitives | backlog | active-slice | prerequisite of 2-15/2-16/2-17 |
| Epic 2a pulls | 2-24 Sanitization + F19/F20 redaction closure | backlog | active-slice **early** | hard prerequisite of 2-15/2-17/2-18 |
| Epic 2b deferred | 2-18, 2-19, 2-21, 2-23, 2-25, 2-26, 2-27, 2-28, 2-29 | backlog | deferred (Epic 2b) | unchanged contents; deferred until 2a + 3a close |
| Epic 3a pulls | 3-1 DockerRunnerAdapter core | backlog | active-slice | profile-switched alongside `MockRunnerAdapter` |
| Epic 3a pulls | 3-2 DockerRunnerAdapter lifecycle | backlog | active-slice | required to prevent leaked containers + enforce timeouts |
| Epic 3a pulls | 3-3 Codex runner image | backlog | active-slice | |
| Epic 3a pulls | 3-4 Claude runner image | backlog | active-slice | parity testing with Codex |
| Epic 3a pulls | 3-5 Runner secrets handling | backlog | active-slice | |
| Epic 3a pulls | 3-6 Runner logs capture + redaction | backlog | active-slice | extends story 1.10 redaction policy to runner output |
| Epic 3a pulls | 3-9 RepositoryWorkspaceService | backlog | active-slice | scope tightened in story 3a-2 — see below |
| Epic 3a pulls | 3-13 Mock GitHub adapter | backlog | active-slice | |
| Epic 3a pulls | 3-14 Real GitHub adapter | backlog | active-slice | PR/branch/commit refs + PAT auth |
| Epic 3a new | **3a-1 Spec-stage orchestration (`dispatchSpecGeneration`)** | — | new, active-slice | analog of 3-11 but for `Inbox → Investigating`; net-new gap |
| Epic 3a new | **3a-2 Spec-stage repo-context bundle extension** | — | new, active-slice | extends story 2-8 spec-investigation bundle; net-new gap |
| Epic 3b deferred | 3-7, 3-8, 3-10, 3-11, 3-12, 3-15 … 3-36 | backlog | deferred (Epic 3b) | unchanged contents |

**Two stories also need scope-clarification updates (not full rewrites):**

- **Story 3-9** — currently described as the prep step for the implementation
  stage. With the pivot, story 3-9 must also serve the spec stage. Update: add
  a "Stages served" section to the story body documenting `spec-investigation`
  is now a primary consumer (alongside the planned `implementation-plan` and
  `pr-output` stages). No AC changes — the workspace lifecycle is identical
  per stage.
- **Story 2-24** — no AC changes; just an ordering note saying it ships *first*
  inside Epic 2a so the dependency-edges block clears before 2-15/2-17 work
  begins.

### 2.3 Artifact Conflicts

| Artifact | Conflict? | Action |
|---|---|---|
| PRD (`prd.md`) | No conflict | No change |
| Epics (`epics.md`, `epic-03-…md`) | Structural — need epic-header split + ordering note + 2 new story entries | Edit `epic-03-agent-execution.md` header (add 3a/3b split note); add 3a-1 + 3a-2 story sections in `epics.md` or a new file; touch `epics.md` Epic 2 header for the 2a/2b split note |
| Architecture (`architecture.md`) | Minor — spec-stage orchestration pattern + repo-context-in-spec-bundle data-flow are not currently documented | Add ADR `docs/adr/0004-spec-stage-orchestration.md`; touch §"Integration Points → Data Flow" step 3 to note repo workspace ref in spec-stage bundles |
| UX (`ux-design-specification.md`) | No conflict | No change |
| Sprint Status (`sprint-status.yaml`) | Significant — needs 2a/2b/3a/3b section split + active-slice ordering markers | Restructure the file's section comments; add `epic-2a`, `epic-2b`, `epic-3a`, `epic-3b` summary lines; mark the 17 active-slice stories as `next` (status remains `backlog` until each story enters the cycle) |
| Deferred Work (`deferred-work.md`) | Indirect — F19/F20 entries will close when 2-24 ships | Add forward-pointer note that 2-24 close will be the F19/F20 closure event (no proactive edit needed yet) |
| Implementation Readiness Reports | No conflict | No change — the 2026-04-26-v2 report covers Epic 2; readiness for 2a stories is already validated. Epic 3a will need its own readiness check before the first 3a story enters cycle |

### 2.4 Technical Impact

- **Code:** ~10 net-new backend modules (`adapters.runner.docker.*`, `application.runner.workspace.*`, runner Dockerfiles in `runners/codex/` + `runners/claude/`, GitHub adapter implementations, two orchestration services, spec-stage bundle extension) + ~7 frontend feature directories under `src/features/workflows/`.
- **Infrastructure:** Docker Desktop / Docker Engine required for real runner work (already in supported-environment matrix from story 1-17). Existing PostgreSQL + compose setup unchanged.
- **Deployment:** No change to the bundled-jar story (still deferred to 2-28 inside Epic 2b).
- **CI:** Tier addition — once 3-1/3-3/3-4/3-9 land, CI's runner-contract-real job (currently planned in story 3-8) is **not** pulled forward (3-8 stays in Epic 3b). Active-slice CI keeps the existing fast/contract/integration tiers; real-runner-contract tests run locally during 3a but full CI coverage waits for 3-8.
- **Secrets:** Pilot installer needs `ANTHROPIC_API_KEY` and/or `CODEX_API_KEY` and a `GITHUB_TOKEN` in `.env` once 3-5 + 3-14 land. Documented in `.env.example` per story 3-5 AC7.

---

## 3. Recommended Approach

### 3.1 Path-forward evaluation

| Option | Viable? | Effort | Risk | Verdict |
|---|---|---|---|---|
| **Option 1 — Direct Adjustment** (re-sequence existing + add 2 new stories) | ✅ Yes | Medium | Low–Medium | **Recommended** |
| Option 2 — Rollback completed work | ❌ No | N/A | N/A | Nothing already shipped conflicts with the new direction |
| Option 3 — PRD MVP review | ❌ No | N/A | N/A | Scope is unchanged; this is pure sequencing |

### 3.2 Recommendation: Option 1 — Direct Adjustment

Split Epic 2 into 2a + 2b and Epic 3 into 3a + 3b. Pull 15 existing backlog
stories forward into the 2a/3a active slice. Add 2 net-new stories (3a-1
spec-stage orchestration, 3a-2 spec-stage repo bundle extension) covering the
gaps not represented in any current epic. PRD, UX, and architecture
remain in scope; architecture takes one new ADR and one data-flow note.

### 3.3 Rationale

- **Lowest-cost path to the user-visible goal.** No rework of completed stories,
  no PRD scope change, no rollback. The new stories address real gaps that
  would otherwise surface during 3a implementation anyway.
- **Preserves all original Epic 2 + Epic 3 scope.** 2b + 3b will close before
  pilot launch (Epic 6). The split is sequencing, not subtraction.
- **Story-id stability.** Every existing story keeps its current identifier;
  the only renumbering is the introduction of 3a-1 / 3a-2 as new IDs.
- **Foundation contracts unchanged.** The 17 pulled stories all build on
  contracts already established in Epics 1 + 2 (runner-contracts v1, artifact
  operations, redaction policy, REST mutations, allowed-actions plan).
- **CI safety preserved.** The hard `dependency-edges` block (2-24 → 2-15/2-17)
  remains enforced; the active slice respects it by sequencing 2-24 first.
- **Cross-runner contract validation lands on day one.** Pulling both 3-3 and
  3-4 (rather than only one) gives the runner-contract pattern two independent
  implementations from the start — matching the architecture's "runners share
  one contract" principle and the party-mode finding on cross-runner parity.

### 3.4 Effort + timeline estimate

- **Backend slice (10 stories — 3-1, 3-2, 3-3, 3-4, 3-5, 3-6, 3-9, 3-13, 3-14, 3a-1, 3a-2)**
  — comparable to ~½ of Epic 1's backend volume. Estimate: **8–12 working sessions**
  if proceeding sequentially per the BMad story cycle (CS → VS → DS → CR per story).
- **Frontend slice (7 stories — 2-14, 2-15, 2-16, 2-17, 2-20, 2-22, 2-24)**
  — comparable to ~⅓ of remaining Epic 2 volume. Estimate: **6–9 working sessions**.
- **Total active-slice estimate: 14–21 sessions** before the user can walk
  the full scenario end-to-end.
- The two slices can partially parallelize: 2-14 + 2-22 + 2-24 (backend
  endpoint + frontend infrastructure + sanitization) can land while 3-1
  through 3-6 are in flight; 2-15/2-16/2-17 land after 2-24 + 2-14 are in.

### 3.5 Risks + mitigations

- **Risk: Docker runner work blocked on local Docker availability.** Mitigation:
  story 1-16's `doctor` command already detects Docker availability and
  emits `DOCTOR_DOCKER_MISSING`; pilot installer guidance is in place. The mock
  runner remains the default profile so non-Docker development continues.
- **Risk: Two net-new stories (3a-1, 3a-2) lack peer-reviewed AC sets.**
  Mitigation: `bmad-create-story` ensures both stories receive the standard
  contextual story generation, validation, and review cycle before entering
  development. Both stories' scope is well-bounded by analogs already in the
  plan (3a-1 ≈ 3-11; 3a-2 ≈ 3-10 + 2-8 hybrid).
- **Risk: Pulling 3-14 (real GitHub adapter) forward exposes pilot to GitHub
  API rate limits + PAT auth + branch-protection edge cases earlier than
  planned.** Mitigation: 3-13 (mock adapter) ships in the same slice; tests +
  contract validation run primarily against the mock. The real adapter's
  failure paths are already enumerated in story 3-9 AC7 (push-rejected,
  branch-protection violation, etc.) and surface as typed errors, not crashes.
- **Risk: 2-24's F19/F20 backend redaction closure is broader-scoped than
  pure sanitization.** Mitigation: this was already part of 2-24's scope per
  the 2026-05-19 sprint change proposal; no new work is being added — only
  sequencing is changing.

---

## 4. Detailed Change Proposals

### 4.1 Edit: `sprint-status.yaml` — restructure for 2a/2b + 3a/3b

**Section: `development_status` (lines 50–225)**

Add epic-split summary lines for `epic-2a`, `epic-2b`, `epic-3a`, `epic-3b`;
add `next` ordering markers on the 17 active-slice stories (preserves `backlog`
status — the marker is purely an ordering hint until the story enters the
cycle); leave all completed stories untouched.

**OLD (excerpt, line 83 onward):**

```yaml
  epic-2: in-progress
  2-1-frontend-module-scaffolding-…: done
  …
  2-13-backend-rest-mutation-endpoints-…: done
  2-14-backend-allowed-actions-inspection-endpoint: backlog
  2-15-run-review-queue-item-component: backlog
  …
  2-29-pm-loop-walkthrough-…: backlog
  2-30-…: done
  2-31-…: done
  2-32-…: done
  epic-2-retrospective: optional
```

**NEW (excerpt):**

```yaml
  # Epic 2 split into 2a (early-usable slice) + 2b (full PM-loop UX) per
  # sprint-change-proposal-2026-05-26.md
  epic-2a: in-progress  # early-usable slice — view-only spec UI
  2-1-frontend-module-scaffolding-…: done
  …
  2-13-backend-rest-mutation-endpoints-…: done
  2-30-…: done
  2-31-…: done
  2-32-…: done
  # active-slice pulls — order: 2-24 first (unblocks 2-15/2-17), then 2-14, 2-22, 2-20, 2-16, 2-15, 2-17
  2-24-artifact-content-sanitization-untrusted-runner-output: backlog  # next-active (1st in 2a slice)
  2-14-backend-allowed-actions-inspection-endpoint: backlog  # next-active
  2-22-navigation-and-empty-loading-error-states-infrastructure: backlog  # next-active
  2-20-queue-shell-states-…: backlog  # next-active
  2-16-run-context-strip-component: backlog  # next-active
  2-15-run-review-queue-item-component: backlog  # next-active
  2-17-artifact-review-panel-generalized-composite-spec-variant: backlog  # next-active

  epic-2b: deferred  # full PM-loop UX — clarifications, decision bar, full a11y, responsive, bundled jar smoke, docs
  2-18-clarification-region-…: backlog  # epic-2b
  2-19-approval-decision-bar-…: backlog  # epic-2b
  2-21-feedback-patterns-infrastructure-…: backlog  # epic-2b
  2-23-modal-overlay-confirmation-patterns-…: backlog  # epic-2b
  2-25-wcag-2-1-aa-compliance-…: backlog  # epic-2b
  2-26-responsive-design-…: backlog  # epic-2b
  2-27-frontend-test-suite-…: backlog  # epic-2b
  2-28-spa-fallback-controller-…: backlog  # epic-2b
  2-29-pm-loop-walkthrough-…: backlog  # epic-2b
  epic-2-retrospective: deferred  # ran at end of 2b
```

And similarly for Epic 3:

```yaml
  # Epic 3 split into 3a (real agent + repo stack for spec stage) + 3b
  # (implementation output + dev review + remaining infra) per
  # sprint-change-proposal-2026-05-26.md
  epic-3a: backlog  # active-slice — real agent + repo for spec stage
  # active-slice pulls — order roughly: 3-1, 3-2, 3-5 ; 3-3 + 3-4 ; 3-6 ; 3-13 + 3-14 ; 3-9 ; 3a-1 ; 3a-2
  3-1-docker-runner-adapter-core-…: backlog  # next-active (epic-3a, first)
  3-2-docker-runner-adapter-lifecycle-…: backlog  # next-active (epic-3a)
  3-5-runner-secrets-handling-…: backlog  # next-active (epic-3a)
  3-3-codex-runner-image-…: backlog  # next-active (epic-3a)
  3-4-claude-runner-image-…: backlog  # next-active (epic-3a)
  3-6-runner-logs-capture-and-redaction-…: backlog  # next-active (epic-3a)
  3-13-mock-github-adapter: backlog  # next-active (epic-3a)
  3-14-real-github-adapter-…: backlog  # next-active (epic-3a)
  3-9-repository-workspace-service-…: backlog  # next-active (epic-3a)
  3a-1-spec-stage-orchestration-dispatch-spec-generation: backlog  # next-active (epic-3a, NEW)
  3a-2-spec-stage-repo-context-bundle-extension: backlog  # next-active (epic-3a, NEW)

  epic-3b: deferred  # rest of Epic 3 — implementation + dev review
  3-7-elk-stack-integration-…: backlog  # epic-3b
  3-8-real-docker-runner-contract-integration-test: backlog  # epic-3b
  3-10-full-context-bundle-generation-for-implementation-stage: backlog  # epic-3b
  3-11-implementation-plan-artifact-generation-flow-orchestration: backlog  # epic-3b
  3-12-pr-output-artifact-generation-flow-orchestration: backlog  # epic-3b
  3-15-…3-36: backlog  # epic-3b
  epic-3-retrospective: deferred  # ran at end of 3b
```

**Rationale:** Establishes a single source of truth for slice membership and
ordering. Story-cycle skills (CS, VS, DS, CR) will key on the `next-active`
markers when picking the next story.

---

### 4.2 Edit: `epics.md` — add 2a/2b + 3a/3b section headers; add 2 new story stubs

**Action 1:** Add a section header comment above the Epic 2 story list
documenting the 2a / 2b split, referencing this proposal. Same for Epic 3.

**Action 2:** Append two new story stubs at the end of the Epic 3 stories
section in `epic-03-agent-execution.md`. The stubs carry a working title +
one-line goal + dependencies + "Full AC set drafted by `bmad-create-story`"
note — the actual AC set is filled in when each story enters the cycle.

**OLD (`epic-03-agent-execution.md` end-of-file area):**

```markdown
### Story 3.36: Execution Walkthrough Documentation Increment
…
```

**NEW (`epic-03-agent-execution.md` end-of-file area):**

```markdown
### Story 3.36: Execution Walkthrough Documentation Increment
…

---

## Epic 3 Sequencing Update (2026-05-26)

Per `sprint-change-proposal-2026-05-26.md`, Epic 3 is split into:

- **Epic 3a — Real Agent + Repo Stack (active-slice):** 3-1, 3-2, 3-3, 3-4,
  3-5, 3-6, 3-9, 3-13, 3-14, plus net-new stories 3a-1 + 3a-2 below.
- **Epic 3b — Implementation Output + Dev Review (deferred):** 3-7, 3-8, 3-10,
  3-11, 3-12, 3-15…3-36.

### Story 3a-1: Spec-Stage Orchestration — `dispatchSpecGeneration`

As a backend developer + workflow orchestrator,
I want `WorkflowOrchestrationService.dispatchSpecGeneration(workflowRunId)`
invoked on the `Inbox → Investigating` state transition — analogous to story
3-11's `dispatchPlanGeneration` but for the spec stage,
So that ticket submission via story 1-15 / 6-9 / 2-13 automatically dispatches
a real runner against the spec-stage context bundle (story 2-8 + 3a-2 below),
producing a spec artifact and transitioning to `WaitingForSpecApproval` — instead
of relying on test code dispatching the mock runner directly.

**Dependencies:** 3-1 (DockerRunnerAdapter), 3a-2 (spec-stage repo-context bundle),
2-8 (spec artifact model), 1-13 (RunnerBroker), 1-5 (state-transition table).

**Full AC set:** Drafted by `bmad-create-story` when this story enters the cycle.
Acceptance criteria will follow the shape of story 3-11 AC 1–11 (orchestration
service method, runner result → artifact ingest, auto-state-transition on success,
failure-category routing, retry path, idempotency, correlation propagation,
ArchUnit boundary). The differences vs. 3-11: trigger state (`Inbox → Investigating`
not `WaitingForSpecApproval → Executing`), target artifact type (`spec` not
`implementationPlan`), success transition (`WaitingForSpecApproval` not
`WaitingForReview`).

### Story 3a-2: Spec-Stage Repo-Context Bundle Extension

As a backend developer,
I want `ContextBundleService.create(workflowRunId, stage='spec-investigation')`
extended to include a reference to the cloned repository working tree (from
story 3-9 `RepositoryWorkspaceService`) and a curated repo summary (top-level
tree, README content, package/config files) — extending the bundle defined in
story 2-8,
So that the spec runner has actual codebase context when generating a spec
against a real Linear ticket — closing the gap between "ticket text" and
"actual git project" that story 2-8 alone left open.

**Dependencies:** 2-8 (spec artifact model + spec-stage bundle baseline), 3-9
(RepositoryWorkspaceService — repo clone + mount), 1-10 (redaction policy —
applies to bundled repo content), 1-6 (runner-contracts schema — bundle
version + validation).

**Full AC set:** Drafted by `bmad-create-story` when this story enters the cycle.
Acceptance criteria will follow the shape of story 3-10 AC 1–10 adapted for the
spec stage: extend the `context-bundle.v1.schema.json` bundle composition to
include `repositoryWorkspaceRef`, `repositoryTreeSummary`, `repositoryReadmeRef`,
`packageManifestRefs`; redaction adversarial tests assert no secrets / absolute
machine paths in persisted bundle; schema validation + classification rules
unchanged.
```

**Action 3:** Touch `epics.md` Epic 2 header area to add a 2a/2b split note
(non-breaking; comment-style markdown).

**Rationale:** Captures the structural split in the document that
`bmad-create-story` reads when sourcing story context.

---

### 4.3 Edit: `architecture.md` — add data-flow note for spec-stage repo workspace ref

**Section: "Integration Points → Data Flow" (lines 1297–1306)**

**OLD (line 1301):**

```markdown
3. Runner broker creates context bundle.
```

**NEW (line 1301):**

```markdown
3. Runner broker creates context bundle. For spec-stage runs, the bundle
   carries a reference to a cloned repository workspace (per Epic 3a) so
   the spec runner has codebase context, not just ticket text.
```

**Rationale:** Documents the spec-stage repo-context handoff at the
architecture level. Companion ADR `docs/adr/0004-spec-stage-orchestration.md`
captures the full rationale.

---

### 4.4 New file: `docs/adr/0004-spec-stage-orchestration.md`

A short ADR documenting:

- **Decision:** spec-stage orchestration is wired via a `dispatchSpecGeneration`
  service method analogous to `dispatchPlanGeneration` (story 3-11). Both run
  through the same `WorkflowOrchestrationService` to keep the "only one path
  auto-advances workflow state on runner outcome" invariant intact (story
  3-11 AC9).
- **Decision:** the spec-stage context bundle includes a repository workspace
  reference, extending the bundle defined in story 2-8. This unifies the
  "agent reads the repo" capability across spec, plan, and PR stages instead
  of treating spec as a degenerate "ticket-text-only" case.
- **Trade-off considered:** alternative — pass repo context only via filesystem
  mount with no bundle reference. Rejected: violates the architecture rule
  that artifact-relevant references must be inspectable via `FR55` /
  `WorkflowInspectionService.getContextBundleForArtifact`.
- **Rejected alternative:** in-process Claude API runner adapter bypassing
  Docker entirely. Considered for speed-of-pilot but rejected by the Project
  Lead to keep the active slice aligned with the MVP architecture target.

---

## 5. Implementation Handoff

### 5.1 Scope classification

**Moderate** — Requires backlog reorganization (4-way epic split, 17 stories
re-sequenced, 2 new stories added) but no PRD changes and no completed-work
rollback.

### 5.2 Handoff recipients + responsibilities

- **Project Lead (Alex):**
  - Approve this proposal end-to-end (yes/no/revise)
  - Decide CS-cycle ordering for the 17 active-slice stories (recommended order
    in §4.1 above; can be re-ordered)
  - Ensure `ANTHROPIC_API_KEY` / `CODEX_API_KEY` / `GITHUB_TOKEN` are available
    in `.env` before stories 3-5 + 3-14 enter the cycle
- **Claude (this session):**
  - On approval: apply the four edits in §4 (sprint-status restructure, epics
    section header + 2 new story stubs, architecture data-flow note, new ADR
    file)
  - Hand off the next story (recommended: 2-24 — clears the dependency-edges
    block first; user may select different) to `bmad-create-story` in a fresh
    context window
- **`bmad-create-story` (downstream skill):**
  - Draft full AC sets for 3a-1 and 3a-2 when those stories enter the cycle
  - Validate readiness for each pulled-forward story (existing AC sets are
    already validated; readiness check is mostly a freshness pass)

### 5.3 Success criteria

The pivot succeeds when the Project Lead can:

1. Run `deliveryline submit --ticket LIN-XXX` against a real Linear ticket
   referencing the DeliveryLine GitHub repo.
2. Observe a real Codex or Claude runner container execute, read the cloned
   repo working tree, and produce a `spec` artifact.
3. Open the React UI at `http://127.0.0.1:8080`, see the run in the queue,
   click into it, and read the generated spec artifact rendered safely via
   the sanitization pipeline.
4. Approve or reject the spec from the CLI (`deliveryline approve-spec …` /
   `deliveryline reject-spec …`) — full in-UI approval lands in Epic 2b.

---

## 6. Open Questions

None at proposal draft time. All earlier branching (Docker vs in-process,
Codex vs Claude, GitHub-adapter scope, view-only vs full-PM-loop) was
resolved interactively during this skill's execution (questions answered
2026-05-26).

---

## 7. Approval

| Reviewer | Role | Status | Date |
|---|---|---|---|
| Alex | Project Lead | ✅ Approved | 2026-05-26 |

**Edits applied 2026-05-26:**

1. ✅ §4.1 `_bmad-output/implementation-artifacts/sprint-status.yaml` — restructured into Epic 2a / 2b / 3a / 3b sections with `next-active` ordering markers; 2-13 status cleanup `review → done`; trailing `last_updated` note added.
2. ✅ §4.2 `_bmad-output/planning-artifacts/epic-03-agent-execution.md` — Epic 3 header now carries the 3a/3b sequencing note; appended story stubs **3a-1 Spec-Stage Orchestration** and **3a-2 Spec-Stage Repo-Context Bundle Extension** with dependencies + AC-shape references.
3. ✅ §4.3 `_bmad-output/planning-artifacts/architecture.md` — Integration Points → Data Flow step 3 updated with spec-stage repo-workspace-ref note (cross-links to ADR 0004).
4. ✅ §4.4 `docs/adr/0004-spec-stage-orchestration.md` — created.

**Recommended next step:** Invoke `bmad-create-story` (action: `create`) in a fresh context window targeting **story 2-24** — the first frontend slice story; it clears the dependency-edges block for stories 2-15 and 2-17.

Backend track can start in parallel with **story 3-1** as soon as a separate session is available.
