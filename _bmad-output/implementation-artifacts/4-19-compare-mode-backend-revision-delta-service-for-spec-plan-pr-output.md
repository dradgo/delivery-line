# Story 4.19: Compare Mode Backend — Revision Delta Service for Spec / Plan / PR-Output

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer providing the data layer for the Compare Mode UI (story 4.20),
I want a `RevisionDeltaService` that computes typed deltas between two artifact versions of the same artifact lineage (per story 1.12 lineage with the `parent_artifact_id` chain) — for spec (markdown section diff), implementation-plan (structured-step diff), and PR-output (file-level diff summary) — with sanitization-aware output,
so that UX-DR13 (Compare Mode / Revision Delta Summary) has a typed, stable backend contract the frontend consumes via REST.

## Context & Central Reconciliation (READ FIRST)

**This is a pure READ-ONLY, backend-only story.** You add a new `application.compare` package with one `@Service` (`RevisionDeltaService`) plus three dedicated, unit-testable diff-algorithm classes; you EXTEND the existing `ArtifactService` with a compare-read method; you add ONE new top-level REST controller (`GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}`) and its response DTO; you regenerate the OpenAPI snapshot + the frontend client; and you add exactly ONE new `DomainErrorCode`. **You do NOT touch any write path, any transition, the `RecoveryService`, the queue, the runner, the artifact write orchestrator (`ArtifactOperationService`), or any Flyway migration.** The Compare Mode UI is story 4.20; the mobile bounded state is 4.21; the `enter_compare_mode` allowed-action lands in 4.20 (NOT here).

The single most important thing to internalize: **the epic's AC text for 4.19 drifts from the live artifact code in several places** (there is no god `ArtifactService`; two of the three named error codes already exist with different mappings; `diffReference`/`steps`/`producedByActor` are NOT artifact columns; implementation-plan steps have no `stepId`). The **binding clarifications below win** over the epic phrasing — this is the same house discipline story 4.1 established for Epic 4.

### HEADLINE RECONCILIATIONS (epic AC text drifts from live code — these bindings win)

1. **THE CENTRAL BINDING — `RevisionDeltaService`'s only injected collaborators are `ArtifactService` + `RedactionPolicyService`, but `ArtifactService` MUST be EXTENDED first.** Epic AC9 says "its only collaborators are `ArtifactService` (story 1.12) + `RedactionPolicyService` (story 1.10)". But the live `ArtifactService` exposes a SINGLE public method — `boolean isApprovalEligible(String artifactId)` — it has NO load/get-payload API. [Source: `application/artifact/ArtifactService.java:26`] So to honor AC9 literally, **extend `ArtifactService`** with a typed compare-read (load the `ArtifactRecordSnapshot` by public id + read the redaction-ready payload bytes, gated on `AVAILABLE`), reusing the two collaborators it ALREADY injects — `ArtifactRecordPort.findByPublicId(...)` and `ArtifactPayloadStore.readBytes(...)`. **Do NOT inject `ArtifactRecordPort` / `ArtifactPayloadStore` directly into `RevisionDeltaService`** — that would break the AC9 collaborator pin, force a needless `application.compare.spi` package, and duplicate the availability gate `ArtifactService` already owns. [Source: `application/artifact/ArtifactService.java:33,48`; `application/artifact/spi/ArtifactRecordPort.java:13`; `application/artifact/spi/ArtifactPayloadStore.java:21`]

2. **Lineage verification MUST walk the `parent_artifact_id` chain — same `(workflow_run_id, artifact_type)` is NOT sufficient.** A single `(workflow_run_id, artifact_type)` pair can host **multiple disjoint lineages** — a fresh draft is started after a `FAILED` leaf (`lineage_recovery=true`, added by `V5__artifact_lineage_recovery.sql`), so two artifacts can share run+type yet belong to different chains. Verify: (a) both snapshots share `workflowRunId` + `artifactType`, AND (b) one is reachable from the other by walking `parentArtifactId` upward. Reuse the exact cycle-guarded parent-walk idiom from `ContextBundleService.collectPriorFeedbackReferences` (visited-set break). If the two are not on one connected chain → raise `ARTIFACT_LINEAGE_MISMATCH` (Reconciliation 3). [Source: `application/runner/ContextBundleService.java:1400-1421`; `application/artifact/ArtifactRecordSnapshot.java:13` (`parentArtifactId`); `db/migration/V5__artifact_lineage_recovery.sql`]

3. **Error codes: only ONE is genuinely new.** The epic AC8 lists three; live code says otherwise:
   | Epic AC8 code | Status | Binding |
   |---|---|---|
   | `ARTIFACT_LINEAGE_MISMATCH` (400) | **NEW** | Add via the **three-site** path (Reconciliation 12). |
   | `ARTIFACT_NOT_FOUND` (404) | **Does NOT exist** | **REUSE `ARTIFACT_RECORD_NOT_FOUND`** (404). [`DomainErrorCode.java:31`; `ProblemDetailsCatalog.java:155-160`] Do NOT add a new not-found code. |
   | `ARTIFACT_PAYLOAD_UNAVAILABLE` (409) | **EXISTS but maps to 503 retryable, NOT 409** | **REUSE as-is at 503.** [`DomainErrorCode.java:29`; `ProblemDetailsCatalog.java:143-148`] It is live wire contract with raise sites in `TechnicalApprovalService`/`ApprovalService`, the `getArtifact` 503 `@ApiResponse`, and `WorkflowCliExitStatusExceptionMapper`. Do NOT change its status. The epic's "409" is superseded by the live mapping. |
   Also **reuse `INVALID_ID_PREFIX` (400)** for a malformed `art_…` id — the `getArtifact` precedent. **NET new `DomainErrorCode`: exactly 1 (`ARTIFACT_LINEAGE_MISMATCH`).**

4. **This is the FIRST top-level `/api/v1/artifacts` REST resource.** Today artifacts are served ONLY run-scoped: `GET /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}` on `WorkflowController.getArtifact`. [Source: `adapters/rest/WorkflowController.java:719-774`] The epic path `GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}` is a NEW top-level controller. This is sound because `art_…` public ids are globally unique and loadable without a run (`ArtifactRecordPort.findByPublicId`). Create `ArtifactCompareController` `@RequestMapping("/api/v1/artifacts")`, `@GetMapping("/{artifactIdA}/compare/{artifactIdB}")`, `operationId="compareArtifacts"`. Mirror `ProjectController` for the resource-root shape and `WorkflowController.getArtifact` for the springdoc `@ApiResponses` + `@Content(mediaType=APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(implementation=ProblemDetailsResponse.class))` pattern. **Reads are idempotent → NO `Idempotency-Key` header, NO `X-Actor-Identity` requirement.** [Source: `adapters/rest/ProjectController.java:62-64`; `WorkflowController.java:719-758`]

5. **`diff` / `steps` / `diffReference` are NOT artifact columns or snapshot fields — they live inside the payload JSON file.** Neither appears on `ArtifactRecordSnapshot` or the `artifacts` table. They are parsed at read time from the payload bytes addressed by `storageRef`: spec payload → markdown; `implementationPlan` payload → `{"steps":[string,…]}`; `prOutput` payload → `{branch, commitSha, prReference, diffReference, diff}` where the RESOLVED unified-diff text is the `diff` field. **`diffReference` is an ephemeral runner-scratch POINTER, frequently ABSENT** — do NOT treat it as durable state (Reconciliation 8). Reuse the EXACT payload keys; mirror the parsing in `WorkflowInspectionService.getArtifactDetail` / `parseSteps` but **re-implement it inside the compare differ classes** (AC9 forbids depending on `WorkflowInspectionService`). [Source: `application/workflow/WorkflowInspectionService.java:1860-1935` (payload→body/diff/steps), `:1968-1984` (`parseSteps`); `application/runner/RunnerBroker.java:3264-3294` (prOutput payload assembly)]

