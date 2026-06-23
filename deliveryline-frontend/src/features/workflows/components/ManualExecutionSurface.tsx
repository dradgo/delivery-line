/**
 * Story 3d-4 (FR66, AC7) — the Manual Execution Surface.
 *
 * For a run parked in `WaitingForManualExecution`, this surface lets the operator (1) download AND
 * copy the emitted, already-redacted runner-contracts input bundle (so they can run the agent by
 * hand) and (2) submit the resulting runner-result-shaped artifact (paste OR file upload) back
 * through the SAME validation/review pipeline an automated runner's output takes.
 *
 * Gating (AC7 / Trap): the surface + its affordances are shown ONLY when the backend advertises the
 * `obtain_manual_bundle` / `submit_manual_artifact` actions (flowing through `useAllowedActions`,
 * never role-inferred — eslint `local-rules/no-role-based-action-gating`). The route resolves those
 * and passes them as `canObtainBundle` / `canSubmitArtifact`.
 *
 * a11y (AC7): explicit `<label>`s for every control, keyboard-operable, validation/Problem-Details
 * errors rendered inline in an `role="alert"`, and submit start/result announced through an
 * `aria-live` region (asserted via `waitFor` — the announcement defers one commit).
 */
import { useEffect, useState } from 'react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { manualArtifactRejected, manualArtifactSubmitting } from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import { isProblemDetailsError } from '@/lib/api/problemDetails';

import { useManualBundle, type ManualBundleResponse } from '../hooks/useManualBundle';
import { useSubmitManualArtifact } from '../hooks/useSubmitManualArtifact';

export interface ManualExecutionSurfaceProps {
  workflowRunId: string;
  /** Backend-advertised `obtain_manual_bundle` — gates the bundle download/copy (never role-derived). */
  canObtainBundle: boolean;
  /** Backend-advertised `submit_manual_artifact` — gates the submit affordance (never role-derived). */
  canSubmitArtifact: boolean;
  /**
   * Story 3d-4 re-review — fired once when a submit SUCCEEDS. This surface unmounts the instant the
   * run advances out of `WaitingForManualExecution` (the route re-gates on `currentState`), so a
   * success announcement rendered here would race the unmount; the run-detail route owns a PERSISTENT
   * live region and announces the result reliably from this callback (AC7 — announce submit result).
   */
  onSubmitted?: () => void;
}

/** Map a Problem-Details `code` to friendly operator text. Tests assert on `code`, never this copy. */
function friendlyError(error: unknown): string {
  if (isProblemDetailsError(error)) {
    switch (error.code) {
      case 'RUNNER_OUTPUT_VALIDATION_FAILED':
        return 'The artifact failed validation against the runner-output contract. Fix it and resubmit.';
      case 'RUNNER_ARTIFACT_TYPE_MISMATCH':
        return 'The artifact type does not match what this step expects. Fix it and resubmit.';
      case 'MANUAL_EXECUTION_NOT_APPLICABLE':
        return 'This run is no longer awaiting a manual artifact.';
      case 'IDEMPOTENCY_KEY_CONFLICT':
        return 'A different artifact was already submitted for this attempt. Refresh and retry.';
      case 'RUN_NOT_FOUND':
        return 'This run could not be found.';
      default:
        return 'The submission could not be completed. Review the error and resubmit.';
    }
  }
  return 'The submission could not be completed. Review the error and resubmit.';
}

function decodeBundle(bundle: ManualBundleResponse): string | null {
  if (!bundle.available || bundle.bundleBase64 == null) {
    return null;
  }
  try {
    // The bundle bytes are UTF-8 redacted JSON; base64 → bytes → UTF-8 text for copy/download.
    // (TextDecoder, not the deprecated escape/unescape idiom, so multi-byte content is not mangled.)
    const binary = atob(bundle.bundleBase64);
    const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
    return new TextDecoder('utf-8').decode(bytes);
  } catch {
    // Undecodable payload: surface nothing rather than hand the operator corrupted bytes.
    return null;
  }
}

// Cap the manual-artifact upload so a stray large file is not read wholesale into memory.
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

