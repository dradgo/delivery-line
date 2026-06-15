/**
 * Story 3.31 (Task 7 / AC6) — backend-truth sourcing contract (NFR20 wrong-link
 * prevention). The PR reference a reviewer trusts MUST come from the workflow-detail /
 * list read models (the projection of `integration_links` — backend truth), NEVER from
 * a runner-emitted artifact (`useArtifact` / `artifactView`, which can drift).
 *
 * This is enforced STRUCTURALLY: the PR-linkage renderers source `prLinkage` from their
 * `RunContextView` / `RunQueueRow` props and must not import the artifact hook/view for
 * PR data. (The render-side proof — the displayed ref equals the view-model's value — is
 * in the component tests.)
 */
import { describe, expect, it } from 'vitest';

import runContextStripSource from '../components/RunContextStrip.tsx?raw';
import runReviewQueueItemSource from '../components/RunReviewQueueItem.tsx?raw';
import prLinkageDetailsSource from '../components/PrLinkageDetails.tsx?raw';

const PR_RENDERERS: ReadonlyArray<readonly [string, string]> = [
  ['RunContextStrip', runContextStripSource],
  ['RunReviewQueueItem', runReviewQueueItemSource],
  ['PrLinkageDetails', prLinkageDetailsSource],
];

describe('story 3.31 AC6 — PR data sourced from the view-models, never from artifacts', () => {
  it.each(PR_RENDERERS)('%s does not import useArtifact', (_name, source) => {
    expect(source).not.toMatch(/useArtifact/);
  });

  it.each(PR_RENDERERS)('%s does not import the artifact view for PR data', (_name, source) => {
    expect(source).not.toMatch(/from ['"][^'"]*artifactView['"]/);
    expect(source).not.toMatch(/from ['"][^'"]*ArtifactView['"]/);
  });
});
