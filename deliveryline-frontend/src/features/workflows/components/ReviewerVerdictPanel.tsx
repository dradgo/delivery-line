/**
 * Story 3d-2 (AC3/AC4/AC6/AC8) — the advisory Reviewer Verdict Panel.
 *
 * Presentational + advisory-only: it surfaces a project-configured second LLM's verdict beside the
 * WaitingForReview Decision Bar but NEVER gates or mutates the human approve/reject buttons (the
 * human decision remains governing; `reviewer_gating_enabled` is not consulted this epic). The
 * verdict state is derived server-side (`pending | available | unavailable`); this component stays
 * dumb.
 *
 * AC5 — for a project with no reviewer binding the endpoint returns `unavailable` +
 * `no_reviewer_configured`, and this panel renders NOTHING (absent entirely).
 *
 * Accessibility (AC, WCAG 2.1 AA): the outcome is conveyed with a color-INDEPENDENT signifier
 * (icon + text label via {@link StateSignifierChip}), never color alone.
 */
import { cn } from '@/lib/utils';

import type { ReviewerVerdict } from '../hooks/useReviewerVerdict';
import { useReviewerVerdict } from '../hooks/useReviewerVerdict';
import { StateSignifierChip } from './WorkflowStateBadge';
import type { StateName } from '@/lib/state-signifiers';

/** The advisory outcome → semantic state-signifier mapping (color-independent: icon + label). */
const OUTCOME_SIGNIFIER: Record<string, { stateName: StateName; label: string }> = {
  pass: { stateName: 'success', label: 'Reviewer: Pass' },
  concern: { stateName: 'warning', label: 'Reviewer: Concern' },
  fail: { stateName: 'error', label: 'Reviewer: Fail' },
};

export interface ReviewerVerdictPanelProps {
  verdict: ReviewerVerdict | undefined;
  className?: string | undefined;
}

/**
 * Pure presentational panel. Returns `null` when there is nothing to show (no verdict yet on the
 * first fetch, or a no-reviewer-configured project — AC5 panel-absent).
 */
export function ReviewerVerdictPanel({ verdict, className }: ReviewerVerdictPanelProps) {
  if (verdict == null) {
    return null;
  }
  // AC5 — no reviewer binding ⇒ render nothing.
  if (verdict.state === 'unavailable' && verdict.unavailableReason === 'no_reviewer_configured') {
    return null;
  }

  return (
    <section
      aria-label="Advisory reviewer verdict"
      data-testid="reviewer-verdict-panel"
      data-verdict-state={verdict.state}
      className={cn('rounded-md border p-3 text-sm', className)}
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 className="font-medium">Advisory reviewer</h3>
        <span className="text-xs text-muted-foreground">
          Advisory only — does not change your decision
        </span>
      </div>

      {verdict.state === 'pending' && (
        <StateSignifierChip
          stateName="informational"
          label="Review in progress"
          testId="reviewer-verdict-pending"
        />
      )}

      {verdict.state === 'unavailable' && (
        <div>
          <StateSignifierChip
            stateName="stale"
            label="Review unavailable"
            testId="reviewer-verdict-unavailable"
          />
          {verdict.unavailableReason != null && (
            <p className="mt-1 text-xs text-muted-foreground" data-testid="reviewer-verdict-reason">
              Reason: {verdict.unavailableReason}
            </p>
          )}
        </div>
      )}

      {verdict.state === 'available' && (
        <div className="space-y-2">
          <StateSignifierChip
            stateName={OUTCOME_SIGNIFIER[verdict.outcome ?? '']?.stateName ?? 'informational'}
            label={
              OUTCOME_SIGNIFIER[verdict.outcome ?? '']?.label ?? `Reviewer: ${verdict.outcome}`
            }
            testId="reviewer-verdict-outcome"
          />

          {verdict.selfReview === true && (
            <StateSignifierChip
              stateName="warning"
              label="Same model produced and reviewed this output"
              testId="reviewer-verdict-self-review"
            />
          )}

          {verdict.rationale != null && verdict.rationale !== '' && (
            <p className="whitespace-pre-wrap" data-testid="reviewer-verdict-rationale">
              {verdict.rationale}
            </p>
          )}

          <dl className="grid grid-cols-[auto_1fr] gap-x-2 text-xs text-muted-foreground">
            {verdict.reviewerModelIdentity != null && (
              <>
                <dt>Reviewed by</dt>
                <dd data-testid="reviewer-model-identity">{verdict.reviewerModelIdentity}</dd>
              </>
            )}
            {verdict.producerModelIdentity != null && (
              <>
                <dt>Produced by</dt>
                <dd data-testid="producer-model-identity">{verdict.producerModelIdentity}</dd>
              </>
            )}
          </dl>
        </div>
      )}
    </section>
  );
}

export interface ReviewerVerdictPanelContainerProps {
  workflowRunId: string;
  className?: string | undefined;
}

/**
 * Container — wires {@link useReviewerVerdict} (which polls while pending) into the presentational
 * panel. Slotted beside the Decision Bar in the WaitingForReview route.
 */
export function ReviewerVerdictPanelContainer({
  workflowRunId,
  className,
}: ReviewerVerdictPanelContainerProps) {
  const { data } = useReviewerVerdict(workflowRunId);
  return <ReviewerVerdictPanel verdict={data} className={className} />;
}
