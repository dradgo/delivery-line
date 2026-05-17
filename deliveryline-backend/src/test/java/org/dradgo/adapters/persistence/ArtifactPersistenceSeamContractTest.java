package org.dradgo.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Entity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class ArtifactPersistenceSeamContractTest {

  @Test
  void artifactPersistenceBuildingBlocksExistInTheExpectedPackages() throws Exception {
    Map<String, Class<?>> requiredTypes =
        Map.of(
            "artifact entity",
                Class.forName("org.dradgo.adapters.persistence.entity.ArtifactEntity"),
            "artifact operation entity",
                Class.forName("org.dradgo.adapters.persistence.entity.ArtifactOperationEntity"),
            "artifact repository",
                Class.forName("org.dradgo.adapters.persistence.repository.ArtifactRepository"),
            "artifact operation repository",
                Class.forName(
                    "org.dradgo.adapters.persistence.repository.ArtifactOperationRepository"),
            "artifact entity mapper",
                Class.forName("org.dradgo.adapters.persistence.mapper.ArtifactEntityMapper"),
            "artifact operation entity mapper",
                Class.forName(
                    "org.dradgo.adapters.persistence.mapper.ArtifactOperationEntityMapper"),
            "artifact record persistence adapter",
                Class.forName("org.dradgo.adapters.persistence.ArtifactRecordPersistenceAdapter"),
            "artifact operation persistence adapter",
                Class.forName(
                    "org.dradgo.adapters.persistence.ArtifactOperationPersistenceAdapter"),
            "artifact workflow event persistence adapter",
                Class.forName("org.dradgo.adapters.persistence.ArtifactEventPersistenceAdapter"),
            "local artifact store", Class.forName("org.dradgo.adapters.files.LocalArtifactStore"));

    assertTrue(requiredTypes.get("artifact entity").isAnnotationPresent(Entity.class));
    assertTrue(requiredTypes.get("artifact operation entity").isAnnotationPresent(Entity.class));
    assertTrue(requiredTypes.get("artifact entity mapper").isAnnotationPresent(Component.class));
    assertTrue(
        requiredTypes.get("artifact operation entity mapper").isAnnotationPresent(Component.class));
    assertTrue(
        Class.forName("org.dradgo.application.artifact.spi.ArtifactRecordPort")
            .isAssignableFrom(requiredTypes.get("artifact record persistence adapter")));
    assertTrue(
        Class.forName("org.dradgo.application.artifact.spi.ArtifactOperationPort")
            .isAssignableFrom(requiredTypes.get("artifact operation persistence adapter")));
    assertTrue(
        Class.forName("org.dradgo.application.artifact.spi.ArtifactEventPort")
            .isAssignableFrom(requiredTypes.get("artifact workflow event persistence adapter")));
    assertTrue(
        Class.forName("org.dradgo.application.artifact.spi.ArtifactPayloadStore")
            .isAssignableFrom(requiredTypes.get("local artifact store")));
  }
}
