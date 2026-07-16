# `AppShell` — Responsive Design & Structural Collapse ADR

**Story:** 2.26 — Responsive Design (Mobile/Tablet/Desktop Breakpoints + Structural Collapse Rules)
**Status:** Active. Referenced by future Epic-2 / Epic-4 story ACs to prevent responsive-collapse drift.
**Scope:** the public responsive contract — the breakpoint matrix, the collapse ORDER under viewport pressure, which surfaces become drawers/sheets, the non-collapsible set, the Compare-Mode mobile reservation (Epic 4), and the browser-support policy. This is the responsive sibling of [`LAYOUT.md`](./LAYOUT.md), which owns the structural three-region shell. Read both together.

This story **hardens already-built infrastructure** (the story-2.7 `AppShell`, `useResponsiveLayout()`, the drawers, and the persistent `MobileTopBar`). It does not rebuild any of it. This ADR is the formal, test-pinned statement of the contract those pieces already implement.

---

## 1. The breakpoint matrix (AC1 — UX-DR22)

| Name        | Range      | Tailwind boundary         | `useResponsiveLayout()` mode | Shell layout                                                                          |
| ----------- | ---------- | ------------------------- | ---------------------------- | ------------------------------------------------------------------------------------- |
| **mobile**  | 320–767px  | unprefixed base (`< md`)  | `'mobile'`                   | single artifact-first column + persistent `MobileTopBar`; both side panels in drawers |
| **tablet**  | 768–1023px | `md` (768px) ≡ `tablet`   | `'tablet'`                   | left nav rail + main pane + slim `TabletContextRail` toggling a right drawer          |
| **desktop** | 1024px+    | `lg` (1024px) ≡ `desktop` | `'desktop'`                  | full inline tri-pane (`<nav>` + `<main>` + `<aside>`)                                 |

**Single source of the boundaries.** `768` / `1024` live in exactly two aligned places and nowhere else:

- `useResponsiveLayout.ts` — `TABLET_MIN_PX = 768`, `DESKTOP_MIN_PX = 1024` (the JS/`matchMedia` path).
- `tailwind.config.ts` — the default `md` (768px) / `lg` (1024px) screens, with **additive** `tablet` / `desktop` aliases pointing at the same px (the CSS-utility path).

These are deliberately the same numbers, so a Tailwind `md:`/`tablet:` utility and a `useResponsiveLayout() === 'tablet'` branch always agree. **The boundaries are fixed (D3).** Moving `md`/`lg` would silently re-flow every existing `sm:`/`md:`/`lg:` utility across the app — a regression, not an enhancement. `mobile` is intentionally **not** a screen alias: Tailwind is mobile-first, so every unprefixed utility already targets the mobile base.

**One hook, no ad-hoc queries (AC8).** Breakpoint-conditional rendering routes through `useResponsiveLayout()` or Tailwind responsive utilities. No composite reaches for its own `window.matchMedia` or hand-rolled CSS media query — the centralization is what keeps the matrix authoritative.

---

## 2. Structural collapse rules (AC10 — UX-DR23)

The governing priority order on narrow screens (UX spec "Structural Collapse Rules"):

> **preserve artifact reading > preserve decision controls > disclose navigation / supporting context.**

### Collapse order under increasing viewport pressure

1. **Right supporting-context panel yields FIRST.** On desktop it is an inline `<aside>` (`w-80` occupied, `w-12` empty); at the **tablet** boundary it collapses to a slim `TabletContextRail` whose toggle opens the context as a right slide-out **drawer**. This happens _before_ the main pane gives up any width — the artifact-primacy floor (TRAP 4, [`LAYOUT.md` §1](./LAYOUT.md)): `<main>` never narrows below its `min-width` (`36rem` desktop / `34rem` tablet) while the context panel still has width to give.
2. **Left navigation rail collapses NEXT.** Inline on desktop (`w-64`) and tablet (`w-56`); at the **mobile** boundary it moves behind a hamburger into a left `Sheet` drawer, while the persistent `MobileTopBar` remains as the always-present navigation landmark.
3. **The main pane is the LAST to change shape.** On mobile it drops its min-width floor (`min-w-0`) and becomes the single full-width column. The artifact is never the thing that yields.

### Surfaces that become drawers / sheets

| Surface                  | Desktop          | Tablet                                    | Mobile                                     |
| ------------------------ | ---------------- | ----------------------------------------- | ------------------------------------------ |
| Supporting context panel | inline `<aside>` | slim rail toggle → right slide-out drawer | right slide-out drawer (toggle in top bar) |
| Navigation rail          | inline `<nav>`   | inline (compacted) `<nav>`                | hamburger → left `Sheet` drawer            |
| Run identity + state     | in nav rail      | in nav rail                               | **persistent** in `MobileTopBar`           |
| Approval decision bar    | `sticky_footer`  | `sticky_footer`                           | `sticky_footer`                            |

### The non-collapsible set (UX-DR23 — "run identity and current state should never disappear during collapse")

These elements MUST remain visible at every breakpoint and never move into a drawer:

- **Run identity + current-state badge** — on mobile they render in the persistent `MobileTopBar` (`RunIdentityRegion variant="compact"`), which is always on screen inside a run. The state badge keeps its icon **+** text signifier at every breakpoint (never color-alone — story 2.3 AC5).
- **The primary governed decision action** (`Approve` / `Reject with feedback`) — anchored in the `sticky_footer` decision bar (`ApprovalDecisionBar.tsx`, `sticky bottom-0`), always reachable without hunting. Secondary actions MAY collapse into an overflow menu; **the primary affirmative action never does** (AC7 — there is exactly one `data-primary` control and it is never hidden by responsive collapse).

