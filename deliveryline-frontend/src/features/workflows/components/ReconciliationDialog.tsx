/**
 * Story 4.23 (AC1–AC11) — the operator Reconciliation Dialog.
 *
 * Shows the internal-state snapshot vs external-state snapshot side-by-side (stacked on mobile),
 * surfaces the safety-ranked `suggestedDecisions`, requires an explicit decision + a non-empty
 * `reasonText` (NFR19 — no silent overwrite), and submits via the story-4.11 reconcile endpoint. The
 * dialog is dialog-scoped: it fetches the conflict detail only while `open && conflictId` is set.
 *
 * REUSE (Dev Notes): `ConfirmationDialog` (desktop; `intent="danger"`, required-`consequence` →
 * aria-describedby, built-in focus-in + focus-restore + Esc) / `BoundedDetailSheet` (mobile bottom
 * sheet, stacked panels); `useReconcileWorkflow` / `useIntegrationConflict`; `StateSignifierChip` +
 * the `safetyStateName` mapping; `useLiveAnnouncement` + the `recoveryReconcile*` vocabulary. Pure
 * helpers live in the sibling `reconciliationDialogView.ts` ([[frontend-react-refresh-no-fn-exports]]).
 *
 * SURVIVAL (Task 10): reconcile resolves the conflict and can flip the run out of `Paused`/`Failed`,
 * which would unmount the container that renders this dialog. So the `reconcile` mutation instance is
 * OPTIONAL and normally supplied by the route-level `WorkflowDecisionBar` (which keeps the recovery
 * bar mounted through `reconcile.isSuccess`), and the live-region announcer is rendered OUTSIDE the
 * radix portal so the "recorded" announcement survives the close. Standalone/test mounts fall back to
 * an internal instance.
 *
 * SECURITY (trap #4): the snapshots are UNTRUSTED external metadata — parsed defensively (degrade,
 * never throw) and rendered only as React-escaped text nodes (never `dangerouslySetInnerHTML`).
 */
import { useEffect, useId, useState } from 'react';

import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { cn } from '@/lib/utils';
import * as announce from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import { BoundedDetailSheet, ConfirmationDialog } from '@/components/overlays';
import { GovernedButton } from '@/components/actions';
import { Textarea } from '@/components/ui/textarea';
import { Skeleton } from '@/components/ui/skeleton';

import {
  useReconcileWorkflow,
  type ReconciliationDecision,
  type ReconcileWorkflowResult,
} from '../hooks/useReconcileWorkflow';
import { useIntegrationConflict } from '../hooks/useIntegrationConflict';
import { useResponsiveLayout } from '../hooks/useResponsiveLayout';
import { StateSignifierChip } from './WorkflowStateBadge';
import {
  coerceSuggestedSafety,
  conflictCategoryLabel,
  decisionConsequence,
  decisionLabel,
  diffSnapshots,
  integrationTypeLabel,
  parseSnapshot,
  prettyJson,
  safetyChipLabel,
  safetyStateName,
  unknownSnapshotFields,
  type FieldDiffStatus,
  type ParsedSnapshot,
  type SnapshotFieldDiff,
} from './reconciliationDialogView';

export interface ReconciliationDialogProps {
  /** The run the reconcile is fired against (the story-4.11 endpoint is run-scoped). */
  workflowRunId: string;
  /** The unresolved conflict to reconcile (resolved by the launch surface — AC8). */
  conflictId: string;
  open: boolean;
  onClose: () => void;
  /**
   * The hoisted reconcile mutation (from `WorkflowDecisionBar`) so a post-reconcile state flip does
   * not tear down the announcement; defaults to an internal instance for standalone/test mounts.
   */
  reconcile?: ReconcileWorkflowResult | undefined;
}

/** Error codes this dialog surfaces with a bespoke explanatory state (AC6). */
const HANDLED_ERROR_CODES: ReadonlySet<string> = new Set([
  'CONFLICT_ALREADY_RESOLVED',
  'CONFLICT_NOT_FOUND',
  'RECONCILE_NOT_APPLICABLE',
  'IDEMPOTENCY_KEY_CONFLICT',
]);

/** The per-field diff highlight (border + foreground token) — never color-alone (a text tag pairs it). */
const DIFF_STATUS_CLASS: Record<FieldDiffStatus, string> = {
  added: 'border-state-success-border text-state-success-foreground',
  removed: 'border-state-error-border text-state-error-foreground',
  modified: 'border-state-warning-border text-state-warning-foreground',
  unchanged: 'border-border text-text-primary',
};

