# Story 2.18: Clarification Region with Visible Incorporation Lifecycle Wiring

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager,
I want a `ClarificationRegion` component that surfaces unresolved questions, accepts my answers in context, and visibly distinguishes the lifecycle states (`open` → `answered` → `accepted` → `incorporated` / `superseded` / `rejected_invalid`),
so that the make-or-break refinement holds end-to-end in the UI: I never see "answer submitted" without seeing whether it was incorporated, superseded, or set aside (UX-DR11 + visible-incorporation refinement).

## Acceptance Criteria

1. **Given** `src/features/workflows/components/ClarificationRegion.tsx`, **Then** the component accepts a typed frontend-owned `ClarificationsView` prop (a list of `ClarificationView`s grouped by status). The presentational component is **prop-driven** — a thin sibling container reads the data hook and maps it to props. (See the Central Reconciliation: the `useClarifications(workflowRunId)` hook the epic references has **no live read endpoint** and is authored here as a disabled stub.)
2. **Given** anatomy per UX-DR11, **Then** the rendered region displays: question list (grouped/sorted by status — `open` first, then `answered`/`accepted` pending, then terminal states collapsed by default), per-question status indicator with **non-color signifier** (icon + label, per story 2.3 AC5), selected-question detail panel, response input area (textarea, or structured-choice selector when applicable), optional structured-choice options, submit/resolve action button, visible relationship to current artifact state (`spec v3` callout), and a per-question **lifecycle indicator** showing the chain `submitted → accepted → incorporated` with the current position highlighted.
3. **Given** states per UX-DR11, **Then** each question renders in one of: `no open questions` (region shows calm empty state), `unanswered` (open), `in progress` (answer drafted but not submitted — local UI state), `answered / pending incorporation` (answer submitted, awaiting acceptance), `accepted` (queued for incorporation in next spec version), `incorporated` (visibly applied — happy outcome), `superseded` (set aside, with explicit reason), `rejected_invalid` (rejected with reason), `blocked / invalid` (validation error — local UI state), `error` (network/backend error).
4. **Given** variants per UX-DR11, **Then** the component supports: `inline review region` (default — embedded in or anchored from the Artifact Review Panel), `sidebar subregion` (right-context-panel slot via AppShell story 2.7 AC4 / `ContextPanelSlot`), `compact summary mode` (just counts and CTA — for queue-level reads), `full response mode` (when a question is selected for answering).
5. **Given** the visible incorporation lifecycle per UX-DR11 + make-or-break refinement, **Then** when a user submits an answer via the response input: (a) the UI shows immediate "answer submitted" **inline** feedback (NOT a toast — UX-DR15 rule), (b) the question's status visibly transitions to `answered` upon backend confirmation, (c) the lifecycle indicator updates as backend events arrive, (d) when the backend marks the clarification `incorporated` or `superseded`, the UI updates to reflect it — proving the backend `clarification.*` event chain (story 2.12 AC3) is faithfully surfaced. **(c)/(d) are DORMANT** — driven by fixtures/MSW, not live data, until the clarification-read endpoint ships.
6. **Given** the make-or-break contract (story 2.12 AC5), **Then** the UI exposes the no-effect-reason explicitly: when a clarification is `superseded`, the UI renders the reason ("Spec rebuilt without addressing this question — superseded by spec v4"); when `rejected_invalid`, the UI renders why ("Answer text was not parseable for question type") — **never a silent disappearance**.
7. **Given** accessibility per UX-DR11 + UX-DR20, **Then** each question is labeled and keyboard-navigable (focus moves between questions with arrow keys or Tab), response controls are programmatically associated with the selected question (`aria-labelledby`/`aria-describedby`), and an **ARIA live region** announces status transitions (e.g., "Clarification answer accepted") — this story ensures the live-region wiring exists; story 2.25 enforces the broader WCAG AA audit.
8. **Given** content guidelines per UX-DR11, **Then** when a question is selected it visually dominates the detail area (other questions stay navigable but secondary); reviewer wording (`answerText`) and system interpretation are visually separated (no commingling — if the system parses an answer into a structured form, both raw and parsed renders are shown side-by-side with clear labels). Untrusted text (questionText, answerText) is rendered **only** via the `@/lib/sanitization` primitives.
9. **Given** the responsibility boundary per UX-DR11, **Then** the region owns: question status display, response capture, visible incorporation state — and emits `onSubmitAnswer(clarificationId, answerText)` which the container wires to the **live** `useSubmitClarification` mutation (story 2.6 pattern + the live `answerClarification` endpoint). The region does **NOT** own approval gating (the Approval/Decision Bar, story 2.19, owns that — backlog).
10. **Given** unresolved questions block approval per story 2.14 AC4, **Then** the region displays a clear "{N} clarifications must be incorporated before approval" affordance when `pendingClarifications > 0` — making the gating reason visible. **DORMANT** — `pendingClarifications` has no live read-model source today (see Reconciliation); derived from the `ClarificationsView` fixtures (count of non-terminal clarifications) and fixture-tested.
11. **Given** component test coverage (fixture-driven RTL — **NOT** `toMatchSnapshot`; story 2.27's snapshot harness does not exist), **Then** tests cover: each state renders correctly with its non-color signifier; full lifecycle path (submitted → answered → accepted → incorporated) updates the UI as sequential backend events arrive (mocked TanStack Query backend); superseded path with no-effect-reason rendered; rejected_invalid path with reason; ARIA live region announces status transitions; focus management when a question is selected; sanitization rejects scriptable payloads in question/answer text (XSS fixture — assert absence via `queryByRole`/`queryAllByRole`, not string match); and the **"no answer received" anti-pattern** test (a fixture where the backend acknowledges a submission but no follow-up event arrives — the UI must visibly surface the stuck `answered / pending incorporation` state, NOT show "answer received" forever).

**Dependency (satisfied):** Story 2.24 (Artifact Content Sanitization + Redaction-Gap Closure) is **done** — its `SafeMarkdownRenderer` / `MetadataChrome` / redaction primitives are the only sanctioned path for rendering untrusted clarification content. Enforced by the `dependency-edges` CI check + the `no-unsanitized-html` ESLint rule.

---

## Tasks / Subtasks

- [x] **Task 1 — `clarificationView.ts`: frontend-owned types + pure helpers + fixtures** (AC: 1, 2, 3, 6, 10)
  - [x] Create `src/features/workflows/clarificationView.ts` exporting the **frontend-owned** types: `ClarificationLifecycleStatus` union (`'open' | 'answered' | 'accepted' | 'incorporated' | 'superseded' | 'rejected_invalid' | 'unknown'` — include `'unknown'` per the `ClarificationAnswerResponse` sentinel note in schema.d.ts:249), `ClarificationView` (one clarification carrying the FULL epic anatomy: `clarificationId`, `workflowRunId`, `artifactId`, `artifactVersion`, `questionId`, `questionText`, `status`, `answerText?`, `answeredByActor?`, `answeredByActorType?`, `answeredAt?`, `acceptedAt?`, `incorporatedAt?`, `incorporatedIntoArtifactId?`, `supersededByArtifactId?`, `noEffectReason?`, `createdAt`), and `ClarificationsView` (`{ clarifications: ClarificationView[] }`).
  - [x] Add pure helpers (all in this `.ts`, never the `.tsx` — `frontend-react-refresh-no-fn-exports`): `groupClarificationsByStatus(view)` (open → answered/accepted pending → terminal collapsed), `resolveLifecyclePosition(status): { stages: ['submitted','accepted','incorporated']; current }`, `countPendingIncorporation(view)` (non-terminal = NOT in `incorporated`/`rejected_invalid`; **note** `superseded` counts as pending per story 2.12/2.14 AC4 — workflow stays blocked until a fresh answer is incorporated or it is rejected_invalid), and `resolveClarificationItemState(view, draft?)` mapping a backend status (+ optional local draft/validation/error UI-state) to the AC3 render state.
  - [x] Add `src/test/fixtures/clarification/clarificationViewFixtures.ts`: one `ClarificationsView` per AC3 backend state (open, answered, accepted, incorporated, superseded-with-reason, rejected_invalid-with-reason), a multi-question grouped fixture, an XSS fixture (questionText/answerText carrying `<script>`/`<img onerror>`), and a "stuck" fixture (answered with no follow-up — AC11 anti-pattern).
  - [x] Unit-test the helpers in `clarificationView.test.ts` (grouping order, pending count incl. superseded, lifecycle position, state resolution incl. local draft/validation/error precedence).

- [x] **Task 2 — `useClarifications` disabled read stub + `useSubmitClarification` live mutation** (AC: 1, 5, 9)
  - [x] Create `src/features/workflows/hooks/useClarifications.ts` as a **DISABLED STUB** mirroring `useAllowedActions.ts`/`useArtifact.ts`: `useQuery({ queryKey: workflowKeys.clarifications(workflowRunId), queryFn: () => { throw new Error('...no clarification-read endpoint yet...') }, enabled: false })`. Document the seam: **no `GET clarifications` endpoint exists in schema.d.ts** — flip `enabled` + supply a real `apiClient.GET` once the read endpoint ships; ZERO component changes.
  - [x] Add the key factory entry `clarifications: (workflowRunId) => [...workflowKeys.detail(workflowRunId), 'clarifications'] as const` to `src/lib/queryKeys/workflowKeys.ts` (the `no-inline-query-keys` rule forbids inline keys).
  - [x] Create `src/features/workflows/hooks/useSubmitClarification.ts` as a **LIVE** mutation (the `POST .../clarifications/{clarificationId}/answer` endpoint IS in schema.d.ts:101–111). Build on the `useWorkflowMutation` factory: `mutationFn` calls the typed `apiClient.POST` with `AnswerClarificationRequest { answerText, artifactId, expectedArtifactVersion }` + the `Idempotency-Key` header; on success the factory invalidates `detail(runId)` (cascades to events/allowedActions) + lists. Surface typed errors via `ProblemDetailsError` (`CLARIFICATION_NOT_FOUND`, `CLARIFICATION_ARTIFACT_VERSION_MISMATCH` [retryable], `CLARIFICATION_TERMINAL_STATE`, `ILLEGAL_CLARIFICATION_TRANSITION`).
  - [x] Test: a `useSubmitClarification` MSW test (success → `ClarificationAnswerResponse`, version-mismatch → typed error state, idempotency-key sent once and reused on retry).

- [x] **Task 3 — `ClarificationRegion.tsx` presentational composite** (AC: 1, 2, 3, 4, 8)
  - [x] Create `src/features/workflows/components/ClarificationRegion.tsx` — pure presentational, takes a resolved `ClarificationsView` + `variant` (`'inline' | 'sidebar' | 'compact' | 'full'`) + `density` (`densityGap`) + callbacks (`onSelectQuestion`, `onSubmitAnswer`, `onDraftChange`). Renders the AC2 anatomy. Stamp a `data-clarification-region-state` / per-item `data-clarification-item-state` attribute for state-detection tests.
  - [x] Group + sort via `groupClarificationsByStatus`; terminal states collapsed-by-default with a disclosure toggle. Selected question dominates the detail area (AC8); others stay navigable but secondary.
  - [x] Per-question status indicator uses a **non-color signifier** — reuse `StateSignifierChip` from `components/WorkflowStateBadge.tsx` (icon + label; do NOT rebuild — T2). Do **not** use `WorkflowStateBadge` (that vocabulary is workflow-*state*, not clarification-status — T3).
  - [x] `no open questions` → calm `EmptyState` from `@/components/feedback` (NOT a spinner; `no-untyped-loading-state` rule). `error` → `ErrorState` with a Retry; `loading` → `LoadingState`/skeleton.
  - [x] Render `questionText` / `answerText` (untrusted) **only** via `SafeMarkdownRenderer` imported from the `@/lib/sanitization` **barrel** (T1 — barrel-only; `no-unsanitized-html`). Where a structured-choice answer is parsed, show raw `answerText` and the parsed render **side-by-side** with clear labels (AC8).

- [x] **Task 4 — Lifecycle indicator + inline feedback + no-effect-reason + approval-gating affordance** (AC: 5, 6, 10)
  - [x] Build a **self-contained** lifecycle indicator (`submitted → accepted → incorporated`) inside the region with the current position highlighted via `resolveLifecyclePosition`. **Do NOT** import `<ActionLifecycleIndicator>` from story 2.21 — that feedback-primitives story is **backlog** and the component does not exist (T6). Keep this region-local; 2.21 may later extract/generalize it.
  - [x] On submit: render immediate inline "answer submitted" feedback in the region (NOT a toast — UX-DR15, T7). Build region-local inline feedback; do NOT depend on 2.21's `<InlineFeedback>` (backlog).
  - [x] Render the no-effect-reason explicitly for `superseded` (`noEffectReason` + `supersededByArtifactId` → "superseded by spec vN") and `rejected_invalid` (`noEffectReason`) — never let a clarification silently disappear (AC6).
  - [x] Render the AC10 affordance "{N} clarifications must be incorporated before approval" when `countPendingIncorporation > 0`. This is a **message only** — the region does NOT gate approval (2.19 owns the bar, backlog). Keep `useAllowedActions` **disabled** (T5).

- [x] **Task 5 — Thin container + route mount + anchor/deep-link wiring** (AC: 1, 4, 9)
  - [x] Create `src/features/workflows/components/ClarificationRegionContainer.tsx`: reads `useClarifications(workflowRunId)` (disabled stub → maps to `no open questions` empty today, mirroring how 2.17's container maps disabled `useArtifact` → empty), maps the (future) data to `ClarificationsView`, and wires `onSubmitAnswer` → `useSubmitClarification`. Reads the `?clarificationId` search param (route already validates it via `isValidClarificationId`, `$workflowRunId/index.tsx:77–81`) and scrolls/focuses the matching question.
  - [x] Mount the container as a **sidebar subregion** in `src/routes/workflows/$workflowRunId/index.tsx` via the `ContextPanelSlot` (AppShell 2.7 AC4) — sits in the right `<aside>`; the main pane stays artifact-primary (`DESKTOP_MAIN_MIN_WIDTH` never collapses). Today it renders the calm empty state.
  - [x] Wire the existing **placeholder anchor** in `SpecArtifactRenderer.tsx` (`data-testid="artifact-clarification-anchor"`, currently a no-target OQ-4 placeholder, `SpecArtifactRenderer.tsx:228–235`) to focus/scroll the region. **Note:** `useNavigateToClarification(runId, clarificationId)` (story 2.22) requires a concrete `clarificationId`; with no read model there is no clarification to target, so the anchor's full deep-link remains partial — wire it to focus the region container (see OQ-4).

- [x] **Task 6 — Tests: states, lifecycle, sanitization, a11y, anti-pattern** (AC: 5, 6, 7, 8, 11)
  - [x] `ClarificationRegion.test.tsx` (fixture-driven RTL): each AC3 state renders with its non-color signifier + `data-clarification-item-state`; selected-question dominance; raw-vs-parsed answer separation; terminal-state collapse/disclosure.
  - [x] Lifecycle test: feed sequential states via re-render / mocked query (submitted → answered → accepted → incorporated) and assert the indicator advances; superseded path renders `noEffectReason`; rejected_invalid renders its reason.
  - [x] Sanitization test: XSS fixture → assert no `<script>`/`<iframe>` rendered (`queryByRole`/`queryAllByRole`, not string match).
  - [x] A11y test: questions focusable + arrow/Tab navigation; response controls `aria-labelledby` the selected question; ARIA live region announces a status transition. Full axe/contrast deferred to story 2.25 (no axe harness — OQ-5).
  - [x] **Anti-pattern test (AC11):** "stuck" fixture (answered, no follow-up event) → UI visibly shows `answered / pending incorporation`, never "answer received/incorporated".
  - [x] Mock `@tanstack/react-router` (replace `Link`/`useNavigate`/`useSearch` — router-free component tests, exact pattern in `RunContextStrip.test.tsx`). Pin `Date.now()` if relative-time renders (mirror 2.16).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. Equivalent contract: structured `console.info` / `console.warn({ event, …fields })`, **field-only**, pinned by `console`-spy tests. Mirror `QueueShell.tsx` / `RunContextStrip.tsx`:
    - `clarification.submit` (`info`) — `{ event, clarificationStatus }` on submit (status from the response, never the answer text).
    - `clarification.submitError` (`warn`) — `{ event, code, transport }` (`code` from ProblemDetails, never raw message/status).
    - `clarification.loadError` (`warn`) — `{ event, code, transport }` when the (future) read fails.
    - `clarification.lifecycleAdvance` (`info`) — `{ event, status }` when a question advances to a new lifecycle position.
  - [x] Use parameterized structured objects — never string concatenation. INFO for normal lifecycle, WARN for recoverable anomalies/errors.
  - [x] **Never log** `questionText`, `answerText`, `questionId` free text, `ticketRef`, `runId`, tokens, or any business/PII content. Add a **negative test** asserting the payload keys are exactly the allowed set (no `message`, no `answerText`, no `question`).
  - [x] Pin each new log line at its level with a focused console-spy test.

### Review Findings

_Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) — 2026-06-04. Triage: 2 decision-needed, 12 patch, 4 deferred, 3 dismissed. Most patch findings are on the DORMANT deep-link / populated-view path (no clarification-read endpoint yet), but the code wiring is present in this change._

_**RESOLUTION (2026-06-04):** both decisions resolved by Alex → patches; all 14 patches (incl. the 2 reclassified decisions) applied; 4 deferred items logged in `deferred-work.md`; 3 dismissed. Gates GREEN via PowerShell: `tsc -b` 0 errors, `eslint --max-warnings=0` clean, `lint:rules-test` 4/4, full `vitest run` 44 files / 374 tests passed (+5 new clarification tests; zero regressions), `prettier --check` clean. No new files; no lockfile change._

**Decision-needed (RESOLVED 2026-06-04 by Alex → reclassified as patches below):**

- [x] [Review][Patch] (was Decision-①) Collapse `unknown` sentinel rows out of the inline actionable list into the terminal/disclosure group, AND exclude `unknown` from `countPendingIncorporation` (the approval-gate count) — treat hard-deleted legacy sentinels as non-actionable historical artifacts. Reconciles the grouping comment with the render + gate. [clarificationView.ts ~349; ClarificationRegion.tsx ~1446]
- [x] [Review][Patch] (was Decision-②) Coerce any unrecognized backend status → `unknown` at the mapping/guard boundary so one out-of-union row never blanks the whole view or throws via `assertNeverStatus`; `isClarificationsView` should normalize rather than reject-whole-view, and the helpers degrade gracefully. [clarificationView.ts ~259; isClarificationsView]

**Patch (fixable, unambiguous):**

- [x] [Review][Patch] Untrusted `questionText` rendered raw in question-list rows, bypassing the `@/lib/sanitization` barrel (AC8/T1/T2 — React-escaped so not XSS, but skips the 2.24 redaction filter; only the detail panel sanitizes). Also exposes no `title`/full-text for the truncated label. [ClarificationRegion.tsx:1105]
- [x] [Review][Patch] Draft answer + validation state leaks across questions — `<QuestionDetail>` has no `key`, so React reuses the instance when the selected question changes; typed draft text and validation error carry over to the next question. Add `key={selected.clarificationId}`. [ClarificationRegion.tsx ~1116/1481]
- [x] [Review][Patch] Submit allowed for a second question while a submit is in flight — `submittingThis` is keyed per-question but the mutation is run-global; selecting another open question and submitting clobbers the in-flight `activeClarificationId`. Guard on any-pending. [ClarificationRegion.tsx ~1242; ClarificationRegionContainer.tsx ~1921]
- [x] [Review][Patch] XSS test omits the `<img onerror>` vector — fixture carries it but the test only asserts no `<script>`/`<iframe>`; assert `window.__xss_executed` stays false / no surviving event-handler attribute. [ClarificationRegion.test.tsx ~1672]
- [x] [Review][Patch] Deep-link to a terminal clarification leaves its row collapsed/hidden — `selected` resolves across all groups but terminal rows sit behind the `showTerminal` disclosure (default false); auto-expand when the selected/deep-linked question is terminal. [ClarificationRegion.tsx ~1455]
- [x] [Review][Patch] Deep-link to a non-existent `clarificationId` → detail panel silently empty with no notice, while the container still scrolls/focuses the region; render a "clarification not found / no longer available" branch. [ClarificationRegion.tsx ~1481; ClarificationRegionContainer.tsx ~1996]
- [x] [Review][Patch] `select()` half-wired controlled/uncontrolled — a *defined* `selectedClarificationId` (deep-link) pins the rendered selection to the prop while clicks only update ignored internal state and there's no `onSelectQuestion` consumer; selection becomes unchangeable. [ClarificationRegion.tsx ~1357]
- [x] [Review][Patch] Container focus effect latches `sectionFocused` and never resets on a later `?clarificationId` change — a second in-route deep link won't re-focus/scroll the region. [ClarificationRegionContainer.tsx ~1976]
- [x] [Review][Patch] Stale `success`/`error` inline feedback persists while the user edits a fresh draft on a still-`open` question (banner not cleared on edit). [ClarificationRegion.tsx ~1252]
- [x] [Review][Patch] Keyboard-nav edges — single-option Arrow keys still `preventDefault` page scroll for no movement; `activeIndex === -1` (focus lost after a terminal-collapse) kills arrow nav with no fallback to first/last row. [ClarificationRegion.tsx ~1368]
- [x] [Review][Patch] `NoEffectReason` doesn't compose "superseded by spec vN" from `supersededByArtifactId` (relies entirely on backend `noEffectReason` prose — AC6/Task4), and drops `noEffectReason`/`supersededByArtifactId` on any non-superseded/rejected status. [ClarificationRegion.tsx ~1058]
- [x] [Review][Patch] Dead Retry control — error state renders an enabled "Retry" wired to `onRetry ?? (() => undefined)` when no handler is supplied; hide/disable instead. [ClarificationRegion.tsx ~1402]

**Deferred:**

- [x] [Review][Defer] First-render lifecycle transitions never announced by the ARIA live region (only announces on a *prior→new* change) [ClarificationRegion.tsx ~1312] — deferred; dormant until the read endpoint ships, and announce-on-mount is a debatable a11y choice.
- [x] [Review][Defer] When several clarifications advance in one render, only the last-in-array is announced/logged (single `latest` slot, no queue) [ClarificationRegion.tsx ~1319] — deferred; dormant, low impact.
- [x] [Review][Defer] Off-chain (`superseded`/`rejected_invalid`) not visibly highlighted in the lifecycle indicator (shows `submitted` reached); only the separate callout conveys it [ClarificationRegion.tsx ~1007] — deferred; cosmetic, dormant.
- [x] [Review][Defer] `CompactSummary` CTA absent for an `unknown`-only view (shows "N pending" with no drill-in) [ClarificationRegion.tsx ~1283] — deferred; dormant edge.

**Dismissed (noise / false-positive / handled elsewhere):** null `answerText` regression (frontend-owned type is `string | undefined` — null cannot arise from typed data); `console.info/warn` logging (matches the project's frontend-adapted logging standard, pinned by the exact-key-set negative test); AC8 "parsed render" is a status label (explicitly deferred by OQ-6 — no structured-answer contract yet).

## Dev Notes

### THE CENTRAL RECONCILIATION (read this first)

This is the **second** frontend composite (after story 2.17) whose **primary content has no live data source** — and the gap is sharper here. The epic's AC1 says the region "accepts a typed `ClarificationsView` prop sourced from `useClarifications(workflowRunId)` (TanStack Query hook backed by story 2.12's inspection methods)." **That hook cannot be live:**

- **No clarification-read endpoint exists in the frontend contract.** Grepping the generated `deliveryline-frontend/src/lib/api/schema.d.ts` shows the **only** clarification path is `POST .../clarifications/{clarificationId}/answer` (`answerClarification`). There is **no** `GET clarifications` list/status endpoint, and **no** `ClarificationsView` / `ClarificationView` / `ClarificationStatusView` type. The backend inspection methods (`getClarifications`, `getClarificationStatus`, `countPendingByWorkflowRun`) exist in the Java service layer (stories 2.11/2.12 are **done**) but are **not exposed via REST/OpenAPI**, so they are invisible to the frontend.
- **`WorkflowDetail` (the live read model) carries no clarification data** — no `pendingClarifications`, no clarification list, no lifecycle timestamps. It has `currentState`, `escalationMarker`, `specRejectionLoopCount`, `latestArtifacts[]` (type/status/version only), timestamps (`schema.d.ts:339–374`).
- **`useAllowedActions` is still a DISABLED STUB in the frontend** (`hooks/useAllowedActions.ts:15–25`, `enabled: false`, throws) even though the backend `getAllowedActions` endpoint shipped (2.14 done) and is in `schema.d.ts:64–75`. Re-wiring it live is the Approval-Bar / data-layer story's job, not this one.

**What IS live and this story legitimately delivers:** the `answerClarification` **mutation** endpoint (`schema.d.ts:101–111`, `232–253`). So `useSubmitClarification` is authored as a **real** mutation hook — the one genuine live seam 2.18 closes.

**The design rule (mirror 2.17 exactly):** Build `ClarificationRegion` to the **full epic anatomy**, presentational + prop-driven, and drive every render in tests from **constructed `ClarificationsView` fixtures**. Live-reachable today is **only** `no open questions` (the disabled `useClarifications` stub → container maps to empty, exactly as 2.17 mapped the disabled `useArtifact` → empty); `loading` + `error` become reachable when the read endpoint ships and the stub is enabled. **Everything else** — all per-question backend states, the lifecycle indicator chain, the submit→watch-incorporate flow (AC5c/d), the `{N}-pending` approval-gating affordance (AC10) — is **DORMANT**: fully built + tested via fixtures, **never fabricated from live data** (the 2.15/2.16/2.17 discipline). When the clarification-read endpoint lands, enabling the hook flips the region from `empty` to populated with **zero component changes**.

### 2.18 is the FIRST epic-2b story — it must be self-contained

Epic 2 is split 2a (done/active) + 2b (`epic-2b: deferred`). **All of 2.18's sibling infrastructure is still `backlog`:** 2.19 (Approval/Decision Bar), 2.21 (Feedback Patterns: `InlineFeedback` / `PersistentStateBadge` / `ActionLifecycleIndicator` / `Toast`), 2.23 (Modal/Overlay), 2.25 (WCAG audit + axe harness), 2.27 (frontend test-suite / snapshot harness). **Consequences:**

- Build the lifecycle indicator and inline-feedback **region-locally**. Do NOT import 2.21's primitives — they do not exist yet (T6). 2.21 may later extract/generalize what you build.
- The AC10 approval-gating affordance is a **message only**; the actual gating + Approval Bar is 2.19 (backlog).
- Tests are **fixture-driven RTL**, not `toMatchSnapshot` (2.27's harness does not exist — OQ-5). Full axe/contrast audit deferred to 2.25.
- Modal confirmation infra (2.23) is absent — clarification submit is **inline** anyway (UX-DR15 says low-risk submits do NOT require a modal), so this is a non-issue for 2.18.

### What already exists — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| `SafeMarkdownRenderer` | `@/lib/sanitization` (**barrel only**) | The ONLY sanctioned renderer for untrusted `questionText`/`answerText`. (story 2.24) |
| `MetadataChrome` / `SafeDiffRenderer` | `@/lib/sanitization` | If wrapping a clarification's artifact-bound context; diff if showing answer revisions. |
| `StateSignifierChip` | `features/workflows/components/WorkflowStateBadge.tsx:26–66` | Non-color clarification-status chip (icon + label). **Not** `WorkflowStateBadge`. |
| `EmptyState` / `LoadingState` / `ErrorState` | `@/components/feedback` (barrel) | no-open-questions / loading / error states. `no-untyped-loading-state` forbids raw spinners. |
| `ContextPanelSlot` + `AppShellContext` | `features/workflows/ContextPanelSlot.tsx`, `AppShellContext.ts` | Portal the sidebar variant into the right `<aside>`; reference-counted, multi-occupant (coexists with `RunContextStrip`). |
| `DESKTOP_MAIN_MIN_WIDTH` (`min-w-[36rem]`) | `features/workflows/AppShell.tsx` | Artifact-primacy floor — the region lives in the aside, never displaces the main pane. |
| `densityGap(density)` | `lib/density.ts` | `'gap-1'` (compact) / `'gap-2'` (standard). |
| `useWorkflowMutation` | `features/workflows/hooks/useWorkflowMutation.ts` | Factory for `useSubmitClarification` (UUIDv7 idempotency key minted once + reused on retry; success invalidation cascade). |
| `workflowKeys` | `lib/queryKeys/workflowKeys.ts` | Add `clarifications(runId)`; never inline keys (`no-inline-query-keys`). |
| `ProblemDetailsError` | `lib/api/problemDetails.ts` | Typed error → `code`/`retryable` for the error state. |
| `useNavigateToClarification` + `?clarificationId` param | `lib/navigation/useNavigateToClarification.ts`, `routes/workflows/$workflowRunId/index.tsx:77–81` | Deep-link semantics (story 2.22): the region READS the search param and scrolls itself into view. |
| `artifact-clarification-anchor` placeholder | `SpecArtifactRenderer.tsx:228–235` | The OQ-4 placeholder anchor from 2.17 — wire it to focus/scroll the region. |
| `isValidClarificationId` (`^cla_[A-Za-z0-9_-]{4,64}$`) | `lib/routing/publicId.js` | Validate any clarification id before use. |

### Live data contract (what the schema actually exposes)

- **`POST /api/v1/workflows/{workflowRunId}/clarifications/{clarificationId}/answer`** → `AnswerClarificationRequest { answerText: string; artifactId: string; expectedArtifactVersion: int }` + required `Idempotency-Key` header → **200** `ClarificationAnswerResponse { clarificationId; clarificationStatus; correlationId?; currentState; workflowRunId }`. Note: answering does **not** transition the run state (`currentState` stays e.g. `WaitingForSpecApproval`); `clarificationStatus` may be `"unknown"` on idempotent replays of hard-deleted legacy rows — default-case unknown statuses (schema.d.ts:249).
- **`GET .../allowed-actions`** → `AllowedActions { actions: string[]; versionStamp }` (actions may include `"answer_clarification"`). **Live in schema but the frontend hook stays disabled** — do not wire it here.
- The clarification's `artifactId` + `expectedArtifactVersion` for the mutation come from the `ClarificationView` (each clarification binds to its artifact version, per backend 2.11). Since the read model is dormant, these are sourced from fixtures today.

### Frontend conventions (architecture)

- Stack: React + TypeScript, Vite, TanStack Query (server state) + TanStack Router (typed routes), shadcn/ui + Tailwind, Vitest + RTL + MSW. [Source: architecture.md#445–520]
- **Backend-reported allowed actions, never frontend inference** — the region must not infer approval eligibility; it only surfaces the visible affordance (AC10). [Source: architecture.md#267, #485]
- **Runner/agent output is untrusted** — clarification question/answer text must be sanitized; trusted metadata visually separated from generated content. [Source: architecture.md#507, #518]
- Component files PascalCase `.tsx`; pure helpers/view-models in sibling `.ts` (react-refresh `only-export-components`). Query keys via the factory, never inline.

### Traps (do NOT step on these)

- **T1 — barrel imports only.** Import `SafeMarkdownRenderer` etc. from `@/lib/sanitization`, never an individual file. Any `dangerouslySetInnerHTML` outside the sanitization package fails `local-rules/no-unsanitized-html` (error). [memory: not applicable to Bash — see T-GATES]
- **T2 — never roll your own status chip / markdown renderer.** Reuse `StateSignifierChip` + `SafeMarkdownRenderer`. Building a bespoke renderer or a color-only dot is a regression.
- **T3 — clarification-status ≠ workflow-state.** Use `StateSignifierChip` (generic semantic chip), NOT `WorkflowStateBadge` (workflow lifecycle vocabulary). The clarification lifecycle is its own vocabulary.
- **T4 — dormant states from fixtures only.** Never fabricate clarification rows from `WorkflowDetail` or any live data. The read model carries no clarification data; inventing it is the exact anti-pattern 2.6/2.17 forbid.
- **T5 — `useClarifications` + `useAllowedActions` stay disabled.** `useClarifications` is a new disabled stub (no read endpoint). `useAllowedActions` stays the existing disabled stub — do NOT wire it live (that is 2.19/data-layer scope). Only `useSubmitClarification` is live.
- **T6 — 2.21 primitives do not exist.** No `InlineFeedback` / `ActionLifecycleIndicator` / `PersistentStateBadge` / `Toast` (`sonner`) — 2.21 is backlog. Build region-local equivalents; do not import a non-existent module.
- **T7 — workflow-significant feedback is NOT toast (UX-DR15).** Submission/incorporation outcomes render **inline / persistent in the region**, never toast-only. (Also: `sonner` is not wired — 2.21.)
- **T8 — `superseded` counts as pending.** `countPendingIncorporation` excludes only `incorporated` + `rejected_invalid`. A `superseded` clarification keeps the run blocked until a fresh answer is incorporated (story 2.12/2.14 AC4).
- **T9 — answering does not change run state.** The mutation returns the same `currentState`; the lifecycle indicator advances the *clarification*, not the workflow. Do not re-derive workflow state from the answer response.
- **T-ANCHOR — the placeholder anchor has no clarificationId target.** `useNavigateToClarification` needs a concrete `cla_…` id; with no read model none exists. Wire the `artifact-clarification-anchor` to focus/scroll the region container, not to a deep-link target (OQ-4).
- **T-GATES — run gates via PowerShell, not Bash.** [memory: `rtk-hook-only-matches-bash`] RTK corrupts only the Bash tool; use native file tools + PowerShell. And run `prettier --write` before finishing or the format gate cascades and skips downstream jobs [memory: `prettier-gate-cascades-ci`].
- **T-REFRESH — pure helpers in `.ts`, not `.tsx`.** [memory: `frontend-react-refresh-no-fn-exports`] All view-model functions/maps live in `clarificationView.ts`; the `.tsx` exports only the component.

### Open questions (resolved with recommendations — proceed unless told otherwise)

- **OQ-1 — presentational vs container split.** *Recommendation:* a pure presentational `ClarificationRegion` (takes resolved `ClarificationsView` + callbacks, router/query-free) + a thin `ClarificationRegionContainer` that reads the disabled `useClarifications` stub, reads the `?clarificationId` param, and wires `onSubmitAnswer → useSubmitClarification`. Tests drive the presentational component directly with fixtures; one container test covers the live `empty` mapping. Mirrors 2.17 OQ-1.
- **OQ-2 — author the live `useSubmitClarification` mutation, or stub it?** *Recommendation:* **author it live.** The `answerClarification` endpoint genuinely exists in the schema, the epic AC9 assigns the mutation hook to this story, and `useWorkflowMutation` makes it low-risk. It is reachable in production only once a clarification can be surfaced (read model), so today it is exercised via MSW/fixtures — that is acceptable (it is real wiring, not fabrication). Keep the *read* (`useClarifications`) disabled.
- **OQ-3 — local UI states (`in progress`, `blocked/invalid`).** *Recommendation:* model `in progress` (draft not submitted) and `blocked/invalid` (client-side validation) as **component-local UI state**, not backend statuses — `resolveClarificationItemState(view, draft)` layers them over the backend status. Keeps the view-model a faithful mirror of the backend.
- **OQ-4 — wiring the 2.17 clarification anchor.** *Recommendation:* wire `artifact-clarification-anchor` to **focus/scroll** the region container (not a deep-link), since no `clarificationId` target exists pre-read-model. Full anchor→specific-question deep-linking activates when the read endpoint + `?clarificationId` round-trip is live.
- **OQ-5 — accessibility depth.** *Recommendation:* deliver the AC7 baseline (labels, keyboard nav, `aria-labelledby`, one ARIA live region) + non-color signifiers; defer full WCAG AA contrast/axe to story 2.25 (no axe harness exists). Document the deferral.
- **OQ-6 — structured-choice selector.** *Recommendation:* support a `structured-choice` answer variant in the type + component (render options as radios; show raw + parsed side-by-side per AC8), fixture-tested. The backend `answerText` is free-text today (the mutation request has only `answerText`), so structured choice serializes to `answerText` — keep it presentational + dormant until a structured-answer contract exists.

### Logging Requirements (project-wide standard, frontend-adapted)

Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. Equivalent contract: structured `console.info` / `console.warn({ event, …fields })`, **field-only** (never `error.message`, question/answer text, ids of business entities, tokens, PII), pinned by `console`-spy tests with an exact-key-set negative assertion. See the Logging instrumentation task for the exact event names and field sets.

### Project Structure Notes

New files (mirror the 2.15/2.16/2.17 layout):

```
deliveryline-frontend/src/
├── features/workflows/
│   ├── clarificationView.ts                 (NEW — types + pure helpers)
│   ├── clarificationView.test.ts            (NEW)
│   ├── hooks/
│   │   ├── useClarifications.ts             (NEW — disabled read stub)
│   │   ├── useSubmitClarification.ts        (NEW — live mutation)
│   │   └── useSubmitClarification.test.tsx  (NEW — MSW)
│   └── components/
│       ├── ClarificationRegion.tsx          (NEW — presentational)
│       ├── ClarificationRegion.test.tsx     (NEW)
│       └── ClarificationRegionContainer.tsx (NEW — thin container)
├── lib/queryKeys/workflowKeys.ts            (MODIFIED — add clarifications key)
├── test/fixtures/clarification/
│   └── clarificationViewFixtures.ts         (NEW)
├── routes/workflows/$workflowRunId/index.tsx          (MODIFIED — mount region in ContextPanelSlot, pass ?clarificationId)
└── features/workflows/components/SpecArtifactRenderer.tsx (MODIFIED — wire artifact-clarification-anchor → focus region)
```

No new npm dependency (sanitization stack from 2.24 + TanStack already present) → **no lockfile change → no WSL2/Linux-CI smoke needed** [memory: `wsl-linux-ci-reproduction`, `frontend-lockfile-cross-platform`].

### Testing standards

- **Fixture-driven RTL — never `toMatchSnapshot`** (story 2.27's snapshot harness does not exist). Assert on roles/text/`data-clarification-*-state` attributes.
- Component tests stay **router-free**: `vi.mock('@tanstack/react-router', …)` replacing `Link`/`useNavigate`/`useSearch` (exact pattern in `RunContextStrip.test.tsx` / `RunReviewQueueItem.test.tsx`).
- Mutation/lifecycle tests use a mocked TanStack Query backend (or MSW for the live mutation) feeding **sequential** states.
- Pin `Date.now()` (or `vi.setSystemTime`) if relative-time renders (mirror 2.16/2.15).
- Console-spy assertions for every new log line + a negative test (no business content / no `message`).

### Gate verification (run via PowerShell — `rtk-hook-only-matches-bash`)

```
tsc -b                                    # 0 errors
eslint . --max-warnings=0                 # 0 (incl. no-unsanitized-html, no-inline-query-keys, react-refresh)
npm run lint:rules-test                   # 4/4 custom-rule suites
vitest run                                # full suite green (+ new clarification tests)
prettier --write "src/**/*.{ts,tsx}"      # before finishing (prettier-gate-cascades-ci)
```

### References

- [Source: epics.md#Story-2.18 lines 1254–1274] — story statement, 11 ACs, dependency on 2.24.
- [Source: ux-design-specification.md#UX-DR11] — clarification region anatomy/states/variants/accessibility/responsibility boundary; "distinguish clearly between answered and incorporated."
- [Source: ux-design-specification.md#UX-DR15] — feedback inline-vs-toast boundaries; "do not collapse 'answer received' and 'answer incorporated' into one message."
- [Source: ux-design-specification.md#UX-DR20 / Accessibility Strategy] — ARIA live regions for async workflow updates.
- [Source: ux-design-specification.md#story-2.3-AC5] — semantic state never color-alone (icon + label).
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:64–75, 101–111, 188–254, 339–374] — live `getAllowedActions`, `answerClarification`, `AllowedActions`, `AnswerClarificationRequest`, `ClarificationAnswerResponse`, `WorkflowDetail` (no clarification list / no pendingClarifications).
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts:1–25] — disabled-stub pattern to mirror for `useClarifications`.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts] — mutation factory (idempotency key, invalidation cascade) for `useSubmitClarification`.
- [Source: deliveryline-frontend/src/features/workflows/components/WorkflowStateBadge.tsx:26–66] — `StateSignifierChip` signature.
- [Source: deliveryline-frontend/src/features/workflows/ContextPanelSlot.tsx + AppShellContext.ts + AppShell.tsx] — sidebar slot + `DESKTOP_MAIN_MIN_WIDTH`.
- [Source: deliveryline-frontend/src/lib/navigation/useNavigateToClarification.ts + routes/workflows/$workflowRunId/index.tsx:77–81] — `?clarificationId` deep-link the region reads.
- [Source: deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx:228–235] — `artifact-clarification-anchor` placeholder to wire.
- [Source: 2-17-artifact-review-panel-generalized-composite-spec-variant.md] — the dormant/live discipline template + presentational/container split + logging pattern.
- [Source: 2-12-backend-visible-incorporation-lifecycle-states-and-event-wiring.md] — lifecycle states, `clarification.*` events, `ClarificationStatusView` fields (backend; not yet REST-exposed).
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml:118–143] — 2.11/2.12/2.13/2.14/2.24 done; 2.19/2.21/2.23/2.25/2.27 backlog; `epic-2b: deferred`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow.

### Debug Log References

- `clarificationView.ts` initially failed the oxc transform: a JSDoc line `answeredBy*/answeredAt`
  contained `*/`, prematurely closing the block comment. Reworded to `answeredBy / answeredAt`.
- `exactOptionalPropertyTypes: true` (repo tsconfig) forced every optional prop to be typed
  `?: T | undefined` (matching the existing `StateSignifierChipProps` idiom) — applied across
  `ClarificationDraft`, `ClarificationSubmissionState`, `ClarificationRegionProps`,
  `ClarificationRegionContainerProps`, and the `QuestionDetail` inline prop type.
- `SubmitClarificationResult` had its `WorkflowMutationResult<TData, TVariables>` type params
  swapped (factory returns `<TData, TVariables>`), which cascaded into the container's `mutate`
  variables type + the MSW test; fixed by ordering them `<ClarificationAnswerResponse, …Variables>`.
- ESLint `no-unnecessary-condition` flagged `submission?.errorCode` inside the `erroredThis ? …`
  branches — TS/eslint aliased-condition narrowing already proves `submission` non-null there, so
  the `?.` was dropped. `role="listbox"` needed `tabIndex={-1}` (jsx-a11y interactive-supports-focus).

### Completion Notes List

Frontend-only presentational composite (2.15/2.16/2.17 lineage). All 11 ACs + Logging satisfied;
6 tasks + the logging task complete.

- **The one live seam (AC9):** `useSubmitClarification` is a REAL mutation on the existing
  `answerClarification` endpoint (`POST .../clarifications/{id}/answer`), built on the
  `useWorkflowMutation` factory → UUIDv7 idempotency-key reuse (AC7) + `detail(runId)` invalidation
  cascade (AC6). T9 honoured: the response `currentState` is never re-derived into workflow state.
- **Dormant-by-design (T4):** `useClarifications` is a new DISABLED STUB (no clarification-read
  endpoint exists in `schema.d.ts`) + a new `workflowKeys.clarifications(runId)` factory key. The
  ONLY live-reachable state today is `no open questions` (disabled stub → container maps to an empty
  view, exactly as 2.17 mapped disabled `useArtifact`). Every other state — per-question lifecycle
  states, the `submitted → accepted → incorporated` indicator, submit→watch-incorporate (AC5c/d),
  the {N}-pending approval-gating affordance (AC10) — is built + fixture-tested, never fabricated
  from live data. Enabling the hook later flips empty→populated with ZERO component changes.
- **Frontend-owned types (no backend `ClarificationsView`):** `clarificationView.ts` exports the
  `ClarificationLifecycleStatus` union (incl. the `unknown` sentinel, schema.d.ts:249),
  `ClarificationView`/`ClarificationsView`, and pure helpers (`groupClarificationsByStatus`,
  `countPendingIncorporation` [T8 — `superseded` counts as pending; excludes only
  `incorporated`/`rejected_invalid`], `resolveLifecyclePosition`, `resolveClarificationItemState`
  with local draft/validation/error precedence, `clarificationItemSignifier`, `isClarificationsView`
  runtime guard). All helpers in the `.ts` sibling (react-refresh — T-REFRESH).
- **Sanctioned primitives reused (T1/T2/T3):** `StateSignifierChip` (NOT `WorkflowStateBadge`) for
  the non-color status chip; `SafeMarkdownRenderer` from the `@/lib/sanitization` BARREL for the
  untrusted `questionText`/`answerText`; `EmptyState`/`LoadingState`/`ErrorState` from
  `@/components/feedback`. Lifecycle indicator + inline submit feedback are REGION-LOCAL (T6/T7 —
  2.21 primitives are backlog; feedback is inline, never toast — UX-DR15).
- **Mount + anchor (Task 5):** container portaled into the right context panel via `ContextPanelSlot`
  (sidebar subregion, main pane stays artifact-primary); the route reads `?clarificationId` and hands
  it to the container; the 2.17 `artifact-clarification-anchor` placeholder now focuses/scrolls the
  region by its stable DOM id (T-ANCHOR/OQ-4 — no concrete `clarificationId` target pre-read-model).
- **A11y baseline (AC7, OQ-5):** arrow-key navigation across questions, `aria-labelledby` wiring of
  the response textarea to the selected question, and a polite ARIA live region announcing lifecycle
  transitions. Full WCAG AA / axe audit deferred to story 2.25 (no axe harness).
- **Logging:** field-only `console.info`/`console.warn` — `clarification.submit` (status only, never
  answer text), `clarification.submitError`/`clarification.loadError` (`{event, code, transport}`),
  `clarification.lifecycleAdvance` (`{event, status}`). Pinned with console-spy tests + an exact
  key-set negative assertion proving no answer/question/message leakage (T8).

**Gates GREEN via PowerShell** (memory `rtk-hook-only-matches-bash`): `tsc -b` 0 errors;
`eslint . --max-warnings=0` clean (incl. `no-unsanitized-html`, `no-inline-query-keys`,
react-refresh); `npm run lint:rules-test` 4/4; full `vitest run` 44 files / 369 tests passed
(+48 new across 4 new test files; zero regressions); `prettier --check` clean. NO new npm dependency
→ no lockfile change → no WSL2/Linux-CI smoke needed.

### File List

**New:**
- `deliveryline-frontend/src/features/workflows/clarificationView.ts`
- `deliveryline-frontend/src/features/workflows/clarificationView.test.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useClarifications.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useSubmitClarification.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useSubmitClarification.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ClarificationRegion.tsx`
- `deliveryline-frontend/src/features/workflows/components/ClarificationRegion.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ClarificationRegionContainer.tsx`
- `deliveryline-frontend/src/features/workflows/components/ClarificationRegionContainer.test.tsx`
- `deliveryline-frontend/src/test/fixtures/clarification/clarificationViewFixtures.ts`

**Modified:**
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` (add `clarifications(runId)` key)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (mount region in `ContextPanelSlot`, pass `?clarificationId`)
- `deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx` (wire `artifact-clarification-anchor` → focus region)

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2026-06-04 | 0.1 | Implemented story 2.18 — Clarification Region with visible incorporation lifecycle wiring (frontend-only presentational composite + live `useSubmitClarification` mutation + disabled `useClarifications` stub). All 11 ACs + Logging; 6 tasks. Gates green (tsc/eslint/rules-test/vitest 369/prettier). Status → review. | Amelia (dev-story) |
| 2026-06-04 | 0.2 | Adversarial code review (3 layers) + triage. 2 decisions resolved → patches; 14 patches applied (sanitize row label via redaction barrel; key QuestionDetail to reset draft; submit-in-flight guard; `<img onerror>` XSS test; auto-expand terminal disclosure for deep-linked terminal rows; not-found notice; controlled/uncontrolled selection seed; focus-latch reset on new deep link; dismiss stale feedback on edit; keyboard-nav edges; compose "spec vN" reason; non-dead Retry; `unknown` collapsed + excluded from gate; coerce unrecognized status → `unknown`). 4 deferred, 3 dismissed. Gates green (vitest 374). Status → done. | Claude (code-review) |
