# Story 3.31: GitHub PR Linkage Display in Run Context Strip + Queue Item

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager / Developer / Workflow Owner viewing any run with a linked GitHub PR,
I want the Run Context Strip + Queue Item to display the GitHub PR reference (branch + commit SHA short-form + PR ref + PR state badge) alongside the existing Linear ticket reference,
so that FR40 (link governed implementation output to GitHub / PR refs) is surfaced in the UI — making the ticket↔repo↔branch↔commit↔PR↔run lineage (NFR17) visible at a glance from the queue without opening the run.

## Acceptance Criteria

1. **Given** `RunReviewQueueItem` from story 2.15, **Then** extended to display linked GitHub PR reference (when present) as a compact secondary metadata element: `PR org/repo#42` with state badge (`draft` / `open` / `merged` / `closed` from `external_metadata.prState` per story 3.15 AC1) + an open-in-GitHub affordance (new tab). See Decision **D1** for how the click target coexists with story 2.15's whole-row `<Link>` (nested-anchor invalidity).
2. **Given** `RunContextStrip` from story 2.16, **Then** extended to display: `branchOrCommitReference` (was hardcoded `undefined` in `toRunContextView` per story 2.16 AC1 reconciliation — now populated for runs that completed at least the PR-output stage), `prReference` with state badge, and last-sync timestamp from `integration_links.last_sync_at` per story 3.15 AC1.
3. **Given** the "lightweight" rule from story 2.16 AC4, **Then** the new PR linkage display does not push the strip past `RUN_CONTEXT_STRIP_MAX_HEIGHT`; if real estate is tight, branch + commit are displayed inline with abbreviation (commit SHA short form, e.g. `a3f291`) + a tooltip for the full SHA. (jsdom caveat per 2.16: tests assert the constraining style is PRESENT, not a measured pixel height.)
4. **Given** stale GitHub state per story 3.15 AC6, **When** `last_sync_at` is older than a configurable freshness threshold (default 5 minutes — a SEPARATE constant from the 10-minute run-activity `RUN_STALE_THRESHOLD_MS`), **Then** the state badge renders with a "(stale, last synced X ago)" affordance.
5. **Given** GitHub unreachable (e.g. backend's GitHubAdapter calls failing), **Then** the cached `external_metadata.prState` continues to render — story 3.15 AC3 / NFR17 reconstruction holds; reviewers are not blocked from inspecting run state. A field-only structured `console.warn` log fires on this branch.
6. **Given** wrong-ticket-link prevention per NFR20, **Then** the displayed PR reference always comes from the workflow-detail / summary read model (backend truth, the future projection of `integration_links`) — NEVER from runner-emitted artifact metadata that might have drifted; a contract test asserts the renderer sources PR data from the `RunContextView` / `RunQueueRow` view-models (mapped from `useWorkflowDetail` / `useWorkflowsList`), never from `useArtifact` / `ArtifactView`.
7. **Given** queue and run-context surfaces are consistent, **Then** the same PR reference + state badge visual treatment is used across both — no surface-specific styling drift (both consume the SAME shared `PrStateBadge` + `githubRef` helpers — see Decision **D2**).
8. **Given** runs without GitHub linkage (run that hasn't reached PR-output stage, or a legacy run), **Then** the PR linkage display gracefully renders **nothing** in the affected slots — no empty placeholders, no "—" markers, just absent. **Trap T-ABSENT:** this differs from the strip's existing `<NotReported />` per-field idiom — the PR linkage cluster is conditionally rendered (omitted entirely when linkage is `null`), NOT a labeled `Item` that falls back to "Not reported".
9. **Given** ARIA labels + accessibility per story 2.25, **Then** the PR reference link's accessible name includes the full canonical reference + state (e.g. `aria-label="Pull request 42 in org/repo, status open"`).
10. **Given** component test coverage, **Then** tests cover: queue item renders PR linkage when present, queue item gracefully omits when absent, run context strip renders branch/commit/PR/state with last-sync timestamp, stale-state freshness affordance renders past threshold, GitHub-unreachable cached-state rendering, max-height threshold preserved with PR linkage added (layout regression test — style-present assertion), backend-truth-only sourcing (artifact-emitted PR refs never reach the renderer), axe-core a11y zero violations.

## Tasks / Subtasks

- [x] **Task 1 — Frontend-owned `PrLinkageView` read model + the shared shape** (AC: #1, #2, #4, #5, #6)
  - [x] Add a new pure module `src/features/workflows/prLinkageView.ts` (a `.ts`, NOT `.tsx`, per `frontend-react-refresh-no-fn-exports`) exporting the single shared shape both surfaces consume:
    ```ts
    export interface PrLinkageView {
      readonly prReference: string;          // canonical `org/repo#42` (backend truth)
      readonly prState: 'draft' | 'open' | 'merged' | 'closed';
      readonly prUrl?: string | undefined;   // prefer verbatim when present
      readonly branch?: string | undefined;  // runner-emitted (untrusted)
      readonly commitSha?: string | undefined; // runner-emitted, 7–40 hex (untrusted)
      readonly lastSyncedAt?: string | undefined; // ISO-8601 UTC, from integration_links.last_sync_at
      readonly githubReachable?: boolean | undefined; // false ⇒ cached-state affordance (AC5)
    }
    ```
  - [x] Export `PR_LINKAGE_STALE_THRESHOLD_MS = 5 * 60 * 1000` (AC4) from this module — a SEPARATE constant from `runContextView`'s 10-minute `RUN_STALE_THRESHOLD_MS` (those are different concepts: run-activity staleness vs PR-sync freshness). Add a pure `isPrLinkageStale(lastSyncedAt, now): boolean` helper (Date.parse guard, mirrors `runContextView.isStale`).
  - [x] **Central reconciliation — there is NO live PR-linkage wire field today.** `WorkflowDetail` / `WorkflowSummary` (schema.d.ts) carry `linkedTicket` but NO `integrationLinks` / `prState` / `prReference`; `toRunContextView` already hardcodes `branchOrCommitReference: undefined`. So this whole feature is **DORMANT** — built + tested via constructed fixtures, never fabricated from live data (the same dormant pattern as story 3.30's recovery fields that ARE on the wire, EXCEPT here nothing is on the wire yet, so it mirrors 3.26/3.27's frontend-owned `ArtifactView`). Document the **intended future source** in the module header: `integration_links` rows of type `github_pr` (story 3.15 AC1 — `externalRef` = canonical PR ref, `externalMetadata.prState`, `lastSyncAt`) projected into the workflow-detail read model by a future read-model story (6.9 / 3.15-surfacing). Until then the mappers return `undefined`.

- [x] **Task 2 — Extend the view-models with a dormant `prLinkage` slot** (AC: #1, #2, #6)
  - [x] **`runContextView.ts`:** add `readonly prLinkage: PrLinkageView | undefined;` to `RunContextView`. In `toRunContextView`, source it from an optional frontend-owned extension on the detail — define `type WorkflowDetailWithLinkage = WorkflowDetail & { integrationLinks?: readonly GitHubPrLinkWire[] }` (the intended future projection) and map the first `type === 'github_pr'` row → `PrLinkageView`, returning `undefined` when absent. Today the live wire has no `integrationLinks`, so production maps to `undefined` (dormant); fixtures inject it. Reuse the existing `presentOrUndefined` (`!= null` guard) for every string field — nullable wire fields arrive as JSON `null` ([[workflowdetail-wire-sends-null-not-undefined]]).
  - [x] Keep `branchOrCommitReference` mapping as-is for now (still no wire field) **but** document that the strip will prefer `prLinkage.branch` + `prLinkage.commitSha` for the populated branch/commit display (AC2/AC3) when `prLinkage` is present, falling back to the legacy `branchOrCommitReference` slot. Do NOT flip `resolveRunContextState` on `prLinkage` (it is an Epic-3 deferral like `branchOrCommitReference` — keep `default` reachable; mirror the existing comment at `runContextView.ts:163-170`).
  - [x] **`runQueueRow.ts`:** add `prLinkage?: PrLinkageView | undefined;` to `RunQueueRow`. In `toRunQueueRow`, leave it `undefined` in the DORMANT block (the live `WorkflowSummary` has no PR projection — same reconciliation as `summary`/`staleIndicator`). Do NOT touch `resolvePrimaryAttentionIndicator` / `resolveQueueItemState` (PR linkage is metadata, not an attention/dominant-state signal).

- [x] **Task 3 — Shared GitHub-ref helpers + `PrStateBadge` (consume-or-create from story 3.27)** (AC: #1, #2, #7, #9)
  - [x] **Sequencing (Decision D2):** story 3.27 (`ready-for-dev`, sibling, may land first) creates `src/features/workflows/githubRef.ts` (`parsePrReference('org/repo#42') → { owner, repo, number } | null`, `prUrl`, `branchUrl(owner,repo,branch)`, `commitUrl(owner,repo,sha)`) and `src/features/workflows/components/PrStateBadge.tsx` (composes the `@/components/ui/badge` `Badge` primitive with a non-color signifier per story 2.3 AC5). **If those modules already exist, CONSUME them unchanged** (do not fork — AC7 forbids styling drift). **If 3.31 lands first, CREATE them per the 3.27 spec** in the SAME shared locations so 3.27 consumes them when it lands. Either way the two stories MUST converge on one `githubRef.ts` + one `PrStateBadge`.
  - [x] `parsePrReference` must accept the real-adapter canonical form `owner/repo#number` (the format `GitHubRealAdapter` actually emits — [[proutput-prref-validator-rejects-real-adapter]]); derive `owner/repo` for branch/commit URLs from the **backend-truth** `prReference` (NOT from runner-emitted branch/commit values). Prefer `prLinkage.prUrl` verbatim for the PR href when present.
  - [x] `PrStateBadge` props: `{ state: PrLinkageView['prState']; stale?: boolean; lastSyncedAt?: string }`. Render a small badge pairing color + a text label + an icon/signifier for each of the four states; when `stale`, append the "(stale, last synced X ago)" affordance text via `formatRelativeTime`. Keep it presentational + reusable across both surfaces (AC7).

- [x] **Task 4 — Run Context Strip: render the PR linkage cluster** (AC: #2, #3, #4, #5, #8, #9)
  - [x] In `RunContextStrip.tsx`, after the existing `Branch` `Item`, render a **conditional** PR-linkage cluster ONLY when `view.prLinkage != null` (Trap T-ABSENT — no `<NotReported />` for these slots; absent ⇒ render nothing).
  - [x] When present, render inside the same single wrapping `Inline` row (AC3 keeps the strip a single lightweight row under `RUN_CONTEXT_STRIP_MAX_HEIGHT`):
    - branch (escaped text/`<code>`), commit SHA short-form (7 chars) inside a `<time>`/`<span>` with `title={fullSha}` tooltip (AC3); build the commit/branch links via `githubRef` `commitUrl`/`branchUrl` from the backend-truth repo.
    - `PR org/repo#42` link — a real `<a href={prUrl} target="_blank" rel="noopener noreferrer">` (the strip is NOT wrapped in a row-level anchor, so a nested-anchor problem does not arise here — unlike the queue, see D1) with `aria-label="Pull request {number} in {owner}/{repo}, status {prState}"` (AC9).
    - `<PrStateBadge state={...} stale={isPrLinkageStale(lastSyncedAt, nowMs)} lastSyncedAt={...} />` (AC4).
  - [x] AC5: when `prLinkage.githubReachable === false`, still render the cached `prState` (it is the cached backend-truth value) + the stale affordance, and emit a field-only `console.warn({ event: 'runContext.prGithubUnreachable', prState, staleForMs })` (NEVER the PR url/token/ref free-text — field-only, mirrors the existing `runContext.loadError`/`.stale` logs). Pin it with a `console.warn` spy assertion.
  - [x] Confirm the strip's `maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT` + `overflow-hidden` box style is unchanged (AC3 layout-regression — the cluster wraps within the capped row).

- [x] **Task 5 — Run Review Queue Item: render the compact PR linkage element** (AC: #1, #7, #8, #9)
  - [x] In `RunReviewQueueItem.tsx`, render the PR linkage inside the existing secondary cluster (`data-testid="queue-item-secondary"`) ONLY when `row.prLinkage != null` (extend the secondary-cluster render condition; Trap T-ABSENT). Show a compact `PR org/repo#42` element + `<PrStateBadge>` matching the strip (AC7).
  - [x] **Decision D1 — the click target vs the whole-row `<Link>`:** story 2.15 wraps the ENTIRE row in a single `<Link to="/workflows/$workflowRunId">` (Trap T5/T8 — whole-row open-run activation). A nested `<a>` for the PR inside that anchor is **invalid HTML (interactive content inside an anchor) and an a11y failure**. Recommended (see Open Questions): convert the row container to the **stretched-link pattern** — a `relative` wrapper whose row-level navigation `<Link>` becomes an absolutely-positioned `inset-0` stretched overlay (the run-open affordance), with the PR `<a target="_blank">` rendered as a sibling at `relative z-10` so it escapes the overlay and is independently clickable (with `onClick` stopPropagation as defense-in-depth). This preserves 2.15's whole-row open-run intent (Enter/Space still open the run) AND honors AC1's clickable PR link without nesting anchors. **Do NOT** simply nest the anchor. If Alex prefers minimal risk, fall back to a NON-interactive PR ref+badge in the queue (clickable PR link only in the strip) — flagged as a decision.
  - [x] AC9: the PR `<a>` carries the same `aria-label="Pull request {number} in {owner}/{repo}, status {prState}"`. Ensure the queue row's existing `composeAriaLabel` (the row's own accessible name) is NOT polluted with PR text — the PR link is a distinct focusable element with its own label.

- [x] **Task 6 — Fixtures** (AC: #2, #4, #5, #8, #10)
  - [x] Add a Run-Context fixture `src/test/fixtures/runContext/prLinkageDisplay.ts`: a `WorkflowDetailWithLinkage` (extend the existing `specRejectAndResubmitDetail` shape) carrying an `integrationLinks: [{ type: 'github_pr', externalRef: 'acme/widgets#42', externalMetadata: { prState: 'open', branch: 'deliveryline/DEL-9002', commitSha: 'a3f29110d…' }, lastSyncAt: '<recent>' }]` so `toRunContextView` projects a populated `prLinkage`. Add sibling variants: `…StaleGitHub` (`lastSyncAt` past `PR_LINKAGE_STALE_THRESHOLD_MS`), `…GitHubUnreachable` (mapper sets `githubReachable: false` — drive via a wire flag or a separate detail field), `…NoLinkage` (no `integrationLinks` → `prLinkage` undefined, AC8).
  - [x] Add a Run-Queue fixture / row helper in `src/test/fixtures/runQueue/` (or inline `RunQueueRow` in the test, like `BASE_ROW`) carrying a populated `prLinkage` + a no-linkage variant (AC8). Cover all four `prState` values across the badge test.

- [x] **Task 7 — Tests** (AC: #10)
  - [x] Extend `RunContextStrip.test.tsx` (router-free, MSW-backed, `Date.now` pinned — the existing pattern): PR cluster renders branch/commit-short(+full-SHA tooltip)/PR-link(+correct GitHub URL)/state badge + last-sync; stale affordance past `PR_LINKAGE_STALE_THRESHOLD_MS`; GitHub-unreachable cached-state + the `runContext.prGithubUnreachable` `console.warn` spy; AC8 absent → no PR slots / no "Not reported" for PR; AC3 max-height style present with linkage added; `expectNoA11yViolations`.
  - [x] Extend `RunReviewQueueItem.test.tsx` (router-`Link`-mocked — the existing pattern): PR linkage renders in the secondary cluster when present; gracefully omitted when absent (AC8); D1 click target opens GitHub in a new tab without breaking whole-row open-run (assert the run-open `data-to`/`data-run-param` still resolves AND the PR `<a>` has the GitHub href + `target="_blank"`); AC9 PR `aria-label`; all four `prState` badges; `expectNoA11yViolations`.
  - [x] Add `src/features/workflows/prLinkageView.test.ts` (pure): `isPrLinkageStale` boundary, the dormant `toRunContextView`/`toRunQueueRow` mapping (no `integrationLinks` ⇒ `prLinkage` undefined; populated ⇒ projected). If creating `githubRef.ts`/`PrStateBadge` in this story (3.27 not yet landed), add their unit/component tests per the 3.27 spec; if consuming, do NOT duplicate their tests.
  - [x] **AC6 backend-truth contract test:** assert (statically/structurally) that `RunContextStrip` + `RunReviewQueueItem` source PR data only from the `RunContextView`/`RunQueueRow` view-models — they do NOT import `useArtifact` / `artifactView`. A focused test that renders with a view-model carrying `prLinkage` and an (unrelated) artifact context proves the PR ref shown is the view-model's, never an artifact's.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **This story is frontend-only** — the backend SLF4J/Logback/MDC standard does NOT apply. The sanctioned SPA surface is the field-only structured `console.warn`/`console.info` pattern already used by `RunContextStrip` (`{ event, code, transport }` — stable codes/flags, never raw error text, PR tokens, urls, or PII).
  - [x] Add exactly the AC5 field-only log `console.warn({ event: 'runContext.prGithubUnreachable', prState, staleForMs })` on the GitHub-unreachable branch and pin it with a `console.warn` spy assertion (as the existing strip tests do). No new logs in the pure mappers.

## Dev Notes

### Central reconciliation (READ FIRST)

There is **no live PR-linkage source on the wire today.** `WorkflowDetail` and `WorkflowSummary` (`deliveryline-frontend/src/lib/api/schema.d.ts:339-374, 456-…`) carry `linkedTicket` (Linear) but **no `integrationLinks` / `prState` / `prReference`**, and `toRunContextView` already hardcodes `branchOrCommitReference: undefined` (`runContextView.ts:141`). Therefore this entire feature is **DORMANT** — extend the frontend-owned view-models with a `prLinkage` slot, map it to `undefined` in the pure mappers (no live source), render it only when present, and exercise every render path via **constructed fixtures**. This is the same discipline 3.26/3.27 use for the frontend-owned `ArtifactView`, and that 2.15/2.16 use for their dormant `summary`/`branchOrCommitReference` fields. [Source: deliveryline-frontend/src/features/workflows/runContextView.ts:14-24, 54-55; runQueueRow.ts:11-21]

**Intended future source** (document in `prLinkageView.ts`): story 3.15 writes `integration_links` rows of type `github_pr` with `externalRef` = canonical PR ref (`owner/repo#number`), `externalMetadata.prState` (`draft`/`open`/`merged`/`closed`), and `lastSyncAt`. A future read-model story (6.9 / a 3.15-surfacing increment) projects those onto the workflow-detail / summary read models. When it lands, only the mapper's optional-field read flips from `undefined` to live — the components are unchanged. [Source: epic-03 story 3.15 AC1/AC3/AC6; sprint-status 3-15 done note]

### AC6 — backend-truth sourcing (NFR20 wrong-link prevention)

The PR ref a reviewer trusts must come from the **workflow-detail read model** (the projection of `integration_links` — backend truth), NEVER from a runner-emitted artifact (`useArtifact` / `ArtifactView`, which can drift). Today `useArtifact` is a disabled stub and neither source carries `prState`, so AC6 is **structural discipline**: the renderers read `prLinkage` from `RunContextView`/`RunQueueRow` (mapped from `useWorkflowDetail`/`useWorkflowsList`) and must not import the artifact view for PR data. The contract test pins this. [Source: story 3.27 Dev Notes "Central reconciliation"; epic-03 story 3.31 AC6]

### Trust boundary (which fields are backend-truth vs runner-emitted)

| Field | Source | Trust | Display rule |
|-------|--------|-------|--------------|
| `prReference` `org/repo#42` + `prState` badge | `integration_links.external_metadata.prState` (backend) | **TRUSTED** | authoritative; render the badge + derive owner/repo for all URLs from here |
| `prUrl`, `lastSyncedAt` | `integration_links` (backend) | **TRUSTED** | trusted; prefer `prUrl` verbatim |
| `branch`, `commitSha` | runner-emitted artifact (echoed into the link row) | untrusted | render as escaped text/`<code>`; build URLs from the **backend-truth** owner/repo + these values |

The owner/repo identity for branch/commit links is parsed from the **trusted** `prReference`, never inferred from runner-emitted branch/commit strings (a spoofed branch can't redirect the link to another repo).

### Key components + seams (already built — extend, don't rebuild)

- `RunContextStrip.tsx` — single labeled `region` ("Run context"), single wrapping `Inline` row capped at `RUN_CONTEXT_STRIP_MAX_HEIGHT` (`= '3.5rem'`) + `overflow-hidden`. Reads `useWorkflowDetail` → `toRunContextView`. Already renders a `Branch` `Item`. Uses `formatRelativeTime`/`formatUtcTimestamp` (`../runContextFormat`). The 3.30 `RecoveryBaseline` is a SEPARATE region below the strip — model the PR cluster as INSIDE the capped row (AC3), not a new region. [Source: RunContextStrip.tsx:52, 138-186, 320-362]
- `RunReviewQueueItem.tsx` — PURE presentational; whole row is one `<Link>` when `navigable` (the D1 nested-anchor constraint). Secondary cluster is `data-testid="queue-item-secondary"` rendering demoted signals + assignee. `Density`/`StateSignifierChip`/`WorkflowStateBadge` reused. [Source: RunReviewQueueItem.tsx:249-282, 371-409]
- `runContextView.ts` / `runQueueRow.ts` — the two pure mapper seams; extend the view-model + mapper, keep state-resolvers untouched.
- `@/components/ui/badge` `Badge` (cva variants: default/secondary/destructive/outline) — the base for `PrStateBadge`. Do NOT use `WorkflowStateBadge` (PR state ≠ workflow state). [Source: components/ui/badge.tsx]

### Project Structure Notes

- Pure helpers/types go in `.ts` siblings (`prLinkageView.ts`, `githubRef.ts`) — a `.tsx` exporting a non-component function trips `frontend-react-refresh-no-fn-exports` ([[frontend-react-refresh-no-fn-exports]]). `PrStateBadge` is a component ⇒ `.tsx`.
- No backend, OpenAPI, runner-contracts, `schema.d.ts`, `package.json`, or lockfile change — presentational + fixture-driven. (No npm/lockfile churn ⇒ none of [[frontend-lockfile-cross-platform]] / [[frontend-ts6-legacy-peer-deps]] applies.)
- Nullable wire fields serialize as JSON `null`, not `undefined` — guard `!= null` before string ops on any `prLinkage` field ([[workflowdetail-wire-sends-null-not-undefined]]).

### Testing standards summary

- **Vitest + Testing Library**, colocated `*.test.tsx`. `RunContextStrip.test.tsx` is router-free + MSW-backed with `Date.now` pinned; `RunReviewQueueItem.test.tsx` mocks `@tanstack/react-router`'s `Link` to a plain `<a>` surfacing `data-to`/`data-run-param`. Follow those existing harnesses. [Source: RunContextStrip.test.tsx:1-66; RunReviewQueueItem.test.tsx:25-53]
- **a11y:** `expectNoA11yViolations(container)` from `@/test/a11y/axe`; keyboard reachability via `@/test/a11y/keyboard`.
- Keep same-module mocks in ONE file (Vitest per-worker registry races) ([[vitest-cross-file-router-mock]]). The QueueShell announcer flake is unrelated — assert announcer text via `waitFor`, never synchronously ([[livesnnouncement-defers-one-commit-test-flake]]).
- Run focused story tests, then the full `vitest` run; run `prettier --write` + the lint / `lint:rules-test` gates before declaring done — one unformatted frontend file cascades the whole CI ([[prettier-gate-cascades-ci]]). RTK corrupts only the Bash tool — drive the frontend toolchain via PowerShell ([[rtk-hook-only-matches-bash]]).

### Logging Requirements (project-wide standard)

This story is frontend-only; the SPA's sanctioned logging is the field-only structured `console.warn`/`console.info` pattern already used by `RunContextStrip` (`{ event, code, transport }` — stable codes/flags, NEVER raw error messages, PR urls/tokens, or PII). The single new log is the AC5 GitHub-unreachable line, pinned by a spy assertion. _(The backend SLF4J + Logback + MDC standard — service entry/exit, state transitions, adapter writes, `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity` — applies to backend stories and is retained here for reference only; it does not apply to the SPA.)_

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.31 (lines 619-636)] — authoritative AC list.
- [Source: deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx] — the strip; extend the populated `StripContent` row + the `Branch` `Item`; `RUN_CONTEXT_STRIP_MAX_HEIGHT` cap (AC3).
- [Source: deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx] — the queue row; whole-row `<Link>` (D1) + the secondary cluster.
- [Source: deliveryline-frontend/src/features/workflows/runContextView.ts] — `RunContextView` + `toRunContextView` (the `branchOrCommitReference: undefined` reconciliation + `presentOrUndefined` `!= null` guard).
- [Source: deliveryline-frontend/src/features/workflows/runQueueRow.ts] — `RunQueueRow` + `toRunQueueRow` dormant-field block.
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:339-374, 456-…] — `WorkflowDetail`/`WorkflowSummary` carry NO PR-linkage field (proves the dormant reconciliation).
- [Source: _bmad-output/implementation-artifacts/3-27-artifact-review-panel-pr-output-variant-renderer.md] — the sibling that creates `githubRef.ts` + `PrStateBadge` (consume-or-create per D2) and the trust-boundary table.
- [Source: deliveryline-frontend/src/components/ui/badge.tsx] — `Badge` primitive for `PrStateBadge`.
- [Source: epic-03-agent-execution.md#Story-3.35 (line 709)] — story 3.35 AC6 expects 3.31's component test coverage; keep test ids/structure discoverable.

### Open questions / decisions (raised after the story was drafted)

1. **D1 — queue PR click target vs the whole-row `<Link>`** (the central queue decision). The 2.15 row is one whole-row anchor; a nested PR `<a>` is invalid HTML + an a11y failure. **Recommended:** the stretched-link pattern (row `<Link>` as an absolute `inset-0` overlay; PR `<a target="_blank">` as a `relative z-10` sibling that escapes it) — keeps whole-row open-run AND a real clickable PR link. **Fallback (lower risk):** non-interactive PR ref+badge in the queue, clickable PR link only in the strip (AC1 "clickable" then reads as "openable from the run's context strip"). Confirm which before implementing.
2. **D2 — `githubRef.ts` + `PrStateBadge` sequencing** with story 3.27 (both `ready-for-dev`, same shared helpers, AC7 forbids drift). **Recommended:** consume them if 3.27 has landed; otherwise create them in the SAME shared locations per the 3.27 spec so 3.27 consumes them. Confirm whether 3.27 should be merged first to avoid a double-create conflict.
3. **Last-sync freshness threshold** — recommended default 5 min (`PR_LINKAGE_STALE_THRESHOLD_MS`), a separate constant from the 10-min run-activity stale window. Confirm the value (and whether it should be config-driven vs a constant in this presentational era).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-dev-story

### Debug Log References

- Frontend gates (PowerShell, per [[rtk-hook-only-matches-bash]]): `tsc -b` clean · `eslint . --max-warnings=0` clean · `vitest run` **965/965 passing (87 files)** · `lint:rules-test` 9/9 · `prettier --check` clean.

### Completion Notes List

**Decisions taken before implementing (the 3 flagged open questions):**

- **D1 (queue click target) → STRETCHED-LINK pattern** (Alex confirmed). Story 2.15 wraps the whole row in one `<Link>`; a nested PR `<a>` is invalid HTML. The navigable row is now a `relative` wrapper, the open-run `<Link>` is an absolutely-positioned `inset-0 z-[1]` overlay (still the focusable open-run control — the `data-testid="run-review-queue-item"` + state + keyboard handlers + aria-label stay ON it), and the PR link is a sibling at `relative z-[2]` (inside `PrLinkageDetails`) with `onClick` stop-propagation so it escapes the overlay. Keeping the testid on the overlay `<Link>` preserved nearly every existing 2.15 test unchanged; only 2 fixture-driven tests that asserted text on the (now-empty) overlay were switched to `getByText`.
- **D2 (shared helpers) → CONSUME** `githubRef.ts` + `PrStateBadge.tsx` unchanged (story 3.27 is `done`; AC7 forbids forking). The existing `PrStateBadge` props are `{ state, className }` (no `stale`/`lastSyncedAt`), so the stale affordance is rendered as a SIBLING text node in the shared `PrLinkageDetails`, not by forking the badge.
- **D3 (freshness threshold) → 5 minutes constant** `PR_LINKAGE_STALE_THRESHOLD_MS` (Alex confirmed) — separate from the 10-min `RUN_STALE_THRESHOLD_MS`.

**Implementation notes:**

- DORMANT, fixture-driven throughout — no live wire field. `WorkflowDetailWithLinkage` is the frontend-owned future-projection extension; `toRunContextView`/`toRunQueueRow` map `prLinkage` to `undefined` in production. State resolvers (`resolveRunContextState`/`resolveQueueItemState`/`resolvePrimaryAttentionIndicator`) deliberately untouched (PR linkage is metadata, not a state/attention signal).
- AC7 single-treatment: one shared `PrLinkageDetails` (`variant: 'strip' | 'queue'`) drives both surfaces. `strip` adds branch/short-commit GitHub links (repo identity parsed from the TRUSTED `prReference`, never the runner-emitted branch/commit) + last-sync; `queue` is the compact PR-ref + badge + stale only (AC1).
- Trap T-ABSENT honored: the cluster is omitted entirely when `prLinkage == null` (the strip's legacy `Branch` "Not reported" Item shows only in the no-linkage path; the PR cluster supersedes it when present). No PR-specific "Not reported".
- AC5: GitHub-unreachable renders the cached `prState` + stale affordance and emits the field-only `console.warn({ event: 'runContext.prGithubUnreachable', prState, staleForMs })`, keyed on primitives so it fires once; pinned by a spy + key-set assertion (no PR url/ref leak).
- AC6 enforced two ways: a render proof (the shown ref equals the view-model's `prLinkage.prReference`) + a structural `?raw`-source contract asserting the renderers never import `useArtifact`/`artifactView`.
- No backend / OpenAPI / `schema.d.ts` / `package.json` / lockfile change.

### File List

**New:**
- `deliveryline-frontend/src/features/workflows/prLinkageView.ts`
- `deliveryline-frontend/src/features/workflows/components/PrLinkageDetails.tsx`
- `deliveryline-frontend/src/features/workflows/__tests__/prLinkageView.test.ts`
- `deliveryline-frontend/src/features/workflows/__tests__/prLinkageBackendTruthContract.test.ts`
- `deliveryline-frontend/src/test/fixtures/runContext/prLinkageDisplay.ts`
- `deliveryline-frontend/src/test/fixtures/runQueue/prLinkage.ts`

**Modified:**
- `deliveryline-frontend/src/features/workflows/runContextView.ts` (+ `WorkflowDetailWithLinkage`, `prLinkage` slot, dormant mapping)
- `deliveryline-frontend/src/features/workflows/runQueueRow.ts` (+ dormant `prLinkage` slot)
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx` (PR cluster + AC5 unreachable log)
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx` (stretched-link restructure + compact PR element)
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.test.tsx` (PR-linkage describe block)
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx` (PR-linkage + D1 + 2 text-assertion updates)
- `deliveryline-frontend/src/features/workflows/runContextView.test.ts` (added `prLinkage: undefined` to the full-view literal)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status transitions)

### Change Log

| Date       | Change                                                                 |
|------------|------------------------------------------------------------------------|
| 2026-06-15 | Implemented story 3.31 — frontend-only, dormant GitHub PR linkage display in `RunContextStrip` + `RunReviewQueueItem` via a shared `PrLinkageDetails` + frontend-owned `prLinkageView`/`PrLinkageView`. D1=stretched-link, D2=consumed 3.27 helpers, D3=5-min stale window. All 10 ACs covered; gates green (tsc/eslint/965 vitest/rules-test/prettier). Status → review. |

### Review Findings

_bmad-code-review 2026-06-15 — 3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor) over the working-tree diff scoped to the File List (13 files, ~870 lines). Acceptance Auditor verified ALL 10 ACs + D1/D2/D3 + Trust boundary + Trap T-ABSENT SATISFIED against real source. Every High verified against source before classifying._

**Decision-needed (resolved):**

- [x] [Review][Patch][Decision→Fixed] PR link href trusted `prLinkage.prUrl` verbatim with no scheme/host validation — a future malicious/compromised `integration_links.prUrl` (`javascript:…` / off-GitHub host) would render directly into `<a href>`; `rel=noopener` does not block `javascript:`. **FIXED 2026-06-15** (Alex: option 1 → patch): new `isGitHubHttpsUrl(url)` in `githubRef.ts` (URL-parse + `protocol==='https:'` + `hostname==='github.com'`), consumed in `PrLinkageDetails.tsx` so the verbatim path is taken ONLY when safe, else falls back to the derived `prUrl(parsed)`. Mirrors the existing `branchUrl` `..`-traversal hardening ([[githubref-branchurl-dot-traversal]]). +2 unit tests (`javascript:`, `http:`, off-GitHub, `github.com.evil.example`, unparseable, empty all rejected). [PrLinkageDetails.tsx:56; githubRef.ts:61-77]
- [x] [Review][Decision→Dismissed] Stale-vs-unreachable copy conflation — `githubReachable === false` reuses the AC4 "(stale, last synced X ago)" affordance. **DISMISSED** (Alex: recommended): the affordance reuse is intentional and AC5 only requires the cached state + an affordance to render, which it does; cosmetic-only. [PrLinkageDetails.tsx:58-59,142-145]

**Deferred:**

- [x] [Review][Defer] AC5 unreachable `useEffect` keeps the time-derived `prStaleForMs` in its deps and logs `staleForMs: NaN` when `lastSyncedAt` is unparseable [RunContextStrip.tsx:329-338] — deferred: bounded (can fire one extra warn only on the run-activity stale-timer transition; `nowMs` is pinned state, not a per-render tick), and the `NaN`-on-unparseable matches the existing sibling `runContext.stale` log (line 281), so it is consistent with current code. Revisit when the live wire lands.
- [x] [Review][Defer] `toPrLinkageView` selects the FIRST `github_pr` row arbitrarily [prLinkageView.ts:138] — deferred: dormant; define recency/active-state ordering when 6.9 projects multiple linkage rows onto the read model.
- [x] [Review][Defer] `prState` enum gating is strict + un-normalized (no case fold) — an unanticipated/case-variant state from the future backend projection drops the WHOLE cluster (AC8 absence) rather than degrading to ref+badge [prLinkageView.ts:122,144] — deferred: dormant; reconcile against the actual 6.9 projected values when live.

**Dismissed as noise (9):** malformed backend `prReference` → non-link "PR garbage" (backend-truth, escaped, acceptably graceful); short/non-hex `commitSha` broken link (untrusted-by-design, `encodeURIComponent`-safe, escaped); `?raw` contract-test brittleness + transitive type-only `PrState` import (auditor verified — type-only enum, non-renderer file, outside scan set); empty-string branch via direct view construction (test-only path; live mapper guards via `presentOrUndefined`); `row()` text-assertion sweep incompleteness (contradicted by green 965/965 vitest); keyboard/focus double-activation on stretched link (separate tab stops, no shared handler; D1 test + axe cover); coincident focusable controls a11y ambiguity (standard stretched-link tradeoff; axe clean); `<time dateTime>` possibly undefined (gated on `freshSync !== null`; React omits undefined attrs); `PrStateBadge` no call-site default for out-of-set state (mapper guarantees the 4 states).
