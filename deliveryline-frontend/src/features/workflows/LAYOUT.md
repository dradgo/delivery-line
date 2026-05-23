# `AppShell` — Tri-Pane Layout ADR

**Story:** 2.7 — Tri-Pane Application Shell with Artifact-Primacy Layout Rules
**Status:** Active. This ADR is referenced by future Epic-2 / Epic-4 story ACs to prevent layout drift.
**Scope:** the structural shell only — the three regions, their width contract, the right-panel slot API, and the responsive split with story 2.26. Composites that plug into the shell ship in later stories (2.16, 2.17, 2.18, 2.19, 2.20, 2.22, 4.x).

---

## 1. The artifact-primacy collapse rule (AC10 — load-bearing)

> **When layout pressure increases, context panels collapse before the main pane; compare mode (Epic 4) is the only state where the main pane may share primacy with a second artifact view.**

This is a **hard layout invariant**, not a styling preference. It comes directly from the UX spec's "Artifact Primacy Rule" (UX spec lines 1050-1057) and is restated here so every future story that touches the shell — composites, responsive matrix, Compare Mode — can be checked against it.

**Two operational consequences:**

1. The right `<aside>` is the element that yields width (or disappears into a drawer) when the viewport shrinks. The `<main>` pane never narrows below its documented `min-width` while the right panel still has width to give.
2. No future composite — Run Context Strip (2.16), Clarification Region (2.18), blocker list, run history, artifact metadata — may compete with the artifact for primacy in normal review. Compare Mode (Epic 4) is the **single** exception, and even there the second pane is itself an artifact view, not a context panel.

The structural contract is asserted in `AppShell.test.tsx` ("artifact primacy" describe block). The true computed-pixel test (visible columns at given viewport widths) defers to Playwright in stories 2.26 / 2.27 — see TRAP 4 in the story spec.

---

## 2. Three regions and the width contract

The shell is a single flex row at `h-dvh` with `overflow-hidden`. Each region scrolls independently (AC9), so long artifact reading in `<main>` never scrolls the nav rail or context panel.

| Region               | Landmark                                  | Width on desktop                                                                                                           | Behavior under pressure                                                                                                                                                     |
| -------------------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Left navigation rail | `<nav aria-label="Workflow navigation">`  | Desktop: `w-64` (16rem). Tablet: `w-56` (14rem) — **fixed**                                                                | Tablet: stays inline but compacts so the main pane keeps its floor. Mobile: the top bar remains the persistent navigation landmark and the full rail moves behind a drawer. |
| Central main pane    | `<main id="main-content">`                | Desktop: `flex-1 min-w-[36rem]`. Tablet: `flex-1 min-w-[34rem]` — **shrinks last**                                         | The floor is responsive: `36rem` on desktop, `34rem` on tablet, `min-w-0` on mobile. Width never drops below the active floor while a context panel can still yield first.  |
| Right context panel  | `<aside aria-label="Supporting context">` | Desktop: `w-80` (20rem) when occupied, `w-12` (3rem) when empty. Tablet/mobile: landmark persists as a slim toggle region. | Tablet/mobile content lives in a right-side `Sheet` drawer, but the supporting-context landmark stays present in the shell frame.                                           |

### Why these tokens

- The UX spec deliberately states **no pixel widths** for the panes (story spec line 175). The numbers above are chosen to honor: a nav rail wide enough for legible queue/status labels (~16rem desktop / ~14rem tablet); a context panel wide enough to host the Run Context Strip without competing with the artifact (~20rem desktop); and a main pane minimum generous enough for `prose` line length on the artifact (~36rem desktop / ~34rem tablet).
- They are **Tailwind utility classes**, not CSS variables, because story 2.7 adds **no** new design tokens — it composes the tokens already shipped in stories 2.3/2.4. Story 2.26 may promote these into named tokens when it ships the full breakpoint matrix.
- The empty right-panel `w-12` is not "hiding" the panel — it preserves the landmark for AC7 (every shell route exposes `<nav>` / `<main>` / `<aside>`) and the structural-stability promise of AC8, while honoring artifact primacy ("an empty context panel must not steal width from the artifact").

