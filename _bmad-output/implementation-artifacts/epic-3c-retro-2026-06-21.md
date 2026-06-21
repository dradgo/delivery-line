# Epic 3c Retrospective — Multi-Project Configuration & Pluggable Connectors

**Date:** 2026-06-21
**Facilitator:** Amelia (Developer)
**Project Lead:** Alex
**Epic Status:** Complete-as-scoped (13/13 stories `done`)
**Retrospective Type:** Fourth retrospective (integrates Epic 3 retro D1–D4 follow-through)

---

## 1. Epic Summary

| Dimension | Result |
|---|---|
| Scope | First-class `Project` aggregate; global single-pilot config **inverted** into per-project data; per-project selectable ticket-source / repo-host connectors + encrypted credentials |
| Stories shipped | **13/13 done** — foundation (3c-1/2), connector + crypto (3c-3/4/5), config-inversion (3c-6/7), surfaces (3c-8/9/10), cross-cutting (3c-11/12/13) |
| Critical defects shipped | **0** (fifth consecutive epic) |
| Foundation gate | Widened to **20 named contracts** (#16–#20 delegate-run Epic 3c tests, zero re-authoring) |
| Security | ADR-0013 credential encryption signed off; AES-256-GCM envelope cipher, host-env master key (`DELIVERYLINE_MASTER_KEY`), write-only credential store |
| New domain error codes | 5 total, all auto-covered by `ProblemDetailsCoverageFoundationContract` |
| Single-project parity | **Byte-identical** to pre-3c — proven by test (default-project seed + backfill) |

**Goal achieved:** A single local operator can configure and govern **multiple projects** from one DeliveryLine instance — each carrying its own repository binding, *selectable* ticket-source / repo-host connector kinds, encrypted per-project credentials, and run options. The global configuration that drove a single pilot repo is inverted into per-project data and migrated transparently to a seeded `default` project, so existing single-project flows continue byte-identically. Two prior MVP non-goals — multi-project configuration and application-level credential encryption — were deliberately reversed.

**Headline:** The three-epoch documentation slip is **broken.** Story 3c-13 finally wrote `registry-recipe.md`, `frontend-test-patterns.md`, and `snapshots-vs-assertions.md` — the A1/A6/A9 → B1/B2/B3 docs that chased three epics — and the team applied its own lesson for the first time.

---

## 2. Team Participants

- **Amelia (Developer)** — facilitator
- **Alice (Product Owner)** — pilot scope, non-goal reversal
- **Charlie (Senior Dev)** — connector resolution, crypto/port seams, config-inversion
- **Dana (QA Engineer)** — credential redaction, foundation-gate widening, coverage floors
- **Winston (Architect)** — vendor-neutral adapter continuity, Epic 3c → 3d contract
- **Alex (Project Lead)** — convener

---

## 3. Successes & Strengths

### 3.1 The doc-debt slip is broken — *Alex's #1 from the Epic 3 retro*
D1–D3 shipped in story 3c-13: `docs/patterns/registry-recipe.md`, `docs/testing/frontend-test-patterns.md`, `docs/testing/snapshots-vs-assertions.md` — sourced from `MEMORY.md` topic files, verified against live source. The lessons that slipped three epics in a row are now committed docs. The team applied its own retro lesson for the first time.

### 3.2 Multiple projects, multiple sources — the foundation Alex wanted
The `Project` aggregate enables developing multiple projects at once, each with its own ticket-source and repository-host. 3c-3 lifted the global `kind` selection into a per-project `ConnectorKind` registry resolved at run time, and **proved the seam with a GITLAB stub** (a third kind, registered, degrading gracefully) — so a real GitLab/Jira project slot already exists, exercised, not theoretical.

### 3.3 Seam-and-lazy-wiring enabled story parallelism
13 stories landed without a giant merge-train. 3c-3 shipped a credential source returning empty (waiting on 3c-5); 3c-6 laid config-inversion plumbing without reading projects on the hot path; 3c-7 threaded them through. Each story stood up its seam; the next consumed it — so the foundation could be developed "all at once."

### 3.4 Credentials that can't leak
Secrets are **write-only, encrypted at rest, never read back**, redacted across logs / events / artifacts / exports. For a feature whose whole point is storing per-project API tokens, zero leaks. The envelope-encryption frame (`[version][nonce1][wrappedDekLen][wrappedDek][nonce2][ct]`) is rotation-friendly via `keyId` indirection.

### 3.5 Architectural foresight keeps compounding
3-32/3-33's vendor-neutral `TicketSourceAdapter` / `RepositoryHostAdapter` were *the* foundation 3c-3 lifted into per-project resolution. Port-placement discipline (cipher port in `application.security`, impl in `infrastructure.crypto` to respect the ArchUnit boundary) is now the canonical pattern for security-sensitive subsystems. Fourth straight epic of foresight paying forward.

### 3.6 Foundation gate widened with zero re-authoring
3c-11 added contracts #16–#20 that *delegate-run* existing Epic 3c tests; changing a test reds exactly one contract tagged `[story 3c.N]`. Regression coverage without duplicated assertions.

---

## 4. Challenges & Growth Areas

### 4.1 Plan artifacts go stale on concrete details
Epic + proposal both said "Flyway V14"; real head had moved to V16 → 3c-1 shipped as V17, 3c-3's GITLAB proof needed V18. Every foundation story opened with a "what's the real version" reconciliation. Stale-on-write version numbers and site counts. (→ A2, A3)

### 4.2 Fan-out width consistently under-sized
`connectorKind()` on the ports = 6 adapters + every test fake. `WorkflowRunCreatePort.create` `+projectId` = ~14 construction sites. `DoctorProbeAdapter` 5-overload ctor chain threaded everywhere. Mechanical, not hard — but wide, and planning never sized the width. (→ A2)

### 4.3 The capture container is overflowing
D4 (trim `MEMORY.md` < 24.4KB) was *done* in 3c-13 (32.5KB → 23.7KB) and **regressed to 31.9KB within the same epic**, loading only partially again. ~50 index entries carry paragraph detail. A per-epic manual trim is a tax we'll forget half the time — the fix must be structural. (→ A1)

### 4.4 Predicted flakes recurred exactly where the docs warned
Micrometer weak-ref gauge NaN hit 3c-10's `ProjectHealthMetricsBinder` again; Hibernate `created_at`-read-on-insert trap got 3c-6; wire-`null`-vs-TS-`optional` showed up in 3c-9. The lessons are now written down — but the team still stepped in each one before reaching them (see 4.3: partially-loaded index = guardrail doesn't fire).

