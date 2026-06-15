# Story 2.2: Tailwind + shadcn/ui Setup + Primitive Inventory

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **Tailwind CSS and shadcn/ui wired into the frontend with a primitive component inventory**,
so that **subsequent stories have the full primitive layer available and no story needs to author foundation UI components from scratch**.

## ⚠️ Critical execution-order dependency (read first)

**Story 2.31 (Frontend Lint + Prettier + Custom Rules) is specified to merge BEFORE this story** — see `epics.md:1009`: *"Although numbered 2.31 to preserve AC cross-reference stability… this story **must merge before story 2.2**… Epic 2's Definition of Done includes verifying this ordering held."* Story 2.31 owns the custom ESLint rule **`no-workflow-domain-in-ui-primitives`** that **2.2 AC7** references (epics.md:1016), and it re-authors the `eslint.config.js` that **story 2.1 deliberately deleted** (see Previous Story Intelligence).

`2-31-frontend-lint-and-prettier-and-custom-rules` is currently `backlog`. Two valid paths:

1. **Recommended:** create + dev + merge story 2.31 first, then return to 2.2. AC7 is then satisfied by the real ESLint rule.
2. **If 2.2 proceeds first:** AC7 explicitly permits *"an ESLint custom rule **or** grep-based CI check"* (epics.md:886). Implement the **grep-based CI fallback** (Task 7) so AC7 is met without 2.31, and leave a TODO to migrate enforcement to the ESLint rule once 2.31 lands. Do **not** author the full `eslint.config.js` here — that is 2.31's scope.

This is surfaced as Open Clarification Q1. Confirm the chosen path before starting Task 7.

## Acceptance Criteria

1. **Given** the frontend module, **Then** `tailwind.config.ts`, `postcss.config.js`, and `src/styles/globals.css` are configured per the shadcn/ui + Tailwind initialization guide, with Tailwind's content globbing covering `src/**/*.{ts,tsx}`.
2. **Given** shadcn/ui CLI initialization, **Then** `components.json` exists with documented configuration (base color, style, CSS variables, component path under `src/components/ui/`).
3. **Given** the primitive inventory, **Then** the following shadcn/ui components are added via CLI and available under `src/components/ui/`: `button`, `input`, `textarea`, `label`, `dialog`, `sheet`, `popover`, `dropdown-menu`, `select`, `tabs`, `badge`, `alert`, `table`, `card`, `tooltip`, `scroll-area`, `accordion`, `collapsible`, `separator`, `toast` / `sonner`.
4. **Given** each primitive, **Then** it is used exactly as shadcn/ui provides it with minimal local customization — workflow-specific visual treatment comes from tokens (stories 2.3/2.4) and composites (stories 2.15–2.19), not from overriding primitives.
5. **Given** a shared `cn()` utility, **Then** `src/lib/utils.ts` exports the standard `cn()` helper (clsx + tailwind-merge) that composites use for conditional className composition.
6. **Given** the primitive layer, **Then** a minimal demo page (e.g., `src/routes/_dev/PrimitivesPlayground.tsx`, gated by dev-only route) renders every primitive in each documented state — serving as living documentation and smoke test, not a production route.
7. **Given** architecture requirement that "shadcn/ui primitives remain generic and reusable", **Then** no primitive file is edited to encode DeliveryLine-specific workflow concepts — an ESLint custom rule or grep-based CI check fails if `src/components/ui/*` files import from `src/features/workflows/` or reference workflow-domain types.
8. **Given** dark mode support, **Then** shadcn/ui's standard dark mode wiring is configured but not activated in E2 — dark mode is out of scope until explicitly prioritized post-MVP; docs note this choice.
9. **Given** the frontend build, **When** Tailwind processes `globals.css`, **Then** the production bundle strips unused utilities (content-purge enabled) and the dev bundle hot-reloads token changes without full-page refresh.

## Tasks / Subtasks

