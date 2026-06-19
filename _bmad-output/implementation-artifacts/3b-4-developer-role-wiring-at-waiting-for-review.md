# Story 3b.4: Developer-Role Wiring at WaitingForReview

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the single operator (one user, multiple roles for now),
I want the UI to request allowed-actions as `developer` when `currentState === 'WaitingForReview'` and send the `developer` reviewer role on accept/reject/takeover calls,
so that the already-built `implementation_review` decision bar's actions appear and fire instead of being `blocked`/inert because the allowed-actions endpoint defaults to `product_reviewer` (which returns only `view_only` at `WaitingForReview`).

## Context & the ONE non-obvious trap (read first — it changes what you build)

This is sub-project **#3, Story A** of "Option X" (the full `WaitingForReview` review experience). #2b (3b-3, done) made `implementationPlan`/`prOutput` `available` and surfaced the impl-output link. The decision bar (`implementation_review` mode), the three decision hooks, and the backend accept/reject/takeover endpoints **already exist and are correct** (stories 3.20–3.28). The ONLY thing missing is the **role wiring**: nothing tells the backend "treat the current operator as `developer`," so:

1. `useAllowedActions` requests with **no `actorRole`** → backend defaults to `product_reviewer` → at `WaitingForReview` the matrix returns **`[view_only]`** → the bar's `canFire`/primary-action gate fails → bar renders **`blocked`/inert**.
2. The three decision hooks (`useAcceptImplementation`/`useRejectImplementation`/`useTakeoverWorkflow`) accept an optional `reviewerRole` but **no caller populates it** ("omitted today; accepted for forward-compat") → even if a user could click, the REST endpoints reject with `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` unless `reviewerRole == "developer"`.

