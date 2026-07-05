# 🔍 Code Review: Story 2.20 — Queue Shell States (loading / empty / filtered-empty / error)

**Verdict:** APPROVE WITH FIXES
**Scope:** 10 files (1 modified `routes/workflows/index.tsx` + 9 new), frontend-only. Matches the story File List exactly; no scope creep.
**ACs:** 9/10 fully satisfied; AC8 partial. Declared traps T1–T10 all honored.

## Must Fix (Critical/High)
_None._ Zero critical/high findings survived verification.

## Should Fix (Medium)
1. **`QueueShell.tsx:228` — populated `<ul>` omits `role="list"` (AC8 partial).** Tailwind v4 preflight resets `ul{list-style:none}`, so Safari/VoiceOver drops the implicit list role → AC8's `<ul role="list">` is literally unmet on the platform it matters for. Fix: re-add `role="list"` + inline `eslint-disable-next-line jsx-a11y/no-redundant-roles` with the Safari rationale, or explicitly defer to the story 2.25 axe sweep and record AC8 as knowingly partial.
2. **`QueueShell.tsx:122-140` — enabled docs-CTA path untested.** Tests only hit the disabled branch (`VITE_DOCS_URL` unset). Add a test stubbing a valid `https://` URL (assert anchor `href`) and a `javascript:` URL (assert disabled fallback).
3. **`queueState.ts:45` — `error` precedence blanks already-loaded rows on a refetch failure.** Spec-correct for 2.20 (no refetch trigger ships yet); flag a follow-up for when 2.15 rows + the filter control land: keep stale rows with an inline error instead of the full-page error.

## Consider (Low)
- AC10 transitions are asserted only at end-states (`loading→populated`/`loading→error` not observed mid-transition).
- Error-log `useEffect` re-fires per `query.error` identity (bounded; comment overstates "once").
- Placeholder malformed-runId branch (`isValidRunId` false → inert `<div>`) untested.
- Nits: dead `default` branch in `announcementFor` (use `assertNever`); `aria-busy` on an `aria-hidden` skeleton is inert; `key={index}` fallback; per-render env read.

## Dismissed
- No security/injection/secret-leak issues — `queueErrorMessage` never echoes raw error text (T3, tested); row text React-escaped; run id `isValidRunId`-gated; docs URL `validateUrlScheme`-gated.
- No null derefs / resource leaks / broken contracts. "Unmapped-code untested" is covered by the unknown-code path.

## What's Good
Faithful, disciplined implementation of a very detailed spec: state selection centralized in a pure, exhaustively unit-tested `resolveQueueState` (one state at a time, surfaced as `data-queue-state`); loading is a `<Skeleton>` shimmer not a spinner; error copy is code-derived with no leakage; the row-delegation `renderItem` seam cleanly avoids importing the not-yet-built `RunReviewQueueItem`; filters live in the URL; logging is field-only and test-pinned. All 10 declared traps honored.

> Gates (`tsc`, `eslint --max-warnings=0`, `lint:rules-test 25/25`, `vitest 103/103`) are GREEN per the Dev Agent Record; not re-run in this review (RTK Bash corruption + PowerShell-only gate path) — flagged, not assumed.
