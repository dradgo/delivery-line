# Story 3g.2: FE — Ticket Title in Queue + Origin Block

Status: done

<!-- 2026-07-02 bmad-create-story context-engine pass (Opus 4.8 [1m]). Target sprint key: 3g-2-fe-ticket-title-in-queue-and-origin-block. Second Epic 3g story (FR73 FE half). Source: epic-03g-provenance-token-accounting.md (Story 3g-2) + the delivered 3g-1 backend (3g-1-ticket-origin-snapshot-and-read-model.md, status review). Consumes 3g-1's already-regenerated schema.d.ts. -->

> **READ FIRST — this is a FE-only, read-only consumption story.** The entire backend + read-model + `schema.d.ts` regeneration was delivered by **3g-1** (status `review`). You are **not** adding any backend, OpenAPI, or contract change here, and you are **not** touching token accounting (that is 3g-3/3g-4). Do **not** regenerate `schema.d.ts` — 3g-1 already committed it; just **verify `check:api` is green** and consume the three new fields.
>
> **The three fields you consume already exist in `deliveryline-frontend/src/lib/api/schema.d.ts`:**
> - `WorkflowSummary.ticketTitle?: string | null` (queue) — verified at `schema.d.ts:1773`.
> - `LinkedTicket.title?: string | null` and `LinkedTicket.url?: string | null` (detail) — verified at `schema.d.ts:1040`/`:1045`; `LinkedTicket` also already carries `externalRef` + `integrationType`.
>
> **CRITICAL NULL POSTURE:** all three fields are typed `string | null` (nullable, JSON `null` on the wire for a pre-3g / unlinked run, or a `null` url when the connector cannot build one). Guard reads with **`!= null`**, never `=== undefined` — the wire sends `null`, not absent (the documented `workflowdetail-wire-sends-null-not-undefined` trap). Map to `?? undefined` at the view-model seam to satisfy `exactOptionalPropertyTypes`.

## Story

As an authorized user,
I want the originating ticket title in the run review queue and a small "Origin" block on the detail page,
so that runs are human-identifiable at a glance and I can click through to the source ticket.

## Acceptance Criteria

1. **Given** the (already-regenerated) `schema.d.ts` and the `WorkflowSummary → RunQueueRow` seam, **Then** the queue run row renders `ticketTitle` as the run's **human label** and **falls back to `ticketRef`** when `ticketTitle` is `null`/absent (pre-3g / unlinked parity — **never a blank cell**). `ticketRef` remains visible as the machine identity (it must not disappear — it is still the aria-label identity and the `<code>` machine id); the title becomes the prominent human-readable label alongside it.
2. **Given** the detail page, **Then** a minimal **Origin** block renders the originating ticket's `title`, `ticketRef` (`externalRef`), and `integrationType`, plus a **link-out** to the source ticket. The link-out is rendered **only when `LinkedTicket.url != null`** and is **omitted entirely** (no dead/`#`/`javascript:` anchor, no disabled control) when `url` is `null`. The block renders nothing at all when there is no linked ticket (no `linkedTicket`, or a `linkedTicket` with a `null` title — mirror the "render only when present" discipline of `PrLinkageDetails`/`RunDependencyPanel`, never an empty placeholder card).
3. **Given** origin depth is deliberately minimal (the locked origin-depth decision), **Then** the Origin block shows **title + ref + integrationType + link only** — it does **not** render the full original ticket body, the initiating prompt, or any other ticket metadata.
4. **Given** accessibility, **Then** the queue title cell, the Origin block, and the link-out meet WCAG 2.1 AA and are **axe-clean** (via `expectNoA11yViolations`). The link-out has an **accessible name that distinguishes it as the external source ticket** (e.g. `aria-label="Open source ticket DEL-1234 (opens in a new tab)"`), opens in a new tab safely (`target="_blank" rel="noopener noreferrer"`), and is never color-alone.
5. **Given** the FE rendering traps, **Then**: any new pure helper/mapper lives in a **sibling `.ts`** (not the `.tsx` — the `frontend-react-refresh-no-fn-exports` rule; a `.tsx` may export only components); wire reads guard **`!= null`** (not `undefined`); the title/URL are treated as **untrusted plain text** — React-escaped, **no** `dangerouslySetInnerHTML` / `SafeMarkdownRenderer` (Trap T6); and **if** you add an announcer reflecting the loaded title, assert it via `waitFor` (the `useLiveAnnouncement` one-commit-lag trap) — an announcer is **optional** for this story, only add one if it adds value.
6. **Given** tests, **Then** Vitest covers: title render in the queue row; **ref fallback** when `ticketTitle == null`; the aria-label still carries the ref identity; Origin block fields (title, ref, integrationType); link-out **present when `url` set / absent when `url` null**; Origin block **absent** when there is no linked ticket; the queue row and Origin block are **axe-clean**. No new backend/contract test (there is no backend change here); `check:api` + `npm run build` (tsc + vite) + lint stay green.

