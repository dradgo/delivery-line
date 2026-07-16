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
import { TriangleAlert } from 'lucide-react';

import { ErrorState } from '@/components/feedback';
import { SafeMarkdownRenderer } from '@/lib/sanitization';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { DecisionArea, GovernedButton } from '@/components/actions';
import { ConfirmationDialog } from '@/components/overlays/ConfirmationDialog';
import { RationaleCaptureDialog } from '@/components/overlays/RationaleCaptureDialog';
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
  recoveryPauseInitiated,
  recoveryPauseRecorded,
  recoveryRerunInitiated,
  recoveryRerunRecorded,
  recoveryResumeInitiated,
  recoveryResumeRecorded,
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
  resolveRecoveryPrimaryAction,
  RECOVERY_OPERATOR_ACTIONS,
  type ApprovalDecisionView,
  type ApprovalLocalUi,
  type ApprovalMutationState,
  type DecisionAction,
  type DecisionSummary,
  type DeveloperTaggedFeedback,
  type ImplementationRejectionDraft,
  type RecoverySafetyLevel,
  type RejectionDraft,
  type TaggedFeedback,
} from '../approvalDecisionView';
import { safetyTone } from '../failureDiagnosticsView';
import { parsePrReference, prUrl } from '../githubRef';
import type { RerunTargetStep } from '../hooks/useRerunFromStep';
import type { PreviewRerunFromStepResponse } from '../hooks/usePreviewRerunFromStep';
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
  /**
   * Story 4.22 — the per-token recovery safety ranks (from `resolveRecoverySafety`), driving the
   * single-primary selection (AC2) + each subordinate's non-color-only affix. Only the
   * `recovery_operator` mode reads it.
   */
  recoverySafetyByToken?: Partial<Record<DecisionAction, RecoverySafetyLevel>> | undefined;
  /** Story 4.22 (AC3) — confirm a resume (wired to `useResumeWorkflow`). */
  onResume?: (() => void) | undefined;
  /** Story 4.22 (AC5) — confirm a rerun-from-step with the captured step + reason. */
  onRerunFromStep?:
    | ((vars: { targetStep: RerunTargetStep; reasonText: string }) => void)
    | undefined;
  /** Story 4.22 (AC6) — confirm a pause with the captured reason (wired to `usePauseWorkflow`). */
  onPause?: ((reasonText: string) => void) | undefined;
  /**
   * Story 4.22 (AC4) — the reconcile entry-point seam (the actual mutation + dialog is owned by
   * story 4.23). When omitted the gated button renders DISABLED + explained (AC11 / OQ-2), never
   * silently gone.
   */
  onReconcile?: (() => void) | undefined;
  /**
   * Story 4.23 — the reconcile seam is wired but its `conflictId` is resolved asynchronously from the
   * run's conflict list. While that resolve is pending (or the list came back empty), the container
   * leaves `onReconcile` undefined AND sets this flag so the disabled button explains it is preparing
   * (rather than the seam-missing "upcoming increment" copy). Prevents an enabled-but-dead click.
   */
  reconcileResolving?: boolean | undefined;
  /**
   * Story 4.22 (AC7) — the classify-failure entry-point seam (owned by story 4.24). When omitted
   * the gated button renders DISABLED + explained (AC11 / OQ-2).
   */
  onClassifyFailure?: (() => void) | undefined;
  /**
   * Story 4.22 (AC5) — the live non-mutating rerun preview for the currently-selected step, fed by
   * the container's `usePreviewRerunFromStep`. Rendered inside the rerun dialog's "Show what will be
   * superseded" section.
   */
  rerunPreview?:
    | { data?: PreviewRerunFromStepResponse | undefined; isLoading?: boolean; isError?: boolean }
    | undefined;
  /**
   * Story 4.22 (AC5) — notify the container of the rerun dialog's open state + selected step so it
   * can enable/param the preview query (the container owns the hook; the bar owns the select).
   */
  onRerunPreviewRequest?:
    | ((args: { open: boolean; targetStep: RerunTargetStep }) => void)
    | undefined;
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
  // Story 4.22 (AC9) — the recovery_operator post-submit outcomes.
  resumed: { stateName: 'recovery', label: 'Resumed' },
  reranFromStep: { stateName: 'recovery', label: 'Rerun submitted' },
  paused: { stateName: 'recovery', label: 'Paused' },
};

