# Fixture: clarification-superseded-and-rejected

Story 2.12 AC8. Demonstrates the two terminal alternates to `incorporated`: an accepted clarification superseded by a spec rebuild that did not acknowledge it, plus an answered clarification rejected as invalid by the PM.

## Scenario

1. Workflow run `run_fix_clr_supersede_001` (DEL-9102) drafts spec v1 (`art_spec_clr_supersede_v1`).
2. PM answers clarification `clr_fix_supersede_001` (`Q-SCOPE-001`) attached to v1.
3. Clarification is auto-accepted by the lifecycle service.
4. Spec v2 (`art_spec_clr_supersede_v2`) is created; the rebuild does NOT acknowledge `Q-SCOPE-001`, so the orchestrator marks the clarification `superseded` with `noEffectReason = "clarification_not_addressed"` and `supersededByArtifactId = art_spec_clr_supersede_v2`.
5. A new clarification `clr_fix_supersede_002` (`Q-SCOPE-002`) is opened against v2 and answered by the PM.
6. The PM (future Epic 4 operator-action path) marks the answer invalid (`clarification.rejectedInvalid` with `noEffectReason = "pm_marked_invalid"`).

## Notes

- Clarification 3 (originally scoped as "answered but no follow-up") is intentionally omitted; the make-or-break contract test requires every `clarification.answered` to have a downstream chain, so the negative case lives **inline** in `ClarificationVisibleIncorporationContractTest` rather than as a malformed fixture file (Trap T9 — `FixtureEventStreamSchemaConformanceTest` would reject a malformed file before the contract test ever ran).
- The PM-driven `rejected_invalid` path is modeled in this fixture (`actorType=human`) even though MVP wiring has no REST endpoint for it (Trap T10); a future Epic 4 operator-action story owns the surface. The fixture documents the desired event shape today so the contract test stays exhaustive.
