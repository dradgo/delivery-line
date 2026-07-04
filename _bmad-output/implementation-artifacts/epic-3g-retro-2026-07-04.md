# Epic 3g Retrospective — Run Provenance & Token Accounting

**Date:** 2026-07-04
**Facilitator:** Amelia (Developer)
**Project Lead:** Alex
**Epic Status:** Complete (4/4 stories `done`)
**Retrospective Type:** Eighth retrospective (carries Epic 3f commitments; warm-up epic of the 3g–3l family; hands off to Epic 3h — the heaviest gate epic since 3f)

---

## 1. Epic Summary

| Dimension | Result |
|---|---|
| Scope | Two additive read-model tracks: (a) **run provenance** — snapshot the originating ticket title + a link back to the source ticket and surface them in the queue + a detail Origin block; (b) **token accounting** — capture per-execution input/output/total tokens and roll them up to a run total. |
| Stories shipped | **4/4 done** — 3g-1 (provenance backend), 3g-2 (provenance FE), 3g-3 (token backend), 3g-4 (token FE). |
| New persistence | V31 three nullable token columns on `runner_executions`; one additive JSON key (`url`) on `integration_links.external_metadata` (no DDL); additive optional `usage{input,output,total}` block on the runner-result contract. |
| New states / actions / events / error-codes | **None** — the deliberately lightest foundation-gate footprint of the whole 3g–3l family. |
| Contract posture | Additive-only; `schemaVersion const:1` unchanged; OpenAPI/`schema.d.ts` regen at two points (3g-1 summary/linked-ticket fields; 3g-4 `WorkflowDetail.totalTokens` + `/steps`). |
| Review intensity | All 4 stories through bmad-code-review's 3 adversarial layers. Patches: 3g-1 (5, incl. a resolved decision), 3g-2 (3), 3g-3 (5, incl. int32-cap decision), 3g-4 (3 + 1 deferred a11y-contrast). Zero confirmed AC violations across the epic. |
| Critical defects | **0 at review time — but 2 found post-close** (see §4): token capture produced no real data (mock-only), and a terminal-transition clobber nulled the token side-write. |

**Goal (partially achieved):** Epic 3g delivered **FR73** (run origin/title visibility) fully and correctly. It delivered **FR74** (per-step token accounting + rollup) *as a pipeline* — but the pipeline carried only mock data at epic close; the actual user value (see *what a real run cost*) was not realized until post-epic fixes now in flight.

