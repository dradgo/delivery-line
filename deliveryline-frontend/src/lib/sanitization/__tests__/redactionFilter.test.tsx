/**
 * Story 2.24 — redaction filter tests (AC15, AC17) + Trap T11 distinction.
 */
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { SafeMarkdownRenderer } from '../SafeMarkdownRenderer';
import { scanForRedactions } from '../redactionFilter';

afterEach(() => cleanup());

describe('scanForRedactions (AC15)', () => {
  it('detects a github token in plain text', () => {
    const result = scanForRedactions('here is a token ghp_abcdefghijklmnopqrstuvwx1 in body');
    expect(result.detectedCategories).toContain('GITHUB_TOKEN');
    expect(result.matches.length).toBe(1);
  });
  it('detects a linear API key', () => {
    const result = scanForRedactions('LINEAR=lin_api_abcdefghijklmnopqrstuvwxyz0123456789');
    expect(result.detectedCategories).toContain('LINEAR_API_KEY');
  });
  it('detects an Idempotency-Key header', () => {
    const result = scanForRedactions('Idempotency-Key: 01H1234567890ABCDEF');
    expect(result.detectedCategories).toContain('IDEMPOTENCY_KEY');
  });
  it('does NOT detect anything in clean text', () => {
    const result = scanForRedactions('the quick brown fox jumps over the lazy dog');
    expect(result.matches.length).toBe(0);
    expect(result.detectedCategories.length).toBe(0);
  });
});

describe('SafeMarkdownRenderer applies the redaction filter (AC15, AC17)', () => {
  it('wraps newly-detected github tokens in <mark class="redaction-applied">', () => {
    const source = 'see token ghp_abcdefghijklmnopqrstuvwx2 in this line';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length).toBeGreaterThanOrEqual(1);
    const markText = marks[0]?.textContent ?? '';
    expect(markText).toContain('[REDACTED: GITHUB_TOKEN]');
    // The original token bytes never appear in rendered text.
    expect(container.textContent).not.toContain('ghp_abcdefghijklmnopqrstuvwx2');
  });

  it('Trap T11 — author-written literal [REDACTED] strings render unchanged', () => {
    const source = 'the literal [REDACTED] string stays unwrapped';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length).toBe(0);
    expect(container.textContent).toContain('[REDACTED]');
  });

  it('Trap T11 — backend-prefixed placeholders like [REDACTED_GITHUB_TOKEN] pass through unchanged', () => {
    const source = 'backend redaction: [REDACTED_GITHUB_TOKEN] here';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length).toBe(0);
    expect(container.textContent).toContain('[REDACTED_GITHUB_TOKEN]');
  });

  it('the <mark> wrapper carries a title attribute explaining the placeholder', () => {
    const source = 'tok lin_api_abcdefghijklmnopqrstuvwxyz0123456789';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const mark = container.querySelector('mark.redaction-applied');
    expect(mark).toBeTruthy();
    expect(mark?.getAttribute('title')).toContain('Redaction applied at render time');
  });

  it('P1 (regression) — wraps secrets inside <strong> bold markdown', () => {
    const source = 'see token **ghp_abcdefghijklmnopqrstuvwx3** in this line';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length, '<strong>-nested token must be wrapped').toBeGreaterThanOrEqual(1);
    expect(container.textContent).not.toContain('ghp_abcdefghijklmnopqrstuvwx3');
  });

  it('P1 (regression) — wraps secrets inside <em> italic markdown', () => {
    const source = 'see token *ghp_abcdefghijklmnopqrstuvwx4* in this line';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length, '<em>-nested token must be wrapped').toBeGreaterThanOrEqual(1);
    expect(container.textContent).not.toContain('ghp_abcdefghijklmnopqrstuvwx4');
  });

  it('P1 (regression) — wraps secrets inside inline-code `…`', () => {
    const source = 'use `ghp_abcdefghijklmnopqrstuvwx5` like this';
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length, 'inline-code token must be wrapped').toBeGreaterThanOrEqual(1);
    expect(container.textContent).not.toContain('ghp_abcdefghijklmnopqrstuvwx5');
  });

  it('P1 (regression) — wraps secrets inside fenced code blocks (Shiki skipped)', () => {
    const source = ['```javascript', 'const token = "ghp_abcdefghijklmnopqrstuvwx6"', '```'].join('\n');
    const { container } = render(<SafeMarkdownRenderer source={source} />);
    const marks = container.querySelectorAll('mark.redaction-applied');
    expect(marks.length, 'fenced-code token must be wrapped').toBeGreaterThanOrEqual(1);
    expect(container.textContent).not.toContain('ghp_abcdefghijklmnopqrstuvwx6');
  });
});
