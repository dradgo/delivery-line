# Story 2.23: Modal / Overlay / Confirmation Patterns + Button Hierarchy Infrastructure

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer needing to confirm a high-consequence action (reject with reason, approve when stale/conflict, stop orchestrator, retry) or surface bounded secondary detail without losing run context,
I want shared modal/overlay/confirmation pattern primitives plus button-hierarchy infrastructure enforcing one visually primary action per decision area,
so that overlays are reserved for genuinely consequential interactions (UX-DR18) and action hierarchy reflects governed-workflow seriousness (UX-DR19).

## ⚠️ Read first — scope, the ordering inversion, and what this story is NOT

**Scope in one line:** ship the **shared overlay primitives** under `src/components/overlays/` (`<ConfirmationDialog>`, `<RationaleCaptureDialog>`, `<BoundedDetailSheet>`, `<NonDismissibleCriticalWarning>`), the **governed button-hierarchy primitives** under a NEW `src/components/actions/` module (`<GovernedButton>`, `<ButtonGroup>`, `<DecisionArea>`), a documented `src/lib/overlays/confirmationCatalog.ts`, plus **two** NEW custom ESLint rules (`local-rules/single-primary-action`, `local-rules/no-confirmation-for-navigation`), fixtures, a PrimitivesPlayground section, and fixture-driven RTL tests. **Frontend-only. Additive. No new npm dependency.**

**🔁 THE ORDERING INVERSION (read this before anything else):** This is the **fourth** epic-2b infrastructure story (after 2.20 states, 2.21 feedback, 2.22 navigation), and like 2.21 it inverts the usual dependency direction. Its primary *governed-action* consumer — **2.19 Approval/Decision Bar** (`reject with reason` confirmation + single-primary-action enforcement) — already shipped **`done`** ahead of it, with its own **region-local** button hierarchy and a `consequence`-bearing reject flow. So this story is **additive infrastructure + extraction**, NOT a retrofit, and the over-riding constraint is: **it must be purely additive and must NOT regress 2.18 or 2.19.**

**This story does NOT:**
- **NOT** rip out or rewire 2.19's `ApprovalDecisionBar.tsx` / `ApprovalDecisionBarContainer.tsx` region-local single-primary logic, reject-with-reason flow, `workflowState` handling, or button rendering. Those are `done`, heavily tested, and retrofitting them risks regressing a closed story. The new primitives are **drop-in supersets**; the retrofit is an explicit **deferred follow-up** (OQ-1). The two new ESLint rules are net-new and additive — there are **zero** `<ConfirmationDialog>` / `<GovernedButton priority="primary">` call sites today (the components are new), so both rules light up **clean**.
- **NOT** wire the overlays into any mutation hook (`useApproveSpec`/`useRejectSpec`/etc.) or open them from any production composite. AC4's "these actions ALWAYS confirm" is satisfied by **shipping the `confirmationCatalog.ts` documentation + the primitives** the composites *can* drive — not by editing the (done) hooks/composites.
- **NOT** the full WCAG AA / axe audit — that's **2.25** (`backlog`). Ship the focus-move / focus-restoration / `aria-labelledby` + `aria-describedby` / Escape-predictability baseline (AC5) and focused render tests; defer the audit.
- **NOT** the full responsive/mobile breakpoint pass — that's **2.26** (`backlog`). Ship the `<BoundedDetailSheet>` full-height-sheet variant + the "primary never collapses" structure (AC6, AC11); defer the breakpoint-matrix testing.
- **NOT** build a visual-regression *snapshot* harness — story **2.27** owns that and is `backlog`. AC12's coverage is satisfied by **PrimitivesPlayground fixtures + fixture-driven RTL render tests** (the exact 2.20/2.21 discipline: assert roles/text/`data-*`, never `toMatchSnapshot`).
- **NOT** stop-orchestrator (Epic 3) or retry/recover (Epic 4) wiring. Those are future consumers the `confirmationCatalog` documents; they adopt these primitives when their stories land.
- **NOT** add an npm dependency. `@radix-ui/react-dialog` (used by `dialog.tsx` + `sheet.tsx`), `@radix-ui/react-slot`, `class-variance-authority`, and `lucide-react` are **already installed**. No new dep ⇒ no lockfile change ⇒ no WSL2/Linux-CI smoke needed [memory: `frontend-lockfile-cross-platform`, `wsl-linux-ci-reproduction`].

If you find yourself editing `ApprovalDecisionBar.tsx`, `ApprovalDecisionBarContainer.tsx`, `ClarificationRegion.tsx`, the shadcn `ui/button.tsx`, or any mutation hook to *consume* the new primitives — **stop**. That retrofit is deferred (OQ-1). This story ends at the overlay primitives + the governed button hierarchy + the catalog + the two ESLint rules + fixtures + playground + tests.

## 🔑 Reference decoder — "UX-DR18 / UX-DR19" are not literal strings in the UX spec

The epic AC text cites `UX-DR18` and `UX-DR19`. There is **no literal `UX-DR18` token** in `ux-design-specification.md` (a `grep` returns nothing — the same indirection 2.18/2.20/2.21 documented). Map them before you read:

- **"UX-DR18 / Modal-Overlay-Confirmation Patterns"** → UX spec section **"Modal, Overlay, and Confirmation Patterns"** (lines **1914–1950**). Hard rules this story encodes:
  - Overlays reserved for **bounded, high-consequence, or interruptive** actions; prefer inline/panel over modal interruption; **avoid stacking** overlays.
  - **Confirm before:** reject with reason · approve when stale/conflict risk · stop orchestrator processing · retry/recover when consequential.
  - **Do NOT** require modal confirmation for low-risk navigation or simple compare entry.
  - Closing an overlay returns the user to the **same review context without reset**.
  - Overlays must **state the consequence clearly**.
  - A11y: focus moves **into** the overlay and **returns to the triggering element** on close; titles + consequences explicit for screen readers; Escape/keyboard dismissal **predictable except where unsafe**.
  - Mobile: full-height sheet pattern where dialogs get cramped; destructive actions clearly separated in touch layouts.
  - The 4 named variants: **confirmation dialog · rationale capture dialog · bounded detail sheet · non-dismissible critical warning overlay**.
- **"UX-DR19 / Button Hierarchy"** → UX spec section **"Button Hierarchy"** (lines **1952–1990**). Hard rules:
  - **One primary action per decision area**; secondary subordinate; destructive clearly differentiated; compare/inspect/navigate must not compete with approve/reject.
  - Primary = the **next intended governed step**; if **no safe primary action exists**, show a **blocked state** rather than promoting an unavailable action.
  - Buttons reflect **workflow truth**: `ready · blocked · stale · submitting · completed`.
  - **Post-decision state remains visible** after the action completes.
  - Disabled buttons carry **adjacent explanation** where the reason is not obvious; labels use **explicit verbs**.
  - Mobile: primary stays reachable without hunting; secondary/tertiary may collapse into menus, never the primary.
  - The 5 named variants: **primary governed · secondary review · tertiary inspect/compare · destructive · blocked/disabled**.
- **"story 2.31"** (cited by epic AC3/AC7 as the ESLint-rule home) → the frontend custom-rule plugin at `tools/eslint-rules/` (5 rules today). New rules extend that plugin via the **5-touchpoint pattern** (see T-ESLINT-5-TOUCHPOINTS).
- **"story 2.3 AC4/AC5 + 2.4 AC6"** → `--state-*` semantic color tokens + the focus-ring token; the **state-blocker token** (`--state-blocker*`, `globals.css` ≈115–120) backs `priority="blocked"`. Non-color signifier contract = `src/lib/state-signifiers.ts`.
- **Architecture invariants** → `architecture.md:516` (TanStack Query owns server state; these primitives are presentational, hold no server state) + `architecture.md:1182` (generic UI/feedback infra lives under `src/components/**`, not `src/features/workflows/**`).

## 🧩 What already exists (compose / extend — do NOT rebuild, do NOT pollute)

