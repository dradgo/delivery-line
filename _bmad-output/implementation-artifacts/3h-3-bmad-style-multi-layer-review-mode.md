# Story 3h.3: BMAD-Style Multi-Layer Review Mode (dedicated `-bmad` runner images)

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want a deeper adversarial review option I can select per project by pointing the reviewer at a dedicated BMAD-equipped runner,
so that high-risk changes get Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor scrutiny instead of a single review pass — without losing the cheap single-pass mode and without polluting the existing runner images.

Delivers **FR77**.

---

## 🏛️ PRODUCT-OWNER ARCHITECTURE DECISION — read first (amends the epic AC wording)

The epic AC1 framed BMAD as *"`reviewer_model_kind` gains a `bmad` mode"* running on the existing codex/claude images. **The product owner (Alex) has chosen a different, cleaner shape:** BMAD is delivered as **two new dedicated reviewer runner images** — `codex-bmad` and `claude-bmad` — each = the corresponding base agent (codex / claude) **+ superpowers + the BMAD toolset**, and **explicitly WITHOUT openspec**. These are selected as **first-class `RunnerKind` values** (`CODEX_BMAD` / `CLAUDE_BMAD`) via the existing `reviewerModelKind` field — *"maybe it can be another runner later"* is satisfied now: they **are** their own runners. The existing `codex` / `claude` images (which carry openspec + superpowers) are **untouched**.

**Why this is cleaner than the mode-flag reading (and what it deletes vs. adds):**
- **Deletes** from the naïve plan: no new `projects.bmadReviewEnabled` column, no per-project config stack, no `context-bundle.v1` `bmadReviewRequested` flag, no bundle-flag resolver plumbing. **The image IS the mode.**
- **Adds:** two new `RunnerKind` values (+ image-tags + secret-env-names), two new runner images (drop openspec, add bmad), and CI/compose wiring for them.
- **The verdict plumbing is unchanged** from either reading: additive `review-result.v1` `layers[]`, `step_reviews.review_findings jsonb`, harvester widened, read-leg + OpenAPI/`schema.d.ts` regen, advisory-only / degrade-not-5xx.
- **How the image knows to review multi-layer:** a baked `ENV DELIVERYLINE_RUNNER_BMAD=true` in the two new Dockerfiles. The **shared** `entrypoint.sh` + `lib/runner.mjs` (reused by both the base and the `-bmad` image) gate the multi-layer review arm on that env (mirroring the existing `openspec_enabled()` gate) — existing images never set it → byte-identical single-pass.

Record this in ADR 0030 decision (e). This is the same class of product-owner amendment as 3h-1's "BUILD runs backend-side" (which amended ADR 0030 decision (a)).

---

## 🧭 ARCHITECTURE DECISIONS — read before coding (firm; grounded in the live bindings)

**Decision 1 — Two reviewer-only `RunnerKind` values; NO `runner_kind` CHECK widening.** Add `CODEX_BMAD("codex-bmad")` + `CLAUDE_BMAD("claude-bmad")` to `RunnerKind`. **Verified:** no drift/contract test asserts set-equality between the `runner_kind` producer CHECKs and the `RunnerKind` registry — `RegistryContractTest` only auto-derives `DomainRegistry.runnerKinds()` from `RunnerKind.values()` (`RegistryContractTest:118`, auto-passes on enum growth) and `FlywaySchemaContractTest` only substring-`contains`-checks codex/claude/manual (`:1257-1259`). There is **no `runnerKinds` array** in `registry-api-schema-placeholders.json` and **no RunnerKind pin test**. So the producer CHECKs `ck_projects_runner_kind` (V20) + `ck_project_runner_kinds_kind` (V26) **stay `in ('codex','claude','manual')`** — and their narrowness is exactly what enforces "bmad kinds are reviewer-only" at the DB layer. **No Flyway migration for the enum growth.**
[Source: RunnerKind.java:15-25; RegistryContractTest.java:118; FlywaySchemaContractTest.java:1257-1259,1290-1294; V20/V26 migrations; registry-api-schema-placeholders.json (no runnerKinds)]

