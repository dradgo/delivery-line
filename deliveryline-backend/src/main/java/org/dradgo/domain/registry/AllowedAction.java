package org.dradgo.domain.registry;

import java.util.Map;

public enum AllowedAction implements RegistryValue {
  APPROVE_SPEC("approve_spec"),
  REJECT_SPEC("reject_spec"),
  ANSWER_CLARIFICATION("answer_clarification"),
  VIEW_ONLY("view_only"),
  AWAIT_OUTCOME("await_outcome"),
  RETRY("retry"),
  VIEW_DIAGNOSTICS("view_diagnostics"),
  CLEAR_ESCALATION_MARKER("clear_escalation_marker"),
  ACCEPT_IMPLEMENTATION("accept_implementation"),
  REJECT_IMPLEMENTATION("reject_implementation"),
  // Story 3.22 (AC9) — developer takeover; canonical executor is DeveloperTakeoverService. Offered
  // (with accept/reject) for state=WAITING_FOR_REVIEW + role=developer. After TakenOver is reached
  // the only allowed action is view_only (already wired).
  TAKEOVER_WORKFLOW("takeover_workflow"),
  // Story 3d-5 (FR65, AC6) — gate for the Step Execution Log Viewer (live + historical runner
  // logs). Role-agnostic: offered in every state where a runner execution exists (INVESTIGATING —
  // the spec-generation stage, added by story 3e-5 — EXECUTING, FAILED, PAUSED,
  // WAITING_FOR_REVIEW). The stream endpoint resolves the latest rex and returns a graceful
  // "no-runner-execution" end when none exists, so broad state coverage is safe.
  VIEW_RUNNER_LOGS("view_runner_logs"),
  // Story 3d-7 (FR69, AC5) — gate for the Provider Limit Status indicator (post-execution provider
  // 5h/weekly usage/limit, or the documented "not exposed" state). Role-agnostic, mirroring
  // view_runner_logs: offered in every state where a runner execution exists (EXECUTING,
  // WAITING_FOR_REVIEW, FAILED, PAUSED). The read endpoint resolves the latest snapshot and returns
  // an empty/absent body when none exists, so broad state coverage is safe.
  VIEW_PROVIDER_USAGE_STATUS("view_provider_usage_status"),
  // Story 3d-3 (AC7, ADR 0024) — actions a WaitingForManualExecution run advertises so the local
  // operator can obtain the emitted input bundle and submit the hand-run artifact. Registered +
  // surfaced in the action matrix here (gated to workflow_owner; other roles view_only); the
  // endpoints that honor them (GET …/manual-bundle, POST …/manual-artifact) land in story 3d-4.
  OBTAIN_MANUAL_BUNDLE("obtain_manual_bundle"),
  SUBMIT_MANUAL_ARTIFACT("submit_manual_artifact"),
  // Story 3d-6 (FR68, AC4, ADR 0025) — gate for the Read-only Diagnostic Console (attach a
  // read-only console to a LIVE runner container). Offered in the live-container states EXECUTING
  // and (story 3e-5) INVESTIGATING — the spec-generation stage, where a spec runner is also live —
  // and ONLY to the run owner (workflow_owner) — the single local operator. The endpoint re-checks
  // liveness at attach time and rejects a non-live/absent rex with console-not-live, so even this
  // narrow gate cannot open a console into an absent container.
  OPEN_DIAGNOSTIC_CONSOLE("open_diagnostic_console"),
  // Story 3d-8 (FR67, AC3/AC4, ADR 0027) — governed soft-hide affordances. Mutually exclusive per
  // run: archive_run is advertised for a NOT-archived run, unarchive_run for an already-archived
  // run. Orthogonal to the per-state lifecycle actions (a run can be hidden from any state), so
  // WorkflowInspectionService.computeActionMatrix threads a `boolean archived` and adds exactly one
  // of these. No DB CHECK exists for allowed-actions (enum <-> frontend placeholder JSON only).
  ARCHIVE_RUN("archive_run"),
  UNARCHIVE_RUN("unarchive_run"),
  // Story 3e-2 (AC1) — explicit PM judgment that an answered clarification's answer is ready to
  // drive a spec rebuild (answered -> accepted, story 2.12 lifecycle). Canonical executor is
  // WorkflowCommandService.acceptClarification -> ClarificationLifecycleService.markAccepted.
  // Surfaced (next to answer_clarification) in the WAITING_FOR_SPEC_APPROVAL reviewer-role matrix.
  // The sweep (ClarificationLifecycleOrchestrator) acts ONLY on accepted rows, so without this
  // action the visible-incorporation loop is starved. No DB CHECK exists for allowed-actions (enum
  // <-> frontend placeholder JSON only).
  ACCEPT_CLARIFICATION("accept_clarification"),
  // Story 3e-2 (AC2) — reviewer triggers a spec regeneration that incorporates the accepted
  // clarifications. Canonical executor is WorkflowCommandService.regenerateSpecWithClarifications:
  // it performs the WaitingForSpecApproval -> Investigating transition then reuses
  // WorkflowOrchestrationService.retrySpecGeneration (re-dispatch only, Trap T8). Surfaced in the
  // WAITING_FOR_SPEC_APPROVAL reviewer-role matrix alongside accept_clarification.
  REGENERATE_SPEC("regenerate_spec_with_clarifications"),
  // Story 3f-4 (AC1) — request an advisory LLM split proposal at the spec/review gate. Canonical
  // executor is SplitProposalService.request; surfaced as an advisory overlay when NO open proposal
  // exists. Creates no children/edges and does NOT move the parent out of its gate (that is 3f-5).
  // No DB CHECK exists for allowed-actions (enum <-> frontend placeholder JSON only).
  REQUEST_SPLIT("request_split"),
  // Story 3f-4 (AC1/AC5) — dismiss the current open split proposal ("continue as one ticket").
  // Canonical executor is SplitProposalService.decline; surfaced when an open proposal exists. The
  // normal gate actions are byte-identical to a run that was never split-proposed (AC5).
  DECLINE_SPLIT("continue_as_single"),
  // Story 3f-4 (AC1/AC4) — re-run the proposal call with operator feedback (materialized by
  // reference). Canonical executor is SplitProposalService.repropose; surfaced when an open
  // proposal exists.
  REPROPOSE_SPLIT("repropose_split"),
  // Story 3f-5 (AC1) — commit the current open split proposal: best-effort fan-out into child runs
  // (sub-tickets where supported, else internal-only), dependency edges, and a parent decomposition
  // into the non-terminal SPLIT state. Canonical executor is SplitCommitService.commit; surfaced
  // alongside repropose_split/continue_as_single when an open proposal exists at the gate role.
  // No DB CHECK exists for allowed-actions (enum <-> frontend placeholder JSON only).
  APPROVE_SPLIT("approve_split"),
  // Story 3h-2 (AC5, FR76) — the CPU lint gate's two operator actions, surfaced ONLY at
  // WAITING_FOR_LINT_APPROVAL for the workflow_owner gate role (other roles get view_only + the
  // log/
  // usage views). approve_lint dismisses the gate and resumes the delivery tail (capture-and-push +
  // WaitingForReview + reviewer enqueue) via the resumable seam; request_lint_fix re-dispatches the
  // EXECUTION runner with the lint findings as redaction-policed referenced feedback, bumps
  // lint_fix_loop_count, and re-parks (never auto-FAILs — Decision 3). Canonical executor is
  // LintApprovalService. No DB CHECK exists for allowed-actions (enum <-> frontend placeholder JSON
  // only).
  APPROVE_LINT("approve_lint"),
  REQUEST_LINT_FIX("request_lint_fix"),
  // Story 3h-4 (AC3, FR78) — the unified delivery gate's operator action, surfaced ONLY at
  // WAITING_FOR_DELIVERY for the workflow_owner gate role (other roles get view_only + the
  // log/usage views). approve_delivery dismisses the gate and advances to WaitingForReview: in
  // approve mode it performs the push (+ PR per autoCreatePullRequest) via the resumable delivery
  // seam; in manual mode it records the out-of-band delivery (delivery.recordedManually) WITHOUT
  // touching git (Decision 4). Canonical executor is DeliveryApprovalService. There is NO
  // request-fix twin: a non-auto delivery is dismissed by the single approve action. No DB CHECK
  // exists for allowed-actions (enum <-> frontend placeholder JSON only).
  APPROVE_DELIVERY("approve_delivery"),
  // Story 4.5 (AC9, FR47/NFR5) — the resume affordance for a paused run, surfaced ONLY at PAUSED
  // for
  // the workflow_owner gate role (other roles keep the view-only + diagnostics/log set). Canonical
  // executor is RecoveryService.resume (routing the Paused → prior-executing-state transition
  // through WorkflowCommandService.resumeWorkflow). The REST endpoint that honors it lands in story
  // 4.10. No DB CHECK exists for allowed-actions (enum <-> frontend placeholder JSON only); the
  // allowed-actions REST field is an open string[] so adding a value needs NO OpenAPI regen.
  RESUME_WORKFLOW("resume_workflow"),
  RECONCILE_CONFLICT("reconcile_conflict"),
  // Story 4.7 (AC10, Reconciliation 7) — the rerun-from-step affordance, surfaced for the
  // workflow_owner gate role at the states with a legal rerun edge (FAILED, WAITING_FOR_REVIEW).
  // Canonical executor is RecoveryService.rerunFromStep (routing the → targetStep transition
  // through
  // WorkflowCommandService.rerunFromStepWorkflow). This is a FLAT action — the allowed `targetStep`
  // sub-list (SafeRerunStep enum) is served by the OpenAPI schema in story 4.12 / the Decision Bar
  // in 4.22; the allowed-actions REST field is an open string[] so adding a value needs NO OpenAPI
  // regen. The REST endpoint that honors it lands in story 4.12. No DB CHECK exists for
  // allowed-actions (enum <-> frontend placeholder JSON only).
  RERUN_FROM_STEP("rerun_from_step");

  private static final Map<String, AllowedAction> LOOKUP = RegistryParsers.index(values());

  private final String value;

  AllowedAction(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }

  static AllowedAction fromValue(String rawValue) {
    return fromValue(rawValue, null);
  }

  public static AllowedAction fromValue(String rawValue, String field) {
    return RegistryParsers.parse("AllowedAction", rawValue, field, LOOKUP);
  }
}
