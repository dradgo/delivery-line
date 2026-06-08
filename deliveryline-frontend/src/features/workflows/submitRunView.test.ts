/**
 * Story 2a.1 (Task 6, AC3/AC5/AC7) — unit tests for the `submitRunView` pure helpers.
 *
 * Covers: client field validation mirroring the backend constraints (AC3),
 * button-state precedence with one case per level (AC5), and the code-only error
 * mapping (AC7 — never branches on `message`).
 */
import { describe, expect, it } from 'vitest';

import { ProblemDetailsError } from '@/lib/api/problemDetails';
import {
  MAX_FIELD_LENGTH,
  isSubmitControlDisabled,
  isSubmitValid,
  resolveSubmitButtonState,
  submitErrorCode,
  submitErrorMessage,
  validateSubmitFields,
  type SubmitFormFields,
} from './submitRunView';

const VALID_FIELDS: SubmitFormFields = {
  linearTicketReference: 'LIN-123',
  actorIdentity: 'alex@example.com',
  actorType: 'HUMAN',
  correlationId: '',
};

const OVER_LIMIT = 'a'.repeat(MAX_FIELD_LENGTH + 1);

function problem(code: string): ProblemDetailsError {
  return new ProblemDetailsError({
    type: 'about:blank',
    title: code,
    status: 400,
    detail: '',
    instance: '',
    code,
    retryable: false,
    correlationId: null,
  });
}

describe('validateSubmitFields (AC3 — mirrors backend @NotBlank @Size(max=128))', () => {
  it('passes a fully valid field set', () => {
    expect(validateSubmitFields(VALID_FIELDS)).toEqual({});
    expect(isSubmitValid(validateSubmitFields(VALID_FIELDS))).toBe(true);
  });

  it('flags a blank required linearTicketReference (whitespace-only is blank)', () => {
    const errors = validateSubmitFields({ ...VALID_FIELDS, linearTicketReference: '   ' });
    expect(errors.linearTicketReference).toMatch(/required/i);
    expect(isSubmitValid(errors)).toBe(false);
  });

  it('flags a blank required actorIdentity', () => {
    const errors = validateSubmitFields({ ...VALID_FIELDS, actorIdentity: '' });
    expect(errors.actorIdentity).toMatch(/required/i);
  });

  it('flags an over-128 linearTicketReference', () => {
    const errors = validateSubmitFields({ ...VALID_FIELDS, linearTicketReference: OVER_LIMIT });
    expect(errors.linearTicketReference).toMatch(/128 characters or fewer/i);
  });

  it('flags an over-128 optional correlationId but allows a blank one', () => {
    expect(
      validateSubmitFields({ ...VALID_FIELDS, correlationId: '' }).correlationId,
    ).toBeUndefined();
    expect(
      validateSubmitFields({ ...VALID_FIELDS, correlationId: OVER_LIMIT }).correlationId,
    ).toMatch(/128 characters or fewer/i);
  });

  it('flags an actorType outside the enum', () => {
    const errors = validateSubmitFields({ ...VALID_FIELDS, actorType: 'ROBOT' });
    expect(errors.actorType).toMatch(/actor type/i);
  });

  it('accepts every valid ActorType enum value', () => {
    for (const actorType of ['HUMAN', 'AGENT', 'SYSTEM', 'SERVICE_ACCOUNT']) {
      expect(validateSubmitFields({ ...VALID_FIELDS, actorType }).actorType).toBeUndefined();
    }
  });
});

describe('resolveSubmitButtonState (AC5 — precedence locked > error > stale > submitting > blocked > disabled > success > ready)', () => {
  it('locked wins over everything', () => {
    expect(
      resolveSubmitButtonState({
        mutationStatus: 'error',
        validationValid: false,
        locked: true,
        stale: true,
      }),
    ).toBe('locked');
  });

  it('error wins over stale', () => {
    expect(
      resolveSubmitButtonState({ mutationStatus: 'error', validationValid: true, stale: true }),
    ).toBe('error');
  });

  it('stale wins over submitting', () => {
    expect(
      resolveSubmitButtonState({ mutationStatus: 'pending', validationValid: true, stale: true }),
    ).toBe('stale');
  });

  it('submitting wins over blocked', () => {
    expect(resolveSubmitButtonState({ mutationStatus: 'pending', validationValid: false })).toBe(
      'submitting',
    );
  });

  it('blocked (invalid fields) wins over disabled', () => {
    expect(
      resolveSubmitButtonState({ mutationStatus: 'idle', validationValid: false, disabled: true }),
    ).toBe('blocked');
  });

  it('disabled wins over success', () => {
    expect(
      resolveSubmitButtonState({
        mutationStatus: 'success',
        validationValid: true,
        disabled: true,
      }),
    ).toBe('disabled');
  });

  it('success when valid + idle and the mutation has landed', () => {
    expect(resolveSubmitButtonState({ mutationStatus: 'success', validationValid: true })).toBe(
      'success',
    );
  });

  it('ready when valid + idle', () => {
    expect(resolveSubmitButtonState({ mutationStatus: 'idle', validationValid: true })).toBe(
      'ready',
    );
  });

  it('isSubmitControlDisabled disables submitting/blocked/disabled, enables ready/success/error', () => {
    expect(isSubmitControlDisabled('submitting')).toBe(true);
    expect(isSubmitControlDisabled('blocked')).toBe(true);
    expect(isSubmitControlDisabled('disabled')).toBe(true);
    expect(isSubmitControlDisabled('ready')).toBe(false);
    expect(isSubmitControlDisabled('success')).toBe(false);
    expect(isSubmitControlDisabled('error')).toBe(false);
  });
});

describe('submitErrorCode / submitErrorMessage (AC7 — branch on code, never message)', () => {
  it('returns the typed code for a problem+json error', () => {
    expect(submitErrorCode(problem('LINEAR_TICKET_NOT_FOUND'))).toBe('LINEAR_TICKET_NOT_FOUND');
  });

  it('returns "transport" for a non-problem error', () => {
    expect(submitErrorCode(new Error('boom'))).toBe('transport');
  });

  it('maps known domain codes to distinct messages', () => {
    expect(submitErrorMessage(problem('LINEAR_TICKET_NOT_FOUND'))).toMatch(
      /ticket could not be found/i,
    );
    expect(submitErrorMessage(problem('IDEMPOTENCY_KEY_CONFLICT'))).toMatch(/already submitted/i);
  });

  it('falls back to generic copy for an unknown domain code', () => {
    expect(submitErrorMessage(problem('SOME_FUTURE_CODE'))).toMatch(/something went wrong/i);
  });

  it('uses transport copy for a non-problem error (no message leak)', () => {
    const message = submitErrorMessage(new Error('secret transport detail'));
    expect(message).toMatch(/could not reach the server/i);
    expect(message).not.toContain('secret transport detail');
  });
});
