# Sprint Change Proposal — 2026-05-19

**Project:** DeliveryLine
**Triggering Epic:** Epic 1 → Epic 2 transition
**Trigger Source:** Epic 1 retrospective (2026-05-19) — Significant Discovery
**Author:** Alex (Project Lead) + Amelia (Developer, facilitating)
**Status:** Approved (all four edits accepted by Project Lead 2026-05-19)
**Mode:** Incremental review (each edit individually approved)

---

## 1. Issue Summary

The Epic 1 retrospective surfaced a **threat-model shift** for Epic 2 that was not
anticipated during PRD/epic planning:

**Core problem:**

- Epic 1 deferred redaction-policy gaps to `deferred-work.md`:
  - **F19** — `Idempotency-Key` HTTP header not redacted; `credential`, `bearer`,
    `private` patterns missing from `RedactionPolicy` allowlist
  - **F20** — PEM-formatted blocks (RSA/EC private keys, certificate bundles) and
    bundle JSON shapes (nested `secret`/`token`/`api_key`/`credential` keys) not
    covered by pattern matching
- These gaps were **acceptable for the CLI pilot** because CLI output is not
  rendered in a UI — operators see redaction-pending sentinels or raw output in
  their own terminal, not in a shared multi-actor interface
- **Epic 2 is the first epic that renders artifact content in a UI** — specifically
  stories 2.15 (Run Review Queue Item), 2.17 (Artifact Review Panel — Spec Variant),
  and 2.18 (Clarification Region) all surface backend-sourced artifact content to a
  Product Manager via React components rendered through `SafeMarkdownRenderer`
- Story **2-24** as currently planned scopes to "artifact content sanitization for
  untrusted runner output" — i.e., **HTML/XSS sanitization** (scripts, iframes,
  `javascript:` URLs, event handlers, polyglot payloads). It does **not** currently
  scope to closing F19/F20 redaction gaps. Sanitization and redaction are different
  threat models:
  - Sanitization = "this content might try to execute code"
  - Redaction = "this content might contain secrets the backend missed"
- **Result:** if 2-24 ships with its current scope, untrusted runner output rendered
  in the PM UI can leak PEM blocks, bundle JSON shapes, idempotency headers, and
  credential-pattern strings that the backend redactor missed

