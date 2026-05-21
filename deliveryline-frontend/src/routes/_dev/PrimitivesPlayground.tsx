import { useState, type ComponentType, type ReactNode } from 'react';
import {
  Ban,
  Check,
  CircleCheck,
  CircleDot,
  FilePen,
  History,
  Inbox,
  Info,
  Loader2,
  LoaderCircle,
  Lock,
  OctagonX,
  RotateCcw,
  TriangleAlert,
  type LucideProps,
} from 'lucide-react';
import { toast } from 'sonner';

import {
  STATE_NAMES,
  STATE_SIGNIFIERS,
  type StateIconName,
  type StateName,
} from '@/lib/state-signifiers';

import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';
import { Toaster } from '@/components/ui/sonner';
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { Container, Divider, Grid, Inline, Stack } from '@/components/layout';
import { densityGap } from '@/lib/density';
import { cn } from '@/lib/utils';

/*
 * Story 2.2 Task 6 (AC6) — dev-only PrimitivesPlayground.
 *
 * Living documentation + manual smoke test for the 20 shadcn/ui primitives. It is
 * mounted ONLY in dev via an `import.meta.env.DEV` + `?playground` guard in App.tsx
 * (story 2.5 converts this file into a real TanStack Router `_dev` route), so it is
 * tree-shaken out of production bundles.
 *
 * Purely presentational: NO workflow data, NO backend calls, NO TanStack Query
 * (those arrive in 2.6+). Semantic-state color tokens land in story 2.3 — here the
 * canonical states are conveyed with icon + TEXT cues, never color alone
 * (ux-design-specification.md:894).
 */

const STATE_NOTES: Record<StateName, string> = {
  informational: 'neutral context',
  success: 'completed',
  warning: 'needs attention',
  blocker: 'cannot proceed',
  draft: 'not yet live',
  selected: 'active',
  loading: 'in progress',
  error: 'failed',
  'permission-restricted': 'not allowed',
  empty: 'no items',
  stale: 'needs refresh',
  recovery: 'operator action',
};

// Story 2.3 — resolve the signifier map's lucide icon NAMES to components for the
// dev gallery (keyed by the exact `icon` strings in STATE_SIGNIFIERS).
const STATE_ICON_COMPONENTS: Record<StateIconName, ComponentType<LucideProps>> = {
  Info,
  CircleCheck,
  TriangleAlert,
  Ban,
  FilePen,
  CircleDot,
  LoaderCircle,
  OctagonX,
  Lock,
  Inbox,
  History,
  RotateCcw,
};

function stateIconComponent(name: StateName) {
  return STATE_ICON_COMPONENTS[STATE_SIGNIFIERS[name].icon];
}

// Literal Tailwind class strings per state. MUST be literal (not built via
// `bg-state-${name}`) so the Tailwind content scanner emits the utilities —
// dynamically-constructed class names are purged from the production CSS.
const STATE_CLASSES: Record<StateName, { badge: string; hc: string }> = {
  informational: {
    badge:
      'bg-state-informational text-state-informational-foreground border-state-informational-border',
    hc: 'bg-state-informational-hc text-state-informational-hc-foreground',
  },
  success: {
    badge: 'bg-state-success text-state-success-foreground border-state-success-border',
    hc: 'bg-state-success-hc text-state-success-hc-foreground',
  },
  warning: {
    badge: 'bg-state-warning text-state-warning-foreground border-state-warning-border',
    hc: 'bg-state-warning-hc text-state-warning-hc-foreground',
  },
  blocker: {
    badge: 'bg-state-blocker text-state-blocker-foreground border-state-blocker-border',
    hc: 'bg-state-blocker-hc text-state-blocker-hc-foreground',
  },
  draft: {
    badge: 'bg-state-draft text-state-draft-foreground border-state-draft-border',
    hc: 'bg-state-draft-hc text-state-draft-hc-foreground',
  },
  selected: {
    badge: 'bg-state-selected text-state-selected-foreground border-state-selected-border',
    hc: 'bg-state-selected-hc text-state-selected-hc-foreground',
  },
  loading: {
    badge: 'bg-state-loading text-state-loading-foreground border-state-loading-border',
    hc: 'bg-state-loading-hc text-state-loading-hc-foreground',
  },
  error: {
    badge: 'bg-state-error text-state-error-foreground border-state-error-border',
    hc: 'bg-state-error-hc text-state-error-hc-foreground',
  },
  'permission-restricted': {
    badge:
      'bg-state-permission-restricted text-state-permission-restricted-foreground border-state-permission-restricted-border',
    hc: 'bg-state-permission-restricted-hc text-state-permission-restricted-hc-foreground',
  },
  empty: {
    badge: 'bg-state-empty text-state-empty-foreground border-state-empty-border',
    hc: 'bg-state-empty-hc text-state-empty-hc-foreground',
  },
  stale: {
    badge: 'bg-state-stale text-state-stale-foreground border-state-stale-border',
    hc: 'bg-state-stale-hc text-state-stale-hc-foreground',
  },
  recovery: {
    badge: 'bg-state-recovery text-state-recovery-foreground border-state-recovery-border',
    hc: 'bg-state-recovery-hc text-state-recovery-hc-foreground',
  },
};

