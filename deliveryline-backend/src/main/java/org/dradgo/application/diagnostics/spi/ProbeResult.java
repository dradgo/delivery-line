package org.dradgo.application.diagnostics.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.diagnostics.DiagnosticsStatus;

public record ProbeResult(
	DiagnosticsStatus status,
	String summary,
	String errorCode,
	Map<String, String> details
) {

	public ProbeResult {
		details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
	}

	public static ProbeResult pass(String summary) {
		return new ProbeResult(DiagnosticsStatus.PASS, summary, null, Map.of());
	}

	public static ProbeResult pass(String summary, Map<String, String> details) {
		return new ProbeResult(DiagnosticsStatus.PASS, summary, null, details);
	}

	public static ProbeResult warn(String summary, String errorCode, Map<String, String> details) {
		return new ProbeResult(DiagnosticsStatus.WARN, summary, errorCode, details);
	}

	public static ProbeResult fail(String summary, String errorCode, Map<String, String> details) {
		return new ProbeResult(DiagnosticsStatus.FAIL, summary, errorCode, details);
	}

	public static ProbeResult skip(String summary) {
		return new ProbeResult(DiagnosticsStatus.SKIP, summary, null, Map.of());
	}
}
