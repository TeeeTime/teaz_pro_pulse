package pro.teaz.pulse.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DockerEngineClientTests {

	private static final String BASE = "http://docker-proxy:2375/v1.44";

	private MockRestServiceServer server;
	private DockerEngineClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new DockerEngineClient(builder.build());
	}

	@Test
	void listsAndInspectsLabeledContainers() {
		server.expect(requestTo(allOf(
						startsWith(BASE + "/containers/json?all=true&filters="),
						containsString("pro.teaz.pulse.app"))))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("""
						[{"Id": "aaa", "Names": ["/portal-backend-1"], "State": "running"},
						 {"Id": "bbb", "Names": ["/traefik"], "State": "exited"}]
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/containers/aaa/json"))
				.andRespond(withSuccess("""
						{"Id": "aaa", "Name": "/portal-backend-1", "RestartCount": 2,
						 "State": {"Status": "running", "Running": true,
						           "StartedAt": "2026-10-17T08:15:30.123456789Z",
						           "FinishedAt": "0001-01-01T00:00:00Z",
						           "Health": {"Status": "healthy", "FailingStreak": 0}},
						 "Config": {"Image": "teaz_pro_portal-backend",
						            "Labels": {"pro.teaz.pulse.app": "Portal",
						                       "com.docker.compose.service": "backend"}}}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/containers/bbb/json"))
				.andRespond(withSuccess("""
						{"Id": "bbb", "Name": "/traefik", "RestartCount": 0,
						 "State": {"Status": "exited", "Running": false,
						           "StartedAt": "2026-10-01T10:00:00Z",
						           "FinishedAt": "2026-10-17T09:00:00Z"},
						 "Config": {"Image": "traefik:v3.4",
						            "Labels": {"pro.teaz.pulse.app": "Traefik",
						                       "pro.teaz.pulse.protected": "true"}}}
						""", MediaType.APPLICATION_JSON));

		List<DockerContainer> containers = client.listManagedContainers();

		assertThat(containers).containsExactly(
				new DockerContainer("aaa", "portal-backend-1", "Portal", "backend", "teaz_pro_portal-backend",
						"running", "healthy", Instant.parse("2026-10-17T08:15:30.123456789Z"), null, 2, false),
				// no compose label: service falls back to the container name; no healthcheck: health null
				new DockerContainer("bbb", "traefik", "Traefik", "traefik", "traefik:v3.4",
						"exited", null, Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-17T09:00:00Z"),
						0, true));
		server.verify();
	}

	@Test
	void readsStatsOfCgroupV2() {
		server.expect(requestTo(BASE + "/containers/aaa/stats?stream=false&one-shot=true"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("""
						{"read": "2026-10-23T12:00:00Z",
						 "cpu_stats": {"cpu_usage": {"total_usage": 5000000000}, "system_cpu_usage": 900000000000,
						               "online_cpus": 4},
						 "memory_stats": {"usage": 300000000, "limit": 8000000000,
						                  "stats": {"inactive_file": 50000000, "anon": 200000000}},
						 "networks": {"eth0": {"rx_bytes": 1000, "tx_bytes": 200},
						              "eth1": {"rx_bytes": 500, "tx_bytes": 100}},
						 "blkio_stats": {"io_service_bytes_recursive": [
						     {"major": 8, "minor": 0, "op": "read", "value": 4096},
						     {"major": 8, "minor": 0, "op": "write", "value": 8192}]}}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.stats("aaa")).isEqualTo(
				new ContainerStats(5_000_000_000L, 900_000_000_000L, 250_000_000, 8_000_000_000L, 1500, 300, 4096, 8192));
		server.verify();
	}

	@Test
	void readsStatsOfCgroupV1WithoutNetworkOrBlockIo() {
		server.expect(requestTo(BASE + "/containers/bbb/stats?stream=false&one-shot=true"))
				.andRespond(withSuccess("""
						{"cpu_stats": {"cpu_usage": {"total_usage": 7}, "system_cpu_usage": 100},
						 "memory_stats": {"usage": 1000, "limit": 4000, "stats": {"total_inactive_file": 300}},
						 "blkio_stats": {"io_service_bytes_recursive": [
						     {"op": "Read", "value": 10}, {"op": "Write", "value": 20}, {"op": "Total", "value": 30}]}}
						""", MediaType.APPLICATION_JSON));

		// network_mode: host has no "networks"
		assertThat(client.stats("bbb")).isEqualTo(new ContainerStats(7, 100, 700, 4000, 0, 0, 10, 20));
	}

	@Test
	void postsActions() {
		server.expect(requestTo(BASE + "/containers/aaa/start")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));
		server.expect(requestTo(BASE + "/containers/aaa/stop?t=10")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));
		// 304: already restarted/stopped, not an error
		server.expect(requestTo(BASE + "/containers/aaa/restart?t=10")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.NOT_MODIFIED));

		client.start("aaa");
		client.stop("aaa");
		client.restart("aaa");
		server.verify();
	}
}
