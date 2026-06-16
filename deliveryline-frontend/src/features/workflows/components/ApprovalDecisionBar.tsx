/**
 * Story 2.19 (AC1–AC10) — the `ApprovalDecisionBar` presentational composite.
 *
 * THE CENTRAL RECONCILIATION (Dev Notes): the bar reads REAL allowed-actions +
 * version stamp (the LIVE `useAllowedActions`), but the decision-FIRING path is gated
 * on a resolvable `artifactId` — no live read model exposes one yet (T-ARTIFACTID), so
 * an unresolved `artifactId` renders `blocked`. This component is PRESENTATIONAL +
 * prop-driven: it takes a resolved {@link ApprovalDecisionView} + the mutation state +
 * callbacks; tests drive every render from constructed fixtures (the 2.17/2.18
 * discipline).
 *
 * Generalized for variant modes from day one (AC1, party-mode finding #3): an
 * exhaustive `switch(mode)` with an `assertNeverMode` default — `spec_approval` is the
 * full bar; `implementation_review` (E3) / `recovery_operator` (E4) are documented
 * placeholder renderers so the mode contract holds without dead UI.
 *
 * Region-local by necessity (siblings 2.21/2.23/2.25/2.27 are backlog): the rejection
 * rationale dialog (T-MODAL — NOT 2.23's `<ConfirmationDialog>`), the inline feedback /
 * post-submit summary (T-FEEDBACK — NEVER toast, UX-DR15), and the submitting
 * indicator are all built here. Reuses the sanctioned primitives: `StateSignifierChip`
 * (T-CHIP — non-color stale/blocked signifier), `ErrorState` (`@/components/feedback`),
 * and `SafeMarkdownRenderer` (`@/lib/sanitization` BARREL — the path for the
 * potentially agent-echoed actor identity in the decision context / summary).
 */
import { useEffect, useId, useRef, useState } from 'react';