| Capability | Location | Use in this story |
|---|---|---|
| shadcn `Dialog` (radix) — `Dialog`, `DialogContent`, `DialogHeader`, `DialogFooter`, `DialogTitle`, `DialogDescription`, `DialogClose`, `DialogTrigger` | `src/components/ui/dialog.tsx` (`@radix-ui/react-dialog`) | **The** base for `<ConfirmationDialog>` / `<RationaleCaptureDialog>` / `<NonDismissibleCriticalWarning>`. Radix already handles focus-move-in + focus-restore-on-close (AC5). Do NOT fork it. |
| shadcn `Sheet` (radix) — `Sheet`, `SheetContent` (cva `side: top/bottom/left/right`), header/footer/title/description | `src/components/ui/sheet.tsx` (`@radix-ui/react-dialog`) | Base for `<BoundedDetailSheet>`. `side="bottom"` is the full-height mobile sheet pattern (AC6). |
| shadcn `Button` + `buttonVariants` (cva: `variant` default/destructive/outline/secondary/ghost/link; `size`) | `src/components/ui/button.tsx` (`@radix-ui/react-slot`, `cva`) | `<GovernedButton>` **wraps/composes** this (maps `priority`→`variant`). Do NOT add `priority`/`workflowState` to this file — it must stay a generic shadcn primitive (T-UI-PURE). |
| `STATE_SIGNIFIERS` / `StateName` (12-state union) + `STATE_NAMES` | `src/lib/state-signifiers.ts` | The non-color signifier contract. `intent` (overlays) and `priority="blocked"` / `workflowState` (button) map to these icons+labels — never color alone (AC5/AC9 a11y). |
| `--state-*` semantic tokens incl. `--state-blocker*` (DOMINANT), `--state-warning*`, `--state-error*`, `--state-stale*`, `--state-success*` + `-foreground`/`-border`/`-hc` | `src/styles/globals.css` (≈87–177) | `intent` colors + `priority="blocked"` blocked-visual + `workflowState` treatments. `blocker`/`warning`/`error` are the DOMINANT tier. |
| Focus-ring token (story 2.4 AC6) + `focus-visible:ring-*` | `src/styles/globals.css`; consumed by `buttonVariants` | Keep focus visible/consistent (AC accessibility). |
| Feedback primitives `<InlineFeedback>` / `<PersistentStateBadge>` / `<ActionLifecycleIndicator>` + `feedbackToast` | `src/components/feedback/primitives/` (story 2.21) | Compose, don't duplicate. `<RationaleCaptureDialog>` field-validation errors and `<GovernedButton workflowState="completed">` outcome surfacing should reuse these where natural (e.g. inline validation message). The "post-decision visible" rule (AC10) is a sibling of 2.21's "persist until reset". |
| `StateSignifierChip` (icon+label, color-safe) | `src/features/workflows/components/WorkflowStateBadge.tsx:26–66` | **Reference** idiom only. Do NOT import it into `components/overlays` or `components/actions` (layering inversion — T-LAYERING). Mirror its rendering using `STATE_SIGNIFIERS` directly. |
| `cn` / `densityGap` | `src/lib/utils.ts`, `src/lib/density.ts` | className merge + spacing. |
| ESLint local-rules plugin (5 rules today) + RuleTester harness (`node:test` + `tseslint.parser`) | `tools/eslint-rules/index.js`, `eslint.config.js` (rules blocks), `package.json` `lint:rules-test` (5 suites), `tools/eslint-rules/__tests__/*.test.js` | The exact 5-touchpoint pattern to add the **two** new rules. JSX-AST rule reference: `no-untyped-loading-state.js` (walks `JSXOpeningElement`, reads attributes, `localName` member-expr helper). Import-tracking + `Program:exit` reference: `no-workflow-toast-success.js`. |
| `<LoadingState>` typed-variant precedent + its ESLint rule | `src/components/feedback/states/`, `no-untyped-loading-state.js` | The pattern for a **TS-required prop enforced by both types AND lint** — mirror for `consequence` required-ness + `single-primary-action`. |
| PrimitivesPlayground (dev-only, lazy via `?playground`; mounts `<Toaster>`, imports `ui/*` + `feedback/*`) | `src/dev/PrimitivesPlayground.tsx` | Add an "Overlays + Button Hierarchy" section (AC12 fixtures). |
| Sanitization barrel `@/lib/sanitization` (`SafeMarkdownRenderer`, story 2.24) | `src/lib/sanitization` | Only if an overlay ever renders caller-supplied **untrusted** text. Default: overlays take **trusted** ReactNode/string authored by the composite. |

**Confirmed by survey — these do NOT exist yet:** `src/components/overlays/` (no files), `src/components/actions/` (no files), `src/lib/overlays/confirmationCatalog.ts`, `<ConfirmationDialog>`, `<RationaleCaptureDialog>`, `<BoundedDetailSheet>`, `<NonDismissibleCriticalWarning>`, `<GovernedButton>`, `<ButtonGroup>`, `<DecisionArea>`, the `single-primary-action` + `no-confirmation-for-navigation` ESLint rules. The shadcn `ui/button.tsx` has **no** `priority`/`workflowState` prop; the only `workflowState` usages today are the 2.19 **domain** view-model in `src/features/workflows/` (NOT to be reused — T-LAYERING).

## Acceptance Criteria

> Story-local ACs refine epic-2.23 ACs 1–12 with concrete file paths + the ordering-inversion reconciliation. Epic AC number in parentheses. All work is **additive** — no edits to `done` composites/hooks or to the generic shadcn `ui/button.tsx`.

1. **(AC1) Typed overlay primitives exist.** NEW `src/components/overlays/` exports four typed components, each wrapping the existing shadcn `Dialog`/`Sheet` (radix) primitive — never a new portal/host:
   - `<ConfirmationDialog title intent="danger|warning|info" consequence … >` — confirm/cancel dialog.
   - `<RationaleCaptureDialog title fields={Field[]} … >` — composes `<ConfirmationDialog>` + structured field inputs (rejection-feedback shape).
   - `<BoundedDetailSheet title … >` — bounded secondary-detail sheet (wraps shadcn `Sheet`).
   - `<NonDismissibleCriticalWarning title body acknowledgmentLabel … >` — Escape/outside-click-blocking acknowledgment overlay.
   All exported via a `overlays/index.ts` barrel. Component files PascalCase `.tsx`; pure helpers/maps/types live in sibling `.ts` (T-REFRESH).

2. **(AC2) `consequence` is a TypeScript-required prop.** `<ConfirmationDialog>`'s `consequence: string` is **mandatory in the type** (not optional) — omitting it is a `tsc` error. The rendered consequence is the dialog's `aria-describedby` target (AC5). `<RationaleCaptureDialog>` composes `<ConfirmationDialog>` and so inherits the required `consequence`. A type-level test (or a `// @ts-expect-error` fixture) proves omission fails to compile.

3. **(AC3) ESLint rule flags confirmation-for-low-risk-navigation.** NEW `tools/eslint-rules/no-confirmation-for-navigation.js` flags a `<ConfirmationDialog>` whose confirm action is **only** a navigation or compare-entry effect — conservatively: a `<ConfirmationDialog>` whose `onConfirm` (or equivalent confirm-handler prop) is an inline arrow/function whose body contains **only** a navigation call (`navigate(...)`, `router.navigate(...)`, `.navigate(`) or a documented compare-entry/view-only call, with no other side effect. Conservative (false-negatives over false-positives), documented escape hatch (`// eslint-disable-next-line local-rules/no-confirmation-for-navigation -- <rationale>`). Ships with a RuleTester suite (valid: confirm handler performing a mutation; any `<ConfirmationDialog>` in a non-navigation context; invalid: confirm handler that only navigates / only enters compare). Registered via the 5-touchpoint pattern. **NOTE (T-AC3-PRAGMATIC):** static handler-intent detection is inherently best-effort — the authoritative source of "which actions confirm" is the `confirmationCatalog.ts` (AC4); the lint rule is a guard-rail for the obvious-abuse case, not a complete proof.

4. **(AC4) Confirm-before catalog is documented.** NEW `src/lib/overlays/confirmationCatalog.ts` (pure `.ts`) exports a typed catalog enumerating the actions that ALWAYS require confirmation per UX-DR18: `rejectWithReason` (Approval/Decision Bar, story 2.19) · `approveWhenStaleOrConflict` · `stopOrchestrator` (Epic 3) · `retryOrRecoverConsequential` (Epic 4). Each entry carries `{ id, requiresConfirmation: true, intent, consequenceTemplate, owningStory }`. The catalog is **documentation + a typed lookup** consumers import; this story does NOT wire it into any done composite (Reconciliation / OQ-1). A unit test asserts each documented action is present, `requiresConfirmation === true`, and carries a non-empty `consequenceTemplate`.

5. **(AC5) `<ConfirmationDialog>` accessibility baseline.** On open, focus moves into the overlay (radix default) and on close returns to the triggering element (focus-restoration **tested** via a focus assertion); the dialog title is the `aria-labelledby` target and the `consequence` is the `aria-describedby` target; Escape dismissal is predictable for `<ConfirmationDialog>`/`<RationaleCaptureDialog>`/`<BoundedDetailSheet>`, and `<NonDismissibleCriticalWarning>` **blocks Escape** (`onEscapeKeyDown` prevented) **and** outside-pointer dismissal (`onPointerDownOutside`/`onInteractOutside` prevented), rendering **no** close affordance other than the acknowledgment control. Full axe/contrast audit deferred to 2.25 (documented).

