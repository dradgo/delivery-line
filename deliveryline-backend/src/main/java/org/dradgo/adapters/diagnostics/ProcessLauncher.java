package org.dradgo.adapters.diagnostics;

import java.io.IOException;

@FunctionalInterface
public interface ProcessLauncher {
  Process launch(ProcessBuilder builder) throws IOException;
}
