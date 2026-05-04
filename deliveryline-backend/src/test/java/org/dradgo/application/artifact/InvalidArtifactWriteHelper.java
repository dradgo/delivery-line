package org.dradgo.application.artifact;

public final class InvalidArtifactWriteHelper {

	void writeArtifact() {
		new LocalArtifactStore().write("artifact");
	}

	static final class LocalArtifactStore {

		void write(String artifact) {
		}
	}
}
