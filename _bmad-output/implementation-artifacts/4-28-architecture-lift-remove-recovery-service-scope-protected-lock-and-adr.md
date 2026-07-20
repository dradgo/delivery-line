# Story 4.28: Architecture Lift — Remove `RecoveryService` Scope-Protected Lock + ADR

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an architect documenting that Epic 4 has fulfilled the deeper-recovery scope that story 1.18 deferred,
I want story 1.18 AC11's ArchUnit scope-protected lock on `RecoveryService` lifted (the rule that asserted the service exposed exactly + only the baseline `retry` + `describeFailure` methods) and a documented ADR recording the lift,
so that the recovery service's deeper actions from stories 4.5–4.9 can land without ArchUnit complaining, and future contributors understand what scope expanded and why.

## Context & Central Reconciliation (READ FIRST)

**This is the LAST story of Epic 4 by ordering convention (cross-cutting closure — mirrors Epic 1 story 1.23, Epic 2 story 2.29, Epic 3 story 3.36 close gates).** It is a **test-infrastructure + documentation** change: you (1) DELETE one ArchUnit rule and its one `@ArchTest` registration, (2) update the Javadoc on `RecoveryService` that advertised the lock, (3) add a governance ADR, and (4) add a **meta-test that asserts the deleted rule stays deleted**. You touch **no production application-service code, no persistence, no runner, no REST, no Flyway migration.** The deeper recovery methods themselves (`resume` / `reconcile` / `rerunFromStep` / `pause` / `classifyFailure`) are built by stories 4.5–4.9 — **NOT here.**

The lock being lifted is `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` in `ArchitectureRuleCatalog` — an ArchUnit rule that fails the build if `RecoveryService` exposes any public method beyond the exact two-signature Epic-1 baseline (`retry(String,String,ActorContext,String)` + `describeFailure(String)`). It was a deliberate scope-creep tripwire installed by story 1.18 AC11 to keep the deeper recovery surface OUT until Epic 4 justified it.

> ⚠️ **THREE epic-AC drifts win against the epic text below (§HEADLINE RECONCILIATIONS). The single most important is Reconciliation 1: the ADR is `0033`, NOT `0010` — the epic's ADR numbering was written when the repo only had ADRs 0001–0009; the repo is now at 0032. Do not create `0010`.**

### HEADLINE RECONCILIATIONS (epic AC text drifts from live code — these bindings win)

1. **THE ADR NUMBER IS `0033`, NOT `0010`.** Epic AC2 says `docs/adr/0010-recovery-service-scope-lift.md` and AC5 says "numbered `0010` continuing the sequence… (numbered 0001 through 0009)." **That sequence framing is stale.** `docs/adr/` currently holds ADRs up through **`0032-replay-safe-aftercommit-helper.md`** (highest on disk). ADRs `0009` and `0010` **do not exist and were never created** — the epic authored those numbers notionally before Epic 3's ADRs (0013, 0019–0027, 0029–0032) landed. **Take the next free number: `0033`.** File name: `docs/adr/0033-recovery-service-scope-lift.md`. **CONFIRM `0033` is still free against the current branch at authoring time** (mirror the [[flyway-v31-cross-branch-collision]] discipline for numbered artifacts — a sibling in-flight story could claim it). Note: story 4.7's referenced `docs/adr/0009-rerun-safe-boundaries.md` is stale in the same way; if 4.7 merges first it will take an earlier free number, so re-check the highest on-disk ADR number at authoring rather than trusting either epic reference. [Source: `docs/adr/` listing — highest is `0032`; epic-04-recovery.md Story 4.28 AC2/AC5]

2. **`docs/adr/README.md` DOES NOT EXIST — AC5 requires cross-linking the new ADR from it.** AC5 says "cross-linked from `docs/adr/README.md` index." There is **no README/index file in `docs/adr/`** (only the numbered ADR files). You must **create `docs/adr/README.md`** as a lightweight index table (one row per ADR: number → title → status) and add the `0033` row, OR (if the team prefers) fold the cross-link into `_bmad-output/planning-artifacts/architecture.md`. **Recommendation: create `docs/adr/README.md`** — it satisfies AC5 literally, is cheap, and gives every prior ADR a discoverable home. Confirm the exact form with the team if uncertain (see Open Questions). [Source: `docs/adr/` listing shows no README; epic AC5]

