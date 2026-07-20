# Story 4.20: Compare Mode UI Component (UX-DR13)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager (spec revisions) / Developer (plan revisions, PR-output revisions) verifying what changed before approving,
I want the `CompareMode` composite (UX-DR13) fully implemented — side-by-side / stacked / summary-first variants, changed-region indicators, filter & jump controls, and an exit-back-to-review control — consuming the `RevisionDeltaService` REST contract from story 4.19,
so that UX-DR13 lands as a first-class trust-and-verification surface: the deferred-from-MVP composite the architecture's "trust verification" promise depended on.

## Context & Central Reconciliation (READ FIRST)

**This is a FRONTEND-heavy full-stack story. ~90% is new frontend; the backend slice is TINY and additive-only.** The entire `RevisionDelta` typed contract + the `GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}` endpoint **already shipped in story 4.19** and are ALREADY in the committed `schema.d.ts` (`RevisionDelta`, `RevisionDeltaChange`, `RevisionDeltaSummary`, `ArtifactRevisionSummary`, `operations.compareArtifacts`). You do **NOT** re-add or regenerate any of that. The backend work here is exactly ONE thing: add the `enter_compare_mode` allowed-action to the registry + surface it in the action matrix so the ARP compare-entry control activates (epic AC9). Everything else is the `CompareMode` React composite + its hook + tests.

The single most important thing to internalize: **the compare-entry seam ALREADY EXISTS in the frontend from story 2.17 — it just points at the wrong action name and has no destination.** `ArtifactReviewPanel` already threads a `compareEnabled` prop into all three artifact renderers, and `canEnableCompare(actions, hasComparableRevision)` already gates on a backend action + a per-artifact `version > 1` predicate. Two problems to reconcile: (a) the checked literal is `'compare'`, but the backend action AC9 mandates is `enter_compare_mode`; (b) the Compare control is a `disabled` placeholder button with an "Available in next release" tooltip and NO onClick destination. Story 4.20 fixes both and builds the destination.

### HEADLINE RECONCILIATIONS (epic AC text vs live code — these bindings win)

1. **THE CENTRAL BINDING — the `RevisionDelta` contract already exists; DO NOT rebuild it.** Epic AC1 says the component "consumes `useRevisionDelta(artifactIdA, artifactIdB)` (TanStack Query hook backed by story 4.19 REST endpoint)". The endpoint + generated types are LIVE in `schema.d.ts`:
   - `components['schemas']['RevisionDelta']` = `{ artifactType?, changes?: RevisionDeltaChange[], linkedDiffReferences?: string[]|null, noMeaningfulDiff?: boolean, revisionA?: ArtifactRevisionSummary, revisionB?: ArtifactRevisionSummary, summary?: RevisionDeltaSummary }`.
   - `RevisionDeltaChange` = one FLATTENED polymorphic block with a `blockType` discriminator (`markdown | planStep | file`) — NOT a `oneOf`. Every variant field is optional/nullable on the wire (`sectionPath`/`priorText`/`currentText` for markdown; `stepId`/`priorStepText`/`currentStepText`/`priorStepOrder`/`currentStepOrder` for planStep; `filePath`/`addedLines`/`removedLines` for file; `changeKind` on all).
   - `ArtifactRevisionSummary` = `{ version?, createdAt?, producedByActor?: string|null, checksum?: string|null }`.
   - `operations.compareArtifacts` = `GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}` → 200 `RevisionDelta` / 400 / 404 / 503 ProblemDetails. **NO `Idempotency-Key`, NO `X-Actor-Identity`** (idempotent read).
   [Source: `deliveryline-frontend/src/lib/api/schema.d.ts:2404-2468` (`RevisionDelta`/`RevisionDeltaChange`/`RevisionDeltaSummary`), `:1339-1357` (`ArtifactRevisionSummary`), `:3112-3160` (`compareArtifacts`); `4-19-compare-mode-backend-revision-delta-service-for-spec-plan-pr-output.md`]

2. **The FE compare-entry seam already exists but checks the WRONG action name — rename `'compare'` → `'enter_compare_mode'` at exactly TWO sites.** Story 2.17 built the seam anticipating action name `'compare'`; AC9 binds it to `enter_compare_mode`. Reconcile:
   - Site 1: `artifactView.ts` → `canEnableCompare(actions, hasComparableRevision)` currently `actions.includes('compare')` → change to `actions.includes('enter_compare_mode')` (drives the Spec + ImplementationPlan renderers via `ArtifactReviewPanel`). Update the stale doc comment ("`'compare'` is the anticipated backend action name").
   - Site 2: `PrOutputArtifactRenderer.tsx:186` → `const compareEnabled = actions?.includes('compare') ?? false` → `actions?.includes('enter_compare_mode') ?? false`.
   [Source: `deliveryline-frontend/src/features/workflows/artifactView.ts:414-433`; `components/PrOutputArtifactRenderer.tsx:184-186`, `:459-470`; `components/SpecArtifactRenderer.tsx:71-74,260-268`; `components/ImplementationPlanArtifactRenderer.tsx:126-127,276-283`]

3. **`enter_compare_mode` is NOT yet in the `AllowedAction` enum — this is a TWO-SITE registry add + a pin test (mirrors the `resume_workflow` / `classify_failure` precedent).** Story 4.19 explicitly deferred it here ("the `enter_compare_mode` allowed-action lands in 4.20 (NOT here)").
   - Site 1: `AllowedAction.ENTER_COMPARE_MODE("enter_compare_mode")` in `domain/registry/AllowedAction.java`.
   - Site 2: append `"enter_compare_mode"` to `test/resources/contracts/frontend/allowed-actions.placeholder.json`.
   - Auto-verified by `RegistryContractTest.allowedActionsStayAlignedWithFrontendPlaceholder` (enum ↔ placeholder parity). Add a focused wire-value pin to `AllowedActionRegistryPinTest` (`enterCompareModeWireValueIsPinned`) mirroring the 15 existing pins.
   - **NO OpenAPI regen for the action** — the allowed-actions REST field is an OPEN `string[]` (every enum comment repeats "adding a value needs NO OpenAPI regen"). There is NO DB CHECK for allowed-actions. [Source: `domain/registry/AllowedAction.java:104-142` (the recovery-action precedents + the "open string[]" note); `test/resources/contracts/frontend/allowed-actions.placeholder.json:3-36`; `contract/RegistryContractTest.java:87,99,460-463`; `architecture/AllowedActionRegistryPinTest.java:127-163`]

4. **Matrix surfacing of `enter_compare_mode` — provisional binding (OQ-1).** The run-scoped `computeActionMatrix(state, actorRole, …)` has NO per-artifact-version awareness — it is a pure `state × role → List<AllowedAction>` switch (the "sole place state×role→action-set is encoded", ArchUnit-pinned). AC9 says compare is "typically" available "when at least 2 versions exist in the artifact's lineage", but the FE seam ALREADY re-gates on the concrete artifact via `hasComparableRevision(artifact) = artifact.version > 1`. **Binding: surface `enter_compare_mode` BROADLY for the reviewing/inspecting roles at the artifact-review + operator states, and let the FE combine it with the per-artifact `version > 1` predicate** (unchanged `canEnableCompare` composition). Concretely, append it (via a small `appendCompareOverlay`, mirroring `appendConflictOverlay`) for: `WAITING_FOR_SPEC_APPROVAL` (product_reviewer / workflow_owner), `WAITING_FOR_REVIEW` (developer), and `FAILED` / `PAUSED` (workflow_owner — the operator deep-dive per AC10.c). The backend action means "compare is conceptually reachable for this run+role"; the FE decides the concrete A/B pair. This keeps the matrix version-agnostic (no artifact read injected into the matrix) and honors the existing seam. Flag OQ-1 for Alex to confirm the exact state/role set.
   [Source: `application/workflow/WorkflowInspectionService.java:1613-1690` (`computeActionMatrix` + `appendConflictOverlay`/`appendSplitOverlay` overlay precedent), `:1709-1713` (the ArchUnit-pinned single-switch); `artifactView.ts:423-433`]

