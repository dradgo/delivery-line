# Blind-spot review — Story 2.20 (Queue Shell States)

Reviewer: skeptical senior engineer. Every finding below is verified against the
actual source in scope plus the supporting contracts (`problemDetails.ts`,
`queryOptions.ts`, `workflowKeys.ts`, `sanitization/policy.ts`, `useWorkflowsList.ts`,
and `QueueShell.test.tsx`), all of which I was able to read.

Files reviewed:
- `src/features/workflows/QueueShell.tsx`
- `src/features/workflows/queueState.ts`
- `src/features/workflows/queueErrorMessage.ts`
- `src/features/workflows/hooks/useWorkflowsList.ts`
- `src/components/ui/skeleton.tsx`
- `src/routes/workflows/index.tsx`
- `src/features/workflows/__tests__/QueueShell.test.tsx`
- contracts: `workflowKeys.ts`, `queryOptions.ts`, `problemDetails.ts`, `sanitization/policy.ts`

---

## HIGH

### H1 — Retry gives no in-flight or repeat-failure feedback; a failed Retry is indistinguishable from a dead button
**File:** `src/features/workflows/QueueShell.tsx:142-151, 167-170, 199-206`

`resolveQueueState` branches on `query.isPending` for the loading state and
`query.isError` for the error state. In TanStack Query v5, `isPending` means
"no data yet AND the very first load is in flight" — it is NOT true during a manual
`refetch()` after an error. After the first failure, `query.data` is `undefined`,
`status === 'error'`, `isError === true`, `isPending === false`.

When the user clicks Retry (`handleRetry` → `void query.refetch()`):
- `isFetching` flips to `true`, but `isError` stays `true` and `isPending` stays
  `false` for the whole retry-in-flight window. So `resolveQueueState` still returns
  `'error'`. The skeleton never shows on retry, and there is **zero visible signal
  that the retry is running.**
- If the retry fails again, the rendered output is byte-identical to before the
  click. A user (and a manual tester) cannot tell whether the button did anything —
  the only evidence is `console.info({ event: 'queue.retry' })`, which users can't see.

This is a genuine TanStack state-semantics gap, not a style nit. The test
(`QueueShell.test.tsx:159-160`) only asserts a *second network call* fires — it
never asserts any UI feedback — so this defect passes the suite.

**Severity:** High — the primary (and per AC6 the only) recovery affordance for the
error state appears broken under the common case of a slow or repeatedly-failing
backend.

**Fix:** drive a busy state off `query.isFetching`: disable the Retry button and/or
swap its label to "Retrying…" while `isFetching && isError`. Optionally honor OQ-4
(`urgency="active"` on a failed retry so focus/announce fires). At minimum disable
the button during `isFetching` to prevent stacked refetches from rapid clicks.

---

## MEDIUM

### M1 — Cold-load skeleton may be silent to assistive tech: the only on-screen content is `aria-hidden`, and a polite live region is generally not announced for text present at first mount
**File:** `src/features/workflows/QueueShell.tsx:182-197` + `skeleton.tsx:21`

On a fresh navigation the queue mounts directly in `loading`. The skeleton container
is `aria-hidden` (line 190) and each `<Skeleton>` is itself `aria-hidden`
(`skeleton.tsx:21`), so the only visible content is hidden from AT by design. The
textual fallback is the `role="status"`/`aria-live="polite"` node (line 182) showing
"Loading the review queue". But polite live regions announce *subsequent* DOM
mutations, not text that already exists when the region is first inserted into the
accessibility tree. On the most common entry (cold page load) the loading text is
present at first paint, so many screen readers will not announce it. Later transitions
(loading→empty/error/populated) are mutations and *will* announce.

Net: the cold-load state can be entirely silent to AT even though everything visible
is `aria-hidden`. AC8 explicitly wants loading "announced when it materially affects
interaction".

**Severity:** Medium — baseline AC8 gap on the primary entry path. Full a11y is
deferred to 2.25, but this is the AC8 baseline this story is meant to ship.

**Fix:** give the skeleton container an `aria-label="Loading the review queue"` and
`role="status"` (drop `aria-hidden` on the container, keep it on the shapes), OR
document this as a known 2.25 limitation.

### M2 — `filtersActive` is defined twice with two different predicates (route vs shell)
**File:** `src/features/workflows/QueueShell.tsx:53-57` vs `src/routes/workflows/index.tsx:23-24`

`validateSearch` (route) keeps `state` only when it is a non-empty string, so at the
route boundary "filters active" ≡ the `state` key exists. The shell independently
re-derives activeness via `hasActiveFilters` scanning `Object.values()` for
`!== undefined && !== null && !== ''`. For the single `state?: string` filter these
agree, so it is correct today. The risk is drift: the two definitions are not the
same predicate. When the documented "filter UI is a future composite" adds, say, a
boolean or numeric filter, `validateSearch` and `hasActiveFilters` will disagree on
`false`/`0`, and a zero-row success could be misclassified (`empty` vs
`filtered-empty` — the exact thing Trap T6 warns against). The spec's own note uses
`Object.keys(search).length > 0`; the impl uses a value-scan.

**Severity:** Medium — correct now, fragile by construction along the stated growth path.

**Fix:** export ONE `isFiltersActive(filters)` predicate and use it in both
`validateSearch`/the route and the shell so there is a single source of truth.

### M3 — Raw `console.warn`/`console.info` in the render path; "fires once per error" comment is inaccurate
**File:** `src/features/workflows/QueueShell.tsx:155-165, 167-168, 172-173`

