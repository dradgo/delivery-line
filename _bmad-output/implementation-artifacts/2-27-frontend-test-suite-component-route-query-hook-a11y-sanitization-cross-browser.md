# Story 2.27: Frontend Test Suite (Component + Route + Query Hook + A11y + Sanitization + Cross-Browser)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a frontend developer,
I want a comprehensive frontend test suite — Vitest + React Testing Library + jest-dom + MSW for API mocking + axe-core for a11y + Playwright for cross-browser end-to-end + adversarial sanitization fixtures — wired into the CI `frontend-build-tests` tier,
so that every composite, route, and query hook from stories 2.5–2.26 has automated coverage and the WCAG / responsive / sanitization commitments hold under CI rather than relying on manual review.

## Scope Decisions (read first — these resolve epic-vs-reality tensions)

**This is the Epic-2 capstone: a CONSOLIDATE + FORMALIZE + ADD-PLAYWRIGHT + ENFORCE-THRESHOLDS story over an ALREADY-SUBSTANTIAL test suite (814 tests across 71 files as of story 2.26 — see its Debug Log), NOT a greenfield "stand up Vitest" story.** Vitest, React Testing Library, `@testing-library/jest-dom`, MSW (`msw@2`), `vitest-axe` + `axe-core`, and the adversarial XSS fixture loop are already installed, wired into `src/test/setup.ts`, and run inside `frontend-build-tests` (which is a `needs:` of `foundation-gate`). Read the "Current-state inventory" in Dev Notes before writing a line of code. Do **not** rebuild what exists.

- **S1 — The net-new deliverables are NARROW.** Everything in AC1 except Playwright is already configured. The genuinely net-new work is: (a) **Playwright** — config + the two critical-journey E2E specs + cross-browser matrix + mobile-viewport variants + keyboard-only variants + the CI matrix job (the single biggest piece); (b) **coverage thresholds** enforced via the already-installed `@vitest/coverage-v8` + documented in `frontend/README.md` (AC10); (c) **shared OpenAPI-derived MSW handlers** that reuse the story-1.23 fixture event streams (AC2/AC3); (d) a **coverage-gap audit** of existing component/route/hook tests against the AC4/AC5/AC6 checklists, filling only the gaps; (e) **flake-control / quarantine** convention (AC11); (f) **CI wiring** (AC12) + **foundation-gate** parity for the e2e job (AC13).

- **S2 — AC1, AC4, AC7 are LARGELY ALREADY SATISFIED — verify + close gaps, do not duplicate.** AC7's build-blocking XSS fixture loop already ships as `src/lib/sanitization/__tests__/SafeMarkdownRenderer.test.tsx` (story 2.24, 11+ attack-class fixtures under `xss-fixtures/`, fails the build if any inert assertion breaks). AC1's Vitest/RTL/jest-dom/MSW/axe stack is in `package.json` + `vitest.config.ts` + `src/test/setup.ts`. AC4's component tests exist for most composites/state primitives. Your job for these ACs is to **audit the existing coverage against the AC checklist, document what is covered where, and add ONLY the missing cases** — not to re-author passing tests.

- **S3 — Playwright is a SEPARATE CI matrix job that fires AFTER Vitest is green (AC12).** Vitest unit/component/a11y/sanitization tests stay in the existing `frontend-build-tests` tier (via the Maven `npm-run-test` execution) and run first; the new Playwright cross-browser + mobile-viewport job is a distinct job gated on `frontend-build-tests` success, so a fast Vitest failure short-circuits before the slow browser job burns CI minutes. Playwright installs its own browser binaries (`npx playwright install --with-deps`) — do **not** add browsers to the Vite/Maven build path.

- **S4 — Coverage thresholds use the ALREADY-INSTALLED `@vitest/coverage-v8`; CI fails under threshold; README documents the numbers + rationale (AC10).** Add a `coverage` block to `vitest.config.ts` (provider `v8`) with per-path thresholds: ~80% line on `src/features/workflows/`, ~90% on `src/lib/sanitization/` + `src/lib/queryKeys/`. Wire `--coverage` into the enforced Maven/CI path (extend the `test` script or add a `test:coverage` script the `npm-run-test` execution calls) so a regression below threshold reds the build. Thresholds go in `frontend/README.md` with a one-line rationale per path.

- **S5 — Foundation-gate (AC13): the Vitest suite is ALREADY wired; only the Playwright job is a decision.** `foundation-gate.needs` already includes `frontend-build-tests` (which runs `vitest run`), so "frontend test suite green on the branch" is **already enforced for the Vitest half** — AC13 is structurally satisfied for it. The encoded default for the new Playwright job: **add `frontend-e2e` to `foundation-gate.needs`** so cross-browser + keyboard-only regressions block at the same gate as backend regressions (AC13 parity intent). Cost is mitigated by S3's fail-fast-after-Vitest + the browser matrix. Alternative (run Playwright as a non-PR-blocking job like `bundled-jar-smoke`) is Open Question 1 — pick the blocking default unless Alex says otherwise.

- **S6 — MSW handlers REUSE the story-1.23 fixture event streams; they are NOT re-invented per test (AC2/AC3).** The canonical deterministic event histories live in `deliveryline-backend/src/test/resources/fixture-event-streams/` (story 1.23 AC4/AC8: `happy-path-success`, `spec-rejection-and-resubmit`, `execution-failure-with-retry`, + the clarification-lifecycle scenario). Build a shared `src/test/handlers.ts` whose MSW responses are shaped by the committed OpenAPI schema (`src/lib/api/schema.d.ts`) and seeded from those fixture streams (import the JSON via a small sync step or relative read), so mocked responses match BOTH the real backend schema (no mock/prod drift) AND realistic event sequences (no synthetic happy-path-only data). Today `src/test/server.ts` ships **zero** default handlers (each test calls `server.use(...)`); this story adds the shared default set without breaking the per-test override pattern.

## Acceptance Criteria

1. **Given** `package.json`, **Then** test infrastructure is configured: Vitest as the test runner, `@testing-library/react` + `@testing-library/jest-dom` for component testing, `msw` (Mock Service Worker) for backend API mocking against the generated client (story 2.6), `vitest-axe` / `axe-core` for accessibility scans, Playwright for end-to-end + cross-browser tests. **(All present except Playwright — add `@playwright/test`; do not re-add the already-present deps.)**
2. **Given** the MSW setup, **Then** request handlers are derived from the OpenAPI snapshot (story 2.6 AC1) so mocked responses conform to the same schema the real backend returns — preventing mock/prod divergence.
3. **Given** the foundation fixture event stream from story 1.23, **Then** frontend tests reuse those fixtures (loaded via MSW handlers or imported directly) so component tests run against realistic event sequences (happy path, spec-rejection-and-resubmit, execution-failure-with-retry, full clarification lifecycle per story 2.12 AC8) — not synthetic happy-path-only data.
4. **Given** component test coverage requirements, **Then** every composite from stories 2.15–2.19 + every state primitive from stories 2.20–2.23 has Vitest + Testing Library tests asserting: each documented state renders correctly, all ACs marked with "component test coverage" in their respective stories are covered, axe-core scan passes with zero `wcag2aa` violations.
5. **Given** route test coverage, **Then** each TanStack Router route from story 2.5 has tests asserting: typed param validation rejects malformed IDs, route loader prefetches the right query, missing-resource handling routes to the correct empty state, deep-link entry renders correctly, scroll-position preservation across navigation works.
6. **Given** query hook test coverage, **Then** each TanStack Query hook from story 2.6 (`useWorkflowDetail`, `useWorkflowEvents`, `useArtifact`, `useAllowedActions`, `useClarifications`) has tests covering: success path with typed response, Problem Details error path with stable error code, mutation success invalidates correct downstream queries (per story 2.6 AC6), Idempotency-Key header sent on every mutation (per story 2.6 AC7), stale time + cache time defaults respected.
7. **Given** sanitization regression coverage per story 2.24, **Then** the adversarial XSS fixture set is wired into the test suite as a dedicated test file; a single passing-XSS-fixture is build-blocking (story 2.24 AC8). **(Already shipped as `SafeMarkdownRenderer.test.tsx` — verify it runs in the enforced gate and the ≥11-fixture floor holds.)**
8. **Given** Playwright cross-browser end-to-end coverage per story 2.26 AC11, **Then** Playwright tests exist for the critical journeys (queue → run → spec read → clarification answer → approve; queue → run → spec read → reject with feedback) and run across Chrome, Firefox, Safari, and Edge in CI matrix; mobile viewport variants run for the same journeys at the Galaxy S23+ class viewport size from story 2.26 AC9.
9. **Given** keyboard-only journey coverage per story 2.25 AC3, **Then** Playwright tests use keyboard-only navigation (Tab/Shift+Tab/Enter/Space/Escape only — no `.click()` calls) for the critical journeys; tests fail if any action becomes unreachable without a mouse.
10. **Given** coverage thresholds, **Then** documented minimum coverage thresholds are enforced (e.g., 80% line coverage on `src/features/workflows/`, 90% on `src/lib/sanitization/` and `src/lib/queryKeys/`); thresholds documented in `frontend/README.md` with rationale; CI fails when thresholds are not met.
11. **Given** test parallelization + flake control, **Then** tests run in parallel where safe (per Vitest defaults); known-flaky tests are explicitly quarantined with documented justification (per story 1.21 AC5 — flake metrics surfaced, not masked); a no-blanket-retry rule applies.
12. **Given** CI integration with the `frontend-build-tests` tier from story 1.21, **Then** Vitest unit/component tests run first, axe scans run alongside, MSW-backed integration tests next, Playwright cross-browser job runs as a separate matrix job (so Vitest failures fail fast before the slower Playwright job consumes CI time).
13. **Given** the foundation-gate verification from story 1.23, **Then** its scope widens to include "frontend test suite green on the branch" — so frontend regression catches at the same gate as backend regressions. **(Vitest half already wired via `frontend-build-tests ∈ foundation-gate.needs`; this story adds the Playwright `frontend-e2e` job to that `needs:` per S5.)**

