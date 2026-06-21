# Story 3c.12: Project-Configuration Documentation Increment

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this is a PURE-DOCUMENTATION story. ZERO production code, ZERO test logic, ZERO CI-workflow-logic changes.** It is the **twin of the done doc-increment stories 3-36** (`docs/execution-walkthrough.md`) **and 3c-13** (`docs/patterns/registry-recipe.md` + `docs/testing/*`). Your whole job is to write **one new operator walkthrough** + add **three glossary entries** + wire **two index/link surfaces**, all describing **features that already shipped** (3c-3 … 3c-10, all `done`). The `git diff --stat` for this story must show only files under `docs/` plus `README.md`. Three things will bite you immediately:
>
> 1. **`docs/index.md` DOES NOT EXIST — and you must NOT create it.** Epic 3c AC6 literally says "the doc is visible from `docs/index.md`", but `docs/index.md` is an **Epic 6 deliverable** (story 6.1 "full documentation index" — see `docs/glossary.md` L8-11) that has never been built. The **established doc-index surface** every prior walkthrough uses is the **`README.md` "Quick links"** section (L10-24) + the **`docs/glossary.md` "Linked from"** section (L204-214). **Reconciliation: make the walkthrough visible from README "Quick links" (and glossary "Linked from"), NOT a net-new `docs/index.md`.** See R1.
> 2. **The CI `docs-link-check` (lychee) HARD-FAILS on any broken internal link or anchor** (`.github/workflows/ci.yml` L147-175: the internal pass runs `--exclude ^https?://` over `docs/*.md docs/**/*.md README.md` with `fail: true`). Every relative link AND every `#anchor` you write must resolve to a real file / real heading. External `https://` URLs (e.g. `github.com`) are WARN-only (non-blocking). See R3.
> 3. **DO NOT promise queue-filtering / per-run project attribution that DID NOT SHIP.** Epic AC1 ends "…scope a run to the project", and the epic 3c-9 wording imagined a project-scoped queue + per-run project badge. **That was deferred** — 3c-9 shipped the project **selector that collapses to a static label when only the `default` project exists**, but the queue filter + per-run badge are an **unwired backend follow-up** (`WorkflowSummary`/`WorkflowDetail` carry no project field; `GET /api/v1/workflows` has no `projectId` param). Describe what an operator can actually do today, not the deferred seam. See R2.

## Story

As an operator joining the pilot,
I want a project-configuration walkthrough — open the Projects area, create/edit a project, choose connector kinds and run options, set credentials safely, run the connection test, activate, and scope work to the project — plus glossary entries for the new vocabulary (`project`, `connector`, `credential`),
so that I can configure and verify a project unaided on my first use, in the browser, on any OS, with every concept I meet defined in the canonical glossary.

## Background / Why this story exists

Epic 3c shipped multi-project configuration end-to-end (the `Project` aggregate, per-project connector resolution, encrypted credentials, the REST API, and the Projects management UI). The **epic doc-increment rule** (epic-03c L7, AC8 below) says Epic 3c **cannot close** without an operator-facing walkthrough merged alongside the feature stories. This story discharges that rule.

It is the **third documentation-increment story in the same family** and must match the format, scope discipline, and index conventions the prior two set:

- **3-36** wrote `docs/execution-walkthrough.md` (the developer execution-stage guide) — the **primary format template** for this story.
- **3c-13** wrote the contributor docs (`docs/patterns/registry-recipe.md`, `docs/testing/*`) and trimmed agent memory — the **scope-discipline + index-convention precedent**.

"Done" means **the walkthrough file exists in the repo**, the three glossary terms are registered, the README/glossary index surfaces point at it, and the gating `docs-link-check` stays green. No feature behavior is invented or changed — this is a **capture** story: describe what 3c-3…3c-10 already do, verified against live source/UI.

## Acceptance Criteria

> These ACs are **reconciled** against the actually-shipped Epic 3c surfaces (all `done`) and the established documentation conventions. Where the epic wording (epic-03c L224-239) assumes an artifact that does not exist (`docs/index.md`) or a capability that was deferred (queue-scoping), the reconciled wording below is authoritative; the rationale is in Dev Notes → "Why these ACs are reconciled (R1-R4)".

