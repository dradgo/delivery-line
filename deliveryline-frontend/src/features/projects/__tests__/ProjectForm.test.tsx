/**
 * Story 3c-9 (Task 9, AC3/AC10) — `ProjectForm` validates required fields, submits a
 * valid create, prefills + disables slug on edit, maps `PROJECT_SLUG_CONFLICT` to the
 * slug field, and passes a wcag2aa axe scan (create + edit).
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

import { ProjectForm } from '../components/ProjectForm';

const PROJECTS_URL = 'http://localhost/api/v1/projects';

function freshClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderForm(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

beforeEach(() => {
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('ProjectForm — create', () => {
  it('AC3 — required-field validation blocks submit (no network call)', async () => {
    let calls = 0;
    server.use(
      http.post(PROJECTS_URL, () => {
        calls += 1;
        return HttpResponse.json(defaultProjectFixture, { status: 201 });
      }),
    );
    renderForm(<ProjectForm mode={{ kind: 'create' }} open onClose={vi.fn()} />);

    fireEvent.submit(screen.getByTestId('project-form-submit').closest('form')!);
    // Per-field messages appear; no request fires.
    expect(await screen.findByText('Name is required.')).toBeInTheDocument();
    expect(screen.getByText('Slug is required.')).toBeInTheDocument();
    expect(calls).toBe(0);
  });

  it('AC3 — a valid submit calls createProject with the typed body + closes', async () => {
    let captured: Record<string, unknown> | undefined;
    server.use(
      http.post(PROJECTS_URL, async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(defaultProjectFixture, { status: 201 });
      }),
    );
    const onClose = vi.fn();
    renderForm(<ProjectForm mode={{ kind: 'create' }} open onClose={onClose} />);

    fireEvent.change(screen.getByTestId('project-name-input'), { target: { value: 'Acme' } });
    fireEvent.change(screen.getByTestId('project-slug-input'), { target: { value: 'acme' } });
    fireEvent.click(screen.getByTestId('project-form-submit'));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(captured).toMatchObject({
      name: 'Acme',
      slug: 'acme',
      ticketSourceKind: 'linear',
      repoHostKind: 'github',
      openspecEnabled: false,
    });
  });

  it('AC3 — PROJECT_SLUG_CONFLICT surfaces on the slug field', async () => {
    server.use(
      http.post(PROJECTS_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Slug conflict',
            status: 409,
            detail: 'slug taken',
            instance: '/api/v1/projects',
            code: 'PROJECT_SLUG_CONFLICT',
            retryable: false,
          },
          { status: 409, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );
    const onClose = vi.fn();
    renderForm(<ProjectForm mode={{ kind: 'create' }} open onClose={onClose} />);

    fireEvent.change(screen.getByTestId('project-name-input'), { target: { value: 'Acme' } });
    fireEvent.change(screen.getByTestId('project-slug-input'), { target: { value: 'default' } });
    fireEvent.click(screen.getByTestId('project-form-submit'));

    expect(
      await screen.findByText('That slug is already in use. Choose a different slug.'),
    ).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('project-slug-input')).toHaveAttribute('aria-invalid', 'true');
  });

  it('AC7 — renders the project-wide default + per-step runner controls (story 3e-4)', () => {
    renderForm(<ProjectForm mode={{ kind: 'create' }} open onClose={vi.fn()} />);
    expect(screen.getByTestId('project-runner-kind')).toBeInTheDocument();
    expect(screen.getByTestId('project-step-runner-spec')).toBeInTheDocument();
    expect(screen.getByTestId('project-step-runner-implementationPlan')).toBeInTheDocument();
    expect(screen.getByTestId('project-step-runner-prOutput')).toBeInTheDocument();
  });

  it('AC10 — the create form passes a wcag2aa axe scan', async () => {
    renderForm(<ProjectForm mode={{ kind: 'create' }} open onClose={vi.fn()} />);
    await screen.findByTestId('project-form-dialog');
    await expectNoA11yViolations(document.body);
  });
});

describe('ProjectForm — edit', () => {
  it('AC3 — prefills non-secret fields and disables the slug', () => {
    renderForm(
      <ProjectForm
        mode={{ kind: 'edit', project: defaultProjectFixture }}
        open
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId('project-name-input')).toHaveValue('Default project');
    const slug = screen.getByTestId('project-slug-input');
    expect(slug).toHaveValue('default');
    expect(slug).toBeDisabled();
  });

  it('AC3 — a valid edit calls updateProject + closes', async () => {
    let hit = false;
    server.use(
      http.put(`${PROJECTS_URL}/prj_default`, () => {
        hit = true;
        return HttpResponse.json(defaultProjectFixture);
      }),
    );
    const onClose = vi.fn();
    renderForm(
      <ProjectForm
        mode={{ kind: 'edit', project: defaultProjectFixture }}
        open
        onClose={onClose}
      />,
    );
    fireEvent.change(screen.getByTestId('project-name-input'), { target: { value: 'Renamed' } });
    fireEvent.click(screen.getByTestId('project-form-submit'));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(hit).toBe(true);
  });

  it('AC6/AC7 — an edit round-trips the prefilled runner config in the PUT body (story 3e-4)', async () => {
    let captured: Record<string, unknown> | undefined;
    server.use(
      http.put(`${PROJECTS_URL}/prj_runner`, async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(defaultProjectFixture);
      }),
    );
    const onClose = vi.fn();
    const project = {
      ...defaultProjectFixture,
      id: 'prj_runner',
      runnerKind: 'claude' as const,
      stepRunnerKinds: { spec: 'codex', prOutput: 'manual' },
    };
    renderForm(<ProjectForm mode={{ kind: 'edit', project }} open onClose={onClose} />);
    fireEvent.click(screen.getByTestId('project-form-submit'));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    // Full-replace: the prefilled project-wide default + per-step map ride the PUT body verbatim.
    expect(captured).toMatchObject({
      runnerKind: 'claude',
      stepRunnerKinds: { spec: 'codex', prOutput: 'manual' },
    });
  });

  it('AC10 — the edit form passes a wcag2aa axe scan', async () => {
    renderForm(
      <ProjectForm
        mode={{ kind: 'edit', project: defaultProjectFixture }}
        open
        onClose={vi.fn()}
      />,
    );
    await screen.findByTestId('project-form-dialog');
    await expectNoA11yViolations(document.body);
  });
});
