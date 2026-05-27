/**
 * Story 2.24 — redaction fixture loop (AC15, AC17, Trap T11).
 *
 * Mirrors the AC7 XSS fixture pattern: each `.txt` paired with `.expected.json`,
 * the test renders the source through SafeMarkdownRenderer and asserts the
 * sidecar contract. Build-blocking per AC8.
 */
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SafeMarkdownRenderer } from '../SafeMarkdownRenderer';
import { scanForRedactions } from '../redactionFilter';

interface RedactionExpected {
  expectedDetectedCategories?: string[];
  renderedTextContains?: string[];
  renderedTextDoesNotContain?: string[];
  noRedactionMarks?: boolean;
}

const textFixtures: Record<string, string> = import.meta.glob('./redaction-fixtures/*.txt', {
  query: '?raw',
  import: 'default',
  eager: true,
});
const expectedFixtures: Record<string, RedactionExpected> = import.meta.glob(
  './redaction-fixtures/*.expected.json',
  { import: 'default', eager: true },
);

afterEach(() => cleanup());

describe('Redaction-fixture loop (AC15, AC17)', () => {
  const fixtureNames = Object.keys(textFixtures)
    .map((path) => path.replace(/^.*\//, '').replace(/\.txt$/, ''))
    .sort();

  expect(fixtureNames.length).toBeGreaterThanOrEqual(5);

  for (const name of fixtureNames) {
    const txtPath = `./redaction-fixtures/${name}.txt`;
    const expectedPath = `./redaction-fixtures/${name}.expected.json`;

    it(`fixture "${name}" matches its expected.json`, () => {
      const source = textFixtures[txtPath]!;
      const expected = expectedFixtures[expectedPath]!;
      expect(expected, `${name}.expected.json missing`).toBeDefined();

      const scan = scanForRedactions(source);
      if (expected.expectedDetectedCategories !== undefined) {
        expect(scan.detectedCategories.sort(), `${name}: detected categories must match`).toEqual(
          [...expected.expectedDetectedCategories].sort(),
        );
      }

      const { container } = render(<SafeMarkdownRenderer source={source} />);
      const renderedText = container.textContent;

      if (expected.renderedTextContains) {
        for (const needle of expected.renderedTextContains) {
          expect(renderedText, `${name}: should contain ${JSON.stringify(needle)}`).toContain(
            needle,
          );
        }
      }
      if (expected.renderedTextDoesNotContain) {
        for (const banned of expected.renderedTextDoesNotContain) {
          expect(renderedText, `${name}: must NOT contain ${JSON.stringify(banned)}`).not.toContain(
            banned,
          );
        }
      }
      if (expected.noRedactionMarks === true) {
        expect(
          container.querySelectorAll('mark.redaction-applied'),
          `${name}: must not emit any <mark.redaction-applied> wrappers`,
        ).toHaveLength(0);
      }
    });
  }
});