// Neutral surface ramp + brand ramp (literal classes for the same purge reason).
const NEUTRAL_SURFACES = [
  { label: 'background', className: 'bg-background' },
  { label: 'surface', className: 'bg-surface' },
  { label: 'surface-elevated', className: 'bg-surface-elevated' },
  { label: 'card', className: 'bg-card' },
  { label: 'muted', className: 'bg-muted' },
] as const;

const BRAND_RAMP = [
  { label: '50', className: 'bg-brand-50' },
  { label: '100', className: 'bg-brand-100' },
  { label: '200', className: 'bg-brand-200' },
  { label: '300', className: 'bg-brand-300' },
  { label: '400', className: 'bg-brand-400' },
  { label: '500', className: 'bg-brand-500' },
  { label: '600', className: 'bg-brand-600' },
  { label: '700', className: 'bg-brand-700' },
  { label: '800', className: 'bg-brand-800' },
  { label: '900', className: 'bg-brand-900' },
] as const;

// Story 2.4 — spacing-scale swatches. Literal `w-*` classes (purge-safe).
// 4px step → control internals / compact / dense rows; 8px step → panel /
// section / layout structure (UX-DR4 ux:792-794).
const SPACING_4PX = [
  { label: '0.5 · 2px', className: 'w-0.5' },
  { label: '1 · 4px', className: 'w-1' },
  { label: '1.5 · 6px', className: 'w-1.5' },
  { label: '2.5 · 10px', className: 'w-2.5' },
] as const;

const SPACING_8PX = [
  { label: '2 · 8px', className: 'w-2' },
  { label: '4 · 16px', className: 'w-4' },
  { label: '6 · 24px', className: 'w-6' },
  { label: '8 · 32px', className: 'w-8' },
] as const;

// Story 2.4 — typography hierarchy samples (the 5 semantic classes, AC2).
const TYPE_SAMPLES = [
  { className: 'text-page-title', label: '.text-page-title — page / panel title' },
  {
    className: 'text-section-heading',
    label: '.text-section-heading — workflow-state / section heading',
  },
  { className: 'text-body', label: '.text-body — artifact body / prose reading size' },
  { className: 'text-meta', label: '.text-meta — metadata / caption / secondary label (muted)' },
  {
    className: 'text-annotation',
    label: '.text-annotation — inline status (pair with state color + signifier)',
  },
] as const;

