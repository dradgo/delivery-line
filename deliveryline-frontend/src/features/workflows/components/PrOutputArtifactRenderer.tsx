/**
 * Story 2.17 (AC3) — `PrOutputArtifactRenderer` stub (Epic 3 scope).
 *
 * Intentionally thin: renders a "Renderer coming in Epic 3" placeholder, but STILL
 * composes the story-2.24 primitives (`MetadataChrome` + `SafeMarkdownRenderer`, both
 * from the `@/lib/sanitization` barrel — T1/T2, 2.24 AC10). Epic 3 fills in the
 * PR-output-specific anatomy here without reshaping the panel dispatch.
 */
import { MetadataChrome, SafeMarkdownRenderer } from '@/lib/sanitization';

import { artifactTypeLabel, type PrOutputArtifactView } from '../artifactView';

export interface PrOutputArtifactRendererProps {
  artifact: PrOutputArtifactView;
}

export function PrOutputArtifactRenderer({ artifact }: PrOutputArtifactRendererProps) {
  return (
    <div data-testid="pr-output-artifact-renderer" data-artifact-type="prOutput">
      <p className="mb-2 text-meta text-text-tertiary" data-testid="artifact-stub-notice">
        {artifactTypeLabel(artifact.artifactType)} renderer coming in Epic 3
      </p>
      <MetadataChrome
        title={artifact.title}
        version={artifact.version}
        classification={artifact.classification}
      >
        <SafeMarkdownRenderer source={artifact.body} className="prose" />
      </MetadataChrome>
    </div>
  );
}