5. **A/B direction is FIXED by 4.19: A = baseline/prior, B = target/current; `changeKind` is computed B-relative-to-A.** Render `revisionA` on the LEFT / "before" and `revisionB` on the RIGHT / "after"; NEVER auto-swap. `added` = present only in B; `removed` = present only in A; `modified` = both, differing; `reordered` (planStep only) = same text, different index. [Source: 4.19 AC1 binding + `RevisionDeltaChange.changeKind`]

6. **Variant → layout mapping is driven by `RevisionDelta.artifactType` (NOT `blockType`).** `spec` + `implementationPlan` → **side-by-side** (default; synced scroll); `prOutput` → **stacked** (file accordions). `summary-first` is an orthogonal presentation toggle (initial render = summary header + collapsed change blocks; expand for detail) available for all types. The `blockType` discriminator selects the per-block RENDERER (markdown section vs plan step vs file). A frontend-owned `normalizeRevisionDelta` narrows the loose generated shape (all-optional fields) into a strict discriminated union BEFORE rendering — mirror the `isArtifactView` / normalize discipline in `artifactView.ts` (never cast a partial wire shape straight into renderer props). [Source: epic AC4; `artifactView.ts:191-315` (runtime-guard/normalize precedent)]

7. **Sanitization (AC5) is defense-in-depth over already-redacted text — route ALL delta text through the `@/lib/sanitization` barrel.** 4.19 AC6 already redacts every `ChangeBlock` text field on serve, but AC5 requires the FE to ALSO sanitize (and to exercise the 2.24 XSS fixtures against the compare renderer):
   - spec `priorText` / `currentText` → `SafeMarkdownRenderer` (block-level markdown).
   - planStep `priorStepText` / `currentStepText` → React-escaped PLAIN text (mirror `ImplementationPlanStep.summary`: untrusted step text renders as escaped plain text, NOT markdown — do not nest a block renderer inside interactive elements). Longer bodies may use `renderTextWithRedactions`.
   - prOutput file blocks carry only `filePath` + `addedLines`/`removedLines` (no free text) → render `filePath` via `renderTextWithRedactions`; render counts as typed numbers.
   - Import EXCLUSIVELY from the `@/lib/sanitization` barrel (`no-unsanitized-html` + `--max-warnings=0` forbid reaching into individual files). [Source: `lib/sanitization/index.ts:1-35`; `components/SpecArtifactRenderer.tsx:28,170`; `components/ImplementationPlanArtifactRenderer.tsx` (summary escaping); `artifactView.ts:73-80`]

8. **prOutput full diff is LAZY-LOADED via `linkedDiffReferences` — reuse the existing per-artifact read, render through `SafeUnifiedDiffRenderer`.** 4.19 returns a file-level SUMMARY (`FileChangeBlock` path + counts) plus `linkedDiffReferences = [artifactIdA, artifactIdB]`. The stacked prOutput view shows the file summary immediately; expanding a file accordion lazy-loads the full unified diff via the EXISTING `getArtifact` read (`GET /api/v1/workflows/{runId}/artifacts/{artifactId}` → the resolved `diff` on `PrOutputArtifactView`) and renders it with `SafeUnifiedDiffRenderer` + `parseUnifiedDiff` (respecting `PR_DIFF_MAX_FILES` / `PR_DIFF_MAX_LINES` caps). Do NOT invent a new diff-content endpoint. `linkedDiffReferences` is null for spec/implementationPlan. [Source: 4.19 AC5/Reconciliation 8; `lib/sanitization/index.ts:13-21` (`SafeUnifiedDiffRenderer`, `parseUnifiedDiff`, `PR_DIFF_MAX_FILES/LINES`); `components/PrOutputArtifactRenderer.tsx` (existing diff render)]

9. **Mode boundary (AC8): Compare Mode is a deeper inspection STATE of the artifact review — NOT a new route.** Do NOT add a `/compare` route or a route param. Mount `CompareMode` as an in-context overlay / expanded panel WITHIN the existing artifact route (`routes/workflows/$workflowRunId/artifacts/$artifactId.tsx`) / AppShell tri-pane (story 2.7), preserving run + artifact identity (UX-DR16). Entry = the ARP Compare control's onClick toggles a local overlay state (no navigation). Exit = `useReturnToRunContext` (story 2.22) + `Esc`. [Source: `lib/navigation/useReturnToRunContext.ts:26-61`; `routes/workflows/$workflowRunId/artifacts/$artifactId.tsx:71-115`; `components/ArtifactReviewPanel.tsx`]