// AC5 — a state's color is ALWAYS paired with an icon + label (never color alone).
function StateBadge({ name, highContrast = false }: { name: StateName; highContrast?: boolean }) {
  const signifier = STATE_SIGNIFIERS[name];
  const Icon = stateIconComponent(name);
  const classes = STATE_CLASSES[name];
  const palette = highContrast ? classes.hc : `border ${classes.badge}`;
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium ${palette}`}
    >
      <Icon className="size-3.5" aria-hidden />
      {signifier.label}
    </span>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-4 rounded-lg border bg-card p-6 text-card-foreground">
      <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
      {children}
    </section>
  );
}

function Row({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center gap-3">{children}</div>;
}

export default function PrimitivesPlayground() {
  const [collapsibleOpen, setCollapsibleOpen] = useState(false);
  const [checkboxItemChecked, setCheckboxItemChecked] = useState(true);

  return (
    <TooltipProvider>
      <Toaster />
      <main className="mx-auto max-w-5xl space-y-8 bg-background p-8 text-foreground">
        <header className="space-y-1">
          <h1 className="text-2xl font-bold tracking-tight">Primitives Playground</h1>
          <p className="text-sm text-muted-foreground">
            Dev-only living documentation for the 20 shadcn/ui primitives (story 2.2). Color tokens
            arrive in story 2.3; semantic states below use icon + text cues, never color alone.
          </p>
        </header>

        <Section title="Canonical state vocabulary (icon + text, never color alone)">
          <div className="flex flex-wrap gap-2">
            {STATE_NAMES.map((name) => {
              const Icon = stateIconComponent(name);
              const iconClassName = name === 'loading' ? 'size-4 animate-spin' : 'size-4';
              return (
                <Badge key={name} variant="outline" className="gap-1.5">
                  <Icon className={iconClassName} aria-hidden />
                  <span>{STATE_SIGNIFIERS[name].label}</span>
                  <span className="text-muted-foreground">- {STATE_NOTES[name]}</span>
                </Badge>
              );
            })}
          </div>
        </Section>

        <Section title="Design tokens (story 2.3) — neutral surfaces & brand ramp">
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">Neutral surfaces</p>
            <div className="flex flex-wrap gap-3">
              {NEUTRAL_SURFACES.map((s) => (
                <div key={s.label} className="space-y-1 text-center">
                  <div className={`size-16 rounded-md border ${s.className}`} />
                  <span className="text-xs text-text-tertiary">{s.label}</span>
                </div>
              ))}
            </div>
            <p className="pt-2 text-sm text-text-tertiary">
              Text: <span className="text-text-primary">primary</span> ·{' '}
              <span className="text-text-secondary">secondary</span> ·{' '}
              <span className="text-text-tertiary">tertiary</span>
            </p>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              Brand (teal) family — primary interactive color only, not ambient decoration
            </p>
            <div className="flex flex-wrap gap-1">
              {BRAND_RAMP.map((b) => (
                <div key={b.label} className="space-y-1 text-center">
                  <div className={`size-12 rounded ${b.className}`} />
                  <span className="text-xs text-text-tertiary">{b.label}</span>
                </div>
              ))}
            </div>
          </div>
        </Section>

        <Section title="Semantic state tokens — blocker/warning dominate (AC6), never color alone (AC5)">
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              Standard badges — same size; blocker &amp; warning read as visually dominant
            </p>
            <div className="flex flex-wrap gap-2">
              {STATE_NAMES.map((name) => (
                <StateBadge key={name} name={name} />
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              High-contrast variants (low-vision / dense screens)
            </p>
            <div className="flex flex-wrap gap-2">
              {STATE_NAMES.map((name) => (
                <StateBadge key={name} name={name} highContrast />
              ))}
            </div>
          </div>
        </Section>

        <Section title="Typography hierarchy (story 2.4) — semantic classes (AC2)">
          <div className="space-y-3">
            {TYPE_SAMPLES.map((t) => (
              <div key={t.className}>
                <p className={t.className}>The quick brown fox jumps over the lazy dog</p>
                <p className="text-xs text-text-tertiary">{t.label}</p>
              </div>
            ))}
          </div>
        </Section>

        <Section title="Spacing — 4px / 8px hybrid scale (story 2.4, AC3/AC7)">
          <div className="grid gap-6 sm:grid-cols-2">
            <div className="space-y-2">
              <p className="text-sm font-medium text-text-secondary">
                4px step — control internals, compact metadata, dense rows
              </p>
              {SPACING_4PX.map((s) => (
                <div key={s.label} className="flex items-center gap-2">
                  <div className={cn('h-4 rounded bg-brand-500', s.className)} />
                  <span className="text-xs text-text-tertiary">{s.label}</span>
                </div>
              ))}
            </div>
            <div className="space-y-2">
              <p className="text-sm font-medium text-text-secondary">
                8px step — panel spacing, section separation, layout structure
              </p>
              {SPACING_8PX.map((s) => (
                <div key={s.label} className="flex items-center gap-2">
                  <div className={cn('h-4 rounded bg-brand-600', s.className)} />
                  <span className="text-xs text-text-tertiary">{s.label}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              Density convention — composites map <code>compact</code> →{' '}
              <code>{densityGap('compact')}</code> (4px) and <code>standard</code> →{' '}
              <code>{densityGap('standard')}</code> (8px)
            </p>
            <div className={cn('flex flex-wrap', densityGap('compact'))}>
              <span className="rounded bg-muted px-2 py-1 text-xs">compact</span>
              <span className="rounded bg-muted px-2 py-1 text-xs">row</span>
              <span className="rounded bg-muted px-2 py-1 text-xs">items</span>
            </div>
            <div className={cn('flex flex-wrap', densityGap('standard'))}>
              <span className="rounded bg-muted px-2 py-1 text-xs">standard</span>
              <span className="rounded bg-muted px-2 py-1 text-xs">row</span>
              <span className="rounded bg-muted px-2 py-1 text-xs">items</span>
            </div>
          </div>
        </Section>

        <Section title="Layout primitives (story 2.4, AC5) — Stack / Inline / Grid / Container / Divider">
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">Stack (gap=&quot;2&quot;)</p>
            <Stack gap="2">
              <div className="rounded bg-muted p-2 text-sm">Stack child A</div>
              <div className="rounded bg-muted p-2 text-sm">Stack child B</div>
            </Stack>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              Inline (gap=&quot;2&quot;, with a vertical Divider)
            </p>
            <Inline gap="2">
              <span className="rounded bg-muted px-2 py-1 text-sm">A</span>
              <Divider orientation="vertical" className="h-5" />
              <span className="rounded bg-muted px-2 py-1 text-sm">B</span>
              <Divider orientation="vertical" className="h-5" />
              <span className="rounded bg-muted px-2 py-1 text-sm">C</span>
            </Inline>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              Grid (cols=3, gap=&quot;2&quot;)
            </p>
            <Grid cols={3} gap="2">
              {Array.from({ length: 6 }, (_, i) => (
                <div key={i} className="rounded bg-muted p-2 text-center text-sm">
                  {i + 1}
                </div>
              ))}
            </Grid>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">
              Container (centered max-width)
            </p>
            <Container className="rounded border bg-muted py-3 text-center text-sm">
              Centered, padded container
            </Container>
          </div>
          <div className="space-y-2">
            <p className="text-sm font-medium text-text-secondary">Divider (horizontal)</p>
            <p className="text-sm text-muted-foreground">Above</p>
            <Divider />
            <p className="text-sm text-muted-foreground">Below</p>
          </div>
        </Section>

        <Section title="Prose reading utility (story 2.4, AC4) — 45–75ch, line-height ≥ 1.5">
          <div className="prose">
            <p>
              The Artifact Review Panel (story 2.17) renders long-form specification content with
              the <code>.prose</code> utility. Line length is bounded to a comfortable measure
              (70ch, within the 45–75 character readable band) so the eye does not lose its place
              when tracking from the end of one line to the start of the next.
            </p>
            <p>
              Paragraph spacing follows the 8px rhythm and the line-height is relaxed (≥ 1.5),
              supporting sustained technical reading without dramatic stylistic contrast. The
              utility is hand-rolled in <code>globals.css</code> — no{' '}
              <code>@tailwindcss/typography</code> dependency.
            </p>
          </div>
        </Section>

        <Section title="Focus ring (story 2.4, AC6) — Tab to the control to see --ring-focus">
          <Row>
            <button
              type="button"
              className="rounded-md border bg-surface px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring-focus focus-visible:ring-offset-2"
            >
              Tab to me — focus ring uses --ring-focus
            </button>
            <span className="text-sm text-muted-foreground">
              Layout / composite / app code uses <code>ring-ring-focus</code>; shadcn primitives
              keep their own <code>ring-ring</code>.
            </span>
          </Row>
        </Section>

        <Section title="Button — variants & states">
          <Row>
            <Button>Primary (governed)</Button>
            <Button variant="secondary">Secondary (review)</Button>
            <Button variant="ghost">Tertiary / ghost</Button>
            <Button variant="link">Tertiary / link</Button>
            <Button variant="destructive">Destructive</Button>
            <Button variant="outline">Outline</Button>
          </Row>
          <Row>
            <Button size="sm">Small</Button>
            <Button size="lg">Large</Button>
            <Button size="icon" aria-label="Confirm">
              <Check aria-hidden />
            </Button>
            <Button disabled>
              <Loader2 className="animate-spin" aria-hidden />
              Submitting…
            </Button>
          </Row>
          <Row>
            <Button disabled aria-describedby="btn-disabled-reason">
              Approve
            </Button>
            {/* Disabled controls show an adjacent reason (ux:1978) — never a bare dead button. */}
            <span id="btn-disabled-reason" className="text-sm text-muted-foreground">
              Disabled: waiting on upstream review.
            </span>
          </Row>
        </Section>

        <Section title="Form primitives — input / textarea / select / label">
          <div className="grid gap-6 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="pg-name">Name (default)</Label>
              <Input id="pg-name" placeholder="Ada Lovelace" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="pg-disabled">Name (disabled)</Label>
              <Input id="pg-disabled" placeholder="Unavailable" disabled />
            </div>
            <div className="space-y-2">
              <Label htmlFor="pg-invalid">Email (error / invalid)</Label>
              <Input
                id="pg-invalid"
                type="email"
                defaultValue="not-an-email"
                aria-invalid
                aria-describedby="pg-invalid-msg"
                className="border-destructive focus-visible:ring-destructive"
              />
              <p id="pg-invalid-msg" className="text-sm text-destructive">
                Enter a valid email address.
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="pg-notes">Notes (textarea)</Label>
              <Textarea id="pg-notes" placeholder="Describe the change…" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="pg-select">Stage (select)</Label>
              <Select>
                <SelectTrigger id="pg-select">
                  <SelectValue placeholder="Choose a stage" />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectLabel>Stages</SelectLabel>
                    <SelectItem value="spec">Specification</SelectItem>
                    <SelectItem value="impl">Implementation</SelectItem>
                    <SelectItem value="review">Review</SelectItem>
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
          </div>
        </Section>

        <Section title="Badge & Alert — semantic states with non-color cue">
          <Row>
            <Badge>
              <Check className="size-3.5" aria-hidden /> Default
            </Badge>
            <Badge variant="secondary">
              <Info className="size-3.5" aria-hidden /> Secondary
            </Badge>
            <Badge variant="destructive">
              <Ban className="size-3.5" aria-hidden /> Destructive
            </Badge>
            <Badge variant="outline">
              <TriangleAlert className="size-3.5" aria-hidden /> Outline
            </Badge>
          </Row>
          <Alert>
            <Info aria-hidden />
            <AlertTitle>Informational</AlertTitle>
            <AlertDescription>
              Stock shadcn alert. The icon + title carry meaning so the state reads without color.
            </AlertDescription>
          </Alert>
          <Alert variant="destructive">
            <TriangleAlert aria-hidden />
            <AlertTitle>Error / blocker</AlertTitle>
            <AlertDescription>Something failed and the run cannot proceed.</AlertDescription>
          </Alert>
        </Section>

        <Section title="Table / Card / ScrollArea — populated, empty, loading">
          <Card>
            <CardHeader>
              <CardTitle>Populated table</CardTitle>
              <CardDescription>Static example rows — no data fetching here.</CardDescription>
            </CardHeader>
            <CardContent>
              <Table>
                <TableCaption>Example runs.</TableCaption>
                <TableHeader>
                  <TableRow>
                    <TableHead>Run</TableHead>
                    <TableHead>Stage</TableHead>
                    <TableHead>Status</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow>
                    <TableCell>run-001</TableCell>
                    <TableCell>Specification</TableCell>
                    <TableCell>
                      <Badge variant="outline">
                        <Check className="size-3.5" aria-hidden /> Approved
                      </Badge>
                    </TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell>run-002</TableCell>
                    <TableCell>Implementation</TableCell>
                    <TableCell>
                      <Badge variant="outline">
                        <Loader2 className="size-3.5 animate-spin" aria-hidden /> Running
                      </Badge>
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </CardContent>
            <CardFooter className="text-sm text-muted-foreground">2 runs.</CardFooter>
          </Card>

          <div className="grid gap-4 sm:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>Empty state</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col items-center gap-2 py-8 text-muted-foreground">
                <Inbox aria-hidden />
                <p className="text-sm">No runs yet.</p>
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle>Loading state</CardTitle>
              </CardHeader>
              <CardContent className="flex items-center gap-2 py-8 text-muted-foreground">
                <Loader2 className="animate-spin" aria-hidden />
                <p className="text-sm">Loading runs…</p>
              </CardContent>
            </Card>
          </div>

          <div className="space-y-2">
            <p className="text-sm font-medium">ScrollArea</p>
            <ScrollArea className="h-32 w-full rounded-md border p-3">
              <ul className="space-y-1 text-sm">
                {Array.from({ length: 20 }, (_, i) => (
                  <li key={i}>Scrollable row {i + 1}</li>
                ))}
              </ul>
            </ScrollArea>
          </div>
        </Section>

        <Section title="Sonner — lightweight confirmation toast">
          <Row>
            <Button
              variant="outline"
              onClick={() =>
                toast.success('Saved', { description: 'Toasts are lightweight confirmation only.' })
              }
            >
              Fire a toast
            </Button>
          </Row>
        </Section>

        <Section title="Overlays & disclosure — focus moves in and returns on close">
          <Row>
            <Dialog>
              <DialogTrigger asChild>
                <Button variant="outline">Open dialog</Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Dialog title</DialogTitle>
                  <DialogDescription>
                    Focus moves into the dialog on open and returns to the trigger on close.
                  </DialogDescription>
                </DialogHeader>
                <DialogFooter>
                  <DialogClose asChild>
                    <Button variant="secondary">Close</Button>
                  </DialogClose>
                </DialogFooter>
              </DialogContent>
            </Dialog>

            <Sheet>
              <SheetTrigger asChild>
                <Button variant="outline">Open sheet</Button>
              </SheetTrigger>
              <SheetContent>
                <SheetHeader>
                  <SheetTitle>Sheet title</SheetTitle>
                  <SheetDescription>
                    Slide-over panel built on the same overlay primitive.
                  </SheetDescription>
                </SheetHeader>
                <SheetFooter>
                  <SheetClose asChild>
                    <Button variant="secondary">Close</Button>
                  </SheetClose>
                </SheetFooter>
              </SheetContent>
            </Sheet>

            <Popover>
              <PopoverTrigger asChild>
                <Button variant="outline">Open popover</Button>
              </PopoverTrigger>
              <PopoverContent>Popover content with interactive focus handling.</PopoverContent>
            </Popover>

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline">Open dropdown</Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent>
                <DropdownMenuLabel>Actions</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem>Inspect</DropdownMenuItem>
                <DropdownMenuItem>Compare</DropdownMenuItem>
                <DropdownMenuCheckboxItem
                  checked={checkboxItemChecked}
                  onCheckedChange={(value) => setCheckboxItemChecked(value === true)}
                >
                  Show details
                </DropdownMenuCheckboxItem>
              </DropdownMenuContent>
            </DropdownMenu>

            <Tooltip>
              <TooltipTrigger asChild>
                <Button variant="outline">Hover for tooltip</Button>
              </TooltipTrigger>
              <TooltipContent>Tooltip content</TooltipContent>
            </Tooltip>
          </Row>

          <Tabs defaultValue="overview" className="w-full">
            <TabsList>
              <TabsTrigger value="overview">Overview</TabsTrigger>
              <TabsTrigger value="details">Details</TabsTrigger>
            </TabsList>
            <TabsContent value="overview" className="text-sm text-muted-foreground">
              Overview tab content.
            </TabsContent>
            <TabsContent value="details" className="text-sm text-muted-foreground">
              Details tab content.
            </TabsContent>
          </Tabs>

          <Accordion type="single" collapsible className="w-full">
            <AccordionItem value="item-1">
              <AccordionTrigger>What is a primitive?</AccordionTrigger>
              <AccordionContent>A generic, reusable shadcn/ui building block.</AccordionContent>
            </AccordionItem>
            <AccordionItem value="item-2">
              <AccordionTrigger>Where do tokens come from?</AccordionTrigger>
              <AccordionContent>
                Stories 2.3 (color) and 2.4 (typography / spacing).
              </AccordionContent>
            </AccordionItem>
          </Accordion>

          <Collapsible open={collapsibleOpen} onOpenChange={setCollapsibleOpen}>
            <CollapsibleTrigger asChild>
              <Button variant="ghost">
                {collapsibleOpen ? 'Hide' : 'Show'} collapsible content
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent className="pt-2 text-sm text-muted-foreground">
              Collapsible region content.
            </CollapsibleContent>
          </Collapsible>

          <div className="space-y-2">
            <p className="text-sm font-medium">Separator</p>
            <p className="text-sm text-muted-foreground">Above</p>
            <Separator />
            <p className="text-sm text-muted-foreground">Below</p>
          </div>
        </Section>
      </main>
    </TooltipProvider>
  );
}
