package org.dradgo.application.integration.linear;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound configuration for the Story 3a.5 opt-in Linear auto-ingest path.
 *
 * <p>When {@code enabled} is {@code true}, {@code LinearPollingHost} stops being a pure watcher and
 * auto-creates a governed run (via the same {@code WorkflowCommandService.submit} the CLI/REST use)
 * for each polled ticket whose Linear issue workflow-state id ({@code issue.state.id}) is in {@code
 * statusIds} <em>and</em> that has no existing active integration link. The default ({@code
 * enabled=false}, {@code statusIds=[]}) keeps the poll byte-identical to the Epic-1 watcher.
 *
 * <p>{@code statusIds} is a list of Linear issue workflow-state <strong>UUIDs</strong> (configured
 * under Team → Settings → Workflow, obtained via the {@code workflowStates} GraphQL query). Gating
 * is on the id (not the name) so a Linear-side rename does not silently break ingestion. An enabled
 * flag with an <strong>empty</strong> {@code statusIds} list ingests NOTHING (fail-safe — never the
 * whole workspace). Ids are per-team; when polling more than one team, list every relevant id.
 *
 * <p>Lives in {@code application.integration.linear} (not {@code infrastructure.config}) so the
 * "application must not depend on infrastructure" ArchUnit rule stays clean — mirrors {@link
 * LinearProperties}. Deliberately <strong>unvalidated</strong>: absence is the valid default, so no
 * {@code @SpringBootTest} context fails on a missing property and the test {@code application.yml}
 * needs no mirroring entry. The compact constructor normalizes {@code statusIds} to an immutable
 * list with blanks stripped/dropped (a stray {@code " uuid "} from a quoted YAML/env entry is not
 * matched as {@code " uuid "} against {@code issue.state.id}).
 */
@ConfigurationProperties("deliveryline.linear.auto-ingest")
public record LinearAutoIngestProperties(boolean enabled, List<String> statusIds) {

  public LinearAutoIngestProperties {
    statusIds =
        statusIds == null
            ? List.of()
            : statusIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::strip)
                .toList();
  }

  public static LinearAutoIngestProperties defaults() {
    return new LinearAutoIngestProperties(false, List.of());
  }
}
