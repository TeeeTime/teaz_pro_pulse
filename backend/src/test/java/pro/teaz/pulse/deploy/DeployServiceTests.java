package pro.teaz.pulse.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.pulse.docker.DockerClient;
import pro.teaz.pulse.docker.DockerContainer;

class DeployServiceTests {

	private final DockerClient docker = mock(DockerClient.class);
	private final Deployer deployer = mock(Deployer.class);
	private final DeployService service = new DeployService(docker, deployer);

	@BeforeEach
	void containers() {
		when(docker.listManagedContainers()).thenReturn(List.of(
				container("portal-front", "teaz_pro_portal", false),
				container("portal-back", "teaz_pro_portal", false),
				container("pulse-back", "teaz_pro_pulse", true),
				container("traefik", null, true)));
	}

	@Test
	void deploysManagedProject() {
		DeployJob job = new DeployJob("teaz_pro_portal", "running", "tom", null, null, null, "");
		when(deployer.start("teaz_pro_portal", "tom")).thenReturn(job);

		assertThat(service.deploy("teaz_pro_portal", "tom")).isEqualTo(job);
	}

	@Test
	void refusesProtectedProject() {
		assertThatThrownBy(() -> service.deploy("teaz_pro_pulse", "tom"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		verify(deployer, never()).start(anyString(), anyString());
	}

	@Test
	void refusesUnknownProject() {
		assertThatThrownBy(() -> service.deploy("other", "tom"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		verify(deployer, never()).start(anyString(), anyString());
	}

	@Test
	void refusesInvalidName() {
		assertThatThrownBy(() -> service.deploy("../etc", "tom"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> service.job("Portal"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void mockDeployerPlaysOneDeployAtATime() throws InterruptedException {
		MockDeployer mock = new MockDeployer(1);
		mock.start("teaz_pro_portal", "tom");
		assertThatThrownBy(() -> mock.start("teaz_pro_demo", "tom"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

		for (int i = 0; i < 200 && "running".equals(mock.job("teaz_pro_portal").orElseThrow().state()); i++) {
			Thread.sleep(10);
		}
		DeployJob done = mock.job("teaz_pro_portal").orElseThrow();
		assertThat(done.state()).isEqualTo("succeeded");
		assertThat(done.exitCode()).isZero();
		assertThat(done.log()).startsWith("$ git pull --ff-only\n").contains("up -d --build");
	}

	private static DockerContainer container(String id, String project, boolean protectedContainer) {
		return new DockerContainer(id, id, "App", "svc", project, "img", "running", null, null, null, 0,
				protectedContainer);
	}
}
