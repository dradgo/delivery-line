package org.dradgo.adapters.diagnostics;

import java.io.IOException;

@FunctionalInterface
interface ProcessLauncher {
	Process launch(ProcessBuilder builder) throws IOException;
}
