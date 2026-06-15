# Story 2.21: Feedback Patterns Infrastructure (Inline / Persistent / Toast Boundaries)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer building any composite that triggers a mutation or surfaces a workflow-significant outcome,
I want shared feedback infrastructure that distinguishes inline / persistent-in-component / toast feedback per documented rules (UX-DR15) and connects feedback to workflow effect rather than generic UI success,
so that "answer received" is never confused with "answer incorporated" and no workflow-significant state change is communicated only by toast.

## ⚠️ Read first — scope, the ordering inversion, and what this story is NOT

**Scope in one line:** ship the **shared feedback primitives** under `src/components/feedback/primitives/` — `<InlineFeedback>`, `<PersistentStateBadge>`, `<ActionLifecycleIndicator>`, and a typed `feedbackToast` wrapper over `sonner` — plus a NEW custom ESLint rule (`local-rules/no-workflow-toast-success`) that enforces UX-DR15's "no workflow-significant outcome by toast alone," plus fixtures + a PrimitivesPlayground section + fixture-driven RTL tests. Frontend-only. **Additive.**

**🔁 THE ORDERING INVERSION (read this before anything else):** This is an *infrastructure* story whose two primary consumers — **2.18 Clarification Region** and **2.19 Approval/Decision Bar** — already shipped **`done`** *ahead* of it (active-slice pull order put the composites first). Both built **region-local** equivalents of exactly these primitives, and both left an explicit note: *"2.21 may later extract/generalize what you build."* (2.18 T6/T7, 2.19 T-FEEDBACK.) So this story is an **extraction/generalization**, not a greenfield build, and the over-riding constraint is: **it must be purely additive and must NOT regress 2.18 or 2.19.**

**This story does NOT:**
- **NOT** rip out or rewire 2.18's / 2.19's region-local inline-feedback or lifecycle indicators. Those are `done`, heavily tested (44+ and 51+ test files green), and retrofitting them risks regressing two closed stories. The primitives here are designed as **drop-in supersets**, and the retrofit is an explicit **deferred follow-up** (see OQ-1 / the Reconciliation). The ESLint rule is net-new and additive — neither 2.18 nor 2.19 calls `toast`, so the rule lights up clean.
- **NOT** wire feedback into the mutation hooks (`useApproveSpec`/`useRejectSpec`/`useSubmitClarification`). AC3's "mutation hooks emit feedback in canonical sequence" is satisfied by **providing the primitives + the lifecycle helper** the hooks/composites *can* drive — not by editing the (done) hooks. The composites place feedback inline (AC4); this story ships the toolkit.
- **NOT** build a visual-regression *snapshot* harness — story **2.27** owns that and is `backlog`. AC7's "visual regression snapshot" is adapted to **PrimitivesPlayground fixtures + fixture-driven RTL render tests** (the exact 2.18/2.19/2.20 discipline: assert roles/text/`data-*`, never `toMatchSnapshot`).
- **NOT** the full WCAG AA / axe audit — that's **2.25** (`backlog`). Ship the ARIA-live + non-color-signifier baseline (AC6) and a focused render test; defer the audit.
- **NOT** the full responsive/mobile breakpoint pass — that's **2.26** (`backlog`). Ship the sonner position config + keep inline feedback region-attached (AC9); defer breakpoint-matrix testing.
- **NOT** modal/confirmation or button-hierarchy infra — that's **2.23** (`backlog`).
- **NOT** add an npm dependency. `sonner@2.0.7` is already installed and `<Toaster>` is already mounted in `AppShell.tsx`. No new dep ⇒ no lockfile change ⇒ no WSL2/Linux-CI smoke needed [memory: `frontend-lockfile-cross-platform`, `wsl-linux-ci-reproduction`].

If you find yourself editing `ClarificationRegion.tsx`, `ApprovalDecisionBar.tsx`, or any mutation hook to *consume* the new primitives — **stop**. That retrofit is deferred (OQ-1). This story ends at the four primitives + the ESLint rule + fixtures + playground + tests.

## 🔑 Reference decoder — "UX-DR15" is not a literal string in the UX spec

The epic AC text cites `UX-DR15`. Map it before you read (same indirection 2.18/2.20 documented):

- **"UX-DR15 / feedback patterns"** → UX spec section **"Feedback Patterns"** (lines **1784–1823**): *"Feedback should communicate workflow truth, not generic UI success."* Hard rules this story encodes:
  - Inline status feedback for **local** actions (clarification submit, compare availability).
  - **Persistent** status treatment **inside the relevant component** for workflow-significant outcomes.
  - Toast **only** for lightweight confirmation — **never as the sole record of an important workflow transition**.
  - Blocker/stale/failure visually **stronger** than informational.
  - Distinguish: *submitted → accepted by system → incorporated into workflow state → blocked → failed.*
  - **"Do not collapse 'answer received' and 'answer incorporated' into one message."**
  - If an action changes the workflow, the new state is visible **in the same region where the user acted**.
  - If an action fails because state is stale, the UI explains **what changed → what to do next**.
  - ARIA live regions for async updates; **never color alone**; feedback connected to its triggering component.
- **"UX-DR11 / lifecycle vocabulary"** → UX spec **"Clarification Region"** states (lines 1489–1497) + **2.12** backend lifecycle: `submitted → accepted → incorporated` (the `<ActionLifecycleIndicator>` chain).
- **"story 2.3 AC5 / non-color signifier"** → semantic state is **never color-alone** (icon + label). Source: `src/lib/state-signifiers.ts` (`STATE_SIGNIFIERS`, `StateName`).
- **Architecture invariant** → `architecture.md:1182` ("Frontend empty, stale, conflict, no-actions, and failed-load states live under `components/feedback`") — the primitives extend that module; `architecture.md:516` (TanStack Query owns server state; the primitives are presentational, hold no server state).

## 🧩 What already exists (compose / generalize — do NOT rebuild)

