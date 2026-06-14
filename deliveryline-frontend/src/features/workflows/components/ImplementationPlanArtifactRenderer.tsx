/**
 * Story 3.26 — `ImplementationPlanArtifactRenderer`.
 *
 * The implementation-plan variant of the generalized Artifact Review Panel (story
 * 2.17 AC3 — replacing that story's "coming in Epic 3" stub). It is the sibling of
 * {@link SpecArtifactRenderer}: same skeleton (type `Badge`, revision indicator +
 * disabled history anchor, `MetadataChrome` + `SafeMarkdownRenderer` body, reserved
 * disabled Compare control) with the spec-only change-summary/section-anchor slots
 * swapped for two impl-plan sections:
 *
 *   • a STRUCTURED-STEPS section — a numbered list rendered with the Radix-backed
 *     `Accordion` (`type="multiple"`). The keyboard semantics AC8 demands (Tab to the
 *     accordion, arrow-key trigger navigation, Enter/Space toggle) come from the
 *     primitive, not hand-rolled handlers (D4). Each step's `summary` renders as
 *     React-escaped plain text in the trigger (T-STEPHTML — the block-level
 *     `SafeMarkdownRenderer` cannot nest inside the `<button>` trigger); the `detail`
 *     renders through `SafeMarkdownRenderer` inside the content, and the
 *     `estimatedComplexity` as a labeled chip.
 *   • a CONTEXT-REFERENCES section — internal refs (the approved spec artifact) render
 *     as keyboard-focusable OQ-4 placeholder controls (no live deep-link yet, D5);
 *     external refs (repo/branch) render an `<a target="_blank">` ONLY when their href
 *     passes `validateUrlScheme`, otherwise plain escaped text.
 *
 * All untrusted text (step summary/detail, ref labels) routes through React
 * text-escaping or `SafeMarkdownRenderer` — never `dangerouslySetInnerHTML` (story
 * 2.24, AC3). Presentational + prop-driven: it takes a resolved
 * `ImplementationPlanArtifactView` and never fetches (story 2.17 OQ-1).
 */
import { Badge } from '@/components/ui/badge';
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { MetadataChrome, SafeMarkdownRenderer, validateUrlScheme } from '@/lib/sanitization';

import {
  artifactTypeLabel,
  type ImplementationPlanArtifactView,
  type ImplementationPlanContextRef,
} from '../artifactView';

export interface ImplementationPlanArtifactRendererProps {
  artifact: ImplementationPlanArtifactView;
  /**
   * AC6 — whether the Compare-Mode entry control is enabled. The CONTAINER derives this
   * from the backend-reported allowed actions (NO frontend permission inference, D2);
   * it is always the safe default `false` live, so the control stays a reserved disabled
   * affordance until Compare Mode (Epic 4) ships.
   */
  compareEnabled?: boolean;
}

/** Human-readable label for a context-reference kind (trusted, typed — never markdown). */
function contextRefKindLabel(kind: ImplementationPlanContextRef['kind']): string {
  switch (kind) {
    case 'spec':
      return 'spec';
    case 'repository':
      return 'repository';
    case 'branch':
      return 'branch';
    default:
      return 'reference';
  }
}

/** A single context reference — an external validated `<a>`, an internal placeholder, or escaped text. */
function ContextRefItem({
  refItem,
  index,
}: {
  refItem: ImplementationPlanContextRef;
  index: number;
}) {
  const kindLabel = contextRefKindLabel(refItem.kind);
  // External refs (repo/branch) get a real anchor ONLY when the href passes the scheme
  // allowlist; otherwise they fall back to plain escaped text (never an unvalidated href).
  const validation = refItem.internal ? null : validateUrlScheme(refItem.href);

  return (
    <li
      className="flex flex-wrap items-center gap-2"
      data-testid={`artifact-context-ref-${index}`}
      data-context-ref-kind={refItem.kind}
      data-context-ref-internal={refItem.internal}
    >
      <Badge variant="outline" className="shrink-0">
        {kindLabel}
      </Badge>
      {refItem.internal ? (
        // Internal target (approved spec artifact) — keyboard-focusable OQ-4 placeholder
        // (like the spec renderer's clarification/approval anchors); no live deep-link yet (D5).
        <button
          type="button"
          title="In-app navigation is available in a later release"
          className="rounded-sm text-sm text-text-secondary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid={`artifact-context-ref-internal-${index}`}
        >
          {refItem.label}
        </button>
      ) : validation?.ok === true ? (
        <a
          href={validation.href}
          target="_blank"
          rel="noopener noreferrer"
          className="rounded-sm text-sm text-text-secondary underline underline-offset-2 hover:text-text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid={`artifact-context-ref-external-${index}`}
        >
          {refItem.label}
        </a>
      ) : (
        <span
          className="text-sm text-text-secondary"
          data-testid={`artifact-context-ref-text-${index}`}
        >
          {refItem.label}
        </span>
      )}
    </li>
  );
}

