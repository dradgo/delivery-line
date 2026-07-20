# Story 3h.6: FE — Delivery-Tail Surfaces (Build Status, Lint Gate, Delivery Bar, CI Panel)

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an authorized user,
I want the build status, lint gate, delivery gate, and CI investigation surfaced in the run view,
so that I can see each new quality gate's state and act on the two new Decision Bars without leaving the run.

---

## ⛔ PREREQUISITE GATE — read before starting

This story is the **front-end twin** of the Epic-3h backend delivery-tail stories. It **reads** their read models; it does **not** re-implement any gate logic. Statuses at authoring time (2026-07-11):

| Backend story | Surface it backs | Status | Read model 3h-6 consumes |
|---|---|---|---|
| 3h-1 (BUILD) | Build-status surface | **done** | `/steps` rows `stage="build"` + new `buildFixLoopCount` (Task 1) + `escalationMarker` |
| 3h-2 (LINT gate) | Lint-gate panel + Decision Bar | **done** | `GET /lint-findings` → `LintFindings`; actions `approve_lint`/`request_lint_fix`; state `WaitingForLintApproval` |
| 3h-4 (delivery gate) | Delivery Decision Bar | **done** | action `approve_delivery`; state `WaitingForDelivery`; new `pushMode`/`autoCreatePullRequest` (Task 1) |
| 3h-5 (CI investigation) | CI investigation panel | **review** (read-model fields landed) | `WorkflowDetail.ciStatus`/`ciHeadSha`/`ciFixLoopCount`/`ciChecksEnforced` |

**3h-5 must be `done` (merged) before this story merges** — its `ciStatus` fields are already in `openapi.json`/`schema.d.ts` on the working tree, but do not ship the CI panel on top of an un-merged contract. All four surface-backing stories are code-complete; only 3h-5's merge is pending.

### 🔀 SCOPE CHANGE — the BMAD verdict panel is NOT in this story (product-owner decision, Alex, 2026-07-11)

The epic's original **3h-6 AC3 (BMAD multi-layer verdict panel)** is **extracted into a separate follow-up FE story**. Reason: **3h-3 (BMAD review mode) is still `ready-for-dev` — not implemented.** The multi-layer `findings` payload it promised does **not** exist in the read model yet: I verified `ReviewerVerdictResponse` today carries only a single `outcome` enum + free-text `rationale` (no `layers[]`, no categorized findings, no triage). There is nothing multi-layer to render. The existing single-pass `ReviewerVerdictPanel` (3d-2) **keeps rendering unchanged** and is out of this story's scope.

**Action:** the multi-layer BMAD verdict UI becomes its own story, sequenced **after 3h-3 lands** (3h-3 owns the additive `ReviewerVerdict.findings` OpenAPI regen; its dev-story note already says "FE render deferred to 3h-6" — that render moves to the extracted story). Logged in `deferred-work.md`. Do **not** build a multi-layer verdict panel here; do **not** touch `ReviewerVerdictPanel.tsx`.

[Source: AskUserQuestion 2026-07-11; sprint-status.yaml `3h-3: ready-for-dev`; agent read of `ReviewerVerdictResponse.java` — no `findings` field; 3h-3 story Decision D6/AC7]

---

## 🧭 ARCHITECTURE DECISIONS — read before coding

**Decision 1 — This story carries a SMALL backend read-model widening (product-owner decision, Alex, 2026-07-11). It is NOT pure-FE.**
Four fields the surfaces need are absent from the run read model and were **not** exposed by the backend stories:
- `build_fix_loop_count` (V33) and `lint_fix_loop_count` (V34) are real `workflow_runs` columns but are **not projected** onto `WorkflowStatusView`/`WorkflowDetailResponse`.
- `pushMode` / `autoCreatePullRequest` (V38) live only on the **Project** read model, not the **run** — the delivery bar (AC4) needs them per-run to pick its label.

