package org.dradgo.application.integration.ticketsource;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Constructor-bound selector for the active ticket-source kind (story 3.32 AC5 / OQ-2).
 *
 * <p>{@code kind} names the vendor implementation that backs the {@link TicketSourceAdapter} port —
 * {@code linear} today; future kinds ({@code jira}, {@code github-issues}, {@code gitlab-issues})
 * plug in by adding a new implementation under {@code adapters.integration.ticketsource.{kind}}
 * plus a profile entry. The {@code kind} key is the <em>documented</em> selector; the load-bearing
 * bean gating remains the existing mutually-exclusive Spring profiles ({@code linear-mock} / {@code
 * linear-real}) — {@code LinearConfiguration} fail-fasts at boot when {@code kind} names a vendor
 * with no implementation on the classpath (today, anything other than {@code linear}). It does
 * <em>not</em> cross-check {@code kind} against the active mock/real profile. Renaming the existing
 * {@code deliveryline.linear.*} config keys to {@code
 * deliveryline.integration.ticket-source.linear.*} is an ops-breaking change intentionally left out
 * of scope (recorded as a future cosmetic migration in ADR 0007).
 *
 * <p>Deliberately a thin, normalized record: {@code kind} defaults to {@code "linear"} when absent
 * or blank, and is lower-cased/stripped so a stray {@code " Linear "} from a quoted YAML/env entry
 * still matches. The default keeps every {@code @SpringBootTest} context green without a test-yaml
 * mirror entry.
 */
@ConfigurationProperties("deliveryline.integration.ticket-source")
public record TicketSourceProperties(String kind) {

  /** The only kind shipped today; the validated default. */
  public static final String KIND_LINEAR = "linear";

  public TicketSourceProperties {
    kind = (kind == null || kind.isBlank()) ? KIND_LINEAR : kind.strip().toLowerCase(Locale.ROOT);
  }

  public static TicketSourceProperties defaults() {
    return new TicketSourceProperties(KIND_LINEAR);
  }

  public boolean isLinear() {
    return KIND_LINEAR.equals(kind);
  }
}
