/**
 * Story 2.15 (Task 5 / AC2–AC12) — `RunReviewQueueItem` component tests.
 *
 * Router-free (mirrors `RunContextStrip.test.tsx` / memory
 * `vitest-cross-file-router-mock`): `@tanstack/react-router`'s `Link` is mocked to a
 * plain `<a>` that surfaces the navigation target as `data-to` / `data-run-param`
 * (so the open-run intent is assertable without a real router), forwarding
 * `onClick` / `onKeyDown` / `aria-label` / `data-*` through.
 *
 * `Date.now` is pinned (NOT fake timers) so `formatRelativeTime` is deterministic.
 */
import type { ComponentProps, MouseEvent } from 'react';
import type * as ReactRouter from '@tanstack/react-router';
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import userEvent from '@testing-library/user-event';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import type { RunQueueRow } from '../../runQueueRow';
import { toRunQueueRow } from '../../runQueueRow';
import { specRejectAndResubmitSummary } from '@/test/fixtures/runQueue/specRejectAndResubmit';
import { executionFailureSummary } from '@/test/fixtures/runQueue/executionFailure';
import { openPrLinkage, prLinkageByState } from '@/test/fixtures/runQueue/prLinkage';
import type { PrState } from '../../prLinkageView';

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof ReactRouter>();
  const LinkMock = ({
    to,
    params,
    children,
    onClick,
    ...rest
  }: ComponentProps<'a'> & { to?: string; params?: Record<string, string> }) => (
    <a
      href={`/workflows/${params?.workflowRunId ?? ''}`}
      data-to={to}
      data-run-param={params?.workflowRunId}
      onClick={(event: MouseEvent<HTMLAnchorElement>) => {
        event.preventDefault();
        onClick?.(event);
      }}
      {...rest}
    >
      {children}
    </a>
  );
  return { ...actual, Link: LinkMock };
});

import { RunReviewQueueItem } from '../RunReviewQueueItem';

/** Pinned "now" — 3 minutes after the fixtures' last transition (deterministic relative time). */
const NOW_MS = Date.parse('2026-05-30T12:03:00Z');

const BASE_ROW: RunQueueRow = {
  runId: 'run_abc123',
  linearTicketReference: 'DEL-1234',
  currentState: 'WaitingForSpecApproval',
  lastTransitionAt: '2026-05-30T12:00:00Z',
};

function row() {
  return screen.getByTestId('run-review-queue-item');
}

beforeEach(() => {
  vi.spyOn(Date, 'now').mockReturnValue(NOW_MS);
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('RunReviewQueueItem — states (AC3)', () => {
  it('renders the default state for a plain row', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'default');
  });

  it('renders selected (accent) when selected', () => {
    render(<RunReviewQueueItem run={BASE_ROW} selected />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'selected');
  });

  it('renders unread with a dot indicator', () => {
    render(<RunReviewQueueItem run={BASE_ROW} unread />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'unread');
    expect(screen.getByTestId('queue-item-unread-dot')).toBeInTheDocument();
    expect(screen.getByText('Unread')).toBeInTheDocument();
  });

  it('renders blocked when blockerCount > 0 (dormant from live data, via fixture)', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, blockerCount: 2 }} />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'blocked');
  });

  it('renders stale when staleIndicator is true (dormant from live data, via fixture)', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, staleIndicator: true }} />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'stale');
  });

  it('renders disabled as an inert, non-navigable control (Trap T9)', () => {
    render(<RunReviewQueueItem run={BASE_ROW} disabled />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'disabled');
    expect(row()).toHaveAttribute('aria-disabled', 'true');
    // No link — not in the tab order.
    expect(screen.queryByRole('link')).toBeNull();
    expect(row().tagName).toBe('DIV');
  });

  it('story 3.30 (AC8) — a Failed run renders the failed state + Failed primary indicator (LIVE)', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, currentState: 'Failed' }} />);
    expect(row()).toHaveAttribute('data-queue-item-state', 'failed');
    const primary = screen.getByTestId('queue-item-primary-attention');
    expect(primary).toHaveTextContent('Failed');
    // Still navigable (the failure is not disabled).
    expect(screen.getByRole('link')).toBeInTheDocument();
  });

  it('story 3.30 (AC8) — the compact failure category is DORMANT (renders from a constructed fixture only)', () => {
    render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, currentState: 'Failed', failureCategory: 'runner_crash' }}
      />,
    );
    expect(screen.getByTestId('queue-item-primary-attention')).toHaveTextContent(
      'Failed · Runner Crash',
    );
  });

  it('story 3.30 (AC8) — failed run has zero axe violations', async () => {
    const { container } = render(
      <RunReviewQueueItem run={{ ...BASE_ROW, currentState: 'Failed' }} />,
    );
    await expectNoA11yViolations(container);
  });
});