10. **State → error-code mapping is the LIVE 4.19 mapping (400/404/503), NOT the epic's "409"/"ARTIFACT_NOT_FOUND" phrasing.** The epic's AC3 error phrasing drifts from what 4.19 actually ships:
    | UX-DR13 state | Trigger | Binding |
    |---|---|---|
    | `no meaningful diff` | `noMeaningfulDiff === true` | Empty state "These revisions are identical" |
    | `no baseline available` | `ARTIFACT_RECORD_NOT_FOUND` (404) | Explanatory `ErrorState` (the epic's "ARTIFACT_NOT_FOUND" reuses this live code) |
    | `partial comparison` / `diff unavailable` | `ARTIFACT_PAYLOAD_UNAVAILABLE` (**503**, retryable — NOT the epic's 409) | `ErrorState` with story-2.22 `nextAction` (Retry / operator override) |
    | `error / comparison unavailable` | `ARTIFACT_LINEAGE_MISMATCH` (400) or `INVALID_ID_PREFIX` (400) or transport | Generic `ErrorState` |
    | `loading` | fetch in flight | Skeleton matching the resolved layout |
    Use `isProblemDetailsError(error)` + `error.code` to branch (never human text). [Source: 4.19 AC8/Reconciliation 3 (`ARTIFACT_RECORD_NOT_FOUND` 404, `ARTIFACT_PAYLOAD_UNAVAILABLE` 503, `ARTIFACT_LINEAGE_MISMATCH` 400); `lib/api/problemDetails.ts` (`isProblemDetailsError`); `components/feedback/states/ErrorState.tsx`]

11. **`useRevisionDelta` is a NET-NEW hook keyed OFF the run tree (artifact-lineage-scoped, cross-run-capable).** Compare spans an artifact lineage, not a single run, so its query key is NOT a child of `workflowKeys.detail(runId)`. Mirror `workflowKeys.artifact(artifactId)` (a child of `all`, not `detail`): add `workflowKeys.revisionDelta(artifactIdA, artifactIdB)` = `[...all, 'revisionDelta', artifactIdA, artifactIdB]`. Read-only + idempotent → NO Idempotency-Key. `staleTime: STALE_TIME.detail`. Unwrap via `apiClient.GET(...)` + `unwrap` (mirror `useAllowedActions`). [Source: `lib/queryKeys/workflowKeys.ts:156-157` (`artifact` key precedent, child of `all`); `hooks/useAllowedActions.ts:46-78`]

12. **Keyboard nav + a11y (AC6, AC10.axe) mirror the established vocabulary.** J/K (+ Down/Up Arrow) jump to next/previous changed region with smooth scroll (GitHub-PR idiom); Tab cycles summary → filter → changed regions → exit; `Esc` exits. Changed-region gutter markers are non-color (icon/shape + label) per 2.3 AC5. Announcements go through ONE `aria-live` region using centralized strings — ADD a `// ---- Compare Mode (story 4.20)` block to `lib/a11y/announcements.ts` (e.g. `compareLoaded(changedRegionCount)`, `compareNoMeaningfulDiff`, `compareJumpedToRegion(index, total)`, `compareExited`) and drive it via `useLiveAnnouncement`. The `announcement-vocabulary` node-test (`npm run check:a11y`) enforces the const-vs-function convention (parameterless = const, parameterized = function). [Source: `lib/a11y/announcements.ts:1-52,136-149`; `lib/a11y/useLiveAnnouncement.ts`]

## Scope Boundary — what 4.20 BUILDS vs REUSES vs DEFERS

| Concern | 4.20 | Note |
|---|---|---|
| `CompareMode.tsx` presentational composite (side-by-side / stacked / summary-first, states, gutter markers, filter/jump/exit) | **BUILD** | epic AC1-AC8; presentational + fixture-driven (mirror `ArtifactReviewPanel`) |
| `CompareModeContainer` thin data seam (reads `useRevisionDelta`, maps to panel props, owns structured logs) | **BUILD** | mirror `ArtifactReviewPanelContainer` |
| `useRevisionDelta(artifactIdA, artifactIdB)` hook + `workflowKeys.revisionDelta(...)` key | **BUILD** | Reconciliation 1/11 |
| `normalizeRevisionDelta` — narrow the loose generated shape into a strict discriminated union + per-block renderers | **BUILD** | Reconciliation 6; mirror `isArtifactView`/normalize |
| Per-block renderers: `MarkdownChangeBlockView` (`SafeMarkdownRenderer`), `PlanStepChangeBlockView` (escaped text), `FileChangeBlockView` (path + counts, lazy diff via `SafeUnifiedDiffRenderer`) | **BUILD** | Reconciliation 7/8 |
| Rename FE compare-action literal `'compare'` → `'enter_compare_mode'` (2 sites) + wire the ARP Compare control onClick to open the overlay | **BUILD** | Reconciliation 2; epic AC9/AC10 |
| Compare-Mode overlay mount + exit inside the artifact route (in-context, no new route) | **BUILD** | Reconciliation 9; epic AC8 |
| `AllowedAction.ENTER_COMPARE_MODE("enter_compare_mode")` enum value + placeholder JSON entry + `AllowedActionRegistryPinTest` pin | **BUILD** | Reconciliation 3; epic AC9 |
| `appendCompareOverlay` in `computeActionMatrix` (surface `enter_compare_mode` at review/operator states) + `WorkflowInspectionServiceAllowedActions*Test` + `AllowedActionsEndpointContractTest` coverage | **BUILD** | Reconciliation 4; epic AC9 |
| Compare-Mode announcement vocabulary block in `announcements.ts` + `useLiveAnnouncement` wiring | **BUILD** | Reconciliation 12; epic AC6 |
| `RevisionDelta` / `compareArtifacts` contract + generated `schema.d.ts` types | **REUSE UNCHANGED** | shipped in 4.19 — Reconciliation 1 |
| `SafeMarkdownRenderer` / `SafeUnifiedDiffRenderer` / `parseUnifiedDiff` / `renderTextWithRedactions` / `MetadataChrome` | **REUSE** | via `@/lib/sanitization` barrel — Reconciliation 7/8 |
| `useReturnToRunContext` (2.22) / `ErrorState`+`EmptyState`+`Skeleton` (2.22) / `useAllowedActions` (2.14) / `useArtifact` (live, 3a-9) | **REUSE** | Reconciliation 9/10 |
| Mobile bounded state (dedicated single-column, before/after toggle) | **DEFER** | story 4.21 (UX-DR23) — this story is the desktop/tablet composite |
| Decision Bar `recovery_operator` full activation (resume/reconcile/rerun/pause/classify) | **DEFER** | story 4.22 (separate surface) |
| `RevisionDelta` backend / OpenAPI regen / new `DomainErrorCode` / Flyway / new `WorkflowState`/`WorkflowEventType` | **NONE** | contract is frozen from 4.19; the only registry touch is the `AllowedAction` value |

## Acceptance Criteria

> From `epic-04-recovery.md` §"Story 4.20" (lines 432–450), with **binding clarifications** in **bold parentheticals**.

1. **Given** `src/features/workflows/components/CompareMode.tsx`, **Then** the component accepts `CompareModeProps { artifactIdA, artifactIdB, onExit }` and consumes `useRevisionDelta(artifactIdA, artifactIdB)` (TanStack Query hook backed by the story 4.19 REST endpoint). **(Split presentational `CompareMode` + `CompareModeContainer` — Reconciliation 1. The hook is NET-NEW against the EXISTING endpoint; new key `workflowKeys.revisionDelta(a,b)` off `all`, not `detail(runId)` — Reconciliation 11. NO Idempotency-Key.)**

2. **Given** anatomy per UX-DR13, **Then** the component displays: revision A + revision B identifiers (`version`, `producedByActor`, `createdAt` from `ArtifactRevisionSummary`), summary header (changed-region count + added/removed/modified counts from `RevisionDeltaSummary`), side-by-side or stacked comparison surface (variant-driven per `artifactType`), changed-region indicators (scroll-gutter markers), filter controls (toggle "Show only changes" / "Show all"), jump controls (next/previous changed region, J/K), exit-back-to-review control. **(`producedByActor`/`checksum` are nullable on the wire — render "unknown"/omit when null. A/B = prior/current, left/right — Reconciliation 5. Gutter markers non-color per 2.3 AC5 — Reconciliation 12.)**

3. **Given** states per UX-DR13, **Then** the component renders: `default comparison`, `loading` (skeleton matching layout), `no meaningful diff` (`noMeaningfulDiff===true` → "These revisions are identical"), `no baseline available` (404 `ARTIFACT_RECORD_NOT_FOUND`), `partial comparison available` (503 `ARTIFACT_PAYLOAD_UNAVAILABLE` → story-2.22 `ErrorState` `nextAction`), `diff unavailable` / `error` (400 `ARTIFACT_LINEAGE_MISMATCH` / `INVALID_ID_PREFIX` / transport). **(Live 4.19 codes 400/404/503, NOT the epic's 409 — Reconciliation 10. Branch on `isProblemDetailsError(error).code`, never human text. Exactly one state reachable per render, exposed as `data-compare-state` — mirror `data-artifact-panel-state`.)**

4. **Given** variants per UX-DR13, **Then** the component supports: `side-by-side compare` (default for spec + plan; horizontal split, synced scroll), `stacked compare` (default for prOutput; vertical, file accordions), `summary-first compare` (summary header + collapsed change blocks; expand for detail), `spec revision compare` (`markdown` blocks, section-level granularity), `plan revision compare` (`planStep` blocks, step ordering visible via `priorStepOrder`/`currentStepOrder`). **(Layout picked by `RevisionDelta.artifactType`; per-block renderer picked by `RevisionDeltaChange.blockType` — Reconciliation 6. `summary-first` is an orthogonal toggle available for all types.)**

5. **Given** sanitization per story 2.24, **Then** all rendered delta text passes through `SafeMarkdownRenderer` (spec) or plain-text/diff rendering (plan + prOutput); the story-2.24 AC7 XSS fixtures are exercised against the compare renderer in tests. **(spec text → `SafeMarkdownRenderer`; plan step text → React-escaped plain text; prOutput `filePath` → `renderTextWithRedactions`; lazy-loaded full diff → `SafeUnifiedDiffRenderer` — Reconciliation 7/8. Barrel imports only.)**

6. **Given** keyboard navigation per UX-DR13 + story 2.25, **Then**: Tab cycles summary header / filter controls / changed regions / exit; J/K (or Down/Up Arrow) jump to next/previous changed region with smooth scroll; Esc exits compare mode and returns to the originating review context (`useReturnToRunContext`, UX-DR16). **(Announcements via ONE `aria-live` region + centralized vocabulary in `announcements.ts` — Reconciliation 12. `scrollIntoView` guarded for jsdom no-op — mirror `SpecArtifactRenderer.scrollToHeading`.)**

7. **Given** content guidelines per UX-DR13, **Then** the renderer prioritizes changed regions (scroll focus follows the next changed region by default), keeps compare scoped + task-driven (no extraneous metadata panels), summarizes what changed before dense detail (collapsed-by-default in summary-first). **(The "Show only changes" filter defaults ON where a diff is non-trivial; the summary header is always first.)**

8. **Given** mode boundary per UX-DR13 + story 2.7 layout ADR, **Then** Compare Mode is a deeper inspection state of the SAME workflow review — not a separate route or sub-product; entering from an Artifact Review Panel uses an in-context overlay / expanded panel within the AppShell tri-pane, preserving run + artifact context (UX-DR16). **(NO new route/param — Reconciliation 9. Entry toggles local overlay state; identity preserved via the existing `$artifactId` route.)**

9. **Given** allowed-actions integration (story 2.14), **Then** the entry control to Compare Mode (the disabled control from story 2.17 AC2 / story 3.27 AC9) is activated when backend allowed-actions include `enter_compare_mode` (added to the registry per the drift test) — typically when ≥2 versions exist in the artifact's lineage. **(TWO-site registry add + pin — Reconciliation 3. Matrix surfacing at review/operator states — Reconciliation 4. FE literal rename `'compare'`→`'enter_compare_mode'` at 2 sites, combined with the existing `hasComparableRevision` `version>1` gate — Reconciliation 2.)**

10. **Given** ARP integration, **Then** Compare Mode is launched from: (a) the ARP "Compare with previous revision" control (default — current vs immediately-prior version), (b) an explicit "Compare with revision N" dropdown when the artifact has >2 versions, (c) the operator deep-dive view (story 4.4) for failure-context comparison. **((a) is the primary path this story wires end-to-end: the existing Compare control's onClick opens the overlay with `{ artifactIdB = current artifactId, artifactIdA = immediately-prior version id }`. (b)/(c) — see OQ-2: the prior-version id source depends on whether `useArtifact`/`latestArtifacts` expose the lineage; provisional binding wires (a) fully and renders (b) only when a lineage list is available, else it stays a reserved control.)**

11. **Given** component test coverage, **Then** tests cover: side-by-side renders with synced scroll, stacked renders for prOutput, summary-first collapsed-then-expanded, no-meaningful-diff empty state, partial-comparison error state with next action, J/K jump shortcuts, Esc-to-exit returns to prior context, sanitization rejects scriptable payloads in delta content, allowed-actions integration enables/disables the entry control, ARP launch contexts work, axe-core a11y zero violations. **(Presentational tests drive `CompareMode` with fixtures router/query-free; container tests mock `useRevisionDelta`; the ARP-integration + rename tests extend `ArtifactReviewPanel.test.tsx` / `PrOutputArtifactRenderer.test.tsx`. Backend: `WorkflowInspectionServiceAllowedActions*Test` + `AllowedActionsEndpointContractTest` + `RegistryContractTest` + `AllowedActionRegistryPinTest`.)**

## Tasks / Subtasks

- [x] **Task 1 — Backend: register `enter_compare_mode` + surface it in the action matrix (AC9)**
  - [x] Site 1: add `ENTER_COMPARE_MODE("enter_compare_mode")` to `domain/registry/AllowedAction.java` with a story-4.20 comment mirroring the recovery-action comment style (note "open string[] → no OpenAPI regen; no DB CHECK").
  - [x] Site 2: append `"enter_compare_mode"` to `test/resources/contracts/frontend/allowed-actions.placeholder.json`.
  - [x] Add `enterCompareModeWireValueIsPinned()` to `architecture/AllowedActionRegistryPinTest.java` (`assertThat(AllowedAction.ENTER_COMPARE_MODE.value()).isEqualTo("enter_compare_mode")`).
  - [x] Add `appendCompareOverlay(result, state, actorRole)` to `WorkflowInspectionService.computeActionMatrix` (mirror `appendConflictOverlay`) surfacing `ENTER_COMPARE_MODE` for `WAITING_FOR_SPEC_APPROVAL` (product_reviewer/workflow_owner), `WAITING_FOR_REVIEW` (developer), `FAILED`/`PAUSED` (workflow_owner). Additive + no-op for every other state×role (all other rows byte-identical). (Reconciliation 4 / OQ-1)
  - [x] Confirm `RegistryContractTest.allowedActionsStayAlignedWithFrontendPlaceholder` passes (enum ↔ placeholder parity round-trips). [[new-workflowcommand-permit-updates-symmetry-contract]] does NOT apply (allowed-actions ≠ command permits).

- [x] **Task 2 — `useRevisionDelta` hook + query key (AC1)**
  - [x] Add `workflowKeys.revisionDelta(artifactIdA, artifactIdB)` = `[...workflowKeys.all, 'revisionDelta', artifactIdA, artifactIdB] as const` (a child of `all`, mirroring `artifact(artifactId)`). (Reconciliation 11)
  - [x] Create `hooks/useRevisionDelta.ts`: `apiClient.GET('/api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}', ...)` → `unwrap` → `RevisionDelta`. `enabled` only when both ids non-empty. `staleTime: STALE_TIME.detail`. NO Idempotency-Key. Exports `type RevisionDelta`.
  - [x] Unit test `useRevisionDelta.test.tsx` (MSW): success shape, disabled when an id is empty, key off `all`, 404/503/400 surface as `ProblemDetailsError`.

- [x] **Task 3 — `normalizeRevisionDelta` + per-block discriminated union (AC4, AC6)**
  - [x] Create `compareView.ts`: strict `CompareView` model + `normalizeRevisionDelta` narrowing the loose generated shape into `{ artifactType, revisionA, revisionB, summary, noMeaningfulDiff, blocks: ChangeBlockView[], linkedDiffReferences }`; `ChangeBlockView` discriminated on `kind` (`markdown`/`planStep`/`file`); `changeKind` narrowed to the closed set; malformed/unknown blocks skipped (mirror `isArtifactView`). Pure + unit-tested. + `compareLayout` helper.
  - [x] `resolveCompareState({ hasBaseline, isError, isLoading, delta, error }): CompareState` — pure precedence `no-baseline → error → loading → no-meaningful-diff → default`, error sub-classified by ProblemDetails code into `no-baseline (404) | partial (503) | unavailable (400/transport)`. Exposed as `data-compare-state`. Unit-tested. (Reconciliation 10) — added the `hasBaseline` gate so the OQ-2 unresolved-baseline path resolves without firing a request.

- [x] **Task 4 — `CompareMode` presentational composite + per-block renderers (AC2, AC3, AC4, AC5, AC7)**
  - [x] `components/CompareMode.tsx`: presentational, prop-driven, router/query-free. Renders exactly one `data-compare-state`; `default` renders the summary header (A/B identifiers + counts), the filter toggle (default ON), jump controls, exit control, and the comparison surface (side-by-side 2-col synced scrollport for spec/plan; stacked file accordions for prOutput). Non-color gutter markers (symbol + label). `summary-first` toggle collapses blocks. Skeleton/`EmptyState`/`ErrorState` (with 2.22 `nextAction`) for the non-default states.
  - [x] Per-block renderers (inline): `MarkdownCell` → `SafeMarkdownRenderer`; `PlanStepCell` → React-escaped plain text + order badges + `changeKind` signifier; file blocks → `renderTextWithRedactions(filePath)` + counts + expand.
  - [x] prOutput lazy diff: `CompareFileDiff` mounts on expand (only when run + artifact ids present), reads via `useArtifact`, `parseUnifiedDiff` → `SafeUnifiedDiffRenderer` (`PR_DIFF_MAX_LINES`). (Reconciliation 8)

- [x] **Task 5 — Keyboard nav + a11y + announcements (AC6, AC11-axe)**
  - [x] J/K + Down/Up jump to next/previous changed region (guarded `scrollIntoView` + focus); Esc → exit. Bound as a native region-level listener (accelerator over natively-operable children). Focus follows the landed region.
  - [x] Added the `// ---- Compare Mode (story 4.20)` block to `lib/a11y/announcements.ts` (`compareLoaded`, `compareNoMeaningfulDiff`, `compareJumpedToRegion`, `compareExited`, `compareLoadFailed`), wired via `useLiveAnnouncement`. `check:a11y` green.
  - [x] `axe-core` zero-violations test on the default + no-meaningful-diff + partial-error renders.

- [x] **Task 6 — Container + ARP entry wiring + overlay mount (AC1, AC8, AC9, AC10)**
  - [x] `components/CompareModeContainer.tsx`: reads `useRevisionDelta`, resolves + normalizes, owns field-only structured logs (`compare.opened` / `compare.loadError` code+transport / `compare.exit`).
  - [x] Renamed `'compare'` → `'enter_compare_mode'` in `artifactView.ts` `canEnableCompare` + `PrOutputArtifactRenderer` (updated the stale doc comments). (Reconciliation 2)
  - [x] Threaded `onEnterCompare`/`onCompare` through `ArtifactReviewPanel(+Container)` → all three renderers; the Compare control's onClick opens the overlay. Overlay state + mount live in `$artifactId.tsx` (in-context, NO navigation); exit closes the overlay. (Reconciliation 9)
  - [x] "Compare with revision N" dropdown (AC10.b) — left reserved (OQ-2: no lineage version-list read exists today).

- [x] **Task 7 — Tests (AC11)**
  - [x] `CompareMode.test.tsx` (presentational, fixtures): side-by-side + synced-scroll, stacked prOutput accordions, summary-first collapse/expand, no-meaningful-diff, each error sub-state, J/K jump announce, Esc-exit, gutter markers non-color, filter default-ON toggle.
  - [x] Sanitization: scriptable payloads fed through `priorText`/`currentText`/`filePath` render no `<script>`/`img[onerror]`. (AC5)
  - [x] `CompareModeContainer.test.tsx`: mock `useRevisionDelta` → loading/default/404/503/400 mappings + `compare.loadError` code-only log assertion + unresolved-baseline path.
  - [x] Extended `ArtifactReviewPanel.test.tsx` / `SpecArtifactRenderer.test.tsx` / `PrOutputArtifactRenderer.test.tsx` / `ImplementationPlanArtifactRenderer.test.tsx` / `artifactView.test.ts`: Compare enabled ONLY when `enter_compare_mode` present AND `version>1`; onClick invokes the handler; old `'compare'` literal no longer enables.
  - [x] Backend: `WorkflowInspectionServiceAllowedActionsTest` (matrix surfaces `enter_compare_mode` at the bound states, absent elsewhere) + `AllowedActionsEndpointContractTest` (wire value at a review state) + `RegistryContractTest` + `AllowedActionRegistryPinTest` all green.
  - [x] Real frontend build (`npm run build` — tsc -b) green; `npm run check:api` in sync (NO schema change); `npm run check:a11y` green; full vitest 1407/0; eslint 0; prettier clean.

- [x] **Logging instrumentation** (cross-cutting)
  - [x] Frontend: field-only structured `console.*` at the container — `compare.opened` (ids), `compare.loadError` (code + transport), `compare.exit`. NEVER the raw error/delta text/body.
  - [x] Backend (matrix slice): pure read, no new SLF4J surface (the overlay is a no-op elsewhere; `getAllowedActions` logging unchanged).
  - [x] Pinned the compare `aria-live` announcement (component test) + the `compare.loadError` code-only log (container test).

### Review Findings

> From `bmad-code-review` (2026-07-16) — three adversarial layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor). Blind Hunter's diff read was truncated at ~2000 lines (missed 4 modified files); Edge Case Hunter + Acceptance Auditor had full project read access and covered them. 1 decision-needed, 2 patch, 3 deferred, 1 dismissed. **Alex resolved the decision-needed as "build it now" and both patches were applied (2026-07-16).**

- [x] [Review][Decision→RESOLVED: built] OQ-2 — prior-version artifact id source added. **Resolution (Alex chose to build now, not defer):** the backend already tracks the lineage parent (`ArtifactEntity.parentArtifact` → `ArtifactRecordSnapshot.parentArtifactId`); it just wasn't surfaced on the read. Added `parentArtifactId` to `ArtifactDetailView` + `ArtifactDetailResponse` (`@Schema(nullable=true)`), regenerated `openapi.json` (hand-mirrored the sibling `branch` field's exact springdoc shape — the snapshot regen needs Docker, unavailable locally) + `schema.d.ts`, threaded it through `ArtifactViewBase`/`toArtifactView`/`isArtifactView`, and resolved `artifactIdA = artifact?.parentArtifactId ?? ''` in `$artifactId.tsx`. A v1 artifact still resolves to "no baseline available" (correct — no prior version). **CI-GATE: `OpenApiSnapshotContractTest` + the artifact-read `@Tag("contract")` IT verify the byte-exact snapshot in CI only (Docker); locally verified: `npm run check:api` in sync, FE build + 1409 vitest green, `WorkflowInspectionServiceArtifactDetailTest` 21/0, backend test-compile + spotless clean.** (auditor; = story OQ-2)
- [x] [Review][Patch→APPLIED] prOutput Compare control now applies the `version > 1` gate [`components/PrOutputArtifactRenderer.tsx:197`] — replaced the self-derived `actions?.includes('enter_compare_mode') ?? false` with the SAME `canEnableCompare(actions, hasComparableRevision(artifact))` composition the container applies to spec/plan, so a v1 prOutput no longer offers a compare with no baseline. Tests updated (`PrOutputArtifactRenderer.test.tsx`) to lock: v1 stays disabled even with the action; v2+ enables. (blind+edge)
- [x] [Review][Patch→APPLIED] Compare Mode now focuses the section on entry [`components/CompareMode.tsx`] — added a mount `useEffect(() => sectionRef.current?.focus(), [])` so the native `keydown` accelerators (J/K/Esc) receive events immediately (previously focus fell to `document.body` after the ARP unmounted the focused Compare button). New regression test asserts `compare-mode` has focus on render. (edge)
- [x] [Review][Defer] `default` state with positive `changedRegionCount` but zero renderable blocks shows a contradictory surface [`components/CompareMode.tsx:448` + `compareView.ts`] — deferred, forward-compat only: `normalizeRevisionDelta` skips unknown `blockType`s (correct) but `summary.changedRegionCount` passes through verbatim, so a wire delta with `noMeaningfulDiff:false` + count>0 + all-unknown blocks renders "N changed regions" over an empty surface. The 4.19 endpoint only emits the three known block types today, so it cannot trigger. (edge)
- [x] [Review][Defer] Sanitization tests use hand-rolled XSS payloads, not the story-2.24 AC7 fixture corpus [`components/CompareMode.test.tsx`] — deferred, intent met: AC11's literal wording asks the 2.24 fixtures be exercised; the tests assert against inline `<script>`/`img[onerror]`/`javascript:` strings instead. Real sanitization is delegated to the barrel primitives (fixture-tested at 2.24) and this matches the sibling `PrOutputArtifactRenderer.test.tsx` convention. (auditor)
- [x] [Review][Defer] Exit control tab order deviates — rendered first, AC6 specifies last [`components/CompareMode.tsx:386`] — deferred, deliberate design: the exit affordance is intentionally rendered in the top bar in EVERY state ("the operator can always leave"), so DOM tab order is exit → filter/summary/jump → regions rather than the AC6-stated summary → filter → regions → exit. Esc also exits. (auditor)

## Dev Notes

### Relevant architecture patterns and constraints

- **The presentational + thin-container split (Reconciliation 1/9).** Every artifact/decision surface in this app is a presentational component (fixture-driven, router/query-free, tested directly) + a thin container that owns the hook read + structured logging. `CompareMode` MUST follow `ArtifactReviewPanel` / `ArtifactReviewPanelContainer` exactly: the panel takes a resolved `state` + `view` and renders; the container reads `useRevisionDelta`, resolves the state via `resolveCompareState`, and maps to props. This keeps the composite unit-testable without a router/query provider. [Source: `components/ArtifactReviewPanel.tsx:154-316`]
- **The compare-entry seam already exists (Reconciliation 2).** `ArtifactReviewPanel` threads `compareEnabled` into all three renderers; `canEnableCompare(actions, hasComparableRevision)` combines the backend action with a per-artifact `version>1` predicate. The Compare control is a `disabled` button with an "Available in next release" tooltip today. 4.20 renames the action literal, removes the disabled/tooltip when enabled, and gives the control an onClick destination. [Source: `artifactView.ts:414-433`; `components/SpecArtifactRenderer.tsx:260-268`; `components/ImplementationPlanArtifactRenderer.tsx:276-283`; `components/PrOutputArtifactRenderer.tsx:184-186,459-470`]
- **The action matrix is the SOLE state×role→action-set switch, ArchUnit-pinned (Reconciliation 4).** `WorkflowInspectionService.baseActionMatrix` is "the sole place in the codebase where state×role → action-set is encoded (UX-DR12 + ArchUnit pin). Any duplication outside this method is a future-bug seed." Overlays (`appendConflictOverlay`, `appendSplitOverlay`, archive/unarchive) are appended in `computeActionMatrix`. Add `appendCompareOverlay` the same way — additive, no-op outside the bound state/role set, so every other matrix row stays byte-identical. The matrix has NO artifact-version awareness by design; the FE re-gates on the concrete artifact. [Source: `WorkflowInspectionService.java:1613-1690,1709-1713`]
- **The allowed-actions wire field is an OPEN `string[]` — no OpenAPI regen, no DB CHECK (Reconciliation 3).** Every recovery-action enum comment states this. The ONLY parity gate is `AllowedAction` enum ↔ `allowed-actions.placeholder.json` via `RegistryContractTest.allowedActionsStayAlignedWithFrontendPlaceholder`, plus the focused wire-value pins in `AllowedActionRegistryPinTest`. Adding a value is a two-site + one-pin change. [Source: `AllowedAction.java:104-142`; `RegistryContractTest.java:460-463`; `AllowedActionRegistryPinTest.java`]
- **Sanitization barrel discipline (Reconciliation 7/8).** Untrusted runner text renders ONLY through `@/lib/sanitization` barrel primitives; direct imports from files inside the package are forbidden outside the package (`no-unsanitized-html` + `--max-warnings=0`). Markdown → `SafeMarkdownRenderer`; unified diff → `SafeUnifiedDiffRenderer` + `parseUnifiedDiff` (+ `PR_DIFF_MAX_FILES`/`PR_DIFF_MAX_LINES`); inline text with redaction markers → `renderTextWithRedactions`; trusted metadata → `MetadataChrome`. Step text is escaped PLAIN text (never markdown inside interactive elements — the `ImplementationPlanStep.summary` precedent). [Source: `lib/sanitization/index.ts:1-35`; `artifactView.ts:73-80`]
- **Query-key factory + hierarchy (Reconciliation 11).** Every TanStack key comes from `workflowKeys` (inline arrays are a build-failing anti-pattern, `no-inline-query-keys`). `revisionDelta(a,b)` mirrors `artifact(artifactId)` — a child of `all`, NOT `detail(runId)`, because a compare spans an artifact lineage independent of a single run. [Source: `lib/queryKeys/workflowKeys.ts:61-158`]
- **Return-to-context on exit (Reconciliation 9).** `useReturnToRunContext` walks the breadcrumb stack backwards to the prior run-centered entry; falls back to `/workflows` on an empty stack (NEVER `history.back()`). Esc + the exit control both call it. Because Compare Mode is an in-context overlay (not a route), exit also just closes the overlay when it was opened from the same view. [Source: `lib/navigation/useReturnToRunContext.ts:26-61`]
- **Wire nullability (`producedByActor`/`checksum`/every block field).** The generated types make nearly everything optional/nullable. Guard `!= null` and render "unknown"/omit — never assume presence (mirror [[workflowdetail-wire-sends-null-not-undefined]]). `noMeaningfulDiff`/`changes` may be absent → treat absent `changes` as `[]`. [Source: `schema.d.ts:2404-2468,1339-1357`]
- **`useArtifact` is LIVE (story 3a-9).** The artifact-read hook fetches when both ids are present (`enabled: workflowRunId.length>0 && artifactId.length>0`), so the prOutput lazy-diff path (Reconciliation 8) reuses it directly; do NOT invent a new read. Note the `ArtifactReviewPanel` header comments still describe it as a "disabled stub" — that comment is stale; the route (`$artifactId.tsx:75-80`) confirms it's enabled. [Source: `hooks/useArtifact.ts`; `routes/workflows/$workflowRunId/artifacts/$artifactId.tsx:75-80`]

### Logging Requirements (project-wide standard)

This is a frontend-heavy story; the "logging" contract is the FE's structured-`console` idiom (the same one `QueueShell` / `RunContextStrip` / `ArtifactReviewPanelContainer` use) plus the trivially-observable backend matrix slice.

- **Framework:** structured `console.{info,warn,debug}` objects at the CONTAINER (never in presentational components). No raw string concatenation; no logging of untrusted delta text / artifact body / error messages — only stable ProblemDetails `code` + a transport flag.
- **Where to log (minimum surface):** `compare.opened` (info, ids), `compare.loadError` (warn, code + transport), `compare.exit` (info), `compare.jump` (debug, index/total). Backend: reuse the existing `getAllowedActions` logging; the overlay is a pure no-op elsewhere so no per-row logs.
- **Required context keys (FE):** `artifactIdA`, `artifactIdB`, plus `code`/`transport` on the error path. (No MDC on the FE; these are structured-object fields.)
- **Forbidden in log output:** delta text, artifact body, diff content, raw error messages, secrets/PII.
- **Test contract:** pin the `compare.loadError` code-only log + one compare `aria-live` announcement with the existing test idiom.

### Project Structure Notes

- New frontend: `features/workflows/components/CompareMode.tsx` + `CompareModeContainer.tsx` (+ per-block sub-views), `features/workflows/hooks/useRevisionDelta.ts`, `features/workflows/compareView.ts` (normalize + state resolver), the `revisionDelta` key in `lib/queryKeys/workflowKeys.ts`, the Compare-Mode vocabulary block in `lib/a11y/announcements.ts`, and the overlay mount + onClick wiring in `routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` / `ArtifactReviewPanel`. Edits: `artifactView.ts` + `PrOutputArtifactRenderer.tsx` (action-literal rename).
- New/changed backend: `domain/registry/AllowedAction.java` (+1 value), `test/resources/contracts/frontend/allowed-actions.placeholder.json` (+1 string), `architecture/AllowedActionRegistryPinTest.java` (+1 pin), `application/workflow/WorkflowInspectionService.java` (`appendCompareOverlay`), + the two matrix/endpoint tests.
- Variance: FIRST cross-run artifact-lineage-scoped FE query key (`revisionDelta` off `all`, not `detail(runId)`) — justified because a compare spans a lineage independent of any single run. NO new route (Compare Mode is an in-context overlay per AC8).
- NO OpenAPI regen (the compare contract shipped in 4.19; the allowed-action is an open string[]). NO Flyway, NO new `WorkflowState`/`WorkflowEventType`/`DomainErrorCode`.

### References

- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.20 (lines 432–450)] — AC1–AC11.
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Compare Mode / Revision Delta Summary (lines 1621–1672)] — UX-DR13 anatomy/states/variants/a11y/content/boundary.
- [Source: _bmad-output/implementation-artifacts/4-19-compare-mode-backend-revision-delta-service-for-spec-plan-pr-output.md] — the frozen `RevisionDelta` contract, A/B direction, error-code mapping, `blockType` discriminator, `linkedDiffReferences`.
- [Source: deliveryline-frontend/src/lib/api/schema.d.ts:2404-2468 (`RevisionDelta`/`RevisionDeltaChange`/`RevisionDeltaSummary`), :1339-1357 (`ArtifactRevisionSummary`), :3112-3160 (`compareArtifacts`)] — the generated types to consume (do NOT regenerate).
- [Source: deliveryline-frontend/src/features/workflows/artifactView.ts:414-433] — `canEnableCompare`/`hasComparableRevision` seam (rename `'compare'`→`'enter_compare_mode'`).
- [Source: deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx:154-316] — presentational + thin-container split to mirror; `compareEnabled` threading.
- [Source: deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx:71-134,260-268; ImplementationPlanArtifactRenderer.tsx:276-283; PrOutputArtifactRenderer.tsx:184-186,459-470] — the three Compare controls to wire; `scrollToHeading` jsdom-guard idiom; step-text escaping.
- [Source: deliveryline-frontend/src/lib/sanitization/index.ts:1-35] — the ONLY sanctioned import surface (`SafeMarkdownRenderer`, `SafeUnifiedDiffRenderer`, `parseUnifiedDiff`, `PR_DIFF_MAX_FILES/LINES`, `renderTextWithRedactions`, `MetadataChrome`).
- [Source: deliveryline-frontend/src/lib/navigation/useReturnToRunContext.ts:26-61] — exit-back-to-review (AC6/AC8).
- [Source: deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts:61-158] — key factory; `artifact(id)` precedent for the `revisionDelta(a,b)` key.
- [Source: deliveryline-frontend/src/features/workflows/hooks/useAllowedActions.ts:46-78] — the `apiClient.GET` + `unwrap` + `STALE_TIME.detail` idiom to mirror for `useRevisionDelta`.
- [Source: deliveryline-frontend/src/lib/a11y/announcements.ts:1-52,136-149 + useLiveAnnouncement.ts] — announcement vocabulary conventions (`npm run check:a11y`).
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx:71-115] — where the ARP mounts + where the Compare overlay hooks in (in-context, no new route).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java:104-142] — recovery-action precedent + the "open string[]/no-regen/no-DB-CHECK" note; add `ENTER_COMPARE_MODE`.
- [Source: deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json:3-36] — the parity placeholder (Site 2).
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java:87,99,460-463] — the enum↔placeholder drift test.
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java:127-163] — the wire-value pin precedent (add `enterCompareModeWireValueIsPinned`).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:1613-1690,1709-1713] — `computeActionMatrix` + overlay precedent + the ArchUnit-pinned single-switch (`appendCompareOverlay`).