| Capability | Location | Use in this story |
|---|---|---|
| `sonner` toast lib + `<Toaster>` host | dep `sonner@2.0.7`; `src/components/ui/sonner.tsx` exports `Toaster`; **mounted** in `src/features/workflows/AppShell.tsx` (and `src/dev/PrimitivesPlayground.tsx`) | The `feedbackToast` wrapper calls sonner's `toast(...)`. Do NOT add a second `<Toaster>` or a new dep. |
| `STATE_SIGNIFIERS` / `StateName` (12-state union) + `STATE_NAMES` | `src/lib/state-signifiers.ts` | **The** non-color signifier contract (lucide icon name + label per state). `PersistentStateBadge` + `InlineFeedback` consume THIS (generic lib), not the feature-layer chip — see T-LAYERING. |
| `--state-*` semantic tokens (informational/success/warning/blocker/error/stale/… + `-foreground`/`-border`/`-hc`) | `src/styles/globals.css` (≈87–177) | Variant colors. `warning`/`blocker`/`error` are the DOMINANT prominence tier (UX-DR15 "visually stronger"). |
| `StateSignifierChip` (icon + label, color-safe) | `src/features/workflows/components/WorkflowStateBadge.tsx:26–66` | **Reference** for the icon+label idiom. Do NOT import it into `components/feedback` (layering inversion — T-LAYERING). Mirror its rendering using `STATE_SIGNIFIERS` directly. |
| Region-local lifecycle indicator + `resolveLifecyclePosition` | 2.18 `src/features/workflows/clarificationView.ts` (`resolveLifecyclePosition(status) → { stages:['submitted','accepted','incorporated']; current }`) | The behavior to **generalize** into `<ActionLifecycleIndicator>` + a generic `resolveLifecyclePosition(stages, currentStage)` helper. 2.18's copy stays as-is (additive). |
| Region-local inline "answer submitted" feedback | 2.18 `ClarificationRegion.tsx`; 2.19 `ApprovalDecisionBar.tsx` post-submit summary + stale text | The behavior to **generalize** into `<InlineFeedback>` / `<PersistentStateBadge>`. Not retrofitted here. |
| `EmptyState` / `LoadingState` / `ErrorState` + barrel | `src/components/feedback/{index.ts,states/}` (`export * from './states'`) | The sibling primitives. Extend the barrel: add `export * from './primitives'`. |
| ARIA-live idiom | `ErrorState.tsx` (`<Alert role="alert" aria-live={passive?'polite':'assertive'}>`), `LoadingState.tsx` (`<output aria-live="polite">`), `QueueShell.tsx` (`<div role="status" aria-live="polite" className="sr-only">`) | Reuse the exact `sr-only` + role/`aria-live` idiom. |
| `cn` / `densityGap` | `src/lib/utils.ts`, `src/lib/density.ts` | className merge + spacing. |
| ESLint local-rules plugin | `tools/eslint-rules/index.js` (registers 4 rules), `eslint.config.js` (rules block), `package.json` `lint:rules-test`, `tools/eslint-rules/__tests__/*.test.js` (RuleTester + `node:test`) | The exact 5-touchpoint pattern to add `no-workflow-toast-success` (Task 5). |
| PrimitivesPlayground | `src/dev/PrimitivesPlayground.tsx` (dev-only, lazy via `?playground`; mounts `<Toaster>`) | Add a "Feedback Primitives" section (AC7 fixtures). |
| Sanitization barrel | `@/lib/sanitization` (`SafeMarkdownRenderer` etc., story 2.24) | Only if a primitive ever renders caller-supplied untrusted text. Default: primitives take **trusted** ReactNode/string children authored by the composite. |

**Confirmed by survey — these do NOT exist yet:** `<InlineFeedback>`, `<PersistentStateBadge>`, `<ActionLifecycleIndicator>`, a typed `Toast`/`feedbackToast` wrapper, the `no-workflow-toast-success` ESLint rule, any `src/components/feedback/primitives/` folder, and any production `toast(...)` call site (only the playground calls sonner today).

## Acceptance Criteria

> Story-local ACs refine epic-2.21 ACs 1–10 with concrete file paths + the ordering-inversion reconciliation. Epic AC number in parentheses.

1. **(AC1) Typed primitives exist.** NEW `src/components/feedback/primitives/` exports:
   - `<InlineFeedback variant="info|success|warning|blocker|error" persistsUntil="dismiss|workflowChange|infinite">` — an in-region status block (icon + label + message body), each variant carrying a **non-color signifier** (icon from `STATE_SIGNIFIERS`) and a `staleStateExplanation` prop (AC5). `persistsUntil` is a documented dismissal-semantics prop (renders a dismiss control for `'dismiss'`; `'workflowChange'`/`'infinite'` render none — the consumer controls unmount).
   - `<PersistentStateBadge state={StateName}>` — a non-auto-dismissing badge for a persistent workflow outcome (icon + label keyed to a `StateName`; `SemanticState` ≡ the existing `StateName` union — do NOT mint a parallel union, T-NO-PARALLEL-UNION).
   - `<ActionLifecycleIndicator stages={readonly string[]} currentStage={string}>` — renders an ordered stage chain with the current position highlighted; generalizes 2.18's `submitted → accepted → incorporated`.
   - `feedbackToast` — a typed wrapper over sonner exposing `{ info, success, warning, error }` variants (lightweight ancillary confirmations ONLY; styled to the design system). Exported from a `.ts` (it is a function map, not a component — T-REFRESH).
   All exported via a `primitives/index.ts` barrel, re-exported from `src/components/feedback/index.ts` (`export * from './primitives'`). Component files are PascalCase `.tsx`; pure helpers/maps live in sibling `.ts`.

2. **(AC2) ESLint rule enforces "no workflow outcome by toast alone."** NEW `tools/eslint-rules/no-workflow-toast-success.js` flags toast calls that signal a workflow mutation outcome: in any file that **imports a workflow-mutation hook** (`useApproveSpec`, `useRejectSpec`, `useSubmitClarification`, `useWorkflowMutation`, or any `hooks/use*Spec`/`use*Clarification` mutation per a documented matcher), a `toast.success(...)` / `toast.error(...)` / `toast.warning(...)` / bare `toast(...)` (from `sonner` **or** the `feedbackToast` wrapper) is an **error**. Lightweight ancillary calls (`toast.info`, `toast.message`, `toast.loading`, `feedbackToast.info`) are allowed. An explicit `// eslint-disable-next-line local-rules/no-workflow-toast-success -- <rationale>` escape hatch is documented for the rare sanctioned ancillary case. The rule is registered in `tools/eslint-rules/index.js`, wired in `eslint.config.js` (app `src/**` block, `error`), and its test file is appended to the `lint:rules-test` npm script. It ships with a RuleTester suite (valid: ancillary toast in a mutation file, any toast in a non-mutation file; invalid: `toast.success`/bare `toast` in a mutation file).

3. **(AC3) Canonical lifecycle sequence is renderable.** `<ActionLifecycleIndicator>` + a pure `resolveLifecyclePosition(stages, currentStage)` helper render the canonical `submitted → accepted → incorporated` (or `… → failed`) chain in the originating component's region. This story **provides** the primitive + helper the mutation hooks/composites can drive; it does NOT edit the (done) hooks (Reconciliation / OQ-1). A unit test proves the indicator advances through each stage and marks an off-chain terminal (`failed`/`superseded`) distinctly.

