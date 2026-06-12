# Story 3.30: UI Minimum-Viable-Recovery Baseline (Failed Stage / Last Successful / Retry Action)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **Sprint note (2026-06-12):** Pulled forward from the deferred epic-3b slice into the **epic-2b active slice** (`sprint-change-proposal-2026-06-12.md`). Story id unchanged. **Frontend-only** — the backend retry path already shipped in story 1.18 (`RecoveryService.retry`, `deliveryline retry` CLI, and the REST endpoint). The deeper "rerun from an earlier step after fixing a runner config" stays in Epic 4 (4.7 / 4.12 / 4.22).

## Story

As a Product Manager / Developer / Workflow Owner viewing a failed run before Epic 4 ships,
I want the UI minimum-viable-recovery baseline (failed stage + last successful stage + failure timestamp + failure category + retry action) surfaced in the run detail view + Decision Bar `recovery_operator` mode (the story 2.19 stub variant) — mirroring the CLI baseline from story 1.18,
so that the first agent-execution failure during pilot use does not strand the team before Epic 4's full operator console + reconciliation lands.

## Acceptance Criteria

> ACs copied verbatim from `epic-03-agent-execution.md` §Story 3.30. Reconciliation notes (marked **⚠ RECONCILE**) point at the live contract where the AC text predates it; the live contract is authoritative — see Dev Notes.

1. **Given** the run timeline component (rendered in run detail view), **When** a workflow event of type `runner.failed` / `runner.timeout` / `runner.crash` / `runner.contractViolation` / `git.pushFailed` is present, **Then** it renders with a prominent failure visual treatment using `state-error` token (story 2.3) + non-color signifier (icon + "Failed" label) + failure category from `FailureCategory` registry rendered as human-readable badge (e.g., "Runner Timeout"). **⚠ RECONCILE:** no run-timeline UI exists yet (the run detail route renders no event list). This story builds a minimal failure-aware event surface — see Dev Notes §"AC1 — the missing timeline".
2. **Given** the Run Context Strip (story 2.16) on a failed run, **Then** it surfaces: **failed stage** (state prior to `Failed`), **last successful stage**, **failure timestamp**, **failure category** (from registry), **last activity timestamp**, **next safe action** (mirroring story 1.18 AC5 CLI behavior). **⚠ RECONCILE:** all six fields already exist on the live `WorkflowDetailResponse` (`failedStage`, `lastSuccessfulStage`, `failureTimestamp`, `failureCategory`, `lastActivityAt`, `nextSafeAction` — `schema.d.ts:349–365`). This AC is **additive rendering only**, gated on `currentState === 'Failed'`; no backend/contract change.
3. **Given** the Decision Bar `recovery_operator` mode (stub from story 2.19 AC1), **Then** the stub is replaced with a real implementation when run state is `Failed` AND backend allowed-actions include `retry`: primary action `Retry failed step` opens a confirmation dialog (story 2.23 AC1) with consequence text `"Retry will re-execute the last failed step with a fresh runner. The previous failure will be preserved in the timeline."` + Cancel + "Confirm retry" buttons.
4. **Given** retry submission, **When** invoked, **Then** the UI calls a `useRetryWorkflow` mutation hook calling the existing CLI-baseline retry path from story 1.18 (via the REST endpoint — see AC10). **⚠ RECONCILE:** the endpoint is `POST /api/v1/workflows/{workflowRunId}/retry-workflow` (operationId `retryWorkflow`), **not** `/retry` — already in the generated client.
5. **Given** scope discipline per Epic 1 refinement R1 + story 1.18 AC11 ArchUnit scope-protected `RecoveryService`, **Then** the UI does NOT expose any deeper recovery actions in E3 — no reconcile, no resume, no rerun-from-arbitrary-step, no failure-taxonomy classification controls; those wait for Epic 4. Decision Bar in `recovery_operator` mode shows ONLY `Retry failed step` (or `View only` when retry is not safe).
6. **Given** the failure diagnostics surface, **Then** clicking a failure event in the timeline expands a detail panel showing: failure category + reason text + `correlationId` (so users can grep logs / story 3.7 ELK if available) + a link to redacted runner logs from story 3.6 (download link calls a backend endpoint — **placeholder URL**, full implementation in Epic 4).
7. **Given** `state-error` token treatment from story 2.3 + WCAG 2.1 AA from story 2.25, **Then** failure visuals meet contrast requirements + non-color signifier + an ARIA live region announces failure-state-entry when the user is on the run page.
8. **Given** the Queue Item (story 2.15) on a failed run, **Then** it displays a `state-error` badge + failure category in compact form + the `Failed` attention indicator becomes the primary indicator (per story 2.15 AC5 priority order — failure ranks alongside `blocker` for visibility). **⚠ RECONCILE:** the live `WorkflowSummary` list carries `currentState` (so the `Failed` badge is live) but **not** `failureCategory` — the compact category display is DORMANT (fixture-tested), consistent with the existing `runQueueRow` dormancy pattern. See Dev Notes §"AC8".
9. **Given** the foundation fixture event stream from story 1.23 includes the execution-failure-with-retry scenario, **Then** this story's UI tests render against that fixture and assert: failure event renders prominently, recovery baseline information surfaces in the Run Context Strip, the retry confirmation dialog opens correctly, the retry mutation invalidates queries to refresh state. **(Fixture already present:** `src/test/fixtures/event-streams/execution-failure-with-retry.json`.)
10. **Given** REST `POST /api/v1/workflows/{workflowRunId}/retry` endpoint, **Then** if not already added, this story adds it… **⚠ RECONCILE: NO-OP — already shipped in story 1.18.** The endpoint exists at `/retry-workflow` with `RetryWorkflowRequest`, Idempotency-Key handling, and Problem Details for `RETRY_NOT_APPLICABLE` (409) / `ILLEGAL_TRANSITION` (409) / `RUN_NOT_FOUND` (404); CLI/REST equivalence holds; the OpenAPI snapshot + `schema.d.ts` already include it. This AC reduces to **verification only** — do not regenerate or add a backend endpoint.
11. **Given** component + integration test coverage, **Then** tests cover: failure event renders with correct visual treatment, Run Context Strip surfaces all baseline fields when run is `Failed`, Decision Bar `recovery_operator` mode shows only `Retry failed step`, retry confirmation dialog enforces the consequence text + Cancel preserves state, retry mutation succeeds + invalidates queries, scope discipline holds (no deeper recovery actions visible in E3 UI), failure diagnostics expand panel shows `correlationId` + log link, queue item shows failed state with priority indicator, ARIA live region announces failure entry, axe-core a11y zero violations.