6. **`implementationPlan` steps are plain strings — there is NO `stepId`.** Epic AC4's `PlanStepChangeBlock{stepId,…}` assumes structured step ids, but the persisted payload is `steps: [string, …]` and `parseSteps` returns `List<String>`. **Bind `stepId` = the 0-based array index (`stepOrder`)**; detect added/removed/reordered/modified via a sequence alignment (LCS) over the string list. Document that "stepId" here is positional, not a persisted identifier. [Source: `WorkflowInspectionService.java:1968-1984`]

7. **`producedByActor` is on the artifact's linked creation EVENT, not the artifact row.** `ArtifactRecordSnapshot` carries `version`, `createdAt`, `checksumValue` (all present) but has NO actor field. The producing actor lives on `linked_event_id → workflow_events.actor_identity / actor_type`. [Source: `adapters/persistence/entity/ArtifactEntity.java:68-70`; `db/migration/V1__create_workflow_core_tables.sql:46-47`] To populate `ArtifactSummary.producedByActor` (epic AC2), add a small read that joins through `linked_event_id` (precedent: `ArtifactRepository.findRunnerExecutionIdForArtifact` reads `workflow_events.details`, `:30-40`), exposed **behind `ArtifactService`** so the collaborator surface stays `ArtifactService` + `RedactionPolicyService`. If Alex prefers, `producedByActor` may ship nullable/deferred — see OQ-3.

8. **prOutput diff is often absent AND the lineage carries an internal `v2` enrich bump.** (a) The resolved `diff` is frequently empty — runners emit only the `diffReference` pointer, and `RunnerBroker.resolvePrOutputDiff` gracefully omits it. (b) The backend git push adds a `v2` "enriched" artifact version with the authoritative diff via a follow-on UPDATE op (`enrichPrOutputArtifact`). So a prOutput lineage can be `v1(no diff) → v2(enriched diff)` — an INTERNAL bump, not a reviewer revision. Handle a missing/absent `diff` gracefully: return the file-level summary as best-effort (null counts where unknown), set `noMeaningfulDiff` appropriately, and ALWAYS populate `linkedDiffReferences` (the two artifact ids) so the UI (4.20) lazy-loads full diff via the existing `getArtifact`. Flag the "compare across enrich bump vs across reviewer revisions" ambiguity as OQ-2. [Source: `RunnerBroker.java:3316-3351` (resolve/omit), `:3096-3185` (enrich → new version)]

9. **`noMeaningfulDiff` has a cheap fast-path via the stored checksum.** Both AVAILABLE artifacts carry a finalized `checksumValue`. If the two are equal → byte-identical → return `noMeaningfulDiff=true` + empty `changes` WITHOUT reading payloads. Otherwise compute; whitespace-only-difference detection then lives inside each per-type differ (normalize non-semantic whitespace before comparing). [Source: `ArtifactRecordSnapshot.java:18` (`checksumValue`)]

10. **No diff library exists on the classpath — keep the algorithms in dedicated pure classes.** No `java-diff-utils` / `diff-match-patch` / `difflib` anywhere (only `com.diffplug.spotless`, the formatter). Two acceptable routes, pick per Task 3: (a) add `io.github.java-diff-utils:java-diff-utils` with an EXPLICIT pinned `<version>` to `deliveryline-backend/pom.xml` (mirror the docker-java `3.7.1` / archunit `1.4.2` explicit-pin convention) and wrap its Myers/LCS primitive behind the differ interfaces; or (b) implement a small LCS in-house. **Recommended: (a)** for the line/sequence primitive, but the markdown-section split, plan-step alignment, and unified-diff header parsing are OURS regardless and live in the dedicated differ classes (AC9: "diff algorithms live in dedicated implementation classes with clear interfaces so they can be unit-tested independently").

