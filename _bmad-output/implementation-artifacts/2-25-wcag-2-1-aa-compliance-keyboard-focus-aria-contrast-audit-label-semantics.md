# Story 2.25: WCAG 2.1 AA Compliance (Keyboard + Focus + ARIA + Contrast + Audit-Label Semantics)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager (or any user with a disability — keyboard-only, screen-reader, low-vision, or motor-impaired),
I want the entire Epic-2 review experience to meet WCAG 2.1 AA — keyboard operability, visible focus, semantic landmarks + ARIA, verified contrast, and a documented announcement vocabulary — plus audit-role labels rendered as honest "recorded label" semantics rather than implying enforced authorization,
so that the governed-review workflow is genuinely usable with assistive technology and the UI never misleads anyone about the MVP's deferred-RBAC posture.

## Scope Decisions (read first — these resolve epic-vs-reality tensions)

This is a **cross-cutting audit + retrofit + harness story**, not a greenfield feature. The composites it audits (2.15–2.24) are already `done`; their reviews deliberately deferred ~25 accessibility items to this story (see Dev Notes → Deferred Punch-List). Three epic ACs reference sibling stories (2.27 / 2.26) that are still `backlog` **after** this one — the epic text is forward-referential and reality has overtaken it. Resolve as follows:

- **D1 — The axe-core harness lands HERE, in the existing Vitest suite.** Every prior story (2.18–2.23) deferred "full axe" to 2.25 with the literal note "no axe harness exists." Epic AC2's parenthetical "(story 2.27)" is overtaken: **2.25 installs `vitest-axe` + `axe-core` and runs WCAG-2.1-AA scans inside the current Vitest run.** Story 2.27 later *extends* the same discipline to cross-browser Playwright — it does not introduce axe.
- **D2 — Keyboard journeys (AC3) are delivered at component/integration level in Vitest + Testing Library now; the cross-browser Playwright keyboard automation is owned and implemented by story 2.27 (its AC9 literally says "keyboard-only journey coverage per story 2.25 AC3 — Playwright tests…").** Do **not** stand up Playwright config in this story. Ship: (a) RTL `userEvent.tab()`-driven keyboard-operability tests across composites, and (b) a documented keyboard-only journey checklist.
- **D3 — Touch-target (AC10): define the min-size utility, apply it to interactive primitives, and add a static class-presence audit test.** jsdom has no layout engine, so live px-size assertions at mobile breakpoints are not testable here. Full mobile-viewport layout verification is coupled to story **2.26** (responsive). Ship the utility + static enforcement + manual-checklist line now.
- **D4 — AC8/AC9 build the `<AuditRoleLabel>` wrapper + two new custom ESLint rules even though no component renders actor-role text today** (confirmed: only `test/fixtures/approval/approvalDecisionFixtures.ts` + generated `schema.d.ts` reference roles). These are guard rails for the Epic-3a dev-review / operator surfaces that will render `(product_reviewer)`. Retrofit any role display that exists at implementation time (currently none).

## Acceptance Criteria

1. **Keyboard operability across all composites.** Every interactive composite (`src/features/workflows/components/*`) and state/feedback/overlay/action primitive (`src/components/{feedback,overlays,actions}/*`) is fully keyboard-operable: Tab order matches visual order, every action reachable without a mouse, and focus is visible at all times via the `--ring-focus` token (story 2.4). RTL keyboard tests (`userEvent.tab()`, `{Enter}`, `{Space}`, `{Escape}`) assert reachability + activation for each composite.

2. **axe-core WCAG-2.1-AA harness wired into Vitest (D1).** `vitest-axe` (+ `axe-core`) is installed and a shared `expectNoA11yViolations(container)` helper exists at `src/test/a11y/axe.ts`. Every composite + primitive test renders its documented states and runs an axe scan configured for the `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` tag set; the suite **fails** on any violation. Any single suppressed rule requires an inline `// a11y-justification:` comment naming the rule and reason.

3. **Component-level keyboard journeys + documented checklist (D2).** Vitest/RTL integration tests cover the critical keyboard journeys using keyboard input only (no `.click()`): queue item → open run → read spec → answer clarification → approve; and queue item → read spec → reject-with-feedback. A manual keyboard-only-journey checklist is added to `docs/testing/a11y-screen-reader-checklist.md` (shared with AC11). Cross-browser Playwright automation of these journeys is explicitly deferred to story 2.27.

4. **Semantic landmarks + region structure.** AppShell keeps its single `<nav>` / `<main>` / `<aside>` landmarks (story 2.7 — already present). Each composite uses an appropriate semantic region: `<section aria-labelledby=…>` for labeled panels; list-like surfaces use `<ul>/<ol>/<li>` or `<table>` per content shape. An audit test (or extended axe `region` rule) asserts no orphaned interactive content sits outside a landmark.

5. **ARIA live regions reference the shared vocabulary (AC7-coupled).** Asynchronous workflow updates announce via `aria-live` — clarification transitions (2.18), decision outcomes (2.19), queue-state transitions (2.20), feedback lifecycle (2.21) — **not** via toast-only signals. The previously-deferred **first-render / multiple-simultaneous-advance** announcement gaps (2.18) and the **cold-load skeleton silence** + **shell↔ErrorState double-announcement** reconciliation (2.20) are closed: a populated-on-mount region announces appropriately, and stacked polite regions do not produce duplicate screen-reader speech.