4. **(AC4) Primitives are placed-inline by construction.** The primitives are **region-agnostic, presentational, and self-contained** — they render wherever a composite mounts them (no portal, no global host), satisfying "the new state is visible in the same screen region where the user acted." A render test mounts `<InlineFeedback>` / `<PersistentStateBadge>` inside an arbitrary container and asserts they render in-place (no document-level escape). Retrofitting 2.18/2.19 to use them is OUT OF SCOPE (OQ-1).

5. **(AC5) Stale-state explanation primitive.** `<InlineFeedback variant="warning">` accepts a `staleStateExplanation` prop (a typed `{ whatChanged: string; whatToDoNext: string }` or equivalent ReactNode pair) and renders a structured **"what changed → what to do next"** layout — the shape 2.19's stale-decision state (2.19 AC6) can consume later. A test asserts both segments render and are visually distinguishable (distinct elements/labels), never concatenated into one line.

6. **(AC6) Accessibility baseline.** `<InlineFeedback>` and `<PersistentStateBadge>` use ARIA live regions — `aria-live="polite"` for `info`/`success`, `aria-live="assertive"` for `warning`/`blocker`/`error` — with documented announcement text; toast uses sonner's built-in live region; **every variant carries a non-color signifier** (icon + label from `STATE_SIGNIFIERS`, story 2.3 AC5). Tests assert the correct `aria-live` per variant and the presence of an icon + text label (not color alone). Full axe/contrast audit deferred to 2.25.

7. **(AC7) Variant inventory has fixtures + playground + render tests.** Every UX-DR15 variant (inline informational / inline success-incorporated / warning-stale / blocker / error-failed-action / persistent decision outcome) has a fixture in NEW `src/test/fixtures/feedback/feedbackFixtures.ts`, a section in `src/dev/PrimitivesPlayground.tsx`, and a **fixture-driven RTL render test** (NOT `toMatchSnapshot` — 2.27's snapshot harness is `backlog`). The render test asserts each variant renders with its unique signifier + label. (AC7's "visual regression snapshot" is satisfied by the playground fixtures + RTL render assertions; true pixel-diff is deferred to 2.27 — documented.)

8. **(AC8) "received ≠ incorporated" is structurally enforced.** The canonical `<ActionLifecycleIndicator>` chain renders `submitted` and `incorporated` as **distinct visual elements** — a unit test asserts both are present as separate nodes (distinct `data-stage`/role) and that the default render path never collapses them into a single combined message. Generic custom chains remain supported by AC1 and reject duplicate stage names.

9. **(AC9) Mobile-aware positioning.** The `feedbackToast` wrapper / `<Toaster>` config positions toasts so they do not cover primary action controls (documented sonner `position`/offset), and `<InlineFeedback>` remains attached to its content region in stacked layouts (no fixed/absolute escape). A jsdom test asserts the inline primitive stays in-flow (in its parent) and the toast config exposes the documented position. Full mobile-breakpoint matrix testing is deferred to **2.26** (documented).

10. **(AC10) Test coverage.** Vitest + RTL tests cover: each variant renders with its non-color signifier (AC1/AC6/AC7); ARIA live region announces at the correct urgency per variant (AC6); `ActionLifecycleIndicator` transitions through stages and marks `submitted`/`incorporated` distinctly (AC3/AC8); the ESLint rule catches a forbidden `toast.success`/bare `toast` in a mutation-hook-importing file and passes ancillary `toast.info` + any toast in a non-mutation file (AC2 — RuleTester); `staleStateExplanation` renders structured "what changed → next action" (AC5); inline primitives render in-region, not document-level (AC4/AC9). Plus the logging task's console-spy pin.

## Tasks / Subtasks

- [x] **Task 1 — `actionLifecycle.ts` helper + `<ActionLifecycleIndicator>`** (AC: 1, 3, 8)
  - [x] NEW `src/components/feedback/primitives/actionLifecycle.ts` (pure `.ts`, T-REFRESH): generic `resolveLifecyclePosition(stages: readonly string[], currentStage: string): { stages; currentIndex; isOffChain }` generalizing 2.18's `clarificationView.ts:resolveLifecyclePosition`. Off-chain terminals (`failed`/`superseded` not in `stages`) → `currentIndex = -1`, `isOffChain = true`. Export the canonical `DEFAULT_LIFECYCLE_STAGES = ['submitted','accepted','incorporated'] as const`. Unit-test all branches.
  - [x] NEW `src/components/feedback/primitives/ActionLifecycleIndicator.tsx`: renders the ordered chain; current stage highlighted; each stage a distinct node with `data-stage={name}` + `data-stage-state="done|current|pending|offchain"` (AC8). Non-color signifier per stage (icon + label; reuse `STATE_SIGNIFIERS` where a stage maps to a state, else a neutral step marker). The canonical default renders `submitted` and `incorporated` as separate elements; generic chains reject duplicate names.

- [x] **Task 2 — `<InlineFeedback>` + `staleStateExplanation`** (AC: 1, 4, 5, 6)
  - [x] NEW `src/components/feedback/primitives/InlineFeedback.tsx`: props `{ variant: 'info'|'success'|'warning'|'blocker'|'error'; persistsUntil: 'dismiss'|'workflowChange'|'infinite'; title?; children?; staleStateExplanation?; onDismiss?; className?; testId? }`. Renders icon (from `STATE_SIGNIFIERS` mapping variant→state: info→informational, success→success, warning→warning/stale, blocker→blocker, error→error) + label + body. Variant prominence: `warning`/`blocker`/`error` use the DOMINANT token tier (UX-DR15 "visually stronger"). `persistsUntil='dismiss'` renders a dismiss control wired to `onDismiss`; `'workflowChange'`/`'infinite'` render none. `data-inline-feedback-variant` stamp.
  - [x] ARIA: `aria-live="polite"` for info/success; `aria-live="assertive"` for warning/blocker/error; `role="status"`/`role="alert"` matching (mirror `ErrorState.tsx`). Documented announcement text.
  - [x] `staleStateExplanation` (AC5): a typed `{ whatChanged: ReactNode; whatToDoNext: ReactNode }` rendered as two distinct labeled segments ("What changed" / "What to do next") — never one concatenated line. Only meaningful for `variant='warning'`; ignored/asserted-absent otherwise.
  - [x] Children are TRUSTED composite-authored content by default (T-UNTRUSTED) — if a caller must render runner/agent text, it sanitizes via `@/lib/sanitization` BEFORE passing it in; document this on the prop.

