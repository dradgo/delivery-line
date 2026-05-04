package org.dradgo.adapters.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.dradgo.application.security.RedactionPolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ProblemDetailsMapperTest {

	@Test
	void missingRedactionPolicyServiceFailsClosedForRejectedValues() throws Exception {
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("objectMapper", new ObjectMapper());
		ProblemDetailsMapper mapper = new ProblemDetailsMapper(
			beanFactory.getBeanProvider(org.dradgo.application.security.RedactionPolicyService.class),
			beanFactory.getBeanProvider(ObjectMapper.class));
		Method method = ProblemDetailsMapper.class.getDeclaredMethod(
			"sanitizeRejectedValue",
			String.class,
			Object.class);
		method.setAccessible(true);

		Object sanitized = method.invoke(mapper, "apiKey", "secret-token-value");

		assertEquals("[REDACTED]", sanitized);
	}

	@Test
	void redactionFailuresFailClosedForRejectedValues() throws Exception {
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		beanFactory.addBean("objectMapper", new ObjectMapper());
		RedactionPolicyService redactionPolicyService = Mockito.mock(RedactionPolicyService.class);
		Mockito.when(redactionPolicyService.redact(Mockito.anyMap(), Mockito.isNull()))
			.thenThrow(new IllegalStateException("boom"));
		beanFactory.addBean("redactionPolicyService", redactionPolicyService);
		ProblemDetailsMapper mapper = new ProblemDetailsMapper(
			beanFactory.getBeanProvider(RedactionPolicyService.class),
			beanFactory.getBeanProvider(ObjectMapper.class));
		Method method = ProblemDetailsMapper.class.getDeclaredMethod(
			"sanitizeRejectedValue",
			String.class,
			Object.class);
		method.setAccessible(true);

		Object sanitized = assertDoesNotThrow(() -> method.invoke(mapper, "apiKey", "secret-token-value"));

		assertEquals("[REDACTED]", sanitized);
	}
}
