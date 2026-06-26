/**
 * Story 3c-9 (Task 9, AC6/AC10) — `ProjectSelector` (reconciled R3 seam).
 *
 * ≤1 project → a static label (no combobox); ≥2 → a selection control. Not wired to
 * mutate the queue (R3). Plus a wcag2aa axe scan in both modes.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Project } from '@/lib/api/queryOptions';
import { server } from '@/test/server';
import { defaultProjectFixture } from '@/test/handlers';
import { expectNoA11yViolations } from '@/test/a11y/axe';

import { ProjectSelector } from '../components/ProjectSelector';

const PROJECTS_URL = 'http://localhost/api/v1/projects';

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderSelector(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

function installRadixSelectJsdomShims() {
  Object.defineProperty(Element.prototype, 'hasPointerCapture', {
    configurable: true,
    value: () => false,
  });
  Object.defineProperty(Element.prototype, 'scrollIntoView', {
    configurable: true,
    value: () => undefined,
  });
}
const SECOND: Project = {
  ...defaultProjectFixture,
  id: 'prj_two',
  slug: 'two',
  name: 'Second project',
};

beforeEach(() => {
  installRadixSelectJsdomShims();
});
afterEach(() => {
  cleanup();
});

describe('ProjectSelector', () => {
  it('AC6 — a single project collapses to a static label (no combobox)', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([defaultProjectFixture])));
    const { container } = renderSelector(<ProjectSelector />);

    await waitFor(() =>
      expect(screen.getByTestId('project-selector')).toHaveAttribute(
        'data-selector-mode',
        'collapsed',
      ),
    );
    expect(screen.getByTestId('project-selector')).toHaveTextContent('Default project');
    expect(screen.queryByRole('combobox')).toBeNull();
    await expectNoA11yViolations(container);
  });

  it('AC6 — two projects present a selection control', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([defaultProjectFixture, SECOND])));
    const { container } = renderSelector(<ProjectSelector />);

    await waitFor(() =>
      expect(screen.getByTestId('project-selector')).toHaveAttribute(
        'data-selector-mode',
        'expanded',
      ),
    );
    expect(screen.getByTestId('project-selector-trigger')).toBeInTheDocument();
    expect(screen.getByRole('combobox')).toBeInTheDocument();
    await expectNoA11yViolations(container);
  });

  it('queue mode exposes an All projects option and reports controlled project changes', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([defaultProjectFixture, SECOND])));
    const onChange = vi.fn();
    const user = userEvent.setup();

    const { rerender } = renderSelector(
      <ProjectSelector includeAllOption value={undefined} onChange={onChange} />,
    );

    await waitFor(() => expect(screen.getByTestId('project-selector-trigger')).toBeInTheDocument());
    expect(screen.getByRole('combobox')).toHaveTextContent('All projects');

    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'Second project' }));
    expect(onChange).toHaveBeenCalledWith('prj_two');

    rerender(
      <QueryClientProvider client={freshClient()}>
        <ProjectSelector includeAllOption value="prj_two" onChange={onChange} />
      </QueryClientProvider>,
    );
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'All projects' }));
    expect(onChange).toHaveBeenCalledWith(undefined);
  });
  it('renders nothing when there are no projects', async () => {
    server.use(http.get(PROJECTS_URL, () => HttpResponse.json([])));
    renderSelector(<ProjectSelector />);
    // Give the query a tick to settle; the selector stays absent.
    await waitFor(() => expect(screen.queryByTestId('project-selector')).toBeNull());
  });
});
