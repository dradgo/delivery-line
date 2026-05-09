package org.dradgo.application.artifact.spi;

import org.dradgo.application.artifact.ArtifactEventRecord;

public interface ArtifactEventPort {

	void append(ArtifactEventRecord eventRecord);
}