- [x] **Task 3 — `<PersistentStateBadge>`** (AC: 1, 4, 6)
  - [x] NEW `src/components/feedback/primitives/PersistentStateBadge.tsx`: props `{ state: StateName; label?; title?; className?; testId? }`. `SemanticState` is a re-export alias of `StateName` from `@/lib/state-signifiers` (T-NO-PARALLEL-UNION). Renders icon (from `STATE_SIGNIFIERS[state]`) + label + the `--state-{…}` token color; never auto-dismisses; non-color-safe. `aria-live="polite"` + `role="status"`. `data-persistent-state-badge` stamp.
  - [x] Render the icon+label idiom by **mirroring** `StateSignifierChip` (do NOT import it from `features/workflows` — T-LAYERING). Consume only `@/lib/state-signifiers` + tokens.

- [x] **Task 4 — `feedbackToast` typed wrapper + barrel** (AC: 1, 9)
  - [x] NEW `src/components/feedback/primitives/feedbackToast.ts` (`.ts` — function map, T-REFRESH): `export const feedbackToast = { info, success, warning, error }`, each calling sonner's `toast.<level>(message, opts)` with design-system defaults. Document loud-and-clear in the file header: **ancillary lightweight confirmations ONLY — workflow-significant outcomes use `<InlineFeedback>`/`<PersistentStateBadge>` (UX-DR15); the `no-workflow-toast-success` rule enforces this.**
  - [x] AC9 positioning: configure/document the `<Toaster>` `position` (e.g. `top-right`/offset) so toasts avoid primary action controls. If the existing `Toaster` in `ui/sonner.tsx` needs a `position` prop to satisfy AC9, set it there (the single host); do NOT add a second Toaster.
  - [x] NEW `src/components/feedback/primitives/index.ts` barrel exporting all four primitives + `actionLifecycle` helper + types (`SemanticState`, `InlineFeedbackVariant`, etc.). MODIFY `src/components/feedback/index.ts`: add `export * from './primitives'`.

- [x] **Task 5 — `no-workflow-toast-success` ESLint rule (5 touchpoints)** (AC: 2)
  - [x] NEW `tools/eslint-rules/no-workflow-toast-success.js`: `@type {import('eslint').Rule.RuleModule}`. Track per-file: (a) does it import a workflow-mutation hook? — match import specifiers `useApproveSpec|useRejectSpec|useSubmitClarification|useWorkflowMutation` OR import paths matching `hooks/use*Spec`/`hooks/use*Clarification`; (b) flag `CallExpression`s on `toast.success|error|warning` or a bare `toast(...)` (identifier `toast` from `sonner`, or `feedbackToast.success|error|warning`). Report only when (a) AND (b). `messages` + `messageId`. Document the matcher + escape hatch in the rule header.
  - [x] NEW `tools/eslint-rules/__tests__/no-workflow-toast-success.test.js`: RuleTester (`node:test` + `tseslint.parser`, mirror `no-inline-query-keys.test.js`). Valid: ancillary `toast.info` in a mutation file; any `toast.success` in a non-mutation file; `feedbackToast.info` in a mutation file. Invalid: `toast.success`, bare `toast(...)`, `feedbackToast.success` in a mutation-hook-importing file.
  - [x] REGISTER: add to `tools/eslint-rules/index.js` `rules` map; add `'local-rules/no-workflow-toast-success': 'error'` to the `src/**` rules block in `eslint.config.js`; append the new test file to the `lint:rules-test` script in `package.json` (now 5 suites).

- [x] **Task 6 — Fixtures + PrimitivesPlayground section** (AC: 7)
  - [x] NEW `src/test/fixtures/feedback/feedbackFixtures.ts`: one fixture per UX-DR15 variant (inline informational, inline success-incorporated, warning-stale [with `staleStateExplanation`], blocker, error-failed-action, persistent decision outcome) + a lifecycle fixture (stage sequences incl. an off-chain terminal). Pure `.ts`.
  - [x] MODIFY `src/dev/PrimitivesPlayground.tsx`: add a "Feedback Primitives" section rendering each fixture (InlineFeedback variants, PersistentStateBadge states, ActionLifecycleIndicator chain, a `feedbackToast.info` demo button). The `<Toaster>` already mounted there hosts the toast demo.

- [x] **Task 7 — Tests** (AC: 3, 4, 5, 6, 7, 8, 10)
  - [x] `src/components/feedback/primitives/__tests__/InlineFeedback.test.tsx`: each variant renders signifier+label; correct `aria-live` per variant (polite info/success, assertive warning/blocker/error); `persistsUntil='dismiss'` renders + fires `onDismiss`, others render none; `staleStateExplanation` renders two distinct segments (AC5); renders in-region not document-level (AC4).
  - [x] `…/__tests__/PersistentStateBadge.test.tsx`: state→icon+label+token; never auto-dismisses; `aria-live="polite"`; non-color signifier present.
  - [x] `…/__tests__/ActionLifecycleIndicator.test.tsx`: advances through stages; `submitted`/`incorporated` distinct nodes (AC8); off-chain terminal marked distinctly; `resolveLifecyclePosition` unit branches.
  - [x] `…/__tests__/feedbackToast.test.ts`: variant map calls the right sonner level (spy on `sonner.toast`); position/config exposed (AC9).
  - [x] Router-free; no MSW needed (presentational). Mirror `EmptyState.test.tsx` patterns.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. These are **presentational primitives with no network calls**, so the logging surface is minimal and **field-only**. Mirror `ClarificationRegion.tsx`/`QueueShell.tsx`:
    - `feedback.toastSuppressedFallback` (`warn`) — IF `feedbackToast` is ever invoked with an unknown/empty variant, log `{ event, variant }` (variant key only, never the message text).
    - Do NOT log the feedback `message`/`children`/`title`/`staleStateExplanation` text — those may carry composite-supplied or (if a caller misuses it) runner-influenced content. Log **event + variant/state key + stage name** only.
  - [x] Use parameterized structured objects — never string concatenation. INFO for normal lifecycle, WARN for anomalies. If a primitive has genuinely no anomaly branch, it carries no log line — do NOT manufacture noise; the ESLint rule + render tests are the contract. Pin any log line added with a `console`-spy test + an exact-key-set negative assertion (no `message`/`children`).

### Review Findings