11. **Redaction on serve = `redact(...)`, NOT `redactForExport(...)`.** Defense-in-depth (AC6): run each `ChangeBlock` text field (`priorText` / `currentText` / section text) through `redactionPolicyService.redact(text, DataClassification.SHAREABLE_REDACTED.value()).sanitizedText()` before serialization. Do NOT use `redactForExport(...)` — it throws `EXPORT_CLASSIFICATION_VIOLATION` on `LOCAL_ONLY`, and this is an in-app read, not an export. All three artifact types default `SHAREABLE_REDACTED`. Still, if EITHER artifact is classified `LOCAL_ONLY`, refuse to serve (mirror `getArtifactDetail`'s `LOCAL_ONLY` guard) — surface via the reused `ARTIFACT_PAYLOAD_UNAVAILABLE` (503) or a documented opacity string. [Source: `application/security/RedactionPolicyService.java:20`; `RedactionResult.java`; `WorkflowInspectionService.java:1810-1818`; `ArtifactType.java:6-8`]

12. **Adding `ARTIFACT_LINEAGE_MISMATCH` is a THREE-SITE change + auto-verified by two gates.** Site 1: add `ARTIFACT_LINEAGE_MISMATCH("ARTIFACT_LINEAGE_MISMATCH")` to `DomainErrorCode`. Site 2: `register(metadata, ARTIFACT_LINEAGE_MISMATCH, HttpStatus.BAD_REQUEST, "…", /*retryable*/ false)` in `ProblemDetailsCatalog.createMetadata()`. Site 3: add the code → derived type-URI entry to `registry-api-schema-placeholders.json` `problemTypeUris`. The URI must equal the auto-derived slug (`…/problems/artifact-lineage-mismatch`). `RegistryContractTest.domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest` + `ProblemDetailsCoverageFoundationContract` round-trip every code and fail if any site is missing. [Source: `domain/registry/DomainErrorCode.java:59` (inline "three-sites" precedent); `adapters/rest/ProblemDetailsCatalog.java:32-534,530-532,548-550`; `test/resources/contracts/openapi/registry-api-schema-placeholders.json:113-187`; `contract/RegistryContractTest.java:404-446`; `foundation/ProblemDetailsCoverageFoundationContract.java:52,96`] Mind the [[new-domainerrorcode-three-sites]] fan-out.

## Scope Boundary — what 4.19 BUILDS vs REUSES vs DEFERS

| Concern | 4.19 | Note |
|---|---|---|
| `application.compare.RevisionDeltaService` (`@Service`) — orchestrates load → lineage-check → redact → dispatch to per-type differ | **BUILD** | epic AC1 — Reconciliation 1 |
| `RevisionDelta` + `DeltaSummary` + `ArtifactSummary` + the variant `ChangeBlock` records (`MarkdownChangeBlock` / `PlanStepChangeBlock` / `FileChangeBlock`) — application view records nested in / beside `RevisionDeltaService` | **BUILD** | epic AC2–AC5 |
| 3 dedicated diff-algorithm classes with interfaces: `MarkdownSectionDiffer`, `PlanStepDiffer`, `FileLevelDiffer` (pure, no Spring deps) | **BUILD** | epic AC3/AC4/AC5/AC9 — Reconciliation 6/8/10 |
| EXTEND `ArtifactService` with a compare-read (`load snapshot by public id` + `read redaction-ready payload bytes`, gated AVAILABLE) + producing-actor lookup | **BUILD** | Reconciliation 1/7 |
| Lineage-connectivity check via `parent_artifact_id` walk | **BUILD** in the service | Reconciliation 2 |
| `ArtifactCompareController` `@RequestMapping("/api/v1/artifacts")` `GET /{artifactIdA}/compare/{artifactIdB}` (`operationId=compareArtifacts`) + `RevisionDeltaResponse` DTO (`.from(...)`) | **BUILD** | epic AC7 — Reconciliation 4 |
| `ARTIFACT_LINEAGE_MISMATCH` (400) three-site add | **BUILD** | epic AC8 — Reconciliation 3/12 |
| OpenAPI snapshot regen (`-Dopenapi.snapshot.write=true`) + extend `OpenApiSnapshotContractTest` `.contains("compareArtifacts")` + FE `npm run generate-api` (commit `schema.d.ts`) | **BUILD** | epic AC7 — [[openapi-regen-frontend-client-drift-cascade]] |
| ArchUnit: `RevisionDeltaService` collaborator-constraint rule + `@ArchTest` registration | **BUILD** | epic AC9 |
| Unit + contract + perf tests | **BUILD** | epic AC10 |
| `ArtifactRecordPort` / `ArtifactPayloadStore` / `RedactionPolicyService` / `ProblemDetails` mapper / payload keys | **REUSE UNCHANGED** | via `ArtifactService` — Reconciliation 1/5/11 |
| `ARTIFACT_RECORD_NOT_FOUND` (404) / `ARTIFACT_PAYLOAD_UNAVAILABLE` (503) / `INVALID_ID_PREFIX` (400) | **REUSE** | Reconciliation 3 — no new not-found/unavailable codes |
| Full diff-content fetch for PR-output | **DEFER (reuse existing `getArtifact`)** | `linkedDiffReferences` → UI lazy-loads via `WorkflowController.getArtifact` (4.20) |
| Compare Mode UI / mobile / `enter_compare_mode` allowed-action | **DEFER** | stories 4.20 / 4.21 |
| Flyway migration / `WorkflowEventType` / `AllowedAction` / `WorkflowState` / write path / `RecoveryService` | **NONE / REUSE UNCHANGED** | read-only |

## Acceptance Criteria

> From `epic-04-recovery.md` §"Story 4.19" (lines 413–430), with **binding clarifications** in **bold parentheticals**.

1. **Given** the `application.compare` package, **Then** `RevisionDeltaService.computeDelta(artifactIdA, artifactIdB) → RevisionDelta` is added; both artifacts must belong to the same lineage (verified by walking the `parent_artifact_id` chain) — mismatch raises `ARTIFACT_LINEAGE_MISMATCH`. **(New `@Service` in `org.dradgo.application.compare`. Its ONLY injected collaborators are the (extended) `ArtifactService` + `RedactionPolicyService` — Reconciliation 1. Lineage check = same `workflowRunId` + `artifactType` AND parent-chain connectivity, cycle-guarded — Reconciliation 2. `ARTIFACT_LINEAGE_MISMATCH` is the ONE new code, mapped 400 — Reconciliation 3/12. A/B direction: A = baseline/prior, B = target/current; `changeKind` is computed B-relative-to-A; honor the passed ids, do not auto-swap — Reconciliation 12-adjacent. Both artifacts must be `AVAILABLE` — Reconciliation 9/11.)**

2. **Given** the typed `RevisionDelta` view, **Then** it returns: `artifactType` (one of `spec` / `implementationPlan` / `prOutput`), `revisionA: ArtifactSummary`, `revisionB: ArtifactSummary` (each with `version`, `createdAt`, `producedByActor`, `checksum`), `summary: DeltaSummary { changedRegionCount, addedCount, removedCount, modifiedCount }`, `changes: List<ChangeBlock>` (variant-specific per AC3–AC5), `noMeaningfulDiff: boolean` (true when both artifacts are byte-equal or differ only in non-semantic whitespace). **(`artifactType` via `ArtifactType.value()` wire strings `spec|implementationPlan|prOutput` exactly — Reconciliation 5. `ArtifactSummary`: `version`/`createdAt`/`checksum` come straight off `ArtifactRecordSnapshot`; `checksum` short-form mirrors `WorkflowInspectionService.shortChecksum` (`<algo>:<first12hex>`) — do not leak the full digest; `producedByActor` needs the `linked_event_id` join — Reconciliation 7. `noMeaningfulDiff` fast-path via equal `checksumValue` — Reconciliation 9. Nullability = plain nullable reference fields documented in Javadoc, NOT `Optional`/`@Nullable`, per the repo view-record convention.)**

3. **Given** spec variant, **Then** `ChangeBlock` is `MarkdownChangeBlock { sectionPath, changeKind: 'added'|'removed'|'modified', priorText, currentText }`; the diff operates on markdown sections (split by heading levels) so changes are section-by-section, not line-by-line. **(`MarkdownSectionDiffer`: split the redacted markdown by ATX headings into `(sectionPath, body)` regions where `sectionPath` is the heading trail (e.g. `"Edge Cases"` or `"Design > Edge Cases"`); align sections by `sectionPath`; a section present only in B = `added`, only in A = `removed`, present in both with differing normalized body = `modified`. `changeKind` is a small enum/`String` constant set. Whitespace-only body differences do not count as `modified`.)**

4. **Given** implementation-plan variant, **Then** `ChangeBlock` is `PlanStepChangeBlock { stepId, changeKind, priorStepText?, currentStepText?, priorStepOrder?, currentStepOrder? }`; diff operates on the structured-steps array — supports added/removed/reordered/modified steps. **(RECONCILED — there is NO `stepId`; steps are plain strings. Bind `stepId`/order to the 0-based array index; `PlanStepDiffer` runs a sequence alignment (LCS) over the `steps: [string,…]` list: unmatched in B = `added`, unmatched in A = `removed`, same text at a different index = `reordered`, aligned pair with differing text = `modified`. Populate `priorStepOrder`/`currentStepOrder` from the indices — Reconciliation 6.)**

5. **Given** PR-output variant, **Then** `RevisionDeltaService` reads the two artifacts' resolved diff payloads and computes a `FileChangeBlock { filePath, changeKind, addedLines, removedLines }` summary, plus a `linkedDiffReferences` field so the UI can lazy-load the actual diff content (story 4.20). **(RECONCILED — read the payload `diff` field (resolved unified diff), NOT the ephemeral `diffReference` pointer — Reconciliation 5/8. `FileLevelDiffer` parses unified-diff headers (`diff --git a/… b/…`, `+++/---`, `@@` hunks) into per-file `(filePath, changeKind, addedLines, removedLines)`. `diff` is FREQUENTLY ABSENT — when either side's `diff` is missing/empty, return an empty/partial file summary with null counts and set `noMeaningfulDiff` accordingly, but ALWAYS populate `linkedDiffReferences = [artifactIdA, artifactIdB]` so 4.20 fetches full diff via the existing `GET /api/v1/workflows/{runId}/artifacts/{artifactId}` — Reconciliation 8. Beware the internal `v1→v2` enrich bump — OQ-2.)**