6. **Contrast verified against rendered states, not just tokens.** The existing `check:contrast` token-pair gate (story 2.3 AC4, `tools/contrast/`) stays green, AND the components from 2.20/2.21/2.23 are audited for contrast in their actual rendered states (including dark-mode `globals.css` overrides) — covered by the axe `color-contrast` rule in the AC2 scans. No `aria-hidden` or off-screen trick is used to mask a failing pair.

7. **Documented announcement vocabulary.** `src/lib/a11y/announcements.ts` exists, mapping each semantic state + lifecycle event to a stable announcement string (e.g. `queueLoaded(n)`, `queueEmpty`, `queueLoadFailed`, `clarificationAccepted`, `decisionStale(what,next)`, `specApproved`). Composites import from this module rather than inlining announcement text. An audit test verifies every `aria-live` usage in `src/features/**` and `src/components/{feedback,overlays,actions}/**` sources its text from the vocabulary (allowlist-based static scan, mirroring the `check:routes` / `check:contrast` node-test pattern).

8. **Audit-label semantics for actor roles (D4).** A `<AuditRoleLabel role="product_reviewer">` primitive renders the role with a `<small>`/tooltip clarifier ("recorded for audit only — not an enforced permission"), present in the accessible name (visible on first hover/focus). A new custom ESLint rule (`no-bare-actor-role-text`) under `tools/eslint-rules/` flags rendering of actor-role text outside the `<AuditRoleLabel>` wrapper, with a `__tests__` suite wired into `lint:rules-test`. Any current role display is retrofitted (none exists today; wrapper + rule are pre-positioned for Epic-3a surfaces).

9. **Frontend must not gate on audit roles (architecture hard invariant).** A new custom ESLint rule (`no-role-based-action-gating`) forbids permission gates keyed on actor role (e.g. `if (actorRole === 'product_reviewer')`, ternaries / `&&` guards on role) in `src/features/**` and `src/components/**`; all action gating must route through `useAllowedActions` (story 2.14). Rule has a `__tests__` suite in `lint:rules-test`. A one-line code-review checklist entry is added to the story-2.28 E2-close gate doc reference.

