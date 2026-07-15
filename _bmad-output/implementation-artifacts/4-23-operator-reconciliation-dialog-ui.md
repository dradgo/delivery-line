# Story 4.23: Operator Reconciliation Dialog UI

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner reviewing a detected integration conflict (from stories 4.17 + 4.18) and deciding how to reconcile,
I want a dedicated reconciliation dialog UI that shows the internal-state snapshot vs external-state snapshot side-by-side, surfaces the safety-ranked `ReconciliationDecision` options, requires an explicit decision + rationale, and submits via story 4.11's reconcile endpoint,
so that NFR19 (no silent overwrite) is enforced at the UI layer — an operator cannot reconcile without seeing both states and choosing an explicit, justified decision.

## Acceptance Criteria

1. **Given** `src/features/workflows/components/ReconciliationDialog.tsx`, **Then** it accepts `ReconciliationDialogProps { workflowRunId: string; conflictId: string; open: boolean; onClose: () => void }` and consumes a **new** `useIntegrationConflict(conflictId)` TanStack Query hook backed by story 4.18's `GET /api/v1/integration-conflicts/{conflictId}` (returns the `IntegrationConflictDetail` wire type). The query is `enabled` only while `open && conflictId` is set (dialog-scoped fetch).

2. **Given** anatomy, **Then** the dialog displays: a header with the conflict category (`conflictCategory`) + integration type (`integrationType`, e.g. `github_pr` → "GitHub", `linear` → "Linear"); **side-by-side panels** — `internalStateSnapshot` (left) vs `externalStateSnapshot` (right) — with field-level diff highlighting (added / removed / modified) using state tokens from story 2.3; a `ReconciliationDecision` radio group with the safety-ranked suggestion pre-selected and marked "Recommended"; a **required** `reasonText` textarea (NFR19 enforcement); and Cancel + "Confirm reconcile" buttons (the latter `intent="danger"` — reconcile is a non-reversible state assertion).

3. **Given** the snapshot panels, **Then** they render `internalStateSnapshot` / `externalStateSnapshot` — which arrive as **raw JSON strings** (nullable) — through a **defensive parse**: known fields (e.g. `state`, `branch`, PR/issue state, commit SHA, ticket status) render as labeled rows with diff highlighting; unknown fields fall back to a "Raw metadata" expandable section showing prettified JSON; a snapshot that fails `JSON.parse` (or is `null`) degrades to a plain-text "Snapshot unavailable / unparseable" affordance rather than throwing. All rendered snapshot text is treated as untrusted (external metadata) — sanitized, never `dangerouslySetInnerHTML`.

4. **Given** the safety-ranked decision options from the detail response's `suggestedDecisions` (each `{ decision, safety }` where `safety ∈ {safe, risky}` — **the wire has no `caution` tier**), **Then** each option renders with its safety level visible using the same visual vocabulary as story 4.22 (`safetyTone()` → `StateSignifierChip`: `safe`→success `[SAFE]`, `risky`→error `[RISKY]`); the **recommended** option is the FIRST entry of `suggestedDecisions` (the list is safe-first ordered — there is no explicit recommended flag) and is pre-selected (operator can change); selecting a `risky` option surfaces an inline warning explaining what could go wrong (from a FE decision-consequence copy map — see Dev Notes; e.g. "`accept_internal_state` will re-assert the internal state and may re-open the externally-merged PR").

5. **Given** required-decision enforcement per NFR19 + story 4.6 AC3, **Then** the "Confirm reconcile" button is disabled until BOTH a `resolutionDecision` is selected AND `reasonText` is non-empty (after trim); submission calls a **new** `useReconcileWorkflow(workflowRunId)` mutation hook that POSTs `ReconcileWorkflowRequest { conflictId, resolutionDecision, reasonText, role: 'workflow_owner' }` to story 4.11's endpoint with a minted `Idempotency-Key` header (via `useWorkflowMutation`) and the `X-Actor-Identity` header. **No optimistic version stamps** are sent — the reconcile request carries none; concurrency is guarded by `conflictId` + `CONFLICT_ALREADY_RESOLVED`.