6. **Given** sanitization per story 2.24 / story 1.10, **Then** all text content in the returned `ChangeBlock`s passes through `RedactionPolicyService` before serialization — defense-in-depth even though capture already redacted. **(Use `redact(text, DataClassification.SHAREABLE_REDACTED.value()).sanitizedText()` on every text field of every `ChangeBlock` before it enters the `RevisionDelta` — NOT `redactForExport` — Reconciliation 11. If either artifact is classified `LOCAL_ONLY`, refuse to serve, mirroring `getArtifactDetail`'s `LOCAL_ONLY` guard.)**

7. **Given** REST `GET /api/v1/artifacts/{artifactIdA}/compare/{artifactIdB}`, **Then** returns 200 with `RevisionDelta` JSON; idempotent read; OpenAPI documented; performance target: under 5s for spec/plan deltas of typical pilot size, under 10s for PR-output deltas (file-level summary only — full diff lazy-loaded by 4.20). **(New top-level `ArtifactCompareController` — Reconciliation 4. `operationId="compareArtifacts"`; camelCase `RevisionDeltaResponse` record with `.from(RevisionDelta)`; NO `Idempotency-Key`. Regenerate `openapi.json` and extend `OpenApiSnapshotContractTest`'s `.contains(...)` additive-safety set with `compareArtifacts`; run FE `npm run generate-api`. Perf is dominated by two payload reads + in-memory diff — assert with a fixture test; no new index needed at pilot scale.)**

8. **Given** Problem Details errors, **Then** typed errors cover: `ARTIFACT_LINEAGE_MISMATCH` (400), `ARTIFACT_NOT_FOUND` (404 for either id), `ARTIFACT_PAYLOAD_UNAVAILABLE` (409 — one of the artifacts is not `available`). **(RECONCILED — `ARTIFACT_NOT_FOUND` → REUSE `ARTIFACT_RECORD_NOT_FOUND` (404); `ARTIFACT_PAYLOAD_UNAVAILABLE` → REUSE existing code at its live **503** mapping (NOT 409); `INVALID_ID_PREFIX` (400) for a malformed `art_…` id. ONLY `ARTIFACT_LINEAGE_MISMATCH` (400) is new — Reconciliation 3/12. Contract tests assert `code` + `status` + `details`, never human text.)**

9. **Given** ArchUnit (story 1.11), **Then** `RevisionDeltaService` lives in `application.compare`; its only collaborators are `ArtifactService` + `RedactionPolicyService`; the diff algorithms live in dedicated implementation classes with clear interfaces so they can be unit-tested independently. **(Add a collaborator-constraint `ArchRule` mirroring `APPROVAL_SERVICE_LIVES_IN_APPLICATION_APPROVAL` (`onlyDependOnClassesThat().resideInAnyPackage(...)`) OR a bespoke `ArchCondition` pinning the FQN dependency set to `ArtifactService` + `RedactionPolicyService` (+ `java..`/`org.slf4j..`/`org.springframework.stereotype..`/`org.dradgo.application.compare..`/`org.dradgo.domain..`). Register it as an `@ArchTest` field in `ArchitectureBoundaryTest`; runs in **Failsafe** [[archunit-runs-in-failsafe-not-surefire]]. The three differ classes are pure — no Spring/persistence deps — so they DON'T need `spi`; do NOT create `application.compare.spi` (keeps the hardcoded thin-controller rule untouched — Reconciliation 1).)**

10. **Given** the test suite, **Then** tests cover: spec delta detects added/removed/modified sections, plan delta detects added/removed/reordered/modified steps, PR-output delta produces correct file-level summary, lineage mismatch rejected, no-meaningful-diff (byte-equal artifacts) returns `noMeaningfulDiff=true` + empty changes, sanitization applied on serve, performance under target for fixture artifacts, REST endpoint conformance. **(Split across: unit `MarkdownSectionDifferTest` / `PlanStepDifferTest` / `FileLevelDifferTest` (pure, table-driven); `RevisionDeltaServiceTest` (mock `ArtifactService` + real `RedactionPolicyService` or a stub) — lineage-mismatch → `ARTIFACT_LINEAGE_MISMATCH`, equal-checksum → `noMeaningfulDiff`, redaction applied on serve, `LOCAL_ONLY` refused; `ArtifactCompareControllerTest` (`@WebMvcTest` or the repo's controller-slice pattern) — 200 shape, 400/404/503 Problem Details `code`+`status`; a real-PG `RevisionDeltaCompareIT` covering the parse-from-payload path for all three types + a ≥ typical-pilot-size fixture asserting wall-clock under target; extend `OpenApiSnapshotContractTest`.)**

## Tasks / Subtasks

- [x] **Task 1 — Extend `ArtifactService` with a compare-read seam (AC1, AC2, AC6)**
  - [x] Add a public typed read to `ArtifactService` that: loads the `ArtifactRecordSnapshot` via `artifactRecordPort.findByPublicId(id)` (missing → `ARTIFACT_RECORD_NOT_FOUND` 404), rejects a malformed id up-front (`INVALID_ID_PREFIX` 400 — mirror `getArtifact`), gates on `status == AVAILABLE` + non-null `storageRef`/checksum (else `ARTIFACT_PAYLOAD_UNAVAILABLE` 503), refuses `LOCAL_ONLY` classification, and returns a small typed carrier (e.g. `ArtifactCompareSource(ArtifactRecordSnapshot snapshot, byte[] payloadBytes, String producedByActor)`) with payload bytes read via `artifactPayloadStore.readBytes(storageRef)`. Reuse the already-injected `ArtifactRecordPort` + `ArtifactPayloadStore` — do NOT add new injections to `RevisionDeltaService`. (Reconciliation 1)
  - [x] Source `producedByActor` from the artifact's `linked_event_id → workflow_events.actor_identity` (add a narrow read on `ArtifactRecordPort`/repo, precedent `ArtifactRepository.findRunnerExecutionIdForArtifact:30-40`; keep it behind `ArtifactService`). If deferring per OQ-3, expose it nullable and note it. (Reconciliation 7)
  - [x] Keep `ArtifactService` collaborators unchanged in count — it already injects `ArtifactRecordPort` + `ArtifactPayloadStore` (`isApprovalEligible` uses both).

- [x] **Task 2 — `application.compare` package: `RevisionDeltaService` + view records (AC1, AC2, AC9)**
  - [x] Create `org.dradgo.application.compare.RevisionDeltaService` (`@Service`), injecting ONLY `ArtifactService` + `RedactionPolicyService`. `computeDelta(String artifactIdA, String artifactIdB) → RevisionDelta`, `@Transactional(readOnly = true)`.
  - [x] Load both via the Task-1 compare-read; assert same `workflowRunId` + `artifactType`; verify parent-chain connectivity (walk `parentArtifactId` from the higher-version snapshot; the other must appear; cycle-guarded visited-set) — else `ARTIFACT_LINEAGE_MISMATCH` (400) with `details{artifactIdA, artifactIdB, reason}`. (Reconciliation 2)
  - [x] Fast-path: equal `checksumValue` → `noMeaningfulDiff=true` + empty `changes` (skip payload diff). (Reconciliation 9)
  - [x] Dispatch on `artifactType` to the matching differ (Task 3); assemble `DeltaSummary` counts + `revisionA`/`revisionB` `ArtifactSummary` (version/createdAt/producedByActor/short-checksum).
  - [x] Redact every `ChangeBlock` text field via `redactionPolicyService.redact(...).sanitizedText()` before it enters the returned `RevisionDelta`. (Reconciliation 11)
  - [x] Nested/sibling public records: `RevisionDelta`, `DeltaSummary`, `ArtifactSummary`, `MarkdownChangeBlock`, `PlanStepChangeBlock`, `FileChangeBlock` (+ a sealed/`ChangeBlock` marker or per-variant lists). Nullable fields via Javadoc, no `Optional`.

- [x] **Task 3 — Three dedicated diff-algorithm classes (AC3, AC4, AC5, AC10)**
  - [x] `MarkdownSectionDiffer` — split redacted markdown by ATX headings into `(sectionPath, body)`; align by `sectionPath`; emit `MarkdownChangeBlock` `added`/`removed`/`modified` with whitespace-normalized body comparison. (Reconciliation 5)
  - [x] `PlanStepDiffer` — parse `payload.steps` (mirror `parseSteps` contract: non-empty array, textual non-blank elements) into `List<String>`; LCS-align; emit `PlanStepChangeBlock` `added`/`removed`/`reordered`/`modified` with index-based `stepId`/order. (Reconciliation 6)
  - [x] `FileLevelDiffer` — parse the payload `diff` unified-diff text into `FileChangeBlock{filePath, changeKind, addedLines, removedLines}`; handle absent/empty `diff` → empty summary + null counts; caller sets `linkedDiffReferences`. (Reconciliation 5/8)
  - [x] Each differ is a pure class with an interface (no Spring/persistence deps) so it unit-tests standalone. Decide the line/LCS primitive: **chose the in-house LCS (OQ-4 alt)** — no new dependency; JSON parsing isolated in the java-only `ComparePayloads` helper so the differs stay pure. (Reconciliation 10)

- [x] **Task 4 — REST controller + DTO + OpenAPI regen (AC7, AC8)**
  - [x] `org.dradgo.adapters.rest.ArtifactCompareController` `@RestController @Validated @RequestMapping("/api/v1/artifacts")` `@Tag(...)`; `@GetMapping("/{artifactIdA}/compare/{artifactIdB}")` `operationId="compareArtifacts"`; `@Parameter(example="art_abc123")` path vars; `@ApiResponses` 200 / 400 (`INVALID_ID_PREFIX`, `ARTIFACT_LINEAGE_MISMATCH`) / 404 (`ARTIFACT_RECORD_NOT_FOUND`) / 503 (`ARTIFACT_PAYLOAD_UNAVAILABLE`) each with `@Content(mediaType=APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(implementation=ProblemDetailsResponse.class))`. Thin: parse ids → `revisionDeltaService.computeDelta(...)` → `RevisionDeltaResponse.from(...)`. NO `Idempotency-Key`. `log.info` received/success with `MdcKeys.sanitizeForLog`. (Reconciliation 4)
  - [x] `RevisionDeltaResponse` record (+ nested response records) in `adapters.rest`, camelCase, ISO-8601 UTC, `.from(RevisionDelta)`. Polymorphic change blocks flattened into one `ChangeResponse` with a `blockType` discriminator (single OpenAPI schema, no `oneOf`).
  - [x] Regenerate `deliveryline-backend/src/main/resources/openapi/openapi.json` via the contract test's `-Dopenapi.snapshot.write=true` (263 additive insertions, 0 deletions); extend `OpenApiSnapshotContractTest` additive-safety `.contains("compareArtifacts")`. Then FE: `npm run generate-api`, committed `deliveryline-frontend/src/lib/api/schema.d.ts`; `check:api` green. [[openapi-regen-frontend-client-drift-cascade]]

- [x] **Task 5 — `ARTIFACT_LINEAGE_MISMATCH` three-site add (AC8)**
  - [x] Site 1: `DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH("ARTIFACT_LINEAGE_MISMATCH")`.
  - [x] Site 2: `register(..., ARTIFACT_LINEAGE_MISMATCH, HttpStatus.BAD_REQUEST, "Artifact lineage mismatch", false)` in `ProblemDetailsCatalog.createMetadata()`.
  - [x] Site 3: add `"ARTIFACT_LINEAGE_MISMATCH": ".../problems/artifact-lineage-mismatch"` to `registry-api-schema-placeholders.json` `problemTypeUris`.
  - [x] Confirmed `RegistryContractTest` (25) + `ProblemDetailsCoverageFoundationContract` / foundation-gate (54) pass — the code round-trips 400 through the mapper. [[new-domainerrorcode-three-sites]]

- [x] **Task 6 — ArchUnit + tests + perf (AC9, AC10)**
  - [x] ArchUnit collaborator-constraint rule `REVISION_DELTA_SERVICE_LIVES_IN_APPLICATION_COMPARE` in `ArchitectureRuleCatalog` + `@ArchTest` in `ArchitectureBoundaryTest` (Failsafe, 63 green). Layered/naming rules auto-cover `application.compare` (no allow-list edit). [[archunit-runs-in-failsafe-not-surefire]]
  - [x] Unit: `MarkdownSectionDifferTest` (8), `PlanStepDifferTest` (6), `FileLevelDifferTest` (7) — table-driven, all `changeKind`s + whitespace-only no-op + absent-diff.
  - [x] `RevisionDeltaServiceTest` (7, mock `ArtifactService`): lineage-mismatch (both reasons), equal-checksum `noMeaningfulDiff`, redaction-on-serve pin, A/B direction, prOutput linkedDiffReferences. (`LOCAL_ONLY` refusal + gating covered at the `ArtifactServiceUnitTest` seam where the logic lives.)
  - [x] `ArtifactCompareControllerTest` (5, controller slice): 200 shape + 400/404/503 Problem Details `code`+`status` (never human text).
  - [x] `RevisionDeltaCompareIT` (6, `@SpringBootTest`, `@Tag("integration")`, real PG, name `*IT`): seeds real spec/plan/prOutput v1→v2 lineages; asserts deltas, byte-equal no-meaningful-diff, disjoint-lineage rejection, and a 150-section fixture under the AC7 5s target. [[springboot-testcontainers-test-must-be-IT]]

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] `INFO` on `computeDelta` entry (`artifactIdA`, `artifactIdB`) + success (`artifactType`, `changedRegionCount`, `noMeaningfulDiff`, `durationMs`); `WARN` at the `ARTIFACT_LINEAGE_MISMATCH` raise site + `LOCAL_ONLY`-refusal (in `loadCompareSource`) with sanitized ids; `DEBUG` for payload load. REST controller `log.info` received/success.
  - [x] Parameterized SLF4J only; ids carried as params via `MdcKeys.sanitizeForLog`; never log payload bytes, diff content, secrets, or PII.
  - [x] Pinned the completion-log line + the `WARN`-on-lineage-mismatch line with a `ListAppender` in `RevisionDeltaServiceTest`.

## Dev Notes

### Relevant architecture patterns and constraints

- **The artifact read seam (Reconciliation 1/5/7).** There is NO god `ArtifactService` — the artifact seam is split: `ArtifactService.isApprovalEligible(id)` (approval gate, `ArtifactService.java:26`), `ArtifactOperationService` (write orchestrator — DO NOT TOUCH), `ArtifactReconciliationService`, and `WorkflowInspectionService.getArtifactDetail(runId, artifactId)` (the run-scoped typed read view). The canonical typed snapshot is `ArtifactRecordSnapshot` (15-arg record; use the CANONICAL constructor, NOT the deprecated 11/14-arg ones that zero `failureCategory`/`createdAt`). Load by id via `ArtifactRecordPort.findByPublicId`; payload bytes via `ArtifactPayloadStore.readBytes(storageRef)`. `RevisionDeltaService` reaches all of this THROUGH the extended `ArtifactService` so its collaborator surface stays `ArtifactService` + `RedactionPolicyService` (AC9).
- **Lineage model (Reconciliation 2).** `artifacts.parent_artifact_id` is a self-FK (`on delete set null`); `version` is monotonic per `(workflow_run_id, artifact_type)` (`uq_artifacts_workflow_run_id_artifact_type_version`); root has null parent. A single `(run, type)` can hold MULTIPLE lineages after a failed-leaf fresh draft (`lineage_recovery`, V5). Existing walks to reuse: JVM-side parent-chain loop `ContextBundleService.collectPriorFeedbackReferences` (`:1400-1421`); DB-side leaf CTE `ArtifactRepository.findActiveLineageLeaf` (`:71-102`); version-ordered listing `ArtifactRecordPort.listByWorkflowRunIdAndArtifactType`. For the connectivity check the JVM parent-walk is simplest — two AVAILABLE artifacts, walk from the higher `version` up; the other must appear.
- **Payload shapes (Reconciliation 5/6/8).** Parse from the payload JSON, mirroring `WorkflowInspectionService.getArtifactDetail` (`:1860-1935`) and `parseSteps` (`:1968-1984`) but re-implemented in the differ classes: spec → markdown body; `implementationPlan` → `{"steps":[string,…]}`; `prOutput` → `{branch, commitSha, prReference, diffReference, diff}` (resolved unified diff is `diff`; `diffReference` is an ephemeral pointer often absent; `prReference`/`prState` at read time actually come from the active `github_pr` integration link, not the payload — irrelevant to the delta). prOutput carries an internal `v1→v2` enrich bump (`RunnerBroker.enrichPrOutputArtifact:3096-3185`).
- **View-record + DTO conventions.** Application view records are nested/sibling public `record`s (see `WorkflowInspectionService.ArtifactDetailView`); nullability documented in Javadoc, no `Optional`/`@Nullable`; timestamps `OffsetDateTime`; checksum served short-form `<algo>:<first12hex>` (`shortChecksum`, `:1943-1953`) — never the full digest. REST DTOs are flat `adapters.rest` records with a static `.from(view)` (`ArtifactDetailResponse.from`, `WorkflowDetailResponse.from`).
- **Redaction (Reconciliation 11).** `RedactionPolicyService.redact(text|Map|JsonNode, claimedClassificationWireString) → RedactionResult`; pull `.sanitizedText()`. `redactForExport(...)` is the egress gate (throws `EXPORT_CLASSIFICATION_VIOLATION` on `LOCAL_ONLY`) — NOT for in-app reads. Precedent for redact-before-serve on event details: `WorkflowInspectionService.java:2475-2477`.
- **Problem Details (Reconciliation 3/12).** `DomainException(code, message, details)` → `ProblemDetailsMapper` (`@RestControllerAdvice`) → status/title from `ProblemDetailsCatalog.metadataFor(code)`. The catalog's static init fails fast if any `DomainErrorCode` is unregistered. Only `ARTIFACT_LINEAGE_MISMATCH` (400) is new; everything else reuses live codes.
- **No REST body needs `JsonNode`.** Keep `RevisionDeltaResponse` a typed record (avoid the Boot-4/Jackson-3 `JsonNode`-body 500 trap [[jackson2-jsonnode-dto-500s-under-boot4-jackson3]]).
- **No write, no `RecoveryService`, no Flyway.** Read-only. The `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` ArchUnit rule is untouched (that lift is 4.28). No new `WorkflowState`/`WorkflowEventType`/`AllowedAction`/migration.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for this story):**
  - `RevisionDeltaService.computeDelta` → `INFO` on entry (`artifactIdA`, `artifactIdB`) + `INFO` on success (`artifactType`, `changedRegionCount`, `noMeaningfulDiff`, `durationMs`); `DEBUG` per payload read.
  - Lineage mismatch / `LOCAL_ONLY` refusal / not-found / not-available → `WARN` at the raise site with the domain error code + sanitized ids (throw logs once; downstream catchers do not re-log).
  - `ArtifactCompareController` → `INFO` received/success mirroring `WorkflowController.getArtifact`.
- **Required context keys:** `correlationId`, `artifactIdA`, `artifactIdB`.
- **Forbidden in log output:** payload bytes, diff text, secrets/tokens, PII. The delta content is redacted for the RESPONSE, but must NOT be echoed into logs at all.
- **Test contract:** pin the completion-log line + the `WARN`-on-mismatch line with a `ListAppender`/`OutputCaptureExtension`.

### Project Structure Notes

- New main: `application/compare/RevisionDeltaService.java` + `MarkdownSectionDiffer` / `PlanStepDiffer` / `FileLevelDiffer` (+ their interfaces) + the view records; extended `application/artifact/ArtifactService.java` (compare-read + producing-actor); `adapters/rest/ArtifactCompareController.java` + `RevisionDeltaResponse.java`. New `DomainErrorCode` value + `ProblemDetailsCatalog` registration + placeholder-manifest entry. Regenerated `openapi.json` + FE `schema.d.ts`.
- No new `application.compare.spi` package (the differs are pure; `RevisionDeltaService` reaches persistence only via `ArtifactService`) — this deliberately keeps the hardcoded `REST_CONTROLLERS_STAY_THIN…` rule (which enumerates `application.artifact.spi..`) untouched.
- Variance: FIRST top-level `/api/v1/artifacts` REST resource (existing artifact reads are run-scoped under `/api/v1/workflows/**`). Justified because `art_…` ids are globally unique and loadable without a run.
- No Flyway migration (read-only; the parent-chain walk rides `idx_artifacts_parent_artifact_id` + `findByPublicId`). No new `WorkflowEventType`/`AllowedAction`/`WorkflowState`.

### References

- [Source: _bmad-output/planning-artifacts/epic-04-recovery.md#Story 4.19 (lines 413–430)] — AC1–AC10.
- [Source: _bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md] — the artifact model (lineage, status, versioning, checksum, `ArtifactRecordSnapshot`, payload store) this story reads.
- [Source: _bmad-output/implementation-artifacts/1-6-runner-context-result-schema-v1-with-artifact-variant-discriminators.md] — `artifactType` wire values `spec`/`implementationPlan`/`prOutput` + per-variant payload sub-schemas (spec markdown; plan steps array; prOutput `branch`/`commitSha`/`prReference`/`diffReference`).
- [Source: _bmad-output/implementation-artifacts/3-27-artifact-review-panel-pr-output-variant-renderer.md] — `diffReference` is a storage REF on the wire, not diff bytes; resolved `diff` is the rendered field.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactService.java:26,33,48] — the single `isApprovalEligible` method + `findByPublicId`/`readBytes` reuse (Reconciliation 1).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactRecordSnapshot.java:9-24] — canonical 15-arg snapshot; `parentArtifactId`, `version`, `checksumValue`, `createdAt`, `status`, `classification`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/spi/ArtifactRecordPort.java:13,25] — `findByPublicId`; `listByWorkflowRunIdAndArtifactType`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/spi/ArtifactPayloadStore.java:21] — `readBytes(storageRef)`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/ContextBundleService.java:1400-1421] — cycle-guarded `parentArtifactId` walk (reuse for lineage check).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java:1810-1818,1860-1935,1943-1953,1968-1984,2475-2477] — `LOCAL_ONLY` refusal; payload→body/diff/steps parsing; `shortChecksum`; `parseSteps`; redact-before-serve precedent.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:3096-3185,3264-3294,3316-3351] — prOutput payload assembly, `diff` resolve/omit, `v2` enrich bump (Reconciliation 8).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactType.java:5-8] — `spec`/`implementationPlan`/`prOutput`, all default `SHAREABLE_REDACTED`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/ArtifactStatus.java:5-9] — `pending|available|failed|late_or_stale`; gate on `available`.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java:20] + RedactionResult.java — `redact(...).sanitizedText()` (Reconciliation 11).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java:29,31,59] — reuse `ARTIFACT_PAYLOAD_UNAVAILABLE`/`ARTIFACT_RECORD_NOT_FOUND`; inline three-sites precedent.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java:143-148,155-160,530-532,548-550] — 503/404 mappings; fail-fast completeness guard; URI slug derivation.
- [Source: deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json:113-187] — `problemTypeUris` manifest (Site 3).
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java:404-446; foundation/ProblemDetailsCoverageFoundationContract.java:52,96] — the two gates that auto-verify the three-site add.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java:719-774; ProjectController.java:62-64,177] — GET artifact springdoc pattern; resource-root controller precedent.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java:58,86-133] — `-Dopenapi.snapshot.write=true` regen + additive `.contains(...)`; deliveryline-frontend/package.json:19-20 (`generate-api`/`check:api`).
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:602-621,784-797,1155-1188] — collaborator-constraint rule precedents (`onlyDependOnClassesThat` + bespoke `ArchCondition`).