**Resolution:** append four **trailing nullable** fields to `WorkflowDetailResponse` (the `totalTokens`/`ciStatus` precedent), sourced in `WorkflowInspectionService` (Task 1). This is a real backend + OpenAPI + `schema.d.ts` change. **`WorkflowSummaryResponse` is NOT touched** — its exact-field contract block would break [[workflow-summary-exact-field-contract-test]]. The alternative (FE fetches the Project read model + infers loop counts from `/steps`) was rejected: it couples the delivery bar to a second query and makes the loop-count indicators approximate.

**Decision 2 — Build outcome is read from `/steps`; the loop indicator is read from the new field.** There is **no** `buildStatus` field and this story does **not** add one. Build pass/fail is derived from the latest `/steps` row with `stage="build"` (`status="completed"` → pass, `status="failed"` → fail); the auto-fix-loop indicator reads the new `buildFixLoopCount` + the existing `escalationMarker`. Build runs inline inside `Executing` — there is **no** `WaitingForBuild` state and no build gate action, so the build surface is informational only (no Decision Bar).

**Decision 3 — Both new Decision Bars gate ONLY on `allowedActions`, never on state or role.** The lint-gate bar (`approve_lint`/`request_lint_fix`) and the delivery bar (`approve_delivery`) render their buttons **only** when the action string is present in `useAllowedActions(runId, 'workflow_owner')`. These three actions are `workflow_owner`-scoped server-side. Gating on `currentState` alone or on an inferred role is a **build failure** (eslint `local-rules/no-role-based-action-gating`). State is used only to decide **panel visibility**, never button enablement.

**Decision 4 — Single-route, state-driven. No new route files.** The run detail route (`src/routes/workflows/$workflowRunId/index.tsx`) is a single state-driven page (AC10 rule baked into its header: *"Epic 3 adds stages by widening the recognized set, never by forking the route tree"*). The two new states **must** be added to `RECOGNIZED_STATES` or the run renders `UnrecognizedRunStateState`. New surfaces are self-hiding sub-panels appended to the existing `<Stack>`, exactly like `RunDependencyPanel` / `ReviewerVerdictPanelContainer`.

**Decision 5 — Regenerate `schema.d.ts` FIRST.** Task 1's backend widening changes `openapi.json`; run `npm run generate-api` **before** writing any component, so the new `WorkflowDetail` fields are typed. Skipping this reds `check:api` and forces `as any` casts [[openapi-regen-frontend-client-drift-cascade]]. The lint/delivery/CI contracts (`LintFindings`, `approveLint`, `approveDelivery`, `ciStatus`) are **already** in `schema.d.ts` from 3h-2/3h-4/3h-5 — only Task 1's four fields are new to the client.

---

## Context — why this story exists (read before coding)

Epic 3h added five delivery-tail quality gates on the backend (build validation + auto-fix, CPU lint gate, BMAD review, governed push/PR delivery gate, CI investigation), but **no front-end consumes any of them yet** — the run view still shows only the Epic-1–3f surfaces. This story surfaces four of them in the existing run detail route: a **build-status surface**, a **lint-gate panel with a Decision Bar**, a **delivery Decision Bar**, and a **CI investigation panel** — plus the two new non-terminal states (`WaitingForLintApproval`, `WaitingForDelivery`) so the run renders instead of falling to the unrecognized-state fallback. It delivers the FE half of **FR75/FR76/FR78/FR79** (the BMAD/FR77 render is extracted — see the Scope Change gate). The single hard structural constraint is the **single-route, allowed-actions-gated** shape the route already enforces.

[Source: epic-03h Story 3h-6 + Cross-Cutting Notes; FE agent code-map 2026-07-11]

---

## Acceptance Criteria

1. **Build-status surface.** A self-hiding panel renders the `BUILD` stage outcome (pass/fail — from the latest `/steps` row `stage="build"`) plus an auto-fix-loop indicator (`buildFixLoopCount` + `escalationMarker`). Rendered when a build step exists; hidden otherwise. Color-independent (`StateSignifierChip`, never color alone).

