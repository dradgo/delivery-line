/**
 * Story 3f-4 (advisory split-proposal channel) — the Split Proposal Panel + its governed
 * actions, beside the Decision Bar at the WaitingForSpecApproval / WaitingForReview gates.
 *
 * FRONT-HALF / ADVISORY ONLY: this surfaces a model-proposed decomposition of the ticket into
 * subtasks (with dependency edges) and offers the three governed loop actions — request a
 * proposal, re-propose with feedback, or decline (continue as one ticket). NOTHING here commits
 * children, moves the parent, or changes the run state; the commit affordance ("approve_split")
 * is deferred to story 3f-5 (see the clearly-marked placeholder below). Whole-proposal display
 * only — no per-subtask editing.
 *
 * Accessibility (WCAG 2.1 AA): the channel state is conveyed with a color-INDEPENDENT signifier
 * (icon + text label via {@link StateSignifierChip}), never color alone — mirrors the advisory
 * {@link ReviewerVerdictPanel}.
 */
import { useState } from 'react';
import { Link } from '@tanstack/react-router';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { components } from '@/lib/api/schema';
import type { StateName } from '@/lib/state-signifiers';

import {
  normalizeActions,
  resolveConsequenceHint,
  type ApprovalBarMode,
} from '../approvalDecisionView';
import { useAllowedActions } from '../hooks/useAllowedActions';
import { useSplitProposal, type SplitProposalResponse } from '../hooks/useSplitProposal';
import {
  useApproveSplit,
  useDeclineSplit,
  useReproposeSplit,
  useRequestSplit,
} from '../hooks/useSplitActions';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import { StateSignifierChip } from './WorkflowStateBadge';

/** Story 3f-5 — the commit result the approve action returns. */
export type SplitCommitResponse = components['schemas']['SplitCommitResponse'];

/** The channel-state → color-independent signifier mapping (icon + label). */
const STATE_SIGNIFIER: Record<
  NonNullable<SplitProposalResponse['state']>,
  { stateName: StateName; label: string } | null
> = {
  // `none` renders nothing (handled before this map is read) — kept for exhaustiveness.
  none: null,
  pending: { stateName: 'loading', label: 'Split proposal in progress' },
  available: { stateName: 'informational', label: 'Split proposal ready' },
  unavailable: { stateName: 'warning', label: 'Split proposal unavailable' },
};

export interface SplitProposalPanelProps {
  proposal: SplitProposalResponse | undefined;
  className?: string | undefined;
}

/**
 * Pure presentational display of the proposal channel. Returns `null` when there is nothing to
 * show (no proposal yet — `state === 'none'` or before the first fetch).
 */
