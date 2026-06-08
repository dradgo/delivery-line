import { Toaster as Sonner, type ToasterProps } from 'sonner';

import { FEEDBACK_TOAST_POSITION } from './sonnerConfig';

// Story 2.2 — shadcn/ui Sonner toast host, adapted for the Vite SPA.
//
// The stock shadcn generator targets Next.js: it reads the active theme from
// `next-themes` and (a known generator glitch) imports `Toaster` from this file
// itself instead of the `sonner` package. DeliveryLine is a Vite SPA with no
// next-themes provider, and dark mode is WIRED-not-activated for Epic 2 (AC8),
// so the theme is pinned to "light" here and next-themes is not a dependency.
// A future dark-mode story (see README "Design System") would replace the
// hard-coded theme with the real theme source.
const Toaster = (props: ToasterProps) => {
  return (
    <Sonner
      theme="light"
      // Story 2.21 (AC9 / OQ-5) — top-right keeps ancillary toasts away from the
      // bottom-anchored primary action controls (e.g. the sticky-footer
      // ApprovalDecisionBar) so a confirmation never covers the buttons the user
      // is acting on. Documented as `FEEDBACK_TOAST_POSITION` in
      // components/feedback/primitives/feedbackToast.ts. Overridable via props.
      position={FEEDBACK_TOAST_POSITION}
      className="toaster group"
      toastOptions={{
        classNames: {
          toast:
            'group toast group-[.toaster]:bg-background group-[.toaster]:text-foreground group-[.toaster]:border-border group-[.toaster]:shadow-lg',
          description: 'group-[.toast]:text-muted-foreground',
          actionButton: 'group-[.toast]:bg-primary group-[.toast]:text-primary-foreground',
          cancelButton: 'group-[.toast]:bg-muted group-[.toast]:text-muted-foreground',
        },
      }}
      {...props}
    />
  );
};

export { Toaster };