So this story is **mostly frontend wiring**: request allowed-actions as `developer` at `WaitingForReview`, and send `reviewerRole: 'developer'` on the three decisions. The single operator carries the `developer` role at this state (the user's "one user, multiple roles for now" decision). Keep the wiring **isolated** so a future story-2.13 header-based role attribution swaps in cleanly.

### ⚠️ THE TRAP: the typed client CANNOT send `actorRole=developer` until the OpenAPI enum advertises it

This is the single thing that will be implemented wrong (or get stuck) if you skip this section. The naive change ("pass `actorRole: 'developer'` from `useAllowedActions`") **does not compile**, because:

- The backend `getAllowedActions` query param is annotated `@Schema(allowableValues = {"product_reviewer", "workflow_owner"}, ...)` (`WorkflowController.java:330`) — it **omits `developer`**, even though the *service* recognizes `developer` (`WorkflowInspectionService.RECOGNIZED_ACTOR_ROLES`) and the matrix already returns the developer action set at `WaitingForReview`.
- That annotation generates the committed OpenAPI snapshot (`openapi.json:1597-1600`: `enum: ["product_reviewer","workflow_owner"]`) and the generated frontend client type (`schema.d.ts:982`: `actorRole?: "product_reviewer" | "workflow_owner"`).
- So `apiClient.GET('/api/v1/workflows/{workflowRunId}/allowed-actions', { params: { query: { actorRole: 'developer' } } })` **fails `tsc`** — `'developer'` is not assignable to the literal union.

**Resolution (the only backend change in this story):** add `"developer"` to the `allowableValues` on `WorkflowController.java:330`, then **regenerate the committed OpenAPI snapshot AND the frontend client** (`scripts/regen-openapi.sh`, or the two manual steps). The service logic, the matrix, the `RECOGNIZED_ACTOR_ROLES` set, and the REST role-gate are all **already** `developer`-aware — this is purely advertising an already-supported value so the typed client can send it. No new endpoint, no service-logic change, no `DomainErrorCode`.

> Net production change: **one Java annotation array** (`+"developer"`), the two regenerated generated files, and a handful of frontend wiring lines. Everything else is reuse + test updates.

### Scope guardrails (do NOT do here)

- Do NOT build the `prOutput` PR/diff renderer — that is **3b-5**. The generic viewer may still render a raw `prOutput` JSON as `error`; acceptable here. This story is about the **decision bar actions appearing and firing**, not artifact rendering.
- Do NOT build `implementationPlan` step rendering / plan-phase nuances — that is **3b-6**.
- Do NOT implement story-2.13 header-based role attribution. Single-operator-all-roles for now; keep the `developer` value behind one constant so 2.13 can swap the source later.
- Do NOT change the backend matrix, `RECOGNIZED_ACTOR_ROLES`, the role-gate helper (`requireDeveloperReviewerRole`), or the accept/reject/takeover request DTOs — they already accept `developer` and a `reviewerRole` field. Do NOT add a `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema, config property, REST endpoint, ArchUnit rule, or transition-table edge.
- Do NOT change `useAllowedActions`'s behavior for the **spec** (`ApprovalDecisionBarContainer`) or **recovery** (`RecoveryDecisionBarContainer`) consumers — they must keep requesting with **no `actorRole`** (default `product_reviewer`). Only the `implementation_review` container opts into `developer`. (Note: `RecoveryDecisionBarContainer` arguably needs `workflow_owner` to surface `retry` — that is a pre-existing, separate concern; do NOT "fix" it here.)

## Acceptance Criteria

1. **Backend — advertise `developer` on the allowed-actions `actorRole` enum.** Add `"developer"` to `@Schema(allowableValues = …)` on the `getAllowedActions` `actorRole` query param (`WorkflowController.java:330`). Regenerate the committed OpenAPI snapshot (`deliveryline-backend/src/main/resources/openapi/openapi.json`) and the generated frontend client (`deliveryline-frontend/src/lib/api/schema.d.ts`) so the param type becomes `"product_reviewer" | "workflow_owner" | "developer"`. No service-logic, matrix, DTO, or `DomainErrorCode` change. `OpenApiSnapshotContractTest` stays green against the regenerated snapshot.

2. **Frontend — `useAllowedActions` can request a specific actor role (isolated, backward-compatible).** Extend `useAllowedActions(workflowRunId, actorRole?)` to accept an optional `actorRole` that (a) is threaded into the GET as the `actorRole` query param, and (b) is incorporated into the TanStack query key **only when provided**, so the developer-role cache entry is distinct from the default (`product_reviewer`) one and the key remains a structural **prefix-child of `workflowKeys.detail(runId)`** (the `detail(id)` invalidation cascade must still refresh it). When `actorRole` is omitted, the hook's URL, query key, and cache behavior are **byte-identical to today** (spec/recovery consumers unaffected).

3. **Frontend — the implementation_review container requests + decides as `developer`.** `ImplementationReviewDecisionBarContainer` calls `useAllowedActions(workflowRunId, DEVELOPER_REVIEWER_ROLE)` and passes `reviewerRole: DEVELOPER_REVIEWER_ROLE` in the `accept.mutate`/`reject.mutate`/`takeover.mutate` variables. With this, a `WaitingForReview` run renders the bar in `ready` state with **Accept / Reject / Take over**, and each decision POSTs `reviewerRole: "developer"` and transitions the run.

4. **Frontend — one shared role constant, react-refresh-safe.** Introduce `export const DEVELOPER_REVIEWER_ROLE = 'developer'` in a `.ts` module (`approvalDecisionView.ts`, already imported by the container and not a `.tsx` — respects `[[frontend-react-refresh-no-fn-exports]]`). The container and (optionally) the hooks reference the constant rather than a bare string literal, so a future story-2.13 attribution source has one swap point.

5. **No spec/recovery regression.** `ApprovalDecisionBarContainer` (spec) and `RecoveryDecisionBarContainer` (recovery) still call `useAllowedActions(workflowRunId)` with no role arg → still default `product_reviewer` → their existing behavior + tests are unchanged.

6. **Tests (failing-first).**
   - **Frontend container** (`ImplementationReviewDecisionBarContainer.test.tsx`): assert the allowed-actions GET is requested with `?actorRole=developer` (the MSW handler captures the query) and that **each** of accept/reject/takeover POSTs `reviewerRole: "developer"`. **Update the existing 3.28 takeover assertion** `expect(body).toEqual({ reasonText: 'manual continuation' })` (line 199) → `{ reasonText: 'manual continuation', reviewerRole: 'developer' }`; add `reviewerRole: 'developer'` to the accept/reject `toMatchObject` bodies. The "renders ready bar with all three actions" test must still pass (the MSW `allowed()` handler already returns the full set regardless of role; optionally tighten it to only return the full set when `actorRole=developer`).
   - **Frontend eligibility guard** (`ApprovalDecisionBar.eligibility.test.ts:46`): the substring assertion `expect(IMPL_CONTAINER).toContain('useAllowedActions(workflowRunId)')` **breaks** once the call gains a second arg — update it to the new exact call string (e.g. `toContain('useAllowedActions(workflowRunId, DEVELOPER_REVIEWER_ROLE)')`). Keep the spec-container assertion (line 41) unchanged. The forbidden-inference-pattern checks must still pass (the constant is a role value, not an eligibility inference).
   - **Frontend hook** (`useAllowedActions.test.tsx`): add a case that `useAllowedActions(id, 'developer')` issues the GET with `?actorRole=developer` and uses a query key distinct from the no-arg call; keep the existing no-arg case green.
   - **Backend (thin REST contract)** (`AllowedActionsEndpointContractTest`): add a case mirroring `actorRoleQueryParamHonoredWhenPresent` — `GET …/allowed-actions?actorRole=developer` on a `WaitingForReview` run returns `[accept_implementation, reject_implementation, takeover_workflow, view_only]` (200). The service-unit matrix already pins this (`WorkflowInspectionServiceAllowedActionsTest` matrixCases) — the REST case pins the boundary + the now-advertised enum value.

7. **Rebuild + re-embed + manual verify.** `mvn package` rebuilds + re-embeds the SPA into backend `static/` (`[[embedded-frontend-at-package-phase]]`). On a live `WaitingForReview` run, the decision bar shows Accept/Reject/Take over (not `blocked`), and a plan-phase accept/reject (or takeover) transitions the run. (A `prOutput` **accept** additionally needs the `github_pr` link from 3b-1/3b-2 to pass the backend PR-link gate — see Risks; test the full accept against a **plan-phase** artifact or after #1, and use **takeover** / **reject** which need no link.)

8. **No new production surface.** Beyond the one annotation value + the two regenerated generated files, no new `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema, config property, REST endpoint, ArchUnit rule, or transition-table edge. The frontend change is the hook param, the container's two-line wiring, and one exported constant.

## Tasks / Subtasks

- [x] Task 1 — Backend: advertise `developer` on the `actorRole` enum + regenerate generated files (AC: #1)
  - [x] In `adapters/rest/WorkflowController.java:330`, change `allowableValues = {"product_reviewer", "workflow_owner"}` → `{"product_reviewer", "workflow_owner", "developer"}`. Leave the `@RequestParam`, the normalization, logging, and the service call untouched.
  - [x] Regenerate both generated files. The enum change is purely additive (declaration order: `product_reviewer`, `workflow_owner`, `developer`), so I edited `openapi.json` + `schema.d.ts` in place and **proved correctness** rather than re-derived: `OpenApiSnapshotContractTest` (booted app + live `/v3/api-docs`, Docker) confirms the committed snapshot is byte-identical to what springdoc generates from the annotation; `npm run check:api` confirms `schema.d.ts` matches the snapshot. (`[[maven-arglineation-goal-crash]]`, `[[openapi-regen-platform-shim]]`, `[[verify-ci-fixes-in-clean-env]]`.)
  - [x] Confirmed `OpenApiSnapshotContractTest` passes against the regenerated snapshot (1/1, no other drift introduced).
- [x] Task 2 — Frontend: parameterize `useAllowedActions` with an optional `actorRole` (AC: #2, #5)
  - [x] `lib/queryKeys/workflowKeys.ts`: `allowedActions(workflowRunId, actorRole?)` appends `actorRole` to the key **only when defined** — `actorRole === undefined ? [...detail(id), 'allowedActions'] : [...detail(id), 'allowedActions', actorRole]`. Prefix-child of `detail(id)`; the no-arg key is byte-identical to today.
  - [x] `features/workflows/hooks/useAllowedActions.ts`: added the optional `actorRole` param (typed with the generated `AllowedActionsActorRole` union, not bare `string`, so it type-checks against the openapi client); `query` is only spread when `actorRole` is defined (no-arg request stays param-free); threaded into `workflowKeys.allowedActions(...)`. JSDoc notes the role-scoped variant (default = `product_reviewer`).
- [x] Task 3 — Frontend: wire the developer role into the implementation_review container (AC: #3, #4)
  - [x] `features/workflows/approvalDecisionView.ts`: added `export const DEVELOPER_REVIEWER_ROLE = 'developer';` (doc notes the single-operator role at `WaitingForReview`; isolated so story-2.13 can re-source it).
  - [x] `ImplementationReviewDecisionBarContainer.tsx`: imported `DEVELOPER_REVIEWER_ROLE`; `useAllowedActions(workflowRunId)` → `useAllowedActions(workflowRunId, DEVELOPER_REVIEWER_ROLE)`; added `reviewerRole: DEVELOPER_REVIEWER_ROLE` to the accept/reject/takeover `mutate` variables. `view`, gating, success/announcement, and logging logic unchanged.
  - [x] Confirmed the spec (`ApprovalDecisionBarContainer.tsx`) and recovery (`RecoveryDecisionBarContainer.tsx`) calls stay `useAllowedActions(workflowRunId)` (no role) — unchanged (eligibility test line 41 still green).
- [x] Task 4 — Frontend tests (AC: #6)
  - [x] `ImplementationReviewDecisionBarContainer.test.tsx`: the `allowed()` handler captures the request `actorRole` and returns the full set ONLY for `developer` (else `[view_only]`), proving the role unblocks the bar; ready-bar test asserts `lastAllowedActorRole === 'developer'`. Added `reviewerRole: 'developer'` to the accept + reject body assertions; flipped the takeover `toEqual` to `{ reasonText: 'manual continuation', reviewerRole: 'developer' }`.
  - [x] `ApprovalDecisionBar.eligibility.test.ts`: updated the impl-container substring assertion to `useAllowedActions(workflowRunId, DEVELOPER_REVIEWER_ROLE)`; spec-container assertion + forbidden-pattern checks unchanged.
  - [x] `useAllowedActions.test.tsx`: added a `actorRole=developer` case — asserts the GET carries `?actorRole=developer` and the role-scoped key differs from the no-arg key; existing no-arg case stays green (AC5 isolation).
  - [x] `prettier --write` on touched frontend files; `tsc -b` + `eslint --max-warnings=0` clean; full `features/workflows` + `lib/queryKeys` vitest suites 670/670 green.
- [x] Task 5 — Backend test (AC: #6)
  - [x] `AllowedActionsEndpointContractTest`: added `getAllowedActionsAsDeveloperAtWaitingForReviewReturnsImplementationActions` mirroring `actorRoleQueryParamHonoredWhenPresent` — stubs the service to return `[accept_implementation, reject_implementation, takeover_workflow, view_only]` for a `WaitingForReview` run, `GET …?actorRole=developer`, asserts 200 + the four actions + the `developer` role reached the service. Slice tier 10/10 green.
- [x] Task 6 — Rebuild + manual verify (AC: #7)
  - [x] `mvn package` (backend, `-am`) rebuilt + re-embedded the SPA into `static/` and assembled the jar (fresh `deliveryline-backend-0.0.1-SNAPSHOT.jar`); the SPA production build (`npm run build`) is clean. ⚠️ The **live manual verify** (on a real `WaitingForReview` run, confirm the bar shows Accept/Reject/Take over and a plan-phase decision/takeover/reject transitions the run) is a human-in-the-loop step requiring a live agent run in that state — NOT performed headlessly; flagged for operator verification. The automated request-body + REST contract pins stand in for the wiring proof. Note `prOutput` accept needs the `github_pr` link (#1) — see Risks.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **No NEW production log branch added.** The backend boundary already logs `REST get allowed-actions received … actorRole={}` / `… success …` (`WorkflowController.java:338-349`) — it now carries `actorRole=developer`, already `MdcKeys.sanitizeForLog`-sanitized + already carries `workflowRunId` (unchanged). The container's field-only `impl.*` logs are byte-identical — `reviewerRole` was NOT added to any logged payload (kept implied by the bar mode).
  - [x] Parameterized logging only; no production log-level change; no new WARN/ERROR branch. The frontend request-body assertions (`reviewerRole: 'developer'`, `actorRole=developer`) + the backend REST contract test are the observability pins for this wiring.

### Review Findings

_Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor), 2026-06-17. Acceptance Auditor: all of AC1–AC6, AC8 PASS; the two intended 3.28 test inversions match the spec. 0 decision-needed, 3 patch (all low/optional polish), 1 defer, 4 dismissed as noise._

- [x] [Review][Patch] Remaining bare `allowed()` MSW handler doesn't capture/gate on `actorRole` — converted the version-mismatch handler to `({ request }) => allowed(request)` so it matches its siblings and exercises the role gate. [ImplementationReviewDecisionBarContainer.test.tsx:246] — FIXED
- [x] [Review][Patch] `workflowKeys.allowedActions` guarded on `=== undefined` (not nullish/empty), so an empty-string role would fork a phantom cache entry and the hook would send `?actorRole=` (→400). Switched both the key factory (`actorRole == null || actorRole === ''`) and the hook's query spread (`actorRole ? …`) to collapse any falsy value to the byte-identical no-arg path. [workflowKeys.ts:70 / useAllowedActions.ts:54] — FIXED
- [x] [Review][Patch] Stale JSDoc in the three decision hooks ("`reviewerRole` omitted today; accepted for forward-compat") refreshed to state the impl-review container now sends `"developer"` (REST gates it to equal `"developer"`). [useAcceptImplementation.ts / useRejectImplementation.ts / useTakeoverWorkflow.ts] — FIXED
- [x] [Review][Defer] AC7 live manual verify not performed headlessly [story Task 6 / Completion Notes] — deferred, operator step. Needs a live agent run in `WaitingForReview` to confirm the bar shows Accept/Reject/Take over and a plan-phase decision/takeover/reject transitions the run; a `prOutput` accept additionally needs the 3b-1/3b-2 `github_pr` link to pass the backend PR-link gate.

_Dismissed (4): the `view_only`+`takeover_workflow` co-presence in the contract-test fixture is the intended matrix baseline per AC6 (not contradictory); the REST contract test asserting passthrough-only is by design (the matrix logic is pinned in `WorkflowInspectionServiceAllowedActionsTest`); the `openapi.json` `"type": "null"` on the param is a pre-existing springdoc artifact unchanged by this diff (schema.d.ts emits the correct string union); the backend hard-400 on an absent `reviewerRole` is a pre-existing gate (3.23–3.25), out of scope here._

## Dev Notes

### Why this is mostly a wiring story (and the one place it isn't)

Everything the bar needs is built: the `implementation_review` mode + container (3.28), the three hooks with `reviewerRole` pass-through (3.20–3.25), the accept/reject/takeover endpoints + the `requireDeveloperReviewerRole` gate, and the **matrix that already returns the developer action set at `WaitingForReview`** (3.20/3.21/3.22). The ONLY genuinely-new change is making the typed client able to *say* `developer` — i.e. advertising the already-supported enum value (Context → "THE TRAP"). Everything else is a hook param, two container lines, one constant, and test updates.

| Concern | Where it already lives | This story |
|---|---|---|
| `getAllowedActions` recognizes `developer` + `WaitingForReview` matrix → accept/reject/takeover | `WorkflowInspectionService` (`RECOGNIZED_ACTOR_ROLES`, matrix `case WAITING_FOR_REVIEW`) | reuse unchanged |
| `actorRole` query param plumbing + normalization + logging | `WorkflowController.getAllowedActions` (`:317-351`) | **add `"developer"` to `allowableValues` only** (`:330`) |
| accept/reject/takeover REST endpoints + `reviewerRole` body field + `requireDeveloperReviewerRole` gate | `WorkflowController` (`:579,659,877,960`), `AcceptImplementationRequest`/`RejectImplementationRequest`/`TakeoverRequest` | reuse unchanged |
| decision hooks pass `reviewerRole` through to the body when provided | `useAcceptImplementation`/`useRejectImplementation`/`useTakeoverWorkflow` (`:70` etc.) | **populate the field** from the container |
| `implementation_review` bar + routing + gating + success/announcement | `WorkflowDecisionBar.tsx:54`, `ImplementationReviewDecisionBarContainer.tsx`, `approvalDecisionView.resolveApprovalBarState` | reuse; activated by the role wiring + re-embed |
| `useAllowedActions` query-key factory (prefix-child of `detail(id)`) | `workflowKeys.allowedActions` | **extend with optional `actorRole`** (back-compat) |

### Design Decision DD1 — why advertise the enum value instead of bypassing the typed client

`apiClient.GET` is the openapi-typescript client; its `query.actorRole` type is the generated literal union. You could cast/`@ts-expect-error` to send `'developer'`, but that defeats the drift gate and rots silently. The honest fix is one annotation array entry + a regen — the value is already a first-class recognized role server-side, so this is "document the contract," not "loosen it." This keeps `OpenApiSnapshotContractTest` and the frontend `check:api` drift gate both green and truthful.

### Design Decision DD2 — query-key isolation (don't collide spec vs developer caches)

`useAllowedActions` is consumed by three containers for the **same** run id: spec (`product_reviewer`, no arg), recovery (no arg), and implementation_review (`developer`). They're state-exclusive (a run is in exactly one of `WaitingForSpecApproval` / `Failed` / `WaitingForReview`), so they won't mount simultaneously — but the React Query cache can retain stale entries across a run's lifetime. Append `actorRole` to the key **only when provided** so: (a) the developer entry can't serve a stale `product_reviewer` payload (or vice-versa); (b) the no-arg key stays byte-identical (spec/recovery tests + the documented `detail(id)` invalidation-cascade comment in `useAllowedActions.ts` remain true — the key is still a prefix-child of `detail(id)`).

### Design Decision DD3 — single-operator-all-roles, isolated for story-2.13

Per the user's "one user, multiple roles for now," the operator is hard-wired as `developer` at `WaitingForReview` via the `DEVELOPER_REVIEWER_ROLE` constant. Story 2.13 (header-based role attribution — OUT of scope) will later source the role from auth/headers. Keeping the value behind one constant in `approvalDecisionView.ts` gives 2.13 exactly one swap point; do NOT thread a role-provider abstraction now (YAGNI).

### Tests that this story INVERTS (read before writing — failing-first)

These currently **pin the un-wired state** and must be flipped (this is intended supersession, not regression):

- `ImplementationReviewDecisionBarContainer.test.tsx:199` — `expect(body).toEqual({ reasonText: 'manual continuation' })` asserts takeover sends **no** `reviewerRole`. 3b-4 makes it send `reviewerRole: 'developer'` → update to `{ reasonText: 'manual continuation', reviewerRole: 'developer' }`. The file header prose ("takeover sends only reasonText, NO versions / actor / reviewerRole") should be amended too.
- `ApprovalDecisionBar.eligibility.test.ts:46` — `toContain('useAllowedActions(workflowRunId)')` is an exact substring that no longer appears once the call gains a second arg → update to the new call string.

### Exact touch points (verified against current source)

| File | Line(s) | Relevance |
|---|---|---|
| `adapters/rest/WorkflowController.java` | `330` (`allowableValues` → add `"developer"`); `317-351` (param plumbing, unchanged); `579,659,877` (accept/reject/takeover endpoints, unchanged); `960` (`requireDeveloperReviewerRole`, unchanged) | the one backend change |
| `application/workflow/WorkflowInspectionService.java` | `RECOGNIZED_ACTOR_ROLES` (incl. `developer`), matrix `case WAITING_FOR_REVIEW` (→ accept/reject/takeover/view_only for `developer`) | reuse; proves the value is already supported |
| `src/main/resources/openapi/openapi.json` | `1590-1603` (the `actorRole` param enum) | regenerate (gains `"developer"`) |
| `deliveryline-frontend/src/lib/api/schema.d.ts` | `975-983` (`getAllowedActions.query.actorRole`) | regenerate (gains `"developer"`) |
| `lib/queryKeys/workflowKeys.ts` | `62-63` (`allowedActions`) | add optional `actorRole` (back-compat) |
| `features/workflows/hooks/useAllowedActions.ts` | `31-47` (fetcher + hook) | add optional `actorRole` → query param + key |
| `features/workflows/approvalDecisionView.ts` | (add) `DEVELOPER_REVIEWER_ROLE` const; `resolveApprovalBarState` `implementation_review` branch (unchanged) | the role constant |
| `features/workflows/components/ImplementationReviewDecisionBarContainer.tsx` | `78` (allowed-actions call → add role), `135-136`/`158-164`/`190-191` (the three `mutate` calls → add `reviewerRole`) | the wiring |
| `features/workflows/components/ApprovalDecisionBarContainer.tsx` | `60` | spec consumer — unchanged (assert it) |
| `features/workflows/components/RecoveryDecisionBarContainer.tsx` | `61` | recovery consumer — unchanged (assert it) |
| `features/workflows/hooks/useAcceptImplementation.ts` | `42-52,65-71` | `reviewerRole` field already pass-through |
| `features/workflows/hooks/useRejectImplementation.ts` | `34-46,58-76` | `reviewerRole` field already pass-through |
| `features/workflows/hooks/useTakeoverWorkflow.ts` | `37-43,54-69` | `reviewerRole` field already pass-through |
| test: `ImplementationReviewDecisionBarContainer.test.tsx` | `44-48` (`allowed()`), `122,155,199` (body asserts) | add actorRole + reviewerRole asserts; flip takeover `toEqual` |
| test: `ApprovalDecisionBar.eligibility.test.ts` | `41` (spec, keep), `46` (impl, update) | update the impl substring |
| test: `useAllowedActions.test.tsx` | (existing no-arg cases) | add the role-param case |
| test: `AllowedActionsEndpointContractTest.java` | `89` (default), `110-121` (honored — mirror for `developer`) | add the WaitingForReview developer REST case |

### Architecture / boundaries

- The backend change is a single annotation-array edit in an existing `@RestController` — no new endpoint, no `application → adapters` import (`[[application-cannot-import-adapters]]`), no `*Controller`-placement concern.
- Frontend helpers/constants live in the `.ts` sibling (`approvalDecisionView.ts`); the container `.tsx` imports them — respects `[[frontend-react-refresh-no-fn-exports]]` (no function/map export from a feature `.tsx`).
- `WorkflowDecisionBar.tsx:54` already routes `WaitingForReview → ImplementationReviewDecisionBarContainer` — no routing change.
- The OpenAPI regen is a **cross-shell, cross-platform** ritual (`[[openapi-regen-platform-shim]]`, `[[runner-contracts-schema-stale-in-m2]]` mindset): backend snapshot (WSL2/Docker) + `npm run generate-api` (shell that owns the `node_modules/.bin` shim). Commit both generated files together or the frontend drift gate fails.

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying.

- **Framework:** SLF4J + Logback (backend); field-only structured `console.*` (frontend). No `System.out`, no `printStackTrace()`.
- **This story:** no new production log branch. The backend `REST get allowed-actions …` INFO lines (`WorkflowController.java:338-349`) now carry `actorRole=developer` (already sanitized via `MdcKeys.sanitizeForLog`, already carries `workflowRunId`). The container's field-only `impl.*` logs are unchanged — do NOT add `reviewerRole` to them (keep payloads byte-identical; the role is implied by the bar mode).
- **Required context keys:** `workflowRunId`, `actorRole` (backend); the existing `event` + non-PII fields (frontend).
- **Forbidden:** `reasonText`, `preservedPrReference`, ids, tokens, PII — unchanged from 3.28.
- **Test contract:** the frontend request-body assertions (`reviewerRole: 'developer'`, `actorRole=developer`) and the backend REST contract test ARE the pins for this wiring; add a list-appender/`OutputCaptureExtension` assertion only if a new log branch is introduced (none is expected).

### Testing standards

- Backend unit tier = Surefire (no Docker); `*IT`/`*ContractTest` (MockMvc/`@WebMvcTest`) — `AllowedActionsEndpointContractTest` is a slice test (no Testcontainers), runs in the standard tier. OpenAPI regen uses the `failsafe:integration-test` via the script's `-Dopenapi.snapshot.write=true` path (`[[maven-arglineation-goal-crash]]`).
- Frontend: vitest + MSW; capture the request in the MSW handler (`({ request }) => new URL(request.url).searchParams.get('actorRole')`); run `prettier --write` before pushing (`[[prettier-gate-cascades-ci]]`); verify the lockfile/CI shape on Linux (`[[frontend-lockfile-cross-platform]]`, `[[verify-ci-fixes-in-clean-env]]`).
- After the backend annotation change, regenerate generated files BEFORE running the frontend `tsc`/tests — `schema.d.ts` must include `developer` or the container call won't type-check.

### Project Structure Notes

- No new module, package, Flyway migration, `DomainErrorCode`, `WorkflowEventType`, runner-contracts schema, config property, REST endpoint, ArchUnit rule, or transition-table edge. Net production change: `+"developer"` in one annotation array + the two regenerated generated files + the frontend hook param / container wiring / one constant.

### References

- [Source: docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md] — authoritative design (sub-project #3); "The gaps" §1 (developer-role wiring), "Role wiring" §, Story A breakdown.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3b-4: Developer-Role Wiring at WaitingForReview] (`:941-957`) — AC-shape, dependencies, sequencing `((3b-1 → 3b-2) and 3b-3 parallel → 3b-4 → 3b-5/3b-6)`, the prOutput-accept-needs-github_pr note.
- [Source: _bmad-output/implementation-artifacts/3b-3-waiting-for-review-artifact-availability-mark-available-on-ingest-and-surface-link.md] — #2b (artifacts now `available` + impl-output link); 3b-4 makes the bar's actions fire over that.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java:330,317] — the `actorRole` `allowableValues` to extend + the param plumbing.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java] — `RECOGNIZED_ACTOR_ROLES` (incl. `developer`) + the `WAITING_FOR_REVIEW` matrix row (accept/reject/takeover/view_only for `developer`); already supports `developer`.
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java:126-133] — the service-unit matrix case already pinning the developer WaitingForReview action set.
- [Source: deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.tsx:78,135,158,190] — the allowed-actions call + the three `mutate` sites to add `reviewerRole`.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts:31-47] and [.../lib/queryKeys/workflowKeys.ts:62] — the hook + key to parameterize.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAcceptImplementation.ts:51,70] — the `reviewerRole` pass-through ("omitted today; accepted for forward-compat") this story populates (mirror in reject/takeover hooks).
- [Source: scripts/regen-openapi.sh] — the combined snapshot + client regen ritual (`OpenApiSnapshotContractTest` write-mode + `npm run generate-api`).
- Dependencies: 3.28 (`implementation_review` bar + container + the three hooks — reuse), 3.23/3.24/3.25 (accept/reject/takeover endpoints + `reviewerRole` boundary + `requireDeveloperReviewerRole`), 3.20/3.21/3.22 (the developer `WaitingForReview` matrix row), 2.14 (allowed-actions endpoint + version stamp), 3b-3 (artifacts available + impl link). OUT: 2.13 (header role attribution), 3b-5 (`prOutput` renderer), 3b-6 (`implementationPlan` rendering).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- `OpenApiSnapshotContractTest` (Failsafe / Docker, `integration-test` phase): 1/1 green — the in-place `openapi.json` edit is byte-identical to the live springdoc `/v3/api-docs` after the annotation change (proves the manual regen is correct without a WSL2/Docker regen-script round-trip).
- `npm run check:api`: ✅ generated client (`schema.d.ts`) in sync with the committed snapshot.
- `AllowedActionsEndpointContractTest` (Surefire slice): 10/10 green (incl. the new `developer` case).
- Frontend `tsc -b` clean (after typing the hook param with the generated `AllowedActionsActorRole` union — bare `string` failed `exactOptionalPropertyTypes` against the literal-union query param); `eslint --max-warnings=0` clean; `prettier --write` applied; `features/workflows` + `lib/queryKeys` vitest 670/670 green; SPA `npm run build` clean; backend `mvn package -am` re-embedded the SPA + assembled the jar.

### Completion Notes List

- **The one backend change is purely advertising an already-supported value.** `WorkflowController.java:330` gains `"developer"` in the `actorRole` `allowableValues`; the service (`RECOGNIZED_ACTOR_ROLES`), the `WaitingForReview` matrix row, and the `requireDeveloperReviewerRole` REST gate were all already `developer`-aware. No service-logic, DTO, `DomainErrorCode`, `WorkflowEventType`, Flyway, runner-contracts, config, endpoint, ArchUnit, or transition-table change.
- **Both generated files regenerated by proof, not by re-running the cross-shell script.** Since the enum change is additive and order-stable, I edited `openapi.json` + `schema.d.ts` directly and validated them against the live spec (`OpenApiSnapshotContractTest`) and the drift gate (`check:api`) — both green.
- **Hook param typed with the generated union.** `useAllowedActions(workflowRunId, actorRole?: AllowedActionsActorRole)` — derived from `operations['getAllowedActions']['parameters']['query']['actorRole']` — so a bare-string param could not silently send an un-advertised role and the openapi-client `query` type checks.
- **Isolation preserved (AC5).** Spec (`ApprovalDecisionBarContainer`) + recovery (`RecoveryDecisionBarContainer`) still call `useAllowedActions(workflowRunId)` with no role → default `product_reviewer`, byte-identical request + query key. Only the `implementation_review` container opts into `developer`.
- **One swap point for story-2.13.** `DEVELOPER_REVIEWER_ROLE` lives in the `.ts` sibling (`approvalDecisionView.ts`), referenced by the container for both the allowed-actions request role and the three decisions' `reviewerRole`.
- **Inverted two 3.28 tests that pinned the un-wired state** (intended supersession): the takeover-body `toEqual` now includes `reviewerRole: 'developer'`; the eligibility substring now matches the two-arg call.
- ⚠️ **Open for reviewer/operator:** the AC7 *live* manual verify (decision bar shows Accept/Reject/Take over on a real `WaitingForReview` run and a decision transitions it) was not run headlessly — needs a live agent run in that state. A `prOutput` *accept* additionally needs the 3b-1/3b-2 `github_pr` link to pass the backend PR-link gate; verify accept against a plan-phase artifact, or use takeover/reject (no link needed).

### File List

Production:
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (modified — `+"developer"` in the `actorRole` `allowableValues`)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated — `actorRole` enum gains `"developer"`)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated — `actorRole?: "product_reviewer" | "workflow_owner" | "developer"`)
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` (modified — optional `actorRole` appended to the key only when defined)
- `deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts` (modified — optional `actorRole` param + `AllowedActionsActorRole` type + role-scoped query/key)
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.ts` (modified — `export const DEVELOPER_REVIEWER_ROLE`)
- `deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.tsx` (modified — request + decide as `developer`)

Tests:
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AllowedActionsEndpointContractTest.java` (modified — new developer `WaitingForReview` REST case)
- `deliveryline-frontend/src/features/workflows/components/ImplementationReviewDecisionBarContainer.test.tsx` (modified — actorRole capture + `reviewerRole` asserts + flipped takeover `toEqual`)
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.eligibility.test.ts` (modified — updated impl-container substring)
- `deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.test.tsx` (modified — developer-role request + distinct-key case)

Tracking:
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status `ready-for-dev` → `in-progress` → `review`)

### Change Log

| Date | Change |
|---|---|
| 2026-06-17 | Story 3b.4 implemented (Status → review): advertised `developer` on the `getAllowedActions` `actorRole` enum (one annotation array) + regenerated `openapi.json`/`schema.d.ts` (validated via `OpenApiSnapshotContractTest` + `check:api`); parameterized `useAllowedActions`/`workflowKeys.allowedActions` with an optional role-scoped `actorRole` (no-arg path byte-identical for spec/recovery); wired `ImplementationReviewDecisionBarContainer` to request allowed-actions as `developer` and send `reviewerRole: 'developer'` on accept/reject/takeover; added `DEVELOPER_REVIEWER_ROLE` constant. Tests: new backend REST developer case + frontend hook/container/eligibility updates (inverted two 3.28 un-wired-state pins). All automated gates green; live manual verify flagged for the operator. |
| 2026-06-17 | Story 3b.4 drafted: developer-role wiring at `WaitingForReview`. Central trap identified by code trace — the `getAllowedActions` `actorRole` enum advertises only `product_reviewer`/`workflow_owner`, so the typed client can't send `developer` until the OpenAPI snapshot + frontend client are regenerated with `"developer"` added to `allowableValues` (`WorkflowController.java:330`). The service/matrix/role-gate already support `developer`; the rest is frontend wiring (hook param + container's allowed-actions role + `reviewerRole` on the three decisions + one constant) plus inverting two 3.28 tests that pin the un-wired state. |
