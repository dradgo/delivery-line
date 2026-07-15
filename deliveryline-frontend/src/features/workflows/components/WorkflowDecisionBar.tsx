/**
 * Story 3.30 (P3) + Story 3.28 (Task 5, R10) — the state-driven Decision Bar selector.
 *
 * A `WaitingForReview` run gets the `implementation_review` bar (accept / reject / take
 * over); a `Failed` run gets the `recovery_operator` bar ("Retry failed step"); every other
 * state gets the story-2.19 `spec_approval` bar. The three states are disjoint
 * (`WaitingForSpecApproval` vs `WaitingForReview` vs `Failed`) so there is no collision.
 *
 * WHY THIS OWNS THE MUTATIONS: a successful decision transitions the run OUT of the state
 * the bar is keyed on (retry: `Failed → Executing`; accept: `→ Executing`/`Completed`;
 * reject: `→ Executing`; takeover: `→ TakenOver`). If the mode were keyed purely on
 * `currentState`, that flip would UNMOUNT the bar the instant the decision succeeds —
 * tearing down its `success` summary, the AC7 PR affordance, AND the live-region
 * announcement before any is seen. By holding the mutation instances HERE and keeping the
 * bar mounted while a mutation is pending OR settled-successful, the acknowledgement
 * survives the post-decision state flip. The SAME instances are threaded into the container
 * so the click and the kept-alive state agree.
 */
import { useAcceptImplementation } from '../hooks/useAcceptImplementation';
import { usePauseWorkflow } from '../hooks/usePauseWorkflow';
import { useRejectImplementation } from '../hooks/useRejectImplementation';
import { useRerunFromStep } from '../hooks/useRerunFromStep';
import { useResumeWorkflow } from '../hooks/useResumeWorkflow';
import { useRetryWorkflow } from '../hooks/useRetryWorkflow';
import { useTakeoverWorkflow } from '../hooks/useTakeoverWorkflow';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import type { ApprovalBarLayout } from '../approvalDecisionView';
import { ApprovalDecisionBarContainer } from './ApprovalDecisionBarContainer';
import { ImplementationReviewDecisionBarContainer } from './ImplementationReviewDecisionBarContainer';
import { RecoveryDecisionBarContainer } from './RecoveryDecisionBarContainer';

export interface WorkflowDecisionBarProps {
  workflowRunId: string;
  /**
   * AC4 — the bar's chrome. The run-detail pane uses the default bottom-of-pane
   * `sticky_footer`; the artifact viewer mounts it `inline_section` in-flow beneath the
   * panel. The layout is threaded UNCHANGED into whichever container the run state selects.
   */
  layout?: ApprovalBarLayout | undefined;
}

export function WorkflowDecisionBar({
  workflowRunId,
  layout = 'sticky_footer',
}: WorkflowDecisionBarProps) {
  const { data } = useWorkflowDetail(workflowRunId);
  const retry = useRetryWorkflow(workflowRunId);
  const accept = useAcceptImplementation(workflowRunId);
  const reject = useRejectImplementation(workflowRunId);
  const takeover = useTakeoverWorkflow(workflowRunId);
  // Story 4.22 — hoist the deeper recovery mutations here (like `retry`) so a post-action
  // currentState flip (resume: Paused→Executing; rerun: Failed→Investigating; pause:
  // Executing→Paused) does NOT unmount the recovery bar and tear down its kept-alive success
  // summary + announcement before they are seen.
  const resume = useResumeWorkflow(workflowRunId);
  const rerun = useRerunFromStep(workflowRunId);
  const pause = usePauseWorkflow(workflowRunId);

  // Keep each bar mounted through a settling decision (pending OR just-succeeded) so the
  // success summary / PR affordance / announcement are not torn down when `currentState`
  // flips on success.
  const showImplReview =
    data?.currentState === 'WaitingForReview' ||
    accept.isPending ||
    accept.isSuccess ||
    reject.isPending ||
    reject.isSuccess ||
    takeover.isPending ||
    takeover.isSuccess;
  // Story 4.22 — the recovery bar covers BOTH `Failed` (retry/rerun/pause/classify) AND `Paused`
  // (resume/reconcile), plus any of the owned recovery mutations settling. Disjoint from the
  // impl-review predicate (WaitingForReview) and the spec_approval fallthrough — no collision.
  const showRecovery =
    data?.currentState === 'Failed' ||
    data?.currentState === 'Paused' ||
    retry.isPending ||
    retry.isSuccess ||
    resume.isPending ||
    resume.isSuccess ||
    rerun.isPending ||
    rerun.isSuccess ||
    pause.isPending ||
    pause.isSuccess;

  if (showImplReview) {
    return (
      <ImplementationReviewDecisionBarContainer
        workflowRunId={workflowRunId}
        layout={layout}
        accept={accept}
        reject={reject}
        takeover={takeover}
      />
    );
  }
  if (showRecovery) {
    return (
      <RecoveryDecisionBarContainer
        workflowRunId={workflowRunId}
        layout={layout}
        retry={retry}
        resume={resume}
        rerun={rerun}
        pause={pause}
      />
    );
  }
  return <ApprovalDecisionBarContainer workflowRunId={workflowRunId} layout={layout} />;
}
