/**
 * Story 2.24 — URL-scheme validation + image-allowlist policy tests (AC4, AC12).
 */
import { describe, expect, it } from 'vitest';

import {
  ALLOWED_URL_SCHEMES,
  FORBIDDEN_URL_SCHEMES,
  isAllowedImageHost,
  validateUrlScheme,
} from '../policy';

describe('validateUrlScheme (AC4)', () => {
  for (const scheme of ALLOWED_URL_SCHEMES) {
    it(`accepts ${scheme}:`, () => {
      const result = validateUrlScheme(`${scheme}:foo`);
      expect(result.ok).toBe(true);
    });
  }
  for (const scheme of FORBIDDEN_URL_SCHEMES) {
    it(`rejects ${scheme}:`, () => {
      const result = validateUrlScheme(`${scheme}:foo`);
      expect(result.ok).toBe(false);
    });
    it(`rejects ${scheme.toUpperCase()}: (case-insensitive — Trap T3)`, () => {
      const result = validateUrlScheme(`${scheme.toUpperCase()}:foo`);
      expect(result.ok).toBe(false);
    });
    it(`rejects "\\t${scheme}:" (whitespace-trim — Trap T3)`, () => {
      const result = validateUrlScheme(`\t${scheme}:foo`);
      expect(result.ok).toBe(false);
    });
    it(`rejects "  ${scheme}:" (leading space — Trap T3)`, () => {
      const result = validateUrlScheme(`  ${scheme}:foo`);
      expect(result.ok).toBe(false);
    });
  }
  it('rejects mixed-case JaVaScRiPt: evasion', () => {
    const result = validateUrlScheme('JaVaScRiPt:alert(1)');
    expect(result.ok).toBe(false);
  });
  it('accepts a relative path (no scheme — same-origin)', () => {
    const result = validateUrlScheme('/path/to/thing');
    expect(result.ok).toBe(true);
  });
  it('rejects null/undefined/empty', () => {
    expect(validateUrlScheme(null).ok).toBe(false);
    expect(validateUrlScheme(undefined).ok).toBe(false);
    expect(validateUrlScheme('').ok).toBe(false);
    expect(validateUrlScheme('   ').ok).toBe(false);
  });
  // P2 (2026-05-26) — protocol-relative URLs route to off-origin in browsers
  // without ever touching our scheme allowlist. They MUST be rejected.
  it('P2 — rejects protocol-relative //evil.example/path', () => {
    expect(validateUrlScheme('//evil.example/path').ok).toBe(false);
  });
  it('P2 — rejects backslash UNC-style \\\\evil.example\\path', () => {
    expect(validateUrlScheme('\\\\evil.example\\path').ok).toBe(false);
  });
  it('P2 — rejects mixed slash/backslash prefix \\\\/evil.example', () => {
    expect(validateUrlScheme('\\/evil.example').ok).toBe(false);
    expect(validateUrlScheme('/\\evil.example').ok).toBe(false);
  });
  it('P2 — rejects whitespace-prefixed protocol-relative \\t//evil.example', () => {
    // Trim happens first, so the leading // is still caught.
    expect(validateUrlScheme('\t//evil.example/x').ok).toBe(false);
  });
});

describe('isAllowedImageHost (Trap T4 — defaults to same-origin only)', () => {
  it('accepts same-origin URLs', () => {
    expect(isAllowedImageHost('/local/image.png')).toBe(true);
    expect(isAllowedImageHost(`${window.location.origin}/local.png`)).toBe(true);
  });
  it('rejects off-origin URLs by default', () => {
    expect(isAllowedImageHost('https://evil.example/image.png')).toBe(false);
    expect(isAllowedImageHost('https://other-host.example/x.png')).toBe(false);
  });
  it('rejects javascript:/data:/file:/vbscript:/blob: image sources', () => {
    expect(isAllowedImageHost('javascript:alert(1)')).toBe(false);
    expect(isAllowedImageHost('data:image/png;base64,AAAA')).toBe(false);
    expect(isAllowedImageHost('file:///etc/passwd')).toBe(false);
    expect(isAllowedImageHost('vbscript:foo')).toBe(false);
    expect(isAllowedImageHost('blob:foo')).toBe(false);
  });
});