### Open Questions (for Alex — do not block dev; provisional bindings applied)

- **OQ-1 — `RevisionDeltaService`'s artifact-read binding.** Provisional: EXTEND `ArtifactService` with the compare-read so its ONLY collaborators are `ArtifactService` + `RedactionPolicyService` (honors AC9 literally). Alternative: inject `ArtifactRecordPort` + `ArtifactPayloadStore` directly and relax the AC9 collaborator wording. The extend-`ArtifactService` route is applied.
- **OQ-2 — prOutput compare semantics across the `v1→v2` enrich bump.** A prOutput lineage often has an internal enrich version (v1 no diff → v2 authoritative diff). Comparing v1↔v2 shows the enrich, not a reviewer change. Provisional: compute the delta faithfully over whatever two ids are passed; the UI (4.20) chooses which revisions to compare (typically across re-runs / rerun-from-step supersessions, not the enrich bump). Confirm whether the backend should special-case/skip enrich-only bumps.
- **OQ-3 — `producedByActor` sourcing.** No actor field on the snapshot; it requires a `linked_event_id → workflow_events.actor_identity` join. Provisional: add a narrow read behind `ArtifactService`. Alternative: ship `producedByActor` nullable/deferred to 4.20 if the join is deemed out of scope.
- **OQ-4 — diff primitive.** Provisional: add `io.github.java-diff-utils:java-diff-utils` (explicit pinned version) for the line/LCS primitive behind the differ interfaces. Alternative: small in-house LCS to avoid a new dependency. Section-split / step-alignment / unified-diff-header parsing are ours either way.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-create-story workflow.

