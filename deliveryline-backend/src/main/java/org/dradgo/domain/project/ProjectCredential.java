package org.dradgo.domain.project;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.ConnectorRole;

/**
 * Story 3c-5 (AC1/AC6) — domain record for one encrypted per-project connector credential, mapping
 * 1:1 to the V17 {@code project_credentials} columns. The {@code ciphertext} is the opaque envelope
 * frame produced by {@code CredentialCipher.encrypt(...)}; {@code keyId}/{@code algo} tie the row
 * to the master key + cipher-suite that wrapped it (rotation indirection).
 *
 * <p>This is a secret-bearing value: the {@code ciphertext} array is <strong>defensively
 * cloned</strong> on construction and on access (mirroring {@code EncryptedSecret}) so a shared
 * mutable array cannot corrupt a stored credential or leak a later mutation. {@code toString()} is
 * deliberately NOT overridden — the record default renders the {@code byte[]} as its identity hash,
 * never the bytes.
 *
 * <p>{@code archivedAt} is nullable: a null value marks the single active credential for a {@code
 * (projectPublicId, role)} (the V17 partial unique index enforces one active per pair); a non-null
 * value marks a rotated-out row.
 */
public record ProjectCredential(
    String publicId,
    String projectPublicId,
    ConnectorRole role,
    byte[] ciphertext,
    String keyId,
    String algo,
    OffsetDateTime createdAt,
    OffsetDateTime archivedAt) { // nullable

  public ProjectCredential {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.PROJECT_CREDENTIAL);
    Objects.requireNonNull(projectPublicId, "projectPublicId");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(ciphertext, "ciphertext");
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(algo, "algo");
    Objects.requireNonNull(createdAt, "createdAt");
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