---

## 3. Compare Mode — the mobile reservation (AC4, Epic 4)

UX-DR23: _"compare becomes a dedicated bounded mobile state rather than compressed side-by-side."_

On desktop/tablet, Compare Mode (Epic 4) may split `<main>` into a side-by-side before/after view — the **single** sanctioned exception to artifact primacy ([`LAYOUT.md` §1](./LAYOUT.md)). On **mobile**, side-by-side is unreadable, so Compare Mode becomes a **dedicated full-screen state** with an explicit **before / after toggle** (one artifact view at a time, switched by a control) rather than two compressed columns.

**Implemented in story 4.21 (UX-DR23).** This pattern is now built: at `useResponsiveLayout() === 'mobile'` (<768px) the `CompareMode` composite renders a `fixed inset-0` full-screen takeover (covering the nav rail + context panel) with a persistent top bar (revision A/B labels + before/after toggle + Previous/Next-change buttons replacing the desktop J/K + exit X) over a single-column body — **never** a shrunk side-by-side (the `compare-synced-scroll` 2-col surface is absent at mobile). PR-output compares keep their single-column file accordions with the toggle hidden (the per-file diff is inherently before/after). The container (`CompareModeContainer`) reads the canonical hook and threads the resolved `viewport` down as a prop, so the presentational `CompareMode` stays matchMedia-free (mirrors `ReconciliationDialog`). Tablet/desktop keep the story-4.20 body unchanged. The contract text above stays authoritative; the mobile real-device journey is in [`responsive-real-device-checklist.md`](../../../../docs/testing/responsive-real-device-checklist.md).

_History:_ through Epic 2 this was a reservation, not an implementation — story 2.17's Compare-Mode entry control shipped **disabled** in E2 (`ArtifactReviewPanel.tsx` — `compareEnabled` defaults `false`), and this section documented the mobile UX pattern so the responsive contract was settled before the Epic-4 code landed.

---

## 4. Browser & device support policy (AC11 — UX-DR24)

| Tier                       | Support                                                                                     |
| -------------------------- | ------------------------------------------------------------------------------------------- |
| **Supported**              | Modern **Chrome, Firefox, Safari, Edge** — **current + n-1** major versions (evergreen).    |
| **Smallest mobile target** | **Galaxy S23+ class** (see `docs/supported-environments.md` and the real-device checklist). |
| **Excluded**               | Internet Explorer (all), and any legacy / non-evergreen engine.                             |

**Enforcement is split (D1).** This document and the manual **real-device checklist** (`docs/testing/responsive-real-device-checklist.md`, AC9) are the contract today. The **executable cross-browser / mobile-viewport CI enforcement (Playwright)** is owned by **story 2.27** — its AC8/AC9 explicitly reference _"per story 2.26 AC11/AC9"_. Story 2.26 ships **zero Playwright config**; it formalizes the policy + defers its automated enforcement with this rationale (mirroring the 2.25 → 2.27 axe/Playwright split).

---

## 5. Test surface (AC12)

The contract above is pinned in `jsdom` via the `@/test/matchMedia` mock (`installMatchMedia` / `setViewportWidth` — a deterministic resize simulator). jsdom has **no layout engine** (D2), so the automated tests assert the **structural contract** — which landmarks/classes/elements render at each simulated breakpoint — never computed pixels. Live computed-pixel checks (touch-target ≥44px, no-horizontal-scroll, sticky-footer reachability) are the **real-device checklist** + story-2.27 Playwright.

- `AppShell.responsive.test.tsx` — per-breakpoint layout, the resize sweep pinning run identity + state badge across breakpoints, the right-panel-collapses-before-main-pane order, the mobile sticky-footer primary action, and the mobile/tablet axe scans.
- `useResponsiveLayout.test.tsx` — the returned mode at boundary widths (767/768/1023/1024) and the SSR-safe `desktop` fallback.

---

## 6. References

- **Sibling ADR:** [`LAYOUT.md`](./LAYOUT.md) — the structural three-region shell, the artifact-primacy floor (§1), the right-panel slot API (§3), the 2.7↔2.26 responsive split (§5).
- **The hook:** [`hooks/useResponsiveLayout.ts`](./hooks/useResponsiveLayout.ts) — the canonical `'mobile' | 'tablet' | 'desktop'` source.
- **The shell:** [`AppShell.tsx`](./AppShell.tsx) — desktop/tablet/mobile branches; `MobileTopBar` (persistent run identity); `TabletContextRail`; the drawers.
- **Decision bar:** [`components/ApprovalDecisionBar.tsx`](./components/ApprovalDecisionBar.tsx) — the `sticky_footer` placement (`LAYOUT_CLASS.sticky_footer`).
- **Tailwind:** `../../../tailwind.config.ts` — the `md`/`lg` defaults + additive `tablet`/`desktop` aliases (the breakpoint doc block).
- **Real-device checklist:** `../../../../docs/testing/responsive-real-device-checklist.md` (AC9) — the Galaxy S23+ critical-flow + touch-target verification, required before E2 close.
- **Supported environments:** `../../../../docs/supported-environments.md` — the browser/device support matrix.
- **UX spec:** `_bmad-output/planning-artifacts/ux-design-specification.md` "Responsive Design & Accessibility" (Breakpoint Strategy, Structural Collapse Rules, Responsive Decision Preservation Rule). The epic's UX-DR22/23/24 labels map onto these named sections — there are no literal `UX-DR` headings in the spec.