### Debug Log References

- Differ unit tests (Surefire): `MarkdownSectionDifferTest` 8, `PlanStepDifferTest` 6, `FileLevelDifferTest` 7 — all green.
- Service/adapter unit (Surefire): `RevisionDeltaServiceTest` 7, `ArtifactServiceUnitTest` 11 (6 new compare-read gates), `ArtifactCompareControllerTest` 5 — all green.
- Failsafe gates: `RevisionDeltaCompareIT` 6, `OpenApiSnapshotContractTest` 1, `RegistryContractTest` 25, `ArchitectureBoundaryTest` 63 — all green.
- Foundation gate (`-Pfoundation-gate`): `FoundationGateVerificationTest` 54 green; log shows `ARTIFACT_LINEAGE_MISMATCH status=400` round-tripping the mapper.
- `spotless:check` clean; FE `check:api` reports client in sync with the committed snapshot.

### Completion Notes List

- **AC1/AC9 collaborator pin honored (OQ-1 applied).** `RevisionDeltaService` injects ONLY `ArtifactService` + `RedactionPolicyService`. The three differs are pure java-only classes instantiated internally (not injected), JSON parsing is isolated in the java-only `ComparePayloads` helper, and a new ArchUnit rule pins the FQN dependency set. `ArtifactService` was extended with `loadCompareSource(id)` (availability-gated carrier) + `findSnapshot(id)` (un-gated ancestor lookup for the parent-walk); its injected collaborator count is unchanged (still the two ports it already had).
- **Reconciliation 2 lineage check** = same `(workflowRunId, artifactType)` + cycle-guarded parent-chain walk from the higher-version snapshot; disjoint lineages under one run+type (lineage_recovery) reject with `ARTIFACT_LINEAGE_MISMATCH` reason `not_on_parent_chain`; different run/type → reason `different_run_or_type`.
- **Reconciliation 3 error codes** — only `ARTIFACT_LINEAGE_MISMATCH` (400) is new (three-site add). `ARTIFACT_NOT_FOUND` reuses `ARTIFACT_RECORD_NOT_FOUND` (404); `ARTIFACT_PAYLOAD_UNAVAILABLE` reused at its live **503**; `LOCAL_ONLY` refused as `ARTIFACT_RECORD_NOT_FOUND` (404 opacity, mirroring `getArtifactDetail`); malformed id → `INVALID_ID_PREFIX` (400).
- **OQ-4 decision: in-house LCS** (no `java-diff-utils` dependency added) — the section split, plan-step alignment, and unified-diff header parsing are all ours; a small LCS covers the plan-step sequence alignment. No `pom.xml` change.
- **OQ-2 (prOutput enrich bump)** — the delta is computed faithfully over whatever two ids are passed (the file-level differ unions the two resolved diffs; `linkedDiffReferences=[a,b]` always populated so 4.20 lazy-loads full diff). No special-casing of the v1→v2 enrich bump; left for the 4.20 UI to choose revisions.
- **OQ-3 (producedByActor)** — implemented via a narrow native `linked_event_id → workflow_events.actor_identity` read on `ArtifactRecordPort` (mirrors `findRunnerExecutionIdForArtifact`), exposed behind `ArtifactService`; nullable when no linked event/actor.
- **Redaction (AC6)** applied to every free-text `ChangeBlock` field (`priorText`/`currentText`, `priorStepText`/`currentStepText`) via `redact(...)` (never `redactForExport`). File blocks carry only path + counts (no free text), so they are not redacted.
- **DTO shape** — polymorphic change blocks flattened to one `ChangeResponse` with a `blockType` discriminator (`markdown`/`planStep`/`file`) → single OpenAPI schema, no `oneOf`/Jackson polymorphism, no `JsonNode` body.
- **No write path / migration / RecoveryService / new state-event-action touched** — read-only story as scoped.

