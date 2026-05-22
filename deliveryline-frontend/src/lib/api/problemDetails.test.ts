/**
 * Story 2.6 (AC5, Task 7b) — typed Problem Details parsing.
 */
import { describe, expect, it } from 'vitest';

import {
  PROBLEM_JSON_CONTENT_TYPE,
  ProblemDetailsError,
  isProblemDetailsError,
  parseProblemDetails,
  toProblemDetails,
} from './problemDetails';

// Mirrors architecture.md:716–729 / story 1.8 catalog.
const RUN_NOT_FOUND_BODY = {
  type: 'https://deliveryline.local/problems/run-not-found',
  title: 'Workflow run not found',
  status: 404,
  detail: 'Workflow run not found: run_abc123',
  instance: '/api/v1/workflows/run_abc123',
  code: 'RUN_NOT_FOUND',
  retryable: false,
  correlationId: '3fa85f64-5717-4562-b3fc-2c963f66afa6',
};

function problemResponse(body: unknown, status = 404): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE },
  });
}

describe('parseProblemDetails', () => {
  it('parses a real problem+json body into typed code / status / retryable', async () => {
    const problem = await parseProblemDetails(problemResponse(RUN_NOT_FOUND_BODY));
    expect(problem).not.toBeNull();
    expect(problem?.code).toBe('RUN_NOT_FOUND');
    expect(problem?.status).toBe(404);
    expect(problem?.retryable).toBe(false);
    expect(problem?.correlationId).toBe('3fa85f64-5717-4562-b3fc-2c963f66afa6');
  });

  it('returns null for non-problem+json content types so callers fall through', async () => {
    const json = new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
    expect(await parseProblemDetails(json)).toBeNull();
  });
});

describe('ProblemDetailsError', () => {
  it('exposes stable typed accessors — never raw status text', () => {
    const error = new ProblemDetailsError(toProblemDetails(RUN_NOT_FOUND_BODY, 404));
    expect(error.code).toBe('RUN_NOT_FOUND');
    expect(error.status).toBe(404);
    expect(error.retryable).toBe(false);
    expect(isProblemDetailsError(error)).toBe(true);
    expect(isProblemDetailsError(new Error('plain'))).toBe(false);
  });
});

describe('toProblemDetails', () => {
  it('defends a malformed body into a usable typed error', () => {
    const problem = toProblemDetails(null, 500);
    expect(problem.code).toBe('INTERNAL_ERROR');
    expect(problem.status).toBe(500);
    expect(problem.retryable).toBe(false);
  });
});
