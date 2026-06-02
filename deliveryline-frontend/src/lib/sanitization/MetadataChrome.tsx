/**
 * Story 2.24 — MetadataChrome composition wrapper (AC6, AC10).
 *
 * Sanctioned surface for rendering an artifact body. Renders trusted
 * system metadata (title / version / classification) above a bordered
 * "Generated content" region that receives the SafeMarkdownRenderer slot
 * via children. Metadata is rendered as React props — never via markdown.
 *
 * All composite renderers (story 2.17 spec / implementation-plan / pr-output
 * variants and Epic 4 Compare Mode) MUST consume this wrapper rather than
 * rolling their own metadata chrome.
 */
import type { ReactNode } from 'react';

export interface MetadataChromeProps {
  title: string;
  version: number;
  classification: string;
  /** Optional sub-title slot for variant-specific metadata. */
  subtitle?: string;
  /** The sanitized body — typically a `<SafeMarkdownRenderer>`. */
  children: ReactNode;
  className?: string;
}

export function MetadataChrome({
  title,
  version,
  classification,
  subtitle,
  children,
  className,
}: MetadataChromeProps) {
  return (
    <article
      className={className}
      data-component="metadata-chrome"
      data-classification={classification}
    >
      <header data-region="trusted-metadata" aria-label="Trusted artifact metadata">
        <h2 data-field="title">{title}</h2>
        <dl>
          <div>
            <dt>Version</dt>
            <dd data-field="version">{version}</dd>
          </div>
          <div>
            <dt>Classification</dt>
            <dd data-field="classification" data-value={classification}>
              {classification}
            </dd>
          </div>
          {subtitle !== undefined && subtitle.length > 0 ? (
            <div>
              <dt>Subtitle</dt>
              <dd data-field="subtitle">{subtitle}</dd>
            </div>
          ) : null}
        </dl>
      </header>
      <section
        data-region="generated-content"
        aria-label="Generated content"
        className="metadata-chrome__generated-content"
      >
        <div data-label="generated-content-marker" aria-hidden="true">
          Generated content
        </div>
        {children}
      </section>
    </article>
  );
}
