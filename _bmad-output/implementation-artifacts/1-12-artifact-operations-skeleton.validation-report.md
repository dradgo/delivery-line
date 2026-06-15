# Story Validation Report: 1-12 Artifact Operations Skeleton

Date: 2026-05-07
Story File: `_bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md`
Validator: `bmad-create-story:validate`

## Outcome

Status: **Fit for `bmad-dev-story`**

Summary:
- Previous critical issues resolved: 3
- Remaining blocking issues: 0
- Optional enhancement ideas: 1

## Rerun scope

Revalidated the updated story against:
- `_bmad-output/planning-artifacts/epics.md`
- `_bmad-output/planning-artifacts/architecture.md`
- `_bmad-output/implementation-artifacts/1-9-idempotency-service.md`
- `_bmad-output/implementation-artifacts/1-11-archunit-package-boundary-tests.md`
- `deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`

## What is now correct

1. **Artifact identity matches the live schema**
   - The story now correctly distinguishes:
     - internal numeric primary keys (`id`)
     - governed external identifiers (`public_id` with `art_...` / `op_...`)
   - This aligns with the live Flyway schema and the existing `PublicIdPrefixes` pattern.

2. **Versioning is no longer ambiguous**
   - The story now explicitly states one lineage per `(workflow_run_id, artifact_type)` in this slice.
   - `newVersion(...)` is tied to the same run + artifact type and points `parent_artifact_id` at the immediately superseded version.
   - Multiple independent same-type lineages in one workflow run are explicitly out of scope.

3. **The write-flow contract is now implementable without guesswork**
   - `recordOperation(...)` now has a defined minimum input contract.
   - Transaction ordering now accounts for the `linked_event_id` NOT NULL constraints on both `artifacts` and `artifact_operations`.
   - Late-runner handling is tied to an optional `runnerExecutionId`, which gives AC10 a concrete decision seam.

4. **Service ownership is explicit**
   - `ArtifactOperationService` owns write orchestration and availability transitions.
   - `ArtifactService` owns eligibility/read-side logic.
   - `ArtifactReconciliationService` owns stale-pending/orphan detection only.

5. **The storage-root contract is explicit**
   - The story now defines `deliveryline.home` / `DELIVERYLINE_HOME` as the artifact-home seam and forbids silent fallback to the working directory.

6. **Expected files and test targets are concrete**
   - The story now names the expected application, persistence, mapper, and file-adapter targets.
   - It also names the exact architecture and contract suites that should remain observable when the implementation lands.

7. **Existing registry/event seams are pinned**
   - The story now explicitly tells the dev agent to reuse `PublicIdPrefixes`, `WorkflowEventType`, and `PersistedRegistryValues` instead of inventing duplicate constants or parsers.

## Optional enhancement

1. **Identifier-surface wording could be made even more explicit**
   - Non-blocking suggestion: add one sentence stating that application-layer service signatures should use governed public IDs (`run_...`, `art_...`) unless a persistence adapter is intentionally operating on internal numeric PKs.
   - This is already strongly implied by the repo’s existing patterns, so it is not a blocker for `bmad-dev-story`.

## Recommendation

The story is ready for implementation. No blocking validation issues remain.

Recommended next step:
- run `bmad-dev-story` against `_bmad-output/implementation-artifacts/1-12-artifact-operations-skeleton.md`
