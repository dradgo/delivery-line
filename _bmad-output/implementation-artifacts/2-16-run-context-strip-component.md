# Story 2.16: Run Context Strip Component

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager opening a governed run,
I want a `RunContextStrip` persistent lightweight component above the primary review surface,
so that I have just enough orientation (run identity, current state, current actor, latest revision pointer, last meaningful transition) to confirm I'm looking at the right artifact at the right workflow point — without the strip competing with the artifact body for attention (UX-DR9).

## Acceptance Criteria

> The ACs below are the epics.md Story 2.16 criteria, **reconciled against the live backend contract** (`WorkflowDetail` in `src/lib/api/schema.d.ts`). Where the epic prose named a field the backend does not expose, the reconciliation is called out inline and detailed in Dev Notes → *Data reconciliation*. The live contract is authoritative.

1. **`RunContextStrip` + view-model seam.** `src/features/workflows/components/RunContextStrip.tsx` accepts a typed `RunContextView` prop produced by a pure mapper `toRunContextView(detail: WorkflowDetail)` (new `src/features/workflows/runContextView.ts`). `RunContextView` carries: `runId`, `currentState`, `currentActorIdentity`, `currentActorType`, `latestArtifactType?`, `latestArtifactVersion?`, `lastTransitionAt?`, `lastTransitionEventType?`, `triggerReference?` (Linear ticket ref), `branchOrCommitReference?` (always `undefined` in E2 — no backend field yet), `escalationMarker`, `staleIndicator` + `lastActivityAt?` (drives the stale tooltip). **Reconciliation:** `latestArtifactId` is NOT carried — `WorkflowDetail.latestArtifacts[]` exposes only `{ artifactType, status, version }` (no id); the strip's revision pointer renders from `latestArtifactType` + `latestArtifactVersion`. The route already supplies the data via `useWorkflowDetail(workflowRunId)` (story 2.6) — the strip reads the **same warmed cache entry**, it does NOT add a new fetch.
2. **Anatomy (UX-DR9).** The strip displays, in a single horizontal `Inline` row (story 2.4 primitive): run identifier (`run_abc…` as `<code>`), a current-state badge via the **new shared `WorkflowStateBadge`** (see AC2.b), current actor + actor-type (`Alex (human)`, `Codex Runner (agent)` — coalesced exactly like `RunIdentityRegion`), latest revision pointer (`spec v3` / `implementation-plan v1` / `pr-output v2`, derived from `latestArtifactType`+`latestArtifactVersion`), last meaningful transition timestamp as **relative time with a precise UTC tooltip** (`title`/`<time dateTime>`), and the optional trigger reference (Linear ticket) when present.
   - **2.b — shared state badge.** Create `WorkflowStateBadge` (`src/features/workflows/components/WorkflowStateBadge.tsx`) mapping the backend `currentState` enum → `StateName` (`src/lib/state-signifiers.ts`) → the `--state-*` token classes + the `STATE_SIGNIFIERS` icon (lucide) + label. This badge is the **shared seam story 2.15 (`RunReviewQueueItem`) consumes for "consistent badge styling"** — 2.15 ships *after* this story, so the badge is born here. It MUST live under `features/workflows/` (NOT `components/ui/` — the `no-workflow-domain-in-ui-primitives` ESLint rule forbids workflow vocabulary there). Mapping table in Dev Notes; unrecognized state → neutral `informational` fallback (mirrors the route's AC8a defensiveness).
3. **States (UX-DR9).** The component renders exactly one of: `default`; `stale` (state-stale token treatment + a "Stale" badge + tooltip per AC9); `partial-context` (field-level — when nullable fields like `branchOrCommitReference`, `triggerReference`, or `latestArtifact*` are absent, those slots render an inline "Not reported" placeholder rather than collapsing — reuse the `NotReported` placeholder idiom from `RunIdentityRegion`); `loading` (skeleton placeholders matching the strip's inline layout — `Skeleton` from `src/components/ui/skeleton.tsx`, **never a spinner**); `error` (load failed — composes `<ErrorState variant="failedRetrieval" nextAction={{kind:'Retry', onRetry: refetch}}>` from story 2.22). `partial-context` is NOT the queue's `<EmptyState>` — it is per-field placeholders within an otherwise-populated strip.
4. **Lightweight constraint (UX-DR9).** Vertical real estate is constrained by a documented exported constant `RUN_CONTEXT_STRIP_MAX_HEIGHT` applied as a `max-h-*` class/style; horizontal layout uses the `Inline` primitive; the strip does NOT expand into a multi-row metadata panel (single inline row, wrap allowed but height-capped). A layout test asserts the max-height constraint is applied across all states. **jsdom caveat:** jsdom does not compute real layout, so the test asserts the constraining class/style is present (and that no state renders the multi-row/`<dl>` expansion shape), not a measured pixel height — documented in Dev Notes.
5. **No lineage/provenance drilldown (UX-DR9 boundary).** The component exposes NO UI control into full lineage or provenance (deferred Phase 3 — UX spec "Full Run State Header / Lineage Summary"). It MAY accept an optional `onNavigateToFullLineage?: () => void` prop reserved for future use, but **no UI control wires it in E2** (a test asserts no such control renders).
6. **Accessibility (UX-DR9).** The strip is a single labeled region: `<section role="region" aria-label="Run context">` (or `<div role="region">`), keyboard-readable in a sensible metadata order, and **status is never color-alone** — every state/badge pairs the color token with the `STATE_SIGNIFIERS` icon + text label (story 2.3 AC5).
7. **CLI / nav-rail parity.** The strip is the in-shell render of the same backend truth the CLI `deliveryline status --format=json` (story 1.15) and the AppShell "current run identity" region (`RunIdentityRegion`, story 2.7 AC11) show. **Field mapping MUST match `RunIdentityRegion`'s documented mapping** (`runId→workflowRunId`, `currentState`, actor `currentActorIdentity(+currentActorType)`, **last transition = `lastEventAt` NOT `lastActivityTimestamp`**). The actor-coalescing and timestamp source are factored into the shared mapper so the two surfaces cannot drift; a test asserts the strip and the mapper agree on these fields for a shared fixture.
8. **Placement.** `RunContextStrip` is rendered by `WorkflowDetailRoute` (`src/routes/workflows/$workflowRunId/index.tsx`) in a slot **above** the main review pane, replacing the "(2.16) … render here once they land" placeholder paragraph. It sits inside the route's existing `<Stack>` in the AppShell `<main>` landmark — between the left nav rail and the (future 2.17) artifact body. It is NOT mounted into the right `ContextPanelSlot` (that slot is for 2.17/2.18 composites).
9. **Stale detection.** When the view's `staleIndicator` is true, the strip renders the stale state with a documented stale-reason tooltip (e.g., "Last activity 12 minutes ago — runner may be unresponsive"), where the relative duration is derived from `lastActivityAt`. **Reconciliation:** `WorkflowDetail` exposes NO dedicated `staleIndicator` flag today, but it DOES expose `lastActivityTimestamp`. Per UX-DR9 the *detection logic* belongs to the backend (story 1.13 AC7 heartbeat tracking), which has not yet surfaced a flag on the read model. E2 MVP: `toRunContextView` computes `staleIndicator` client-side from `lastActivityTimestamp` age against a documented threshold constant `RUN_STALE_THRESHOLD_MS`, **isolated in the mapper as the single seam** so a future backend-provided flag swaps in without touching the component. Decision recorded in Dev Notes → Open Questions OQ-3.
10. **Component test coverage.** Tests cover: each state renders correctly (default/stale/partial-context/loading/error); the max-height constraint is applied across states (per AC4 caveat); escalation-marker rendering when `escalationMarker` is true; partial-context placeholders for null fields; the `role="region"` labeling + non-color signifier; no lineage/provenance control renders; field mapping parity with the `RunIdentityRegion` source fields (AC7); Retry invokes `refetch`. **Reconciliation of "snapshot test":** the frontend has no snapshot harness yet (story 2.27) and no events endpoint (story 6.9), so AC10's "content matches `status --format=json`" is satisfied by **fixture-driven render assertions** against a frontend fixture mirroring backend `WorkflowDetail`, plus the documented field-mapping table — NOT vitest `toMatchSnapshot`. Full cross-surface CLI-parity harness is deferred (Dev Notes → OQ-5).
11. **Fixture-stream rendering.** Against a frontend fixture mirroring the terminal state of backend `spec-rejection-and-resubmit.json` (story 1.23; `run_fix_rej_001`, terminal `Completed`, latest spec advanced to **v2** after the v1 rejection-and-resubmit), the strip renders correctly: run id, `Completed` badge, actor, latest revision `spec v2` (proving the version advanced past the prior), and trigger `DEL-9002`. Fixture lives under `src/test/fixtures/` (frontend-owned copy — the backend fixtures are not yet served to the SPA; see OQ-5).

## Tasks / Subtasks

- [x] **Task 1 — View-model mapper + shared formatters** (AC: 1, 7, 9)
  - [x] Add `src/features/workflows/runContextView.ts` exporting the `RunContextView` interface and pure `toRunContextView(detail: WorkflowDetail): RunContextView`.
  - [x] Map fields per AC1 (use `lastEventAt` for `lastTransitionAt`, NOT `lastActivityTimestamp`; coalesce actor like `RunIdentityRegion`; derive `latestArtifactType`/`latestArtifactVersion` by selecting the highest-`version` entry of `latestArtifacts` — document the selection rule).
  - [x] Compute `staleIndicator` from `lastActivityTimestamp` age vs exported `RUN_STALE_THRESHOLD_MS`; expose `lastActivityAt` for the tooltip. Keep this the ONLY stale-derivation site (AC9 seam).
  - [x] Add a relative-time formatter `formatRelativeTime(iso)` + absolute-UTC tooltip helper (reuse `RunIdentityRegion`'s UTC formatting idiom for the tooltip) in a shared module (e.g. `src/features/workflows/runContextFormat.ts`). No new date library — use `Intl.RelativeTimeFormat` (built-in).
  - [x] Unit-test the mapper: each field, the artifact-selection rule, stale on/off across the threshold boundary, actor coalescing, partial inputs (all-undefined `WorkflowDetail`).
- [x] **Task 2 — Shared `WorkflowStateBadge`** (AC: 2, 2.b, 6)
  - [x] Add `src/features/workflows/components/WorkflowStateBadge.tsx` mapping `currentState` → `StateName` (table in Dev Notes) → `bg-state-*`/`text-state-*`/`border-state-*` classes + lucide icon (from `STATE_SIGNIFIERS[name].icon`) + label. (Pure mapping factored into sibling `workflowStateMapping.ts` so the badge file exports only components — react-refresh hygiene; 2.15 imports the sibling.)
  - [x] Unrecognized/`undefined` state → neutral `informational` fallback (no crash).
  - [x] Never color-alone: render icon + text label alongside the color (AC6 / story 2.3 AC5). Confirm placement under `features/workflows/` keeps the `no-workflow-domain-in-ui-primitives` rule satisfied.
  - [x] Test: representative states map to expected `StateName`/label; unknown state falls back; icon + label both present.
- [x] **Task 3 — `RunContextStrip` component + states** (AC: 1, 2, 3, 4, 5, 6, 8, 9)
  - [x] Build the strip using `Inline` (horizontal anatomy) inside a labeled `<section aria-label="Run context">` (implicit `region` role — explicit `role` is redundant under `jsx-a11y/no-redundant-roles`), capped by `RUN_CONTEXT_STRIP_MAX_HEIGHT`.
  - [x] Resolve state with a pure helper (mirror `resolveQueueState` style): `error` (query error) → `loading` (query pending) → `stale` (view.staleIndicator) → `partial-context`/`default`. Expose a `data-run-context-state` attribute for tests.
  - [x] `loading` → `Skeleton` rows matching the inline layout (never a spinner). `error` → `<ErrorState variant="failedRetrieval" nextAction={{kind:'Retry', onRetry: query.refetch}}>`. `stale` → stale token + "Stale" badge + tooltip. `partial-context` → `NotReported` placeholders for absent slots.
  - [x] Render escalation marker when `escalationMarker` is true (icon + label, non-color).
  - [x] Accept optional `onNavigateToFullLineage?` but wire NO UI control to it (AC5).
  - [x] Decide the prop contract: component takes `workflowRunId: string`, calls `useWorkflowDetail` itself (reads the warmed cache), and maps via `toRunContextView`. (Props-injectable variant optional for router-free tests — see OQ-1.)
- [x] **Task 4 — Route placement** (AC: 8)
  - [x] In `src/routes/workflows/$workflowRunId/index.tsx`, render `<RunContextStrip workflowRunId={workflowRunId} />` at the top of the route's `<Stack>` (above the heading / future artifact pane); remove the "(2.16) … render here once they land" placeholder sentence (keep the 2.17 reference accurate).
  - [x] Do not change the route's loader, search-param, or unrecognized-state guard.
- [x] **Task 5 — Tests** (AC: 10, 11, 7)
  - [x] `RunContextStrip.test.tsx` (vitest + RTL): each state; max-height class present across states; escalation marker; partial-context placeholders; `role="region"` label; non-color signifier; no lineage control; Retry → refetch.
  - [x] Mock `useReturnToRunContext` at its module (ErrorState consumes it internally) — see Dev Notes → Test conventions.
  - [x] Add frontend fixture `src/test/fixtures/runContext/specRejectAndResubmit.ts` (a `WorkflowDetail` mirroring `run_fix_rej_001` terminal state, latest `spec` `version: 2`, `linkedTicket.externalRef: 'DEL-9002'`, `currentState: 'Completed'`); assert AC11 rendering.
  - [x] Field-mapping parity test (AC7): assert `toRunContextView` derives `runId/currentState/actor/lastTransitionAt` from the same `WorkflowDetail` fields `RunIdentityRegion` reads.
- [x] **Task 6 — Logging instrumentation** (cross-cutting; frontend-adapted)
  - [x] Mirror QueueShell's structured logging seam (`console.info`/`console.warn` with field-only objects — NO payload bytes, NO `error.message`): emit `runContext.loadError` (`{ event, code, transport }` derived from ProblemDetails `code` / transport classification, never the raw message), `runContext.retry`, and `runContext.stale` (`{ event, staleForMs }`) when the stale state renders.
  - [x] Levels: `warn` for load error + stale, `info` for retry. Field-only contract identical in spirit to `queue.loadError`.
  - [x] Pin each new log line with a `console` spy assertion (mirror `QueueShell.test.tsx`) — and a negative test that the raw error message / payload is never logged.

### Review Findings

- [x] [Review][Patch] Error state removes overflow clipping while the section remains max-height capped, so the full ErrorState can overlap following route content [deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx:202] — fixed
- [x] [Review][Patch] Stale state is computed only during render, so a run crossing `RUN_STALE_THRESHOLD_MS` can remain non-stale until an unrelated render or query update [deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx:157] — fixed
- [x] [Review][Patch] Latest artifact entries with missing `version` render as a complete revision instead of a partial-context placeholder [deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx:111] — fixed
- [x] [Review][Patch] Revision labels render raw backend artifact keys like `implementationPlan`/`prOutput` instead of the AC2 display labels `implementation-plan`/`pr-output` [deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx:111] — fixed
- [x] [Review][Patch] Max-height coverage is not asserted across partial, stale, and error states as required by AC4/AC10 [deliveryline-frontend/src/features/workflows/components/RunContextStrip.test.tsx:86] — fixed
- [x] [Review][Patch] Loading state renders skeletons without an assistive busy/status signal on the labeled strip [deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx:208] — fixed
- [x] [Review][Patch] Long backend values are `whitespace-nowrap` inside an overflow-hidden capped strip and can be visually clipped without truncation/title or safe wrapping fallback [deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx:74] — fixed

## Dev Notes

### What already exists — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| `useWorkflowDetail(id)` → `WorkflowDetail` (TanStack Query, `STALE_TIME.detail=5000`, key `workflowKeys.detail(id)`) | `src/features/workflows/hooks/useWorkflowDetail.ts` + `src/lib/api/queryOptions.ts` | The strip's data source. Route loader already warms this cache (flash-free); read the same entry — **add no new fetch**. |
| `WorkflowDetail` generated type | `src/lib/api/schema.d.ts` (lines ~339–374) | Source of all fields. See *Data reconciliation*. |
| `RunIdentityRegion` (the nav-rail "current run identity", story 2.7 AC11) | `src/features/workflows/RunIdentityRegion.tsx` | **Parity reference (AC7).** Reuse its actor-coalescing + UTC-timestamp idiom + `NotReported` placeholder; documents the exact `lastEventAt` (not `lastActivityTimestamp`) decision. |
| `Inline` / `Stack` layout primitives (story 2.4) | `src/components/layout/Inline.tsx`, `Stack.tsx`, `gap.ts` | Horizontal anatomy via `Inline`; gap tokens. |
| `StateName` union + `STATE_SIGNIFIERS` (icon+label) (story 2.3) | `src/lib/state-signifiers.ts` | Non-color signifier source for `WorkflowStateBadge`. |
| `--state-*` color tokens + `bg-state-*`/`text-state-*`/`border-state-*` utilities | `src/styles/globals.css` (incl. `--state-stale`) | Badge + stale treatment colors. |
| `Skeleton` (animate-pulse, `bg-surface-elevated`) (story 2.20) | `src/components/ui/skeleton.tsx` | Loading state rows. |
| `EmptyState` / `LoadingState` / `ErrorState` (story 2.22) | `src/components/feedback/states/` | `ErrorState variant="failedRetrieval" nextAction={Retry}` for the error state. `ErrorState` calls `useReturnToRunContext` internally → mock that module in tests. |
| `useReturnToRunContext`, `NavigationBreadcrumbProvider` | `src/lib/navigation/` | Only relevant because `ErrorState` uses it; mock in unit tests. |
| Public-id validators (`isValidRunId`, patterns `run_`/`art_`/`cla_`) | `src/lib/routing/publicId.{js,d.ts}` | Defensive run-id checks if needed. |
| `ProblemDetailsError` / `isProblemDetailsError` / `DomainErrorCode` (story 1.8) | `src/lib/api/problemDetails.ts` | Map error → stable `code` for the error-state copy + log (never raw message/status). |
| Structured-log pattern (`console.info({event,…})`, field-only, console-spy tested) | `src/features/workflows/QueueShell.tsx` (`queue.loadError`/`queue.retry`) + its test | Copy this seam exactly for Task 6. There is NO logger module — it's structured `console` calls. |
| Route mount point (placeholder explicitly names "Run Context Strip (2.16)") | `src/routes/workflows/$workflowRunId/index.tsx` | Render the strip here (AC8). |

### Must CREATE in this story

- `src/features/workflows/runContextView.ts` — `RunContextView` type + `toRunContextView` mapper + `RUN_STALE_THRESHOLD_MS`. **No backend-state→StateName map and no relative-time util exist anywhere yet** (verified) — they are born here.
- `src/features/workflows/runContextFormat.ts` (or colocated) — `formatRelativeTime` (`Intl.RelativeTimeFormat`) + UTC tooltip helper.
- `src/features/workflows/components/WorkflowStateBadge.tsx` — the shared state badge (story 2.15 will import it).
- `src/features/workflows/components/RunContextStrip.tsx` — the component.
- `src/test/fixtures/runContext/specRejectAndResubmit.ts` — frontend fixture for AC11.
- Tests for each of the above.

### Data reconciliation (live `WorkflowDetail` vs epic prose) — READ THIS

The epic's `RunContextView` field list predates the real backend read model. Map as follows:

| Epic field | Backend source | Note |
|---|---|---|
| `runId` | `workflowRunId` | direct |
| `currentState` | `currentState` | string enum (see badge table) |
| `currentActor` (+type) | `currentActorIdentity` + `currentActorType` | coalesce `"Identity (type)"` like `RunIdentityRegion` |
| `latestArtifactId` | **— NONE —** | `latestArtifacts[]` has no id; **do not invent one**. Render pointer from type+version only. |
| `latestArtifactType` / `latestArtifactVersion` | `latestArtifacts[].artifactType` / `.version` | select highest `version`; absent ⇒ partial-context placeholder |
| `lastTransitionAt` | `lastEventAt` | **NOT `lastActivityTimestamp`** (parity with CLI/`RunIdentityRegion`) |
| `lastTransitionEventType` | `lastEventType` | direct |
| `triggerReference` | `linkedTicket.externalRef` | Linear ref; absent ⇒ placeholder |
| `branchOrCommitReference` | **— NONE in E2 —** | always `undefined`; renders partial-context placeholder (populated in Epic 3 when GitHub linkage exists) |
| `escalationMarker` | `escalationMarker` | boolean |
| `staleIndicator` | **derived** from `lastActivityTimestamp` | see AC9 / OQ-3; mapper is the single seam |

### Backend `currentState` → `StateName` mapping (recommended)

`currentState` enum (from the route's `RECOGNIZED_STATES`): `Inbox, Planned, Investigating, WaitingForSpecApproval, Executing, WaitingForReview, Completed, Failed, Paused, TakenOver, Reconciled`.

| currentState | StateName | rationale |
|---|---|---|
| Inbox, Planned | `informational` | neutral intake |
| Investigating, Executing | `draft` / `informational` | work in progress (pick one; `draft` reads as "being produced") |
| WaitingForSpecApproval, WaitingForReview | `warning` | needs reviewer action |
| Completed | `success` | terminal happy |
| Failed | `error` | terminal failure |
| Paused | `warning` | intentional hold |
| TakenOver, Reconciled | `recovery` | operator/recovery path |
| *(unrecognized)* | `informational` | safe fallback (route already guards truly-unknown states upstream) |

This is a defaults table — the dev may refine pairings as long as every state maps and the unknown-fallback holds. Keep it in `WorkflowStateBadge` (or a sibling map) so story 2.15 imports the same source.

### Traps (do NOT step on these)

- **T1 — `WorkflowStateBadge` location.** Must be under `features/workflows/`, NOT `components/ui/` — the `no-workflow-domain-in-ui-primitives` ESLint rule (story 2.31) errors on workflow vocabulary in `ui/**`. `eslint --max-warnings=0` will fail otherwise.
- **T2 — last transition = `lastEventAt`.** Using `lastActivityTimestamp` for the "last meaningful transition" silently disagrees with the CLI and `RunIdentityRegion` (AC7). `lastActivityTimestamp` feeds the *stale* tooltip only.
- **T3 — loading is a skeleton, never a spinner** (AC3). Use `Skeleton`; do NOT use `LoadingState` (spinner) here.
- **T4 — partial-context ≠ empty.** Per-field `NotReported` placeholders within a populated strip; do NOT render `<EmptyState>`.
- **T5 — no new fetch.** Read the route-warmed cache via `useWorkflowDetail`; do not introduce a second query key or `fetch`.
- **T6 — `ErrorState` pulls `useReturnToRunContext`.** Unit tests MUST `vi.mock` that module (hoisted spy) — otherwise the hook throws outside its provider. Do NOT wrap tests in the router; mock the hook (the established pattern).
- **T7 — never log raw error/payload.** Logs are field-only (`code`, transport, `staleForMs`) — mirror `queue.loadError`. A negative test asserts the message is absent.
- **T8 — single labeled region.** One `role="region" aria-label="Run context"`; don't nest multiple live regions or duplicate the announcer the shell already owns.
- **T9 — no lineage control.** `onNavigateToFullLineage` may exist as a prop but no button/link wires it (AC5); a test asserts absence.
- **T10 — `--max-warnings=0` + no-color-alone.** Every color carries an icon+label; run `prettier --write` before finishing (memory: `prettier-gate-cascades-ci`).

### Open questions (resolved with recommendations — proceed unless told otherwise)

- **OQ-1 — props-injectable strip for router-free tests?** *Recommendation:* component takes `workflowRunId` and calls `useWorkflowDetail` itself (matches `RunIdentityRegion`); tests drive states via MSW + `QueryClientProvider`. A thin internal presentational sub-component taking a resolved `RunContextView` is fine for pure-render tests.
- **OQ-2 — `Investigating`/`Executing` badge tone.** *Recommendation:* `draft` for Investigating, `informational` for Executing; refine freely.
- **OQ-3 — stale source.** *Recommendation:* client-derive from `lastActivityTimestamp` vs `RUN_STALE_THRESHOLD_MS` (e.g. 10 min), isolated in `toRunContextView`. When story 1.13's heartbeat read-model exposes a flag, swap the mapper line only. Recorded as a documented deviation from AC9's "reported by the backend".
- **OQ-4 — relative-time precision.** *Recommendation:* `Intl.RelativeTimeFormat` (built-in, no dep), with a precise UTC string in the `title`/`<time dateTime>` tooltip.
- **OQ-5 — CLI-parity snapshot (AC10/AC11).** Frontend has no snapshot harness (story 2.27) and no events/status endpoint served to the SPA yet (story 6.9). *Recommendation:* satisfy via fixture-driven render assertions + the documented field-mapping table now; defer a true cross-surface CLI-`status --format=json` parity harness to 2.27/6.9. Note this in Completion Notes so the reviewer doesn't expect a `toMatchSnapshot`.

### Logging Requirements (project-wide standard, frontend-adapted)

This is a frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. The equivalent contract: structured `console.info`/`console.warn({ event, …fields })` calls, **field-only** (never `error.message`, payload bytes, tokens, or PII), pinned by `console`-spy tests. Mirror `QueueShell.tsx` exactly:

- `runContext.loadError` (`warn`) — `{ event, code, transport }`, `code` from ProblemDetails `code` (never raw message/status).
- `runContext.retry` (`info`) — emitted on Retry.
- `runContext.stale` (`warn`) — `{ event, staleForMs }` when the stale state renders.
- Each new log line is asserted at the expected level by a focused test; a negative test confirms no raw message/payload is logged.

### Project Structure Notes

- Component → `src/features/workflows/components/RunContextStrip.tsx` (architecture.md: workflow-specific components live under `features/workflows`). Shared badge alongside it. Feedback-state primitives stay in `components/feedback/states/` (reused, not moved).
- No backend changes. No new npm dependency (`Intl.RelativeTimeFormat` is built-in; `lucide-react` already present).
- Gates: `tsc -b`, `eslint . --max-warnings=0`, `npm run lint:rules-test`, `vitest`, `prettier --write`. RTK corrupts only the Bash tool here — run gates via PowerShell or `rtk proxy` (memory: `rtk-hook-only-matches-bash`). Smoke-verify on WSL2/Linux before push if touching the lockfile (not expected here) (memory: `wsl-linux-ci-reproduction`, `prettier-gate-cascades-ci`).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.16 (lines 1211–1230)]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Run Context Strip (UX-DR9, lines ~1384–1419); Phase 3 Deferred Trust Surface (~1704–1709)]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Organization (~687–693); Project Structure Refinements (~1181–1204)]
- [Source: deliveryline-frontend/src/features/workflows/RunIdentityRegion.tsx — AC7 parity reference + field-mapping comments]
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx — mount point + RECOGNIZED_STATES]
- [Source: deliveryline-frontend/src/lib/state-signifiers.ts — StateName + STATE_SIGNIFIERS]
- [Source: deliveryline-frontend/src/features/workflows/QueueShell.tsx — logging + state-resolution + Skeleton composition pattern (story 2.20)]
- [Source: deliveryline-frontend/src/components/feedback/states/ErrorState.tsx — nextAction contract + useReturnToRunContext mock note (story 2.22)]
- [Source: deliveryline-backend/src/test/resources/fixture-event-streams/spec-rejection-and-resubmit.json — AC11 terminal state (run_fix_rej_001, spec v2, DEL-9002)]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

