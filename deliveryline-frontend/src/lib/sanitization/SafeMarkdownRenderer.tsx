/**
 * Story 2.24 — SafeMarkdownRenderer (AC1, AC2, AC3, AC4, AC15, AC17).
 *
 * Wraps `react-markdown` with `rehype-sanitize` configured against the strict
 * SANITIZATION_SCHEMA. Custom component overrides for `<a>` (URL-scheme
 * validation + rel/target hardening), `<img>` (host allowlist fallback to
 * link), and `<code>` (Shiki syntax-highlighted via HAST → React, NEVER via
 * raw HTML injection). A second-pass redaction filter (AC15) runs over the
 * produced React tree to wrap backend-missed secret patterns in
 * `<mark class="redaction-applied">`.
 *
 * Trap T1 — Shiki output is NEVER injected via `dangerouslySetInnerHTML`.
 *           `codeToHast` returns a HAST tree which is converted directly to
 *           React elements via `hast-util-to-jsx-runtime`. No HTML strings.
 * Trap T2 — Inline `style` on Shiki token spans is intentional and safe:
 *           values come from Shiki's fixed token-color tables, never from
 *           untrusted input. The `rehype-sanitize` style-strip still applies
 *           to the markdown source HAST path. Class-only output (the
 *           original spec design) is preserved as a future enhancement.
 * Trap T5 — the redaction filter walks the rendered text tree (not source).
 */
