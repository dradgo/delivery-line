# Story 3.15: IntegrationLinkService Extended for GitHub PR Linkage

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + reviewer reconstructing run lineage,
I want `IntegrationLinkService` extended with `linkGitHubPr(...)` writing an NFR17-durable `integration_links` row of type `github_pr` (PR ref + repo + branch + commit + PR state) — plus `syncGitHubPr(...)` to refresh PR state, cross-run conflict detection (`INTEGRATION_LINK_CONFLICT`), repo-compatibility enforcement (`LINEAR_GITHUB_REPO_MISMATCH`), an artifact↔link drift guard (`ARTIFACT_PR_LINK_MISMATCH`), and supersede-on-retry-with-a-new-PR semantics,
so that a reviewer can reconstruct the full ticket↔repository↔branch/commit↔artifacts↔PR↔run chain for any governed run **without re-querying GitHub** (NFR17), conflicts cannot silently overwrite (NFR19), and the dormant `// TODO(story 3.15)` linkage seam story 3.12 left in `RunnerBroker.validateAndEnrichPrOutput` becomes a live, tested write.

## Context & Central Reconciliation (READ FIRST)

**This story closes the linkage gap story 3.12 explicitly deferred.** Read `3-12-pr-output-artifact-generation-flow-orchestration.md` (Scope Boundary table rows AC4/AC6, Decision D4) and `3-14-real-github-adapter-pr-branch-commit-refs-and-pat-auth.md` first. The PR is **already physically created/updated** by `RepositoryWorkspaceService.captureAndPush` (story 3.9, via `GitHubAdapter.createPullRequest`/`updatePullRequest`); 3.15 builds **only the durable `integration_links` `github_pr` row + the conflict/compat/drift guards + the sync refresher**, and wires them into the seam 3.12 marked.

**STRUCTURAL FACTS (each verified against current code):**

1. **`IntegrationLinkService` exists but is Linear-only.** `application.integration.IntegrationLinkService` exposes `linkTicket` / `linkTicketWithinTransaction` (both Linear), `findActiveLink`, `findActiveLinkByWorkflowRun`, `markSynced`, `markStale`, `markFailed` (`IntegrationLinkService.java:107,248,323,333,344,354`). There is **NO** `linkGitHubPr` and **NO** `syncGitHubPr`. The constant `LINEAR_INTEGRATION_TYPE = "linear"` is the only type wired; `"github_pr"` is a string the DB CHECK already permits but no code writes it.

2. **The `insert` SPI is type-agnostic and reusable as-is.** `IntegrationLinkRecordPort.insert(NewIntegrationLink{publicId, workflowRunPublicId, integrationType, externalRef, externalMetadata(bytes), createdAt, lastSyncAt})` (`IntegrationLinkRecordPort.java:118-125`) takes `integrationType` + opaque redacted JSON bytes — pass `"github_pr"` and the GitHub metadata. The persistence adapter (`IntegrationLinkPersistenceAdapter.java:84-150`) translates unique-index collisions to `INTEGRATION_LINK_CONFLICT` for **any** type.

3. **No Flyway migration is needed.** `integration_links` already has every column 3.15 AC1 lists: `external_metadata jsonb`, `sync_status`, `last_sync_at`, `created_at`, `archived_at`; the `ck_integration_links_integration_type` CHECK **already includes `'github_pr'`** (V1, `V1__create_workflow_core_tables.sql:251`). The V6 partial-unique index `uq_integration_links_active_linear_ref ON (integration_type, external_ref) WHERE archived_at IS NULL AND sync_status != 'superseded'` is keyed on `integration_type` despite the "linear" name — **it already enforces the AC2 cross-run conflict for `github_pr` too** (`V6__integration_link_active_uniqueness.sql`). Current head = **V11**; confirm before adding any migration (you should not need one).

4. **The conflict/supersede primitives exist; two SPI reads + one SPI write are MISSING.**
   - Conflict (AC2a): reuse `findActiveByTypeAndExternalRefForUpdate("github_pr", canonicalPrRef)` (pessimistic lock, race-free) + the V6 DB backstop.
   - Supersede (AC9): needs a **NEW** SPI read `findActiveByTypeAndWorkflowRun(ForUpdate)("github_pr", runId)` — `findActiveByWorkflowRun` returns the **Linear** variant by convention (`IntegrationLinkRecordPort.java:45-51`), so it cannot find a prior `github_pr` link to supersede.
   - `syncGitHubPr` (AC6) needs a **NEW** SPI write to update `external_metadata` + `last_sync_at` (+ `sync_status`) — the SPI has `updateSyncStatus`, `touchLastSyncAtByTypeAndExternalRef`, `markArchived`, but **none updates `external_metadata`**.

5. **The state machine BLOCKS the supersede path 3.15 needs.** `IntegrationLinkStateMachine` (`:40-61`) allows `SYNCED→SUPERSEDED` and `STALE→SUPERSEDED` but **NOT `LINKED→SUPERSEDED`**. A freshly-linked PR row is in `LINKED`; a retry-with-a-new-PR that supersedes it before any `syncGitHubPr` ran would throw `ILLEGAL_TRANSITION`. **You must add `LINKED→SUPERSEDED`** (+ update `IntegrationLinkStateMachineTest`).

