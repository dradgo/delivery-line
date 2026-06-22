package org.dradgo.adapters.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalRunnerScratchStoreTest {

  @TempDir Path tempHome;

  @Test
  void deleteContextBundleRemovesPreviouslyWrittenBundle() {
    LocalRunnerScratchStore store =
        new LocalRunnerScratchStore(tempHome.toAbsolutePath().toString());
    String runnerExecutionId = "rex_scratchdelete01";
    Path bundle = store.writeContextBundle(runnerExecutionId, "{}".getBytes());

    store.deleteContextBundle(runnerExecutionId);

    assertThat(bundle).doesNotExist();
  }
}
