# Story 1.22: Setup + CLI First-Run Quickstart Documentation

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **pilot installer**,
I want **`docs/quickstart.md` + `docs/setup-local.md` + a root `README.md` quickstart pointer that walk me through install → credential config → `doctor` verify → first governed `submit` end-to-end via CLI against `linear-mock` + `runners.mock` profiles**,
so that **I can complete a cold first-run without live assistance, satisfying NFR42 (pilot-user can run one low-risk ticket through the guided workflow using documented setup + tutorial material) for the Epic 1 CLI slice — the UI tutorial follows in Epic 2**.

## Acceptance Criteria

1. **`docs/quickstart.md` (new) — linear, copy-paste flow.** Contains a target completion time line ("~15 minutes from zero to first governed run"). The body is a numbered, linear sequence: (a) prerequisites check (Java 21, Docker, git), (b) clone the repo, (c) choose your environment (link to `docs/supported-environments.md` — Win 11 / macOS 14+ / Ubuntu 22.04+ / WSL2), (d) `cp .env.example .env` (no key needs to be edited for the mock-only first-run path — `.env.example` ships sufficient values), (e) `docker compose up -d postgres` (or `scripts/start-all.{ps1,sh}`), (f) build the CLI jar via `mvnw package` and capture its path, (g) run `java -jar $JAR deliveryline doctor` with `SPRING_PROFILES_ACTIVE=demo` and confirm `overall: PASS`, (h) `java -jar $JAR deliveryline submit --ticket LIN-101 --actor-identity <you> --actor-type human` against the mock Linear adapter, (i) `java -jar $JAR deliveryline status <runId>` and `... history <runId>`, (j) "how to interpret what you just saw" with a labelled screenshot-equivalent text block of expected output and the field-by-field meaning.

2. **`docs/setup-local.md` (new) — depth doc.** Explains in depth, with section anchors: (a) supported-environment matrix — link out to `docs/supported-environments.md` (story 1.17), do not duplicate the matrix; (b) Java 21 install per OS (Temurin/Adoptium 21 — link to vendor install pages for Windows, macOS, Ubuntu, WSL2); (c) Docker Desktop install per OS (Win, macOS) and Docker Engine 24+ on Ubuntu, plus Docker Desktop WSL2 integration toggle; (d) `.env` structure with every key from `.env.example` documented (`LINEAR_API_KEY`, `GITHUB_TOKEN`, `DELIVERYLINE_HOME`, `POSTGRES_PASSWORD`, `POSTGRES_HOST_PORT`) — call out the reserved Epic-3 keys with their status; the file MUST state "Do NOT commit `.env`"; (e) Spring profile choice — `local` vs `demo` (both activate `runners.mock + linear-mock` per `application.yml`; `local` is for active development, `demo` for stable show-and-tell); (f) Flyway V1 auto-applies on first start (point to `db/migration/` and note migrations are read-only on success — no manual apply step); (g) how to reset local state via `scripts/reset-local.{ps1,sh}` — what it removes (named compose volume `deliveryline-postgres-data`, `${DELIVERYLINE_HOME}` artifacts) and what survives.

3. **`docs/failure-recovery-walkthrough.md` (extend the existing 1-18 baseline).** The file already exists (created in story 1.18) and contains a self-marking note `"Story 1.22 will polish this into the full pilot-ops handbook"` (line 4). In this story: (a) replace that polish-pending sentence with a present-tense intro that says this IS the Epic-1 pilot-ops handbook for failed runs; (b) add an explicit "How to interpret each `failure category`" subsection mapping every registry value (`runner_timeout`, `runner_crash`, `runner_contract_violation`, `artifact_payload_unavailable`, etc.) to a one-line operator action; (c) add a "When to wait vs when to retry" decision-tree paragraph keyed off the `next safe action` matrix (already documented in `docs/cli/workflow-commands.md` — link to it, do not duplicate); (d) keep the existing forward-reference to Epic 4's `reconcile/takeover/resume/rerun` (intact); (e) do NOT remove the existing TL;DR, recovery-audit, or dispatch-failure-event sections — they stay.

4. **No bare `{}` placeholders.** Every command block in `docs/quickstart.md`, `docs/setup-local.md`, and `docs/failure-recovery-walkthrough.md` is copy-paste-ready: no command line contains a literal `{placeholder}` token that the reader must edit before pasting. Where substitution is genuinely needed (e.g., `${DELIVERYLINE_HOME}`, `<your linear ticket>`, the run ID from a prior step), the preceding paragraph shows exactly how to obtain that value first (e.g., "the `<runId>` is the first token of the `submit` command's stdout — copy it"). Run-ID example value `run_abc1234` from `docs/failure-recovery-walkthrough.md` is acceptable as a worked example, but in the quickstart, every reference is annotated as "from your previous step".

5. **NFR45 closure at Epic-1 boundary.** Both required first-run docs exist and are reachable from the root `README.md`: happy path → `docs/quickstart.md`; failed-run recovery → `docs/failure-recovery-walkthrough.md`. The root `README.md` (currently a 3-line stub) is replaced with: (a) one-paragraph project description, (b) "Pilot installer? Start with → `docs/quickstart.md`", (c) "Run failed? → `docs/failure-recovery-walkthrough.md`", (d) "All CLI commands → `docs/cli/`", (e) "Supported environments → `docs/supported-environments.md`", (f) "Glossary → `docs/glossary.md`".

6. **PowerShell + bash side-by-side.** Every OS-specific command in `quickstart.md` and `setup-local.md` shows both PowerShell and bash variants. Pattern: an `### PowerShell (Windows)` subsection immediately followed by an `### bash (macOS / Linux / WSL2)` subsection, each with its own fenced code block. No command appears in only one shell without the other counterpart. WSL2 follows the bash variants (it is a Linux shell) — call this out once in the prerequisite section, not on every step.

7. **Human pilot-installer validator placeholder.** Both `docs/quickstart.md` and `docs/setup-local.md` carry a visible block (e.g., at the bottom of the front-matter section or a banner under the title): "**Pilot-installer validator:** `_____________________________` (to be named before Epic 1 close)". This is the John-from-party-mode finding — name the human whose cold walkthrough gates the epic. The same placeholder appears in `docs/failure-recovery-walkthrough.md`.

8. **Link-check CI step.** `.github/workflows/ci.yml` gains a new step (cleanest placement: a new tier `docs-link-check` slotted before `foundation-gate`, or — equivalent — a step appended to `format-static-checks`) that runs a markdown-link checker (recommended: `lycheeverse/lychee-action@v2` — broadly supported, single binary, configurable include/exclude). The check runs on `ubuntu-latest`, runs against `docs/**/*.md` + `README.md`, validates internal links resolve to real files, and smokes external links (Linear, Docker install pages, Temurin/Adoptium). External-link failures emit `WARN` (not `FAIL`) on PR runs to avoid third-party outages blocking merges; internal-link failures FAIL the job. Add `.lycheeignore` (or equivalent config) to silence known-flaky external URLs (`docs.docker.com` redirects, GitHub anchor links). Wire the new job into `foundation-gate`'s `needs:` so story 1.23 picks it up automatically.

