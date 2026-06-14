/**
 * Story 3.27 (Task 7 / AC2, AC3, AC5) — SafeUnifiedDiffRenderer tests.
 *
 * Proves the primitive renders `<ins>`/`<del>`/`<span>` with the stable token classes,
 * routes untrusted line text + hunk headers through the redaction path (script renders
 * inert), and caps rendered lines with a "showing N of M" note (no silent truncation).
 */
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SafeUnifiedDiffRenderer } from '../SafeUnifiedDiffRenderer';
import { parseUnifiedDiff } from '../unifiedDiff';

afterEach(() => cleanup());

const SAMPLE_DIFF = [
  'diff --git a/x.ts b/x.ts',
  '--- a/x.ts',
  '+++ b/x.ts',
  '@@ -1,3 +1,3 @@',
  ' const shared = 1;',
  '-const old = 2;',
  '+const next = 3;',
].join('\n');

function hunksOf(diff: string) {
  return parseUnifiedDiff(diff)[0]?.hunks ?? [];
}

describe('SafeUnifiedDiffRenderer', () => {
  it('renders added/removed/context lines with the stable token classes', () => {
    const { container } = render(<SafeUnifiedDiffRenderer hunks={hunksOf(SAMPLE_DIFF)} />);
    expect(container.querySelector('ins.diff-line-added')?.textContent).toContain(
      'const next = 3;',
    );
    expect(container.querySelector('del.diff-line-removed')?.textContent).toContain(
      'const old = 2;',
    );
    expect(container.querySelector('span.diff-line-context')?.textContent).toContain(
      'const shared = 1;',
    );
  });

  it('renders the hunk header', () => {
    const { container } = render(<SafeUnifiedDiffRenderer hunks={hunksOf(SAMPLE_DIFF)} />);
    const header = container.querySelector('[data-diff-hunk-header="true"]');
    expect(header?.textContent).toContain('@@ -1,3 +1,3 @@');
  });

  it('never interprets HTML — a script in a diff line renders inert', () => {
    const scripted = [
      '@@ -1,1 +1,2 @@',
      ' context',
      '+const x = "<script>window.__xss = 1;</script>";',
    ].join('\n');
    const { container } = render(<SafeUnifiedDiffRenderer hunks={hunksOf(scripted)} />);
    expect(container.querySelector('script')).toBeNull();
    expect(container.textContent).toContain('<script>window.__xss = 1;</script>');
  });

  it('caps rendered lines at maxLines and surfaces a "showing N of M" note (AC5)', () => {
    const many = ['@@ -1,6 +1,6 @@', ...Array.from({ length: 6 }, (_, i) => `+line ${i + 1}`)].join(
      '\n',
    );
    const { container } = render(<SafeUnifiedDiffRenderer hunks={hunksOf(many)} maxLines={3} />);
    expect(container.querySelectorAll('ins.diff-line-added')).toHaveLength(3);
    expect(screen.getByTestId('diff-line-cap-note')).toHaveTextContent('Showing the first 3 of 6');
  });

  it('renders nothing extra for an empty hunk list', () => {
    const { container } = render(<SafeUnifiedDiffRenderer hunks={[]} />);
    expect(container.querySelector('[data-component="safe-unified-diff"]')).not.toBeNull();
    expect(container.querySelector('ins, del')).toBeNull();
  });
});