describe('RunReviewQueueItem — one primary attention signal (AC5/T7)', () => {
  it('shows exactly one primary signal and demotes the rest to the secondary cluster', () => {
    render(
      <RunReviewQueueItem
        run={{
          ...BASE_ROW,
          blockerCount: 1,
          escalationMarker: true,
          openQuestionCount: 3,
          staleIndicator: true,
        }}
      />,
    );
    // Exactly one primary chip, and it is the highest-priority signal (blocker).
    const primaries = screen.getAllByTestId('queue-item-primary-attention');
    expect(primaries).toHaveLength(1);
    expect(primaries[0]).toHaveTextContent('1 blocker');
    // The other active signals demote to the secondary cluster.
    const secondary = screen.getByTestId('queue-item-secondary');
    expect(secondary).toHaveTextContent('Escalated');
    expect(secondary).toHaveTextContent('3 open questions');
    expect(secondary).toHaveTextContent('Stale');
  });

  it('renders the escalation marker as the primary signal when it is the only one', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, escalationMarker: true }} />);
    expect(screen.getByTestId('queue-item-primary-attention')).toHaveTextContent('Escalated');
  });
});

describe('RunReviewQueueItem — open-run intent + keyboard (AC6/AC8)', () => {
  it('renders a typed Link to the run detail when the run id is well-formed (Enter is native)', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    const link = row();
    // The navigable row is rendered as the router <Link> (here a mocked <a>).
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('data-to', '/workflows/$workflowRunId');
    expect(link).toHaveAttribute('data-run-param', 'run_abc123');
  });

  it('activates (opens) on Space — Trap T8', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    fireEvent.keyDown(row(), { key: ' ' });
    expect(console.info).toHaveBeenCalledWith(expect.objectContaining({ event: 'queueItem.open' }));
  });

  it('does not repeatedly activate while Space is held down', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    fireEvent.keyDown(row(), { key: ' ', repeat: true });
    expect(console.info).not.toHaveBeenCalled();
  });

  it('activates (opens) on Enter explicitly and can receive focus', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    row().focus();
    expect(row()).toHaveFocus();
    fireEvent.keyDown(row(), { key: 'Enter' });
    expect(console.info).toHaveBeenCalledWith(expect.objectContaining({ event: 'queueItem.open' }));
  });

  it('logs queueItem.open on click activation', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    fireEvent.click(row());
    expect(console.info).toHaveBeenCalledWith(expect.objectContaining({ event: 'queueItem.open' }));
  });

  it('renders inert and disabled when the run id is malformed — never builds a bad route param', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, runId: 'not-a-valid-id' }} />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(row().tagName).toBe('DIV');
    expect(row()).toHaveAttribute('data-queue-item-state', 'disabled');
    expect(row()).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('RunReviewQueueItem — relative time refresh', () => {
  it('recomputes relative time when the row rerenders after queue data refreshes later', () => {
    const { rerender } = render(<RunReviewQueueItem run={BASE_ROW} />);
    expect(screen.getByTestId('queue-item-age')).toHaveTextContent('3 minutes ago');

    vi.mocked(Date.now).mockReturnValue(Date.parse('2026-05-30T12:07:00Z'));
    rerender(
      <RunReviewQueueItem run={{ ...BASE_ROW, lastTransitionAt: '2026-05-30T12:02:00Z' }} />,
    );

    expect(screen.getByTestId('queue-item-age')).toHaveTextContent('5 minutes ago');
  });
});

