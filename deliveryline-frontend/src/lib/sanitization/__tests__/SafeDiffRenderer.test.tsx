/**
 * Story 2.24 — SafeDiffRenderer tests (AC5, AC12).
 */
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SafeDiffRenderer } from '../SafeDiffRenderer';

afterEach(() => cleanup());

describe('SafeDiffRenderer', () => {
  it('never interprets HTML in before/after — script in either side renders inert', () => {
    const { container } = render(
      <SafeDiffRenderer
        before="<script>alert('xss-diff-before')</script>"
        after="<script>alert('xss-diff-after')</script>"
      />,
    );
    expect(container.querySelectorAll('script')).toHaveLength(0);
    expect(container.textContent).toContain("<script>alert('xss-diff-before')</script>");
    expect(container.textContent).toContain("<script>alert('xss-diff-after')</script>");
  });

  it('renders removed lines via <del> on the before side', () => {
    const { container } = render(
      <SafeDiffRenderer before={'line a\nline b\nline c'} after={'line a\nline c'} />,
    );
    const dels = container.querySelectorAll('del.diff-line-removed');
    expect(dels.length).toBeGreaterThanOrEqual(1);
    const foundBLine = Array.from(dels).some((el) => el.textContent === 'line b');
    expect(foundBLine).toBe(true);
  });

  it('renders added lines via <ins> on the after side', () => {
    const { container } = render(
      <SafeDiffRenderer before={'line a\nline c'} after={'line a\nline b\nline c'} />,
    );
    const ins = container.querySelectorAll('ins.diff-line-added');
    expect(ins.length).toBeGreaterThanOrEqual(1);
    const foundBLine = Array.from(ins).some((el) => el.textContent === 'line b');
    expect(foundBLine).toBe(true);
  });

  it('renders context lines via <span class="diff-line-context"> on both sides', () => {
    const { container } = render(
      <SafeDiffRenderer before={'shared line'} after={'shared line'} />,
    );
    const contexts = container.querySelectorAll('span.diff-line-context');
    expect(contexts.length).toBe(2);
  });

  it('reports duplicate-line deletions correctly (D5 regression — set-based diff lost count)', () => {
    // The earlier set-based implementation marked all three before-lines as
    // "context" because the single after-line was present in the after set.
    // With proper LCS (Myers via diffLines), two of the three "a" lines on
    // the before side must be removed; the surviving one is context.
    const { container } = render(
      <SafeDiffRenderer before={'a\na\na'} after={'a'} />,
    );
    const dels = container.querySelectorAll('del.diff-line-removed');
    const beforeContexts = container.querySelectorAll(
      'section[data-side="before"] span.diff-line-context',
    );
    expect(dels.length).toBe(2);
    expect(beforeContexts.length).toBe(1);
  });

  it('reports duplicate-line insertions correctly', () => {
    // Inverse of the above — one before "a", three after; two of the three
    // after-lines must be added; the surviving one is context.
    const { container } = render(
      <SafeDiffRenderer before={'a'} after={'a\na\na'} />,
    );
    const ins = container.querySelectorAll('ins.diff-line-added');
    const afterContexts = container.querySelectorAll(
      'section[data-side="after"] span.diff-line-context',
    );
    expect(ins.length).toBe(2);
    expect(afterContexts.length).toBe(1);
  });
});
