/**
 * Story 2.20 (AC5) — `queueErrorMessage` maps codes/transport to fixed copy and
 * NEVER echoes the raw error message (Trap T3).
 */
import { describe, expect, it } from 'vitest';

import { ProblemDetailsError, toProblemDetails } from '@/lib/api/problemDetails';
import { queueErrorMessage } from '../queueErrorMessage';

function problem(code: string): ProblemDetailsError {
  return new ProblemDetailsError(
    toProblemDetails({ type: 'about:blank', title: 't', status: 500, code, retryable: true }, 500),
  );
}

describe('queueErrorMessage', () => {
  it('maps known domain codes to their fixed strings', () => {
    expect(queueErrorMessage(problem('INTERNAL_ERROR'))).toBe(
      'The server had a problem loading the review queue.',
    );
    expect(queueErrorMessage(problem('VALIDATION_ERROR'))).toBe(
      'The queue request was rejected as invalid.',
    );
  });

  it('falls back to a generic line for an unrecognized code', () => {
    expect(queueErrorMessage(problem('SOME_FUTURE_CODE'))).toBe(
      'Something went wrong loading the review queue.',
    );
  });

  it('uses the transport line for a non-ProblemDetails error and never leaks its message', () => {
    const raw = new Error('connect ECONNREFUSED 127.0.0.1:8080 — secret-ish internals');
    const message = queueErrorMessage(raw);
    expect(message).toBe('Couldn’t reach the server to load the review queue.');
    expect(message).not.toContain('ECONNREFUSED');
  });
});
