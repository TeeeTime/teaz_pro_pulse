package pro.teaz.pulse.deploy;

import java.time.Instant;

/**
 * One deploy of a compose project ({@code git pull --ff-only}, then
 * {@code docker compose up -d --build}), as the deployer reports it.
 *
 * @param state     running, succeeded or failed
 * @param startedBy who clicked Deploy (Keycloak user name)
 * @param exitCode  of the command that ended it; null while running, -1 when it never ran or timed out
 * @param log       both commands' output, the last 200,000 characters
 */
public record DeployJob(
		String project,
		String state,
		String startedBy,
		Instant startedAt,
		Instant finishedAt,
		Integer exitCode,
		String log) {
}
