# Story 4.24: Failure-Taxonomy Classification UI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner classifying a failed run for cross-run pattern analysis (per story 4.9),
I want a dedicated UI (`FailureClassificationDialog`) for selecting the appropriate `FailureTaxonomyValue` — with taxonomy descriptions + example scenarios + deprecated-marker visibility — and submitting via story 4.14's `classify-failure` endpoint,
so that FR37 (workflow owners can apply a failure category) is wired in the UI with a richer experience than a flat dropdown — operators understand what each category means and pick consistently.

## Context & Central Reconciliation (READ FIRST)

**This is a FULL-STACK story, not FE-only.** The write path (`POST .../classify-failure` + `RecoveryService.classifyFailure` + all four taxonomy error codes + the `FailureTaxonomyValue` registry enum + the `FailureClassificationView` read model) is **done and committed** (stories 4.9 + 4.14). But three things the epic's ACs require **do not exist yet** and must be built here:

1. **⚠️ THE FIRST DEFINING GAP — `GET /api/v1/registries/failure-taxonomy` (AC1/AC3) is GREENFIELD. There is NO `RegistryController` and NO `/api/v1/registries/**` path anywhere in the codebase.** You are adding a brand-new controller with no sibling to copy. Follow the thin-controller convention of the other read endpoints (`getAllowedActions`/`getFailureDiagnostics`): no `Idempotency-Key`, no actor gate, no `role` — a public idempotent GET.

2. **⚠️ THE SECOND DEFINING GAP — the backend has NO descriptions, examples, or human-readable names for taxonomy values.** `FailureTaxonomyValue` (`domain/registry/FailureTaxonomyValue.java`) carries ONLY the wire `value` + a nullable `deprecatedReplacementValue` (+ derived `deprecated()`/`displayLabel()`). It has NO `description`/`examples`/`humanReadableName` field, and `displayLabel()` returns the raw snake_case wire value (or a `" (deprecated)"` affix), NOT a title-cased name. AC3 requires the endpoint to serve `{value, humanReadableName, description, examples, deprecated, replacementValue}`. **The prose (humanReadableName + description + examples) MUST be curated + added in this story** (see Reconciliation 3 for the recommended shape + the exact copy to ship). The domain enum stays minimal — do NOT pollute the `domain/registry` enum with prose (story 4.9 R2 deliberately kept it two-arg).

3. **⚠️ THE THIRD DEFINING GAP — a run's *current* classification is NOT exposed over REST.** `WorkflowInspectionService.getFailureClassification(runId) → FailureClassificationView` (built in story 4.9) is consumed ONLY by its own tests — no controller calls it, and `WorkflowDetailResponse` carries no classification field. AC2 (prior-classification visible in the dialog), AC5 (pre-select prior/replacement), and AC9 (badge in Run Context Strip) all need a read surface. **Build a dedicated `GET /api/v1/workflows/{workflowRunId}/failure-classification` endpoint** (Reconciliation 4) — this avoids the `WorkflowDetailResponse` exact-field-contract fan-out ([[workflow-summary-exact-field-contract-test]]) and the `WorkflowStatusView` widening.

**⚠️ THE FOURTH TRAP — the classify write endpoint + its DTOs are ALREADY generated into the frontend client.** `src/lib/api/schema.d.ts` already has `POST /api/v1/workflows/{workflowRunId}/classify-failure`, `ClassifyFailureRequest` (`{ role, taxonomyValue: <6-value union>, reasonText? }`), and `ClassifyFailureResponse`. Do NOT re-add them — build the `useClassifyFailure` mutation hook on top of the existing generated types. The `taxonomyValue` union in the generated client is the authoritative wire enum; the dialog's radio cards render one card per union member.

**⚠️ THE FIFTH TRAP — `role: 'workflow_owner'` is a REQUIRED body field on classify (not a header).** The generated `ClassifyFailureRequest.role` is required and the backend validates it to `workflow_owner` then discards it. The mutation body MUST send `role: 'workflow_owner'` (reuse `RECOVERY_OPERATOR_ROLE` from `approvalDecisionView.ts`). `reasonText` is genuinely optional (spread-omit when blank — a blank reason stores `null`, there is NO `MISSING_REASON_TEXT` on this path).

### HEADLINE RECONCILIATIONS

1. **The write half is DONE — verify, do not build.** Committed + `done`: `RecoveryService.classifyFailure` (5 positional args), `POST .../classify-failure` (`WorkflowController.classifyFailure`, `~:2561`), `ClassifyFailureRequest`/`ClassifyFailureResponse`, all four taxonomy codes (`MISSING_TAXONOMY_VALUE` 400 · `INVALID_TAXONOMY_VALUE` 400 · `DEPRECATED_TAXONOMY_VALUE` 400 w/ `details.replacementValue` · `CLASSIFY_NOT_APPLICABLE` 409), `FailureTaxonomyValue` (six active values, zero deprecated today), `FailureTaxonomyPolicy.requireNotDeprecated`, `WorkflowEventType.RECOVERY_FAILURE_CLASSIFIED`, Flyway V44, the `classify_failure` allowed-action on `case FAILED` for `workflow_owner`, and `WorkflowInspectionService.getFailureClassification → FailureClassificationView`. **NO Flyway migration, NO new `DomainErrorCode`, NO new `WorkflowEventType`, NO new `AllowedAction`, NO new registry value, NO change to the write endpoint or its DTOs.** [Source: 4-9…md; 4-14…md; DomainErrorCode.java:298-301; FailureTaxonomyValue.java:25-30]

2. **Two NEW read endpoints + one curated metadata source are the entire backend scope.** (a) `GET /api/v1/registries/failure-taxonomy` (new `RegistryController`); (b) `GET /api/v1/workflows/{workflowRunId}/failure-classification` (new method on the existing `WorkflowController`, mapping `getFailureClassification`); (c) a curated `FailureTaxonomyCatalog` (application layer) supplying `humanReadableName`/`description`/`examples` per wire value. Everything else is FE.

3. **⚠️ The taxonomy metadata belongs in a NEW curated catalog, NOT on the domain enum, and it MUST have a drift test.** Recommended shape: `application/recovery/FailureTaxonomyCatalog.java` (a `@Component` or a pure static registry) exposing `List<FailureTaxonomyMetadataView> allValues()` where `FailureTaxonomyMetadataView(String value, String humanReadableName, String description, List<String> examples, boolean deprecated, String replacementValue)`. It ITERATES `FailureTaxonomyValue.values()` (so `value`/`deprecated`/`replacementValue` are sourced from the single domain registry — never re-listed) and JOINS each to a curated `humanReadableName`/`description`/`examples` map keyed by wire value. **Add a contract test asserting the catalog's key set equals `DomainRegistry.failureTaxonomyValues()`** — a future registry addition/deprecation (governed by ADR 0035) that forgets the prose then reds the build, never ships a value with no description (mirror the drift-test discipline of `RegistryContractTest`). The `RegistryController` maps `FailureTaxonomyMetadataView → TaxonomyValue` DTO 1:1. See OQ-1 for the enum-vs-catalog decision and OQ-3 for reconciling the copy against `docs/adr/0035-failure-taxonomy-governance.md`. **Curated copy to ship (reconcile against ADR 0035 first):**

   | value | humanReadableName | description | examples |
   |---|---|---|---|
   | `specification_gap` | Specification Gap | The failure traces to missing, ambiguous, or incorrect requirements in the spec — not to execution. | "Acceptance criteria omitted a required edge case." · "The spec didn't define the error-handling behavior the reviewer expected." |
   | `context_gap` | Context Gap | The agent lacked repository or domain context needed to complete the task correctly. | "The runner reimplemented a helper that already existed because it wasn't in the context bundle." · "A project convention wasn't surfaced to the agent." |
   | `agent_execution_failure` | Agent Execution Failure | The agent/runner failed to produce a valid result despite adequate spec and context. | "The runner produced malformed output that failed contract validation." · "The agent looped without converging on a fix." |
   | `review_rejection` | Review Rejection | The run failed because a human or automated review rejected the work product. | "The reviewer rejected the spec twice for scope creep." · "Automated review flagged an unaddressed security finding." |
   | `integration_or_merge_failure` | Integration or Merge Failure | The failure occurred at the integration boundary — push, merge, or external ticket sync. | "The push was rejected by a required status check." · "A merge conflict with concurrent external changes blocked delivery." |
   | `tooling_or_infrastructure_failure` | Tooling or Infrastructure Failure | The failure was caused by tooling, CI, or infrastructure rather than the work itself. | "The runner image lacked a required JDK." · "CI timed out during an infrastructure outage." |

