# Story 2.15: Run / Review Queue Item Component

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager (and later, developer + workflow owner — variants in Epics 3 + 4),
I want a `RunReviewQueueItem` component that represents one actionable run in a review queue with enough context to decide whether to open it now,
so that the queue surface is the entry point into a run-centered workflow per UX-DR8 — scannable, keyboard-accessible, and prioritizing **one** primary attention signal without dense overload.

## ⚠️ Read first — scope, sequencing, and what this story is NOT

**Scope in one line:** ship `RunReviewQueueItem` — the populated **row** for the `/workflows` review queue — and **wire it into the existing `QueueShell` `renderItem` seam** (story 2.20) so real rows replace the placeholder. Frontend-only.

**This story does NOT:**
- **NOT** rebuild the queue shell, its four non-row states, the `useWorkflowsList` hook, the `<Skeleton>` primitive, or the live region — those are **story 2.20 (done)**. This story **fills the `renderItem` seam** and shares `QUEUE_ROW_MIN_HEIGHT`.
- **NOT** rebuild the state badge — **story 2.16 (done)** shipped the shared `WorkflowStateBadge` (+ `StateSignifierChip`, `workflowStateMapping`) **explicitly so this story consumes it** for consistent badge styling. Import it; do not author a second badge.
- **NOT** build the **filter UI control**, sorting, or pagination — those live on the parent queue page (AC8 responsibility boundary; the shell already owns filter *state* from the URL).
- **NOT** add the Artifact Review Panel (story 2.17), Run Context Strip (done 2.16), Clarification Region, or Decision Bar. The row emits an **open-run navigation intent** and nothing more.
- **NOT** add backend changes, new endpoints, or new query-key factories. The row is **pure-presentational** — it receives its data as a prop; `QueueShell` owns the query.
- **NOT** the WCAG 2.1 AA audit / axe sweep — that is **story 2.25 (epic-2b)**. This story ships the correct semantic/keyboard/`aria-label` baseline per AC6/AC11; it does not run the full audit (no axe harness exists yet — see OQ-5).

If you find yourself building a filter control, a new route, a new query hook, a markdown renderer, or a second state badge — **stop**. This story ends at `RunReviewQueueItem` + its pure helpers (`toRunQueueRow`, `resolveQueueItemState`, `resolvePrimaryAttentionIndicator`) + the `renderItem` wiring + tests.

## 🧭 THE central reconciliation — live `WorkflowSummary` is far thinner than the epic's `RunQueueRow`

**Read this before anything else.** Epic AC1 lists a 12-field `RunQueueRow`. That list predates the real backend read model. The **live** list contract (`GET /api/v1/workflows` → `WorkflowSummary[]`, generated into `src/lib/api/schema.d.ts`, surfaced as `WorkflowSummary` from `@/lib/api/queryOptions`) carries **only 7 fields**:

```ts
WorkflowSummary = {
  workflowRunId?: string;
  currentState?: string;
  lastEventAt?: string;        // ISO-8601 UTC
  lastEventType?: string;
  escalationMarker?: boolean;
  specRejectionLoopCount?: number;
  ticketRef?: string;          // Linear ref, e.g. "DEL-1234"
}
```

**The live contract is authoritative** (same rule as story 2.16). Mirror 2.16's pattern exactly: define a rich view-model `RunQueueRow` + a pure mapper `toRunQueueRow(summary)` so the component is built to the **full epic anatomy** (Epic 3 / story 6.9 enrich the mapper later) while the **live mapper only populates what exists today**. Field-by-field:

| Epic `RunQueueRow` field | Live `WorkflowSummary` source | Mapper behavior today |
|---|---|---|
| `runId` | `workflowRunId` | direct (validate with `isValidRunId` before building a route param) |
| `linearTicketReference` | `ticketRef` | direct; absent ⇒ omit (no ticket chip) |
| `currentState` | `currentState` | direct → `WorkflowStateBadge` |
| `lastTransitionAt` | `lastEventAt` | direct — **`lastEventAt`, NOT `lastActivityTimestamp`** (parity with CLI / `RunIdentityRegion` / 2.16, Trap T2) |
| `escalationMarker` | `escalationMarker` | direct boolean |
| `specRejectionLoopCount` | `specRejectionLoopCount` | direct number — **real live trust signal** ("N spec rejections"); not in the epic list but available and meaningful |
| `summary` | **— NONE —** | `undefined`; render no summary line today. **Do not invent one.** Lands when 6.9's projection grows. |
| `primaryAttentionIndicator` | **— NONE —** | **derived client-side** by `resolvePrimaryAttentionIndicator(row)` (AC5), never a backend field |
| `currentArtifactType` | **— NONE on list —** (`latestArtifacts[]` is detail-only) | `undefined`; no artifact-type badge today |
| `assigneeHint` | **— NONE —** | `undefined`; omit |
| `blockerCount` | **— NONE —** | `undefined`/`0`; the `blocked` state + blocker indicator are **unreachable from live data today** (Epic-deferred) but the component still supports them, exercised via constructed fixtures (Trap T4) |
| `openQuestionCount` | **— NONE —** | `undefined`/`0`; same as `blockerCount` |
| `staleIndicator` | **— NONE on list —** | `undefined`. Unlike 2.16's strip, the **list summary carries no `lastActivityTimestamp`**, so staleness is **not derivable here**. The `stale` state is supported in the view-model + tested via fixtures, but the live mapper never sets it (Trap T3). |