### Open Questions (for Alex — do not block dev; provisional bindings applied)

- **OQ-1 — the `enter_compare_mode` matrix state/role set.** Provisional: surface at `WaitingForSpecApproval` (product_reviewer/workflow_owner), `WaitingForReview` (developer), `Failed`/`Paused` (workflow_owner). The FE combines this with the per-artifact `version>1` gate. Alternative: surface it in EVERY state that can show an artifact (broader) or make the matrix artifact-version-aware (couples the matrix to an artifact read — rejected, breaks the pure state×role switch). Confirm the state/role set.
- **OQ-2 — the prior-version artifact id source for AC10.a/b. [RESOLVED 2026-07-16 via code-review — Alex chose "build now".]** The default "Compare with previous revision" needs `artifactIdA` = the immediately-prior version's public id. **Resolution:** the artifact-read contract now exposes `parentArtifactId` (the lineage parent, sourced from the already-populated `ArtifactRecordSnapshot.parentArtifactId`), and `$artifactId.tsx` resolves `artifactIdA = artifact?.parentArtifactId ?? ''` — so AC10.a compares current-vs-immediately-prior end-to-end. A v1 artifact (no parent) still resolves to "no baseline available" (correct). AC10.b ("Compare with revision N" dropdown) remains reserved — a full lineage-list read (not just the single parent) is a follow-up if multi-revision selection is wanted.
- **OQ-3 — synced-scroll fidelity for side-by-side.** Provisional: implement a lightweight scroll-sync (mirror scrollTop ratio between the two panes) sufficient for the AC4 "synced scroll" + the test assertion; a pixel-perfect line-locked sync is out of scope. Confirm the fidelity bar.
- **OQ-4 — summary-first default per variant.** Provisional: `summary-first` collapsed-by-default for prOutput (dense file diffs) and expanded-by-default for spec/plan (fewer, section/step-level blocks). Confirm the per-variant default.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story workflow.

