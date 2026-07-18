# Story 4.30: Integration-Conflict Terminal-Run Guard + Self-Healing Reconciliation Sweep

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner relying on `RecoveryService.reconcile` to clear every detected integration conflict,
I want conflicts to never get stranded on a workflow run that has already terminalized to `Reconciled` (or any terminal state) — both by stopping the 4.17 detector from creating them there AND by a self-healing sweep that clears any that slip through the race window,
so that the "manifestation (b)" gap left open by story 4.6's P3a per-run advisory lock is closed and no `integration_conflicts` row can linger unresolved-but-unreconcilable forever.

## Context & Central Reconciliation (READ FIRST)

**This story exists ONLY because of a code-review split-out from story 4.6 (P3b).** It is a BACKEND-ONLY hardening story. Read the 4.6 Review Findings (`4-6-recovery-service-reconcile-...md` §"Review Findings — 2026-07-09", items **P3a/P3b**) and `deferred-work.md` §"code review of 4-6" before coding — that is the authoritative problem statement.

**The gap (manifestation "b" of the D1 race).** Story 4.6's D1 patch made reconcile *per-conflict*: a run terminalizes to `RECONCILED` only when the reconcile resolves the run's **last** unresolved conflict. Story 4.6's follow-up **P3a** added a per-run advisory lock (`pg_advisory_xact_lock(0x5243, hashtext(runId))`) inside `RecoveryService.performReconcilePrep`, which serializes concurrent reconciles **on the same run** and fully closes manifestation **(a)** (two reconciles both skipping terminalization → run stuck non-terminal with zero conflicts).

**P3a does NOT close manifestation (b):** the per-run reconcile lock is a *different* advisory key from the one the 4.17 detector holds (`IntegrationConflictPersistenceAdapter.CONFLICT_SWEEP_ADVISORY_LOCK_KEY` = `0x49434F4E` "ICON", sweep-wide). So the reconcile lock does **not** serialize against the detector. The failure sequence:

1. A run has one open conflict; the workflow owner reconciles it. Being the last conflict, the run terminalizes to `RECONCILED` and commits.
2. Concurrently (or just after), the 4.17 `IntegrationConflictDetectionService` sweep — which does **not** take the reconcile lock — detects fresh external drift on that same run's link and `insertIfAbsent`s a NEW `integration_conflicts` row for the now-terminal run.
3. That conflict is now **stranded unresolvable**: `RecoveryService.reconcile` rejects it with `RECONCILE_NOT_APPLICABLE` (terminal-state guard, `RecoveryService.java:1020`), and after story 4.6's **P1** the `reconcile_conflict` allowed-action overlay no longer even advertises on terminal runs. The row sits `resolved_at IS NULL` forever, and `countUnresolvedByCategoryAndIntegration()` over-reports.

