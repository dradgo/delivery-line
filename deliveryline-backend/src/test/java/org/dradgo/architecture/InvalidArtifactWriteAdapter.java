package org.dradgo.architecture;

import org.dradgo.adapters.files.LocalArtifactStore;

final class InvalidArtifactWriteAdapter {

	void writeArtifact() {
		new LocalArtifactStore(".").write("run_ready1234", "art_ready1234", 1, "artifact.md", new byte[0]);
	}
}
