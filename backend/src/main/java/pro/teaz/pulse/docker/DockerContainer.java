package pro.teaz.pulse.docker;

import java.time.Instant;

/**
 * One container as Pulse sees it, already reduced from Docker's inspect data.
 *
 * @param app        value of the {@value #APP_LABEL} label: the app it belongs to
 * @param service    compose service name, or the container name outside compose
 * @param state      Docker state: created, running, paused, restarting, exited, dead
 * @param health     Docker healthcheck status (starting, healthy, unhealthy), null without healthcheck
 * @param startedAt  last start, null if never started
 * @param finishedAt last stop, null if never stopped
 * @param protectedContainer {@value #PROTECTED_LABEL}=true: must not be stopped from Pulse
 */
public record DockerContainer(
		String id,
		String name,
		String app,
		String service,
		String image,
		String state,
		String health,
		Instant startedAt,
		Instant finishedAt,
		int restartCount,
		boolean protectedContainer) {

	/** Opt-in label: only containers carrying it are managed by Pulse. Its value is the app's display name. */
	public static final String APP_LABEL = "pro.teaz.pulse.app";

	/** Containers Pulse depends on (Traefik, Keycloak, Postgres, Pulse itself): restart only, never stop. */
	public static final String PROTECTED_LABEL = "pro.teaz.pulse.protected";

	public boolean running() {
		return "running".equals(state);
	}
}
