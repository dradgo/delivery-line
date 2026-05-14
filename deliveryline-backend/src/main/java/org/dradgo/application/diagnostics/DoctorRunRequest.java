package org.dradgo.application.diagnostics;

import java.util.LinkedHashSet;
import java.util.Set;

public record DoctorRunRequest(
	Set<String> only,
	Set<String> exclude,
	String correlationId
) {

	public DoctorRunRequest {
		only = only == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(only));
		exclude = exclude == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(exclude));
	}

	public static DoctorRunRequest all() {
		return new DoctorRunRequest(Set.of(), Set.of(), null);
	}
}
