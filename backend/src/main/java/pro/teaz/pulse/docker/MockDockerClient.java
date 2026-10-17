package pro.teaz.pulse.docker;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local-dev stand-in for {@link DockerEngineClient}: a fixed set of fake
 * containers resembling the VPS, kept in memory so start/stop/restart
 * visibly change their state. Used whenever {@code pulse.docker.url} is unset.
 */
public class MockDockerClient implements DockerClient {

	private final Map<String, DockerContainer> containers = new ConcurrentHashMap<>();

	public MockDockerClient() {
		Instant now = Instant.now();
		add("traefik", "Traefik", "traefik", "traefik:v3.4", "running", null, now.minus(Duration.ofDays(12)), 0, true);
		add("keycloak", "Keycloak", "keycloak", "quay.io/keycloak/keycloak:26.2", "running", "healthy", now.minus(Duration.ofDays(12)), 0, true);
		add("keycloak-db", "Keycloak", "postgres", "postgres:17", "running", "healthy", now.minus(Duration.ofDays(12)), 0, true);
		add("portal-frontend", "Teaz Portal", "frontend", "teaz_pro_portal-frontend", "running", null, now.minus(Duration.ofHours(5)), 0, false);
		add("portal-backend", "Teaz Portal", "backend", "teaz_pro_portal-backend", "running", "unhealthy", now.minus(Duration.ofHours(5)), 3, false);
		add("pulse-frontend", "Teaz Pulse", "frontend", "teaz_pro_pulse-frontend", "running", null, now.minus(Duration.ofMinutes(42)), 0, true);
		add("pulse-backend", "Teaz Pulse", "backend", "teaz_pro_pulse-backend", "running", null, now.minus(Duration.ofMinutes(42)), 0, true);
		add("demo-worker", "Demo", "worker", "teaz_pro_demo-worker", "exited", null, null, 1, false);
	}

	private void add(String name, String app, String service, String image, String state, String health,
			Instant startedAt, int restartCount, boolean protectedContainer) {
		String id = "mock-" + name;
		containers.put(id, new DockerContainer(id, name, app, service, image, state, health, startedAt,
				state.equals("running") ? null : Instant.now().minus(Duration.ofHours(2)), restartCount,
				protectedContainer));
	}

	@Override
	public List<DockerContainer> listManagedContainers() {
		return new ArrayList<>(containers.values());
	}

	@Override
	public void start(String id) {
		update(id, "running", Instant.now(), null);
	}

	@Override
	public void stop(String id) {
		DockerContainer current = get(id);
		update(id, "exited", current.startedAt(), Instant.now());
	}

	@Override
	public void restart(String id) {
		update(id, "running", Instant.now(), Instant.now());
	}

	private void update(String id, String state, Instant startedAt, Instant finishedAt) {
		DockerContainer c = get(id);
		// A fresh start clears a failing healthcheck, which is what a restart is usually for.
		String health = c.health() == null ? null : "healthy";
		containers.put(id, new DockerContainer(c.id(), c.name(), c.app(), c.service(), c.image(), state, health,
				startedAt, finishedAt, c.restartCount(), c.protectedContainer()));
	}

	private DockerContainer get(String id) {
		DockerContainer c = containers.get(id);
		if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such container: " + id);
		return c;
	}
}