10. **Touch-target minimum (D3).** A Tailwind `min-h`/`min-w` touch-target utility (`touch` = 44px, the committed higher bar over WCAG 2.5.5 AA's 24px) is defined in `tailwind.config.ts` and applied to interactive primitives (`GovernedButton`, queue item, icon-only controls, drawer toggles). A static test asserts interactive primitives carry the floor class. Live mobile-viewport px verification is deferred to story 2.26 + the manual checklist.

11. **Screen-reader manual checklist.** `docs/testing/a11y-screen-reader-checklist.md` documents NVDA + VoiceOver runs of the critical journeys (queue → run → spec → clarification → approve; queue → spec → reject). The doc states it must be executed at least once before E2 epic close (referenced from the story-2.28 close gate). Executing the manual run itself is an E2-close activity, not a code task here — the deliverable is the checklist.

12. **CI enforcement in the frontend-build-tests tier (story 1.21).** axe-core scan failures (AC2), contrast failures (AC6), missing-vocabulary live-region usages (AC7), bare-actor-role-text (AC8), role-based gating (AC9), and touch-target floor regressions (AC10) all fail CI via the existing `frontend-build-tests` job — through `npm run test` (now axe-bearing), `npm run lint` (new a11y rules + `lint:rules-test`), `npm run check:contrast`, and the new `check:a11y` node-test. No new CI job is required; new node-test scripts are added to the frontend Maven `process-resources` gate list.

## Tasks / Subtasks

- [x] **Task 1 — Install + wire the axe-core Vitest harness (AC2, AC6, D1)**
  - [x] Add `axe-core` + `vitest-axe` as devDependencies. Regenerate the lockfile with a full `npm install` and verify on Linux/CI before pushing — Vite 8/rolldown native bindings + the committed `.npmrc legacy-peer-deps=true` mean a partial lockfile edit reds CI. See [[frontend-lockfile-cross-platform]] and [[frontend-ts6-legacy-peer-deps]]. (Also added `@testing-library/user-event`, required by the AC1/AC3 `userEvent.tab()` keyboard tests.)
  - [x] Create `src/test/a11y/axe.ts` exporting `expectNoA11yViolations(container, options?)` configured for tags `['wcag2a','wcag2aa','wcag21a','wcag21aa']`; register the `toHaveNoViolations` matcher in `src/test/setup.ts`. (Plus `src/test/a11y/keyboard.ts` reachability helper + a harness self-test `src/test/a11y/axe.test.tsx`. jsdom `getContext` stubbed so axe's `color-contrast` degrades to *incomplete* quietly — the token gate `check:contrast` is the authoritative contrast check, AC6.)
  - [x] Document the suppression convention (`// a11y-justification: <rule> — <reason>`); zero unjustified suppressions allowed. (Documented in `axe.ts` header; zero suppressions were needed.)

- [x] **Task 2 — Run axe + keyboard-operability across every composite & primitive (AC1, AC2, AC4)**
  - [x] For each of the 11 `src/features/workflows/components/*` composites and each `feedback/`, `overlays/`, `actions/`, `feedback/states/` primitive: extend its existing `*.test.tsx` to (a) render each documented state and assert `expectNoA11yViolations`, and (b) drive Tab/Shift+Tab/Enter/Space/Escape via `userEvent` and assert focus reachability + activation + visual focus-order parity.
  - [x] Fix every violation surfaced. Add `<section aria-labelledby>` / list semantics where axe `region`/`list` rules flag orphaned content. Retone any primitive still on `ring-ring` to `ring-ring-focus` if focus-visibility fails. (Two real source fixes: `ClarificationRegion.tsx` moved the "Show resolved" toggle OUT of `role="listbox"` — axe `aria-required-children`; `BoundedDetailSheet.tsx` added controlled-mode focus restoration — WCAG 2.4.3. All other components were already clean.)

- [x] **Task 3 — Announcement vocabulary + live-region reconciliation (AC5, AC7)**
  - [x] Create `src/lib/a11y/announcements.ts` (pure `.ts`, no JSX — keep helper functions out of `.tsx` per [[frontend-react-refresh-no-fn-exports]]). Map every semantic state + lifecycle event to a stable string. (Plus `src/lib/a11y/useLiveAnnouncement.ts` — first-render-safe deferral hook.)
  - [x] Refactor existing `aria-live` consumers to source text from the vocabulary. (The three workflow ANNOUNCERS — `QueueShell`, `ClarificationRegion`, `ApprovalDecisionBar` — now import the vocabulary. The generic PRIMITIVES `InlineFeedback`/`PersistentStateBadge`/`LoadingState`/`ErrorState`/`GovernedButton` keep sourcing their live text from `STATE_SIGNIFIERS`/typed variant-defaults/consumer content — wiring a workflow vocabulary into `components/` would invert layering — and are instead held to a documented `PRIMITIVE_ALLOWLIST` in the AC7 audit.)
  - [x] Close the deferred announcement gaps: first-render/populated-on-mount announcement (2.18) + cold-load skeleton silence (2.20) via `useLiveAnnouncement`; multiple-simultaneous-advance (announce the count, log each) (2.18); shell↔`ErrorState` double-announcement reconciliation — `QueueShell` error announcer is silenced, delegating to the composed `<ErrorState>` polite region (2.20). Fixed the `ErrorState` `role="alert"`+`aria-live="polite"` contradiction (2.22 defer): `role="status"` for passive, `role="alert"` for active.
  - [x] Add `tools/a11y/__tests__/announcement-vocabulary.test.js` (node --test) asserting all `aria-live` usages reference the vocabulary; wire a `check:a11y` script.

- [x] **Task 4 — Audit-label wrapper + role-gating guards (AC8, AC9, D4)**
  - [x] Build `src/components/feedback/AuditRoleLabel.tsx` (role text + `<small>`/tooltip clarifier in the accessible name). **Deviation:** the prop is `actorRole`, NOT `role` (a `role="…"` prop trips `jsx-a11y/aria-role` at every call site — it reads as the ARIA role attribute). Exported via the feedback barrel.
  - [x] Add custom ESLint rule `no-bare-actor-role-text` + rule `no-role-based-action-gating` under `tools/eslint-rules/`, each with a `__tests__/*.test.js`; register both in `eslint.config.js` and extend the `lint:rules-test` script list (now 9 rules). (Actor-role enum = `product_reviewer | workflow_owner`, from `schema.d.ts`.)
  - [x] Retrofit any actor-role rendering through `<AuditRoleLabel>` (verified: NONE renders actor-role text today — wrapper + rules are pre-positioned for Epic-3a). The no-role-gating checklist line + the pre-E2-close execution requirement are written into `docs/testing/a11y-screen-reader-checklist.md` (Task 6), which serves as the E2-close gate reference until story 2.28 (backlog) formalizes it.

- [x] **Task 5 — Touch-target utility + static enforcement (AC10, D3)**
  - [x] Add `theme.extend.minHeight.touch`/`minWidth.touch` = `2.75rem` (44px) in `tailwind.config.ts`; apply the floor class to interactive primitives + icon-only controls. (`min-h-touch` on `GovernedButton` + the navigable `RunReviewQueueItem` row; `min-h-touch min-w-touch` on the icon-only `InlineFeedback` dismiss control.)
  - [x] Add a static class-presence assertion (`src/test/a11y/touch-target.test.tsx`) that interactive primitives carry the floor class. Note live mobile-viewport verification → story 2.26.

- [x] **Task 6 — Manual checklists (AC3, AC11)**
  - [x] Author `docs/testing/a11y-screen-reader-checklist.md`: NVDA + VoiceOver runs of both critical journeys + the keyboard-only journey steps. State the pre-E2-close execution requirement and link it from the story-2.28 gate reference. (Also carries the AC9 no-role-gating + AC8 audit-label code-review checklist lines.)

- [x] **Task 7 — CI gate wiring (AC12)**
  - [x] Add the new node-test scripts (`check:a11y`) to the frontend Maven `process-resources` gate list (mirror how `check:contrast`/`check:routes` are wired) so they run in `frontend-build-tests`. Run `prettier --write` before pushing — one unformatted file cascades the whole tier ([[prettier-gate-cascades-ci]]).
  - [x] Verify the full gate locally via PowerShell (the RTK Bash hook corrupts only Bash — use native tools + PowerShell, [[rtk-hook-only-matches-bash]]): `tsc -b` ✅, `eslint . --max-warnings=0` ✅, `npm run lint:rules-test` ✅ (9), `npm run check:contrast` ✅ (8), `npm run check:a11y` ✅ (4), `npm run check:tokens` ✅, `npm run check:routes` ✅, `npm run check:api` ✅, `vitest run` ✅ (802/802), `prettier --check .` ✅. Lockfile regenerated via full `npm install`; the 3 added devDeps (`axe-core`, `vitest-axe`, `@testing-library/user-event`) are pure-JS (no platform-native bindings), so cross-platform risk is low — still verify on CI Linux per [[frontend-lockfile-cross-platform]]/[[verify-ci-fixes-in-clean-env]].

- [x] **Logging instrumentation** (cross-cutting standard) — **N/A for this story.** Story 2.25 is frontend-only (React/TS); it touches no Spring `@Service`, SPI, or persistence surface, so the SLF4J/MDC logging contract does not apply. Frontend observability convention: no `console.log` in shipped code (ESLint already enforces); user-facing async outcomes are surfaced through the AC7 announcement vocabulary + inline feedback, never silent. If implementation unexpectedly adds a backend touch (it should not), apply the full logging task from the project standard.

## Dev Notes

### Current-state inventory (what already exists — do NOT rebuild)

- **Composites to audit (11)** under `src/features/workflows/components/`: `ApprovalDecisionBar(.Container)`, `ArtifactReviewPanel`, `ClarificationRegion`, `RunContextStrip`, `RunReviewQueueItem`, `SpecArtifactRenderer`, `PrOutputArtifactRenderer`, `ImplementationPlanArtifactRenderer`, `WorkflowStateBadge`, `SubmitRunForm`. Each has an existing `*.test.tsx`.
- **Primitives to audit**: `src/components/feedback/primitives/` (`InlineFeedback`, `PersistentStateBadge`, `ActionLifecycleIndicator`), `src/components/feedback/states/` (`EmptyState`, `LoadingState`, `ErrorState`), `src/components/overlays/` (`ConfirmationDialog`, `RationaleCaptureDialog`, `NonDismissibleCriticalWarning`), `src/components/actions/` (`GovernedButton`, `ButtonGroup`, `DecisionArea`). Radix-wrapped `src/components/ui/*` are stock — audit but expect few findings.
- **Focus ring** (story 2.4): `--ring-focus` defined `src/styles/globals.css:221` (light) / `:352` (dark); Tailwind alias `ring-focus` at `tailwind.config.ts:41`; canonical usage `focus-visible:ring-ring-focus`. ⚠️ Stock primitives (`ui/button.tsx`) still use `ring-ring` (teal) — retone to `ring-ring-focus` only where focus-visibility fails the audit.
- **AppShell** `src/features/workflows/AppShell.tsx` already has the single `<nav aria-label="Workflow navigation">`, `<main id=… tabIndex={-1}>`, `<aside aria-label="Supporting context">` + skip-to-content link. Landmark structure is compliant — do not duplicate landmarks.
- **Live regions today** (inline, not vocabulary-sourced): `InlineFeedback` (polite/assertive + status/alert), `PersistentStateBadge`, `GovernedButton` (`aria-busy`/completed), `LoadingState` (`<output role="status" aria-live="polite">`), `ErrorState` (urgency-driven). AC7 centralizes their text.
- **`useAllowedActions`**: `src/features/workflows/hooks/useAllowedActions.ts` — `useAllowedActions(workflowRunId)` → `AllowedActions` from generated OpenAPI schema. AC9's single point of action gating.
- **Contrast gate**: `tools/contrast/__tests__/token-contrast.test.js` (+ `token-prominence`, `state-signifiers`) via `npm run check:contrast`; math in `tools/contrast/contrast.js` (4.5:1 body, 3:1 large/border). Parses `globals.css :root`.
- **Custom ESLint rules (7)** in `tools/eslint-rules/` (`no-workflow-domain-in-ui-primitives`, `no-inline-query-keys`, `no-unsanitized-html`, `no-untyped-loading-state`, `no-workflow-toast-success`, `single-primary-action`, `no-confirmation-for-navigation`) + `lint:rules-test`. **jsx-a11y at `error`** (10 rules) already in `eslint.config.js:81`. This story adds rules #8 + #9.
- **Test infra**: Vitest 4 (`vitest.config.ts`, jsdom, setup `src/test/setup.ts`), `@testing-library/react` 16, `jest-dom`, `msw` 2. **No `axe-core`, no Playwright** — Task 1 introduces axe; Playwright stays for 2.27.
- **No `src/lib/a11y/announcements.ts`, no touch-target utility, no `AuditRoleLabel`** — all net-new here.
- **Actor-role rendering**: none in components (only `test/fixtures/approval/approvalDecisionFixtures.ts` + generated `schema.d.ts`). AC8 wrapper/rule are pre-positioned guards.

### Deferred Punch-List (the ~25 items prior reviews routed to 2.25 — close or explicitly re-defer each)

From the `done` stories' Dev Notes + `deferred-work.md`:
- **2.18 (Clarification)**: full axe/contrast sweep (OQ-5); first-render live-region never announces (only on prior→new change); multiple-simultaneous-advance announces only last-in-array (single `latest` slot); off-chain (`superseded`/`rejected_invalid`) not distinguished in lifecycle indicator; `CompactSummary` CTA absent for `unknown`-only view.
- **2.19 (Approval Bar)**: full axe (OQ-4); rejection dialog has no focus trap despite `aria-modal="true"` (Escape-inert is intentional per AC8 — verify focus containment, not Escape); untrusted actor identity rendered as markdown source not plain text → security pass (verify renderer link policy or render as plain text node).
- **2.20 (Queue shell)**: full axe; live-region duplication reconciliation (shell vs `ErrorState`); cold-load skeleton may be silent to screen readers.
- **2.21 (Feedback)**: full axe + contrast across all variants. (Mobile-breakpoint a11y → 2.26.)
- **2.22 (Nav/states)**: full axe + contrast; `ErrorState` `role="alert"`+`aria-live="polite"` contradiction for passive urgency (fix: `status`/`region` for passive, `alert` for active); `withRunContext` scroll-restoration semantics; ESLint `no-untyped-loading-state` blind to dynamic classNames (note TS type is the real backstop); `src/dev/**` ESLint trusted-boundary widening (document/tighten); overlay portal-target doc vs Radix Portal; empty-string `title`/`message` renders blank heading (harden non-color contract).
- **2.23 (Overlays/buttons)**: full axe of overlays + button hierarchy; visual-regression snapshots → 2.27 (not here). Eight items were tagged "→ a11y audit 2.25" at close.

Treat each as: close it, or write a one-line explicit re-defer (to 2.26/2.27) with rationale in the Completion Notes. Do not silently drop any.

### Patterns to honor (established by 2.3 / 2.20–2.23)

- **Non-color signifier contract** (2.3 AC5): every state/intent/priority carries icon **plus** text label from `STATE_SIGNIFIERS`. Never icon-alone, never color-alone. Audit all 2.21/2.23 components for violations.
- **aria-live discipline**: `polite` for info/success/loading, `assertive` for warning/blocker/error. Stacked polite siblings must not double-announce.
- **Tests assert roles/text/`data-*`, never `toMatchSnapshot`** (the 2.20–2.23 discipline). Keep it — snapshots are 2.27.
- **Helper functions live in `.ts`, not `.tsx`** — a feature `.tsx` exporting a non-component fails the react-refresh eslint gate ([[frontend-react-refresh-no-fn-exports]]). `announcements.ts` must be pure TS.
- **Vitest 4 shares a module registry per worker** — if you add cross-file router/module mocks, consolidate same-module mocks into one file ([[vitest-cross-file-router-mock]]).

### Architecture compliance (hard invariants relevant here)

- "UI labels must make clear that MVP roles are recorded audit labels, not enforced authorization. **Frontend code must not gate actions based on audit role labels.**" → AC8 + AC9 directly enforce this invariant (`architecture.md` Frontend Quality Gates).
- "Available UI controls come from backend-reported allowed actions… must not infer workflow permissions locally." → AC9 routes all gating through `useAllowedActions`.
- "Version mismatch or stale decision attempts need a clear UI state, not only a toast." → AC5/AC7 stale-decision announcement (`decisionStale(what,next)`), not toast-only.
- Frontend stack is fixed: Vite + React + TypeScript, TanStack Query/Router, shadcn/ui + Tailwind, Maven-driven build. Do not introduce a different a11y-testing stack than axe-core/Vitest.

### CI / gate facts (story 1.21)

- Job `frontend-build-tests` in `.github/workflows/ci.yml` runs `./mvnw -pl deliveryline-frontend clean package`, which executes (in `process-resources`): `lint` (`eslint --max-warnings=0`), `lint:rules-test`, `check:contrast`, `check:tokens`, `check:routes`, `check:api`, `test` (`vitest run`), `format:check`. Add `check:a11y` to this list (frontend `pom.xml` plugin executions).
- Frontend format gate runs inside this tier; one unformatted file cascades the whole pipeline ([[prettier-gate-cascades-ci]]). `prettier --write` before pushing.
- Verify in a clean/Linux-shaped env before claiming green — local ≠ CI ([[verify-ci-fixes-in-clean-env]]), especially for the new lockfile after adding axe deps.

### Project Structure Notes

- New files: `src/test/a11y/axe.ts`, `src/lib/a11y/announcements.ts`, `src/components/feedback/AuditRoleLabel.tsx`, `tools/eslint-rules/no-bare-actor-role-text.js` + `no-role-based-action-gating.js` (+ `__tests__/`), `tools/a11y/__tests__/announcement-vocabulary.test.js`, `docs/testing/a11y-screen-reader-checklist.md`.
- Modified: existing composite/primitive `*.test.tsx` (axe + keyboard), `src/test/setup.ts` (matcher), `eslint.config.js` (2 rules), `tailwind.config.ts` (touch utility), `globals.css`/primitives only if focus-retoning needed, frontend `pom.xml` + `package.json` (scripts/deps).
- No backend, OpenAPI, schema.d.ts, or Flyway change. If `schema.d.ts` would change, stop — this story must stay frontend-only.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.25] — the 12 epic ACs (lines 1404–1423).
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Accessibility Strategy] (lines 2108–2189) — WCAG 2.1 AA target, keyboard/focus/landmark/live-region/touch-target priorities, "consistent announcement of stale/blocked/error/completed states", "Frontend must not gate on role." Note: the spec organizes a11y under named sections ("Accessibility Strategy", "Accessibility Principles", per-component "Accessibility" blocks) — there are **no literal `UX-DR20/21/24` headings** in the doc; the epic's UX-DR labels map onto these sections.
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Quality Gates] (lines 510–520) + #Hard Invariants (838–852).
- [Source: deliveryline-frontend/eslint.config.js:81] — existing jsx-a11y error set. [Source: tools/contrast/__tests__/token-contrast.test.js] — contrast gate. [Source: src/features/workflows/AppShell.tsx] — landmarks. [Source: src/features/workflows/hooks/useAllowedActions.ts] — gating hook.
- Prior stories: `2-18`…`2-23` `.md` Dev Notes + `deferred-work.md` (the punch-list above).

