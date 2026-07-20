package org.dradgo.application.compare;

/**
 * Story 4.19 (AC3) — a spec (markdown) section-level change. Emitted by {@link
 * MarkdownSectionDiffer}, which splits the markdown by ATX headings into {@code (sectionPath,
 * body)} regions and aligns them by {@code sectionPath}.
 *
 * @param sectionPath the heading trail identifying the section (e.g. {@code "Edge Cases"} or {@code
 *     "Design > Edge Cases"}); the empty string for the pre-heading preamble region. Never {@code
 *     null}.
 * @param changeKind one of {@link ChangeKind#ADDED} / {@link ChangeKind#REMOVED} / {@link
 *     ChangeKind#MODIFIED}. Never {@code null}.
 * @param priorText the section body in revision A; {@code null} for an {@code added} section
 *     (absent in A). Redacted before serialization (AC6).
 * @param currentText the section body in revision B; {@code null} for a {@code removed} section
 *     (absent in B). Redacted before serialization (AC6).
 */
public record MarkdownChangeBlock(
    String sectionPath, String changeKind, String priorText, String currentText)
    implements ChangeBlock {}
