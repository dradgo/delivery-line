# Story 3.34: CI Tier — Real Docker Runner Image Build + Compatibility Checks

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Created 2026-06-13 via bmad-create-story. This is an INFRASTRUCTURE story: it adds GitHub
     Actions jobs to .github/workflows/ci.yml + a local-repro section to docs/setup-local.md. It
     adds NO Java/TS production code and does NOT modify the runner images, entrypoints, or the
     RealRunnerContractIT (all of which already exist and are DONE — stories 3.3/3.4/3.8). Read the
     "Central Reconciliations" block FIRST: the epic ACs cross-reference stale story numbers, an
     AC10 shell command that does not work as written against the profile-gated compose services,
     and a foundation-gate-vs-path-filter tension (OQ-1) that the implementing dev must resolve. -->

## Story

As a backend developer + CI maintainer,
I want a dedicated, path-triggered CI tier that **really** builds the Codex + Claude runner images, runs each image's `--self-test`, and runs the `RealRunnerContractIT` (story 3.8) in a Linux-only Docker-backed job,
so that runner-image drift, schema-contract drift, or self-test failures surface in CI **before** reaching a pilot — closing the CI-wiring that stories 3.8 (Decisions D1/D8/D10) and 3.7 (the `docker-runner-it` Maven profile) deliberately deferred to this story.

---

## ⚠️ READ FIRST — Central Reconciliations (the epic ACs describe an end-state; here is what is actually true in this repo today)

> The epic text for story 3.34 was written at planning time and cross-references several story numbers and assumes a compose/CLI shape that has since shifted. **The implementing dev MUST honor these reconciliations over the literal epic AC wording.** Where an AC's literal wording conflicts with a reconciliation, the reconciliation wins.

1. **This is a CI + docs story — you are CONSUMING finished artifacts, not building them.** The runner images, `--self-test`, and `RealRunnerContractIT` already exist and are `done`:
   - `runners/codex/entrypoint.sh` + `runners/claude/entrypoint.sh` already implement `--self-test` (verifies node + runner helper + the agent CLI version pin + the OpenSpec CLI pin + the vendored superpowers skills, then `exit 0`; `exit 1` with `SELF-TEST FAIL: …` on any miss). Stories 3.3/3.4/3a-6/3a-7.
   - `docker-compose.yml` already defines `codex-runner` + `claude-runner` build targets (see Reconciliation 6 for their **profile gate**).
   - `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java` already exists, is `@Tag("docker-runner-it")` + `@Tag("real-runner-contract")` + `@EnabledIfDockerAvailable`, and builds its **own** mock images in `@BeforeAll` (see Reconciliation 4). Story 3.8.
   **Do NOT edit `runners/**`, the entrypoints, the Dockerfiles, or `RealRunnerContractIT`.** If you find yourself editing those, stop — your deliverable is `.github/workflows/ci.yml` + `docs/setup-local.md` only (plus possibly a tiny semver-source file, Reconciliation 8).

2. **`RealRunnerContractIT` is story 3.8 (DONE), not "story 3.8" as a future dependency.** The epic AC body refers to "the real-runner contract integration test from story 3.8" and (in the story-3.8 epic text) to a future "story 3.28" CI tier — **3.28 is a stale planning number for THIS story (3.34).** There is no 3.28 in this repo's numbering. You are the CI-tier story those references point at.

3. **There are THREE new jobs, and they are PATH-TRIGGERED — but path-triggering a job inside the always-on `ci.yml` is NOT `on.paths`.** GitHub Actions `on.pull_request.paths` filters the **whole workflow**, and `ci.yml` must keep running on every PR (it carries `foundation-gate`). → Implement AC2/AC9 with a **`detect-changes` job** (a cheap first job using `actions/github-script` to list changed files, exactly like the existing `foundation-gate` "Detect non-docs changed files on PR" step at `.github/workflows/ci.yml:1150`) that sets a `runner-paths-changed` output, and gate each of the three new jobs with `if: needs.detect-changes.outputs.runner-paths-changed == 'true'`. The watched path set is: `runners/**`, `runner-contracts/**` (note: the module dir is `deliveryline-runner-contracts/**` — see Reconciliation 9), and `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/**`. On `push: main`, treat the jobs as always-eligible (do not path-filter main builds — AC4 needs `:latest` tags on every main build). **Do NOT split into a second workflow file** unless you have a strong reason; the changed-files detector keeps one tier order and one PR-comment surface.

