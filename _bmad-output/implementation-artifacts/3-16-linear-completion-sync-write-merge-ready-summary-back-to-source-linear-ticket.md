# Story 3.16: Linear Completion Sync — Write Merge-Ready Summary Back to Source Linear Ticket

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Product Manager working in Linear (without opening the DeliveryLine UI),
I want the workflow to write a merge-ready completion summary back to the source Linear ticket when a governed run reaches `Completed`,
so that Linear-native users see the outcome in-place — closing the loop between Linear intake (story 1.14) and DeliveryLine governance — and the sync is best-effort, after-the-fact, redaction-enforced, and never blocks the governed flow.

## Context & Central Reconciliation (READ FIRST)

**This story is an out-of-slice pull from epic-3b (which remains `deferred`), exactly like the already-done 3-7/3-8/3-10/3-11/3-12/3-15.** Do not flip `epic-3b`'s status. Read `3-15-integration-link-service-extended-for-github-pr-linkage.md` first — it built the `github_pr` `integration_links` row whose `external_metadata.prUrl` this story reads, and it establishes the house discipline (Scope Boundary table, Decisions, Traps, three-sites discipline) you must follow.

**The single most important fact: the LinearAdapter port method this story needs ALREADY EXISTS.** The epic AC1 says "add a new port method `commentOnTicket(ticketRef, body)`." The live code already has a richer, purpose-built method — `LinearAdapter.postGovernedRunComment(String ticketRef, GovernedRunComment summary)` — implemented by **both** `LinearMockAdapter` (records to an in-memory `postedComments` log) and `LinearRealAdapter` (posts via the Linear GraphQL `commentCreate` mutation, with built-in **fingerprint-marker idempotency** that scans existing comments before posting). It currently has **ZERO production callers** (verified: only the port decl + the two adapter impls reference it). **Story 3.16 is its first real caller. REUSE it — do NOT add a thinner `commentOnTicket`.** This single reconciliation collapses epic ACs 1 (port method on both adapters), 5 (idempotent re-sync), and most of 2 (adapter expects a pre-redacted body) into "wire the existing method correctly."

**The second most important fact: the trigger site does not exist yet.** AC3 says the hook fires "when `WorkflowTransitionService` transitions a run to `Completed`." But **nothing transitions a run to `COMPLETED` today** — the only legal source is `WAITING_FOR_REVIEW → COMPLETED`, driven by `TechnicalApprovalService.acceptImplementation` which is **story 3.20 (backlog)**. So — exactly mirroring how 3.15 built the `ARTIFACT_PR_LINK_MISMATCH` guard before its 3.20 call-site — this story **BUILDS the generic post-commit hook + `syncCompletionToLinear` + the CLI manual path, and tests them by driving a `COMPLETED` transition directly in tests.** The hook fires automatically once 3.20 (or any future path) commits a `COMPLETED` transition; no rewiring needed then.

**STRUCTURAL FACTS (each verified against current code — confirm line numbers before editing, they drift):**

1. **`LinearAdapter.postGovernedRunComment(ticketRef, GovernedRunComment)` exists and is the write path.** Port: `application/integration/linear/LinearAdapter.java:54`. The payload record `GovernedRunComment(String runPublicId, String fingerprint, String body, DataClassification classification)` lives at `application/integration/linear/GovernedRunComment.java` (all fields required non-blank; `body` is **expected already-redacted** — "the adapter does not redact"). Mock impl records `postedComments` + a `PostedComment(ticketRef, comment)` test accessor (`adapters/integration/linear/LinearMockAdapter.java:101,117,147`). Real impl posts the `commentCreate` GraphQL mutation and **already dedups** via a `<!-- deliveryline:run=<id> fp=<fp> -->` marker scan over existing comments (`LinearRealAdapter.java:234`, marker at `:504`, GraphQL resource `resources/graphql/linear/post-comment.graphql`).

2. **The redaction + classification primitives exist (story 1.10).** `application/security/RedactionPolicyService.redact(String payload, String claimedClassificationValue) → RedactionResult` (also `Map`/`JsonNode` overloads). `RedactionResult(sanitizedText, sanitizedJson, claimedClassification, effectiveClassification, redacted, detectedCategories)`. `DataClassification` constants: `LOCAL_ONLY("local-only")`, `SHAREABLE_REDACTED("shareable-redacted")`, **`SHAREABLE_FULL("shareable-full")`**, `DERIVED_PUBLIC_SAFE("derived-public-safe")` (`domain/registry/DataClassification.java:5-31`). AC6 wants the body claimed `SHAREABLE_FULL` and a test proving the **effective** classification stays shareable even when source data was dirty (redaction sanitizes). Pass `DataClassification.SHAREABLE_FULL.value()`; send `result.sanitizedText()`.

3. **`WorkflowOrchestrationService` exists** (`application/workflow/WorkflowOrchestrationService.java`) — has the spec/plan/pr-output `dispatch*`/`on*StageSucceeded` methods; deps include `WorkflowTransitionService`, `WorkflowRunReadPort`, `RunnerExecutionRecordPort`, `ContextBundleService`, `RunnerBroker`. **This is where `syncCompletionToLinear(workflowRunId)` lives.**

4. **`WorkflowTransitionService.transition(runId, targetState, actor, reason, idempotencyKey, failureCategory, eventDetails)`** (`application/workflow/WorkflowTransitionService.java:52`) is `@Transactional`, validates against `WorkflowTransitionTable`, updates state via `WorkflowRunStatePort`, appends an event via `WorkflowEventWritePort`. `WorkflowState.COMPLETED` exists; the only legal source is `WAITING_FOR_REVIEW` (`WorkflowTransitionTable` ≈`:79`). **This is where the post-commit hook is registered.**

5. **The in-repo post-commit pattern is `TransactionSynchronizationManager.registerSynchronization(...)`.** Only `WorkflowCommandService.completeWhenTransactionFinishes` (`:379-394`) uses it — `afterCompletion(int status)` with Spring's `STATUS_COMMITTED`. **There is NO `@TransactionalEventListener` / `ApplicationEventPublisher` anywhere in main.** Mirror the `registerSynchronization` pattern. Because the sync runs **after commit**, the `Completed` transition is already durable — AC4 ("failure does not roll back completion") is satisfied structurally, not just by try/catch.

6. **Avoiding the orchestration↔transition cycle.** `WorkflowOrchestrationService` depends on `WorkflowTransitionService`. The hook needs `WorkflowTransitionService` to invoke `WorkflowOrchestrationService.syncCompletionToLinear` → a cycle. Resolve it the way the broker↔orchestration cycle is resolved in this repo: inject `ObjectProvider<WorkflowOrchestrationService>` and resolve it **lazily at `afterCommit` time** (never eagerly in the ctor) — [[broker-orchestration-lazy-supplier]]. By `afterCommit` the context is fully built, so `getIfAvailable()` is safe.

