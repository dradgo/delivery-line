# Design: Archive / Unarchive button in the UI

**Date:** 2026-07-03
**Status:** Approved (design), pending implementation plan
**Scope:** Frontend only (`deliveryline-frontend`). No backend, schema, migration, or OpenAPI change.

## Problem

The backend (story 3d-8) ships full soft-hide/archive support — `POST /api/v1/workflows/{id}/archive` and `/unarchive`, a `workflow.archived` / `workflow.unarchived` audit event, and the `archive_run` / `unarchive_run` allowed-actions — plus a CLI. But **the web UI never wired a control to trigger it**: no component calls `archiveRun`/`unarchiveRun`. The queue can only *display* a passive "Hidden" chip for already-archived runs (`RunReviewQueueItem`), and the `includeArchived` URL filter already exists. There is no delete (by design — runs are append-only/audit-preserving; soft-hide is the only "remove").

This design adds the missing UI: a governed Archive / Unarchive control on the run-detail page.

## Key discovery — zero backend change

`WorkflowInspectionService.computeActionMatrix` already threads a `boolean archived` and appends exactly one of `AllowedAction.ARCHIVE_RUN` (live run) / `AllowedAction.UNARCHIVE_RUN` (already-hidden run), scoped to the `workflow_owner` actor role (mirroring RETRY / OPEN_DIAGNOSTIC_CONSOLE). The FE `useAllowedActions(runId, actorRole)` returns `actions: string[]` verbatim, so `archive_run` / `unarchive_run` pass through even though they are not in the typed enum.

Consequence: the button is gated the same idiomatic way as every other governed action. We do **not** need to add `archivedAt` to the detail response (it is only on `WorkflowSummary` today), and there is **no OpenAPI/FE-client regen**.

## Placement

Run-detail page (`routes/workflows/$workflowRunId/index.tsx`), in a small **"Run actions"** area beneath the run id/state header, separate from the state-driven `WorkflowDecisionBar` (which owns Retry / approve). Archive is a low-frequency, mildly consequential lifecycle action → a subtle secondary button, not a prominent primary.

Out of scope (YAGNI): queue-row button, bulk archive, hard delete.

## Components (following existing patterns)

### 1. Hooks — `useArchiveRun(runId)` / `useUnarchiveRun(runId)`
Mirror `useTakeoverWorkflow`: built on the `useWorkflowMutation` factory, which mints one UUIDv7 idempotency key per attempt and, on success, invalidates `workflowKeys.detail(id)` (a prefix of `events` / `allowedActions`) plus `workflowKeys.lists()`.

- `useArchiveRun`: `POST /api/v1/workflows/{workflowRunId}/archive`, body `ArchiveRunRequest { reason }` (required), `Idempotency-Key` header. Variables: `{ reason: string }`.
- `useUnarchiveRun`: `POST /api/v1/workflows/{workflowRunId}/unarchive`, body `UnarchiveRunRequest { reason? }` (optional), `Idempotency-Key` header. Variables: `{ reason?: string | undefined }` — omit the body field when blank.
- Both return the generated `ArchiveRun` response (`{ archivedAt, currentState, replayed?, workflowRunId }`). `reason` is user-authored → pass through, never log (T-LOG-PII).

### 2. `RunArchiveControl` (container)
- Calls `useAllowedActions(runId, 'workflow_owner')`. Renders **nothing** unless `actions` includes `archive_run` or `unarchive_run` (self-hiding — matches every other advisory/governed surface).
- Owns dialog open-state and both mutations. Renders one `<GovernedButton priority="secondary">` (subtle, non-primary; `workflowState="submitting"` while the mutation is pending → inherits the spinner + `aria-busy` + non-interactive treatment):
  - `archive_run` present → **"Archive run"** → opens the archive dialog.
  - `unarchive_run` present → **"Unarchive run"** → opens the unarchive dialog.
- Uses `RationaleCaptureDialog` (composes `ConfirmationDialog`; required-field validation gates confirm):
  - **Archive:** one **required** `reason` textarea, `validate` enforcing max 512 chars (parity with backend `@Size(max = 512)`). Consequence: *"This hides the run from the default review queue. The run stays fully accessible and can be unarchived at any time."*
  - **Unarchive:** one **optional** `reason` textarea. Consequence: *"This returns the run to the default review queue."*
- On confirm: call the matching mutation with `{ reason }`; `isConfirming` = mutation pending; close the dialog on success.
- Presentational/container split mirrors `ApprovalDecisionBarContainer` (container owns query+mutation; dialog is the shared presentational primitive). Any local helper functions live in a sibling `.ts`, not the `.tsx` (react-refresh-no-fn-exports).

### 3. Wiring
Render `<RunArchiveControl workflowRunId={workflowRunId} />` in `routes/workflows/$workflowRunId/index.tsx`, in the run-actions area below the run id/state header.

## Data flow

Click → dialog → confirm with reason → `useArchiveRun.mutate({ reason })` → POST with `Idempotency-Key` → on success the factory invalidates `detail` + `lists` → `useAllowedActions` refetches → the control automatically flips to the opposite action. The queue's already-built "Hidden" chip and `includeArchived` filter reflect the new state on next view.

## Error handling

Surface typed `ProblemDetailsError` via the existing problem-details mapping:
- `ARCHIVE_NOT_APPLICABLE` (409) — raced to already-archived / not-archived; inline "This run's hidden state changed — refresh and retry."
- `IDEMPOTENCY_KEY_CONFLICT` (409), `RUN_NOT_FOUND` (404).
- `INVALID_COMMAND_PAYLOAD` (400) — a blank archive reason; the dialog's required-field guard prevents this pre-flight, so this is a defensive fallback.

Errors render inline in/near the dialog; they do not crash the page.

## Testing

- **Hook tests** mirroring `useTakeoverWorkflow.test` / `useRejectImplementation.test`: posts the reason + `Idempotency-Key`; omits `reason` from the unarchive body when blank; invalidates `detail` + `lists` on success; surfaces `ProblemDetailsError` on failure.
- **Container/component tests** mirroring `ApprovalDecisionBar.test`:
  - renders nothing when neither `archive_run` nor `unarchive_run` is advertised;
  - shows **Archive run** when `archive_run` advertised, **Unarchive run** when `unarchive_run` advertised;
  - archive dialog blocks confirm until a non-blank reason (≤512) is entered; unarchive confirm is enabled with no reason;
  - confirm calls the matching mutation with the entered reason;
  - a typed 409 surfaces an inline message and keeps the dialog open.
- Gates: `npm run build`, lint, and the prettier `format:check` (the maven reactor enforces `format:check`; the dev FE gate list omits it — run it explicitly).

## Files

New:
- `deliveryline-frontend/src/features/workflows/hooks/useArchiveRun.ts` (+ `.test.tsx`)
- `deliveryline-frontend/src/features/workflows/hooks/useUnarchiveRun.ts` (+ `.test.tsx`)
- `deliveryline-frontend/src/features/workflows/components/RunArchiveControl.tsx` (+ `.test.tsx`)
- (if helpers needed) `deliveryline-frontend/src/features/workflows/runArchiveView.ts`

Modified:
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` — render the control.

## Non-goals

- No backend, DB, or contract change; no OpenAPI/FE-client regen.
- No queue-row control, no bulk actions, no hard delete.
