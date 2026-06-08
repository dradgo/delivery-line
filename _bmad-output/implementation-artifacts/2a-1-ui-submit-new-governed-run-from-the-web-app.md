# Story 2a.1: UI Submit — New Governed Run from the Web App

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **Product Manager / Workflow Owner**,
I want **a "Submit a Run" form in the web app that creates a governed run from a Linear ticket reference by calling the existing `POST /api/v1/workflows/submit-workflow`**,
so that **I can initiate work without the CLI — realizing PRD FR1's PM-initiation, which was CLI-only until now**.

**Backend status: DONE — no backend change.** `WorkflowController.submit()` + `SubmitWorkflowRequest` already exist and are in the committed OpenAPI snapshot and the generated client (`src/lib/api/schema.d.ts`, operation `submitWorkflow`). This is a **frontend-only** story. Do NOT touch the backend, the OpenAPI snapshot, or `schema.d.ts` (it is generated).

## Acceptance Criteria

1. **Typed route + queue entry point.** A new file-based route (`/submit`) is reachable from the queue shell. Add a persistent, always-visible "Submit a Run" affordance (e.g. a button/link in the queue header) so the form is reachable whether or not the queue is empty — not only from the empty state. Navigating to `/submit` renders the form inside the existing app shell (`__root.tsx` → `AppShell`); the route is registered in `routeTree.gen.ts` via `npm run routes:generate`.

2. **Empty-state CTA updated (story 2.20 touch-point).** The queue empty-state copy in `QueueShell.tsx` (currently `"No specifications awaiting review. New runs from Linear appear here once submitted via the CLI."`) is updated so it no longer says "via the CLI" and instead offers a link/CTA to the new `/submit` form (e.g. `"…or submit a run from a Linear ticket."` with a `<Link to="/submit">`). The existing docs CTA (`GettingStartedAction`) may remain alongside or be replaced — at minimum the "CLI" wording is gone and a working in-app link to the form is present.

3. **Form fields + client validation mirroring backend constraints.** The form collects:
   - `linearTicketReference` — **required**, trimmed-non-blank, `≤128` chars (mirrors backend `@NotBlank @Size(max=128)`).
   - `actorIdentity` — **required**, trimmed-non-blank, `≤128` chars.
   - `actorType` — **required**, a `<Select>` constrained to the `ActorType` enum `"HUMAN" | "AGENT" | "SYSTEM" | "SERVICE_ACCOUNT"` (default `HUMAN` for a human PM).
   - `correlationId` — **optional**, free text (≤128).
   Client validation blocks submit and shows per-field messages when a required field is blank or any field exceeds 128 chars. Validation mirrors the backend; it does not replace it.

4. **Submit calls the existing endpoint with a stable idempotency key.** A `useSubmitWorkflow` mutation hook calls `apiClient.POST('/api/v1/workflows/submit-workflow', …)` with the body above. The hook mints a **UUIDv7 `Idempotency-Key`** via `newIdempotencyKey()` (`src/lib/api/idempotency.ts`), passed explicitly in `params.header['Idempotency-Key']`. The key is **regenerated for each distinct submit attempt and reused across that attempt's retries** (do not let the client middleware mint a fresh fallback key per retry — pass it from the hook). On success, the mutation invalidates `workflowKeys.lists()` so the new run appears in the queue.

5. **Button-state precedence.** The submit control's render state follows the project precedence `locked > error > stale > submitting > blocked > disabled > success > ready`, resolved in a single pure function (mirror `resolveApprovalBarState` in `approvalDecisionView.ts`). For this CREATE form the relevant levels are at minimum: `error` (last submit failed), `submitting` (mutation pending — disable + show progress, prevent double-submit), `blocked`/`disabled` (client validation failing or required fields empty), `success` (submit succeeded), `ready` (valid + idle). The function is unit-tested with one case per level asserting precedence order.

6. **Success surface (persistent, non-toast).** On success, render a **persistent** confirmation (not a `sonner` toast) showing the new `workflowRunId`, the returned `currentState` (expected `Inbox`), and a `<Link>` to the run detail route `/workflows/$workflowRunId` (params `{ workflowRunId }`). **`SubmitWorkflowResponse` fields (`workflowRunId`, `currentState`, `correlationId`) are all optional in the generated type** — guard for `undefined` and degrade gracefully (e.g. omit the link if `workflowRunId` is absent) rather than rendering "undefined". Persistent-feedback infra (story 2.21) is not yet built (Epic 2b backlog) — build the confirmation inline using the existing `Alert`/`Card` primitives.

