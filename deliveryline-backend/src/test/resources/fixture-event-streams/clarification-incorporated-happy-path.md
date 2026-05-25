# Fixture: clarification-incorporated-happy-path

Story 2.12 AC8. Demonstrates the visible-incorporation lifecycle happy path: a workflow run with two open clarifications that both progress `open → answered → accepted → incorporated` after the next spec rebuild.

## Scenario

1. Workflow run `run_fix_clr_incorp_001` (DEL-9101) advances through `Inbox → Investigating`.
2. Spec v1 (`art_spec_clr_incorp_v1`) is drafted and the run lands in `WaitingForSpecApproval`.
3. PM answers two clarifications attached to spec v1:
   - `clr_fix_incorp_001` / `Q-AUTH-001`
   - `clr_fix_incorp_002` / `Q-AUTH-002`
4. Each answered clarification is auto-accepted by the lifecycle service.
5. A new spec version (`art_spec_clr_incorp_v2`) is created; the orchestrator sweeps the run.
6. Both clarifications are marked `incorporated` because v2's payload acknowledges both `questionId`s. Each emits a `clarification.incorporated` event with `incorporatedIntoArtifactId` + `incorporationEventId`.

## Contract test coverage

`ClarificationVisibleIncorporationContractTest` walks this stream and asserts that every `clarification.answered` event has a downstream `clarification.accepted` followed by a `clarification.incorporated` event for the same `clarificationId`.
