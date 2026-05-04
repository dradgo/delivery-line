package org.dradgo.architecture;

final class InvalidArtifactWriteAdapter {

	void writeArtifact() {
		new LocalArtifactStore().write("artifact");
	}

	static final class LocalArtifactStore {

		void write(String artifact) {
		}
	}
}
