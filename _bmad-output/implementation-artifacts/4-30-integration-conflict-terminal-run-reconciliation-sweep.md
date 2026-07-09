# Story 4.30: Integration-Conflict Terminal-Run Guard + Self-Healing Reconciliation Sweep

Status: ready-for-dev

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

- [ ] **Task 1 — Detector terminal-run guard (AC1)**
  - [ ] Extract a single source of truth for terminality: add `WorkflowState.isTerminal()` (or a shared constant) covering `COMPLETED`/`TAKEN_OVER`/`RECONCILED`; refactor `RecoveryService.TERMINAL_STATES` and `WorkflowInspectionService.RECONCILE_TERMINAL_STATES` (added by 4.6 P1) to reference it (avoid a 3rd hardcode).
  - [ ] In `IntegrationConflictDetectionService`, before `writePort.insertIfAbsent(request)` (`:463`), `continue` + `log.debug` when `WorkflowState.fromValue(row.currentState()).isTerminal()`. Guard null/unparseable state defensively (treat as non-terminal → proceed, since detection should not silently drop on a bad state string).
  - [ ] Unit test: a scan row with `currentState="Reconciled"` → `insertIfAbsent` never called; a non-terminal row still inserts.
- [ ] **Task 2 — Terminal-run read query (AC2, Reconciliation 4)**
  - [ ] `IntegrationConflictReadPort.findUnresolvedConflictsOnTerminalRuns(int batchLimit)` + adapter SQL (`join workflow_runs r ... where c.resolved_at is null and c.archived_at is null and r.current_state in ('Completed','TakenOver','Reconciled') order by c.id asc limit :batchLimit`). Return `ConflictResolutionView` (reuse) or a lean id+run row.
- [ ] **Task 3 — Sweep service + scheduler (AC2, AC4, AC5, Reconciliation 2/3/6)**
  - [ ] `application.integration.conflict.IntegrationConflictTerminalRunReconciliationSweepService.sweep()` → `SweepResult(found, cleared, batchLimitHit)`; per stranded conflict: `integrationConflictService.lockRunForReconcile(runId)` then clear per OQ-1; per-item WARN; batch-limit WARN; INFO tick summary. Framework-trigger-free.
  - [ ] `infrastructure.config` `IntegrationConflictTerminalSweepConfiguration` (`@Configuration @EnableScheduling @ConditionalOnProperty @Scheduled fixedDelayString`), mirror `SplitRollupSweepConfiguration`.
  - [ ] Properties record (mirror `RollupSweepProperties`) for `enabled`/`interval-ms`/`batch-limit`; register test `application.yml` values ([[validated-config-needs-test-yaml]]).
  - [ ] IF OQ-1 = system-resolve: add the SYSTEM-actor `recovery_actions` insert + (optional) new `ReconciliationDecision.SYSTEM_TERMINAL_RUN` registry value (three-site/set-equality per 4.6 Reconciliation 8) + `RECOVERY_RECONCILED` event append. IF OQ-1 = log-only: skip the write leg, WARN only.
- [ ] **Task 4 — Tests + ArchUnit (AC6)**
  - [ ] Real-PG `IntegrationConflictTerminalRunSweepIT` (`*IT`): seed via the 4.17 adapter an unresolved conflict on a run set to `RECONCILED` → sweep clears it → `findUnresolvedConflictsOnTerminalRuns` empty + (if system-resolve) `recovery_actions` row present.
  - [ ] Unit: sweep no-op with no terminal-run conflicts; batch-limit WARN; disabled-flag no-bean (context test).
  - [ ] ArchUnit `ONLY_CONFLICT_PACKAGE_MAY_WRITE_INTEGRATION_CONFLICTS` green (sweep writes via the in-package service). Verify in the Failsafe slice ([[archunit-runs-in-failsafe-not-surefire]]).
- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] Detector skip → DEBUG (`workflowRunId`+`integrationLinkId`+`currentState`). Sweep: INFO tick summary (`found`+`cleared`+`batchLimit`), WARN per auto-clear (`conflictId`+`workflowRunId`+`currentState`), WARN batch-limit. No secrets/payloads — ids/states only.
  - [ ] Pin the detector-skip DEBUG and the sweep per-item WARN with a ListAppender assertion.

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

### Debug Log References

### Completion Notes List

### File List