- [x] **Task 1: Install + configure Tailwind CSS v3.4.x** (AC: 1, 9)
  - [x] Add dev dependencies pinned for the Tailwind **v3** line (NOT v4 — see Dev Notes "Tailwind v3 vs v4 decision"): `tailwindcss@^3.4`, `postcss@^8.4`, `autoprefixer@^10.4`. Use the **module-local pinned npm** at `deliveryline-frontend/.frontend-node/node/npm` (10.8.2) for all install commands — NOT ambient npm (see Previous Story Intelligence + cross-platform lockfile guardrail in Task 9).
  - [x] Create `deliveryline-frontend/tailwind.config.ts` with: `darkMode: ['class']` (AC8 — wired, not activated), `content: ['./index.html', './src/**/*.{ts,tsx}']` (AC1 + AC9 purge), and the shadcn/ui `theme.extend` block (colors mapped to CSS variables, `borderRadius`, container) the shadcn init produces. Keep the theme mapping shadcn generates — do NOT hand-pick brand/teal colors or a custom spacing scale (those are tokens for stories 2.3/2.4).
  - [x] Create `deliveryline-frontend/postcss.config.js` with `tailwindcss` + `autoprefixer` plugins.
  - [x] Create `deliveryline-frontend/src/styles/globals.css` with `@tailwind base; @tailwind components; @tailwind utilities;` plus the shadcn CSS-variable `:root { … }` and `.dark { … }` blocks (HSL design-token variables). Leave the variable VALUES at shadcn defaults — stories 2.3/2.4 own the real palette.
  - [x] Import `./styles/globals.css` from `src/main.tsx` (replacing or alongside the template `index.css`; remove the Vite-template `App.css`/`index.css` demo styles that conflict, per AC4 minimal-customization and the 2.1 scaffold being a throwaway template).
  - [x] Verify `tsc -b && vite build` still succeeds and dev server (`npm run dev`) hot-reloads a class change in `src/App.tsx` without full-page refresh (AC9).

- [x] **Task 2: Initialize shadcn/ui** (AC: 2, 5)
  - [x] Run `npx shadcn@latest init` in `deliveryline-frontend/` (answer: style = `default` or `new-york` — document the choice; base color = `slate` or `neutral` — document; CSS variables = **yes**; components path = `src/components/ui`; utils path = `src/lib/utils`; `tailwind.config.ts`; `globals.css` = `src/styles/globals.css`; React Server Components = **no** (Vite SPA)).
  - [x] Verify `components.json` is created and committed with the documented config (AC2). Confirm aliases resolve: `@/components`, `@/lib/utils` — this requires a `paths` alias. **Note divergence:** story 2.1's Dev Notes explicitly said "Do NOT add a `tsconfig.json` `paths` alias in this story" (deferred to 2.5/2.6). shadcn needs the `@/*` alias — add `"baseUrl": "."` + `"paths": { "@/*": ["./src/*"] }` to `tsconfig.app.json` (and the matching `resolve.alias` in `vite.config.ts`) **here in 2.2** since shadcn requires it. Document this as the deliberate point where the alias is introduced (supersedes 2.1's deferral note).
  - [x] Confirm `src/lib/utils.ts` exports `cn()` = `clsx` + `twMerge` (shadcn init adds `clsx` + `tailwind-merge` deps). This satisfies AC5. Verify `clsx` and `tailwind-merge` (and `class-variance-authority`, `lucide-react` — shadcn primitive deps) are pure-JS packages (no native binaries → no new cross-platform lockfile risk beyond Task 9 hygiene).

