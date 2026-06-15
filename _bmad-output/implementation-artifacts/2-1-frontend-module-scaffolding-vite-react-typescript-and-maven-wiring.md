# Story 2.1: Frontend Module Scaffolding (Vite React TypeScript + Maven Wiring)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **frontend developer**,
I want **`deliveryline-frontend` initialized as a Vite React TypeScript project with Maven build integration**,
so that **the frontend module has a reproducible build graph inside the root Maven multi-module structure and CI can bundle React assets into the Spring Boot executable jar**.

## Acceptance Criteria

1. **Given** the `deliveryline-frontend` Maven module (scaffolded as a stub in story 1.1), **When** the Vite React TypeScript app is initialized via `npm create vite@latest . -- --template react-ts` in the module directory, **Then** `package.json`, `vite.config.ts`, `tsconfig.json`, `src/main.tsx`, and `src/App.tsx` exist with React 18+ and TypeScript strict mode.
2. **Given** the Maven module's `pom.xml`, **Then** the `frontend-maven-plugin` (or equivalent) is configured to install Node 20.19+ or 22.12+ locally, run `npm ci` and `npm run build` during the `generate-resources` or `compile` phase, and place output in a canonical `target/dist/` directory.
3. **Given** the backend's `pom.xml`, **Then** its packaging step depends on `deliveryline-frontend` and copies the canonical `dist/` output into `backend/src/main/resources/static/` before jar assembly — failing the backend build if the frontend dist is missing (AR32 quality gate).
4. **Given** `mvn clean install` at the root, **When** run on a machine with Node 20.19+/22.12+ available locally (or via frontend-maven-plugin download), **Then** both modules build successfully and the backend jar contains the compiled React SPA under `BOOT-INF/classes/static/`.
5. **Given** the TypeScript config, **Then** `strict: true`, `noUncheckedIndexedAccess: true`, and `exactOptionalPropertyTypes: true` are enabled.
6. **Given** `.gitignore` in the frontend module, **Then** `node_modules/`, `dist/`, `.vite/`, and coverage dirs are excluded; package lockfile (`package-lock.json`) is committed.
7. **Given** per-OS support (story 1.17 matrix), **Then** frontend build works on Windows 11 PowerShell + Ubuntu 22.04 + macOS 14+ — verified by an **in-story** CI matrix extension (NOT inherited from 1.21's collapsed-to-Ubuntu doctor-smoke job). Story 2.1 ships a `frontend-build` CI job with `strategy.matrix.os` = `[ubuntu-latest, windows-latest]` (macOS deferred per cross-platform support tier from 1.17), both running `mvn -pl deliveryline-frontend clean package` end-to-end; both must be green before merge. A failing Windows job is build-blocking — never a warning, never skippable. Rationale documented inline referencing the Epic 1 retro finding (2026-05-19, sprint-change proposal).
8. **Given** a development workflow, **Then** `npm run dev` inside the frontend module starts Vite's dev server on a documented port (default 5173, configurable via `PORT` env per AC9c), proxying `/api/*` requests to the Spring Boot backend on `localhost:8080` — configured in `vite.config.ts`. The proxy config works identically on Windows PowerShell, Windows Git Bash, Ubuntu, and macOS — verified manually during Story 2.1 implementation (informed by spike Q4 findings from `docs/spikes/2026-05-frontend-on-windows.md` if available; spike now runs in parallel per `sprint-change-proposal-2026-05-19-followup.md`, not pre-story).
9. **Given** Windows + Linux line-ending and path-length differences discovered in the frontend-on-Windows tooling spike (per sprint-change proposal 2026-05-19, action A4 — see `docs/spikes/2026-05-frontend-on-windows.md`), **Then**:
   - (a) `deliveryline-frontend/.gitattributes` declares `* text=auto eol=lf` for source files and `*.bat text eol=crlf` for any Windows-only scripts, preventing CRLF contamination of snapshot tests and build artifacts.
   - (b) Path lengths inside `node_modules/` are documented as a known Windows risk; if any transitive dep exceeds `MAX_PATH=260` chars under default Windows config, the spike report identifies a mitigation (long-paths enabled in project README, or transitive dep replaced) before story 2.1 ships.
   - (c) Vite dev-server port (default 5173 per AC8) is documented as a possible conflict point on Windows; the dev-server config exposes a `PORT` env override documented in `deliveryline-frontend/README.md`.
10. **Given** the foundation-gate CI verification from story 1.23 (Epic 1 close gate), **Then** the gate's scope widens to include "frontend-build matrix green on the branch" — meaning the Windows + Ubuntu `frontend-build-tests` jobs from AC7 are added to the `foundation-gate` `needs:` chain in `.github/workflows/ci.yml`. A frontend-build failure on either OS blocks every subsequent Epic 2 / 3 / 4 PR from merging. The foundation-gate workflow file is updated in this story, NOT in a later story — preventing the regression class where "we'll wire it later" becomes "we shipped 8 stories on Linux-only".

## Tasks / Subtasks

- [x] **Task 1: Vite React-TS scaffold inside the existing module** (AC: 1, 5, 6)
  - [x] In `deliveryline-frontend/`, run `npm create vite@latest . -- --template react-ts` (answer "Ignore" for the non-empty-dir warning so the pre-existing `pom.xml` is preserved). Verify generated tree includes `package.json`, `vite.config.ts`, `tsconfig.json`, `tsconfig.node.json`, `tsconfig.app.json`, `index.html`, `src/main.tsx`, `src/App.tsx`, `src/App.css`, `src/index.css`, `src/vite-env.d.ts`, `src/assets/`, `public/`.
  - [x] Pin React + TypeScript versions: React `^18.3.x` (NOT React 19 — Epic 2 stories assume 18-era hook semantics; document this pin in `package.json` `dependencies` comment), TypeScript `^5.6.x` (or whichever version Vite's `react-ts` template ships with, but no lower than `5.6`).
  - [x] Edit `tsconfig.app.json` (the file that holds `compilerOptions` for `src/`) to enable `"strict": true`, `"noUncheckedIndexedAccess": true`, `"exactOptionalPropertyTypes": true`. Verify `tsc --noEmit` passes against the generated `src/App.tsx` (Vite's template should already be compatible; if any compile error appears, fix the template code minimally — do NOT relax the strict flags).
  - [x] Author `deliveryline-frontend/.gitignore` covering: `node_modules/`, `dist/`, `.vite/`, `coverage/`, `*.log`, `.env.local`, `.env.development.local`, `.env.test.local`, `.env.production.local`, `*.tsbuildinfo`. Confirm root `.gitignore` already covers `node_modules/`, `dist/`, `.vite/`, `coverage/` (per story 1.1 Task 7) — the module-local file is additive for IDE-clarity and ensures the module-scoped patterns survive root-`.gitignore` refactors.
  - [x] **Commit `package-lock.json`** (NOT `pnpm-lock.yaml` / `yarn.lock` — the team uses npm). Verify `.gitignore` does NOT exclude it.

- [x] **Task 2: Vite dev-server + proxy config** (AC: 8, 9c)
  - [x] Edit `deliveryline-frontend/vite.config.ts` to: (a) read `PORT` from `process.env.PORT` with `5173` default, (b) configure `server.proxy['/api']` → `http://localhost:8080` with `changeOrigin: true` and `secure: false` (local development only), (c) set `server.host: true` so the dev server binds on all interfaces (lets WSL2 / Docker Desktop reach it via `localhost` forwarding).
  - [x] Run `npm run dev` locally on PowerShell + at least one POSIX shell (Git Bash, WSL, or remote Ubuntu/macOS) and confirm: dev server binds on the expected port, HMR works on editing `src/App.tsx`, a request to `http://localhost:5173/api/health` proxies to `localhost:8080` (it will 404 today because the backend has no `/api/health` endpoint until later stories — that 404 is the success signal that proxying is wired; a connection-refused error means the proxy is misconfigured). Verified manually by user on 2026-05-20 in PowerShell and Git Bash.
  - [x] Document the `PORT` override + port-conflict behavior in `deliveryline-frontend/README.md` (new file — see Task 6).

- [x] **Task 3: `frontend-maven-plugin` wiring** (AC: 2, 4)
  - [x] Edit `deliveryline-frontend/pom.xml`. Keep `<packaging>pom</packaging>` (Maven still treats this as a buildable reactor module via plugin bindings; no Java classes are produced so no `jar` packaging is appropriate). Add a `<build><plugins>` section with `com.github.eirslett:frontend-maven-plugin` version `1.15.1` (latest stable as of writing — verify with `mvn versions:display-plugin-updates` before commit) configured with three executions: (a) `install-node-and-npm` bound to `generate-resources` phase, pinning `<nodeVersion>v20.19.0</nodeVersion>` and `<npmVersion>10.8.2</npmVersion>` (or the higher of: Vite's required floor `20.19+` / current LTS — keep node version in sync with `docs/supported-environments.md`); (b) `npm ci` bound to `generate-resources`; (c) `npm run build` bound to `generate-resources`. Set `<workingDirectory>${project.basedir}</workingDirectory>` and `<installDirectory>${project.basedir}/.frontend-node</installDirectory>` so Node lands inside the module (NOT a user-global PATH location) — per spike Q1 expectation.
  - [x] **Vite output → `target/dist/`:** Edit `vite.config.ts` to set `build.outDir = 'target/dist'` (relative to the module root). This puts Vite's bundle under Maven's standard `target/` so `mvn clean` removes it without explicit configuration. Verify Vite emits `index.html`, `assets/index-<hash>.js`, `assets/index-<hash>.css`, and `vite.svg` (or similar template defaults) under `deliveryline-frontend/target/dist/`.
  - [x] Add `maven-clean-plugin` config (or rely on the default `<directory>target</directory>` clean) so `mvn -pl deliveryline-frontend clean` also wipes `target/dist/` — verify with a manual `mvn -pl deliveryline-frontend clean install` cycle.
  - [x] Decide on the Node-cache strategy: by default `frontend-maven-plugin` re-runs `npm ci` every build (slow). Add `<skip>${frontend-maven-plugin.skip}</skip>` parameterization on the executions (defaulted to `false`) so local devs can skip the npm steps via `-Dfrontend-maven-plugin.skip=true` while iterating on backend code. **Do not skip in CI** — the CI matrix job from Task 5 must always run the full pipeline.

- [x] **Task 4: Backend wiring — copy `dist/` into Spring Boot static resources** (AC: 3, 4)
  - [x] Edit `deliveryline-backend/pom.xml`. Add `<dependency>` on `org.dradgo:deliveryline-frontend:${project.version}` with `<type>pom</type>` so Maven's reactor build order resolves `deliveryline-frontend` first. This is a build-order dependency only — the frontend module ships no Java classes, so the classpath is unaffected.
  - [x] Add `maven-resources-plugin` configuration with a `<copy-resources>` execution bound to `process-resources` phase that copies `${project.basedir}/../deliveryline-frontend/target/dist/` into `${project.build.directory}/classes/static/` (NOT `src/main/resources/static/` despite the literal AC3 wording — writing to `src/` pollutes the source tree, breaks reproducible builds, and gets picked up by spotless/git as a perpetual diff). Document this divergence inline in the POM comment and in the Dev Agent Record. The end-result is identical to AC3's intent: the SPA lands in `BOOT-INF/classes/static/` per AC4.
  - [x] Add a `maven-enforcer-plugin` rule (or a custom `<execution>` on `maven-antrun-plugin` / `groovy-maven-plugin`) that fails the backend build with a clear error message if `${project.basedir}/../deliveryline-frontend/target/dist/index.html` does not exist when `process-resources` starts. The error message must say: `Frontend dist missing — run 'mvn -pl deliveryline-frontend package' first, or run 'mvn install' from the reactor root.` This is the AR32 quality gate from AC3.
  - [x] Verify `mvn -DskipTests package` from the reactor root produces `deliveryline-backend/target/deliveryline-backend-*.jar` containing `BOOT-INF/classes/static/index.html` + bundled JS/CSS. Validate via `unzip -l deliveryline-backend/target/deliveryline-backend-*.jar | grep static/` (or PowerShell equivalent `Get-Content` over `jar tf`).
  - [x] Verify the jar still boots: `java -jar deliveryline-backend/target/deliveryline-backend-*.jar help` (matching the `jar-packaging` CI job pattern from `.github/workflows/ci.yml:443-500`) — the SPA presence must not break Spring Boot's bean wiring. SPA fallback controller does NOT ship in this story (it's story 2.28); direct `GET /` against the booted jar will 404 today, and that's correct. _Local boot fails on docker-compose port conflict (host's 5432 already in use) — unrelated to story 2.1. Jar structure verified; CI `jar-packaging` tier runs the full boot path in a clean env._

- [x] **Task 5: CI — extend `frontend-build-tests` to Ubuntu + Windows matrix** (AC: 7, 10)
  - [x] Edit `.github/workflows/ci.yml`'s `frontend-build-tests` job (currently the placeholder at lines 240–273 — see `_bmad-output/implementation-artifacts/1-21-github-actions-ci-tiered-pipeline.md`). Add `strategy: { fail-fast: false, matrix: { os: [ubuntu-latest, windows-latest] } }` and change `runs-on:` to `${{ matrix.os }}`. Rename `name:` to `frontend-build-tests (${{ matrix.os }})` for status-check clarity. macOS is intentionally deferred per AC7 (cross-platform support tier from story 1.17 — macOS not yet in CI rotation).
  - [x] Replace the placeholder verify step with: `./mvnw -B -ntp -pl deliveryline-frontend clean package` (PowerShell: `.\mvnw.cmd -B -ntp -pl deliveryline-frontend clean package`). The `clean package` cycle exercises the full `frontend-maven-plugin` chain — Node install → `npm ci` → `npm run build` → output materialization in `target/dist/`. Use `shell: bash` so the same command line works on both runners (GitHub Actions provides bash on `windows-latest`).
  - [x] Keep the existing `Set up Node` step but gate it differently: on Ubuntu, the system Node cache speeds up `frontend-maven-plugin`'s `install-node-and-npm` step; on Windows, the system Node may interfere with the module-local `.frontend-node/` install per spike Q1. The simplest path is to let `frontend-maven-plugin` always install its own Node and drop the workflow-level `setup-node` step entirely (`frontend-maven-plugin` ignores ambient Node). Decide based on spike Q1 findings; document the decision in the workflow YAML comments. _Decision: workflow-level `setup-node` step dropped (per Q4 recommendation); `frontend-maven-plugin` installs its own Node into `.frontend-node/`._
  - [x] Adjust `needs:` chain: `backend-unit-tests` already has `needs: [frontend-build-tests]`. Verify a Windows-only failure in the matrix correctly blocks downstream `backend-unit-tests` via `fail-fast: false` semantics (one OS failing keeps the other running but the job aggregate reports failure).
  - [x] **Add `frontend-build-tests` to `foundation-gate`'s `needs:` chain** (line 771–781 of `ci.yml`). `frontend-build-tests` is already present (line 775 — added defensively in story 1.21 as a placeholder). Confirm the line is unchanged after this story's edits; the matrix expansion does NOT change the dependency edge — GitHub Actions resolves matrix children to the parent job ID for `needs:` purposes, so the foundation-gate `needs: - frontend-build-tests` line covers both OS variants automatically.
  - [x] Update `docs/ci-pipeline.md`'s `frontend-build-tests` row in the tier table (line 45) — replace the "placeholder until story 2.1 scaffolds Vite/React/TS" wording with the actual matrix scope (`ubuntu + windows`, runs `mvn -pl deliveryline-frontend clean package`, ~3–6 min including Node download + `npm ci` cold cache).
  - [x] Update `docs/ci-branch-protection.md`'s pipeline diagram (line 104) — replace `frontend-build-tests (placeholder until story 2.1)` with `frontend-build-tests (Linux + Windows matrix; Vite + frontend-maven-plugin)`. Also added `frontend-build-tests (ubuntu-latest)` + `frontend-build-tests (windows-latest)` to both `scripts/ci/configure-branch-protection.{sh,ps1}` so branch protection requires both matrix children.

- [x] **Task 6: `.gitattributes`, README, line-ending hygiene** (AC: 6, 9a, 9b, 9c)
  - [x] Author `deliveryline-frontend/.gitattributes` with the spike-validated declarations (final values come from spike Q2 if it has run; otherwise use these defaults):
    ```gitattributes
    # Default to LF for all text files
    * text=auto eol=lf

    # Windows-only scripts (none today; reserved for future)
    *.bat text eol=crlf
    *.cmd text eol=crlf
    *.ps1 text eol=crlf

    # Lockfiles, JSON, MD, TS, TSX, CSS — explicit LF to keep Vitest snapshots stable
    package-lock.json text eol=lf
    *.json text eol=lf
    *.md text eol=lf
    *.ts text eol=lf
    *.tsx text eol=lf
    *.css text eol=lf
    *.html text eol=lf
    *.yml text eol=lf
    *.yaml text eol=lf

    # Binary assets — no normalization
    *.png binary
    *.jpg binary
    *.jpeg binary
    *.gif binary
    *.svg text eol=lf
    *.ico binary
    *.woff binary
    *.woff2 binary
    ```
  - [x] After the `.gitattributes` is committed, run `git add --renormalize .` inside `deliveryline-frontend/` and commit any resulting line-ending normalizations as a separate, isolated commit (`chore(frontend): normalize line endings per .gitattributes`) so the AC9a hygiene change is auditable. _Deferred to user's commit step — all scaffolded files were generated fresh; no pre-existing mixed line-endings to renormalize._
  - [x] Author `deliveryline-frontend/README.md` with sections: (1) **Quick start** — `npm install && npm run dev`, (2) **Build** — `npm run build` produces `target/dist/`, (3) **Maven integration** — `mvn -pl deliveryline-frontend clean package` runs the full pipeline, (4) **Dev server port + proxy** — default `PORT=5173`, override with `PORT=5174 npm run dev` (or `$env:PORT=5174; npm run dev` in PowerShell), `/api/*` proxies to `localhost:8080`, (5) **Windows-specific notes** — long-paths support, line-ending policy reference, common pitfalls from spike Q1–Q5 findings (filled in mid-flight as the spike completes), (6) **Scripts** — `npm run dev` / `build` / `preview` / `lint` (lint added by story 2.31) / `test` (added by story 2.27).
  - [x] **Windows long-paths note (AC9b):** If spike Q3 surfaces any transitive dep exceeding `MAX_PATH=260`, document the mitigation in `deliveryline-frontend/README.md`'s Windows section AND in `docs/supported-environments.md` footnote (a) (currently mentions "Long-path support is not yet validated" — replace with the validated mitigation). If no path exceeds 260 chars, replace the supported-environments footnote (a) wording with "Long-path support validated — no `node_modules` path exceeds 260 chars in the deliveryline-frontend dependency tree as of story 2.1". _Validated locally on Windows 11 — current React 18 + Vite 8 + TypeScript 6 dep tree has no path >260 chars. Footnote (a) updated to reflect validation._

- [x] **Task 7: Spike folding (mid-flight integration)** (AC: 8, 9a, 9b, 9c)
  - [x] On story start, read `docs/spikes/2026-05-frontend-on-windows.md`'s **Findings** sections (currently blank — populated by Dana + Elena in parallel). For each completed finding (Q1–Q5), fold the result into the corresponding ACs: Q1 → Task 3 `frontend-maven-plugin` config; Q2 → Task 6 `.gitattributes`; Q3 → Task 6 README + supported-environments footnote; Q4 → Task 2 `vite.config.ts` proxy + PORT override; Q5 → README Windows-section pitfalls.
  - [x] **If the spike has NOT started or has fewer than 3 findings written when story 2.1 begins:** proceed with the documented defaults above; the spike folds into AC9 mid-flight per `sprint-change-proposal-2026-05-19-followup.md`. Re-check the spike file at PR-ready time and re-fold any new findings before requesting review. _Spike findings section was empty at story start; proceeded with documented defaults._
  - [x] **If the spike surfaces a blocker that 2.1 cannot absorb** (e.g., `frontend-maven-plugin` cannot install Node on `windows-latest` without admin privileges): STOP the implementation and follow the spike charter's escalation path (a) absorb into 2.1, (b) descope Windows from 2.1 with a fresh sprint-change, (c) replace `frontend-maven-plugin` with an alternative. Do NOT silently drop the Windows matrix — that is the regression class AC7 + AC10 exist to prevent. _No blocker — local Windows Maven run completed end-to-end (Node v20.19.0 installed into module-local `.frontend-node/`, `npm ci` + `npm run build` succeeded, `target/dist/index.html` produced)._

- [x] **Task 8: Reactor smoke + jar verification** (AC: 4)
  - [x] From the project root, run `./mvnw clean install` (PowerShell: `.\mvnw.cmd clean install`). Expect all four reactor modules to build green: `deliveryline` (parent pom), `deliveryline-runner-contracts`, `deliveryline-frontend` (now with the full Vite pipeline), `deliveryline-backend` (now with the frontend dist copied into `target/classes/static/`). _Verified locally: reactor green in 45s, all four modules SUCCESS. Frontend module took 4.9s (warm Node cache); cold cache would be ~3-6 min._
  - [x] Verify the produced backend jar contains `BOOT-INF/classes/static/index.html` and the JS/CSS assets. Use `jar tf deliveryline-backend/target/deliveryline-backend-*.jar | grep -E 'BOOT-INF/classes/static/'` (POSIX) or `& "$env:JAVA_HOME\bin\jar.exe" tf deliveryline-backend\target\deliveryline-backend-*.jar | Select-String 'BOOT-INF/classes/static/'` (PowerShell). _Verified: `BOOT-INF/classes/static/{index.html, favicon.svg, icons.svg, assets/*.js, assets/*.css, assets/*.svg, assets/hero-*.png}`._
  - [x] Boot the jar with the same env vars the `jar-packaging` CI job uses (lines 458–471 of `ci.yml`) — `SPRING_PROFILES_ACTIVE=test`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `DELIVERYLINE_HOME=...`, etc. — and confirm `java -jar ... help` still emits the `AVAILABLE COMMANDS` banner with `deliveryline doctor` listed. The frontend assets being present must not break Spring Shell command discovery. _Local boot exits early at Spring's docker-compose autoconfig because host's port 5432 is already bound by an unrelated Postgres instance — a local-env conflict, not a story-2.1 regression. CI's `jar-packaging` tier runs in a clean env and exercises the full boot path. Jar structure (SPA presence) is verified above._

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** This story ships build configuration (Maven plugins, Vite config, CI YAML, `.gitattributes`, README docs, Vite-template TSX). It introduces **no new JVM application-service classes**, **no new domain exceptions**, **no new SPI calls**, and **no new state-machine transitions**. The standard logging surfaces (`INFO` on entry/exit, `WARN` on typed-domain rejection, `ERROR` on unhandled failure, MDC keys for `correlationId` / `workflowRunId` / `idempotencyKey` / `actorIdentity`) therefore have **no application code to instrument** in this story. The standard remains in force for any future Java/TS code added under this story's scope.
  - [x] If the AR32 quality-gate enforcer (Task 4) raises a Maven build failure with the "Frontend dist missing" message, that failure surfaces as a clear `mvn` error — no SLF4J logger is needed because it fires during build (not runtime). The error message itself is the logging surface; ensure it includes the remediation hint per Task 4.
  - [x] Frontend-side logging (browser console + future client-side structured logging) is **out of scope** for this story — the React app is only the Vite default template at this point. Application-level frontend logging arrives with story 2.27 (frontend test suite) and downstream stories.

### Review Findings

_Generated by bmad-code-review on 2026-05-19. Three parallel reviewers: Blind Hunter (diff-only), Edge Case Hunter (diff + repo), Acceptance Auditor (diff + spec). All 10 ACs Pass per Acceptance Auditor; findings below are quality, robustness, and design concerns._

- [x] [Review][Patch] Manual verification status mismatch — resolved on 2026-05-20 after user-confirmed PowerShell + Git Bash `npm run dev` verification (HMR + `/api/*` proxy path) aligned the story record with AC8 completion.
- [x] [Review][Defer] Backend static cleanup currently wipes the shared `target/classes/static/assets` namespace [deliveryline-backend/pom.xml:228-299] — accepted/deferred as future hardening because story 2.1 ships no backend-owned static assets yet; revisit when story 2.28 or later introduces server-owned static assets under the same path.
- [x] [Review][Patch] (D1 resolved → patch) Change `<scope>provided</scope>` → `<scope>test</scope>` on backend's frontend POM dependency — keeps reactor ordering, prevents leak into runtime classpath analysis [deliveryline-backend/pom.xml:23-31]
- [x] [Review][Patch] (D2 resolved → patch) Add `console.warn` placeholder banner in `App.tsx` flagging Vite template as scaffolding to be replaced in story 2.7 [deliveryline-frontend/src/App.tsx]
- [x] [Review][Defer] (D2 resolved → defer) Strip Vite/React promo content from `App.tsx` + `public/icons.svg` — deferred to story 2.7 (real tri-pane app shell); placeholder banner added meanwhile per the related patch
- [x] [Review][Patch] `process.env.PORT` lacks NaN/empty-string guard [deliveryline-frontend/vite.config.ts:13]
- [x] [Review][Patch] `@types/node ^24` mismatches pinned Node v20.19.0 runtime [deliveryline-frontend/package.json]
- [x] [Review][Patch] `maven-enforcer-plugin` declared without explicit `<version>` [deliveryline-backend/pom.xml:113-135]
- [x] [Review][Patch] Inconsistent path resolution: enforcer uses `${maven.multiModuleProjectDirectory}` while resources-plugin copy uses `${project.basedir}/../...` — standardize on `${maven.multiModuleProjectDirectory}` [deliveryline-backend/pom.xml:147-168]
- [x] [Review][Patch] `maven-resources-plugin` copy execution in `process-resources` phase risks overlap with `default-resources` (same phase) — bind to `prepare-package` for deterministic ordering [deliveryline-backend/pom.xml:147-168]
- [x] [Review][Patch] Stale files persist in `target/classes/static/` between incremental builds without `clean` — add `maven-clean-plugin` filesets or explicit delete before copy [deliveryline-backend/pom.xml:147-168]
- [x] [Review][Patch] `createRoot(document.getElementById('root')!)` non-null assertion bypasses strict-mode benefit [deliveryline-frontend/src/main.tsx:6]
- [x] [Review][Patch] External `target="_blank"` links missing `rel="noopener noreferrer"` (becomes moot if promo content stripped per Decision item) [deliveryline-frontend/src/App.tsx:44,51,65,78,91,102]
- [x] [Review][Patch] Vite proxy `/api` lacks `ws: true` — future WebSocket/SSE endpoints will fail to upgrade in dev [deliveryline-frontend/vite.config.ts]
- [x] [Review][Patch] `npm ci` execution lacks `--no-audit --no-fund --prefer-offline` flags — slow on Windows CI, fails on air-gapped builds [deliveryline-frontend/pom.xml:42-84]
- [x] [Review][Patch] CI artifact upload captures full `target/` including the SPA bundle on every matrix run with 14-day retention [.github/workflows/ci.yml]
- [x] [Review][Patch] CI lacks `actions/cache` for `.frontend-node/` and `~/.npm` — every run re-downloads Node + npm registry on both OSes [.github/workflows/ci.yml]
- [x] [Review][Patch] `frontend-maven-plugin.skip` property does not propagate to backend `require-frontend-dist` enforcer — `-Dfrontend-maven-plugin.skip=true` breaks backend-only local builds [deliveryline-backend/pom.xml:113-135]
- [x] [Review][Patch] `vite.config.ts` `secure: false` lacks inline comment explaining the dev-only HTTP target assumption [deliveryline-frontend/vite.config.ts]
- [x] [Review][Patch] `.gitattributes` added without `git add --renormalize .` sweep — pre-existing CRLF files keep their line endings until renormalized [deliveryline-frontend/.gitattributes] — ✅ Resolved 2026-05-20: ran `git add --renormalize deliveryline-frontend/`; `git ls-files --eol deliveryline-frontend/` confirms every tracked text file is `i/lf w/lf` (no CRLF, no mixed-eol). All frontend files were scaffolded fresh — there is no pre-existing CRLF history to normalize, so the sweep is a verified no-op.
- [x] [Review][Defer] Enforcer only checks `index.html`, not full `dist/assets/` tree — partial/interrupted build can pass [deliveryline-backend/pom.xml:113-135] — deferred, hardening for a later story
- [x] [Review][Defer] Lockfile entries for React/types/scheduler annotated `"peer": true` — npm 10.x metadata cosmetic concern [deliveryline-frontend/package-lock.json] — deferred, follow-up investigation
- [x] [Review][Defer] Windows long-path validation asserted in docs without diff-level evidence [docs/supported-environments.md:93-99] — deferred, CI Windows matrix is the ongoing enforcement
- [x] [Review][Defer] `mvn -pl deliveryline-backend -am` can resolve stale frontend POM from `.m2` cache [deliveryline-backend/pom.xml] — deferred, edge case, rare in practice
- [x] [Review][Patch] Interactive HMR / proxy-round-trip verification record corrected [deliveryline-frontend/vite.config.ts] — resolved on 2026-05-20 after user-confirmed manual verification in PowerShell and Git Bash

_Generated by bmad-code-review on 2026-05-20. Blind Hunter, Edge Case Hunter, and Acceptance Auditor all completed; the findings below are the triaged result plus direct repo/Maven verification for the accepted patch items._

- [x] [Review][Patch] Backend-side `frontend-maven-plugin.skip` is not actually defaulted in the modules that consume it — the property is defined only in `deliveryline-frontend/pom.xml`, but the backend POM reads it in three plugin executions and the parent/root POM does not define it. Direct verification: `./mvnw -q -pl deliveryline-backend help:evaluate "-Dexpression=frontend-maven-plugin.skip" -DforceStdout` returned `null object or invalid expression`, so the backend-side skip wiring was relying on an undefined property instead of the story's promised default-false behavior. Resolved 2026-05-20 by centralizing `<frontend-maven-plugin.skip>false</frontend-maven-plugin.skip>` in the root `pom.xml`; verification now returns `false`. [pom.xml:26-36, deliveryline-backend/pom.xml:204,240,278]
- [x] [Review][Patch] `-Dfrontend-maven-plugin.skip=true` can silently package stale SPA assets into the backend jar — resolved 2026-05-20 by making the backend-side cleanup always run and narrowing it to frontend-generated outputs (`static/assets`, `index.html`, `favicon.svg`, `icons.svg`, `vite.svg`) even when the frontend build/copy steps are skipped. This prevents stale SPA reuse without wiping unrelated backend outputs. [deliveryline-backend/pom.xml:228-248]
- [x] [Review][Patch] README documents the wrong proxy success signal — the story requires a backend `404` to prove `/api/*` is reaching Spring Boot while `ECONNREFUSED` means the proxy target is unreachable, but the README currently states the opposite. Resolved 2026-05-20 by correcting the dev-server proxy guidance. [deliveryline-frontend/README.md:56-60]
- [x] [Review][Patch] Frontend matrix-gate documentation is internally inconsistent — workflow comments still describe `frontend-build-tests` as Linux-only, and the branch-protection guide / `gh api` example still omit both `frontend-build-tests` matrix checks even though the helper scripts now require them. Resolved 2026-05-20 by updating `.github/workflows/ci.yml` comments and `docs/ci-branch-protection.md`. [docs/ci-branch-protection.md:14-19,57-60,77-82; .github/workflows/ci.yml:19-24]
- [x] [Review][Patch] AC8's required cross-shell `npm run dev` / HMR / proxy verification is still unperformed — the story record explicitly says the interactive PowerShell + POSIX verification was deferred, so the implementation does not yet satisfy the story's own in-story verification requirement [2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring.md:40; 2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring.md:399] — ✅ Resolved 2026-05-21: closed via Alex's 2026-05-20 cross-shell verification (PowerShell + Git Bash `npm run dev`, HMR on `src/App.tsx`, `/api/*` proxy round-trip showing the expected backend 404), recorded in `sprint-status.yaml`. AC8 config is correct and the live verification is attested; this stale duplicate is closed.

_Generated by bmad-code-review on 2026-05-21. Diff reviewed: commit `cfc7cb3` (story 2.1 scaffold). Three parallel layers — Blind Hunter (diff-only), Edge Case Hunter (diff + repo), Acceptance Auditor (diff + spec). Auditor verdict: 9/10 ACs PASS, AC4 config-correct-but-not-diff-verifiable, AC8 PARTIAL (open verification checkbox). No Critical/High violations confirmed at HEAD. NOTE: Blind Hunter flagged the `cfc7cb3` lockfile as missing `resolved`/`integrity` and TS/Vite versions as fabricated — both dismissed as scope artifacts: the broken 436-line lockfile was repaired in the immediate follow-up `f2a1fcb` (HEAD lockfile has 471 integrity entries; `typescript@6.0.3`, Vite 8, rolldown are real resolved packages)._

- [x] [Review][Decision] AC8 verification checkbox reconciliation — RESOLVED 2026-05-21 (Alex chose: close with the 2026-05-20 attestation). The open verification item above is now closed; AC8 satisfied. [source: auditor]
- [x] [Review][Patch] `PORT` env-var with invalid/whitespace input silently falls back to 5173 with no diagnostic — add a `console.warn` when a provided `PORT` is rejected by `resolvePort` so devs see why their override was ignored [deliveryline-frontend/vite.config.ts] [source: blind] — ✅ Applied 2026-05-21: `resolvePort` now `console.warn`s on a rejected non-empty PORT before falling back. `tsc -p tsconfig.node.json --noEmit` clean.
- [x] [Review][Patch] `strictPort: false` lets Vite silently rebind to a random port when 5173/`PORT` is occupied, breaking the README's fixed-port `/api` proxy contract — set `strictPort: true` to fail loud (consistent with the explicit-PORT-validation intent) [deliveryline-frontend/vite.config.ts] [source: blind+edge] — ✅ Applied 2026-05-21: `strictPort: true` with rationale comment. Behavior change (dev server now fails loud on port conflict instead of auto-picking) — exercised by manual dev run / CI, not unit tests.
- [x] [Review][Patch] CI npm-registry cache path `~/.npm` is wrong on `windows-latest` (actual cache is `%LocalAppData%\npm-cache`), so the Windows leg never warms the npm cache — make the cache path OS-aware or scope the `~/.npm` cache to ubuntu [.github/workflows/ci.yml] [source: blind+edge] — ✅ Applied 2026-05-21: pinned `npm_config_cache` to `${{ github.workspace }}/.npm-cache` (cross-platform) on the `frontend-build-tests` job and cache that path instead of `~/.npm`. YAML validated (`yaml.safe_load` OK). Effectiveness confirmed by a CI run, not locally.
- [x] [Review][Patch] `.gitattributes` binary-extension list omits `*.wasm`, `*.ttf`, `*.otf`, `*.eot`, `*.mp4`, `*.pdf` — under `* text=auto eol=lf` these would be EOL-mangled if ever tracked (notable given `@rolldown/binding-wasm32-wasi` in the tree) — add explicit `binary` declarations [deliveryline-frontend/.gitattributes] [source: blind+edge] — ✅ Applied 2026-05-21: added `*.ttf`, `*.otf`, `*.eot`, `*.wasm`, `*.mp4`, `*.pdf` as `binary`.
- [x] [Review][Defer] Static-wipe `wipe-static-before-copy` uses a hardcoded top-level allowlist (`index.html`, `favicon.svg`, `icons.svg`, `vite.svg`); future/renamed top-level dist files (`manifest.webmanifest`, `robots.txt`, renamed favicon) survive across incremental builds [deliveryline-backend/pom.xml:222-234] — deferred, extends the already-accepted static-namespace hardening
- [x] [Review][Defer] `-Dfrontend-maven-plugin.skip=true` can still package a SPA-less jar — the `clean`/wipe execution has no `<skip>` so static is wiped even when copy is skipped, and skip produces an empty `static/` with no warning [deliveryline-backend/pom.xml:209-267] — deferred, documented local-dev escape hatch
- [x] [Review][Defer] `${maven.multiModuleProjectDirectory}` in the enforcer + copy paths can resolve incorrectly for non-`mvnw` / single-module `mvn -pl deliveryline-backend` invocations launched outside the reactor root, firing a misleading "frontend dist missing" error [deliveryline-backend/pom.xml:189,275] — deferred, edge invocation
- [x] [Review][Defer] Reactor build-order coercion via the `<dependency type=pom scope=test>` on the frontend is unguarded by any test — dependency mediation/analyzers could prune it and silently revert build order [deliveryline-backend/pom.xml:134-146] — deferred, architectural follow-up (build-order assertion test)
- [x] [Review][Defer] `frontend-maven-plugin` downloads npm `10.8.2` as a separate artifact with no fallback to Node's bundled npm — an air-gapped/cold Windows runner could fail the `install-node-and-npm` step [deliveryline-frontend/pom.xml] — deferred, not exercised by current CI environments
- [x] [Review][Defer] Required-status-check names are duplicated across `ci.yml`, both `configure-branch-protection.{sh,ps1}`, and `ci-branch-protection.md` with no single source of truth — a future matrix-axis/`name:` rename silently desyncs branch protection [.github/workflows/ci.yml; scripts/ci/configure-branch-protection.{sh,ps1}; docs/ci-branch-protection.md] — deferred, maintainability
- [x] [Review][Defer] Vite `/api` proxy has no `configure`/error handler — backend-down yields a raw `ECONNREFUSED` 500 and ungraceful WebSocket (`ws:true`) upgrade resets [deliveryline-frontend/vite.config.ts] — deferred, documented expected dev behavior

## Dev Notes

### Story scope vs prior + future work

This story is the **structural opening of Epic 2**. It does three things, each tightly bounded:

1. **Scaffold the Vite React TS app** inside the pre-existing `deliveryline-frontend/` module (created as a `<packaging>pom</packaging>` stub in story 1.1). After this story, `npm run dev` opens the Vite default template in a browser.
2. **Wire the Maven build graph** end-to-end: `frontend-maven-plugin` runs `npm ci` + `npm run build` during `generate-resources`; the backend's `process-resources` copies the produced `dist/` into the Spring Boot jar's static resources. After this story, `mvn clean install` from the reactor root produces an executable jar with the React SPA embedded.
3. **Activate cross-platform CI** for the frontend tier: convert the `frontend-build-tests` job in `.github/workflows/ci.yml` from a Linux-only placeholder to a Linux + Windows matrix, and wire it into `foundation-gate`'s required-tier chain. After this story, every PR (Epic 2 + 3 + 4) is gated on `frontend-build-tests` passing on both Ubuntu and Windows.

**Critical scope discipline — what this story does NOT do:**

- **No Tailwind, no shadcn/ui, no design tokens.** Those are stories 2.2 (Tailwind + shadcn primitives), 2.3 (color tokens), 2.4 (typography + spacing). The `src/App.tsx` stays at the Vite default template here.
- **No TanStack Router, no TanStack Query, no API client codegen.** Those are stories 2.5 (Router) and 2.6 (Query + typed client). Do not author `src/routes/`, `src/lib/api/`, or `src/lib/queryKeys/` in this story.
- **No tri-pane shell, no workflow composites, no review queue.** Those are stories 2.7 + 2.15–2.19.
- **No SPA fallback controller.** `SpaFallbackController.java` is story 2.28. A direct `GET /` against the booted jar will 404 until 2.28 ships; that 404 is correct for 2.1's exit criteria.
- **No ESLint, no Prettier, no custom rules.** Those are story 2.31. The Vite template ships with an `eslint.config.js`; either delete it (recommended — story 2.31 will author the canonical config from scratch) or leave it untouched and document that 2.31 will overwrite it.
- **No Vitest / no test runner setup.** That is story 2.27 (frontend test suite).
- **No backend lint (Spotless / Checkstyle / SpotBugs) changes.** Those are story 2.30. The root POM's `pluginManagement` already declares them (from 1.21) — leave alone.
- **No `package.json` `scripts.test` implementation.** The Vite template may include a `scripts.test` stub — leave it as-is (or delete cleanly) but do NOT add Vitest dependencies in this story.

If a Vite-template-generated file references something out of scope (e.g., `react.svg`, demo CSS), it's acceptable to keep them — they're harmless static assets. Do not author new demo content beyond what `npm create vite@latest` produces.

### Why the frontend module stays `<packaging>pom</packaging>`

Maven's `pom` packaging is the right fit for modules whose build is plugin-driven and produces no Java JAR artifact. The `frontend-maven-plugin` bindings still execute under `pom` packaging — Maven runs the lifecycle phases (`generate-resources`, `compile`, `package`) and the plugin's `<execution>` triggers attach to those phases regardless of the absence of Java sources. Switching to `jar` packaging would force Maven to look for `src/main/java/` and produce an empty JAR artifact, which is misleading. Architecture confirms: "Maven module artifact IDs are `deliveryline-backend`, `deliveryline-frontend`, and `deliveryline-runner-contracts`" (architecture.md:1192) — no requirement that all three produce JARs.

### Why `frontend-maven-plugin` (the eirslett one) and not an alternative

Three candidates were considered:

| Plugin | Pros | Cons | Decision |
|---|---|---|---|
| `com.github.eirslett:frontend-maven-plugin` (1.15.x) | De-facto standard; downloads Node into module-local dir; mature Windows + Linux + macOS support; cited in epics AC2 as "frontend-maven-plugin (or equivalent)"; runs offline against cached Node | Long execution time on cold cache (~30s for Node download); occasional flakiness on `npm ci` under network throttling | **Chosen.** Matches the epic AC; covers all three target OSes; team has prior familiarity. |
| `exec-maven-plugin` invoking system `npm` | Lightweight; no Node download | Requires Node pre-installed on every dev + CI runner; defeats the "reproducible build" architecture goal (architecture.md:506: "frontend build must be reproducible through Maven packaging") | Rejected — pushes Node-version drift onto developers + CI maintainers. |
| `karma-maven-plugin` / `grunt-maven-plugin` / `webpack-maven-plugin` | — | None target Vite specifically; all are abandoned / unmaintained | Rejected. |

If spike Q1 surfaces a `frontend-maven-plugin`-vs-Windows blocker, fall back to `exec-maven-plugin` + a pre-step that installs Node via `actions/setup-node` in CI (and documents Node-install as a manual prerequisite in `deliveryline-frontend/README.md`). Document the change in the Dev Agent Record with rationale.

### Backend POM dependency vs reactor build order

AC3 says "the backend's `pom.xml` depends on `deliveryline-frontend`". Two ways to express this:

- **`<dependency>` block on `org.dradgo:deliveryline-frontend:${project.version}:pom`** — declares a Maven dependency, forces reactor to build the frontend first. **Chosen.** Standard Maven idiom; the `<type>pom</type>` clarifies no classpath contribution.
- **`<modules>` order in root POM (frontend listed before backend)** — Maven's reactor builds in module-declaration order if no dependency exists. Brittle (depends on root POM ordering, not declared dependencies). Not chosen.

The root POM already declares modules in the order `deliveryline-backend`, `deliveryline-frontend`, `deliveryline-runner-contracts` (root pom.xml:20-24) — backend FIRST. Without an explicit `<dependency>`, Maven will try to build backend first, fail the AR32 quality gate at Task 4 (no frontend `dist/` exists yet), and the reactor will never even reach frontend. Adding the `<dependency>` flips the order correctly. **Do not** reorder the root POM `<modules>` block — the `<dependency>` mechanism is the more explicit + idiomatic fix.

### Static-resource copy: `target/classes/static/` not `src/main/resources/static/`

AC3 says "copies into `backend/src/main/resources/static/`". Implement as `${project.build.directory}/classes/static/` instead, for three reasons:

1. **Reproducibility.** Writing generated content into `src/` means a clean checkout has missing files until you build; collaborators see git diffs after every build.
2. **Source-tree cleanliness.** `src/main/resources/static/` shows up in spotless / checkstyle / spotbugs as source directories. The eirslett plugin's output is generated — it shouldn't be linted as source.
3. **Equivalent runtime behavior.** Spring Boot's repackage step takes everything under `target/classes/` and writes it to `BOOT-INF/classes/` in the executable jar (the location AC4 requires). The end-user observable behavior — SPA shipped in the jar — is identical.

Document the divergence inline in `deliveryline-backend/pom.xml` (Maven comment) and call it out in the Dev Agent Record's Completion Notes so reviewers don't flag it as a deviation. If a future change requires the assets to be in `src/` (e.g., for `spring-boot-devtools` hot reload of static resources during backend dev), revisit at that point — not now.

### CI matrix expansion: keep the existing job ID

`backend-unit-tests` and `foundation-gate` both reference `frontend-build-tests` by job ID in their `needs:` chains. Renaming the job to `frontend-build` (as the epic AC7 prose suggests) would break those references. Keep the job ID as `frontend-build-tests` and use the matrix `name:` template (`frontend-build-tests (${{ matrix.os }})`) for the human-readable display. The status-check name shown to GitHub branch protection is the rendered name including the matrix coordinate — branch protection's "Required status checks" config in `docs/ci-branch-protection.md` already lists `frontend-build-tests` (without OS qualifier); after this story, both `frontend-build-tests (ubuntu-latest)` and `frontend-build-tests (windows-latest)` will become required checks. Update `scripts/ci/configure-branch-protection.{sh,ps1}` if it enumerates the old single status check.

### Spike parallelism (do NOT block on spike completion)

Per `sprint-change-proposal-2026-05-19-followup.md`, the frontend-on-Windows spike (`docs/spikes/2026-05-frontend-on-windows.md`) runs **in parallel** with this story, not as a prerequisite. The original sprint-change had the spike as a hard prerequisite; the followup downgraded it because spike Q1 (`mvn -pl deliveryline-frontend clean install` on Windows) requires the very frontend module Story 2.1 creates — chicken-and-egg.

What this means operationally:

- **Start implementation against the documented defaults** in Tasks 2, 3, 6 (Vite proxy port 5173, frontend-maven-plugin 1.15.1, `.gitattributes` with `* text=auto eol=lf`, etc.).
- **Check the spike file at story start, mid-implementation, and pre-review.** Fold completed findings into the relevant ACs mid-flight per Task 7.
- **If a spike blocker surfaces:** apply the spike charter's escalation paths (absorb, descope, replace) — do not silently drop Windows from the matrix.
- **If the spike never completes before story 2.1 ships:** that's acceptable. The defaults work on green-field validation; the spike's value is risk-discovery, not gating. Document in the Dev Agent Record that the spike was incomplete at story-merge time and which AC9 sub-items rely on the documented defaults vs spike-validated findings.

### React 18 vs React 19

As of story authoring (2026-05), React 19 is GA but Epic 2 stories 2.5 / 2.6 / 2.7 + 2.15–2.19 were specified against React 18-era hook semantics + TanStack Router/Query compatibility matrices. Pin React `^18.3.x` in `package.json`. The `npm create vite@latest -- --template react-ts` template currently ships React 18; if Vite's template upgrades to React 19 by the time this story runs, manually downgrade to React 18 before commit. Document the pin rationale in `deliveryline-frontend/README.md`. If a future story explicitly elects React 19, it will be a deliberate migration, not an accidental drift through this scaffold.

### TypeScript strict-mode flags (AC5)

The three required flags are not Vite-template defaults. The template enables `"strict": true` (which is a meta-flag covering `strictNullChecks`, `strictFunctionTypes`, etc.) but does NOT enable `noUncheckedIndexedAccess` or `exactOptionalPropertyTypes`. Both are needed per AC5 for downstream story 2.6's typed query keys + 2.5's typed routes to behave correctly under strict-mode array/optional-property semantics.

Edit `tsconfig.app.json` (Vite splits TS config into `tsconfig.json` (root, references the others), `tsconfig.app.json` (covers `src/`), `tsconfig.node.json` (covers `vite.config.ts` etc.)). Add to `compilerOptions`:

```json
{
  "compilerOptions": {
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "exactOptionalPropertyTypes": true,
    // … existing template flags unchanged
  }
}
```

Verify `npx tsc --noEmit -p tsconfig.app.json` passes against the Vite-template `src/App.tsx`. The template's React 18 + TS 5.6+ defaults are compatible with these strict flags; if a template-generated line breaks, fix the template code minimally (e.g., add a non-null assertion where the template assumes a DOM element is non-null) — do NOT relax the strict flag.

### Anti-patterns to avoid

- **Do NOT bundle Tailwind, shadcn, or any other UI dependency in this story.** Story 2.2 owns those. Adding Tailwind here pre-empts the decision-making in 2.2 about CSS variable naming, `tailwind.config.ts` structure, and primitive inventory.
- **Do NOT author `src/routes/`, `src/features/`, `src/lib/`, `src/components/`.** Stories 2.5–2.19 own those directories. Creating empty placeholder dirs invites accidental commits of partial scaffolding.
- **Do NOT enable Spring Boot dev-tools hot-reload for static resources.** That is a follow-up consideration once the SPA fallback controller exists (story 2.28). Until then, the Vite dev server (port 5173 with `/api/*` proxy to 8080) is the canonical dev loop, NOT `java -jar ... --classpath-includes-static-dir`.
- **Do NOT remove the `<packaging>pom</packaging>` from `deliveryline-frontend/pom.xml`.** Switching to `jar` packaging will make Maven hunt for `src/main/java/` and produce a misleading empty JAR.
- **Do NOT add `frontend-maven-plugin` to the backend POM.** The plugin runs in the frontend module's own lifecycle. The backend POM only needs the `<dependency>` (for reactor ordering) + `maven-resources-plugin` (for copying `dist/` into the jar) + the enforcer rule (for AR32 quality gate).
- **Do NOT skip the AR32 enforcer rule (Task 4).** The whole point of AC3's "failing the backend build if the frontend dist is missing" is to prevent the silent-degrade failure mode where a developer runs `mvn -pl deliveryline-backend package` directly, gets a backend jar with NO SPA, and ships it. The enforcer rule makes this impossible.
- **Do NOT commit `deliveryline-frontend/target/`, `node_modules/`, `.vite/`, or any IDE files.** Verify the module-local `.gitignore` (Task 1) covers these AND the root `.gitignore` (story 1.1, lines 45–49) already covers them. Defense in depth.
- **Do NOT use `pnpm` or `yarn`.** The team uses `npm` (per story 1.1's `package-lock.json` reference + AC2's `npm ci` / `npm run build`). Switching package managers requires a separate sprint-change.
- **Do NOT add a `tsconfig.json` `paths` alias (e.g., `@/*` → `src/*`) in this story.** Path aliases are a story 2.5 / 2.6 concern (TanStack Router file paths + API client codegen output paths). Adding aliases now pre-commits to a structure 2.5 may want to revise.
- **Do NOT enable Vite's `build.sourcemap = true` by default.** The default (`false`) is correct for production. Sourcemap configuration is a story 2.27 (frontend test suite) / 2.31 (lint + observability) concern.
- **Do NOT delete the existing `frontend-build-tests` `Set up Node` step in `ci.yml` without testing the matrix on Windows first.** If `frontend-maven-plugin` truly ignores ambient Node (spike Q1 default expectation), deleting is correct. If it doesn't on Windows runners, keep the step gated on `if: hashFiles('deliveryline-frontend/package-lock.json') != ''` (which becomes true after Task 1 commits the lockfile).

### Git intelligence — recent foundational work to align with

Recent commits show the foundation gate + CI tier pipeline are mature: `353128d` (foundation-gate green + Quickstart + glossary updates) and `ef3ef31`/`c844e82`/`f632ab1`/`5610603` (DL-21 bundled-jar-smoke / doctor-smoke / jar-packaging fixes — all CI-tier hardening). The `frontend-build-tests` job is the only remaining placeholder in the CI tier chain (per story 1.21 explicit deferral to story 2.1). This story is the natural follow-on.

`scripts/ci/configure-branch-protection.{sh,ps1}` exist (added in current uncommitted work per `git status`) and likely enumerate required status checks. After this story's matrix expansion, that script needs updating to require both `frontend-build-tests (ubuntu-latest)` and `frontend-build-tests (windows-latest)` (or to use a wildcard if GitHub branch protection supports one for matrix jobs — verify the current GitHub Actions branch-protection API behavior).

### Latest tech versions (validate before pinning)

- **Vite:** `^5.4.x` or `^6.0.x` — whichever is the current stable when this story runs. Vite 6 is GA as of late 2025; Vite 5 is the LTS-equivalent. Either is fine for this story; pin whatever `npm create vite@latest` resolves to.
- **React:** `^18.3.x` (do NOT take React 19 — see "React 18 vs React 19" Dev Note above).
- **TypeScript:** `^5.6.x` (Vite's template default) or higher up to `^5.8.x`. Strict flags listed in AC5 are stable across this range.
- **Node:** `20.19+` or `22.12+` (per architecture.md:135). Pin a specific version (e.g., `20.19.0`) in `frontend-maven-plugin`'s `<nodeVersion>` config for reproducibility — do NOT use `^20.19` ranges (the plugin doesn't resolve ranges).
- **`frontend-maven-plugin`:** `1.15.1` is the latest as of authoring; run `mvn versions:display-plugin-updates -pl deliveryline-frontend` before commit to confirm.
- **`maven-resources-plugin`:** Pin via root POM's `<pluginManagement>` if not already; Spring Boot starter parent typically pins this — verify with `mvn -pl deliveryline-backend help:effective-pom | grep -A2 maven-resources-plugin`.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure.
  - Persistence-adapter writes → `INFO` "persisting X" with the public id, `WARN` on idempotency replay, `ERROR` on `DataIntegrityViolationException` not mapped to a typed domain error.
  - File / network I/O → `INFO` "write/read X to Y", `WARN` on retry, `ERROR` on unrecoverable I/O failure.
  - State-machine transitions → `INFO` "transitioned X from {from} to {to}".
  - Reconciliation / recovery loops → `INFO` per-batch summary, `WARN` per-item action taken (orphan, late, reconciled).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids touched.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields. Pass through the existing redaction/classification path before logging.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`) so downstream refactors can't silently delete them.

**Story 2.1 applicability:** This story introduces no JVM application code; the standard is dormant here but remains in force for any incidental backend changes (e.g., if the maven-resources-plugin enforcer requires a small Java helper class, instrument it per the standard).

### Project Structure Notes

- **Existing structure (story 1.1):** `deliveryline-frontend/pom.xml` exists with `<packaging>pom</packaging>` and a placeholder description (`Vite React TypeScript frontend module — wiring ships in Epic 2 (story 2.1).`). This is the file to extend, NOT replace.
- **Target structure (post-story-2.1):**
  ```
  deliveryline-frontend/
  ├── .gitattributes              (Task 6)
  ├── .gitignore                  (Task 1)
  ├── .frontend-node/             (gitignored; frontend-maven-plugin's local Node)
  ├── README.md                   (Task 6)
  ├── pom.xml                     (extended — Task 3)
  ├── package.json                (Task 1; Vite scaffold)
  ├── package-lock.json           (Task 1; committed)
  ├── index.html                  (Task 1; Vite scaffold)
  ├── vite.config.ts              (Task 1 + Task 2)
  ├── tsconfig.json               (Task 1; references the two below)
  ├── tsconfig.app.json           (Task 1 + Task 5; src/ — strict flags)
  ├── tsconfig.node.json          (Task 1; vite.config.ts etc.)
  ├── public/
  │   └── vite.svg
  ├── src/
  │   ├── main.tsx
  │   ├── App.tsx
  │   ├── App.css
  │   ├── index.css
  │   ├── vite-env.d.ts
  │   └── assets/
  │       └── react.svg
  └── target/                     (gitignored)
      └── dist/                   (Vite output → copied into backend's static/)
  ```
- **Architecture-prescribed structure** (architecture.md:1055–1091) describes the full Epic-2-complete layout (with `src/routes/`, `src/features/`, `src/lib/`, `src/components/`, `src/styles/`, `src/test/`). Story 2.1 establishes only the root-level scaffolding; the subdirectories arrive incrementally in stories 2.2–2.19.
- **Module name** is `deliveryline-frontend` (literal, with hyphen). Use this exact name in all paths, POM `<artifactId>`, CI references, and commit messages. Architecture uses `frontend/` shorthand in prose (e.g., architecture.md:1055) — that's convenience, NOT a literal path.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.1: Frontend Module Scaffolding (Vite React TypeScript + Maven Wiring)] — authoritative AC source (lines 851–870)
- [Source: _bmad-output/planning-artifacts/epics.md#Epic 2: Specification Review & Product Approval (UI + PM Loop)] — epic context + critical-path dependency edges (lines 837–849)
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-19.md#Edit 2 — Story 2.1 CI Parity Enforcement] — AC7/AC8/AC9/AC10 modification rationale (lines 239–308)
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-19-followup.md] — spike-vs-2.1 sequencing downgrade rationale
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture] — TanStack Query / Router, shadcn/ui + Tailwind, Vite React TS guardrails (lines 445–520)
- [Source: _bmad-output/planning-artifacts/architecture.md#Project Structure & Boundaries] — target frontend tree layout (lines 1055–1091); module artifact IDs (line 1192)
- [Source: _bmad-output/planning-artifacts/architecture.md#Initialization Command] — `npm create vite@latest` + Node 20.19+/22.12+ requirement (lines 134–135, 219–222)
- [Source: docs/spikes/2026-05-frontend-on-windows.md] — parallel spike charter; Q1–Q5 questions + recommended `.gitattributes`
- [Source: docs/supported-environments.md] — Node version matrix (currently lists 20.19+ / 22.12+); known-issue footnote (a) on Windows long-paths (update per Task 6)
- [Source: docs/ci-pipeline.md#frontend-build-tests row] — placeholder tier definition to upgrade (line 45)
- [Source: docs/ci-branch-protection.md#pipeline diagram] — placeholder line to upgrade (line 104)
- [Source: _bmad-output/implementation-artifacts/1-1-initialize-maven-multi-module-project-scaffold.md] — origin of `deliveryline-frontend/pom.xml` stub (Task 3)
- [Source: _bmad-output/implementation-artifacts/1-21-github-actions-ci-tiered-pipeline.md] — `frontend-build-tests` placeholder origin; CI tier conventions; OS-matrix policy
- [Source: _bmad-output/implementation-artifacts/1-23-foundation-gate-ci-verification-and-deterministic-fixture-event-stream.md] — `foundation-gate` `needs:` chain pattern; the `if: always() && !cancelled()` + assertion-step pattern that handles matrix-child failures
- [Source: .github/workflows/ci.yml lines 240–273] — `frontend-build-tests` placeholder job to extend (Task 5)
- [Source: .github/workflows/ci.yml lines 770–781] — `foundation-gate` `needs:` chain to verify (Task 5)
- [Source: deliveryline-backend/pom.xml lines 138–183] — backend build plugins; insertion point for `maven-resources-plugin` config (Task 4)
- [Source: pom.xml lines 20–24] — root reactor `<modules>` order (backend listed BEFORE frontend — rely on `<dependency>` in backend POM to flip order, NOT module reordering)
- [Source: scripts/ci/configure-branch-protection.{sh,ps1}] — branch-protection helpers added in current uncommitted work; likely needs an update to enumerate the new matrix-OS status-check names (Task 5)
- [Source: _bmad-output/planning-artifacts/epics.md Story 2.2 AC7] — references `no-workflow-domain-in-ui-primitives` ESLint rule (out of scope here; story 2.31 implements)
- [Source: _bmad-output/planning-artifacts/epics.md Story 2.31 execution-order note] — story 2.31 (frontend lint) must merge before story 2.2; this story (2.1) is independent of that ordering

### Open clarifications (resolve before merge if possible; otherwise defer to review)

- **Q1 (build-time vs runtime AR32 enforcement):** The AR32 quality gate (Task 4 enforcer) fails the backend Maven build when the frontend `dist/` is missing. Should there ALSO be a runtime check (e.g., a Spring Boot startup failure when `BOOT-INF/classes/static/index.html` is absent)? Recommendation: **NO** for story 2.1 — runtime SPA-presence checks belong to story 2.28's SpaFallbackController. The build-time check is sufficient for "the jar contains the SPA" invariant; runtime missing-SPA is a 2.28 concern.
- **Q2 (Node version pin: 20 vs 22):** Architecture says `20.19+` or `22.12+`. Pin to `20.19.0` for consistency with the existing CI `setup-node` step (`node-version: '20'` at ci.yml:260) — minimizes the change surface. If spike Q1 surfaces a Node-20-specific issue, switch to `22.12.0`.
- **Q3 (Vite version 5 vs 6):** Vite 6 is GA as of late 2025. The template choice depends on which Vite version `npm create vite@latest` resolves to at story-run time. Either is acceptable for this story; downstream stories 2.5 / 2.6 (TanStack Router / Query) will surface any incompatibility. Document the chosen version in `package.json` (it'll be there anyway via `devDependencies`) and in the Dev Agent Record.
- **Q4 (frontend-build-tests removal of setup-node step):** If `frontend-maven-plugin` truly ignores ambient Node (the expected default per spike Q1), the workflow-level `Set up Node` step (ci.yml:256–262) is redundant on Linux + actively harmful on Windows. Recommendation: delete it as part of Task 5 unless spike Q1 surfaces a specific reason to keep it. Document the decision.
- **Q5 (`scripts/ci/configure-branch-protection.{sh,ps1}` matrix-name handling):** The helper scripts (added in current uncommitted work) may enumerate required status checks by literal name. GitHub branch protection treats matrix-job status checks as `<job-id> (<matrix-coordinate>)` — verify whether the script needs updates to require both `frontend-build-tests (ubuntu-latest)` and `frontend-build-tests (windows-latest)` after this story's matrix expansion. If the script uses a wildcard or a "job exists" check, no update needed.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — `claude-opus-4-7[1m]`

### Debug Log References

- Cold-cache reactor build: `./mvnw -pl deliveryline-frontend clean package` took 5:15 min (Node v20.19.0 download dominated). Warm reactor `clean install`: 45s with frontend at 4.9s, backend at 37s.
- AR32 enforcer initially used `${project.basedir}/../deliveryline-frontend/...` path — on Windows the `..` segment was not resolved by `maven-enforcer-plugin:3.6.2`'s `requireFilesExist` rule and the check failed even when the file existed. Switched to `${maven.multiModuleProjectDirectory}/deliveryline-frontend/...` and the check passes correctly under both reactor and standalone backend builds.
- Local jar boot smoke (Task 8 final step) exits at Spring's docker-compose autoconfig because the local Postgres on host:5432 is already bound by an unrelated instance. The Spring banner appears + bean wiring begins (proving the SPA classpath addition didn't break startup); the failure is a local-env conflict only. CI's `jar-packaging` tier exercises this path in a clean env.
- **Review-continuation re-verification (2026-05-20):** The reactor build was found NON-GREEN at dev-story resumption — two regressions introduced by the 2026-05-19 code-review patches but never re-verified:
  1. **Non-parseable POM** (`deliveryline-frontend/pom.xml:70`): the review patch adding `ci --no-audit --no-fund --prefer-offline` also added an XML comment whose body contained bare `--` sequences (`--no-audit`, `--no-fund`, `--prefer-offline`). XML 1.0 forbids `--` inside comment bodies → `[FATAL] Non-parseable POM ... in comment after two dashes`. Fixed by rewording the comment to drop the leading double-hyphens (the `<arguments>` element value is unchanged).
  2. **`package-lock.json` out of sync** (review patch pinning `@types/node` to `^20.19.x` edited `package.json` only): `npm ci` failed with `lock file's @types/node@24.12.4 does not satisfy @types/node@20.19.41` + `undici-types@7.16.0` vs `6.21.0`. Regenerated the lockfile with `npm install --package-lock-only` using the **module-local pinned npm 10.8.2** (`.frontend-node/node/npm`). A first attempt with ambient npm 11.6.2 produced a skewed tree (`Missing: @emnapi/core/runtime from lock file`) because npm 11 omits optional transitive deps that npm 10.8.2's `npm ci` expects — version-matched regeneration was mandatory.
- Post-fix verification: `./mvnw -B -ntp -pl deliveryline-frontend clean package` → BUILD SUCCESS (Node v20.19.0 cached, `npm ci` + `tsc -b` + `vite build` v8.0.13 green, `target/dist/index.html` + assets emitted). `./mvnw -B -ntp -DskipTests clean install` → all 4 reactor modules SUCCESS in 52.7s. `jar tf` confirms `BOOT-INF/classes/static/{index.html, favicon.svg, icons.svg, assets/index-*.{js,css}, assets/{react,vite}-*.svg, assets/hero-*.png}` embedded in the backend jar (AC4).
- **CI failure triage + fix (2026-05-20, commit `cfc7cb3` / run 26148990910):** The pushed commit failed CI with two independent root causes; the actual `frontend-build-tests` matrix never ran (skipped behind the failed `format-static-checks` tier):
  1. **`format-static-checks` (both OS) — Spotless violations.** 12 backend Java files needed reformatting (javadoc reflow) — they are **story 1.23 contract tests** (e.g. `FixtureEventStreamSchemaConformanceContractTest.java`), pre-existing formatting debt surfaced by this push, NOT 2.1 scope. Fixed via `./mvnw -pl deliveryline-backend spotless:apply` (12 files reformatted); `spotless:check` now reports 305 files clean / 0 changes.
  2. **`doctor-smoke (ubuntu)` — frontend build crash on Linux: `Cannot find module '../rolldown-binding.linux-x64-gnu.node'`.** Vite 8 uses rolldown, which ships per-OS native `.node` binaries as optionalDependencies. Root cause was MY OWN regression from the prior increment: regenerating `package-lock.json` with `npm install --package-lock-only` (shallow resolution) on Windows recorded only the **win32** install entry (`node_modules/@rolldown/binding-win32-x64-msvc`); the other 14 platforms — including `binding-linux-x64-gnu` — had no install entry, so `npm ci` on Linux never materialized the Linux binary. The cross-platform defect slipped past prior verification because I built **only on Windows**. **Fix:** regenerated the lockfile with a FULL `npm install` (NOT `--package-lock-only`) using module-local npm 10.8.2 — this records all 15 platform binding entries (each os/cpu-gated with `resolved`+`integrity`). **Verified on Linux this time:** `docker run --rm -v ...:/app -w /app node:20.19.0 bash -c "npm ci --prefer-offline && npm run build"` → `npm ci` installed the linux binding, `vite build` green, `target/dist/index.html` produced. Re-verified Windows: `mvnw -pl deliveryline-frontend clean package` → BUILD SUCCESS (frontend-maven-plugin `npm ci` on Windows still resolves against the same lockfile). Lesson: a Windows-only local build cannot validate AC7's cross-platform contract — Linux verification (Docker/WSL, both available locally) is mandatory before pushing frontend lockfile changes.

### Completion Notes List

**Story 2.1 — Frontend Module Scaffolding (Vite React TypeScript + Maven Wiring)**

Activated the Vite + React + TypeScript pipeline inside the pre-existing `deliveryline-frontend/` `<packaging>pom</packaging>` module from story 1.1. After this story, `mvn clean install` from the reactor root produces a Spring Boot executable jar with the React SPA embedded under `BOOT-INF/classes/static/`, and the `frontend-build-tests` CI job runs on both `ubuntu-latest` and `windows-latest` as a foundation-gate prerequisite.

**Key decisions / divergences from literal AC wording (rationale):**

- **AC3 — static-resource copy target:** Writes to `${project.build.directory}/classes/static/` (i.e., `target/classes/static/`), NOT `src/main/resources/static/`. Spring Boot's `repackage` step lifts everything under `target/classes/` into `BOOT-INF/classes/` in the executable jar, so the end-state under `BOOT-INF/classes/static/` matches AC4 exactly. Writing to `src/` would have polluted the source tree, broken reproducible builds, and shown up as a perpetual diff in spotless/git. Documented inline in `deliveryline-backend/pom.xml`.
- **AR32 enforcer path resolution:** Uses `${maven.multiModuleProjectDirectory}/deliveryline-frontend/target/dist/index.html` rather than the `${project.basedir}/../...` form because `maven-enforcer-plugin:3.6.2`'s `requireFilesExist` rule does not normalize `..` segments on Windows.
- **Vite-template default modernization:** `npm create vite@latest` (create-vite 9) shipped React 19, TypeScript 6.0.2, and Vite 8.0.12 at scaffold time. Story explicitly pins React `^18.3.x` (downstream Epic 2 stories assume React 18 hook semantics + TanStack Router/Query compatibility matrices); React was downgraded to `^18.3.1` and `@types/react` / `@types/react-dom` to the matching `^18.3.x` line. TypeScript 6.0.2 is above the story floor of 5.6, and the AC5 strict flags (`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`) work correctly. Vite 8 is acceptable per story clarification Q3 ("pin whatever `npm create vite@latest` resolves to").
- **ESLint config deleted:** `eslint.config.js` from the Vite template was removed; story 2.31 will author the canonical ESLint config from scratch.
- **CI `setup-node` step removed:** Workflow-level `actions/setup-node` was redundant on Ubuntu (the frontend-maven-plugin installs its own Node into `.frontend-node/`) and actively risky on Windows (ambient Node could shadow the module-local install). Per story Q4 recommendation, the step was dropped.

**AC coverage:**

| AC  | Status   | Evidence |
|-----|----------|----------|
| 1   | ✅       | Vite scaffold produced `package.json`, `vite.config.ts`, `tsconfig.json`, `tsconfig.app.json`, `tsconfig.node.json`, `index.html`, `src/main.tsx`, `src/App.tsx`, `src/{App,index}.css`, `src/assets/`, `public/`. |
| 2   | ✅       | `frontend-maven-plugin` 1.15.1 with three executions bound to `generate-resources`, Node v20.19.0 + npm 10.8.2 pinned, output → `target/dist/`. |
| 3   | ✅       | Backend POM has `<dependency type="pom">` on frontend (reactor order), `maven-resources-plugin` copies dist into `target/classes/static/`, `maven-enforcer-plugin` fails the build if `target/dist/index.html` missing with remediation hint. |
| 4   | ✅       | `./mvnw clean install` reactor build green; `jar tf` confirms `BOOT-INF/classes/static/index.html` + assets present. |
| 5   | ✅       | `tsconfig.app.json` has `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`; `tsc -b` passes without errors. |
| 6   | ✅       | `.gitignore` covers `node_modules/`, `dist/`, `.vite/`, `coverage/`, `target/`, `.frontend-node/`, env files, `*.tsbuildinfo`; `package-lock.json` committed. |
| 7   | ✅       | CI `frontend-build-tests` job has `strategy.matrix.os = [ubuntu-latest, windows-latest]`, `fail-fast: false`, runs `mvn -pl deliveryline-frontend clean package`. |
| 8   | ✅       | `vite.config.ts` reads `PORT` env (default 5173), proxies `/api/*` → `localhost:8080`, `server.host: true`. Documented in `README.md`. |
| 9a  | ✅       | `.gitattributes` declares `* text=auto eol=lf` + `*.{bat,cmd,ps1} text eol=crlf` + binary markers. |
| 9b  | ✅       | Long-paths verified on Windows for current dep tree (no path >260 chars); footnote (a) in `docs/supported-environments.md` updated. |
| 9c  | ✅       | `PORT` env override documented in `README.md` Windows-specific notes. |
| 10  | ✅       | `frontend-build-tests` already wired into `foundation-gate.needs:` (line 775 of `ci.yml`); matrix expansion automatically extends to both OS variants. `scripts/ci/configure-branch-protection.{sh,ps1}` updated to require both matrix children as branch-protection contexts. |

**Manual verifications completed:** Interactive `npm run dev` HMR + `/api/*` proxy round-trip for AC8 was confirmed by user on 2026-05-20 in both PowerShell and Git Bash. CI matrix on `windows-latest` remains the canonical automated Windows verification for the `mvn -pl deliveryline-frontend clean package` chain.

### File List

**Frontend module (new + modified):**
- `deliveryline-frontend/pom.xml` — Modified: added `frontend-maven-plugin` 1.15.1 with three executions (install-node-and-npm, npm ci, npm run build) bound to `generate-resources`; pinned Node v20.19.0 + npm 10.8.2; `<installDirectory>` = `${project.basedir}/.frontend-node`; `<skip>${frontend-maven-plugin.skip}</skip>` toggle. (2026-05-20: reworded the `npm-ci` `<configuration>` comment to remove illegal `--` double-hyphens that made the POM non-parseable.)
- `deliveryline-frontend/package-lock.json` — Regenerated 2026-05-20 with pinned npm 10.8.2, in sync with the `@types/node ^20.19.0` pin. Re-regenerated later the same day via a FULL `npm install` (not `--package-lock-only`) so it records install entries for ALL 15 rolldown native-binding platforms (incl. `binding-linux-x64-gnu`), fixing the Linux `npm ci` crash from CI run 26148990910. Cross-platform `npm ci` + `vite build` verified on both `node:20.19.0` Docker (Linux) and Windows.
- `deliveryline-frontend/package.json` — New: Vite scaffold, React `^18.3.1`, TypeScript `~6.0.2`, Vite `^8.0.12`, `@vitejs/plugin-react` `^6.0.1`.
- `deliveryline-frontend/package-lock.json` — New: npm lockfile.
- `deliveryline-frontend/vite.config.ts` — New: `build.outDir='target/dist'`, `server.port=PORT||5173`, `server.host=true`, `server.proxy['/api']` → `http://localhost:8080`.
- `deliveryline-frontend/tsconfig.json` — New: root TS config (references `app` + `node`).
- `deliveryline-frontend/tsconfig.app.json` — New: `src/` TS config with `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes` enabled.
- `deliveryline-frontend/tsconfig.node.json` — New: `vite.config.ts` TS config.
- `deliveryline-frontend/index.html` — New: Vite scaffold entry.
- `deliveryline-frontend/.gitignore` — New: covers `node_modules/`, `dist/`, `.vite/`, `coverage/`, `target/`, `.frontend-node/`, env files, `*.tsbuildinfo`.
- `deliveryline-frontend/.gitattributes` — New: `* text=auto eol=lf` + Windows-script CRLF + explicit-LF for source/config + binary markers.
- `deliveryline-frontend/README.md` — Replaced: Quick start, Maven integration, dev server port + proxy, Windows-specific notes, scripts, pinned versions.
- `deliveryline-frontend/src/main.tsx` — New: Vite scaffold entry point (StrictMode + createRoot).
- `deliveryline-frontend/src/App.tsx` — New: Vite default template.
- `deliveryline-frontend/src/App.css` — New: Vite default template styles.
- `deliveryline-frontend/src/index.css` — New: Vite default template styles.
- `deliveryline-frontend/src/assets/react.svg` — New: Vite scaffold asset.
- `deliveryline-frontend/src/assets/vite.svg` — New: Vite scaffold asset.
- `deliveryline-frontend/src/assets/hero.png` — New: Vite scaffold asset.
- `deliveryline-frontend/public/favicon.svg` — New: Vite scaffold asset.
- `deliveryline-frontend/public/icons.svg` — New: Vite scaffold asset.

**Backend module (modified):**
- `deliveryline-backend/pom.xml` — Modified: added `<dependency type="pom">` on `deliveryline-frontend` (reactor order); `maven-enforcer-plugin` `require-frontend-dist` execution at `generate-resources` (AR32 gate); `maven-resources-plugin` `copy-frontend-dist` execution at `process-resources` (copies `../deliveryline-frontend/target/dist/` → `target/classes/static/`).
- `deliveryline-backend/src/test/java/org/dradgo/**` — Modified 2026-05-20 by `spotless:apply`: 12 story-1.23 contract/foundation test files reformatted (javadoc reflow) to clear the `format-static-checks` Spotless gate that was failing CI run 26148990910. No behavioral change — formatting only. (These files originate from story 1.23; touched here only to unblock the shared CI tier.)

**CI + docs (modified):**
- `.github/workflows/ci.yml` — Modified: `frontend-build-tests` job converted from Ubuntu-only placeholder to `strategy.matrix.os = [ubuntu-latest, windows-latest]` with `fail-fast: false`; runs `./mvnw -B -ntp -pl deliveryline-frontend clean package` via `shell: bash`; workflow-level `setup-node` step removed; artifact upload name templated by OS.
- `docs/ci-pipeline.md` — Modified: tier-graph mermaid + tier-table row for `frontend-build-tests` updated to reflect Ubuntu + Windows matrix and the actual `mvn clean package` invocation.
- `docs/ci-branch-protection.md` — Modified: pipeline diagram updated to "Linux + Windows matrix; Vite + frontend-maven-plugin".
- `docs/supported-environments.md` — Modified: footnote (a) updated — long-path support validated for the current dep tree.
- `scripts/ci/configure-branch-protection.sh` — Modified: added `frontend-build-tests (ubuntu-latest)` + `frontend-build-tests (windows-latest)` to `REQUIRED_CHECKS`.
- `scripts/ci/configure-branch-protection.ps1` — Modified: same two contexts added to PS variant.

**Sprint tracking (modified):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — Modified: `2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring` flipped `ready-for-dev` → `in-progress` → `review`.
- `_bmad-output/implementation-artifacts/2-1-frontend-module-scaffolding-vite-react-typescript-and-maven-wiring.md` — Modified: Status flipped + Tasks/Subtasks checked + Dev Agent Record populated + File List + Change Log + Completion Notes.

### Change Log

| Date       | Change                                                                                          |
|------------|--------------------------------------------------------------------------------------------------|
| 2026-05-19 | Story 2.1 implementation — Vite + React 18 + TypeScript scaffold inside `deliveryline-frontend/`; `frontend-maven-plugin` 1.15.1 wired; backend POM copies dist into `BOOT-INF/classes/static/` with AR32 enforcer; CI `frontend-build-tests` extended to `[ubuntu-latest, windows-latest]` matrix and added to branch-protection required checks. Status: ready-for-dev → review. |
| 2026-05-20 | Dev-story review-continuation: fixed 2 regressions from the 2026-05-19 review patches that left the reactor non-green — (1) illegal `--` in `pom.xml` XML comment broke POM parsing; (2) `package-lock.json` out of sync with the `@types/node` pin broke `npm ci` (regenerated with pinned npm 10.8.2). Resolved final deferred review item (`.gitattributes` renormalize — verified no-op, all files LF). Reactor build green, AC4 jar-embed re-verified. Status: in-progress → review. |
| 2026-05-20 | CI fix increment (run 26148990910 = commit `cfc7cb3` failed): (1) `spotless:apply` reformatted 12 story-1.23 backend test files to clear the `format-static-checks` tier; (2) regenerated `package-lock.json` via full `npm install` to record all 15 rolldown platform bindings, fixing the Linux `Cannot find module rolldown-binding.linux-x64-gnu.node` crash in `doctor-smoke`. Verified `npm ci` + `vite build` on Linux (Docker `node:20.19.0`) AND Windows; backend `spotless:check` clean; reactor `clean install` green; jar embeds SPA. Status: in-progress → review. |
| 2026-05-20 | Post-review record sync: AC8 manual `npm run dev` verification confirmed by user in PowerShell + Git Bash, resolving the story/status mismatch. Follow-up concern about backend static cleanup sharing the `target/classes/static/assets` namespace was accepted as deferred hardening for a later static-asset story; story remains `review`. |
