/**
 * Story 2.7 — the tri-pane application SHELL.
 *
 * The structural host every Epic-2 review surface plugs into: a left navigation
 * rail, a central main review pane, and a right supporting context panel, bound
 * by the artifact-primacy collapse invariant. It is the SKELETON only — it ships
 * NO composites (Run Context Strip 2.16, Artifact Review Panel 2.17, Clarification
 * Region 2.18, decision bar 2.19, queue UI 2.15/2.20). See `LAYOUT.md`.
 *
 * Mounted once in `src/routes/__root.tsx`, wrapping the router `<Outlet />`.
 *
 * Key invariants (full rationale in `src/features/workflows/LAYOUT.md`):
 *  - AC2 / TRAP 4 — artifact primacy: `<main>` carries a real `min-width`; the
 *    right `<aside>` is the element that yields width (shrinks, then collapses to
 *    a drawer) under viewport pressure. The main pane never narrows below its
 *    minimum while the context panel still has width to give.
 *  - AC4 / TRAP 2 — the right panel is a documented SLOT (`<ContextPanelSlot>`),
 *    not hard-coded content.
 *  - AC7 — exactly one `<nav>`, one `<main>`, one `<aside>` landmark on the page.
 *  - AC8 — one structurally stable frame across queue / run-detail / (future)
 *    Compare Mode + clarification states; it is not a "mode switch".
 *  - TRAP 5 — run identity reads `useWorkflowDetail`, never `useState`.
 */
import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { Link, useParams } from '@tanstack/react-router';
import { Circle, FolderCog, Inbox, Menu, PanelRight, X } from 'lucide-react';

import { Stack } from '@/components/layout';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { isValidRunId } from '@/lib/routing/publicId';
import { cn } from '@/lib/utils';
import { AppShellContext, type AppShellContextValue } from './AppShellContext';
import { RunIdentityRegion } from './RunIdentityRegion';
import { useResponsiveLayout } from './hooks/useResponsiveLayout';

/** The `<main>` landmark id — the skip-link target (Task 7, AC7). */
const MAIN_CONTENT_ID = 'main-content';
const CONTEXT_DRAWER_TITLE_ID = 'supporting-context-title';

/**
 * AC2 / TRAP 4 — the artifact-primacy floor. The central main pane never narrows
 * below this width on desktop + tablet; the right context panel yields first.
 * The true computed-pixel assertion lives in Playwright (stories 2.26 / 2.27) —
 * jsdom has no layout engine. The chosen tokens are documented in `LAYOUT.md`:
 * nav rail 16rem (fixed), context panel 20rem (preferred, shrinks), main ≥ 36rem.
 */
const DESKTOP_MAIN_MIN_WIDTH = 'min-w-[36rem]';
const TABLET_MAIN_MIN_WIDTH = 'min-w-[34rem]';

/** Skip-to-content link — the first focusable element, visible only on focus. */
function SkipToMainContentLink() {
  return (
    <a
      href={`#${MAIN_CONTENT_ID}`}
      className="sr-only focus:absolute focus:left-3 focus:top-3 focus:z-50 focus:not-sr-only focus:rounded-md focus:bg-surface-elevated focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:text-text-primary focus:shadow-lg focus:outline-none focus:ring-2 focus:ring-ring-focus"
    >
      Skip to main content
    </a>
  );
}

/** Primary navigation link — the run queue home. */
function QueueHomeLink({ onNavigate }: { onNavigate?: (() => void) | undefined }) {
  return (
    <Link
      to="/workflows"
      onClick={onNavigate}
      className={cn(
        'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-text-secondary',
        'transition-colors hover:bg-surface-elevated hover:text-text-primary',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        'data-[status=active]:bg-state-selected data-[status=active]:text-state-selected-foreground',
      )}
    >
      <Inbox className="size-4 shrink-0" aria-hidden />
      Run queue
    </Link>
  );
}

