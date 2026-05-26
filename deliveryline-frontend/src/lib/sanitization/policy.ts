/**
 * Story 2.24 — sanitization policy constants (AC2, AC4, AC9).
 *
 * Single source of truth for allowed tags, allowed URL schemes, the image
 * allowlist, and the Shiki language registry. Anything outside these
 * constants is dropped by the renderer.
 */
import type { Schema } from 'hast-util-sanitize';

/**
 * Allowed URL schemes for `<a>` href.
 * Trap T3 — scheme validation must be case-insensitive AND trim leading
 * whitespace. `\tjavascript:` and `JaVaScRiPt:` are common evasions.
 */
export const ALLOWED_URL_SCHEMES: readonly string[] = ['http', 'https', 'mailto'];

/**
 * Forbidden URL schemes — explicitly rejected (rendered as plain text, no
 * `<a>` emitted). Kept as a tracking constant so the test suite can iterate
 * the full set and a future contributor can see the rationale.
 */
export const FORBIDDEN_URL_SCHEMES: readonly string[] = [
  'javascript',
  'data',
  'file',
  'vbscript',
  'blob',
];

/**
 * Image allowlist — defaults to empty for MVP. Off-origin URLs render as a
 * link-only fallback (with full scheme validation), never as an `<img>`.
 * Trap T4 — extension point: future stories add hosts here.
 */
export const ALLOWED_IMAGE_HOSTS: readonly string[] = [];

/**
 * Shiki lazy language registry — only these languages are loaded.
 * AC9 — keep tight to stay under the 250 KB gzipped bundle threshold.
 */
export const SHIKI_LANGUAGES: readonly string[] = [
  'json',
  'yaml',
  'markdown',
  'bash',
  'typescript',
  'javascript',
  'python',
  'diff',
];

export type UrlSchemeResult = { ok: true; href: string } | { ok: false; reason: string };

/**
 * Validate that a URL uses an allowed scheme.
 *
 * Trap T3 — `.trim()` AND `.toLowerCase()` BEFORE scheme extraction; both
 * required to defeat `\tjavascript:` and `JaVaScRiPt:` style evasions.
 * Relative URLs (no scheme) are accepted as same-origin.
 */
export function validateUrlScheme(href: string | null | undefined): UrlSchemeResult {
  if (href === null || href === undefined) {
    return { ok: false, reason: 'empty href' };
  }
  const trimmed = href.trim();
  if (trimmed.length === 0) {
    return { ok: false, reason: 'empty href' };
  }
  // P2 (2026-05-26) — Reject protocol-relative URLs (`//evil.com/...`) and
  // backslash UNC-style (`\\evil.com\...`). Browsers resolve `//foo` to
  // `https://foo`, sidestepping our scheme allowlist; backslash variants
  // get normalized to forward-slash by some browsers/servers.
  if (
    trimmed.startsWith('//') ||
    trimmed.startsWith('\\\\') ||
    trimmed.startsWith('\\/') ||
    trimmed.startsWith('/\\')
  ) {
    return { ok: false, reason: 'protocol-relative url rejected' };
  }
  const colonIndex = trimmed.indexOf(':');
  if (colonIndex === -1) {
    return { ok: true, href: trimmed };
  }
  // Be careful: 'mailto:user@example.com' has the first colon at the scheme
  // boundary. URLs containing a path-fragment colon before the scheme
  // separator would mis-parse — but markdown autolinks always emit
  // scheme://... or mailto:..., so this is safe.
  const scheme = trimmed.slice(0, colonIndex).toLowerCase();
  if (ALLOWED_URL_SCHEMES.includes(scheme)) {
    return { ok: true, href: trimmed };
  }
  return { ok: false, reason: `scheme not allowed: ${scheme}` };
}

/**
 * Check whether an image URL targets an allowed host (same-origin or in
 * {@link ALLOWED_IMAGE_HOSTS}).
 */
export function isAllowedImageHost(src: string): boolean {
  const schemeResult = validateUrlScheme(src);
  if (!schemeResult.ok) {
    return false;
  }
  try {
    const url = new URL(schemeResult.href, window.location.origin);
    if (url.host === window.location.host) {
      return true;
    }
    return ALLOWED_IMAGE_HOSTS.includes(url.host);
  } catch {
    return false;
  }
}

/**
 * Strict sanitization schema for `rehype-sanitize` (AC2).
 *
 * Inline `style` is NEVER allowed (Trap T2 — Shiki must use CSS-class-only
 * output). Event handler attributes are rejected by omission. The schema
 * explicitly enumerates the allowed tag set and per-tag attributes.
 */
/**
 * `tagNames` controls which elements survive rehype-sanitize's walk over
 * the markdown-source HAST tree. The list mirrors AC2's "exactly" set
 * (headings/text/lists/links/tables/images) plus `del` for GFM
 * strikethrough (the `~~foo~~` syntax that remark-gfm emits — AC2 is
 * silent on strikethrough but remark-gfm is already required by AC2's
 * table support, so this is a coherent extension).
 *
 * Note: `span`, `ins`, `mark` are NOT in this list. The diff renderer
 * emits `<span>`/`<ins>`/`<del>` and the redaction filter emits `<mark>`
 * as React components AFTER the markdown pipeline finishes; they never
 * appear in the HAST tree rehype-sanitize sees. Keeping them out of
 * `tagNames` makes the AC2-allowlist reading literal and removes the
 * (small) attack surface of accepting them from a future markdown source
 * that toggles `skipHtml: false`. Tightened during code-review D7
 * (2026-05-26).
 */
export const SANITIZATION_SCHEMA: Schema = {
  tagNames: [
    'h1',
    'h2',
    'h3',
    'h4',
    'h5',
    'h6',
    'p',
    'strong',
    'em',
    'code',
    'pre',
    'blockquote',
    'hr',
    'br',
    'ul',
    'ol',
    'li',
    'a',
    'table',
    'thead',
    'tbody',
    'tr',
    'th',
    'td',
    'img',
    'del',
  ],
  attributes: {
    a: ['href', 'title', 'rel', 'target', 'className'],
    img: ['src', 'alt', 'title', 'width', 'height'],
    code: ['className'],
    pre: ['className'],
    th: ['align', 'scope'],
    td: ['align'],
    del: ['className'],
    ol: ['start'],
    li: ['value'],
  },
  protocols: {
    href: [...ALLOWED_URL_SCHEMES],
    src: ['http', 'https'],
  },
  // Belt-and-suspenders: explicitly strip the attack-class tags AC7
  // enumerates, even though they're excluded from tagNames above.
  strip: ['script', 'iframe', 'style', 'object', 'embed', 'form', 'input'],
  clobberPrefix: 'sanitized-',
  clobber: [],
};
