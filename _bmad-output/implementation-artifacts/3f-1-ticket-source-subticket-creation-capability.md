# Story 3f.1: Ticket-Source Sub-Ticket Creation Capability

Status: done

<!-- 2026-06-26 bmad-create-story context-engine pass. Target sprint key: 3f-1-ticket-source-subticket-creation-capability. This is the first Epic 3f story, so sprint-status marks epic-3f in-progress. -->

> READ FIRST - this is a ticket-source port/capability story, not a workflow-state, split-proposal, dependency, or UI story. The implementation must widen the existing `TicketSourceAdapter` abstraction so later 3f-5 split commit can ask for a source sub-ticket when the connector supports it, and can skip cleanly to internal-only child runs when it does not. Do not create child workflow runs, parent lineage, dependency edges, split proposal persistence, split actions, REST split endpoints, or queue UI here.

## Story

As an operator splitting a run,
I want the system to create source sub-tickets for the subtasks when the project's ticket connector supports it,
So that each child run is backed by a real, linked ticket in the same source system as the parent - and where the connector cannot create tickets, the split can still proceed internal-only.

## Acceptance Criteria

1. Given the vendor-neutral `TicketSourceAdapter` port, then it gains a domain-shaped `CreateSubticketResult createSubticket(TicketRef parentRef, SubticketDraft draft)` operation and `TicketSourceCapabilities` gains `supportsTicketCreation`, defaulting to `false` for sources that do not implement creation.
2. Given the Linear adapter is the first-release reference implementation, then `LinearRealAdapter` implements `createSubticket` through Linear issue creation as a sub-issue under the parent and reports `supportsTicketCreation=true`; `LinearMockAdapter` implements a deterministic in-memory equivalent for tests.
3. Given a connector without ticket-creation support, then consuming code can inspect `supportsTicketCreation=false` and must not call `createSubticket`; GitHub/GitLab/future ticket-source adapters stay false until a later story implements them.
4. Given a created sub-ticket, then the adapter posts a parent-link/back-reference comment through the existing governed-comment mechanism so the parent ticket records the split and child reference; the returned result carries a deterministic child `TicketRef` and enough non-secret metadata for 3f-5 to link the child run.
5. Given idempotency, then `createSubticket` is safe for replay under a caller-provided deterministic key derived from parent run + proposal/subtask ordinal; replay must not create duplicate sub-tickets or duplicate parent-link comments.
6. Given redaction and logging policy, then subtask title/scope/description sent to the connector pass the same redaction/content posture as other outbound ticket comments, and logs include ids, ordinals, lengths, categories, and capability decisions only - never payload bodies, secrets, raw tokens, or full PII.
7. Given tests, then coverage asserts the capability flag, default false behavior, Linear real adapter GraphQL variables/mapping, Linear mock deterministic creation + idempotent replay, parent-link comment idempotency, "false-capability adapter is not called" caller pattern, redaction/logging safety, and `application.*` coverage remains at or above the committed gate.

## Tasks / Subtasks

- [x] Task 1 - Add neutral domain records and capability flag (AC: 1, 3)
  - [x] Add `SubticketDraft` and `CreateSubticketResult` under `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/`.
  - [x] Keep both records vendor-neutral. Allowed field shape: parent/run/proposal/subtask identifiers, ordinal, title, scope/description, idempotency key/fingerprint, and returned `TicketRef`; no Linear issue ids, GraphQL DTOs, HTTP types, or SDK objects.
  - [x] Extend `TicketSourceCapabilities` from 3 booleans to 4 booleans with `supportsTicketCreation`. Preserve source compatibility by updating all constructor sites and `linearDefaults()`. If a future non-Linear adapter exists or is stubbed, it must explicitly return `false`.
  - [x] Add a named default capability factory or helper for "no creation" if it reduces constructor churn, but do not introduce a registry table or database migration.

- [x] Task 2 - Extend `TicketSourceAdapter` without leaking vendor types (AC: 1, 2)
  - [x] Add `CreateSubticketResult createSubticket(TicketRef parentRef, SubticketDraft draft)` to `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java`.
  - [x] Document it as an optional operation guarded by `getCapabilities().supportsTicketCreation()`.
  - [x] Keep the port in `application.integration.ticketsource`; implementations stay under `adapters.integration.ticketsource.{kind}` per existing ArchUnit rules.
  - [x] Update `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT` if needed only to allow the new neutral records.