---

## 5. Previous Retrospective Follow-Through (Epic 3 → Epic 3c)

| Epic-3 commitment | Status | Evidence |
|---|---|---|
| **D1** Write `registry-recipe.md` | ✅ Done | Story 3c-13 |
| **D2** Write `frontend-test-patterns.md` | ✅ Done | Story 3c-13 |
| **D3** Write `snapshots-vs-assertions.md` | ✅ Done | Story 3c-13 |
| **D4** Trim `MEMORY.md` < 24.4KB | ⚠️ Done-then-regressed | 3c-13 trimmed to 23.7KB; back to 31.9KB by epic end |
| **P1** Mock-vs-real recovery test bar → Epic 4 planning | ⏳ Carried | Re-applied to Epic 3d as P2 below |
| **P2** Audit recovery / Decision Bar surface | ⏳ Epic 4 scope | Not in 3c scope; remains an Epic 4 prep item |

**The honest signal:** The hard items inverted this epic — we finally codified the lessons (D1–D3). The new wrinkle is meta: the *capture container itself* (`MEMORY.md`) overflowed right after being trimmed. Same root lesson as the doc slip — "capture in the durable, committed place, not the convenient ephemeral one" — now applied to memory itself via A1.

---

## 6. Significant Discovery — Epic 3d Plan Impact

**No epic update required.** Epic 3d was authored after Epic 3c (same 2026-06-21 sprint-change proposal) and already accounts for the Project aggregate, per-project credentials, and `ProjectConnectorResolver`.

**Latent assumption to flag (not a blocker):** TD3 — run-read DTOs carry no `projectId` field (deferred backend-blocked by 3c-7/3c-9). Epic 3d makes this assumption in **two** stories (3d-5 per-run log viewer, 3d-8 soft-hide queue state). Pre-emptively adding `projectId` to run-read DTOs early in 3d (action P3) unblocks both at once. Headline input to 3d sprint planning.

---

## 7. Next Epic Preview & Dependencies — Epic 3d

**Title:** Per-Step Execution Control, Observability & Manual Execution. 10 stories, `in-progress` (3d-1/3/5/8 already `ready-for-dev`).

**Scope:** per-project reviewer model (advisory second-LLM verdict); `manual` runner kind + new `WaitingForManualExecution` state; live + historical step-log viewing (SSE); read-only diagnostic console (ADR-0025, security-gated); post-execution provider usage/limit status (spike-gated); soft hide/archive of obsolete executions (ADR-0027, archive-not-delete).

**Dependencies on Epic 3c (delivered — watch these):**
- Reviewer model (3d-1/2) reuses 3c `project_credentials` + cipher + `ProjectConnectorResolver` (adds a `reviewer` connector role).
- `manual` runner kind (3d-3) + `WaitingForManualExecution` selectable per-project via 3c config.
- The same rough edges 3c left (TD1–TD3) will be magnified by 3d — audit before building on them.

---

## 8. Action Items

