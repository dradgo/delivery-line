# Code Review Triage — Story 2.20 (Queue Shell States)

## Summary
- 7 findings: 0 Critical, 0 High, 3 Medium, 4 Low (plus dismissals)
- AC tally: 9/10 fully satisfied, AC8 partial (`<ul role="list">`). Traps T1–T10 all honored.
- Scope verified: 1 modified file (`routes/workflows/index.tsx`) + 9 new files. Frontend-only, matches story File List exactly. No scope creep.
- **Verdict: APPROVE WITH FIXES** (one a11y line; rest optional test hardening)

## Critical
_None._

## High
_None._ (The `isError`-discards-stale-rows item, flagged HIGH by one reviewer, is downgraded to Medium — it matches AC1's literal "error — query isError" precedence and there is no refetch trigger in this story's scope yet.)

## Medium

### M1 — Populated `<ul>` omits the spec-mandated `role="list"` (AC8 partial; Safari/VoiceOver)
- **File:** `QueueShell.tsx:228`
- **Problem:** AC8 + AC9 + the Row-delegation seam all mandate `<ul role="list">`. The code renders `<ul className="flex flex-col gap-2" data-testid="queue-list">` with no `role`. The project uses Tailwind v4 (`@import 'tailwindcss'` in `globals.css`), whose preflight resets `ul { list-style: none }`. WebKit (Safari + iOS VoiceOver) **drops the implicit `list` role** when `list-style: none`, so SR users won't get "list, N items". The dev deliberately omitted `role` to satisfy `jsx-a11y/no-redundant-roles` under `--max-warnings=0` (recorded in sprint-status), but the AC text is literally unmet for the exact platform `role="list"` exists to fix.
- **Fix:** Re-add `role="list"` with an inline `// eslint-disable-next-line jsx-a11y/no-redundant-roles` carrying the Safari rationale (this is the rule's well-known carve-out), or carve the rule out for `<ul>` in `eslint.config.js`. If the team prefers, explicitly defer to the story 2.25 axe sweep — but record that AC8 is knowingly partial until then.

### M2 — `error` precedence discards already-loaded rows on a background-refetch failure
- **File:** `queueState.ts:45` (consumed at `QueueShell.tsx:146`)
- **Problem:** `resolveQueueState` returns `'error'` whenever `isError`, even when `query.data` still holds a previously-successful list (background refetch failed). Today that's invisible (first-load is the only path; no filter control / no refetch trigger ships in 2.20), and it matches AC1's literal wording. But once story 2.15 (rows) + the future filter control land, a transient refetch error will blank the whole queue instead of keeping stale rows with an inline error.
- **Fix:** Acceptable as-is for this story. Recommend a follow-up (track in 2.25/2.15): branch `isError && count > 0` to keep the list visible with a non-blocking error affordance, rather than the full-page error state. No change required to merge.

### M3 — Enabled docs-CTA path is untested
- **File:** `QueueShell.tsx:122-140` (`GettingStartedAction`), tests in `__tests__/QueueShell.test.tsx`
- **Problem:** Tests run with `VITE_DOCS_URL` unset, so only the **disabled placeholder** branch is exercised. The enabled `<a href target=_blank rel=noreferrer>` branch and the `validateUrlScheme(href).ok` truthiness are never executed by any test. A regression in the URL-gating (e.g. `.ok` shape change, wrong scheme handling) would ship silently.
- **Fix:** Add a test that stubs `import.meta.env.VITE_DOCS_URL` to a valid `https://` URL and asserts the rendered anchor's `href`, plus one with a `javascript:` URL asserting the disabled fallback.

## Low

### L1 — AC10 transitions asserted only at end-states
- **File:** `__tests__/QueueShell.test.tsx`
- Tests assert the final state of each case; only `loading→empty` is actually observed as a transition. `loading→populated` and `loading→error` (AC10) aren't asserted mid-transition, and the live-region "updates per transition" is checked per-state, not across one. Add an MSW-delayed-then-resolve test asserting `data-queue-state` flips `loading → populated`/`error` with the announcer text changing.

### L2 — Error-log effect re-fires on every `query.error` identity change
- **File:** `QueueShell.tsx:155-165` — dep array `[state, query.error, filtersActive]`. Each failed refetch yields a new error object → re-log. Bounded and arguably desirable (one log per failure), so keep — but the comment "Fires once per error entry" slightly overstates it.

### L3 — Placeholder malformed-runId branch untested
- **File:** `QueueShell.tsx:97-118` — the `isValidRunId(runId) === false` branch (inert `<div>` instead of `<Link>`) has no test. Defensive, low risk; add a row with a malformed id asserting no anchor is rendered.

### L4 — Minor nits (batch)
- `announcementFor` `default: return ''` (`QueueShell.tsx:75-76`) is unreachable dead code (`QueueState` is exhaustive). Optional: replace with an `assertNever` for exhaustiveness.
- Populated `<li key={item.workflowRunId ?? index}>` (`:230`) index fallback can cause reconciliation quirks if ids are missing/duplicated; acceptable for a placeholder.
- Skeleton container has `aria-busy="true"` on an `aria-hidden` element (`:187-191`) — `aria-busy` is inert under `aria-hidden`; the announcer carries the state, so harmless. Drop `aria-busy` or the `aria-hidden`.
- `import.meta.env.VITE_DOCS_URL` read + `validateUrlScheme` run every render (`:123`); negligible, could memo.

## Dismissed (with reasons)
- **Security / injection / secret leakage** — none. `queueErrorMessage` never echoes `error.message`/status/stack (unit-tested, T3); row fields are React-escaped text from the trusted list DTO; the run id is `isValidRunId`-gated before becoming a route param; the docs URL is `validateUrlScheme`-gated.
- **Null/undefined derefs & resource leaks** — none. `query.data ?? []` guards the list; no timers/subscriptions/manual effects to leak.
- **Broken contracts/types** — none; `tsc -b` green per Dev Agent Record; key factory routed through `listQueryOptions` (T5).
- **"known-but-unmapped DomainErrorCode" untested** — functionally identical to the unknown-code path, which IS tested. Not a real gap.
- **`isError` HIGH rating** — downgraded to M2 (spec-sanctioned for this story's scope).

## Verification note
Findings are from static review of ground-truth source (native Read/Grep/Glob) + 3 parallel adversarial subagents. Gates (`tsc`, `eslint --max-warnings=0`, `lint:rules-test 25/25`, `vitest 103/103`) are reported GREEN in the Dev Agent Record; **they were NOT re-run in this review** (the RTK Bash corruption + PowerShell-only gate path makes a re-run slow; flagged rather than silently assumed).