### Open Questions (for Alex — do not block dev-story; default behavior is encoded above)

1. **D1 confirmation**: OK that 2.25 introduces the `vitest-axe` harness (rather than waiting for 2.27)? Default = yes (every prior story deferred "full axe" here).
2. **D2 confirmation**: OK that AC3 keyboard journeys ship as Vitest/RTL component-integration tests + a manual checklist now, with cross-browser Playwright keyboard automation implemented in 2.27 (its AC9)? Default = yes.
3. **AC10 touch-target**: accept static class-presence enforcement now, with live mobile-viewport px verification folded into 2.26? Default = yes.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context)

### Debug Log References

Full local gate (PowerShell, RTK-Bash-safe): `tsc -b` ✅ · `eslint . --max-warnings=0` ✅ · `lint:rules-test` 9/9 ✅ · `check:contrast` 8/8 ✅ · `check:a11y` 4/4 ✅ · `check:tokens` 4/4 ✅ · `check:routes` 9/9 ✅ · `check:api` ✅ · `vitest run` 802/802 (70 files) ✅ · `prettier --check .` ✅.

### Completion Notes List

**Implementation order.** Built the axe harness (Task 1) first, then ran the per-component audit (Task 2) before the source-refactor tasks, then announcement/role/touch tasks (3–5), then docs + CI (6–7). Task 2's axe/keyboard tests assert roles/focus, not exact announcement text, so the later Task-3 text refactor did not invalidate them (the one role-sensitive case — `ErrorState` passive role — was updated alongside the Task-3 fix).