**Two complementary fixes — do BOTH (belt-and-suspenders, mirrors the 3f-7 hook + 3f-8 sweep pairing).** Neither alone is sufficient: the guard prevents the *common* case at the source but cannot retroactively clear rows already stranded (or created in the sub-millisecond window between the detector's state-read and its insert); the sweep heals the residue but a guard is cheaper than sweeping every tick.

### HEADLINE RECONCILIATIONS

1. **PRIMARY (option i) — the 4.17 detector must SKIP creating conflicts for runs in a terminal state. The run's `currentState` is ALREADY in hand at the detection site — this is a near-one-line guard, not a new query.** `IntegrationLinkScanRow` already carries `currentState` (`IntegrationLinkScanRow.java:30`; the scan SQL join-fetches `r.current_state` — `IntegrationConflictPersistenceAdapter.SCAN_ACTIVE_LINKS_SQL:74`), and the detection service already reads it into `internal_state_snapshot` (`IntegrationConflictDetectionService.java:542`). **Bind: before `writePort.insertIfAbsent(request)` (`IntegrationConflictDetectionService.java:463`), skip (continue + DEBUG-log "skipping terminal-run conflict") when `row.currentState()` parses to a terminal `WorkflowState` (`COMPLETED`/`TAKEN_OVER`/`RECONCILED` — mirror `RecoveryService.TERMINAL_STATES`, do NOT re-hardcode the set in a third place; extract a shared `WorkflowState.isTerminal()` or a single constant referenced by both).** This prevents the strand at the source for every non-racing case. [Source: IntegrationConflictDetectionService.java:463,542; IntegrationLinkScanRow.java:30; RecoveryService.java:183 TERMINAL_STATES]

2. **BACKSTOP (option ii) — a `@ConditionalOnProperty`-gated, advisory-locked self-healing sweep that clears unresolved conflicts belonging to terminal runs. Mirror `SplitRollupReconciliationSweepService` / `SplitRollupSweepConfiguration` EXACTLY.** The sweep covers (a) rows stranded BEFORE this story shipped and (b) the tiny TOCTOU window where the detector read a non-terminal state that terminalized before its insert committed. **Bind: new application-layer `IntegrationConflictTerminalRunReconciliationSweepService.sweep()` (framework-trigger-free, like `SplitRollupReconciliationSweepService`) + a `infrastructure.config` `@Configuration @EnableScheduling @ConditionalOnProperty(name="deliveryline.integration-conflict.terminal-sweep.enabled") @Scheduled(fixedDelayString="${deliveryline.integration-conflict.terminal-sweep.interval-ms:60000}")` trigger (mirror `SplitRollupSweepConfiguration.java` byte-for-byte in shape). A disabled flag registers NO bean and adds ZERO scheduled work.** [Source: SplitRollupReconciliationSweepService.java; SplitRollupSweepConfiguration.java:27-44; RollupSweepProperties.java]

3. **What "clear" means for the sweep is the DESIGN DECISION (see OQ-1) — provisional binding: SYSTEM-RESOLVE the row (not re-open the run, not silently delete).** A terminal run is done; re-opening it (option iii) reverses a governed terminal transition and is out of scope / dangerous. The provisional binding: the sweep marks each stranded conflict resolved via a SYSTEM-actor `recovery_actions` row (`action_type='reconcile'`, `actor_type='system'`, `reviewer_role='system'`, a sweep correlation id) so `resolved_by_action_id` FK is satisfied and the audit trail records WHY it was auto-cleared, then `markResolved`. It emits a `RECOVERY_RECONCILED` event with a `reconciliationDecision` of a NEW `ReconciliationDecision.SYSTEM_TERMINAL_RUN` value **OR** (lighter) reuses a WARN-log-only "surfaced, not auto-mutated" posture. **Confirm OQ-1 before implementing the write leg.** [Source: 4.6 Reconciliation 3/4; RecoveryActionWriteCommand 9-arg; V36 resolved_by_action_id FK]

4. **NEW single read-port query — `IntegrationConflictReadPort.findUnresolvedConflictsOnTerminalRuns(int batchLimit)` → `List<ConflictResolutionView>` (or a lean row) — keyset/bounded, mirroring the 4.17 sweep's batch-limit + no-silent-truncation WARN.** `IntegrationConflictReadPort` today has only `listUnresolved(ConflictFilter)`, `findByPublicId`, `countUnresolvedByCategoryAndIntegration` (`IntegrationConflictReadPort.java:18-26`) — none filter by run terminality. **Bind: add the query + adapter SQL joining `workflow_runs` where `c.resolved_at is null and c.archived_at is null and r.current_state in ('Completed','TakenOver','Reconciled')`, `limit :batchLimit`.** If `found == batchLimit`, WARN "more may remain, healing next tick" (mirror `SplitRollupReconciliationSweepService.java:110-118`). [Source: IntegrationConflictReadPort.java:18-26; SplitRollupReconciliationSweepService.java:110-124]

5. **NO Flyway migration. NO new state/transition edge. NO REST/CLI/FE.** All columns (`integration_conflicts.resolved_at`/`resolved_by_action_id` + FK, `recovery_actions` `action_type='reconcile'` CHECK) already exist (V36/V1). The sweep is a scheduled backend job; it surfaces via logs + existing unresolved-conflict counts, not a new API. (If OQ-1 chooses a new `ReconciliationDecision` value, that is a registry fan-out — Reconciliation 8 of 4.6 — NOT a migration.) [Source: 4.6 Reconciliation 3; V36; V1:265-293]

6. **The sweep write must honor the conflict-package write boundary AND take the reconcile per-run lock, so it never races a live reconcile of the same run.** Route the resolve through `IntegrationConflictService` (in `application.integration.conflict`) exactly as `RecoveryService` does, and take `lockRunForReconcile(runId)` first (the P3a lock, `IntegrationConflictService.lockRunForReconcile`) so a sweep and a live operator reconcile on the same run serialize. The ArchUnit rule `ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS` (already extended for `markResolved`/`lockRunForReconcile`) keeps the write in-package. [Source: 4.6 P3a; ArchitectureRuleCatalog.java:511-529; IntegrationConflictService.java]

## Scope Boundary — what 4.30 BUILDS vs REUSES vs DEFERS

| Concern | 4.30 | Note |
|---|---|---|
| 4.17 detector terminal-run skip guard (before `insertIfAbsent`) | **BUILD** | Reconciliation 1 — the primary, cheap fix |
| Shared `WorkflowState.isTerminal()` (or single referenced constant) replacing the 3rd hardcode | **BUILD** | Reconciliation 1 — de-dup COMPLETED/TAKEN_OVER/RECONCILED |
| `IntegrationConflictTerminalRunReconciliationSweepService.sweep()` (application layer, trigger-free) | **BUILD** | Reconciliation 2 — mirror `SplitRollupReconciliationSweepService` |
| `infrastructure.config` `@ConditionalOnProperty @Scheduled` trigger + properties record | **BUILD** | Reconciliation 2 — mirror `SplitRollupSweepConfiguration` + `RollupSweepProperties` |
| `IntegrationConflictReadPort.findUnresolvedConflictsOnTerminalRuns(int)` + adapter SQL | **BUILD** | Reconciliation 4 |
| Sweep resolve-write (SYSTEM-actor `recovery_actions` + `markResolved`, lock-first, via `IntegrationConflictService`) | **BUILD (pending OQ-1)** | Reconciliation 3/6 |
| Real-PG `*IT`: seed a conflict on a terminalized run → sweep clears it + `findUnresolved…` no longer returns it | **BUILD** | AC — [[springboot-testcontainers-test-must-be-IT]] |
| Detector-guard unit test: terminal-run scan row → `insertIfAbsent` NOT called | **BUILD** | AC |
| Re-open the terminal run (option iii) | **DO NOT BUILD** | Reverses a governed terminal transition — dangerous, out of scope |
| Flyway migration / new WorkflowState / new transition edge | **DO NOT BUILD** | Reconciliation 5 |
| REST / CLI / FE surfacing of the sweep | **DO NOT BUILD** | Observability via logs + existing counts |
| Changing P3a's per-run reconcile lock | **DO NOT BUILD** | 4.6 already shipped + tested it |

## Acceptance Criteria

1. **Given** the 4.17 detection sweep scanning an active link whose run is in a terminal state (`Completed`/`TakenOver`/`Reconciled`), **When** a fresh external-vs-internal drift would otherwise create a conflict, **Then** `insertIfAbsent` is NOT called for that link and a DEBUG log records the skip (`row.currentState()` already available — Reconciliation 1). No `integration_conflicts` row is created for a terminal run.
2. **Given** an existing unresolved `integration_conflicts` row whose run is terminal (created before this story, or in the detector's TOCTOU window), **When** the terminal-run reconciliation sweep tick runs, **Then** the row is cleared (per OQ-1's chosen semantics) and `findUnresolvedConflictsOnTerminalRuns` no longer returns it; a per-item WARN records the auto-clear with `conflictId`+`workflowRunId`+`currentState`.
3. **Given** NFR19 (no silent overwrite) and the conflict-package write boundary, **When** the sweep clears a row, **Then** the write routes through `IntegrationConflictService` under the P3a per-run advisory lock (`lockRunForReconcile`) so it serializes against a live operator reconcile of the same run, and the audit trail records a SYSTEM-actor action (never an anonymous DB mutation).
4. **Given** the `@ConditionalOnProperty` gate `deliveryline.integration-conflict.terminal-sweep.enabled`, **When** the flag is absent/false, **Then** neither the scheduled bean nor `@EnableScheduling` for it is registered (byte-identical to pre-story behavior — mirror `SplitRollupSweepConfiguration` AC4).
5. **Given** the sweep's batch limit, **When** a tick fills the batch, **Then** a WARN records "more may remain, healing next tick" (no silent truncation — mirror `SplitRollupReconciliationSweepService`).
6. **Given** the test suite, **Then** tests cover: detector skips terminal-run insert (unit); sweep clears a real seeded terminal-run conflict and excludes it thereafter (real-PG `*IT`); sweep is a no-op when no terminal-run conflicts exist; disabled flag registers no bean; batch-limit WARN. ArchUnit conflict-write boundary stays green.

## Tasks / Subtasks

- [x] **Task 1 — Detector terminal-run guard (AC1)**
  - [x] Extract a single source of truth for terminality: add `WorkflowState.isTerminal()` (or a shared constant) covering `COMPLETED`/`TAKEN_OVER`/`RECONCILED`; refactor `RecoveryService.TERMINAL_STATES` and `WorkflowInspectionService.RECONCILE_TERMINAL_STATES` (added by 4.6 P1) to reference it (avoid a 3rd hardcode). — Added `WorkflowState.isTerminal()` + a private `TERMINAL_STATES` set on the enum; removed both consumers' local hardcodes and routed them through `.isTerminal()`.
  - [x] In `IntegrationConflictDetectionService`, before `writePort.insertIfAbsent(request)` (`:463`), `continue` + `log.debug` when `WorkflowState.fromValue(row.currentState()).isTerminal()`. Guard null/unparseable state defensively (treat as non-terminal → proceed, since detection should not silently drop on a bad state string). — Guard placed at the `recordConflict` call site; `isTerminalRun(String)` catches parse failures → non-terminal.
  - [x] Unit test: a scan row with `currentState="Reconciled"` → `insertIfAbsent` never called; a non-terminal row still inserts. — `terminalRunSkipsConflictInsertAndLogsDebug` + `nonTerminalRunStillInsertsConflict`.
- [x] **Task 2 — Terminal-run read query (AC2, Reconciliation 4)**
  - [x] `IntegrationConflictReadPort.findUnresolvedConflictsOnTerminalRuns(int batchLimit)` + adapter SQL (`join workflow_runs r ... where c.resolved_at is null and c.archived_at is null and r.current_state in ('Completed','TakenOver','Reconciled') order by c.id asc limit :batchLimit`). Return `ConflictResolutionView` (reuse) or a lean id+run row. — Lean `TerminalRunConflict(conflictId, workflowRunId, currentState)` record (currentState needed for the AC2 per-item WARN).
- [x] **Task 3 — Sweep service + scheduler (AC2, AC4, AC5, Reconciliation 2/3/6)**
  - [x] `application.integration.conflict.IntegrationConflictTerminalRunReconciliationSweepService.sweep()` → `SweepResult(found, cleared, batchLimitHit)`; per stranded conflict: `integrationConflictService.lockRunForReconcile(runId)` then clear per OQ-1; per-item WARN; batch-limit WARN; INFO tick summary. Framework-trigger-free. One bounded tx per conflict via `TransactionTemplate`.
  - [x] `infrastructure.config` `IntegrationConflictTerminalSweepConfiguration` (`@Configuration @EnableScheduling @ConditionalOnProperty @Scheduled fixedDelayString`), mirror `SplitRollupSweepConfiguration`.
  - [x] Properties record (mirror `RollupSweepProperties`) for `enabled`/`interval-ms`/`batch-limit`; register test `application.yml` values ([[validated-config-needs-test-yaml]]). — `IntegrationConflictTerminalSweepProperties`; registered UNCONDITIONALLY in `IntegrationConflictConfiguration`; keys mirrored into both `application.yml` files.
  - [x] IF OQ-1 = system-resolve: add the SYSTEM-actor `recovery_actions` insert + (optional) new `ReconciliationDecision.SYSTEM_TERMINAL_RUN` registry value (three-site/set-equality per 4.6 Reconciliation 8) + `RECOVERY_RECONCILED` event append. — **OQ-1 = SYSTEM-resolve.** SYSTEM-actor `recovery_actions` row (`reconcile`/`system`/`succeeded`/`reviewer_role=system`, inserted `succeeded` directly like classify) + `RECOVERY_RECONCILED` event. **Deliberately did NOT add a `ReconciliationDecision` value** (see Completion Notes) — zero registry/OpenAPI/FE fan-out.
- [x] **Task 4 — Tests + ArchUnit (AC6)**
  - [x] Real-PG `IntegrationConflictTerminalRunSweepIT` (`*IT`): seed an unresolved conflict on a run set to `RECONCILED` → sweep clears it → `findUnresolvedConflictsOnTerminalRuns` empty + `recovery_actions` row present. Plus a non-terminal-run control (row left untouched). GREEN on real PG (2/2).
  - [x] Unit: sweep no-op with no terminal-run conflicts; batch-limit WARN; disabled-flag no-bean (context test). — 5 sweep-service unit tests + 3 config-context tests, all GREEN.
  - [x] ArchUnit `ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS` green (sweep writes via the in-package service). Verified in the Failsafe slice — `ArchitectureBoundaryTest` 64/64 GREEN.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Detector skip → DEBUG (`workflowRunId`+`integrationLinkId`+`currentState`). Sweep: INFO tick summary (`found`+`cleared`+`batchLimit`), WARN per auto-clear (`conflictId`+`workflowRunId`+`currentState`), WARN batch-limit. No secrets/payloads — ids/states only.
  - [x] Pin the detector-skip DEBUG and the sweep per-item WARN with a ListAppender assertion. — DEBUG pinned in the detection test; WARN + INFO pinned in the sweep test.

## Review Findings — 2026-07-18

- [x] [Review][Patch] `RECOVERY_RECONCILED` auto-clear event needs a distinguishing detail so timeline consumers can tell a SYSTEM strand-clear from an operator reconcile [IntegrationConflictTerminalRunReconciliationSweepService.java:253-270] — (resolved from Decision D1, option 2) **FIXED:** added `WorkflowEventDetailKeys.AUTO_CLEARED = "autoCleared"` (allow-listed, like `viaSplitRollup`) + a `"autoCleared": {"type":"boolean"}` property in `workflow-history.v1.schema.json` (keeps the `WorkflowEventDetailKeysContractTest` set-equality green), and the sweep now emits `AUTO_CLEARED=true` on the event. Sweep unit test pins the discriminator on every auto-clear event. No OpenAPI/FE fan-out (CLI-only schema).
- [x] [Review][Patch] Sweep read-query re-hardcodes the terminal-state triple a 4th time, defeating the story's single-source-of-truth claim [IntegrationConflictPersistenceAdapter.java `FIND_UNRESOLVED_ON_TERMINAL_RUNS_SQL`] — **FIXED:** added `IntegrationConflictTerminalRunSqlContractTest` (source-scan, mirrors the `CORRELATION_ID` literal-pin pattern) asserting the SQL `current_state in (...)` literals are byte-for-byte the wire values of the `WorkflowState` members where `isTerminal()`, so a 4th terminal state can't silently diverge the query.
- [x] [Review][Patch] `interval-ms<=0 → 60000` clamp is dead for the scheduler; javadoc overclaims runtime normalization [IntegrationConflictTerminalSweepProperties.java] — **FIXED:** the `application.yml` had no misleading inline comment (Blind Hunter's quote was imprecise); the overclaim was in the record javadoc ("non-positive intervalMs/batchLimit clamp to their defaults"), an exact mirror of the accepted `RollupSweepProperties` house pattern. Clarified the 4.30 javadoc to state the `@Scheduled` reads the RAW `interval-ms` property so a non-positive value fails fast at startup (the clamp guards only `batchLimit`, which the sweep reads from the record). Did NOT touch the sibling 3f-8 file (out of scope) nor remove the clamp (structural parity with the mirror).
- [x] [Review][Defer] Deterministic per-conflict failure re-thrashes at the batch head with no attempt cap / dead-letter / cursor-advance [IntegrationConflictTerminalRunReconciliationSweepService.java:156-192] — deferred, pre-existing (shared with the `SplitRollupReconciliationSweepService` house pattern this story mirrors byte-for-byte). A conflict that throws a non-`CONFLICT_ALREADY_RESOLVED` error every tick keeps the lowest `c.id`, so `order by c.id asc limit :batchLimit` re-fetches it at the head forever; ≥`batchLimit` such poison rows would starve the remainder. Self-surfacing via the per-tick WARN; internal-only writes make deterministic failure unlikely.

## Dev Notes

### Relevant architecture patterns and constraints

- **`SplitRollupReconciliationSweepService` (3f-8) is the EXACT structural template** — trigger-free application service returning a `SweepResult` record, paired with a `@ConditionalOnProperty`-gated `infrastructure.config` `@Scheduled` trigger (`SplitRollupSweepConfiguration`) and a properties record (`RollupSweepProperties`). Copy its shape: batch-limit discovery via a read-port query, per-item best-effort action, no-silent-truncation WARN, INFO tick summary. Every `@Scheduled`/`@EnableScheduling` lives in `infrastructure.config` (never the application layer) — [[post-commit-hook-needs-requires-new]] discipline is about tx, this is about scheduler placement.
- **The detector already has the run state.** `IntegrationLinkScanRow.currentState` (`:30`) is join-fetched and written to `internal_state_snapshot` (`IntegrationConflictDetectionService.java:542`) — the guard is essentially free. This is the single most important fact: option (i) is NOT a new query.
- **Reuse the P3a per-run reconcile lock in the sweep** so a sweep and a live reconcile of the same run serialize (`IntegrationConflictService.lockRunForReconcile`, key `0x5243`). Do NOT invent a new lock. The sweep's own multi-instance safety comes from the tx-scoped advisory lock + the idempotent `markResolved ... where resolved_at is null` (0 rows on a concurrent clear → skip, like the 3f-8 sweep's re-read).
- **No I/O under lock / atomicity** — the sweep's resolve (lock + optional recovery_actions insert + markResolved + event) is one bounded tx per conflict; there is NO external system call (unlike operator reconcile's post-commit side-effect — the sweep is purely internal bookkeeping), so [[caught-idempotency-conflict-poisons-shared-tx]] does not bite here.
- **Terminality single-source-of-truth** — 4.6 P1 introduced a second hardcode of `{COMPLETED,TAKEN_OVER,RECONCILED}` (`WorkflowInspectionService.RECONCILE_TERMINAL_STATES`) alongside `RecoveryService.TERMINAL_STATES`. This story adds a third consumer (detector + sweep query), so extract `WorkflowState.isTerminal()` and collapse all three.

