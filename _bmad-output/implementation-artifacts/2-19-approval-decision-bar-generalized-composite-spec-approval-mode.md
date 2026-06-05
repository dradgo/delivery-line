# Story 2.19: Approval / Decision Bar — Generalized Composite (Spec Approval Mode)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager making a decision on a spec (and later a developer deciding on implementation output, or an operator making a recovery decision in Epic 4),
I want an `ApprovalDecisionBar` composite that **reads backend-reported allowed actions** (no frontend permission inference — UX-DR12 hard rule), is **generalized for variant modes from day one**, and concentrates the current decision into one explicit control area with clear consequences,
so that Epic 3 adds `implementation_review` mode and Epic 4 adds `recovery_operator` mode without reshaping infrastructure (party-mode finding #3) — and the bar always echoes the expected artifact + context versions so a stale UI surfaces `APPROVAL_VERSION_MISMATCH` instead of silently overwriting.

## Acceptance Criteria

1. **Given** `src/features/workflows/components/ApprovalDecisionBar.tsx`, **Then** the component is generalized — it accepts a typed `mode` prop (`'spec_approval' | 'implementation_review' | 'recovery_operator'`) and renders the variant per mode; the `spec_approval` variant is fully implemented in E2, `implementation_review` + `recovery_operator` are **stub-only renderers** with a documented placeholder ("available in Epic 3" / "available in Epic 4") so the mode contract holds. (Exhaustive `mode` switch — a TS `assertNever` default so a new mode can't compile without a branch.)
2. **Given** anatomy per UX-DR12, **Then** the bar renders: current decision context (e.g. "Approve specification v3 by Alex (product_reviewer)"), primary action (one visually primary — `Approve` for `spec_approval`), secondary action (`Reject with feedback` opens a rationale dialog), required reason input where relevant (rejection requires `reasonText` + `taggedFeedback`), stale/conflict warning slot, immediate-consequence hint (e.g. "Approval will transition the run to Executing"), disabled-state explanation when an action is unavailable, and post-submit decision summary (after a decision lands, the action area is replaced by a summary: timestamp + actor + decision).
3. **Given** states per UX-DR12, **Then** the bar renders each of: `ready` (actions enabled), `blocked` (no safe primary action — clearly explained; NEVER a disabled primary action without explanation), `stale` (workflow changed since the bar loaded — refresh CTA), `disabled` (mode-specific control restriction), `submitting` (mutation in flight), `success` (post-decision summary), `error` (mutation failed, error shown), `locked` (decision already made — read-only view). A `data-approval-bar-state` attribute stamps the current state for state-detection tests.
4. **Given** variants per UX-DR12, **Then** prop-based variants include `mode` (`spec_approval` E2 / `implementation_review` E3-stub / `recovery_operator` E4-stub) **and** `layout` (`'sticky_footer'` default — fixed to the bottom of the main pane / `'inline_section'` — rendered inline within the Artifact Review Panel for shorter artifacts).
5. **Given** **backend-reported allowed actions** per UX-DR12 hard rule + party-mode finding #3, **Then** the bar reads `useAllowedActions(workflowRunId)` (story 2.14, now wired **LIVE** — see Reconciliation) and: (a) only renders actions returned by the backend `actions[]`, (b) the `Approve` button is **hidden** if `approve_spec` is not in `actions[]`, (c) when the bar disables/withholds an action it renders a disabled-state explanation via a documented **reason-code → localized-text mapping table** — fed by `disabledActions: { [action]: reasonCode }` **when the backend supplies it**; today the live `AllowedActions` schema carries no `disabledActions` field, so the specific reason is **DORMANT** (fixture-driven) and the live fallback is a generic "no decision is available in the current state" explanation (never a bare disabled control). The bar gracefully ignores unknown action wire-values (forward-compat, UX-DR6).
6. **Given** version-stamped mutations, **Then** every mutation (approve / reject) sends `expectedArtifactVersion` + `expectedContextBundleVersion` (ints derived from the live `AllowedActions.versionStamp` — `currentSpecArtifactVersion` / `currentContextBundleVersion`); the `versionStamp.lastEventId` / `workflowState` are used UI-side for stale-detection (there is **no** single `expectedAllowedActionsVersionStamp` request field — the composite stamp is carried by the two int fields). When the backend returns `APPROVAL_VERSION_MISMATCH` (HTTP 409) or any version conflict, the bar renders the `stale` state with a refresh-and-retry CTA explaining what changed (e.g. "Spec was updated to v4 by the agent — review the new version before approving").
7. **Given** "one visually primary action per decision area" (UX-DR19 + UX-DR12), **Then** the bar enforces this: only one button uses primary styling at a time; secondary actions are visually subordinate; if no safe primary action exists, the bar renders the `blocked` state instead of promoting an unavailable action. A pure helper computes the single primary action; a fixture with multiple candidate actions proves only one renders primary.
8. **Given** confirmation patterns (UX-DR18; story 2.23 modal infra is **backlog**), **Then** `Reject with feedback` opens a **region-local** confirmation/rationale dialog capturing `reasonText` (free-form) + `taggedFeedback` (radio: rework taxonomy `MISSING_SCOPE` / `UNCLEAR_SPECIFICATION` / `MISUNDERSTOOD_IMPLEMENTATION` — story 2.10, **UPPERCASE wire values** per `schema.d.ts`); the dialog cannot be dismissed without an explicit cancel; submit-with-confirmation invokes `useRejectSpec`. Do NOT import 2.23's `<ConfirmationDialog>`/`<RationaleCaptureDialog>` (they do not exist yet) — build region-local; 2.23 may later extract it.
9. **Given** the post-submit decision summary, **Then** after a decision lands the bar persists the outcome visibly (timestamp + actor + decision + the linked `currentState`/event reference for audit) until the next workflow state change — the user sees their action's consequence rather than an empty bar; it never auto-clears (UX-DR19).
10. **Given** accessibility per UX-DR12, **Then** all actions are keyboard reachable (Tab order matches visual order), button labels use explicit verbs ("Approve specification", "Reject with feedback" — never "OK"/"Cancel"), disabled rationale is readable by screen reader (`aria-describedby` links the withheld/disabled control to its reason text), warning/stale states announce via an ARIA live region, and focus moves predictably into and out of the rejection dialog (focus restoration on close). Full WCAG AA / axe audit is deferred to story 2.25 (no axe harness exists).
11. **Given** the responsibility boundary per UX-DR12, **Then** the bar owns: decision actions, rationale capture, blocked-state messaging, visible decision outcome — it does **NOT** compute approval eligibility (the backend does, via `useAllowedActions`), hide action consequences, or omit stale-state warnings. The bar consumes only `useAllowedActions` output and never re-derives eligibility (enforced by a documented convention + a focused import-graph guard — see Task 5 / OQ-5; no permission-inference module exists to import).
12. **Given** component test coverage (fixture-driven RTL — **NOT** `toMatchSnapshot`), **Then** tests cover: each AC3 state renders; allowed-actions integration (Approve hidden when `approve_spec` absent; blocked with reason when no primary action); version-stamped mutation sends both expected versions; stale-decision UI on `APPROVAL_VERSION_MISMATCH`; rejection confirmation dialog flow (open → capture `reasonText`+`taggedFeedback` → confirm → `useRejectSpec`); post-submit summary persists; `locked` state when a decision already made; mode-prop dispatch (`spec_approval` renders fully, `implementation_review`+`recovery_operator` render placeholders); one-primary-action rule (multi-candidate fixture → exactly one primary); keyboard navigation through the full action set including the dialog.
13. **Given** the "answered ≠ incorporated — can't approve until incorporated" rule (party-mode + story 2.14 AC4), **Then** when clarifications are pending incorporation the bar visibly shows "{N} clarifications pending incorporation — approval blocked" (never a silently disabled control). **DORMANT** — there is no live `pendingClarifications` source (absent from `WorkflowDetail`) and the live `AllowedActions` reports only action absence, so the blocked-reason text is fixture-driven today; live, an absent `approve_spec` renders the generic blocked explanation. The Clarification Region (story 2.18 AC10) provides the reciprocal affordance.

**Dependencies:** Stories **2.13** (approve/reject REST mutations — **done**), **2.14** (allowed-actions inspection endpoint — **done**), **2.10** (spec-rejection rework taxonomy — **done**), **2.6** (`useWorkflowMutation` factory — **done**), **2.24** (sanitization barrel — **done**), **2.16/2.17/2.18** (RunContextStrip / ArtifactReviewPanel / ClarificationRegion mount points — **done**) are all satisfied. Sibling infra **2.21** (feedback primitives), **2.23** (modal/overlay + button-hierarchy), **2.25** (WCAG/axe), **2.27** (snapshot harness) remain **backlog** — build region-local equivalents (see Traps).

---

## Tasks / Subtasks

- [x] **Task 1 — `approvalDecisionView.ts`: frontend-owned types + pure helpers + reason-mapping + fixtures** (AC: 1, 2, 3, 5, 6, 7, 13)
  - [x] Create `src/features/workflows/approvalDecisionView.ts` exporting frontend-owned types: `ApprovalBarMode` (`'spec_approval' | 'implementation_review' | 'recovery_operator'`), `ApprovalBarLayout` (`'sticky_footer' | 'inline_section'`), `ApprovalBarState` (`'ready' | 'blocked' | 'stale' | 'disabled' | 'submitting' | 'success' | 'error' | 'locked'`), `DecisionAction` wire union (`'approve_spec' | 'reject_spec' | 'answer_clarification' | 'unknown'` — coerce unrecognized backend strings → `'unknown'` and drop them, mirroring 2.18's `unknown`-sentinel discipline), `ApprovalDecisionView` (the resolved prop the presentational bar consumes: `workflowRunId`, `mode`, `layout`, `actions: DecisionAction[]`, `versionStamp` parts, `currentState`, `decisionContextLabel`, `pendingClarifications?`, `disabledReasons?: Partial<Record<DecisionAction, string>>`, optional last-decision summary), and `RejectionDraft` (`{ reasonText: string; taggedFeedback: TaggedFeedback }`).
  - [x] Pure helpers (all in this `.ts`, never the `.tsx` — `frontend-react-refresh-no-fn-exports`): `resolvePrimaryAction(actions): DecisionAction | null` (AC7 — single primary; `approve_spec` wins over `reject_spec`; returns `null` → bar renders `blocked`), `resolveApprovalBarState(view, mutation, localUi)` (maps live action-list + mutation status + version-mismatch + local dialog state → the AC3 state, with precedence `locked > error > stale > submitting > blocked > disabled > success > ready`), `mapDisabledReason(reasonCode | undefined): string` (the AC5c reason-code → localized-text table, with a generic fallback), `deriveExpectedVersions(versionStamp): { expectedArtifactVersion; expectedContextBundleVersion }` (AC6 — pulls `currentSpecArtifactVersion`/`currentContextBundleVersion`; both null → cannot build a mutation request → contributes to `blocked`), `isStaleAgainst(view, latestStamp)` (compares `lastEventId`/`currentSpecArtifactVersion` for UI-side stale detection), and `assertNeverMode(mode)` for the exhaustive switch.
  - [x] Add `src/test/fixtures/approval/approvalDecisionFixtures.ts`: one `ApprovalDecisionView` per AC3 state (ready, blocked-no-primary, blocked-pending-clarifications [AC13], stale, disabled, submitting, success-with-summary, error, locked), a multi-candidate-action fixture (AC7 — `approve_spec` + `reject_spec` both present → only one primary), an `implementation_review` + `recovery_operator` stub fixture, and a "no resolvable artifactId/version" fixture (the dormant-firing boundary).
  - [x] Unit-test the helpers in `approvalDecisionView.test.ts` (primary-action selection, state-resolution precedence, reason-code mapping incl. fallback, version derivation incl. null-stamp → blocked, unknown-action coercion).

- [x] **Task 2 — Data layer: wire `useAllowedActions` LIVE + author `useApproveSpec`/`useRejectSpec` live mutations** (AC: 5, 6, 11)
  - [x] **Flip `useAllowedActions` from disabled stub → LIVE** in `src/features/workflows/hooks/useAllowedActions.ts`: replace the `enabled: false` / throwing `queryFn` with a real `apiClient.GET('/api/v1/workflows/{workflowRunId}/allowed-actions', …)` returning the generated `AllowedActions` (`{ actions: string[]; versionStamp }`). Keep the existing `workflowKeys.allowedActions(workflowRunId)` key (the contract 2.6 reserved). This is the wiring 2.18 explicitly deferred to "the Approval-Bar / data-layer story" (2.18 T5). **Verify no current consumer breaks** — 2.18's ClarificationRegion treats its `useAllowedActions` usage as disabled/dormant; confirm flipping it live does not surface fabricated data anywhere (grep all callers).
  - [x] **Relocate `useApproveSpec`** out of `useWorkflowMutation.ts` (where it lives as a compile-proof scaffold, lines ~146–160) into `src/features/workflows/hooks/useApproveSpec.ts` as the LIVE hook the bar calls — typed variables (`artifactId`, `expectedArtifactVersion`, `expectedContextBundleVersion`, `reason?`, `reviewerRole?`) → `WorkflowStateChangeResponse`. Keep the `useWorkflowMutation` factory file free of concrete hooks (or leave the scaffold + re-export — dev's call; document the choice). Mirror the live-mutation shape of `useSubmitClarification.ts` exactly.
  - [x] **Author `useRejectSpec`** (NEW — no hook exists yet) in `src/features/workflows/hooks/useRejectSpec.ts`: live `apiClient.POST('/api/v1/workflows/{workflowRunId}/reject-spec', …)` with `RejectSpecRequest { artifactId, expectedArtifactVersion, expectedContextBundleVersion, reasonText, reviewerRole?, taggedFeedback }` + the `Idempotency-Key` header, on the `useWorkflowMutation` factory (UUIDv7 key reuse + `detail(runId)`/`lists()` invalidation cascade). `taggedFeedback` is the **UPPERCASE** enum (`MISSING_SCOPE | UNCLEAR_SPECIFICATION | MISUNDERSTOOD_IMPLEMENTATION`).
  - [x] Surface typed errors via `ProblemDetailsError` for both mutations: `APPROVAL_VERSION_MISMATCH` (the stale-decision trigger, AC6), `ILLEGAL_TRANSITION`, `WORKFLOW_RUN_TERMINAL`, `IDEMPOTENCY_KEY_CONFLICT` (409); `RUN_NOT_FOUND` / `ARTIFACT_RECORD_NOT_FOUND` (404); `ARTIFACT_PAYLOAD_UNAVAILABLE` (503); `MISSING/INVALID_IDEMPOTENCY_KEY` / `INVALID_COMMAND_PAYLOAD` / `INVALID_ID_PREFIX` (400).
  - [x] Tests: a live MSW test per mutation (success → `WorkflowStateChangeResponse`; `APPROVAL_VERSION_MISMATCH` → typed error; idempotency-key minted once + reused on retry) and a `useAllowedActions` live test (200 → `actions`+`versionStamp`; ProblemDetails error → typed error state).

- [x] **Task 3 — `ApprovalDecisionBar.tsx` presentational composite** (AC: 1, 2, 3, 4, 7, 10)
  - [x] Create `src/features/workflows/components/ApprovalDecisionBar.tsx` — pure presentational: takes a resolved `ApprovalDecisionView` + callbacks (`onApprove`, `onRejectIntent`, `onRefresh`, `onResetSummary?`) + `layout`. Renders the AC2 anatomy. Stamp `data-approval-bar-state` (+ `data-approval-bar-mode`, `data-approval-bar-layout`) for state-detection tests.
  - [x] **Mode dispatch (AC1):** exhaustive `switch(mode)` with `assertNeverMode` default. `spec_approval` renders the full bar; `implementation_review` / `recovery_operator` render a documented placeholder ("Implementation review — available in Epic 3" / "Operator recovery — available in Epic 4") so the contract holds without dead UI.
  - [x] **One-primary-action (AC7):** use `resolvePrimaryAction`; render exactly one primary-styled button (`Approve specification`), `Reject with feedback` visually subordinate (secondary). If `resolvePrimaryAction` returns `null`, render the `blocked` state — never a disabled primary button.
  - [x] **States (AC3):** drive the rendered branch off `resolveApprovalBarState`. Reuse `@/components/feedback` where it fits (`ErrorState` for `error` with a Retry/refresh; a region-local non-spinner indicator for `submitting`). `blocked`/`stale`/`disabled` render their explanation text (AC5c / AC6 / AC13). Non-color signifiers for warning/stale (icon + label) per story 2.3 AC5 — reuse `StateSignifierChip` from `WorkflowStateBadge.tsx`; do NOT roll a color-only dot.
  - [x] **Layout (AC4):** `sticky_footer` (fixed to the bottom of the main pane; respect `DESKTOP_MAIN_MIN_WIDTH` artifact-primacy) vs `inline_section` (in-flow within the ARP). Keep the bar router/query-free; the container supplies data + callbacks.
  - [x] Render any untrusted decision-context text (actor identity, reason echoes) only via the `@/lib/sanitization` barrel if it originates from runner/agent output; trusted UI chrome (button labels, consequence hints) is static.

- [x] **Task 4 — Rejection rationale dialog + post-submit summary + immediate-consequence hint** (AC: 2, 8, 9)
  - [x] Build a **region-local** rejection confirmation/rationale dialog (do NOT import 2.23 primitives — T-MODAL): captures `reasonText` (textarea) + `taggedFeedback` (radio group over the UPPERCASE rework taxonomy); cannot be dismissed except via explicit Cancel; Confirm invokes the container's `onReject(draft)`. Focus moves into the dialog on open and restores to the trigger on close (AC10). `reasonText` is reviewer-authored free text — pass through, never log it.
  - [x] Render the **immediate-consequence hint** (AC2) for the primary action (e.g. "Approval will transition the run to Executing") — derived from a static consequence map keyed by `mode`+action, not from live state.
  - [x] Build the **post-submit decision summary** (AC9): after `success`, replace the action area with timestamp + actor + decision (+ resulting `currentState` from `WorkflowStateChangeResponse`); persist it until the next workflow state change; never auto-clear. Pin `Date.now()`/`vi.setSystemTime` in tests if relative time renders (mirror 2.16).
  - [x] Inline feedback (submission ack / outcome) renders **in the bar's own region**, never toast (UX-DR15; `sonner`/2.21 not wired — T-FEEDBACK).

- [x] **Task 5 — Thin container, route mounts, artifactId-sourcing seam, eligibility-import guard** (AC: 5, 6, 11, 13)
  - [x] Create `src/features/workflows/components/ApprovalDecisionBarContainer.tsx`: reads `useAllowedActions(workflowRunId)` (now LIVE), reads `useWorkflowDetail(workflowRunId)` for the decision-context label + `currentState`, maps both into an `ApprovalDecisionView`, derives expected versions via `deriveExpectedVersions`, and wires `onApprove`/`onReject` → `useApproveSpec`/`useRejectSpec`. On `APPROVAL_VERSION_MISMATCH` it surfaces the `stale` state + refetches `allowedActions`.
  - [x] **artifactId-sourcing seam (THE dormancy boundary):** approve/reject request bodies REQUIRE `artifactId`, which **no live read model exposes** (`WorkflowDetail.latestArtifacts` = type/status/version only; `useArtifact` is a dormant stub). When the container cannot resolve a concrete `artifactId`, the view resolves to `blocked` ("specification not yet available for a decision") — the bar NEVER renders a primary button it cannot fire. Document the single line where a live `artifactId` source plugs in (ARP `useArtifact` live, or a versionStamp/`latestArtifacts` id field) → bar goes fully live with zero component changes. Tests exercise the firing path via fixtures/MSW (real wiring, not fabrication — the 2.17/2.18 discipline).
  - [x] **Mount (AC4):** add the `sticky_footer` bar to the run-detail route `src/routes/workflows/$workflowRunId/index.tsx` (bottom of the main pane, beside the existing `RunContextStrip` + `ClarificationRegionContainer`); add the `inline_section` variant inside the Artifact Review Panel (`ArtifactReviewPanel` container) for the spec artifact. Keep the main pane artifact-primary.
  - [x] **Eligibility-import guard (AC11):** add a documented convention + a focused guard that the bar/container import only `useAllowedActions` output and no permission-inference helper (none exists today). Prefer a lightweight import-graph unit test or extend the existing `tools/eslint-rules/` set ONLY if trivially expressible; otherwise document-and-defer the full ArchUnit-equivalent rule to 2.25/2.31 and note it. (OQ-5.)

- [x] **Task 6 — Tests: states, mode dispatch, gating, version-mismatch, rejection, summary, a11y** (AC: 1, 3, 5, 6, 7, 8, 9, 10, 12, 13)
  - [x] `ApprovalDecisionBar.test.tsx` (fixture-driven RTL): each AC3 state renders with `data-approval-bar-state`; mode dispatch (spec_approval full vs E3/E4 placeholders); one-primary-action rule via the multi-candidate fixture; layout variants render.
  - [x] Allowed-actions gating: Approve hidden when `approve_spec` absent (AC5b); blocked-with-reason when no primary action (AC5c fallback text live; specific "{N} clarifications pending" via fixture — AC13).
  - [x] Version + stale: mutation variables include both `expectedArtifactVersion`+`expectedContextBundleVersion` derived from the stamp (AC6); `APPROVAL_VERSION_MISMATCH` → `stale` UI with refresh CTA + the "what changed" explanation.
  - [x] Rejection flow (AC8): open dialog → enter `reasonText` + select `taggedFeedback` (UPPERCASE) → confirm → `useRejectSpec` called with the exact body; dialog non-dismissible without Cancel; focus restored on close.
  - [x] Post-submit summary (AC9): after success the summary persists (timestamp + actor + decision + `currentState`); does not auto-clear; `locked` state renders read-only when a decision already made.
  - [x] A11y (AC10): full keyboard traversal of actions + dialog (Tab order matches visual order); explicit verb labels; `aria-describedby` links a withheld/disabled control to its reason; ARIA live region announces a stale/warning transition. Mock `@tanstack/react-router` (router-free component tests — pattern in `RunContextStrip.test.tsx`). Full axe deferred to 2.25 (OQ-4).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. Equivalent contract: structured `console.info` / `console.warn({ event, …fields })`, **field-only**, pinned by `console`-spy tests. Mirror `ClarificationRegion.tsx` / `QueueShell.tsx`:
    - `approval.approveSubmit` (`info`) — `{ event, currentState }` from the response (never `reason`/`artifactId`).
    - `approval.rejectSubmit` (`info`) — `{ event, taggedFeedback, currentState }` (the enum is non-PII; never `reasonText`).
    - `approval.versionMismatch` (`warn`) — `{ event, code }` on `APPROVAL_VERSION_MISMATCH` / stale refetch.
    - `approval.submitError` (`warn`) — `{ event, code, transport }` (`code` from ProblemDetails, never raw message/status).
    - `approval.allowedActionsLoadError` (`warn`) — `{ event, code, transport }` when the live allowed-actions read fails.
  - [x] Use parameterized structured objects — never string concatenation. INFO for normal lifecycle, WARN for recoverable anomalies/errors.
  - [x] **Never log** `reasonText`, `artifactId`, `workflowRunId`, actor free-text, tokens, or any business/PII content. Add a **negative test** asserting the payload keys are exactly the allowed set (no `reasonText`, no `message`, no `artifactId`).
  - [x] Pin each new log line at its level with a focused console-spy test.

### Review Findings

_Code review 2026-06-05 (bmad-code-review) — 3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). 2 decision-needed, 6 patch, 4 deferred, 10 dismissed as noise/intentional. Findings verified against source, not just the diff._

_**Resolution (2026-06-05):** both decisions resolved by Alex (D1→minimal-patch+defer, D2→reconcile-patch). All 8 resulting patches APPLIED. Gates green: `tsc -b` 0 errors · `eslint --max-warnings=0` 0 · `lint:rules-test` 4/4 · `vitest run` 452 passed (51 files, +8 new) · `prettier` clean._

**Decision-needed (RESOLVED by Alex 2026-06-05):**

- [x] [Review][Decision→Patch+Defer] UI-side stale detection is unwired and incomplete → **Option 2 (minimal patch)**: fix `isStaleAgainst` to also compare `currentContextBundleVersion` now (correctness); the full container wiring (refetch-compare source + mutation short-circuit) is **deferred** to a follow-up. See the matching `[Patch]` (contextBundle fix) + `[Defer]` (full wiring) bullets below. [`approvalDecisionView.ts:215-238`, `ApprovalDecisionBarContainer.tsx:109-111`]
- [x] [Review][Decision→Patch] Post-submit summary persists indefinitely → **Option 1 (reconcile-and-clear)**: clear `lastDecision` when a later refetch shows a new `currentState`, so the bar re-renders against fresh data. Converted to the `[Patch]` bullet below. [`ApprovalDecisionBarContainer.tsx:65,105-111`]

**Patch (fixable without input):**

- [x] [Review][Patch] (from Decision-1) `isStaleAgainst` ignores `currentContextBundleVersion` — add the third comparison so a context-bundle bump alone is detected as stale (UI-side correctness; AC6). [`approvalDecisionView.ts:215-238`]
- [x] [Review][Patch] (from Decision-2) Reconcile-and-clear the post-submit summary when the workflow state advances — when `useWorkflowDetail`/`useAllowedActions` report a new `currentState`/version, clear `lastDecision` so `locked` doesn't outlive the decision it recorded (AC9 "until the next workflow state change"). [`ApprovalDecisionBarContainer.tsx:65,105-111`]

- [x] [Review][Patch] Allowed-actions load failure masquerades as the benign "specification not yet available" blocked state — on `allowedActionsQuery.isError` the container only `console.warn`s; the view is built with empty actions → `blocked` with `ARTIFACT_UNAVAILABLE_REASON`, indistinguishable from the dormant-artifactId case. A transport/`RUN_NOT_FOUND` load error needs a distinct error/retry affordance. [`ApprovalDecisionBarContainer.tsx:67-87,174-184`]
- [x] [Review][Patch] Post-submit summary drops the AC9 audit event reference — AC9 requires "the linked `currentState`/event reference for audit," but `DecisionSummary` carries only decision/state/timestamp/actor and `setLastDecision` discards the response `correlationId`. Thread `correlationId` into the summary. [`ApprovalDecisionBarContainer.tsx:123-128,154-159`, `approvalDecisionView.ts:68-77`]
- [x] [Review][Patch] Stale CTA omits the specific new version AC6 calls for — the `stale` branch renders a fixed generic line; `versionStamp.currentSpecArtifactVersion` is available to say what it changed to (AC6 e.g. "updated to v4"). [`ApprovalDecisionBar.tsx:274-292`]
- [x] [Review][Patch] `resolveSpecArtifactId` returns the array-first spec, not the latest version — `.find(artifactType==='spec')` ignores version ordering; with multiple spec entries the mutation could target a stale `artifactId` (forward-looking — the id field is dormant today). [`approvalDecisionView.ts:360-366`]
- [x] [Review][Patch] `resolveApprovalBarState` doc-comment contradicts the code — the header documents precedence `... blocked > disabled ...` but the body resolves stub-mode `disabled` BEFORE the `blocked` check. The behavior (stub → disabled) is intended; fix the doc-comment/precedence string to match. [`approvalDecisionView.ts:300-344`]
- [x] [Review][Patch] Approve-path idempotency-key reuse-across-retry is untested — only `useRejectSpec` has the "mints one key, reuses on retry" test; the relocated/rewritten `useApproveSpec` asserts the AC7 invariant only transitively. Add the equivalent approve retry-key MSW test. [`useApproveSpec.test.tsx`]

**Deferred (real, postponed):**

- [x] [Review][Defer] (from Decision-1) Full UI-side stale-detection wiring — wire `localUi.stale` from a refetch-compare source (poll/focus refetch of the version stamp) and short-circuit `handleApprove`/`handleReject` against a known-stale stamp. Deferred per Alex's Option 2; the 409 path covers stale live today, and the proactive refetch-compare source doesn't exist in the container yet. [`ApprovalDecisionBarContainer.tsx:109-111,113-164`]
- [x] [Review][Defer] Rejection dialog has no focus trap despite `aria-modal="true"` [`ApprovalDecisionBar.tsx:141-150`] — deferred to 2.25 (full WCAG/axe; Escape-inert is INTENTIONAL per AC8 non-dismissible-except-Cancel; focus-in + restore-on-close already implemented).
- [x] [Review][Defer] Decision timestamp is a raw client-clock ISO string [`ApprovalDecisionBarContainer.tsx:126`, `ApprovalDecisionBar.tsx:88`] — deferred; `WorkflowStateChangeResponse` carries no server timestamp, so client clock is the only source; relative/locale formatting is polish.
- [x] [Review][Defer] Untrusted actor rendered as markdown source rather than plain text [`approvalDecisionView.ts:369-378`, `ApprovalDecisionBar.tsx:94,411`] — deferred to a 2.25 security pass; the story sanctioned the `SafeMarkdownRenderer` barrel and the real link/HTML policy lives there (out of this diff).
- [x] [Review][Defer] `currentState` empty-string fallback flows downstream [`ApprovalDecisionBarContainer.tsx:81`] — deferred; low-impact, a `?? ''` default feeding the label/summary rather than a not-ready guard.

## Dev Notes

### THE CENTRAL RECONCILIATION (read this first)

2.19 is **sharper** than its sibling 2.18. Where 2.18 had exactly **one** live seam (the `answerClarification` mutation) and an otherwise-dormant region, 2.19 is the **data-layer story** — it lights up **three** live seams, and the dormancy boundary moves from "the whole read model" down to a **single missing field: `artifactId`**.

**What is genuinely LIVE (this story delivers it):**

- **`useApproveSpec` + `useRejectSpec` mutations.** Both `POST .../approve-spec` and `POST .../reject-spec` exist in the generated `schema.d.ts` (story 2.13 done). `useApproveSpec` already exists as a compile-proof **scaffold** inside `useWorkflowMutation.ts:146–160` (its own comment says "the approval decision-bar UI calls this; not built here … story 2.13/2.19"). `useRejectSpec` does **not** exist yet — author it live here. Both ride the `useWorkflowMutation` factory (idempotency-key reuse + invalidation cascade), exactly like `useSubmitClarification`.
- **`useAllowedActions` flips disabled → LIVE.** The `GET .../allowed-actions` endpoint exists (story 2.14 done; `schema.d.ts:64–75`). Today the frontend hook is a **disabled stub** (`useAllowedActions.ts`, `enabled:false`, throws) — and 2.18 T5 explicitly says "Re-wiring it live is the Approval-Bar / data-layer story's job, not this one." **That story is 2.19.** Flip it to a real `apiClient.GET`. This gives the bar a **live** action list (`actions: string[]`) + version stamp for gating + stale-detection.

**The dormancy boundary — `artifactId` (the ONE thing missing):**

- The approve/reject request bodies REQUIRE `artifactId` (+ `expectedArtifactVersion` + `expectedContextBundleVersion`). The two **version ints are live** — derive them from `AllowedActions.versionStamp.currentSpecArtifactVersion` / `.currentContextBundleVersion`. But **no live read model exposes the spec's `artifactId`**: `WorkflowDetail.latestArtifacts[]` carries `artifactType`/`status`/`version` only (no id — confirmed in 2.18), and `useArtifact` (story 2.17) is still a dormant stub. So the bar can **read live allowed-actions and render gating/staleness for real**, but it **cannot FIRE a mutation end-to-end** until an `artifactId` source ships. The mutations are authored live + MSW-tested; their firing is exercised via fixtures today. When the container cannot resolve a concrete `artifactId`, the bar renders `blocked` ("specification not yet available for a decision") — **never a dead primary button**. The day an artifactId source lands (ARP `useArtifact` live, or a `latestArtifacts`/versionStamp id field), the bar goes fully live with **zero component changes**.

**Two epic ACs reference fields that DO NOT exist in the live schema → frontend-derived / DORMANT:**

- **AC5(c) `disabledActions: { [action]: reasonCode }`** — the live `AllowedActions` schema has **only** `actions: string[]` + `versionStamp` (`schema.d.ts:193–206`). The backend reports an action's **presence/absence**, never a reason code for *why* it is unavailable. Build the reason-code → text **mapping table** (so the contract holds when the field ships), but live the bar derives a **generic** blocked explanation ("no decision is available in the current state"); the specific reason is fixture-driven.
- **AC6 `expectedAllowedActionsVersionStamp`** as a single request field — the request bodies have **no** such field. The composite stamp is **sent as the two int fields** `expectedArtifactVersion` + `expectedContextBundleVersion`; `lastEventId`/`workflowState` are used UI-side for stale-detection only.
- **AC13 `pendingClarifications` count** — absent from `WorkflowDetail` (2.18). The "{N} clarifications pending — approval blocked" message is fixture-driven; live, an absent `approve_spec` renders the generic blocked text.

**The design rule (mirror 2.17/2.18 exactly):** Build `ApprovalDecisionBar` to the **full epic anatomy**, presentational + prop-driven; drive every render in tests from **constructed `ApprovalDecisionView` fixtures**. Live-reachable today: the bar reads **real** allowed-actions + version stamp and renders `ready`/`blocked`/`stale`/`disabled` against live data; the **decision-firing** path (`success`/`error`/`locked` after a real approve/reject) is exercised via MSW/fixtures until `artifactId` is sourceable. Never fabricate an `artifactId` or a clarification count from `WorkflowDetail`.

### `taggedFeedback` is UPPERCASE (epic wrote it lowercase)

The epic AC8 lists `missing_scope` / `unclear_specification` / `misunderstood_implementation`. The **generated wire contract** (`RejectSpecRequest.taggedFeedback`, `schema.d.ts:309–310`) is the **UPPERCASE** enum: `"MISSING_SCOPE" | "UNCLEAR_SPECIFICATION" | "MISUNDERSTOOD_IMPLEMENTATION"`. Use the schema values; render human labels in the radio UI.

### 2.19 is an epic-2b story — self-contained against backlog siblings

`epic-2b: deferred`; **2.21 / 2.23 / 2.25 / 2.27 are still `backlog`.** Consequences (identical posture to 2.18):

- **Rejection dialog + button hierarchy:** build **region-local**. Do NOT import 2.23's `<ConfirmationDialog>` / `<RationaleCaptureDialog>` / button-hierarchy primitives — they do not exist. 2.23 may later extract what you build.
- **Inline feedback / post-submit summary:** region-local. Do NOT import 2.21's `<InlineFeedback>` / `<PersistentStateBadge>` / `<ActionLifecycleIndicator>` / `<Toast>` (`sonner`) — 2.21 is backlog. Workflow-significant feedback is **inline, never toast** (UX-DR15).
- **Tests:** fixture-driven RTL, **not** `toMatchSnapshot` (2.27's harness does not exist). Full axe/contrast deferred to 2.25.

### What already exists — REUSE, do not rebuild

| Capability | Location | Use |
|---|---|---|
| `useWorkflowMutation` (factory) | `features/workflows/hooks/useWorkflowMutation.ts` | Base for `useApproveSpec` (relocate scaffold) + `useRejectSpec` (new). Idempotency-key reuse + `detail(runId)`/`lists()` invalidation. |
| `useApproveSpec` scaffold | `features/workflows/hooks/useWorkflowMutation.ts:146–160` | Promote to its own hook the bar calls; bound to the live `approveSpec` op. |
| `useSubmitClarification` | `features/workflows/hooks/useSubmitClarification.ts` | The exact live-mutation template (header, `unwrap`, typed body) to copy for reject/approve. |
| `useAllowedActions` (disabled stub) | `features/workflows/hooks/useAllowedActions.ts` | **Flip to LIVE** `apiClient.GET` here (the 2.18-deferred wiring). |
| `workflowKeys.allowedActions(runId)` / `.detail(runId)` | `lib/queryKeys/workflowKeys.ts` | Existing keys — never inline (`no-inline-query-keys`). |
| `ProblemDetailsError` / `isProblemDetailsError` | `lib/api/problemDetails.ts` | Typed `code`/`retryable` → `APPROVAL_VERSION_MISMATCH` → `stale` state. |
| `StateSignifierChip` | `features/workflows/components/WorkflowStateBadge.tsx:26–66` | Non-color signifier (icon + label) for warning/stale. NOT `WorkflowStateBadge`. |
| `EmptyState` / `LoadingState` / `ErrorState` | `@/components/feedback` (barrel) | error/blocked surfaces. `no-untyped-loading-state` forbids raw spinners. |
| `SafeMarkdownRenderer` | `@/lib/sanitization` (**barrel only**) | Any untrusted decision-context text (actor identity from agent output, reason echoes). |
| `ContextPanelSlot` / `AppShellContext` / `DESKTOP_MAIN_MIN_WIDTH` | `features/workflows/ContextPanelSlot.tsx`, `AppShell.tsx` | Artifact-primacy floor for the sticky-footer layout. |
| `densityGap(density)` | `lib/density.ts` | `gap-1`/`gap-2` spacing. |
| `useWorkflowDetail` | `features/workflows/hooks/useWorkflowDetail.ts` | Decision-context label + `currentState` (live). |
| Run-detail + ARP routes | `routes/workflows/$workflowRunId/index.tsx`, `.../artifacts/$artifactId.tsx` | Mount points for sticky-footer + inline-section variants. |
| `tools/eslint-rules/` (custom rules + `lint:rules-test`) | `deliveryline-frontend/tools/eslint-rules/` | Where an AC11 eligibility-import guard would live IF added (see OQ-5). |

### Live data contract (what the schema actually exposes)

- **`GET .../allowed-actions`** → `AllowedActions { actions: string[]; versionStamp: AllowedActionsVersionStamp }`. `versionStamp = { currentContextBundleVersion?: int|null; currentSpecArtifactVersion?: int|null; lastEventId?: string|null; workflowState: string }`. **No `disabledActions` field.** Read-only, no Idempotency-Key. (`schema.d.ts:64–75, 193–231`)
- **`POST .../approve-spec`** → `ApproveSpecRequest { artifactId; expectedArtifactVersion: int; expectedContextBundleVersion: int; reason?; reviewerRole? }` + `Idempotency-Key` header → **200** `WorkflowStateChangeResponse { currentState; workflowRunId; correlationId? }`. Errors: 400 idempotency/payload; 404 `RUN_NOT_FOUND`/`ARTIFACT_RECORD_NOT_FOUND`; **409 `APPROVAL_VERSION_MISMATCH`**/`ILLEGAL_TRANSITION`/`WORKFLOW_RUN_TERMINAL`/`IDEMPOTENCY_KEY_CONFLICT`; 503 `ARTIFACT_PAYLOAD_UNAVAILABLE`. (`schema.d.ts:84–99, 238–246, 450–454, 648–712`)
- **`POST .../reject-spec`** → `RejectSpecRequest { artifactId; expectedArtifactVersion: int; expectedContextBundleVersion: int; reasonText; reviewerRole?; taggedFeedback: "MISSING_SCOPE"|"UNCLEAR_SPECIFICATION"|"MISUNDERSTOOD_IMPLEMENTATION" }` + `Idempotency-Key` → **200** `WorkflowStateChangeResponse`. Same error families. Increments `specRejectionLoopCount` (story 2.10). (`schema.d.ts:138–154, 301–311, 814+`)
- **`WorkflowDetail`** (live read) carries `currentState`, `currentActorIdentity?`, `currentActorType?`, `escalationMarker?`, `specRejectionLoopCount?`, `latestArtifacts[]` (**type/status/version only — no id**), timestamps. **No `pendingClarifications`, no spec `artifactId`.** (`schema.d.ts:338–374`)

### Frontend conventions (architecture)

- Stack: React + TypeScript, Vite, TanStack Query + TanStack Router, shadcn/ui + Tailwind, Vitest + RTL + MSW. [Source: architecture.md#445–520]
- **Backend-reported allowed actions, never frontend inference** — the bar must not compute approval eligibility; it only renders the backend `actions[]`. [Source: architecture.md#267, #485; UX-DR12]
- **Runner/agent output is untrusted** — sanitize any agent-originated decision-context text via the `@/lib/sanitization` barrel. [Source: architecture.md#507, #518]
- Mutation hooks live under `features/workflows/hooks/`; each invalidates the affected queries on success. [Source: epics.md#956]
- Component files PascalCase `.tsx`; pure helpers/view-models in sibling `.ts` (react-refresh `only-export-components`). Query keys via the factory, never inline.

### Traps (do NOT step on these)

- **T-ARTIFACTID — `artifactId` has no live source.** Never fabricate it from `WorkflowDetail.latestArtifacts` (no id field) or invent one. Unresolved `artifactId` → `blocked` state. The mutation firing is fixture/MSW-tested only, until an artifactId-read seam ships. This is the exact 2.6/2.17/2.18 anti-fabrication discipline.
- **T-ALLOWEDACTIONS-LIVE — flip the stub, don't fork it.** `useAllowedActions` becomes a real `GET`; reuse the existing key. Grep every caller before flipping (2.18's ClarificationRegion path treats it as dormant — confirm no fabricated render appears once it returns real data).
- **T-NO-DISABLEDACTIONS — the backend gives no reason codes.** `AllowedActions` is `actions[]` + `versionStamp` only. Build the reason-map table but feed it from fixtures; live fallback is generic blocked text. Don't assert a live `disabledActions` field exists.
- **T-VERSIONSTAMP — two ints, not one stamp.** Send `expectedArtifactVersion` + `expectedContextBundleVersion` derived from `versionStamp`. There is no `expectedAllowedActionsVersionStamp` request field.
- **T-TAGGED-UPPERCASE — `taggedFeedback` is UPPERCASE.** Use `MISSING_SCOPE`/`UNCLEAR_SPECIFICATION`/`MISUNDERSTOOD_IMPLEMENTATION` (schema), not the epic's lowercase.
- **T-MODAL — 2.23 primitives do not exist.** Build the rejection dialog region-local; no `<ConfirmationDialog>`/`<RationaleCaptureDialog>` import.
- **T-FEEDBACK — 2.21 primitives do not exist + no toast.** Inline/persistent feedback region-local; workflow-significant outcomes are NEVER toast-only (UX-DR15). `sonner` is not wired.
- **T-ONE-PRIMARY — never promote an unavailable action.** If no safe primary action, render `blocked`; do not render a disabled primary button (AC3/AC7).
- **T-CHIP — decision status ≠ workflow-state badge.** Use `StateSignifierChip` (generic semantic chip), NOT `WorkflowStateBadge`.
- **T-SUMMARY-PERSIST — never auto-clear the outcome.** The post-submit summary persists until the next workflow state change (AC9/UX-DR19).
- **T-LOG-PII — never log `reasonText`/`artifactId`/run id/actor free-text.** Field-only structured logs + an exact-key-set negative test.
- **T-GATES — run gates via PowerShell, not Bash.** [memory: `rtk-hook-only-matches-bash`] Use native file tools + PowerShell. Run `prettier --write` before finishing or the format gate cascades and skips downstream jobs [memory: `prettier-gate-cascades-ci`].
- **T-REFRESH — pure helpers in `.ts`, not `.tsx`.** [memory: `frontend-react-refresh-no-fn-exports`] All view-model functions/maps in `approvalDecisionView.ts`; the `.tsx` exports only the component.
- **T-LOCKFILE — no new npm dependency expected** (TanStack + sanitization + feedback already present) → no lockfile change → no WSL2/Linux-CI smoke [memory: `frontend-lockfile-cross-platform`, `wsl-linux-ci-reproduction`]. If you add one, regenerate the lockfile and Linux-verify before pushing.

### Open questions (resolved with recommendations — proceed unless told otherwise)

- **OQ-1 — wire `useAllowedActions` live, or keep it stubbed like 2.18?** *Recommendation:* **wire it LIVE.** The endpoint genuinely exists (2.14 done), 2.18 T5 explicitly assigns the wiring to this story, and the bar's gating/stale-detection need real data. The dormancy moves to `artifactId` (mutation firing), not the read. Grep callers first.
- **OQ-2 — relocate `useApproveSpec` or leave the scaffold in the factory file?** *Recommendation:* **relocate** to `hooks/useApproveSpec.ts` (its own file, like `useSubmitClarification`) so the factory file holds only the generic factory; re-export or delete the scaffold. Author `useRejectSpec` as a sibling. Keeps hooks discoverable + consistent.
- **OQ-3 — how to handle the missing `artifactId` for mutation firing?** *Recommendation:* container resolves `artifactId` from whatever live source exists (none today) → falls back to `blocked` ("specification not yet available for a decision"). Mutations authored live + MSW-tested; firing reachable via fixtures. One documented line flips it live when an artifactId-read seam ships. Do NOT fabricate (T-ARTIFACTID).
- **OQ-4 — accessibility depth.** *Recommendation:* deliver the AC10 baseline (keyboard reachability, explicit verb labels, `aria-describedby` for disabled rationale, one ARIA live region, focus restoration on dialog close) + non-color signifiers; defer full WCAG AA contrast/axe to 2.25 (no axe harness). Document the deferral.
- **OQ-5 — the AC11 "ArchUnit-equivalent ESLint rule".** *Recommendation:* there is **no** permission-inference module to forbid importing (eligibility lives entirely backend-side), so a full custom ESLint rule is low-value today. Ship a **documented convention** + a focused import-graph unit test asserting the bar/container import only `useAllowedActions`; defer a generalized rule to 2.31's rule-set story. Note the deferral.
- **OQ-6 — `reviewerRole` sourcing.** *Recommendation:* `reviewerRole` is optional on both requests and there is **no live actor-role context** in the frontend yet (2.6 deliberately dropped `viewerAuthorized`; `PermissionRestrictedState` defers to the role-context story). Omit `reviewerRole` (or pass a documented placeholder) until a role-context story ships; do not invent a role.

### Logging Requirements (project-wide standard, frontend-adapted)

Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. Equivalent contract: structured `console.info` / `console.warn({ event, …fields })`, **field-only** (never `error.message`, `reasonText`, `artifactId`, run/actor ids, tokens, PII), pinned by `console`-spy tests with an exact-key-set negative assertion. See the Logging instrumentation task for exact event names + field sets.

### Project Structure Notes

New + modified files (mirror the 2.17/2.18 layout):

```
deliveryline-frontend/src/
├── features/workflows/
│   ├── approvalDecisionView.ts                    (NEW — types + pure helpers + reason-map)
│   ├── approvalDecisionView.test.ts               (NEW)
│   ├── hooks/
│   │   ├── useAllowedActions.ts                   (MODIFIED — disabled stub → LIVE GET)
│   │   ├── useAllowedActions.test.tsx             (NEW — live MSW)
│   │   ├── useApproveSpec.ts                      (NEW — relocated live scaffold)
│   │   ├── useApproveSpec.test.tsx                (NEW — live MSW)
│   │   ├── useRejectSpec.ts                       (NEW — live mutation)
│   │   ├── useRejectSpec.test.tsx                 (NEW — live MSW)
│   │   └── useWorkflowMutation.ts                 (MODIFIED — remove/relocate useApproveSpec scaffold)
│   └── components/
│       ├── ApprovalDecisionBar.tsx                (NEW — presentational)
│       ├── ApprovalDecisionBar.test.tsx           (NEW)
│       ├── ApprovalDecisionBarContainer.tsx       (NEW — thin container)
│       └── ApprovalDecisionBarContainer.test.tsx  (NEW)
├── test/fixtures/approval/
│   └── approvalDecisionFixtures.ts                (NEW)
├── routes/workflows/$workflowRunId/index.tsx                 (MODIFIED — mount sticky-footer bar)
└── routes/workflows/$workflowRunId/artifacts/$artifactId.tsx (MODIFIED — mount inline-section bar in ARP, optional)
```

No new npm dependency expected → **no lockfile change → no WSL2/Linux-CI smoke needed**.

### Testing standards

- **Fixture-driven RTL — never `toMatchSnapshot`** (2.27's harness does not exist). Assert on roles/text/`data-approval-bar-*` attributes.
- Component tests stay **router-free**: `vi.mock('@tanstack/react-router', …)` (pattern in `RunContextStrip.test.tsx` / `ClarificationRegion.test.tsx`).
- Mutation/allowed-actions tests use MSW (live endpoints) or a mocked TanStack Query backend feeding sequential states.
- Pin `Date.now()` / `vi.setSystemTime` for the relative-time post-submit summary (mirror 2.16/2.18).
- Console-spy assertions for every new log line + an exact-key-set negative test (no `reasonText`/`artifactId`/`message`).

### Gate verification (run via PowerShell — `rtk-hook-only-matches-bash`)

```
tsc -b                                    # 0 errors
eslint . --max-warnings=0                 # 0 (incl. no-unsanitized-html, no-inline-query-keys, react-refresh)
npm run lint:rules-test                   # custom-rule suites green
vitest run                                # full suite green (+ new approval tests)
prettier --write "src/**/*.{ts,tsx}"      # before finishing (prettier-gate-cascades-ci)
```

### References

- [Source: epics.md#Story-2.19 lines 1276–1296] — story statement, 13 ACs (mode contract, anatomy, states, variants, backend-reported gating, version stamps, one-primary-action, rejection confirmation, post-submit summary, a11y, responsibility boundary, test coverage, clarification-gating).
- [Source: epics.md#1146, #1170, #1180] — `getRunSummary.pendingClarifications` (backend-only, not REST-exposed), allowed-actions version-stamp contract, `expectedAllowedActionsVersionStamp` echo intent.
- [Source: ux-design-specification.md#Approval-/-Decision-Bar lines 1526–1579] — anatomy, 8 states, 5 variants, accessibility, content guidelines, interaction behavior, responsibility boundary (UX-DR12).
- [Source: ux-design-specification.md#UX-DR15] — feedback inline-vs-toast boundary; workflow-significant outcomes never toast-only.
- [Source: ux-design-specification.md#UX-DR18 / UX-DR19] — confirm-before-consequential-action; one visually primary action per decision area; post-decision state persists.
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:64–99, 138–154, 193–246, 301–311, 338–374, 450–454, 648–712, 814+] — live `getAllowedActions`, `approveSpec`, `rejectSpec`; `AllowedActions` (no `disabledActions`), `AllowedActionsVersionStamp`, `ApproveSpecRequest`, `RejectSpecRequest` (UPPERCASE `taggedFeedback`), `WorkflowStateChangeResponse`, `WorkflowDetail` (no `pendingClarifications`/`artifactId`).
- [Source: deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts:1–19, 138–160] — factory invariants + the `useApproveSpec` scaffold tagged for story 2.19.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useSubmitClarification.ts] — the exact live-mutation template (header, `unwrap`, typed body) to mirror.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts:1–25] — the disabled stub to flip live (2.18 T5 deferred this to the Approval-Bar story).
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx, .../artifacts/$artifactId.tsx] — sticky-footer + inline-section mount points.
- [Source: 2-18-clarification-region-with-visible-incorporation-lifecycle-wiring.md] — the dormant/live discipline, presentational/container split, region-local feedback (2.21/2.23 backlog), logging pattern, and the explicit "useAllowedActions wiring is 2.19's job" handoff (T5).
- [Source: 2-17-artifact-review-panel-generalized-composite-spec-variant.md] — generalized-composite + dormant `useArtifact` template (the artifactId-read seam 2.19 awaits).
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml] — 2.13/2.14/2.10/2.24 done; 2.19/2.21/2.23/2.25/2.27 backlog; `epic-2b: deferred`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Gates (PowerShell per `rtk-hook-only-matches-bash`): `tsc -b` → 0 errors; `eslint . --max-warnings=0` → 0; `npm run lint:rules-test` → 4/4 custom-rule suites green; `vitest run` → **444 passed (51 files)**; `prettier --check` on all new/changed files → clean.
- T-ALLOWEDACTIONS-LIVE due diligence: grepped every `useAllowedActions` caller before flipping. Only two live consumers — `ArtifactReviewPanelContainer` (gates compare on `actions.includes('compare')` + a dormant artifact, so no fabricated render) and `useAssertRunContextLoaded` (no production consumer; its own test mocks the hook). Both verified green post-flip.

### Completion Notes List

- **Three LIVE seams delivered (the data-layer story):** (1) `useAllowedActions` flipped disabled-stub → real `apiClient.GET` (the 2.18 T5 handoff); (2) `useApproveSpec` relocated out of the `useWorkflowMutation.ts` scaffold into its own live hook (OQ-2) — the factory file now holds only the generic factory, and `useWorkflowMutation.test.tsx` re-points its import to exercise the factory through the relocated hook; (3) `useRejectSpec` authored new on the same factory.
- **Dormancy boundary = the single `artifactId` field.** `resolveSpecArtifactId(detail)` is the ONE documented seam — it READS a forward-compat `artifactId` off the spec `latestArtifacts` entry (absent today → `undefined` → bar renders `blocked`, never a dead primary; T-ARTIFACTID). The firing path is exercised honestly in the container test by serving that field via MSW (real wiring, not fabrication). The day a read model ships the id, the bar goes fully live with zero component changes.
- **Generalized composite (AC1):** exhaustive `switch(mode)` + `assertNeverMode`; `spec_approval` full, `implementation_review`/`recovery_operator` documented placeholders (`disabled` state).
- **State machine** `resolveApprovalBarState` follows the documented precedence `locked > error > stale > submitting > blocked > disabled > success > ready`; `APPROVAL_VERSION_MISMATCH` renders `stale` (refresh CTA), not generic `error` (AC6). Two version ints sent (T-VERSIONSTAMP), not a single stamp; `taggedFeedback` UPPERCASE (T-TAGGED-UPPERCASE).
- **Region-local (2.21/2.23/2.25/2.27 backlog):** rejection rationale dialog (non-dismissible without explicit Cancel, focus-in on open + restore on close), inline post-submit summary (persists read-only via `locked`, never toast — UX-DR15/T-SUMMARY-PERSIST), submitting indicator (non-spinner). Reused `StateSignifierChip` (T-CHIP), `ErrorState`, and `SafeMarkdownRenderer` (barrel) for the potentially agent-echoed actor identity.
- **DORMANT per the live schema:** AC5c `disabledActions` reason-codes (mapping table built, fed by fixtures; live = generic blocked text) and AC13 `pendingClarifications` (fixture-driven message; live = absent `approve_spec` → generic blocked).
- **Logging:** field-only structured `console.info`/`console.warn` (`approval.approveSubmit`/`rejectSubmit`/`versionMismatch`/`submitError`/`allowedActionsLoadError`) with an EXACT-key-set negative test asserting no `reasonText`/`artifactId`/`workflowRunId`/`message` (T-LOG-PII).
- **AC11/OQ-5:** eligibility-import guard shipped as a focused import-graph unit test (no permission-inference module exists to forbid; a generalized ESLint rule deferred to 2.31, noted).
- **No new npm dependency → no lockfile change → no WSL2/Linux-CI smoke needed.**

### File List

**New:**
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.ts`
- `deliveryline-frontend/src/features/workflows/approvalDecisionView.test.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useApproveSpec.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useRejectSpec.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useRejectSpec.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.eligibility.test.ts`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBarContainer.tsx`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBarContainer.test.tsx`
- `deliveryline-frontend/src/test/fixtures/approval/approvalDecisionFixtures.ts`

**Modified:**
- `deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts` (disabled stub → LIVE GET)
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.ts` (removed/relocated `useApproveSpec` scaffold; factory-only)
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowMutation.test.tsx` (import re-pointed to `./useApproveSpec`)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (mount sticky_footer bar)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` (mount inline_section bar)

### Change Log

- 2026-06-05 — Story 2.19 implemented (bmad-dev-story): Approval/Decision Bar generalized composite (spec_approval mode) + the three live data-layer seams (useAllowedActions live, useApproveSpec relocated, useRejectSpec new). All 13 ACs satisfied; all gates green (444 tests). Status ready-for-dev → in-progress → review.