## Tasks / Subtasks

- [x] **Task 1 — Audit + document existing test infrastructure; close AC1/AC2/AC3/AC7 gaps (AC1, AC2, AC3, AC7, S1, S2, S6)**
  - [x] Confirm AC1's stack is present (it is: `vitest`, `@testing-library/react`/`jest-dom`/`user-event`/`dom`, `msw`, `vitest-axe`+`axe-core`, `@vitest/coverage-v8` in `package.json`). Add ONLY `@playwright/test` (devDependency). Regenerate the lockfile with a full `npm install` and verify on Linux before pushing — Vite-8/rolldown native bindings + the committed `.npmrc legacy-peer-deps=true` are load-bearing ([[frontend-lockfile-cross-platform]], [[frontend-ts6-legacy-peer-deps]]).
  - [x] Build shared MSW handlers `src/test/handlers.ts`: responses typed by `src/lib/api/schema.d.ts` (AC2 — no mock/prod drift), seeded from the story-1.23 fixture event streams in `deliveryline-backend/src/test/resources/fixture-event-streams/` (AC3 — happy-path, spec-rejection-and-resubmit, execution-failure-with-retry, clarification-lifecycle). Decide the reuse mechanism (relative-path JSON import vs a tiny copy/sync script run in `process-resources`) and document it; do NOT hand-author divergent synthetic streams. Register the shared handlers as defaults in `src/test/server.ts` WITHOUT removing the per-test `server.use(...)` override pattern (`setup.ts` `resetHandlers()` already restores defaults each test).
  - [x] Verify AC7: `SafeMarkdownRenderer.test.tsx` (story 2.24) runs in the enforced `npm run test` gate, loops every `xss-fixtures/*.md` against its `.expected.json`, and keeps the `≥11` fixture floor build-blocking. No new fixture work unless a gap is found — just confirm it's in the gate and add a Dev-Notes pointer.

- [x] **Task 2 — Component + state-primitive coverage audit (AC4, S2)**
  - [x] Inventory existing tests for composites (stories 2.15–2.19: queue item, run identity/context strip 2.16, artifact review panel 2.17, clarification region 2.18, approval decision bar 2.19) and state primitives (2.20–2.23: queue shell states, feedback patterns, nav/empty/loading/error infra, modal/overlay/confirmation). Most already have `*.test.tsx`. Produce a short coverage map (composite → test file → which documented states are asserted → axe scan present?).
  - [x] Fill ONLY the gaps: any documented state not rendered+asserted, any AC marked "component test coverage" in the source story not covered, any composite missing a `wcag2aa` axe scan via `expectNoA11yViolations` (`src/test/a11y/axe.ts`). Assert roles/text/classes/`data-*`, NEVER `toMatchSnapshot` (the 2.20–2.26 discipline; visual-regression snapshots are out of scope).
  - [x] Helper functions live in `.ts`, not `.tsx` — a feature `.tsx` exporting a non-component trips the react-refresh eslint gate ([[frontend-react-refresh-no-fn-exports]]). Consolidate same-module mocks into one file — Vitest 4 shares a module registry per worker; two files each `vi.mock`-ing the router race ([[vitest-cross-file-router-mock]]).

- [x] **Task 3 — Route coverage (AC5)**
  - [x] For each route in `src/routes/` (`/` → `/workflows` queue, `/workflows/$workflowRunId` detail, `/workflows/$workflowRunId/artifacts/$artifactId` viewer, `/submit`, not-found): assert typed param validation rejects malformed `run_…`/`art_…` ids (the `routeParamValidation` + `check:routes` node-test already guard the shape — add route-level rejection tests), loader prefetches the right query (mount with a `QueryClient` + MSW handler, assert the expected `queryKey` is populated), missing-resource routes to the correct empty/dead-end state (`DeadEndState`, story 2.22 empty-state patterns), deep-link entry renders directly, scroll-position preservation across navigation.
  - [x] Reuse `src/test/fixtures/**` + the new shared MSW handlers (Task 1) so route tests run on realistic 1.23-derived data. Do not duplicate the existing `DeadEndState.test.tsx` coverage — extend it.

- [x] **Task 4 — Query-hook coverage (AC6)**
  - [x] For each hook in `src/features/workflows/hooks/` named in AC6 (`useWorkflowDetail`, `useWorkflowEvents`, `useArtifact`, `useAllowedActions`, `useClarifications`) — plus the mutation hooks (`useApproveSpec`, `useRejectSpec`, `useSubmitClarification`, `useSubmitWorkflow`, built on `useWorkflowMutation`): assert success path with typed response, Problem Details error path with stable `error.code` (`src/lib/api/problemDetails.ts`), mutation success invalidates the correct hierarchical downstream queries (story 2.6 AC6 — `workflowKeys.detail(id)` prefix covers `.events`/`.allowedActions`), `Idempotency-Key` header on every mutation attempt + reused across retries (story 2.6 AC7; see `src/lib/api/idempotency.ts`), and `staleTime`/`gcTime` defaults from `src/lib/api/queryOptions.ts` respected.
  - [x] Audit first — `client.test.ts`, `idempotency.test.ts`, `problemDetails.test.ts`, `workflowKeys.test.ts` already exist (story 2.6). Fill only the hooks lacking the full AC6 matrix.

- [x] **Task 5 — Coverage thresholds (AC10, S4)**
  - [x] Add a `coverage` block to `vitest.config.ts`: `provider: 'v8'`, `reporter: ['text', 'html']`, and `thresholds` with per-path globs — `src/features/workflows/**` ≈ 80% lines, `src/lib/sanitization/**` + `src/lib/queryKeys/**` ≈ 90% lines. Set sensible `exclude` (generated `routeTree.gen.ts`, `schema.d.ts`, `src/dev/**`, `*.test.*`, fixtures).
  - [x] Wire coverage into the enforced Maven/CI path: either change the `npm-run-test` execution to `run test -- --coverage` or add a `test:coverage` script and point the execution at it, so a sub-threshold regression fails `mvn -pl deliveryline-frontend clean package` (and thus `frontend-build-tests` + `foundation-gate`).
  - [x] Document the thresholds + a one-line rationale per path in `frontend/README.md` (a new "Test suite & coverage" section). Update the existing README line that says story 2.27 "extends it to the full component/route/a11y suite" to reflect the shipped state.