/** Story 3c-9 (AC1) — the projects management / configuration link. Mirrors `QueueHomeLink`. */
function ProjectsLink({ onNavigate }: { onNavigate?: (() => void) | undefined }) {
  return (
    <Link
      to="/projects"
      onClick={onNavigate}
      className={cn(
        'flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-text-secondary',
        'transition-colors hover:bg-surface-elevated hover:text-text-primary',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus',
        'data-[status=active]:bg-state-selected data-[status=active]:text-state-selected-foreground',
      )}
      data-testid="nav-projects-link"
    >
      <FolderCog className="size-4 shrink-0" aria-hidden />
      Projects
    </Link>
  );
}

/**
 * Global app-status indicator (AC3). A stable, labeled PLACEHOLDER — its live
 * content (backend connectivity / health) is a later concern; story 2.7 ships the
 * placement, not the logic.
 */
function StatusIndicator() {
  return (
    <div
      className="flex items-center gap-2 px-3 py-2 text-text-tertiary"
      data-testid="app-status-indicator"
    >
      <Circle className="size-2.5 shrink-0 fill-current" aria-hidden />
      <span className="text-sm text-text-secondary">System status</span>
      <span className="text-xs">not yet wired</span>
    </div>
  );
}

/** Persistent slim tablet context rail — keeps the landmark present without stealing width. */
function TabletContextRail({ onOpen }: { onOpen: () => void }) {
  return (
    <aside
      aria-label="Supporting context"
      className="flex w-12 shrink-0 items-start justify-center border-l border-border bg-surface px-2 py-4"
    >
      <Button
        variant="ghost"
        size="icon"
        onClick={onOpen}
        aria-label="Open supporting context"
        className="shrink-0"
      >
        <PanelRight className="size-4" aria-hidden />
      </Button>
    </aside>
  );
}

/** The inner content of the navigation rail — reused inline and in the mobile drawer. */
function NavRailContent({
  workflowRunId,
  showRunIdentity,
  onNavigate,
}: {
  workflowRunId: string | undefined;
  showRunIdentity: boolean;
  /** Invoked when a nav link is followed — the mobile drawer uses it to close. */
  onNavigate?: (() => void) | undefined;
}) {
  const hasRun = workflowRunId !== undefined && workflowRunId !== '';
  return (
    <Stack gap="4">
      <QueueHomeLink onNavigate={onNavigate} />
      <ProjectsLink onNavigate={onNavigate} />
      {showRunIdentity && hasRun ? <RunIdentityRegion workflowRunId={workflowRunId} /> : null}
      <StatusIndicator />
    </Stack>
  );
}

/** The empty state of the right context panel — shown when no composite occupies it. */
function ContextPanelEmpty({ collapsed }: { collapsed: boolean }) {
  if (collapsed) {
    // Desktop empty form: a slim strip that does not steal width from the artifact.
    return (
      <div className="flex justify-center pt-1">
        <PanelRight className="size-4 text-text-tertiary" aria-hidden />
        <span className="sr-only">No supporting context for this view.</span>
      </div>
    );
  }
  return (
    <p className="text-sm text-text-tertiary">
      No supporting context for this view yet. Run details, blockers, and clarifications appear here
      when a run is open.
    </p>
  );
}

/**
 * The right context panel's interior (AC4). Always renders the portal target so a
 * `<ContextPanelSlot>` has somewhere to mount; shows the empty state until a
 * composite occupies the slot.
 */
function ContextPanelRegion({
  mountRef,
  occupied,
  collapsed,
}: {
  mountRef: (node: HTMLElement | null) => void;
  occupied: boolean;
  collapsed: boolean;
}) {
  return (
    <>
      <div ref={mountRef} className={occupied ? undefined : 'hidden'} />
      {occupied ? null : <ContextPanelEmpty collapsed={collapsed} />}
    </>
  );
}

