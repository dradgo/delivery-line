# Story 3a.9: Spec Artifact Live Review + Approval — Read-Model `artifactId` Exposure + Artifact-Read Endpoint

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner / spec reviewer,
I want the run-detail read surface to expose the spec artifact's **public id** and a **read endpoint that returns the artifact's redacted content**,
so that the Artifact Review Panel (story 2.17) renders the real spec body and the Approval/Decision Bar (story 2.19) can fire `approve_spec`/`reject_spec` against a live artifact — closing the last mile of the hands-off Linear-ticket → real-spec → human-review loop instead of stranding every run at `WaitingForSpecApproval`.

### Context — why this story exists (the real-run gate)

Surfaced by the Epic-2-retro **"first real full-cycle run"** pilot-readiness gate (`[[epic-2-retro-real-run-gate]]`, C1–C3). The first real Linear→spec runs (e.g. `run_b3fbcafb…`, FIN-18) reached `WaitingForSpecApproval` with a genuine spec artifact, but the reviewer **cannot read the spec and cannot approve it** in the UI. Three dormant seams stack up; the spec stage was never exercised end-to-end before the real-run gate:

1. **Ingest leaves the spec `pending`.** The runner-ingested spec artifact stayed `status=pending`; the human approval gate (`ArtifactService.isApprovalEligible`, which requires `status=AVAILABLE` + checksum + storageRef) rejected approval with `ARTIFACT_PAYLOAD_UNAVAILABLE`. → **Gate 1.**
2. **The read model omits the artifact id.** `WorkflowDetail.latestArtifacts[]` carries only `{artifactType, version, status}` — no `artifactId`. The approval bar's `resolveSpecArtifactId` (story 2.19 **T-ARTIFACTID**) therefore resolves `undefined` → `blocked` → *"The specification is not yet available for a decision."* → **Gate 2.**
3. **No artifact-content read endpoint.** `useArtifact(artifactId)` (story 2.6 / 2.17) is a disabled stub; the detail route only links a hardcoded `art_sample0001`. → **Gate 3.**

> **SCOPE DISCIPLINE — spec-stage read+approve ONLY.** NOT artifact revision history, compare/diff (Epic 4), implementation-plan/pr-output variants (3.26/3.27), or per-viewer authorization. The `implementationPlan` + `prOutput` artifacts **deliberately remain `pending`-on-ingest** and out of this story; their human gate (developer review) arrives with 3.20/3.23/3.26. This is the spec slice of the read surface only.

> ⚠️ **Gate 1 is ALREADY PARTIALLY WIRED in the working tree — DO NOT re-implement or revert it.** Four files are modified+staged in the working tree (see *Pre-staged working-tree changes* below). Your job for Gate 1 is to **keep, finalize, and test** that wiring. Gates 2 and 3 (read model + endpoint + frontend) are net-new.

## Acceptance Criteria

1. **Spec artifact `available` on ingest (Gate 1 — formalize + test).** On a successful **spec-stage** ingest the broker promotes the artifact to `available` (checksum over the ingested payload bytes + the store-reported `storageRef`), so `ArtifactService.isApprovalEligible` passes. Scoped to `spec`; `implementationPlan`/`prOutput` keep their deliberate `pending`-on-ingest posture. Idempotent-replay safe (a duplicate `onResult` with no re-write is a no-op — `storageRef==null` → skip). Backend real-wiring IT asserts the ingested spec is `available` and `approveSpec` advances `WaitingForSpecApproval → Executing`. Supersedes `[[markavailable-has-no-production-caller]]` for the spec stage only.

2. **Expose `artifactId` (Gate 2).** Add the artifact **public id** to `LatestArtifactView` → `WorkflowDetail.latestArtifacts[].artifactId`. Regenerate the committed OpenAPI snapshot + frontend `schema.d.ts` (story 1.21 drift check passes). The approval bar goes live with **zero bar-component changes** — `resolveSpecArtifactId` already reads `latestArtifacts[].artifactId` (T-ARTIFACTID closed).

3. **Artifact-read endpoint (Gate 3).** `GET /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}` returns a typed artifact DTO: `artifactId`, `artifactType`, `version`, `status`, `classification`, `createdAt`, `checksum` (short-form), and the **redacted** payload `body` (UTF-8 markdown string, NOT base64). Sourced from the existing artifact record + payload store — **no new redaction logic** (the persisted bytes are already redacted at write time per 1.10/2.24).

4. **Cross-run ownership guard (Gate 3).** An `artifactId` not owned by `{workflowRunId}` returns `ARTIFACT_RECORD_NOT_FOUND` (404) — never another run's artifact. A non-existent `{workflowRunId}` returns `RUN_NOT_FOUND` (404). Malformed id → `INVALID_COMMAND_PAYLOAD`/`INVALID_ID_PREFIX` (400) at the route/validation boundary, mirroring the existing detail endpoint. All three codes ALREADY exist in `DomainErrorCode` + `ProblemDetailsCatalog` (do NOT add new codes — see Dev Notes).

5. **Classification guard (Gate 3).** A `local-only` artifact is never served as shareable content. The served `body` is the already-persisted redacted/shareable payload. Adversarial test: no Linear/GitHub token, absolute host path, or `.env` content in the response body.