9. **Documentation-increment acceptance gate hand-off.** The story 1.23 `FoundationGateVerificationTest` (out of scope for this story) will assert the presence of `docs/quickstart.md`, `docs/setup-local.md`, `docs/failure-recovery-walkthrough.md`, `docs/glossary.md`. This story does NOT modify `FoundationGateVerificationTest`; it simply guarantees the four files exist with non-trivial content (≥30 non-blank lines each, except `glossary.md` which has its own minimum below). A documented note in this story's Dev Notes points the 1.23 implementer at the four file paths for the presence-assertion.

10. **`docs/glossary.md` (new) — minimal PRD concept set + glossary discipline rule.** Contains a heading per PRD-canonical concept — **ticket, spec, run, artifact, review, failure, recovery action** — with a one-sentence plain-language definition each. Plus a banner at the top: "**Glossary discipline:** Any doc that introduces a new term beyond this canonical set must add an entry here in the same PR. Concept sprawl is tracked against NFR43." Plus a forward reference: "Epic 2–5 vocabulary additions are owned by stories 6.1 / 6.2 (full audit) — this file is the Epic 1 seed." Minimum: 7 entries (the PRD-canonical set) + the discipline banner + the forward-reference paragraph. Anything beyond is welcome but not required.

## Tasks / Subtasks