## Tasks / Subtasks

- [x] **Task 1 — `useRetryWorkflow` mutation hook (AC4, AC10)**
  - [x] New file `deliveryline-frontend/src/features/workflows/hooks/useRetryWorkflow.ts`. Mirror `useApproveSpec.ts` / `useRejectSpec.ts` exactly: ride the `useWorkflowMutation` factory (inherits the once-per-attempt UUIDv7 `Idempotency-Key` + `detail(runId)` prefix invalidation + `lists()` invalidation).
  - [x] Call `apiClient.POST('/api/v1/workflows/{workflowRunId}/retry-workflow', …)` — **the `-workflow` suffix is required**; `RetryWorkflowRequest = components['schemas']['RetryWorkflowRequest']`.
  - [x] Body: `{ actorIdentity, actorType, correlationId?, reasonText? }` — **`actorIdentity` + `actorType` are required (`@NotBlank` / `@NotNull`) on the backend.** **DECIDED (OQ-1 resolved):** send `actorIdentity: 'local-operator'`, `actorType: 'HUMAN'` via a single shared constant `LOCAL_ACTOR_IDENTITY` in a new `src/lib/api/actor.ts` (the one seam to swap when real auth/session lands). This is the exact value the backend already stamps for every other UI governance action (it's the `deliveryline.security.local-actor-identity` property default that approve/reject/clarify fall back to), so the retry audit trail stays consistent. Do NOT add a form field — retry is one-click. `reasonText` may be wired later from an optional rationale input; omit when blank (optional-field spread, mirror `useApproveSpec`).
  - [x] Surface typed failures via `ProblemDetailsError`: `RETRY_NOT_APPLICABLE` (409), `ILLEGAL_TRANSITION` (409), `RUN_NOT_FOUND` (404), idempotency 409s.
  - [x] Co-locate a `useRetryWorkflow.test.tsx` mirroring `useApproveSpec.test.tsx`.
- [x] **Task 2 — Decision Bar `recovery_operator` real mode (AC3, AC5)**
  - [x] In `approvalDecisionView.ts`: add a real resolution branch for `mode === 'recovery_operator'` (today `resolveApprovalBarState` renders `disabled` for every non-`spec_approval` mode — `ApprovalDecisionBar.tsx:451`). Gate: `currentState === 'Failed'` AND `normalizeActions(allowed.actions)` includes the retry action → primary `Retry failed step`; else `View only`. Keep all view-model derivation in the pure `.ts` (react-refresh rule).
  - [x] In `ApprovalDecisionBar.tsx`: replace the `case 'recovery_operator': return <StubPlaceholder … epic="Epic 4" />;` with the real render. Use `<DecisionArea primary={…} />` — **exactly one primary** (`single-primary-action` ESLint rule); `View only` is not a primary CTA.
  - [x] Wire `recovery_operator` selection at the call site: extend `ApprovalDecisionBarContainer.tsx` (or add a thin sibling container) to choose `mode='recovery_operator'` when `detail.currentState === 'Failed'`, and call `useRetryWorkflow`. Update `routes/workflows/$workflowRunId/index.tsx:170` which currently hardcodes the default `spec_approval` container — make the mode state-driven. Reuse the `useAllowedActions` eligibility pattern (no permission-inference module — AC11/OQ-5 of 2.19).
  - [x] Confirm-before: import `CONFIRMATION_CATALOG.retryOrRecoverConsequential` and render `<ConfirmationDialog intent="warning" consequence={…} confirmLabel="Confirm retry" cancelLabel="Cancel" />`. **Update the catalog entry** (`confirmationCatalog.ts:87–94`): set `consequenceTemplate` to the AC3 exact text and change `owningStory` from `'Epic 4'` to `'3.30'`.
  - [x] On confirm → `useRetryWorkflow().mutate(...)`. On success: announce + let prefix invalidation refresh `detail`/`events`/`allowedActions`. Field-only structured logging (`console.info({ event: 'recovery.retrySubmit', currentState })`) — **never** `reasonText`/ids/PII (T-LOG-PII).
- [x] **Task 3 — Run Context Strip recovery fields (AC2)**
  - [x] Extend `RunContextStrip.tsx` + its view model (`runContextView.ts` / `runContextFormat.ts`) to render, **only when `currentState === 'Failed'`**: failed stage, last successful stage, failure timestamp (relative + UTC tooltip, mirror `lastTransitionAt`), failure category (humanized — Task 6), last activity timestamp, next safe action. Source every field from the already-present `useWorkflowDetail` fields (`failedStage`, `lastSuccessfulStage`, `failureTimestamp`, `failureCategory`, `lastActivityAt`, `nextSafeAction`).
  - [x] Render `nextSafeAction` **verbatim-humanized** from the response (values: `retry`, `await_outcome`, `view_only`, `await_manual_reconciliation`) — do NOT hardcode `await_operator_action` (AC2's example label predates the live enum).
  - [x] Absent fields use the existing `<NotReported />` pattern; do not exceed `RUN_CONTEXT_STRIP_MAX_HEIGHT` (3.5rem) — collapse recovery fields into a secondary row/expander if tight (mirror 2.16/3.31 lightweight rule).
- [x] **Task 4 — Failure event surface + diagnostics panel (AC1, AC6)**
  - [x] Build a minimal failure-aware event display in the run detail route, fed by `useWorkflowEvents(workflowRunId)`. Render failure events (`runner.failed` / `runner.timeout` / `runner.crash` / `runner.contractViolation` / `git.pushFailed`, plus `recovery.retried`) with the `state-error` token + the `error` state signifier (icon + "Failed" label). **Scope-discipline (AC5):** a *minimal* failure list, not a general-purpose timeline (Epic 4 owns the full operator console) — see OQ-2.
  - [x] Clicking a failure event expands a `BoundedDetailSheet` (story 2.23) detail panel showing: failure category (humanized) + reason text + `correlationId` (selectable/copyable for log grep) + a placeholder runner-logs download link (story 3.6 endpoint not yet wired — render a disabled/placeholder affordance, no fabricated URL).
- [x] **Task 5 — Queue Item failed treatment (AC8)**
  - [x] In `RunReviewQueueItem.tsx` + `runQueueRow.ts`: when `currentState === 'Failed'`, render a `state-error` badge and make `Failed` the primary attention indicator (extend `AttentionIndicator` + `resolvePrimaryAttentionIndicator` so failure ranks at/above `blocker`). `Failed` is LIVE (from `summary.currentState`).
  - [x] Compact failure-category display is **DORMANT** (the live `WorkflowSummary` has no `failureCategory`) — build + test it via constructed fixtures only; never fabricate from live data (mirror the existing `runQueueRow` dormancy reconciliation).
- [x] **Task 6 — Failure-category humanizer + signifiers (AC1, AC2, AC8)**
  - [x] New pure helper `failureCategoryView.ts` (NON-component `.ts`, react-refresh rule): map the wire enum (`runner_timeout`→"Runner Timeout", `runner_crash`→"Runner Crash", `runner_contract_violation`→"Contract Violation", `runner_non_zero_exit`→"Non-Zero Exit", `runner_late_result`→"Late Result", `runner_duplicate_result`→"Duplicate Result", `runner_malformed_output`→"Malformed Output", `runner_secret_leak`→"Secret Leak", `orphan`→"Orphaned"). Unknown values degrade gracefully (title-case the raw token).
  - [x] Reuse the existing `state-signifiers.ts` `error` (OctagonX) + `recovery` (RotateCcw) entries — do NOT add new StateNames.
- [x] **Task 7 — A11y announcements (AC7)**
  - [x] Add retry/failure vocabulary to `lib/a11y/announcements.ts` (e.g. `failureEntered`, `retryInitiated`, `retryRecorded`). Announce failure-state-entry via the existing live-region mechanism when the run page mounts in `Failed` state; announce retry submission. Reuse the established live-region reconciliation (no duplicate announcements).
  - [x] Success feedback via the inline `aria-live`/GovernedButton `completed` path — **NOT** a toast (`no-workflow-toast-success` rule, UX-DR15).
- [x] **Task 8 — Tests + a11y gate (AC9, AC11)**
  - [x] Component tests (Vitest + Testing Library) for every surface above, rendering against `executionFailureStream` (and constructed fixtures for the dormant queue category). Assert: failure event prominence, Run Context Strip baseline fields when `Failed`, Decision Bar shows ONLY `Retry failed step` (scope discipline — assert reconcile/resume/rerun controls are absent), confirmation dialog enforces consequence text + Cancel preserves state, retry mutation success invalidates `detail`/`lists`, diagnostics panel shows `correlationId` + log-link, queue item failed priority indicator, ARIA failure announcement.
  - [x] Call `expectNoA11yViolations(container)` in every new component test (WCAG 2.1 AA tags).
  - [x] (Optional, per story 3.35 AC7) a Playwright keyboard-only retry-flow journey may be added there; not required to close 3.30.
- [x] **Task 9 — AC10 verification (no code)**
  - [x] Confirm `retryWorkflow` is present in `schema.d.ts` and the hook compiles against it. Do NOT run `regen-openapi` or touch the backend.
- [x] **Logging / observability (frontend adaptation of the cross-cutting standard)**
  - [x] Backend logging for the retry path already ships in story 1.18's `RecoveryService` (untouched here). On the frontend, follow the established **field-only** structured-console pattern from `ApprovalDecisionBarContainer.tsx`: `console.info`/`console.warn({ event, currentState, code })` at retry-submit, retry-success, and retry-error (version/transport split). **Never** log `reasonText`, run/actor ids, tokens, or PII (T-LOG-PII). Add a focused test asserting the success path logs the expected `event` field at the expected call (mirror the approval container's logging assertions).

## Dev Notes

This is a **frontend-only** story. The backend (`deliveryline-backend`) is complete and **must not be modified**. The implementer's entire surface is `deliveryline-frontend/`.

### The five reconciliations that prevent disasters

1. **Endpoint is `/retry-workflow`, not `/retry` (AC4/AC10).** `WorkflowController.retry()` maps `POST /api/v1/workflows/{workflowRunId}/retry-workflow`, operationId `retryWorkflow`. It is already in `openapi.json` and the generated `schema.d.ts`. **AC10 is verification-only — do not add an endpoint or run `regen-openapi`.** [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java#540–558]
2. **`RetryWorkflowRequest` requires a non-blank actor — DECIDED (OQ-1 resolved).** Unlike `ApproveSpecRequest` (which carries no actor; the controller resolves it from the optional `X-Actor-Identity` header → property default `local-operator`, with `actorType` hardcoded `HUMAN`), `RetryWorkflowRequest` takes `@NotBlank actorIdentity` + required `actorType` in the **body**. The frontend has no live actor/session context. **Send `actorIdentity: 'local-operator'`, `actorType: 'HUMAN'`** via a shared `LOCAL_ACTOR_IDENTITY` constant (`src/lib/api/actor.ts`) — the SAME value the backend already stamps for every UI governance action, so the retry audit trail stays consistent. Audit-only field (not used for idempotency or authz), safe to constant until real auth lands; that constant is the single swap-seam. [Source: deliveryline-backend/.../rest/RetryWorkflowRequest.java#9–10; WorkflowController.java#336–377 (header→`local-operator` fallback + `ActorType.HUMAN`); application/security/LocalActorIdentityResolver.java#48,153–174 (≤128 + safe-charset)]
3. **AC2's six fields already exist on the wire** (`failedStage`, `lastSuccessfulStage`, `failureTimestamp`, `failureCategory`, `lastActivityAt`, `nextSafeAction`) — `schema.d.ts:349–365`. AC2 is additive rendering, no contract change. `nextSafeAction` enum is `retry | await_outcome | view_only | await_manual_reconciliation` (NOT `await_operator_action`). [Source: deliveryline-backend/.../application/recovery/RecoveryService.java#132–135, 714–817]
4. **AC8 failure-category in the queue is DORMANT.** The live `GET /api/v1/workflows → WorkflowSummary[]` carries `currentState` (so the `Failed` badge IS live) but not `failureCategory` (`schema.d.ts` WorkflowSummary #456–490). Render the `Failed` `state-error` badge + priority indicator from live `currentState`; build/test the compact category via fixtures only — exactly the `runQueueRow.ts` dormancy convention. Do NOT widen the backend projection (out of frontend scope; defer to story 6.9/Epic 3 enrichment).
5. **No run-timeline UI exists (AC1).** `routes/workflows/$workflowRunId/index.tsx` renders RunContextStrip + ClarificationRegion + ApprovalDecisionBar, **no event list**. Task 4 builds a *minimal* failure-event surface from `useWorkflowEvents`; the full timeline/operator console is Epic 4 (scope discipline, AC5).

### Source-tree map (mirror these patterns — exact paths)

- **Mutation hook to clone:** `src/features/workflows/hooks/useApproveSpec.ts` (+ `useRejectSpec.ts`, `useSubmitClarification.ts`). Factory: `hooks/useWorkflowMutation.ts` (idempotency + invalidation). Query keys: `lib/queryKeys/workflowKeys.ts` (use the factory — `no-inline-query-keys` rule). API client: `lib/api/client.ts` (`apiClient.POST`, `unwrap`, auto `Idempotency-Key`/`X-Correlation-Id` middleware). Idempotency header const: `lib/api/idempotency.ts`.
- **Decision Bar:** presentational `components/ApprovalDecisionBar.tsx` (the `recovery_operator` stub is at the `case 'recovery_operator'` switch arm); view model `approvalDecisionView.ts` (`ApprovalBarMode` union already includes `'recovery_operator'`; `resolveApprovalBarState`); container `components/ApprovalDecisionBarContainer.tsx` (live `useAllowedActions` + `useWorkflowDetail`, mutation wiring, field-only logging). Actions primitive: `components/actions/DecisionArea.tsx` + `GovernedButton`.
- **Run Context Strip:** `components/RunContextStrip.tsx` + `runContextView.ts` + `runContextFormat.ts`; `<NotReported />`, `RUN_CONTEXT_STRIP_MAX_HEIGHT`, relative-time-with-UTC-tooltip helpers all already there.
- **Queue Item:** `components/RunReviewQueueItem.tsx` + `runQueueRow.ts` (`AttentionIndicator`, `resolvePrimaryAttentionIndicator`, `STATE_CONTAINER_CLASSES`, `StateSignifierChip`).
- **Overlays:** `components/overlays/ConfirmationDialog.tsx` (props: `open`, `intent`, `consequence` [required], `confirmLabel`, `cancelLabel`, `onConfirm`, `isConfirming`, focus-restoration baked in), `BoundedDetailSheet.tsx` (detail panel for AC6), catalog `lib/overlays/confirmationCatalog.ts`.
- **Tokens / signifiers:** `src/styles/globals.css` `--state-error*` scale (#143–148); `lib/state-signifiers.ts` (`error`→OctagonX, `recovery`→RotateCcw — reuse).
- **A11y:** `test/a11y/axe.ts` (`expectNoA11yViolations`, WCAG21AA tags); `lib/a11y/announcements.ts` (live-region vocabulary); `components/feedback/AuditRoleLabel.tsx` (wrap any actor-role text — `no-bare-actor-role-text` rule).
- **Fixtures / MSW:** `test/fixtures/event-streams/execution-failure-with-retry.json` (failure `evt_fix_fail_010` + `recovery.retried` `evt_fix_fail_012`), `test/fixtures/event-streams/index.ts` (`executionFailureStream`), `test/handlers.ts` (default GET handlers for list/detail/events/allowed-actions), `test/server.ts` (`server.use(...)` per-test overrides).
- **Route:** `src/routes/workflows/$workflowRunId/index.tsx` (TanStack file-based router; `RECOGNIZED_STATES` already includes `Failed`).

### Custom ESLint rules this story MUST obey

`tools/eslint-rules/index.js`: **no-inline-query-keys** (keys via `workflowKeys` only) · **single-primary-action** (one `data-decision-primary` per decision area) · **no-confirmation-for-navigation** (no `<Link>` inside `<ConfirmationDialog>`) · **no-workflow-toast-success** (success via `aria-live`, never toast) · **no-bare-actor-role-text** (use `<AuditRoleLabel>`) · **react-refresh/only-export-components** (pure helpers live in `.ts` siblings, not `.tsx`). `max-warnings=0`, TS strict, `no-unnecessary-condition`.

### Testing standards

Vitest + Testing Library; tests co-located (`*.test.tsx` or `__tests__/`). MSW handlers derive list/detail/events/allowed-actions responses from the fixture event streams (`test/handlers.ts`); override per-test with `server.use(...)`. Every component test calls `expectNoA11yViolations(container)`. Pin new structured-log surfaces with a focused assertion (mirror the approval container's logging tests). Run `npm run lint`, `tsc`, `vitest`, `prettier --write` before claiming done (see memory: prettier gate cascades CI; verify lockfile/CI shape).

### Logging Requirements (project-wide standard)

Backend retry logging is already in place (story 1.18 `RecoveryService`); this story does not touch backend. Frontend observability follows the established **field-only** `console.info`/`console.warn({ event, currentState, code, transport })` pattern (`ApprovalDecisionBarContainer.tsx`). Forbidden in output: `reasonText`, run/actor ids, tokens, raw PII (T-LOG-PII). New frontend log surfaces (`recovery.retrySubmit`, `recovery.retryError`) get a focused test assertion.

### Project Structure Notes

- All work lands under `deliveryline-frontend/src/features/workflows/**`, `…/components/overlays/**`, `…/lib/**`, `…/test/**`. No backend, no `openapi.json`, no `schema.d.ts` regeneration.
- New pure helpers (`failureCategoryView.ts`, view-model extensions) go in NON-component `.ts` files per the react-refresh rule (memory: [[frontend-react-refresh-no-fn-exports]]).
- Verify on Linux/CI before pushing (memory: [[frontend-lockfile-cross-platform]], [[prettier-gate-cascades-ci]], [[verify-ci-fixes-in-clean-env]]); the Bash tool is RTK-corrupted — use native tools + PowerShell ([[rtk-hook-only-matches-bash]]).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.30] — ACs 1–11.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-12.md] — pull-forward rationale, backend-already-done finding, Epic-4 boundary.
- [Source: deliveryline-backend/.../adapters/rest/WorkflowController.java#125–156, 207–272, 540–558] — detail response (failure fields + `nextSafeAction`), allowed-actions endpoint, retry endpoint.
- [Source: deliveryline-backend/.../adapters/rest/RetryWorkflowRequest.java] — required `actorIdentity`/`actorType`, optional `correlationId`/`reasonText`.
- [Source: deliveryline-backend/.../application/recovery/RecoveryService.java] — retry baseline + `describeFailure` (next-safe-action values).
- [Source: deliveryline-backend/.../domain/registry/FailureCategory.java, WorkflowEventType.java, AllowedAction.java, DomainErrorCode.java] — wire enums.
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts#349–365, 419, 456–490] — detail failure fields, `failureCategory` enum, WorkflowSummary (no category).
- [Source: deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.ts, useWorkflowMutation.ts] — mutation pattern.
- [Source: deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBarContainer.tsx, ApprovalDecisionBar.tsx, approvalDecisionView.ts] — Decision Bar wiring + `recovery_operator` stub.
- [Source: deliveryline-frontend/src/lib/overlays/confirmationCatalog.ts#87–94] — `retryOrRecoverConsequential` (re-own + retext).
- [Source: deliveryline-frontend/src/test/fixtures/event-streams/execution-failure-with-retry.json] — the AC9 fixture.

## Open Questions / Decisions for Alex

1. **OQ-1 — actor identity for `RetryWorkflowRequest` — ✅ RESOLVED 2026-06-12.** Decision: send `actorIdentity: 'local-operator'`, `actorType: 'HUMAN'` via a shared `LOCAL_ACTOR_IDENTITY` constant (`src/lib/api/actor.ts`). Chosen because `local-operator` is the exact value the backend already records for every UI governance action (the `deliveryline.security.local-actor-identity` default that approve/reject/clarify fall back to), keeping the retry audit trail consistent; the actor is audit-only (not used for idempotency/authz), and the constant is the single seam to replace when a real auth/session context lands. See Task 1 + Dev Notes reconciliation #2.
2. **OQ-2 — failure-surface scope (AC1/AC6).** No run-timeline exists. Recommended: a *minimal* failure-event list + expandable diagnostics panel (not a general-purpose timeline — Epic 4). Confirm minimal scope is acceptable, or specify how much of a timeline to build now.
3. **OQ-3 — Decision Bar wiring shape.** Extend the existing `ApprovalDecisionBarContainer` with state-driven mode selection (`recovery_operator` when `Failed`), vs. add a parallel `RecoveryDecisionBarContainer`. Recommended: extend the existing container (the mode union + `recovery` signifier already exist) to avoid duplicating the allowed-actions/staleness plumbing.

---

## Dev Agent Record

### Completion Notes

Frontend-only implementation (no backend touched; AC10 verified, not regenerated). Mirrored the story-2.19 Decision Bar architecture throughout.

- **Task 1 — `useRetryWorkflow` hook + actor seam.** New `src/lib/api/actor.ts` exports the single `LOCAL_ACTOR_IDENTITY = 'local-operator'` / `LOCAL_ACTOR_TYPE = 'HUMAN'` seam (OQ-1). `useRetryWorkflow.ts` rides the `useWorkflowMutation` factory (inherits the once-per-attempt UUIDv7 Idempotency-Key + `detail(runId)`/`lists()` invalidation), POSTs `/api/v1/workflows/{workflowRunId}/retry-workflow` (the `-workflow` suffix), and spreads `reasonText`/`correlationId` only when present.
- **Task 2 — Decision Bar `recovery_operator` real mode.** Added `'retry'` to the `DecisionAction` union + `KNOWN_ACTIONS`; `resolveApprovalBarState` now resolves `recovery_operator` to `ready`/`disabled`(View only)/`submitting`/`success`/`error` via the new `canRetry(view)` gate (`currentState === 'Failed'` AND allowed-actions include `retry`). Replaced the E4 `StubPlaceholder` in `ApprovalDecisionBar.tsx` with a real render using `<DecisionArea primary={<GovernedButton priority="primary">Retry failed step</GovernedButton>} />` + the shared 2.23 `<ConfirmationDialog>` (consequence from the re-owned `CONFIRMATION_CATALOG.retryOrRecoverConsequential`, "Confirm retry"/"Cancel"). Wiring lives in a thin sibling `RecoveryDecisionBarContainer.tsx` (OQ-3 — keeps the spec-approval container untouched); the route picks the mode from `detail.currentState === 'Failed'`. Field-only logging: `recovery.retrySubmit` (success) + `recovery.retryError`.
- **Task 3 — Run Context Strip recovery fields.** Extended `runContextView.ts` with the five already-on-the-wire failure fields; `RunContextStrip.tsx` renders them in a separate `aria-label="Recovery baseline"` region BELOW the (still height-capped) orientation strip, only when `currentState === 'Failed'`. `nextSafeAction` is humanized verbatim (never the stale `await_operator_action`).
- **Task 4 — Failure event surface + diagnostics.** New `FailureEventSurface.tsx` reads `useWorkflowEvents`, renders a MINIMAL failure list (live event types `runner.failed`/`runner.timeout`/`runner.orphaned`/`recovery.dispatchFailed` + the `recovery.retried` marker — AC1's `runner.crash`/`runner.contractViolation` are reconciled as `failureCategory` values), and opens a `BoundedDetailSheet` with category + reason + selectable `correlationId` + a disabled placeholder runner-logs affordance (story 3.6 not wired).
- **Task 5 — Queue Item failed treatment.** `runQueueRow.ts`: added `failed` to `AttentionIndicator` + `QueueItemState` (ranks above `blocker`), LIVE from `currentState === 'Failed'`; the compact `failureCategory` chip is DORMANT (fixtures only). `RunReviewQueueItem.tsx` renders the `state-error` row + `Failed` primary indicator.
- **Task 6 — `failureCategoryView.ts`** humanizes `FailureCategory` + `nextSafeAction` wire enums, title-casing unknown tokens (forward-compat). Reuses the existing `error`/`recovery` state signifiers (no new StateNames).
- **Task 7 — A11y.** Added `failureEntered`/`retryInitiated`/`retryRecorded` to the announcement vocabulary; failure-state-entry + retry lifecycle announced through the Decision Bar's single existing live region (no duplicate regions; `check:a11y` green). Success feedback is inline `aria-live`, never a toast.
- **Tasks 8/9 — Tests + AC10.** Component/unit/integration tests + `expectNoA11yViolations` on every new surface. AC10 is verification-only: `retryWorkflow` is present in `schema.d.ts` and the hook compiles against it (no regen).

**Validation:** `tsc -b` clean · `eslint --max-warnings=0` clean · `vitest run` 871/871 · `check:a11y` 4/4 · `lint:rules-test` (all 9 custom rules) green · `check:fixtures` green · `prettier --check` clean. (Verified locally on Windows via PowerShell per [[rtk-hook-only-matches-bash]]; verify on Linux/CI before merge per [[frontend-lockfile-cross-platform]]/[[prettier-gate-cascades-ci]].)

### File List

**New (source):**
- `deliveryline-frontend/src/lib/api/actor.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useRetryWorkflow.ts`
- `deliveryline-frontend/src/features/workflows/failureCategoryView.ts`
- `deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.tsx`
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx`

**New (tests):**
- `deliveryline-frontend/src/features/workflows/hooks/useRetryWorkflow.test.tsx`
- `deliveryline-frontend/src/features/workflows/failureCategoryView.test.ts`
- `deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.test.tsx`

**Modified (source):**
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.ts` (retry action + `canRetry` + recovery state/consequence/context label)
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx` (real `recovery_operator` render + `onRetry` + recovery announcements)
- `deliveryline-frontend/src/features/workflows/runContextView.ts` (recovery baseline fields)
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx` (recovery baseline region)
- `deliveryline-frontend/src/features/workflows/runQueueRow.ts` (`failed` indicator/state + dormant `failureCategory`)
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx` (failed treatment)
- `deliveryline-frontend/src/lib/a11y/announcements.ts` (recovery vocabulary)
- `deliveryline-frontend/src/lib/overlays/confirmationCatalog.ts` (re-owned `retryOrRecoverConsequential` text + owningStory `3.30`)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (state-driven decision bar + failure surface)
- `deliveryline-frontend/src/test/handlers.ts` (`Failed` allowed-actions entry)

**Modified (tests/fixtures):**
- `deliveryline-frontend/src/test/fixtures/approval/approvalDecisionFixtures.ts` (`recoveryOperatorView` View-only + new `recoveryReadyView`)
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.test.ts`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.test.tsx`
- `deliveryline-frontend/src/features/workflows/runContextView.test.ts`
- `deliveryline-frontend/src/features/workflows/__tests__/runQueueRow.test.ts`
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx`

### Change Log

- 2026-06-12 — Implemented story 3.30 (UI minimum-viable-recovery baseline): retry mutation hook + actor seam, Decision Bar `recovery_operator` real mode with confirm-before, Run Context Strip recovery baseline, minimal failure-event surface + diagnostics, queue-item failed treatment, failure-category humanizer, recovery a11y vocabulary. Frontend-only; AC10 verification-only. All quality gates green (status → review).

---
**Ultimate context-engine analysis completed — comprehensive developer guide created.**

---

### Review Findings

> bmad-code-review 2026-06-13 — 3-layer adversarial review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the story-scoped working-tree diff (26 files: 17 modified +682/−77, 9 new ~879 lines). Triage: **2 decision-needed, 1 patch, 10 defer, 13 dismissed**. Acceptance Auditor verdict: all 11 ACs MET and all five reconciliations honored against the *verified* live contract (`schema.d.ts`, the fixture, CSS tokens, ConfirmationDialog). The two decisions are architectural gaps the AC-lens did not weight (surfaced by the edge/blind layers). Notable dismissed false-positive: "`decisionContextLabel` computed but never rendered" — it IS rendered for every mode at `ApprovalDecisionBar.tsx:600`.

#### Decision-needed (RESOLVED 2026-06-13 — Alex chose Option 1 / patch-now for both)

- [x] [Review][Decision→Patch] Recovery bar silently swallows allowed-actions / detail load errors — RESOLVED: patch now (see Patch P2).
- [x] [Review][Decision→Patch] Retry success feedback is racy / likely-unseen — RESOLVED: patch now (see Patch P3).

#### Patch (ALL APPLIED + verified 2026-06-13)

- [x] [Review][Patch] P1 — Run Context Strip recovery timestamps rendered but unasserted [`RunContextStrip.test.tsx`] — APPLIED: added `<time>`-datetime assertions for `run-recovery-failed-at` + `run-recovery-last-activity` (and `not "Not reported"`) to the AC2 baseline test. Test-only.
- [x] [Review][Patch] P2 — Surface allowed-actions / detail load errors in recovery mode [`RecoveryDecisionBarContainer.tsx`, `ApprovalDecisionBar.tsx`] — APPLIED: container now derives `loadError = allowedActionsQuery.isError || detailQuery.isError`, passes it to the bar, and logs field-only `recovery.allowedActionsLoadError` (code + transport, no PII); the bar's `showLoadError` gate widened to `spec_approval || recovery_operator` so a failed read renders the load-error ErrorState + Refresh instead of "View only". New test asserts the error surface + log.
- [x] [Review][Patch] P3 — Make retry success feedback survive the mode swap [new `components/WorkflowDecisionBar.tsx`, `routes/workflows/$workflowRunId/index.tsx`, `RecoveryDecisionBarContainer.tsx`] — APPLIED: extracted a `WorkflowDecisionBar` selector that OWNS the `useRetryWorkflow` instance and keeps the recovery bar mounted while a retry is `isPending || isSuccess` (so the success panel + AC7 `retryRecorded` announcement aren't torn down when a successful retry flips `currentState` Failed→Executing — confirmed against `RecoveryService` line 124). The same instance is threaded into the container via a new optional `retry` prop (internal fallback preserves standalone mounts). New `WorkflowDecisionBar.test.tsx` drives a real retry through MSW (detail flips Failed→Executing) and asserts the recovery success panel persists.

**Verification (2026-06-13, PowerShell per [[rtk-hook-only-matches-bash]]):** `tsc -b` 0 · `eslint . --max-warnings=0` 0 · `prettier --write` clean · `vitest run` **873/873** (+2 net new tests). Verify on Linux/CI before merge ([[frontend-lockfile-cross-platform]] / [[prettier-gate-cascades-ci]] / [[verify-ci-fixes-in-clean-env]]).

**New files from review:** `src/features/workflows/components/WorkflowDecisionBar.tsx` (+ `.test.tsx`).

#### Deferred (low — real, not actionable now)

- [x] [Review][Defer] FailureEventSurface relative times don't tick [`FailureEventSurface.tsx:168`] — `const nowMs = Date.now()` captured per-render, no interval (RunContextStrip ticks). Consistent with `RunReviewQueueItem.tsx:344`, so low-priority. — deferred, hardening
- [x] [Review][Defer] `humanizeNextSafeAction`/`titleCaseToken` lowercases acronyms in unknown tokens [`failureCategoryView.ts:39`] — `runner_OOM` → "Runner Oom"; lossy only for non-lowercase forward-compat values. — deferred, hardening
- [x] [Review][Defer] `humanizeFailureCategory` guards `undefined`/blank but not `null` [`failureCategoryView.ts:54`] — wire type is `… | null`; type-safe today (callers coerce `?? undefined`), guard the helper itself for robustness. — deferred, hardening
- [x] [Review][Defer] Failure events rendered in raw stream order, no sort/dedup [`FailureEventSurface.tsx`] — "Failure timeline" heading implies order; fail→retry→fail yields unordered/duplicate rows. Tests cover only the single-failure fixture. — deferred, hardening
- [x] [Review][Defer] No client-side staleness gate in recovery mode [`RecoveryDecisionBarContainer.tsx`] — a stale "Retry failed step" can be clicked; server guards it (RETRY_NOT_APPLICABLE / ILLEGAL_TRANSITION → error+refresh), but unlike the spec-approval bar there's no version-stamp pre-empt. — deferred, server-guarded
- [x] [Review][Defer] Idempotency-replay 409 surfaced as generic error [`useRetryWorkflow.ts` / `ApprovalDecisionBar.tsx:494–505`] — a duplicate-submission 409 (first attempt actually succeeded) shows "could not be submitted (CODE). Refresh and try again." mislabeling a succeeded retry. — deferred, low-frequency
- [x] [Review][Defer] Success visible text duplicates the `retryRecorded` constant [`ApprovalDecisionBar.tsx:478–480`] — the `<p>` literal is hand-copied from `announcements.ts` `retryRecorded`; edit-one-drifts-the-other. Source both from the constant. — deferred, maintainability
- [x] [Review][Defer] `useRetryWorkflow` spreads optionals on `!== undefined`, not blank [`useRetryWorkflow.ts`] — an empty-string `reasonText`/`correlationId` would go on the wire as present-but-blank; latent (no caller passes blank today — `retry.mutate({})`). — deferred, latent
- [x] [Review][Defer] AC11 retry-mutation query-invalidation asserted only at the factory level [`useRetryWorkflow.test.tsx`] — invalidation is inherited+tested via `useWorkflowMutation.test.tsx` (mirrors `useApproveSpec`); no retry-hook-specific `detail`/`lists` invalidation assertion. — deferred, factory-covered
- [x] [Review][Defer] Run Context Strip tooltips: raw snake_case stage values vs humanized category/next-action [`RunContextStrip.tsx`] — `failedStage`/`lastSuccessfulStage` `title=` show raw tokens while category/next-action show humanized text; minor SR/tooltip inconsistency. — deferred, cosmetic
