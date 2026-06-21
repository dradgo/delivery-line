/**
 * Story 3c-9 (Task 9, AC5/AC8/AC10) — `ConnectionTestControl`.
 *
 * Per-check rows render pass/fail/skipped with icon + text (not color alone); the
 * live announcer settles to the result string (own `waitFor` —
 * [[livesnnouncement-defers-one-commit-test-flake]]); a `PROJECT_NOT_FOUND` Problem
 * Detail renders an `ErrorState`; plus a wcag2aa axe scan.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { defaultProjectFixture } from '@/test/handlers';
import { expectNoA11yViolations } from '@/test/a11y/axe';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { ConnectionTestControl } from '../components/ConnectionTestControl';

const TEST_URL = 'http://localhost/api/v1/projects/prj_default/test-connection';

function freshClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderControl(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

const MIXED_RESULT = {
  checks: [
    { check: 'repository_reachable', status: 'pass', detail: 'reachable' },
    { check: 'ticket_source_auth', status: 'fail', detail: 'unauthorized' },
    { check: 'repository_host_auth', status: 'skipped', detail: 'no credential' },
  ],
};

beforeEach(() => {
  backSpy.mockClear();
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ConnectionTestControl', () => {
  it('AC5 — renders pass/fail/skipped rows (icon+text) and lifts the result up', async () => {
    server.use(http.post(TEST_URL, () => HttpResponse.json(MIXED_RESULT)));
    const onResult = vi.fn();
    const { container } = renderControl(
      <ConnectionTestControl project={defaultProjectFixture} canTest onResult={onResult} />,
    );

    fireEvent.click(screen.getByTestId('connection-test-button'));
    await waitFor(() => expect(screen.getByTestId('connection-test-result')).toBeInTheDocument());

    const rows = screen.getByTestId('connection-test-result');
    expect(rows.querySelector('[data-check="repository_reachable"]')).toHaveAttribute(
      'data-check-status',
      'pass',
    );
    expect(rows.querySelector('[data-check="ticket_source_auth"]')).toHaveAttribute(
      'data-check-status',
      'fail',
    );
    expect(rows.querySelector('[data-check="repository_host_auth"]')).toHaveAttribute(
      'data-check-status',
      'skipped',
    );
    // Status conveyed by text, not color alone.
    expect(rows).toHaveTextContent('Pass');
    expect(rows).toHaveTextContent('Fail');
    expect(rows).toHaveTextContent('Skipped');

    expect(onResult).toHaveBeenCalledTimes(1);
    await expectNoA11yViolations(container);
  });

  it('AC8 — the live announcer settles to the secret-free result summary', async () => {
    server.use(http.post(TEST_URL, () => HttpResponse.json(MIXED_RESULT)));
    renderControl(<ConnectionTestControl project={defaultProjectFixture} canTest />);

    fireEvent.click(screen.getByTestId('connection-test-button'));
    // The announcer lags by one commit (useLiveAnnouncement) — assert in its own waitFor.
    await waitFor(() =>
      expect(screen.getByTestId('connection-test-announcer')).toHaveTextContent(
        'Connection test complete: 1 passed, 1 failed, 1 skipped.',
      ),
    );
  });

  it('AC5 — a PROJECT_NOT_FOUND Problem Detail renders an ErrorState (not in-band)', async () => {
    server.use(
      http.post(TEST_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Project not found',
            status: 404,
            detail: 'gone',
            instance: TEST_URL,
            code: 'PROJECT_NOT_FOUND',
            retryable: false,
          },
          { status: 404, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    renderControl(<ConnectionTestControl project={defaultProjectFixture} canTest />);

    fireEvent.click(screen.getByTestId('connection-test-button'));
    const error = await screen.findByTestId('connection-test-error');
    expect(error).toHaveAttribute('data-error-code', 'PROJECT_NOT_FOUND');
    expect(screen.getByTestId('error-state')).toBeInTheDocument();
  });

  it('AC7 — no test affordance when test_connection is not allowed', () => {
    renderControl(<ConnectionTestControl project={defaultProjectFixture} canTest={false} />);
    expect(screen.queryByTestId('connection-test-button')).toBeNull();
  });
});