**Secondary issue (bundled into this sprint-change per Project Lead's scope decision):**

- Story 2.1 AC7 ("frontend build works on Windows 11 PowerShell + Ubuntu 22.04 +
  macOS 14+ — verified by the CI matrix job from story 1.21") is **aspirational
  but not verified on Windows** — Epic 1 retro revealed that 1.21's Windows
  doctor-smoke job was collapsed to Ubuntu-only as a pragmatic unblock, so the
  inherited verification doesn't actually exercise Windows
- Without enforcement, Epic 2 risks the same Linux-only-assumption-bleed pattern
  Epic 1 hit in story 1.17 — but now compounded with frontend-tooling-on-Windows
  surprises (line endings, path lengths, dev-server ports, file locking)

**Evidence:**

- `_bmad-output/implementation-artifacts/deferred-work.md` F19/F20 entries (~line 80
  region)
- `_bmad-output/implementation-artifacts/epic-1-retro-2026-05-19.md` section 6
  "Significant Discovery — Epic 2 Planning Review Required"
- Story 2.24 current ACs (epics.md lines 1357–1376) — 12 ACs all sanitization,
  zero redaction
- Story 2.1 AC7 (epics.md line 855) + Epic 1 story 1.17/1.21 outcome (Windows
  doctor-smoke collapsed to Ubuntu-only)

**Issue category:** Misunderstanding of original requirements + downstream-dependency
discovery — not a stakeholder pivot, not a failed approach, not a new external
requirement.

---

## 2. Impact Analysis

### 2.1 Epic Impact

- **Epic 1:** No impact. Epic 1 is closed (23/23 stories done, retrospective complete,
  sprint-status `epic-1-retrospective: done`).
- **Epic 2:** Localized scope expansion + dependency-edge tightening. Three artifacts
  modified (story 2.1, story 2.24, epic header), one new artifact created (spike
  charter). **No story renumbering** — story IDs remain stable per readiness-report
  guidance.
- **Epic 3 / 4 / 5 / 6:** No impact. Epic 2 closure unblocks them as planned.

### 2.2 Story Impact

| Story | Change | Type |
|---|---|---|
| 2.1 | AC7 tightened; AC8 refined; new AC9 (spike outputs); new AC10 (foundation-gate extension); per-story prerequisite note for spike | Scope expansion |
| 2.15 | Dependency note appended: cannot merge before 2.24 | Dependency edge |
| 2.17 | Dependency note appended: cannot merge before 2.24 | Dependency edge |
| 2.18 | Dependency note appended: cannot merge before 2.24 | Dependency edge |
| 2.24 | Title renamed; user-story expanded; ACs 13–18 appended (backend redaction extension, frontend second-pass filter, shared `runner-contracts/redaction-policy.json` spec, contract test, UX placeholder, deferred-work closure) | Scope expansion |
| New | `docs/spikes/2026-05-frontend-on-windows.md` spike charter | New artifact |

### 2.3 Artifact Conflicts

| Artifact | Conflict | Action |
|---|---|---|
| **PRD** | No conflict | None — PRD already requires "untrusted runner output handled safely"; this is sharpening, not contradiction |
| **Architecture doc** | Minor — `RedactionPolicy` (E1 story 1-10) gets extended; the shared `runner-contracts/redaction-policy.json` spec is new | Add a brief note to architecture doc when 2-24 ships, referencing the extended pattern set + JSON spec contract |
| **UX spec** | No conflict | The rendered output now shows `[REDACTED: <classification>]` placeholders — visually distinguishable from author-written `[REDACTED]` literals (per 2.24 AC17); this is additive UX, not a redesign |
| **`deferred-work.md`** | F19/F20 entries need status update | Update F19 + F20 entries to `closed by 2-24 (per sprint-change-proposal-2026-05-19.md)` when 2-24 ships |
| **`sprint-status.yaml`** | No new stories added; no renames | No change required |
| **CI helpers** | New `check-story-dependencies` script needed | Extension of branch-protection helpers from 1-21; implemented as follow-up task in story 2.1 |

### 2.4 Technical Impact

- **Backend:** `RedactionPolicy` service (1-10) gets a one-time pattern-set
  extension + fixture additions. Reuses existing pattern; no new architectural seams.
- **Frontend:** New `src/lib/sanitization/redactionFilter.ts` second-pass filter
  (~200–300 lines). Consumes shared `runner-contracts/redaction-policy.json` spec.
- **Contract layer:** New `runner-contracts/redaction-policy.json` artifact + a
  contract test asserting backend/frontend pattern parity (extension of existing
  `RegistryContractTest` pattern from Epic 1 story 1-4).
- **CI:** Frontend-build matrix (Ubuntu + Windows) added in story 2.1; foundation-gate
  `needs:` chain extended; new `check-story-dependencies` enforcement check added.
- **Documentation:** Spike charter file + spike findings (populated during execution).

---

## 3. Recommended Approach

### Path forward selected: **Option 1 — Direct Adjustment**

The change is localized: scope expansion on two existing stories (2.1, 2.24), a
dependency-edge declaration in the epic header + three downstream story notes, and
a new spike charter. No epic added, no epic removed, no MVP scope reduction, no
rollback (Epic 1 is closed). Effort is bounded; risk is low; the alternative paths
were not viable:

- **Option 2 (Rollback)** — N/A. Epic 1 is closed; the deferred F19/F20 items were
  legitimately acceptable at the time they were deferred (CLI pilot threat model).
  Nothing to roll back.
- **Option 3 (MVP Review)** — Not warranted. The PRD's MVP scope is preserved; the
  pilot still ships the PM-review loop with the same surface area. The change
  *tightens* a security threat-model gap inside that scope, not reduces or expands
  the scope itself.

### Rationale for Direct Adjustment

- **Effort:** Low–Medium. Backend pattern extensions are mechanical; frontend
  second-pass filter is ~200–300 lines of straightforward code; contract test
  reuses Epic 1's `RegistryContractTest` pattern; CI changes are matrix-extension +
  one new gate helper.
- **Risk:** Low. No architectural shifts. Reuses existing seams (RedactionPolicy
  service, runner-contracts module, foundation-gate CI). Defense-in-depth design
  (backend + frontend + contract test) ensures pattern drift is impossible.
- **Timeline impact:** Story 2-24's effort grows by an estimated 30–50% (6 new ACs
  on top of 12); story 2-1's effort grows by ~20% (3 new ACs + AC7 enforcement);
  spike adds ~3 hours upfront before 2.1 starts. Net: meaningful but contained
  cost, with the alternative (discover redaction-leak in production pilot, or
  Linux-only-assumption-bleed for 6+ stories) being catastrophic.
- **Sustainability:** The dependency-edge enforcement (CI check) prevents the same
  class of issue from recurring as Epic 2 progresses and Epic 3+ add more
  artifact-rendering stories.

---

## 4. Detailed Change Proposals

### Edit 1 — Story 2.24 Scope Expansion *(approved)*

**Target:** `_bmad-output/planning-artifacts/epics.md` lines 1357–1376
**Story:** 2.24: Artifact Content Sanitization (Untrusted Runner Output)

**Title change:**

```
OLD: Story 2.24: Artifact Content Sanitization (Untrusted Runner Output)
NEW: Story 2.24: Artifact Content Sanitization + Redaction-Gap Closure (Untrusted Runner Output)
```

**User-story expansion:**

```
OLD: As a frontend developer rendering markdown content produced by an LLM-driven runner,
     I want a hardened markdown sanitization library + diff sanitization + safe artifact rendering pipeline,
     So that untrusted runner output cannot inject scripts, exfiltrate via crafted links, or mislead reviewers via metadata spoofing
     — directly addressing the architecture's frontend quality gate "runner output is untrusted" + party-mode finding from Murat ("XSS/injection surface — risk-weight artifact sanitization tests highest").

NEW: As a frontend developer rendering markdown content produced by an LLM-driven runner — combined with a backend redaction-policy maintainer,
     I want a hardened markdown sanitization library + diff sanitization + safe artifact rendering pipeline AND closure of the F19/F20 redaction-policy gaps deferred from Epic 1 (PEM blocks, bundle JSON shapes, Idempotency-Key header, missing credential/bearer/private patterns),
     So that untrusted runner output cannot inject scripts, exfiltrate via crafted links, mislead reviewers via metadata spoofing, OR leak secrets that the backend redactor missed
     — closing the threat-model shift identified in the Epic 1 retrospective (2026-05-19): the CLI pilot didn't render untrusted content in a UI, but Epic 2 does, so backend-side redaction gaps that were "acceptable for CLI" now become UI-rendering vulnerabilities.
```

**Existing ACs 1–12:** UNCHANGED. They cover XSS/HTML sanitization correctly.

**New ACs to APPEND (13–18):**

13. **Given** the F19/F20 redaction-policy gaps from `_bmad-output/implementation-artifacts/deferred-work.md`,
    **Then** story 2.24 includes a backend-side redaction-policy hardening pass that extends the
    `RedactionPolicy` service from story 1.10 to cover: (a) PEM-formatted blocks
    (`-----BEGIN [A-Z ]+-----` ... `-----END [A-Z ]+-----`), (b) bundle JSON shapes
    (any object key matching `(?i)(secret|token|password|api[_-]?key|credential|bearer|private[_-]?key|access[_-]?token|refresh[_-]?token)`),
    (c) the `Idempotency-Key` HTTP header in any logged/exported request shape,
    (d) the field-name allowlist extended from `{password,token,secret}` to
    `{password,token,secret,credential,bearer,private,access_token,refresh_token,api_key,client_secret}`.

14. **Given** the extended RedactionPolicy, **Then** the adversarial fixture set from story 1.10
    (`backend/src/test/resources/redaction-fixtures/`) is extended with: a PEM RSA private key,
    a PEM EC private key, a PEM certificate-with-private-key bundle, a bundle JSON containing
    nested `credential`/`bearer`/`private_key` values, a request log shape containing
    `Idempotency-Key: <uuid>`; each fixture has an `.expected-redacted` sidecar asserting the
    post-redaction shape. The `RedactionPolicyContractTest` extends to include these fixtures
    and is build-blocking.

15. **Given** the frontend SafeMarkdownRenderer from AC1, **Then** a **second-pass frontend
    redaction filter** runs on rendered text content (after sanitization, before display)
    that re-asserts the redaction policy as defense-in-depth — using a frontend port of the
    same patterns from AC13, sourced from a shared spec file (`runner-contracts/redaction-policy.json`
    — new artifact) to prevent frontend/backend pattern drift. A passing redaction fixture in
    the frontend test suite is build-blocking.

16. **Given** the shared `runner-contracts/redaction-policy.json` spec, **Then** a contract test
    (in `runner-contracts/` module per the existing E1 contract-test pattern) asserts the
    backend RedactionPolicy and the frontend filter consume identical pattern sets. Pattern
    additions in either side must update the JSON spec; CI fails on drift.

17. **Given** the visible-distinction-from-redaction-failure UX, **Then** when redaction
    detects a pattern hit in untrusted content, the rendered output shows a documented
    `[REDACTED: <classification>]` placeholder (matching the backend's redaction sentinel
    convention from story 1.19) — never silently dropping characters. The placeholder is
    visually distinguishable from author-written `[REDACTED]` literals (e.g., wrapped in
    `<mark class="redaction-applied">` with a tooltip "Redaction applied — see audit log").

18. **Given** the F19/F20 closure, **Then** `deferred-work.md` F19 and F20 entries are
    marked `closed by 2-24` with a link to the story. Any redaction-pattern additions
    discovered during story execution are added to the JSON spec and tracked in the same
    file, not as new deferred entries.

**Deferred-work.md update (also in this edit):**

F19 entry status: `deferred (acceptable for CLI pilot — no UI rendering of untrusted output)`
→ `closed by Epic 2 story 2-24 (per sprint-change-proposal-2026-05-19.md)`. Closure note
explains AC13–AC18 coverage.

---

### Edit 2 — Story 2.1 CI Parity Enforcement *(approved)*

**Target:** `epics.md` lines 841–856
**Story:** 2.1: Frontend Module Scaffolding (Vite React TypeScript + Maven Wiring)

**AC7 — modify:**

```
OLD:
7. **Given** per-OS support (story 1.17 matrix), **Then** frontend build works on
   Windows 11 PowerShell + Ubuntu 22.04 + macOS 14+ — verified by the CI matrix job
   from story 1.21.

NEW:
7. **Given** per-OS support (story 1.17 matrix), **Then** frontend build works on
   Windows 11 PowerShell + Ubuntu 22.04 + macOS 14+ — verified by an **in-story**
   CI matrix extension (NOT inherited from 1.21's collapsed-to-Ubuntu doctor-smoke
   job). Story 2.1 ships a `frontend-build` CI job with `strategy.matrix.os` =
   `[ubuntu-latest, windows-latest]` (macOS deferred per cross-platform support
   tier from 1.17), both running `mvn -pl deliveryline-frontend clean package`
   end-to-end; both must be green before merge. A failing Windows job is
   build-blocking — never a warning, never skippable. Rationale documented inline
   referencing the Epic 1 retro finding (2026-05-19, sprint-change proposal).
```

**AC8 — minor refinement:**

```
NEW AC8 (appended):
8. **Given** a development workflow, **Then** `npm run dev` inside the frontend module
   starts Vite's dev server on a documented port (default 5173, configurable via PORT
   env per AC9c), proxying `/api/*` requests to the Spring Boot backend on `localhost:8080`
   — configured in `vite.config.ts`. The proxy config works identically on Windows
   PowerShell, Windows Git Bash, Ubuntu, and macOS — verified manually as part of
   the pre-story spike (A4).
```

**New AC9 — codify spike outputs:**

9. **Given** Windows + Linux line-ending and path-length differences discovered in
   the pre-2.1 frontend-on-Windows tooling spike (per sprint-change proposal
   2026-05-19, action A4 — see `docs/spikes/2026-05-frontend-on-windows.md`), **Then**:
   (a) `deliveryline-frontend/.gitattributes` declares `* text=auto eol=lf` for source
       files and `*.bat text eol=crlf` for any Windows-only scripts, preventing CRLF
       contamination of snapshot tests and build artifacts;
   (b) Path lengths inside `node_modules/` are documented as a known Windows risk;
       if any transitive dep exceeds `MAX_PATH=260` chars under default Windows
       config, the spike report identifies a mitigation (long-paths enabled in
       project README, or transitive dep replaced) before story 2.1 ships;
   (c) Vite dev-server port (default 5173 per AC8) is documented as a possible
       conflict point on Windows; the dev-server config exposes a `PORT` env override
       documented in `frontend/README.md`.

**New AC10 — foundation-gate extension:**

10. **Given** the foundation-gate CI verification from story 1.23 (Epic 1 close gate),
    **Then** the gate's scope widens to include "frontend-build matrix green on the
    branch" — meaning the Windows + Ubuntu frontend-build jobs from AC7 are added to
    the foundation-gate `needs:` chain. A frontend-build failure on either OS blocks
    every subsequent Epic 2 / 3 / 4 PR from merging. The foundation-gate workflow file
    (`.github/workflows/ci.yml`) is updated in this story, NOT in a later story
    — preventing the regression class where "we'll wire it later" becomes "we shipped
    8 stories on Linux-only".

**Per-story prerequisite note (append to story 2.1):**

> **Prerequisite:** Story 2.1 cannot start until the frontend-on-Windows tooling spike
> (retro action A4) is documented in `docs/spikes/2026-05-frontend-on-windows.md`.
> The spike's outputs (line-ending strategy, path-length notes, port config) inform
> AC7/AC8/AC9 directly.

---

### Edit 3 — Dependency Edges *(approved)*

**Target:** `epics.md` Epic 2 header (after line 839) + per-story notes on 2.15 / 2.17 / 2.18

**Epic 2 header insertion** (after the existing narrative paragraph, before `### Story 2.1`):

```markdown
**Epic 2 critical-path dependency edges (per sprint-change proposal 2026-05-19):**

Story IDs remain stable. The following execution-order edges are enforced at sprint
planning + CI branch-protection time, NOT by renumbering:

- **2.24 (Artifact Content Sanitization + Redaction-Gap Closure)** must merge before
  any of: **2.15** (Run Review Queue Item — renders queue-item artifact metadata),
  **2.17** (Artifact Review Panel — Spec Variant — renders artifact body content),
  **2.18** (Clarification Region — renders clarification content from agent runners).
  Rationale: 2.24 closes F19/F20 redaction-policy gaps deferred from Epic 1; without it,
  2.15/2.17/2.18 would render UI content that the backend redactor has not scrubbed for
  PEM blocks, bundle JSON shapes, Idempotency-Key headers, or credential/bearer/private
  patterns. See sprint-change-proposal-2026-05-19.md and Epic 1 retro
  (epic-1-retro-2026-05-19.md, section 6 "Significant Discovery").

- **Frontend-on-Windows tooling spike** (action A4 from Epic 1 retro) must complete
  before **2.1** starts. See `docs/spikes/2026-05-frontend-on-windows.md`.

- All other story-ordering follows the natural dependency reading of the AC text
  (e.g., 2.2 depends on 2.1's scaffold; 2.6's typed client depends on backend stories
  2.13/2.14 publishing OpenAPI).

Enforcement: branch-protection (per story 1.21 AC7) extends to gate merges of 2.15/2.17/2.18
PRs on the presence of merged 2.24 commits on `main`. Mechanism: a required CI check
("dependency-edges") verifies the dependency graph against the declared edges; the helper
script lives in `scripts/ci/check-story-dependencies.{sh,ps1}` (extension of the
branch-protection helpers from story 1.21).
```

**Per-story dependency notes** — appended to stories 2.15, 2.17, 2.18:

> **Dependency:** Cannot merge before story 2.24 ships (Artifact Content Sanitization
> + Redaction-Gap Closure). Reason: this story renders untrusted backend artifact content
> in the UI; 2.24 closes the F19/F20 redaction gaps that make safe rendering possible.
> Enforced by dependency-edges CI check (per sprint-change-proposal-2026-05-19.md).

---

### Edit 4 — Spike Charter *(approved)*

**Target:** New file `docs/spikes/2026-05-frontend-on-windows.md`

Spike charter with 5 questions (frontend-maven-plugin Node bundling on Windows;
line-endings + .gitattributes; node_modules path length; Vite dev-server port +
proxy; file-locking + HMR), 2-3 hour time-box, explicit acceptance criteria, and
pre-populated findings skeleton sections.

See the spike file for full content (created as part of this proposal's implementation).

---

## 5. Implementation Handoff

### Scope classification: **Moderate**

This is more than a Minor change (more than one artifact touched, more than a
single AC tweak), but it's less than a Major change (no PRD revisions, no epic
restructuring, no architecture redesign). The right handoff path is:

- **Product Owner + Developer agents** coordinate the artifact updates
- No PM / Architect escalation needed (no PRD/architecture rewrite)

### Implementation tasks (this sprint-change)

The following are executed as direct artifact updates in this session, **NOT** as
new sprint-tracked stories:

1. ✅ **Apply Edit 1** — Update story 2.24 in `epics.md` (title, user-story, ACs 13–18)
2. ✅ **Apply Edit 2** — Update story 2.1 in `epics.md` (AC7 modify, AC8 refine, AC9/AC10 append, prerequisite note)
3. ✅ **Apply Edit 3** — Insert Epic 2 header dependency block; append per-story notes to 2.15, 2.17, 2.18
4. ✅ **Apply Edit 4** — Create `docs/spikes/2026-05-frontend-on-windows.md`
5. ✅ **Update `deferred-work.md`** — F19 + F20 status to `closed by 2-24 (per sprint-change-proposal-2026-05-19.md)` (deferred until 2-24 actually ships; for now, a forward-reference note is added)
6. ✅ **Save this sprint-change-proposal document**
7. ⏳ **Sprint-status.yaml** — no change needed (no story renames, no new stories, no scope deltas at the epic level)

### Follow-on work (downstream of this proposal, **not** part of this sprint-change)

- **Action A4 execution:** Dana + Elena run the frontend-on-Windows spike per
  charter. **Blocks story 2.1.**
- **Story 2.1 execution:** Includes the new ACs (7/9/10) + `check-story-dependencies`
  CI helper script as part of its task list. Wired via the standard `bmad-create-story`
  flow when prerequisites are met.
- **Story 2.24 execution:** Includes the new ACs (13–18) + backend RedactionPolicy
  extension + frontend filter + shared spec + contract test. Wired via `bmad-create-story`.
- **Stories 2.15 / 2.17 / 2.18 execution:** Continue normally; dependency-edges CI
  check enforces the merge-order constraint.

### Success criteria

- All four edits applied to `epics.md` (or referenced new files created)
- `sprint-change-proposal-2026-05-19.md` saved alongside the 2026-05-11 predecessor
- `docs/spikes/2026-05-frontend-on-windows.md` exists and is ready for Dana + Elena to populate
- No regressions in Epic 1's closed state (sprint-status.yaml `epic-1-retrospective: done` preserved)
- Next BMad agent invocation (e.g., `bmad-create-story` for 2.1) sees the new
  prerequisite note and can route accordingly

---

## 6. Approval

**Project Lead (Alex)** approved all four edits incrementally on 2026-05-19:

- Edit 1 — Story 2.24 Scope Expansion → **Approve as drafted**
- Edit 2 — Story 2.1 CI Parity Enforcement → **Approve as drafted**
- Edit 3 — Dependency Edges → **Approve as drafted**
- Edit 4 — Spike Charter → **Approve as drafted**

This proposal is approved for implementation as of 2026-05-19.

---

## 7. Related Documents

- `_bmad-output/implementation-artifacts/epic-1-retro-2026-05-19.md` — Epic 1
  retrospective that surfaced the Significant Discovery
- `_bmad-output/implementation-artifacts/deferred-work.md` — F19/F20 entries (to
  be updated to `closed by 2-24` when 2-24 ships)
- `_bmad-output/planning-artifacts/sprint-change-proposal-2026-05-11.md` — Prior
  sprint-change (descoped story 1-20, narrowed 1-12 scope, inserted 1-12c)
- `_bmad-output/planning-artifacts/epics.md` — Master epics document (modified by
  Edits 1, 2, 3)
- `docs/spikes/2026-05-frontend-on-windows.md` — Spike charter (created by Edit 4)
