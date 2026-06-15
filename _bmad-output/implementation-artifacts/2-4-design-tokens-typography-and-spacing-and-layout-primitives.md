# Story 2.4: Design Tokens — Typography + Spacing + Layout Primitives

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **a typography hierarchy + hybrid 4px/8px spacing system + layout primitives implemented as design tokens**,
so that **composites have consistent reading rhythm, scanning density, and layout structure — supporting long-form spec reading and rapid scanning without dramatic stylistic contrast**.

## ⚠️ Read first — what this story is and is NOT

This story delivers the **non-color half of Layer 2 (Design Tokens)** of the design-system three-layer model (ux:1756-1780). Story 2.3 shipped the **color** half (neutral surfaces, teal `--brand-*`, 12 semantic state groups). This story adds **typography, spacing, layout primitives, the `prose` reading utility, and the global focus-ring token** — the structural/rhythm tokens that composites (2.15-2.19) and the app shell (2.7) consume.

- **No color tokens.** Color is **done in 2.3** — do NOT add/edit `--brand-*`, `--state-*`, `--surface*`, `--text-primary/secondary/tertiary`, or re-tone shadcn neutrals. You **consume** `--text-secondary`/`--text-tertiary` in semantic typography classes (for muted metadata/captions) but do not redefine them. The only color-shaped token this story ADDS is `--ring-focus` (focus-visible ring; AC6).
- **No primitive edits.** Do NOT edit `src/components/ui/*` (2.2 AC4/AC7; architecture.md:488; ux:587). Stock shadcn primitives must keep rendering byte-identically. **This drives the Q1 decision below: define a semantic typography class layer and DO NOT override Tailwind's default `fontSize` keys** (`text-xs/sm/base/lg/xl`) that primitives already consume.
- **No composites, no routes, no shell.** Queue Item / Run Context Strip / ARP are 2.15-2.19; the tri-pane shell is 2.7; routing is 2.5. This story produces tokens + 5 layout primitives + the `prose` utility + a token-presence test, and demonstrates them in the existing dev-only `PrimitivesPlayground`. It builds no product UI.
- **No new test runner, no new runtime dependency.** Vitest/Playwright arrive in 2.27. Use the project's **already-established `node --test` tooling pattern** (mirrors `lint:rules-test` + `check:contrast`). Do **NOT** add `@tailwindcss/typography` for the `prose` utility — hand-roll it (a new dep = a lockfile/native-binding round-trip; see Task 8 + memory `frontend-lockfile-cross-platform`).
- **Layout primitives are React components, verified like 2.2's primitives** (playground + `tsc -b`/`vite build`). Component-level unit tests defer to 2.27 — the `node --test` gate in this story covers the **token layer** (CSS var presence/shape + prose constraints), not the React components.

## Acceptance Criteria

1. **Given** typography tokens in `globals.css`, **Then** font stack (`--font-sans` = system modern sans-serif), font-size scale (`--text-xs` through `--text-2xl`), line-height scale (`--leading-tight` / `--leading-normal` / `--leading-relaxed`), and font-weight scale (`--weight-regular` / `--weight-medium` / `--weight-semibold` / `--weight-bold`) are defined.
2. **Given** typography hierarchy per UX-DR3, **Then** semantic classes exist for: page/panel title (h1 equivalent), workflow state / section heading (h2/h3), artifact body content (prose reading size), metadata / captions / secondary labels (smaller, muted), inline status / annotation (smallest, often bold or colored per state).
3. **Given** the hybrid spacing system per UX-DR4, **Then** Tailwind's spacing scale is configured to expose both a `4px` step (`space-0.5`, `space-1`, `space-1.5`, `space-2.5`, etc. — used for control internals, compact metadata, dense review rows) and an `8px` step (`space-2`, `space-4`, `space-6`, `space-8` — used for panel spacing, section separation, larger layout structure).
4. **Given** reading surfaces for long-form spec content (Artifact Review Panel in story 2.17), **Then** a documented `prose` utility applies readable line length (45–75 characters), comfortable line-height (≥ 1.5), and appropriate paragraph spacing.
5. **Given** layout primitive components (`src/components/layout/`), **Then** baseline primitives exist: `Stack` (vertical flex with gap), `Inline` (horizontal flex with gap), `Grid` (simple CSS grid), `Container` (max-width with horizontal padding), `Divider` (semantic section break) — each accepts spacing tokens as props.
6. **Given** focus-visible styling, **Then** a global focus ring token (`--ring-focus`) is defined and applied consistently via Tailwind's `focus-visible:ring-*` utilities — not default browser outlines, and high-contrast enough to satisfy WCAG 2.4.7.
7. **Given** adaptive density (UX-DR — medium-density default, compact for quick scanning, expanded for sustained reading), **Then** a `density` prop pattern is documented for composites (e.g., Queue Item in story 2.15 supports `compact | standard` density) using the 4px scale for compact and 8px for standard.
8. **Given** the tokens are ready, **Then** the primitives-playground route (story 2.2 AC6) renders typographic hierarchy + spacing scale + layout primitives so visual regressions are spottable before composite stories begin.

## Tasks / Subtasks

