# Story 2.20 — Queue Shell States: Edge-Case & Boundary Review

Reviewer: QA-minded edge-case walk. Every claim below was verified against the actual
working-tree source — implementation files, all four test files, the route change, and
the supporting contracts (`queryOptions.ts`, `problemDetails.ts`,
`validateUrlScheme.ts`, `ErrorState.tsx`, `EmptyState.tsx`). File:line references are to
those files as read.

---

## A. Branch / boundary walk of the NEW implementation code

### A1. `resolveQueueState` (queueState.ts:39-55) — the five-way branch — PASS

Precedence: `isError` → `isPending` → `count===0 ? (filtersActive?filtered-empty:empty)`
→ `populated`.

| Input | Resolved | Correct? |
|---|---|---|
| isError=true (+ pending or rows) | `error` | yes — error wins (tested: queueState.test.ts:16-19) |
| isError=false, isPending=true | `loading` | yes (tested:21-23) |
| success, count=0, filters off | `empty` | yes (tested:25-27) |
| success, count=0, filters on | `filtered-empty` | yes, Trap T6 (tested:29-31) |
| success, count>0 | `populated` | yes (tested:33-36) |

Pure function is exhaustive, deterministic, and unit-tested on all branches incl. the
`isError`-over-`isPending` precedence and the `isError`+rows case. Strongest part of the
change. **PASS.**

### A2. `hasActiveFilters` (QueueShell.tsx:53-57) — value-based, not key-based — Low

`Object.values(filters).some(v => v!==undefined && v!==null && v!=='')`.
- `{}`→false; `{state:undefined}`→false; `{state:''}`→false; `{state:'x'}`→true. All
  correct, and stricter than the spec's documented `Object.keys(search).length>0`.
- In production the route's `validateSearch` (index.tsx:23-24) already collapses
  empty `state` to `{}`, so key-count and value-check agree. The divergence only surfaces
  if `QueueShell` is rendered with a hand-built `{state:''}` (a future caller).
- **Boundary untested:** `{state:''}` / `{state:undefined}` resolving to `empty` (not
  `filtered-empty`). The AC4 test (QueueShell.test.tsx:118) passes `{state:'Executing'}`;
  no test exercises the empty-string-filter boundary. Suggest a `hasActiveFilters`/
  `resolveQueueState` case for `{state:''}`. **Low.**

### A3. `query.data ?? []` (QueueShell.tsx:144) — null/undefined data — PASS

During `isPending` data is `undefined`→`items=[]`→count 0, but `isPending` is checked
before count, so loading never falls into the empty branch. Verified against
`listQueryOptions` (queryOptions.ts:55-61, queryFn returns `Promise<WorkflowSummary[]>`,
non-null) — the `?? []` is belt-and-suspenders, not load-bearing for a 200. PASS.

### A4. `QueuePlaceholderRow` (QueueShell.tsx:86-119) — malformed run ids — Medium (test gap)

The malformed-run-id placeholder boundary:
- `runId===undefined` → inert `<div>`, body text `'Run'` (line 91). No link. Correct.
- `runId` present but fails `isValidRunId` → inert `<div>`, shows the raw id as **React-
  escaped** text (line 91). No `<Link>` → no route-param injection. Correct defensive guard.
- `runId` valid → `<Link to params={{workflowRunId:runId}}>`. Correct.
- `meta` join (line 88): `[currentState,lastEventAt].filter(Boolean).join(' · ')` — both
  absent → no meta span; one absent → no dangling separator. Correct.

**TESTING GAP (Medium):** the placeholder test (QueueShell.test.tsx:186-201) renders ONE row
with a **valid** id (`run_abcd0001`) and asserts one `queue-placeholder-row`. The defensive
**invalid-id → inert `<div>` (no anchor)** branch and the **undefined-id → "Run"** branch are
never exercised. This is the security-flavored half of the row (never build a route param
from a malformed id). Suggest a test rendering `populated` with a malformed `workflowRunId`,
asserting the row exists as text with NO `<a>`/role=link.

### A5. `GettingStartedAction` (QueueShell.tsx:122-140) — VITE_DOCS_URL set vs unset — High (test gap)

The disabled-vs-enabled docs CTA boundary:
- unset / non-string → `href=undefined` → disabled `<Button disabled>` (135-139). Correct.
- empty string `VITE_DOCS_URL=''` → `validateUrlScheme('')` returns `{ok:false}` (verified
  sanitization/policy.ts:65-68 — empty-after-trim → `ok:false`) → disabled. Correct — no dead
  `<a href="">`. `validateUrlScheme` also rejects `javascript:`, protocol-relative `//`, and
  backslash UNC forms (policy.ts:69-93).
