# Story 2.3: Design Tokens — Color Palette + Semantic State Variables

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **a neutral/calm color token system with semantic state variables driven by CSS custom properties**,
so that **every workflow composite draws from one semantic palette, blocker/warning states are visually dominant, and no state relies on color alone**.

## ⚠️ Read first — what this story is and is NOT

This story delivers **Layer 2 (Design Tokens — color half)** of the design-system three-layer model (ux:1756-1780). Story 2.2 installed **stock shadcn slate CSS variables** (`globals.css:18-63`) precisely so this story can **re-skin the system by editing CSS custom properties WITHOUT touching any primitive** (2.2 AC4). You are now editing those variable values + adding new token layers.

- **Color tokens only.** Typography, spacing, layout primitives, and the global focus ring are **story 2.4** — do NOT add `--font-*`, `--text-*`, `--leading-*`, spacing scales, `Stack`/`Inline`/`Grid`, or `--ring-focus` here (ux:773-794; epics.md:907-922). The one exception: the `--ring` color value already exists from shadcn and may be re-toned to the accent (see Task 2), but the focus-ring **utility/token system** is 2.4's scope.
- **No composites.** Queue Item, Run Context Strip, Artifact Review Panel, etc. are 2.15-2.19. This story produces tokens + a signifier map + verification, and demonstrates them in the existing PrimitivesPlayground — it builds no product UI.
- **No primitive edits.** Do NOT edit `src/components/ui/*` (2.2 AC4/AC7; architecture.md:488; ux:587). Tokens flow into primitives automatically through the existing `tailwind.config.ts` → CSS-variable mapping.
- **No new test runner.** Vitest arrives in 2.27. This story's automated verification (AC4/AC5/AC6) uses the project's **already-established `node --test` tooling pattern** (`package.json` `lint:rules-test`; `tools/eslint-rules/__tests__/`). See Dev Notes "Testing approach".

## Acceptance Criteria

1. **Given** `src/styles/globals.css`, **Then** CSS custom properties define the base neutral palette (background, surface, elevated-surface, text-primary, text-secondary, text-tertiary, border) and a blue-green/teal accent family (`--accent-50` through `--accent-900`) per UX-DR2.
2. **Given** the semantic state token layer, **Then** tokens are defined for each documented state: `--state-informational`, `--state-success`, `--state-warning`, `--state-blocker`, `--state-draft`, `--state-selected`, `--state-loading`, `--state-error`, `--state-permission-restricted`, `--state-empty`, `--state-stale`, `--state-recovery` — each with foreground + background + border triplets and a dedicated high-contrast variant for accessibility edge cases.
3. **Given** Tailwind's theme extension, **Then** `tailwind.config.ts` exposes the tokens as utility classes (e.g., `bg-state-blocker`, `text-state-success`, `border-state-warning`) — composites consume tokens via utilities, not raw hex.
4. **Given** contrast requirements (WCAG 2.1 AA; story 2.25), **Then** every foreground/background pair in the semantic palette passes 4.5:1 for body text and 3:1 for large text — verified by an automated contrast test using the defined tokens.
5. **Given** the "no state by color alone" rule (UX-DR2), **Then** every state token has a documented non-color signifier (icon, label text, pattern) that composites must apply alongside the color — enforced in component-test fixtures.
6. **Given** blocker/warning dominance (UX-DR2), **Then** visual regression fixtures prove that a `state-blocker` badge is visually more prominent than a `state-informational` badge at the same size.
7. **Given** the accent palette, **Then** documentation (`src/styles/README.md`) explains: neutral surfaces for reading, accent for primary interactive actions only (no ambient decoration), blocker/warning for critical states, draft/stale for superseded content.
8. **Given** future dark-mode support (deferred per story 2.2 AC8), **Then** the token system is structured so dark-mode values can be added by overriding CSS custom properties under a `.dark` scope without restructuring composites.

## Tasks / Subtasks