6. **The canonical `external_ref` is the GitHubAdapter PR ref, NOT the runner-reported string.** `GitHubRealAdapter` emits `prRef = owner/repo#number` (`GitHubPullRequest.prRef`); the runner-reported `prReference` validated in `RunnerBroker` is `^PR-\d+$` or a GitHub PR URL — these formats do **not** match ([[proutput-prref-validator-rejects-real-adapter]]). AC1 fixes the canonical form as `org/repo#42`. **Feed `linkGitHubPr` from `RepositoryPushOutcome.prRef()` (the authoritative captureAndPush/GitHubAdapter value), never the untrusted runner string.** This sidesteps the format mismatch for the link and makes AC5 a canonical-vs-canonical comparison (the 3.12 enrichment already overwrites the artifact's `prReference` with `actual.prRef()` when present).

7. **`RepositoryPushOutcome` carries `prRef`/`branchRef`/`commitSha` but NOT `repoRef`.** `RepositoryPushOutcome(commitSha, branchRef, prRef, committed)` (`RepositoryWorkspaceService.java:678`). To populate AC1's `repositoryFullName`/`prNumber`/`prState`/`prUrl` metadata — and to verify the PR exists (`GITHUB_PR_NOT_FOUND`) — `linkGitHubPr` resolves the PR via `GitHubAdapter.getPullRequestByRef(prRef)` (`GitHubPullRequest{prRef, repoRef, number, sourceBranch, state, url, createdAt}`). The repo-compat rule (AC4) reuses the pattern already in `RepositoryWorkspaceService.verifyRepositoryIsConsistent` (`:467-494`, the `LINEAR_GITHUB_REPO_MISMATCH` source).

8. **The AC5 enforcement point (prOutput approval) DOES NOT EXIST yet.** `ApprovalService` exposes only `approveSpec`/`rejectSpec` — there is no `acceptImplementation`/`approvePlan` (story 3.17/3.20, backlog). So AC5's `ARTIFACT_PR_LINK_MISMATCH` is built here as a **reusable guard + unit test**, but its production call site lands in **story 3.20**. The new `DomainErrorCode` is added now (three-sites) so 3.20 can wire it without a registry change.

**SCOPE BOUNDARY — what 3.15 BUILDS vs DEFERS:**

| Concern | This story (3.15) | Deferred to |
|---|---|---|
| `IntegrationLinkService.linkGitHubPr(...)` (transactional: compat-check → cross-run conflict → supersede-prior-different-PR → insert `github_pr` row → append `integration.linked`) | **BUILD** | — |
| `IntegrationLinkService.syncGitHubPr(workflowRunId)` (refresh `prState` + `last_sync_at` via the new metadata-update SPI) | **BUILD** | — |
| `INTEGRATION_LINK_CONFLICT` (cross-run double-link) — AC2 | **BUILD** (DomainErrorCode exists; reuse conflict path) | — |
| `LINEAR_GITHUB_REPO_MISMATCH` (repo-compat, defense-in-depth) — AC4 | **BUILD** (DomainErrorCode exists; reuse 3.9 rule) | — |
| `ARTIFACT_PR_LINK_MISMATCH` guard method + **new DomainErrorCode (three-sites)** — AC5 | **BUILD** the guard + code | **wire at prOutput-approval → story 3.20** |
| Wire `RunnerBroker.validateAndEnrichPrOutput` TODO-3.15 seam (`:1279-1287`) → call `linkGitHubPr` from the authoritative push outcome | **BUILD** | — |
| New SPI reads/writes: `findActiveByTypeAndWorkflowRun(ForUpdate)`, `updateExternalMetadataAndSync` (+ persistence adapter + repository queries) | **BUILD** | — |
| State-machine `LINKED→SUPERSEDED` (+ test) — AC9 supersede | **BUILD** | — |
| Supersede-on-retry-with-a-NEW-PR + idempotent no-op on SAME-PR re-link — AC9 | **BUILD** | — |
| Foundation-gate widening: `linkGitHubPr` e2e vs `github-mock` — AC8 | **BUILD** | — |
| `IntegrationLinkService.markFailed`/`markStale` already exist — AC reuse | **REUSE** | — |
| **`PR_REF_CONTEXT_MISMATCH`** (3.12 AC6 leftover) | **RECONCILE** (subsumed by AC4 `LINEAR_GITHUB_REPO_MISMATCH`; see clarification) | — |
| Flyway migration / new table column | **NONE** (all columns + the `github_pr` CHECK exist; V6 index covers it) | — |
| Real-GitHub end-to-end sync against a live PR | **DEFER** (mock-driven here; real adapter is `github-real`-gated) | pilot / 3.14 IT |

## Acceptance Criteria

> Criteria are the epic's verbatim ACs (`epic-03-agent-execution.md` §"Story 3.15", lines 297–314) with **binding clarifications** in **bold parentheticals** where the epic wording predates the live code or references not-yet-built upstream pieces (3.20 plan-approval trigger).

1. **Given** the `integration_links` table from story 1.3, **Then** rows for GitHub linkage carry: `public_id` (`ilk_` prefix), `integration_type='github_pr'`, `workflow_run_id`, `external_ref` (canonical PR identifier — `org/repo#42`), `external_metadata` (JSONB: `repositoryFullName`, `branch`, `commitSha`, `prNumber`, `prState`, `prUrl`), `created_at`, `last_sync_at`, `sync_status`. **(No Flyway change — every column exists; the `github_pr` CHECK exists. `external_ref` MUST be the GitHubAdapter canonical `prRef` (`owner/repo#number`), NOT the runner-reported `PR-<n>` — Trap T1 / [[proutput-prref-validator-rejects-real-adapter]]. `external_metadata` is built as a `Map`, redacted via `RedactionPolicyService` at `SHAREABLE_REDACTED` exactly like `buildExternalMetadata`/`serializeRedactedMetadata` in `linkTicket`, then passed as bytes to `insert`.)**

2. **Given** `IntegrationLinkService.linkGitHubPr(...)`, **When** called, **Then** in **one transaction** it: (a) verifies the (`repositoryFullName`, `prNumber`) — i.e. the canonical `external_ref` — is not already linked to a **different** workflow run, raising **`INTEGRATION_LINK_CONFLICT`** if so (story 3.14 AC4); (b) inserts the row keyed by the idempotency parameter; (c) appends an **`integration.linked`** workflow event (`WorkflowEventType.INTEGRATION_LINKED` — already in the registry) with details including PR ref + repo + branch + commit. **(Conflict via `findActiveByTypeAndExternalRefForUpdate("github_pr", prRef)` (pessimistic lock) + the V6 DB backstop translated to `INTEGRATION_LINK_CONFLICT` by the persistence adapter. The `integration.linked` event is NEW — `linkTicket` does NOT currently emit it; you must inject `WorkflowEventWritePort` and append within the same tx — Trap T4. Add the needed `WorkflowEventDetailKeys` + allow-list entries — Trap T5.)**

3. **Given** NFR17 durability, **Then** the row preserves enough metadata that a reviewer can reconstruct run↔ticket↔repo↔branch↔commit↔artifacts↔PR **without re-querying GitHub** — verified by an inspection test that simulates GitHub unreachable and asserts the inspection still returns the linked metadata. **(All reconstruction fields live in `external_metadata`; the inspection path reads the persisted bytes, never the adapter. Reuse the `TicketSummaryProjection`-style raw-bytes read pattern.)**

4. **Given** NFR20 wrong-ticket prevention, **Then** before linking, the service verifies the workflow's existing Linear ticket linkage and the GitHub PR's referenced repository are compatible; mismatches raise **`LINEAR_GITHUB_REPO_MISMATCH`**. **(Defense-in-depth — story 3.9 AC9 `verifyRepositoryIsConsistent` already prevents the workspace from being prepared against a conflicting repo, so this can only fire on a code-path bug. The epic's `linear-github-mapping.yml` config does NOT exist; the implemented MVP rule (story 3.9) is "the PR's `repoRef` must match the run's established repository linkage." Reuse that rule — do NOT introduce a new mapping file. `DomainErrorCode.LINEAR_GITHUB_REPO_MISMATCH` already exists.)**

5. **Given** the ApprovalService, **Then** approving a `prOutput` artifact requires the artifact's PR reference to match the linked `integration_links.external_ref` — preventing approval of an artifact whose PR reference has drifted; mismatches raise **`ARTIFACT_PR_LINK_MISMATCH`** (NEW `DomainErrorCode`, three-sites). **(The prOutput-approval call site does NOT exist — `ApprovalService` has only `approveSpec`; `acceptImplementation` is story 3.20. BUILD the new DomainErrorCode + a reusable guard (e.g. `IntegrationLinkService.assertArtifactPrLinkMatches(runId, artifactPrReference)`) + unit test; DEFER the production wiring at prOutput approval to story 3.20. Because 3.12's enrichment overwrites the artifact `prReference` with `actual.prRef()` (canonical), the guard compares canonical-to-canonical — note the dependency that enrichment succeeded; if it was swallowed (best-effort) the artifact may still hold `PR-<n>` and the guard must normalize or fail closed — document the chosen normalization.)**

6. **Given** sync status, **Then** `IntegrationLinkService.syncGitHubPr(workflowRunId)` calls `GitHubAdapter.getPullRequestByRef(...)` to refresh `external_metadata.prState` (open/closed/merged) and `last_sync_at` — used by orchestration when transitioning toward `Completed` to verify the PR is mergeable. **(Needs the NEW `updateExternalMetadataAndSync` SPI write + re-redaction of the refreshed metadata. Transition `LINKED→SYNCED` (or `STALE→SYNCED`) via the existing state machine. The `Completed`-precondition consumer is NOT wired here — that orchestration lands with the completion/merge flow; build `syncGitHubPr` + its test, expose it for later callers.)**

7. **Given** classification, **Then** the GitHub metadata in `external_metadata` is classified `shareable-redacted` (PR URLs, branch names, commit SHAs are shareable) — never `local-only`. **(Pass `DataClassification.SHAREABLE_REDACTED.value()` to `RedactionPolicyService.redact`, identical to `linkTicket`. Assert the effective classification in a test.)**

8. **Given** ArchUnit + foundation-gate widening, **Then** this story widens the foundation gate (story 1.23) to assert `IntegrationLinkService.linkGitHubPr` works end-to-end against the **mock** adapter as part of the gate scenarios. **(No existing foundation contract references integration links — add a new gate scenario/assertion under `src/test/java/org/dradgo/foundation/…` that drives `linkGitHubPr` against `github-mock` + asserts the row + `integration.linked` event. Verify via `-Pfoundation-gate` — [[verify-ci-fixes-in-clean-env]].)**

9. **Given** retry / re-dispatch behavior from story 3.12 AC8, **Then** if a PR/output retry produces the **same** PR reference, `linkGitHubPr` is a **no-op** (idempotent replay) — never duplicate-links; if a retry produces a **different** PR reference, the prior link is **superseded** (status updated, new link created) with both visible in audit history. **(Same-PR same-run → return the existing row (mirror `linkTicket`'s `idempotent_same_run` branch). Different-PR same-run → find the prior `github_pr` link for THIS run via the NEW `findActiveByTypeAndWorkflowRun(ForUpdate)` read, transition it to `SUPERSEDED` (requires the `LINKED→SUPERSEDED` state-machine addition — Trap T2), then insert the new row — all in one tx. Cross-run same-PR → `INTEGRATION_LINK_CONFLICT` (AC2). The `external_ref` compared is the canonical `org/repo#n`.)**

10. **Given** the test suite, **Then** tests cover: happy-path linkage + `integration.linked` event; `INTEGRATION_LINK_CONFLICT` on cross-workflow double-link; `LINEAR_GITHUB_REPO_MISMATCH` on incompatible link; `ARTIFACT_PR_LINK_MISMATCH` on artifact↔link drift (guard unit test); NFR17 reconstruction with GitHub unreachable; idempotent re-link (same PR → no-op); supersede behavior on retry with a different PR (prior `superseded`, new `linked`, both in audit). **(Unit tests on `IntegrationLinkService` (mock the SPI + `GitHubAdapter` + `WorkflowEventWritePort`) + the foundation-gate e2e (AC8). A `@SpringBootTest`+Testcontainers persistence/IT that exercises the real conflict index + supersede must be named `*IT` and carry `@Profile`/`github-mock` — Trap T6/T7.)**

**Logging instrumentation** (cross-cutting; see task below) — `INFO` on `linkGitHubPr`/`syncGitHubPr` entry + link success + supersede + sync refresh; `WARN` on cross-run conflict / repo mismatch / artifact-link mismatch / idempotent no-op; carry `correlationId`, `workflowRunId`, `integrationLinkPublicId`, plus the **non-secret** `externalRef`/`prNumber`/`prState` (sanitize via `MdcKeys.sanitizeForLog` as the adapter already does); **never** log the GitHub token, PR body, or any redacted-away field.

## Tasks / Subtasks

- [x] **Task 1 — `linkGitHubPr` on `IntegrationLinkService`** (AC: #1, #2, #4, #7, #9)
  - [x] Add `@Transactional public IntegrationLink linkGitHubPr(String workflowRunPublicId, String prReference, String repositoryRef, String branchName, String commitSha, ActorContext actor, String idempotencyKey)`. Mirror `linkTicketWithinTransaction`'s shape (lock → fetch → redact → insert) but for `GITHUB_INTEGRATION_TYPE = "github_pr"`. `prReference` MUST be the canonical `org/repo#n` (Trap T1).
  - [x] Resolve the authoritative PR via `gitHubAdapter.getPullRequestByRef(prReference)` → `GitHubPullRequest{repoRef, number, state, url, sourceBranch}`; empty → typed failure (`GITHUB_PR_NOT_FOUND` category). Use it to fill metadata `prNumber`/`prState`/`prUrl`/`repositoryFullName`.
  - [x] AC4 compat: verify the run's existing repository linkage matches `repoRef` (reuse the story-3.9 rule); mismatch → `LINEAR_GITHUB_REPO_MISMATCH`.
  - [x] AC2 conflict: `findActiveByTypeAndExternalRefForUpdate("github_pr", prReference)`; present + different run → `INTEGRATION_LINK_CONFLICT`; present + same run + same ref → idempotent no-op return.
  - [x] AC9 supersede: `findActiveByTypeAndWorkflowRunForUpdate("github_pr", runId)` (NEW SPI, Task 4); if a prior `github_pr` link exists for THIS run with a **different** `external_ref` → `updateSyncStatus(prior, SUPERSEDED, null)` (needs Task 5 state-machine add), then insert.
  - [x] Build `external_metadata` Map → `redactionPolicyService.redact(map, SHAREABLE_REDACTED)` → bytes → `insert(new NewIntegrationLink("github_pr", …))`. Reuse `serializeRedactedMetadata`.
  - [x] Append the `integration.linked` event (Task 3).
  - [x] **Constructor change** ([[docker-adapter-ctor-dep-fans-out]] / [[two-public-constructors-need-autowired]]): add `ObjectProvider<GitHubAdapter>` + `WorkflowEventWritePort` (+ pass through to the secondary test ctor). Keep `@Autowired` on exactly one ctor. Update the 2 `new IntegrationLinkService(...)` sites (Trap T3).

- [x] **Task 2 — `syncGitHubPr` + `assertArtifactPrLinkMatches`** (AC: #5, #6)
  - [x] `@Transactional public IntegrationLink syncGitHubPr(String workflowRunPublicId)`: locate the active `github_pr` link for the run; `gitHubAdapter.getPullRequestByRef(externalRef)`; rebuild + re-redact metadata with the fresh `prState`; persist via the NEW `updateExternalMetadataAndSync` SPI (Task 4); transition `→SYNCED` and set `last_sync_at`.
  - [x] `assertArtifactPrLinkMatches(String workflowRunPublicId, String artifactPrReference)`: normalize to canonical `org/repo#n`; compare to the active `github_pr` link's `external_ref`; mismatch → **`ARTIFACT_PR_LINK_MISMATCH`** (Task 6). Reusable guard; **do NOT** wire it into approval (no prOutput-approval site exists — DEFER to story 3.20; leave a `// TODO(story 3.20)` note at the (future) `acceptImplementation` site reference only in this story's docs, not in `ApprovalService`).

- [x] **Task 3 — `integration.linked` event + detail keys** (AC: #2)
  - [x] Append `WorkflowEventType.INTEGRATION_LINKED` ("integration.linked", already in `WorkflowEventType`) via `WorkflowEventWritePort.append(...)` inside the `linkGitHubPr` tx (actor = the passed `ActorContext`; `SYSTEM` when broker-originated).
  - [x] Add detail keys to `WorkflowEventDetailKeys` (e.g. `GITHUB_PR_REFERENCE`, `REPOSITORY_FULL_NAME`, `BRANCH`, `COMMIT_SHA`, `PR_NUMBER`, `PR_STATE`) and add them to the render allow-list set (Trap T5). Re-anchor any checkstyle suppression that shifts.

- [x] **Task 4 — SPI additions + persistence adapter + repository queries** (AC: #6, #9)
  - [x] `IntegrationLinkRecordPort.findActiveByTypeAndWorkflowRunForUpdate(integrationType, workflowRunPublicId): Optional<IntegrationLink>` (pessimistic lock) — used for supersede.
  - [x] `IntegrationLinkRecordPort.updateExternalMetadataAndSync(publicId, byte[] externalMetadata, IntegrationSyncStatus newStatus, Instant lastSyncAt): IntegrationLink` — re-validates state-machine + 64KB ceiling, decodes bytes to the Hibernate Map, persists.
  - [x] Implement both in `IntegrationLinkPersistenceAdapter` + the matching `IntegrationLinkRepository` JPQL/`@Query` (mirror `findActiveByTypeAndExternalRefForUpdate` for the lock query).

- [x] **Task 5 — State-machine `LINKED→SUPERSEDED`** (AC: #9)
  - [x] Add `SUPERSEDED` to `IntegrationLinkStateMachine` `LINKED` targets (`:40-45`). Update the class Javadoc table.
  - [x] Update `IntegrationLinkStateMachineTest` (legal `LINKED→SUPERSEDED`; keep the existing illegal-transition assertions intact).

- [x] **Task 6 — New DomainErrorCode (three-sites)** (AC: #5)
  - [x] Add `ARTIFACT_PR_LINK_MISMATCH` to `DomainErrorCode` + `ProblemDetailsCatalog` (HttpStatus.CONFLICT, non-retryable — mirror `INTEGRATION_LINK_CONFLICT` at `:139-142`) + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`. ([[new-domainerrorcode-three-sites]]) Verify GREEN via `-Pfoundation-gate`.
  - [x] Do NOT add `PR_REF_CONTEXT_MISMATCH` (reconciled into `LINEAR_GITHUB_REPO_MISMATCH` — already exists; see AC4 clarification).

- [x] **Task 7 — Wire the RunnerBroker 3.15 seam** (AC: #2, #9)
  - [x] Replace the `// TODO(story 3.15)` DEBUG-only seam in `RunnerBroker.validateAndEnrichPrOutput` (`:1279-1287`) with a call to `linkGitHubPr(workflowRunId, actual.prRef(), <repoRef from getPullRequestByRef or push outcome>, actual.branchRef(), actual.commitSha(), systemActor(correlationId), key)` — **only when `pushOutcome.isPresent()` and `actual.prRef() != null`** (no-repo dispatch returns empty — stay byte-identical to today on that path). Best-effort + swallow like the enrichment block (a linkage-only failure must NOT unwind the committed runner outcome or block `WaitingForReview`) — but surface `INTEGRATION_LINK_CONFLICT`/`LINEAR_GITHUB_REPO_MISMATCH` as `WARN` with the error code. Keep the `application/...`→`adapters..` boundary ([[application-cannot-import-adapters]]) — `GitHubAdapter` is an `application.integration.github` port, reachable.

- [x] **Task 8 — Foundation-gate widening** (AC: #8)
  - [x] Add a gate scenario asserting `linkGitHubPr` writes a `github_pr` row + emits `integration.linked` end-to-end against `github-mock`. Run under `-Pfoundation-gate`.

- [x] **Task 9 — Tests** (AC: #10)
  - [x] Extend `IntegrationLinkServiceUnitTest`: happy link + event details; cross-run `INTEGRATION_LINK_CONFLICT`; `LINEAR_GITHUB_REPO_MISMATCH`; idempotent same-PR no-op; supersede on different-PR (prior `SUPERSEDED`, new `LINKED`); `syncGitHubPr` refreshes `prState`+`last_sync_at`; `assertArtifactPrLinkMatches` pass + `ARTIFACT_PR_LINK_MISMATCH` fail; `SHAREABLE_REDACTED` classification (AC7); NFR17 reconstruction reads metadata with the adapter stubbed to throw.
  - [x] Extend `IntegrationLoggingContractTest` for the new log surfaces (link/supersede/sync/conflict/mismatch) via list-appender; update its `new IntegrationLinkService(...)` ctor call.
  - [x] Persistence/IT (`*IT`, `github-mock`): real V6 conflict index + supersede across rows + `updateExternalMetadataAndSync` round-trip. ([[springboot-testcontainers-test-must-be-IT]])
  - [x] `IntegrationLinkStateMachineTest`: `LINKED→SUPERSEDED` legal.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J + MDC on `linkGitHubPr`/`syncGitHubPr` entry + success + supersede + sync; `WARN` on conflict / repo-mismatch / artifact-link mismatch / idempotent no-op; the broker linkage seam (INFO success / WARN on typed failure).
  - [x] Parameterized logging only; levels as specified (INFO lifecycle, WARN recoverable).
  - [x] Context keys: `correlationId`, `workflowRunId`, `integrationLinkPublicId`; sanitize `externalRef` via `MdcKeys.sanitizeForLog`. Never log token / PR body / redacted fields.
  - [x] Pin each new surface with a focused list-appender assertion.

## Dev Notes

### THE references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **The method to twin** | `IntegrationLinkService.linkTicketWithinTransaction` (`:248-317`) + `linkTicket` idempotent/conflict branches (`:147-211`) + `buildExternalMetadata`/`serializeRedactedMetadata` (`:379-404`) + `crossRunConflict` (`:416-429`) | `linkGitHubPr` is the GitHub twin — same lock→fetch→redact→insert spine, different type + adapter + metadata + the supersede branch. |
| **The seam to promote** | `RunnerBroker.validateAndEnrichPrOutput` TODO-3.15 (`:1279-1287`); the authoritative refs at `enrichPrOutputArtifact` (`:1353-1425`); `RepositoryPushOutcome` (`RepositoryWorkspaceService.java:678`) | wire the linkage from `actual.prRef()`/`branchRef()`/`commitSha()`; best-effort/swallow discipline already established there. |
| **GitHub port + canonical ref** | `application.integration.github.GitHubAdapter` (`getPullRequestByRef`), `GitHubPullRequest{prRef,repoRef,number,state,url,sourceBranch}`, `GitHubRealAdapter` (`prRef = owner/repo#number`) | source of `repositoryFullName`/`prNumber`/`prState`/`prUrl` + the canonical `external_ref`. |
| **Repo-compat rule (AC4)** | `RepositoryWorkspaceService.verifyRepositoryIsConsistent` (`:467-494`) → `LINEAR_GITHUB_REPO_MISMATCH` | reuse the MVP rule (run's PR repo must match `repoRef`); there is no `linear-github-mapping.yml`. |
| **SPI + persistence** | `IntegrationLinkRecordPort` (`:21-125`) + `IntegrationLinkPersistenceAdapter` (`insert :84-150`, `updateSyncStatus :216-238`, conflict-translation `:303-332`) + `IntegrationLinkRepository` | add the 2 new SPI methods next to these; the conflict translation already covers `github_pr`. |
| **State machine** | `IntegrationLinkStateMachine` (`:40-61`) + `IntegrationLinkStateMachineTest` | add `LINKED→SUPERSEDED`. |
| **Event + detail keys** | `WorkflowEventType.INTEGRATION_LINKED`, `WorkflowEventWritePort.append`, `WorkflowEventRecord`, `WorkflowEventDetailKeys` (`:29-94` + allow-list set) | the event type exists but is unused; add detail keys + allow-list entries. |
| **Three-sites** | `DomainErrorCode` (`INTEGRATION_LINK_CONFLICT:28`, `LINEAR_GITHUB_REPO_MISMATCH:62` already present), `ProblemDetailsCatalog` (`:139-142`), `registry-api-schema-placeholders.json` | add only `ARTIFACT_PR_LINK_MISMATCH`. |
| **House discipline** | `3-12-…-pr-output…md` (Scope table, Decisions, Traps), `3-14-…-real-github-adapter…md` | the deferral/seam pattern + GitHub adapter shape. |

### Decisions (made by this story; rationale)

- **D1 — `external_ref` is the GitHubAdapter canonical `org/repo#n` from `RepositoryPushOutcome.prRef()`, not the runner-reported `PR-<n>`/URL.** Resolves the [[proutput-prref-validator-rejects-real-adapter]] format split and makes AC5 a canonical-vs-canonical compare (3.12 enrichment already writes `actual.prRef()` into the artifact). The untrusted runner string is never the link key.
- **D2 — Resolve PR metadata via `GitHubAdapter.getPullRequestByRef(prRef)` inside `linkGitHubPr`.** `RepositoryPushOutcome` lacks `repoRef`/`number`/`state`/`url`; the adapter fetch supplies them AND verifies existence (`GITHUB_PR_NOT_FOUND`). Avoids fragile string-parsing of `owner/repo#n`.
- **D3 — Inject `GitHubAdapter` via `ObjectProvider` (lazy).** `IntegrationLinkService` is an unconditional `@Service`; `GitHubAdapter` is `@Profile(github-mock|github-real)`-gated. A direct dep would red every `@SpringBootTest` lacking the profile ([[unconditional-service-needs-profile-gate]]). Resolve `getIfAvailable()` at the github-only call site; if absent at a real link attempt, surface a typed failure rather than NPE.
- **D4 — Build the AC5 guard + new error code now; DEFER its approval call-site to story 3.20.** No `acceptImplementation` exists. The reusable guard + three-sites code unblock 3.20 with no registry churn there.
- **D5 — No Flyway migration.** Every AC1 column + the `github_pr` CHECK exist; the V6 index (keyed on `integration_type`) already enforces the cross-run conflict. Confirm head = V11 stays put.
- **D6 — Reconcile `PR_REF_CONTEXT_MISMATCH` (3.12 AC6) into `LINEAR_GITHUB_REPO_MISMATCH` (3.15 AC4).** Both are the wrong-repo guard; the epic gives 3.15 the concrete `LINEAR_GITHUB_REPO_MISMATCH` code (already in `DomainErrorCode`). Do not add a second code for the same condition.

### Open Questions (each carries a recommendation — proceed unless the architect objects)

- **OQ-1 — Idempotency mechanism for `linkGitHubPr`.** `linkTicket` uses the full `IdempotencyService.checkAndReserve/complete` dance; the broker seam already has at-most-once via the runner-execution terminal state. **Recommend** the lighter `linkTicketWithinTransaction` shape (no `IdempotencyService`) for the broker-originated path — idempotency comes from the same-run/same-ref no-op + the unique index — and accept the `idempotencyKey` param for signature fidelity / a future direct caller. Confirm whether a standalone (non-broker) `linkGitHubPr` caller needs the full reservation path.
- **OQ-2 — `ARTIFACT_PR_LINK_MISMATCH` normalization when enrichment was swallowed.** 3.12 enrichment is best-effort; a failed enrichment leaves the artifact `prReference` as the runner-reported `PR-<n>` while `external_ref` is canonical. **Recommend** the guard normalize both sides to canonical where derivable and **fail closed** (`ARTIFACT_PR_LINK_MISMATCH`) when it cannot prove equivalence — surfacing the drift rather than silently approving. Confirm at the 3.20 wiring.
- **OQ-3 — `findActiveByWorkflowRun` convention.** The SPI doc says it returns the `linear` variant "by convention" once a run carries both link types. **Recommend** the NEW `findActiveByTypeAndWorkflowRun` for all `github_pr` reads (supersede + sync) and leave `findActiveByWorkflowRun` untouched. Confirm no current caller depends on it returning a `github_pr` row (none found).

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T1 — Canonical `external_ref`** ([[proutput-prref-validator-rejects-real-adapter]]). Link from `actual.prRef()` (`owner/repo#n`), NOT the runner-reported `PR-<n>`/URL. Mixing formats breaks the conflict index, supersede match, and AC5 compare.
- **T2 — `LINKED→SUPERSEDED` is illegal today** (`IntegrationLinkStateMachine.java:40-61`). A retry-with-a-new-PR supersede on a never-synced row throws `ILLEGAL_TRANSITION`. Add the transition + update the test.
- **T3 — Constructor fan-out** ([[docker-adapter-ctor-dep-fans-out]] / [[two-public-constructors-need-autowired]]). Adding `ObjectProvider<GitHubAdapter>` + `WorkflowEventWritePort` (+ thread through the secondary ctor, keep one `@Autowired`) breaks the 2 `new IntegrationLinkService(...)` sites: `IntegrationLinkServiceUnitTest`, `IntegrationLoggingContractTest`.
- **T4 — `linkTicket` does NOT emit `integration.linked` today.** The event type exists but is unused; you are adding the first emission. It MUST be inside the `linkGitHubPr` tx (`WorkflowEventWritePort.append` is `Propagation.REQUIRED`).
- **T5 — Event-detail allow-list** — new keys not in the `WorkflowEventDetailKeys` render allow-list are stripped from history. Add `GITHUB_PR_REFERENCE`/`REPOSITORY_FULL_NAME`/`BRANCH`/`COMMIT_SHA`/`PR_NUMBER`/`PR_STATE` to the allow-list set (`:92+`).
- **T6 — `*IT` naming** for the `@SpringBootTest`+Testcontainers persistence/conflict test ([[springboot-testcontainers-test-must-be-IT]]); add `@Tag` if you want it off default `verify`, but the name drives Surefire/Failsafe routing.
- **T7 — `github-mock` profile** for any test that touches `GitHubAdapter` ([[unconditional-service-needs-profile-gate]]); the unit tests mock the port directly and need no profile.
- **T8 — Validated-config / test-yaml** ([[validated-config-needs-test-yaml]]) — only relevant if you add a new `@ConfigurationProperties` field (you should NOT need one; no `linear-github-mapping.yml`). If you do, mirror it in `src/test/resources/application.yml`.
- **T9 — New DomainErrorCode → three sites** ([[new-domainerrorcode-three-sites]]) for `ARTIFACT_PR_LINK_MISMATCH`; verify `-Pfoundation-gate`.
- **T10 — Checkstyle line-anchored suppressions** ([[checkstyle-suppressions-line-anchored]]) — edits to `RunnerBroker`/`IntegrationLinkService` that shift a suppressed forbidden-call line need the `lines="N"` re-anchored.
- **T11 — `application/...` cannot import `org.dradgo.adapters..`** ([[application-cannot-import-adapters]]). `GitHubAdapter` is an `application.integration.github` port — reachable; do not reach `adapters.integration.github.GitHubRealAdapter` directly.
- **T12 — ArchUnit "only this service writes the link".** `linkTicket` enforces that only `IntegrationLinkService` (+ polling host) calls `LinearAdapter`; keep `linkGitHubPr` as the sole writer of `github_pr` rows — the broker calls the service, never the SPI/adapter directly.
- **T13 — `markFailed` on a failed sync** — `IntegrationLinkService.markFailed`/`markStale` exist; reuse for `GITHUB_PR_NOT_FOUND`/`GITHUB_RATE_LIMITED` during `syncGitHubPr` rather than inventing a new path (`failed → linked|superseded` is the recovery edge).
- **T14 — Run gates via PowerShell** ([[rtk-hook-only-matches-bash]]); verify Docker-backed tiers / foundation gate in a clean env / WSL2 ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. Enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`. ADR `0019-structured-logging` governs format.
- **Surface:** `IntegrationLinkService.linkGitHubPr`/`syncGitHubPr`/`assertArtifactPrLinkMatches` + the `RunnerBroker` linkage seam. `INFO` lifecycle (link success, supersede, sync refresh), `WARN` recoverable (cross-run conflict, repo mismatch, artifact-link mismatch, idempotent no-op, PR-not-found on sync), `ERROR` only unhandled.
- **Required context keys:** `correlationId`, `workflowRunId`, `integrationLinkPublicId`; non-secret `externalRef`/`prNumber`/`prState` permitted (sanitize via `MdcKeys.sanitizeForLog`).
- **Forbidden:** GitHub token, PR/commit bodies, any field the redaction pass removed, host absolute paths.
- **Test contract:** new logging surfaces pinned by at least one focused list-appender assertion (extend `IntegrationLoggingContractTest`).

### Project Structure Notes

- Backend module **`deliveryline-backend/`**. Base package `org.dradgo`. Java 21, Spring Boot 4.0.6.
- Service → extend `org.dradgo.application.integration.IntegrationLinkService` (do NOT create a parallel service).
- SPI → `org.dradgo.application.integration.spi.IntegrationLinkRecordPort` (+ `IntegrationLinkPersistenceAdapter` + `IntegrationLinkRepository`).
- State machine → `org.dradgo.application.integration.IntegrationLinkStateMachine` (+ test).
- Event keys → `org.dradgo.domain.registry.WorkflowEventDetailKeys`.
- Error code → `org.dradgo.domain.registry.DomainErrorCode` + `ProblemDetailsCatalog` + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (three-sites; add only `ARTIFACT_PR_LINK_MISMATCH`).
- Broker seam → `org.dradgo.application.runner.RunnerBroker.validateAndEnrichPrOutput` (`:1279-1287`).
- Foundation gate → new scenario under `src/test/java/org/dradgo/foundation/…`.
- **No transition-table change. No Flyway migration. No new config property.** Confirm head migration = V11 before adding anything. **Do NOT build** `acceptImplementation` (story 3.20) or any Epic-4 recovery action.

### Verification commands (PowerShell — [[rtk-hook-only-matches-bash]])

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=IntegrationLinkServiceUnitTest,IntegrationLinkStateMachineTest,IntegrationLoggingContractTest`
- Persistence/conflict IT (Docker/Testcontainers, `*IT`+`github-mock`): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=IntegrationLinkGitHubPrIT` (run via `verify` if the `@{argLine}`/jacoco issue bites — see 3.9/3a-1 note).
- Foundation gate (Docker up — three-sites error-code contracts + the new `linkGitHubPr` e2e): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false`.
- Static + full fast tier: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then `mvnw -pl deliveryline-backend test`.
- WSL2 Linux smoke of the Docker-backed IT + foundation gate ([[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]]).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.15] — ACs 1–10 (lines 297–314); NFR17/NFR19/NFR20; the 3b deferral note (line 8). Adjacent: Story 3.14 AC4 (`INTEGRATION_LINK_CONFLICT`, lines 277–296); Story 3.9 AC9 (`LINEAR_GITHUB_REPO_MISMATCH`, lines 173–196); Story 3.12 AC3/AC4/AC6 (lines 236–255).
- The deferring story: [Source: _bmad-output/implementation-artifacts/3-12-pr-output-artifact-generation-flow-orchestration.md] — Scope Boundary rows AC4/AC6, Decision D4, the `// TODO(story 3.15)` seam; the prOutput-ref format patterns + enrichment (the canonical-ref reconciliation).
- GitHub adapter: [Source: _bmad-output/implementation-artifacts/3-14-real-github-adapter-pr-branch-commit-refs-and-pat-auth.md] (`getPullRequestByRef`, `owner/repo#n` ref, idempotent PR) + 3-13 (mock + `github-mock` profile).
- Repo workspace: [Source: _bmad-output/implementation-artifacts/3-9-repository-workspace-service-git-clone-branch-management-commit-push.md] — `captureAndPush`/`RepositoryPushOutcome`, `verifyRepositoryIsConsistent` (the `LINEAR_GITHUB_REPO_MISMATCH` rule).
- Linear precedent: [Source: _bmad-output/implementation-artifacts/.../story 1.14 IntegrationLinkService] — `linkTicket` spine reused for `linkGitHubPr`.
- Code anchors (verified): `IntegrationLinkService.java:107,248,323,333,344,354,379-404,416-429`; `IntegrationLinkRecordPort.java:21-125`; `IntegrationLinkPersistenceAdapter.java:84-150,216-238,303-332`; `IntegrationLinkStateMachine.java:40-61`; `RunnerBroker.java:1182-1190,1204-1288,1353-1425` (TODO-3.15 at `:1279-1287`); `RepositoryWorkspaceService.java:467-494,502-545,678`; `GitHubAdapter`/`GitHubPullRequest`/`GitHubRealAdapter` (`application.integration.github`, `adapters.integration.github`); `DomainErrorCode.java:28,62` (INTEGRATION_LINK_CONFLICT + LINEAR_GITHUB_REPO_MISMATCH exist); `IntegrationFailureCategory.java` (GITHUB_PR_NOT_FOUND); `WorkflowEventType` (INTEGRATION_LINKED = "integration.linked"); `WorkflowEventDetailKeys.java:29-94`; `V1__create_workflow_core_tables.sql:236-263`; `V6__integration_link_active_uniqueness.sql`; head migration `V11`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-create-story 2026-06-10.

### Debug Log References

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=IntegrationLinkServiceUnitTest,IntegrationLinkStateMachineTest,IntegrationLoggingContractTest,RunnerBrokerUnitTest` → 85/0.
- Registry/schema/problem-details contracts: `-Dtest=WorkflowEventDetailKeysContractTest,*ProblemDetails*,*Registry*` → 31/0 (validates the new event detail keys ↔ history schema parity + the `ARTIFACT_PR_LINK_MISMATCH` three-sites code).
- Persistence IT (Docker/Testcontainers): `verify -Dit.test=IntegrationLinkGitHubPrIT` → 4/0.
- Foundation gate (Docker): `-Pfoundation-gate verify -Dit.test=FoundationGateVerificationTest` → fast tier 863/0/11skip + foundation contracts 17/0 (Contract #12 story 3.15 GREEN; Contract #7 ProblemDetails coverage GREEN).
- `spotless:apply` + `checkstyle:check` clean.
- IT note: the persistence IT needs an ambient tx (`@Transactional`) because the adapter mapper traverses the lazy `WorkflowRun`; a constraint violation aborts the Postgres tx, so a "same-ref re-link after supersede" scenario is invalid (V1 per-run unique `(type, ref, run)` forbids it) — the IT instead verifies supersede frees the ref **cross-run** (the realistic AC9 path).

### Completion Notes List

Implemented story 3.15 — `IntegrationLinkService` extended for GitHub PR linkage. All 10 ACs satisfied; the dormant `// TODO(story 3.15)` seam in `RunnerBroker.validateAndEnrichPrOutput` is now a live, tested `linkGitHubPr` write.

- **`linkGitHubPr` (AC1/2/4/7/9)** — transactional spine mirroring `linkTicketWithinTransaction`: resolve PR via `GitHubAdapter.getPullRequestByRef` (Decision D2) → AC4 repo-compat (defense-in-depth, `LINEAR_GITHUB_REPO_MISMATCH`) → AC2 cross-run conflict (`findActiveByTypeAndExternalRefForUpdate` + V6 DB backstop, `INTEGRATION_LINK_CONFLICT`) → same-ref/same-run idempotent no-op → AC9 supersede a prior different-PR link for the run (`updateSyncStatus(prior, SUPERSEDED)`) → build+redact `external_metadata` (`SHAREABLE_REDACTED`) → `insert("github_pr", …)` → append `integration.linked`. `external_ref` is the canonical GitHubAdapter `prRef` (Trap T1), fed from `RepositoryPushOutcome.prRef()` at the broker.
- **`syncGitHubPr` (AC6)** — re-queries the PR, rebuilds+re-redacts metadata with the fresh `prState`, persists via the new `updateExternalMetadataAndSync` SPI + `→SYNCED` + `last_sync_at`; a vanished PR / classified adapter failure routes to `markFailed` (Trap T13).
- **`assertArtifactPrLinkMatches` (AC5)** — reusable guard comparing the artifact PR ref to the active link's `external_ref`; mismatch / no-active-link → `ARTIFACT_PR_LINK_MISMATCH` (fails closed, OQ-2). Production approval call-site deferred to story 3.20 (no `acceptImplementation` exists); `ApprovalService` untouched.
- **SPI (Task 4)** — `findActiveByTypeAndWorkflowRunForUpdate` (pessimistic lock, supersede/sync reads) + `updateExternalMetadataAndSync` (re-validates state-machine + 64KB ceiling), implemented in `IntegrationLinkPersistenceAdapter` + `IntegrationLinkRepository`.
- **State machine (Task 5)** — added legal `LINKED → SUPERSEDED` (+ Javadoc + test updates) for supersede-on-retry of a never-synced row (Trap T2).
- **Three-sites (Task 6)** — `ARTIFACT_PR_LINK_MISMATCH` added to `DomainErrorCode` + `ProblemDetailsCatalog` (CONFLICT, non-retryable) + `registry-api-schema-placeholders.json`. No `PR_REF_CONTEXT_MISMATCH` (reconciled into `LINEAR_GITHUB_REPO_MISMATCH`, D6).
- **Event keys (Task 3)** — `GITHUB_PR_REFERENCE`/`REPOSITORY_FULL_NAME`/`BRANCH`/`COMMIT_SHA`/`PR_NUMBER`/`PR_STATE` added to `WorkflowEventDetailKeys` allow-list + `workflow-history.v1.schema.json` (Trap T5; contract test GREEN). `integration.linked` is the first emission of `WorkflowEventType.INTEGRATION_LINKED` (Trap T4).
- **Broker seam (Task 7)** — `IntegrationLinkService` injected via lazy `ObjectProvider`→`Supplier` (Trap T3 fan-out absorbed by `() -> null` in the package-private test ctors); `linkGitHubPrBestEffort` fires only when `pushOutcome.isPresent()` and `actual.prRef() != null`, best-effort + swallow (cross-run conflict / repo-mismatch surface as WARN with the error code). No-repo dispatch path is byte-identical. Repo-ref passed as `null` (not carried on `RepositoryPushOutcome`; `linkGitHubPr` resolves it via the adapter and story 3.9 already enforced repo-compat).
- **No Flyway / no transition-table / no new config property** (D5). Head migration unchanged.
- **OQ-1** — used the lighter `linkTicketWithinTransaction` shape (no `IdempotencyService`); the `idempotencyKey` param is accepted for signature fidelity. **OQ-3** — left `findActiveByWorkflowRun` untouched; all `github_pr` reads use the new typed lock query.

### File List

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkStateMachine.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/spi/IntegrationLinkRecordPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationLinkPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/IntegrationLinkRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/resources/schemas/cli/workflow-history.v1.schema.json`

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLinkServiceUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLinkStateMachineTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/IntegrationLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/foundation/IntegrationLinkGitHubPrFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/persistence/IntegrationLinkGitHubPrIT.java`

### Change Log

- 2026-06-10 — Story 3.15 implemented (`ready-for-dev → in-progress → review`): `IntegrationLinkService.linkGitHubPr`/`syncGitHubPr`/`assertArtifactPrLinkMatches`; 2 new SPI methods + persistence/repository impls; `LINKED→SUPERSEDED` state-machine edge; `ARTIFACT_PR_LINK_MISMATCH` (three-sites); 6 GitHub PR event detail keys + history-schema parity; live `RunnerBroker` linkGitHubPr seam; foundation-gate Contract #12 + persistence IT. Gates GREEN (focused 85/0, contracts 31/0, IT 4/0, `-Pfoundation-gate` fast tier 863/0 + foundation 17/0, spotless+checkstyle clean).

### Review Findings

> bmad-code-review 2026-06-10 — 3-layer adversarial (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the working-tree diff (18 files, ~+1267). Acceptance Auditor: all 10 ACs MET (AC5 with a doc/code discrepancy, below); three-sites + event-key/schema parity + ctor fan-out all verified. Every High finding was verified against source before classifying — 2 were FALSE PREMISES (see Dismissed). Triage: 1 decision-needed, 2 patch, 5 defer, 6 dismissed.

- [x] [Review][Decision→Patch][FIXED] `syncGitHubPr` strands a link in `FAILED` with no recovery path — A `GitHubAdapterException` (transient rate-limit/5xx OR terminal PR-not-found, undistinguished) routes the link to `FAILED` via `markFailed`. `syncGitHubPr` then locates the active link via `findActiveByTypeAndWorkflowRunForUpdate` (excludes only `superseded`, so a `FAILED` row is still "active") and drives `→ SYNCED`; `FAILED → SYNCED` is illegal (`IntegrationLinkStateMachine` allows `FAILED → LINKED|SUPERSEDED` only), so a subsequent sync threw `ILLEGAL_TRANSITION`. **Resolution (Alex, option 1):** `syncGitHubPr` now recovers `FAILED → LINKED` (the state-machine's recovery edge) after a successful re-fetch, before the `→ SYNCED` write, so a cleared transient outage can re-sync. Regression test `syncGitHubPrRecoversAFailedLinkBeforeSyncing` (InOrder: updateSyncStatus(LINKED) → updateExternalMetadataAndSync(SYNCED)). [IntegrationLinkService.java:493-540, IntegrationLinkStateMachine.java:63-65]

- [x] [Review][Patch][FIXED] `assertArtifactPrLinkMatches` — null-unsafe compare + javadoc overstated "normalization" [IntegrationLinkService.java:565-592] — flipped to `artifactPrReference.equals(canonicalLink)` (the arg is validated non-blank) for null-safe fail-closed; corrected the javadoc to describe the actual **format-exact** fail-closed-on-non-exact compare (no normalization, OQ-2). Dormant (guard deferred to 3.20).
- [x] [Review][Patch][FIXED] `buildGitHubExternalMetadata` stored `commitSha: null` instead of omitting it [IntegrationLinkService.java:771-786] — guarded the put with `commitSha != null && !isBlank`, symmetric with `appendIntegrationLinkedEvent` (:798), so the link path never persists JSON `null` and a failed `resolveHeadSha` on `syncGitHubPr` no longer overwrites a previously-good SHA with `null` (NFR17 reconstruction preserved).

- [x] [Review][Defer] V1 non-partial unique `(type, external_ref, run_id)` collides when a retry returns to an earlier (now-superseded) PR ref [IntegrationLinkService.java:430-462] — deferred, documented constraint ([[integration-link-adapter-tx-and-supersede]] + Debug-Log IT note). Active-row queries exclude `superseded` so the same-ref no-op misses the superseded row; `insert` then hits the constraint. Swallowed as WARN on the broker path; surfaces untyped (`DataIntegrityViolationException`) to a future direct (OQ-1) caller.
- [x] [Review][Defer] `findActiveByTypeAndWorkflowRunForUpdate` returns `Optional` with `order by` but no `LIMIT`/`findFirst` → `NonUniqueResultException` if ≥2 active rows ever exist for `(type, run)` [IntegrationLinkRepository.java:54-67] — deferred, latent. Mirrors the pre-existing `findFirstActiveByWorkflowRunPublicId` pattern (`@Query` ignores the name-based limit); guarded in practice by the supersede invariant + pessimistic lock + unique index keeping it ≤1.
- [x] [Review][Defer] Broker swallows cross-run conflict / repo-mismatch as WARN with no durable signal [RunnerBroker.java:1359-1370] — deferred, spec-mandated best-effort (Task 7). A genuinely mislinked PR (cross-run `INTEGRATION_LINK_CONFLICT`) is logged and the run still advances to `WaitingForReview` with the `integration_links` row simply absent — no event/status surfaces the missed linkage a reviewer relies on. Revisit when the live trigger (3.20) lands.
- [x] [Review][Defer] PR-not-found / transient adapter-failure surface as `INTEGRATION_LINK_CONFLICT` (HTTP 409, non-retryable) in `linkGitHubPr` [IntegrationLinkService.java:848-879] — deferred, mirrors the existing Linear `adapterFailure` precedent (:711). Carrying a transient (rate-limit/5xx) failure as a non-retryable 409, and a "not found" as a "conflict", is a debatable typed surface at a future direct REST caller; the broker path swallows it today.
- [x] [Review][Defer] `assertArtifactPrLinkMatches` is non-`@Transactional` yet invokes the `PESSIMISTIC_WRITE` (`ForUpdate`) read [IntegrationLinkService.java:565-572] — deferred. A read-only drift guard should not take a write lock and will need an ambient tx (pessimistic locking outside a tx is a no-op / `TransactionRequiredException` risk). Reconcile to a non-locking read + ambient tx when wiring at prOutput-approval (story 3.20).

**Dismissed (6):** (1) `idempotencyKey` ignored — documented OQ-1, idempotency comes from the same-run no-op + V6 unique index. (2) Links when `committed()==false` — a non-null `prRef` means the PR exists; linking it is valid regardless of a new commit. (3) `prState`/`branch` put unconditionally vs `commitSha` guarded — correct: `GitHubPullRequest` guarantees non-blank `state`/`sourceBranch`/`url`; only `commitSha` (from `RepositoryPushOutcome`) is nullable. (4) adapter-unavailable → `INTERNAL_ERROR` — D3-satisfied typed failure (no NPE); misconfigured-profile is an ops fault. (5) **FALSE PREMISE** — "best-effort swallow leaves the tx rollback-only / unwinds the runner outcome" (Blind+Edge): `onResult` is non-`@Transactional` and calls `validateAndEnrichPrOutput`→`linkGitHubPrBestEffort` directly (RunnerBroker.java:1041, 1297) with no wrapping `TransactionTemplate`; `linkGitHubPr` (`@Transactional` REQUIRED) opens its own physical tx that rolls back independently on throw — the swallow is safe, `recordCompleted` commits afterward. (6) IT uses `@ActiveProfiles({"test","linear-mock"})` not `github-mock` — functionally correct; the IT exercises only the persistence SPI and never touches `GitHubAdapter`.