4. **⚠️ The read endpoint maps the EXISTING `FailureClassificationView` — do NOT invent a new read model.** `WorkflowInspectionService.getFailureClassification(runId)` already returns `FailureClassificationView(currentTaxonomyValue, currentDisplayLabel, boolean deprecated, deprecatedReplacementValue, classifiedAt, classifiedBy, List<PriorClassification> priorClassifications)` with `PriorClassification(taxonomyValue, displayLabel, classifiedAt, classifiedBy)`. Bind `FailureClassificationResponse` as a faithful projection (all current-* fields NOT_REQUIRED/nullable — null == never classified; `priorClassifications` REQUIRED never-null empty list). A never-classified run returns 200 with `currentTaxonomyValue: null` + `priorClassifications: []` (NOT 404) so the FE can render "not yet classified" without special-casing an error. `RUN_NOT_FOUND` (404) only when the run id itself is unknown. Place the method next to the other `WorkflowController` reads; it needs NO `Idempotency-Key`/actor/`role`.

5. **The dialog is built from existing FE primitives — NOTHING net-new in `components/overlays` or `components/ui`.** Compose the controlled `ConfirmationDialog` (`{ open, onOpenChange, title, intent, consequence(REQUIRED), children, confirmLabel, cancelLabel, onConfirm, isConfirming, confirmDisabled }`) — replicate its explicit focus-capture/restore effect. Radio cards use the **native `<fieldset><legend>` + `<label><input type="radio" name=.../>` pattern already shipped in `ApprovalDecisionBar.tsx`'s `RejectionDialog`** (native radios give arrow-key roving focus + `radiogroup` semantics for free — AC10). Deprecated/reduced-prominence styling uses the **literal** `state-draft` Tailwind classes (`bg-state-draft`, `text-state-draft-foreground`, `border-state-draft-border`) + the `draft` state signifier — NEVER a dynamically-built `state-${x}` class (Tailwind purge trap). Mobile full-height (AC11) uses `BoundedDetailSheet` (`side="bottom"`, `fullHeightOnMobile`) OR the `sheet.tsx` `side="bottom"` primitive — do NOT nest a `ConfirmationDialog` inside a sheet (`T-NO-STACK`).

6. **Three query/mutation hooks, all on the existing factories.** (a) `useFailureTaxonomy()` — TanStack query over `GET /registries/failure-taxonomy`, keyed under a NEW `registryKeys.failureTaxonomy()` (NOT run-scoped — the registry is global), long `staleTime` (registry rarely changes); read-only, no idempotency key. (b) `useFailureClassification(runId)` — query over `GET .../failure-classification`, keyed under `workflowKeys.detail(runId)` **prefix** so the classify mutation's `detail(id)` invalidation refreshes it for free. (c) `useClassifyFailure(runId)` — mutation via the `useWorkflowMutation` factory (inherits UUIDv7 `Idempotency-Key` + `detail(id)`/`lists()` invalidation), body `{ role: RECOVERY_OPERATOR_ROLE, taxonomyValue, ...(reasonText spread when non-blank) }`, `apiClient.POST('/api/v1/workflows/{workflowRunId}/classify-failure', ...)` + `unwrap`. Copy `useRetryWorkflow.ts` for the body-spread + logging shape. Inline query-key arrays are ESLint-forbidden — use the key factories.

7. **The Decision-Bar launch context (AC8c) depends on story 4.22 (`onClassifyFailure` seam), which is `ready-for-dev` — NOT merged.** Story 4.22 renders the gated "Classify failure" entry-point button + fires `onClassifyFailure`; 4.24 owns the dialog + mutation the seam opens. Operator queue (4.2, **done**) and failure-diagnostics deep-dive (4.4, **done**) are the two launch contexts that exist today. **Bind: wire all three, but treat the 4.22 seam as the integration point that lands whenever 4.22 merges** — the dialog + hook are self-contained and can be developed/tested against 4.2 + 4.4 first, then attached to the 4.22 `onClassifyFailure` prop. Confirm 4.22's merge order (OQ-4). Do NOT rebuild the Decision Bar here.

