package org.dradgo.application.runner;

import org.dradgo.domain.registry.DataClassification;

/**
 * Story 3.6 AC1 — result of {@link RunnerLogCaptureService#captureLogs}. Carries the redacted-log
 * store reference path, the total redacted byte size (sum of both streams), the AC4 effective
 * classification, and the derived redaction count (Trap T2 / OQ-1). Like {@link RunnerLogReference}
 * it carries metrics ONLY — never raw or redacted content, never a secret value (Trap T1).
 */
public record CapturedLogs(
    String referencePath, long byteSize, DataClassification classification, int redactionCount) {}