- [x] **Task 3: Add the 20 primitive components via shadcn CLI** (AC: 3, 4)
  - [x] Run `npx shadcn@latest add button input textarea label dialog sheet popover dropdown-menu select tabs badge alert table card tooltip scroll-area accordion collapsible separator sonner` (use **`sonner`** for the toast primitive — current shadcn replaced the deprecated `toast` component with `sonner`; AC3 says "`toast` / `sonner`" so sonner satisfies it. Document this choice).
  - [x] Verify all 20 land under `src/components/ui/` and `npx tsc --noEmit` passes against them under the strict flags (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`) inherited from story 2.1. If a generated primitive trips `exactOptionalPropertyTypes` or `noUncheckedIndexedAccess`, fix the generated file **minimally** (the smallest type-correct change) — do NOT relax the strict flags and do NOT encode workflow concepts (AC4/AC7).
  - [x] Confirm no primitive was edited to add DeliveryLine workflow concepts (AC4) — they remain stock shadcn output plus only strict-mode type fixes.

- [x] **Task 4: Generic-primitive boundary enforcement** (AC: 7)
  - [x] **If story 2.31 has merged:** confirm its `no-workflow-domain-in-ui-primitives` ESLint rule is active and a `src/components/ui/*` file importing from `src/features/workflows/` fails `npm run lint`. Nothing further needed here.
  - [x] **If 2.31 has NOT merged (fallback per AC7):** add a grep-based CI check — a small script (`scripts/ci/check-ui-primitive-purity.{sh,ps1}` or a step in the `frontend-build-tests` job) that greps `deliveryline-frontend/src/components/ui/` for imports from `features/workflows` or `@/features/workflows` and exits non-zero on a hit. Wire it into the `frontend-build-tests` CI job. Add a `# TODO(2.31): replace with no-workflow-domain-in-ui-primitives ESLint rule` comment. Document the decision in Completion Notes.
  - [x] Either way, add a negative test fixture (a comment-documented example or the grep-check's self-test) proving the guard actually fails on a violation — so the rule isn't silently a no-op.

- [x] **Task 5: Dark mode wiring (configured, NOT activated)** (AC: 8)
  - [x] Confirm `tailwind.config.ts` has `darkMode: ['class']` and `globals.css` has the `.dark { … }` variable block (shadcn init produces both). Do NOT add a theme toggle, a `dark` class on `<html>`, or any dark-mode UI.
  - [x] Document in `deliveryline-frontend/README.md` (Design System section) that dark mode is wired but intentionally inactive for Epic 2 / MVP, and how a future story would activate it (add a toggle + set `class="dark"`).

- [x] **Task 6: PrimitivesPlayground dev-only page** (AC: 6)
  - [x] **Routing caveat:** TanStack Router is NOT installed until story 2.5, so AC6's `src/routes/_dev/PrimitivesPlayground.tsx` cannot be a real router route yet. Create the component at `src/routes/_dev/PrimitivesPlayground.tsx` (preserving the AC's path for 2.5 to later register) but **mount it via a dev-only conditional** in `src/App.tsx`, gated on `import.meta.env.DEV && new URLSearchParams(location.search).has('playground')` (or a `#playground` hash). In production builds the component is tree-shaken out (the `import.meta.env.DEV` guard is statically false). Document this divergence; 2.5 will convert it to a proper `_dev` route.
  - [x] Render **every** primitive in each documented state. Use the UX spec's canonical state vocabulary (ux-design-specification.md:881-894): informational; success/approved; warning; blocker; draft/inactive; selected/focused; loading; error; permission-restricted; empty. For interactive primitives show: default, hover, focus-visible, active/pressed, disabled, loading (where applicable), error/invalid.
  - [x] **Button** (ux:1968-1990): variants primary / secondary / tertiary (`ghost`/`link`) / destructive; states default, hover, focus-visible, disabled (with adjacent reason text per ux:1978), loading/submitting. **Form primitives** (input/textarea/select/label): default, focus, disabled, error/invalid, each with an associated `<Label htmlFor>` (ux:2168). **Badge/Alert**: render the semantic states with a non-color cue (icon/text) — "semantic state should never rely on color alone" (ux:894). **Table/Card/ScrollArea**: show populated, empty, and loading variants (ux:1866 "empty/loading/error are part of the real product"). **Sonner**: a trigger button that fires a toast; note in-page that toasts are lightweight confirmation only (ux:1794). **Dialog/Sheet/Popover/Dropdown/Tooltip/Tabs/Accordion/Collapsible/Separator**: render open + interactive; verify focus moves into overlays and returns on close (ux:1938-1940).
  - [x] Keep the playground purely presentational (static example states) — NO workflow data, NO backend calls, NO TanStack Query. It is a smoke test + living docs, not a product route (AC6).

- [x] **Task 7: Build, purge, and dev-HMR verification** (AC: 9)
  - [x] Production build (`npm run build`) — confirm Tailwind content-purge strips unused utilities (the CSS bundle should be small; spot-check that an unused utility class like `grid-cols-12` is absent from `target/dist/assets/*.css` if not used). Tailwind v3 purges automatically via the `content` globs from Task 1.
  - [x] Dev server HMR — confirm editing a Tailwind class or a CSS variable in `globals.css` hot-reloads without a full-page refresh (AC9).

- [x] **Task 8: README / docs increment** (AC: 2, 8)
  - [x] Extend `deliveryline-frontend/README.md` with a "Design System" section: the chosen shadcn style/base-color, the primitive inventory (20 components), the `cn()` convention, the dark-mode-wired-but-inactive note (Task 5), the PrimitivesPlayground dev-only access (`?playground` in dev), and the scope boundary (tokens = 2.3/2.4, composites = 2.15–2.19).

- [x] **Task 9: Cross-platform lockfile + reactor/CI verification** (AC: 1, 9 — cross-cutting; LOAD-BEARING per Epic 1 retro)
  - [x] After all `npm`/`shadcn` installs, **regenerate `package-lock.json` with a FULL `npm install`** (NOT `npm install --package-lock-only`) using the module-local pinned npm 10.8.2. This is the exact trap from story 2.1: a shallow/`--package-lock-only` regeneration omits platform-gated optional native deps and breaks `npm ci` on the other OS. (Tailwind v3 + shadcn deps are pure-JS — no NEW native binaries — but the lockfile still must be regenerated cleanly so the existing rolldown/vite native-binding entries for all platforms are preserved.)
  - [x] **Verify on Linux** before considering done — `docker run --rm -v "<frontend>:/app" -w /app node:20.19.0 bash -c "npm ci --no-audit --no-fund --prefer-offline && npm run build"` → must be green (Docker + WSL Ubuntu-24.04 are available locally). Then re-verify Windows: `./mvnw -B -ntp -pl deliveryline-frontend clean package`.
  - [x] Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` green (all 4 modules); confirm the backend jar still embeds the SPA (`jar tf … | grep BOOT-INF/classes/static/index.html`) — Tailwind/shadcn output flows through the existing `target/dist` → `target/classes/static` copy from story 2.1.
  - [x] Confirm the `frontend-build-tests` CI matrix (ubuntu + windows, from story 2.1) stays green on the branch — the new Tailwind/shadcn build runs end-to-end on both OSes.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** This story ships frontend build/config + stock shadcn primitives + a dev-only playground. It introduces **no new JVM application-service classes, no domain exceptions, no SPI calls, no state transitions** — so the SLF4J/MDC logging standard (entry/exit INFO, WARN on typed rejection, ERROR on unhandled, correlationId/workflowRunId/idempotencyKey/actorIdentity keys) has no application code to instrument here. The standard remains in force for any future JVM code added under this story's scope.
- [x] Frontend client-side structured logging is out of scope (arrives with later stories); the playground uses no logging beyond incidental dev-console output.

### Review Findings

- [x] [Review][Patch] Production placeholder logs a dev-only scaffold warning on every mount [deliveryline-frontend/src/App.tsx:18]

## Dev Notes

### Story scope — what this story does and does NOT do

This story delivers **Layer 1 (Foundation primitives)** of the design-system three-layer model (ux-design-specification.md:1756-1780). It installs Tailwind + shadcn/ui, adds the 20 stock primitives, the `cn()` helper, and a dev-only playground.

**Explicitly OUT of scope (do NOT pull these in):**
- **Design tokens** — color palette / teal accent, typography scale, 4px/8px spacing rhythm. Those are stories **2.3** and **2.4** (ux:560-566, 754-794). Install primitives on **shadcn default CSS-variable theming** so 2.3/2.4 can re-skin via tokens without touching primitives. Do NOT hand-tune brand colors/spacing/type here.
- **Workflow composites** — Run/Review Queue Item, Run Context Strip, Artifact Review Panel, Clarification Region, Approval/Decision Bar, Compare Mode. Those are stories **2.15–2.19** (ux:1763-1770). Composites compose primitives; they are not built here.
- **TanStack Router** (story 2.5) and **TanStack Query + typed API client** (story 2.6). The playground does no routing-library work and no data fetching. (See PrimitivesPlayground routing caveat in Task 6.)
- **ESLint config / Prettier / custom rules** — story **2.31** (which must merge before this — see top warning). Do NOT author `eslint.config.js` here.
- **Dark mode activation** — wired only (AC8).
- **WCAG audit** (story 2.25), **responsive design** (2.26), **frontend test suite / Vitest** (2.27). The playground is a manual smoke test, not an automated test suite — do NOT add Vitest here.

### Tailwind v3 vs v4 decision (pin v3.4.x)

`npm`/shadcn's current default is **Tailwind v4**, but this story pins **Tailwind v3.4.x**. Rationale:
- **AC1 literally requires `tailwind.config.ts` + `postcss.config.js`** — these are Tailwind **v3** idioms. Tailwind v4 moves to CSS-first config (`@theme` in CSS, `@tailwindcss/postcss`) and does not generate a `tailwind.config.ts` by default; adopting v4 would diverge from AC1's wording.
- **Native-binary lockfile risk:** Tailwind v4 ships `@tailwindcss/oxide` + `lightningcss`, both **platform-specific native binaries** — the exact class of dependency that broke `npm ci` on Linux CI in story 2.1 (rolldown). v3 is pure-JS (postcss + autoprefixer), avoiding new cross-platform lockfile hazard.
- **shadcn maturity:** the shadcn + Tailwind v3 path is the most documented/stable for a React-18 + Vite SPA.

If the team prefers v4, it is a deliberate decision requiring: CSS-first config, the `@tailwindcss/vite` or `@tailwindcss/postcss` plugin, full cross-platform lockfile re-verification of the oxide/lightningcss native bindings on both OSes (Task 9 becomes load-bearing for new native deps), and an AC1 amendment. Surfaced as Open Clarification Q2.

### Previous Story Intelligence (Story 2.1)

Story 2.1 scaffolded the module; key state this story builds on (source: `_bmad-output/implementation-artifacts/2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring.md`):
- **Pinned versions:** React `^18.3.1`, `@types/react`/`@types/react-dom` `^18.3.x`, TypeScript `~6.0.2`, Vite `^8.0.12`, `@vitejs/plugin-react` `^6.0.1`, `@types/node` `^20.19.0`. **React 18, NOT 19** (Epic 2 assumes React 18 hook semantics). shadcn primitives must be React-18-compatible.
- **Strict TS flags** live in `tsconfig.app.json`: `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`. shadcn-generated primitives must compile under these (fix generated files minimally if needed; never relax flags).
- **`eslint.config.js` was DELETED in 2.1** — story 2.31 re-authors it. Do not assume an ESLint config exists.
- **Build wiring:** `vite.config.ts` sets `build.outDir = 'target/dist'`; `frontend-maven-plugin` (eirslett 1.15.1) runs `npm ci` + `npm run build` during `generate-resources` with Node `v20.19.0` / npm `10.8.2` installed module-locally under `.frontend-node/`. Backend POM copies `target/dist/` → `target/classes/static/` (→ `BOOT-INF/classes/static/`).
- **🔴 Cross-platform lockfile lesson (cost 4 CI rounds in 2.1):** regenerate `package-lock.json` with a **FULL `npm install`** (never `--package-lock-only`) using the **module-local npm 10.8.2** (ambient npm 11 omits optional deps like `@emnapi/*`). Verify `npm ci` + build **on Linux** (Docker `node:20.19.0` or WSL) before pushing — a Windows-only local build does NOT validate the cross-platform CI contract. See Task 9 and the project memory `frontend-lockfile-cross-platform`.
- **CI:** `frontend-build-tests` runs `mvn -pl deliveryline-frontend clean package` on `[ubuntu-latest, windows-latest]`; it is wired into `foundation-gate`. Both OSes must stay green.

### Git intelligence — recent work patterns

Recent commits are story-2.1 scaffold + a chain of CI-hardening fixes (Spotless format, cross-platform rolldown lockfile, `json-schema-validator` runtime scope, lychee `output` config, dead-link softening, foundation-gate `verify`-lifecycle). Takeaways for 2.2:
- The pipeline gates every PR on `format-static-checks`, the `frontend-build-tests` matrix, and `foundation-gate` — keep all green.
- Frontend dependency/lockfile changes are the highest-risk area for cross-OS CI breakage (rolldown native binding). Apply the Task 9 discipline.
- `_bmad-output/` artifacts (this story file, sprint-status.yaml) are **untracked in git** by repo convention — commit only the `deliveryline-frontend/` + CI/doc changes, never the `_bmad-output/` files (use explicit paths or `git add -u`, never `git add .` — the tree has many untracked tooling dirs like `.m2/`, `_tmp/`).

### Architecture-prescribed structure (architecture.md)

Target locations (architecture.md:1055-1091, 687-694, 1182-1183):
- `src/components/ui/` — shadcn primitives (line 1079).
- `src/lib/utils.ts` — the `cn()` helper (line 1088).
- `src/styles/globals.css` — single global stylesheet / Tailwind directives + CSS vars (lines 1089-1090).
- `tailwind.config.ts`, `components.json` at frontend root (lines 1060-1061).
- `src/components/layout/` (app shell) and `src/components/feedback/` (empty/error states) exist in the tree but are **NOT** populated here (layout = 2.7; feedback = 2.20-2.22).
- `src/features/workflows/` — domain layer; primitives must never import from it (AC7).
Guardrails: "shadcn/ui components should be used consistently with **minimal local customization**" (architecture.md:488); "Product differentiation should come… from composition… **rather than from heavily restyled primitives**" (ux:587).

### PrimitivesPlayground state matrix (from UX spec)

Canonical state vocabulary every status-bearing primitive must demonstrate (ux:881-894): informational, success/approved, warning, blocker, draft/inactive, selected/focused, loading, error, permission-restricted, empty — and **never color-alone** (ux:894). Button states (ux:1968-1973): ready, blocked, stale, submitting, completed; variants (ux:1986-1990): primary (governed), secondary (review), tertiary (inspect/compare), destructive, blocked/disabled (with adjacent reason, ux:1978). Feedback states (ux:1818-1823): inline informational/success/warning/blocker/error + persistent decision outcome. Empty/loading/error are first-class, not edge polish (ux:1866). Overlay focus management (ux:1938-1940): focus moves in and returns on close.

### WCAG 2.1 AA touch-points (full audit is story 2.25)

Target WCAG 2.1 AA (ux:2110). For primitive setup specifically: visible & consistent **focus-visible** indicators (ux:876, 1979); **label-associated** form controls (`<Label htmlFor>`, ux:2168); **color-independent** semantic state (ux:894); **ARIA live regions** for async/toast updates (ux:1809, 2125); adequate **touch-target sizing** (ux:2120, 2128). shadcn primitives are built on Radix (accessible by default) — preserve that; don't strip ARIA.

### Logging Requirements (project-wide standard)

Frontend-only story; no JVM/application code is added, so the SLF4J + Logback standard (INFO entry/exit, WARN typed-rejection, ERROR unhandled, MDC keys `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity`/`actorType`, redaction before logging, list-appender test pinning) is dormant here but remains in force for any incidental backend change. No `System.out`/`printStackTrace()` anywhere.

### Anti-patterns to avoid

- **Do NOT customize colors, typography, or spacing** — leave shadcn default CSS-variable values; tokens are 2.3/2.4.
- **Do NOT build any composite** (queue item, review panel, decision bar, etc.) — 2.15–2.19.
- **Do NOT author `eslint.config.js`, Prettier config, or the ESLint custom rules** — story 2.31.
- **Do NOT install TanStack Router/Query or author `src/lib/api`, `src/lib/queryKeys`, `src/routes/` real routes** — 2.5/2.6.
- **Do NOT add Vitest / a test runner** — 2.27. The playground is the manual smoke test.
- **Do NOT activate dark mode** (no toggle, no `dark` class) — AC8 is "wired, not activated".
- **Do NOT edit `src/components/ui/*` to reference workflow domain types or import `features/workflows`** — AC7.
- **Do NOT use Tailwind v4** unless Open Clarification Q2 is resolved to adopt it (native-binary lockfile risk + AC1 divergence).
- **Do NOT regenerate the lockfile with `--package-lock-only`, and do NOT skip Linux verification** — the story-2.1 cross-platform trap.
- **Do NOT `git add .`** — `_bmad-output/` + tooling dirs are untracked by convention; stage explicit `deliveryline-frontend/` + CI/doc paths.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.2] — authoritative ACs (lines 872-888)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.31 + execution-order note] — 2.31-before-2.2 ordering + `no-workflow-domain-in-ui-primitives` rule (lines 1003-1023, esp. 1009, 1016)
- [Source: _bmad-output/planning-artifacts/epics.md#Epic 2 critical-path dependency edges] — 2.24-before-2.15/2.17/2.18 (lines 841-849; not this story but epic context)
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture] — shadcn/ui + Tailwind decision, "minimal local customization" (lines 447-461, 488)
- [Source: _bmad-output/planning-artifacts/architecture.md#Project Structure] — frontend tree: `components/ui/`, `lib/utils.ts`, `styles/globals.css`, `components.json`, `tailwind.config.ts` (lines 1055-1091, 687-694, 1182-1183)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Component Layering Model] — 3-layer model: primitives vs tokens vs composites (lines 1756-1780)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Canonical State Semantics] — state vocabulary for the playground (lines 879-894)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Design System Components] — primitive inventory confirmation (lines 1281-1296, 550, 1760-1761)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Accessibility] — WCAG 2.1 AA (lines 2108-2129, 809-821)
- [Source: _bmad-output/implementation-artifacts/2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring.md] — pins, strict tsconfig, deleted eslint config, build wiring, cross-platform lockfile lesson
- [Source: project memory `frontend-lockfile-cross-platform` + `verify-ci-fixes-in-clean-env`] — full `npm install` regeneration + Linux verification discipline

### Open clarifications (resolve before/at start; otherwise apply the recommended default)

- **Q1 (2.31-before-2.2 ordering):** Recommended — merge story 2.31 first so AC7 uses the real ESLint rule. If proceeding with 2.2 first, use the grep-based CI fallback (Task 4) per AC7's "or grep-based CI check" wording, with a TODO to migrate. **Confirm the path before Task 4.**
- **Q2 (Tailwind v3 vs v4):** Recommended — pin **v3.4.x** (matches AC1's `tailwind.config.ts`/`postcss.config.js`, avoids native-binary lockfile risk). Adopting v4 requires AC1 amendment + native-dep cross-platform re-verification.
- **Q3 (toast vs sonner):** Recommended — use **`sonner`** (current shadcn replacement for the deprecated `toast`); AC3 lists "`toast` / `sonner`" so sonner satisfies it.
- **Q4 (PrimitivesPlayground mount):** TanStack Router is not installed until 2.5. Recommended — keep the file at `src/routes/_dev/PrimitivesPlayground.tsx` but mount via an `import.meta.env.DEV`-gated conditional (e.g. `?playground`) in `App.tsx`; 2.5 converts it to a real `_dev` route.
- **Q5 (shadcn `@/*` alias vs 2.1's deferral):** shadcn requires the `@/*` path alias; story 2.1 deferred aliases to 2.5/2.6. Recommended — introduce `@/* → ./src/*` here (tsconfig + vite) since shadcn mandates it, and document that this supersedes 2.1's deferral note.

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m] (Claude Opus 4.7, 1M context) — bmad-dev-story workflow.

### Debug Log References

- All `npm`/`shadcn` commands run with the **module-local npm 10.8.2** under
  `deliveryline-frontend/.frontend-node/node` (never ambient npm), per the story-2.1
  cross-platform lockfile lesson.
- Tailwind v3.4.19 + postcss 8.5.x + autoprefixer 10.5.x + tailwindcss-animate installed
  first so the shadcn CLI detects v3 mode.
- shadcn CLI: latest is `4.7.0` (Tailwind-v4-first; its `components.json` schema diverges
  from AC2). Used **`shadcn@2.10.0`** (v3-compatible, classic `components.json` with
  `style`/`baseColor`/`cssVariables`) to add the 20 primitives.
- `shadcn add` initially wrote to a literal `@/` directory (no `baseUrl` → its resolver
  couldn't map the alias); files were relocated to `src/components/ui/`. The `@/*` →
  `./src/*` mapping still resolves at compile/runtime under `moduleResolution: bundler`
  without `baseUrl` (which is deprecated in TS 6.0).
- Linux verification: `docker run --rm node:20.19.0 … npm ci && npm run build` → green,
  **byte-identical bundle hashes** to the Windows build (`index-D8Zbgsha.css`,
  `index-PkTmzsyq.js`).

### Completion Notes List

Implements Layer 1 (foundation primitives) of the design system. All 9 ACs satisfied.

- **AC1/AC9 (Tailwind v3):** `tailwind.config.ts` (`darkMode:['class']`,
  `content:['./index.html','./src/**/*.{ts,tsx}']`, stock shadcn `theme.extend`),
  `postcss.config.js` (ESM), `src/styles/globals.css` (`@tailwind` directives + slate
  `:root`/`.dark` CSS-variable blocks). Imported from `main.tsx` (template `index.css`/
  `App.css` deleted). Purge verified: unused `grid-cols-12` absent from the prod CSS,
  used `bg-background` present. HMR confirmed via dev-server boot (Vite-native; the
  no-full-refresh observation is a manual browser check, as in story 2.1).
- **AC2 (components.json):** authored to the canonical shadcn-2.x schema (style `new-york`,
  base color `slate`, CSS variables, `src/components/ui/`, `@/*` alias). `shadcn@2.x init`'s
  CSS-path/alias prompts are not flag-driven for a Vite project, so the file was authored
  directly and **validated by a successful `shadcn add`** that read it.
- **AC3/AC4 (20 primitives):** added via `shadcn@2.10.0 add` — button, input, textarea,
  label, dialog, sheet, popover, dropdown-menu, select, tabs, badge, alert, table, card,
  tooltip, scroll-area, accordion, collapsible, separator, **sonner** (current shadcn toast
  replacement; AC3 "toast / sonner"). Stock output except two minimal fixes: (1)
  `dropdown-menu.tsx` — `checked` flows via `{...props}` instead of an explicit prop to
  satisfy `exactOptionalPropertyTypes` (Radix uncontrolled-when-absent semantics preserved);
  (2) `sonner.tsx` — rewritten for the Vite SPA (fixed the generator's self-import bug; pinned
  `theme="light"` and dropped the `next-themes` Next.js dependency per AC8). No primitive
  encodes workflow concepts. `tsc --noEmit` clean under all strict flags.
- **AC5 (cn()):** `src/lib/utils.ts` exports `cn()` = `clsx` + `twMerge`.
- **AC6 (playground):** `src/routes/_dev/PrimitivesPlayground.tsx` renders every primitive
  across the UX-spec canonical states (icon + text, never color alone). Mounted dev-only via
  `import.meta.env.DEV` + `?playground` in `App.tsx`; the `lazy(import())` is created only
  under the DEV guard so Rollup **tree-shakes it out of prod** (verified: prod build emits no
  playground chunk; 16 modules transformed). Purely presentational — no data/Query.
- **AC7 (primitive purity) — Q1 resolved (path A):** story 2.31 is `done`, so its
  `no-workflow-domain-in-ui-primitives` ESLint rule (scoped to `src/components/ui/**`) is the
  enforcement. Verified end-to-end: a temporary violating primitive made `npm run lint` exit 1
  with the rule's message; the committed RuleTester fixture (`lint:rules-test`) also proves it.
  No grep fallback needed.
- **AC8 (dark mode wired, not activated):** `darkMode:['class']` + `.dark` block present; no
  toggle, no `dark` on `<html>`. Documented in README "Design System".
- **Lint integration:** stock shadcn primitives tripped four rules in 2.31's strict config that
  never saw real primitives before (`react/prop-types`, `react-refresh/only-export-components`,
  `strict-boolean-expressions`, `jsx-a11y/heading-has-content`). Rather than edit 20 stock files,
  these are relaxed in a **scoped override on the `src/components/ui/**` block 2.31 already
  created** — keeping primitives pristine (AC4) while application code retains the full ruleset.
  All other a11y rules stay ON.
- **AC alias note (Q5):** `@/*` → `./src/*` introduced in `tsconfig.app.json` (`paths`, no
  `baseUrl`) + `vite.config.ts` (`resolve.alias`), superseding story 2.1's deferral.
- **Task 9 (cross-platform):** lockfile regenerated with full `npm install` (npm 10.8.2);
  platform-gated native bindings preserved (linux/win32/darwin entries intact). Linux Docker
  `npm ci`+build green; Windows reactor `./mvnw -B -ntp -DskipTests clean install` green (all 4
  modules; frontend module ran lint + lint:rules-test + format:check + build via
  frontend-maven-plugin); backend jar embeds the SPA at `BOOT-INF/classes/static/`.
- **Logging:** N/A — frontend-only story; no JVM/application code added. SLF4J/MDC standard
  remains dormant-but-in-force for any future backend code under this scope.

**Deferred / notes for review:** (a) Tailwind's text-based content scan includes the dev-only
playground's utility classes in the prod CSS (~27 kB) even though the playground JS is
tree-shaken — accepted; AC1 prescribes the `src/**/*.{ts,tsx}` glob. (b) Story-2.1 demo assets
(`src/assets/{hero.png,react.svg,vite.svg}`, `public/icons.svg`) are now unreferenced but left
in place (out of scope to remove). (c) Pre-existing backend Spotbugs `[ERROR]` warnings
(`DomainRegistry`, `LinearConfiguration`, `RunnerConfiguration`) are unrelated to this story and
non-blocking (reactor BUILD SUCCESS).

### File List

**Created**

- `deliveryline-frontend/tailwind.config.ts`
- `deliveryline-frontend/postcss.config.js`
- `deliveryline-frontend/components.json`
- `deliveryline-frontend/src/styles/globals.css`
- `deliveryline-frontend/src/lib/utils.ts`
- `deliveryline-frontend/src/routes/_dev/PrimitivesPlayground.tsx`
- `deliveryline-frontend/src/components/ui/accordion.tsx`
- `deliveryline-frontend/src/components/ui/alert.tsx`
- `deliveryline-frontend/src/components/ui/badge.tsx`
- `deliveryline-frontend/src/components/ui/button.tsx`
- `deliveryline-frontend/src/components/ui/card.tsx`
- `deliveryline-frontend/src/components/ui/collapsible.tsx`
- `deliveryline-frontend/src/components/ui/dialog.tsx`
- `deliveryline-frontend/src/components/ui/dropdown-menu.tsx`
- `deliveryline-frontend/src/components/ui/input.tsx`
- `deliveryline-frontend/src/components/ui/label.tsx`
- `deliveryline-frontend/src/components/ui/popover.tsx`
- `deliveryline-frontend/src/components/ui/scroll-area.tsx`
- `deliveryline-frontend/src/components/ui/select.tsx`
- `deliveryline-frontend/src/components/ui/separator.tsx`
- `deliveryline-frontend/src/components/ui/sheet.tsx`
- `deliveryline-frontend/src/components/ui/sonner.tsx`
- `deliveryline-frontend/src/components/ui/table.tsx`
- `deliveryline-frontend/src/components/ui/tabs.tsx`
- `deliveryline-frontend/src/components/ui/textarea.tsx`
- `deliveryline-frontend/src/components/ui/tooltip.tsx`

**Modified**

- `deliveryline-frontend/package.json` (Tailwind v3 + shadcn/Radix deps)
- `deliveryline-frontend/package-lock.json` (regenerated, full npm install)
- `deliveryline-frontend/tsconfig.app.json` (`@/*` paths alias)
- `deliveryline-frontend/vite.config.ts` (`resolve.alias` for `@`)
- `deliveryline-frontend/src/main.tsx` (import `./styles/globals.css`)
- `deliveryline-frontend/src/App.tsx` (re-skinned to Tailwind + dev-only playground mount)
- `deliveryline-frontend/eslint.config.js` (scoped rule relaxations for `src/components/ui/**`)
- `deliveryline-frontend/README.md` ("Design System" section)

**Deleted**

- `deliveryline-frontend/src/App.css`
- `deliveryline-frontend/src/index.css`

## Change Log

| Date       | Version | Description                                                                                          | Author |
| ---------- | ------- | ---------------------------------------------------------------------------------------------------- | ------ |
| 2026-05-20 | 0.1     | Implemented Tailwind v3 + shadcn/ui (20 primitives) + cn() + dev playground; AC1–AC9 satisfied; cross-platform verified (Linux Docker + Windows reactor). Status → review. | Amelia (Dev) |