export function ManualExecutionSurface({
  workflowRunId,
  canObtainBundle,
  canSubmitArtifact,
  onSubmitted,
}: ManualExecutionSurfaceProps) {
  const bundleQuery = useManualBundle(workflowRunId, canObtainBundle);
  const submit = useSubmitManualArtifact(workflowRunId);

  const [payloadText, setPayloadText] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const bundle = bundleQuery.data;
  const bundleText = bundle !== undefined ? decodeBundle(bundle) : null;

  // Reset the "Copied" confirmation so a later copy re-confirms (and it never sticks forever).
  useEffect(() => {
    if (!copied) {
      return;
    }
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  // Announce submit START + ERROR here (both fire while the surface is reliably mounted: a pending
  // submit hasn't advanced the run, and a rejection leaves it parked). The SUCCESS announcement is
  // hoisted to the route via `onSubmitted` because a successful submit unmounts this surface.
  const announced = useLiveAnnouncement(
    submit.isPending
      ? manualArtifactSubmitting
      : submit.isError
        ? manualArtifactRejected
        : '',
  );

  // Signal the route (which owns a persistent live region) to announce the result, since this surface
  // unmounts the moment the run advances out of WaitingForManualExecution.
  useEffect(() => {
    if (submit.isSuccess) {
      onSubmitted?.();
    }
  }, [submit.isSuccess, onSubmitted]);

  function handleDownload() {
    if (bundleText == null) {
      return;
    }
    const blob = new Blob([bundleText], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${workflowRunId}-manual-bundle.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  async function handleCopy() {
    if (bundleText == null) {
      return;
    }
    try {
      await navigator.clipboard.writeText(bundleText);
      setCopied(true);
      setLocalError(null);
    } catch {
      setLocalError('The bundle could not be copied to the clipboard. Use download instead.');
    }
  }

  function readFile(file: File) {
    if (file.size > MAX_UPLOAD_BYTES) {
      setLocalError('The selected file is too large to submit (max 5 MiB).');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      setPayloadText(typeof reader.result === 'string' ? reader.result : '');
      setLocalError(null);
    };
    reader.onerror = () => {
      setLocalError('The selected file could not be read. Try again or paste the artifact.');
    };
    reader.readAsText(file);
  }

  function handleSubmit() {
    setLocalError(null);
    let result: unknown;
    try {
      result = JSON.parse(payloadText);
    } catch {
      setLocalError('The artifact is not valid JSON. Paste or upload a runner-result document.');
      return;
    }
    submit.mutate({ result });
  }

  return (
    <section
      aria-label="Manual execution"
      data-testid="manual-execution-surface"
      className="w-full rounded-md border border-border bg-surface p-4"
    >
      <h2 className="mb-1 text-section-title">Manual execution</h2>
      <p className="mb-3 text-meta text-text-tertiary">
        This run is parked for manual execution. Download the input bundle, run the agent yourself,
        then submit the resulting artifact below.
      </p>

      {canObtainBundle ? (
        <div className="mb-4" data-testid="manual-bundle-region">
          <h3 className="mb-1 text-meta uppercase tracking-wide text-text-tertiary">Input bundle</h3>
          {bundleQuery.isPending ? (
            <p className="text-meta text-text-tertiary">Loading the input bundle…</p>
          ) : bundleQuery.isError ? (
            // Distinct from the typed `bundleNotPersisted` degrade below — a transient load failure is
            // retryable and must NOT read as a permanent eviction.
            <Alert variant="destructive" data-testid="manual-bundle-error">
              <AlertDescription>
                The input bundle could not be loaded. Refresh to try again.
              </AlertDescription>
            </Alert>
          ) : bundle?.available === true ? (
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                data-testid="manual-bundle-download"
                onClick={handleDownload}
              >
                Download bundle
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                data-testid="manual-bundle-copy"
                onClick={() => void handleCopy()}
              >
                {copied ? 'Copied' : 'Copy bundle'}
              </Button>
              <span className="text-meta text-text-tertiary">
                version {bundle.contextBundleVersion ?? '—'}
              </span>
            </div>
          ) : (
            <p data-testid="manual-bundle-unavailable" className="text-meta text-text-tertiary">
              The input bundle is no longer available
              {bundle?.unavailableReason != null ? ` (${bundle.unavailableReason})` : ''}.
            </p>
          )}
        </div>
      ) : null}

      {/* localError covers BOTH clipboard/copy failures from the bundle region AND file/JSON errors
          from the submit region — render it independently of the submit gating so a bundle-only
          operator still sees copy failures inline (AC7 — errors shown inline). */}
      {localError !== null ? (
        <Alert variant="destructive" className="mb-3" data-testid="manual-artifact-local-error">
          <AlertDescription>{localError}</AlertDescription>
        </Alert>
      ) : null}

      {canSubmitArtifact ? (
        <div data-testid="manual-submit-region">
          <label htmlFor="manual-artifact-payload" className="mb-1 block text-meta font-medium">
            Manual artifact (runner-result JSON)
          </label>
          <Textarea
            id="manual-artifact-payload"
            data-testid="manual-artifact-payload"
            className="min-h-32 font-mono text-xs"
            value={payloadText}
            spellCheck={false}
            onChange={(event) => setPayloadText(event.target.value)}
            placeholder='{ "schemaVersion": 1, "workflowRunId": "run_…", … }'
          />
          <div className="mt-2 flex items-center gap-3">
            <label htmlFor="manual-artifact-file" className="text-meta text-text-secondary">
              or upload a file
            </label>
            <input
              id="manual-artifact-file"
              data-testid="manual-artifact-file"
              type="file"
              accept="application/json,.json"
              className="text-meta"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file !== undefined) {
                  readFile(file);
                }
              }}
            />
          </div>

          {submit.isError ? (
            <Alert variant="destructive" className="mt-3" data-testid="manual-artifact-error">
              <AlertTitle>Submission rejected</AlertTitle>
              <AlertDescription>{friendlyError(submit.error)}</AlertDescription>
            </Alert>
          ) : null}

          {submit.isSuccess ? (
            <Alert className="mt-3" data-testid="manual-artifact-success">
              <AlertDescription>
                Manual artifact submitted. The run advanced to{' '}
                <code>{submit.data?.currentState}</code>.
              </AlertDescription>
            </Alert>
          ) : null}

          <Button
            type="button"
            className="mt-3"
            data-testid="manual-artifact-submit"
            disabled={submit.isPending || payloadText.trim().length === 0}
            onClick={handleSubmit}
          >
            {submit.isPending ? 'Submitting…' : 'Submit manual artifact'}
          </Button>
        </div>
      ) : null}

      {/* AC7 — submit start/result announced via an aria-live region. */}
      <div role="status" aria-live="polite" className="sr-only" data-testid="manual-submit-announcer">
        {announced}
      </div>
    </section>
  );
}
