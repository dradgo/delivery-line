# Sprint Change Proposal — Multi-Project Configuration & Pluggable Connectors (new Epic 3c)

- **Date:** 2026-06-14
- **Author:** Alex (via Correct Course workflow)
- **Trigger type:** New capability + deliberate scope-boundary reversal (multi-project was an MVP non-goal)
- **Affected epics:** New **Epic 3c** inserted between Epic 3 and Epic 4; ripple into Epics 4, 5, 6
- **Change scope classification:** **Major** (reopens architecture non-goals; needs PRD + architecture amendment + new FRs; new UI surface; new security-sensitive credential subsystem)
- **Mode:** Batch (full proposal presented for review, then iterate)

---

## Section 1 — Issue Summary

DeliveryLine is **single-project by construction today.** The three things this change targets are all *global application configuration*, not per-project data:

- **Git URL** → `deliveryline.workflow.repos.*` → one `RepoConfig`. Its Javadoc: *"Single configured pilot repository (1:1; a real Linear↔GitHub mapping is deferred to 3.32/3.33)."*
- **Ticket connector** → global `deliveryline.linear.*` (one Linear workspace).
- **OpenSpec mode** → global `deliveryline.runner.openspec.enabled` (the opt-in flag from the 2026-06-13 proposal).

The change introduces a first-class **`Project`** aggregate so a single DeliveryLine instance can process **multiple projects**, each carrying its own repository binding, ticket-source + repo-host connector (of selectable *type*), encrypted credentials, and run options (incl. OpenSpec mode). Every `workflow_run` becomes scoped to a `project_id`.

**This is a deliberate reversal of a documented MVP boundary.** `architecture.md:272` defers *"tenant isolation, multi-project RBAC, OS keychain support, app-level encryption"* to post-MVP. The decisions below (taken with Alex via Correct Course) knowingly pull **multi-project configuration** and **app-level credential encryption** *into* scope, while leaving **multi-user authentication / RBAC** out (projects are configuration, not access-control tenants).

**Decisions already taken (this session):**
- **D1 — Positioning: pilot-blocking.** The epic slots **between Epic 3 and Epic 4**; Epics 4–6 build on the Project entity. Multi-project must exist before the pilot.
- **D2 — Management surface: DB entity + REST + UI.** `Project` is a Flyway-backed entity with full CRUD via REST and a React management screen (not just CLI/config seed).
- **D3 — Connector ambition: per-project pluggable types.** Each project selects its ticket-source type (Linear vs future Jira/…) and repo-host type (GitHub vs future GitLab/…). Built **on top of** stories 3.32/3.33 — which become hard prerequisites and get *promoted* from "forward-looking prudence" to load-bearing, and *widened* from global-single-instance to per-project resolution.
- **D4 — Credentials: secrets in DB, encrypted.** Per-project connector secrets are stored encrypted at rest (envelope encryption, master key from host env). **Reopens the app-level-encryption non-goal** → requires a key-management story, redaction guarantees, and a security review.
- **D5 — Numbering: non-colliding insert label = "Epic 3c".** Avoids renumbering E4→E5→E6 across the ~490 KB `sprint-status.yaml` and hundreds of `3.x`/`4.x` cross-references. Uses the established letter-suffix mechanic (3a/3b) purely as an ordinal that sorts between 3 and 4; it is a **standalone epic**, not a slice of Epic 3 (Agent Execution).

---

## Section 2 — Impact Analysis

### Epic Impact

| Epic | Impact |
|------|--------|
| **Epic 3 (Agent Execution)** | Stories **3.32 + 3.33** become **prerequisites** for Epic 3c and are widened (per-project resolution, connector registry). No change to 3.32/3.33 intent — they already define vendor-neutral ports + capability detection + `kind` selection; Epic 3c lifts `kind` from a single global value to a per-project binding. |
| **Epic 3c (NEW)** | The new epic. ~12 stories (Section 4). |
| **Epic 4 (Recovery)** | `IntegrationConflictDetectionJob` (epic-04 L383) iterates `integration_links` and queries the adapter — must now resolve the adapter **per project** and compare per-project cached metadata. Operator console run views gain a project column/filter. |
| **Epic 5 (Export/Retention)** | Redaction guarantees (FR50/FR52) must cover **per-project credentials** (the new encrypted store is a new secret surface). Retention windows + the `repoRemovedFromSource` job (epic-05 L177) become per-project. Pilot-measurement reports gain a project dimension. |
| **Epic 6 (Pilot Docs)** | New "configure a project" onboarding step; concept vocabulary (NFR43) gains `project`, `connector`, `credential`. |

