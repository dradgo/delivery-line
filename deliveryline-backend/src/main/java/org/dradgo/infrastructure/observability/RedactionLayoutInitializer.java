package org.dradgo.infrastructure.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.security.RedactionPolicyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the Spring-managed {@link RedactionPolicyService} into the static
 * {@link RedactionLayoutHolder} consulted by Logback converters and JSON providers.
 *
 * <p>Spring's lifecycle order means the holder is set early in application startup, before
 * any application-emitted log line that could contain a project payload. Spring's own startup
 * banner output emitted before this {@code @PostConstruct} runs has no project secrets, so
 * the cold-start window is safe by construction.
 */
@Configuration
public class RedactionLayoutInitializer {

	private final RedactionPolicyService redactionPolicyService;

	public RedactionLayoutInitializer(RedactionPolicyService redactionPolicyService) {
		this.redactionPolicyService = redactionPolicyService;
	}

	@PostConstruct
	void wireHolder() {
		RedactionLayoutHolder.setRedactionService(redactionPolicyService);
	}

	@PreDestroy
	void clearHolder() {
		RedactionLayoutHolder.clearForTesting();
	}

	/**
	 * Register the {@link CorrelationIdFilter} only when a {@link UuidV7Generator} bean is on
	 * the context — keeps {@code @WebMvcTest} slices working without forcing them to import the
	 * full application configuration.
	 */
	@Bean
	@ConditionalOnBean(UuidV7Generator.class)
	FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(UuidV7Generator uuidV7Generator) {
		FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter(uuidV7Generator));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		registration.addUrlPatterns("/*");
		registration.setName("correlationIdFilter");
		return registration;
	}
}