describe('RunReviewQueueItem — aria-label (AC6)', () => {
  it('composes present fields and includes state + escalation + relative time', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, escalationMarker: true }} />);
    expect(row()).toHaveAttribute(
      'aria-label',
      'DEL-1234 run_abc123, WaitingForSpecApproval, escalated, last updated 3 minutes ago',
    );
  });

  it('omits absent fields and never renders the literal "undefined"', () => {
    render(<RunReviewQueueItem run={{ runId: 'run_abc123' }} />);
    const label = row().getAttribute('aria-label') ?? '';
    expect(label).toBe('run_abc123');
    expect(label).not.toMatch(/undefined/);
  });
});

describe('RunReviewQueueItem — density variants (AC4)', () => {
  it('applies the standard (8px) gap by default', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    expect(screen.getByTestId('queue-item-anatomy')).toHaveClass('gap-2');
  });

  it('applies the compact (4px) gap when density="compact"', () => {
    render(<RunReviewQueueItem run={BASE_ROW} density="compact" />);
    const anatomy = screen.getByTestId('queue-item-anatomy');
    expect(anatomy).toHaveClass('gap-1');
    expect(anatomy).not.toHaveClass('gap-2');
  });
});

describe('RunReviewQueueItem — operator variant (story 4.2 AC2)', () => {
  it('renders operator metadata FROM operatorSignifier and is navigable', () => {
    render(
      <RunReviewQueueItem
        run={{
          ...BASE_ROW,
          operatorSignifier: 'STALLED',
          failureCategory: 'runner_timeout',
          runnerKind: 'claude',
          escalationMarker: true,
          lastOperatorActionAt: '2026-05-30T12:00:00Z',
        }}
        variant="operator"
      />,
    );
    // Badge comes from the server signifier, NOT re-derived from currentState (Reconciliation 5).
    expect(screen.getByTestId('operator-signifier')).toHaveTextContent('STALLED');
    expect(screen.getByTestId('operator-runner-kind')).toHaveTextContent('claude');
    expect(screen.getByTestId('operator-failure-category')).toBeInTheDocument();
    expect(screen.getByTestId('operator-escalation')).toBeInTheDocument();
    // The whole row is an open-run link to the run detail (unlike the 2.15 placeholder).
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('data-variant', 'operator');
  });

  it('falls back to the state badge when operatorSignifier is absent (never re-derives)', () => {
    render(<RunReviewQueueItem run={BASE_ROW} variant="operator" />);
    expect(screen.queryByTestId('operator-signifier')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Story 2.25 a11y — axe scans of every documented queue-item state + keyboard.
// ---------------------------------------------------------------------------
describe('RunReviewQueueItem a11y (story 2.25)', () => {
  it('AC2 — default (navigable) state has no axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={BASE_ROW} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — selected state has no axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={BASE_ROW} selected />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — unread state has no axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={BASE_ROW} unread />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — blocked state has no axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={{ ...BASE_ROW, blockerCount: 2 }} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — stale state has no axe violations', async () => {
    const { container } = render(
      <RunReviewQueueItem run={{ ...BASE_ROW, staleIndicator: true }} />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — disabled (inert) state has no axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={BASE_ROW} disabled />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — operator variant has no axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={BASE_ROW} variant="operator" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — multiple attention signals (primary + secondary cluster) has no axe violations', async () => {
    const { container } = render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, blockerCount: 1, escalationMarker: true, openQuestionCount: 2 }}
      />,
    );
    await expectNoA11yViolations(container);
  });

  // AC1 — keyboard: the navigable row is a real <a> (via Link mock), Tab-reachable,
  // and activates on Enter (native anchor) and Space (Trap T8 handler).
  it('AC1 — navigable row is Tab-reachable', async () => {
    const user = userEvent.setup();
    render(<RunReviewQueueItem run={BASE_ROW} />);
    await user.tab();
    expect(document.activeElement).toBe(row());
  });

  it('AC1 — navigable row activates on Enter (logs queueItem.open)', async () => {
    const user = userEvent.setup();
    render(<RunReviewQueueItem run={BASE_ROW} />);
    await user.tab();
    expect(document.activeElement).toBe(row());
    await user.keyboard('{Enter}');
    expect(console.info).toHaveBeenCalledWith(expect.objectContaining({ event: 'queueItem.open' }));
  });

  it('AC1 — navigable row activates on Space (logs queueItem.open)', async () => {
    const user = userEvent.setup();
    render(<RunReviewQueueItem run={BASE_ROW} />);
    await user.tab();
    expect(document.activeElement).toBe(row());
    await user.keyboard(' ');
    expect(console.info).toHaveBeenCalledWith(expect.objectContaining({ event: 'queueItem.open' }));
  });

  it('AC1 — disabled row is NOT in the tab order', async () => {
    const user = userEvent.setup();
    render(<RunReviewQueueItem run={BASE_ROW} disabled />);
    await user.tab();
    // Nothing focusable — focus stays on body / wraps outside the row.
    expect(document.activeElement).not.toBe(row());
  });
});

describe('RunReviewQueueItem — logging contract (Task 6)', () => {
  it('logs only field-only data, never ticketRef / summary / runId', () => {
    render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, summary: 'sensitive text', specRejectionLoopCount: 2 }}
      />,
    );
    fireEvent.click(row());
    const infoMock = vi.mocked(console.info);
    const call = infoMock.mock.calls.find(
      ([arg]) => (arg as { event?: string } | undefined)?.event === 'queueItem.open',
    );
    expect(call).toBeDefined();
    const payload = call![0] as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual([
      'attention',
      'event',
      'hasEscalation',
      'rejectionLoopCount',
      'state',
    ]);
    // Negative assertion — no content/identity fields leak.
    expect(payload).not.toHaveProperty('ticketRef');
    expect(payload).not.toHaveProperty('summary');
    expect(payload).not.toHaveProperty('runId');
    const serialized = JSON.stringify(payload);
    expect(serialized).not.toContain('DEL-1234');
    expect(serialized).not.toContain('run_abc123');
    expect(serialized).not.toContain('sensitive text');
  });
});