Gates (run via PowerShell to route around the RTK Bash-output corruption — memory `rtk-hook-only-matches-bash`):

- `tsc -b` → clean (after resolving two `exactOptionalPropertyTypes` prop-type errors + the stored-boolean error-narrowing into an inline `isProblemDetailsError(error)` guard).
- `eslint . --max-warnings=0` → clean (after two fixes: dropped the redundant explicit `role="region"` from the labeled `<section>` per `jsx-a11y/no-redundant-roles`; moved the pure `backendStateToStateName` map out of the badge `.tsx` into sibling `workflowStateMapping.ts` because `react-refresh/only-export-components` with `allowConstantExport` permits only literal constants, not function exports).
- `npm run lint:rules-test` → 4/4 suites pass (custom ESLint rules incl. `no-workflow-domain-in-ui-primitives` + `no-untyped-loading-state` — no `animate-spin`, no untyped `<LoadingState>`).
- `prettier --write` → applied.
- `vitest run` → 33 files / **226 tests** pass (was 199 pre-story; +27 net-new across the 3 new test files). The `Window.scrollTo not implemented` lines are jsdom stderr noise from unrelated tests, not failures.

### Completion Notes List

- **All 6 tasks + all 11 ACs satisfied.** Frontend-only story; no backend changes, no new npm dependency (`Intl.RelativeTimeFormat` + `lucide-react` already present).
- **View-model seam (AC1/AC7/AC9).** `toRunContextView` is the single pure `WorkflowDetail → RunContextView` mapper. Field mapping reconciled against the LIVE contract: revision pointer from `latestArtifactType`+`latestArtifactVersion` (no `latestArtifactId` — it does not exist); last transition = `lastEventAt` (NOT `lastActivityTimestamp`, AC7 / CLI + `RunIdentityRegion` parity); `branchOrCommitReference` hardcoded `undefined` (no E2 backend field). `staleIndicator` is DERIVED client-side from `lastActivityTimestamp` age vs `RUN_STALE_THRESHOLD_MS` (10 min) — isolated as the SOLE seam (OQ-3) so a future story-1.13 heartbeat flag swaps in by changing one line. Documented deviation from AC9's "reported by the backend".
- **Artifact-selection rule (AC1).** Highest numeric `version` wins (the most-revised artifact — proves the spec advanced past a rejection); entries without a version sort last; empty list → partial-context placeholder.
- **Shared `WorkflowStateBadge` (AC2.b/T1).** Born here under `features/workflows/` (NOT `components/ui/`). Maps backend `currentState` → `StateName` → `--state-*` tokens + lucide icon + label; unknown/undefined → neutral `informational`. The pure mapping (`backendStateToStateName` + the literal token-class map + icon resolution) lives in sibling `workflowStateMapping.ts` — story 2.15 imports the SAME source. Reusable `StateSignifierChip` also backs the strip's "Stale" + "Escalated" markers; every chip pairs color with icon + text label (AC6, never color-alone).
- **States (AC3).** Pure `resolveRunContextState`: `error → loading → stale → partial-context → default`. `partial-context` fires when a business field (latest artifact OR trigger) is unreported; `branchOrCommitReference` is a KNOWN Epic-3 deferral that always renders a placeholder but does NOT flip the state (otherwise `default` is unreachable — documented). `loading` = `Skeleton` rows (never a spinner, T3); `error` composes story-2.22 `<ErrorState variant="failedRetrieval" nextAction=Retry>`; `partial-context` = per-field "Not reported", never `<EmptyState>` (T4).
- **AC4 max-height.** Exported `RUN_CONTEXT_STRIP_MAX_HEIGHT='3.5rem'` applied as the `<section>` `maxHeight` style across all states; tests assert the constraining style is PRESENT (jsdom can't measure px — AC4 caveat) and that no `<dl>` multi-row expansion shape renders.
- **AC5 / T9.** `onNavigateToFullLineage?` prop accepted but NO UI control wires it; a test asserts no button/link renders in the default state.
- **AC6 / T8.** Single labeled landmark via `<section aria-label="Run context">` (implicit `region` role — explicit `role` removed as redundant per eslint).
- **Logging (Task 6 / T7).** Field-only structured logs mirroring QueueShell: `runContext.loadError {event,code,transport}` (code from ProblemDetails or `'transport'`, NEVER raw message), `runContext.retry`, `runContext.stale {event,staleForMs}`. Pinned by console-spy assertions + a negative test that the payload keys are exactly `code/event/transport` (no `message`).
- **AC10/AC11 reconciliation (OQ-5).** No frontend snapshot harness (story 2.27) and no events/status endpoint served to the SPA (story 6.9), so "content matches `status --format=json`" is satisfied via fixture-driven render assertions over the new frontend fixture `specRejectAndResubmit.ts` (`run_fix_rej_001`, `Completed`, spec v2 post-resubmit, `DEL-9002`) + the documented field-mapping table — NOT `toMatchSnapshot`. Full cross-surface CLI parity remains deferred.
- **Recommend** running `code-review` with a different LLM. A WSL2/Linux smoke is NOT required here — no lockfile/dependency change (memory `wsl-linux-ci-reproduction` applies only when the lockfile moves).

### File List

- `deliveryline-frontend/src/features/workflows/runContextView.ts` (new) — `RunContextView` + `toRunContextView` + `RUN_STALE_THRESHOLD_MS` + `resolveRunContextState`.
- `deliveryline-frontend/src/features/workflows/runContextFormat.ts` (new) — `formatRelativeTime` (`Intl.RelativeTimeFormat`) + `formatUtcTimestamp`.
- `deliveryline-frontend/src/features/workflows/runContextView.test.ts` (new) — mapper + state-resolution unit tests (incl. AC7 parity, AC9 stale boundary).
- `deliveryline-frontend/src/features/workflows/components/WorkflowStateBadge.tsx` (new) — `WorkflowStateBadge` + `StateSignifierChip`.
- `deliveryline-frontend/src/features/workflows/components/workflowStateMapping.ts` (new) — `backendStateToStateName` + token-class map + icon resolution (shared seam for story 2.15).
- `deliveryline-frontend/src/features/workflows/components/WorkflowStateBadge.test.tsx` (new) — badge + mapping tests.
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx` (new) — the component + `RUN_CONTEXT_STRIP_MAX_HEIGHT`.
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.test.tsx` (new) — state/AC11/logging tests.
- `deliveryline-frontend/src/test/fixtures/runContext/specRejectAndResubmit.ts` (new) — AC11 frontend fixture.
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (modified) — mounts `<RunContextStrip>` above the review pane; removed the 2.16 placeholder sentence.

## Change Log

| Date | Version | Description |
|---|---|---|
| 2026-06-01 | 0.1 | Story 2.16 implemented — `RunContextStrip` + `toRunContextView` mapper + shared `WorkflowStateBadge`/`StateSignifierChip` + relative-time formatters + AC11 fixture; mounted in `WorkflowDetailRoute`. All 6 tasks + 11 ACs; gates green (tsc, eslint --max-warnings=0, lint:rules-test 4/4, vitest 226/226, prettier). Status ready-for-dev → review. |