### Artifact Conflicts

| Artifact | Impact |
|----------|--------|
| **PRD** | **Amendment required.** Add multi-project configuration to MVP scope; carve the non-goal so it excludes *multi-user RBAC* only, not *multi-project config*; add **new FRs (proposed FR56–FR63)** for project CRUD, per-project connector selection, encrypted credentials, run↔project association, connection testing, and config migration. Add a Project to the domain glossary. |
| **Architecture** | **Amendment required.** Edit `architecture.md:272` non-goals (remove multi-project config + app-level encryption from "deferred"; keep multi-user auth/RBAC/tenant *isolation* deferred). Add `projects` + `project_credentials` to the data-model list (L282). New ADRs: multi-project decision reversal, per-project connector resolution, credential encryption. Note the encryption primitive is the first app-managed secret-at-rest. |
| **UX Design** | **Amendment required.** New "Projects" management area: list, create/edit form (repo URL, connector type pickers, credential entry, OpenSpec + run-option toggles), connection-test affordance, and a project selector in the run/queue context. Must meet WCAG 2.1 AA and reuse the Epic 2 design system + allowed-action model. |
| **Epics** | New file `epic-03c-multi-project-configuration.md`; `epics.md` Epic List gains an Epic 3c entry between Epic 3 and Epic 4; Epic 4/5/6 FR-coverage notes updated for the project dimension. |
| **Sprint status** | `sprint-status.yaml` gains the Epic 3c story block (after Epic 3's stories, before Epic 4's), and `next-active` markers updated per the active-slice convention. |
| **Foundation gate (story 1.23)** | Widened: new central registries (connector-kind, project-status) drift-tested; `prj_`/`cred_` prefixes registered; credential-redaction adversarial fixtures added (AR10 parity); project entity drift test. |

### Technical Impact & Constraints (decisive ones)

