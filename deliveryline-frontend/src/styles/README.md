# Design Tokens — Color (story 2.3)

Layer 2 (color half) of the three-layer design system. Story 2.2 shipped the stock
shadcn/ui primitives (Layer 1); this story re-skins them through **CSS custom
properties only** — no primitive is edited — and defines the semantic state
vocabulary that workflow composites (Layer 3, stories 2.15+) consume. Typography,
spacing, layout primitives, and the focus-ring token system are **story 2.4**.

All tokens live in [`globals.css`](./globals.css) as **HSL channel triplets**
(`H S% L%`, no `hsl()` wrapper) so the `hsl(var(--x))` mappings in
[`tailwind.config.ts`](../../tailwind.config.ts) work uniformly. `globals.css` is the
**single source of truth** — the automated gates parse it directly (see "Verification").

## How to use color (UX-DR2)

- **Neutral surfaces are for reading.** Backgrounds and panels stay low-saturation and
  calm so review content (specs, plans, diffs) is what draws the eye.
- **Brand (teal) is the primary interactive color — used deliberately and sparingly,
  never as ambient decoration** (ux:874). Primary buttons, active/selected affordances,
  focus ring. Don't paint surfaces or large regions teal.
- **Blocker and warning are visually dominant** over informational/draft/empty — stronger
  saturation and heavier borders — so critical states cannot be missed (UX-DR2 ux:771).
- **Draft / stale / empty are deliberately recessive** (muted, low-contrast) because they
  represent superseded or inactive content.
- **Never state by color alone.** Every state color is paired with a non-color signifier
  (icon + label) from [`src/lib/state-signifiers.ts`](../lib/state-signifiers.ts). Visual
  decision priority: **semantic clarity > scanability > visual consistency > stylistic nuance**.

## Token families

### Neutral surfaces

`--background`, `--surface` (one step off background), `--surface-elevated` (raised:
popovers/cards-on-surface), `--card`, `--text-primary`, `--text-secondary`,
`--text-tertiary`, `--border`. The stock shadcn neutral vars
(`--foreground`/`--muted`/`--secondary`/…) are **re-toned in place** to this palette;
their names are unchanged so primitives keep working.

Utilities: `bg-surface`, `bg-surface-elevated`, `text-text-primary`,
`text-text-secondary`, `text-text-tertiary` (the `text-text-*` doubling is the Tailwind
`text` color object — `bg-background`/`text-foreground`/`text-muted-foreground` remain the
everyday neutrals).

### Brand (teal) family — `--brand-50 … --brand-900`

The primary interactive color. `--primary` and `--ring` are re-toned to brand teal so
stock primitive buttons and focus rings render as the interactive color **without editing
any primitive**.

Utilities: `bg-brand-600`, `text-brand-700`, `border-brand-500`, …

> **AC1 naming deviation (intentional, accepted).** AC1 literally names this family
> `--accent-50…900`, but shadcn already owns `--accent` / `colors.accent` as a **muted
> hover surface** that primitives depend on. To avoid the collision we ship the teal
> family as **`--brand-*`** (Tailwind `brand-*`) and leave shadcn `--accent` untouched
> (only its value is re-toned neutral). The AC's intent — a teal interactive family
> exposed as utilities — is fully satisfied; only the token name changed.

### Semantic state tokens — 12 states

`informational`, `success`, `warning`, `blocker`, `draft`, `selected`, `loading`,
`error`, `permission-restricted`, `empty`, `stale`, `recovery`. The canonical list is the
`StateName` union in [`state-signifiers.ts`](../lib/state-signifiers.ts) — import it, never
re-type it.

Each state defines four parts (+ a high-contrast variant for low-vision / dense screens):

| CSS variable                   | Role                                |
| ------------------------------ | ----------------------------------- |
| `--state-{name}`               | fill (the chip/badge surface)       |
| `--state-{name}-foreground`    | text/icon color on the fill         |
| `--state-{name}-border`        | border (≥ 3:1 vs `--background`)    |
| `--state-{name}-hc`            | high-contrast fill                  |
| `--state-{name}-hc-foreground` | text/icon on the high-contrast fill |

**Canonical utility vocabulary** (DEFAULT = fill):

```tsx
// standard badge
<span className="border bg-state-blocker text-state-blocker-foreground border-state-blocker-border">
  <Ban className="size-3.5" aria-hidden /> Blocker   {/* icon + label — never color alone */}
</span>

// success / warning text + border on a neutral surface
<p className="text-state-success-foreground">Approved</p>
<div className="border-state-warning-border">…</div>

// high-contrast variant
<span className="bg-state-blocker-hc text-state-blocker-hc-foreground">Blocker</span>
```

