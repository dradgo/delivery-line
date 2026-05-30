/**
 * Story 2.22 (AC8.b) — typed accessor for the catastrophic-error context.
 */
import { useContext } from 'react';

import {
  CatastrophicErrorContext,
  type CatastrophicErrorContextValue,
} from './CatastrophicErrorContext';

export function useCatastrophicError(): CatastrophicErrorContextValue {
  const value = useContext(CatastrophicErrorContext);
  if (value === null) {
    throw new Error('useCatastrophicError must be used within <CatastrophicErrorProvider>.');
  }
  return value;
}
