/**
 * Story 3.26 (AC10, AC3, AC8) — `ImplementationPlanArtifactRenderer`.
 *
 * Fixture-driven render assertions (NOT `toMatchSnapshot` — no snapshot harness,
 * story 2.27). Drives the presentational renderer directly with constructed fixtures
 * (router/query-free), mirroring `SpecArtifactRenderer.test.tsx`. Covers: the anatomy
 * slots (type badge distinct from spec, revision, body, steps, context-refs); numbered
 * steps in order; expand/collapse via mouse AND keyboard; internal-vs-external context
 * refs; sanitization of scriptable payloads; the reserved disabled Compare control; and
 * axe-core zero violations across the documented variants.
 */
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import {
  implementationPlanArtifactView,
  implementationPlanArtifactViewXss,
} from '@/test/fixtures/artifact/artifactViewFixtures';
import { ImplementationPlanArtifactRenderer } from './ImplementationPlanArtifactRenderer';

afterEach(() => {
  cleanup();
  delete (window as { __xss_executed?: boolean }).__xss_executed;
});

describe('ImplementationPlanArtifactRenderer', () => {
  it('AC2 — renders every anatomy slot (type badge, revision, body, steps, context-refs)', () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />,
    );

    // Artifact-type badge — labeled `implementation-plan`, NOT a workflow-state badge (T5).
    expect(screen.getByTestId('artifact-type-badge')).toHaveTextContent('implementation-plan');
    expect(screen.queryByTestId('workflow-state-badge')).toBeNull();

    // Revision indicator.
    expect(screen.getByTestId('artifact-revision')).toHaveTextContent('v1');

    // Trusted metadata via MetadataChrome (title rendered as a prop).
    expect(screen.getByText('Implementation Plan — DEL-9002 v1')).toBeInTheDocument();

    // Body went through SafeMarkdownRenderer (its data-component marker).
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();

    // The two impl-plan-specific sections.
    expect(screen.getByTestId('artifact-plan-steps')).toBeInTheDocument();
    expect(screen.getByTestId('artifact-context-references')).toBeInTheDocument();
  });

  it('AC2/T5 — the type badge uses a variant DISTINCT from the spec variant (not secondary)', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    const badge = screen.getByTestId('artifact-type-badge');
    // The spec variant uses `variant="secondary"` (→ `bg-secondary`); this one must not.
    expect(badge.className).not.toContain('bg-secondary');
    expect(badge.className).toContain('text-foreground'); // the `outline` variant class
  });

  it('AC2/OQ-4 — the revision-history placeholder stays keyboard-focusable while unavailable', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    const history = screen.getByTestId('artifact-revision-history-anchor');
    history.focus();
    expect(history).toHaveFocus();
    expect(history).toHaveAttribute('aria-disabled', 'true');
  });

  it('AC2 — numbered steps render in order with their summaries', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    const triggers = screen
      .getAllByTestId(/^artifact-plan-step-trigger-\d+$/)
      .map((node) => node.textContent);

    expect(triggers).toHaveLength(3);
    expect(triggers[0]).toContain('Step 1');
    expect(triggers[0]).toContain('Add the payload validation guard');
    expect(triggers[1]).toContain('Step 2');
    expect(triggers[2]).toContain('Step 3');
  });

  it('AC8 — a step expands and collapses via mouse (click)', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    const trigger = screen.getByTestId('artifact-plan-step-trigger-1');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    // Detail (markdown) + complexity chip become visible.
    expect(screen.getByTestId('artifact-plan-step-complexity-1')).toHaveTextContent('M');
    expect(screen.getByTestId('artifact-plan-step-content-1')).toBeInTheDocument();

    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('AC8 — a step toggles via keyboard (Enter/Space) and arrow keys move focus', async () => {
    const user = userEvent.setup();
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    const trigger1 = screen.getByTestId('artifact-plan-step-trigger-1');
    const trigger2 = screen.getByTestId('artifact-plan-step-trigger-2');

    trigger1.focus();
    expect(trigger1).toHaveFocus();

    await user.keyboard('{Enter}');
    expect(trigger1).toHaveAttribute('aria-expanded', 'true');
    await user.keyboard(' ');
    expect(trigger1).toHaveAttribute('aria-expanded', 'false');

    // The Radix accordion gives arrow-key trigger navigation for free (AC8).
    await user.keyboard('{ArrowDown}');
    expect(trigger2).toHaveFocus();
  });

  it('AC2 — context references render internal placeholders and validated external anchors', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);

    // Internal spec ref — a keyboard-focusable placeholder button (no live target, D5).
    const internal = screen.getByTestId('artifact-context-ref-internal-0');
    expect(internal.tagName).toBe('BUTTON');
    expect(internal).toHaveTextContent('Approved specification — DEL-9002 v2');
    internal.focus();
    expect(internal).toHaveFocus();

    // External repo ref — a real anchor opening in a new tab.
    const repo = screen.getByTestId('artifact-context-ref-external-1');
    expect(repo.tagName).toBe('A');
    expect(repo).toHaveAttribute('href', 'https://github.com/dradgo/deliveryline');
    expect(repo).toHaveAttribute('target', '_blank');
    expect(repo).toHaveAttribute('rel', 'noopener noreferrer');

    // External branch ref — also a validated anchor.
    expect(screen.getByTestId('artifact-context-ref-external-2')).toHaveAttribute(
      'href',
      'https://github.com/dradgo/deliveryline/tree/feature/del-9002-payload-validation',
    );
  });

  it('AC3 — scriptable step text + a javascript: ref href never produce active markup', () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactViewXss} />,
    );

    // Expand the scriptable step so its UNTRUSTED detail markdown is rendered too.
    fireEvent.click(screen.getByTestId('artifact-plan-step-trigger-1'));

    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    expect((window as { __xss_executed?: boolean }).__xss_executed).toBeUndefined();

    // The `javascript:` external ref is rejected by validateUrlScheme → plain text, no <a>.
    expect(screen.queryByTestId('artifact-context-ref-external-1')).toBeNull();
    const textRef = screen.getByTestId('artifact-context-ref-text-1');
    expect(textRef.tagName).not.toBe('A');
    const anchors = Array.from(container.querySelectorAll('a'));
    const hasJavascriptHref = anchors.some((a) => {
      const href = a.getAttribute('href');
      return href !== null && href.startsWith('javascript:');
    });
    expect(hasJavascriptHref).toBe(false);
  });

  it('AC6 — the Compare control is a reserved disabled affordance by default', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    const compare = screen.getByTestId('artifact-compare-entry');
    expect(compare).toBeDisabled();
    expect(compare).toHaveAttribute('title', 'Available in next release');
  });

  it('AC6 — the Compare control enables when the container reports compareEnabled', () => {
    render(
      <ImplementationPlanArtifactRenderer
        artifact={implementationPlanArtifactView}
        compareEnabled
      />,
    );
    const compare = screen.getByTestId('artifact-compare-entry');
    expect(compare).toBeEnabled();
    expect(compare).not.toHaveAttribute('title');
  });

  it('renders the body via the .prose typography utility', () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />,
    );
    expect(container.querySelector('.prose')).not.toBeNull();
  });

  it('R3 — a body-only artifact (no steps/refs from the live wire) degrades gracefully', () => {
    // A body-only artifact omits steps/contextReferences entirely (the live wire shape).
    const { container } = render(
      <ImplementationPlanArtifactRenderer
        artifact={{
          artifactType: 'implementationPlan',
          artifactId: 'art_plan_body_only',
          title: 'Implementation Plan — body only',
          version: 1,
          classification: 'internal',
          createdAt: '2026-05-30T12:30:00Z',
          body: '# Plan\n\nBody only.',
        }}
      />,
    );
    // The body still renders via MetadataChrome + SafeMarkdownRenderer.
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();
    // Both sections show their empty-state notes rather than crashing.
    expect(screen.getByTestId('artifact-plan-steps-empty')).toBeInTheDocument();
    expect(screen.getByTestId('artifact-context-references-empty')).toBeInTheDocument();
  });

  it('a step with no detail still renders a content panel when expanded', () => {
    render(<ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />);
    // Step 3 has neither detail nor complexity.
    const trigger3 = screen.getByTestId('artifact-plan-step-trigger-3');
    fireEvent.click(trigger3);
    const content = screen.getByTestId('artifact-plan-step-content-3');
    expect(within(content).getByText('No further detail provided.')).toBeInTheDocument();
  });
});

/**
 * Story 2.25 a11y — the renderer is markdown + a keyboard-operable accordion + a
 * reserved disabled Compare control. Each documented variant is axe-scanned.
 */
describe('ImplementationPlanArtifactRenderer a11y (story 2.25 / AC10)', () => {
  it('AC10 — the default render has no axe violations', async () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC10 — the empty-body variant has no axe violations', async () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer
        artifact={{ ...implementationPlanArtifactView, body: '' }}
      />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC10 — the XSS-bearing variant has no axe violations', async () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactViewXss} />,
    );
    await expectNoA11yViolations(container);
  });
});