- [x] [Review][Patch] Retain generic lifecycle chains, reject duplicate stage names, and scope the AC8 received-versus-incorporated guarantee to the canonical default chain; update types/docs/tests accordingly. Decision: option 3 selected by Alex. [deliveryline-frontend/src/components/feedback/primitives/ActionLifecycleIndicator.tsx:32]
- [x] [Review][Patch] Always render the canonical `STATE_SIGNIFIERS` label even when `title` is supplied; current fixture titles replace the required non-color label. [deliveryline-frontend/src/components/feedback/primitives/InlineFeedback.tsx:116]
- [x] [Review][Patch] Derive `PersistentStateBadge` role/live urgency from semantic state so warning/blocker/error are assertive as required by AC6. [deliveryline-frontend/src/components/feedback/primitives/PersistentStateBadge.tsx:48]
- [x] [Review][Patch] Make the RTL inventory tests fixture-driven; the fixture arrays currently drive only the playground and are not imported by any test. [deliveryline-frontend/src/test/fixtures/feedback/feedbackFixtures.ts:33]
- [x] [Review][Patch] Test the actual `<Toaster>` position prop rather than an independent constant that can drift from the host configuration. [deliveryline-frontend/src/components/feedback/primitives/__tests__/feedbackToast.test.ts:54]
- [x] [Review][Patch] Close the exported `emitFeedbackToast('success', ...)` lint bypass by either keeping the dispatcher private or teaching the rule to flag forbidden literal variants. [deliveryline-frontend/src/components/feedback/primitives/feedbackToast.ts:45]
- [x] [Review][Patch] Require a functional `onDismiss` whenever `persistsUntil="dismiss"` so the rendered dismiss button cannot be a silent no-op. [deliveryline-frontend/src/components/feedback/primitives/InlineFeedback.tsx:67]
- [x] [Review][Patch] Preserve the badge's required textual signifier when a caller supplies an empty label override. [deliveryline-frontend/src/components/feedback/primitives/PersistentStateBadge.tsx:58]
- [x] [Review][Patch] Tighten ESLint import tracking to the shared feedback wrapper and ignore type-only mutation-hook imports, preventing unrelated `feedbackToast` symbols and type imports from activating the rule. [deliveryline-frontend/tools/eslint-rules/no-workflow-toast-success.js:73]

## Dev Notes

### THE CENTRAL RECONCILIATION (read this first)

This is the **third** epic-2b infrastructure story, and it inverts the usual dependency direction. Normally infra ships before its consumers; here the consumers (**2.18 ClarificationRegion**, **2.19 ApprovalDecisionBar**) shipped **first** (active-slice pull order) and built **region-local** versions of these exact primitives, each with an explicit forward-reference: *"2.21 may later extract/generalize what you build"* (2.18 T6/T7; 2.19 T-FEEDBACK). Consequences for how 2.21 must behave:

