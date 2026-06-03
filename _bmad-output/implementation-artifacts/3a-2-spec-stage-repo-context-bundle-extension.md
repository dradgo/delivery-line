# Story 3a.2: Spec-Stage Repo-Context Bundle Extension

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer,
I want `ContextBundleService.createForSpecInvestigation(...)` (the spec-stage bundle from story 2.8) extended to additionally carry a reference to the cloned repository working tree (from story 3.9 `RepositoryWorkspaceService`) plus a curated repo summary — top-level tree listing, README pointer, and package/config manifest pointers — gated so that **no-repo and no-`github-*`-profile dispatches stay byte-for-byte identical to today**,
so that the spec runner has actual codebase context when generating a spec against a real Linear ticket — closing the gap between "ticket text" (current story 2.8 scope) and "actual git project" that the active-slice pilot requires.

## Context & Central Reconciliation (READ FIRST)

This story extends an **existing, working** method — it does NOT create a parallel one. The spec-stage bundle is composed by `ContextBundleService.createForSpecInvestigation(...)` (`deliveryline-backend/src/main/java/org/dradgo/application/runner/ContextBundleService.java:237`), assembled by the private `assembleForSpecInvestigation(...)` (`:348`), redacted once via `RedactionPolicyService.redact(root, "shareable-redacted")` (`:292`), validated against `context-bundle.v1.schema.json` by `RunnerContractValidator`, and returned as redacted bytes. You are adding **five additive, optional fields** to that JSON, sourced from a prepared repository workspace.

**THE CENTRAL ORDERING PROBLEM (this is the heart of the story):**

1. The spec bundle is composed inside `RunnerBroker.dispatch(...)` at `RunnerBroker.java:388-396` — this happens **before** the runner ever runs.
2. The repository is cloned by `DockerRunnerAdapter` **later**, during `runnerAdapter.dispatch(request)` (`RunnerBroker.java:465`), and **only** when `request.repositoryRef() != null`.
3. `RunnerBroker.dispatch(workflowRunId, stage, idempotencyKey, actor)` carries **no `repositoryRef`**, and the request is built with the **7-arg back-compat constructor** (`RunnerBroker.java:456-464`) — so `repositoryRef` is `null` on the broker→adapter path **today**, and the story-3.9 repo seam is effectively dormant end-to-end (exercised only by direct `DockerRunnerAdapter` tests).

Therefore: to embed *real* repo content (tree listing, README, manifests) into the persisted bundle, the working tree must exist on disk **at composition time** — i.e. the workspace must be cloned **before** `createForSpecInvestigation` runs, not after in the adapter. `RepositoryWorkspaceService.prepareWorkspace(...)` is idempotent (reuses an existing clone — story 3.9 AC3), so cloning early and reusing in the adapter is safe.

**THE GATING DISCIPLINE (mirrors stories 3.9 / 3a-1 / 2.17 / 2.18 — build the capability, gate the live seam):**

- The fast Surefire tier runs with **no `github-mock`/`github-real` profile**, so the profile-gated `RepositoryWorkspaceService` bean is **absent** → the broker's nullable `repositoryWorkspaceService` field (`RunnerBroker.java:98`, resolved from `ObjectProvider` at `:155`) is `null` → **no repo summary is produced → the spec bundle is byte-for-byte identical to today** (~700 fast tests stay green; existing `ContextBundleServiceSpecInvestigationTest` assertions unchanged for the no-repo path).
- The full repo-context path is proven by a **`github-mock`-profile integration test** with a configured repo + the story-3.9 local-bare-repo harness.
- `repositoryRef` has **no Linear↔GitHub mapping** (does not exist — deferred to stories 3.32/3.33). For the pilot it is **config-resolved** from `deliveryline.workflow.repos` (1:1 single-repo assumption). `ticketRepositoryMappingVersion` is a config-derived marker, NOT a real mapping version.

**Do NOT change `ContextBundleService.create(...)` (the execution-stage path) in this story.** Implementation-stage repo context is story 3.10's job. This story is spec-investigation only (epic 3a-2 note: repo fields are added *alongside* the existing 2.8 AC3 fields, never replacing them).

## Acceptance Criteria

(AC shape adapted from story 3.10 ACs 1–10 for the spec-investigation stage.)

1. **Bundle composition (additive).** `ContextBundleService.createForSpecInvestigation(...)` is extended to additionally emit, **when (and only when) a repository workspace was prepared for this run**, these five fields into the spec-investigation bundle JSON: `repositoryWorkspaceRef` (the container mount path the runner reads the working tree from — the deterministic `/workspace/repo`), `repositoryTreeSummary` (depth-limited top-level tree listing with file/dir type), `repositoryReadmeRef` (mount-relative path to the README, or null when none exists), `packageManifestRefs` (array of mount-relative paths + kind for detected `package.json` / `pom.xml` / `Cargo.toml` / `build.gradle` / `go.mod` / `requirements.txt` / `pyproject.toml`), and `ticketRepositoryMappingVersion` (the marker for which Linear↔GitHub mapping resolved to this repo). When no workspace was prepared, **none of these fields are emitted** and the bundle is byte-identical to the story-2.8 baseline. The existing 2.8 AC3 fields (`ticketSummary`, `approvedSpecificationReference=null`, `priorFeedbackReferences`, `artifactReferences`, `executionConstraints`, `classification`) are unchanged.

2. **Schema validation.** The bundle continues to conform to `context-bundle.v1.schema.json` (story 1.6) and is validated by `RunnerContractValidator` before persistence. The five new fields are added to the schema as **optional** properties (NOT in the `required` array) so that no-repo spec bundles and execution-stage `create(...)` bundles still validate; `additionalProperties:false` and `schemaVersion const:1` are preserved (the new fields must be listed under `properties`). This is an **additive, backward-compatible in-place v1 patch** — no version bump (mirrors the existing `minItems` 1→0 precedent at `context-bundle.v1.schema.json:86-87`). `ContextBundleService.CONTEXT_BUNDLE_SCHEMA_VERSION` stays `1`.

