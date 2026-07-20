# ADR 0033 — RecoveryService Scope Lift (Retire the Epic-1 Scope-Protection Tripwire)

**Status:** Accepted (proposed 2026-07-08; confirmed 2026-07-19 on the merge/close of story 4-28, verified by story 4-29's Epic-4 closure gate)
**Driver:** Epic 4 (Failure Handling, Recovery & Reconciliation) fulfils the deeper-recovery scope that story 1.18 deliberately deferred. Story 1.18 AC11 installed an ArchUnit tripwire (`RECOVERY_SERVICE_IS_SCOPE_PROTECTED`) that failed the build the moment `RecoveryService` grew any public method beyond its Epic-1 baseline. That tripwire has done its job — it kept the deeper surface out until an epic justified it — and now stands in the way of the very stories (4.5–4.9) it was reserving room for. Story 4.28 (the cross-cutting Epic-4 closure story, mirroring the 1.23 / 2.29 / 3.36 close gates) lifts the tripwire and replaces it with this governing ADR.

## Context

Story 1.18 shipped the CLI minimum-viable recovery baseline: `RecoveryService` exposed exactly two public methods, `retry(String, String, ActorContext, String)` and `describeFailure(String)`. To prevent a future contributor from stealth-adding the much larger recovery surface that the roadmap reserved for Epic 4 (resume, reconcile, rerun-from-step, pause, failure classification), story 1.18 AC11 added an ArchUnit rule to `ArchitectureRuleCatalog`:

```java
static final ArchRule RECOVERY_SERVICE_IS_SCOPE_PROTECTED =
    namedRule(
        "RecoveryService must expose only the Epic-1 baseline public method signatures",
        "…",
        classes().that().haveFullyQualifiedName("org.dradgo.application.recovery.RecoveryService")
            .should(exposeOnlyPublicMethodSignatures(
                methodSignature("retry", String.class, String.class, ActorContext.class, String.class),
                methodSignature("describeFailure", String.class))));
```

registered as an `@ArchTest` in `ArchitectureBoundaryTest` and exercised in the Failsafe architecture slice. The rule is a **scope-creep tripwire**: it does not protect a runtime invariant — it fails the build if the *public surface* of one class grows without an accompanying scope decision.

Epic 4 is that scope decision. Story 4.5 (RecoveryService.resume) was the first to need the surface to grow; rather than lift the whole rule mid-epic, 4.5 **widened** the tripwire in lockstep (adding the `resume` signature to the allowed set) and left the wholesale lift to this closure story. As of story 4.28, the recovery surface Epic 4 set out to deliver is either landed or actively in flight, so a per-method widening dance no longer earns its keep — the governance belongs in a durable ADR, not in a build rule that every new recovery story must remember to edit.

At the time of this lift the live surface is `retry`, `resume` (4.5, `done`), and `describeFailure`; the remaining Epic-4 recovery methods (`reconcile`, `rerunFromStep`, `pause`, `classifyFailure`) are authored as stories 4.6–4.9 and land against the already-lifted lock plus this ADR — the intended end state.

## Decision

The `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` ArchUnit rule and its `@ArchTest` registration are **removed**. `RecoveryService`'s public recovery surface is henceforth governed by the following five facts, which any change to that surface must honour. A reflection meta-test (`RecoveryServiceScopeLiftMetaTest`) guards that the rule stays removed and that the sibling `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` lock stays in place.

**(a) What was scope-protected.** The Epic-1 `RecoveryService` public surface — originally exactly `retry` + `describeFailure` (later `retry` + `resume` + `describeFailure` after story 4.5's lockstep widening). The rule existed for **pre-Epic-4 scope-creep prevention**: it made adding any deeper recovery method a build-breaking event, forcing an explicit scope conversation before the surface could grow. It never guarded a runtime behaviour — only the shape of the class's public API.

**(b) What changed.** Epic 4 delivered the deeper-recovery scope the tripwire was holding space for. Story 4.5 added `resume`; stories 4.6–4.9 add `reconcile`, `rerunFromStep`, `pause`, and `classifyFailure`. With that scope now authorized epic-wide, the per-method tripwire is redundant: it can only fire on the exact methods Epic 4 already sanctions, so keeping it means every recovery story must edit the rule in lockstep for no additional safety. Story 4.28 lifts it and moves the governance here.

**(c) What new scope is now allowed.** The following recovery methods on `RecoveryService`, and their REST endpoints (stories 4.10–4.14), are the **exhaustive** Epic-4 allow-list:

| Method | Story | REST surface |
|---|---|---|
| `resume(...)` | 4.5 (`done`) | 4.10 |
| `reconcile(...)` | 4.6 | 4.11 |
| `rerunFromStep(...)` | 4.7 | 4.12 |
| `pause(...)` | 4.8 | 4.13 |
| `classifyFailure(...)` | 4.9 | 4.14 |

plus the retained Epic-1 baseline `retry(...)` and `describeFailure(...)`. Note: `takeover(...)` is **not** on `RecoveryService` — it lives on the sibling `DeveloperTakeoverService`, which remains ArchUnit scope-protected (`DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED`) and is out of scope for this lift (story 4.28 AC8).

**(d) What is still NOT allowed.** Any recovery method **beyond** the (c) allow-list. Lifting the tripwire is not an invitation to grow `RecoveryService` freely — it delegates the guard from a build rule to this ADR. Adding a public recovery method that is not listed in (c) without first updating this ADR is a governance violation, even though ArchUnit no longer fails the build for it. The sibling `DeveloperTakeoverService` mutation lock and the `WorkflowTransitionService`-mutation / artifact-operation-monopoly boundaries are likewise **not** lifted.

**(e) How to add a new recovery method in a future version.** The lift trades an automatic tripwire for a documented process. To add a recovery method beyond the (c) allow-list:

1. **Write an ADR** (or amend this one) recording why the new method is needed and what it does.
2. **Add the method** to `RecoveryService` with unit + integration tests.
3. **Add its REST endpoint** (and CLI surface, if applicable).
4. **Add its UI / operator-console affordance** where user-facing.
5. **Update this ADR's (c) "what new scope is now allowed" allow-list** so the governance record stays exhaustive and future contributors can see the full sanctioned surface in one place.

## Alternatives Considered

### Alt 1 — Keep the tripwire and widen it per method (the 4.5 pattern) for the rest of Epic 4
**Rejected.** Story 4.5 widened the rule in lockstep as a deliberately conservative mid-epic move. Repeating that for 4.6–4.9 buys no real safety — the rule can only ever fire on methods Epic 4 already sanctions — while adding a mandatory edit-the-rule step to every recovery story and a merge-conflict surface on one shared catalog constant. Once the epic-wide scope decision exists, a durable ADR is the right home for the governance, not a build rule.

### Alt 2 — Delete the rule and record nothing (no ADR)
**Rejected.** The tripwire encoded a real intent (recovery scope is governed, not free-for-all). Removing it silently would lose that intent and invite exactly the uncontrolled growth 1.18 guarded against. The ADR preserves the governance in a form that survives the rule's deletion, and the meta-test points a future contributor at it.

### Alt 3 — Lift the sibling `DEVELOPER_TAKEOVER_SERVICE_IS_SCOPE_PROTECTED` at the same time
**Rejected / out of scope.** The takeover lock (story 3.22) pins a different service to a single method and no Epic-4 story justifies growing it. Story 4.28 AC8 explicitly narrows the lift to `RecoveryService` only; the reflection meta-test regression-guards the sibling's continued presence.

## Consequences

- The Epic-4 recovery stories (4.5–4.9) land their methods against an already-lifted lock and this governing ADR — no per-story rule edit, no lockstep merge conflicts on `ArchitectureRuleCatalog`.
- `RecoveryService`'s allowed surface is now discoverable in one place (this ADR's (c) table) rather than inferred from an ArchUnit rule constant.
- Recovery-surface governance shifts from an **automatic** build gate to a **documented** process (d)/(e). This is a deliberate trade: less mechanical enforcement, clearer intent. `RecoveryServiceScopeLiftMetaTest` keeps the lift durable — a well-meaning contributor who reverts this change by re-adding the tripwire under its original name `RECOVERY_SERVICE_IS_SCOPE_PROTECTED` (or who deletes the sibling lock) fails the build with a message pointing back here. The reflection guard keys on that constant's name, so it catches an accidental revert rather than a deliberate re-introduction of surface-pinning under a new name; the latter is a governance decision that must go through (d)/(e) above and would surface in review.
- The buildable slice of story 4.28 (this ADR + the rule removal + the meta-test) is safe to merge before 4.6–4.9: while the surface is still the sanctioned methods, removing the tripwire changes nothing observable. The merge-gate, Epic-4 close gate, and end-to-end proof that all five deeper methods pass ArchUnit are tracked in `deferred-work.md` against 4.6–4.9 merging.
- Story 4.27's recovery walkthrough will reference this ADR in its "Background" section once that documentation increment lands (currently `backlog`); the pending cross-link is recorded in `deferred-work.md`.