- [x] **Task 1: Define the base neutral palette + teal accent family in `globals.css`** (AC: 1, 8)
  - [x] In `src/styles/globals.css` `:root`, add the **named neutral surface tokens** UX-DR2 requires (these are NEW, additive — do NOT delete the existing shadcn `--background`/`--foreground`/`--card`/… variables that primitives depend on): `--surface` (panel surface, one step off background), `--surface-elevated` (raised surface — popovers/cards-on-surface), `--text-primary`, `--text-secondary`, `--text-tertiary`, and confirm `--border` (already present). Keep HSL channel-triplet format (`H S% L%`) so the existing `hsl(var(--x))` mapping works.
  - [x] Define the **teal/blue-green brand family** `--brand-50` … `--brand-900` (10 stops) per UX-DR2's "blue-green / teal accent family as the primary interactive color" (ux:758). Low-saturation, operationally-calm teal — NOT a bright consumer teal (ux:762, 873). **Naming note (Q2 RESOLVED: `brand`):** AC1 literally calls this `--accent-50…900`, but we name it `--brand-*` (CSS var + Tailwind `brand` key) to avoid colliding with shadcn's existing `--accent` muted-surface semantic. This is a deliberate, documented deviation from AC1's variable name — the *intent* of AC1 (a teal interactive family exposed as utilities) is fully met; only the token name changes. Record this in `src/styles/README.md` and Completion Notes so review does not flag it as a missing AC.
  - [x] **Re-tone the shadcn neutral variables** from the stock slate values to the DeliveryLine neutral palette so primitives inherit the calm surface system: update `--background`, `--foreground`, `--card`, `--popover`, `--muted`, `--muted-foreground`, `--secondary`, `--border`, `--input` to the neutral values. Leave the variable **names** intact (primitives reference them); only the values change.
  - [x] **`--primary` → teal (Q1 RESOLVED: yes).** Re-tone `--primary` / `--primary-foreground` to a brand-family teal (`--brand-600`-ish for the fill, light/white foreground) so stock primitive buttons render as the teal primary-interactive color per UX-DR2 — without editing any primitive. Ensure the `--primary` / `--primary-foreground` pair still passes the Task 4 contrast gate.
  - [x] Keep **all** new and re-toned values mirrored in the existing `.dark { … }` block as structurally-parallel overrides (AC8). Dark values may be approximate/placeholder (dark mode is wired-not-activated, 2.2 AC8) but the **structure must exist** so a future story only overrides values. Add a comment that `.dark` values are provisional pending the post-MVP dark-mode story.

- [x] **Task 2: Define the 12 semantic state tokens with fg/bg/border + high-contrast triplets** (AC: 2, 8)
  - [x] For **each** of the 12 states (`informational`, `success`, `warning`, `blocker`, `draft`, `selected`, `loading`, `error`, `permission-restricted`, `empty`, `stale`, `recovery`), define a four-part token group in `:root`:
    - `--state-{name}` — the **background fill** (the surface the chip/badge/banner paints).
    - `--state-{name}-foreground` — text/icon color that sits on that fill.
    - `--state-{name}-border` — border color for the fill.
    - `--state-{name}-hc` (+ `-hc-foreground`, `-hc-border` if needed) — the **high-contrast variant** for accessibility edge cases (dense screens, low-vision, AC2 + ux:818-821).
  - [x] Apply the **dominance rule** (AC6, UX-DR2 ux:771, 894): `blocker` and `warning` use stronger saturation / darker fills / heavier borders than `informational`/`draft`/`empty`, which stay low-contrast and recessive. `draft`/`stale`/`empty` are deliberately muted (superseded/inactive content). `selected` reads as the **brand** (teal) family. `recovery`/`stale` map to the "attention-needed" amber/neutral family per ux:759, 769.
  - [x] Use channel-triplet HSL format throughout. Mirror every `--state-*` token into `.dark` with provisional values (AC8 structure parity).
  - [x] Single source of truth: these CSS variables are authoritative. The contrast test (Task 4) and signifier map (Task 5) read **the shipped `globals.css`** — do NOT duplicate the values into a second TS/JSON file that can drift.

- [x] **Task 3: Expose tokens as Tailwind utilities** (AC: 3)
  - [x] In `tailwind.config.ts` `theme.extend.colors`, **add** (do NOT remove or modify the existing shadcn `border`/`input`/`ring`/`background`/`foreground`/`primary`/`secondary`/`destructive`/`muted`/`accent`/`popover`/`card` mappings — primitives import them; **leave `accent` exactly as-is**):
    - The teal scale under a **new, dedicated `brand` key** (Q2 RESOLVED: `brand`): `brand: { 50: 'hsl(var(--brand-50))', …, 900: 'hsl(var(--brand-900))' }`. Utilities: `bg-brand-600`, `text-brand-700`, `border-brand-500`, etc. This keeps shadcn's `accent` (muted hover surface) completely untouched — no overload, no collision.
    - Named neutral surfaces: `surface`, `surface-elevated`, `text-primary`/`text-secondary`/`text-tertiary` (or a `text` object) mapped to the new vars.
    - A `state` color group keyed by the 12 state names. Shape per state: `blocker: { DEFAULT: 'hsl(var(--state-blocker))', foreground: 'hsl(var(--state-blocker-foreground))', border: 'hsl(var(--state-blocker-border))', hc: 'hsl(var(--state-blocker-hc))' }`. Utilities produced: `bg-state-blocker`, `text-state-blocker-foreground`, `border-state-blocker-border`, `bg-state-blocker-hc`, etc.
  - [x] **AC3 example reconciliation (Q2 RESOLVED):** AC3 lists `bg-state-blocker`, `text-state-success`, `border-state-warning` as *illustrative* utility shapes. The DEFAULT=fill convention above makes `bg-state-blocker` correct; for text/border the precise utilities are `text-state-success-foreground` / `border-state-warning-border`. This is the canonical vocabulary composites (2.15+) must use — document it in `src/styles/README.md` (Task 7).
  - [x] Verify `tsc -b && vite build` succeeds and the utilities resolve (a quick smoke: add `bg-state-blocker` to a playground element and confirm it compiles + appears in the dev CSS). *(Playground gallery uses literal `bg-state-*`/`bg-brand-*`/`bg-surface` utilities; `npm run build` green, CSS bundle 37.66 kB includes the new utilities.)*