3. **Redaction (same rigor as 3.10 AC4).** The repo-derived content is redacted by the **existing single** `redactionPolicyService.redact(root, "shareable-redacted")` pass (`ContextBundleService.java:292`) — the JSON walker redacts every text leaf, including tree-summary entries. **Adversarial fixture tests prove** that no Linear API key (`lin_api_…`), GitHub token (`ghp_…` / `github_pat_…`), absolute machine path (`C:\Users\{name}\…`, `/Users/{name}/…`, `/home/{name}/…`), `.env` `KEY=VALUE` secret, or PEM private-key block appears in the persisted bundle even when a tree entry / README filename / manifest content contains them. The persisted bundle carries **only** the container mount path (`/workspace/repo`) and mount-relative paths — **never** a host absolute path.

4. **Versioning (same as 3.10 AC5).** Each bundle is persisted with the monotonic `contextBundleVersion` per (workflowRun, stage) already assigned by `recordPort.nextContextBundleVersion(...)` (`RunnerBroker.java:356`); a re-dispatch (`retrySpecGeneration`, story 3a-1 AC5) creates a new version with a freshly-summarized workspace — never overwrites. No new persistence column is needed (the bundle bytes live in scratch; only `contextBundleVersion` is on `runner_executions`). **No Flyway migration** (max stays V11).

5. **Inspection (same as 3.10 AC6 / FR55).** `WorkflowInspectionService.getContextBundleForArtifact(artifactId)` returns the spec-stage bundle **verbatim** from scratch (no recomposition) — the new fields appear automatically with **no service code change**. Verify the repo-context fields render via CLI `deliveryline status {runId} --include-context-bundle` (both text and JSON modes) and the REST detail expansion path. Resolution is by the artifact→runner-execution link, independent of artifact `status` (relevant: spec artifacts stay `pending` — [[markavailable-has-no-production-caller]]).

6. **Repo summary derivation lives in the workspace layer (layering).** The top-level tree listing + README/manifest detection is produced by **`RepositoryWorkspaceService`** (application layer, already the owner of the prepared workspace), returning a plain project-owned record (e.g. `RepositoryContextSummary`). Tree enumeration goes through a **new `GitCommandPort` SPI operation** (`application.runner.workspace.spi`) implemented in `adapters.git.CliGitAdapter` via `git ls-tree`/`ls-files` (deterministic, `.gitignore`-respecting, depth-limited) — NOT a bespoke filesystem walk that would sweep `node_modules`/`target`, and NOT a direct adapter import from the application layer ([[application-cannot-import-adapters]]). `ContextBundleService` receives the summary as a **plain method parameter** and must NOT depend on `RepositoryWorkspaceService` (see Trap T1).

7. **Gated wiring in the broker (the live seam).** `RunnerBroker.dispatch(...)`, for `stage == RunnerStage.INVESTIGATION` only, when `repositoryWorkspaceService != null` AND a `repositoryRef` resolves from config, calls `prepareWorkspace(...)` (idempotent) **before** `createForSpecInvestigation`, derives the `RepositoryContextSummary`, and passes it (nullable) into the extended `createForSpecInvestigation(...)`. The later `DockerRunnerAdapter.prepareWorkspace` becomes an idempotent reuse of the same `{rex}/repo` clone. When the service is absent or no repo resolves, the summary is `null` and the dispatch path is unchanged (no clone, no extra fields). Do **not** widen the `dispatch(...)` public signature — resolve `repositoryRef` internally (see D2).

8. **ArchUnit boundary (same as 3.10 AC8).** Bundle composition depends only on application services + domain types + project-owned records; no adapter types leak into `ContextBundleService`. Redaction goes through `RedactionPolicyService` (no bespoke regex — `CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` would fail the build). The new `GitCommandPort` op keeps git in `adapters.git` behind the SPI. `RepositoryWorkspaceService`'s pinned-collaborator rule (`REPOSITORY_WORKSPACE_SERVICE_SCOPE`) still passes.

9. **Fixture extension (same as 3.10 AC10 / epic 3a-2 note).** At least one fixture event stream entry in story 1.23's set (`deliveryline-backend/src/test/resources/fixture-event-streams/`) includes a spec-stage bundle with repo context, so E2 frontend tests (story 2.17 spec-variant rendering) have realistic fixture data. Extend `schema/workflow-events-response.schema.json` `details` properties if needed; the three fixture contract tests (`FixtureEventStreamSchemaConformanceContractTest`, `FixtureEventStreamTransitionIntegrityContractTest`, `FixtureEventStreamArtifactVariantCoverageContractTest`) stay green.

10. **Runner-contracts fixtures (same as 3.10 AC9, contracts module).** A **new valid** fixture carrying the five repo fields is added under `deliveryline-runner-contracts/src/test/resources/fixtures/valid/` (e.g. `context-bundle.v1.spec-investigation-repo-context.valid.json`); the existing `context-bundle.v1.spec-investigation-bootstrap.valid.json` (no repo fields) stays valid; `RunnerContractValidatorTest` corpus passes.