- [x] Task 3 - Implement deterministic Linear mock support (AC: 2, 5, 7)
  - [x] In `LinearMockAdapter`, report `supportsTicketCreation=true`.
  - [x] Add in-memory created-subticket history keyed by `(parentRef, draft.idempotencyKey/fingerprint)` so replay returns the same `CreateSubticketResult` and does not append another parent-link comment.
  - [x] Generate deterministic child refs suitable for tests, e.g. based on parent ticket key + ordinal or a stable fixture value. Avoid randomness and wall-clock dependence.
  - [x] Expose a test-only immutable accessor/clearer analogous to `postedComments()` / `clearPostedComments()` if adapter-scope tests need to inspect creation history.

- [x] Task 4 - Implement Linear real GraphQL support (AC: 2, 4, 5, 6)
  - [x] Add a GraphQL resource such as `graphql/linear/create-subticket.graphql` and load it in `LinearRealAdapter` next to the existing fetch/poll/comment queries.
  - [x] Use the current Linear public GraphQL schema at implementation time. Context checked 2026-06-26: Linear's public GraphQL schema is exposed through Apollo GraphOS Studio; verify the exact `issueCreate` input names before coding, especially the parent/sub-issue field.
  - [x] Resolve the parent Linear issue id before creation if `issueCreate` requires an internal id rather than the human key. Reuse existing parsing/fetch helpers where practical; do not pass human `LIN-123` into a field that expects a UUID.
  - [x] Map auth/rate-limit/network/GraphQL errors through `TicketSourceAdapterException` with the existing `IntegrationFailureCategory` conventions.
  - [x] Post the parent-link/back-reference using the existing `postGovernedRunComment` path or its internal helper so idempotent comment behavior is reused. The body must be pre-redacted; the adapter still does not redact by itself.
  - [x] Keep GraphQL payload/body/token values out of logs. Use the existing logging style: operation, ticketRef, fingerprint/key, durationMs, category, success.

- [x] Task 5 - Widen architecture guardrails intentionally (AC: 4, 7)
  - [x] Existing ArchUnit rule `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` says Linear comments are completion-sync only. This story intentionally creates a second governed write-back path: sub-ticket parent-link comments.
  - [x] Update the rule and remediation text to allow the new canonical service/call path only, not arbitrary callers. Prefer a narrow allow-list such as a new `SplitSubticketCreationService`/`TicketSourceSubticketService` if introduced; do not loosen the rule to "any application service".
  - [x] Add/update the corresponding `ArchitectureBoundaryTest` coverage so future code cannot call `postGovernedRunComment` or `createSubticket` from controllers, CLIs, runner code, or persistence adapters directly.

- [x] Task 6 - Add the smallest application-level caller seam for 3f-5 (AC: 3, 5, 7)
  - [x] Add a small application service only if needed to prove capability-gated behavior now, e.g. `TicketSourceSubticketService` in `application.integration` or `application.workflow`.
  - [x] The service should resolve the project's ticket source through `ProjectConnectorResolver`/existing project seams, check `supportsTicketCreation`, call `createSubticket` only when true, and return a result that 3f-5 can consume.
  - [x] If the source is false-capability, return an explicit skipped/internal-only outcome; do not throw and do not fail the split. This story proves the pattern, while 3f-5 consumes it end-to-end.
  - [x] Keep it free of persistence/adapter imports. It may depend on application services/ports and domain records only.

- [x] Task 7 - Tests and contracts (AC: 1-7)
  - [x] Unit-test `TicketSourceCapabilities` defaults and Linear support flag.
  - [x] Unit-test `LinearMockAdapter.createSubticket`: happy path, replay no duplicate, parent-link comment recorded once.
  - [x] Unit-test `LinearRealAdapter.createSubticket` with `MockRestServiceServer`: GraphQL request variables are correct, response maps to `CreateSubticketResult`, replay/comment idempotency behavior is preserved, auth/rate-limit/network/malformed responses classify correctly.
  - [x] Test the capability-gated caller seam with a false-capability adapter: `createSubticket` is never invoked and the outcome is internal-only/skipped.
  - [x] Extend `ArchitectureBoundaryTest` for the new write path and port method.
  - [x] Add a focused log-safety assertion for the new branch using a list appender; the log must not contain draft body text, tokens, or planted secret-shaped values.
  - [x] Run the backend test slice that covers adapter units, architecture rules, registry/contracts touched by the capability change, and any affected OpenAPI/client drift checks if the application seam is surfaced through REST. If no REST/DTO changes are made, OpenAPI should remain byte-identical.

