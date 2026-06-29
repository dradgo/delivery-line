package org.dradgo.application.workflow;

/**
 * Story 3f-4 — one suggested ordering edge inside an advisory split proposal: the subtask at {@code
 * fromOrdinal} depends on the subtask at {@code toOrdinal}. 3f-5 turns these into run_dependencies
 * edges. A read-view record (REST maps it directly), so it lives in {@code application.workflow},
 * not {@code application.workflow.spi} (REST-stays-thin ArchUnit pin).
 */
public record SplitDependencyView(int fromOrdinal, int toOrdinal) {}
