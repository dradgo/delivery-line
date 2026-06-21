package org.dradgo.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import org.dradgo.application.security.CredentialCipher;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.project.ProjectCredential;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ConnectorRole;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.ProjectStatus;
import org.dradgo.infrastructure.crypto.CryptoProperties;
import org.dradgo.infrastructure.crypto.EnvelopeCredentialCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Story 3c-5 — unit coverage of the write-only credential store. The {@link
 * ProjectCredentialRecordPort} is mocked; the {@link CredentialCipher} is the real {@link
 * EnvelopeCredentialCipher} under a deterministic test master key so set&rarr;getDecrypted truly
 * round-trips through genuine AES-256-GCM ciphertext.
 */
class ProjectCredentialServiceTest {

  private static final String PROJECT_ID = "prj_unitstore01";
  private static final String SECRET = "lin_api_super-secret-value-DO-NOT-LOG";

  private final ProjectCredentialRecordPort recordPort = mock(ProjectCredentialRecordPort.class);
  private final CredentialCipher cipher =
      new EnvelopeCredentialCipher(
          new CryptoProperties(Base64.getEncoder().encodeToString(new byte[32])));
  private final ProjectCredentialService service = new ProjectCredentialService(recordPort, cipher);

  @Test
  void setThenGetDecryptedRoundTripsThroughTheStore() {
    when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

    String credentialId = service.setCredential(PROJECT_ID, ConnectorRole.TICKET_SOURCE, SECRET);
    assertThat(credentialId).startsWith(PublicIdPrefixes.PROJECT_CREDENTIAL.prefix());

    ProjectCredential stored = captureInserted();
    when(recordPort.findActive(PROJECT_ID, ConnectorRole.TICKET_SOURCE))
        .thenReturn(Optional.of(stored));

    assertThat(service.getDecrypted(PROJECT_ID, ConnectorRole.TICKET_SOURCE)).contains(SECRET);
  }