- **2.21 is additive extraction, not greenfield and not a retrofit.** Build the four shared primitives + the ESLint rule. Do NOT edit `ClarificationRegion.tsx`, `ApprovalDecisionBar.tsx`, or any mutation hook to consume them — those are `done` with large green test suites, and rewiring risks regressing two closed stories for no in-scope AC. The primitives are designed as **drop-in supersets** of the region-local versions; the **retrofit is a documented deferred follow-up** (OQ-1) for a later cleanup story (or 2.27's test-suite consolidation).
- **The ESLint rule is the one piece of genuine new enforcement that "reaches back."** It is safe precisely because neither 2.18 nor 2.19 calls `toast` (both feedback inline, per UX-DR15) — so turning the rule to `error` lights up **clean** today. Verify this with `npm run lint` after wiring (grep first: there should be zero production `toast(` call sites; only `PrimitivesPlayground.tsx` calls sonner, and it imports no mutation hook).
- **"Mutation hooks emit feedback in canonical sequence" (epic AC3)** is satisfied by *shipping the toolkit* (`<ActionLifecycleIndicator>` + `resolveLifecyclePosition`), not by editing the done hooks. The composites place feedback inline (epic AC4) — they already do, region-locally. 2.21 provides the generalized primitives they (or their successors) adopt.
- **Two backlog siblings gate the "audit" ACs:** 2.27 (snapshot harness) ⇒ AC7 uses fixtures + RTL, never `toMatchSnapshot`; 2.25 (axe/WCAG) ⇒ AC6 ships the ARIA-live + non-color baseline only; 2.26 (responsive) ⇒ AC9 ships position config + region-attachment, defers the breakpoint matrix.

### What is genuinely NEW vs generalized

- **Genuinely new:** the `no-workflow-toast-success` ESLint rule; the `feedbackToast` typed wrapper (first production use of sonner); the shared `src/components/feedback/primitives/` module + barrel export; the PrimitivesPlayground feedback section.
- **Generalized from region-local prior art:** `<ActionLifecycleIndicator>` + `resolveLifecyclePosition` (from 2.18 `clarificationView.ts`); `<InlineFeedback>` (from 2.18's inline "answer submitted" + 2.19's inline outcome); `<PersistentStateBadge>` (from 2.19's post-submit summary persistence + non-color chips); `staleStateExplanation` shape (from 2.19's stale-decision text).

### Layering: feedback primitives stay generic

- `src/components/feedback/` is **generic infrastructure** (per `architecture.md:1182`). The new primitives must NOT import from `src/features/workflows/**` (no `StateSignifierChip`, no `WorkflowStateBadge`, no `clarificationView`). They consume the **generic** contracts only: `@/lib/state-signifiers` (`STATE_SIGNIFIERS`, `StateName`), `@/lib/utils` (`cn`), `@/lib/density`, `@/components/ui/*` primitives, and `sonner`. (Note: the existing `ErrorState` does call `useReturnToRunContext` from navigation — that is a pre-existing wrinkle; the new primitives stay strictly presentational and take no navigation/query dependency. T-LAYERING.)
- `SemanticState` ≡ `StateName` (re-export alias). Do NOT mint a second 5- or 12-value union — one source of truth (T-NO-PARALLEL-UNION).

### Frontend conventions (architecture)

- Stack: React + TypeScript, Vite, TanStack Query (server state) + TanStack Router (typed routes), shadcn/ui + Tailwind, Vitest + RTL + MSW. [Source: architecture.md#445–520]
- Presentational primitives hold **no server state** and take no query/router dependency; they receive trusted props from composites. [Source: architecture.md#516]
- Component files PascalCase `.tsx`; pure helpers/maps in sibling `.ts` (`react-refresh/only-export-components`, `allowConstantExport: true` ⇒ only literal `const`/`type` exports may sit beside a component) [memory: `frontend-react-refresh-no-fn-exports`].
- Never color alone — every state carries an icon + label from `STATE_SIGNIFIERS` (story 2.3 AC5). [Source: ux-design-specification.md#story-2.3-AC5]

### Traps (do NOT step on these)

- **T-NO-RETROFIT — do not edit done stories.** No changes to `ClarificationRegion.tsx`, `ApprovalDecisionBar.tsx`, `ClarificationRegionContainer.tsx`, `ApprovalDecisionBarContainer.tsx`, or any mutation hook. Retrofit is deferred (OQ-1). If you touch them, you are out of scope and risk regressing `done` stories.
- **T-LAYERING — feedback primitives import only generic libs.** No `import … from '@/features/workflows/…'` inside `src/components/feedback/`. Mirror `StateSignifierChip`'s rendering using `@/lib/state-signifiers`; do not import the chip itself.
- **T-NO-PARALLEL-UNION — reuse `StateName`.** `SemanticState` is an alias of the existing 12-state union in `@/lib/state-signifiers`. Inventing a new union forks the signifier contract.
- **T-TOAST-IS-ANCILLARY — toast never carries workflow truth.** `feedbackToast` is lightweight-confirmation only; the `no-workflow-toast-success` rule enforces it. Workflow-significant outcomes use `<InlineFeedback>`/`<PersistentStateBadge>` (UX-DR15).
- **T-RECEIVED-NE-INCORPORATED — never collapse the two.** `<ActionLifecycleIndicator>` renders `submitted` and `incorporated` as distinct nodes; no combined "received+incorporated" string. (Epic AC8 + UX-DR15.)
- **T-NO-SECOND-TOASTER — one `<Toaster>` host.** It is already mounted in `AppShell.tsx`. The wrapper calls `toast()`; do NOT mount another `<Toaster>` (the playground's own mount is dev-only and separate).
- **T-NO-NEW-DEP — sonner is already installed.** No npm install ⇒ no lockfile change ⇒ no WSL2/Linux smoke [memory: `frontend-lockfile-cross-platform`, `wsl-linux-ci-reproduction`].
- **T-REFRESH — pure helpers/maps in `.ts`.** `actionLifecycle.ts`, `feedbackToast.ts`, `feedbackFixtures.ts` are `.ts`; only the components are `.tsx` [memory: `frontend-react-refresh-no-fn-exports`].
- **T-UNTRUSTED — primitives take trusted children.** Default children are composite-authored. If a caller passes runner/agent text it must sanitize via `@/lib/sanitization` first; document on the prop. Do not render raw HTML (`no-unsanitized-html`).
- **T-ESLINT-5-TOUCHPOINTS — a new rule has five edits.** Rule file + test file + `index.js` register + `eslint.config.js` rules entry + `package.json` `lint:rules-test` script. Missing any one ⇒ the rule silently doesn't run or `lint:rules-test` doesn't cover it.
- **T-GATES — run gates via PowerShell, not Bash.** [memory: `rtk-hook-only-matches-bash`] Use native file tools + PowerShell for `tsc`/`eslint`/`vitest`/`prettier`. Run `prettier --write` before finishing or the format gate cascades and skips downstream jobs [memory: `prettier-gate-cascades-ci`].

### Open Questions (recommendations in brackets — proceed with the recommendation unless told otherwise)

- **OQ-1 — retrofit 2.18/2.19 to consume the new primitives now, or defer?** **[Recommend: DEFER.]** 2.18/2.19 are `done` with large green suites; rewiring them to the shared primitives is a behavior-preserving refactor with real regression surface and **no in-scope AC requiring it** (epic AC4 says the infra *provides* primitives, composites *place* them — both already place region-local ones). Ship the supersets + a `[Defer]` note pointing a later cleanup (or 2.27 consolidation) at the swap. Keep this story additive.
- **OQ-2 — does `feedbackToast` need a custom-rendered toast (tsx) or just typed sonner calls (ts)?** **[Recommend: typed `.ts` map over `sonner.toast.<level>`]** with design-system options (duration, className) — no custom JSX render needed for ancillary confirmations. If a richer toast body is ever required, sonner accepts a render fn; defer until a real need exists.
- **OQ-3 — `persistsUntil='workflowChange'` semantics.** **[Recommend: documented prop only.]** The primitive cannot observe workflow state (it is presentational + query-free, T-LAYERING). `'workflowChange'` means "render no dismiss control; the consumer unmounts/replaces me when the workflow advances" — the same contract 2.19's summary uses (persist until next state change). Document it; do not wire a query.
- **OQ-4 — ESLint mutation-hook matcher breadth.** **[Recommend: name-or-path match]** on `useApproveSpec|useRejectSpec|useSubmitClarification|useWorkflowMutation` import specifiers plus import paths matching `hooks/use*Spec`/`hooks/use*Clarification`. Conservative (false-negatives over false-positives) — the rule should never block a legitimate ancillary toast in a non-mutation file. Broaden later if a new mutation family appears.
- **OQ-5 — Toaster `position` for AC9.** **[Recommend: set `position` on the single `ui/sonner.tsx` Toaster]** (e.g. `top-right` with a small offset) so toasts never overlay the sticky-footer ApprovalDecisionBar (2.19) at the bottom of the main pane. Document; full breakpoint validation is 2.26.

### Logging Requirements (project-wide standard, frontend-adapted)

Presentational primitives with no network calls ⇒ minimal logging surface. Field-only structured `console.warn({ event, …keys })` for the single anomaly branch (`feedback.toastSuppressedFallback` on an unknown `feedbackToast` variant); **never** log the feedback `message`/`children`/`title`/`staleStateExplanation`/stage label text (composite- or potentially runner-supplied). Pin any added line with a `console`-spy test + exact-key-set negative assertion. Do not manufacture log noise on branches that have no anomaly — the ESLint rule + render tests are the real contract here.

### Project Structure Notes

New + modified files:

```
deliveryline-frontend/
├── src/components/feedback/
│   ├── index.ts                                  (MODIFIED — add `export * from './primitives'`)
│   └── primitives/
│       ├── index.ts                              (NEW — barrel)
│       ├── actionLifecycle.ts                    (NEW — resolveLifecyclePosition + DEFAULT_LIFECYCLE_STAGES)
│       ├── ActionLifecycleIndicator.tsx          (NEW)
│       ├── InlineFeedback.tsx                    (NEW)
│       ├── PersistentStateBadge.tsx              (NEW)
│       ├── feedbackToast.ts                      (NEW — typed sonner wrapper)
│       └── __tests__/
│           ├── ActionLifecycleIndicator.test.tsx (NEW)
│           ├── InlineFeedback.test.tsx           (NEW)
│           ├── PersistentStateBadge.test.tsx     (NEW)
│           └── feedbackToast.test.ts             (NEW)
├── src/test/fixtures/feedback/feedbackFixtures.ts (NEW)
├── src/dev/PrimitivesPlayground.tsx               (MODIFIED — add Feedback Primitives section)
├── src/components/ui/sonner.tsx                   (MODIFIED if AC9 needs a `position` — single Toaster host)
├── tools/eslint-rules/
│   ├── no-workflow-toast-success.js              (NEW — rule)
│   ├── index.js                                  (MODIFIED — register rule)
│   └── __tests__/no-workflow-toast-success.test.js (NEW — RuleTester)
├── eslint.config.js                              (MODIFIED — add rule at `error` in src/** block)
└── package.json                                  (MODIFIED — append test file to `lint:rules-test`)
```

No new npm dependency ⇒ **no lockfile change ⇒ no WSL2/Linux-CI smoke needed** [memory: `wsl-linux-ci-reproduction`, `frontend-lockfile-cross-platform`].

### Testing standards

- **Fixture-driven RTL — never `toMatchSnapshot`** (2.27's snapshot harness does not exist). Assert on roles/text/`data-*` attributes + `aria-live` values.
- Presentational primitives ⇒ **router-free, query-free, no MSW** for the component tests. Mirror `EmptyState.test.tsx` / `LoadingState.test.tsx`.
- ESLint rule ⇒ `RuleTester` + `node:test` (mirror `no-inline-query-keys.test.js`); run via `npm run lint:rules-test` (now **5** suites).
- Console-spy assertion for any log line + an exact-key-set negative test (no `message`/`children`).

### Gate verification (run via PowerShell — `rtk-hook-only-matches-bash`)

```
tsc -b                                    # 0 errors
eslint . --max-warnings=0                 # 0 (incl. the NEW no-workflow-toast-success at error; verify zero existing violations)
npm run lint:rules-test                   # 5/5 custom-rule suites (was 4)
vitest run                                # full suite green (+ new feedback-primitive tests)
prettier --write "src/**/*.{ts,tsx}"      # before finishing (prettier-gate-cascades-ci)
```

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.21 (lines 1317–1334)] — the 10 epic ACs (typed primitives, ESLint enforcement, canonical lifecycle, inline placement, stale explanation, a11y, variant fixtures, received≠incorporated, mobile, test coverage).
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Feedback Patterns (1784–1823)] — UX-DR15: inline/persistent/toast boundaries, "do not collapse 'answer received' and 'answer incorporated'", visually-stronger blocker/stale/failure, ARIA live, non-color, mobile attachment, the 6-variant inventory.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Clarification Region states (1489–1497)] — lifecycle vocabulary (`submitted → accepted → incorporated`).
- [Source: _bmad-output/planning-artifacts/architecture.md#1182, #516] — feedback states live under `components/feedback`; presentational primitives hold no server state.
- [Source: deliveryline-frontend/src/lib/state-signifiers.ts] — `STATE_SIGNIFIERS`, `StateName` (the non-color signifier contract `<PersistentStateBadge>`/`<InlineFeedback>` consume).
- [Source: deliveryline-frontend/src/styles/globals.css (≈87–177)] — `--state-*` semantic tokens + prominence tiers.
- [Source: deliveryline-frontend/src/components/feedback/{index.ts,states/}] — the sibling primitives + barrel to extend.
- [Source: deliveryline-frontend/src/components/ui/sonner.tsx + src/features/workflows/AppShell.tsx] — the single `<Toaster>` host (already mounted) the wrapper calls into.
- [Source: deliveryline-frontend/src/features/workflows/components/WorkflowStateBadge.tsx:26–66] — `StateSignifierChip` icon+label idiom to MIRROR (not import) in the primitives.
- [Source: deliveryline-frontend/tools/eslint-rules/{index.js,no-inline-query-keys.js,__tests__/}] + eslint.config.js + package.json `lint:rules-test` — the 5-touchpoint pattern for the new rule.
- [Source: deliveryline-frontend/src/dev/PrimitivesPlayground.tsx] — playground to extend (AC7 fixtures).
- [Source: 2-18-clarification-region-with-visible-incorporation-lifecycle-wiring.md] — region-local lifecycle indicator + inline feedback to GENERALIZE; the "2.21 may later extract" handoff (T6/T7); `resolveLifecyclePosition` in `clarificationView.ts`.
- [Source: 2-19-approval-decision-bar-generalized-composite-spec-approval-mode.md] — region-local post-submit summary + stale-decision text + `StateSignifierChip` reuse; the T-FEEDBACK handoff; the `staleStateExplanation` shape (2.19 AC6).
- [Source: 2-20-queue-shell-states-loading-empty-filtered-empty-error.md] — the ARIA-live `sr-only` idiom + the fixture-driven-RTL / playground discipline this story mirrors.
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml:170–179] — 2.18/2.19 `done`; 2.21 `backlog`; 2.23/2.25/2.26/2.27 `backlog`; `epic-2b: deferred`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story workflow.

### Debug Log References

- `tsc -b` initially failed (TS2375) on the PrimitivesPlayground passing possibly-`undefined` fixture props (`staleStateExplanation`/`label`/`stages`) under `exactOptionalPropertyTypes: true`. Fixed by declaring those optional props as `?: T | undefined` in the three component interfaces (the `StateSignifierChipProps` precedent) — no playground change needed.

### Completion Notes List

**Scope delivered (additive extraction, NOT a retrofit — OQ-1 deferred):** the four shared feedback primitives + the net-new ESLint rule + fixtures/playground/tests. No edits to `ClarificationRegion.tsx`, `ApprovalDecisionBar.tsx`, or any mutation hook (T-NO-RETROFIT honored).

- **Task 1 — lifecycle.** `actionLifecycle.ts`: generic `resolveLifecyclePosition(stages, currentStage)` (in-chain → index; off-chain terminal `failed`/`superseded` → `currentIndex=-1`,`isOffChain=true`; empty `''` → not-started, not off-chain) + `DEFAULT_LIFECYCLE_STAGES` + `lifecycleStageState`. `ActionLifecycleIndicator.tsx` renders each stage as a distinct `<li data-stage data-stage-state>`; `submitted`/`incorporated` are always separate nodes (AC8 / T-RECEIVED-NE-INCORPORATED); off-chain terminal appended as a distinctly-marked `data-stage-state="offchain"` node. Non-color step icons (Check/CircleDot/Circle/OctagonX) + text label.
- **Task 2 — InlineFeedback.** 5 variants; ARIA `polite` (info/success) / `assertive` (warning/blocker/error) with matching `role`; dominant tier stamped `data-prominence="dominant"` + heavier border/weight (UX-DR15 "visually stronger"). `persistsUntil` is documented dismissal semantics (`dismiss` renders a wired dismiss control; `workflowChange`/`infinite` render none — consumer-unmounts, OQ-3). `staleStateExplanation` (AC5) renders two distinct labeled `data-stale-segment` blocks (What changed / What to do next), only for `variant="warning"`. Children TRUSTED by default (T-UNTRUSTED documented on the prop).
- **Task 3 — PersistentStateBadge.** Mirrors `StateSignifierChip`'s icon+label idiom using ONLY `@/lib/state-signifiers` (T-LAYERING — no `features/workflows` import); `role="status"`+`aria-live="polite"`; `SemanticState` is a re-export alias of `StateName` (T-NO-PARALLEL-UNION).
- **Task 4 — feedbackToast.** Typed `.ts` map over `sonner.toast.<level>` with design-system defaults (OQ-2). `position="top-right"` set on the single `ui/sonner.tsx` Toaster (T-NO-SECOND-TOASTER) + exported `FEEDBACK_TOAST_POSITION` for AC9. Barrels: `primitives/index.ts` (new) + `feedback/index.ts` (`export * from './primitives'`). NEW helper `feedbackStatePresentation.ts` (`.ts`, T-REFRESH) carries the generic icon-component + `--state-*` token-class maps both components share — mirrors `workflowStateMapping` without the layering inversion.
- **Task 5 — ESLint rule (5 touchpoints).** `no-workflow-toast-success.js`: a file is a "mutation file" if it imports a mutation hook by name (`useApproveSpec`/`useRejectSpec`/`useSubmitClarification`/`useWorkflowMutation`) or from a path matching `hooks/use*Spec`/`hooks/use*Clarification` (OQ-4); inside one, `toast.success/error/warning`, bare `toast(...)`, or `feedbackToast.success/error/warning` are errors; `toast.info/message/loading` + `feedbackToast.info` stay allowed. Reporting deferred to `Program:exit` so import-vs-call order is irrelevant. Registered in `index.js` + `eslint.config.js` (src/** `error`) + appended to `lint:rules-test` (now 5 suites). Verified clean against the existing tree: only `PrimitivesPlayground` calls sonner and it imports no mutation hook, so the rule lights up clean (`eslint --max-warnings=0` = 0).
- **Task 6 — fixtures + playground.** `feedbackFixtures.ts` (6 inline + 3 persistent + 4 lifecycle incl. off-chain) drives both a new "Feedback Primitives" PrimitivesPlayground section and the RTL render tests.
- **Task 7 / Logging.** 4 test files, 32 new tests. Single anomaly log line `feedback.toastSuppressedFallback` (warn) on an unknown `feedbackToast` variant, pinned by a console-spy test with an exact-key-set assertion (`['event','variant']` only — never the `message` text).

**Review gates (run via PowerShell — `rtk-hook-only-matches-bash`):** `npm run build` clean · `eslint . --max-warnings=0` clean · all 5 custom-rule suites pass (run sequentially after Windows blocked `node --test` worker spawning with `EPERM`) · full `vitest run` 540/540 (59 files) · `prettier --check` clean. No new npm dependency ⇒ no lockfile change ⇒ no WSL2/Linux smoke (T-NO-NEW-DEP).

### File List

**New:**
- `deliveryline-frontend/src/components/feedback/primitives/feedbackStatePresentation.ts`
- `deliveryline-frontend/src/components/feedback/primitives/actionLifecycle.ts`
- `deliveryline-frontend/src/components/feedback/primitives/ActionLifecycleIndicator.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/InlineFeedback.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/PersistentStateBadge.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/feedbackToast.ts`
- `deliveryline-frontend/src/components/feedback/primitives/index.ts`
- `deliveryline-frontend/src/components/feedback/primitives/__tests__/ActionLifecycleIndicator.test.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/__tests__/InlineFeedback.test.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/__tests__/PersistentStateBadge.test.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/__tests__/feedbackToast.test.ts`
- `deliveryline-frontend/src/components/ui/sonnerConfig.ts`
- `deliveryline-frontend/src/components/ui/__tests__/sonner.test.tsx`
- `deliveryline-frontend/src/test/fixtures/feedback/feedbackFixtures.ts`
- `deliveryline-frontend/tools/eslint-rules/no-workflow-toast-success.js`
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-workflow-toast-success.test.js`

**Modified:**
- `deliveryline-frontend/src/components/feedback/index.ts` (add `export * from './primitives'`)
- `deliveryline-frontend/src/components/ui/sonner.tsx` (add `position="top-right"` — AC9)
- `deliveryline-frontend/src/dev/PrimitivesPlayground.tsx` (add Feedback Primitives section)
- `deliveryline-frontend/tools/eslint-rules/index.js` (register the rule)
- `deliveryline-frontend/eslint.config.js` (rule at `error` in the src/** block)
- `deliveryline-frontend/package.json` (append test file to `lint:rules-test`)

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2026-06-08 | 1.1 | Code review closed: 1 decision resolved (generic unique lifecycle chains; canonical AC8 guarantee) and 9 patches applied across accessibility, signifier labels, dismiss typing, fixture-driven tests, Toaster config verification, and ESLint enforcement. Full frontend gates green: 540 tests, lint, build, formatting, and 5 custom-rule suites. Status to done. | Codex (code-review) |
| 2026-06-08 | 0.1 | Created story 2.21 — Feedback Patterns Infrastructure (shared `<InlineFeedback>`/`<PersistentStateBadge>`/`<ActionLifecycleIndicator>`/`feedbackToast` primitives + `no-workflow-toast-success` ESLint rule + fixtures/playground/tests). Resolved the ordering-inversion: additive extraction of 2.18/2.19's region-local feedback, NOT a retrofit (OQ-1 deferred). Ready-for-dev. | Alex (create-story) |
| 2026-06-08 | 1.0 | Implemented all 7 tasks + logging (bmad-dev-story). Shipped the four shared feedback primitives + `feedbackStatePresentation.ts` helper, the additive `no-workflow-toast-success` ESLint rule (5 touchpoints), fixtures, PrimitivesPlayground section, and 4 test files (+32 tests). Purely additive — no edit to 2.18/2.19 or any mutation hook (retrofit deferred, OQ-1). Toaster positioned `top-right` (AC9). Gates green via PowerShell: tsc 0, eslint --max-warnings=0 0, lint:rules-test 5/5, vitest 521/521 (58 files), prettier clean. No new dep. Status → review. | Amelia (dev-story) |
