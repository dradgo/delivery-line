# Story 2.20 — Queue Shell States: Acceptance Audit

Scope reviewed (the real implementation lives under `src/features/workflows/`, NOT in the route file):
- `deliveryline-frontend/src/features/workflows/QueueShell.tsx` (new, 238 lines — the actual component)
- `deliveryline-frontend/src/features/workflows/queueState.ts` (new — `resolveQueueState`, `QUEUE_ROW_MIN_HEIGHT`, `QUEUE_SKELETON_ROW_COUNT`)
- `deliveryline-frontend/src/features/workflows/queueErrorMessage.ts` (new)
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowsList.ts` (new)
- `deliveryline-frontend/src/components/ui/skeleton.tsx` (new)
- `deliveryline-frontend/src/routes/workflows/index.tsx` (modified — thin wrapper passing `filters`/`onClearFilters`)
- `deliveryline-frontend/src/components/feedback/states/{EmptyState,ErrorState}.tsx` (story-2.22 primitives, composed)
- Tests: `__tests__/QueueShell.test.tsx`, `__tests__/queueState.test.ts`, `__tests__/queueErrorMessage.test.ts`, `hooks/useWorkflowsList.test.tsx`

Environment fact (load-bearing for AC7/T1): `deliveryline-frontend` uses Tailwind v4 (`@import 'tailwindcss'` in `globals.css`, confirmed in `_context.md` line 27). Tailwind v4 preflight resets `ul { list-style: none }`, which causes WebKit/VoiceOver to drop the implicit `list` role unless `role="list"` is set explicitly.

---

## Per-AC verdicts

### AC1 — State determination — PASS
`resolveQueueState` (`queueState.ts:39-55`) is a pure function returning exactly one of `error|loading|filtered-empty|empty|populated` from `isError/isPending/count/filtersActive`. `QueueShell` (`QueueShell.tsx:146-151`) computes `state` once and switches the JSX on it (`:186/199/208/216/227`) — no overlapping `&&` chains. Unit-tested in `queueState.test.ts`. Satisfied.

### AC2 — Loading = skeleton rows, never a spinner — PASS
Loading branch (`QueueShell.tsx:186-197`) renders `QUEUE_SKELETON_ROW_COUNT` (=4) `<Skeleton>` rows at `QUEUE_ROW_MIN_HEIGHT`. `<Skeleton>` (`skeleton.tsx:18-26`) uses `animate-pulse` (never `animate-spin`). Container has `aria-busy="true"` and `aria-hidden` on the shapes. `<LoadingState>` is deliberately not used. Test `QueueShell.test.tsx:73-98` asserts `.animate-spin` is null and `.animate-pulse` present. Satisfied.

### AC3 — Empty state — PASS
Empty branch (`QueueShell.tsx:208-214`) renders `<EmptyState variant="queue">` with PM-persona override message "No specifications awaiting review. New runs from Linear appear here once submitted via the CLI." and a docs CTA (`GettingStartedAction`, `:122-140`) gated behind a validated `VITE_DOCS_URL`, falling back to a disabled button (never a dead link). Title defaults to "No runs yet" from the primitive. Copy and behavior match the AC. Satisfied.

### AC4 — Filtered-empty state — PASS
Filtered-empty branch (`QueueShell.tsx:216-225`) renders `<EmptyState variant="filtered">` (distinct icon `ListFilter`, distinct default copy "No matching runs / No runs match the current filters…") with a **Clear filters** button wired to `handleClearFilters` → `onClearFilters` (`:172-175`). The route (`routes/workflows/index.tsx:33`) implements `onClearFilters` as `navigate({ search: {} })`, resetting all filters in the URL. Distinct from `empty` in variant, copy, and action. Test `QueueShell.test.tsx:113-134`. Satisfied.

### AC5 — Error state — PASS
Error branch (`QueueShell.tsx:199-206`) renders `<ErrorState variant="failedRetrieval" urgency="passive">` with `message = queueErrorMessage(error) + " If this keeps happening, report it to your administrator."` (the AC5 "report if it persists" pointer), and `nextAction={{ kind: 'Retry', onRetry: handleRetry }}` → `query.refetch()`. `queueErrorMessage` (`queueErrorMessage.ts`) maps the Problem-Details `code` to fixed copy and NEVER echoes `error.message`/status/stack. The Retry button label rendered by `<ErrorState>` is "Try again" (`ErrorState.tsx:127`). Tested in `QueueShell.test.tsx:136-162` (asserts no "500" leaks, refetch fires) and `queueErrorMessage.test.ts`. Satisfied.

### AC6 — Always an action — PASS
Error always has the type-required `nextAction` (Retry). Filtered-empty always renders Clear filters. Empty always renders the docs CTA (real link or disabled placeholder). No silent no-action path. Satisfied.

### AC7 — Populated list (`<ul role="list">`) — PARTIAL / FAIL on the mandated attribute
The populated branch (`QueueShell.tsx:227-235`) renders a semantic `<ul>` with one `<li key=…>` per item and the row-delegation seam (`renderItem` or `QueuePlaceholderRow`). It carries `data-testid="queue-list"` and `data-queue-state="populated"` on the root, and rows are keyboard-operable.

**Defect:** the `<ul>` is `<ul className="flex flex-col gap-2" data-testid="queue-list">` — it has **NO `role="list"`** (confirmed: a Grep for `role="list"` in `QueueShell.tsx` returns zero matches). AC8 explicitly says "The populated list uses `<ul role="list">`/`<li>`" and Trap **T1 is specifically about exactly this** under Tailwind v4 preflight. Because this project is Tailwind v4 (preflight sets `ul { list-style: none }`), Safari/WebKit + VoiceOver will drop the implicit list role and the list will not be announced as a list / item count — defeating the accessibility intent. The structural `<ul>/<li>` is present, so it is not a total fail, but the explicitly-mandated `role="list"` is absent and its absence has real a11y impact in this exact stack.
**Severity: High** (mandated attribute missing; concrete VoiceOver/WebKit regression on the project's actual CSS framework; also no test guards it).

### AC8 — Live-region announcements — PASS
`QueueShell.tsx:182-184` renders a single `sr-only` `role="status" aria-live="polite"` region whose text is `announcementFor(state, count)`. The documented AC8 strings vs `announcementFor` (`:63-78`):
- loading → "Loading the review queue" ✅ (matches)
- empty → "Review queue is empty" ✅
- filtered-empty → "No runs match the current filters" ✅
- error → "Failed to load the review queue — retry available" ✅
- populated → "Review queue loaded: {n} run(s) available" ✅ (AC documents "{n} runs available"; code pluralizes run/runs — a benign improvement, the singular "1 run" is asserted in the test)

All five documented strings are emitted exactly (the populated copy is the documented template with grammatically-correct pluralization). T10 honored: shell wording deliberately differs from `<ErrorState>`'s own polite message so screen readers don't double-read verbatim. Satisfied.

### AC9 — Responsibility boundary — PASS
`QueueShell` owns only: state resolution, the four states, `refetch()` on Retry, reset-search on Clear filters, and the live region. It does NOT import a filter control (the route hands `filters` in via props) and does NOT import `RunReviewQueueItem` — rows go through the `renderItem` seam with `QueuePlaceholderRow` default (`:46-49, 86-119, 227-235`). `[SEAM story 2.15]` comments and shared `QUEUE_ROW_MIN_HEIGHT` are present. Satisfied.

### AC10 — Test coverage — MOSTLY PASS (one transition gap)
All four required test files exist and are real (not stubs):
- `queueState.test.ts` — all five branches incl. error-precedence (AC1) ✅
- `queueErrorMessage.test.ts` — known codes, unknown-code fallback, transport, no-leak (AC5) ✅
- `useWorkflowsList.test.tsx` — success + isError (AC1) ✅
- `QueueShell.test.tsx` — loading skeleton/no-spinner, empty, filtered-empty + Clear filters + log, error copy/no-leak/log/Retry-refetch, populated seam + placeholder, live-region text per state ✅

**Gaps vs AC10's explicit list:**
1. AC10 says cover "the four states **distinguishable side-by-side** … each has a distinct `data-queue-state`, a unique accessible label, and is mutually exclusive (only one present at a time)" (this is the AC7 snapshot test). No single test renders/asserts mutual exclusivity across states — each test checks one state's `data-queue-state` in isolation. **Severity: Low/Medium.**
2. AC10 lists transition tests; the suite checks initial-paint-per-state and `loading→empty` (settle in the AC2 test) but does not assert `loading→populated` / `loading→error` / `success→filtered-empty` as explicit transitions. The end states are covered, the transition assertions are thin. **Severity: Low.**
3. No test asserts `role="list"` on the populated `<ul>` — consistent with the AC7 defect above; a test would have caught it. **Severity: Low (but it is the missing guard for the High AC7 issue).**
Core coverage is solid; treat as Partial only on the AC7 distinctness snapshot.

---

## Trap audit (T1–T10)

| Trap | Verdict | Evidence |
|------|---------|----------|
| T1 — No row component import; `renderItem` seam + placeholder; shared `QUEUE_ROW_MIN_HEIGHT` | **HONORED** | `QueueShell.tsx:46-49, 86-119, 227-235`; no `RunReviewQueueItem` import. (Note: T1 in the **story's** Declared-Traps list is the "no row import" trap, which is honored. The task's separate framing of T1 as the `role="list"` requirement is the AC7 defect below.) |
| T1 (task framing) — `<ul role="list">` for Tailwind v4 preflight | **VIOLATED** | `QueueShell.tsx:228` `<ul>` has no `role="list"`; project is Tailwind v4. See AC7. Severity High. |
| T2 — Loading = skeleton, not spinner | **HONORED** | `skeleton.tsx` `animate-pulse`; `<LoadingState>` unused; test asserts no `.animate-spin`. |
| T3 — Error text from `code`, never `message`/status/stack | **HONORED** | `queueErrorMessage.ts`; test asserts no "500"/"ECONNREFUSED" leak. |
| T4 — `<ErrorState>` needs the breadcrumb provider | **HONORED (via mock)** | Test mocks `useReturnToRunContext` (`QueueShell.test.tsx:21-24`); in-app the provider is mounted in `App.tsx`. |
| T5 — Query keys via the factory, no inline key | **HONORED** | `useWorkflowsList.ts` routes through `listQueryOptions`. |
| T6 — `filtered-empty` ≠ `empty` (distinct variant/copy/action) | **HONORED** | `EmptyState variant="filtered"` + Clear filters vs `variant="queue"` + docs CTA; `resolveQueueState` splits on `filtersActive`. |
| T7 — Filters in the URL, not React state | **HONORED** | route `validateSearch`/`loaderDeps`; `onClearFilters` = `navigate({ search: {} })`; hook holds no state. |
| T8 — One state at a time | **HONORED** | single `state` value drives mutually-exclusive `state === …` branches; `resolveQueueState` returns one literal. |
| T9 — `<ErrorState>` consumes `useReturnToRunContext` internally; consumer passes no back callback | **HONORED** | Retry-only `nextAction`; no back callback passed. |
| T10 — Don't double-announce | **HONORED** | shell announcer wording differs from `<ErrorState>`'s polite message. |
| (Story Declared-Trap) T9 "Skeleton token must exist" | **HONORED** | `bg-surface-elevated` used in `skeleton.tsx`, present in globals.css per dev notes. |

Net: the only violated trap is the `role="list"` requirement (the one the task specifically asked to scrutinize). All ten story-declared traps are honored.

---

## Scope creep / extras (no AC, but justified)
- Task-8 logging (`queue.loadError` / `queue.retry` / `queue.clearFilters`, `QueueShell.tsx:155-175`) is cross-cutting, not an AC1–10 item, but is a project-wide required task and is field-only (no raw message/PII). Not creep.
- `QueuePlaceholderRow` defensively gates the deep link behind `isValidRunId` (`:97`) — reasonable hardening, not creep.
- No stray components, routes, filter controls, or row anatomy were added. Clean.

## Claimed-vs-actual tests
- All four test files claimed in the File List exist and exercise the code (verified by reading them) — no phantom/empty tests. The only test-coverage shortfalls are the missing side-by-side distinctness snapshot (AC7/AC10) and the missing `role="list"` assertion.

## Severity-ranked gaps
1. **High** — AC7 / task-T1: populated `<ul>` lacks `role="list"`; under this project's Tailwind v4 preflight (`ul{list-style:none}`) WebKit/VoiceOver drops the implicit list role. Mandated by AC8 + the trap. One-line fix: add `role="list"` to `QueueShell.tsx:228`.
2. **Low/Medium** — AC10/AC7: no single test asserts all states are mutually exclusive with distinct `data-queue-state` + unique label (the side-by-side distinctness snapshot the AC calls out).
3. **Low** — AC10: transition assertions (`loading→populated/error`, `success→filtered-empty`) are implicit rather than explicit; end states are covered.

## Tally
- PASS: AC1, AC2, AC3, AC4, AC5, AC6, AC8, AC9 (8)
- PARTIAL: AC7 (structure present, mandated `role="list"` missing), AC10 (core solid, distinctness/transition assertions thin) (2)
- FAIL: none outright
- Traps: 10/10 story-declared traps honored; the task-flagged `role="list"` requirement is VIOLATED (drives the AC7 High finding).