- set + bad scheme (`javascript:`) → `validateUrlScheme` rejects → disabled. Correct.
- set + valid → real `<a href target="_blank" rel="noreferrer">` (126-132). `rel="noreferrer"`
  present (good). Correct.

**TESTING GAP (High):** NO test renders the empty state's CTA at all, and none toggles
`VITE_DOCS_URL`. Vitest reads `import.meta.env` at load → the test env almost certainly has
`VITE_DOCS_URL` unset, so only the **disabled** branch is reachable, and even that is
unasserted (the AC3 test, QueueShell.test.tsx:100-111, checks the EmptyState variant +
announcer but never the CTA). The entire **enabled real-link** path (AC3's happy path) is
unverified. Suggest two tests: (a) unset → CTA `disabled`, no `href`; (b)
`vi.stubEnv('VITE_DOCS_URL','https://x.dev/docs')` (re-import the module so the env read
refreshes) → enabled `<a href>` carrying `rel="noreferrer"`.

### A6. Pluralization (`announcementFor`, QueueShell.tsx:74) — zero/one/many — PASS (1-run tested)

`${count} ${count===1?'run':'runs'}`. count===1→"1 run" (tested QueueShell.test.tsx:181-183,
"Review queue loaded: 1 run available"); count>1→"runs"; count===0 unreachable for
`populated`. The singular off-by-one IS covered. **Plural (≥2 rows) is NOT** — no test renders
2+ rows. Low: add a 2-row assertion for "2 runs".

### A7. error-log `useEffect` deps (QueueShell.tsx:155-165) — Medium

`useEffect(()=>{ if(state!=='error')return; console.warn({event:'queue.loadError', state,
code: isProblemDetailsError(query.error)?query.error.code:'transport', filtersActive}); },
[state, query.error, filtersActive])`.
- All referenced values are in deps; `console.warn`/`isProblemDetailsError` are stable module
  refs → no stale closure, no exhaustive-deps violation. PASS on correctness.
- **Re-fire on repeated failure:** a failing Retry usually yields a new `query.error`
  reference → effect re-logs (desirable). If a client reuses the error object the second
  failure would NOT log. Client-dependent; untested.
- **StrictMode double-invoke (Low):** in dev the effect fires twice per error entry. The spy
  assertion (QueueShell.test.tsx:154-156) uses `toHaveBeenCalledWith(objectContaining(...))`,
  which is count-agnostic, so it's robust to this. Good.
- **Forbidden-field contract NOT asserted (Medium):** the test only positively asserts
  `{event:'queue.loadError', code:'INTERNAL_ERROR'}`. It does NOT assert the **absence** of
  `error.message`/status/stack in the logged object — the whole security point of Task 8 /
  Trap T3 for the log surface. A refactor could add `message: error.message` and the test
  would still pass. Suggest `not.objectContaining({message: expect.anything()})` or an exact
  3-key assertion.

### A8. Live-region transitions (QueueShell.tsx:182-184) — Medium / Low

Announcer text is derived from `state` each render, so it changes on every transition with no
separate effect to forget. Good design.
- **Double announcement on error (Medium, accepted per OQ-3):** in the error state BOTH the
  shell announcer (`role="status"` + `aria-live="polite"`, QueueShell.tsx:182) AND
  `<ErrorState urgency="passive">` are live regions. Verified: ErrorState composes a shadcn
  `<Alert>` which is **`role="alert"`** and sets `aria-live="polite"` for `passive`
  (ErrorState.tsx:235-239). So the two regions are NOT identical roles — one is `status`, one
  is `alert` — and both announce on the error transition. Wording differs (T10 honored: shell
  says "Failed to load the review queue — retry available"; ErrorState shows the
  `queueErrorMessage` body, default title "Couldn't load this"), so not verbatim-duplicate.
  A screen reader still hears two announcements. Spec explicitly accepted this (OQ-3,
  reconciliation → 2.25). Flagged so the reviewer knows two live regions coexist on error.
- **T10 non-identical-wording unasserted (Low):** the only machine-checkable part of T10 (shell
  text ≠ ErrorState text) has no test. Suggest asserting they differ.
- **"Updates PER TRANSITION" NOT directly tested (Medium):** AC8/AC10 require the announcer to
  *update across a transition*. Every QueueShell test renders one terminal state and reads the
  announcer once (lines 92, 110, 125-127, 181-183). None starts in `loading` and re-reads the
  announcer after it flips to assert the **text changed** on one live region. Suggest one test
  asserting announcer text before AND after a single transition.

### A9. `useWorkflowsList` (useWorkflowsList.ts:18-20) — PASS

Thin `useQuery(listQueryOptions(filters))`, default `{}`, no inline key (T5 honored). Tested
for success shape and `isError` (useWorkflowsList.test.tsx:24-61). PASS.

### A10. `Skeleton` (skeleton.tsx:18-25) — Low

`aria-hidden` is hard-coded (line 21) BEFORE `{...props}` (line 24) → a caller could override
`aria-hidden`. In `QueueShell` only `className`/`style` are passed, so fine. `animate-pulse`
not `animate-spin` (T2/T9). Token `bg-surface-elevated` (T9). The no-spinner / pulse-present
contract is tested (QueueShell.test.tsx:90-91). PASS.

---

## B. Higher-order boundaries the four-way model under-handles

### B1. isError WITH stale cached data (background-refetch failure) — High

`resolveQueueState` returns `error` whenever `isError` is true, **regardless of whether
`query.data` still holds the previously-loaded list** (queueState.ts:45-46). TanStack Query
keeps the last successful `data` on a background-refetch error. Consequence: a user viewing a
populated queue whose refetch fails sees the **whole list replaced by a full-page ErrorState**,
losing rows they could still use. architecture.md:520 lists a distinct *stale data* state this
story does not implement.
- AC1 defines `error` purely as `isError`, so this is **spec-compliant** — but it's a real UX
  boundary worth an explicit decision. First-load→error and Retry→error are safe (no prior
  data); the dangerous path is success→refetch→error, which won't fully manifest until 2.15
  adds real refetch triggers.
- **Test note:** queueState.test.ts:18 tests `isError+count:5`→`error` but frames it as "error
  wins", not as the stale-data tradeoff. Suggest documenting this as deferred (point at 2.25 /
  a stale-data story) and keeping the explicit test so a future change is intentional.