3. **⛔ DEPENDENCY GATE — stories 4.5–4.9 are ALL `backlog` (unbuilt). AC3's merge-gate + AC6's "deeper methods work end-to-end" are BLOCKED until they land.** Per `sprint-status.yaml`, `4-5`…`4-9` are all `backlog`; `RecoveryService` today still exposes **only** `retry` + `describeFailure` (verified — see Dev Notes). Consequences:
   - **AC1 (remove the rule) + AC2/AC5 (ADR) + AC6-meta-test (rule absence) are FULLY DOABLE NOW** and harmless: removing the tripwire while the surface is still the baseline two methods simply means future additions are no longer auto-caught. The build stays green.
   - **AC6's second clause — "the deeper recovery methods (resume/reconcile/rerun/pause/classify) work end-to-end without triggering ArchUnit failures" — CANNOT be exercised** because those methods do not exist yet.
   - **AC3's merge-gate ("depends on 4.5–4.9 being merged") CANNOT pass.**
   **This story's implementation must be SCOPED to what is buildable today (AC1, AC2, AC5, AC6-absence-test, AC7 doc-ref, AC8 sibling-still-protected).** The end-to-end proof (AC6 clause 2) and the merge-gate (AC3, AC4 close-gate) are **deferred to when 4.5–4.9 have merged** and recorded in `deferred-work.md`. **Do NOT attempt to implement 4.5–4.9 to satisfy this story.** Surface this scoping decision to the operator before finalizing (see Open Questions). [Source: sprint-status.yaml lines 638–642 (`4-5`…`4-9` = backlog); RecoveryService.java public surface]

4. **EXACT delete targets — remove the rule in TWO files, update the Javadoc in a THIRD; KEEP the sibling lock and the shared helpers.**
   - **DELETE** the rule constant `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` — `ArchitectureRuleCatalog.java:784-797`.
   - **DELETE** its registration — `ArchitectureBoundaryTest.java:195-197` (`@ArchTest static final ArchRule recovery_service_is_scope_protected = …`).
   - **UPDATE** the `RecoveryService` class Javadoc — `RecoveryService.java:46-56` — which currently states "**Scope-protected by ArchUnit:** exposes exactly two public methods… The rule `RECOVERY_SERVICE_IS_SCOPE_PROTECTED`… fails the build if any other public method name is added." Rewrite it to note the lock was lifted by story 4.28 (reference ADR `0033`) and that the deeper-recovery surface is now governed by that ADR's "what new scope is now allowed" list rather than an ArchUnit tripwire.
   - **KEEP (AC8 — do NOT lift):** `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` (`ArchitectureRuleCatalog.java:803-816` + its registration `ArchitectureBoundaryTest.java:199-201`), `ONLY_WORKFLOW_TRANSITION_SERVICE_MAY_MUTATE_WORKFLOW_STATE`, and the artifact-operation monopoly rule. Their protection is out of scope.
   - **KEEP the shared private helpers** `exposeOnlyPublicMethodSignatures(...)` (`ArchitectureRuleCatalog.java:1155`) and `methodSignature(...)` (`:1190`) — they are **still used by `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED`** (`:813`). Removing them would break the takeover rule. (The name-only variant `exposeOnlyPublicMethods` at `:1121` has no call sites today and is pre-existing — leave it untouched; do NOT let its removal creep into this story.) [Source: grep — `exposeOnlyPublicMethodSignatures` called at :794 (RECOVERY) and :813 (TAKEOVER)]

5. **The AC6 "meta-test that asserts the rule's absence" is a REFLECTION test, not an ArchUnit `.check()` fixture.** The existing meta-test file `ArchitectureDiagnosticMetaTest.java` proves rules *fire* by calling `Rule.check(invalidFixtureClasses)` and asserting the `AssertionError` message. **That pattern cannot prove a rule's ABSENCE** — a deleted constant cannot be referenced. Implement the absence-guard by **reflection over the two owning classes**: assert `ArchitectureRuleCatalog.class.getDeclaredFields()` contains **no** field named `RECOVERY_SERVICE_IS_SCOPE_PROTECTED`, and `ArchitectureBoundaryTest.class.getDeclaredFields()` contains **no** field named `recovery_service_is_scope_protected`; in the **same** test assert the sibling `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` field **is still present** (regression guard for AC8). This meta-test is what makes the lift durable: a future well-meaning contributor re-adding the tripwire (or accidentally deleting the sibling) fails the build with a message pointing at ADR `0033`. Place it in `org.dradgo.architecture` (new `RecoveryServiceScopeLiftMetaTest`, tagged `@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)` — same tag the other arch tests use so it runs in the Failsafe arch slice, per [[archunit-runs-in-failsafe-not-surefire]]). [Source: ArchitectureDiagnosticMetaTest.java (`.check()` fixture pattern); ArchitectureRuleCatalog.ARCHITECTURE_TAG]

## Scope Boundary — what 4.28 BUILDS vs DEFERS