1. **`docs/project-configuration-walkthrough.md` exists** and follows a single linear sequence an operator can complete in the browser: **open the Projects area** (the "Projects" nav landmark, distinct from the queue/run views) → **create / edit a project** (display name, slug [create-only — immutable on edit], repository URL, ticket-source kind, repository-host kind, OpenSpec run-option toggle) → **set credentials safely** (write-only per connector role) → **run the connection test** → **activate** (the project is `active` and advertises its allowed actions) → **scope work to the project** described as it actually ships (see AC of the selector, R2). The doc names the real UI surfaces, statuses, and the three connection checks exactly as they exist.

2. **Credential-safety guidance is correct and concrete.** The doc explains that connector credentials are **write-only** (set/replaced, never displayed, never pre-filled, never placed in the DOM or exported), **encrypted at rest** via envelope encryption (a per-secret AES-256-GCM data key wrapped by a host-supplied master key), and that the **master key is supplied via the `DELIVERYLINE_MASTER_KEY` host environment variable** (Base64), is never persisted to the DB or any file, and that the credential subsystem therefore defends an at-rest DB compromise but **not** a host compromise. It links the canonical detail in [`docs/adr/0013-credential-encryption.md`](../../docs/adr/0013-credential-encryption.md) and the `master key` / `credential encryption` glossary entries rather than re-deriving the cryptography.

3. **The connection-test section explains each of the three checks** — `repository_reachable`, `ticket_source_auth`, `repository_host_auth` — what each verifies, that each returns `pass` / `fail` / `skipped` with a **secret-free** `detail` string (a `fail`/`skipped` is in-band data on an HTTP 200, not an error), and **how to fix a named failing check** (e.g. a `ticket_source_auth` fail → the connector kind or its credential is wrong; a `repository_reachable` fail → the repository URL or repo-host credential). Results are session-scoped (the backend persists no test history — "Not tested" until the operator runs a test this session).

4. **The default-project transparency note is present.** The doc explains that an existing single-project setup is **migrated transparently** into a seeded `default` project (repo URL + `linear` ticket-source + `github` repo-host + OpenSpec flag taken from the prior global config; its credentials reference the existing global env-var secrets) and **requires no operator action**; when only the `default` project exists the project selector collapses to a static label (no selection friction).

5. **Glossary entries are added (glossary discipline + NFR43).** `docs/glossary.md` gains canonical entries for **`project`**, **`connector`**, and **`credential`** under the existing "Epic 3c vocabulary" section; the placeholder note there (L172-174: "story 3c-12 adds the broader `project` / `connector` / `credential` vocabulary") is replaced by the real entries. Each entry follows the existing glossary style (definition + "See also" cross-links) and stays consistent with the already-present `master key` / `credential encryption` entries. No concept used in the walkthrough is left without a glossary entry.

6. **Index/link surfaces are wired and all internal links resolve.** The walkthrough is **visible from `README.md` "Quick links"** (a new operator-keyed entry, mirroring the existing "Developer reviewing implementation output?" line) **and** registered in the `docs/glossary.md` "Linked from" list — this is the project's real doc-index convention (`docs/index.md` is an Epic-6 deliverable that does not exist; do **not** create it). Every internal relative link and `#anchor` in the new doc + edited files resolves; the CI `docs-link-check` (lychee internal pass, `fail: true`) stays green.

