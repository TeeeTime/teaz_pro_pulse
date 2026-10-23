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
 * visibly change their state, with made-up but plausible resource usage. Used
 * whenever {@code pulse.docker.url} is unset.
 */
public class MockDockerClient implements DockerClient {

	/** Host size the made-up usage is measured against, the same as MockHostProbe's. */
	public static final int CORES = 4;
	public static final long MEMORY = 8L << 30;

	private final Map<String, DockerContainer> containers = new ConcurrentHashMap<>();
	private final Instant created = Instant.now();

	public MockDockerClient() {
		Instant now = Instant.now();
		add("traefik", "Traefik", "traefik", "traefik:v3.4", "running", null, now.minus(Duration.ofDays(12)), 0, true);
		add("keycloak", "Keycloak", "keycloak", "quay.io/keycloak/keycloak:26.2", "running", "healthy", now.minus(Duration.ofDays(12)), 0, true);
		add("keycloak-db", "Keycloak", "postgres", "postgres:17", "running", "healthy", now.minus(Duration.ofDays(12)), 0, true);
		add("portal-frontend", "Portal", "frontend", "teaz_pro_portal-frontend", "running", null, now.minus(Duration.ofHours(5)), 0, false);
		add("portal-backend", "Portal", "backend", "teaz_pro_portal-backend", "running", "unhealthy", now.minus(Duration.ofHours(5)), 3, false);
		add("pulse-frontend", "Pulse", "frontend", "teaz_pro_pulse-frontend", "running", null, now.minus(Duration.ofMinutes(42)), 0, true);
		add("pulse-backend", "Pulse", "backend", "teaz_pro_pulse-backend", "running", null, now.minus(Duration.ofMinutes(42)), 0, true);
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

	/**
	 * Counters that grow at a rate that drifts slowly per container (a few
	 * percent of the host's CPU, a few hundred MB), so the charts move.
	 */
	@Override
	public ContainerStats stats(String id) {
		DockerContainer c = get(id);
		double seconds = Duration.between(created, Instant.now()).toMillis() / 1000.0;
		int seed = Math.floorMod(id.hashCode(), 97);
		// Share of the whole host, integrated over time so two readings give that rate.
		double share = 0.01 + 0.03 * (seed % 5) / 4.0;
		// Never below 0 and always growing: the rate swings by a quarter around the share.
		double drift = 30 * (1 + Math.sin(seconds / 120 + seed));
		double cpuSeconds = CORES * share * (seconds + drift);
		long system = (long) (CORES * seconds * 1e9);
		long memory = (long) ((120 + 60 * (seed % 7) + 20 * Math.sin(seconds / 300 + seed)) * (1L << 20));
		long io = (long) (seconds * (2_000 + 500 * (seed % 4)));
		return new ContainerStats((long) (cpuSeconds * 1e9), system, c.running() ? memory : 0, MEMORY,
				io * 3, io, io / 2, io / 4);
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