6. **Given** error handling per story 4.11 AC4, **When** the backend returns `CONFLICT_ALREADY_RESOLVED` (409 — another operator reconciled in parallel), **Then** the dialog renders a stale-state notice ("This conflict was already resolved") with a "Refresh and try again" CTA (re-fetches the conflict detail); `CONFLICT_NOT_FOUND` (404) and `RECONCILE_NOT_APPLICABLE` (409 — the run left a reconcilable state) render equivalent explanatory terminal states; `IDEMPOTENCY_KEY_CONFLICT` (409) surfaces the standard retry-safe path. (There is **no** `ACTION_NOT_ALLOWED` code for reconcile.)

7. **Given** focus management per UX-DR18 + story 2.23 AC5, **Then** focus moves into the dialog on open and returns to the triggering element on close (Cancel or successful submission — the reused `ConfirmationDialog`/sheet already restores focus). **Esc** dismisses; but when `reasonText` has been edited, Esc (and any close attempt) first shows a "Discard your reconciliation note?" confirm-discard prompt to prevent accidental loss of work — this is custom logic layered ON TOP of the base dialog's default immediate-Esc behavior.

8. **Given** the dialog launch contexts, **Then** it can be invoked from: (a) the Decision Bar `recovery_operator` mode via story 4.22's `onReconcile` seam (wire `onReconcile={openReconcileDialog}` into `RecoveryDecisionBarContainer`); (b) the failure-diagnostics deep-dive drift indicator (`FailureEventSurface.tsx` currently shows a static drift tooltip — replace with a control that opens this dialog); (c) the operator queue conflict-flagged row (story 4.2) — see OQ-3 for how a queue row (which carries only `unresolvedConflictCount`, not a `conflictId`) reaches a conflict-scoped dialog.

9. **Given** mobile responsiveness per story 2.26, **Then** at the mobile breakpoint (`useResponsiveLayout() === 'mobile'`) the dialog renders via `BoundedDetailSheet` (`side="bottom"`, `fullHeightOnMobile`) and the side-by-side snapshot panels collapse to a **stacked** layout (internal state above, external state below); the decision radio group + `reasonText` + buttons remain prominent; desktop/tablet render the standard dialog.

10. **Given** ARIA per story 2.25, **Then** the dialog has `role="dialog"` + `aria-labelledby` (header) + `aria-describedby` (the consequence/warning text of the selected decision) — inherited from the radix-backed base dialog; keyboard fully operable; a live region announces submission state via **new** `announcements.ts` entries (`recoveryReconcileInitiated` / `recoveryReconcileRecorded` + a submit-failure path) wired through `useLiveAnnouncement`.

11. **Given** component test coverage, **Then** tests cover: side-by-side snapshot rendering with field-level diff highlighting + raw/unparseable-snapshot fallback, recommended option pre-selected (first `suggestedDecisions` entry), Confirm disabled until decision + reasonText set, `risky` inline warning renders, submission via `useReconcileWorkflow` sends `role: 'workflow_owner'` + `Idempotency-Key`, `CONFLICT_ALREADY_RESOLVED` stale-state UI + refresh, focus into/out + Esc-with-edited-reason discard prompt, mobile stacked layout (no side-by-side <768px), the wired launch contexts open the dialog, and axe-core zero `wcag2aa` violations.

## Tasks / Subtasks