### Project Structure Notes

- New main: `IntegrationConflictTerminalRunReconciliationSweepService` + read-port query + adapter SQL (`application.integration.conflict` / `adapters.persistence`); `IntegrationConflictTerminalSweepConfiguration` + properties (`infrastructure.config`); detector guard edit (`IntegrationConflictDetectionService`); `WorkflowState.isTerminal()` (`domain.registry`).
- Test/fixture: `IntegrationConflictTerminalRunSweepIT` (new real-PG), detector-guard unit test, sweep unit tests, disabled-flag context test, test `application.yml` sweep keys.
- Variance: the SYSTEM-resolve vs log-only fork (OQ-1) is unresolved — provisional binding = system-resolve. Re-open (option iii) explicitly rejected.

### References

- [Source: _bmad-output/implementation-artifacts/4-6-recovery-service-reconcile-reconcile-workflow-state-on-integration-conflict.md#Review Findings — 2026-07-09] — P3a (applied lock) + P3b (this story's mandate).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md#code review of 4-6] — the deferred P3b note.
- [Source: deliveryline-backend/.../application/workflow/SplitRollupReconciliationSweepService.java] — the sweep template (3f-8).
- [Source: deliveryline-backend/.../infrastructure/config/SplitRollupSweepConfiguration.java:27-44] — the `@ConditionalOnProperty @Scheduled` trigger template.
- [Source: deliveryline-backend/.../application/integration/conflict/IntegrationConflictDetectionService.java:463,542] — the `insertIfAbsent` site + existing `currentState` read.
- [Source: deliveryline-backend/.../application/integration/conflict/spi/IntegrationLinkScanRow.java:30] — `currentState` already on the scan row.
- [Source: deliveryline-backend/.../application/integration/conflict/spi/IntegrationConflictReadPort.java:18-26] — read surface to extend.
- [Source: deliveryline-backend/.../application/recovery/RecoveryService.java:183] — `TERMINAL_STATES`; :1020 terminal-state reconcile guard.
- [Source: deliveryline-backend/.../application/integration/conflict/IntegrationConflictService.java] — `lockRunForReconcile` (P3a) + `resolveConflict`; the in-package write boundary.
- [Source: deliveryline-backend/.../test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:511-529] — `ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS` (already guards markResolved/lockRunForReconcile).
- [Source: sprint-status.yaml] — 4-6 in-progress (parent); 4-17 done (producer); this story = 4-30.

### Open Questions (for Alex — provisional binding applied, do not block dev)

- **OQ-1 — sweep semantics for a terminal-run conflict: SYSTEM-resolve (recommended) vs log-only vs re-open.** Provisional: **SYSTEM-resolve** — mark the row resolved via a SYSTEM-actor `recovery_actions` row + `RECOVERY_RECONCILED` event (audit-visible auto-clear), so unresolved counts stop over-reporting and the partial-unique dedup index frees for a genuine future re-detect. Alternatives: **log-only** (surface via WARN + a metric, never mutate — safest but leaves the row unresolved and counts inflated) or **re-open the run** (rejected — reverses a governed terminal transition). Confirm SYSTEM-resolve, or downgrade to log-only.
- **OQ-2 — is the detector guard (option i) alone enough, making the sweep optional?** The guard closes every non-racing case; the sweep only covers pre-existing strands + the sub-ms TOCTOU window. If Alex judges the residual negligible AND no pre-existing stranded rows exist in prod, the sweep (Tasks 2/3) could be deferred and this story reduced to the guard + a one-off cleanup query. Recommended: ship both (cheap, and the sweep is the durability guarantee 3f-8 established as the house pattern).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (BMad dev-story workflow)

### Debug Log References

- `maven-argLine-direct-goal-crash` — `surefire:test`/`failsafe:integration-test` direct goals crash on `@{argLine}`; ran via the `test`/`integration-test` lifecycle phases with `-Djacoco.skip=true` instead.
- Two-constructor bean wiring: the sweep service's public `Clock`-defaulting constructor + package-private `Clock`-accepting one initially failed context load ("No default constructor found") — added `@Autowired` to the public constructor (mirrors `IntegrationConflictService`). Caught by `IntegrationConflictTerminalRunSweepIT` context load, fixed, re-GREEN.
- Spotless reformat required after hand-editing (`spotless:apply`) — em-dashes in new source verified as proper U+2014 by codepoint (no mojibake).

### Completion Notes List

- **OQ-1 resolved = SYSTEM-resolve, WITHOUT a new `ReconciliationDecision` value.** The provisional binding said "SYSTEM-resolve via a SYSTEM-actor `recovery_actions` row + `RECOVERY_RECONCILED` event", with the new `ReconciliationDecision.SYSTEM_TERMINAL_RUN` value flagged **(optional)**. I deliberately did NOT add it: a `ReconciliationDecision` is an OPERATOR-facing decision (it fans out to `DomainRegistry`, `RegistryContractTest`, `openapi.json`, the FE `schema.d.ts`, `ConflictReconciliationSuggester`, and the REST reconcile request) — polluting that operator vocabulary with a system-internal auto-clear token would be wrong and would red `check:api`/FE. Instead the `RECOVERY_RECONCILED` event carries the semantics via its SYSTEM actor + a `reason` detail; the audit trail is fully honest with ZERO registry/OpenAPI/FE fan-out. If Alex wants the explicit decision token later it can be added as a system-only registry value.
- **OQ-2 resolved = ship BOTH** (guard + sweep), per the recommendation and the 3f-7 hook + 3f-8 sweep house pattern.
- **NO migration, NO new WorkflowState/transition edge, NO new DomainErrorCode/WorkflowEventType/WorkflowEventDetailKey, NO REST/CLI/FE** (Reconciliation 5). `action_type='reconcile'` is a pre-reserved V1 slot; `reviewer_role='system'` and `actor_type='system'` already valid; `recovery_actions` inserted `succeeded` directly (one-tx, no post-commit side-effect — the classify_failure R16 rationale).
- **Sweep write boundary + P3a lock (AC3):** each conflict is SYSTEM-resolved in its own bounded `TransactionTemplate` tx that takes `IntegrationConflictService.lockRunForReconcile(runId)` FIRST, then routes the resolve through `IntegrationConflictService.resolveConflict` (in-package) — never a raw `IntegrationConflictWritePort` call, so `ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS` stays green. A concurrent live reconcile that already cleared the row makes `resolveConflict` throw `CONFLICT_ALREADY_RESOLVED` → the per-conflict tx rolls back cleanly and the sweep counts it a benign skip (not a failure, no false "auto-cleared" WARN).
- **Terminality single-source-of-truth (Reconciliation 1):** `WorkflowState.isTerminal()` now backs the reconcile guard (`RecoveryService`), the allowed-actions overlay (`WorkflowInspectionService`), and the new detector guard + sweep query — the three prior hardcodes collapsed to one.
- **Default posture:** the sweep is OFF by default in both `application.yml` files (opt-in per-deploy, mirroring `rollup-sweep`); the always-active detector guard prevents the common case, and the sweep is the durability backstop for pre-existing strands + the TOCTOU window. AC4 disabled-flag no-bean is pinned by the config context test.
- Detector guard is state-scoped: a terminal-run scan row is skipped (DEBUG), a non-terminal row still inserts (control test). Defensive `isTerminalRun` treats null/unparseable state as non-terminal so a bad state string never silently drops a genuine conflict.

### File List

**Main (changed):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowState.java` — added `isTerminal()` + private `TERMINAL_STATES` set (single source of truth).
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java` — removed local `TERMINAL_STATES`; 2 call sites → `.isTerminal()`.
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowInspectionService.java` — removed local `RECONCILE_TERMINAL_STATES`; call site → `!state.isTerminal()`.
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/conflict/IntegrationConflictDetectionService.java` — terminal-run guard before `recordConflict` + `isTerminalRun` helper + DEBUG skip log.
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/conflict/spi/IntegrationConflictReadPort.java` — `findUnresolvedConflictsOnTerminalRuns(int)`.
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IntegrationConflictPersistenceAdapter.java` — `FIND_UNRESOLVED_ON_TERMINAL_RUNS_SQL` + impl.
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/IntegrationConflictConfiguration.java` — register `IntegrationConflictTerminalSweepProperties`.
- `deliveryline-backend/src/main/resources/application.yml` — new `deliveryline.integration-conflict.terminal-sweep.*` (enabled:false).
- `deliveryline-backend/src/test/resources/application.yml` — mirror (enabled:false).

**Main (new):**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/conflict/spi/TerminalRunConflict.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/conflict/IntegrationConflictTerminalRunReconciliationSweepService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/conflict/IntegrationConflictTerminalSweepProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/IntegrationConflictTerminalSweepConfiguration.java`

**Test (changed):**
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/conflict/IntegrationConflictDetectionServiceTest.java` — 2 guard tests + `rowWithState` helper.

**Test (new):**
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/conflict/IntegrationConflictTerminalRunReconciliationSweepServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/integration/conflict/IntegrationConflictTerminalRunSweepIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/IntegrationConflictTerminalSweepConfigurationTest.java`

### Change Log

- 2026-07-18 — Story 4.30 implemented: 4.17 detector terminal-run guard (skip creating conflicts on terminalized runs) + `WorkflowState.isTerminal()` single-source-of-truth + self-healing `IntegrationConflictTerminalRunReconciliationSweepService` (SYSTEM-resolve, `@ConditionalOnProperty`-gated, OFF by default) + `findUnresolvedConflictsOnTerminalRuns` read query. No migration / registry / REST / FE changes. OQ-1=SYSTEM-resolve (no new `ReconciliationDecision`), OQ-2=ship both.
- 2026-07-18 — Code review (`review`→`done`): 1 decision + 3 patches applied, 1 deferred, 6 dismissed. (D1→P1) new allow-listed `AUTO_CLEARED` event detail key + CLI history schema property so timeline consumers distinguish a SYSTEM terminal-run auto-clear from an operator reconcile; (P2) `IntegrationConflictTerminalRunSqlContractTest` pins the sweep SQL terminal-state literals to `WorkflowState.isTerminal()`; (P3) honest `IntegrationConflictTerminalSweepProperties` javadoc re the inert `interval-ms` clamp. Deferred: poison-row thrash/starvation (shared 3f-8 house-pattern limitation → `deferred-work.md`). Affected tests GREEN (22/22: sweep unit + 2 contract + CLI JSON schema + config context).
