package org.dradgo.application.diagnostics;

import java.time.OffsetDateTime;
import java.util.List;

public record DiagnosticsReport(
	int schemaVersion,
	OffsetDateTime generatedAt,
	DiagnosticsStatus overallStatus,
	List<DiagnosticsCheck> checks
) {

	public DiagnosticsReport {
		checks = checks == null ? List.of() : List.copyOf(checks);
	}
}