import { toJsxRuntime } from 'hast-util-to-jsx-runtime';
import { ExternalLink } from 'lucide-react';
import {
  Fragment,
  type ReactNode,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { jsx, jsxs } from 'react/jsx-runtime';
import ReactMarkdown, { type Components } from 'react-markdown';
import rehypeSanitize from 'rehype-sanitize';
import remarkGfm from 'remark-gfm';
import {
  createHighlighterCore,
  type HighlighterCore,
} from 'shiki/core';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';
import type { Element as HastElement, Root as HastRoot, RootContent } from 'hast';

import {
  isAllowedImageHost,
  SANITIZATION_SCHEMA,
  SHIKI_LANGUAGES,
  validateUrlScheme,
} from './policy';
import { renderTextWithRedactions, scanForRedactions } from './redactionFilter';

/**
 * Shiki highlighter singleton — created on first use. The explicit
 * `langs` + `themes` arrays use dynamic `import(...)` so the bundler can
 * code-split exactly the grammars in {@link SHIKI_LANGUAGES} and exclude
 * everything else (AC9 bundle-delta hygiene). The JavaScript regex engine
 * is preferred over Oniguruma WASM to avoid shipping a ~600 KB WASM blob.
 */
let highlighterPromise: Promise<HighlighterCore> | null = null;

function getHighlighter(): Promise<HighlighterCore> {
  if (highlighterPromise === null) {
    highlighterPromise = createHighlighterCore({
      themes: [import('@shikijs/themes/github-light')],
      langs: [
        import('@shikijs/langs/json'),
        import('@shikijs/langs/yaml'),
        import('@shikijs/langs/markdown'),
        import('@shikijs/langs/bash'),
        import('@shikijs/langs/typescript'),
        import('@shikijs/langs/javascript'),
        import('@shikijs/langs/python'),
        import('@shikijs/langs/diff'),
      ],
      engine: createJavaScriptRegexEngine(),
    });
  }
  return highlighterPromise;
}

export interface SafeMarkdownRendererProps {
  /** Raw, untrusted markdown source from the runner. */
  source: string;
  /**
   * Optional className on the wrapping `<div>`. The renderer's internal
   * components never receive caller-supplied className overrides.
   */
  className?: string;
}

/**
 * Component override for `<a>`. Validates the URL scheme, drops the link
 * entirely on rejection (renders the literal href text instead), and adds
 * `rel="noopener noreferrer" target="_blank"` plus an ExternalLink icon for
 * off-origin links.
 */
function SafeAnchor({
  href,
  children,
  ...rest
}: {
  href?: string;
  children?: ReactNode;
} & React.AnchorHTMLAttributes<HTMLAnchorElement>): ReactNode {
  const validation = validateUrlScheme(href);
  if (!validation.ok) {
    return (
      <span data-testid="rejected-link" data-reason={validation.reason}>
        {children}
        {href !== undefined ? ` (${href})` : ''}
      </span>
    );
  }
  // P12 (2026-05-26) — `target="_blank"` + `rel` on a `mailto:` href is
  // a no-op for the mail-client handler (and visually misleading); only
  // apply the external-link decorations to http/https targets that resolve
  // off-origin. Same logic for the ExternalLink icon below.
  let isExternal = false;
  try {
    const url = new URL(validation.href, window.location.origin);
    isExternal =
      (url.protocol === 'http:' || url.protocol === 'https:') &&
      url.host !== window.location.host;
  } catch {
    isExternal = false;
  }
  // Strip any caller-supplied event handlers via the rest cast — only the
  // attributes our schema permits ever reach here.
  const cleaned: React.AnchorHTMLAttributes<HTMLAnchorElement> = {
    title: rest.title,
    className: typeof rest.className === 'string' ? rest.className : undefined,
  };
  return (
    <a
      {...cleaned}
      href={validation.href}
      rel={isExternal ? 'noopener noreferrer' : undefined}
      target={isExternal ? '_blank' : undefined}
    >
      {children}
      {isExternal ? (
        <ExternalLink className="external-link-icon" aria-hidden="true" />
      ) : null}
    </a>
  );
}

/**
 * Component override for `<img>`. Enforces the host allowlist; off-origin
 * images render as a link with the same scheme validation applied.
 */
function SafeImage({
  src,
  alt,
  ...rest
}: React.ImgHTMLAttributes<HTMLImageElement>): ReactNode {
  if (src === undefined || src === '') {
    return <span data-testid="rejected-image">{alt ?? ''}</span>;
  }
  const stringSrc = typeof src === 'string' ? src : '';
  if (!isAllowedImageHost(stringSrc)) {
    const validation = validateUrlScheme(stringSrc);
    if (!validation.ok) {
      return (
        <span data-testid="rejected-image" data-reason={validation.reason}>
          {alt ?? ''}
        </span>
      );
    }
    return (
      <a href={validation.href} rel="noopener noreferrer" target="_blank">
        {alt ?? validation.href}
        <ExternalLink className="external-link-icon" aria-hidden="true" />
      </a>
    );
  }
  return <img src={stringSrc} alt={alt ?? ''} title={rest.title} />;
}

/**
 * Recursively flatten a React children tree to a plain string so it can be
 * passed to Shiki as code text. Non-string nodes are ignored — react-markdown
 * only emits strings inside code-fence `<code>` so this branch is rare.
 */
function flattenChildrenToString(node: ReactNode): string {
  if (node === null || node === undefined || node === false || node === true) {
    return '';
  }
  if (typeof node === 'string') {
    return node;
  }
  if (typeof node === 'number') {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(flattenChildrenToString).join('');
  }
  // React element with children prop — descend.
  if (typeof node === 'object' && 'props' in node) {
    const props = (node as { props?: { children?: ReactNode } }).props;
    if (props !== undefined && 'children' in props) {
      return flattenChildrenToString(props.children);
    }
  }
  return '';
}

/**
 * Extract the children of the `<code>` element inside Shiki's `<pre><code>`
 * HAST output. We render those children inside our own `<code>` to avoid
 * a nested `<pre>` (react-markdown already wraps fenced blocks in `<pre>`).
 */
function extractShikiCodeChildren(hast: HastRoot): RootContent[] {
  const pre = hast.children.find(
    (child): child is HastElement =>
      child.type === 'element' && child.tagName === 'pre',
  );
  if (pre === undefined) {
    return [];
  }
  const code = pre.children.find(
    (child): child is HastElement =>
      child.type === 'element' && child.tagName === 'code',
  );
  if (code === undefined) {
    return [];
  }
  return code.children as RootContent[];
}

/**
 * Component override for `<code>`. Renders Shiki-highlighted token spans
 * (HAST → React via `hast-util-to-jsx-runtime`) when the fence's language
 * is in {@link SHIKI_LANGUAGES}; otherwise renders the code text plain.
 *
 * Trap T1 — never injects HTML strings; Shiki returns a HAST tree that we
 *           convert to React elements directly. The original code text is
 *           never parsed or executed as code — Shiki tokenizes it as a
 *           string and emits inert `<span>` elements per token.
 * Trap T2 — Inline `style` on token spans is shiki-controlled (token color
 *           lookup table), never derived from input.
 */
function SafeCode({
  className,
  children,
  ...rest
}: React.HTMLAttributes<HTMLElement>): ReactNode {
  const language =
    typeof className === 'string' && className.startsWith('language-')
      ? className.slice('language-'.length)
      : undefined;
  const finalClassName = language !== undefined ? `language-${language}` : undefined;
  const codeText = useMemo(() => flattenChildrenToString(children), [children]);
  const [highlighted, setHighlighted] = useState<ReactNode | null>(null);

  // P1 (2026-05-26) — Pre-scan the code text for redaction patterns. If the
  // block contains a secret (Shiki tokenization would otherwise hide the
  // `[REDACTED:…]` wrap inside a colored span), skip Shiki entirely and
  // render the text via the redaction filter. Trade-off: code blocks
  // containing secrets lose syntax highlighting; redaction is preserved.
  const containsRedactionTarget = useMemo(
    () => scanForRedactions(codeText).matches.length > 0,
    [codeText],
  );

  const supportedLanguage =
    language !== undefined && SHIKI_LANGUAGES.includes(language) ? language : null;

  useEffect(() => {
    if (supportedLanguage === null || codeText.length === 0 || containsRedactionTarget) {
      setHighlighted(null);
      return;
    }
    let cancelled = false;
    getHighlighter()
      .then((highlighter) =>
        highlighter.codeToHast(codeText, {
          lang: supportedLanguage,
          theme: 'github-light',
        }),
      )
      .then((hast) => {
        if (cancelled) {
          return;
        }
        const codeChildren = extractShikiCodeChildren(hast);
        if (codeChildren.length === 0) {
          return;
        }
        const root: HastRoot = { type: 'root', children: codeChildren };
        // hast-util-to-jsx-runtime returns a React element. Inline styles
        // on the produced token spans come from Shiki's fixed color tables
        // — never from untrusted input (Trap T2 deviation documented in
        // README "Bundle impact" + "Trap reference").
        const node = toJsxRuntime(root, {
          Fragment,
          jsx: jsx as never,
          jsxs: jsxs as never,
        }) as ReactNode;
        setHighlighted(node);
      })
      .catch(() => {
        // Highlighter failure (unknown language grammar, parse error) falls
        // back to inert plain text — security property is preserved.
        if (!cancelled) {
          setHighlighted(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [codeText, supportedLanguage, containsRedactionTarget]);

  if (containsRedactionTarget) {
    return (
      <code className={finalClassName} {...rest}>
        {renderTextWithRedactions(codeText)}
      </code>
    );
  }

  return (
    <code className={finalClassName} {...rest}>
      {highlighted ?? children}
    </code>
  );
}

const COMPONENTS: Components = {
  a: SafeAnchor as NonNullable<Components['a']>,
  img: SafeImage as NonNullable<Components['img']>,
  code: SafeCode as NonNullable<Components['code']>,
  // Apply the redaction filter to text content of paragraphs, list items,
  // table cells, headings, and blockquotes. The wrapper passes children
  // through `renderTextWithRedactions`, which walks the React tree and
  // wraps matched patterns in `<mark class="redaction-applied">`.
  p: ({ children }) => <p>{renderTextWithRedactions(children)}</p>,
  li: ({ children }) => <li>{renderTextWithRedactions(children)}</li>,
  td: ({ children }) => <td>{renderTextWithRedactions(children)}</td>,
  th: ({ children }) => <th>{renderTextWithRedactions(children)}</th>,
  h1: ({ children }) => <h1>{renderTextWithRedactions(children)}</h1>,
  h2: ({ children }) => <h2>{renderTextWithRedactions(children)}</h2>,
  h3: ({ children }) => <h3>{renderTextWithRedactions(children)}</h3>,
  h4: ({ children }) => <h4>{renderTextWithRedactions(children)}</h4>,
  h5: ({ children }) => <h5>{renderTextWithRedactions(children)}</h5>,
  h6: ({ children }) => <h6>{renderTextWithRedactions(children)}</h6>,
  blockquote: ({ children }) => <blockquote>{renderTextWithRedactions(children)}</blockquote>,
};

/**
 * The sanctioned safe-markdown renderer. ALL untrusted artifact bodies in the
 * UI MUST go through this component. Direct use of `dangerouslySetInnerHTML`
 * is forbidden by the AC11 ESLint rule outside of `src/lib/sanitization/**`.
 */
export function SafeMarkdownRenderer({ source, className }: SafeMarkdownRendererProps) {
  // Memoize sanitization plugin instance to avoid re-creating it on every
  // render — re-creating the plugin doesn't change behavior but causes
  // unnecessary work inside react-markdown's unified pipeline.
  const rehypePlugins = useMemo(
    () => [[rehypeSanitize, SANITIZATION_SCHEMA] as const],
    [],
  );
  const remarkPlugins = useMemo(() => [remarkGfm], []);
  return (
    <div className={className} data-component="safe-markdown">
      <ReactMarkdown
        remarkPlugins={remarkPlugins}
        rehypePlugins={rehypePlugins as never}
        components={COMPONENTS}
        skipHtml
      >
        {source}
      </ReactMarkdown>
    </div>
  );
}
