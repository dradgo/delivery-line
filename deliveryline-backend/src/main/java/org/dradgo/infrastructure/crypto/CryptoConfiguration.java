package org.dradgo.infrastructure.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Story 3c-4 — binds {@link CryptoProperties} for the credential subsystem.
 *
 * <p>The project registers {@code @ConfigurationProperties} via an explicit
 * {@code @EnableConfigurationProperties} on a per-subsystem {@code @Configuration} (mirroring
 * {@code RestBindingConfiguration} / {@code RunnerConfiguration}) rather than a global
 * {@code @ConfigurationPropertiesScan}, so this small class is the binding site. The cipher ({@link
 * EnvelopeCredentialCipher}) and the startup guard ({@link CredentialMasterKeyGuard}) are
 * {@code @Component}s picked up by the component scan.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfiguration {}
