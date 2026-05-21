import type { ReactNode } from 'react';
import {
  CircleHelp,
  FileX,
  Link2Off,
  MapPinOff,
  PackageOpen,
  SearchX,
  ShieldX,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';

import { Container, Stack } from '@/components/layout';

/**
 * Story 2.5 — shared "this link goes nowhere good" UI states for the route tree.
 *
 * Lives under `src/routes/-states/`: the `-` ignore prefix (router default
 * `routeFileIgnorePrefix`) means the TanStack Router generator does NOT treat
 * these as routes — they are plain components the route files compose.
 *
 * These are MINIMAL, accessible placeholders built from the story-2.4 layout
 * primitives (`Container`/`Stack`) + the 2.4 typography classes + 2.3 state
 * tokens. They are deliberately not product UI.
 *
 * SEAM (story 2.22): when the shared empty-state / feedback-pattern infrastructure
 * lands, these dead-end states adopt that component instead of this local shell.
 */
interface DeadEndStateProps {
  /** Distinct lucide glyph so each state is recognizable at a glance (paired with text — never icon/color alone). */
  icon: LucideIcon;
  /** Short page-title-level summary of what happened. */
  title: string;
  /** Plain-language explanation of the situation and what is safe to do next. */
  children: ReactNode;
}

function DeadEndState({ icon: Icon, title, children }: DeadEndStateProps) {
  return (
    <Container className="py-12" data-testid="dead-end-state">
      <Stack gap="2" className="max-w-prose items-start">
        <Icon className="size-8 text-text-tertiary" aria-hidden />
        <h1 className="text-page-title">{title}</h1>
        <div className="text-body text-text-secondary">{children}</div>
      </Stack>
    </Container>
  );
}

/** AC2 — the route param did not match the `run_`/`art_` public-id shape. */
export function InvalidLinkState() {
  return (
    <DeadEndState icon={Link2Off} title="Invalid link">
      <p>
        This link contains an identifier that isn&rsquo;t in the expected format, so it can&rsquo;t
        point to a real run or artifact. Check the URL — it may have been copied incompletely or
        corrupted in transit.
      </p>
    </DeadEndState>
  );
}

/** AC4 — backend reported no run for this (well-formed) id. Wired by 2.6/2.28. */
export function RunNotFoundState({ workflowRunId }: { workflowRunId?: string }) {
  return (
    <DeadEndState icon={SearchX} title="Run not found">
      <p>
        No workflow run matches{' '}
        {workflowRunId !== undefined && workflowRunId !== '' ? (
          <code className="text-meta">{workflowRunId}</code>
        ) : (
          'this identifier'
        )}
        . It may have been removed under a retention policy, or the link may be out of date.
      </p>
    </DeadEndState>
  );
}

/** AC4 — backend reported no artifact for this (well-formed) id. Wired by 2.6/2.28. */
export function ArtifactNotFoundState({ artifactId }: { artifactId?: string }) {
  return (
    <DeadEndState icon={FileX} title="Artifact not found">
      <p>
        No artifact matches{' '}
        {artifactId !== undefined && artifactId !== '' ? (
          <code className="text-meta">{artifactId}</code>
        ) : (
          'this identifier'
        )}{' '}
        on this run. It may belong to a different run, or the link may be out of date.
      </p>
    </DeadEndState>
  );
}

/** AC1 — any URL that matched no route at all. */
export function PageNotFoundState() {
  return (
    <DeadEndState icon={MapPinOff} title="Page not found">
      <p>
        This address doesn&rsquo;t match any page in the review workspace. Use the run queue to find
        the run you&rsquo;re looking for.
      </p>
    </DeadEndState>
  );
}

/** Generic error boundary — distinct from any not-found state. */
export function GenericErrorState() {
  return (
    <DeadEndState icon={TriangleAlert} title="Something went wrong">
      <p>
        An unexpected error interrupted this view. Reloading usually clears it; if it keeps
        happening, the run may need operator attention.
      </p>
    </DeadEndState>
  );
}

/**
 * AC8 (UX-DR6) case (a) — a run reported in a workflow state this build does not
 * recognize (e.g. a future Epic-3+ state seen from an older Epic-2 build). Render
 * an explicit "newer state" panel rather than crashing or guessing.
 */
export function UnrecognizedRunStateState({ currentStage }: { currentStage?: string }) {
  return (
    <DeadEndState icon={CircleHelp} title="This run is in a newer state">
      <p>
        This run reports{' '}
        {currentStage !== undefined && currentStage !== '' ? (
          <code className="text-meta">{currentStage}</code>
        ) : (
          'a workflow state'
        )}{' '}
        that this version of the workspace doesn&rsquo;t know how to display yet. Updating to a
        newer build should resolve it. Nothing about the run has changed.
      </p>
    </DeadEndState>
  );
}

/**
 * AC8 (UX-DR6) case (b) — an artifact whose type the current Artifact Review Panel
 * cannot render yet (ARP variants land across 2.17 / 3.26 / 3.27).
 */
export function UnrenderableArtifactState({ artifactType }: { artifactType?: string }) {
  return (
    <DeadEndState icon={PackageOpen} title="This artifact type isn’t viewable here yet">
      <p>
        {artifactType !== undefined && artifactType !== '' ? (
          <>
            Artifacts of type <code className="text-meta">{artifactType}</code> aren&rsquo;t
          </>
        ) : (
          <>This artifact type isn&rsquo;t</>
        )}{' '}
        renderable in this build of the review panel. The viewer for it arrives in a later release.
      </p>
    </DeadEndState>
  );
}

/**
 * AC8 (UX-DR6) case (c) — a navigation the recorded actor role is not associated
 * with. This is a RECORDED-role-aware DISPLAY only — the UI never enforces
 * permissions or approval eligibility (architecture.md:519); the backend remains
 * the authority. The label marks the role as recorded, not enforced.
 */
export function PermissionRestrictedState() {
  return (
    <DeadEndState icon={ShieldX} title="Not available for your recorded role">
      <p>
        Your recorded role isn&rsquo;t associated with this view. This is an informational signal
        based on recorded role context — it isn&rsquo;t a security control, and access decisions are
        made by the backend, not here.
      </p>
    </DeadEndState>
  );
}