### Debug Log References

- Backend: appending `enter_compare_mode` to the version-agnostic matrix rippled the exact-match rows in `WorkflowInspectionServiceAllowedActionsTest` (5 `matrixCases` rows + 3 focused tests) and the reviewer-verdict pin — all updated + re-verified (`WorkflowInspectionServiceAllowedActionsTest` 59/0, `AllowedActionsEndpointContractTest` 11/0, `RegistryContractTest`/`AllowedActionRegistryPinTest` green). No OTHER backend test references `getAllowedActions`/`.actions()` (grep-confirmed) → no further ripple.
- FE: the region-level J/K/Esc keyboard accelerator initially tripped `jsx-a11y/no-noninteractive-element-interactions` on the `<section>` landmark (the disable directive couldn't anchor to the root JSX element). Resolved by binding the shortcut as a NATIVE `keydown` listener on a `sectionRef` (no JSX handler) — `fireEvent.keyDown` still dispatches to it, so the tests are unchanged.
- FE: `ErrorState` consumes `useReturnToRunContext` (breadcrumb provider) — the presentational `CompareMode` tests stub it (as the ARP tests do) so the error sub-states render router/query-free.

### Completion Notes List

**Scope delivered:** the FRONTEND-heavy Compare-Mode composite (UX-DR13) over the frozen story-4.19 `RevisionDelta` contract + the TINY additive backend registry slice. NO OpenAPI regen, NO Flyway, NO new `WorkflowState`/`WorkflowEventType`/`DomainErrorCode` — the only registry touch is the `AllowedAction.ENTER_COMPARE_MODE` value (Reconciliation 3 held).

- **Backend (AC9):** `AllowedAction.ENTER_COMPARE_MODE("enter_compare_mode")` + placeholder JSON entry + `enterCompareModeWireValueIsPinned` pin + `appendCompareOverlay` in `computeActionMatrix` (additive, no-op outside the bound state/role set → all other matrix rows byte-identical). Surfaced for WaitingForSpecApproval (product_reviewer/workflow_owner), WaitingForReview (developer), Failed/Paused (workflow_owner) per OQ-1. The matrix stays version-agnostic; the FE re-gates on the concrete artifact `version>1`.
- **FE contract layer:** `useRevisionDelta` hook + `workflowKeys.revisionDelta(a,b)` (off `all`, not `detail(runId)` — Reconciliation 11); `compareView.ts` = `normalizeRevisionDelta` (loose wire → strict `ChangeBlockView` discriminated union, skips malformed blocks) + `resolveCompareState` (pure precedence, error sub-classified by the LIVE 4.19 codes 404/503/400 — Reconciliation 10).
- **FE composite:** `CompareMode` (presentational) + `CompareModeContainer` (thin data seam). Layout by `artifactType` (spec/plan side-by-side synced-scrollport; prOutput stacked file accordions); per-block renderer by `kind`. All delta text sanitized via the `@/lib/sanitization` barrel (spec→`SafeMarkdownRenderer`, planStep→escaped plain text, prOutput `filePath`→`renderTextWithRedactions`, lazy full diff→`SafeUnifiedDiffRenderer`). Non-color gutter markers; J/K/Esc keyboard nav; ONE `aria-live` region + a new announcement vocabulary block. In-context overlay mounted in `$artifactId.tsx` (AC8 — NO new route). FE literal renamed `'compare'`→`'enter_compare_mode'` at both seam sites; the ARP Compare control is now activated (onClick opens the overlay).

**OQ provisional bindings applied (per the story's "do not block dev" header):**
- **OQ-1** — matrix state/role set as above. Confirm the exact set.
- **OQ-2 (needs Alex)** — there is NO resolvable prior-version artifact id today: `ArtifactDetail`/`LatestArtifact` expose `version` but no `parentArtifactId`/lineage-id list (verified in `schema.d.ts`). So the ARP Compare control opens the overlay with `artifactIdB = current` and `artifactIdA = ''` → the surface renders the **"no baseline available"** state. The whole composite (side-by-side/stacked/summary-first/errors) is fully exercised by fixtures; only the live end-to-end real-diff path is gated on a future lineage-list read. The "Compare with revision N" dropdown (AC10.b) stays reserved. **Decision needed: add a backend lineage-list read now (expands scope beyond the TINY registry touch) vs. defer to a follow-up.**
- **OQ-3** — synced scroll implemented as a single scrollport with a 2-column prior|current grid (inherently synced) rather than two ratio-mirrored panes; sufficient for AC4 + the test. Confirm the fidelity bar.
- **OQ-4** — summary-first default: collapsed for prOutput, expanded for spec/plan (seeded once per artifactType). Confirm.

**Verification (all green):** BE `WorkflowInspectionServiceAllowedActionsTest` 59/0 + `AllowedActionsEndpointContractTest` 11/0 + `RegistryContractTest`/`AllowedActionRegistryPinTest` (aggregate 70–72/0), spotless clean. FE full vitest **1407/0**, `npm run build` (tsc -b + vite) green, eslint 0, `check:api` in sync (no schema change), `check:a11y` 4/0, prettier clean. Backend ITs / ArchUnit-Failsafe tier not run locally (no Docker) — the change is additive + mirrors the existing `appendConflictOverlay`/`appendSplitOverlay` overlay pattern the ArchUnit single-switch pin already tolerates.

### File List

**Backend (modified):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/AllowedAction.java` (+`ENTER_COMPARE_MODE`)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (`appendCompareOverlay` + wiring; **code-review OQ-2: +`parentArtifactId` on `ArtifactDetailView` + construction**)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ArtifactDetailResponse.java` (**code-review OQ-2: +`parentArtifactId` field + `@Schema` + `.from()`**)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (**code-review OQ-2: +`parentArtifactId` on `ArtifactDetail` schema — hand-mirrored sibling shape, CI regen-verified**)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceArtifactDetailTest.java` (**code-review OQ-2: +parent-surfacing + null-parent assertions**)
- `deliveryline-backend/src/test/resources/contracts/frontend/allowed-actions.placeholder.json` (+`enter_compare_mode`)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/AllowedActionRegistryPinTest.java` (+pin)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceAllowedActionsTest.java` (affected matrix rows)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/AllowedActionsEndpointContractTest.java` (+wire-value test)

**Frontend (new):**
- `deliveryline-frontend/src/features/workflows/hooks/useRevisionDelta.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useRevisionDelta.test.tsx`
- `deliveryline-frontend/src/features/workflows/compareView.ts`
- `deliveryline-frontend/src/features/workflows/compareView.test.ts`
- `deliveryline-frontend/src/features/workflows/components/CompareMode.tsx`
- `deliveryline-frontend/src/features/workflows/components/CompareMode.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/CompareModeContainer.tsx`
- `deliveryline-frontend/src/features/workflows/components/CompareModeContainer.test.tsx`

**Frontend (modified):**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (**code-review OQ-2: regenerated — `ArtifactDetail.parentArtifactId`**)
- `deliveryline-frontend/src/lib/api/queryOptions.ts` (**code-review OQ-2: `toArtifactView` maps `parentArtifactId`**)
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` (+`revisionDelta`)
- `deliveryline-frontend/src/lib/a11y/announcements.ts` (+Compare-Mode vocabulary block)
- `deliveryline-frontend/src/features/workflows/artifactView.ts` (`canEnableCompare` literal rename)
- `deliveryline-frontend/src/features/workflows/artifactView.test.ts`
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx` (+`onEnterCompare` threading)
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.tsx` (+`onCompare`)
- `deliveryline-frontend/src/features/workflows/components/SpecArtifactRenderer.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.tsx` (+`onCompare`)
- `deliveryline-frontend/src/features/workflows/components/ImplementationPlanArtifactRenderer.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.tsx` (literal rename + `onCompare`)
- `deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.test.tsx`
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` (in-context overlay mount; **code-review OQ-2: `artifactIdA = artifact?.parentArtifactId ?? ''`**)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/artifacts/$artifactId.test.tsx` (**code-review OQ-2: end-to-end Compare-opens-against-parent test**)
- `deliveryline-frontend/src/features/workflows/artifactView.ts` (**code-review OQ-2: `parentArtifactId` on `ArtifactViewBase` + `isArtifactView` guard**)
- `deliveryline-frontend/src/features/workflows/components/CompareMode.tsx` (**code-review patch: focus section on mount**)

## Change Log

| Date | Change |
|---|---|
| 2026-07-16 | Implemented story 4.20 (Compare Mode UI, UX-DR13) via bmad-dev-story: backend `enter_compare_mode` registry slice + `appendCompareOverlay`; FE `useRevisionDelta` hook + `revisionDelta` key, `compareView` (normalize + state resolver), `CompareMode` composite + `CompareModeContainer`, Compare-Mode announcement vocabulary, `'compare'`→`'enter_compare_mode'` seam rename, in-context overlay in the artifact route. All 4 OQs applied as provisional bindings (OQ-2 flagged for Alex: no lineage-id source → primary path renders "no baseline available" until a lineage read lands). Verified: BE allowed-action tests green, FE build + 1407 vitest + lint + check:api + check:a11y green. Status `in-progress → review`. |
| 2026-07-16 | `bmad-code-review` (3 adversarial layers → 1 decision, 2 patch, 3 defer, 1 dismiss). Alex resolved OQ-2 as **build now**: surfaced the lineage parent as `parentArtifactId` on the artifact-read contract (`ArtifactDetailView` + `ArtifactDetailResponse` + `openapi.json` + regenerated `schema.d.ts` + `toArtifactView`/`ArtifactViewBase`/`isArtifactView`), wiring `artifactIdA = artifact?.parentArtifactId ?? ''` so AC10.a compares current-vs-immediately-prior end-to-end. Patches applied: prOutput Compare control now version>1-gated (`canEnableCompare`); Compare Mode focuses its section on entry so J/K/Esc work. 3 findings deferred (see Review Findings + deferred-work.md), 1 dismissed ("Show only changes" no-op, by design). Verified locally: FE build + **1409** vitest + lint + `check:api` + `check:a11y` green; BE `WorkflowInspectionServiceArtifactDetailTest` 21/0 + test-compile + spotless clean. **CI-gate: OpenAPI snapshot regen + artifact-read contract IT verify in CI only (Docker unavailable locally); the `openapi.json` edit hand-mirrors the verified sibling `branch` field.** Status `review → done`. |