- [x] **Task 1 — Author `docs/quickstart.md`** (AC: 1, 4, 5, 6, 7, 10)
  - [x] Create `docs/quickstart.md`. Open with: project name, one-line value prop, `**Target time:** ~15 minutes from zero to first governed run.`, and the pilot-installer-validator placeholder block (AC7).
  - [x] Add a `## Prerequisites` section listing: Java 21 (Temurin/Adoptium), Docker (Desktop on Win/macOS, Engine 24+ on Ubuntu), `git`, ≥4GB RAM free. Each prereq links to `docs/setup-local.md` anchors for install detail. Note WSL2 once here: "WSL2 users — follow the bash variants throughout this doc."
  - [x] Numbered linear sequence — one `##` step per top-level action with `### PowerShell` and `### bash` subsections under each OS-specific step (AC6). Steps: `## 1. Clone`, `## 2. Start Postgres`, `## 3. Configure `.env``, `## 4. Run `doctor``, `## 5. Submit your first run`, `## 6. Inspect status + history`, `## 7. Interpret what you saw`.
  - [x] In step 2, prefer `scripts/start-all.{ps1,sh}` (story 1.17) over raw `docker compose up -d postgres` — the script is the documented entrypoint and includes the observability profile no-op. Show the raw `docker compose` form in a collapsible "what this does under the hood" sub-block.
  - [x] In step 3, point at `.env.example` → `cp .env.example .env` (bash) / `Copy-Item .env.example .env` (PowerShell). Document which `.env` keys MUST be set for a mock-only first run (answer: none — `LINEAR_API_KEY` + `GITHUB_TOKEN` may stay empty because `linear-mock` + Epic-3-deferred GitHub adapter do not consume them; `POSTGRES_PASSWORD` has a sensible default). Document which keys MAY be overridden (`POSTGRES_HOST_PORT` if 5432 is in use).
  - [x] In step 4, show `deliveryline doctor` (preferred — the actual Spring Shell command from the running app) AND the `scripts/doctor.{ps1,sh}` fallback for "before you boot the app" / smoke usage. Expected output is the clean-install sample from `docs/cli/doctor.md` (link out, don't duplicate the full sample).
  - [x] In step 5, use `LIN-101` as the example ticket reference (matches the example in `docs/cli/workflow-commands.md` end-to-end example). Show the exact `deliveryline submit --ticket LIN-101 --actor-identity <your-name> --actor-type human --correlation-id quickstart-1` invocation. Substitution sentence (AC4): "Replace `<your-name>` with your username — e.g., `--actor-identity alex`."
  - [x] In step 6, show `status` then `history` text-mode output. Then mention `--format json` for tooling consumers.
  - [x] In step 7, walk through the expected `status` text-mode output field by field — `current state`, `current actor`, `last event type`, `last event timestamp`, `linked ticket`, `next safe action`. Link out to `docs/cli/workflow-commands.md` for the `next safe action` matrix; do not duplicate it.
  - [x] Add a final "If something went wrong" callout linking to `docs/failure-recovery-walkthrough.md` for failed-run handling and to `docs/cli/doctor.md` for `doctor: FAIL` triage.
  - [x] Add a "Concepts you just used" footer listing the PRD-canonical concept set used in this doc (`ticket`, `run`, `artifact`, `failure`) with one-line definitions cross-linked to `docs/glossary.md` (AC10).

- [x] **Task 2 — Author `docs/setup-local.md`** (AC: 2, 4, 6, 7, 10)
  - [x] Create `docs/setup-local.md`. Open with the same pilot-installer-validator placeholder (AC7) and a one-paragraph scope statement: "this doc is the depth reference behind `docs/quickstart.md`. Use it when a quickstart step needs more context — install detail, troubleshooting, or full option reference."
  - [x] `## Supported environments` section: link out to `docs/supported-environments.md`. Single sentence. Do not duplicate the matrix — that file is the source of truth.
  - [x] `## Install Java 21` section: per-OS subsections with `### PowerShell (Windows)`, `### bash (macOS)`, `### bash (Ubuntu)`, `### bash (WSL2 Ubuntu)`. Each shows the canonical Temurin/Adoptium install path (winget on Win, brew on macOS, apt+adoptium-repo on Ubuntu, same as Ubuntu inside WSL2). End with the verification command `java -version` and expected output starting with `21`.
  - [x] `## Install Docker` section: per-OS subsections. Win+macOS: Docker Desktop 4.x install + first-run config (resource allocation note: ≥4GB RAM, ≥2 vCPU). Ubuntu: `docker-ce` repo install + post-install `sudo systemctl enable docker` + non-root group note. WSL2: Docker Desktop WSL2 integration toggle path (Settings → Resources → WSL Integration). End with `docker version` and `docker compose version` verification commands.
  - [x] `## Configure `.env`` section: document every key in `.env.example`. Each key: name, purpose, required for what (e.g., `LINEAR_API_KEY` — required only for real Linear ticket intake, not for `linear-mock` profile), default behavior if blank, where to get the value. Bold note at the top: "**Do NOT commit `.env`** — it is in `.gitignore` per story 1.1 AC6."
  - [x] `## Choose a Spring profile` section: explain `local` vs `demo` vs `test` (all three group to `runners.mock + linear-mock` per `application.yml`, but each implies different operator intent — `local`: active development; `demo`: stable show-and-tell; `test`: CI-only, do not use as a runtime profile). Show how to set it: `SPRING_PROFILES_ACTIVE=local ./mvnw -pl deliveryline-backend spring-boot:run` (bash) and the `$env:SPRING_PROFILES_ACTIVE='local'; ./mvnw -pl deliveryline-backend spring-boot:run` (PowerShell) equivalents.
  - [x] `## Database migrations` section: explain Flyway V1 auto-applies on first Spring Boot start. Point at `deliveryline-backend/src/main/resources/db/migration/` for the migration source of truth. Note that Flyway is idempotent — restarting the app on an already-migrated DB is a no-op.
  - [x] `## Reset local state` section: show `scripts/reset-local.ps1` and `scripts/reset-local.sh` invocations. Document what gets removed (named compose volume `deliveryline-postgres-data` from `docker-compose.yml`, all artifacts under `${DELIVERYLINE_HOME}`, Flyway schema-state) and what survives (`.env`, source code, IDE state). Add a "When to reset" callout: after a failed Flyway migration, when switching between incompatible schema versions, when intentionally starting a clean demo.
  - [x] `## Troubleshooting` section: 3–5 most likely first-run failures with their fixes. Minimum coverage: (i) Postgres port 5432 already in use → override `POSTGRES_HOST_PORT` in `.env`; (ii) Docker daemon not running → start Docker Desktop / `sudo systemctl start docker`; (iii) Java 21 not on PATH → see `Install Java 21` section; (iv) `doctor` reports `DOCTOR_UNSUPPORTED_ENVIRONMENT` → see `docs/supported-environments.md` near-miss WARN list; (v) `doctor` reports `DOCTOR_POSTGRES_UNREACHABLE` → step 2 of quickstart not run yet.
  - [x] Final "See also" footer cross-linking `docs/quickstart.md`, `docs/supported-environments.md`, `docs/cli/README.md`, `docs/cli/doctor.md`.

- [x] **Task 3 — Extend `docs/failure-recovery-walkthrough.md`** (AC: 3, 4, 7)
  - [x] Open the existing file. Replace the line that says `"Story 1.22 will polish this into the full pilot-ops handbook; for now this is the CLI subset."` (line 4–5 in the current file) with the present-tense intro: "This walkthrough is the Epic-1 pilot-ops handbook for failed governed runs. Epic 4 will extend it with the operator console + `reconcile`/`takeover`/`resume`/`rerun` actions; for now, `retry` is the only CLI-driven recovery action."
  - [x] Add a new `## How to interpret each `failure category`` section after `## Step 2 — Decide`. Each subsection: one of the registry values from `failure_category_registry` (see `deliveryline-backend/src/main/resources/registries/`; alternatively the values listed in `docs/cli/workflow-commands.md` retry-exit-codes block: `runner_timeout`, `runner_crash`, `runner_contract_violation`, `artifact_payload_unavailable`, plus any others present in the failure-category registry source). Each entry: one-line "what it means", one-line "operator action" (retry / await reconciliation / file ticket). Do not list categories that are not in the registry — verify against the registry source file before listing.
  - [x] Add a `## Decision tree: retry vs wait` paragraph after the existing `## Step 2 — Decide`. Distill the `next safe action` matrix from `docs/cli/workflow-commands.md` (line 170–179) into prose, with an inline link to the full matrix. Do not duplicate the table — link only.
  - [x] Add the pilot-installer-validator placeholder block (AC7) at the top of the file, immediately under the H1 title.
  - [x] Keep intact: existing TL;DR (lines 12–28), Step 1 / Step 3 / Step 4 / Step 5, "What happens if the broker dispatch itself fails", "Replay", "What is NOT in Epic 1" section. Do not delete the existing forward-reference to Epic 4.
  - [x] Verify every command line in the file has no bare `{}` placeholders per AC4. `run_abc1234` is OK as the explicit "worked example" run ID (already in the file); existing `--correlation-id ops-2026-05-15-1` and `--actor-identity alex` are concrete and copy-paste-ready.

- [x] **Task 4 — Author `docs/glossary.md`** (AC: 10)
  - [x] Create `docs/glossary.md`. Open with the AC10 banner: "**Glossary discipline:** Any doc that introduces a new term beyond this canonical set must add an entry here in the same PR. Concept sprawl is tracked against NFR43."
  - [x] One entry per PRD-canonical concept — `ticket`, `spec`, `run`, `artifact`, `review`, `failure`, `recovery action`. Each entry: `### Term` heading, one-sentence plain-language definition, "See also:" line with cross-links to the doc/story that defines it deepest (e.g., `run` → link to `docs/cli/workflow-commands.md`; `failure` → link to `docs/failure-recovery-walkthrough.md`).
  - [x] Add the AC10 forward-reference paragraph: "Epic 2–5 vocabulary additions (spec lifecycle terms, runner pool, takeover, export bundle, classification, etc.) are owned by Epic 6 stories 6.1 (documentation index) and 6.2 (full glossary audit). This file is the Epic 1 seed — the canonical 7-concept set the PRD declared."
  - [x] Add a "Linked from" footer listing `docs/quickstart.md`, `docs/setup-local.md`, `docs/failure-recovery-walkthrough.md`, `docs/cli/README.md`. These are the docs that this story or prior stories link to the glossary.

- [x] **Task 5 — Replace root `README.md`** (AC: 5)
  - [x] Replace the existing 3-line `README.md` with the AC5 structure: project name + one-paragraph description, then a "Quick links" section: "Pilot installer? → `docs/quickstart.md`", "Run failed? → `docs/failure-recovery-walkthrough.md`", "All CLI commands → `docs/cli/`", "Supported environments → `docs/supported-environments.md`", "Glossary → `docs/glossary.md`".
  - [x] Preserve the existing pointer to `docs/cli/README.md` (lines 4–5 of current README) — fold it into the "All CLI commands" entry.
  - [x] Add a "License" section if a `LICENSE` file exists in the repo root; otherwise omit (do not invent license text). Verify by inspecting the repo root before this sub-task.
  - [x] Keep `README.md` under 80 lines — it is a landing page, not a depth doc.

- [x] **Task 6 — Add link-check CI tier** (AC: 8)
  - [x] Add a new job `docs-link-check` in `.github/workflows/ci.yml`. Placement: after `format-static-checks` (so it runs in parallel with `runner-contract-fixtures` for speed; both are fast). Runs on `ubuntu-latest`. Single OS — link-check is OS-agnostic.
  - [x] Use `lycheeverse/lychee-action@v2` (pinned to a tag, not `@main`). Inputs: `args: --no-progress --max-concurrency 4 --accept 200,206,429 docs/**/*.md README.md`. The `429` accept covers GitHub rate-limit responses; `--accept` syntax is lychee-v2 idiomatic. Set `fail: true` for internal links; configure external-link failures to emit `WARN` only via the lychee config below.
  - [x] Add `.lycheeignore` at repo root listing known-flaky external URL patterns: `^https://docs\.docker\.com/`, `^https://linear\.app/`, `^https://github\.com/.*#`. Document inline why each is ignored (third-party redirects + GitHub anchor-link false positives).
  - [x] Internal-link failures MUST fail the job (the AC8 contract). External-link failures emit a `::warning::` annotation and do NOT fail. Implementation: run lychee twice — once with `--exclude '^https?://'` and `fail: true` for internal-only; once with `--include '^https?://'` and continue-on-error for external warnings. Alternative single-invocation approach: use lychee's `.lychee.toml` with `skip_missing = false` for internal, `fail_on_error = false` overall, then post-process the JSON output to fail only on file-link 404s.
  - [x] Add the new `docs-link-check` job to `foundation-gate`'s `needs:` chain so story 1.23 picks it up automatically. Pattern matches Task 13 in story 1.21 (the `foundation-gate` placeholder job already aggregates all tier results).
  - [x] Upload the lychee JSON report as artifact `docs-link-check-report` with `if: always()` so failures are triagable. Retention 14 days per the story 1.21 convention (AC10).
  - [x] Add a comment block in `ci.yml` above the new job explaining the internal-fail / external-warn policy + the `.lycheeignore` rationale.

- [x] **Task 7 — Cross-doc anchor validation pass (manual)** (AC: 8)
  - [x] After Tasks 1–5 are written, sweep every `[text](path)` link in the four new/edited docs (`quickstart.md`, `setup-local.md`, `failure-recovery-walkthrough.md`, `glossary.md`) plus the new `README.md`. Confirm every relative path resolves against the current `docs/` tree on disk. Specifically verify: `docs/cli/README.md`, `docs/cli/workflow-commands.md`, `docs/cli/doctor.md`, `docs/supported-environments.md`, `docs/failure-recovery-walkthrough.md`, `docs/observability/log-schema.md` (created in story 1.19; verify present), `docs/ci-pipeline.md`, `docs/ci-branch-protection.md` (both created in story 1.21).
  - [x] If any referenced anchor (e.g., `#section-name`) does not match a heading in the linked file, fix it. The link-check CI step (Task 6) will catch broken file paths but not necessarily broken in-file anchors — that's why this manual pass exists.
  - [x] Do NOT introduce links to files that do not yet exist (e.g., `docs/index.md` is owned by story 6.1 — do not pre-link it from this story's docs).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `artifactId`, `operationId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`).
  - [x] **Story-specific note:** This story is documentation-only + a CI workflow tweak. There is no new JVM production code, so the logging instrumentation checklist above has **no production-code application surface** for this story. The standard remains in force for any tasks that did surface code (the link-check job is a shell workflow step — its triage signal is the lychee JSON artifact + `$GITHUB_STEP_SUMMARY`). Document in the Dev Agent Record: "No new JVM logging surfaces; CI step triage runs through the lychee JSON artifact and the GitHub Actions step summary."

## Dev Notes

### Story scope vs prior + future work

This story is the **last documentation-increment in Epic 1** (the only remaining backlog item paired with story 1.23). It hands four artifacts to story 1.23's `FoundationGateVerificationTest` for presence assertion:

- `docs/quickstart.md` (new)
- `docs/setup-local.md` (new)
- `docs/failure-recovery-walkthrough.md` (extended — file already exists from story 1.18)
- `docs/glossary.md` (new)

It also extends two existing artifacts:

- `README.md` (root) — currently a 3-line stub; story 1.21 Task 15 explicitly deferred the root README quickstart to this story.
- `.github/workflows/ci.yml` — adds the `docs-link-check` job that future Epic 2–6 doc-increment stories reference (every later doc-increment story mentions "the link-check CI step from story 1.22 AC8").

### Anti-patterns to avoid

- **Do not duplicate the supported-environment matrix.** `docs/supported-environments.md` (story 1.17) is the source of truth — link to it. The `## Known-good quickstart (≤ 10 minutes)` section inside that file is per-OS quickstart commands; `docs/quickstart.md` should reference those scripts (`scripts/start-all.{ps1,sh}`, `scripts/doctor.{ps1,sh}`) rather than re-listing them.
- **Do not duplicate the `next safe action` matrix.** `docs/cli/workflow-commands.md` already has the canonical table (lines 170–179). Link to it from `docs/failure-recovery-walkthrough.md` prose; do not paste a second copy.
- **Do not invent failure categories.** Read the source registry before listing categories in the "How to interpret each `failure category`" section (Task 3). The registry source is in `deliveryline-backend/src/main/resources/registries/` (story 1.4 central registries). If the registry source is sharded across multiple files, the failure-category one is the authoritative list — do not add categories that are not registered there.
- **Do not link to `docs/index.md`.** That file is owned by story 6.1 — pre-linking it from Epic 1 docs creates a broken-link risk and steps on Epic 6's scope.
- **Do not modify `FoundationGateVerificationTest` in this story.** Story 1.23 owns it. This story only guarantees the four files exist with non-trivial content.
- **Do not rewrite `scripts/start-all.{ps1,sh}` or `scripts/reset-local.{ps1,sh}` to make them more "doc-friendly."** They are stable artifacts from story 1.17 with their own ACs and tests. Document them as-is.

### Glossary discipline (NFR43)

The `docs/glossary.md` banner is the operational hook for NFR43 (minimize new workflow concepts). Every future doc-increment story in Epics 2–6 ACs already says "new terms introduced in docs require a glossary entry in `docs/glossary.md`" — this story creates the file those ACs point to. Keeping the Epic 1 seed minimal (7 PRD-canonical concepts) is intentional; Epic 6 stories 6.1 + 6.2 do the full audit + add Epic-specific vocabulary.

### Link-check tool choice rationale

Lychee was chosen over `markdown-link-check`, `linkchecker`, and `mlc`:

- **`lycheeverse/lychee-action@v2`** is actively maintained (last release within the past 6 months as of this story), runs as a single Rust binary (fast cold start, no Node deps), supports configurable accept-status codes (`--accept 200,206,429` covers GitHub rate-limit), and has a stable `.lycheeignore` file convention that matches the spirit of `.gitignore` (familiar to operators).
- `markdown-link-check` is Node-based — slower cold start, higher dependency surface.
- `linkchecker` is Python-based with a heavier install footprint than the team needs for a single CI step.
- `mlc` (markup-link-checker) is less widely adopted; using lychee aligns with what other Spring Boot OSS projects ship.

Pin `lychee-action@v2` to a specific tag — `lycheeverse/lychee-action@v2.6.1` or whatever the current stable tag is at story-execution time — to avoid silent breakage on minor action updates. The dev agent should check `https://github.com/lycheeverse/lychee-action/releases` for the latest stable v2.x tag before pinning.

### Profile choice clarification — `local` vs `demo`

Both group to `runners.mock + linear-mock` per `deliveryline-backend/src/main/resources/application.yml:6-7`. The functional behavior is identical; the operator-intent split is:

- `local` — active development. The operator may be hot-reloading code, running with `spring-boot-devtools`, attaching a debugger, frequently restarting.
- `demo` — stable show-and-tell. The operator is showing the product to a stakeholder; no restarts, no devtools, no debugger.

`test` is profile-grouped the same way but is reserved for `@SpringBootTest` integration tests via Testcontainers — operators should not use it as a runtime profile.

Document this in `docs/setup-local.md` `## Choose a Spring profile` — the source-of-truth definition lives in `application.yml`, not in any markdown doc; defer to the YAML for any future profile-group changes.

### `.env` keys — which are required for first-run

For the mock-only first-run path the quickstart walks, **no `.env` key is strictly required** — defaults are sufficient:

- `LINEAR_API_KEY` — empty is fine; `linear-mock` profile does not consume it.
- `GITHUB_TOKEN` — empty is fine; GitHub adapter is Epic-3 work.
- `DELIVERYLINE_HOME` — defaults to `./deliveryline-data` if unset (per `.env.example` line 9).
- `POSTGRES_PASSWORD` — defaults to `deliveryline` (per `.env.example` line 13). The `docker-compose.yml` `:?` syntax (`${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}`) means an unset value fails compose-up — so `.env` MUST exist (even if empty) with at least `POSTGRES_PASSWORD=deliveryline`. The quickstart's `cp .env.example .env` step satisfies this because `.env.example` line 13 already has the default value.
- `POSTGRES_HOST_PORT` — defaults to `5432` (per `.env.example` line 16). Override only if 5432 is already in use on the host.

This is the "right answer" for AC3 substitution sentences and AC2 `.env` documentation: a fresh `cp .env.example .env` is sufficient for the mock-only flow; users only edit `.env` if they hit a port collision or want to point at a real Linear workspace.

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

**Story-specific application:** No new JVM production code is introduced by this story. The standard is documented here for completeness — the dev agent should add a one-line note in `Dev Agent Record → Completion Notes` confirming "no new production-code logging surfaces" rather than treating this as a forgotten requirement.

### Project Structure Notes

- All new docs live under `docs/` (no new top-level dirs). Existing tree:
  ```
  docs/
    adr/
    cli/
      README.md, doctor.md, workflow-commands.md
    observability/
    .gitkeep
    ci-branch-protection.md, ci-pipeline.md, failure-recovery-walkthrough.md, supported-environments.md
  ```
  After this story:
  ```
  docs/
    adr/
    cli/...
    observability/...
    .gitkeep
    ci-branch-protection.md, ci-pipeline.md
    failure-recovery-walkthrough.md (extended)
    glossary.md (NEW)
    quickstart.md (NEW)
    setup-local.md (NEW)
    supported-environments.md
  ```
- `README.md` (root) is replaced (not appended to) — Task 5 explicitly rewrites the 3-line stub.
- `.lycheeignore` (NEW) at repo root — alphabetically sorts near `.gitignore` and `.gitattributes`.
- `.github/workflows/ci.yml` gains one new job (`docs-link-check`) + the `foundation-gate` `needs:` chain is extended. No new workflow files.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-1.22 (lines 796–813)] — Story 1.22 acceptance criteria 1–10, verbatim.
- [Source: _bmad-output/planning-artifacts/epics.md#Story-1.17 AC9 (line 734)] — NFR40 quickstart hook + forward reference to this story's documentation-increment acceptance check.
- [Source: _bmad-output/planning-artifacts/epics.md#Story-1.23 AC10 (line 832)] — Epic 1 Definition of Done references this story's docs + the pilot-installer validator naming gate.
- [Source: _bmad-output/planning-artifacts/prd.md (lines 488–490, 767, 772, 775)] — NFR40, NFR42, NFR43, NFR45.
- [Source: docs/supported-environments.md (lines 31–112)] — Per-OS known-good quickstart blocks the new `docs/quickstart.md` references (do NOT duplicate).
- [Source: docs/cli/README.md (entire file)] — CLI exit-code bands, idempotency-key contract, correlation-ID contract; linked from setup-local.md + quickstart.md.
- [Source: docs/cli/workflow-commands.md (lines 170–179)] — `next safe action` matrix; linked from extended failure-recovery-walkthrough.md.
- [Source: docs/cli/doctor.md (entire file)] — doctor command reference; linked from quickstart.md step 4.
- [Source: docs/failure-recovery-walkthrough.md (lines 1–5)] — explicit "Story 1.22 will polish this" marker that Task 3 replaces.
- [Source: .env.example (entire file)] — every key documented in setup-local.md `.env` section.
- [Source: docker-compose.yml] — `POSTGRES_PASSWORD:?` enforcement note for the .env-required clarification.
- [Source: deliveryline-backend/src/main/resources/application.yml (lines 1–8)] — `local`/`demo`/`test` profile-group definitions for the profile-choice section.
- [Source: scripts/start-all.sh + start-all.ps1, scripts/doctor.sh + doctor.ps1, scripts/reset-local.sh + reset-local.ps1] — cross-platform script entrypoints; referenced from quickstart.md + setup-local.md (do NOT modify, only document).
- [Source: _bmad-output/implementation-artifacts/1-21-github-actions-ci-tiered-pipeline.md Task 15 (line 138–142)] — explicit deferral of root README quickstart to story 1.22.
- [Source: _bmad-output/planning-artifacts/epic-06-pilot-docs.md (lines 31, 47–49)] — glossary discipline rule + future audit (story 6.2) confirming Epic 1 seed minimum is sufficient.
- [Source: https://github.com/lycheeverse/lychee-action] — pinned tool for the link-check CI step; check releases page for the latest stable `v2.x.y` tag at story-execution time.
- [Source: docs/cli/README.md (lines 4–9)] — failure-recovery-walkthrough already linked from the CLI index; AC5 README quick-link mirrors this.

### Open clarifications (resolve before merge if possible; otherwise defer to dev with documented rationale)

1. **Pilot-installer validator name (AC7) — placeholder vs concrete.** The AC explicitly calls for a placeholder ("to be named before Epic 1 close") because story 1.23 AC10 says the validator must be named before Epic 1 closes — which happens AFTER this story merges. **Recommendation:** ship the placeholder; do NOT block this story on finding the human. Alex (the user) tracks this in the project memory as an Epic-1-close action.

2. **Lychee version pin.** The recommended pin (`lycheeverse/lychee-action@v2.6.1` or current stable) needs to be verified at story-execution time. **Recommendation:** the dev agent runs `gh api repos/lycheeverse/lychee-action/releases?per_page=1` (or equivalent) at the top of Task 6 to find the current stable v2.x tag, then pins to that. Document the chosen tag in Dev Agent Record.

3. **External-link policy nuance.** AC8 says "all internal doc links … resolve to real files and all external links … pass a smoke check." Strict reading: external failures also FAIL the job. But that creates flake risk when third-party sites are slow/rate-limited. **Recommendation:** WARN-only on external failures (Task 6 sub-bullet 4) with a clear comment in `ci.yml` explaining the deviation from a strict reading of AC8; if the user (Alex) wants strict FAIL behavior, flip the lychee `fail` arg back on in a follow-up. Document the choice in Dev Agent Record.

4. **`docs/supported-environments.md` already has a "Known-good quickstart (≤ 10 minutes)" section.** This story's `docs/quickstart.md` is a different doc with a longer (~15 min) end-to-end flow including `submit`/`status`/`history`. **Recommendation:** the new `docs/quickstart.md` is the canonical first-run doc; the existing per-OS blocks inside `docs/supported-environments.md` are the platform-specific install reference. `docs/quickstart.md` should link out to those blocks for the per-OS install step rather than re-listing them. This is consistent with the "do not duplicate" anti-pattern above.

5. **Failure-category registry source location.** The Dev Notes refer to `deliveryline-backend/src/main/resources/registries/` but the exact filename containing the failure-category registry needs verification. **Recommendation:** dev agent greps the repo (`grep -rn "failure_category" deliveryline-backend/src/main/resources/`) at the start of Task 3 to find the authoritative file, then references the actual filename in Task 3's "How to interpret each `failure category`" subsection rather than hand-listing categories.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) — `claude-opus-4-7[1m]`

### Debug Log References

- Failure-category registry source confirmed at
  `deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java`. Eight
  registry values verified verbatim against the source enum: `runner_timeout`, `runner_crash`,
  `runner_contract_violation`, `runner_non_zero_exit`, `runner_late_result`,
  `runner_duplicate_result`, `runner_malformed_output`, `orphan`. The story's mention of
  `artifact_payload_unavailable` is a `DomainErrorCode`, not a `FailureCategory` value — it
  was excluded from the new "How to interpret each `failure category`" section to comply with
  the "Do not invent failure categories" anti-pattern.
- Lychee action stable tag verified at story-execution time via
  `gh api repos/lycheeverse/lychee-action/releases?per_page=5` — latest stable is `v2.8.0`
  (published 2026-02-25). Pinned `lycheeverse/lychee-action@v2.8.0` in `.github/workflows/ci.yml`.
- `python -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"` passed after
  inserting the new `docs-link-check` tier — YAML parse OK.
- Cross-doc anchor validation performed manually per Task 7. All anchors in new/edited docs
  resolve: `setup-local.md#install-java-21`, `setup-local.md#install-docker`,
  `setup-local.md#configure-env`, `setup-local.md#reset-local-state`,
  `supported-environments.md#known-issue-footnotes`,
  `cli/workflow-commands.md#next-safe-action-matrix`,
  `failure-recovery-walkthrough.md#step-2--decide-retry-vs-await_manual_reconciliation`.

### Completion Notes List

- **Status flip:** ready-for-dev → in-progress → review.
- **Open clarification 1 (pilot-installer validator name):** shipped the placeholder block in
  all three docs (`quickstart.md`, `setup-local.md`, `failure-recovery-walkthrough.md`) per
  the AC7 recommendation. Naming the human is an Epic-1-close action tracked elsewhere.
- **Open clarification 2 (lychee version pin):** pinned to `lycheeverse/lychee-action@v2.8.0`
  — the current stable v2.x tag at story-execution time (2026-05-18). Verified via
  `gh api repos/lycheeverse/lychee-action/releases?per_page=5`. Documented in the Debug Log
  above.
- **Open clarification 3 (external-link policy):** implemented the two-pass approach — pass
  1 (`--exclude '^https?://'`) FAILS the job on internal-link breakage; pass 2
  (`--include '^https?://'`) WARNS via `continue-on-error: true` plus a `::warning::`
  annotation. The ci.yml comment block above the job documents the policy + the deviation
  rationale. If the user wants strict-FAIL on external links, set `fail: true` on the second
  invocation in a follow-up PR.
- **Open clarification 4 (`supported-environments.md` quickstart section):** the new
  `docs/quickstart.md` is the canonical ~15-min first-run doc; the per-OS install blocks
  inside `supported-environments.md` remain the platform-specific install reference.
  `docs/quickstart.md` references those scripts (`scripts/start-all`, `scripts/doctor`) and
  the supported-env doc by link rather than duplicating commands.
- **Open clarification 5 (failure-category registry):** registry source confirmed at
  `deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java` (Debug
  Log above). The new "How to interpret each `failure category`" section in
  `failure-recovery-walkthrough.md` enumerates the eight registry values verbatim with their
  operator actions; the integration-layer `IntegrationFailureCategory` enum is a separate
  axis and was intentionally left out (runner-scoped vs. integration-scoped).
- **Minor outdated forward-reference fixed:** the existing
  `docs/failure-recovery-walkthrough.md` Step 5 said "story 1.22 will extend the operator
  surface" — that was factually wrong once this story committed (1.22 is doc-only). Updated
  the line to point at Epic 4 as the operator-console owner. Story sub-task 5 of Task 3
  required keeping Step 5 intact; this is a one-sentence factual correction, not a deletion.
- **AC4 compliance:** verified no bare `{placeholder}` tokens exist in any command block in
  the three story-owned docs. The only `{}`-bracketed strings present are
  `${DELIVERYLINE_HOME}` (a legitimate shell env-var reference — explicitly OK per AC4) and
  REST endpoint patterns like `/api/v1/workflows/{id}/retry` (in prose narrative inside the
  "What is NOT in Epic 1" section, not in a copy-paste command block).
- **AC9 line minimums:** verified all four 1.23-presence-asserted files have ≥30 non-blank
  lines (quickstart 222, setup-local 267, failure-recovery 219, glossary 63).
- **Logging instrumentation:** no new JVM production code introduced by this story — only
  Markdown docs and a single YAML workflow tier. The CI step's triage signal is the
  `docs-link-check-report` artifact (`lychee-internal.json` + `lychee-external.json`) plus
  the `::warning::` annotation surfaced through `$GITHUB_STEP_SUMMARY`. No SLF4J / Logback
  surface to instrument.
- **`FoundationGateVerificationTest` deferred to story 1.23:** this story does not modify
  that test. The four files it will presence-assert all exist with non-trivial content:
  `docs/quickstart.md`, `docs/setup-local.md`, `docs/failure-recovery-walkthrough.md`,
  `docs/glossary.md`.

### File List

- `README.md` — replaced (3-line stub → quick-links landing page, 39 lines).
- `docs/quickstart.md` — NEW (311 lines; 7-step linear copy-paste flow + interpret block).
- `docs/setup-local.md` — NEW (380 lines; per-OS install detail, `.env` per-key table,
  profile choice, Flyway notes, reset-local, troubleshooting).
- `docs/failure-recovery-walkthrough.md` — extended (added pilot-installer-validator
  placeholder, replaced polish-pending intro with present-tense intro, added "How to
  interpret each `failure category`" section, added "Decision tree: retry vs wait" section,
  fixed one outdated forward-reference to story 1.22 in Step 5; existing TL;DR, Step 1, Step
  3, Step 4, Step 5, "What is NOT in Epic 1" sections preserved).
- `docs/glossary.md` — NEW (91 lines; 7 PRD-canonical concepts + discipline banner +
  Epic 6 forward-reference + linked-from footer).
- `.lycheeignore` — NEW (repo root; suppresses known-flaky external URL patterns for the
  `docs-link-check` CI tier).
- `.github/workflows/ci.yml` — added `docs-link-check` tier (parallel with
  `runner-contract-fixtures`, both needing `format-static-checks`) using
  `lycheeverse/lychee-action@v2.8.0`; two-pass internal-fail / external-warn policy;
  uploads `docs-link-check-report` artifact with 14-day retention; wired into
  `foundation-gate` `needs:` chain.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — flipped
  `1-22-setup-and-cli-first-run-quickstart-documentation` status `ready-for-dev` →
  `in-progress` → `review`.

### Change Log

- 2026-05-18 — Story 1.22 implemented end-to-end. Five docs (`README.md`,
  `docs/quickstart.md`, `docs/setup-local.md`, `docs/failure-recovery-walkthrough.md`
  extended, `docs/glossary.md`) plus the `docs-link-check` CI tier and `.lycheeignore`.
  Lychee pinned to v2.8.0 (verified current stable). Internal-link failures gate merges;
  external-link failures emit WARN-only annotations. Foundation-gate `needs:` extended so
  story 1.23 inherits the new tier automatically. Status → review.
- 2026-05-18 — Code review patches applied (30 findings: 3 decisions + 27 patches).
  Highlights: (a) `docs/quickstart.md` rewritten — added step 0 "Choose your environment",
  swapped step order so `.env` is created before `start-all`, added step 4 "Build the CLI
  jar", switched every `deliveryline` invocation to `java -jar $DELIVERYLINE_JAR
  deliveryline …` with `SPRING_PROFILES_ACTIVE=demo`, replaced `<your-name>` /
  `<your-org>` angle-bracket placeholders with shell-safe unbracketed forms, added
  mock-mode auto-advance notes between submit/status and on `WaitingForSpecApproval`,
  added Postgres healthcheck `start_period` callout, removed the buggy prereq-doctor
  escape hatch. (b) `docs/setup-local.md` — added `sudo install -m 0755 -d
  /etc/apt/keyrings` to Java install blocks (Ubuntu 22.04 + WSL2), separated Docker
  Desktop WSL2 integration from WSL2 networking mode, reworded `POSTGRES_PASSWORD` row
  to clarify Compose has no default, split Verify (Java + Docker) and `.env` copy blocks
  into paired PS/bash subsections per AC6. (c) `docs/failure-recovery-walkthrough.md` —
  added `artifact_payload_unavailable` cross-listing as a `DomainErrorCode` (resolving
  AC3(b) decision-1 with option-a), reconciled per-category retry advice with the
  `next safe action` decision-tree (esp. `runner_late_result` and `runner_duplicate_result`),
  fixed duplicate "transient … transient" prose, removed fragile em-dash/colon anchor link.
  (d) `docs/glossary.md` — pruned bogus back-references from the "Linked from" footer
  (kept the two real ones; deferred the other two to Epic 6). (e) `README.md` — qualified
  `export-run` as Epic-5 placeholder, downgraded the 1.23 status-check claim to upcoming.
  (f) `.lycheeignore` — narrowed the `github.com.*#` regex to `blob/` JS-rendered heading
  anchors only; fixed the dead `adoptium.adoptium.net` alternative. (g)
  `.github/workflows/ci.yml` lychee tier — switched globs to explicit
  `docs/*.md docs/**/*.md README.md`, removed nested single quotes around regex args
  (shlex.split passes them through), pinned `--output` and artifact upload paths to
  `${{ github.workspace }}` so reports land where `upload-artifact` can find them.
  AC1(e) wording reworded in the spec body to "no key needs to be edited for the
  mock-only first-run path" (resolving AC1(e) decision-3 with option-b's spec-rewording
  variant). AC3(e) Step 5 forward-reference edit accepted as a permitted one-sentence
  factual correction (resolving AC3(e) decision-2 with option-b). Status → review (still
  awaiting human pilot-installer validation per AC7).

### Review Findings

_Code review 2026-05-18 — 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). 5 dismissed as noise. 0 deferred._

#### Decision needed

- [x] [Review][Decision] (resolved) AC3(b): `artifact_payload_unavailable` not in failure-category catalogue — AC literal text names it as a category to map. Dev Agent Record says it is a `DomainErrorCode`, not a `FailureCategory` enum value, so the implementation omits it. The deviation is justified internally but invisible to readers and contradicts the literal AC text. Decide: (a) add it as a category-row anyway with a note explaining it is a domain-error-code surfaced as a category-equivalent; (b) keep current omission and add a brief in-doc note explaining the registry-vs-AC resolution; (c) accept omission as a deliberate AC reinterpretation and update the story acceptance criteria.
- [x] [Review][Decision] (resolved) AC3(e): Step 5 of `docs/failure-recovery-walkthrough.md` modified ("Epic 4 ships the `reconcile` / `takeover` audit views and **the operator-console surface**") while spec said "do NOT remove the existing TL;DR, recovery-audit, or dispatch-failure-event sections — they stay". Dev Agent Record acknowledges it as a "one-sentence factual correction" of an outdated story-1.22 forward-reference. Decide: (a) revert the line to keep AC3(e) strictly satisfied; (b) accept the edit as a permitted factual correction and note it in Change Log; (c) refactor so the forward-reference line is deleted entirely (Epic 4 is mentioned elsewhere already).
- [x] [Review][Decision] (resolved) AC1(e): quickstart step 3 says "no `.env` key needs to be edited" while spec sub-step (e) reads "`cp .env.example .env` **and fill required keys**". Current text is the correct technical answer for the mock-only first-run path. Decide: (a) keep current text — spec text is wrong for mock path; (b) reword spec text in the story file to "and fill any required keys (none for the mock first-run path)" to remove the deviation; (c) add a required-key flow (e.g., make user pick a value for `POSTGRES_PASSWORD` even though default works).

#### Patch (action items)

- [x] [Review][Patch] (applied) **HARD BLOCKER** `deliveryline` CLI not on PATH; quickstart steps 5–6 call `deliveryline submit/status/history` but no binary, alias, script, or build step puts it there. The 15-min first-run promise is unrunnable as written. [docs/quickstart.md:124-160 (submit/status/history blocks)]
- [x] [Review][Patch] (applied) **HARD BLOCKER** Step ordering reversed — `start-all` (step 2) runs `docker compose up` before `.env` is created (step 3). Compose `${POSTGRES_PASSWORD:?Set POSTGRES_PASSWORD in .env}` aborts. Reorder: configure `.env` first, then start Postgres. [docs/quickstart.md steps 2 ↔ 3]
- [x] [Review][Patch] (applied) **HARD BLOCKER** `SPRING_PROFILES_ACTIVE` is never set in the quickstart, so even if the user finds the CLI they get the real Linear adapter (empty key) instead of `linear-mock`. Document the profile flag inline at step 5 (or wherever the app boots). [docs/quickstart.md step 5]
- [x] [Review][Patch] (applied) **HARD BLOCKER** Java install on fresh Ubuntu 22.04 / WSL2 fails — `/etc/apt/keyrings` does not exist by default; the Docker block correctly does `sudo install -m 0755 -d /etc/apt/keyrings` but the Java blocks do not. Add the directory-create line before `tee`. [docs/setup-local.md:47, :62]
- [x] [Review][Patch] (applied) Prerequisites' `scripts/doctor.{ps1,sh} --only supported-environment` escape hatch boots Spring (which runs `docker compose config` for compose autoconfig) and fails on the unset `${POSTGRES_PASSWORD:?...}`. Either remove the early invocation, gate it on `.env` existence, or add a no-Spring "env-only" mode. [docs/quickstart.md Prerequisites section]
- [x] [Review][Patch] (applied) CI risk: `docs/**/*.md` in the lychee args may not match top-level `docs/*.md` files (depends on glob impl). Add explicit `docs/*.md docs/**/*.md README.md` to guarantee coverage of the seven Epic-1 top-level docs. [.github/workflows/ci.yml lychee args, both passes]
- [x] [Review][Patch] (applied) CI risk: `--exclude '^https?://'` quoting may not survive YAML → `lycheeverse/lychee-action` argv tokenization on v2; if the regex arrives with literal single quotes, the internal pass matches no URL and external links can break the build (inverting the WARN-only contract in AC8). Verify or switch to action's native input. [.github/workflows/ci.yml lychee args]
- [x] [Review][Patch] (applied) CI risk: `--output ./lychee-*.json` may write inside the action's container scratch dir, not `$GITHUB_WORKSPACE`. The subsequent `actions/upload-artifact` step would silently upload nothing (`if-no-files-found: ignore`). Pin with absolute `${{ github.workspace }}/…` or a `working-directory:`. [.github/workflows/ci.yml lychee output + artifact upload]
- [x] [Review][Patch] (applied) Anchor links with em-dash + colon (e.g. `#step-2--decide-retry-vs-await_manual_reconciliation`) may resolve differently in lychee (pulldown-cmark) than GitHub. Either rename the offending H2 to ASCII-only or pre-flight the anchor against the same lychee version used in CI. [docs/failure-recovery-walkthrough.md:88, similar throughout]
- [x] [Review][Patch] (applied) `.lycheeignore` rule `^https://github\.com/.*#` is over-broad — silences every GitHub URL with a fragment (issue-comments, code-permalinks, PR anchors). Scope to JS-rendered heading anchors only (e.g. `^https://github\.com/[^/]+/[^/]+/blob/[^#]+#L?[A-Za-z0-9-_]+$`) or drop with justification. [.lycheeignore:21]
- [x] [Review][Patch] (applied) `.lycheeignore` rule `^https://(packages|adoptium)\.adoptium\.net/` has a dead alternative (`adoptium.adoptium.net` does not exist). Rewrite as `^https://(packages\.|www\.)?adoptium\.net/`. [.lycheeignore:24]
- [x] [Review][Patch] (applied) AC1(c): "choose your environment" is not a discrete numbered step in quickstart; the four supported OSes (Win 11 / macOS 14+ / Ubuntu 22.04+ / WSL2) are not enumerated at the choice point. Add either as a `## 0. Pick your environment` preamble or expand the Prerequisites OS section. [docs/quickstart.md Prerequisites]
- [x] [Review][Patch] (applied) AC6: cross-shell-identical verify commands appear as single fenced blocks rather than `### PowerShell` / `### bash` paired subsections — affects `docker compose ps`, `docker version`, `docker compose version`, `java -version`, and the `--format json` status/history block. Split into paired subsections to match spec wording. [docs/quickstart.md, docs/setup-local.md "Verify" subsections]
- [x] [Review][Patch] (applied) Step 4 doctor inverts the spec's command preference. Spec wants `deliveryline doctor` (Spring-Shell command) as primary plus `scripts/doctor.*` as fallback; diff shows scripts as primary and the Spring-Shell command only in prose. Promote `deliveryline doctor` to a copy-paste-ready code block. [docs/quickstart.md step 4]
- [x] [Review][Patch] (applied) Decision-tree section gives per-category retry advice (esp. `runner_late_result`: "await reconciliation — retrying may dual-write") that contradicts the formal "trust `next safe action` first" rule introduced two paragraphs later. Reconcile: either remove per-category retry directives or qualify them as "if `next safe action` agrees". [docs/failure-recovery-walkthrough.md:107-150]
- [x] [Review][Patch] (applied) Glossary "Linked from" footer claims back-references from `cli/README.md` (admits "Epic 6 will add the cross-link") and `failure-recovery-walkthrough.md` (no link added in this diff). Either wire the cross-links in this PR or remove the bogus entries. [docs/glossary.md:88-91]
- [x] [Review][Patch] (applied) `runner_non_zero_exit` operator-action prose repeats "transient … transient adapter failure". Trivial copy-edit. [docs/failure-recovery-walkthrough.md:106 or nearby]
- [x] [Review][Patch] (applied) `<your-name>` and `<your-org>` placeholders in copy-paste command blocks parse as input redirection in both bash (`<file`) and PowerShell. A user who forgets to substitute gets an opaque shell error. Switch to unbracketed placeholders (`alex` example with a substitute note) or use `__YOUR_NAME__` style. [docs/quickstart.md:418-422 (clone) and :554-570 (submit)]
- [x] [Review][Patch] (applied) `POSTGRES_PASSWORD` is described as having a "default of `deliveryline`", but `docker-compose.yml` uses `${POSTGRES_PASSWORD:?...}` which has no default — the value must be set in `.env`. Reword as "shipped value in `.env.example` is `deliveryline`; compose fails if absent". [docs/setup-local.md and docs/quickstart.md .env tables]
- [x] [Review][Patch] (applied) WSL2 Postgres reachability note conflates "Docker Desktop WSL2 integration" (about the daemon) with WSL2 networking (mirrored vs NAT). Rewrite the troubleshooting bullet to distinguish the two. [docs/setup-local.md:842 (or nearby in WSL2 section)]
- [x] [Review][Patch] (applied) Quickstart Postgres verification says you'll see `running (healthy)` but `start_period: 30s` in `docker-compose.yml` means `health: starting` for the first 30s. Add a wait-loop snippet, or document the starting state. [docs/quickstart.md:101]
- [x] [Review][Patch] (applied) Quickstart step 7 enumerates `WaitingForSpecApproval` in the workflow states but earlier text says the mock runner advances "automatically". A reader who knows the state machine expects a human approval gate. Add a one-line "mock-mode auto-approves the spec-review gate" note. [docs/quickstart.md step 7]
- [x] [Review][Patch] (applied) Quickstart submit shows `state: Inbox` then status shows `current state: Executing` with no warning that the mock runner advanced between commands. Add a one-line "the mock runner advances the workflow as soon as it sees the new run" callout between step 5 and step 6. [docs/quickstart.md step 5→6 transition]
- [x] [Review][Patch] (applied) README "Project layout" lists `export-run` alongside three actually-working scripts, implying Epic-1 readiness; `scripts/export-run.sh` actually `exit 2`s with "not available until Epic 5". Mark as `(Epic 5)` or remove from the listed-as-ready set. [README.md:29-30]
- [x] [Review][Patch] (applied) README claims `foundation-gate` is the required status check "(story 1.23)" — story 1.23 has not shipped at the time this README ships. Qualify as upcoming or move the line until 1.23 lands. [README.md:30-31]
- [x] [Review][Patch] (applied) `Set-Location deliveryline` (and `cd deliveryline`) assumes the clone directory name matches "deliveryline". If `<your-org>/repo` is named differently, the step breaks. Either show `git clone <url> deliveryline` to force the dir name, or `cd <repo>` with a substitute note. [docs/quickstart.md:418-422]
- [x] [Review][Patch] (applied) PowerShell version preface missing — `supported-environments.md` covers PS 5.1 and 7+; users on Core 6.x or older PS get no signal they are outside support. Add a one-line PS-version requirement at the top of the Windows section. [docs/quickstart.md Prerequisites]

#### Dismissed as noise

- `.lycheeignore` comment "silences known-flaky external URL patterns" is mildly misleading about influencing both passes — harmless.
- CI comment "byte-identical except for the include/exclude flag" — also output filename differs; pedantic.
- Speculation that lychee `--include` overrides `.lycheeignore` — lychee CLI confirms AND-combine behavior.
- README cosmetic: `[\`docs/cli/\`](docs/cli/README.md)` visible text trails slash; target resolves correctly.
- `reset-local.ps1` strict-mode + PowerShell Core under WSL2 — WSL2 PS Core is not in the support matrix.
