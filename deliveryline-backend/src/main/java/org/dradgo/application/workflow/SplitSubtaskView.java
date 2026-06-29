package org.dradgo.application.workflow;

/**
 * Story 3f-4 — one proposed subtask inside an advisory split proposal: a 1-based ordinal, a short
 * title, and a one-sentence scope. The ordinal is the index the dependency edges reference and the
 * order 3f-5 mints child runs in. A read-view record (REST maps it directly), so it lives in {@code
 * application.workflow}, not {@code application.workflow.spi} (REST-stays-thin ArchUnit pin).
 */
public record SplitSubtaskView(int ordinal, String title, String scope) {}
