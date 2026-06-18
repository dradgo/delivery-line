# Story 3b.5: `prOutput` Review Renderer — PR Link + State Badge + Unified Diff

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer reviewer,
I want a dedicated `prOutput` review panel that renders the **real** PR link (`PrStateBadge` + `githubRef`) and the **real** unified diff (`SafeUnifiedDiffRenderer` / `parseUnifiedDiff`),
so that a live `prOutput` renders as a reviewable PR + diff at `WaitingForReview` (not an empty shell), and accept / reject / takeover fire end-to-end against the actual PR.

## Context & the ONE non-obvious finding that re-shapes this story (read first)

This is sub-project **#3, Story B** of "Option X" (the full `WaitingForReview` review experience). #3-Story-A (3b-4, done) wired the developer role so the decision bar's actions appear and fire. 3b-5 makes the artifact the reviewer is looking at **render as a real PR + diff** instead of an empty panel.

### The epic's framing is OUTDATED — verify this before you start

The epic stub says: *"the done 3.27 `PrOutputArtifactRenderer` exists but is **unreachable** for a live `prOutput` (the raw JSON body fails `isArtifactView` → renders `error`); just wire it to the live path, do not re-build it."*

**That is not what the live code does.** Traced in source (2026-06-17):

- `toArtifactView` (`deliveryline-frontend/src/lib/api/queryOptions.ts:139-167`) **already** has a `prOutput` arm. It builds a valid `PrOutputArtifactView`, so `isArtifactView` **passes** and the renderer **dispatches** (`ArtifactReviewPanel.tsx:88-93`). A live `prOutput` does **not** render `error`.
- It renders **EMPTY**, because that arm hard-codes `branch:'', commitSha:'', diff:'', prLinkage:null` (the comment even says "no live source — fixture-driven; map empty defaults"). The renderer degrades gracefully → "No diff content was produced." + "No linked pull request".

So the renderer + the dispatch + the guard are **already correct and reachable**. The real gap is **data**: the structured prOutput fields are not on the live wire.

### What IS on the live wire vs. what is NOT (the crux — confirmed in backend source)

The live artifact-read endpoint (`GET /api/v1/workflows/{runId}/artifacts/{artifactId}`, story 3a-9) returns `ArtifactDetail.body` = the **UTF-8 of the stored prOutput JSON**: `{ artifactId, artifactType, branch, commitSha, prReference, diffReference }` (post-3.12-enrich, `branch`/`commitSha`/`prReference` are the **real pushed values**).

| Field | On the live wire today? | Source |
|---|---|---|
| `branch`, `commitSha`, `prReference` | ✅ Yes — inside `body` JSON | runner JSON, overwritten real by `enrichPrOutputArtifact` |
| **unified diff content** | ❌ **No** | `diffReference` is an **unresolved scratch pointer** (`diffs/run_.../pr-1.diff`); `RunnerBroker.java:~1704` — *"A canonical diff blob is never resolved and stored."* |
| **PR `prState`** | ❌ **No** | lives only in `integration_links.external_metadata` (`github_pr` row); not on `ArtifactDetail` and not on any live `WorkflowDetail`/`WorkflowSummary` DTO ([[story-3-31-pr-linkage-display-reconciliations]] — dormant since 3.31) |

**Decision (Alex, 2026-06-17): full read-model story.** Resolve the diff backend-side AND join the `github_pr` link's `prState` into the `ArtifactDetail` wire DTO (contract change + `openapi.json`/`schema.d.ts` regen), then the frontend renders the real PR + badge + diff. This is a **full-stack** story (backend ingest + backend read + contract regen + frontend mapper) — **not** the frontend-only wiring the epic stub imagined. Do **not** re-build the renderer (3.27) or the `githubRef`/`PrStateBadge`/`SafeUnifiedDiffRenderer` primitives (3.31) — they are correct; this story **feeds them real data**.

### ⚠️ THE TRAP: the diff must be resolved at INGEST, at BOTH artifact-write sites (not at read time)

The runner scratch directory is **ephemeral** (cleaned after the run), so `diffReference` can only be read while the run is live. You must resolve `diffReference` → diff bytes **during ingest in `RunnerBroker`** and embed it into the **persisted** prOutput payload JSON (read-time has no scratch to read).

And — exactly like 3b-3's availability trap ([[story-3b-3-availability-reconciliations]]) — a `prOutput` is written **twice**:
1. the **in-loop ingest** write (the v1 payload, the only write for a no-push prOutput and for **every mock-runner IT**), and
2. `enrichPrOutputArtifact` (`RunnerBroker.java:~1979-2060`), which `createNextVersion`s a **v2** payload from the real push outcome.

`resolveImplementationArtifact` picks the **highest version** → the reviewer reads **v2** when enrich ran. So the resolved diff must be embedded in the payload at **both** sites, or the enriched v2 the reviewer actually reads loses the diff. Mock runners yield no `RepositoryPushOutcome` → enrich is **skipped** in ITs → the in-loop write must carry the diff for IT coverage; cover the v2/enriched-head diff path in `RunnerBrokerUnitTest` (stub a present push outcome).

### Scope guardrails (do NOT do here)