**Headline (Alex's words):** *"Title went well. Token usage went bad — it didn't work initially, only mocks were used to collect usage; actual codex usage wasn't applied at all."* The record confirms this precisely.

---

## 2. Team Participants

- **Amelia (Developer)** — facilitator
- **Alice (Product Owner)** — FR73/FR74 value framing, scope discipline
- **Charlie (Senior Dev)** — runner/broker, contract, transaction seams
- **Dana (QA Engineer)** — contract, drift gates, real-vs-mock verification bar
- **Winston (Architect)** — read-model conventions, B1/replay pattern, 3h readiness
- **Alex (Project Lead)** — convener, decision-maker

---

## 3. Successes & Strengths

### 3.1 Provenance is the clean win — and it works because the data was real
3g-1/3g-2 shipped the ticket title as a human queue label (with a `ticketRef` fallback — never a blank cell) and a self-hiding detail Origin block (title + ref + integrationType + a `url`-gated external link-out). Critically, the origin **works in production** — because 3g-1 **snapshotted the title at run creation**, immutable, never live-resolved (prep item P3 from the 3f retro). The data existed the instant the run was created, so the display had truth to render. This is the contrast that explains the whole epic.

### 3.2 The lightest foundation-gate footprint, as designed
No new `WorkflowState`, `AllowedAction`, `WorkflowEventType`, or `DomainErrorCode` across all four stories. The only drift-tested additions were the V31 token columns and the additive `usage` contract field. After 3f's state-machine, lineage, and afterCommit weight, 3g was a genuine breather — by design.

### 3.3 Convention-setting held for the family
3g pinned the additive-DTO conventions the heavier 3h–3l epics reuse: nullable read-model widening, the summary exact-field contract-test update (prep P1 — no silent CI-only break), the additive runner-contract field, the snapshot ctor-shim fan-out (27-arg canonical + new 24-arg shim, ~36 call sites stayed green), and the `null`→"not reported" (never `0`) token posture mirrored from 3d-7.

### 3.4 Review caught real defects
- 3g-1: workspace-agnostic Linear deep-link wouldn't resolve (added an optional `workspaceSlug`); metadata JSON parsed twice per run (parse-once).
- 3g-2: empty/whitespace `ticketTitle` rendered a **blank** prominent queue label because two sibling seams guarded a `string | null` differently (`?? undefined` vs. trim-check) — a genuine AC1 violation caught before merge.
- 3g-4: the run-level total vanished whenever the separate `/steps` read errored, though it comes from the independent detail query; nondeterministic sort on equal `createdAt` (added `.thenComparing(publicId)`).

---

## 4. Challenges & Growth Areas — the token failure, two layers deep

Alex's honest signal maps onto a **two-layer failure**, and both layers matter.

### 4.1 Layer one — the scope trap: ACs tested the pipe, not the water
3g-3's `buildUsage()` read **only** `DELIVERYLINE_USAGE_MOCK_FILE`. This was not an accident — the story *explicitly* deferred real-CLI extraction to a "documented forward option" and marked itself done. Every AC passed: contract round-trip (present/absent/malformed), columns persist, snapshot carries the fields end-to-end, null parity, **mock determinism**. **Not one AC exercised a real codex run producing a real token count.** The acceptance criteria validated the plumbing; nothing validated that real water flowed through it. Result: in production, every step showed "Not reported" — 0 of 76 rows.

### 4.2 The bitter irony — prep item P2 was executed flawlessly, and *that* masked the hole
3f's retro committed P2: *"mirror the additive `usage` block into both `runner.mjs` entrypoints + both offline mocks byte-identically."* We did exactly that. The byte-identical **mock** is precisely what ran in production and made the feature *look* wired. We nailed the prep, and the prep was what hid the failure. Lesson: mock-parity discipline proves the *contract shape* is honored; it says nothing about whether the *real source* is connected.

### 4.3 Layer two — a latent clobber that would have nulled real data anyway
Even where a token count *was* written, it got clobbered. The token write was `@Transactional(REQUIRES_NEW)`, but the ambient `onResult` transaction re-saved the row via `markCompleted` from a **stale entity** with a **full-row UPDATE** (no `@DynamicUpdate`), nulling the side-write. (The separate `providerUsage` survived only because it lives in a different table.) So the epic had a correctness bug that would have destroyed real data even after 4.1 was fixed.

### 4.4 Root cause: the clobber is another B1-family bug — in the epic that was supposed to be safe from it
The token clobber is a **transaction-boundary / stale-entity / replay** bug — the *exact* family 3f's retro named as "the cost of deferring B1 (the shared idempotent/replay-safe side-effect pattern)." 3g was meant to be the epic where that family *didn't* appear — additive read-model only. **It appeared anyway, in a new form.** That is the sharpest insight of this retro: **B1's absence is not a heavy-epic problem; it is a whenever-we-do-a-cross-boundary-side-write problem.** 3g is the second consecutive proof, and the first on a "light" epic.

### 4.5 The fix was found post-close, not by review
Both defects surfaced **after** the epic was marked done, during real-run debugging — not by the adversarial review layers (which had no real-run signal to test against). The remedies are partly landed on `feat/archive-unarchive-ui`:
- **Committed:** codex `--json` real token capture (`turn.completed.usage`; `runner.stdout` now JSONL; artifact reconstructed from `agent_message` events; `parseCodexEvents` falls back to plain text) — commits `299c560`, `2e9523b`; runner image rebuilt.
- **In flight (uncommitted working tree):** the `@DynamicUpdate` clobber fix + a review-arm `usage` field on `review-result.v1` (`RunnerExecutionEntity.java`, `RunnerExecutionService.java`, `ReviewResultHarvester.java`, `review-result.v1.schema.json` are all modified).
- **Still open:** Claude-runner real-capture parity (only codex done), and log-viewer readability now that codex stdout is JSONL.

---

## 5. Previous Retrospective Follow-Through (Epic 3f → Epic 3g)

| 3f commitment | Status | Evidence in 3g |
|---|---|---|
| P1: update summary exact-field guard in the same commit as `ticketTitle` | ✅ Completed | 3g-1 updated `WorkflowReadEndpointsContractTest:185` — no silent CI-only break. |
| P2: mirror the additive `usage` block byte-identical into both runners + both mocks | ✅ Completed — **and it masked the failure** (§4.2) | Byte-identical `sanitizeUsage`/`buildUsage`; the mock was all that ran. |
| P3: enforce snapshot-at-creation immutability for title/URL | ✅ Completed | 3g-1 read models never live-resolve — *this is why provenance works* (§3.1). |
| A2: mandatory 3-layer adversarial review for the replay/afterCommit story class | ✅ Applied | All 4 stories got the 3 layers. **Caveat:** review can't catch a real-run-only defect with no real-run signal (§4.5). |
| A1/B1: extract the shared replay-safe afterCommit pattern (committed to Epic 4) | ⏳ **Still open — and its absence bit 3g** (§4.4) | The token clobber is a B1-family bug. **Course-corrected this retro:** pull B1 forward *before* 3h (see §8). |

**Continuity signal (third retro running):** the *prep* actions land cleanly; the *consolidation* debt (B1) stays open and keeps costing us. 3g is the first evidence it costs even on a "safe" epic.

---

## 6. Significant Discovery — Epic 3h Impact

**No change to the Epic 3h plan's *shape* — but two hard prerequisites and one sequencing course-correction.**

Epic 3h (Pre-Review Quality Gates & Delivery-Tail Governance) is the **opposite weight** of 3g: two new `RunnerStage`s (BUILD, LINT), two new non-terminal `WorkflowState`s (`WaitingForLintApproval`, `WaitingForDelivery`), three new `AllowedAction`s, a new build `FailureCategory`, the structural **`captureAndPush` relocation** out of `onResult`, a GitHub Actions CI reader, and ADR-0030. It is the heaviest foundation-gate + afterCommit work since 3f.

1. **3h re-derives the async machinery B1 consolidates — three times, before Epic 4.** 3h-1 (build fix-loop), 3h-2 (lint fix-loop), and 3h-5 (CI fix-loop) each rebuild the `priorFeedbackReferences` + loop-counter + escalation pattern. With B1 scheduled *after* 3h, that's three more re-derivations of exactly the pattern that just bit us in 3g. **Alex's decision: pull B1 forward before 3h** so the three loops are its first consumers.
2. **3h's no-token guarantee rides on 3g's token seam.** 3h-1 asserts BUILD records **zero** token usage (command-only). If 3g's capture isn't trusted against *real* output, that assertion sits on a shaky seam. The 3g lesson — *verify capture against the real producer, not the mock* — applies straight to 3h.
3. **The foundation-gate discipline returns hard.** The light 3g footprint was a one-epic breather; 3h reintroduces the full state/action/error-code drift discipline. Do not carry a "this is additive and cheap" reflex into 3h.

---

## 7. Readiness Assessment — Epic 3g

| Dimension | Status | Notes |
|---|---|---|
| Stories complete | ✅ 4/4 `done` | Retrospective was the only remaining deferred key. |
| Provenance (3g-1/3g-2) | ✅ Production-ready | Real snapshotted title data; queue label + Origin block work; axe-clean. |
| Token accounting (3g-3/3g-4) | ⚠️ **Story-done, but hollow at close** | The capture pipeline + display are correct; **real extraction did not exist at epic close** (mock-only) and a clobber bug nulled writes. Codex real-capture now landed; Claude parity + log-viewer readability still open. |
| Testing & quality | ✅ Strong on plumbing / ⚠️ **blind to the real source** | Surefire ~1477/0 at 3g-4 close; real-PG ITs on persistence; FE vitest + axe green. But no test exercised a real agent run → real tokens. |
| Open findings | ✅ None from review / ⚠️ 2 post-close | Both being fixed on `feat/archive-unarchive-ui`. |
| Deployment / stakeholder | Local-first | No external stakeholder gate recorded. |

**Verdict:** Unlike 3f ("genuinely complete, not story-done-secretly-broken"), **3g's token half *was* story-done-secretly-empty.** The provenance half is genuinely complete. FR74's real value is realized *in progress*, on a branch — formalized as story **3g-5** (§8).

---

## 8. Action Items

### Decisions taken this retro (Alex)

| # | Decision |
|---|---|
| D1 | **AC discipline — both layers.** Any capture/telemetry story MUST have (a) at least one story-level AC exercising the **real producer** end-to-end (real codex/claude output → real value shown), AND (b) an **epic-close real-run smoke gate** ("one real run shows real data") before the epic is marked done. |
| D2 | **Pull B1 forward before Epic 3h.** Extract the shared idempotent/replay-safe afterCommit helper *before* 3h, so 3h's build/lint/CI fix-loops are its first consumers instead of three re-derivations. This re-sequences the 3f-retro commitment (B1 was an Epic 4 deliverable). |
| D3 | **Spin story 3g-5** for token real-capture completion — formalize the in-flight branch work with ACs (Claude parity, JSONL-stdout log-viewer readability, and a real run showing real tokens as the closing proof), rather than leaving it as loose branch work. |

### Process / Quality

| ID | Action | Owner | Success criteria |
|---|---|---|---|
| A1 | Codify the **real-producer AC** (D1a) as a `create-story` gate for any capture/telemetry/metrics story. | Dana | Checklist line + create-story flags such stories; no future "mock-only, marked done." |
| A2 | Add an **epic-close real-run smoke gate** (D1b) to the retrospective / done-definition. | Amelia / Dana | An epic touching runtime capture is not `done` until one real run demonstrates the captured value. |
| A3 | Add "**a `REQUIRES_NEW` side-write must not be clobbered by a full-row UPDATE from a stale entity** — use `@DynamicUpdate` / re-read before save" to the review checklist. | Winston / Dana | Checklist line exists; reviewers flag any side-write sharing a row with an ambient-tx full-row save. |

### Technical

| ID | Item | Owner | Disposition |
|---|---|---|---|
| B1 | Extract the shared replay-safe afterCommit side-effect pattern (`REQUIRES_NEW` + advisory lock + swallow/log + idempotent re-invoke + no-clobber save). | Winston / Charlie | **Course-corrected: pulled forward to BEFORE Epic 3h (D2).** Was Epic 4. Needs a `correct-course`/sprint-planning pass to re-sequence. |
| 3g-5 | Token real-capture completion: Claude-runner parity with codex `--json`; log-viewer readability for JSONL stdout; land the `@DynamicUpdate` clobber fix; verify one real run shows real tokens. | Charlie | **New story (D3).** Complete before 3h-1 leans on the token seam. |
| TD-tokens | Codex real-capture committed; clobber fix uncommitted on `feat/archive-unarchive-ui`; image rebuilt for codex only. | Charlie | Folded into 3g-5. |
| TD1–TD4 (3e), TD5 (3f) | Clarification bounds, prompt-injection posture, ack-fence dedup, project-config concurrency; 3f-8 dead interval-ms clamp. | — | Still open / accepted; none block 3h. |

### Epic 3h Preparation

| ID | Action | Owner |
|---|---|---|
| P1 | Run `correct-course` / sprint-planning to (a) insert **3g-5**, (b) re-sequence **B1** before 3h, (c) confirm 3h story order after B1. | Alex / Amelia |
| P2 | Ensure 3h-1's **no-token BUILD assertion** is verified against a **real** command-only execution (the 3g lesson), not a mock. | Dana |
| P3 | Treat 3h as **full foundation-gate discipline** (2 stages, 2 states, 3 actions, 1 error code, push relocation, ADR-0030) — the 3g light footprint does not carry over. | Winston |

### Team Agreements

- **Mock-parity proves the contract shape, not the data source.** A telemetry/capture feature is not delivered until a *real* producer feeds it, proven by a real run.
- **B1 is no longer deferrable.** Two consecutive epics — one heavy (3f), one light (3g) — proved the replay/tx-boundary family costs us regardless of epic weight. It moves ahead of 3h.
- **Reuse remains the default**; 3g-1's snapshot-at-creation is the model for "record real truth early so the display has truth to render."

---

## 9. Key Takeaways

1. **Provenance succeeded because its data was real and snapshotted early; tokens failed because the data source was never connected.** Same epic, opposite outcomes, one root cause: real-source verification.
2. **All ACs can pass while the feature delivers nothing** — 3g-3/3g-4 validated the pipe, never the water. Hence D1: a mandatory real-producer AC + an epic-close real-run smoke gate.
3. **The one real bug was a B1-family bug on a "safe" epic** — the token clobber (`REQUIRES_NEW` side-write nulled by a stale-entity full-row UPDATE). B1's absence bites regardless of epic weight.
4. **Prep can mask the very thing it was meant to ensure** — flawless byte-identical mock-parity (P2) is exactly what hid the missing real capture.
5. **B1 is pulled forward before 3h** — 3h's three fix-loops become its first consumers, not three more re-derivations.
6. **3h is the opposite of 3g** — heaviest foundation-gate + afterCommit work since 3f; the light-footprint reflex must not carry over.

---

## 10. Next Steps

1. Review this retrospective.
2. `epic-3g-retrospective` marked `done` in sprint status.
3. **Run `correct-course` / sprint-planning** to insert story **3g-5** and re-sequence **B1** before Epic 3h (P1).
4. Complete **3g-5** (token real-capture: Claude parity, JSONL log-viewer readability, land the clobber fix, verify a real run) before 3h-1 leans on the token seam.
5. Extract **B1** (shared replay-safe afterCommit helper) as the gate to starting 3h.
6. Begin Epic 3h with full foundation-gate discipline (ADR-0030, push relocation as the structural crux).

---

Amelia (Developer): "Epic 3g reviewed. The title half is a clean win and a model for 'record real truth early.' The token half taught us the hard way that green ACs can sit on top of an empty pipe — so we're adding a real-run gate, spinning 3g-5 to finish the real capture, and finally pulling B1 forward before 3h asks for it three more times. Honest epic. Good corrections."