7. **Browser-based, OS-neutral.** The walkthrough is entirely browser-based with **no OS-specific instructions**; the one host-environment touch-point (the `DELIVERYLINE_MASTER_KEY` env var) is described as an environment variable without shell-specific or OS-specific syntax (consistent with `execution-walkthrough.md`'s cross-platform stance).

8. **Epic doc-increment rule satisfied.** Epic 3c cannot close without this walkthrough merged **and** a **named human-validator placeholder** included at the top of the doc — mirror the existing pattern exactly: a blockquote `> **Project-configuration walkthrough validator:** \`_____________________________\` (to be named before Epic 3c close)` (cf. `execution-walkthrough.md` L3, `pm-loop-walkthrough.md` L3).

9. **Scope discipline: docs-only.** `git diff --stat` for this story shows **only** files under `docs/` plus `README.md` — no backend, frontend, test, Flyway, OpenAPI, error-code, or CI-workflow change. `format-static-checks` is **N/A** (docs are outside the backend Spotless + frontend Prettier scope, consistent with 3-36 / 3c-13). No new glossary term is introduced anywhere else in the same change without its entry (AC5).

## Tasks / Subtasks

- [x] **Task 0 — Verify the shipped surfaces before writing (AC: 1-4)**
  - [x] This is a **capture** story: confirm each behavior you describe still exists. Re-read the authoritative sources: the 3c-9 story file (the live UI: nav landmark, list columns, create/edit form fields, write-only credential control, the three connection checks, the collapse-to-label selector, the **deferred** queue-scoping), the 3c-8 story file (the REST contract + `TestConnection` check enums + write-only `SetCredentialResponse`), `docs/adr/0013-credential-encryption.md` (master key + envelope encryption + threat model), and the 3c-6 reconciliation (default-project seed + transparent migration).
  - [x] Where the epic AC text and the shipped reality differ, **write the shipped reality** and capture the reconciliation in the doc's "What is NOT in this walkthrough" section (queue-scoping/per-run-attribution = a documented follow-up, NOT shipped). Do **not** "fix" any code you read.
- [x] **Task 1 — Write `docs/project-configuration-walkthrough.md` (AC: 1, 2, 3, 4, 7, 8)**
  - [x] Open with the title + the **validator-placeholder blockquote** (AC8) + a one-paragraph framing that pairs it with `quickstart.md` / `setup-local.md` (upstream: getting DeliveryLine running) and states it is browser-based + OS-neutral (AC7), mirroring `execution-walkthrough.md` L1-20.
  - [x] Linear step sequence (AC1): open Projects area → create/edit project (fields incl. slug-immutable-on-edit, kinds, OpenSpec toggle) → set credentials safely (AC2) → connection test (AC3) → activate → scope work to the project (R2-accurate). Use ASCII panel sketches in the `execution-walkthrough.md` house style where they clarify a surface; keep them illustrative, not pixel-exact.
  - [x] Credential-safety section (AC2) — write-only, encrypted-at-rest, master-key-from-host-env; link ADR 0013 + glossary, don't re-derive crypto.
  - [x] Connection-test section (AC3) — the three checks, pass/fail/skipped semantics, per-check fix guidance, session-scoped results.
  - [x] Default-project note (AC4) — transparent migration, no action needed, selector collapses to a label.
  - [x] Close with a **"Concepts you just used"** footer linking `glossary.md` (the new `project` / `connector` / `credential` entries + the existing `master key` / `credential encryption`) and a **"What is NOT in this walkthrough"** section that records the deferrals (queue-scoping / per-run attribution backend follow-up; rotation mechanics; multi-user/RBAC out of scope) — mirror `execution-walkthrough.md` L519-547.
- [x] **Task 2 — Add glossary entries `project` / `connector` / `credential` (AC: 5)**
  - [x] Under the existing `## Epic 3c vocabulary (multi-project credentials)` section in `docs/glossary.md`, replace the L172-174 placeholder note with three real entries in the established style (definition + bold **See also:** cross-links to the walkthrough + ADR 0013 + the related `master key` / `credential encryption` entries). Keep wording consistent with the already-present entries.
  - [x] `project` = the first-class aggregate every governed run is scoped to (repo binding + selectable connector kinds + encrypted per-project credentials + run options; statuses `active`/`disabled`; a seeded `default` project preserves single-project parity). `connector` = the selectable, vendor-neutral ticket-source / repository-host adapter a project binds by `ConnectorKind` (`linear`, `github`, and the registered `gitlab` proof-of-seam), resolved per project at run time. `credential` = the write-only, envelope-encrypted per-role secret (`ticket_source` / `repo_host`) that a connector uses at call time; set/rotated, never read back, redacted from every egress.
- [x] **Task 3 — Wire the index/link surfaces (AC: 6)**
  - [x] Add a `README.md` "Quick links" entry pointing operators at the walkthrough (e.g. `**Configuring a project (connectors + credentials)?** → docs/project-configuration-walkthrough.md`), placed near the existing operator-facing entries.
  - [x] Add the walkthrough to the `docs/glossary.md` "Linked from" list (it links to the glossary in its "Concepts you just used" footer, so the back-reference is accurate — unlike the contributor docs 3c-13 deliberately did **not** add there).
  - [x] **Do NOT create `docs/index.md`** (R1).
- [x] **Task 4 — Verify links + diff scope (AC: 6, 7, 9)**
  - [x] Hand-verify every relative link and every `#anchor` in the new doc + the README/glossary edits resolves (broken internal links HARD-FAIL the gating lychee pass). If you reference another doc's heading anchor, open that doc and confirm the heading exists and the slug matches (GitHub-style: lowercased, spaces→`-`, punctuation dropped).
  - [x] If `lychee` is available locally, run the internal pass (`lychee --exclude '^https?://' docs/*.md docs/**/*.md README.md`); otherwise audit by hand. Confirm `git diff --stat` shows only `docs/` + `README.md`. (lychee not installed locally → hand-audited; diff scope confirmed `docs/` + `README.md`.)
- [x] **Logging instrumentation** — **N/A for this story** (pure documentation; no services, no frontend code touched). Recorded explicitly per the project-wide logging task, consistent with 3-36 / 3c-13.

## Dev Notes

### Scope discipline (read this twice)
- **Docs-only.** Mirror 3-36 / 3c-13 exactly. Do **not** "improve" any code, test, or CI file you read while sourcing the doc. If you find drift between a memory/epic claim and live source, **correct the doc text** to match reality and (only if it's a genuine bug) note it as a follow-up — do not fix it here.
- **Capture, not design.** The walkthrough describes **existing** Epic-3c behavior. Invent no new conventions, toggles, endpoints, or flows. Every surface/status/check name must match the shipped UI + REST contract.
- The story's `git diff` is **repo-only** and must be `docs/` + `README.md`. No `format-static-checks` impact (docs are outside Spotless/Prettier scope).

### Why these ACs are reconciled (R1-R4) — epic wording vs. the live codebase
The dev agent only has this file. These are the traps where the epic 3c-12 ACs (epic-03c L224-239) collide with the real repo state; follow the reconciled ACs above.

| # | Epic assumption | Reality | Reconciliation |
|---|---|---|---|
| **R1** | "the doc is visible from `docs/index.md`" (epic AC6) | **`docs/index.md` does not exist.** It is an Epic 6 deliverable (story 6.1 "full documentation index"; see `docs/glossary.md` L8-11). The real doc-index surface every walkthrough uses is **`README.md` "Quick links"** (L10-24) + **`docs/glossary.md` "Linked from"** (L204-214). 3-36 and 3c-13 both indexed via README, **not** an index.md. | **Index via README "Quick links" + glossary "Linked from". Do NOT create `docs/index.md`.** Creating a stub index.md would be out-of-convention and risks lychee anchor breakage. |
| **R2** | "…scope a run to the project" implying a project-scoped queue + per-run project attribution (echoing epic 3c-9 AC6) | 3c-9 **deferred** the queue filter + per-run badge: `WorkflowSummary`/`WorkflowDetail` carry **no** project field and `GET /api/v1/workflows` has **no** `projectId` param. What shipped is the **project selector that collapses to a static label when only `default` exists** (and offers selection at ≥2 projects), delivered as an **unwired seam**. Run↔project association happens at **submit/intake** (3c-7: optional `projectReference` on `submit`), not via a queue UI filter. | **Describe what ships:** an operator picks/created a project, credentials + tests it, and **work is associated with a project at submission/intake**; the selector collapses to a label in the single-project pilot. Record the **queue-scoping + per-run attribution as a documented backend follow-up** in "What is NOT in this walkthrough" — do not present it as available. |
| **R3** | "all internal links resolve; … link-check CI step" | The CI job is **`docs-link-check`** using **lychee** (`.github/workflows/ci.yml` L147-205). The **internal pass** (`--exclude ^https?://`, `fail: true`) hard-fails on any broken file/anchor over `docs/*.md docs/**/*.md README.md`. The **external pass** (`--include ^https?://`) is WARN-only. `.lycheeignore` silences known-flaky external URLs. | **Every relative link + `#anchor` must resolve** (open the target, confirm the heading slug). Example `https://` URLs are safe (WARN-only). This is the one gate this story can actually break. |
| **R4** | "credentials are … encrypted at rest, … how the master key is supplied via the host environment" | Accurate and shipped (3c-4/3c-5): envelope encryption, AES-256-GCM data key wrapped by a master key from **`DELIVERYLINE_MASTER_KEY`** (Base64 host env var), `keyId` = `mk_`+first-12-hex of SHA-256, never persisted. Threat model: defends at-rest DB compromise, **not** host compromise. **Note:** the env var is documented in `docs/adr/0013-credential-encryption.md` + the glossary `master key` entry, **but NOT yet in `quickstart.md`/`setup-local.md`.** | **Link ADR 0013 + the glossary `master key` entry** for the canonical detail; describe the env var OS-neutrally (AC7). Do **not** invent a setup step in quickstart/setup-local (out of scope) — if a cross-link target for "how to set the env var" is wanted, point at ADR 0013, which is the source of truth today. |

### The shipped surfaces to describe (verified — capture accurately)
- **Nav + screen (3c-9 AC1):** a "Projects" nav landmark (in the existing nav rail + mobile drawer) opens a settings/config area distinct from `/workflows` (queue) and `/workflows/$id` (run).
- **Project list (3c-9 AC2):** per project — name, status (`active`/`disabled`, icon+text, never color-alone), ticket-source kind, repository-host kind, repository URL (graceful empty state), per-role credential presence (`configured`/`not_configured`), last connection-test result + timestamp (session-scoped; "Not tested" until run this session).
- **Create/edit form (3c-9 AC3):** display name (≤256), slug (create-only, immutable on edit), repository URL (nullable, ≤2048), ticket-source kind picker, repository-host kind picker, OpenSpec toggle; field-level validation + explicit error identification.
- **Connector kinds (registry):** `ConnectorKind` = `linear`, `github`, plus a registered **`gitlab`** kind that proves the per-project resolution seam (3c-3 AC8 — a documented stub, not a full vendor impl). The picker is a frontend constant mirroring the registry (3c-9 Open Decision #3).
- **Write-only credentials (3c-8 AC2 / 3c-9 AC4):** roles `ticket_source` / `repo_host` (underscored wire form); set/replace via a `type="password"` input that is never seeded from any response; the response is id-only (`{role, status:'configured', credentialId:'cred_…'}`), never a secret. `setProjectCredential` requires an `Idempotency-Key`.
- **Connection test (3c-8 AC3 / 3c-9 AC5):** `POST …/test-connection` → `TestConnection.checks[]`, each `{ check: 'repository_reachable'|'ticket_source_auth'|'repository_host_auth', status: 'pass'|'fail'|'skipped', detail }`. In-band data on HTTP 200; a check `fail`/`skipped` is **not** an error surface. Only `PROJECT_NOT_FOUND` / `UNSUPPORTED_CONNECTOR_KIND` (Problem Details) are true errors.
- **Allowed actions (3c-8 AC6 / 3c-9 AC7):** project controls (create/edit/disable/enable/set-credential/test) are gated on the backend-reported `Project.allowedActions: string[]` (status-derived, NO role dimension — no RBAC). The `default` project never advertises `disable`; a `disabled` project advertises `enable`.
- **Default project + transparent migration (3c-6):** a single `default` project is seeded from the prior global config (repo URL, `linear`, `github`, OpenSpec flag); existing runs are backfilled; single-project behavior is byte-identical to pre-3c. No operator action required.
- **Credential encryption (3c-4 / ADR 0013):** envelope (AES-256-GCM data key wrapped by the `DELIVERYLINE_MASTER_KEY` master key), `keyId` indirection for rotation, fail-fast `CREDENTIAL_MASTER_KEY_UNCONFIGURED` only when an encrypted credential exists and the key is missing/blank.

### Format template + conventions (do NOT reinvent)
- **Primary template:** `docs/execution-walkthrough.md` — match its tone, structure, ASCII-panel style, "The one thing to remember" / "Before you start" / numbered steps / "Concepts you just used" / "What is NOT in this walkthrough" sections, and the validator-placeholder blockquote at the very top.
- **Validator placeholder (AC8):** exact pattern from `execution-walkthrough.md` L3 / `pm-loop-walkthrough.md` L3 / `failure-recovery-walkthrough.md` L3 — `> **<Role> walkthrough validator:** \`_____________________________\` (to be named before Epic <X> close)`. For this story: **Project-configuration walkthrough validator**, "before Epic 3c close".
- **Index convention:** `README.md` "Quick links" (L10-24) is the operator-facing index; `docs/glossary.md` "Linked from" (L204-214) lists docs that link *to* the glossary. 3c-13 confirmed the README quick-links is the right index surface and `docs/index.md` is NOT used.
- **Glossary discipline:** `docs/glossary.md` L3-5 — any doc introducing a new term must add its entry **in the same PR**; tracked against NFR43. The Epic 3c vocab section already holds `master key` + `credential encryption` and explicitly reserves `project`/`connector`/`credential` for this story.

### Project Structure Notes
- **New:** `docs/project-configuration-walkthrough.md` (top-level `docs/`, alongside the other `*-walkthrough.md` files — `execution-walkthrough.md`, `pm-loop-walkthrough.md`, `failure-recovery-walkthrough.md`).
- **Modified:** `docs/glossary.md` (+3 entries, replace the placeholder note, +1 "Linked from" line); `README.md` (+1 "Quick links" entry).
- **NOT touched:** any backend/frontend module; `openapi.json`; any test; any CI workflow; and **no `docs/index.md`** (R1).

### Logging Requirements (project-wide standard)
**N/A for this story** — pure documentation, no services or frontend code touched. Recorded explicitly per the project-wide logging task, consistent with 3-36 / 3c-13.

### References
- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-12] — authoritative ACs (L224-239); the epic doc-increment rule (L7) + the "browser-based, no OS-specific instructions" + "named human validator placeholder" requirements.
- [Source: _bmad-output/implementation-artifacts/3c-9-projects-management-ui.md] — the **live UI** this walkthrough describes: the Projects nav landmark + list columns + create/edit form fields (slug-immutable-on-edit) + write-only credential control + the three connection checks + the **collapse-to-label selector** + the **deferred** queue-scoping/per-run-attribution (R2, Open Decision #2). The single most important source for AC1-AC3 accuracy.
- [Source: _bmad-output/implementation-artifacts/3c-8-project-rest-api-crud-and-connection-test.md] — the REST contract behind the UI: write-only `SetCredentialResponse` (id-only), the in-band `TestConnection` per-check shape + the three check enums, status-derived `allowedActions` (no role), default-project-not-disable-able.
- [Source: docs/adr/0013-credential-encryption.md] — canonical master-key + envelope-encryption + threat-model detail to link from AC2 (the env var `DELIVERYLINE_MASTER_KEY` lives here + in the glossary, NOT yet in quickstart/setup-local — R4).
- [Source: docs/glossary.md] — the file to extend (AC5): glossary discipline (L3-5), the Epic-6-owned index note (L8-11), the existing Epic-3c `master key` / `credential encryption` entries (L171-201), the `project`/`connector`/`credential` placeholder (L172-174), and the "Linked from" list (L204-214).
- [Source: docs/execution-walkthrough.md] — the **format/structure template** (3-36, the sibling docs-only story) + the validator-placeholder pattern (L3) + "Concepts you just used" / "What is NOT in this walkthrough" footers (L519-547).
- [Source: README.md#Quick-links] — the operator-facing doc index to extend (L10-24); the convention 3-36 + 3c-13 followed.
- [Source: .github/workflows/ci.yml#docs-link-check] — the gating lychee job (L147-205): internal pass `fail: true` over `docs/*.md docs/**/*.md README.md`; external pass WARN-only; `.lycheeignore` for flaky external URLs (R3).
- [Source: _bmad-output/implementation-artifacts/3c-13-knowledge-capture-documentation-debt-and-memory-trim.md] — the twin docs-only story's scope-discipline + index-convention precedent (README quick-links is the index; `docs/index.md` not used; `format-static-checks` N/A for docs).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context)

### Debug Log References

- Hand-verified internal links/anchors (lychee not installed locally). All relative targets exist
  (`quickstart.md`, `setup-local.md`, `supported-environments.md`, `pm-loop-walkthrough.md`,
  `execution-walkthrough.md`, `glossary.md`, `adr/0013-credential-encryption.md`) and every
  `#anchor` resolves: walkthrough self-anchors (`#step-3--set-credentials-safely`,
  `#step-4--run-the-connection-test`, `#step-6--scope-work-to-the-project`, `#connector-kinds`,
  `#the-default-project-no-action-needed`, `#concepts-you-just-used`,
  `#what-is-not-in-this-walkthrough` — GitHub em-dash slug rule applied: `—` dropped → double
  hyphen) and glossary anchors (`#project`, `#connector`, `#credential`, `#master-key`,
  `#credential-encryption`, `#run`).
- `git status --porcelain` over `docs/` + `README.md` confirms diff scope: new
  `docs/project-configuration-walkthrough.md`, modified `docs/glossary.md` + `README.md`. No
  backend/frontend/test/Flyway/OpenAPI/error-code/CI change. ADRs 0024–0027 in the worktree are
  pre-existing Epic-3d untracked files, not part of this story.

### Completion Notes List

- **Pure-docs capture story (twin of 3-36 / 3c-13).** Wrote one new operator walkthrough, added
  three glossary entries replacing the placeholder, wired two index/link surfaces. Zero production
  code / test / CI-workflow change; `format-static-checks` N/A (docs outside Spotless/Prettier).
- **R1 honored:** indexed via `README.md` "Quick links" + `docs/glossary.md` "Linked from"; did
  NOT create `docs/index.md` (Epic-6 deliverable).
- **R2 honored:** described run↔project association at submit/intake + the collapse-to-label
  selector that shipped; recorded queue-scoping / per-run attribution as a documented backend
  follow-up in "What is NOT in this walkthrough" — not presented as available.
- **R3 honored:** every internal link + anchor hand-verified against heading slugs (the gating
  lychee internal pass is `fail: true`).
- **R4 honored:** credential safety links ADR 0013 + the `master key` / `credential encryption`
  glossary entries; `DELIVERYLINE_MASTER_KEY` described OS-neutrally as a host env var; no new
  setup step invented in quickstart/setup-local.
- **AC8 honored:** validator-placeholder blockquote at the top of the walkthrough, exact pattern
  ("Project-configuration walkthrough validator … to be named before Epic 3c close").
- Capture accuracy verified against the live sources: connection-check enums
  (`repository_reachable` / `ticket_source_auth` / `repository_host_auth`), pass/fail/skipped
  in-band semantics, write-only id-only credential response (`cred_…`), roles
  `ticket_source` / `repo_host`, connector kinds `linear`/`github`/`gitlab` (proof-of-seam),
  status-derived `allowedActions` (no RBAC), default-project-not-disable-able, transparent
  seeded-`default` migration.

### File List

- `docs/project-configuration-walkthrough.md` (new) — the operator project-configuration walkthrough.
- `docs/glossary.md` (modified) — added `project` / `connector` / `credential` entries (replacing the placeholder note); added the walkthrough to "Linked from".
- `README.md` (modified) — added the operator "Quick links" entry for the walkthrough.

## Change Log

- 2026-06-21 — Implemented (bmad-dev-story): wrote `docs/project-configuration-walkthrough.md`; added `project`/`connector`/`credential` glossary entries + glossary "Linked from" back-reference; added README "Quick links" entry. Docs-only, all internal links/anchors hand-verified. Status `ready-for-dev` → `in-progress` → `review`.

## Review Findings

Adversarial code review (bmad-code-review, 2026-06-21) — 3 layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). All 9 ACs + reconciliations R1–R4 PASS; every internal link/anchor verified against live headings; every factual capture-claim verified against live source (connection-check enums, connector roles/kinds, `cred_` prefix, no `projectId` on `GET /api/v1/workflows`); scope confirmed docs-only. One low-severity copy-polish item:

- [x] [Review][Patch] Authorial voice leak — "The three terms **this story** registers" in the operator-facing "Concepts you just used" intro [docs/project-configuration-walkthrough.md] — process vocabulary ("this story") in operator copy; reworded to "The three terms this walkthrough uses, …". Fixed 2026-06-21.
