/**
 * Story 2.17 (AC3) — stub variant renderers.
 *
 * They render a "coming in Epic 3" placeholder but MUST still compose the story-2.24
 * primitives (`MetadataChrome` + `SafeMarkdownRenderer` — 2.24 AC10 / T2), so Epic 3
 * only fills in the variant-specific anatomy. These tests pin both.
 *
 * Story 3.26 (T-STUBTEST) — the `implementationPlan` variant is now FULLY rendered
 * (`ImplementationPlanArtifactRenderer.test.tsx`), so its stub blocks were removed
 * from here. Only the `prOutput` stub remains (story 3.27 still pending).
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { prOutputArtifactView } from '@/test/fixtures/artifact/artifactViewFixtures';
import { PrOutputArtifactRenderer } from './PrOutputArtifactRenderer';

afterEach(cleanup);

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
// Story 2.25 a11y — axe scan for the remaining stub renderer.
// Non-interactive widget — axe-only (no keyboard tests required).
// ---------------------------------------------------------------------------
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