The logging fields are correctly scrubbed (code/category + `filtersActive` only;
`error.message`/status/stack never logged — Trap T3 honored, and `queryOptions.ts`
deliberately keeps its own `QueryCache.onError` console-free, so this shell is the
intended log site). Two smaller issues:

1. These are bare `console.warn`/`console.info` calls. The spec cites
   "`<ErrorState>`'s `state.activeError` log" as the precedent pattern; if a shared
   logger/observability seam exists, bypassing it is an inconsistency. (No shared
   logger was found in the contracts I read, so this likely matches precedent —
   flagging for confirmation against `<ErrorState>`.)
2. The comment at line 153-154 says the error log "fires once per error entry". The
   effect deps are `[state, query.error, filtersActive]`. A repeated failure that
   produces a *new* `ProblemDetailsError` object (each failed fetch throws a fresh
   instance) will re-fire the log on every failed load/retry. That is arguably
   desirable, but it is not "once per error" — correct the comment to "per distinct
   error object (i.e. per failed load/retry)".

**Severity:** Medium (consistency + a misleading comment that could mask the M1/H1
retry behavior during debugging).

### M4 — Empty-state copy names two different ingestion channels in one sentence
**File:** `src/features/workflows/QueueShell.tsx:211`

Copy: "New runs from Linear appear here once submitted via the CLI." This is faithful
to the AC3 wording, but it is internally contradictory user-facing text — "from
Linear" and "via the CLI" are different ingestion paths, and a PM persona is the
audience. Flagging because it is user-visible.

**Severity:** Medium (content; matches spec, so PM/product should arbitrate — not a
code-quality defect).

---

## LOW

### L1 — Populated rows fall back to `key={index}` when `workflowRunId` is undefined
**File:** `src/features/workflows/QueueShell.tsx:230`

`WorkflowSummary.workflowRunId` is optional. Two rows with undefined ids get
positional keys; if the backend returns id-less rows and the list reorders, React
can mis-reconcile DOM nodes. Harmless for today's inert placeholder rows, but it
matters once story 2.15's interactive rows (selection/hover/focus state) replace the
placeholder.

**Fix:** filter id-less rows or derive a stable composite key before 2.15 lands.

### L2 — `target="_blank"` docs link uses only `rel="noreferrer"`
**File:** `src/features/workflows/QueueShell.tsx:129`

`rel="noreferrer"` implies `noopener` in modern browsers, so reverse-tabnabbing is
mitigated; acceptable. The href is gated by `validateUrlScheme` (confirmed in
`sanitization/policy.ts` + its tests: rejects `javascript:`, `data:`, `//evil`,
backslash variants, case/whitespace tricks), so the injection vector is closed.

**Fix:** optional — add explicit `noopener` for belt-and-suspenders.

### L3 — Announcer text and the EmptyState/ErrorState body copy differ for the same state
**File:** `src/features/workflows/QueueShell.tsx:63-78, 182-184` + EmptyState/ErrorState

The shell announcer says e.g. "Review queue is empty" while the visible
`<EmptyState variant="queue">` shows "No specifications awaiting review…". This is
the intended T10 "non-verbatim" wording, and AC7's "unique accessible label" is
satisfied, but an AT user hears different text than a sighted user reads for the same
state. Noted for the 2.25 a11y reconciliation; not a bug.

---

## Verified CORRECT (checked against code + contracts — not findings)

- **Cache-key stability (initially suspected, now cleared).** The route change from
  `listQueryOptions()` to `listQueryOptions(deps)` where `deps` can be `{}` is safe:
  `workflowKeys.list` runs `normalizeFilters`, which returns `{}` for BOTH `undefined`
  and `{}`, so `list(undefined)` deep-equals `list({})` = `['workflows','list',{}]`.
  Deep links and Clear-filters share one warm cache entry; the flash-free claim holds.
- `resolveQueueState` precedence (error → loading → empty/filtered-empty → populated)
  is exhaustive, pure, and correct; error-over-loading prevents a refetch error from
  masquerading as loading. (`queueState.ts:39-55`)
- Exactly one state renders — five independent `state === 'x' ? … : null` blocks off
  one resolved value; no overlapping `&&` chains (Trap T8 honored).
- `filtered-empty` vs `empty` split on `filtersActive`, with distinct variant + copy +
  action (Trap T6 honored).
- `queueErrorMessage` never echoes `error.message`/status/stack; transport-vs-
  ProblemDetails branch is correct given `isProblemDetailsError` is an `instanceof`
  guard (`problemDetails.ts:85-87`). Test asserts no "500" leaks (T3 honored).
- No `RunReviewQueueItem` import; renderItem seam + `QueuePlaceholderRow` default
  (Trap T1). Skeleton uses `animate-pulse`, never `animate-spin` (Trap T2).
- `query.data ?? []` guards the undefined-during-loading/error case before `.length`
  — no null deref (`QueueShell.tsx:144`).
- `onClearFilters?.()` optional-chains — no crash if the prop is omitted.
- `useWorkflowsList` routes through `listQueryOptions`/`workflowKeys.list` — no inline
  key (Trap T5 honored).
- Docs CTA never renders a dead link: valid `VITE_DOCS_URL` → real anchor, else a
  disabled button (`QueueShell.tsx:121-140`).
- No unhandled promise rejection in the component: `refetch()` is `void`-ed and
  TanStack swallows query rejections into `query.error`.
```