const DIFF_STATUS_TAG: Record<FieldDiffStatus, string> = {
  added: 'Added',
  removed: 'Removed',
  modified: 'Changed',
  unchanged: 'Same',
};

export function ReconciliationDialog({
  workflowRunId,
  conflictId,
  open,
  onClose,
  reconcile: reconcileProp,
}: ReconciliationDialogProps) {
  const isMobile = useResponsiveLayout() === 'mobile';
  const internalReconcile = useReconcileWorkflow(workflowRunId);
  const reconcile = reconcileProp ?? internalReconcile;

  const conflictQuery = useIntegrationConflict(conflictId, {
    enabled: open && conflictId !== '',
  });
  const conflict = conflictQuery.data;

  const [selectedDecision, setSelectedDecision] = useState<ReconciliationDecision | undefined>(
    undefined,
  );
  const [reasonText, setReasonText] = useState('');
  const [discardPromptOpen, setDiscardPromptOpen] = useState(false);
  const baseId = useId();

  const suggestions = (conflict?.suggestedDecisions ?? []).filter(
    (s): s is { decision: ReconciliationDecision; safety?: 'safe' | 'risky' } =>
      typeof s.decision === 'string',
  );
  // The recommended option is the FIRST entry (safe-first ordered — trap #3, no explicit flag).
  const recommended = suggestions[0]?.decision;
  const selectedSafety = suggestions.find((s) => s.decision === selectedDecision)?.safety;
  const selectedIsRisky =
    selectedDecision !== undefined && coerceSuggestedSafety(selectedSafety) === 'risky';

  // Reset local state + clear a prior mutation result each time the dialog OPENS (not on close — the
  // success announcement must survive the close, so we never reset while closing). Also re-run when
  // `conflictId` changes while the dialog stays open (a launch surface reopens it for a DIFFERENT
  // conflict without an intervening close) so a stale decision/reason can never POST against the new
  // conflict.
  useEffect(() => {
    if (open) {
      setSelectedDecision(undefined);
      setReasonText('');
      setDiscardPromptOpen(false);
      reconcile.reset();
    }
    // reconcile is a stable per-run instance; intentionally not a dep (avoids reset loops).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, conflictId]);

  // Pre-select the recommended (first) suggestion once detail loads (operator can change).
  useEffect(() => {
    if (open && selectedDecision === undefined && recommended !== undefined) {
      setSelectedDecision(recommended);
    }
  }, [open, selectedDecision, recommended]);

  const reasonDirty = reasonText.trim() !== '';
  const canSubmit =
    selectedDecision !== undefined && reasonDirty && !reconcile.isPending && conflict !== undefined;

  const errorCode =
    reconcile.isError && isProblemDetailsError(reconcile.error) ? reconcile.error.code : undefined;
  const handledError = errorCode !== undefined && HANDLED_ERROR_CODES.has(errorCode);

  // Live-region announcement (AC10). Success persists (survives the close); an UNHANDLED submit error
  // announces the generic failure; handled errors get an explanatory visual state instead.
  const announcementMessage = reconcile.isSuccess
    ? announce.recoveryReconcileRecorded
    : reconcile.isPending
      ? announce.recoveryReconcileInitiated
      : reconcile.isError && !handledError
        ? announce.decisionSubmitFailed
        : '';
  const announced = useLiveAnnouncement(announcementMessage);

  function handleConfirm() {
    // Inline the gate (rather than the `canSubmit` boolean) so TypeScript narrows `selectedDecision`
    // to a concrete decision for the mutation payload.
    if (
      selectedDecision === undefined ||
      reasonText.trim() === '' ||
      reconcile.isPending ||
      conflict === undefined
    ) {
      return;
    }
    reconcile.mutate(
      { conflictId, resolutionDecision: selectedDecision, reasonText },
      {
        onSuccess: (data) => {
          // Field-only (T-LOG-PII): never reasonText / snapshot bytes / actor ids.
          console.info({ event: 'recovery.reconcileSubmit', currentState: data.currentState });
          onClose();
        },
        onError: (error) => {
          console.warn({
            event: 'recovery.reconcileError',
            code: isProblemDetailsError(error) ? error.code : 'transport',
            transport: !isProblemDetailsError(error),
          });
        },
      },
    );
  }

  /** Intercept a close attempt: confirm-discard first when the reason has been edited (AC7). */
  function requestClose() {
    if (reasonDirty && !reconcile.isPending) {
      setDiscardPromptOpen(true);
      return;
    }
    onClose();
  }

  function handleOpenChange(next: boolean) {
    if (!next) {
      requestClose();
    }
  }

  const headerTitle =
    conflict !== undefined
      ? `${conflictCategoryLabel(conflict.conflictCategory)} · ${integrationTypeLabel(conflict.integrationType)}`
      : 'Reconcile integration conflict';
  // The selected decision's consequence is the aria-describedby target (AC10) + the risky warning (AC4).
  const consequenceText =
    selectedDecision !== undefined
      ? decisionConsequence(selectedDecision)
      : 'Choose how to reconcile the internal and external state, then record your reasoning.';

  const body = (
    <ReconciliationBody
      baseId={baseId}
      isMobile={isMobile}
      isLoading={conflictQuery.isLoading}
      isLoadError={conflictQuery.isError}
      conflict={conflict}
      suggestions={suggestions}
      recommended={recommended}
      selectedDecision={selectedDecision}
      onSelectDecision={setSelectedDecision}
      selectedIsRisky={selectedIsRisky}
      consequenceText={consequenceText}
      reasonText={reasonText}
      onReasonChange={setReasonText}
      errorCode={errorCode}
      handledError={handledError}
      submitFailed={reconcile.isError && !handledError}
      onRefreshConflict={() => void conflictQuery.refetch()}
    />
  );

  const footer = (
    <div className="flex flex-wrap justify-end gap-2">
      <GovernedButton priority="secondary" type="button" onClick={requestClose}>
        Cancel
      </GovernedButton>
      <GovernedButton
        priority="destructive"
        type="button"
        testId="reconcile-confirm"
        workflowState={reconcile.isPending ? 'submitting' : undefined}
        disabled={!canSubmit}
        onClick={handleConfirm}
      >
        Confirm reconcile
      </GovernedButton>
    </div>
  );

  return (
    <>
      {/* AC10 — the live region lives OUTSIDE the radix portal so the "recorded" announcement is
          spoken even as the dialog closes on success. */}
      <p className="sr-only" role="status" aria-live="polite" data-testid="reconcile-announcer">
        {announced}
      </p>

      {isMobile ? (
        <BoundedDetailSheet
          open={open}
          onOpenChange={handleOpenChange}
          side="bottom"
          fullHeightOnMobile
          title={headerTitle}
          description={consequenceText}
          testId="reconciliation-dialog"
        >
          <div className="flex flex-col gap-4">
            {body}
            {footer}
          </div>
        </BoundedDetailSheet>
      ) : (
        <ConfirmationDialog
          open={open}
          onOpenChange={handleOpenChange}
          title={headerTitle}
          intent="danger"
          consequence={consequenceText}
          confirmLabel="Confirm reconcile"
          onConfirm={handleConfirm}
          onCancel={requestClose}
          isConfirming={reconcile.isPending}
          confirmDisabled={!canSubmit}
          testId="reconciliation-dialog"
        >
          {body}
        </ConfirmationDialog>
      )}

      {/* AC7 — confirm-discard when the reason has been edited. A separate controlled dialog (not
          nested inside the sheet body) so it works for both layouts. */}
      <ConfirmationDialog
        open={discardPromptOpen}
        onOpenChange={(next) => setDiscardPromptOpen(next)}
        title="Discard your reconciliation note?"
        intent="warning"
        consequence="Your reconciliation note has not been submitted and will be lost."
        confirmLabel="Discard note"
        cancelLabel="Keep editing"
        onConfirm={() => {
          setDiscardPromptOpen(false);
          onClose();
        }}
        onCancel={() => setDiscardPromptOpen(false)}
        testId="reconcile-discard-prompt"
      />
    </>
  );
}

// --- Body -----------------------------------------------------------------------

interface ReconciliationBodyProps {
  baseId: string;
  isMobile: boolean;
  isLoading: boolean;
  isLoadError: boolean;
  conflict: ReturnType<typeof useIntegrationConflict>['data'];
  suggestions: readonly { decision: ReconciliationDecision; safety?: 'safe' | 'risky' }[];
  recommended: ReconciliationDecision | undefined;
  selectedDecision: ReconciliationDecision | undefined;
  onSelectDecision: (decision: ReconciliationDecision) => void;
  selectedIsRisky: boolean;
  consequenceText: string;
  reasonText: string;
  onReasonChange: (value: string) => void;
  errorCode: string | undefined;
  handledError: boolean;
  /** An unhandled submit failure with no typed code (network / non-problem+json 5xx) — AC6. */
  submitFailed: boolean;
  onRefreshConflict: () => void;
}

function ReconciliationBody({
  baseId,
  isMobile,
  isLoading,
  isLoadError,
  conflict,
  suggestions,
  recommended,
  selectedDecision,
  onSelectDecision,
  selectedIsRisky,
  consequenceText,
  reasonText,
  onReasonChange,
  errorCode,
  handledError,
  submitFailed,
  onRefreshConflict,
}: ReconciliationBodyProps) {
  if (isLoading) {
    return (
      <div className="flex flex-col gap-3" data-testid="reconciliation-loading">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    );
  }

  if (isLoadError || conflict === undefined) {
    return (
      <p data-testid="reconciliation-load-error" className="text-sm text-state-error-foreground">
        The conflict details could not be loaded. Close and try again.
      </p>
    );
  }

  const internalParsed = parseSnapshot(conflict.internalStateSnapshot);
  const externalParsed = parseSnapshot(conflict.externalStateSnapshot);
  const diffs = diffSnapshots(internalParsed, externalParsed);
  const reasonId = `${baseId}-reason`;
  const consequenceId = `${baseId}-consequence`;

  return (
    <div className="flex flex-col gap-5">
      {/* Snapshot panels — side-by-side on desktop/tablet, stacked on mobile (AC2/AC9). */}
      <section
        aria-label="State comparison"
        data-testid="reconciliation-snapshots"
        className={cn('grid gap-3', isMobile ? 'grid-cols-1' : 'grid-cols-2')}
      >
        <SnapshotPanel
          title="Internal state"
          testId="reconciliation-snapshot-internal"
          parsed={internalParsed}
          diffs={diffs}
          side="internal"
        />
        <SnapshotPanel
          title="External state"
          testId="reconciliation-snapshot-external"
          parsed={externalParsed}
          diffs={diffs}
          side="external"
        />
      </section>

      {/* Decision radio group (AC4). */}
      <fieldset className="flex flex-col gap-2" data-testid="reconciliation-decisions">
        <legend className="text-sm font-medium text-text-primary">Reconciliation decision</legend>
        {suggestions.length === 0 ? (
          <p className="text-sm text-text-tertiary">No reconciliation options are available.</p>
        ) : (
          suggestions.map((s) => {
            const safety = coerceSuggestedSafety(s.safety);
            const isRecommended = s.decision === recommended;
            return (
              <label
                key={s.decision}
                data-testid={`reconciliation-decision-${s.decision}`}
                data-safety={safety}
                className="flex items-center gap-2 rounded-md border border-border px-3 py-2 text-sm"
              >
                <input
                  type="radio"
                  name={`${baseId}-decision`}
                  value={s.decision}
                  checked={selectedDecision === s.decision}
                  onChange={() => onSelectDecision(s.decision)}
                  className="size-4"
                />
                <span className="text-text-primary">{decisionLabel(s.decision)}</span>
                <StateSignifierChip
                  stateName={safetyStateName(safety)}
                  label={safetyChipLabel(safety)}
                  testId={`reconciliation-safety-${s.decision}`}
                />
                {isRecommended ? (
                  <span
                    data-testid="reconciliation-recommended"
                    className="text-meta text-text-secondary"
                  >
                    Recommended
                  </span>
                ) : null}
              </label>
            );
          })
        )}
      </fieldset>

      {/* Selected decision consequence — the aria-describedby target (AC10); an inline WARNING when
          the selected option is risky (AC4). */}
      <p
        id={consequenceId}
        data-testid="reconciliation-consequence"
        data-risky={selectedIsRisky ? 'true' : 'false'}
        className={cn(
          'rounded-md border px-3 py-2 text-sm',
          selectedIsRisky
            ? 'border-state-error-border text-state-error-foreground'
            : 'border-border text-text-secondary',
        )}
      >
        {selectedIsRisky ? <strong className="mr-1">Warning:</strong> : null}
        {consequenceText}
      </p>

      {/* Required reason (NFR19 — AC5). */}
      <div className="flex flex-col gap-1.5">
        <label htmlFor={reasonId} className="text-sm font-medium text-text-primary">
          Reason
          <span aria-hidden className="ml-0.5 text-state-error-foreground">
            *
          </span>
        </label>
        <Textarea
          id={reasonId}
          data-testid="reconciliation-reason"
          value={reasonText}
          aria-describedby={consequenceId}
          placeholder="Explain why this reconciliation decision is correct."
          onChange={(e) => onReasonChange(e.target.value)}
        />
      </div>

      {/* Error / stale-state handling (AC6). A typed code renders its explanatory copy; an unhandled
          transport / non-problem+json failure (no code) still surfaces a visible generic notice —
          never left as an sr-only announcement while the dialog looks idle. */}
      {errorCode !== undefined || submitFailed ? (
        <ErrorNotice errorCode={errorCode} handled={handledError} onRefresh={onRefreshConflict} />
      ) : null}
    </div>
  );
}

// --- Snapshot panel -------------------------------------------------------------

function SnapshotPanel({
  title,
  testId,
  parsed,
  diffs,
  side,
}: {
  title: string;
  testId: string;
  parsed: ParsedSnapshot;
  diffs: readonly SnapshotFieldDiff[];
  side: 'internal' | 'external';
}) {
  const unknown = unknownSnapshotFields(parsed);
  const unknownKeys = Object.keys(unknown);
  return (
    <div
      data-testid={testId}
      data-snapshot-ok={parsed.ok ? 'true' : 'false'}
      className="flex flex-col gap-2 rounded-md border border-border p-3"
    >
      <h3 className="text-annotation uppercase tracking-wide text-text-tertiary">{title}</h3>
      {!parsed.ok ? (
        <p className="text-sm text-text-tertiary" data-testid={`${testId}-fallback`}>
          Snapshot unavailable or unparseable.
        </p>
      ) : (
        <>
          <dl className="flex flex-col gap-1.5">
            {diffs.length === 0 ? (
              <p className="text-sm text-text-tertiary">No recognized fields.</p>
            ) : (
              diffs.map((row) => {
                const value = side === 'internal' ? row.internalValue : row.externalValue;
                return (
                  <div
                    key={row.key}
                    data-diff-status={row.status}
                    className={cn(
                      'flex items-baseline justify-between gap-2 border-l-2 pl-2',
                      DIFF_STATUS_CLASS[row.status],
                    )}
                  >
                    <dt className="text-meta uppercase tracking-wide text-text-tertiary">
                      {row.label}
                    </dt>
                    <dd className="flex items-center gap-1.5 break-all text-sm">
                      <span>{value ?? '—'}</span>
                      {row.status !== 'unchanged' ? (
                        <span className="text-meta">({DIFF_STATUS_TAG[row.status]})</span>
                      ) : null}
                    </dd>
                  </div>
                );
              })
            )}
          </dl>
          {unknownKeys.length > 0 ? (
            <details data-testid={`${testId}-raw`}>
              <summary className="cursor-pointer text-meta text-text-secondary">
                Raw metadata ({unknownKeys.length})
              </summary>
              <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded bg-surface-elevated p-2 text-meta text-text-secondary">
                {prettyJson(unknown)}
              </pre>
            </details>
          ) : null}
        </>
      )}
    </div>
  );
}

// --- Error notice ---------------------------------------------------------------

function ErrorNotice({
  errorCode,
  handled,
  onRefresh,
}: {
  errorCode: string | undefined;
  handled: boolean;
  onRefresh: () => void;
}) {
  const copy = errorNoticeCopy(errorCode, handled);
  return (
    <div
      role="alert"
      data-testid="reconciliation-error"
      data-error-code={errorCode ?? 'transport'}
      className="flex flex-col gap-2 rounded-md border border-state-error-border p-3 text-sm text-state-error-foreground"
    >
      <p>{copy}</p>
      {errorCode === 'CONFLICT_ALREADY_RESOLVED' ? (
        <GovernedButton
          priority="secondary"
          type="button"
          testId="reconcile-refresh"
          onClick={onRefresh}
        >
          Refresh and try again
        </GovernedButton>
      ) : null}
    </div>
  );
}

/** Map a submit error code to an operator-facing explanation (AC6). */
function errorNoticeCopy(errorCode: string | undefined, handled: boolean): string {
  switch (errorCode) {
    case 'CONFLICT_ALREADY_RESOLVED':
      return 'This conflict was already resolved by another operator. Refresh to see the current state.';
    case 'CONFLICT_NOT_FOUND':
      return 'This conflict no longer exists — it may have been resolved. Close this dialog.';
    case 'RECONCILE_NOT_APPLICABLE':
      return 'This run is no longer in a state that can be reconciled. Close this dialog.';
    case 'IDEMPOTENCY_KEY_CONFLICT':
      return 'A matching reconcile is already being processed. Refresh to see the result before retrying.';
    default:
      return handled
        ? 'The reconciliation could not be completed.'
        : 'The reconciliation could not be submitted. Please try again.';
  }
}
