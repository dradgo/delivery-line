# Epic 3e Retrospective - Clarification Loop Activation

**Date:** 2026-06-26
**Facilitator:** Amelia (Developer)
**Project Lead:** Alex
**Epic Status:** Complete-as-scoped (5/5 stories `done`)
**Retrospective Type:** Sixth retrospective (carries Epic 3d commitments into Epic 3f)

---

## 1. Epic Summary

| Dimension | Result |
|---|---|
| Scope | Activated the missing clarification front half, closed the accept -> regenerate -> incorporate loop, extended advisory review to the spec gate, added per-step runner mapping, and exposed spec-stage runner observability in `Investigating`. |
| Stories shipped | 5/5 done: 3e-1 through 3e-5. |
| Critical defects shipped | 0 known open high or medium findings at epic close. |
| Contract posture | Additive runner-result and context-bundle fields stayed v1-compatible; OpenAPI churn was limited to real endpoint/client additions or documentation correction. |
| Review intensity | Heavy and useful: 3e-1 caught a Hibernate session-poisoning path; 3e-2 caught spec-graft replay non-idempotency; 3e-3 caught wrong-artifact fallback misattribution; 3e-4 and 3e-5 closed lower-risk test and documentation gaps. |
| Standing carried debt | Several low/medium hardening items remain tracked in `deferred-work.md`; none block Epic 3f start. |

**Goal achieved:** Epic 3e turned the clarification loop from dormant infrastructure into a governed product loop. A spec runner can now emit structured questions; those become first-class clarifications; a reviewer can answer, accept, and regenerate the spec; the runner reports structured acknowledgements; the lifecycle marks clarifications incorporated or superseded. The epic also extended reuse-heavy Epic 3d substrates: the advisory reviewer now works at the spec gate, per-step runner selection resolves the 3d-3 granularity deferral, and the live runner log/console surfaces are available during spec generation.

**Headline:** The epic succeeded because it treated "missing front half" as a systems gap, not a UI gap. The work completed the data contract, runner contract, broker ingest, lifecycle transition, review context, and visible surface together.

---

## 2. Team Participants

- **Amelia (Developer)** - facilitator
- **Alice (Product Owner)** - product-loop and human decision boundary
- **Charlie (Senior Dev)** - runner/broker/idempotency seams
- **Dana (QA Engineer)** - contract, replay, redaction, and regression gates
- **Winston (Architect)** - Epic 3d reuse and Epic 3f readiness
- **Alex (Project Lead)** - convener

---

## 3. Successes & Strengths

### 3.1 Clarification loop is now real

3e-1 closed the original silent gap: `ClarificationWritePort.insertOpen(...)` had no production caller and runner results had no structured question channel. The new `questions[]` channel plus `ClarificationIngestService` made questions visible and answerable without inventing a parallel create endpoint.

3e-2 completed the lifecycle: accept clarification, regenerate spec with accepted answers, persist structured acknowledgements, graft the rebuilt spec into the existing lineage, and sweep clarifications to `incorporated` or `superseded`.

### 3.2 Review caught real state-corruption and replay bugs

The most important catches were not cosmetic:

- 3e-1: catching an `IDEMPOTENCY_KEY_CONFLICT` after a flushed insert did not heal the Hibernate session and could strand a completed run. The fix moved safety before the flush with dedup and a pre-flight idempotency-key probe.
- 3e-2: recomputing spec-graft `operationType` from mutable lineage state made re-harvest non-idempotent. The fix reused the already recorded operation type.
- 3e-3: a lost review pin fallback could re-derive the wrong artifact after the run advanced, causing silent execution-review misattribution. The fix split spec-inclusive and execution-only reviewed-artifact resolution.

These were exactly the kinds of bugs the governed workflow cannot tolerate: replay, attribution, and state progression must be boringly correct.

### 3.3 Reuse from Epic 3d paid off

Epic 3e reused Epic 3d rather than rebuilding it:

- 3e-3 used the same reviewer execution, `step_reviews`, verdict endpoint, and panel path from 3d-2.
- 3e-4 extended the project runner-kind model created by 3d-3 instead of bypassing it.
- 3e-5 widened the existing log and diagnostic-console action gates for `Investigating`; no new streaming subsystem was created.

That is a strong architectural signal. The prior epic's seams were not merely sufficient for their own stories; they were reusable under adjacent product pressure.

### 3.4 Scope discipline held

The epic avoided several tempting expansions:

- no schema-version bump for additive runner/context fields;
- no new endpoint for clarification creation;
- no auto-approval from advisory review;
- no provider-usage expansion in `Investigating`;
- no new observability transport for spec-stage logs;
- no separate runner dispatch path for regenerate-spec.

The result is a bigger product surface without a bigger conceptual model.

---

## 4. Challenges & Growth Areas

### 4.1 Replay and idempotency remain the sharpest edge

Multiple stories had serious replay/idempotency findings. The system handled them, but the pattern is clear: whenever runner harvest, artifact operation, or lifecycle updates mix persisted side effects, "catch and continue" is not enough if the persistence session has already observed a failed flush.

This strengthens the Epic 3d B1 lesson: idempotency needs shared patterns and proof points, not local optimism.

### 4.2 Contract additions need cardinality and sanitization follow-through

Several deferred items cluster around unbounded or insufficiently normalized runner-supplied clarification data:

- `questions[]` and clarification text lack stronger item/text bounds beyond the existing payload cap.
- open clarification text can flow into runner prompts, creating a prompt-injection surface.
- redaction-to-empty can collide with schema `minLength: 1`.
- acknowledgement fence dedup semantics still need an explicit first-wins, last-wins, or reject policy.

These are not blockers for the current local-first pilot, but they are the next hardening layer for user-supplied or agent-supplied text.

### 4.3 Generated and local dependency state can go stale

3e-2 hit a stale local `.m2` runner-contracts schema jar, producing misleading failures until runner-contracts were reinstalled. This is the same family as earlier OpenAPI/client drift and Flyway-head drift: generated or installed contract artifacts need an explicit refresh step when schemas change.

### 4.4 Admin configuration still lacks concurrency and registry drift safeguards

3e-4 intentionally shipped full-replace `project_runner_kinds` updates without optimistic locking, and the frontend runner-kind options remain hard-coded without a registry-drift gate. This is acceptable for the admin surface now, but it is a known weak point if project configuration becomes multi-operator.

---

## 5. Previous Retrospective Follow-Through (Epic 3d -> Epic 3e)

| 3d commitment | Status | Evidence |
|---|---|---|
| P1: Verify 3d seams are stage-agnostic before 3e-3/3e-5 | Completed | 3e-3 reused reviewer storage/endpoint/panel; 3e-5 confirmed streaming endpoints were already stage-agnostic and only matrix gating was missing. |
| P2: Use additive-optional no-`schemaVersion` pattern for runner-contract fields | Completed | 3e-1 `questions[]`, 3e-2 `clarificationAcknowledgements[]`, and 3e-3 context-bundle additions stayed additive. |
| P3: Keep mock-vs-real runner bar | Completed | Runner-contract tests, both runner entrypoints, and offline mocks were updated; 3e-3 ITs caught mock scenario pitfalls. |
| B1: Treat repeated idempotency issues as a named decision | Still open, strengthened | 3e-1 and 3e-2 produced more evidence that replay/idempotency needs a shared approach. Carry into Epic 4 recovery planning. |
| TD3/P3: Add project attribution to run-read DTOs | Scheduled and started in 3f | 3f-6 is created and ready for dev; it explicitly completes deferred 3c-9 AC6. |
| A3: Stamp real Flyway head at story creation | Partially improved | 3f-1 story creation now names the live Flyway head as V26. Keep enforcing this in create-story. |

**Continuity signal:** The strongest Epic 3d prep actions were applied. The remaining misses are the same category as before: recurring hardening and housekeeping need explicit gates, not memory.

---

## 6. Significant Discovery - Epic 3f Plan Impact

**No Epic 3f update required.** Epic 3f has already started and remains coherent after Epic 3e.

Epic 3e actually de-risks 3f:

- 3f-4 can reuse the reviewer-style structured-channel pattern proven by 3e-1 and 3e-3.
- 3f-4's re-propose feedback loop can reuse the accepted pattern of redaction-policed context-bundle input rather than inventing another prompt channel.
- 3f-6 directly closes the project-attribution gap that Epic 3d carried and Epic 3e did not need to solve.
- 3f's split proposal and split commit paths should adopt the 3e replay lesson: record or pre-flight immutable operation choices before mutable state can change replay behavior.

**Risk to carry into 3f:** 3f introduces more cross-run identity, parent/child lineage, dependency release, and idempotent fan-out. That is a larger version of the same class of bug 3e reviews caught. The design must pin identity and operation choices early, then replay from recorded truth.

---

