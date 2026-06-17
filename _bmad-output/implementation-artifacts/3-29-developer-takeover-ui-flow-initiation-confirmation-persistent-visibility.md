# Story 3.29: Developer Takeover UI Flow — Initiation, Confirmation, Persistent Visibility

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **Developer initiating a takeover from the Decision Bar (story 3.28) AND a reviewer/PM later opening a taken-over run**,
I want **the takeover state surfaced as first-class, persistent UX — a distinct queue badge + filter, a Run Context Strip "taken over by X, when, why" block, and a prominent takeover row in the run-event surface — sourced from backend truth**,
so that **takeover is not just a backend transition: any reviewer opening the run later sees clearly who took over, when, why, and where the work continued (FR19 reconstruction surface), and the active queue stays uncluttered.**

## Acceptance Criteria

> **Reconciliation note (read first):** the epic AC text was written before stories 3.23/3.24/3.25/3.28 landed and assumes wire fields + UI surfaces that do not exist as written. Where the live contract diverges it is flagged inline as **[R#]** and resolved in Dev Notes → *Central Reconciliations*. **The flagged item WINS over the AC prose.** This is a **frontend-only consumer story** — NO backend / OpenAPI / `schema.d.ts` change.

1. **Given** the takeover initiation flow from story 3.28 AC4, **Then** the confirmation dialog is the only entry point to takeover; the action cannot be triggered accidentally (no keyboard shortcut without confirmation, no double-click escalation). **[R1]** This is already delivered by story 3.28's `ApprovalDecisionBar` (shared `<ConfirmationDialog>`, `intent="danger"`, required `reasonText`, confirm gated until non-blank, in-flight disable). 3.29 adds a **regression pin**, not a rebuild.
2. **Given** the takeover mutation succeeds and returns the typed result from story 3.25 AC8, **Then** the UI immediately: (a) the `useWorkflowDetail(workflowRunId)` query is invalidated (TanStack Query, story 2.6 AC6) **[R7]** — already covered by `useWorkflowMutation.onSuccess` (`detail(id)` is a prefix of `events(id)`/`allowedActions(id)`, plus `lists()`); (b) Run Context Strip (story 2.16) re-renders showing `currentState='TakenOver'` **[R3 live]** + a takeover attribution block (`takenOverBy` + `takenOverAt` + `takenOverReason`) **[R2/R4]** derived **live from the workflow-events stream** (NOT from a detail DTO field — those do not exist on the wire); (c) Decision Bar transitions to the read-only takenover state with "Continue work in PR {ref}" per story 3.28 AC7. **[R1]** (c) is already delivered by 3.28.
3. **Given** the queue (story 2.15 `RunReviewQueueItem`), **Then** taken-over runs render with a distinct takeover state badge (icon + "Taken over" label — non-color signifier per story 2.3 AC5). **[R3]** The badge is LIVE today via `WorkflowStateBadge currentState='TakenOver'` (mapped to the `recovery` token, not `state-draft` as the AC prose suggests — keep `recovery` for consistency with the Decision Bar / `FailureEventSurface`). **[R5]** The `takenOverBy` actor + `takenOverAt` timestamp in queue-row metadata are **DORMANT** (the lean `WorkflowSummary` list payload carries neither, and per-row event fetches are out of scope) — built + tested via constructed fixtures, never fabricated from live data.
4. **Given** the queue filters (extending story 2.15/2.20), **Then** a "Show taken-over runs" filter toggle exists. **[R5]** Live posture: the toggle navigates the EXISTING single-state `?state` filter to `?state=TakenOver` (reusing `WorkflowListFilters.state` → `?state` query param → backend `listRuns(stateFilter)`), showing ONLY taken-over runs when on. The epic's "default: hidden so they don't clutter the active queue" default-EXCLUSION is a **backend list-default** behavior (the live `listRuns(null)` returns ALL states) and is **OUT of frontend scope** (OQ-2 / defer).
5. **Given** the run-event surface (story 3.30 `FailureEventSurface` — the only event surface; there is no full run-timeline UI, Epic 4 owns it), **Then** the takeover transition renders with a prominent visual treatment — actor name + timestamp + reason text + an event `publicId` anchor (AC5 permalink) — preserving story 3.22 AC12 FR19 reconstruction in the UI. **[R6]** There is NO `recovery.takeover` event type; takeover is a live `workflow.stateChanged` event with `resultingState='TakenOver'`. Extend `FailureEventSurface` to surface it (LIVE via `useWorkflowEvents`); do NOT add a backend event type.
6. **Given** post-takeover navigation from story 3.28 AC7, **When** the user clicks "Continue work in PR {ref}", **Then** they navigate to the linked GitHub PR (new tab, via `githubRef.ts`); the DeliveryLine UI does not deep-link into GitHub for embedded editing — the integration ends at the PR-ref link in E3. **[R1]** Already delivered by 3.28; 3.29 pins it.
7. **Given** opening a taken-over run later, **Then** the entire UI consistently reflects the takeover state — queue badge, Run Context Strip (state + attribution block), Decision Bar (read-only), run-event surface (takeover row) — no surface incorrectly shows the run as in-flight or actionable. **[R7]** The shared `useWorkflowEvents`/`useWorkflowDetail`/`useAllowedActions` cache (warmed by the route loader, invalidated together) makes this consistency automatic; this AC is a cross-surface integration test.
8. **Given** correlation propagation per story 1.19, **Then** the takeover mutation carries the originating `correlationId` so backend logs trace back to the UI action. **[R1]** Already delivered: the API client middleware rides `X-Correlation-Id` on every request (story 2.6/1.19); the takeover hook needs no per-call header code.
9. **Given** the ARIA live region + announcement vocabulary per story 2.25 AC7, **Then** when takeover succeeds an ARIA live region announces the state transition. **[R8]** Already delivered by 3.28 (`workflowTakenOver` routed through the single polite region on takeover success). Optional enrichment: format `workflowTakenOver` as `(runId, actor) => string` to match the AC's "Workflow run {runId} has been taken over by {actor}". Do NOT auto-announce on every passive page load — keep announcements to the action moment.
10. **Given** component + integration test coverage, **Then** tests cover: takeover initiation only via confirmation dialog (regression pin); successful takeover updates Run Context Strip + Decision Bar + Queue Item + run-event surface simultaneously (invalidation works); the takeover attribution selector derives `{takenOverBy, takenOverAt, takenOverReason}` from a live `workflow.stateChanged→TakenOver` event; queue takeover badge + `?state=TakenOver` filter toggle; run-event surface renders the takeover row prominently with anchor; "Continue work in PR" navigation (pin); taken-over state visible across all surfaces consistently; ARIA live region announcement; **axe-core a11y: zero violations**.

## Tasks / Subtasks

- [x] **Task 1 — Pure takeover-attribution selector over the events stream** (AC2, AC5, AC7) **[R2, R6]**
  - [x] Add `selectTakeoverAttribution(events: WorkflowEvent[]): TakeoverAttributionView | undefined` to a NON-component module (e.g. a new `features/workflows/takeoverView.ts` — NEVER export helpers from a `.tsx`, `[[frontend-react-refresh-no-fn-exports]]`). It returns the attribution from the **latest** event matching `eventType === 'workflow.stateChanged' && resultingState === 'TakenOver'`: `{ takenOverBy: actorIdentity, actorType, reviewerRole: details.reviewerRole, takenOverAt: createdAt, takenOverReason: reason ?? undefined, eventId: publicId }`. Mirror the backend fallback (`WorkflowInspectionService.java:322-331`) — this is backend truth, no DTO field needed.
  - [x] Define `TakeoverAttributionView` (readonly slot type) with `exactOptionalPropertyTypes`-safe optionals (`T | undefined`, never bare optional with an assigned `undefined`; `[[artifactview-variant-field-fanout]]`).
  - [x] Events are append-only and may arrive in any order — select by max `createdAt` (defensive against ordering), not array position.

- [x] **Task 2 — Run Context Strip: takeover attribution block** (AC2b, AC7) **[R3, R4]**
  - [x] Add a `takeover?: TakeoverAttributionView | undefined` slot to `RunContextView` (`runContextView.ts`). Keep `toRunContextView` detail-only (it has no events input); the slot is merged in the strip/container from the Task-1 selector.
  - [x] In `RunContextStrip` (or a thin container around it), consume `useWorkflowEvents(workflowRunId)` **only when** `currentState === 'TakenOver'` (gate the query `enabled`, or derive lazily) and feed `selectTakeoverAttribution(...)` into the view. Render a takeover sub-region: "Taken over" chip (reuse `StateSignifierChip stateName='recovery'` + humanized "Taken over" label) + actor + relative time (`runContextFormat`) + reason. Reason text renders escaped / via `SafeMarkdownRenderer` (untrusted reviewer text — never raw).
  - [x] Render the block ONLY when `takeover` is present; absent → render nothing (no empty placeholder, mirror story 3.31 T-ABSENT discipline).

- [x] **Task 3 — Queue: takeover badge label + dormant attribution slots** (AC3) **[R3, R5]**
  - [x] Confirm `WorkflowStateBadge currentState='TakenOver'` already renders the `recovery` chip — do NOT re-map to `state-draft`, and do NOT change the shared badge's raw label (CLI parity). If the epic's "Taken over" wording is wanted in the queue/strip, add a LOCAL humanize map (`TakenOver → 'Taken over'`) for the takeover-specific chip only.
  - [x] Add DORMANT `takenOverBy?: string | undefined` + `takenOverAt?: string | undefined` slots to `RunQueueRow` (`runQueueRow.ts`), mapped `undefined` in `toRunQueueRow` with a comment citing the lean `WorkflowSummary` (same reconciliation as `summary`/`prLinkage`). Render them in `RunReviewQueueItem`'s secondary metadata cluster ONLY when present (fixture-driven). Takeover is metadata, NOT an attention/dominant-state signal — do NOT feed `resolvePrimaryAttentionIndicator`/`resolveQueueItemState`.

- [x] **Task 4 — Queue filter: "Show taken-over runs" toggle** (AC4) **[R5]**
  - [x] Add a minimal toggle control (in `QueueShell` or the `/workflows` route header) that navigates `?state=TakenOver` ⇄ `?state` cleared, reusing the live `WorkflowListFilters.state` plumbing (`useWorkflowsList` → `fetchWorkflowList` `query.state`). Reflect active state from `Route.useSearch()` (URL is the single source of truth — `QueueShell` holds no filter state).
  - [x] Document the single-state semantics (toggle ON shows ONLY taken-over runs). Do NOT attempt a default-exclusion of taken-over runs from the unfiltered queue — that is a backend list-default change (OQ-2 / defer).
  - [x] Keep the existing `filtered-empty` + "Clear filters" path working for `?state=TakenOver` with zero results.

- [x] **Task 5 — Run-event surface: takeover row** (AC5) **[R6]**
  - [x] Extend `FailureEventSurface.isSurfaceEvent` to also match the takeover transition (`eventType === 'workflow.stateChanged' && resultingState === 'TakenOver'`). Render it as a prominent `recovery`-styled row: "Taken over" `StateSignifierChip` + actor + reason + relative/UTC time + the event `publicId` as an anchor (AC5 permalink — `id`/`data-event-id`, no fabricated URL). Reuse the existing `recovery` border/styling already used for `recovery.retried`.
  - [x] Keep scope discipline: ONE extra event class (takeover), NOT a general-purpose timeline. Update the file's scope comment.
  - [x] If desired, surface the takeover reason/correlationId in the existing `BoundedDetailSheet` diagnostics panel on click (reuse the failure-event pattern; reason via escaped text).

- [x] **Task 6 — Regression pins for the 3.28-delivered surfaces** (AC1, AC2c, AC6, AC8, AC9) **[R1]**
  - [x] Pin (do NOT rebuild): confirmation-only initiation (no accidental trigger); Decision Bar read-only takenover + "Continue work in PR {ref}" affordance; `X-Correlation-Id` rides the takeover request; `workflowTakenOver` announced on success. These live in `ApprovalDecisionBar`/`useTakeoverWorkflow` (story 3.28). Optional: enrich `workflowTakenOver` into a `(runId, actor)` formatter (AC9 wording) routed through the same live region.

- [x] **Task 7 — Tests** (AC10) — Vitest + Testing Library + axe-core (story 2.27 / 3.28 / 3.30 / 3.31 coverage pattern):
  - [x] `takeoverView.test.ts`: selector picks the latest `workflow.stateChanged→TakenOver` event; ignores non-takeover state changes; returns `undefined` when none; tolerates out-of-order events.
  - [x] `RunContextStrip` test: renders the takeover block (actor/time/reason) from a fixture events stream when `currentState='TakenOver'`; omits it otherwise; reason is escaped.
  - [x] `RunReviewQueueItem` test: renders the `TakenOver` recovery badge ("Taken over"); renders dormant actor/timestamp metadata when the fixture provides them; omits when absent.
  - [x] Queue filter test: toggle navigates to `?state=TakenOver` and back; reflects URL state; `filtered-empty` + clear works.
  - [x] `FailureEventSurface` test: renders the takeover row prominently with anchor + actor + reason; does not break the existing failure/`recovery.retried` rendering.
  - [x] Integration test (AC2/AC7): a successful takeover (mock `useTakeoverWorkflow`) invalidates the shared cache so the strip + badge + event surface + Decision Bar update together; consistency across surfaces on a pre-taken-over run loaded fresh.
  - [x] axe-core zero violations on the strip takeover block + queue badge + event-surface takeover row.
  - [x] `npm run check:api` MUST show ZERO diff (frontend-only — the OpenAPI contract is frozen). If `generate-api` produces a diff, something is wrong.

- [x] **Logging instrumentation** (cross-cutting; required on every story) **[R1 — frontend `console` discipline]**
  - [x] This is a frontend story; the observable surface is the existing field-only structured `console.info`/`console.warn` discipline (see `QueueShell`/`ApprovalDecisionBarContainer`/`RecoveryDecisionBarContainer`). No SLF4J/MDC applies.
  - [x] Emit field-only events for the NEW interactions: `queue.toggleTakenOverFilter` (the new state, `'TakenOver' | undefined`), and (if added) a takeover-row open in the event surface (reuse the failure-event open log shape). The takeover SUBMIT/error/announcement logging already lives in 3.28's container (`impl.takeoverSubmit`/`impl.submitError`) — do NOT duplicate.
  - [x] **NEVER log** `reasonText`/`takenOverReason` (reviewer free text), `preservedPrReference`, actor identity beyond what existing surfaces already log, or any PII (T-LOG-PII). Stable enum codes / response states / `ProblemDetails.code` + a `transport` boolean only.
  - [x] Pin each new log branch with a focused `console`-spy assertion (the existing tests' pattern).

## Dev Notes

### This is a frontend-only consumer story
All takeover backend endpoints + their OpenAPI + the generated `schema.d.ts` already shipped (stories 3.22/3.25, `done`) and the **initiation/confirmation/Decision-Bar/PR-affordance/correlation/announcement already shipped in story 3.28** (`done`). **Do not touch the backend or regenerate the OpenAPI snapshot** — `npm run check:api` must show zero diff. All work is under `deliveryline-frontend/src/features/workflows/` (+ `lib/a11y` if enriching the announcement). The story is **persistent-visibility surfaces** (queue, Run Context Strip, run-event surface), plus regression pins for what 3.28 delivered.

### The reuse map (do NOT reinvent)
| Need | Reuse / extend | Path |
|---|---|---|
| Live takeover/who/when/why source | `useWorkflowEvents` → latest `workflow.stateChanged` w/ `resultingState='TakenOver'` (`actorIdentity`/`createdAt`/`reason`/`details.reviewerRole`) | `hooks/useWorkflowEvents.ts` |
| State badge | `WorkflowStateBadge` (already maps `TakenOver → recovery`) + `StateSignifierChip` | `components/WorkflowStateBadge.tsx`, `components/workflowStateMapping.ts` |
| Run Context Strip + view model | `RunContextStrip` + `runContextView.ts` (add `takeover` slot) | `components/RunContextStrip.tsx`, `runContextView.ts` |
| Queue item + view model | `RunReviewQueueItem` + `runQueueRow.ts` (add dormant slots) | `components/RunReviewQueueItem.tsx`, `runQueueRow.ts` |
| Queue shell + filter plumbing | `QueueShell` + `WorkflowListFilters.state` (`?state`) | `QueueShell.tsx`, `lib/queryKeys/workflowKeys.ts`, `routes/workflows/index.tsx` |
| Run-event surface | `FailureEventSurface` (extend `isSurfaceEvent`) | `components/FailureEventSurface.tsx` |
| Decision Bar takeover affordance (DONE 3.28) | `ApprovalDecisionBar` / `ImplementationReviewDecisionBarContainer` | `components/ApprovalDecisionBar.tsx` |
| Takeover mutation + invalidation (DONE 3.28) | `useTakeoverWorkflow` on `useWorkflowMutation` | `hooks/useTakeoverWorkflow.ts`, `hooks/useWorkflowMutation.ts` |
| PR-ref → link (DONE 3.28) | `githubRef.ts` (dot-traversal-hardened, `isGitHubHttpsUrl`) | `features/workflows/githubRef.ts` |
| Announcements | `lib/a11y/announcements.ts` (`workflowTakenOver` exists) + `useLiveAnnouncement` | `lib/a11y/*` |
| Relative/UTC time | `runContextFormat` (`formatRelativeTime`/`formatUtcTimestamp`) | `features/workflows/runContextFormat.ts` |

### Central Reconciliations (these WIN over the epic AC prose)

- **[R1] 3.28 already delivered initiation + confirmation + Decision-Bar read-only + "Continue work in PR" + correlation + success announcement.** AC1, AC2(c), AC6, AC8, AC9(success-moment) are DONE in `ApprovalDecisionBar`/`useTakeoverWorkflow`/`githubRef.ts` (story 3.28, `done`). 3.29 adds **regression pins**, not rebuilds, for these — and focuses net-new work on the persistent-visibility surfaces 3.28 left untouched.
- **[R2] (HEADLINE) Takeover attribution is LIVE via the events stream — not a detail-DTO field, not a backend change.** The REST `WorkflowDetail` and `WorkflowSummary` carry NO `takenOverBy/At/Reason` (`schema.d.ts:574-610`, `:692-728`). They exist only **app-internally** in `WorkflowInspectionService.getRunSummary` (`WorkflowRunDetailedSummaryView`), unmapped to OpenAPI. BUT the takeover transition is a LIVE `workflow.stateChanged` event with `resultingState='TakenOver'`, `actorIdentity`, `actorType`, `createdAt`, `reason`, `details.reviewerRole` — the SAME source `getRunSummary` reads as its fallback (`WorkflowInspectionService.java:322-331`). Derive attribution from it (Task 1). **Backend truth, zero backend/OpenAPI churn.** (Adding the fields to the detail DTO is a separate backend story — OQ-1.)
- **[R3] `TakenOver` state + badge are LIVE; keep the `recovery` token, do NOT use `state-draft`.** `currentState='TakenOver'` is on the wire enum and in `RECOGNIZED_STATES`; `workflowStateMapping` maps `TakenOver → recovery` (amber, `RotateCcw`, label = raw "TakenOver"). The Decision Bar and `FailureEventSurface` already use `recovery` styling for takeover/retry — re-mapping to `state-draft` (epic AC3) would desync them. Keep `recovery`. AC3's non-color signifier (icon + label) is already satisfied; humanize the label to "Taken over" only in the takeover-specific chips, not the shared badge (CLI parity).
- **[R4] Run Context Strip: the state flip is already live; the attribution block is the net-new.** The strip reads `useWorkflowDetail`+`toRunContextView`; `currentState` flips to `TakenOver` live and the badge follows. Add the attribution block fed by the Task-1 events selector (the strip/container also reads `useWorkflowEvents`, gated to `currentState==='TakenOver'`). Keep `toRunContextView` pure/detail-only — merge the takeover slot in the component layer.
- **[R5] Queue: badge live; per-row attribution dormant; filter is a `?state=TakenOver` toggle.** The lean `WorkflowSummary` has no attribution and no per-row events → `takenOverBy`/`takenOverAt` are DORMANT slots (fixture-driven, mapped `undefined`, exactly like `summary`/`prLinkage`). There are NO filter control primitives today and `listRuns(stateFilter=null)` returns ALL states — so AC4's "default hidden" default-EXCLUSION is a backend concern (OQ-2 / defer). Ship a minimal toggle reusing the single-state `?state` filter (`?state=TakenOver` = view-only-taken-over).
- **[R6] Run timeline = extend `FailureEventSurface`; no `recovery.takeover` event type.** There is no full timeline (Epic 4 owns it) and no `recovery.takeover` in the live `eventType` enum (takeover = `workflow.stateChanged→TakenOver`). Adding a backend event type triggers the `WorkflowEventType` fixture fan-out (`[[new-workfloweventtype-fixture-sites]]`) + backend work — do NOT. Surface the live state-change event in the existing minimal surface, scope-disciplined.
- **[R7] Invalidation already refreshes every surface together.** `useWorkflowMutation.onSuccess` invalidates `workflowKeys.detail(id)` (a structural PREFIX of `events(id)` + `allowedActions(id)`) plus `lists()`. So a successful takeover refetches the detail, events, allowed-actions, and queue list — the strip, attribution block, event surface, Decision Bar, and queue badge update simultaneously (AC2/AC7). NO new invalidation wiring; pin it with a test.
- **[R8] Announcement.** 3.28 fires `workflowTakenOver` on takeover success via the single polite region. AC9's "Workflow run {runId} has been taken over by {actor}" can be matched by formatting `workflowTakenOver` as `(runId, actor) => string`. Do NOT auto-announce on passive load — announcements belong to the action moment (live-region politeness; assistive-tech users opening a taken-over run read the static strip block, not a re-announced alert).

### Backend contract (frozen — quoted from the live schema)
- **`WorkflowEvent`** (`schema.d.ts:612-666`): `actorIdentity` (string, req), `actorType` (`human|agent|system|service_account`), `createdAt` (date-time), `reason?` (string|null), `priorState?` / `resultingState?` (the 11-state enum incl. `TakenOver`), `publicId`, `details.reviewerRole?`, `details.correlationId?`. **This is the live attribution source.**
- **`WorkflowEventsResponse`** (`:668-671`): `{ events: WorkflowEvent[], workflowRun: WorkflowRunRef }`; `WorkflowRunRef.terminalState` (incl. `TakenOver`). Served by the live `getWorkflowEvents` (`useWorkflowEvents`).
- **`WorkflowDetail`** (`:574-610`): `currentState?` (incl. `TakenOver`), `currentActorIdentity?`, `currentActorType?`, `latestArtifacts?`, `linkedTicket?`, failure/escalation fields. **NO `takenOverBy/At/Reason`, NO `integrationLinks`** (the `WorkflowDetailWithLinkage` type is a frontend-only dormant extension from 3.31).
- **`WorkflowSummary`** (`:692-728`): `currentState?`, `escalationMarker?`, `lastEventAt?`, `lastEventType?`, `specRejectionLoopCount?`, `ticketRef?`, `workflowRunId?`. **NO attribution, NO PR/state projection.**
- **Filter**: `GET /api/v1/workflows?state=<WorkflowState>` (single optional state) → backend `WorkflowInspectionService.listRuns(stateFilter, limit)`; `stateFilter=null` returns ALL states (no default takeover exclusion).
- **Takeover mutation (DONE 3.28)**: `useTakeoverWorkflow` → `POST .../takeover`, body `TakeoverRequest { reasonText }`, response rich `TakeoverResponse { currentState='TakenOver', preservedPrReference?, cancelledInFlightCount?, cancelledQueuedCount?, correlationId? }`.

### Architecture compliance
- **No backend / no OpenAPI changes** — consumer-only. `npm run generate-api` must produce ZERO diff (frozen contract).
- **`react-refresh/only-export-components`** (`[[frontend-react-refresh-no-fn-exports]]`): the new selector + view types go in a `.ts` (`takeoverView.ts`), NEVER exported from a `.tsx`.
- **`exactOptionalPropertyTypes`** is on (`[[artifactview-variant-field-fanout]]`): optional view-model fields are `T | undefined`, never bare-optional with an assigned `undefined` literal.
- **No frontend permission inference** (UX-DR12): eligibility comes ONLY from `useAllowedActions` (the Decision Bar's concern, already enforced). The queue badge/filter + strip block are PRESENTATION of backend-reported state/events — not permission decisions.
- **Untrusted text**: `takenOverReason`, actor identity, and any echoed strings render escaped / via `SafeMarkdownRenderer` — never raw. The PR ref (3.28 path) passes `githubRef.ts` URL hardening (`[[githubref-branchurl-dot-traversal]]`).
- **`WorkflowDetail` nullable wire** (`[[workflowdetail-wire-sends-null-not-undefined]]`): guard `!= null` before string ops on nullable fields; `reason` can be `null` (not just `undefined`).
- **Vitest cross-file mock hygiene** (`[[vitest-cross-file-router-mock]]`): consolidate same-module mocks; **live-announcement defer** (`[[livesnnouncement-defers-one-commit-test-flake]]`): assert announcement text via `waitFor`, never synchronously.

### Project Structure Notes
- **New files:** `features/workflows/takeoverView.ts` + `takeoverView.test.ts`.
- **Modified:** `runContextView.ts` (+`takeover` slot), `components/RunContextStrip.tsx` (+test), `runQueueRow.ts` (+dormant slots), `components/RunReviewQueueItem.tsx` (+test), `QueueShell.tsx` or `routes/workflows/index.tsx` (filter toggle; +tests), `lib/queryKeys/workflowKeys.ts` (only if a typed toggle helper is added — `?state` already supports it), `components/FailureEventSurface.tsx` (+test), and (optional) `lib/a11y/announcements.ts` (announcement enrichment). Relevant fixtures under `src/test/fixtures/`.
- The run-detail route (`routes/workflows/$workflowRunId/index.tsx`) already mounts `RunContextStrip` + `FailureEventSurface` + `WorkflowDecisionBar` — no route restructure needed.

### Logging Requirements (project-wide standard)
Frontend story; observable surface is the existing field-only structured `console` discipline. No SLF4J/MDC. Hard rule: **never log reviewer-authored free text or PII** (`takenOverReason`, `preservedPrReference`) — only stable enum codes / state values / `ProblemDetails.code` + a `transport` boolean. Pin each new log branch (`queue.toggleTakenOverFilter`, event-row open) with a focused `console`-spy test. The takeover submit/error logs already exist in 3.28's container — do not duplicate.

### References
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.29] (lines 578-595)
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowEvents.ts + lib/api/schema.d.ts:612-685] — the live `WorkflowEvent`/`WorkflowEventsResponse` attribution source
- [Source: deliveryline-backend/.../application/workflow/WorkflowInspectionService.java:300-347] — `getRunSummary` takeover attribution (app-internal) + the transition-event fallback this story mirrors client-side; `listRuns` (line 878) returns all states when `stateFilter=null`
- [Source: deliveryline-frontend/src/features/workflows/components/workflowStateMapping.ts:30-49] — `TakenOver → recovery` mapping (R3)
- [Source: deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx + runContextView.ts] — strip + view model (R4)
- [Source: deliveryline-frontend/src/features/workflows/runQueueRow.ts + components/RunReviewQueueItem.tsx] — queue row + dormant-slot pattern (R5)
- [Source: deliveryline-frontend/src/features/workflows/QueueShell.tsx + lib/queryKeys/workflowKeys.ts + routes/workflows/index.tsx] — queue shell + `?state` filter plumbing (R5)
- [Source: deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx] — the minimal event surface to extend (R6)
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts:89-96] — `detail(id)`-prefix invalidation covering events/allowed-actions + lists (R7)
- [Source: _bmad-output/implementation-artifacts/3-28-approval-decision-bar-implementation-review-mode-activation.md] — the DONE initiation/confirmation/PR-affordance/announcement this story pins (R1)
- Memory: [[story-3-22-developer-takeover-reconciliations]] (getRunSummary takeover fields are app-internal), [[story-3-25-takeover-rest-reconciliations]] (`TakeoverResponse` + consequence text), [[story-3-31-pr-linkage-display-reconciliations]] (the dormant frontend-owned view-slot pattern this story reuses), [[story-3-30-retry-ui-reconciliations]] (`FailureEventSurface` + recovery styling), [[new-workfloweventtype-fixture-sites]] (why NOT to add a `recovery.takeover` event type), [[githubref-branchurl-dot-traversal]], [[artifactview-variant-field-fanout]], [[frontend-react-refresh-no-fn-exports]], [[workflowdetail-wire-sends-null-not-undefined]], [[livesnnouncement-defers-one-commit-test-flake]], [[vitest-cross-file-router-mock]], [[prettier-gate-cascades-ci]], [[frontend-lockfile-cross-platform]]