/** Persistent mobile top bar — run identity never disappears during collapse. */
function MobileTopBar({
  workflowRunId,
  onOpenNav,
  onOpenContext,
}: {
  workflowRunId: string | undefined;
  onOpenNav: () => void;
  onOpenContext: () => void;
}) {
  const hasRun = workflowRunId !== undefined && isValidRunId(workflowRunId);
  return (
    <header className="flex shrink-0 items-center gap-2 border-b border-border bg-surface px-3 py-2">
      <nav aria-label="Workflow navigation" className="flex min-w-0 flex-1 items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          onClick={onOpenNav}
          aria-label="Open workflow navigation"
        >
          <Menu className="size-5" aria-hidden />
        </Button>
        <div className="min-w-0 flex-1">
          {hasRun ? (
            <RunIdentityRegion workflowRunId={workflowRunId} variant="compact" />
          ) : (
            <span className="text-sm font-semibold text-text-primary">DeliveryLine</span>
          )}
        </div>
      </nav>
      <aside aria-label="Supporting context" className="shrink-0">
        <Button
          variant="ghost"
          size="icon"
          onClick={onOpenContext}
          aria-label="Open supporting context"
        >
          <PanelRight className="size-5" aria-hidden />
        </Button>
      </aside>
    </header>
  );
}