6. **Frontend wiring — minimal (Gate 2/3).** Flip `useArtifact` from disabled stub → live `apiClient.GET` against the new endpoint (reusing the `workflowKeys.artifact(artifactId)` key from 2.6). The detail route replaces the hardcoded `art_sample0001` link with the real spec `artifactId` resolved from `latestArtifacts`. The Artifact Review Panel flips `empty → default` and the Approval Bar flips `blocked → ready` on a real spec run.

7. **DTO ⇄ `isArtifactView` contract alignment (Gate 3 — CRITICAL, see Dev Notes D1).** The data returned by the live `useArtifact` MUST satisfy the frontend runtime guard `isArtifactView` (`artifactView.ts`), which **requires** `artifactId: string`, `title: string`, `version: number`, `classification: string`, `body: string`, `createdAt: string`. A response missing `artifactId` or `title` makes the panel render **`error`, not `default`**. Pin this with a test (panel renders `default` on the live shape).

8. **Tests.** Backend: read-endpoint contract test (happy 200 with redacted body; each 4xx; cross-run guard) + a `WorkflowInspectionService` test asserting `latestArtifacts[].artifactId` is populated + the Gate-1 real-wiring IT (already added — verify it passes). Frontend: `useArtifact` live test (MSW), approval-bar `ready` (not `blocked`) once `artifactId` present, ARP renders the real spec body (`default` state). One end-to-end happy path: real spec run → read spec body → approve → `Executing`.

9. **No new contract beyond the field + endpoint.** No new `DomainErrorCode`, no new `WorkflowEventType`, no Flyway migration, no new `@ConfigurationProperties`. The only contract changes are: `+artifactId` on `LatestArtifact` and the new `GET .../artifacts/{artifactId}` operation — both reflected in the regenerated OpenAPI snapshot + `schema.d.ts`.

## Tasks / Subtasks

- [x] **Task 1 — Gate 1: finalize + verify the spec-available wiring (AC1)**
  - [x] Verify the four pre-staged working-tree files compile and the new logic is correct (see *Pre-staged working-tree changes*). Do NOT revert them.
  - [x] Confirm `RunnerBroker.markSpecArtifactAvailable(...)` is scoped to `ArtifactType.SPEC` only, runs inside the poller per-item tx, and no-ops on `storageRef==null` (idempotent replay).
  - [x] Run `SpecStageOrchestrationIT.specArtifactIsMarkedAvailableAndApprovableAfterPoll` (Docker tier) — green.
- [x] **Task 2 — Gate 2: add `artifactId` to the read model (AC2)**
  - [x] `WorkflowInspectionService.java`: add `String artifactId` to the `LatestArtifactView` record (`:1219`) and pass `snapshot.publicId()` at the construction site (`~:537–549`).
  - [x] `adapters/rest/WorkflowDetailResponse.java`: add `artifactId` to the nested `LatestArtifact` record (`:74–81`) and its `from(LatestArtifactView)` mapper.
  - [x] Regenerate the OpenAPI snapshot + frontend `schema.d.ts` (see *OpenAPI / schema regen*).
  - [x] Add a `WorkflowInspectionService` test asserting `latestArtifacts[].artifactId` is populated for a run with a spec artifact.
- [x] **Task 3 — Gate 3: artifact-read endpoint (AC3, AC4, AC5, AC7)**
  - [x] Add a read method to `WorkflowInspectionService` (e.g. `getArtifactDetail(workflowRunId, artifactId)`): resolve run (→ `RUN_NOT_FOUND`), `artifactRecordPort.findByPublicId(artifactId)` (→ `ARTIFACT_RECORD_NOT_FOUND`), cross-run guard `artifact.workflowRunId().equals(runId)` else `ARTIFACT_RECORD_NOT_FOUND`, classification guard (reject `LOCAL_ONLY`), read bytes via `ArtifactPayloadStore.readBytes(storageRef)`, return a view carrying `body` as a UTF-8 string.
  - [x] New REST DTO `ArtifactDetailResponse` (record in `adapters.rest`) — `artifactId`, `artifactType`, `version`, `status`, `classification`, `createdAt` (UTC ISO-8601), `checksum` (short-form), `body`. `@Schema` annotated.
  - [x] Add `@GetMapping("/{workflowRunId}/artifacts/{artifactId}")` to the **existing** `WorkflowController` (no new controller class → no new ArchUnit rule). Mirror the detail endpoint: entry/success `log.info`, `@ApiResponses` for 200/400/404, `produces=APPLICATION_JSON_VALUE`.
  - [x] Regenerate OpenAPI snapshot + `schema.d.ts` for the new operation.