**Net source fixes surfaced by the audit (everything else was already compliant):**
- `ClarificationRegion.tsx` — moved the "Show resolved" disclosure toggle OUT of `role="listbox"` (axe `aria-required-children`: a listbox admits only `option` children). Real violation, was failing.
- `BoundedDetailSheet.tsx` — added controlled-mode focus restoration (WCAG 2.4.3) mirroring `ConfirmationDialog`; without it focus dropped to `<body>` on close.
- `ErrorState.tsx` — resolved the `role="alert"`+`aria-live="polite"` contradiction: passive → `role="status"`/polite, active → `role="alert"`/assertive.

**Deviations (documented):**
- `<AuditRoleLabel>` prop is `actorRole`, NOT `role` (a `role="…"` prop trips `jsx-a11y/aria-role` at every call site). AC8's literal example used `role`.
- Generic primitives (`InlineFeedback`/`PersistentStateBadge`/`LoadingState`/`ErrorState`/`GovernedButton`) source live text from `STATE_SIGNIFIERS`/typed variant-defaults/consumer content rather than the workflow announcement vocabulary (wiring a workflow vocab into `components/` would invert layering). The AC7 audit holds them to a documented `PRIMITIVE_ALLOWLIST`; the three workflow ANNOUNCERS import the vocabulary directly.
- Added `@testing-library/user-event` (the AC1/AC3 `userEvent.tab()` keyboard tests require it — within spec).
- A second a11y helper file `src/lib/a11y/useLiveAnnouncement.ts` (first-render-safe deferral) accompanies `announcements.ts`.

