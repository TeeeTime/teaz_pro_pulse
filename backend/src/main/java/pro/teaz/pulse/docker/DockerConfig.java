package pro.teaz.pulse.docker;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import pro.teaz.pulse.PulseProperties;

/**
 * Picks the Docker client: the real engine when {@code pulse.docker.url} is
 * set (prod), otherwise the mock. Plain {@code ./mvnw spring-boot:run}
 * therefore needs no Docker, like the frontend's mock auth.
 */
@Configuration
public class DockerConfig {

	@Bean
	DockerClient dockerClient(PulseProperties properties) {
		PulseProperties.Docker docker = properties.docker();
		if (docker == null || docker.url() == null || docker.url().isBlank()) {
			return new MockDockerClient();
		}

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		// Stop waits up to DockerEngineClient.STOP_TIMEOUT for the container, plus headroom.
		requestFactory.setReadTimeout(Duration.ofSeconds(30));

		RestClient restClient = RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(docker.url() + "/" + docker.apiVersion())
				.build();
		return new DockerEngineClient(restClient);
	}
}