- [ ] **Task 1 — `useIntegrationConflict` query hook** (AC: #1, #6)
  - [ ] Create `src/features/workflows/hooks/useIntegrationConflict.ts` modeled on `useFailureDiagnostics.ts`: `useQuery({ queryKey: workflowKeys.integrationConflict(conflictId), queryFn: () => unwrap(apiClient.GET('/api/v1/integration-conflicts/{conflictId}', { params: { path: { conflictId } } })), enabled: open && !!conflictId, staleTime: STALE_TIME.detail })`.
  - [ ] Add `integrationConflict: (conflictId: string) => [...workflowKeys.all, 'integrationConflict', conflictId] as const` to `src/lib/queryKeys/workflowKeys.ts` (rooted at `all`, NOT under `detail(runId)` — the endpoint is keyed by `conflictId`, not run id). Update `workflowKeys.test.ts` for the new member. NEVER inline the key array (`no-inline-query-keys` ESLint rule).
- [ ] **Task 2 — `useReconcileWorkflow` mutation hook** (AC: #5, #6, #10)
  - [ ] Create `src/features/workflows/hooks/useReconcileWorkflow.ts` on the `useWorkflowMutation` factory, mirroring `useResumeWorkflow.ts` / `usePauseWorkflow.ts`. Variables: `{ conflictId: string; resolutionDecision: ReconciliationDecision; reasonText: string }`. `mutationFn({ variables, idempotencyKey })` POSTs `/api/v1/workflows/{workflowRunId}/reconcile` with body `{ conflictId, resolutionDecision, reasonText, role: RECOVERY_OPERATOR_ROLE }` and header `[IDEMPOTENCY_KEY_HEADER]: idempotencyKey`. Return type = `ReconcileResponse`.
  - [ ] In `onSuccess`, in addition to the factory's `detail(runId)` + `lists()` invalidation, **also invalidate** `workflowKeys.integrationConflict(conflictId)` and `workflowKeys.failureDiagnostics(runId)` so the resolved conflict + drift indicator refresh (the factory does not know the conflict key).
- [ ] **Task 3 — `ReconciliationDialog.tsx` shell + data states** (AC: #1, #2, #6, #9)
  - [ ] Create `src/features/workflows/components/ReconciliationDialog.tsx`. Choose the container by `useResponsiveLayout()`: `BoundedDetailSheet` (`side="bottom"`, `fullHeightOnMobile`) on `'mobile'`, else `ConfirmationDialog`/right-sheet. Both are controlled (`open`, `onOpenChange`). Import overlays ONLY from `@/components/overlays` (generic infra; must not be imported the other way).
  - [ ] Render loading (skeleton matching layout), error, and the resolved-conflict body; header shows category + integration type.
- [ ] **Task 4 — Snapshot panels + defensive JSON diff** (AC: #2, #3, #9)
  - [ ] Build `reconciliationDialogView.ts` (sibling `.ts`, NOT the `.tsx` — react-refresh forbids non-component exports from `.tsx`): pure helpers `parseSnapshot(raw: string | null): { ok: boolean; fields?: Record<string, unknown>; raw: string }`, `diffSnapshots(internal, external)` → per-field `{ status: 'added'|'removed'|'modified'|'unchanged' }`, and the decision-consequence copy map (Task 6).
  - [ ] Panels render known labeled fields with added/removed/modified state-token treatment (reuse `SafeUnifiedDiffRenderer`/`SafeDiffRenderer` where the shape fits; otherwise labeled rows), an expandable "Raw metadata" prettified-JSON section, and a plain-text fallback for `null`/unparseable snapshots. Stack panels vertically on mobile (internal above external).
- [ ] **Task 5 — Decision radio group + recommended pre-selection** (AC: #4)
  - [ ] Render one radio per `suggestedDecisions[]` entry; pre-select `suggestedDecisions[0].decision` (safe-first order = recommended); tag it "Recommended".
  - [ ] Show each option's safety via `safetyTone()` → `StateSignifierChip` (safe→success `[SAFE]`, risky→error `[RISKY]`). Map decision wire values to human-readable labels.
- [ ] **Task 6 — Risky inline warning (FE consequence copy)** (AC: #4)
  - [ ] In `reconciliationDialogView.ts`, author a static consequence-copy map keyed by `ReconciliationDecision` (optionally category-aware) derived from `ConflictReconciliationSuggester`'s per-category javadoc semantics. When the selected option's `safety === 'risky'`, render the warning inline (this text is the `aria-describedby` target per AC10).
- [ ] **Task 7 — Required-fields gating + submit** (AC: #5, #7, #10)
  - [ ] Disable "Confirm reconcile" until `resolutionDecision` selected AND `reasonText.trim().length > 0`.
  - [ ] On confirm, call `useReconcileWorkflow(...).mutate({ conflictId, resolutionDecision, reasonText })`; announce `recoveryReconcileInitiated`; on success announce `recoveryReconcileRecorded`, close, and let query invalidation refresh surfaces.
  - [ ] Esc / close-attempt while `reasonText` is dirty → nested "Discard your reconciliation note?" confirm-discard prompt before closing (custom; base dialog Esc is immediate by default).
- [ ] **Task 8 — Error / stale-state handling** (AC: #6)
  - [ ] Map `ProblemDetailsError.code`: `CONFLICT_ALREADY_RESOLVED` → stale notice + "Refresh and try again" (refetch detail); `CONFLICT_NOT_FOUND` / `RECONCILE_NOT_APPLICABLE` → explanatory terminal states; `IDEMPOTENCY_KEY_CONFLICT` → standard retry-safe surface; announce `decisionSubmitFailed` on unhandled errors.
- [ ] **Task 9 — Announcements** (AC: #10)
  - [ ] Add `recoveryReconcileInitiated` / `recoveryReconcileRecorded` (+ reuse `decisionSubmitFailed`) to `src/lib/a11y/announcements.ts` under the recovery section; satisfy the `announcement-vocabulary` node-test (`npm run check:a11y`).
- [ ] **Task 10 — Wire launch contexts** (AC: #8)
  - [ ] Decision Bar: in `RecoveryDecisionBarContainer.tsx`, own dialog open-state + pass `onReconcile={openReconcileDialog}` into `ApprovalDecisionBar` (mirror the container-owns-dialog pattern; if a post-reconcile state flip could unmount the bar, hoist the mutation into `WorkflowDecisionBar` as resume/rerun/pause were). Update `RecoveryDecisionBarContainer.full.test.tsx` + the reconcile-seam assertions in `ApprovalDecisionBar.recovery.test.tsx` (the disabled-placeholder path now has a handler).
  - [ ] Failure diagnostics: in `FailureEventSurface.tsx` (the drift indicator at the deferred-modal comment ~L293), replace the static tooltip with a control opening this dialog.
  - [ ] Operator queue: per OQ-3 resolution.
- [ ] **Task 11 — Tests** (AC: #11)
  - [ ] Component tests (`ReconciliationDialog.test.tsx`) with Vitest + Testing Library + `user-event`; mock the conflict GET + reconcile POST via MSW `server.use(...)` inside a per-test `QueryClientProvider` (`mutations: { retry: retryUnlessNonRetryable, retryDelay: 0 }`). Assert every AC11 case; run `await expectNoA11yViolations(document.body)` against the open (portaled) content, in both desktop and mobile layouts.
- [ ] **Observability instrumentation** (cross-cutting — FE adaptation of the project logging standard)
  - [ ] This is a **frontend-only** story; the SLF4J/Logback "Logging Requirements" below are the backend project standard and are **N/A** here (no server code is touched). The FE-equivalent observable surfaces are: (a) the ARIA live-region announcements (Task 9) for state changes, (b) `ProblemDetailsError`-typed error surfaces (Task 8) that carry `code` + `correlationId` (already stamped on every request by `headerMiddleware` / `X-Correlation-Id`), and (c) the standard TanStack Query error boundary. Do **not** `console.log` snapshot bytes, external metadata, or the `reasonText` (may contain operator-sensitive prose). No new `console.*` calls; rely on the existing error-surface + announcement channels.

## Dev Notes

### Scope boundary (READ FIRST)

- **This story OWNS:** `ReconciliationDialog.tsx`, `reconciliationDialogView.ts` (pure helpers + consequence copy), `useIntegrationConflict.ts`, `useReconcileWorkflow.ts`, the `integrationConflict` query key, `recoveryReconcile*` announcements, and wiring `onReconcile` into `RecoveryDecisionBarContainer` (+ the `FailureEventSurface` drift control). Story 4.22 built the **seam** (`onReconcile?` prop, disabled-placeholder, `reconcile_conflict` token plumbing, `Paused`-state bar selection) and tested it — do NOT rebuild that; supply the handler.
- **Reuse, do NOT rebuild:** `ConfirmationDialog`, `RationaleCaptureDialog`, `BoundedDetailSheet` (all `@/components/overlays`); `useWorkflowMutation`, `useResponsiveLayout`, `useLiveAnnouncement`; `safetyTone()` + `StateSignifierChip`; the diff renderers; `apiClient` + `unwrap`.
- **Backend is DONE:** stories 4.11 (reconcile endpoint, COMMITTED) and 4.18 (conflict list/detail + suggester) are present in `openapi.json` AND `schema.d.ts` — **no backend change and no `generate-api` regen is needed** unless OQ-1 (add `detectedAt`) is accepted.

### Backend contract (live-verified against source + openapi.json + schema.d.ts)

**Read — `GET /api/v1/integration-conflicts/{conflictId}` → `IntegrationConflictDetail`** (`IntegrationConflictController.java:153`, DTO `IntegrationConflictDetailResponse.java`):
- `conflictId`, `workflowRunId`, `integrationLinkId`, `integrationType?` (nullable, e.g. `github_pr`), `externalRef`
- `conflictCategory`: `external_state_advanced | external_state_reverted | external_resource_removed | metadata_drift | link_broken` — **note `external_resource_removed`, NOT `external_state_resource_removed`**
- `resolvedAt?` (nullable) — **there is NO `detectedAt` on the detail** (see OQ-1)
- `externalStateSnapshot?` / `internalStateSnapshot?`: **raw JSON strings, nullable** — FE parses
- `suggestedDecisions: SuggestedReconciliationDecision[]` — each `{ decision: <4-enum>, safety: 'safe' | 'risky' }`, **safe-first ordered**, no `caution`, no recommended flag
- 404 `CONFLICT_NOT_FOUND`; read-only (no Idempotency-Key / actor headers).

**Write — `POST /api/v1/workflows/{workflowRunId}/reconcile` → `ReconcileResponse`** (`WorkflowController.java:2279`, `operations["reconcile"]`):
- Headers: `Idempotency-Key` (required), `X-Actor-Identity` (optional)
- Body `ReconcileWorkflowRequest`: `{ conflictId, resolutionDecision: 'accept_external_state'|'accept_internal_state'|'mark_completed_externally'|'mark_failed_externally', reasonText, role }` — `role` must be the literal `'workflow_owner'` (`RECOVERY_OPERATOR_ROLE`); `resolutionDecision` + `reasonText` are NOT `@NotBlank` server-side (so the typed `MISSING_*` codes stay reachable) — but the UI still requires both (AC5).
- 200 `ReconcileResponse`: `{ workflowRunId, currentState, recoveryActionId, resolvedConflictId, replayed, reconciledEventId?, correlationId? }`
- Errors: 400 `MISSING_RECONCILIATION_DECISION` / `INVALID_RECONCILIATION_DECISION` / `INVALID_COMMAND_PAYLOAD` / `INVALID_REVIEWER_ROLE_FOR_ENDPOINT` / idempotency-key 400s; 404 `RUN_NOT_FOUND` / `CONFLICT_NOT_FOUND`; 409 `RECONCILE_NOT_APPLICABLE` / `CONFLICT_ALREADY_RESOLVED` / `IDEMPOTENCY_KEY_CONFLICT`. **No `ACTION_NOT_ALLOWED`.**

### Create-time traps (each caused a wrong assumption in the epic text — honor these)

1. **No `detectedAt` in the detail response.** AC2 asks for a "detected-at timestamp" in the header but the detail DTO carries only `resolvedAt`. `detectedAt` lives on the LIST summary row (`IntegrationConflictSummary`), not the single-conflict detail. → **OQ-1** (recommended: header shows category + integration only, or widen the backend detail DTO). Do NOT invent a timestamp.
2. **Safety is two-level (`safe` / `risky`), NOT the epic's `safe`/`caution`/`risky`.** `ConflictReconciliationSuggester` emits only those two at the wire. Render exactly what arrives; `caution` never appears from `suggestedDecisions`. (The Decision Bar's aggregate reconcile affix defaults to `caution` in `approvalDecisionView` — that is a SEPARATE concern from the per-decision suggestions this dialog renders. Do not conflate.)
3. **No "recommended" flag** — recommended = `suggestedDecisions[0]` (safe-first ordering).
4. **Snapshots are raw JSON strings, not objects** — parse defensively; malformed/`null` must degrade, not throw (external metadata is attacker-influenceable — treat as untrusted, sanitize, never `dangerouslySetInnerHTML`).
5. **Reconcile has no version stamps.** Unlike spec/impl approvals, `ReconcileWorkflowRequest` carries no `expectedArtifactVersion`/`expectedContextBundleVersion`. Concurrency is guarded by `conflictId` + `CONFLICT_ALREADY_RESOLVED`. Do not add version fields.
6. **Body uses `role`, not `reviewerRole`/`actorType`.** Mirror `useResumeWorkflow`/`usePauseWorkflow` exactly (`role: RECOVERY_OPERATOR_ROLE`, actor via `X-Actor-Identity` header, idempotency key minted by `useWorkflowMutation`).
7. **Invalidate the conflict key on success.** `useWorkflowMutation` invalidates `detail(runId)` + `lists()` only — NOT the `conflictId`-keyed detail. Add explicit invalidation of `integrationConflict(conflictId)` + `failureDiagnostics(runId)` (Task 2).
8. **Esc-with-dirty-reason is custom.** `ConfirmationDialog`/radix Esc closes immediately; AC7's "confirm before discard when reasonText edited" must be layered on top (intercept `onOpenChange(false)` / Esc when dirty).
9. **`no-inline-query-keys` + react-refresh.** Query keys come only from the factory; non-component exports (the consequence map, parse/diff helpers) live in the sibling `reconciliationDialogView.ts`, never exported from the `.tsx` — see [[frontend-react-refresh-no-fn-exports]].
10. **`confirmationCatalog` — do NOT add a reconcile entry.** Per the 4.22 decision, reconcile carries its own consequence copy in its own dialog (there is deliberately no `reconcileConflict` catalog id). Adding one would force a lockstep edit to `confirmationCatalog.test.ts`'s `EXPECTED_ACTIONS`.

### Files to create / touch

**Create:**
- `src/features/workflows/components/ReconciliationDialog.tsx`
- `src/features/workflows/components/reconciliationDialogView.ts` (parse/diff helpers + decision-consequence copy)
- `src/features/workflows/hooks/useIntegrationConflict.ts`
- `src/features/workflows/hooks/useReconcileWorkflow.ts`
- `src/features/workflows/components/__tests__/ReconciliationDialog.test.tsx` (+ hook tests as siblings dictate)

**Touch:**
- `src/lib/queryKeys/workflowKeys.ts` (+ `workflowKeys.test.ts`) — add `integrationConflict`
- `src/lib/a11y/announcements.ts` — add `recoveryReconcile*`
- `src/features/workflows/components/RecoveryDecisionBarContainer.tsx` (+ `RecoveryDecisionBarContainer.full.test.tsx`) — wire `onReconcile`
- `src/features/workflows/components/ApprovalDecisionBar.recovery.test.tsx` — handler-present assertions
- possibly `src/features/workflows/components/WorkflowDecisionBar.tsx` (+ `WorkflowDecisionBar.paused.test.tsx`) — hoist mutation if needed for state-flip survival
- `src/features/workflows/components/FailureEventSurface.tsx` — drift indicator → open dialog

### Reuse map (exact symbols)

- Overlays (`@/components/overlays`): `ConfirmationDialog` (controlled; `consequence` REQUIRED; `intent="danger"`→destructive confirm; focus-in + focus-restore built in), `RationaleCaptureDialog` (composes `ConfirmationDialog` + typed fields + per-field validation gating — closest precedent), `BoundedDetailSheet` (`side`, `fullHeightOnMobile`, `h-[90dvh] max-sm:h-[100dvh]`).
- Hooks: `useWorkflowMutation({ workflowRunId, mutationFn })` (mints UUIDv7 Idempotency-Key, reuses on retry, invalidates detail+lists), `useResponsiveLayout()` → `'mobile'|'tablet'|'desktop'` (768/1024 breakpoints; single source per `RESPONSIVE.md`), `useLiveAnnouncement(message)`.
- View helpers: `safetyTone(level)` (`failureDiagnosticsView.ts`) → `'safe'|'caution'|'risky'|'neutral'`; `StateSignifierChip` (greyscale-safe `[SAFE]`/`[RISKY]`); `RECOVERY_OPERATOR_ROLE` from `../approvalDecisionView`.
- API: `apiClient.GET/POST` + `unwrap` (`@/lib/api/client`), `ProblemDetailsError` (`@/lib/api/problemDetails`), `IDEMPOTENCY_KEY_HEADER` (`@/lib/api/idempotency`), `STALE_TIME` + `retryUnlessNonRetryable` (`@/lib/api/queryOptions`).
- State tokens: `state-signifiers.ts` (12-state `StateName` union), `--state-*` CSS vars (`globals.css`), diff renderers `SafeUnifiedDiffRenderer.tsx` / `SafeDiffRenderer.tsx`.

### Logging Requirements (project-wide standard)

This is the **backend** SLF4J/Logback standard. **Story 4.23 touches no server code — it does not apply here.** See the "Observability instrumentation" task for the frontend-equivalent surfaces (ARIA announcements, typed `ProblemDetailsError` with `correlationId`, TanStack error boundary). Never log `reasonText`, snapshot bytes, or external metadata.

### Project Structure Notes

- Dialog is a **feature component** under `src/features/workflows/components/`; it may import from generic overlays (`src/components/overlays`) but overlays must not import features (one-way dependency).
- Compare Mode (stories 4.20/4.21) is a separate deeper-inspection surface — the reconciliation dialog is unrelated; do not reuse Compare Mode components.
- Verify with `npm run build` (tsc -b **typechecks test files** — `tsc --noEmit`/vitest/eslint alone do not — see [[frontend-tsc-noemit-misses-test-files]]), `npm run test`, `npm run lint`, `npm run check:a11y`.

### References

- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.23] — the 11 ACs + launch contexts + NFR19 rationale
- [Source: deliveryline-backend/.../adapters/rest/IntegrationConflictController.java:153] — `GET /{conflictId}` detail
- [Source: deliveryline-backend/.../adapters/rest/IntegrationConflictDetailResponse.java] — detail wire shape (no `detectedAt`; snapshots raw JSON strings; `safety` safe/risky)
- [Source: deliveryline-backend/.../application/integration/conflict/ConflictReconciliationSuggester.java] — per-category safe/risky ranking + semantics (source for the FE consequence copy)
- [Source: deliveryline-backend/.../adapters/rest/WorkflowController.java:2279] — reconcile endpoint + error contract
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:1700,2239,2249,2674] — `IntegrationConflictDetail`, `ReconcileResponse`, `ReconcileWorkflowRequest`, `SuggestedReconciliationDecision`
- [Source: deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.tsx:310] — the `onReconcile` handoff comment (story 4.23 seam)
- [Source: deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx:157,745,780] — `onReconcile?` prop + `reconcile_conflict` dispatch + disabled-placeholder copy
- [Source: deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx:293] — drift-indicator deferred-modal comment (launch context b)
- [Source: deliveryline-frontend/src/components/overlays/ConfirmationDialog.tsx] + `BoundedDetailSheet.tsx` + `RationaleCaptureDialog.tsx` — dialog/sheet primitives
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts] + `useResumeWorkflow.ts` / `usePauseWorkflow.ts` — mutation pattern
- [Source: deliveryline-frontend/src/features/workflows/hooks/useFailureDiagnostics.ts] — query-hook pattern for `useIntegrationConflict`
- [Source: deliveryline-frontend/src/lib/a11y/announcements.ts:136] — recovery announcement section

### Open Questions for Alex

- **OQ-1 (detected-at in header, AC2):** the detail response has no `detectedAt` (only `resolvedAt`); the list summary has it. Options: (a) **[recommended]** header shows conflict category + integration type only (drop detected-at) — zero backend change; (b) widen `IntegrationConflictDetailResponse` + `ConflictResolutionView` to expose `detectedAt` (the row's `detected_at` is `timestamptz NOT NULL`) — small governed backend change + `generate-api` regen + `OpenApiSnapshotContractTest` update; (c) pass `detectedAt` as a prop from the launch context (only the queue/list surfaces have it — Decision Bar / drift indicator do not, so the header would be inconsistent). Default if unanswered: **(a)**.
- **OQ-2 (`caution` tier):** the wire safety is `safe`/`risky` only. Confirm the dialog renders exactly those two (no synthetic `caution`), even though epic AC4 references the 3-level 4.22 vocabulary. Default: render the two emitted levels; reserve `caution` styling for nothing.
- **OQ-3 (operator-queue launch, AC8c):** the operator queue row (story 4.2) carries only `unresolvedConflictCount`, not a `conflictId`, so it cannot open a conflict-scoped dialog directly. Options: (a) **[recommended]** the queue "Reconcile" affordance navigates to the run detail / operator panel where the conflict list (or Decision Bar / drift indicator) opens this dialog with a concrete `conflictId`; (b) the queue fetches the run's conflict list on demand and, if exactly one unresolved conflict, opens the dialog inline (ambiguous when >1). Default: **(a)** — treat AC8c as "navigates to a surface that launches the dialog", keeping this story's owned launch points to the Decision Bar seam + the drift indicator.
- **OQ-4 (post-reconcile close vs summary):** after a successful reconcile, close the dialog immediately (relying on query invalidation to refresh the run + drift surfaces), or show an in-dialog success summary (actor/action/new state) before closing? Default: **close immediately + announce `recoveryReconcileRecorded`** (the Decision Bar's own post-submit summary from story 4.22 AC9 carries the persistent outcome).

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