- [x] **Task 4: Automated contrast test (AC4)** — `node --test`, NOT Vitest
  - [x] Create a contrast verification test under the existing tooling convention (e.g. `tools/contrast/__tests__/token-contrast.test.js` + a small `tools/contrast/contrast.js` helper), mirroring how `tools/eslint-rules/__tests__/*` are run by `lint:rules-test`. *(Added `tools/contrast/contrast.js` + `tools/contrast/parse-globals.js` helpers.)*
  - [x] The test must: (a) **parse the shipped `src/styles/globals.css`** `:root` block, extracting every `--state-*` and base color var; (b) convert each HSL channel-triplet → RGB → WCAG relative luminance; (c) compute the contrast ratio for every `foreground` vs its `background` pair (the standard triplet) **and** every high-contrast `-hc-foreground` vs `-hc` pair; (d) assert ≥ **4.5:1** for the normal fg/bg pairs (treated as body text) and ≥ **3:1** for border-vs-background and large-text thresholds; (e) fail with a clear per-token message naming the failing pair and its actual ratio.
  - [x] Add an npm script (e.g. `"check:contrast": "node --test tools/contrast/__tests__/token-contrast.test.js"`) and **wire it into the frontend-maven-plugin / `frontend-build-tests` CI path** exactly as `lint:rules-test` was wired (per sprint-status note 2026-05-20: "`lint:rules-test` is wired into frontend-maven-plugin so the enforced Maven/CI path runs the custom-rule fixtures"). The contrast gate must run on the **enforced Maven/CI path**, not only as a local convenience. *(Verified: the `npm-run-check-contrast` execution fires in the reactor build.)*
  - [x] Include a self-test / negative assertion proving the gate actually fails on a sub-threshold pair (so it can't silently become a no-op) — same discipline as 2.2 Task 4's negative fixture.

- [x] **Task 5: Non-color signifier map (AC5)**
  - [x] Create `src/lib/state-signifiers.ts` (or `src/styles/state-signifiers.ts`) exporting a typed, exhaustive map from each of the 12 state names → `{ icon: <lucide-react icon name>, label: string, /* optional */ pattern?: string }`. Use `lucide-react` (already a dep) icon identifiers. This is the contract future composites import so a state's color is **always** accompanied by an icon + label (UX-DR2 ux:894; epics.md:902).
  - [x] Type it so the map is **statically exhaustive** over the 12-state union (e.g. `Record<StateName, StateSignifier>` where `StateName` is a string-literal union) — TypeScript fails the build if a state is missing. Define the `StateName` union as the single canonical list other stories import.
  - [x] Add a `node --test` fixture (alongside Task 4, or `tools/contrast/__tests__/state-signifiers.test.js`) asserting (a) every `--state-*` group present in `globals.css` has a signifier entry and vice-versa (no orphan tokens, no orphan signifiers), and (b) each entry has a non-empty `icon` + `label`. This is the "enforced in component-test fixtures" requirement of AC5, satisfied at the token layer before composites exist.

- [x] **Task 6: Blocker-dominance fixture (AC6)**
  - [x] **Programmatic proxy (the enforceable part):** add a `node --test` assertion that, from the parsed `globals.css`, the `blocker` (and `warning`) tokens are quantifiably more prominent than `informational`/`draft`/`empty` — e.g. greater fill saturation AND/OR larger lightness delta from `--background` AND a non-transparent border. Document the chosen prominence metric in a comment. This guards against a future edit that accidentally makes blocker recede. *(`tools/contrast/__tests__/token-prominence.test.js`: prominence = saturation + |lightness − background|; asserts min(blocker,warning) > max(informational,draft,empty) + visible border.)*
  - [x] **Visual demonstration:** extend the existing dev-only `src/routes/_dev/PrimitivesPlayground.tsx` (story 2.2, mounted via `import.meta.env.DEV` + `?playground`) with a token-gallery section rendering: the neutral surface ramp, the `accent-50…900` ramp, and **all 12 state badges side-by-side at identical size** (Badge primitive + icon from the signifier map + label) so a reviewer can eyeball that blocker/warning dominate informational. Keep it purely presentational (no data/Query), consistent with 2.2's playground discipline. *(Brand ramp shown as `--brand-*` per Q2; standard + high-contrast state badge rows added.)*
  - [x] **Scope honesty (Q3 RESOLVED: defer pixel-VR).** True pixel-level visual-regression tooling (Playwright/Chromatic snapshots) is **not** installed and is **deferred** to the frontend test suite (2.27) / a11y audit (2.25) — do NOT add it here. AC6's "visual regression fixtures" is satisfied in this story by the programmatic prominence proxy + the playground gallery as the human-reviewable fixture. State this interpretation in Completion Notes and log the pixel-VR follow-up for 2.27 (e.g. in `deferred-work.md` if the repo tracks deferrals there). *(Logged in `deferred-work.md`.)*

- [x] **Task 7: `src/styles/README.md` design-token documentation (AC7)**
  - [x] Create `src/styles/README.md` explaining the token system: (a) neutral surfaces for reading; (b) **accent for primary interactive actions only — no ambient decoration** (ux:874); (c) blocker/warning for critical states (visually dominant); (d) draft/stale for superseded/inactive content; (e) the canonical utility vocabulary (`bg-state-*`, `text-state-*-foreground`, `border-state-*-border`, `bg-brand-*`/`text-brand-*`, `surface`/`surface-elevated`) with copy-paste examples, plus the note that the teal family is named `brand` (not `accent`) and why (shadcn `--accent` collision — AC1 deviation); (f) the "never color alone — pair with the signifier map" rule and where the map lives; (g) the dark-mode structure note (AC8). Cross-link from `deliveryline-frontend/README.md`'s existing "Design System" section (added in 2.2).

- [x] **Task 8: Cross-platform lockfile + reactor/CI verification** (cross-cutting; LOAD-BEARING per Epic 1 retro + story 2.1/2.2)
  - [x] This story adds **no new runtime dependencies** (lucide-react already present; the contrast/signifier tooling is pure-JS using Node built-ins). Therefore **no `package.json` dependency change is expected.** If you find yourself adding a dependency (e.g. a color/contrast lib), STOP — prefer a ~30-line hand-rolled HSL→luminance helper (it's trivial) to avoid a new lockfile/native-binding risk. If a dep is genuinely needed, you MUST regenerate `package-lock.json` with a **full `npm install`** using the module-local pinned npm 10.8.2 (NOT `--package-lock-only`, NOT ambient npm) and re-verify on Linux — the exact story-2.1 trap (see memory `frontend-lockfile-cross-platform`). *(No dep added; only `package.json` `scripts` gained `check:contrast`. `package-lock.json` not regenerated by this story.)*
  - [x] If only `package.json` `scripts` changed (adding `check:contrast`) with no dependency delta, the lockfile typically does not need regeneration — confirm `npm ci` still succeeds. *(`npm ci` green on Linux and via the Maven `npm-ci` execution.)*
  - [x] **Verify on Linux** before done: `docker run --rm -v "<frontend>:/app" -w /app node:20.19.0 bash -c "npm ci --no-audit --no-fund --prefer-offline && npm run build && npm run check:contrast && npm run lint:rules-test"` → green. *(Ran on `node:20.19.0` with an anonymous `node_modules` volume; all four steps green — check:contrast 7/7, lint:rules-test 2/2.)*
  - [x] Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` green (all 4 modules); backend jar still embeds the SPA at `BOOT-INF/classes/static/`. Confirm the new contrast/signifier tests run on the enforced frontend-maven-plugin path and the `frontend-build-tests` matrix (ubuntu + windows) stays green. *(BUILD SUCCESS, 4/4 modules; `npm-run-check-contrast` execution ran 7 tests; jar embeds `BOOT-INF/classes/static/{index.html,assets/*}`.)*

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** This is a frontend tokens/config/docs story. It introduces **no JVM application-service classes, no domain exceptions, no SPI calls, no state transitions** — so the SLF4J/MDC logging standard (entry/exit INFO, WARN typed-rejection, ERROR unhandled, `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity` keys) has no application code to instrument here. The standard remains in force for any future JVM code under this story's scope.
  - [x] Frontend client-side structured logging is out of scope (arrives with later stories). The `node --test` tooling and contrast script may use plain `console`/test-reporter output only — no `System.out`/`printStackTrace()` (no JVM code exists here). *(Test files emit only the node test reporter / assertion messages.)*

## Dev Notes

### Review Findings

- [x] [Review][Patch] Token gate validates the emitted CSS bundle when available [deliveryline-frontend/tools/contrast/parse-globals.js:15]
- [x] [Review][Patch] `:root` parsing now strips comments and matches the real selector [deliveryline-frontend/tools/contrast/parse-globals.js:42]
- [x] [Review][Patch] Missing base fill tokens now fail the contrast suite [deliveryline-frontend/tools/contrast/__tests__/token-contrast.test.js:41]
- [x] [Review][Patch] Invalid signifier icons now fail verification instead of silently falling back [deliveryline-frontend/tools/contrast/__tests__/state-signifiers.test.js:79]
- [x] [Review][Patch] Alert primitive ref element types match rendered DOM nodes [deliveryline-frontend/src/components/ui/alert.tsx:30]
- [x] [Review][Patch] Global body margin reset restored in the replacement stylesheet [deliveryline-frontend/src/styles/globals.css:302]
- [x] [Review][Patch] Playground canonical state rendering now derives from `STATE_SIGNIFIERS` / `STATE_NAMES` [deliveryline-frontend/src/routes/_dev/PrimitivesPlayground.tsx:274]

### Story scope — Layer 2 (color tokens) of the three-layer design system

The design system has three layers (ux:1756-1780): **(1) Foundation primitives** (stock shadcn — done in 2.2), **(2) Design tokens** (this story = color; 2.4 = typography/spacing/layout), **(3) Workflow composites** (2.15-2.19). This story re-skins layer 1 through tokens **without editing primitives**, and prepares the semantic vocabulary that layer 3 consumes. The discipline from UX-DR2/UX-DR5: product character comes from *state semantics + composition*, not restyled primitives (ux:587; architecture.md:488).

### 🔴 Accent namespace — resolved via `brand` naming (Q2)

shadcn already defines `--accent` / `--accent-foreground` as a **muted gray hover surface** (`globals.css:32-33`, value `210 40% 96.1%`) and `tailwind.config.ts:49-52` maps `colors.accent = { DEFAULT, foreground }`. Primitives (button ghost/hover, dropdown highlight, etc.) depend on `bg-accent` / `text-accent-foreground`.

AC1 also wants a **teal "accent" family** ("primary interactive color", ux:758) — a *different* concept that happens to share the word "accent."

**Decision (Q2): name the teal family `brand`, not `accent`.** This sidesteps the overload entirely:
- **Leave shadcn `--accent` / `--accent-foreground` and `colors.accent` completely untouched** (re-tone only their *values* to neutral as part of the surface re-skin in Task 1; keep the names + Tailwind mapping). Primitives keep working unchanged (2.2 AC4 preserved).
- Add the teal scale as **new** `--brand-50…900` CSS vars, exposed under a **new** `colors.brand` Tailwind key → `bg-brand-600`, `text-brand-700`, etc.
- Route the *primary interactive* color into primitives via `--primary` (re-toned to `--brand-600`, Q1) — never by hijacking `--accent`.

**AC1 deviation (documented & accepted):** AC1 literally names the family `--accent-50…900`; we ship `--brand-50…900` instead. The AC's *intent* (a teal interactive family exposed as utilities) is fully satisfied; only the token name differs, to avoid the shadcn collision. Call this out in `src/styles/README.md` + Completion Notes so the code reviewer / Acceptance Auditor recognizes it as intentional, not a missed AC.

### Token naming convention (prescriptive)

- **Neutral surfaces:** `--background`, `--surface`, `--surface-elevated`, `--text-primary`, `--text-secondary`, `--text-tertiary`, `--border` (+ existing shadcn vars retained).
- **Brand (teal) family:** `--brand-50` … `--brand-900` → Tailwind `brand-*` (Q2). shadcn `--accent`/`--accent-foreground` retained, untouched, as the muted hover surface.
- **Semantic state (×12):** `--state-{name}`, `--state-{name}-foreground`, `--state-{name}-border`, `--state-{name}-hc` (+ `-hc-foreground`/`-hc-border`).
- **HSL channel-triplet format** (`222 47% 11%`, no `hsl()` wrapper) for **all** vars so the existing `hsl(var(--x))` Tailwind mapping keeps working uniformly. Do not mix in hex or `rgb()`.
- The 12-state list is canonical: define a `StateName` string-literal union (Task 5) as the single import point so 2.4/2.15+ never re-type the list.

### Testing approach — `node --test`, NOT Vitest (resolves the AC4/AC5/AC6 ↔ "no test runner yet" tension)

Story 2.2 deferred **Vitest** to story 2.27 and used the playground as a manual smoke test. But the repo **already runs tooling tests** with Node's built-in test runner: `package.json` `lint:rules-test` = `node --test tools/eslint-rules/__tests__/*.test.js`, and that script is **wired into frontend-maven-plugin** so it runs on the enforced Maven/CI path (sprint-status note 2026-05-20). Reuse this exact mechanism for AC4 (contrast), AC5 (signifier exhaustiveness), and AC6 (prominence proxy). This satisfies "automated contrast test" / "enforced in component-test fixtures" **without** pulling Vitest forward (which would be 2.27's scope + a dependency-graph change). Do **not** add Vitest, Playwright, or any test/VR framework here.

- Parse the **shipped `globals.css`** (single source of truth) rather than a parallel JS token file — eliminates token/CSS drift.
- Hand-roll HSL→RGB→relative-luminance→contrast (≈30 lines; WCAG formula). No new dependency (Task 8).

### Previous Story Intelligence (Story 2.2 — done; Story 2.1 — review)

Source: `2-2-tailwind-and-shadcn-ui-setup-and-primitive-inventory.md`, `2-1-...-maven-wiring.md`. Key state this story builds on:
- **Stock shadcn slate variables are intentional placeholders** for THIS story to replace (`globals.css:5-17` says so explicitly). `tailwind.config.ts` maps them; do not remove mappings.
- **Strict TS** (`tsconfig.app.json`): `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`. The `state-signifiers.ts` map + `StateName` union must compile clean under these.
- **`@/*` → `./src/*` alias** is live (added in 2.2: `tsconfig.app.json` paths + `vite.config.ts` resolve.alias). Import the signifier map via `@/lib/...`.
- **ESLint custom rules exist** (`tools/eslint-rules/`): `no-workflow-domain-in-ui-primitives` (scoped to `src/components/ui/**`) and `no-inline-query-keys`. Tokens/signifier files live OUTSIDE `src/components/ui/`, so the primitive-purity rule does not constrain them — but still must not import workflow-domain types.
- **Stock shadcn primitives are relaxed** via a scoped `src/components/ui/**` override block in `eslint.config.js`. Your new files (`src/lib/state-signifiers.ts`, `src/styles/README.md`, `tools/contrast/*`) are app/tooling code → **full strict ESLint ruleset applies**; run `npm run lint` and `npm run format:check`.
- **`lucide-react@^1.16.0`** is the icon library (signifier map icons). Already a dep.
- **🔴 Cross-platform lockfile lesson (cost 4 CI rounds in 2.1, re-confirmed in 2.2):** any lockfile change → full `npm install` with module-local npm 10.8.2 (never `--package-lock-only`, never ambient npm) + Linux verification before push. This story should add **no** deps, so ideally no lockfile change at all (Task 8). Memory: `frontend-lockfile-cross-platform`, `verify-ci-fixes-in-clean-env`.
- **Git/commit hygiene:** `_bmad-output/` + tooling dirs (`.m2/`, `.mvn-home/`, etc.) are **untracked by convention** — stage explicit `deliveryline-frontend/` paths; never `git add .`. Omit the Claude co-author trailer (memory `commit-no-claude-coauthor`).
- **CI gates to keep green:** `format-static-checks`, `frontend-build-tests` (ubuntu + windows), `foundation-gate`. The new contrast/signifier tests join the enforced frontend path.

### Architecture-prescribed structure (architecture.md)

- `src/styles/globals.css` — the single global stylesheet for Tailwind directives + CSS vars (architecture.md:1089-1090). Token vars live here.
- `src/styles/README.md` — new (AC7).
- `src/lib/` — shared non-domain utilities (`utils.ts` `cn()` already here); `state-signifiers.ts` fits here.
- `tools/` — repo tooling/tests (existing `tools/eslint-rules/`); add `tools/contrast/`.
- `src/components/ui/` — **off limits** (primitives stay stock; architecture.md:488, ux:587).
- `src/features/workflows/` — domain layer; token/signifier files must not import from it.

### UX-DR2 color requirements (authoritative for AC1/AC2/AC6/AC7)

From the UX spec "Visual Design Foundation → Color System / State Semantics" (ux:752-894):
- Neutral, calm, operationally clear; **low-saturation surfaces** so review content stays primary (ux:754, 873).
- **Blue-green/teal** as the primary interactive accent (ux:758).
- Strong semantic colors for blockers, warnings, **stale**, **recovery**, approval status (ux:759).
- **Warning + blocker must be strong, obvious, and visually dominant** over informational (ux:771, 894) → AC6.
- **Semantic state never by color alone** — pair with icon/label/pattern (ux:815, 894) → AC5.
- Accent used **deliberately and sparingly, not as ambient decoration** (ux:874) → AC7 wording.
- Visual decision priority: **semantic clarity > scanability > visual consistency > stylistic nuance** (ux:865).
- The 10 base states (ux:881-893) + `stale` + `recovery` (ux:759, 769) = the 12 in AC2.

### Anti-patterns to avoid

- **Do NOT** overwrite shadcn's `--accent`/`--accent-foreground` with teal (breaks primitive hover surfaces — see collision note).
- **Do NOT** edit any `src/components/ui/*` primitive (2.2 AC4/AC7).
- **Do NOT** add typography/spacing/layout/focus-ring tokens or layout primitives — that's **2.4**.
- **Do NOT** build composites or wire real routes — 2.15-2.19 / 2.5.
- **Do NOT** add Vitest / Playwright / any test or visual-regression framework — 2.27. Use `node --test`.
- **Do NOT** add a runtime color/contrast dependency — hand-roll the WCAG math.
- **Do NOT** duplicate token values into a second JS/JSON file — parse `globals.css` (single source of truth).
- **Do NOT** use bright/consumer/loud teal or gradients (ux:762, 877).
- **Do NOT** regenerate the lockfile with `--package-lock-only`, skip Linux verification, or `git add .`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.3] — authoritative ACs (lines 890-905)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.2 / 2.4 scope boundaries] — tokens = 2.3/2.4 split (lines 883, 907-922)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Color System] — neutral/teal palette, semantic mapping (lines 752-771)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#State Semantics] — 10 states + dominance + no-color-alone (lines 879-894)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Non-Negotiable Visual Rules / Visual Decision Rules] — accent sparingly, clarity>nuance (lines 861-877)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Customization Strategy + Foundation Boundary] — what to customize via tokens (lines 556-597)
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture] — shadcn+Tailwind, minimal customization (lines 445-490)
- [Source: deliveryline-frontend/src/styles/globals.css] — stock shadcn slate vars THIS story replaces (lines 5-63)
- [Source: deliveryline-frontend/tailwind.config.ts] — existing color mappings to extend, not replace (lines 26-66)
- [Source: _bmad-output/implementation-artifacts/2-2-...-primitive-inventory.md] — primitive layer, strict TS, alias, eslint scoped override, lockfile discipline
- [Source: deliveryline-frontend/package.json#scripts] — `lint:rules-test` = `node --test` tooling pattern to mirror for AC4/5/6
- [Source: project memory `frontend-lockfile-cross-platform`, `verify-ci-fixes-in-clean-env`, `commit-no-claude-coauthor`]

### Resolved decisions (confirmed by Alex 2026-05-20 — no open clarifications remain)

- **Q1 (`--primary` → teal): YES.** Re-tone `--primary`/`--primary-foreground` to a brand-family teal so stock primitive buttons render as the UX-DR2 primary-interactive color with zero primitive edits. (Task 1.)
- **Q2 (teal family naming): `brand`.** Name the teal family `--brand-50…900` (CSS) + `colors.brand` (Tailwind utility) — NOT `accent`. shadcn's `--accent`/`colors.accent` stays untouched (only its value is re-toned neutral). State group: `bg-state-*` / `text-state-*-foreground` / `border-state-*-border` / `*-state-*-hc`. **Documented AC1 deviation** (`--accent-50…900` → `--brand-50…900`) — record in README + Completion Notes. (Tasks 1, 3, 7.)
- **Q3 (AC6 visual-regression): DEFER pixel-VR.** Satisfy AC6 with the `node --test` prominence proxy + playground token gallery; defer Playwright/Chromatic pixel snapshots to 2.27 / 2.25. Do NOT add VR tooling here. (Task 6.)
- **Q4 (dark `.dark` values): YES, provisional.** Populate `.dark` with structurally-parallel provisional values (dark mode wired-not-activated per 2.2 AC8); a future post-MVP story tunes them. AC8 requires structure, not tuned dark values. (Tasks 1, 2.)

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m] (Claude Opus 4.7, 1M context)

### Debug Log References

- `npm run check:contrast` → 7/7 pass (contrast + prominence + signifier parity).
- `npm run lint` → no issues; `npm run format:check` → clean; `npm run build` → green (CSS bundle 37.66 kB).
- Linux Docker `node:20.19.0`: `npm ci && npm run build && npm run check:contrast && npm run lint:rules-test` → all green.
- `./mvnw -B -ntp -DskipTests clean install` → BUILD SUCCESS (4/4 modules); `npm-run-check-contrast` execution ran 7 tests on the enforced Maven path; backend jar embeds `BOOT-INF/classes/static/{index.html,assets/index-*.js,index-*.css}`.

### Completion Notes List

- **Layer-2 color tokens delivered without touching any primitive** (2.2 AC4 preserved). The DeliveryLine palette is shipped purely by re-toning shadcn CSS variables in `globals.css` and adding new token layers; no file under `src/components/ui/**` was edited.
- **AC1 deviation `--accent-50…900` → `--brand-50…900` (intentional, accepted, Q2).** The teal interactive family is named `brand` to avoid colliding with shadcn's existing `--accent` muted-hover-surface semantic, which primitives depend on. shadcn `--accent`/`colors.accent` is left untouched (only re-toned neutral). AC1's *intent* (a teal interactive family exposed as utilities) is fully met; only the token name changed. Documented in `src/styles/README.md` and the frontend README.
- **`--primary`/`--ring` re-toned to brand teal (Q1)** so stock primitive buttons + focus ring render as the UX-DR2 primary-interactive color with zero primitive edits.
- **12 semantic state groups** (`informational`, `success`, `warning`, `blocker`, `draft`, `selected`, `loading`, `error`, `permission-restricted`, `empty`, `stale`, `recovery`), each with fill / `-foreground` / `-border` / `-hc` / `-hc-foreground`, all mirrored in a provisional `.dark` block (AC8 structure parity; dark mode wired-not-activated, values placeholder).
- **Single source of truth:** all three node --test gates parse the shipped `globals.css` — no parallel JS/JSON token copy that could drift. WCAG math is hand-rolled (no new dependency).
- **Contrast gate (AC4) pair semantics:** every text-on-fill pair (`--X-foreground` vs `--X`, incl. `--state-*-foreground`/`--state-*-hc-foreground`) is asserted ≥ 4.5:1; `--text-tertiary` (de-emphasized/large text) ≥ 3:1; every `--state-*-border` is asserted ≥ 3:1 vs `--background` (WCAG 1.4.11 component-boundary, since light tinted fills are < 3:1 vs the page). The neutral hairline `--border` is intentionally not gated (decorative divider). A negative self-test proves the gate isn't a no-op.
- **Dominance proxy (AC6):** prominence = fill saturation + |fill lightness − background lightness|; the test asserts min(blocker, warning) > max(informational, draft, empty) and that blocker/warning carry a visible (≥ 3:1) border. **Pixel-level visual regression deferred to 2.27/2.25 (Q3)** — logged in `deferred-work.md`; the playground token gallery is the human-reviewable fixture.
- **Signifier map (AC5):** `src/lib/state-signifiers.ts` exports the canonical `StateName` union + `STATE_NAMES` + an exhaustive `Record<StateName, StateSignifier>` (lucide-react icon name + label). `state-signifiers.test.js` enforces 1:1 token↔signifier parity and non-empty icon/label.
- **Dark `.dark` values (Q4):** populated as structurally-parallel provisional placeholders; not contrast-gated; a future post-MVP story tunes them.
- **No dependency / lockfile change:** only `package.json` `scripts` gained `check:contrast`; `npm ci` confirmed green on Linux and the Maven path.

### File List

- `deliveryline-frontend/src/styles/globals.css` (modified — neutral re-tone, `--brand-*`, `--primary`/`--ring` teal, named surfaces, 12 state groups, provisional `.dark` parity)
- `deliveryline-frontend/tailwind.config.ts` (modified — added `surface`/`surface-elevated`/`text`/`brand`/`state` color groups; shadcn mappings untouched)
- `deliveryline-frontend/src/lib/state-signifiers.ts` (new — `StateName` union, `STATE_NAMES`, `STATE_SIGNIFIERS`)
- `deliveryline-frontend/tools/contrast/contrast.js` (new — HSL→RGB→WCAG luminance/contrast helpers)
- `deliveryline-frontend/tools/contrast/parse-globals.js` (new — `:root` parser / state-name discovery, single source of truth)
- `deliveryline-frontend/tools/contrast/__tests__/token-contrast.test.js` (new — AC4 contrast gate + negative self-test)
- `deliveryline-frontend/tools/contrast/__tests__/token-prominence.test.js` (new — AC6 dominance proxy)
- `deliveryline-frontend/tools/contrast/__tests__/state-signifiers.test.js` (new — AC5 token↔signifier parity)
- `deliveryline-frontend/src/routes/_dev/PrimitivesPlayground.tsx` (modified — neutral/brand ramps + 12 standard + high-contrast state badges)
- `deliveryline-frontend/src/styles/README.md` (new — AC7 design-token documentation)
- `deliveryline-frontend/README.md` (modified — cross-link to color-token docs in the Design System section)
- `deliveryline-frontend/package.json` (modified — added `check:contrast` script)
- `deliveryline-frontend/pom.xml` (modified — `npm-run-check-contrast` execution on the enforced frontend-maven-plugin path)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — logged pixel-VR deferral to 2.27)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — story status tracking)

## Change Log

- 2026-05-20: Story 2.3 implemented — Layer-2 color design tokens (neutral surfaces, teal `--brand-*` family, 12 semantic state groups with high-contrast variants), Tailwind utility exposure, `node --test` contrast/prominence/signifier gates wired into the Maven/CI path, signifier map, dev playground token gallery, and `src/styles/README.md`. All 8 ACs satisfied (AC1 ships as documented `--brand-*` deviation; AC6 pixel-VR deferred to 2.27 per Q3). Status: ready-for-dev → in-progress → review.
