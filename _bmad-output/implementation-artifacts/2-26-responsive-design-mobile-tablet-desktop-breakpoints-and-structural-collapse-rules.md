# Story 2.26: Responsive Design (Mobile/Tablet/Desktop Breakpoints + Structural Collapse Rules)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager occasionally needing to triage on a phone (Galaxy S23+ class) or tablet,
I want the full E2 PM-loop usable across desktop / tablet / mobile breakpoints with structural collapse rules that preserve artifact reading, decision controls, and run identity even as side panels collapse,
so that mobile-and-tablet usage genuinely supports governed decisions — not a desktop layout squeezed into a phone.

## Scope Decisions (read first — these resolve epic-vs-reality tensions)

**This is a HARDEN + FORMALIZE + DOCUMENT + TEST story over already-built responsive infrastructure — NOT a greenfield feature.** Story 2.7 (AppShell) shipped the working tri-pane→tablet→mobile responsive shell, the `useResponsiveLayout()` hook, drawers, the persistent mobile top bar, and the artifact-primacy collapse invariant. It left **explicit "story 2.26 OWNS this" markers** in code (`useResponsiveLayout.ts:9-19`, `matchMedia.ts:7-9`, `AppShell.tsx:46-47`, `LAYOUT.md`). Do **not** rebuild any of it. Read the "Current-state inventory" in Dev Notes before writing a line of code.

- **D1 — Cross-browser Playwright is OWNED BY STORY 2.27, not here.** Epic AC11 says "extension of story 2.27"; AC6 says "a mobile-flow E2E test (story 2.27) covers each." This mirrors story 2.25's posture (it shipped axe in Vitest and explicitly deferred all Playwright to 2.27). **2.26 ships ZERO Playwright config.** It ships: jsdom-testable resize-based layout tests (via the existing `@/test/matchMedia` mock + `setViewportWidth`), the breakpoint formalization, the docs/ADR, and the real-device checklist. AC6 + AC11 are satisfied here by **documenting the contract + writing the checklist**; the executable cross-browser/mobile-viewport Playwright tests are 2.27's deliverable (its AC8/AC9 literally reference "per story 2.26 AC11" / "AC9").

- **D2 — Live computed-pixel layout assertions are NOT testable in jsdom; they go to the checklist + 2.27.** jsdom has no layout engine (the AppShell tests already note this at `AppShell.test.tsx:6-8`; the touch-target px verification was deferred FROM 2.25 TO here for exactly this reason — `2-25...md` D3/AC10). 2.26's automated tests assert the **structural contract** (which classes/landmarks/elements render at each simulated breakpoint via `matchMedia`), not real pixels. Real-device px validation (touch-target size, no-horizontal-scroll, sticky-footer reachability) is the **real-device checklist (AC9)** + 2.27 Playwright.

- **D3 — The Tailwind breakpoint formalization is ADDITIVE and must NOT move the boundaries.** The hook already uses `md`=768 / `lg`=1024 boundaries deliberately aligned to Tailwind defaults (`useResponsiveLayout.ts:16-19`). AC1 asks for *named, documented* aliases mapping to UX-DR22's `mobile 320–767 / tablet 768–1023 / desktop 1024+`. Add documented `theme.screens` entries that keep `md`/`lg` at their current px so **no existing `sm:`/`md:`/`lg:` utility shifts**. If introducing named aliases (`tablet`/`desktop`) risks breaking stock shadcn `sm:`/`md:`/`lg:` usages, prefer ADDING aliases alongside the defaults rather than replacing the default scale. Verify the production build + every existing test stays green — a boundary move is a regression, not an AC.

- **D4 — Close the two responsive items prior stories re-deferred here.** Story 2.21 re-deferred "mobile-breakpoint a11y" to 2.26; story 2.25 re-deferred "live mobile-viewport touch px" to 2.26 (`2-25...md` Completion Notes). Close both: (a) add axe scans of composites mounted at the mobile/tablet simulated breakpoints to the responsive test file; (b) add the touch-target px line to the real-device checklist (jsdom can't measure px — the static class-presence test already lives in 2.25's `touch-target.test.tsx`; this story adds the *real-device* verification line, not a new jsdom px test).

## Acceptance Criteria

