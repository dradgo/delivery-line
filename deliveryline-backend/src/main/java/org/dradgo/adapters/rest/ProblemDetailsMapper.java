package org.dradgo.adapters.rest;

import com.fasterxml.jackson.databind.JsonMappingException.Reference;
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
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ProblemDetailsMapper {

	private static final String REDACTED_VALUE = "[REDACTED]";
	private static final String GENERIC_INTERNAL_ERROR_DETAIL = "An unexpected internal error occurred.";
	private static final Pattern JACKSON_FIELD_PATTERN = Pattern.compile("\\[\"([^\"]+)\"\\]");
	private static final Pattern JACKSON_STRING_VALUE_PATTERN = Pattern.compile("from String \\\"([^\\\"]+)\\\"");

	@ExceptionHandler(DomainException.class)
	public ResponseEntity<ProblemDetail> handleDomainException(DomainException exception, HttpServletRequest request) {
		ProblemDetailsCatalog.ProblemDetailsMetadata metadata = ProblemDetailsCatalog.metadataFor(exception.errorCode());
		return problemResponse(metadata, exception.errorCode(), exception.getMessage(), request.getRequestURI(), publicDetailsFor(exception));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
		MethodArgumentNotValidException exception,
		HttpServletRequest request
	) {
		return invalidCommandPayload(request.getRequestURI(), fieldErrorsFromBindingResult(exception.getBindingResult()));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(
		HandlerMethodValidationException exception,
		HttpServletRequest request
	) {
		List<Map<String, Object>> details = new ArrayList<>();
		for (ParameterValidationResult result : exception.getParameterValidationResults()) {
			if (result instanceof ParameterErrors parameterErrors) {
				details.addAll(fieldErrorsFromBindingResult(parameterErrors));
				continue;
			}
			String field = resolveParameterName(result);
			Object rejectedValue = redactIfSensitive(field, result.getArgument());
			String constraint = "invalid";
			List<MessageSourceResolvable> resolvableErrors = result.getResolvableErrors();
			if (!resolvableErrors.isEmpty()) {
				MessageSourceResolvable resolvable = resolvableErrors.getFirst();
				ConstraintViolation<?> violation = result.unwrap(resolvable, ConstraintViolation.class);
				if (violation != null) {
					constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
				}
			}
			details.add(fieldError(field, rejectedValue, constraint));
		}
		return invalidCommandPayload(request.getRequestURI(), details);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ProblemDetail> handleMissingRequestHeader(
		MissingRequestHeaderException exception,
		HttpServletRequest request
	) {
		return invalidCommandPayload(request.getRequestURI(), List.of(
			fieldError(exception.getHeaderName(), null, "required")));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ProblemDetail> handleMethodArgumentTypeMismatch(
		MethodArgumentTypeMismatchException exception,
		HttpServletRequest request
	) {
		return invalidCommandPayload(request.getRequestURI(), List.of(
			fieldError(exception.getName(), redactIfSensitive(exception.getName(), exception.getValue()), "typeMismatch")));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
		HttpMessageNotReadableException exception,
		HttpServletRequest request
	) {
		InvalidFormatException invalidFormatException = findInvalidFormatException(exception);
		if (invalidFormatException != null && !invalidFormatException.getPath().isEmpty()) {
			String field = invalidFormatException.getPath().stream()
				.map(Reference::getFieldName)
				.filter(java.util.Objects::nonNull)
				.reduce((first, second) -> second)
				.orElse("body");
			return invalidCommandPayload(request.getRequestURI(), List.of(
				fieldError(field, redactIfSensitive(field, invalidFormatException.getValue()), "typeMismatch")));
		}
		String message = exception.getMessage();
		if (message != null && message.contains("through reference chain")) {
			String field = extractJacksonFieldName(message);
			if (field != null) {
				return invalidCommandPayload(request.getRequestURI(), List.of(
					fieldError(field, redactIfSensitive(field, extractJacksonRejectedValue(message)), "typeMismatch")));
			}
		}
		return invalidCommandPayload(request.getRequestURI(), List.of(fieldError("body", null, "malformedJson")));
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

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception, HttpServletRequest request) {
		ProblemDetailsCatalog.ProblemDetailsMetadata metadata = ProblemDetailsCatalog.metadataFor(DomainErrorCode.INTERNAL_ERROR);
		return problemResponse(
			metadata,
			DomainErrorCode.INTERNAL_ERROR,
			GENERIC_INTERNAL_ERROR_DETAIL,
			request.getRequestURI(),
			null);
	}

	private ResponseEntity<ProblemDetail> invalidCommandPayload(String requestPath, List<Map<String, Object>> details) {
		ProblemDetailsCatalog.ProblemDetailsMetadata metadata = ProblemDetailsCatalog.metadataFor(DomainErrorCode.INVALID_COMMAND_PAYLOAD);
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
		Object details
	) {
		ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(metadata.status(), detail);
		problemDetail.setType(URI.create(metadata.typeUri()));
		problemDetail.setTitle(metadata.title());
		problemDetail.setInstance(URI.create(requestPath));
		problemDetail.setProperty("code", code.value());
		problemDetail.setProperty("retryable", metadata.retryable());
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
			String field = String.valueOf(map.get("field"));
			Object rejectedValue = map.get("rejectedValue");
			String constraint = String.valueOf(map.get("code"));
			translated.add(fieldError(field, redactIfSensitive(field, rejectedValue), constraint));
		}
		return translated;
	}

	private List<Map<String, Object>> fieldErrorsFromBindingResult(Errors errors) {
		List<Map<String, Object>> details = new ArrayList<>();
		for (FieldError fieldError : errors.getFieldErrors()) {
			details.add(fieldError(
				fieldError.getField(),
				redactIfSensitive(fieldError.getField(), fieldError.getRejectedValue()),
				resolveConstraint(fieldError)));
		}
		return details;
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
		return code == null || code.isBlank() ? "invalid" : code;
	}

	private String resolveParameterName(ParameterValidationResult result) {
		RequestHeader requestHeader = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
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

	private Object redactIfSensitive(String field, Object rejectedValue) {
		if (rejectedValue == null) {
			return null;
		}
		String normalizedField = field.toLowerCase(Locale.ROOT);
		if (normalizedField.contains("secret")
			|| normalizedField.contains("password")
			|| normalizedField.contains("token")
			|| normalizedField.contains("authorization")
			|| normalizedField.contains("api-key")
			|| normalizedField.contains("api_key")
			|| normalizedField.contains("apikey")) {
			return REDACTED_VALUE;
		}
		return rejectedValue;
	}
}
