// runArchiveView.test.ts
import { describe, expect, it } from 'vitest';
import {
  resolveArchiveMode,
  archiveFields,
  archiveButtonLabel,
  archiveConfirmLabel,
  archiveConsequence,
  archiveDialogTitle,
  archiveIntent,
  mapArchiveErrorCode,
  ARCHIVE_REASON_MAX_LENGTH,
} from './runArchiveView';

describe('resolveArchiveMode', () => {
  it('archive_run → archive, unarchive_run → unarchive, neither → null', () => {
    expect(resolveArchiveMode(['archive_run', 'retry'])).toBe('archive');
    expect(resolveArchiveMode(['unarchive_run'])).toBe('unarchive');
    expect(resolveArchiveMode(['retry'])).toBeNull();
    expect(resolveArchiveMode(undefined)).toBeNull();
  });
});

describe('archiveFields', () => {
  it('archive reason is required; unarchive reason is optional; both length-capped', () => {
    const archive = archiveFields('archive')[0]!;
    const unarchive = archiveFields('unarchive')[0]!;
    expect(archive.required).toBe(true);
    expect(unarchive.required).toBeFalsy();
    const tooLong = 'a'.repeat(ARCHIVE_REASON_MAX_LENGTH + 1);
    expect(archive.validate?.(tooLong)).toMatch(/512/);
    expect(archive.validate?.('ok')).toBeUndefined();
  });
});

describe('labels + intent + error copy', () => {
  it('mode-specific labels and intent', () => {
    expect(archiveButtonLabel('archive')).toBe('Archive run');
    expect(archiveButtonLabel('unarchive')).toBe('Unarchive run');
    expect(archiveConfirmLabel('archive')).toBe('Archive');
    expect(archiveConfirmLabel('unarchive')).toBe('Unarchive');
    expect(archiveIntent('archive')).toBe('warning');
    expect(archiveIntent('unarchive')).toBe('info');
  });
  it('maps known error codes; undefined otherwise', () => {
    expect(mapArchiveErrorCode('ARCHIVE_NOT_APPLICABLE')).toMatch(/refresh/i);
    expect(mapArchiveErrorCode('RUN_NOT_FOUND')).toMatch(/no longer exists|not found/i);
    expect(mapArchiveErrorCode('SOMETHING_ELSE')).toBeUndefined();
    expect(mapArchiveErrorCode(undefined)).toBeUndefined();
  });
  it('mode-specific dialog title and consequence copy', () => {
    expect(archiveDialogTitle('archive')).toBe('Archive run');
    expect(archiveDialogTitle('unarchive')).toBe('Unarchive run');
    expect(archiveConsequence('archive')).toMatch(/hides/i);
    expect(archiveConsequence('unarchive')).toMatch(/returns the run/i);
  });
  it('maps IDEMPOTENCY_KEY_CONFLICT to an actionable message', () => {
    expect(mapArchiveErrorCode('IDEMPOTENCY_KEY_CONFLICT')).toMatch(/already submitted|refresh/i);
  });
});
