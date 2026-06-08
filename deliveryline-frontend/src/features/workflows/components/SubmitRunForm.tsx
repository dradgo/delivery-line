/**
 * Story 2a.1 (Task 3, AC3/AC6/AC7/AC9) — the "Submit a Run" form.
 *
 * A CREATE form over the EXISTING `POST /api/v1/workflows/submit-workflow`. Manual
 * `useState` (no react-hook-form/zod — not in deps); validation, the button-state
 * resolver, and the error-message map are all PURE helpers in the sibling
 * `submitRunView.ts` (a `.ts`, so the feature `.tsx` exports only the component and
 * does not trip `react-refresh/only-export-components`).
 *
 * Feedback surfaces:
 *   • AC6 — a PERSISTENT (non-toast) success `Alert` showing the new `workflowRunId`,
 *     `currentState`, and a `<Link>` to the run detail. Every response field is
 *     OPTIONAL in the generated type, so each is guarded and degrades gracefully.
 *   • AC7 — failures render through the shared `<ErrorState>`, branching only on the
 *     typed `code` (never `message`). The error-state retry REUSES the failed
 *     attempt's idempotency key; a fresh form submit mints a new one (the hook owns
 *     the key lifecycle).
 *
 * AC9 — field-only structured logs: `run.submitAttempt` / `run.submitSuccess` /
 * `run.submitError`. NEVER logs `linearTicketReference`, `actorIdentity`,
 * `correlationId`, the idempotency key, or `error.message`.
 */
import { useEffect, useRef, useState } from 'react';
import { Link } from '@tanstack/react-router';
import { CircleCheck } from 'lucide-react';

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { ErrorState } from '@/components/feedback';

import { ACTOR_TYPES, useSubmitWorkflow, type ActorType } from '../hooks/useSubmitWorkflow';
import {
  isSubmitControlDisabled,
  resolveSubmitButtonState,
  submitErrorCode,
  submitErrorMessage,
  validateSubmitFields,
  type SubmitFieldErrors,
  type SubmitMutationStatus,
} from '../submitRunView';

/** Human-readable labels for each actor type (the wire value stays the enum string). */
const ACTOR_TYPE_LABELS: Record<ActorType, string> = {
  HUMAN: 'Human',
  AGENT: 'Agent',
  SYSTEM: 'System',
  SERVICE_ACCOUNT: 'Service account',
};

/** A single labelled field-error message (rendered only after a submit attempt). */
function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (message === undefined) {
    return null;
  }
  return (
    <p id={id} className="text-sm text-state-error-foreground" role="alert">
      {message}
    </p>
  );
}