- [x] **Task 4 — Frontend wiring (AC6, AC7)**
  - [x] `queryOptions.ts`: add `artifactQueryOptions(workflowRunId, artifactId)` + a `fetchArtifact` that calls `apiClient.GET('/api/v1/workflows/{workflowRunId}/artifacts/{artifactId}', ...)` and **maps the raw DTO into the `ArtifactView` shape** (compose `title`, ensure `artifactId` present) so `isArtifactView` passes (D1).
  - [x] `useArtifact.ts`: change signature to `useArtifact(workflowRunId, artifactId)`, drop the stub/`enabled:false`, return `useQuery(artifactQueryOptions(...))`.
  - [x] `ArtifactReviewPanel.tsx:243`: update the call site to `useArtifact(workflowRunId, artifactId)` (the container already has `workflowRunId` in props — one-line change; the "zero component changes" claim refers to render logic, not this wire).
  - [x] `routes/workflows/$workflowRunId/index.tsx:164–169`: replace the hardcoded `art_sample0001` with the real spec `artifactId` resolved via `resolveSpecArtifactId(data)`; hide/disable the link when no spec artifact exists yet.
  - [x] (Optional, in-scope) `routes/.../artifacts/$artifactId.tsx` loader stub: keep as-is OR warm the artifact query — the ARP container drives the live fetch regardless.
- [x] **Task 5 — Tests (AC8)**
  - [x] Backend contract test for the read endpoint (mirror `WorkflowReadEndpointsContractTest`): happy 200 redacted body, 404 cross-run, 404 missing run, 400 malformed, classification/adversarial (no token/path/.env in body).
  - [x] Frontend: `useArtifact` live MSW test; approval-bar `ready` fixture test already exists (verify it lights with resolved `artifactId`); ARP `default`-state test on the live shape (D1).
  - [x] E2E happy path: real spec run → GET body → approve → `Executing` (the Gate-1 IT covers the approve half; assert the read half too).
- [x] **Task 6 — Verification & regen hygiene**
  - [x] Run gates via **PowerShell** (`[[rtk-hook-only-matches-bash]]`): backend fast tier, foundation-gate, the Docker IT; frontend vitest + lint + prettier.
  - [x] OpenAPI/schema regen is a cross-shell chore (`[[openapi-regen-platform-shim]]`); regenerate the lockfile cleanly if touched (`[[frontend-lockfile-cross-platform]]`); verify on Linux/Docker CI before merge (`[[verify-ci-fixes-in-clean-env]]`, `[[wsl-linux-ci-reproduction]]`).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB read, payload-store read), and every guard/reject branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (read-request start/finish, spec→available transition), `WARN` for recoverable anomalies (cross-run/classification reject, payload unreadable), `ERROR` only for unhandled failures.
  - [x] Every log carries the relevant context keys: `correlationId`, `workflowRunId`, `artifactId`, `actorIdentity`. Use MDC (`MdcKeys`) where the framework supports it.
  - [x] Never log secrets, payload bytes, raw tokens, the artifact `body`, or full PII. (T8: the frontend already logs only the ProblemDetails `code`, never the body — mirror on the backend.)
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (list-appender / `OutputCaptureExtension`).

### Review Findings

