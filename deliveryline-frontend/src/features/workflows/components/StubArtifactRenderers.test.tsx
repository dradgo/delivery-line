/**
 * Story 2.17 (AC3) — stub variant renderers.
 *
 * They render a "coming in Epic 3" placeholder but MUST still compose the story-2.24
 * primitives (`MetadataChrome` + `SafeMarkdownRenderer` — 2.24 AC10 / T2), so Epic 3
 * only fills in the variant-specific anatomy. These tests pin both.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

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
