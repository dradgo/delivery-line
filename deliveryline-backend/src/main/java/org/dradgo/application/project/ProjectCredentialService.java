package org.dradgo.application.project;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.application.security.CredentialCipher;
import org.dradgo.application.security.CredentialCipherException;
import org.dradgo.application.security.EncryptedSecret;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.project.ProjectCredential;
import org.dradgo.domain.registry.ConnectorRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3c-5 — the <strong>write-only</strong> encrypted per-project credential store, and the live
 * implementation of the 3c-3 {@link ProjectCredentialSource} seam (so {@code
 * ProjectConnectorResolver.resolveConnectorSecret(...)} now returns real decrypted secrets at
 * use-time).
 *
 * <p><strong>Write-only contract (AC2/AC7).</strong> The public API exposes only {@link
 * #setCredential} (which returns the non-secret {@code cred_} public id) and the internal {@link
 * #getDecrypted} (plaintext for immediate connector use). There is deliberately <em>no</em>
 * read-back method that returns a stored secret — plaintext or ciphertext — to a caller. The
 * decrypted plaintext is held only on the call stack: it is never logged and never assigned to a
 * field.
 *
 * <p><strong>Export note (AC5).</strong> No run-export pipeline exists yet (epic-05 is unbuilt).
 * Because this store is write-only, the {@code project_credentials} ciphertext is never serialized
 * onto any client-facing payload and so cannot reach an export by construction. When epic-05's
 * export pipeline lands it MUST additionally exclude the {@code project_credentials} table
 * explicitly (a {@code redactForExport} regression test guards the egress redaction path today).
 *
 * <p><strong>Boundary (AC9).</strong> Depends only on the {@link CredentialCipher} <em>port</em>
 * (in {@code application.security}, same layer) and the application-owned {@link
 * ProjectCredentialRecordPort} SPI — never on {@code infrastructure.crypto} or {@code
 * adapters.persistence}. Spring injects {@code EnvelopeCredentialCipher} where the port is
 * required.
 *
 * <p><strong>Secret-hostility.</strong> The same bar as 3c-4's cipher: lifecycle logs carry only
 * the {@code projectPublicId}, the {@code role.value()}, and the new {@code cred_} id — never the
 * plaintext, ciphertext, or {@code keyId} payload. A {@code CredentialCipherException} from decrypt
 * propagates unchecked; it is never caught-and-logged with the ciphertext.
 */
@Service
public class ProjectCredentialService implements ProjectCredentialSource {

  private static final Logger log = LoggerFactory.getLogger(ProjectCredentialService.class);

  private final ProjectCredentialRecordPort recordPort;
  private final CredentialCipher cipher;

  public ProjectCredentialService(ProjectCredentialRecordPort recordPort, CredentialCipher cipher) {
    this.recordPort = Objects.requireNonNull(recordPort, "recordPort");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
  }

  /**
   * Encrypt {@code plaintext} and persist it as the new active credential for {@code
   * (projectPublicId, role)}, archiving any current active row in the <strong>same
   * transaction</strong> (AC6 — replace-on-reset / rotation, enabled by the V17 partial unique
   * index). Returns the non-secret {@code cred_} public id of the inserted row.
   */
  @Transactional
  public String setCredential(String projectPublicId, ConnectorRole role, String plaintext) {
    if (projectPublicId == null || projectPublicId.isBlank()) {
      throw new IllegalArgumentException("projectPublicId must be non-blank");
    }
    Objects.requireNonNull(role, "role");
    if (plaintext == null || plaintext.isBlank()) {
      throw new IllegalArgumentException("credential plaintext must be non-blank");
    }
    log.info("setCredential projectId={} role={}", projectPublicId, role.value());

    EncryptedSecret encrypted = cipher.encrypt(plaintext);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    recordPort.archiveActive(projectPublicId, role, now);
    ProjectCredential inserted =
        recordPort.insert(
            new ProjectCredential(
                PublicIdPrefixes.PROJECT_CREDENTIAL.next(),
                projectPublicId,
                role,
                encrypted.ciphertext(),
                encrypted.keyId(),
                encrypted.algo(),
                now,
                null));
    log.info(
        "setCredential stored projectId={} role={} credentialId={}",
        projectPublicId,
        role.value(),
        inserted.publicId());
    return inserted.publicId();
  }

  /**
   * Resolve the active credential for {@code (projectPublicId, role)} and return its decrypted
   * plaintext <strong>for immediate in-memory use only</strong>, or {@link Optional#empty()} when
   * none is configured (not an error). The plaintext is never logged or retained.
   */
  public Optional<String> getDecrypted(String projectPublicId, ConnectorRole role) {
    if (projectPublicId == null || projectPublicId.isBlank()) {
      throw new IllegalArgumentException("projectPublicId must be non-blank");
    }
    Objects.requireNonNull(role, "role");

    Optional<ProjectCredential> active = recordPort.findActive(projectPublicId, role);
    if (active.isEmpty()) {
      log.debug("getDecrypted projectId={} role={} present=false", projectPublicId, role.value());
      return Optional.empty();
    }
    ProjectCredential row = active.get();
    // The decrypted value lives only on this stack frame. A CredentialCipherException (tamper /
    // wrong key / bad algo) propagates unchecked — never caught-and-logged with the ciphertext; we
    // log only the non-secret cred_ public id as an operational breadcrumb before rethrowing, so a
    // tampered / rotated row can be traced without exposing any secret material.
    String plaintext;
    try {
      plaintext = cipher.decrypt(row.ciphertext(), row.keyId(), row.algo());
    } catch (CredentialCipherException e) {
      log.warn(
          "getDecrypted decrypt failed projectId={} role={} credentialId={}",
          projectPublicId,
          role.value(),
          row.publicId());
      throw e;
    }
    log.debug("getDecrypted projectId={} role={} present=true", projectPublicId, role.value());
    return Optional.of(plaintext);
  }

  /**
   * Story 3c-3 {@link ProjectCredentialSource} seam — made live here. Parses the raw {@code role}
   * string (the underscored DB form, R1) to {@link ConnectorRole} with fail-fast registry parsing,
   * then delegates to {@link #getDecrypted}.
   */
  @Override
  public Optional<String> resolveSecret(Project project, String role) {
    Objects.requireNonNull(project, "project");
    ConnectorRole connectorRole =
        ConnectorRole.fromValue(role, "project_credentials.connector_role");
    return getDecrypted(project.publicId(), connectorRole);
  }
}
