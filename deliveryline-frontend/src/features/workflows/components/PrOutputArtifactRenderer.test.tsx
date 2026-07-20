/**
 * Story 3.27 (AC10) — `PrOutputArtifactRenderer` tests.
 *
 * Fixture-driven render assertions (router/query-free, like `SpecArtifactRenderer.test.tsx`).
 * Covers every AC10 bullet: branch/commit/PR refs + GitHub URLs; state badge; sanitized
 * unified-diff (`<ins>`/`<del>` + token classes); XSS inertness in diff/path/body; file
 * pagination at threshold; GitHub-unreachable cached-state + "(last synced X ago)" + log;
 * large-diff non-enumeration; keyboard accordion + jump-to-changed-region; axe a11y.
 */
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import {
  prOutputArtifactView,
  prOutputArtifactViewLargeDiff,
  prOutputArtifactViewStaleGitHub,
  prOutputArtifactViewXss,
} from '@/test/fixtures/artifact/artifactViewFixtures';
import { PR_DIFF_MAX_FILES } from '@/lib/sanitization';
import { PrOutputArtifactRenderer } from './PrOutputArtifactRenderer';

afterEach(() => {
  cleanup();
  delete (window as { __xss_executed?: boolean }).__xss_executed;
});

describe('PrOutputArtifactRenderer', () => {
  it('AC2 — renders the type badge + revision chrome + trusted reference panel', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    expect(screen.getByTestId('artifact-type-badge')).toHaveTextContent('pr-output');
    expect(screen.getByTestId('artifact-revision')).toHaveTextContent('v1');
    expect(screen.getByTestId('pr-reference-panel')).toBeInTheDocument();
  });

  it('AC2 — branch / commit / PR refs render with the correct backend-truth GitHub URLs', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);

    const branch = screen.getByTestId('pr-branch-link');
    expect(branch).toHaveAttribute(
      'href',
      'https://github.com/acme/widgets/tree/feature/del-9002-payload-validation',
    );
    expect(branch).toHaveAttribute('target', '_blank');
    expect(branch).toHaveAttribute('rel', 'noopener noreferrer');

    const commit = screen.getByTestId('pr-commit-link');
    expect(commit).toHaveAttribute(
      'href',
      'https://github.com/acme/widgets/commit/3f9a1c2b4e6d8033a1b2c3d4e5f60718293a4b5c',
    );
    // Short-form (7 chars) shown; full SHA kept in the title.
    expect(commit).toHaveTextContent('3f9a1c2');
    expect(commit).toHaveAttribute('title', '3f9a1c2b4e6d8033a1b2c3d4e5f60718293a4b5c');

    const pr = screen.getByTestId('pr-reference-link');
    expect(pr).toHaveAttribute('href', 'https://github.com/acme/widgets/pull/42');
    expect(pr).toHaveTextContent('acme/widgets#42');
  });

  it('AC2 — the state badge reflects prLinkage.prState', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    expect(screen.getByTestId('pr-state-badge')).toHaveAttribute('data-pr-state', 'open');
    expect(screen.getByTestId('pr-state-badge')).toHaveTextContent('Open');
  });

  it('AC2 — the copy button copies the FULL commit SHA', () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    const copy = screen.getByTestId('pr-commit-copy');
    expect(copy).toHaveAttribute('aria-label', 'Copy full commit SHA');
    fireEvent.click(copy);
    expect(writeText).toHaveBeenCalledWith('3f9a1c2b4e6d8033a1b2c3d4e5f60718293a4b5c');
  });

  it('AC2 — the diff renders file-by-file with <ins>/<del> + token classes when expanded', () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    // 3 files, collapsed by default — no diff lines until a file is expanded.
    expect(container.querySelector('ins.diff-line-added')).toBeNull();
    fireEvent.click(screen.getByTestId('pr-diff-file-trigger-0'));
    expect(container.querySelector('ins.diff-line-added')).not.toBeNull();
    expect(container.querySelector('del.diff-line-removed')).not.toBeNull();
    expect(container.querySelector('span.diff-line-context')).not.toBeNull();
  });

  it('AC2 — each file header shows +adds/−dels summary stats', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    expect(screen.getByTestId('pr-diff-file-stats-0')).toHaveTextContent('+3');
    expect(screen.getByTestId('pr-diff-file-stats-0')).toHaveTextContent('−1');
  });

  it('AC3 — scriptable diff content, file path, and PR body never produce active markup', () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactViewXss} />);
    // Expand the (single) file so its UNTRUSTED diff is rendered.
    fireEvent.click(screen.getByTestId('pr-diff-file-trigger-0'));

    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    expect((window as { __xss_executed?: boolean }).__xss_executed).toBeUndefined();

    // No anchor with a javascript: scheme anywhere.
    const anchors = Array.from(container.querySelectorAll('a'));
    expect(anchors.some((a) => (a.getAttribute('href') ?? '').startsWith('javascript:'))).toBe(
      false,
    );
    // The scripted file path renders as inert text (the script tag is visible, not executed).
    expect(container.textContent).toContain('<script>window.__xss_executed = true;</script>');
  });

  it('AC3 — the trusted reference panel is structurally separated from the untrusted diff', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    expect(screen.getByTestId('pr-reference-panel')).toHaveAttribute(
      'data-region',
      'trusted-references',
    );
    expect(screen.getByTestId('pr-diff')).toHaveAttribute('data-region', 'untrusted-diff');
  });

  it('AC5 — over the file threshold, only the first page renders + a "Show more" control', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactViewLargeDiff} />);
    // Only the first page of file headers is rendered (NOT all 55).
    const triggers = screen.getAllByTestId(/^pr-diff-file-trigger-\d+$/);
    expect(triggers).toHaveLength(PR_DIFF_MAX_FILES);
    expect(screen.getByTestId('pr-diff-file-count')).toHaveTextContent(
      `Showing ${PR_DIFF_MAX_FILES} of ${PR_DIFF_MAX_FILES + 5} files`,
    );
    const showMore = screen.getByTestId('pr-diff-show-more');
    expect(showMore).toBeInTheDocument();

    fireEvent.click(showMore);
    expect(screen.getAllByTestId(/^pr-diff-file-trigger-\d+$/)).toHaveLength(PR_DIFF_MAX_FILES + 5);
  });

  it('AC6 — GitHub-unreachable shows the cached state + "(last synced X ago)" + logs', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactViewStaleGitHub} />);

    // Cached state still shows the prState badge + the unreachable affordance.
    expect(screen.getByTestId('pr-state-badge')).toHaveAttribute('data-pr-state', 'merged');
    expect(screen.getByTestId('pr-github-unreachable')).toBeInTheDocument();
    expect(screen.getByTestId('pr-last-sync')).toHaveTextContent('last synced');

    // Field-only structured log (NEVER payload bytes).
    expect(warnSpy).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'prOutput.githubUnreachable', prState: 'merged' }),
    );
    warnSpy.mockRestore();
  });

  it('AC8 — file headers toggle via keyboard (Enter/Space)', async () => {
    const user = userEvent.setup();
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    const trigger = screen.getByTestId('pr-diff-file-trigger-0');
    trigger.focus();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await user.keyboard('{Enter}');
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    await user.keyboard(' ');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('AC8 — jump-to-changed-region moves focus to the next/previous file header', () => {
    render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    const trigger0 = screen.getByTestId('pr-diff-file-trigger-0');
    const trigger1 = screen.getByTestId('pr-diff-file-trigger-1');

    trigger0.focus();
    expect(trigger0).toHaveFocus();

    fireEvent.click(screen.getByTestId('pr-diff-jump-next'));
    expect(trigger1).toHaveFocus();

    fireEvent.click(screen.getByTestId('pr-diff-jump-prev'));
    expect(trigger0).toHaveFocus();
  });

  it('AC7 / 4.20 AC9 — the Compare control is reserved disabled unless actions include enter_compare_mode AND version > 1', () => {
    // A comparable (v2+) prOutput — a prior revision exists to diff against.
    const comparable = { ...prOutputArtifactView, version: 2 };
    const { rerender } = render(<PrOutputArtifactRenderer artifact={comparable} />);
    expect(screen.getByTestId('artifact-compare-entry')).toBeDisabled();

    // The old anticipated literal no longer enables it (renamed in story 4.20).
    rerender(<PrOutputArtifactRenderer artifact={comparable} actions={['compare']} />);
    expect(screen.getByTestId('artifact-compare-entry')).toBeDisabled();

    // A v1 artifact (no prior revision) stays disabled even with the backend action present — the
    // per-artifact version>1 gate (parallel to spec/plan) prevents a compare with no baseline.
    rerender(
      <PrOutputArtifactRenderer artifact={prOutputArtifactView} actions={['enter_compare_mode']} />,
    );
    expect(screen.getByTestId('artifact-compare-entry')).toBeDisabled();

    rerender(<PrOutputArtifactRenderer artifact={comparable} actions={['enter_compare_mode']} />);
    expect(screen.getByTestId('artifact-compare-entry')).toBeEnabled();
  });

  it('4.20 AC9 — clicking the enabled Compare control invokes onCompare (opens the overlay)', async () => {
    const onCompare = vi.fn();
    render(
      <PrOutputArtifactRenderer
        artifact={{ ...prOutputArtifactView, version: 2 }}
        actions={['enter_compare_mode']}
        onCompare={onCompare}
      />,
    );
    await userEvent.click(screen.getByTestId('artifact-compare-entry'));
    expect(onCompare).toHaveBeenCalledTimes(1);
  });

  it('renders the PR description body via the .prose typography utility (untrusted markdown)', () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();
    expect(container.querySelector('.prose')).not.toBeNull();
  });

  it('degrades gracefully when there is no linked PR (prLinkage null)', () => {
    render(<PrOutputArtifactRenderer artifact={{ ...prOutputArtifactView, prLinkage: null }} />);
    expect(screen.getByTestId('pr-reference-unlinked')).toBeInTheDocument();
    // Branch/commit fall back to plain text (no derivable owner/repo → no links).
    expect(screen.getByTestId('pr-branch-text')).toBeInTheDocument();
    expect(screen.getByTestId('pr-commit-text')).toBeInTheDocument();
    expect(screen.queryByTestId('pr-state-badge')).toBeNull();
  });
});

/**
 * Story 2.25 a11y — markdown + a keyboard-operable file accordion + reserved Compare
 * control. Each documented variant is axe-scanned (AC10).
 */
describe('PrOutputArtifactRenderer a11y (story 2.25 / AC10)', () => {
  it('AC10 — the default render has no axe violations', async () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    await expectNoA11yViolations(container);
  });

  it('AC10 — the XSS-bearing variant has no axe violations', async () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactViewXss} />);
    await expectNoA11yViolations(container);
  });

  it('AC10 — the stale-GitHub variant has no axe violations', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { container } = render(
      <PrOutputArtifactRenderer artifact={prOutputArtifactViewStaleGitHub} />,
    );
    await expectNoA11yViolations(container);
    warnSpy.mockRestore();
  });
});