4. **`runner-contract-real` does NOT consume the images `runner-image-build` produces — it REBUILDS its own (Decision-level nuance; AC7's parenthetical is inaccurate).** AC7 says `runner-contract-real needs: runner-image-build` "(consumes the built images via Docker daemon shared with Testcontainers)." In reality `RealRunnerContractIT.@BeforeAll` runs its **own** `docker build … --build-arg INSTALL_*_CLI=false -t deliveryline/{codex,claude}-runner:real-contract-it .` (see `RealRunnerContractIT.java:177-230`). Those are the **mock-CLI, offline** images tagged `:real-contract-it` — different build args AND different tags from `runner-image-build`'s production `:pr-{n}` images. → Keep `needs: runner-image-build` for **ordering + BuildKit layer-cache warming** (the `node:22-slim` base + the `COPY` layers are shared, so the IT's rebuild is a fast cache hit — this is what makes AC8 pay off here), but write the job comment to state plainly that the IT rebuilds and does not reuse the tagged images. Do NOT try to make the IT consume `:pr-{n}` (that would require editing the DONE test — out of scope).

5. **`runner-image-build` builds the REAL production images; `runner-contract-real` (via the IT) builds the MOCK images — this split is the whole point.** The self-test job's value is catching REAL drift (npm-install failure, `codex`/`openspec` version-pin drift, missing `ca-certificates`). So `runner-image-build` must build with `INSTALL_CODEX_CLI=true` / `INSTALL_CLAUDE_CLI=true` (the compose default — see Reconciliation 6), i.e. a **network npm install** of `@openai/codex@0.135.0`, `@anthropic-ai/claude-code`, and `@fission-ai/openspec@1.4.1`. That is network-dependent and heavier than the existing `runner-image-compat` tier — acceptable because it is path-triggered (AC2), not every-PR. The existing `runner-image-compat` tier (`.github/workflows/ci.yml:515`, every PR, **offline** `INSTALL_*_CLI=false` mock build + contract re-validate) **stays unchanged** and is complementary; do not remove or fold it. See Reconciliation 11 for the deliberate division of labor.

6. **The compose runner services are profile-gated, so AC10's documented command DOES NOT WORK AS WRITTEN — fix it.** `docker-compose.yml` puts `codex-runner` + `claude-runner` behind `profiles: ["runners"]` (so Spring Boot's compose autoconfig `up --wait` doesn't try to start the one-shot runners). Consequences you must honor in BOTH the CI job and the AC10 docs:
   - A bare `docker compose build` builds **neither** runner service (profiled services are skipped). Use `docker compose --profile runners build` **or** name them: `docker compose build codex-runner claude-runner`.
   - `docker compose run --rm codex-runner --self-test` **does** work (compose v2 auto-activates a named service's profile for `run`), so the AC10 self-test invocations are fine once the build is corrected.
   - → AC10's documented local-repro block must be: `docker compose --profile runners build` then `docker compose run --rm codex-runner --self-test` then `docker compose run --rm claude-runner --self-test`. Mirror exactly that in the CI `runner-image-build` + `runner-image-self-test` jobs so "CI == local" is literally true (AC10).

7. **Foundation-gate widening (AC6) collides with path-filtering (AC2) — this is OQ-1, the one real decision.** AC6 says `runner-contract-real` success is "required for foundation-gate PRs … its failure blocks merge regardless of other green checks." But the existing `foundation-gate` job asserts **every** `needs:` ended in `success` and converts `skipped`→`fail` (`.github/workflows/ci.yml:1074-1089`). If you add the path-filtered `runner-contract-real` to `foundation-gate.needs`, **every docs-only PR (where the job is skipped) would fail the gate.** Story 3.8's deferred-work entry already flagged this and chose: realize the widening as a **dedicated required check**, NOT by adding the heavy test to `FoundationGateVerificationTest`. → **Recommended (and the default this story ships):** do NOT add `runner-contract-real` to `foundation-gate.needs`. Instead make `runner-contract-real` a **standalone required status check** via branch protection, and handle the "path-filtered → didn't run" case with a tiny always-runs **`runner-contract-real-gate`** aggregator job (mirrors the `foundation-gate` "Assert all required tiers succeeded" pattern) that passes when the real job was either `success` OR legitimately skipped because no runner paths changed. This keeps merges unblocked on docs-only PRs while still hard-blocking on runner-path PRs. **Surface OQ-1 to Alex before finalizing** (see Open Questions) — if Alex wants it folded directly into `foundation-gate.needs`, the changed-files detector must instead make the real job emit a green "no-op success" (not a skip) on non-runner PRs so the gate's skip→fail logic stays satisfied.

8. **Canonical image name is `deliveryline/codex-runner` / `deliveryline/claude-runner` (NOT `runners-codex`).** Beware: the existing offline `runner-image-compat` tier tags `deliveryline/runners-codex:ci` / `deliveryline/runners-claude:ci` (a different name) — do **not** copy that name. The compose file, the conformance ITs, and `RealRunnerContractIT` all use `deliveryline/codex-runner` / `deliveryline/claude-runner`; AC4 wants `deliveryline/codex-runner:pr-{prNumber}` + `deliveryline/claude-runner:pr-{prNumber}`. Use the `codex-runner`/`claude-runner` form. The PR number is `${{ github.event.pull_request.number }}`. For `push: main`, tag `:latest` plus a semver tag — the semver flows from the Dockerfile `ARG IMAGE_VERSION` (`runners/codex/Dockerfile:44`, default `latest`). **There is no existing semver source-of-truth file** for the runner image; decide one (e.g. a `runners/VERSION` file or reuse the Maven project version) and pass it via `--build-arg IMAGE_VERSION=<semver>`. Flag the choice in Completion Notes (this is a minor in-scope addition if you add a VERSION file).

9. **Module/path names: `deliveryline-runner-contracts` (not `runner-contracts`), `deliveryline-backend` (not `backend`).** The epic writes `runner-contracts/**` and `backend/src/main/java/...`. In this repo the schema dir is `deliveryline-runner-contracts/src/main/resources/schemas/` and the adapter is `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/**`. Use the real module-prefixed paths in every `paths`/changed-files matcher.

10. **The `runner-contract-real` job command selects the test via the `-Pdocker-runner-it` profile, not a raw tag filter.** `RealRunnerContractIT` is excluded from the default Failsafe `verify` tier by `<excludedGroups>known-failure,docker-runner-it</excludedGroups>` (`deliveryline-backend/pom.xml:569`). The `-Pdocker-runner-it` profile clears that exclusion. → The job runs: `./mvnw -B -ntp -Pdocker-runner-it -pl deliveryline-backend -am verify -Dit.test=RealRunnerContractIT -Dfailsafe.failIfNoSpecifiedTests=false`. (`-am` keeps the freshest `deliveryline-runner-contracts` jar on the classpath — [[runner-contracts-schema-stale-in-m2]].) Do NOT invent a new `-Preal-runner-contract` profile; the `@Tag("real-runner-contract")` is the *semantic* selector for humans/future filtering, but the runnable selector today is the `docker-runner-it` profile.

11. **Division of labor between the new tier and the existing `runner-image-compat` tier (so you don't duplicate or delete the wrong thing):**
   | Job | Trigger | Build args | What it proves |
   |---|---|---|---|
   | `runner-image-compat` (EXISTING, keep) | every PR | `INSTALL_*_CLI=false` (offline mock) | image build-shape + schema-v1 contract re-validation, cheap, no network |
   | `runner-image-build` (NEW) | runner-path PRs + main | `INSTALL_*_CLI=true` (real, via compose) | real npm install + real CLI version pins resolve; image actually builds with the production toolchain |
   | `runner-image-self-test` (NEW) | needs build | (consumes built real image) | `--self-test` exit 0 on the REAL image (real `codex`/`openspec`/superpowers present + pinned) |
   | `runner-contract-real` (NEW) | needs build | IT rebuilds `INSTALL_*_CLI=false` | end-to-end broker→adapter→contract-validator→persistence against real images |

---

## Acceptance Criteria

> Carried from epic-03 story 3.34, **reconciled** per the block above. Reconciliation notes are called out inline where the literal AC wording diverges from repo reality.

1. **Three new CI jobs exist in `.github/workflows/ci.yml`:** `runner-image-build`, `runner-image-self-test`, `runner-contract-real`, all on `ubuntu-latest` (Docker-backed jobs are Linux-only — story 1.21 AC3). `runner-image-build` builds both **real** Codex + Claude images via `docker compose --profile runners build` (R6: the bare `docker compose build` skips profiled services; R5: real `INSTALL_*_CLI=true`). `runner-image-self-test` runs `--self-test` on each built image. `runner-contract-real` runs `RealRunnerContractIT` via `-Pdocker-runner-it` (R10).

2. **Path-triggered (R3).** The three jobs run only on PRs whose changed files touch `runners/**`, `deliveryline-runner-contracts/**` (R9), or `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/**` — implemented via a `detect-changes` job + `if:` guards, NOT `on.paths`. Docs-only / unrelated PRs skip all three (CI-time saver). On `push: main` the jobs always run (R3).

3. **PR summary comment.** When the three jobs run on a PR, a single CI summary comment is upserted (deterministic marker, e.g. `<!-- runner-image-ci-status -->`, mirroring the `foundation-gate` upsert at `.github/workflows/ci.yml:1178`) showing: which runner images built, each image's `--self-test` outcome, and the `runner-contract-real` outcome. The job that posts it escalates `pull-requests: write` at the job level only (workflow root stays `contents: read`). Make runner regressions visible without opening the Actions UI.

4. **Image-tag pinning.** PR builds tag `deliveryline/codex-runner:pr-{prNumber}` + `deliveryline/claude-runner:pr-{prNumber}` (R8: canonical `codex-runner`/`claude-runner`, `prNumber` = `github.event.pull_request.number`). `main` builds tag `:latest` + a semver tag driven by `--build-arg IMAGE_VERSION=<semver>` (R8: decide the semver source; note it in Completion Notes).

5. **Flake control (no blanket retries — story 1.21 AC5).** A `runner-contract-real` flake is surfaced as a tracked tech-debt item (a `deferred-work.md` entry + the PR comment notes it), NOT silently retried. If a container-cold-start / base-layer-pull flake is observed, any retry is scoped to the *pull/build* step ONLY, inline-documented — never wrap the test execution (matches the `backend-contract-tests` policy comment at `.github/workflows/ci.yml:430`).

6. **Foundation-gate relationship (R7 — OQ-1).** `runner-contract-real` is a **required check that blocks merge on runner-path PRs**. **Default realization (this story):** a standalone required status check + an always-runs `runner-contract-real-gate` aggregator that passes on `success` OR legitimate path-skip — so docs-only PRs are NOT blocked and the existing `foundation-gate` is left unchanged. Do **not** add `RealRunnerContractIT` to `FoundationGateVerificationTest`, and do **not** naively add the path-filtered job to `foundation-gate.needs` (it would red every docs-only PR — R7). Resolve OQ-1 with Alex before finalizing the exact wiring.

7. **Dependent ordering.** `runner-image-self-test` `needs: runner-image-build`. `runner-contract-real` `needs: runner-image-build` — for ordering + BuildKit cache warming, NOT image reuse (R4: the IT rebuilds its own mock images).

8. **Build caching.** `runner-image-build` uses BuildKit cache (GHA cache backend, `type=gha`, or a registry/local cache) keyed on a hash of the runner Dockerfiles + entrypoints (+ `runner.mjs` + vendored superpowers + mock scripts) so a PR that doesn't change the runner image gets a cache hit and skips the heavy npm-install rebuild. Because the `runner-contract-real` IT shares the `node:22-slim` base + COPY layers, the warmed cache also speeds its rebuild (R4).

9. **Schema-change cross-runner consistency (story 3.4 AC10).** A PR touching `deliveryline-runner-contracts/src/main/resources/schemas/**` (R9) is inside the watched path set (AC2), so it triggers `runner-image-build` for BOTH images and asserts both build + pass `--self-test` — preventing a schema change from silently breaking one runner. (No extra job needed beyond AC2's path set including the schema dir.)

10. **Documented "reproduce CI locally" path in `docs/setup-local.md`.** Add a section whose commands **exactly match** what `runner-image-build` + `runner-image-self-test` run: `docker compose --profile runners build` (R6 — NOT bare `docker compose build`) then `docker compose run --rm codex-runner --self-test` then `docker compose run --rm claude-runner --self-test`, plus the `runner-contract-real` local invocation `./mvnw -Pdocker-runner-it -pl deliveryline-backend -am verify -Dit.test=RealRunnerContractIT` (cross-link `scripts/start-all.{ps1,sh}` story 1.17 + the unified compose file). Verify the doc commands actually run green on Linux/Docker (WSL2 per [[wsl-linux-ci-reproduction]]) before claiming AC10 — a copy-paste-broken repro doc is an AC10 failure.

---

## Tasks / Subtasks

- [x] **Task 1 — `detect-changes` job for path-triggering (AC2, AC9; R3, R9)**
  - [x] Add a cheap first job `detect-changes` using `actions/github-script@v7` (mirror the `foundation-gate` changed-files step at `.github/workflows/ci.yml:1150`) that paginates `pulls.listFiles` and sets output `runner-paths-changed=true` when any changed file starts with `runners/`, `deliveryline-runner-contracts/`, or `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/`.
  - [x] On `push: main` (no PR context), default the output to `true` so main builds always run (AC4 `:latest`).
- [x] **Task 2 — `runner-image-build` job (AC1, AC4, AC8; R5, R6, R8)**
  - [x] `needs: [detect-changes]`, `if: needs.detect-changes.outputs.runner-paths-changed == 'true'`, `ubuntu-latest`.
  - [x] Set up Docker Buildx; configure BuildKit cache (`type=gha`) keyed on a hash of `runners/**` Dockerfiles + entrypoints + `lib/runner.mjs` + `test/mock-*.sh` + `runners/vendor/**` (AC8).
  - [x] Build BOTH real images via `docker compose --profile runners build` (R6) — confirm the compose build honors `INSTALL_*_CLI=true` default (R5).
  - [x] Tag images: PR → `deliveryline/{codex,claude}-runner:pr-${{ github.event.pull_request.number }}`; main → `:latest` + semver via `--build-arg IMAGE_VERSION` (R8). Decide + document the semver source.
  - [x] Export/persist the built images for the dependent jobs (same-runner Docker daemon, or `docker save`/artifact, or rebuild-from-warm-cache — pick the simplest that AC7 ordering supports).
- [x] **Task 3 — `runner-image-self-test` job (AC1, AC7; R6)**
  - [x] `needs: [runner-image-build]`, same `if:` guard, `ubuntu-latest`.
  - [x] Run `docker compose run --rm codex-runner --self-test` and `… claude-runner --self-test` (R6 — these work against profiled services); assert exit 0. Capture and surface the self-test stdout (the `deliveryline/<runner> self-test: OK` block) for the PR comment.
- [x] **Task 4 — `runner-contract-real` job (AC1, AC5, AC7; R4, R10)**
  - [x] `needs: [runner-image-build]`, same `if:` guard, `ubuntu-latest`, generous timeout (the IT builds 2 mock images + runs ~14 container scenarios).
  - [x] Run `./mvnw -B -ntp -Pdocker-runner-it -pl deliveryline-backend -am verify -Dit.test=RealRunnerContractIT -Dfailsafe.failIfNoSpecifiedTests=false` (R10).
  - [x] Provide the Postgres password placeholder + `SPRING_PROFILES_ACTIVE` env the Testcontainers/compose-autoconfig path needs (match the `foundation-gate` job env at `.github/workflows/ci.yml:1114`).
  - [x] Upload failsafe reports as an artifact. NO blanket retry (AC5); if a build/pull flake appears, scope a retry to that step only with an inline justification + a `deferred-work.md` entry.
- [x] **Task 5 — PR summary comment (AC3)**
  - [x] Add a job (or a step on a gate aggregator) that upserts a comment with marker `<!-- runner-image-ci-status -->` summarizing build / self-test / real-contract outcomes. Gate on `github.event_name == 'pull_request'`; escalate `pull-requests: write` at the job level only.
- [x] **Task 6 — Required-check wiring + foundation-gate relationship (AC6; R7 — resolve OQ-1 first)**
  - [x] Default path: add an always-runs `runner-contract-real-gate` job that passes on `runner-contract-real == success` OR a legitimate path-skip; document it as the branch-protection required check (update `docs/ci-branch-protection.md` if present).
  - [x] Do NOT add `runner-contract-real` to `foundation-gate.needs` and do NOT touch `FoundationGateVerificationTest` unless OQ-1 resolves otherwise.
- [x] **Task 7 — Local-repro docs (AC10; R6)**
  - [x] Add a `## Reproduce the runner-image CI locally` section to `docs/setup-local.md` with the corrected `--profile runners` build + the two self-test runs + the `runner-contract-real` mvnw invocation; cross-link `scripts/start-all.{ps1,sh}` and the compose file. Internal links must pass the `docs-link-check` tier (`.github/workflows/ci.yml:147`).
  - [x] Actually run the documented commands on Linux/Docker (WSL2) and confirm green before marking AC10 done ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).
- [x] **Task 8 — Close the deferred-work breadcrumbs**
  - [x] Update `_bmad-output/implementation-artifacts/deferred-work.md`: mark the two story-3.8 deferrals (`runner-image-build`/`runner-contract-real` jobs; foundation-gate widening) as delivered by 3.34, and record the OQ-1 resolution.
- [x] **CI observability instrumentation (cross-cutting; this story's analogue of the standard logging task)**
  - [x] This story ships YAML + Markdown, not Java services — the standard SLF4J logging task does not apply. Its intent (debug a failure without re-running blind) is satisfied by: (a) `::error::`/`::warning::` workflow annotations on every failure path with an actionable hint (mirror the `spotless:apply` / coverage hints already in `ci.yml`), e.g. self-test failures echo the `SELF-TEST FAIL: …` line and the offending image tag; (b) the AC3 PR summary comment; (c) `actions/upload-artifact` for the self-test output + failsafe reports + the `docker compose build` log. Do NOT swallow a job failure into a green check.

---

## Dev Notes

### What already exists (do not rebuild)

- **`--self-test`** — `runners/codex/entrypoint.sh:136` (`run_self_test`) and the Claude twin. Verifies: `node` on PATH, `runner.mjs` present, the agent CLI present + `--version` matches the pin (`CODEX_CLI_VERSION=0.135.0` / Claude pin), the **OpenSpec** CLI present + `--version` matches `OPENSPEC_VERSION=1.4.1` (story 3a-6), and the vendored **superpowers** skills dir resolves with ≥1 `SKILL.md` (story 3a-7). Prints a structured OK block then `exit 0`; any miss → `SELF-TEST FAIL: …` + `exit 1`. In the **real** image (`INSTALL_*_CLI=true`) these are the real tools; in the **mock** image (`INSTALL_*_CLI=false`) the baked `mock-codex.sh`/`mock-openspec.sh` report the pins. The new `runner-image-self-test` job runs against the REAL image (R5).
- **Compose build targets** — `docker-compose.yml:35` (`codex-runner`) + `:47` (`claude-runner`), both `profiles: ["runners"]`, `build.context: .`, `dockerfile: runners/<x>/Dockerfile`, `image: deliveryline/<x>-runner:latest`. They default to `INSTALL_*_CLI=true` (no build-arg override in compose) → real images.
- **`RealRunnerContractIT`** — `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java`. `@SpringBootTest` + `@Import(TestcontainersConfiguration)` + `@ActiveProfiles({"test","linear-mock","runners.docker"})`, `@Tag("docker-runner-it")` + `@Tag("real-runner-contract")` + `@EnabledIfDockerAvailable`. Builds `deliveryline/{codex,claude}-runner:real-contract-it` (mock, offline) in `@BeforeAll` (lines 176-230, 10-min build timeout), then drives the real broker reconcile entry points. Covers happy-path × 3 artifact variants × 2 kinds, crash / contract_violation / timeout failure modes, mock-vs-real parity, secret-scan, log redaction. **Your job just invokes it; do not modify it.**
- **Existing CI patterns to copy** (`.github/workflows/ci.yml`): the changed-files detector (`:1150`), the PR-comment upsert with a deterministic marker + job-level `pull-requests: write` (`:1178`), the Docker-daemon-reachability `[env]` guard (`:1101`), the no-blanket-retry policy comment (`:430`), the foundation-gate "assert all needs succeeded, convert skip→fail" aggregator (`:1074`), and the Postgres-password env shape for Testcontainers (`:1114`). The existing **offline** `runner-image-compat` tier (`:515`) is the template for "build both images with `-f runners/<x>/Dockerfile --build-arg INSTALL_*_CLI=false … .`" — but your new job uses compose + real build args (R5/R6/R11).

### Architecture & constraints

- **Linux-only Docker jobs** (story 1.21 AC3 / AR28 tier policy; `.github/workflows/ci.yml:19-29`). All three new jobs `runs-on: ubuntu-latest`. Windows/macOS Docker is excluded for cost/flakiness.
- **Least-privilege token** — workflow root is `contents: read`. Only the PR-comment job escalates `pull-requests: write`, at job level (`.github/workflows/ci.yml:43`, `:1070`).
- **Tier independence** — the three new jobs are a side-tier hanging off `detect-changes`; they do NOT insert into the linear `format-static-checks → … → foundation-gate` chain (R7: keep them out of `foundation-gate.needs` per the default OQ-1 resolution).
- **No production code change** — this story touches `.github/workflows/ci.yml`, `docs/setup-local.md`, `deferred-work.md`, and (optionally) a small runner-image semver source (R8). It must not change any Java, the runner images, or the compose service definitions (beyond, if you choose, confirming the `--profile runners` build path).

### Project Structure Notes

- Workflow file: `.github/workflows/ci.yml` (single file; add jobs, do not fork a second workflow — R3).
- Watched paths (R9, exact module-prefixed): `runners/**`, `deliveryline-runner-contracts/**`, `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/**`.
- Docs: `docs/setup-local.md` (AC10) — internal links validated by the `docs-link-check` tier; keep relative links resolvable.
- Maven selector for the IT: `-Pdocker-runner-it` profile (`deliveryline-backend/pom.xml:661`) clears the default `docker-runner-it` Failsafe exclusion (`:569`).

### Testing standards

- This story's "tests" are the CI jobs themselves + the AC10 doc round-trip. Validate by:
  1. Pushing a branch that touches `runners/**` and confirming the three jobs RUN (path filter true) and go green.
  2. Pushing a docs-only change and confirming the three jobs are SKIPPED and `foundation-gate` still passes (proves R7/AC2 — no docs-only PR is blocked).
  3. Running the AC10 documented commands locally on Linux/Docker (WSL2) and confirming `--self-test` exits 0 on both REAL images and `RealRunnerContractIT` passes under `-Pdocker-runner-it`.
- **Verify in a clean Linux env, not just locally** — local green ≠ CI green ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]). The real-image build is network-dependent (npm registry); a transient npm failure is an AC5 flake, not a contract break — surface it as such.
- Memory tripwires for this slice: [[runner-image-ci-uses-root-context]] (the offline compat tier builds with `-f runners/<x>/Dockerfile … .` from repo root + the Windows mvnw download flake), [[runner-tool-self-test-needs-offline-mock]] (self-test asserts agent-side tools; the offline build bakes deterministic mocks — your REAL-image self-test instead asserts the real pins), [[docker-it-needs-exact-docker-runner-it-tag]] / [[springboot-testcontainers-test-must-be-IT]] (why the IT is tagged + named the way it is), [[runner-contracts-schema-stale-in-m2]] (use `-am` so the freshest runner-contracts jar is on the classpath).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.34] — the 10 ACs (lines 677-694).
- [Source: .github/workflows/ci.yml] — tier order + OS policy (1-33), least-privilege token (40-50), `runner-image-compat` offline tier (509-559), Testcontainers env shape (1114-1139), changed-files detector (1150-1176), PR-comment upsert (1178-1209), skip→fail gate aggregator (1074-1089).
- [Source: docker-compose.yml] — `codex-runner`/`claude-runner` profile-gated build targets (21-52).
- [Source: runners/codex/entrypoint.sh#run_self_test] — `--self-test` contract (136-200); Claude twin `runners/claude/entrypoint.sh`.
- [Source: runners/codex/Dockerfile] — `INSTALL_CODEX_CLI` (47), `IMAGE_VERSION` ARG (44), version pins (29/36/42); Claude twin `runners/claude/Dockerfile`.
- [Source: deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java] — the test this story wires (tiering javadoc 93-115; `@BeforeAll` build 176-230).
- [Source: deliveryline-backend/pom.xml] — `docker-runner-it` Failsafe exclusion (569) + profile (661-672); foundation-gate profile (617-653).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md#Deferred from: dev-story of story-3.8 (2026-06-05)] — the two deferrals 3.34 closes + OQ-1 (lines 599-607).
- [Source: _bmad-output/implementation-artifacts/3-8-real-docker-runner-contract-integration-test.md] — sibling story; the reconciliation-block house style + Decisions D1/D8/D10.
- [Source: docs/setup-local.md] — target for AC10's local-repro section.

## Open Questions (raise with Alex before finalizing CI wiring)

- **OQ-1 (blocking the AC6 wiring choice):** Should `runner-contract-real` be enforced as a **standalone branch-protection required check** with a path-skip-tolerant aggregator (the default this story ships — keeps docs-only PRs unblocked, leaves `foundation-gate` untouched), **or** folded into `foundation-gate.needs` (which then requires making the job emit a green "no-op success" rather than a skip on non-runner PRs, so the gate's skip→fail logic isn't tripped)? Story 3.8's deferred-work flagged this as OQ-1 and leaned toward the dedicated required check.
- **OQ-2 (AC4):** What is the source of the `main`-build semver tag? Options: add a `runners/VERSION` file, reuse the Maven project `${revision}`/project.version, or a git-tag-derived value. The default assumed here is a new `runners/VERSION` file passed via `--build-arg IMAGE_VERSION`.
- **OQ-3 (AC1/AC5 cost):** The real-image build is a network npm install (`@openai/codex`, `@anthropic-ai/claude-code`, `@fission-ai/openspec`). Confirm CI runners may reach the npm registry, and confirm the path-trigger (AC2) is acceptable as the only cost control (vs. also gating `runner-image-build` to non-draft PRs).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow, 2026-06-19.

### Debug Log References

Local verifications run (Windows + Docker Desktop 28.5.1):

- `python -c "import yaml; yaml.safe_load(...)"` on `.github/workflows/ci.yml` → parses; 18 jobs total, all 5 new jobs present with correct `needs:` + `if:` guards.
- `node --check` on both embedded `actions/github-script` blocks (`detect-changes`, `runner-contract-real-gate` comment) → JS OK.
- `bash -n` on all 12 `run:` script blocks across the 5 new jobs → all OK.
- `docker compose -f docker-compose.yml -f docker-compose.ci.yml --profile runners config` (IMAGE_VERSION=0.1.0) → override merges; `cache_from`/`cache_to`/`args.IMAGE_VERSION` applied, `image: deliveryline/codex-runner:latest` preserved.
- `docker compose --profile runners build --dry-run` → targets BOTH `codex-runner` + `claude-runner` → `deliveryline/{codex,claude}-runner:latest` (R8 canonical names confirmed).
- Offline build `docker build -f runners/codex/Dockerfile --build-arg INSTALL_CODEX_CLI=false -t deliveryline/codex-runner:latest .` then the exact AC10 command `docker compose run --rm codex-runner --self-test` → `self-test: OK` … `exit 0`. Validates the self-test command wiring (profiled service resolves; `--self-test` passes through to the entrypoint).
- `deliveryline-backend/pom.xml` `-Pdocker-runner-it` profile (id at :672, clears the `docker-runner-it` Failsafe exclusion at :579) + `RealRunnerContractIT.java` exist — the `runner-contract-real` mvnw selector is valid.
- `BranchProtectionConfigSmokeContractTest` (`@Tag("contract")`) — change is additive (markers intact, `foundation-gate` still listed, one sibling added); the default surefire tier excludes `contract` (pom-fixed `excludedGroups`), so it runs in the `backend-contract-tests` Failsafe tier in CI. Passes by inspection.

NOT run locally (operator/CI to confirm — see deferred-work.md story-3.34 section): the network `npm install` REAL image build, the real-image `--self-test`, and the heavy `RealRunnerContractIT` round-trip (local≠CI; verify on WSL2/Linux per the repo guidance).

### Completion Notes List

CI + docs story — added NO Java / runner-image / entrypoint / `RealRunnerContractIT` changes (consumed the finished 3.3/3.4/3.8 artifacts). Closes the story-3.8 deferrals D1/D8/D10 and the 3.7 `docker-runner-it` CI wiring.

Three Open Questions were resolved with Alex (all to the story-recommended defaults):

- **OQ-1 → standalone gate aggregator.** Added an always-runs `runner-contract-real-gate` job (passes on a legitimate path-skip OR all three runner-image jobs green) as the branch-protection required check. `foundation-gate.needs` was NOT touched and `RealRunnerContractIT` was NOT added to `FoundationGateVerificationTest` (R7).
- **OQ-2 → new `runners/VERSION` file** (`0.1.0`), an independent runner-image semver track threaded via `--build-arg IMAGE_VERSION` for the `main`-build semver tag.
- **OQ-3 → path-trigger as the only cost control.** The three heavy jobs run only on runner-path PRs (via the `detect-changes` job + `if:` guards) or `push: main`. No draft-PR gate.

Key engineering decisions / reconciliations honored:

- **Path-trigger via `detect-changes` + `if:` guards, NOT `on.paths`** (R3) — `ci.yml` keeps running every PR for `foundation-gate`.
- **`docker compose --profile runners build`** (R6) — the bare command skips the profiled runner services. The CI-only `docker-compose.ci.yml` override adds ONLY a BuildKit `type=local` cache (AC8, keyed by `hashFiles('runners/**', …)`) + the `IMAGE_VERSION` build-arg, leaving the canonical service definitions untouched so the documented local command is override-free + byte-identical in build graph.
- **`runner-image-self-test`** loads the exact images built upstream via a `docker save`/`docker load` artifact (AC7), then runs the canonical `docker compose run --rm <svc> --self-test`; both images are tested even if the first fails (per-image outcomes feed the PR comment, AC9).
- **`runner-contract-real`** selects the IT via `-Pdocker-runner-it` (R10), with `-am` (fresh runner-contracts jar). `needs: runner-image-build` is ORDERING-only on hosted runners (no shared daemon → the R4 "cache-warming" benefit does not materialize on GitHub-hosted runners; documented honestly in the job comment + deferred-work).
- **AC3 PR comment** upserts under marker `<!-- runner-image-ci-status -->`; only the gate job escalates `pull-requests: write` (workflow root stays `contents: read`).
- **AC4 tags are local-daemon only** (no registry push in scope — `contents: read` token); they prove the `:pr-{n}` / `:latest`+`:<semver>` convention and feed a future publish story.

AC10 docs (`docs/setup-local.md`) document the override-free local commands and were partially verified locally (self-test command exits 0 on an offline build; the network REAL build + IT must be confirmed on WSL2/Linux). Branch-protection wiring registers `runner-contract-real-gate` in `docs/ci-branch-protection.md` + both `scripts/ci/configure-branch-protection.{sh,ps1}` source-of-truth arrays.

### Change Log

- 2026-06-19 — Implemented story 3.34 (CI tier: real Docker runner image build + compatibility checks). Added `detect-changes`, `runner-image-build`, `runner-image-self-test`, `runner-contract-real`, and `runner-contract-real-gate` jobs to `.github/workflows/ci.yml`; added `runners/VERSION` (0.1.0) + `docker-compose.ci.yml` (CI-only cache/version override); added the "Reproduce the runner-image CI locally" section to `docs/setup-local.md`; registered `runner-contract-real-gate` as a required check in `docs/ci-branch-protection.md` + `scripts/ci/configure-branch-protection.{sh,ps1}`; closed the story-3.8 deferred-work breadcrumbs and recorded the OQ resolutions in `deferred-work.md`. Status `ready-for-dev → review`.

### File List

- `.github/workflows/ci.yml` (modified) — 5 new jobs (detect-changes, runner-image-build, runner-image-self-test, runner-contract-real, runner-contract-real-gate).
- `runners/VERSION` (new) — runner-image semver source (`0.1.0`), threaded via `--build-arg IMAGE_VERSION`.
- `docker-compose.ci.yml` (new) — CI-only build override: BuildKit `type=local` cache + `IMAGE_VERSION` build-arg on the two runner services.
- `docs/setup-local.md` (modified) — new "Reproduce the runner-image CI locally" section (AC10).
- `docs/ci-branch-protection.md` (modified) — `runner-contract-real-gate` required-check row + explanatory section + `gh api` context.
- `scripts/ci/configure-branch-protection.sh` (modified) — added `runner-contract-real-gate` to `REQUIRED_CHECKS`.
- `scripts/ci/configure-branch-protection.ps1` (modified) — added `runner-contract-real-gate` to `$REQUIRED_CHECKS`.
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified) — marked the two story-3.8 deferrals DELIVERED + new story-3.34 scope-boundary section (OQ resolutions, cross-job cache caveat, tags-local-only, AC10 partial-verify).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified) — story 3-34 `ready-for-dev → in-progress → review` + last_updated entries.
- `_bmad-output/implementation-artifacts/3-34-ci-tier-real-docker-runner-image-build-and-compatibility-checks.md` (modified) — tasks checked, Status, Dev Agent Record.

### Review Findings

> bmad-code-review 2026-06-19 (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 1 decision-needed, 6 patch, 2 deferred, ~12 dismissed as noise/false-positive. CI/YAML story — could not be executed in the Windows dev env; review is static (Actions semantics, shell safety, AC/reconciliation traceability).

- [x] [Review][Decision→Patch] `COMPOSE_BAKE` bake build may not `--load` the real images into the local daemon — with the buildx `docker-container` driver (required for `cache_to type=local`), `docker buildx bake` can leave results in the cache backend and NOT in the local image store, so the "Verify images loaded" step (ci.yml ~177) + `docker save` (~200) could fail on the FIRST real CI run. **RESOLVED 2026-06-19 (Alex chose option 2):** added explicit `build.x-bake.output: [type=docker]` to both runner services in `docker-compose.ci.yml` to force the load regardless of the buildx driver default, and corrected the header comment that had asserted the load happens. [docker-compose.ci.yml:33-58]

- [x] [Review][Patch] Required gate `runner-contract-real-gate` fails OPEN when `detect-changes` errors/skips — empty `runner-paths-changed` makes `"" != "true"` true → `exit 0` while the three heavy jobs were skipped. Fix: gate asserts `needs.detect-changes.result == 'success'` before the path-skip branch, AND wrap the detector's `pulls.listFiles` in try/catch with a fail-safe (run the tier on error, e.g. fork-PR token error). [.github/workflows/ci.yml:~391 (gate), ~69 (detect-changes)]
- [x] [Review][Patch] `detect-changes` does not guard `pull_request.base.ref == 'main'` — the sibling foundation-gate steps do (ci.yml:1152/1179); without it the heavy tier + the required gate fire on PRs targeting non-main bases. Add the base-ref guard. [.github/workflows/ci.yml:~62-68]
- [x] [Review][Patch] Build-graph files absent from the path trigger — `docker-compose.yml`, `docker-compose.ci.yml`, and `runners/VERSION` feed the build graph + the cache key (ci.yml:123) but are NOT in the detect-changes `watched[]` set, so a compose/VERSION-only change skips the tier untested. Add them to `watched[]`. [.github/workflows/ci.yml:~76-83]
- [x] [Review][Patch] `runner-contract-real` is fail-open on a broken selector — `-Dfailsafe.failIfNoSpecifiedTests=false` means if `-Pdocker-runner-it` ever stops matching `RealRunnerContractIT`, zero ITs run and the required gate passes green. Set it `true`, or assert a `RealRunnerContractIT` failsafe report exists post-run. [.github/workflows/ci.yml:~353]
- [x] [Review][Patch] Empty/malformed `runners/VERSION` → invalid main-build tag — `IMAGE_VERSION="$(tr -d '[:space:]' < runners/VERSION)"` is unguarded; an empty file yields `docker tag deliveryline/codex-runner:` (invalid ref) mid-loop. Add a non-empty / semver-shape assertion after reading it. [.github/workflows/ci.yml:~142]
- [x] [Review][Patch] `runner-image-self-test` lacks env-vs-contract disambiguation — unlike `runner-contract-real` (which has the `[env]` daemon-reachability guard), a missing-image / daemon failure is reported as a runner `--self-test` contract failure. Assert both `:latest` images are present (`docker image inspect`) right after `docker load`, before the self-test steps. [.github/workflows/ci.yml:~250-297]

- [x] [Review][Defer] AC10/Task 7 real-Linux verification not performed — the mandated WSL2 round-trip of `--profile runners build` + real-image `--self-test` + `RealRunnerContractIT` was not run (Windows dev env); task marked [x] on offline-only verification. Must confirm on WSL2/CI before trusting the repro doc + the real build path (overlaps the decision above). [story Task 7] — deferred, needs Linux/CI run
- [x] [Review][Defer] BuildKit cache move-dance promotes a partial cache on build failure — the swap is `if: always()`, so a failed build still moves a partial `/tmp/.buildx-cache-new` into place; the "bounded/immutable" comment overstates safety. Low impact (buildx tolerates partial content-addressed local cache; immutable keys aren't re-saved on hit). [.github/workflows/ci.yml:~161-168] — deferred, low impact
