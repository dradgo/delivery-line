/**
 * Story 4.24 (AC1/AC2/AC4/AC5/AC6/AC7/AC10/AC11) — the failure-taxonomy classification dialog.
 *
 * Composes the controlled `ConfirmationDialog` (radix focus capture/restore) — NEVER a new
 * portal/host (T-NO-STACK). The classification options are native `<fieldset>/<legend>/<input
 * type="radio">` cards (arrow-key roving focus + `radiogroup` semantics for free, AC10). A run's
 * prior classification (from `useFailureClassification`) pre-selects the prior value — or its
 * replacement when the prior is deprecated (AC5). Deprecated values render with the reduced-prominence
 * `state-draft` treatment + a "(deprecated, use X instead)" affix composed in the FE (AC4). Submission
 * goes through `useClassifyFailure`; a server-side `DEPRECATED_TAXONOMY_VALUE` re-selects the
 * recommended replacement inline (AC7). Mobile: one responsive presentation — the dialog goes
 * full-height edge-to-edge at the mobile breakpoint and the cards stack vertically (AC11).
 *
 * The curated `description`/`examples` are backend-authored TRUSTED strings — rendered as
 * React-escaped plain text (auto-escaped). Helper fns live in the sibling
 * `failureClassificationDialogView.ts` (react-refresh forbids fn exports from a `.tsx`).
 */
import { useEffect, useId, useState } from 'react';

import { ConfirmationDialog } from '@/components/overlays/ConfirmationDialog';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { failureClassificationApplied } from '@/lib/a11y/announcements';
import { announce } from '@/lib/a11y/liveAnnouncer';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import { cn } from '@/lib/utils';

import { humanizeFailureCategory } from '../failureCategoryView';
import { formatUtcTimestamp } from '../runContextFormat';
import { useClassifyFailure } from '../hooks/useClassifyFailure';
import { useFailureClassification } from '../hooks/useFailureClassification';
import { useFailureDiagnostics } from '../hooks/useFailureDiagnostics';
import { useFailureTaxonomy } from '../hooks/useFailureTaxonomy';
import { useWorkflowDetail } from '../hooks/useWorkflowDetail';
import {
  deprecatedAffix,
  findTaxonomy,
  humanNameForTaxonomy,
  replacementValueFromDetails,
  resolvePreselectValue,
} from './failureClassificationDialogView';

export interface FailureClassificationDialogProps {
  /** The failed run to classify. */
  workflowRunId: string;
  /** Invoked when the dialog closes (maps to the launch context's local `open` flag). */
  onClose: () => void;
}

const CONSEQUENCE =
  'Classification is recorded in audit history for cross-run analysis. It does not change the run’s state.';