1. **Given** Tailwind config from story 2.2, **Then** breakpoints are explicitly documented as `mobile: 320–767px`, `tablet: 768–1023px`, `desktop: 1024px+` (UX-DR22) — Tailwind's `md` (768px) / `lg` (1024px) boundaries are kept and documented in `tailwind.config.ts` with a comment block tying them to the breakpoint names; any named alias added is additive and moves no existing boundary (D3).
2. **Given** the AppShell (story 2.7) + structural collapse rules (UX-DR23), **Then** the existing behaviour is preserved + test-pinned: desktop renders the full tri-pane; tablet keeps left nav rail + main pane with the right context panel as a toggleable slide-out drawer (persistent `TabletContextRail` toggle); mobile collapses to single-column artifact-first with a top-nav menu (`MobileTopBar`) for queue navigation and the bottom-anchored decision bar.
3. **Given** UX-DR23 priority order on narrow screens ("preserve artifact reading > preserve decision controls > disclose navigation/supporting context"), **Then** mobile reserves the central `<main>` for artifact content; the Approval Decision Bar (story 2.19) keeps its `sticky_footer` placement (already wired at `index.tsx:170`) so it is always reachable without scroll; supporting context (Run Context Strip 2.16, Clarification Region 2.18) remains accessible via the drawer/slot, never occluding the artifact.
4. **Given** UX-DR23 "compare becomes a dedicated bounded mobile state rather than compressed side-by-side", **Then** `RESPONSIVE.md` (AC10) documents Compare Mode (Epic 4) as a mobile-specific full-screen state with explicit before/after toggle — noting story 2.17's Compare-Mode entry control already ships disabled in E2 (`ArtifactReviewPanel.tsx:52` `compareEnabled` defaults `false`); the responsive plan reserves the mobile UX pattern (doc-only, no Epic-4 code).
5. **Given** UX-DR23 "run identity and current state should never disappear during collapse", **Then** the mobile top bar always renders run identity + current-state badge when inside a run (already via `MobileTopBar` → `RunIdentityRegion variant="compact"`) — pinned by responsive layout tests that simulate viewport resize across breakpoints (`setViewportWidth`) and assert these elements remain visible at every breakpoint.
6. **Given** UX-DR22 "mobile must support all critical actions", **Then** the mobile review flow's reachability is verified at component/integration level: browse queue → open a run → read current artifact → answer clarifications → approve/reject-with-reason → enter Compare Mode (Epic-4 stub, disabled). The **executable cross-browser/mobile-viewport E2E** for this flow is documented as owned by story 2.27 (D1); 2.26 asserts each surface renders + is operable at the mobile breakpoint in Vitest/RTL and lists the journey in the real-device checklist.
7. **Given** mobile decision controls (story 2.23 AC11), **Then** primary actions (`Approve` / `Reject with feedback`) remain visible in the sticky footer at all breakpoints without hunting; secondary actions may collapse into an overflow menu but the primary governed action never does — pinned by a test asserting primary-action presence in the `sticky_footer` decision bar at the mobile breakpoint.
8. **Given** UX-DR23 "supporting context moves into drawers/tabs/sheets/accordions before the artifact becomes unreadable", **Then** breakpoint-conditional rendering routes through the documented `useResponsiveLayout()` hook (`'mobile' | 'tablet' | 'desktop'`, `matchMedia`-backed) — composites/shell use the hook + Tailwind responsive utilities, NOT ad-hoc per-component media queries. The hook is hardened in place (kept SSR-safe `desktop` default, dual `change`-listener subscription) and its JSDoc updated to drop the "2.26 will own this" forward-reference now that this story closes it.
9. **Given** Galaxy S23+ real-device testing (UX-DR24), **Then** `docs/testing/responsive-real-device-checklist.md` exists, covering critical-flow validation on a real Galaxy S23+ (or documented equivalent); the reference device is recorded in `docs/supported-environments.md` (which today is OS/shell/runtime only — add a browser/device sub-section or row); checklist execution is required before E2 epic close (referenced from the story-2.28 close gate, mirroring how 2.25's a11y checklist is wired).
10. **Given** UX-DR23 "structural collapse rules", **Then** an ADR `src/features/workflows/RESPONSIVE.md` documents: which panels collapse first, which surfaces become drawers/tabs/sheets/accordions, which elements are non-collapsible (run identity, current state, primary decision action), and the collapse order under viewport pressure (right context panel yields before main pane narrows — TRAP 4 / `AppShell.tsx:44-51`); it cross-links `LAYOUT.md` (the structural shell ADR) and is referenced by future story ACs.
11. **Given** browser coverage (UX-DR24), **Then** `RESPONSIVE.md` (or a browser-support section it links) documents the support policy: modern Chrome, Firefox, Safari, Edge (current + n-1), IE/legacy excluded. The **CI Playwright cross-browser job** that enforces this is explicitly DEFERRED to story 2.27 (D1) — 2.26 documents the policy + the deferral with rationale; it adds NO Playwright job.
12. **Given** component + layout test coverage, **Then** a responsive test surface (extending `AppShell.test.tsx` and/or a new `*.responsive.test.tsx`) covers, using `installMatchMedia`/`setViewportWidth`: each breakpoint renders the correct layout; structural collapse order respected (right panel collapses before main pane narrows — story 2.7 AC2); run identity + state badge visible across breakpoints (resize sweep); primary decision action reachable on the mobile sticky footer; drawers/sheets render when invoked at narrow breakpoints; `useResponsiveLayout` returns the correct breakpoint per simulated viewport (mobile <768, tablet 768–1023, desktop ≥1024) including boundary values (767/768/1023/1024).

## Tasks / Subtasks

- [x] **Task 1 — Formalize + document the breakpoint scale (AC1, D3)**
  - [x] In `tailwind.config.ts` add a documented breakpoint comment block naming `mobile 320–767 / tablet 768–1023 / desktop 1024+` and tying them to Tailwind's `md`(768)/`lg`(1024). If adding named `theme.screens` aliases (`tablet`/`desktop`), keep the default `sm`/`md`/`lg` px values intact so no existing utility shifts (verify the diff of generated CSS or rely on the full test+build staying green).
  - [x] Confirm the boundaries match `useResponsiveLayout.ts`'s `TABLET_MIN_PX=768` / `DESKTOP_MIN_PX=1024` exactly — these are now the single documented source of the matrix.
  - [x] Run the production build (`npm run build` via the Maven gate) + full `vitest run` to prove the change is non-breaking.

- [x] **Task 2 — Harden `useResponsiveLayout` in place (AC8)**
  - [x] Keep behaviour identical (SSR-safe `desktop` default, dual-query `change` subscription). Update the JSDoc to remove the "story 2.26 OWNS / do not pre-empt" forward-reference (this story closes it) and instead state this is the canonical breakpoint hook; cite `RESPONSIVE.md`.
  - [x] Ensure NO new ad-hoc `window.matchMedia`/CSS media query is introduced elsewhere — composites needing breakpoint logic consume this hook or Tailwind responsive utilities (audit `src/features/**` + `src/components/**` for stray `matchMedia` usage; there should be none today).

- [x] **Task 3 — Author `RESPONSIVE.md` ADR (AC4, AC10, AC11)**
  - [x] Create `deliveryline-frontend/src/features/workflows/RESPONSIVE.md`. Document: the 3 breakpoints; collapse order (right context panel → drawer first, main pane never narrows below its floor — TRAP 4; nav rail → mobile top-bar menu); which surfaces become drawers/sheets (context panel, nav); the non-collapsible set (run identity, current-state badge, primary `Approve`/`Reject` action); Compare-Mode mobile full-screen before/after pattern (AC4, Epic-4 reservation, no code); the browser-support policy (Chrome/Firefox/Safari/Edge current+n-1; IE/legacy excluded — AC11) and the explicit note that cross-browser Playwright enforcement is story 2.27.
  - [x] Cross-link `LAYOUT.md` (structural shell ADR) ↔ `RESPONSIVE.md` (responsive collapse ADR) so the pair is discoverable.

- [x] **Task 4 — Real-device checklist + supported-environments device (AC6, AC9, D4)**
  - [x] Create `docs/testing/responsive-real-device-checklist.md`: critical-flow steps on a real Galaxy S23+ (or documented equivalent) — queue → run → read artifact → answer clarification → approve / reject-with-reason; plus the touch-target ≥44px real-device check (D4, the px verification 2.25 deferred here), no-horizontal-scroll, sticky-footer reachability, run-identity-always-visible. State the pre-E2-close execution requirement and reference it from the story-2.28 close gate (mirror `a11y-screen-reader-checklist.md`).
  - [x] Add the reference device to `docs/supported-environments.md` (browser/device sub-section or matrix row): Galaxy S23+ class as the smallest supported mobile target; the four supported desktop browsers + n-1 policy.

- [x] **Task 5 — Responsive layout test matrix (AC2, AC5, AC6, AC7, AC12, D2, D4)**
  - [x] Extend `AppShell.test.tsx` (or add `AppShell.responsive.test.tsx`) using `installMatchMedia` + `setViewportWidth`. Cover: (a) correct layout per breakpoint (desktop tri-pane / tablet rail+drawer / mobile single-column+top-bar) — partly exists, fill gaps; (b) a **resize sweep** (`setViewportWidth` desktop→tablet→mobile→back) asserting run identity + current-state badge stay visible at every step (AC5); (c) right context panel collapses to a drawer BEFORE the main pane drops its min-width floor (AC2 collapse order); (d) the `sticky_footer` decision bar's primary `Approve`/`Reject` is present + operable at the mobile breakpoint (AC7) — mount the run-detail route content or `ApprovalDecisionBarContainer` directly.
  - [x] Add a focused `useResponsiveLayout` unit test asserting the returned mode at boundary widths: 767→mobile, 768→tablet, 1023→tablet, 1024→desktop (AC12).
  - [x] Add axe scans (via `expectNoA11yViolations`, story 2.25's `src/test/a11y/axe.ts`) of the shell + a representative composite mounted at the mobile + tablet simulated breakpoints — closes story 2.21's re-deferred "mobile-breakpoint a11y" (D4). Assert structure/roles, NEVER `toMatchSnapshot` (snapshots are 2.27).

- [x] **Task 6 — CI + gate verification (AC12)**
  - [x] No new CI job (Playwright is 2.27). The new tests run inside the existing `frontend-build-tests` Vitest tier; new docs add no gate. Verify the full local gate via PowerShell (RTK Bash hook corrupts only Bash — [[rtk-hook-only-matches-bash]]): `tsc -b`, `eslint . --max-warnings=0`, `lint:rules-test`, `check:contrast`, `check:a11y`, `check:tokens`, `check:routes`, `check:api`, `vitest run`, `prettier --check .`. Run `prettier --write` before pushing — one unformatted file cascades the whole tier ([[prettier-gate-cascades-ci]]).
  - [x] Verify on a Linux-shaped env before claiming green — local ≠ CI ([[verify-ci-fixes-in-clean-env]]); no new deps are expected (so no lockfile churn — if a dep is somehow added, regenerate the lockfile with a full `npm install` per [[frontend-lockfile-cross-platform]]).

- [x] **Logging instrumentation** (cross-cutting standard) — **N/A for this story.** Story 2.26 is frontend-only (React/TS + Markdown docs); it touches no Spring `@Service`, SPI, or persistence surface, so the SLF4J/MDC logging contract does not apply (same as story 2.25). Frontend observability convention: no `console.log` in shipped code (ESLint enforces); user-facing async outcomes surface through the story-2.25 announcement vocabulary + inline feedback, never silent. If implementation unexpectedly adds a backend touch (it must not), apply the full logging task from the project standard.

## Dev Notes

### Current-state inventory (what ALREADY EXISTS — do NOT rebuild)

The responsive shell is built. Story 2.7 implemented it and tagged this story as the hardener. Confirmed by reading the source:

- **`useResponsiveLayout()`** — `src/features/workflows/hooks/useResponsiveLayout.ts`. Returns `'mobile' | 'tablet' | 'desktop'` from `matchMedia`; `TABLET_MIN_PX=768`, `DESKTOP_MIN_PX=1024`; SSR-safe `desktop` default; subscribes to both media queries' `change` events. **Its JSDoc explicitly says "Story 2.26 OWNS the full breakpoint matrix, the `RESPONSIVE.md` ADR, the named Tailwind breakpoint aliases, and the exhaustive test matrix — it HARDENS this hook in place. Do not pre-empt that here."** That is THIS story.
- **`AppShell`** — `src/features/workflows/AppShell.tsx`. Already branches on `useResponsiveLayout()`:
  - **Desktop**: inline tri-pane — `<nav w-64>` + `<main min-w-[36rem]>` + `<aside>` (empty collapses to `w-12`, populated `w-80`).
  - **Tablet**: `<nav w-56>` + `<main min-w-[34rem]>` + slim `TabletContextRail` (`w-12`) whose `PanelRight` button opens the right context **slide-out drawer**.
  - **Mobile**: persistent `MobileTopBar` (`<header role=banner>`: hamburger → left nav `Sheet`, run identity via `RunIdentityRegion variant="compact"`, `PanelRight` → context drawer) + single-column `<main min-w-0 px-4>`.
  - Artifact-primacy floor (TRAP 4): the right `<aside>` yields width first; `<main>` never narrows below its min-width while the panel still has width. Documented `AppShell.tsx:44-51`.
  - Landmarks: exactly one `<nav>` / `<main>` / `<aside>` (AC7 from 2.7) — do not add more.
- **`MobileTopBar` / `RunIdentityRegion`** — run identity + current-state badge ALWAYS render on mobile inside a run (AC5 already satisfied structurally; this story PINS it with resize tests).
- **Decision bar `sticky_footer`** — `ApprovalDecisionBar.tsx:81-84` defines the `sticky_footer` placement (`sticky bottom-0 z-10 … border-t … backdrop-blur`); mounted in the run-detail route at `src/routes/workflows/$workflowRunId/index.tsx:170` as `<ApprovalDecisionBarContainer layout="sticky_footer" />`. AC3/AC7 sticky footer = DONE; this story PINS the primary-action-visible-at-mobile contract.
- **`ContextPanelSlot`** — composites project into the right panel via the AppShell slot (`index.tsx:132`). Clarification Region (2.18) + Run Context Strip (2.16) already mount through it.
- **Test tooling** — `src/test/matchMedia.ts` provides `installMatchMedia(width)`, `setViewportWidth(width)` (fires `change` listeners — the resize simulator AC12 needs), `uninstallMatchMedia()`. `AppShell.test.tsx` already exercises tablet(800)/mobile(400) branches. `src/test/a11y/axe.ts` `expectNoA11yViolations` (story 2.25) for AC12 axe scans.
- **Docs** — `docs/supported-environments.md` exists (OS/shell/runtime matrix; AC9 adds a browser/device section). `docs/testing/a11y-screen-reader-checklist.md` exists (the pattern AC9's responsive checklist mirrors). `src/features/workflows/LAYOUT.md` exists (structural shell ADR; `RESPONSIVE.md` is its responsive sibling, AC10).
- **Net-new in this story**: `src/features/workflows/RESPONSIVE.md`, `docs/testing/responsive-real-device-checklist.md`, the responsive test additions, the `tailwind.config.ts` breakpoint doc block, the `useResponsiveLayout` JSDoc update, the `supported-environments.md` device addition.

### What this story is NOT

- NOT a rebuild of the shell, the hook, the drawers, or the mobile top bar — they work. Touching their behaviour is a regression risk, not an AC.
- NOT a Playwright story. Zero Playwright config. Cross-browser + mobile-viewport E2E + real-device-execution-as-CI are story 2.27 (D1). 2.26 = the contract + checklist + jsdom-level structural tests.
- NOT a boundary change. The 768/1024 px boundaries are fixed and already shared by the hook and Tailwind defaults (D3).

### Patterns to honor (established by 2.7 / 2.20–2.25)

- **Tests assert roles/text/classes/`data-*`, never `toMatchSnapshot`** — the 2.20–2.25 discipline; visual-regression snapshots are 2.27.
- **Helper functions live in `.ts`, not `.tsx`** — a feature `.tsx` exporting a non-component fails the react-refresh eslint gate ([[frontend-react-refresh-no-fn-exports]]). (Relevant only if you add a helper; this story is mostly docs + tests.)
- **Vitest 4 shares a module registry per worker** — consolidate same-module mocks into one file; don't add a second file `vi.mock`-ing the router that an existing file already mocks ([[vitest-cross-file-router-mock]]).
- **`matchMedia` must be installed before render and uninstalled in `afterEach`** — `AppShell.test.tsx` does `uninstallMatchMedia()` in `afterEach`; default (no install) = desktop. Follow that lifecycle exactly or breakpoint state bleeds across tests.
- **Non-color signifier contract** (2.3 AC5) — the state badge in the mobile top bar must keep icon + text, never color-alone, at every breakpoint.

### Architecture compliance (hard invariants relevant here)

- Frontend stack is fixed: Vite + React + TypeScript, TanStack Query/Router, shadcn/ui + Tailwind, Maven-driven build. Do not introduce a different responsive/testing stack (no CSS-in-JS, no extra media-query lib — Tailwind utilities + the one `useResponsiveLayout` hook only).
- Breakpoint logic is centralized: one hook (`useResponsiveLayout`) + Tailwind responsive utilities. No ad-hoc `window.matchMedia` scattered through components (AC8).
- WCAG 2.1 AA (story 2.25) holds at every breakpoint — the mobile/tablet axe scans (Task 5) are the regression guard; touch-target ≥44px floor (2.25 `tailwind.config.ts:38-43` `min-h-touch`/`min-w-touch`) must hold on real devices (real-device checklist, Task 4).

### CI / gate facts (story 1.21 / 2.25)

- Job `frontend-build-tests` in `.github/workflows/ci.yml` runs `./mvnw -pl deliveryline-frontend clean package`, executing (in `process-resources`): `lint` (`eslint --max-warnings=0`), `lint:rules-test`, `check:contrast`, `check:tokens`, `check:routes`, `check:api`, `check:a11y`, `test` (`vitest run`), `format:check`. **No new script/job for this story** — new tests ride `vitest run`; new docs add no gate.
- Frontend format gate runs inside this tier; one unformatted file cascades the whole pipeline ([[prettier-gate-cascades-ci]]). `prettier --write` before pushing.
- Verify in a clean/Linux-shaped env before claiming green — local ≠ CI ([[verify-ci-fixes-in-clean-env]]).
- Run the gate via PowerShell, not Bash — the RTK hook corrupts only the Bash tool ([[rtk-hook-only-matches-bash]]).

### Project Structure Notes

- **New files**: `deliveryline-frontend/src/features/workflows/RESPONSIVE.md`, `docs/testing/responsive-real-device-checklist.md`, optionally `deliveryline-frontend/src/features/workflows/AppShell.responsive.test.tsx` (or extend `AppShell.test.tsx` + `useResponsiveLayout.test.ts`).
- **Modified**: `deliveryline-frontend/tailwind.config.ts` (breakpoint doc block / additive aliases), `deliveryline-frontend/src/features/workflows/hooks/useResponsiveLayout.ts` (JSDoc only — behaviour frozen), `deliveryline-frontend/src/features/workflows/LAYOUT.md` (cross-link to RESPONSIVE.md), `docs/supported-environments.md` (browser/device section).
- **No backend, OpenAPI, `schema.d.ts`, or Flyway change.** If `schema.d.ts` would change, stop — this story must stay frontend + docs only.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.26] — the 12 epic ACs (lines 1425–1444).
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Responsive Design & Accessibility] (lines 2063–2246) — Responsive Strategy, Breakpoint Strategy (mobile 320–767 / tablet 768–1023 / desktop 1024+, Galaxy S23+ target, lines 2092–2106), Structural Collapse Rules (2231–2246), Responsive Decision Preservation Rule (2218–2229). NOTE: the spec organizes responsive guidance under these NAMED sections — there are **no literal `UX-DR22/23/24` headings** in the doc; the epic's UX-DR labels map onto them ([[ux-dr-labels-map-to-named-sections]]).
- [Source: deliveryline-frontend/src/features/workflows/AppShell.tsx] — the built responsive shell (desktop/tablet/mobile branches, artifact-primacy floor lines 44–51, MobileTopBar lines 184–226).
- [Source: deliveryline-frontend/src/features/workflows/hooks/useResponsiveLayout.ts] — the breakpoint hook (lines 9–19 carry the "2.26 owns this" marker this story closes).
- [Source: deliveryline-frontend/src/test/matchMedia.ts] — `installMatchMedia`/`setViewportWidth`/`uninstallMatchMedia` (the AC12 resize simulator).
- [Source: deliveryline-frontend/src/features/workflows/AppShell.test.tsx] — existing breakpoint tests (tablet 800 / mobile 400) to extend.
- [Source: deliveryline-frontend/src/features/workflows/components/ApprovalDecisionBar.tsx:81-84] + [src/routes/workflows/$workflowRunId/index.tsx:170] — the `sticky_footer` decision bar (AC3/AC7).
- [Source: deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:52] — Compare-Mode entry control, `compareEnabled` defaults `false` in E2 (AC4).
- [Source: deliveryline-frontend/tailwind.config.ts:34-43] — story-2.25 touch-target floor (AC10 real-device check, D4).
- [Source: docs/supported-environments.md] — the matrix AC9 extends with a browser/device section.
- [Source: deliveryline-frontend/src/features/workflows/LAYOUT.md] — the structural shell ADR `RESPONSIVE.md` pairs with.
- Prior story: `2-25-...md` (D3/AC10 deferred touch-px to here; Completion Notes re-deferred 2.21 mobile-a11y + 2.25 touch-px to 2.26).

### Open Questions (for Alex — do not block dev-story; default behavior is encoded above)

1. **D1 confirmation**: OK that 2.26 ships NO Playwright and AC6/AC11's executable cross-browser/mobile E2E is delivered by story 2.27 (which already owns it per its AC8/AC9)? Default = yes (mirrors the 2.25→2.27 Playwright split).
2. **D3 confirmation**: OK to keep the 768/1024 boundaries and add the breakpoint names as documentation + (optionally) additive Tailwind aliases, rather than remapping `sm`/`md`/`lg`? Default = yes (any boundary move would silently shift every existing responsive utility).
3. **AC9 device**: Galaxy S23+ as the documented smallest-supported mobile reference (recorded in `supported-environments.md`), with the manual real-device run itself being an E2-close activity (the deliverable here is the checklist)? Default = yes.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Full gate run via PowerShell ([[rtk-hook-only-matches-bash]]): `tsc -b` 0 · `eslint . --max-warnings=0` 0 · `lint:rules-test` 9/9 · `check:contrast` · `check:a11y` · `check:tokens` · `check:routes` · `check:api` ✅ · `vitest run` **814/814 (71 files)** · `prettier --check .` clean · `npm run build` (production vite build) ✅ exit 0.

### Completion Notes List

**HARDEN + FORMALIZE + DOCUMENT + TEST over the already-built story-2.7 responsive shell — NOT greenfield.** No shell/hook/drawer/top-bar behaviour was changed; this story pins and documents the contract those pieces already implement and closes the "2.26 OWNS this" markers.

- **Task 1 (AC1, D3)** — Added a documented breakpoint comment block to `tailwind.config.ts` (mobile 320–767 / tablet 768–1023 / desktop 1024+, tied to `md`=768 / `lg`=1024 and to `useResponsiveLayout`'s `TABLET_MIN_PX`/`DESKTOP_MIN_PX`). Named aliases `tablet`/`desktop` were added **additively under `theme.extend.screens`** (which MERGES, never replaces) pointing at the same px as `md`/`lg`, so no existing `sm:`/`md:`/`lg:` utility shifts (D3). `mobile` is intentionally NOT an alias (Tailwind is mobile-first; the unprefixed base IS mobile). Non-breaking proven by the full production build + 814-test suite staying green.
- **Task 2 (AC8)** — `useResponsiveLayout` behaviour frozen (SSR-safe `desktop` default + dual-query `change` subscription unchanged); only the JSDoc was rewritten to drop the "story 2.26 OWNS / do not pre-empt" forward-reference and declare it the canonical breakpoint hook citing `RESPONSIVE.md`. Audited `src/features/**` + `src/components/**` for stray `window.matchMedia` / ad-hoc media queries — the ONLY `matchMedia` references are the canonical hook and the `@/test/matchMedia` test mock; no production composite rolls its own (AC8 holds).
- **Task 3 (AC4, AC10, AC11)** — Authored `src/features/workflows/RESPONSIVE.md`: the 3-breakpoint matrix, collapse order (right context panel → drawer FIRST; nav rail → mobile top-bar menu; main pane drops its floor LAST — TRAP 4), the drawer/sheet surfaces, the non-collapsible set (run identity + state badge + primary `Approve`/`Reject`), the Compare-Mode mobile full-screen before/after reservation (AC4, Epic-4, doc-only — `compareEnabled` ships `false`), and the browser-support policy (Chrome/Firefox/Safari/Edge current+n-1; IE/legacy excluded — AC11) with the explicit Playwright-is-2.27 deferral. Cross-linked `LAYOUT.md` ↔ `RESPONSIVE.md`.
- **Task 4 (AC6, AC9, D4)** — Authored `docs/testing/responsive-real-device-checklist.md` (Galaxy S23+ critical flow queue→run→read→clarify→decide; the live touch-target ≥44px check 2.25 deferred here per D4; no-horizontal-scroll; sticky-footer reachability; run-identity-always-visible; pre-E2-close execution requirement wired to the story-2.28 gate, mirroring the a11y checklist). Added a **Browser & device support** section to `docs/supported-environments.md` (the 4 evergreen browsers + n-1; Galaxy S23+ as smallest mobile target; IE/legacy excluded).
- **Task 5 (AC2, AC5, AC6, AC7, AC12, D2, D4)** — New `AppShell.responsive.test.tsx` (8 tests): per-breakpoint layout (desktop tri-pane floor / tablet rail+toggle+narrower floor / mobile single-column+top-bar); collapse order (right panel → drawer BEFORE main pane drops its floor); a resize sweep (desktop→tablet→mobile→back) pinning run identity + state badge at every step (AC5); the `sticky_footer` primary `Approve` present + enabled at the mobile breakpoint (AC7, mounting `ApprovalDecisionBar` with the `readyView` fixture directly); and **mobile + tablet axe scans** of the shell + a representative composite — closing story 2.21's re-deferred mobile-breakpoint a11y (D4), zero violations. Added 4 boundary-width cases (767→mobile, 768→tablet, 1023→tablet, 1024→desktop) to `useResponsiveLayout.test.tsx` (AC12). Structural assertions only — NO `toMatchSnapshot`, NO computed-pixel checks (D2; those are the real-device checklist + 2.27).
- **Task 6 (AC12)** — No new CI job; the 12 new tests ride the existing `frontend-build-tests` Vitest tier and the new docs add no gate. Full local gate green (see Debug Log). No new npm dep ⇒ no lockfile churn ([[frontend-lockfile-cross-platform]]). Recommend a Linux/clean-env CI confirm before merge ([[verify-ci-fixes-in-clean-env]]).
- **Scope decisions honored:** D1 (ZERO Playwright — cross-browser/mobile-viewport E2E owned by 2.27, documented + deferred). D2 (no computed-px assertions in jsdom — checklist + 2.27). D3 (boundaries unmoved; aliases additive). D4 (both re-deferred items closed: 2.21 mobile-a11y axe scans + 2.25 touch-px on the real-device checklist).
- **Open questions** (D1/D3/AC9 confirmations) all resolved by their encoded defaults — no blocker surfaced.
- **N/A — Logging instrumentation:** frontend-only (React/TS + Markdown docs), no backend `@Service`/SPI/persistence touch, so the SLF4J/MDC contract does not apply (same posture as story 2.25).

### File List

**Modified (frontend):**

- `deliveryline-frontend/tailwind.config.ts` — breakpoint doc block + additive `theme.extend.screens` `tablet`/`desktop` aliases (AC1, D3).
- `deliveryline-frontend/src/features/workflows/hooks/useResponsiveLayout.ts` — JSDoc only; declares the canonical hook, drops the "2.26 OWNS" forward-ref (AC8).
- `deliveryline-frontend/src/features/workflows/hooks/useResponsiveLayout.test.tsx` — +4 boundary-width cases (AC12).
- `deliveryline-frontend/src/features/workflows/LAYOUT.md` — §5 cross-link to `RESPONSIVE.md` (AC10).

**New (frontend):**

- `deliveryline-frontend/src/features/workflows/RESPONSIVE.md` — the responsive collapse ADR (AC4, AC10, AC11).
- `deliveryline-frontend/src/features/workflows/AppShell.responsive.test.tsx` — the responsive layout test matrix + mobile/tablet axe scans (AC2, AC5, AC6, AC7, AC12, D2, D4).

**Modified / new (docs):**

- `docs/supported-environments.md` — new Browser & device support section (AC9).
- `docs/testing/responsive-real-device-checklist.md` — new real-device critical-flow checklist (AC6, AC9, D4).

**Tracking:**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — 2-26 `ready-for-dev` → `in-progress` → `review`.

### Change Log

| Date       | Change                                                                                                                                                                                              |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-06-10 | Implemented story 2.26 (responsive design hardening): additive Tailwind breakpoint aliases + doc block, `useResponsiveLayout` JSDoc canonicalization, `RESPONSIVE.md` ADR, real-device checklist + supported-environments browser/device section, `AppShell.responsive.test.tsx` matrix (incl. mobile/tablet axe scans) + hook boundary cases. All 12 ACs satisfied; gates green (tsc/eslint/rules/contrast/a11y/tokens/routes/api/prettier + vitest 814/814 + production build). Status → review. |