describe('RunReviewQueueItem — fixture-driven rendering (AC12)', () => {
  it('renders the spec-rejection-and-resubmit terminal row (Completed + N rejections)', () => {
    render(<RunReviewQueueItem run={toRunQueueRow(specRejectAndResubmitSummary)} />);
    // The row body is a sibling of the stretched open-run overlay (D1), so assert content
    // against the rendered document rather than the (empty) overlay anchor.
    expect(screen.getByText('run_fix_rej_001')).toBeInTheDocument();
    expect(screen.getByText('DEL-9002')).toBeInTheDocument();
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge).toHaveTextContent('Completed');
    expect(badge).toHaveAttribute('data-state-name', 'success');
    // specRejectionLoopCount = 1 → a secondary "1 rejection" trust signal (OQ-3).
    expect(screen.getByTestId('queue-item-rejections')).toHaveTextContent('1 rejection');
    expect(screen.getByTestId('queue-item-age')).toBeInTheDocument();
  });

  it('renders the execution-failure terminal row (story 3.30 AC8 — Failed primary, escalation demoted)', () => {
    render(<RunReviewQueueItem run={toRunQueueRow(executionFailureSummary)} />);
    expect(screen.getByText('run_exec_fail_001')).toBeInTheDocument();
    expect(row()).toHaveAttribute('data-queue-item-state', 'failed');
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge).toHaveTextContent('Failed');
    expect(badge).toHaveAttribute('data-state-name', 'error');
    // Story 3.30 (AC8): failure now outranks escalation for the primary indicator;
    // the escalation marker demotes to the secondary trust cluster.
    expect(screen.getByTestId('queue-item-primary-attention')).toHaveTextContent('Failed');
    expect(screen.getByTestId('queue-item-secondary')).toHaveTextContent('Escalated');
  });
});

