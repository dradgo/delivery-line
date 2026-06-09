/**
 * Story 2.17 (AC3) — stub variant renderers.
 *
 * They render a "coming in Epic 3" placeholder but MUST still compose the story-2.24
 * primitives (`MetadataChrome` + `SafeMarkdownRenderer` — 2.24 AC10 / T2), so Epic 3
 * only fills in the variant-specific anatomy. These tests pin both.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import {
  implementationPlanArtifactView,
  prOutputArtifactView,
} from '@/test/fixtures/artifact/artifactViewFixtures';
import { ImplementationPlanArtifactRenderer } from './ImplementationPlanArtifactRenderer';
import { PrOutputArtifactRenderer } from './PrOutputArtifactRenderer';

afterEach(cleanup);

describe('ImplementationPlanArtifactRenderer (stub)', () => {
  it('renders the Epic-3 notice and composes the sanitization primitives', () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />,
    );
    expect(screen.getByTestId('artifact-stub-notice')).toHaveTextContent(
      'implementation-plan renderer coming in Epic 3',
    );
    // 2.24 AC10 / T2 — MUST compose MetadataChrome + SafeMarkdownRenderer.
    expect(container.querySelector('[data-component="metadata-chrome"]')).not.toBeNull();
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();
  });
});

describe('PrOutputArtifactRenderer (stub)', () => {
  it('renders the Epic-3 notice and composes the sanitization primitives', () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    expect(screen.getByTestId('artifact-stub-notice')).toHaveTextContent(
      'pr-output renderer coming in Epic 3',
    );
    expect(container.querySelector('[data-component="metadata-chrome"]')).not.toBeNull();
    expect(container.querySelector('[data-component="safe-markdown"]')).not.toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Story 2.25 a11y — axe scans for both stub renderers.
// Non-interactive widgets — axe-only (no keyboard tests required).
// ---------------------------------------------------------------------------
describe('ImplementationPlanArtifactRenderer a11y (story 2.25)', () => {
  it('AC2 — with body content has no axe violations', async () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer artifact={implementationPlanArtifactView} />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — with empty body has no axe violations', async () => {
    const { container } = render(
      <ImplementationPlanArtifactRenderer
        artifact={{ ...implementationPlanArtifactView, body: '' }}
      />,
    );
    await expectNoA11yViolations(container);
  });
});

describe('PrOutputArtifactRenderer a11y (story 2.25)', () => {
  it('AC2 — with body content has no axe violations', async () => {
    const { container } = render(<PrOutputArtifactRenderer artifact={prOutputArtifactView} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — with empty body has no axe violations', async () => {
    const { container } = render(
      <PrOutputArtifactRenderer artifact={{ ...prOutputArtifactView, body: '' }} />,
    );
    await expectNoA11yViolations(container);
  });
});
