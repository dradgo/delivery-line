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
import { useRejectImplementation } from '../hooks/useRejectImplementation';
import { useRetryWorkflow } from '../hooks/useRetryWorkflow';
import { useTakeoverWorkflow } from '../hooks/useTakeoverWorkflow';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { ApprovalDecisionBarContainer } from './ApprovalDecisionBarContainer';
import { ImplementationReviewDecisionBarContainer } from './ImplementationReviewDecisionBarContainer';
import { RecoveryDecisionBarContainer } from './RecoveryDecisionBarContainer';

export interface WorkflowDecisionBarProps {
  workflowRunId: string;
}

export function WorkflowDecisionBar({ workflowRunId }: WorkflowDecisionBarProps) {
  const { data } = useWorkflowDetail(workflowRunId);
  const retry = useRetryWorkflow(workflowRunId);
  const accept = useAcceptImplementation(workflowRunId);
  const reject = useRejectImplementation(workflowRunId);
  const takeover = useTakeoverWorkflow(workflowRunId);

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
  const showRecovery = data?.currentState === 'Failed' || retry.isPending || retry.isSuccess;

  if (showImplReview) {
    return (
      <ImplementationReviewDecisionBarContainer
        workflowRunId={workflowRunId}
        layout="sticky_footer"
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
        layout="sticky_footer"
        retry={retry}
      />
    );
  }
  return <ApprovalDecisionBarContainer workflowRunId={workflowRunId} layout="sticky_footer" />;
}
