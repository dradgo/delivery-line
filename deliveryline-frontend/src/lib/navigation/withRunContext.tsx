/**
 * Story 2.22 (AC10.c) — declarative HOC wrapping a component in a
 * `<RunContextBoundary>` for callers that prefer composition over the render
 * boundary. The wrapped component receives the boundary's identity props plus its
 * own props.
 */
import type { ComponentType } from 'react';

import { RunContextBoundary, type RunContextBoundaryProps } from './RunContextBoundary';

type BoundaryIdentity = Omit<RunContextBoundaryProps, 'children'>;

export function withRunContext<P extends object>(Component: ComponentType<P>) {
  function WithRunContext(props: P & BoundaryIdentity) {
    const { runId, artifactId, clarificationId } = props;
    return (
      <RunContextBoundary runId={runId} artifactId={artifactId} clarificationId={clarificationId}>
        {/* Forward the FULL props (identity included): a wrapped component that
            declares e.g. `runId` in its own props must still receive it — the
            boundary only reads the identity, it does not consume it. */}
        <Component {...props} />
      </RunContextBoundary>
    );
  }
  WithRunContext.displayName = `withRunContext(${Component.displayName ?? Component.name})`;
  return WithRunContext;
}