11. **Test coverage (same as 3.10 AC9).** Tests cover: (a) spec-investigation bundle composition WITH repo content (tree summary, README ref, manifest refs, mapping version) given a non-null summary; (b) no-repo composition produces the byte-identical 2.8 baseline; (c) redaction adversarial fixtures including a PEM block, `.env` content, GitHub/Linear tokens, and `C:\Users\{name}` paths embedded in repo content (leveraging 2.24's hardened patterns); (d) schema validation rejection of a malformed repo-bearing bundle; (e) end-to-end `github-mock`-profile integration test (configured repo → `prepareWorkspace` clone via local bare repo → bundle carries repo context → `getContextBundleForArtifact` returns it); (f) the new `GitCommandPort` tree-listing op (depth limit, `.gitignore` respect).

12. **Logging instrumentation** (cross-cutting; see task below) — thin, field-only repo-context summary logging; NEVER tree contents, file bodies, tokens, or host paths.

## Tasks / Subtasks

- [x] **Task 1 — Extend the v1 schema additively (AC2)** (AC: #2)
  - [x] Add to `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json` `properties` (NOT `required`): `repositoryWorkspaceRef` (string, pattern `^/workspace/repo$` or a constrained mount path), `repositoryTreeSummary` (array of `$defs/repoTreeEntry`), `repositoryReadmeRef` (`[string,null]`, mount-relative `minLength:1`), `packageManifestRefs` (array of `$defs/repoManifestRef`), `ticketRepositoryMappingVersion` (string, `minLength:1`).
  - [x] Add `$defs/repoTreeEntry` (`{path: string minLength1, type: enum [file,dir]}`, `additionalProperties:false`) and `$defs/repoManifestRef` (`{path: string minLength1, kind: string minLength1}`, `additionalProperties:false`).
  - [x] Keep `additionalProperties:false`, `schemaVersion const:1`, and the existing `required` array unchanged. Add an inline `description` documenting the additive backward-compatible patch (mirror the `minItems` precedent at line 86-87).
  - [x] Verify `ContextBundleService.CONTEXT_BUNDLE_SCHEMA_VERSION` stays `1` (`ContextBundleService.java:51`).

- [x] **Task 2 — Tree-listing SPI op + adapter impl (AC6, AC8, T4)** (AC: #6)
  - [x] Add to `GitCommandPort` (`application/runner/workspace/spi/GitCommandPort.java`): `List<RepoTreeEntry> listTopLevelTree(Path repoDir, int maxDepth)` (or `lsFiles`-style), returning workspace-relative entries; respects `.gitignore`; deterministic ordering.
  - [x] Implement in `adapters/git/CliGitAdapter.java` via `git ls-tree -r --name-only HEAD` (or `git ls-files` for tracked files) bounded by `maxDepth`; route stderr through the existing redaction (`CliGitAdapter` already redacts every git line). Classify failures through the existing `GitCommandException` ladder.
  - [x] Add the `RepoTreeEntry` record to the SPI package (project-owned).

- [x] **Task 3 — `RepositoryContextSummary` derivation in `RepositoryWorkspaceService` (AC6)** (AC: #6)
  - [x] Add a project-owned record `RepositoryContextSummary(String mountPath, List<RepoTreeEntry> treeSummary, String readmeRef, List<RepoManifestRef> manifestRefs, String mappingVersion)` (decide package — `application.runner.workspace`).
  - [x] Add `RepositoryContextSummary summarize(RepositoryMount mount, ...)` (or `prepareAndSummarize(...)`) to `RepositoryWorkspaceService`: tree via the new `GitCommandPort.listTopLevelTree`; README detection by case-insensitive filename match (`README*`); manifest detection by filename match against the known set; `mountPath = mount.containerMountPath()` (`/workspace/repo`); `readmeRef`/`manifestRefs` are **mount-relative paths** (D4), never host paths (T7).
  - [x] Depth/size cap: default top-level (e.g. depth 2) + a max-entry cap to bound bundle size (T9); honor a config key if added (T3).

- [x] **Task 4 — Extend `createForSpecInvestigation` to embed the summary (AC1, AC3)** (AC: #1, #3)
  - [x] Add a trailing **nullable** `RepositoryContextSummary repositoryContextSummary` parameter to `createForSpecInvestigation(...)` (`ContextBundleService.java:237`) and thread it into `assembleForSpecInvestigation(...)` (`:348`). Do **NOT** add a constructor dependency (T1/T2).
  - [x] In `assembleForSpecInvestigation`, when the summary is non-null, write the five fields onto `root` (after the existing fields, before/with `classification`); when null, write nothing (byte-identical baseline — AC1).
  - [x] Confirm the single existing `redact(root, SHAREABLE_REDACTED)` pass at `:292` covers the new text leaves; do not add a second redaction call.
  - [x] Update the two `new ContextBundleService(...)` test sites + the `createForSpecInvestigation` call site in `ContextBundleServiceSpecInvestigationTest` to pass `null` for the new param (no-repo baseline stays unchanged).

- [x] **Task 5 — Gated broker wiring (AC7, T1, T10, T14)** (AC: #7)
  - [x] Add a config-based `repositoryRef` resolver (D2): resolve the single configured repo key from `WorkflowProperties.repos` (1:1 pilot); return empty when none. Decide home (small helper in `application.runner.workspace` or a method on `WorkflowProperties`).
  - [x] In `RunnerBroker.dispatch(...)`, for `stage == INVESTIGATION` only: when `repositoryWorkspaceService != null` (the existing nullable field, `RunnerBroker.java:98`) AND a `repositoryRef` resolves, call `prepareWorkspace(workflowRunId, stage, reservedRexId, <ticketRef>, <ticketSummary>, repositoryRef)` then `summarize(...)` BEFORE the `createForSpecInvestigation(...)` call at `:390`; pass the summary in. Otherwise pass `null`.
  - [x] Thread the resolved `repositoryRef` onto the `RunnerDispatchRequest` (use the 9-arg ctor) so `DockerRunnerAdapter` mounts `/workspace/repo` (idempotent reuse of the broker's clone — T14) and the runner can read the referenced files. (`linearTicketSummary` optional.)
  - [x] Clone failure during composition surfaces as a `DomainException` on the existing dispatch bundle-rejection path (`RunnerBroker.java:408`) → dispatch fails → orchestration drives `Failed` (OQ-5). Do not swallow.

- [x] **Task 6 — Tests (AC11) + fixtures (AC9, AC10)** (AC: #9, #10, #11)
  - [x] Unit: extend `ContextBundleServiceSpecInvestigationTest` — repo-bearing composition (summary present → five fields present, validated), no-repo baseline (summary null → byte-identical), schema-rejection of a malformed repo bundle.
  - [x] Adversarial redaction: add fixtures under `deliveryline-backend/src/test/resources/redaction-fixtures/` (repo content embedding `lin_api_`, `ghp_`, `github_pat_`, `C:\Users\{name}\…`, `.env KEY=VALUE`, a PEM block) + matching `fixtures-manifest.json` entries (`forbiddenSnippets`); the `RedactionAdversarialFoundationContract` auto-enforces no-leak + no-silent-fixture.
  - [x] Contracts module: add `context-bundle.v1.spec-investigation-repo-context.valid.json`; keep bootstrap fixture valid; `RunnerContractValidatorTest` green.
  - [x] Integration (`github-mock` profile + local bare repo harness, mirroring `RepositoryWorkspaceServiceIT`): configured repo → `prepareWorkspace` clone → INVESTIGATION dispatch → bundle carries repo context → `getContextBundleForArtifact` returns the five fields → token/host-path never present.
  - [x] `GitCommandPort.listTopLevelTree` op test (`CliGitAdapterTest` / dedicated): depth limit, `.gitignore` respect, deterministic order.
  - [x] Fixture event stream: add a spec-stage repo-context entry (new file or `details` keys on a spec event) under `fixture-event-streams/` + `.md` sidecar + README row; extend `workflow-events-response.schema.json` `details` if needed; the three fixture contract tests stay green.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at: `RepositoryWorkspaceService.summarize` entry/exit (counts only), the broker's gated repo-context branch (decision taken: repo resolved / skipped), the new `GitCommandPort` tree-listing op, and the `createForSpecInvestigation` repo-context-present branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (repo resolved, summary built with `{treeEntryCount, manifestCount, readmePresent}`, fields embedded), `WARN` for recoverable anomalies (no repo resolved when one was expected, README/manifest absent, tree truncated by cap), `ERROR` only for unhandled failures. `DEBUG` for per-entry detail.
  - [x] Every log carries the relevant context keys: `correlationId`, `workflowRunId`, `runnerExecutionId`, plus `repositoryRef` and `contextBundleVersion`. Use MDC where available (the broker already opens `WORKFLOW_RUN_ID`/`RUNNER_EXECUTION_ID` scopes).
  - [x] **NEVER log** tree contents, README/manifest bodies, file paths beyond mount-relative names, GitHub tokens, or host absolute paths.
  - [x] Add at least one assertion (list-appender / `OutputCaptureExtension`) that the expected repo-context log line(s) are emitted at the expected level AND that a serialized log payload contains no token / host path.

## Dev Notes

### Decisions (made by this story; rationale)

- **D1 — Materialize-then-compose for the spec stage.** The bundle is composed before the runner clones; embedding real repo content requires the clone first. The broker (INVESTIGATION path) prepares the workspace (idempotent) and summarizes it before `createForSpecInvestigation`. The adapter's later clone is a no-op reuse. [OQ-2: ownership broker vs orchestration.]
- **D2 — `repositoryRef` is config-resolved for the pilot.** No Linear↔GitHub mapping exists (deferred 3.32/3.33). Resolve the single configured repo from `deliveryline.workflow.repos` (1:1). Resolve internally in the broker — do NOT widen `dispatch(...)`'s public signature (avoids fanning out all dispatch callers). `ticketRepositoryMappingVersion` is a config-derived marker (e.g. `config:<repoKey>@1`). [OQ-3, OQ-4.]
- **D3 — Summary derivation lives in `RepositoryWorkspaceService`, not `ContextBundleService`.** File/tree I/O stays in the workspace layer; `ContextBundleService` receives a plain record. This avoids injecting the profile-gated workspace bean into the unconditional `ContextBundleService` (T1) and keeps `ContextBundleService` pure.
- **D4 — Reference-by-mount-path, not embedded bodies.** `repositoryReadmeRef`/`packageManifestRefs` are mount-relative paths the runner reads off the read-write `/workspace/repo` mount (mirrors story 3.10 AC7 "refs not payloads"). Only the small `repositoryTreeSummary` is embedded (and redacted). This avoids bundle bloat and shrinks the persisted-secret surface. [OQ-1: architect may opt for embedded redacted content for a self-contained inspectable bundle.]
- **D5 — Additive optional schema patch, no version bump.** New fields are optional `properties` (not `required`), `schemaVersion` stays `const:1`. No-repo and execution-stage bundles still validate. Precedent: the `minItems` 1→0 in-place patch.
- **D6 — Reuse the single existing redaction pass.** The JSON walker at `ContextBundleService.java:292` already redacts all text leaves; no new redaction call. The persisted bundle carries only `/workspace/repo` + mount-relative paths — never host absolute paths (T7); the path redactors are a backstop, not the primary defense.
- **D7 — No inspection-service code change.** `getContextBundleForArtifact` returns raw persisted bytes verbatim; the new fields surface automatically.

### Open Questions (resolve with architect before/while implementing)

- **OQ-1 (headline):** Embed redacted README/manifest **content** in the bundle (self-contained inspectable bundle) vs **reference-by-mount-path** (D4, recommended)? The epic stub wording ("ref to redacted README content") is ambiguous; D4 reads it as a reference. If the inspectable/exportable bundle must survive workspace cleanup with content, switch to embedded-redacted-content and expand adversarial coverage to the bodies.
- **OQ-2:** Should the prepare-before-compose live in `RunnerBroker` (recommended — broker already holds the nullable `repositoryWorkspaceService` and owns composition) or in `WorkflowOrchestrationService.dispatchSpecGeneration` (story 3a-1, already in review — would reopen it)?
- **OQ-3:** `repositoryRef` sourcing for the pilot — single configured key under `deliveryline.workflow.repos` (recommended) vs a per-run field added to the dispatch/run model? Full per-ticket mapping is 3.32/3.33.
- **OQ-4:** `ticketRepositoryMappingVersion` format/semantics under the 1:1 config assumption (recommend a marker string `config:<repoKey>@1`).
- **OQ-5:** Clone failure during INVESTIGATION dispatch — surface as `DomainException` → dispatch fails → orchestration drives `Failed` (recommended, reuses `RunnerBroker.java:408` path). Confirm this is acceptable vs degrading to a no-repo bundle.
- **OQ-6:** Should execution-stage `create(...)` bundles ALSO carry repo fields now? Recommend NO — that is story 3.10's scope; 3a-2 is spec-investigation only.
- **OQ-7:** Tree-summary depth + max-entry cap defaults and whether they need a `deliveryline.workflow.*` config key (if added → both `application.yml` files, T3).

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T1 — Do NOT inject the profile-gated `RepositoryWorkspaceService` into the unconditional `ContextBundleService`** ([[unconditional-service-needs-profile-gate]]). It would break every `@SpringBootTest` tier lacking a `github-*` profile. Pass a plain `RepositoryContextSummary` param instead; the broker (which already holds the nullable service) owns the gating.
- **T2 — Do NOT add a constructor arg to `ContextBundleService`** ([[docker-adapter-ctor-dep-fans-out]], [[two-public-constructors-need-autowired]]). It has two public ctors (5-arg `@Autowired` + deprecated 4-arg); a new ctor dep fans out to every `new ContextBundleService(...)` test site and risks the JaCoCo/`@Autowired` context-startup trap. Add only a **method** parameter to `createForSpecInvestigation`.
- **T3 — Any new validated config key goes in BOTH `application.yml` files** ([[validated-config-needs-test-yaml]]; the test yaml shadows, not merges). `WorkflowProperties` uses the normalize-never-throw pattern (`WorkflowProperties.java:29`) so absent keys bind cleanly — keep that pattern for any addition.
- **T4 — Tree listing goes through the `GitCommandPort` SPI** ([[application-cannot-import-adapters]]); impl in `adapters.git.CliGitAdapter`. No `adapters..` import from `application..`.
- **T5 — New schema fields MUST be optional, not `required`** — else no-repo and execution-stage bundles fail validation and the whole fast tier breaks.
- **T6 — Redaction only through `RedactionPolicyService`** (`CREDENTIAL_DETECTION_MUST_STAY_IN_APPLICATION_SECURITY` ArchUnit). No bespoke regex in the new code. If a manifest-specific secret shape isn't caught, extend `SensitivePayloadAnalyzer` + the runner-contracts `redaction-policy.json` mirror together.
- **T7 — No host absolute paths in the bundle.** Use `RepositoryMount.containerMountPath()` (`/workspace/repo`) + mount-relative paths only. `RepositoryMount.repoHostPath()` is host-side — never serialize it.
- **T8 — Spec artifacts stay `pending`** ([[markavailable-has-no-production-caller]]); inspection-by-artifact resolves via the runner-execution link, not `status`, so the bundle is still inspectable.
- **T9 — Bound the tree summary** (depth + entry cap; `.gitignore` respect) so a large repo doesn't bloat the bundle past the validator's payload-size limit (`FILE_TOO_LARGE`).
- **T10 — Use the broker's existing lazy nullable `repositoryWorkspaceService`** (`RunnerBroker.java:98`, resolved via `ObjectProvider.getIfAvailable()` at `:155`, [[broker-orchestration-lazy-supplier]]) — do not add a new eager injection or a cycle.
- **T11 — Runner-contracts fixture corpus:** add the repo-bearing valid fixture; keep the bootstrap (no-repo) fixture valid; `RunnerContractValidatorTest` enforces.
- **T12 — Fixture event stream:** the `details` map is open but the schema may need new properties; the three fixture contract tests gate it; story 2.17 mirrors the stream into frontend `ArtifactView` fixtures.
- **T13 — Run gates via PowerShell** ([[rtk-hook-only-matches-bash]]) — the RTK hook corrupts the Bash tool. Foundation-gate tiers need Docker up; verify the schema/redaction-fixture changes via `-Pfoundation-gate` ([[verify-ci-fixes-in-clean-env]] / [[wsl-linux-ci-reproduction]] for the Testcontainers shape).
- **T14 — Idempotent clone:** broker `prepareWorkspace` → adapter `prepareWorkspace` must reuse the same `{rex}/repo` (story 3.9 AC3) — never double-clone. Note that clone-before-compose adds network I/O + a failure mode to the dispatch hot path (OQ-5).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - `RepositoryWorkspaceService.summarize` → `INFO` on entry + `INFO` on success with counts (`treeEntryCount`, `manifestCount`, `readmePresent`), `WARN` on truncation/absent README.
  - Broker INVESTIGATION repo-context branch → `INFO` "repo context resolved/skipped" decision with `repositoryRef`, `contextBundleVersion`.
  - New `GitCommandPort` tree-listing op (adapter) → `INFO`/`DEBUG`, redact every git line (CliGitAdapter already does).
  - `createForSpecInvestigation` → extend the existing `INFO` "ok" line (`ContextBundleService.java:330`) with `repoContextPresent` boolean.
- **Required context keys** (MDC or parameters): `correlationId`, `workflowRunId`, `runnerExecutionId`, `repositoryRef`, `contextBundleVersion`.
- **Forbidden in log output:** tree contents, file/README/manifest bodies, host absolute paths, GitHub/Linear tokens, secrets, raw PII. Route any uncertain content through the redaction path before logging.
- **Test contract:** new logging surfaces pinned by at least one focused test (list-appender or `OutputCaptureExtension`) including a negative assertion that no token / host path is serialized.

### Project Structure Notes

- Extend (do not duplicate): `ContextBundleService.createForSpecInvestigation` / `assembleForSpecInvestigation` (`application/runner/ContextBundleService.java:237,348`).
- Workspace summary: `application/runner/workspace/RepositoryWorkspaceService.java` (+ new `RepositoryContextSummary` record); tree op on `application/runner/workspace/spi/GitCommandPort.java` + `adapters/git/CliGitAdapter.java`.
- Schema: `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json` (+ contracts-module valid fixture).
- Broker seam: `application/runner/RunnerBroker.java:343-465` (INVESTIGATION branch at `:388`; nullable service field at `:98`; request build at `:456`).
- Config: `deliveryline.workflow.repos` already exists (`WorkflowProperties.java`); any new key → both `src/main/resources/application.yml` + `src/test/resources/application.yml`.
- Fixtures: `deliveryline-backend/src/test/resources/redaction-fixtures/` (+ manifest) and `.../fixture-event-streams/` (+ schema + README).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3a-2] (AC shape adapted from #Story-3.10 ACs 1–10); note repo fields are additive to story 2.8 AC3.
- Spec-stage bundle baseline: [Source: _bmad-output/implementation-artifacts/2-8-backend-specification-artifact-model-and-spec-stage-context-bundle.md] and `ContextBundleService.createForSpecInvestigation` (`:237`).
- Repository workspace: [Source: _bmad-output/implementation-artifacts/3-9-repository-workspace-service-git-clone-branch-management-commit-push.md]; `RepositoryWorkspaceService.RepositoryMount` (`repoHostPath`, `containerMountPath="/workspace/repo"`, `defaultBranch`, `branch`); `GitCommandPort` SPI; idempotent `prepareWorkspace` (AC3).
- Orchestration that dispatches the spec stage: [Source: _bmad-output/implementation-artifacts/3a-1-spec-stage-orchestration-dispatch-spec-generation.md]; broker routes `INVESTIGATION → createForSpecInvestigation` (`RunnerBroker.java:388`).
- Redaction: `RedactionPolicyService.redact(JsonNode, "shareable-redacted")`; 2.24 hardened patterns; adversarial fixture corpus + `RedactionAdversarialFoundationContract`. [Source: _bmad-output/implementation-artifacts/1-10-…md, 2-24-…md]
- Schema/validator: `context-bundle.v1.schema.json` (`additionalProperties:false`, `schemaVersion const:1`); `RunnerContractValidator` (`ValidationTarget.CONTEXT_BUNDLE`).
- Inspection: `WorkflowInspectionService.getContextBundleForArtifact` (verbatim scratch read); CLI `--include-context-bundle` in `adapters/cli/WorkflowCommands.java`.
- Linear↔GitHub mapping (deferred): stories 3.32/3.33; ADR `docs/adr/0004-spec-stage-orchestration.md` (`ticketRepositoryMappingVersion` 1:1 assumption).
- Fixture streams: `deliveryline-backend/src/test/resources/fixture-event-streams/` (story 1.23); consumed/mirrored by story 2.17.

## Change Log

| Date       | Version | Description                                                                 | Author |
| ---------- | ------- | --------------------------------------------------------------------------- | ------ |
| 2026-06-03 | 0.1     | Implemented all 6 tasks + Logging: additive v1 schema patch, GitCommandPort tree-listing SPI op + CliGitAdapter impl, RepositoryContextSummary derivation, createForSpecInvestigation repo-context extension, gated broker wiring, tests + fixtures. Status ready-for-dev → review. | Amelia (dev-story) |

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Stale-`.m2` artifact during local verification: running `mvnw -pl deliveryline-backend test` (without `-am`) resolved the **old** `deliveryline-runner-contracts` jar (pre-patch `context-bundle.v1.schema.json`), so the two repo-bearing unit tests failed contract validation (`additionalProperties:false` rejected the 5 new fields). Fixed by `mvnw -pl deliveryline-runner-contracts install` before backend-only runs. Not a code defect — CI builds the whole reactor; the `-am` focused run was always green.

### Completion Notes List

Implemented additively — **no-repo and non-INVESTIGATION dispatches stay byte-for-byte identical to the story-2.8 baseline** (proved by the fast Surefire tier staying at 744/0/11skip and the `bootstrapBundle...` test asserting none of the 5 fields appear when the summary is null).

- **Task 1 (AC2):** Added 5 optional `properties` (`repositoryWorkspaceRef` pattern `^/workspace/repo$`, `repositoryTreeSummary`, `repositoryReadmeRef` `[string,null]`, `packageManifestRefs`, `ticketRepositoryMappingVersion`) + 2 `$defs` (`repoTreeEntry`, `repoManifestRef`) to `context-bundle.v1.schema.json`. `additionalProperties:false`, `schemaVersion const:1`, and the `required` array are unchanged; `CONTEXT_BUNDLE_SCHEMA_VERSION` stays `1`. In-place backward-compatible v1 patch (no version bump) per the `minItems` precedent. **No Flyway migration** (max stays V11).
- **Task 2 (AC6/T4):** New `GitCommandPort.listTopLevelTree(repoDir, maxDepth)` SPI op + `RepoTreeEntry` record (SPI package); implemented in `CliGitAdapter` via `git ls-tree -r --name-only HEAD` — committed content only (`.gitignore`-respecting), depth-bounded in Java (files + ancestor dirs truncated to `maxDepth`), `TreeMap` for deterministic ascending order, paths routed through the adapter's existing redaction pass.
- **Task 3 (AC6/D3):** `RepositoryContextSummary` + `RepoManifestRef` project-owned records; `RepositoryWorkspaceService.summarize(mount, mappingVersion)` derives the tree (depth 2, 200-entry cap — `TREE_DEPTH_LIMIT`/`TREE_ENTRY_CAP`, **no config key added** to dodge the both-yaml burden T3), case-insensitive `README*` detection, manifest detection against the 7-name set (+`build.gradle.kts`→`build.gradle`), all as **mount-relative paths** off `/workspace/repo`. Plus `resolveConfiguredRepositoryRef()` (single-repo config resolver, D2) and static `configMappingVersion(repoRef)` → `config:<repoKey>@1`.
- **Task 4 (AC1/AC3, T1/T2):** Added a **trailing nullable `RepositoryContextSummary` method parameter** to `createForSpecInvestigation` (NOT a constructor dep — avoids the two-ctor/JaCoCo trap); threaded into `assembleForSpecInvestigation` which emits the 5 fields only when non-null (before `classification`), nothing when null. The single existing `redact(root, "shareable-redacted")` pass covers the new text leaves (no second redaction). `ok` log line extended with `repoContextPresent`.
- **Task 5 (AC7, T10/T14, OQ-5):** `RunnerBroker.dispatch` — for INVESTIGATION only, when the (nullable, ObjectProvider-injected) `repositoryWorkspaceService` is present AND `resolveConfiguredRepositoryRef()` resolves, calls `prepareWorkspace` (idempotent) + `summarize` **inside the try block** so a `GitCommandException` clone failure is mapped to a `DomainException` (`INTERNAL_ERROR`, git category in details — **no new error code**, dodging the three-sites manifest fan-out) and routes through the existing FAILED-idempotency dispatch-rejection path → orchestration drives Failed. The resolved `repositoryRef` is threaded onto the `RunnerDispatchRequest` via the 9-arg ctor (null on every other path → byte-identical request). **The broker needs NO new constructor dependency** — the config resolver lives on the already-injected `RepositoryWorkspaceService`.
- **Task 6 (AC9/10/11):** Unit (`ContextBundleServiceSpecInvestigationTest` +3: 5-field emission/validation, absent-README→null, malformed-repo-ref schema rejection; 7 existing call sites updated to pass `null`); broker/logging stubs updated (7th `any()` matcher). Adversarial redaction fixtures (`repo-context-tree-summary-with-secrets.json` with `ghp_`/`github_pat_`/`lin_api_`; `repo-context-readme-with-pem-and-path.md` with PEM block + `.env` value + `C:\Users\…` path) + manifest entries — auto-enforced by `RedactionAdversarialFoundationContract`. New runner-contracts valid fixture `context-bundle.v1.spec-investigation-repo-context.valid.json`. New git-backed `SpecStageRepoContextIT` (real `CliGitAdapter` + local bare repo): tree-op depth/`.gitignore`/order, summarize README+manifest detection + mount-relative paths, end-to-end compose with **no token / no host-path leak** + field-only log assertion. Fixture event stream: repo-context `details` on `happy-path-success.json` spec `artifact.draftCreated` event + 5 typed schema `details` properties + `.md` sidecar + README row.
- **Logging (AC12):** Field-only structured logs at `summarize` entry/ok (`treeEntryCount`/`manifestCount`/`readmePresent`/`truncated`, WARN on truncation/absent-README), broker repo-context resolved/skipped decision, the git tree-op (DEBUG), and `createForSpecInvestigation` (`repoContextPresent`). NEVER tree contents/bodies/tokens/host paths. Pinned by the IT log-appender assertion.

**Scope note on AC5 / AC11(e) (deliberate, documented):** AC5/D7 require **no inspection-service code change** — `getContextBundleForArtifact` returns the persisted bundle bytes **verbatim**, so the 5 fields surface automatically. The integration test (`SpecStageRepoContextIT`) proves the full path with the REAL `CliGitAdapter` clone → `summarize` → REAL `ContextBundleService` compose → REAL `RedactionPolicyService` + `RunnerContractValidator`, and asserts on **the exact bytes** that `getContextBundleForArtifact` would return. It does **not** stand up a `@SpringBootTest`-`github-mock` DB-inspection e2e because (a) that path has zero code change here, and (b) a true persisted-artifact-inspection e2e is constrained by the **`markAvailable`-has-no-production-caller** gap carried over from story 3a-1 (the spec artifact stays `pending`; full availability needs out-of-scope checksum/storageRef artifact code — see `[[markavailable-has-no-production-caller]]`). This mirrors the dormant-seam reality the story's Central Reconciliation describes.

**Gates (all green, via PowerShell — RTK corrupts the Bash tool):**
- Fast Surefire (no Docker): **744/0/0/11skip** — no regression; byte-identical baseline preserved.
- `-Pfoundation-gate verify` (Docker up): **30/0/1skip** — incl. Contract #9 RedactionPolicyService adversarial sweep (new repo-content fixtures), the 3 FixtureEventStream contract tests, Contract #1 ArchUnit boundaries (`REPOSITORY_WORKSPACE_SERVICE_SCOPE` + bundle-composition), Contract #5 RunnerContractValidator sweep (new valid fixture).
- Failsafe ITs (git on PATH): **13/0/0** — `SpecStageRepoContextIT` 4/0 + `RepositoryWorkspaceServiceIT` 9/0 (3.9 regression intact).
- `spotless:check` + `checkstyle:check`: **0 violations**. SpotBugs clean (ran in `verify`).
- Recommend `code-review` with a **different** LLM + a WSL2/Linux-CI confirm of the Docker-backed tiers (memory `[[wsl-linux-ci-reproduction]]` / `[[verify-ci-fixes-in-clean-env]]`).

### File List

**Production (main):**
- `deliveryline-runner-contracts/src/main/resources/schemas/context-bundle.v1.schema.json` (5 optional props + 2 $defs)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/spi/GitCommandPort.java` (new `listTopLevelTree` op)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/spi/RepoTreeEntry.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/git/CliGitAdapter.java` (`listTopLevelTree` impl)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepoManifestRef.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryContextSummary.java` (new)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java` (`summarize`, `resolveConfiguredRepositoryRef`, `configMappingVersion`, constants, README/manifest helpers)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/ContextBundleService.java` (trailing nullable param + `writeRepositoryContext`)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (gated repo-context prep + 9-arg dispatch request + GitCommandException→DomainException mapping)

**Tests + fixtures:**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/ContextBundleServiceSpecInvestigationTest.java` (+3 tests, 7 call sites updated)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` (stub matchers)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerLoggingContractTest.java` (stub matcher)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/SpecStageRepoContextIT.java` (new)
- `deliveryline-backend/src/test/resources/redaction-fixtures/repo-context-tree-summary-with-secrets.json` (new)
- `deliveryline-backend/src/test/resources/redaction-fixtures/repo-context-readme-with-pem-and-path.md` (new)
- `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json` (2 entries)
- `deliveryline-runner-contracts/src/test/resources/fixtures/valid/context-bundle.v1.spec-investigation-repo-context.valid.json` (new)
- `deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.json` (spec event repo-context details)
- `deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.md` (sidecar)
- `deliveryline-backend/src/test/resources/fixture-event-streams/README.md` (table row)
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (5 typed details props)

## Review Findings

_Code review 2026-06-03 (3 adversarial layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 decision-needed (D1→patch, D2→defer), 3 patch, 7 defer, 5 dismissed. Auditor verified all 12 ACs met; the traps T1/T2/T5/T7/T10/T14 and the 9-arg dispatch wiring confirmed genuinely respected._

- [x] [Review][Patch] **FIXED** — Unexpected RuntimeException in the new clone/summarize path leaks the idempotency reservation — `RunnerBroker.dispatch` reserves the idempotency key at `RunnerBroker.java:360`, then 3a-2 runs `prepareWorkspace` + `summarize` (git clone + I/O) inside the inner `try` (`:395-477`). That try completes the reservation `FAILED` only for `GitCommandException` and `DomainException`; there is no outer `catch` (only a `finally` ending MDC scopes at `:551`). An NPE / `IllegalStateException` / `IllegalArgumentException` from the new git path escapes uncaught → the idempotency record stays `RESERVED` forever → a later same-key dispatch may REPLAY to a lost rex or be blocked. **Decision (Alex): PATCH** — add `catch (RuntimeException)` to the inner try → complete reservation `FAILED` + rethrow as `INTERNAL_ERROR`. [HIGH] [`RunnerBroker.java:445-477`]
- [x] [Review][Defer] Manifest/README detection is silently bounded by the depth-2 tree cap — `RepositoryWorkspaceService.summarize` runs `detectReadme`/`detectManifests` over the depth-2-truncated tree (`RepositoryWorkspaceService.java:347,479,531,552`), so a manifest/README deeper than 2 segments (e.g. `modules/foo/pom.xml`) is collapsed to a `DIR` and never detected → incomplete `packageManifestRefs`/`repositoryReadmeRef` for monorepos. **Deferred (Alex): pilot scope** — acceptable for the 1:1 root-manifest pilot; nested-manifest detection lands with the full Linear↔GitHub mapping work (stories 3.32/3.33). [MED]
- [x] [Review][Patch] **FIXED** — `git ls-tree --name-only` octal-escapes non-ASCII paths (default `core.quotePath=true`) and there is no NUL-delimited parse — non-ASCII/space/quote filenames arrive as `"\303\251.txt"` and break README/manifest detection + garble the embedded tree; now runs `git -c core.quotePath=false ls-tree -r --name-only -z HEAD` and splits on NUL [`CliGitAdapter.java:330`] [LOW-MED]
- [x] [Review][Patch] **FIXED** — Dead null-check — `Objects.requireNonNull(mountPath, "mountPath")` ran after the `isBlank` guard already proved `mountPath` non-null; removed the redundant line + the now-unused `java.util.Objects` import [`RepositoryContextSummary.java`] [TRIVIAL]
- [x] [Review][Defer] Empty/unborn-HEAD repo → `git ls-tree HEAD` fails and is thrown as `GIT_NETWORK_FAILURE` (mislabel for a local read) and fails the whole spec dispatch [`CliGitAdapter.java:31-34`] — deferred; pilot repos always have commits, no `GIT_LOCAL` category exists (adding = three-sites fan-out)
- [x] [Review][Defer] Embedded-tree cap vs full-tree detection — when `fullTree > 200`, a README/manifest sorted past index 200 is referenced in the bundle but absent from the embedded `repositoryTreeSummary` [`RepositoryWorkspaceService.java:479-486`] — deferred; by Decision D4 refs are read off the `/workspace/repo` mount independent of the embedded tree, so the contract does not require them to co-occur
- [x] [Review][Defer] Multi-repo (`repos.size() > 1`) silently disables repo-context with an INFO-only `reason=no_repo_resolved` log [`RepositoryWorkspaceService.java:515`, `RunnerBroker.java:413-417`] — deferred; documented D2 1:1 pilot assumption (consider WARN-level when >1 repo is configured)
- [x] [Review][Defer] Adapter builds the full `TreeMap` of every truncated ancestor before any cap is applied — O(tracked-files) allocation in the dispatch hot path [`CliGitAdapter.java:43-67`] — deferred; depth-2 collapse bounds the map by distinct depth-2 prefixes, low real impact
- [x] [Review][Defer] `GitCommandException` is not chained as the `cause` of the remapped `DomainException` — git failure stack is lost (category preserved in `details`) [`RunnerBroker.java:464`] — deferred; minor debuggability nit
- [x] [Review][Defer] Schema lacks `maxItems` on `repositoryTreeSummary`; the 200-entry cap lives only in `summarize`, not the contract [`context-bundle.v1.schema.json:117`] — deferred; app enforces it, schema `maxItems` would couple the contract to the app cap
