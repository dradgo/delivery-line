/**
 * Story 2.17 (AC2, AC5, AC7, AC8, AC11) — `SpecArtifactRenderer`.
 *
 * Fixture-driven render assertions (NOT `toMatchSnapshot` — no snapshot harness,
 * story 2.27). Covers the spec anatomy slots, the change-summary present/absent
 * gate, section-anchor keyboard navigation (best-effort scroll), and the reserved
 * disabled Compare control.
 */
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  specArtifactView,
  specArtifactViewWithChangeSummary,
} from '@/test/fixtures/artifact/artifactViewFixtures';
import { SpecArtifactRenderer } from './SpecArtifactRenderer';

beforeEach(() => {
  // jsdom has no layout engine; ensure scrollIntoView exists so we can spy on it.
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('SpecArtifactRenderer', () => {
  it('AC2 — renders every anatomy slot (type badge, revision, body, inline metadata, anchors)', () => {
    const { container } = render(<SpecArtifactRenderer artifact={specArtifactView} />);

    // Artifact-type badge — NOT a workflow-state badge (T5).
    expect(screen.getByTestId('artifact-type-badge')).toHaveTextContent('spec');
    expect(screen.queryByTestId('workflow-state-badge')).toBeNull();

    // Revision indicator.
    expect(screen.getByTestId('artifact-revision')).toHaveTextContent('v2');

    // Trusted metadata via MetadataChrome (title + classification rendered as props).
    expect(screen.getByText('Specification — DEL-9002 v2')).toBeInTheDocument();

    // Inline metadata region.
    expect(screen.getByTestId('artifact-created-at')).toBeInTheDocument();
    expect(screen.getByTestId('artifact-classification-badge')).toHaveTextContent('internal');
    expect(screen.getByTestId('artifact-checksum')).toBeInTheDocument();

    // Body went through SafeMarkdownRenderer (its data-component marker).
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();

    // Region entry-points.
    expect(screen.getByTestId('artifact-clarification-anchor')).toBeInTheDocument();
    expect(screen.getByTestId('artifact-approval-anchor')).toBeInTheDocument();
  });

  it('AC5 — the body is rendered with the .prose typography utility', () => {
    const { container } = render(<SpecArtifactRenderer artifact={specArtifactView} />);
    expect(container.querySelector('.prose')).not.toBeNull();
  });

  it('AC8 — section anchors derive from the markdown headings and exclude code fences', () => {
    render(<SpecArtifactRenderer artifact={specArtifactView} />);
    const nav = screen.getByTestId('artifact-section-anchors');
    const labels = Array.from(nav.querySelectorAll('button')).map((b) => b.textContent);
    expect(labels).toEqual(['Overview', 'Goals', 'Non-Goals', 'Implementation Notes']);
  });

  it('AC8 — anchors are keyboard-focusable buttons; activating scrolls to the heading', () => {
    render(<SpecArtifactRenderer artifact={specArtifactView} />);
    const nav = screen.getByTestId('artifact-section-anchors');
    const overview = Array.from(nav.querySelectorAll('button')).find(
      (b) => b.textContent === 'Overview',
    )!;

    // Focusable (a native button, in the tab order).
    overview.focus();
    expect(overview).toHaveFocus();

    fireEvent.keyDown(overview, { key: 'Enter' });
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(overview, { key: ' ' });
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(2);
  });

  it('AC8 - duplicate-heading anchors scroll to the matching occurrence, not the first label', () => {
    const duplicateHeadingArtifact = {
      ...specArtifactView,
      body: ['# Notes', '', 'First notes.', '', '## Notes', '', 'Second notes.'].join('\n'),
    };
    render(<SpecArtifactRenderer artifact={duplicateHeadingArtifact} />);

    const headings = screen.getAllByRole('heading', { name: 'Notes' });
    const firstScroll = vi.fn();
    const secondScroll = vi.fn();
    headings[0]!.scrollIntoView = firstScroll;
    headings[1]!.scrollIntoView = secondScroll;

    const anchors = screen.getAllByRole('button', { name: 'Notes' });
    fireEvent.click(anchors[1]!);
    expect(firstScroll).not.toHaveBeenCalled();
    expect(secondScroll).toHaveBeenCalledTimes(1);
  });

  it('AC2/OQ-4 - revision-history placeholder remains keyboard-focusable while unavailable', () => {
    render(<SpecArtifactRenderer artifact={specArtifactView} />);
    const revisionHistory = screen.getByTestId('artifact-revision-history-anchor');
    revisionHistory.focus();
    expect(revisionHistory).toHaveFocus();
    expect(revisionHistory).toHaveAttribute('aria-disabled', 'true');
  });

  it('AC8 - metadata and generated content are exposed as labeled regions', () => {
    render(<SpecArtifactRenderer artifact={specArtifactView} />);
    expect(screen.getByRole('region', { name: 'Artifact metadata' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Generated content' })).toBeInTheDocument();
  });

  it('AC2 — change-summary slot renders ONLY when changeSummary is non-null', () => {
    const { rerender } = render(<SpecArtifactRenderer artifact={specArtifactView} />);
    expect(screen.queryByTestId('artifact-change-summary')).toBeNull();

    rerender(<SpecArtifactRenderer artifact={specArtifactViewWithChangeSummary} />);
    const summary = screen.getByTestId('artifact-change-summary');
    expect(summary).toBeInTheDocument();
    expect(summary.querySelector('[data-component="safe-diff"]')).not.toBeNull();
  });

  it('AC9 — the Compare control is a reserved disabled affordance by default', () => {
    render(<SpecArtifactRenderer artifact={specArtifactView} />);
    const compare = screen.getByTestId('artifact-compare-entry');
    expect(compare).toBeDisabled();
    expect(compare).toHaveAttribute('title', 'Available in next release');
  });

  it('AC9 — the Compare control enables when the panel reports compareEnabled', () => {
    render(<SpecArtifactRenderer artifact={specArtifactView} compareEnabled />);
    const compare = screen.getByTestId('artifact-compare-entry');
    expect(compare).toBeEnabled();
    expect(compare).not.toHaveAttribute('title');
  });
});