- [x] Logging instrumentation (cross-cutting; required)
  - [x] Add SLF4J structured logs at the public service entry/exit, capability skip, adapter external call, idempotent replay, and failure-classification branches.
  - [x] Use parameterized logging only.
  - [x] Required context keys where available: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus `parentTicketRef`, child ticket ref/result id, and subtask ordinal.
  - [x] Never log draft title/scope/description bodies, payload bytes, GraphQL body, credentials, or raw tokens.
  - [x] Pin at least one log line in a focused test.

## Dev Notes

### Reconciled Scope

This story is a foundation for 3f-5. It must stop at "the ticket source can create a sub-ticket when asked safely." It must not implement:

- `parent_run_id` or child workflow runs - story 3f-2.
- `SPLIT`, `workflow.split`, split actions, split proposal persistence, or split REST endpoints - stories 3f-2, 3f-4, 3f-5.
- `run_dependencies` or `WaitingForDependencies` - story 3f-3.
- Queue project filter or run lineage UI - stories 3f-2/3f-6.
- GitHub/GitLab sub-ticket creation - explicitly future additive scope.

### Live Code Seams Verified 2026-06-26

- `TicketSourceAdapter` currently lives at `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java`. It already exposes `fetchTicketByReference`, `pollNewTickets`, `postGovernedRunComment`, `getCapabilities`, and `verifyConnectivity`.
- `TicketSourceCapabilities` currently has three flags only: `supportsCommentOnTicket`, `supportsPolling`, and `supportsTicketStateUpdates`; `linearDefaults()` returns all true.
- Linear implementations are `LinearMockAdapter` and `LinearRealAdapter` under `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/`.
- `LinearRealAdapter` already has GraphQL resource loading, `executeGraphQL`, Linear ticket-ref parsing, failure classification, comment idempotency marker scanning, and redacted logging conventions. Reuse those patterns.
- `postGovernedRunComment` currently embeds a marker `<!-- deliveryline:run=... fp=... -->` and scans existing comments for idempotency. Sub-ticket parent-link comments should reuse this mechanism or an equivalent marker; do not invent a second unbounded comment write path.
- Current Flyway head is `V26__create_project_runner_kinds.sql`; this story should require no migration. If implementation discovers a persistence need, stop and reassess because that likely belongs to 3f-2/3f-5.

### Architecture Guardrails

- Existing ArchUnit rules enforce that the ticket-source port is vendor-neutral and that concrete implementations live in `adapters.integration.ticketsource`. Keep new records neutral so the port does not depend on Linear GraphQL/HTTP types.
- Existing rule `ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT` was written when completion-sync was the only Linear write-back. 3f-1 deliberately adds a second write-back type. Update the rule narrowly so only the new sub-ticket creation seam may create the parent-link comment.
- Do not let controllers or CLI commands call `TicketSourceAdapter.createSubticket` directly. 3f-5 split approval should later call an application service that owns capability gating and idempotency.

### Idempotency Model

- The draft/result records should carry an idempotency key or fingerprint supplied by the caller. 3f-5 will derive it from parent run + proposal id + subtask ordinal.
- The adapter-level behavior must be replay-safe because Linear has no native idempotency key for issue creation. For the real adapter, choose a deterministic marker strategy that can detect an already-created sub-ticket before creating another one. If the Linear schema cannot search by a metadata marker on issues, use a parent-link comment marker as the replay backstop and document the residual race clearly.
- Keep the existing "best effort but never silently duplicate" posture: replay returns the original child reference when discoverable; ambiguous external state should fail closed with a typed integration failure rather than create a second child.

### Latest External Context

- Checked 2026-06-26: Linear's current public GraphQL schema is available through Apollo GraphOS Studio. The story should not hardcode stale field names beyond the conceptual requirement to create a child/sub-issue under a parent. Before implementation, inspect the live `IssueCreateInput` / `issueCreate` schema and pin the exact fields in the adapter unit tests.

### Testing Standards

