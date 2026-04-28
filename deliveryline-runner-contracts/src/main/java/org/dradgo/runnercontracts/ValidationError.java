package org.dradgo.runnercontracts;

public record ValidationError(
	ValidationErrorCode code,
	String path,
	String message
) {
}
