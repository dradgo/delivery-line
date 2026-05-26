/**
 * Story 2.6 (Task 8 / TRAP 2) — the client middleware attaches the cross-cutting
 * headers: X-Correlation-Id on every request, Idempotency-Key on mutations only.
 */
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { server } from '@/test/server';
import { HttpResponseError, apiClient, unwrap } from './client';
import { CORRELATION_ID_HEADER } from './correlation';
import { IDEMPOTENCY_KEY_HEADER } from './idempotency';
import { ProblemDetailsError } from './problemDetails';

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

describe('apiClient header middleware', () => {
  it('attaches X-Correlation-Id to a GET (read) request', async () => {
    let seen: string | null = null;
    server.use(
      http.get('http://localhost/api/v1/workflows', ({ request }) => {
        seen = request.headers.get(CORRELATION_ID_HEADER);
        return HttpResponse.json([]);
      }),
    );

    await apiClient.GET('/api/v1/workflows', { params: { query: {} } });

    expect(seen).toMatch(UUID_SHAPE);
  });

  it('attaches BOTH correlation and idempotency headers to a mutation (POST)', async () => {
    let correlation: string | null = null;
    let idempotency: string | null = null;
    server.use(
      http.post('http://localhost/api/v1/workflows/:id/approve-spec', ({ request }) => {
        correlation = request.headers.get(CORRELATION_ID_HEADER);
        idempotency = request.headers.get(IDEMPOTENCY_KEY_HEADER);
        return HttpResponse.json({ workflowRunId: 'run_abcd', currentState: 'Executing' });
      }),
    );

    // Story 2.13: Idempotency-Key header is now declared required in the OpenAPI
    // contract. The middleware still auto-attaches a UUIDv7 fallback (asserted by
    // the assertions below) — pass an empty placeholder header to satisfy the
    // generated TS types; the middleware overwrites it when no real key is supplied.
    await apiClient.POST('/api/v1/workflows/{workflowRunId}/approve-spec', {
      params: {
        path: { workflowRunId: 'run_abcd' },
        header: { 'Idempotency-Key': '' },
      },
      body: {
        artifactId: 'art_abcd',
        expectedArtifactVersion: 1,
        expectedContextBundleVersion: 1,
      },
    });

    expect(correlation).toMatch(UUID_SHAPE);
    // Middleware fallback minted a v7 idempotency key (no explicit key supplied here).
    expect(idempotency).toMatch(UUID_SHAPE);
    expect((idempotency as unknown as string)[14]).toBe('7');
  });

  it('does NOT attach an idempotency key to a GET', async () => {
    let idempotency: string | null = 'unset';
    server.use(
      http.get('http://localhost/api/v1/workflows/:id', ({ request }) => {
        idempotency = request.headers.get(IDEMPOTENCY_KEY_HEADER);
        return HttpResponse.json({ workflowRunId: 'run_abcd' });
      }),
    );

    await apiClient.GET('/api/v1/workflows/{workflowRunId}', {
      params: { path: { workflowRunId: 'run_abcd' } },
    });

    expect(idempotency).toBeNull();
  });

  it('keeps non-problem HTTP failures generic so retry policy can treat them as transient', () => {
    const response = new Response('bad gateway', {
      status: 502,
      statusText: 'Bad Gateway',
      headers: { 'content-type': 'text/plain' },
    });

    try {
      unwrap({ response, error: 'bad gateway' });
      throw new Error('expected unwrap to throw');
    } catch (error) {
      expect(error).toBeInstanceOf(HttpResponseError);
      expect(error).not.toBeInstanceOf(ProblemDetailsError);
      expect((error as HttpResponseError).status).toBe(502);
    }
  });
});