> AC3 lists `bg-state-blocker`, `text-state-success`, `border-state-warning` as
> _illustrative_. With the DEFAULT=fill convention, `bg-state-*` is exact; for text/border
> the precise utilities are `text-state-{name}-foreground` and `border-state-{name}-border`.

Always pair the color with its signifier:

```tsx
import { STATE_SIGNIFIERS } from '@/lib/state-signifiers';
const { icon, label } = STATE_SIGNIFIERS.blocker; // -> { icon: 'Ban', label: 'Blocker' }
```

## Dark mode — wired, NOT activated (AC8)

`tailwind.config.ts` sets `darkMode: ['class']` and `globals.css` ships a `.dark { … }`
block that mirrors **every** token, so a future post-MVP story activates dark mode by
adding `class="dark"` to `<html>` and tuning values — no composite restructuring. The
`.dark` values today are **provisional placeholders** (Epic 2 ships no theme toggle) and
are intentionally not contrast-gated.

## Verification (node --test, not Vitest)

Vitest arrives in 2.27. These token gates reuse the repo's existing `node --test` tooling
pattern and run on the enforced Maven/CI path (`frontend-maven-plugin`), same as
`lint:rules-test`. Run locally with `npm run check:contrast`:

- **`token-contrast.test.js`** (AC4) — parses `globals.css`, computes WCAG contrast for
  every text-on-fill pair (≥ 4.5:1) and every state border vs background (≥ 3:1), with a
  negative self-test so the gate can't silently no-op.
- **`token-prominence.test.js`** (AC6) — asserts blocker/warning outscore
  informational/draft/empty on a documented prominence proxy (saturation + lightness delta
  from background) and carry a visible border. True pixel visual-regression is deferred to
  2.27 / 2.25; the [PrimitivesPlayground](../routes/_dev/PrimitivesPlayground.tsx) token
  gallery is the human-reviewable fixture.
- **`state-signifiers.test.js`** (AC5) — asserts 1:1 parity between the `--state-*` token
  groups and the signifier map, and that every signifier has a non-empty icon + label.

---

# Design Tokens — Typography, Spacing & Layout (story 2.4)

Layer 2's **non-color** half. Story 2.3 shipped color; this story adds the structural
tokens composites and the app shell consume: a typography hierarchy, the 4px/8px spacing
convention + density pattern, a hand-rolled `prose` reading utility, the `--ring-focus`
focus token, and the `src/components/layout/` primitives. All token values live in
[`globals.css`](./globals.css) `:root` (single source of truth, parsed by the token gate);
the semantic classes + `.prose` live in `@layer components`.

## Typography (AC1/AC2, UX-DR3)

**Tokens** (`:root`): `--font-sans` (plain-utilitarian system stack — no branded/web font),
`--text-xs … --text-2xl` (rem scale), `--leading-tight|normal|relaxed`, and
`--weight-regular|medium|semibold|bold`. `--font-sans` is also wired onto `body` and exposed
as `theme.fontFamily.sans`, so `font-sans` and the default text utilities resolve to it.

> **Why we did NOT touch Tailwind's `theme.fontSize` (Q1).** Stock shadcn primitives consume
> `text-sm`/`text-xs` directly; repointing those keys at `--text-*` would silently resize every
> primitive and break the "primitives render identically" guarantee (2.2 AC4). The `--text-*`
> vars are NEW and are consumed only through the semantic classes below.

**Semantic classes** (`@layer components`) — use these in composites/app code, not the raw
`text-*` Tailwind utilities, for hierarchy:

| Class                   | Role                                  | Tokens                                           |
| ----------------------- | ------------------------------------- | ------------------------------------------------ |
| `.text-page-title`      | page / panel title (h1)               | `--text-2xl` + `--leading-tight` + semibold      |
| `.text-section-heading` | workflow-state / section heading (h2) | `--text-lg` + `--leading-tight` + semibold       |
| `.text-body`            | artifact body / prose reading size    | `--text-base` + `--leading-relaxed` + regular    |
| `.text-meta`            | metadata / captions / secondary       | `--text-sm` + muted `--text-secondary`           |
| `.text-annotation`      | inline status / annotation (smallest) | `--text-xs` + medium; **color left to consumer** |

