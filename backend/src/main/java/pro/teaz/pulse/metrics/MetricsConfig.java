package pro.teaz.pulse.metrics;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import pro.teaz.pulse.PulseProperties;

/**
 * The host probe goes with the Docker client (see DockerConfig): the real
 * server's /proc when Pulse talks to a real engine, a made-up server with
 * the mock containers otherwise.
 */
@Configuration
public class MetricsConfig {

	@Bean
	HostProbe hostProbe(PulseProperties properties) {
		PulseProperties.Docker docker = properties.docker();
		if (docker == null || docker.url() == null || docker.url().isBlank()) return new MockHostProbe();
		return new ProcHostProbe(Path.of("/proc"), Path.of("/"));
	}
}
