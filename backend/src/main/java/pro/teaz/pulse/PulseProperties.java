package pro.teaz.pulse;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code pulse.*}, see application.properties.
 *
 * @param docker how to reach the Docker Engine API
 * @param cors origins (other Teaz apps) allowed to read {@code /api/health}
 */
@ConfigurationProperties("pulse")
public record PulseProperties(Docker docker, @DefaultValue Cors cors) {

	/**
	 * @param url        Docker Engine API base URL (the socket proxy); unset = mock data
	 * @param apiVersion API version path prefix, e.g. {@code v1.44}
	 */
	public record Docker(String url, @DefaultValue("v1.44") String apiVersion) {
	}

	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}
}
