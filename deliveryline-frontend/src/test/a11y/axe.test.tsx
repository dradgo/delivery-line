/**
 * Story 2.25 (Task 1) — self-test for the axe-core harness.
 *
 * Validates that `expectNoA11yViolations` passes on accessible markup and fails
 * on a real WCAG violation, so the rest of the suite can trust it.
 */
import { render, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations, WCAG_21_AA_TAGS } from './axe';

afterEach(cleanup);

describe('expectNoA11yViolations harness', () => {
  it('passes on accessible markup', async () => {
    const { container } = render(
      <main>
        <h1>Accessible page</h1>
        <button type="button">Do the thing</button>
      </main>,
    );
    await expectNoA11yViolations(container);
  });

  it('fails on a real WCAG violation (image without alt text)', async () => {
    const { container } = render(
      <main>
        {/* eslint-disable-next-line jsx-a11y/alt-text -- intentionally invalid for the harness self-test */}
        <img src="/x.png" />
      </main>,
    );
    await expect(expectNoA11yViolations(container)).rejects.toThrow();
  });

  it('targets the WCAG 2.1 AA tag set', () => {
    expect(WCAG_21_AA_TAGS).toEqual(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']);
  });
});
