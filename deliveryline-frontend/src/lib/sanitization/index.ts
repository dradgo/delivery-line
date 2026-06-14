/**
 * Story 2.24 — sanitization package barrel.
 *
 * The ONLY sanctioned import surface for composites and renderers that need
 * to display untrusted runner output. Direct imports from individual files
 * inside this package are allowed within the package itself; consumers
 * outside `src/lib/sanitization/**` MUST import from this barrel.
 */
export { SafeMarkdownRenderer } from './SafeMarkdownRenderer';
export type { SafeMarkdownRendererProps } from './SafeMarkdownRenderer';
export { SafeDiffRenderer } from './SafeDiffRenderer';
export type { SafeDiffRendererProps } from './SafeDiffRenderer';
export { SafeUnifiedDiffRenderer } from './SafeUnifiedDiffRenderer';
export type { SafeUnifiedDiffRendererProps } from './SafeUnifiedDiffRenderer';
export {
  parseUnifiedDiff,
  countChangedLines,
  PR_DIFF_MAX_FILES,
  PR_DIFF_MAX_LINES,
} from './unifiedDiff';
export type { DiffLineKind, ParsedDiffLine, ParsedDiffHunk, ParsedDiffFile } from './unifiedDiff';
export { MetadataChrome } from './MetadataChrome';
export type { MetadataChromeProps } from './MetadataChrome';
export {
  ALLOWED_URL_SCHEMES,
  FORBIDDEN_URL_SCHEMES,
  ALLOWED_IMAGE_HOSTS,
  SHIKI_LANGUAGES,
  isAllowedImageHost,
  validateUrlScheme,
} from './policy';
export type { UrlSchemeResult } from './policy';
export { scanForRedactions, renderTextWithRedactions } from './redactionFilter';
export type { RedactionMatch, RedactionScanResult } from './redactionFilter';