/**
 * Story 4.22 — the recovery_operator action descriptors: explicit verb label (AC10) + how the
 * control behaves (`owned` opens a dialog this story fully owns; `seam` is an entry point delegating
 * to stories 4.23 / 4.24). Iterated in {@link RECOVERY_OPERATOR_ACTIONS} display order.
 */
const RECOVERY_ACTION_LABELS: Record<
  | 'retry'
  | 'resume_workflow'
  | 'reconcile_conflict'
  | 'rerun_from_step'
  | 'pause_workflow'
  | 'classify_failure',
  string
> = {
  retry: 'Retry failed step',
  resume_workflow: 'Resume run',
  reconcile_conflict: 'Reconcile conflict',
  rerun_from_step: 'Rerun from step',
  pause_workflow: 'Pause run',
  classify_failure: 'Classify failure',
};

/** Story 4.22 (AC2) — the non-color-only safety affix text for a subordinate action's rank. */
const RECOVERY_SAFETY_AFFIX: Record<RecoverySafetyLevel, string> = {
  safe: 'Safe',
  caution: 'Caution',
  risky: 'Higher risk',
};

/**
 * Story 4.22 (AC5) — the rerun `RationaleCaptureDialog` fields. MODULE-level (stable reference) so
 * the dialog's `useEffect([open, fields])` reset does NOT re-fire on every parent render and wipe
 * the reason mid-type. The `targetStep` select is NOT a field here — it is bar-owned (in the dialog
 * `children`) so the preview query can react to the selection.
 */