// ---------------------------------------------------------------------------
// Story 3.31 — compact GitHub PR linkage + the D1 stretched-link click target.
// ---------------------------------------------------------------------------
describe('RunReviewQueueItem — PR linkage (story 3.31)', () => {
  it('AC1/AC7 — renders the compact PR ref + state badge in the secondary cluster when present', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, prLinkage: openPrLinkage }} />);
    const secondary = screen.getByTestId('queue-item-secondary');
    const prLink = within(secondary).getByTestId('pr-linkage-link');
    expect(prLink).toHaveTextContent('PR acme/widgets#42');
    expect(within(secondary).getByTestId('pr-state-badge')).toHaveAttribute(
      'data-pr-state',
      'open',
    );
    // AC1 — the compact queue element omits branch/commit (those are strip-only).
    expect(within(secondary).queryByTestId('pr-linkage-branch')).toBeNull();
    expect(within(secondary).queryByTestId('pr-linkage-commit')).toBeNull();
  });

  it('AC8 — gracefully omits the PR linkage entirely when absent (Trap T-ABSENT)', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    expect(screen.queryByTestId('pr-linkage')).toBeNull();
    // No secondary cluster at all for a plain row (no demoted signals either).
    expect(screen.queryByTestId('queue-item-secondary')).toBeNull();
  });

  it('D1 — the PR link opens GitHub in a new tab without breaking whole-row open-run', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, prLinkage: openPrLinkage }} />);

    // The whole-row open-run intent still resolves on the row's `<Link>` overlay.
    expect(row().tagName).toBe('A');
    expect(row()).toHaveAttribute('data-to', '/workflows/$workflowRunId');
    expect(row()).toHaveAttribute('data-run-param', 'run_abc123');

    // The PR link is a distinct anchor → GitHub, new tab.
    const prLink = screen.getByTestId('pr-linkage-link');
    expect(prLink).toHaveAttribute('href', 'https://github.com/acme/widgets/pull/42');
    expect(prLink).toHaveAttribute('target', '_blank');
    expect(prLink).toHaveAttribute('rel', 'noopener noreferrer');

    // Clicking the PR link must NOT activate the row's open-run logging (D1 escape).
    fireEvent.click(prLink);
    expect(console.info).not.toHaveBeenCalledWith(
      expect.objectContaining({ event: 'queueItem.open' }),
    );
  });

  it('AC9 — the PR link carries the canonical accessible name; the row label is not polluted', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, prLinkage: openPrLinkage }} />);
    expect(screen.getByTestId('pr-linkage-link')).toHaveAttribute(
      'aria-label',
      'Pull request 42 in acme/widgets, status open',
    );
    // The row's own accessible name (AC6 of 2.15) stays free of PR text.
    expect(row().getAttribute('aria-label') ?? '').not.toMatch(/pull request/i);
  });

  it('AC10 — renders the badge for every PR lifecycle state', () => {
    (['draft', 'open', 'merged', 'closed'] satisfies PrState[]).forEach((state) => {
      const { unmount } = render(
        <RunReviewQueueItem run={{ ...BASE_ROW, prLinkage: prLinkageByState[state] }} />,
      );
      expect(screen.getByTestId('pr-state-badge')).toHaveAttribute('data-pr-state', state);
      unmount();
    });
  });

  it('AC6 — the PR ref shown comes from the row view-model (never an artifact source)', () => {
    // The component takes ONLY a `RunQueueRow` — there is no artifact prop/import path; the
    // rendered PR ref can only be the view-model's backend-truth `prLinkage.prReference`.
    render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, prLinkage: { ...openPrLinkage, prReference: 'backend/truth#7' } }}
      />,
    );
    expect(screen.getByTestId('pr-linkage-link')).toHaveTextContent('PR backend/truth#7');
  });

  it('AC10 — a row with PR linkage has zero axe violations', async () => {
    const { container } = render(
      <RunReviewQueueItem run={{ ...BASE_ROW, prLinkage: openPrLinkage }} />,
    );
    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 3.29 — TakenOver badge (LIVE) + DORMANT per-row takeover attribution.
// ---------------------------------------------------------------------------
describe('RunReviewQueueItem — takeover (story 3.29, AC3)', () => {
  const TAKEN_OVER_ROW: RunQueueRow = { ...BASE_ROW, currentState: 'TakenOver' };

  it('R3 — a TakenOver run renders the recovery badge with the raw "TakenOver" label (CLI parity)', () => {
    render(<RunReviewQueueItem run={TAKEN_OVER_ROW} />);
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge).toHaveTextContent('TakenOver');
    expect(badge).toHaveAttribute('data-state-name', 'recovery');
    // Icon + label pairing (never color-alone, AC3 non-color signifier).
    expect(badge.querySelector('svg')).not.toBeNull();
  });

  it('R5 — renders DORMANT actor + timestamp metadata when the fixture provides them', () => {
    render(
      <RunReviewQueueItem
        run={{
          ...TAKEN_OVER_ROW,
          takenOverBy: 'dev@acme.example',
          takenOverAt: '2026-05-30T12:00:00Z',
        }}
      />,
    );
    const takeover = within(screen.getByTestId('queue-item-secondary')).getByTestId(
      'queue-item-takeover',
    );
    expect(takeover).toHaveTextContent('Taken over by dev@acme.example');
    expect(takeover).toHaveTextContent('3 minutes ago');
  });

  it('R5 — takeover is metadata, NOT a primary attention signal', () => {
    render(<RunReviewQueueItem run={{ ...TAKEN_OVER_ROW, takenOverBy: 'dev@acme.example' }} />);
    // The takeover line never escalates into the primary-attention slot.
    expect(screen.queryByTestId('queue-item-primary-attention')).toBeNull();
  });

  it('R5 — omits the takeover metadata entirely when absent (fixture-driven, Trap T-ABSENT)', () => {
    render(<RunReviewQueueItem run={TAKEN_OVER_ROW} />);
    expect(screen.queryByTestId('queue-item-takeover')).toBeNull();
  });

  it('AC10 — a taken-over row with attribution has zero axe violations', async () => {
    const { container } = render(
      <RunReviewQueueItem
        run={{
          ...TAKEN_OVER_ROW,
          takenOverBy: 'dev@acme.example',
          takenOverAt: '2026-05-30T12:00:00Z',
        }}
      />,
    );
    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 3d-8 (AC5) — the archived/"Hidden" chip (LIVE from `archived`).
// ---------------------------------------------------------------------------
describe('RunReviewQueueItem — archived chip (story 3d-8, AC5)', () => {
  it('renders a color-independent "Hidden" chip when the run is archived', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, archived: true }} />);
    const chip = within(screen.getByTestId('queue-item-secondary')).getByTestId(
      'queue-item-archived',
    );
    // Paired icon + label — never color-alone (AC5 non-color signifier).
    expect(chip).toHaveTextContent('Hidden');
    expect(chip.querySelector('svg')).not.toBeNull();
  });

  it('omits the archived chip entirely for a live (non-archived) run', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, archived: false }} />);
    expect(screen.queryByTestId('queue-item-archived')).toBeNull();
  });

  it('archived is metadata, NOT a primary attention signal', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, archived: true }} />);
    expect(screen.queryByTestId('queue-item-primary-attention')).toBeNull();
  });

  it('AC10 — an archived row has zero axe violations', async () => {
    const { container } = render(<RunReviewQueueItem run={{ ...BASE_ROW, archived: true }} />);
    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 3f-6 - project attribution metadata.
// ---------------------------------------------------------------------------
describe('RunReviewQueueItem - project attribution (story 3f-6)', () => {
  it('renders a color-independent project chip from live row attribution', () => {
    render(
      <RunReviewQueueItem
        run={{
          ...BASE_ROW,
          projectId: 'prj_alpha',
          projectName: 'Alpha project',
          projectSlug: 'alpha',
        }}
      />,
    );

    const project = within(screen.getByTestId('queue-item-secondary')).getByTestId(
      'queue-item-project',
    );
    expect(project).toHaveTextContent('Project: Alpha project');
    expect(project.querySelector('svg')).not.toBeNull();
    expect(row()).toHaveAttribute(
      'aria-label',
      'DEL-1234 run_abc123, Alpha project, WaitingForSpecApproval, last updated 3 minutes ago',
    );
  });

  it('falls back to slug then id and omits the chip when no project attribution exists', () => {
    const { rerender } = render(
      <RunReviewQueueItem run={{ ...BASE_ROW, projectId: 'prj_alpha', projectSlug: 'alpha' }} />,
    );
    expect(screen.getByTestId('queue-item-project')).toHaveTextContent('Project: alpha');

    rerender(<RunReviewQueueItem run={{ ...BASE_ROW, projectId: 'prj_alpha' }} />);
    expect(screen.getByTestId('queue-item-project')).toHaveTextContent('Project: prj_alpha');

    rerender(<RunReviewQueueItem run={BASE_ROW} />);
    expect(screen.queryByTestId('queue-item-project')).toBeNull();
  });

  it('AC5 — a project-attributed row passes a wcag2aa axe scan', async () => {
    const { container } = render(
      <RunReviewQueueItem
        run={{
          ...BASE_ROW,
          projectId: 'prj_alpha',
          projectName: 'Alpha project',
          projectSlug: 'alpha',
        }}
      />,
    );

    await expectNoA11yViolations(container);
  });
});

// ---------------------------------------------------------------------------
// Story 3g-2 (FR73) — the human ticket title as the queue's prominent label.
// ---------------------------------------------------------------------------
describe('RunReviewQueueItem - ticket title (story 3g-2)', () => {
  it('AC1 - renders the human title as the prominent label AND keeps the ref visible', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, ticketTitle: 'Fix flaky checkout test' }} />);
    // The title is the prominent identity label.
    expect(screen.getByTestId('queue-item-identity-label')).toHaveTextContent(
      'Fix flaky checkout test',
    );
    // The machine ref stays visible as secondary identity (must not disappear).
    expect(screen.getByTestId('queue-item-ticket-ref')).toHaveTextContent('DEL-1234');
    // The run id (machine identity) is still rendered.
    expect(screen.getByText('run_abc123')).toBeInTheDocument();
  });

  it('AC1 - falls back to the ref in the prominent slot when the title is absent (never blank)', () => {
    render(<RunReviewQueueItem run={BASE_ROW} />);
    // With no title, the ref occupies the prominent label (today's parity behavior).
    expect(screen.getByTestId('queue-item-identity-label')).toHaveTextContent('DEL-1234');
    // No duplicated secondary ref chip when the ref is already the prominent label.
    expect(screen.queryByTestId('queue-item-ticket-ref')).toBeNull();
  });

  it('AC6 - the aria identity carries the title AND the machine ref + run id', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, ticketTitle: 'Fix flaky checkout test' }} />);
    const label = row().getAttribute('aria-label') ?? '';
    expect(label).toContain('Fix flaky checkout test');
    expect(label).toContain('DEL-1234');
    expect(label).toContain('run_abc123');
  });

  it('AC4/AC6 - a titled row has zero axe violations', async () => {
    const { container } = render(
      <RunReviewQueueItem run={{ ...BASE_ROW, ticketTitle: 'Fix flaky checkout test' }} />,
    );
    await expectNoA11yViolations(container);
  });

  // Story 4.24 (AC8a) — the operator-variant Classify launch context.
  it('4.24 - a Failed operator row renders a Classify action that calls onClassify(runId)', async () => {
    const onClassify = vi.fn();
    const user = userEvent.setup();
    render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, currentState: 'Failed' }}
        variant="operator"
        onClassify={onClassify}
      />,
    );

    await user.click(screen.getByTestId('operator-classify-action'));
    expect(onClassify).toHaveBeenCalledWith('run_abc123');
  });

  it('4.24 - a non-Failed operator row renders NO Classify action', () => {
    render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, currentState: 'WaitingForReview' }}
        variant="operator"
        onClassify={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('operator-classify-action')).toBeNull();
  });

  it('4.24 (AC9) - an operator row surfaces the applied classification chip when a label is given', () => {
    render(
      <RunReviewQueueItem
        run={{ ...BASE_ROW, currentState: 'Failed' }}
        variant="operator"
        classificationLabel="Agent Execution Failure"
      />,
    );
    const chip = screen.getByTestId('operator-classification-chip');
    expect(chip).toHaveTextContent('Classification: Agent Execution Failure');
  });

  it('4.24 (AC9) - no classification chip when no label is given', () => {
    render(<RunReviewQueueItem run={{ ...BASE_ROW, currentState: 'Failed' }} variant="operator" />);
    expect(screen.queryByTestId('operator-classification-chip')).toBeNull();
  });
});
