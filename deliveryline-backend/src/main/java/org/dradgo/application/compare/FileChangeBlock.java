package org.dradgo.application.compare;

/**
 * Story 4.19 (AC5, Reconciliation 8) — a prOutput file-level change summary. Emitted by {@link
 * FileLevelDiffer}, which parses the two artifacts' resolved unified-diff payloads and summarizes
 * per file. The full diff content is NOT carried here — the UI (story 4.20) lazy-loads it via the
 * existing {@code GET /api/v1/workflows/{runId}/artifacts/{artifactId}} using {@link
 * RevisionDelta#linkedDiffReferences()}.
 *
 * @param filePath the changed file's path (the {@code b/…} side of the unified-diff {@code diff
 *     --git} header, or the {@code a/…} side for a deleted file). Never {@code null}.
 * @param changeKind one of {@link ChangeKind#ADDED} (file present only in revision B's changeset) /
 *     {@link ChangeKind#REMOVED} (present only in revision A's changeset) / {@link
 *     ChangeKind#MODIFIED} (present in both). Never {@code null}.
 * @param addedLines number of {@code +} lines for this file in the revision the counts are taken
 *     from; {@code null} when the relevant unified diff was absent/unparseable (Reconciliation 8).
 * @param removedLines number of {@code -} lines for this file; {@code null} when the relevant
 *     unified diff was absent/unparseable.
 */
public record FileChangeBlock(
    String filePath, String changeKind, Integer addedLines, Integer removedLines)
    implements ChangeBlock {}
