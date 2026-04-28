package org.dradgo.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.domain.registry.DomainErrorCode;

public final class DomainException extends RuntimeException {

	private final DomainErrorCode errorCode;
	private final Map<String, Object> details;

	public DomainException(DomainErrorCode errorCode, String message) {
		this(errorCode, message, null, null);
	}

	public DomainException(DomainErrorCode errorCode, String message, Throwable cause) {
		this(errorCode, message, null, cause);
	}

	public DomainException(DomainErrorCode errorCode, String message, Map<String, Object> details) {
		this(errorCode, message, details, null);
	}

	public DomainException(DomainErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
		this.details = details == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(details));
	}

	public DomainErrorCode errorCode() {
		return errorCode;
	}

	public Map<String, Object> details() {
		return details;
	}
}