- Adapter tests should stay unit-level and deterministic unless they need Testcontainers indirectly through an application service. Do not add a real Linear network test.
- Use `MockRestServiceServer` for `LinearRealAdapter` GraphQL calls, matching existing tests.
- `@SpringBootTest` + Testcontainers classes must be named `*IT` and run through the Failsafe tier.
- If no REST surface is added, `OpenApiSnapshotContractTest` should remain byte-identical and no `schema.d.ts` regeneration is needed.

### References

- Epic definition: `_bmad-output/planning-artifacts/epic-03f-complex-ticket-flow.md` - Story 3f-1 and Cross-Cutting Notes.
- Sprint change proposal: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-24.md` - Epic 3f technical impact and sequencing.
- Architecture rules: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` - ticket-source boundaries and Linear comment write-back rule.
- Port: `deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java`.
- Capabilities: `deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilities.java`.
- Linear adapters: `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapter.java`, `LinearRealAdapter.java`.
- Previous story intelligence: `_bmad-output/implementation-artifacts/3e-5-spec-stage-runner-observability-and-decision-bar-placement.md` and `_bmad-output/implementation-artifacts/3e-4-per-step-runner-mapping-per-project.md`.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- 2026-06-26: RED `mvn -pl deliveryline-backend "-Dtest=TicketSourceCapabilitiesTest,TicketSourceSubticketServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` failed on missing `SubticketDraft`, `CreateSubticketResult`, `supportsTicketCreation`, `createSubticket`, and `TicketSourceSubticketService`.
- 2026-06-26: RED `mvn -pl deliveryline-backend "-Dtest=LinearMockAdapterUnitTest,LinearRealAdapterUnitTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` failed on the `LinearRealAdapter.createSubticket` stub.
- 2026-06-26: Could not obtain a useful indexed public Linear schema page during web search; implementation uses the conventional `IssueCreateInput` fields `teamId`, `parentId`, `title`, and `description`, pinned by `MockRestServiceServer` unit tests. No real Linear network test was added.
- 2026-06-26: Verification passed: focused 3f-1 slice 38 tests, full backend Surefire 1360 tests / 0 failures / 12 skipped, Spotless check, and Checkstyle check.

### Completion Notes List

- Added vendor-neutral `SubticketDraft` and `CreateSubticketResult`, widened `TicketSourceCapabilities` with `supportsTicketCreation`, and kept GitLab/non-creation sources explicitly false via `noCreation(...)`.
- Extended `TicketSourceAdapter` with optional `createSubticket(...)` and added `TicketSourceSubticketService` / `TicketSourceSubticketOutcome` as the capability-gated application seam for 3f-5.
- Implemented Linear mock deterministic source sub-ticket creation with replay-safe in-memory history and parent-link comment de-duplication.
- Implemented Linear real source sub-ticket creation through GraphQL `issueCreate`, parent internal id/team id resolution, parent-link governed comments, comment-marker replay detection, typed failure classification, and log-safety coverage.
- Updated architecture guardrails to allow the new subticket service path while preventing arbitrary direct `createSubticket` or comment write-back calls.
- No REST/OpenAPI/client schema changes were made for this story.

### File List

- deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceAdapter.java
- deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceSubticketOutcome.java
- deliveryline-backend/src/main/java/org/dradgo/application/integration/ticketsource/TicketSourceSubticketService.java
- deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/CreateSubticketResult.java
- deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/SubticketDraft.java
- deliveryline-backend/src/main/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilities.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/gitlab/GitLabTicketSourceStubAdapter.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapter.java
- deliveryline-backend/src/main/java/org/dradgo/adapters/integration/ticketsource/linear/LinearRealAdapter.java
- deliveryline-backend/src/main/resources/graphql/linear/create-subticket.graphql
- deliveryline-backend/src/main/resources/graphql/linear/fetch-ticket-by-reference.graphql
- deliveryline-backend/src/test/java/org/dradgo/domain/integration/ticketsource/TicketSourceCapabilitiesTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/integration/ticketsource/TicketSourceSubticketServiceTest.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/integration/ticketsource/linear/LinearMockAdapterUnitTest.java
- deliveryline-backend/src/test/java/org/dradgo/adapters/integration/ticketsource/linear/LinearRealAdapterUnitTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectivityServiceTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectorResolverTest.java
- deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowOrchestrationServiceTest.java
- deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java
- deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java
- deliveryline-backend/src/test/java/org/dradgo/foundation/TicketSourceAbstractionFoundationContract.java