export function ImplementationPlanArtifactRenderer({
  artifact,
  compareEnabled = false,
}: ImplementationPlanArtifactRendererProps) {
  // R3 — steps/contextReferences are OPTIONAL on the read model (the live wire DTO omits
  // them, rendering body-only); default to empty so the sections degrade gracefully.
  const steps = artifact.steps ?? [];
  const contextReferences = artifact.contextReferences ?? [];

  return (
    // AC7 primacy — full width within `<main>`; NO min-width below the 36rem floor and
    // NO auto-collapse class (mirror SpecArtifactRenderer / the panel).
    <div
      className="w-full"
      data-testid="implementation-plan-artifact-renderer"
      data-artifact-type="implementationPlan"
    >
      {/* Revision + type chrome — surfaced near the TOP, visually secondary. The type
          badge uses a variant DISTINCT from the spec's `secondary` (T5 / AC2). */}
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <Badge variant="outline" data-testid="artifact-type-badge">
          {artifactTypeLabel(artifact.artifactType)}
        </Badge>
        <span
          className="inline-flex items-center gap-1 text-sm text-text-secondary"
          data-testid="artifact-revision"
        >
          <span className="font-medium text-text-primary">v{artifact.version}</span>
          {/* Revision-history view is deferred — a keyboard-focusable placeholder anchor (OQ-4). */}
          <button
            type="button"
            aria-disabled="true"
            title="Revision history — available in a later release"
            className="rounded-sm text-annotation uppercase tracking-wide text-text-tertiary"
            data-testid="artifact-revision-history-anchor"
          >
            history
          </button>
        </span>
      </div>

      {/* Trusted metadata + the untrusted markdown body, via the sanctioned chrome (T2). */}
      <MetadataChrome
        title={artifact.title}
        version={artifact.version}
        classification={artifact.classification}
      >
        <SafeMarkdownRenderer source={artifact.body} className="prose" />
      </MetadataChrome>

      {/* Structured-steps section (AC2 / AC8 / D4) — a numbered list via the Radix
          accordion. `role="list"`/`role="listitem"` give ordered semantics without the
          invalid `<ol><div>` nesting Radix items would produce. */}
      <section aria-label="Implementation steps" className="mt-4" data-testid="artifact-plan-steps">
        <h3 className="mb-1 text-section-heading">Steps</h3>
        {steps.length > 0 ? (
          <Accordion type="multiple" role="list">
            {steps.map((step, index) => {
              const stepNumber = index + 1;
              return (
                <AccordionItem
                  key={`step-${stepNumber}`}
                  value={`step-${stepNumber}`}
                  role="listitem"
                  data-testid={`artifact-plan-step-${stepNumber}`}
                >
                  <AccordionTrigger data-testid={`artifact-plan-step-trigger-${stepNumber}`}>
                    {/* Summary is UNTRUSTED → React-escaped plain text. The block-level
                        SafeMarkdownRenderer CANNOT nest in this <button> (T-STEPHTML). */}
                    <span className="flex min-w-0 items-baseline gap-2">
                      <span className="shrink-0 font-semibold text-text-primary">
                        Step {stepNumber}
                      </span>
                      <span className="min-w-0 text-text-secondary">{step.summary}</span>
                    </span>
                  </AccordionTrigger>
                  <AccordionContent data-testid={`artifact-plan-step-content-${stepNumber}`}>
                    {step.estimatedComplexity !== undefined ? (
                      <p
                        className="mb-2 inline-flex items-center gap-1 text-meta text-text-tertiary"
                        data-testid={`artifact-plan-step-complexity-${stepNumber}`}
                      >
                        <span className="uppercase tracking-wide">Complexity</span>
                        <Badge variant="secondary">{step.estimatedComplexity}</Badge>
                      </p>
                    ) : null}
                    {step.detail !== undefined ? (
                      // Detail is UNTRUSTED markdown → through the sanitizer (AC3).
                      <SafeMarkdownRenderer source={step.detail} className="prose" />
                    ) : (
                      <p className="text-meta text-text-tertiary">No further detail provided.</p>
                    )}
                  </AccordionContent>
                </AccordionItem>
              );
            })}
          </Accordion>
        ) : (
          <p className="text-meta text-text-tertiary" data-testid="artifact-plan-steps-empty">
            This implementation plan has no steps.
          </p>
        )}
      </section>

      {/* Context-references section (AC2 / story 3.9 AC2) — internal placeholders + validated externals. */}
      <section
        aria-label="Context references"
        className="mt-4"
        data-testid="artifact-context-references"
      >
        <h3 className="mb-1 text-section-heading">Context references</h3>
        {contextReferences.length > 0 ? (
          <ul className="flex flex-col gap-1">
            {contextReferences.map((refItem, index) => (
              <ContextRefItem key={`${refItem.kind}-${index}`} refItem={refItem} index={index} />
            ))}
          </ul>
        ) : (
          <p
            className="text-meta text-text-tertiary"
            data-testid="artifact-context-references-empty"
          >
            No context references.
          </p>
        )}
      </section>

      {/* Reserved disabled Compare control (AC6 / story 2.17 AC9) — enabled/disabled
          PURELY from the container-supplied prop; the renderer never infers permissions (D2). */}
      <div className="mt-4 flex flex-wrap items-center gap-2" data-testid="artifact-region-anchors">
        <button
          type="button"
          disabled={!compareEnabled}
          title={compareEnabled ? undefined : 'Available in next release'}
          className="rounded-md border border-border px-2.5 py-1 text-sm text-text-secondary disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus"
          data-testid="artifact-compare-entry"
        >
          Compare
        </button>
      </div>
    </div>
  );
}
