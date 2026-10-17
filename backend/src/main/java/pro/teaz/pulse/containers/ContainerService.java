package pro.teaz.pulse.containers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.pulse.docker.DockerClient;
import pro.teaz.pulse.docker.DockerContainer;

@Service
public class ContainerService {

	private static final Comparator<DockerContainer> ORDER = Comparator
			.comparing(DockerContainer::app, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(DockerContainer::service, String.CASE_INSENSITIVE_ORDER);

	private final DockerClient docker;

	public ContainerService(DockerClient docker) {
		this.docker = docker;
	}

	public List<ContainerView> containers() {
		return managed().stream().map(ContainerView::of).toList();
	}

	public HealthSummary health() {
		Map<String, List<DockerContainer>> byApp = new LinkedHashMap<>();
		for (DockerContainer c : managed()) {
			byApp.computeIfAbsent(c.app(), app -> new ArrayList<>()).add(c);
		}

		List<HealthSummary.AppHealth> apps = byApp.entrySet().stream()
				.map(entry -> new HealthSummary.AppHealth(
						entry.getKey(),
						entry.getValue().stream().map(Status::of).reduce(Status.GOOD, Status::worse),
						(int) entry.getValue().stream().filter(DockerContainer::running).count(),
						entry.getValue().size()))
				.toList();
		Status overall = apps.stream().map(HealthSummary.AppHealth::status).reduce(Status.GOOD, Status::worse);
		return new HealthSummary(overall, Instant.now(), apps);
	}

	/**
	 * Only acts on containers Pulse manages (the socket proxy would allow any
	 * container), and never stops a protected one: stopping Traefik, Keycloak
	 * or Pulse itself would leave no way back in through Pulse.
	 */
	public void perform(String id, ContainerAction action) {
		DockerContainer container = managed().stream()
				.filter(c -> c.id().equals(id))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not a managed container: " + id));

		switch (action) {
			case START -> docker.start(id);
			case RESTART -> docker.restart(id);
			case STOP -> {
				if (container.protectedContainer()) {
					throw new ResponseStatusException(HttpStatus.CONFLICT,
							container.name() + " is protected and can only be restarted, not stopped.");
				}
				docker.stop(id);
			}
		}
	}

	private List<DockerContainer> managed() {
		return docker.listManagedContainers().stream().sorted(ORDER).toList();
	}
}