- Do **not** re-build the `PrOutputArtifactRenderer` (3.27), `githubRef.ts`/`PrStateBadge` (3.31), or `SafeUnifiedDiffRenderer`/`parseUnifiedDiff` (3.31) — consume them **unforked**. Keep the `[[githubref-branchurl-dot-traversal]]` `..`-segment URL guard.
- Do **not** build `implementationPlan` step rendering / plan-phase decisions — that is **3b-6**.
- Do **not** add a `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema change, config property, REST endpoint, ArchUnit rule, or transition-table edge. The read endpoint's error codes already exist ([[artifact-read-dto-must-satisfy-isartifactview]]); the new DTO fields are **additive nullable**.
- Do **not** change the decision-bar role wiring (3b-4) or the kept-alive success/announcement behavior (`WorkflowDecisionBar.tsx:42-49`) — they are done; preserve them.
- Do **not** add a constructor dep to `WorkflowInspectionService` for integration links — it **already injects `IntegrationLinkService`** (`:79`). (A new ctor dep would fan out to ~7 `new WorkflowInspectionService(...)` test sites — avoid it.)

## ⚠️ BLOCKING open question to resolve in Task 0 (verify before writing ingest code)

**OQ-1 — Does the runner actually write the unified-diff FILE to the scratch path named by `diffReference`?** `diffReference` is a required string in `runner-result.v1.schema.json`, but the resolution (`scratchStore.tryReadArtifactContent(runnerExecutionId, diffReference)`) only works if the runner **wrote the file** to scratch under that relative path. Verify against `runners/*/runner.mjs` (the `pr-output` stage emitter) and the conformance fixtures (`RealRunnerContractIT`). **If the runner emits only the reference string without the file content, resolving the diff needs a runner change** — that likely belongs in the 3b-1 two-phase contract, not here. **Confirm this first;** if the file is absent, narrow this story to "PR link + state badge render real, diff shows a graceful 'diff unavailable' empty-state" and raise a runner follow-up. (Recommended assumption pending verification: the runner DOES write the diff file — `runner.mjs build --stage pr-output` emits the full `prOutput` shape including the diff artifact.)

## Acceptance Criteria

### Backend — resolve + persist the diff at ingest

1. **Resolve `diffReference` → diff bytes during ingest and embed it in the persisted `prOutput` payload JSON.** In `RunnerBroker`'s `PR_OUTPUT` ingest branch, read the diff via the existing `scratchStore.tryReadArtifactContent(runnerExecutionId, diffReference)` (same API used for `contentReference`), and add a `diff` field (UTF-8 string) to the serialized prOutput payload JSON. Apply a **size cap** (see AC2). When the scratch read is empty/absent, omit `diff` (do not fail ingest; the read path degrades to an empty-diff panel). The resolved diff inherits the artifact's `SHAREABLE_REDACTED` classification — do **not** add a second redaction pass unless the diff can carry un-redacted secrets (verify; if so, route it through the existing `RedactionPolicyService` path the rest of the payload uses).

2. **Cap the stored diff.** Truncate the embedded diff to a bounded size before persisting (recommend ~`5000` lines to mirror the frontend `PR_DIFF_MAX_LINES`, plus an absolute byte ceiling), appending a single truncation marker line so a reviewer sees the cut was deliberate (no silent truncation — `WARN`-log the truncation with `workflowRunId`/`artifactId`/original-vs-stored size). The frontend caps again at render (`SafeUnifiedDiffRenderer maxLines`), so the backend cap is the storage guardrail.

3. **Embed the diff at BOTH write sites (the dual-write trap).** Both the in-loop ingest write **and** `enrichPrOutputArtifact`'s `createNextVersion` UPDATE persist the `diff` field, so the highest-version artifact `resolveImplementationArtifact` selects always carries the diff. In-loop covers no-push prOutput + all mock-runner ITs (enrich skipped); the enriched-head path is covered by a `RunnerBrokerUnitTest` case stubbing a present `RepositoryPushOutcome`. No change to `RepositoryPushOutcome` (it does not and need not carry the diff).

### Backend — expose structured prOutput fields on the artifact-read response

4. **Extend the read DTO with additive nullable fields.** Add `branch`, `commitSha`, `prReference`, `prState`, `diff` (all nullable `String`) to `ArtifactDetailView` (`WorkflowInspectionService`) and `ArtifactDetailResponse` (`adapters/rest`). They are populated **only for `prOutput`**; for `spec`/`implementationPlan` they are `null`. No new `DomainErrorCode`.

5. **Populate the fields in `getArtifactDetail` for `prOutput`.** When `artifact.artifactType() == PR_OUTPUT`: parse the stored payload JSON (the same UTF-8 `body` bytes already read) to extract `branch`/`commitSha`/`prReference`/`diff`; and fetch `prState` via `integrationLinkService.findActiveGitHubPrLink(workflowRunPublicId)` → parse the link's `external_metadata` JSON for `prState` (values: `draft`/`open`/`merged`/`closed`). `prReference` and `prState` are **co-present** — surface both from the link when present, else both `null` (no link → frontend renders "No linked pull request"). Malformed payload JSON must **not** 500 the read — fall back to null structured fields + the existing `body`. (Guard `getInt`/boolean RETURNING-style traps don't apply; this is a JSON parse + a read-only link lookup.)

6. **`body` for `prOutput` no longer leaks raw JSON to the markdown renderer.** Set the response `body` for a `prOutput` to an empty string (the structured fields are the source of truth; the diff travels in the typed `diff` field, not duplicated inside `body`). `spec`/`implementationPlan` `body` is unchanged. (Storage keeps the full JSON payload for checksum/replay fidelity — only the **response projection** changes.)

7. **Regenerate the contract.** Add the five nullable fields to the `ArtifactDetail` schema in `openapi.json` and regenerate `schema.d.ts`; `OpenApiSnapshotContractTest` is re-captured green. The additive-nullable change keeps existing `ArtifactDetail` consumers (3a-9 spec read) working unchanged. ([[openapi-regen-platform-shim]], [[maven-arglineation-goal-crash]].)

### Frontend — map the real fields and render the real PR + diff

8. **`toArtifactView` (`queryOptions.ts`) prOutput arm consumes the new wire fields.** Replace the empty-default hard-code with the live values: `branch`/`commitSha`/`diff` from the wire (`?? ''` when null, [[workflowdetail-wire-sends-null-not-undefined]] — nullable wire fields serialize as JSON `null`, guard `!= null`); build `prLinkage` from `prReference` + `prState` **only when both are present** (else `prLinkage: null`). Respect `exactOptionalPropertyTypes` (build the `prLinkage` object conditionally; never assign `undefined` to an optional). The `PrOutputArtifactView` type + `isArtifactView` guard + the renderer are **unchanged** (3.27 already shaped them) — this arm now feeds them real data.

9. **A live `prOutput` renders as a real PR + diff, not an empty shell.** On a live `WaitingForReview` run with a pushed PR: the panel shows the branch link, the short commit + copy-full-SHA, the `prReference` link + `PrStateBadge`, the last-sync affordance when present, and the unified diff via `SafeUnifiedDiffRenderer` (file accordion + pagination). When no `github_pr` link exists yet → "No linked pull request" + (if the diff resolved) the diff still renders. When the diff is absent → "No diff content was produced." (graceful, per OQ-1 fallback).

10. **Accept / reject / takeover fire and transition the run, preserving kept-alive behavior.** The decision bar (3b-4 wiring) stays mounted through the post-decision state flip; the success summary + announcement + takeover preserved-PR affordance are unchanged. (Reminder: a `prOutput` **accept** needs the backend `github_pr` PR-link gate satisfied — present once 3b-1/3b-2 persist the link; test the full accept after #1/#2 or via a plan-phase artifact, and use reject/takeover which need no link.)

### Tests & verification

11. **Tests (failing-first).**
    - **Backend ingest** (`RunnerBrokerUnitTest`): an ingested `prOutput` whose `diffReference` resolves in scratch ends with a `diff` field in its persisted payload; the **enriched v2 head** (stub a present `RepositoryPushOutcome`) also carries the diff; a missing/empty scratch read omits `diff` without failing ingest; an oversize diff is truncated + `WARN`-logged.
    - **Backend read** (`WorkflowInspectionServiceArtifactDetailTest` or sibling): `getArtifactDetail` on a `prOutput` returns populated `branch`/`commitSha`/`prReference`/`diff` + `prState` from `findActiveGitHubPrLink`; no link → `prReference`/`prState` null; malformed payload JSON → null structured fields, no 500; `spec` read unchanged (all five null, body intact). Name any Testcontainers IT `*IT` ([[springboot-testcontainers-test-must-be-IT]]).
    - **Backend contract** (`OpenApiSnapshotContractTest`): re-captured snapshot byte-matches the springdoc-generated spec with the five new nullable `ArtifactDetail` fields.
    - **Frontend mapper** (`queryOptions` test): the prOutput arm maps live wire fields → a `PrOutputArtifactView` that passes `isArtifactView`; null prReference/prState → `prLinkage: null`; null branch/commit/diff → `''`.
    - **Frontend render** (reuse/extend `PrOutputArtifactRenderer.test.tsx` patterns, or a panel/route test): a live-shaped `prOutput` (post-mapper) renders the PR link + `PrStateBadge` + parsed diff (not the empty-state); confirm accept/reject still fire (existing `ImplementationReviewDecisionBarContainer` tests stay green).
    - **No regression**: spec artifact-read + spec renderer + 3.27 fixture-driven renderer tests unchanged.

12. **Rebuild + re-embed + manual verify.** After the backend annotation/DTO change, regenerate generated files **before** the frontend `tsc`/tests ([[openapi-regen-platform-shim]]). `mvn package` re-embeds the SPA into backend `static/` ([[embedded-frontend-at-package-phase]]). On a live `WaitingForReview` run that produced a pushed PR, the panel shows the real PR link + state badge + diff and a decision transitions the run. (Live verify is a human-in-the-loop step — flag for the operator if not run headlessly; the automated mapper + render + contract pins stand in for the wiring proof.)

13. **No new production surface beyond the five nullable DTO fields + the regenerated generated files + the ingest diff-resolution.** No new `DomainErrorCode`, `WorkflowEventType`, Flyway, runner-contracts schema, config property, REST endpoint, ArchUnit rule, transition-table edge, or `WorkflowInspectionService` ctor dep.

## Tasks / Subtasks

- [x] **Task 0 — Resolve OQ-1 (does the runner write the diff file to scratch?)** (AC: gates 1/3)
  - [x] Traced `runners/{claude,codex}/lib/runner.mjs` `commandBuild`: the `pr-output` arm emits `diffReference: artifacts/{workflowRunId}/pr.diff` but — unlike the `spec` arm which `writeAtomically`s its `contentReference` — **never writes the `pr.diff` file**. So scratch resolution returns `Optional.empty()` in production. **OUTCOME: narrowed per the documented fallback** — PR link + state badge render real; the diff shows the graceful "No diff content was produced." empty-state until a runner follow-up writes the file. The ingest diff-resolution plumbing IS built (forward-compatible: AC1's absent-scratch branch handles today; the diff flows with no backend change once the runner writes the file). **Runner follow-up raised in `deferred-work.md`.**
- [x] **Task 1 — Backend: resolve + cap + embed the diff at ingest (both write sites)** (AC: #1, #2, #3)
  - [x] `RunnerBroker.onResult` loop: resolve `diffReference` ONCE via `scratchStore.tryReadArtifactContent` (`resolvePrOutputDiff`); cap to 5000 lines + 1 MB byte-ceiling with a truncation marker + `WARN`; embed `diff` into the in-loop v1 payload via the new 3-arg `artifactPayload(...)`. Empty/absent scratch → omit `diff`, `WARN` graceful-skip, continue.
  - [x] `enrichPrOutputArtifact`: the once-resolved diff is threaded through `validateAndEnrichPrOutput` and `put("diff", ...)` on the enriched v2 `ObjectNode` (no second scratch read) so the highest-version head carries it.
  - [x] Classification: the diff inherits the payload's `SHAREABLE_REDACTED` (stored raw alongside branch/commitSha like the rest of the payload; rendered through the redaction-aware `SafeUnifiedDiffRenderer` on the frontend — no second backend redaction pass, matching the existing payload fields).
- [x] **Task 2 — Backend: extend the read DTO + populate for prOutput** (AC: #4, #5, #6)
  - [x] Added nullable `branch`/`commitSha`/`prReference`/`prState`/`diff` to `ArtifactDetailView` + `ArtifactDetailResponse`.
  - [x] `getArtifactDetail`: for `PR_OUTPUT`, JSON-parse the payload bytes → branch/commitSha/diff; `prReference`+`prState` co-present from `integrationLinkService.findActiveGitHubPrLinkView(runId)` (NEW non-locking typed projection — see reconciliation below); `body=""`. Malformed JSON → null structured fields, no 500, `WARN`. Non-prOutput → all five null, body unchanged.
- [x] **Task 3 — Backend: regenerate the contract** (AC: #7)
  - [x] Five nullable `["string","null"]` fields added to `ArtifactDetail` in `openapi.json`; `schema.d.ts` regenerated (`npm run generate-api`); `OpenApiSnapshotContractTest` re-captured + **byte-match GREEN** (Docker IT); `npm run check:api` GREEN.
- [x] **Task 4 — Frontend: map the wire fields in `toArtifactView`** (AC: #8)
  - [x] Replaced the empty-default prOutput arm in `queryOptions.ts`: branch/commitSha/diff `?? ''`; `prLinkage` built only when both prReference+prState present (else `null`), respecting `exactOptionalPropertyTypes`. No change to `artifactView.ts`/`isArtifactView`/the renderer.
- [x] **Task 5 — Tests (failing-first)** (AC: #11)
  - [x] Backend ingest (`RunnerBrokerUnitTest`, +4): diff embedded in v1 + enriched v2 head; missing-scratch omits without failing ingest; oversize truncate + `WARN` + no diff-content in logs.
  - [x] Backend read (`WorkflowInspectionServiceArtifactDetailTest`, +5): populated prOutput fields + prState from link; no-link → null prReference/prState; link-without-state → no-linkage; malformed-JSON → null fields no-500; spec unchanged. Service unit (`IntegrationLinkServiceUnitTest`, +3): prState parse / null-state / no-link. Adapter IT (`IntegrationLinkGitHubPrIT`, +2): the non-locking typed projection resolves github_pr metadata + excludes superseded.
  - [x] Frontend mapper (3) + live-render (1) in `ArtifactReviewPanel.test.tsx`; full vitest 1041 GREEN (decision-bar tests stay green).
- [x] **Task 6 — Rebuild + re-embed + manual verify** (AC: #12)
  - [x] Generated files regenerated BEFORE frontend `tsc`/tests; `tsc -b` clean, `eslint` clean, `prettier --write`. `mvn package` SPA re-embed + the live `WaitingForReview` PR-render verify are **flagged for the operator** in `deferred-work.md` (human-in-the-loop; the automated mapper/render/contract pins stand in).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs: `INFO` "prOutput diff resolved" (`workflowRunId`/`artifactId`/`diffReference`/lineCount/byteSize); `WARN` "prOutput diff truncated" (original-vs-stored sizes) + "prOutput diffReference not found in scratch" (graceful skip); `WARN` "malformed prOutput payload" at read. Reused the `getArtifactDetail` boundary INFO lines.
  - [x] Parameterized logging only; `correlationId`/`workflowRunId`/`artifactId` carried (MDC where available); **diff content, payload bytes, secrets never logged** — sizes/counts/refs only (pinned by the oversize-test's "diff content must never be logged" assertion).
  - [x] Diff-truncation `WARN` + malformed-payload `WARN` pinned with `ListAppender` assertions.

## Dev Notes

### Why this is full-stack (and exactly which half is which)

The 3.27 renderer, the 3.31 primitives (`githubRef`/`PrStateBadge`/`SafeUnifiedDiffRenderer`/`parseUnifiedDiff`), the artifact-read endpoint (3a-9), the route → container → guard → dispatch path, and the decision-bar role wiring (3b-4) are **all done and correct**. The single thing missing is **real data reaching the renderer**: the live wire carries `branch`/`commitSha`/`prReference` (inside the body JSON) but **not the resolved diff** (only an ephemeral `diffReference`) and **not `prState`** (only in `integration_links`). This story resolves the diff at ingest, joins `prState` at read, surfaces all five as typed nullable wire fields, and points the frontend mapper at them.

| Concern | Where it already lives | This story |
|---|---|---|
| prOutput renderer (PR link + badge + diff accordion + copy-SHA + pagination + a11y) | `PrOutputArtifactRenderer.tsx` (3.27) | reuse unchanged — feed it real data |
| `githubRef` (`parsePrReference`/`prUrl`/`branchUrl`/`commitUrl`/`shortSha`/`isGitHubHttpsUrl` + `..`-guard), `PrStateBadge`, `SafeUnifiedDiffRenderer`/`parseUnifiedDiff` (caps 50/5000) | 3.31 / `lib/sanitization` | consume **unforked** |
| `PrOutputArtifactView` type + `isArtifactView` prOutput branch | `artifactView.ts:162-172,279-289` (3.27) | unchanged (already shaped) |
| route → `ArtifactReviewPanelContainer` → `useArtifact` → guard → variant dispatch | `artifacts/$artifactId.tsx`, `ArtifactReviewPanel.tsx:76-100,259-267` | unchanged |
| artifact-read endpoint + `ArtifactDetail` body (UTF-8) + error codes | `WorkflowController.getArtifact` (`:252`), `WorkflowInspectionService.getArtifactDetail` (`:1040-1134`) | **add nullable fields + populate for prOutput** |
| `integration_links` `github_pr` row + `prState` in `external_metadata` | `IntegrationLinkService.findActiveGitHubPrLink` (`:648-651`), `buildGitHubExternalMetadata` (`:812-830`) | **read it in getArtifactDetail** (ctor dep already present) |
| scratch read API (`tryReadArtifactContent`, containment-checked) | `RunnerScratchStore` (`application/runner/spi`) | resolve `diffReference` with it at ingest |
| prOutput payload write (in-loop + enrich v2) | `RunnerBroker` ingest loop + `enrichPrOutputArtifact` (`:1979-2060`) | **embed the `diff` field at both** |
| decision-bar kept-alive + dev-role wiring | `WorkflowDecisionBar.tsx:42-49`, `ImplementationReviewDecisionBarContainer.tsx` (3b-4) | unchanged |

### Design Decision DD1 — resolve the diff at ingest, embed at both write sites

Scratch is ephemeral, so read-time resolution is impossible. Resolve `diffReference` once during ingest and embed it in the persisted payload JSON. Because `prOutput` is written twice (in-loop v1 + enriched v2) and `resolveImplementationArtifact` reads the **highest version**, the diff must be in **both** payloads — the exact shape of the 3b-3 dual-mark trap ([[story-3b-3-availability-reconciliations]]). Mock runners skip enrich → ITs exercise only the in-loop write → put the diff there for IT coverage and unit-test the enriched-head path.

### Design Decision DD2 — typed nullable DTO fields, not frontend `JSON.parse(body)`

Alex chose the read-model extension over frontend body-parsing. Surfacing `branch`/`commitSha`/`prReference`/`prState`/`diff` as **typed nullable** `ArtifactDetail` fields keeps the frontend off an untyped body blob (the [[artifact-read-dto-must-satisfy-isartifactview]] contract warns against coupling to body shape) and lets `tsc`/`check:api` police drift. Set prOutput `body=""` so the diff isn't duplicated in the HTTP response and no raw JSON reaches a markdown renderer. Storage keeps the full JSON (checksum/replay fidelity); only the response **projection** changes.

### Design Decision DD3 — `prReference` + `prState` are co-present (no ArtifactView field-fanout)

Both come from the **same** `integration_links` `github_pr` row, so the read service surfaces them together or not at all. That lets the frontend keep `isValidPrLinkage`'s requirement that a present `prLinkage` carries a valid `prState` ([[artifactview-variant-field-fanout]]) — no need to make `prState` optional and fan out the guard/renderer. No link → both null → `prLinkage: null` → renderer shows "No linked pull request" (still shows the diff if it resolved). Edge case (link present but `prState` stripped from `external_metadata`): treat as no-linkage for rendering; minor, acceptable.

### Trust boundary (unchanged, still enforced)

`branch`/`commitSha`/`diff` are **runner-emitted (UNTRUSTED)**; their GitHub URLs are built from the **owner/repo parsed from the TRUSTED `prReference`** (carries the [[githubref-branchurl-dot-traversal]] `..`-segment guard). The diff is rendered through `SafeUnifiedDiffRenderer` (sanitized, XSS-inert, redaction-aware per 2.24). Do not relax any of this when wiring live data through.

### Exact touch points (verified against current source, 2026-06-17)

| File | Line(s) | Relevance |
|---|---|---|
| `application/runner/RunnerBroker.java` | `PR_OUTPUT` ingest branch (`onResult` loop ~`:1340`); `artifactPayload(...)` ~`:2128`; `enrichPrOutputArtifact` `:1979-2060`; field `scratchStore` `:97`; the "diff never resolved" note ~`:1704` | resolve+embed diff at both write sites |
| `application/runner/spi/RunnerScratchStore.java` | `tryReadArtifactContent(String,String)` `:39` | scratch read API for `diffReference` |
| `application/workflow/WorkflowInspectionService.java` | `getArtifactDetail` `:1040-1134` (`body` decode `:1109`, `ArtifactDetailView` build `:1110-1119`); `ArtifactDetailView` record `:1625-1633`; ctor `IntegrationLinkService` `:79` | add+populate the five fields; read the link |
| `application/integration/IntegrationLinkService.java` | `findActiveGitHubPrLink` `:648-651`; `buildGitHubExternalMetadata` (`prState` into metadata) `:812-830` | source of `prState` |
| `adapters/rest/ArtifactDetailResponse.java` | record `:20-34` | add five nullable fields |
| `src/main/resources/openapi/openapi.json` | `ArtifactDetail` schema block | add five nullable properties (regen) |
| `deliveryline-frontend/src/lib/api/schema.d.ts` | `ArtifactDetail` (`:365-393`) | regenerate |
| `deliveryline-frontend/src/lib/api/queryOptions.ts` | `toArtifactView` prOutput arm `:153-167` | map live fields (remove empty defaults) |
| `deliveryline-frontend/src/features/workflows/artifactView.ts` | `PrOutputArtifactView` `:162-172`; `isArtifactView` prOutput `:279-289`; `isValidPrLinkage` `:227-244`; `PrState` `:129` | unchanged (reference) |
| `deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.tsx` | `:82-105` (reads branch/commitSha/diff/prLinkage), diff `:337-457` | unchanged (reference) |
| `deliveryline-frontend/src/features/workflows/githubRef.ts` | `parsePrReference`/`prUrl`/`branchUrl`/`commitUrl`/`shortSha`/`isGitHubHttpsUrl` `:28-99` | consume unforked |
| `deliveryline-frontend/src/lib/sanitization/unifiedDiff.ts` | `parseUnifiedDiff` `:102-222`; `PR_DIFF_MAX_FILES=50` `:18`, `PR_DIFF_MAX_LINES=5000` `:20` | caps to mirror backend |
| test: `RunnerBrokerUnitTest.java` | prOutput ingest fixture ~`:2334` | diff-embed cases |
| test: `WorkflowInspectionServiceArtifactDetailTest.java` | `:83-97` | read-projection cases |
| test: `OpenApiSnapshotContractTest.java` | `:50-80` | re-capture snapshot |
| route: `routes/workflows/$workflowRunId/index.tsx` | `:125-128,184-197` | the 3b-3 impl-output link that targets this render (reference) |

### Architecture / boundaries

- Backend changes stay in `application` (`RunnerBroker`, `WorkflowInspectionService`) + the REST DTO; no `application → adapters` import ([[application-cannot-import-adapters]]); no new `*Controller` (`getArtifact` already exists).
- `WorkflowInspectionService` already injects `IntegrationLinkService` — **no new ctor dep** (avoids the ~7-site `new WorkflowInspectionService(...)` fanout that 3a-9's `ArtifactPayloadStore` dep caused).
- Frontend mapper change is in the `.ts` `queryOptions.ts`; no helper exported from a feature `.tsx` ([[frontend-react-refresh-no-fn-exports]]); respect `exactOptionalPropertyTypes`.
- The OpenAPI regen is the cross-shell, cross-platform ritual ([[openapi-regen-platform-shim]]); commit `openapi.json` + `schema.d.ts` together or the frontend drift gate fails. Validate runner-contracts freshness if touched ([[runner-contracts-schema-stale-in-m2]] — not expected here).

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying.

- **Framework:** SLF4J + Logback (backend); field-only structured `console.*` (frontend). No `System.out`, no `printStackTrace()`.
- **This story (backend):** `INFO` "resolved prOutput diff" (`workflowRunId`/`artifactId`/`diffReference`/lineCount/byteSize); `WARN` "prOutput diff truncated" (original-vs-stored size) and "prOutput diffReference not found in scratch" (graceful skip); `WARN` "malformed prOutput payload at read" (parse fallback). Reuse the existing `getArtifactDetail` boundary INFO lines for the read path.
- **Required context keys:** `correlationId`, `workflowRunId`, `artifactId` (+ `idempotencyKey` where the ingest path already carries it).
- **Forbidden in log output:** diff **content**, raw payload bytes, secrets/tokens, PII, classification-restricted fields — log sizes/counts/refs only.
- **Test contract:** pin the diff-truncation `WARN` + the malformed-payload `WARN` with a list-appender/`OutputCaptureExtension`.

### Testing standards

- Backend unit tier = Surefire (no Docker); Testcontainers ITs named `*IT` so Failsafe runs them and the Windows fast tier excludes them ([[springboot-testcontainers-test-must-be-IT]]). OpenAPI regen via the `failsafe:integration-test` write path ([[maven-arglineation-goal-crash]]).
- Regenerate generated files **before** running the frontend `tsc`/tests, or the mapper won't type-check against the new `ArtifactDetail` fields.
- Frontend: vitest + MSW; `prettier --write` before pushing ([[prettier-gate-cascades-ci]]); verify lockfile/CI shape on Linux ([[frontend-lockfile-cross-platform]], [[verify-ci-fixes-in-clean-env]]); drive the toolchain via PowerShell ([[rtk-hook-only-matches-bash]]).

### Project Structure Notes

- Net production change: diff resolution + dual-write embed in `RunnerBroker`; five nullable fields added+populated in `WorkflowInspectionService`/`ArtifactDetailResponse`; the two regenerated generated files; the `queryOptions.ts` mapper arm. No new module, package, Flyway, `DomainErrorCode`, `WorkflowEventType`, runner-contracts schema, config property, REST endpoint, ArchUnit rule, transition-table edge, or `WorkflowInspectionService` ctor dep.

### References

- [Source: docs/superpowers/specs/2026-06-16-waiting-for-review-ui-design.md] — authoritative design (sub-project #3); "The gaps" §2 (no prOutput renderer), "prOutput / plan rendering" §("diff source is the artifact payload / `diffReference`"), Story B breakdown.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3b-5] (`:959-975`) — AC-shape, dependencies, sequencing `((3b-1 → 3b-2) and 3b-3 parallel → 3b-4 → 3b-5/3b-6)`; the "reconcile 3.27, do not re-build" + "prOutput accept needs the github_pr link" notes.
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-16-waiting-for-review.md:278-311] — the 3b-5 stub (#3 B).
- [Source: _bmad-output/implementation-artifacts/3b-4-developer-role-wiring-at-waiting-for-review.md] — #3 Story A (dev-role wiring); 3b-5 renders the artifact whose decisions 3b-4 unblocked.
- [Source: _bmad-output/implementation-artifacts/3b-3-...-mark-available-on-ingest-...md] — #2b; the dual-write / enrich-v2 trap this story mirrors for the diff.
- [Source: deliveryline-backend/.../RunnerBroker.java:1340,1704,1979-2060,2128] — ingest loop, the "diff never resolved" note, `enrichPrOutputArtifact`, `artifactPayload`.
- [Source: deliveryline-backend/.../WorkflowInspectionService.java:1040-1134,1625-1633,79] — read service, `ArtifactDetailView`, the `IntegrationLinkService` ctor dep.
- [Source: deliveryline-backend/.../IntegrationLinkService.java:648-651,812-830] — `findActiveGitHubPrLink` + where `prState` lives in `external_metadata`.
- [Source: deliveryline-backend/.../adapters/rest/ArtifactDetailResponse.java:20-34] — the REST DTO to extend.
- [Source: deliveryline-frontend/src/lib/api/queryOptions.ts:139-167] — `toArtifactView` prOutput arm (the empty-default to replace).
- [Source: deliveryline-frontend/src/features/workflows/artifactView.ts:162-172,227-244,279-289] — `PrOutputArtifactView`, `isValidPrLinkage`, the prOutput guard branch (DD3 rationale).
- [Source: deliveryline-frontend/src/features/workflows/components/PrOutputArtifactRenderer.tsx] and [.../components/PrStateBadge.tsx] and [.../githubRef.ts] and [.../lib/sanitization/SafeUnifiedDiffRenderer.tsx,unifiedDiff.ts] — the 3.27/3.31 components consumed unforked.
- [Source: deliveryline-runner-contracts/src/main/resources/schemas/runner-result.v1.schema.json:203-240] — the `prOutput` runner shape (`branch`/`commitSha`/`prReference`/`diffReference`); OQ-1 source of truth.
- Memory: [[story-3b-3-availability-reconciliations]], [[artifactview-variant-field-fanout]], [[artifact-read-dto-must-satisfy-isartifactview]], [[story-3-31-pr-linkage-display-reconciliations]], [[githubref-branchurl-dot-traversal]], [[workflowdetail-wire-sends-null-not-undefined]], [[frontend-react-refresh-no-fn-exports]], [[openapi-regen-platform-shim]], [[maven-arglineation-goal-crash]], [[springboot-testcontainers-test-must-be-IT]], [[embedded-frontend-at-package-phase]], [[prettier-gate-cascades-ci]], [[verify-ci-fixes-in-clean-env]], [[frontend-lockfile-cross-platform]], [[rtk-hook-only-matches-bash]], [[application-cannot-import-adapters]], [[new-domainerrorcode-three-sites]].

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Backend Surefire (full, Spotless + Checkstyle enabled): **1046 pass, 0 fail, 12 skipped — BUILD SUCCESS**.
- Backend Failsafe (Docker IT): `OpenApiSnapshotContractTest` byte-match GREEN (proves the hand-edited `openapi.json` matches springdoc's annotation output); `IntegrationLinkGitHubPrIT` **7/7** (incl. the 2 new non-locking-projection cases).
- Frontend: `tsc -b` clean; `eslint` 0 warnings; `prettier --write` applied; full vitest **1041 pass / 94 files**; `npm run check:api` in-sync.

### Completion Notes List

- **OQ-1 (BLOCKING) resolved: the runner does NOT write the `pr.diff` file to scratch.** `runners/{claude,codex}/lib/runner.mjs` emit only the `diffReference` pointer (the `spec` stage writes its content file; the `pr-output` stage does not). Per the story's documented fallback, scope narrowed: **PR link + `PrStateBadge` render real; the diff renders the graceful empty-state until a runner follow-up writes the file.** The ingest diff-resolution plumbing IS built and **forward-compatible** — once the runner writes the file the diff flows end-to-end with zero backend change. Runner follow-up + the live-verify caveat recorded in `deferred-work.md`.
- **AC5 reconciliation (sourcing + locking):** `findActiveGitHubPrLink` returns an `IntegrationLink` with no `external_metadata`, so `prState` couldn't be read from it. Added a NEW **non-locking** typed projection `IntegrationLinkRecordPort.findActiveTicketSummaryByTypeAndWorkflowRun` (repo + adapter + service `findActiveGitHubPrLinkView`) that returns the `github_pr` row's `external_ref` + metadata bytes. `prReference` + `prState` are therefore both sourced **co-presently from the link** (DD3 invariant guaranteed at the wire), and the read path never takes the `PESSIMISTIC_WRITE` lock the enrich/sync writers hold (strict improvement over AC5's locking suggestion). No new `WorkflowInspectionService` ctor dep (a plain `ObjectMapper` instance field).
- **Dual-write trap honored:** the diff is resolved ONCE in the ingest loop and embedded at BOTH the in-loop v1 write (`artifactPayload(..,resolvedDiff)`) and the enriched v2 head (`enrichPrOutputArtifact`), so `resolveImplementationArtifact`'s highest-version pick always carries it. Storage cap = 5000 lines + 1 MB ceiling + truncation marker + `WARN`.
- **`body=""` for prOutput:** the response projection blanks the markdown body (structured fields are source-of-truth; the diff travels in the typed `diff` field). Storage keeps the full JSON for checksum/replay; only the response changes. spec/implementationPlan reads are byte-identical (all five fields null, body intact).
- Consumed the 3.27 renderer + 3.31 primitives (`githubRef`/`PrStateBadge`/`SafeUnifiedDiffRenderer`) UNFORKED; the `prReference` shorthand (`org/repo#n`) is what drives the real PR link (a full URL doesn't parse `githubRef.parsePrReference`).

### File List

**Backend — main**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` — resolve/cap/embed the prOutput diff at both write sites (`resolvePrOutputDiff`, 3-arg `artifactPayload`, threaded into `validateAndEnrichPrOutput`/`enrichPrOutputArtifact`).
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — 5 nullable fields on `ArtifactDetailView`; populate for prOutput (payload parse + co-present link prState; `body=""`); `ObjectMapper` instance field + `textOrNull` helper.
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java` — `findActiveGitHubPrLinkView` + `GitHubPrLinkView` record + `extractPrState`.
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/spi/IntegrationLinkRecordPort.java` — `findActiveTicketSummaryByTypeAndWorkflowRun` (non-locking typed projection).
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationLinkPersistenceAdapter.java` — projection impl.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/IntegrationLinkRepository.java` — `findActiveByTypeAndWorkflowRunPublicId` (non-locking JPQL).
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ArtifactDetailResponse.java` — 5 nullable `@Schema` fields + `from` mapping.
- `deliveryline-backend/src/main/resources/openapi/openapi.json` — regenerated `ArtifactDetail` (5 nullable fields).

**Backend — test**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` — +4 ingest tests (v1 embed, enriched v2, absent-scratch omit, oversize truncate+WARN+no-content-leak).
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceArtifactDetailTest.java` — +5 read tests + `prOutputSnapshot` helper.
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLinkServiceUnitTest.java` — +3 `findActiveGitHubPrLinkView` tests.
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/IntegrationLinkGitHubPrIT.java` — +2 non-locking-projection IT cases.

**Frontend**
- `deliveryline-frontend/src/lib/api/queryOptions.ts` — prOutput mapper arm reads live fields; `PrLinkage`/`PrState` imports.
- `deliveryline-frontend/src/lib/api/schema.d.ts` — regenerated (ArtifactDetail 5 fields).
- `deliveryline-frontend/src/features/workflows/components/ArtifactReviewPanel.test.tsx` — +3 mapper tests + 1 live-render test + `isArtifactView` import.

**Docs**
- `_bmad-output/implementation-artifacts/deferred-work.md` — OQ-1 runner follow-up + AC5 reconciliation + live-verify caveat.

### Change Log

- 2026-06-18 — Story 3b-5 implemented (`ready-for-dev → in-progress → review`). Full-stack `prOutput` read model: ingest resolves+caps+dual-embeds the unified diff (forward-compatible; OQ-1 found the runner does not yet write the diff file → narrowed to PR-link+badge live, diff graceful empty-state + runner follow-up); artifact-read DTO gains 5 nullable structured fields (`branch`/`commitSha`/`prReference`/`prState`/`diff`) with prReference+prState co-present from a new non-locking github_pr projection; contract regenerated (snapshot byte-match green); frontend mapper feeds the unforked 3.27 renderer + 3.31 primitives real data. Backend 1046 unit + targeted ITs green; frontend 1041 vitest green.

### Review Findings

_Code review 2026-06-18 — 3 adversarial layers (Blind Hunter on full diff + Edge Case Hunter + Acceptance Auditor) over the uncommitted working-tree diff (17 files, +899/−22). No Blocker/High findings — implementation is faithful to the narrowed (OQ-1) scope. 0 decision-needed, 3 patch (all FIXED), 3 deferred, 3 dismissed as noise. Post-fix verification: backend RunnerBrokerUnitTest 52/0 + WorkflowInspectionServiceArtifactDetailTest 15/0; frontend tsc clean + 61/61 affected vitest + prettier/eslint clean. `review → done`._

- [x] [Review][Patch] FIXED — `prState` is never validated against its enum → an out-of-range value reds the WHOLE panel instead of degrading [deliveryline-frontend/src/lib/api/queryOptions.ts (toArtifactView prOutput arm)] — The mapper built `prLinkage` whenever `prReference != null && prState != null` and cast `prState as PrState` without a membership check; `isArtifactView`→`isValidPrLinkage` then rejected the entire view for an out-of-enum state → `error` panel instead of "No linked pull request". **Fix applied:** added exported `isPrState` type-guard to `artifactView.ts` (and reused it inside `isValidPrLinkage`), and the mapper now narrows with `isPrState(prState)` before building the linkage (removed the unsafe `as PrState` cast) → an unexpected state degrades to `prLinkage: null`. (blind+edge+auditor)
- [x] [Review][Patch] FIXED — Backend read test stubbed `prReference` as a full URL that production never emits [deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowInspectionServiceArtifactDetailTest.java] — production's `github_pr` `external_ref` is the canonical `org/repo#n` form (`GitHubRealAdapter.java:590`). **Fix applied:** replaced all 3 `"https://github.com/acme/app/pull/42"` fixtures with `"acme/app#42"` so the test pins the real wire shape. (blind+auditor)
- [x] [Review][Patch] FIXED — Empty/whitespace-only scratch diff embedded `"diff":""` into the persisted payload, diverging stored bytes/checksum from the absent-scratch path [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java (resolvePrOutputDiff)] — **Fix applied:** added a `diff.isBlank()` early-return (`Optional.empty()` + graceful WARN) after decoding the scratch bytes, so a present-but-blank diff embeds no `diff` field and persists identical payload bytes to the absent-scratch path. (edge)
- [x] [Review][Defer] Combined line+byte cap ordering in `resolvePrOutputDiff` — soft byte ceiling (final = MAX_BYTES + marker) + untested combined-overflow path [deliveryline-backend/.../RunnerBroker.java] — deferred to deferred-work.md, low new-code hardening (only one marker survives; not corrupting). (blind)
- [x] [Review][Defer] `prState` fidelity — the real GitHub adapter emits only `open`/`closed`, so `merged`/`draft` badges are unreachable (merged renders as closed) [deliveryline-backend/.../GitHubRealAdapter.java:587] — deferred, pre-existing (3.15), surfaced not regressed by 3b-5. (auditor)
- [x] [Review][Defer] Truncation/cap log cosmetics — logged `lineCount` can overstate stored content when only the byte cap fires; backend caps total lines while frontend caps changed lines [deliveryline-backend/.../RunnerBroker.java] — deferred, observability/comment-accuracy only. (blind+edge)