export function SplitProposalPanel({ proposal, className }: SplitProposalPanelProps) {
  if (proposal == null || proposal.state === undefined || proposal.state === 'none') {
    return null;
  }
  const signifier = STATE_SIGNIFIER[proposal.state];
  const payload = proposal.proposal;
  const subtasks = payload?.subtasks ?? [];
  const dependencies = payload?.dependencies ?? [];

  return (
    <section
      aria-label="Advisory split proposal"
      data-testid="split-proposal-panel"
      data-split-state={proposal.state}
      className={cn('flex flex-col gap-2 rounded-md border p-3 text-sm', className)}
    >
      <div className="flex items-center justify-between gap-2">
        <h3 className="font-medium">Split proposal</h3>
        <span className="text-xs text-muted-foreground">Advisory only — nothing is committed</span>
      </div>

      {signifier !== null ? (
        <StateSignifierChip
          stateName={signifier.stateName}
          label={signifier.label}
          testId={`split-proposal-${proposal.state}`}
        />
      ) : null}

      {proposal.state === 'available' ? (
        <div className="flex flex-col gap-3">
          {payload?.selfReview === true ? (
            <StateSignifierChip
              stateName="warning"
              label="Same model produced and reviewed this proposal"
              testId="split-proposal-self-review"
            />
          ) : null}

          <div data-testid="split-proposal-subtasks" className="flex flex-col gap-2">
            <h4 className="text-meta text-text-tertiary">Proposed subtasks</h4>
            {subtasks.length === 0 ? (
              <p className="text-body text-text-secondary">None</p>
            ) : (
              <ol className="flex flex-col gap-2">
                {subtasks.map((subtask, index) => (
                  <li
                    key={subtask.ordinal ?? index}
                    className="flex flex-col gap-0.5"
                    data-testid="split-proposal-subtask"
                  >
                    <span className="font-medium">
                      {subtask.ordinal !== undefined ? `${subtask.ordinal}. ` : ''}
                      {subtask.title ?? 'Untitled subtask'}
                    </span>
                    {subtask.scope != null && subtask.scope !== '' ? (
                      <span className="text-meta text-text-secondary">{subtask.scope}</span>
                    ) : null}
                  </li>
                ))}
              </ol>
            )}
          </div>

          {dependencies.length > 0 ? (
            <div data-testid="split-proposal-dependencies" className="flex flex-col gap-1">
              <h4 className="text-meta text-text-tertiary">Dependencies (must complete first)</h4>
              <ul className="flex flex-col gap-1">
                {dependencies.map((dep, index) => (
                  <li key={`${dep.fromOrdinal}-${dep.toOrdinal}-${index}`} className="text-meta">
                    {/* "from → to": subtask `from` depends on subtask `to`. */}
                    <code>
                      {dep.fromOrdinal ?? '?'} &rarr; {dep.toOrdinal ?? '?'}
                    </code>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <dl className="grid grid-cols-[auto_1fr] gap-x-2 text-xs text-muted-foreground">
            {payload?.reviewerModelIdentity != null ? (
              <>
                <dt>Reviewed by</dt>
                <dd data-testid="split-proposal-reviewer-identity">
                  {payload.reviewerModelIdentity}
                </dd>
              </>
            ) : null}
            {payload?.producerModelIdentity != null ? (
              <>
                <dt>Produced by</dt>
                <dd data-testid="split-proposal-producer-identity">
                  {payload.producerModelIdentity}
                </dd>
              </>
            ) : null}
          </dl>

          {/*
            Story 3f-5 — the governed commit affordance ("approve_split") is rendered by the
            SplitActionBar below the panel (allowed-actions-driven), and the commit RESULT is
            rendered by SplitCommitResultPanel once the approve lands.
          */}
        </div>
      ) : null}

      <p className="text-meta text-text-tertiary" data-testid="split-proposal-loop-count">
        Re-propose attempts: {proposal.loopCount ?? 0}
      </p>
    </section>
  );
}

/** The per-subtask outcome status → color-independent signifier mapping. */
const FAILED_SUBTASK_SIGNIFIER: { stateName: StateName; label: string } = {
  stateName: 'warning',
  label: 'Failed',
};
const SUBTASK_STATUS_SIGNIFIER: Record<string, { stateName: StateName; label: string }> = {
  created: { stateName: 'informational', label: 'Sub-ticket created' },
  internal_only: { stateName: 'informational', label: 'Internal-only' },
  failed: FAILED_SUBTASK_SIGNIFIER,
};

export interface SplitCommitResultPanelProps {
  result: SplitCommitResponse | undefined;
  className?: string | undefined;
}

/**
 * Story 3f-5 — renders the commit result: each subtask's outcome (created / internal-only /
 * failed), whether the parent decomposed, and the parent → child links. A zero-child commit
 * (outcome=aborted_no_children) is surfaced with a color-independent warning signifier (the
 * parent stays at its gate). Returns `null` before any approve has landed.
 */
export function SplitCommitResultPanel({ result, className }: SplitCommitResultPanelProps) {
  if (result == null) {
    return null;
  }
  const childRunIds = result.childRunIds ?? [];
  const subtasks = result.subtasks ?? [];
  const aborted = result.parentDecomposed !== true;

  return (
    <section
      aria-label="Split commit result"
      data-testid="split-commit-result"
      data-split-outcome={result.outcome}
      data-parent-decomposed={String(result.parentDecomposed === true)}
      className={cn('flex flex-col gap-2 rounded-md border p-3 text-sm', className)}
    >
      <h3 className="font-medium">Split committed</h3>
      <StateSignifierChip
        stateName={aborted ? 'warning' : 'informational'}
        label={
          aborted
            ? 'No subtasks could be created — the run stays at its gate'
            : `Decomposed into ${childRunIds.length} child run${childRunIds.length === 1 ? '' : 's'}`
        }
        testId={`split-commit-${aborted ? 'aborted' : 'decomposed'}`}
      />

      <ol className="flex flex-col gap-1" data-testid="split-commit-subtasks">
        {subtasks.map((subtask, index) => {
          const status = subtask.status ?? 'failed';
          const signifier = SUBTASK_STATUS_SIGNIFIER[status] ?? FAILED_SUBTASK_SIGNIFIER;
          return (
            <li
              key={subtask.ordinal ?? index}
              className="flex items-center gap-2"
              data-testid="split-commit-subtask"
              data-subtask-status={status}
            >
              <span className="font-medium">{subtask.ordinal ?? index + 1}.</span>
              <span className="text-meta text-text-secondary">{signifier.label}</span>
              {subtask.childRunId != null && subtask.childRunId !== '' ? (
                <Link
                  to="/workflows/$workflowRunId"
                  params={{ workflowRunId: subtask.childRunId }}
                  className="text-meta underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                  data-testid="split-commit-child-link"
                >
                  {subtask.childRunId}
                </Link>
              ) : null}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

export interface SplitActionBarProps {
  /** Drives the consequence-hint text (identical copy for both gate modes). */
  mode: ApprovalBarMode;
  /** Advertised by the gate's allowed-actions: request when no proposal is open. */
  canRequest: boolean;
  /** Advertised when a proposal IS open. */
  canRepropose: boolean;
  /** Advertised when a proposal IS open (decline → continue as one ticket). */
  canDecline: boolean;
  /** Story 3f-5 — advertised when a proposal IS open (approve → commit the split). */
  canApprove: boolean;
  /** A split mutation is in flight — disable the controls. */
  pending: boolean;
  onRequest: () => void;
  onRepropose: (feedbackText: string) => void;
  onDecline: () => void;
  onApprove: () => void;
}

/**
 * The governed split actions, rendered from the gate's allowed-actions (never role-inferred).
 * Renders nothing when no split action is advertised, keeping the panel calm for ordinary runs.
 */
export function SplitActionBar({
  mode,
  canRequest,
  canRepropose,
  canDecline,
  canApprove,
  pending,
  onRequest,
  onRepropose,
  onDecline,
  onApprove,
}: SplitActionBarProps) {
  const [feedbackText, setFeedbackText] = useState('');

  if (!canRequest && !canRepropose && !canDecline && !canApprove) {
    return null;
  }

  const requestHint = resolveConsequenceHint(mode, 'request_split');
  const reproposeHint = resolveConsequenceHint(mode, 'repropose_split');
  const declineHint = resolveConsequenceHint(mode, 'continue_as_single');
  const approveHint = resolveConsequenceHint(mode, 'approve_split');

  return (
    <div className="flex flex-col gap-2" data-testid="split-action-bar">
      {canApprove ? (
        <div className="flex flex-col gap-1">
          <div>
            <Button
              type="button"
              variant="default"
              data-primary="true"
              disabled={pending}
              onClick={onApprove}
              data-testid="split-approve-button"
            >
              Approve split
            </Button>
          </div>
          {approveHint !== undefined ? (
            <p className="text-meta text-text-tertiary">{approveHint}</p>
          ) : null}
        </div>
      ) : null}

      {canRequest ? (
        <div className="flex flex-col gap-1">
          <div>
            <Button
              type="button"
              variant="default"
              data-primary="true"
              disabled={pending}
              onClick={onRequest}
              data-testid="split-request-button"
            >
              Request split proposal
            </Button>
          </div>
          {requestHint !== undefined ? (
            <p className="text-meta text-text-tertiary">{requestHint}</p>
          ) : null}
        </div>
      ) : null}

      {canRepropose ? (
        <div className="flex flex-col gap-1">
          <label className="flex flex-col gap-1 text-meta text-text-secondary">
            <span>Feedback for the re-proposal</span>
            <textarea
              value={feedbackText}
              onChange={(event) => setFeedbackText(event.target.value)}
              rows={3}
              className="rounded-md border border-input bg-background p-2 text-sm text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
              data-testid="split-repropose-feedback"
            />
          </label>
          <div>
            <Button
              type="button"
              variant="outline"
              disabled={pending || feedbackText.trim() === ''}
              onClick={() => onRepropose(feedbackText.trim())}
              data-testid="split-repropose-button"
            >
              Re-propose with feedback
            </Button>
          </div>
          {reproposeHint !== undefined ? (
            <p className="text-meta text-text-tertiary">{reproposeHint}</p>
          ) : null}
        </div>
      ) : null}

      {canDecline ? (
        <div className="flex flex-col gap-1">
          <div>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={onDecline}
              data-testid="split-decline-button"
            >
              Continue as single ticket
            </Button>
          </div>
          {declineHint !== undefined ? (
            <p className="text-meta text-text-tertiary">{declineHint}</p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

export interface SplitProposalPanelContainerProps {
  workflowRunId: string;
  className?: string | undefined;
}

/**
 * Container — wires the proposal read (which polls while pending) + the gate's allowed-actions
 * (which advertise request_split ⇄ repropose_split + continue_as_single) + the three split
 * mutations into the presentational panel and action bar. Slotted beside the Decision Bar at the
 * WaitingForSpecApproval / WaitingForReview gates. On success the factory invalidates the
 * `detail(id)` prefix, so the panel and allowed-actions refetch.
 */
export function SplitProposalPanelContainer({
  workflowRunId,
  className,
}: SplitProposalPanelContainerProps) {
  const { data: proposal } = useSplitProposal(workflowRunId);
  const allowedActionsQuery = useAllowedActions(workflowRunId);
  const detailQuery = useWorkflowDetail(workflowRunId);
  const requestSplit = useRequestSplit(workflowRunId);
  const reproposeSplit = useReproposeSplit(workflowRunId);
  const declineSplit = useDeclineSplit(workflowRunId);
  const approveSplit = useApproveSplit(workflowRunId);
  // Hold the last commit result so the per-subtask outcomes + parent → child links survive the
  // allowed-actions/detail refetch that tears the proposal panel down once the run leaves its gate.
  const [commitResult, setCommitResult] = useState<SplitCommitResponse | undefined>(undefined);

  const actions = normalizeActions(allowedActionsQuery.data?.actions);
  const mode: ApprovalBarMode =
    detailQuery.data?.currentState === 'WaitingForReview'
      ? 'implementation_review'
      : 'spec_approval';
  const pending =
    requestSplit.isPending ||
    reproposeSplit.isPending ||
    declineSplit.isPending ||
    approveSplit.isPending;

  // Field-only structured logging (T-LOG-PII): the resulting channel state ONLY — NEVER
  // feedbackText / ids. Mirrors the sibling decision-bar containers.
  const logResult = (event: string) => (data: SplitProposalResponse) => {
    console.info({ event, state: data.state });
  };
  const logError = (event: string) => () => {
    console.warn({ event });
  };

  return (
    <div className={cn('flex flex-col gap-3', className)} data-testid="split-proposal-region">
      <SplitProposalPanel proposal={proposal} />
      <SplitCommitResultPanel result={commitResult} />
      <SplitActionBar
        mode={mode}
        canRequest={actions.includes('request_split')}
        canRepropose={actions.includes('repropose_split')}
        canDecline={actions.includes('continue_as_single')}
        canApprove={actions.includes('approve_split')}
        pending={pending}
        onRequest={() =>
          requestSplit.mutate(undefined, {
            onSuccess: logResult('split.requestSubmit'),
            onError: logError('split.requestError'),
          })
        }
        onRepropose={(feedbackText) =>
          reproposeSplit.mutate(
            { feedbackText },
            {
              onSuccess: logResult('split.reproposeSubmit'),
              onError: logError('split.reproposeError'),
            },
          )
        }
        onDecline={() =>
          declineSplit.mutate(undefined, {
            onSuccess: logResult('split.declineSubmit'),
            onError: logError('split.declineError'),
          })
        }
        onApprove={() =>
          approveSplit.mutate(undefined, {
            onSuccess: (data) => {
              setCommitResult(data);
              // Field-only logging: the aggregate outcome + counts ONLY — NEVER ids/titles.
              console.info({
                event: 'split.approveSubmit',
                outcome: data.outcome,
                parentDecomposed: data.parentDecomposed,
                childCount: data.childRunIds?.length ?? 0,
              });
            },
            onError: logError('split.approveError'),
          })
        }
      />
    </div>
  );
}