| Concern | 4.28 | Note |
|---|---|---|
| Delete `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` rule constant | **BUILD** | AC1 — `ArchitectureRuleCatalog.java:784-797` |
| Delete `@ArchTest recovery_service_is_scope_protected` registration | **BUILD** | AC1 — `ArchitectureBoundaryTest.java:195-197` |
| Rewrite `RecoveryService` scope-protection Javadoc → reference ADR 0033 | **BUILD** | Reconciliation 4 — `RecoveryService.java:46-56` |
| ADR `docs/adr/0033-recovery-service-scope-lift.md` (sections a–e per AC2) | **BUILD** | AC2 — number 0033 not 0010 (Reconciliation 1) |
| `docs/adr/README.md` index + `0033` cross-link row | **BUILD** | AC5 — no README exists today (Reconciliation 2) |
| Reflection meta-test: rule + registration ABSENT, sibling PRESENT | **BUILD** | AC6 clause 1 + AC8 (Reconciliation 5) |
| ArchUnit + full arch slice green after removal | **BUILD** | AC1 — verify via Failsafe |
| Recovery walkthrough (story 4.27) "Background" links ADR 0033 | **BUILD if 4.27 merged; else DEFER** | AC7 — 4.27 is `backlog` today; can't edit a non-existent doc |
| End-to-end proof that resume/reconcile/rerun/pause/classify pass ArchUnit | **DEFER** | AC6 clause 2 — needs 4.5–4.9 (all backlog, Reconciliation 3) |
| AC3 merge-gate (4.5–4.9 merged) + AC4 Epic-4 close gate | **DEFER** | Gated on the rest of Epic 4 landing (Reconciliation 3) |
| Any change to `RecoveryService` public methods / persistence / REST | **OUT OF SCOPE** | Built by 4.5–4.9 / 4.10–4.14 |

## Acceptance Criteria

_(Verbatim from epic-04-recovery.md Story 4.28, annotated with the reconciliations that win. Where an AC is dependency-gated, the buildable-now portion is called out.)_

