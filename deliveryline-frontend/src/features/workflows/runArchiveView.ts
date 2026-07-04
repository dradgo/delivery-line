// runArchiveView.ts
/**
 * Pure view helpers for the run archive/unarchive control (story 3d-8 FE gap). Lives in a
 * sibling `.ts` (not the `.tsx`) so the component file exports only a component
 * (react-refresh-no-fn-exports). No React, no I/O — trivially unit-testable.
 */
import type { RationaleField } from '@/components/overlays/RationaleCaptureDialog';

export type RunArchiveMode = 'archive' | 'unarchive';

/** Reason cap — parity with backend `ArchiveRunRequest.reason @Size(max = 512)`. */
export const ARCHIVE_REASON_MAX_LENGTH = 512;

/**
 * The single advertised lifecycle action (the matrix emits exactly one for a
 * workflow_owner). Forward-compat: ignores unknown actions.
 */
export function resolveArchiveMode(actions: readonly string[] | undefined): RunArchiveMode | null {
  if (actions === undefined) {
    return null;
  }
  if (actions.includes('archive_run')) {
    return 'archive';
  }
  if (actions.includes('unarchive_run')) {
    return 'unarchive';
  }
  return null;
}

function reasonLengthError(value: string): string | undefined {
  return value.length > ARCHIVE_REASON_MAX_LENGTH
    ? `Reason must be ${ARCHIVE_REASON_MAX_LENGTH} characters or fewer`
    : undefined;
}

export function archiveFields(mode: RunArchiveMode): readonly RationaleField[] {
  if (mode === 'archive') {
    return [
      {
        name: 'reason',
        label: 'Reason',
        type: 'textarea',
        required: true,
        placeholder: 'Why is this run being hidden?',
        validate: reasonLengthError,
      },
    ];
  }
  return [
    {
      name: 'reason',
      label: 'Reason (optional)',
      type: 'textarea',
      required: false,
      placeholder: 'Optional note',
      validate: reasonLengthError,
    },
  ];
}

export function archiveButtonLabel(mode: RunArchiveMode): string {
  return mode === 'archive' ? 'Archive run' : 'Unarchive run';
}

export function archiveConfirmLabel(mode: RunArchiveMode): string {
  return mode === 'archive' ? 'Archive' : 'Unarchive';
}

export function archiveDialogTitle(mode: RunArchiveMode): string {
  return mode === 'archive' ? 'Archive run' : 'Unarchive run';
}

export function archiveIntent(mode: RunArchiveMode): 'warning' | 'info' {
  return mode === 'archive' ? 'warning' : 'info';
}

export function archiveConsequence(mode: RunArchiveMode): string {
  return mode === 'archive'
    ? 'This hides the run from the default review queue. The run stays fully accessible and can be unarchived at any time.'
    : 'This returns the run to the default review queue.';
}

export function mapArchiveErrorCode(code: string | undefined): string | undefined {
  switch (code) {
    case 'ARCHIVE_NOT_APPLICABLE':
      return "This run's hidden state changed — refresh and try again.";
    case 'IDEMPOTENCY_KEY_CONFLICT':
      return 'This action was already submitted — refresh to see the current state.';
    case 'RUN_NOT_FOUND':
      return 'This run no longer exists.';
    default:
      return undefined;
  }
}