1. **Schema:** next Flyway is **V14** (V13 is current head). New tables `projects` (`prj_` prefix) and `project_credentials` (`cred_` prefix, encrypted payload); `project_id text` FK added to `workflow_runs` (and likely `integration_links`). Enum-likes (`project_status`, `connector_kind`) as `text` + CHECK per the data-architecture rules.
2. **Config inversion is the riskiest refactor.** Today's global `RepoConfig` / `LinearProperties` / `GitHubProperties` / `runner.openspec` must become per-project, resolved at run time from the Project bound to the run. A **default seeded project** migrates existing global config so current flows keep working (backward-compat) — this is the seam that de-risks the inversion.
3. **Per-project connector resolution rides 3.32/3.33.** The vendor-neutral ports + capability detection already exist; the new work is a `ProjectConnectorResolver` that, given a Project, returns the correctly-typed + correctly-credentialed `TicketSourceAdapter`/`RepositoryHostAdapter` instance. The existing `LINEAR_GITHUB_REPO_MISMATCH` guard (NFR20, story 3.14 AC4) becomes project-scoped.
4. **Credential encryption is a new security primitive.** Envelope encryption with a host-env master key; ciphertext at rest; plaintext only in memory at egress; **never** logged or exported (extends `RedactionPolicyService` + the export-redaction gate). Needs a dedicated key-management + redaction story and a security review — this is the part that most violates the prior "no app-level encryption" posture.
5. **Run↔project association threads everywhere a run is created or dispatched:** CLI `submit`, REST intake, runner bundle composition (project's repo + connector context), workspace prep, Linear completion sync, and the queue. Intake must resolve which project a ticket belongs to (explicit project ref, or by ticket-source binding).
6. **Below multi-user auth altitude.** Projects are configuration records, not access-control tenants — no RBAC, no per-user visibility scoping. Single-operator posture preserved (keeps the auth non-goal intact).

---

## Section 3 — Recommended Approach

**Direct Adjustment with scope-boundary amendment** — insert **Epic 3c** between Epics 3 and 4, sequenced **after stories 3.32 + 3.33 merge** (the connector abstractions it builds on).

**Rationale:** the four ambitious decisions (pilot-blocking, full CRUD+UI, pluggable types, encrypted secrets) make this a genuine MVP-scope expansion, not a gap-fill — so it correctly carries PRD + architecture amendments and new FRs rather than living as derived story-level prudence. Building on 3.32/3.33 means the vendor-neutral seams are reused, not reinvented. The default-seeded-project migration keeps every existing flow green during the inversion.

- **Effort:** **High** (new entity + config inversion + new security subsystem + new UI area + downstream ripple).
- **Risk:** **High**, concentrated in (a) the global→per-project config inversion touching live run paths, and (b) the credential-encryption subsystem reopening a security non-goal. Mitigations: default-seeded-project backward-compat seam; spike the encryption primitive + security review before wiring CRUD; keep pluggable-type surface to *one* extra registered kind beyond Linear/GitHub to prove the seam without pulling full vendor support forward.

---

## Section 4 — Detailed Change Proposals

> Story list is an **AC-shape sketch** to size and sequence the epic; each becomes a full context-engineered file via `bmad-create-story`. Sequenced foundation → domain/persistence → resolution → security → application → REST → UI → cross-cutting, matching the house ordering pattern.

**Prerequisites (existing Epic 3 stories, must merge first):** 3.32 (TicketSourceAdapter), 3.33 (RepositoryHostAdapter).

| Story | Title | Shape |
|-------|-------|-------|
| **3c-1** | Flyway V14 — `projects` + `project_credentials` schema + `project_id` FK | `projects` (`prj_`), `project_credentials` (`cred_`, encrypted blob + key-id + algo), `workflow_runs.project_id` FK (+ `integration_links.project_id`). `project_status`/`connector_kind` as `text` + CHECK. Nullable `project_id` backfilled to the default project (3c-6). |
| **3c-2** | Project domain aggregate + central registry entries + drift tests | `Project` domain model, `ProjectStatus` enum, `ConnectorKind` registry (ticket-source kinds + repo-host kinds), `prj_`/`cred_` in the prefix registry; registry drift tests vs domain enums + DB CHECK + API schema (extends story 1.4 pattern). |
| **3c-3** | Per-project connector resolution over 3.32/3.33 | `ProjectConnectorResolver` returns the correctly-typed `TicketSourceAdapter`/`RepositoryHostAdapter` for a Project (lifts global `...kind` to per-project); capability checks reused; project-scoped `LINEAR_GITHUB_REPO_MISMATCH`. |
| **3c-4** | Credential encryption primitive + key management (security spike → impl) | Envelope encryption, master key from host env, key-id/rotation hooks; `ADR-00xx-credential-encryption`; **security review gate**. Reopens the app-level-encryption non-goal deliberately. |
| **3c-5** | Encrypted credential store + redaction integration | `project_credentials` CRUD at the application layer; plaintext in-memory only at egress; extend `RedactionPolicyService` + adversarial secret fixtures (AR10) + export-redaction gate so credentials never log/export. |
| **3c-6** | Default-project migration + config-inversion seam | Seed a "default" Project from today's global config (`workflow.repos`, `linear`, `github`, `runner.openspec`); run-time config resolution prefers the run's Project, falls back to default; **flag/behavior parity for existing single-project flows**. |
| **3c-7** | Run ↔ Project association across intake + dispatch | CLI `submit` + REST intake resolve/bind `project_id`; bundle composition, workspace prep, Linear completion sync, and queue all derive repo/connector/openspec from the run's Project. |
| **3c-8** | Project REST API (CRUD + connection test) | List/create/read/update/disable endpoints; credential set/rotate (write-only, never returned); `testConnection` (git reachable + ticket-source auth + repo-host auth, capability-aware); Problem Details codes; idempotency; backend-reported allowed actions. |
| **3c-9** | Projects management UI | React Projects list + create/edit form (repo URL, connector-type pickers, credential entry, OpenSpec + run-option toggles), connection-test affordance, project selector in run/queue context; design-system reuse; WCAG 2.1 AA; allowed-action-driven. |
| **3c-10** | Doctor + observability for projects | `doctor` lists configured projects + connection-test status + credential-presence (PASS/WARN, no secret values); per-project health surfaced. |
| **3c-11** | Foundation-gate widening + test suite extension | Gate asserts new registries, prefixes, credential redaction, project drift, per-project resolution; Vitest/Playwright/axe for the UI; integration tests for config inversion + per-project dispatch; coverage thresholds for new packages. |
| **3c-12** | Project-configuration documentation increment | `docs/project-configuration-walkthrough.md` — add/edit a project, choose connector types, enter credentials safely, test the connection, run options/OpenSpec; glossary + concept-vocabulary (NFR43) update. |

**Downstream amendments (handled by widening existing stories, NOT new Epic 3c stories):**
- **Epic 4:** `IntegrationConflictDetectionJob` per-project resolution; operator views gain project filter.
- **Epic 5:** redaction covers `project_credentials`; retention + `repoRemovedFromSource` per project; measurement reports gain project dimension.
- **Epic 6:** project onboarding step in the consolidated tutorial.

**Planning-artifact edits:**
- `prd.md`: MVP scope + non-goals carve-out + FR56–FR63 + glossary (drafted in Step 3 incremental review).
- `architecture.md`: non-goals edit (L272), data-model edit (L282), new ADRs.
- `ux-design-specification.md`: Projects management area spec.
- `epics.md`: Epic List entry for Epic 3c; E4/E5/E6 coverage notes.
- New `epic-03c-multi-project-configuration.md`.
- `sprint-status.yaml`: Epic 3c block + `next-active` markers.

---

## Section 5 — Implementation Handoff

- **Scope classification: Major** — fundamental scope expansion reversing two documented non-goals; needs PM + Architect involvement before story creation.
- **Handoff sequence:**
  1. **Approve this proposal** (Step 5 of Correct Course).
  2. **PM (`bmad-edit-prd`)** — amend MVP scope + non-goals + add FR56–FR63 + glossary.
  3. **Architect (`bmad-create-architecture` / edit)** — amend non-goals (L272) + data model (L282); author ADRs (multi-project reversal, connector resolution, credential encryption).
  4. **UX (`bmad-create-ux-design` / edit)** — Projects management area spec.
  5. **PO/Dev (`bmad-create-epics-and-stories` then `bmad-create-story 3c-1`…)** — write `epic-03c-...md` + per-story files; add the Epic 3c block to `sprint-status.yaml`.
  6. **Dev (`bmad-dev-story`)** — implement after 3.32/3.33 merge, in story order; **3c-4 (encryption) gated on a security review** before 3c-5 wiring.
- **Success criteria:** existing single-project flows stay green via the default-seeded project; a second project can be created (UI + REST) with its own connector types + encrypted credentials; a run is correctly scoped to its project; credentials never appear in logs/exports; foundation gate + Epic 3c test suite green; PRD/architecture/UX amendments merged.

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Config inversion breaks live run paths** (biggest unknown) | Default-seeded-project backward-compat seam (3c-6); inversion lands behind parity tests asserting byte-identical single-project behavior before any second project exists. |
| **Credential encryption reopens a security non-goal** | Dedicated spike + ADR + **security review gate** (3c-4) before CRUD wiring; redaction + export-gate coverage (3c-5); master key from host env, never in DB. |
| **Pluggable-type scope creep** (full Jira/GitLab support pulled forward) | Prove the seam with *one* extra registered kind beyond Linear/GitHub (or a documented stub kind); real vendor implementations stay post-pilot. |
| **Pilot-blocking positioning delays the pilot** | Epic 3c is sequenced tight after 3.32/3.33; if schedule slips, fallback is to ship with the default project only (multi-project dormant) — but this contradicts D1, so confirm at first checkpoint. |
| **Renumber churn** | Avoided via the "Epic 3c" non-colliding label (D5). |
| **Downstream epics assume single project** | E4/E5/E6 widening enumerated above; flagged now so their stories carry the project dimension when authored. |