2. **Lint-gate panel + Decision Bar.** When `currentState === "WaitingForLintApproval"`, a lint-gate panel renders the severity-classified findings from `GET /lint-findings` (`state ∈ none|advisory|gated`, `hasCritical`, `findings[]` with `severity ∈ error|warning|info`) **plus** a Decision Bar exposing `approve_lint` and `request_lint_fix`. The panel is hidden when the run is not at the lint gate. Advisory (non-`gated`) findings may also render read-only when present outside the gate. Both buttons gate on `allowedActions` (Decision 3).

3. **Delivery Decision Bar.** When `currentState === "WaitingForDelivery"`, a delivery Decision Bar renders `approve_delivery`, its label reflecting the run's `pushMode` + `autoCreatePullRequest` (e.g. "Approve to push + create PR" for `approve`+`true` vs. "Record manual delivery" for `manual`). Hidden when not at the delivery gate. Gates on `allowedActions` (Decision 3).

4. **CI investigation panel.** A panel renders the pushed branch's CI status (`ciStatus ∈ pending|success|failure|neutral|unavailable`) + a `ciFixLoopCount` indicator, from the run detail model. `ciStatus == null` **or** `ciChecksEnforced === false` renders a clear "no CI status" empty state — **never** an error. Color-independent.

5. **Read-model widening (backend).** `WorkflowDetailResponse` gains four **trailing nullable** fields — `buildFixLoopCount`, `lintFixLoopCount`, `pushMode`, `autoCreatePullRequest` — sourced in `WorkflowInspectionService`; `WorkflowSummaryResponse` is untouched. OpenAPI snapshot + `schema.d.ts` regenerated; `check:api` in-sync.

6. **Client generation + react-refresh discipline.** `schema.d.ts` is regenerated **first** (Decision 5). The two new states are added to the route's `RECOGNIZED_STATES` set and to `workflowStateMapping`. No non-constant helper is exported from a `.tsx` file (eslint `react-refresh/only-export-components`, `--max-warnings=0` ⇒ effectively an error); view-models live in flat `.ts` files. Query keys go through the `workflowKeys` factory (no inline keys).

7. **Accessibility.** Every new surface meets WCAG 2.1 AA and is **axe-clean**. Gate/state transitions are announced via `useLiveAnnouncement` + a live region; because a successful gate approval **advances the run out of the gate state** (unmounting the panel), the success announcement rides the route's **persistent always-mounted announcer**, not the panel's own region. New announcement strings are added to `src/lib/a11y/announcements.ts` (`check:a11y` enforced). A workflow-significant outcome is never toast-only (eslint `no-workflow-toast-success`).

8. **Tests.** Vitest asserts each surface renders for its state and is hidden otherwise; the two Decision Bars dispatch their actions only when the action is allowed; the CI panel renders the empty state for `null`/`unavailable`/`ciChecksEnforced=false`; the build surface renders pass/fail + loop indicator; axe is clean on each new surface. MSW default handlers extend `ACTIONS_BY_STATE` for the two new states + a `lint-findings` handler (consolidate same-module handlers). Backend: `WorkflowReadEndpointsContractTest` asserts the four new detail fields (+ null/zero cases); the summary field-set block stays untouched. `src/features/workflows/**` ≥ 85% coverage floor holds.

---

## Tasks / Subtasks