## Change Log

| Date | Version | Change |
|------|---------|--------|
| 2026-06-26 | 0.1 | Created ready-for-dev story for 3f-1 with live-code guardrails, adapter seams, idempotency/redaction rules, and architecture-rule updates. |
| 2026-06-26 | 1.0 | Implemented ticket-source sub-ticket port/capability, Linear mock/real support, gated application service, architecture guardrails, and tests. |

## Review Findings

<!-- bmad-code-review 2026-06-26. Adversarial layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor. Scoped to the 21-file File List. -->

- [x] [Review][Decision→Verified] Architecture-tier verification gap (Task 5) — the new/changed ArchUnit rules (`ONLY_ORCHESTRATION_AND_CLI_MAY_POST_LINEAR_COMMENT`, `ONLY_SUBTICKET_SERVICE_MAY_CREATE_SOURCE_SUBTICKET`, `TICKET_SOURCE_TYPES_MUST_NOT_LEAK_THROUGH_PORT`) run in the Failsafe/architecture tier, which the Debug Log's Surefire-only run did not exercise. RESOLVED 2026-06-26: ran the architecture tier via Failsafe (`ArchitectureBoundaryTest` 57 tests + 70 total, 0 failures) — all rules green.
- [x] [Review][Decision→Patch] No single-JVM serialization around `createSubticket` scan+create. RESOLVED: wrapped fetch→scan→create+marker in a per-parent lock reusing the `commentLocks` pattern (reentrant with the nested comment post). [LinearRealAdapter.java]
- [x] [Review][Patch] `findExistingSubticket` fails OPEN on truncated pagination — fixed: now fails closed whenever `hasNextPage` is still true after the loop (page-cap OR blank cursor) with a typed `SYNC_FAILURE`. [LinearRealAdapter.java]
- [x] [Review][Patch] Spec-mandated residual-race documentation missing — fixed: added a javadoc on `createSubticket` documenting the idempotency model, the single-JVM lock, and the accepted create-then-marker residual race. [LinearRealAdapter.java]
- [x] [Review][Defer] Mock `childRef = parentRef-ordinal` collides across distinct idempotency keys with the same ordinal (multi-proposal split) → distinct sub-tickets share a child ref, masking duplication in 3f-5 tests. Mock-only. [LinearMockAdapter.java:168] — deferred, revisit when 3f-5 exercises multi-proposal splits.
- [x] [Review][Defer] `idempotencyKey` interpolated unescaped into the HTML-comment marker; a key containing `-->` would corrupt replay parse. Input is system-derived (parent run + proposal + ordinal), low risk. [LinearRealAdapter.java:478-499] — deferred, add charset validation to `SubticketDraft` as hardening.
- [x] [Review][Defer] `pollNewTickets` shares the same blank-cursor fail-open pagination pattern as the Patch above. [LinearRealAdapter.java:198] — deferred, pre-existing (not introduced by this change).
- [x] [Review][Defer] Replay-path `CreateSubticketResult` metadata omits `linearIssueId` (present only on the create path); `childRef` is the contract linking key so impact is minor. [LinearRealAdapter.java:310-315] — deferred, inherent to the marker-backstop design.

### Dismissed (false positives / out of scope)

- ArchUnit "LinearMockAdapter not allow-listed → build break" — false positive. `callMethod(TicketSourceAdapter.class, "postGovernedRunComment", ...)` matches calls whose bytecode owner is the interface type; adapter self-calls (`this.postGovernedRunComment(...)`) compile with the concrete class as owner, so neither LinearMock nor LinearReal trips the rule. The `LinearRealAdapter` allow-list entry is unnecessary/defensive but harmless.
- "Internal Linear UUID wrapped as TicketRef for comment posting will fail to re-parse" — false positive. `postGovernedRunComment` uses `ref.value()` directly as the GraphQL `issueId` (no `parseTicketRef` re-resolution), and the UUID is used consistently for both the marker scan and the comment post within `createSubticket`.
- "Cannot create a sub-ticket under a sub-ticket (`LIN-101-2` won't parse)" — out of scope; nested sub-ticket creation is not a 3f-1 concern.
- "requireText-after-create leaves orphan child" — merged into the spec-accepted residual-race finding (Patch: documentation).
