/**
 * Story 3d-8 FE gap — the run Archive / Unarchive control. Self-hiding container: reads the
 * workflow_owner allowed-actions matrix and renders exactly one governed button (Archive run
 * for a live run, Unarchive run for a hidden run), capturing a reason via the shared
 * `RationaleCaptureDialog`. On success the mutation factory invalidates detail + lists, so the
 * allowed-actions refetch flips the control automatically. The reason is user-authored and is
 * NEVER logged (T-LOG-PII).
 */
import { useState } from 'react';

import { GovernedButton } from '@/components/actions/GovernedButton';
import {
  RationaleCaptureDialog,
  type RationaleValues,
} from '@/components/overlays/RationaleCaptureDialog';
import { isProblemDetailsError } from '@/lib/api/problemDetails';

import { useAllowedActions } from '../hooks/useAllowedActions';
import { useArchiveRun } from '../hooks/useArchiveRun';
import { useUnarchiveRun } from '../hooks/useUnarchiveRun';
import {
  archiveButtonLabel,
  archiveConfirmLabel,
  archiveConsequence,
  archiveDialogTitle,
  archiveFields,
  archiveIntent,
  mapArchiveErrorCode,
  resolveArchiveMode,
} from '../runArchiveView';

export function RunArchiveControl({ workflowRunId }: { workflowRunId: string }) {
  const allowed = useAllowedActions(workflowRunId, 'workflow_owner');
  const archive = useArchiveRun(workflowRunId);
  const unarchive = useUnarchiveRun(workflowRunId);
  const [open, setOpen] = useState(false);

  const mode = resolveArchiveMode(allowed.data?.actions);
  if (mode === null) {
    return null;
  }

  const active = mode === 'archive' ? archive : unarchive;
  const pending = active.isPending;
  const errorMessage = isProblemDetailsError(active.error)
    ? mapArchiveErrorCode(active.error.code)
    : undefined;

  function handleConfirm(values: RationaleValues) {
    const reason = values.reason ?? '';
    if (mode === 'archive') {
      archive.mutate({ reason }, { onSuccess: () => setOpen(false) });
    } else {
      unarchive.mutate({ reason }, { onSuccess: () => setOpen(false) });
    }
  }

  return (
    <div data-testid="run-archive-control" className="flex flex-col gap-1">
      <div>
        <GovernedButton
          priority="secondary"
          workflowState={pending ? 'submitting' : undefined}
          onClick={() => setOpen(true)}
          testId="run-archive-button"
        >
          {archiveButtonLabel(mode)}
        </GovernedButton>
      </div>
      <RationaleCaptureDialog
        open={open}
        onOpenChange={setOpen}
        title={archiveDialogTitle(mode)}
        intent={archiveIntent(mode)}
        consequence={archiveConsequence(mode)}
        fields={archiveFields(mode)}
        confirmLabel={archiveConfirmLabel(mode)}
        onConfirm={handleConfirm}
        isConfirming={pending}
        testId="run-archive-dialog"
      >
        {errorMessage !== undefined ? (
          <p
            role="alert"
            data-testid="run-archive-error"
            className="text-meta text-state-error-foreground"
          >
            {errorMessage}
          </p>
        ) : null}
      </RationaleCaptureDialog>
    </div>
  );
}
