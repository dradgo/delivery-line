/**
 * Story 2.22 — shared type surface for the navigation library.
 *
 * Pure types + two runtime exports (the {@link InvalidNavigationTargetError}
 * class and the {@link MAX_BREADCRUMB_STACK_DEPTH} cap constant). No React, no
 * router imports — this module is the dependency-free root every other
 * navigation file builds on.
 */

/**
 * The SIX "meaningful" navigation contexts the breadcrumb stack tracks (AC3.b).
 * Transitional URLs (a 404 landing, a redirect) are NOT one of these and are
 * never pushed onto the stack.
 */
export type BreadcrumbKind =
  | 'queue'
  | 'runDetail'
  | 'artifact'
  | 'clarification'
  | 'compareMode'
  | 'recoveryDeepDive';

/**
 * A single entry in the per-session breadcrumb stack (AC3.b). `runId` /
 * `artifactId` / `clarificationId` are populated per kind; `scrollY` +
 * `createdAt` support future restoration + ordering.
 */
export interface BreadcrumbEntry {
  readonly kind: BreadcrumbKind;
  readonly runId?: string;
  readonly artifactId?: string;
  readonly clarificationId?: string;
  readonly scrollY: number;
  readonly createdAt: number;
}

/**
 * The discriminated next-safe-action union an {@link "./types".NextAction}
 * carries (AC4.c, AC6). Every `<ErrorState>` REQUIRES one of these — there is
 * no path through the component without a next safe action (UX-DR17).
 */
export type NextAction = Retry | Refresh | NavigateBack | ContactSupport | DocsLink;

/** Re-run the failed operation. */
export interface Retry {
  readonly kind: 'Retry';
  readonly onRetry: () => void;
  readonly label?: string;
}

/** Refetch / reload the surrounding data. */
export interface Refresh {
  readonly kind: 'Refresh';
  readonly onRefresh: () => void;
  readonly label?: string;
}

/**
 * Return to the prior meaningful run context. Trap T9: this kind carries NO
 * callback — `<ErrorState>` consumes {@link "./useReturnToRunContext"} internally
 * so all "back" affordances share one implementation.
 */
export interface NavigateBack {
  readonly kind: 'NavigateBack';
  readonly label?: string;
}

/**
 * Link to operator support. Trap T10: when no `href` resolves (neither an
 * explicit prop nor `VITE_SUPPORT_URL`), the action renders as a DISABLED
 * placeholder rather than a broken link.
 */
export interface ContactSupport {
  readonly kind: 'ContactSupport';
  readonly href?: string;
  readonly label?: string;
}

/** Link to documentation. `href` is REQUIRED and scheme-validated at render. */
export interface DocsLink {
  readonly kind: 'DocsLink';
  readonly href: string;
  readonly label?: string;
}

/**
 * A captured run-context snapshot (AC10.a) — the scroll + identity state the
 * {@link "./RunContextBoundary"} restores on unmount. Orthogonal to the
 * breadcrumb stack (Trap T14): this tracks scroll/selection, not pages.
 */
export interface RunContextSnapshot {
  readonly runId: string;
  readonly artifactId?: string | undefined;
  readonly clarificationId?: string | undefined;
  readonly scrollY: number;
  readonly mainPaneScrollTop: number;
}

/**
 * Thrown synchronously by {@link "./useNavigateToArtifact"} /
 * {@link "./useNavigateToClarification"} when an id fails its public-id shape
 * check (AC1, Trap T6 — the untrusted-string defense lives at the source).
 */
export class InvalidNavigationTargetError extends Error {
  /** Which id kind failed validation. */
  readonly target: 'artifact' | 'clarification' | 'run';
  /** The offending value (already known to be a malformed id). */
  readonly invalidValue: string;

  constructor(target: 'artifact' | 'clarification' | 'run', invalidValue: string) {
    super(`Invalid ${target} navigation target: malformed public id`);
    this.name = 'InvalidNavigationTargetError';
    this.target = target;
    this.invalidValue = invalidValue;
  }
}

/**
 * Cap on the per-session breadcrumb stack (AC3.d). Exported so future stories
 * can grep for the limit instead of hard-coding `16`.
 */
export const MAX_BREADCRUMB_STACK_DEPTH = 16;
