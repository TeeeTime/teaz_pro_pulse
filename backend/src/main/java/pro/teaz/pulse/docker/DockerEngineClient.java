package pro.teaz.pulse.docker;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * Docker Engine API over HTTP. In prod that's the socket proxy (see
 * compose.yml), which only lets these exact calls through: list/inspect
 * containers, read one's stats, start/stop/restart one.
 */
public class DockerEngineClient implements DockerClient {

	/** Seconds Docker waits for a graceful stop before killing the container. */
	static final int STOP_TIMEOUT = 10;

	/** Docker's zero time, used for "never started/stopped". */
	private static final String ZERO_TIME = "0001-01-01T00:00:00Z";

	private final RestClient restClient;

	public DockerEngineClient(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public List<DockerContainer> listManagedContainers() {
		List<ListEntry> entries = restClient.get()
				.uri(uri -> uri.path("/containers/json")
						.queryParam("all", "true")
						.queryParam("filters", "{filters}")
						.build("{\"label\":[\"" + DockerContainer.APP_LABEL + "\"]}"))
				.retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
		if (entries == null) return List.of();

		// The list endpoint has no start time or health details, so inspect each one.
		return entries.stream().map(entry -> inspect(entry.id())).toList();
	}

	private DockerContainer inspect(String id) {
		Inspect inspect = restClient.get()
				.uri("/containers/{id}/json", id)
				.retrieve()
				.body(Inspect.class);

		Map<String, String> labels = inspect.config().labels() != null ? inspect.config().labels() : Map.of();
		String name = inspect.name().startsWith("/") ? inspect.name().substring(1) : inspect.name();
		State state = inspect.state();

		return new DockerContainer(
				inspect.id(),
				name,
				labels.get(DockerContainer.APP_LABEL),
				labels.getOrDefault("com.docker.compose.service", name),
				inspect.config().image(),
				state.status(),
				state.health() != null ? state.health().status() : null,
				parseTime(state.startedAt()),
				parseTime(state.finishedAt()),
				inspect.restartCount(),
				"true".equalsIgnoreCase(labels.get(DockerContainer.PROTECTED_LABEL)));
	}

	/**
	 * {@code one-shot}: one reading right away, instead of Docker sampling twice a
	 * second apart; Pulse compares readings of its own rounds.
	 */
	@Override
	public ContainerStats stats(String id) {
		Stats stats = restClient.get()
				.uri("/containers/{id}/stats?stream=false&one-shot=true", id)
				.retrieve()
				.body(Stats.class);

		Memory memory = stats.memory();
		Map<String, Long> memoryDetails = memory.stats() != null ? memory.stats() : Map.of();
		// cgroup v2 calls the droppable page cache inactive_file, v1 total_inactive_file; docker stats subtracts it.
		long cache = memoryDetails.getOrDefault("inactive_file", memoryDetails.getOrDefault("total_inactive_file", 0L));

		long rx = 0;
		long tx = 0;
		if (stats.networks() != null) {
			for (Network network : stats.networks().values()) {
				rx += network.rxBytes();
				tx += network.txBytes();
			}
		}
		long read = 0;
		long write = 0;
		if (stats.blkio() != null && stats.blkio().ioServiceBytes() != null) {
			for (BlkioEntry entry : stats.blkio().ioServiceBytes()) {
				// v1 writes "Read"/"Write", v2 "read"/"write".
				if ("read".equalsIgnoreCase(entry.op())) read += entry.value();
				else if ("write".equalsIgnoreCase(entry.op())) write += entry.value();
			}
		}

		return new ContainerStats(
				stats.cpu().usage().total(),
				stats.cpu().system(),
				Math.max(0, memory.usage() - cache),
				memory.limit(),
				rx,
				tx,
				read,
				write);
	}

	@Override
	public void start(String id) {
		post("/containers/{id}/start", id);
	}

	@Override
	public void stop(String id) {
		post("/containers/{id}/stop?t=" + STOP_TIMEOUT, id);
	}

	@Override
	public void restart(String id) {
		post("/containers/{id}/restart?t=" + STOP_TIMEOUT, id);
	}

	// 204 = done, 304 = already in that state; both fine. Errors throw.
	private void post(String path, String id) {
		restClient.post().uri(path, id).retrieve().toBodilessEntity();
	}

	private static Instant parseTime(String value) {
		if (value == null || value.isEmpty() || value.equals(ZERO_TIME)) return null;
		return Instant.parse(value);
	}

	// Only the fields Pulse reads; Docker's JSON uses PascalCase.

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ListEntry(@JsonProperty("Id") String id) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Inspect(
			@JsonProperty("Id") String id,
			@JsonProperty("Name") String name,
			@JsonProperty("RestartCount") int restartCount,
			@JsonProperty("State") State state,
			@JsonProperty("Config") Config config) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record State(
			@JsonProperty("Status") String status,
			@JsonProperty("StartedAt") String startedAt,
			@JsonProperty("FinishedAt") String finishedAt,
			@JsonProperty("Health") Health health) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Health(@JsonProperty("Status") String status) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Stats(
			@JsonProperty("cpu_stats") Cpu cpu,
			@JsonProperty("memory_stats") Memory memory,
			@JsonProperty("networks") Map<String, Network> networks,
			@JsonProperty("blkio_stats") Blkio blkio) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Cpu(@JsonProperty("cpu_usage") CpuUsage usage, @JsonProperty("system_cpu_usage") long system) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CpuUsage(@JsonProperty("total_usage") long total) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Memory(
			@JsonProperty("usage") long usage,
			@JsonProperty("limit") long limit,
			@JsonProperty("stats") Map<String, Long> stats) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Network(@JsonProperty("rx_bytes") long rxBytes, @JsonProperty("tx_bytes") long txBytes) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Blkio(@JsonProperty("io_service_bytes_recursive") List<BlkioEntry> ioServiceBytes) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BlkioEntry(@JsonProperty("op") String op, @JsonProperty("value") long value) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Config(
			@JsonProperty("Image") String image,
			@JsonProperty("Labels") Map<String, String> labels) {
	}
}