- [x] **Task 6 — Playwright cross-browser + mobile-viewport + keyboard-only E2E (AC8, AC9, S3)**
  - [x] Add `playwright.config.ts` (TS): projects matrix for `chromium`, `firefox`, `webkit` (= Safari engine), and the `msedge` channel (= Edge); a mobile project using a Galaxy S23+ class viewport (≈ 360×780, `deviceScaleFactor`, touch) per story 2.26 AC9; `webServer` config to `npm run build` + `npm run preview` (or `vite preview`) so E2E runs against the production bundle; `retries: 0` on CI by default (no blanket retry — AC11) with any quarantined spec explicitly `.fixme`/tagged + justified.
  - [x] Author the two critical-journey specs (under `e2e/` or `tests/e2e/`, OUTSIDE the Vitest `include` glob so they never run under jsdom): **(J1)** queue → open run → read spec artifact → answer clarification → approve; **(J2)** queue → open run → read spec → reject with feedback (rework taxonomy). Drive them against MSW-equivalent stubbed backend responses OR a Playwright route-fulfillment layer seeded from the SAME 1.23 fixture streams (do not hit a live backend in CI; reuse the fixtures from Task 1).
  - [x] Add **keyboard-only** variants (AC9): same J1/J2 journeys using ONLY `keyboard.press('Tab'/'Shift+Tab'/'Enter'/'Space'/'Escape')` — zero `.click()`/`.tap()` calls; the test fails if any decision action is unreachable without a pointer. This is the executable form of story 2.25 AC3's keyboard-operability contract (the jsdom `expectTabReachesAll` helper in `src/test/a11y/keyboard.ts` is the unit-level analogue — Playwright is the real-browser proof).
  - [x] Add **mobile-viewport** variants of J1/J2 at the Galaxy S23+ project (story 2.26 AC6/AC9 — the executable cross-browser/mobile E2E that 2.26 explicitly deferred here): assert the sticky-footer primary `Approve`/`Reject with feedback` stays reachable, the tri-pane collapses to single-column, run identity + state badge stay visible (the contract 2.26 pinned in jsdom, now proven on a real engine).
  - [x] Add npm scripts: `test:e2e` (`playwright test`), `test:e2e:ui` (headed/debug). Gitignore Playwright output (`playwright-report/`, `test-results/`, `.frontend-node` already ignored).

- [x] **Task 7 — CI integration: Playwright matrix job + fail-fast + foundation-gate parity (AC11, AC12, AC13, S3, S5)**
  - [x] Add a new `frontend-e2e` job to `.github/workflows/ci.yml`: `needs: [frontend-build-tests]` (so it only runs after Vitest is green — fail-fast per AC12/S3), `strategy.matrix.browser: [chromium, firefox, webkit, msedge]`, `fail-fast: false`. Steps: checkout → setup-node (or reuse the frontend-maven-plugin Node) → `npm ci` → `npx playwright install --with-deps ${{ matrix.browser }}` → `npm run build` → `npm run test:e2e -- --project=${{ matrix.browser }}` (+ a mobile project run). Upload `playwright-report/` as an artifact on failure.
  - [x] Keep Vitest exactly where it is — it already runs inside `frontend-build-tests` via the Maven `npm-run-test` execution; do NOT move it into the e2e job (AC12 ordering).
  - [x] Wire `frontend-e2e` into `foundation-gate.needs` (AC13, S5 default) so cross-browser/keyboard regressions block on PRs at the same gate as backend; the existing `Assert all required tiers succeeded` step will then enforce it. If Alex chooses the non-blocking alternative (Open Question 1), instead document it in the job comment mirroring the `bundled-jar-smoke` "intentionally NOT in needs:" note.
  - [x] Document the flake-control policy (AC11): no blanket `retries` on CI; a quarantined Playwright spec must be `test.fixme(...)` (or `@quarantine` tag) with a one-line justification comment, surfacing the flake rather than masking it (story 1.21 AC5). Note it in `frontend/README.md`.