**Consequence:** today the row visibly renders **run id + ticket chip + state badge + relative "last updated" time + escalation marker + spec-rejection-loop count**. The richer trust signals (summary, blocker/open-question counts, artifact type, assignee, stale) are **built and tested but dormant** until the backend projection grows — exactly the 2.16 posture. Make this explicit in Completion Notes; the reviewer must not expect blocker counts to render from live data.

## 🔑 Reference decoder — UX-DR8

Epic AC text cites **UX-DR8**. Map before reading: **UX-DR8 → UX spec "Run / Review Queue Item" (the queue-row component spec, ~lines 1320–1360)**: anatomy (identity, summary, stage badge, one primary attention indicator, artifact type, age, assignee, trust signals), states (default/hover/selected/unread/blocked/stale/disabled), variants (`reviewer|operator`, `compact|standard`), the **"one primary attention signal"** rule, keyboard operability, and the responsibility boundary (row owns rendering + emits open intent; parent owns filter/sort/paginate). Architecture anchors: `architecture.md:516` (TanStack Query owns server state — the row holds none); `architecture.md:1182` (workflow composites live under `features/workflows`, feedback states under `components/feedback`).

## 🔑 What already exists (compose, do NOT rebuild)

- **Shared state badge — story 2.16 (done):** `import { WorkflowStateBadge, StateSignifierChip } from '@/features/workflows/components/WorkflowStateBadge'`. `<WorkflowStateBadge currentState={row.currentState} />` maps backend state → `StateName` → `--state-*` token classes + lucide icon + label; unknown/undefined → neutral fallback; renders **"Unknown"** label when state is empty. `<StateSignifierChip stateName label title? className? testId? />` is the reusable color+icon+label chip — **use it for the escalation marker, stale marker, blocker/open-question signals** so every signal is non-color-alone (AC2/AC11). This badge was **born in 2.16 for this story** — consuming it is the whole point of the seam.
- **State mapping — story 2.16 (done):** `import { backendStateToStateName, STATE_CHIP_CLASSES, stateIconComponent } from '@/features/workflows/components/workflowStateMapping'`. Use `backendStateToStateName(row.currentState)` if you need the `StateName` directly (e.g. to drive a `StateSignifierChip` tone); never hand-code the state→tone mapping.
- **State signifiers — story 2.3 (done):** `import { type StateName, STATE_SIGNIFIERS } from '@/lib/state-signifiers'` (12-state union + icon-name/label per state).
- **Queue seam + shared row height — story 2.20 (done):** `import { QUEUE_ROW_MIN_HEIGHT } from '@/features/workflows/queueState'` (`'4.5rem'`). Apply `style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}` to the row root so real rows match the skeleton exactly (no layout shift). The `[SEAM story 2.15]` comment marks it. The shell's `renderItem?: (item: WorkflowSummary) => ReactNode` prop is the slot you fill; the shell wraps each result in `<li>` inside `<ul role="list">` and provides the list semantics — **the row returns row content, not its own `<li>`/`<ul>`**.
- **Relative time — story 2.16 (done):** `import { formatRelativeTime, formatUtcTimestamp } from '@/features/workflows/runContextFormat'`. `formatRelativeTime(iso, now?) → string | null` ("3 minutes ago"); `formatUtcTimestamp(iso) → string | null` ("2026-05-31 14:00 UTC") for the precise tooltip. Built on `Intl.RelativeTimeFormat` — **no new date dependency**.
- **`NotReported` idiom + actor-coalescing — story 2.7 (done):** `src/features/workflows/RunIdentityRegion.tsx` — reuse the `<span className="text-text-tertiary">Not reported</span>` idiom and the `presentOrNull` coalescing pattern (only relevant if you render an assignee slot; today assignee is absent).
- **Layout + tokens — stories 2.3/2.4 (done):** `Inline`, `Stack` from `@/components/layout` (`Inline` props: `gap`, `wrap`, `align`, `justify`); `cn` from `@/lib/utils`; typography `.text-body`/`.text-meta`; tokens `bg-surface`/`bg-surface-elevated`/`border-border`/`text-text-primary`/`text-text-secondary`/`text-text-tertiary`. Focus ring (story 2.4 AC6): `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus`. Line clamp: Tailwind `line-clamp-2` (used in `src/components/ui/select.tsx`).
- **Density — story 2.4 (done):** `import { densityGap, type Density } from '@/lib/density'` (`'compact' → 'gap-1'` (4px), `'standard' → 'gap-2'` (8px)).
- **Routing / navigation — story 2.5/2.22 (done):** TanStack Router typed `<Link to="/workflows/$workflowRunId" params={{ workflowRunId }}>` is the open-run intent (mirror `QueuePlaceholderRow` in `QueueShell.tsx:97–108`). `isValidRunId` from `@/lib/routing/publicId` (defensive — never build a route param from a malformed id). The route detail page is `src/routes/workflows/$workflowRunId/index.tsx`.
- **Sanitization — story 2.24 (done):** `src/lib/sanitization/` ships `SafeMarkdownRenderer` (untrusted **markdown**), `validateUrlScheme`, redaction helpers. **You almost certainly need none of it:** the row renders only short plain-text values (`ticketRef`, and—when it lands—`summary`) as **React-escaped plain text**, which is inert by default. Do NOT route plain text through `SafeMarkdownRenderer` (it's for markdown bodies, story 2.17). Never use `dangerouslySetInnerHTML` (the `no-unsanitized-html` ESLint rule forbids it).
- **NO `RunReviewQueueItem`, NO `toRunQueueRow`, NO queue-item-state helper exist yet** — confirmed by survey. They are NEW here.

## Acceptance Criteria

> Story-local ACs refine epic-2.15 ACs 1–12 against the **live contract** (see the reconciliation table above). Epic AC number in parentheses. Where the epic named a field the backend list does not expose, the reconciliation is the binding behavior.

1. **(AC1) Prop contract + view-model seam.** NEW `src/features/workflows/components/RunReviewQueueItem.tsx` exports `RunReviewQueueItem`, accepting a typed `run: RunQueueRow` prop plus presentational props (AC4). `RunQueueRow` (a NEW interface in `src/features/workflows/runQueueRow.ts`) carries: `runId?`, `linearTicketReference?`, `summary?`, `currentState?`, `currentArtifactType?`, `lastTransitionAt?`, `assigneeHint?`, `blockerCount?`, `openQuestionCount?`, `escalationMarker?`, `specRejectionLoopCount?`, `staleIndicator?`. A pure mapper `toRunQueueRow(summary: WorkflowSummary): RunQueueRow` maps the 7 live fields per the reconciliation table and leaves absent fields `undefined`. The component is **pure-presentational** — it takes its data as a prop and holds **no query/server state** (`architecture.md:516`); `QueueShell` owns the query and passes rows through `renderItem`.

2. **(AC2) Anatomy (UX-DR8).** The rendered row displays, using the `Inline` primitive: **run/ticket identity** (`linearTicketReference` + `runId` rendered together, e.g. `DEL-1234 · run_abc…`, `runId` as `<code>`; when `linearTicketReference` absent, show `runId` alone), **stage badge** via `<WorkflowStateBadge currentState={run.currentState} />` (AC2 "consistent badge styling" — the 2.16 seam), **one primary attention indicator** (AC5 — a single `<StateSignifierChip>`, never multiple competing), **artifact-type badge** (only when `currentArtifactType` present — dormant today), **age / "last updated" relative-time** via `formatRelativeTime(run.lastTransitionAt)` with a precise UTC `title`/`<time dateTime>` tooltip, **optional assignee hint** (only when present), and **secondary trust signals** demoted from the primary slot (blocker count, open-question count, stale icon, escalation icon, spec-rejection-loop count) — each as a `<StateSignifierChip>` or labeled count. A `summary` line (plain text, `line-clamp-2`) renders **only when `run.summary` is present** (dormant today).

3. **(AC3) States (UX-DR8).** The component supports: `default`, `hover` (CSS-only background tint via `hover:bg-surface-elevated` — never JS state), `selected` (prop `selected?: boolean` → accent border + background), `unread` (prop `unread?: boolean` → subtle dot indicator), `blocked` (`run.blockerCount > 0` → blocker token treatment), `stale` (`run.staleIndicator === true` → stale token treatment), `disabled` (prop `disabled?: boolean` → reduced opacity, no hover affordance, **not focusable/navigable**). A pure `resolveQueueItemState({ row, selected, unread, disabled }) → 'default' | 'selected' | 'unread' | 'blocked' | 'stale' | 'disabled'` returns **exactly one** dominant state (documented precedence: `disabled` > `blocked` > `stale` > `selected` > `unread` > `default`), surfaced as a stable `data-queue-item-state` attribute. `blocked`/`stale` are dormant from live data today (reconciliation) but fully implemented + tested via constructed `RunQueueRow` fixtures.

4. **(AC4) Variants (UX-DR8).** Props support `variant: 'reviewer' | 'operator'` (default `'reviewer'`; `operator` is E4 — it MUST compile and render a documented placeholder, e.g. a minimal row with an "Operator view — available in Epic 4" affordance) and `density: 'compact' | 'standard'` (default `'standard'`; use `densityGap(density)` for inner `Inline` spacing — 4px compact / 8px standard, story 2.4).

5. **(AC5) One primary attention signal + priority.** A pure `resolvePrimaryAttentionIndicator(row: RunQueueRow): AttentionIndicator | null` encodes the documented priority **blocker > escalation > open question > stale**: it returns the single highest-priority active signal; the others **demote to secondary trust signals** (rendered in the secondary cluster, not the primary slot). `AttentionIndicator` is a small typed union (`'blocker' | 'escalation' | 'openQuestion' | 'stale'`). A **unit test asserts the priority order** with a fixture that has several signals active simultaneously (only one is primary). Today live data can only yield `escalation` (from `escalationMarker`); the fixtures exercise the full priority ladder.

6. **(AC6) Keyboard accessibility (UX-DR8).** When not `disabled`, the row is a single keyboard-focusable control that **opens the run**: render a TanStack `<Link to="/workflows/$workflowRunId" params={{ workflowRunId: run.runId }}>` (Enter works natively) **and** add a `onKeyDown` handler so **Space** also activates it (`e.key === ' '` → `e.preventDefault()` + trigger navigation), matching the epic's "Enter and Space to open". The row exposes a composed `aria-label` including ticket/run identity + state + attention state + relative time (e.g. `"DEL-1234 run_abc, Waiting for spec approval, escalated, last updated 3 minutes ago"`); absent fields are omitted from the label, never rendered as "undefined". The focus-ring token from story 2.4 AC6 is applied. When `disabled`, the row is inert (no `<Link>`, `aria-disabled`, not in tab order). The `<li>`/list semantics come from `QueueShell` (AC8 — the row does not add its own).

7. **(AC7) Content guidelines (UX-DR8).** When `summary` is present it is truncated via `line-clamp-2` (documented limit). Hover does **NOT** reveal a tooltip-of-everything; the **only** hover-revealed secondary metadata is the precise `lastTransitionAt` timestamp (via the relative-time element's `title`). The row **never expands inline** (no accordion/disclosure).

8. **(AC8) Responsibility boundary (UX-DR8).** The component owns: identity rendering, stage badge, the one primary indicator, last-activity time, secondary trust signals — and emits an **open-run navigation intent** via the typed `<Link>` (story 2.5). It does NOT own queue-level filtering, sorting, or pagination (those live on the parent queue page), and does NOT own the `<ul>`/`<li>` list container (the shell provides it). **Wire it in:** modify `src/routes/workflows/index.tsx` so `<QueueShell renderItem={(summary) => <RunReviewQueueItem run={toRunQueueRow(summary)} />} … />` — replacing the placeholder rows with real rows. (This also closes story 2.20's deferred Clear-filters route-mount integration test, AC10/OQ-1.)

9. **(AC9) ESLint rule compliance (story 2.31 AC4).** The component lives under `src/features/workflows/components/` (NOT `src/components/ui/*`) and consumes shadcn primitives + the workflow badge — satisfying `no-workflow-domain-in-ui-primitives` (which only fires inside `components/ui/**`). No `dangerouslySetInnerHTML` (`no-unsanitized-html`). No untyped `<LoadingState>`/spinner (`no-untyped-loading-state` — the row has no loading state of its own; the shell owns loading). `eslint . --max-warnings=0` and `npm run lint:rules-test` must stay green.

10. **(AC10) Component test coverage.** Vitest + Testing Library cover: each state renders correctly (default/selected/unread/blocked/stale/disabled — `data-queue-item-state` asserted); **primary-attention-indicator priority order** (multi-signal fixture → exactly one primary); keyboard navigation (Tab focuses the row link; Enter and Space both open — assert navigation intent / link href); `aria-label` content correctness (composed string with present fields, absent fields omitted); density variant rendering (compact vs standard gap class); escalation-marker rendering when `escalationMarker === true`; `toRunQueueRow` mapping (each live field + absent-field → undefined); `resolveQueueItemState` + `resolvePrimaryAttentionIndicator` unit-tested across all branches.

11. **(AC11) WCAG 2.1 AA baseline (story 2.25 does the full audit).** Focus-visible ring from story 2.4 AC6 applies to the row; **every state/signal pairs its color token with a non-color signifier** (icon + text via `WorkflowStateBadge`/`StateSignifierChip`) — never color-alone (story 2.3 AC5). Full contrast/axe verification is deferred to story 2.25 (no axe harness exists yet — OQ-5).

12. **(AC12) Fixture-driven rendering.** Against frontend `WorkflowSummary` fixtures mirroring the **terminal states** of foundation fixture streams (story 1.23) — `spec-rejection-and-resubmit` (`run_fix_rej_001`, `currentState: 'Completed'`, `specRejectionLoopCount ≥ 1`, `ticketRef: 'DEL-9002'`) and an execution-failure terminal (`currentState: 'Failed'`, `escalationMarker: true`) — the row renders the correct identity, state badge, relative time, and the right trust-signal combination, proving the component handles diverse real-fixture signal combinations. Satisfied via **fixture-driven render assertions** (NOT vitest `toMatchSnapshot` — no snapshot harness yet, story 2.27; OQ-5). Reuse / sit alongside the 2.16 fixture under `src/test/fixtures/`.

## Tasks / Subtasks

- [x] **Task 1 — `RunQueueRow` view-model + `toRunQueueRow` mapper** (AC1, AC5)
  - [x] NEW `src/features/workflows/runQueueRow.ts` exporting the `RunQueueRow` interface (fields per AC1), the `AttentionIndicator` union, the pure `toRunQueueRow(summary: WorkflowSummary): RunQueueRow` mapper (map the 7 live fields per the reconciliation table; absent fields `undefined`; **`lastTransitionAt` from `lastEventAt`** — Trap T2), the pure `resolvePrimaryAttentionIndicator(row): AttentionIndicator | null` (priority blocker > escalation > openQuestion > stale), and the pure `resolveQueueItemState({ row, selected, unread, disabled })` (precedence per AC3).
  - [x] Unit-test the mapper (each live field; absent fields → undefined; `lastEventAt` → `lastTransitionAt`), the priority function (multi-signal fixture proves single primary + the full ladder), and the state resolver (all branches). → `src/features/workflows/__tests__/runQueueRow.test.ts` (11 tests green).

- [x] **Task 2 — `RunReviewQueueItem` component + anatomy + states** (AC1, AC2, AC3, AC4, AC6, AC7, AC11)
  - [x] NEW `src/features/workflows/components/RunReviewQueueItem.tsx`. Build the anatomy with `Inline` inside a row root carrying `style={{ minHeight: QUEUE_ROW_MIN_HEIGHT }}`, `data-queue-item-state`, the focus-ring token, and `hover:bg-surface-elevated` (non-disabled). Compose `<WorkflowStateBadge>` (2.16) for the stage badge and `<StateSignifierChip>` for primary + secondary signals (non-color-alone, AC11).
  - [x] Identity (`linearTicketReference` + `runId`-as-`<code>`); relative time via `formatRelativeTime` + UTC `title` tooltip (AC7 — the only hover reveal). `summary` line gated on presence, `line-clamp-2` plain text (React-escaped — no markdown renderer).
  - [x] States via `resolveQueueItemState`: `selected` (accent border+bg), `unread` (dot), `blocked`/`stale` (token treatments — dormant from live data, Traps T3/T4), `disabled` (opacity + inert + `aria-disabled`, no `<Link>`, out of tab order).
  - [x] Primary indicator via `resolvePrimaryAttentionIndicator` (one slot, AC5); demoted signals + `specRejectionLoopCount` + escalation render in the secondary cluster.
  - [x] `variant` (`reviewer` default; `operator` placeholder that compiles + renders an "available in Epic 4" affordance) + `density` (`densityGap`).

- [x] **Task 3 — Open-run intent + keyboard + `aria-label`** (AC6, AC8)
  - [x] Non-disabled row = `<Link to="/workflows/$workflowRunId" params={{ workflowRunId: run.runId }}>` (guard `isValidRunId(run.runId)`; if absent/malformed render inert non-link content — never build a route param from a bad id). Add `onKeyDown` so **Space** activates (preventDefault + `currentTarget.click()`); Enter is native.
  - [x] Compose `aria-label` from present fields only (identity + raw state per OQ-2 + primary attention + relative time); omit absent fields.

- [x] **Task 4 — Wire `renderItem` into the queue route** (AC8)
  - [x] Modify `src/routes/workflows/index.tsx`: pass `renderItem={(summary) => <RunReviewQueueItem run={toRunQueueRow(summary)} />}` to `<QueueShell>` (keep `filters` + `onClearFilters` + the loader). Real rows now replace `QueuePlaceholderRow`.

- [x] **Task 5 — Tests** (AC10, AC12)
  - [x] `src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx`: each state (`data-queue-item-state`); priority order; keyboard (Space opens via the log; Link `data-to`/`data-run-param` for Enter-native); `aria-label` correctness; density variants; escalation rendering; disabled inert. Mirrors `RunContextStrip.test.tsx` — mocks `@tanstack/react-router` `Link` → plain `<a>` (surfacing `to`/`params` as data attrs), `vi.spyOn(console, …)` for logging, `vi.spyOn(Date, 'now')` to pin relative time.
  - [x] Unit tests for `runQueueRow.ts` (Task 1) → `src/features/workflows/__tests__/runQueueRow.test.ts`.
  - [x] NEW frontend fixtures `src/test/fixtures/runQueue/*.ts` (`WorkflowSummary` rows for `run_fix_rej_001` Completed + an execution-failure Failed terminal) → AC12 fixture-driven render assertions.
  - [x] A thin route integration test (`src/features/workflows/__tests__/queueRoute.integration.test.tsx`) mounting `/workflows` in a REAL memory router with MSW rows, asserting real `RunReviewQueueItem` rows render and resolve `/workflows/$workflowRunId` hrefs — closes story 2.20's deferred route-mount test (OQ-1).

- [x] **Task 6 — Logging instrumentation** (cross-cutting; frontend-adapted)
  - [x] Thin surface: `console.info({ event: 'queueItem.open', state, attention, hasEscalation, rejectionLoopCount })` on open-run activation (click/Enter/Space) — **field-only**, NEVER `ticketRef`/`summary`/`runId`/free text. Pinned by a `console.info` spy + an exact-key-set assertion + negative assertions that `DEL-1234`/`run_abc123`/summary text never appear in the serialized payload.

### Review Findings

- [x] [Review][Patch] Invalid or absent run ids render as inert rows without disabled semantics [`deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx:321`]
- [x] [Review][Patch] Unread indicator is color/title-only despite the non-color-signifier rule [`deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx:340`]
- [x] [Review][Patch] Relative time freezes across same-key queue refetches and can show future ages [`deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx:314`]
- [x] [Review][Patch] Repeated Space keydown can trigger duplicate open actions [`deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx:363`]
- [x] [Review][Patch] Keyboard coverage omits explicit Tab focus and Enter activation assertions [`deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx:129`]
- [x] [Review][Patch] Route integration test duplicates queue wiring instead of mounting the production route [`deliveryline-frontend/src/features/workflows/__tests__/queueRoute.integration.test.tsx:33`]

## Dev Notes

- Relevant architecture patterns and constraints
- Source tree components to touch
- Testing standards summary

### Architecture & patterns
- **Pure-presentational row; TanStack Query owns server state** (`architecture.md:516`). The row receives `RunQueueRow` and holds no query. `QueueShell` (story 2.20) owns the `useWorkflowsList` query and passes `WorkflowSummary` rows through `renderItem`; this story maps each via `toRunQueueRow` at the wiring site. Keep the component free of hooks that fetch.
- **View-model + mapper mirrors story 2.16 exactly.** `toRunQueueRow` is the single `WorkflowSummary → RunQueueRow` seam, the place where Epic 3 / story 6.9 enrich fields. Build to the full anatomy; populate only what's live (the reconciliation table is binding).
- **Consistent badge styling is the seam's whole purpose.** Story 2.16 placed `WorkflowStateBadge` under `features/workflows/components/` specifically so this story imports it. Do not author a second badge or a second state→tone map — import `workflowStateMapping` if you need the tone for a `StateSignifierChip`.
- **Untrusted-by-default, but the live surface is tiny.** Today the only backend-influenced text rendered is `ticketRef` (and, when it lands, `summary`) — both rendered as React-escaped **plain text**, which is inert. Do not reach for `SafeMarkdownRenderer` (that's for markdown artifact bodies, story 2.17) and never use `dangerouslySetInnerHTML`. (Trap T6 lineage from 2.24.)

### Reconciliation recap (do not skip)
The live `WorkflowSummary` carries 7 fields; the epic's `RunQueueRow` named 12. `summary`/`blockerCount`/`openQuestionCount`/`currentArtifactType`/`assigneeHint`/`staleIndicator`/`primaryAttentionIndicator` are **not on the live list contract**. The component is built + tested to the full anatomy; the live mapper populates only run id, ticket ref, state, relative time (`lastEventAt`), escalation, and spec-rejection-loop count. `blocked`/`stale`/`summary` are dormant until the backend projection grows — exercised via constructed fixtures, never expected from live data in this story.

### Logging Requirements (project-wide standard, frontend-adapted)

This is a frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. Equivalent contract: structured `console.info`/`console.warn({ event, …fields })`, **field-only** (never `error.message`, ids, ticket refs, summary text, tokens, or PII), pinned by `console`-spy tests. The row's only meaningful surface is the open-run intent:

- `queueItem.open` (`info`) — `{ event, state, attention, hasEscalation, rejectionLoopCount }`. NEVER `ticketRef`/`summary`/`runId`/free text.
- A negative test asserts no content/identity field is logged.

(There is NO logger module — it's structured `console` calls, exactly as `QueueShell.tsx` does for `queue.loadError`/`queue.retry`.)

### Project Structure Notes
- NEW: `src/features/workflows/runQueueRow.ts` (+ test), `src/features/workflows/components/RunReviewQueueItem.tsx` (+ `__tests__/RunReviewQueueItem.test.tsx`), `src/test/fixtures/runQueue/*.ts`.
- MODIFIED: `src/routes/workflows/index.tsx` (pass `renderItem` to `<QueueShell>`).
- Aligns with `architecture.md:1182` (workflow composites under `features/workflows`; feedback states stay in `components/feedback`). The row sits beside `RunContextStrip`/`WorkflowStateBadge` (story 2.16) under `features/workflows/components/`.

### Testing
- **Runner:** Vitest (`jsdom`), RTL, MSW. Setup `src/test/setup.ts` (server `onUnhandledRequest:'error'`, `cleanup` per test); server `src/test/server.ts` (install handlers per test via `server.use(http.get('http://localhost/api/v1/workflows', …))`).
- **Component tests stay router-free:** `vi.mock('@tanstack/react-router', …)` replacing `Link` with a plain `<a>` that discards `to`/`params` (exact pattern in `RunContextStrip.test.tsx`). Assert the `<a>` `href`/presence for the open-intent, and fire `keyDown` Enter/Space for AC6.
- **Relative time is `Date.now()`-driven:** `vi.spyOn(Date, 'now').mockReturnValue(NOW_MS)` so `formatRelativeTime` output is deterministic (mirror 2.16's tests — pin `Date.now`, not fake timers).
- **Route integration test:** mount the real `/workflows` route via a TanStack Router memory harness + MSW rows to assert real rows render + link correctly (this is the test 2.20 deferred). Keep most tests router-free; this one exercises the wiring.
- **No axe harness yet** (story 2.27/2.25) — AC11 is satisfied by the non-color-signifier + focus-ring assertions, not an axe scan.
- **Patterns to copy:** `RunContextStrip.test.tsx` (router/Link mock, `Date.now` spy, console-spy, state-attr assertions), `WorkflowStateBadge.test.tsx` (badge/label assertions), `QueueShell.test.tsx` (MSW row handlers, `data-*-state` distinctness).

### References
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.15 (lines 1188–1209)] — the 12 epic ACs + the 2.24 dependency.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Run / Review Queue Item (UX-DR8, ~1320–1360)] — anatomy/states/variants/one-primary-signal rule.
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend (516 TanStack Query owns server state; 1182 feature/feedback layout)].
- [Source: deliveryline-frontend/src/lib/api/queryOptions.ts + src/lib/api/schema.d.ts (WorkflowSummary, lines ~455–493)] — the LIVE 7-field list contract (authoritative).
- [Source: deliveryline-frontend/src/features/workflows/components/WorkflowStateBadge.tsx + workflowStateMapping.ts] — the shared badge seam born in story 2.16 for this story.
- [Source: deliveryline-frontend/src/features/workflows/queueState.ts] — `QUEUE_ROW_MIN_HEIGHT` `[SEAM story 2.15]`.
- [Source: deliveryline-frontend/src/features/workflows/QueueShell.tsx (renderItem seam, QueuePlaceholderRow 86–119, populated list 227–239)] — the seam this story fills + the Link pattern to mirror.
- [Source: deliveryline-frontend/src/features/workflows/runContextFormat.ts] — `formatRelativeTime`/`formatUtcTimestamp` (story 2.16).
- [Source: deliveryline-frontend/src/lib/density.ts] — `densityGap`/`Density` (story 2.4).
- [Source: deliveryline-frontend/src/routes/workflows/index.tsx] — wire `renderItem` here (AC8).
- [Source: _bmad-output/implementation-artifacts/2-20-queue-shell-states-loading-empty-filtered-empty-error.md] — the renderItem seam contract + the deferred Clear-filters/route-mount test this story closes.
- [Source: _bmad-output/implementation-artifacts/2-16-run-context-strip-component.md] — the view-model+mapper pattern, badge seam, relative-time util, RTK/PowerShell gate workaround.

### Declared Traps (avoid these specific mistakes)
- **T1 — Do NOT rebuild the badge.** Import `WorkflowStateBadge`/`StateSignifierChip` from story 2.16. A second badge or a second state→tone map breaks "consistent badge styling" and duplicates the seam.
- **T2 — `lastTransitionAt` = `lastEventAt`, NOT `lastActivityTimestamp`.** Parity with CLI / `RunIdentityRegion` / 2.16. (The list summary has no `lastActivityTimestamp` anyway — see T3.)
- **T3 — `stale` is NOT derivable from the live list.** `WorkflowSummary` carries no `lastActivityTimestamp`, so (unlike the 2.16 strip) you cannot derive staleness here. The `stale` state is supported + tested via constructed fixtures; the live mapper leaves `staleIndicator` undefined. Do not invent a stale derivation from `lastEventAt`.
- **T4 — `blocked`/`summary`/counts are dormant, not removed.** `blockerCount`/`openQuestionCount`/`summary`/`currentArtifactType`/`assigneeHint` are absent from live data. Build + test them via fixtures; never fabricate them from other fields. Completion Notes must say so.
- **T5 — Do NOT own the `<li>`/`<ul>`.** `QueueShell` wraps each `renderItem` result in `<li>` inside `<ul role="list">`. The row returns row content only; adding a nested list breaks list semantics.
- **T6 — Plain text only; no markdown renderer, no `dangerouslySetInnerHTML`.** `ticketRef`/`summary` render as React-escaped plain text. `SafeMarkdownRenderer` is for artifact bodies (2.17); `dangerouslySetInnerHTML` trips `no-unsanitized-html`.
- **T7 — One primary attention signal.** `resolvePrimaryAttentionIndicator` returns exactly one (priority blocker > escalation > openQuestion > stale); the rest demote to secondary. A multi-signal fixture proves it.
- **T8 — Space must open the run.** A bare `<Link>`/`<a>` activates on Enter but NOT Space. Add the `onKeyDown` Space handler (AC6 wants both).
- **T9 — `disabled` is truly inert.** No `<Link>`, `aria-disabled`, removed from tab order, no hover affordance. Don't render a focusable-but-dead control.
- **T10 — Location + `--max-warnings=0` + no-color-alone.** Component under `features/workflows/components/` (NOT `components/ui/`); every color carries icon+label; run `prettier --write` before finishing (memory `prettier-gate-cascades-ci`). Gates via PowerShell / `rtk proxy` — RTK corrupts only the Bash tool (memory `rtk-hook-only-matches-bash`).

### Open Questions (recommendations in brackets — proceed with the recommendation unless told otherwise)
- **OQ-1 — `unread` source.** No backend "unread" concept exists. *Recommendation:* keep `unread` a **prop** (parent decides; default `false`) — it's a presentation concern the queue page may later drive from local read-state. Do not invent a backend field.
- **OQ-2 — `aria-label` state wording.** `WorkflowStateBadge` renders the **raw** `currentState` string (e.g. `WaitingForSpecApproval`). *Recommendation:* for the `aria-label`, reuse the same raw state string for consistency with the visible badge (a humanization map is a 2.25 polish; note as deferred). Don't introduce a second label source that could drift from the badge.
- **OQ-3 — `specRejectionLoopCount` presentation.** It's real live data not in the epic anatomy. *Recommendation:* render it as a secondary `<StateSignifierChip stateName="warning">` "N rejections" only when `> 0`; it is NOT a primary attention indicator (escalation already is). Keeps the row honest about a real signal without competing for the primary slot.
- **OQ-4 — `operator` variant depth.** E4 is far off. *Recommendation:* the `operator` variant renders the same identity + badge but swaps the action affordance for a disabled "Operator view — available in Epic 4" marker; keep it minimal so it compiles and the variant contract holds (party-mode finding #3 — generalize from day one without over-building).
- **OQ-5 — AC12 "Storybook-equivalent fixtures" + axe.** No Storybook, no snapshot harness (story 2.27), no axe harness (story 2.25) exist yet. *Recommendation:* satisfy AC12 via fixture-driven RTL render assertions over new `WorkflowSummary` fixtures (mirror 2.16's `specRejectAndResubmit.ts` approach); defer true visual snapshots + axe to 2.27/2.25. Note in Completion Notes so the reviewer doesn't expect `toMatchSnapshot` or an axe scan.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context) — bmad-dev-story workflow.

### Debug Log References

- Gates run via PowerShell (memory `rtk-hook-only-matches-bash` — RTK corrupts only the Bash tool; native file tools + PowerShell route around it).
- `prettier --write` applied to new/modified files before gates (memory `prettier-gate-cascades-ci`).
- Pure helpers kept in the `.ts` view-model module, not the `.tsx` component (memory `frontend-react-refresh-no-fn-exports`); the component file exports only components.

### Completion Notes List

- **Live-vs-epic reconciliation honored (the central rule).** The row is built to the full 12-field epic anatomy but the live `toRunQueueRow` mapper populates only the 7 live `WorkflowSummary` fields: run id, ticket ref, state, `lastTransitionAt` (= `lastEventAt`, Trap T2), escalation, spec-rejection-loop count. **`summary`, `blockerCount`, `openQuestionCount`, `currentArtifactType`, `assigneeHint`, `staleIndicator` are DORMANT** — built + tested via constructed fixtures, NEVER fabricated from live data. The reviewer must NOT expect blocker counts / summary / artifact-type / stale to render from live `/api/v1/workflows` data today; `blocked`/`stale`/`summary`/artifact-type chips appear only when a future projection (story 6.9 / Epic 3) supplies them.
- **`stale` is intentionally NOT derivable here (Trap T3).** Unlike the 2.16 strip, the list summary carries no `lastActivityTimestamp`, so the live mapper leaves `staleIndicator` undefined; the `stale` state is supported + tested via fixtures only.
- **Composes, does not rebuild.** Imports `WorkflowStateBadge`/`StateSignifierChip` (story 2.16, Trap T1), `QUEUE_ROW_MIN_HEIGHT` (2.20), `formatRelativeTime`/`formatUtcTimestamp` (2.16), `densityGap` (2.4). No second badge, no second state→tone map, no new date dep.
- **One primary attention signal (AC5/T7).** `resolvePrimaryAttentionIndicator` picks the single highest-priority active signal (blocker > escalation > openQuestion > stale); the rest demote to the secondary cluster. Today only `escalation` is live-reachable; fixtures exercise the full ladder. `specRejectionLoopCount` renders as a secondary `warning` chip ("N rejections", OQ-3) — never the primary slot.
- **Open-run intent + keyboard (AC6/T8/T9).** Navigable only when enabled AND `isValidRunId(run.runId)` — a typed `<Link to="/workflows/$workflowRunId">` (Enter native) plus an `onKeyDown` Space handler (`preventDefault` + `currentTarget.click()`). `disabled` (or an absent/malformed run id) renders an inert `<div>` (`aria-disabled`, no `<Link>`, out of tab order). `aria-label` composes present fields only (raw state per OQ-2), never the literal "undefined".
- **Plain text only (T6).** `ticketRef`/`summary` render as React-escaped plain text; no `SafeMarkdownRenderer`, no `dangerouslySetInnerHTML`.
- **Wiring closes 2.20's deferred test (AC8/OQ-1).** `routes/workflows/index.tsx` now passes `renderItem={(s) => <RunReviewQueueItem run={toRunQueueRow(s)} />}`; the new `queueRoute.integration.test.tsx` mounts the queue in a REAL memory router with MSW rows and asserts real rows + resolved `/workflows/$workflowRunId` hrefs.
- **AC11 baseline, not the full audit.** Every state/signal pairs a color token with an icon + text label (via `WorkflowStateBadge`/`StateSignifierChip`); focus-ring token applied. The unread dot carries a `title="Unread"`. The full contrast/axe sweep is deferred to story 2.25 (no axe harness yet — OQ-5).
- **AC12 satisfied via fixture-driven RTL render assertions** (NOT `toMatchSnapshot` — no snapshot harness yet, story 2.27; OQ-5) over new `WorkflowSummary` fixtures (`run_fix_rej_001` Completed + an execution-failure Failed terminal).
- **Operator variant (AC4/OQ-4)** renders identity + badge + a non-navigable "Operator view — available in Epic 4" placeholder so the variant contract compiles + holds without over-building.
- **Gates GREEN (PowerShell):** `tsc -b` 0 · `eslint . --max-warnings=0` 0 · `lint:rules-test` 4/4 · `vitest run` 36 files / 263 tests (+32 from this story) · `prettier --check` clean. No lockfile/dependency change → no WSL2/Linux-CI smoke needed (memory `frontend-lockfile-cross-platform` not triggered).

### File List

- NEW `deliveryline-frontend/src/features/workflows/runQueueRow.ts`
- NEW `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx`
- NEW `deliveryline-frontend/src/features/workflows/__tests__/runQueueRow.test.ts`
- NEW `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx`
- NEW `deliveryline-frontend/src/features/workflows/__tests__/queueRoute.integration.test.tsx`
- NEW `deliveryline-frontend/src/test/fixtures/runQueue/specRejectAndResubmit.ts`
- NEW `deliveryline-frontend/src/test/fixtures/runQueue/executionFailure.ts`
- MODIFIED `deliveryline-frontend/src/routes/workflows/index.tsx` (wire `renderItem` → `RunReviewQueueItem` via `toRunQueueRow`)

### Change Log

- 2026-06-01 — Story 2.15 implemented (all 6 tasks, 12 ACs). NEW `RunReviewQueueItem` row + `runQueueRow` view-model/mapper/resolvers filling story 2.20's `renderItem` seam; wired into `/workflows`. Status `in-progress → review`.
