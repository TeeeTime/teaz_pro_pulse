package pro.teaz.pulse.containers;

import com.fasterxml.jackson.annotation.JsonValue;

import pro.teaz.pulse.docker.DockerContainer;

/**
 * Overall verdict for a container, an app or everything. Serialized as
 * good/warning/critical, the same states as teaz_pro_ui's StatusRow, so a
 * Portal tile can show {@code /api/health} without mapping.
 */
public enum Status {
	GOOD, WARNING, CRITICAL;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}

	public Status worse(Status other) {
		return other.ordinal() > ordinal() ? other : this;
	}

	/**
	 * Running and healthy (or without healthcheck) = good. Running but still
	 * starting, restarting or paused = warning. Unhealthy or not running at
	 * all = critical; that includes containers stopped on purpose.
	 */
	public static Status of(DockerContainer container) {
		return switch (container.state()) {
			case "running" -> switch (container.health() == null ? "none" : container.health()) {
				case "unhealthy" -> CRITICAL;
				case "starting" -> WARNING;
				default -> GOOD;
			};
			case "restarting", "paused" -> WARNING;
			default -> CRITICAL;
		};
	}
}