- [ ] **Task 1 — Backend read-model widening + regen `schema.d.ts` FIRST** (AC: #5, #6; Decisions 1, 5)
  - [ ] `application/workflow/WorkflowInspectionService` — widen `WorkflowStatusView` with `buildFixLoopCount` (int), `lintFixLoopCount` (int), `pushMode` (nullable String), `autoCreatePullRequest` (nullable Boolean).
    - Loop counts: project them from the `workflow_runs` read projection the service already loads (columns exist — V33 `build_fix_loop_count`, V34 `lint_fix_loop_count`). If the projection/port doesn't carry them, add them to the read leg in `adapters/persistence/WorkflowRunPersistenceAdapter` (read-only, no new migration).
    - `pushMode`/`autoCreatePullRequest`: resolve via `application/project/ProjectRuntimeConfigResolver.resolvePushMode(runId)` (→ `PushMode.value()` lowercase string) and `resolveAutoCreatePullRequest(runId)` (both exist post-3h-4; cheap in-memory read, safe in-tx — no I/O). Resolve **defensively**: null project / resolver throw → `null`, never strand the read (mirror the `ciChecksEnforced` defensive capability read).
  - [ ] `adapters/rest/WorkflowDetailResponse.java` — append the four fields **at the end** of the record (the `totalTokens`/`ciStatus` trailing-field precedent). Map them in the response factory.
  - [ ] **Do NOT touch `WorkflowSummaryResponse`** — `WorkflowReadEndpointsContractTest` pins its field set with `containsExactlyInAnyOrder` [[workflow-summary-exact-field-contract-test]].
  - [ ] OpenAPI regen: `mvnw -pl deliveryline-backend verify -Dopenapi.snapshot.write=true` (via `OpenApiSnapshotContractTest`) → then in `deliveryline-frontend`: `npm run generate-api` → commit **both** `openapi.json` and `schema.d.ts`, else `check:api` and `OpenApiSnapshotContractTest` both red [[openapi-regen-frontend-client-drift-cascade]]. **This is the first FE step** — do it before writing any component so the new `WorkflowDetail` fields are typed.

- [ ] **Task 2 — Route recognition + state mapping + a11y vocabulary** (AC: #2, #3, #6, #7; Decision 4)
  - [ ] `src/routes/workflows/$workflowRunId/index.tsx` — add `'WaitingForLintApproval'` and `'WaitingForDelivery'` to `RECOGNIZED_STATES` (the `Set` near line 77). Without this the run renders `UnrecognizedRunStateState`.
  - [ ] `src/features/workflows/workflowStateMapping.ts` (and `src/lib/state-signifiers.ts` union if needed) — add human labels + `StateName` signifier mapping for the two new states (e.g. `WaitingForLintApproval` → informational/recovery, `WaitingForDelivery` → informational). Never state-by-color.
  - [ ] `src/lib/a11y/announcements.ts` — add strings/functions for the new transitions: lint approved, lint-fix requested, delivery approved (and, if surfaced, CI verdict changed). `check:a11y` (`announcement-vocabulary.test.js`) enforces these exist and are used.

- [ ] **Task 3 — Build-status surface** (AC: #1; Decision 2)
  - [ ] New `src/features/workflows/components/BuildStatusPanel.tsx` (+ container if it owns a query) — read build outcome from `useStepExecutions(runId)` (`GET /steps`): latest row with `stage === "build"` → `status === "completed"` (pass) / `"failed"` (fail); the `runner_build_failed` failure category already exists in the schema. Render the auto-fix-loop indicator from `WorkflowDetail.buildFixLoopCount` (Task 1) + `escalationMarker`. Self-hides when no build step exists. Model it on `RunStepTokensPanel.tsx`.
  - [ ] Pure mapping logic (pass/fail derivation, "N fix attempts" text) lives in a flat `src/features/workflows/buildStatusView.ts` — **not** in the `.tsx` (react-refresh). Guard wire nulls with `!= null` [[workflowdetail-wire-sends-null-not-undefined]].
  - [ ] Append `<BuildStatusPanel workflowRunId={workflowRunId} />` to the route `<Stack>`; it self-hides.

- [ ] **Task 4 — Lint-gate panel + Decision Bar** (AC: #2; Decisions 3, 4)
  - [ ] New hook `src/features/workflows/hooks/useLintFindings.ts` — `useQuery` over `GET /api/v1/workflows/{id}/lint-findings` → `components['schemas']['LintFindings']`. Key via `workflowKeys` (child of `workflowKeys.detail(id)` so a detail invalidation cascades; **no inline query keys**). Type import idiom: `import type { components } from '@/lib/api/schema'`.
  - [ ] New `src/features/workflows/components/LintGatePanel.tsx` (+ `LintGatePanelContainer`) — presentational renders `findings[]` grouped by `severity` (`error`/`warning`/`info`) via `StateSignifierChip` (map `error`→error, `warning`→warning, `info`→informational), plus the `state`/`hasCritical` summary. Self-hides when `state === "none"` (or verdict null). May render advisory findings read-only outside the gate.
  - [ ] New `src/features/workflows/components/LintGateDecisionBarContainer.tsx` — clone `ImplementationReviewDecisionBarContainer.tsx` (the closest multi-action model). Read `useAllowedActions(runId, 'workflow_owner')`; `normalizeActions(...)` to drop unknown wire actions; render `approve_lint` (primary) + `request_lint_fix` via `DecisionArea`/`GovernedButton` (`single-primary-action` — exactly one primary). Buttons enabled **iff** the action is in `allowedActions` (Decision 3). Mutations: new hooks `useApproveLint`/`useRequestLintFix` (`POST .../approve-lint` with `ApproveLintRequest { reasonText?; role }`, `POST .../request-lint-fix`), owning the mutation at container level so the bar survives the post-approve state flip. `onSuccess` → invalidate `workflowKeys.detail(runId)` + fire the a11y announcement via the **persistent announcer** (Task 2/AC7).
  - [ ] `src/features/workflows/components/WorkflowDecisionBar.tsx` — add a `WaitingForLintApproval` branch selecting `LintGateDecisionBarContainer`.
  - [ ] Route wiring: render `<LintGatePanelContainer>` gated on `currentState === 'WaitingForLintApproval'` (panel visibility only); the bar routes through `WorkflowDecisionBar`.

- [ ] **Task 5 — Delivery Decision Bar** (AC: #3; Decisions 3, 4)
  - [ ] New `src/features/workflows/components/DeliveryDecisionBarContainer.tsx` — clone the approval-bar container shape; single action `approve_delivery` gated on `useAllowedActions(runId, 'workflow_owner')`. New hook `useApproveDelivery` (`POST .../approve-delivery`, `ApproveDeliveryRequest { reasonText?; role }`). `onSuccess` → invalidate detail + persistent-announcer announcement.
  - [ ] Label logic reflects `WorkflowDetail.pushMode` + `autoCreatePullRequest` (Task 1): `manual` → "Record manual delivery"; `approve` + `autoCreatePullRequest` → "Approve to push + create PR"; `approve` + `!autoCreatePullRequest` → "Approve to push". Label derivation lives in a flat `src/features/workflows/deliveryBarView.ts` (react-refresh). `auto` mode never parks here, so the bar is not expected in `auto`.
  - [ ] `WorkflowDecisionBar.tsx` — add a `WaitingForDelivery` branch selecting `DeliveryDecisionBarContainer`.

- [ ] **Task 6 — CI investigation panel** (AC: #4)
  - [ ] New `src/features/workflows/components/CiInvestigationPanel.tsx` — read `ciStatus`, `ciHeadSha`, `ciFixLoopCount`, `ciChecksEnforced` from `useWorkflowDetail(runId)` (already typed from 3h-5). Render the status via `StateSignifierChip` (`success`→success, `failure`→error, `pending`→loading, `neutral`→informational, `unavailable`→stale) + a `ciFixLoopCount` indicator when `> 0`. `ciStatus == null || ciChecksEnforced === false` → a clear "no CI status" empty state (informational), **never** an error. Guard wire nulls with `!= null`.
  - [ ] Append to the route `<Stack>`; self-hides only if you choose not to render the empty state — prefer rendering the explicit "no CI status" state per AC4.

- [ ] **Task 7 — Tests (Vitest + axe + backend contract)** (AC: #8)
  - [ ] MSW defaults: `src/test/handlers.ts` — extend `ACTIONS_BY_STATE` with `WaitingForLintApproval` → `['approve_lint','request_lint_fix', …view actions]` and `WaitingForDelivery` → `['approve_delivery', …]`; add a default `GET /lint-findings` handler. Keep same-module handlers consolidated (avoid the cross-file router/handler duplication trap [[vitest-cross-file-router-mock]]).
  - [ ] Pure-component tests (fixture props, no router): `BuildStatusPanel.test.tsx`, `LintGatePanel.test.tsx`, `CiInvestigationPanel.test.tsx` — per-state render + hidden-otherwise + `await expectNoA11yViolations(container)`.
  - [ ] Container tests: `LintGateDecisionBarContainer.test.tsx`, `DeliveryDecisionBarContainer.test.tsx` — buttons present only when the action is in `allowedActions`; absent when not (Decision 3); mutation fired on click; label reflects `pushMode`/`autoCreatePullRequest`.
  - [ ] Route/integration test additions in `src/routes/workflows/$workflowRunId/index.*.test.tsx` (mount the real `routeTree` + MSW): a `WaitingForLintApproval` run shows the lint panel + bar; a `WaitingForDelivery` run shows the delivery bar; a run with `ciStatus` shows the CI panel; neither new state falls to `UnrecognizedRunStateState`. Assert the a11y announcer via `waitFor` — the announcement lags one commit [[livesnnouncement-defers-one-commit-test-flake]]. `cleanup()` in `afterEach`.
  - [ ] Backend: `WorkflowReadEndpointsContractTest` — assert `buildFixLoopCount`, `lintFixLoopCount`, `pushMode`, `autoCreatePullRequest` present on the **detail** response + a null/zero case (copy the `totalTokens` null-case test); confirm the **summary** field-set block is untouched.
  - [ ] Run the **real** FE build before claiming green: `npm run build` (tsc -b typechecks test files too, which `tsc --noEmit`/vitest/eslint do not) [[frontend-tsc-noemit-misses-test-files]], plus `npm run check:api`, `npm run check:a11y`, `npm run lint` (`--max-warnings=0`), `npm run test`. Backend: targeted `WorkflowReadEndpointsContractTest` + `OpenApiSnapshotContractTest`.

- [ ] **Logging instrumentation** (cross-cutting; required on every story — scoped to the backend read-model touch)
  - [ ] The FE has no SLF4J surface; the only server-side change is the **read-only** `WorkflowInspectionService` widening (Task 1). Add a `DEBUG` line only if a resolver fallback fires (`WARN reason=push_mode_unresolvable workflowRunId={}` when `resolvePushMode`/`resolveAutoCreatePullRequest` throws and the field is defaulted to null) — carry `workflowRunId`; **never** log project internals or secrets. No new INFO lifecycle logging is warranted for a pure projection read.
  - [ ] Pin the WARN fallback (if added) with a `ListAppender`/`OutputCaptureExtension` assertion. **Trap:** a new sliced `@WebMvcTest` nulls the redaction holder and masks `CapturedOutput` to `[redaction-pending]` in a reused fork [[webmvctest-redaction-holder-poisons-capturedoutput]].

---

## Dev Notes

### Read models this story consumes (exact contracts)

- **Run detail** — `GET /api/v1/workflows/{id}` → `components['schemas']['WorkflowDetail']`; hook `src/features/workflows/hooks/useWorkflowDetail.ts` (warmed by the route loader via `detailQueryOptions`). Fields used: `currentState`, `escalationMarker`, `ciStatus`, `ciHeadSha`, `ciFixLoopCount`, `ciChecksEnforced`, and **new** `buildFixLoopCount`/`lintFixLoopCount`/`pushMode`/`autoCreatePullRequest`. `currentState` is a plain `string` on the wire — no schema enum blocks the new states; only the route `RECOGNIZED_STATES` set matters.
- **Steps** — `GET /api/v1/workflows/{id}/steps` → `StepExecution[]`; hook `useStepExecutions.ts`; `stage` is a free `string | null` (`"build"`/`"lint"` rows appear automatically — no enum widening).
- **Lint findings** — `GET /api/v1/workflows/{id}/lint-findings` → `LintFindings { state: 'none'|'advisory'|'gated'; hasCritical; findings: LintFinding[] }`, `LintFinding { file; line; message; rule; severity: 'error'|'warning'|'info' }`. Note the wire severity is `error` (there is no `critical` value — "critical" = `error` severity + `hasCritical`/`state="gated"`).
- **Allowed actions** — `GET /api/v1/workflows/{id}/allowed-actions?actorRole=workflow_owner` → `AllowedActions { actions: string[]; versionStamp }`; hook `useAllowedActions.ts`. The three gate actions are `workflow_owner`-scoped — always pass the role.
- **Mutations** — `POST .../approve-lint` (`ApproveLintRequest`), `.../request-lint-fix`, `.../approve-delivery` (`ApproveDeliveryRequest`). All carry `{ reasonText?; role }`. `apiClient` (`src/lib/api/client.ts`) auto-attaches `X-Correlation-Id` + `Idempotency-Key`.

### Existing patterns to reuse (do NOT reinvent)

- **Route:** `src/routes/workflows/$workflowRunId/index.tsx` — single state-driven page; self-hiding sub-panels in a `<Stack gap="4">`. State-gated render: `data?.currentState === 'X' ? <Panel/> : null`. Action-gated render: `allowedActions.data?.actions.includes('x') ?? false`.
- **Decision Bar (3 layers):** presentational `src/components/actions/DecisionArea.tsx` + `GovernedButton.tsx` (`single-primary-action`, `data-decision-*`); route-level selector `src/features/workflows/components/WorkflowDecisionBar.tsx`; data containers `ApprovalDecisionBarContainer.tsx` / `ImplementationReviewDecisionBarContainer.tsx` / `RecoveryDecisionBarContainer.tsx` → shared `ApprovalDecisionBar.tsx`. Clone the container that owns its mutations so the bar survives the post-decision state flip.
- **Eligibility:** `src/features/workflows/hooks/useAllowedActions.ts` (`actorRole` appended to the query key). `normalizeActions(...)` drops unknown wire actions (forward-compat).
- **Panel-with-polling-hook shape:** `ReviewerVerdictPanel.tsx` + `useReviewerVerdict.ts` (presentational + container + hook; `StateSignifierChip` for color-independence; `data-testid` per field). Mirror this for the lint/CI panels. (Do **not** modify `ReviewerVerdictPanel` — BMAD is extracted.)
- **Stage list:** `RunStepTokensPanel.tsx` + `useStepExecutions.ts` — the model for the build-status surface.
- **a11y:** `src/lib/a11y/useLiveAnnouncement.ts` (`.ts`, returns `''` first render then the message one commit later — assert via `waitFor`); vocabulary `src/lib/a11y/announcements.ts`; the route's **persistent always-mounted announcer** (`manual-execution-result-announcer` pattern) for results that fire after a panel unmounts on state advance — the lint/delivery approvals need this.
- **Client / keys:** `src/lib/api/client.ts` (`apiClient`, `unwrap`), types via `components['schemas'][...]`; shared options `src/lib/api/queryOptions.ts`; key factory `src/lib/queryKeys/workflowKeys.ts` (`no-inline-query-keys` is a build error).

### Build-gating eslint local rules (all `--max-warnings=0`)

- `no-role-based-action-gating` — gate only via `useAllowedActions`, never an audit role or bare state (Decision 3).
- `react-refresh/only-export-components` — non-constant helpers/hooks in `.ts`, not `.tsx` [[frontend-react-refresh-no-fn-exports]]. View-models (`buildStatusView.ts`, `deliveryBarView.ts`) are flat `.ts`.
- `no-inline-query-keys`; `single-primary-action` (one primary per `DecisionArea`); `no-workflow-toast-success` (workflow-significant outcomes use inline UI + live region, never toast-only); `no-untyped-loading-state`; `no-unsanitized-html`; `no-workflow-domain-in-ui-primitives` (keep workflow vocab out of `src/components/ui/**`).

### Testing standards summary

- **Framework:** Vitest + jsdom + RTL + MSW (`src/test/server.ts`, handlers `src/test/handlers.ts`, `onUnhandledRequest: 'error'`); axe via `expectNoA11yViolations` (`src/test/a11y/axe.ts`, WCAG 2.1 AA). Route tests mount the real generated `routeTree` in `createMemoryHistory`. `cleanup()` in `afterEach` (no auto-clean). Coverage floor: `src/features/workflows/** = 85%` (`vitest.config.ts`).
- **Real typecheck:** `npm run build` (tsc -b) — `tsc --noEmit`/vitest/eslint miss test-file type errors [[frontend-tsc-noemit-misses-test-files]].
- **Lockfile:** if deps change, regenerate with a full `npm install` (Vite native bindings are platform-specific; committed `.npmrc` has `legacy-peer-deps=true`) [[frontend-lockfile-cross-platform]] [[frontend-ts6-legacy-peer-deps]]. This story adds no deps — do not touch the lockfile unless forced.
- **Verify in a clean env:** local green ≠ CI green; run the full FE gate (`build`, `check:api`, `check:a11y`, `lint`, `test`) [[verify-ci-fixes-in-clean-env]].

### Logging Requirements (project-wide standard)

The only server-side change is a read-only projection widening (Task 1). SLF4J + Logback applies to that touch only; no `System.out`, no `printStackTrace()`. Log a `WARN` **only** on the defensive resolver fallback (`push_mode_unresolvable`) carrying `workflowRunId`; never log project internals, tokens, or PII. The FE has no logging surface. Pin any new log line with a `ListAppender`/`OutputCaptureExtension` assertion (watch the reused-fork redaction-holder trap).

### Project Structure Notes

- Components → `src/features/workflows/components/` (co-located `*.test.tsx`). Hooks → `src/features/workflows/hooks/`. View-models → flat `.ts` under `src/features/workflows/`. Do **not** add a route file — extend the existing detail route (Decision 4).
- Backend: `WorkflowInspectionService` (application), `WorkflowDetailResponse` (adapters.rest); read-only projection read may extend `WorkflowRunPersistenceAdapter`. No new Flyway migration (columns exist). Summary DTO untouched.

### References

- [Source: `_bmad-output/planning-artifacts/epic-03h-pre-review-quality-gates.md#Story 3h-6`]
- [Source: `_bmad-output/implementation-artifacts/3h-1-build-validation-stage-and-bounded-auto-fix-loop.md` — build stage, `build_fix_loop_count` (V33)]
- [Source: `_bmad-output/implementation-artifacts/3h-2-cpu-linter-gate-and-waiting-for-lint-approval-hard-gate.md` — `LintFindingsResponse`, `/lint-findings`, `WaitingForLintApproval`, `approve_lint`/`request_lint_fix`, `lint_fix_loop_count` (V34)]
- [Source: `_bmad-output/implementation-artifacts/3h-4-push-mode-and-unified-delivery-gate-and-pr-flag.md` — `WaitingForDelivery`, `approve_delivery`, `pushMode`/`autoCreatePullRequest` (V38), `ProjectRuntimeConfigResolver.resolvePushMode`/`resolveAutoCreatePullRequest`]
- [Source: `_bmad-output/implementation-artifacts/3h-5-ci-build-error-investigation-github-actions.md` — `ciStatus`/`ciHeadSha`/`ciFixLoopCount`/`ciChecksEnforced` on `WorkflowDetailResponse`; CI status vocabulary]
- [Source: `deliveryline-backend/.../adapters/rest/WorkflowDetailResponse.java`, `.../adapters/rest/LintFindingsResponse.java`, `.../adapters/rest/AllowedActionsResponse.java`, `.../domain/registry/WorkflowState.java`, `.../domain/registry/AllowedAction.java`]
- [Source: FE code-map — `src/routes/workflows/$workflowRunId/index.tsx`, `src/features/workflows/components/{WorkflowDecisionBar,ImplementationReviewDecisionBarContainer,ReviewerVerdictPanel,RunStepTokensPanel}.tsx`, `src/features/workflows/hooks/{useAllowedActions,useWorkflowDetail,useStepExecutions}.ts`, `src/lib/a11y/{useLiveAnnouncement,announcements}.ts`, `src/lib/api/{client,schema.d.ts}`, `src/test/{server,handlers,setup}.ts`, `eslint.config.js` local rules]

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