6. **(AC6) Mobile full-height sheet + destructive separation.** `<BoundedDetailSheet>` supports a full-height bottom-sheet treatment (`side="bottom"` slide-up) for narrow breakpoints — wired via a documented prop/utility (full breakpoint-matrix validation deferred to **2.26**); destructive actions use `intent="danger"` styling and remain visually separated from the confirm action in the footer (distinct elements, not adjacent same-weight buttons). A test asserts the bottom-sheet variant renders and that danger-intent confirm is rendered as a distinct/`destructive`-styled element separated from cancel.

7. **(AC7) `<GovernedButton priority>` + single-primary ESLint rule.** NEW `src/components/actions/GovernedButton.tsx` extends the shadcn `Button` (composition, not edit) with `priority="primary|secondary|tertiary|destructive|blocked"` mapping to documented visual treatments (`primary`→default, `secondary`→secondary, `tertiary`→ghost/link, `destructive`→destructive, `blocked`→non-interactive blocked visual). NEW `tools/eslint-rules/single-primary-action.js` asserts no React component renders more than one element carrying `priority="primary"` (literal) within the same `<ButtonGroup>` or `<DecisionArea>` ancestor (NEW container components in `src/components/actions/`). Conservative: only statically-literal `priority="primary"` attributes are counted; dynamic `priority={expr}` cannot be proven and is skipped (documented). RuleTester suite: valid (one primary in a group; multiple primaries in DIFFERENT groups; dynamic priority); invalid (two literal primaries in one `<ButtonGroup>`/`<DecisionArea>`). Registered via the 5-touchpoint pattern.

8. **(AC8) `priority="blocked"` is a non-interactive blocked-state visual.** `<GovernedButton priority="blocked">` renders as a **non-interactive** blocked-state visual using the `--state-blocker*` token (story 2.3) with a **required adjacent explanation** (`blockedExplanation` prop, rendered as visible text + linked via `aria-describedby`) — NOT a plain `disabled` primary button. A test asserts the blocked variant is non-interactive (no click handler fires / `aria-disabled` semantics), carries the blocker signifier (icon+label, non-color), and renders its adjacent explanation.

9. **(AC9) `workflowState` reflects workflow truth.** `<GovernedButton workflowState="ready|blocked|stale|submitting|completed">` maps to documented visual + ARIA treatments: `submitting`→spinner + `aria-busy="true"` + non-interactive; `completed`→post-action checkmark + `aria-live` confirmation; `blocked`→blocked visual (composes AC8); `stale`→warning/stale treatment; `ready`→normal. `workflowState` is a **local presentation union defined in `src/components/actions/`** — it must NOT import the 2.19 domain `workflowState` mapping from `src/features/workflows/` (T-LAYERING). Tests assert each state's signifier + ARIA (`aria-busy` on submitting, `aria-live` region on completed).

10. **(AC10) `workflowState="completed"` persists until reset.** A `<GovernedButton workflowState="completed">` **persists** its outcome visual and never auto-clears — the parent controls reset (mirrors 2.21 `persistsUntil` / 2.19 AC9 reset timing). A test mounts it `completed`, advances timers, and asserts the outcome visual is still present (no auto-dismiss); a controlled re-render to `ready` clears it.

11. **(AC11) Mobile: primary never collapses.** `<ButtonGroup>`/`<DecisionArea>` provide a documented structure where the `priority="primary"` action stays in the always-visible region and `secondary`/`tertiary` may move into an overflow affordance — the primary is structurally excluded from collapse. A jsdom/structural test asserts the primary remains rendered in the non-overflow region while secondary/tertiary are eligible for the overflow slot. Full mobile-breakpoint matrix deferred to **2.26** (documented).

12. **(AC12) Variant inventory has fixtures + playground + render tests.** Every overlay variant (confirmation danger/warning/info, rationale-capture, bounded-detail-sheet incl. bottom variant, non-dismissible-critical) and every button variant (5 priorities × the workflowStates, blocked-with-explanation) has a fixture in NEW `src/test/fixtures/overlays/overlayFixtures.ts`, a section in `src/dev/PrimitivesPlayground.tsx`, and a **fixture-driven RTL render test** (NOT `toMatchSnapshot` — 2.27 owns pixel-diff). Tests cover: ConfirmationDialog focus management + Escape behavior + consequence required (type test); RationaleCaptureDialog field validation; BoundedDetailSheet mobile full-height variant; NonDismissibleCriticalWarning blocks Escape + outside-click; single-primary-action rule catches violations (RuleTester); blocked-state GovernedButton renders with explanation; `workflowState="completed"` persists until reset.

## Tasks / Subtasks

- [x] **Task 1 — overlay presentation helpers (`.ts`) + `confirmationCatalog.ts`** (AC: 1, 2, 4)
  - [x] NEW `src/components/overlays/overlayPresentation.ts` (pure `.ts`, T-REFRESH): map `intent: 'danger'|'warning'|'info'` → `{ stateName: StateName; iconName; tokenClass }` using `@/lib/state-signifiers` + `--state-*` tokens (`danger`→`blocker`/`error` tier, `warning`→`warning`, `info`→`informational`). Export `OverlayIntent` type. No `features/workflows` import (T-LAYERING).
  - [x] NEW `src/lib/overlays/confirmationCatalog.ts` (pure `.ts`): typed `ConfirmationCatalogEntry` + `CONFIRMATION_CATALOG` record keyed by action id (`rejectWithReason`, `approveWhenStaleOrConflict`, `stopOrchestrator`, `retryOrRecoverConsequential`), each `{ id, requiresConfirmation: true, intent: OverlayIntent, consequenceTemplate: string, owningStory: string }`. Document loud-and-clear: **documentation + typed lookup; consumers (2.19 done, Epic 3/4 future) adopt it later — this story does NOT wire it (OQ-1).** Unit-test presence + invariants.

- [x] **Task 2 — `<ConfirmationDialog>` + `<RationaleCaptureDialog>`** (AC: 1, 2, 5)
  - [x] NEW `src/components/overlays/ConfirmationDialog.tsx`: wraps shadcn `Dialog`/`DialogContent`. Props `{ open; onOpenChange; title: string; intent: OverlayIntent; consequence: string /* REQUIRED */; confirmLabel?; cancelLabel?; onConfirm: () => void; onCancel?; isConfirming?: boolean; className?; testId? }`. Renders `<DialogTitle>` (the `aria-labelledby` target) + the `consequence` text (the `aria-describedby` target) + intent signifier (icon+label from `overlayPresentation`, non-color) + footer with a `<GovernedButton priority={intent==='danger'?'destructive':'primary'}>` confirm and a `secondary`/`tertiary` cancel. Confirm reflects `isConfirming` via `workflowState="submitting"`. Escape predictable (radix default). `data-confirmation-dialog` + `data-intent` stamps.
  - [x] NEW `src/components/overlays/RationaleCaptureDialog.tsx`: composes `<ConfirmationDialog>` (so `consequence` stays required) + a typed `fields: RationaleField[]` (`{ name; label; type: 'text'|'textarea'|'select'; required?; options?; validate?(value): string | undefined }`). Renders labeled inputs (reuse `ui/input`/`ui/textarea`/`ui/select` + `ui/label`); confirm is disabled until required fields validate; per-field validation errors surface inline (reuse `<InlineFeedback variant="error">` or an `aria-describedby` error node). `onConfirm(values)` receives the collected field map. This is the **reject-with-reason** shape (2.19) — built but NOT wired here.
  - [x] A11y (AC5): radix handles focus-move-in + focus-restore-on-close. Title→`aria-labelledby`, consequence→`aria-describedby`. Add a **focus-restoration test** (trigger button → open → close → assert focus back on trigger).

- [x] **Task 3 — `<BoundedDetailSheet>` + `<NonDismissibleCriticalWarning>`** (AC: 1, 5, 6)
  - [x] NEW `src/components/overlays/BoundedDetailSheet.tsx`: wraps shadcn `Sheet`/`SheetContent`. Props `{ open; onOpenChange; title; side?: 'right'|'bottom'; fullHeightOnMobile?: boolean; children; className?; testId? }`. `side="bottom"` (or `fullHeightOnMobile`) → full-height slide-up sheet (AC6). Closing returns to context without reset (presentational — holds no state). `<SheetTitle>` for SR. Children TRUSTED by default (T-UNTRUSTED). `data-bounded-detail-sheet` stamp.
  - [x] NEW `src/components/overlays/NonDismissibleCriticalWarning.tsx`: wraps shadcn `Dialog` with `onEscapeKeyDown={(e)=>e.preventDefault()}` + `onPointerDownOutside={(e)=>e.preventDefault()}` + `onInteractOutside={(e)=>e.preventDefault()}`; renders NO `DialogClose`/X — the ONLY exit is an explicit acknowledgment `<GovernedButton priority="primary">{acknowledgmentLabel}</GovernedButton>` calling `onAcknowledge`. Props `{ open; title; body: ReactNode; acknowledgmentLabel: string; onAcknowledge: () => void; intent?: OverlayIntent /* default 'danger' */; testId? }`. `role="alertdialog"`, title→`aria-labelledby`, body→`aria-describedby`. `data-non-dismissible` stamp.
  - [x] NEW `src/components/overlays/index.ts` barrel exporting the four components + `OverlayIntent`/types.

