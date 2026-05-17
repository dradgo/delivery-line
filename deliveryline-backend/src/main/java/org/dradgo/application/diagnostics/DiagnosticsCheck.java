package org.dradgo.application.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

public record DiagnosticsCheck(
    String name,
    DiagnosticsStatus status,
    String summary,
    String remediation,
    String errorCode,
    Map<String, String> details) {

  public DiagnosticsCheck {
    details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
  }
}
