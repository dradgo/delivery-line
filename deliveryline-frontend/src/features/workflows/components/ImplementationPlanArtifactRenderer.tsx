/**
 * Story 2.17 (AC3) — `ImplementationPlanArtifactRenderer` stub (Epic 3 scope).
 *
 * Intentionally thin: renders a "Renderer coming in Epic 3" placeholder, but STILL
 * composes the story-2.24 primitives (`MetadataChrome` + `SafeMarkdownRenderer`, both
 * from the `@/lib/sanitization` barrel — T1/T2, 2.24 AC10: variant renderers MUST
 * consume the sanctioned primitives, never roll their own). Epic 3 fills in the
 * implementation-plan-specific anatomy here without reshaping the panel dispatch.
 */
import { MetadataChrome, SafeMarkdownRenderer } from '@/lib/sanitization';

import { artifactTypeLabel, type ImplementationPlanArtifactView } from '../artifactView';

export interface ImplementationPlanArtifactRendererProps {
  artifact: ImplementationPlanArtifactView;
}

export function ImplementationPlanArtifactRenderer({
  artifact,
}: ImplementationPlanArtifactRendererProps) {
  return (
    <div
      data-testid="implementation-plan-artifact-renderer"
      data-artifact-type="implementationPlan"
    >
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
