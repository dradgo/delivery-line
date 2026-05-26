/**
 * Story 2.24 — MetadataChrome tests (AC6, AC10).
 */
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { MetadataChrome } from '../MetadataChrome';
import { SafeMarkdownRenderer } from '../SafeMarkdownRenderer';

afterEach(() => cleanup());

describe('MetadataChrome — trusted/generated separation (AC6)', () => {
  it('renders title/version/classification as plain React props (not markdown)', () => {
    const { container } = render(
      <MetadataChrome title="**fake bold**" version={3} classification="shareable-redacted">
        <p>body</p>
      </MetadataChrome>,
    );
    expect(screen.getByText('**fake bold**')).toBeTruthy();
    expect(container.querySelector('strong')).toBeNull();
    const versionEl = container.querySelector('[data-field="version"]');
    expect(versionEl?.textContent).toBe('3');
    const classEl = container.querySelector('[data-field="classification"]');
    expect(classEl?.textContent).toBe('shareable-redacted');
  });

  it('renders the "Generated content" boundary label above the children slot', () => {
    const { container } = render(
      <MetadataChrome title="x" version={1} classification="local-only">
        <SafeMarkdownRenderer source="body **md**" />
      </MetadataChrome>,
    );
    expect(screen.getByText('Generated content')).toBeTruthy();
    expect(container.querySelector('[data-region="generated-content"]')).toBeTruthy();
    expect(container.querySelector('[data-region="trusted-metadata"]')).toBeTruthy();
    // Markdown bold inside children IS rendered.
    expect(container.querySelector('[data-region="generated-content"] strong')).toBeTruthy();
  });
});