- [x] **Task 1: Define typography tokens in `globals.css` `:root`** (AC: 1)
  - [x] Add to the existing `:root` block (do NOT touch the 2.3 color tokens): a system sans stack `--font-sans` (e.g. `ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`) — plain-utilitarian, no branded/web font (ux:777-779).
  - [x] Font-size scale `--text-xs` … `--text-2xl` in **`rem`** (e.g. `--text-xs: 0.75rem; --text-sm: 0.875rem; --text-base: 1rem; --text-lg: 1.125rem; --text-xl: 1.25rem; --text-2xl: 1.5rem;`). These are NEW vars; they do **not** override Tailwind's built-in `text-*` utilities (Q1).
  - [x] Line-height scale `--leading-tight` (≈1.25), `--leading-normal` (≈1.5), `--leading-relaxed` (≈1.625). The body/prose path must land ≥ 1.5 (AC4).
  - [x] Font-weight scale `--weight-regular: 400; --weight-medium: 500; --weight-semibold: 600; --weight-bold: 700;` (unitless).
  - [x] These tokens are **color-agnostic** → they will NOT be picked up by the `parseRootColorVars` HSL-triplet filter, so `check:contrast` stays unaffected. (Verify by running `npm run check:contrast` after — it must stay 8/8.)
  - [x] Set `--font-sans` as the document font: apply `font-family: var(--font-sans)` on `body` (in the existing `@layer base { body { … } }` block) AND expose it as `theme.extend.fontFamily.sans` in `tailwind.config.ts` so `font-sans` and default Tailwind text utilities resolve to it. Do NOT add `--font-sans` to `:root` as an HSL var (it's a font stack, not a color).

- [x] **Task 2: Semantic typography class layer (AC: 2)** — Q1 RESOLVED: semantic classes, Tailwind defaults untouched
  - [x] In `globals.css` `@layer components`, define the 5-level hierarchy from UX-DR3 (ux:779-786) as composable classes that read from the Task 1 tokens:
    - `.text-page-title` — page/panel title (h1 equiv): `--text-2xl` + `--leading-tight` + `--weight-semibold`, `color: hsl(var(--text-primary))`.
    - `.text-section-heading` — workflow-state / section heading (h2/h3): `--text-lg`/`--text-xl` + `--leading-tight` + `--weight-semibold`.
    - `.text-body` — artifact body / prose reading size: `--text-base` + `--leading-relaxed` + `--weight-regular`, `color: hsl(var(--text-primary))`.
    - `.text-meta` — metadata / captions / secondary labels: `--text-xs`/`--text-sm` + `--weight-regular`, `color: hsl(var(--text-secondary))` (muted).
    - `.text-annotation` — inline status / annotation (smallest, often bold or per-state color): `--text-xs` + `--weight-medium`/`--weight-semibold`; color left to the consumer (composites pair it with a `--state-*` color + the 2.3 signifier — never color alone, ux:894).
  - [x] **CRITICAL — do NOT override Tailwind's `theme.fontSize`** keys (`xs/sm/base/lg/xl/2xl`). Stock shadcn primitives (`button`, `badge`, `input`, …) consume `text-sm`/`text-xs` directly; repointing those keys at `--text-*` would silently change every primitive's rendered size and breaks the "primitives render identically" guarantee (2.2 AC4). The semantic classes above are additive and used by **composites/app code**, not by primitives.
  - [x] Map metadata/secondary color to the 2.3 `--text-secondary`/`--text-tertiary` vars (consume, don't redefine).

- [x] **Task 3: Hybrid 4px/8px spacing system (AC: 3, 7)** — do NOT rebuild Tailwind's scale
  - [x] **Tailwind v3's default spacing scale already encodes the hybrid rhythm** (`space-0.5`=2px, `space-1`=4px, `space-1.5`=6px, `space-2`=8px, `space-2.5`=10px, `space-4`=16px, `space-6`=24px, `space-8`=32px — base unit 0.25rem = 4px). **Do NOT redefine `theme.spacing`** (a wholesale override would drop the rest of the scale and break shadcn primitives that rely on default `p-*`/`gap-*`/`m-*`). The work here is to **codify and document the convention**, not to invent new numbers.
  - [x] Document the convention in `src/styles/README.md`: **4px step** (`0.5/1/1.5/2.5`) → control internals, compact metadata groups, dense review rows; **8px step** (`2/4/6/8`) → panel spacing, section separation, larger layout structure (ux:792-794, UX-DR4).
  - [x] If — and only if — a genuinely-missing step is needed, add it **additively** under `theme.extend.spacing` (do not replace). Default scale almost certainly suffices; prefer documentation over new tokens.
  - [x] **Density pattern (AC7):** document the `density: 'compact' | 'standard'` prop convention composites will adopt (e.g. Queue Item 2.15, ARP 2.17, Run Context Strip 2.16): `compact` uses the 4px step for internal padding/gaps; `standard` uses the 8px step. This is a **documented convention + a tiny shared helper** (e.g. `src/lib/density.ts` exporting `densityGap(density)` → `'gap-1' | 'gap-4'` literal classes) so composites don't re-derive it. Keep it generic (no workflow-domain imports). Reference epics.md:1199, 1206 (Queue Item density), and the layout-primitive gap union (Task 5).

- [x] **Task 4: `prose` reading utility (AC: 4)** — hand-rolled, NOT `@tailwindcss/typography`
  - [x] In `globals.css` `@layer components`, define a `.prose` class for long-form artifact reading (consumed by ARP, story 2.17): `max-width: 70ch` (within the 45–75ch readable-line-length band, ux:839 sustained readability), `line-height: var(--leading-relaxed)` (≥ 1.5, AC4), comfortable paragraph spacing (e.g. `> * + *` vertical rhythm on the 8px step), `color: hsl(var(--text-primary))`, base size `--text-base`.
  - [x] Do **NOT** install `@tailwindcss/typography` (new dep → lockfile/native-binding round-trip; Task 8). The hand-rolled class is ~10 lines and avoids the dependency-graph change.
  - [x] The `node --test` gate (Task 6) asserts `.prose` encodes a `ch`-based max-width in the 45–75 band AND a line-height ≥ 1.5, so a future edit can't silently break readability.

- [x] **Task 5: Layout primitives in `src/components/layout/` (AC: 5)** — Q3 RESOLVED: closed gap-token union → literal classes
  - [x] Create `src/components/layout/` (NEW dir; NOT `src/components/ui/` — these are app-level layout helpers, full strict ESLint applies, the `no-workflow-domain-in-ui-primitives` rule is scoped to `ui/**` so it does not constrain this dir, but **keep them generic** — no `src/features/workflows/` imports, no domain types).
  - [x] `Stack` — vertical flex (`flex flex-col`) with a `gap` prop. `Inline` — horizontal flex (`flex flex-row items-center` default) with `gap` + optional `wrap`/`align`/`justify`. `Grid` — `grid` with `cols` + `gap`. `Container` — `mx-auto` max-width + horizontal padding (consume Tailwind `container` config already in `tailwind.config.ts:19-25`, or a `max-w-*` + `px-*`). `Divider` — semantic `<hr role="separator">` (or `<div role="separator">`) using `--border`, with `orientation: 'horizontal' | 'vertical'`.
  - [x] **Spacing prop API (Q3): closed union of spacing-token names mapped to LITERAL Tailwind classes** so Tailwind's content-purge keeps them. Tailwind cannot see dynamically-constructed class strings (`` `gap-${n}` `` gets purged), so use a static lookup:
    ```ts
    type GapToken = '0' | '0.5' | '1' | '1.5' | '2' | '2.5' | '3' | '4' | '6' | '8';
    const GAP_CLASS: Record<GapToken, string> = {
      '0': 'gap-0', '0.5': 'gap-0.5', '1': 'gap-1', '1.5': 'gap-1.5',
      '2': 'gap-2', '2.5': 'gap-2.5', '3': 'gap-3', '4': 'gap-4', '6': 'gap-6', '8': 'gap-8',
    };
    ```
    `<Stack gap="4">` (8px-step) / `<Inline gap="1">` (4px-step). Type-safe + purge-safe + enforces the hybrid scale. Use the existing `cn()` helper (`src/lib/utils.ts`) to compose classes; forward `className` + `...rest` and use `React.forwardRef` so composites can ref/extend (match the shadcn primitive ergonomics — see `src/components/ui/card.tsx` for the house pattern).
  - [x] Compile clean under strict TS (`tsconfig.app.json`: `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`). For `exactOptionalPropertyTypes`, optional props that may be `undefined` must be typed/handled accordingly (the 2.2 dropdown-menu `{...props}` lesson). Import via the `@/*` alias where useful.

- [x] **Task 6: Focus-ring token + token-presence `node --test` gate (AC: 6, AC1-AC4 enforcement)** — Q2 RESOLVED: add the gate
  - [x] **`--ring-focus` (AC6):** add an HSL-triplet `--ring-focus` to `:root` (and a provisional `.dark` value, mirroring 2.3's AC8 structure-parity discipline). Make it high-contrast enough for WCAG 2.4.7 — reuse/relate to the brand teal but ensure it reads against both surface and elevated surfaces. Expose it as a Tailwind ring color (`theme.extend.colors.ring-focus` → `'hsl(var(--ring-focus))'`, or a `ringColor` extension) so app/composite code applies `focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2` instead of browser default outlines.
    - **Do NOT edit primitives to swap their ring.** shadcn primitives already use `focus-visible:ring-ring` (the 2.3 teal `--ring`); leave them. `--ring-focus` is the **canonical token for layout primitives, composites, and app-authored interactive elements**. Document in `src/styles/README.md` that `--ring-focus` is the project focus-ring token and `--ring` is the shadcn-internal primitive ring (they may share a value, but composite/app code references `ring-focus`).
    - **Contrast-gate note:** `--ring-focus` is an HSL triplet, so `parseRootColorVars` will include it — but the contrast test only pairs `*-foreground` tokens with their base and only gates `--state-*-border`, so a lone `--ring-focus` (no `-foreground` partner, not a `--state-*-border`) is **not** gated and won't break `check:contrast`. Confirm `npm run check:contrast` stays 8/8.
  - [x] **Token-presence/shape gate (Q2):** add `tools/tokens/__tests__/typography-tokens.test.js` (+ reuse `tools/contrast/parse-globals.js` `parseRootVars` — it returns ALL `:root` vars, color or not) asserting:
    - (a) every required token exists: `--font-sans`, `--text-xs..2xl`, `--leading-tight/normal/relaxed`, `--weight-regular/medium/semibold/bold`, `--ring-focus`.
    - (b) shape: `--text-*` end in `rem`/`em`/`px`; `--leading-*` are unitless or have valid units and the relaxed value is ≥ 1.5; `--weight-*` are 100-900 multiples of 100; `--ring-focus` matches the HSL triplet shape.
    - (c) the `.prose` rule in `globals.css` declares a `ch`-based `max-width` within 45–75 and `line-height` ≥ 1.5 (parse the `@layer components` block for the `.prose { … }` rule — a small regex/brace-walk like `extractRootBlock`).
    - (d) a negative self-test proving the gate fails when a required token is absent / mis-shaped (so it can't silently no-op — same discipline as 2.3 Task 4 / 2.2 Task 4).
  - [x] Add `"check:tokens": "node --test tools/tokens/__tests__/typography-tokens.test.js"` to `package.json` scripts AND **wire it into `pom.xml`'s frontend-maven-plugin** as a new execution (mirror the existing `npm-run-check-contrast` / `lint:rules-test` executions) so it runs on the enforced Maven/CI path (`frontend-build-tests`), not just locally. Verify the execution fires in the reactor build.

- [x] **Task 7: Extend `PrimitivesPlayground` + document tokens (AC: 8, supports AC2/3/5/6)**
  - [x] Extend the existing dev-only `src/routes/_dev/PrimitivesPlayground.tsx` (story 2.2; mounted via `import.meta.env.DEV` + `?playground`) with a token-gallery section rendering: (a) the **typographic hierarchy** — each of the 5 semantic classes with sample text (page title → annotation); (b) the **spacing scale** — visual swatches for the 4px and 8px steps side-by-side so off-grid regressions are spottable; (c) the **layout primitives** — `Stack`/`Inline`/`Grid`/`Container`/`Divider` each rendered at a couple of gap values; (d) a `.prose` block with realistic long-form sample paragraphs (so the 45-75ch line length + line-height are eyeballable); (e) focus-ring demonstration — a focusable element using `focus-visible:ring-ring-focus` (tab to it to see the ring). Keep it purely presentational (no Query/data), consistent with 2.2/2.3 playground discipline.
  - [x] Update `src/styles/README.md` (created in 2.3) with a **Typography + Spacing + Layout** section: the typography hierarchy + which class to use where; the 4px/8px hybrid convention + density pattern; the `prose` utility usage + constraints; the layout-primitive prop API (gap-token union); and the `--ring-focus` focus-ring token + when to use `ring-focus` vs shadcn `ring-ring`. Keep the cross-link from `deliveryline-frontend/README.md`'s Design System section intact/extended.

- [x] **Task 8: Cross-platform lockfile + reactor/CI verification** (cross-cutting; LOAD-BEARING per Epic 1 retro + stories 2.1/2.2/2.3)
  - [x] This story adds **no new runtime dependency** (layout primitives use React + `cn()` already present; `prose` is hand-rolled; the token gate is pure Node built-ins). **No `package.json` dependency change is expected** — only `scripts` gains `check:tokens`. If you find yourself reaching for `@tailwindcss/typography` or any color/spacing lib, STOP — hand-roll it (the exact 2.3 Task 8 discipline).
  - [x] If only `package.json` `scripts` changed (no dependency delta), the lockfile typically does not need regeneration — confirm `npm ci` still succeeds. If a dep is genuinely unavoidable, regenerate `package-lock.json` with a **full `npm install`** using module-local pinned npm 10.8.2 (NOT `--package-lock-only`, NOT ambient npm) and re-verify on Linux (memory `frontend-lockfile-cross-platform`; cost 4 CI rounds in 2.1).
  - [x] **Verify on Linux** before done: `docker run --rm -v "<frontend>:/app" -w /app node:20.19.0 bash -c "npm ci --no-audit --no-fund --prefer-offline && npm run build && npm run check:contrast && npm run check:tokens && npm run lint && npm run lint:rules-test && npm run format:check"` → all green. (Use an anonymous `node_modules` volume on Windows hosts, as in 2.3.)
  - [x] Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` green (all 4 modules); backend jar still embeds the SPA at `BOOT-INF/classes/static/`. Confirm the new `check:tokens` test runs on the enforced frontend-maven-plugin path and the `frontend-build-tests` matrix (ubuntu + windows) + `format-static-checks` + `foundation-gate` stay green.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** This is a frontend tokens/primitives/config/docs story. It introduces **no JVM application-service classes, no domain exceptions, no SPI calls, no state transitions** — so the SLF4J/MDC logging standard (entry/exit INFO, WARN typed-rejection, ERROR unhandled, `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity` keys) has no application code to instrument here. The standard remains in force for any future JVM code.
  - [x] Frontend client-side structured logging is out of scope (arrives with later stories). The `node --test` tooling may use plain test-reporter/assertion output only — no `System.out`/`printStackTrace()` (no JVM code exists here). Layout primitives must not `console.*` in render.

## Dev Notes

### Story scope — non-color half of Layer 2 (the three-layer design system)

The design system has three layers (ux:1756-1780): **(1) Foundation primitives** (stock shadcn — done in 2.2), **(2) Design tokens** (color = 2.3 done; **typography/spacing/layout/focus = this story**), **(3) Workflow composites** (2.15-2.19, shell 2.7). This story completes Layer 2's structural vocabulary so composites have consistent reading rhythm, scanning density, and layout scaffolding. Product character comes from *state semantics + composition + consistent rhythm*, not restyled primitives (UX-DR2/UX-DR5; ux:587; architecture.md:488). Visual decision priority remains **semantic clarity > scanability > visual consistency > stylistic nuance** (ux:865).

### Resolved decisions (confirmed by Alex 2026-05-21 — bake these in, no open clarifications)

- **Q1 (typography exposure): semantic classes, Tailwind defaults untouched.** Define `--font-sans`/`--text-*`/`--leading-*`/`--weight-*` CSS vars + a `@layer components` semantic class layer (`.text-page-title`/`.text-section-heading`/`.text-body`/`.text-meta`/`.text-annotation`). **Do NOT override Tailwind's `theme.fontSize` keys** — stock primitives consume `text-sm`/`text-xs` and must render identically (2.2 AC4). Composites/app code use the semantic classes. (Tasks 1, 2.)
- **Q2 (token verification): add a `node --test` token-presence/shape gate.** New `tools/tokens/__tests__/typography-tokens.test.js`, wired into frontend-maven-plugin like `check:contrast`, asserting required tokens exist + are well-shaped + `.prose` encodes line-length (45-75ch) + line-height ≥ 1.5, with a negative self-test. Layout React primitives are still verified via the playground + `tsc` (component tests defer to 2.27). (Task 6.)
- **Q3 (layout prop API): closed gap-token union → literal Tailwind classes.** Props take a closed `GapToken` union mapped through a static `Record` to literal `gap-*` strings (purge-safe + type-safe + enforces the 4px/8px scale). No arbitrary numeric / inline-style spacing. (Task 5.)

### Previous Story Intelligence (Story 2.3 — done; Story 2.2 — done)

Source: `2-3-design-tokens-color-palette-and-semantic-state-variables.md`, `2-2-tailwind-and-shadcn-ui-setup-and-primitive-inventory.md`. Key state this story builds on:

- **`globals.css` is the single source of truth + the `:root` block is parsed by the `node --test` gates.** The `tools/contrast/parse-globals.js` parser (`parseRootVars` = all vars, `parseRootColorVars` = HSL-triplet-only) is reusable — import `parseRootVars` for the typography gate. The parser strips comments and brace-walks `:root` (it deliberately ignores `.dark`). Your typography/weight/leading tokens are non-HSL → invisible to `parseRootColorVars`, so `check:contrast` is unaffected; `--ring-focus` IS an HSL triplet but isn't a gated pair (see Task 6).
- **`check:contrast` runs 8 tests today** (per sprint-status 2026-05-20: "8/8"). Keep it green; do not perturb the 2.3 color tokens.
- **Strict TS** (`tsconfig.app.json`): `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`. Layout primitives + `density.ts` must compile clean. `noUncheckedIndexedAccess` means `GAP_CLASS[gap]` is `string | undefined` unless the key type is the exact union — type the lookup as `Record<GapToken, string>` and the prop as `GapToken` so indexing is total.
- **`@/*` → `./src/*` alias** is live (2.2: `tsconfig.app.json` paths + `vite.config.ts` resolve.alias). Import `cn` via `@/lib/utils`.
- **`cn()` helper** (`src/lib/utils.ts`, clsx + tailwind-merge) is the house className-composition utility — use it in every layout primitive (forward `className`, merge last).
- **ESLint:** stock `src/components/ui/**` is relaxed via a scoped override; **everything else (incl. `src/components/layout/`, `src/lib/`, `tools/`) gets the full strict ruleset.** Run `npm run lint` (`--max-warnings=0`) + `npm run format:check`. The `no-workflow-domain-in-ui-primitives` rule is scoped to `ui/**` only — it does not police `layout/`, but keep layout primitives domain-free anyway (architecture: layout helpers are generic).
- **`forwardRef` + `...props` spread** is the established primitive ergonomic (see `src/components/ui/card.tsx`). Match it so composites can ref and extend layout primitives.
- **Playground discipline:** `src/routes/_dev/PrimitivesPlayground.tsx` is dev-only (`import.meta.env.DEV` + `?playground`), purely presentational, derives gallery content from canonical sources where possible (2.3 derived state badges from `STATE_NAMES`). Keep that discipline.
- **🔴 Cross-platform lockfile lesson (cost 4 CI rounds in 2.1, re-confirmed 2.2/2.3):** any lockfile change → full `npm install` with module-local npm 10.8.2 (never `--package-lock-only`, never ambient npm) + Linux verification before push. This story should add **no** deps → ideally no lockfile change (Task 8). Memory: `frontend-lockfile-cross-platform`, `verify-ci-fixes-in-clean-env`.
- **Git/commit hygiene:** `_bmad-output/` + tooling dirs (`.m2/`, `.mvn-home/`, etc.) are untracked by convention — stage explicit `deliveryline-frontend/` + `_bmad-output/implementation-artifacts/` paths; never `git add .`. Omit the Claude co-author trailer (memory `commit-no-claude-coauthor`).
- **CI gates to keep green:** `format-static-checks`, `frontend-build-tests` (ubuntu + windows), `foundation-gate`. The new `check:tokens` joins the enforced frontend path alongside `check:contrast` + `lint:rules-test`.

### Architecture-prescribed structure (architecture.md)

- `src/styles/globals.css` — single global stylesheet for Tailwind directives + CSS vars (architecture.md:1089-1090). Typography/spacing/focus tokens live in `:root`; `.prose` + semantic typography classes live in `@layer components`.
- `src/styles/README.md` — extend (2.3 created it) with the typography/spacing/layout section (Task 7).
- `src/components/layout/` — **NEW** dir for `Stack`/`Inline`/`Grid`/`Container`/`Divider` (AC5). App-level layout helpers, generic, no domain imports.
- `src/lib/` — shared non-domain utilities (`utils.ts` `cn()`, `state-signifiers.ts`); `density.ts` fits here.
- `tools/tokens/` — **NEW** dir for the token-presence `node --test` gate (mirrors `tools/contrast/`, `tools/eslint-rules/`).
- `src/components/ui/` — **off limits** (primitives stay stock; architecture.md:488, ux:587).
- `src/features/workflows/` — domain layer; layout/token/lib files must not import from it.
- `tailwind.config.ts` — extend `fontFamily.sans` + `colors.ring-focus`; **leave `fontSize`, `spacing`, and all 2.2/2.3 mappings untouched** (Q1, Task 3).
- `pom.xml` — add a `check:tokens` frontend-maven-plugin execution (mirror `npm-run-check-contrast`).

### UX requirements (authoritative for AC1/2/3/4/6/7)

From the UX spec (ux:773-821, 896-904):
- **Typography (ux:773-786):** balanced reading + scanning, professional plain-utilitarian tone, common modern sans stack (no branded font), clear hierarchy across page/panel titles → state/section headings → artifact body → metadata/captions → inline status/annotation; support long-form reading AND rapid scanning without dramatic stylistic contrast.
- **Spacing/layout (ux:788-807):** medium-density, structured; hybrid 4px (tight internal/compact/dense) + 8px (panel/section/layout) rhythm; left-nav + main review pane + optional context panel (the shell is 2.7 — this story only supplies the spacing/layout *tokens & primitives* the shell composes).
- **Accessibility (ux:809-821):** readable font sizes + line-heights for long technical reading; **keyboard-friendly navigation and focus visibility** (→ AC6 `--ring-focus`, WCAG 2.4.7); semantic states never color-alone (the `.text-annotation` class must be paired with a 2.3 `--state-*` color + signifier by composites — never carry meaning by color alone).
- **Density rules (ux:896-904):** medium-density default, adaptive by task — compact metadata blocks for scanning, expanded reading areas for sustained attention (→ AC7 density pattern + `.prose`).

### Anti-patterns to avoid

- **Do NOT** override Tailwind's `theme.fontSize` keys with `--text-*` vars — silently resizes every stock primitive (Q1). Use semantic `@layer components` classes instead.
- **Do NOT** redefine/replace `theme.spacing` — drops the rest of Tailwind's scale and breaks primitives' `p-*`/`gap-*` (Task 3). Default scale already encodes the 4px/8px hybrid; document the convention, extend additively only if truly needed.
- **Do NOT** edit any `src/components/ui/*` primitive (2.2 AC4/AC7) — including swapping their focus ring. `--ring-focus` is for layout/composite/app code; primitives keep `ring-ring`.
- **Do NOT** install `@tailwindcss/typography` (or any color/spacing/contrast lib) — hand-roll `.prose` (Task 4) + keep the lockfile unchanged (Task 8).
- **Do NOT** build dynamic Tailwind class strings (`` `gap-${n}` ``) in layout primitives — Tailwind purge can't see them. Use the static `GAP_CLASS` literal lookup (Q3, Task 5).
- **Do NOT** touch the 2.3 color tokens (`--brand-*`, `--state-*`, `--surface*`, `--text-primary/secondary/tertiary`, re-toned shadcn neutrals) or perturb `check:contrast` (must stay 8/8).
- **Do NOT** add Vitest / Playwright / a React component test framework — that's 2.27. Use `node --test` for the token layer; verify primitives via the playground.
- **Do NOT** build composites, the tri-pane shell, or routes — 2.15-2.19 / 2.7 / 2.5.
- **Do NOT** regenerate the lockfile with `--package-lock-only`, skip Linux verification, or `git add .`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.4] — authoritative ACs (lines 907-922)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.3 / 2.2 scope boundaries] — tokens = 2.3 (color) / 2.4 (typography/spacing/layout) split (lines 883, 890-922)
- [Source: _bmad-output/planning-artifacts/epics.md] — downstream consumers: density (2.15, line 1199, 1206), Run Context Strip `Inline` (2.16, line 1222), ARP `prose` (2.17, line 1243), focus ring (2.25, line 1412)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Typography System] — hierarchy + plain-utilitarian sans (lines 773-786)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Spacing & Layout Foundation] — 4px/8px hybrid, left-nav shell (lines 788-807)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Accessibility Considerations] — focus visibility, readable sizes, no color-alone (lines 809-821)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Density and Review Scanning Rules] — adaptive density (lines 896-904)
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture] — shadcn+Tailwind, minimal customization, layout guardrails (lines 445-498)
- [Source: deliveryline-frontend/src/styles/globals.css] — `:root` (2.3 color tokens, do not touch) + `@layer base`/`@layer components` to extend
- [Source: deliveryline-frontend/tailwind.config.ts] — extend `fontFamily.sans` + `colors.ring-focus`; leave `fontSize`/`spacing`/2.2-2.3 mappings intact
- [Source: deliveryline-frontend/tools/contrast/parse-globals.js] — reuse `parseRootVars` for the typography gate; `parseRootColorVars`/`HSL_TRIPLET` show why non-color tokens don't perturb `check:contrast`
- [Source: deliveryline-frontend/tools/contrast/__tests__/token-contrast.test.js] — the negative-self-test discipline to mirror in `typography-tokens.test.js`
- [Source: deliveryline-frontend/src/components/ui/card.tsx] — house `forwardRef` + `cn()` + `...props` primitive pattern to match in layout primitives
- [Source: deliveryline-frontend/package.json#scripts] — `lint:rules-test` / `check:contrast` = `node --test` pattern to mirror for `check:tokens`
- [Source: _bmad-output/implementation-artifacts/2-3-...-semantic-state-variables.md] — color-token layer, single-source-of-truth gate pattern, `.dark` structure parity, lockfile discipline, playground discipline
- [Source: project memory `frontend-lockfile-cross-platform`, `verify-ci-fixes-in-clean-env`, `commit-no-claude-coauthor`]

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):** Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure; persistence writes → `INFO` + `WARN` on idempotency replay + `ERROR` on unmapped integrity violation; file/network I/O → `INFO` + `WARN` on retry + `ERROR` on unrecoverable failure; state-machine transitions → `INFO`; reconciliation/recovery loops → `INFO` per-batch + `WARN` per-item.
- **Required context keys** (MDC or structured params): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields — route through the existing redaction/classification path.
- **Test contract:** new logging surfaces must be pinned by a focused test (list-appender or `OutputCaptureExtension`).
- **This story's applicability:** frontend-only (tokens/primitives/config/docs) — **no JVM code is introduced**, so there is no application logging surface to instrument here. The standard remains binding for any future JVM code under this story's scope.

### Project Structure Notes

- New paths: `src/components/layout/{Stack,Inline,Grid,Container,Divider}.tsx`, `src/lib/density.ts`, `tools/tokens/__tests__/typography-tokens.test.js` (+ optional `tools/tokens/parse-css.js` helper if not reusing `tools/contrast/parse-globals.js`).
- Modified: `src/styles/globals.css` (typography/spacing/focus tokens + `@layer components` semantic + `.prose`), `tailwind.config.ts` (`fontFamily.sans` + `colors.ring-focus`), `src/routes/_dev/PrimitivesPlayground.tsx`, `src/styles/README.md`, `deliveryline-frontend/README.md` (cross-link), `package.json` (`check:tokens` script), `pom.xml` (frontend-maven-plugin execution), `sprint-status.yaml`.
- No conflicts with unified structure: layout helpers under `src/components/layout/` (distinct from stock `ui/`), tooling under `tools/` — consistent with `tools/eslint-rules/` + `tools/contrast/`.

## Dev Agent Record

### Review Findings

- [x] [Review][Patch] Token gates prefer stale emitted CSS over current source [deliveryline-frontend/tools/contrast/parse-globals.js:19]
- [x] [Review][Patch] `standard` density maps to 16px while docs and demo say 8px [deliveryline-frontend/src/lib/density.ts:17]
- [x] [Review][Patch] Vertical `Divider` collapses without a caller-supplied height [deliveryline-frontend/src/components/layout/Divider.tsx:15]

### Agent Model Used

claude-opus-4-7[1m] (Claude Opus 4.7, 1M context)

### Debug Log References

- Local sweep (Windows, module-local npm 10.8.2): `npm run build` (tsc -b + vite, 16 modules), `npm run check:tokens` 4/4, `npm run check:contrast` 8/8, `npm run lint` clean, `npm run lint:rules-test` 2/2, `npm run format:check` clean.
- **Token gate dist-parse bug found + fixed.** First `check:tokens` run against the emitted bundle reported `--ring-focus missing`. Root cause: CSS minifiers (esbuild/lightningcss in `vite build`) drop the trailing `;` on the LAST declaration of a block, and `--ring-focus` is now the last `:root` var; `parseRootVars`'s regex required a `;`, silently dropping the final declaration (a latent bug that also affected the last color token when the contrast gate parsed the dist bundle). Fixed by making the trailing `;` optional in `tools/contrast/parse-globals.js` (`[^;]+` already stops at the next `;` / block end). Strengthens both gates; `check:contrast` stays 8/8.
- Linux verification (memory `verify-ci-fixes-in-clean-env`): `docker run --rm node:20.19.0` with anonymous `node_modules` volume → `npm ci && build && check:contrast && check:tokens && lint && lint:rules-test && format:check` all green.
- Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` → all 4 modules BUILD SUCCESS; the new `npm-run-check-tokens` frontend-maven-plugin execution fires (`# pass 4`); backend jar embeds the SPA at `BOOT-INF/classes/static/` (incl. the token CSS).

### Completion Notes List

- **AC1 (typography tokens):** added `--font-sans` (system stack), `--text-xs..2xl` (rem), `--leading-tight/normal/relaxed`, `--weight-regular/medium/semibold/bold` to `globals.css` `:root`; wired `--font-sans` onto `body` + `theme.fontFamily.sans`. Tailwind `theme.fontSize` LEFT UNTOUCHED (Q1) — primitives render identically.
- **AC2 (semantic hierarchy):** 5 `@layer components` classes (`.text-page-title`, `.text-section-heading`, `.text-body`, `.text-meta`, `.text-annotation`) reading the Task-1 tokens + consuming the 2.3 `--text-primary/secondary` colors; `.text-annotation` carries no color (composites pair it with a `--state-*` color + signifier).
- **AC3/AC7 (spacing + density):** documented the 4px/8px hybrid convention in `src/styles/README.md` (no `theme.spacing` override — default scale already encodes it); added `src/lib/density.ts` `densityGap('compact'|'standard') → 'gap-1'|'gap-4'` literal-class helper.
- **AC4 (`.prose`):** hand-rolled `.prose` (`max-width: 70ch`, `line-height: var(--leading-relaxed)` ≥ 1.5, 8px paragraph rhythm) — no `@tailwindcss/typography`. Gated by `check:tokens`.
- **AC5 (layout primitives):** `src/components/layout/` — `Stack`, `Inline`, `Grid`, `Container`, `Divider` + barrel `index.ts`, all `forwardRef` + `cn()` + `...props`. Closed `GapToken` union (`src/components/layout/gap.ts`) mapped via static `GAP_CLASS` to literal `gap-*` (purge-safe + type-safe, Q3). Compile clean under strict TS (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`).
- **AC6 (focus ring):** `--ring-focus` HSL triplet in `:root` (+ provisional `.dark`), exposed as Tailwind `ring-focus` color; applied via `focus-visible:ring-ring-focus`. Did NOT touch any primitive's `ring-ring`.
- **AC8 (playground):** extended `PrimitivesPlayground` with typography hierarchy, 4px/8px spacing swatches, density rows, all 5 layout primitives, a `.prose` sample, and a focus-ring demo.
- **Q2 (token gate):** `tools/tokens/__tests__/typography-tokens.test.js` (`node --test`, reuses `parseRootVars`) asserts token presence/shape + `.prose` constraints with a negative self-test; `check:tokens` script wired into `pom.xml` frontend-maven-plugin (process-resources) alongside `check:contrast`.
- **Logging:** N/A — frontend-only (tokens/primitives/config/docs); no JVM application code introduced. Layout primitives do not `console.*`.
- **No new runtime dependency; lockfile unchanged** (only `package.json` `scripts` gained `check:tokens`); `npm ci` verified green.
- **Out-of-scope inherited file:** `src/components/ui/alert.tsx` carried a pre-existing uncommitted type-correctness edit (forwardRef element types) that failed the global `prettier --check .`. Applied Prettier formatting only (no logic change) so `format:check` stays green; the substantive edit predates this story.

### File List

**New:**
- `deliveryline-frontend/src/components/layout/Stack.tsx`
- `deliveryline-frontend/src/components/layout/Inline.tsx`
- `deliveryline-frontend/src/components/layout/Grid.tsx`
- `deliveryline-frontend/src/components/layout/Container.tsx`
- `deliveryline-frontend/src/components/layout/Divider.tsx`
- `deliveryline-frontend/src/components/layout/gap.ts`
- `deliveryline-frontend/src/components/layout/index.ts`
- `deliveryline-frontend/src/lib/density.ts`
- `deliveryline-frontend/tools/tokens/__tests__/typography-tokens.test.js`

**Modified:**
- `deliveryline-frontend/src/styles/globals.css` (typography/leading/weight/font tokens + `--ring-focus` + `.dark` ring + `body` font-family + `@layer components` semantic classes + `.prose`)
- `deliveryline-frontend/tailwind.config.ts` (`fontFamily.sans` + `colors.ring-focus`)
- `deliveryline-frontend/src/routes/_dev/PrimitivesPlayground.tsx` (token gallery sections)
- `deliveryline-frontend/src/styles/README.md` (Typography/Spacing/Layout section)
- `deliveryline-frontend/README.md` (Design System cross-link extended for 2.4)
- `deliveryline-frontend/package.json` (`check:tokens` script)
- `deliveryline-frontend/pom.xml` (`npm-run-check-tokens` frontend-maven-plugin execution)
- `deliveryline-frontend/tools/contrast/parse-globals.js` (optional trailing-`;` fix for minified-bundle parsing)
- `deliveryline-frontend/src/components/ui/alert.tsx` (Prettier formatting only — inherited pre-existing edit; see Completion Notes)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review)

## Change Log

- 2026-05-21: Story 2.4 dev-story complete → `review`. Shipped the non-color half of Layer 2: typography tokens + 5 semantic `@layer components` classes (Tailwind `fontSize` untouched, Q1), 4px/8px hybrid spacing convention + `densityGap` helper (no `theme.spacing` override), hand-rolled `.prose` reading utility (no `@tailwindcss/typography`), `--ring-focus` focus token (+ Tailwind `ring-focus` color), and the `Stack`/`Inline`/`Grid`/`Container`/`Divider` layout primitives with a closed `GapToken`→literal-class API (Q3). Added a `node --test` token-presence/shape + `.prose` gate (`check:tokens`) wired into the frontend-maven-plugin path (Q2), and extended the PrimitivesPlayground gallery + docs. Fixed a latent `parse-globals.js` bug (minifiers drop the final `;`, dropping the last `:root` var when parsing the emitted bundle). No new runtime dependency; lockfile unchanged. Verified green locally, on Linux (`node:20.19.0` Docker), and on the reactor (`./mvnw -DskipTests clean install`, all 4 modules; `check:tokens` fires; SPA embedded in backend jar). `check:contrast` stays 8/8.
- 2026-05-21: Story 2.4 created via create-story → `ready-for-dev`. Non-color half of Layer 2 design tokens (typography hierarchy, 4px/8px hybrid spacing convention + density pattern, hand-rolled `prose` utility, `Stack`/`Inline`/`Grid`/`Container`/`Divider` layout primitives, `--ring-focus` focus token) + a `node --test` token-presence gate wired into the Maven/CI path + playground gallery. Three clarifications resolved up front by Alex: Q1 semantic typography classes with Tailwind defaults untouched (no `fontSize` key override — primitives render identically), Q2 add the `node --test` token gate (parity with 2.3's enforceable discipline), Q3 closed `GapToken` union → literal Tailwind classes (purge-safe layout prop API). No new runtime dependency; lockfile unchanged.
