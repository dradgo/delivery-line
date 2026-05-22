package org.dradgo.adapters.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.dradgo.application.diagnostics.DiagnosticsCheck;
import org.dradgo.application.diagnostics.DiagnosticsReport;
import org.dradgo.application.diagnostics.DiagnosticsStatus;
import org.dradgo.application.diagnostics.DoctorRunRequest;
import org.dradgo.application.diagnostics.DoctorService;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(name = "doctor", description = "Runtime diagnostics", prefix = "deliveryline")
public class DoctorCommands {

  private static final Logger log = LoggerFactory.getLogger(DoctorCommands.class);

  private static final String COMMAND_NAME = "doctor";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";

  private final DoctorService doctorService;
  private final DoctorReportRenderer renderer;
  private final Supplier<String> correlationIdSupplier;

  @Autowired
  public DoctorCommands(
      DoctorService doctorService, DoctorReportRenderer renderer, UuidV7Generator uuidV7Generator) {
    this(doctorService, renderer, uuidV7Generator::generate);
  }

  public DoctorCommands(
      DoctorService doctorService,
      DoctorReportRenderer renderer,
      Supplier<String> correlationIdSupplier) {
    this.doctorService = doctorService;
    this.renderer = renderer;
    this.correlationIdSupplier = correlationIdSupplier;
  }

  @Command(
      name = "doctor",
      description = "Run runtime prerequisite checks",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String doctor(
      @Option(
              longName = "format",
              description = "Output format: text or json",
              required = false,
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "only",
              description = "Comma-separated list of check names to run",
              required = false)
          String only,
      @Option(
              longName = "exclude",
              description = "Comma-separated list of check names to skip",
              required = false)
          String exclude,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    int checksRun = 0;
    try {
      String normalizedFormat = normalizeFormat(format);
      Set<String> onlySet = parseCsvOption(only);
      Set<String> excludeSet = parseCsvOption(exclude);
      DoctorRunRequest request = new DoctorRunRequest(onlySet, excludeSet, resolvedCorrelation);

      DiagnosticsReport report = doctorService.runDiagnostics(request);
      checksRun = report.checks().size();
      String rendered = render(report, normalizedFormat);

      if (report.overallStatus() == DiagnosticsStatus.FAIL) {
        // System.out is intentional here: this is the CLI command's user-facing
        // result envelope (Spring Shell renders command output to stdout), not
        // diagnostic logging. Story 2.30 keeps it and exempts this file in
        // config/checkstyle/suppressions.xml rather than routing product output
        // through the SLF4J logger.
        System.out.println(rendered);
        throw failureException(report);
      }
      emitSuccess(checksRun, resolvedCorrelation, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure(checksRun, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure(checksRun, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  private String render(DiagnosticsReport report, String normalizedFormat) {
    return switch (normalizedFormat) {
      case FORMAT_JSON -> renderer.renderJson(report);
      case FORMAT_TEXT -> renderer.renderText(report);
      default -> throw unsupportedFormat(normalizedFormat);
    };
  }

  private String normalizeFormat(String format) {
    if (format == null || format.isBlank()) {
      return FORMAT_TEXT;
    }
    String normalized = format.trim().toLowerCase(Locale.ROOT);
    if (FORMAT_TEXT.equals(normalized) || FORMAT_JSON.equals(normalized)) {
      return normalized;
    }
    throw unsupportedFormat(format);
  }

  private DomainException unsupportedFormat(String format) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("format", format);
    details.put("supportedFormats", List.of(FORMAT_TEXT, FORMAT_JSON));
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Unsupported --format value: " + format, details);
  }

  private Set<String> parseCsvOption(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    Set<String> values = new LinkedHashSet<>();
    for (String token : csv.split(",")) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return values;
  }

  private DomainException failureException(DiagnosticsReport report) {
    DiagnosticsCheck firstFail =
        report.checks().stream()
            .filter(c -> c.status() == DiagnosticsStatus.FAIL)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("FAIL report without FAIL check"));
    DomainErrorCode code = parseErrorCode(firstFail.errorCode());
    List<String> failedNames = new ArrayList<>();
    for (DiagnosticsCheck check : report.checks()) {
      if (check.status() == DiagnosticsStatus.FAIL) {
        failedNames.add(check.name());
      }
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("failedChecks", failedNames);
    return new DomainException(code, firstFail.summary(), details);
  }

  private DomainErrorCode parseErrorCode(String wireValue) {
    if (wireValue == null) {
      return DomainErrorCode.INTERNAL_ERROR;
    }
    for (DomainErrorCode candidate : DomainErrorCode.values()) {
      if (candidate.value().equals(wireValue)) {
        return candidate;
      }
    }
    return DomainErrorCode.INTERNAL_ERROR;
  }

  private CorrelationScope pushCorrelation(String supplied) {
    String resolved = supplied;
    if (resolved == null || resolved.isBlank()) {
      resolved = correlationIdSupplier.get();
    }
    resolved = MdcKeys.sanitizeForLog(resolved);
    String prior = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, resolved);
    return new CorrelationScope(resolved, prior);
  }

  record CorrelationScope(String resolved, String prior) {}

  private static void emitSuccess(int checksRun, String correlationId, long startNanos) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "doctor command completed correlationId={} outcome={} checksRun={} durationMs={}",
        correlationId,
        OUTCOME_SUCCESS,
        checksRun,
        elapsedMs);
  }

  private static void emitFailure(
      int checksRun, String correlationId, long startNanos, String outcome) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "doctor command completed correlationId={} outcome={} checksRun={} durationMs={}",
        correlationId,
        outcome,
        checksRun,
        elapsedMs);
  }

  private static String codeFor(DomainException de) {
    return de.errorCode() == null
        ? OUTCOME_UNKNOWN
        : OUTCOME_FAILURE_PREFIX + de.errorCode().value();
  }
}
