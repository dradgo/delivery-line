# Story 2.32: Backend Coverage Reporting + Maven JaCoCo Threshold Gate

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **backend developer**,
I want **Maven-wired JaCoCo coverage reporting plus enforceable backend coverage thresholds in `verify`**,
so that **backend test coverage is measured mechanically, published in a machine-readable form for CI, and able to fail the build when regression-prone code lands without sufficient automated coverage**.

## ⚠️ Execution order & sibling-story coordination (read first)

- Per `epics.md:1031`, this story should **merge alongside or immediately after story 2.30** and **before the backend-heavy Epic 2 slices (2.8–2.14)** — so coverage reporting exists before the specification/approval backend code expands. It is numbered 2.32 only to preserve story-numbering stability.
- **Story 2.30 (`ready-for-dev`, not yet done) and 2.32 touch overlapping surfaces:** `deliveryline-backend/pom.xml`, `deliveryline-backend/README.md`, `docs/` and the foundation-gate widening. 2.30 explicitly scopes JaCoCo **out** ("2.30 must NOT add a JaCoCo `check`/threshold execution" — `2-30-...md:110`) and hands the gate to 2.32. Coordinate: whichever story lands first **creates** `deliveryline-backend/README.md`; the second **appends a section** — do not overwrite.
- The working tree has **uncommitted in-flight work** (story 6.9 `review`: modified backend `.java`, `pom.xml` springdoc dep, `ci.yml` OpenAPI step, `application.yml`; possibly 2.30). Rebase/sync onto the latest committed `main` before starting, and scope your commit narrowly (see Task 7).

## 🔑 What is ALREADY done (story 1.21) — do NOT re-invent

Story 1.21 (CI tiered pipeline, **done**) already laid the JaCoCo plumbing as a **report-only artifact**. **Read this before touching anything** — your job is to add the *gate*, not to start fresh:

