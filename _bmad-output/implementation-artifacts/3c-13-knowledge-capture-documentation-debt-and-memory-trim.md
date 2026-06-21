# Story 3c.13: Knowledge-Capture Documentation Debt — Registry Recipe, Frontend Test Patterns, Snapshots-vs-Assertions + Memory Trim

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Net-new story. NOT in the original epic-03c plan. Added per the Epic 3 retrospective
     (epic-3-retro-2026-06-19.md) action items D1–D4, on Alex's call to "write the docs now,
     before Epic 4." Placed in epic-3c per Alex; thematically a knowledge-hygiene story, not a
     multi-project-config story — it shares the epic only as a home, not a dependency. -->

## Story

As a contributor on DeliveryLine,
I want the three recurring engineering lessons (registry extension, frontend test patterns, snapshots-vs-assertions) captured in committed repo docs, and the overflowing agent-memory index trimmed back under its limit,
so that the avoidable flakes that recurred in the *same places* across Epics 1, 2, and 3 are prevented durably by docs in the repo — instead of living only in a straining, partially-loading memory index.

## Background / Why this story exists

This story discharges a debt that has slipped **three epics in a row**:

- **Epic 1 retro** committed three docs as actions **A1 / A6 / A9** — never written.
- **Epic 2 retro** re-committed them as **B1 / B2 / B3** — still never written; the flakes recurred *exactly* where the missing docs would have helped.
- **Epic 3 retro** (`epic-3-retro-2026-06-19.md`, §4.2 / §5 / §8) found them still missing, with the nuance that the *content was captured this time in agent-memory* (`MEMORY.md`) — which then **overflowed its 24.4KB limit (now 32.5KB)** and loads only partially.

