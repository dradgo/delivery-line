import type { Config } from 'tailwindcss';
import tailwindcssAnimate from 'tailwindcss-animate';

// Story 2.2 — Tailwind v3.4.x config (NOT v4 — see story Dev Notes "Tailwind v3 vs
// v4 decision": AC1 requires tailwind.config.ts + postcss.config.js, and v3 is
// pure-JS so it adds no platform-specific native binaries to the lockfile).
//
//   - darkMode: ['class']  → AC8: dark mode WIRED, not activated. No `dark` class
//                            is set on <html> and no toggle ships in Epic 2.
//   - content globs        → AC1 + AC9: drives production purge of unused utilities.
//   - theme.extend         → stock shadcn/ui CSS-variable theme mapping. Colors map
//                            to the HSL variables in src/styles/globals.css. Do NOT
//                            hand-pick brand/teal colors or a custom spacing scale
//                            here — those are design tokens owned by stories 2.3/2.4.
export default {
  darkMode: ['class'],
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    container: {
      center: true,
      padding: '2rem',
      screens: {
        '2xl': '1400px',
      },
    },
    extend: {
      // Story 2.4 — document font stack (AC1). Points `font-sans` (and the
      // default text utilities, since `sans` is Tailwind's default family) at
      // the `--font-sans` token in globals.css. The `theme.fontSize` keys are
      // deliberately LEFT UNTOUCHED (Q1) so stock primitives render identically.
      fontFamily: {
        sans: ['var(--font-sans)'],
      },
      colors: {
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        // Story 2.4 — focus-ring token (AC6). Canonical focus ring for layout
        // primitives / composites / app code: `focus-visible:ring-ring-focus`.
        // shadcn primitives keep `ring-ring` (2.3 teal `--ring`) untouched.
        'ring-focus': 'hsl(var(--ring-focus))',
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },

        // ---- Story 2.3 design tokens (color). The shadcn mappings above are
        //      left untouched (primitives import them); `accent` in particular
        //      stays the muted hover surface — the teal family is `brand` below
        //      (Q2: avoids the shadcn --accent collision; documented AC1
        //      deviation --accent-* -> --brand-*). ----

        // Named neutral surfaces (UX-DR2 surface vocabulary).
        surface: 'hsl(var(--surface))',
        'surface-elevated': 'hsl(var(--surface-elevated))',
        text: {
          primary: 'hsl(var(--text-primary))',
          secondary: 'hsl(var(--text-secondary))',
          tertiary: 'hsl(var(--text-tertiary))',
        },

        // Teal interactive family — `bg-brand-600`, `text-brand-700`, etc.
        brand: {
          50: 'hsl(var(--brand-50))',
          100: 'hsl(var(--brand-100))',
          200: 'hsl(var(--brand-200))',
          300: 'hsl(var(--brand-300))',
          400: 'hsl(var(--brand-400))',
          500: 'hsl(var(--brand-500))',
          600: 'hsl(var(--brand-600))',
          700: 'hsl(var(--brand-700))',
          800: 'hsl(var(--brand-800))',
          900: 'hsl(var(--brand-900))',
        },

        // 12 semantic state groups. Convention: DEFAULT = fill, so `bg-state-X`
        // paints the fill; `text-state-X-foreground`, `border-state-X-border`,
        // `bg-state-X-hc`, `text-state-X-hc-foreground` cover the rest.
        state: {
          informational: {
            DEFAULT: 'hsl(var(--state-informational))',
            foreground: 'hsl(var(--state-informational-foreground))',
            border: 'hsl(var(--state-informational-border))',
            hc: 'hsl(var(--state-informational-hc))',
            'hc-foreground': 'hsl(var(--state-informational-hc-foreground))',
          },
          success: {
            DEFAULT: 'hsl(var(--state-success))',
            foreground: 'hsl(var(--state-success-foreground))',
            border: 'hsl(var(--state-success-border))',
            hc: 'hsl(var(--state-success-hc))',
            'hc-foreground': 'hsl(var(--state-success-hc-foreground))',
          },
          warning: {
            DEFAULT: 'hsl(var(--state-warning))',
            foreground: 'hsl(var(--state-warning-foreground))',
            border: 'hsl(var(--state-warning-border))',
            hc: 'hsl(var(--state-warning-hc))',
            'hc-foreground': 'hsl(var(--state-warning-hc-foreground))',
          },
          blocker: {
            DEFAULT: 'hsl(var(--state-blocker))',
            foreground: 'hsl(var(--state-blocker-foreground))',
            border: 'hsl(var(--state-blocker-border))',
            hc: 'hsl(var(--state-blocker-hc))',
            'hc-foreground': 'hsl(var(--state-blocker-hc-foreground))',
          },
          draft: {
            DEFAULT: 'hsl(var(--state-draft))',
            foreground: 'hsl(var(--state-draft-foreground))',
            border: 'hsl(var(--state-draft-border))',
            hc: 'hsl(var(--state-draft-hc))',
            'hc-foreground': 'hsl(var(--state-draft-hc-foreground))',
          },
          selected: {
            DEFAULT: 'hsl(var(--state-selected))',
            foreground: 'hsl(var(--state-selected-foreground))',
            border: 'hsl(var(--state-selected-border))',
            hc: 'hsl(var(--state-selected-hc))',
            'hc-foreground': 'hsl(var(--state-selected-hc-foreground))',
          },
          loading: {
            DEFAULT: 'hsl(var(--state-loading))',
            foreground: 'hsl(var(--state-loading-foreground))',
            border: 'hsl(var(--state-loading-border))',
            hc: 'hsl(var(--state-loading-hc))',
            'hc-foreground': 'hsl(var(--state-loading-hc-foreground))',
          },
          error: {
            DEFAULT: 'hsl(var(--state-error))',
            foreground: 'hsl(var(--state-error-foreground))',
            border: 'hsl(var(--state-error-border))',
            hc: 'hsl(var(--state-error-hc))',
            'hc-foreground': 'hsl(var(--state-error-hc-foreground))',
          },
          'permission-restricted': {
            DEFAULT: 'hsl(var(--state-permission-restricted))',
            foreground: 'hsl(var(--state-permission-restricted-foreground))',
            border: 'hsl(var(--state-permission-restricted-border))',
            hc: 'hsl(var(--state-permission-restricted-hc))',
            'hc-foreground': 'hsl(var(--state-permission-restricted-hc-foreground))',
          },
          empty: {
            DEFAULT: 'hsl(var(--state-empty))',
            foreground: 'hsl(var(--state-empty-foreground))',
            border: 'hsl(var(--state-empty-border))',
            hc: 'hsl(var(--state-empty-hc))',
            'hc-foreground': 'hsl(var(--state-empty-hc-foreground))',
          },
          stale: {
            DEFAULT: 'hsl(var(--state-stale))',
            foreground: 'hsl(var(--state-stale-foreground))',
            border: 'hsl(var(--state-stale-border))',
            hc: 'hsl(var(--state-stale-hc))',
            'hc-foreground': 'hsl(var(--state-stale-hc-foreground))',
          },
          recovery: {
            DEFAULT: 'hsl(var(--state-recovery))',
            foreground: 'hsl(var(--state-recovery-foreground))',
            border: 'hsl(var(--state-recovery-border))',
            hc: 'hsl(var(--state-recovery-hc))',
            'hc-foreground': 'hsl(var(--state-recovery-hc-foreground))',
          },
        },
      },
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      keyframes: {
        'accordion-down': {
          from: {
            height: '0',
          },
          to: {
            height: 'var(--radix-accordion-content-height)',
          },
        },
        'accordion-up': {
          from: {
            height: 'var(--radix-accordion-content-height)',
          },
          to: {
            height: '0',
          },
        },
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out',
      },
    },
  },
  plugins: [tailwindcssAnimate],
} satisfies Config;
