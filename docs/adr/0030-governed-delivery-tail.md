# ADR 0030 — Governed Delivery Tail (Build/Lint Gates, Review Modes, Push & PR/MR Governance, CI Investigation)

**Status:** Proposed (2026-06-29) — to be confirmed during Epic 3h story creation (3h-1..3h-6)
**Driver:** Epic 3h (PRD FR75 build validation + auto-fix, FR76 CPU lint gate, FR77 BMAD review mode, FR78 push-mode + PR/MR governance, FR79 CI build-error investigation). Today the delivery tail is rigid and ungated: produced code flows straight to an advisory review and is then auto-pushed with an auto-created PR, with no cheap quality gates in front of the expensive language-model review and no per-project control over whether/when work is pushed.

## Context

Through Epic 3g the runner stage set is exactly `{INVESTIGATION, EXECUTION, REVIEW}` — there is no build or static-analysis stage. The advisory reviewer (ADR 0026, `RunnerStage.REVIEW`) is the only quality signal. `RepositoryWorkspaceService.captureAndPush()` runs commit → `GitCommandPort.push` → `createOrUpdatePullRequest` **automatically** the instant the implementation result lands in `RunnerBroker.onResult`, self-gated only on "a repository workspace exists and has uncommitted changes"; PR creation is automatic on a successful push (the only implicit gate is a stamped `repoRef`). There is **no** per-project push-mode or create-PR flag, and **no** continuous-integration awareness anywhere (no checks/Actions/Pipelines reader; `RepositoryHostCapabilities.supportsRequiredStatusChecks` is a static declared flag, not a live read).

Substrates to reuse rather than re-derive:
- the command-only runner-execution path + raw-output capture (story 3.6) + the per-step log/step view (3d-5) — hosts a no-token BUILD/LINT execution;
- the advisory-reviewer channel (ADR 0026 — `enqueueReviewerIfConfigured`, `step_reviews`, `review-result`, the verdict panel, `reviewer_model_kind`, advisory-only / degrade-not-5xx);
- the `priorFeedbackReferences` bundle + loop-counter + escalation-marker pattern (spec-rejection / ADR 0029 split-repropose) — powers every bounded fix loop;
- per-project configuration on the `Project` aggregate resolved through `ProjectRuntimeConfigResolver` (the `auto-dispatch` / `openspecEnabled` precedent).

## Decision

**1. Build and lint are command-only, no-token runner stages that run before review.** New `RunnerStage` values `BUILD` and `LINT` execute the governed project's own configured build/lint commands in the materialized workspace, capturing output through the story-3.6 path and surfacing on the 3d-5 step view. Both are per-project and **default disabled** — a project with no build/lint command configured skips them (byte-identical parity). Every exhaustive `switch(stage)` consumer gains a BUILD/LINT arm.

**2. A build failure triggers a bounded auto-fix loop, then escalates.** On BUILD failure the implementation runner is re-dispatched with the build error log as a redaction-policed `priorFeedbackReferences` input; a `build_fix_loop_count` (distinct idempotency keys) is tracked with an escalation marker and a configurable cap. Cap exceeded → the run fails with a build `FailureCategory` for Epic-4 recovery — never silently pushed.

**3. Critical lint findings are a hard gate before the language-model review.** A new non-terminal `WaitingForLintApproval` state holds the run when the linter reports critical (error-severity) findings; new `AllowedAction`s `approve_lint` (proceed to REVIEW) and `request_lint_fix` (re-dispatch with findings as referenced feedback, loop-counted) resolve it. Non-critical findings are advisory and proceed. This spends review tokens only on code that passes static analysis.

**4. BMAD-style multi-layer review is a new review *mode* that augments the single-pass reviewer.** `reviewer_model_kind` gains a `bmad` mode that runs a multi-layer adversarial review (Blind Hunter / Edge-Case Hunter / Acceptance Auditor) and emits a richer verdict via an additive `review-result` field; the single-pass reviewer remains available. The ADR 0026 advisory-only / degrade-not-5xx posture is preserved.

**5. `captureAndPush` relocates from `onResult` to the end of the tail.** To run build → lint → review before code is pushed, the push/PR step is lifted out of the `RunnerBroker.onResult` EXECUTION arm and moved to a final delivery step after the gates pass. The backend keeps git ownership (`GitCommandPort`, `RepositoryWorkspaceService`); only the trigger point moves.

**6. Push and PR/MR creation are governed by a unified per-project delivery gate.** Per-project `pushMode ∈ {auto, manual, approve}` (default `auto`) + `autoCreatePullRequest` (default `true`). A new non-terminal `WaitingForDelivery` state + a single `approve_delivery` action perform push and/or PR per the two flags: **auto** runs `captureAndPush` automatically once gates pass; **approve** parks at `WaitingForDelivery` until `approve_delivery`; **manual** parks and the system never calls git/PR, recording the operator's out-of-band delivery on `approve_delivery`. PR default-on keeps existing projects byte-identical (modulo push now firing after the gates).

**7. CI build-error investigation reads the pushed branch's result and bounded-fixes it.** A new `RepositoryHostAdapter` CI-status port method + `supportsCiStatusReads` capability (GitHub Actions first; Bitbucket Pipelines in Epic 3i) lets a pushed run poll its CI build; a failed build triggers a bounded investigation/fix loop (distinct from the local build loop, same machinery). `supportsRequiredStatusChecks` becomes meaningfully backed where the new capability is true.

## Alternatives Considered

### Alt 1 — Build/lint as application-layer steps rather than runner stages
**Rejected.** Running build/lint outside the runner-execution substrate would re-derive log capture, status tracking, and the step view. A command-only runner execution reuses all of it; the only cost is the `switch(stage)` fan-out.

### Alt 2 — Lint as advisory-only (no gate)
**Rejected per the requirement.** The point of a CPU-cheap gate is to avoid spending review tokens on code that fails static analysis; an advisory linter that still proceeds to review does not save that cost. Non-critical findings remain advisory.

### Alt 3 — BMAD review replaces the single-pass reviewer
**Rejected.** A multi-layer adversarial pass is more expensive; forcing it on every project removes the lightweight option. A selectable mode lets projects choose depth.

### Alt 4 — Separate push-approval and PR-approval gates
**Rejected as the default.** Two states + two actions add fan-out for a distinction most projects don't need; a unified delivery gate driven by two per-project flags covers the same space with one state/action. Granular split remains a forward option.

### Alt 5 — Keep auto-push in `onResult`, gate only PR creation
**Rejected.** It cannot satisfy "review before the build is finished" for manual-push projects, because the push (and therefore the CI build) would still fire before review. Relocating the push is what makes the ordering configurable.
