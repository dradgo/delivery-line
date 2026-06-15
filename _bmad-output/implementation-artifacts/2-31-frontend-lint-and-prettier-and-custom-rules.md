# Story 2.31: Frontend Lint + Prettier + Custom Rules

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **ESLint + Prettier + TypeScript strict rules + jsx-a11y + custom project rules (`no-workflow-domain-in-ui-primitives`, `no-inline-query-keys`) wired into the frontend module and CI**,
so that **the ESLint rules referenced in story 2.2 AC7 and story 2.7 AC4 have a concrete implementing story — and formatting, accessibility-lint, and architectural-boundary enforcement are mechanical on every PR**.

## ⚠️ Execution order — this story is a gate for 2.2 (read first)

Per `epics.md:1009`, **this story (2.31) must merge BEFORE story 2.2** (Tailwind + shadcn setup, which references `no-workflow-domain-in-ui-primitives` in its AC7) — *"or at latest before story 2.4 color tokens begin using Tailwind utilities. Epic 2's Definition of Done includes verifying this ordering held."*

Consequence for implementation: when 2.31 runs, the directories the custom rules target **may not exist yet**:
- `src/components/ui/*` — created by story **2.2** (likely not merged yet).
- `src/features/workflows/*` — created by stories **2.7/2.15+**.
- `src/lib/queryKeys/*` + TanStack Query (`useQuery`/`useMutation`) — created by story **2.6**.

This is fine and expected. The custom rules are **path-pattern-based** and proven by **fixture tests** (AC11) under `tools/eslint-rules/__tests__/`, so they pass against a minimal `src/` and begin enforcing automatically as those directories get populated. Do **not** wait for or stub those directories — author the rules + fixtures now.

## Acceptance Criteria