/** The dialog for applying a governed failure-taxonomy classification to a failed run. */
export function FailureClassificationDialog({
  workflowRunId,
  onClose,
}: FailureClassificationDialogProps) {
  const [open, setOpen] = useState(true);
  const [selected, setSelected] = useState<string>('');
  const [reasonText, setReasonText] = useState('');
  const [inlineError, setInlineError] = useState<string | undefined>(undefined);
  const [preselectApplied, setPreselectApplied] = useState(false);

  const idBase = useId();
  const taxonomyQuery = useFailureTaxonomy();
  const classificationQuery = useFailureClassification(workflowRunId);
  const detailQuery = useWorkflowDetail(workflowRunId);
  // Review D4 (AC2) — the header shows the failure REASON text; it is not on `WorkflowDetailResponse`
  // (deliberately not widened, OQ), so read it from the run's failure diagnostics (same source the
  // diagnostics deep-dive uses). Absent/errored → no reason segment (the header degrades gracefully).
  const diagnosticsQuery = useFailureDiagnostics(workflowRunId);
  const mutation = useClassifyFailure(workflowRunId);

  const taxonomies = taxonomyQuery.data;
  const classification = classificationQuery.data;
  const priorValue = classification?.currentTaxonomyValue ?? undefined;

  // AC5 — pre-select the prior value (or its replacement when deprecated), ONCE, after both the
  // registry + the run's classification have loaded.
  useEffect(() => {
    if (preselectApplied || taxonomies === undefined || classificationQuery.isLoading) {
      return;
    }
    const preselect = resolvePreselectValue(classification, taxonomies);
    if (preselect !== '') {
      setSelected(preselect);
    }
    setPreselectApplied(true);
  }, [preselectApplied, taxonomies, classification, classificationQuery.isLoading]);

  const selectedEntry = findTaxonomy(taxonomies, selected);
  const selectedIsDeprecated = selectedEntry?.deprecated === true;
  const selectedReplacement =
    selectedEntry !== undefined &&
    selectedEntry.replacementValue != null &&
    selectedEntry.replacementValue !== ''
      ? selectedEntry.replacementValue
      : undefined;

  const failureCategory = humanizeFailureCategory(detailQuery.data?.failureCategory);
  const failureReason =
    diagnosticsQuery.data?.failureReason != null && diagnosticsQuery.data.failureReason !== ''
      ? diagnosticsQuery.data.failureReason
      : undefined;

  const announcement = mutation.isPending ? 'Applying classification…' : (inlineError ?? '');
  const liveMessage = useLiveAnnouncement(announcement);

  const handleOpenChange = (next: boolean) => {
    setOpen(next);
    if (!next) {
      onClose();
    }
  };

  const handleConfirm = () => {
    if (selected === '') {
      return;
    }
    setInlineError(undefined);
    mutation.mutate(
      { taxonomyValue: selected, reasonText },
      {
        onSuccess: () => {
          // Review P3 (AC10) — the dialog unmounts on close, so announce success through the
          // document-level live region (an in-dialog aria-live region would be torn down first).
          announce(failureClassificationApplied);
          handleOpenChange(false);
        },
        onError: (error) => {
          if (isProblemDetailsError(error)) {
            if (error.code === 'DEPRECATED_TAXONOMY_VALUE') {
              // AC7 — the server-side backstop: re-select the recommended replacement inline.
              const replacement = replacementValueFromDetails(error.problem.details);
              if (
                replacement !== undefined &&
                findTaxonomy(taxonomies, replacement) !== undefined
              ) {
                setSelected(replacement);
                setInlineError(
                  `That value is deprecated. We’ve selected the recommended replacement (${replacement}). Review and re-apply.`,
                );
                return;
              }
            }
            // Review P1 — non-retryable failures must not read "try again": retrying the same
            // submission cannot succeed once the run left the classifiable state or no longer exists.
            if (error.code === 'CLASSIFY_NOT_APPLICABLE') {
              setInlineError(
                'This run can no longer be classified because its state changed. Close this dialog and refresh the run.',
              );
              return;
            }
            if (error.code === 'RUN_NOT_FOUND') {
              setInlineError('This run no longer exists. Close this dialog and refresh the queue.');
              return;
            }
            if (
              error.code === 'INVALID_TAXONOMY_VALUE' ||
              error.code === 'MISSING_TAXONOMY_VALUE'
            ) {
              setInlineError('Select a valid failure category, then apply again.');
              return;
            }
          }
          setInlineError('Could not apply the classification. Please try again.');
        },
      },
    );
  };

  const taxonomyLoadFailed = taxonomyQuery.isError;

  return (
    <ConfirmationDialog
      open={open}
      onOpenChange={handleOpenChange}
      title="Classify failure"
      intent="warning"
      consequence={CONSEQUENCE}
      confirmLabel="Apply classification"
      cancelLabel="Cancel"
      onConfirm={handleConfirm}
      isConfirming={mutation.isPending}
      confirmDisabled={selected === '' || mutation.isPending || taxonomyQuery.isLoading}
      testId="failure-classification-dialog"
      className="max-h-[85vh] overflow-y-auto max-sm:left-0 max-sm:top-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-none max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none"
    >
      <div className="flex flex-col gap-4">
        {/* AC10 — submission-state live region (deferred one commit; assert with waitFor). */}
        <p aria-live="polite" className="sr-only" data-testid="failure-classification-live">
          {liveMessage}
        </p>

        {/* Header: run + current failure context (AC2). */}
        <div className="text-meta text-text-secondary" data-testid="failure-classification-context">
          <span className="font-medium text-text-primary">{workflowRunId}</span>
          {failureCategory !== undefined ? <> · Failure category: {failureCategory}</> : null}
          {/* Review D4 (AC2) — the failure reason text. Runner-authored (untrusted) → React-escaped. */}
          {failureReason !== undefined ? <> · Reason: {failureReason}</> : null}
          {detailQuery.data?.failedStage != null ? (
            <> · Failed stage: {detailQuery.data.failedStage}</>
          ) : null}
        </div>

        {/* AC2/AC5 — prior classification + re-classify warning. */}
        {priorValue !== undefined ? (
          <div
            className="rounded-md border border-border bg-surface p-2 text-meta text-text-secondary"
            data-testid="failure-classification-prior"
          >
            <p>
              Previously classified as{' '}
              <span className="font-medium text-text-primary">
                {/* Review D1 — humanize via the registry FIRST (the canonical label shared with the
                    strip + diagnostics surfaces); the raw wire `currentDisplayLabel` is only a
                    fallback for a value the current build's registry lacks. */}
                {humanNameForTaxonomy(taxonomies, priorValue) ??
                  classification?.currentDisplayLabel ??
                  priorValue}
              </span>
              {classification?.deprecated === true ? (
                <span className="font-normal text-state-draft-foreground"> (deprecated)</span>
              ) : null}
              {classification?.classifiedAt != null ? (
                <>
                  {' at '}
                  {formatUtcTimestamp(classification.classifiedAt) ?? classification.classifiedAt}
                </>
              ) : null}
              {classification?.classifiedBy != null ? <> by {classification.classifiedBy}</> : null}
              .
            </p>
            <p className="mt-1" role="note">
              This will re-classify the run. The prior classification remains in audit history.
            </p>
          </div>
        ) : null}

        {/* AC1/AC2/AC4/AC10 — the taxonomy radio cards. */}
        {taxonomyLoadFailed ? (
          <p role="alert" className="text-meta text-state-error-foreground">
            Could not load the failure taxonomy. Close and try again.
          </p>
        ) : (
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-meta font-medium text-text-primary">
              Failure category
            </legend>
            {(taxonomies ?? []).map((entry) => {
              const descriptionId = `${idBase}-${entry.value}-desc`;
              const affix = deprecatedAffix(entry);
              const accessibleName = `${entry.humanReadableName}${affix}`;
              return (
                <label
                  key={entry.value}
                  data-testid={`failure-classification-option-${entry.value}`}
                  data-deprecated={entry.deprecated ? 'true' : undefined}
                  className={cn(
                    'flex min-h-touch cursor-pointer items-start gap-2 rounded-md border p-2',
                    entry.deprecated
                      ? 'border-state-draft-border bg-state-draft text-state-draft-foreground'
                      : 'border-input bg-background',
                    selected === entry.value ? 'ring-2 ring-ring-focus' : '',
                  )}
                >
                  <input
                    type="radio"
                    name={`${idBase}-taxonomy`}
                    value={entry.value}
                    checked={selected === entry.value}
                    onChange={() => setSelected(entry.value)}
                    aria-describedby={descriptionId}
                    aria-label={accessibleName}
                    className="mt-1 min-h-touch"
                  />
                  <span className="flex flex-col gap-1">
                    <span className="text-sm font-medium text-text-primary">
                      {entry.humanReadableName}
                      {entry.deprecated ? (
                        <span className="ml-1 font-normal text-state-draft-foreground">
                          {affix}
                        </span>
                      ) : null}
                      {entry.deprecated ? <span className="sr-only"> Draft</span> : null}
                    </span>
                    <span id={descriptionId} className="text-meta text-text-secondary">
                      {entry.description}
                    </span>
                    {entry.examples.length > 0 ? (
                      <ul className="list-disc pl-4 text-meta text-text-secondary">
                        {entry.examples.map((example, index) => (
                          <li key={index}>{example}</li>
                        ))}
                      </ul>
                    ) : null}
                  </span>
                </label>
              );
            })}
          </fieldset>
        )}

        {/* AC4 — client-side deprecated-selection warning (first line of defense). */}
        {selectedIsDeprecated ? (
          <p
            role="alert"
            className="text-meta text-state-error-foreground"
            data-testid="failure-classification-deprecated-warning"
          >
            This value is deprecated and will be rejected by the backend
            {selectedReplacement !== undefined ? <> — use {selectedReplacement} instead</> : null}.
          </p>
        ) : null}

        {/* Optional operator note (AC2/AC6). */}
        <label className="flex flex-col gap-1 text-meta text-text-secondary">
          <span>Reason (optional)</span>
          <textarea
            value={reasonText}
            onChange={(event) => setReasonText(event.target.value)}
            rows={3}
            aria-describedby={`${idBase}-reason-hint`}
            data-testid="failure-classification-reason"
            className="rounded-md border border-input bg-background p-2 text-sm text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          />
          <span id={`${idBase}-reason-hint`} className="text-meta text-text-tertiary">
            A note explaining the classification for cross-run analysis. Optional.
          </span>
        </label>

        {/* AC7 — the server-side error backstop (also covers non-deprecated failures). */}
        {inlineError !== undefined ? (
          <p
            role="alert"
            className="text-meta text-state-error-foreground"
            data-testid="failure-classification-error"
          >
            {inlineError}
          </p>
        ) : null}
      </div>
    </ConfirmationDialog>
  );
}