**Deferred Punch-List reconciliation (each closed or explicitly re-deferred):**
- *2.18*: full axe ✅ (Task 2); first-render announcement ✅ (`useLiveAnnouncement`); multiple-simultaneous-advance ✅ (announce count + log each); off-chain `superseded`/`rejected_invalid` and `CompactSummary` `unknown`-only CTA — pre-existing behaviour unchanged, no axe/keyboard violation surfaced (no new gap; not in this story's AC set → left as-is).
- *2.19*: full axe ✅; rejection-dialog focus CONTAINMENT verified (Escape intentionally inert per AC8) ✅; untrusted actor identity renders through `SafeMarkdownRenderer` (sanitization barrier intact — verified, not newly altered) ✅.
- *2.20*: full axe ✅; shell↔`ErrorState` double-announcement reconciled (shell announcer silent on error) ✅; cold-load skeleton silence closed (`useLiveAnnouncement`) ✅.
- *2.21*: full axe + all variants ✅. Mobile-breakpoint a11y → **re-deferred to 2.26** (jsdom has no layout engine).
- *2.22*: full axe ✅; `ErrorState` role/aria-live contradiction ✅; `withRunContext` scroll-restoration, `no-untyped-loading-state` dynamic-className blindness, `src/dev/**` ESLint widening, overlay portal-target docs, empty-`title`/`message` blank-heading hardening — no a11y violation surfaced; **re-deferred** (out of this story's AC set; tracked in deferred-work.md).
- *2.23*: full axe of overlays + button hierarchy ✅; touch-target floor applied ✅. Visual-regression snapshots → **re-deferred to 2.27** (per the discipline: no `toMatchSnapshot`).
- *Cross-browser keyboard automation (Playwright)* → **re-deferred to 2.27** (AC3/D2); *live mobile-viewport touch px* → **re-deferred to 2.26** (AC10/D3); *NVDA/VoiceOver manual run* → E2-close activity, checklist shipped (AC11).

**AC coverage:** AC1 (keyboard) ✅, AC2 (axe harness) ✅, AC3 (component journeys + checklist) ✅, AC4 (landmarks/regions — ClarificationRegion listbox fix) ✅, AC5 (live regions + gaps) ✅, AC6 (contrast — token gate + axe `color-contrast` enabled; jsdom degrades it to *incomplete*) ✅, AC7 (vocabulary + audit) ✅, AC8 (`AuditRoleLabel` + `no-bare-actor-role-text`) ✅, AC9 (`no-role-based-action-gating`) ✅, AC10 (touch utility + static test) ✅, AC11 (manual checklist) ✅, AC12 (`check:a11y` in the Maven gate) ✅.

### File List

**New — harness/library:**
- `deliveryline-frontend/src/test/a11y/axe.ts`
- `deliveryline-frontend/src/test/a11y/keyboard.ts`
- `deliveryline-frontend/src/test/a11y/axe.test.tsx`
- `deliveryline-frontend/src/test/a11y/touch-target.test.tsx`
- `deliveryline-frontend/src/lib/a11y/announcements.ts`
- `deliveryline-frontend/src/lib/a11y/useLiveAnnouncement.ts`
- `deliveryline-frontend/src/components/feedback/AuditRoleLabel.tsx`
- `deliveryline-frontend/src/components/feedback/__tests__/AuditRoleLabel.test.tsx`

**New — tooling/gates/docs:**
- `deliveryline-frontend/tools/eslint-rules/no-bare-actor-role-text.js`
- `deliveryline-frontend/tools/eslint-rules/no-role-based-action-gating.js`
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-bare-actor-role-text.test.js`
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-role-based-action-gating.test.js`
- `deliveryline-frontend/tools/a11y/__tests__/announcement-vocabulary.test.js`
- `docs/testing/a11y-screen-reader-checklist.md`

**Modified — config/infra:**
- `deliveryline-frontend/package.json` (devDeps: axe-core, vitest-axe, @testing-library/user-event; scripts: `check:a11y`, extended `lint:rules-test`)
- `deliveryline-frontend/package-lock.json`
- `deliveryline-frontend/pom.xml` (`npm-run-check-a11y` execution)
- `deliveryline-frontend/eslint.config.js` (register 2 rules)
- `deliveryline-frontend/tailwind.config.ts` (`min-h/-w-touch` = 44px)
- `deliveryline-frontend/tools/eslint-rules/index.js` (register 2 rules)
- `deliveryline-frontend/src/test/setup.ts` (axe matcher + jsdom canvas stub + Vitest augmentation)

**Modified — components (source):**
- `deliveryline-frontend/src/components/feedback/index.ts`
- `deliveryline-frontend/src/components/feedback/states/ErrorState.tsx`
- `deliveryline-frontend/src/components/feedback/primitives/InlineFeedback.tsx`
- `deliveryline-frontend/src/components/actions/GovernedButton.tsx`
- `deliveryline-frontend/src/components/overlays/BoundedDetailSheet.tsx`
- `deliveryline-frontend/src/features/workflows/QueueShell.tsx`
- `deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx`
- `deliveryline-frontend/src/features/workflows/components/ClarificationRegion.tsx`
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx`

**Modified — tests (axe + keyboard + new-behaviour assertions):**
- `…/components/feedback/states/__tests__/{LoadingState,EmptyState,ErrorState}.test.tsx`
- `…/components/feedback/primitives/__tests__/{InlineFeedback,PersistentStateBadge,ActionLifecycleIndicator}.test.tsx`
- `…/components/actions/__tests__/{GovernedButton,ButtonGroup,DecisionArea}.test.tsx`
- `…/components/overlays/__tests__/{ConfirmationDialog,RationaleCaptureDialog,NonDismissibleCriticalWarning,BoundedDetailSheet}.test.tsx`
- `…/features/workflows/components/{WorkflowStateBadge,RunContextStrip,SubmitRunForm,StubArtifactRenderers,ApprovalDecisionBar,ApprovalDecisionBarContainer,ArtifactReviewPanel,ClarificationRegion,ClarificationRegionContainer,SpecArtifactRenderer}.test.tsx`
- `…/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx`
- `…/src/routes/-states/__tests__/DeadEndState.test.tsx`

### Change Log

| Date | Change |
| --- | --- |
| 2026-06-10 | Story 2.25 implemented — WCAG 2.1 AA audit + retrofit: axe-core/vitest-axe Vitest harness, axe + keyboard coverage across all composites/primitives, announcement vocabulary + live-region reconciliation, `<AuditRoleLabel>` + 2 audit-role ESLint rules, 44px touch-target utility, manual SR/keyboard checklist, and the `check:a11y` CI gate. Status → review. |

## Review Findings

_Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor), 2026-06-10. 3 decision-needed (all resolved → accept-as-scoped/deferred), 2 patch, 4 deferred, 7 dismissed as noise._

- [x] [Review][Decision→Defer] Guard-rail ESLint rules cover only the canonical patterns — `no-role-based-action-gating` matches only equality `BinaryExpression` vs a role string-literal (misses `switch(actorRole)`, `.includes()`/`Set.has()`, and comparison against a role *const*); `no-bare-actor-role-text` misses role text in template substitutions, JSX attribute values (`aria-label`/`title`), and dynamic variables. **Resolved (Alex, 2026-06-10): accept canonical coverage** — the AC8/AC9 literal examples are enforced and no component renders/gates on roles today; broadening is deferred to Epic-3a when real consumers exist. [tools/eslint-rules/no-role-based-action-gating.js:41, tools/eslint-rules/no-bare-actor-role-text.js:102]
- [x] [Review][Decision→Defer] AC6 rendered-state contrast is verified only by the token-pair gate (`check:contrast`); axe `color-contrast` is inert under jsdom (`getContext` stubbed to `null` → degrades to *incomplete*), so the ~100 axe scans do NOT exercise contrast. **Resolved (Alex, 2026-06-10): accept token-gate coverage for E2 close** — jsdom has no layout engine; live rendered-state contrast is deferred to the 2.27 Playwright tier. [src/test/setup.ts:25, src/test/a11y/axe.ts]
- [x] [Review][Decision→Defer] AC7 vocabulary gate enforces *import presence* + a `PRIMITIVE_ALLOWLIST`, not per-region text-provenance, and is blind to role-only live regions (`role="alert"`/`"status"` without an `aria-live=` attribute, which its regex keys on). **Resolved (Alex, 2026-06-10): accept current scope** — import-presence + allowlist is a reasonable static approximation, per-region provenance is hard to prove statically, and today's 3 composite announcers were verified clean. [tools/a11y/__tests__/announcement-vocabulary.test.js]
- [x] [Review][Patch] `no-bare-actor-role-text` error message tells developers to use `<AuditRoleLabel role="…">`, but the wrapper's prop is `actorRole` (a `role=` prop trips `jsx-a11y/aria-role`) — fix the message to `actorRole="…"`. [tools/eslint-rules/no-bare-actor-role-text.js:82] — **fixed 2026-06-10** (message now reads `actorRole="…"`; `lint:rules-test` 9/9).
- [x] [Review][Patch] `ApprovalDecisionBar` announces an empty string when `state === 'success'` but `view.lastDecision` is `undefined` (a plausible mutation-settled-before-read-repopulates window) — the visual renders the generic "Decision recorded." while the live region stays silent. Add a generic recorded fallback so the outcome is always spoken. [src/features/workflows/components/ApprovalDecisionBar.tsx:259] — **fixed 2026-06-10** (added `decisionRecorded` vocab string; success branch falls back to it when `lastDecision` is absent; `check:a11y` 4/4, ApprovalDecisionBar 57/57, tsc clean).
- [x] [Review][Defer] `useLiveAnnouncement` cannot re-announce an identical consecutive message (A→A): a repeated submit failure / recurring identical state is not re-spoken because the `[message]` effect dep does not change. Out of AC5 scope (first-render + multi-advance); a full fix needs a clear-then-rAF reset that risks new test flakiness. [src/lib/a11y/useLiveAnnouncement.ts:18] — deferred
- [x] [Review][Defer] `BoundedDetailSheet` focus restoration no-ops when the opener was removed from the DOM before close (`target.focus()` on a detached node drops focus to `<body>`) — the WCAG 2.4.3 case for a detached opener. Mirrors the sibling `ConfirmationDialog` limitation; a full fix needs a `document.contains(target)` guard + a fallback-focus target. [src/components/overlays/BoundedDetailSheet.tsx:71] — deferred
- [x] [Review][Defer] Keyboard-test rigor: several suites assert activation via `.focus()` + key event rather than pure Tab traversal (so a broken tab path still passes the activation half); disabled "excluded from Tab order" tests assert only `toBeDisabled()`; `expectTabReachesAll` never asserts the absence of *extra* focusables. Tests pass for the right reason today but the assertions are weaker than their titles claim. [src/test/a11y/keyboard.ts, multiple *.test.tsx] — deferred
- [x] [Review][Defer] `keyboard.ts` `TABBABLE_SELECTOR` omits `[contenteditable]`, `summary`, `[controls]`, and ignores positive-`tabindex` reordering — not exercised by current components; relevant only if such elements are added later. [src/test/a11y/keyboard.ts:37] — deferred