### File List

**New — main (`deliveryline-backend/src/main/java`):**
- `org/dradgo/application/compare/ChangeKind.java`
- `org/dradgo/application/compare/ChangeBlock.java` (sealed)
- `org/dradgo/application/compare/MarkdownChangeBlock.java`
- `org/dradgo/application/compare/PlanStepChangeBlock.java`
- `org/dradgo/application/compare/FileChangeBlock.java`
- `org/dradgo/application/compare/DeltaSummary.java`
- `org/dradgo/application/compare/ArtifactSummary.java`
- `org/dradgo/application/compare/RevisionDelta.java`
- `org/dradgo/application/compare/MarkdownSectionDiffer.java` + `DefaultMarkdownSectionDiffer.java`
- `org/dradgo/application/compare/PlanStepDiffer.java` + `DefaultPlanStepDiffer.java`
- `org/dradgo/application/compare/FileLevelDiffer.java` + `DefaultFileLevelDiffer.java`
- `org/dradgo/application/compare/ComparePayloads.java`
- `org/dradgo/application/compare/RevisionDeltaService.java`
- `org/dradgo/application/artifact/ArtifactCompareSource.java`
- `org/dradgo/adapters/rest/ArtifactCompareController.java`
- `org/dradgo/adapters/rest/RevisionDeltaResponse.java`