  @Test
  void reviewerRoleCredentialRoundTripsThroughTheExistingStoreEncryptedAtRest() {
    // Story 3d-1 (AC8) — the `reviewer` connector role is a FIRST-CLASS credential role with NO new
    // subsystem: a reviewer credential stores via the existing 3c-5 ProjectCredentialService and the
    // 3c-4 AES-256-GCM cipher unchanged, persists ciphertext (never plaintext) at rest, and decrypts
    // back to the original secret for immediate in-memory use.
    when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

    String credentialId = service.setCredential(PROJECT_ID, ConnectorRole.REVIEWER, SECRET);
    assertThat(credentialId).startsWith(PublicIdPrefixes.PROJECT_CREDENTIAL.prefix());

    ProjectCredential stored = captureInserted();
    assertThat(stored.role()).isEqualTo(ConnectorRole.REVIEWER);
    assertThat(stored.algo()).isEqualTo(EnvelopeCredentialCipher.ALGORITHM);
    // Encrypted at rest: the persisted ciphertext never contains the plaintext.
    assertThat(new String(stored.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(SECRET);

    when(recordPort.findActive(PROJECT_ID, ConnectorRole.REVIEWER)).thenReturn(Optional.of(stored));
    assertThat(service.getDecrypted(PROJECT_ID, ConnectorRole.REVIEWER)).contains(SECRET);
  }

  @Test
  void setCredentialArchivesActiveBeforeInserting() {
    when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.setCredential(PROJECT_ID, ConnectorRole.REPO_HOST, SECRET);

    InOrder inOrder = Mockito.inOrder(recordPort);
    inOrder.verify(recordPort).archiveActive(eq(PROJECT_ID), eq(ConnectorRole.REPO_HOST), any());
    inOrder.verify(recordPort).insert(any());
  }

  @Test
  void setCredentialPersistsCipherTextNotPlaintext() {
    when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.setCredential(PROJECT_ID, ConnectorRole.TICKET_SOURCE, SECRET);

    ProjectCredential stored = captureInserted();
    assertThat(stored.algo()).isEqualTo(EnvelopeCredentialCipher.ALGORITHM);
    assertThat(stored.keyId()).startsWith("mk_");
    assertThat(new String(stored.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(SECRET);
  }

  @Test
  void replaceOnResetArchivesEachTime() {
    when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.setCredential(PROJECT_ID, ConnectorRole.TICKET_SOURCE, "first-secret-value");
    service.setCredential(PROJECT_ID, ConnectorRole.TICKET_SOURCE, "second-secret-value");

    verify(recordPort, Mockito.times(2))
        .archiveActive(eq(PROJECT_ID), eq(ConnectorRole.TICKET_SOURCE), any());
    verify(recordPort, Mockito.times(2)).insert(any());
  }

  @Test
  void getDecryptedReturnsEmptyWhenNoActiveCredential() {
    when(recordPort.findActive(PROJECT_ID, ConnectorRole.REPO_HOST)).thenReturn(Optional.empty());

    assertThat(service.getDecrypted(PROJECT_ID, ConnectorRole.REPO_HOST)).isEmpty();
  }

  @Test
  void resolveSecretParsesRoleAndDelegatesToGetDecrypted() {
    when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service.setCredential(PROJECT_ID, ConnectorRole.TICKET_SOURCE, SECRET);
    ProjectCredential stored = captureInserted();
    when(recordPort.findActive(PROJECT_ID, ConnectorRole.TICKET_SOURCE))
        .thenReturn(Optional.of(stored));

    assertThat(service.resolveSecret(project(), "ticket_source")).contains(SECRET);
  }

  @Test
  void resolveSecretFailsFastOnUnknownRoleValue() {
    assertThatThrownBy(() -> service.resolveSecret(project(), "ticket-source"))
        .isInstanceOf(DomainException.class)
        .satisfies(
            error ->
                assertThat(((DomainException) error).errorCode())
                    .isEqualTo(DomainErrorCode.UNKNOWN_REGISTRY_VALUE));
  }

  @Test
  void blankInputsAreRejected() {
    assertThatThrownBy(() -> service.setCredential("  ", ConnectorRole.REPO_HOST, SECRET))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.setCredential(PROJECT_ID, ConnectorRole.REPO_HOST, "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.getDecrypted("", ConnectorRole.REPO_HOST))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void neverLogsThePlaintextAcrossASetGetCycle() {
    Logger logger = (Logger) LoggerFactory.getLogger(ProjectCredentialService.class);
    Level previous = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.setLevel(Level.TRACE);
    logger.addAppender(appender);
    try {
      when(recordPort.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
      service.setCredential(PROJECT_ID, ConnectorRole.TICKET_SOURCE, SECRET);
      ProjectCredential stored = captureInserted();
      when(recordPort.findActive(PROJECT_ID, ConnectorRole.TICKET_SOURCE))
          .thenReturn(Optional.of(stored));
      service.getDecrypted(PROJECT_ID, ConnectorRole.TICKET_SOURCE);

      // The operative lifecycle INFO line is emitted (projectId + role + credentialId only)...
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                    .contains("setCredential stored")
                    .contains(stored.publicId());
              });
      // ...and NOTHING logged at any level carries the plaintext or the keyId payload.
      assertThat(appender.list)
          .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(SECRET));
      assertThat(appender.list)
          .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(stored.keyId()));
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }
  }

  private ProjectCredential captureInserted() {
    ArgumentCaptor<ProjectCredential> captor = ArgumentCaptor.forClass(ProjectCredential.class);
    verify(recordPort, Mockito.atLeastOnce()).insert(captor.capture());
    return captor.getValue();
  }

  private static Project project() {
    return new Project(
        PROJECT_ID,
        "Unit Store",
        "unit-store",
        ProjectStatus.ACTIVE,
        null,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        OffsetDateTime.now(ZoneOffset.UTC),
        null);
  }
}
