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
 * containers, start/stop/restart one.
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
	record Config(
			@JsonProperty("Image") String image,
			@JsonProperty("Labels") Map<String, String> labels) {
	}
}
