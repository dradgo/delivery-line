/**
 * Story 3c-9 (Task 9, AC1/AC6/AC9) — `ProjectsScreen` composition.
 *
 * The "New project" primary opens the create form; a row "Edit" opens the prefilled
 * edit form; running a connectivity test updates the session-scoped last-test cell
 * (R4). `useReturnToRunContext` (via `<ErrorState>` in the list) is mocked at its own
 * module ([[vitest-cross-file-router-mock]]).
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { ProjectsScreen } from '../ProjectsScreen';

const PROJECTS_URL = 'http://localhost/api/v1/projects';

function freshClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderScreen(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

beforeEach(() => {
  backSpy.mockClear();
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ProjectsScreen', () => {
  it('AC1/AC9 — renders the heading, selector and a single "New project" primary', async () => {
    renderScreen(<ProjectsScreen />);
    expect(screen.getByRole('heading', { name: 'Projects' })).toBeInTheDocument();
    expect(screen.getByTestId('project-new-button')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('project-selector')).toBeInTheDocument());
  });

  it('AC3 — "New project" opens the create form', async () => {
    renderScreen(<ProjectsScreen />);
    await waitFor(() => expect(screen.getByTestId('project-list-table')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('project-new-button'));
    expect(await screen.findByTestId('project-form-dialog')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'New project' })).toBeInTheDocument();
  });

  it('AC3 — row "Edit" opens the prefilled edit form', async () => {
    renderScreen(<ProjectsScreen />);
    await waitFor(() => expect(screen.getByTestId('project-edit-button')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('project-edit-button'));
    expect(await screen.findByTestId('project-form-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('project-name-input')).toHaveValue('Default project');
  });

  it('R4 — running a connection test updates the session last-test cell', async () => {
    server.use(
      http.post(`${PROJECTS_URL}/prj_default/test-connection`, () =>
        HttpResponse.json({
          checks: [
            { check: 'repository_reachable', status: 'pass', detail: 'ok' },
            { check: 'ticket_source_auth', status: 'pass', detail: 'ok' },
            { check: 'repository_host_auth', status: 'skipped', detail: 'no credential' },
          ],
        }),
      ),
    );
    renderScreen(<ProjectsScreen />);
    await waitFor(() => expect(screen.getByTestId('project-row')).toBeInTheDocument());
    expect(screen.getByText('Not tested')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('connection-test-button'));
    await waitFor(() =>
      expect(screen.getByTestId('project-last-test')).toHaveTextContent(
        '2 passed, 0 failed, 1 skipped',
      ),
    );
  });
});
