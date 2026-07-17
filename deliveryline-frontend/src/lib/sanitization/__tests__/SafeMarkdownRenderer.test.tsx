/**
 * Story 2.24 — XSS fixture loop + positive-set tests (AC7, AC12).
 *
 * Loops every `.md` fixture under `xss-fixtures/`, renders it through
 * `SafeMarkdownRenderer`, and asserts the paired `.expected.json` sidecar
 * contract. A single passing-XSS-fixture is build-blocking per AC8.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SafeMarkdownRenderer } from '../SafeMarkdownRenderer';

interface ExpectedContract {
  renderedTextContains?: string[];
  renderedTextDoesNotContain?: string[];
  noScriptElements?: boolean;
  noIframeElements?: boolean;
  noStyleElements?: boolean;
  noActiveElements?: boolean;
  noJavascriptHrefAnchors?: boolean;
  noDataUriImages?: boolean;
}

// Vite's `import.meta.glob` resolves fixture pairs at test time.
const markdownFixtures: Record<string, string> = import.meta.glob('./xss-fixtures/*.md', {
  query: '?raw',
  import: 'default',
  eager: true,
});
const expectedFixtures: Record<string, ExpectedContract> = import.meta.glob(
  './xss-fixtures/*.expected.json',
  { import: 'default', eager: true },
);

afterEach(() => {
  cleanup();
});

describe('SafeMarkdownRenderer — XSS fixture loop (AC7, AC12)', () => {
  const fixtureNames = Object.keys(markdownFixtures)
    .map((path) => path.replace(/^.*\//, '').replace(/\.md$/, ''))
    .sort();

  // Floor bumped from 11 (story 2.24/2.27) to 14 by story 3.35 (AC8): three implementation-plan
  // step-detail fixtures (`plan-step-detail-*`) join the build-blocking sweep, since a plan step's
  // `detail` renders through THIS renderer (ImplementationPlanArtifactRenderer routes step.detail →
  // SafeMarkdownRenderer). The diff-content fixtures live in their own loop in
  // SafeUnifiedDiffRenderer.test.tsx (a different renderer).
  //
  // Floor bumped from 14 to 16 by story 4.26 (AC8): two Compare-Mode spec-section fixtures
  // (`compare-spec-section-*`) join the sweep, since CompareMode's `MarkdownCell` routes the
  // untrusted prior/current spec-section delta text through THIS renderer (CompareMode.tsx). The
  // reconciliation-JSONB and classification-description Epic-4 content types render through their own
  // React-escape path and have a dedicated build-blocking loop in the workflows feature
  // (Epic4ContentSanitization.test.tsx), not here.
  expect(
    fixtureNames.length,
    'Fixture set must not shrink — at least sixteen attack-class fixtures required (AC7 + story 3.35 AC8 plan-step + story 4.26 AC8 compare-section fixtures)',
  ).toBeGreaterThanOrEqual(16);

  for (const name of fixtureNames) {
    const mdPath = `./xss-fixtures/${name}.md`;
    const expectedPath = `./xss-fixtures/${name}.expected.json`;

    it(`renders fixture "${name}" inert and matches its expected.json contract`, () => {
      const source = markdownFixtures[mdPath];
      const expected = expectedFixtures[expectedPath];
      expect(source, `fixture ${name} missing`).toBeDefined();
      expect(expected, `${name}.expected.json missing`).toBeDefined();

      const { container } = render(<SafeMarkdownRenderer source={source!} />);
      const renderedText = container.textContent;

      if (expected!.renderedTextContains) {
        for (const needle of expected!.renderedTextContains) {
          expect(
            renderedText,
            `${name}: rendered text should contain ${JSON.stringify(needle)}`,
          ).toContain(needle);
        }
      }
      if (expected!.renderedTextDoesNotContain) {
        for (const banned of expected!.renderedTextDoesNotContain) {
          expect(
            renderedText,
            `${name}: rendered text must NOT contain ${JSON.stringify(banned)}`,
          ).not.toContain(banned);
        }
      }
      if (expected!.noScriptElements === true) {
        expect(
          container.querySelectorAll('script'),
          `${name}: must not emit <script> elements`,
        ).toHaveLength(0);
      }
      if (expected!.noIframeElements === true) {
        expect(
          container.querySelectorAll('iframe'),
          `${name}: must not emit <iframe> elements`,
        ).toHaveLength(0);
      }
      if (expected!.noStyleElements === true) {
        expect(
          container.querySelectorAll('style'),
          `${name}: must not emit <style> elements`,
        ).toHaveLength(0);
      }
      if (expected!.noActiveElements === true) {
        expect(screen.queryAllByRole('script'), `${name}: no role="script" elements`).toHaveLength(
          0,
        );
        // Belt-and-suspenders DOM scan for executable handlers
        for (const el of container.querySelectorAll('*')) {
          for (const attr of el.attributes) {
            expect(
              attr.name.startsWith('on'),
              `${name}: element <${el.tagName}> retained event handler "${attr.name}"`,
            ).toBe(false);
          }
        }
      }
      if (expected!.noJavascriptHrefAnchors === true) {
        for (const a of container.querySelectorAll('a')) {
          const href = a.getAttribute('href') ?? '';
          expect(
            href.trim().toLowerCase().startsWith('javascript:'),
            `${name}: <a href> must not be a javascript: URL — got ${JSON.stringify(href)}`,
          ).toBe(false);
          expect(
            href.trim().toLowerCase().startsWith('data:'),
            `${name}: <a href> must not be a data: URL — got ${JSON.stringify(href)}`,
          ).toBe(false);
        }
      }
      if (expected!.noDataUriImages === true) {
        for (const img of container.querySelectorAll('img')) {
          const src = img.getAttribute('src') ?? '';
          expect(
            src.trim().toLowerCase().startsWith('data:'),
            `${name}: <img src> must not be a data: URI — got ${JSON.stringify(src)}`,
          ).toBe(false);
        }
      }
    });
  }
});

describe('SafeMarkdownRenderer — positive set (AC2, AC12)', () => {
  it('renders allowed tags from the AC2 allowlist correctly', () => {
    const source = [
      '# Heading 1',
      '',
      'Paragraph with **bold** and *italic* and `inline code`.',
      '',
      '- list item 1',
      '- list item 2',
      '',
      '[same-origin link](/path)',
      '',
      '| Col A | Col B |',
      '| ----- | ----- |',
      '| a1    | b1    |',
    ].join('\n');
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    expect(container.querySelector('h1')).toBeTruthy();
    expect(container.querySelector('strong')).toBeTruthy();
    expect(container.querySelector('em')).toBeTruthy();
    expect(container.querySelectorAll('li')).toHaveLength(2);
    expect(container.querySelector('table')).toBeTruthy();
    const sameOriginLink = container.querySelector('a[href="/path"]');
    expect(sameOriginLink).toBeTruthy();
    // Same-origin link: NO rel/target attrs.
    expect(sameOriginLink?.getAttribute('rel')).toBeNull();
    expect(sameOriginLink?.getAttribute('target')).toBeNull();
  });

  it('adds rel="noopener noreferrer" target="_blank" to external links', () => {
    const source = '[external](https://example.com/foo)';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const link = container.querySelector('a[href="https://example.com/foo"]');
    expect(link).toBeTruthy();
    expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link?.getAttribute('target')).toBe('_blank');
  });
});

describe('SafeMarkdownRenderer — Shiki active highlighting (AC3)', () => {
  it('renders token <span> elements for a supported language fence', async () => {
    const source = '```javascript\nconst x = 1;\n```';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    // Initial render is inert plain text — Shiki resolves the highlight
    // post-mount via the lazy-loaded grammar bundle.
    await waitFor(
      () => {
        const code = container.querySelector('code.language-javascript');
        expect(code, 'language-javascript <code> must be rendered').toBeTruthy();
        // Once highlighted, Shiki emits nested <span> elements with style
        // attributes for token colors. At minimum one styled span must exist.
        const styledSpans = code!.querySelectorAll('span[style]');
        expect(
          styledSpans.length,
          'Shiki must emit at least one styled token span after async highlight',
        ).toBeGreaterThan(0);
      },
      { timeout: 4000 },
    );
  });

  it('keeps <script> content inert when placed inside a javascript fence', async () => {
    const source = ['```javascript', "<script>alert('xss')</script>", 'const x = 1;', '```'].join(
      '\n',
    );
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    await waitFor(
      () => {
        const code = container.querySelector('code.language-javascript');
        expect(code).toBeTruthy();
        const styledSpans = code!.querySelectorAll('span[style]');
        expect(styledSpans.length).toBeGreaterThan(0);
      },
      { timeout: 4000 },
    );
    // The fixture text must still appear verbatim in the rendered DOM, but
    // never as an active <script> element — Shiki tokenizes the chars; the
    // angle brackets become token text, not HTML.
    expect(container.textContent).toContain("<script>alert('xss')</script>");
    expect(container.querySelectorAll('script')).toHaveLength(0);
  });

  it('falls back to plain text for an unsupported language', () => {
    const source = '```rust\nfn main() {}\n```';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    // No highlighter run — rust is not in SHIKI_LANGUAGES. The <code> still
    // carries the language- class but never gains styled token spans.
    const code = container.querySelector('code.language-rust');
    expect(code).toBeTruthy();
    expect(code!.textContent).toContain('fn main()');
  });
});