- [x] **Task 4 — governed button hierarchy: `<GovernedButton>` + `<ButtonGroup>` + `<DecisionArea>`** (AC: 7, 8, 9, 10, 11)
  - [x] NEW `src/components/actions/buttonHierarchy.ts` (pure `.ts`, T-REFRESH): `ButtonPriority` (`primary|secondary|tertiary|destructive|blocked`) + `ButtonWorkflowState` (`ready|blocked|stale|submitting|completed`) unions (LOCAL — not the 2.19 domain union); `PRIORITY_VARIANT` map (`priority`→shadcn `variant`); `workflowStatePresentation(state)` → `{ iconName?; ariaBusy?; ariaLive?; nonInteractive; tokenClass? }`.
  - [x] NEW `src/components/actions/GovernedButton.tsx`: composes shadcn `Button` (import from `@/components/ui/button` — do NOT edit that file, T-UI-PURE). Props extend `ButtonProps` minus `variant`, plus `{ priority: ButtonPriority; workflowState?: ButtonWorkflowState; blockedExplanation?: string; testId? }`. `priority="blocked"` OR `workflowState="blocked"` → non-interactive blocked visual (`--state-blocker` token, `aria-disabled`, blocker signifier) + REQUIRED `blockedExplanation` (visible text + `aria-describedby`) — TS-narrow so blocked requires the explanation. `submitting` → spinner (use the trusted `ui`/`feedback` spinner path, NOT a raw `animate-spin` outside the boundary — `no-untyped-loading-state`) + `aria-busy` + non-interactive. `completed` → checkmark + `aria-live` outcome, persists until parent resets (AC10 — never auto-clears). `data-priority` + `data-workflow-state` stamps.
  - [x] NEW `src/components/actions/ButtonGroup.tsx` + `src/components/actions/DecisionArea.tsx`: layout containers stamping `data-button-group` / `data-decision-area` (the single-primary ESLint rule's ancestor markers). `DecisionArea` provides the always-visible-primary region + an overflow slot for secondary/tertiary (AC11 structure). Keep primary structurally outside the collapsible region.
  - [x] NEW `src/components/actions/index.ts` barrel + types.

- [x] **Task 5 — `single-primary-action` ESLint rule (5 touchpoints)** (AC: 7)
  - [x] NEW `tools/eslint-rules/single-primary-action.js`: walk `JSXElement`; when the opening element's `localName` is `ButtonGroup` or `DecisionArea`, count descendant `JSXOpeningElement`s carrying a **literal** `priority="primary"` attribute (mirror `no-untyped-loading-state.js`'s `localName`/attribute helpers); if `> 1`, report on the 2nd+. Skip dynamic `priority={expr}` (cannot prove). `messages` + `messageId`; document the literal-only limitation + escape hatch in the header.
  - [x] NEW `tools/eslint-rules/__tests__/single-primary-action.test.js`: RuleTester (`node:test` + `tseslint.parser`, mirror `no-untyped-loading-state.test.js`). Valid: one primary in a group; two primaries in DIFFERENT groups; dynamic `priority={p}`. Invalid: two literal `priority="primary"` in one `<ButtonGroup>` and one `<DecisionArea>`.
  - [x] REGISTER (touchpoints 3–5): add to `tools/eslint-rules/index.js`; add `'local-rules/single-primary-action': 'error'` to the `src/**` rules block in `eslint.config.js`; append the test file to `lint:rules-test` in `package.json`.

- [x] **Task 6 — `no-confirmation-for-navigation` ESLint rule (5 touchpoints)** (AC: 3)
  - [x] NEW `tools/eslint-rules/no-confirmation-for-navigation.js`: on a `<ConfirmationDialog>` `JSXOpeningElement`, inspect its confirm-handler attribute (`onConfirm`); if the value is an inline arrow/function whose body is a single statement (or expression) that is **only** a navigation call (`navigate(...)`, `router.navigate(...)`, member `.navigate(`) or a documented compare-entry call (e.g. `enterCompare`/`setCompareMode`), report. Conservative — any additional statement/effect suppresses the report (false-negatives over false-positives). `messageId` + documented escape hatch + the T-AC3-PRAGMATIC note that `confirmationCatalog.ts` is authoritative.
  - [x] NEW `tools/eslint-rules/__tests__/no-confirmation-for-navigation.test.js`: RuleTester. Valid: `onConfirm` performing a mutation; `onConfirm` with a navigation PLUS another effect; a non-ConfirmationDialog element navigating. Invalid: `onConfirm={() => navigate('/queue')}`; `onConfirm={() => router.navigate(...)}`; compare-entry-only confirm.
  - [x] REGISTER (touchpoints 3–5): `index.js` + `eslint.config.js` (`src/**`, `error`) + `package.json` `lint:rules-test`. After Tasks 5+6 the script runs **7** suites (was 5).

- [x] **Task 7 — fixtures + PrimitivesPlayground section** (AC: 12)
  - [x] NEW `src/test/fixtures/overlays/overlayFixtures.ts` (pure `.ts`): one fixture per overlay variant (confirmation danger/warning/info, rationale-capture with fields, bounded-detail-sheet right + bottom, non-dismissible-critical) + per button variant (5 priorities, each workflowState, blocked-with-explanation, a single-primary `DecisionArea` example).
  - [x] MODIFY `src/dev/PrimitivesPlayground.tsx`: add an "Overlays + Button Hierarchy" section rendering each fixture (open-on-click for overlays; static gallery for buttons). Reuse the existing `?playground` lazy mount; no second `<Toaster>`.

- [x] **Task 8 — Tests** (AC: 2, 3, 5, 6, 7, 8, 9, 10, 11, 12)
  - [x] `src/components/overlays/__tests__/ConfirmationDialog.test.tsx`: opens/closes; title→`aria-labelledby`, consequence→`aria-describedby`; **focus restoration** to trigger on close (AC5); Escape closes; danger intent renders destructive confirm separated from cancel (AC6); a `// @ts-expect-error` or `tsd`-style assertion that omitting `consequence` fails to compile (AC2).
  - [x] `…/__tests__/RationaleCaptureDialog.test.tsx`: required-field validation gates confirm; per-field error renders; `onConfirm(values)` receives the map (AC1/AC2).
  - [x] `…/__tests__/BoundedDetailSheet.test.tsx`: renders; `side="bottom"`/`fullHeightOnMobile` full-height variant (AC6); presentational/in-flow.
  - [x] `…/__tests__/NonDismissibleCriticalWarning.test.tsx`: Escape blocked (overlay stays open) + outside-pointer blocked; no close affordance other than acknowledgment; `onAcknowledge` fires; `role="alertdialog"` (AC5).
  - [x] `src/components/actions/__tests__/GovernedButton.test.tsx`: each `priority` maps to its variant + signifier; `priority="blocked"`/`workflowState="blocked"` non-interactive + renders required `blockedExplanation` (AC8); `submitting` → `aria-busy` + spinner + non-interactive (AC9); `completed` → checkmark + `aria-live`, persists across timer advance, clears on controlled reset (AC9/AC10).
  - [x] `…/__tests__/ButtonGroup.test.tsx` / `DecisionArea.test.tsx`: primary stays in the non-overflow region; secondary/tertiary eligible for overflow (AC11).
  - [x] ESLint RuleTester suites from Tasks 5+6 (AC3/AC7).
  - [x] Router-free, query-free, no MSW (presentational). Mirror `EmptyState.test.tsx` / 2.21 primitive tests. Plus the logging task's console-spy pin (if any anomaly branch is added).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Frontend (React/TS) story — the backend SLF4J/MDC standard does not apply literally. These are **presentational primitives with no network calls**, so the logging surface is minimal and **field-only**. Mirror 2.21's `feedback.toastSuppressedFallback` discipline:
    - Add a `console.warn({ event, … })` ONLY on a genuine anomaly branch — e.g. `overlay.blockedButtonMissingExplanation` (`warn`) if a `blocked` button is somehow rendered without an explanation (defense-in-depth behind the TS narrowing), or `overlay.unknownIntent` if `intent` resolves to no presentation. Log **event + intent/priority/workflowState key only** — NEVER the `title`/`consequence`/`body`/`children`/field values (composite- or potentially runner-supplied).
    - If a primitive has genuinely no anomaly branch, it carries **no** log line — do NOT manufacture noise; the ESLint rules + render tests are the contract.
  - [x] Use parameterized structured objects — never string concatenation. Pin any log line added with a `console`-spy test + an exact-key-set negative assertion (no `title`/`consequence`/`body`).