/**
 * The tri-pane application shell. `children` is the router `<Outlet />` — it
 * becomes the central main-pane content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const layout = useResponsiveLayout();
  // TRAP 5 — render identity is keyed off the route param, read with the backend
  // as the source of truth. `strict: false` — the shell mounts above every route.
  const params = useParams({ strict: false });
  const workflowRunId = isValidRunId(params.workflowRunId) ? params.workflowRunId : undefined;

  // AC4 — the right-panel slot. `contextPanelMount` is the live portal target;
  // `occupantCount` is reference-counted so concurrent `<ContextPanelSlot>`s do
  // not prematurely flip the panel back to its empty state.
  const [contextPanelMount, setContextPanelMount] = useState<HTMLElement | null>(null);
  const [parkedContextPanelMount, setParkedContextPanelMount] = useState<HTMLElement | null>(null);
  const [occupantCount, setOccupantCount] = useState(0);
  const [navDrawerOpen, setNavDrawerOpen] = useState(false);
  const [contextDrawerOpen, setContextDrawerOpen] = useState(false);

  const registerContextPanelOccupant = useCallback<
    AppShellContextValue['registerContextPanelOccupant']
  >(() => {
    setOccupantCount((count) => count + 1);
    return () => setOccupantCount((count) => count - 1);
  }, []);

  const isContextPanelOccupied = occupantCount > 0;
  const activeContextPanelMount = contextPanelMount ?? parkedContextPanelMount;

  const contextValue = useMemo<AppShellContextValue>(
    () => ({
      contextPanelMount: activeContextPanelMount,
      isContextPanelOccupied,
      registerContextPanelOccupant,
    }),
    [activeContextPanelMount, isContextPanelOccupied, registerContextPanelOccupant],
  );

  const isMobile = layout === 'mobile';
  const isTablet = layout === 'tablet';
  const isDesktop = layout === 'desktop';

  return (
    <AppShellContext.Provider value={contextValue}>
      <TooltipProvider>
        <SkipToMainContentLink />

        <div className="flex h-dvh w-full flex-col overflow-hidden bg-background">
          <div ref={setParkedContextPanelMount} className="hidden" aria-hidden="true" />

          {isMobile ? (
            <MobileTopBar
              workflowRunId={workflowRunId}
              onOpenNav={() => setNavDrawerOpen(true)}
              onOpenContext={() => setContextDrawerOpen(true)}
            />
          ) : null}

          <div className="flex flex-1 overflow-hidden">
            {/* Left navigation rail — inline on desktop + tablet (AC3). */}
            {isMobile ? null : (
              <nav
                aria-label="Workflow navigation"
                className={cn(
                  'shrink-0 overflow-y-auto border-r border-border bg-surface p-4',
                  isTablet ? 'w-56' : 'w-64',
                )}
              >
                <NavRailContent workflowRunId={workflowRunId} showRunIdentity />
              </nav>
            )}

            {/* Central main review pane — always present, the single `<main>` (AC1, AC9). */}
            <main
              id={MAIN_CONTENT_ID}
              tabIndex={-1}
              className={cn(
                'flex-1 overflow-y-auto bg-background pb-12 focus:outline-none',
                // AC6 — the main pane carries more vertical headroom than the side
                // panels (pt-8 vs pt-4) to signal visual primacy.
                isMobile ? 'px-4 pt-6' : 'px-6 pt-8',
                // AC2 / TRAP 4 — the artifact-primacy min-width floor. Relaxed on
                // mobile, where main is already the single column.
                isMobile ? 'min-w-0' : isTablet ? TABLET_MAIN_MIN_WIDTH : DESKTOP_MAIN_MIN_WIDTH,
              )}
            >
              {children}
            </main>

            {isTablet ? <TabletContextRail onOpen={() => setContextDrawerOpen(true)} /> : null}

            {/* Right supporting context panel — inline on desktop only (AC4). */}
            {isDesktop ? (
              <aside
                aria-label="Supporting context"
                className={cn(
                  'shrink overflow-y-auto border-l border-border bg-surface pb-6 pt-4',
                  // AC4 — an EMPTY panel collapses to a slim strip so it never
                  // steals width from the artifact; a populated panel is the
                  // fixed-width (but shrinkable) supporting column.
                  isContextPanelOccupied ? 'w-80 min-w-0 px-4' : 'w-12 px-0',
                )}
              >
                <ContextPanelRegion
                  mountRef={setContextPanelMount}
                  occupied={isContextPanelOccupied}
                  collapsed
                />
              </aside>
            ) : null}
          </div>
        </div>

        {/* Tablet + mobile — the context panel as a right slide-out drawer (AC5). */}
        {isDesktop ? null : (
          <>
            {contextDrawerOpen ? (
              <button
                type="button"
                aria-label="Close supporting context"
                className="fixed inset-0 z-40 bg-black/80"
                onClick={() => setContextDrawerOpen(false)}
              />
            ) : null}
            <div
              role="dialog"
              aria-modal="false"
              aria-labelledby={CONTEXT_DRAWER_TITLE_ID}
              aria-hidden={!contextDrawerOpen}
              className={cn(
                'fixed inset-y-0 right-0 z-50 w-80 overflow-y-auto border-l bg-background p-6 shadow-lg transition-transform duration-300 sm:max-w-sm',
                contextDrawerOpen ? 'translate-x-0' : 'pointer-events-none translate-x-full',
              )}
            >
              <button
                type="button"
                aria-label="Close"
                className="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
                onClick={() => setContextDrawerOpen(false)}
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
              <div className="flex flex-col space-y-2 text-center sm:text-left">
                <h2 id={CONTEXT_DRAWER_TITLE_ID} className="text-lg font-semibold text-foreground">
                  Supporting context
                </h2>
              </div>
              <div className="mt-4">
                <ContextPanelRegion
                  mountRef={setContextPanelMount}
                  occupied={isContextPanelOccupied}
                  collapsed={false}
                />
              </div>
            </div>
          </>
        )}

        {/* Mobile — the navigation rail as a left slide-out drawer (AC5). */}
        {isMobile ? (
          <Sheet open={navDrawerOpen} onOpenChange={setNavDrawerOpen}>
            <SheetContent
              side="left"
              aria-describedby={undefined}
              className="w-72 overflow-y-auto sm:max-w-sm"
            >
              <SheetHeader>
                <SheetTitle>Workflow navigation</SheetTitle>
              </SheetHeader>
              {/* Run identity stays in the persistent top bar on mobile (it must
                  never disappear), so the drawer omits it — `showRunIdentity={false}`. */}
              <div className="mt-4">
                <NavRailContent
                  workflowRunId={workflowRunId}
                  showRunIdentity={false}
                  onNavigate={() => setNavDrawerOpen(false)}
                />
              </div>
            </SheetContent>
          </Sheet>
        ) : null}

        {/*
         * SEAM CLOSED (story 2.22 AC8.c, Trap T11) — story 2.7 reserved a
         * placement here for the global catastrophic-error overlay. Story 2.22
         * intentionally does NOT use it: a shell crash must still surface the
         * overlay, so `CatastrophicErrorOverlay` mounts in `App.tsx` OUTSIDE the
         * AppShell subtree and portals to `document.body`. See
         * `src/lib/navigation/README.md` and LAYOUT.md §4 for the rationale.
         */}
        <Toaster />
      </TooltipProvider>
    </AppShellContext.Provider>
  );
}
