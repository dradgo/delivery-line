import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

// Story 2.2 AC5 — the standard shadcn/ui `cn()` helper. Composites and primitives
// use it for conditional className composition: `clsx` resolves conditional/array
// class inputs, `twMerge` de-duplicates conflicting Tailwind utilities so the last
// one wins (e.g. `cn('p-2', 'p-4')` → `'p-4'`).
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
