package pro.teaz.pulse;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code pulse.*}, see application.properties.
 *
 * @param docker how to reach the Docker Engine API
 * @param deployer how to reach the deployer sidecar (Deploy button)
 * @param cors origins (other Teaz apps) allowed to read {@code /api/health} and {@code /api/load}
 * @param metrics how often the server is measured and how much of it is kept
 */
@ConfigurationProperties("pulse")
public record PulseProperties(Docker docker, Deployer deployer, @DefaultValue Cors cors, @DefaultValue Metrics metrics) {

	/**
	 * @param url        Docker Engine API base URL (the socket proxy); unset = mock data
	 * @param apiVersion API version path prefix, e.g. {@code v1.44}
	 */
	public record Docker(String url, @DefaultValue("v1.44") String apiVersion) {
	}

	/**
	 * @param url deployer base URL (the sidecar in compose.yml); unset = MockDeployer
	 */
	public record Deployer(String url) {
	}

	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}

	/**
	 * @param interval time between two measurements
	 * @param history  how far back the charts go (kept in memory)
	 */
	public record Metrics(@DefaultValue("10s") Duration interval, @DefaultValue("1h") Duration history) {
	}
}
