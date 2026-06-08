package org.dradgo.adapters.rest;

import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.security.RedactionPolicyService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ProblemDetailsMapper {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsMapper.class);
  private static final String GENERIC_INTERNAL_ERROR_DETAIL =
      "An unexpected internal error occurred.";
  private static final String UNKNOWN_FIELD_NAME = "unknown";
  private static final String DEFAULT_CONSTRAINT = "invalid";
  private static final String REDACTED_PLACEHOLDER = "[REDACTED]";
  private static final Pattern JACKSON_FIELD_PATTERN = Pattern.compile("\\[\"([^\"]+)\"\\]");
  private static final Pattern JACKSON_STRING_VALUE_PATTERN =
      Pattern.compile("from String \\\"([^\\\"]+)\\\"");

  private final RedactionPolicyService redactionPolicyService;
  private final ObjectMapper objectMapper;

  public ProblemDetailsMapper(
      ObjectProvider<RedactionPolicyService> redactionPolicyServiceProvider,
      ObjectProvider<ObjectMapper> objectMapperProvider) {
    this.redactionPolicyService = redactionPolicyServiceProvider.getIfAvailable();
    this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ProblemDetail> handleDomainException(
      DomainException exception, HttpServletRequest request) {
    ProblemDetailsCatalog.ProblemDetailsMetadata metadata =
        ProblemDetailsCatalog.metadataFor(exception.errorCode());
    // Story 2.13 logging instrumentation: pin a WARN at the typed-domain-rejection catch-site so a
    // log scrape can correlate request id → outcome without re-deploying. Code + status + path are
    // machine-readable and free of caller-private payload data (architecture line 712 forbids
    // human-text assertions on Problem Details, mirrored here for log content).
    LOG.warn(
        "REST domain exception code={} status={} method={} path={} correlationId={}",
        exception.errorCode().value(),
        metadata.status().value(),
        MdcKeys.sanitizeForLog(request.getMethod()),
        MdcKeys.sanitizeForLog(request.getRequestURI()),
        MdcKeys.sanitizeForLog(MDC.get(MdcKeys.CORRELATION_ID)));
    return problemResponse(
        metadata,
        exception.errorCode(),
        exception.getMessage(),
        request.getRequestURI(),
        publicDetailsFor(exception));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    return invalidCommandPayload(
        request.getRequestURI(), fieldErrorsFromBindingResult(exception.getBindingResult()));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(
      HandlerMethodValidationException exception, HttpServletRequest request) {
    List<Map<String, Object>> details = new ArrayList<>();
    for (ParameterValidationResult result : exception.getParameterValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        details.addAll(fieldErrorsFromBindingResult(parameterErrors));
        continue;
      }
      String field = resolveParameterName(result);
      Object rejectedValue = sanitizeRejectedValue(field, result.getArgument());
      String constraint = "invalid";
      List<MessageSourceResolvable> resolvableErrors = result.getResolvableErrors();
      if (!resolvableErrors.isEmpty()) {
        MessageSourceResolvable resolvable = resolvableErrors.getFirst();
        ConstraintViolation<?> violation = result.unwrap(resolvable, ConstraintViolation.class);
        if (violation != null) {
          constraint =
              violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        }
      }
      details.add(fieldError(field, rejectedValue, constraint));
    }
    return invalidCommandPayload(request.getRequestURI(), details);
  }

  /**
   * Story 2.13 round-4 P-R4-14: registry of header-name → typed DomainErrorCode so future required
   * headers can claim a typed Problem Details code via a one-line addition instead of growing the
   * if/else chain. Lookup is case-insensitive (Spring's {@code getHeaderName()} echoes the declared
   * parameter name, which may differ in case from what the client sent).
   */
  private static final Map<String, DomainErrorCode> MISSING_HEADER_CODES =
      Map.of("idempotency-key", DomainErrorCode.MISSING_IDEMPOTENCY_KEY);

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ProblemDetail> handleMissingRequestHeader(
      MissingRequestHeaderException exception, HttpServletRequest request) {
    // Story 2.13 trap T3: missing Idempotency-Key must surface as the typed
    // MISSING_IDEMPOTENCY_KEY Problem Details code (not the generic INVALID_COMMAND_PAYLOAD), so
    // clients can distinguish "you forgot the header" from "the body failed validation". Other
    // missing required headers still fall through to INVALID_COMMAND_PAYLOAD with field-level
    // details so future required headers do not become silently typed.
    String headerName = exception.getHeaderName();
    DomainErrorCode typed =
        headerName == null
            ? null
            : MISSING_HEADER_CODES.get(headerName.toLowerCase(java.util.Locale.ROOT));
    if (typed != null) {
      ProblemDetailsCatalog.ProblemDetailsMetadata metadata =
          ProblemDetailsCatalog.metadataFor(typed);
      List<Map<String, Object>> details = List.of(fieldError(headerName, null, "required"));
      return problemResponse(
          metadata,
          typed,
          "Missing required header: " + headerName,
          request.getRequestURI(),
          details);
    }
    return invalidCommandPayload(
        request.getRequestURI(), List.of(fieldError(headerName, null, "required")));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentTypeMismatch(
      MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
    return invalidCommandPayload(
        request.getRequestURI(),
        List.of(
            fieldError(
                exception.getName(),
                sanitizeRejectedValue(exception.getName(), exception.getValue()),
                "typeMismatch")));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    InvalidFormatException invalidFormatException = findInvalidFormatException(exception);
    if (invalidFormatException != null && !invalidFormatException.getPath().isEmpty()) {
      String field =
          invalidFormatException.getPath().stream()
              .map(Reference::getFieldName)
              .filter(java.util.Objects::nonNull)
              .reduce((first, second) -> second)
              .orElse("body");
      return invalidCommandPayload(
          request.getRequestURI(),
          List.of(
              fieldError(
                  field,
                  sanitizeRejectedValue(field, invalidFormatException.getValue()),
                  "typeMismatch")));
    }
    String message = exception.getMessage();
    if (message != null && message.contains("through reference chain")) {
      String field = extractJacksonFieldName(message);
      if (field != null) {
        return invalidCommandPayload(
            request.getRequestURI(),
            List.of(
                fieldError(
                    field,
                    sanitizeRejectedValue(field, extractJacksonRejectedValue(message)),
                    "typeMismatch")));
      }
    }
    return invalidCommandPayload(
        request.getRequestURI(), List.of(fieldError("body", null, "malformedJson")));
  }

  private InvalidFormatException findInvalidFormatException(Throwable exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof InvalidFormatException invalidFormatException) {
        return invalidFormatException;
      }
      current = current.getCause();
    }
    return null;
  }

  private String extractJacksonFieldName(String message) {
    Matcher matcher = JACKSON_FIELD_PATTERN.matcher(message);
    String field = null;
    while (matcher.find()) {
      field = matcher.group(1);
    }
    return field;
  }

  private String extractJacksonRejectedValue(String message) {
    Matcher matcher = JACKSON_STRING_VALUE_PATTERN.matcher(message);
    return matcher.find() ? matcher.group(1) : null;
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
    return requestShapeProblem(
        HttpStatus.METHOD_NOT_ALLOWED,
        "HTTP method not allowed.",
        request.getRequestURI(),
        List.of(fieldError("method", exception.getMethod(), "methodNotAllowed")));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> handleNoResourceFound(
      NoResourceFoundException exception, HttpServletRequest request) {
    // Embedded-frontend mode: a hard-load / refresh of a client-side route (e.g.
    // /workflows/run_123)
    // reaches the backend as a NoResourceFoundException. Serve the SPA shell so TanStack Router can
    // resolve it client-side — but ONLY for genuine browser navigations to non-API routes, and only
    // when the Vite bundle is actually embedded (classpath:/static/index.html). API/management
    // paths,
    // missing assets, non-GET, and JSON clients keep their JSON ProblemDetails unchanged. When the
    // bundle is absent (e.g. the test tier, or an API-only dev run) this branch is inert.
    if (shouldServeSpaShell(exception.getResourcePath(), request) && SPA_SHELL.exists()) {
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_HTML)
          // index.html must not be cached — content-hashed assets cache, the shell must not, so a
          // redeploy is picked up immediately.
          .cacheControl(CacheControl.noStore())
          .body(SPA_SHELL);
    }
    return requestShapeProblem(
        HttpStatus.NOT_FOUND,
        "Resource not found.",
        request.getRequestURI(),
        List.of(fieldError("path", exception.getResourcePath(), "notFound")));
  }

  /** The embedded Vite app shell; present only when the frontend bundle is on the classpath. */
  private static final Resource SPA_SHELL = new ClassPathResource("static/index.html");

  /**
   * Path prefixes that must NEVER be diverted to the SPA shell — they have to keep their JSON 404s
   * so API clients, the actuator, and the OpenAPI/Swagger tooling behave correctly.
   */
  private static final List<String> RESERVED_NON_SPA_PREFIXES =
      List.of("api", "actuator", "v3/api-docs", "swagger-ui");

  /**
   * Decide whether an unresolved request should be served the SPA shell (embedded-frontend
   * deep-link / refresh) instead of a JSON ProblemDetail 404. Package-private + static so the
   * routing predicate can be unit-tested without a Spring context or a built bundle.
   */
  static boolean shouldServeSpaShell(String resourcePath, HttpServletRequest request) {
    if (resourcePath == null || !"GET".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    for (String reserved : RESERVED_NON_SPA_PREFIXES) {
      if (path.equalsIgnoreCase(reserved)
          || path.regionMatches(true, 0, reserved + "/", 0, reserved.length() + 1)) {
        return false;
      }
    }
    // A trailing segment containing a dot looks like a (missing) static asset, not a route — let it
    // 404 so a broken asset reference stays visible instead of silently returning HTML.
    String lastSegment = path.substring(path.lastIndexOf('/') + 1);
    if (lastSegment.contains(".")) {
      return false;
    }
    // Only divert genuine browser navigations (which accept HTML); JSON/XHR clients keep the 404.
    return acceptsHtml(request);
  }

  private static boolean acceptsHtml(HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    if (accept == null || accept.isBlank()) {
      return true; // no Accept header → treat as a navigation (browser default / curl)
    }
    String lower = accept.toLowerCase(Locale.ROOT);
    return lower.contains("text/html") || lower.contains("*/*");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleHttpMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
    return requestShapeProblem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "Unsupported media type.",
        request.getRequestURI(),
        List.of(
            fieldError(
                "Content-Type",
                String.valueOf(exception.getContentType()),
                "unsupportedMediaType")));
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> handleHttpMediaTypeNotAcceptable(
      HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
    return requestShapeProblem(
        HttpStatus.NOT_ACCEPTABLE,
        "Not acceptable.",
        request.getRequestURI(),
        List.of(
            fieldError("Accept", String.valueOf(request.getHeader("Accept")), "notAcceptable")));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpectedException(
      Exception exception, HttpServletRequest request) {
    LOG.error(
        "Unhandled exception caught by ProblemDetailsMapper; returning 500 INTERNAL_ERROR",
        exception);
    ProblemDetailsCatalog.ProblemDetailsMetadata metadata =
        ProblemDetailsCatalog.metadataFor(DomainErrorCode.INTERNAL_ERROR);
    return problemResponse(
        metadata,
        DomainErrorCode.INTERNAL_ERROR,
        GENERIC_INTERNAL_ERROR_DETAIL,
        request.getRequestURI(),
        null);
  }

  private ResponseEntity<ProblemDetail> requestShapeProblem(
      HttpStatus status, String detail, String requestPath, List<Map<String, Object>> details) {
    ProblemDetailsCatalog.ProblemDetailsMetadata metadata =
        ProblemDetailsCatalog.metadataFor(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    problemDetail.setType(URI.create(metadata.typeUri()));
    problemDetail.setTitle(metadata.title());
    problemDetail.setInstance(URI.create(requestPath));
    problemDetail.setProperty("code", DomainErrorCode.INVALID_COMMAND_PAYLOAD.value());
    problemDetail.setProperty("retryable", metadata.retryable());
    if (details != null) {
      problemDetail.setProperty("details", details);
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemDetail);
  }

  private ResponseEntity<ProblemDetail> invalidCommandPayload(
      String requestPath, List<Map<String, Object>> details) {
    ProblemDetailsCatalog.ProblemDetailsMetadata metadata =
        ProblemDetailsCatalog.metadataFor(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
    return problemResponse(
        metadata,
        DomainErrorCode.INVALID_COMMAND_PAYLOAD,
        "Invalid command payload.",
        requestPath,
        details);
  }

  private ResponseEntity<ProblemDetail> problemResponse(
      ProblemDetailsCatalog.ProblemDetailsMetadata metadata,
      DomainErrorCode code,
      String detail,
      String requestPath,
      Object details) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(metadata.status(), detail);
    problemDetail.setType(URI.create(metadata.typeUri()));
    problemDetail.setTitle(metadata.title());
    problemDetail.setInstance(URI.create(requestPath));
    problemDetail.setProperty("code", code.value());
    problemDetail.setProperty("retryable", metadata.retryable());
    String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
    if (correlationId != null && !correlationId.isBlank() && correlationId.length() <= 64) {
      problemDetail.setProperty(MdcKeys.CORRELATION_ID, correlationId);
    }
    if (details != null) {
      problemDetail.setProperty("details", details);
    }
    return ResponseEntity.status(metadata.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemDetail);
  }

  private Object publicDetailsFor(DomainException exception) {
    Map<String, Object> details = exception.details();
    if (details.isEmpty()) {
      return null;
    }
    Object rawFieldErrors = details.get("fieldErrors");
    if (rawFieldErrors instanceof List<?> fieldErrors) {
      return translateFieldErrors(fieldErrors);
    }
    return new LinkedHashMap<>(details);
  }

  private List<Map<String, Object>> translateFieldErrors(List<?> fieldErrors) {
    List<Map<String, Object>> translated = new ArrayList<>();
    for (Object rawFieldError : fieldErrors) {
      if (!(rawFieldError instanceof Map<?, ?> map)) {
        continue;
      }
      Object rawField = map.get("field");
      String field = rawField == null ? UNKNOWN_FIELD_NAME : rawField.toString();
      Object rejectedValue = map.get("rejectedValue");
      Object rawConstraint = map.get("code");
      String constraint = rawConstraint == null ? DEFAULT_CONSTRAINT : rawConstraint.toString();
      translated.add(fieldError(field, sanitizeRejectedValue(field, rejectedValue), constraint));
    }
    return translated;
  }

  private List<Map<String, Object>> fieldErrorsFromBindingResult(Errors errors) {
    List<Map<String, Object>> details = new ArrayList<>();
    for (FieldError fieldError : errors.getFieldErrors()) {
      details.add(
          fieldError(
              fieldError.getField(),
              sanitizeRejectedValue(fieldError.getField(), fieldError.getRejectedValue()),
              resolveConstraint(fieldError)));
    }
    for (ObjectError objectError : errors.getGlobalErrors()) {
      details.add(
          fieldError(objectError.getObjectName(), null, resolveObjectConstraint(objectError)));
    }
    return details;
  }

  private String resolveObjectConstraint(ObjectError objectError) {
    String code = objectError.getCode();
    return code == null || code.isBlank() ? DEFAULT_CONSTRAINT : code;
  }

  private Map<String, Object> fieldError(String field, Object rejectedValue, String constraint) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("field", field);
    detail.put("rejectedValue", rejectedValue);
    detail.put("constraint", constraint);
    return detail;
  }

  private String resolveConstraint(FieldError fieldError) {
    String code = fieldError.getCode();
    return code == null || code.isBlank() ? DEFAULT_CONSTRAINT : code;
  }

  private String resolveParameterName(ParameterValidationResult result) {
    RequestHeader requestHeader =
        result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
    if (requestHeader != null) {
      if (!requestHeader.name().isBlank()) {
        return requestHeader.name();
      }
      if (!requestHeader.value().isBlank()) {
        return requestHeader.value();
      }
    }
    String parameterName = result.getMethodParameter().getParameterName();
    return parameterName == null ? "parameter" : parameterName;
  }

  private Object sanitizeRejectedValue(String field, Object rejectedValue) {
    if (rejectedValue == null) {
      return null;
    }
    if (field == null) {
      return rejectedValue;
    }
    if (redactionPolicyService == null) {
      return REDACTED_PLACEHOLDER;
    }
    try {
      JsonNode sanitized =
          redactionPolicyService.redact(Map.of(field, rejectedValue), null).sanitizedJson();
      if (sanitized == null || !sanitized.has(field)) {
        return REDACTED_PLACEHOLDER;
      }
      return objectMapper.convertValue(sanitized.get(field), Object.class);
    } catch (RuntimeException exception) {
      LOG.warn(
          "Rejected-value redaction failed for field {}; falling back to placeholder",
          field,
          exception);
      return REDACTED_PLACEHOLDER;
    }
  }
}