### Process / Capture
| ID | Action | Owner |
|---|---|---|
| A1 | **Structural memory fix:** enforce one-line (<200 char) `MEMORY.md` index entries; lift all paragraph detail into `docs/` topic files; archive per-story reconciliation memories once folded into a pattern doc. Stops per-epic re-overflow. | Alex |
| A2 | **Size fan-out width in planning:** a story adding a port method / record component / probe lists the impl + test-fake + construction sites up front (grep-first). Fold into `registry-recipe.md`. | Team |
| A3 | **Stamp real Flyway head in story files at creation**, not the proposal's stale number — foundation stories open with the actual `V_n`. | Team |

### Technical Debt carried into Epic 3d
| ID | Item | Note |
|---|---|---|
| TD1 | Ticket-source **identity** routing (deferred 3c-3) | 3d-3 manual dispatch + reviewer binding assume it |
| TD2 | Master-key **rotation** mechanics (deferred 3c-5) | 3d adds `reviewer` role on the same un-rotatable key |
| TD3 | Per-project **queue filtering + run attribution** (deferred 3c-7/3c-9, backend-blocked) | 3d-5 / 3d-8 both want it; run DTOs need a `projectId` field |

### Epic 3d Preparation
| ID | Action | Owner |
|---|---|---|
| P1 | Hold the **security/spike gates**: 3d-6 (ADR-0025 console) + 3d-7 (provider-limit spike) don't close until sign-off — mirror the 3c-4 → 3c-5 gate discipline. | Charlie / Winston |
| P2 | Apply the **mock-vs-real bar** to 3d-2 (reviewer run) + 3d-5 (live logs): one real run each before `done`. | Dana |
| P3 | Decide whether to add `projectId` to run-read DTOs **early in 3d** (unblocks TD3 for both 3d-5 and 3d-8 at once). | Alex / Winston |

### Team Agreements
- **"Pilot-ready" = one real run end-to-end** — held since Epic 3; extend the bar to 3d's reviewer + live-log surfaces (P2).
- **Lessons get codified in committed docs, not ephemeral memory** — the Epic-3 doc slip taught this; A1 finally applies it to `MEMORY.md` itself.
- **Security/spike gates block dependents** — 3c-4 → 3c-5 held; keep it for 3d-6 / 3d-7.

---

## 9. Readiness Assessment — Epic 3c

| Dimension | Status | Notes |
|---|---|---|
| Stories complete | ✅ 13/13 `done` | No stale bookkeeping this epic |
| Foundation gate | ✅ Green | Widened to 20 contracts (#16–#20) |
| Testing & quality | ✅ Strong | 0 criticals (5th straight); coverage floors met (`application.project` ≥0.80, `infrastructure.crypto` ≥0.90) |
| Security | ✅ Signed | ADR-0013 credential encryption review complete |
| Single-project parity | ✅ Proven | Byte-identical default-project seed + backfill |
| Deployment / stakeholder | N/A | Solo pilot project; no external gate |
| Standing weakness | ⚠️ Capture container | `MEMORY.md` re-overflowed; A1 is the structural corrective |

**Verdict:** Epic 3c is complete-as-scoped and complete against the foundation gate. The named Epic-3 weakness (lessons codified poorly) *inverted* — D1–D3 shipped — exposing a meta-version of the same problem: the memory index outgrew its limit immediately after trimming. A1 makes the fix structural so it stops recurring.

---

## 10. Key Takeaways

1. **The foundation for multiple projects is real.** A first-class `Project` aggregate, per-project selectable connectors (GITLAB stub proves the seam), and encrypted per-project credentials — with single-project flows byte-identical.
2. **The three-epoch doc slip is broken.** 3c-13 shipped D1–D3. The team applied its own retro lesson for the first time.
3. **The capture container is now the bottleneck.** `MEMORY.md` overflowed again within the same epic it was trimmed — the fix is structural (one-line index, detail in committed docs), not another manual trim.
4. **Seam-and-lazy-wiring is the parallelism engine.** 13 stories landed without a merge-train because each stood up a seam the next consumed.
5. **Plan artifacts go stale on concrete details.** Flyway versions and fan-out site counts are wrong-on-write; A2/A3 push the real numbers into story files at creation.
6. **Epic 3d magnifies 3c's deferred edges.** TD1–TD3 (identity routing, key rotation, project-scoped queue reads) all land in 3d — P3 pre-empts the worst one.

---

## 11. Next Steps

1. Review this retrospective.
2. Land **A1** (structural memory fix) before `MEMORY.md` overflow degrades the guardrail further.
3. Carry **TD1–TD3** into Epic 3d planning as known debt the epic will touch.
4. At Epic 3d execution: hold the **security/spike gates (P1)**, apply the **mock-vs-real bar (P2)**, and decide **`projectId` on run DTOs early (P3)**.
5. Continue Epic 3d (`bmad-create-story` / `bmad-dev-story`) — foundation stories (3d-1/3/5/8) are already `ready-for-dev`.

---

*Retrospective complete. Epic 3's job was to make one real run real; Epic 3c's job was to make many projects possible — done, on a foundation that doesn't leak. Epic 3d's job: make per-step control, observation, and manual execution real on top of it.*