8. **OpenAPI regen is MANDATORY and cascades into the frontend — TWO new paths.** `/registries/failure-taxonomy` + `.../failure-classification` change `openapi.json`; `OpenApiSnapshotContractTest` reds until regenerated with `-Dopenapi.snapshot.write=true` (writes then `fail()`s — expected), review + commit, re-run byte-identical, then `cd deliveryline-frontend; npm run generate-api` → `schema.d.ts` (else `check:api` reds). Expect two new paths + the new response schemas (`FailureTaxonomyRegistryResponse`, `TaxonomyValue`, `FailureClassificationResponse`, `PriorClassification`). Watch mojibake em-dash on Windows ([[mojibake-emdash-openapi-drift]]); a nullable object field → generated TS OPTIONAL (omit the key in fixtures, don't `:null`) ([[workflowdetail-wire-sends-null-not-undefined]]).

## Scope Boundary — what 4.24 BUILDS vs REUSES vs DEFERS

| Concern | 4.24 | Note |
|---|---|---|
| `RegistryController` + `GET /api/v1/registries/failure-taxonomy` → `FailureTaxonomyRegistryResponse { List<TaxonomyValue> values }` | **BUILD** | AC1/AC3 — R1/R2, greenfield controller |
| `TaxonomyValue` DTO `{ value, humanReadableName, description, examples: List<String>, deprecated, replacementValue? }` | **BUILD** | AC3 — R3 |
| `FailureTaxonomyCatalog` (application) + curated `humanReadableName`/`description`/`examples` per wire value + drift test vs `DomainRegistry.failureTaxonomyValues()` | **BUILD** | AC3 — R3, OQ-1/OQ-3 |
| `GET /api/v1/workflows/{workflowRunId}/failure-classification` → `FailureClassificationResponse` (projects `getFailureClassification`/`FailureClassificationView`) | **BUILD** | AC2/AC5/AC9 — R4 |
| `useFailureTaxonomy()` query hook + `registryKeys.failureTaxonomy()` | **BUILD** | AC1 — R6 |
| `useFailureClassification(runId)` query hook (keyed under `workflowKeys.detail(runId)`) | **BUILD** | AC2/AC5/AC9 — R6 |
| `useClassifyFailure(runId)` mutation hook (via `useWorkflowMutation`; body `{ role, taxonomyValue, reasonText? }`) | **BUILD** | AC6 — R6 |
| `FailureClassificationDialog.tsx` (radio cards + descriptions + examples + deprecated markers + prior-classification + reasonText + submit) | **BUILD** | AC1/AC2/AC4/AC5/AC6/AC7 — R5 |
| Wire launch contexts: operator queue (4.2 done), diagnostics deep-dive (4.4 done), Decision Bar `onClassifyFailure` seam (4.22) | **BUILD** | AC8 — R7 |
| Run Context Strip (2.16) classification badge — extend `RecoveryBaseline`; queue-row + diagnostics surfacing | **BUILD** | AC9 |
| ARIA radio-group keyboard nav + `useLiveAnnouncement` submission state; mobile full-height sheet | **BUILD** | AC10/AC11 |
| Component tests (Vitest + Testing Library + axe + MSW) + backend contract/snapshot tests | **BUILD** | AC12 |
| OpenAPI snapshot regen (`-Dopenapi.snapshot.write=true`) + FE `npm run generate-api` | **BUILD** | AC3 — R8 |
| `RecoveryService.classifyFailure`, `POST .../classify-failure`, `ClassifyFailureRequest`/`Response`, all four taxonomy codes, `FailureTaxonomyValue` enum, `FailureTaxonomyPolicy`, `RECOVERY_FAILURE_CLASSIFIED` event, V44 | **REUSE (done, 4.9 + 4.14)** | R1 |
| `WorkflowInspectionService.getFailureClassification` + `FailureClassificationView`/`PriorClassification` | **REUSE (done, 4.9) — now wired to REST here** | R4 |
| `ConfirmationDialog` / `BoundedDetailSheet` / `sheet.tsx` / `state-draft` tokens / `draft` signifier / `useLiveAnnouncement` / `SafeMarkdownRenderer` | **REUSE (existing primitives)** | R5 |
| `useWorkflowMutation` factory / `useRetryWorkflow` template / `apiClient` / `unwrap` / `workflowKeys` | **REUSE** | R6 |
| Any Flyway migration; new `DomainErrorCode`/`WorkflowEventType`/`AllowedAction`/registry value; change to the write endpoint or its DTOs | **DO NOT BUILD** | R1 |
| Extending `WorkflowDetailResponse`/`WorkflowStatusView` with a classification field | **DO NOT BUILD (use the dedicated read endpoint)** | R4, OQ-2 |
| Rebuilding the Decision Bar `recovery_operator` mode | **DO NOT BUILD (owned by 4.22)** | R7 |
| A named `FailureTaxonomyValue` OpenAPI component on the write DTO | **DO NOT BUILD (already an inline enum on `classify-failure`)** | R1 |

## Acceptance Criteria

> From `epic-04-recovery.md` §"Story 4.24" (lines 513–532), with **binding clarifications** in **bold parentheticals**.

1. **Given** `src/features/workflows/components/FailureClassificationDialog.tsx`, **Then** the component accepts `FailureClassificationDialogProps { workflowRunId, onClose }` and consumes `useFailureTaxonomy()` (TanStack Query hook fetching the typed taxonomy values with descriptions + deprecated markers from a new `GET /api/v1/registries/failure-taxonomy` endpoint added in this story). **(The dialog is controlled by a local `open` flag in each launch context; `onClose` maps to `onOpenChange(false)`. `useFailureTaxonomy()` returns `TaxonomyValue[]` — one radio card per entry. R1/R6.)**

2. **Given** anatomy, **Then** the dialog displays: header with run identifier + current failure context (failure category + reason text), **classification options** rendered as labeled radio cards (each card shows: `humanReadableName`, 1-2 sentence `description`, 1-2 `examples`, deprecated marker if `deprecated=true` with replacement-value pointer), prior classification visible if the run was previously classified (per story 4.9 AC9 — shows "Previously classified as X at Y by Z" + lets operator re-classify), optional `reasonText` textarea, Cancel + "Apply classification" buttons. **(Prior classification comes from `useFailureClassification(runId)` — R4. `failureCategory` for the header comes from the shared `useWorkflowDetail` cache (`failureCategory` field, humanized via `failureCategoryView`). Curated `description`/`examples` are backend-authored trusted strings — render as React-escaped plain text (auto-escaped); story 4.26 AC8 adds them to the XSS fixture set, so if you ever render them as markdown, route through `SafeMarkdownRenderer`.)**

3. **Given** the taxonomy registry endpoint, **Then** `GET /api/v1/registries/failure-taxonomy` returns `FailureTaxonomyRegistryResponse { values: List<TaxonomyValue> }` where each entry carries `{value, humanReadableName, description, examples: List<String>, deprecated: boolean, replacementValue?: string}`; idempotent read; OpenAPI documented; consumed by the dialog. **(NEW greenfield `RegistryController` — R1. `value`/`deprecated`/`replacementValue` sourced from `FailureTaxonomyValue`; `humanReadableName`/`description`/`examples` from the curated `FailureTaxonomyCatalog` — R3. `replacementValue` NOT_REQUIRED/nullable; `examples` REQUIRED never-null. No `Idempotency-Key`, no actor, no `role` gate.)**

4. **Given** deprecated-value handling (story 4.9 AC6 + story 4.14 AC4), **Then** deprecated taxonomy values render with reduced visual prominence (`state-draft` token treatment, story 2.3) + "(deprecated, use {replacementValue} instead)" affix; selecting a deprecated value shows a warning that submission will be rejected by the backend (`DEPRECATED_TAXONOMY_VALUE`, story 4.14 AC4) — the dialog actively guides operators away from deprecated values. **(⚠️ ZERO values are deprecated today — this path is exercised via a fixture/MSW response that marks one synthetic value deprecated, never a fake enum constant. Compose the affix in the FE from `deprecated`+`replacementValue`, NOT from a pre-affixed backend string. Deprecated card = literal `state-draft*` classes + `draft` signifier — R5.)**

5. **Given** prior-classification handling (story 4.9 AC9), **Then** when the run was previously classified, the dialog: (a) pre-selects the prior value (if not deprecated; else pre-selects the replacement value), (b) shows the prior classification provenance, (c) warns "This will re-classify the run. The prior classification remains in audit history." — operator confirms re-classification intentionally. **(Prior value + provenance from `useFailureClassification(runId)` — R4. If the prior value is deprecated, pre-select its `replacementValue` (resolved against the taxonomy list).)**

6. **Given** submission, **Then** "Apply classification" calls `useClassifyFailure` mutation calling story 4.14's endpoint with the selected `taxonomyValue` + optional `reasonText` + `Idempotency-Key` + `role=workflow_owner`; success closes the dialog + invalidates the workflow detail query to refresh the run's classification display. **(⚠️ `role: RECOVERY_OPERATOR_ROLE` is a REQUIRED body field, not a header — R6/Trap 5. `reasonText` spread-omitted when blank. Invalidation is free from the `useWorkflowMutation` factory (`detail(id)` prefix covers `useFailureClassification`). NEVER log `reasonText`.)**

7. **Given** error handling (story 4.14 AC4), **When** the backend returns `DEPRECATED_TAXONOMY_VALUE`, **Then** the dialog renders an inline error pointing to `details.replacementValue` and re-selects the recommended replacement. **(The typed error surfaces as `ProblemDetailsError` with `.code === 'DEPRECATED_TAXONOMY_VALUE'` and `details.replacementValue`; read it via `isProblemDetailsError` — R6. The client-side deprecation warning (AC4) is the first line of defense; this is the server-side backstop.)**

8. **Given** the dialog launch context, **Then** it can be invoked from: (a) the operator queue (story 4.2) via a "Classify" action on a failed-run row not yet classified, (b) the failure diagnostics deep-dive view (story 4.4), (c) the Decision Bar `recovery_operator` mode (story 4.22 AC7) when "Classify failure" is invoked. **(4.2 + 4.4 are `done`; 4.22 is `ready-for-dev` — wire its `onClassifyFailure` seam when it merges — R7/OQ-4. Each context owns a local `open` flag; the dialog is a shared component. "Not yet classified" gating uses `useFailureClassification` (`currentTaxonomyValue == null`) or the run's allowed-actions (`classify_failure`).)**

9. **Given** post-classification surfacing, **Then** after successful classification the run's failure classification appears across the UI: Run Context Strip (story 2.16) shows a "Failure classification: {humanReadableName}" badge, operator queue rows display it in their attention-indicator slot, failure diagnostics shows it with provenance. **(Run Context Strip: extend the existing `RecoveryBaseline` sub-region — it already renders only for `Failed` runs and shows failure category; add the classified taxonomy chip via `StateSignifierChip`, fed from `useFailureClassification` (keyed under `detail(id)` so classify invalidation refreshes it, no new fetch). Queue-row + diagnostics surfacing: see OQ-5 for whether the operator-runs-list DTO gains a classification field or the FE reads it per-row — recommend the shared cache read to avoid a queue DTO fan-out.)**

10. **Given** ARIA + accessibility (story 2.25), **Then** the radio cards are keyboard-navigable with arrow keys (native radio-group roving focus), each card labeled by its `humanReadableName` + describedby its description; deprecated markers announced by screen readers; an ARIA live region announces submission state. **(Native `<fieldset>/<legend>/<input type=radio name=...>` gives arrow-key nav for free — R5. Live region via `useLiveAnnouncement` — its return value defers one commit ([[livesnnouncement-defers-one-commit-test-flake]]); assert with `waitFor`. Deprecated affix must be in the accessible name/description, not color-only.)**

11. **Given** mobile responsiveness (story 2.26), **Then** at mobile breakpoint the radio cards stack vertically (default) + full-height sheet pattern (story 2.23 AC6). **(Use `BoundedDetailSheet` `side="bottom"` `fullHeightOnMobile` OR `sheet.tsx` bottom variant — do NOT nest `ConfirmationDialog` in a sheet (`T-NO-STACK`). One responsive presentation, not two divergent trees — OQ-6.)**

12. **Given** component test coverage, **Then** tests cover: radio cards render with descriptions + examples + deprecated markers; deprecated value reduced-prominence + warning; prior classification pre-selected (or replacement pre-selected when prior is deprecated); re-classification warning; submission via mutation + query invalidation; `DEPRECATED_TAXONOMY_VALUE` error re-selects replacement; all 3 launch contexts; post-classification visible across surfaces; axe-core zero `wcag2aa` violations. **Plus backend:** `RegistryController` contract test (endpoint shape + catalog-vs-registry drift), `failure-classification` endpoint contract test (never-classified → 200 null + `[]`; classified → prior list; unknown run → 404), `OpenApiSnapshotContractTest` green post-regen, `FailureTaxonomyCatalog` drift test. **(Run the REAL `npm run build` (`tsc -b` typechecks test files that `tsc --noEmit` misses — [[frontend-tsc-noemit-misses-test-files]]) + eslint + vitest before claiming FE green. Backend contract tests run in Failsafe — [[archunit-runs-in-failsafe-not-surefire]].)**

## Tasks / Subtasks

- [x] **Task 0 — Verify the 4.9 + 4.14 write half + read model are present (R1/R4)**
  - [x] Confirm `POST .../classify-failure` + `ClassifyFailureRequest`/`Response` exist in `WorkflowController` and in `schema.d.ts` (`classify-failure` path + the `taxonomyValue` 6-value union). If absent, 4.14 has not landed — STOP and rebase.
  - [x] Confirm `WorkflowInspectionService.getFailureClassification` + `FailureClassificationView`/`PriorClassification` exist (`WorkflowInspectionService.java:~3155,~4241,~4251`) and `FailureTaxonomyValue` has the six values + `deprecated()`/`deprecatedReplacementValue()`/`displayLabel()`.
  - [x] Confirm there is NO `RegistryController` / `/api/v1/registries/**` path yet (greenfield).

- [x] **Task 1 — Backend: curated taxonomy catalog + drift test (AC3, R3)**
  - [x] Add `application/recovery/FailureTaxonomyCatalog.java` exposing `List<FailureTaxonomyMetadataView> allValues()` (view record `(value, humanReadableName, description, examples, deprecated, replacementValue)`). Iterate `FailureTaxonomyValue.values()` for `value`/`deprecated()`/`deprecatedReplacementValue()`; join a curated static map (wire value → humanReadableName/description/examples) — copy the table in R3. Reconcile the copy against `docs/adr/0035-failure-taxonomy-governance.md` (OQ-3).
  - [x] Add `FailureTaxonomyCatalogTest` asserting the curated map's key set equals `DomainRegistry.failureTaxonomyValues()` (no missing/extra prose) + every entry has non-blank name/description + ≥1 example.
  - [x] `application → domain` import is allowed; the catalog does NOT import adapters (ArchUnit [[application-cannot-import-adapters]]).

- [x] **Task 2 — Backend: `RegistryController` + `GET /registries/failure-taxonomy` (AC1, AC3)**
  - [x] New `adapters/rest/RegistryController` `@RestController @RequestMapping("/api/v1/registries")`; `@GetMapping("/failure-taxonomy")` operationId `getFailureTaxonomyRegistry`; returns `FailureTaxonomyRegistryResponse`. Thin: call `failureTaxonomyCatalog.allValues()`, map to DTOs. NO idempotency/actor/role. `@Operation` + `@ApiResponses(200)` referencing `ProblemDetailsResponse` for 5xx only.
  - [x] DTOs `FailureTaxonomyRegistryResponse(List<TaxonomyValue> values)` + `TaxonomyValue(String value REQUIRED, String humanReadableName REQUIRED, String description REQUIRED, List<String> examples REQUIRED never-null, boolean deprecated, @Schema(nullable=true) String replacementValue)` + `static from(FailureTaxonomyMetadataView)`.
  - [x] `RegistryControllerTest` (`@WebMvcTest(controllers = RegistryController.class)`): 200 returns all six values; each carries name/description/≥1 example; `deprecated=false`/`replacementValue` omitted today; assert the `values[].value` set equals the six wire values (drift guard at the wire).

- [x] **Task 3 — Backend: `GET .../failure-classification` read endpoint (AC2, AC5, AC9, R4)**
  - [x] Add `WorkflowController.getFailureClassification(@PathVariable String workflowRunId)` `@GetMapping("/{workflowRunId}/failure-classification")` operationId `getFailureClassification`; call `workflowInspectionService.getFailureClassification(workflowRunId)`; map to `FailureClassificationResponse`. NO idempotency/actor/role (copy the `getFailureDiagnostics` read shape). `PublicIdPrefixes.require` for the run id → `INVALID_ID_PREFIX` (400); unknown run → `RUN_NOT_FOUND` (404). Reuse existing codes only.
  - [x] DTO `FailureClassificationResponse(String workflowRunId, @nullable String currentTaxonomyValue, @nullable String currentDisplayLabel, boolean deprecated, @nullable String deprecatedReplacementValue, @nullable OffsetDateTime classifiedAt, @nullable String classifiedBy, List<PriorClassification> priorClassifications)` + nested `PriorClassification(taxonomyValue, displayLabel, classifiedAt, classifiedBy)` + `static from(String runId, FailureClassificationView)`. Never-classified → all current-* null + `priorClassifications: []` + 200.
  - [x] `FailureClassificationEndpointContractTest`: never-classified → 200 (`currentTaxonomyValue` absent, `priorClassifications: []`); classified w/ priors → 200 (current + ordered priors + provenance); unknown run → 404; bad prefix → 400.

- [x] **Task 4 — Backend: OpenAPI snapshot + FE client regen (AC3, R8)**
  - [x] Regen `openapi.json` via `OpenApiSnapshotContractTest` `-Dopenapi.snapshot.write=true` (Failsafe; writes then `fail()`s — expected), review diff (two new paths + `FailureTaxonomyRegistryResponse`/`TaxonomyValue`/`FailureClassificationResponse`/`PriorClassification`), re-run byte-identical. Codepoint-check any em-dash ([[mojibake-emdash-openapi-drift]]). Add operationId assertions for `getFailureTaxonomyRegistry` + `getFailureClassification`.
  - [x] `cd deliveryline-frontend; npm run generate-api` → `schema.d.ts`; `npm run check:api` GREEN.

- [x] **Task 5 — FE: query + mutation hooks (AC1, AC2, AC6, R6)**
  - [x] `registryKeys.failureTaxonomy()` in a new/`src/lib/queryKeys/registryKeys.ts`. `useFailureTaxonomy()` in `src/features/workflows/hooks/` → `apiClient.GET('/api/v1/registries/failure-taxonomy')` + `unwrap`, long `staleTime`, read-only. Returns `TaxonomyValue[]`.
  - [x] `useFailureClassification(runId, { enabled })` → `apiClient.GET('/api/v1/workflows/{workflowRunId}/failure-classification')`, `queryKey: workflowKeys.failureClassification(runId)` (add to `workflowKeys` UNDER the `detail(id)` prefix so classify invalidation covers it), `staleTime: STALE_TIME.detail`.
  - [x] `useClassifyFailure(runId)` via `useWorkflowMutation<{ taxonomyValue; reasonText? }, ClassifyFailureResponse>`: body `{ role: RECOVERY_OPERATOR_ROLE, taxonomyValue, ...(reasonText?.trim() ? { reasonText } : {}) }`, `apiClient.POST('/api/v1/workflows/{workflowRunId}/classify-failure', { params:{ path, header:{ [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } }, body })` + `unwrap`. Field-only structured console: `recovery.classifySubmit` (response `taxonomyValue` only) / `recovery.classifyError` (stable `code` + transport flag). NEVER `reasonText`.

- [x] **Task 6 — FE: `FailureClassificationDialog.tsx` (AC1, AC2, AC4, AC5, AC6, AC7, AC10, AC11)**
  - [x] New `src/features/workflows/components/FailureClassificationDialog.tsx`, props `{ workflowRunId, onClose }` (+ internal `open`). Compose `ConfirmationDialog` (title "Classify failure", `intent="warning"`, `consequence` = "Classification is recorded in audit history for cross-run analysis. It does not change the run's state."), footer confirm "Apply classification".
  - [x] Header: run id + humanized `failureCategory` (from `useWorkflowDetail`) + current reason context.
  - [x] Radio cards: `<fieldset><legend>` + one `<label><input type="radio" name="taxonomy">` per `useFailureTaxonomy()` entry; card body = `humanReadableName` + `description` + `examples`. Deprecated card: literal `state-draft*` classes + `draft` signifier + "(deprecated, use {replacementValue} instead)" affix + on-select warning (AC4). Selection `useState<string>('')`.
  - [x] Prior-classification section (AC2/AC5) from `useFailureClassification`: provenance line + re-classify warning; pre-select prior value (or its replacement if deprecated) via an effect.
  - [x] Optional `reasonText` `<textarea>` (label, `aria-describedby`).
  - [x] Submit → `useClassifyFailure`; on success `onClose()`; on `DEPRECATED_TAXONOMY_VALUE` render inline error + re-select `details.replacementValue` (AC7). Confirm disabled while no selection or submitting.
  - [x] `useLiveAnnouncement` for submission state (AC10). Mobile: `BoundedDetailSheet` bottom/full-height (AC11) — OQ-6.
  - [x] Helper fns (safety/format) live in a sibling `.ts`, NOT exported from the `.tsx` ([[frontend-react-refresh-no-fn-exports]]).

- [x] **Task 7 — FE: wire launch contexts + Run Context Strip badge (AC8, AC9)**
  - [x] Operator queue (`src/features/workflows/OperatorQueue.tsx`): add a "Classify" row action for `Failed` runs not yet classified (gate on allowed-actions `classify_failure` or `useFailureClassification.currentTaxonomyValue == null`); local `open` state mounts the dialog. Ensure any queue search-param nav spreads existing params ([[tanstack-validatesearch-strips-unparsed-param]]).
  - [x] Diagnostics deep-dive (`src/features/workflows/components/FailureEventSurface.tsx`/deep-dive): add a "Classify failure" trigger → dialog; show classification + provenance when present.
  - [x] Decision Bar seam (4.22): pass a real handler to `ApprovalDecisionBar`'s `onClassifyFailure` prop that opens the dialog (wire when 4.22 merges — R7/OQ-4).
  - [x] Run Context Strip (`RunContextStrip.tsx`): extend `RecoveryBaseline` with a "Failure classification: {humanReadableName}" `StateSignifierChip` fed from `useFailureClassification` (shared cache; no new fetch). Height stays in the secondary region (strip row is height-capped).

- [x] **Task 8 — FE: tests (AC12)**
  - [x] `FailureClassificationDialog.test.tsx`: cards render name/description/examples; deprecated fixture → reduced-prominence + warning; prior-classification pre-select (+ replacement pre-select when prior deprecated); re-classify warning; submit → MSW classify POST body `{ role, taxonomyValue, reasonText? }` + `detail(id)` invalidation refetches classification; `DEPRECATED_TAXONOMY_VALUE` MSW error → inline error + replacement re-selected; keyboard arrow-nav; `expectNoA11yViolations`.
  - [x] Launch-context tests: queue "Classify" action opens dialog for a not-yet-classified failed run; diagnostics trigger; Decision Bar `onClassifyFailure` opens it (spy or real, per 4.22 availability).
  - [x] `useFailureTaxonomy` / `useFailureClassification` / `useClassifyFailure` hook tests (MSW, `renderHook` + `QueryClientProvider`, `retryDelay: 0`).
  - [x] Add a deprecated-taxonomy MSW fixture + XSS fixture for a classification description (feeds story 4.26 AC8).
  - [x] REAL `npm run build` + eslint + vitest green before claiming FE done ([[frontend-tsc-noemit-misses-test-files]]).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Backend: `INFO` on `getFailureTaxonomyRegistry` entry/exit (value count only); `INFO` on `getFailureClassification` entry/exit with sanitized `workflowRunId` + `classified`(bool) — NEVER `reasonText`/prose. Parameterized logging; `MdcKeys.sanitizeForLog` on `workflowRunId`.
  - [x] FE: field-only structured console — `recovery.classifySubmit` (response `taxonomyValue`), `recovery.classifyError` (stable `code` + transport flag), `recovery.taxonomyLoadError` / `recovery.classificationLoadError` warns. Assert exact object keys in a focused test to prove no PII (`reasonText`, ids) leaks.
  - [x] Levels per the standard below; carry `correlationId`/`workflowRunId` where available; never log `reasonText`, `taxonomyValue` free text beyond the wire value, tokens, or PII.

## Dev Notes

### The exact primitives this story composes (do NOT rebuild)

- **Write endpoint (done, 4.14):** `POST /api/v1/workflows/{workflowRunId}/classify-failure`; generated client `ClassifyFailureRequest { role, taxonomyValue: <6-value union>, reasonText? }` / `ClassifyFailureResponse { taxonomyValue, priorTaxonomyValue?, recoveryActionId, classifiedEventId?, correlationId?, replayed, workflowRunId }` in `src/lib/api/schema.d.ts`.
- **Read model (done, 4.9):** `WorkflowInspectionService.getFailureClassification(runId) → FailureClassificationView(currentTaxonomyValue, currentDisplayLabel, deprecated, deprecatedReplacementValue, classifiedAt, classifiedBy, List<PriorClassification>)` — `WorkflowInspectionService.java:~3155/~4241/~4251`.
- **Taxonomy enum (done, 4.9):** `domain/registry/FailureTaxonomyValue.java:25-30` (six values) + `deprecated()`/`deprecatedReplacementValue()`/`displayLabel()`; `DomainRegistry.failureTaxonomyValues()` (`:105-107`).
- **FE dialog primitives:** `src/components/overlays/ConfirmationDialog.tsx` (controlled, explicit focus-restore), `BoundedDetailSheet.tsx` (`side="bottom"` `fullHeightOnMobile`), `src/components/ui/sheet.tsx`. Radio pattern: `ApprovalDecisionBar.tsx`'s `RejectionDialog` (`<fieldset>/<legend>/<input type=radio>`).
- **FE hooks:** `useWorkflowMutation.ts` (UUIDv7 key + `detail(id)`/`lists()` invalidation), `useRetryWorkflow.ts` (body-spread template), `useFailureDiagnostics.ts` (read-hook template), `src/lib/queryKeys/workflowKeys.ts`.
- **Tokens:** `state-draft` group in `src/styles/globals.css` (`--state-draft*`), `draft` signifier in `src/lib/state-signifiers.ts`. Literal classes only (purge trap).
- **a11y:** `src/lib/a11y/useLiveAnnouncement.ts` (defers one commit), `src/test/a11y/axe.ts` `expectNoA11yViolations`.

### The two backend design decisions (the #1 + #2 traps)

- **Prose source (R3/OQ-1):** the domain enum has no descriptions/examples and must stay minimal (4.9 R2). Curate them in an application-layer `FailureTaxonomyCatalog` keyed by wire value, joined to the enum's `deprecated()`/`replacementValue()` at read time, guarded by a catalog-vs-`DomainRegistry.failureTaxonomyValues()` drift test so a future registry addition can't ship without prose. Reconcile the copy against ADR 0035 (OQ-3).
- **Current-classification read surface (R4/OQ-2):** the existing `FailureClassificationView` is not on REST. Expose it via a **dedicated** `GET .../failure-classification` (mapping the view 1:1), NOT by widening `WorkflowDetailResponse` — the latter is a flat record whose exact-field contract test ([[workflow-summary-exact-field-contract-test]]) reds on any new field, and `WorkflowStatusView` would also need widening. The dedicated endpoint keys under `detail(id)` in the FE so the classify mutation's invalidation refreshes it for free (AC9).

### Role/actor + reasonText posture (the write half's traps, inherited)

- `role: 'workflow_owner'` is a REQUIRED **body** field on `classify-failure` (validated then discarded by the controller). Send it (`RECOVERY_OPERATOR_ROLE`). `reasonText` is genuinely optional (blank → stored `null`, no `MISSING_REASON_TEXT`) — spread-omit when blank. Do NOT copy `useRetryWorkflow`'s `actorIdentity`/`actorType` body fields — classify uses the header-derived actor + `role` body field.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback (backend); FE field-only structured `console.info`/`console.warn` (never string-concat, never PII).
- **Where to log:** `getFailureTaxonomyRegistry` read (`INFO` entry/exit, value count); `getFailureClassification` read (`INFO` entry/exit, sanitized `workflowRunId` + `classified` bool); FE `recovery.classifySubmit`/`recovery.classifyError`/`recovery.taxonomyLoadError`/`recovery.classificationLoadError`.
- **Required context keys:** `correlationId`, `workflowRunId` (sanitized via `MdcKeys.sanitizeForLog`). Never `reasonText` prose, `taxonomyValue` free-text beyond the wire value, idempotency-key, tokens, PII.
- **Test contract:** backend `ListAppender`/`OutputCaptureExtension` pins on the new read logs; FE `console` spy asserting exact keys (no PII).

### Project Structure Notes

- **New (backend main):** `adapters/rest/RegistryController.java`, `adapters/rest/FailureTaxonomyRegistryResponse.java` (+ nested/`TaxonomyValue`), `adapters/rest/FailureClassificationResponse.java` (+ nested `PriorClassification`), `application/recovery/FailureTaxonomyCatalog.java` (+ `FailureTaxonomyMetadataView`). **Modified:** `WorkflowController.java` (`getFailureClassification` read method + import), `src/main/resources/openapi/openapi.json` (regen).
- **New (FE):** `src/features/workflows/components/FailureClassificationDialog.tsx` (+ sibling `.ts` helpers), `src/features/workflows/hooks/useFailureTaxonomy.ts` / `useFailureClassification.ts` / `useClassifyFailure.ts`, `src/lib/queryKeys/registryKeys.ts`. **Modified:** `workflowKeys.ts` (`failureClassification` key under `detail`), `OperatorQueue.tsx`, diagnostics deep-dive component, `RunContextStrip.tsx` (`RecoveryBaseline`), `WorkflowDecisionBar`/`RecoveryDecisionBarContainer` `onClassifyFailure` wiring (when 4.22 lands), `src/lib/api/schema.d.ts` (regen).
- **New (test):** `RegistryControllerTest`, `FailureClassificationEndpointContractTest`, `FailureTaxonomyCatalogTest` (backend); `FailureClassificationDialog.test.tsx`, hook tests (FE).
- `application` cannot import `adapters` ([[application-cannot-import-adapters]]); the DTOs map the catalog/view, not vice-versa. `REST_CONTROLLERS_STAY_THIN` — map into flat DTOs, no reaching through nested application types.
- No Flyway migration. No new `DomainErrorCode`/`WorkflowEventType`/`AllowedAction`/registry value.

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.24 (lines 513–532)].
- Write half: [Source: _bmad-output/implementation-artifacts/4-14-rest-endpoint-classify-failure-and-openapi.md] (classify endpoint + DTOs + four taxonomy codes; the generated client already carries them).
- Producer: [Source: _bmad-output/implementation-artifacts/4-9-recovery-service-classify-failure-and-failure-taxonomy-registry-management.md] (`FailureTaxonomyValue` two-arg enum, `FailureTaxonomyPolicy`, `getFailureClassification`/`FailureClassificationView`, V44, ADR 0035).
- FE Decision-Bar seam + recovery patterns: [Source: _bmad-output/implementation-artifacts/4-22-…md] (`onClassifyFailure` seam, `RECOVERY_OPERATOR_ROLE`, `RecoveryDecisionBarContainer`, field-only logging) — `ready-for-dev`, wire when merged.
- Backend anchors: `domain/registry/FailureTaxonomyValue.java:25-30`; `DomainRegistry.java:33-34,105-107`; `WorkflowInspectionService.java:~3155,~4241,~4251`; `WorkflowController.java:~2561` (classify) + the `getFailureDiagnostics` read template; `adapters/rest/ClassifyFailureRequest.java`/`ClassifyFailureResponse.java`; `docs/adr/0035-failure-taxonomy-governance.md`.
- FE anchors: `src/components/overlays/ConfirmationDialog.tsx` / `BoundedDetailSheet.tsx`; `src/features/workflows/components/ApprovalDecisionBar.tsx` (`RejectionDialog` radio pattern); `src/features/workflows/hooks/useWorkflowMutation.ts` / `useRetryWorkflow.ts` / `useFailureDiagnostics.ts`; `src/lib/queryKeys/workflowKeys.ts`; `src/styles/globals.css` (`--state-draft*`); `src/lib/a11y/useLiveAnnouncement.ts`; `src/features/workflows/components/RunContextStrip.tsx` (`RecoveryBaseline`); `src/features/workflows/OperatorQueue.tsx`.
- Traps: [[workflow-summary-exact-field-contract-test]], [[openapi-regen-frontend-client-drift-cascade]], [[mojibake-emdash-openapi-drift]], [[workflowdetail-wire-sends-null-not-undefined]], [[frontend-tsc-noemit-misses-test-files]], [[frontend-react-refresh-no-fn-exports]], [[livesnnouncement-defers-one-commit-test-flake]], [[tanstack-validatesearch-strips-unparsed-param]], [[vitest-cross-file-router-mock]], [[application-cannot-import-adapters]], [[archunit-runs-in-failsafe-not-surefire]], [[springboot-testcontainers-test-must-be-IT]].

### Open Questions (resolve during dev; defaults chosen)

- **OQ-1 (prose home — enum vs catalog).** Default: a curated application-layer `FailureTaxonomyCatalog` + drift test (keeps the domain enum minimal per 4.9 R2). Alternative: add `humanReadableName`/`description`/`examples` ctor args to `FailureTaxonomyValue` (single source, but pollutes `domain/registry` with presentation prose + i18n concerns). Recommend the catalog.
- **OQ-2 (current-classification read surface — dedicated endpoint vs `WorkflowDetailResponse` field).** Default: dedicated `GET .../failure-classification`. Alternative: add a `failureClassification` object to `WorkflowDetailResponse` (fewer round-trips for the strip, but triggers the exact-field contract fan-out + `WorkflowStatusView` widening). Recommend the dedicated endpoint.
- **OQ-3 (curated copy vs ADR 0035).** Reconcile the R3 humanReadableName/description/examples table against `docs/adr/0035-failure-taxonomy-governance.md` — if the ADR defines canonical descriptions, those win. Confirm with Alex.
- **OQ-4 (4.22 merge order for AC8c).** The Decision-Bar launch context needs 4.22's `onClassifyFailure` seam. Default: build + ship the dialog + hooks + the 4.2/4.4 launch contexts now; attach the 4.22 seam when 4.22 merges (self-contained, testable independently). Confirm sequencing.
- **OQ-5 (queue-row classification surfacing, AC9).** Default: the queue reads classification per-row from the shared cache / a lightweight `useFailureClassification` (no operator-runs-list DTO change). Alternative: add a `failureClassification` field to the operator-runs-list response (one fetch, but a backend DTO fan-out). Recommend the cache read; escalate if per-row fetches are too chatty at queue scale.
- **OQ-6 (one responsive presentation vs two).** Default: a single dialog that renders as a centered `ConfirmationDialog` on desktop and a bottom full-height sheet on mobile via one responsive wrapper — avoid two divergent component trees. Confirm the exact breakpoint switch mirrors 2.23/2.26 precedent.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Backend targeted Surefire: `FailureTaxonomyCatalogTest`, `RegistryControllerTest`, `FailureClassificationEndpointContractTest`, `WorkflowInspectionServiceFailureClassificationTest` → **16 tests, 0 failures**.
- OpenAPI regen: `OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` (Failsafe, full app boot) wrote `openapi.json` (+217 lines, additive), then re-ran WITHOUT the write flag → **byte-identical green**. FE `npm run generate-api` + `npm run check:api` → **in sync**.
- Backend static gates: `verify -Dit.test="architecture/**/*Test"` → ArchUnit **90 tests, 0 failures** (incl. `REST_CONTROLLERS_STAY_THIN` + application→adapters boundary); spotless clean; checkstyle 0; spotbugs 0 errors (pre-existing Medium `MS_EXPOSE_REP` on `DomainRegistry`, unrelated).
- FE: `npm run build` (tsc -b + vite) ✅ · `npm run lint` (0 issues) ✅ · `vitest run` full suite **1482 + new dialog/hook/queue tests, 0 failures** ✅.

### Completion Notes List

- **Backend (2 new read endpoints + 1 curated catalog).** `RegistryController` `GET /api/v1/registries/failure-taxonomy` → `FailureTaxonomyRegistryResponse{values:[TaxonomyValue]}`; `WorkflowController.getFailureClassification` `GET /api/v1/workflows/{id}/failure-classification` → `FailureClassificationResponse`. Curated `FailureTaxonomyCatalog` (application layer, `@Component`) iterates `FailureTaxonomyValue.values()` and joins the R3 prose (reconciled against ADR 0035 — the ADR fixes the six values but defines no descriptions, so the R3 copy is canonical, OQ-3 resolved). `FailureTaxonomyCatalogTest` pins the catalog key set == `DomainRegistry.failureTaxonomyValues()` (drift guard).
- **Run-existence 404 (design decision).** `WorkflowInspectionService.getFailureClassification` did NOT verify run existence (it returned nulls for any id), but AC/Task 3 require unknown run → `RUN_NOT_FOUND` (404) while a KNOWN never-classified run → 200 null + `[]`. Since the method's only caller today is its own unit test, I added the existence probe (via `workflowRunReadPort`, AFTER the port-wired check so the unwired-port INTERNAL_ERROR ordering is preserved) directly in the service and updated the one 4.9 unit test (+ an unknown-run 404 case). Keeps the controller thin per `REST_CONTROLLERS_STAY_THIN`.
- **Null-inclusion (`@JsonInclude(NON_NULL)`).** The project has no global non-null Jackson config, so I annotated both new response DTOs explicitly so a nullable field (`replacementValue`, the never-classified current-* fields) is OMITTED on the wire, matching the FE "omit-the-key, don't `:null`" contract and keeping generated TS fields optional.
- **FE hooks.** `registryKeys.failureTaxonomy()` (global, NOT run-scoped) + `useFailureTaxonomy()` (long `STALE_TIME.registry` added); `workflowKeys.failureClassification(id)` under the `detail(id)` prefix + `useFailureClassification(id,{enabled})`; `useClassifyFailure(id)` on the `useWorkflowMutation` factory — body `{ role: RECOVERY_OPERATOR_ROLE, taxonomyValue, ...(reasonText when non-blank) }`. Field-only logs `recovery.classifySubmit`/`recovery.classifyError`/`recovery.taxonomyLoadError`/`recovery.classificationLoadError` (asserted key-exact; `reasonText` never logged).
- **FE dialog.** `FailureClassificationDialog` composes the controlled `ConfirmationDialog` (no nested sheet, `T-NO-STACK`); native `<fieldset>/<legend>/<input type=radio>` cards (AC10 roving focus); deprecated cards use literal `state-draft*` classes + FE-composed `(deprecated, use X instead)` affix + on-select warning (AC4); prior-classification pre-select (or replacement when deprecated, AC5) + provenance + re-classify warning; `DEPRECATED_TAXONOMY_VALUE` re-selects the replacement inline (AC7); one responsive presentation (full-height edge-to-edge on mobile via `max-sm:` utilities, AC11); helpers in the sibling `.ts` (react-refresh). Curated `description`/`examples` render as React-escaped plain text (XSS-escaping test added, feeding 4.26 AC8).
- **Launch contexts wired (AC8).** Decision Bar `onClassifyFailure` seam (4.22, now `done`) → opens the dialog (the bar still gates the button on the live `classify_failure` allowed-action; updated the 4.22 `full` test's disabled→enabled assertion). Diagnostics deep-dive (`FailureEventSurface`) — a "Classify failure" trigger + current-classification provenance line. Operator queue — a per-Failed-row "Classify" action (threaded `onClassify` through `RunReviewQueueItem`→`OperatorRow`→`OperatorRowBody`, `z-[2]` above the stretched link) opening a single lifted dialog. Run Context Strip `RecoveryBaseline` — a "Failure classification: {name}" `StateSignifierChip` fed from the shared `useFailureClassification` cache (+ `useFailureTaxonomy` for the human name), refreshed for free by the classify mutation's `detail(id)` invalidation (AC9).
- **OQ resolutions.** OQ-1 catalog (not enum); OQ-2 dedicated read endpoint; OQ-3 R3 copy canonical (ADR has no descriptions); OQ-4 4.22 is `done`, seam wired directly; OQ-5 shared-cache per-row read (no queue DTO fan-out); OQ-6 one responsive dialog.

### File List

**Backend — new:**
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/FailureTaxonomyCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/FailureTaxonomyMetadataView.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/RegistryController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/FailureTaxonomyRegistryResponse.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/FailureClassificationResponse.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/recovery/FailureTaxonomyCatalogTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/RegistryControllerTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/FailureClassificationEndpointContractTest.java`

**Backend — modified:**
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` (new `getFailureClassification` read method)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` (`getFailureClassification` run-existence 404 probe)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceFailureClassificationTest.java` (stub run existence + unknown-run 404 case)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated — 2 new paths + 4 new schemas)

**Frontend — new (incl. code-review additions):**
- `deliveryline-frontend/src/features/workflows/components/OperatorClassifiedRow.tsx` (review D2b/D3 — per-row classification container for the operator queue)
- `deliveryline-frontend/src/features/workflows/components/__tests__/OperatorClassifiedRow.test.tsx`
- `deliveryline-frontend/src/lib/a11y/liveAnnouncer.ts` (review P3 — document-level `announce()` that outlives the dialog unmount)
- `deliveryline-frontend/src/lib/queryKeys/registryKeys.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useFailureTaxonomy.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useFailureClassification.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useClassifyFailure.ts`
- `deliveryline-frontend/src/features/workflows/components/FailureClassificationDialog.tsx`
- `deliveryline-frontend/src/features/workflows/components/failureClassificationDialogView.ts`
- `deliveryline-frontend/src/features/workflows/hooks/useFailureTaxonomy.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useFailureClassification.test.tsx`
- `deliveryline-frontend/src/features/workflows/hooks/useClassifyFailure.test.tsx`
- `deliveryline-frontend/src/features/workflows/components/FailureClassificationDialog.test.tsx`

**Frontend — modified:**
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- `deliveryline-frontend/src/lib/api/queryOptions.ts` (`STALE_TIME.registry`)
- `deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts` (`failureClassification` key)
- `deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.tsx` (classify seam wired)
- `deliveryline-frontend/src/features/workflows/components/FailureEventSurface.tsx` (classify trigger + provenance)
- `deliveryline-frontend/src/features/workflows/components/RunReviewQueueItem.tsx` (`onClassify` operator action)
- `deliveryline-frontend/src/features/workflows/OperatorQueue.tsx` (lifted classify dialog)
- `deliveryline-frontend/src/features/workflows/components/RunContextStrip.tsx` (classification chip in `RecoveryBaseline`)
- `deliveryline-frontend/src/features/workflows/components/RecoveryDecisionBarContainer.full.test.tsx` (classify button disabled→enabled)
- `deliveryline-frontend/src/features/workflows/components/__tests__/RunReviewQueueItem.test.tsx` (operator classify-action cases)

### Change Log

| Date | Change |
|---|---|
| 2026-07-16 | Implemented story 4.24 full-stack: `RegistryController` + `GET /registries/failure-taxonomy`, `GET /workflows/{id}/failure-classification`, curated `FailureTaxonomyCatalog` + drift test, OpenAPI regen; FE `useFailureTaxonomy`/`useFailureClassification`/`useClassifyFailure` hooks + `FailureClassificationDialog`; wired all 3 launch contexts (Decision Bar / diagnostics / operator queue) + Run Context Strip classification badge; tests + full backend/FE gate verification. Status → review. |

### Review Findings

_Adversarial code review 2026-07-16 (Blind Hunter + Edge-Case Hunter + Acceptance Auditor). 4 decision-needed, 5 patch, 2 deferred, 3 dismissed as noise. All 4 decisions resolved to "patch"; **ALL 9 patches now landed** across two passes (batch-apply landed 7, then D2b/D3/P3 + the P4 test remainder landed after Alex chose the per-row FE approach for the queue). Final gates GREEN: `tsc -b` 0, eslint 0, prettier 0, check:a11y 4/4, `check:api` in-sync, **998 FE workflows tests** (+8), custom eslint rules 0, changed backend classes pass. Only the 2 accepted defers remain (in `deferred-work.md`)._

**Applied in this review pass (batch-apply):**

- [x] [Review][D1] Humanize the classification label everywhere — `FailureEventSurface` + the dialog now resolve via `humanNameForTaxonomy(...)` (deprecated surfaced separately), and the dead `?? currentDisplayLabel` fallback was removed from `RunContextStrip`. Tests added for the humanized surface label. [`FailureEventSurface.tsx`, `FailureClassificationDialog.tsx`, `RunContextStrip.tsx`]
- [x] [Review][D2a] Gate the diagnostics-surface "Classify failure" trigger on the live `classify_failure` allowed-action (parity with the Decision Bar) — `FailureEventSurface` now hides the trigger for ineligible runs. Present/absent tests added. [`FailureEventSurface.tsx`]
- [x] [Review][D4] Header now shows the failure **reason text** (AC2), sourced from `useFailureDiagnostics` (WorkflowDetailResponse deliberately not widened). Test added. [`FailureClassificationDialog.tsx`]
- [x] [Review][P1] `onError` maps `CLASSIFY_NOT_APPLICABLE`/`RUN_NOT_FOUND`/`INVALID|MISSING_TAXONOMY_VALUE` to distinct, non-"try again" messages. Test added. [`FailureClassificationDialog.tsx`]
- [x] [Review][P2] `classifiedAt` now renders via `formatUtcTimestamp`. [`FailureClassificationDialog.tsx`]
- [x] [Review][P5] Test hardening — `RegistryControllerTest` uses `not(blankOrNullString())`; `FailureTaxonomyCatalog` exposes `curatedWireValues()` + a new `curatedProseHasNoExtraKeysBeyondRegistry` drift test catches EXTRA prose keys. [`RegistryControllerTest.java`, `FailureTaxonomyCatalog.java`, `FailureTaxonomyCatalogTest.java`]
- [x] [Review][P4-partial] Added the diagnostics-launch gate/label tests (D1/D2a) and the D4/P1 dialog tests. Still open: arrow-key roving-focus test (Task 8) and the cross-surface post-classification visibility test (blocked on D3).

**Resolved after the batch (second pass — Alex chose the per-row FE approach for the queue):**

- [x] [Review][D2b+D3] Operator queue gate + AC9 chip — NEW `OperatorClassifiedRow` container wraps each row, reads `useFailureClassification`/`useFailureTaxonomy` gated to Failed rows (bounded by virtualization; cached under `detail(id)`), and passes derived props to the still-pure `RunReviewQueueItem`: `onClassify` ONLY when `currentTaxonomyValue == null` (AC8a), and a new `classificationLabel` attention-slot chip (`operator-classification-chip`, registry-humanized) once classified (AC9). Loading/errored read → treated as not-yet-classified so Classify is never hidden by a slow read. Container + chip + queue tests added. [`OperatorClassifiedRow.tsx`, `RunReviewQueueItem.tsx`, `OperatorQueue.tsx`]
- [x] [Review][P3] AC10 success announcement — NEW `lib/a11y/liveAnnouncer.ts` document-level polite region (`announce()`) that outlives the dialog's unmount; dialog `onSuccess` announces `failureClassificationApplied` (added to the `announcements.ts` vocabulary). Test asserts the global announcer speaks after apply. [`FailureClassificationDialog.tsx`, `liveAnnouncer.ts`, `announcements.ts`]
- [x] [Review][P4] Test remainder — arrow-key roving-focus test (Task 8) + cross-surface post-classification chip test (`RunContextStrip`) added; combined with the earlier diagnostics-launch + queue tests, AC12's coverage list is met.

**Original findings (for reference):**

- [x] [Review][Decision] Classification label rendered inconsistently across the three surfaces — `FailureTaxonomyValue.displayLabel()` returns the raw snake_case wire value (only appends `" (deprecated)"`), so `currentDisplayLabel` is snake_case. `FailureEventSurface` (`FailureEventSurface.tsx:646,672`) and the dialog "Previously classified as" line (`FailureClassificationDialog.tsx:173-174`) render `currentDisplayLabel` verbatim → `"agent_execution_failure"`, while `RunContextStrip` (`RunContextStrip.tsx:457-461`) humanizes via `humanNameForTaxonomy(...)` → `"Agent Execution Failure"`. Same run, same session, two surfaces show snake_case and one shows the curated name. Decision: pick a canonical label strategy (humanize everywhere via the registry + surface `deprecated` separately, vs show raw `displayLabel` everywhere). Note the tension: humanizing drops the backend's `" (deprecated)"` suffix. Folds in the dead `?? currentDisplayLabel` fallback at `RunContextStrip.tsx:461` (unreachable — `humanNameForTaxonomy` only returns undefined when the value is empty, in which case `currentDisplayLabel` is also absent).
- [x] [Review][Decision] (D2a + D2b both applied — diagnostics surface + queue row gated) Classify-action eligibility gate deviates from AC8a/Task 7 on two surfaces — the Decision Bar path IS correctly gated on the live `classify_failure` allowed-action (`ApprovalDecisionBar` filters on `view.actions`). But (a) `FailureEventSurface` renders the "Classify failure" trigger whenever any failure-history events exist, with no `disabled`/allowed-action/state gate (`FailureEventSurface.tsx:672`) — a failed-then-recovered run still shows an enabled Classify button; and (b) the queue row gates only on `row.currentState === 'Failed'` (`RunReviewQueueItem.tsx:512-515`), not on the `classify_failure` allowed-action nor `currentTaxonomyValue == null` that AC8a + Task 7 mandate (`OperatorQueue.tsx` documents the deliberate relaxation). Result: ineligible runs offer Classify → operator submits → server `CLASSIFY_NOT_APPLICABLE` (409). Decision: accept the relaxation (rely on the 409 backstop) or enforce the AC-mandated gate.
- [x] [Review][Decision] (applied — OperatorClassifiedRow chip, AC9) AC9 — operator-queue rows do NOT display the applied classification — AC9 requires the classification to show "across the UI: Run Context Strip …, operator queue rows display it in their attention-indicator slot, failure diagnostics …". The strip badge and diagnostics "Classified as X" are implemented; `RunReviewQueueItem.tsx` gained only the Classify **launch** button, no classification-label read-back (OQ-5's recommended per-row `useFailureClassification`). Decision: build the queue-row chip now or defer.
- [x] [Review][Decision] (applied — reason text added via diagnostics) AC2 — dialog header shows "Failed stage" instead of the failure **reason text** — AC2 asks for "failure category + reason text"; the header (`FailureClassificationDialog.tsx`, `failure-classification-context`) renders `Failure category` + `Failed stage` (from `useWorkflowDetail`), omitting reason text. Decision: accept the substitution or add the reason text.

**Patch** (unambiguous fixes):

- [x] [Review][Patch] (applied) Dialog error handler collapses non-retryable server codes to "Please try again" — `onError` special-cases only `DEPRECATED_TAXONOMY_VALUE`; `CLASSIFY_NOT_APPLICABLE` (409), `RUN_NOT_FOUND` (404), `INVALID/MISSING_TAXONOMY_VALUE` (400) all fall through to `'Could not apply the classification. Please try again.'`, which is misleading for terminal failures. [`FailureClassificationDialog.tsx:1684-1697`]
- [x] [Review][Patch] (applied) `classifiedAt` rendered as a raw ISO string in the dialog instead of `formatUtcTimestamp`/`formatRelativeTime` used elsewhere. [`FailureClassificationDialog.tsx:1747`]
- [x] [Review][Patch] (applied — document-level `announce()`) AC10 — no success live-region announcement; `announcement` covers only `isPending`/`inlineError`, and `handleOpenChange(false)` unmounts the dialog on success, so completion is never announced to screen readers. [`FailureClassificationDialog.tsx:1663-1683`]
- [x] [Review][Patch] (partial — diagnostics-launch + D4/P1 tests added; arrow-key nav + cross-surface still open) AC12 test coverage gaps — no test for the diagnostics launch context (`FailureEventSurface` trigger); the Decision-Bar test only asserts the button is `toBeEnabled()`, not that it opens the dialog and not the disabled/ineligible case; no post-classification cross-surface visibility test; no arrow-key roving-focus test (Task 8). [`RecoveryDecisionBarContainer.full.test.tsx`, `FailureEventSurface`, `RunContextStrip`]
- [x] [Review][Patch] (applied) Test-quality hardening — `RegistryControllerTest` asserts `everyItem(not(""))` which admits whitespace-only names/descriptions (tighten to non-blank); and the catalog drift test guards the "missing prose" direction but not "extra" — a `CURATED` key that is not a `FailureTaxonomyValue` is never iterated so it goes undetected (add `CURATED.keySet()` == enum-set assertion). [`RegistryControllerTest.java:1084`, `FailureTaxonomyCatalogTest.java`]

**Deferred:**

- [x] [Review][Defer] AC11 mobile full-height uses a raw `max-sm:*` CSS override on `ConfirmationDialog` rather than the prescribed `BoundedDetailSheet`/`sheet.tsx` `side="bottom"` primitive (story 2.23 AC6 pattern) — deferred, user-facing outcome is met and T-NO-STACK avoided; revisit if mobile QA finds the override doesn't win over shadcn's centered-transform classes. [`FailureClassificationDialog.tsx`]
- [x] [Review][Defer] `FailureEventSurface` calls `useFailureClassification(runId)` with no `enabled` gate, firing a GET even for runs that render nothing (unlike `RunContextStrip` which gates on `enabled: showRecovery`) — deferred, negligible extra request. [`FailureEventSurface.tsx:638`]
