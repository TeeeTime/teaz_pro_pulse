package pro.teaz.pulse.containers;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/health}: one line per app, for other apps (Portal's
 * status tile) to show. Deliberately no container IDs or images.
 *
 * @param status worst status of all apps
 */
public record HealthSummary(Status status, Instant checkedAt, List<AppHealth> apps) {

	/**
	 * @param status  worst status of the app's containers
	 * @param running containers currently running
	 * @param total   all containers of the app
	 */
	public record AppHealth(String name, Status status, int running, int total) {
	}
}