1. **Given** `deliveryline-frontend`, **Then** ESLint is configured via `eslint.config.js` (flat config) with plugins: `@typescript-eslint/eslint-plugin`, `eslint-plugin-react`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`, `eslint-plugin-jsx-a11y`, `eslint-plugin-import`.
2. **Given** Prettier configured via `.prettierrc.json`, **Then** project conventions are codified (single quotes, trailing commas `all`, semicolons, 2-space indent, 100-char print width) — documented and not duplicated in ESLint to avoid rule conflicts; `eslint-config-prettier` disables stylistic ESLint rules that Prettier handles.
3. **Given** TypeScript strict rules extending story 2.1's tsconfig, **Then** `@typescript-eslint` rules are set: `no-explicit-any` = error, `no-floating-promises` = error, `strict-boolean-expressions` = warn, `no-unnecessary-condition` = warn, `consistent-type-imports` = error, `no-misused-promises` = error.
4. **Given** the custom ESLint rule **`no-workflow-domain-in-ui-primitives`** referenced in story 2.2 AC7, **Then** it is implemented under `tools/eslint-rules/no-workflow-domain-in-ui-primitives.js` (or via a local ESLint plugin) — the rule flags any import from `src/features/workflows/` or any workflow-domain type reference inside files under `src/components/ui/*`.
5. **Given** the custom ESLint rule **`no-inline-query-keys`** referenced in story 2.7 AC4, **Then** it is implemented similarly — the rule flags `useQuery`/`useMutation`/`useInfiniteQuery` calls where the `queryKey` argument is an inline array literal rather than a call to a `workflowKeys.*` factory function; valid usages import from `src/lib/queryKeys/*`.
6. **Given** jsx-a11y rules, **Then** at minimum these are enabled at `error`: `anchor-is-valid`, `click-events-have-key-events`, `no-autofocus`, `role-has-required-aria-props`, `aria-props`, `aria-proptypes`, `aria-unsupported-elements`, `interactive-supports-focus`, `no-noninteractive-element-interactions`, `label-has-associated-control` — supporting WCAG 2.1 AA compliance enforced in story 2.25.
7. **Given** the `frontend-build-tests` CI tier from story 1.21, **Then** `npm run lint` (ESLint with `--max-warnings=0`) and `npm run format:check` (Prettier --check) run here and fail the build on violations.
8. **Given** the shared `.editorconfig` from story 2.30, **Then** frontend file encoding, line endings, and indentation match — no frontend-specific overrides unless documented (JSON/YAML/TSX already handled by top-level `.editorconfig` entries).
9. **Given** optional developer ergonomics, **Then** Husky + lint-staged configuration is documented as optional (not required) in `deliveryline-frontend/README.md` — teams can enable local pre-commit lint/format runs for their own workflow without the config being mandatory.
10. **Given** the foundation-gate verification story (1.23), **Then** its scope is widened (alongside story 2.30's widening) to include "ESLint + Prettier + custom rules green on the branch" for the frontend module — so frontend lint cleanliness is part of the epic-close gate.
11. **Given** a stub or failing-case test fixture for each custom rule, **Then** `tools/eslint-rules/__tests__/` contains cases proving `no-workflow-domain-in-ui-primitives` catches violations (e.g., a `src/components/ui/Button.test-fixture.tsx` importing `WorkflowRun` type fails lint) and `no-inline-query-keys` catches ad-hoc array literals — preventing rule drift as the codebase grows.

## Tasks / Subtasks

- [x] **Task 1: Install lint/format toolchain** (AC: 1, 2, 3, 6)
  - [x] Using the **module-local pinned npm 10.8.2** (`deliveryline-frontend/.frontend-node/node/npm` — NOT ambient npm; see Previous Story Intelligence), add dev dependencies: `eslint@^9`, `@eslint/js`, `typescript-eslint@^8` (the v8 meta-package providing parser + plugin + `config()` helper for flat config), `eslint-plugin-react@^7`, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`, `eslint-plugin-jsx-a11y@^6`, `eslint-plugin-import` (+ `eslint-import-resolver-typescript` for the `@/*` alias resolution introduced by story 2.2), `prettier@^3`, `eslint-config-prettier@^9`. Pin majors; let exact patch float. All are pure-JS (no native binaries) — but still follow the Task 10 lockfile discipline.
  - [x] Confirm package `"type": "module"` (from 2.1) → `eslint.config.js` is ESM (`export default`).

- [x] **Task 2: Author `eslint.config.js` (flat config)** (AC: 1, 3, 6)
  - [x] Create `deliveryline-frontend/eslint.config.js` (flat config) composing: `@eslint/js` recommended, `typescript-eslint` recommended-type-checked (for the type-aware rules in AC3), `eslint-plugin-react` (with `settings.react.version: 'detect'`), `react-hooks`, `react-refresh`, `jsx-a11y`, `import`. This file was **deleted in story 2.1** — you are authoring it fresh (do NOT expect a pre-existing config).
  - [x] **Type-aware linting setup:** AC3's `no-floating-promises`, `no-misused-promises`, `strict-boolean-expressions`, `no-unnecessary-condition` are type-aware and require `languageOptions.parserOptions.project`. Point it at the existing `tsconfig.app.json` + `tsconfig.node.json` (or add a thin `tsconfig.eslint.json` that references both and includes config files). Document the choice. Be mindful of type-aware lint runtime on the Windows CI runner.
  - [x] Set `@typescript-eslint` rule severities exactly per AC3: `@typescript-eslint/no-explicit-any` = `error`, `no-floating-promises` = `error`, `strict-boolean-expressions` = `warn`, `no-unnecessary-condition` = `warn`, `consistent-type-imports` = `error`, `no-misused-promises` = `error`.
  - [x] Set jsx-a11y rules to `error` per AC6 (the 10 named rules): `anchor-is-valid`, `click-events-have-key-events`, `no-autofocus`, `role-has-required-aria-props`, `aria-props`, `aria-proptypes`, `aria-unsupported-elements`, `interactive-supports-focus`, `no-noninteractive-element-interactions`, `label-has-associated-control`.
  - [x] **`eslint-config-prettier` MUST be last** in the config array (AC2) so it turns off stylistic ESLint rules Prettier owns — preventing rule conflicts.
  - [x] Add an `ignores` block for `target/`, `dist/`, `node_modules/`, `.frontend-node/`, `coverage/`, and (if present) generated shadcn primitive boilerplate exceptions documented inline.
  - [x] **shadcn primitive caveat:** when story 2.2's `src/components/ui/*` lands, stock shadcn primitives sometimes trip strict type-aware rules (e.g. `no-unnecessary-condition`) or use patterns the project flags. Since 2.31 merges first, this is forward-looking — but document the intended policy: prefer per-file `/* eslint-disable */` blocks confined to generated primitive files over relaxing the global rule, and never disable `no-workflow-domain-in-ui-primitives` on those files.

- [x] **Task 3: Prettier config** (AC: 2)
  - [x] Create `deliveryline-frontend/.prettierrc.json`: `{ "singleQuote": true, "trailingComma": "all", "semi": true, "tabWidth": 2, "printWidth": 100 }`.
  - [x] Create `deliveryline-frontend/.prettierignore` covering `target/`, `dist/`, `node_modules/`, `.frontend-node/`, `package-lock.json`, `coverage/`, `src/components/ui/` IF the team chooses to exclude generated shadcn output (document the decision — default: include it so generated primitives are formatted consistently).
  - [x] Do NOT duplicate Prettier's stylistic concerns as ESLint rules (AC2) — `eslint-config-prettier` (Task 2) handles the de-confliction.

- [x] **Task 4: Custom rule `no-workflow-domain-in-ui-primitives`** (AC: 4, 11)
  - [x] Create `deliveryline-frontend/tools/eslint-rules/no-workflow-domain-in-ui-primitives.js` — an ESLint rule (ESM, since `type: module`) that, for files matching `src/components/ui/**`, reports any `ImportDeclaration` whose source matches `src/features/workflows`, `@/features/workflows`, or a relative path resolving into `features/workflows` (also flag type-only imports of workflow-domain types). The rule should only activate for files under `src/components/ui/` (check `context.filename`).
  - [x] Register it in `eslint.config.js` via a local plugin object (e.g. `plugins: { 'local-rules': { rules: { 'no-workflow-domain-in-ui-primitives': rule } } }`) and enable `local-rules/no-workflow-domain-in-ui-primitives: 'error'` scoped to `src/components/ui/**`.
  - [x] **Fixture test (AC11):** add `tools/eslint-rules/__tests__/no-workflow-domain-in-ui-primitives.test.*` using ESLint's `RuleTester` proving (a) a `src/components/ui/Button.test-fixture.tsx` importing a `WorkflowRun` type / `src/features/workflows/...` FAILS, and (b) a primitive importing only from `@/lib/utils` / React PASSES. Run it via a node-based test runner (`node --test`) — NO Vitest in this story (Vitest is 2.27); document the chosen runner.

- [x] **Task 5: Custom rule `no-inline-query-keys`** (AC: 5, 11)
  - [x] Create `deliveryline-frontend/tools/eslint-rules/no-inline-query-keys.js` — flags `CallExpression`s named `useQuery` / `useMutation` / `useInfiniteQuery` where the `queryKey` (or `mutationKey`) property value is an **inline array literal** rather than a call expression (expected: a `workflowKeys.*()` factory call). Valid usages reference factories from `src/lib/queryKeys/*`.
  - [x] **Forward-looking note:** TanStack Query and `src/lib/queryKeys/` do not exist until story 2.6, so this rule has no real call sites to lint yet. It is authored now (referenced by story 2.7 AC4) and proven via fixtures; enforcement activates when 2.6 introduces queries. This aligns with the architecture hard-invariant "query keys… not ad hoc inline arrays" (architecture.md:766, 851).
  - [x] Register in `eslint.config.js` (`local-rules/no-inline-query-keys: 'error'`).
  - [x] **Fixture test (AC11):** `RuleTester` cases proving an inline `useQuery({ queryKey: ['workflows', id] })` FAILS and `useQuery({ queryKey: workflowKeys.detail(id) })` PASSES.

- [x] **Task 6: npm scripts + CI wiring** (AC: 7, 10)
  - [x] Add `package.json` scripts: `"lint": "eslint . --max-warnings=0"`, `"lint:fix": "eslint . --fix"`, `"format": "prettier --write ."`, `"format:check": "prettier --check ."`, and a `"lint:rules-test"` running the custom-rule fixture tests (Task 4/5).
  - [x] **Wire into the `frontend-build-tests` CI tier (AC7):** the tier runs `mvn -pl deliveryline-frontend clean package`. Add `frontend-maven-plugin` `npm` executions (bound to a phase such as `process-test-resources` or `verify`) running `run lint` and `run format:check` so BOTH local `mvn` builds and the CI matrix enforce lint/format. Alternatively add explicit `npm run lint` / `npm run format:check` steps to the `frontend-build-tests` job — choose one, document why. `--max-warnings=0` means the AC3 `warn`-level rules (`strict-boolean-expressions`, `no-unnecessary-condition`) DO fail CI; confirm the initial codebase (post-2.1 minimal `src/`) is clean under them or adjust to `off`/`warn` with a documented rationale.
  - [x] **foundation-gate widening (AC10):** confirm lint failures block `foundation-gate`. Mechanism: `frontend-build-tests` is already in `foundation-gate`'s `needs:` chain (story 2.1 AC10), so a lint failure in that tier already blocks the gate. Verify and document — no separate gate job needed unless the team wants an explicit `frontend-lint` step name for attributability (mirror story 2.30 AC4's separate-named-steps approach).

- [x] **Task 7: `.editorconfig` alignment** (AC: 8)
  - [x] AC8 expects the **root `.editorconfig` from story 2.30**. If 2.30 has merged, verify the frontend matches it (UTF-8, LF, 2-space JSON/YAML/TSX, `insert_final_newline`) with no frontend-specific overrides; done.
  - [x] **If 2.30 has NOT merged** (it is `backlog`; its execution-order note ties it to merging before 2.8, not before 2.31): create a minimal repo-root `.editorconfig` covering the values 2.30 AC5 specifies (UTF-8, LF, 4-space Java, 2-space JSON/YAML/TSX, `insert_final_newline = true`, CRLF exception for `.cmd`/`.ps1`) so the frontend has a shared config to match. Document that story 2.30 owns/extends this file; 2.31 only seeds the minimum so AC8 is satisfiable. Surfaced as Open Clarification Q1.

- [x] **Task 8: Husky + lint-staged (optional, documented)** (AC: 9)
  - [x] Do NOT make pre-commit hooks mandatory. Document in `deliveryline-frontend/README.md` (Lint/Format section) an OPTIONAL Husky + lint-staged setup devs can opt into locally (sample config + install steps), explicitly noting it is not required and not installed by default.

- [x] **Task 9: README / docs increment** (AC: 2, 7, 9)
  - [x] Extend `deliveryline-frontend/README.md`: lint/format commands (`npm run lint`, `npm run format`, `npm run format:check`), the Prettier conventions, the two custom rules and what they enforce (+ that `no-inline-query-keys` activates with 2.6), the `--max-warnings=0` CI policy, and the optional Husky/lint-staged section.

- [x] **Task 10: Cross-platform lockfile + reactor/CI verification** (cross-cutting; LOAD-BEARING per Epic 1 retro)
  - [x] After all installs, **regenerate `package-lock.json` with a FULL `npm install`** (NOT `--package-lock-only`) using module-local npm 10.8.2 — the exact story-2.1 trap (shallow regeneration drops platform-gated optional deps and breaks `npm ci` on the other OS). ESLint/Prettier deps are pure-JS, but the lockfile must be regenerated cleanly so the existing rolldown/vite native-binding entries for all platforms survive.
  - [x] **Verify on Linux** before done — `docker run --rm -v "<frontend>:/app" -w /app node:20.19.0 bash -c "npm ci --no-audit --no-fund --prefer-offline && npm run lint && npm run format:check && npm run build"` → green (Docker + WSL Ubuntu-24.04 available locally). Then re-verify Windows: `./mvnw -B -ntp -pl deliveryline-frontend clean package`.
  - [x] Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` green (4 modules); backend jar still embeds the SPA. Confirm `frontend-build-tests` matrix (ubuntu + windows) stays green WITH lint now running in the tier.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** Frontend tooling/config story — no JVM application-service classes, domain exceptions, SPI calls, or state transitions are added. The SLF4J + Logback standard (INFO entry/exit, WARN typed-rejection, ERROR unhandled, MDC keys `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity`/`actorType`, redaction before logging, list-appender test pinning) has no application code to instrument here, but remains in force for any future JVM code. No `System.out`/`printStackTrace()`.

### Review Findings

- [x] [Review][Patch] `no-inline-query-keys` under-enforces AC5 by allowing non-factory keys and easy bypasses [deliveryline-frontend/tools/eslint-rules/no-inline-query-keys.js:49]
- [x] [Review][Patch] `no-workflow-domain-in-ui-primitives` misses workflow-domain references expressed via lazy imports [deliveryline-frontend/tools/eslint-rules/no-workflow-domain-in-ui-primitives.js:56]
- [x] [Review][Patch] Custom-rule fixture tests are not wired into the enforced Maven/CI gate, so rule drift can pass required checks [deliveryline-frontend/pom.xml:91]

## Dev Notes

### Story scope — what this story does and does NOT do

Delivers the frontend quality-enforcement layer: ESLint flat config + Prettier + type-aware TS rules + jsx-a11y + the two project-specific custom rules, wired into CI so every PR is mechanically checked. It is a **prerequisite gate for story 2.2** (see top warning).

**OUT of scope (do NOT pull in):**
- **Tailwind/shadcn primitives** (story 2.2), **design tokens** (2.3/2.4), **TanStack Router** (2.5), **TanStack Query / query-key factories** (2.6), **composites** (2.15–2.19). The custom rules *reference* `src/components/ui/`, `src/features/workflows/`, and `src/lib/queryKeys/` but do NOT create them — they enforce against those paths once other stories add them.
- **Vitest / a test runner for app code** (story 2.27). The custom-rule fixture tests (AC11) use ESLint `RuleTester` via `node --test`, NOT Vitest.
- **Backend lint** (Spotless/Checkstyle/SpotBugs) — story **2.30**. 2.31 is frontend-only; it only *consumes* 2.30's root `.editorconfig` (AC8 / Task 7).
- **The WCAG audit itself** (story 2.25) — 2.31 only adds the jsx-a11y lint rules that *support* it.

### Critical dependencies & ordering

- **2.31 → before 2.2** (epics.md:1009): this story gates 2.2. Implications handled in Task 2 (author config fresh), Tasks 4/5 (path-pattern rules + fixtures pass on minimal `src/`).
- **AC8 → story 2.30's `.editorconfig`** (epics.md:1020, 2.30 AC5 at epics.md:996): if 2.30 hasn't merged, seed a minimal root `.editorconfig` (Task 7); 2.30 extends it. Open Clarification Q1.
- **AC5 rule → forward-looking to 2.6**: `no-inline-query-keys` has no real call sites until TanStack Query lands in 2.6 (architecture.md:766/830/851 establish the invariant). Authored + fixture-tested now.
- **AC4 rule → activates with 2.2**: `no-workflow-domain-in-ui-primitives` matches `src/components/ui/*`, which 2.2 creates. Fixtures prove correctness now.

### Previous Story Intelligence (Stories 2.1 + 2.2)

- **🔴 `eslint.config.js` was DELETED in story 2.1** (its Completion Notes: *"`eslint.config.js` from the Vite template was removed; story 2.31 will author the canonical ESLint config from scratch"*). This story authors it fresh — do not expect a starting config.
- **`package.json` has `"type": "module"`** → `eslint.config.js` and custom rule files are ESM (`export default` / `import`). Use ESM, not `module.exports`.
- **Pins (2.1):** React `^18.3.1`, TypeScript `~6.0.2`, Vite `^8.0.12`, `@vitejs/plugin-react` `^6.0.1`, Node `v20.19.0`, npm `10.8.2`. ESLint 9 + typescript-eslint 8 support TS 6.0 and React 18 — verify the typescript-eslint version supports TS `~6.0` at install time.
- **Strict tsconfig (2.1):** `tsconfig.app.json` enables `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`. Type-aware ESLint must use a tsconfig that includes the files being linted.
- **`@/*` alias (introduced by 2.2):** shadcn adds `@/* → ./src/*` in `tsconfig.app.json` + `vite.config.ts`. If 2.31 merges before 2.2, the alias may not exist yet — `eslint-plugin-import`'s resolver should tolerate its absence (configure `eslint-import-resolver-typescript` to read the tsconfig; it resolves the alias once 2.2 adds it). Document.
- **Build wiring (2.1):** `frontend-maven-plugin` (eirslett 1.15.1) runs `npm ci` + `npm run build` during `generate-resources`; `build.outDir = 'target/dist'`; backend copies → `BOOT-INF/classes/static/`. Adding lint as a `frontend-maven-plugin` npm execution keeps `mvn`-driven builds (local + CI) enforcing it (Task 6).
- **🔴 Cross-platform lockfile lesson (cost 2.1 four CI rounds):** regenerate `package-lock.json` with a **FULL `npm install`** (never `--package-lock-only`), module-local npm 10.8.2 (ambient npm 11 omits `@emnapi/*`), and **verify `npm ci` on Linux** (Docker `node:20.19.0`/WSL) before pushing. See project memory `frontend-lockfile-cross-platform` + `verify-ci-fixes-in-clean-env`.
- **Git/commit convention:** `_bmad-output/` is untracked by repo convention — commit only `deliveryline-frontend/` + `.github/workflows/ci.yml` + root `.editorconfig` changes; never `git add .` (tree has untracked `.m2/`, `_tmp/`, etc.).

### Architecture alignment (architecture.md)

- **Query-key-factory hard invariant** that `no-inline-query-keys` mechanizes: *"TanStack Query keys are created through query key factory functions, not ad hoc inline arrays"* (line 766); *"New query keys must be added through query key factories, not inline arrays inside components"* (line 851); *"Do not create ad hoc TanStack Query keys; use query key factories"* (line 830). Factory home: `src/lib/queryKeys/workflowKeys.ts` (line 1086).
- **Primitive/feature boundary** that `no-workflow-domain-in-ui-primitives` mechanizes: `src/components/ui/` (generic primitives) vs `src/features/workflows/` (domain) — the directional separation in the structure (lines 1055-1091) + *"shadcn/ui components… used consistently with minimal local customization"* (line 488).
- **jsx-a11y rules** support the WCAG 2.1 AA target (story 2.25) — ux-design-specification.md:2110.

### CI / quality-gate notes

- The `frontend-build-tests` tier (story 1.21, matrix ubuntu+windows) is already wired into `foundation-gate`'s `needs:` chain (story 2.1 AC10). Running `npm run lint --max-warnings=0` + `npm run format:check` in that tier means a lint/format failure already blocks both the tier and the gate (AC7 + AC10) — verify rather than adding a new gate job. Optionally add a separately-named CI step for attributability (mirrors story 2.30 AC4).
- `--max-warnings=0` (AC7) makes AC3's two `warn`-level rules build-blocking. Ensure the minimal post-2.1 `src/` (App.tsx + main.tsx) is clean under them, or document any rule downgrade.

### Anti-patterns to avoid

- **Do NOT install Tailwind/shadcn, TanStack Router/Query, or author primitives/composites/routes/query-key factories** — those are 2.2–2.19. This story only enforces against those paths.
- **Do NOT add Vitest** — fixture tests use ESLint `RuleTester` + `node --test` (Vitest is 2.27).
- **Do NOT duplicate Prettier formatting as ESLint stylistic rules** — `eslint-config-prettier` last (AC2).
- **Do NOT make Husky/lint-staged mandatory** — optional + documented only (AC9).
- **Do NOT disable `no-workflow-domain-in-ui-primitives` on primitive files** — that defeats AC4/AC7. Confine any necessary `eslint-disable` to other generated-primitive type-rule noise.
- **Do NOT regenerate the lockfile with `--package-lock-only` and do NOT skip Linux verification** — the 2.1 cross-platform trap.
- **Do NOT author the root `.editorconfig` beyond the minimum** if 2.30 hasn't merged — 2.30 owns it (Task 7 / Q1).

### Logging Requirements (project-wide standard)

Frontend tooling story; no JVM/application code added, so the SLF4J + Logback standard is dormant here but remains in force for any incidental backend change. No `System.out`/`printStackTrace()`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.31] — authoritative ACs + execution-order note (lines 1003-1023, esp. 1009)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.30] — shared `.editorconfig` (AC5, line 996), foundation-gate widening pattern (AC10), separate-named CI steps (AC4) (lines 982-1002)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.2 AC7] — `no-workflow-domain-in-ui-primitives` consumer (line 886)
- [Source: _bmad-output/planning-artifacts/architecture.md#Query Key Factories] — `no-inline-query-keys` invariant (lines 766, 830, 851, 1086)
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Structure] — components/ui vs features/workflows boundary (lines 488, 1055-1091)
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Accessibility] — WCAG 2.1 AA target backing jsx-a11y rules (line 2110)
- [Source: _bmad-output/implementation-artifacts/2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring.md] — deleted eslint config, `type: module`, pins, strict tsconfig, build wiring, cross-platform lockfile lesson
- [Source: _bmad-output/implementation-artifacts/2-2-tailwind-and-shadcn-ui-setup-and-primitive-inventory.md] — 2.2 consumes AC4 rule; introduces `@/*` alias + `src/components/ui/`
- [Source: project memory `frontend-lockfile-cross-platform` + `verify-ci-fixes-in-clean-env`] — full `npm install` regeneration + Linux verification discipline

### Open clarifications (resolve before/at start; otherwise apply the recommended default)

- **Q1 (`.editorconfig` ownership vs story 2.30):** 2.30 (which owns the root `.editorconfig`) is `backlog` and tied to merging before 2.8 — likely after 2.31. Recommended — seed a minimal root `.editorconfig` here (Task 7) with the 2.30 AC5 values so AC8 is satisfiable; 2.30 later extends it. Confirm before Task 7.
- **Q2 (ESLint 9 flat config + typescript-eslint 8):** Recommended — use ESLint 9 flat config with the `typescript-eslint` v8 meta-package (`config()` helper). Confirm it supports TypeScript `~6.0.2` (2.1's pin) at install time; if not, pin the latest typescript-eslint that does and note it.
- **Q3 (custom-rule registration + test runner):** Recommended — register custom rules via a local plugin object in `eslint.config.js`; write rule fixtures with ESLint `RuleTester` run by `node --test` (no Vitest until 2.27).
- **Q4 (lint CI wiring mechanism):** Recommended — add `frontend-maven-plugin` npm executions for `run lint` + `run format:check` so `mvn clean package` (the CI command) enforces them on both OSes; alternatively explicit CI steps. Either satisfies AC7/AC10 via the existing `frontend-build-tests` → `foundation-gate` chain.
- **Q5 (`--max-warnings=0` vs `warn`-level AC3 rules):** With `--max-warnings=0`, `strict-boolean-expressions`/`no-unnecessary-condition` (`warn`) become build-blocking. Recommended — keep them `warn` and ensure the current minimal `src/` is clean; if shadcn primitives (2.2) later produce unavoidable warnings, address per the Task 2 shadcn caveat rather than weakening the rule globally.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — `claude-opus-4-7[1m]`

### Debug Log References

- **ESLint version skew:** `@eslint/js@*` resolved to v10 (peer `eslint@^10`) against the pinned `eslint@^9` → ERESOLVE. Pinned both `eslint@^9` + `@eslint/js@^9`. Installed set: eslint 9.39.4, typescript-eslint 8.59.4, eslint-plugin-react 7.37.5, react-hooks 5.2.0, react-refresh 0.4.26, jsx-a11y 6.10.2, import 2.32.0, prettier 3.8.3, eslint-config-prettier 9.1.2, globals 17.6.0.
- **Flat-config parser gap:** initial run errored `Parsing error: Unexpected token :` on `vite.config.ts` — the config-files block (`*.{js,ts}`) lacked a TS parser and fell through to espree. Fixed by setting `languageOptions.parser: tseslint.parser` (with `project: false, projectService: false`) on that block.
- **Custom-rule filename guard bug:** `no-workflow-domain-in-ui-primitives` initially produced 0 errors on invalid fixtures — the `UI_PRIMITIVE_FILE` regex required a separator BEFORE `src` (`/[\\/]src.../`), but RuleTester filenames are relative (`src/components/ui/button.tsx`). Fixed to `/(^|[\\/])src[\\/]components[\\/]ui[\\/]/`. Fixtures then pass 2/2.
- **`node --test` portability:** `node --test <dir>` is not portable and Node 20.19 (CI runtime) lacks `--test` glob support; switched `lint:rules-test` to explicit file paths.
- **POM XML-comment `--`:** the `npm-run-lint` execution comment contained `--max-warnings=0` — a bare `--` is illegal inside an XML comment → Non-parseable POM. Reworded to "the max-warnings=0 flag" (same class of defect seen in story 2.1's pom).
- **Type-aware linting** uses typescript-eslint `projectService: true` (auto-discovers `tsconfig.app.json`/`tsconfig.node.json`) rather than enumerating `parserOptions.project` paths — honours story 2.1's strict flags without a separate `tsconfig.eslint.json`.

### Completion Notes List

**Story 2.31 — Frontend Lint + Prettier + Custom Rules**

Re-authored the `eslint.config.js` that story 2.1 deleted, as an ESLint 9 **flat config** with all six required plugins (AC1), type-aware `@typescript-eslint` rules at the AC3 severities (via `projectService`), the 10 jsx-a11y rules at `error` (AC6), and `eslint-config-prettier` applied **last** (AC2). Added Prettier (`.prettierrc.json`: single-quote, trailing-all, semi, 2-space, 100-col) + `.prettierignore`. Both custom rules live under `tools/eslint-rules/` and are registered as the `local-rules` plugin, each proven by ESLint `RuleTester` fixtures run via `node --test` (AC11) — no committed self-failing `.tsx` (which would make `npm run lint` permanently red).

**Key decisions / divergences:**
- **AC10 (foundation-gate widening):** satisfied **transitively** — `npm run lint` (`--max-warnings=0`) + `npm run format:check` run as `frontend-maven-plugin` executions during `mvn -pl deliveryline-frontend clean package`, which is exactly the `frontend-build-tests` CI tier command, and that tier is already in `foundation-gate`'s `needs:` chain (story 2.1 AC10). A lint/format failure therefore fails the tier → fails the gate. No CI YAML change needed; no separate named step added (story left it optional).
- **AC8 (`.editorconfig`):** story 2.30 (which owns the root `.editorconfig`) is still `backlog`, so per Open Clarification Q1 a **minimal root `.editorconfig`** was seeded with the 2.30 AC5 values (UTF-8, LF, 4-space Java, 2-space JSON/YAML/TSX, final newline, CRLF for `.cmd/.bat/.ps1`), with an inline note that 2.30 owns/extends it.
- **AC5 (`no-inline-query-keys`) is forward-looking:** TanStack Query + `src/lib/queryKeys/` arrive in story 2.6, so the rule has no real call sites yet — authored now (referenced by 2.7 AC4), proven by fixtures, enforces automatically once 2.6 lands.
- **`@/*` import resolution:** `eslint-plugin-import`'s `no-unresolved` is turned **off** (TypeScript already enforces module resolution) to avoid false positives on the `@/*` alias that story 2.2 will add; `import/no-duplicates` kept at `error`.
- Husky + lint-staged documented as **optional/not-installed** in the README (AC9).

**Verification (all green):**
- Local (Windows, module-local node 20.19.0): `npm run lint` clean, `npm run format:check` clean, `npm run lint:rules-test` 2/2 pass, `npm run build` green.
- **Linux (Docker `node:20.19.0`, the CI runtime):** `npm ci` (lockfile resolves cross-platform) → `lint` → `format:check` → `lint:rules-test` (2/2) → `build` — all green. Confirms the lockfile is cross-platform correct (story-2.1 trap avoided) and lint works on the CI runtime.
- Maven: `mvn -pl deliveryline-frontend clean package` runs `npm-run-lint` + `npm-run-format-check` executions → BUILD SUCCESS.
- Reactor: `mvn -DskipTests clean install` → all 4 modules SUCCESS; backend jar still embeds `BOOT-INF/classes/static/index.html`.

**Cross-platform lockfile discipline:** dependencies added via full `npm install` (module-local npm 10.8.2), NOT `--package-lock-only`; `package-lock.json` regenerated cleanly with all platform native-binding entries preserved (rolldown/vite) — verified by the Linux `npm ci`.

### File List

**Frontend module — new:**
- `deliveryline-frontend/eslint.config.js` — ESLint 9 flat config (TS type-aware + react/hooks/refresh + jsx-a11y + import + local-rules; eslint-config-prettier last).
- `deliveryline-frontend/.prettierrc.json` — Prettier conventions (AC2).
- `deliveryline-frontend/.prettierignore` — excludes build output, deps, lockfile, tsbuildinfo.
- `deliveryline-frontend/tools/eslint-rules/no-workflow-domain-in-ui-primitives.js` — custom rule (AC4).
- `deliveryline-frontend/tools/eslint-rules/no-inline-query-keys.js` — custom rule (AC5).
- `deliveryline-frontend/tools/eslint-rules/index.js` — `local-rules` plugin export.
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-workflow-domain-in-ui-primitives.test.js` — RuleTester fixtures (AC11).
- `deliveryline-frontend/tools/eslint-rules/__tests__/no-inline-query-keys.test.js` — RuleTester fixtures (AC11).

**Frontend module — modified:**
- `deliveryline-frontend/package.json` — added devDeps (eslint/prettier toolchain + globals) + scripts: `lint`, `lint:fix`, `lint:rules-test`, `format`, `format:check`.
- `deliveryline-frontend/package-lock.json` — regenerated (full `npm install`, module-local npm 10.8.2; Linux-verified).
- `deliveryline-frontend/pom.xml` — added `frontend-maven-plugin` `npm-run-lint` + `npm-run-format-check` executions (process-resources) so `mvn package` / the `frontend-build-tests` CI tier enforce lint+format (AC7/AC10).
- `deliveryline-frontend/README.md` — Scripts table updated; new "Lint & Format (story 2.31)" section incl. custom-rule docs + optional Husky/lint-staged (AC9); minor Prettier reformat.
- `deliveryline-frontend/src/{App.tsx,App.css,index.css,main.tsx}`, `index.html`, `tsconfig.json`, `vite.config.ts` — Prettier baseline reformat (no behavioral change; 2.1 template files).

**Repo root — new:**
- `.editorconfig` — minimal shared baseline seeded per AC8/Q1 (story 2.30 owns/extends).

### Change Log

| Date       | Change                                                                                                                                                                                                                                                              |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-05-20 | Story 2.31 implementation — ESLint 9 flat config + Prettier + type-aware TS rules + jsx-a11y + two custom rules (`no-workflow-domain-in-ui-primitives`, `no-inline-query-keys`) with RuleTester fixtures; lint/format wired into `frontend-maven-plugin` (→ frontend-build-tests → foundation-gate); minimal root `.editorconfig` seeded (2.30 owns). Verified local + Linux (Docker node:20.19) + Maven reactor. Status: ready-for-dev → in-progress → review. |