export function SubmitRunForm() {
  const [linearTicketReference, setLinearTicketReference] = useState('');
  const [actorIdentity, setActorIdentity] = useState('');
  const [actorType, setActorType] = useState<ActorType>('HUMAN');
  const [correlationId, setCorrelationId] = useState('');
  // Per-field messages stay hidden until the first submit attempt (then live-update).
  const [showErrors, setShowErrors] = useState(false);

  const submitWorkflow = useSubmitWorkflow();
  const { status, data, error } = submitWorkflow;

  const fields = { linearTicketReference, actorIdentity, actorType, correlationId };
  const errors: SubmitFieldErrors = validateSubmitFields(fields);
  const valid = Object.keys(errors).length === 0;
  const visibleErrors: SubmitFieldErrors = showErrors ? errors : {};

  // Editing any field after a terminal submit clears the prior success/error surface
  // so the displayed runId/error never describes a stale, now-edited attempt — and
  // re-enables the submit control (which is disabled on `success`, see below).
  const resetIfSettled = () => {
    if (status === 'success' || status === 'error') {
      submitWorkflow.reset();
    }
  };

  const buttonState = resolveSubmitButtonState({
    mutationStatus: status,
    validationValid: valid,
  });
  // The DOM control is disabled while a submit is in flight (prevent double-submit)
  // AND after a success (a fresh click would mint a NEW idempotency key and create a
  // DUPLICATE run — editing a field resets the mutation and re-enables it). A
  // `blocked` state keeps the button clickable so the click reveals the per-field
  // messages (AC3) while `handleSubmit` blocks the network.
  const submitDisabled =
    (isSubmitControlDisabled(buttonState) && buttonState !== 'blocked') || status === 'success';

  // AC9 — log success/error EXACTLY once per status transition. A ref (not a render
  // log) prevents re-firing on unrelated re-renders (lineage: ErrorState's effect).
  const loggedStatusRef = useRef<SubmitMutationStatus>('idle');
  useEffect(() => {
    if (status === loggedStatusRef.current) {
      return;
    }
    if (status === 'success') {
      console.info({ event: 'run.submitSuccess', currentState: data?.currentState });
    } else if (status === 'error') {
      console.warn({
        event: 'run.submitError',
        code: submitErrorCode(error),
        transport: submitErrorCode(error) === 'transport',
      });
    }
    loggedStatusRef.current = status;
  }, [status, data, error]);

  const handleSubmit = (formEvent: React.FormEvent<HTMLFormElement>) => {
    formEvent.preventDefault();
    setShowErrors(true);
    // Re-validate at fire time so a blank/over-128 field BLOCKS with no network call.
    if (Object.keys(validateSubmitFields(fields)).length > 0) {
      return;
    }
    console.info({ event: 'run.submitAttempt' });
    submitWorkflow.submit({
      linearTicketReference,
      actorIdentity,
      actorType,
      // Omit the optional field when blank (mirror the hook's optional-field spread).
      correlationId: correlationId.trim().length > 0 ? correlationId : undefined,
    });
  };

  // AC7 — a retry from the error surface REUSES the failed attempt's idempotency key.
  const handleRetry = () => {
    console.info({ event: 'run.submitAttempt' });
    submitWorkflow.retry();
  };

  const showSuccess = status === 'success' && data !== undefined;
  const runId = data?.workflowRunId;

  return (
    <div className="flex max-w-prose flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-page-title">Submit a run</h1>
        <p className="text-sm text-text-secondary">
          Start a governed run from a Linear ticket. The run enters the queue for review.
        </p>
      </header>

      <form className="flex flex-col gap-5" onSubmit={handleSubmit} noValidate>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="linearTicketReference">Linear ticket reference</Label>
          <Input
            id="linearTicketReference"
            name="linearTicketReference"
            value={linearTicketReference}
            maxLength={512}
            aria-required="true"
            aria-invalid={visibleErrors.linearTicketReference !== undefined}
            aria-describedby={
              visibleErrors.linearTicketReference !== undefined
                ? 'linearTicketReference-error'
                : undefined
            }
            onChange={(event) => {
              resetIfSettled();
              setLinearTicketReference(event.target.value);
            }}
          />
          <FieldError
            id="linearTicketReference-error"
            message={visibleErrors.linearTicketReference}
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="actorIdentity">Actor identity</Label>
          <Input
            id="actorIdentity"
            name="actorIdentity"
            value={actorIdentity}
            maxLength={512}
            aria-required="true"
            aria-invalid={visibleErrors.actorIdentity !== undefined}
            aria-describedby={
              visibleErrors.actorIdentity !== undefined ? 'actorIdentity-error' : undefined
            }
            onChange={(event) => {
              resetIfSettled();
              setActorIdentity(event.target.value);
            }}
          />
          <FieldError id="actorIdentity-error" message={visibleErrors.actorIdentity} />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="actorType">Actor type</Label>
          <Select
            value={actorType}
            onValueChange={(value) => {
              resetIfSettled();
              setActorType(value as ActorType);
            }}
          >
            <SelectTrigger
              id="actorType"
              aria-label="Actor type"
              aria-invalid={visibleErrors.actorType !== undefined}
              aria-describedby={
                visibleErrors.actorType !== undefined ? 'actorType-error' : undefined
              }
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {ACTOR_TYPES.map((value) => (
                <SelectItem key={value} value={value}>
                  {ACTOR_TYPE_LABELS[value]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FieldError id="actorType-error" message={visibleErrors.actorType} />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="correlationId">Correlation id (optional)</Label>
          <Input
            id="correlationId"
            name="correlationId"
            value={correlationId}
            maxLength={512}
            aria-invalid={visibleErrors.correlationId !== undefined}
            aria-describedby={
              visibleErrors.correlationId !== undefined ? 'correlationId-error' : undefined
            }
            onChange={(event) => {
              resetIfSettled();
              setCorrelationId(event.target.value);
            }}
          />
          <FieldError id="correlationId-error" message={visibleErrors.correlationId} />
        </div>

        <div>
          <Button
            type="submit"
            disabled={submitDisabled}
            aria-disabled={submitDisabled}
            data-submit-state={buttonState}
            data-testid="submit-run-button"
          >
            {buttonState === 'submitting' ? 'Submitting…' : 'Submit run'}
          </Button>
        </div>
      </form>

      {showSuccess ? (
        <Alert
          className="border-state-success-border bg-state-success text-state-success-foreground [&>svg]:!text-state-success-foreground"
          data-testid="submit-success"
        >
          <CircleCheck className="size-4" aria-hidden />
          <AlertTitle>Run submitted</AlertTitle>
          <AlertDescription className="flex flex-col gap-2 text-text-secondary">
            <span>
              {runId !== undefined ? (
                <>
                  Run <span className="font-medium text-text-primary">{runId}</span> was created
                </>
              ) : (
                'Your run was created'
              )}
              {data.currentState !== undefined ? (
                <>
                  {' '}
                  — current state{' '}
                  <span className="font-medium text-text-primary">{data.currentState}</span>
                </>
              ) : null}
              .
            </span>
            {runId !== undefined ? (
              <Link
                to="/workflows/$workflowRunId"
                params={{ workflowRunId: runId }}
                className="text-sm font-medium text-text-primary underline underline-offset-4"
                data-testid="submit-success-detail-link"
              >
                View the run
              </Link>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}

      {status === 'error' ? (
        <div data-testid="submit-error" data-error-code={submitErrorCode(error)}>
          <ErrorState
            variant="failedRetrieval"
            urgency="active"
            title="Couldn't submit this run"
            message={submitErrorMessage(error)}
            nextAction={{ kind: 'Retry', label: 'Try again', onRetry: handleRetry }}
          />
        </div>
      ) : null}
    </div>
  );
}