---

## 3. Right-panel composition slot (AC4 — the API for 2.16 / 2.18)

The right `<aside>` is a **slot**, not a hard-coded panel. Story 2.7 does not build Run Context Strip, Clarification Region, blocker list, artifact metadata, or run history — every one of those is a later story that _plugs into_ this slot (TRAP 2). The shell owns the frame; the route's component tree owns the content.

### How to plug in

```tsx
import { ContextPanelSlot } from '@/features/workflows/ContextPanelSlot';

function RunReviewQueueRoute() {
  return (
    <>
      <h1>Run review queue</h1>

      <ContextPanelSlot>
        <RunContextStrip /> {/* story 2.16 */}
        <ClarificationSidebar /> {/* story 2.18 */}
      </ContextPanelSlot>
    </>
  );
}
```

### Contract

- **Single source of mount:** the shell renders exactly one mount point for the right panel. `ContextPanelSlot` uses `createPortal` (react-dom) to project its children into that mount, regardless of where in the route tree it is rendered.
- **Occupancy is reference-counted:** rendering at least one `<ContextPanelSlot>` flips the aside from `w-12` (empty) to `w-80` (occupied) and removes the "No supporting context for this view." placeholder. Multiple slots are additive — the aside stays occupied until the last slot unmounts.
- **No layout responsibility:** the slot does not impose padding, scroll, or spacing on its children. Composites style their own internals against the documented `w-80` budget.
- **Why not a TanStack layout route?** The repo's file-based router uses a custom route generator (`tools/routing/generate-route-tree.js`); a nested layout route would touch that generator and was explicitly out of scope (TRAP — see `__root.tsx` notes). A React context + portal is router-shape-agnostic and works identically for the queue route, every run-detail route, and the dead-end routes.

### What composites **must not** do

- Re-render their own `<aside>` landmark (would create a duplicate complementary region — fails AC7).
- Add wrapping `<Container>` or `<main>` (the shell owns these — TRAP 1).
- Reach for `document.querySelector('aside')` or other DOM escape hatches — use `ContextPanelSlot`.

---

## 4. Hybrid-coherence invariant (AC8) — one frame, many states

The shell is **structurally stable** across every Epic-2 view it hosts and every Epic-4 mode it grows into. Compare Mode, clarification state, and normal review are **not "mode switches"** that swap layouts — they are different content inside the same three landmarks.

- The same `<nav>` / `<main>` / `<aside>` exist on the queue route, every `$workflowRunId` route, every artifact route, and the dead-end / 404 / error routes (per Q4 — dead-end routes render _inside_ the shell).
- The catastrophic-error overlay (story 2.22 AC8) **lives in this shell**. A documented seam is reserved in `AppShell.tsx` (search for `// SEAM:`). Do not build the overlay here.
- Compare Mode (Epic 4) does **not** ship a different shell. It ships content that occupies `<main>` and may split it into two artifact views — at which point the artifact-primacy exception in §1 applies.

This is tested by `AppShell.test.tsx` ("structural stability" describe block): mounting the shell at `/workflows` and at `/workflows/:id` both yield the three landmarks.

---

## 5. Responsive split with story 2.26

Story 2.7 owns the **structure** of responsive collapse. Story 2.26 owns the **full breakpoint matrix and the public design contract**. This split mirrors how story 2.6 stood up the minimal Vitest runner and story 2.27 extends it.