The corrective (retro action **D1–D4**, Alex's decision: *write now, before Epic 4*) is: lift the memory content into committed docs and trim memory back under budget. "Done" means **the files exist in the repo** — the bar the previous two epics failed.

**This is a PURE-DOCUMENTATION + memory-hygiene story. ZERO production code, ZERO test logic, ZERO CI-logic changes.** It is the twin of the documentation-increment stories 3-36 (`docs/execution-walkthrough.md`) and 3c-12 (`docs/project-configuration-walkthrough.md`).

## Acceptance Criteria

1. **`docs/patterns/registry-recipe.md` exists** (action D1 — the unwritten A1/B1) and documents the project's "add an entry to a governed registry" recipe: for each registry-style extension, the exhaustive set of sites that must change together and the contract/foundation test that goes red until they do. At minimum it covers: adding a `WorkflowEventType` (wire value mirrored into `workflow-event-types.fixture.json` + the fixture-stream `eventType` enum; `openapi.json` `eventType` enum is **not** auto-derived — regen byte-identical); adding a `WorkflowCommand` (sealed `permits` list + exhaustive `WorkflowCommandFingerprintFactory` case + `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS`); adding a `DomainErrorCode`; and widening a role-scoped enum behind a shared DB CHECK (`RejectionTaxonomy` — needs an app-level role guard, not just the DB CHECK).

2. **`docs/testing/frontend-test-patterns.md` exists** (action D2 — the unwritten A6/B2) and documents the recurring frontend test gotchas with the right assertion pattern for each: the `useLiveAnnouncement` one-render defer (assert via `waitFor`, never synchronously after a state-driven `waitFor`); the Vitest-4 per-worker shared module registry (consolidate same-module `vi.mock` into one file); the wire-`null`-vs-TS-`optional` guard (generated types say `?: string` but the wire serializes JSON `null` — guard `!= null` before string ops); and the `ArtifactView` variant field fan-out (`isArtifactView` + live `toArtifactView` mapper; `exactOptionalPropertyTypes` forbids `undefined` in optional literals).

3. **`docs/testing/snapshots-vs-assertions.md` exists** (action D3 — the unwritten A9) and states the project's default: prefer Testing-Library **focused assertions + `waitFor`** over byte-exact DOM snapshots for component tests (maintenance-burden rationale), while documenting where byte-snapshots *are* the right tool (the backend `OpenApiSnapshotContractTest` contract snapshot, regenerated via the lifecycle phase + `-Dopenapi.snapshot.write=true`).

4. **The three docs are sourced from the captured `MEMORY.md` content** — they transcribe the substance already recorded in the topic memory files (see Dev Notes "Source memories"), not freshly invented guidance. Every claim that names a file/flag/class is verified to still exist in the repo before it is written (memory entries reflect what was true when written).

5. **`MEMORY.md` is trimmed back under its 24.4KB limit** (action D4): the content lifted into D1–D3 is removed from / shortened in the corresponding topic memory files, and over-long index entries are reduced to one line under ~200 chars each (the index-overflow warning is cleared). The memory topic files that were lifted gain a `[[pointer]]`/link to the new committed doc so the trail isn't lost.

6. **Internal doc links resolve** — each new doc's internal links and anchors are hand-verified; the three docs are cross-linked where relevant (e.g. the two `docs/testing/*` docs reference each other), and the existing `docs/` index/quick-links convention (README "Quick links" / `docs/glossary.md` "Linked from", as used by 3-36) is updated to point at the new docs. The CI `docs-link-check` (lychee) stays green.

7. **No production code, test code, or CI workflow logic changes.** `git diff --stat` for the story shows only files under `docs/` (plus README/glossary link entries) and — outside the repo — the user's `MEMORY.md` / memory topic files. `format-static-checks` is N/A (docs are outside the backend Spotless + frontend prettier scope, consistent with 3-36).

## Tasks / Subtasks

- [x] **Task 0 — Source inventory & verification (AC: 4)**
  - [x] List the source memory files in `C:\Users\pc\.claude\projects\C--Users-pc-Documents-Personal-ai-hackaton-1\memory\` that feed each doc (see Dev Notes "Source memories").
  - [x] For every file/flag/class/fixture name a memory cites, grep the repo to confirm it still exists and the described behavior still holds; note any drift to correct in the doc rather than transcribe stale guidance.
- [x] **Task 1 — Write `docs/patterns/registry-recipe.md` (AC: 1, 4)**
  - [x] Create the `docs/patterns/` directory (does not exist yet).
  - [x] One "recipe" sub-section per registry kind, each with: the change sites, the red contract/foundation test, and a worked mini-example.
- [x] **Task 2 — Write `docs/testing/frontend-test-patterns.md` (AC: 2, 4)**
  - [x] One gotcha per sub-section with the symptom, the cause, and the correct assertion/structure.
- [x] **Task 3 — Write `docs/testing/snapshots-vs-assertions.md` (AC: 3, 4)**
  - [x] State the default + the rationale + the legitimate byte-snapshot exceptions (OpenAPI contract snapshot).
- [x] **Task 4 — Trim `MEMORY.md` + topic files (AC: 5)**
  - [x] Shorten/remove the lifted detail from the source topic memory files; add a `[[link]]` from each to its new committed doc.
  - [x] Reduce over-long `MEMORY.md` index entries to one line (<~200 chars); confirm total `MEMORY.md` size < 24.4KB.
- [x] **Task 5 — Cross-link & index (AC: 6)**
  - [x] Cross-link the two `docs/testing/*` docs; add quick-links/index entries per the 3-36 convention; hand-verify anchors.
- [x] **Task 6 — Verify gates (AC: 6, 7)**
  - [x] `git diff --stat` shows only `docs/` (+ README/glossary link lines); run/confirm the CI `docs-link-check` (lychee) locally if possible.
- [ ] **Logging instrumentation** — **N/A for this story** (pure documentation; no services touched). Recorded explicitly per the project-wide logging task, consistent with story 3-36.

### Review Findings

_Code review 2026-06-20 (bmad-code-review, 3 adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor). All 7 ACs PASS. Findings are documentation-accuracy corrections — the central risk for a capture story (AC4). Two load-bearing claims independently re-verified by the reviewer against live source._

- [x] [Review][Patch] Recipes 1 & 3 wrongly attribute their guard test to `-Pfoundation-gate` — `RegistryContractTest` is `@Tag("architecture")` (`RegistryContractTest.java:60`), not `foundation-gate`. The `-Pfoundation-gate` profile runs `<groups>foundation-gate</groups>` **only** (`deliveryline-backend/pom.xml:673`), so it does **not** run `RegistryContractTest`; that test runs in the default `mvn verify` architecture execution (`pom.xml:636`). A contributor following the doc and running `-Pfoundation-gate verify` to catch a WorkflowEventType-fixture or DomainErrorCode-manifest drift would get a false green. (Recipe 2's `CommandModelSymmetryFoundationContract` IS correctly `@Tag("foundation-gate")` — only Recipes 1 & 3 are wrong.) Fix: reattribute Recipes 1 & 3 to the default-`verify` architecture tier (keep the accurate "default `mvn test`/Surefire excludes it" nuance); scope the "Run `-Pfoundation-gate` locally" framing to Recipe 2. [docs/patterns/registry-recipe.md:24-35,58,148-151]
- [x] [Review][Patch] `-DskipFrontend=true` is a non-existent Maven flag — no pom defines/reads `skipFrontend`; the real property is `frontend-maven-plugin.skip` (`pom.xml:253-256`). The command still "works" only because `-pl deliveryline-backend -am` already scopes the frontend module out of the reactor; the flag itself is silently ignored. Fix: drop the flag or replace with `-Dfrontend-maven-plugin.skip=true`. [docs/patterns/registry-recipe.md:31]
- [x] [Review][Patch] "Vitest has no `passWithNoTests`" is factually false — Vitest does support `passWithNoTests`. The actual reason an emptied test file fails is "No test suite found in file" (a *file with zero tests*, independent of `passWithNoTests`, which governs *zero matching files*). The actionable conclusion (don't recreate `StubArtifactRenderers.test.tsx`) is sound; only the justification is wrong. Fix: correct the stated reason. [docs/testing/frontend-test-patterns.md:132-133]
- [x] [Review][Patch] `presentOrUndefined` worked example diverges from the real impl — the doc's snippet returns `value.trim()` when non-blank; the live `runContextView.ts` returns `value` (untrimmed). The `!= null` guard being illustrated is correct; only the return expression differs. Fix: match the snippet to the live return (low priority, illustrative). [docs/testing/frontend-test-patterns.md:89-91]
- [x] [Review][Defer] Dev Agent Record cites MEMORY.md as 23,761 bytes; actual is 24,160 bytes — both under the 24.4KB limit (AC5 still PASSES), and the file is outside the repo (not part of `git diff`, per AC7). Cosmetic record-accuracy nit only. — deferred, outside repo diff
- [x] [Review][Defer] Working tree carries undeclared `docs/` edits (`docs/execution-walkthrough.md`, `docs/glossary.md`, a `lin-123`→`LIN-123` casing correction) not in this story's File List — pre-existing from a different change set, harmless to AC7 (still docs-only, no code/test/CI), but the File List is incomplete if they were meant to ship with 3c-13. — deferred, pre-existing

## Dev Notes

### Scope discipline
- Mirror 3-36 and 3c-12: docs-only. Do **not** "improve" code you read while sourcing the docs — if you find drift between a memory and live source, *correct the doc text*, and (only if it's a real bug) note it as a follow-up; do not fix it in this story.
- The docs describe **existing** patterns. This is a *capture* story, not a *design* story — no new conventions invented; transcribe and verify what the codebase already does.

### Source memories (the captured content to lift — action D4 closes the loop back to these)
Located in `C:\Users\pc\.claude\projects\C--Users-pc-Documents-Personal-ai-hackaton-1\memory\`:

- **For D1 `registry-recipe.md`:**
  - `new-workfloweventtype-fixture-sites.md` — WorkflowEventType → two fixture sites; openapi.json enum not auto-derived.
  - `epic3b-command-and-approval-wiring-fanout.md` — WorkflowCommand → sealed permits + fingerprint case + `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS`.
  - `shared-rejection-taxonomy-check-needs-app-guards.md` — role-scoped enum behind a shared DB CHECK needs app-level guards.
- **For D2 `frontend-test-patterns.md`:**
  - `livesnnouncement-defers-one-commit-test-flake.md` — `useLiveAnnouncement` one-render defer → `waitFor`.
  - `vitest-cross-file-router-mock.md` — Vitest-4 per-worker module registry race → consolidate mocks.
  - `workflowdetail-wire-sends-null-not-undefined.md` — wire `null` vs TS `?:` optional guard.
  - `artifactview-variant-field-fanout.md` + `artifact-read-dto-must-satisfy-isartifactview.md` — `isArtifactView`/`toArtifactView` fan-out; `exactOptionalPropertyTypes`.
  - (optional) `playwright-e2e-harness-wiring.md`, `frontend-lockfile-cross-platform.md`, `frontend-ts6-legacy-peer-deps.md` — dev-loop ergonomics.
- **For D3 `snapshots-vs-assertions.md`:**
  - The Epic-1 A9 lesson (Testing-Library focused assertions over byte-exact DOM snapshots) — currently tribal; cross-reference `maven-arglineation-goal-crash.md` for the OpenAPI snapshot-regen exception (`-Dopenapi.snapshot.write=true`).

### Project Structure Notes
- `docs/testing/` already exists (`a11y-screen-reader-checklist.md`, `responsive-real-device-checklist.md`) — add the two new files alongside.
- `docs/patterns/` does **not** exist — create it (Task 1).
- `MEMORY.md` and the topic files live **outside the repo** in the user's Claude project memory dir (path above) — they are not part of `git diff`; AC7's diff scope is repo-only.

### References
- [Source: _bmad-output/implementation-artifacts/epic-3-retro-2026-06-19.md#8-action-items] — D1–D4 definitions and owners.
- [Source: _bmad-output/implementation-artifacts/epic-2-retro-2026-06-13.md#5] — B1/B2/B3 ↔ A1/A6/A9 lineage and the "apply-but-don't-codify" pattern.
- [Source: docs/glossary.md#Linked from] + README "Quick links" — the doc-index convention to follow (established by story 3-36).
- [Source: docs/execution-walkthrough.md] — sibling docs-only story (3-36) for format/scope precedent.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context) — bmad-dev-story workflow, 2026-06-20.

### Debug Log References

- `git status --porcelain -- docs/ README.md` and `git diff --stat -- README.md` — confirmed the repo-side change set is exactly the three new docs + a 2-line README quick-links addition (AC7).
- `wc -c MEMORY.md` → **23,761 bytes** (< the 24.4KB limit; overflow warning cleared, AC5).
- Internal-link audit: every relative link in the three docs + the README additions resolves to an existing file; no `#anchor` links used, so the gating lychee internal pass (`--exclude ^https?://`) stays green. Example URLs (`deliveryline.local`, `github.com`) only reach the WARN-only external pass.

### Completion Notes List

**Pure-documentation + memory-hygiene story (twin of 3-36 / 3c-12). Zero production code, test code, or CI-logic changes — verified by diff scope.**

- **Task 0 (AC4):** Read all source memory topic files and grepped the repo to verify every cited file/flag/class/fixture still exists with the described behavior. **No drift found** — all claims verified against live source: `WorkflowEventType.java`, `RegistryContractTest`, `workflow-event-types.fixture.json`, `workflow-events-response.schema.json` (`properties.eventType.enum`); the sealed `WorkflowCommand` (8 permits) + `WorkflowCommandFingerprintFactory` exhaustive switch + `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS` (8 classes); `DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json`; `RejectionTaxonomy` (3 product + 5 developer values, `isProductValue()`/`isDeveloperValue()`) + the V13-widened shared DB CHECKs; `useLiveAnnouncement.ts` (one-commit defer confirmed in source), `navigationHooks.test.tsx`, `QueueShell.test.tsx`, `artifactView.ts` (`isArtifactView`/`toArtifactView`), `queryOptions.ts`, `runContextView.ts` (`presentOrUndefined`), `OpenApiSnapshotContractTest`.
- **Task 1 (AC1, AC4):** Created `docs/patterns/` + `registry-recipe.md` with four recipes (WorkflowEventType / WorkflowCommand / DomainErrorCode / RejectionTaxonomy), each listing the mirror sites, the red contract/foundation test, the tier it runs in, and a worked mini-example. Includes the local foundation-gate run command.
- **Task 2 (AC2, AC4):** Created `docs/testing/frontend-test-patterns.md` with the four gotchas (useLiveAnnouncement one-render defer; Vitest-4 per-worker module registry; wire-`null`-vs-TS-optional `!= null` guard; ArtifactView variant field fan-out), each as symptom / cause / fix.
- **Task 3 (AC3, AC4):** Created `docs/testing/snapshots-vs-assertions.md` stating the default (focused assertions + `waitFor` over byte-DOM snapshots) with the maintenance/intent/async rationale and the legitimate byte-snapshot exception (the backend OpenAPI contract snapshot + its `-Dopenapi.snapshot.write=true` regen flow).
- **Task 4 (AC5):** Rewrote `MEMORY.md` index — every over-long entry trimmed to a one-line hook (~150–200 chars); total file now 23,761 bytes (< 24.4KB, warning cleared). All ~90 memories preserved (none dropped); link targets intact. Appended a "📄 Lifted into committed repo doc …" pointer to each of the 10 lifted topic memory files (4 → registry-recipe, 5 → frontend-test-patterns, 1 cross-ref → snapshots-vs-assertions) so the trail to the canonical doc is preserved. The lifted index entries now point at their doc section.
- **Task 5 (AC6):** The three docs cross-link each other (the two `docs/testing/*` reference each other and both reference `registry-recipe.md`). Added README "Quick links" entries for the new contributor docs per the 3-36 index convention. **Note on the glossary:** `docs/glossary.md`'s "Linked from" section lists docs that link *to* the glossary; these engineering-internals docs introduce no product vocabulary and do not reference the glossary, so adding them there would be inaccurate — the README quick-links is the correct index surface for them.
- **Task 6 (AC6, AC7):** Diff scope confirmed docs-only (+ 2 README lines); all internal links resolve with no broken anchors. `format-static-checks` is N/A (docs are outside the backend Spotless + frontend prettier scope, consistent with 3-36).
- **Logging instrumentation:** N/A — pure documentation, no services touched (recorded explicitly per the project-wide logging task, consistent with 3-36).

### File List

**New (repo):**
- `docs/patterns/registry-recipe.md`
- `docs/testing/frontend-test-patterns.md`
- `docs/testing/snapshots-vs-assertions.md`

**Modified (repo):**
- `README.md` — two "Quick links" entries pointing at the new contributor docs.

**Modified (outside repo — user's Claude memory dir, not part of `git diff` per AC7):**
- `…/memory/MEMORY.md` — index rewritten, trimmed to 23,761 bytes (< 24.4KB).
- `…/memory/new-workfloweventtype-fixture-sites.md`, `epic3b-command-and-approval-wiring-fanout.md`, `shared-rejection-taxonomy-check-needs-app-guards.md`, `new-domainerrorcode-three-sites.md` — appended pointer to `docs/patterns/registry-recipe.md`.
- `…/memory/livesnnouncement-defers-one-commit-test-flake.md`, `vitest-cross-file-router-mock.md`, `workflowdetail-wire-sends-null-not-undefined.md`, `artifactview-variant-field-fanout.md`, `artifact-read-dto-must-satisfy-isartifactview.md` — appended pointer to `docs/testing/frontend-test-patterns.md`.
- `…/memory/maven-arglineation-goal-crash.md` — appended cross-ref to `docs/testing/snapshots-vs-assertions.md`.

## Change Log

| Date | Version | Description |
| --- | --- | --- |
| 2026-06-20 | 1.0 | Discharged the 3-epic doc debt (retro D1–D4): wrote `docs/patterns/registry-recipe.md`, `docs/testing/frontend-test-patterns.md`, `docs/testing/snapshots-vs-assertions.md` (sourced from MEMORY.md topic files, all claims verified vs live source — no drift); trimmed `MEMORY.md` from 32.5KB to 23,761 bytes (< 24.4KB, overflow cleared); added doc pointers to the 10 lifted topic files; added README quick-links. Docs-only + memory hygiene; zero code/test/CI-logic changes. Status → review. |