- **Root `pom.xml` `<pluginManagement>` (`pom.xml:125-150`)** already pins `jacoco-maven-plugin 0.8.13` and declares three executions: `jacoco-prepare-agent` (goal `prepare-agent`, default phase `initialize`), `jacoco-prepare-agent-integration` (goal `prepare-agent-integration`, default phase `pre-integration-test`), and `jacoco-report` (goal `report`, bound to `verify`). It carries an explicit handoff comment: *"No threshold gate here; story 2.32 wires the gate per epics.md:1028."*
- **`deliveryline-backend/pom.xml:346-349`** opts in with a versionless `<plugin>org.jacoco:jacoco-maven-plugin</plugin>` (no `<executions>` — all three executions are inherited from pluginManagement).
- **`prepare-agent` instruments Surefire** (writes `target/jacoco.exec`); **`prepare-agent-integration` instruments Failsafe** (writes `target/jacoco-it.exec`). Both work today — `argLine` wiring is live (the foundation-gate CI job comment at `ci.yml:891` confirms `jacoco:prepare-agent` must run or Failsafe's `@{argLine}` crashes the forked JVM). **Do NOT touch the two `prepare-agent*` executions.**
- **`.github/workflows/ci.yml`** already has the `backend-coverage-jacoco` artifact upload (`ci.yml:411-418`) in the `backend-contract-tests` tier, and that tier already runs the full `verify` lifecycle (`ci.yml:383`). AC8 is **almost entirely pre-wired**.
- **`deliveryline-runner-contracts`** does **not** opt into JaCoCo (`deliveryline-runner-contracts/pom.xml` declares Spotless only). This story is **backend-only**; leave runner-contracts alone.
- `docs/ci-pipeline.md:107-110` documents the current state: *"There is **no coverage threshold gate** in story 1.21 — that ships in story 2.32 … The artifact exists today so the future gate has data to consume from day one."*

So the **net-new work for 2.32** is: (1) make `report` + a new `check` evaluate **unit + integration coverage combined** (the central technical problem — see DISASTER #1); (2) add the `check` execution with **empirically-measured** thresholds; (3) define minimal, documented exclusions (AC5); (4) make the `foundation-gate` Maven profile skip `check` (DISASTER #2); (5) author `deliveryline-backend/README.md` coverage section; (6) update `docs/ci-pipeline.md`. CI YAML changes are minimal-to-none.

## Acceptance Criteria

1. **Given** `deliveryline-backend/pom.xml`, **Then** `org.jacoco:jacoco-maven-plugin` is configured with `prepare-agent`, `report`, and `check` executions so a standard Maven run of `mvn -pl deliveryline-backend verify` both generates a coverage report and enforces thresholds.
2. **Given** the Maven lifecycle, **Then** coverage verification is attached to the `verify` phase (not a custom ad-hoc script only), and the documented backend coverage command is `mvn -pl deliveryline-backend verify`.
3. **Given** the generated reports, **Then** JaCoCo writes HTML, XML, and CSV outputs under `deliveryline-backend/target/site/jacoco/` (or the documented default output path), so local developers can inspect the HTML report and CI can ingest XML without custom path guessing.
4. **Given** backend quality gates, **Then** JaCoCo `check` enforces documented minimum thresholds at the backend module level — at minimum line and branch coverage — with the threshold values committed in the POM and justified in `deliveryline-backend/README.md` (or equivalent module documentation).
5. **Given** threshold scope, **Then** the story documents exactly what is and is not counted toward the gate: generated sources, configuration-only bootstrapping classes, and clearly documented framework glue may be excluded only through committed plugin configuration with rationale; business/application code may not be blanket-excluded.
6. **Given** the existing test stack (`surefire`, ArchUnit, Spring MVC tests, Testcontainers-backed contract tests), **Then** JaCoCo coverage instrumentation works with the current backend test suite and does not require developers to run a separate test command from `verify`.
7. **Given** a threshold breach, **Then** Maven fails with a clear JaCoCo `check` error during `verify`; this failure is deterministic and does not require manual inspection of the HTML report to detect.
8. **Given** CI integration through story 1.21, **Then** the backend quality job includes a named step that runs `mvn -pl deliveryline-backend verify` (or the final agreed module-scoped equivalent) and archives/publishes the JaCoCo XML/HTML artifacts for inspection when the job fails.
9. **Given** the relationship to story 2.30, **Then** documentation makes the tool split explicit: Spotless/Checkstyle/SpotBugs enforce formatting, style, and bug patterns; JaCoCo measures test coverage; none of them is treated as a substitute for the others.
10. **Given** future backend growth in stories 2.8-2.14 and Epic 3, **Then** the coverage gate is scoped so new backend packages automatically participate unless explicitly excluded by committed configuration; developers do not need to touch the plugin just because a new application package was added.
11. **Given** developer ergonomics, **Then** `deliveryline-backend/README.md` (or equivalent) documents: the standard coverage command; where the HTML report appears locally; what to do when the JaCoCo threshold fails; the current threshold values and why they were chosen for this phase.
12. **Given** the foundation-gate verification story (1.23), **Then** its scope is widened to include "backend JaCoCo coverage report generated and threshold gate green on the branch" so backend coverage regressions are caught at the same epic-close gate as backend tests and lint.

## 🚨 The two disasters this story must prevent

### DISASTER #1 — Gating on unit coverage alone makes the gate meaningless (or red on day one)

The backend's tests are **overwhelmingly Failsafe-based** (Testcontainers contract tests + ArchUnit + Spring MVC slice tests). Surefire pure-unit tests are a small minority — the backend POM routes every `*ContractTest`, `*IT`, `architecture/**`, `*ApplicationTests` to Failsafe (`deliveryline-backend/pom.xml:363-396`).

- `prepare-agent` → Surefire coverage → `target/jacoco.exec`
- `prepare-agent-integration` → Failsafe coverage → `target/jacoco-it.exec`
- The inherited `jacoco:report` reads **only `target/jacoco.exec`** by default.

So today the report (and a naive `check`) would see **only unit coverage** — a small slice — making any honest threshold near-zero and the report artifact in CI effectively empty. **`report` and `check` MUST evaluate the union of `jacoco.exec` + `jacoco-it.exec`.** This is the core engineering task — see Task 1.

It compounds with how CI runs the gate: the `backend-contract-tests` tier runs `verify -Dsurefire.skip=true` (`ci.yml:383`) — **Surefire is skipped there**, so in CI the gate sees `jacoco-it.exec` *only*, while a local `mvn verify` produces *both* files. Calibrate the committed thresholds against the **CI-shaped measurement** (see Task 2) so the binding gate and a local run both pass.

### DISASTER #2 — The `foundation-gate` CI job would red-build on subset coverage

The `foundation-gate` job runs `./mvnw -pl deliveryline-backend -am -Pfoundation-gate verify -Dit.test='*FoundationGateVerificationTest*'` (`ci.yml:901-903`) — the full `verify` lifecycle, but Failsafe runs **only the aggregator**. Once `check` binds to `verify`, that job would evaluate coverage against a non-representative subset and fail unpredictably.

**Do NOT fix this with `-Djacoco.skip=true`** — that also skips `prepare-agent`, and Failsafe's `<argLine>@{argLine} …</argLine>` then resolves to a literal, crashing the forked JVM ("forked VM terminated without properly saying goodbye" — exact failure mode documented at `ci.yml:891-893`). The `check` execution must be skippable **independently** of `prepare-agent` (see Task 4). AC12 is satisfied **transitively** — the real gate runs in `backend-contract-tests`, which is a `needs:` dependency of `foundation-gate` — not by running `check` inside the gate job itself.

## Tasks / Subtasks

- [x] **Task 1: Wire `report` + `check` to evaluate unit + integration coverage combined (DISASTER #1 — core task)** (AC: 1, 3, 6, 10)
  - [x] **Recommended approach — `merge` goal:** add a `jacoco-merge` execution to the backend `<plugin>` block, goal `merge`, **phase `post-integration-test`** (this runs strictly after `integration-test` writes `jacoco-it.exec` and after the `test` phase writes `jacoco.exec`, and strictly before every `verify`-phase execution — sidestepping all execution-ordering ambiguity). Merge `target/jacoco.exec` + `target/jacoco-it.exec` (a `<fileSet>` with both filename includes — `merge` silently skips any include that does not exist, so the CI `-Dsurefire.skip=true` case where only `jacoco-it.exec` exists works fine) into `target/jacoco-merged.exec`.
  - [x] Override the inherited `jacoco-report` execution: re-declare an `<execution>` with the **same id `jacoco-report`** (Maven merges config by id; phase `verify` stays inherited) and add `<dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>` so the HTML/XML/CSV report reflects combined coverage. This also fixes a latent 1.21 bug — today the `backend-coverage-jacoco` CI artifact is effectively empty because `report` reads the never-written `jacoco.exec` in the surefire-skipped tier.
  - [x] Do **NOT** modify `jacoco-prepare-agent` or `jacoco-prepare-agent-integration` — they are correct and load-bearing.
  - [x] Do **NOT** add a per-package `<includes>` allowlist. A `BUNDLE`-scoped rule (Task 2) plus an `<excludes>`-only policy means **new backend packages auto-participate** in coverage with zero plugin edits — this is exactly AC10.
  - [x] AC3: confirm HTML + XML + CSV all land under `deliveryline-backend/target/site/jacoco/` (JaCoCo `report` emits all three by default — just verify the path).
  - [x] *Alternative (acceptable, simpler ordering but intermingles data):* override `jacoco-prepare-agent-integration`'s `<destFile>` to `target/jacoco.exec` so the Failsafe agent appends to the same file as the Surefire agent (JaCoCo agent `append=true` by default); then `report`/`check` read the single `jacoco.exec` with no merge goal. If you choose this, document why in the POM. **The `merge` approach is recommended** — it keeps unit/IT exec files separate (better hygiene) and matches the existing two-file pluginManagement shape.

- [x] **Task 2: Add the `jacoco-check` execution with EMPIRICALLY-MEASURED thresholds** (AC: 1, 2, 4, 7)
  - [x] **Measure before you commit a number.** Wire Task 1 first, then run the **CI-shaped** measurement (Docker required for Testcontainers): `./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true`. Open `deliveryline-backend/target/site/jacoco/index.html` and read the **BUNDLE-level** `LINE` and `BRANCH` covered ratios. Then run full `./mvnw -B -ntp -pl deliveryline-backend -am verify` and confirm both ratios are **≥** the surefire-skipped numbers (they will be — full `verify` adds unit coverage on top).
  - [x] Add a `jacoco-check` execution: goal `check`, phase `verify`, `<dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>`, with one `<rule>` at `<element>BUNDLE</element>` carrying two `<limit>`s — `LINE` `COVEREDRATIO` and `BRANCH` `COVEREDRATIO`. `<haltOnFailure>` is `true` by default (AC7 — deterministic build failure).
  - [x] **Set each `<minimum>` as a regression FLOOR a few points BELOW the surefire-skipped measurement** (round down to a clean value) — never at or above it, or the gate red-builds the moment it lands or on the first trivial change. *Worked example:* if the CI-shaped run reports line 78%, branch 64%, commit `LINE` minimum `0.70` and `BRANCH` minimum `0.55`. **Replace these with your real measured-minus-headroom values** — do not commit the example numbers blind.
  - [x] Capture the measured ratios and the chosen floors (with the headroom rationale) in the Completion Notes and in the README (Task 5 / AC11).
  - [x] **Sequencing:** wire Task 1 → measure → only then add `check` with the floor. Adding `check` before measuring risks an instant red build.

- [x] **Task 3: Define minimal, documented coverage exclusions (AC: 5, 10)**
  - [x] Add an `<excludes>` list at the JaCoCo `<plugin>` `<configuration>` level (so both `report` and `check` honor it — class-file glob patterns, e.g. `org/dradgo/DeliveryLineApplication.class`).
  - [x] **Exclude `DeliveryLineApplication`** — the Spring Boot bootstrap class is `@SpringBootApplication` + a one-line `main()` calling `SpringApplication.run(...)`; it has no business logic and is not meaningfully unit-testable. Add an inline POM comment stating the rationale.
  - [x] **Be conservative with everything else.** AC5 forbids blanket-excluding business/application code. `infrastructure/config/*` (`LinearConfiguration`, `RunnerConfiguration`, `RunnerContractsConfiguration`, `RunnerProperties`, `LinearPollingHost`) are *candidate* framework glue — **inspect each individually**; exclude a specific class **only** if it is provably pure bean-wiring with no branching/validation logic, each with its own one-line rationale comment. If a config class has any testable logic, leave it in and let the threshold absorb it. **Do not exclude the package wholesale.**
  - [x] There are no generated Java sources in the backend (no MapStruct/annotation-processed code; `openapi.json` is a committed resource, not generated Java) — so "generated sources" exclusions are N/A here. Note this in the README scope description.

- [x] **Task 4: Make the `foundation-gate` Maven profile skip `check` (DISASTER #2)** (AC: 12)
  - [x] Give the `jacoco-check` execution `<configuration><skip>${jacoco.check.skip}</skip></configuration>` and declare a default `<jacoco.check.skip>false</jacoco.check.skip>` in a `<properties>` block in `deliveryline-backend/pom.xml` (the backend POM has no `<properties>` block yet — add one).
  - [x] In the existing **`foundation-gate` profile** (`deliveryline-backend/pom.xml:463-487`), add `<properties><jacoco.check.skip>true</jacoco.check.skip></properties>`. Activating `-Pfoundation-gate` then skips **only** `check` — `prepare-agent`, `prepare-agent-integration`, `merge`, and `report` still run, so Failsafe's `@{argLine}` stays intact (no JVM-fork crash). Add a POM comment explaining why (the foundation-gate job runs only the aggregator subset, so its coverage is not representative; the real gate runs in `backend-contract-tests`).
  - [x] **No `ci.yml` edit is required for the foundation-gate job** — the profile property carries it. *(Acceptable alternative: leave the property at `false` everywhere and pass `-Djacoco.check.skip=true` explicitly in the `foundation-gate` job's `mvn` line in `ci.yml:901`. The profile-property approach is recommended — self-contained in the POM, zero CI-YAML risk.)*
  - [x] AC12 widening is **transitive and already wired**: `backend-contract-tests` runs the real coverage gate and is in `foundation-gate`'s `needs:` chain (`ci.yml:825`); the gate's "Assert all required tiers succeeded" step (`ci.yml:838-853`) fails `foundation-gate` if that tier is non-`success`. **Do NOT add a `@Nested` class to `FoundationGateVerificationTest`** — that aggregator delegate-runs Epic-1 *contract tests* via the JUnit Platform Launcher; a build-level coverage gate is not a JUnit contract. This mirrors exactly how stories 2.30 (Task 9) and 2.31 (AC10) widened the gate for lint.

- [x] **Task 5: Author the `deliveryline-backend/README.md` coverage section (AC: 4, 9, 11)**
  - [x] `deliveryline-backend/README.md` does **not** exist yet. If story 2.30 has already created it (it also targets this file for its lint thresholds), **append** a `## Test Coverage` section; otherwise create the file with a short module header + that section. Structure it so a lint section and a coverage section coexist cleanly.
  - [x] Document (AC11): the standard command `mvn -pl deliveryline-backend verify`; that the HTML report appears at `deliveryline-backend/target/site/jacoco/index.html`; what to do when the gate fails (open the HTML report, find the under-covered class, add tests — *not* lower the threshold); the committed `LINE`/`BRANCH` minimums and the measured-minus-headroom rationale for this phase.
  - [x] Document (AC5): exactly what is excluded (`DeliveryLineApplication`, plus any config class you excluded in Task 3) and why; state that business/application code is never blanket-excluded and that new packages auto-participate (AC10).
  - [x] Note the CI nuance: the binding gate runs in `backend-contract-tests` with Surefire skipped, so the committed thresholds are calibrated against integration + contract + ArchUnit coverage; a local full `mvn verify` shows higher numbers and also passes.
  - [x] Document (AC9 — tool split): *"Spotless, Checkstyle, and SpotBugs enforce formatting, style, and bug patterns; JaCoCo measures test coverage. None substitutes for another — a high-coverage codebase can still be poorly formatted, and a lint-clean codebase can still be untested."* Keep wording consistent with story 2.30's Checkstyle-config tool-split comment (2.30 AC7).

- [x] **Task 6: Confirm CI integration + update `docs/ci-pipeline.md` (AC: 8, 9, 12)**
  - [x] AC8 is essentially pre-satisfied: `backend-contract-tests` already runs `verify` (`ci.yml:383`) and uploads `backend-coverage-jacoco` from `target/site/jacoco/` (which contains `jacoco.xml` + HTML + CSV — `ci.yml:411-418`). Once `check` binds to `verify`, that existing named step **becomes** the gate. **Verify** the artifact now actually contains a populated report (it was effectively empty before — see Task 1).
  - [x] *Optional, recommended ergonomics:* add a `if: failure()` hint step in the `backend-contract-tests` job (mirroring the Spotless/Checkstyle hint steps at `ci.yml:83-86`) pointing devs at the `backend-coverage-jacoco` artifact's `index.html`. Keep any `ci.yml` change minimal — do not restructure the tier.
  - [x] Update `docs/ci-pipeline.md`: replace the "There is **no coverage threshold gate**" paragraph (`docs/ci-pipeline.md:107-110`) with the now-enforced state, and update the `backend-contract-tests` row of the per-tier table to mention the coverage gate. Note in the `foundation-gate` description that backend coverage is part of the gate transitively (AC12).
  - [x] ⚠️ When writing POM/YAML comments, never put a bare `--` inside an XML comment (`<!-- -->`) — it makes the POM unparseable (this bit stories 2.1 and 2.31). Reword (e.g. "the X flag" not `--X`).

- [x] **Task 7: Full verification before claiming done (DISASTER prevention; LOAD-BEARING)** (AC: 2, 6, 7)
  - [x] Run, in order: `./mvnw -B -ntp -pl deliveryline-backend -am verify` (full — Surefire + Failsafe; expects BUILD SUCCESS with the gate green) → then `./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true` (the **CI shape** — this is the binding measurement; must also be BUILD SUCCESS).
  - [x] Prove AC7 deterministically: temporarily bump a `<minimum>` above the measured ratio, run `verify`, confirm a clear `Rule violated for bundle … lines covered ratio is …, but expected minimum is …` failure, then revert the bump.
  - [x] Confirm the `foundation-gate` profile path stays green: `./mvnw -B -ntp -pl deliveryline-backend -am -Pfoundation-gate verify -Dit.test='*FoundationGateVerificationTest*' -Dfailsafe.failIfNoSpecifiedTests=false` — `check` must be skipped (Task 4), `prepare-agent`/`merge`/`report` must still run, no JVM-fork crash.
  - [x] Reactor smoke: `./mvnw -B -ntp -DskipTests clean install` (all modules) green — confirm nothing in the JaCoCo wiring breaks the reactor build or the backend jar's SPA embedding.
  - [x] **Verify in a clean / Linux env** (project memory `verify-ci-fixes-in-clean-env`): coverage instrumentation, the Testcontainers-backed contract tests, and the threshold calculation all depend on Docker + the Linux runner. Local-Windows-green is **not** sufficient — reproduce the `backend-contract-tests` shape (`verify -Dsurefire.skip=true` on Linux with Docker) before claiming done.
  - [x] Scope the commit narrowly: `deliveryline-backend/pom.xml`, `deliveryline-backend/README.md`, `docs/ci-pipeline.md`, and `.github/workflows/ci.yml` **only if** you added the optional hint step. **Never `git add .`** — the tree has untracked `.m2/`, `.agents/`, `.claude/`, `_bmad-output/` (untracked by repo convention) and uncommitted 6.9/2.30 work; stage coverage-tooling files explicitly.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] **Scope note:** This is a build-tooling / static-analysis configuration story. It adds **no** JVM application-service methods, domain exceptions, SPI calls, or state transitions — there is no new application code to instrument with SLF4J/MDC. The project-wide logging standard (below) remains in force for any incidental Java change, but this story is not expected to make one.

### Review Findings

- [x] [Review][Patch] Backend-contract-tests still documents the wrong CI shape [.github/workflows/ci.yml:378] — fixed by rewriting the workflow and CI-pipeline docs to match the story's own completion notes: the job still passes `-Dsurefire.skip=true`, but the current build does not honor that flag, so the gated Linux job effectively runs the full backend `verify` shape.
- [x] [Review][Patch] Clean Linux + Docker reproduction of the real backend-contract-tests shape — RESOLVED 2026-05-22. Reproduced natively inside WSL2 Ubuntu 24.04 (JDK 21, Docker 28.5.1 via Docker Desktop integration) so Testcontainers talks to the daemon directly — the closest local analogue of the `ubuntu-latest` runner, with no docker-in-docker. The two earlier failures were reproduction-environment artifacts, not story defects: the frontend break was WSL Windows-PATH interop leaking a Windows `node.exe` into the build (fixed by stripping `/mnt/<drive>/` PATH entries), and the "no valid Docker environment" was a container without the daemon socket (avoided by running natively in WSL). The exact CI command `./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true` produced BUILD SUCCESS: Surefire 370/0/0, Failsafe 292/0/0/3 (Testcontainers + Ryuk + Postgres-backed contract tests genuinely executed), JaCoCo merge plus report plus check green ("All coverage checks have been met") — Linux-measured BUNDLE LINE 81.33%, BRANCH 62.74%, both clear of the committed 0.75 / 0.55 floors. See Completion Notes.

**Code review 2026-05-22 (bmad-code-review — Blind Hunter / Edge Case Hunter / Acceptance Auditor):**

- [x] [Review][Patch] Drop the verified no-op `-Dsurefire.skip=true` flag from the backend-contract-tests verify command [.github/workflows/ci.yml] — APPLIED 2026-05-22. Decision resolved: remove the flag. The story's own Completion Notes establish that maven-surefire-plugin does not honor `surefire.skip` in this build, so the flag is dead and a latent trap (a future Maven/Surefire upgrade that honors it would silently drop unit tests from the Linux gate). Remove `-Dsurefire.skip=true` from the `run:` command and rewrite the step comment so it no longer claims the flag does anything.
- [x] [Review][Patch] README "Thresholds" section — stale measured numbers + inconsistent rounding rationale [deliveryline-backend/README.md] — APPLIED 2026-05-22. The README commits LINE ≈ 81% (6029/7437) / BRANCH ≈ 62% (1372/2201), the pre-review Windows measurement; the story's authoritative final calibration is the clean Linux WSL CI-shaped run (LINE 81.33% 6049/7438, BRANCH 62.74% 1381/2201). It also says floors were "rounded down to a clean 0.05 multiple" — but rounding 81%/62% down to a 0.05 multiple yields 0.80/0.60, not the committed 0.75/0.55; the prose conflates "round the measurement" with "set a floor below the measurement." The "refactors that merely move covered code around" example is also imprecise (moving covered code leaves the ratio ~unchanged). Rewrite to cite the Linux/CI-platform numbers and describe the floors honestly as "set ~6-7 points below the measurement."
- [x] [Review][Patch] docs/ci-pipeline.md dropped the backend-contract-tests test-selection detail [docs/ci-pipeline.md] — APPLIED 2026-05-22 (restored `@Tag`/class-name-suffix + AC7 ArchUnit note; also reconciled the surefire.skip wording with the flag removal above). The rewritten `backend-contract-tests` table cell removed the sentence "Picks up by `@Tag` or class-name suffix" while adding the coverage-gate language. That selection mechanism is unchanged and is useful triage info; restore it.
- [x] [Review][Patch] Hardcoded `jacoco-merged.exec` filename repeated 3x in pom.xml [deliveryline-backend/pom.xml] — APPLIED 2026-05-22 (extracted to `<jacoco.merged.exec>` property; resolution verified via `help:evaluate`). The merged-exec filename is typed literally in the merge `<destFile>` and two `<dataFile>` entries. A typo in any one silently decouples merge from report/check (JaCoCo `check` no-ops on a missing dataFile). Low priority: extract to a single Maven property (e.g. `<jacoco.merged.exec>`).
- [x] [Review][Defer] Stale `jacoco.exec`/`jacoco-it.exec` on non-clean local rebuilds [deliveryline-backend/pom.xml] — deferred, pre-existing. The JaCoCo agent's append behavior and skipped-tier reuse can let a non-clean local `mvn verify` merge stale exec data. Pre-existing story-1.21 exec-file behavior, not introduced by 2.32; CI uses fresh checkouts so it is unaffected.
- [x] [Review][Defer] docs/ci-pipeline.md foundation-gate row still labeled "Placeholder for story 1.23" [docs/ci-pipeline.md] — deferred, pre-existing. Story 1.23 is `done` but the table cell still calls the job a placeholder with a "shell echo" body. Pre-existing doc staleness outside story 2.32's scope.

## Dev Notes

### Story scope — what this story does and does NOT do

Delivers the **backend test-coverage measurement + gate**: JaCoCo wired so `mvn -pl deliveryline-backend verify` produces a combined (unit + integration) HTML/XML/CSV report and a `check` execution fails the build below committed line/branch floors. It complements story 2.30's lint/format gate (AC9 — distinct tools).

**OUT of scope (do NOT pull in):**
- **Backend lint/format** (Spotless/Checkstyle/SpotBugs) — story **2.30**. 2.32 must not touch those plugins.
- **Frontend coverage** (Vitest coverage thresholds) — that lives with the frontend test story 2.27 (`epics.md:1463` AC10), not here.
- **JaCoCo on `deliveryline-runner-contracts`** — AC1 names `deliveryline-backend/pom.xml` only; runner-contracts has no JaCoCo opt-in. Backend-only.
- **Raising thresholds aspirationally / a coverage "ratchet" automation** — set an honest regression FLOOR for this phase (AC11 "for this phase"); future stories can raise it.
- **Refactoring untested code to hit a number** — set the floor below current reality; do not rewrite production code in a tooling story.
- **Changing the `backend-contract-tests` tier to stop skipping Surefire** — see Open Question Q2; default is to keep 1.21's `-Dsurefire.skip=true` and calibrate to it.

### Critical dependencies & ordering

- **Builds on 1.21 (done):** version pin, `prepare-agent` / `prepare-agent-integration` / `report` executions, the `backend-contract-tests` tier, and the `backend-coverage-jacoco` artifact upload all exist. This story adds the *gate*; it does not start the wiring.
- **Sibling 2.30 (`ready-for-dev`):** shares `deliveryline-backend/pom.xml` and `deliveryline-backend/README.md`. 2.30 deliberately leaves JaCoCo to 2.32 (`2-30-...md:110, :150`). Coordinate README creation (see top warning).
- **Gates Epic 2 backend slices (2.8–2.14):** `epics.md:1031` wants coverage reporting in place before the specification/approval backend code expands — so regressions are caught from the first slice.
- **Story 1.23 (foundation gate, done):** AC12 widens its scope; the mechanism is the transitive `needs:`-chain (Task 4), identical to how 2.30/2.31 widened it for lint.

### Architecture & test-stack alignment

- **AR28 tiered CI** (`architecture.md` — referenced in `ci.yml:4`): the pipeline fails fast on cheap checks before Docker-backed tiers. The coverage gate naturally lands in `backend-contract-tests` (the tier that already runs full `verify` with Docker) — do not invent a new tier or job.
- **Test routing** (`deliveryline-backend/pom.xml:351-426`): Surefire runs pure unit tests only; Failsafe runs ArchUnit + Testcontainers + `*ContractTest` + Spring MVC slice + `*ApplicationTests`. This is *why* DISASTER #1 exists — coverage is mostly produced by the Failsafe tier, so the gate must read `jacoco-it.exec`.
- **No architecture-mandated coverage number:** neither `architecture.md` nor the PRD pins a specific coverage percentage. AC4/AC11 explicitly leave the value to "this phase" judgement, justified in the README — hence the empirical-measurement approach (Task 2).

### Latest tech notes

- **JaCoCo 0.8.13** is pinned in root `pom.xml:36` (by story 1.21). It fully supports Java 21 bytecode (the project targets `java.version=21`, Spring Boot 4.0.6). **No version bump is needed or in scope** — use the pinned version.
- The `prepare-agent-integration` goal + the JaCoCo `merge` goal are standard parts of jacoco-maven-plugin 0.8.x; `merge` has no default lifecycle phase, so it must be bound explicitly (Task 1 binds it to `post-integration-test`).

### Previous Story Intelligence

**From story 2.30 (Backend Lint — `ready-for-dev`; the direct sibling, follow its shape):**
- 2.30 found that **story 1.21 had already done most plumbing** and its real work was *finishing the wiring* — the identical situation here. Read what exists before adding.
- 2.30 Task 9 / 2.31 AC10: the foundation-gate widening is satisfied **transitively** via the `needs:` chain — no new gate job, no `FoundationGateVerificationTest` change. 2.32 Task 4 does the same.
- 2.30 ran `spotless:apply` before binding `spotless:check` to `verify` so the in-flight tree did not red-build. 2.32's analogue: **measure coverage first, then add `check` with the floor below the measurement** — never commit a threshold before measuring (Task 2).
- **POM XML-comment `--` trap:** a bare `--` inside `<!-- -->` makes the POM unparseable (bit stories 2.1 and 2.31). Reword comments.

**From story 2.31 (Frontend Lint — done) & the CI history:**
- The `~285 stat-only "modified"` artifact and cross-platform line-ending fragility are real; the project memory `verify-ci-fixes-in-clean-env` exists because **local green ≠ CI green**. Coverage depends on Docker + Linux Testcontainers — verify there (Task 7).

### Logging Requirements (project-wide standard)

Build-tooling story; no JVM/application code is added, so the SLF4J + Logback standard (INFO entry/exit, WARN typed-rejection, ERROR unhandled, MDC keys `correlationId`/`workflowRunId`/`idempotencyKey`/`actorIdentity`/`actorType`, redaction before logging, list-appender test pinning) is dormant here but remains in force for any incidental backend change.

### Anti-patterns to avoid

- **Do NOT let `report`/`check` read only `jacoco.exec`** — that ignores the Failsafe tier where most coverage is produced (DISASTER #1). Read merged (unit + integration) data.
- **Do NOT touch `jacoco-prepare-agent` or `jacoco-prepare-agent-integration`** — they are correct and load-bearing for Failsafe's `@{argLine}`.
- **Do NOT use `-Djacoco.skip=true` to keep the gate out of the `foundation-gate` job** — it skips `prepare-agent` and crashes the Failsafe fork (DISASTER #2). Skip only `check`, via the profile property.
- **Do NOT commit a threshold before measuring** — an aspirational floor red-builds instantly. Measure (CI-shaped run), then set the floor below it.
- **Do NOT blanket-exclude packages** to hit a number — AC5 forbids excluding business/application code; exclude only `DeliveryLineApplication` and (case-by-case, with rationale) provably-pure framework glue.
- **Do NOT add a per-package `<includes>` allowlist** — it breaks AC10 (new packages would silently not participate). Use a `BUNDLE` rule + `<excludes>` only.
- **Do NOT add JaCoCo to `deliveryline-runner-contracts`** or touch the Spotless/Checkstyle/SpotBugs plugins — out of scope.
- **Do NOT claim done on local-Windows-green alone** — reproduce the Linux + Docker `backend-contract-tests` shape (project memory `verify-ci-fixes-in-clean-env`).
- **Do NOT `git add .`** — stage coverage-tooling files explicitly; the tree has untracked dirs and in-flight 6.9/2.30 work.

### Project Structure Notes

- All changes are confined to `deliveryline-backend/pom.xml` (JaCoCo `merge`/`report`/`check` executions, `<properties>`, `foundation-gate` profile property), a new/extended `deliveryline-backend/README.md`, `docs/ci-pipeline.md`, and at most one optional hint step in `.github/workflows/ci.yml`.
- Coverage report output path: `deliveryline-backend/target/site/jacoco/` (`index.html`, `jacoco.xml`, `jacoco.csv`) — the JaCoCo default; do not relocate it (CI's artifact upload and `docs/ci-pipeline.md` already assume it).
- No new source files, no schema/migration changes, no new dependencies.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.32] — authoritative ACs + execution-order note (lines 1025-1050)
- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.30] — sibling lint story; AC9 tool-split relationship (lines 982-1001)
- [Source: _bmad-output/implementation-artifacts/2-30-backend-lint-and-format-spotless-checkstyle-spotbugs.md] — JaCoCo explicitly out of scope there (lines 110, 150); transitive foundation-gate widening pattern (Task 9); POM `--` comment trap
- [Source: _bmad-output/implementation-artifacts/1-21-github-actions-ci-tiered-pipeline.md] — JaCoCo report-only seeding, the `format-static-checks`/`backend-contract-tests` tiers
- [Source: pom.xml:30-37, 123-150] — root pluginManagement: JaCoCo 0.8.13 pin + `prepare-agent`/`prepare-agent-integration`/`report` executions + the "story 2.32 wires the gate" handoff comment
- [Source: deliveryline-backend/pom.xml:346-349] — versionless JaCoCo `<plugin>` opt-in
- [Source: deliveryline-backend/pom.xml:351-426] — Surefire/Failsafe test routing (why most coverage is in `jacoco-it.exec`)
- [Source: deliveryline-backend/pom.xml:463-487] — the `foundation-gate` Maven profile (Task 4 adds the skip property here)
- [Source: deliveryline-backend/src/main/java/org/dradgo/DeliveryLineApplication.java] — the bootstrap class to exclude (AC5)
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/] — candidate framework-glue classes (inspect case-by-case)
- [Source: .github/workflows/ci.yml:362-418] — `backend-contract-tests` tier: runs `verify -Dsurefire.skip=true`, uploads `backend-coverage-jacoco` (AC8 mostly pre-wired)
- [Source: .github/workflows/ci.yml:810-853, 901-903] — `foundation-gate` job: `needs:` chain + assert step (AC12 transitive widening) and the `-Pfoundation-gate verify` invocation (DISASTER #2)
- [Source: docs/ci-pipeline.md:107-110] — current "no coverage threshold gate" statement (Task 6 updates it)
- [Source: project memory `verify-ci-fixes-in-clean-env`] — reproduce CI in a clean/Linux env before claiming done

### Open clarifications (resolve before/at start; otherwise apply the recommended default)

- **Q1 (threshold values):** the committed `LINE`/`BRANCH` minimums cannot be known until measured. **Recommended:** wire Task 1, run the CI-shaped `verify -Dsurefire.skip=true`, set each floor a few points below the measured BUNDLE ratio (rounded down), document the headroom rationale in the README. No user decision needed unless Alex wants a specific headroom policy (e.g. exactly 5% vs 10%).
- **Q2 (CI gate shape — Surefire skip):** the binding gate runs in `backend-contract-tests` with `-Dsurefire.skip=true`, so the gate measures integration + contract + ArchUnit coverage only; code reachable solely by pure unit tests is not protected by the gate. **Recommended:** keep 1.21's `-Dsurefire.skip=true` (changing it adds a ~2-4 min redundant unit re-run and revisits a deliberate 1.21 design choice) and calibrate thresholds to that shape, documenting the limitation in the README. *Alternative:* drop the skip so CI coverage == local coverage and the gate also protects unit-only code.
- **Q3 (exclusions beyond `DeliveryLineApplication`):** **Recommended:** exclude only `DeliveryLineApplication` outright; inspect `infrastructure/config/*` and exclude a specific class only if it is provably pure bean-wiring, each with a rationale comment. Do not exclude the config package wholesale.
- **Q4 (`deliveryline-backend/README.md` ownership with 2.30):** both 2.30 and 2.32 write this file. **Recommended:** whichever story merges first creates it; the second appends its section. No conflict if both use distinct `## ` sections.
- **Q5 (foundation-gate skip mechanism):** **Recommended:** the `foundation-gate` profile sets `jacoco.check.skip=true` (self-contained in the POM, zero CI-YAML change). *Alternative:* pass `-Djacoco.check.skip=true` explicitly in the `foundation-gate` CI job. Either is acceptable.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — `claude-opus-4-7[1m]`

### Debug Log References

- CI-shaped measurement (`mvn -pl deliveryline-backend -am clean verify -Dsurefire.skip=true -Djacoco.check.skip=true`): BUILD SUCCESS; `jacoco-report` loaded `jacoco-merged.exec`, analyzed bundle with 230 classes.
- Empirically observed `-Dsurefire.skip=true` is NOT honored by maven-surefire-plugin: a clean `verify -Dsurefire.skip=true` still produced 40 Surefire unit-test result files under `deliveryline-backend/target/surefire-reports/`. See Completion Notes.
- AC7 proof: with the `LINE` minimum temporarily at `0.97`, `mvn -pl deliveryline-backend jacoco:check@jacoco-check` produced `[WARNING] Rule violated for bundle deliveryline-backend: lines covered ratio is 0.81, but expected minimum is 0.97` and BUILD FAILURE (exit 1). Bump reverted to `0.75`.
- Pre-existing non-failing SpotBugs MEDIUM findings on `org.dradgo.infrastructure.config` / `DomainRegistry` appear in the build log; not introduced by this story (failThreshold=High, non-failing) and out of scope.
- Clean-Linux reproduction (2026-05-22): `./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true` inside WSL2 Ubuntu 24.04 (JDK 21, Docker 28.5.1) → BUILD SUCCESS in 4m25s; `jacoco-merge` loaded both `jacoco-it.exec` and `jacoco.exec`, `jacoco-report`/`jacoco-check` analyzed 230 classes, "All coverage checks have been met."

### Completion Notes List

**What was built**

- `deliveryline-backend/pom.xml` — JaCoCo coverage gate:
  - `jacoco-merge` execution (phase `post-integration-test`) merges `jacoco.exec` (Surefire) + `jacoco-it.exec` (Failsafe) into `jacoco-merged.exec` — resolves DISASTER #1 (report and check now see unit + integration coverage combined).
  - `jacoco-report` overridden by id to read `jacoco-merged.exec`.
  - `jacoco-check` execution (phase `verify`) — BUNDLE rule with `LINE` and `BRANCH` `COVEREDRATIO` limits; `skip` bound to `${jacoco.check.skip}`.
  - Plugin-level `excludes`: only `org/dradgo/DeliveryLineApplication.class` (AC5) — `infrastructure/config` classes left in and absorbed by the floor.
  - New `properties` block: `jacoco.check.skip=false`; the `foundation-gate` profile overrides it to `true` — resolves DISASTER #2 (check skipped there, while `prepare-agent`/`merge`/`report` still run, so no JVM-fork crash; AC12 holds transitively via the `needs:` chain).
- `deliveryline-backend/README.md` — new. Module header, Quality gates (AC9 tool split vs story 2.30 lint), and a full Test Coverage section (command, HTML report path, thresholds + rationale, failure remediation, exclusions, CI integration). Story 2.30 did not create this file, so it was created fresh.
- `docs/ci-pipeline.md` — replaced the "no coverage threshold gate" paragraph with the enforced state; updated the `backend-contract-tests` and `foundation-gate` per-tier table rows.
- `.github/workflows/ci.yml` — added `id: backend-verify` to the `backend-contract-tests` verify step plus a `Coverage gate failure hint` step (mirrors the existing Spotless/Checkstyle hint pattern).

**Measured coverage & thresholds**

Reactor `verify` with the Testcontainers contract suite (Docker): BUNDLE **LINE 81.1% (6029/7437)**, **BRANCH 62.3% (1372/2201)**, 230 classes analyzed (DeliveryLineApplication excluded). Committed regression floors: **`LINE` 0.75**, **`BRANCH` 0.55** — a few points below the measurement, rounded down to a clean 0.05 multiple (~6 / ~7 points headroom). Floors for this phase, not targets.

**Finding — `-Dsurefire.skip=true` is a no-op**

The `backend-contract-tests` CI tier runs `verify -Dsurefire.skip=true` intending to skip the unit tier. maven-surefire-plugin does not honor a `surefire.skip` property — a clean `verify -Dsurefire.skip=true` ran all 40 unit-test classes. So `backend-contract-tests` runs the full suite and CI coverage equals a full local `verify`. Threshold calibration is unaffected (measured with the exact CI command). The story's premise that "CI runs with Surefire skipped" is therefore inaccurate; the README and `docs/ci-pipeline.md` were written to the actual behavior. The redundant unit re-run in `backend-contract-tests` is a pre-existing story-1.21 issue (Open Clarification 4), out of scope for 2.32 — recommend a follow-up / retro item.

**Verification (Task 7)**

All runs on Windows + Docker (real Testcontainers contract suite), each a clean reactor build:

- `verify -Dsurefire.skip=true` (CI shape) — BUILD SUCCESS; `jacoco-check` ran, "All coverage checks have been met."
- `verify` (full) — BUILD SUCCESS; gate met.
- `-Pfoundation-gate verify -Dit.test='*FoundationGateVerificationTest*' -Dfailsafe.failIfNoSpecifiedTests=false` — BUILD SUCCESS; `jacoco-check` skipped ("property jacoco.skip is set"), `merge`/`report` still ran — no JVM-fork crash.
- `clean install -DskipTests` (reactor smoke) — BUILD SUCCESS; with no tests run, JaCoCo `merge`/`report`/`check` gracefully skip (missing exec data) and the reactor build is unaffected.
- AC7 — deterministic `Rule violated` failure proven (see Debug Log References).

Linux-env note (Task 7 subtask "Verify in a clean / Linux env"): a later review pass attempted Linux reproduction via `docker run ... maven:3.9.9-eclipse-temurin-21`. Two separate blockers surfaced:

- Full reactor shape (`./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true`) failed in `deliveryline-frontend` on Linux before the backend module with `frontend-maven-plugin` reporting `Could not determine Node.js install directory`.
- Backend-isolated shape (`./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true -Dfrontend-maven-plugin.skip=true`) reached `deliveryline-backend`, executed JaCoCo `prepare-agent`, generated `target/site/jacoco/index.html`, and then failed in Failsafe because Testcontainers inside the Linux container could not find a valid Docker environment (`Could not find a valid Docker environment. Please see logs and check configuration`).

So Linux verification was attempted and partially exercised the JaCoCo path, but the story still does **not** have a clean Linux + Docker reproduction of the real `backend-contract-tests` CI shape from this environment.

**Review follow-up (2026-05-22) — clean Linux + Docker reproduction RESOLVED (supersedes the note above)**

The previously-pending clean-Linux verification is now green. It was run **natively inside WSL2 Ubuntu 24.04** (JDK 21 / Docker 28.5.1 via Docker Desktop integration) rather than docker-in-docker — this lets Testcontainers talk to the daemon directly and is the closest local analogue of the `ubuntu-latest` CI runner. The two earlier failures were reproduction-environment artifacts, not story defects:

- The full-reactor frontend failure was **WSL Windows-PATH interop** leaking a Windows `node.exe` (v24.12.0) into the frontend's nested `npm run routes:generate`, which then ran under `cmd.exe` with a UNC working directory. Fixed by stripping every `/mnt/<drive>/` entry from `PATH` so the toolchain is 100% Linux (Linux Node is installed fresh by `frontend-maven-plugin`).
- The "no valid Docker environment" failure was a docker-in-docker container without the daemon socket mounted; running natively in WSL avoids it entirely.

The working tree (including the still-uncommitted 2.32 changes to `pom.xml` / `README.md` / `ci.yml` / `docs/ci-pipeline.md`) was staged into WSL ext4 excluding `node_modules` / `target` / `.frontend-node`, so no Windows-native build artifacts leaked in. The exact CI command `./mvnw -B -ntp -pl deliveryline-backend -am verify -Dsurefire.skip=true` produced **BUILD SUCCESS** (4m25s):

- Surefire **370 / 0 / 0** (confirms again that `-Dsurefire.skip=true` is a no-op — the unit tier still ran).
- Failsafe **292 / 0 / 0 / 3 skipped** — ArchUnit + Spring MVC slice + Testcontainers contract tests; `tc.testcontainers/ryuk` and the Postgres-backed contract suite genuinely executed against real Docker.
- JaCoCo `merge` loaded `jacoco.exec` + `jacoco-it.exec`; `report` + `check` analyzed 230 classes; **"All coverage checks have been met."**
- Linux-measured BUNDLE coverage: **LINE 81.33%** (6049/7438), **BRANCH 62.74%** (1381/2201) — both clear of the committed `0.75` / `0.55` floors and consistent with the earlier Windows measurement (LINE 81.1%, BRANCH 62.3%), confirming the thresholds are correctly calibrated for the real CI platform.

### File List

- `deliveryline-backend/pom.xml` — modified (JaCoCo `merge`/`report`/`check` executions, plugin `excludes`, `properties` block, `foundation-gate` profile `jacoco.check.skip` override)
- `deliveryline-backend/README.md` — new (module README + Test Coverage section)
- `docs/ci-pipeline.md` — modified (coverage-gate documentation; per-tier table rows)
- `.github/workflows/ci.yml` — modified (`backend-contract-tests`: step `id` + coverage-gate failure hint step)

Process tracking file (not a story code deliverable): `_bmad-output/implementation-artifacts/sprint-status.yaml` — `2-32` status updated.

## Change Log

| Date | Change |
| --- | --- |
| 2026-05-22 | Story 2.32 implemented — JaCoCo backend coverage gate: `jacoco-merge` + `jacoco-check` executions, merged unit + integration report, BUNDLE thresholds `LINE` 0.75 / `BRANCH` 0.55, `foundation-gate` profile skip, new `deliveryline-backend/README.md`, `docs/ci-pipeline.md` + `ci.yml` updates. All Task 7 verification green; status set to review. |
| 2026-05-22 | Review follow-up resolved — clean Linux + Docker reproduction of the `backend-contract-tests` CI shape verified green natively in WSL2 Ubuntu 24.04 (real Testcontainers): BUILD SUCCESS, Surefire 370/0/0, Failsafe 292/0/0/3, JaCoCo gate met, Linux-measured LINE 81.33% / BRANCH 62.74%. Last open review item closed; status set to review. |