| Concern                                                                                                                                  | Story                | Notes                                                                                                                                                                                        |
| ---------------------------------------------------------------------------------------------------------------------------------------- | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `useResponsiveLayout()` hook — minimal, returns `'mobile' \| 'tablet' \| 'desktop'`, subscribes to `matchMedia` change events            | **2.7 (this story)** | Breakpoints: mobile `<768px`, tablet `768-1023px`, desktop `≥1024px`. SSR-safe fallback to `desktop`. Lives at `src/features/workflows/hooks/useResponsiveLayout.ts`.                        |
| Tablet drawer + mobile drawer wiring for nav and aside                                                                                   | **2.7**              | Via Radix `Sheet`. The `<main>` `min-w-[36rem]` floor holds at every mode (artifact primacy).                                                                                                |
| Full breakpoint matrix, additional intermediate breakpoints, Tailwind `screens` aliases, `RESPONSIVE.md` ADR, mobile sticky decision-bar | **2.26**             | Story 2.26 hardens the hook and authors the cross-component responsive contract. Story 2.7 deliberately does **not** edit `tailwind.config.ts` `screens` or author `RESPONSIVE.md` (TRAP 3). |
| Playwright computed-pixel layout tests (real layout engine)                                                                              | **2.26 AC12 / 2.27** | jsdom has no layout engine — story 2.7 asserts the structural contract only (TRAP 4).                                                                                                        |

**Implication for the 2.26 dev:** the hook already exists. Extend it; do not re-create it. The 2.7 test suite (`useResponsiveLayout.test.tsx`) is intentionally minimal and is expected to grow when 2.26 lands.

---

## 6. File-location decision (Task 1)

The shell lives at `src/features/workflows/AppShell.tsx` (and this ADR at `src/features/workflows/LAYOUT.md`).

This **deviates** from `_bmad-output/planning-artifacts/architecture.md:1183`, which says "Frontend app shell/navigation components live under `components/layout`."

**Why we chose `features/workflows/`:**

1. The epic ACs explicitly place the shell here. Epic 2 AC1 says `src/features/workflows/AppShell.tsx (or equivalent)`; AC10 says `src/features/workflows/LAYOUT.md`; story 2.26 AC10 says `src/features/workflows/RESPONSIVE.md`. Three independent epic ACs converge on the same path — this is intentional, not accidental.
2. The shell is **workflow-coupled**, not generic chrome. It reads `useWorkflowDetail($workflowRunId)` for the run-identity region (AC3, AC11), it hosts the workflow-domain composition slot (AC4), and its responsive collapse rules are derived from the artifact-primacy invariant — a workflow-domain concept. A generic `components/layout/` location would invite reuse by non-workflow surfaces, which is exactly the wrong direction.
3. `src/components/ui/**` is guarded by the custom `no-workflow-domain-in-ui-primitives` ESLint rule (story 2.31). Moving the shell into `components/layout/` adjacent to UI primitives would be at odds with the spirit of that rule even though it would not literally trip it.
4. The shell composes the **layout primitives** that _do_ live in `components/layout/` (`Stack`, `Inline`, `Container`, `Divider`) — that is the boundary the architecture line was actually drawing. Primitives in `components/layout/`; workflow-specific composition of those primitives in `features/workflows/`.

The deviation is intentional and documented here. Future stories that read architecture.md:1183 should treat this ADR as the authoritative location for the workflow shell.

---

## 7. References

- **UX spec (load-bearing directives):**
  - Artifact Primacy Rule — lines 1050-1057 (AC2, AC10).
  - Structural Collapse Rules — lines 2232-2245 ("run identity and current state should never disappear during collapse" — see the persistent mobile top bar).
  - Accessibility Strategy — lines 2109-2129 (landmarks, focus, keyboard movement).
  - Hybrid Coherence Rules — §Hybrid Coherence Rules (AC8).
- **Architecture:** `_bmad-output/planning-artifacts/architecture.md` — folder conventions (686-694), server-state rules (761-766, 800-805, 825-836), app-shell location guidance (1183 — see §6 above).
- **CLI parity:** `WorkflowCommandOutputs#renderStatusJson` (story 1.15) — the fields shown in the run-identity region match the CLI's `status --format=json` output verbatim (AC11). Last-transition timestamp is `lastEventAt` (the CLI's `lastEvent.createdAt`), **not** `lastActivityTimestamp`.
- **Predecessor:** story 2.6 — `useWorkflowDetail` + `WorkflowDetail` typed from `src/lib/api/schema.d.ts`, query keys via `workflowKeys.*` only, minimal Vitest+RTL+MSW runner.
