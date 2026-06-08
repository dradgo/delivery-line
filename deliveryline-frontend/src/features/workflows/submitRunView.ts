/**
 * Story 2a.1 (Task 2, AC3/AC5) — pure view helpers for the Submit-a-Run form.
 *
 * Kept as a `.ts` (no JSX) so its non-component exports don't trip the
 * `react-refresh/only-export-components` rule that a feature `.tsx` enforces
 * (memory: frontend-react-refresh-no-fn-exports). Holds TWO pure concerns:
 *   • client field validation mirroring the backend constraints (AC3); and
 *   • the single-source button-state resolver with the project precedence (AC5),
 *     mirroring `resolveApprovalBarState`.
 */

import { isProblemDetailsError } from '@/lib/api/problemDetails';

/** Backend `@Size(max=128)` — the shared field-length ceiling for every text field. */
export const MAX_FIELD_LENGTH = 128;

/** The raw string values the form holds (all strings; the Select keeps `actorType` valid). */
export interface SubmitFormFields {
  linearTicketReference: string;
  actorIdentity: string;
  actorType: string;
  correlationId: string;
}

/** Per-field validation messages — a field is omitted when it is valid. */
export interface SubmitFieldErrors {
  linearTicketReference?: string;
  actorIdentity?: string;
  actorType?: string;
  correlationId?: string;
}

const ACTOR_TYPE_VALUES = new Set(['HUMAN', 'AGENT', 'SYSTEM', 'SERVICE_ACCOUNT']);

/** Validate one text field against the required + ≤128 constraints (mirrors the backend). */
function validateText(
  value: string,
  { required, label }: { required: boolean; label: string },
): string | undefined {
  if (required && value.trim().length === 0) {
    return `${label} is required.`;
  }
  if (value.length > MAX_FIELD_LENGTH) {
    return `${label} must be ${MAX_FIELD_LENGTH} characters or fewer.`;
  }
  return undefined;
}

/**
 * Client validation mirroring `SubmitWorkflowRequest`'s backend constraints (AC3).
 * This BLOCKS the submit and renders per-field messages; it does NOT replace the
 * server-side validation (which still runs and surfaces as a typed `VALIDATION_ERROR`).
 */
export function validateSubmitFields(fields: SubmitFormFields): SubmitFieldErrors {
  const errors: SubmitFieldErrors = {};

  const linearTicketReference = validateText(fields.linearTicketReference, {
    required: true,
    label: 'Linear ticket reference',
  });
  if (linearTicketReference !== undefined) {
    errors.linearTicketReference = linearTicketReference;
  }

  const actorIdentity = validateText(fields.actorIdentity, {
    required: true,
    label: 'Actor identity',
  });
  if (actorIdentity !== undefined) {
    errors.actorIdentity = actorIdentity;
  }

  if (!ACTOR_TYPE_VALUES.has(fields.actorType)) {
    errors.actorType = 'Select an actor type.';
  }

  // Correlation id is OPTIONAL — only the length ceiling applies when it is present.
  const correlationId = validateText(fields.correlationId, {
    required: false,
    label: 'Correlation id',
  });
  if (correlationId !== undefined) {
    errors.correlationId = correlationId;
  }

  return errors;
}

/** True when the field-error map is empty — the form may fire. */
export function isSubmitValid(errors: SubmitFieldErrors): boolean {
  return Object.keys(errors).length === 0;
}

/** The eight render states, ordered by the project precedence. */
export type SubmitButtonState =
  | 'locked'
  | 'error'
  | 'stale'
  | 'submitting'
  | 'blocked'
  | 'disabled'
  | 'success'
  | 'ready';

/** The mutation lifecycle the resolver reads (a subset of TanStack's `MutationStatus`). */
export type SubmitMutationStatus = 'idle' | 'pending' | 'success' | 'error';

export interface SubmitRunViewInput {
  /** The live mutation status. */
  mutationStatus: SubmitMutationStatus;
  /** Whether client validation currently passes (all required present + within limits). */
  validationValid: boolean;
  /**
   * A prior decision locked the control (precedence completeness; the CREATE form
   * never sets this today, but the resolver carries it so the precedence is total).
   */
  locked?: boolean;
  /** A UI-side staleness flag (precedence completeness; unused by the CREATE form today). */
  stale?: boolean;
  /** A deliberate control restriction independent of validation. */
  disabled?: boolean;
}

/**
 * Resolve the submit control's render state in ONE place, following the project
 * precedence `locked > error > stale > submitting > blocked > disabled > success >
 * ready` (AC5). Mirrors `resolveApprovalBarState`. For the CREATE form the live
 * levels are `error` / `submitting` / `blocked` / `success` / `ready`; `locked` /
 * `stale` / `disabled` are carried for precedence completeness and tested as such.
 */
export function resolveSubmitButtonState(input: SubmitRunViewInput): SubmitButtonState {
  if (input.locked === true) {
    return 'locked';
  }
  if (input.mutationStatus === 'error') {
    return 'error';
  }
  if (input.stale === true) {
    return 'stale';
  }
  if (input.mutationStatus === 'pending') {
    return 'submitting';
  }
  if (!input.validationValid) {
    return 'blocked';
  }
  if (input.disabled === true) {
    return 'disabled';
  }
  if (input.mutationStatus === 'success') {
    return 'success';
  }
  return 'ready';
}

/** The submit control is non-interactive while in flight or while inputs cannot fire. */
export function isSubmitControlDisabled(state: SubmitButtonState): boolean {
  return state === 'submitting' || state === 'blocked' || state === 'disabled';
}

/** The stable error code the failure surface branches on — `'transport'` for non-problem+json. */
export function submitErrorCode(error: unknown): string {
  return isProblemDetailsError(error) ? error.code : 'transport';
}

/**
 * Map a submit failure to a human message (AC7). Branches ONLY on the typed
 * `code` — NEVER on `error.message` or HTTP status text. Unknown domain codes and
 * transport failures fall through to generic, still-actionable copy.
 */
export function submitErrorMessage(error: unknown): string {
  if (!isProblemDetailsError(error)) {
    return 'Could not reach the server to submit this run. Check your connection and try again.';
  }
  switch (error.code) {
    case 'LINEAR_TICKET_NOT_FOUND':
      return 'That Linear ticket could not be found. Check the reference and try again.';
    case 'IDEMPOTENCY_KEY_CONFLICT':
      return 'This run was already submitted. Open the run queue to find it, or change the details and resubmit.';
    case 'MISSING_IDEMPOTENCY_KEY':
      return 'The submission was missing its idempotency key. Try submitting again.';
    case 'VALIDATION_ERROR':
      return 'The server rejected one or more fields. Review your entries and try again.';
    default:
      return 'Something went wrong submitting this run. Try again, and report it if it persists.';
  }
}