## Dev Notes

### THE CENTRAL RECONCILIATION (read this first)

This is the **fourth** epic-2b infrastructure story and, like 2.21, it inverts the usual dependency direction. Its governed-action consumer (**2.19 ApprovalDecisionBar** — reject-with-reason confirmation, single-primary enforcement, `workflowState` handling) shipped **first** (active-slice pull order) and built **region-local** versions of exactly these patterns. Consequences for how 2.23 must behave:

- **2.23 is additive infrastructure + extraction, not greenfield and not a retrofit.** Build the overlay primitives, the governed button hierarchy, the catalog, and the two ESLint rules. Do NOT edit `ApprovalDecisionBar.tsx`, `ApprovalDecisionBarContainer.tsx`, `ClarificationRegion.tsx`, the shadcn `ui/button.tsx`, or any mutation hook to consume them — those are `done`/generic, and rewiring risks regressing closed stories for no in-scope AC. The primitives are **drop-in supersets**; the **retrofit is a documented deferred follow-up** (OQ-1) for a later cleanup story (or 2.27's test-suite consolidation).
- **The two ESLint rules are the genuine new enforcement that "reaches back."** They are safe precisely because there are **zero** `<ConfirmationDialog>` / `<GovernedButton priority="primary">` call sites today (the components are brand-new) — so turning both rules to `error` lights up **clean**. Verify with `npm run lint` after wiring (grep first: zero `<ConfirmationDialog`/`<GovernedButton` JSX usages outside the playground + tests).
- **Epic AC4's "these actions ALWAYS confirm"** is satisfied by *shipping the `confirmationCatalog.ts` documentation + the primitives*, not by editing the done composites. 2.19 already confirms reject-with-reason region-locally; the catalog records the contract and future consumers (Epic 3 stop-orchestrator, Epic 4 retry) adopt the shared primitives when their stories land.
- **Three backlog siblings gate the "audit" ACs:** 2.27 (snapshot harness) ⇒ AC12 uses fixtures + RTL, never `toMatchSnapshot`; 2.25 (axe/WCAG) ⇒ AC5 ships focus/ARIA baseline + focused tests, defers the full audit; 2.26 (responsive) ⇒ AC6/AC11 ship the bottom-sheet variant + "primary never collapses" structure, defer the breakpoint matrix.

### What is genuinely NEW vs generalized

- **Genuinely new:** the four overlay primitives (`src/components/overlays/`); the governed button hierarchy (`src/components/actions/` — `GovernedButton`/`ButtonGroup`/`DecisionArea`); the `confirmationCatalog.ts`; the two ESLint rules (`single-primary-action`, `no-confirmation-for-navigation`); the PrimitivesPlayground overlays section.
- **Generalized from region-local prior art:** the single-primary / `workflowState` / consequence-bearing-reject behavior (from 2.19's `ApprovalDecisionBar` + `approvalDecisionView.ts`); the "persist post-decision until reset" rule (sibling of 2.21's `persistsUntil` + 2.19 AC9). 2.19's copies stay as-is (additive).

### Layering: overlays + actions stay generic

- `src/components/overlays/` and `src/components/actions/` are **generic infrastructure** (architecture.md:1182). They MUST NOT import from `src/features/workflows/**` (no `StateSignifierChip`, `WorkflowStateBadge`, `approvalDecisionView`, `workflowStateMapping`). They consume **generic** contracts only: `@/lib/state-signifiers`, `@/lib/utils` (`cn`), `@/lib/density`, `@/components/ui/*` primitives, `@/components/feedback/*`, `@/lib/overlays/confirmationCatalog`, and `lucide-react`.
- **`workflowState` is a LOCAL presentation union** in `src/components/actions/buttonHierarchy.ts` — do NOT reuse or import the 2.19 domain `workflowState` view-model from `features/workflows`. One presentation source of truth; the domain mapping is a separate concern (T-NO-PARALLEL-UNION-DOMAIN).
- `intent` (`danger|warning|info`) maps to `StateName` via `overlayPresentation.ts` — do NOT mint a parallel state union; reuse `StateName`/`STATE_SIGNIFIERS`.

### Frontend conventions (architecture)

- Stack: React + TypeScript, Vite, TanStack Query (server state) + TanStack Router (typed routes), shadcn/ui + Tailwind (radix under the hood), Vitest + RTL + MSW. [Source: architecture.md#445–520]
- Presentational primitives hold **no server state** and take no query/router dependency; they receive trusted props from composites. [Source: architecture.md#516]
- Component files PascalCase `.tsx`; pure helpers/maps/types in sibling `.ts` (`react-refresh/only-export-components`, `allowConstantExport: true` ⇒ only literal `const`/`type` exports may sit beside a component) [memory: `frontend-react-refresh-no-fn-exports`].
- Never color alone — every state/intent carries an icon + label from `STATE_SIGNIFIERS` (story 2.3 AC5). [Source: ux-design-specification.md#story-2.3-AC5]

### Traps (do NOT step on these)

- **T-NO-RETROFIT — do not edit done stories or the generic shadcn button.** No changes to `ApprovalDecisionBar.tsx`, `ApprovalDecisionBarContainer.tsx`, `ClarificationRegion.tsx`, any mutation hook, or `src/components/ui/button.tsx`. Retrofit is deferred (OQ-1). Touching them = out of scope + regression risk.
- **T-UI-PURE — `<GovernedButton>` COMPOSES `ui/button.tsx`, never edits it.** The shadcn `Button` stays a generic primitive (the `no-workflow-domain-in-ui-primitives` philosophy). `priority`/`workflowState` are governed-workflow vocabulary and live in `src/components/actions/`, NOT in `src/components/ui/`. (Note: `no-workflow-domain-in-ui-primitives` only catches `features/workflows` *imports* — it would NOT catch adding the props to `ui/button.tsx`, so this is a discipline trap, not a lint-caught one.)
- **T-LAYERING — overlays/actions import only generic libs.** No `import … from '@/features/workflows/…'`. Mirror `StateSignifierChip`'s rendering using `@/lib/state-signifiers`; do not import the chip or the domain `workflowState` mapping.
- **T-NO-PARALLEL-UNION-DOMAIN — `actions` `workflowState` is its own presentation union.** Do not fork or import 2.19's domain union; do not reuse `StateName` for it either (it is a 5-value presentation set). `intent`, by contrast, DOES map onto `StateName`.
- **T-CONSEQUENCE-REQUIRED — `consequence` is type-required.** Make it a non-optional `string` so `tsc` rejects omission (AC2). `<RationaleCaptureDialog>` composes `<ConfirmationDialog>` so it inherits the requirement — do not loosen it.
- **T-NON-DISMISSIBLE — block ALL implicit exits.** `<NonDismissibleCriticalWarning>` prevents `onEscapeKeyDown` AND `onPointerDownOutside`/`onInteractOutside` AND renders no `DialogClose`/X. The only exit is the acknowledgment button. Test both Escape and outside-click.
- **T-NO-STACK — avoid overlay stacking.** Overlays are single-layer; do not nest a `<ConfirmationDialog>` inside a `<BoundedDetailSheet>` in the fixtures/playground (UX-DR18 "avoid stacking").
- **T-SPINNER-BOUNDARY — `submitting` spinner must use the sanctioned path.** A raw `animate-spin` className outside `src/components/feedback/states` + `src/components/ui` trips `no-untyped-loading-state`. Render the submitting spinner via a `ui` primitive or the feedback `<LoadingState>` path, or keep the spinner element inside the trusted boundary.
- **T-UNTRUSTED — overlays take trusted children.** Default children/body are composite-authored. If a caller passes runner/agent text it must sanitize via `@/lib/sanitization` first; document on the prop. Do not render raw HTML (`no-unsanitized-html`).
- **T-ESLINT-5-TOUCHPOINTS ×2 — each new rule has five edits.** Rule file + test file + `index.js` register + `eslint.config.js` rules entry + `package.json` `lint:rules-test` script. TWO rules this story ⇒ `lint:rules-test` goes 5 → 7 suites. Missing any one ⇒ the rule silently doesn't run or isn't covered.
- **T-AC3-PRAGMATIC — `no-confirmation-for-navigation` is best-effort.** Static handler-intent detection cannot be complete; keep it conservative (literal/obvious cases only) and document that `confirmationCatalog.ts` is the authoritative "which actions confirm" source.
- **T-NO-NEW-DEP — radix/cva/slot/lucide already installed.** `dialog.tsx`/`sheet.tsx` already import `@radix-ui/react-dialog`; `button.tsx` already imports `@radix-ui/react-slot` + `cva`. No npm install ⇒ no lockfile change ⇒ no WSL2/Linux smoke [memory: `frontend-lockfile-cross-platform`, `wsl-linux-ci-reproduction`].
- **T-REFRESH — pure helpers/maps/types in `.ts`.** `overlayPresentation.ts`, `confirmationCatalog.ts`, `buttonHierarchy.ts`, `overlayFixtures.ts` are `.ts`; only components are `.tsx` [memory: `frontend-react-refresh-no-fn-exports`].
- **T-EXACT-OPTIONAL — `exactOptionalPropertyTypes: true`.** Declare optional props that may receive `undefined` from fixtures as `?: T | undefined` (the TS2375 trap 2.21 hit on the playground). Watch `blockedExplanation`, `workflowState`, `side`, `confirmLabel`.
- **T-GATES — run gates via PowerShell, not Bash.** [memory: `rtk-hook-only-matches-bash`] Use native file tools + PowerShell for `tsc`/`eslint`/`vitest`/`prettier`. Run `prettier --write` before finishing or the format gate cascades and skips downstream jobs [memory: `prettier-gate-cascades-ci`]. On Windows, `node --test` may need the suites run sequentially if worker spawning throws `EPERM` (2.21 hit this).

### Open Questions (recommendations in brackets — proceed with the recommendation unless told otherwise)

- **OQ-1 — retrofit 2.19 to consume the new overlays/GovernedButton now, or defer?** **[Recommend: DEFER.]** 2.19 is `done` with large green suites; rewiring its region-local single-primary/reject-confirm/`workflowState` to the shared primitives is a behavior-preserving refactor with real regression surface and **no in-scope AC requiring it** (epic AC4 says the infra *provides* the catalog + primitives; the composite already confirms region-locally). Ship the supersets + a `[Defer]` note pointing a later cleanup (or 2.27 consolidation) at the swap. Keep this story additive.
- **OQ-2 — where does the governed button live: extend `ui/button.tsx` or a new `actions` module?** **[Recommend: NEW `src/components/actions/` module composing the shadcn `Button`.]** Epic AC7 says "extending shadcn primitive" — read as *composition*, not *editing the generic file*. `priority`/`workflowState` are governed-workflow vocabulary; putting them in the stock `ui/button.tsx` violates the "ui primitives stay generic" architecture invariant (the same principle `no-workflow-domain-in-ui-primitives` defends). `GovernedButton` wraps `Button` and lives beside the overlays infra. The `single-primary-action` lint rule keys on the `priority="primary"` **attribute** (element-name-agnostic), so it works regardless of the component's name.
- **OQ-3 — single-primary rule scope: same-container only, or whole-component?** **[Recommend: same `<ButtonGroup>`/`<DecisionArea>` ancestor.]** Epic AC7 says "within the same container". A component may legitimately have several decision areas each with its own primary; scoping to the container avoids false positives. Literal-`priority="primary"`-only (dynamic skipped) keeps it sound.
- **OQ-4 — `no-confirmation-for-navigation` breadth.** **[Recommend: conservative inline-handler analysis]** — flag only when `onConfirm` is an inline arrow/function whose sole effect is navigation/compare-entry. Any additional statement suppresses it. Document `confirmationCatalog.ts` as authoritative (T-AC3-PRAGMATIC). Broaden later if abuse appears.
- **OQ-5 — `<BoundedDetailSheet>` mobile trigger: prop or breakpoint hook?** **[Recommend: documented `side`/`fullHeightOnMobile` prop now]** — the actual breakpoint switch is 2.26's job. Ship the bottom-sheet variant + the prop; defer the responsive auto-switch + breakpoint-matrix test to 2.26.
- **OQ-6 — does `workflowState="completed"` need its own outcome text, or reuse `<PersistentStateBadge>`?** **[Recommend: inline checkmark + `aria-live` on the button itself]** for the button's own outcome (AC9/AC10), and let composites additionally render a `<PersistentStateBadge>`/`<InlineFeedback>` for the broader region (2.21). Don't duplicate 2.21's badge inside the button.

### Logging Requirements (project-wide standard, frontend-adapted)

Presentational primitives with no network calls ⇒ minimal logging surface. Field-only structured `console.warn({ event, …keys })` for genuine anomaly branches ONLY (e.g. `overlay.blockedButtonMissingExplanation`, `overlay.unknownIntent`); **never** log `title`/`consequence`/`body`/`children`/field values (composite- or potentially runner-supplied). Pin any added line with a `console`-spy test + exact-key-set negative assertion. Do not manufacture log noise on branches with no anomaly — the ESLint rules + render tests are the real contract.

### Project Structure Notes

New + modified files:

```
deliveryline-frontend/
├── src/components/overlays/                          (NEW module)
│   ├── index.ts                                      (NEW — barrel)
│   ├── overlayPresentation.ts                        (NEW — intent→StateName/icon/token map; .ts)
│   ├── ConfirmationDialog.tsx                        (NEW)
│   ├── RationaleCaptureDialog.tsx                    (NEW — composes ConfirmationDialog)
│   ├── BoundedDetailSheet.tsx                        (NEW)
│   ├── NonDismissibleCriticalWarning.tsx             (NEW)
│   └── __tests__/
│       ├── ConfirmationDialog.test.tsx               (NEW)
│       ├── RationaleCaptureDialog.test.tsx           (NEW)
│       ├── BoundedDetailSheet.test.tsx               (NEW)
│       └── NonDismissibleCriticalWarning.test.tsx    (NEW)
├── src/components/actions/                            (NEW module)
│   ├── index.ts                                      (NEW — barrel)
│   ├── buttonHierarchy.ts                            (NEW — priority/workflowState unions + maps; .ts)
│   ├── GovernedButton.tsx                            (NEW — composes ui/button)
│   ├── ButtonGroup.tsx                               (NEW)
│   ├── DecisionArea.tsx                              (NEW)
│   └── __tests__/
│       ├── GovernedButton.test.tsx                   (NEW)
│       ├── ButtonGroup.test.tsx                      (NEW)
│       └── DecisionArea.test.tsx                     (NEW)
├── src/lib/overlays/confirmationCatalog.ts           (NEW — typed confirm-before catalog; .ts)
├── src/lib/overlays/__tests__/confirmationCatalog.test.ts (NEW)
├── src/test/fixtures/overlays/overlayFixtures.ts     (NEW)
├── src/dev/PrimitivesPlayground.tsx                  (MODIFIED — add Overlays + Button Hierarchy section)
├── tools/eslint-rules/
│   ├── single-primary-action.js                      (NEW — rule)
│   ├── no-confirmation-for-navigation.js             (NEW — rule)
│   ├── index.js                                      (MODIFIED — register BOTH rules)
│   └── __tests__/
│       ├── single-primary-action.test.js             (NEW — RuleTester)
│       └── no-confirmation-for-navigation.test.js    (NEW — RuleTester)
├── eslint.config.js                                  (MODIFIED — BOTH rules at `error` in src/** block)
└── package.json                                      (MODIFIED — append BOTH test files to lint:rules-test → 7 suites)
```

No new npm dependency ⇒ **no lockfile change ⇒ no WSL2/Linux-CI smoke needed** [memory: `wsl-linux-ci-reproduction`, `frontend-lockfile-cross-platform`].

### Testing standards

- **Fixture-driven RTL — never `toMatchSnapshot`** (2.27's snapshot harness does not exist). Assert on roles/text/`data-*` attributes + `aria-*` values + focus.
- Presentational primitives ⇒ **router-free, query-free, no MSW** for the component tests. Mirror `EmptyState.test.tsx` / the 2.21 primitive tests.
- Overlay tests use RTL `userEvent` for open/close/Escape/outside-click + focus assertions (radix renders into a portal — query via `screen`/`within(document.body)`).
- The `consequence`-required check (AC2) is a **compile-time** assertion (`// @ts-expect-error` fixture or `tsd`-style) — a runtime test cannot prove a required prop.
- ESLint rules ⇒ `RuleTester` + `node:test` (mirror `no-untyped-loading-state.test.js`); run via `npm run lint:rules-test` (now **7** suites). If Windows blocks `node --test` worker spawning (`EPERM`), run the suites sequentially.
- Console-spy assertion for any log line + an exact-key-set negative test (no `title`/`consequence`/`body`).

### Gate verification (run via PowerShell — `rtk-hook-only-matches-bash`)

```
tsc -b                                    # 0 errors (incl. the consequence-required @ts-expect-error fixture)
eslint . --max-warnings=0                 # 0 (incl. the 2 NEW rules at error; verify zero existing violations)
npm run lint:rules-test                   # 7/7 custom-rule suites (was 5)
vitest run                                # full suite green (+ new overlay/action tests)
prettier --write "src/**/*.{ts,tsx}"      # before finishing (prettier-gate-cascades-ci)
```

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.23 (lines 1356–1375)] — the 12 epic ACs (typed overlay primitives, consequence-required, no-modal-for-low-risk ESLint rule, confirm-before catalog, a11y focus/Escape, mobile sheet, single-primary-action button rule, blocked-state variant, workflowState truth, persist-until-reset, mobile-primary-never-collapses, test coverage).
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Modal, Overlay, and Confirmation Patterns (1914–1950)] — UX-DR18: overlays for high-consequence only, confirm-before list, no-modal-for-low-risk-nav, return-without-reset, state-the-consequence, focus move/restore, Escape predictable-except-unsafe, mobile full-height sheet, the 4 variants.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Button Hierarchy (1952–1990)] — UX-DR19: one primary per decision area, blocked-state-not-promoted, workflow-truth states, post-decision-visible, adjacent-explanation, explicit-verb labels, mobile-primary-reachable, the 5 variants.
- [Source: _bmad-output/planning-artifacts/architecture.md#516, #1182] — presentational primitives hold no server state; generic UI/feedback infra lives under `src/components/**`, not `features/workflows`.
- [Source: deliveryline-frontend/src/components/ui/dialog.tsx] — radix `Dialog` to wrap (`onEscapeKeyDown`/`onPointerDownOutside` for non-dismissible; focus handled by radix).
- [Source: deliveryline-frontend/src/components/ui/sheet.tsx] — radix `Sheet` (cva `side` incl. `bottom`) to wrap for `<BoundedDetailSheet>`.
- [Source: deliveryline-frontend/src/components/ui/button.tsx] — shadcn `Button` + `buttonVariants` to COMPOSE (not edit) in `<GovernedButton>`; `variant` map target.
- [Source: deliveryline-frontend/src/lib/state-signifiers.ts] — `STATE_SIGNIFIERS`, `StateName` (intent→state + blocked-signifier source; non-color contract).
- [Source: deliveryline-frontend/src/styles/globals.css (≈87–177)] — `--state-*` tokens incl. `--state-blocker*` (DOMINANT, story 2.3) backing `priority="blocked"`; focus-ring token (story 2.4).
- [Source: deliveryline-frontend/tools/eslint-rules/no-untyped-loading-state.js] — JSX-AST rule reference (`JSXOpeningElement`, `localName`, attribute reading) for `single-primary-action`.
- [Source: deliveryline-frontend/tools/eslint-rules/no-workflow-toast-success.js] — import-tracking + `Program:exit` deferral reference; the 5-touchpoint registration pattern.
- [Source: deliveryline-frontend/tools/eslint-rules/{index.js}, eslint.config.js, package.json `lint:rules-test`] — the 5-touchpoint wiring (5 rules today → 7 after this story).
- [Source: deliveryline-frontend/src/components/feedback/primitives/] — 2.21 feedback primitives to compose (`<InlineFeedback>` for rationale-field errors; "persist until reset" sibling of `workflowState="completed"`).
- [Source: 2-21-feedback-patterns-infrastructure-inline-persistent-toast-boundaries.md] — the ordering-inversion + additive-extraction discipline this story mirrors; the `exactOptionalPropertyTypes` TS2375 playground trap; the Windows `node --test` EPERM sequential-run note.
- [Source: 2-19-approval-decision-bar-generalized-composite-spec-approval-mode.md] — region-local single-primary / reject-with-reason-confirm / `workflowState` prior art to GENERALIZE (not retrofit); the AC9 reset-timing contract `workflowState="completed"` mirrors.
- [Source: deliveryline-frontend/src/dev/PrimitivesPlayground.tsx] — dev-only playground to extend (AC12 fixtures); lazy `?playground` mount.
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml:170–181] — 2.18/2.19/2.21 `done`; 2.23 `backlog`; 2.25/2.26/2.27 `backlog`; `epic-2b: deferred`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story workflow.

### Debug Log References

- `node --test tools/eslint-rules/__tests__/{single-primary-action,no-confirmation-for-navigation}.test.js` → both new RuleTester suites green.
- `npm run lint:rules-test` → **7/7** custom-rule suites (was 5).
- `npx tsc -b` → 0 errors (incl. the AC2 `// @ts-expect-error` consequence-required fixture).
- `npx eslint . --max-warnings=0` → 0 errors / 0 warnings — both NEW rules light up **clean** (zero existing `<ConfirmationDialog>` / `<GovernedButton priority="primary">` call sites, as predicted).
- `npx vitest run` → full suite **578 passed** (was 540; +38 new overlay/action/catalog/logging tests).
- `npx prettier --check "src/**/*.{ts,tsx}"` → clean.
- Fix applied during dev: AC5 focus-restoration — radix only restores focus to a `<DialogTrigger>`, which a CONTROLLED/composite-opened dialog has none of; added an explicit capture-on-open / restore-on-close effect + `onCloseAutoFocus` preventDefault in `<ConfirmationDialog>` so the trigger regains focus on close.
- Trap dodged: TS aliased-condition narrowing collapsed `blockedExplanation` to `string` inside the `isBlocked` block (flagging the defense-in-depth guard as `no-unnecessary-condition`); routed the check through `isBlockedPresentation(...)` so the runtime guard stays meaningful and lint stays at 0 warnings.

### Completion Notes List

Additive, frontend-only infrastructure + extraction — NO edits to any `done` composite/hook or to the generic shadcn `ui/button.tsx` (T-NO-RETROFIT / T-UI-PURE honored; OQ-1 retrofit of 2.19 left deferred). All 6 OQ recommendations followed as written.

- **AC1/AC2/AC5/AC6 — overlay primitives** (`src/components/overlays/`): `<ConfirmationDialog>` (intent danger/warning/info; `consequence` is a **non-optional** `string` → radix-wired `aria-describedby`; danger → destructive `<GovernedButton>` confirm distinct from cancel), `<RationaleCaptureDialog>` (composes `<ConfirmationDialog>` so `consequence` is inherited-required; typed `fields` with required-gating + inline `aria-describedby`/`aria-invalid` errors; `onConfirm(values)` map), `<BoundedDetailSheet>` (wraps shadcn `Sheet`; `side="bottom"`/`fullHeightOnMobile` full-height variant), `<NonDismissibleCriticalWarning>` (`role="alertdialog"`; blocks Escape + outside-pointer + renders no close X — only exit is the acknowledgment button). `overlayPresentation.ts` maps `intent → StateName` via the generic `@/lib/state-signifiers` (no `features/workflows` import — T-LAYERING).
- **AC7/AC8/AC9/AC10/AC11 — governed button hierarchy** (`src/components/actions/`): `<GovernedButton>` **composes** shadcn `Button` (priority→variant); `priority="blocked"`/`workflowState="blocked"` → non-interactive `--state-blocker` visual with a **TS-required** `blockedExplanation` (visible + `aria-describedby`), NOT a disabled button; `submitting` → the sanctioned `<LoadingState>` spinner + `aria-busy` + non-interactive (T-SPINNER-BOUNDARY); `completed` → checkmark + `aria-live` that **persists until the parent resets** (no auto-clear); `stale` → stale treatment. `<ButtonGroup>`/`<DecisionArea>` stamp the single-primary ancestor markers and give the always-visible-primary + overflow-eligible-secondary structure (primary never collapses).
- **AC4 — `confirmationCatalog.ts`** (`src/lib/overlays/`): typed `CONFIRMATION_CATALOG` enumerating `rejectWithReason`/`approveWhenStaleOrConflict`/`stopOrchestrator`/`retryOrRecoverConsequential`, each `{ requiresConfirmation: true, intent, consequenceTemplate, owningStory }`. Documentation + typed lookup only — **not wired** into any done composite.
- **AC3/AC7 — two NEW ESLint rules** via the 5-touchpoint pattern (`lint:rules-test` 5 → 7): `single-primary-action` (≥2 literal `priority="primary"` in one `<ButtonGroup>`/`<DecisionArea>`; dynamic `priority={expr}` skipped) and `no-confirmation-for-navigation` (`<ConfirmationDialog>` whose `onConfirm` only navigates/enters-compare; conservative, `confirmationCatalog.ts` authoritative).
- **AC12 — fixtures + playground + tests**: `overlayFixtures.ts` (every overlay + button variant), a new "Overlays + Button Hierarchy" section in `PrimitivesPlayground.tsx`, and fixture-driven RTL render tests (roles/text/`data-*`/`aria-*`/focus — never `toMatchSnapshot`).
- **Logging**: the only anomaly branch — `overlay.blockedButtonMissingExplanation` (`console.warn`, field-only: `event`/`priority`/`workflowState`, **never** children/explanation) — pinned with a console-spy + exact-key-set negative assertion. No other primitive manufactures log noise.
- **No new npm dependency** ⇒ no lockfile change ⇒ no WSL2/Linux-CI smoke needed.

### File List

**NEW — overlays (`deliveryline-frontend/src/components/overlays/`)**
- `overlayPresentation.ts`
- `ConfirmationDialog.tsx`
- `RationaleCaptureDialog.tsx`
- `BoundedDetailSheet.tsx`
- `NonDismissibleCriticalWarning.tsx`
- `index.ts`
- `__tests__/ConfirmationDialog.test.tsx`
- `__tests__/RationaleCaptureDialog.test.tsx`
- `__tests__/BoundedDetailSheet.test.tsx`
- `__tests__/NonDismissibleCriticalWarning.test.tsx`

**NEW — actions (`deliveryline-frontend/src/components/actions/`)**
- `buttonHierarchy.ts`
- `GovernedButton.tsx`
- `ButtonGroup.tsx`
- `DecisionArea.tsx`
- `index.ts`
- `__tests__/GovernedButton.test.tsx`
- `__tests__/ButtonGroup.test.tsx`
- `__tests__/DecisionArea.test.tsx`

**NEW — catalog + fixtures**
- `deliveryline-frontend/src/lib/overlays/confirmationCatalog.ts`
- `deliveryline-frontend/src/lib/overlays/__tests__/confirmationCatalog.test.ts`
- `deliveryline-frontend/src/test/fixtures/overlays/overlayFixtures.ts`

**NEW — ESLint rules + RuleTester suites (`deliveryline-frontend/tools/eslint-rules/`)**
- `single-primary-action.js`
- `no-confirmation-for-navigation.js`
- `__tests__/single-primary-action.test.js`
- `__tests__/no-confirmation-for-navigation.test.js`

**MODIFIED**
- `deliveryline-frontend/tools/eslint-rules/index.js` (register both rules)
- `deliveryline-frontend/eslint.config.js` (both rules at `error` in the `src/**` block)
- `deliveryline-frontend/package.json` (append both test files to `lint:rules-test` → 7 suites)
- `deliveryline-frontend/src/dev/PrimitivesPlayground.tsx` (add "Overlays + Button Hierarchy" section)

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2026-06-08 | 0.1 | Created story 2.23 — Modal/Overlay/Confirmation Patterns + Button Hierarchy Infrastructure (overlay primitives `ConfirmationDialog`/`RationaleCaptureDialog`/`BoundedDetailSheet`/`NonDismissibleCriticalWarning`, governed button hierarchy `GovernedButton`/`ButtonGroup`/`DecisionArea`, `confirmationCatalog.ts`, two new ESLint rules `single-primary-action` + `no-confirmation-for-navigation`, fixtures/playground/tests). Resolved the ordering inversion: additive infra + extraction of 2.19's region-local patterns, NOT a retrofit (OQ-1 deferred); governed button composes (not edits) the generic shadcn `ui/button.tsx` (OQ-2). Ready-for-dev. | Alex (create-story) |
| 2026-06-09 | 1.0 | Implemented story 2.23 (dev-story). Shipped the four overlay primitives + the governed button hierarchy + `confirmationCatalog.ts` + both new ESLint rules (5-touchpoint, `lint:rules-test` 5→7) + `overlayFixtures.ts` + the PrimitivesPlayground section + fixture-driven RTL tests. Additive — no edits to any `done` composite/hook or the generic shadcn `ui/button.tsx` (OQ-1 retrofit deferred). All 12 ACs satisfied. Gates GREEN via PowerShell ([[rtk-hook-only-matches-bash]]): `tsc -b` 0, `eslint --max-warnings=0` 0 (both new rules clean), `lint:rules-test` 7/7, full `vitest run` 578/578 (+38), `prettier --check` clean ([[prettier-gate-cascades-ci]]). No new npm dep ⇒ no lockfile/WSL2 smoke ([[frontend-lockfile-cross-platform]]). Notable dev fixes: explicit focus-restoration for the controlled `<ConfirmationDialog>` (radix only restores to a `<DialogTrigger>`); `isBlockedPresentation()` helper to keep the blocked defense-in-depth guard from being flagged by TS aliased-narrowing. Status → review. | Amelia (dev-story) |

## Review Findings

_Adversarial code review 2026-06-09 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 patch, 10 deferred, 13 dismissed as noise/within-spec. No Critical findings; Acceptance Auditor verdict: accept (all 12 ACs satisfied, every hard guardrail held — purely additive, no protected-file edits, no new dep, no snapshots)._

### Patch (fixable now, unambiguous)

- [x] [Review][Patch] (FIXED) `RationaleCaptureDialog` does not reset form state across close→reopen [deliveryline-frontend/src/components/overlays/RationaleCaptureDialog.tsx:98-102] — `values`/`touched`/`submitAttempted` are seeded once via `useState` with no `useEffect`/`key` tied to `open`. After a user types a reason, cancels, and reopens, the prior (abandoned) rationale **and** all "X is required" errors persist into the next decision — wrong for a reject-with-reason flow. Fix: reset state on the `open`→false transition, re-deriving `values` from the current `fields` (also closes the common changed-`fields`-between-opens drift where `onConfirm` emits stale keys). [blind+edge]
- [x] [Review][Patch] (FIXED) `GovernedButton` blocked `aria-describedby` guard contradicts the empty-explanation guard [deliveryline-frontend/src/components/actions/GovernedButton.tsx:99,116,127] — the warn path treats `blockedExplanation.trim() === ''` as missing, but `aria-describedby` and the explanation `<span>` are both gated on `!== undefined`. An empty-string explanation therefore renders a dangling `aria-describedby` pointing at an empty node (and still warns). Fix: compute one `hasExplanation = blockedExplanation !== undefined && blockedExplanation.trim() !== ''` predicate and use it for both. [blind]

### Deferred (real, out-of-scope / lower-value / design-nuanced — see deferred-work.md)

- [x] [Review][Defer] `completed`/`submitting` `aria-live` nested inside an `aria-busy` button may not reliably announce the outcome [GovernedButton.tsx:153,148] — deferred to the full a11y/axe audit (story 2.25); literal AC9 (`aria-busy` on submitting, `aria-live` on completed) is satisfied. [blind+edge+auditor]
- [x] [Review][Defer] `ConfirmationDialog` manual focus-restoration is unreliable for programmatically/composite-opened dialogs (captures whatever `document.activeElement` was at open, often `<body>`) [ConfirmationDialog.tsx:901-912] — a11y refinement, baseline restoration handled; story 2.25. [blind+edge]
- [x] [Review][Defer] `NonDismissibleCriticalWarning` focus-restoration-on-close not explicitly handled (relies on radix `FocusScope` default — verify it restores rather than dropping to `<body>`) [NonDismissibleCriticalWarning.tsx] — verify under 2.25; auditor judged AC5 met. [edge]
- [x] [Review][Defer] `GovernedButton` blocked branch drops all `...rest` passthrough props (`id`, `aria-label`, `data-*`) [GovernedButton.tsx:80-138] — no current call sites; the fix needs a design call on which props to forward without breaking non-interactivity. [blind+edge]
- [x] [Review][Defer] `single-primary-action` does not flag two literal primaries across **nested** `ButtonGroup`s inside one `DecisionArea` (only counts within the nearest container) [tools/eslint-rules/single-primary-action.js] — rule-scope enhancement; `confirmationCatalog` + review backstop; spec sanctions conservative detection. [blind+edge]
- [x] [Review][Defer] `NonDismissibleCriticalWarning` renders `body` (typed `ReactNode`) inside `DialogDescription` (a `<p>`) — block content would be invalid DOM nesting [NonDismissibleCriticalWarning.tsx:1056] — fixtures currently pass inline content; constrain or restructure later. [blind]
- [x] [Review][Defer] `RationaleCaptureDialog` collapses duplicate field `name`s → duplicate DOM `id`s break `htmlFor`/`aria-describedby` [RationaleCaptureDialog.tsx:98,145] — consumer-contract edge (field names should be unique). [edge]
- [x] [Review][Defer] `BoundedDetailSheet` Escape-predictability + focus-restoration are untested (radix-default behavior) [src/components/overlays/__tests__/BoundedDetailSheet.test.tsx] — coverage thinness; AC5 lists the sheet. [edge+auditor]
- [x] [Review][Defer] `RationaleCaptureDialog` missing focus-restoration test + `consequence`-required type test [src/components/overlays/__tests__/RationaleCaptureDialog.test.tsx] — coverage thinness; required-ness is enforced structurally. [auditor]
- [x] [Review][Defer] `RationaleCaptureDialog` ignores a `fields` prop changed **while open** (stale `useState` initializer) [RationaleCaptureDialog.tsx:98-100] — rare mid-open case; the common between-opens case is covered by the patch above. [blind+edge]