1. **Given** story 1.18 AC11's ArchUnit rule (`RecoveryService` scope-protected — exposes exactly + only `retry` + `describeFailure`), **Then** this story removes that rule from the ArchUnit test class; the class is no longer scope-protected; the ArchUnit / arch-slice tests pass after removal. → **Reconciliation 4**: delete both the catalog constant AND the `@ArchTest` registration; update the `RecoveryService` Javadoc.
2. **Given** `docs/adr/0033-recovery-service-scope-lift.md` _(Reconciliation 1 — NOT 0010)_, **Then** the ADR documents: **(a)** what was scope-protected (the original 1.18 rule + rationale — pre-Epic-4 scope-creep prevention); **(b)** what changed (Epic 4 stories 4.5–4.9 fulfilled the deeper-recovery scope); **(c)** what new scope is now allowed (`resume` / `reconcile` / `rerunFromStep` / `pause` / `classifyFailure` + their REST endpoints 4.10–4.14 — this list is **exhaustive** for E4); **(d)** what is still NOT allowed (no further recovery methods without updating this ADR); **(e)** how to add a new recovery method in a future version (write ADR → add method + tests + REST endpoint + UI → update this ADR's "allowed" list).
3. **Given** the lift is gated on Epic 4 readiness, **Then** this story depends on stories 4.5–4.9 being merged; a merge-gate check asserts these are merged before 4.28 merges. → **Reconciliation 3 — DEFERRED**: 4.5–4.9 are all `backlog`; record the gate in `deferred-work.md`, do not block the buildable AC1/AC2/AC5/AC6/AC8 work.
4. **Given** this is the last Epic 4 story (cross-cutting closure), **Then** Epic 4's close gate (mirroring 1.23 / 2.29 / 3.36) requires: 4.1–4.27 all merged + 4.28 lift applied + Operator-validator named (4.27 AC12) + documented walkthrough validated. → **DEFERRED** (Epic-4-wide, not satisfiable at story-create time).
5. **Given** ADR linkage, **Then** the new ADR is numbered continuing the on-disk sequence and cross-linked from `docs/adr/README.md`. → **Reconciliation 1 + 2**: number = **0033** (next free after 0032); **create** the README index (does not exist) and add the row.
6. **Given** the test suite, **Then** tests cover: the ArchUnit test class no longer contains the scope-protection rule for `RecoveryService` (verified by a meta-test asserting the rule's absence); the deeper recovery methods work end-to-end without triggering ArchUnit failures; the ADR file exists + contains all required sections per AC2. → **Reconciliation 5**: absence meta-test = **reflection** (buildable now). **The "deeper methods end-to-end" clause is DEFERRED** (needs 4.5–4.9). ADR-existence + required-sections check is buildable now (a simple doc-presence test or checklist assertion).
7. **Given** documentation, **Then** the recovery walkthrough (story 4.27) references this ADR in its "Background" section. → **Conditional**: 4.27 is `backlog`; if its doc does not yet exist, DEFER this edit and note it in `deferred-work.md` so 4.27 picks it up.
8. **Given** ArchUnit boundary scope, **Then** the lift is narrowly targeted at `RecoveryService` only — `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED`, `WorkflowTransitionService` mutation lock, and the artifact-operation monopoly rule are NOT lifted. → **Reconciliation 4 + 5**: keep the sibling rule + shared helpers; the meta-test regression-guards the sibling's presence.
9. **Given** Epic 4 close validation, **Then** this story's merge is the architectural acknowledgment that Epic 4's scope has landed; cross-epic references to "story 4.28 has occurred" (e.g. 4.22 AC12) are satisfied. → **DEFERRED** (merge-time semantics).

## Tasks / Subtasks

- [x] **Task 1 — Confirm the ADR number + the buildable scope (AC1/AC2/AC3/AC5)**
  - [x] Re-glob `docs/adr/*.md`; confirm the highest existing number (expected `0032`) and that `0033` is free on the current branch. If a sibling story already took `0033`, use the next free number and update every reference in this story consistently ([[flyway-v31-cross-branch-collision]] discipline for numbered artifacts).
  - [x] Re-read `sprint-status.yaml` for `4-5`…`4-9`. If any are still `backlog`/`in-progress` (not merged), confirm the DEFER scoping (Reconciliation 3) with the operator before proceeding.
- [x] **Task 2 — Remove the scope-protection rule (AC1)**
  - [x] Delete the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` constant — `ArchitectureRuleCatalog.java:784-797`.
  - [x] Delete the `@ArchTest recovery_service_is_scope_protected` field — `ArchitectureBoundaryTest.java:195-197`.
  - [x] Verify `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` and both shared helpers (`exposeOnlyPublicMethodSignatures`, `methodSignature`) remain intact and still compile (AC8).
  - [x] Rewrite the `RecoveryService` class Javadoc (`:46-56`) — replace the "Scope-protected by ArchUnit" paragraph with a note that the lock was lifted by story 4.28 / ADR 0033, and that scope is now governed by that ADR. Run `spotless:apply` after the hand edit ([[spotless-apply-before-pushing-java-edits]]).
- [x] **Task 3 — Author the ADR (AC2/AC5)**
  - [x] Create `docs/adr/0033-recovery-service-scope-lift.md` following the house format (`# ADR 0033 — Title`, `**Status:**`, `**Driver:**`, `## Context`, `## Decision`, `## Alternatives Considered`, `## Consequences`) — exemplars: `0032-replay-safe-aftercommit-helper.md`, `0030-governed-delivery-tail.md`. Include the five required subsections (a)–(e) from AC2.
  - [x] Create `docs/adr/README.md` as an index table (number | title | status) covering all existing ADRs + the new `0033` row (AC5). (Or, if the team prefers, cross-link from `architecture.md` — see Open Questions.)
- [x] **Task 4 — Absence + sibling-presence meta-test (AC6 clause 1 + AC8)**
  - [x] Add `RecoveryServiceScopeLiftMetaTest` in `deliveryline-backend/src/test/java/org/dradgo/architecture/`, `@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)`.
  - [x] Assert via reflection: no field `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` on `ArchitectureRuleCatalog`; no field `recovery_service_is_scope_protected` on `ArchitectureBoundaryTest`.
  - [x] Assert in the same test: `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` field IS still declared on `ArchitectureRuleCatalog` (AC8 regression guard). Give each assertion a failure message pointing at ADR 0033.
  - [x] Add a lightweight ADR-presence assertion (AC6 clause 3): the `0033` file exists and contains the (a)–(e) section markers. (A plain file-read test in the same class, or a checklist step — keep it simple.)
- [x] **Task 5 — Documentation cross-links (AC7) — CONDITIONAL**
  - [x] If the story-4.27 recovery walkthrough doc exists, add an ADR-0033 reference in its "Background" section. If 4.27 is unbuilt, record the pending edit in `deferred-work.md` for 4.27 to absorb.
- [x] **Task 6 — Record deferred AC portions (AC3/AC4/AC6-clause-2/AC9)**
  - [x] Append to `_bmad-output/implementation-artifacts/deferred-work.md`: the AC3 merge-gate, AC4 Epic-4 close gate, AC6 "deeper methods end-to-end" proof, and AC9 close-validation — all gated on stories 4.5–4.9 (and the full Epic-4 set) merging. Cross-reference this story.
- [x] **Task 7 — Verify green**
  - [x] Run the Failsafe arch slice (the tag that carries `ArchitectureBoundaryTest` + the new meta-test) — [[archunit-runs-in-failsafe-not-surefire]]: a new/removed `@ArchTest` is exercised by Failsafe, NOT `mvnw test`. Confirm both the arch slice and the new meta-test pass. Prefer verifying in a clean env ([[verify-ci-fixes-in-clean-env]]).
- [x] **Logging instrumentation** (cross-cutting standard) — **N/A for this story, with rationale.**
  - [x] This story touches **no runtime application-service, persistence, SPI, or I/O code** — it deletes an ArchUnit test rule, edits Javadoc/Markdown, and adds a reflection meta-test. There is **no SLF4J surface, no new branch, no service entry/exit, no DomainException raise site** to instrument. The project-wide logging contract (below) is therefore satisfied vacuously. **Do not manufacture log statements in test or ADR files to "comply."** If Task 2's Javadoc edit somehow expands into touching `RecoveryService` runtime code (it must not), re-apply the full logging task.

## Review Findings

Adversarial code review 2026-07-08 (bmad-code-review, Opus 4.8 [1m]) — 3 layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) over the story-scoped uncommitted diff (~432 lines). **No Critical or High.** Acceptance Auditor: all buildable ACs (AC1/AC2/AC5/AC6-clauses-1+3/AC8) **PASS**; the AC3/AC4/AC6-clause-2/AC7/AC9 deferrals are honestly recorded. Edge Case Hunter **verified against live code** that the highest-risk Blind Hunter concerns are false alarms (see Dismissed). Triage: 0 decision-needed, 3 patch, 2 defer, 5 dismissed.

- [x] [Review][Patch] Meta-test `(a)`–`(e)` section check uses an unanchored substring match — `body.contains("(a)")` … `"(e)"` can false-pass on incidental two-char substrings (e.g. the prose "process (d)/(e)"), so a gutted ADR whose real governance subsections were deleted would still pass. **FIXED:** anchored to the bold line-start markers (`**(a)`…`**(e)`); Failsafe meta-test 4/0 green against the real ADR. [deliveryline-backend/src/test/java/org/dradgo/architecture/RecoveryServiceScopeLiftMetaTest.java:105]
- [x] [Review][Patch] `RecoveryService` Javadoc "not yet present" list says `rerun(...)` while the new ADR 0033 (c) allow-list and `deferred-work.md` name the story-4.7 method `rerunFromStep(...)` — the class and its governing ADR contradicted each other. **FIXED:** aligned to `rerunFromStep(...)` + pointed at the ADR 0033 (c) allow-list; test-compile clean. [deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java:61]
- [x] [Review][Patch] ADR 0033 Consequences overclaimed the meta-test's durability — "a contributor re-adding the tripwire … fails the build" is only true for a literal same-name revert; a scope-protection rule re-added under a different constant name escapes the reflection guard. **FIXED:** softened to "re-adding the tripwire under its original name `RECOVERY_SERVICE_IS_SCOPE_PROTECTED`" and noted the rename case routes through (d)/(e). [docs/adr/0033-recovery-service-scope-lift.md:72]
- [x] [Review][Defer] `RecoveryServiceScopeLiftMetaTest.resolveAdr()` cwd-dependent upward walk — green in current Failsafe/CI layout, hypothetically false-fail/false-pass under a relocated or nested cwd. [deliveryline-backend/src/test/java/org/dradgo/architecture/RecoveryServiceScopeLiftMetaTest.java:119] — deferred, low-value robustness nit
- [x] [Review][Defer] Meta-test guards the ADR file but not the doc cross-links (README 0033 row, the two doc pointers) it relies on — silent link-rot possible. [deliveryline-backend/src/test/java/org/dradgo/architecture/RecoveryServiceScopeLiftMetaTest.java] — deferred, coverage gap arguably out of arch-meta-test scope

**Dismissed as noise (5):** (1) `ActorContext` unused-import risk from deleting the rule — Edge verified the import is still live via the takeover rule (`:446`/`:499`) and the shared helpers survive; (2) dangling reference / broken rule-count contract to the removed constant — Edge grep confirmed only comments/docs/string-literals remain, no live Java reference; (3) `RecoveryServiceScopeLiftMetaTest` uses the `*Test` (Surefire) name while claiming Failsafe placement — Edge verified the POM excludes `architecture/**/*Test` from Surefire and includes `<groups>architecture</groups>` in Failsafe, so it runs in the Failsafe arch slice as claimed; (4) ADR ships/stays `Proposed` with no merge mechanism to flip it — house convention (matches 0024/0026/0027/0029–0032); (5) closure story advanced to `review` with AC3/AC4 unmet — by-design deferral, operator-confirmed 2026-07-08, enforced by the new `4-29` backlog close-gate story.

## Dev Notes

### Live-code ground truth (verified this session)

- **`RecoveryService` public surface today = exactly `retry(String, String, ActorContext, String)` + `describeFailure(String)`.** No `resume`/`reconcile`/`rerunFromStep`/`pause`/`classifyFailure` exist yet — those are stories 4.5–4.9 (all `backlog`). This is *why* the tripwire currently passes, and why removing it now is safe. [Source: `RecoveryService.java:266` (`retry`), `:761` (`describeFailure`)]
- **The rule being deleted** (`ArchitectureRuleCatalog.java:784-797`):
  ```java
  static final ArchRule RECOVERY_SERVICE_IS_SCOPE_PROTECTED =
      namedRule(
          "RecoveryService must expose only the Epic-1 baseline public method signatures",
          "Remediation: Epic 4 will add resume/rerun/reconcile/pause/classifyFailure/takeover. ...",
          classes().that().haveFullyQualifiedName("org.dradgo.application.recovery.RecoveryService")
              .should(exposeOnlyPublicMethodSignatures(
                  methodSignature("retry", String.class, String.class, ActorContext.class, String.class),
                  methodSignature("describeFailure", String.class))));
  ```
- **Its registration** (`ArchitectureBoundaryTest.java:195-197`):
  ```java
  @ArchTest
  static final ArchRule recovery_service_is_scope_protected =
      ArchitectureRuleCatalog.RECOVERY_SERVICE_IS_SCOPE_PROTECTED;
  ```
- **Sibling to preserve** (AC8) — `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` (`ArchitectureRuleCatalog.java:803-816`) pins `DeveloperTakeoverService` to exactly `takeoverWorkflow`. It shares the `exposeOnlyPublicMethodSignatures` + `methodSignature` helpers → those helpers must survive the deletion.
- **`RecoveryService` Javadoc to rewrite** (`:46-56`) currently reads: *"Scope-protected by ArchUnit: exposes exactly two public methods… The rule `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` in `ArchitectureRuleCatalog` fails the build if any other public method name is added without an Epic-4 story justifying it. Methods not in story 1.18 (Epic 4 will add): resume(...), rerun(...), reconcile(...), pause(...), classifyFailure(...), takeover(...)."* Replace with the lifted-lock note + ADR 0033 pointer.

### ADR house format (match these exemplars)

`# ADR NNNN — <Title>` / `**Status:** Proposed (YYYY-MM-DD) — <confirm-on-merge note>` / `**Driver:** <why>` / `## Context` / `## Decision` (numbered decisions) / `## Alternatives Considered` (`### Alt N — … **Rejected.** …`) / `## Consequences` (bullets). See `docs/adr/0032-replay-safe-aftercommit-helper.md` and `docs/adr/0030-governed-delivery-tail.md`. Set Status to `Proposed (2026-07-05) — to be confirmed on merge of story 4-28` (matches 0032's convention). This ADR is unusual in that its "Decision" is retrospective governance, not a new mechanism — frame the Decision section as the four governance facts (a/b/c/d/e from AC2).

### Testing standards summary

- Arch rules + `@ArchTest` fields live in the **Failsafe** slice, not Surefire ([[archunit-runs-in-failsafe-not-surefire]]) — a removed `@ArchTest` won't be re-checked by `mvnw test`; verify via the Failsafe arch tag. The meta-test uses plain reflection + JUnit (`getDeclaredFields()`), so it runs anywhere, but tag it `@Tag(ArchitectureRuleCatalog.ARCHITECTURE_TAG)` for co-location and to keep it in the arch slice.
- `ArchitectureDiagnosticMetaTest` is the sibling meta-test file — study it for tag + package placement, but note its `.check(invalidFixture)` pattern proves *presence/firing* and is the WRONG shape here (Reconciliation 5); the absence guard must be reflection-based.
- Hand-edited Java must pass `spotless:apply` before commit ([[spotless-apply-before-pushing-java-edits]]); a hand-edited POM is not involved here.

### Project Structure Notes

- ArchUnit rule catalog: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (rule constants) + `ArchitectureBoundaryTest.java` (`@ArchTest` registrations). New meta-test goes in the same package.
- ADRs: `docs/adr/` (flat, numbered). No index today — this story adds `README.md` there.
- No module boundary, package move, or build-file change. No OpenAPI/Flyway/registry drift (this story adds **no** `DomainErrorCode`, `WorkflowEventType`, `AllowedAction`, migration, or DTO field) — so none of the registry/OpenAPI fan-out traps apply.

### Dependency & sequencing (the crux)

This story is **safe to implement and merge now** for its buildable slice (AC1/AC2/AC5/AC6-absence/AC8), but its **full closure semantics (AC3/AC4/AC6-e2e/AC7/AC9) are gated on the rest of Epic 4**. Removing the tripwire early does not weaken the codebase: the only thing the rule did was fail the build on an unauthorized *new* public method, and there are none. When 4.5–4.9 add their methods, they land against an already-lifted lock + a governing ADR — exactly the intended end state. The meta-test is the durable guard that the lock stays gone and the sibling stays protected.

### References

- [Source: `_bmad-output/planning-artifacts/epic-04-recovery.md` #Story-4.28 — AC1–AC9]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:784-797` — rule to delete; `:803-816` — sibling to keep; `:1155`,`:1190` — shared helpers]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java:195-197` — registration to delete; `:199-201` — sibling registration to keep]
- [Source: `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java:46-56` — Javadoc to rewrite; `:266`,`:761` — the two baseline public methods]
- [Source: `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureDiagnosticMetaTest.java` — meta-test package/tag exemplar (wrong pattern for absence — see Reconciliation 5)]
- [Source: `docs/adr/0032-replay-safe-aftercommit-helper.md`, `docs/adr/0030-governed-delivery-tail.md` — ADR house format; `docs/adr/` listing — highest number 0032, no README]
- [Source: `_bmad-output/implementation-artifacts/sprint-status.yaml:638-642` — 4-5…4-9 backlog (dependency gate)]
- [Related memory: [[flyway-v31-cross-branch-collision]] (numbered-artifact collision discipline), [[archunit-runs-in-failsafe-not-surefire]], [[spotless-apply-before-pushing-java-edits]], [[transition-table-change-fans-to-contracts]] (does NOT apply — no transition change), [[verify-ci-fixes-in-clean-env]]]

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. **For this story the standard is satisfied vacuously** — no runtime service/persistence/I/O/transition code is touched (only an ArchUnit test rule, Javadoc, ADR markdown, and a reflection meta-test). The full contract below is retained for reference and applies the moment any runtime code is touched.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):** public application-service methods (INFO entry/success, WARN typed-rejection, ERROR unexpected); persistence writes; file/network I/O; state-machine transitions; reconciliation/recovery loops.
- **Required context keys** (MDC or structured params): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus entity public ids.
- **Forbidden in output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields.
- **Test contract:** new logging surfaces pinned by a focused test (list-appender or `OutputCaptureExtension`).

## Open Questions (surface to operator before finalizing)

1. **Scope confirmation (Reconciliation 3):** OK to implement 4.28 now as the buildable slice (remove rule + ADR 0033 + README index + absence/sibling meta-test) and DEFER the merge-gate / end-to-end proof / close-gate to when stories 4.5–4.9 have merged? (Recommended — the lift is harmless while the surface is still the baseline two methods.)
2. **ADR cross-link home (Reconciliation 2 / AC5):** Create a new `docs/adr/README.md` index (recommended — none exists), or cross-link the ADR from `architecture.md` instead?
3. **ADR number (Reconciliation 1):** Confirm `0033` at authoring time (highest on disk is `0032`; if story 4.7 or another sibling lands an ADR first, take the next free number and update all references in this story consistently).

## Dev Agent Record

### Agent Model Used

Opus 4.8 [1m] (claude-opus-4-8) — bmad-dev-story, 2026-07-08.

### Debug Log References

- Format + test-compile: `./mvnw -pl deliveryline-backend spotless:apply test-compile -Djacoco.skip=true` → clean (spotless reflowed one lambda in the new meta-test).
- Failsafe architecture slice: `./mvnw -pl deliveryline-backend integration-test -Dit.test='org.dradgo.architecture.*Test' -Dfailsafe.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true -Dspotbugs.skip=true -Djacoco.skip=true` → **BUILD SUCCESS**. Failsafe arch slice **82 tests / 0 failures**, incl. `RecoveryServiceScopeLiftMetaTest` **4/0**, `ArchitectureBoundaryTest` **60/0** (green with the recovery rule removed), `ArchitectureDiagnosticMetaTest` **5/0**. Full backend Surefire suite **1661 / 0 failures** (no regression from the `RecoveryService` Javadoc / catalog / boundary edits).
- NOTE: `verify` (full) aborts at the `jacoco:check` coverage gate when run as an isolated subset (0.62 vs 0.75 line ratio) — an artifact of running one slice, NOT a test failure. Also skipped `checkstyle`/`spotbugs`: HEAD carries a PRE-EXISTING checkstyle drift at `WorkflowCommandService.java:1158` unrelated to 4.28 (see sprint-status 4-17 residual). Recommend a clean-env full `mvnw verify` before merge ([[verify-ci-fixes-in-clean-env]]).

### Completion Notes List

Implemented the **buildable slice** of the Epic-4 closure story (scope confirmed with operator 2026-07-08). Dependency-gated ACs are deferred to `deferred-work.md`.

**GROUND-TRUTH DRIFT from the 2026-07-05 story snapshot (handled):** story 4-5 is now `done` — it had already added a public `resume(...)` method and **widened** `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` to permit it. So (a) the live surface was 3 methods (`retry`/`resume`/`describeFailure`), not the 2 the story assumed, and (b) the rule + registration + Javadoc had moved off the story's cited line numbers (rule `ArchitectureRuleCatalog.java:809-826` not `:784-797`; registration `ArchitectureBoundaryTest.java:195-197`; Javadoc already rewritten once by 4.5 to "three methods"). Verified all against live code before editing.

- ✅ **AC1** — Deleted the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` rule constant (`ArchitectureRuleCatalog`) and its `@ArchTest recovery_service_is_scope_protected` registration (`ArchitectureBoundaryTest`); left explanatory tombstone comments pointing at ADR 0033. Arch slice green after removal.
- ✅ **AC8** — Kept `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` + both shared helpers (`exposeOnlyPublicMethodSignatures`, `methodSignature` — still used by the takeover rule at the new `:842`). Confirmed `ActorContext` import stays live (used by 2 other rules at `:446`/`:499`), so no unused-import breakage. Rewrote the `RecoveryService` class Javadoc from "scope-protected by ArchUnit" → "scope governed by ADR 0033".
- ✅ **AC2** — Authored `docs/adr/0033-recovery-service-scope-lift.md` in the house format (Status/Driver/Context/Decision/Alternatives/Consequences) with the five required governance subsections (a)–(e). Confirmed `0033` free (highest on disk was `0032`).
- ✅ **AC5** — Created `docs/adr/README.md` index (did not exist) — one row per existing ADR 0001–0032 + the new 0033, with a note on the non-contiguous numbering gaps.
- ✅ **AC6 (clause 1 + clause 3)** — Added `RecoveryServiceScopeLiftMetaTest` (reflection, `@Tag(ARCHITECTURE_TAG)`): asserts the rule field is absent on the catalog, the registration field is absent on the boundary test, the sibling `DEVELOPER_TAKEOVER_*` field is still present (AC8 regression guard), and ADR 0033 exists with its (a)–(e) markers + house sections. Each assertion message points at ADR 0033.
- ➕ **Doc hygiene (in-scope with AC1)** — `docs/failure-recovery-walkthrough.md` and `docs/cli/workflow-commands.md` both asserted the now-deleted rule protects `RecoveryService`; corrected both to note the lift + link ADR 0033 (leaving them would be a stale claim about a rule I just deleted). Neither is the story-4.27 "Background" walkthrough (that increment is `backlog`).
- ⏸️ **DEFERRED to `deferred-work.md`** (dependency-gated on 4.6–4.9, which are `backlog`; 4.5 is `done`): AC3 merge-gate, AC4 Epic-4 close gate, AC6 clause 2 (all-five-methods end-to-end ArchUnit proof — only `resume` exists today), AC7 (4.27 walkthrough Background cross-link — doc not authored yet), AC9 (merge-time acknowledgment).

### File List

Modified:
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` — deleted `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` rule constant (AC1); tombstone comment + updated sibling comment (AC8).
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` — deleted `recovery_service_is_scope_protected` `@ArchTest` registration (AC1); tombstone comment.
- `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecoveryService.java` — rewrote class Javadoc: scope now governed by ADR 0033, not the ArchUnit tripwire.
- `docs/failure-recovery-walkthrough.md` — corrected stale "scope-protected by ArchUnit rule" claim → lifted-by-4.28 + ADR 0033 link.
- `docs/cli/workflow-commands.md` — same stale-claim correction + ADR 0033 link.
- `_bmad-output/implementation-artifacts/deferred-work.md` — new "dev-story of story-4-28" section (AC3/AC4/AC6-clause-2/AC7/AC9 deferrals).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — 4-28 `ready-for-dev` → `in-progress` → `review` + last_updated log.

Added:
- `docs/adr/0033-recovery-service-scope-lift.md` — the governing ADR (AC2).
- `docs/adr/README.md` — ADR index (AC5).
- `deliveryline-backend/src/test/java/org/dradgo/architecture/RecoveryServiceScopeLiftMetaTest.java` — reflection absence/sibling/ADR-presence meta-test (AC6 clause 1+3, AC8).

## Change Log

| Date | Change |
|---|---|
| 2026-07-08 | Buildable slice implemented via bmad-dev-story (Opus 4.8 [1m]): removed the `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` ArchUnit rule + registration (AC1), rewrote `RecoveryService` Javadoc, authored ADR 0033 + `docs/adr/README.md` index (AC2/AC5), added `RecoveryServiceScopeLiftMetaTest` absence/sibling guard (AC6/AC8), corrected two stale doc references. AC3/AC4/AC6-clause-2/AC7/AC9 deferred to `deferred-work.md` (gated on 4.6–4.9). Failsafe arch slice + full Surefire suite green. Status → review. |
