# Validation Report — Story 1.13 (Runner Broker + Deterministic Mock Runner Adapter)

**Run:** 2026-05-11 — `bmad-create-story:validate` (interactive review).
**Result:** All 13 findings applied to `1-13-runner-broker-and-deterministic-mock-runner-adapter.md`. Story remains `ready-for-dev`.

## Critical fixes applied

1. **Pre-reserved `rex_` id ordering.** AC2 + AC3 + Task 4 + Task 5 now make the `rex_` id reservation the first step of `RunnerBroker.dispatch`, before bundle assembly. Resolves the contradiction with `context-bundle.v1.schema.json` requiring `runnerExecutionId` at validation time. `ContextBundleService.create` signature widened to `(workflowRunId, stage, runnerExecutionId, contextBundleVersion, actor)`.
2. **`RunnerStage` registry enum.** Task 1 now mandates a new `domain/registry/RunnerStage.java` (initial values `investigation`, `execution`) registered in `DomainRegistry` + `PersistedRegistryValues` + `RegistryContractTest`, enforced at the application boundary. No DB CHECK added (deferred — application-boundary parser is the source of truth, matching the existing `RunnerExecutionStatus` shape against a `text` column).
3. **Idempotency wiring.** Task 4 dispatch now explicitly calls `IdempotencyService.checkAndReserve` with a canonical `(workflowRunId, stage, nextContextBundleVersion)` fingerprint; on `REPLAY` returns `RunnerDispatchResult.Replayed(handle)` without redispatching; on success calls `IdempotencyService.complete(...)` with the row's public id as `resultRef`. Mirrors 1.12's typed-failure-result pattern.
4. **AC5 workflow-state split made explicit.** AC5 now spells out which `FailureCategory` values drive `EXECUTING → FAILED` (the 4 listed in `WorkflowTransitionTable.ALLOWED_RUNNER_FAILURE_CATEGORIES`) and which 3 do not (`runner_late_result`, `runner_duplicate_result`, `runner_malformed_output`) — the latter only mutate the runner-execution row and emit a `RUNNER_FAILED` event with no workflow-state change. Anti-patterns list reinforces.
5. **MockRunnerAdapter classpath fix.** Production scenarios move to `src/main/resources/runner-scenarios/` so the main-source `@Profile("runners.mock")` bean can load them at runtime under any profile. Test-only adversarial scenarios stay in `src/test/resources/runner-scenarios/`. AC10 + Task 3 + Project Structure Notes all reflect the split.

## Enhancements applied

6. **`RunnerExecutionEventPort` allow-list** widened to include `RECOVERY_RECONCILED` (orphan-recovery signal in Task 4 step 4).
7. **`RunnerAdapter` port surface** spelled out concretely: `dispatch(RunnerDispatchRequest) → RunnerDispatchAck`, `poll(...) → RunnerPollStatus`, `tryReadResult(...) → Optional<byte[]>`, `cancel(...) → void`. Plus typed records `RunnerDispatchRequest`, `RunnerDispatchAck`, `RunnerExecutionHandle`, `RunnerResultEnvelope`, `RunnerFailure`, `RunnerDispatchResult` (sealed `Dispatched | Replayed`).
8. **`onTimeout` invocation** pinned to a Spring `@Scheduled` bean in `infrastructure.config` calling `RunnerBroker.scanForTimeouts()` with configurable interval + batch size.
9. **Artifact-type mapping** spelled out in Task 4 step 5: runner-result wire-form `spec` / `implementationPlan` / `prOutput` → `ArtifactType.SPEC` / `IMPLEMENTATION_PLAN` / `PR_OUTPUT` (verify at use site via the registry parser).
10. **Context-bundle file storage** locked in to `{deliveryline.home}/runner-scratch/{rex_id}/context-bundle.v1.json` even in mock mode, so Epic 3's real Docker adapter does not reshape the on-disk contract. Result file lives at `runner-result.v1.json` in the same scratch directory.
11. **`ActorContext` → `WorkflowTransitionService.TransitionActor` translator** prescribed as a static helper on `ActorContext` (or package-private utility in `application/runner/`) so a third actor type is not introduced.

## Optimizations applied

12. **Git Intelligence section trimmed** from a 6-line commit list to a 2-line summary pointing to "Previous Story Intelligence."
13. **Anti-patterns "Do NOT" callout** added as the first subsection of Dev Notes so the dev agent reads the disaster-prevention rules before the architecture references.

## Notes for the dev agent

- The dispatch flow's ordering invariant is load-bearing: **reserve id → fingerprint + idempotency reserve → bundle build/validate → DB transaction (row insert + RUNNER_STARTED event) → idempotency complete → write bundle file → adapter.dispatch**. Reordering any step breaks the schema validation or the outbox guarantee.
- The AC5 split is the most likely place for a future dev to accidentally regress — the temptation to add `runner_malformed_output` to `WorkflowTransitionTable.ALLOWED_RUNNER_FAILURE_CATEGORIES` "for symmetry" must be resisted. The anti-patterns list and AC5 wording are both designed to make that resistance loud.
- `RunnerStage` registry must extend `RegistryContractTest` — that test catches the registry/enum/SQL drift that has been the source of multiple 1.x regressions.