**Modified — main:**
- `org/dradgo/application/artifact/ArtifactService.java` (+ `loadCompareSource`, `findSnapshot`)
- `org/dradgo/application/artifact/spi/ArtifactRecordPort.java` (+ `findProducingActorForArtifact`)
- `org/dradgo/adapters/persistence/repository/ArtifactRepository.java` (+ native actor query)
- `org/dradgo/adapters/persistence/ArtifactRecordPersistenceAdapter.java` (+ impl)
- `org/dradgo/domain/registry/DomainErrorCode.java` (+ `ARTIFACT_LINEAGE_MISMATCH`)
- `org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+ 400 registration)
- `deliveryline-backend/src/main/resources/openapi/openapi.json` (regenerated, additive)

**New — test:**
- `org/dradgo/application/compare/MarkdownSectionDifferTest.java`
- `org/dradgo/application/compare/PlanStepDifferTest.java`
- `org/dradgo/application/compare/FileLevelDifferTest.java`
- `org/dradgo/application/compare/RevisionDeltaServiceTest.java`
- `org/dradgo/application/compare/RevisionDeltaCompareIT.java`
- `org/dradgo/adapters/rest/ArtifactCompareControllerTest.java`

**Modified — test:**
- `org/dradgo/application/artifact/ArtifactServiceUnitTest.java` (+ 6 compare-read gates)
- `org/dradgo/adapters/rest/OpenApiSnapshotContractTest.java` (+ `compareArtifacts` assertion)
- `org/dradgo/architecture/ArchitectureRuleCatalog.java` (+ collaborator rule)
- `org/dradgo/architecture/ArchitectureBoundaryTest.java` (+ `@ArchTest` registration)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+ URI)

**Regenerated — frontend:**
- `deliveryline-frontend/src/lib/api/schema.d.ts`

## Change Log

| Date | Change |
|---|---|
| 2026-07-15 | Story 4.19 implemented via bmad-dev-story (Opus 4.8 [1m]). New `application.compare` package: `RevisionDeltaService` (@Service, collaborators pinned to `ArtifactService`+`RedactionPolicyService`) + 8 view records + 3 pure differ classes (in-house LCS, OQ-4 alt) + `ComparePayloads` JSON helper. Extended `ArtifactService` with `loadCompareSource`/`findSnapshot` + producing-actor native read (OQ-3). New top-level `GET /api/v1/artifacts/{a}/compare/{b}` (`compareArtifacts`) + `RevisionDeltaResponse` (flattened `blockType` change DTO). `ARTIFACT_LINEAGE_MISMATCH` (400) three-site add. OpenAPI regen (additive) + FE `schema.d.ts`. New ArchUnit collaborator rule. Tests: 26 unit (differs/service/controller) + 6 real-PG IT + gates (OpenAPI/Registry/ArchUnit/foundation) all green; spotless clean; `check:api` in sync. Status → review. |
| 2026-07-05 | Story 4.19 created via bmad-create-story (Opus 4.8 [1m]). Compare Mode BACKEND: new `application.compare.RevisionDeltaService` computing typed deltas (spec section diff / plan step diff / prOutput file-level summary) between two artifact versions of one lineage, served via a NEW top-level `GET /api/v1/artifacts/{a}/compare/{b}`. Central reconciliations (epic AC drifts; live bindings win): (1) `ArtifactService` exposes only `isApprovalEligible` → EXTEND it with a compare-read so `RevisionDeltaService`'s only collaborators are `ArtifactService`+`RedactionPolicyService` (AC9); (2) lineage check must WALK `parent_artifact_id` (multi-lineage per run+type via `lineage_recovery`); (3) of the 3 epic error codes only `ARTIFACT_LINEAGE_MISMATCH` (400) is new — `ARTIFACT_NOT_FOUND`→reuse `ARTIFACT_RECORD_NOT_FOUND` (404), `ARTIFACT_PAYLOAD_UNAVAILABLE` reused at its live 503 (not 409); (5) `diff`/`steps`/`diffReference` are payload-JSON fields not columns; (6) plan steps are plain strings (no `stepId` → positional); (8) prOutput `diff` often absent + internal `v1→v2` enrich bump. Read-only: no Flyway/WorkflowEventType/AllowedAction/write path. OpenAPI regen + FE `generate-api`. Status → ready-for-dev. |

### Review Findings

_Code review 2026-07-15 (bmad-code-review, 3 adversarial layers: Blind Hunter / Edge Case Hunter / Acceptance Auditor). 13 distinct findings after dedup. Decisions resolved by Alex 2026-07-15: D1 → patch (full fix), D2 → accepted per Reconciliation 11. Patches batch-applied 2026-07-15: 4 fixed, 1 dismissed-on-verification (P4). Final: 4 patch FIXED, 1 patch dismissed, 4 deferred, 4 dismissed/accepted. Compare unit tier 45/0 green (6 new tests); 2 new perf ITs compile (run under Failsafe)._

**Decisions resolved (2026-07-15)**

- [x] [Review][Decision→Dismiss] `sectionPath` heading trail emitted unredacted (AC6 tension) — **ACCEPTED per binding Reconciliation 11** (which lists only prior/current/section-text; `sectionPath` is a structural alignment key, not body content — redacting it would break A/B section matching). Explicit accepted decision, no code change. [`RevisionDeltaService.java:185`]

**Patch (batch-applied 2026-07-15)**

- [x] [Review][Patch][FIXED] `noMeaningfulDiff` masks non-whitespace differences (was D1) — replaced the `changes.isEmpty()` shortcut with a positive per-type whitespace-only test: SPEC uses new `MarkdownSectionDiffer.isWhitespaceOnlyDifference` (order-preserving normalize → a reorder returns false); IMPLEMENTATION_PLAN requires `changes.isEmpty()` AND both payloads pass new `ComparePayloads.stepsPayloadParseable` (a swallowed parse failure no longer masquerades as equivalence); prOutput unchanged (never no-diff). Tests: `specSectionReorderIsNotClaimedAsNoMeaningfulDiff`, `bothUnparseablePlanPayloadsAreNotClaimedAsNoMeaningfulDiff`, `identicalPlanStepsWithDifferingChecksumIsNoMeaningfulDiff`. [`RevisionDeltaService.java`, `MarkdownSectionDiffer.java`, `ComparePayloads.java`]
- [x] [Review][Patch][FIXED] Unified-diff parser misclassifies content lines starting `-- `/`++ ` as file headers — added hunk-state tracking to `DefaultFileLevelDiffer.parse`: `@@` sets `inHunk`, `diff --git` resets it, and `--- `/`+++ ` are only treated as headers when `!inHunk`, so content lines inside a hunk (e.g. a removed `-- sql comment`) are counted, not misparsed. Test: `contentLinesResemblingFileHeadersAreCountedNotMisparsed`. [`DefaultFileLevelDiffer.java`]
- [x] [Review][Patch][FIXED] Mixed/interchangeable code-fence markers swallow following headings — `DefaultMarkdownSectionDiffer.split` now tracks the opening marker (`fenceMarker`) and only closes on a matching ` ``` `/`~~~`, so a `~~~` line inside a ` ``` ` fence no longer flips fence state and drops later headings. Test: `mismatchedFenceMarkersDoNotSwallowFollowingHeadings`. [`DefaultMarkdownSectionDiffer.java`]
- [x] [Review][Patch][DISMISSED on verification] `createdAt` nullability contract — **NOT a bug.** `artifacts.created_at` is `NOT NULL DEFAULT now()` since V1 (`V1__create_workflow_core_tables.sql:116`), so `createdAt` can never actually serialize `null`; the OpenAPI non-nullable declaration is correct and the Java Javadoc/`toUtc` null-guard is merely dead-defensive (harmless). No contract change made. [`openapi.json:439`, `V1__create_workflow_core_tables.sql:116`]
- [x] [Review][Patch][FIXED] Performance ACs for plan/prOutput unasserted (AC7/AC10) — added `planStepDeltaOfTypicalPilotSizeCompletesUnderTarget` (200-step plan, <5s) and `prOutputDeltaOfTypicalPilotSizeCompletesUnderTarget` (120-file diff, <10s) to `RevisionDeltaCompareIT`. [`RevisionDeltaCompareIT.java`]

**Deferred**

- [x] [Review][Defer] LCS DP matrix `int[n+1][m+1]` is O(n·m) unbounded — OOM on pathological plan sizes [`DefaultPlanStepDiffer.java:121`] — deferred: beyond AC7 "typical pilot size"; add a size guard/fallback when hardening.
- [x] [Review][Defer] Duplicate heading trails merge into one section [`DefaultMarkdownSectionDiffer.java:95`] — deferred: `sections.merge(...)` concatenates same-`sectionPath` bodies; rare in well-formed specs, a change to one duplicate is mis-attributed.
- [x] [Review][Defer] `normalize` strips whitespace inside fenced code blocks [`DefaultMarkdownSectionDiffer.java:99`] — deferred: an indentation-only change in a code sample inside a spec is normalized to equal → missed MODIFIED; whitespace-insensitivity is intended for prose.
- [x] [Review][Defer] `extractGitPaths`/`stripDiffPath` mis-parse paths with spaces or git-quoted paths [`DefaultFileLevelDiffer.java:82`] — deferred: `indexOf(" b/")` can match inside a space-containing filename; rare input.

**Dismissed (noise / by-design)** — 3: (1) plan differ zips leftover deleted/inserted steps as MODIFIED — explicitly documented design per Reconciliation 6; (2) `checksumsEqual` ignores the algorithm — algorithm is constant within one lineage, cross-algorithm hex collision astronomically unlikely; (3) fork-sibling compare rejected as `ARTIFACT_LINEAGE_MISMATCH` — sanctioned by binding Reconciliation 2 (parent-chain reachability).
