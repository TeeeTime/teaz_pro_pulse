package pro.teaz.pulse.deploy;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import pro.teaz.pulse.PulseProperties;

/**
 * Picks the deployer: the sidecar when {@code pulse.deployer.url} is set
 * (prod), otherwise the mock, like {@code DockerConfig}.
 */
@Configuration
public class DeployConfig {

	@Bean
	Deployer deployer(PulseProperties properties) {
		PulseProperties.Deployer deployer = properties.deployer();
		if (deployer == null || deployer.url() == null || deployer.url().isBlank()) {
			return new MockDeployer();
		}

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		// Starting only asks Docker about the project; the deploy itself runs in the background.
		requestFactory.setReadTimeout(Duration.ofSeconds(30));

		return new DeployerClient(RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(deployer.url())
				.build());
	}
}
