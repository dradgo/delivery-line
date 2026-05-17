package org.dradgo.application.artifact;

import org.dradgo.adapters.files.LocalArtifactStore;

public final class InvalidArtifactWriteHelper {

  void writeArtifact() {
    new LocalArtifactStore(".")
        .write("run_ready1234", "art_ready1234", 1, "artifact.md", new byte[0]);
  }
}