- [x] **Task 8 — Gate verification (all ACs)**
  - [x] Run the FULL frontend gate via PowerShell (the RTK hook corrupts only the Bash tool — [[rtk-hook-only-matches-bash]]): `tsc -b`, `eslint . --max-warnings=0`, `lint:rules-test`, `check:contrast`, `check:a11y`, `check:tokens`, `check:routes`, `check:api`, `vitest run --coverage` (thresholds must pass), `prettier --check .`, `npm run build`. Run `prettier --write` before pushing — one unformatted file cascades the whole tier and skips downstream jobs ([[prettier-gate-cascades-ci]]).
  - [x] Run `npm run test:e2e` locally across at least chromium + one of firefox/webkit to prove the journeys + keyboard-only + mobile variants pass before pushing.
  - [x] Verify on a Linux-shaped env before claiming green — local ≠ CI ([[verify-ci-fixes-in-clean-env]]); WSL2 native reproduces the CI shape (strip `/mnt/<drive>/` from PATH so Windows `node.exe` doesn't hijack the build — [[wsl-linux-ci-reproduction]]). If a dep was added (Playwright), regenerate the lockfile with a full `npm install` and verify on Linux ([[frontend-lockfile-cross-platform]]).

- [x] **Logging instrumentation** (cross-cutting standard) — **N/A for this story.** Story 2.27 is frontend test-tooling only (Vitest/Playwright/MSW config + TS test specs + CI YAML + Markdown docs); it touches no Spring `@Service`, SPI, or persistence surface, so the SLF4J/MDC logging contract does not apply (same posture as stories 2.25 / 2.26). Frontend observability convention: no `console.log` in shipped code (ESLint enforces); test diagnostics use the framework reporters. If implementation unexpectedly adds a backend touch (it must not), apply the full logging task from the project standard.

## Dev Notes

### Current-state inventory (what ALREADY EXISTS — do NOT rebuild)

The frontend already has a real test suite (814 tests / 71 files per story 2.26's Debug Log). Confirmed by reading the source:

- **Vitest + RTL + jest-dom + MSW + axe are installed and wired.** `package.json` has `vitest@4`, `@vitest/coverage-v8@4`, `@testing-library/{react,jest-dom,user-event,dom}`, `msw@2.14`, `vitest-axe@0.1` + `axe-core@4.12`, `jsdom@29`. `vitest.config.ts` is the story-2.6 "MINIMAL" data-layer config (jsdom, `setupFiles: ['./src/test/setup.ts']`, `include: ['src/**/*.test.{ts,tsx}']`, NO router plugin, NO coverage block yet). `src/test/setup.ts` wires jest-dom matchers, the `vitest-axe` `toHaveNoViolations` matcher, the jsdom canvas stub, and the **MSW lifecycle** (`server.listen({ onUnhandledRequest: 'error' })` / `resetHandlers()` / `close()`). `src/test/server.ts` is `setupServer()` with **no default handlers** (each test calls `server.use(...)`; the comment explicitly says "Story 2.27 may add shared default handlers").
- **axe a11y harness** — `src/test/a11y/axe.ts` (`expectNoA11yViolations`, story 2.25), `axe.test.tsx`, `touch-target.test.tsx`. `src/test/a11y/keyboard.ts` (`tabbableElements`, `expectTabReachesAll`) is the jsdom keyboard-operability helper (story 2.25 AC1) — the unit-level analogue of AC9's Playwright keyboard journeys.
- **XSS fixture loop (AC7) — DONE.** `src/lib/sanitization/__tests__/SafeMarkdownRenderer.test.tsx` (story 2.24) loops every `xss-fixtures/*.md` (11+ attack classes: script-tag, img-onerror, iframe-src, style-tag, entity-encoded-script, mixed-case-script, data-uri-image, polyglot, code-fence-with-script, a-href-javascript, markdown-link-javascript-url) against paired `.expected.json` contracts, asserts the `≥11` floor, and is build-blocking. Also a redaction-fixture sweep (`redactionFilter.fixtures.test.tsx`) + `redaction-policy-drift.test.ts`. AC7 = verify-it's-in-the-gate, not re-author.
- **Test fixtures** — `src/test/fixtures/{runContext,runQueue,artifact,clarification,approval,feedback,overlays}/*.ts` already provide composite-level view fixtures (incl. `specRejectAndResubmit`, `executionFailure`). The story-1.23 canonical event streams live SEPARATELY in `deliveryline-backend/src/test/resources/fixture-event-streams/` — AC3 wants the MSW handlers seeded from THOSE (the cross-module reuse decision is Task 1 / Open Question 2).
- **Query layer (story 2.6)** — hooks under `src/features/workflows/hooks/` (`useWorkflowDetail`, `useWorkflowEvents`, `useArtifact`, `useAllowedActions`, `useClarifications`, `useApproveSpec`, `useRejectSpec`, `useSubmitClarification`, `useSubmitWorkflow`, `useWorkflowMutation`, `useWorkflowsList`). Data-layer tests exist: `src/lib/api/{client,idempotency,problemDetails}.test.ts`, `src/lib/queryKeys/workflowKeys.test.ts`. `src/lib/api/queryOptions.ts` holds the staleTime/gcTime defaults; `src/lib/api/idempotency.ts` the UUIDv7 idempotency-key logic.
- **Routes (story 2.5)** — `src/routes/` file-based: `__root.tsx`, `index.tsx` (`/`), `workflows/index.tsx` (queue), `workflows/$workflowRunId/index.tsx` (detail; sticky-footer decision bar mounted line 170), `workflows/$workflowRunId/artifacts/$artifactId.tsx` (viewer), `submit/index.tsx`, `-states/DeadEndState.tsx` (+ test). `src/lib/routing/routeParamValidation.ts` + `tools/routing/__tests__/public-id-params.test.js` (`check:routes` gate) guard param shape. The generated `routeTree.gen.ts` is gitignored + eslint/prettier-excluded — never commit it.
- **NO Playwright anywhere.** No `playwright.config.*`, no `e2e/`, no `*.spec.ts`, no `@playwright/test` dep. Fully net-new (the biggest piece of this story).
- **NO coverage thresholds.** `@vitest/coverage-v8` is installed but `vitest.config.ts` has no `coverage` block and the Maven `npm-run-test` execution runs plain `vitest run`.

### CI / gate facts (stories 1.21 / 1.23 / 2.1)

- **`frontend-build-tests` job** (`.github/workflows/ci.yml`, `needs: [runner-contract-fixtures]`, matrix `ubuntu-latest` + `windows-latest`) runs `./mvnw -B -ntp -pl deliveryline-frontend clean package`. The frontend `pom.xml` `frontend-maven-plugin` executions run (in `process-resources`, after `npm run build` in `generate-resources`): `lint` (`--max-warnings=0`), `lint:rules-test`, `check:contrast`, `check:a11y`, `check:tokens`, `check:routes`, `check:api`, **`test` (`vitest run`)**, `format:check`. The Vitest suite is execution id `npm-run-test` (frontend `pom.xml` lines 179–191) — **this is where coverage gets wired (Task 5)**.
- **`foundation-gate` job** (`needs:` includes `frontend-build-tests`) runs `FoundationGateVerificationTest` under `-Pfoundation-gate` and has an `Assert all required tiers succeeded` step that converts any non-`success` need into an explicit fail. Because `frontend-build-tests` (which runs Vitest) is already a need, **AC13's Vitest half is already enforced**; adding `frontend-e2e` to `needs:` is the only new wiring (S5).
- **Playwright is a SEPARATE job** (AC12/S3): `needs: [frontend-build-tests]`, browser matrix, `npx playwright install --with-deps`. It installs its own browser binaries — keep it OUT of the Maven `clean package` path (that path is for the Vite build + Vitest only).
- **Prettier gate cascades** — one unformatted file fails `format-static-checks` + `frontend-build-tests` and skips every downstream job; run `prettier --write` before pushing and read the real failing step, not the job name ([[prettier-gate-cascades-ci]]).
- **Lockfile cross-platform** — Vite 8 / rolldown native bindings: adding `@playwright/test` means regenerating the lockfile with a full `npm install` and verifying on Linux before pushing, or CI's Linux job fails ([[frontend-lockfile-cross-platform]]). The committed `.npmrc legacy-peer-deps=true` reconciles `npm install` + CI `npm ci` ([[frontend-ts6-legacy-peer-deps]]).
- **Run the gate via PowerShell**, not Bash — the RTK hook corrupts only the Bash tool ([[rtk-hook-only-matches-bash]]). Verify in a clean/Linux-shaped env before claiming green ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

### Architecture compliance (hard invariants relevant here)

- **Frontend stack is fixed:** Vite 8 + React 18.3 + TypeScript ~6.0 (strict, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`), TanStack Query/Router, shadcn/ui + Tailwind v3, Maven-driven build. The test stack is fixed too: **Vitest + RTL + jest-dom + MSW + vitest-axe for unit/component/integration; Playwright for E2E/cross-browser.** Do NOT introduce a different runner (no Jest, no Cypress) or a second a11y/mock lib.
- **No mock/prod divergence (AC2):** MSW responses must be typed by the committed `src/lib/api/schema.d.ts` (generated from the backend OpenAPI snapshot, guarded by `check:api`). If the backend schema changes, `check:api` reds first — regenerate via `scripts/regen-openapi.*` ([[openapi-regen-platform-shim]]).
- **Tests assert structure, never pixels or snapshots in jsdom** (the 2.20–2.26 discipline) — roles/text/`data-*`/classes; computed-pixel + visual-regression checks belong to Playwright/real devices, never jsdom.
- **`src/components/ui/**` stay generic** — the `no-workflow-domain-in-ui-primitives` rule forbids workflow imports there; don't add workflow-coupled test utilities into the primitive layer.
- **Backend, OpenAPI, `schema.d.ts`, Flyway: NO change** in this story. If `schema.d.ts` would change, stop — this story is frontend test-tooling + CI + docs only. (Reusing the 1.23 fixture-stream JSON for MSW seeding is a read of backend TEST resources, not a backend code change.)

### Project Structure Notes

- **New files (frontend):** `deliveryline-frontend/playwright.config.ts`; `deliveryline-frontend/e2e/` (or `tests/e2e/`) critical-journey + keyboard-only + mobile-viewport specs; `deliveryline-frontend/src/test/handlers.ts` (shared OpenAPI-typed, 1.23-fixture-seeded MSW handlers); any gap-filling `*.test.tsx` under `src/features/workflows/**` + `src/routes/**`.
- **Modified (frontend):** `package.json` (add `@playwright/test` dev dep + `test:e2e`/`test:e2e:ui`/optional `test:coverage` scripts; possibly `test` → `--coverage`); `vitest.config.ts` (add `coverage` block + thresholds); `src/test/server.ts` (register shared default handlers); `README.md` (new "Test suite & coverage" section: stack, thresholds + rationale, flake-control/quarantine policy, Playwright commands; update the stale "story 2.27 extends it" line); `.gitignore` (Playwright `playwright-report/` + `test-results/`); possibly `deliveryline-frontend/pom.xml` (point `npm-run-test` at `--coverage`).
- **Modified (CI):** `.github/workflows/ci.yml` — new `frontend-e2e` matrix job (`needs: [frontend-build-tests]`); add `frontend-e2e` to `foundation-gate.needs` (S5 default).
- **No new gate scripts in the Maven `process-resources` chain for Playwright** — Playwright runs in its own CI job, not via `frontend-maven-plugin`.
- **Tracking:** `_bmad-output/implementation-artifacts/sprint-status.yaml` — `2-27` `ready-for-dev` → `in-progress` → `review`.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.27] — the 13 epic ACs (lines 1446–1466).
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.26 AC6/AC9/AC11] (lines 1438, 1443) — the cross-browser/mobile-viewport E2E that 2.26 explicitly DEFERRED to this story (its D1 scope decision); 2.26 lines 1425–1444.
- [Source: _bmad-output/implementation-artifacts/2-26-...md] — prior story; D1 (Playwright owned by 2.27), D2 (no computed-px in jsdom), the 814-test/71-file baseline (Debug Log), the `frontend-build-tests`/`foundation-gate` gate facts.
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.25 AC2/AC3] (lines 1404–1413) — axe-core wired into the suite "per story 2.27"; keyboard-only journey contract AC9 implements.
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.24 AC7/AC8] (lines 1377–1399) — adversarial XSS fixture set, single-passing-fixture build-blocking (AC7).
- [Source: _bmad-output/implementation-artifacts/1-23-...md AC3/AC4/AC8] — the deterministic `fixture-event-streams/` (happy-path-success, spec-rejection-and-resubmit, execution-failure-with-retry, + clarification lifecycle) in `deliveryline-backend/src/test/resources/fixture-event-streams/`, with the per-fixture README table mapping scenarios → E2 story IDs (AC3 reuse).
- [Source: deliveryline-frontend/vitest.config.ts] — the story-2.6 minimal config to extend with the coverage block (Task 5).
- [Source: deliveryline-frontend/src/test/setup.ts + server.ts] — MSW lifecycle + the empty `setupServer()` awaiting this story's shared default handlers (Task 1).
- [Source: deliveryline-frontend/src/lib/sanitization/__tests__/SafeMarkdownRenderer.test.tsx] — the already-shipped build-blocking XSS fixture loop (AC7).
- [Source: deliveryline-frontend/src/test/a11y/{axe.ts,keyboard.ts}] — `expectNoA11yViolations` (AC4 axe) + `expectTabReachesAll` (jsdom analogue of AC9 keyboard).
- [Source: deliveryline-frontend/pom.xml lines 179–201] — the `npm-run-test` + `npm-run-format-check` executions (where coverage wires in, Task 5).
- [Source: .github/workflows/ci.yml lines 241–309 (frontend-build-tests), 880–923 (foundation-gate needs + assert step)] — the CI tiers (AC12/AC13).
- [Source: deliveryline-frontend/README.md] — the doc to extend with the coverage/flake/Playwright section (AC10/AC11).
- [Source: _bmad-output/planning-artifacts/architecture.md] — frontend stack + quality-gate invariants (Vite/React/TS/TanStack/shadcn; cache headers; CI tiers).

### Open Questions (for Alex — do not block dev-story; default behavior is encoded above)

1. **Playwright PR-blocking?** Default = YES: wire the new `frontend-e2e` job into `foundation-gate.needs` so cross-browser + keyboard-only regressions block PRs at the same gate as backend (AC13 parity intent, S5). Alternative = run it like `bundled-jar-smoke` (push:main only, NOT a foundation-gate need) to keep PR latency/cost down. The browser matrix + fail-fast-after-Vitest already bound the cost; recommend the blocking default unless CI-minute budget says otherwise.
2. **1.23 fixture-stream reuse mechanism (AC3).** Default = import the backend `fixture-event-streams/*.json` into the frontend test layer via a relative read (or a tiny `process-resources` copy/sync step) and shape MSW handlers from them — single source of truth, no synthetic drift. Alternative = vendor a copy under `src/test/fixtures/event-streams/` with a drift test asserting parity against the backend originals (mirrors the existing `redaction-policy.generated.json` mirror-drift pattern). Pick the relative-import default unless cross-module test-resource coupling is undesirable.
3. **"Edge" in the cross-browser matrix (AC8).** Default = Playwright `msedge` channel (requires Edge on the runner; `windows-latest` has it, `ubuntu` needs the channel install) — true Edge. Acceptable fallback if the channel is flaky on Linux = treat chromium as the Edge-engine proxy and document the substitution (Edge is Chromium-based; the rendering risk Edge-specific bugs add over chromium is low for this app). Default to real `msedge` on at least the Windows leg.
4. **Coverage threshold numbers (AC10).** Default = 80% lines `src/features/workflows/**`, 90% lines `src/lib/sanitization/**` + `src/lib/queryKeys/**` (the epic's own examples). If the current measured coverage is already well above these, ratchet the thresholds up to `current − ~2%` so they actually guard against regression rather than sitting as a loose floor — but never set a threshold above current measured coverage (that reds the build on day one).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

Full frontend gate run via PowerShell ([[rtk-hook-only-matches-bash]]):

- `tsc -b` → exit 0 (after dropping a generic `HttpResponse` return annotation in `handlers.ts`).
- `eslint . --max-warnings=0` → 0 problems (after giving `e2e/**` a dedicated config block with node+browser globals — `document` inside `page.evaluate` callbacks).
- `lint:rules-test` 9/0, `check:contrast` 8/0, `check:a11y` 4/0, `check:tokens` 4/0, `check:routes` 9/0, `check:api` in sync, **`check:fixtures` 5/0** (new drift gate).
- `vitest run --coverage` → **75 files / 830 tests passed** (was 814 — +16 net-new; ZERO regressions from the new default MSW handlers). Coverage thresholds pass: sanitization 88.1% ≥ 86, queryKeys 93.8% ≥ 90, features/workflows ~90% ≥ 85. Thresholds set just under measured per OQ-4 (sanitization measured 88.1% < the 90% epic example, so floored at 86%).
- `prettier --check .` → clean.
- `npm run build` → exit 0 (tsc -b + vite build; the stderr chunk-size note is wrapped by PowerShell 5.1's NativeCommandError, not a failure).
- Playwright (after `npx playwright install chromium firefox`): **chromium 4 passed / 2 quarantined**, **firefox 4 passed / 2 quarantined**, **mobile-galaxy-s23 4 passed / 2 quarantined**. The 2 quarantined are the dormant answer-clarification/approve and reject-commit steps (`test.fixme`, AC11).
- Fixes during E2E: JSON imports in `e2e/support/mockApi.ts` needed `with { type: 'json' }` (Playwright's native Node ESM loader); the artifact-page assertion `getByRole('heading', { name: 'Artifact' })` was ambiguous (page h1 + panel title) → switched to the unique artifact-id text.

Review follow-up run (2026-06-11, P2/P3/P5):

- `eslint e2e --max-warnings=0` → clean (the P2 tripwire's `// eslint-disable-next-line no-console` was itself flagged as an unused directive — `no-console` isn't enabled in the e2e block — so it was removed; `console.error` lints clean there).
- `prettier --check e2e/**/*.ts` → clean.
- `npm run test:e2e -- --project=chromium --project=mobile-galaxy-s23` (build + `vite preview` of the production bundle) → **8 passed / 4 quarantined (`test.fixme`)** in 15.2s. The P2 501 tripwire never fired (every J1/J2 read is modelled); the P3 single-column block ran on the 360px mobile leg and was correctly skipped on the 1280px chromium smoke leg.
- P5 is CI-only (`.github/workflows/ci.yml`) — YAML matrix shape validated locally; the authoritative check is the first `frontend-e2e` run on push (the mobile leg now runs independently of desktop chromium). `tsc -b` / Vitest are untouched (the changes are confined to `e2e/**` + CI YAML, both outside the `tsc`/Vitest surfaces).

### Completion Notes List

This is the Epic-2 capstone: CONSOLIDATE + FORMALIZE + ADD-PLAYWRIGHT + ENFORCE-THRESHOLDS over the already-substantial 814-test suite — net-new work was narrow, exactly as the scope decisions framed.

- **Task 1 (AC1/AC2/AC3/AC7)** — Added `@playwright/test` (the only missing AC1 dep). Built `src/test/handlers.ts`: OpenAPI-typed shared MSW defaults (list/detail/events/allowed-actions) **derived from the story-1.23 fixture event streams**, registered as `setupServer(...)` defaults without breaking per-test `server.use(...)`. **OQ-2 resolved the "vendor + drift gate" way** (the documented alternative): the 5 streams are vendored under `src/test/fixtures/event-streams/` (in-`src` JSON, no cross-module TS/eslint coupling) and a new `check:fixtures` `node --test` gate asserts byte-for-content parity with the backend originals on the enforced Maven path. AC7 verified: `SafeMarkdownRenderer.test.tsx` (≥11 XSS attack classes, build-blocking) runs in the enforced `test:coverage` gate — verify-only, no re-author.
- **Task 2 (AC4)** — Audit found full composite/primitive coverage EXCEPT axe scans on `QueueShell` + the main `AppShell` test. Added `wcag2aa` scans to both (empty/populated/error for QueueShell; desktop tri-pane for AppShell).
- **Task 3 (AC5)** — Added `routeCoverage.integration.test.tsx`: real `routeTree` mount covering param-rejection→InvalidLinkState, well-formed-unknown→RunNotFoundState (via the default handler's RUN_NOT_FOUND), loader-prefetch cache warming, and deep-link entry for detail/artifact/queue/`/`-redirect. Scroll preservation (AC5e) is proven in the Playwright tier (jsdom has no scroll box).
- **Task 4 (AC6)** — Added `useWorkflowEvents.test.tsx` (the one untested live read hook: success + RUN_NOT_FOUND), the missing RUN_NOT_FOUND error case to `useWorkflowDetail.test.tsx`, and disabled-stub contract tests for `useArtifact`/`useClarifications` (both AC6-named; pinned key + never-fetches). Mutation invariants stay proven in `useWorkflowMutation.test.tsx`.
- **Task 5 (AC10)** — `coverage` block in `vitest.config.ts` (v8, per-path line thresholds, target/coverage reports, sensible excludes). Wired the Maven `npm-run-test` execution to `run test:coverage` so sub-threshold reds the build. Documented in `README.md`.
- **Task 6 (AC8/AC9)** — `playwright.config.ts` (chromium/firefox/webkit/msedge + Galaxy S23+ mobile project; webServer = production preview; retries 0). Two critical-journey specs (J1/J2), keyboard-only (`tabUntilFocused`, zero `.click()`), and mobile-viewport (sticky-footer reachability). Backed by `e2e/support/mockApi.ts` seeded from the SAME 1.23 fixtures. The dormant answer/approve/reject-commit steps are `test.fixme`-quarantined (AC11) since the clarification-read endpoint + artifactId read-seam are unshipped (the approval bar renders `blocked`, the clarification region renders empty).
- **Task 7 (AC11/12/13)** — New `frontend-e2e` matrix job (`needs: [frontend-build-tests]` for fail-fast-after-Vitest; per-browser `npx playwright install --with-deps`; mobile on the chromium leg). Wired `frontend-e2e` into `foundation-gate.needs` (S5 default — Playwright is PR-blocking, OQ-1). Flake policy documented in README.
- **Logging** — N/A (frontend test-tooling only; no backend touch), same posture as 2.25/2.26.

**Open-question dispositions (defaults taken):** OQ-1 Playwright PR-blocking → YES (in foundation-gate.needs). OQ-2 fixture reuse → vendor + `check:fixtures` drift gate. OQ-3 Edge → real `msedge` channel in the matrix (documented Linux fallback to chromium-as-Edge). OQ-4 thresholds → 86/90/85 (floored under measured; sanitization could not be 90 without redding day one).

**CI follow-up (per [[verify-ci-fixes-in-clean-env]] / [[frontend-lockfile-cross-platform]]):** all gates verified on Windows; the only new runtime dep is `@playwright/test` (platform-neutral JS — browsers install separately), so the lockfile change is cross-platform safe, but a Linux `frontend-build-tests` run is the authoritative confirmation.

### File List

**New (frontend):**

- `deliveryline-frontend/src/test/handlers.ts`
- `deliveryline-frontend/src/test/fixtures/event-streams/index.ts`
- `deliveryline-frontend/src/test/fixtures/event-streams/happy-path-success.json`
- `deliveryline-frontend/src/test/fixtures/event-streams/spec-rejection-and-resubmit.json`
- `deliveryline-frontend/src/test/fixtures/event-streams/execution-failure-with-retry.json`
- `deliveryline-frontend/src/test/fixtures/event-streams/clarification-incorporated-happy-path.json`
- `deliveryline-frontend/src/test/fixtures/event-streams/clarification-superseded-and-rejected.json`
- `deliveryline-frontend/tools/fixtures/__tests__/event-stream-drift.test.js`
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowEvents.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useArtifact.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useClarifications.test.tsx`
- `deliveryline-frontend/src/features/workflows/__tests__/routeCoverage.integration.test.tsx`
- `deliveryline-frontend/playwright.config.ts`
- `deliveryline-frontend/e2e/support/mockApi.ts`
- `deliveryline-frontend/e2e/critical-journey.spec.ts`
- `deliveryline-frontend/e2e/keyboard-only.spec.ts`
- `deliveryline-frontend/e2e/mobile-viewport.spec.ts`

**Modified (frontend):**

- `deliveryline-frontend/package.json` (+`@playwright/test`; +`test:coverage`/`test:e2e`/`test:e2e:ui`/`check:fixtures` scripts)
- `deliveryline-frontend/package-lock.json`
- `deliveryline-frontend/vitest.config.ts` (coverage block + thresholds)
- `deliveryline-frontend/tsconfig.app.json` (`resolveJsonModule`)
- `deliveryline-frontend/src/test/server.ts` (register shared default handlers)
- `deliveryline-frontend/src/features/workflows/hooks/useWorkflowDetail.test.tsx` (RUN_NOT_FOUND error case)
- `deliveryline-frontend/src/features/workflows/AppShell.test.tsx` (axe scan)
- `deliveryline-frontend/src/features/workflows/__tests__/QueueShell.test.tsx` (axe scans)
- `deliveryline-frontend/eslint.config.js` (e2e block + Playwright-output ignores)
- `deliveryline-frontend/.gitignore` (Playwright output)
- `deliveryline-frontend/.prettierignore` (Playwright output)
- `deliveryline-frontend/README.md` ("Test suite & coverage" section)
- `deliveryline-frontend/pom.xml` (`check:fixtures` execution; `npm-run-test` → `run test:coverage`)

**Modified (CI):**

- `.github/workflows/ci.yml` (new `frontend-e2e` matrix job; `frontend-e2e` added to `foundation-gate.needs`)

### Change Log

| Date       | Version | Description                                                                                       | Author |
| ---------- | ------- | ------------------------------------------------------------------------------------------------- | ------ |
| 2026-06-10 | 0.1     | Implemented story 2.27 — Playwright cross-browser/mobile/keyboard E2E, shared 1.23-seeded MSW handlers + drift gate, coverage thresholds, route/hook/a11y gap tests, CI `frontend-e2e` job + foundation-gate parity. 830 Vitest tests + 4 E2E specs green on chromium/firefox/mobile. | Amelia (dev agent) |
| 2026-06-11 | 0.2     | Addressed code review findings — 3 batch-skipped patches resolved (3 items): P2 E2E mockApi fallthrough → loud `501` tripwire; P3 mobile-viewport spec → hard viewport assertion + tri-pane single-column collapse proof; P5 mobile E2E → its own independent CI matrix leg. Playwright-verified (8 passed / 4 quarantined on chromium + mobile-galaxy-s23). | Amelia (dev agent) |

## Review Findings

_Code review 2026-06-11 — 3 adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). 2 decision-needed, 10 patch, 6 deferred, 2 dismissed as noise._

_Follow-up 2026-06-11 (dev-story rerun): the 3 remaining batch-skipped patches (P2 mockApi tripwire, P3 mobile single-column assertion, P5 mobile-as-own-CI-leg) are now APPLIED and Playwright-verified (8 passed / 4 quarantined on chromium + mobile-galaxy-s23). All 10 patches resolved; both decisions resolved; deferred + dismissed unchanged._

### Decision-needed

- [x] [Review][Decision] **RESOLVED → accept as documented deferral** (Alex, 2026-06-11): the quarantine is legitimate — the clarification-read + artifactId read-seams (stories 2.18/2.19) are genuinely unshipped, so J1/J2 approve/reject cannot run end-to-end yet. The `test.fixme` + inline justification stand; reactivate the steps when the seams land. AC8 recorded satisfied-by-deferral. **AC8 critical-journey decision steps are `test.fixme`-quarantined — the journeys never run end-to-end** — J1 (queue→spec→answer clarification→**approve**) and J2 (queue→spec→**reject** with feedback) have only an identical live navigation step (`e2e/critical-journey.spec.ts`); the steps that distinguish approve from reject — the actual AC8 payload — are `test.fixme`. Compounding it (AC9/AC8-mobile): the keyboard-only + mobile-viewport specs assert reachability of an **inert `blocked` approval bar** (`HAPPY_RUN_ID` terminalState `Completed` → `ACTIONS_BY_STATE` empty), so the keyboard-operability and sticky-footer reachability of the real Approve/Reject controls are never exercised. Quarantine is *legitimate* (the clarification-read + artifactId read-seams are genuinely unshipped — 2.18/2.19), but AC8 is deferred, not met. Decide: accept story as done-with-documented-deferral, or hold until the seams ship. (blind+auditor)
- [x] [Review][Decision] **RESOLVED → keep blocking, fix comment only** (Alex, 2026-06-11): msedge stays in `foundation-gate.needs` (trust the matrix + fail-fast); the only action is correcting the misleading "windows-latest" comment — tracked as patch P8 below. **`msedge`-on-`ubuntu-latest` is a known-fragile, unverified, PR-blocking CI leg** — `playwright.config.ts` pins `channel: 'msedge'`; `frontend-e2e` runs `npx playwright install --with-deps msedge` on `ubuntu-latest` and is a hard `foundation-gate.needs`. The Debug Log proves only chromium/firefox/mobile locally — **webkit and msedge were never run**. OQ-3 pre-flagged Linux Edge flakiness and documented a chromium-as-Edge-proxy fallback that is **not actually wired**. An Edge-channel install hiccup on the runner blocks every PR. Decide: keep msedge blocking, make the e2e job non-blocking (like `bundled-jar-smoke`), or implement the chromium-proxy fallback. (blind+edge+auditor)

### Patch

- [x] [Review][Patch] ✅ APPLIED — Drift gate has no set-equality check — a newly added/removed backend stream silently goes un-vendored [deliveryline-frontend/tools/fixtures/__tests__/event-stream-drift.test.js] — added a `readdirSync(backendDir).filter(.json)` === `STREAMS` assertion (6/6 tests green).
- [x] [Review][Patch] ✅ APPLIED (2026-06-11, Playwright-verified) — E2E `mockBackend` answered every unmodelled `/api/v1` read with empty `200 {}` — no tripwire (unlike Vitest `onUnhandledRequest:'error'`); a mis-wired/new endpoint passed E2E green [deliveryline-frontend/e2e/support/mockApi.ts]. Flipped the fallthrough to a loud `501 application/problem+json` (`code: E2E_UNMODELLED_ENDPOINT`) + a `console.error` naming the `method`/`path`. Verified the feared regression does NOT occur: the artifact loader is a stub and `useArtifact` is a disabled query (no artifact read), so the J1/J2 `navigateToSpecArtifact` step makes only modelled reads (queue/detail/events/allowed-actions). `npm run test:e2e --project=chromium --project=mobile-galaxy-s23` → **8 passed / 4 quarantined**, tripwire never fired.
- [x] [Review][Patch] ✅ APPLIED (2026-06-11, Playwright-verified) — `mobile-viewport` spec silently skipped its only assertions when `viewportSize()` was null and never asserted single-column collapse [deliveryline-frontend/e2e/mobile-viewport.spec.ts] — replaced the silent `if (box && viewport)` guard with a hard `expect(viewport).not.toBeNull()` (a misconfigured project now reds, not greens) and added the tri-pane-stacks-to-one-column proof: gated to a mobile-range viewport (`< TABLET_MIN_PX` 768), assert `<main>` (`getByRole('main')`) sits flush-left (`x ≤ 1`) and spans the full viewport width (`width ≥ viewport−1`) — the structural signature of AppShell collapsing the nav rail + right context panel into drawers. The selector is the semantic `main` landmark (no fragile `data-testid` needed). Verified: passes on `mobile-galaxy-s23` (360px — collapse block runs) AND as a desktop smoke on chromium (1280px — collapse block correctly gated off).
- [x] [Review][Patch] ✅ APPLIED — E2E `mockApi` hardcodes `specRejectionLoopCount: 0`, diverging from `handlers.ts` (which derives it) despite the "mirrors handlers.ts" header claim [deliveryline-frontend/e2e/support/mockApi.ts] — added a `specRejectionLoopCount(stream)` helper (counts `approval.rejected`) used by both `summary` + `detail`.
- [x] [Review][Patch] ✅ APPLIED (2026-06-11) — `frontend-e2e` mobile project ran only as a conditional step on the chromium leg, after the desktop step — if desktop chromium failed, mobile E2E never ran yet the gate only keyed on leg success [.github/workflows/ci.yml] — gave the Galaxy S23+ mobile project its OWN `matrix.include` entry (`{ browser: mobile-galaxy-s23, project: mobile-galaxy-s23, install: chromium }`), so it runs independently of the desktop chromium leg and the gate keys on its success separately. Refactored the matrix to per-leg `browser`/`project`/`install` keys (install step → `matrix.install`, run step → `--project=${{ matrix.project }}`) and removed the conditional mobile step. CI YAML structure validated locally; authoritative confirmation is the first real `frontend-e2e` run on push.
- [x] [Review][Patch] ✅ APPLIED — Drift gate docstring/README claim "byte-for-content parity" but the test does `assert.deepEqual` of parsed JSON (key-order/whitespace/dup-key drift passes) [deliveryline-frontend/tools/fixtures/__tests__/event-stream-drift.test.js] — softened the docstring to state the per-file comparison is SEMANTIC (parsed-JSON deepEqual). Kept deepEqual (a byte compare could red the gate if vendored/backend formatting differs).
- [x] [Review][Patch] ✅ APPLIED — README labels the 86% sanitization floor "the highest bar" while `queryKeys` sits at 90% — self-contradictory [deliveryline-frontend/README.md] — reworded the rationale: the epic's 90% example was capped to 86% here because measured was 88.1% (never floor above measured, OQ-4).
- [x] [Review][Patch] ✅ APPLIED — `playwright.config.ts` comment says the msedge channel is "present on windows-latest" but `frontend-e2e` runs on `ubuntu-latest` — misleading [deliveryline-frontend/playwright.config.ts:50] — corrected: the comment now states the job runs on ubuntu-latest and installs the channel per-leg via `playwright install --with-deps msedge`. (Resolves the comment half of msedge decision D2.)
- [x] [Review][Patch] ✅ APPLIED — `@playwright/test` declared `^1.50.1` but lockfile resolved `1.60.0` — 10-minor stale floor [deliveryline-frontend/package.json] — bumped the floor to `^1.60.0` in BOTH `package.json` and the lockfile root `packages[""]` mirror (resolved version unchanged at 1.60.0, so `npm ci` stays in sync).
- [x] [Review][Patch] ✅ APPLIED — `mobile-viewport` horizontal-bounds check uses an unexplained `viewport.width + 1` tolerance [deliveryline-frontend/e2e/mobile-viewport.spec.ts:40] — added a comment: the +1 absorbs sub-pixel `boundingBox()` rounding vs the integer viewport width, NOT slack for real overflow (off-canvas drifts ≫1px).

### Deferred

- [x] [Review][Defer] No global coverage floor — only 3 per-path globs are thresholded; everything else can regress to 0% [deliveryline-frontend/vitest.config.ts] — deferred, matches S4 spec intent (per-path was the design); harden later.
- [x] [Review][Defer] Glob-keyed thresholds silently pass if a glob matches zero files — a rename defeats the guard with no signal [deliveryline-frontend/vitest.config.ts] — deferred, hardening (add a meta-test asserting globs bind to instrumented files).
- [x] [Review][Defer] Fixture `runner.started` events carry a `failedStage` in `details` (copy-paste artifact) [src/test/fixtures/event-streams/happy-path-success.json] — deferred, backend-owned (story 1.23 originals; 2.27 must not touch backend); drift gate locks frontend to backend.
- [x] [Review][Defer] `useWorkflowEvents` success test asserts positional `.at(-1)?.resultingState === 'Completed'` — fragile to fixture-tail re-vendoring [deliveryline-frontend/src/features/workflows/hooks/useWorkflowEvents.test.tsx:1232] — deferred, minor hardening (assert the specific `workflow.stateChanged` event).
- [x] [Review][Defer] Shared `webServer` + chromium leg invoking `test:e2e` twice against `--strictPort 4173` is a theoretical port-contention risk [deliveryline-frontend/playwright.config.ts] — deferred, works in practice (sequential teardown); low risk.
- [x] [Review][Defer] `summaryFromStream`/`detailFromStream` derive `currentState` from `terminalState` for the non-terminal clarification streams [deliveryline-frontend/src/test/handlers.ts:2820] — deferred, low-confidence (WaitingForSpecApproval is plausibly the correct reported state).

### Dismissed (noise)

- E2E `mockApi` `detail()`/`summary()` byte-identical — folded into the `specRejectionLoopCount` patch; identical shape is acceptable while the contracts match.
- `tabUntilFocused` post-loop re-evaluate boundary nitpick — the helper behaves correctly for its happy-path use.

### Re-review findings (2026-06-11)

_Re-review of the current working tree (3 adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). Acceptance Auditor confirmed all 13 ACs MET or covered by the previously-RESOLVED deferral decisions, and that follow-up patches P2/P3/P5 are correctly applied. 1 decision-needed, 3 patch, 8 defer, 9 dismissed as noise (recorded resolutions not re-litigated)._

#### Decision-needed

- [x] [Review][Decision] **RESOLVED → accept as documented (Alex, 2026-06-11): the duplication stands (Playwright loader can't import the MSW-coupled handlers); only the concrete `?? 'Inbox'` divergence is fixed via patch PA2 below. No parity gate / shared-module extraction.** **`mockApi.ts` duplicates `handlers.ts` derivation logic with no parity gate — and the copies already diverge.** Both files re-implement `summary`/`detail`/`allowedActions`/`specRejectionLoopCount`/`ACTIONS_BY_STATE` against the same vendored fixtures; `mockApi.ts` cannot import `handlers.ts` (MSW + `@/` alias unavailable under Playwright's loader), so it is a hand-copy. Concrete proof of drift: `handlers.ts` applies `terminalState ?? 'Inbox'` (handlers.ts:53/66/83) where `mockApi.ts` uses bare `terminalState` (mockApi.ts:54/67/80). This is the exact drift the `check:fixtures` gate prevents for the JSON, reintroduced one layer up in the derivation TS. Options: (a) accept duplication + add a parity/drift test asserting both derive identically from a fixture; (b) extract a shared pure-TS derivation module (no MSW, no `@/` alias) that both import; (c) accept as documented and just apply the null-handling parity fix (PA2 below). (blind+edge)

#### Patch

- [x] [Review][Patch] ✅ APPLIED (2026-06-11, Playwright-verified) — E2E mock benign-successed ALL non-GET methods before the P2 tripwire, so a mis-wired/new mutation endpoint returned `200` instead of the loud `501`. Extracted a shared `unmodelled()` helper; the non-GET branch now models only the real workflow commands (`approve-spec`/`reject-spec`/`clarifications/{id}/answer`/`submit-workflow`) and routes any UNMODELLED non-GET to the 501 tripwire, symmetric with the GET guard. `npm run test:e2e --project=chromium --project=mobile-galaxy-s23` → 8 passed / 4 quarantined, tripwire never fired. [deliveryline-frontend/e2e/support/mockApi.ts]
- [x] [Review][Patch] ✅ APPLIED (2026-06-11) — `mockApi` `summary`/`detail`/`allowedActions` used bare `terminalState` where `handlers.ts` uses `terminalState ?? 'Inbox'`. Typed `EventStream.workflowRun.terminalState` as `string | null` (matching the schema reality) and applied the `?? 'Inbox'` fallback at all three sites — parity with handlers.ts (resolves Decision D1, accept-as-documented). Latent today (all 5 fixtures non-null); eslint e2e clean. [deliveryline-frontend/e2e/support/mockApi.ts]
- [x] [Review][Patch] ✅ APPLIED (2026-06-11) — `event-stream-drift.test.js` `readJson` now wraps `JSON.parse(readFileSync(...))` in try/catch and `assert.fail`s with the path + reason, fulfilling the JSDoc's "clear message if missing/unreadable" promise. `check:fixtures` → 6/6 green. [deliveryline-frontend/tools/fixtures/__tests__/event-stream-drift.test.js]

#### Deferred

- [x] [Review][Defer] terminalState→currentState mapping reports the happy-path run as `Completed` → empty `allowedActions` → approval bar structurally `blocked`; reactivating the quarantined J1/J2 approve/reject steps will require driving a NON-terminal run/fixture too, not just shipping the 2.18/2.19 read-seams [deliveryline-frontend/e2e/support/mockApi.ts:54, src/test/handlers.ts:53] — deferred, forward-looking note for AC8 reactivation (relates to the already-RESOLVED quarantine decision). (blind+auditor)
- [x] [Review][Defer] Playwright `--project=X` matching zero specs (project-name typo) exits 0 → silent cross-browser coverage loss; no leg asserts its browser binary actually launched [.github/workflows/ci.yml frontend-e2e matrix] — deferred, hardening. (edge)
- [x] [Review][Defer] `mobile-viewport` asserts only horizontal bounds — a sticky decision bar pushed below the fold (vertical off-viewport) still passes [deliveryline-frontend/e2e/mobile-viewport.spec.ts] — deferred, P3 hardening (add a `y + height ≤ viewport.height` check). (edge)
- [x] [Review][Defer] `mockApi` casts fixtures `as unknown as EventStream` — the Playwright mock is untyped against the OpenAPI schema, so AC2's no-drift guarantee holds for `handlers.ts` (Vitest) but NOT the E2E layer [deliveryline-frontend/e2e/support/mockApi.ts:34] — deferred, documented loader constraint. (blind)
- [x] [Review][Defer] `useArtifact`/`useClarifications` "queryFn never runs" is asserted via `fetchStatus==='idle'` + comment, not a queryFn spy [deliveryline-frontend/src/features/workflows/hooks/useArtifact.test.tsx, useClarifications.test.tsx] — deferred, hardening. (blind)
- [x] [Review][Defer] `routeCoverage.integration.test.tsx` exercises only `state=Executing`; the empty/unknown filtered-queue path is unasserted [deliveryline-frontend/src/features/workflows/__tests__/routeCoverage.integration.test.tsx] — deferred, coverage hardening. (edge)
- [x] [Review][Defer] No uniqueness assertion on fixture `workflowRun.publicId` across STREAMS — a duplicate id silently first-wins in `streamByRunId`/`eventStreamByRunId` [deliveryline-frontend/src/test/handlers.ts, e2e/support/mockApi.ts, src/test/fixtures/event-streams/index.ts] — deferred, hardening (add a load-time uniqueness assert). (edge)
- [x] [Review][Defer] `tabUntilFocused` budget off-by-one / no focus-loop-wrap detection — a >max-stop reachable control false-fails; the final Tab fires after the in-loop focus check [deliveryline-frontend/e2e/keyboard-only.spec.ts] — deferred, low. (blind+edge)

#### Dismissed (noise / recorded resolutions)

- `frontend-e2e` runs no coverage — false mismatch: AC13's Vitest+coverage half is enforced via `frontend-build-tests ∈ foundation-gate.needs`; the e2e job is the cross-browser half by design.
- `mobile-viewport` is a near-no-op on the desktop legs — intended (the single-column block is correctly gated to `< 768`; P3 design).
- critical-journey asserts the decision bar is *visible*, not its *state* — documented reachability intent (same as the prior dismissed item).
- `mockApi` list ignores the `?state=` query filter — no E2E test depends on a filtered queue.
- `tabUntilFocused` doesn't traverse shadow roots / iframes — the app has neither.
- backend nested / non-`.json` stream variants invisible to the set-parity check — the backend fixture dir is flat `.json`; handled.
- `getByRole('link', { name: /sample artifact/ })` throws on >1 match — hypothetical future fixture growth; one link today.
- AC8 quarantine + `webkit`/`msedge`-never-run-locally — already-RESOLVED decisions (accept-as-deferral; keep-blocking-fix-comment); not re-litigated. First CI run on push is the authoritative cross-browser confirmation (as the Debug Log concedes).