## Tasks / Subtasks

- [x] **Task 0 — Verify the consumed contract is already in place (no regen)** (AC: 1, 2)
  - [x] Confirm `WorkflowSummary.ticketTitle`, `LinkedTicket.title`, `LinkedTicket.url` exist in `deliveryline-frontend/src/lib/api/schema.d.ts` (they do — 3g-1 committed them). Run `npm run check:api` and confirm green. **Do NOT run `generate-api` / regenerate** — 3g-1 owns that regen; re-running it here would produce a spurious diff.

- [x] **Task 1 — Queue: surface `ticketTitle` with `ticketRef` fallback** (AC: 1, 5, 6)
  - [x] `features/workflows/runQueueRow.ts`: add a nullable `ticketTitle?: string | undefined` field to the `RunQueueRow` interface (place it next to `linearTicketReference`, `:35`), and in `toRunQueueRow` (`:117`) map `ticketTitle: summary.ticketTitle ?? undefined` (the `?? undefined` collapses the wire `null` — the `exactOptionalPropertyTypes` pattern already used for `projectId`/`projectName` at `:127-129`).
  - [x] `features/workflows/components/RunReviewQueueItem.tsx` — the `Identity` component (`:207`): render the **human label** preferring `row.ticketTitle`, falling back to `row.linearTicketReference` when the title is absent (never a blank label). Keep `linearTicketReference` visible as the machine identity (it currently is the bold label + feeds `<code>{row.runId}</code>` and the aria-label). Suggested anatomy: `«Human title»  ·  DEL-1234  ·  run_abc…` — title prominent, ref as secondary text. When there is no title, the ref takes the prominent slot (today's exact behavior — parity). Title is React-escaped plain text (Trap T6).
  - [x] `composeAriaLabel` (`:179`): include the human title in the identity segment when present, but **keep `linearTicketReference` in the aria identity** (it is the stable machine id reviewers search by). Never emit the literal `"undefined"` (existing `.filter(Boolean)` idiom).
  - [x] Any new pure helper (e.g. a `queueRowLabel(row)` picker) → put it in `runQueueRow.ts` (a `.ts`), not the `.tsx` (react-refresh rule). Prefer inline expression if trivial.

- [x] **Task 2 — Detail: the Origin view mapper (sibling `.ts`)** (AC: 2, 3, 5)
  - [x] Add `features/workflows/runOriginView.ts` — a pure mapper `toRunOriginView(detail: WorkflowDetail): RunOriginView | undefined` mirroring the `runContextView.ts` / `prLinkageView.ts` sibling-mapper pattern. It reads **only** `detail.linkedTicket` (`title`, `externalRef`, `integrationType`, `url`); returns `undefined` when there is no `linkedTicket` **or** `title == null` (nothing meaningful to show — AC2 "render nothing" gate). `RunOriginView` carries exactly: `title: string`, `ticketRef: string | undefined`, `integrationType: string | undefined`, `url: string | undefined` (all coalesced via a `!= null` guard). **Do NOT** add body/prompt fields (AC3, locked origin depth).
  - [x] Optional hardening: only surface `url` when it is an `http(s)` absolute URL (defensive against a stored non-http scheme). 3g-1 builds `https://linear.app/...` / `https://linear.mock/...` and passes it through `SHAREABLE_REDACTED`, so this is belt-and-suspenders — a one-line `url.startsWith('http')` guard, not a full parser.

- [x] **Task 3 — Detail: the `RunOriginBlock` component** (AC: 2, 3, 4, 5, 6)
  - [x] Add `features/workflows/components/RunOriginBlock.tsx` — a pure-presentational block taking `{ detail: WorkflowDetail | undefined }` (or `{ linkedTicket }`) as a prop, mapping via `toRunOriginView`, and **rendering nothing** (`return null`) when the mapper returns `undefined` (parity with `RunDependencyPanel` / the `PrLinkageDetails` T-ABSENT discipline). Label the block as a landmark: a `<section aria-label="Origin">` (implicit region, no redundant `role`) with a small uppercase "Origin" label — mirror the `Item`/section idiom in `RunContextStrip.tsx:73-96,225-228`.
  - [x] Render: the `title` (human text), the `ticketRef` (as `<code>` or a labeled Item), the `integrationType` (small chip/label — reuse `StateSignifierChip stateName="informational"` or a plain labeled Item). Title/ref/type only (AC3).
  - [x] Link-out: render an `<a href={url} target="_blank" rel="noopener noreferrer">` **only when `view.url !== undefined`** (omit entirely otherwise — AC2). Give it a distinguishing accessible name (`aria-label={`Open source ticket ${ticketRef ?? ''} (opens in a new tab)`}`) and the shared external-link treatment from `PrLinkageDetails.tsx:133-139` (`underline-offset`, focus ring). Never a `#`/`javascript:` fallback anchor.
  - [x] Mount it on the detail route `routes/workflows/$workflowRunId/index.tsx` — pass the already-warmed `data` from `useWorkflowDetail` (do **not** add a second fetch; reuse the route's `data`, as `RunDependencyPanel`/`SplitLineagePanel` do at `:205,:209`). Place it near the top of the `<Stack>` (e.g. just under `<RunContextStrip>` at `:197`) so origin sits with run identity. It self-hides for unlinked runs.

- [x] **Task 4 — FE structured logging (field-only)** (AC: 5)
  - [x] If (and only if) you add a click handler on the link-out, emit a field-only `console.info({ event: 'runOrigin.openSource', integrationType })` — mirror the `queueItem.open` / `runContext.*` field-only log discipline (`RunReviewQueueItem.tsx:441`, `RunContextStrip.tsx:378`). **Never** log the `title`, the `url`, or the `ticketRef` free text. Logging is otherwise not required for a presentational read-only block.

- [x] **Task 5 — Tests** (AC: 1–6)
  - [x] `features/workflows/__tests__/runQueueRow.test.ts`: `toRunQueueRow` maps `ticketTitle` from the summary; maps to `undefined` when the summary omits/`null`s it (mirror the `archivedAt` null cases at `:77-82`).
  - [x] `features/workflows/components/__tests__/RunReviewQueueItem.test.tsx`: the human title renders when present; **falls back to `ticketRef`** when `ticketTitle` is absent; the ref/machine identity is still present in both cases; the row is axe-clean (`expectNoA11yViolations`, mirror the existing axe blocks).
  - [x] `features/workflows/__tests__/runOriginView.test.ts` (new, render-free): maps title/ref/type/url; returns `undefined` for no `linkedTicket` and for a `null` title; coalesces a `null` url to `undefined`; (if added) drops a non-http url.
  - [x] `features/workflows/components/__tests__/RunOriginBlock.test.tsx` (new): renders title + ref + integrationType; **link-out present when `url` set, absent when `url` null**; renders **nothing** when there is no linked ticket; link-out has the external accessible name + `rel="noopener noreferrer"`; block is axe-clean. Build the `WorkflowDetail` prop via a constructed fixture (mirror `RunContextStrip.test.tsx` detail fixtures).
  - [x] Green gates: `npm run test` (vitest), `npm run build` (tsc + vite), `npm run lint` (max-warnings=0), `npm run check:api`.

- [x] **Logging instrumentation** (cross-cutting standard — FE-adapted)
  - [x] This is a FE-only presentational story; the SLF4J/backend logging surface does not apply. The FE equivalent is the **field-only `console` discipline** in Task 4: structured `{ event, ...primitiveFields }` only, **never** the ticket title / source URL / free-text ref content. No new backend log surface is introduced.

## Dev Notes

### The real shape of this story (read before coding)

3g-1 did **all** the backend + read-model work and **already regenerated + committed `schema.d.ts`**. This story is a thin, read-only FE consumption layer over three already-present nullable fields. There is **no** OpenAPI regen, **no** contract test, **no** backend touch. The two surfaces are:

1. **Queue** — swap the row's human label to prefer `ticketTitle`, falling back to `ticketRef` (which today is the label). One field on the view-model, one mapper line, one label tweak in `Identity`.
2. **Detail** — a new small **Origin** block (`title + ref + integrationType + link-out`), self-hiding when there is no linked ticket, with the link-out gated on a non-null `url`.

Both are pure-presentational and follow shipped idioms (`PrLinkageDetails` for the external link + T-ABSENT "render only when present", `RunDependencyPanel`/`SplitLineagePanel` for a self-hiding detail block fed by the route's warmed `data`, `runContextView.ts`/`prLinkageView.ts` for the sibling `.ts` pure mapper).

### Source-tree components to touch (with line anchors, verify before editing)

- **Consumed contract (do NOT edit — verify only):** `deliveryline-frontend/src/lib/api/schema.d.ts` — `WorkflowSummary.ticketTitle` `:1773`; `LinkedTicket` `:1030` (`externalRef` `:1032`, `integrationType` `:1034`, `title` `:1040`, `url` `:1045`). Types surfaced as `WorkflowSummary` / `WorkflowDetail` in `lib/api/queryOptions.ts:33-34`.
- **Queue (Task 1):**
  - `features/workflows/runQueueRow.ts` — `RunQueueRow` interface `:32` (add `ticketTitle` near `linearTicketReference` `:35`); `toRunQueueRow` mapper `:117` (add the mapped line near `linearTicketReference` `:120`; `?? undefined` pattern at `:127-129`).
  - `features/workflows/components/RunReviewQueueItem.tsx` — `Identity` `:207` (label anatomy), `composeAriaLabel` `:179` (identity segment `:185`). Plain-text discipline documented at the file header (`:25`, Trap T6). Wiring site: `routes/workflows/index.tsx:82` (`renderItem={(summary) => <RunReviewQueueItem run={toRunQueueRow(summary)} />}`) — no change needed there.
- **Detail (Tasks 2–3):**
  - New `features/workflows/runOriginView.ts` (sibling mapper) + `features/workflows/components/RunOriginBlock.tsx` (component).
  - Pattern refs: `features/workflows/runContextView.ts` (pure mapper reading `detail.linkedTicket?.externalRef` at `:169` — the same source your block widens to title/url/type); `features/workflows/prLinkageView.ts` + `components/PrLinkageDetails.tsx:85-139` (external `<a target="_blank" rel="noopener noreferrer" aria-label=…>` treatment + `href !== undefined` gating).
  - Mount point: `routes/workflows/$workflowRunId/index.tsx` — the `<Stack>` at `:194`, `<RunContextStrip>` at `:197`, self-hiding blocks fed by `data` at `:205` (`RunDependencyPanel dependencies={data?.dependencies}`) and `:209` (`SplitLineagePanel`). Insert `<RunOriginBlock detail={data} />` just under `<RunContextStrip>`.
- **Tests:** `features/workflows/__tests__/runQueueRow.test.ts` (`LIVE_SUMMARY` fixture, null-case idiom `:77-82`); `features/workflows/components/__tests__/RunReviewQueueItem.test.tsx` (`toRunQueueRow(...)` render + axe); `features/workflows/components/RunContextStrip.test.tsx:314-671` (axe-per-state idiom + detail fixtures to copy). Axe helper: `import { expectNoA11yViolations } from '@/test/a11y/axe'` (`RunContextStrip.test.tsx:28`).

### Anti-patterns to avoid (disaster prevention)

- **Do NOT regenerate `schema.d.ts` / run `generate-api`** — 3g-1 committed it; a re-run here produces a spurious diff and can red `check:api` in review. Verify only.
- **Do NOT touch the backend / OpenAPI / any contract test** — there is no backend change in 3g-2.
- **Do NOT guard the wire fields with `=== undefined`** — they arrive as JSON `null` (`string | null`). Use `!= null` (the `workflowdetail-wire-sends-null-not-undefined` trap), and coalesce with `?? undefined` at the view-model seam for `exactOptionalPropertyTypes`.
- **Do NOT render a dead/disabled link when `url` is `null`** — omit the anchor entirely (AC2). No `#`, no `javascript:`, no visually-disabled control.
- **Do NOT render the title/url as markdown/HTML** — React-escaped plain text only; no `dangerouslySetInnerHTML`, no `SafeMarkdownRenderer` (Trap T6). The title is user/ticket-authored, treat as untrusted.
- **Do NOT export a non-component function from a `.tsx`** — the `frontend-react-refresh-no-fn-exports` eslint rule fails the build (max-warnings=0). Pure mappers/helpers go in a sibling `.ts` (`runOriginView.ts`, or into `runQueueRow.ts`).
- **Do NOT drop `ticketRef` from the queue row** — the title is additive; the ref stays as the machine identity (aria-label + `<code>` id). AC1 fallback = title→ref, not title-replaces-ref.
- **Do NOT add a second fetch on the detail page for the origin** — reuse the route's already-warmed `useWorkflowDetail` `data` (pass as prop), exactly like `RunDependencyPanel`/`SplitLineagePanel`.
- **Do NOT show the full ticket body or the initiating prompt** — origin depth is locked to title + ref + link only (AC3).

### Testing standards summary

- **Framework:** Vitest + React Testing Library; a11y via `expectNoA11yViolations` (`@/test/a11y/axe`) — mirror the axe-per-state blocks in `RunContextStrip.test.tsx`. Router-free pure-mapper tests for `runQueueRow.ts`/`runOriginView.ts`; render tests for the components.
- **Non-interactive vs interactive:** the queue row already has keyboard/nav tests; the Origin block's only interactive element is the external `<a>` (native activation — no custom keyboard handler needed). Axe-scan both.
- **Gates (all must be green, max-warnings=0):** `npm run test`, `npm run build` (tsc+vite), `npm run lint`, `npm run check:api`. No backend `mvnw` run is required for this FE-only story.
- **Frontend lockfile / native-binding caveat** does not apply (no dependency change). The `openapi-regen → FE-client-drift` cascade does not apply here because you are **not** regenerating — but it is *why* 3g-1 had to regen before this story could consume the fields.

### Logging Requirements (project-wide standard — FE adaptation)

This story touches no backend service, so the SLF4J/MDC surface in the template does not apply. The applicable standard is the FE **field-only structured `console`** discipline already used across the workflows feature (`console.info({ event, …primitives })` in `RunReviewQueueItem`/`RunContextStrip`):

- Emit a structured event **only** if you add a link-out click handler (`{ event: 'runOrigin.openSource', integrationType }`) — primitive fields only.
- **Forbidden in log output:** the ticket `title`, the source `url`, the raw `ticketRef` free text, or any PII. Log the `integrationType` enum at most.
- No new backend log surface; no list-appender test needed (there is no backend logging in this story).

### Project Structure Notes

- View-model mappers live in `features/workflows/*.ts` (sibling to components) — `runOriginView.ts` joins `runContextView.ts`, `runQueueRow.ts`, `prLinkageView.ts`. Components live in `features/workflows/components/*.tsx`. Tests in the co-located `__tests__/` dirs. No new package, no new dependency, no route change beyond mounting the block.
- The Origin block is a **new detail-page surface**; it partially overlaps the `RunContextStrip` "Trigger" Item (which shows `linkedTicket.externalRef` only, `RunContextStrip.tsx:181`). That is acceptable — the strip stays a single height-capped orientation row (`RUN_CONTEXT_STRIP_MAX_HEIGHT`), and the Origin block is the richer, dedicated provenance surface (title + link). Do **not** try to fold the link/title into the capped strip; keep them separate (mirrors how `RecoveryBaseline`/`RunTakeoverAttribution` are separate sections below the strip).

### References

- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Story 3g-2: FE — Ticket Title in Queue + Origin Block]
- [Source: _bmad-output/planning-artifacts/epic-03g-provenance-token-accounting.md#Cross-Cutting Notes] (origin posture locked: title + ref + link-out only; two OpenAPI regen points, both owned by the backend stories)
- [Source: _bmad-output/planning-artifacts/prd.md#FR73]
- [Source: 3g-1-ticket-origin-snapshot-and-read-model.md] (backend dependency, status `review`; delivered `WorkflowSummary.ticketTitle` + `LinkedTicket.title`/`url` and committed `schema.d.ts`)
- Seams: `schema.d.ts:1030,1040,1045,1773`; `runQueueRow.ts:32,35,117,120`; `RunReviewQueueItem.tsx:179,185,207`; `runContextView.ts:169`; `PrLinkageDetails.tsx:85-139`; `routes/workflows/$workflowRunId/index.tsx:194,197,205,209`; `routes/workflows/index.tsx:82`; `RunContextStrip.test.tsx:28,314`.
- Traps: `frontend-react-refresh-no-fn-exports`; `workflowdetail-wire-sends-null-not-undefined`; `livesnnouncement-defers-one-commit-test-flake`; `artifactview-variant-field-fanout` (FE §4 view-model discipline); `openapi-regen-frontend-client-drift-cascade` (why 3g-1 regenerated — do not re-run here).

## Review Findings

<!-- 2026-07-02 bmad-code-review (Opus 4.8 [1m]): 3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Acceptance Auditor: all 6 ACs PASS. Blind+Edge independently converged on the empty/whitespace-title blank-label defect. 3 patches, 1 dismissed (type-precluded null guard). -->

- [x] [Review][Patch] Empty/whitespace `ticketTitle` renders a BLANK prominent queue label (AC1 "never a blank label" violation) — `toRunQueueRow` uses `summary.ticketTitle ?? undefined`, which collapses only null/undefined; an empty/whitespace `""` survives, and in `Identity` `prominent = row.ticketTitle ?? row.linearTicketReference` keeps `""` (nullish `??` does not collapse `''`), so the prominent label renders empty AND the real ref is demoted to a secondary chip behind a dangling `·`. The sibling `runOriginView.ts` guards the identical `string | null` field via `presentOrUndefined` (trim + empty-check) — the two seams disagree. Fix: apply the same present/trim guard in the queue mapper (or in `Identity`). Also fixes the aria/visual divergence (aria `.filter(Boolean)` drops `''` but the visual label does not). [deliveryline-frontend/src/features/workflows/runQueueRow.ts:126; deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx:211]
- [x] [Review][Patch] `presentOrUndefined` returns the UNTRIMMED value — it gates on `value.trim() !== ''` but returns the original `value`, so whitespace-padded title/ref render verbatim (into the visible label, the `title=` tooltip, and the aria name), and a leading-whitespace URL (`"  https://x"`) passes the present-check then fails the `startsWith('https://')` prefix test → link-out silently dropped. Fix: return the trimmed value. [deliveryline-frontend/src/features/workflows/runOriginView.ts:40]
- [x] [Review][Patch] `httpUrlOrUndefined` scheme check is case-sensitive — `startsWith('https://') || startsWith('http://')` drops a valid case-varied scheme (`HTTPS://…`, RFC 3986 schemes are case-insensitive), silently losing a legitimate source-ticket link-out. Low likelihood (3g-1 emits lowercase `https://`) but a cheap, correct hardening. Fix: lowercase the scheme prefix before comparison. [deliveryline-frontend/src/features/workflows/runOriginView.ts:54]

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — `claude-opus-4-8[1m]`

### Debug Log References

- `npm run check:api` → in sync (no regen; consumed 3g-1's committed `schema.d.ts`).
- Targeted Vitest (4 files) → 95 passed / 0 failed.
- `npm run lint` (max-warnings=0) → No issues found.
- `npm run build` (tsc + vite) → EXIT=0, `✓ built`.
- Full `npm run test` → 116 files / 1250 tests passed, 0 failed (no regressions).

### Completion Notes List

FE-only, read-only consumption of 3g-1's already-committed schema — NO backend/OpenAPI/contract change, NO `schema.d.ts` regen (verified `check:api` only).

- **Queue (Task 1):** added a LIVE nullable `ticketTitle` to `RunQueueRow` + mapped it in `toRunQueueRow` (`summary.ticketTitle ?? undefined` — the exactOptionalPropertyTypes seam, collapsing the wire `null`). `Identity` now renders the human title as the prominent label with `linearTicketReference` demoted to a secondary chip; when the title is absent the ref stays in the prominent slot (today's exact parity — never a blank label). `composeAriaLabel` prepends the title to the identity segment while keeping the ref + run id (machine identity) in the aria name.
- **Detail (Tasks 2–3):** new sibling pure mapper `runOriginView.ts` (`toRunOriginView`) reading ONLY `detail.linkedTicket` (title/ref/type/url), returning `undefined` when there is no linked ticket or a null/blank title (AC2 render-nothing gate). It coalesces wire `null` via `!= null` and drops any non-`http(s)` url scheme (belt-and-suspenders). New `RunOriginBlock.tsx` self-hides (`return null`) via the mapper, renders title + ref + integrationType + a link-out gated on a present `url` (`target="_blank" rel="noopener noreferrer"` + distinguishing `aria-label`; omitted entirely — no `#`/`javascript:` anchor — when url is null). Mounted on the detail route just under `<RunContextStrip>`, fed the route's already-warmed `useWorkflowDetail` `data` (no second fetch).
- **Task 4 (logging):** no link-out click handler added (not required for a presentational read-only block) → no `console` event emitted.
- **Anti-patterns respected:** guarded wire fields with `!= null` (never `=== undefined`); title/url are React-escaped plain text (no `dangerouslySetInnerHTML`/`SafeMarkdownRenderer`); pure mappers live in sibling `.ts` (react-refresh rule); `ticketRef` never dropped from the queue row; no second detail fetch; origin depth locked to title+ref+link.

### File List

- `deliveryline-frontend/src/features/workflows/runQueueRow.ts` (modified — `ticketTitle` field + mapper line)
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx` (modified — `Identity` label anatomy + `composeAriaLabel` identity segment)
- `deliveryline-frontend/src/features/workflows/runOriginView.ts` (new — pure mapper)
- `deliveryline-frontend/src/features/workflows/components/RunOriginBlock.tsx` (new — self-hiding Origin block)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (modified — import + mount `<RunOriginBlock detail={data} />`)
- `deliveryline-frontend/src/features/workflows/__tests__/runQueueRow.test.ts` (modified — ticketTitle mapping cases)
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx` (modified — title label / ref fallback / aria / axe)
- `deliveryline-frontend/src/features/workflows/__tests__/runOriginView.test.ts` (new — mapper unit tests)
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunOriginBlock.test.tsx` (new — component + axe tests)

## Change Log

| Date | Version | Description | Author |
| ---- | ------- | ----------- | ------ |
| 2026-07-02 | 0.1 | Drafted FR73 FE story: consume 3g-1's `WorkflowSummary.ticketTitle` (queue human label with `ticketRef` fallback) + `LinkedTicket.title`/`url` (new self-hiding detail Origin block: title + ref + integrationType + gated external link-out). Read-only FE consumption — no backend/OpenAPI/contract change, no `schema.d.ts` regen. Status → ready-for-dev. | Bob (Opus 4.8) |
| 2026-07-02 | 1.0 | Implemented all 6 tasks: queue `ticketTitle` human label with `ticketRef` fallback (view-model + mapper + `Identity` + aria); new sibling `runOriginView.ts` mapper + self-hiding `RunOriginBlock` (title/ref/type + `url`-gated external link-out), mounted on the detail route from the warmed `useWorkflowDetail` data. Verified `check:api` (no regen), lint (0 warnings), build (tsc+vite), full Vitest 1250/1250. Status → review. | Amelia (Opus 4.8 [1m]) |