### B2. Loader↔hook query-key parity — Medium (test gap)

Route loader `ensureQueryData(listQueryOptions(deps))` with `deps=({search})=>search`
(index.tsx:25-26); hook `listQueryOptions(filters)` with `filters=Route.useSearch()`
(index.tsx:31-33). Both feed the SAME `listQueryOptions`→`workflowKeys.list`, so keys hash
equal and the "flash-free off one cache entry" claim holds by construction. **But it's only
type-checked, never executed:** OQ-1's route-mount test was deferred (story line 228), so the
`validateSearch`→`filtersActive`→`filtered-empty` wiring and loader/hook dedup are not
exercised end-to-end. See C6.

### B3. `validateSearch` boundaries (index.tsx:23-24) — Low

`typeof state==='string' && state.length>0 ? {state} : {}`.
- `?state=` (empty) → `{}` → `empty`. Matches value-based `hasActiveFilters`. Good.
- `?state=%20` (whitespace) → length>0 → `{state:' '}` → `filtersActive=true` → would query
  `state=' '` and show `filtered-empty`. Whitespace-only treated as active. Low; a future
  filter control would normally prevent this.
- Unknown keys (`?foo=bar`) → dropped (only `state` read). Correct.
- Duplicate `?state=a&state=b` → if the parser yields an array, `typeof!=='string'`→`{}`. Safe
  default. Low.

---

## C. TEST-SUITE coverage vs AC10 (confirmed against the real test files)

Four test files, 339 lines: QueueShell.test.tsx (6 tests), queueState.test.ts (5),
queueErrorMessage.test.ts (3), useWorkflowsList.test.tsx (2). Mapping AC10's required items:

| AC10 requirement | Status | Evidence / gap |
|---|---|---|
| Each of four states renders | COVERED | loading/empty/filtered-empty/error/populated each asserted via `data-queue-state` (QueueShell.test.tsx:87,106,122,148,178) |
| loading → empty transition | PARTIAL | the loading test (73-98) ends by `waitFor` empty, so this ONE transition is real |
| loading → populated | GAP | populated tests (164-201) don't assert the loading→populated flip on one live region |
| loading → error | GAP | error test (136-162) doesn't assert the loading phase first |
| success → filtered-empty | PARTIAL/STATIC | AC4 test (113-134) renders directly with `filters={state:'Executing'}` + empty response → static filtered-empty, not a user-applies-filter transition |
| Retry → second fetch fires | COVERED (good) | `calls` 1→2 via real MSW counter (137-160). Solid. |
| Clear filters resets search AND re-queries | GAP (re-query half) | only `onClearFilters` callback fired + logged (129-133). The actual `navigate({search:{}})`+re-query is in the route (index.tsx:33) and NOT exercised. See C6. |
| Live region updates per transition | GAP | announcer asserted once per terminal render; never re-read across a transition (see A8) |
| queueErrorMessage each code + transport | COVERED (good) | INTERNAL_ERROR, VALIDATION_ERROR, unmapped→generic, transport+no-leak (queueErrorMessage.test.ts:16-37) — incl. the `?? UNKNOWN_CODE_MESSAGE` fallback |
| resolveQueueState all branches | COVERED (good) | queueState.test.ts:15-37 |
| Logging assertions (Task 8) | PARTIAL | `queue.loadError` (code), `queue.retry`, `queue.clearFilters` pinned (154-156,161,131-133). The **forbidden-field** negative (no `error.message`) is NOT asserted (see A7). |

### C6. Clear-filters re-query path unexercised end-to-end — High

The biggest gap vs AC10's literal wording ("Clear filters resets search **and re-queries**").
`QueueShell` only calls `onClearFilters?.()` (QueueShell.tsx:174); the real `navigate({search:
{}})` is in the route (index.tsx:33). With OQ-1's memory-router test deferred, nothing proves
the navigation resets the URL and triggers a fresh unfiltered query. Suggest one thin TanStack
Router memory-history test: deep-link `/workflows?state=x` → assert `filtered-empty` → click
Clear filters → assert URL becomes `/workflows` AND a new MSW hit for the unfiltered key fires.

### C7. AC7 side-by-side distinctness / mutual exclusion — Medium

AC7 wants all four non-row states rendered and asserted **mutually exclusive** (one
`data-queue-state`, others absent). Mutual exclusion IS structural (each block is
`state==='x' ? (...) : null` off one resolved value, QueueShell.tsx:186-235). But no test
asserts the negative — e.g. that in the `error` state `queue-loading`/`queue-list`/`empty-state`
testids are ALL absent. Each test only checks the expected element is present. Suggest a
distinctness test asserting the other states' markers are absent.

---

## D. Net assessment

The **implementation is clean and correct** — no logic bug found in the resolver, the render
branching, the error-copy mapping, the URL guard, or the row-param defense. All declared traps
(T1–T10) are genuinely honored in code and several are pinned by tests (T2 no-spinner:90-91;
T3 no-`500`-leak:153; T5 factory key; T6 distinct variant:124; T8 error log:154-156).
`validateUrlScheme('')`→`{ok:false}` closes the empty-docs-URL boundary; `ErrorState` passive→
polite is confirmed (ErrorState.tsx:75-76).

Risk is concentrated in **boundary test coverage**, not the code. The three High items:
1. **C6** — Clear-filters *re-query* path never executed (route wiring untested; OQ-1
   deferred). AC10 literally requires "resets search and re-queries".
2. **A5** — the enabled docs-CTA path (VITE_DOCS_URL set → real `<a rel="noreferrer">`) has
   zero coverage; only the disabled branch is reachable in the test env, and even it is
   unasserted. AC3's happy path is unverified.
3. **B1** — `isError` replaces a populated list wholesale (no stale-data handling); the
   decision is spec-compliant but undocumented as a deliberate tradeoff.

Medium items worth fixing before sign-off: A4 (malformed-id no-link branch untested), A7
(forbidden-field log assertion missing), A8 (per-transition announcer update not asserted), C7
(mutual-exclusion negative not asserted), B2 (loader/hook key parity type-only), A8 (double
polite live region — accepted per OQ-3 but flagged).

## E. Severity tally

- Critical: 0
- High: 3 — C6 clear-filters re-query untested; A5 enabled docs-CTA untested; B1 stale-data-on-error undocumented/untested
- Medium: 6 — A4 malformed-id branch; A7 forbidden-field log assertion; A8 per-transition announcer update; A8 two live regions on error (status+alert); C7 mutual-exclusion negative; B2 loader/hook key parity
- Low: 6 — A2 value-vs-key filter drift; A6 plural ≥2 untested; A10 skeleton aria override; B3 whitespace filter; T10 wording-differ unasserted; A8/OQ-3 wording