import { ErrorState } from '@/components/feedback';
import { SafeMarkdownRenderer } from '@/lib/sanitization';
import { Button } from '@/components/ui/button';
import { DecisionArea, GovernedButton } from '@/components/actions';
import { ConfirmationDialog } from '@/components/overlays/ConfirmationDialog';
import { CONFIRMATION_CATALOG } from '@/lib/overlays/confirmationCatalog';
import { cn } from '@/lib/utils';
import {
  decisionOptionsLoadFailed,
  decisionRecorded,
  decisionStale,
  decisionSubmitFailed,
  failureEntered,
  implementationAccepted,
  implementationRejected,
  retryInitiated,
  retryRecorded,
  specApproved,
  specRejected,
  workflowTakenOver,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';

import {
  ARTIFACT_UNAVAILABLE_REASON,
  assertNeverMode,
  mapDisabledReason,
  pendingClarificationsMessage,
  resolveApprovalBarState,
  resolveConsequenceHint,
  type ApprovalDecisionView,
  type ApprovalLocalUi,
  type ApprovalMutationState,
  type DecisionSummary,
  type DeveloperTaggedFeedback,
  type ImplementationRejectionDraft,
  type RejectionDraft,
  type TaggedFeedback,
} from '../approvalDecisionView';
import { parsePrReference, prUrl } from '../githubRef';
import { StateSignifierChip } from './WorkflowStateBadge';

/** The UPPERCASE rework taxonomy (story 2.10 / T-TAGGED-UPPERCASE) + human labels. */
const TAGGED_FEEDBACK_OPTIONS: ReadonlyArray<{ value: TaggedFeedback; label: string }> = [
  { value: 'MISSING_SCOPE', label: 'Missing scope' },
  { value: 'UNCLEAR_SPECIFICATION', label: 'Unclear specification' },
  { value: 'MISUNDERSTOOD_IMPLEMENTATION', label: 'Misunderstood implementation' },
];

/**
 * Story 3.28 (R5) — the DEVELOPER rejection taxonomy (5 values) + human labels. DISTINCT
 * from {@link TAGGED_FEEDBACK_OPTIONS} (the product/spec set) — the dialog renders these in
 * `implementation_review` mode. Wire values bind by Jackson enum NAME (UPPERCASE).
 */
const DEVELOPER_TAGGED_FEEDBACK_OPTIONS: ReadonlyArray<{
  value: DeveloperTaggedFeedback;
  label: string;
}> = [
  { value: 'INCORRECT_APPROACH', label: 'Incorrect approach' },
  { value: 'INCOMPLETE_IMPLEMENTATION', label: 'Incomplete implementation' },
  { value: 'QUALITY_ISSUE', label: 'Quality issue' },
  { value: 'BREAKS_EXISTING_FUNCTIONALITY', label: 'Breaks existing functionality' },
  { value: 'OUT_OF_SCOPE', label: 'Out of scope' },
];

export interface ApprovalDecisionBarProps {
  /** The resolved decision view (from the container's mapped live read / fixtures). */
  view: ApprovalDecisionView;
  /** The live mutation state (approve/reject) — drives submitting/success/error/stale. */
  mutation: ApprovalMutationState;
  /** Local UI overlay (prior-decision lock, UI-side stale) the container controls. */
  localUi?: ApprovalLocalUi | undefined;
  /** The live allowed-actions read failed — render a distinct load-error/retry surface. */
  loadError?: boolean | undefined;
  /** Fire the approve mutation (the container wires this to `useApproveSpec`). */
  onApprove: () => void;
  /** Confirm a rejection with the captured draft (wired to `useRejectSpec`). */
  onReject: (draft: RejectionDraft) => void;
  /** Refresh allowed-actions after a stale/version-mismatch (the refetch CTA). */
  onRefresh: () => void;
  /**
   * Story 3.30 — fire the retry mutation (the recovery container wires this to
   * `useRetryWorkflow`). Only the `recovery_operator` mode invokes it; `spec_approval`
   * passes none.
   */
  onRetry?: (() => void) | undefined;
  /**
   * Story 3.28 — fire the accept-implementation mutation (the impl-review container wires
   * this to `useAcceptImplementation`). Only `implementation_review` invokes it.
   */
  onAccept?: (() => void) | undefined;
  /** Story 3.28 — confirm a developer rejection (wired to `useRejectImplementation`). */
  onRejectImplementation?: ((draft: ImplementationRejectionDraft) => void) | undefined;
  /** Story 3.28 — confirm a takeover with the captured reason (wired to `useTakeoverWorkflow`). */
  onTakeover?: ((reasonText: string) => void) | undefined;
}

/** Layout classes per AC4 — sticky footer vs in-flow inline section. */
const LAYOUT_CLASS: Record<ApprovalDecisionView['layout'], string> = {
  sticky_footer:
    'sticky bottom-0 z-10 w-full border-t border-border bg-surface/95 px-4 py-3 backdrop-blur supports-[backdrop-filter]:bg-surface/80',
  inline_section: 'w-full rounded-md border border-border bg-surface px-4 py-3',
};

/** Decision-outcome presentation (chip tone + label) per decision kind (AC9 / story 3.28). */
const DECISION_PRESENTATION: Record<
  DecisionSummary['decision'],
  { stateName: 'success' | 'stale' | 'recovery'; label: string }
> = {
  approved: { stateName: 'success', label: 'Approved' },
  accepted: { stateName: 'success', label: 'Accepted' },
  rejected: { stateName: 'stale', label: 'Rejected' },
  takenover: { stateName: 'recovery', label: 'Taken over' },
};

/**
 * Story 3.28 (AC7 / R9) — the post-takeover affordance: a "Run is taken over" read-only
 * label + (when a PR reference was preserved) a "Continue work in PR {ref}" GitHub link
 * (new tab, hardened via `githubRef.ts`). A null/malformed reference renders the label
 * WITHOUT a link (no empty placeholder).
 */
function TakeoverPrAffordance({ summary }: { summary: DecisionSummary }) {
  const reference = summary.preservedPrReference;
  const parsed =
    reference !== undefined && reference.trim() !== '' ? parsePrReference(reference) : null;
  return (
    <div className="flex flex-col gap-1" data-testid="takeover-pr-affordance">
      <StateSignifierChip
        stateName="recovery"
        label="Run is taken over"
        testId="takeover-readonly-label"
      />
      {parsed !== null ? (
        <a
          href={prUrl(parsed)}
          target="_blank"
          rel="noopener noreferrer"
          data-testid="takeover-continue-pr"
          className="text-meta text-brand-600 underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
        >
          Continue work in PR {reference}
        </a>
      ) : null}
    </div>
  );
}

/** The persisted post-submit / prior-decision outcome (AC9) — never auto-clears. */
function DecisionSummaryView({ summary, locked }: { summary: DecisionSummary; locked: boolean }) {
  const presentation = DECISION_PRESENTATION[summary.decision];
  return (
    <div className="flex flex-col gap-1" data-testid="approval-decision-summary">
      <StateSignifierChip
        stateName={presentation.stateName}
        label={presentation.label}
        testId="approval-summary-chip"
      />
      <p className="text-meta text-text-secondary">
        {presentation.label} · <time dateTime={summary.decidedAt}>{summary.decidedAt}</time>
        {' · '}resulting state <code>{summary.resultingState}</code>
        {summary.correlationId !== undefined ? (
          <>
            {' · '}ref <code data-testid="approval-summary-ref">{summary.correlationId}</code>
          </>
        ) : null}
      </p>
      {summary.actor !== undefined ? (
        <div className="text-meta text-text-tertiary" data-testid="approval-summary-actor">
          {/* Actor identity may be echoed from agent output — sanitize (T-untrusted). */}
          <SafeMarkdownRenderer source={`by ${summary.actor}`} />
        </div>
      ) : null}
      {summary.decision === 'takenover' ? <TakeoverPrAffordance summary={summary} /> : null}
      {locked ? (
        <p className="text-meta text-text-tertiary">This decision is recorded and read-only.</p>
      ) : null}
    </div>
  );
}

/**
 * The region-local rejection rationale dialog (AC8) — NOT 2.23's primitives (T-MODAL).
 * Generic over the taxonomy value so it serves BOTH the spec ({@link TAGGED_FEEDBACK_OPTIONS})
 * and the story-3.28 developer ({@link DEVELOPER_TAGGED_FEEDBACK_OPTIONS}) sets without a
 * second component (the `options` prop supplies the radio set + value type).
 */
function RejectionDialog<TFeedback extends string>({
  labelledById,
  options,
  onCancel,
  onConfirm,
}: {
  labelledById: string;
  options: ReadonlyArray<{ value: TFeedback; label: string }>;
  onCancel: () => void;
  onConfirm: (draft: { reasonText: string; taggedFeedback: TFeedback }) => void;
}) {
  const [reasonText, setReasonText] = useState('');
  const [taggedFeedback, setTaggedFeedback] = useState<TFeedback | ''>('');
  const [validationError, setValidationError] = useState<string | undefined>(undefined);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // AC10 — focus moves into the dialog on open (the first field).
  useEffect(() => {
    textareaRef.current?.focus();
  }, []);

  const handleConfirm = () => {
    if (reasonText.trim() === '' || taggedFeedback === '') {
      setValidationError('Add a reason and select the kind of rework needed before rejecting.');
      return;
    }
    onConfirm({ reasonText, taggedFeedback });
  };

  return (
    // T-MODAL — region-local modal. Cannot be dismissed except via explicit Cancel:
    // no backdrop-click / Escape close handlers are wired (AC8).
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby={labelledById}
      data-testid="approval-rejection-dialog"
      className="mt-3 flex flex-col gap-3 rounded-md border border-border bg-surface-elevated p-3"
    >
      <h3 id={labelledById} className="text-meta font-medium text-text-primary">
        Reject with feedback
      </h3>
      <label className="flex flex-col gap-1 text-meta text-text-secondary">
        <span>Reason</span>
        <textarea
          ref={textareaRef}
          value={reasonText}
          onChange={(event) => setReasonText(event.target.value)}
          rows={3}
          className="rounded-md border border-input bg-background p-2 text-sm text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid="approval-rejection-reason"
        />
      </label>
      <fieldset className="flex flex-col gap-1 text-meta text-text-secondary">
        <legend className="mb-1">Kind of rework needed</legend>
        {options.map((option) => (
          <label key={option.value} className="flex items-center gap-2">
            <input
              type="radio"
              name={`${labelledById}-feedback`}
              value={option.value}
              checked={taggedFeedback === option.value}
              onChange={() => setTaggedFeedback(option.value)}
            />
            <span>{option.label}</span>
          </label>
        ))}
      </fieldset>
      {validationError !== undefined ? (
        <p role="alert" className="text-meta text-state-error-foreground">
          {validationError}
        </p>
      ) : null}
      <div className="flex items-center gap-2">
        <Button type="button" variant="default" onClick={handleConfirm}>
          Confirm rejection
        </Button>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

export function ApprovalDecisionBar({
  view,
  mutation,
  localUi,
  loadError,
  onApprove,
  onReject,
  onRefresh,
  onRetry,
  onAccept,
  onRejectImplementation,
  onTakeover,
}: ApprovalDecisionBarProps) {
  // A failed allowed-actions read is a distinct error from a missing artifact (blocked)
  // or a mutation failure — surface it as `error` with a load-specific message + retry,
  // never the benign "not yet available" blocked text. Every mode is now a real decision
  // path (spec_approval / recovery_operator / implementation_review), so a load error
  // applies to all of them — a failed read must never masquerade as "no decision available".
  const showLoadError = loadError === true;
  const state = showLoadError ? 'error' : resolveApprovalBarState(view, mutation, localUi);
  const idBase = useId();
  const consequenceId = `${idBase}-approve-consequence`;
  const rejectReasonId = `${idBase}-reject-reason`;
  const dialogTitleId = `${idBase}-reject-dialog`;
  // Story 3.28 — distinct ids for the implementation-review action area.
  const implRejectDialogTitleId = `${idBase}-impl-reject-dialog`;
  const acceptConsequenceId = `${idBase}-accept-consequence`;

  const [dialogOpen, setDialogOpen] = useState(false);
  const rejectTriggerRef = useRef<HTMLButtonElement>(null);
  // Story 3.30 — the retry confirm-before overlay (the shared 2.23 `ConfirmationDialog`,
  // NOT the region-local rejection dialog). `ConfirmationDialog` owns focus restoration.
  const [retryConfirmOpen, setRetryConfirmOpen] = useState(false);
  // Story 3.28 — the implementation-review dialogs: the developer rejection rationale
  // (region-local) + the takeover confirm-before overlay (shared `ConfirmationDialog`).
  const [implRejectDialogOpen, setImplRejectDialogOpen] = useState(false);
  const [takeoverConfirmOpen, setTakeoverConfirmOpen] = useState(false);
  const [takeoverReason, setTakeoverReason] = useState('');
  const implRejectTriggerRef = useRef<HTMLButtonElement>(null);

  const closeDialog = () => {
    setDialogOpen(false);
    // AC10 — restore focus to the trigger on close.
    rejectTriggerRef.current?.focus();
  };

  const confirmRejection = (draft: RejectionDraft) => {
    setDialogOpen(false);
    onReject(draft);
    rejectTriggerRef.current?.focus();
  };

  const closeImplRejectDialog = () => {
    setImplRejectDialogOpen(false);
    implRejectTriggerRef.current?.focus();
  };

  const confirmImplRejection = (draft: ImplementationRejectionDraft) => {
    setImplRejectDialogOpen(false);
    onRejectImplementation?.(draft);
    implRejectTriggerRef.current?.focus();
  };

  // AC10 / story 2.25 AC5+AC7 — announce decision-lifecycle transitions through a
  // single polite live region, sourcing every string from the shared vocabulary.
  // Success now announces the recorded outcome (the 2.19 decision-outcome gap),
  // not only the visual summary.
  const announcementText =
    view.mode === 'recovery_operator'
      ? // Story 3.30 (AC7) — the recovery lifecycle is announced through this SAME
        // single live region (no duplicate failure-entry announcement elsewhere).
        state === 'submitting'
        ? retryInitiated
        : state === 'success'
          ? retryRecorded
          : state === 'error'
            ? decisionSubmitFailed
            : view.currentState === 'Failed'
              ? failureEntered
              : ''
      : view.mode === 'implementation_review'
        ? // Story 3.28 (AC8) — the implementation-review lifecycle through the same region.
          showLoadError
          ? decisionOptionsLoadFailed
          : state === 'stale'
            ? decisionStale(
                'This view is out of date.',
                'Refresh to review the latest version before deciding.',
              )
            : state === 'error'
              ? decisionSubmitFailed
              : state === 'success'
                ? view.lastDecision?.decision === 'accepted'
                  ? implementationAccepted
                  : view.lastDecision?.decision === 'rejected'
                    ? implementationRejected
                    : view.lastDecision?.decision === 'takenover'
                      ? workflowTakenOver
                      : decisionRecorded
                : ''
        : showLoadError
          ? decisionOptionsLoadFailed
          : state === 'stale'
            ? decisionStale(
                'This view is out of date.',
                'Refresh to review the latest version before deciding.',
              )
            : state === 'error'
              ? decisionSubmitFailed
              : state === 'success'
                ? view.lastDecision !== undefined
                  ? view.lastDecision.decision === 'approved'
                    ? specApproved
                    : specRejected
                  : // Decision settled but the resolved outcome isn't repopulated yet —
                    // announce the generic recorded message rather than going silent.
                    decisionRecorded
                : '';
  // Deferred so a mount-time error/success (present at first render) is still
  // announced as a change, not swallowed as the region's initial content (AC5).
  const announcement = useLiveAnnouncement(announcementText);

  function renderSpecApproval() {
    switch (state) {
      case 'locked':
        return view.lastDecision !== undefined ? (
          <DecisionSummaryView summary={view.lastDecision} locked />
        ) : (
          <p className="text-meta text-text-tertiary">A decision has already been made.</p>
        );
      case 'success':
        return view.lastDecision !== undefined ? (
          <div className="flex flex-col gap-1">
            <DecisionSummaryView summary={view.lastDecision} locked={false} />
            <p className="text-meta text-text-secondary">Decision recorded.</p>
          </div>
        ) : (
          <p className="text-meta text-text-secondary">Decision recorded.</p>
        );
      case 'submitting':
        return (
          <span
            role="status"
            aria-busy="true"
            className="text-meta text-text-secondary"
            data-testid="approval-submitting"
          >
            Submitting your decision…
          </span>
        );
      case 'error':
        return (
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="Your decision wasn't submitted"
            message={`The decision could not be submitted${
              mutation.errorCode !== undefined ? ` (${mutation.errorCode})` : ''
            }. Refresh and try again.`}
            nextAction={{ kind: 'Refresh', onRefresh }}
          />
        );
      case 'stale': {
        const latestSpecVersion = view.versionStamp?.currentSpecArtifactVersion;
        const versionNote =
          typeof latestSpecVersion === 'number'
            ? ` It is now at version ${latestSpecVersion}.`
            : '';
        return (
          <div className="flex flex-col gap-2" data-testid="approval-stale">
            <StateSignifierChip
              stateName="stale"
              label="Out of date"
              testId="approval-stale-chip"
            />
            <p className="text-meta text-text-secondary">
              The specification changed since this view loaded.{versionNote} Review the new version
              before approving.
            </p>
            <div>
              <Button type="button" variant="outline" onClick={onRefresh}>
                Refresh and review
              </Button>
            </div>
          </div>
        );
      }
      case 'blocked':
        return renderBlocked();
      case 'disabled':
      case 'ready':
        return renderReady();
      default:
        return null;
    }
  }

  function renderBlocked() {
    const reason =
      view.pendingClarifications !== undefined && view.pendingClarifications > 0
        ? pendingClarificationsMessage(view.pendingClarifications)
        : view.artifactId === undefined
          ? ARTIFACT_UNAVAILABLE_REASON
          : view.disabledReasons?.approve_spec !== undefined
            ? mapDisabledReason(view.disabledReasons.approve_spec)
            : mapDisabledReason(undefined);
    return (
      <div className="flex flex-col gap-2" data-testid="approval-blocked">
        <StateSignifierChip
          stateName="blocker"
          label="No decision available"
          testId="approval-blocked-chip"
        />
        {/* NEVER a bare disabled control — the blocked reason is always explained (AC3/AC5c). */}
        <p className="text-meta text-text-secondary" data-testid="approval-blocked-reason">
          {reason}
        </p>
      </div>
    );
  }

  function renderReady() {
    const hasApprove = view.actions.includes('approve_spec');
    const rejectAvailable = view.actions.includes('reject_spec');
    const rejectReasonCode = view.disabledReasons?.reject_spec;
    const consequence = resolveConsequenceHint('spec_approval', 'approve_spec');
    return (
      <div className="flex flex-col gap-2" data-testid="approval-action-area">
        <div className="flex flex-wrap items-center gap-2">
          {/* AC5b — Approve is HIDDEN (not disabled) when `approve_spec` is absent. AC7 —
              exactly one primary-styled control (`data-primary`). */}
          {hasApprove ? (
            <Button
              type="button"
              variant="default"
              data-primary="true"
              aria-describedby={consequence !== undefined ? consequenceId : undefined}
              onClick={onApprove}
            >
              Approve specification
            </Button>
          ) : null}
          {/* Reject is the visually subordinate secondary (AC2). When withheld with a
              reason it renders DISABLED with an `aria-describedby` link (AC5c/AC10). */}
          {rejectAvailable && rejectReasonCode === undefined ? (
            <Button
              ref={rejectTriggerRef}
              type="button"
              variant="outline"
              onClick={() => setDialogOpen(true)}
            >
              Reject with feedback
            </Button>
          ) : null}
          {rejectAvailable && rejectReasonCode !== undefined ? (
            <Button type="button" variant="outline" disabled aria-describedby={rejectReasonId}>
              Reject with feedback
            </Button>
          ) : null}
        </div>
        {hasApprove && consequence !== undefined ? (
          <p id={consequenceId} className="text-meta text-text-tertiary">
            {consequence}
          </p>
        ) : null}
        {rejectAvailable && rejectReasonCode !== undefined ? (
          <p id={rejectReasonId} className="text-meta text-text-tertiary">
            {mapDisabledReason(rejectReasonCode)}
          </p>
        ) : null}
        {dialogOpen ? (
          <RejectionDialog
            labelledById={dialogTitleId}
            options={TAGGED_FEEDBACK_OPTIONS}
            onCancel={closeDialog}
            onConfirm={confirmRejection}
          />
        ) : null}
      </div>
    );
  }

  /**
   * Story 3.30 (AC3, AC5) — the REAL `recovery_operator` render (replaces the E4
   * stub). Scope discipline: the ONLY affirmative action is `Retry failed step`; there
   * is NO reconcile / resume / rerun-from-step control. When retry is not safe the bar
   * renders `View only` — explicitly NOT a primary CTA (single-primary-action rule).
   */
  function renderRecoveryOperator() {
    const consequence = resolveConsequenceHint('recovery_operator', 'retry');
    switch (state) {
      case 'success':
        return (
          <div className="flex flex-col gap-1" data-testid="recovery-retry-success">
            <StateSignifierChip
              stateName="recovery"
              label="Retry submitted"
              testId="recovery-retry-chip"
            />
            <p className="text-meta text-text-secondary">
              Retry recorded. The previous failure is preserved in the timeline.
            </p>
          </div>
        );
      case 'submitting':
        return (
          <DecisionArea
            ariaLabel="Recovery actions"
            primary={
              <GovernedButton priority="primary" workflowState="submitting" testId="recovery-retry">
                Retry failed step
              </GovernedButton>
            }
          />
        );
      case 'error':
        return (
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="Your retry wasn't submitted"
            message={`The retry could not be submitted${
              mutation.errorCode !== undefined ? ` (${mutation.errorCode})` : ''
            }. Refresh and try again.`}
            nextAction={{ kind: 'Refresh', onRefresh }}
          />
        );
      case 'ready':
        return (
          <div className="flex flex-col gap-2" data-testid="recovery-action-area">
            <DecisionArea
              ariaLabel="Recovery actions"
              primary={
                <GovernedButton
                  priority="primary"
                  type="button"
                  testId="recovery-retry"
                  aria-describedby={consequence !== undefined ? consequenceId : undefined}
                  onClick={() => setRetryConfirmOpen(true)}
                >
                  Retry failed step
                </GovernedButton>
              }
            />
            {consequence !== undefined ? (
              <p id={consequenceId} className="text-meta text-text-tertiary">
                {consequence}
              </p>
            ) : null}
            <ConfirmationDialog
              open={retryConfirmOpen}
              onOpenChange={setRetryConfirmOpen}
              intent={CONFIRMATION_CATALOG.retryOrRecoverConsequential.intent}
              title="Retry failed step"
              consequence={CONFIRMATION_CATALOG.retryOrRecoverConsequential.consequenceTemplate}
              confirmLabel="Confirm retry"
              cancelLabel="Cancel"
              isConfirming={mutation.status === 'pending'}
              onConfirm={() => {
                setRetryConfirmOpen(false);
                onRetry?.();
              }}
            />
          </div>
        );
      case 'disabled':
      default:
        // `View only` — retry is not a safe action in the current state (AC5). NOT a
        // primary CTA; a plain explained, non-interactive marker.
        return (
          <div className="flex flex-col gap-1" data-testid="recovery-view-only">
            <StateSignifierChip
              stateName="informational"
              label="View only"
              testId="recovery-view-only-chip"
            />
            <p className="text-meta text-text-secondary">
              No recovery action is available for this run right now.
            </p>
          </div>
        );
    }
  }

  /**
   * Story 3.28 (AC1, AC2, AC6, AC7, AC8) — the `implementation_review` action area.
   * `accept_implementation` is the single visually-primary control (`data-primary`);
   * `Reject with feedback` + `Take over` are subordinate secondaries. `Take over` is
   * ALWAYS available when in the allowed-actions (it needs no artifact/version), so it
   * renders even when accept/reject are `blocked` (`acceptReady === false`).
   */
  function renderImplementationActions({ acceptReady }: { acceptReady: boolean }) {
    const hasAccept = acceptReady && view.actions.includes('accept_implementation');
    const hasReject = acceptReady && view.actions.includes('reject_implementation');
    const takeoverAvailable = view.actions.includes('takeover_workflow');
    const acceptConsequence = resolveConsequenceHint(
      'implementation_review',
      'accept_implementation',
    );
    return (
      <div className="flex flex-col gap-2" data-testid="impl-review-action-area">
        <div className="flex flex-wrap items-center gap-2">
          {/* AC2 — exactly one primary-styled control (`data-primary`). Accept is HIDDEN
              (not disabled) when it cannot fire (no implementation artifact / versions). */}
          {hasAccept ? (
            <Button
              type="button"
              variant="default"
              data-primary="true"
              disabled={mutation.status === 'pending'}
              aria-describedby={acceptConsequence !== undefined ? acceptConsequenceId : undefined}
              onClick={onAccept}
            >
              Accept implementation
            </Button>
          ) : null}
          {hasReject ? (
            <Button
              ref={implRejectTriggerRef}
              type="button"
              variant="outline"
              onClick={() => setImplRejectDialogOpen(true)}
            >
              Reject with feedback
            </Button>
          ) : null}
          {takeoverAvailable ? (
            <Button type="button" variant="outline" onClick={() => setTakeoverConfirmOpen(true)}>
              Take over
            </Button>
          ) : null}
        </div>
        {hasAccept && acceptConsequence !== undefined ? (
          <p id={acceptConsequenceId} className="text-meta text-text-tertiary">
            {acceptConsequence}
          </p>
        ) : null}
        {!acceptReady ? (
          // NEVER a bare disabled control — explain why accept/reject are unavailable while
          // keeping takeover (which needs no artifact) actionable above.
          <p className="text-meta text-text-secondary" data-testid="impl-review-blocked-reason">
            The implementation is not yet available for an accept or reject decision.
          </p>
        ) : null}
        {implRejectDialogOpen ? (
          <RejectionDialog
            labelledById={implRejectDialogTitleId}
            options={DEVELOPER_TAGGED_FEEDBACK_OPTIONS}
            onCancel={closeImplRejectDialog}
            onConfirm={confirmImplRejection}
          />
        ) : null}
        {takeoverAvailable ? (
          // AC4 — the shared confirm-before overlay with the VERBATIM OpenAPI consequence
          // text + a REQUIRED reasonText (confirm gated until non-blank), intent=danger.
          <ConfirmationDialog
            open={takeoverConfirmOpen}
            onOpenChange={(open) => {
              setTakeoverConfirmOpen(open);
              if (!open) {
                setTakeoverReason('');
              }
            }}
            intent={CONFIRMATION_CATALOG.takeoverWorkflow.intent}
            title="Take over run"
            consequence={CONFIRMATION_CATALOG.takeoverWorkflow.consequenceTemplate}
            confirmLabel="Confirm takeover"
            cancelLabel="Cancel"
            isConfirming={mutation.status === 'pending'}
            confirmDisabled={takeoverReason.trim() === '' || mutation.status === 'pending'}
            onConfirm={() => {
              const reason = takeoverReason.trim();
              setTakeoverConfirmOpen(false);
              setTakeoverReason('');
              onTakeover?.(reason);
            }}
          >
            <label className="flex flex-col gap-1 text-meta text-text-secondary">
              <span>Reason (required)</span>
              <textarea
                value={takeoverReason}
                onChange={(event) => setTakeoverReason(event.target.value)}
                rows={3}
                className="rounded-md border border-input bg-background p-2 text-sm text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="takeover-reason"
              />
            </label>
          </ConfirmationDialog>
        ) : null}
      </div>
    );
  }

  /**
   * Story 3.28 — the REAL `implementation_review` render (replaces the E3 stub). The
   * shared state machine drives `locked`/`success`/`submitting`/`error`/`stale`; the
   * action area (`ready`/`blocked`) renders accept primary + reject + takeover secondaries,
   * keeping `Take over` available even when accept/reject are blocked.
   */
  function renderImplementationReview() {
    switch (state) {
      case 'locked':
        return view.lastDecision !== undefined ? (
          <DecisionSummaryView summary={view.lastDecision} locked />
        ) : (
          <p className="text-meta text-text-tertiary">A decision has already been made.</p>
        );
      case 'success':
        return view.lastDecision !== undefined ? (
          <div className="flex flex-col gap-1">
            <DecisionSummaryView summary={view.lastDecision} locked={false} />
            <p className="text-meta text-text-secondary">Decision recorded.</p>
          </div>
        ) : (
          <p className="text-meta text-text-secondary">Decision recorded.</p>
        );
      case 'submitting':
        return (
          <span
            role="status"
            aria-busy="true"
            className="text-meta text-text-secondary"
            data-testid="approval-submitting"
          >
            Submitting your decision…
          </span>
        );
      case 'error':
        return (
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="Your decision wasn't submitted"
            message={`The decision could not be submitted${
              mutation.errorCode !== undefined ? ` (${mutation.errorCode})` : ''
            }. Refresh and try again.`}
            nextAction={{ kind: 'Refresh', onRefresh }}
          />
        );
      case 'stale': {
        // Only accept/reject can be stale (takeover sends no versions — AC5/R2). No version
        // number is surfaced here: the impl-review decision targets the IMPLEMENTATION
        // artifact, so `currentSpecArtifactVersion` would be the WRONG number (3.28 review P2).
        return (
          <div className="flex flex-col gap-2" data-testid="approval-stale">
            <StateSignifierChip
              stateName="stale"
              label="Out of date"
              testId="approval-stale-chip"
            />
            <p className="text-meta text-text-secondary">
              The run changed since this view loaded. Review the latest version before deciding.
            </p>
            <div>
              <Button type="button" variant="outline" onClick={onRefresh}>
                Refresh and review
              </Button>
            </div>
          </div>
        );
      }
      case 'blocked':
        return renderImplementationActions({ acceptReady: false });
      case 'disabled':
      case 'ready':
        return renderImplementationActions({ acceptReady: true });
      default:
        return null;
    }
  }

  function renderBody() {
    if (showLoadError) {
      return (
        <div data-testid="approval-load-error">
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="We couldn't load the decision options"
            message="The available actions for this run failed to load. Refresh to try again."
            nextAction={{ kind: 'Refresh', onRefresh }}
          />
        </div>
      );
    }
    switch (view.mode) {
      case 'spec_approval':
        return renderSpecApproval();
      case 'implementation_review':
        return renderImplementationReview();
      case 'recovery_operator':
        return renderRecoveryOperator();
      default:
        return assertNeverMode(view.mode);
    }
  }

  return (
    <section
      aria-label="Approval decision bar"
      data-testid="approval-decision-bar"
      data-approval-bar-state={state}
      data-approval-bar-mode={view.mode}
      data-approval-bar-layout={view.layout}
      className={cn('flex flex-col gap-2', LAYOUT_CLASS[view.layout])}
    >
      <div className="text-meta text-text-secondary" data-testid="approval-decision-context">
        {/* Decision context may carry an echoed actor identity — sanitize via the barrel. */}
        <SafeMarkdownRenderer source={view.decisionContextLabel} />
      </div>
      {renderBody()}
      <div aria-live="polite" className="sr-only" data-testid="approval-live-region">
        {announcement}
      </div>
    </section>
  );
}