7. **Failure surface via ProblemDetails.** On failure, surface the typed `ProblemDetailsError.code` (e.g. `LINEAR_TICKET_NOT_FOUND`, `IDEMPOTENCY_KEY_CONFLICT`, `MISSING_IDEMPOTENCY_KEY`, `VALIDATION_ERROR`, `INTERNAL_ERROR`) through a shared error surface (`ErrorState` from `src/components/feedback/states/`). Branch only on `error.code` / `error.status` / `error.retryable` — **never** on `error.message` or HTTP status text. A retry from the error state **reuses the same idempotency key** for the in-flight attempt's payload (a brand-new submit click mints a new key). Transport (non-problem+json) failures render a generic message.

8. **Tests (vitest + MSW).** Cover: (a) happy submit → request body assertion + persistent success with `runId`/`Inbox`/detail link; (b) client validation gating (blank/over-128 blocks submit, no network call); (c) error-code rendering for at least `LINEAR_TICKET_NOT_FOUND` and `IDEMPOTENCY_KEY_CONFLICT` (problem+json via MSW); (d) idempotency-key reuse on retry vs. new key on a fresh submit (assert the `Idempotency-Key` header across calls); (e) empty-state CTA navigates to `/submit`; (f) button-precedence unit tests.

9. **Logging (field-only, no PII).** Emit field-only structured `console.info`/`console.warn` logs `{ event: 'run.submitAttempt' | 'run.submitSuccess' | 'run.submitError', … }`. Success may carry `currentState`; error carries `{ code, transport }`. **Never log `linearTicketReference`, `actorIdentity`, `correlationId`, the idempotency key, or `error.message`** (all user-entered or sensitive). Add an exact-key negative test (mirror the 2.19 `ApprovalDecisionBarContainer.test.tsx` convention) asserting the success log's key set is exactly the allowed fields.

## Tasks / Subtasks

