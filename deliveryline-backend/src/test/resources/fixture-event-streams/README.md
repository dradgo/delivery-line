# Fixture event streams

This directory publishes deterministic governed-run event histories that Epic 2 UI tests,
Epic 3 runner-adapter tests, and Epic 4 recovery-flow tests can consume without booting a live
runner. Each fixture is contract-tested for schema conformance, transition-table legality, and
artifact-variant coverage (story 1.23).

## Wire shape

The authoritative wire shape is described by
[`schema/workflow-events-response.schema.json`](schema/workflow-events-response.schema.json).
The schema is JSON Schema draft 2020-12 and matches the response shape that story 6.9
(`localhost-rest-binding-and-workflow-read-endpoints`) will eventually serve from
`GET /api/v1/workflows/{workflowRunId}/events`. Any change to either side is a coordinated change.

## Fixtures

| Fixture | Scenario | States covered | Artifact variants | Recommended for Epic 2 stories |
| --- | --- | --- | --- | --- |
| [`happy-path-success.json`](happy-path-success.json) | Full lifecycle from `submit` to `Completed` with no rejections or failures. The spec `artifact.draftCreated` event carries story-3a-2 repo-context `details` (`repositoryWorkspaceRef`, `repositoryReadmeRef`, `ticketRepositoryMappingVersion`, tree/manifest counts); the `implementationPlan` + `prOutput` events additionally carry the story-3.10 `repositoryBranchRef` (full implementation-stage bundle). | Inbox, Planned, Investigating, WaitingForSpecApproval, Executing, WaitingForReview, Completed | spec, implementationPlan, prOutput | `2.15`, `2.16`, `2.17`, `2.19` |
| [`spec-rejection-and-resubmit.json`](spec-rejection-and-resubmit.json) | Spec v1 rejected with structured feedback; revert to Investigating; spec v2 approved; run completes. | Inbox, Planned, Investigating, WaitingForSpecApproval (twice), Executing, WaitingForReview, Completed | spec (v1 and v2) | `2.17`, `2.18`, `2.19`, `2.20` |
| [`execution-failure-with-retry.json`](execution-failure-with-retry.json) | Runner crashes during Executing; run transitions to Failed; recovery.retried fires; retry succeeds; run completes. | Inbox, Planned, Investigating, WaitingForSpecApproval, Executing (twice), Failed, WaitingForReview, Completed | spec, implementationPlan, prOutput | `2.15`, `2.16`, `2.19`, `2.20` |

Each fixture has an adjacent `.md` sidecar (e.g., [`happy-path-success.md`](happy-path-success.md))
that describes the scenario, what it does NOT cover, and which Epic 2 composites should consume
it.

## Determinism

Every fixture uses:

- Deterministic public IDs (e.g., `run_fix_happy_001`, `evt_fix_rej_007`, `art_spec_fail_001`) so
  CI-driven assertions remain reproducible across runs.
- ISO-8601 UTC timestamps anchored to `2026-01-01T00:00:00Z` (happy path), `2026-01-02T00:00:00Z`
  (rejection), and `2026-01-03T00:00:00Z` (failure-and-retry), incrementing in 30-second steps.
- Placeholder structured-feedback strings — no real PII, no real secrets, no real customer data.
  The fixture corpus is the structural backstop for Epic 2 UI development, not a redaction-policy
  fixture.

## How to add a new fixture

1. Author the JSON file under this directory matching
   [`schema/workflow-events-response.schema.json`](schema/workflow-events-response.schema.json).
2. Author the `.md` sidecar (same basename) describing scenario, covered states, artifact
   variants, and recommended Epic 2 consumers.
3. Re-run the three contract tests locally to confirm conformance:
   - `FixtureEventStreamSchemaConformanceContractTest` (wire-shape conformance)
   - `FixtureEventStreamTransitionIntegrityContractTest` (transition-table legality)
   - `FixtureEventStreamArtifactVariantCoverageContractTest` (variant set coverage)
4. Update the table above with the new row.

## Forward-compat invariant

When story 6.9's `WorkflowEventsController` ships, its response serializer MUST conform to
[`schema/workflow-events-response.schema.json`](schema/workflow-events-response.schema.json). The
schema file's top-level `description` records this contract; any breaking change to either side
requires coordinated updates to both.
