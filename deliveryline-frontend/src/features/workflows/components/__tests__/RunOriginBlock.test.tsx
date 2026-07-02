/**
 * Story 3g-2 (Task 5 / AC2, AC3, AC4, AC6) — `RunOriginBlock` component tests.
 *
 * Router-free (the block renders no `<Link>` — the link-out is a native external `<a>`).
 * Covers: the origin fields render; the link-out is present when `url` is set and absent
 * when `url` is null; the block self-hides when there is no linked ticket; the external
 * link carries the distinguishing accessible name + `rel="noopener noreferrer"`; and the
 * surface is axe-clean.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import type { WorkflowDetail } from '@/lib/api/queryOptions';

import { RunOriginBlock } from '../RunOriginBlock';

const DETAIL_WITH_URL: WorkflowDetail = {
  workflowRunId: 'run_abc123',
  linkedTicket: {
    externalRef: 'DEL-1234',
    integrationType: 'linear',
    title: 'Fix flaky checkout test',
    url: 'https://linear.app/acme/issue/DEL-1234',
  },
};

const DETAIL_WITHOUT_URL: WorkflowDetail = {
  workflowRunId: 'run_abc123',
  linkedTicket: {
    externalRef: 'DEL-1234',
    integrationType: 'linear',
    title: 'Fix flaky checkout test',
    url: null,
  },
};

afterEach(cleanup);

describe('RunOriginBlock', () => {
  it('AC2/AC3 — renders the title, ref, and integrationType (no body/prompt)', () => {
    render(<RunOriginBlock detail={DETAIL_WITH_URL} />);
    expect(screen.getByTestId('run-origin-block')).toHaveAccessibleName('Origin');
    expect(screen.getByTestId('run-origin-title')).toHaveTextContent('Fix flaky checkout test');
    expect(screen.getByTestId('run-origin-ref')).toHaveTextContent('DEL-1234');
    expect(screen.getByTestId('run-origin-type')).toHaveTextContent('linear');
  });

  it('AC2/AC4 — renders the link-out when url is set, opening a new tab safely', () => {
    render(<RunOriginBlock detail={DETAIL_WITH_URL} />);
    const link = screen.getByTestId('run-origin-link');
    expect(link).toHaveAttribute('href', 'https://linear.app/acme/issue/DEL-1234');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    // AC4 — the accessible name distinguishes it as the external source ticket + new tab.
    expect(link).toHaveAttribute(
      'aria-label',
      'Open source ticket DEL-1234 (opens in a new tab)',
    );
  });

  it('AC2 — omits the link-out entirely when url is null (no dead/# anchor)', () => {
    render(<RunOriginBlock detail={DETAIL_WITHOUT_URL} />);
    // The block still renders (title present), but no anchor at all.
    expect(screen.getByTestId('run-origin-block')).toBeInTheDocument();
    expect(screen.queryByTestId('run-origin-link')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('AC2 — renders nothing when there is no linked ticket', () => {
    const { container } = render(<RunOriginBlock detail={{ workflowRunId: 'run_abc123' }} />);
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByTestId('run-origin-block')).toBeNull();
  });

  it('AC2 — renders nothing when the linked ticket has a null title', () => {
    const { container } = render(
      <RunOriginBlock detail={{ linkedTicket: { externalRef: 'DEL-1234', title: null } }} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('AC2 — renders nothing while the detail is still loading (undefined)', () => {
    const { container } = render(<RunOriginBlock detail={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('AC6 — the link-out aria-label omits a stray ref when the ref is absent', () => {
    render(
      <RunOriginBlock
        detail={{ linkedTicket: { title: 'Titled', url: 'https://linear.app/issue/x' } }}
      />,
    );
    expect(screen.getByTestId('run-origin-link')).toHaveAttribute(
      'aria-label',
      'Open source ticket (opens in a new tab)',
    );
  });

  it('AC4 — the block (with link-out) is axe-clean', async () => {
    const { container } = render(<RunOriginBlock detail={DETAIL_WITH_URL} />);
    await expectNoA11yViolations(container);
  });

  it('AC4 — the block (no link-out) is axe-clean', async () => {
    const { container } = render(<RunOriginBlock detail={DETAIL_WITHOUT_URL} />);
    await expectNoA11yViolations(container);
  });
});
