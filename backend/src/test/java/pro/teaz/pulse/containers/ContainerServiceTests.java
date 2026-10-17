package pro.teaz.pulse.containers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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

class ContainerServiceTests {

	private final DockerClient docker = mock(DockerClient.class);
	private final ContainerService service = new ContainerService(docker);

	@BeforeEach
	void containers() {
		when(docker.listManagedContainers()).thenReturn(List.of(
				container("p-front", "Portal", "frontend", "running", null, false),
				container("p-back", "Portal", "backend", "running", "unhealthy", false),
				container("traefik", "Traefik", "traefik", "running", null, true),
				container("kc", "Keycloak", "keycloak", "running", "starting", true),
				container("worker", "Demo", "worker", "exited", null, false)));
	}

	@Test
	void statusPerContainerSortedByAppAndService() {
		assertThat(service.containers()).extracting(ContainerView::id, ContainerView::status).containsExactly(
				tuple("worker", Status.CRITICAL),
				tuple("kc", Status.WARNING),
				tuple("p-back", Status.CRITICAL),
				tuple("p-front", Status.GOOD),
				tuple("traefik", Status.GOOD));
	}

	@Test
	void healthTakesWorstStatusPerApp() {
		HealthSummary health = service.health();

		assertThat(health.status()).isEqualTo(Status.CRITICAL);
		assertThat(health.apps()).containsExactly(
				new HealthSummary.AppHealth("Demo", Status.CRITICAL, 0, 1),
				new HealthSummary.AppHealth("Keycloak", Status.WARNING, 1, 1),
				new HealthSummary.AppHealth("Portal", Status.CRITICAL, 2, 2),
				new HealthSummary.AppHealth("Traefik", Status.GOOD, 1, 1));
	}

	@Test
	void performsActionsOnManagedContainers() {
		service.perform("p-back", ContainerAction.RESTART);
		service.perform("worker", ContainerAction.START);
		service.perform("p-front", ContainerAction.STOP);

		verify(docker).restart("p-back");
		verify(docker).start("worker");
		verify(docker).stop("p-front");
	}

	@Test
	void protectedContainersCanRestartButNotStop() {
		service.perform("traefik", ContainerAction.RESTART);
		verify(docker).restart("traefik");

		assertThatThrownBy(() -> service.perform("traefik", ContainerAction.STOP))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
		verify(docker, never()).stop("traefik");
	}

	@Test
	void unmanagedContainersAreNotFound() {
		assertThatThrownBy(() -> service.perform("someone-elses", ContainerAction.STOP))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
		verify(docker, never()).stop("someone-elses");
	}

	private static DockerContainer container(String id, String app, String service, String state, String health,
			boolean protectedContainer) {
		return new DockerContainer(id, id, app, service, "img", state, health, null, null, 0, protectedContainer);
	}
}