_Code review 2026-06-14 (adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 decision-needed, 5 patch, 1 defer, 7 dismissed as noise/by-design._

- [x] [Review][Patch] APPLIED (resolved from Decision → add `status == AVAILABLE` filter) Read endpoint is not status-scoped — serves non-`AVAILABLE` artifacts, including the deliberately-`pending` `implementationPlan`/`prOutput` — `getArtifactDetail` (`WorkflowInspectionService.java`) guards cross-run + `LOCAL_ONLY` classification but never checks `artifact.status()`, unlike the sibling `ArtifactService.isApprovalEligibleInternal` which filters `status() == AVAILABLE`. The endpoint resolves ANY artifact by public id, so a caller holding an `implementationPlan`/`prOutput` artifactId can read its redacted body now, before the developer-review gate (3.20/3.23/3.26) — contradicting the story's "spec slice ONLY; impl-plan/pr-output deliberately remain pending and out of scope" discipline. Secrets stay protected (write-time redaction + LOCAL_ONLY guard), so this is premature-readability/scope, not a secret leak. (Note: `ArtifactStatus` is `PENDING/AVAILABLE/FAILED/LATE_OR_STALE` — no quarantined/superseded as one hunter guessed.) Decision: add a `status == AVAILABLE` filter and/or restrict to `ArtifactType.SPEC`, or consciously accept serving any status (the DTO does expose `status`).
- [x] [Review][Patch] APPLIED (resolved from Decision → extend `SpecStageOrchestrationIT` to assert the read half) AC8 "one end-to-end happy path (read → approve → `Executing`)" not realized as a single integrated test — the read half is covered only by `WorkflowArtifactReadEndpointContractTest` (hand-seeds artifacts via raw `jdbcTemplate` on a `WaitingForSpecApproval` run), and the approve half by `SpecStageOrchestrationIT` (which has no `getArtifactDetail`/`artifacts/{id}` assertion). Both halves are individually green, but AC8 + Task 5 explicitly ask for one path that reads a real broker-produced spec body and then approves. Decision: extend `SpecStageOrchestrationIT` to assert the read half, or accept the split coverage.
- [x] [Review][Patch] APPLIED — Empty payload (`byte[0]`) served as 200 with blank `body` — `getArtifactDetail` only checks `payloadBytes.isEmpty()` (the Optional), not array length; `isApprovalEligibleInternal` additionally rejects `payload.length == 0`. A present-but-empty payload renders the panel `default` with a blank spec. Mirror the `length == 0 → ARTIFACT_PAYLOAD_UNAVAILABLE` guard. [`WorkflowInspectionService.java` getArtifactDetail]
- [ ] [Review][Patch] NOT APPLIED (left as action item — behavior change needs judgment: the field-defaulting in `toArtifactView` is deliberate; tightening risks the happy path) Frontend `isArtifactView` `error` path is effectively unreachable — `toArtifactView` always injects `artifactId` (from arg) + composes `title`, and defaults `version→0`, `classification→''`, `createdAt→''`; all pass the guard. A malformed live DTO renders `default` (title `"Specification — v0"`, blank timestamp) instead of failing loudly as D1 intends. Also `artifactType: (… ) as 'spec'` casts an unknown wire type to the literal, defeating the type check. Tighten so a partial/unknown DTO trips `error`. [`queryOptions.ts` toArtifactView/composeArtifactTitle]
- [x] [Review][Patch] APPLIED — Short-form checksum echoes stored algorithm casing verbatim — `shortChecksum(artifact.checksumAlgorithm(), …)` does no normalization; tests/OpenAPI show both `SHA-256:` and `sha-256:`. Frontend prefix consumers can disagree across rows. Normalize via `ArtifactChecksum.canonicalAlgorithm(...)`. [`WorkflowInspectionService.java` shortChecksum]
- [x] [Review][Patch] APPLIED — `composeArtifactTitle` computes `const label = artifactTypeLabel(...)` but uses it only in the `default` branch; the three explicit cases hardcode their own strings, inviting drift if `artifactTypeLabel` changes. Route all cases through `label` or drop the unused computation. [`queryOptions.ts` composeArtifactTitle]
- [ ] [Review][Patch] NOT APPLIED (left as action item — `ArtifactPayloadStore` exposes no `delete`; a clean fix needs a new store-reset mechanism, and `write()` is idempotent-overwrite so repeated runs don't corrupt state — low priority) Contract-test `wipe()` truncates `workflow_runs … cascade` but never removes the payload-store bytes written by `seedArtifact` via the real `artifactPayloadStore.write(...)` — test-isolation hazard across repeated runs. [`WorkflowArtifactReadEndpointContractTest.java` wipe]
- [x] [Review][Defer] Read endpoint decodes the full payload as UTF-8 with no size bound and silent replacement chars for non-UTF-8 bytes [`WorkflowInspectionService.java` getArtifactDetail] — deferred, pre-existing pattern (payload store is unbounded project-wide; not unique to this change; bodies are redacted markdown).

**Dismissed (noise / verified safe / by-design):** `readBytes(null)` NPE→500 (false positive — `LocalArtifactStore.isReadable` null-guards → clean 503); frontend hook `.length` on `undefined` (unreachable — signature is `(string, string)`, route gates the link with `specArtifactId !== undefined`); one-arg query key ignores `workflowRunId` (by design, D2 — artifact public ids globally unique); `fetchArtifact` re-injects path-arg `artifactId` (by design — wire-null safety, D1); cross-run guard assumes `workflowRunId()` is the public id (verified — contract test + auditor confirm); Gate-1/AC1 code not in diff (by design — committed earlier); `503` response beyond AC's 400/404 enumeration (in-spirit, reuses existing `ARTIFACT_PAYLOAD_UNAVAILABLE`, captured in the OpenAPI snapshot).

## Dev Notes

### Pre-staged working-tree changes (Gate 1 — KEEP, do not revert)

Four files are already modified in the working tree and form AC1. Treat them as part of THIS story:

- `application/artifact/ArtifactOperationService.java` — `recordOperation` now captures and returns the payload-store-reported `storageRef` (was discarded).
- `application/artifact/RecordArtifactOperationResult.java` — new `storageRef` component + overloaded ctors (back-compatible; `null` on replay/no-payload).
- `application/runner/RunnerBroker.java` — new `markSpecArtifactAvailable(opResult, payloadBytes, correlationId)` called only when `artifactType == ArtifactType.SPEC`; computes a `SHA-256` checksum over the same ingested bytes and calls `ArtifactOperationService.markAvailable(publicId, checksum, storageRef, SYSTEM actor)`. No-ops when `storageRef == null` (idempotent replay).
- `application/workflow/SpecStageOrchestrationIT.java` — new `specArtifactIsMarkedAvailableAndApprovableAfterPoll` IT + updated comments on the existing assertion.

> Verify these compile and the IT passes; then finalize (spotless/checkstyle). They are correct as drafted — your work is to confirm + test, not rewrite.

### D1 — CRITICAL contract trap: the live `useArtifact` response MUST satisfy `isArtifactView`

When `useArtifact` goes live, its `data` flows through `isArtifactView(rawArtifact)` in `ArtifactReviewPanel.tsx:250`. If the guard returns `false`, `hasInvalidArtifact=true` → the panel renders **`error`**, not `default`. The guard (`artifactView.ts:81–126`) **requires the body to have**:

```
artifactId: string   title: string   version: number (finite)
classification: string   body: string   createdAt: string
```

The backend endpoint AC (AC3) lists `artifactType, version, status, classification, createdAt, checksum, body` — it does **not** mandate `title`, and `title` ("Specification — LIN-123 v3") is a display concern with no clean backend source.

> **DECIDED (Alex, 2026-06-14): compose `title` in the FRONTEND adapter — do NOT add `title` to the backend DTO.** The frontend `fetchArtifact` adapter maps the raw backend DTO into the `ArtifactView` shape: inject `artifactId` (it's the query arg), compose `title` from `artifactType`+`version` (e.g. `` `Specification — v${version}` `` for spec), pass through `body`/`classification`/`createdAt`/`checksum`. The backend DTO stays minimal and matches the epic field list (no Linear-ref coupling in the read endpoint).

**Pin AC7 with a panel `default`-state test on the exact live (post-adapter) shape** so a future DTO/adapter drift that drops `artifactId`/`title` fails loudly instead of silently rendering `error`.

### D2 — `useArtifact` signature must gain `workflowRunId`

The endpoint path is `/api/v1/workflows/{workflowRunId}/artifacts/{artifactId}` — it needs **both** path params, but `useArtifact(artifactId)` takes only one today. Change to `useArtifact(workflowRunId, artifactId)` and update the single call site (`ArtifactReviewPanel.tsx:243`, container already has `workflowRunId`). Keep the query **key** `workflowKeys.artifact(artifactId)` (one arg — artifact public ids are globally unique). Note: the `$artifactId.tsx` route loader comment references a two-arg `workflowKeys.artifact(runId, artifactId)` — that comment is aspirational drift; the real factory is one-arg. Do not change the key factory.

### D3 — No new `DomainErrorCode` (avoids the three-sites fan-out)

`RUN_NOT_FOUND`, `ARTIFACT_RECORD_NOT_FOUND`, and `INVALID_COMMAND_PAYLOAD` ALL already exist in `DomainErrorCode` and are already mapped in `ProblemDetailsCatalog` AND present in `registry-api-schema-placeholders.json`. **Do NOT add a new code** — the `[[new-domainerrorcode-three-sites]]` fan-out does not apply here. Reuse the existing ones.

### Backend — exact anchors

| What | File | Line | Action |
|---|---|---|---|
| `LatestArtifactView` record | `application/workflow/WorkflowInspectionService.java` | 1219 | add `String artifactId` (4th field) |
| `LatestArtifactView` construction | `application/workflow/WorkflowInspectionService.java` | ~537–549 | pass `snapshot.publicId()` |
| REST `LatestArtifact` + `from()` | `adapters/rest/WorkflowDetailResponse.java` | 74–81 | add `artifactId` + map it |
| Read controller (existing) | `adapters/rest/WorkflowController.java` | 71 (class), 125–155 (detail pattern) | add `@GetMapping("/{workflowRunId}/artifacts/{artifactId}")` |
| Artifact lookup by id | `application/artifact/spi/ArtifactRecordPort.java` | 13 | `findByPublicId(artifactId)` |
| Payload bytes read | `application/artifact/spi/ArtifactPayloadStore.java` | 21 | `readBytes(storageRef)` → `Optional<byte[]>` |
| Record snapshot fields | `application/artifact/ArtifactRecordSnapshot.java` | 9–24 | `publicId, workflowRunId, artifactType, version, classification, storageRef, checksumAlgorithm/Value, status, createdAt` |
| Classification enum | `domain/registry/DataClassification.java` | 5–31 | `LOCAL_ONLY` / `SHAREABLE_REDACTED` / … (guard out `LOCAL_ONLY`) |
| Error codes (exist) | `domain/registry/DomainErrorCode.java` | 31/41/47 | `ARTIFACT_RECORD_NOT_FOUND`, `INVALID_COMMAND_PAYLOAD`, `RUN_NOT_FOUND` |
| ProblemDetails (exist) | `adapters/rest/ProblemDetailsCatalog.java` | 155–160, 251–256 | already registered |
| OpenAPI snapshot | `deliveryline-backend/src/main/resources/openapi/openapi.json` | — | regenerate (drift test below) |
| Snapshot test | `adapters/rest/OpenApiSnapshotContractTest.java` | 51+ | boots app, byte-compares `/v3/api-docs`; regen with `-Dopenapi.snapshot.write=true` |
| ArchUnit controller rule | `architecture/ArchitectureRuleCatalog.java` | 251–275 | `*Controller` → `@RestController` in `adapters.rest` (already satisfied — endpoint goes on existing `WorkflowController`) |
| Read-endpoint contract test to mirror | `adapters/rest/WorkflowReadEndpointsContractTest.java` | 58–62, 174–226 | template for the new endpoint test |

- **Body encoding:** return `body` as a **UTF-8 string** of the redacted markdown (`new String(bytes, UTF_8)`), NOT base64 — the frontend `ArtifactView.body` is a markdown `string` rendered via `SafeMarkdownRenderer`.
- **Cross-run guard order:** resolve the run first (404 `RUN_NOT_FOUND` if absent), then the artifact (404 `ARTIFACT_RECORD_NOT_FOUND` if absent OR owned by a different run — never leak existence of another run's artifact).
- **`[[spa-fallback-lives-in-problemdetailsmapper]]`** — the controller-placement ArchUnit rule pins any `*Controller` to `adapters.rest` + `@RestController`; the existing `WorkflowController` already satisfies it, so no new rule and no `[[archunit-runs-in-failsafe-not-surefire]]` concern.
- **`[[workflow-read-endpoints-test-isolation-flake]]`** — the read-endpoints contract tier has a known order-dependent flake (events-vs-artifacts FK delete order); a local full-`verify` flake there is not a regression — verify the new test in isolation.

### Frontend — exact anchors

| What | File | Line | Action |
|---|---|---|---|
| `useArtifact` stub | `src/features/workflows/hooks/useArtifact.ts` | 19–29 | make live; gain `workflowRunId` param (D2) |
| query-options factory to mirror | `src/lib/api/queryOptions.ts` | 64–96 (`fetchWorkflowDetail`/`detailQueryOptions`) | add `fetchArtifact` + `artifactQueryOptions` (adapter maps DTO→`ArtifactView`, D1) |
| `apiClient` | `src/lib/api/client.ts` | 64–68 | `apiClient.GET(path, { params: { path: {...} } })` + `unwrap(...)` |
| query key (exists) | `src/lib/queryKeys/workflowKeys.ts` | 72–73 | `artifact: (artifactId) => [...all,'artifact',artifactId]` — reuse, do not change |
| ARP container call site | `src/features/workflows/components/ArtifactReviewPanel.tsx` | 243 | `useArtifact(workflowRunId, artifactId)` |
| `isArtifactView` guard (the contract) | `src/features/workflows/artifactView.ts` | 81–126 | response must satisfy it (D1) |
| approval-bar resolver (no change) | `src/features/workflows/approvalDecisionView.ts` | 419–437 | already reads `latestArtifacts[].artifactId` |
| hardcoded sample link | `src/routes/workflows/$workflowRunId/index.tsx` | 164–169 | replace `art_sample0001` with `resolveSpecArtifactId(data)` |
| generated schema | `src/lib/api/schema.d.ts` | 255–261 (`LatestArtifact`), 359 | regenerated after backend change |
| hook test to mirror | `src/features/workflows/hooks/useWorkflowDetail.test.tsx` | 24–40 | MSW `http.get` handler for the new path |
| approval fixtures | `src/test/fixtures/approval/approvalDecisionFixtures.ts` | 16–78 | `blockedNoArtifactView` / `readyView` |

- **Wire-null guard (`[[workflowdetail-wire-sends-null-not-undefined]]`):** generated TS says `artifactId?: string`, but the wire may serialize it as JSON `null`. Guard `typeof candidateId === 'string'` (the existing `resolveSpecArtifactId` already does this) and `!= null` before string ops anywhere you touch the new field.

### OpenAPI / schema regen (cross-shell chore)

Adding `artifactId` to `LatestArtifact` and the new operation are contract changes that **red `OpenApiSnapshotContractTest` + the 1.21 drift check until regenerated** (same blast pattern as `[[new-workfloweventtype-fixture-sites]]`). Steps:
1. Backend snapshot: re-run the app/`OpenApiSnapshotContractTest` with `-Dopenapi.snapshot.write=true` to rewrite `openapi.json` (works in WSL2).
2. Frontend types: `npm run generate-api` (runs `openapi-typescript ../deliveryline-backend/.../openapi.json --output src/lib/api/schema.d.ts`) — needs whichever shell owns the `node_modules/.bin` platform (`[[openapi-regen-platform-shim]]`).
3. Commit both regenerated files. Do NOT hand-edit `schema.d.ts`.

### Testing standards summary

- **Framework:** JUnit 5 + Spring Boot test; Testcontainers ITs named `*IT` (`[[springboot-testcontainers-test-must-be-IT]]`) run under Failsafe (`SpecStageOrchestrationIT` already conforms). Frontend: Vitest + Testing Library + MSW.
- **Run gates in PowerShell** (`[[rtk-hook-only-matches-bash]]`) — the RTK Bash hook corrupts Maven/npm invocations.
- **Verify on Linux/Docker before merge** (`[[verify-ci-fixes-in-clean-env]]`, `[[wsl-linux-ci-reproduction]]`) — local Windows green ≠ CI green, especially the Docker IT tier and the regenerated lockfile (`[[frontend-lockfile-cross-platform]]`).
- **Prettier gate cascades** (`[[prettier-gate-cascades-ci]]`) — run `prettier --write` before pushing.

### Logging Requirements (project-wide standard)

Every story leaves touched services observable enough to debug a production incident without re-deploying (enforced via the "Logging instrumentation" task above).

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface for this story):**
  - The new artifact-read service method → `INFO` on entry (`workflowRunId`, `artifactId`) + `INFO` on success / `WARN` on cross-run or classification reject / `WARN` on payload-unreadable / typed-domain rejection.
  - The Gate-1 `markSpecArtifactAvailable` → `INFO` "spec artifact promoted to available" with `artifactId` + `checksum-algorithm` (never the bytes); the existing `recordOperation success` log stays.
  - Read controller → `INFO` entry + success mirroring the detail endpoint.
- **Required context keys** (MDC via `MdcKeys` or structured params): `correlationId`, `workflowRunId`, `artifactId`, `actorIdentity`.
- **Forbidden in log output:** the artifact `body`, payload bytes, secrets/tokens, raw PII. The body is served redacted but still must never be logged.
- **Test contract:** new logging surfaces pinned by ≥1 focused test (list-appender / `OutputCaptureExtension`).

### Project Structure Notes

- Backend hexagonal boundaries hold: the read path depends only on application services (`WorkflowInspectionService`) + SPI ports (`ArtifactRecordPort`, `ArtifactPayloadStore`) + domain types. The controller stays in `adapters.rest`. `application/...` must not import `org.dradgo.adapters..` (`[[application-cannot-import-adapters]]`).
- No new bean wiring, no new `@ConfigurationProperties`, no Flyway, no profile gating — all reads run on the existing wiring.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3a-9] — full AC-shape, dependencies, scope discipline, risk.
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml#3a-9] — correct-course 2026-06-14 rationale + the three-stacked-gates summary.
- Dependencies: 3a-1 (spec auto-dispatch produces the artifact), 2.8 (spec artifact model + payload store), 2.13 (`approve-spec`/`reject-spec` + `ARTIFACT_PAYLOAD_UNAVAILABLE`), 2.14 (allowed-actions version stamp), 6.9 (`WorkflowController` reads + `WorkflowInspectionService` + `LatestArtifactView`), 2.17 (Artifact Review Panel + dormant `useArtifact`), 2.19 (Approval Bar + T-ARTIFACTID), 2.6 (`workflowKeys.artifact` reserved), 1.8 (Problem Details), 1.10/2.24 (redaction at write time), 1.21 (OpenAPI drift check).
- Memory: `[[epic-2-retro-real-run-gate]]`, `[[markavailable-has-no-production-caller]]`, `[[new-domainerrorcode-three-sites]]`, `[[workflowdetail-wire-sends-null-not-undefined]]`, `[[openapi-regen-platform-shim]]`, `[[new-workfloweventtype-fixture-sites]]`, `[[spa-fallback-lives-in-problemdetailsmapper]]`, `[[workflow-read-endpoints-test-isolation-flake]]`, `[[springboot-testcontainers-test-must-be-IT]]`, `[[rtk-hook-only-matches-bash]]`, `[[verify-ci-fixes-in-clean-env]]`, `[[wsl-linux-ci-reproduction]]`, `[[frontend-lockfile-cross-platform]]`, `[[prettier-gate-cascades-ci]]`, `[[application-cannot-import-adapters]]`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Backend Surefire fast tier: 904 tests, 0 failures, 11 skipped (PowerShell — RTK Bash hook avoided).
- New `WorkflowInspectionServiceArtifactDetailTest`: 8/8 green (happy + each reject branch + log assertions).
- `WorkflowInspectionServiceTest` Gate-2 assertion (`latestArtifacts[].artifactId`): green.
- OpenAPI snapshot regenerated (`-Dopenapi.snapshot.write=true`) + re-verified byte-identical via `OpenApiSnapshotContractTest`.
- New `WorkflowArtifactReadEndpointContractTest` (Docker/Failsafe contract tier): 7/7 green (200 redacted body, cross-run 404, local-only 404, missing run 404, missing artifact 404, malformed 400, adversarial no-secret body).
- Gate-1 `SpecStageOrchestrationIT`: 4/4 green (spec marked `available` + `approveSpec` advances `WaitingForSpecApproval → Executing`).
- `WorkflowReadEndpointsContractTest`: 8/8 green (no regression from the `+artifactId` field).
- Foundation-gate aggregator (`-Pfoundation-gate`): 31 tests, 0 failures (registries/transition/ProblemDetails/ArchUnit unaffected).
- Frontend: full vitest 878/878, `tsc -b` clean, eslint `--max-warnings=0` clean, prettier clean (incl. generated `schema.d.ts`), `check:api` in sync.
- Maven gotcha: invoking `surefire:test`/`failsafe:integration-test` goals directly crashes the fork (`@{argLine}`/`{argLine}`) because jacoco `prepare-agent` doesn't run; use the `test`/`integration-test` lifecycle phase (with `-Djacoco.skip=true` to null the argLine) instead.

### Completion Notes List

Implemented across the three stacked gates (spec read+approve slice only):

- **Gate 1 (AC1) — already committed in the working tree, verified + tested.** `RunnerBroker.markSpecArtifactAvailable` (scoped to `ArtifactType.SPEC`, SHA-256 over ingested bytes, no-op on `storageRef==null` replay), `ArtifactOperationService.recordOperation` returning the store `storageRef`, and the `RecordArtifactOperationResult` storageRef ctor were present and committed (the story's "pre-staged in working tree" note was stale — they had since been committed; not reverted). `SpecStageOrchestrationIT` confirms the spec is `available` and approvable. Supersedes `[[markavailable-has-no-production-caller]]` for the spec stage.
- **Gate 2 (AC2) — `artifactId` on the read model.** Added `String artifactId` to `LatestArtifactView` (4th field; 3-arg convenience ctor keeps the CLI status/history fixtures — which don't surface the id — compiling) and passed `snapshot.publicId()` at the construction site; mapped it through `WorkflowDetailResponse.LatestArtifact.from`. OpenAPI snapshot + `schema.d.ts` regenerated. The approval bar (`resolveSpecArtifactId`, story 2.19 T-ARTIFACTID) now resolves live with zero bar-component changes.
- **Gate 3 (AC3/4/5/7) — artifact-read endpoint.** New `WorkflowInspectionService.getArtifactDetail(runId, artifactId)` (injected `ArtifactPayloadStore` — fanned the new ctor dep through all 7 `new WorkflowInspectionService(...)` test sites): prefix-validate both ids → resolve run (`RUN_NOT_FOUND`) → resolve artifact (`ARTIFACT_RECORD_NOT_FOUND`) → cross-run guard (reports `ARTIFACT_RECORD_NOT_FOUND`, never leaks) → classification guard (`LOCAL_ONLY` → `ARTIFACT_RECORD_NOT_FOUND`) → read bytes (`ARTIFACT_PAYLOAD_UNAVAILABLE`) → return UTF-8 body + short-form checksum. New `ArtifactDetailResponse` DTO + `@GetMapping("/{workflowRunId}/artifacts/{artifactId}")` on the EXISTING `WorkflowController` (no new controller → no new ArchUnit rule). No new `DomainErrorCode` (all three reused; D3).
- **Frontend (AC6/AC7) — `useArtifact` live + sample link swap.** Added `fetchArtifact` + `artifactQueryOptions` with a `toArtifactView` adapter that INJECTS `artifactId` from the query arg (wire-null-safe — `[[workflowdetail-wire-sends-null-not-undefined]]`) and COMPOSES `title` (D1 — `title` deliberately not on the backend DTO, decided by Alex 2026-06-14). `useArtifact(workflowRunId, artifactId)` flipped from disabled stub to live (`enabled` gated on both ids). ARP container call site updated (one line). Detail route replaces the hardcoded `art_sample0001` link with `resolveSpecArtifactId(data)`, hidden when no spec artifact exists. AC7/D1 pinned by a panel `default`-state test on the exact post-adapter shape + the `useArtifact` MSW live test.
- **Scope discipline held:** spec stage only; `implementationPlan`/`prOutput` keep their `pending`-on-ingest posture (their human gate arrives with 3.20/3.23/3.26). No Flyway, no new event type, no new `@ConfigurationProperties`. The only contract changes are `+artifactId` on `LatestArtifact` and the new `GET .../artifacts/{artifactId}` operation, both reflected in the regenerated OpenAPI snapshot + `schema.d.ts`.

### File List

**Backend — production:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — `+artifactId` on `LatestArtifactView` (+ 3-arg convenience ctor) and its construction; new `getArtifactDetail` + `ArtifactDetailView` record + `shortChecksum`/`artifactRecordNotFound`/`artifactPayloadUnavailable` helpers; injected `ArtifactPayloadStore`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowDetailResponse.java` — `+artifactId` on nested `LatestArtifact` record + mapper.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ArtifactDetailResponse.java` — NEW REST DTO for the artifact-read endpoint.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java` — new `getArtifact` `@GetMapping` on the existing controller.
- `deliveryline-backend/src/main/resources/openapi/openapi.json` — regenerated snapshot (+`artifactId`, +`ArtifactDetail`, +`getArtifact`).
- (Gate 1, already committed — verified, not modified this session: `application/runner/RunnerBroker.java`, `application/artifact/ArtifactOperationService.java`, `application/artifact/RecordArtifactOperationResult.java`.)

**Backend — tests:**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceArtifactDetailTest.java` — NEW (Gate-3 service unit tests + log assertions).
- `deliveryline-backend/src/test/java/org/dradgo/adapters/rest/WorkflowArtifactReadEndpointContractTest.java` — NEW (Gate-3 endpoint contract test).
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceTest.java` — Gate-2 `artifactId` assertion + `payloadStore` ctor arg.
- `WorkflowInspectionServiceSpecTest.java`, `WorkflowInspectionServiceClarificationTest.java`, `WorkflowInspectionServiceClarificationStatusTest.java`, `WorkflowInspectionServiceAllowedActionsTest.java`, `WorkflowInspectionServiceAllowedActionsLoggingTest.java`, `WorkflowInspectionServiceRunnerLogReferenceTest.java` — `+ArtifactPayloadStore` mock ctor arg.

**Frontend:**
- `deliveryline-frontend/src/lib/api/schema.d.ts` — regenerated (`+artifactId`, `+ArtifactDetail`, `+getArtifact`).
- `deliveryline-frontend/src/lib/api/queryOptions.ts` — `fetchArtifact` + `artifactQueryOptions` + `toArtifactView` adapter + `composeArtifactTitle` + `ArtifactDetail` type + `STALE_TIME.artifact`.
- `deliveryline-frontend/src/features/workflows/hooks/useArtifact.ts` — disabled stub → live (`useArtifact(workflowRunId, artifactId)`).
- `deliveryline-frontend/src/features/workflows/hooks/useArtifact.test.tsx` — rewritten for the live hook (MSW happy/null-id/404/disabled + key reservation).
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.tsx` — container call site `useArtifact(workflowRunId, artifactId)`.
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx` — AC7/D1 `default`-state test on the live post-adapter shape; stale test title corrected.
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` — hardcoded `art_sample0001` link → `resolveSpecArtifactId(data)` (hidden when absent).

**Sprint tracking:**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `3a-9` `ready-for-dev → in-progress → review`.

### Change Log

- 2026-06-14 — Story 3a-9 implemented (Gates 1–3): spec artifact `available` on ingest (verified), `artifactId` exposed on `WorkflowDetail.latestArtifacts[]`, new `GET /api/v1/workflows/{workflowRunId}/artifacts/{artifactId}` redacted-content endpoint, and live frontend `useArtifact` + real spec link. Status `ready-for-dev → review`.
