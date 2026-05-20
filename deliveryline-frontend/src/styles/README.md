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
