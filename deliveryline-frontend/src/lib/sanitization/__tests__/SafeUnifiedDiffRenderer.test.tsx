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

/**
 * Story 3.35 (AC8, Task 5) — adversarial diff-content XSS fixture sweep, the diff-renderer
 * analogue of `SafeMarkdownRenderer.test.tsx`'s `xss-fixtures/` loop. Each `.diff` under
 * `diff-xss-fixtures/` carries an attack payload in a hunk line and/or the hunk header (the two
 * untrusted surfaces `SafeUnifiedDiffRenderer` routes through `renderTextWithRedactions`); the
 * paired `.expected.json` asserts the payload renders inert (no `<script>`/`<iframe>`/`<style>`/
 * `<a>`, no `on*` handlers) while its literal text is preserved. A single payload that survives
 * as live markup reds the build (AC8: one failing fixture is build-blocking).
 */
interface DiffExpectedContract {
  renderedTextContains?: string[];
  renderedTextDoesNotContain?: string[];
  noScriptElements?: boolean;
  noIframeElements?: boolean;
  noStyleElements?: boolean;
  noActiveElements?: boolean;
  noAnchorElements?: boolean;
}

const diffFixtures: Record<string, string> = import.meta.glob('./diff-xss-fixtures/*.diff', {
  query: '?raw',
  import: 'default',
  eager: true,
});
const diffExpectedFixtures: Record<string, DiffExpectedContract> = import.meta.glob(
  './diff-xss-fixtures/*.expected.json',
  { import: 'default', eager: true },
);

describe('SafeUnifiedDiffRenderer — diff-content XSS fixture loop (AC8)', () => {
  const fixtureNames = Object.keys(diffFixtures)
    .map((path) => path.replace(/^.*\//, '').replace(/\.diff$/, ''))
    .sort();

  // AC8 floor — the diff sanitization sweep must keep at least five attack-class fixtures
  // (script-in-line, img-onerror, javascript: URL, entity-encoded, polyglot header+line). Story
  // 2.27's regression block is EXPANDED here, not duplicated (the markdown loop keeps its own floor).
  //
  // Floor bumped from 5 to 6 by story 4.26 (AC8): a Compare-Mode prOutput file-diff fixture
  // (`compare-proutput-file-diff`) joins the sweep, since CompareMode's `CompareFileDiff` routes the
  // untrusted prOutput unified-diff delta through THIS renderer (CompareMode.tsx → SafeUnifiedDiffRenderer).
  expect(
    fixtureNames.length,
    'Diff XSS fixture set must not shrink — at least six attack-class fixtures required (AC8 + story 4.26 compare prOutput diff)',
  ).toBeGreaterThanOrEqual(6);

  for (const name of fixtureNames) {
    const diffPath = `./diff-xss-fixtures/${name}.diff`;
    const expectedPath = `./diff-xss-fixtures/${name}.expected.json`;

    it(`renders diff fixture "${name}" inert and matches its expected.json contract`, () => {
      const source = diffFixtures[diffPath];
      const expected = diffExpectedFixtures[expectedPath];
      expect(source, `fixture ${name} missing`).toBeDefined();
      expect(expected, `${name}.expected.json missing`).toBeDefined();

      const hunks = parseUnifiedDiff(source!).flatMap((file) => file.hunks);
      const { container } = render(<SafeUnifiedDiffRenderer hunks={hunks} />);
      const renderedText = container.textContent ?? '';

      for (const needle of expected!.renderedTextContains ?? []) {
        expect(
          renderedText,
          `${name}: rendered text should contain ${JSON.stringify(needle)}`,
        ).toContain(needle);
      }
      for (const banned of expected!.renderedTextDoesNotContain ?? []) {
        expect(
          renderedText,
          `${name}: rendered text must NOT contain ${JSON.stringify(banned)}`,
        ).not.toContain(banned);
      }
      if (expected!.noScriptElements === true) {
        expect(
          container.querySelectorAll('script'),
          `${name}: must not emit <script>`,
        ).toHaveLength(0);
      }
      if (expected!.noIframeElements === true) {
        expect(
          container.querySelectorAll('iframe'),
          `${name}: must not emit <iframe>`,
        ).toHaveLength(0);
      }
      if (expected!.noStyleElements === true) {
        expect(container.querySelectorAll('style'), `${name}: must not emit <style>`).toHaveLength(
          0,
        );
      }
      if (expected!.noAnchorElements === true) {
        expect(
          container.querySelectorAll('a'),
          `${name}: diff text must never become an <a>`,
        ).toHaveLength(0);
      }
      if (expected!.noActiveElements === true) {
        for (const el of container.querySelectorAll('*')) {
          for (const attr of el.attributes) {
            expect(
              attr.name.startsWith('on'),
              `${name}: <${el.tagName}> retained event handler "${attr.name}"`,
            ).toBe(false);
          }
        }
      }
    });
  }
});