- [x] **Task 1 — `useSubmitWorkflow` mutation hook** (AC: #4, #7)
  - [x] Create `src/features/workflows/hooks/useSubmitWorkflow.ts`. This is a **CREATE** mutation — there is **no pre-existing `workflowRunId`**, so do **not** use the `useWorkflowMutation` factory verbatim (it requires a run id to key `mutationKey`/`detail(id)` invalidation). Instead write a thin `useMutation` that: mints `newIdempotencyKey()` once per attempt (mirror the `mutate`/`mutateAsync` wrapper in `useWorkflowMutation.ts` so retries reuse the attempt's key), calls `apiClient.POST('/api/v1/workflows/submit-workflow', { params: { header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } }, body })`, wraps the result with `unwrap()` (from `src/lib/api/client.ts`) so failures throw a typed `ProblemDetailsError`, and on success calls `queryClient.invalidateQueries({ queryKey: workflowKeys.lists() })`.
  - [x] Build the body as `SubmitWorkflowRequest`: `{ linearTicketReference, actorIdentity, actorType, ...(correlationId ? { correlationId } : {}) }` (omit `correlationId` when empty — match the optional-field spread idiom in `useApproveSpec.ts`).
- [x] **Task 2 — Button-state resolver + view types** (AC: #5)
  - [x] Create a pure resolver (e.g. `src/features/workflows/submitRunView.ts`) computing the button/render state from `{ mutationStatus, errorCode, validationValid }` with precedence `locked > error > stale > submitting > blocked > disabled > success > ready`. Keep it a `.ts` (no JSX) so pure helpers don't trip the `react-refresh` eslint rule — see Dev Notes.
- [x] **Task 3 — Submit form component** (AC: #3, #6, #7)
  - [x] Create `src/features/workflows/components/SubmitRunForm.tsx`: manual `useState` form (no react-hook-form/zod — not in deps), shadcn `Label`/`Input`/`Select`/`Button` primitives, per-field validation messages, submit disabled per the Task 2 resolver. Render the persistent success `Alert`/`Card` (AC6) and the `ErrorState` failure surface (AC7).
- [x] **Task 4 — Route + queue entry point** (AC: #1)
  - [x] Create `src/routes/submit/index.tsx` with `createFileRoute('/submit/')({ component: … })` rendering `SubmitRunForm` (mirror the structure of `src/routes/workflows/index.tsx`). Run `npm run routes:generate` to update `routeTree.gen.ts` (custom generator: `node tools/routing/generate-route-tree.js` — do not hand-edit `routeTree.gen.ts`).
  - [x] Add a persistent "Submit a Run" `<Link to="/submit">` affordance in the queue shell header so it's reachable independent of queue state.
- [x] **Task 5 — Update queue empty-state CTA** (AC: #2)
  - [x] In `src/features/workflows/QueueShell.tsx` (line ~211) replace the `"…submitted via the CLI."` copy and wire a `<Link to="/submit">` CTA into the empty state.
- [x] **Task 6 — Tests** (AC: #8, #9)
  - [x] `useSubmitWorkflow.test.tsx` (MSW): happy body assertion, problem+json error → typed code, idempotency-key reuse-on-retry vs new-key-on-fresh-submit.
  - [x] `SubmitRunForm.test.tsx`: validation gating, success surface (runId/Inbox/detail link), error-code rendering, exact-key logging negative test.
  - [x] `submitRunView.test.ts`: one case per precedence level.
  - [x] A test asserting the empty-state CTA links to `/submit`.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Frontend equivalent: field-only `console` structured logs at submit attempt/success/error (see AC9). There is no SLF4J in the frontend — this story is UI-only; the backend submit path is already instrumented.
  - [x] Use object-form structured logs (`console.info({ event, currentState })`), never string concatenation of user input.
  - [x] Levels: `info` for attempt/success lifecycle, `warn` for submit errors (problem+json or transport).
  - [x] Every log carries only safe fields: `event`, and `currentState` (success) or `code`/`transport` (error). The correlation id rides the request header automatically (client middleware) — do not log it from the form.
  - [x] Never log `linearTicketReference`, `actorIdentity`, `correlationId`, the idempotency key, or `error.message`.
  - [x] Add an exact-key negative test that the success log's key set is exactly `['currentState','event']` (mirror `ApprovalDecisionBarContainer.test.tsx`).

## Dev Notes

### What already exists (DO NOT rebuild)

- **Endpoint + client + types** — backend `WorkflowController.submit()` and the generated `submitWorkflow` operation are done. Body `SubmitWorkflowRequest`, response `SubmitWorkflowResponse`. [Source: deliveryline-frontend/src/lib/api/schema.d.ts:319-330, :528-553]
- **Typed client + `unwrap()`** — `apiClient.POST(path, { params:{ header }, body })`; `unwrap()` returns typed data or throws `ProblemDetailsError`. The header middleware auto-attaches `X-Correlation-Id` on every request and a *fallback* `Idempotency-Key` only if one is absent — the hook MUST pass its own key so retries reuse it. [Source: deliveryline-frontend/src/lib/api/client.ts:36-68, :115-132]
- **Idempotency key minter** — `newIdempotencyKey()` (UUIDv7), `IDEMPOTENCY_KEY_HEADER`. [Source: deliveryline-frontend/src/lib/api/idempotency.ts:19-49]
- **Mutation-attempt key-reuse pattern** — the factory mints the key once per `mutate()`/`mutateAsync()` call and threads it through the attempt so internal retries reuse it; copy this wrapper shape into the CREATE hook. [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts:99-128]
- **Concrete mutation hook to mirror** — `useApproveSpec.ts` (header passing, `unwrap`, optional-field spread). [Source: deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.ts]
- **Query-key factory** — invalidate `workflowKeys.lists()` on success. Inline query-key arrays are a build-failing anti-pattern (`no-inline-query-keys` eslint rule) — always use the factory. [Source: deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts:46-49]
- **ProblemDetails contract** — branch on `error.code` (`DomainErrorCode`, open union), `error.status`, `error.retryable`; never `.message`. `isProblemDetailsError(e)` guard available. [Source: deliveryline-frontend/src/lib/api/problemDetails.ts:33-71]
- **Button-precedence reference** — `resolveApprovalBarState` + its tests. [Source: deliveryline-frontend/src/features/workflows/approvalDecisionView.ts:325-358]
- **Feedback states** — `ErrorState`, `EmptyState`, `LoadingState`. [Source: deliveryline-frontend/src/components/feedback/states/]
- **Form primitives** — `Input`, `Label`, `Select` (Radix), `Textarea`, `Button`, `Alert`, `Card`. No `react-hook-form`/`zod` in deps — use manual `useState` + validation. [Source: deliveryline-frontend/src/components/ui/, package.json:24-53]
- **Routing** — file-based; custom generator `npm run routes:generate`. Detail route `/workflows/$workflowRunId`; link via `<Link to="/workflows/$workflowRunId" params={{ workflowRunId }}>`. [Source: deliveryline-frontend/src/routes/workflows/index.tsx, src/routes/workflows/$workflowRunId/index.tsx:1]
- **Logging negative-test convention** — exact-key assertion on the success log. [Source: deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBarContainer.test.tsx]
- **Test infra** — MSW (`src/test/server.ts`), setup (`src/test/setup.ts`, `onUnhandledRequest:'error'`); handler pattern `server.use(http.post(URL, async ({request}) => …))`. [Source: deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.test.tsx]

### Critical gotchas (prevent rework)

1. **CREATE mutation ≠ the `useWorkflowMutation` factory.** Submit creates a run; there is no `workflowRunId` yet, so the factory's `detail(id)` mutation-key/invalidation does not apply. Write a dedicated `useMutation`, reuse only the *key-reuse wrapper idiom* and `newIdempotencyKey()`, and invalidate only `workflowKeys.lists()`.
2. **`SubmitWorkflowResponse` fields are all optional** in the generated type (`workflowRunId?`, `currentState?`, `correlationId?`). Guard for `undefined` in the success surface; never render the literal "undefined" or build a detail link with a missing id.
3. **No `schema.d.ts` / OpenAPI / backend edits.** Story is frontend-only. The submit operation in the snapshot documents only the 200 response — error bodies are still typed `problem+json` at runtime and handled by `unwrap()`; you do not need to add error codes to the schema. The three referenced codes are open-union `DomainErrorCode` strings — render them generically.
4. **`react-refresh` eslint rule** forbids a feature `.tsx` from exporting non-component helpers (only literal constants). Keep the button-state resolver and any pure helpers/maps in a sibling `.ts` (`submitRunView.ts`), not in the `.tsx`. [[frontend-react-refresh-no-fn-exports]]
5. **Non-toast success.** `sonner` is installed but the spec wants a *persistent* confirmation — render inline (`Alert`/`Card`), not a toast.
6. **Idempotency key lifecycle.** New key per distinct submit click; same key reused for retrying the same failed attempt. Tests must assert both behaviors via the `Idempotency-Key` header across MSW calls.
7. **PII in logs.** `linearTicketReference` and `actorIdentity` are user-entered — never log them, never put them in error strings. Only `event` + `currentState`/`code`/`transport`.

### Project Structure Notes

- New files: `src/features/workflows/hooks/useSubmitWorkflow.ts` (+ test), `src/features/workflows/submitRunView.ts` (+ test), `src/features/workflows/components/SubmitRunForm.tsx` (+ test), `src/routes/submit/index.tsx`.
- Modified files: `src/features/workflows/QueueShell.tsx` (empty-state CTA + header entry point), `src/routeTree.gen.ts` (generated — via `npm run routes:generate`, do not hand-edit).
- Gates to run (PowerShell — see memory [[rtk-hook-only-matches-bash]]; rtk corrupts only the Bash tool): `npm run test`, `npm run lint` (`--max-warnings=0`), `npm run lint:rules-test`, `tsc -b` (via `npm run build`), `npm run format:check`. One unformatted file cascades CI failures [[prettier-gate-cascades-ci]] — run `prettier --write` before pushing.
- Cross-platform: regenerate the lockfile with a full `npm install` and verify on Linux before pushing if any dep changes (none expected here) [[frontend-lockfile-cross-platform]]; `.npmrc` `legacy-peer-deps=true` is committed [[frontend-ts6-legacy-peer-deps]].

### References

- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-07.md#New Story 2a-1] — full story rationale, AC-shape, deps, FR1-gap closure.
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:319-330] — `SubmitWorkflowRequest` / `SubmitWorkflowResponse` shapes + `ActorType` enum.
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:528-553] — `submitWorkflow` operation (path, header, 200 only).
- [Source: deliveryline-frontend/src/lib/api/client.ts:36-68] — header middleware (correlation + idempotency fallback) and `unwrap()`.
- [Source: deliveryline-frontend/src/lib/api/idempotency.ts:19-49] — UUIDv7 key minter + reuse rule.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts:99-128] — per-attempt key-reuse wrapper to copy.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.ts] — concrete mutation-hook mirror.
- [Source: deliveryline-frontend/src/features/workflows/approvalDecisionView.ts:325-358] — button-precedence resolver pattern.
- [Source: deliveryline-frontend/src/features/workflows/QueueShell.tsx:121-214] — empty-state CTA to update + `GettingStartedAction`.
- [Source: deliveryline-frontend/src/routes/workflows/index.tsx] — file-route registration pattern; [Source: src/routes/workflows/$workflowRunId/index.tsx:1] — detail-route link target.
- [Source: deliveryline-frontend/src/lib/api/problemDetails.ts:33-71] — `DomainErrorCode` union + `ProblemDetailsError`.
- Architecture: AR8 (CLI/REST translate to the same `submit` command — UI submit has audit/idempotency parity with the CLI). [Source: _bmad-output/planning-artifacts/architecture.md]
- PRD FR1 — PM-initiation of a governed workflow; this story closes the UI-form gap (FR-coverage table scoped FR1 to Epic 1 CLI). [Source: _bmad-output/planning-artifacts/epics.md:293]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- ESLint clean (`--max-warnings=0`) after fixing: unused `ReactNode` import + `as any` router cast in `SubmitRunForm.test.tsx`; `strict-boolean-expressions` on the optional-`correlationId` spread in `useSubmitWorkflow.ts`; and an `no-unnecessary-condition` on `data?.currentState` (TS aliased-condition narrowing made `data` non-nullish inside the `showSuccess` branch — changed to `data.currentState`).
- `npm run routes:generate` registered `/submit` (`SubmitIndexRoute`, `to: '/submit'`) into `routeTree.gen.ts` (generated — not hand-edited).

### Completion Notes List

- **Frontend-only**, as scoped: no backend, OpenAPI snapshot, or `schema.d.ts` edits. Built over the existing `submitWorkflow` operation already in the generated client.
- **AC4/AC7 — `useSubmitWorkflow`**: dedicated `useMutation` (NOT the `useWorkflowMutation` factory — submit has no run id). `submit()` mints a fresh UUIDv7 `Idempotency-Key` per attempt; `retry()` re-runs the LAST attempt verbatim (same key); internal transient retries reuse the attempt key. On success invalidates `workflowKeys.lists()`. Optional `correlationId` omitted from the body when blank.
- **AC5 — `submitRunView.ts`** (a `.ts`, so the `.tsx` exports only its component → no `react-refresh` violation): single pure `resolveSubmitButtonState` with precedence `locked > error > stale > submitting > blocked > disabled > success > ready`; plus pure field validation (`validateSubmitFields`) and code-only error mapping (`submitErrorMessage`/`submitErrorCode`, branch on `code`, never `message`). The submit control stays clickable while `blocked` so the click reveals per-field messages (AC3); it is `disabled` only while in flight (no double-submit).
- **AC3/AC6/AC7 — `SubmitRunForm.tsx`**: manual `useState` form, shadcn `Label`/`Input`/`Select`/`Button` primitives. Persistent (non-toast) success `Alert` showing `workflowRunId` + `currentState` + a `<Link to="/workflows/$workflowRunId">`, each guarded for the all-optional `SubmitWorkflowResponse` (degrades, never renders "undefined"). Failures render through the shared `<ErrorState>` (Retry → `retry()`, reusing the failed key).
- **AC1/AC2 — route + entry points**: `/submit` file-route renders inside the existing `AppShell`. A persistent "Submit a run" header link in `QueueShell` (reachable in any queue state) + an empty-state CTA that drops the "via the CLI" copy and links to `/submit`.
- **AC9 — logging**: field-only `console` logs `run.submitAttempt` / `run.submitSuccess` (`{currentState}`) / `run.submitError` (`{code, transport}`); never logs `linearTicketReference`/`actorIdentity`/`correlationId`/idempotency key/`error.message`. Exact-key negative test asserts the success log is exactly `{currentState, event}`.
- **Gates (PowerShell)**: `npm run test` 486/486 (54 files, incl. 32 new + 2 new queue-route assertions); `npm run lint` clean (`--max-warnings=0`); `npm run lint:rules-test` 4/4; `npm run build` (routes:generate + `tsc -b` + vite) exit 0; `npm run format:check` clean.
- Test isolation: `SubmitRunForm.test.tsx` uses a REAL in-memory router (no `@tanstack/react-router` `vi.mock`) to avoid the cross-file router-mock flake ([[vitest-cross-file-router-mock]]); the empty-state/`/submit` href assertions live in the existing `queueRoute.integration.test.tsx` (real router).

### File List

**New:**
- `deliveryline-frontend/src/features/workflows/hooks/useSubmitWorkflow.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useSubmitWorkflow.test.tsx`
- `deliveryline-frontend/src/features/workflows/submitRunView.ts`
- `deliveryline-frontend/src/features/workflows/submitRunView.test.ts`
- `deliveryline-frontend/src/features/workflows/components/SubmitRunForm.tsx`
- `deliveryline-frontend/src/features/workflows/components/SubmitRunForm.test.tsx`
- `deliveryline-frontend/src/routes/submit/index.tsx`

**Modified:**
- `deliveryline-frontend/src/features/workflows/QueueShell.tsx` (persistent header "Submit a run" link + empty-state CTA copy/link)
- `deliveryline-frontend/src/features/workflows/__tests__/queueRoute.integration.test.tsx` (header + empty-state `/submit` href assertions)
- `deliveryline-frontend/src/routeTree.gen.ts` (generated — `/submit` route registered via `npm run routes:generate`)

## Change Log

| Date       | Version | Description                                                                 | Author |
| ---------- | ------- | --------------------------------------------------------------------------- | ------ |
| 2026-06-08 | 0.1     | Implemented story 2a.1 — in-app Submit-a-Run form over the existing submit endpoint (hook, button-state/validation helpers, form, `/submit` route, queue entry points, tests). Status ready-for-dev → review. | Amelia (dev-story) |
| 2026-06-08 | 0.2     | Adversarial code review (0 AC violations). Fixed 2 decision + 3 patch findings in `SubmitRunForm.tsx`: reset feedback on field edit, disable submit after success (duplicate-run guard), conditional `aria-describedby`, Select ARIA wiring, success-message double-period. +3 tests (489 pass). Status review → done. | Code Review |

## Review Findings

_Code review 2026-06-08 (adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor). Acceptance Auditor found **zero AC violations** — all 9 ACs and 7 gotchas satisfied. The items below are robustness/UX hardening beyond the ACs._

### Decision needed (resolved → fixed)

- [x] [Review][Decision→Fixed] Form feedback never resets on field edit — `reset()` is exported but never called — **Fixed:** added a `resetIfSettled()` helper called from every field `onChange`/Select `onValueChange`; when `status` is `success`/`error` it calls `submitWorkflow.reset()`, so the prior success/error surface clears on the first keystroke and never describes a now-edited attempt. Covered by two new tests (success-edit clears the card; error-edit clears `ErrorState`). [`SubmitRunForm.tsx`] (source: blind+edge)
- [x] [Review][Decision→Fixed] Submit button stays enabled after success → a second click mints a NEW idempotency key and creates a duplicate run — **Fixed:** `submitDisabled` now also disables the control while `status === 'success'`; editing a field (via the D1 reset above) re-enables it for the next distinct submission. New test asserts the button is disabled after success. [`SubmitRunForm.tsx`] (source: blind+edge)

### Patch (fixed)

- [x] [Review][Patch] Dangling `aria-describedby` on the three text inputs [`SubmitRunForm.tsx`] — **Fixed:** `aria-describedby` is now set only when the field's error node is rendered (`!== undefined ? '<field>-error' : undefined`).
- [x] [Review][Patch] `actorType` Select error unassociated [`SubmitRunForm.tsx`] — **Fixed:** the `<SelectTrigger>` now carries `aria-invalid` and a conditional `aria-describedby="actorType-error"`, consistent with the text inputs.
- [x] [Review][Patch] Double period in the success message fallback [`SubmitRunForm.tsx`] — **Fixed:** the fallback string is now `'Your run was created'` (no trailing period); the single conditional `.` after the `currentState` block renders exactly one period in every branch.

### Dismissed (noise / false positives / intentional)

- `maxLength={512}` vs the 128 validation ceiling — intentional: a `maxLength={128}` would make the over-128 validation path (and its 129-char test) unreachable.
- `handleSubmit` re-validates against the render-time `fields` snapshot — correct fire-time re-validation; `handleSubmit` is rebuilt each render with fresh `fields`.
- `loggedStatusRef` could miss an error→error re-log — react-query always ticks through `pending` between terminal states, so the transition is observed.
- `isPending` exported but unused — deliberate API completeness; the form derives flight state from `status`.
- `onValueChange` casts `string` to `ActorType` without a guard — Radix only emits values from the rendered `ACTOR_TYPES` items; safe in practice.
- `mutationKey: workflowKeys.lists()` shared across hook instances — benign; `retry()` reads instance-scoped `mutation.variables`. Single-instance form today.
- `handleRetry` logs `run.submitAttempt` before confirming dispatch — only reachable from the error surface where `mutation.variables` is defined.