const RERUN_RATIONALE_FIELDS = [
  {
    name: 'reasonText',
    label: 'Reason',
    type: 'textarea',
    required: true,
    placeholder: 'Why are you rerunning from this step?',
  },
] as const;

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
  recoverySafetyByToken,
  onResume,
  onRerunFromStep,
  onPause,
  onReconcile,
  reconcileResolving,
  onClassifyFailure,
  rerunPreview,
  onRerunPreviewRequest,
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
  // Story 4.22 — the rerun dialog's step-select label association.
  const rerunStepId = `${idBase}-rerun-step`;

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
  // Story 4.22 — the deeper recovery dialogs + the in-flight action (so the single live region
  // announces the RIGHT action lifecycle). resume/pause use the shared `ConfirmationDialog`; rerun
  // uses `RationaleCaptureDialog` (reasonText field) with a bar-owned `targetStep` select so the
  // preview can react to the selection.
  const [resumeConfirmOpen, setResumeConfirmOpen] = useState(false);
  const [pauseConfirmOpen, setPauseConfirmOpen] = useState(false);
  const [pauseReason, setPauseReason] = useState('');
  const [rerunDialogOpen, setRerunDialogOpen] = useState(false);
  const [rerunStep, setRerunStep] = useState<RerunTargetStep>('investigating');
  const [pendingRecoveryAction, setPendingRecoveryAction] = useState<DecisionAction | null>(null);

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

  // Story 4.22 (AC5) — the container owns the preview query but the bar owns the dialog's open state
  // + step select; notify the container on every open/step change so it enables/params the fetch.
  const notifyRerunPreview = (open: boolean, targetStep: RerunTargetStep) => {
    onRerunPreviewRequest?.({ open, targetStep });
  };
  const openRerunDialog = () => {
    setRerunDialogOpen(true);
    notifyRerunPreview(true, rerunStep);
  };
  const changeRerunStep = (step: RerunTargetStep) => {
    setRerunStep(step);
    notifyRerunPreview(true, step);
  };
  const handleRerunOpenChange = (open: boolean) => {
    setRerunDialogOpen(open);
    notifyRerunPreview(open, rerunStep);
  };

  // AC10 / story 2.25 AC5+AC7 — announce decision-lifecycle transitions through a
  // single polite live region, sourcing every string from the shared vocabulary.
  // Success now announces the recorded outcome (the 2.19 decision-outcome gap),
  // not only the visual summary.
  const announcementText =
    view.mode === 'recovery_operator'
      ? // Story 3.30 + 4.22 (AC7 / AC10) — the recovery lifecycle is announced through this SAME
        // single live region (no duplicate failure-entry announcement elsewhere). The specific
        // action is resolved from the in-flight action (submitting) / the landed outcome (success)
        // so a pause/resume/rerun is not mis-announced as a retry.
        state === 'submitting'
        ? pendingRecoveryAction === 'resume_workflow'
          ? recoveryResumeInitiated
          : pendingRecoveryAction === 'rerun_from_step'
            ? recoveryRerunInitiated
            : pendingRecoveryAction === 'pause_workflow'
              ? recoveryPauseInitiated
              : retryInitiated
        : state === 'success'
          ? view.lastDecision?.decision === 'resumed'
            ? recoveryResumeRecorded
            : view.lastDecision?.decision === 'reranFromStep'
              ? recoveryRerunRecorded
              : view.lastDecision?.decision === 'paused'
                ? recoveryPauseRecorded
                : retryRecorded
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

  /** Story 4.22 — a recovery seam action (reconcile/classify) is only invokable when its handler is
   * wired (4.23/4.24). Absent → the gated button renders disabled + explained (AC11/OQ-2). */
  function recoverySeamMissingHandler(token: DecisionAction): boolean {
    return (
      (token === 'reconcile_conflict' && onReconcile === undefined) ||
      (token === 'classify_failure' && onClassifyFailure === undefined)
    );
  }

  /** Story 4.22 — dispatch a recovery action's click to its owned dialog or delegated seam. */
  function recoveryActionClick(token: DecisionAction) {
    switch (token) {
      case 'retry':
        setPendingRecoveryAction('retry');
        setRetryConfirmOpen(true);
        break;
      case 'resume_workflow':
        setResumeConfirmOpen(true);
        break;
      case 'rerun_from_step':
        openRerunDialog();
        break;
      case 'pause_workflow':
        setPauseConfirmOpen(true);
        break;
      case 'reconcile_conflict':
        onReconcile?.();
        break;
      case 'classify_failure':
        onClassifyFailure?.();
        break;
      default:
        break;
    }
  }

  /** Story 4.22 (AC1, AC2, AC10, AC11) — render one recovery action as primary or subordinate. */
  function renderRecoveryActionButton(token: DecisionAction, isPrimary: boolean) {
    const label = RECOVERY_ACTION_LABELS[token as keyof typeof RECOVERY_ACTION_LABELS];
    const testId = `recovery-action-${token}`;

    // Seam without a handler (pre-4.23/4.24): disabled + `aria-describedby` explanation — the
    // action is never silently gone (AC11 / OQ-2 seam-only default).
    if (recoverySeamMissingHandler(token)) {
      const explId = `${idBase}-${token}-unavailable`;
      const explanation =
        token === 'reconcile_conflict'
          ? reconcileResolving === true
            ? // Seam IS wired (4.23) — the button is disabled only until the conflict to reconcile is
              // resolved from the run's conflict list (or none is currently available).
              'Loading the conflict to reconcile…'
            : 'Opens in the reconciliation dialog — available in an upcoming increment.'
          : 'Opens in the classification dialog — available in an upcoming increment.';
      return (
        <span key={token} className="inline-flex flex-col gap-1" data-testid={testId}>
          <Button type="button" variant="outline" disabled aria-describedby={explId}>
            {label}
          </Button>
          <span id={explId} className="text-meta text-text-tertiary">
            {explanation}
          </span>
        </span>
      );
    }

    if (isPrimary) {
      const consequence = resolveConsequenceHint('recovery_operator', token);
      return (
        <GovernedButton
          key={token}
          priority="primary"
          type="button"
          testId={testId}
          aria-describedby={consequence !== undefined ? consequenceId : undefined}
          onClick={() => recoveryActionClick(token)}
        >
          {label}
        </GovernedButton>
      );
    }

    // Subordinate: an outline button + a non-color-only safety affix (AC2). `safe` subordinates
    // carry no affix (only the primary is the safe forward action; a second safe action is a
    // deterministic tie-break loser and needs no warning).
    const safety = recoverySafetyByToken?.[token];
    const affix =
      safety !== undefined && safety !== 'safe' ? RECOVERY_SAFETY_AFFIX[safety] : undefined;
    return (
      <span key={token} className="inline-flex items-center gap-1.5" data-testid={testId}>
        <Button type="button" variant="outline" onClick={() => recoveryActionClick(token)}>
          {label}
        </Button>
        {affix !== undefined && safety !== undefined ? (
          <span
            data-safety={safety}
            data-testid={`recovery-safety-${token}`}
            className={cn(
              'inline-flex items-center gap-1 text-meta',
              safetyTone(safety) === 'risky'
                ? 'text-state-blocker-foreground'
                : 'text-state-warning-foreground',
            )}
          >
            <TriangleAlert className="size-3 shrink-0" aria-hidden />
            {affix}
          </span>
        ) : null}
      </span>
    );
  }

  /**
   * Story 4.22 (AC1–AC7, AC9–AC11) — the REAL multi-action `recovery_operator` render (full
   * activation beyond the 3.30 retry baseline). Preserves the 3.30 success/submitting/error/disabled
   * branches; the `ready` branch now renders ONE safety-ranked primary + subordinate secondaries
   * (each with its safety affix), the owned confirm/rationale dialogs, and the reconcile/classify
   * entry-point seams.
   */
  function renderRecoveryOperator() {
    switch (state) {
      case 'success':
        // AC9 — the post-submit summary persists across the currentState flip. resume/rerun/pause
        // set `lastDecision` (a recovery outcome) → the DecisionSummary block; retry (the 3.30
        // baseline) sets none → its bespoke chip.
        return view.lastDecision !== undefined ? (
          <div className="flex flex-col gap-1" data-testid="recovery-decision-summary">
            <DecisionSummaryView summary={view.lastDecision} locked={false} />
            <p className="text-meta text-text-secondary">Recovery action recorded.</p>
            {view.lastDecision.decision === 'paused' ? (
              // AC9 follow-up — pause flips the run to `Paused`, where `resume_workflow` becomes
              // valid. Unlike resume/rerun (which move forward), the pause summary would otherwise
              // strand the now-valid Resume behind an external refetch; offer the re-sync here.
              <Button
                type="button"
                variant="outline"
                data-testid="recovery-paused-refresh"
                onClick={onRefresh}
              >
                Refresh to continue
              </Button>
            ) : null}
          </div>
        ) : (
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
              <GovernedButton
                priority="primary"
                workflowState="submitting"
                testId="recovery-submitting"
              >
                {pendingRecoveryAction !== null
                  ? RECOVERY_ACTION_LABELS[
                      pendingRecoveryAction as keyof typeof RECOVERY_ACTION_LABELS
                    ]
                  : 'Submitting'}
              </GovernedButton>
            }
          />
        );
      case 'error':
        return (
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="Your recovery action wasn't submitted"
            message={`The recovery action could not be submitted${
              mutation.errorCode !== undefined ? ` (${mutation.errorCode})` : ''
            }. Refresh and try again.`}
            nextAction={{ kind: 'Refresh', onRefresh }}
          />
        );
      case 'ready':
        return renderRecoveryActionArea();
      case 'disabled':
      default:
        // `View only` — no recovery action is available in the current state (AC5/AC11). NOT a
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

  /** Story 4.22 — the `ready`-state recovery action area: single primary + subordinates + dialogs. */
  function renderRecoveryActionArea() {
    const safety = recoverySafetyByToken ?? {};
    const available = RECOVERY_OPERATOR_ACTIONS.filter((action) => view.actions.includes(action));
    let primary = resolveRecoveryPrimaryAction(available, safety);
    // A `safe`-ranked seam with no wired handler (this story) cannot be a functional primary —
    // demote it so the single primary is always actionable (it re-renders as a disabled secondary).
    if (primary !== null && recoverySeamMissingHandler(primary)) {
      primary = null;
    }
    const secondaries = available.filter((action) => action !== primary);
    const primaryConsequence =
      primary !== null ? resolveConsequenceHint('recovery_operator', primary) : undefined;

    return (
      <div className="flex flex-col gap-2" data-testid="recovery-action-area">
        <DecisionArea
          ariaLabel="Recovery actions"
          primary={primary !== null ? renderRecoveryActionButton(primary, true) : null}
          secondary={
            secondaries.length > 0 ? (
              <>{secondaries.map((token) => renderRecoveryActionButton(token, false))}</>
            ) : undefined
          }
        />
        {primaryConsequence !== undefined ? (
          <p id={consequenceId} className="text-meta text-text-tertiary">
            {primaryConsequence}
          </p>
        ) : null}

        {/* Retry — the 3.30 baseline confirm dialog (kept). */}
        {available.includes('retry') ? (
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
              setPendingRecoveryAction('retry');
              onRetry?.();
            }}
          />
        ) : null}

        {/* Resume — AC3: a plain ConfirmationDialog (reasonText is optional server-side). */}
        {available.includes('resume_workflow') ? (
          <ConfirmationDialog
            open={resumeConfirmOpen}
            onOpenChange={setResumeConfirmOpen}
            intent={CONFIRMATION_CATALOG.resumeRun.intent}
            title="Resume run"
            consequence={CONFIRMATION_CATALOG.resumeRun.consequenceTemplate}
            confirmLabel="Confirm resume"
            cancelLabel="Cancel"
            isConfirming={mutation.status === 'pending'}
            onConfirm={() => {
              setResumeConfirmOpen(false);
              setPendingRecoveryAction('resume_workflow');
              onResume?.();
            }}
          />
        ) : null}

        {/* Pause — AC6: ConfirmationDialog + a REQUIRED reason (the pause endpoint raises
            MISSING_REASON_TEXT on blank), mirroring the takeover reason-capture shape. */}
        {available.includes('pause_workflow') ? (
          <ConfirmationDialog
            open={pauseConfirmOpen}
            onOpenChange={(open) => {
              setPauseConfirmOpen(open);
              if (!open) {
                setPauseReason('');
              }
            }}
            intent={CONFIRMATION_CATALOG.pauseRun.intent}
            title="Pause run"
            consequence={CONFIRMATION_CATALOG.pauseRun.consequenceTemplate}
            confirmLabel="Confirm pause"
            cancelLabel="Cancel"
            isConfirming={mutation.status === 'pending'}
            confirmDisabled={pauseReason.trim() === '' || mutation.status === 'pending'}
            onConfirm={() => {
              const reason = pauseReason.trim();
              setPauseConfirmOpen(false);
              setPauseReason('');
              setPendingRecoveryAction('pause_workflow');
              onPause?.(reason);
            }}
          >
            <label className="flex flex-col gap-1 text-meta text-text-secondary">
              <span>Reason (required)</span>
              <textarea
                value={pauseReason}
                onChange={(event) => setPauseReason(event.target.value)}
                rows={3}
                className="rounded-md border border-input bg-background p-2 text-sm text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
                data-testid="pause-reason"
              />
            </label>
          </ConfirmationDialog>
        ) : null}

        {/* Rerun-from-step — AC5: RationaleCaptureDialog (required reasonText) + a bar-owned
            targetStep select + the non-mutating supersession preview. */}
        {available.includes('rerun_from_step') ? (
          <RationaleCaptureDialog
            open={rerunDialogOpen}
            onOpenChange={handleRerunOpenChange}
            intent={CONFIRMATION_CATALOG.rerunFromStep.intent}
            title="Rerun from step"
            consequence={CONFIRMATION_CATALOG.rerunFromStep.consequenceTemplate}
            confirmLabel="Confirm rerun"
            cancelLabel="Cancel"
            isConfirming={mutation.status === 'pending'}
            fields={RERUN_RATIONALE_FIELDS}
            onConfirm={(values) => {
              setRerunDialogOpen(false);
              notifyRerunPreview(false, rerunStep);
              setPendingRecoveryAction('rerun_from_step');
              onRerunFromStep?.({ targetStep: rerunStep, reasonText: values.reasonText ?? '' });
            }}
          >
            <div className="flex flex-col gap-2">
              <div className="flex flex-col gap-1">
                <Label htmlFor={rerunStepId}>Rerun from step</Label>
                <Select
                  value={rerunStep}
                  onValueChange={(next) => changeRerunStep(next as RerunTargetStep)}
                >
                  <SelectTrigger
                    id={rerunStepId}
                    data-testid="rerun-step-select"
                    className="w-full"
                  >
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="investigating">Investigating</SelectItem>
                    <SelectItem value="executing">Executing</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              {renderRerunPreview()}
            </div>
          </RationaleCaptureDialog>
        ) : null}
      </div>
    );
  }

  /** Story 4.22 (AC5) — the "Show what will be superseded" preview section inside the rerun dialog. */
  function renderRerunPreview() {
    return (
      <div
        className="flex flex-col gap-1 rounded-md border border-border bg-surface-elevated p-2"
        data-testid="rerun-preview"
      >
        <p className="text-meta font-medium text-text-primary">Show what will be superseded</p>
        {rerunPreview?.isLoading === true ? (
          <p className="text-meta text-text-secondary" role="status">
            Loading the rerun preview…
          </p>
        ) : rerunPreview?.isError === true ? (
          <p className="text-meta text-state-warning-foreground">
            The rerun preview could not be loaded.
          </p>
        ) : rerunPreview?.data !== undefined ? (
          <div className="flex flex-col gap-1">
            <p
              className="text-meta text-text-secondary"
              data-testid="rerun-preview-superseded-count"
            >
              Superseded artifacts: {rerunPreview.data.supersededArtifactIds.length}
            </p>
            {rerunPreview.data.supersededArtifactIds.length > 0 ? (
              <ul className="list-disc pl-4 text-meta text-text-tertiary">
                {rerunPreview.data.supersededArtifactIds.map((id) => (
                  <li key={id} data-testid="rerun-preview-superseded">
                    <code>{id}</code>
                  </li>
                ))}
              </ul>
            ) : null}
            <p
              className="text-meta text-text-secondary"
              data-testid="rerun-preview-invalidated-count"
            >
              Invalidated approvals: {rerunPreview.data.invalidatedApprovalIds.length}
            </p>
            {rerunPreview.data.invalidatedApprovalIds.length > 0 ? (
              <ul className="list-disc pl-4 text-meta text-text-tertiary">
                {rerunPreview.data.invalidatedApprovalIds.map((id) => (
                  <li key={id} data-testid="rerun-preview-invalidated">
                    <code>{id}</code>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : (
          <p className="text-meta text-text-secondary">Select a step to preview the impact.</p>
        )}
      </div>
    );
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
