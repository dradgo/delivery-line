# Story 3.27: Artifact Review Panel — PR/Output Variant Renderer

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Developer reviewing the agent's PR/output (the actual code change with branch + commit + PR refs),
I want the `PrOutputArtifactRenderer` (stub from story 2.17 AC3) fully implemented with diff display + branch/commit/PR reference panels,
so that the PR/output variant gives developers everything they need to decide accept / reject / takeover without leaving the DeliveryLine UI for routine cases.

## Acceptance Criteria

1. `src/features/workflows/components/PrOutputArtifactRenderer.tsx` stub (story 2.17 AC3 "coming in Epic 3" placeholder) is replaced with a real renderer for the `prOutput` artifact variant, modeling the runner-contracts schema-v1 `prOutputArtifact` sub-schema (story 1.6 AC4 — `branch`, `commitSha`, `prReference`, `diffReference`).
2. Given the artifact payload + linked GitHub PR (from story 3.15), the renderer displays:
   - artifact **title**, artifact **type badge** (`pr-output`), **revision indicator** (`v{version}` + deferred history anchor) — mirroring `SpecArtifactRenderer` chrome.
   - **branch reference** (clickable; opens the GitHub branch view in a new tab — story 3.9 AC2).
   - **commit SHA** rendered short-form (7 chars) with a **copy button** (copies the full SHA) and clickable to the GitHub commit view.
   - **PR reference** (`org/repo#42`) with a **state badge** (`draft` / `open` / `merged` / `closed`) sourced from `integration_links.external_metadata.prState` (story 3.15 AC1), clickable to open the GitHub PR view.
   - **diff display** rendered through the story-2.24 sanitization pipeline: file-by-file accordion, additions/deletions in semantic `<ins>` / `<del>` with stable token classes (`diff-line-added` / `diff-line-removed` / `diff-line-context` — same convention as `SafeDiffRenderer`), diff syntax treatment, and **no execution of embedded scripts**.
3. Given runner output is untrusted (story 2.24), diff content + file paths + commit messages all pass through the sanctioned text path (`renderTextWithRedactions` / `SafeMarkdownRenderer` from the `@/lib/sanitization` barrel). XSS fixtures from story 2.24 AC7 are exercised against the diff body. Metadata-spoofing protection (story 2.24 AC6) is explicit: **trusted system metadata** (the PR reference + state — backend truth from `integration_links`) is visually and structurally separated from **untrusted runner-emitted content** (the diff body, file paths, branch/commit values).
4. Given the discriminated-union dispatch from story 2.17 AC1, when `useArtifact(artifactId)` returns an artifact with `artifactType==='prOutput'`, `ArtifactReviewPanel` dispatches to this renderer (the dispatch arm already exists at `ArtifactReviewPanel.tsx:77` — this story makes the target real and threads any new props through `renderVariant`).
5. Given large-diff handling, files are paginated/virtualized when the diff exceeds a documented threshold (**50 files OR 5000 changed lines** — exported as named constants). Each file is **collapsed by default** with summary stats (path + `+adds`/`−dels`); the user can expand individual files. When over the file-count threshold, files beyond the first page are not rendered until paged in, and a clear "showing N of M files" affordance is shown (no silent truncation).
6. Given GitHub link reconciliation per NFR17 (story 3.15 AC3), when GitHub is unreachable but the `integration_links` row carries `external_metadata.prState`, the renderer still displays the cached state with a "(last synced X ago)" affordance.
7. Given allowed-actions integration, the renderer is gated by `useAllowedActions(workflowRunId)` (read by the container, threaded down as props per the story-2.17 presentational pattern) — variant-specific controls enable/disable strictly from backend-reported actions, never frontend inference (UX-DR12).
8. Given keyboard accessibility, the file accordion is fully keyboard operable (Tab to a file header, Enter/Space to expand/collapse, Tab into the diff body); a documented jump-to-changed-region keyboard affordance (next/previous changed file or hunk) is provided and reachable without a mouse.
9. Given the frontend-owned artifact fixtures, at least one `prOutput` fixture with a realistic small diff (3–5 files, ~100 changed lines) exists; tests render against it. (Mirrors the story-2.17 fixture reconciliation — the backend fixture event streams are NOT served to the SPA, so these are frontend-owned copies of the intended read model.)
10. Given component test coverage, tests cover: branch + commit + PR refs render with correct GitHub URLs; state badge reflects `prState` (all four values + non-color signifier); diff display renders correctly (`<ins>`/`<del>` + token classes); sanitization neutralizes scriptable payloads in diff content + file paths + commit messages (no active `<script>`/`<iframe>`, `javascript:` links neutralized); file pagination works at threshold; GitHub-unreachable cached-state rendering with "(last synced X ago)"; large-diff render does not enumerate every file when over threshold; keyboard navigation through the file accordion + jump-to-changed-region; axe-core a11y scan reports zero violations.