`.text-annotation` carries no color: composites pair it with a 2.3 `--state-*` color **and**
the signifier (icon + label) — never color alone (ux:894).

## Spacing — 4px / 8px hybrid (AC3/AC7, UX-DR4)

Tailwind v3's **default** spacing scale already encodes the hybrid rhythm (base unit
0.25rem = 4px), so we **document the convention rather than redefine `theme.spacing`**
(a wholesale override would drop the rest of the scale and break primitives' `p-*`/`gap-*`):

- **4px step** — `0.5` (2px), `1` (4px), `1.5` (6px), `2.5` (10px): control internals,
  compact metadata groups, dense review rows.
- **8px step** — `2` (8px), `4` (16px), `6` (24px), `8` (32px): panel spacing, section
  separation, larger layout structure.

**Density pattern (AC7).** Composites (Queue Item 2.15, ARP 2.17, Run Context Strip 2.16)
take a `density: 'compact' | 'standard'` prop. The shared
[`densityGap`](../lib/density.ts) helper maps it to a literal gap class — `compact → gap-1`
(4px), `standard → gap-2` (8px) — so composites don't re-derive the convention.

## `prose` reading utility (AC4)

`.prose` (in `globals.css` `@layer components`) is the long-form artifact reading surface
(consumed by ARP, story 2.17): `max-width: 70ch` (within the 45–75ch readable-line-length
band), `line-height: var(--leading-relaxed)` (≥ 1.5), 8px-step paragraph rhythm
(`> * + *`), and `--text-primary` color. It is **hand-rolled** — `@tailwindcss/typography`
is intentionally NOT installed (a new dep = a lockfile/native-binding round-trip). The token
gate asserts the `ch` max-width band + line-height so a future edit can't silently break
readability.

## Layout primitives (AC5) — `src/components/layout/`

Generic, domain-free layout helpers (the tri-pane shell 2.7 + composites 2.15+ compose
them). Each uses `forwardRef` + `cn()` + `...props` like the shadcn primitives:

| Primitive   | Element / behaviour                                      | Key props                         |
| ----------- | -------------------------------------------------------- | --------------------------------- |
| `Stack`     | `flex flex-col` with gap                                 | `gap`                             |
| `Inline`    | `flex flex-row` (default `items-center`) with gap        | `gap`, `wrap`, `align`, `justify` |
| `Grid`      | CSS grid                                                 | `cols`, `gap`                     |
| `Container` | `mx-auto` max-width + responsive horizontal padding      | (standard div attrs)              |
| `Divider`   | `role="separator"` section break painted with `--border` | `orientation`                     |

**Gap prop API (Q3).** `gap` takes a closed `GapToken` union (`'0' | '0.5' | … | '8'`) mapped
through a static `GAP_CLASS` `Record` to **literal** `gap-*` strings. Tailwind's content-purge
cannot see dynamically-constructed class names (`` `gap-${n}` `` is purged), so the static
lookup is both purge-safe and type-safe, and the union enforces the 4px/8px scale.

## Focus ring (AC6, WCAG 2.4.7)

`--ring-focus` (`:root`, HSL triplet) is the **project focus-ring token** for layout
primitives, composites, and app-authored interactive elements — applied as
`focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2`
(exposed as the Tailwind `ring-focus` color), never the default browser outline.

> `--ring-focus` vs `--ring`: shadcn primitives keep their own `focus-visible:ring-ring`
> (the 2.3 teal `--ring`); this story does **not** swap their ring. `--ring-focus` is the
> canonical token for **everything outside `src/components/ui/`**. They may share a value, but
> composite/app code references `ring-focus`.

## Verification

The token layer is gated by `npm run check:tokens`
([`tools/tokens/__tests__/typography-tokens.test.js`](../../tools/tokens/__tests__/typography-tokens.test.js)),
wired into `frontend-maven-plugin` alongside `check:contrast`. It asserts every required token
exists + is well-shaped (sizes carry units, weights are 100–900 multiples of 100,
`--leading-relaxed` ≥ 1.5, `--ring-focus` is an HSL triplet) and that `.prose` encodes the
45–75ch line length + line-height ≥ 1.5, with a negative self-test. The layout React
primitives are verified via `tsc -b` / `vite build` + the
[PrimitivesPlayground](../routes/_dev/PrimitivesPlayground.tsx) gallery; component-level unit
tests defer to 2.27. `check:contrast` stays **8/8** — the typography tokens are color-agnostic
and `--ring-focus` is not a gated pair.
