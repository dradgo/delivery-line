/**
 * Story 3c-9 (Task 9, AC2/AC7/AC10) — `ProjectList` renders exactly one of
 * loading / empty / populated / error, gates row actions on `allowedActions`, and
 * passes a wcag2aa axe scan on each documented state (mirrors `QueueShell.test`).
 *
 * `useReturnToRunContext` (consumed by `<ErrorState>`) is mocked at its own module so
 * the file stays isolated from `@tanstack/react-router` internals (memory:
 * [[vitest-cross-file-router-mock]]).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import type { Project } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { defaultProjectFixture } from '@/test/handlers';
import { expectNoA11yViolations } from '@/test/a11y/axe';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { ProjectList } from '../components/ProjectList';

const PROJECTS_URL = 'http://localhost/api/v1/projects';

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderList(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

function noop() {
  /* test stub */
}

function defaultList() {
  return <ProjectList onEdit={noop} testResults={{}} onTestResult={noop} />;
}

function problemResponse() {
  return new HttpResponse(
    JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', retryable: true, title: 'x' }),
    { status: 500, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
  );
}

beforeEach(() => {
  backSpy.mockClear();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ProjectList', () => {
  it('AC2 — shows the typed loading state on first paint', async () => {
    server.use(
      http.get(PROJECTS_URL, async () => {
        await delay(20);
        return HttpResponse.json([]);
      }),
    );
    const { container } = renderList(defaultList());
    expect(screen.getByTestId('project-list-loading')).toBeInTheDocument();
    expect(screen.getByTestId('loading-state')).toHaveAttribute('data-variant', 'fetchingData');
    await waitFor(() => expect(screen.getByTestId('empty-state')).toBeInTheDocument());
    await expectNoA11yViolations(container);
  });

  it('AC2 — empty list renders the empty state', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([])));
    const { container } = renderList(defaultList());
    await waitFor(() => expect(screen.getByTestId('empty-state')).toBeInTheDocument());
    await expectNoA11yViolations(container);
  });

  it('AC2/AC7 — populated list shows status, kinds, credential presence, gated actions', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([defaultProjectFixture])));
    const { container } = renderList(defaultList());

    await waitFor(() => expect(screen.getByTestId('project-list-table')).toBeInTheDocument());
    const row = screen.getByTestId('project-row');
    expect(row).toHaveAttribute('data-project-id', 'prj_default');
    // Status is icon + text (non-color signifier).
    expect(row.querySelector('[data-project-status="active"]')).not.toBeNull();
    expect(row).toHaveTextContent('Active');
    // Kinds are human-labelled.
    expect(row).toHaveTextContent('Linear');
    expect(row).toHaveTextContent('GitHub');
    // Credential presence per role.
    expect(row.querySelector('[data-credential-status="configured"]')).not.toBeNull();
    expect(row.querySelector('[data-credential-status="not_configured"]')).not.toBeNull();
    // AC7 — the default project advertises edit/set_credential/test_connection,
    // never disable or enable.
    expect(screen.getByTestId('project-edit-button')).toBeInTheDocument();
    expect(screen.queryByTestId('project-disable-button')).toBeNull();
    expect(screen.queryByTestId('project-enable-button')).toBeNull();
    expect(screen.getByTestId('connection-test-button')).toBeInTheDocument();
    // Not tested yet (R4 — session-scoped).
    expect(row).toHaveTextContent('Not tested');
    await expectNoA11yViolations(container);
  });

  it('AC7 — a disabled project advertises enable (not disable)', async () => {
    const disabled: Project = {
      ...defaultProjectFixture,
      id: 'prj_other',
      slug: 'other',
      name: 'Other',
      status: 'disabled',
      allowedActions: ['edit', 'enable', 'set_credential', 'test_connection'],
    };
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([disabled])));
    renderList(defaultList());
    await waitFor(() => expect(screen.getByTestId('project-row')).toBeInTheDocument());
    expect(screen.getByTestId('project-enable-button')).toBeInTheDocument();
    expect(screen.queryByTestId('project-disable-button')).toBeNull();
    expect(screen.getByText('Disabled')).toBeInTheDocument();
  });

  it('AC2 — repository URL renders an em-dash empty state when null', async () => {
    server.use(
      http.get(PROJECTS_URL, () =>
        HttpResponse.json([{ ...defaultProjectFixture, repositoryUrl: null }]),
      ),
    );
    renderList(defaultList());
    await waitFor(() => expect(screen.getByTestId('project-row')).toBeInTheDocument());
    expect(screen.getByLabelText('No repository URL')).toBeInTheDocument();
  });

  it('AC2 — shows a session-scoped last-test summary when a result is present (R4)', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([defaultProjectFixture])));
    render(
      <QueryClientProvider client={freshClient()}>
        <ProjectList
          onEdit={noop}
          onTestResult={noop}
          testResults={{
            prj_default: {
              result: {
                checks: [
                  { check: 'repository_reachable', status: 'pass', detail: 'ok' },
                  { check: 'ticket_source_auth', status: 'fail', detail: 'bad' },
                  { check: 'repository_host_auth', status: 'skipped', detail: 'n/a' },
                ],
              },
              testedAt: '2026-06-21T10:00:00Z',
            },
          }}
        />
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('project-last-test')).toBeInTheDocument());
    expect(screen.getByTestId('project-last-test')).toHaveTextContent(
      '1 passed, 1 failed, 1 skipped',
    );
  });

  it('AC10 — error state passes a wcag2aa axe scan', async () => {
    server.use(http.get(PROJECTS_URL, () => problemResponse()));
    const { container } = renderList(defaultList());
    await waitFor(() => expect(screen.getByTestId('error-state')).toBeInTheDocument());
    await expectNoA11yViolations(container);
  });
});