### Open Questions for Alex
- **OQ-1 (attribution source, RECOMMEND Path C):** Confirm the takeover attribution (who/when/why) is sourced from the **live workflow-events stream** (`workflow.stateChanged→TakenOver`; frontend-only, no backend/OpenAPI change, this story's default) — vs. a backend story that maps `takenOverBy/At/Reason` onto the REST `WorkflowDetail` DTO + OpenAPI + `schema.d.ts`. Path C keeps 3.29 in the frontend-only family and still delivers live FR19 visibility.
- **OQ-2 (AC4 default-exclusion):** Accept the minimal frontend-only `?state=TakenOver` view-only toggle (this story's default) and DEFER the "taken-over runs hidden from the unfiltered queue by default" behavior to a backend list-default change — or pull that backend change into scope? Recommend defer.
- **OQ-3 (AC5 home):** Accept extending `FailureEventSurface` to carry the takeover row (this story's default) — or wait for Epic 4's full run-timeline UI? Recommend extend now.
- **OQ-4 (AC3 badge color):** Confirm KEEP the live `recovery` (amber) token for `TakenOver` over the epic's `state-draft` suggestion. Recommend keep `recovery`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Full frontend gate suite GREEN (PowerShell/Bash, [[rtk-hook-only-matches-bash]]): `tsc -b` 0 errors; `eslint . --max-warnings=0` 0; `prettier --check` clean; `check:api` ZERO diff (frozen contract honored — no backend/OpenAPI/`schema.d.ts` change); `check:a11y` 4/4; `lint:rules-test` 9/9; full `vitest run` **1036/1036** (up from 3.28's 1005 — +31 net-new takeover tests, 0 regressions).

### Completion Notes List

Frontend-only persistent-takeover-visibility story over the DONE 3.22/3.25 backend + DONE 3.28 Decision-Bar takeover flow. All 8 reconciliations honored (live contract WINS over epic AC prose).

- **Task 1 (AC2/AC5/AC7, R2 HEADLINE):** new `takeoverView.ts` — pure `selectTakeoverAttribution(events)` derives `{takenOverBy, actorType, reviewerRole, takenOverAt, takenOverReason, eventId}` from the LATEST `workflow.stateChanged → TakenOver` event by max `createdAt` (out-of-order safe), mirroring the backend `getRunSummary` fallback. ZERO backend churn — attribution is LIVE from the events stream, NOT a DTO field. `exactOptionalPropertyTypes`-safe view type.
- **Task 2 (AC2b/AC7, R3/R4):** added an optional `takeover?` slot to `RunContextView` (mapper stays detail-only); `RunContextStrip` mounts a new internal `RunTakeoverAttribution` child ONLY when `currentState === 'TakenOver'` — that conditional mount GATES `useWorkflowEvents` (R4, no fetch on non-taken-over runs). Renders a recovery-styled "Taken over" chip + actor + role + relative time + escaped reason; absent → nothing (T-ABSENT).
- **Task 3 (AC3, R3/R5):** the `TakenOver` recovery badge is already LIVE via `WorkflowStateBadge` (kept the `recovery` token + raw "TakenOver" label for CLI parity — NOT `state-draft`). Added DORMANT `takenOverBy?`/`takenOverAt?` slots to `RunQueueRow` (mapped `undefined`, fixture-driven like `prLinkage`), rendered in the secondary cluster ONLY when present; takeover is metadata, never feeds `resolvePrimaryAttentionIndicator`/`resolveQueueItemState`.
- **Task 4 (AC4, R5):** "Show taken-over runs" toggle in `QueueShell` navigating `?state=TakenOver` ⇄ cleared via the live single-state `?state` plumbing (`onToggleTakenOverFilter` wired in the route). URL is the single source of truth (`aria-pressed` reflects `filters.state`); `filtered-empty` + Clear-filters path preserved. Default-exclusion deferred (OQ-2, a backend list-default concern).
- **Task 5 (AC5, R6):** extended `FailureEventSurface.isSurfaceEvent` to also match the takeover transition (NO new `recovery.takeover` event type — avoids the `WorkflowEventType` fixture fan-out). The takeover row leads with actor + escaped reason + relative time + the event `publicId` as an `id`/`data-event-id` permalink anchor; recovery styling. Heading/aria-label generalized "Failure timeline" → "Run events" (a taken-over run may carry no failures); diagnostics panel adapts (actor instead of category, no runner-logs placeholder for takeover).
- **Task 6 (AC1/AC2c/AC6/AC8/AC9, R1):** PINS, not rebuilds. Initiation/confirmation/Decision-Bar-read-only/Continue-in-PR/correlation/success-announcement remain covered by 3.28's own passing suites (`ImplementationReviewDecisionBarContainer.test`, `ApprovalDecisionBar.test`, `useTakeoverWorkflow.test` — all green in the 1036). Added explicit regression pins: R7 invalidation-cascade (`detail(id)` is a structural prefix of `events(id)`/`allowedActions(id)`) + AC9 announcement-vocabulary stability. AC9 OPTIONAL `(runId, actor)` formatter NOT taken — the existing `workflowTakenOver` const already announces takeover success at the action moment (changing 3.28's wiring would add risk for an explicitly-optional enrichment).
- **Task 7 (AC10):** new `takeoverConsistency.integration.test.tsx` — strip attribution + event-surface row reflect the takeover together from one shared `QueryClient` (AC7, no surface shows in-flight) + combined axe scan. Plus per-surface tests (selector, strip block, queue badge/metadata, filter toggle, event row) and axe zero-violations on every new surface.
- **Logging:** new field-only `queue.toggleTakenOverFilter` (the new `'TakenOver' | undefined` state only) pinned with a console-spy. NEVER logs `takenOverReason`/actor-PII (T-LOG-PII). No row-open log added to the event surface (the existing surface logs none; the takeover SUBMIT log already lives in 3.28's container — not duplicated).

### File List

- `deliveryline-frontend/src/features/workflows/takeoverView.ts` (NEW)
- `deliveryline-frontend/src/features/workflows/takeoverView.test.ts` (NEW)
- `deliveryline-frontend/src/features/workflows/components/__tests__/takeoverConsistency.integration.test.tsx` (NEW)
- `deliveryline-frontend/src/features/workflows/runContextView.ts` (+`takeover` slot + import)
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx` (+`RunTakeoverAttribution` block + gated mount)
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.test.tsx` (+takeover-block tests)
- `deliveryline-frontend/src/features/workflows/runQueueRow.ts` (+dormant `takenOverBy`/`takenOverAt` slots)
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx` (+takeover metadata + `takeoverMetaText` helper)
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx` (+takeover badge/metadata tests)
- `deliveryline-frontend/src/features/workflows/__tests__/runQueueRow.test.ts` (+dormant-mapping assertion)
- `deliveryline-frontend/src/features/workflows/QueueShell.tsx` (+filter toggle + `queue.toggleTakenOverFilter` log)
- `deliveryline-frontend/src/features/workflows/__tests__/QueueShell.test.tsx` (+filter-toggle tests)
- `deliveryline-frontend/src/routes/workflows/index.tsx` (+`onToggleTakenOverFilter` wiring)
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx` (+takeover row + diagnostics + heading rename)
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.test.tsx` (+takeover-row tests)

### Change Log

- 2026-06-17 — Story 3.29 implemented (frontend-only persistent takeover visibility). Status `ready-for-dev` → `in-progress` → `review`. All 10 ACs satisfied (R1–R8 reconciliations applied); net-new takeover-attribution selector + Run Context Strip block + queue dormant slots/filter toggle + run-event takeover row + cross-surface consistency tests. NO backend/OpenAPI/`schema.d.ts` change (`check:api` zero diff). Gates green (tsc/eslint/prettier/check:api/check:a11y/lint:rules-test + vitest 1036/1036).

### Review Findings

_Adversarial code review 2026-06-17 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 0 decision-needed, 2 patch, 1 defer, 14 dismissed as noise/spec-sanctioned/type-safe._

- [x] [Review][Patch] Selector can be permanently pinned by an unparseable `createdAt` — add a NaN guard so a corrupt timestamp can't beat valid later takeovers (sibling `runContextFormat` already guards with `Number.isNaN`) [deliveryline-frontend/src/features/workflows/takeoverView.ts:64] — FIXED 2026-06-17 (NaN-guarded comparison + `latestMs` tracker)
- [x] [Review][Patch] `takenOverReason` is not trimmed in the selector while `FailureEventSurface` hides whitespace-only reasons — a whitespace-only reason would render in the strip but vanish in the event row (minor cross-surface inconsistency vs AC7). Coalesce blank → `undefined` in the selector for parity [deliveryline-frontend/src/features/workflows/takeoverView.ts:76] — FIXED 2026-06-17 (blank/whitespace reason coalesced to `undefined`)
- [x] [Review][Defer] Unrelated story-3b-3 test file present untracked in the working tree (its own header declares "Story 3b-3"); not part of the 3.29 changeset — keep it out of the 3.29 commit [deliveryline-frontend/src/routes/workflows/$workflowRunId/index.test.tsx] — deferred, pre-existing/out-of-scope
