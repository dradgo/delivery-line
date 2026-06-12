/**
 * Story 3.30 (P3, code review 2026-06-13) — the state-driven Decision Bar selector.
 *
 * A `Failed` run gets the `recovery_operator` bar (the "Retry failed step" action);
 * every other state gets the story-2.19 `spec_approval` bar.
 *
 * WHY THIS OWNS THE RETRY MUTATION: a successful retry transitions the run
 * `Failed → Executing` (`RecoveryService`). If the mode were keyed purely on
 * `currentState`, that flip would UNMOUNT the recovery bar the instant the retry
 * succeeds — tearing down its `success` panel AND the AC7 `retryRecorded` live-region
 * announcement before either is seen. By holding the `useRetryWorkflow` instance HERE
 * and keeping the recovery bar mounted while the retry is pending OR settled-successful,
 * the success acknowledgement survives the post-retry state flip. The SAME instance is
 * threaded into the container so the click and the kept-alive state agree (a separate
 * container-internal instance would never observe the success this component gates on).
 */
import { useRetryWorkflow } from '../hooks/useRetryWorkflow';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { ApprovalDecisionBarContainer } from './ApprovalDecisionBarContainer';
import { RecoveryDecisionBarContainer } from './RecoveryDecisionBarContainer';

export interface WorkflowDecisionBarProps {
  workflowRunId: string;
}

export function WorkflowDecisionBar({ workflowRunId }: WorkflowDecisionBarProps) {
  const { data } = useWorkflowDetail(workflowRunId);
  const retry = useRetryWorkflow(workflowRunId);

  // Keep the recovery bar mounted through a settling retry (pending OR just-succeeded)
  // so the success panel + the AC7 announcement are not torn down when `currentState`
  // flips Failed→Executing on success.
  const showRecovery = data?.currentState === 'Failed' || retry.isPending || retry.isSuccess;

  return showRecovery ? (
    <RecoveryDecisionBarContainer
      workflowRunId={workflowRunId}
      layout="sticky_footer"
      retry={retry}
    />
  ) : (
    <ApprovalDecisionBarContainer workflowRunId={workflowRunId} layout="sticky_footer" />
  );
}