7. **The summary-template data sources (each must be resolved DEFENSIVELY — see Decision D5):**
   - `{runId}` → the `workflowRunId` param (always present).
   - `{prUrl}` → the **`github_pr` integration link's `external_metadata.prUrl`**, written by 3.15. Read via the typed `IntegrationLinkService.findActive...ByWorkflowRun` for `github_pr` (NOT the Linear variant). **May be absent** — 3.15's broker linkage is best-effort/swallow, so a run can reach completion with no `github_pr` row. Degrade gracefully.
   - `{ticketRef}` (the post target) → the **`linear_ticket` (constant `LINEAR_INTEGRATION_TYPE = "linear"`) integration link's `external_ref`** via `IntegrationLinkService.findActiveLinkByWorkflowRun`. If absent → there is no Linear ticket to post to → no-op + WARN (do NOT raise).
   - `{specSummary}` / `{specVersion}` → latest `SPEC` artifact (`ArtifactRecordPort.findLatestByWorkflowRunIdAndArtifactType(runId, SPEC)`) — `version()` is available; a human-readable summary requires reading the artifact payload/metadata (may not be trivially available — OQ-2).
   - `{pmReviewer}` / `{devReviewer}` → approvals by artifact type (PM = SPEC approval, Dev = PR_OUTPUT/plan approval) via the approval read port; defensively `unknown` if not found.
   - `{durationFormatted}` → cycle time = completion timestamp − first run event timestamp (read the run's event stream / created-at); format as a human duration.

**SCOPE BOUNDARY — what 3.16 BUILDS vs REUSES vs DEFERS:**

| Concern | This story (3.16) | Note / Deferred to |
|---|---|---|
| `WorkflowOrchestrationService.syncCompletionToLinear(workflowRunId)` — load ticket+PR links, compose summary, redact, post via existing adapter method | **BUILD** | — |
| Linear port write method (`commentOnTicket`) | **REUSE existing `postGovernedRunComment(ticketRef, GovernedRunComment)`** | epic AC1 reconciled — D1 |
| Mock fixture comment log + real GraphQL `commentCreate` + fingerprint idempotency | **REUSE (already built)** | covers AC1 mock-log, AC5 idempotent re-sync |
| Automatic post-commit hook on `COMPLETED` transition (generic, fires for any path) | **BUILD** (in `WorkflowTransitionService`, lazy `ObjectProvider<WorkflowOrchestrationService>`, gated on `enabled`) | trigger site (`acceptImplementation`) is **story 3.20** |
| `linear.completionSyncFailed` event on post failure (best-effort, no rollback) — AC4 | **BUILD** (new `WorkflowEventType` + detail keys + history-schema parity) | — |
| Redaction of summary body + `SHAREABLE_FULL` classification + classification test — AC2, AC6 | **BUILD** (reuse `RedactionPolicyService`) | — |
| Manual retry CLI `deliveryline sync-completion {runId}` (idempotent) — AC5 | **BUILD** (Spring Shell command in `WorkflowCommands`) | idempotency via the adapter fingerprint marker (D3) |
| Opt-out config `deliveryline.workflow.linear-completion-sync.enabled` (default `true`) + doctor reports it — AC7 | **BUILD** (`WorkflowProperties` nested record + doctor probe) | — |
| Template config `...linear-completion-sync.template` + invalid-template → `INVALID_COMPLETION_TEMPLATE` at startup — AC8 | **BUILD** (config field + startup validator bean + new DomainErrorCode three-sites) | — |
| ArchUnit: `postGovernedRunComment` only callable from `syncCompletionToLinear` + the CLI command — AC9 | **BUILD** (caller-restriction rule; safe — no other caller exists) | — |
| Test suite (happy path, redaction, no-rollback, manual retry, opt-out, invalid template, idempotent re-sync) — AC10 | **BUILD** | — |
| `docs/integrations/linear-completion-sync.md` — AC11 | **BUILD** (new `docs/integrations/` dir) | — |
| Flyway migration / new table | **NONE** (events + `integration_links` already exist) | — |
| New public-id prefix | **NONE** | — |
| `TicketSourceAdapter` rename (epic §3a-1/3a-2 line 645) | **DO NOT** — port is still `LinearAdapter` today | follow-up story |
| Real end-to-end Linear post against a live workspace | **DEFER** (mock-driven here; `linear-real`-gated) | pilot |

## Acceptance Criteria

> Verbatim from `epic-03-agent-execution.md` §"Story 3.16" (lines 322–334) with **binding clarifications** in **bold parentheticals** where the epic wording predates the live code (the already-existing port method, the not-yet-built 3.20 trigger).

1. **Given** a new `LinearAdapter` port method `commentOnTicket(ticketRef, body)` (added to the port shared by mock + real adapters from story 1.14), **Then** both implementations support it: `LinearMockAdapter` records the comment in its in-memory fixture log; `LinearRealAdapter` posts via Linear's GraphQL `commentCreate` mutation. **(RECONCILED — D1: the port ALREADY exposes `postGovernedRunComment(String ticketRef, GovernedRunComment summary)`, implemented on both adapters exactly as described (mock `postedComments` log; real `commentCreate` mutation). REUSE it; do NOT add a redundant `commentOnTicket`. The `GovernedRunComment.body` is the already-redacted summary, `fingerprint` drives idempotency (AC5), `classification` is `SHAREABLE_FULL` (AC6).)**

2. **Given** redaction-on-egress, **Then** the comment body passes through `RedactionPolicyService` (story 1.10) before sending — a contract test asserts no secret patterns reach Linear. **(Compose the body, then `redactionPolicyService.redact(body, DataClassification.SHAREABLE_FULL.value())`; send `result.sanitizedText()` into `GovernedRunComment.body`. The real adapter explicitly "does not redact" — redaction MUST happen in `syncCompletionToLinear`. Contract test: seed a body containing a secret pattern, assert the sanitized text reaching the (mock) adapter is scrubbed.)**

3. **Given** `WorkflowOrchestrationService` extension (`syncCompletionToLinear`), **When** `WorkflowTransitionService` transitions a run to `Completed`, **Then** an automatic post-commit hook calls `syncCompletionToLinear(workflowRunId)` which: (a) loads the linked Linear ticket reference from `integration_links` of type `linear_ticket`, (b) loads the linked GitHub PR reference from `integration_links` of type `github_pr`, (c) composes a documented summary template ("DeliveryLine governed run `{runId}` completed: PR `{prUrl}` ready for merge. Spec: `{specSummary}` (v{specVersion}). Reviewers: PM `{pmReviewer}`, Dev `{devReviewer}`. Cycle time: `{durationFormatted}`."), (d) calls `LinearAdapter.commentOnTicket(ticketRef, body)`. **(The hook is registered in `WorkflowTransitionService` via `TransactionSynchronizationManager.registerSynchronization` (Fact 5) firing in `afterCommit` only when `targetState == COMPLETED` AND `enabled`; it resolves `WorkflowOrchestrationService` via lazy `ObjectProvider` to avoid the cycle (Fact 6). The trigger that drives `→COMPLETED` is story 3.20 — test the hook by driving a `WAITING_FOR_REVIEW → COMPLETED` transition directly. "calls `commentOnTicket`" → calls `postGovernedRunComment` (D1). Resolve every `{placeholder}` defensively — D5.)**

4. **Given** failure handling, **When** Linear comment posting fails (network, auth, rate limit), **Then** the failure is recorded as a `linear.completionSyncFailed` event with `failureCategory` per registry — but does NOT roll back the `Completed` transition; completion sync is an after-the-fact best-effort notification, never blocking governed flow. **(Add `WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED("linear.completionSyncFailed")`. `failureCategory` ← `IntegrationFailureCategory` (the integration-scoped enum), e.g. map the caught `LinearAdapterException.category()`; add a new `IntegrationFailureCategory.LINEAR_COMPLETION_SYNC_FAILED("linear_completion_sync_failed")` if no existing value fits, OR reuse `SYNC_FAILURE`/`NETWORK_API_FAILURE`/`LINK_FAILURE` from the thrown exception — D6. The event MUST be written in its OWN transaction (the sync runs post-commit; recording the failure cannot and must not touch the completed run's tx). No-rollback is structural — the hook runs after commit.)**

5. **Given** retry on transient failure, **Then** `syncCompletionToLinear` may be re-invoked manually via CLI (`deliveryline sync-completion {runId}`) — idempotent via Linear's comment-deduplication or a documented client-side fingerprint check (don't post the same canonical summary twice). **(REUSE the real adapter's existing fingerprint-marker dedup (Fact 1). Provide a STABLE `GovernedRunComment.fingerprint` = a deterministic hash of the canonical (pre-redaction) summary body (D3) so identical inputs → no-op; the mock's `postedComments` lets the CLI/unit test assert single-post. No `IdempotencyService` needed — AC5 explicitly permits the fingerprint approach, which is already built.)**

6. **Given** classification per story 1.10, **Then** the summary body is classified `shareable-full` (no secrets, no local paths, only shareable identifiers) — and a test asserts this classification holds even when the source data contains local paths or secrets that should have been redacted upstream. **(Claim `SHAREABLE_FULL`; the test feeds a deliberately dirty `{specSummary}`/`{prUrl}` (embedded secret or `/abs/local/path`) and asserts (a) the sanitized body sent to the adapter is scrubbed and (b) `result.effectiveClassification()` is still shareable — i.e. redaction kept it postable rather than the dirty data leaking. Assert via `RedactionResult.effectiveClassification()`.)**

7. **Given** opt-out configuration, **Then** `application.yml` `deliveryline.workflow.linear-completion-sync.enabled` (default `true`) lets pilots disable completion-sync without code changes; doctor (story 1.16) reports current setting. **(Add a nested record `LinearCompletionSync(boolean enabled, String template)` to `WorkflowProperties` (record, normalize-never-throw compact ctor + `defaults()`). The hook checks `enabled` before firing. Doctor: add a `probeLinearCompletionSync()` reporting enabled/disabled + template validity — mind the probe fan-out, Trap T6. MUST mirror the new keys in `src/test/resources/application.yml` — Trap T5.)**

8. **Given** message-template configuration, **Then** the summary template lives in `application.yml` `deliveryline.workflow.linear-completion-sync.template` (with documented placeholder variables: `{runId}`, `{prUrl}`, `{specSummary}`, etc.) — pilots can customize without code; an invalid template (missing required variable) raises `INVALID_COMPLETION_TEMPLATE` at startup. **(Add `DomainErrorCode.INVALID_COMPLETION_TEMPLATE` (three-sites — Trap T4). The compact ctor of `WorkflowProperties` must NOT throw (it normalizes — house rule, story 3.9 D8); instead add a startup validator `@Bean` (in `WorkflowConfiguration` or a new `LinearCompletionSyncConfiguration`) that, when `enabled`, validates the template and throws `DomainException(INVALID_COMPLETION_TEMPLATE, ...)` — failing context startup. Define the "required"/"known" placeholder set explicitly — D4. Ship a VALID default template so the default profile + every `@SpringBootTest` boots.)**

9. **Given** ArchUnit + scope, **Then** this is the only path that writes to Linear — runner CLIs (Codex/Claude) never post to Linear directly; preserving the "Linear is intake + completion sync" narrow boundary. Verified by an ArchUnit rule that `LinearAdapter.commentOnTicket` may only be invoked from `WorkflowOrchestrationService.syncCompletionToLinear` and the CLI `sync-completion` command. **(Rule targets `postGovernedRunComment` (D1). Add to `org.dradgo.architecture.ArchitectureRuleCatalog` mirroring `ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS` (`:376-403`): `noClasses().that().doNotHaveFullyQualifiedName(WorkflowOrchestrationService) .and().doNotHaveFullyQualifiedName("org.dradgo.adapters.cli.WorkflowCommands").should().callMethod(LinearAdapter.class, "postGovernedRunComment", String.class, GovernedRunComment.class)`. Safe — `postGovernedRunComment` has zero callers today. ArchUnit runs in Failsafe — Trap T9.)**

10. **Given** the test suite, **Then** tests cover: happy-path completion sync after `Completed` transition, redaction of summary body, failure does not roll back completion, manual retry via CLI, opt-out config disables sync, invalid template rejected at startup, idempotent re-sync (no duplicate Linear comments). **(Unit tests on `WorkflowOrchestrationService.syncCompletionToLinear` (mock adapter + read ports + `RedactionPolicyService`); a focused test driving a real `WAITING_FOR_REVIEW → COMPLETED` transition asserting the hook fired post-commit; validator unit test for invalid template; CLI command test; logging contract test. A `@SpringBootTest`+Testcontainers test, if added, MUST be `*IT` — Trap T7.)**

11. **Given** documentation, **Then** `docs/integrations/linear-completion-sync.md` documents the feature, the default template, customization, opt-out, and security posture (best-effort, after-the-fact, redaction enforced). **(New `docs/integrations/` directory; match the `docs/cli/doctor.md` heading style. Link it from `docs/cli/README.md` if an index exists.)**

**Logging instrumentation** (cross-cutting; see task below) — `INFO` on `syncCompletionToLinear` entry + post success + hook-fired; `WARN` on missing ticket/PR link, redaction downgrade, opt-out-skip, and Linear post failure (with the error/failure category); `ERROR` only on unexpected. Carry `correlationId`, `workflowRunId`, the (non-secret) `ticketRef`/`prUrl`. **Never** log the Linear token, the full PR body, or any redacted-away field; sanitize free-text via `MdcKeys.sanitizeForLog`.

## Tasks / Subtasks

- [x] **Task 1 — `WorkflowOrchestrationService.syncCompletionToLinear(String workflowRunId)`** (AC: #2, #3, #5, #6)
  - [x] Add `@Transactional public void syncCompletionToLinear(String workflowRunId)`. Resolve the `linear_ticket` link (`IntegrationLinkService.findActiveLinkByWorkflowRun`) → ticketRef; if absent, `WARN` + return (no ticket to post to).
  - [x] Resolve the `github_pr` link's `external_metadata.prUrl` (typed `github_pr` read — NOT the Linear variant) defensively; resolve `{specSummary}`/`{specVersion}` (latest `SPEC` artifact), `{pmReviewer}`/`{devReviewer}` (approvals by artifact type), `{durationFormatted}` (completion − first-event cycle time). Each unresolved value renders as a documented fallback (D5).
  - [x] Render the configured template (`WorkflowProperties.linearCompletionSync().template()`); compute a stable `fingerprint` = deterministic hash of the rendered pre-redaction body (D3).
  - [x] `redactionPolicyService.redact(body, DataClassification.SHAREABLE_FULL.value())`; build `GovernedRunComment(workflowRunId, fingerprint, result.sanitizedText(), SHAREABLE_FULL)`; call `linearAdapter.postGovernedRunComment(ticketRef, comment)`.
  - [x] Wrap the post in try/catch: on `LinearAdapterException` (or any failure) → Task 3 failure event; never rethrow past the hook (best-effort).
  - [x] **Constructor change** ([[docker-adapter-ctor-dep-fans-out]] / [[two-public-constructors-need-autowired]]): inject `LinearAdapter` (via `ObjectProvider`/lazy — it's `@Profile(linear-mock|linear-real)`-gated, so a direct dep reds every `@SpringBootTest` lacking the profile — [[unconditional-service-needs-profile-gate]]), `RedactionPolicyService`, `WorkflowProperties`, the approval/artifact read ports, and `WorkflowEventWritePort`. Keep exactly one `@Autowired` ctor; thread new deps through any secondary/test ctor. Update every `new WorkflowOrchestrationService(...)` site.

- [x] **Task 2 — Post-commit hook on `COMPLETED` transition** (AC: #3, #4)
  - [x] In `WorkflowTransitionService.transition(...)`, when `targetState == WorkflowState.COMPLETED` and `linearCompletionSync.enabled()`, register a `TransactionSynchronization` (mirror `WorkflowCommandService.completeWhenTransactionFinishes` `:379-394`) whose `afterCommit()` resolves `WorkflowOrchestrationService` via the lazy `ObjectProvider` (Fact 6, [[broker-orchestration-lazy-supplier]]) and calls `syncCompletionToLinear(runId)`. Swallow + `WARN` on any throw from the hook (afterCommit cannot roll back; AC4).
  - [x] Inject `ObjectProvider<WorkflowOrchestrationService>` + `WorkflowProperties` (for the `enabled` gate) into `WorkflowTransitionService`. **Re-anchor any checkstyle line-anchored suppression** that shifts ([[checkstyle-suppressions-line-anchored]], Trap T10).

- [x] **Task 3 — `linear.completionSyncFailed` event + detail keys** (AC: #4)
  - [x] Add `WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED("linear.completionSyncFailed")` (`domain/registry/WorkflowEventType.java`, before the final constant).
  - [x] Append the event in its **own** transaction (the sync is post-commit) via `WorkflowEventWritePort.append(new WorkflowEventRecord(...))` with `ActorType.SYSTEM`, the `failureCategory` (D6), and detail keys.
  - [x] Add detail keys to `WorkflowEventDetailKeys` + the `ALLOW_LISTED_KEYS` set + history schema (`resources/schemas/cli/workflow-history.v1.schema.json` `details.properties`). `ERROR_CODE` and `failureCategory` likely already exist — reuse; add `TICKET_REFERENCE` and `FAILURE_REASON` (Trap T8). Keep the `WorkflowEventDetailKeysContractTest` parity green.

- [x] **Task 4 — `INVALID_COMPLETION_TEMPLATE` (three-sites) + startup validator** (AC: #8)
  - [x] Add `DomainErrorCode.INVALID_COMPLETION_TEMPLATE` + `ProblemDetailsCatalog` entry (`HttpStatus.BAD_REQUEST`, non-retryable — mirror `INVALID_COMMAND_PAYLOAD`) + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` entry ([[new-domainerrorcode-three-sites]], Trap T4). Verify `-Pfoundation-gate`.
  - [x] Add a startup validator `@Bean` (in `WorkflowConfiguration` or a new `LinearCompletionSyncConfiguration`) that, when `enabled`, parses the template's `{...}` tokens and throws `DomainException(INVALID_COMPLETION_TEMPLATE, ...)` if a required placeholder is missing or an unknown placeholder appears (D4). Do NOT throw from the `WorkflowProperties` compact ctor.

- [x] **Task 5 — Config: `WorkflowProperties.LinearCompletionSync` + yaml (main + test)** (AC: #7, #8)
  - [x] Add nested record `LinearCompletionSync(boolean enabled, String template)` to `WorkflowProperties` (`application/workflow/WorkflowProperties.java`) with a normalize-never-throw compact ctor + a `defaults()` supplying a VALID default template; update `WorkflowProperties` root record + `WorkflowProperties.defaults()` (the latter is used in `DoctorProbeAdapter` fallbacks — Fact, `:191/225/290/337`).
  - [x] Add `deliveryline.workflow.linear-completion-sync.{enabled,template}` to `src/main/resources/application.yml` AND mirror in `src/test/resources/application.yml` ([[validated-config-needs-test-yaml]], Trap T5).

- [x] **Task 6 — Doctor probe** (AC: #7)
  - [x] Add `DoctorProbePort.probeLinearCompletionSync()` + impl in `DoctorProbeAdapter` (reads `workflowProperties.linearCompletionSync()`; PASS with enabled/disabled + template-valid; report invalid template). Add a `CHECK_LINEAR_COMPLETION_SYNC` constant + `STATIC_ORDER` entry + `runSingleProbe` switch case in `DoctorService`.
  - [x] **Probe fan-out** ([[new-doctor-probe-fans-out]], Trap T6): stub the new probe in `DoctorServiceTest`/`DoctorLoggingContractTest` everywhere the full set runs; bump the hardcoded `checksRun=N` count; thread any new ctor seam through `DoctorProbeAdapter`'s overload chain (`:130/238/314`).

- [x] **Task 7 — CLI `deliveryline sync-completion {runId}`** (AC: #5)
  - [x] Add a `@Command(name = "sync-completion", ...)` method to `org.dradgo.adapters.cli.WorkflowCommands` (mirror `submit`): `@Argument` runId + `--correlation-id`; push correlation scope; call `workflowOrchestrationService.syncCompletionToLinear(runId)` (inject it if not present); print a single-line result; `emitSuccess`/`emitFailure` + `WorkflowCliExitStatusExceptionMapper`.

- [x] **Task 8 — ArchUnit caller-restriction** (AC: #9)
  - [x] Add `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` to `ArchitectureRuleCatalog` (see AC9 clarification). Verify via Failsafe ([[archunit-runs-in-failsafe-not-surefire]], Trap T9).

- [x] **Task 9 — Docs** (AC: #11)
  - [x] Create `docs/integrations/linear-completion-sync.md` (feature, default template + placeholders, customization, opt-out, security posture). Match `docs/cli/doctor.md` style.

- [x] **Task 10 — Tests** (AC: #10)
  - [x] `WorkflowOrchestrationService` unit tests: happy path (mock adapter records one comment); redaction scrubs a seeded secret (AC2); `SHAREABLE_FULL` effective-classification holds on dirty source (AC6); missing ticket/PR link degrades (WARN, no throw); Linear failure → `linear.completionSyncFailed` event + completion intact (AC4); idempotent re-sync (same fingerprint → mock posts once) (AC5).
  - [x] Hook test: drive `WAITING_FOR_REVIEW → COMPLETED` via `WorkflowTransitionService`; assert `syncCompletionToLinear` fired after commit; assert opt-out (`enabled=false`) suppresses it (AC7).
  - [x] Validator unit test: invalid/missing-placeholder template → `INVALID_COMPLETION_TEMPLATE` (AC8).
  - [x] CLI command test (AC5); doctor probe tests + checksRun bump (AC7); logging contract test for the new surfaces.

- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J + MDC on `syncCompletionToLinear` entry/success, the hook firing, and every WARN branch (missing link, redaction downgrade, opt-out skip, post failure). Parameterized logging only; levels per the Logging Requirements block.
  - [x] Context keys `correlationId`, `workflowRunId`, non-secret `ticketRef`/`prUrl` (sanitize via `MdcKeys.sanitizeForLog`). Never log token / PR body / redacted fields.
  - [x] Pin each new surface with a focused list-appender (or `OutputCaptureExtension`) assertion.

## Dev Notes

### THE references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **The write method to REUSE** | `LinearAdapter.postGovernedRunComment` (`application/integration/linear/LinearAdapter.java:54`); `GovernedRunComment` record; `LinearMockAdapter` (`:101,117,147`); `LinearRealAdapter` (`:234`, marker `:504`) | the port method + idempotency + mock log already exist — D1. Do not add `commentOnTicket`. |
| **Redaction + classification** | `application/security/RedactionPolicyService.redact(String, String)` → `RedactionResult`; `DataClassification.SHAREABLE_FULL` | AC2/AC6 — claim `SHAREABLE_FULL`, send `sanitizedText()`, assert `effectiveClassification()`. |
| **The service to extend** | `application/workflow/WorkflowOrchestrationService.java` (`on*StageSucceeded` shape, ctor, deps) | `syncCompletionToLinear` lives here. |
| **The transition + hook** | `WorkflowTransitionService.transition` (`:52`); `WorkflowCommandService.completeWhenTransactionFinishes` (`:379-394`, the `registerSynchronization` pattern); `WorkflowTransitionTable` (`COMPLETED` from `WAITING_FOR_REVIEW`, `≈:79`) | register the post-commit hook on `targetState==COMPLETED`. |
| **Cycle-break** | the broker↔orchestration lazy `ObjectProvider`/`Supplier` precedent | [[broker-orchestration-lazy-supplier]] — resolve orchestration lazily in `afterCommit`. |
| **Integration link reads** | `IntegrationLinkService.findActiveLinkByWorkflowRun` (Linear) + the typed `github_pr` read added by 3.15; `external_metadata.prUrl` written by 3.15 | sources of `{ticketRef}` and `{prUrl}`. |
| **Event + keys** | `WorkflowEventType` (add `LINEAR_COMPLETION_SYNC_FAILED`), `WorkflowEventDetailKeys` (+ allow-list + `workflow-history.v1.schema.json`), `WorkflowEventDetailKeysContractTest` | AC4 failure event + parity. |
| **failureCategory** | `domain/registry/IntegrationFailureCategory.java` (integration-scoped; `SYNC_FAILURE`/`LINK_FAILURE`/`NETWORK_API_FAILURE` exist) | D6 — map the `LinearAdapterException.category()`. |
| **Three-sites** | `DomainErrorCode` (add `INVALID_COMPLETION_TEMPLATE`), `ProblemDetailsCatalog` (BAD_REQUEST, non-retryable), `registry-api-schema-placeholders.json` | AC8 — [[new-domainerrorcode-three-sites]]. |
| **Config** | `WorkflowProperties.java` (record, `defaults()`); `WorkflowConfiguration` (`@EnableConfigurationProperties(WorkflowProperties.class)`); `application.yml` + `src/test/resources/application.yml` | AC7/AC8 nested `LinearCompletionSync`. |
| **Doctor** | `DoctorService` (`STATIC_ORDER`, `runSingleProbe`), `DoctorProbePort`, `DoctorProbeAdapter` (overload chain `:130/238/314`, `probeGitBotIdentity` shape) | AC7 — [[new-doctor-probe-fans-out]]. |
| **CLI** | `adapters/cli/WorkflowCommands.java` (`submit` `:146-201`, `@CommandGroup` `:52-54`, `emitSuccess/Failure`, exit mapper) | AC5 — Spring Shell command. |
| **ArchUnit** | `ArchitectureRuleCatalog.ONLY_ORCHESTRATION_AUTO_ADVANCES_ON_SPEC_RUNNER_SUCCESS` (`:376-403`); the existing `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (`:577`) | AC9 — `callMethod` caller-restriction. |
| **House discipline** | `3-15-…-github-pr-linkage.md` (Scope table, Decisions, Traps, three-sites) | the build-now/defer-trigger-to-3.20 pattern. |

### Decisions (made by this story; rationale)

- **D1 — REUSE `postGovernedRunComment`, do NOT add `commentOnTicket`.** The port already exposes the richer, purpose-built method on both adapters, with mock-log + GraphQL `commentCreate` + fingerprint idempotency, and zero existing callers. Adding a second, thinner method would duplicate function and split the ArchUnit boundary. The epic's `commentOnTicket(ticketRef, body)` is the same operation at a lower fidelity — subsumed.
- **D2 — Build the generic post-commit hook now; its production `→COMPLETED` trigger lands in story 3.20.** Mirrors 3.15's "build the guard, defer the call-site." The hook fires for ANY committed `COMPLETED` transition, so 3.20 needs no rewiring; tests drive the transition directly.
- **D3 — Idempotency via the adapter's existing fingerprint marker, no `IdempotencyService`.** Provide a stable `fingerprint` = deterministic hash of the canonical pre-redaction body. AC5 explicitly permits the fingerprint approach; it is already implemented in `LinearRealAdapter` and observable via the mock's `postedComments`. A changed summary (e.g. a refreshed PR URL) yields a new fingerprint → a new comment, which is acceptable progress signalling.
- **D4 — Template validation: known + required placeholder set, validated at startup (not in the config ctor).** Known placeholders: `{runId}`, `{prUrl}`, `{specSummary}`, `{specVersion}`, `{pmReviewer}`, `{devReviewer}`, `{durationFormatted}`. **Required**: at minimum `{runId}` (recommend also `{prUrl}`). Invalid = a missing required placeholder OR an unknown `{token}`. The `WorkflowProperties` compact ctor must not throw (house rule); a startup `@Bean` validator throws `DomainException(INVALID_COMPLETION_TEMPLATE)` failing context boot. The default template is valid so default/test contexts boot.
- **D5 — Defensive placeholder resolution; never fail the sync for a missing optional datum.** Completion sync is best-effort (AC4). A missing `{prUrl}` (no `github_pr` link — 3.15 swallows linkage failures), unresolved reviewer, or absent spec summary renders as a documented fallback (empty or `n/a`); only a missing `linear_ticket` link short-circuits (nothing to post to) with a WARN.
- **D6 — `failureCategory` from `IntegrationFailureCategory`.** Map the caught `LinearAdapterException.category()` directly (it is already an `IntegrationFailureCategory`: `LINK_FAILURE`/`NETWORK_API_FAILURE`/`SYNC_FAILURE`/`STATE_CONFLICT`). Add a dedicated `LINEAR_COMPLETION_SYNC_FAILED` value only if you want a single rolled-up category in the event detail (OQ-3) — prefer passing the adapter's own category through for fidelity.
- **D7 — No Flyway, no new public-id prefix, no transition-table change.** Events + `integration_links` cover all persistence; the `COMPLETED` transition is already legal.

### Open Questions (each carries a recommendation — proceed unless the architect objects)

- **OQ-1 — Hook location: `WorkflowTransitionService` vs an ApplicationEvent listener.** There is no `@TransactionalEventListener`/`ApplicationEventPublisher` in main today; the only post-commit precedent is `registerSynchronization` inside `WorkflowCommandService`. **Recommend** registering the synchronization inside `WorkflowTransitionService.transition` (single, authoritative transition site) with a lazy `ObjectProvider<WorkflowOrchestrationService>`. If the architect prefers decoupling, introduce a `WorkflowRunCompletedEvent` + `@TransactionalEventListener(phase = AFTER_COMMIT)` listener — but that adds a pattern the codebase doesn't yet use.
- **OQ-2 — `{specSummary}` source.** `specVersion` is trivially `artifact.version()`, but a human-readable spec *summary* may require reading the SPEC artifact payload/metadata, which is not obviously exposed on the read port. **Recommend** sourcing it from the artifact's stored summary/title field if one exists, else a short fixed string (`"see DeliveryLine"`) and dropping `{specSummary}` from the *required* set (D4). Confirm what the SPEC artifact exposes.
- **OQ-3 — Single `LINEAR_COMPLETION_SYNC_FAILED` failure category vs pass-through.** **Recommend** pass-through of the adapter's `IntegrationFailureCategory` (D6) for fidelity; add the rolled-up value only if a dashboard needs to filter all completion-sync failures by one category.

### Traps (wiring hazards — each maps to a memory or a verified code fact)

- **T1 — Do NOT add `commentOnTicket`** — the port already has `postGovernedRunComment` (D1). Adding a parallel method duplicates the write path and breaks the AC9 single-boundary claim.
- **T2 — Redaction is the SERVICE's job, not the adapter's.** `LinearRealAdapter` explicitly does not redact; `syncCompletionToLinear` MUST call `RedactionPolicyService` before constructing `GovernedRunComment` (AC2).
- **T3 — Orchestration↔transition cycle** — inject `WorkflowOrchestrationService` into the hook via **lazy** `ObjectProvider`, resolved at `afterCommit` time, never eagerly ([[broker-orchestration-lazy-supplier]], Fact 6).
- **T4 — New `DomainErrorCode` → three sites** ([[new-domainerrorcode-three-sites]]) for `INVALID_COMPLETION_TEMPLATE`; verify `-Pfoundation-gate`.
- **T5 — Validated-config / test-yaml** ([[validated-config-needs-test-yaml]]) — mirror the new `linear-completion-sync` keys in `src/test/resources/application.yml`; ship a VALID default template or every `@SpringBootTest` reds at startup (the validator throws on a bad template).
- **T6 — Doctor probe fan-out** ([[new-doctor-probe-fans-out]]) — stub `probeLinearCompletionSync` in `DoctorServiceTest`/`DoctorLoggingContractTest`, bump `checksRun=N`, thread any ctor seam through the `DoctorProbeAdapter` overload chain.
- **T7 — `*IT` naming** for any `@SpringBootTest`+Testcontainers test ([[springboot-testcontainers-test-must-be-IT]]); add `@Tag("docker-runner-it")` only if it should leave default `verify` ([[docker-it-needs-exact-docker-runner-it-tag]]). Most of this story's tests are plain unit tests (mock the port — no profile, no Docker, Trap-free).
- **T8 — Event-detail allow-list + history-schema parity** — new keys absent from `ALLOW_LISTED_KEYS` are stripped from CLI history, and the `WorkflowEventDetailKeysContractTest` enforces key↔schema parity. Add `TICKET_REFERENCE`/`FAILURE_REASON` to both; reuse existing `ERROR_CODE`/`failureCategory`.
- **T9 — ArchUnit runs in Failsafe, not Surefire** ([[archunit-runs-in-failsafe-not-surefire]]) — a new `@ArchTest`/catalog rule reports 0 under `mvnw test`; verify via `failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- **T10 — Checkstyle line-anchored suppressions** ([[checkstyle-suppressions-line-anchored]]) — edits to `WorkflowTransitionService`/`WorkflowOrchestrationService`/`RunnerBroker` that shift a suppressed forbidden-call line need the `lines="N"` re-anchored.
- **T11 — `linear-mock`/`linear-real` profile gating** ([[unconditional-service-needs-profile-gate]]) — `LinearAdapter` is profile-gated; inject it into `WorkflowOrchestrationService` via `ObjectProvider` and resolve `getIfAvailable()` at the post site (surface a typed/WARN no-op if absent), or every profile-less `@SpringBootTest` reds. Unit tests mock the port directly — no profile needed.
- **T12 — `application/...` cannot import `org.dradgo.adapters..`** ([[application-cannot-import-adapters]]) — `LinearAdapter`/`GovernedRunComment` are `application.integration.linear` types (reachable); never reach `adapters.integration.linear.LinearRealAdapter`.
- **T13 — After-commit means no rollback is even possible.** Don't wrap the sync in a way that re-enters the completed run's transaction; record the failure event in a fresh tx (AC4).
- **T14 — Run gates via PowerShell** ([[rtk-hook-only-matches-bash]]); verify Docker-backed tiers / foundation gate in a clean env / WSL2 ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. Enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`. ADR `0019-structured-logging` governs format.
- **Surface:** `WorkflowOrchestrationService.syncCompletionToLinear`, the `WorkflowTransitionService` completion hook, the CLI `sync-completion` command. `INFO` lifecycle (sync entry/success, hook fired), `WARN` recoverable (missing ticket/PR link, redaction downgrade, opt-out skip, Linear post failure with category), `ERROR` only unexpected.
- **Required context keys:** `correlationId`, `workflowRunId`; non-secret `ticketRef`/`prUrl` permitted (sanitize via `MdcKeys.sanitizeForLog`).
- **Forbidden:** Linear API token, full PR/comment body, any field the redaction pass removed, host absolute paths.
- **Test contract:** new logging surfaces pinned by at least one focused list-appender assertion.

### Project Structure Notes

- Backend module **`deliveryline-backend/`**. Base package `org.dradgo`. Java 21, Spring Boot 4.0.6. Spring Shell for CLI.
- Service → extend `org.dradgo.application.workflow.WorkflowOrchestrationService` (do NOT create a parallel service).
- Hook → `org.dradgo.application.workflow.WorkflowTransitionService`.
- Linear port → `org.dradgo.application.integration.linear.LinearAdapter` / `GovernedRunComment` (REUSE; do not modify the port).
- Redaction → `org.dradgo.application.security.RedactionPolicyService` + `RedactionResult`; `org.dradgo.domain.registry.DataClassification`.
- Event → `org.dradgo.domain.registry.WorkflowEventType` + `WorkflowEventDetailKeys` + `resources/schemas/cli/workflow-history.v1.schema.json`; failureCategory `org.dradgo.domain.registry.IntegrationFailureCategory`.
- Error code → `org.dradgo.domain.registry.DomainErrorCode` + `org.dradgo.adapters.rest.ProblemDetailsCatalog` + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (three-sites; add only `INVALID_COMPLETION_TEMPLATE`).
- Config → `org.dradgo.application.workflow.WorkflowProperties` (nested `LinearCompletionSync`) + `org.dradgo.infrastructure.config.WorkflowConfiguration` (validator bean) + `application.yml` (main + test).
- Doctor → `org.dradgo.application.diagnostics.DoctorService` + `spi.DoctorProbePort` + `org.dradgo.adapters.diagnostics.DoctorProbeAdapter`.
- CLI → `org.dradgo.adapters.cli.WorkflowCommands`.
- ArchUnit → `org.dradgo.architecture.ArchitectureRuleCatalog`.
- Docs → `docs/integrations/linear-completion-sync.md` (new directory).
- **No Flyway migration. No transition-table change. No new public-id prefix.** **Do NOT build** `acceptImplementation` (story 3.20) or rename `LinearAdapter` → `TicketSourceAdapter` (later story).

### Verification commands (PowerShell — [[rtk-hook-only-matches-bash]])

- Focused unit: `mvnw -pl deliveryline-backend test -Dtest=WorkflowOrchestrationServiceTest,WorkflowTransitionServiceTest,WorkflowCommandsTest,DoctorServiceTest,DoctorLoggingContractTest` (adjust to actual test class names).
- Registry/schema/problem-details contracts: `mvnw -pl deliveryline-backend test -Dtest=WorkflowEventDetailKeysContractTest,*ProblemDetails*,*Registry*` (validates the new event detail keys ↔ history schema parity + the `INVALID_COMPLETION_TEMPLATE` three-sites code).
- ArchUnit (Failsafe — Trap T9): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=**/architecture/**/*Test`.
- Foundation gate (Docker up — three-sites error-code contracts): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false`.
- Static + full fast tier: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then `mvnw -pl deliveryline-backend test`.
- WSL2 Linux smoke of the foundation gate ([[wsl-linux-ci-reproduction]] / [[verify-ci-fixes-in-clean-env]]).

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.16] — ACs 1–11 (lines 322–334). Adjacent: Story 3.20 AC6 (the deferred `acceptImplementation` + PR-link gate, lines 402–416 — the future trigger); Story 2.9 (product approval precedent for reviewer identities); the `TicketSourceAdapter` abstraction note (lines 645–651 — do NOT pre-implement).
- Predecessor: [Source: _bmad-output/implementation-artifacts/3-15-integration-link-service-extended-for-github-pr-linkage.md] — the `github_pr` `integration_links` row + `external_metadata.prUrl` this story reads; the build-now/defer-trigger-to-3.20 house pattern; the three-sites + event-key/schema parity + ctor-fan-out discipline.
- Linear adapter (story 1.14): `application/integration/linear/LinearAdapter.java:54` (`postGovernedRunComment`), `GovernedRunComment.java`; `adapters/integration/linear/LinearMockAdapter.java:101,117,147`; `adapters/integration/linear/LinearRealAdapter.java:234,251,504`; `resources/graphql/linear/post-comment.graphql`; `infrastructure/config/LinearConfiguration.java` (`linear-mock`/`linear-real` profiles).
- Redaction (story 1.10): `application/security/RedactionPolicyService.java:20-30`, `RedactionResult.java:7-13`, `domain/registry/DataClassification.java:5-31`.
- Orchestration/transition: `application/workflow/WorkflowOrchestrationService.java`; `WorkflowTransitionService.java:52`; `WorkflowCommandService.java:379-394` (the `registerSynchronization` post-commit pattern); `WorkflowTransitionTable.java` (`COMPLETED` from `WAITING_FOR_REVIEW`); `domain/registry/WorkflowState.java`.
- Registries: `domain/registry/WorkflowEventType.java`; `WorkflowEventDetailKeys.java` (+ `ALLOW_LISTED_KEYS`); `resources/schemas/cli/workflow-history.v1.schema.json`; `domain/registry/IntegrationFailureCategory.java`; `domain/registry/DomainErrorCode.java`; `adapters/rest/ProblemDetailsCatalog.java`; `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`.
- Config/doctor/CLI/archunit: `application/workflow/WorkflowProperties.java`; `infrastructure/config/WorkflowConfiguration.java:16`; `application.yml` (main + `src/test/resources/`); `application/diagnostics/DoctorService.java`; `application/diagnostics/spi/DoctorProbePort.java`; `adapters/diagnostics/DoctorProbeAdapter.java:130,238,314`; `adapters/cli/WorkflowCommands.java:52-54,146-201`; `architecture/ArchitectureRuleCatalog.java:376-403,577`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — bmad-create-story 2026-06-10; bmad-dev-story 2026-06-11.

### Debug Log References

- **Post-commit hook tx propagation (CompletionSyncOrchestrationIT first run).** `syncCompletionToLinear`
  was initially `@Transactional` (REQUIRED). Driven from the `afterCommit` synchronization, REQUIRED
  joined the *committing* original transaction, and the `github_pr`/`linear` pessimistic-lock reads
  threw `InvalidDataAccessApiUsageException` (swallowed by the hook → no comment posted → IT failed).
  Fixed by `@Transactional(propagation = REQUIRES_NEW)` — the sync now runs in its own fresh tx, which
  also makes AC4 / Trap T13 ("failure event in its own transaction; cannot touch the completed run's
  tx") structural. IT then green: hook fired post-commit, posted to `LIN-101`.
- **ArchUnit `credential_detection_must_stay_in_application_security` (first arch run).** The template
  helper's `private static final Pattern TOKEN` field name matched the catalog's sensitive-name regex
  (`.*TOKEN.*`), which forbids credential-detection regex catalogs outside `application.security`.
  Renamed the field to `PLACEHOLDER_PATTERN` (no semantic change). 43/0 after.
- **Registry/snapshot fixtures for the new event type.** Adding `WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED`
  failed `RegistryContractTest` (foundation gate) until the new wire value was mirrored into
  `contracts/events/workflow-event-types.fixture.json` and the fixture-stream
  `workflow-events-response.schema.json` `eventType` enum. The committed `openapi/openapi.json`
  `eventType` enum is NOT auto-derived from the Java enum (regenerating with
  `-Dopenapi.snapshot.write=true` produced byte-identical output) — no snapshot change needed;
  `OpenApiSnapshotContractTest` stays green.

### Completion Notes List

Implemented story 3.16 — Linear completion sync (write a merge-ready summary back to the source Linear
ticket when a governed run reaches `Completed`). House discipline followed: out-of-slice epic-3b pull
(epic-3b stays `deferred`); build-now / defer-the-`→COMPLETED`-trigger-to-3.20, mirroring 3.15.

- **D1 (REUSE, do NOT add `commentOnTicket`).** Wired the EXISTING `LinearAdapter.postGovernedRunComment(ticketRef, GovernedRunComment)`
  (mock log + real `commentCreate` + fingerprint-marker idempotency) — story 3.16 is its first
  production caller. No thinner method added.
- **Task 1.** `WorkflowOrchestrationService.syncCompletionToLinear(workflowRunId)` (`@Transactional(REQUIRES_NEW)`):
  resolves the `linear_ticket` link (no link → WARN no-op), composes the configured template
  (placeholders resolved defensively per D5 — `{prUrl}` reconstructed from the `github_pr` link's
  canonical `owner/repo#number` ref; `{specSummary}`/`{specVersion}` from the latest SPEC artifact;
  `{pmReviewer}`/`{devReviewer}` from approvals; `{durationFormatted}` from first→latest event
  timestamps; every unresolved datum → `n/a`/`unknown`), computes a stable SHA-256 fingerprint of the
  canonical pre-redaction body (D3), redacts claiming `SHAREABLE_FULL` (AC2/AC6 — sends
  `result.sanitizedText()`), and posts via `postGovernedRunComment`. `LinearAdapter` injected via lazy
  `ObjectProvider` (profile-gated, T11) — absent → WARN no-op. Ctor extended (single ctor; the one
  `WorkflowOrchestrationServiceTest.service(...)` site updated).
- **Task 2.** Post-commit hook in `WorkflowTransitionService.transition(...)`: on a committed
  `targetState == COMPLETED` AND `linear-completion-sync.enabled`, registers a `TransactionSynchronization`
  whose `afterCommit` resolves `WorkflowOrchestrationService` via lazy `ObjectProvider` (cycle break,
  Fact 6) and calls `syncCompletionToLinear`; any throw swallowed + WARN (AC4 — never rolls back the
  durable completion). Fires for ANY `COMPLETED` transition — 3.20's `acceptImplementation` needs no
  rewiring.
- **Task 3.** `WorkflowEventType.LINEAR_COMPLETION_SYNC_FAILED("linear.completionSyncFailed")` recorded
  on post failure (own REQUIRES_NEW tx). **Reused** the existing allow-listed detail keys
  `LINEAR_TICKET_REFERENCE` + `FAILURE_CATEGORY` (the integration `IntegrationFailureCategory` value)
  + `ERROR_CLASS` + `CORRELATION_ID` (event record's typed `failureCategory` stays `null`, mirroring
  `INTEGRATION_LINKED`) — **no new detail key / history-schema change** needed (D1 reuse spirit; T8
  parity green). New event wire value mirrored into the event-types fixture + fixture-stream schema.
- **Task 4.** `DomainErrorCode.INVALID_COMPLETION_TEMPLATE` three-sites (enum + `ProblemDetailsCatalog`
  BAD_REQUEST/non-retryable + `registry-api-schema-placeholders.json`); startup validator bean
  `LinearCompletionSyncConfiguration` validates the template when enabled (throws → fails boot, D4);
  normalize-never-throw stays in the config record.
- **Task 5.** `WorkflowProperties.LinearCompletionSync(enabled, template)` (normalize-never-throw +
  `defaults()` with a valid default template) + `linear-completion-sync.{enabled,template}` in
  `application.yml` (main `enabled: true`) and `src/test/resources/application.yml` (`enabled: false`,
  mirroring the auto-dispatch OFF-in-test discipline — T5).
- **Task 6.** `DoctorProbePort.probeLinearCompletionSync` + `DoctorProbeAdapter` impl (reuses the
  already-injected `workflowProperties`; PASS enabled/disabled + template validity) + `DoctorService`
  `CHECK_LINEAR_COMPLETION_SYNC` constant / `STATIC_ORDER` / switch / remediation. Probe fan-out (T6):
  stubbed in `DoctorServiceTest` + `DoctorLoggingContractTest`, `checksRun` bumped 16→17. No new
  ctor seam.
- **Task 7.** `deliveryline sync-completion {runId}` Spring Shell command on `WorkflowCommands`
  (injects `WorkflowOrchestrationService`; `requireOrchestrationWired()` guard for the legacy
  submit-only ctor; all master-ctor test sites threaded with the new arg).
- **Task 8.** `ArchitectureRuleCatalog.ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` + registered
  `@ArchTest` in `ArchitectureBoundaryTest` (43 arch rules, was 42).
- **Task 9.** `docs/integrations/linear-completion-sync.md` (new dir) + linked from `docs/cli/README.md`.
- **D7 honored:** NO Flyway / transition-table / new public-id-prefix change.

**Decisions / deviations from the literal task text (each toward the story's REUSE-first principle):**
- Task 3 reuses `LINEAR_TICKET_REFERENCE` + `FAILURE_CATEGORY` + `ERROR_CLASS` instead of adding
  `TICKET_REFERENCE`/`FAILURE_REASON` (the existing keys already fit exactly and are allow-listed +
  in the history schema → zero schema churn).
- `{prUrl}` is reconstructed from the `github_pr` link's canonical `external_ref` (deterministic for
  the single github.com pilot) rather than reading `external_metadata.prUrl`, avoiding a new
  metadata-projection SPI. The two typed link reads added to `IntegrationLinkService`
  (`findActiveLinearTicketLink`/`findActiveGitHubPrLink`) use the existing
  `findActiveByTypeAndWorkflowRunForUpdate` port method (the run-only finder is ambiguous when a run
  carries both a `linear` and a `github_pr` link).
- AC10 opt-out suppression is unit-covered at the service layer (`syncCompletionSkippedWhenDisabled`);
  the hook IT covers the enabled post-commit path (one Testcontainers boot).

**Gates (all GREEN, PowerShell — [[rtk-hook-only-matches-bash]]):** `spotless:apply` + `checkstyle:check`
0 violations; focused units `WorkflowOrchestrationServiceTest`(34)/`LinearCompletionTemplateTest`(7)/`LinearCompletionSyncConfigurationTest`(3)/`WorkflowCommandsSyncCompletionTest`(2)/`DoctorServiceTest`(17)/`WorkflowCommandsTest`(4)/`DoctorLoggingContractTest`(3)=70/0; registry+problem-details contracts 30/0; full fast Surefire 883/0/11skip; ArchUnit Failsafe `ArchitectureBoundaryTest` 43/0; `-Pfoundation-gate verify` 31/0/1; `OpenApiSnapshotContractTest` 1/0; `CompletionSyncOrchestrationIT` 1/0 (real Docker — hook fires post-commit + posts to LIN-101). Docker tiers run on local Docker (28.5.1); recommend a WSL2/Linux clean-env confirm ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]) + code-review with a different LLM.

### File List

**Main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowOrchestrationService.java` (M — `syncCompletionToLinear` + ctor deps + helpers)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java` (M — post-commit hook + lazy orchestration/`WorkflowProperties` deps)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowProperties.java` (M — nested `LinearCompletionSync` record)
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/LinearCompletionTemplate.java` (NEW — placeholder grammar: validate/render)
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/IntegrationLinkService.java` (M — `findActiveLinearTicketLink`/`findActiveGitHubPrLink`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java` (M — `LINEAR_COMPLETION_SYNC_FAILED`)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (M — `INVALID_COMPLETION_TEMPLATE`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (M — `INVALID_COMPLETION_TEMPLATE` mapping)
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearCompletionSyncConfiguration.java` (NEW — startup template validator bean)
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java` (M — `probeLinearCompletionSync`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java` (M — probe impl)
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java` (M — `CHECK_LINEAR_COMPLETION_SYNC` + order + switch + remediation)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java` (M — `sync-completion` command + ctor dep + guard)
- `deliveryline-backend/src/main/resources/application.yml` (M — `linear-completion-sync` keys)

**Test / contracts / docs:**
- `deliveryline-backend/src/test/resources/application.yml` (M — mirror `linear-completion-sync`, `enabled: false`)
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (M — `INVALID_COMPLETION_TEMPLATE` problemTypeUri)
- `deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json` (M — `linear.completionSyncFailed`)
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (M — `eventType` enum += `linear.completionSyncFailed`)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java` (M — ctor + 7 syncCompletionToLinear tests)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/LinearCompletionTemplateTest.java` (NEW)
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/LinearCompletionSyncConfigurationTest.java` (NEW)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCommandsSyncCompletionTest.java` (NEW)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/CompletionSyncOrchestrationIT.java` (NEW — Testcontainers hook e2e)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (M — AC9 rule)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (M — register AC9 rule)
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java` (M — stub new probe)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java` (M — stub new probe + checksRun 16→17)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorGitProbeTest.java` (M — 3-arg WorkflowProperties ctor)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/SpecStageRepoContextIT.java` (M — 3-arg WorkflowProperties ctor)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/ImplementationStageRepoContextIT.java` (M — 3-arg WorkflowProperties ctor)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/WorkflowCliCommandRegistrationIT.java`, `WorkflowCommandsContextBundleFlagTest.java`, `WorkflowCommandsStatusHistoryTest.java`, `WorkflowCommandsRunnerLogsFlagTest.java`, `WorkflowCliJsonSchemaContractTest.java`, `org/dradgo/foundation/CliRestEquivalenceContractTest.java` (M — thread the new `WorkflowCommands` ctor arg = `null`)
- `docs/integrations/linear-completion-sync.md` (NEW)
- `docs/cli/README.md` (M — link the new doc)

### Change Log

- 2026-06-11 — bmad-dev-story: implemented story 3.16 (Linear completion sync) across all 10 tasks +
  logging instrumentation; status `ready-for-dev` → `in-progress` → `review`. All gates GREEN
  (checkstyle 0, fast 883/0, contracts 30/0, ArchUnit 43/0, foundation-gate 31/0, OpenAPI snapshot
  1/0, CompletionSyncOrchestrationIT 1/0).

### Review Findings

> bmad-code-review 2026-06-11 — 3 adversarial layers (Blind Hunter [diff-only], Edge Case Hunter
> [diff + source], Acceptance Auditor [diff + spec]). Triage: 2 decision-needed + 1 patch + 3 defer
> + 7 dismissed. Each High verified against source before classifying (3.11/3.12 false-premise
> discipline). NOTE: the Blind Hunter's diff was RTK-compacted (548 of 1657 lines); compensated by
> regenerating the full diff via PowerShell + verifying every contested finding against on-disk
> source. Acceptance Auditor independently confirmed all 11 ACs implemented.

- [x] [Review][Decision→Patch] **RESOLVED (Alex: stable run-identity fingerprint) + APPLIED.** Fingerprint now hashes `stableFingerprintBasis(values)` which EXCLUDES `durationFormatted` + `prUrl` (`WorkflowOrchestrationService.java:1069,1305-1318`), so a re-sync after the timeline advances is robustly at-most-once. The vacuous test was rewritten to `syncCompletionFingerprintIsStableEvenWhenTheEventTimelineAdvances` — it advances the latest-event timestamp between the two invocations, asserts the rendered bodies DIFFER (cycle-time changed) AND the fingerprints are EQUAL. Verified: WorkflowOrchestrationServiceTest 34/0. — Idempotency fingerprint embeds the volatile `{durationFormatted}` — a re-sync after the event timeline advances can post a DUPLICATE Linear comment; the "stable fingerprint" test is vacuous — `templateValues` puts `durationFormatted` (=latest−first event ts, `WorkflowOrchestrationService.java:1115,1185-1199`) into the fingerprinted `canonicalBody` (`:1048-1052`), and `LinearRealAdapter` dedups by exact `fp=` marker. `syncCompletionUsesAStableFingerprintAcrossInvocations` (`WorkflowOrchestrationServiceTest.java:849`) only passes because shared setup (`:741-744`) stubs both event reads → empty, so duration is constantly `n/a`. Practical risk is LOW today (a successful post writes no event; a COMPLETED run is terminal) but real vectors exist: a late-arriving `github_pr` link changes `{prUrl}` between auto-post and manual retry (D3 calls this "acceptable progress signalling"), a success→fail→success flap (the failure event advances the timeline → duration drifts), and future Epic-4 post-completion events. DECISION: keep D3 content-fingerprint semantics (accept summary-change ⇒ fresh comment) OR base the fingerprint on a stable run-identity (exclude volatile duration/prUrl) for robust at-most-once. Either way make the test non-vacuous (advance the timeline between invocations).
- [x] [Review][Decision→Patch] **RESOLVED (Alex: surface the real outcome) + APPLIED.** `syncCompletionToLinear` now returns `SyncCompletionOutcome` (POSTED / SKIPPED_DISABLED / SKIPPED_NO_TICKET_LINK / SKIPPED_NO_LINEAR_PROFILE / POST_FAILED). The CLI prints the specific outcome (`WorkflowCommands.describeSyncOutcome`) and, on POST_FAILED, throws `DomainException(INTERNAL_ERROR)` → non-zero exit via the existing mapper (the failure is already recorded as an event; the governed run is never rolled back). The auto-hook ignores the return (best-effort unchanged). +2 CLI tests (skip-reason surfaced; POST_FAILED → INTERNAL_ERROR). Verified: WorkflowCommandsSyncCompletionTest 4/0. — Manual CLI `sync-completion` always prints "completion-sync requested" + exit 0, even when the sync no-ops (disabled / no `linear_ticket` link / no linear profile) or the Linear post fails — `WorkflowCommands.syncCompletion` (`WorkflowCommands.java:235-241`) calls the void `syncCompletionToLinear` then unconditionally `emitSuccess`; the service swallows every no-op/failure branch (`WorkflowOrchestrationService.java:1022-1090`). The command's documented contract is intentionally "never fails the run", but the OUTCOME (posted / skipped-why / failed-recorded) is not surfaced — false assurance on the AC5 retry path. DECISION: have `syncCompletionToLinear` return an outcome the CLI reports (and optionally exit non-zero on a genuine post failure for the manual path) OR accept the current opaque message.
- [x] [Review][Patch] **APPLIED.** `recordSyncFailure`'s failure-event `append(...)` is now wrapped in try/catch + WARN (`WorkflowOrchestrationService.java:1311-1327`) so a double-fault (DB error while recording the failure) can no longer escape the best-effort sync — the "never throws" contract holds end-to-end. Was: unguarded append at :1268.
- [x] [Review][Defer] Empty rendered body (custom template whose tokens all resolve blank, or redaction → whitespace) trips `GovernedRunComment`'s blank-body guard → `IllegalArgumentException` recorded as the generic `failureCategory="unknown"` instead of a clear template/config diagnostic; default template is literal-bearing so unreachable by default [WorkflowOrchestrationService.java:1088] — deferred, low / default-safe
- [x] [Review][Defer] `registerCompletionSyncHookIfApplicable` no-ops (WARN + return) when no transaction synchronization is active; currently unreachable (`transition()` always runs inside `transactionTemplate`) but a future direct/non-transactional COMPLETED path would silently skip the sync [WorkflowTransitionService.java:152-158] — deferred, latent
- [x] [Review][Defer] No `enabled ⇔ linear profile` consistency guard — `linear-completion-sync.enabled=true` with no `linear-mock|linear-real` profile silently no-ops every completion at WARN (adapter null); the doctor probe reports `enabled` but not profile presence [WorkflowOrchestrationService.java:1066-1073] — deferred, best-effort by design

**Dismissed (7):** (1) redaction "downgrade not blocked" / hardcoded `SHAREABLE_FULL` — spec-conformant: AC2/AC6 design is redact→send `sanitizedText()`→claim `SHAREABLE_FULL`; secrets are scrubbed to placeholders so nothing leaks. (2) `findActive*ForUpdate` reads in `IntegrationLinkService` not `@Transactional` — by design; sole prod caller is `@Transactional(REQUIRES_NEW)`, matches the repo ambient-tx SPI pattern. (3) `requireOrchestrationWired` → `INTERNAL_ERROR` — only reachable via the legacy/test ctor; `@Autowired` ctor always injects it in prod. (4) doctor probe defensive FAIL branch is dead code — harmless depth; startup validator already fails boot on a bad template. (5) AC9 ArchUnit rule allow-lists the CLI (which calls `syncCompletionToLinear`, not `postGovernedRunComment`, directly) — faithful to AC9's literal wording; dormant entry, not wrong. (6) doctor `templatePlaceholders` "leak" — `placeholdersIn` returns `{token}` names, not rendered values. (7) fingerprint-on-pre-redaction-body — intentional per D3 (redaction is deterministic); folded into the decision-needed idempotency finding.

**Resolution (2026-06-11):** both decision-needed findings resolved by Alex into patches; all 3 patches applied. Gates GREEN via PowerShell ([[rtk-hook-only-matches-bash]]): `spotless:apply` + `checkstyle:check` 0 violations; focused `WorkflowOrchestrationServiceTest` 34/0 + `WorkflowCommandsSyncCompletionTest` 4/0 (38/0/0, all backend test sources recompiled against the new `SyncCompletionOutcome` return type). 3 defers logged to `deferred-work.md`. NOT re-run in this review env: the Docker-backed `CompletionSyncOrchestrationIT` (hook e2e — unaffected by the patches: the hook still ignores the return and the fingerprint change still posts) + `-Pfoundation-gate` — recommend a WSL2/Linux clean-env Docker confirm ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]) before merge. Status `review → done`.