## Tasks / Subtasks

- [x] **Task 1 — Extend the frontend-owned `PrOutputArtifactView` read model** (AC: #1, #2, #3, #6)
  - [x] In `src/features/workflows/artifactView.ts`, replaced the bare `PrOutputArtifactView` with the prOutput-specific shape; kept `body` (untrusted markdown PR description) from the base.
  - [x] Added **runner-emitted (UNTRUSTED)** fields: `branch: string`, `commitSha: string`, `diff: string` (the resolved `diffReference` content). Documented that `diffReference` is a storage *ref* on the wire; the frontend-owned view carries the resolved `diff` text (no live artifact-read endpoint).
  - [x] Added the **backend-truth** `prLinkage: { prReference; prState; prUrl?; lastSyncedAt?; githubReachable? } | null` slot + exported `PrState`/`PrLinkage` types — the AC3 metadata-spoofing boundary.
  - [x] Extended `isArtifactView` so the `prOutput` branch validates the required string fields + the optional `prLinkage` shape (new `isValidPrLinkage` accepts undefined/null or a valid record).
  - [x] `artifactTypeLabel` `prOutput → 'pr-output'` already present — no change needed.
- [x] **Task 2 — Pure unified-diff parsing + sanitized diff rendering** (AC: #2, #3, #5)
  - [x] Added the **pure parser** `src/lib/sanitization/unifiedDiff.ts` (no JSX, unit-tested): parses `diff --git`/`+++`/`---`/`@@`/`+`/`-`/` ` into `{ path, oldPath?, additions, deletions, hunks }[]`. Defensive — malformed/headerless diffs fall back to a single synthetic file (never throws); empty diff → `[]`.
  - [x] Added the **sanctioned primitive** `src/lib/sanitization/SafeUnifiedDiffRenderer.tsx` (exported from the barrel). Renders hunks with `<ins className="diff-line-added">`/`<del className="diff-line-removed">`/`<span className="diff-line-context">`, every line + hunk header through `renderTextWithRedactions`. Honors a `maxLines` cap with a "showing N of M" note.
  - [x] Exported `PR_DIFF_MAX_FILES = 50` + `PR_DIFF_MAX_LINES = 5000` from `unifiedDiff.ts` (re-exported via the barrel).
- [x] **Task 3 — GitHub reference helpers + PR state badge** (AC: #2, #6)
  - [x] Added `src/features/workflows/githubRef.ts` (pure `.ts`): `parsePrReference('org/repo#42')`, `prUrl`, `branchUrl`, `commitUrl`, `shortSha`. Branch/commit URLs derive `owner/repo` from the backend-truth `prLinkage.prReference`; the renderer prefers a validated `prLinkage.prUrl` verbatim for the PR link.
  - [x] Added `PrStateBadge` (`src/features/workflows/components/PrStateBadge.tsx`) composing `Badge` (NOT `WorkflowStateBadge`); per-state icon + text label (non-colour signifiers) for `draft`/`open`/`merged`/`closed`. Reusable for story 3.31.
- [x] **Task 4 — Implement the real `PrOutputArtifactRenderer`** (AC: #1, #2, #3, #5, #7, #8)
  - [x] Replaced the stub body: type badge + revision chrome, **trusted reference panel** (branch link, commit short-form + copy button + commit link, PR ref + `PrStateBadge` + PR link, last-sync affordance), **PR description body** via `MetadataChrome` + `SafeMarkdownRenderer`, then the **diff display**.
  - [x] Diff accordion (Radix `type="multiple"`): one collapsible section per file (collapsed by default), header shows the sanitized path + `+adds`/`−dels`; expanding renders the file's hunks via `SafeUnifiedDiffRenderer`. Trusted panel (`data-region="trusted-references"`) is structurally + visually separated from the untrusted diff (`data-region="untrusted-diff"`, "from the agent's output (untrusted)" annotation).
  - [x] Pagination (AC5): over `PR_DIFF_MAX_FILES`, only the first page renders + a "Show more files (N of M)" control; per-file expansion is capped at `PR_DIFF_MAX_LINES` (note surfaced). No silent truncation — counts always shown.
  - [x] AC7: accepts `actions?: readonly string[]` from the panel and gates the reserved Compare control strictly on it.
  - [x] AC8 keyboard: native-`<button>` file headers (Enter/Space toggle + Radix arrow-key nav); a jump-to-changed-region control (Previous/Next changed file) moves focus between file headers via a focus-tracking ref.
- [x] **Task 5 — Thread props through the panel + container** (AC: #4, #7)
  - [x] `renderVariant` now passes `actions` to the `prOutput` arm; `ArtifactReviewPanel` gained an `actions` prop; `ArtifactReviewPanelContainer` forwards its `useAllowedActions` `actions` to the panel.
  - [x] Unknown-discriminant `UnsupportedArtifact` fallback untouched (verified by the panel test).
- [x] **Task 6 — Fixtures** (AC: #9, #3, #6, #10)
  - [x] Extended `prOutputArtifactView` to the new shape with a realistic small diff (3 files, ~100 changed lines) + a populated `prLinkage` (`open`, `acme/widgets#42`, `lastSyncedAt`).
  - [x] Added `prOutputArtifactViewXss` (script in body + a file path + diff body + a `javascript:` link), `prOutputArtifactViewLargeDiff` (`PR_DIFF_MAX_FILES + 5` files), `prOutputArtifactViewStaleGitHub` (`githubReachable: false` + old `lastSyncedAt`).
- [x] **Task 7 — Tests** (AC: #10)
  - [x] Created `src/features/workflows/components/PrOutputArtifactRenderer.test.tsx` covering every AC10 bullet (router/query-free, fixture-driven) incl. `expectNoA11yViolations`.
  - [x] Added `src/lib/sanitization/__tests__/SafeUnifiedDiffRenderer.test.tsx` + `src/lib/sanitization/__tests__/unifiedDiff.test.ts` (parse correctness + malformed-diff fallback). Also added `githubRef.test.ts`, `PrStateBadge.test.tsx`, and a prOutput guard case in `artifactView.test.ts`.
  - [x] **`StubArtifactRenderers.test.tsx` DELETED** (deviation — see Completion Notes): removing the two prOutput `describe` blocks would have emptied the file (3.26 already removed the impl-plan stub blocks; the story task text predates that), and an empty test file fails Vitest (no `passWithNoTests`).
  - [x] Updated the two stale `ArtifactReviewPanel.test.tsx` descriptions ("stub renderer" → real); no stub-notice assertion existed to migrate. The `pr-output-artifact-renderer` testid is unchanged so those assertions stay green.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Added a field-only structured log on the GitHub-unreachable cached-state path in the renderer: `console.warn({ event: 'prOutput.githubUnreachable', prState, staleForMs })` — never the diff body, PR token, or payload bytes.
  - [x] Pinned with a `console.warn` spy assertion in the AC6 renderer test.

## Review Findings

_Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) — 2026-06-14. Acceptance Auditor verdict: all 10 ACs SATISFIED; both documented deviations (StubArtifactRenderers.test.tsx deletion, live `toArtifactView` empty-defaults) sound. No AC violations. All 5 patch findings fixed 2026-06-14 (tsc/eslint/prettier clean, 934 vitest tests green)._

- [x] [Review][Patch] `branchUrl` open-redirect via `..` path segments — `branch` is untrusted runner output; `encodeBranchPath` splits on `/` and `encodeURIComponent`s each segment, which does NOT escape `.`/`..`. A branch like `../../attacker/evil` yields `href=".../tree/../../attacker/evil"`, which the browser normalizes to a different repo/page under github.com — defeating the file's own stated premise ("a spoofed runner value can never redirect the link to an attacker-chosen repo"). Fix: drop/reject `.`/`..` segments (or any empty segment) in `encodeBranchPath`. `commitUrl`/`prUrl` are safe (single `encodeURIComponent`, no slash-split). [deliveryline-frontend/src/features/workflows/githubRef.ts:48] [blind]
- [x] [Review][Patch] Copy-SHA reports success unconditionally and never resets — `handleCopySha` calls `setCopied(true)` regardless of whether `navigator.clipboard` exists or `writeText` rejects (the `void clipboard?.writeText(...)` is fire-and-forget), and there is no timeout to revert the label to "copy". UI permanently claims "copied" after one click, and claims it even when nothing was written. Fix: set `copied` on promise resolution only, and reset after a short timeout. [deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.tsx:133-139] [blind+edge]
- [x] [Review][Patch] `parsePrReference` repo group permits `/` → wrong owner/repo split — regex `^([^/\s]+)\/([^#\s]+)#(\d+)$` lets the repo capture include `/`, so `a/b/c#1` parses to `owner='a', repo='b/c'` and produces a plausible-but-wrong URL instead of `null`. Fix: tighten the repo group to `[^/#\s]+`. [deliveryline-frontend/src/features/workflows/githubRef.ts:29] [blind+edge]
- [x] [Review][Patch] Binary-file diff line rendered as a 0/0 context line — no handler for `Binary files … differ` / `GIT binary patch`; the line has no `+`/`-`/space prefix so it falls to the prefix-less `else` and renders verbatim as context, with `+0/−0` stats. Add a binary-file affordance. [deliveryline-frontend/src/lib/sanitization/unifiedDiff.ts:184-187] [edge]
- [x] [Review][Patch] Non-`diff --git` pure-deletion shows `/dev/null` as the path — for a headerless `--- a/foo` / `+++ /dev/null` deletion, `current.path` is set to `/dev/null` (non-empty) so the final-map `path === ''` substitution to `oldPath` never fires, displaying `/dev/null` instead of the deleted path. (Git-style diffs with a `diff --git` header are unaffected — path is set from the header first.) Fix: treat `+++ /dev/null` as a deletion (don't overwrite path). [deliveryline-frontend/src/lib/sanitization/unifiedDiff.ts:140-148,192] [edge]
- [x] [Review][Defer] Parser has no input-size bound before fully parsing untrusted diff — `parseUnifiedDiff` `split('\n')`s the entire untrusted diff into per-line objects; `PR_DIFF_MAX_FILES`/`PR_DIFF_MAX_LINES` are applied at render time only, not at parse time. Deferred — presentational/fixture-driven with no live `prOutput` read model yet; revisit when a live source is wired. [deliveryline-frontend/src/lib/sanitization/unifiedDiff.ts:99-122] [blind]

**Dismissed as noise (13):** URLs built from a hardcoded `https://github.com` base are not run through `validateUrlScheme` (scheme injection impossible); `PrStateBadge` "throws on unknown state" (prop typed `PrState` + `isValidPrLinkage` rejects non-enum values — unreachable); `lastFocusedFileIndex` stale index (the `% count` modulo keeps it in range — no crash/no-op); live `toArtifactView` empty-defaults provide no guard protection (documented + auditor-confirmed intentional); `githubReachable: undefined` treated as reachable (reasonable default); future `lastSyncedAt` shows "synced in X" (clock-skew, rare); malformed `@@` accepted as a header (intentional never-throw defensiveness); rename-without-hunk metadata loss (uncommon, degrades fine); `staleForMs` log snapshot drift (logs are point-in-time by nature); per-file cap counts context but "large diff" note counts changed-only (per-file "showing N of M" note still fires — not silent); hunk headers render past the line cap + "changed lines" wording counts context (cosmetic); empty `branch`/`commitSha` render empty-text (only via non-live fixture path where `prLinkage` is non-null but values empty); `isValidPrLinkage` accepts empty/garbage `prReference` (renderer degrades to plain text, no crash); CRLF `\r` (git emits LF headers; `\r` only reaches inert content text).

## Dev Notes

### Central reconciliation (READ FIRST — same shape as story 2.17)

There is **no backend `ArtifactView` type and no artifact-read endpoint**. `useArtifact` is a **disabled stub**; `schema.d.ts` carries only `WorkflowDetail.latestArtifacts[]` summaries (`artifactType?`, `status?`, `version?` — no body, no id, no diff). Therefore:

- `ArtifactView` (incl. `PrOutputArtifactView`) is a **frontend-owned** type modeling the epic's intended read model. The future artifact-read story will populate it; today it has **no live source** and the renderer is **presentational** — every render in tests is driven by constructed fixtures. [Source: deliveryline-frontend/src/features/workflows/artifactView.ts:1-31]
- `diffReference` on the runner-result wire is a **storage reference**, not the diff bytes. The frontend-owned `PrOutputArtifactView.diff` carries the *resolved* diff text so the renderer has something to display in this presentational era. Do NOT invent a fetch for it.
- `ArtifactReviewPanel` is **presentational**; `ArtifactReviewPanelContainer` is the thin data seam that reads the disabled hooks. Keep the new renderer presentational + prop-driven (mirror `SpecArtifactRenderer`, which takes `compareEnabled` from the panel rather than reading a hook). [Source: deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:11-23, 239-258]

### The dispatch is already wired

`ArtifactReviewPanel.renderVariant` already has the `case 'prOutput': return <PrOutputArtifactRenderer artifact={artifact} />;` arm (`ArtifactReviewPanel.tsx:77`). AC4 is structurally satisfied — this story just makes the target real and (for AC7) threads an allowed-actions prop through that arm. The unknown-discriminant `UnsupportedArtifact` fallback stays untouched. [Source: deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:71-85]

### Sanitization is THE hard requirement — consume sanctioned primitives, never roll your own

Story 2.24 established the only sanctioned import surface: the `@/lib/sanitization` barrel. The `no-unsanitized-html` ESLint rule + `--max-warnings=0` forbid `dangerouslySetInnerHTML` and direct package-internal imports outside `src/lib/sanitization/**`. The available primitives:

- `SafeMarkdownRenderer` — untrusted markdown → sanitized React (rehype-sanitize + Shiki highlight, redaction second-pass). Use for the PR description `body`.
- `SafeDiffRenderer` — before/after two-column diff (NOT unified-diff/file-grouped). Reuse its **stable token-class convention** (`diff-line-added`/`diff-line-removed`/`diff-line-context`) but it does not do file grouping, so this story adds a unified-diff primitive.
- `renderTextWithRedactions(text)` — wraps untrusted text in inert React text nodes + `<mark class="redaction-applied">` for secrets. **This is the sanctioned path for per-line diff text.**
- `MetadataChrome` — the trusted typed-metadata wrapper.

[Source: deliveryline-frontend/src/lib/sanitization/index.ts, SafeDiffRenderer.tsx:90-140, SafeMarkdownRenderer.tsx:305-324]

**Recommended diff architecture** (the central design decision — see Questions): put the untrusted-text→DOM concern in the sanctioned package as a new `SafeUnifiedDiffRenderer` primitive that parses + renders hunks via `renderTextWithRedactions`; keep the accordion / keyboard / pagination *chrome* in the feature-layer `PrOutputArtifactRenderer`. This keeps the sanitization boundary inside `src/lib/sanitization/**` and out of ESLint's way, and gives story 3.31 a reusable diff primitive.

**Syntax highlighting (AC2):** Shiki already loads the `diff` grammar (`SafeMarkdownRenderer.tsx:62`). Scope "syntax highlighting" to **diff-level** treatment (add/remove/context/hunk-header coloring via the stable token classes). Full intra-line *per-language* token highlighting (TS/JSON tokens inside a `+` line) is heavier and not required for E3 — note it as a deferred enhancement. (Flagged as a decision below.)

### Trusted vs untrusted boundary (AC3 — metadata-spoofing protection)

| Field | Source | Trust | Display rule |
|-------|--------|-------|--------------|
| PR reference `org/repo#42` + state badge | `integration_links.external_metadata.prState` (backend) | **TRUSTED** | Authoritative; render in the trusted reference panel |
| PR URL, last-sync timestamp | `integration_links` (backend) | **TRUSTED** | Trusted panel |
| branch, commitSha | runner-emitted artifact | untrusted | Render as escaped text/code; build branch/commit URLs from the **backend-truth** `owner/repo` (parsed from `prLinkage.prReference`) + these values |
| diff body, file paths, commit messages | runner-emitted | untrusted | MUST route through `renderTextWithRedactions` / `SafeMarkdownRenderer` |

The renderer must make this boundary visible (labeled regions) — the PR ref a developer trusts must never be confusable with a runner-emitted echo. [Source: epic-03-agent-execution.md:550 (story 3.27 AC3), story 3.15 AC1/AC3]

### Project Structure Notes

- New presentational renderer replaces the stub in place: `src/features/workflows/components/PrOutputArtifactRenderer.tsx`.
- Pure helpers go in `.ts` siblings (`unifiedDiff.ts` in the sanitization package; `githubRef.ts` in `features/workflows`) — a `.tsx` exporting a non-component function trips the `frontend-react-refresh-no-fn-exports` ESLint gate. [Source memory: frontend-react-refresh-no-fn-exports]
- The sanctioned diff primitive + its barrel export live in `src/lib/sanitization/`. Consumers import only from `@/lib/sanitization` (the barrel).
- `WorkflowDetail` TS types serialize nullable fields as JSON **null**, not `undefined` — guard `prLinkage`/`lastSyncedAt` with `!= null` before string ops if/when a live wire appears. [Source memory: workflowdetail-wire-sends-null-not-undefined]

### Testing standards summary

- **Vitest + Testing Library**, tests colocated `*.test.tsx`. Render the renderer directly with fixtures (router/query-free) — the pattern `SpecArtifactRenderer.test.tsx` uses. [Source: deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx:18-24]
- **a11y:** `expectNoA11yViolations(container)` from `@/test/a11y/axe`; keyboard reachability via `expectTabReachesAll` from `@/test/a11y/keyboard`. [Source: deliveryline-frontend/src/test/a11y/keyboard.ts:42-49]
- **Sanitization assertions** prove the renderer *routes* untrusted content through the safe path (no active `<script>`/`<iframe>`, `javascript:` neutralized) — do NOT re-test the sanitizer internals (that is story 2.24's own suite). [Source: artifactViewFixtures.ts:72-87]
- A shared module mocked across multiple test files races under Vitest's per-worker registry — keep same-module mocks in one file. [Source memory: vitest-cross-file-router-mock]
- Run the focused story tests, then the full `vitest` run (the QueueShell announcer flake is unrelated — assert announcer text via `waitFor`). Run `prettier --write` + the lint/`lint:rules-test` gates before declaring done — one unformatted frontend file cascades the whole CI. [Source memory: prettier-gate-cascades-ci, livesnnouncement-defers-one-commit-test-flake]

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **This story is frontend-only** — the backend SLF4J/Logback/MDC standard below does not apply. The SPA's sanctioned logging is the field-only structured `console.warn`/`console.info` pattern already used by `ArtifactReviewPanelContainer` / `RunContextStrip` (`{ event, code, transport }` — stable codes + flags, NEVER raw error messages, diff bytes, tokens, or PII).
- **Where to log (this story):** add a field-only log on the GitHub-unreachable cached-state branch (AC6) in the container/renderer. Pin it with a `console.warn` spy assertion.
- **Forbidden in log output:** diff content, PR tokens, payload bytes, raw PII (T8). Never log the untrusted body.

_(The backend logging standard — SLF4J + Logback, INFO/WARN/ERROR on service entry/exit + state transitions + adapter writes, MDC `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity`, focused log-assertion test — applies to backend stories and is retained here for reference only.)_

### Project Structure Notes

- Alignment: frontend feature-sliced under `src/features/workflows/`; sanctioned sanitization under `src/lib/sanitization/`; shared UI primitives under `src/components/ui/`.
- No backend, OpenAPI, or runner-contracts changes in this story — it is presentational and fixture-driven (no live `prOutput` read model exists yet).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.27 (lines 540-557)] — the authoritative AC list.
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json#prOutputArtifact (lines 203-240)] — `branch`, `commitSha` (7–40 hex), `prReference`, `diffReference` field shapes (story 1.6 AC4).
- [Source: deliveryline-frontend/src/features/workflows/artifactView.ts] — the frontend-owned `ArtifactView` union + `isArtifactView` guard + `artifactTypeLabel`.
- [Source: deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx] — dispatch + presentational/container split + `compareEnabled` precedent for AC7 prop threading.
- [Source: deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx] — the only fully-built ARP variant; copy its chrome (type badge, revision indicator, MetadataChrome + SafeMarkdownRenderer body, keyboard anchor nav).
- [Source: deliveryline-frontend/src/lib/sanitization/{index.ts,SafeDiffRenderer.tsx,SafeMarkdownRenderer.tsx}] — sanctioned primitives + stable diff token classes + Shiki `diff` grammar.
- [Source: deliveryline-frontend/src/test/fixtures/artifact/artifactViewFixtures.ts] — extend `prOutputArtifactView`; add XSS / large-diff / stale-GitHub siblings.
- [Source: deliveryline-frontend/src/features/workflows/components/StubArtifactRenderers.test.tsx] — surgically remove ONLY the prOutput stub blocks (3.26 impl-plan stub must stay green).
- [Source: epic-03-agent-execution.md#Story-3.31] — the sibling PR-linkage display; reuse the `githubRef`/`PrStateBadge` helpers there for visual consistency.

### Open questions / decisions (raised after the story was drafted)

1. **Diff-rendering placement** — recommended: a new sanctioned `SafeUnifiedDiffRenderer` primitive in `src/lib/sanitization/` (parser + per-line redacted rendering), with the accordion/keyboard/pagination chrome in the feature renderer. Alternative: keep all of it in the feature renderer using only the barrel's `renderTextWithRedactions`. The sanctioned-primitive split is cleaner for story 3.31 reuse and avoids any ESLint-rule friction — confirm before implementing.
2. **Syntax-highlighting depth** — recommended: diff-level coloring (add/del/context/hunk) via stable token classes; full per-language intra-line token highlighting deferred. Confirm this satisfies AC2's "syntax highlighting" for E3 scope.
3. **Shared PR helper extraction now vs in 3.31** — recommended: build the focused `githubRef.ts` + `PrStateBadge` in this story (3.27 lands first) and have 3.31 consume them. Confirm you don't want them deferred/local.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (claude-opus-4-8[1m]) — bmad-dev-story

### Debug Log References

- Focused + full Vitest run (PowerShell — Bash is RTK-corrupted, [[rtk-hook-only-matches-bash]]): **85 files / 934 tests pass**.
- `tsc -b`: clean (fixed two `exactOptionalPropertyTypes` issues in the parser + the live `toArtifactView` prOutput mapper which now must supply branch/commit/diff).
- `eslint . --max-warnings=0`: clean; `lint:rules-test` 9/9 (the `no-unsanitized-html` rule is satisfied — the only untrusted-text→DOM site is the new `SafeUnifiedDiffRenderer` inside `src/lib/sanitization/**`). Prettier clean.
- Use the project-local ESLint (`node node_modules/eslint/bin/eslint.js …`) — `npx eslint` resolved a mismatched cached ESLint 10.x and crashed on the React plugin.

### Completion Notes List

- Replaced the story-2.17 `PrOutputArtifactRenderer` stub with a real renderer: trusted reference panel (branch/commit/PR refs + `PrStateBadge` + copy-full-SHA + last-sync) structurally separated from an untrusted sanitized unified-diff file accordion. All 10 ACs satisfied.
- **Open-question decisions taken (the Dev Notes recommendations):** (1) the diff rendering is split into a sanctioned `SafeUnifiedDiffRenderer` primitive (parser + per-line redacted rendering) in `src/lib/sanitization/`, with accordion/keyboard/pagination chrome in the feature renderer — keeps the sanitization boundary inside the package and gives story 3.31 a reusable primitive; (2) "syntax highlighting" scoped to diff-LEVEL token-class coloring (per-language intra-line highlighting deferred to E4); (3) built `githubRef.ts` + `PrStateBadge` now (3.27 lands first) for 3.31 to consume.
- **Deviation — `StubArtifactRenderers.test.tsx` was DELETED, not surgically edited.** The story task said to remove only the two prOutput `describe` blocks and keep the file "because story 3.26's impl-plan stub blocks must stay green," but 3.26 is already `done` and its stub blocks were removed when it landed — the file's only remaining content was the prOutput stubs. Removing them empties the file, and Vitest has no `passWithNoTests` so an empty test file fails. Deleting the now-obsolete file (every artifact variant has a real renderer + its own test) is the faithful resolution of the task's intent. No production code imports it.
- **Live mapper reconciliation:** `toArtifactView` (`queryOptions.ts`, story 3a-9) now maps a live `prOutput` artifact to empty `branch`/`commitSha`/`diff` + `prLinkage: null` (the 3a-9 wire carries none of these yet). The renderer degrades gracefully (empty diff → "No diff content", null linkage → "No linked pull request"); a future read-model story populates them. This keeps `isArtifactView` passing on the live path.
- No backend / OpenAPI / runner-contracts / `schema.d.ts` / npm / lockfile changes — presentational + fixture-driven, as scoped.

### File List

**Added**
- `deliveryline-frontend/src/lib/sanitization/unifiedDiff.ts` — pure unified-diff parser + `PR_DIFF_MAX_FILES`/`PR_DIFF_MAX_LINES`/`countChangedLines`.
- `deliveryline-frontend/src/lib/sanitization/SafeUnifiedDiffRenderer.tsx` — sanctioned diff primitive.
- `deliveryline-frontend/src/lib/sanitization/__tests__/unifiedDiff.test.ts` — parser unit tests.
- `deliveryline-frontend/src/lib/sanitization/__tests__/SafeUnifiedDiffRenderer.test.tsx` — primitive tests.
- `deliveryline-frontend/src/features/workflows/githubRef.ts` — PR-ref parsing + URL builders.
- `deliveryline-frontend/src/features/workflows/githubRef.test.ts` — helper unit tests.
- `deliveryline-frontend/src/features/workflows/components/PrStateBadge.tsx` — reusable PR-state badge.
- `deliveryline-frontend/src/features/workflows/components/PrStateBadge.test.tsx` — badge tests.
- `deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.test.tsx` — renderer tests (AC10).

**Modified**
- `deliveryline-frontend/src/features/workflows/artifactView.ts` — `PrOutputArtifactView` shape + `PrState`/`PrLinkage` types + `isValidPrLinkage` + tightened `isArtifactView` prOutput branch.
- `deliveryline-frontend/src/features/workflows/artifactView.test.ts` — prOutput guard test case.
- `deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.tsx` — the real renderer (replaced stub).
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx` — thread `actions` through `renderVariant` + panel + container.
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx` — updated two stale "stub" descriptions.
- `deliveryline-frontend/src/lib/sanitization/index.ts` — barrel exports for the new primitive + parser.
- `deliveryline-frontend/src/lib/api/queryOptions.ts` — live `toArtifactView` prOutput mapping to empty defaults.
- `deliveryline-frontend/src/test/fixtures/artifact/artifactViewFixtures.ts` — extended `prOutputArtifactView` + 3 sibling fixtures.

**Deleted**
- `deliveryline-frontend/src/features/workflows/components/StubArtifactRenderers.test.tsx` — obsolete (see Completion Notes).

### Change Log

| Date       | Version | Description                                                                 |
| ---------- | ------- | --------------------------------------------------------------------------- |
| 2026-06-14 | 0.1     | Story 3.27 implemented — real PR/Output variant renderer + sanctioned unified-diff primitive + GitHub-ref/PR-state helpers; all 10 ACs, 934 frontend tests green. Status → review. |