## 7. Readiness Assessment - Epic 3e

| Dimension | Status | Notes |
|---|---|---|
| Stories complete | Complete | 5/5 stories are `done`; retrospective was the only remaining deferred key. |
| Testing and quality | Strong | Story records show focused backend, frontend, runner, contract, OpenAPI, and Testcontainers verification on the risky paths. |
| Open high/medium findings | None known | Story reviews closed the high/medium issues before done. |
| Deployment / stakeholder | Local-first, no external gate | No separate external stakeholder acceptance gate is recorded. |
| Technical health | Good with named hardening | Replay/idempotency, unbounded clarification text, prompt sanitization, and admin-concurrency items are carried as explicit debt. |
| Next epic readiness | Ready with guardrails | 3f is already in progress; 3f-1 and 3f-6 are ready for dev. |

**Verdict:** Epic 3e is complete-as-scoped. It is safe to proceed with Epic 3f, provided the split/dependency stories explicitly apply the replay and identity lessons from 3e.

---

## 8. Action Items

### Process / Quality

| ID | Action | Owner | Mechanism |
|---|---|---|---|
| A1 | For any runner-result or context-bundle additive field, include bounds/sanitization analysis in story creation, even when the schema field remains additive. | Dana | Add to create-story checklist for runner/context stories. |
| A2 | Treat any caught persistence exception after `saveAndFlush` as session-poisoning unless proven otherwise. | Charlie | Require pre-flight/read-recorded-operation design in review checklist for replay-sensitive paths. |
| A3 | Refresh generated/installed contract artifacts after schema changes before interpreting failures. | Team | Story validation notes must name OpenAPI, `schema.d.ts`, runner-contracts install, or equivalent refresh steps. |

### Technical Debt

| ID | Item | Disposition |
|---|---|---|
| B1 | Shared idempotent-complete / replay-safe side-effect pattern | Carry into Epic 4 recovery planning as a named decision. |
| TD1 | Bound and normalize clarification text and clarification arrays | Hardening follow-up; align runner-contract schema, DB constraints, and redaction behavior. |
| TD2 | Clarification text prompt-injection posture | Hardening follow-up before broader or less trusted inputs. |
| TD3 | Acknowledgement fence duplicate/conflict semantics | Decide first-wins, last-wins, or reject; update both runners byte-identically. |
| TD4 | Project config full-replace concurrency and frontend registry drift | Carry with project-admin hardening; not blocking 3f foundation stories. |

### Epic 3f Preparation

| ID | Action | Owner |
|---|---|---|
| P1 | In 3f-1, verify exact Linear GraphQL `issueCreate` and parent/sub-issue fields before coding the adapter. | Charlie |
| P2 | In 3f-4/3f-5, record immutable proposal/operation identity before fan-out so replay never recomputes from mutable parent state. | Winston |
| P3 | In 3f-6, close the long-carried `projectId` DTO gap with OpenAPI/client drift checks and queue filter accessibility coverage. | Dana |

### Team Agreements

- Replay safety must be designed before implementation, not patched after review finds a bad re-harvest.
- Additive contract changes are still product changes; they need bounds, redaction, and generated-artifact refresh discipline.
- Reuse remains the default: extend existing reviewer, runner, artifact, and action-gating seams unless a story proves they are insufficient.

---

## 9. Key Takeaways

1. **The clarification loop is now activated end-to-end.** Questions become records, answers become accepted inputs, regenerated specs acknowledge them structurally, and lifecycle state becomes visible.
2. **Replay/idempotency is the epic's main lesson.** The most serious bugs involved recomputation or failed flushes under replay pressure.
3. **Epic 3d seams were real.** Reviewer, runner mapping, and observability surfaces were extended without rebuilding their substrates.
4. **Contract discipline held, but hardening remains.** Additive fields avoided version churn; bounds, prompt posture, and duplicate semantics are the next layer.
5. **Epic 3f should start with identity and replay paranoia.** Split fan-out and dependencies will magnify any weak identity or replay decision.

---

## 10. Next Steps

1. Review this retrospective.
2. Mark `epic-3e-retrospective` as done in sprint status.
3. Continue Epic 3f with 3f-1 and 3f-6, applying the replay/identity guardrails above.
4. Keep B1 visible for Epic 4 recovery planning.

---

Amelia (Developer): "Epic 3e is reviewed. The clarification loop is no longer dormant, and the next epic has a clear warning label: split fan-out must be replay-safe from the first design pass."