**Decision 2 — `reviewerModelKind` (CHECK-free) accepts the new kinds with zero projects-schema change.** `reviewer_model_kind` is nullable opaque text with **no CHECK** (`FlywaySchemaContractTest:867-874` asserts the CHECK's absence). `ProjectRuntimeConfigResolver.resolveReviewerKind` (`:281-308`) and `ProjectManagementService.parseReviewerModelKind` (`:364-379`) both just `RunnerKind.fromValue(...)` and reject only `MANUAL` — so `"codex-bmad"` / `"claude-bmad"` become resolvable reviewers the instant the enum values exist, **no persistence/DTO/mapper change**. Reviewer selection is edit-only (`UpdateProjectRequest`), exactly as today.
[Source: V19...sql:24; FlywaySchemaContractTest.java:867-874; ProjectRuntimeConfigResolver.java:281-308; ProjectManagementService.java:364-379]

**Decision 3 — Producer-exclusion guard (the inverse of MANUAL-reviewer exclusion).** MANUAL is rejected as a *reviewer*; symmetrically, `CODEX_BMAD`/`CLAUDE_BMAD` must be rejected as *producers*. Add guards in `ProjectManagementService.parseRunnerKind` (`:323`, the `projects.runner_kind` override on create+update) **and** `parseStepRunnerKinds` (`:397`, the per-step `project_runner_kinds` map) rejecting the two bmad kinds with `INVALID_COMMAND_PAYLOAD` (mirror the MANUAL-reviewer rejection at `:369-377`). Optional defense-in-depth: `ProjectRuntimeConfigResolver.resolveRunnerKind` (`:197`). The narrow DB CHECKs (Decision 1) are the free backstop. **Leave** `parseReviewerModelKind`/`resolveReviewerKind` accepting them.
[Source: ProjectManagementService.java:323-325,364-401; ProjectRuntimeConfigResolver.java:197-244]

**Decision 4 — Mandatory `RunnerProperties` wiring (else startup/tests throw).** The `RunnerProperties.Docker` compact-ctor **requires a non-blank image tag for EVERY non-MANUAL `RunnerKind`** (`:653-668`, throws `IllegalArgumentException` at bind time). So the two new kinds **must** get `image-tags` entries in **both** `src/main/resources/application.yml` (`:373-376`) **and** `src/test/resources/application.yml` (`:136-139`), plus `RunnerProperties.Docker.defaults()` (`:710-713`). They also need secret-env-var names in `RunnerProperties.defaultSecretEnvNames()` (`:354-359`) — reuse the base kind's names (`codex-bmad` → `CODEX_AUTH_JSON…`, `claude-bmad` → `CLAUDE_CODE_OAUTH_TOKEN`/`ANTHROPIC_API_KEY`) — else the per-project reviewer-credential path (`DockerRunnerAdapter:715-719`) and the doctor runner-secret probe (`DoctorProbeAdapter:748`, iterates `RunnerKind.values()`, skips only MANUAL) throw `DOCTOR_RUNNER_SECRET_MISSING`.
[Source: RunnerProperties.java:653-668,710-713,354-359,376-383; application.yml:373-376; test application.yml:136-139; DockerRunnerAdapter.java:235-237,681-728; DoctorProbeAdapter.java:748-753]

**Decision 5 — The two new images REUSE the shared `entrypoint.sh` + `runner.mjs`; only the Dockerfile differs.** `entrypoint.sh` and `lib/runner.mjs` are per-image **COPY'd from the build context (repo root)**, not symlinked — but a Dockerfile can COPY from **any** path. So `runners/codex-bmad/Dockerfile` COPYs `runners/codex/entrypoint.sh` + `runners/codex/lib/runner.mjs` + `runners/codex/test/mock-codex.sh` (claude-bmad from `runners/claude/...`). This means the multi-layer review logic lives **once** in the shared `runner.mjs` review arm (gated on `DELIVERYLINE_RUNNER_BMAD`), consumed identically by codex and codex-bmad — **no forked runner.mjs to keep in sync** (honors the `RUNNER_CONTRACT.md` change-rule automatically). The `-bmad` Dockerfile = base Dockerfile **minus** the openspec lines (npm `@fission-ai/openspec` + `mock-openspec.sh` COPY/install + `OPENSPEC_VERSION` ARG/ENV) **plus** the BMAD toolset (installed via `npx bmad-method install`, **real-branch only** — see Task 2) **plus** `ENV DELIVERYLINE_RUNNER_BMAD=true` (both branches). Superpowers stays (its COPY/move/symlink lines).
[Source: codex/Dockerfile:68-72,78,100-131; claude/Dockerfile:71-74,81,103-134; runners/vendor/superpowers/**; RUNNER_CONTRACT.md:10-16,151-171]

**Decision 6 — Extend `review-result.v1` ADDITIVELY; the image env drives the branch.** Add optional `reviewMode` + `layers[]` to `review-result.v1.schema.json` (schemaVersion stays `const 1`, `additionalProperties:false` kept — the 3g-5 `usage` additive precedent). The shared `runner.mjs` review arm branches: `split-proposal` (existing, first) → **bmad** (`process.env.DELIVERYLINE_RUNNER_BMAD === 'true'`, emits `layers[]`) → single-pass (existing). Single-pass omitting `layers` stays **byte-identical**. **NO `context-bundle.v1` change** (mode is the image env, not a bundle field). The `runner-contracts` install trap applies (rebuild/install the jar or the backend validates the stale `.m2` schema — [runner-contracts-schema-stale-in-m2]).
[Source: review-result.v1.schema.json:65-86; runner.mjs codex:749-793 / claude:780-825; entrypoint.sh openspec_enabled() gate :89-90,592]

**Decision 7 — ONE reviewer execution, ONE richer verdict; persist as `step_reviews.review_findings jsonb`.** BMAD runs as a single `RunnerStage.REVIEW` dispatch (one `runner_executions` row, one `review:<producerExecId>` key) emitting one `review-result.v1` with `layers[]` — preserving the V21 `uq_step_reviews_runner_execution` (do NOT fan out three rows). `step_reviews` has no payload column, so add a nullable `review_findings jsonb` column (mirrors 3h-2 Decision-5 `lint_findings jsonb`). `outcome`/`rationale`/identity columns stay authoritative; `review_findings` is `null` for single-pass.
[Source: V19...sql:40-62; V21 uq_step_reviews_runner_execution; 3h-2 Decision 5]

**Decision 8 — advisory-only / degrade-not-5xx INHERITED; the `layers` parse is TOLERANT.** BMAD rides the same `ReviewResultHarvester.harvest()` degrade envelope (`:137-424`): malformed/failed bmad review → recorded non-fatal, tail proceeds, no 5xx, no stranded RUNNING. Malformed `layers` + valid top-level `outcome` ⇒ persist the aggregate verdict with `review_findings=null` (partial degrade); missing/invalid `outcome` ⇒ "no verdict" as today. The harvester stays total.
[Source: ReviewResultHarvester.java:137-193,315-424]

**Decision 9 — NO other foundation-registry entries.** No new `WorkflowState`/`AllowedAction`/`WorkflowEventType`/`FailureCategory`/`RunnerStage`/`ValidationTarget`. The ONLY foundation touch-points: `RunnerKind` +2 (auto-mirrored, no CHECK), one additive Flyway column (`step_reviews.review_findings jsonb`), the additive review-result schema field, and the additive OpenAPI `ReviewerVerdict.findings`. Review remains advisory-only (no governed action on the verdict panel).

---

## Context — why this story exists

Today the advisory reviewer (3d-2) is a **single pass** on the codex/claude image: emits a flat `review-result.v1` (one `outcome` + one `rationale`, driven by the last `VERDICT:` marker) → `ReviewResultHarvester` writes one `step_reviews` row → the FE Verdict Panel renders it. No categorization, no multi-layer scrutiny, and no way to run a heavier review methodology without bloating the standard images.

This story adds **BMAD as its own reviewer runner** — two dedicated images (`codex-bmad`, `claude-bmad`) carrying **superpowers + the BMAD toolset** (not openspec), selected as `RunnerKind` reviewer values via `reviewerModelKind`. When a project points its reviewer at a `-bmad` kind, the REVIEW stage runs the **multi-layer adversarial review** (Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor) and emits a **richer, categorized** `review-result.v1` (`layers[]`). Single-pass on the standard images stays fully available and byte-identical — this **augments, does not replace** (AC1). The richer payload is persisted (jsonb) and the contract regenerated (OpenAPI + `schema.d.ts`); the FE rendering of the multi-layer findings is **deferred to 3h-6** — 3h-3 owns backend + images + contract + regen so 3h-6 has a typed surface.

---

## Acceptance Criteria

1. **Two dedicated `-bmad` reviewer images + two reviewer-only `RunnerKind` values (augment, not replace).** New `RunnerKind.CODEX_BMAD("codex-bmad")` + `CLAUDE_BMAD("claude-bmad")` and two new runner images (`deliveryline/codex-bmad-runner`, `deliveryline/claude-bmad-runner`) = base agent + superpowers + BMAD toolset, **without openspec**. Existing `codex`/`claude` images + single-pass review are **untouched / byte-identical**. The bmad kinds are **reviewer-only** — rejected as producer / per-step runner kinds (Decision 3), enforced in the app layer + backstopped by the unchanged narrow `runner_kind` DB CHECKs (Decision 1, **no CHECK migration**).

2. **Selection via `reviewerModelKind` with no projects-schema change.** A project selects a bmad reviewer by setting `reviewerModelKind = "codex-bmad"` / `"claude-bmad"` (edit-only, `UpdateProjectRequest`). `resolveReviewerKind` returns the bmad kind (Decision 2); `DockerRunnerAdapter` dispatches the matching `-bmad` image (image-tags + secret-env wired per Decision 4). No `projects` column, no CHECK, no DTO/mapper change beyond the kinds existing.

3. **The bmad image emits a richer, additive `review-result` (both flavors + both offline mocks).** The `-bmad` image's shared `runner.mjs` review arm (gated on the baked `DELIVERYLINE_RUNNER_BMAD=true` env, Decision 5/6) emits a `review-result.v1` with `reviewMode:"bmad"` + `layers[]` (categorized multi-layer findings, per-layer outcome, aggregated into the existing top-level `outcome`) via **additive** optional schema properties (`schemaVersion` stays `const 1`; single-pass omits `layers` → **byte-identical**). Both flavors (codex-bmad + claude-bmad) and **both offline mocks** support it; the mocks emit a **deterministic multi-layer verdict** under the env. Nothing bypasses the existing redaction posture. The `runner-contracts` install trap applies.

4. **Advisory-only / degrade-not-5xx.** A failed or malformed bmad review degrades to a recorded non-fatal verdict and the tail proceeds — never strands RUNNING, never 5xx (Decision 8). Malformed `layers` + valid `outcome` ⇒ aggregate persisted, `review_findings=null`.

5. **Harvester + store widened without breaking single-pass.** `step_reviews` gains a nullable `review_findings jsonb` column (Decision 7); `ReviewResultHarvester` persists the redacted multi-layer payload into it (single-pass writes `null`). The harvester stays total.

6. **Provenance preserved per layer.** Each layer entry in `review_findings` carries the reviewing + producing model identity (derived by the backend; all layers share the one `-bmad` reviewer image), and the top-level scalar `reviewer_model_identity`/`producer_model_identity` + derived `selfReview` stay populated as today (the reviewer identity now reads e.g. `codex-bmad:<tag>`). FE render is 3h-6.

7. **OpenAPI + `schema.d.ts` regen (owned here).** An additive nullable `findings` (multi-layer) field on the `ReviewerVerdict` response DTO → regenerate `openapi.json` + `schema.d.ts` (`npm run generate-api`; NOT byte-identical; `check:api` in-sync). Existing single-pass FE consumers keep compiling.

8. **Tests + CI image build.** Coverage asserts: the two new RunnerKinds resolvable as reviewers + **rejected as producers**; image-tags/secret-env wiring (`RunnerProperties` ctor accepts the new kinds; doctor probe green); bmad image emits the multi-layer verdict + harvester persists it into `review_findings`; **single-pass parity** (verdict + emitted review-result byte-identical; `review_findings=null`); degrade-not-5xx; runner fence/payload over **both** shared runner.mjs + **both** mocks; the additive Flyway column + replay in `FlywaySchemaContractTest`; OpenAPI/`schema.d.ts` regen drift; **CI builds the two new images** (compat + build tiers). `application.*` ≥ 80% line coverage. ArchUnit via **Failsafe**.

---

## Tasks / Subtasks

- [ ] **Task 1 — `RunnerKind` +2 reviewer-only values + `RunnerProperties`/doctor wiring** (AC: #1, #2, #8)
  - [ ] `domain/registry/RunnerKind.java:16-25`: add `CODEX_BMAD("codex-bmad")`, `CLAUDE_BMAD("claude-bmad")` (mind the trailing `;`). `DomainRegistry.runnerKinds()` auto-mirrors — **no** registry edit; confirm `RegistryContractTest:118` stays green.
  - [ ] `application/runner/RunnerProperties.java`: add `image-tags` entries for both kinds in `Docker.defaults()` (`:710-713`, e.g. `deliveryline/codex-bmad-runner:latest`); the compact-ctor (`:653-668`) requires them for every non-MANUAL kind. Add secret-env-var names in `defaultSecretEnvNames()` (`:354-359`): `CODEX_BMAD` → the CODEX names, `CLAUDE_BMAD` → the CLAUDE names (reviewers reuse the base agent's credential).
  - [ ] `src/main/resources/application.yml:373-376` **and** `src/test/resources/application.yml:136-139`: add `codex-bmad:`/`claude-bmad:` under `docker.image-tags` (test can reuse `alpine:3.20`). [validated-config-needs-test-yaml — this IS a validated @ConfigurationProperties, so the test yaml MUST be updated or `@SpringBootTest` fails at bind time.]
  - [ ] **Producer-exclusion guard (Decision 3):** reject `CODEX_BMAD`/`CLAUDE_BMAD` in `ProjectManagementService.parseRunnerKind` (`:323`) + `parseStepRunnerKinds` (`:397`) with `INVALID_COMMAND_PAYLOAD` (mirror the MANUAL-reviewer rejection `:369-377`); optional guard in `ProjectRuntimeConfigResolver.resolveRunnerKind` (`:197`). Leave the reviewer parse paths accepting them.
  - [ ] `DoctorProbeAdapter` (`:748-753`): the runner-secret probe now iterates the 2 new kinds — confirm they resolve secret env names (from the `defaultSecretEnvNames` addition) so the probe passes when the base agent's host secret is configured. **Verify** whether `checksRun` count changes (`DoctorLoggingContractTest` hardcoded literal) — the probe loops internally so it likely stays one check; if the count shifts, update the literal + stubs (the 3i-story doctor fan-out precedent).
  - [ ] Tests: `RunnerPropertiesTest` (ctor accepts the 2 kinds + tags), `ProjectRuntimeConfigResolverTest` (resolveReviewerKind accepts codex-bmad/claude-bmad; resolveRunnerKind rejects them if guarded), `ProjectManagementServiceTest` (reviewer accepts / producer rejects), `RunnerKind` parse round-trip, `RegistryContractTest` + `FlywaySchemaContractTest` green with **no** runner_kind CHECK edit.

- [ ] **Task 2 — Two new runner images (`codex-bmad`, `claude-bmad`)** (AC: #1, #3)
  - [ ] **Install the BMAD toolset via its official installer** — `npx bmad-method install` (https://docs.bmad-method.org/how-to/install-bmad/). BMAD is an **npx-driven installer** (NOT a vendored static tree like superpowers): it creates a `_bmad/` dir (module `bmm` + `config.toml` + `_config/manifest.yaml`) and integrates with the AI tool via `--tools`. Run it in the **REAL** Dockerfile branch RUN layer (network is available there, same layer as `npm install -g @openai/codex`), pinned + non-interactive: `npx bmad-method@${BMAD_VERSION} install --yes --modules bmm --tools <tool>` (add `ARG BMAD_VERSION`/`ENV BMAD_VERSION` mirroring the openspec pin at `:36/:57`). `--modules bmm` = the BMad Method core carrying the `bmad-code-review` (Blind/Edge/Auditor) workflow — the whole point; keeping to the **bundled** `bmm` avoids needing `git` in the image (external modules clone via git). `--tools`: `claude-code` for claude-bmad; for codex-bmad use the codex-compatible tool id if `bmad-method` supports one, else install the bundled `_bmad/` core and have the entrypoint bmad prompt reference the `_bmad/` methodology path (mirror how codex references `~/.agents/skills/`). Prereqs already met by `node:22-slim` (Node 20.12+). The **offline mock branch skips the install** (mirrors the real-CLI pattern — the mock emits the deterministic verdict; `DELIVERYLINE_RUNNER_BMAD=true` stays baked). Confirm the exact `--modules`/`--tools`/`--set` args + the pinned `BMAD_VERSION` at build time.
  - [ ] `runners/codex-bmad/Dockerfile` = a copy of `runners/codex/Dockerfile` with: **remove** the openspec lines (`ARG OPENSPEC_VERSION` `:36`, `ENV OPENSPEC_VERSION` `:57`, `COPY …/mock-openspec.sh` `:71`, the real `npm install @fission-ai/openspec` `:102`, the mock `install …/mock-openspec.sh` `:106`); **keep** the superpowers COPY/move/symlink (`:78,128-131`) + CLI install; **add** the BMAD install step in the REAL branch RUN layer (`npx bmad-method@${BMAD_VERSION} install --yes --modules bmm --tools …`, Task-2 installer subtask — skipped in the offline mock branch) + `ARG BMAD_VERSION`/`ENV BMAD_VERSION`; **add** `ENV DELIVERYLINE_RUNNER_BMAD=true` (both branches); **COPY** `runners/codex/entrypoint.sh` + `runners/codex/lib/runner.mjs` + `runners/codex/test/mock-codex.sh` (reuse, Decision 5). Update the Dockerfile header build-command comment.
  - [ ] `runners/claude-bmad/Dockerfile` = the same treatment of `runners/claude/Dockerfile` (COPY entrypoint/runner.mjs/mock from `runners/claude/...`, claude HOME paths, `CLAUDE_CODE_OAUTH_TOKEN`/`ANTHROPIC_API_KEY`).
  - [ ] `runners/codex-bmad/README.md` + `runners/claude-bmad/README.md` (mirror the base READMEs; note reviewer-only + the pinned `BMAD_VERSION` + the `npx bmad-method install` step + real-branch-only). Update `runners/RUNNER_CONTRACT.md` image lineup (`:4-8`) to list the 4 images + the reviewer-only note; confirm new vocabulary against `docs/glossary.md` (NFR43).
  - [ ] **Do not** create new `entrypoint.sh`/`runner.mjs`/mock copies under the `-bmad` dirs — they COPY from the base dirs (Decision 5).

- [ ] **Task 3 — Shared `entrypoint.sh` + `runner.mjs` bmad review arm (both base files → inherited by `-bmad`)** (AC: #3)
  - [ ] `runners/codex/entrypoint.sh` **and** `runners/claude/entrypoint.sh`: add a `bmad_enabled()` gate `[ "${DELIVERYLINE_RUNNER_BMAD:-}" = "true" ]` (mirror `openspec_enabled()` `:89-90`); in the review prompt block (`:550-585`), when `bmad_enabled`, select a **BMAD review PROMPT_INSTRUCTION** (Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor layer instructions + the fenced multi-layer output format the runner parses). Existing (env-unset) path unchanged.
  - [ ] `runners/codex/lib/runner.mjs` **and** `runners/claude/lib/runner.mjs`: in the `stage === 'review'` arm, insert a bmad branch **after** the split-proposal branch (`:749-765` codex / `:780-796` claude), **before** the plain-review branch: when `process.env.DELIVERYLINE_RUNNER_BMAD === 'true'`, parse the model's structured multi-layer output (mirror the fenced-block helpers `splitClarificationsFence`/`splitSplitProposalFence` `:462-522`/`:612-692`) into `layers[]`, compute top-level `outcome` = **worst layer outcome** (`fail` > `concern` > `pass`), emit `review-result.v1` with `reviewMode:"bmad"` + `layers[]` (plus the existing `outcome`/`rationale`/`summary`/`reviewerModelIdentity`/`classification`/`usage`). **Plain-text fallback mandatory** (3g-5 discipline): no parseable fence ⇒ degrade to a single synthetic layer / the flat `parseReviewOutcome` outcome, never crash, keep `truncateRationale`/`sanitizeUsage`. Keep the two files lock-step (only the `reviewerModelIdentity` string + header differ).
  - [ ] `runners/codex/test/mock-codex.sh` **and** `runners/claude/test/mock-claude.sh`: add a bmad branch keyed on `DELIVERYLINE_RUNNER_BMAD` (mirror the split branch) emitting a **deterministic** multi-layer fenced verdict (fixed 3 layers, fixed findings). Read only non-secret env (keep the negative-log assertion). These mocks are COPY'd into both the base and `-bmad` images.

- [ ] **Task 4 — Additive `review-result.v1` schema + rebuild contracts jar** (AC: #3)
  - [ ] `deliveryline-runner-contracts/src/main/resources/schemas/review-result.v1.schema.json`: add (keeping `additionalProperties:false`, `schemaVersion:const 1`):
    - `reviewMode`: optional `enum ["single","bmad", null]`.
    - `layers`: optional `["array","null"]`, `maxItems:3`, items = object (`additionalProperties:false`): `layer` (`enum ["blind_hunter","edge_case_hunter","acceptance_auditor"]`), `outcome` (`enum ["pass","concern","fail"]`), `summary` (nullable string), `reviewerModelIdentity`/`producerModelIdentity` (nullable string), `findings` (optional array, `maxItems:50`, items: `category` string, `severity` `enum ["blocker","high","medium","low","info"]`, `title` string `maxLength 512`, `detail` nullable string `maxLength 8000` [redacted by backend], `file` nullable string, `line` nullable integer). Bound every string so the payload stays under `MAX_REVIEW_RESULT_BYTES = 256 KiB`.
  - [ ] **NO `context-bundle.v1` change** (mode is the image env — Decision 6).
  - [ ] **Rebuild/install the contracts jar** (`mvn -pl deliveryline-runner-contracts install` or root `mvn install`/`-am`) before any backend validation test — confirm both `src/main/resources/schemas/…` and `target/classes/schemas/…` current. [runner-contracts-schema-stale-in-m2]

- [ ] **Task 5 — Widen `step_reviews` + `ReviewResultHarvester`** (AC: #4, #5, #6)
  - [ ] **Flyway (next-free head — re-confirm; V33 highest on-disk, V34 claimed by unmerged 3h-2):** `alter table step_reviews add column review_findings jsonb null;`. `FlywaySchemaContractTest` asserts the nullable jsonb column + replay. (This is the story's **only** migration — no projects column, Decision 2.)
  - [ ] `StepReviewEntity` (jsonb mapping — match the codebase's Spring Data JDBC jsonb converter), `StepReviewWritePort`(`NewStepReview` +`reviewFindingsJson`), `StepReviewWritePersistenceAdapter.insert`, `StepReviewSnapshot`, `StepReviewReadPort`/`StepReviewRepository.findLatestForRun` — thread the nullable jsonb (single-pass passes `null`).
  - [ ] `ReviewResultHarvester.harvest`: after the `outcome` parse (`:174-184`), parse optional `reviewMode`/`layers[]` (tolerant — Decision 8; malformed ⇒ `review_findings=null`, never throw). **Redact** each layer finding's `detail`/`summary` via `redactionPolicyService.redact(..., SHAREABLE_REDACTED)` (like `rationale` `:251-259`). **Provenance per layer:** stamp `resolveReviewerIdentity`/`resolveProducerIdentity` (`:427-472`) into each layer entry. Serialize redacted layers → `reviewFindingsJson`; pass via `NewStepReview` into the **existing** atomic `reviewVerdictTransactionTemplate` insert (`:282-326`) — no new tx/write. Keep the harvest inside the degrade envelope.

- [ ] **Task 6 — Read leg: additive `findings` on `ReviewerVerdict` + OpenAPI/`schema.d.ts` regen** (AC: #6, #7)
  - [ ] `WorkflowInspectionService.getReviewerVerdict` (`:274-384`) + `ReviewerVerdictView` (`:392-400`): add nullable `findings` sourced from `StepReviewSnapshot.reviewFindingsJson` (deserialize to a typed view); other fields unchanged.
  - [ ] `adapters/rest/ReviewerVerdictResponse.java`: add nullable `findings: List<ReviewLayerResponse>` (new nested `@Schema` records `ReviewLayerResponse`/`ReviewFindingResponse` — **typed, not `JsonNode`** [jackson2-jsonnode-dto-500s-under-boot4-jackson3]). Additive — single-pass returns `findings:null`.
  - [ ] Regenerate: `-Dopenapi.snapshot.write=true` via `OpenApiSnapshotContractTest` → `openapi.json`; `npm run generate-api` → `schema.d.ts`; commit both; `check:api` in-sync. [openapi-regen-frontend-client-drift-cascade] Confirm `useReviewerVerdict`/`ReviewerVerdictPanel.tsx` still compile (additive; no FE render change — that's 3h-6).

- [ ] **Task 7 — CI + compose wiring for the two new images** (AC: #8)
  - [ ] `docker-compose.yml` (`:35-52`): add `codex-bmad-runner` + `claude-bmad-runner` services (profile `runners`, `dockerfile: runners/codex-bmad/Dockerfile`, `image: deliveryline/codex-bmad-runner:latest`). `docker-compose.ci.yml` (`:42-66`): add the per-service cache_from/cache_to + IMAGE_VERSION blocks.
  - [ ] `.github/workflows/ci.yml`: (a) `runner-image-compat` (`:515-559`) — add two `docker build -f runners/{codex,claude}-bmad/Dockerfile --build-arg INSTALL_*_CLI=false -t deliveryline/runners-{…}-bmad:ci .`; (b) `runner-image-build` (`:1326-1462`) — extend the hardcoded `for runner in codex claude` / `deliveryline/codex-runner deliveryline/claude-runner` loops (`:1420,1430-1435,1443`) to include the bmad flavors; (c) `runner-image-self-test` (`:1507-1529`) — add the two `docker compose run --rm {…}-bmad-runner --self-test`. `detect-changes` already watches `runners/` (`:1304-1310`) so the new dirs auto-trigger.
  - [ ] `--self-test` on the bmad images asserts: `DELIVERYLINE_RUNNER_BMAD=true` is set + superpowers resolves + **openspec is absent** (both branches); and `_bmad/` present in the **real-CLI** build (skip that assertion in the offline mock build, where bmad isn't installed — mirror the real-CLI-is-real-branch-only pattern).

- [ ] **Task 8 — Docs (ADR + glossary + contract)** (AC: architecture)
  - [ ] `docs/adr/0030-governed-delivery-tail.md` decision (e): record BMAD as dedicated `-bmad` reviewer images + reviewer-only `RunnerKind`s (not a mode flag), image-env-driven multi-layer, additive `review-result.v1 layers[]`, `review_findings jsonb`, reviewer-only producer-exclusion, no CHECK change.
  - [ ] `docs/glossary.md`: `BMAD review` (Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor), `codex-bmad`/`claude-bmad` runner images (NFR43 — justify each new concept).
  - [ ] `runners/RUNNER_CONTRACT.md`: image lineup + reviewer-only note + the BMAD-installed-via-`npx bmad-method install` note (real-branch only, pinned `BMAD_VERSION`).

- [ ] **Task 9 — Tests** (AC: #8)
  - [ ] **Backend unit:** `ReviewResultHarvesterTest` (bmad → `review_findings` persisted redacted, per-layer provenance; single-pass → `null`; malformed `layers` + valid `outcome` → aggregate + `null`, no throw; secret in a finding `detail` redacted); `WorkflowInspectionServiceReviewerVerdictTest` (bmad surfaces `findings`; single-pass `null`); `RunnerPropertiesTest`, `ProjectRuntimeConfigResolverTest`, `ProjectManagementServiceTest` (reviewer-accept / producer-reject), `RunnerKind` parse.
  - [ ] **Runner JS/shell:** extend `runner-token-usage.test.mjs` review arm (both flavors) with a bmad case (`DELIVERYLINE_RUNNER_BMAD=true` → `reviewMode:'bmad'` + `layers[]` + aggregate outcome) + single-pass (env unset → `layers` absent, byte-identical); `entrypoint-review-directive.test.sh` (+ twin) asserts the bmad prompt branch under the env; a mock test asserts the deterministic multi-layer verdict (both mocks).
  - [ ] **Image/CI:** the `runner-image-compat` build of both `-bmad` images succeeds offline; `--self-test` asserts bmad present / openspec absent.
  - [ ] **Foundation-gate:** `FlywaySchemaContractTest` (review_findings column + replay; **no** runner_kind CHECK edit), `OpenApiSnapshotContractTest` (ReviewerVerdict.findings + nested schemas), `RegistryContractTest` (RunnerKind auto-mirror), `RunnerContractValidator` schema tests (bmad + single validate), `check:api` in-sync. ArchUnit via **Failsafe** (`verify -Djacoco.skip=true` — [maven-argline-direct-goal-crash]).
  - [ ] `application.*` ≥ 80% line coverage.
- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] SLF4J structured logs (parameterized): reviewer dispatch (INFO reviewer kind codex-bmad/claude-bmad vs codex/claude), bmad harvest (INFO "harvested bmad verdict layers={n} outcome={o}"), partial-degrade (WARN "bmad layers unparseable, persisting aggregate only"), redaction (no finding bytes). Levels: INFO lifecycle, WARN recoverable, ERROR only unexpected.
  - [ ] Every line carries `correlationId`, `workflowRunId`, reviewer `runnerExecutionId`. **Never log finding bodies / rationale / redacted detail** — ids/counts/outcome only. Pin new/moved lines with `OutputCaptureExtension`/`ListAppender` (mirror 3g-5's reviewer-usage pin).

---

## Dev Notes

### Implementation anchors — read first
- **RunnerKind is cheap to grow:** `RunnerKind.java:15-25`; the guard/CHECK story is Decision 1+3 (verified — no CHECK migration, producer-exclusion in `ProjectManagementService`). The mandatory wiring is Decision 4 (`RunnerProperties` image-tags + secret-env, both yaml files, doctor probe).
- **Image recipe:** base Dockerfile (`runners/codex/Dockerfile:100-131`, `claude:103-134`) minus openspec, plus BMAD (`npx bmad-method install`, real-branch only), plus `ENV DELIVERYLINE_RUNNER_BMAD=true`, reusing the base `entrypoint.sh`+`runner.mjs`+mock (Decision 5).
- **Mode gate precedent:** `openspec_enabled()` in `entrypoint.sh:89-90,592` — mirror as `bmad_enabled()`; `runner.mjs` reads `process.env.DELIVERYLINE_RUNNER_BMAD`.
- **Additive contract precedent:** `review-result.v1.schema.json:65-86` (`usage`, story 3g-5) — additive, schemaVersion unchanged, omitted-when-absent byte-identical.
- **jsonb-findings-on-existing-row precedent:** 3h-2 Decision 5 (`lint_findings jsonb`).
- **Harvest degrade envelope:** `ReviewResultHarvester.java:137-424` — extend inside it, reuse the existing verdict tx.

### Foundation footprint (light)
| Touch-point | Change |
|---|---|
| `RunnerKind` | **+2 values** (auto-mirrored; NO CHECK migration, Decision 1) |
| `runner_kind` producer CHECK | **none** (stays narrow = reviewer-only backstop) |
| `reviewer_model_kind` | **none** (CHECK-free, accepts new tokens, Decision 2) |
| WorkflowState/AllowedAction/EventType/FailureCategory/RunnerStage/ValidationTarget | **none** |
| Flyway | **1 migration, next-free head**: `step_reviews.review_findings jsonb null` |
| RunnerProperties + both application.yml | image-tags +2 + secret-env +2 (Decision 4 — MANDATORY) |
| runner-contracts | `review-result.v1` (+`reviewMode`,`layers[]`) — install trap; **no** context-bundle change |
| OpenAPI / schema.d.ts | additive `ReviewerVerdict.findings` → regen |
| Images / CI | +2 images (`codex-bmad`,`claude-bmad`) + compose + CI loops |

### Traps to carry
- **Validated config:** the `image-tags` map is a validated `@ConfigurationProperties` iterating all non-MANUAL kinds — **both** main + test `application.yml` MUST get the two new tags or `@SpringBootTest` throws at bind time. [validated-config-needs-test-yaml; RunnerProperties.java:653-668]
- **Doctor secret probe** iterates `RunnerKind.values()` — new kinds need secret-env names (reuse base) or the probe FAILs; verify `DoctorLoggingContractTest` `checksRun` literal.
- **runner-contracts install trap:** rebuild the jar before backend validation. [runner-contracts-schema-stale-in-m2]
- **OpenAPI regen cascade** + **Flyway head contested** (V33 highest; re-confirm) + **Testcontainers `*IT` non-`@Transactional`** + **ArchUnit in Failsafe** + **spotless:apply** + **typed DTO not JsonNode**.
- **RUNNER_CONTRACT change-rule:** editing the shared `entrypoint.sh`/`runner.mjs` (both codex+claude) keeps all 4 images in sync automatically (the `-bmad` images COPY the base files). Do NOT fork the runner.mjs.

### Redaction (AC3) — nothing new bypasses it
Bundle egress redaction unchanged (no bundle field added). On harvest, each layer finding's `detail`/`summary` passes the SAME `redactionPolicyService.redact(..., SHAREABLE_REDACTED)` as `rationale` before jsonb serialization. Runner-side `truncateRationale` bounds each detail (avoids mid-token cuts so backend regex still matches). Validator `SECRET_PATTERN` fires only for `shareable-full` (review is `shareable-redacted`), so the backend redact is authoritative.

### BMAD toolset install (RESOLVED — official npx installer)
BMAD is installed via its **official installer** (https://docs.bmad-method.org/how-to/install-bmad/), NOT vendored like superpowers: `npx bmad-method@${BMAD_VERSION} install --yes --modules bmm --tools <tool>` creates `_bmad/` (bmm core + `config.toml` + `_config/manifest.yaml`) in the image HOME and integrates with the AI tool. Run it in the **real** Dockerfile branch only (network at build; mirror `npm install -g @openai/codex`); the **offline mock branch skips it** (the mock CLI emits the deterministic multi-layer verdict, and `DELIVERYLINE_RUNNER_BMAD=true` is baked in both branches so the review arm stays multi-layer). Pin `BMAD_VERSION` for reproducibility. `bmm` carries the `bmad-code-review` (Blind/Edge/Auditor) workflow — this is the review methodology the bmad reviewer runs. Node 20.12+ is satisfied by `node:22-slim`; keep to bundled `--modules bmm` to avoid needing `git` in the image (external modules clone via git). Open sub-detail: the exact `--tools` id for the **codex** flavor (claude-bmad uses `claude-code`) — resolve at build; fall back to the bundled `_bmad/` core + an entrypoint-prompt path reference if codex has no formal tool integration.

### Reviewer-only invariant — why it holds three ways
1. App guard rejects bmad kinds in `parseRunnerKind`/`parseStepRunnerKinds` (Decision 3).
2. `resolveReviewerKind` accepts them (Decision 2) — they ARE valid reviewers.
3. DB backstop: `ck_projects_runner_kind`/`ck_project_runner_kinds_kind` stay `('codex','claude','manual')` — Postgres rejects `codex-bmad` in any producer column even if an app guard were missed (Decision 1).

### Testing standards summary
- Rebuild `runner-contracts` (`install`/`-am`) before backend contract-validation tests.
- afterCommit/REQUIRES_NEW ITs non-`@Transactional`, `*IT` (Failsafe); ArchUnit via Failsafe; `spotless:apply`; OpenAPI `-Dopenapi.snapshot.write=true` then `npm run generate-api`; re-confirm Flyway head.
- Verify the runner JS (Node test runner) + `.test.sh` harness actually run; verify the offline `-bmad` image build in the compat tier.

### Project Structure Notes
- **New:** `runners/codex-bmad/Dockerfile` + `README.md`, `runners/claude-bmad/Dockerfile` + `README.md` (BMAD installed via `npx bmad-method install` in the real branch — **no** vendored tree, **no** `runners/vendor/bmad`); `adapters/rest/ReviewLayerResponse.java` + `ReviewFindingResponse.java`.
- **Modified — backend:** `RunnerKind`; `RunnerProperties` (image-tags defaults + defaultSecretEnvNames); both `application.yml`; `ProjectManagementService` (producer-exclusion guards); `ProjectRuntimeConfigResolver` (optional producer guard); `StepReviewEntity`/`StepReviewWritePort`(`NewStepReview`)/`StepReviewWritePersistenceAdapter`/`StepReviewSnapshot`/`StepReviewReadPort`/`StepReviewRepository`; `ReviewResultHarvester`; `WorkflowInspectionService`(`ReviewerVerdictView`+`getReviewerVerdict`); `ReviewerVerdictResponse`; possibly `DoctorLoggingContractTest` (checksRun literal — verify).
- **Modified — runner (shared, both flavors inherit):** `runners/codex/{entrypoint.sh,lib/runner.mjs,test/mock-codex.sh}` + `runners/claude/{entrypoint.sh,lib/runner.mjs,test/mock-claude.sh}`; `runners/RUNNER_CONTRACT.md`.
- **Modified — contracts:** `review-result.v1.schema.json` (rebuild jar). **No** `context-bundle.v1` change.
- **Modified — generated/committed:** `openapi.json`, `schema.d.ts`.
- **Modified — build/CI:** `docker-compose.yml`, `docker-compose.ci.yml`, `.github/workflows/ci.yml`.
- **New Flyway (next-free head, re-confirm):** `step_reviews.review_findings jsonb null`.
- **NO** new WorkflowState/AllowedAction/WorkflowEventType/FailureCategory/RunnerStage/ValidationTarget; **NO** runner_kind CHECK migration; **NO** projects column; **NO** context-bundle change; **NO** FE component work (3h-6).

### References
- [Source: _bmad-output/planning-artifacts/epic-03h-pre-review-quality-gates.md — Story 3h-3 (AC1-8) + Cross-Cutting Notes + FR77 + ADR 0030 (e)]
- [Source: **PRODUCT-OWNER DECISION (Alex, 2026-07-06)** — BMAD delivered as dedicated `-bmad` reviewer images + reviewer-only RunnerKind, superpowers+bmad without openspec, both codex & claude flavors, one story]
- [Source: RunnerKind.java:15-25; RegistryContractTest.java:118; FlywaySchemaContractTest.java:867-874,1257-1259,1290-1294; V20/V26 migrations; registry-api-schema-placeholders.json (no runnerKinds)]
- [Source: RunnerProperties.java:354-359,376-383,653-668,710-725,727-739; application.yml:373-376; test application.yml:136-139; DockerRunnerAdapter.java:235-237,314,681-728; DoctorProbeAdapter.java:748-753]
- [Source: ProjectManagementService.java:323-325,364-401; ProjectRuntimeConfigResolver.java:197-244,281-308]
- [Source: **BMAD install docs** https://docs.bmad-method.org/how-to/install-bmad/ — `npx bmad-method install --yes --modules bmm --tools claude-code`; creates `_bmad/`; Node 20.12+; interactive-by-default, `--yes` for CI]
- [Source: runners/codex/Dockerfile:36,57,68-72,78,100-131 + runners/claude/Dockerfile:39,60,71-74,81,103-134; runners/vendor/superpowers/** + VENDOR.md; runners/RUNNER_CONTRACT.md:4-16,151-171; runners/codex/entrypoint.sh:89-90,249-252,550-585,592; runners/codex/lib/runner.mjs:264-291,462-522,612-692,749-793 + claude:780-825; mock-codex.sh/mock-claude.sh]
- [Source: docker-compose.yml:35-52; docker-compose.ci.yml:42-66; .github/workflows/ci.yml:515-559,1304-1310,1326-1462,1507-1529; runners/VERSION]
- [Source: review-result.v1.schema.json:65-86; RunnerContractValidator.java:31-32,50-57,217-218; deliveryline-backend/pom.xml:31-35]
- [Source: V19__add_reviewer_model_and_step_reviews.sql:24,40-62; V21 uq_step_reviews_runner_execution; ReviewResultHarvester.java:84-129,137-424,427-472; StepReviewSnapshot.java:70-90; WorkflowInspectionService.java:274-384,392-400; WorkflowController.java:627-665; ReviewerVerdictResponse.java; openapi.json:1234-1306; deliveryline-frontend/package.json:19-20, src/features/workflows/{components/ReviewerVerdictPanel.tsx,hooks/useReviewerVerdict.ts}]
- [Source: _bmad-output/implementation-artifacts/3h-2-…-hard-gate.md (Decision 5 jsonb precedent); 3g-5-token-real-capture-completion.md (additive review-result.usage; plain-text-fallback discipline; reviewer-usage log pin)]
- [Source: memory — runner-contracts-schema-stale-in-m2, flyway-v31-cross-branch-collision, openapi-regen-frontend-client-drift-cascade, validated-config-needs-test-yaml, springboot-testcontainers-test-must-be-IT, archunit-runs-in-failsafe-not-surefire, maven-argline-direct-goal-crash, spotless-apply-before-pushing-java-edits, jackson2-jsonnode-dto-500s-under-boot4-jackson3, application-cannot-import-adapters, runner-tool-self-test-needs-offline-mock]

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List

### Change Log
