/**
 * Story 3d-7 (FR69, AC5/AC7) — `ProviderLimitStatus` Vitest coverage.
 *
 * Drives the one-shot provider-usage read through MSW across the available + not-exposed + absent +
 * error paths. Asserts the color-independent signal signifier (icon + label), the provider-reported
 * + as-of labelling, the "not exposed" degradation, the live-region announcement (via `waitFor` —
 * the announcer defers one commit, `livesnnouncement-defers-one-commit-test-flake`), and zero
 * `wcag2aa` axe violations.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { server } from '@/test/server';

import { ProviderLimitStatus } from './ProviderLimitStatus';

const PROVIDER_USAGE_URL = 'http://localhost/api/v1/workflows/:runId/provider-usage';

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderIndicator(workflowRunId: string) {
  return render(
    <QueryClientProvider client={freshClient()}>
      <ProviderLimitStatus workflowRunId={workflowRunId} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
});

describe('ProviderLimitStatus (story 3d-7)', () => {
  it('renders the available 5h + weekly windows, labelled provider-reported + as-of (AC5)', async () => {
    server.use(
      http.get(PROVIDER_USAGE_URL, () =>
        HttpResponse.json({
          present: true,
          signalState: 'available',
          accountReference: 'claude:oauth',
          fiveHour: { usedFraction: 0.62, used: 62, limit: 100, resetsAt: '2030-01-01T05:00:00Z' },
          weekly: { usedFraction: 0.18, used: 126, limit: 700, resetsAt: '2030-01-06T00:00:00Z' },
          asOf: '2026-06-23T09:05:00Z',
          capturedAt: '2026-06-23T09:05:01Z',
        }),
      ),
    );
    renderIndicator('run_provider000001');

    // Wait for the data to land (the windows are data-gated), then assert the signifier.
    await screen.findByTestId('provider-limit-5h');
    // Color-independent signal signifier (icon + label, never color alone).
    expect(screen.getByTestId('provider-limit-signal')).toHaveTextContent('Provider-reported');
    expect(screen.getByTestId('provider-limit-account')).toHaveTextContent('claude:oauth');
    expect(screen.getByTestId('provider-limit-5h')).toHaveTextContent('62/100');
    expect(screen.getByTestId('provider-limit-weekly')).toHaveTextContent('126/700');
    expect(screen.getByTestId('provider-limit-asof')).toHaveTextContent('Provider-reported');
    expect(screen.getByTestId('provider-limit-asof')).toHaveTextContent('2026-06-23T09:05:00Z');
    await waitFor(() =>
      expect(screen.getByTestId('provider-limit-announcer')).toHaveTextContent(
        'Provider usage status loaded.',
      ),
    );
  });

  it('degrades to the documented "not exposed" state without a fabricated number (AC1/AC5)', async () => {
    server.use(
      http.get(PROVIDER_USAGE_URL, () =>
        HttpResponse.json({
          present: true,
          signalState: 'not_exposed',
          accountReference: 'codex:subscription',
          fiveHour: null,
          weekly: null,
          asOf: null,
          capturedAt: '2026-06-23T09:05:01Z',
        }),
      ),
    );
    renderIndicator('run_provider000002');

    await screen.findByTestId('provider-limit-5h-not-exposed');
    expect(screen.getByTestId('provider-limit-signal')).toHaveTextContent(
      'Not exposed by provider',
    );
    expect(screen.getByTestId('provider-limit-5h-not-exposed')).toBeInTheDocument();
    expect(screen.getByTestId('provider-limit-weekly-not-exposed')).toBeInTheDocument();
    expect(screen.getByTestId('provider-limit-asof')).toHaveTextContent('does not expose');
    await waitFor(() =>
      expect(screen.getByTestId('provider-limit-announcer')).toHaveTextContent(
        'Provider usage status not exposed by the provider.',
      ),
    );
  });

  it('renders an empty state when no snapshot has been captured yet (AC7)', async () => {
    server.use(http.get(PROVIDER_USAGE_URL, () => HttpResponse.json({ present: false })));
    renderIndicator('run_provider000003');

    expect(await screen.findByTestId('provider-limit-empty')).toBeInTheDocument();
    expect(screen.getByTestId('provider-limit-signal')).toHaveTextContent('No snapshot yet');
  });

  it('surfaces an error state and announces it', async () => {
    server.use(http.get(PROVIDER_USAGE_URL, () => new HttpResponse(null, { status: 500 })));
    renderIndicator('run_provider000004');

    expect(await screen.findByTestId('provider-limit-error')).toBeInTheDocument();
    expect(screen.getByTestId('provider-limit-signal')).toHaveTextContent('Error');
    await waitFor(() =>
      expect(screen.getByTestId('provider-limit-announcer')).toHaveTextContent(
        'The provider usage status could not be loaded.',
      ),
    );
  });

  it('has zero WCAG 2.1 AA axe violations on the available indicator (AC7)', async () => {
    server.use(
      http.get(PROVIDER_USAGE_URL, () =>
        HttpResponse.json({
          present: true,
          signalState: 'available',
          accountReference: 'claude:oauth',
          fiveHour: { usedFraction: 0.62, used: 62, limit: 100, resetsAt: '2030-01-01T05:00:00Z' },
          weekly: { usedFraction: 0.18, used: 126, limit: 700, resetsAt: '2030-01-06T00:00:00Z' },
          asOf: '2026-06-23T09:05:00Z',
          capturedAt: '2026-06-23T09:05:01Z',
        }),
      ),
    );
    const { container } = renderIndicator('run_provider000005');
    await screen.findByTestId('provider-limit-signal');
    await expectNoA11yViolations(container);
  });
});
