package org.dradgo.application.security;

public enum RedactionCategory {
	LINEAR_API_KEY("[REDACTED_LINEAR_API_KEY]"),
	GITHUB_TOKEN("[REDACTED_GITHUB_TOKEN]"),
	SSH_PRIVATE_KEY("[REDACTED_SSH_PRIVATE_KEY]"),
	SSH_PUBLIC_KEY("[REDACTED_SSH_PUBLIC_KEY]"),
	AUTHORIZATION_HEADER("[REDACTED_AUTHORIZATION_HEADER]"),
	QUERY_SECRET("[REDACTED_QUERY_SECRET]"),
	ENV_VALUE("[REDACTED_ENV_VALUE]"),
	SECRET_FIELD("[REDACTED_SECRET_FIELD]"),
	LOCAL_PATH("[REDACTED_LOCAL_PATH]"),
	ENVIRONMENT_BLOCK("[REDACTED_ENVIRONMENT_BLOCK]");

	private final String placeholder;

	RedactionCategory(String placeholder) {
		this.placeholder = placeholder;
	}

	public String placeholder() {
		return placeholder;
	}
}
